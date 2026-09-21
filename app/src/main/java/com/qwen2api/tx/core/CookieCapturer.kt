package com.qwen2api.tx.core

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 用内置 WebView 打开 Qwen 登录页，自动把登录 Cookie 取回来。
 *
 * 设计要点：
 *  - 与 App 共用一个 WebView CookieManager（系统全局，按进程隔离），
 *    因此用户登录一次后，后续再打开登录页通常已是登录态，可一键取回。
 *  - 登录成功后 Qwen 会把 token 写进 cookie（名 [TOKEN_COOKIE_NAME]）。
 *    这里只需拿到整段 cookie 头丢给 [ConfigStore.sanitizeQwenToken]，
 *    它会自动挑出 token 值（也兼容只有 JWT 的情况）。
 *  - 因为登录页是 SPA，必须开启 JS 与 DOM Storage。
 */
object CookieCapturer {

    const val LOGIN_URL = "https://chat.qwen.ai"
    const val TOKEN_COOKIE_NAME = "token"

    /** 需要探测的 cookie 作用域（Qwen 会写在主域或子域） */
    private val PROBE_URLS = listOf(
        "https://chat.qwen.ai",
        "https://qwen.ai",
        "https://www.qwen.ai",
        "https://qianwen.com",
        "https://www.qianwen.com",
        "https://tongyi.aliyun.com",
    )

    /**
     * 构造一个用于登录的 WebView。
     * 调用方负责把它挂到视图树里并且在结束时 [destroyWebView]。
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(
        context: Context,
        onPageChanged: (url: String) -> Unit = {},
        onLoadStarted: (url: String?) -> Unit = {},
        onLoadFinished: (url: String?) -> Unit = {},
        onLoadError: (message: String) -> Unit = {},
        /** 加载进度 0-100，用于区分「卡住」与「白屏」 */
        onProgress: (Int) -> Unit = {},
        /** 诊断日志（回调给 UI 或 logcat），排查加载失败用 */
        onDebug: (String) -> Unit = { logDebug(it) },
    ): WebView {
        val webView = WebView(context)

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        // 必须用「真正的那个 WebView」作为参数；new 一个临时的既无效又泄漏
        @Suppress("DEPRECATION")
        runCatching { cm.setAcceptThirdPartyCookies(webView, true) }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // 允许混合内容，避免站点内部 https 页面引用 http 资源时整页空白
            @Suppress("DEPRECATION")
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // 关键：默认 UA 带 "; wv" 标记，阿里系站点会识别为内嵌 WebView 并降级渲染（白屏）。
            // 这里构造一个「看起来像普通 Chrome 移动端」的 UA。
            val original = userAgentString
            val patched = buildChromeLikeUserAgent(original)
            userAgentString = patched
            onDebug("UA 原始: $original")
            onDebug("UA 替换: $patched")
        }

        // SPA 登录页会用到 alert/confirm/console 与文件选择；
        // 缺 WebChromeClient 时部分站点会卡在初始化（H2）。
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                onProgress(newProgress)
            }

            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                // 页面的 JS 报错是白屏的最直接线索
                msg?.let { onDebug("console[${it.messageLevel()}] ${it.message()} @${it.lineNumber()}") }
                return true
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                title?.let { onDebug("title: $it") }
            }

            // 允许 JS 打开新窗口（扫码登录常走 window.open），在同一个 WebView 内加载
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?,
            ): Boolean = false // 交给 shouldOverrideUrlLoading 处理，避免额外 WebView 泄漏
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                url: String?,
            ): Boolean {
                url?.let(onPageChanged)
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                url?.let(onPageChanged)
                onLoadStarted(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                url?.let(onPageChanged)
                onLoadFinished(url)
                // 白屏排查：页面的实际内容长度能区分「真白屏」与「渲染失败」
                view?.evaluateJavascript(
                    "(function(){try{return document.title+' | bodyLen='+" +
                        "(document.body?document.body.innerHTML.length:0)+' | url='+location.href}catch(e){return 'eval-err:'+e}})()",
                ) { r -> onDebug("page info: $r") }
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                // 只报主框架错误，子资源失败不算加载失败
                val desc = "${error?.errorCode} ${error?.description ?: "加载失败"}"
                if (request?.isForMainFrame == true) {
                    onDebug("主框架错误: $desc url=${request.url}")
                    onLoadError(desc)
                } else {
                    onDebug("子资源错误(忽略): $desc url=${request?.url}")
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?,
            ) {
                val code = errorResponse?.statusCode ?: 0
                if (request?.isForMainFrame == true && code >= 400) {
                    onDebug("主框架 HTTP $code url=${request.url}")
                    onLoadError("HTTP $code")
                } else if (code >= 400) {
                    onDebug("子资源 HTTP $code url=${request?.url}")
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?,
            ) {
                // 不静默放行（有安全风险），但记录下来便于判断是否证书问题
                onDebug("SSL 错误: ${error?.primaryError} ${error?.url}")
                handler?.cancel()
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?,
            ) {
                // 旧版回调：仅当失败的是主框架 URL 时才报
                if (failingUrl != null && failingUrl == view?.url) {
                    onLoadError("$errorCode ${description ?: ""}")
                }
            }
        }

        onDebug("开始加载 $LOGIN_URL")
        webView.loadUrl(LOGIN_URL)
        return webView
    }

    /** 诊断日志：统一走 logcat，便于真机排查（不依赖 BuildConfig.DEBUG）。 */
    private fun logDebug(msg: String) {
        runCatching { android.util.Log.i("QwenWebView", msg) }
    }

    /**
     * 构造一个移除 WebView 特征的 UA，让 Qwen 把内嵌 WebView 当成普通浏览器。
     *
     * 阿里系风控会检查多个特征，仅去 "; wv" 往往不够：
     *   - "; wv"        : WebView 标准标记
     *   - "Version/4.0" : 旧 WebView 标记
     *   - 缺失 Mobile   : 部分 WebView 不带
     * 这里统一清理，并把 Chrome 主版本对齐到一个较新的稳定值。
     */
    fun buildChromeLikeUserAgent(original: String?): String {
        val base = original?.trim().orEmpty()
        if (base.isEmpty()) return FALLBACK_UA

        var ua = base
            .replace("; wv)", ")")
            .replace("; wv", "")
            .replace(Regex("\\s*Version/\\d+\\.\\d+\\s*"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

        // 若清理后没有 Chrome 版本号，或版本过旧，替换成较新的稳定版
        val chrome = Regex("Chrome/(\\d+)").find(ua)?.groupValues?.get(1)?.toIntOrNull()
        if (chrome == null || chrome < 120) {
            ua = ua.replace(Regex("Chrome/[\\d.]+"), "Chrome/$UA_CHROME_VERSION")
        }
        return ua
    }

    /** 目标 Chrome 主版本：与设备 System WebView 138 接近，避免"版本过旧"被拦。 */
    private const val UA_CHROME_VERSION = "138.0.7204.179"

    private const val FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$UA_CHROME_VERSION Mobile Safari/537.36"

    /**
     * 读取当前已登录的 Cookie。
     *
     * @return 形如 `cna=xxx; token=eyJ...; tfstk=yyy` 的 cookie 头；
     *         完全没有任何 cookie（或 CookieManager 不可用）时返回 null。
     */
    fun readCookieHeader(): String? {
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return null
        val parts = LinkedHashSet<String>()
        PROBE_URLS.forEach { url ->
            val c = runCatching { cm.getCookie(url) }.getOrNull()
            if (!c.isNullOrBlank()) {
                c.split(";").forEach { seg ->
                    val s = seg.trim()
                    if (s.isNotEmpty()) parts.add(s)
                }
            }
        }
        if (parts.isEmpty()) return null
        return parts.joinToString("; ")
    }

    /** 是否已经能从 cookie 里解析出可用的 token（或 JWT 本体） */
    fun hasToken(): Boolean {
        val header = readCookieHeader() ?: return false
        return extractToken(header) != null
    }

    /**
     * 从 cookie 串里挑出 token：
     *  1. 优先 cookie 中名为 token 的项；
     *  2. 退而求其次，直接找 JWT 本体。
     * 找不到返回 null。
     */
    fun extractToken(cookieHeader: String?): String? {
        val h = cookieHeader?.trim().orEmpty()
        if (h.isEmpty()) return null

        // 1) cookie 项：token=xxx
        Regex("(?:^|;)\\s*${TOKEN_COOKIE_NAME}\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE)
            .find(h)?.groupValues?.get(1)?.trim()?.let { v ->
                if (v.isNotEmpty()) return v
            }

        // 2) 任意 cookie 项的值本身是 JWT
        Regex("(?:^|;)\\s*([A-Za-z0-9_.%-]+)\\s*=\\s*([^;]+)").findAll(h).forEach { m ->
            val v = m.groupValues[2].trim()
            if (isJwt(v)) return v
        }

        // 3) 整串里出现 JWT
        Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}")
            .find(h)?.value?.let { return it }

        return null
    }

    private fun isJwt(v: String): Boolean =
        Regex("^eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$").matches(v)

    /** 清理登录用 WebView，避免内存泄漏 */
    fun destroyWebView(webView: WebView?) {
        runCatching {
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            webView?.removeAllViews()
            webView?.destroy()
        }
        runCatching { CookieManager.getInstance().flush() }
    }

    /** 退出登录：清掉 WebView 中的所有 cookie（可用于「切换账号」） */
    fun clearCookies() {
        runCatching {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        }
    }
}
