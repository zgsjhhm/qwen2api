package com.qwen2api.tx.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 文生图 SSE 解析器。
 *
 * 与对话流（[QwenSseParser]）分开实现的原因：图片流的 phase 取值、
 * 增量语义、结果字段都不同，硬塞进一个类会让两边的 `when` 互相污染，
 * 且任何一侧的上游变更都可能悄悄改坏另一侧。
 *
 * 上游事件形态（前端 bundle 校准）：
 * ```jsonc
 * // 思考/规划
 * {"choices":[{"delta":{"phase":"image_gen_think","content":"..."}}]}
 * // 出图：content 是图片地址，extra.output_image_hw = [[高,宽]]（顺序实为 h,w）
 * {"choices":[{"delta":{"phase":"image_gen","content":"https://.../x.png",
 *                       "extra":{"output_image_hw":[[1328,1328]]}}}]}
 * // 文字说明（也可能走 answer）
 * {"choices":[{"delta":{"phase":"answer","content":"..."}}]}
 * {"response.completed":{}}
 * ```
 *
 * 设计上刻意**容错**：上游字段命名在小版本间会漂移（image_gen / image_gen_tool，
 * content 有时是数组），因此这里对「图片地址到底藏在哪」做了多路尝试，
 * 而不是只认一种形状 —— 解析失败时用户看到的是「没出图」，
 * 而真实原因往往只是字段换了个位置。
 */
internal class QwenImageSseParser {

    private val buf = StringBuilder()

    /**
     * 不视为「产出」的图片地址（图生图时用户上传的源图）。
     *
     * 存在的理由：图生图（`image_edit`）的 SSE 会把输入图的 file 条目一并回显，
     * 而回显的形态与产出图几乎无法区分（同样有 url、同样走 image 类 phase）。
     * 只按 URL 形状判断，用户会拿到「一张自己刚上传的图」当作改图结果 ——
     * 这种错误不会报错，只会让人以为模型没干活。
     *
     * 因此由调用方把源图地址登记进来，解析阶段直接剔除。
     */
    private val excluded = LinkedHashSet<String>()

    /** 登记源图地址（多形态：原样、归一化后、去查询串后） */
    fun excludeSources(urls: Collection<String>) {
        for (u in urls) {
            val t = u.trim()
            if (t.isEmpty()) continue
            excluded.add(t)
            excluded.add(stripQuery(t))
            normalizedOf(t)?.let {
                excluded.add(it)
                excluded.add(stripQuery(it))
            }
        }
    }

    /**
     * 跨物理行未闭合的 JSON 残留。
     *
     * 为什么需要：SSE 规范里一条事件可以跨越多个物理行（每行都带 `data:`），
     * 而有的部署干脆把一份 JSON 直接换行推送、只有第一行带 `data:` 前缀。
     * 只按行 `Json.parse` 会把这些帧整条丢掉 —— 用户看到的现象是「没出图」，
     * 而真实原因只是 JSON 被拆成了三行。
     */
    private val pending = StringBuilder()

    /** 累积的说明文字 */
    var captions: String = ""
        private set

    /** 累积的思考文字 */
    var thinking: String = ""
        private set

    var usage: JSONObject? = null
        private set

    private val found = LinkedHashMap<String, GeneratedImage>()

    /** 已解析到的图片（按首次出现顺序，去重） */
    val images: List<GeneratedImage> get() = found.values.toList()

    /**
     * 是否属于「出图」phase。
     *
     * 名单取自前端 bundle 的 `pa(e)`：
     * `[IMAGE_GEN_THINK, IMAGE_GEN_WEB_SEARCH, IMAGE_GEN, IMAGE_EDIT].includes(e)`
     * —— 注意 **`image_edit`（图生图）在这里，而此前实现只认 `image_gen` 前缀**，
     * 于是整条图生图结果流被当作「无 phase 的未知事件」丢掉，表现为
     * 「图生图跑完什么也没出」且不报错。
     *
     * think 类单独在这里也返回 true 是刻意的：它们的 content 是思考文本，
     * 由 [handle] 里更早的分支优先归到 Thinking，不会走到出图逻辑；
     * 放进名单只是为了与前端判据保持同构，避免以后再漏 phase。
     */
    private fun isImagePhase(phase: String): Boolean =
        phase == "image_gen" || phase == "image" || phase == "image_edit" ||
            phase == "image_edit_tool" || phase == "image_gen_tool"

    /** 出图前的思考类 phase（image_gen_think / image_gen_websearch / image_edit_think…） */
    private fun isImageThinkPhase(phase: String): Boolean =
        phase.contains("think", ignoreCase = true) ||
            phase == "image_gen_websearch" || phase == "web_search_image"

    fun feed(chunk: String): List<ImageEvent> {
        buf.append(chunk)
        val out = ArrayList<ImageEvent>()
        while (true) {
            val idx = buf.indexOf("\n")
            if (idx < 0) break
            val line = buf.substring(0, idx).trim()
            buf.delete(0, idx + 1)
            out.addAll(consumeLine(line))
        }
        return out
    }

    /**
     * 处理一个物理行。
     *
     * 两种输入都要吃下：
     * 1. 正常 SSE —— `data: {完整 JSON}`，一行一帧，立即解析；
     * 2. 跨行 JSON —— 首行 `data: {` 解析失败且括号未闭合时先攒着，
     *    后续行（无论带不带 `data:` 前缀）依次拼接，直到能解析出对象。
     *
     * 代价是首帧延迟到事件收尾才产出，但这只发生在真的跨行时；
     * 单行 JSON 走的是同一条快路径，没有额外等待。
     */
    private fun consumeLine(line: String): List<ImageEvent> {
        val isData = line.startsWith("data:")
        val body = if (isData) line.substring(5).trim() else line
        if (body.isEmpty()) {
            // SSE 的空行是事件分隔符：跨行帧到此为止，攒不出的直接丢弃
            pending.setLength(0)
            return emptyList()
        }
        if (!isData && pending.isEmpty()) return emptyList()
        if (body == "[DONE]") {
            pending.setLength(0)
            return emptyList()
        }

        if (pending.isNotEmpty()) pending.append('\n')
        pending.append(body)

        // 只在「括号已闭合」时才尝试解析，避免为一个半截 JSON 反复付出解析成本
        if (!bracesBalanced(pending)) {
            if (pending.length > MAX_PENDING_CHARS) pending.setLength(0)
            return emptyList()
        }

        val raw = pending.toString()
        val obj = Json.parse(raw)
        pending.setLength(0)
        if (obj == null) return emptyList()
        return handle(obj)
    }

    /**
     * 括号配平判断：只看结构括号，且忽略字符串字面量内部的括号。
     *
     * 不引 JSON 解析器来做这件事的原因：这里要判断的是「还该不该等下一行」，
     * 必须对不完整输入容错，而解析器在输入不完整时只会失败。
     */
    private fun bracesBalanced(sb: StringBuilder): Boolean {
        var depth = 0
        var inStr = false
        var escape = false
        for (i in 0 until sb.length) {
            val c = sb[i]
            if (escape) {
                escape = false
                continue
            }
            when {
                c == '\\' && inStr -> escape = true
                c == '"' -> inStr = !inStr
                inStr -> Unit
                c == '{' || c == '[' -> depth++
                c == '}' || c == ']' -> depth--
            }
        }
        if (inStr) return false
        return depth == 0 && sb.indexOf("{") >= 0
    }

    /** 流结束后残留缓冲 */
    fun tail(): String = buf.toString().trim().also {
        buf.setLength(0)
        pending.setLength(0)
    }

    private companion object {
        /** 跨行残留上限：避免畸形流把内存吃满 */
        const val MAX_PENDING_CHARS = 1 shl 20
    }

    private fun handle(obj: JSONObject): List<ImageEvent> {
        val out = ArrayList<ImageEvent>()
        Json.obj(obj, "usage")?.let { usage = it }

        // ---- 错误帧（风控 / 服务端异常）----
        val errObj = Json.obj(obj, "error")
        val choicesArr = Json.arr(obj, "choices")
        val codeField = Json.strOrNull(obj, "code")
        val messageField = Json.strOrNull(obj, "message")
        if (Json.boolOrNull(obj, "success") == false || errObj != null ||
            (codeField != null && messageField != null && choicesArr == null)
        ) {
            val d = Json.obj(obj, "data")
            out.add(
                ImageEvent.Error(
                    code = codeField
                        ?: Json.strOrNull(d, "code")
                        ?: Json.strOrNull(errObj, "code")
                        ?: "UPSTREAM_ERROR",
                    message = Util.safeStr(
                        messageField
                            ?: Json.strOrNull(errObj, "message")
                            ?: Json.strOrNull(d, "details")
                            ?: Json.strOrNull(d, "message")
                            ?: obj.toString(),
                        400,
                    ),
                ),
            )
            return out
        }

        if (obj.has("response.completed")) {
            out.add(ImageEvent.Completed(usage))
        }

        // ---- 部分部署会在顶层直接给 messages/content_list ----
        Json.arr(obj, "content_list")?.let { out.addAll(scrapeList(it)) }

        val choices = choicesArr ?: return out
        if (choices.length() == 0) return out
        val delta = Json.obj(choices.optJSONObject(0), "delta") ?: JSONObject()

        val phase = Json.str(delta, "phase")
        val contentRaw = delta.opt("content")
        val extra = Json.obj(delta, "extra") ?: JSONObject()

        // 思考类 phase 一律归到 Thinking（image_gen_think / image_edit_think / image_gen_websearch…）
        if (isImageThinkPhase(phase) && !isImagePhase(phase)) {
            val t = asText(contentRaw)
            if (t.isNotEmpty() && !isImageRef(t) && !looksLikeUrl(t)) {
                thinking += t
                out.add(ImageEvent.Thinking(t))
            }
        }

        // ---- 出图（t2i 的 image_gen / 图生图的 image_edit 都在这里）----
        if (isImagePhase(phase)) {
            val hw = extractHw(extra)
            var emitted = false
            for (cand in candidateStrings(contentRaw, extra)) {
                val url = normalizeImageUrl(cand) ?: continue
                // 图生图会把用户上传的源图一并回显，必须剔除，否则调用方会
                // 拿到「自己刚上传的那张图」当改图结果（见 [excluded] 注释）
                if (isExcluded(url)) continue
                val img = addImage(url, hw.first, hw.second)
                out.add(ImageEvent.Image(url, img.width, img.height))
                emitted = true
            }
            // content 不是地址 -> 当作说明文字
            if (!emitted) {
                val t = asText(contentRaw)
                if (t.isNotEmpty() && !looksLikeUrl(t)) {
                    captions += t
                    out.add(ImageEvent.Caption(t))
                } else if (t.isNotEmpty() && looksLikeUrl(t)) {
                    // 认得出是 URL 但被判为「非图片」时也收下：宁可多给一个地址，
                    // 也不要因为后缀判断过严而整轮空手而归。
                    val u = normalizeImageUrl(t)
                    if (u != null && !isExcluded(u)) {
                        val img = addImage(u, hw.first, hw.second)
                        out.add(ImageEvent.Image(u, img.width, img.height))
                    }
                }
            }
        }

        if (phase == "answer" || phase == "single_answer" || phase == "analyze") {
            val t = asText(contentRaw)
            if (t.isNotEmpty()) {
                val u = normalizeImageUrl(t)
                if (looksLikeUrl(t) && u != null && !isExcluded(u)) {
                    val img = addImage(u, 0, 0)
                    out.add(ImageEvent.Image(u, img.width, img.height))
                } else {
                    captions += t
                    out.add(ImageEvent.Caption(t))
                }
            }
        }

        // 兜底：无 phase 的增量（老格式）
        if (phase.isEmpty()) {
            val t = asText(contentRaw)
            val u = if (t.isNotEmpty()) normalizeImageUrl(t) else null
            if (u != null && !isExcluded(u)) {
                val img = addImage(u, 0, 0)
                out.add(ImageEvent.Image(u, img.width, img.height))
            }
        }

        // 数组形态 content_list（部分响应把多个 phase 打包）
        Json.arr(delta, "content_list")?.let { out.addAll(scrapeList(it)) }
        return out
    }

    /** 解析 content_list 里每一项（phase/content/extra 平铺） */
    private fun scrapeList(arr: JSONArray): List<ImageEvent> {
        val out = ArrayList<ImageEvent>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val phase = Json.str(item, "phase")
            val extra = Json.obj(item, "extra") ?: JSONObject()
            val content = item.opt("content")
            if (isImagePhase(phase)) {
                val hw = extractHw(extra)
                for (cand in candidateStrings(content, extra)) {
                    val url = normalizeImageUrl(cand) ?: continue
                    if (isExcluded(url)) continue
                    val img = addImage(url, hw.first, hw.second)
                    out.add(ImageEvent.Image(url, img.width, img.height))
                }
            } else if (isImageThinkPhase(phase)) {
                val t = asText(content)
                if (t.isNotEmpty()) {
                    thinking += t
                    out.add(ImageEvent.Thinking(t))
                }
            } else if (phase == "answer" || phase == "single_answer" || phase == "analyze") {
                val t = asText(content)
                if (t.isNotEmpty()) {
                    val u = normalizeImageUrl(t)
                    if (u != null && !isExcluded(u)) {
                        val img = addImage(u, 0, 0)
                        out.add(ImageEvent.Image(u, img.width, img.height))
                    } else {
                        captions += t
                        out.add(ImageEvent.Caption(t))
                    }
                }
            }
        }
        return out
    }

    /**
     * 从 extra 里取 `output_image_hw` / {"width":..,"height":..} / "16:9" 形态的尺寸。
     *
     * ⚠ `output_image_hw` 的元素顺序是 **[高, 宽]**，不是名字暗示的 [宽, 高]。
     * 两处独立证据：
     *  1. 前端 bundle 里消费它的函数 `Ln` 写作
     *     `const o=Number(a[0]), r=Number(a[1]); ... Math.abs(r/o - t/n) < .1`
     *     其中 `t/n` 由 `"16:9".split(":")` 得到（= 宽/高），即 `r` 才是宽、`o` 是高；
     *  2. 实测：请求 `size=16:9` 拿到 `output_image_hw=[[1536,2688]]`，
     *     把返回的 CDN 地址下载下来读 PNG IHDR 是 **2688x1536**，
     *     即上游给的是高 1536、宽 2688。
     * 按 [宽,高] 读会把每张图都转 90°，且比例越窄越明显，所以这里按 [高,宽] 解析。
     */
    private fun extractHw(extra: JSONObject): Pair<Int, Int> {
        val hw = extra.opt("output_image_hw")
        if (hw is JSONArray && hw.length() > 0) {
            val first = hw.opt(0)
            if (first is JSONArray && first.length() >= 2) {
                // 顺序是 [高, 宽]
                val h = first.opt(0).toString().toDoubleOrNull()?.toInt() ?: 0
                val w = first.opt(1).toString().toDoubleOrNull()?.toInt() ?: 0
                if (w > 0 && h > 0) return w to h
            }
            if (first is JSONObject) {
                val w = Json.int(first, "width")
                val h = Json.int(first, "height")
                if (w > 0 && h > 0) return w to h
            }
        }
        val meta = Json.obj(extra, "meta")
        val w = Json.int(extra, "width").let { if (it > 0) it else Json.int(meta, "width") }
        val h = Json.int(extra, "height").let { if (it > 0) it else Json.int(meta, "height") }
        if (w > 0 && h > 0) return w to h
        val sizeStr = Json.strOrNull(extra, "size") ?: Json.strOrNull(meta, "size")
        if (!sizeStr.isNullOrEmpty() && sizeStr.contains(":")) {
            val parts = sizeStr.split(":")
            if (parts.size == 2) {
                val a = parts[0].trim().toDoubleOrNull()
                val b = parts[1].trim().toDoubleOrNull()
                if (a != null && b != null && a > 0 && b > 0) {
                    // 比例无法还原像素，按长边 1024 估算，仅用于展示
                    val w2 = 1024
                    return w2 to Math.round(1024 * b / a).toInt()
                }
            }
        }
        return 0 to 0
    }

    /** 把 content（可能是字符串/数组/对象）压成纯文本 */
    private fun asText(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        is JSONArray -> {
            val sb = StringBuilder()
            for (i in 0 until v.length()) {
                val item = v.opt(i)
                if (item is String) sb.append(item)
                else if (item is JSONObject) sb.append(asText(item.opt("content")))
            }
            sb.toString()
        }
        is JSONObject -> asText(v.opt("content"))
        else -> v.toString()
    }

    private fun addImage(url: String, w: Int, h: Int): GeneratedImage {
        val exist = found[url]
        if (exist != null) {
            if ((exist.width == 0 || exist.height == 0) && w > 0 && h > 0) {
                val merged = exist.copy(width = w, height = h)
                found[url] = merged
                return merged
            }
            return exist
        }
        val img = GeneratedImage(url = url, b64 = "", width = w, height = h)
        found[url] = img
        return img
    }

    /** 从 content/extra 里穷举可能的图片地址候选 */
    private fun candidateStrings(content: Any?, extra: JSONObject): List<String> {
        val out = ArrayList<String>()
        fun add(v: Any?) {
            when (v) {
                is String -> if (v.isNotBlank()) out.add(v)
                is JSONArray -> for (i in 0 until v.length()) add(v.opt(i))
                is JSONObject -> {
                    for (k in listOf(
                        "url", "image_url", "imageUrl", "src", "thumbnail_url",
                        "source_url", "file_url", "path", "image",
                    )) {
                        if (v.has(k)) add(v.opt(k))
                    }
                }
            }
        }
        add(content)
        for (k in listOf(
            "url", "image_url", "imageUrl", "src", "image", "output_image",
            "output_image_url", "file_url", "oss_url", "image_list", "images",
        )) {
            if (extra.has(k)) add(extra.opt(k))
        }
        return out
    }

    /** 把候选串归一成可直接访问的图片地址；不像地址则返回 null */
    private fun normalizeImageUrl(raw: String): String? {
        var s = raw.trim().trim('"', '\'', ',')
        if (s.isEmpty()) return null
        // content 里塞的是 JSON 时会多一层引号/括号
        if (s.startsWith("(") && s.endsWith(")")) s = s.substring(1, s.length - 1).trim()
        if (s.startsWith("data:image/")) return s
        if (s.startsWith("//")) return "https:$s"
        if (s.startsWith("http://") || s.startsWith("https://")) return s
        // 上游有时给相对路径（/api/v2/files/... 或 /oss/...）
        if (s.startsWith("/") && !s.contains(" ")) return QwenClient.QWEN_BASE + s
        return null
    }

    /** 归一化一个**已经是地址**的串（用于源图登记，不改变「不是地址」的语义） */
    private fun normalizedOf(raw: String): String? = normalizeImageUrl(raw)

    /**
     * 地址是否属于「已登记的源图」。
     *
     * 做两层比较：
     *  1. 完全相等；
     *  2. 去掉查询串后相等 —— 上游回显源图时经常带上 OSS 图片处理参数
     *     （`?x-oss-process=image/resize,...`，前端也在用），
     *     只比全串会让同一张图因为多了一段查询参数而绕过排除。
     */
    private fun isExcluded(url: String): Boolean {
        if (excluded.isEmpty()) return false
        if (excluded.contains(url)) return true
        return excluded.contains(stripQuery(url))
    }

    private fun stripQuery(u: String): String {
        val i = u.indexOf('?')
        return if (i >= 0) u.substring(0, i) else u
    }

    private fun isImageRef(s: String): Boolean =
        s.startsWith("data:image/") || looksLikeUrl(s)

    private fun looksLikeUrl(s: String): Boolean {
        val t = s.trim()
        if (t.length < 12 || t.contains(" ")) return false
        return t.startsWith("http://") || t.startsWith("https://") ||
            t.startsWith("//") || t.startsWith("/api/") || t.startsWith("/oss") ||
            t.startsWith("data:image/") ||
            // OSS 直链常省略协议头
            Regex("^[a-z0-9-]+\\.oss-[a-z0-9-]+\\.aliyuncs\\.com/", RegexOption.IGNORE_CASE).containsMatchIn(t)
    }
}
