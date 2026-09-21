package com.qwen2api.tx.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 模型信息（对应 /v1/models 的扩展字段） */
data class QwenModel(
    val id: String,
    val name: String,
    val description: String = "",
    val capabilities: JSONObject = JSONObject(),
    val maxContext: Int? = null,
    val modality: List<String> = emptyList(),
)

/** 附件类型定义（移植自 FILE_KINDS） */
data class FileKind(
    val key: String,
    val stsType: String,
    val attachType: String,
    val fileClass: String,
    val showType: String,
    val parse: Boolean,
    val maxMB: Int,
)

enum class AttachmentKind { IMAGE, VIDEO, AUDIO, DOCUMENT }

/** 上传结果 */
data class UploadResult(
    val entry: JSONObject,
    val id: String,
    val url: String,
    val name: String,
    val size: Long,
    val kind: AttachmentKind,
    val mime: String,
)

/** 流式对话结果 */
data class ChatResult(
    val chatId: String,
    val answer: String,
    val thinking: String,
    val steps: List<String>,
    val docs: List<QwenEvent.SearchDoc>,
    val queries: List<String>,
    val usage: JSONObject?,
    val model: String,
    /** 本轮助手回复的消息 id，用于下一轮 parentId 续接 */
    val responseId: String = "",
)

/** 流式生命周期标志（看门狗与读取循环共享） */
internal class StreamFlags {
    @Volatile var lastDataAt: Long = System.currentTimeMillis()

    @Volatile var hardFired: Boolean = false

    @Volatile var idleFired: Boolean = false
}

/**
 * Qwen 逆向客户端（移植自 lib/qwen.js 的 QwenClient）。
 *
 * 协议要点（实测校准）：
 *  - 认证：Authorization: Bearer <JWT> + Cookie: token=<JWT> 双保险
 *  - 建聊天：POST /api/v2/chats/new -> data.id（自造 UUID 会 CHAT_NOT_FOUND）
 *  - completion：messages 仅允许 1 条；多轮用「对话历史拼接」（flat）策略
 *  - SSE：phase=thinking_summary（快照式,需去重）/ phase=answer（真增量）
 *  - 请求头缺 Timezone 等会「静默挂起」-> 必须全量携带 + 响应头超时保护
 *  - 高频调用触发 Baxia 滑块风控 -> 客户端节流
 */
/**
 * 上游 chat.qwen.ai 客户端。
 *
 * ⚠ 注意：本类是 `open` 且关键业务方法为 `open`，**仅为测试可替换性**而开
 *（[GatewayRouter] 里通过 `newClient` 工厂创建，测试可注入假实现来端到端验证
 * 工具调用的 SSE 分片与降级行为）。生产代码不应继承本类。
 */
open class QwenClient(
    private val qwenToken: String,
    private val throttleMs: Int,
) {
    /**
     * 上游根地址。默认即生产地址，**只在测试里**被指向本地假上游。
     *
     * 存在的理由：错误分流（风控 / 配额用尽 / 普通 5xx）的判据只有在拿到
     * **真实形态的响应体**时才能验证 —— 这些分支在既有测试里全被
     * "override 掉整个方法"的假实现绕过了，于是「额度用尽被当成风控」
     * 这类错误在真实调用前一直不可见。可替换基址让假上游返回逐字节相同的
     * 响应体，才谈得上回归。
     */
    var baseUrl: String = QWEN_BASE

    companion object {
        const val QWEN_BASE = "https://chat.qwen.ai"
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** 全局节流状态（所有实例共享，因为每个 API 请求都会新建 client） */
        private var lastCallAt: Long = 0L

        private val THROTTLE_LOCK = kotlinx.coroutines.sync.Mutex()

        /** 流式硬上限（默认 30 分钟，对应 QPP_MAX_STREAM_MINUTES） */
        var maxStreamMinutes: Double = 30.0

        /**
         * 是否保留云端对话（默认 false = 会话结束后自动删除）。
         *
         * 【与会话续接的关系】
         * 续接（[sessionCache]）要求 chat 在下一轮仍然存在，所以「立即删除」
         * 与「续接」是互斥的。为了同时兼顾「快」与「不留痕」，采用**延迟删除**：
         *   - 会话活跃期间保留 chat，后续轮次走 parentId 增量续接（快、省 token）
         *   - 闲置超过 [SESSION_IDLE_DELETE_MS] 后，由 [sweepSessions] 自动删除
         *
         * keepChats = true 时则完全不删（纯保留模式）。
         * 对应原方案的 QPP_KEEP_CHATS 开关。
         */
        var keepChats: Boolean = false

        /** 会话闲置多久后自动删除云端记录（默认 5 分钟） */
        private const val SESSION_IDLE_DELETE_MS = 5 * 60 * 1000L

        /** 停滞看门狗（默认 3 分钟，对应 QPP_IDLE_TIMEOUT_MINUTES） */
        var idleTimeoutMinutes: Double = 3.0

        /**
         * 历史窗口（保留最近 N 条历史消息）。
         *
         * 为什么需要：Qwen Web 接口不支持标准 messages 数组，多轮必须把历史
         * 压扁成一段文本重发。若不设上限，请求体会随轮数线性膨胀，
         * 表现为「聊得越久越慢」。
         *
         * 有了 [sessionCache] 增量续接后，正常情况下历史根本不会重发；
         * 这个窗口只在「续接失效、回退到扁平模式」时兜底。
         */
        var historyWindow: Int = 12

        /**
         * 会话缓存：把「同一段对话」的 chatId / 上轮响应 id 记下来，
         * 下一轮只发新消息并带 parentId 续接，避免重发全部历史。
         *
         * key = 会话指纹（由 system 提示 + 首条用户消息决定），
         * value = (chatId, lastResponseId, lastUsedAt)
         *
         * 注意：只有 [keepChats] = true 时才有意义 —— 用完即删会把 chatId
         * 删掉，续接自然失效，此时自动回退到扁平模式。
         */
        private data class SessionEntry(
            val chatId: String,
            var lastResponseId: String,
            var lastUsedAt: Long,
            /** 该会话已发送的消息轮数（用于判断是否匹配历史） */
            var turns: Int,
        )

        private val sessionCache = java.util.concurrent.ConcurrentHashMap<String, SessionEntry>()

        /** 会话缓存有效期（默认 30 分钟无活动即失效） */
        private const val SESSION_TTL_MS = 30 * 60 * 1000L

        /**
         * 计算会话指纹：相同 system + 相同首条用户消息视为同一段对话。
         *
         * ⚠ 关键：必须先剥离 ToolInjector 注入的工具定义，否则会导致续接永久失效。
         * 原因：ToolInjector 把工具说明追加到「最后一条 user 消息」，
         * 而在只有一条消息的首轮，那条消息同时就是「首条消息」——
         * 于是首轮指纹带上了 5K+ tokens 的工具定义，次轮却不带，
         * 两者 hash 不同 → 永远 miss。这里统一取「注入分隔符」之前的部分。
         */
        private fun stripInjected(text: String): String {
            // ToolInjector 用 "\n\n---\n\n" 分隔原始内容与注入指令
            val marker = "\n\n---\n\n"
            val i = text.indexOf(marker)
            return if (i >= 0) text.substring(0, i) else text
        }

        private fun sessionKey(messages: List<ChatMessage>): String? {
            val sys = messages.filter { it.role == "system" }
                .joinToString("\n") { stripInjected(it.textContent()) }
            val firstUser = messages.firstOrNull { it.role == "user" }
                ?.let { stripInjected(it.textContent()) } ?: return null
            if (firstUser.isEmpty()) return null
            return Util.sha1Hex((sys + "\u0000" + firstUser).toByteArray(Charsets.UTF_8))
        }

        /** 取出仍有效的会话（过期则清理）。 */
        private fun getSession(key: String?): SessionEntry? {
            if (key == null) return null
            val e = sessionCache[key] ?: return null
            if (System.currentTimeMillis() - e.lastUsedAt > SESSION_TTL_MS) {
                sessionCache.remove(key)
                return null
            }
            return e
        }

        /** 清空所有会话缓存（切换账号/改配置后调用）。 */
        fun clearSessions() {
            sessionCache.clear()
        }

        val FALLBACK_MODELS: List<QwenModel> = listOf(
            QwenModel("qwen3.8-max", "Qwen3.8-Max"),
            QwenModel("qwen3.7-max", "Qwen3.7-Max"),
            QwenModel("qwen3.7-plus", "Qwen3.7-Plus"),
            QwenModel("qwen3.6-plus", "Qwen3.6-Plus"),
            QwenModel("qwen3.5-plus", "Qwen3.5-Plus"),
            QwenModel("qwen3.5-omni-plus", "Qwen3.5-Omni-Plus"),
        )

        private val FILE_KINDS = mapOf(
            AttachmentKind.IMAGE to FileKind("image", "image", "image", "vision", "image", false, 10),
            AttachmentKind.VIDEO to FileKind("video", "video", "video", "vision", "video", false, 100),
            AttachmentKind.AUDIO to FileKind("audio", "audio", "audio", "vision", "audio", false, 25),
            AttachmentKind.DOCUMENT to FileKind("document", "file", "file", "document", "file", true, 30),
        )

        fun kindOf(kind: AttachmentKind): FileKind = FILE_KINDS.getValue(kind)

        /** MIME/扩展名 -> 附件类型 */
        fun mimeToKind(mime: String?, filename: String?): AttachmentKind {
            val ct = mime.orEmpty().lowercase()
            if (ct.startsWith("image/")) return AttachmentKind.IMAGE
            if (ct.startsWith("video/")) return AttachmentKind.VIDEO
            if (ct.startsWith("audio/")) return AttachmentKind.AUDIO
            val ext = filename.orEmpty().lowercase().substringAfterLast('.', "")
            return when (ext) {
                "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg" -> AttachmentKind.IMAGE
                "mp4", "mov", "avi", "mkv", "webm", "m4v" -> AttachmentKind.VIDEO
                "mp3", "wav", "m4a", "aac", "ogg", "flac" -> AttachmentKind.AUDIO
                else -> AttachmentKind.DOCUMENT
            }
        }

        // 全局 HTTP 引擎（复用连接池，避免每次请求重建）
        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)   // 流式读取不设总超时，由业务层看门狗控制
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val JSON_MT = "application/json; charset=utf-8".toMediaTypeOrNull()

        /**
         * 多轮消息 -> Qwen 单条消息内容（flat 拼接策略，移植自 buildFlatContent）
         */
        fun buildFlatContent(messages: List<ChatMessage>): String {
            val sys = messages.filter { it.role == "system" }
                .mapNotNull { it.textContent().takeIf { t -> t.isNotEmpty() } }
                .joinToString("\n\n")
            val turn = messages.filter { it.role != "system" }
            if (turn.isEmpty()) throw QwenException("BAD_REQUEST", "messages 不能为空", 400)

            if (turn.size == 1 && turn[0].role == "user") {
                return if (sys.isNotEmpty()) {
                    "[System Instructions]\n$sys\n\n${turn[0].textContent()}"
                } else {
                    turn[0].textContent()
                }
            }
            val last = turn.last()
            // 历史窗口：只取最近 N 条，避免扁平模式下请求体随轮数无限膨胀。
            // （续接模式不会走到这里；这是续接失效时的兜底。）
            val histAll = turn.dropLast(1)
            val hist = if (historyWindow > 0 && histAll.size > historyWindow) {
                histAll.takeLast(historyWindow)
            } else {
                histAll
            }
            val sb = StringBuilder()
            if (sys.isNotEmpty()) sb.append("[System Instructions]\n").append(sys).append("\n\n")
            sb.append("[Conversation History]\n")
            if (hist.size < histAll.size) {
                sb.append("\n(Earlier messages omitted)\n")
            }
            for (m in hist) {
                val role = if (m.role == "assistant") "Assistant" else "User"
                sb.append("\n").append(role).append(": ").append(m.textContent()).append("\n")
            }
            sb.append("\nBased on the conversation history above, respond directly to the user's latest message. ")
                .append("Answer in the same language as the user. Do not repeat or mention this instruction.\n\n")
            sb.append("User: ").append(last.textContent())
            return sb.toString()
        }

        const val MAX_STREAM_NOTE_PREFIX = "\n\n---\n[Qwen2API] 已达到 "
    }

    fun hasToken(): Boolean = qwenToken.trim().isNotEmpty()

    // ---------------- 请求头 ----------------

    private fun headers(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val token = qwenToken.trim()
        val h = LinkedHashMap<String, String>()
        h["Accept"] = "application/json"
        h["Accept-Language"] = "en-US,en;q=0.9"
        h["Content-Type"] = "application/json"
        h["X-Accel-Buffering"] = "no"
        h["X-Request-Id"] = Util.uuid()
        h["Version"] = "0.2.91"
        h["source"] = "web"
        h["Timezone"] = Util.asciiTimezoneHeader()
        h["User-Agent"] = UA
        h["Origin"] = QWEN_BASE
        h["Referer"] = "$QWEN_BASE/"
        h.putAll(extra)

        if (token.isNotEmpty()) {
            // 防御：HTTP 头只允许 Latin-1 字符
            if (token.any { it.code > 0xFF }) {
                throw QwenException(
                    "TOKEN_INVALID_CHARS",
                    "token 含中文或非拉丁字符，无法用于请求头。请在配置页重新粘贴: 打开 chat.qwen.ai → " +
                        "F12 → Console 输入 localStorage.token，只复制 eyJ 开头的字符串本体",
                    400,
                )
            }
            if (token.startsWith("eyJ")) {
                h["Authorization"] = "Bearer $token"
                h["Cookie"] = "token=$token"
            } else {
                h["Cookie"] = token // 整段 Cookie 串
            }
        }
        // 出站前最后防线：逐个扫描头值，非 Latin-1 直接点名拦截
        for ((k, v) in h) {
            if (v.any { it.code > 0xFF }) {
                throw QwenException(
                    "HEADER_INVALID_CHARS",
                    "请求头「$k」含中文或非拉丁字符, 已在本地拦截(未发往服务端)。若反复出现请反馈",
                    400,
                )
            }
        }
        return h
    }

    // ---------------- 节流 ----------------

    /**
     * 节流：距上次 Qwen 请求不足 throttleMs 则等待（全局共享状态）。
     * 注意：suspend 函数不能标 @Synchronized，改用普通锁 + 延迟在锁外执行。
     */
    private suspend fun throttle() {
        val gap = throttleMs.coerceAtLeast(0)
        if (gap > 0) {
            val last = THROTTLE_LOCK.withLock { lastCallAt }
            val wait = gap - (System.currentTimeMillis() - last)
            if (wait > 0) kotlinx.coroutines.delay(wait)
        }
        THROTTLE_LOCK.withLock { lastCallAt = System.currentTimeMillis() }
    }

    /**
     * 统一请求：带响应头超时（防静默挂起）+ 错误归一化。
     *
     * 注意：超时只覆盖「响应头阶段」。Android OkHttp 的 callTimeout 会连正文流一起割断，
     * 因此这里用 callTimeout(0) + 业务层手写超时协程实现响应头超时。
     */
    private suspend fun request(
        pathname: String,
        method: String = "GET",
        body: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        headerTimeoutMs: Long = 30_000,
    ): Response {
        if (!hasToken()) {
            throw QwenException("NO_TOKEN", "Qwen token 未配置, 请在配置页设置", 401)
        }
        throttle()

        val builder = Request.Builder().url(baseUrl + pathname)
        for ((k, v) in headers(extraHeaders)) builder.header(k, v)
        when (method.uppercase()) {
            "POST" -> builder.post((body ?: "").toRequestBody(JSON_MT))
            "PUT" -> builder.put((body ?: "").toRequestBody(JSON_MT))
            "DELETE" -> builder.delete()
            else -> builder.get()
        }
        val call = http.newCall(builder.build())
        // 追踪非对话类请求（DELETE 清理等），便于确认「用完即删」真的发出
        if (method.uppercase() != "POST" || !pathname.contains("completions")) {
            logDebug("REQ ${method.uppercase()} $pathname")
        }

        return try {
            // 响应头超时保护：用带超时的 withContext 竞速，超时则取消 call
            kotlinx.coroutines.withTimeoutOrNull(headerTimeoutMs) {
                withContext(Dispatchers.IO) { call.execute() }
            } ?: run {
                call.cancel()
                throw QwenException(
                    "NETWORK_TIMEOUT",
                    "服务器 ${headerTimeoutMs / 1000} 秒内未返回响应头(静默挂起保护触发)。" +
                        "Qwen 偶发无响应, 稍后重试; 若反复出现请检查网络代理",
                    502,
                )
            }
        } catch (e: QwenException) {
            throw e
        } catch (e: IOException) {
            throw QwenException("NETWORK", "无法连接 chat.qwen.ai: ${Util.describeNetworkError(e)}", 502)
        } catch (e: Exception) {
            throw QwenException("NETWORK", "无法连接 chat.qwen.ai: ${Util.describeNetworkError(e)}", 502)
        }
    }

    private suspend fun readText(resp: Response): String = withContext(Dispatchers.IO) {
        resp.use { it.body?.string().orEmpty() }
    }

    // ---------------- 子类复用钩子 ----------------
    // 文生图（QwenImageClient）与对话共用同一套：请求头拼装（含 Token 校验、
    // Latin-1 防呆）、全局节流、响应头超时、错误归一化。
    // 这些逻辑一旦复制成两份，上游改协议时必然出现「对话修好了、文生图没修」的
    // 分叉，所以刻意只暴露薄薄一层转发，而不是把内部实现整体 protected 化。

    /** 子类请求入口（语义与 [request] 完全一致） */
    protected suspend fun doRequest(
        pathname: String,
        method: String = "GET",
        body: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        headerTimeoutMs: Long = 30_000,
    ): Response = request(pathname, method, body, extraHeaders, headerTimeoutMs)

    /** 子类读取响应正文（自动关闭响应） */
    protected suspend fun readRespText(resp: Response): String = readText(resp)

    /** 子类使用同一套百分号编码规则 */
    protected fun encodeSegment(s: String): String = encodeUriComponent(s)

    // ---------------- 业务接口 ----------------

    /** 模型列表 */
    /** 模型列表（open 仅为测试可替换） */
    open suspend fun listModels(): List<QwenModel> {
        val resp = request("/api/v2/models/", "GET")
        val status = resp.code
        val text = readText(resp)
        if (status == 401 || status == 403) {
            throw QwenException("AUTH_FAILED", "Token 无效或已过期 (HTTP $status), 请重新获取", 401)
        }
        val body = Json.parse(text)
            ?: Json.parseAny(text)?.let { if (it is JSONObject) it else null }
            ?: throw QwenException("BAD_RESPONSE", "模型列表响应异常: " + text.take(120), 502)

        val items = ArrayList<JSONObject>()
        val data = body.opt("data")
        when {
            data is JSONArray -> for (i in 0 until data.length()) data.optJSONObject(i)?.let { items.add(it) }
            data is JSONObject -> {
                val inner = data.opt("data")
                if (inner is JSONArray) for (i in 0 until inner.length()) inner.optJSONObject(i)?.let { items.add(it) }
                else items.add(data)
            }
            body.has("id") -> items.add(body)
        }

        val models = items.mapNotNull { m ->
            val id = Json.strOrNull(m, "id") ?: return@mapNotNull null
            val meta = Json.obj(Json.obj(m, "info"), "meta") ?: JSONObject()
            QwenModel(
                id = id,
                name = Json.str(m, "name", id),
                description = Json.str(meta, "description"),
                capabilities = Json.obj(meta, "capabilities") ?: JSONObject(),
                maxContext = meta.opt("max_context_length")?.let { Json.int(meta, "max_context_length") },
                modality = Json.arr(meta, "modality")?.let { a ->
                    (0 until a.length()).map { a.optString(it, "") }.filter { it.isNotEmpty() }
                } ?: emptyList(),
            )
        }
        if (models.isEmpty()) {
            throw QwenException("BAD_RESPONSE", "模型列表为空, token 可能已失效", 502)
        }
        return models
    }

    /** 新建聊天 -> chat_id（必须用服务端返回的 id） */
    suspend fun createChat(model: String): String {
        val payload = JSONObject()
            .put("chatId", "")
            .put("models", JSONArray().put(model))
            .put("project_id", "")
            .put("timestamp", System.currentTimeMillis())
            .put("chat_type", "t2t")
            .put("chat_mode", "normal")
        val resp = request("/api/v2/chats/new", "POST", payload.toString())
        val status = resp.code
        val text = readText(resp)
        val body = Json.parse(text)
            ?: throw QwenException("BAD_RESPONSE", "创建聊天失败: " + text.take(120), 502)

        if (Json.boolOrNull(body, "success") == false) {
            val d = Json.obj(body, "data")
            val code = Json.strOrNull(d, "code") ?: Json.strOrNull(body, "code") ?: "CREATE_CHAT_FAIL"
            val details = Util.safeStr(
                Json.strOrNull(d, "details") ?: Json.strOrNull(body, "message"), 300,
            )
            if (status == 401 || status == 403 || Regex("unauthorized|token", RegexOption.IGNORE_CASE).containsMatchIn(details)) {
                throw QwenException("AUTH_FAILED", "Token 无效或已过期: $details", 401)
            }
            // 建会话同样会被配额/风控拦，且上游把两者编码进同一个 `RateLimited`
            // 家族 —— 分流统一走 RiskControl.blockCode（配额优先，两者处置建议相反）。
            val blocked = RiskControl.blockCode(details) ?: RiskControl.blockCode(code)
            if (blocked != null) {
                throw QwenException(blocked, RiskControl.hintFor(blocked), 502)
            }
            throw QwenException(code, "创建聊天失败: " + details.ifEmpty { code }, 502)
        }
        val id = Json.strOrNull(Json.obj(body, "data"), "id")
            ?: Json.strOrNull(body, "id")
            ?: Json.strOrNull(Json.obj(Json.obj(body, "data"), "data"), "id")
            ?: throw QwenException("BAD_RESPONSE", "创建聊天未返回 id: " + text.take(120), 502)
        return id
    }

    /** 获取阿里云 STS 临时凭证（文件上传第一步） */
    private suspend fun getSts(kind: AttachmentKind, filename: String, size: Long): JSONObject {
        val payload = JSONObject()
            .put("filename", filename.ifEmpty { "file.bin" })
            .put("filesize", size.toString())
            .put("filetype", kindOf(kind).stsType)
        val resp = request("/api/v2/files/getstsToken", "POST", payload.toString())
        val status = resp.code
        val text = readText(resp)
        val body = Json.parse(text)
            ?: throw QwenException("BAD_RESPONSE", "STS 响应异常: " + text.take(120), 502)
        val data = Json.obj(body, "data")
        if (Json.boolOrNull(body, "success") == false || data == null || data.optString("file_id").isEmpty()) {
            val details = Util.safeStr(
                Json.strOrNull(data, "details") ?: Json.strOrNull(body, "message") ?: body, 200,
            )
            if (status == 401 || status == 403 || Regex("unauthorized|token", RegexOption.IGNORE_CASE).containsMatchIn(details)) {
                throw QwenException("AUTH_FAILED", "Token 无效或已过期: $details", 401)
            }
            throw QwenException("UPLOAD_FAIL", "获取上传凭证失败: $details", 502)
        }
        return data
    }

    /**
     * OSS 直传（HMAC-SHA1 手写签名，零依赖；移植自 ossPut）
     */
    private suspend fun ossPut(sts: JSONObject, bytes: ByteArray, contentType: String): String {
        val bucket = Json.str(sts, "bucketname")
        val objectKey = OssSigner.objectKeyOf(Json.str(sts, "file_path"), bucket)
        if (objectKey.isEmpty()) throw QwenException("UPLOAD_FAIL", "STS 响应缺少 file_path", 502)

        val secToken = Json.str(sts, "security_token")
        val date = Util.httpDate()
        // 拼串 / 签名 / URL 全部委托 OssSigner —— 拆出去的目的是让**签名字符串本身**
        // 能被单测直接断言。以前这段内联在方法体里，canonical 串只存在于局部变量，
        // 要断言它就必须真发一次 PUT（单测里没有 OSS 可打），
        // 于是 CanonicalizedOSSHeaders 的分隔符写成 "=" 也能一路溜进生产环境。
        val canonical = OssSigner.canonicalString(
            contentType = contentType,
            date = date,
            securityToken = secToken,
            bucket = bucket,
            objectKey = objectKey,
        )
        val sig = OssSigner.signature(Json.str(sts, "access_key_secret"), canonical)
        val url = OssSigner.urlFor(Json.str(sts, "endpoint"), bucket, objectKey)

        // 上传失败时最需要知道的是「我们到底签了什么、发到哪个 URL」：
        // OSS 401/403 的响应体里只有一句 SignatureDoesNotMatch，
        // 而 canonical 串本身（bucket 前缀是否被重复、endpoint 是否被改写、
        // Date 格式是否被方言化）是唯一能定位的线索。secret 绝不落日志。
        logDebug(
            "OSS PUT url=$url date=$date ct=$contentType bucket=$bucket key=$objectKey " +
                "tokenLen=${secToken.length} akid=${Json.str(sts, "access_key_id").take(6)}**",
        )

        val reqBody = bytes.toRequestBody(contentType.toMediaTypeOrNull())
        val req = Request.Builder()
            .url(url)
            .put(reqBody)
            .header("Content-Type", contentType)
            .header("Date", date)
            .header("Authorization", "OSS " + Json.str(sts, "access_key_id") + ":" + sig)
            .header("x-oss-security-token", secToken)
            .build()

        return try {
            withContext(Dispatchers.IO) {
                http.newBuilder()
                    .callTimeout(120, TimeUnit.SECONDS)
                    .build()
                    .newCall(req)
                    .execute().use { resp ->
                        if (!resp.isSuccessful) {
                            val t = resp.body?.string().orEmpty()
                            // 403 时把「我方 canonical」与「服务端 StringToSign」逐字段 diff，
                            // 直接指出是哪一段不一致，而不是回一句"签名不对"让人猜。
                            // 归因结果不含 secret，token 已截断掩码。
                            val attr = OssSigner.diagnose(
                                canonical,
                                OssSigner.serverStringToSign(t),
                            )
                            logDebug(attr)
                            throw QwenException(
                                "UPLOAD_FAIL",
                                "OSS 上传失败 HTTP ${resp.code}: " +
                                    attr + "\n--- raw ---\n" + t.take(1200),
                                502,
                            )
                        }
                    }
            }
            Json.str(sts, "file_url")
        } catch (e: QwenException) {
            throw e
        } catch (e: Exception) {
            throw QwenException("UPLOAD_FAIL", "OSS 上传网络异常: " + (e.message ?: e.toString()), 502)
        }
    }

    /**
     * 百分号编码（空格转 `%20`）。实现已收敛到 [OssSigner.encodeUriComponent]，
     * 这里保留薄封装是为了不改动调用点（chat_id / 文件名等）。
     *
     * 之所以不再各存一份：签名用的 canonical 里是**原始 key**，URL 里是**编码后 key**，
     * 两处编码规则一旦分叉，就会复制出「签名与 URL 对不上」的 403，
     * 而那种 403 与本次修掉的 `=` / `:` 问题症状完全一样，排查上极难区分。
     */
    private fun encodeUriComponent(s: String): String = OssSigner.encodeUriComponent(s)

    /** 文档解析（上传后触发 + 轮询直至 success） */
    private suspend fun parseDocument(fileId: String) {
        try {
            request(
                "/api/v2/files/parse", "POST",
                JSONObject().put("file_id", fileId).toString(),
                headerTimeoutMs = 30_000,
            ).close()
        } catch (e: Exception) {
            // 触发失败不阻断：有些类型不支持解析
        }
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 15_000) {
            delay(1200)
            try {
                val resp = request(
                    "/api/v2/files/parse/status", "POST",
                    JSONObject().put("file_id_list", JSONArray().put(fileId)).toString(),
                    headerTimeoutMs = 15_000,
                )
                val j = Json.parse(readText(resp)) ?: continue
                val data = j.opt("data")
                val st = when {
                    data is JSONArray && data.length() > 0 ->
                        Json.str(data.optJSONObject(0), "status")
                    else -> Json.str(j, "status")
                }
                if (st == "success") return
                if (st == "failed") {
                    throw QwenException(
                        "PARSE_FAILED",
                        "文档解析失败 (Qwen 服务端不支持该文件或内容为空), 可尝试换格式(如 txt/md/pdf)重试",
                        502,
                    )
                }
            } catch (e: QwenException) {
                if (e.code == "PARSE_FAILED") throw e
            } catch (e: Exception) {
                // 单次轮询失败忽略，继续
            }
        }
    }

    /** 构造 web UI 同款 files[] 条目 */
    private fun buildFileEntry(
        kind: AttachmentKind,
        sts: JSONObject,
        filename: String,
        size: Long,
        contentType: String,
    ): JSONObject {
        val k = kindOf(kind)
        val userId = Json.str(sts, "file_path").split("/").firstOrNull().orEmpty()
        val now = System.currentTimeMillis()
        val meta = JSONObject()
            .put("name", filename)
            .put("size", size)
            .put("content_type", contentType)
        if (k.parse) meta.put("parse_meta", JSONObject().put("parse_status", "success"))

        val fileObj = JSONObject()
            .put("created_at", now)
            .put("data", JSONObject())
            .put("filename", filename)
            .put("hash", JSONObject.NULL)
            .put("id", Json.str(sts, "file_id"))
            .put("user_id", userId)
            .put("meta", meta)
            .put("update_at", now)
            .put("lastModified", now)
            .put("name", filename)
            .put("webkitRelativePath", "")
            .put("size", size)
            .put("type", contentType)

        return JSONObject()
            .put("type", k.attachType)
            .put("file", fileObj)
            .put("id", Json.str(sts, "file_id"))
            .put("url", Json.str(sts, "file_url"))
            .put("name", filename)
            .put("collection_name", "")
            .put("progress", 0)
            .put("status", "uploaded")
            .put("greenNet", "success")
            .put("size", size)
            .put("error", "")
            .put("itemId", Util.uuid())
            .put("file_type", contentType)
            .put("showType", k.showType)
            .put("file_class", k.fileClass)
            .put("uploadTaskId", Util.uuid())
    }

    /**
     * 上传附件 -> 返回可直接填入 chat files[] 的条目。
     * （open 仅为测试可替换）
     */
    open suspend fun uploadFile(
        bytes: ByteArray,
        filename: String,
        contentType: String,
        kind: AttachmentKind? = null,
    ): UploadResult {
        if (bytes.isEmpty()) throw QwenException("BAD_REQUEST", "附件内容为空", 400)
        val actualKind = kind ?: mimeToKind(contentType, filename)
        val k = kindOf(actualKind)
        if (bytes.size > k.maxMB.toLong() * 1024 * 1024) {
            throw QwenException(
                "FILE_TOO_LARGE",
                "附件过大: ${"%.1f".format(bytes.size / 1048576.0)}MB, ${k.key} 类型上限 ${k.maxMB}MB",
                413,
            )
        }
        val safeName = (filename.ifEmpty {
            "file." + when (actualKind) {
                AttachmentKind.IMAGE -> "png"
                AttachmentKind.VIDEO -> "mp4"
                else -> "bin"
            }
        }).take(180)
        val ct = contentType.ifEmpty { "application/octet-stream" }

        val sts = getSts(actualKind, safeName, bytes.size.toLong())
        ossPut(sts, bytes, ct)
        if (k.parse) parseDocument(Json.str(sts, "file_id"))
        val entry = buildFileEntry(actualKind, sts, safeName, bytes.size.toLong(), ct)
        return UploadResult(
            entry = entry,
            id = Json.str(sts, "file_id"),
            url = Json.str(sts, "file_url"),
            name = safeName,
            size = bytes.size.toLong(),
            kind = actualKind,
            mime = ct,
        )
    }

    /**
     * 流式对话（移植自 chatStream）。
     *
     * @param onEvent 每个解析事件的回调（在 IO 线程调用，实现方需自行切线程）
     *
     * 本方法为 `open` 仅为测试可替换（见类注释）。
     */
    open suspend fun chatStream(
        model: String,
        messages: List<ChatMessage>,
        thinking: Boolean,
        files: List<JSONObject> = emptyList(),
        onEvent: (suspend (QwenEvent) -> Unit)? = null,
    ): ChatResult {
        // ---------- 0. 顺带清理闲置过久的云端会话（延迟删除）----------
        runCatching { sweepSessions() }

        // ---------- 1. 尝试增量续接（只发新消息）----------
        // 命中缓存即可续接，不必依赖 keepChats：chat 在闲置窗口内仍然存在。
        val key = sessionKey(messages)
        val sess = getSession(key)
        if (sess != null && sess.lastResponseId.isNotEmpty()) {
            val lastUser = messages.lastOrNull { it.role == "user" }
            if (lastUser != null) {
                try {
                    val r = chatStreamOnce(
                        model = model,
                        messages = listOf(lastUser),        // 仅新消息
                        content = lastUser.textContent(),
                        thinking = thinking,
                        files = files,
                        chatId = sess.chatId,
                        parentId = sess.lastResponseId,     // 续接点
                        onEvent = onEvent,
                    )
                    // 续接成功：刷新使用时间，维持会话存活
                    sess.lastResponseId = r.responseId.ifEmpty { sess.lastResponseId }
                    sess.lastUsedAt = System.currentTimeMillis()
                    sess.turns++
                    logDebug("session resumed: chat=${sess.chatId} turn=${sess.turns}")
                    return r
                } catch (e: Exception) {
                    // 续接失败（chat 被删 / parentId 失效）→ 清缓存，回退扁平模式
                    logDebug("session resume failed, fallback to flat: ${e.message}")
                    sessionCache.remove(key)
                }
            }
        }

        // ---------- 2. 扁平模式（建立新会话）----------
        val content = buildFlatContent(messages)
        val chatId = createChat(model)
        try {
            val r = chatStreamOnce(
                model = model,
                messages = messages,
                content = content,
                thinking = thinking,
                files = files,
                chatId = chatId,
                parentId = "",           // 新会话：parentId 空
                onEvent = onEvent,
            )
            // 记下会话供下一轮续接（不立即删除，交由 sweepSessions 闲置清理）
            if (key != null && r.responseId.isNotEmpty()) {
                sessionCache[key] = SessionEntry(
                    chatId = chatId,
                    lastResponseId = r.responseId,
                    lastUsedAt = System.currentTimeMillis(),
                    turns = 1,
                )
            } else if (!keepChats) {
                // 拿不到 responseId 就无法续接，直接删掉避免残留
                disposeChat(chatId, force = true)
            }
            return r
        } catch (e: Exception) {
            disposeChat(chatId, force = true)
            throw e
        }
    }

    private suspend fun chatStreamOnce(
        model: String,
        messages: List<ChatMessage>,
        content: String,
        thinking: Boolean,
        files: List<JSONObject>,
        chatId: String,
        parentId: String = "",
        onEvent: (suspend (QwenEvent) -> Unit)?,
    ): ChatResult {

        val featureConfig = JSONObject()
            .put("thinking_enabled", thinking)
            .put("output_schema", "phase")
            .put("research_mode", "normal")
            .put("auto_thinking", thinking)
            .put("thinking_mode", "Auto")
            .put("thinking_format", "summary")
            .put("auto_search", false) // 纯 API 包装，不启用联网搜索

        val msg = JSONObject()
            .put("id", JSONObject.NULL)
            .put("fid", Util.uuid())
            // 续接模式下指向上一轮助手回复；新会话为空
            .put("parentId", parentId.ifEmpty { JSONObject.NULL })
            .put("childrenIds", JSONArray())
            .put("role", "user")
            .put("content", content)
            .put("user_action", "chat")
            .put("files", JSONArray(files))
            .put("timestamp", Util.nowSec())
            .put("models", JSONArray().put(model))
            .put("model", "")
            .put("chat_type", "t2t")
            .put("feature_config", featureConfig)
            .put("extra", JSONObject().put("meta", JSONObject().put("subChatType", "t2t")))
            .put("sub_chat_type", "t2t")
            .put("parent_id", parentId.ifEmpty { JSONObject.NULL })

        val payload = JSONObject()
            .put("stream", true)
            .put("version", "2.1")
            .put("incremental_output", true)
            .put("chatId", chatId)
            .put("chat_id", chatId)
            .put("parentId", parentId.ifEmpty { JSONObject.NULL })
            .put("parent_id", parentId.ifEmpty { JSONObject.NULL })
            .put("chat_mode", "normal")
            .put("model", model)
            .put("messages", JSONArray().put(msg))
            .put("timestamp", Util.nowSec())

        val resp = request(
            "/api/v2/chat/completions?chat_id=" + encodeUriComponent(chatId),
            "POST",
            payload.toString(),
            extraHeaders = mapOf("Accept-Language" to "en-US,en;q=0.9"),
            headerTimeoutMs = 30_000,
        )

        val status = resp.code
        val ctype = resp.header("Content-Type") ?: ""

        if (status != 200) {
            val t = readText(resp)
            if (status == 401 || status == 403) {
                throw QwenException("AUTH_FAILED", "Token 无效或已过期 (HTTP $status)", 401)
            }
            throw QwenException("UPSTREAM_$status", "Qwen 返回 HTTP $status: " + t.take(150), 502)
        }
        // Qwen 有时用 HTTP 200 返回 JSON 错误体
        if (!ctype.contains("event-stream") && !ctype.contains("text/")) {
            val t = readText(resp)
            var code = "UPSTREAM_ERROR"
            var details = t.take(200)
            Json.parse(t)?.let { j ->
                code = Json.strOrNull(Json.obj(j, "data"), "code") ?: Json.strOrNull(j, "code") ?: code
                details = Util.safeStr(
                    Json.strOrNull(Json.obj(j, "data"), "details")
                        ?: Json.strOrNull(j, "details")
                        ?: Json.strOrNull(j, "message"),
                    300,
                ).ifEmpty { details }
                if (Regex("token|unauthorized", RegexOption.IGNORE_CASE).containsMatchIn(details + code)) {
                    throw QwenException("AUTH_FAILED", "Token 无效或已过期: $details", 401)
                }
                // 额度用尽与风控/限流的处置建议相反（等明天 vs 过滑块），
                // 且上游把它编码成 `RateLimited` —— 字面与"限流"同族。
                // 不先摘出来会被上层当风控走 8/15/25s 等待重试，
                // 而它今天之内重试多少次都是同一结果。
                val blocked = RiskControl.blockCode(details) ?: RiskControl.blockCode(code)
                if (blocked == RiskControl.QUOTA_CODE) {
                    throw QwenException(blocked, RiskControl.hintFor(blocked), 502)
                }
            }
            throw QwenException(code, "Qwen 拒绝请求: $details", 502)
        }

        // ---- 流式读取 ----
        val parser = QwenSseParser()
        val answer = StringBuilder()
        val docList = ArrayList<QwenEvent.SearchDoc>()
        var queries: List<String> = emptyList()
        /** 本轮响应 id（来自 response.created），用于下一轮续接 */
        var responseId: String = ""
        val flags = StreamFlags()

        val hardMs = (maxStreamMinutes * 60_000).toLong()
        val idleMs = (idleTimeoutMinutes * 60_000).toLong()

        // 看门狗：停滞检测（在 chatStream 的协程作用域内启动，随函数返回自动取消）
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.Default,
        )
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
                    val source = r.body?.source() ?: throw QwenException(
                        "UPSTREAM_EMPTY", "上游未返回响应体", 502,
                    )
                    while (true) {
                        if (flags.idleFired || flags.hardFired) break
                        val line = source.readUtf8Line() ?: break
                        flags.lastDataAt = System.currentTimeMillis()
                        val events = parser.feed(line + "\n")
                        for (evt in events) {
                            when (evt) {
                                is QwenEvent.Content -> answer.append(evt.delta)
                                // 记下本轮响应 id，供下一轮 parentId 续接
                                is QwenEvent.Created -> responseId = evt.responseId
                                is QwenEvent.SearchDocs -> docList.addAll(evt.docs)
                                is QwenEvent.SearchQueries -> queries = evt.queries
                                is QwenEvent.UpstreamError -> {
                                    // 配额用尽与风控都会走错误帧，但给用户的处置建议完全相反。
                                    // 上游把两者都编码进 200 的正文，因此必须在这里分流，
                                    // 否则「额度用尽」会被附上一句「请完成滑块验证」的无效建议。
                                    val quota = RiskControl.isQuotaExhausted(evt.message) ||
                                        RiskControl.isQuotaExhausted(evt.code)
                                    upstreamError = if (quota) {
                                        QwenException(RiskControl.QUOTA_CODE, RiskControl.QUOTA_HINT, 502)
                                    } else {
                                        QwenException(
                                            evt.code.ifEmpty { "UPSTREAM_ERROR" },
                                            "Qwen 流中返回错误: ${evt.message}" +
                                                (if (parser.thinking.isNotEmpty()) " (思考已输出 ${parser.thinking.length} 字)" else "") +
                                                (if (answer.isNotEmpty()) " (正文已接收 ${answer.length} 字, 客户端已保留)" else "") +
                                                "。若为风控/频率限制: 请在浏览器打开 chat.qwen.ai 完成滑块验证或冷却几分钟后重试",
                                            502,
                                        )
                                    }
                                }
                                else -> Unit
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
            try { resp.close() } catch (e: Exception) { /* ignore */ }
        }

        upstreamError?.let { throw it }

        // 收尾校验：流结束却没有正文 = 被上游掐断/提前关闭
        if (answer.isEmpty()) {
            val rest = parser.tail()
            if (parser.thinking.isEmpty() && rest.isNotEmpty() && rest.contains("\"success\":false")) {
                var code = "UPSTREAM_ERROR"
                var details = rest.take(200)
                Json.parse(rest.removePrefix("data:").trim())?.let { j ->
                    code = Json.strOrNull(Json.obj(j, "data"), "code") ?: Json.strOrNull(j, "code") ?: code
                    details = Util.safeStr(
                        Json.strOrNull(Json.obj(j, "data"), "details")
                            ?: Json.strOrNull(j, "details")
                            ?: Json.strOrNull(j, "message"),
                        300,
                    ).ifEmpty { details }
                }
                // 同上：流尾部形态的错误也要先把「额度用尽」摘出来，
                // 否则会被上层的风控重试逻辑接走，白等 8/15/25s。
                val blocked = RiskControl.blockCode(details) ?: RiskControl.blockCode(code)
                if (blocked == RiskControl.QUOTA_CODE) {
                    throw QwenException(blocked, RiskControl.hintFor(blocked), 502)
                }
                throw QwenException(code, "Qwen 拒绝请求: $details", 502)
            }
            val why = when {
                flags.hardFired -> "(达到 ${fmtMs(hardMs)}流式上限被本地收尾)"
                flags.idleFired -> "(连接停滞 ${fmtMs(idleMs)}无数据被本地收尾)"
                else -> "(连接提前关闭)"
            }
            if (parser.thinking.isEmpty()) {
                throw QwenException(
                    "UPSTREAM_EMPTY",
                    "上游未返回任何内容$why" + (if (rest.isNotEmpty()) ", 流尾部: ${Util.safeStr(rest, 200)}" else "") +
                        "。常见原因: 触发 Qwen 风控——请在浏览器打开 chat.qwen.ai 完成滑块验证或冷却几分钟后重试",
                    502,
                )
            }
            throw QwenException(
                "UPSTREAM_TRUNCATED",
                "思考完成后上游中断, 未输出正文 (已接收思考 ${parser.thinking.length} 字$why" +
                    (if (rest.isNotEmpty()) ", 流尾部: ${Util.safeStr(rest, 200)}" else "") + ")" +
                    "。常见原因: 触发 Qwen 风控或连接被重置——请在浏览器打开 chat.qwen.ai 完成滑块验证/冷却几分钟再试; " +
                    "反复出现请降低调用频率",
                502,
            )
        }

        var finalAnswer = answer.toString().trim()
        if (flags.hardFired) {
            finalAnswer += MAX_STREAM_NOTE_PREFIX + fmtMs(hardMs) +
                "流式上限, 回复在此截断。如需更长回答请调大流式上限设置"
        }

        // 成功路径：把结果带给调用方后即可清理云端对话
        // （先用局部变量保存，确保 dispose 失败也不影响返回）
        val result = ChatResult(
            chatId = chatId,
            answer = finalAnswer,
            thinking = parser.thinking.trim(),
            steps = parser.steps,
            docs = docList,
            queries = queries,
            usage = parser.usage,
            model = model,
            responseId = responseId,
        )
        return result
    }

    /** 删除上游文件（best-effort） */
    /** 删除云端文件（open 仅为测试可替换） */
    open suspend fun deleteUpstreamFile(qwenFileId: String) {
        try {
            request("/api/v1/files/$qwenFileId", "DELETE", headerTimeoutMs = 15_000).close()
        } catch (e: Exception) {
            // 忽略
        }
    }

    /**
     * 删除云端对话（best-effort，用完即删）。
     *
     * 为什么要删：
     *  1. 隐私 —— 每次 API 调用都会在 chat.qwen.ai 的对话列表留下记录，
     *     长期使用会积累大量无意义会话；
     *  2. 降低风控概率 —— 异常高频的「只建不删」行为模式本身就是风控特征之一。
     *
     * 失败不抛异常（不影响主流程）；可通过 QPP_KEEP_CHATS 语义的配置项保留。
     */
    suspend fun disposeChat(chatId: String?, force: Boolean = false) {
        if (chatId.isNullOrEmpty()) return
        if (keepChats && !force) {
            logDebug("disposeChat skipped (keepChats=true): $chatId")
            return
        }
        try {
            val resp = request(
                "/api/v2/chats/" + encodeUriComponent(chatId),
                "DELETE",
                headerTimeoutMs = 15_000,
            )
            resp.close()
            logDebug("disposeChat ok: $chatId")
        } catch (e: Exception) {
            logDebug("disposeChat failed: $chatId -> ${e.message}")
            // 忽略：清理失败不应影响已成功的对话结果
        }
    }

    /**
     * 清理闲置过久的云端会话（延迟删除）。
     *
     * 为什么不是「用完即删」：续接需要 chat 存活到下一轮，立即删会让
     * 每轮都退化成扁平模式（= 又慢又费 token）。这里改为闲置一段时间后
     * 再删，既拿到续接的性能收益，又不会长期在官网留记录。
     *
     * 由 [chatStream] 在每次请求前顺带调用，无需额外定时器。
     */
    private suspend fun sweepSessions() {
        if (keepChats) return
        val now = System.currentTimeMillis()
        val expired = sessionCache.entries.filter { now - it.value.lastUsedAt > SESSION_IDLE_DELETE_MS }
        for ((k, e) in expired) {
            sessionCache.remove(k)
            disposeChat(e.chatId, force = true)
        }
    }

    private fun logDebug(msg: String) {
        // 注意：不能依赖 BuildConfig.DEBUG —— 本工程的 debug 构建里
        // 该字段可能为 false（实测 manifest 是 DEBUGGABLE 但字段为 false），
        // 会导致日志全部静默、排查时误判「代码没执行」。
        runCatching { android.util.Log.i("Qwen2API", msg) }
    }

    private fun fmtMs(ms: Long): String =
        if (ms >= 60_000) "${Math.round(ms / 60000.0)} 分钟" else "${Math.round(ms / 1000.0)} 秒"
}

/** 对话消息（对应 OpenAI messages 条目，content 支持纯文本或多模态数组） */
data class ChatMessage(
    val role: String,
    val content: Any?,     // String 或 List<Map<String, Any?>>
    /** 工具消息（role="tool"）对应哪个调用 */
    val toolCallId: String? = null,
    /** 工具消息（role="tool"）的工具名 */
    val name: String? = null,
    /**
     * assistant 消息里模型发起的工具调用（OpenAI tool_calls 数组）。
     * 上游不支持，发送前由 [ToolMessageCodec] 还原成约定的 fence 文本。
     */
    val toolCalls: JSONArray? = null,
) {
    /** 取出纯文本内容（多模态数组时拼接 text part） */
    @Suppress("UNCHECKED_CAST")
    fun textContent(): String {
        val c = content ?: return ""
        if (c is String) return c
        if (c is List<*>) {
            return c.filterIsInstance<Map<String, Any?>>()
                .filter { it["type"] == "text" }
                .mapNotNull { it["text"]?.toString() }
                .joinToString("\n")
        }
        return c.toString()
    }
}
