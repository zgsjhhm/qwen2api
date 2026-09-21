package com.qwen2api.tx

import com.qwen2api.tx.core.B64
import com.qwen2api.tx.core.GatewayConfig
import com.qwen2api.tx.core.ImageEditRequest
import com.qwen2api.tx.core.ImageRequest
import com.qwen2api.tx.core.ImageResult
import com.qwen2api.tx.core.MemoryConfigRepository
import com.qwen2api.tx.core.QwenImageClient
import com.qwen2api.tx.core.QwenImageSseParser
import com.qwen2api.tx.core.SourceImage
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
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 图生图（`/v1/images/edits`）回归测试。
 *
 * 分三层，因为它们失败的**症状完全不同**，混在一起测会定位不到真正坏掉的那层：
 *  1. 解析层：`image_edit` phase 被认出来、源图 URL 被剔除（坏了表现为「跑完什么也没出」）；
 *  2. 客户端层：重试判定与退避（坏了表现为「偶发 502」或「被风控却打得更凶」）；
 *  3. 网关层：multipart/JSON 双形态入参、错误映射、路由表（坏了表现为 400/404）。
 */
class ImageEditTest {

    // ---------------- 1. 解析层 ----------------

    private fun data(json: String): String = "data: $json\n"

    @Test
    fun `image_edit phase 被识别为出图而不是未知事件`() {
        val p = QwenImageSseParser()
        val evts = p.feed(
            data(
                """{"choices":[{"delta":{"phase":"image_edit",
                   "content":"https://cdn.qwen.ai/edited/xyz.png",
                   "extra":{"output_image_hw":[[768,1024]]}}}]}""",
            ),
        )
        val img = evts.filterIsInstance<com.qwen2api.tx.core.ImageEvent.Image>().single()
        assertEquals("https://cdn.qwen.ai/edited/xyz.png", img.url)
        // output_image_hw 的顺序是 [高, 宽]，与 t2i 同一套约定
        assertEquals(1024, img.width)
        assertEquals(768, img.height)
        assertEquals(1, p.images.size)
    }

    @Test
    fun `image_edit_think 归到 Thinking 且不算出图`() {
        val p = QwenImageSseParser()
        val evts = p.feed(
            data("""{"choices":[{"delta":{"phase":"image_edit_think","content":"调整背景色调…"}}]}"""),
        )
        assertTrue(evts.any { it is com.qwen2api.tx.core.ImageEvent.Thinking })
        assertEquals(0, p.images.size)
    }

    @Test
    fun `回显的源图被剔除`() {
        val p = QwenImageSseParser()
        p.excludeSources(listOf("https://cdn.qwen.ai/in/source.png"))
        p.feed(
            data(
                """{"choices":[{"delta":{"phase":"image_edit","content_list":[
                   {"phase":"image_edit","content":"https://cdn.qwen.ai/in/source.png"},
                   {"phase":"image_edit","content":"https://cdn.qwen.ai/edited/out.png"}]}}]}""",
            ),
        )
        assertEquals(1, p.images.size)
        assertEquals("https://cdn.qwen.ai/edited/out.png", p.images[0].url)
    }

    @Test
    fun `源图带查询串时同样被剔除`() {
        val p = QwenImageSseParser()
        p.excludeSources(listOf("https://cdn.qwen.ai/in/source.png?sig=abc&t=1"))
        p.feed(
            data("""{"choices":[{"delta":{"phase":"image_edit","content":"https://cdn.qwen.ai/in/source.png"}}]}"""),
        )
        assertEquals(0, p.images.size)
    }

    @Test
    fun `源图与产出同源时只保留产出`() {
        // 上游确实会把改图结果放在同一个 bucket 下，只有路径不同。
        // 用「同 host 全部剔除」这种粗暴规则会连产出一起干掉，这里钉住必须按完整 URL 比。
        val p = QwenImageSseParser()
        p.excludeSources(listOf("https://cdn.qwen.ai/f/in.png"))
        p.feed(
            data("""{"choices":[{"delta":{"phase":"image_edit","content":"https://cdn.qwen.ai/f/out.png"}}]}"""),
        )
        assertEquals(1, p.images.size)
    }

    @Test
    fun `未登记源图时全部保留`() {
        // 文生图路径不能受这个功能影响
        val p = QwenImageSseParser()
        p.feed(data("""{"choices":[{"delta":{"phase":"image_gen","content":"https://x/a.png"}}]}"""))
        assertEquals(1, p.images.size)
    }

    // ---------------- 2. 客户端层：重试 ----------------

    private fun client(retry: Int = 2): QwenImageClient =
        QwenImageClient("fake-credential", 0, "qwen3.8-max", retry, 1000L)

    @Test
    fun `瞬时故障可重试`() {
        val c = client()
        assertTrue(c.isRetryable("UPSTREAM_500"))
        assertTrue(c.isRetryable("UPSTREAM_502"))
        assertTrue(c.isRetryable("UPSTREAM_503"))
        assertTrue(c.isRetryable("NETWORK"))
        assertTrue(c.isRetryable("NETWORK_TIMEOUT"))
        assertTrue(c.isRetryable("UPSTREAM_EMPTY"))
    }

    @Test
    fun `确定性失败不重试`() {
        val c = client()
        // 这些重试只会把同样的错误再打一遍：token 不对、参数写错、文件太大、格式被拒
        assertFalse(c.isRetryable("AUTH_FAILED"))
        assertFalse(c.isRetryable("NO_TOKEN"))
        assertFalse(c.isRetryable("BAD_REQUEST"))
        assertFalse(c.isRetryable("FILE_TOO_LARGE"))
        assertFalse(c.isRetryable("UPLOAD_FAIL"))
        assertFalse(c.isRetryable("PARSE_FAILED"))
    }

    @Test
    fun `退避指数增长且有上限`() {
        val c = client()
        // attempt 是「第几次重试」，从 0 开始：首次重试就该等一个 base
        assertEquals(1000L, c.retryDelayMs(0))
        assertEquals(2000L, c.retryDelayMs(1))
        assertEquals(4000L, c.retryDelayMs(2))
        // 再久就不如让调用方自己重试，而且会拖长客户端超时
        assertEquals(QwenImageClient.MAX_RETRY_BACKOFF_MS, c.retryDelayMs(20))
        assertTrue(c.retryDelayMs(20) <= QwenImageClient.MAX_RETRY_BACKOFF_MS)
    }

    @Test
    fun `退避为 0 时立即重试`() {
        val c = QwenImageClient("fake-credential", 0, "qwen3.8-max", 2, 0L)
        assertEquals(0L, c.retryDelayMs(1))
        assertEquals(0L, c.retryDelayMs(5))
    }

    // ---------------- 3. 网关层 ----------------

    private lateinit var configRepo: MemoryConfigRepository
    private lateinit var fileStore: com.qwen2api.tx.core.FileStore
    private lateinit var server: MiniHttpServer
    private var port: Int = 0

    /** 假上游收到的图生图请求（供断言参数传递） */
    private val edits = ArrayList<ImageEditRequest>()

    /** 假上游收到的文生图请求（确保 edits 没抢走 generations 的流量） */
    private val gens = ArrayList<ImageRequest>()

    /** 一个合法的最小 PNG（8 字节签名 + IHDR 头足够让魔数判定命中） */
    private val smallestPng: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
    )

    private inner class FakeImageClient(
        private val editResult: ImageResult? = null,
        private val editFail: com.qwen2api.tx.core.QwenException? = null,
    ) : QwenImageClient("fake-credential", 0, "qwen3.8-max", 0, 0L) {

        override suspend fun generateImage(
            req: ImageRequest,
            onEvent: (suspend (com.qwen2api.tx.core.ImageEvent) -> Unit)?,
        ): ImageResult {
            gens.add(req)
            return ImageResult(
                chatId = "gen-chat",
                images = listOf(com.qwen2api.tx.core.GeneratedImage("https://cdn.x/gen.png", "", 0, 0)),
                model = req.model,
                size = req.size,
            )
        }

        override suspend fun generateImageEdit(
            req: ImageEditRequest,
            onEvent: (suspend (com.qwen2api.tx.core.ImageEvent) -> Unit)?,
        ): ImageResult {
            edits.add(req)
            // 真实实现里 uploadFile 在这之前对每张源图各调一次；
            // 这里照样记一笔，好让"多图应上传多次"这条断言仍然有效。
            for (s in req.sources) uploaded.add(s.filename to s.bytes.size)
            editFail?.let { throw it }
            onEvent?.invoke(
                com.qwen2api.tx.core.ImageEvent.Image("https://cdn.x/edited.png", 0, 0),
            )
            return editResult ?: ImageResult(
                chatId = "edit-chat",
                images = listOf(
                    com.qwen2api.tx.core.GeneratedImage("https://cdn.x/edited.png", "", 0, 0),
                ),
                model = req.model,
                size = req.size,
                caption = "edited",
            )
        }

        override suspend fun downloadAsBase64(url: String): String = "ZWRpdGVkYmFzZTY0"
    }

    private val uploaded = ArrayList<Pair<String, Int>>()

    private fun startServer(
        editResult: ImageResult? = null,
        editFail: com.qwen2api.tx.core.QwenException? = null,
    ) {
        edits.clear()
        gens.clear()
        uploaded.clear()
        configRepo = MemoryConfigRepository()
        configRepo.update {
            it.copyWith(
                apiKey = "sk-qpp-" + UUID.randomUUID().toString().take(12),
                qwenToken = "fake-credential-value",
                defaultModel = "qwen3.8-max",
            )
        }
        fileStore = MemoryFileStore()
        val router = GatewayRouter(
            configRepo, fileStore,
            { cfg: GatewayConfig -> FakeImageClient(editResult, editFail) },
            true,
        )
        server = MiniHttpServer(0, "127.0.0.1") { req, res -> router.handle(req, res) }
        server.start()
        port = server.boundPort
    }

    @Before
    fun setUp() = startServer()

    @After
    fun tearDown() {
        runCatching { server.stop() }
    }

    private fun key(): String = configRepo.load().apiKey

    private data class Resp(val code: Int, val body: String, val headers: Map<String, List<String>>)

    /** multipart 造体：手写而不是引库，保证测试不依赖被测代码之外的实现 */
    private fun multipartBody(
        boundary: String,
        fields: List<Pair<String, String>>,
        files: List<Triple<String, String, ByteArray>>,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        for ((k, v) in fields) {
            w("--$boundary\r\n")
            w("Content-Disposition: form-data; name=\"$k\"\r\n\r\n")
            w(v)
            w("\r\n")
        }
        for ((k, name, bytes) in files) {
            w("--$boundary\r\n")
            w("Content-Disposition: form-data; name=\"$k\"; filename=\"$name\"\r\n")
            w("Content-Type: image/png\r\n\r\n")
            out.write(bytes)
            w("\r\n")
        }
        w("--$boundary--\r\n")
        return out.toByteArray()
    }

    private fun request(
        path: String,
        method: String = "GET",
        bearer: String? = null,
        body: ByteArray? = null,
        contentType: String? = null,
    ): Resp {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5000
        conn.readTimeout = 20000
        bearer?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            conn.doOutput = true
            contentType?.let { conn.setRequestProperty("Content-Type", it) }
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        val heads = conn.headerFields.orEmpty()
            .filterKeys { it != null }
            .mapKeys { (k, _) -> k.lowercase() }
        return Resp(code, text, heads)
    }

    private fun editJson(body: String, bearer: String? = key()): Resp = request(
        "/v1/images/edits", "POST", bearer,
        body.toByteArray(Charsets.UTF_8), "application/json",
    )

    private fun editMultipart(
        prompt: String,
        sources: List<Pair<String, ByteArray>>,
        extraFields: List<Pair<String, String>> = emptyList(),
        fieldName: String = "image",
        bearer: String? = key(),
    ): Resp {
        val boundary = "----q2a" + UUID.randomUUID().toString().take(8)
        val body = multipartBody(
            boundary,
            listOf("prompt" to prompt) + extraFields,
            sources.mapIndexed { i, (name, bytes) ->
                Triple(if (sources.size == 1) fieldName else "${fieldName}_${i + 1}", name, bytes)
            },
        )
        return request(
            "/v1/images/edits", "POST", bearer, body,
            "multipart/form-data; boundary=$boundary",
        )
    }

    // ---- 自描述 ----

    @Test
    fun `GET 描述图生图能力`() {
        val r = request("/v1/images/edits", "GET", key())
        assertEquals(200, r.code)
        val j = JSONObject(r.body)
        assertEquals(true, j.getBoolean("supported"))
        assertEquals("/v1/images/edits", j.getString("endpoint"))
        assertEquals(4, j.getInt("max_images"))
        assertTrue(j.getInt("max_image_bytes") > 0)
    }

    @Test
    fun `GET 图生图描述需要鉴权`() {
        assertEquals(401, request("/v1/images/edits", "GET", null).code)
    }

    // ---- multipart ----

    @Test
    fun `multipart 单图改图返回 OpenAI 结构`() {
        val r = editMultipart("把背景换成夜晚", listOf("src.png" to smallestPng))
        assertEquals(200, r.code)
        val j = JSONObject(r.body)
        val item = j.getJSONArray("data").getJSONObject(0)
        assertEquals("https://cdn.x/edited.png", item.getString("url"))
        assertEquals("把背景换成夜晚", item.getString("revised_prompt"))
        // 源图张数回显，便于排查「传了几张」
        assertEquals(1, j.getJSONObject("qwen").getInt("source_count"))
        assertEquals(1, edits.size)
        assertEquals(1, edits[0].sources.size)
    }

    @Test
    fun `multipart 字段名 image 与 image 编号变体都能识别`() {
        for (field in listOf("image", "image[]", "images")) {
            startServer()
            val r = editMultipart("改一下", listOf("a.png" to smallestPng), fieldName = field)
            assertEquals("字段 $field 应被识别为源图", 200, r.code)
            assertEquals(1, edits.size)
        }
    }

    @Test
    fun `多图按编号字段上传`() {
        val r = editMultipart(
            "合并两张图",
            listOf("a.png" to smallestPng, "b.png" to smallestPng),
        )
        assertEquals(200, r.code)
        assertEquals(1, edits.size)
        assertEquals(2, edits[0].sources.size)
        assertEquals(2, uploaded.size)
    }

    @Test
    fun `忽略 mask 时在响应里明确说明`() {
        // 静默忽略会让调用方以为做了局部重绘，而结果其实是整图重画
        val boundary = "----q2a" + UUID.randomUUID().toString().take(8)
        val body = multipartBody(
            boundary,
            listOf("prompt" to "局部改色"),
            listOf(
                Triple("image", "src.png", smallestPng),
                Triple("mask", "m.png", smallestPng),
            ),
        )
        val r = request(
            "/v1/images/edits", "POST", key(), body,
            "multipart/form-data; boundary=$boundary",
        )
        assertEquals(200, r.code)
        val qwen = JSONObject(r.body).getJSONObject("qwen")
        assertTrue(qwen.has("ignored_mask"))
        assertEquals(1, edits.size)
        // mask 不能被当成第二张源图传上去
        assertEquals(1, edits[0].sources.size)
    }

    @Test
    fun `multipart 缺源图报 400`() {
        val boundary = "----q2a" + UUID.randomUUID().toString().take(8)
        val body = multipartBody(boundary, listOf("prompt" to "改一下"), emptyList())
        val r = request(
            "/v1/images/edits", "POST", key(), body,
            "multipart/form-data; boundary=$boundary",
        )
        assertEquals(400, r.code)
        assertTrue(r.body.contains("缺少源图"))
        assertEquals(0, edits.size)
    }

    @Test
    fun `multipart 缺 prompt 报 400`() {
        val boundary = "----q2a" + UUID.randomUUID().toString().take(8)
        val body = multipartBody(
            boundary, emptyList(),
            listOf(Triple("image", "a.png", smallestPng)),
        )
        val r = request(
            "/v1/images/edits", "POST", key(), body,
            "multipart/form-data; boundary=$boundary",
        )
        assertEquals(400, r.code)
        assertTrue(r.body.contains("prompt is required"))
    }

    @Test
    fun `超过源图张数上限报 400`() {
        val many = (1..GatewayRouter.MAX_EDIT_SOURCES + 1).map { "s$it.png" to smallestPng }
        val r = editMultipart("太多张了", many)
        assertEquals(400, r.code)
        assertTrue(r.body.contains("最多"))
        assertEquals(0, edits.size)
    }

    @Test
    fun `源图超体积报 400`() {
        val big = ByteArray(GatewayRouter.MAX_EDIT_SOURCE_BYTES + 1)
        // 填入 PNG 魔数，确保拒绝理由是体积而不是空内容
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47).copyInto(big)
        val r = editMultipart("太大", listOf("big.png" to big))
        assertEquals(400, r.code)
        assertTrue(r.body.contains("上限"))
        assertEquals(0, edits.size)
    }

    // ---- JSON ----

    @Test
    fun `JSON 形态接受裸 base64`() {
        val b64 = B64.encode(smallestPng)
        val r = editJson("""{"prompt":"改成油画","image":"$b64"}""")
        assertEquals(200, r.code)
        assertEquals(1, edits.size)
        assertEquals(smallestPng.size, edits[0].sources[0].bytes.size)
        // 魔数判定优先于文件名：没有文件名也要认出 PNG
        assertEquals("image/png", edits[0].sources[0].contentType)
    }

    @Test
    fun `JSON 形态接受 data URI`() {
        val r = editJson("""{"prompt":"加雪","image":"data:image/jpeg;base64,${B64.encode(smallestPng)}"}""")
        assertEquals(200, r.code)
        assertEquals("image/jpeg", edits[0].sources[0].contentType)
    }

    @Test
    fun `JSON 形态接受对象与 images 数组`() {
        val b64 = B64.encode(smallestPng)
        val r = editJson(
            """{"prompt":"融合","images":[{"data":"$b64"},{"b64":"$b64"}]}""",
        )
        assertEquals(200, r.code)
        assertEquals(2, edits[0].sources.size)
    }

    @Test
    fun `JSON 里的 http 链接被明确拒绝而不是静默丢掉`() {
        val r = editJson("""{"prompt":"改","image":"https://example.com/a.png"}""")
        assertEquals(400, r.code)
        assertTrue(r.body.contains("不支持 http"))
        assertEquals(0, edits.size)
    }

    @Test
    fun `JSON 非法 base64 报 400`() {
        val r = editJson("""{"prompt":"改","image":"不是base64!!!"}""")
        assertEquals(400, r.code)
        // 关键是不能被当成"合法但空的图片"放过去：那种情况下上游会回一句含糊的失败，
        // 真正的入参错误被完全掩盖
        val err = JSONObject(r.body).getJSONObject("error")
        assertEquals("bad_request", err.getString("code"))
        assertEquals(0, edits.size)
    }

    @Test
    fun `JSON 传了非图片字节报 400`() {
        // 合法 base64 但内容不是图片：必须拦下来，而不是把垃圾传给上游
        val r = editJson("""{"prompt":"改","image":"${B64.encode("hello world".toByteArray())}"}""")
        assertEquals(400, r.code)
        assertTrue(r.body.contains("不是可识别的图片格式"))
        assertEquals(0, edits.size)
    }

    @Test
    fun `data URI 缺 base64 标记报 400`() {
        val r = editJson("""{"prompt":"改","image":"data:image/png,notbase64"}""")
        assertEquals(400, r.code)
        assertTrue(r.body.contains("base64"))
    }

    @Test
    fun `JSON 缺 prompt 报 400`() {
        val b64 = B64.encode(smallestPng)
        assertEquals(400, editJson("""{"image":"$b64"}""").code)
    }

    @Test
    fun `JSON 默认回 url 而 b64_json 触发下载`() {
        val b64 = B64.encode(smallestPng)
        val r1 = editJson("""{"prompt":"改","image":"$b64"}""")
        assertEquals(200, r1.code)
        assertFalse(edits[0].b64Only)
        assertFalse(JSONObject(r1.body).getJSONArray("data").getJSONObject(0).isNull("url"))

        startServer()
        val r2 = editJson("""{"prompt":"改","image":"$b64","response_format":"b64_json"}""")
        assertEquals(200, r2.code)
        assertTrue(edits[0].b64Only)
        val item = JSONObject(r2.body).getJSONArray("data").getJSONObject(0)
        assertEquals("ZWRpdGVkYmFzZTY0", item.getString("b64_json"))
        assertTrue(item.isNull("url"))
    }

    // ---- 参数归一与错误映射 ----

    @Test
    fun `图生图同样归一模型与尺寸`() {
        val b64 = B64.encode(smallestPng)
        editJson("""{"prompt":"改","image":"$b64","model":"qwen-image","size":"1024x1024"}""")
        assertEquals("qwen-image-2.0-pro", edits[0].model)
        assertEquals("1:1", edits[0].size)
    }

    @Test
    fun `图生图 n 被限制在 1 到 4`() {
        val b64 = B64.encode(smallestPng)
        editJson("""{"prompt":"改","image":"$b64","n":99}""")
        assertEquals(4, edits[0].n)
    }

    @Test
    fun `上游空结果映射为 502`() {
        startServer(editFail = com.qwen2api.tx.core.QwenException("UPSTREAM_EMPTY", "上游未返回任何改图结果", 502))
        val b64 = B64.encode(smallestPng)
        val r = editJson("""{"prompt":"改","image":"$b64"}""")
        assertEquals(502, r.code)
        assertEquals(
            "UPSTREAM_EMPTY",
            JSONObject(r.body).getJSONObject("error").getString("code"),
        )
    }

    @Test
    fun `未配 token 时图生图明确报错`() {
        configRepo.update { it.copyWith(qwenToken = "") }
        val b64 = B64.encode(smallestPng)
        val r = editJson("""{"prompt":"改","image":"$b64"}""")
        assertEquals(400, r.code)
        assertTrue(r.body.contains("no_token"))
        assertEquals(0, edits.size)
    }

    @Test
    fun `未带密钥报 401`() {
        val b64 = B64.encode(smallestPng)
        assertEquals(401, editJson("""{"prompt":"改","image":"$b64"}""", bearer = null).code)
        assertEquals(0, edits.size)
    }

    @Test
    fun `重试次数透出到响应便于排查`() {
        startServer(
            editResult = ImageResult(
                chatId = "c",
                images = listOf(com.qwen2api.tx.core.GeneratedImage("https://cdn.x/e.png", "", 0, 0)),
                model = "qwen-image-2.0-pro",
                size = "1:1",
                retries = 2,
            ),
        )
        val b64 = B64.encode(smallestPng)
        val r = editJson("""{"prompt":"改","image":"$b64"}""")
        assertEquals(2, JSONObject(r.body).getJSONObject("qwen").getInt("retries"))
    }

    // ---- 路由语义 ----

    @Test
    fun `PUT edits 返回 405 且带 Allow`() {
        val conn = URL("http://127.0.0.1:$port/v1/images/edits").openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.setRequestProperty("Authorization", "Bearer ${key()}")
        conn.doOutput = true
        conn.outputStream.use { it.write("{}".toByteArray()) }
        assertEquals(405, conn.responseCode)
        val allow = conn.getHeaderField("Allow") ?: ""
        assertTrue(allow.contains("POST"))
        assertTrue(allow.contains("GET"))
    }

    @Test
    fun `edits 与 generations 互不抢路由`() {
        // 两个路径前缀相同，when 分支写错顺序会静默互吞
        startServer()
        val b64 = B64.encode(smallestPng)
        editJson("""{"prompt":"改","image":"$b64"}""")
        assertEquals(1, edits.size)
        assertEquals(0, gens.size)

        startServer()
        request(
            "/v1/images/generations", "POST", key(),
            """{"prompt":"画只猫"}""".toByteArray(Charsets.UTF_8), "application/json",
        )
        assertEquals(1, gens.size)
        assertEquals(0, edits.size)
    }

    @Test
    fun `edits 尾斜杠被容忍`() {
        assertEquals(200, request("/v1/images/edits/", "GET", key()).code)
    }

    @Test
    fun `不存在的 images 子路径仍是 404`() {
        assertEquals(404, request("/v1/images/variations", "POST", key()).code)
    }
}
