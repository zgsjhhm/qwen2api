package com.qwen2api.tx.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 文生图请求参数（OpenAI Images API 风格）。
 */
data class ImageRequest(
    val prompt: String,
    /** 上游模型 id，如 qwen-image-3.0-pro / qwen-image-2.0-pro */
    val model: String,
    /** 宽高比，如 1:1 / 16:9；也接受 1024x1024 这类像素写法；空表示跟随模型默认 */
    val size: String = "",
    val negativePrompt: String = "",
    /** 一次请求生成的图片数（上游单轮只回一张，>1 时串行生成） */
    val n: Int = 1,
    /** 只返回 b64_json，不返回可访问 URL（应对 OSS 链接需要鉴权 / 会过期的场景） */
    val b64Only: Boolean = false,
)

/** 单张文生图结果 */
data class GeneratedImage(
    val url: String,
    val b64: String,
    val width: Int,
    val height: Int,
    val mime: String = "image/png",
)

/** 图生图的输入图（网关已经把 multipart / JSON 里的内容取成字节） */
data class SourceImage(
    val bytes: ByteArray,
    val filename: String,
    val contentType: String,
) {
    // ByteArray 的 equals 是引用比较，data class 默认实现会让两个内容相同的
    // 源图不相等（测试里断言 request 相等时会莫名失败），显式按内容比。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SourceImage) return false
        return filename == other.filename &&
            contentType == other.contentType &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int =
        (filename.hashCode() * 31 + contentType.hashCode()) * 31 + bytes.contentHashCode()
}

/**
 * 图生图（`image_edit`）请求参数。
 *
 * 与 [ImageRequest] 分开而不是加一个可空 images 字段：两条链路的失败原因
 * 完全不同（文生图失败是提示词/额度，图生图还有「源图上传失败」「源图被判违规」），
 * 合成一个类型后错误信息只能含糊地二选一。
 */
data class ImageEditRequest(
    val prompt: String,
    /** 上游模型 id；归一化由调用方或 [QwenImageClient.normalizeModel] 完成 */
    val model: String,
    /** 一或多张源图（首个是主图） */
    val sources: List<SourceImage>,
    val size: String = "",
    val negativePrompt: String = "",
    val n: Int = 1,
    val b64Only: Boolean = false,
)

/** 文生图结果 */
data class ImageResult(
    val chatId: String,
    val images: List<GeneratedImage>,
    val model: String,
    val size: String,
    val caption: String = "",
    /** 实际发往上游的重试次数（0 = 一次成功）。用于排查「偶发 502」到底重试没有 */
    val retries: Int = 0,
)

/** 文生图事件（与 [QwenEvent] 分开：图片流语义不同，混用会让 when 分支爆炸） */
sealed class ImageEvent {
    /** 上游思考/规划过程文字（image_gen_think） */
    data class Thinking(val delta: String) : ImageEvent()

    /** 产出图片 */
    data class Image(val url: String, val width: Int, val height: Int) : ImageEvent()

    /** 上游对该轮生成的文字说明 */
    data class Caption(val delta: String) : ImageEvent()

    data class Completed(val usage: JSONObject?) : ImageEvent()

    data class Error(val code: String, val message: String) : ImageEvent()
}

/**
 * Qwen 文生图客户端（chat.qwen.ai 网页端 t2i 能力）。
 *
 * ## 上游机制（前端 bundle qwen-chat-fe 0.2.91 逆向 + 端点探测校准）
 *
 * 文生图**不是**独立的 REST 资源 —— `/api/v2/images/generations`、
 * `/api/v2/task` 之类全部返回 `{"code":"not found"}`，唯一的入口是
 * 与普通聊天共用的 `/api/v2/chat/completions`。真正的字段布局（bundle 里
 * `ChatSender.sendPromptOpenAI` 的实录，比端点探测可靠）是：
 *
 *  1. **顶层 `model` 仍是文本 LLM**（`n.id`，即用户选的 qwen3.x 模型），
 *     顶层 `size` 为期望比例 —— t2i 时前端会额外带上它
 *     （`q&&t.visionSize?{size:t.visionSize}:{}`）；
 *  2. 图像模型**只出现在 `messages[0].extra.meta.model`**，
 *     同一层还有 `subChatType: "t2i"` 与 `size`；
 *  3. `chat_type` / `sub_chat_type` 置 `"t2i"`（普通聊天是 `"t2t"`）；
 *  4. 建聊天（`/api/v2/chats/new`）时 `models` 也是**文本 LLM 列表**
 *     （`models: this._models`），只把 `chat_type` 标成 `t2i`。
 *
 * ⚠ 这是本类最容易写错的一处：把顶层 `model` 填成 `qwen-image-2.0-pro`
 * 会被上游直接判成 `Model not found`（图像模型不在 LLM 模型表里，
 * 无法作为顶层 model 路由）。
 *
 * 结果出现在 SSE 的 `phase == "image_gen"` 事件：`content` 为图片地址，
 * `extra.output_image_hw` 为 `[[高,宽]]`（元素顺序是 h,w，见 QwenImageSseParser.extractHw）。
 *
 * ## 图生图（image_edit）
 *
 * 从 bundle（0.2.91）`ChatSender.sendPromptOpenAI` 校准得到的布局：
 *
 * ```js
 * // 进入图生图态时仍是 ImageGeneration（t2i）
 * hU.makeVideoOrImageByImageGen({chatType: xt.ImageGeneration,
 *                                subChatType: xt.ImageGeneration, files: [图片条目]})
 * // 出图 phase：pa(e) = [image_gen_think, image_gen_websearch, image_gen, image_edit].includes(e)
 * ```
 *
 * 三条容易踩错的点：
 *  1. **`chat_type` 依然是 `t2i`** —— 图生图不是 `image_edit` 这个 chat_type，
 *     bundle 里 `xt.ImageEdit` 只出现在守卫与按钮文案中，上游收到的仍是 t2i
 *     （命中 `Ii(e){return e===ImageGeneration||e===ImageEdit}` 时被映射回
 *     `xt.ImageGeneration`）；
 *  2. 产出 phase 是 **`image_edit`**，而 `model` 同样只进 `extra.meta.model`；
 *  3. SSE 会把**用户上传的源图**一并回显 —— 不剔除的话调用方会拿到
 *     「自己刚传上去的那张图」当结果（见 [QwenImageSseParser.excludeSources]）。
 *
 * 因此本类**继承** [QwenClient] 而非独立实现：请求头拼装（含 Token 的
 * Latin-1 防呆）、全局节流、响应头超时保护、错误归一化这些「防静默挂起」的
 * 关键逻辑只应存在一份 —— 复制成两份后，上游一改协议必然出现「对话修好了、
 * 文生图没修」的分叉。
 *
 * ## 可重试错误
 *
 * 上游在高频调用下会返回 5xx、连接中断，或「流正常结束但一张图都没出」。
 * 这类失败与「提示词被内容安全拦截」在用户侧表现一样（都是没图），但前者重试
 * 一次大概率就好了。因此这里做**带退避的重试**，并且只重试可重试类别
 * （见 [isRetryable]）—— 鉴权/参数错误立即抛出，否则只是把同一个错误
 * 重复打给上游，白白加剧风控。
 */
open class QwenImageClient(
    token: String = "",
    throttleMs: Int,
    /**
     * 顶层 `model` 用的文本 LLM id。
     *
     * 存在的理由：t2i 的顶层 `model` **必须是 LLM**（见类注释），
     * 图像模型只能进 `messages[0].extra.meta.model`。这个参数让网关能把
     * 「当前配置的默认模型」带进来，而不是在客户端里硬编码一个 id。
     */
    private val llmModel: String = GatewayConfig.DEFAULT_MODEL,
    /** 可重试错误的最大重试次数（0 = 不重试） */
    private val retryCount: Int = 2,
    /** 首次退避毫秒（指数递增：base, base*2, base*4…） */
    private val retryBackoffMs: Long = 1500,
) : QwenClient(token, throttleMs) {

    companion object {
        /** 上游默认图像模型（前端默认值） */
        const val DEFAULT_IMAGE_MODEL = "qwen-image-2.0-pro"

        /** 退避上限：再久就不如让调用方自己重试了（而且长等待只会拖长客户端超时） */
        const val MAX_RETRY_BACKOFF_MS: Long = 8000

        /** 前端下拉框里的模型清单 */
        val IMAGE_MODELS = listOf("qwen-image-3.0-pro", "qwen-image-2.0-pro")

        /** 别名 -> 上游 id（OpenAI 风格调用方习惯只传 "qwen-image"） */
        private val MODEL_ALIASES = mapOf(
            "qwen-image" to DEFAULT_IMAGE_MODEL,
            "qwen-image-2" to "qwen-image-2.0-pro",
            "qwen-image-3" to "qwen-image-3.0-pro",
            "qwen-image-2.0" to "qwen-image-2.0-pro",
            "qwen-image-3.0" to "qwen-image-3.0-pro",
            "qwen-image-2.0-pro" to "qwen-image-2.0-pro",
            "qwen-image-3.0-pro" to "qwen-image-3.0-pro",
            "auto" to "qwen-image-3.0-pro",
        )

        /** 允许的比例（前端常量） */
        val ALLOWED_SIZES = listOf("1:1", "3:4", "4:3", "16:9", "9:16")

        /** 各模型默认比例 */
        private val DEFAULT_SIZE = mapOf(
            "qwen-image-3.0-pro" to "auto",
            "qwen-image-2.0-pro" to "16:9",
        )

        /**
         * 归一化模型名：接受别名、大小写、带组织前缀的写法。
         *
         * 为什么需要：调用方（OpenAI SDK / Cherry Studio）习惯传 `qwen-image`，
         * 而上游只认带版本号的 id。直接透传会被上游判为无效模型，
         * 错误信息又只会说「模型不存在」，排查成本全落在用户身上。
         */
        fun normalizeModel(raw: String?): String {
            val t = raw.orEmpty().trim().lowercase()
            if (t.isEmpty()) return DEFAULT_IMAGE_MODEL
            MODEL_ALIASES[t]?.let { return it }
            val last = t.substringAfterLast('/')
            MODEL_ALIASES[last]?.let { return it }
            return if (IMAGE_MODELS.contains(t)) t else DEFAULT_IMAGE_MODEL
        }

        /** 归一化比例：不合法则回落到该模型默认值 */
        fun normalizeSize(raw: String?, model: String): String {
            val t = raw.orEmpty().trim().lowercase()
            if (t.isEmpty()) return DEFAULT_SIZE[model] ?: "1:1"
            if (t == "auto" && model == "qwen-image-3.0-pro") return "auto"
            if (ALLOWED_SIZES.contains(t)) return t
            // OpenAI 习惯用 "1024x1024" 表达尺寸，映射到最接近的合法比例
            val m = Regex("^(\\d+)\\s*[x×*]\\s*(\\d+)$").find(t)
            if (m != null) {
                val w = m.groupValues[1].toDoubleOrNull() ?: 0.0
                val h = m.groupValues[2].toDoubleOrNull() ?: 0.0
                if (w > 0 && h > 0) return nearestSize(w / h)
            }
            return DEFAULT_SIZE[model] ?: "1:1"
        }

        /** 按宽高比找最接近的合法比例（阈值 12%，与前端判据同构） */
        fun nearestSize(ratio: Double): String {
            var best = "1:1"
            var bestDiff = Double.MAX_VALUE
            for (s in ALLOWED_SIZES) {
                val parts = s.split(":")
                if (parts.size != 2) continue
                val r = parts[0].toDouble() / parts[1].toDouble()
                val diff = kotlin.math.abs(ratio - r)
                if (diff < bestDiff) {
                    bestDiff = diff
                    best = s
                }
            }
            return if (bestDiff <= 0.12) best else "1:1"
        }

        /**
         * 构造 t2i 的 `/api/v2/chat/completions` 请求体。
         *
         * 抽成 `internal` 纯函数是**刻意的**：本方法的字段布局（顶层 model 必须是
         * LLM、图像模型只能进 `extra.meta.model`）是整条文生图链路最容易写错、
         * 且最难从错误信息里反推的地方 —— 写错时上游只会回一句
         * `Model not found`，看不出是哪个 model 写错了。有纯函数就能把它钉在单测里。
         *
         * @param imageModel 图像模型 id（`qwen-image-*.0-pro`）
         * @param llmModel   顶层 `model` 使用的文本 LLM id
         */
        internal fun buildImagePayload(
            chatId: String,
            prompt: String,
            imageModel: String,
            llmModel: String,
            size: String,
            negativePrompt: String = "",
            fid: String = "",
            /** 附件条目（图生图的源图）；为空时 `files` 是空数组 */
            files: List<JSONObject> = emptyList(),
        ): JSONObject {
            val featureConfig = JSONObject()
                .put("thinking_enabled", false)
                .put("output_schema", "phase")
                .put("research_mode", "normal")
                .put("auto_search", false)

            // 图像模型 / 比例 / 子类型三个键必须同层出现在 extra.meta 里
            val meta = JSONObject()
                .put("subChatType", "t2i")
                .put("model", imageModel)
            if (size.isNotEmpty()) meta.put("size", size)

            val msg = JSONObject()
                .put("id", JSONObject.NULL)
                .put("fid", fid.ifEmpty { Util.uuid() })
                .put("parentId", JSONObject.NULL)
                .put("childrenIds", JSONArray())
                .put("role", "user")
                .put("content", prompt)
                .put("user_action", "chat")
                .put("files", JSONArray().apply { files.forEach { put(it) } })
                .put("timestamp", Util.nowSec())
                .put("models", JSONArray().put(llmModel))
                .put("model", "")
                .put("chat_type", "t2i")
                .put("feature_config", featureConfig)
                .put("extra", JSONObject().put("meta", meta))
                .put("sub_chat_type", "t2i")
                .put("parent_id", JSONObject.NULL)
            if (size.isNotEmpty()) msg.put("info", JSONObject().put("size", size))

            val payload = JSONObject()
                .put("stream", true)
                .put("version", "2.1")
                .put("incremental_output", true)
                .put("chatId", chatId)
                .put("chat_id", chatId)
                .put("parentId", JSONObject.NULL)
                .put("parent_id", JSONObject.NULL)
                .put("chat_mode", "normal")
                .put("model", llmModel)
                .put("messages", JSONArray().put(msg))
                .put("timestamp", Util.nowSec())
            if (size.isNotEmpty()) payload.put("size", size)
            if (negativePrompt.isNotBlank()) payload.put("negative_prompt", negativePrompt.trim())
            return payload
        }

        /** 构造 `/api/v2/chats/new` 请求体（`models` 同样必须是 LLM） */
        internal fun buildImageChatPayload(llmModel: String): JSONObject = JSONObject()
            .put("chatId", "")
            .put("models", JSONArray().put(llmModel))
            .put("project_id", "")
            .put("timestamp", System.currentTimeMillis())
            .put("chat_type", "t2i")
            .put("chat_mode", "normal")

        /** 比例 -> 上游展示用像素（仅用于把上游「比例」对外表达成尺寸） */
        fun sizeToPixels(size: String): Pair<Int, Int> = when (size) {
            "1:1" -> 1024 to 1024
            "3:4" -> 896 to 1152
            "4:3" -> 1152 to 896
            "16:9" -> 1280 to 720
            "9:16" -> 720 to 1280
            else -> 1024 to 1024
        }
    }

    /**
     * 文生图主入口。
     *
     * @param onEvent 可选事件回调（思考/出图/说明），在 IO 线程调用
     *
     * 本方法为 `open` 仅为测试可替换（与 [QwenClient.chatStream] 同理）：
     * 端到端验证网关路由/鉴权/响应格式时不应真的去打 chat.qwen.ai。
     */
    open suspend fun generateImage(
        req: ImageRequest,
        onEvent: (suspend (ImageEvent) -> Unit)? = null,
    ): ImageResult {
        val prompt = req.prompt.trim()
        if (prompt.isEmpty()) {
            throw QwenException("BAD_REQUEST", "prompt 不能为空", 400)
        }
        val model = normalizeModel(req.model)
        val size = normalizeSize(req.size, model)
        val count = req.n.coerceIn(1, 4)

        // 上游单轮只产一张图：n>1 时串行补请求。
        // 串行而非并发是刻意的 —— 并发会显著抬高触发 Baxia 风控的概率。
        val images = ArrayList<GeneratedImage>(count)
        var caption = ""
        var chatId = ""
        var retries = 0
        var lastErr: QwenException? = null
        for (i in 0 until count) {
            try {
                val one = withRetry { attempt ->
                    if (attempt > 0) logImage("t2i retry=$attempt")
                    generateOnce(
                        prompt = prompt,
                        model = model,
                        size = size,
                        negativePrompt = req.negativePrompt,
                        b64Only = req.b64Only,
                        onEvent = if (attempt == 0) onEvent else null,
                    )
                }
                // 计数从 withRetry 取，而不是从 ImageResult 上取：
                // ImageResult.retries 由「单轮流解析」构造，那层根本不知道外面重试了几次，
                // 照抄它会让响应里的 retries 结构性地恒为 0（排查时反而误导成「没重试」）。
                retries += one.retries
                val r = one.value
                if (r.chatId.isNotEmpty()) chatId = r.chatId
                images.addAll(r.images)
                if (r.caption.isNotEmpty() && caption.isEmpty()) caption = r.caption
            } catch (e: QwenException) {
                // 失败路径上的尝试次数同样要留证，否则「重试到底跑没跑」只剩猜
                val attempts = (e as? RetryExhausted)?.attempts ?: 0
                retries += attempts
                lastErr = (e as? RetryExhausted)?.original ?: e
                logImage("t2i fail [${e.code}] attempts=$attempts got=${images.size}/$count")
                // 第一张就失败 -> 直接抛出；后续失败则保留已有结果（部分成功优于全失败）
                break
            }
        }
        if (images.isEmpty()) {
            val fail = lastErr ?: QwenException(
                "UPSTREAM_EMPTY",
                "上游未返回任何图片。可能原因: 提示词被内容安全拦截, 或账号无文生图额度",
                502,
            )
            throw fail.withRetries(retries)
        }
        return ImageResult(
            chatId = chatId,
            images = images,
            model = model,
            size = size,
            caption = caption,
            retries = retries,
        )
    }

    /**
     * 图生图主入口（OpenAI `POST /v1/images/edits` 语义）。
     *
     * 上游布局与文生图的差异只在两点（其余复用同一条链路）：
     *  1. user 消息带上 `files[]`（源图条目，由 [QwenClient.uploadFile] 上传得到）；
     *  2. 产出 phase 是 `image_edit` 而非 `image_gen`（解析器已支持）。
     *
     * `chat_type` 仍然是 `t2i`，图像模型仍然只在 `extra.meta.model`
     * —— 见类注释里 bundle 的原始代码。
     */
    open suspend fun generateImageEdit(
        req: ImageEditRequest,
        onEvent: (suspend (ImageEvent) -> Unit)? = null,
    ): ImageResult {
        val prompt = req.prompt.trim()
        if (prompt.isEmpty()) {
            throw QwenException("BAD_REQUEST", "prompt 不能为空", 400)
        }
        if (req.sources.isEmpty()) {
            throw QwenException("BAD_REQUEST", "图生图需要至少一张源图 (image)", 400)
        }
        val model = normalizeModel(req.model)
        val size = normalizeSize(req.size, model)
        val count = req.n.coerceIn(1, 4)

        // 源图只上传一次，n 张产出复用同一批 files：
        // 重传会白白多打几次 OSS 与 getstsToken，且每次重传都会在账号下留一个新文件。
        val entries = ArrayList<JSONObject>(req.sources.size)
        val sourceUrls = ArrayList<String>(req.sources.size)
        for (src in req.sources) {
            val up = uploadFile(src.bytes, src.filename, src.contentType)
            entries.add(up.entry)
            if (up.url.isNotEmpty()) sourceUrls.add(up.url)
            logImage("edit source uploaded: ${up.name} ${up.size}B -> ${up.id}")
        }

        val images = ArrayList<GeneratedImage>(count)
        var caption = ""
        var chatId = ""
        var retries = 0
        var lastErr: QwenException? = null
        for (i in 0 until count) {
            try {
                val one = withRetry { attempt ->
                    if (attempt > 0) logImage("edit retry=$attempt")
                    generateEditOnce(
                        prompt = prompt,
                        model = model,
                        size = size,
                        negativePrompt = req.negativePrompt,
                        b64Only = req.b64Only,
                        files = entries,
                        sourceUrls = sourceUrls,
                        // 重试轮的 Thinking/Caption 会与上一轮重复，静默重试
                        onEvent = if (attempt == 0) onEvent else null,
                    )
                }
                retries += one.retries
                val r = one.value
                if (r.chatId.isNotEmpty()) chatId = r.chatId
                images.addAll(r.images)
                if (r.caption.isNotEmpty() && caption.isEmpty()) caption = r.caption
            } catch (e: QwenException) {
                val attempts = (e as? RetryExhausted)?.attempts ?: 0
                retries += attempts
                lastErr = (e as? RetryExhausted)?.original ?: e
                logImage("edit fail [${e.code}] attempts=$attempts got=${images.size}/$count")
                break
            }
        }
        if (images.isEmpty()) {
            val fail = lastErr ?: QwenException(
                "UPSTREAM_EMPTY",
                "上游未返回任何改图结果。可能原因: 源图或提示词被内容安全拦截, 或账号额度用尽",
                502,
            )
            throw fail.withRetries(retries)
        }
        return ImageResult(
            chatId = chatId,
            images = images,
            model = model,
            size = size,
            caption = caption,
            retries = retries,
        )
    }

    /**
     * 上游可重试错误判定。
     *
     * 只放行「再打一次大概率就好」的类别：
     *  - `UPSTREAM_5xx` / `UPSTREAM_ERROR`：上游瞬时故障；
     *  - `NETWORK*` / `*_TIMEOUT`：连接抖动、响应头超时；
     *  - `UPSTREAM_EMPTY`：流正常结束但一张图都没有 —— 实测多为上游排队/瞬时抖动，
     *    值得再试一次；真的被内容安全拦截时重试也只是再失败一次，代价可控。
     *
     * **故意排除**：`AUTH_FAILED`（token 问题重试无用）、`BAD_REQUEST`（参数写错了）、
     * `FILE_TOO_LARGE` / `UPLOAD_FAIL`（重试只会再传一遍同样的东西）、
     * `PARSE_FAILED`（服务端明确拒绝该文件格式）、
     * [RiskControl.THROTTLE_CODE]（风控态下重试只会加重封禁）。
     */
    internal fun isRetryable(code: String): Boolean = when (code) {
        "UPSTREAM_EMPTY", "UPSTREAM_ERROR", "UPSTREAM_UNKNOWN" -> true
        "AUTH_FAILED", "NO_TOKEN", "BAD_REQUEST", "FILE_TOO_LARGE",
        "UPLOAD_FAIL", "PARSE_FAILED", "CREATE_CHAT_FAIL", "BAD_RESPONSE",
        // 风控命中：不再按 2.5s 起步的短退避重试。
        // 实测同一风控响应下 retry=4 耗时 58s、retry=0 耗时 8s，错误完全一样 ——
        // 多出来的 50s 只是把上游打得更多，而风控正是按请求数判定的。
        RiskControl.THROTTLE_CODE,
        -> false
        else -> code.startsWith("UPSTREAM_5") ||
            code.startsWith("NETWORK") ||
            code.endsWith("_TIMEOUT")
    }

    /** 退避等待（指数递增，封顶 [MAX_RETRY_BACKOFF_MS]） */
    internal fun retryDelayMs(attempt: Int): Long {
        if (retryBackoffMs <= 0) return 0
        var ms = retryBackoffMs
        repeat(attempt) { ms *= 2 }
        return ms.coerceAtMost(MAX_RETRY_BACKOFF_MS)
    }

    /**
     * 带退避的重试包装。
     *
     * 注意：`attempt` 会传给 block —— 调用方据此在重试轮**不再下发事件**。
     * 否则同一次 Thinking/Caption 会被推给客户端两遍，表现为答案里出现重复文字，
     * 而调用方完全看不出是重试造成的。
     *
     * 返回 [AttemptResult] 而不是裸结果：重试次数必须是**这一层**的事实。
     * 之前调用方改从 `ImageResult.retries` 累加，而那个字段由单轮流解析构造、
     * 恒为默认值 0，于是对外回显的 `retries` 永远是 0 —— 恰好把「到底重试没有」
     * 这个排查线索变成了假信息。
     *
     * @param block (attempt) -> 结果；attempt 从 0 开始计
     */
    private suspend fun <T> withRetry(block: suspend (Int) -> T): AttemptResult<T> {
        var attempt = 0
        var last: QwenException? = null
        while (true) {
            try {
                val r = block(attempt)
                if (attempt > 0) logImage("retry succeeded after $attempt attempt(s)")
                return AttemptResult(r, attempt)
            } catch (e: QwenException) {
                last = e
                if (attempt >= retryCount || !isRetryable(e.code)) {
                    if (attempt > 0) logImage("retry exhausted (${attempt}x) on [${e.code}]")
                    // 已失败：把「尝试了几次」一并带出去（成功路径上就是重试次数）
                    throw RetryExhausted(e, attempt)
                }
                val wait = retryDelayMs(attempt)
                logImage("retryable [${e.code}] -> wait ${wait}ms (attempt ${attempt + 1}/$retryCount)")
                if (wait > 0) delay(wait)
                attempt++
            }
        }
    }

    /**
     * 单轮文生图（建会话 -> 发请求 -> 解析流）。
     *
     * `protected open` **仅为测试可替换性**：`retries` 的正确性只有在
     * 「走了真实 [generateImage] + [withRetry] 循环」的用例里才成立。
     * 之前的用例直接 override [generateImage] 并自己构造 `retries=2`，
     * 于是它绕过的那段累加逻辑恒为 0 也没人发现 —— 用 override 掩盖 bug
     * 的测试比没有测试更危险。
     */
    protected open suspend fun generateOnce(
        prompt: String,
        model: String,
        size: String,
        negativePrompt: String,
        b64Only: Boolean,
        onEvent: (suspend (ImageEvent) -> Unit)?,
    ): ImageResult {
        val chatId = createImageChat()
        // 文生图没有「下一轮续接」需求，无论成败都用完即删，避免官网残留会话
        try {
            return imageStreamOnce(
                chatId = chatId,
                prompt = prompt,
                model = model,
                size = size,
                negativePrompt = negativePrompt,
                b64Only = b64Only,
                onEvent = onEvent,
            )
        } finally {
            runCatching { disposeChat(chatId, force = true) }
        }
    }

    /**
     * 图生图单轮：建会话 -> 带 files 的 t2i 请求 -> 解析 `image_edit` 产出。
     *
     * 与 [generateOnce] 唯一的差别是 `files` 与源图排除表，因此共用
     * [imageStreamOnce] 而不另写一份流读取循环 —— 两份流循环意味着
     * 看门狗、超时、错误归一化都要维护两遍，必然分叉。
     *
     * `protected open` 同样**仅为测试可替换性**（见 [generateOnce]）：
     * 图生图的重试计数只有在走真实 [generateImageEdit] 循环的用例里才被验证。
     */
    protected open suspend fun generateEditOnce(
        prompt: String,
        model: String,
        size: String,
        negativePrompt: String,
        b64Only: Boolean,
        files: List<JSONObject>,
        sourceUrls: List<String>,
        onEvent: (suspend (ImageEvent) -> Unit)?,
    ): ImageResult {
        val chatId = createImageChat()
        try {
            return imageStreamOnce(
                chatId = chatId,
                prompt = prompt,
                model = model,
                size = size,
                negativePrompt = negativePrompt,
                b64Only = b64Only,
                onEvent = onEvent,
                files = files,
                excludeUrls = sourceUrls,
            )
        } finally {
            runCatching { disposeChat(chatId, force = true) }
        }
    }

    /** 建聊天：`models` 用文本 LLM（图像模型不在 LLM 表里，传进去会被判 Model not found），只把 chat_type 标成 t2i */
    private suspend fun createImageChat(): String {
        val payload = buildImageChatPayload(llmModel)
        val resp = doRequest("/api/v2/chats/new", "POST", payload.toString())
        val status = resp.code
        val text = readRespText(resp)
        val body = Json.parse(text)
            ?: throw QwenException("BAD_RESPONSE", "创建文生图会话失败: " + text.take(120), 502)
        if (Json.boolOrNull(body, "success") == false) {
            val d = Json.obj(body, "data")
            val details = Util.safeStr(
                Json.strOrNull(d, "details") ?: Json.strOrNull(body, "message"), 300,
            )
            if (status == 401 || status == 403 ||
                Regex("unauthorized|token", RegexOption.IGNORE_CASE).containsMatchIn(details)
            ) {
                throw QwenException("AUTH_FAILED", "Token 无效或已过期: $details", 401)
            }
            // 建会话同样会被风控拦（实测处罚页的 url 就是 chats/completions 下的 punish 路径，
            // 但某些形态会在 chats/new 这步就返回 ret 失败）。
            if (RiskControl.hasUpstreamRiskSignature(details) ||
                RiskControl.isRiskControlBlock(details)
            ) {
                logImage("create chat blocked by risk control")
                throw QwenException(RiskControl.THROTTLE_CODE, RiskControl.HINT, 502)
            }
            throw QwenException(
                Json.strOrNull(d, "code") ?: "CREATE_CHAT_FAIL",
                "创建文生图会话失败: " + details.ifEmpty { text.take(120) },
                502,
            )
        }
        return Json.strOrNull(Json.obj(body, "data"), "id")
            ?: Json.strOrNull(body, "id")
            ?: throw QwenException("BAD_RESPONSE", "创建文生图会话未返回 id: " + text.take(120), 502)
    }

    private suspend fun imageStreamOnce(
        chatId: String,
        prompt: String,
        model: String,
        size: String,
        negativePrompt: String,
        b64Only: Boolean,
        onEvent: (suspend (ImageEvent) -> Unit)?,
        /** 图生图的源图条目（挂到 user 消息的 files[] 上）；文生图为空 */
        files: List<JSONObject> = emptyList(),
        /** 源图地址：解析阶段剔除，避免把输入图当成产出（见 QwenImageSseParser.excludeSources） */
        excludeUrls: List<String> = emptyList(),
    ): ImageResult {
        // 请求体布局见 [buildImagePayload] 的注释：顶层 model 必须是 LLM，
        // 图像模型只能进 extra.meta.model —— 写成顶层图像模型会让上游回
        // `Model not found`（图像模型不在 LLM 模型表里）。
        val payload = buildImagePayload(
            chatId = chatId,
            prompt = prompt,
            imageModel = model,
            llmModel = llmModel,
            size = size,
            negativePrompt = negativePrompt,
            files = files,
        )
        // 上游把 t2i 参数错误统一回成 `Model not found`（不说是哪个模型），
        // 没有原文就只能靠猜。这里是唯一能拿到「网关实际发了什么」的地方。
        debugPayload(
            if (files.isEmpty()) "t2i payload: " else "edit payload: ",
            payload.toString(),
        )

        val resp = doRequest(
            "/api/v2/chat/completions?chat_id=" + encodeSegment(chatId),
            "POST",
            payload.toString(),
            extraHeaders = mapOf("Accept-Language" to "en-US,en;q=0.9"),
            headerTimeoutMs = 30_000,
        )

        val status = resp.code
        val ctype = resp.header("Content-Type") ?: ""
        if (status != 200) {
            val t = readRespText(resp)
            if (status == 401 || status == 403) {
                throw QwenException("AUTH_FAILED", "Token 无效或已过期 (HTTP $status)", 401)
            }
            // 风控处罚页有时直接以非 200 返回。识别到就换成明确的风控码与中文提示，
            // 不再走「可重试的 UPSTREAM_5xx」分支 —— 风控态下重试只会加重封禁。
            if (RiskControl.hasUpstreamRiskSignature(t)) {
                logImage("upstream risk control on HTTP $status")
                throw QwenException(
                    RiskControl.THROTTLE_CODE,
                    "${RiskControl.HINT}（上游 HTTP $status）",
                    502,
                )
            }
            throw QwenException("UPSTREAM_$status", "Qwen 返回 HTTP $status: " + t.take(150), 502)
        }
        // 上游有时用 HTTP 200 返回 JSON 错误体
        if (!ctype.contains("event-stream") && !ctype.contains("text/")) {
            val t = readRespText(resp)
            if (RiskControl.hasUpstreamRiskSignature(t)) {
                logImage("upstream risk control in 200 body")
                throw QwenException(RiskControl.THROTTLE_CODE, RiskControl.HINT, 502)
            }
            if (RiskControl.isRiskControlBlock(t)) {
                logImage("upstream throttle in 200 body")
                throw QwenException(RiskControl.THROTTLE_CODE, RiskControl.HINT, 502)
            }
            throw QwenException("UPSTREAM_ERROR", "Qwen 拒绝文生图请求: " + t.take(200), 502)
        }

        val parser = QwenImageSseParser()
        // 图生图：先把源图登记为「非产出」，否则回显的输入图会被当成改图结果
        if (excludeUrls.isNotEmpty()) parser.excludeSources(excludeUrls)
        val flags = ImageStreamFlags()
        val hardMs = (maxStreamMinutes * 60_000).toLong()
        val idleMs = (idleTimeoutMinutes * 60_000).toLong()

        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val watchdog = scope.launch {
            while (true) {
                delay(500)
                if (System.currentTimeMillis() - flags.lastDataAt > idleMs) {
                    flags.idleFired = true
                    break
                }
            }
        }
        val hardJob = scope.launch {
            delay(hardMs)
            flags.hardFired = true
        }

        var upstreamError: QwenException? = null
        try {
            withContext(Dispatchers.IO) {
                resp.use { r ->
                    val source = r.body?.source()
                        ?: throw QwenException("UPSTREAM_EMPTY", "上游未返回响应体", 502)
                    while (true) {
                        if (flags.idleFired || flags.hardFired) break
                        val line = source.readUtf8Line() ?: break
                        flags.lastDataAt = System.currentTimeMillis()
                        for (evt in parser.feed(line + "\n")) {
                            if (evt is ImageEvent.Error) {
                                // 流内错误帧是风控最典型的表现（HTTP 200 + 处罚页 JSON）。
                                // 命中指纹就用统一的风控码，顺带把原始处罚页 JSON（含带
                                // x5secdata 的 punish URL）换成中文提示，不再糊给终端用户。
                                val risk = RiskControl.hasUpstreamRiskSignature(evt.message) ||
                                    RiskControl.isRiskControlBlock(evt.code, evt.message)
                                logImage("image stream error [${evt.code}] risk=$risk")
                                upstreamError = if (risk) {
                                    QwenException(RiskControl.THROTTLE_CODE, RiskControl.HINT, 502)
                                } else {
                                    QwenException(
                                        evt.code.ifEmpty { "UPSTREAM_ERROR" },
                                        "Qwen 流中返回错误: ${evt.message}。若为风控/频率限制: " +
                                            "请在浏览器打开 chat.qwen.ai 完成滑块验证或冷却几分钟后重试",
                                        502,
                                    )
                                }
                            }
                            onEvent?.invoke(evt)
                        }
                        if (upstreamError != null) break
                    }
                }
            }
        } finally {
            watchdog.cancel()
            hardJob.cancel()
            scope.cancel()
            runCatching { resp.close() }
        }

        upstreamError?.let { throw it }

        var images = parser.images
        val reason = when {
            flags.hardFired -> "达到 ${Math.round(hardMs / 60000.0)} 分钟流式上限"
            flags.idleFired -> "连接停滞 ${Math.round(idleMs / 60000.0)} 分钟无数据"
            else -> "连接提前关闭"
        }
        if (images.isEmpty()) {
            val captions = parser.captions.trim()
            val what = if (files.isEmpty()) "图片" else "改图结果"
            throw QwenException(
                "UPSTREAM_EMPTY",
                "上游未产出$what ($reason)" +
                    (if (captions.isNotEmpty()) ", 上游文字说明: ${Util.safeStr(captions, 300)}" else "") +
                    "。常见原因: 提示词触发内容安全策略 / 账号额度用尽 / 触发风控" +
                    "（可在浏览器打开 chat.qwen.ai 完成滑块验证后重试）",
                502,
            )
        }

        // 上游只给 OSS 地址；b64 按需就地下载后再编码。
        // 之所以「按需」：图片常有 1~3MB，不需要 base64 的调用方不该白付这份带宽。
        if (b64Only) {
            val withB64 = ArrayList<GeneratedImage>(images.size)
            for (img in images) {
                withB64.add(img.copy(b64 = downloadAsBase64(img.url)))
            }
            images = withB64
        }

        return ImageResult(
            chatId = chatId,
            images = images,
            model = model,
            size = size,
            caption = parser.captions.trim(),
        )
    }

    /**
     * 请求体诊断钩子。
     *
     * 默认空实现：客户端跑在网关进程里，日志出口由 [com.qwen2api.tx.server.GatewayRouter]
     * 提供（客户端不该自己依赖 android.util.Log，否则单测要额外打桩）。
     * 抽成属性而不是直接 Log 的理由就在这 —— 协议调试期必须能看到「实际发出去的字节」。
     */
    var payloadDebugSink: ((String) -> Unit)? = null

    /** 输出请求体诊断（带截断，避免日志被单条大请求刷爆） */
    protected fun debugPayload(prefix: String, body: String) {
        val sink = payloadDebugSink ?: return
        runCatching { sink(prefix + Util.safeStr(body, 1200)) }
    }

    /**
     * 图像链路日志。
     *
     * 与小工具 sink 一样由外部注入：单测里没有 android.util.Log，
     * 直接调用会抛 `RuntimeException: Stub!`，让「重试到底跑了没有」
     * 这种最需要观察的行为在测试里反而不可见。
     */
    var logSink: ((String) -> Unit)? = null

    private fun logImage(msg: String) {
        logSink?.let { runCatching { it(msg) } }
    }

    /**
     * 下载上游图片并编码为 base64（best-effort）。
     *
     * 注意：OSS 链接是**预签名直链**，不需要也不应该带 Qwen 的 Authorization/Cookie，
     * 因此这里单独构建请求，复用基类的连接池但不复用鉴权头。
     */
    open suspend fun downloadAsBase64(url: String): String {
        if (url.startsWith("data:")) return url.substringAfter(",", "")
        return withContext(Dispatchers.IO) {
            try {
                val req = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", QwenClient.UA)
                    .get()
                    .build()
                http.newCall(req).execute().use { resp ->
                    val bytes = resp.body?.bytes() ?: return@use ""
                    if (!resp.isSuccessful || bytes.isEmpty()) return@use ""
                    B64.encode(bytes)
                }
            } catch (e: Exception) {
                "" // 下载失败不影响主流程，调用方可退回使用 url
            }
        }
    }
}

/** 流生命周期标志（与对话流同构，独立一份避免跨类耦合） */
internal class ImageStreamFlags {
    @Volatile var lastDataAt: Long = System.currentTimeMillis()

    @Volatile var hardFired: Boolean = false

    @Volatile var idleFired: Boolean = false
}

/**
 * 带尝试次数的一次调用结果。
 *
 * 存在的唯一理由：让「重试了几次」成为重试包装层的**事实输出**，
 * 而不是各调用方各自去凑。此前 `retries` 由单轮流结果透传，而那层没有
 * 重试概念，累加源恒为 0 —— 对外回显的排查线索因此变成假的。
 */
internal class AttemptResult<T>(
    val value: T,
    /** 实际重试次数（0 = 首次即成功） */
    val retries: Int,
)
