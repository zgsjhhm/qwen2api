package com.qwen2api.tx

import com.qwen2api.tx.core.AttachmentKind
import com.qwen2api.tx.core.B64
import com.qwen2api.tx.core.ChatMessage
import com.qwen2api.tx.core.ChatResult
import com.qwen2api.tx.core.FileRecord
import com.qwen2api.tx.core.FileStore
import com.qwen2api.tx.core.QwenClient
import com.qwen2api.tx.core.QwenEvent
import com.qwen2api.tx.core.QwenException
import com.qwen2api.tx.core.QwenModel
import com.qwen2api.tx.core.UploadResult
import com.qwen2api.tx.server.AttachmentResolver
import com.qwen2api.tx.server.MemoryFileStore
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * 多模态附件解析链路（[AttachmentResolver]）的单元测试。
 *
 * ## 为什么必须补
 * `GatewayE2ETest` 只从**网关外部**碰过附件安全策略（白名单 / 内网地址判定），
 * `FileUploadChainTest` 只覆盖 `/v1/files` 这条「客户端主动上传」的路。
 * 而真正把 OpenAI 多模态 content 数组翻译成 Qwen `files[]` 的
 * [AttachmentResolver.loadBytes] / `resolveSuspend` **一行都没被执行过**：
 *
 * ```
 *   POST /v1/chat/completions
 *     {"messages":[{"content":[
 *        {"type":"text","text":"这张图是什么"},
 *        {"type":"image_url","image_url":{"url":"data:image/png;base64,..."}},
 *        {"type":"file","file":{"file_id":"file-xxx"}}]}]}
 *            │
 *            ├─ loadBytes(data URI / 远程 URL)  ← 零覆盖
 *            ├─ uploadFile -> files[] 条目      ← 只有「客户端上传」路径被覆盖
 *            ├─ content 原地扁平化为纯文本      ← 零覆盖
 *            └─ humanize(附件类错误提示)        ← 零覆盖
 * ```
 *
 * 这段代码的失效方式全是**静默**的：
 *  - data URI 的 mime/扩展名推导错 → 上传的文件名变成 `media.plain; charset=utf-8`，
 *    文档解析（`parse=true`）按扩展名判类型，于是"文档看起来传上去了但模型读不到内容"；
 *  - 响应体先整体读进内存再判大小上限 → 白名单域名返回一个超大响应就能把进程内存吃掉
 *    （上限存在但**永远来得太晚**）；
 *  - 附件类错误在 `files` 为空时不再附加「换 omni 模型」的提示 → 用户拿到一句无法行动的上游报错。
 *
 * 本测试用假 [QwenClient] 替换上游：`loadBytes` / `resolveSuspend` / `humanize`
 * 全部可离线、确定性地跑完，且**断言到「上游收到的字节与文件名」**这一层。
 */
class AttachmentResolveTest {

    // ---------------- 假上游 ----------------

    private data class Seen(
        val bytes: ByteArray,
        val filename: String,
        val contentType: String,
        val kind: AttachmentKind?,
    )

    private val uploads = ArrayList<Seen>()

    private inner class FakeClient : QwenClient("fake-token", 0) {

        override suspend fun uploadFile(
            bytes: ByteArray,
            filename: String,
            contentType: String,
            kind: AttachmentKind?,
        ): UploadResult {
            uploads.add(Seen(bytes, filename, contentType, kind))
            val id = "file-up-${uploads.size}"
            return UploadResult(
                entry = JSONObject().put("id", id).put("name", filename).put("type", kind?.name?.lowercase() ?: "file"),
                id = id,
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
        ): ChatResult = throw UnsupportedOperationException("本测试不跑对话")

        override suspend fun listModels(): List<QwenModel> = emptyList()
    }

    private fun store(): FileStore = MemoryFileStore()

    private fun resolve(messages: MutableList<ChatMessage>, files: FileStore) =
        AttachmentResolver.resolve(FakeClient(), messages, files)

    // ---------------- 构造辅助 ----------------

    private fun dataUri(mime: String, payload: ByteArray): String =
        "data:$mime;base64," + B64.encode(payload)

    private fun textPart(t: String): Map<String, Any?> = mapOf("type" to "text", "text" to t)

    private fun imagePart(url: String): Map<String, Any?> =
        mapOf("type" to "image_url", "image_url" to mapOf("url" to url))

    private fun videoPart(url: String): Map<String, Any?> =
        mapOf("type" to "video_url", "video_url" to mapOf("url" to url))

    private fun audioPart(data: String, format: String?): Map<String, Any?> =
        mapOf("type" to "input_audio", "input_audio" to mapOf("data" to data, "format" to format))

    private fun fileIdPart(id: String): Map<String, Any?> =
        mapOf("type" to "file", "file" to mapOf("file_id" to id))

    private fun fileDataPart(data: String, name: String?): Map<String, Any?> {
        val f = HashMap<String, Any?>()
        f["file_data"] = data
        if (name != null) f["filename"] = name
        return mapOf("type" to "file", "file" to f)
    }

    private fun one(vararg parts: Map<String, Any?>): MutableList<ChatMessage> =
        arrayListOf(ChatMessage("user", parts.toList()))

    /** 断言抛出的是 BAD_REQUEST 并返回消息文本 */
    private fun badRequest(block: () -> Unit): String {
        try {
            block()
        } catch (e: QwenException) {
            assertEquals("BAD_REQUEST", e.code)
            return e.message
        }
        throw AssertionError("本应抛出 QwenException(BAD_REQUEST)")
    }

    // ================= 1. data URI 解析 =================

    @Test
    fun `base64 data uri yields bytes and mime`() {
        val raw = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0x7F)
        val l = AttachmentResolver.loadBytes(dataUri("image/png", raw), "image_url")
        assertArrayEquals(raw, l.bytes)
        assertEquals("image/png", l.mime)
    }

    @Test
    fun `non base64 data uri is percent decoded as utf8`() {
        val l = AttachmentResolver.loadBytes("data:text/plain,hello%20world", "file.file_data")
        assertEquals("hello world", String(l.bytes, Charsets.UTF_8))
        assertEquals("text/plain", l.mime)
    }

    @Test
    fun `data uri scheme is case insensitive`() {
        val l = AttachmentResolver.loadBytes("DATA:text/plain;base64," + B64.encode("AB".toByteArray()), "x")
        assertEquals("AB", String(l.bytes, Charsets.UTF_8))
    }

    @Test
    fun `base64 marker is case insensitive`() {
        val l = AttachmentResolver.loadBytes("data:text/plain;BASE64," + B64.encode("AB".toByteArray()), "x")
        assertEquals("AB", String(l.bytes, Charsets.UTF_8))
    }

    @Test
    fun `charset parameter does not leak into the mime`() {
        // 关键：mime 会直接用作上传时的 Content-Type 与文件名扩展名。
        // 若把 ";charset=utf-8" 带进去，扩展名会变成 "plain; charset=utf-8"。
        val l = AttachmentResolver.loadBytes(
            "data:text/plain;charset=utf-8;base64," + B64.encode("x".toByteArray()), "x",
        )
        assertEquals("text/plain", l.mime)
    }

    @Test
    fun `missing comma is rejected`() {
        val msg = badRequest { AttachmentResolver.loadBytes("data:image/png;base64", "image_url") }
        assertTrue("应指出 data URI 格式问题: $msg", msg.contains("data URI"))
    }

    @Test
    fun `invalid base64 payload is rejected with a clear message`() {
        val msg = badRequest { AttachmentResolver.loadBytes("data:image/png;base64,@@@not-base64@@@", "image_url") }
        assertTrue("应指出 base64 解码失败: $msg", msg.contains("base64"))
    }

    @Test
    fun `empty mime falls back to octet stream`() {
        val l = AttachmentResolver.loadBytes("data:;base64," + B64.encode(byteArrayOf(1, 2)), "x")
        assertEquals("application/octet-stream", l.mime)
        assertArrayEquals(byteArrayOf(1, 2), l.bytes)
    }

    @Test
    fun `empty base64 payload yields empty bytes rather than throwing`() {
        // 空内容的上传由 QwenClient.uploadFile 统一拦（"附件内容为空"），
        // 这里不应先抛 base64 异常 —— 否则用户看到的是错误的报错原因。
        val l = AttachmentResolver.loadBytes("data:image/png;base64,", "image_url")
        assertEquals(0, l.bytes.size)
    }

    @Test
    fun `binary payload survives base64 round trip byte for byte`() {
        val raw = ByteArray(4096) { ((it * 131) % 256).toByte() }
        val l = AttachmentResolver.loadBytes(dataUri("application/octet-stream", raw), "x")
        assertArrayEquals(raw, l.bytes)
    }

    // ================= 2. 非 data URI 的入口校验 =================

    @Test
    fun `null and blank source is rejected`() {
        badRequest { AttachmentResolver.loadBytes(null, "image_url") }
        badRequest { AttachmentResolver.loadBytes("", "image_url") }
    }

    @Test
    fun `local file path is rejected`() {
        val msg = badRequest { AttachmentResolver.loadBytes("/etc/passwd", "image_url") }
        assertTrue("应说明只支持 data URI 或 http(s): $msg", msg.contains("data URI"))
    }

    @Test
    fun `plaintext http is rejected even for allowed domain`() {
        val msg = badRequest { AttachmentResolver.loadBytes("http://cdn.qwen.ai/a.png", "image_url") }
        assertTrue("应要求 https: $msg", msg.contains("https"))
    }

    @Test
    fun `non whitelisted host is rejected before any network io`() {
        val msg = badRequest { AttachmentResolver.loadBytes("https://example.com/a.png", "image_url") }
        assertTrue("应说明白名单限制: $msg", msg.contains("安全") || msg.contains("域名"))
    }

    @Test
    fun `loopback https url is rejected`() {
        badRequest { AttachmentResolver.loadBytes("https://127.0.0.1/a.png", "image_url") }
    }

    // ================= 3. MIME -> 扩展名推导 =================

    @Test
    fun `known mime types map to conventional extensions`() {
        assertEquals("png", AttachmentResolver.extFromMime("image/png"))
        assertEquals("jpg", AttachmentResolver.extFromMime("image/jpeg"))
        assertEquals("svg", AttachmentResolver.extFromMime("image/svg+xml"))
        assertEquals("mp4", AttachmentResolver.extFromMime("video/mp4"))
        assertEquals("txt", AttachmentResolver.extFromMime("text/plain"))
        assertEquals("md", AttachmentResolver.extFromMime("text/markdown"))
        assertEquals("pdf", AttachmentResolver.extFromMime("application/pdf"))
        assertEquals("json", AttachmentResolver.extFromMime("application/json"))
        assertEquals("bin", AttachmentResolver.extFromMime("application/octet-stream"))
    }

    @Test
    fun `mime parameters are stripped before mapping`() {
        assertEquals("txt", AttachmentResolver.extFromMime("text/plain; charset=utf-8"))
        assertEquals("png", AttachmentResolver.extFromMime("image/png;charset=UTF-8"))
    }

    @Test
    fun `unknown subtype is sanitized into a safe extension`() {
        // 扩展名会进入上传文件名与 Qwen 的解析判定，必须是可预期的字符集
        val ext = AttachmentResolver.extFromMime("application/x-my+weird subtype")
        assertTrue("不应含空格/加号等字符: $ext", Regex("^[a-z0-9]+$").matches(ext))
    }

    @Test
    fun `absurd subtype falls back to bin`() {
        assertEquals("bin", AttachmentResolver.extFromMime("application/${"x".repeat(80)}"))
    }

    @Test
    fun `mime without slash falls back to bin`() {
        assertEquals("bin", AttachmentResolver.extFromMime("garbage"))
        assertEquals("bin", AttachmentResolver.extFromMime(""))
    }

    @Test
    fun `mime mapping is case insensitive`() {
        assertEquals("png", AttachmentResolver.extFromMime("IMAGE/PNG"))
    }

    // ================= 4. 有界读取（防内存放大） =================

    @Test
    fun `bounded read accepts a body exactly at the limit`() {
        val data = ByteArray(1024) { 7 }
        val out = AttachmentResolver.readBounded(ByteArrayInputStream(data), 1024L, "image_url")
        assertArrayEquals(data, out)
    }

    @Test
    fun `bounded read aborts as soon as the limit is exceeded`() {
        // 旧实现在 `resp.body?.bytes()` 之后才比大小：上限永远来得太晚，
        // 一个白名单域名只要返回超大响应就能把内存吃掉（静默内存放大）。
        val msg = badRequest {
            AttachmentResolver.readBounded(ByteArrayInputStream(ByteArray(4097) { 1 }), 4096L, "image_url")
        }
        assertTrue("应给出可读的上限提示: $msg", msg.contains("上限"))
        assertTrue("提示里应带单位与上下文: $msg", msg.contains("image_url"))
    }

    @Test
    fun `bounded read preserves content byte for byte across multiple chunks`() {
        val data = ByteArray(300_000) { ((it * 7) % 256).toByte() }
        val out = AttachmentResolver.readBounded(ByteArrayInputStream(data), 400_000L, "x")
        assertArrayEquals(data, out)
    }

    @Test
    fun `bounded read of an empty stream yields empty bytes`() {
        assertEquals(0, AttachmentResolver.readBounded(ByteArrayInputStream(ByteArray(0)), 10L, "x").size)
    }

    // ================= 5. resolveSuspend：content 扁平化 =================

    @Test
    fun `plain string content is untouched and no upload happens`() {
        val msgs = arrayListOf(ChatMessage("user", "你好"))
        val files = resolve(msgs, store())
        assertEquals(0, files.size)
        assertEquals(0, uploads.size)
        assertEquals("你好", msgs[0].content)
    }

    @Test
    fun `text parts are joined with newline`() {
        val msgs = one(textPart("第一行"), textPart("第二行"))
        resolve(msgs, store())
        assertEquals("第一行\n第二行", msgs[0].content)
    }

    @Test
    fun `image part is uploaded and content keeps only the text`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val msgs = one(textPart("这张图是什么"), imagePart(dataUri("image/png", png)))
        val files = resolve(msgs, store())

        assertEquals("扁平化后只保留文本", "这张图是什么", msgs[0].content)
        assertEquals(1, uploads.size)
        assertEquals(AttachmentKind.IMAGE, uploads[0].kind)
        assertEquals("image/png", uploads[0].contentType)
        assertArrayEquals(png, uploads[0].bytes)
        assertEquals("上传文件名必须带正确的扩展名", "media.png", uploads[0].filename)
        assertEquals(1, files.size)
    }

    @Test
    fun `image url holder may be a bare string`() {
        val png = byteArrayOf(1, 2, 3)
        val msgs = one(mapOf("type" to "image_url", "image_url" to dataUri("image/png", png)))
        resolve(msgs, store())
        assertEquals(1, uploads.size)
        assertArrayEquals(png, uploads[0].bytes)
    }

    @Test
    fun `video url is uploaded as video`() {
        val msgs = one(videoPart(dataUri("video/mp4", byteArrayOf(0, 0, 0, 1))))
        resolve(msgs, store())
        assertEquals(AttachmentKind.VIDEO, uploads[0].kind)
        assertEquals("media.mp4", uploads[0].filename)
    }

    @Test
    fun `image part without url is rejected before any upload`() {
        val msgs = one(textPart("hi"), mapOf("type" to "image_url", "image_url" to JSONObject()))
        val msg = badRequest { resolve(msgs, store()) }
        assertTrue("应指明缺失字段: $msg", msg.contains("url"))
        assertEquals(0, uploads.size)
    }

    @Test
    fun `part type with wrong holder shape is rejected`() {
        val msgs = one(mapOf("type" to "image_url", "image_url" to 42))
        badRequest { resolve(msgs, store()) }
        assertEquals(0, uploads.size)
    }

    @Test
    fun `unknown part types are ignored and text survives`() {
        // 向后兼容：新版本客户端可能发来网关还不认识的 part 类型。
        // 但**不能**因此丢掉同一条消息里的文本。
        val msgs = one(textPart("保留我"), mapOf("type" to "brand_new_type", "x" to 1))
        val files = resolve(msgs, store())
        assertEquals("保留我", msgs[0].content)
        assertEquals(0, files.size)
        assertEquals(0, uploads.size)
    }

    @Test
    fun `non list content is stringified for backward compatibility`() {
        // /v1/chat/completions 里 content 允许是对象/数字，网关统一转字符串
        val msgs = arrayListOf(ChatMessage("user", JSONObject().put("unexpected", 1)))
        resolve(msgs, store())
        assertTrue("应转成字符串: ${msgs[0].content}", msgs[0].content is String)
        assertTrue((msgs[0].content as String).contains("unexpected"))
    }

    @Test
    fun `null content is left alone`() {
        val msgs = arrayListOf(ChatMessage("user", null))
        resolve(msgs, store())
        assertEquals(null, msgs[0].content)
    }

    @Test
    fun `several attachments in one message all get uploaded in order`() {
        val a = dataUri("image/png", byteArrayOf(1, 1))
        val b = dataUri("image/jpeg", byteArrayOf(2, 2))
        val msgs = one(imagePart(a), textPart("中间"), imagePart(b))
        val files = resolve(msgs, store())
        assertEquals(2, files.size)
        assertEquals(listOf("media.png", "media.jpg"), uploads.map { it.filename })
        assertEquals("中间", msgs[0].content)
    }

    // ================= 6. input_audio =================

    @Test
    fun `audio part is uploaded with format based extension`() {
        val raw = byteArrayOf(9, 8, 7)
        val msgs = one(audioPart(B64.encode(raw), "wav"))
        resolve(msgs, store())
        assertEquals(AttachmentKind.AUDIO, uploads[0].kind)
        assertEquals("audio.wav", uploads[0].filename)
        assertEquals("audio/wav", uploads[0].contentType)
        assertArrayEquals(raw, uploads[0].bytes)
    }

    @Test
    fun `audio format defaults to wav when absent`() {
        val msgs = one(audioPart(B64.encode(byteArrayOf(1)), null))
        resolve(msgs, store())
        assertEquals("audio.wav", uploads[0].filename)
    }

    @Test
    fun `audio format is sanitized before becoming a filename`() {
        // format 直接来自请求体，可能含路径分隔符 —— 必须剥成纯字母数字
        val msgs = one(audioPart(B64.encode(byteArrayOf(1)), "../../etc/passwd"))
        resolve(msgs, store())
        assertTrue("文件名不得含路径分隔符: ${uploads[0].filename}", !uploads[0].filename.contains("/"))
        assertTrue("文件名不得含点: ${uploads[0].filename}", Regex("^audio\\.[A-Za-z0-9]+$").matches(uploads[0].filename))
    }

    @Test
    fun `audio with invalid base64 is rejected`() {
        val msgs = one(audioPart("!!!!", "wav"))
        val msg = badRequest { resolve(msgs, store()) }
        assertTrue("应指出 base64: $msg", msg.contains("base64"))
        assertEquals(0, uploads.size)
    }

    @Test
    fun `audio without data is rejected`() {
        val msgs = one(mapOf("type" to "input_audio", "input_audio" to mapOf("format" to "wav")))
        badRequest { resolve(msgs, store()) }
    }

    @Test
    fun `audio holder must be an object`() {
        val msgs = one(mapOf("type" to "input_audio", "input_audio" to "not-an-object"))
        badRequest { resolve(msgs, store()) }
    }

    // ================= 7. file 分段 =================

    @Test
    fun `file id reuses the registry entry without uploading again`() {
        val files = store()
        val entry = JSONObject().put("id", "file-reg-1").put("type", "file")
        files.add(
            FileRecord(
                id = "file-reg-1", qwenId = "q1", url = "https://cdn.example/doc.pdf",
                filename = "doc.pdf", bytes = 12, mime = "application/pdf", kind = "document",
                createdAt = 1, purpose = "assistants", entry = entry,
            ),
        )
        val msgs = one(fileIdPart("file-reg-1"))
        val out = resolve(msgs, files)
        assertEquals(1, out.size)
        assertEquals("file-reg-1", out[0].optString("id"))
        assertEquals("不得重复上传已有文件", 0, uploads.size)
    }

    @Test
    fun `file id lookup tolerates the file prefix`() {
        val files = store()
        files.add(
            FileRecord(
                id = "file-abc", qwenId = "q", url = "u", filename = "a.txt", bytes = 1,
                mime = "text/plain", kind = "document", createdAt = 1, purpose = "assistants",
                entry = JSONObject().put("id", "file-abc"),
            ),
        )
        // 客户端习惯把 id 当 "abc" 传（去掉 file- 前缀）
        val out = resolve(one(fileIdPart("abc")), files)
        assertEquals(1, out.size)
        assertEquals(0, uploads.size)
    }

    @Test
    fun `unknown file id gives an actionable error`() {
        val msg = badRequest { resolve(one(fileIdPart("file-nope")), store()) }
        assertTrue("应告诉用户先上传: $msg", msg.contains("/v1/files"))
        assertTrue("应带上具体 id: $msg", msg.contains("file-nope"))
    }

    @Test
    fun `file data is uploaded with the client supplied filename`() {
        val raw = "hello doc".toByteArray()
        val msgs = one(fileDataPart(dataUri("text/plain", raw), "note.txt"))
        resolve(msgs, store())
        assertEquals(1, uploads.size)
        assertEquals("note.txt", uploads[0].filename)
        assertEquals("text/plain", uploads[0].contentType)
        assertArrayEquals(raw, uploads[0].bytes)
    }

    @Test
    fun `file data without filename derives a clean extension`() {
        // 旧实现这里用 mime 直接拼扩展名且**没有剥分号**，
        // 于是 `text/plain; charset=utf-8` 生出 `file.plain; charset=utf-8` 这种文件名。
        val msgs = one(
            mapOf(
                "type" to "file",
                "file" to mapOf(
                    "file_data" to "data:text/plain;charset=utf-8;base64," + B64.encode("x".toByteArray()),
                ),
            ),
        )
        resolve(msgs, store())
        assertEquals("file.txt", uploads[0].filename)
    }

    @Test
    fun `file part without id and data is rejected`() {
        val msgs = one(mapOf("type" to "file", "file" to mapOf("filename" to "x.txt")))
        badRequest { resolve(msgs, store()) }
    }

    @Test
    fun `file holder must be an object`() {
        val msgs = one(mapOf("type" to "file", "file" to listOf("a")))
        badRequest { resolve(msgs, store()) }
    }

    @Test
    fun `empty file id falls through to file data`() {
        // file_id 为空串时应继续尝试 file_data，而不是直接报「未找到」
        val raw = "payload".toByteArray()
        val msgs = one(
            mapOf(
                "type" to "file",
                "file" to mapOf("file_id" to "", "file_data" to dataUri("text/plain", raw), "filename" to "p.txt"),
            ),
        )
        resolve(msgs, store())
        assertEquals(1, uploads.size)
        assertArrayEquals(raw, uploads[0].bytes)
    }

    // ================= 8. 错误传播 =================

    @Test
    fun `upload failure propagates as qwen exception`() {
        val failing = object : QwenClient("t", 0) {
            override suspend fun uploadFile(
                bytes: ByteArray, filename: String, contentType: String, kind: AttachmentKind?,
            ): UploadResult = throw QwenException("UPLOAD_FAIL", "OSS 拒绝", 502)

            override suspend fun listModels(): List<QwenModel> = emptyList()

            override suspend fun chatStream(
                model: String, messages: List<ChatMessage>, thinking: Boolean,
                files: List<JSONObject>, onEvent: (suspend (QwenEvent) -> Unit)?,
            ): ChatResult = throw UnsupportedOperationException()
        }
        val msgs = one(imagePart(dataUri("image/png", byteArrayOf(1))))
        try {
            AttachmentResolver.resolve(failing, msgs, store())
            throw AssertionError("上传失败必须冒泡")
        } catch (e: QwenException) {
            assertEquals("UPLOAD_FAIL", e.code)
            assertEquals(502, e.status)
        }
    }

    @Test
    fun `resolve returns entries carrying id and url`() {
        val files = resolve(one(imagePart(dataUri("image/png", byteArrayOf(1)))), store())
        assertEquals(1, files.size)
        assertTrue(files[0].optString("id").isNotEmpty())
        assertNotNull(files[0].opt("type"))
    }

    @Test
    fun `multiple messages are all processed`() {
        val msgs = arrayListOf(
            ChatMessage("user", "第一轮"),
            ChatMessage("assistant", "回答"),
            ChatMessage("user", listOf(textPart("第二轮"), imagePart(dataUri("image/png", byteArrayOf(3))))),
        )
        val files = resolve(msgs, store())
        assertEquals(1, files.size)
        assertEquals("第一轮", msgs[0].content)
        assertEquals("回答", msgs[1].content)
        assertEquals("第二轮", msgs[2].content)
    }

    // ================= 9. humanize（附件类错误提示） =================

    private fun videoEntry(): List<JSONObject> =
        listOf(JSONObject().put("type", "video").put("id", "v1"))

    @Test
    fun `video attachment failure hints at the omni model`() {
        val e = AttachmentResolver.humanize(
            QwenException("invalid_input", "上游拒绝", 400), videoEntry(),
        )
        assertTrue("应给出换模型提示: ${e.message}", e.message.contains("omni"))
        assertTrue("应保留原始报错: ${e.message}", e.message.contains("上游拒绝"))
        assertEquals("错误码不应被改写", "invalid_input", e.code)
        assertEquals(400, e.status)
    }

    @Test
    fun `video hint also triggers on messages mentioning attachments`() {
        val e = AttachmentResolver.humanize(
            QwenException("UPSTREAM_ERROR", "attachment not supported", 502), videoEntry(),
        )
        assertTrue("应给出换模型提示: ${e.message}", e.message.contains("omni"))
    }

    @Test
    fun `image only failure gets no video hint`() {
        val files = listOf(JSONObject().put("type", "image"))
        val e = AttachmentResolver.humanize(QwenException("invalid_input", "上游拒绝", 400), files)
        assertTrue("图片附件不该提示换 omni: ${e.message}", !e.message.contains("omni"))
    }

    @Test
    fun `failure without any attachment gets no hint`() {
        val e = AttachmentResolver.humanize(QwenException("invalid_input", "上游拒绝", 400), emptyList())
        assertTrue(!e.message.contains("omni"))
    }

    @Test
    fun `unrelated failure codes are not rewritten`() {
        val e = AttachmentResolver.humanize(
            QwenException("AUTH_FAILED", "Token 无效", 401), videoEntry(),
        )
        assertEquals("AUTH_FAILED", e.code)
        assertTrue("非附件类错误不得附加提示: ${e.message}", !e.message.contains("omni"))
    }

    @Test
    fun `non qwen exception is normalized to 502`() {
        val e = AttachmentResolver.humanize(RuntimeException("socket closed"), videoEntry())
        assertEquals("UPSTREAM_ERROR", e.code)
        assertEquals(502, e.status)
        assertTrue(e.message.contains("socket closed"))
    }

    @Test
    fun `non qwen exception with blank message still produces readable text`() {
        val e = AttachmentResolver.humanize(RuntimeException(), emptyList())
        assertTrue("消息不能为空: ", e.message.isNotEmpty())
    }

    // ================= 10. 内存文件注册表 =================

    @Test
    fun `memory store normalizes ids and reports missing`() {
        val s = store()
        assertEquals(null, s.find("file-x"))
        s.add(
            FileRecord(
                id = "file-x", qwenId = "q", url = "u", filename = "f", bytes = 0,
                mime = "text/plain", kind = "document", createdAt = 0, purpose = "assistants",
                entry = JSONObject(),
            ),
        )
        assertNotNull(s.find("file-x"))
        assertNotNull("去掉前缀也应命中", s.find("x"))
        assertEquals(1, s.all().size)
        assertNotNull(s.remove("x"))
        assertEquals(0, s.all().size)
        assertEquals(null, s.remove("x"))
    }
}
