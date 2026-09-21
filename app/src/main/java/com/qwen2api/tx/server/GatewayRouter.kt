package com.qwen2api.tx.server

import android.content.Context

import com.qwen2api.tx.BuildConfig
import com.qwen2api.tx.core.AttachmentKind
import com.qwen2api.tx.core.B64
import com.qwen2api.tx.core.ChatMessage
import com.qwen2api.tx.core.ConfigRepository
import com.qwen2api.tx.core.ConfigStore
import com.qwen2api.tx.core.FileRecord
import com.qwen2api.tx.core.FileStore
import com.qwen2api.tx.core.GatewayConfig
import com.qwen2api.tx.core.Json
import com.qwen2api.tx.core.QwenClient
import com.qwen2api.tx.core.QwenEvent
import com.qwen2api.tx.core.QwenImageClient
import com.qwen2api.tx.core.ImageEditRequest
import com.qwen2api.tx.core.ImageRequest
import com.qwen2api.tx.core.ImageResult
import com.qwen2api.tx.core.SourceImage
import com.qwen2api.tx.core.ToolPrompt
import com.qwen2api.tx.core.QwenException
import com.qwen2api.tx.core.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 运行期状态（对应 lib/server.js 的 state） */
object GatewayState {
    val startTime: Long = System.currentTimeMillis()

    @Volatile var qwenOk: Boolean? = null

    @Volatile var qwenCheckAt: Long = 0L

    @Volatile var modelsCache: List<com.qwen2api.tx.core.QwenModel>? = null

    @Volatile var modelsCacheAt: Long = 0L

    @Volatile var reqCount: Long = 0L

    @Volatile var lastRequestLog: String = ""
}

/**
 * 主路由（移植自 lib/server.js）。
 *  - /v1/models, /v1/chat/completions  完整 OpenAI Completion 兼容（流式/非流式）
 *  - /v1/files                         OpenAI Files API 风格
 *  - /admin/api/...                    管理接口
 *
 * 依赖通过构造参数注入（配置仓储 / 文件仓储），
 * 使同一份生产代码能在普通 JVM 上被端到端测试。
 */
class GatewayRouter(
    private val context: Context?,
    private val configRepo: ConfigRepository,
    private val fileStore: FileStore,
    /**
     * 上游客户端工厂。
     *
     * 默认实现即生产行为（每次请求新建 [QwenClient]）。
     * 抽成构造参数是**唯一**为了可测试性：工具调用链路的端到端测试
     *（请求注入 -> 流式 SSE 分片 -> 工具名校验）需要替换上游，
     * 否则只能靠真实网络打 chat.qwen.ai，无法在单测里回归。
     */
    private val clientFactory: (GatewayConfig) -> QwenClient = { cfg ->
        QwenClient(cfg.qwenToken, cfg.throttleMs)
    },
    /**
     * 文生图客户端工厂。
     *
     * 与 [clientFactory] 分开而不是复用同一个工厂：两者返回的**类型语义不同**
     * （对话客户端 vs 具备 t2i 能力的客户端），共用一个工厂会让「注入假上游」
     * 的测试必须在同一实现里同时伪造 chat 与 image 两条完全不同的流，
     * 假实现迅速膨胀成第二套协议实现，回归价值反而下降。
     */
    private val imageClientFactory: (GatewayConfig) -> QwenImageClient = { cfg ->
        // 顶层 model 必须是文本 LLM：用用户配置的默认模型，而不是图像模型 id。
        QwenImageClient(
            token = cfg.qwenToken,
            throttleMs = cfg.throttleMs,
            llmModel = cfg.defaultModel,
            // 重试策略来自配置而不是构造器默认值：默认值只是兜底，
            // 账号被风控时用户需要能在 UI/admin 里把它关掉，否则网关会越打越凶。
            retryCount = cfg.imageRetryCount,
            retryBackoffMs = cfg.imageRetryBackoffMs.toLong(),
        )
    },
) {
    /** 生产环境构造器：使用 SharedPreferences 持久化 */
    constructor(context: Context) : this(
        context.applicationContext,
        ConfigStore.repository(context),
        FileRegistry(context),
    )

    /** 测试构造器：纯内存依赖 */
    constructor(configRepo: ConfigRepository, fileStore: FileStore) : this(null, configRepo, fileStore)

    /** 测试构造器：纯内存依赖 + 自定义上游对话客户端 */
    constructor(
        configRepo: ConfigRepository,
        fileStore: FileStore,
        clientFactory: (GatewayConfig) -> QwenClient,
    ) : this(null, configRepo, fileStore, clientFactory)

    /**
     * 测试构造器：纯内存依赖 + 自定义文生图客户端。
     *
     * 注意 [clientFactory] 默认值指向真实 [QwenClient]（不触网也能构造），
     * 因此只关心文生图的测试无需伪造对话流。
     */
    constructor(
        configRepo: ConfigRepository,
        fileStore: FileStore,
        imageClientFactory: (GatewayConfig) -> QwenImageClient,
        imageOnly: Boolean,
    ) : this(null, configRepo, fileStore, { cfg -> QwenClient(cfg.qwenToken, cfg.throttleMs) }, imageClientFactory)

    fun fileRegistryRef(): FileStore = fileStore

    /** 入口：分发单个请求 */
    suspend fun handle(req: HttpRequest, res: HttpResponse) {
        val cfg = configRepo.load()

        // CORS
        res.header("Access-Control-Allow-Origin", "*")
        res.header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        res.header("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Api-Key")

        if (req.method == "OPTIONS") {
            res.writeHead(204)
            res.end()
            return
        }

        // 去掉尾部斜杠
        val pathname = req.path.replace(Regex("/+$"), "").ifEmpty { "/" }

        // 路由判定用 effectiveMethod：HEAD 与 GET 指向同一个资源表示，
        // 区别只在"不要正文"。若按原始方法匹配，`HEAD /v1/models`（探活/预检最常用的方法）
        // 会掉进 404，调用方会误判成"网关没有这个接口"。
        // 正文的抑制由 HttpResponse 负责，它知道本次请求是不是 HEAD。
        val method = req.effectiveMethod

        try {
            when {
                pathname == "/v1/models" && method == "GET" -> handleModels(req, res, cfg)
                pathname == "/v1/chat/completions" && method == "POST" -> handleChat(req, res, cfg)
                pathname == "/v1/images/generations" && method == "POST" ->
                    handleImageGeneration(req, res, cfg)
                pathname == "/v1/images/generations" && method == "GET" ->
                    handleImageModels(req, res, cfg)
                // 图生图（编辑）。POST 走 multipart（OpenAI 官方语义）或 JSON（base64 内联），
                // GET 与 generations 一样给自描述，让调用方先探测再发真实请求。
                pathname == "/v1/images/edits" && method == "POST" ->
                    handleImageEdit(req, res, cfg)
                pathname == "/v1/images/edits" && method == "GET" ->
                    handleImageEditModels(req, res, cfg)
                pathname == "/v1/files" && method == "POST" -> handleFileUpload(req, res, cfg)
                pathname == "/v1/files" && method == "GET" -> handleFileList(req, res, cfg)
                pathname.startsWith("/v1/files/") -> handleFileItem(req, res, cfg, pathname)
                pathname.startsWith("/admin/") -> handleAdmin(req, res, cfg, pathname)
                pathname == "/healthz" && method == "GET" ->
                    sendJson(res, 200, mapOf("ok" to true, "version" to GatewayConfig.VERSION))
                else -> {
                    // "路径不存在"（404）与"方法不对"（405）必须分开：
                    // 前者让调用方去翻文档找路径、怀疑网关版本，而真相常常只是
                    // 把 POST 写成了 GET；后者带 Allow 头，一眼可见。
                    val allow = allowedMethods(pathname)
                    if (allow != null) methodNotAllowed(res, allow)
                    else sendJson(res, 404, mapOf("error" to "not found"))
                }
            }
        } catch (e: Exception) {
            val (code, msg, _) = Util.normalizeError(e)
            debugLog("handler exception: ${e::class.java.name}: ${e.message}\n" +
                e.stackTraceToString().take(1500))
            try {
                sendJson(
                    res, 500,
                    mapOf(
                        "error" to mapOf(
                            "message" to "internal error: $msg",
                            "type" to "api_error",
                            "code" to code,
                        ),
                    ),
                )
            } catch (e2: Exception) {
                // 连接已断开
            }
        }
    }

    // ---------------- 工具 ----------------

    /**
     * 路径 -> 允许的方法集合（供 405 + `Allow` 头使用）。
     *
     * 返回 null 表示**这个路径根本不存在**，调用方应回 404。
     * 两者混用是排障成本极高的一类错误：404 会让人去翻文档找路径、
     * 怀疑网关版本不对，而真相常常只是把 POST 写成了 GET。
     *
     * 表本身与 [handle] 里的 `when` 一一对应，改动路由时两处都要动 ——
     * 这是刻意保留的重复：把"允许什么"集中在一处会让 [handle] 的分支
     * 与 Allow 头更容易悄悄漂移。
     *
     * HEAD 与 GET 同义（RFC 7231 §4.3.2），因此凡允许 GET 的路径都一并列出 HEAD。
     */
    private fun allowedMethods(pathname: String): List<String>? = when {
        pathname == "/v1/models" -> listOf("GET", "HEAD")
        pathname == "/v1/chat/completions" -> listOf("POST")
        // 文生图：POST 生成；GET 用于「这个网关支不支持出图 + 有哪些模型」的自描述，
        // 便于调用方在配置前先探测，而不是发一次真实生成请求去试错。
        pathname == "/v1/images/generations" -> listOf("GET", "HEAD", "POST")
        // 图生图：与 generations 同构，POST 编辑 / GET 自描述
        pathname == "/v1/images/edits" -> listOf("GET", "HEAD", "POST")
        pathname == "/v1/files" -> listOf("GET", "HEAD", "POST")
        pathname == "/healthz" -> listOf("GET", "HEAD")
        // /v1/files/<id>：详情 GET/HEAD，删除 DELETE；
        // /v1/files/<id>/content 是只读子资源，写方法一律不允许。
        pathname.startsWith("/v1/files/") -> {
            val segs = pathname.split('/').filter { it.isNotEmpty() }
            when {
                segs.size == 3 -> listOf("GET", "HEAD", "DELETE")
                segs.size == 4 && segs[3] == "content" -> listOf("GET", "HEAD")
                // 形状非法（多一级、或第四段不是 content）：交给详情处理器按 404 处理
                else -> null
            }
        }
        pathname == "/admin/api/status" -> listOf("GET", "HEAD")
        pathname == "/admin/api/token" -> listOf("POST")
        pathname == "/admin/api/check" -> listOf("POST")
        pathname == "/admin/api/settings" -> listOf("POST")
        pathname == "/admin/api/key/regenerate" -> listOf("POST")
        pathname.startsWith("/admin/") -> null
        else -> null
    }

    /**
     * 405 响应：路径存在但方法不对。
     *
     * `Allow` 是这条响应里**唯一**有效的信息载体，缺了它调用方仍然只能猜；
     * 因此必须无条件带上（OPTIONS 未在此列是因为它已在入口被统一处理为 CORS 预检）。
     */
    private fun methodNotAllowed(res: HttpResponse, allow: List<String>) {
        res.header("Allow", allow.joinToString(", "))
        sendJson(
            res, HttpStatus.METHOD_NOT_ALLOWED,
            mapOf(
                "error" to mapOf(
                    "message" to "方法不被该路径允许，请使用：${allow.joinToString(", ")}",
                    "type" to "invalid_request_error",
                    "code" to "method_not_allowed",
                ),
            ),
        )
    }

    private fun sendJson(res: HttpResponse, status: Int, obj: Any) {
        val body = Json.encode(obj).toByteArray(Charsets.UTF_8)
        res.header("Content-Type", "application/json; charset=utf-8")
        res.header("Content-Length", body.size.toString())
        res.writeHead(status)
        res.end(body)
    }

    private fun sendError(res: HttpResponse, e: QwenException) {
        sendJson(res, e.status, mapOf("error" to e.toOpenAiError()))
    }

    private fun checkAuth(req: HttpRequest, cfg: GatewayConfig): Boolean {
        val h = req.header("authorization").orEmpty().trim()
        val m = Regex("^Bearer\\s+(.+)$", RegexOption.IGNORE_CASE).find(h) ?: return false
        val key = m.groupValues[1].trim()
        return key.isNotEmpty() && key == cfg.apiKey
    }

    private fun apiKeyError(res: HttpResponse) {
        res.header("WWW-Authenticate", "Bearer")
        sendJson(
            res, 401,
            mapOf(
                "error" to mapOf(
                    "message" to "Invalid API key. 请使用应用内生成的密钥 (sk-qpp-...)",
                    "type" to "authentication_error",
                    "code" to "invalid_api_key",
                ),
            ),
        )
    }

    /**
     * admin 仅接受本机 host（DNS rebinding 防护）。
     *
     * 与 reference/lib/server.js 的 adminHostOk 严格对齐：只认回环地址与 localhost。
     * 不能把 "0.0.0.0" 或空串算作本机 —— 攻击者页面只要让请求的 Host 落在这两个值上，
     * 防护就整体失效（局域网模式下浏览器甚至可直接把 Host 写成 0.0.0.0）。
     * Host 头缺失（HTTP/1.0 或畸形请求）同样视为不可信。
     */
    private fun adminHostOk(req: HttpRequest): Boolean {
        val raw = req.hostHeader.trim().lowercase()
        if (raw.isEmpty()) return false
        val host = hostWithoutPort(raw)
        if (host.isEmpty()) return false
        return host in listOf("127.0.0.1", "localhost", "[::1]", "::1")
    }

    /**
     * 去掉 host:port 里的端口部分。
     * 不能简单 substringBefore(':') —— IPv6 字面量（如 [::1]:8818）会被切坏成 "["。
     */
    private fun hostWithoutPort(raw: String): String {
        val v6End = raw.indexOf(']')
        if (v6End >= 0) return raw.substring(0, v6End + 1)
        val colon = raw.indexOf(':')
        return if (colon >= 0) raw.substring(0, colon) else raw
    }

    /**
     * 存储加密状态查询门面。
     *
     * [com.qwen2api.tx.core.SecretVault] 是 `internal` 且强依赖 AndroidKeyStore，
     * 这里只暴露一个布尔值给管理接口/UI。之所以要单独开个口子：
     * `lastDegraded` 原先是个**只写不读**的标志，加密降级（用户凭证明文落盘）
     * 对用户完全不可见 —— 属于最典型的"静默失效"。
     */
    internal object StorageCrypto {
        fun isDegraded(): Boolean = try {
            com.qwen2api.tx.core.SecretVault.lastDegraded
        } catch (e: Throwable) {
            false
        }
    }

    private fun newClient(cfg: GatewayConfig) = clientFactory(cfg)

    private fun newImageClient(cfg: GatewayConfig) = imageClientFactory(cfg)

    private fun parseMessages(body: JSONObject): MutableList<ChatMessage> {
        val arr = Json.arr(body, "messages") ?: return ArrayList()
        val list = ArrayList<ChatMessage>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            var role = Json.str(m, "role", "user")
            if (role !in listOf("user", "assistant", "system", "tool")) role = "user"
            val contentRaw = m.opt("content")
            val content: Any? = when (contentRaw) {
                null, JSONObject.NULL -> ""
                is String -> contentRaw
                is JSONArray -> {
                    val parts = ArrayList<Map<String, Any?>>()
                    for (j in 0 until contentRaw.length()) {
                        val p = contentRaw.optJSONObject(j) ?: continue
                        @Suppress("UNCHECKED_CAST")
                        parts.add(Json.toMap(p) as Map<String, Any?>)
                    }
                    parts
                }
                else -> contentRaw.toString()
            }
            list.add(
                ChatMessage(
                    role = role,
                    content = content,
                    toolCallId = Json.strOrNull(m, "tool_call_id"),
                    name = Json.strOrNull(m, "name"),
                    toolCalls = m.optJSONArray("tool_calls"),
                ),
            )
        }
        return list
    }

    private fun openAiModels(models: List<com.qwen2api.tx.core.QwenModel>): Map<String, Any> {
        val data = models.map { m ->
            linkedMapOf<String, Any?>(
                "id" to m.id,
                "object" to "model",
                "created" to 1700000000,
                "owned_by" to "qwen2api-tx",
                // 扩展字段（不影响标准 OpenAI SDK 解析）
                "name" to m.name.ifEmpty { m.id },
                "capabilities" to m.capabilities,
                "max_context_length" to m.maxContext,
                "modality" to m.modality,
            )
        }
        return linkedMapOf("object" to "list", "data" to data)
    }

    private fun chunkFrame(
        id: String,
        model: String,
        delta: Map<String, Any?>,
        finishReason: String?,
        usage: Map<String, Any?>? = null,
    ): String {
        val frame = JSONObject()
        frame.put("id", id)
        frame.put("object", "chat.completion.chunk")
        frame.put("created", Util.nowSec())
        frame.put("model", model)
        val choice = JSONObject()
        choice.put("index", 0)
        choice.put("delta", Json.toJsonObject(delta))
        choice.put("finish_reason", finishReason ?: JSONObject.NULL)
        frame.put("choices", JSONArray().put(choice))
        if (usage != null) frame.put("usage", Json.toJsonObject(usage))
        return frame.toString()
    }

    private fun usageOf(usage: JSONObject?): Map<String, Any?> {
        val input = usage?.let {
            Json.int(it, "input_tokens").takeIf { v -> v > 0 } ?: Json.int(it, "prompt_tokens")
        } ?: 0
        val output = usage?.let {
            Json.int(it, "output_tokens").takeIf { v -> v > 0 } ?: Json.int(it, "completion_tokens")
        } ?: 0
        val total = usage?.let { Json.int(it, "total_tokens") } ?: 0
        return linkedMapOf(
            "prompt_tokens" to input,
            "completion_tokens" to output,
            "total_tokens" to total,
        )
    }

    // ---------------- /v1/models ----------------

    private suspend fun handleModels(req: HttpRequest, res: HttpResponse, cfg: GatewayConfig) {
        GatewayState.reqCount++
        if (!checkAuth(req, cfg)) return apiKeyError(res)

        val cached = GatewayState.modelsCache
        val cacheAt = GatewayState.modelsCacheAt
        if (cached != null && cacheAt > 0 && System.currentTimeMillis() - cacheAt < 300_000) {
            return sendJson(res, 200, openAiModels(cached))
        }
        val fresh = configRepo.load()
        try {
            val models = newClient(fresh).listModels()
            GatewayState.modelsCache = models
            GatewayState.modelsCacheAt = System.currentTimeMillis()
            GatewayState.qwenOk = true
            GatewayState.qwenCheckAt = System.currentTimeMillis()
            sendJson(res, 200, openAiModels(models))
        } catch (e: QwenException) {
            if (e.code == "AUTH_FAILED") {
                GatewayState.qwenOk = false
                GatewayState.qwenCheckAt = System.currentTimeMillis()
                return sendError(res, e)
            }
            // 拉取失败 -> 兜底旧缓存 / 静态列表（仍可调用）
            val fallback = cached ?: QwenClient.FALLBACK_MODELS
            sendJson(res, 200, openAiModels(fallback))
        }
    }

    // ---------------- /v1/images/generations ----------------

    /**
     * 文生图能力自描述（`GET /v1/images/generations`）。
     *
     * 存在的理由：OpenAI 的 Images API 只有 POST，调用方无法在「发一次真实
     * 生成请求」之前知道这个网关支不支持出图、有哪些模型、支持哪些尺寸。
     * 这里用一个只读的 GET 把这三件事讲清楚，代价是零上游请求。
     *
     * 但这个接口**不能免鉴权**：它虽然不打上游，仍然在对外暴露账号能力与
     * 模型清单。局域网模式下任何一台设备扫到端口就能读到，
     * 而调用方拿不到「未授权」信号时，会以为密钥是可选的。
     */
    private suspend fun handleImageModels(req: HttpRequest, res: HttpResponse, cfg: GatewayConfig) {
        GatewayState.reqCount++
        if (!checkAuth(req, cfg)) return apiKeyError(res)
        sendJson(
            res, 200,
            mapOf(
                "object" to "list",
                "supported" to true,
                "endpoint" to "/v1/images/generations",
                "data" to QwenImageClient.IMAGE_MODELS.mapIndexed { i, id ->
                    mapOf(
                        "id" to id,
                        "object" to "model",
                        "created" to GatewayState.startTime / 1000,
                        "owned_by" to "qwen",
                        "index" to i,
                        "default" to (id == QwenImageClient.DEFAULT_IMAGE_MODEL),
                    )
                },
                "sizes" to QwenImageClient.ALLOWED_SIZES,
                "default_size" to mapOf(
                    "qwen-image-2.0-pro" to "16:9",
                    "qwen-image-3.0-pro" to "auto",
                ),
                "max_n" to 4,
                "notes" to listOf(
                    "尺寸支持比例写法 (1:1/3:4/4:3/16:9/9:16); 1024x1024 这类像素值会自动映射到最近比例",
                    "仅 qwen-image-3.0-pro 支持 size=auto",
                    "上游单轮只产一张图, n>1 时网关串行补请求(每张之间受节流限制)",
                ),
            ),
        )
    }

    private suspend fun handleImageGeneration(
        req: HttpRequest,
        res: HttpResponse,
        cfg: GatewayConfig,
    ) {
        GatewayState.reqCount++
        if (!checkAuth(req, cfg)) return apiKeyError(res)

        val body = Json.parse(req.bodyText())
            ?: return sendJson(
                res, 400,
                mapOf(
                    "error" to mapOf(
                        "message" to "invalid JSON body",
                        "type" to "invalid_request_error",
                        "code" to "bad_request",
                    ),
                ),
            )

        val prompt = Json.str(body, "prompt").ifEmpty {
            // OpenAI 新版用 input / 部分客户端用 messages，兼容一下免得调用方踩空
            Json.str(body, "input")
        }
        if (prompt.isBlank()) {
            return sendJson(
                res, 400,
                mapOf(
                    "error" to mapOf(
                        "message" to "prompt is required",
                        "type" to "invalid_request_error",
                        "code" to "bad_request",
                    ),
                ),
            )
        }

        val t0 = System.currentTimeMillis()
        val fresh = configRepo.load()
        if (fresh.qwenToken.isBlank()) {
            return sendJson(
                res, 400,
                mapOf(
                    "error" to mapOf(
                        "message" to "请先在应用内配置 Qwen token",
                        "type" to "invalid_request_error",
                        "code" to "no_token",
                    ),
                ),
            )
        }

        val responseFormat = Json.str(body, "response_format", "url").lowercase()
        val wantB64 = responseFormat == "b64_json"
        // 归一化必须在**网关层**完成，而不是只靠客户端内部再做一次：
        // 上游只认带版本号的 id 和比例写法，而调用方习惯传 `qwen-image` /
        // `1024x1024`。放在这里才能让日志、响应回显、错误信息都反映真实请求值，
        // 否则用户看到 `qwen` 字段回显的是自己传入的别名，无法定位上游拒绝原因。
        val reqModel = QwenImageClient.normalizeModel(Json.str(body, "model"))
        val reqSize = QwenImageClient.normalizeSize(
            Json.str(body, "size").ifEmpty { Json.str(body, "aspect_ratio") },
            reqModel,
        )
        val imageReq = ImageRequest(
            prompt = prompt,
            model = reqModel,
            size = reqSize,
            negativePrompt = Json.str(body, "negative_prompt"),
            n = Json.int(body, "n", 1).coerceIn(1, 4),
            b64Only = wantB64,
        )

        val client = newImageClient(fresh)
        try {
            val result = withContext(Dispatchers.IO) { client.generateImage(imageReq) }
            val data = buildImageData(client, result, prompt, wantB64)
            val payload = LinkedHashMap<String, Any?>()
            payload["created"] = Util.nowSec()
            payload["data"] = data
            // 网关扩展字段：不放标准位，只作为排查线索
            payload["qwen"] = mapOf(
                "model" to result.model,
                "size" to result.size,
                "caption" to result.caption,
                "downloaded_b64" to if (wantB64) {
                    data.count { (it["b64_json"] as? String).orEmpty().isNotEmpty() }
                } else {
                    null
                },
            )
            logReq(req, 200, System.currentTimeMillis() - t0, "img ${result.images.size}x ${result.model}")
            sendJson(res, 200, payload)
        } catch (e: Exception) {
            val err = if (e is QwenException) e else {
                val (c, m, s) = Util.normalizeError(e); QwenException(c, m, s)
            }
            logReq(req, err.status, System.currentTimeMillis() - t0, "[${err.code}]")
            sendError(res, err)
        }
    }

    // ---------------- /v1/images/edits（图生图） ----------------

    /**
     * 图生图能力自描述（`GET /v1/images/edits`）。
     *
     * 与 [handleImageModels] 分开而不是共用一个 handler + 参数：
     * 两者返回的**字段集不同**（edits 要说清「源图怎么传」「最多几张」），
     * 合起来以后必然要在一堆 if 里按路径分叉，不如各自摊平。
     */
    private suspend fun handleImageEditModels(req: HttpRequest, res: HttpResponse, cfg: GatewayConfig) {
        GatewayState.reqCount++
        if (!checkAuth(req, cfg)) return apiKeyError(res)
        sendJson(
            res, 200,
            mapOf(
                "object" to "list",
                "supported" to true,
                "endpoint" to "/v1/images/edits",
                "data" to QwenImageClient.IMAGE_MODELS.mapIndexed { i, id ->
                    mapOf(
                        "id" to id,
                        "object" to "model",
                        "created" to GatewayState.startTime / 1000,
                        "owned_by" to "qwen",
                        "index" to i,
                        "default" to (id == QwenImageClient.DEFAULT_IMAGE_MODEL),
                    )
                },
                "sizes" to QwenImageClient.ALLOWED_SIZES,
                "max_n" to 4,
                "max_images" to MAX_EDIT_SOURCES,
                "max_image_bytes" to MAX_EDIT_SOURCE_BYTES,
                "accepts" to listOf(
                    "multipart/form-data（字段 image / image[] / image_1..N 传文件，prompt 传文本）",
                    "application/json（image 为 data:image/...;base64,xxx 或裸 base64；也支持 images 数组）",
                ),
                "notes" to listOf(
                    "源图会先上传到上游文件服务再作为 files 引用，n>1 时多张产出复用同一批源图",
                    "产出 phase 是 image_edit；网关会剔除 SSE 里回显的源图 URL，避免把输入图当成产出",
                    "上游单轮只产一张图, n>1 时网关串行补请求(每张之间受节流限制)",
                    "源图被判违规时上游返回空结果，此时 code=UPSTREAM_EMPTY（重试无效，换图或改提示词）",
                ),
            ),
        )
    }

    /**
     * 图生图主入口（OpenAI `POST /v1/images/edits` 语义）。
     *
     * 【为什么要同时吃 multipart 与 JSON】
     * OpenAI 官方是 multipart（`image` 字段传文件流），但实际调用图生图的常常是
     * 已经拿到 base64 的中转/脚本（把本地图读成 b64 再 POST）。只支持一种就得让
     * 调用方自己转换，而转换本身正是最容易出错（少填 padding、错用 URL-safe 表）的一步。
     * 代价只是多十几行解析，换来的是「两种习惯都能直接跑」。
     *
     * 【为什么 `image` 字段名要接受这么多变体】
     * `image`（官方）、`image[]`（PHP/表单数组习惯）、`image_1/image_2`（多图显式编号）、
     * `mask` 不算 —— 上游没有独立的 inpaint 蒙版通道，静默把 mask 当源图会造成
     * 「结果完全不对但接口报成功」这类最难排查的故障，因此明确拒绝。
     */
    private suspend fun handleImageEdit(req: HttpRequest, res: HttpResponse, cfg: GatewayConfig) {
        GatewayState.reqCount++
        if (!checkAuth(req, cfg)) return apiKeyError(res)
        return try {
            doImageEdit(req, res, cfg)
        } catch (e: QwenException) {
            // 入参校验抛出来的 QwenException（非法 base64 / 传了 http 链接 / 非图片字节…）
            // 都属于**调用方错误**，必须回 4xx。
            // 不包这一层的话，它们会一路冒到 handle() 最外层的兜底 catch，
            // 被 normalizeError 当未知异常处理成 500，调用方就会去翻网关日志找"服务故障"，
            // 而真相只是自己 body 写错了。
            if (e.status in 400..499) sendError(res, e) else throw e
        }
    }

    private suspend fun doImageEdit(req: HttpRequest, res: HttpResponse, cfg: GatewayConfig) {
        val ct = req.header("content-type").orEmpty()
        val isMultipart = ct.contains("multipart/form-data", ignoreCase = true)

        var prompt = ""
        var model = ""
        var size = ""
        var negativePrompt = ""
        var n = 1
        // 默认回 url，与 OpenAI / generations 一致：显式传 response_format=b64_json 才回 b64。
        // 反过来（图生图默认 b64）看着"贴心"，实际是让响应体积凭空膨胀几十倍，
        // 而多数调用方会自己把 url 取走，等于白付一次 base64 编码与传输成本。
        var wantB64 = false
        val sources = ArrayList<SourceImage>()
        val droppedMask = ArrayList<String>()

        if (isMultipart) {
            val parts = MultipartParser.parse(req.body, ct)
                ?: return badRequest(
                    res,
                    "无法解析 multipart/form-data 请求体（缺少 boundary 或请求被截断）",
                )
            for (p in parts) {
                val text = if (p.filename.isEmpty()) {
                    p.data.toString(Charsets.UTF_8)
                } else {
                    ""
                }
                when {
                    // 有 filename 的一律当文件候选；字段名再按下面的规则筛
                    p.filename.isNotEmpty() -> {
                        if (isEditImageField(p.name)) {
                            sources.add(
                                SourceImage(
                                    bytes = p.data,
                                    filename = p.filename.ifEmpty { "source.png" },
                                    contentType = p.contentType.ifEmpty {
                                        guessImageMime(p.filename, p.data)
                                    },
                                ),
                            )
                        } else if (p.name == "mask") {
                            droppedMask.add(p.filename)
                        }
                    }
                    p.name == "prompt" -> prompt = text.trim()
                    p.name == "model" -> model = text.trim()
                    p.name == "size" || p.name == "aspect_ratio" -> size = text.trim()
                    p.name == "negative_prompt" -> negativePrompt = text.trim()
                    p.name == "n" -> n = text.trim().toIntOrNull() ?: 1
                    p.name == "response_format" -> wantB64 = text.trim().lowercase() != "url"
                }
            }
        } else {
            val body = Json.parse(req.bodyText())
                ?: return badRequest(res, "invalid JSON body")
            prompt = Json.str(body, "prompt").ifEmpty { Json.str(body, "input") }.trim()
            model = Json.str(body, "model").trim()
            size = Json.str(body, "size").ifEmpty { Json.str(body, "aspect_ratio") }.trim()
            negativePrompt = Json.str(body, "negative_prompt")
            n = Json.int(body, "n", 1)
            wantB64 = Json.str(body, "response_format", "url").lowercase() == "b64_json"

            // 源图收集过程中会抛 QwenException（非法 base64 / 传了 http 链接等），
            // 这些是**调用方参数错误**，必须映射成 400 而不是让外层兜底成 500 ——
            // 500 会让调用方以为网关炸了，去翻网关日志，而真相只是入参写错。
            try {
                // 单图 `image`（字符串或对象）与多图 `images`（数组）都要吃下
                collectJsonImages(body.opt("image"), sources, droppedMask, 0)
                val arr = Json.arr(body, "images")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        collectJsonImages(arr.opt(i), sources, droppedMask, i + 1)
                    }
                }
            } catch (e: QwenException) {
                return badRequest(res, e.message)
            }
        }

        if (prompt.isBlank()) {
            return badRequest(res, "prompt is required（图生图必须说明要怎么改）")
        }
        if (sources.isEmpty()) {
            return badRequest(
                res,
                "缺少源图：multipart 用字段 image（或 image[]/image_1..N），" +
                    "JSON 用 image（字符串 / {data|b64|url}）或 images 数组",
            )
        }
        if (sources.size > MAX_EDIT_SOURCES) {
            return badRequest(res, "源图最多 $MAX_EDIT_SOURCES 张（收到 ${sources.size} 张）")
        }
        for (s in sources) {
            if (s.bytes.isEmpty()) return badRequest(res, "源图 ${s.filename} 内容为空")
            if (s.bytes.size > MAX_EDIT_SOURCE_BYTES) {
                return badRequest(
                    res,
                    "源图 ${s.filename} 为 ${s.bytes.size / 1024}KB，超过 " +
                        "${MAX_EDIT_SOURCE_BYTES / (1024 * 1024)}MB 上限",
                )
            }
        }
        // 逐张确认确实是图片。上游对非图片源图只会回一个含糊的失败，
        // 定位成本极高（要重新抓包比字节），所以能在网关拦下就拦下。
        for (s in sources) {
            if (!looksLikeImage(s.bytes)) {
                return badRequest(
                    res,
                    "源图 ${s.filename} 不是可识别的图片格式（PNG/JPEG/WebP/GIF/BMP）；" +
                        "若已压缩过请确认是图片字节而不是 base64 文本。",
                )
            }
        }

        val t0 = System.currentTimeMillis()
        val fresh = configRepo.load()
        if (fresh.qwenToken.isBlank()) {
            return sendJson(
                res, 400,
                mapOf(
                    "error" to mapOf(
                        "message" to "请先在应用内配置 Qwen token",
                        "type" to "invalid_request_error",
                        "code" to "no_token",
                    ),
                ),
            )
        }

        val reqModel = QwenImageClient.normalizeModel(model)
        val reqSize = QwenImageClient.normalizeSize(size, reqModel)
        val editReq = ImageEditRequest(
            prompt = prompt,
            model = reqModel,
            sources = sources,
            size = reqSize,
            negativePrompt = negativePrompt,
            n = n.coerceIn(1, 4),
            b64Only = wantB64,
        )

        val client = newImageClient(fresh)
        try {
            val result = withContext(Dispatchers.IO) { client.generateImageEdit(editReq) }
            val data = buildImageData(client, result, prompt, wantB64)
            val payload = LinkedHashMap<String, Any?>()
            payload["created"] = Util.nowSec()
            payload["data"] = data
            payload["qwen"] = LinkedHashMap<String, Any?>().apply {
                put("model", result.model)
                put("size", result.size)
                put("caption", result.caption)
                put("source_count", sources.size)
                put("retries", result.retries)
                put("downloaded_b64", if (wantB64) {
                    data.count { (it["b64_json"] as? String).orEmpty().isNotEmpty() }
                } else {
                    null
                })
                // mask 被忽略时必须说出来。静默忽略会让调用方以为做了局部重绘，
                // 而结果其实是整图重画 —— 这类"成功但不对"最难定位。
                if (droppedMask.isNotEmpty()) {
                    put("ignored_mask", droppedMask)
                    put(
                        "ignored_mask_hint",
                        "上游图生图没有独立蒙版通道，mask 已忽略（整图重绘）；" +
                            "需要局部修改请直接在图里圈出区域或用提示词描述",
                    )
                }
            }
            logReq(
                req, 200, System.currentTimeMillis() - t0,
                "edit ${result.images.size}x ${result.model} src=${sources.size}",
            )
            sendJson(res, 200, payload)
        } catch (e: Exception) {
            val err = if (e is QwenException) e else {
                val (c, m, s) = Util.normalizeError(e); QwenException(c, m, s)
            }
            logReq(req, err.status, System.currentTimeMillis() - t0, "[${err.code}]")
            sendError(res, err)
        }
    }

    /** multipart 里哪些字段名算「源图」 */
    private fun isEditImageField(name: String): Boolean {
        val n = name.trim().lowercase()
        if (n == "image" || n == "images" || n == "image[]" || n == "images[]") return true
        // image_1 / image_2 / image1 / image2
        return Regex("^images?[_-]?\\d+$").matches(n)
    }

    /**
     * 收集 JSON 形态的源图。
     *
     * 支持三种值：字符串（裸 b64 或 data URI）、对象（data/b64/url 三个键）、
     * 以及对象里的 file_id（本网关 /v1/files 上传后拿到的 id）。
     */
    private fun collectJsonImages(
        value: Any?,
        out: MutableList<SourceImage>,
        droppedMask: MutableList<String>,
        idx: Int,
    ) {
        when (value) {
            is String -> {
                val v = value.trim()
                if (v.isEmpty()) return
                if (v.startsWith("data:")) {
                    val m = Regex("^data:([^;,]+)?(;base64)?,(.*)$", RegexOption.DOT_MATCHES_ALL)
                        .find(v)
                        ?: throw QwenException("BAD_REQUEST", "image 的 data URI 格式非法（应形如 data:image/png;base64,...）", 400)
                    val mime = m.groupValues[1].ifEmpty { "image/png" }
                    val isB64 = m.groupValues[2].isNotEmpty()
                    val rawPart = m.groupValues[3]
                    // 非 base64 的 data URI（内联文本）在这里没有意义：
                    // 上游要的是图片字节，把任意文本当图片传只会换来一句含糊的上游错误。
                    if (!isB64) {
                        throw QwenException(
                            "BAD_REQUEST",
                            "image 的 data URI 必须是 base64 编码（缺 ;base64）",
                            400,
                        )
                    }
                    val bytes = decodeImageBase64(rawPart)
                    out.add(SourceImage(bytes, "source${idx}.png", mime))
                } else if (v.startsWith("http://") || v.startsWith("https://")) {
                    // 不在这里下载：交给上层会引入超时/重定向/大小限制一整套逻辑，
                    // 而附件模块已经有实现。JSON 里直接给 URL 属于误用，明确报错更好。
                    throw QwenException(
                        "BAD_REQUEST",
                        "JSON 里的 image 不支持 http(s) 链接，请改传 data URI / base64，或先用 /v1/files 上传再传 file_id",
                        400,
                    )
                } else {
                    val bytes = decodeImageBase64(v)
                    out.add(SourceImage(bytes, "source${idx}.png", guessImageMime("", bytes)))
                }
            }
            is JSONObject -> {
                if (value.has("mask")) droppedMask.add("mask")
                val data = Json.str(value, "data").ifEmpty { Json.str(value, "b64") }
                if (data.isNotEmpty()) {
                    collectJsonImages(data, out, droppedMask, idx)
                    return
                }
                val url = Json.str(value, "url")
                if (url.isNotEmpty()) {
                    collectJsonImages(url, out, droppedMask, idx)
                    return
                }
                val fid = Json.str(value, "file_id").ifEmpty { Json.str(value, "fileId") }
                if (fid.isNotEmpty()) {
                    throw QwenException(
                        "BAD_REQUEST",
                        "JSON 里的 file_id 形态暂不支持（file_id=$fid），请改传 base64 / data URI",
                        400,
                    )
                }
            }
            else -> Unit
        }
    }

    /**
     * 解码 JSON 里传进来的图片 base64，并做「结果像不像一张图」的校验。
     *
     * 【为什么不能直接用 [B64.decode]】
     * 那个实现为了兼容 URL-safe / MIME 换行等形态，会在严格解码失败后回退到
     * `getMimeDecoder()`，而 MIME 解码器**静默丢弃非法字符**：
     * `不是base64!!!` 会被解成一段乱码字节而不是报错。后果是源图上传到上游、
     * 上游返回一个含糊的"处理失败"，而真正的原因（调用方传了非 base64）被完全掩盖。
     *
     * 因此这里先用严格解码器判定合法性，失败才在**保留兼容性**的前提下放宽，
     * 并额外用魔数确认解出来确实是图片。
     */
    private fun decodeImageBase64(raw: String): ByteArray {
        val text = raw.trim().ifEmpty {
            throw QwenException("BAD_REQUEST", "image 内容为空", 400)
        }
        val bytes = try {
            java.util.Base64.getDecoder().decode(text)
        } catch (strict: Exception) {
            try {
                // 兼容 URL-safe 与带换行的 MIME 变体（真实客户端会这么传）
                java.util.Base64.getMimeDecoder().decode(text)
            } catch (relaxed: Exception) {
                throw QwenException(
                    "BAD_REQUEST",
                    "image 不是合法的 base64 / data URI：" + strict.message.orEmpty().take(80),
                    400,
                )
            }
        }
        if (bytes.isEmpty()) {
            throw QwenException("BAD_REQUEST", "image 解码后为空，请检查是否漏传了 base64 主体", 400)
        }
        if (!looksLikeImage(bytes)) {
            throw QwenException(
                "BAD_REQUEST",
                "image 解码后不是可识别的图片格式（PNG/JPEG/WebP/GIF/BMP），" +
                    "请确认传的是图片内容本身而不是经过二次编码的字符串",
                400,
            )
        }
        return bytes
    }

    /** 魔数判定：上游对非图片会给出含糊错误，能在这里拦住就拦住 */
    private fun looksLikeImage(b: ByteArray): Boolean {
        if (b.size < 4) return false
        val png = b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
            b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()
        val jpeg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()
        val gif = b.size >= 6 && String(b, 0, 3, Charsets.ISO_8859_1) == "GIF"
        val bmp = b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte()
        val webp = b.size >= 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
            String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP"
        return png || jpeg || gif || bmp || webp
    }

    /**
     * 按魔数猜图片 MIME。
     *
     * 不信任 filename 后缀：调用方常把 JPEG 存成 .png，而 Content-Type 会一路带到上游。
     * 魔数只在**认定失败**时才回落到默认值。
     */
    private fun guessImageMime(filename: String, bytes: ByteArray): String {
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        ) {
            return "image/png"
        }
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) {
            return "image/jpeg"
        }
        if (bytes.size >= 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
            String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP"
        ) {
            return "image/webp"
        }
        if (bytes.size >= 6 && String(bytes, 0, 6, Charsets.ISO_8859_1).startsWith("GIF8")) {
            return "image/gif"
        }
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            else -> "image/png"
        }
    }

    /** 统一的 400 响应，避免每个校验分支抄一遍同样的 map */    private fun badRequest(res: HttpResponse, message: String): Unit = sendJson(
        res, 400,
        mapOf(
            "error" to mapOf(
                "message" to message,
                "type" to "invalid_request_error",
                "code" to "bad_request",
            ),
        ),
    )

    /**
     * 把 [ImageResult] 摊成 OpenAI `data[]` 数组。
     *
     * 抽出来是因为 generations 与 edits 两份响应的**字段构成完全一致**，
     * 唯一的差别是 edits 的 b64 默认开启。此前 generations 里那段内联逻辑
     * 已经出现过一次「width/height 只在 result.size 非空时补」的修正，
     * 复制一份必然会漏掉后续同样的修正。
     */
    private suspend fun buildImageData(
        client: QwenImageClient,
        result: ImageResult,
        prompt: String,
        wantB64: Boolean,
    ): List<Map<String, Any?>> = result.images.map { img ->
        val m = LinkedHashMap<String, Any?>()
        m["url"] = if (wantB64) null else img.url
        if (wantB64) {
            // 下载兜底：客户端拿到的 img.b64 可能为空（data URI 或实现只填 url）。
            // 静默给空 b64_json 会让调用方拿到一张"看不见的图"。
            val b64 = img.b64.ifEmpty {
                withContext(Dispatchers.IO) { client.downloadAsBase64(img.url) }
            }
            m["b64_json"] = b64
        }
        m["revised_prompt"] = prompt
        if (img.width > 0) m["width"] = img.width
        if (img.height > 0) m["height"] = img.height
        if (result.size.isNotEmpty() && img.width == 0) {
            val (dw, dh) = QwenImageClient.sizeToPixels(result.size)
            m["width"] = dw
            m["height"] = dh
        }
        m
    }

    // ---------------- /v1/chat/completions ----------------

    private suspend fun handleChat(req: HttpRequest, res: HttpResponse, cfg: GatewayConfig) {
        GatewayState.reqCount++
        if (!checkAuth(req, cfg)) return apiKeyError(res)

        val body = Json.parse(req.bodyText())
            ?: return sendJson(
                res, 400,
                mapOf(
                    "error" to mapOf(
                        "message" to "invalid JSON body",
                        "type" to "invalid_request_error",
                        "code" to "bad_request",
                    ),
                ),
            )

        val stream = Json.bool(body, "stream")
        if (stream) return streamChat(req, res, cfg, body)
        return nonStreamChat(req, res, cfg, body)
    }

    private suspend fun streamChat(req: HttpRequest, res: HttpResponse, cfg: GatewayConfig, body: JSONObject) {
        val t0 = System.currentTimeMillis()
        val client = newClient(cfg)
        val messages = parseMessages(body)
        if (messages.isEmpty()) {
            return sendJson(
                res, 400,
                mapOf(
                    "error" to mapOf(
                        "message" to "messages is required",
                        "type" to "invalid_request_error",
                        "code" to "bad_request",
                    ),
                ),
            )
        }
        val model = Json.str(body, "model").ifEmpty { cfg.defaultModel }
        val thinking = if (body.has("thinking")) Json.bool(body, "thinking", true) else cfg.thinking
        val id = "chatcmpl-" + Util.uuid().replace("-", "").take(24)

        // 全局 System Prompt 注入。
        // 必须在 ToolInjector 之前：ToolInjector 会把工具说明追加到「最后一条 user」
        // 末尾，而本步只碰 system 角色。先跑可保证工具说明仍紧贴用户当前问题
        // （对模型最有效的位置），且工具指令不会干扰 system 合并。
        val sysCtx = SystemPromptInjector.prepare(messages, cfg)

        // 方案 B：上游不支持 tools 协议，把工具定义注入成提示词
        val toolCtx = ToolInjector.prepare(messages, body)
        debugLog(
            "stream req: model=$model tools=" + (body.optJSONArray("tools")?.length() ?: 0) +
                " toolCtx=" + toolCtx.enabled + " allowed=" + toolCtx.allowedNames +
                " sysPrompt=" + sysCtx.applied + "/" + sysCtx.mode +
                (if (sysCtx.applied) " (+" + sysCtx.injectedChars + "ch" +
                    (if (sysCtx.replacedChars > 0) ", replaced " + sysCtx.replacedChars + "ch" else "") + ")" else "") +
                " msgs=" + messages.size + " roles=" + messages.map { it.role },
        )

        // 附件解析/上传必须在 SSE 响应头之前完成 —— 失败时才能直接返回 JSON 错误
        var files: List<JSONObject> = emptyList()
        try {
            files = withContext(Dispatchers.IO) {
                AttachmentResolver.resolveSuspend(client, messages, fileStore)
            }
        } catch (e: Exception) {
            val err = AttachmentResolver.humanize(e, files)
            logReq(req, err.status, System.currentTimeMillis() - t0, "[${err.code}]")
            return sendError(res, err)
        }

        res.header("Content-Type", "text/event-stream; charset=utf-8")
        res.header("Cache-Control", "no-cache")
        res.header("X-Accel-Buffering", "no")
        res.writeHead(200)

        // 客户端是否已断开。一旦置位就停止一切写入与重试，
        // 避免向已关闭的 socket 反复写入导致异常。
        var clientGone = false

        fun send(delta: Map<String, Any?>, fr: String?, u: Map<String, Any?>?) {
            // 客户端可能因超时提前断开（尤其重试会让耗时翻倍）。
            // 向已关闭的 socket 写入会抛 IOException，若不吞掉会导致
            // 连接处理线程异常终止，进一步影响服务器稳定性。
            try {
                res.writeChunk("data: " + chunkFrame(id, model, delta, fr, u) + "\n\n")
            } catch (e: Exception) {
                clientGone = true
            }
        }

        // 若请求未指定 model 且配置了默认模型，仍使用 defaultModel
        send(mapOf("role" to "assistant", "content" to ""), null, null)
        var answerLen = 0

        // ---------------------------------------------------------------
        // 上游存在概率性「不按格式输出工具调用」的问题（实测约 1/3 失败）。
        // 失败时表现为：模型把调用意图写成普通文本，或干脆改用内置能力，
        // 对外则变成「客户端发消息后没有任何反应」。
        //
        // 因此这里做**按需重试**：先完整缓冲一轮输出，若判定为
        // 「本应调用工具但没输出」，则丢弃并重发一次（最多 2 次尝试）。
        // 只有在确认成功或重试耗尽后，才真正把内容写给客户端，
        // 避免把失败轮的半截文本泄漏给用户。
        // ---------------------------------------------------------------
        val maxAttempts = if (toolCtx.enabled) 2 else 1
        var finalValid: List<ToolPrompt.ParsedCall> = emptyList()
        var finalText = ""
        var finalUsage: Map<String, Any?>? = null

        // 是否已向客户端推送过内容（thinking/content）。
        // 参考 qwenstudiopp：一旦推过就不再重试，避免重复输出与半截内容。
        var contentEmitted = false

        var attempt = 0
        while (attempt < maxAttempts) {
            attempt++
            val isLastAttempt = attempt >= maxAttempts
            val buffer = if (toolCtx.enabled) com.qwen2api.tx.core.ToolCallStreamBuffer() else null
            val textCollector = StringBuilder()
            var usage: Map<String, Any?>? = null
            // 本轮是否已把内容推给客户端（决定本轮失败能否重试）
            var emittedThisRound = false

            // 重试轮的上游异常单独捕获：失败轮可以重试，
            // 若直接抛出会穿透到外层变成 502，并可能造成响应重复写入。
            val result = try {
                client.chatStream(model, messages, thinking, files) { evt ->
                    when (evt) {
                        is QwenEvent.Thinking -> {
                            // 思维链实时下发（让用户看到「正在思考」，避免空白体验）。
                            // 注意：一旦下发就置 contentEmitted，本轮失败将不再重试。
                            if (!clientGone) {
                                send(mapOf("reasoning_content" to evt.delta), null, null)
                                emittedThisRound = true
                                contentEmitted = true
                            }
                        }
                        is QwenEvent.Content -> {
                            val out = buffer?.feed(evt.delta) ?: evt.delta
                            textCollector.append(out)
                            // 工具注入模式下正文先缓冲（可能是待剥离的调用块），
                            // 因此只有非工具模式才即时下发并锁定重试。
                            if (buffer == null && out.isNotEmpty() && !clientGone) {
                                send(mapOf("content" to out), null, null)
                                emittedThisRound = true
                                contentEmitted = true
                            }
                        }
                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                debugLog("stream attempt=$attempt upstream error: ${e.message}")
                val risk = isRiskControlBlock(e.message)
                debugLog("stream attempt=$attempt riskControl=$risk emitted=$emittedThisRound")
                // 风控类错误：递增等待后重试（不再立即重试，否则加剧封禁）
                if (risk && !contentEmitted && !isLastAttempt) {
                    val waitMs = THROTTLE_WAIT_STEPS_MS[
                        attempt.coerceAtMost(THROTTLE_WAIT_STEPS_MS.size - 1),
                    ]
                    debugLog("stream throttle wait ${waitMs}ms then retry (${attempt + 1}/$maxAttempts)")
                    // 等待期间发 SSE 注释行保活，避免客户端超时断连
                    keepAlive(res, waitMs) { clientGone }
                    if (clientGone) throw e
                    continue
                }
                // 非最后一轮且未吐内容：静默重试一次（应对模型格式跑偏）
                if (!isLastAttempt && !contentEmitted) continue
                throw e
            }
            val tail = buffer?.flushRemaining().orEmpty()
            textCollector.append(tail)
            if (buffer == null && textCollector.isEmpty() && result.answer.isNotEmpty()) {
                textCollector.append(result.answer)
            }
            usage = usageOf(result.usage)

            val parsed = buffer?.calls.orEmpty()
            val valid = ToolInjector.filterValid(parsed, toolCtx)

            debugLog(
                "stream attempt=$attempt/${maxAttempts} parsed=${parsed.size} " +
                    "valid=${valid.size} textLen=${textCollector.length} calls=${valid.map { it.name }}",
            )

            finalValid = valid
            finalText = textCollector.toString()
            finalUsage = usage

            if (valid.isNotEmpty()) break
            // 没注入工具，或模型确实无需工具（正常回答），都不重试
            if (!toolCtx.enabled) break
            // 启发式判断：正文里若出现「工具/调用/tool_call」等字样，
            // 说明模型想调工具但格式跑偏了，值得重试一次
            val looksLikeMissedCall = ToolPrompt.looksLikeMissedToolCall(finalText)
            debugLog("stream attempt=$attempt missedCall=$looksLikeMissedCall")
            if (!looksLikeMissedCall) break
            // 客户端已断开则不再重试（否则白白多打一次上游）
            if (clientGone) {
                debugLog("stream attempt=$attempt client gone, stop retry")
                break
            }
            if (attempt < maxAttempts) {
                debugLog("stream retrying (attempt ${attempt + 1})")
            }
        }

        try {
            // 最终轮的结果写回客户端
            if (finalText.isNotEmpty()) {
                answerLen += finalText.length
                send(mapOf("content" to finalText), null, null)
            }
            val valid = finalValid
            if (valid.isNotEmpty()) {
                // 关键：即使客户端不处理 tool_calls，也要让用户看到反馈。
                // 有些客户端（如部分内置 Agent）不解析 tool_calls，
                // 若此时 content 为空，用户会看到「消息发出去了但没有任何回应」。
                // 因此这里补一句自然语言说明，既不违反 OpenAI 规范，
                // 又保证任何客户端都有可见输出。
                if (answerLen == 0) {
                    send(mapOf("content" to buildToolCallNotice(valid)), null, null)
                    answerLen += 1
                }
                // 转成标准 tool_calls 分片：先发 id/name/空 arguments，再分段发 arguments，
                // 这样既符合 OpenAI 协议，也让增量客户端能正常累积。
                valid.forEachIndexed { idx, call ->
                    val toolCallsDelta = JSONArray().put(
                        JSONObject()
                            .put("index", idx)
                            .put("id", com.qwen2api.tx.core.ToolPrompt.newToolCallId())
                            .put("type", "function")
                            .put("function", JSONObject().put("name", call.name).put("arguments", "")),
                    )
                    send(mapOf("tool_calls" to toolCallsDelta), null, null)
                    // arguments 分片下发（按 256 字符切，模拟真实续传节奏）
                    val args = call.argumentsJson
                    var p = 0
                    while (p < args.length) {
                        val end = minOf(p + 256, args.length)
                        val piece = args.substring(p, end)
                        val d = JSONArray().put(
                            JSONObject()
                                .put("index", idx)
                                .put("function", JSONObject().put("arguments", piece)),
                        )
                        send(mapOf("tool_calls" to d), null, null)
                        p = end
                    }
                }
                send(emptyMap(), "tool_calls", finalUsage)
            } else {
                send(emptyMap(), "stop", finalUsage)
            }
        } catch (e: Exception) {
            val err = AttachmentResolver.humanize(e, files)
            res.writeChunk(
                "data: " + JSONObject()
                    .put(
                        "error",
                        JSONObject()
                            .put("message", err.message)
                            .put("type", "api_error")
                            .put("code", err.code)
                            .put("status", err.status),
                    ) + "\n\n",
            )
            res.writeChunk("data: [DONE]\n\n")
            res.end()
            logReq(req, err.status, System.currentTimeMillis() - t0, "[${err.code}]")
            return
        }
        res.writeChunk("data: [DONE]\n\n")
        res.end()
        logReq(req, 200, System.currentTimeMillis() - t0, "${answerLen}ch")
    }

    private suspend fun nonStreamChat(req: HttpRequest, res: HttpResponse, cfg: GatewayConfig, body: JSONObject) {
        val t1 = System.currentTimeMillis()
        val client = newClient(cfg)
        val messages = parseMessages(body)
        if (messages.isEmpty()) {
            return sendJson(
                res, 400,
                mapOf(
                    "error" to mapOf(
                        "message" to "messages is required",
                        "type" to "invalid_request_error",
                        "code" to "bad_request",
                    ),
                ),
            )
        }
        val model = Json.str(body, "model").ifEmpty { cfg.defaultModel }
        val thinking = if (body.has("thinking")) Json.bool(body, "thinking", true) else cfg.thinking

        // 方案 B：非流式下先把工具定义注入成提示词
        // 顺序同 streamChat：全局 System Prompt 先注入（只碰 system 角色），
        // 再由 ToolInjector 追加工具说明到最后一条 user 消息末尾。
        val sysCtx = SystemPromptInjector.prepare(messages, cfg)
        val toolCtx = ToolInjector.prepare(messages, body)
        debugLog(
            "nonstream req: model=$model tools=" + (body.optJSONArray("tools")?.length() ?: 0) +
                " sysPrompt=" + sysCtx.applied + "/" + sysCtx.mode,
        )

        var files: List<JSONObject> = emptyList()
        val result = try {
            withContext(Dispatchers.IO) {
                files = AttachmentResolver.resolveSuspend(client, messages, fileStore)
                client.chatStream(model, messages, thinking, files, null)
            }
        } catch (e: Exception) {
            val err = AttachmentResolver.humanize(e, files)
            logReq(req, err.status, System.currentTimeMillis() - t1, "[${err.code}]")
            return sendError(res, err)
        }

        // 解析模型输出里的工具调用（若有），并把调用块从正文中剥离
        val parsed = if (toolCtx.enabled) {
            val p = com.qwen2api.tx.core.ToolPrompt.parse(result.answer)
            ToolInjector.filterValid(p.calls, toolCtx) to p.remainingText
        } else {
            emptyList<com.qwen2api.tx.core.ToolPrompt.ParsedCall>() to result.answer
        }
        val toolCalls = parsed.first
        val answerText = parsed.second

        val msg = JSONObject().put("role", "assistant").put("content", answerText)
        if (result.thinking.isNotEmpty()) msg.put("reasoning_content", result.thinking)

        val finishReason: String
        if (toolCalls.isNotEmpty()) {
            msg.put("tool_calls", ToolInjector.toToolCalls(toolCalls))
            // 若模型没有输出正文，补一句说明 —— 见流式分支里的同款处理。
            // 这保证「不解析 tool_calls 的客户端」也能看到可见反馈。
            if (answerText.isBlank()) {
                msg.put("content", buildToolCallNotice(toolCalls))
            }
            finishReason = "tool_calls"
        } else {
            finishReason = "stop"
        }

        logReq(req, 200, System.currentTimeMillis() - t1, "${answerText.length}ch")
        sendJson(
            res, 200,
            linkedMapOf(
                "id" to ("chatcmpl-" + Util.uuid().replace("-", "").take(24)),
                "object" to "chat.completion",
                "created" to Util.nowSec(),
                "model" to result.model,
                "system_fingerprint" to ("qwen2api-tx/" + GatewayConfig.VERSION),
                "choices" to listOf(
                    linkedMapOf(
                        "index" to 0,
                        "message" to Json.toMap(msg),
                        "finish_reason" to finishReason,
                    ),
                ),
                "usage" to usageOf(result.usage),
            ),
        )
    }

    // ---------------- /v1/files ----------------

    private suspend fun handleFileUpload(req: HttpRequest, res: HttpResponse, cfg: GatewayConfig) {
        GatewayState.reqCount++
        if (!checkAuth(req, cfg)) return apiKeyError(res)
        val fresh = configRepo.load()
        if (fresh.qwenToken.isBlank()) {
            return sendJson(
                res, 400,
                mapOf(
                    "error" to mapOf(
                        "message" to "请先在应用内配置 Qwen token",
                        "type" to "invalid_request_error",
                        "code" to "no_token",
                    ),
                ),
            )
        }
        val parts = MultipartParser.parse(req.body, req.header("content-type"))
            ?: return sendJson(
                res, 400,
                mapOf(
                    "error" to mapOf(
                        "message" to "需要 multipart/form-data 请求体 (字段 file)",
                        "type" to "invalid_request_error",
                        "code" to "bad_request",
                    ),
                ),
            )
        val filePart = parts.firstOrNull { it.filename.isNotEmpty() }
            ?: parts.firstOrNull { it.name == "file" }
        if (filePart == null || filePart.data.isEmpty()) {
            return sendJson(
                res, 400,
                mapOf(
                    "error" to mapOf(
                        "message" to "缺少文件字段 (file)",
                        "type" to "invalid_request_error",
                        "code" to "bad_request",
                    ),
                ),
            )
        }
        val purpose = parts.firstOrNull { it.name == "purpose" }
            ?.data?.toString(Charsets.UTF_8)?.take(60) ?: "assistants"

        val client = newClient(fresh)
        val guessedKind = QwenClient.mimeToKind(filePart.contentType, filePart.filename)
        val contentType = filePart.contentType.ifEmpty {
            when (guessedKind) {
                AttachmentKind.IMAGE -> "image/png"
                AttachmentKind.VIDEO -> "video/mp4"
                AttachmentKind.AUDIO -> "audio/wav"
                else -> "application/octet-stream"
            }
        }
        return try {
            val t0 = System.currentTimeMillis()
            val up = withContext(Dispatchers.IO) {
                client.uploadFile(filePart.data, filePart.filename, contentType)
            }
            val rec = FileRecord(
                id = "file-" + Util.randomBase64Url(12),
                qwenId = up.id,
                url = up.url,
                filename = up.name,
                bytes = up.size,
                mime = up.mime,
                kind = up.kind.name.lowercase(),
                createdAt = Util.nowSec(),
                purpose = purpose,
                entry = up.entry,
            )
            fileStore.add(rec)
            logReq(req, 200, System.currentTimeMillis() - t0, "${rec.kind} ${"%.1f".format(up.size / 1024.0)}KB")
            sendJson(res, 200, Json.toMap(rec.toOpenAi()))
        } catch (e: Exception) {
            val err = if (e is QwenException) e else {
                val (c, m, s) = Util.normalizeError(e); QwenException(c, m, s)
            }
            logReq(req, err.status, 0, "[${err.code}]")
            sendError(res, err)
        }
    }

    private fun handleFileList(req: HttpRequest, res: HttpResponse, cfg: GatewayConfig) {
        GatewayState.reqCount++
        if (!checkAuth(req, cfg)) return apiKeyError(res)
        val data = fileStore.all().map { Json.toMap(it.toOpenAi()) }
        sendJson(res, 200, mapOf("object" to "list", "data" to data))
    }

    private suspend fun handleFileItem(req: HttpRequest, res: HttpResponse, cfg: GatewayConfig, pathname: String) {
        GatewayState.reqCount++
        if (!checkAuth(req, cfg)) return apiKeyError(res)
        // 从**结构**解析而不是对整条路径做字符串裁剪：
        // 路由用的是编码原样的 path（见 MiniHttpServer.readRequest），
        // 因此 id 的百分号编码会原样带到这里，解码只应作用于**单个路径段**。
        // 裁剪写法（removePrefix + endsWith("/content")）在 id 含特殊字符时
        // 会把编码串直接喂给文件注册表，用户看到"文件明明还在却报未找到"。
        val segs = pathname.split('/').filter { it.isNotEmpty() } // ["v1","files", <id>, "content"?]
        if (segs.size < 3) {
            return sendJson(
                res, 404,
                mapOf("error" to "not found"),
            )
        }
        val isContent = segs.size >= 4 && segs[3] == "content"
        // 多出来的层级（/v1/files/<id>/other）不做猜测，直接 404 ——
        // 静默按前缀匹配会把不存在的形状当成合法请求。
        if (segs.size > 4 || (segs.size == 4 && !isContent)) {
            return sendJson(res, 404, mapOf("error" to "not found"))
        }
        // 路径形状已知但方法不对 -> 405（**不是** 404）。
        // 这条分支必须显式存在：否则 POST /v1/files/<id> 会一路落到下面的
        // "查记录 -> 回详情" 兜底分支上，静默返回 200 + 文件详情 ——
        // 调用方以为自己发起的写操作成功了，实际什么都没发生。
        val method = req.effectiveMethod
        val allowHere = if (isContent) listOf("GET", "HEAD") else listOf("GET", "HEAD", "DELETE")
        if (method != "GET" && method != "DELETE") {
            return methodNotAllowed(res, allowHere)
        }
        if (method == "DELETE" && isContent) {
            return methodNotAllowed(res, allowHere)
        }
        val fid = req.pathParam(2)
            ?: return sendJson(res, 404, mapOf("error" to "not found"))
        val rec = fileStore.find(fid)
            ?: return sendJson(
                res, 404,
                mapOf(
                    "error" to mapOf(
                        "message" to "file 未找到: $fid",
                        "type" to "invalid_request_error",
                        "code" to "file_not_found",
                    ),
                ),
            )

        if (req.effectiveMethod == "GET" && isContent) {
            if (rec.url.isEmpty()) {
                return sendJson(
                    res, 404,
                    mapOf(
                        "error" to mapOf(
                            "message" to "该文件无内容 URL",
                            "type" to "invalid_request_error",
                            "code" to "no_content",
                        ),
                    ),
                )
            }
            res.header("Location", rec.url)
            res.writeHead(302)
            res.end()
            return
        }
        if (req.effectiveMethod == "DELETE") {
            fileStore.remove(rec.id)
            // 尽力删除上游文件（失败不影响本地删除结果）
            try {
                withContext(Dispatchers.IO) { newClient(configRepo.load()).deleteUpstreamFile(rec.qwenId) }
            } catch (e: Exception) {
                // 忽略
            }
            return sendJson(res, 200, mapOf("id" to rec.id, "object" to "file", "deleted" to true))
        }
        sendJson(res, 200, Json.toMap(rec.toOpenAi()))
    }

    // ---------------- 管理接口 ----------------

    private suspend fun handleAdmin(req: HttpRequest, res: HttpResponse, cfg: GatewayConfig, pathname: String) {
        if (!adminHostOk(req)) {
            return sendJson(res, 403, mapOf("error" to "admin 仅允许本机访问"))
        }
        val method = req.effectiveMethod
        val body = if (method == "POST") Json.parse(req.bodyText()) ?: JSONObject() else JSONObject()

        when {
            method == "GET" && pathname == "/admin/api/status" -> {
                val models = GatewayState.modelsCache
                    ?: QwenClient.FALLBACK_MODELS
                sendJson(
                    res, 200,
                    linkedMapOf(
                        "version" to GatewayConfig.VERSION,
                        "uptime" to ((System.currentTimeMillis() - GatewayState.startTime) / 1000),
                        "port" to cfg.port,
                        "host" to cfg.host,
                        "hasToken" to cfg.qwenToken.isNotBlank(),
                        "tokenMask" to ConfigStore.maskToken(cfg.qwenToken),
                        "tokenType" to ConfigStore.detectTokenType(cfg.qwenToken),
                        "apiKey" to cfg.apiKey,
                        "defaultModel" to cfg.defaultModel,
                        "thinking" to cfg.thinking,
                        "throttleMs" to cfg.throttleMs,
                        "autoOpen" to cfg.autoOpen,
                        // 图像重试策略：调用方排查"偶发 502"时需要知道网关到底重试了没有、
                        // 重试几次。不透出的话只能靠猜，而这个值直接影响上游请求量（风控）。
                        "imageRetryCount" to cfg.imageRetryCount,
                        "imageRetryBackoffMs" to cfg.imageRetryBackoffMs,
                        // 全局 System Prompt：只回长度与开关，**不回正文**。
                        // 原因：/admin/api/status 是免鉴权（仅限本机）的只读接口，
                        // 常被脚本/浏览器插件轮询；提示词里可能写有私有业务规则，
                        // 正文回出去等于多一个泄漏面。要看/改正文走 settings 接口。
                        "systemPromptEnabled" to cfg.systemPromptEnabled,
                        "systemPromptMode" to cfg.systemPromptMode,
                        "systemPromptChars" to cfg.systemPrompt.length,
                        "qwenOk" to GatewayState.qwenOk,
                        "qwenCheckAt" to GatewayState.qwenCheckAt,
                        "models" to models.map { linkedMapOf("id" to it.id, "name" to it.name) },
                        "reqCount" to GatewayState.reqCount,
                        // 存储加密是否降级。
                        // SecretVault.lastDegraded 此前只被写入、**没有任何消费者**，
                        // 于是"apiKey/qwenToken 正在以明文落盘"这件事实对用户完全不可见。
                        // 接进管理接口后，UI 与外部脚本都能读到。
                        "vaultDegraded" to StorageCrypto.isDegraded(),
                        "vaultDegradedHint" to if (StorageCrypto.isDegraded()) {
                            "本机 Keystore 不可用，敏感配置以明文存储（功能不受影响，但 adb backup / root 可直读）"
                        } else {
                            ""
                        },
                    ),
                )
            }

            method == "POST" && pathname == "/admin/api/token" -> {
                val san = ConfigStore.sanitizeQwenToken(Json.strOrNull(body, "token"))
                if (!san.ok) return sendJson(res, 400, mapOf("ok" to false, "message" to san.message))
                val testClient = QwenClient(san.token, 0)
                try {
                    val models = withContext(Dispatchers.IO) { testClient.listModels() }
                    GatewayState.modelsCache = models
                    GatewayState.modelsCacheAt = System.currentTimeMillis()
                    GatewayState.qwenOk = true
                    GatewayState.qwenCheckAt = System.currentTimeMillis()
                    configRepo.update { it.copyWith(qwenToken = san.token) }
                    val note = if (san.note.isNotEmpty()) "。${san.note}" else ""
                    sendJson(
                        res, 200,
                        mapOf(
                            "ok" to true,
                            "message" to "验证通过, 已保存 (${models.size} 个模型可用)$note",
                            "models" to models.map { it.id },
                        ),
                    )
                } catch (e: Exception) {
                    GatewayState.qwenOk = false
                    GatewayState.qwenCheckAt = System.currentTimeMillis()
                    sendJson(res, 200, mapOf("ok" to false, "message" to (e.message ?: "验证失败")))
                }
            }

            method == "POST" && pathname == "/admin/api/check" -> {
                val fresh = configRepo.load()
                if (fresh.qwenToken.isBlank()) {
                    return sendJson(res, 200, mapOf("ok" to false, "message" to "未配置 token"))
                }
                try {
                    val models = withContext(Dispatchers.IO) { newClient(fresh).listModels() }
                    GatewayState.modelsCache = models
                    GatewayState.qwenOk = true
                    GatewayState.qwenCheckAt = System.currentTimeMillis()
                    sendJson(
                        res, 200,
                        mapOf(
                            "ok" to true,
                            "message" to "连接正常 (${models.size} 个模型)",
                            "models" to models.map { it.id },
                        ),
                    )
                } catch (e: Exception) {
                    GatewayState.qwenOk = false
                    GatewayState.qwenCheckAt = System.currentTimeMillis()
                    sendJson(res, 200, mapOf("ok" to false, "message" to (e.message ?: "检测失败")))
                }
            }

            method == "POST" && pathname == "/admin/api/key/regenerate" -> {
                val newKey = ConfigStore.generateApiKey()
                configRepo.update { it.copyWith(apiKey = newKey) }
                sendJson(res, 200, mapOf("ok" to true, "apiKey" to newKey))
            }

            method == "POST" && pathname == "/admin/api/settings" -> {
                var restart = false
                configRepo.update { cur ->
                    var next = cur
                    Json.strOrNull(body, "defaultModel")?.takeIf { it.isNotEmpty() }?.let {
                        next = next.copyWith(defaultModel = it)
                    }
                    body.opt("thinking")?.let { if (it is Boolean) next = next.copyWith(thinking = it) }
                    body.opt("autoOpen")?.let { if (it is Boolean) next = next.copyWith(autoOpen = it) }
                    body.opt("throttleMs")?.let {
                        val v = Json.dbl(body, "throttleMs", -1.0)
                        if (v >= 0) next = next.copyWith(throttleMs = v.toInt().coerceIn(0, 30000))
                    }
                    body.opt("port")?.let {
                        val p = Json.int(body, "port", cur.port).coerceIn(1, 65535)
                        if (p != cur.port) restart = true
                        next = next.copyWith(port = p)
                    }
                    body.opt("imageRetryCount")?.let {
                        val v = Json.int(body, "imageRetryCount", cur.imageRetryCount)
                        next = next.copyWith(
                            imageRetryCount = v.coerceIn(0, GatewayConfig.MAX_IMAGE_RETRY),
                        )
                    }
                    body.opt("imageRetryBackoffMs")?.let {
                        val v = Json.int(body, "imageRetryBackoffMs", cur.imageRetryBackoffMs)
                        next = next.copyWith(
                            imageRetryBackoffMs = v.coerceIn(0, QwenImageClient.MAX_RETRY_BACKOFF_MS.toInt()),
                        )
                    }
                    // 全局 System Prompt：文本 / 开关 / 模式三者可独立下发。
                    // 文本单独改成空串是合法操作（= 清空并停用），所以不能用
                    // takeIf { it.isNotEmpty() } 过滤 —— 那样用户就没法在 admin 里删掉提示词。
                    body.opt("systemPrompt")?.let {
                        if (it is String) {
                            val clean = ConfigStore.sanitizeSystemPrompt(it)
                            next = next.copyWith(
                                systemPrompt = clean,
                                // 联动：写入非空文本即视为想启用，省掉一次多余请求；
                                // 显式传 systemPromptEnabled 时以显式值为准（下面会覆盖）。
                                // 清空文本时不动开关，避免"清空输入框"被误解为"关掉功能"。
                                systemPromptEnabled = if (clean.isEmpty()) {
                                    next.systemPromptEnabled
                                } else {
                                    true
                                },
                            )
                        }
                    }
                    body.opt("systemPromptEnabled")?.let {
                        if (it is Boolean) next = next.copyWith(systemPromptEnabled = it)
                    }
                    body.opt("systemPromptMode")?.let {
                        if (it is String) {
                            next = next.copyWith(
                                systemPromptMode = ConfigStore.normalizeSystemPromptMode(it),
                            )
                        }
                    }
                    next
                }
                sendJson(
                    res, 200,
                    mapOf(
                        "ok" to true,
                        "message" to ("设置已保存" + if (restart) " (端口修改需重启服务生效)" else ""),
                        "restart" to restart,
                    ),
                )
            }

            else -> {
                // 路径形状已知但方法不对 -> 405，与"路径不存在"（404）严格区分。
                // 必须放在此处而不是靠外层 when：外层 when 已经按 startsWith("/admin/")
                // 把整个前缀交给本函数，落到这里的唯一原因就是方法不匹配。
                val allow = when (pathname) {
                    "/admin/api/status" -> listOf("GET", "HEAD")
                    "/admin/api/token", "/admin/api/check",
                    "/admin/api/settings", "/admin/api/key/regenerate",
                    -> listOf("POST")
                    else -> null
                }
                if (allow != null) methodNotAllowed(res, allow)
                else sendJson(res, 404, mapOf("error" to "not found"))
            }
        }
    }

    // ---------------- 日志 ----------------

    /**
     * 工具调用时附带的自然语言说明。
     *
     * 有些客户端不解析 tool_calls，若 content 为空会表现为「没有回应」。
     * 这里给出一句可见反馈，同时不违反 OpenAI 规范（content 允许非空）。
     */
    private fun buildToolCallNotice(calls: List<ToolPrompt.ParsedCall>): String {
        val names = calls.map { it.name }
        return if (names.size == 1) {
            "正在调用工具 ${names[0]} …"
        } else {
            "正在调用 ${names.size} 个工具：${names.joinToString("、")} …"
        }
    }

    private fun logReq(req: HttpRequest, status: Int, ms: Long, note: String) {
        val line = "${req.method} ${req.rawTarget} -> $status (${ms}ms)${if (note.isNotEmpty()) " $note" else ""}"
        GatewayState.lastRequestLog = line
        debugLog(line)
    }

    /**
     * 输出诊断日志。
     *
     * 注意：单元测试跑在纯 JVM 上，android.util.Log 未实现会抛异常，
     * 因此这里整体包一层容错 —— 测试环境静默跳过，真机输出到 logcat。
     */
    private fun debugLog(msg: String) {
        // 不依赖 BuildConfig.DEBUG：实测该字段在 debug 构建里可能为 false，
        // 会让全部诊断日志静默，排查时误以为「代码没执行」。
        runCatching { android.util.Log.i("Qwen2API", msg) }
    }

    /**
     * 重试等待期间发送 SSE 注释行心跳，保持连接存活。
     *
     * 为什么需要：风控重试要等待 8~25 秒，若不发任何数据，
     * 客户端（OkHttp/浏览器/各类 SDK）会因读超时而断开，
     * 表现为「Remote end closed」或界面报错——这正是之前的故障现象。
     * SSE 规范中，以 ':' 开头的行是注释，客户端应忽略，因此安全。
     *
     * @param totalMs 总等待时长
     * @param clientGone 返回 true 表示客户端已断开，应提前中止等待
     */
    private suspend fun keepAlive(
        res: HttpResponse,
        totalMs: Long,
        clientGone: () -> Boolean,
    ) {
        val interval = 2_000L
        var elapsed = 0L
        while (elapsed < totalMs) {
            if (clientGone()) return
            val slice = minOf(interval, totalMs - elapsed)
            kotlinx.coroutines.delay(slice)
            elapsed += slice
            if (clientGone()) return
            try {
                res.writeChunk(": keep-alive\n\n")
            } catch (e: Exception) {
                return
            }
        }
    }

    companion object {
        fun decodeBase64(s: String): ByteArray = B64.decode(s)

        /**
         * 图生图源图张数上限。
         *
         * 4 与 n 的上限同值不是巧合：上游单轮只产一张图，源图越多越容易触发内容安全，
         * 而收益（多图参考）在 4 张之后基本饱和。放开只会让"传 20 张然后全部失败"更容易发生。
         */
        const val MAX_EDIT_SOURCES = 4

        /** 单张源图字节上限（与附件下载上限同量级，但源图是调"改图"用的，不需要 64MB） */
        const val MAX_EDIT_SOURCE_BYTES = 16 * 1024 * 1024

        /**
         * 风控/频率限制错误识别。
         *
         * 参考 qwenstudiopp v1.3.0 的 isThrottleError：
         *  - 覆盖 429 / rate limit / 风控 / 滑块 / baxia(阿里盾) / 稍后 / 频繁 等措辞
         *  - **必须包含 UPSTREAM_EMPTY**：上游被风控掐断时最典型的表现就是
         *    「流正常建立但没有任何内容」，若不识别会导致静默失败（无回应）。
         *  - 明确排除业务类错误（鉴权/参数/文件），避免无意义重试。
         */
        private val THROTTLE_PAT = Regex(
            "429|rate.?limit|too.?many|throttl|风控|频率|滑块|baxia|busy|overload|try.?again|稍后|频繁|" +
                "fail_sys_user_validate|rgv587|punish|x5sec|被挤爆",
            RegexOption.IGNORE_CASE,
        )

        private val NON_RETRYABLE = setOf(
            "AUTH_FAILED", "NO_TOKEN", "BAD_REQUEST", "FILE_TOO_LARGE",
            "PARSE_FAILED", "TOKEN_INVALID_CHARS", "HEADER_INVALID_CHARS", "NETWORK_TIMEOUT",
        )

        fun isRiskControlBlock(msg: String?): Boolean {
            if (msg.isNullOrEmpty()) return false
            if (msg in NON_RETRYABLE) return false
            return THROTTLE_PAT.containsMatchIn(msg)
        }

        /** 带错误码的判定（优先看 code，其次看 message）。 */
        fun isRiskControlBlock(code: String?, msg: String?): Boolean {
            if (!code.isNullOrEmpty()) {
                if (code in NON_RETRYABLE) return false
                if (code == "UPSTREAM_429" || code == "UPSTREAM_EMPTY" ||
                    code == "THROTTLE_RATE_LIMIT"
                ) {
                    return true
                }
                if (THROTTLE_PAT.containsMatchIn(code)) return true
            }
            return isRiskControlBlock(msg)
        }

        /** 折中等待阶梯（毫秒）：8s / 15s / 25s —— 兼顾成功率与响应速度。 */
        val THROTTLE_WAIT_STEPS_MS = longArrayOf(8_000, 15_000, 25_000)

        /** 风控拦截时给用户的友好中文提示（替代原始的处罚页 JSON）。 */
        const val RISK_CONTROL_HINT =
            "上游触发了安全验证/限流（风控）。这通常因短时间内请求过多导致，并非网关故障。" +
                "请稍后重试；若持续出现，请在浏览器打开 chat.qwen.ai 手动过一次滑块验证。"
    }
}
