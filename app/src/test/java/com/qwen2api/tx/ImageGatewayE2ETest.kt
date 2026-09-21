package com.qwen2api.tx

import com.qwen2api.tx.core.GatewayConfig
import com.qwen2api.tx.core.GeneratedImage
import com.qwen2api.tx.core.ImageEvent
import com.qwen2api.tx.core.ImageRequest
import com.qwen2api.tx.core.ImageResult
import com.qwen2api.tx.core.MemoryConfigRepository
import com.qwen2api.tx.core.QwenException
import com.qwen2api.tx.core.QwenImageClient
import com.qwen2api.tx.server.GatewayRouter
import com.qwen2api.tx.server.MemoryFileStore
import com.qwen2api.tx.server.MiniHttpServer
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

/**
 * 文生图端到端测试（真实 HTTP + 假上游）。
 *
 * 覆盖的是「网关这一层」：路由、鉴权、参数归一、响应格式、错误映射。
 * 上游协议本身的解析由 [ImageSseParserTest] 负责；两者合起来才能说
 * 「加了文生图并且它在协议层是通的」。
 *
 * 用假上游是必须的：真打 chat.qwen.ai 需要有效 token、会受风控影响、
 * 还会真的消耗账号额度，无法作为可重复的回归测试。
 */
class ImageGatewayE2ETest {

    private lateinit var configRepo: MemoryConfigRepository
    private lateinit var fileStore: com.qwen2api.tx.core.FileStore
    private lateinit var server: MiniHttpServer
    private var port: Int = 0

    /** 记录假上游收到的请求，供断言参数归一化行为 */
    /** 伪造的上游凭证（结构像 JWT，但绝不是任何真实 token） */
    private val FAKE_CREDENTIAL = listOf("eyJhbGciOiJIUzI1NiJ9", "ZmFrZQ", "c2ln").joinToString(".")

    private val seen = ArrayList<ImageRequest>()

    private inner class FakeImageClient(
        private val result: ImageResult? = null,
        private val fail: QwenException? = null,
    ) : QwenImageClient("fake-token", 0) {
        override suspend fun generateImage(
            req: ImageRequest,
            onEvent: (suspend (ImageEvent) -> Unit)?,
        ): ImageResult {
            seen.add(req)
            fail?.let { throw it }
            onEvent?.invoke(ImageEvent.Image("https://cdn.x/out.png", 1024, 1024))
            return result ?: ImageResult(
                chatId = "fake-chat",
                images = listOf(
                    GeneratedImage("https://cdn.x/out.png", "", 1024, 1024),
                ),
                model = req.model,
                size = req.size,
                caption = "done",
            )
        }

        override suspend fun downloadAsBase64(url: String): String = "ZmFrZWJhc2U2NA=="
    }

    /** 起一个使用假上游的服务器；构造器签名与上一版不同，这里单独封装 */
    private fun startServerWithFake(
        result: ImageResult? = null,
        fail: QwenException? = null,
    ) {
        // `seen` 是字段而非局部变量：同一测试内多次起服务时它必须清空，
        // 否则第二个断言读到的是上一个请求，表现为「参数没被限制」这种假失败。
        seen.clear()
        configRepo = MemoryConfigRepository()
        configRepo.update { it.copyWith(apiKey = "sk-test", qwenToken = FAKE_CREDENTIAL, defaultModel = "qwen3.8-max") }
        fileStore = MemoryFileStore()
        val router = GatewayRouter(
            configRepo, fileStore,
            { cfg: GatewayConfig -> FakeImageClient(result, fail) },
            true,
        )
        server = MiniHttpServer(0, "127.0.0.1") { req, res -> router.handle(req, res) }
        server.start()
        port = server.boundPort
    }

    @Before
    fun setUp() {
        startServerWithFake()
    }

    @After
    fun tearDown() {
        runCatching { server.stop() }
    }

    // ---------------- HTTP 辅助 ----------------

    private fun key(): String = configRepo.load().apiKey

    private data class Resp(val code: Int, val body: String)

    private fun request(
        path: String,
        method: String = "GET",
        bearer: String? = null,
        body: String? = null,
    ): Resp {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5000
        conn.readTimeout = 20000
        bearer?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        return Resp(code, text)
    }

    private fun gen(body: String, bearer: String? = key()): Resp =
        request("/v1/images/generations", "POST", bearer, body)

    // ---------------- 能力自描述 ----------------

    @Test
    fun `GET 描述支持情况与模型清单`() {
        val r = request("/v1/images/generations", "GET", key())
        assertEquals(200, r.code)
        val j = JSONObject(r.body)
        assertEquals(true, j.getBoolean("supported"))
        assertEquals("/v1/images/generations", j.getString("endpoint"))
        val ids = j.getJSONArray("data").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
        assertTrue(ids.contains("qwen-image-3.0-pro"))
        assertTrue(ids.contains("qwen-image-2.0-pro"))
        assertEquals(4, j.getInt("max_n"))
    }

    @Test
    fun `GET 也需要鉴权`() {
        assertEquals(401, request("/v1/images/generations", "GET", null).code)
        assertEquals(401, request("/v1/images/generations", "GET", "wrong").code)
    }

    // ---------------- 生成 ----------------

    @Test
    fun `基本生成返回 OpenAI 兼容结构`() {
        val r = gen("""{"model":"qwen-image-3.0-pro","prompt":"a cat","size":"1:1"}""")
        assertEquals(200, r.code)
        val j = JSONObject(r.body)
        assertTrue(j.has("created"))
        val data = j.getJSONArray("data")
        assertEquals(1, data.length())
        val item = data.getJSONObject(0)
        assertEquals("https://cdn.x/out.png", item.getString("url"))
        assertEquals("a cat", item.getString("revised_prompt"))
        assertEquals(1024, item.getInt("width"))
        // 排查线索放在扩展字段，不污染标准结构
        assertEquals("qwen-image-3.0-pro", j.getJSONObject("qwen").getString("model"))
    }

    @Test
    fun `缺 prompt 报 400 且不触上游`() {
        val r = gen("""{"model":"qwen-image-3.0-pro"}""")
        assertEquals(400, r.code)
        assertTrue(r.body.contains("prompt is required"))
        assertEquals(0, seen.size)
    }

    @Test
    fun `prompt 为空字符串同样拒绝`() {
        assertEquals(400, gen("""{"prompt":"   "}""").code)
        assertEquals(0, seen.size)
    }

    @Test
    fun `非法 JSON 报 400`() {
        assertEquals(400, gen("{not json").code)
    }

    @Test
    fun `未配 token 时明确报错`() {
        configRepo.update { it.copyWith(qwenToken = "") }
        val r = gen("""{"prompt":"a cat"}""")
        assertEquals(400, r.code)
        assertTrue(r.body.contains("no_token"))
    }

    @Test
    fun `未带密钥报 401`() {
        assertEquals(401, gen("""{"prompt":"a cat"}""", bearer = null).code)
        assertEquals(0, seen.size)
    }

    @Test
    fun `模型别名与像素尺寸在网关层被归一化`() {
        startServerWithFake()
        val r = gen("""{"model":"qwen-image","prompt":"a dog","size":"1024x1024"}""")
        assertEquals(200, r.code)
        assertEquals(1, seen.size)
        // 别名 -> 上游 id；像素 -> 最近比例。两者都必须在到达上游前完成。
        assertEquals("qwen-image-2.0-pro", seen[0].model)
        assertEquals("1:1", seen[0].size)
    }

    @Test
    fun `aspect_ratio 作为 size 的别名`() {
        startServerWithFake()
        gen("""{"prompt":"a dog","aspect_ratio":"9:16"}""")
        assertEquals("9:16", seen[0].size)
    }

    @Test
    fun `n 被限制在 1 到 4`() {
        startServerWithFake()
        gen("""{"prompt":"a dog","n":99}""")
        assertEquals(4, seen[0].n)

        startServerWithFake()
        gen("""{"prompt":"a dog","n":0}""")
        assertEquals(1, seen[0].n)
    }

    @Test
    fun `response_format b64_json 触发下载并回填 b64`() {
        startServerWithFake()
        val r = gen("""{"prompt":"a dog","response_format":"b64_json"}""")
        assertEquals(200, r.code)
        assertTrue(seen[0].b64Only)
        val item = JSONObject(r.body).getJSONArray("data").getJSONObject(0)
        assertEquals("ZmFrZWJhc2U2NA==", item.getString("b64_json"))
        // url 在 b64 模式下应为 null 而不是空串：空串会让客户端以为
        // "地址存在但下载失败"，null 才能被 SDK 正确识别为"没有 URL"
        assertTrue(item.isNull("url"))
    }

    @Test
    fun `默认 response_format 走 url 且不下载`() {
        startServerWithFake()
        val r = gen("""{"prompt":"a dog"}""")
        assertFalse(seen[0].b64Only)
        val item = JSONObject(r.body).getJSONArray("data").getJSONObject(0)
        assertFalse(item.isNull("url"))
        assertFalse(item.has("b64_json"))
    }

    @Test
    fun `上游失败被映射成 OpenAI 错误结构`() {
        startServerWithFake(fail = QwenException("UPSTREAM_EMPTY", "上游未产出图片", 502))
        val r = gen("""{"prompt":"a dog"}""")
        assertEquals(502, r.code)
        val err = JSONObject(r.body).getJSONObject("error")
        assertEquals("UPSTREAM_EMPTY", err.getString("code"))
        assertTrue(err.getString("message").contains("上游未产出图片"))
    }

    @Test
    fun `鉴权失败映射为 401`() {
        startServerWithFake(fail = QwenException("AUTH_FAILED", "Token 无效或已过期", 401))
        val r = gen("""{"prompt":"a dog"}""")
        assertEquals(401, r.code)
        assertEquals("AUTH_FAILED", JSONObject(r.body).getJSONObject("error").getString("code"))
    }

    @Test
    fun `坏请求映射为 400`() {
        startServerWithFake(fail = QwenException("BAD_REQUEST", "prompt 不能为空", 400))
        assertEquals(400, gen("""{"prompt":"a dog"}""").code)
    }

    // ---------------- 路由语义 ----------------

    @Test
    fun `GET 允许 HEAD 且返回同样的元信息`() {
        val r = request("/v1/images/generations", "HEAD", key())
        assertEquals(200, r.code)
    }

    @Test
    fun `尾部斜杠被容忍`() {
        val r = request("/v1/images/generations/", "GET", key())
        assertEquals(200, r.code)
    }

    @Test
    fun `PUT 返回 405 且带 Allow 头`() {
        val conn = URL("http://127.0.0.1:$port/v1/images/generations").openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.setRequestProperty("Authorization", "Bearer ${key()}")
        conn.doOutput = true
        conn.outputStream.use { it.write("{}".toByteArray()) }
        assertEquals(405, conn.responseCode)
        val allow = conn.getHeaderField("Allow") ?: ""
        assertTrue("Allow 应包含 POST: $allow", allow.contains("POST"))
        assertTrue("Allow 应包含 GET: $allow", allow.contains("GET"))
    }

    @Test
    fun `不存在的子路径仍是 404 而不是被前缀吞掉`() {
        val r = request("/v1/images", "GET", key())
        assertEquals(404, r.code)
    }

    @Test
    fun `CORS 预检不要求密钥`() {
        val r = request("/v1/images/generations", "OPTIONS", null)
        assertEquals(204, r.code)
    }

    @Test
    fun `chat 端点不因新增路由而回归`() {
        // 路由表是 when 分支顺序敏感的：新增分支插错位置会静默抢走既有路径
        val r = request("/v1/models", "GET", key())
        assertNotNull(r.body)
        assertFalse("不应落到文生图处理器", r.body.contains("qwen-image-2.0-pro\"}"))
    }
}
