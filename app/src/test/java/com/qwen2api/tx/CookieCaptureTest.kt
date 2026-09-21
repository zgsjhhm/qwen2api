package com.qwen2api.tx

import com.qwen2api.tx.core.ConfigStore
import com.qwen2api.tx.core.CookieCapturer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览器登录取回 Cookie 的解析逻辑测试。
 *
 * 覆盖 CookieCapturer.extractToken / hasToken 与 ConfigStore.sanitizeQwenToken
 * 的协作：从真实 Qwen 登录后产生的 Cookie 串里正确挑出 token。
 */
class CookieCaptureTest {

    // 真实形态的 Qwen 登录 Cookie（token 为 JWT）
    private val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" +
        ".eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IlF3ZW4ifQ" +
        ".dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk"

    @Test
    fun `extract token from typical qwen cookie header`() {
        val cookie = "cna=abc123; tfstk=zzz999; token=$jwt; _bl_uid=xyz"
        assertEquals(jwt, CookieCapturer.extractToken(cookie))
    }

    @Test
    fun `extract token when token cookie is last entry`() {
        val cookie = "cna=abc123; tfstk=zzz999; token=$jwt"
        assertEquals(jwt, CookieCapturer.extractToken(cookie))
    }

    @Test
    fun `extract jwt even when cookie name is not token`() {
        // 有些环境 token 落在别的 cookie 名里，或只剩 JWT 本体
        val cookie = "cna=abc123; someSession=$jwt; tfstk=zzz"
        assertEquals(jwt, CookieCapturer.extractToken(cookie))
    }

    @Test
    fun `extract bare jwt without cookie syntax`() {
        assertEquals(jwt, CookieCapturer.extractToken(jwt))
    }

    @Test
    fun `token cookie wins over other jwt-like values`() {
        val other = "eyJhbGciOiJIUzI1NiJ9.eyJhIjoxfQ.aaaa"
        val cookie = "cna=$other; token=$jwt"
        assertEquals("token cookie 应优先", jwt, CookieCapturer.extractToken(cookie))
    }

    @Test
    fun `extract returns null when no credential present`() {
        assertNull(CookieCapturer.extractToken("cna=abc123; tfstk=zzz999"))
        assertNull(CookieCapturer.extractToken(""))
        assertNull(CookieCapturer.extractToken(null))
        assertNull(CookieCapturer.extractToken("   "))
    }

    @Test
    fun `empty token cookie value is not accepted`() {
        assertNull(CookieCapturer.extractToken("cna=abc; token=; tfstk=zzz"))
        assertNull(CookieCapturer.extractToken("cna=abc; token=   "))
    }

    @Test
    fun `token value with surrounding spaces is trimmed`() {
        val cookie = "cna=abc; token=  $jwt  ; tfstk=zzz"
        assertEquals(jwt, CookieCapturer.extractToken(cookie))
    }

    @Test
    fun `cookie name matching is case insensitive`() {
        assertEquals(jwt, CookieCapturer.extractToken("CNA=abc; TOKEN=$jwt"))
        assertEquals(jwt, CookieCapturer.extractToken("Token=$jwt"))
    }

    @Test
    fun `token cookie takes precedence at string start`() {
        assertEquals(jwt, CookieCapturer.extractToken("token=$jwt; cna=abc"))
    }

    // ---------------- 与 ConfigStore 的协作（导入链路） ----------------

    @Test
    fun `captured cookie is accepted by sanitize and yields jwt`() {
        val cookie = "cna=abc123; tfstk=zzz999; token=$jwt; _bl_uid=xyz"
        val captured = CookieCapturer.extractToken(cookie)
        assertNotNull(captured)

        // 关键：把 CookieManager 取到的整段 cookie 丢进 sanitize，
        // 它必须能挑出 token 并判定为 jwt（这正是「自动回填」链路）
        val san = ConfigStore.sanitizeQwenToken(cookie)
        assertTrue("整段 Cookie 应能被清洗接受: ${san.message}", san.ok)
        assertEquals(jwt, san.token)
        assertEquals("jwt", san.type)
    }

    @Test
    fun `cookie with short opaque token value is accepted and typed as cookie`() {
        // 不是 JWT、但确实含 token 项的 Cookie（Qwen 某些环境下会下发短 token）
        val san = ConfigStore.sanitizeQwenToken("cna=abc123; token=opaque-session-value; tfstk=zzz")
        assertTrue("含 token 项的 Cookie 应被接受: ${san.message}", san.ok)
        assertEquals("opaque-session-value", san.token)
        assertEquals("已从 Cookie 串中提取 token 值", san.note)
    }

    @Test
    fun `cookie without token is rejected with actionable message`() {
        // 用户还没登录成功时点了「取回 Cookie」，应给出明确指引而不是静默存下无效凭证
        val san = ConfigStore.sanitizeQwenToken("cna=abc123; tfstk=zzz999")
        assertTrue("仅有非凭证 cookie 时应被拒", !san.ok)
        assertTrue(
            "提示应说明缺少登录凭证: ${san.message}",
            san.message.contains("登录凭证") || san.message.contains("token"),
        )
    }

    @Test
    fun `containsCredential detects token presence`() {
        assertTrue(ConfigStore.containsCredential("cna=abc; token=xyz"))
        assertTrue(ConfigStore.containsCredential("token=$jwt"))
        assertTrue(ConfigStore.containsCredential("cna=abc; sess=$jwt"))
        assertTrue(!ConfigStore.containsCredential("cna=abc; tfstk=zzz"))
        assertTrue(!ConfigStore.containsCredential("token=; cna=abc"))
    }

    @Test
    fun `captured bare jwt is accepted by sanitize`() {
        val san = ConfigStore.sanitizeQwenToken(jwt)
        assertTrue(san.ok)
        assertEquals(jwt, san.token)
        assertEquals("jwt", san.type)
    }

    @Test
    fun `cookie without token is rejected and message is non empty`() {
        // 用户还没登录成功时点了「取回 Cookie」，应给出明确指引而不是静默失败
        val san = ConfigStore.sanitizeQwenToken("cna=abc123; tfstk=zzz999")
        assertTrue("仅有非凭证 cookie 时应被拒", !san.ok)
        assertTrue("错误提示应可读", san.message.isNotEmpty())
    }

    @Test
    fun `captured cookie round trips through config`() {
        val cookie = "cna=abc; token=$jwt; tfstk=zzz"
        val san = ConfigStore.sanitizeQwenToken(cookie)
        assertTrue(san.ok)
        // 保存进配置后，检测类型应为 jwt（下游 QwenClient 以 Bearer 使用）
        assertEquals("jwt", ConfigStore.detectTokenType(san.token))
        // 掩码展示不应泄漏完整凭证
        val masked = ConfigStore.maskToken(san.token)
        assertTrue(masked.contains("****"))
        assertTrue("掩码不应包含完整 token", !masked.contains(jwt))
    }

    @Test
    fun `login url points to qwen chat`() {
        assertEquals("https://chat.qwen.ai", CookieCapturer.LOGIN_URL)
        assertEquals("token", CookieCapturer.TOKEN_COOKIE_NAME)
    }

    // ---------------- 反 WebView 标记（白屏根因之一） ----------------

    @Test
    fun `user agent strips wv marker`() {
        // 默认 WebView UA 里的 "; wv" 会让阿里系站点判定为内嵌浏览器并降级渲染（白屏）
        val original = "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/UQ1A; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
            "Chrome/124.0.6367.82 Mobile Safari/537.36"
        val ua = CookieCapturer.buildChromeLikeUserAgent(original)
        assertTrue("不应残留 ; wv 标记: $ua", !ua.contains("; wv"))
        assertTrue("不应残留 wv) 断句: $ua", !ua.contains("wv)"))
        assertTrue("不应残留 Version/4.0: $ua", !ua.contains("Version/"))
        assertTrue("应保留 Chrome 版本信息: $ua", ua.contains("Chrome/124"))
        assertTrue("应保留 Mobile 标记: $ua", ua.contains("Mobile"))
        assertTrue("应是合法 UA 开头: $ua", ua.startsWith("Mozilla/5.0"))
    }

    @Test
    fun `user agent falls back when original is blank`() {
        val ua = CookieCapturer.buildChromeLikeUserAgent(null)
        assertTrue(ua.contains("Chrome/"))
        assertTrue(ua.contains("Android"))
        assertTrue(!ua.contains("; wv"))
        assertTrue(CookieCapturer.buildChromeLikeUserAgent("  ").contains("Android"))
    }

    @Test
    fun `user agent leaves clean ua untouched except wv`() {
        val clean = "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        val ua = CookieCapturer.buildChromeLikeUserAgent(clean)
        assertTrue(ua.contains("Chrome/120"))
        assertTrue(ua.contains("SM-G991B"))
        assertTrue(!ua.contains("; wv"))
    }

    // ---------------- Cookie 作用域探测 ----------------

    @Test
    fun `readCookieHeader degrades safely without android framework`() {
        // 纯 JVM 下 CookieManager 不可用（Stub! 抛异常），但生产代码里已用
        // runCatching 包裹每个 getCookie，方法本身不会把异常抛给调用方。
        // 这里只验证「不抛未捕获异常」这一契约在 JVM 上同样成立。
        val result = runCatching { CookieCapturer.readCookieHeader() }
        assertTrue("runCatching 包裹后不应泄漏异常", result.isSuccess)
    }

    @Test
    fun `hasToken degrades safely without android framework`() {
        val result = runCatching { CookieCapturer.hasToken() }
        assertTrue("runCatching 包裹后不应泄漏异常", result.isSuccess)
    }
}
