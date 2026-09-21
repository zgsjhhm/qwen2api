package com.qwen2api.tx.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/**
 * System Prompt 文本导入：把「用户手里的一个文件」变成「能直接放进输入框的提示词」。
 *
 * 为什么需要一层独立解析，而不是 `File.readText().trim()`：
 *
 *  1. **编码**。提示词文件的现实来源是记事本、Word、网页复制、Git 仓库，
 *     Windows 记事本至今仍会写出带 BOM 的 UTF-8，中文环境下还有大量 GBK 文件。
 *     这些文件按 UTF-8 硬解会得到「锟斤拷」乱码 —— 而且**不报错**，
 *     用户保存后发现模型行为诡异，很难联想到是编码问题。所以先嗅探再解码。
 *  2. **形态**。导出的提示词经常是三种壳：纯 .txt、带 ``` 围栏的 .md、
 *     以及从各种客户端导出的 .json（字段名五花八门）。让用户自己剥壳不现实，
 *     这里按可判定的规则剥掉；判不出来就**原样返回**，不猜。
 *  3. **上限**。这个字段会被注进每一条请求，超长会顶满上下文（见
 *     [GatewayConfig.MAX_SYSTEM_PROMPT_CHARS]）。导入时就必须截断并**告知**，
 *     静默砍掉后半段是最难排查的一类"我明明写了但模型没照做"。
 *
 * 本对象不依赖 Android 框架类型，可直接在 JVM 单测里跑（见 SystemPromptImportTest）。
 */
object SystemPromptImport {

    /** 单个文件读取上限。提示词是文本，超过这个量级一定是选错了文件（比如整个模型日志）。 */
    const val MAX_BYTES = 512 * 1024

    /** 读取超限时抛出（携带上限，便于 UI 拼出明确文案） */
    class TooLargeException(val limitBytes: Int) :
        Exception("文件超过 ${limitBytes / 1024} KB 上限")

    /**
     * 导入结果。
     *
     * @param text     清洗、截断后可直接使用的提示词
     * @param encoding 嗅探出的编码名（回显给用户，编码问题可自证）
     * @param shape    识别到的形态（"纯文本" / "JSON 字段 systemPrompt" …）
     * @param truncated 是否因为超过字符上限被截断
     */
    data class Imported(
        val text: String,
        val encoding: String,
        val shape: String,
        val truncated: Boolean,
    )

    /**
     * 导入失败的原因。区分失败类型是为了给出可操作的提示：
     * 「空文件」和「JSON 里没有提示词字段」需要用户做完全不同的事。
     */
    sealed class Outcome {
        data class Ok(val value: Imported) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    /** 从字节导入：解码 → 剥壳 → 清洗截断。任何一步失败都返回 [Outcome.Failed]。 */
    fun fromBytes(bytes: ByteArray): Outcome {
        if (bytes.isEmpty()) return Outcome.Failed("文件是空的")
        val decoded = runCatching { decode(bytes) }.getOrElse {
            return Outcome.Failed("无法解码文件内容（编码既不是 UTF-8 也不是 GBK）")
        }
        return fromText(decoded.first, decoded.second)
    }

    /**
     * 从已是字符串的文本导入（剪贴板路径）。
     *
     * 剪贴板拿到的已经是 String，编码信息在系统层就丢了，因此这里固定标 UTF-8，
     * 只走「剥壳 + 清洗截断」。
     */
    fun fromText(raw: String, encoding: String = "UTF-8"): Outcome {
        val body = stripInvisibles(raw)
        if (body.isBlank()) return Outcome.Failed("内容为空（只有空白字符）")

        val extracted = when (val r = extract(body)) {
            is ShapeResult.Got -> r.value
            is ShapeResult.NoPromptField -> return Outcome.Failed(r.hint)
        }
        val cleaned = ConfigStore.sanitizeSystemPrompt(extracted.text)
        if (cleaned.isEmpty()) return Outcome.Failed("内容为空（清洗后没有有效字符）")

        return Outcome.Ok(
            Imported(
                text = cleaned,
                encoding = encoding,
                shape = extracted.shape,
                truncated = cleaned.length < extracted.text.trim().length,
            ),
        )
    }

    /** 读取流并限长。超过 [MAX_BYTES] 立刻中断，不把内存读爆。 */
    @Throws(TooLargeException::class)
    fun readLimited(input: InputStream, maxBytes: Int = MAX_BYTES): ByteArray {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (bos.size() + n > maxBytes) throw TooLargeException(maxBytes)
            bos.write(buf, 0, n)
        }
        return bos.toByteArray()
    }

    // ==================== 编码嗅探 ====================

    /**
     * 字节 → 文本 + 编码名。
     *
     * 判定顺序，先强证据后弱证据：
     *  1. **BOM** —— 有 BOM 就是确证（UTF-8 / UTF-16LE / UTF-16BE），直接采信；
     *  2. **NUL 单侧分布** —— 无 BOM 的 UTF-16 若以 ASCII 为主，每个字符会写成
     *     「一个字节 + 一个 0」，NUL 集中在同一奇偶位，这是可判定的；
     *  3. **干净 UTF-8** —— 严格解通且无控制字符，绝大多数情况在此返回；
     *  4. **无 BOM 的 CJK UTF-16** —— 靠控制字节的奇偶分布判定，见 [utf16Evidence]；
     *  5. **GBK 与「轻度损坏的 UTF-8」二选一** —— 用坏字节比例区分，见下方注释；
     *  6. **兜底** —— 保留 UTF-8 替换解码的结果，并如实标注有非法字节。
     *
     * 之所以每一步都要有独立的**字节层**证据，是因为中文场景下 GBK 与 UTF-16
     * 的字节模式高度重叠；靠"解出来像不像中文"来判断会把两者互认，
     * 而误判的代价是整份提示词变乱码 —— 比不识别更糟。
     */
    fun decode(bytes: ByteArray): Pair<String, String> {
        if (bytes.isEmpty()) return "" to "UTF-8"

        // 1. BOM
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return strictDecode(bytes, 3, StandardCharsets.UTF_8) to "UTF-8 (BOM)"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return strictDecode(bytes, 2, StandardCharsets.UTF_16LE) to "UTF-16LE (BOM)"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return strictDecode(bytes, 2, StandardCharsets.UTF_16BE) to "UTF-16BE (BOM)"
        }

        // 2. NUL 单侧分布：ASCII 为主的 UTF-16
        val sample = minOf(bytes.size, 4096)
        if (sample >= 8) {
            var evenNul = 0
            var oddNul = 0
            for (i in 0 until sample) {
                if (bytes[i] == 0.toByte()) {
                    if (i % 2 == 0) evenNul++ else oddNul++
                }
            }
            // 真正的 UTF-16 文本里约一半字节是 0；要求「只在一侧出现」且占比够高，
            // 否则会把含少量 NUL 的二进制/误选文件当成 UTF-16。
            if (oddNul >= sample / 4 && evenNul <= oddNul / 8) {
                strictOrNull(bytes, 0, StandardCharsets.UTF_16LE)
                    ?.let { return it to "UTF-16LE" }
            }
            if (evenNul >= sample / 4 && oddNul <= evenNul / 8) {
                strictOrNull(bytes, 0, StandardCharsets.UTF_16BE)
                    ?.let { return it to "UTF-16BE" }
            }
        }

        val utf8Strict = strictOrNull(bytes, 0, StandardCharsets.UTF_8)
        // 3. 干净 UTF-8（绝大多数情况）
        if (utf8Strict != null && junk(utf8Strict) == 0) return utf8Strict to "UTF-8"

        // 4. 无 BOM 的中文 UTF-16（BOM 被工具剥掉、或从某些导出器出来）
        if (bytes.size % 2 == 0) {
            utf16Evidence(bytes, sample)?.let { cs ->
                strictOrNull(bytes, 0, cs)
                    ?.takeIf { junk(it) == 0 }
                    ?.let {
                        return it to (if (cs == StandardCharsets.UTF_16LE) "UTF-16LE" else "UTF-16BE")
                    }
            }
        }

        // 走到这里意味着「严格 UTF-8 失败」或「解出来带控制字符」。
        // 现在要在「轻度损坏的 UTF-8」与「干净的 GBK」之间选一个，判据是坏字节比例：
        //  - 真正的 GBK 文件按 UTF-8 解，几乎每个汉字都会坏（比例极高）；
        //  - UTF-8 文件只坏了零星几个字节时，坏字节占比很低。
        // 只有比例足够高才敢改判 GBK —— 把整份中文一次性判成乱码的代价，
        // 远大于留下几个 U+FFFD，这个方向必须保守。
        val lossy8 = strictDecodeLossy(bytes, StandardCharsets.UTF_8)
        val bad8 = lossy8.count { it == '\uFFFD' }

        val gbk = runCatching { Charset.forName("GBK") }.getOrNull()
        val gbkStrict = gbk?.let { strictOrNull(bytes, 0, it) }
        if (gbkStrict != null && junk(gbkStrict) == 0 && bad8 * GBK_JUNK_RATIO > lossy8.length) {
            return gbkStrict to "GBK"
        }

        // 5. 兜底：保留 UTF-8 的解读；严格解码失败过就标注出来，供用户判断是否选错了文件
        val note = if (utf8Strict == null) "UTF-8 (有非法字节)" else "UTF-8"
        return lossy8 to note
    }

    /**
     * 改判 GBK 所需的坏字节比例阈值（1/20 = 5%）。
     *
     * 取 5% 而不是更高：真实的 GBK 中文文件按 UTF-8 解会有 50%+ 的坏字节，
     * 而 UTF-8 文件损坏到 5% 以上时，内容也已残缺到无法照原样使用了，
     * 此时改判 GBK 至少能让"另一个可能"被救回来。
     */
    private const val GBK_JUNK_RATIO = 20

    /**
     * 无 BOM 的 UTF-16 证据。
     *
     * 为什么不能只看「解出来好不好看」：GBK 与 UTF-16 的字节模式高度重叠，
     * 用"解码后像不像中文"判会把 GBK 文件误判成 UTF-16（反之亦然），
     * 而误判的结果是整份乱码 —— 比不识别更糟。
     *
     * 这里用的是**字节层**的可判定特征：CJK 字符在 UTF-16 里的低位字节常常
     * 落在控制区（0x00-0x1F，例如 U+4E13 → 13 4E）。于是同一份文本里会出现
     * 多个控制字节，且它们**必然集中在同一奇偶位**：
     *  - LE 低字节在前 → 控制字节落在偶数位；
     *  - BE 低字节在后 → 落在奇数位。
     * 要求 ≥2 个且至少是另一侧的 4 倍，避免被「UTF-8 正文里偶发一个控制字符」触发。
     */
    private fun utf16Evidence(bytes: ByteArray, sample: Int): Charset? {
        if (sample < 8) return null
        var evenCtrl = 0
        var oddCtrl = 0
        for (i in 0 until sample) {
            val b = bytes[i].toInt() and 0xFF
            // 控制字符（不含正文里合法的 \t \n \r）是提示词里绝不该出现的东西
            if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D) {
                if (i % 2 == 0) evenCtrl++ else oddCtrl++
            }
        }
        return when {
            evenCtrl >= 2 && evenCtrl >= 4 * oddCtrl -> StandardCharsets.UTF_16LE
            oddCtrl >= 2 && oddCtrl >= 4 * evenCtrl -> StandardCharsets.UTF_16BE
            else -> null
        }
    }

    /** 不该出现在提示词里的字符数：控制字符、私有区、解码失败标记 */
    private fun junk(text: String): Int {
        var n = 0
        for (ch in text) {
            val c = ch.code
            when {
                c == 0x09 || c == 0x0A || c == 0x0D -> {}
                c < 0x20 || c == 0x7F -> n++
                c in 0xE000..0xF8FF -> n++   // 私有使用区：解码错位的典型产物
                c == 0xFFFD -> n++
            }
        }
        return n
    }

    private fun strictOrNull(bytes: ByteArray, offset: Int, cs: Charset): String? =
        runCatching { strictDecode(bytes, offset, cs) }.getOrNull()

    private fun strictDecode(bytes: ByteArray, offset: Int, cs: Charset): String =
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            .toString()

    private fun strictDecodeLossy(bytes: ByteArray, cs: Charset): String =
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    // ==================== 形态剥离 ====================

    /** 剥离结果：文本 + 用于回显的形态名 */
    private data class Extracted(val text: String, val shape: String)

    /**
     * 形态判定结果。
     *
     * [NoPromptField] 是必须单独存在的第三种结果：它是 JSON 但没有提示词字段，
     * 既不能当纯文本原样导入（等于把一段元数据喂给模型），也不能说"导入成功"。
     */
    private sealed class ShapeResult {
        data class Got(val value: Extracted) : ShapeResult()
        data class NoPromptField(val hint: String) : ShapeResult()
    }

    private val FENCE = Regex("^```[^\\n]*\\n?([\\s\\S]*?)(?:\\n)?```\\s*$")

    private const val SHAPE_PLAIN = "纯文本"
    private const val SHAPE_FENCE = "Markdown 代码块"

    /**
     * 最多剥几层壳。
     *
     * 现实里的文件会是「围栏里贴 JSON」「JSON 字段里又套围栏」这种两层结构，
     * 所以剥一层就返回是不够的；但也没必要无限挖 —— 三层都拿不到就说明
     * 这份文件不是给人看的提示词，硬猜只会猜错。
     */
    private const val MAX_LAYERS = 3

    /**
     * 形态剥离。**每一层剥完都要回到本函数重新判定**，直到内容不再像壳。
     *
     * 踩过的坑：`extract` 原来一层就 return，于是「``` 围栏里包着 JSON」这种
     * 极常见的结构只被剥了围栏，内层 JSON 原样进了输入框 ——
     * 正是 [jsonExtract] 注释里明确要避免的「给模型一段无意义元数据」，
     * 而且它还会被回显成「导入成功（Markdown 代码块）」，用户看不出问题。
     */
    private fun extract(body: String, depth: Int = 0): ShapeResult {
        val trimmed = body.trim()

        // 1. JSON（.json 导出 / OpenAI 风格请求体）
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            when (val r = jsonExtract(trimmed)) {
                null -> return ShapeResult.NoPromptField(
                    "文件是 JSON，但里面没有可识别的提示词字段" +
                        "（已查找 systemPrompt / prompt / instructions / messages 等）",
                )
                // 字段值本身可能又是个整份围栏，统一在这里剥一次
                is ShapeResult.Got -> return ShapeResult.Got(withFencePeeled(r.value))
                is ShapeResult.NoPromptField -> return r
            }
        }

        // 2. Markdown 围栏：只在**整份内容就是一个围栏**时剥掉。
        //    提示词里正常出现的代码示例（比如要求模型输出 ```python）必须保留，
        //    所以不做「取第一个代码块」这种会吃掉正文的激进规则。
        if (depth < MAX_LAYERS) {
            fenceInner(trimmed)?.let { return asFenced(extract(it, depth + 1)) }
        }

        return ShapeResult.Got(Extracted(trimmed, SHAPE_PLAIN))
    }

    /** 整份内容就是一个围栏时返回其内部文本（已 trim）；否则 null。 */
    private fun fenceInner(text: String): String? {
        val m = FENCE.matchEntire(text) ?: return null
        return m.groupValues[1].trim().takeIf { it.isNotEmpty() }
    }

    /**
     * 把内层结果标注成「Markdown 代码块 → 内层形态」。
     *
     * 内层就是纯文本时不加前缀：那种情况下围栏确实只是运输包装，
     * 回显「Markdown 代码块」已经准确，多一段前缀反而让人以为做了额外改动。
     */
    private fun asFenced(inner: ShapeResult): ShapeResult = when (inner) {
        is ShapeResult.Got ->
            if (inner.value.shape == SHAPE_PLAIN) {
                ShapeResult.Got(Extracted(inner.value.text, SHAPE_FENCE))
            } else {
                ShapeResult.Got(Extracted(inner.value.text, "$SHAPE_FENCE → ${inner.value.shape}"))
            }
        // 围栏内层是「没有提示词字段的 JSON」时必须照样报错，
        // 否则就会把那段 JSON 当正文导进去。提示里给出唯一的自救路径，
        // 免得「我明明想用这段 JSON 当提示词」的用户卡死在这里。
        is ShapeResult.NoPromptField -> ShapeResult.NoPromptField(
            inner.hint +
                "（这段内容在 Markdown 代码围栏里；若确实想把它原样当提示词，请去掉外层围栏）",
        )
    }

    /** JSON 字段值是整份围栏时剥掉围栏，回显里保留「这个字段裹着围栏」这个事实。 */
    private fun withFencePeeled(found: Extracted): Extracted {
        val inner = fenceInner(found.text) ?: return found
        return Extracted(inner, "${found.shape} → $SHAPE_FENCE")
    }

    /**
     * JSON 形态抽取。
     *
     * 字段名按「有多大概率真的是提示词」排优先级，同优先级取层级最浅的：
     * 一旦在浅层命中就返回，避免挖到 messages[3].content 这种正文当提示词。
     *
     * 返回 null 表示「是 JSON，但里面没有提示词字段」—— 调用方据此明确报错，
     * 而不是把整份 JSON 原样塞进输入框（那等于给模型一段没有意义的元数据）。
     */
    private fun jsonExtract(text: String): ShapeResult? {
        val root = runCatching { JSONObject(text) }.getOrNull()
        if (root != null) {
            // 单个 message 对象：{"role":"system","content":"..."}
            if (root.has("role") && root.opt("content") is String) {
                val c = root.optString("content")
                if (c.isNotBlank()) return ShapeResult.Got(Extracted(c, "JSON 消息对象"))
            }
            findPromptField(root, 0)?.let { return ShapeResult.Got(it) }
            // OpenAI 风格请求体里的 messages
            val messages = root.optJSONArray("messages")
                ?: root.optJSONArray("conversation")
                ?: root.optJSONArray("history")
            if (messages != null) {
                joinMessages(messages)?.let { return ShapeResult.Got(Extracted(it, "JSON 消息数组")) }
            }
            return null
        }

        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return null
        return joinMessages(arr)?.let { ShapeResult.Got(Extracted(it, "JSON 消息数组")) }
    }

    /** 提示词字段名，按优先级从高到低。比较时忽略大小写、下划线与连字符。 */
    private val PROMPT_KEYS = listOf(
        "systemPrompt",
        "system_prompt",
        "system",
        "prompt",
        "instructions",
        "instruction",
        "persona",
        "content",
        "text",
    )

    private fun normalizeKey(k: String): String = k.lowercase().replace("_", "").replace("-", "")

    /**
     * 在对象里按优先级找提示词字段，最多下钻 [MAX_DEPTH] 层。
     *
     * 下钻是必要的：真实导出常见 {"config":{"systemPrompt":"..."}} 和
     * {"settings":{"prompt":"..."}} 这类包一层外壳的结构。
     */
    private fun findPromptField(obj: JSONObject, depth: Int): Extracted? {
        if (depth > MAX_DEPTH) return null

        val byNorm = HashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.opt(k)
            if (v is String) byNorm[normalizeKey(k)] = v
        }
        for (key in PROMPT_KEYS) {
            val v = byNorm[normalizeKey(key)]
            if (!v.isNullOrBlank()) return Extracted(v, "JSON 字段 $key")
        }

        // 同层没有，再下钻一层子对象
        val subKeys = obj.keys()
        while (subKeys.hasNext()) {
            val k = subKeys.next()
            val child = obj.optJSONObject(k) ?: continue
            findPromptField(child, depth + 1)?.let { return it }
        }
        return null
    }

    /** 消息数组 → 文本。优先只取 role=system 的条目；没有则把所有 content 拼起来。 */
    private fun joinMessages(arr: JSONArray): String? {
        val systems = ArrayList<String>()
        val others = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            val text = if (o != null) messageText(o) else arr.optString(i, "")
            if (text.isNullOrBlank()) continue
            val role = o?.optString("role").orEmpty()
            // 跳过 tool / function 之类的非对话内容
            if (role == "tool" || role == "function") continue
            if (role == "system" || role == "developer") systems.add(text) else others.add(text)
        }
        val picked = if (systems.isNotEmpty()) systems else others
        if (picked.isEmpty()) return null
        return picked.joinToString("\n\n")
    }

    private fun messageText(o: JSONObject): String? {
        // content 可能是字符串，也可能是 [{"type":"text","text":"..."}] 的分片数组
        val c = o.opt("content")
        if (c is String) return c
        if (c is JSONArray) {
            val sb = StringBuilder()
            for (i in 0 until c.length()) {
                val part = c.optJSONObject(i) ?: continue
                val t = part.optString("text", "")
                if (t.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(t)
                }
            }
            if (sb.isNotEmpty()) return sb.toString()
        }
        return o.optString("text", "").takeIf { it.isNotBlank() }
    }

    private const val MAX_DEPTH = 2

    // ==================== 清洗 ====================

    /**
     * 去掉不可见字符并统一换行。
     *
     * 只做「不可能改变语义」的变换：BOM、零宽字符、CRLF。
     * 这两类字符是"从网页/文档复制"的必然产物，而它们会让模型看到
     * 「看起来正常但每句话中间有不可见字符」的指令 —— 效果随机变差且无法解释。
     */
    private fun stripInvisibles(raw: String): String = raw
        .removePrefix("\uFEFF")
        .replace(Regex("[\uFEFF\u200B-\u200D\u2060]"), "")
        .replace("\r\n", "\n")
        .replace("\r", "\n")
}
