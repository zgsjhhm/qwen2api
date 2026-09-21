package com.qwen2api.tx

import com.qwen2api.tx.core.MemoryConfigRepository
import com.qwen2api.tx.core.QwenClient
import com.qwen2api.tx.core.QwenException
import com.qwen2api.tx.core.QwenImageClient
import com.qwen2api.tx.core.RiskControl
import com.qwen2api.tx.server.GatewayRouter
import com.qwen2api.tx.server.MemoryFileStore
import com.qwen2api.tx.server.MiniHttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * 「上游 200 + 错误 JSON」的分流回归。
 *
 * ## 为什么单独立一组
 *
 * 这类错误的**症状是具有误导性的**：网关回的是同一个 502，但给用户的处置
 * 建议可能完全反了。2026-09-21 真机实测的原文是：
 *
 * ```json
 * {"success":false,"request_id":"...","data":{"code":"RateLimited",
 *  "details":"You've reached the upper limit for today's usage.
 *             Please try again tomorrow.","num":12}}
 * ```
 *
 * 这是**今日额度用尽** —— 处置是「等明天/换账号」。但旧判据的窄口径正则里有
 * `rate.?limit`，命中了 `RateLimited`，于是被归成阿里盾风控，回给用户的是
 * 「请在浏览器打开 chat.qwen.ai 手动过一次滑块验证」。用户照做不可能有用，
 * 因为额度要到次日才重置；而且 `THROTTLE_CODE` 还落在"不重试"分支上，
 * 连方向都错了。判据本身看不出问题 —— 必须拿**上游真实形态的响应体**喂进去。
 *
 * 因此本文件用一个**本地假上游**返回逐字节相同的响应体，走真实的
 * `generateImage` / `chatStream` 路径（不 override 掉被测逻辑）。
 */
class UpstreamBlockClassificationTest {

    /**
     * 真机抓到的原始响应体（额度用尽）。
     *
     * 用 raw string 而不是分段拼接：拼接时极易漏/多一个引号，而 JSON 一旦
     * 不合法，被测代码会在 `Json.parse` 就返回 null 并抛 `BAD_RESPONSE` ——
     * 于是测试红在一处与判据**完全无关**的地方，看起来像判据写错。
     */
    private val quotaBody = """
        {"success":false,"request_id":"3c9429b0-ca12-4e2a-ad4e-98e1d381adf4","data":{"code":"RateLimited","details":"You've reached the upper limit for today's usage. Please try again tomorrow.","template":"You've reached the upper limit for today's usage. Please try again tomorrow.","num":12}}
    """.trimIndent()

    /** 阿里盾处罚页（真风控）—— 与上面必须分到不同一类 */
    private val riskBody = """
        {"ret":["FAIL_SYS_USER_VALIDATE","RGV587_ERROR::SM::哎哟喂,被挤爆啦,请稍后重试"],"data":{"url":"https://chat.qwen.ai/api/v2/chat/completions/_____tmd_____/punish?x5secdata=abc"}}
    """.trimIndent()

    // ---------------- 1. 判据层：纯函数，先钉死分类 ----------------

    @Test
    fun `额度用尽与风控被分成两类`() {
        assertEquals(RiskControl.QUOTA_CODE, RiskControl.blockCode(quotaBody))
        assertEquals(RiskControl.THROTTLE_CODE, RiskControl.blockCode(riskBody))
        assertNotEquals(
            "两者处置建议相反，绝不能归成同一类",
            RiskControl.blockCode(quotaBody),
            RiskControl.blockCode(riskBody),
        )
    }

    @Test
    fun `额度用尽的响应不再被判成风控`() {
        // 回归点：旧窄口径正则含 `rate.?limit`，而 `RateLimited` 里的
        // "rate…limit" 会命中它 —— 这就是误判的确切机制。
        assertFalse(
            "额度用尽不是风控（旧判据在此返回 true，正是 bug 本体）",
            RiskControl.hasUpstreamRiskSignature(quotaBody),
        )
        assertFalse(RiskControl.isRiskControlBlock(quotaBody))
    }

    @Test
    fun `额度用尽与风控的提示互不含对方的建议`() {
        assertTrue(RiskControl.QUOTA_HINT.contains("额度"))
        // 「去过滑块」对额度用尽是无用建议，不能出现
        assertFalse("额度提示不该让用户去过滑块", RiskControl.QUOTA_HINT.contains("滑块"))
        // 反过来，风控提示仍应保留滑块指引
        assertTrue(RiskControl.HINT.contains("滑块"))
        // 两者都要说清"不是网关故障"，否则用户会去查网关
        assertTrue(
            "配额提示应排除网关故障",
            RiskControl.QUOTA_HINT.contains("不是网关故障"),
        )
    }

    @Test
    fun `真正的限流措辞仍算风控`() {
        // 收窄判据不能把真限流一起放掉：它们与 `RateLimited` 同族但形态不同，
        // 而瞬时 429 是**可以**靠等待重试解决的。
        assertTrue(RiskControl.blockCode("HTTP 429 Too Many Requests") == RiskControl.THROTTLE_CODE)
        assertTrue(RiskControl.blockCode("rate limit exceeded") == RiskControl.THROTTLE_CODE)
        assertTrue(GatewayRouter.isRiskControlBlock("rate limit exceeded"))
    }

    @Test
    fun `额度用尽的错误码不在可重试名单里`() {
        // 上游明说"请明天再试"：今天之内重试多少次都是同一结果。
        val c = QwenImageClient("fake-credential", 0)
        assertFalse("配额码必须显式排除", c.isRetryable(RiskControl.QUOTA_CODE))
        // 对照：真正的 5xx 仍要重试（收窄判据不能连这个一起放掉）
        assertTrue(c.isRetryable("UPSTREAM_503"))
        assertTrue(c.isRetryable("UPSTREAM_EMPTY"))
    }

    // ---------------- 2. 客户端层：走真实请求路径 ----------------

    /** 本地假上游：固定返回一个 body，用于验证客户端如何解释它 */
    private lateinit var server: MiniHttpServer
    private var port = 0
    private val paths = ArrayList<String>()

    /**
     * 本地假上游：`chats/new` 一律回「建会话成功」，其余路径回 [body]。
     *
     * 分路径是必须的：被测流程都是「先建会话、再打 completions」，若让建会话
     * 也返回错误体，请求会死在第一步（`BAD_RESPONSE` / `CREATE_CHAT_FAIL`），
     * 根本走不到本文件真正要覆盖的「200 + 错误 JSON」分支。
     */
    private fun startFakeUpstream(body: String, contentType: String = "application/json; charset=UTF-8") {
        paths.clear()
        server = MiniHttpServer(0, "127.0.0.1") { req, res ->
            paths.add(req.path)
            val payload = if (req.path.contains("chats/new")) {
                """{"success":true,"data":{"id":"fake-chat-id"}}"""
            } else {
                body
            }
            res.header("Content-Type", contentType)
            val b = payload.toByteArray(Charsets.UTF_8)
            res.header("Content-Length", b.size.toString())
            res.writeHead(200)
            res.end(b)
        }
        server.start()
        port = server.boundPort
    }

    @Before
    fun setUp() {
        startFakeUpstream(quotaBody)
    }

    @After
    fun tearDown() {
        runCatching { server.stop() }
    }

    private fun client() = QwenImageClient("fake-credential", 0).also {
        it.baseUrl = "http://127.0.0.1:$port"
    }

    private fun chatClient() = QwenClient("fake-credential", 0).also {
        it.baseUrl = "http://127.0.0.1:$port"
    }

    @Test
    fun `文生图遇到额度用尽时报配额码而不是风控码`() = runBlocking {
        val e = runCatching {
            client().generateImage(
                com.qwen2api.tx.core.ImageRequest(prompt = "一只猫", model = "qwen-image-2.0-pro"),
            )
        }.exceptionOrNull()
        assertTrue("应是 QwenException: $e", e is QwenException)
        val qe = e as QwenException
        assertEquals(RiskControl.QUOTA_CODE, qe.code)
        assertEquals(RiskControl.QUOTA_HINT, qe.message)
        // 建会话那一步就该被拦下，不该继续往下打 completions
        assertTrue("应至少请求过 chats/new: $paths", paths.any { it.contains("chats/new") })
    }

    @Test
    fun `文生图遇到真处罚页时仍报风控码`() = runBlocking {
        server.stop()
        startFakeUpstream(riskBody)
        val e = runCatching {
            client().generateImage(
                com.qwen2api.tx.core.ImageRequest(prompt = "一只猫", model = "qwen-image-2.0-pro"),
            )
        }.exceptionOrNull() as QwenException
        assertEquals(RiskControl.THROTTLE_CODE, e.code)
        assertEquals(RiskControl.HINT, e.message)
    }

    @Test
    fun `文本链路遇到额度用尽也报配额码`() = runBlocking {
        // 文本链路此前会把 `RateLimited` 当限流，按 8/15/25s 阶梯等待重试 ——
        // 对一个"明天才恢复"的错误白等三轮。
        val e = runCatching {
            chatClient().chatStream(
                model = "qwen3.8-max",
                messages = listOf(com.qwen2api.tx.core.ChatMessage("user", "hi")),
                thinking = false,
            )
        }.exceptionOrNull()
        assertTrue("应是 QwenException: $e", e is QwenException)
        assertEquals(RiskControl.QUOTA_CODE, (e as QwenException).code)
    }

    // ---------------- 3. 网关层：错误映射与文案 ----------------

    private lateinit var configRepo: MemoryConfigRepository
    private lateinit var gw: MiniHttpServer
    private var gwPort = 0

    private inner class QuotaImageClient : QwenImageClient("fake-credential", 0) {
        override suspend fun generateImage(
            req: com.qwen2api.tx.core.ImageRequest,
            onEvent: (suspend (com.qwen2api.tx.core.ImageEvent) -> Unit)?,
        ) = throw QwenException(RiskControl.QUOTA_CODE, RiskControl.QUOTA_HINT, 502)
    }

    private fun startGateway() {
        configRepo = MemoryConfigRepository()
        configRepo.update {
            it.copyWith(
                apiKey = "sk-qpp-" + UUID.randomUUID().toString().take(12),
                qwenToken = "fake.credential.value",
                defaultModel = "qwen3.8-max",
            )
        }
        val router = GatewayRouter(
            configRepo, MemoryFileStore(),
            { QuotaImageClient() },
            true,
        )
        gw = MiniHttpServer(0, "127.0.0.1") { req, res -> router.handle(req, res) }
        gw.start()
        gwPort = gw.boundPort
    }

    @Test
    fun `网关把配额码映射成 502 且不回显上游原文`() {
        startGateway()
        val conn = URL("http://127.0.0.1:$gwPort/v1/images/generations").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000
        conn.readTimeout = 15000
        conn.setRequestProperty("Authorization", "Bearer ${configRepo.load().apiKey}")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write("""{"prompt":"a cat"}""".toByteArray()) }
        assertEquals(502, conn.responseCode)
        val err = JSONObject(conn.errorStream.bufferedReader().use { it.readText() })
            .getJSONObject("error")
        assertEquals(RiskControl.QUOTA_CODE, err.getString("code"))
        assertEquals("api_error", err.getString("type"))
        val msg = err.getString("message")
        assertTrue("应说明是额度问题: $msg", msg.contains("额度"))
        // 给用户的建议必须是"等明天/换账号"，不能是"过滑块"
        assertFalse("不该建议过滑块: $msg", msg.contains("滑块"))
        gw.stop()
    }
}
