package com.qwen2api.tx

import com.qwen2api.tx.core.CookieScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外部浏览器登录回调链路（[CookieScript]）的单元测试。
 *
 * ## 为什么必须补
 * 这条链路是「把 chat.qwen.ai 的登录凭证从浏览器搬进 App」的唯一自动化通道：
 *
 * ```
 * 浏览器脚本 → qwen2api://callback?c=<cookie> → Android 派发 Intent
 *            → MainActivity.handleLoginCallback → CookieScript.parseCallback
 *            → LoginCallbackBus → UI 校验并保存
 * ```
 *
 * 改动前 `parseCallback` 与 `looksLikeCredential` 都是零覆盖的纯逻辑，
 * 而它们守着两个**安全边界**：
 *
 *  1. **scheme 不是身份**。`qwen2api://` 是全局命名空间，任何应用、任何网页
 *     （`<a href="qwen2api://...">` / `location.href`）都能构造并触发它。
 *     若解析时只看 scheme 不看 host，攻击者就能用
 *     `qwen2api://anything/?c=<20 字符>` 把**自己的凭证**注入用户 App，
 *     之后用户的提问会带着攻击者的登录态发出去 —— 会话串号 / 数据泄漏。
 *     真正的身份必须由 host（`callback`）承担，并且要在**解析器内部**校验，
 *     不能指望调用方记得做（`MainActivity` 当时只判断了 scheme 前缀）。
 *
 *  2. **凭证判定必须严格**。剪贴板里随手一段文本被误判为凭证，就等于把
 *     用户带进「导入了一个无效 token」的状态；反之把真凭证误判掉，
 *     用户会以为功能坏了。
 *
 * ## 覆盖重点
 *  - URI 解析：host 校验、参数顺序、多参数、URL 编码、空值/畸形输入
 *  - 凭证判定：token= cookie / JWT 的形态边界（短路前缀、长度）
 *  - 脚本常量：注入的 scheme/host/参数名必须与解析器完全对齐（两边改错一边就断链）
 */
class CookieScriptTest {

    /** 一个形态合法的 Qwen cookie（长度超过 MIN_CREDENTIAL_LEN=20） */
    private val cookie =
        "qwen-locale=zh-CN; token=eyJhbGciOiJIUzI1NiJ9.abc12345; theme=dark"

    /** 形态合法的 JWT */
    private val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcdefgh"

    private fun cb(vararg params: Pair<String, String>): String {
        val q = params.joinToString("&") { "${it.first}=${it.second}" }
        return "${CookieScript.CALLBACK_FULL}?$q"
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    // ---------------- 1. 正常回调 ----------------

    @Test
    fun `valid callback returns the decoded cookie`() {
        val got = CookieScript.parseCallback(cb(CookieScript.PARAM_COOKIE to enc(cookie)))
        assertEquals(cookie, got)
    }

    @Test
    fun `callback works regardless of scheme letter case`() {
        val uri = "QWEN2API://CALLBACK?${CookieScript.PARAM_COOKIE}=${enc(cookie)}"
        assertEquals(cookie, CookieScript.parseCallback(uri))
    }

    @Test
    fun `callback works with a trailing slash on the host`() {
        val uri = "qwen2api://callback/?${CookieScript.PARAM_COOKIE}=${enc(cookie)}"
        assertEquals(cookie, CookieScript.parseCallback(uri))
    }

    @Test
    fun `extra query parameters do not break parsing`() {
        // 浏览器/脚本后续版本可能追加耗时、版本号等参数
        val uri = "qwen2api://callback?ts=123&${CookieScript.PARAM_COOKIE}=${enc(cookie)}&v=2"
        assertEquals(cookie, CookieScript.parseCallback(uri))
    }

    @Test
    fun `credential is returned even when not the first parameter`() {
        val uri = "qwen2api://callback?v=2&${CookieScript.PARAM_COOKIE}=${enc(cookie)}"
        assertEquals(cookie, CookieScript.parseCallback(uri))
    }

    @Test
    fun `already percent decoded payload is not double decoded into garbage`() {
        // cookie 里没有 % 时，解码是幂等的
        val plain = "token=abcdefghijklmnopqrstuv; path=/"
        assertEquals(plain, CookieScript.parseCallback(cb(CookieScript.PARAM_COOKIE to enc(plain))))
    }

    @Test
    fun `plus signs survive decoding as spaces per form encoding`() {
        // URLDecoder 按 application/x-www-form-urlencoded 把 + 解成空格；
        // 浏览器 encodeURIComponent 不会把空格变 +，所以这里钉住的是行为一致性
        val value = "token=abcdefghijklmnopqrstuv aaaaaa"
        val got = CookieScript.parseCallback("qwen2api://callback?c=" + enc(value))
        assertNotNull(got)
        assertTrue(got!!.startsWith("token=abcdefghijklmnopqrstuv"))
    }

    @Test
    fun `url encoded special characters are decoded`() {
        // 注意：这里手工拼 %3D，不能再过一遍 enc()（那会变成 %253D，测的就成了双重解码）
        val got = CookieScript.parseCallback("qwen2api://callback?c=token=${"a".repeat(24)}%3Bflags%3Dx")
        assertNotNull(got)
        assertTrue("百分号编码必须被解开", got!!.contains("flags=x"))
    }

    // ---------------- 2. host 校验（安全边界） ----------------

    @Test
    fun `foreign host with cookie param is rejected`() {
        // 关键回归点：scheme 是全局命名空间，任何应用都能构造这个 URI。
        // 只校验 scheme 就等于接受任意来源注入的凭证。
        assertNull(
            "非 callback host 的凭证注入必须被拒",
            CookieScript.parseCallback("qwen2api://evil.example/?${CookieScript.PARAM_COOKIE}=${enc(cookie)}"),
        )
    }

    @Test
    fun `foreign host with credential payload in path is rejected`() {
        // 攻击者最省事的形式：把凭证塞进 path，不写 query
        assertNull(
            CookieScript.parseCallback("qwen2api://evil.example/${"a".repeat(40)}"),
        )
    }

    @Test
    fun `host that merely contains callback is rejected`() {
        // 子串匹配不够：callback.attacker.com / evil-callback 都不能过
        assertNull(CookieScript.parseCallback("qwen2api://callback.attacker.com/?c=${enc(cookie)}"))
        assertNull(CookieScript.parseCallback("qwen2api://evil-callback/?c=${enc(cookie)}"))
        assertNull(CookieScript.parseCallback("qwen2api://xcallback/?c=${enc(cookie)}"))
    }

    @Test
    fun `callback host as a userinfo or path segment is rejected`() {
        // qwen2api://callback@evil.com/ 的 authority 是 callback@evil.com，
        // 真实 host 是 evil.com —— 不能因为出现过 "callback" 就放行
        assertNull(CookieScript.parseCallback("qwen2api://callback@evil.com/?c=${enc(cookie)}"))
        assertNull(CookieScript.parseCallback("qwen2api://evil.com/callback?c=${enc(cookie)}"))
    }

    @Test
    fun `missing host is rejected`() {
        assertNull(CookieScript.parseCallback("qwen2api:///?c=${enc(cookie)}"))
        assertNull(CookieScript.parseCallback("qwen2api://?c=${enc(cookie)}"))
    }

    // ---------------- 3. 非本 App 的 URI ----------------

    @Test
    fun `null and blank input return null`() {
        assertNull(CookieScript.parseCallback(null))
        assertNull(CookieScript.parseCallback(""))
        assertNull(CookieScript.parseCallback("   "))
    }

    @Test
    fun `other schemes are rejected`() {
        assertNull(CookieScript.parseCallback("https://example.com/?c=${enc(cookie)}"))
        assertNull(CookieScript.parseCallback("javascript:alert(1)"))
        assertNull(CookieScript.parseCallback("intent://callback?c=x#Intent;end"))
    }

    @Test
    fun `scheme lookalikes are rejected`() {
        // 前缀匹配的经典坑：myqwen2api:// 不能因为 contains("qwen2api") 就放行
        assertNull(CookieScript.parseCallback("notqwen2api://callback?c=${enc(cookie)}"))
        assertNull(CookieScript.parseCallback("x-qwen2api://callback?c=${enc(cookie)}"))
    }

    @Test
    fun `uri without query and without host returns null`() {
        assertNull(CookieScript.parseCallback("qwen2api://callback"))
        assertNull(CookieScript.parseCallback("qwen2api://callback/"))
    }

    // ---------------- 4. 参数解析的退化情形 ----------------

    @Test
    fun `missing cookie param returns null`() {
        assertNull(CookieScript.parseCallback("qwen2api://callback?other=value"))
        assertNull(CookieScript.parseCallback("qwen2api://callback?c"))
        assertNull(CookieScript.parseCallback("qwen2api://callback?=emptykey"))
    }

    @Test
    fun `empty cookie value returns null`() {
        assertNull(CookieScript.parseCallback("qwen2api://callback?c="))
    }

    @Test
    fun `too short payload is rejected`() {
        // 过短的一律视为无效，避免把误触/占位文本当成凭证
        assertNull(CookieScript.parseCallback("qwen2api://callback?c=" + enc("short")))
        assertNull(CookieScript.parseCallback("qwen2api://callback?c=" + enc("x".repeat(19))))
    }

    @Test
    fun `payload at the minimum length boundary is accepted`() {
        val exactly20 = "x".repeat(20)
        assertEquals(exactly20, CookieScript.parseCallback("qwen2api://callback?c=" + enc(exactly20)))
    }

    @Test
    fun `garbage percent sequences fall back to the raw value`() {
        // 畸形编码不应抛异常，也不应吞掉凭证
        val raw = "token=" + "a".repeat(24) + "%zz"
        assertEquals(raw, CookieScript.parseCallback("qwen2api://callback?c=$raw"))
    }

    @Test
    fun `multiple cookie params return the first valid one`() {
        val first = "token=" + "a".repeat(24)
        val second = "token=" + "b".repeat(24)
        val uri = "qwen2api://callback?c=${enc(first)}&c=${enc(second)}"
        assertEquals(first, CookieScript.parseCallback(uri))
    }

    @Test
    fun `invalid first value falls through to a later valid one`() {
        val good = "token=" + "c".repeat(24)
        val uri = "qwen2api://callback?c=short&c=${enc(good)}"
        assertEquals("第一个值太短时应继续找下一个", good, CookieScript.parseCallback(uri))
    }

    @Test
    fun `leading and trailing whitespace around the uri is ignored`() {
        val uri = "  ${cb(CookieScript.PARAM_COOKIE to enc(cookie))}\n"
        assertEquals(cookie, CookieScript.parseCallback(uri))
    }

    // ---------------- 5. 凭证判定 ----------------

    @Test
    fun `token cookie is recognized`() {
        assertTrue(CookieScript.looksLikeCredential(cookie))
    }

    @Test
    fun `token cookie with surrounding whitespace is recognized`() {
        assertTrue(CookieScript.looksLikeCredential("  token=abcdefghijklmnopqrstuv  "))
    }

    @Test
    fun `token cookie after a semicolon is recognized`() {
        assertTrue(CookieScript.looksLikeCredential("locale=zh;token=abcdefghijklmnopqrstuv"))
    }

    @Test
    fun `token cookie is case insensitive`() {
        assertTrue(CookieScript.looksLikeCredential("Token=abcdefghijklmnopqrstuv"))
    }

    @Test
    fun `jwt is recognized`() {
        assertTrue(CookieScript.looksLikeCredential(jwt))
    }

    @Test
    fun `jwt prefixed by a cookie name is recognized`() {
        assertTrue(CookieScript.looksLikeCredential("qwen_token=$jwt"))
    }

    @Test
    fun `empty and blank text is not a credential`() {
        assertFalse(CookieScript.looksLikeCredential(null))
        assertFalse(CookieScript.looksLikeCredential(""))
        assertFalse(CookieScript.looksLikeCredential("    \n\t "))
    }

    @Test
    fun `arbitrary text is not a credential`() {
        assertFalse(CookieScript.looksLikeCredential("这是一段普通的文本，不是凭证"))
        assertFalse(CookieScript.looksLikeCredential("https://chat.qwen.ai/"))
        assertFalse(CookieScript.looksLikeCredential("my secret notes about tokens"))
    }

    @Test
    fun `token substring inside a plain word is not a credential`() {
        // `subtokens=abc` 里的 "token=" 不能算命中：正则要求 token 前面是
        // 串首 / 分号 / 空白
        assertFalse(
            "被更长单词裹住的 token= 不是 cookie 字段",
            CookieScript.looksLikeCredential("subtokens=abcdefghijklmnopqrstuv"),
        )
    }

    @Test
    fun `bare jwt prefix without full structure is not a credential`() {
        assertFalse(CookieScript.looksLikeCredential("eyJhbGciOiJIUzI1NiJ9"))
        assertFalse(CookieScript.looksLikeCredential("eyJhbGciOiJIUzI1NiJ9.shortonly"))
    }

    @Test
    fun `text shorter than the minimum is not a credential`() {
        assertFalse(CookieScript.looksLikeCredential("token=x"))
    }

    @Test
    fun `very short jwt is not a credential`() {
        // JWT 各段都有最小长度要求
        assertFalse(CookieScript.looksLikeCredential("eyJhbGci.eyJzdWIi.ab"))
    }

    // ---------------- 6. 回调的往返一致性 ----------------

    @Test
    fun `callback built from a valid credential round trips`() {
        // 模拟浏览器脚本行为：encodeURIComponent(cookie) 拼进回调
        val uri = "${CookieScript.CALLBACK_FULL}?${CookieScript.PARAM_COOKIE}=${enc(cookie)}"
        val parsed = CookieScript.parseCallback(uri)
        assertNotNull(parsed)
        assertEquals("解析结果必须逐字节还原 cookie", cookie, parsed)
        assertTrue("还原出的凭证必须通过凭证判定", CookieScript.looksLikeCredential(parsed))
    }

    @Test
    fun `an injected credential from a foreign host cannot reach the bus`() {
        // 攻击者能拼出一个「看起来完全合法」的回调，唯一区别是 host。
        // 解析层必须把它挡掉，否则 UI 会带着攻击者的登录态工作。
        val forged = "qwen2api://attacker/?${CookieScript.PARAM_COOKIE}=${enc(cookie)}"
        assertNull(CookieScript.parseCallback(forged))
    }

    // ---------------- 7. 脚本常量与界面文本 ----------------

    @Test
    fun `scheme and callback host constants stay aligned with the manifest contract`() {
        assertEquals("qwen2api", CookieScript.SCHEME)
        assertEquals("callback", CookieScript.CALLBACK_HOST)
        assertEquals("qwen2api://callback", CookieScript.CALLBACK_FULL)
        assertEquals("c", CookieScript.PARAM_COOKIE)
    }

    @Test
    fun `address bar script contains the callback url and cookie param`() {
        val s = CookieScript.ADDRESS_BAR_SCRIPT
        assertTrue("脚本必须指向回调地址", s.contains(CookieScript.CALLBACK_FULL))
        assertTrue("脚本必须带上凭证参数名", s.contains("${CookieScript.PARAM_COOKIE}="))
        assertTrue("脚本必须读取 document.cookie", s.contains("document.cookie"))
        assertTrue("必须用 encodeURIComponent 编码凭证", s.contains("encodeURIComponent"))
    }

    @Test
    fun `address bar script only fires when a token cookie exists`() {
        val s = CookieScript.ADDRESS_BAR_SCRIPT
        assertTrue("必须先检查 token 是否存在", s.contains("token="))
        assertTrue("未登录时应提示而不是跳转", s.contains("alert("))
    }

    @Test
    fun `address bar script has a clipboard fallback prompt`() {
        // scheme 被浏览器拦截时，用户仍有手动兜底路径
        assertTrue(CookieScript.ADDRESS_BAR_SCRIPT.contains("prompt("))
    }

    @Test
    fun `script constant is stable across calls`() {
        // 界面展示与剪贴板写入共用同一份常量，不能每次调用都生成不同文本
        assertEquals(CookieScript.ADDRESS_BAR_SCRIPT, CookieScript.ADDRESS_BAR_SCRIPT)
        assertEquals(CookieScript.ADDRESS_BAR_SCRIPT, CookieScript.addressBarScript())
    }

    @Test
    fun `address bar script is a javascript pseudo url`() {
        assertTrue(
            "地址栏脚本必须带 javascript: 前缀，否则粘贴后不执行",
            CookieScript.ADDRESS_BAR_SCRIPT.startsWith("javascript:"),
        )
    }

    @Test
    fun `prompt fallback script reads document cookie`() {
        assertTrue(CookieScript.PROMPT_SCRIPT.startsWith("javascript:"))
        assertTrue(CookieScript.PROMPT_SCRIPT.contains("document.cookie"))
    }

    @Test
    fun `how to text is non empty`() {
        assertTrue(CookieScript.HOW_TO.isNotBlank())
    }

    @Test
    fun `generated callbacks from the script shape are parseable`() {
        // 把脚本里实际会生成的 URI 形状手写一遍，确保解析端接受
        val sample = "${CookieScript.CALLBACK_FULL}?${CookieScript.PARAM_COOKIE}=" +
            java.net.URLEncoder.encode(cookie, "UTF-8").replace("+", "%20")
        assertEquals(cookie, CookieScript.parseCallback(sample))
    }
}
