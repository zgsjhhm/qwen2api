package com.qwen2api.tx

import com.qwen2api.tx.core.AttachmentKind
import com.qwen2api.tx.core.ChatMessage
import com.qwen2api.tx.core.ChatResult
import com.qwen2api.tx.core.FileStore
import com.qwen2api.tx.core.MemoryConfigRepository
import com.qwen2api.tx.core.QwenClient
import com.qwen2api.tx.core.QwenEvent
import com.qwen2api.tx.core.QwenModel
import com.qwen2api.tx.core.UploadResult
import com.qwen2api.tx.server.GatewayRouter
import com.qwen2api.tx.server.MemoryFileStore
import com.qwen2api.tx.server.MiniHttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * `/v1/files` 上传链路的端到端测试（真实 HTTP + 假上游）。
 *
 * ## 为什么必须补这一层
 * 改动前 `GatewayE2ETest` 只用两条「边界请求」碰过上传端点：
 *   - 没配 qwenToken  → 400 no_token
 *   - body 不是 multipart → 400 bad_request
 * 也就是说 `MultipartParser.parse` **成功**之后的整段——取文件分段、推断
 * MIME/类型、调用上游、写入 [FileStore]、回 OpenAI 规范响应——**一次都没执行过**。
 * 这段恰恰是最容易静默出错的地方：
 *   - multipart 头顺序变化（filename 在 name 前）会让文件字段选不出来
 *   - 二进制正文被分隔符切碎不会抛异常，只会传上去一个坏文件
 *   - 上游抛错时若错误码映射错了，客户端看到的是 200 + 空对象
 *
 * 用假 [QwenClient] 替换上游，链路即可离线、确定性地跑完。
 *
 * 注意：假上游会把**收到的字节**原样回传（放进 url 的 query），
 * 因此「HTTP 层发出去的字节」与「上游收到的字节」是同一个断言对象——
 * 中间任何一环改写/截断数据都会被这条断言抓到。
 */
class FileUploadChainTest {

    private lateinit var configRepo: MemoryConfigRepository
    private lateinit var fileStore: FileStore
    private lateinit var server: MiniHttpServer
    private var port: Int = 0

    private val B = "----FileUploadChainBoundary8f3a"

    // ---------------- 假上游 ----------------

    /** 记录一次 uploadFile 收到的参数 */
    private data class SeenUpload(
        val bytes: ByteArray,
        val filename: String,
        val contentType: String,
        val kind: AttachmentKind?,
    )

    private val uploads = ArrayList<SeenUpload>()

    /** 需要上游抛错时设置的异常（消费一次即清空） */
    private var uploadFailure: Throwable? = null

    private inner class FakeClient : QwenClient("fake-token", 0) {

        override suspend fun uploadFile(
            bytes: ByteArray,
            filename: String,
            contentType: String,
            kind: AttachmentKind?,
        ): UploadResult {
            uploads.add(SeenUpload(bytes, filename, contentType, kind))
            uploadFailure?.let { uploadFailure = null; throw it }
            return UploadResult(
                entry = JSONObject()
                    .put("id", "file-upstream-1")
                    .put("type", "image")
                    .put("name", filename)
                    .put("url", "https://cdn.example/$filename"),
                id = "file-upstream-1",
                url = "https://cdn.example/$filename",
                name = filename,
                size = bytes.size.toLong(),
                kind = kind ?: AttachmentKind.IMAGE,
                mime = contentType,
            )
        }

        override suspend fun chatStream(
            model: String,
            messages: List<ChatMessage>,
            thinking: Boolean,
            files: List<JSONObject>,
            onEvent: (suspend (QwenEvent) -> Unit)?,
        ): ChatResult = throw UnsupportedOperationException("not used")

        override suspend fun listModels(): List<QwenModel> = throw UnsupportedOperationException("not used")
    }

    @Before
    fun setUp() {
        configRepo = MemoryConfigRepository()
        // 未配 token 时上传会在解析前就短路，这里先配上才能走到解析器
        configRepo.update { it.copyWith(qwenToken = "fake-token") }
        fileStore = MemoryFileStore()
        uploads.clear()
        uploadFailure = null
        val router = GatewayRouter(configRepo, fileStore) { FakeClient() }
        server = MiniHttpServer(0, "127.0.0.1") { req, res -> router.handle(req, res) }
        server.start()
        port = server.boundPort
    }

    @After
    fun tearDown() {
        server.stop()
    }

    // ---------------- HTTP 辅助 ----------------

    private fun key(): String = configRepo.load().apiKey

    private data class Resp(val code: Int, val body: String)

    /** 直接发原始 multipart 字节（不用任何客户端库，避免掩盖编码问题） */
    private fun uploadBytes(
        body: ByteArray,
        contentType: String = "multipart/form-data; boundary=$B",
        autoContentType: Boolean = false,
    ): Resp {
        val conn = URL("http://127.0.0.1:$port/v1/files").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000
        conn.readTimeout = 20000
        conn.setRequestProperty("Authorization", "Bearer ${key()}")
        conn.setRequestProperty("Content-Type", contentType)
        conn.doOutput = true
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        conn.disconnect()
        return Resp(code, text)
    }

    /** 组一个规整的 multipart 请求体 */
    private fun multipart(
        filename: String,
        data: ByteArray,
        contentType: String? = "image/png",
        fieldName: String = "file",
        extraFields: Map<String, String> = emptyMap(),
        dispositionOverride: String? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun crlf() = out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))

        extraFields.forEach { (k, v) ->
            ascii("--$B"); crlf()
            ascii("Content-Disposition: form-data; name=\"$k\""); crlf(); crlf()
            out.write(v.toByteArray(Charsets.UTF_8)); crlf()
        }
        ascii("--$B"); crlf()
        ascii(
            dispositionOverride
                ?: "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$filename\"",
        )
        crlf()
        contentType?.let { ascii("Content-Type: $it"); crlf() }
        crlf()
        out.write(data); crlf()
        ascii("--$B--"); crlf()
        return out.toByteArray()
    }

    private fun pngBytes(size: Int = 512): ByteArray {
        val b = ByteArray(size)
        // 真实 PNG magic + 伪随机正文（含 NUL 与高位字节）
        val magic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        magic.copyInto(b)
        for (i in magic.size until size) b[i] = ((i * 37) % 256).toByte()
        return b
    }

    private fun json(r: Resp): JSONObject = JSONObject(r.body)

    // ---------------- 1. 成功路径 ----------------

    @Test
    fun `upload returns openai file object`() {
        val png = pngBytes()
        val r = uploadBytes(multipart("cat.png", png))

        assertEquals("上传成功应返回 200", 200, r.code)
        val j = json(r)
        assertEquals("file", j.optString("object"))
        assertTrue("本网关的 file id 应用 file- 前缀", j.optString("id").startsWith("file-"))
        assertEquals("cat.png", j.optString("filename"))
        assertEquals(png.size.toLong(), j.optLong("bytes"))
        assertTrue("应带创建时间", j.optLong("created_at") > 0)
        assertEquals("默认 purpose", "assistants", j.optString("purpose"))
    }

    @Test
    fun `upstream receives the exact uploaded bytes`() {
        val png = pngBytes(4096)
        uploadBytes(multipart("cat.png", png))

        assertEquals("上游应被调用一次", 1, uploads.size)
        assertArrayEquals(
            "上游必须收到逐字节一致的文件内容（二进制安全）",
            png,
            uploads[0].bytes,
        )
        assertEquals("cat.png", uploads[0].filename)
    }

    @Test
    fun `upstream receives the declared content type`() {
        uploadBytes(multipart("cat.png", pngBytes(), contentType = "image/png"))
        assertEquals("image/png", uploads[0].contentType)
        // 网关不替客户端判类型（kind 由 QwenClient.uploadFile 内部推断），
        // 这一层只保证 MIME 与文件名被原样传下去
        assertNull("网关不应越权指定 kind", uploads[0].kind)
    }

    @Test
    fun `what the gateway passes downstream infers the right kind`() {
        // 端到端不变式：网关传下去的 (contentType, filename) 组合必须能被
        // QwenClient.mimeToKind 识别成正确的附件类型。否则用户传了图片，
        // 上游却按 document 上限/校验处理 —— 比如 10MB 的 PNG 会被拒。
        uploadBytes(multipart("cat.png", pngBytes(), contentType = "image/png"))
        assertEquals(
            AttachmentKind.IMAGE,
            QwenClient.mimeToKind(uploads[0].contentType, uploads[0].filename),
        )

        // 前端把 mp4 标成 application/octet-stream 是常见情况，只能靠扩展名兜底
        uploadBytes(
            multipart("clip.mp4", ByteArray(64) { 1 }, contentType = "application/octet-stream"),
        )
        assertEquals(
            "必须能从扩展名识别出视频",
            AttachmentKind.VIDEO,
            QwenClient.mimeToKind(uploads[1].contentType, uploads[1].filename),
        )
    }

    @Test
    fun `missing content type falls back to a concrete mime`() {
        // 分段里没有 Content-Type 头时，网关按推断出的 kind 补一个具体 MIME，
        // 不能把空字符串透传给上游（OSS 签名会因此失败）。
        // 注意这里走的是 handleFileUpload 的 filename 推断分支（png → image/png）。
        uploadBytes(multipart("cat.png", pngBytes(), contentType = null))
        assertEquals("必须补一个具体 MIME", "image/png", uploads[0].contentType)
    }

    @Test
    fun `missing content type for unknown extension falls back to octet stream`() {
        uploadBytes(multipart("blob.dat", ByteArray(32) { 7 }, contentType = null))
        assertEquals("application/octet-stream", uploads[0].contentType)
        assertEquals(
            AttachmentKind.DOCUMENT,
            QwenClient.mimeToKind(uploads[0].contentType, uploads[0].filename),
        )
    }

    @Test
    fun `purpose field overrides the default`() {
        val r = uploadBytes(multipart("a.txt", "hello".toByteArray(), "text/plain", extraFields = mapOf("purpose" to "fine-tune")))
        assertEquals(200, r.code)
        assertEquals("fine-tune", json(r).optString("purpose"))
    }

    @Test
    fun `record lands in the file registry and is listable`() {
        val r = uploadBytes(multipart("cat.png", pngBytes()))
        val id = json(r).optString("id")

        assertEquals("注册表应有一条记录", 1, fileStore.all().size)
        val list = json(
            (URL("http://127.0.0.1:$port/v1/files").openConnection() as HttpURLConnection).let { c ->
                c.setRequestProperty("Authorization", "Bearer ${key()}")
                val code = c.responseCode
                Resp(code, c.inputStream.bufferedReader().use(BufferedReader::readText))
            },
        )
        // 用 optJSONArray 的空安全分支而不是 .toString()：
        // 字段缺失时 JSONArray("null") 会抛 JSONException，报错指向测试框架而非真实原因。
        val arr = list.optJSONArray("data")
            ?: throw AssertionError("响应缺少 data 数组: ${list}")
        assertEquals(1, arr.length())
        assertEquals(id, arr.optJSONObject(0)!!.optString("id"))
    }

    @Test
    fun `uploaded file can be fetched back by id`() {
        val id = json(uploadBytes(multipart("cat.png", pngBytes()))).optString("id")
        val conn = URL("http://127.0.0.1:$port/v1/files/$id").openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer ${key()}")
        assertEquals(200, conn.responseCode)
        val j = JSONObject(conn.inputStream.bufferedReader().use(BufferedReader::readText))
        assertEquals(id, j.optString("id"))
        assertEquals("cat.png", j.optString("filename"))
    }

    @Test
    fun `content endpoint redirects to the upstream url`() {
        val id = json(uploadBytes(multipart("cat.png", pngBytes()))).optString("id")
        val conn = URL("http://127.0.0.1:$port/v1/files/$id/content").openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Authorization", "Bearer ${key()}")
        assertEquals(302, conn.responseCode)
        assertEquals("https://cdn.example/cat.png", conn.getHeaderField("Location"))
    }

    @Test
    fun `delete removes the record and reports deleted true`() {
        val id = json(uploadBytes(multipart("cat.png", pngBytes()))).optString("id")
        val conn = URL("http://127.0.0.1:$port/v1/files/$id").openConnection() as HttpURLConnection
        conn.requestMethod = "DELETE"
        conn.setRequestProperty("Authorization", "Bearer ${key()}")
        assertEquals(200, conn.responseCode)
        val j = JSONObject(conn.inputStream.bufferedReader().use(BufferedReader::readText))
        assertEquals(true, j.optBoolean("deleted"))
        assertEquals(0, fileStore.all().size)
    }

    // ---------------- 2. 解析器与网关的接缝 ----------------

    @Test
    fun `filename before name is still found as the file field`() {
        // 回归点：旧解析器把 `filename="..."` 里的子串当成 name，
        // handleFileUpload 的 `it.name == "file"` 兜底就会失效 → 400。
        val png = pngBytes(128)
        val r = uploadBytes(
            multipart(
                "cat.png", png,
                dispositionOverride = "Content-Disposition: form-data; filename=\"cat.png\"; name=\"file\"",
            ),
        )
        assertEquals("头顺序变化不得导致上传失败", 200, r.code)
        assertArrayEquals(png, uploads[0].bytes)
    }

    @Test
    fun `file content containing the boundary literal survives the round trip`() {
        // 回归点：分隔符未锚定行首时，正文里的 `--boundary` 会把文件切碎，
        // 而且不报错 —— 静默数据损坏。这里让「源码自带 boundary 字面量」的
        // 文件真的走一遍 HTTP。
        val payload = "dump --$B and --$B again\n".toByteArray(Charsets.ISO_8859_1)
        val r = uploadBytes(multipart("dump.txt", payload, "text/plain"))
        assertEquals(200, r.code)
        assertArrayEquals("上传内容必须与文件内容逐字节一致", payload, uploads[0].bytes)
    }

    @Test
    fun `binary file with nul bytes is not truncated`() {
        val data = ByteArray(2048).also { it[0] = 0; it[1024] = 0; it[2047] = 0 }
        uploadBytes(multipart("blob.bin", data, "application/octet-stream"))
        assertEquals(2048, uploads[0].bytes.size)
        assertArrayEquals(data, uploads[0].bytes)
    }

    @Test
    fun `part with empty filename falls back to the named file field`() {
        // 某些客户端以普通字段形式发文件（无 filename），网关按 name=file 兜底
        val out = ByteArrayOutputStream()
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        ascii("--$B\r\n")
        ascii("Content-Disposition: form-data; name=\"file\"\r\n\r\n")
        out.write("raw-content".toByteArray())
        ascii("\r\n--$B--\r\n")

        val r = uploadBytes(out.toByteArray())
        assertEquals(200, r.code)
        assertEquals("raw-content", String(uploads[0].bytes, Charsets.UTF_8))
    }

    @Test
    fun `first segment with filename wins when several are present`() {
        val out = ByteArrayOutputStream()
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        ascii("--$B\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\nassistants\r\n")
        ascii("--$B\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n\r\nAAA\r\n")
        ascii("--$B\r\nContent-Disposition: form-data; name=\"thumb\"; filename=\"b.txt\"\r\n\r\nBBB\r\n")
        ascii("--$B--\r\n")

        uploadBytes(out.toByteArray())
        assertEquals("a.txt", uploads[0].filename)
        assertEquals("AAA", String(uploads[0].bytes, Charsets.UTF_8))
    }

    // ---------------- 3. 前置校验（保持既有契约） ----------------

    @Test
    fun `bad boundary yields 400 with clear code`() {
        val r = uploadBytes(multipart("cat.png", pngBytes()), contentType = "multipart/form-data")
        assertEquals(400, r.code)
        assertEquals("bad_request", json(r).optJSONObject("error")!!.optString("code"))
        assertEquals("解析失败时上游不得被调用", 0, uploads.size)
    }

    @Test
    fun `missing file field yields 400 and does not call upstream`() {
        // 只有 purpose 字段，没有任何文件
        val out = ByteArrayOutputStream()
        out.write("--$B\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\nassistants\r\n--$B--\r\n".toByteArray())
        val r = uploadBytes(out.toByteArray())
        assertEquals(400, r.code)
        assertEquals(0, uploads.size)
    }

    @Test
    fun `truncated body yields 400 and does not call upstream`() {
        // 请求被截断（缺结束分隔符）时必须保守拒绝，而不是传一个可能不完整的文件
        val full = multipart("cat.png", pngBytes())
        val truncated = full.copyOfRange(0, full.size - 20)
        val r = uploadBytes(truncated)
        assertEquals("截断请求必须被拒绝", 400, r.code)
        assertEquals(0, uploads.size)
    }

    @Test
    fun `upload without auth returns 401 and does not call upstream`() {
        val conn = URL("http://127.0.0.1:$port/v1/files").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$B")
        conn.outputStream.use { it.write(multipart("cat.png", pngBytes())) }
        assertEquals(401, conn.responseCode)
        assertEquals(0, uploads.size)
    }

    @Test
    fun `upload without qwen token returns no_token`() {
        configRepo.update { it.copyWith(qwenToken = "") }
        val r = uploadBytes(multipart("cat.png", pngBytes()))
        assertEquals(400, r.code)
        assertEquals("no_token", json(r).optJSONObject("error")!!.optString("code"))
        assertEquals(0, uploads.size)
    }

    // ---------------- 4. 上游失败映射 ----------------

    @Test
    fun `upstream failure becomes a structured json error`() {
        uploadFailure = com.qwen2api.tx.core.QwenException("UPSTREAM_ERROR", "上游 500", 502)
        val r = uploadBytes(multipart("cat.png", pngBytes()))
        assertEquals("上游错误码必须原样透传", 502, r.code)
        val err = json(r).optJSONObject("error")!!
        assertEquals("UPSTREAM_ERROR", err.optString("code"))
        assertTrue(err.optString("message").contains("500"))
        assertEquals("失败时不应写入注册表", 0, fileStore.all().size)
    }

    @Test
    fun `unexpected upstream exception is normalized not leaked as 500 html`() {
        uploadFailure = RuntimeException("socket closed")
        val r = uploadBytes(multipart("cat.png", pngBytes()))
        assertTrue("必须是 4xx/5xx 的 JSON，而不是未捕获异常", r.code >= 400)
        assertNotNull("应返回结构化 error 对象", json(r).optJSONObject("error"))
        assertEquals(0, fileStore.all().size)
    }

    // ---------------- 5. 鉴权与隔离 ----------------

    @Test
    fun `file endpoints require the bearer key`() {
        val id = json(uploadBytes(multipart("cat.png", pngBytes()))).optString("id")
        assertEquals(401, (URL("http://127.0.0.1:$port/v1/files").openConnection() as HttpURLConnection).responseCode)
        assertEquals(
            401,
            (URL("http://127.0.0.1:$port/v1/files/$id").openConnection() as HttpURLConnection).responseCode,
        )
        assertEquals(
            401,
            (URL("http://127.0.0.1:$port/v1/files/$id/content").openConnection() as HttpURLConnection).responseCode,
        )
    }

    @Test
    fun `unknown file id returns 404 file_not_found`() {
        val conn = URL("http://127.0.0.1:$port/v1/files/file-nope").openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer ${key()}")
        assertEquals(404, conn.responseCode)
        assertEquals("file_not_found", JSONObject(conn.errorStream.bufferedReader().use(BufferedReader::readText)).optJSONObject("error")!!.optString("code"))
    }

    @Test
    fun `file ids are unique across uploads`() {
        val a = json(uploadBytes(multipart("a.png", pngBytes(64)))).optString("id")
        val b = json(uploadBytes(multipart("b.png", pngBytes(64)))).optString("id")
        assertTrue("id 不能重复，否则 content/delete 会串号", a != b)
        assertEquals(2, fileStore.all().size)
    }

    @Test
    fun `null upstream url is handled on the content endpoint`() {
        // 上游偶尔不回 file_url；此时 content 端点应 404 no_content 而不是 302 到空地址
        val rec = com.qwen2api.tx.core.FileRecord(
            id = "file-nourl", qwenId = "q1", url = "", filename = "x.bin",
            bytes = 1, mime = "application/octet-stream", kind = "bin",
            createdAt = 1, purpose = "assistants", entry = JSONObject(),
        )
        fileStore.add(rec)
        val conn = URL("http://127.0.0.1:$port/v1/files/file-nourl/content").openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Authorization", "Bearer ${key()}")
        assertEquals(404, conn.responseCode)
        assertEquals("no_content", JSONObject(conn.errorStream.bufferedReader().use(BufferedReader::readText)).optJSONObject("error")!!.optString("code"))
        assertNull("不该有 Location 头", conn.getHeaderField("Location"))
    }
}
