package com.qwen2api.tx.core

import android.content.Context

/** 应用配置（移植自 lib/config.js 的 DEFAULTS + config.json） */
data class GatewayConfig(
    val port: Int = DEFAULT_PORT,
    val host: String = DEFAULT_HOST,          // 127.0.0.1 仅本机；0.0.0.0 允许局域网
    val apiKey: String = "",
    val qwenToken: String = "",
    val defaultModel: String = DEFAULT_MODEL,
    val thinking: Boolean = true,
    val throttleMs: Int = 3200,               // 请求最小间隔（防 Baxia 风控）
    val autoOpen: Boolean = true,
    /**
     * 图像生成遇到可重试错误（上游 5xx / 连接抖动 / 空结果）时的重试次数。
     *
     * 为什么做成配置而不是写死 2：
     *  - 上游抖动是**概率性**的，重试能显著降低「调用方看到 502、但其实只差一次重试」
     *    的概率，这是默认值不取 0 的理由；
     *  - 但重试会成倍放大请求量（尤其 `n>1`），而风控正是按请求数判定的。
     *    账号已被风控时，调用方最需要的是**关掉重试**，而不是让它打得更凶。
     */
    val imageRetryCount: Int = DEFAULT_IMAGE_RETRY,
    /** 图像重试的首次退避毫秒（指数递增，封顶 8s） */
    val imageRetryBackoffMs: Int = DEFAULT_IMAGE_RETRY_BACKOFF,
    /**
     * 全局 System Prompt：网关侧强制附加到每一次对话上的系统指令。
     *
     * 和调用方自己发的 `messages[].role=system` 是**两码事**，两者会合并：
     *  - 调用方发的 system = 单次请求的临时指令，客户端换会话就没了；
     *  - 这里的 systemPrompt = 网关级常驻人格/约束，所有客户端、所有会话吃同一份，
     *    适合放「回答必须用中文」「禁止输出免责声明」「你是 xx 角色的助手」这类硬约束。
     *
     * 空串 = 不注入（默认），行为与改动前完全一致。
     */
    val systemPrompt: String = "",
    /** 全局 System Prompt 开关。文本留着、开关关掉 = 暂时停用而不丢内容。 */
    val systemPromptEnabled: Boolean = false,
    /**
     * 注入方式：
     *  - [SYSTEM_PROMPT_MERGE]（默认）与调用方自己的 system 合并；
     *  - [SYSTEM_PROMPT_REPLACE] 覆盖掉调用方发的 system —— 用于「客户端偷偷塞了
     *    自己的破提示词、行为不可控」的场景。只覆盖 system，user 内容不动。
     */
    val systemPromptMode: String = SYSTEM_PROMPT_MERGE,
) {
    fun copyWith(
        port: Int? = null,
        host: String? = null,
        apiKey: String? = null,
        qwenToken: String? = null,
        defaultModel: String? = null,
        thinking: Boolean? = null,
        throttleMs: Int? = null,
        autoOpen: Boolean? = null,
        imageRetryCount: Int? = null,
        imageRetryBackoffMs: Int? = null,
        systemPrompt: String? = null,
        systemPromptEnabled: Boolean? = null,
        systemPromptMode: String? = null,
    ): GatewayConfig = GatewayConfig(
        port = port ?: this.port,
        host = host ?: this.host,
        apiKey = apiKey ?: this.apiKey,
        qwenToken = qwenToken ?: this.qwenToken,
        defaultModel = defaultModel ?: this.defaultModel,
        thinking = thinking ?: this.thinking,
        throttleMs = throttleMs ?: this.throttleMs,
        autoOpen = autoOpen ?: this.autoOpen,
        imageRetryCount = imageRetryCount ?: this.imageRetryCount,
        imageRetryBackoffMs = imageRetryBackoffMs ?: this.imageRetryBackoffMs,
        systemPrompt = systemPrompt ?: this.systemPrompt,
        systemPromptEnabled = systemPromptEnabled ?: this.systemPromptEnabled,
        systemPromptMode = systemPromptMode ?: this.systemPromptMode,
    )

    companion object {
        const val DEFAULT_PORT = 8818
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_MODEL = "qwen3.8-max"
        const val VERSION = "1.2.0"

        /** 默认重试 2 次（1.5s 起指数退避，总等待上限约 4.5s） */
        const val DEFAULT_IMAGE_RETRY = 2

        /**
         * 重试次数上限。
         *
         * 不是「越多越好」：每多一次重试就多一次上游出图请求，账号被风控时
         * 只会把滑块验证触发得更快；5 次已经是「网络抖动」与「被限流」的分界。
         */
        const val MAX_IMAGE_RETRY = 5

        /** 重试首次退避（ms） */
        const val DEFAULT_IMAGE_RETRY_BACKOFF = 1500

        /** 全局 System Prompt 注入方式：与调用方 system 合并 */
        const val SYSTEM_PROMPT_MERGE = "merge"

        /** 全局 System Prompt 注入方式：覆盖调用方 system */
        const val SYSTEM_PROMPT_REPLACE = "replace"

        /**
         * 全局 System Prompt 长度上限（字符）。
         *
         * 必须有上限：这个字段会被**每条**请求注进上游，超长内容一次就把上下文顶满，
         * 表现为所有会话集体变慢甚至报上游超长错误；而且它是常驻的，不会随会话结束消失。
         * 8000 字符已足够放一份详细人格设定。
         */
        const val MAX_SYSTEM_PROMPT_CHARS = 8000
    }
}

/**
 * 配置仓库门面：对 UI/服务层暴露统一 API，内部委托给 [ConfigRepository]。
 * 生产使用 SharedPreferences 实现，测试可注入内存实现。
 */
object ConfigStore {

    @Volatile
    private var defaultRepo: ConfigRepository? = null

    /** 由 Application/Service 注入；未注入时按 context 惰性构造 */
    fun repository(context: Context): ConfigRepository {
        defaultRepo?.let { return it }
        synchronized(this) {
            defaultRepo?.let { return it }
            val repo = PrefsConfigRepository(context)
            defaultRepo = repo
            return repo
        }
    }

    /** 测试专用：替换仓储实现 */
    fun overrideRepository(repo: ConfigRepository?) {
        synchronized(this) { defaultRepo = repo }
    }

    fun load(context: Context): GatewayConfig = repository(context).load()

    fun save(context: Context, cfg: GatewayConfig): GatewayConfig =
        repository(context).save(cfg).let { cfg }

    fun update(context: Context, patch: (GatewayConfig) -> GatewayConfig): GatewayConfig =
        repository(context).update(patch)

    fun generateApiKey(): String = "sk-qpp-" + Util.randomBase64Url(24)

    /**
     * 归一化 System Prompt 注入方式。
     *
     * 为什么不直接信任存储里的字符串：这个值来自 SharedPreferences / admin API /
     * 手工改的配置文件，任何一种都可能写进 "REPLACE"、"replace " 这类脏值。
     * 脏值若被当成未知模式处理，注入行为会退化成"静默不注入"，
     * 而用户看到开关是开的 —— 这种"开关说谎"最难排查。因此统一收敛：
     * 只有明确等于 replace 才覆盖，其余一律合并。
     */
    fun normalizeSystemPromptMode(raw: String?): String =
        if (raw?.trim()?.lowercase() == "replace") {
            GatewayConfig.SYSTEM_PROMPT_REPLACE
        } else {
            GatewayConfig.SYSTEM_PROMPT_MERGE
        }

    /**
     * 清洗 System Prompt：去 BOM / 零宽字符、统一换行、限长。
     *
     * 为什么要去零宽字符：这段文本来自用户从网页/文档里复制，
     * 常混入零宽空格与 BOM，而它们会被原样送进上游 —— 模型看到的是
     * "看起来正常但每句话中间有不可见字符"的指令，效果随机变差且无法解释。
     */
    fun sanitizeSystemPrompt(raw: String?): String {
        val s = raw.orEmpty()
            .replace(Regex("[\uFEFF\u200B-\u200D\u2060]"), "")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()
        return if (s.length > GatewayConfig.MAX_SYSTEM_PROMPT_CHARS) {
            s.substring(0, GatewayConfig.MAX_SYSTEM_PROMPT_CHARS)
        } else {
            s
        }
    }

    fun maskToken(token: String?): String {
        if (token.isNullOrBlank()) return ""
        if (token.length <= 12) return token.take(4) + "****"
        return token.take(10) + "****" + token.takeLast(6)
    }

    /** 识别 token 类型：jwt / cookie / unknown / empty */
    fun detectTokenType(token: String?): String {
        val t = token?.trim().orEmpty()
        if (t.isEmpty()) return "empty"
        if (Regex("^eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+").containsMatchIn(t)) return "jwt"
        if (t.contains("=") && t.contains(";")) return "cookie"
        if (t.length > 100) return "jwt"
        return "unknown"
    }

    data class SanitizeResult(
        val ok: Boolean,
        val token: String = "",
        val type: String = "unknown",
        val note: String = "",
        val message: String = "",
    )

    /**
     * 清洗并校验用户粘贴的 token（移植自 sanitizeQwenToken）。
     * 背景：用户常把 token 连同中文说明/引号一起粘贴，
     * 而 HTTP 头只允许 Latin-1 字符，含中文会让请求直接抛异常。
     */
    fun sanitizeQwenToken(raw: String?): SanitizeResult {
        var s = raw.orEmpty()
            .replace(Regex("[\uFEFF\u200B-\u200D\u2060]"), "")
            .trim()
        if (s.isEmpty()) return SanitizeResult(false, message = "token 不能为空")

        // 去掉成对包裹的引号（含中文弯引号）
        s = s.replace(Regex("^[\"'\u201C\u201D\u2018\u2019]+"), "")
            .replace(Regex("[\"'\u201C\u201D\u2018\u2019]+$"), "")
            .trim()

        // 1) 优先：从任意混杂文本中提取 JWT 本体
        val jwt = Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}")
            .find(s)?.value
        if (jwt != null) {
            return SanitizeResult(
                ok = true,
                token = jwt,
                type = "jwt",
                note = if (jwt == s) "" else "已自动从粘贴内容中提取 token 本体",
            )
        }

        // 2) 无 JWT 结构：逐字符校验（HTTP 头只能 Latin-1），给出精确位置
        for (i in s.indices) {
            if (s[i].code > 0xFF) {
                return SanitizeResult(
                    ok = false,
                    message = "粘贴内容第 ${i + 1} 个字符是中文或特殊字符「${s[i]}」，HTTP 请求头不允许。" +
                        "请只复制 token 本体：打开 chat.qwen.ai → F12 → Console 输入 localStorage.token 回车，" +
                        "复制输出的 eyJ 开头字符串（不要带中文说明或引号）。",
                )
            }
        }
        if (s.any { it.code < 0x20 || it.code == 0x7F }) {
            return SanitizeResult(false, message = "token 含换行或控制字符，请确认复制完整且仅为一行")
        }

        // 3) Cookie 串里的 token= 值
        val cm = Regex("(?:^|;)\\s*token\\s*=\\s*([^;]+)").find(s)
        if (cm != null && cm.groupValues[1].trim().isNotEmpty()) {
            val v = cm.groupValues[1].trim()
            return SanitizeResult(
                ok = true, token = v, type = detectTokenType(v), note = "已从 Cookie 串中提取 token 值",
            )
        }

        // 4) 兜底：既非 JWT 也非 Cookie 的裸字符串，本地拦下明显不是凭证的内容
        val type = detectTokenType(s)
        if (type == "unknown") {
            return SanitizeResult(
                ok = false,
                message = "粘贴内容看起来不是 Qwen Token（既不是 eyJ 开头的 JWT，也不是 Cookie 串）。" +
                    "获取方式：点「用手机浏览器登录 Qwen」自动取回；" +
                    "或浏览器登录 chat.qwen.ai 后在 Console 输入 localStorage.token 回车，" +
                    "复制输出的 eyJ 开头字符串；或复制请求头里完整的 cookie 值。",
            )
        }
        // 4b) 是 Cookie 形状，但里面没有任何凭证项（常见于「还没登录成功就点了取回」）
        if (type == "cookie" && !containsCredential(s)) {
            return SanitizeResult(
                ok = false,
                message = "这段 Cookie 里没有找到登录凭证（缺少 token 项）。" +
                    "请先在浏览器里完成 Qwen 登录（页面能正常对话），再点「取回 Cookie 并导入」。",
            )
        }
        return SanitizeResult(ok = true, token = s, type = type)
    }

    /** Cookie 串里是否真的含凭证：有 token 项，或任意 cookie 值/整串里带 JWT */
    fun containsCredential(cookieHeader: String): Boolean {
        if (Regex("(?:^|;)\\s*token\\s*=\\s*[^;\\s]", RegexOption.IGNORE_CASE).containsMatchIn(cookieHeader)) {
            return true
        }
        return Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}").containsMatchIn(cookieHeader)
    }

    /** 调试/备份用：导出为 JSON */
    fun toJson(cfg: GatewayConfig): org.json.JSONObject {
        val o = org.json.JSONObject()
        o.put("port", cfg.port)
        o.put("host", cfg.host)
        o.put("apiKey", cfg.apiKey)
        o.put("qwenToken", cfg.qwenToken)
        o.put("defaultModel", cfg.defaultModel)
        o.put("thinking", cfg.thinking)
        o.put("throttleMs", cfg.throttleMs)
        o.put("autoOpen", cfg.autoOpen)
        // 出图重试策略也进导出：备份/迁移时漏掉它，用户会遇到
        // 「换机后偶发 502 变多」这种没有任何线索的行为差异。
        o.put("imageRetryCount", cfg.imageRetryCount)
        o.put("imageRetryBackoffMs", cfg.imageRetryBackoffMs)
        // System Prompt 也进导出：它是用户手工写的资产，导出/迁移时漏掉就得重写。
        o.put("systemPrompt", cfg.systemPrompt)
        o.put("systemPromptEnabled", cfg.systemPromptEnabled)
        o.put("systemPromptMode", cfg.systemPromptMode)
        return o
    }
}
