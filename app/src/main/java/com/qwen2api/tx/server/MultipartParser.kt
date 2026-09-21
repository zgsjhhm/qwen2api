package com.qwen2api.tx.server

/**
 * 零依赖 multipart/form-data 解析器（移植自 lib/util.js 的 parseMultipart）。
 * 返回各分段；请求体里找不到合法的 boundary 时返回 null。
 *
 * ## 两条必须守住的规则
 *  1. **分隔符必须位于行首**（RFC 2046：delimiter 前必须是 CRLF，或整个体的开头）。
 *     不锚定的话，文件正文里只要出现 `--<boundary>` 字面量（上传源码、
 *     上传本文件的测试用例、任何二进制巧合）就会被当作分段边界，把文件切成两半 ——
 *     而且不报错，属于静默数据损坏。
 *  2. **字段属性要按词边界匹配**。`name="..."` 不能命中 `filename="a.txt"` 里的子串，
 *     否则字段名会被解析成文件名，`handleFileUpload` 的 `it.name == "file"` 兜底失效。
 *
 * ## 截断处理
 * 末段分隔符必须是带 `--` 后缀的结束分隔符。找不到就说明请求被截断，
 * 此时返回空表让上层回 400，而不是把一个可能不完整的文件传上去。
 */
object MultipartParser {

    data class Part(
        val name: String,
        val filename: String,
        val contentType: String,
        val data: ByteArray,
    )

    private const val CR = 13.toByte()
    private const val LF = 10.toByte()
    private const val DASH = 45.toByte()

    private val HEADER_END = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

    fun parse(buf: ByteArray, contentType: String?): List<Part>? {
        val m = Regex("boundary=(?:\"([^\"]+)\"|([^;]+))", RegexOption.IGNORE_CASE)
            .find(contentType.orEmpty()) ?: return null
        val boundaryStr = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
        if (boundaryStr.isEmpty()) return null
        // 分隔符按字节比较：boundary 里可能含正则元字符，绝不能用正则找
        val delim = ("--$boundaryStr").toByteArray(Charsets.ISO_8859_1)

        // 收集所有「位于行首」的分隔符位置
        val marks = ArrayList<Int>()
        var pos = indexOfDelimiter(buf, delim, 0)
        while (pos >= 0) {
            marks.add(pos)
            pos = indexOfDelimiter(buf, delim, pos + delim.size)
        }
        // 至少要有「起始分隔符 + 结束分隔符」；否则视为没有有效分段
        if (marks.size < 2) return emptyList()

        // 末段必须是结束分隔符（--boundary--），否则判定请求被截断
        val last = marks.last()
        val closed = last + delim.size + 1 < buf.size &&
            buf[last + delim.size] == DASH && buf[last + delim.size + 1] == DASH
        if (!closed) return emptyList()

        val out = ArrayList<Part>()
        for (i in 0 until marks.size - 1) {
            val contentStart = marks[i] + delim.size
            val next = marks[i + 1]
            // 起始分隔符后必须紧跟 CRLF（若紧跟 -- 说明它是结束分隔符，跳过）
            if (contentStart + 1 >= buf.size ||
                buf[contentStart] != CR || buf[contentStart + 1] != LF
            ) {
                continue
            }
            val headStart = contentStart + 2
            var bodyEnd = next
            // 段尾的 CRLF 属于分隔符，不是数据
            if (bodyEnd - headStart >= 2 &&
                buf[bodyEnd - 2] == CR && buf[bodyEnd - 1] == LF
            ) {
                bodyEnd -= 2
            }
            if (bodyEnd < headStart) continue
            val headerEnd = indexOfBytes(buf, HEADER_END, headStart)
            if (headerEnd < 0 || headerEnd >= bodyEnd) continue
            val head = String(buf, headStart, headerEnd - headStart, Charsets.UTF_8)
            val bodyStart = headerEnd + 4
            if (bodyStart > bodyEnd) continue
            out.add(
                Part(
                    name = attribute(head, "name"),
                    filename = attribute(head, "filename"),
                    contentType = Regex("content-type:\\s*([^\\r\\n]+)", RegexOption.IGNORE_CASE)
                        .find(head)?.groupValues?.get(1)?.trim().orEmpty(),
                    // 必须是独立副本：不能返回共享请求缓冲的视图
                    data = buf.copyOfRange(bodyStart, bodyEnd),
                ),
            )
        }
        return out
    }

    /**
     * 提取 `key="value"` 形式的头部属性。
     *
     * 左侧的负向断言排除「被更长单词包住」的情况，避免 `name` 命中 `filename` 的子串。
     */
    private fun attribute(head: String, key: String): String =
        Regex("(?<![\\w-])${Regex.escape(key)}=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.get(1).orEmpty()

    /** 查找位于行首的分隔符（前两个字节是 CRLF，或整个体的开头） */
    private fun indexOfDelimiter(buf: ByteArray, delim: ByteArray, from: Int): Int {
        var i = from
        while (i >= 0) {
            val hit = indexOfBytes(buf, delim, i)
            if (hit < 0) return -1
            val atLineStart = hit == 0 ||
                (hit >= 2 && buf[hit - 2] == CR && buf[hit - 1] == LF)
            if (atLineStart) return hit
            i = hit + 1
        }
        return -1
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        val start = if (from < 0) 0 else from
        if (start > haystack.size - needle.size) return -1
        outer@ for (i in start..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
