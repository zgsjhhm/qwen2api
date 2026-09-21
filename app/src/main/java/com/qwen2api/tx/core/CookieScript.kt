package com.qwen2api.tx.core

/**
 * 外部浏览器登录方案里，贴在浏览器地址栏执行的「取凭证并回调」脚本。
 *
 * 工作原理（这是外部浏览器路线下唯一能做到自动闭环的方式）：
 *  1. 用户在浏览器登录 chat.qwen.ai
 *  2. 执行本脚本：读取 document.cookie，拼成 qwen2api://callback?c=<cookie>
 *  3. 脚本让浏览器跳转到该自定义 scheme
 *  4. Android 把跳转派发给本 App 的 Activity（已注册 intent-filter）
 *  5. App 在 onNewIntent 里解析出 cookie → 自动校验并保存 ✅ 全自动完成
 *
 * 兼容性：
 *  - 地址栏 `javascript:` 伪协议在多数移动浏览器可用；新版 Chrome 粘贴时会剥掉前缀，
 *    故脚本尽量短，便于手动补 `javascript:`。
 *  - 若浏览器拦截 scheme 跳转，脚本会回退到 prompt 弹框，用户可手动复制后
 *    用 App 里的「从剪贴板导入」兜底。
 */
object CookieScript {

    /** 自定义 scheme：与 AndroidManifest 中注册的保持一致 */
    const val SCHEME = "qwen2api"
    const val CALLBACK_HOST = "callback"
    const val CALLBACK_FULL = "$SCHEME://$CALLBACK_HOST"

    /** 回调里承载凭证的查询参数名 */
    const val PARAM_COOKIE = "c"

    /** 凭证最短长度：过短的一律视为无效，避免误导入 */
    private const val MIN_CREDENTIAL_LEN = 20

    /**
     * 地址栏主脚本（自动回调版）。
     *
     * 逻辑：
     *  - 没登录（cookie 里没有 token=）→ alert 提示，不跳转
     *  - 有凭证 → 跳转 qwen2api://callback?c=<encodeURIComponent(cookie)>
     *  - 若跳转后 1.2s 内页面没有离开（说明 scheme 被拦截）→ 回退 prompt 让用户手动复制
     */
    fun addressBarScript(): String = buildString {
        append("javascript:(function(){")
        append("var c=document.cookie;")
        append("if(!c||c.indexOf('token=')<0){")
        append("alert('未找到登录 token，请先确认已登录 chat.qwen.ai');return;}")
        append("var u='$CALLBACK_FULL?$PARAM_COOKIE='+encodeURIComponent(c);")
        append("var t=Date.now();")
        append("location.href=u;")
        append("setTimeout(function(){")
        append("if(Date.now()-t<2500&&!document.hidden){")
        append("prompt('未能自动跳回 App，请复制这段凭证后回到 App 导入',c);}")
        append("},1200);")
        append("})()")
    }

    /**
     * 固定脚本文本（供界面展示与一键复制）。
     * 注意：这是常量而非函数调用，界面展示与剪贴板写入必须是同一份文本。
     */
    val ADDRESS_BAR_SCRIPT: String = addressBarScript()

    /** 手动兜底：不用剪贴板 API，直接弹框让用户全选复制 */
    val PROMPT_SCRIPT: String =
        "javascript:prompt('复制这段凭证',document.cookie)"

    /**
     * 解析 App 被回调时的 URI，取出凭证。
     *
     * @param uri 形如 `qwen2api://callback?c=<urlencoded cookie>`
     * @return 解码后的凭证串；不是本 App 的回调或凭证无效时返回 null
     *
     * ## 为什么必须校验 host
     * `qwen2api://` 是**全局命名空间**：任何应用、任何网页（`<a href>` 或
     * `location.href`）都能构造并触发这个 URI。也就是说 scheme 只代表
     * 「这是发给本 App 的」，**不代表「这是本 App 的登录脚本发的」**。
     *
     * 如果只判断 scheme 就直接取凭证，攻击者只要构造
     * `qwen2api://anything/?c=<20 字符>`，就能把**自己的**凭证注入用户 App；
     * 之后用户的提问会带着攻击者的登录态发往上游 —— 会话串号与数据泄漏。
     * 因此身份必须由 host（[CALLBACK_HOST]）承担，并且校验必须放在解析器内部，
     * 不能指望调用方记得做。
     */
    fun parseCallback(uri: String?): String? {
        val raw = uri?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (!raw.startsWith("$SCHEME://", ignoreCase = true)) return null
        // host 校验：只接受 qwen2api://callback/...，拒绝 userinfo、端口、
        // 子串假名（callback.attacker.com / evil-callback / callback@evil.com）
        val host = authorityHost(raw) ?: return null
        if (!host.equals(CALLBACK_HOST, ignoreCase = true)) return null

        // 手动解析，避免依赖 android.net.Uri 而影响纯 JVM 测试
        val queryStart = raw.indexOf('?')
        if (queryStart < 0) {
            // 允许把凭证直接放在 host 之后：qwen2api://callback/<cookie>
            val afterAuthority = raw.drop("$SCHEME://".length)
                .dropWhile { it != '/' }
                .removePrefix("/")
            return afterAuthority.takeIf { it.length >= MIN_CREDENTIAL_LEN }
        }

        val query = raw.substring(queryStart + 1)
        query.split('&').forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@forEach
            val k = pair.substring(0, idx)
            if (k != PARAM_COOKIE) return@forEach
            val v = pair.substring(idx + 1)
            val decoded = runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
            if (decoded.length >= MIN_CREDENTIAL_LEN) return decoded
        }
        return null
    }

    /**
     * 取出 URI 的 authority 里的真实 host。
     *
     * 规则（对齐 RFC 3986 的简化版，够用且不引依赖）：
     *  - authority 截止到第一个 `/`、`?` 或 `#`
     *  - 丢掉 userinfo（`@` 之前）
     *  - 丢掉端口（`:` 之后）
     *
     * @return host；URI 里没有 authority 时返回 null
     */
    private fun authorityHost(raw: String): String? {
        // 不能用 substringAfter("$SCHEME://")：那是大小写敏感的，
        // 而 scheme 匹配本身忽略大小写（QWEN2API:// 也是合法回调）
        val rest = raw.substring(SCHEME.length + 3)
        if (rest.isEmpty()) return null
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        val host = authority.substringAfterLast('@').substringBefore(':')
        return host.ifEmpty { null }
    }

    /**
     * 判断剪贴板/回调内容是否像可用的 Qwen 凭证。
     * 与 [ClipboardUtil.looksLikeCredential] 保持一致的判定口径。
     */
    fun looksLikeCredential(text: String?): Boolean {
        val t = text?.trim().orEmpty()
        if (t.length < MIN_CREDENTIAL_LEN) return false
        if (Regex("(?:^|[;\\s])token\\s*=\\s*[^;\\s]", RegexOption.IGNORE_CASE).containsMatchIn(t)) {
            return true
        }
        return Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}")
            .containsMatchIn(t)
    }

    /** 供卡片里展示的简短说明 */
    val HOW_TO = """
        1. 点「打开浏览器登录」跳转系统浏览器登录 chat.qwen.ai
        2. 登录成功后点「一键取凭证并返回」，脚本会自动跳回 App
        3. 回到 App 后凭证自动导入，无需手动复制
    """.trimIndent()
}
