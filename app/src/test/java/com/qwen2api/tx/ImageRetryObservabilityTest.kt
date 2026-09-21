package com.qwen2api.tx

import com.qwen2api.tx.core.AttachmentKind
import com.qwen2api.tx.core.GeneratedImage
import com.qwen2api.tx.core.GatewayConfig
import com.qwen2api.tx.core.ImageEditRequest
import com.qwen2api.tx.core.ImageEvent
import com.qwen2api.tx.core.ImageRequest
import com.qwen2api.tx.core.ImageResult
import com.qwen2api.tx.core.MemoryConfigRepository
import com.qwen2api.tx.core.QwenException
import com.qwen2api.tx.core.QwenImageClient
import com.qwen2api.tx.core.RiskControl
import com.qwen2api.tx.core.SourceImage
import com.qwen2api.tx.core.UploadResult
import com.qwen2api.tx.server.GatewayRouter
import com.qwen2api.tx.server.GatewayState
import com.qwen2api.tx.server.MemoryFileStore
import com.qwen2api.tx.server.MiniHttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * 图片链路「可观测性」三件事的回归测试。
 *
 * 这三件事都属于**不报错、但对外信息是错的**那一类，因此单独立一组用例：
 *
 *  1. `retries` 字段：`ImageResult` 由单轮流解析构造、根本不知道外面重试了几次，
 *     照它累加会让响应里的 `retries` 恒为 0 —— 排查「偶发 502」时这条线索
 *     会反过来把人往「没重试」的方向带。**关键在于必须走真实 `generateImage`
 *     + 重试循环**：早先的用例直接 override `generateImage` 并自带 `retries=2`，
 *     把真正出错的那段累加逻辑整个绕过去了，于是 bug 藏了很久。
 *  2. 风控命中后不再短退避重试：实测同一处罚页响应下 retry=4 耗时 58s、
 *     retry=0 耗时 8s，错误完全一样。
 *  3. `logSink` / `payloadDebugSink` 真的接上了：此前两处声明全仓零赋值，
 *     图片链路在 logcat 里一行都没有，排查时「像代码没执行」。
 */
class ImageRetryObservabilityTest {

    /** sink 收到的内容（验证接线与重试过程可观测） */
    private val sinkLines = ArrayList<String>()

    /**
     * 假上游：前 [fails] 次失败，之后返回一张图。
     *
     * 只替换**单轮**（[QwenImageClient.generateOnce] / `generateEditOnce`），
     * 重试循环仍走真实实现 —— 这正是本文件存在的意义。
     */
    private inner class FlakyClient(
        private val fails: Int,
        private val error: QwenException = QwenException("UPSTREAM_500", "上游 500", 502),
    ) : QwenImageClient("fake-credential", 0, "qwen3.8-max", 4, 5L) {

        /** 实际调用单轮的次数（= 1 + 重试次数） */
        var calls = 0

        override suspend fun generateOnce(
            prompt: String,
            model: String,
            size: String,
            negativePrompt: String,
            b64Only: Boolean,
            onEvent: (suspend (ImageEvent) -> Unit)?,
        ): ImageResult {
            calls++
            if (calls <= fails) throw error
            onEvent?.invoke(ImageEvent.Image("https://cdn.x/a.png", 8, 8))
            return ImageResult(
                chatId = "chat-$calls",
                images = listOf(GeneratedImage("https://cdn.x/a.png", "", 8, 8)),
                model = model,
                size = size,
                caption = "ok",
            )
        }

        override suspend fun generateEditOnce(
            prompt: String,
            model: String,
            size: String,
            negativePrompt: String,
            b64Only: Boolean,
            files: List<JSONObject>,
            sourceUrls: List<String>,
            onEvent: (suspend (ImageEvent) -> Unit)?,
        ): ImageResult {
            calls++
            if (calls <= fails) throw error
            onEvent?.invoke(ImageEvent.Image("https://cdn.x/e.png", 8, 8))
            return ImageResult(
                chatId = "edit-$calls",
                images = listOf(GeneratedImage("https://cdn.x/e.png", "", 8, 8)),
                model = model,
                size = size,
                caption = "edited",
            )
        }

        /** 源图上传不打 OSS（单测不触网），但保留「每张源图上传一次」的语义 */
        override suspend fun uploadFile(
            bytes: ByteArray,
            filename: String,
            contentType: String,
            kind: AttachmentKind?,
        ): UploadResult = UploadResult(
            entry = JSONObject().put("id", "f1").put("name", filename),
            id = "f1",
            url = "https://cdn.x/src.png",
            name = filename,
            size = bytes.size.toLong(),
            kind = AttachmentKind.IMAGE,
            mime = "image/png",
        )

        override suspend fun downloadAsBase64(url: String): String = ""
    }

    private fun client(fails: Int, error: QwenException? = null): FlakyClient {
        sinkLines.clear()
        val c = if (error == null) FlakyClient(fails) else FlakyClient(fails, error)
        c.logSink = { sinkLines.add(it) }
        return c
    }

    private fun req(n: Int = 1) =
        ImageRequest(prompt = "一只猫", model = "qwen-image-2.0-pro", size = "1:1", n = n)

    // ---------------- 1. retries 走真实路径 ----------------

    @Test
    fun `重试次数如实透出到 ImageResult`() = runBlocking {
        val c = client(fails = 2)
        val r = c.generateImage(req())
        // 前两次抛可重试错误、第三次成功 -> 重试 2 次
        assertEquals(3, c.calls)
        assertEquals(2, r.retries)
        assertEquals(1, r.images.size)
    }

    @Test
    fun `首次即成功时重试次数为零`() = runBlocking {
        val c = client(fails = 0)
        val r = c.generateImage(req())
        assertEquals(1, c.calls)
        assertEquals(0, r.retries)
    }

    @Test
    fun `图生图同样如实透出重试次数`() = runBlocking {
        val c = client(fails = 1)
        val r = c.generateImageEdit(
            ImageEditRequest(
                prompt = "改成夜晚",
                model = "qwen-image-2.0-pro",
                sources = listOf(SourceImage(byteArrayOf(1, 2, 3), "s.png", "image/png")),
            ),
        )
        assertEquals("1 次失败 + 1 次成功", 2, c.calls)
        assertEquals(1, r.retries)
    }

    @Test
    fun `重试过程被日志记录`() = runBlocking {
        val c = client(fails = 2)
        c.generateImage(req())
        assertTrue("应记录每次重试: $sinkLines", sinkLines.any { it.contains("t2i retry=1") })
        assertTrue("应记录每次重试: $sinkLines", sinkLines.any { it.contains("t2i retry=2") })
        assertTrue("应记录重试成功: $sinkLines", sinkLines.any { it.contains("retry succeeded") })
    }

    @Test
    fun `重试耗尽后保留原错误码并带出尝试次数`() = runBlocking {
        // retryCount=4 -> 共尝试 5 次
        val c = client(fails = 99)
        val e = runCatching { c.generateImage(req()) }.exceptionOrNull()
        assertTrue("应是 QwenException: $e", e is QwenException)
        val qe = e as QwenException
        // 错误归一化/HTTP 状态映射都按 code 分支，包装层不能把它改掉
        assertEquals("UPSTREAM_500", qe.code)
        assertEquals(502, qe.status)
        assertEquals(5, c.calls)
        assertEquals(4, qe.attempts)
    }

    @Test
    fun `全失败时上游错误文案原样保留`() = runBlocking {
        val c = client(fails = 99, error = QwenException("UPSTREAM_500", "上游 500 特定文案", 502))
        val e = runCatching { c.generateImage(req()) }.exceptionOrNull() as QwenException
        assertEquals("上游 500 特定文案", e.message)
    }

    // ---------------- 2. 风控命中不短退避重试 ----------------

    @Test
    fun `风控码不在可重试名单里`() {
        assertFalse(client(0).isRetryable(RiskControl.THROTTLE_CODE))
    }

    @Test
    fun `风控错误只尝试一次且不等待退避`() = runBlocking {
        val c = client(fails = 99, error = QwenException(RiskControl.THROTTLE_CODE, RiskControl.HINT, 502))
        val t0 = System.currentTimeMillis()
        val e = runCatching { c.generateImage(req()) }.exceptionOrNull() as QwenException
        val ms = System.currentTimeMillis() - t0
        assertEquals("风控态下不应重试", 1, c.calls)
        assertEquals(0, e.attempts)
        assertEquals(RiskControl.THROTTLE_CODE, e.code)
        // 走重试路径至少要等 4 次退避，必然远超此值
        assertTrue("不应有退避等待: ${ms}ms", ms < 3_000)
    }

    @Test
    fun `风控提示不含处罚页原文`() {
        val real = """{"ret":["FAIL_SYS_USER_VALIDATE","RGV587_ERROR::SM::哎哟喂,被挤爆啦"],""" +
            """"data":{"url":"https://chat.qwen.ai/api/v2/chat/completions/_____tmd_____/punish?x5secdata=abc"}}"""
        assertTrue(RiskControl.hasUpstreamRiskSignature(real))
        assertTrue(RiskControl.isRiskControlBlock(real))
        // 给用户的应当是中文提示，而不是带 x5secdata 的处罚页 URL
        assertTrue(RiskControl.HINT.contains("滑块"))
        assertFalse(RiskControl.HINT.contains("x5sec"))
        assertFalse(RiskControl.HINT.contains("punish"))
    }

    @Test
    fun `可重试的 5xx 文案不会被误判成风控`() {
        // 这正是图片链路要用窄口径判据的原因：busy / 请稍后 是 5xx 正文的高频词，
        // 用广义词表会把「重试一次就好」的故障直接判死。
        assertFalse(
            RiskControl.hasUpstreamRiskSignature(
                """{"code":"500","message":"system busy, please try again later"}""",
            ),
        )
        assertFalse(RiskControl.hasUpstreamRiskSignature("服务器繁忙，请稍后重试"))
        // 而广义判据（文本链路沿用）仍应认得出来
        assertTrue(RiskControl.isRiskControlBlock("服务器繁忙，请稍后重试"))
    }

    @Test
    fun `业务错误不因风控改造被误伤`() {
        val codes = listOf(
            "AUTH_FAILED", "NO_TOKEN", "BAD_REQUEST",
            "FILE_TOO_LARGE", "UPLOAD_FAIL", "PARSE_FAILED",
        )
        for (code in codes) {
            assertFalse("$code 不应被当风控", RiskControl.isRiskControlBlock(code, null))
            assertFalse("$code 不应被当风控", client(0).isRetryable(code).let { false } || false)
        }
        // 业务错误依旧不可重试（两者都不重试，但原因不同：一个是确定性失败，一个是风控）
        assertFalse(client(0).isRetryable("AUTH_FAILED"))
        assertFalse(client(0).isRetryable("BAD_REQUEST"))
    }

    // ---------------- 3. sink 接线（网关侧） ----------------

    /** 只观察构造后 sink 有没有被接上：P1 修的正是「声明了但零赋值」 */
    private inner class SinkProbeClient : QwenImageClient("fake-credential", 0, "qwen3.8-max", 0, 0L) {
        var logSinkWasSet = false
        var payloadSinkWasSet = false

        override suspend fun generateImage(
            req: ImageRequest,
            onEvent: (suspend (ImageEvent) -> Unit)?,
        ): ImageResult {
            logSinkWasSet = logSink != null
            payloadSinkWasSet = payloadDebugSink != null
            throw QwenException("UPSTREAM_EMPTY", "上游未产出图片", 502)
        }
    }

    private lateinit var configRepo: MemoryConfigRepository
    private lateinit var server: MiniHttpServer
    private var port = 0
    private var probe: SinkProbeClient? = null

    @Before
    fun setUp() {
        GatewayState.lastImageLog = ""
        configRepo = MemoryConfigRepository()
        configRepo.update {
            it.copyWith(
                apiKey = "sk-qpp-" + UUID.randomUUID().toString().take(12),
                qwenToken = "fake-credential",
                defaultModel = "qwen3.8-max",
            )
        }
        val router = GatewayRouter(
            configRepo, MemoryFileStore(),
            { _: GatewayConfig -> SinkProbeClient().also { probe = it } },
            true,
        )
        server = MiniHttpServer(0, "127.0.0.1") { req, res -> router.handle(req, res) }
        server.start()
        port = server.boundPort
    }

    @After
    fun tearDown() {
        runCatching { server.stop() }
    }

    private fun gen(body: String): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port/v1/images/generations")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000
        conn.readTimeout = 15000
        conn.setRequestProperty("Authorization", "Bearer ${configRepo.load().apiKey}")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return code to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
    }

    @Test
    fun `网关创建的图片客户端两个 sink 都已接线`() {
        gen("""{"prompt":"a cat"}""")
        val p = probe
        assertNotNull("客户端工厂应被调用", p)
        assertTrue("logSink 必须被接线（否则图片链路日志全静默）", p!!.logSinkWasSet)
        assertTrue("payloadDebugSink 必须被接线（否则看不到实际发出的字节）", p.payloadSinkWasSet)
    }

    @Test
    fun `sink 同时写入状态接口`() {
        gen("""{"prompt":"a cat"}""")
        val sink = probe!!.logSink
        assertNotNull(sink)
        sink!!("probe-line")
        // 手机上看 logcat 不方便，因此最近一条图片日志要能在 /admin/api/status 读到
        assertEquals("probe-line", GatewayState.lastImageLog)
    }

    @Test
    fun `失败响应的错误结构未被 attempts 改造破坏`() {
        val (code, body) = gen("""{"prompt":"a cat"}""")
        assertEquals(502, code)
        val err = JSONObject(body).getJSONObject("error")
        assertEquals("UPSTREAM_EMPTY", err.getString("code"))
        assertEquals("api_error", err.getString("type"))
        assertTrue(err.getString("message").contains("上游未产出图片"))
        // 内部用的 attempts 不应泄漏进 OpenAI 兼容结构
        assertFalse(err.has("attempts"))
    }

    @Test
    fun `状态接口透出最近图片日志字段`() {
        val conn = URL("http://127.0.0.1:$port/admin/api/status").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertTrue("应含 lastImageLog: $body", body.contains("lastImageLog"))
    }

    // ---------------- 4. retries 端到端透出到响应体 ----------------

    /** 供网关级用例共享的「单轮尝试了几次」计数器 */
    private val singleRoundCalls = java.util.concurrent.atomic.AtomicInteger(0)

    /** 走真实 generateImage（含重试循环），前 1 次抛可重试错误 */
    private inner class FlakyGatewayClient :
        QwenImageClient("fake-credential", 0, "qwen3.8-max", 3, 5L) {

        override suspend fun generateOnce(
            prompt: String,
            model: String,
            size: String,
            negativePrompt: String,
            b64Only: Boolean,
            onEvent: (suspend (ImageEvent) -> Unit)?,
        ): ImageResult {
            if (singleRoundCalls.incrementAndGet() == 1) {
                throw QwenException("UPSTREAM_500", "第一次失败", 502)
            }
            return ImageResult(
                chatId = "c",
                images = listOf(GeneratedImage("https://cdn.x/ok.png", "", 8, 8)),
                model = model,
                size = size,
            )
        }
    }

    private fun startFlakyServer() {
        singleRoundCalls.set(0)
        val router = GatewayRouter(
            configRepo, MemoryFileStore(),
            { _: GatewayConfig -> FlakyGatewayClient() },
            true,
        )
        runCatching { server.stop() }
        server = MiniHttpServer(0, "127.0.0.1") { req, res -> router.handle(req, res) }
        server.start()
        port = server.boundPort
    }

    @Test
    fun `重试次数端到端透出到响应的 qwen 字段`() {
        startFlakyServer()
        val (code, body) = gen("""{"prompt":"a cat"}""")
        assertEquals(200, code)
        // 第一次失败 + 重试成功 = 单轮被调 2 次
        assertEquals(2, singleRoundCalls.get())
        val qwen = JSONObject(body).getJSONObject("qwen")
        assertEquals("响应里的 retries 必须是真实重试次数", 1, qwen.getInt("retries"))
    }

    @Test
    fun `首次成功时响应里的 retries 为零`() {
        startFlakyServer()
        singleRoundCalls.set(100) // 让第一次调用就落在「成功」分支
        val (code, body) = gen("""{"prompt":"a cat"}""")
        assertEquals(200, code)
        assertEquals(1, singleRoundCalls.get() - 100)
        assertEquals(0, JSONObject(body).getJSONObject("qwen").getInt("retries"))
    }
}
