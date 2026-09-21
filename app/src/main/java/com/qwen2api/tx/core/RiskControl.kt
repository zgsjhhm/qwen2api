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
     */
    private val UPSTREAM_RISK_PAT = Regex(
        "fail_sys_user_validate|rgv587|punish|x5sec|被挤爆|滑块|rate.?limit|too.?many|429",
        RegexOption.IGNORE_CASE,
    )

    private val NON_RETRYABLE = setOf(
        "AUTH_FAILED", "NO_TOKEN", "BAD_REQUEST", "FILE_TOO_LARGE",
        "PARSE_FAILED", "TOKEN_INVALID_CHARS", "HEADER_INVALID_CHARS", "NETWORK_TIMEOUT",
    )

    /** 风控命中判定（只看面向用户的错误文案） */
    fun isRiskControlBlock(msg: String?): Boolean {
        if (msg.isNullOrEmpty()) return false
        if (msg in NON_RETRYABLE) return false
        return THROTTLE_PAT.containsMatchIn(msg)
    }

    /** 带错误码的判定（优先看 code，其次看 message）。 */
    fun isRiskControlBlock(code: String?, msg: String?): Boolean {
        if (!code.isNullOrEmpty()) {
            if (code in NON_RETRYABLE) return false
            if (code == "UPSTREAM_429" || code == "UPSTREAM_EMPTY" || code == THROTTLE_CODE) {
                return true
            }
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

    /** 折中等待阶梯（毫秒）：8s / 15s / 25s —— 兼顾成功率与响应速度。 */
    val WAIT_STEPS_MS = longArrayOf(8_000, 15_000, 25_000)

    /** 风控拦截时给用户的友好中文提示（替代原始的处罚页 JSON）。 */
    const val HINT =
        "上游触发了安全验证/限流（风控）。这通常因短时间内请求过多导致，并非网关故障。" +
            "请稍后重试；若持续出现，请在浏览器打开 chat.qwen.ai 手动过一次滑块验证。"
}
