package com.qwen2api.tx.core

/**
 * 上游风控/限流的统一识别。
 *
 * ## 为什么放在 core 而不是 [com.qwen2api.tx.server.GatewayRouter]
 *
 * 图片客户端（[QwenImageClient]）在 core 包，不能反向依赖 server 包；而
 * 「哪些措辞算风控」这件事一旦有两份实现，必然分叉。分叉的代价实测过：
 * 文本链路已识别阿里盾处罚页（`RGV587_ERROR` / `punish`），图片链路却把它
 * 当成「上游瞬时故障」按 2.5s 起步退避重试 4 次 —— 同一请求风控态下耗时
 * 8s 变 58s，错误一模一样，而且持续打风控只会把封禁钉得更牢。
 *
 * 因此判据只留一份在这里，server 侧的 `isRiskControlBlock` 直接转发过来。
 */
object RiskControl {

    /**
     * 风控/限流归一化后的错误码。
     *
     * 取一个**明确不在可重试名单里**的码，是为了让「命中风控」这件事本身
     * 就决定「不要重试」，而不是靠每条链路各自再判一次。
     */
    const val THROTTLE_CODE = "THROTTLE_RATE_LIMIT"

    /**
     * 广义风控判据（文本链路用）。
     *
     *  - 覆盖 429 / rate limit / 风控 / 滑块 / baxia(阿里盾) / 稍后 / 频繁 等措辞；
     *  - **包含 `UPSTREAM_EMPTY`**（见 [isRiskControlBlock]）：上游被风控掐断时
     *    最典型的表现就是「流正常建立但没有任何内容」，不识别会静默失败（无回应）；
     *  - 明确排除业务类错误（鉴权/参数/文件），避免无意义重试。
     */
    private val THROTTLE_PAT = Regex(
        "429|rate.?limit|too.?many|throttl|风控|频率|滑块|baxia|busy|overload|try.?again|稍后|频繁|" +
            "fail_sys_user_validate|rgv587|punish|x5sec|被挤爆",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 窄口径判据：只认**阿里盾处罚页的确定性指纹**（图片链路用）。
     *
     * 为什么图片链路不能直接用 [THROTTLE_PAT]：那里的 `UPSTREAM_5xx` 是
     * **有意重试**的（上游排队/抖动重试一次大概率就好），而广义词表里的
     * `busy` / `overload` / `稍后` / `try again` 恰好是 5xx 正文的高频词，
     * 用广义表会把可重试故障误判成风控、直接放弃重试。
     *
     * 处罚页的指纹是确定且排他的：`_tmd_/punish` + `x5secdata` 参数，
     * 外加 `FAIL_SYS_USER_VALIDATE` / `RGV587_ERROR` 两个返回码。
     *
     * ## `rate.?limit` / `too.?many` / `429` 为什么**不在**这里
     *
     * 它们曾在这张表里，代价是实测出来的：上游额度用尽时的响应是
     * `{"data":{"code":"RateLimited","details":"You've reached the upper limit
     * for today's usage. Please try again tomorrow."}}` —— `RateLimited` 里的
     * "rate…limit" 命中了本表，于是**日额度用尽被当成滑块风控**，给用户的
     * 处置建议是「去过滑块验证」，而真实解法是等明天或换账号。同一份响应被
     * 归成 [THROTTLE_CODE] 后还顺带落进"不重试"分支，方向完全错了。
     *
     * 「限流」是**不确定**措辞：它可以指瞬时 429（重试有效），也可以指日配额
     * （重试无用），因此不能靠措辞猜 —— 配额有独立的确定性指纹，见 [QUOTA_PAT]。
     */
    private val UPSTREAM_RISK_PAT = Regex(
        "fail_sys_user_validate|rgv587|punish|x5sec|被挤爆|滑块",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 配额用尽归一化后的错误码。
     *
     * 与 [THROTTLE_CODE] 分开的理由：两者对用户是**完全不同**的处置建议
     * （等明天 / 换账号 vs. 过滑块 / 冷却重试），混用会让"提示"这件唯一
     * 有用户价值的东西变成误导。
     */
    const val QUOTA_CODE = "UPSTREAM_QUOTA_EXHAUSTED"

    /**
     * 上游「今日额度用尽」的指纹（阿里盾风控之外的独立一类）。
     *
     * 只认确定性措辞，不认裸 `quota` / `limit`：那些词在正常响应与别的错误里
     * 都可能出现，误判的代价是把「重试一次就好」的故障钉成「今天别试了」。
     */
    private val QUOTA_PAT = Regex(
        // `ratelimited` 是上游给「今日额度用尽」用的**错误码原样 token**
        // （形如 `{"data":{"code":"RateLimited","details":"You've reached the upper
        // limit for today's usage.","num":12}}`）。它连写、无空格，与真正的
        // 限流措辞（`rate limit exceeded`）不同形，因此可以安全地单列一条。
        "ratelimited|reached the upper limit|today'?s usage|today'?s limit|daily (usage )?limit|" +
            "usage (limit|quota)|quota (exceeded|exhausted|reached)|额度(已)?(用尽|耗尽|超限)|" +
            "今日(额度|次数|上限)|已用完",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 上游原文是否是「配额用尽」。
     *
     * 调用方应在**风控判定之前**先问这个：上游把日配额用尽编码成了
     * `code: "RateLimited"`，字面上与"限流"同族，先判风控必然误判。
     */
    fun isQuotaExhausted(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        return QUOTA_PAT.containsMatchIn(text)
    }

    /**
     * 配额用尽时给用户的提示。
     *
     * 刻意**不出现"滑块"二字**：额度用尽时去点滑块验证是无用动作，提示里
     * 一旦出现该词，用户就会照做、然后得到"还是不行"的结论，反过来怀疑网关。
     * 同理必须点明"不是风控"，否则用户会去刷新浏览器重试。
     */
    const val QUOTA_HINT =
        "上游账号今日额度/次数已用尽（上游原文：You've reached the upper limit for today's usage）。" +
            "这不是网关故障，也不是安全验证：重试无效，请等待次日额度重置，或改用其他账号。"

    private val NON_RETRYABLE = setOf(
        "AUTH_FAILED", "NO_TOKEN", "BAD_REQUEST", "FILE_TOO_LARGE",
        "PARSE_FAILED", "TOKEN_INVALID_CHARS", "HEADER_INVALID_CHARS", "NETWORK_TIMEOUT",
    )

    /** 风控命中判定（只看面向用户的错误文案） */
    fun isRiskControlBlock(msg: String?): Boolean {
        if (msg.isNullOrEmpty()) return false
        if (msg in NON_RETRYABLE) return false
        // 配额用尽必须先在风控之前被摘出去：它属于「等明天」，不属于「过滑块」，
        // 而 [THROTTLE_PAT] 的 `rate.?limit` / `稍后` 措辞会把两者混成一类。
        if (isQuotaExhausted(msg)) return false
        return THROTTLE_PAT.containsMatchIn(msg)
    }

    /** 带错误码的判定（优先看 code，其次看 message）。 */
    fun isRiskControlBlock(code: String?, msg: String?): Boolean {
        if (!code.isNullOrEmpty()) {
            if (code in NON_RETRYABLE) return false
            if (code == QUOTA_CODE) return false
            if (code == "UPSTREAM_429" || code == "UPSTREAM_EMPTY" || code == THROTTLE_CODE) {
                return true
            }
            if (isQuotaExhausted(code)) return false
            if (THROTTLE_PAT.containsMatchIn(code)) return true
        }
        return isRiskControlBlock(msg)
    }

    /**
     * 上游原文里是否含风控指纹（窄口径）。
     *
     * 用于「手里有上游原始响应体」的场景 —— 此时不需要靠泛化措辞猜，
     * 只认处罚页指纹即可，避免把 5xx 正文里的 busy / 稍后 误判成风控。
     */
    fun hasUpstreamRiskSignature(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        return UPSTREAM_RISK_PAT.containsMatchIn(text)
    }

    /**
     * 把上游原文归一成**一个**原因码；不属于风控/配额则返回 null。
     *
     * ## 为什么必须有这个单点入口
     *
     * 「配额用尽」与「风控」给用户的处置建议正好相反（等明天 vs 过滑块），
     * 而上游把两者编码进**同一个** `code: "RateLimited"` 家族。判错方向的代价
     * 实测过一次：日额度用尽被归成风控，用户拿到的建议是"去过滑块验证"，
     * 照做当然没用，因为额度要到次日才重置。
     *
     * 靠调用方各自「记得先判配额再判风控」是守不住的 —— 调用点有 5 处
     *（建会话 / 非 200 / 非 SSE 200 / 流内错误帧 / 流尾部），漏一处就退化成原样。
     * 因此优先级只在这里定义一次，调用方只消费结果。
     *
     * 判定顺序：配额 → 窄口径风控指纹 → 广义风控。
     */
    fun blockCode(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        if (isQuotaExhausted(text)) return QUOTA_CODE
        if (hasUpstreamRiskSignature(text)) return THROTTLE_CODE
        if (isRiskControlBlock(text)) return THROTTLE_CODE
        return null
    }

    /** 按 [blockCode] 的结果取对应提示（未识别时给通用风控提示）。 */
    fun hintFor(code: String?): String = if (code == QUOTA_CODE) QUOTA_HINT else HINT

    /** 折中等待阶梯（毫秒）：8s / 15s / 25s —— 兼顾成功率与响应速度。 */
    val WAIT_STEPS_MS = longArrayOf(8_000, 15_000, 25_000)

    /** 风控拦截时给用户的友好中文提示（替代原始的处罚页 JSON）。 */
    const val HINT =
        "上游触发了安全验证/限流（风控）。这通常因短时间内请求过多导致，并非网关故障。" +
            "请稍后重试；若持续出现，请在浏览器打开 chat.qwen.ai 手动过一次滑块验证。"
}
