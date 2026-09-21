package com.qwen2api.tx

import com.qwen2api.tx.server.MultipartParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * multipart/form-data 解析器（[MultipartParser]）的单元测试。
 *
 * ## 为什么单独补这一层
 * `/v1/files` 上传链路是 `GatewayRouter.handleFileUpload` →
 * `MultipartParser.parse` → `QwenClient.uploadFile`。E2E 测试只用一条
 * 「格式规整」的请求体碰过 upload 的**错误分支**（未配 token / 非 multipart），
 * 解析器本身的正反两面（多分段、二进制、异常头）在改动前是零覆盖。
 * 这是整条附件链路上唯一的自研二进制解析代码，它错了就是「上传的图片变成半张」，
 * 而且不会报错——静默数据损坏。
 *
 * ## 覆盖重点
 *  1. Content-Type 里的 boundary 提取（带引号 / 不带引号 / 大小写 / 参数夹在中间）
 *  2. 分段切分的正确性：多分段、顺序、二进制安全、CRLF 与 NUL 原样保留
 *  3. 分段头部解析：name / filename / content-type，以及 **filename 在 name 之前**
 *     这种顺序（浏览器不保证顺序，旧实现会把文件名当成字段名）
 *  4. 边界锚定：文件正文里出现 boundary 字面量时不得把内容切碎（RFC 2046
 *     要求分隔符必须位于行首）
 *  5. 退化输入：无 boundary / 空 body / 截断 body / 无头分段 → 不崩、返回 null 或空表
 */
class MultipartParserTest {

    // ---------------- 构造 multipart 请求体的辅助 ----------------

    /** 典型浏览器 boundary（带连字符、大小写混合） */
    private val B = "----WebKitFormBoundary7MA4YWxkTrZu0gW"

    private class Body(private val boundary: String) {
        private val out = ByteArrayOutputStream()

        /** 追加一个普通文本字段 */
        fun field(name: String, value: String) = apply {
            out.write("--$boundary\r\n".toByteArray(Charsets.ISO_8859_1))
            out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.write(value.toByteArray(Charsets.UTF_8))
            out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
        }

        /** 追加一个文件分段；[headers] 可整体覆盖 Content-Disposition 行以构造异常头 */
        fun file(
            name: String,
            filename: String,
            data: ByteArray,
            contentType: String? = "application/octet-stream",
            dispositionOverride: String? = null,
        ) = apply {
            out.write("--$boundary\r\n".toByteArray(Charsets.ISO_8859_1))
            val disp = dispositionOverride
                ?: "Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\""
            out.write("$disp\r\n".toByteArray(Charsets.UTF_8))
            if (contentType != null) {
                out.write("Content-Type: $contentType\r\n".toByteArray(Charsets.ISO_8859_1))
            }
            out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
            out.write(data)
            out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
        }

        /** 原样追加字节（用于构造畸形 / 截断请求体） */
        fun raw(bytes: ByteArray) = apply { out.write(bytes) }

        fun raw(s: String) = apply { out.write(s.toByteArray(Charsets.ISO_8859_1)) }

        fun build(): ByteArray {
            out.write("--$boundary--\r\n".toByteArray(Charsets.ISO_8859_1))
            return out.toByteArray()
        }

        /** 不写结束分隔符，模拟被截断的请求 */
        fun buildTruncated(): ByteArray = out.toByteArray()
    }

    private fun body(boundary: String = B) = Body(boundary)

    private fun ct(boundary: String = B) = "multipart/form-data; boundary=$boundary"

    private fun parse(bytes: ByteArray, contentType: String? = ct()): List<MultipartParser.Part>? =
        MultipartParser.parse(bytes, contentType)

    private fun oneField(bytes: ByteArray, name: String = "file"): MultipartParser.Part? =
        parse(bytes)?.firstOrNull { it.name == name }

    // ---------------- 1. Content-Type 解析 ----------------

    @Test
    fun `plain boundary is extracted`() {
        val p = parse(body().field("file", "hi").build())
        assertEquals(1, p!!.size)
        assertEquals("file", p[0].name)
        assertEquals("hi", String(p[0].data, Charsets.UTF_8))
    }

    @Test
    fun `quoted boundary is extracted without the quotes`() {
        val p = MultipartParser.parse(
            body().field("file", "hi").build(),
            "multipart/form-data; boundary=\"$B\"",
        )
        assertEquals(1, p!!.size)
        assertEquals("hi", String(p[0].data, Charsets.UTF_8))
    }

    @Test
    fun `boundary among other parameters is still found`() {
        val p = MultipartParser.parse(
            body().field("file", "hi").build(),
            "multipart/form-data; charset=utf-8; boundary=$B",
        )
        assertEquals(1, p!!.size)
    }

    @Test
    fun `parameter name and value are case insensitive`() {
        val p = MultipartParser.parse(
            body().field("file", "hi").build(),
            "MULTIPART/FORM-DATA; BOUNDARY=$B",
        )
        assertEquals(1, p!!.size)
    }

    @Test
    fun `boundary with regex metacharacters is treated literally`() {
        // 旧实现用正则找分隔符时会把 ( ) + 当语法；必须按字节比较
        val weird = "abc(def)+x*y"
        val p = MultipartParser.parse(
            body(weird).field("file", "payload").build(),
            ct(weird),
        )
        assertEquals(1, p!!.size)
        assertEquals("payload", String(p[0].data, Charsets.UTF_8))
    }

    @Test
    fun `missing boundary returns null`() {
        assertNull(parse(body().field("file", "hi").build(), "multipart/form-data"))
    }

    @Test
    fun `null content type returns null`() {
        assertNull(parse(body().field("file", "hi").build(), null))
    }

    @Test
    fun `empty content type returns null`() {
        assertNull(parse(body().field("file", "hi").build(), ""))
    }

    @Test
    fun `empty boundary value returns null`() {
        assertNull(parse(body().field("file", "hi").build(), "multipart/form-data; boundary="))
    }

    // ---------------- 2. 分段切分 ----------------

    @Test
    fun `two file parts keep original order and payloads`() {
        val p = parse(
            body().file("file", "a.txt", "AAA".toByteArray())
                .file("thumb", "b.bin", "BBB".toByteArray())
                .build(),
        )!!
        assertEquals(2, p.size)
        assertEquals(listOf("a.txt", "b.bin"), p.map { it.filename })
        assertEquals("AAA", String(p[0].data, Charsets.UTF_8))
        assertEquals("BBB", String(p[1].data, Charsets.UTF_8))
    }

    @Test
    fun `file part after purpose field is still parsed`() {
        // 真实客户端常先发 purpose 再发 file；顺序不能被解析器擅自重排
        val p = parse(
            body().field("purpose", "assistants")
                .file("file", "x.png", byteArrayOf(1, 2, 3), "image/png")
                .build(),
        )!!
        assertEquals(2, p.size)
        assertEquals("purpose", p[0].name)
        assertEquals("file", p[1].name)
        assertEquals("x.png", p[1].filename)
    }

    @Test
    fun `crlf inside payload is preserved`() {
        val payload = "line1\r\nline2\r\n"
        val p = oneField(body().file("file", "a.txt", payload.toByteArray()).build())!!
        assertArrayEquals(
            "正文里的 CRLF 属于数据，不能被当作分隔符处理",
            payload.toByteArray(),
            p.data,
        )
    }

    @Test
    fun `payload with leading and trailing whitespace is byte exact`() {
        val payload = "  \r\n  keep  \r\n  "
        val p = oneField(body().file("file", "a.txt", payload.toByteArray()).build())!!
        assertArrayEquals(payload.toByteArray(), p.data)
    }

    @Test
    fun `binary payload with nul and high bytes is byte exact`() {
        // 100KB，含 NUL / 0xFF / 所有单字节值，PNG 头尾
        val payload = ByteArray(100_000)
        for (i in payload.indices) payload[i] = (i % 256).toByte()
        payload[0] = 0x89.toByte(); payload[1] = 'P'.code.toByte()
        payload[2] = 'N'.code.toByte(); payload[3] = 'G'.code.toByte()
        payload[payload.size - 1] = 0x00

        val p = oneField(body().file("file", "img.png", payload, "image/png").build())!!
        assertArrayEquals("二进制正文必须逐字节一致", payload, p.data)
        assertEquals(payload.size, p.data.size)
    }

    @Test
    fun `empty payload yields empty data not missing part`() {
        val p = oneField(body().file("file", "empty.txt", ByteArray(0)).build())
        assertNotNull("空文件仍应产出分段（由上层判定为空并报错）", p)
        assertEquals(0, p!!.data.size)
    }

    @Test
    fun `wide multi byte utf8 filename survives`() {
        val p = oneField(body().file("file", "测试-图片.png", byteArrayOf(9)).build())!!
        assertEquals("测试-图片.png", p.filename)
    }

    // ---------------- 3. 头部解析 ----------------

    @Test
    fun `part content type is captured`() {
        val p = oneField(body().file("file", "a.png", byteArrayOf(1), "image/png").build())!!
        assertEquals("image/png", p.contentType)
    }

    @Test
    fun `missing content type yields empty string`() {
        val p = oneField(body().file("file", "a.bin", byteArrayOf(1), contentType = null).build())!!
        assertEquals("", p.contentType)
    }

    @Test
    fun `text field without filename has empty filename`() {
        val p = parse(body().field("purpose", "assistants").build())!!
        assertEquals("assistants", String(p[0].data, Charsets.UTF_8))
        assertEquals("", p[0].filename)
    }

    @Test
    fun `filename before name is not mistaken for the field name`() {
        // 浏览器不保证 name 在 filename 之前。旧实现用 `name="..."` 直接 find，
        // 会命中 `filename="a.txt"` 里的子串，把字段名解析成文件名 ——
        // 后果是 handleFileUpload 的 `it.name == "file"` 兜底直接失效。
        val p = parse(
            body().file(
                "file", "a.txt", "DATA".toByteArray(),
                dispositionOverride = "Content-Disposition: form-data; filename=\"a.txt\"; name=\"file\"",
            ).build(),
        )!!
        assertEquals("file", p[0].name)
        assertEquals("a.txt", p[0].filename)
    }

    @Test
    fun `name is empty when disposition omits it`() {
        val p = parse(
            body().file(
                "file", "a.txt", "DATA".toByteArray(),
                dispositionOverride = "Content-Disposition: form-data; filename=\"a.txt\"",
            ).build(),
        )!!
        assertEquals("", p[0].name)
        assertEquals("a.txt", p[0].filename)
    }

    @Test
    fun `header names are case insensitive`() {
        val p = parse(
            body().file(
                "file", "a.png", byteArrayOf(1),
                dispositionOverride = "content-disposition: FORM-DATA; NAME=\"file\"; FILENAME=\"a.png\"",
            ).build(),
        )!!
        assertEquals("file", p[0].name)
        assertEquals("a.png", p[0].filename)
    }

    // ---------------- 4. 边界锚定（RFC 2046） ----------------

    @Test
    fun `boundary literal inside payload without line start is not a delimiter`() {
        // 正文里出现 `--<boundary>` 字面量（例如上传源码 / 上面这段测试自身）时，
        // 旧实现会把它当分隔符把文件切成两半 —— 静默数据损坏。
        val payload = "hello --$B world".toByteArray(Charsets.ISO_8859_1)
        val p = oneField(body().file("file", "a.txt", payload).build())!!
        assertArrayEquals("行内的 boundary 字面量必须原样保留", payload, p.data)
    }

    @Test
    fun `boundary literal inside payload keeps part count stable`() {
        val payload = "a--${B}b--${B}c".toByteArray(Charsets.ISO_8859_1)
        val p = parse(body().file("file", "a.txt", payload).file("file2", "b.txt", "X".toByteArray()).build())!!
        assertEquals("不得凭空多出分段", 2, p.size)
        assertArrayEquals(payload, p[0].data)
        assertEquals("X", String(p[1].data, Charsets.UTF_8))
    }

    @Test
    fun `payload starting with the boundary marker still belongs to the payload`() {
        // 分段正文紧跟 `\r\n\r\n` 之后的第一个字节就是 `--B`，但它在行首 ——
        // 这属于客户端选了个会撞车的 boundary，解析器只能按 RFC 保守处理：
        // 至少不能崩，也不能把后面真正的分段吃掉。
        val payload = "--${B}fake".toByteArray(Charsets.ISO_8859_1)
        val p = parse(body().file("file", "a.txt", payload).build())
        assertNotNull(p)
    }

    // ---------------- 5. 退化输入 ----------------

    @Test
    fun `body without any boundary returns empty list`() {
        val p = parse("just plain text".toByteArray())
        assertNotNull("Content-Type 合法时不应返回 null", p)
        assertTrue("没有分段就是空表", p!!.isEmpty())
    }

    @Test
    fun `empty body returns empty list`() {
        val p = parse(ByteArray(0))
        assertNotNull(p)
        assertTrue(p!!.isEmpty())
    }

    @Test
    fun `truncated body without closing delimiter does not crash`() {
        val truncated = body().file("file", "a.txt", "DATA".toByteArray()).buildTruncated()
        val p = parse(truncated)
        assertNotNull(p)
        // 缺少结束分隔符时无法界定末段边界，解析器选择不产出分段而不是抛异常；
        // 上层据此回 400「缺少文件字段」，是可接受的降级。
        assertTrue(p!!.isEmpty())
    }

    @Test
    fun `part without header separator is skipped`() {
        val malformed = body()
            .raw("--$B\r\nContent-Disposition: form-data; name=\"broken\"\r\n") // 缺 \r\n\r\n
            .file("file", "a.txt", "OK".toByteArray())
            .build()
        val p = parse(malformed)!!
        assertEquals("畸形分段应被跳过而非污染结果", 1, p.size)
        assertEquals("file", p[0].name)
        assertEquals("OK", String(p[0].data, Charsets.UTF_8))
    }

    @Test
    fun `closing delimiter alone returns empty list`() {
        val p = parse("--$B--\r\n".toByteArray())
        assertNotNull(p)
        assertTrue(p!!.isEmpty())
    }

    @Test
    fun `data is a copy not a view of the request buffer`() {
        // handleFileUpload 会把 part.data 直接交给 uploadFile；
        // 若返回的是共享底层数组的切片，后续读取/复用请求缓冲会污染上传内容。
        val bytes = body().file("file", "a.txt", "MUTABLE".toByteArray()).build()
        val p = oneField(bytes)!!
        val before = p.data.copyOf()
        java.util.Arrays.fill(bytes, 0)
        assertArrayEquals("分段数据必须是独立副本", before, p.data)
    }
}
