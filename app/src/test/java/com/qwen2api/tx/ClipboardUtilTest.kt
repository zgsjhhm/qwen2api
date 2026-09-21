package com.qwen2api.tx

import com.qwen2api.tx.core.ClipboardUtil
import com.qwen2api.tx.core.CookieScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 凭证判定口径的一致性测试（[ClipboardUtil.looksLikeCredential] vs
 * [CookieScript.looksLikeCredential]）。
 *
 * ## 为什么必须补这一层
 * 这是同一个判定在两条入口上的两份实现，而两条入口的**后果不同**：
 *
 *  - 回调入口（CookieScript.parseCallback）：判定通过 → 直接保存登录态
 *  - 剪贴板入口（ClipboardUtil.readCredential）：判定通过 → 认领剪贴板内容并导入
 *
 * `CookieScript` 的文档明确写了「与 ClipboardUtil.looksLikeCredential 保持一致的
 * 判定口径」，但改动前两份实现对**最小长度**的处理不同：
 * ClipboardUtil 版本没有长度下界，因此 `token=x` 这种明显不是凭证的文本
 * 在剪贴板入口会被认领，而回调入口会拒绝 —— 同一份输入两个答案。
 *
 * 判定口径漂移本身不会直接造成安全问题（上游最终仍会校验 token），
 * 但会让「为什么这次能导入、那次不能」变成无法解释的行为，
 * 所以这里把口径钉成单一事实来源，并锁住两侧一致。
 *
 * 注意：本测试只调用不依赖 Android Context 的纯函数，因此可在纯 JVM 上运行。
 */
class ClipboardUtilTest {

    private val cookie =
        "qwen-locale=zh-CN; token=eyJhbGciOiJIUzI1NiJ9.abc12345; theme=dark"

    private val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcdefgh"

    /** 两侧判定必须给出同一答案的样本集合 */
    private fun samples(): List<Pair<String, String>> = listOf(
        "真实 cookie" to cookie,
        "真实 JWT" to jwt,
        "cookie 名 + JWT" to "qwen_token=$jwt",
        "分号紧邻 token" to "locale=zh;token=abcdefghijklmnopqrstuv",
        "大写 Token" to "Token=abcdefghijklmnopqrstuv",
        "前后空白" to "  token=abcdefghijklmnopqrstuv  ",
        "普通文本" to "这是一段普通的文本",
        "网址" to "https://chat.qwen.ai/",
        "空串" to "",
        "纯空白" to "   \n\t ",
        "被单词裹住的 token" to "subtokens=abcdefghijklmnopqrstuv",
        "过短 token" to "token=x",
        "只有 JWT 头" to "eyJhbGciOiJIUzI1NiJ9",
        "两段 JWT" to "eyJhbGciOiJIUzI1NiJ9.eyJzdWIi",
    )

    // ---------------- 1. 口径一致性 ----------------

    @Test
    fun `both implementations agree on every sample`() {
        samples().forEach { (label, text) ->
            assertEquals(
                "「$label」在两条入口上的判定必须一致，否则行为无法解释",
                CookieScript.looksLikeCredential(text),
                ClipboardUtil.looksLikeCredential(text),
            )
        }
    }

    @Test
    fun `both implementations reject blank input`() {
        listOf("", "   ", "\n\t ").forEach { t ->
            assertFalse("剪贴板: 「$t」不是凭证", ClipboardUtil.looksLikeCredential(t))
            assertFalse("回调: 「$t」不是凭证", CookieScript.looksLikeCredential(t))
        }
    }

    @Test
    fun `both implementations accept real cookie and jwt`() {
        listOf(cookie, jwt, "qwen_token=$jwt").forEach { t ->
            assertTrue("剪贴板应认领真实凭证", ClipboardUtil.looksLikeCredential(t))
            assertTrue("回调应认领真实凭证", CookieScript.looksLikeCredential(t))
        }
    }

    @Test
    fun `both implementations reject arbitrary text`() {
        listOf("这是一段文本", "https://example.com", "my notes").forEach { t ->
            assertFalse(ClipboardUtil.looksLikeCredential(t))
            assertFalse(CookieScript.looksLikeCredential(t))
        }
    }

    // ---------------- 2. 长度下界 ----------------

    @Test
    fun `short token is rejected by the clipboard path too`() {
        // 回归点：旧 ClipboardUtil 实现没有长度下界，会把 "token=x" 认领成凭证
        assertFalse(
            "过短内容不应被剪贴板入口认领",
            ClipboardUtil.looksLikeCredential("token=x"),
        )
    }

    @Test
    fun `credential at the minimum length boundary is accepted by both`() {
        val exactly20 = "token=" + "a".repeat(14) // 20 字符
        assertEquals(20, exactly20.length)
        assertTrue(ClipboardUtil.looksLikeCredential(exactly20))
        assertTrue(CookieScript.looksLikeCredential(exactly20))
    }

    @Test
    fun `one character below the boundary is rejected by both`() {
        val len19 = "token=" + "a".repeat(13) // 19 字符
        assertEquals(19, len19.length)
        assertFalse(ClipboardUtil.looksLikeCredential(len19))
        assertFalse(CookieScript.looksLikeCredential(len19))
    }

    // ---------------- 3. 与回调解析口径一致 ----------------

    @Test
    fun `anything parseCallback returns passes the clipboard check`() {
        // 两条入口的判定必须闭合：能通过回调进来的凭证，剪贴板入口也要认
        val uri = "${CookieScript.CALLBACK_FULL}?${CookieScript.PARAM_COOKIE}=" +
            java.net.URLEncoder.encode(cookie, "UTF-8")
        val parsed = CookieScript.parseCallback(uri)!!
        assertTrue(
            "回调解析出的凭证在剪贴板入口也必须被认领",
            ClipboardUtil.looksLikeCredential(parsed),
        )
    }
}
