package com.qwen2api.tx.server

import android.content.Context

import com.qwen2api.tx.BuildConfig
import com.qwen2api.tx.core.AccountRepository
import com.qwen2api.tx.core.AccountRouter
import com.qwen2api.tx.core.AccountStore
import com.qwen2api.tx.core.ApiLogLevel
import com.qwen2api.tx.core.ApiLogStore
import com.qwen2api.tx.core.AttachmentKind
import com.qwen2api.tx.core.B64
import com.qwen2api.tx.core.ChatMessage
import com.qwen2api.tx.core.ConfigRepository
import com.qwen2api.tx.core.ConfigStore
import com.qwen2api.tx.core.CredentialRoute
import com.qwen2api.tx.core.FileRecord
import com.qwen2api.tx.core.FileStore
import com.qwen2api.tx.core.GatewayConfig
import com.qwen2api.tx.core.Json
import com.qwen2api.tx.core.MemoryAccountRepository
import com.qwen2api.tx.core.QwenAccount
import com.qwen2api.tx.core.QwenClient
import com.qwen2api.tx.core.QwenEvent
import com.qwen2api.tx.core.QwenImageClient
import com.qwen2api.tx.core.ImageEditRequest
import com.qwen2api.tx.core.ImageRequest
import com.qwen2api.tx.core.ImageResult
import com.qwen2api.tx.core.SourceImage
import com.qwen2api.tx.core.ToolPrompt
import com.qwen2api.tx.core.QwenException
import com.qwen2api.tx.core.RiskControl
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

    /**
     * 最近一条**图片链路**诊断日志（含请求体诊断与重试过程）。
     *
     * 与 [lastRequestLog] 分开：后者只在请求**结束时**写一行，而图片链路
     * 真正的排查价值在过程中（重试了几轮、body 到底发了什么、源图上传成功没）。
     * 分开后 `/admin/api/status` 能直接给出最近一条过程日志，
     * 不必守着 logcat 复现。
     */
    @Volatile var lastImageLog: String = ""

    /**
     * 最近一次请求的账号路由结果摘要（含尝试次数/切换次数）。
     *
     * 与 [lastRequestLog] 的区别：后者是"哪个 URL 回了什么状态码"，
     * 本字段回答的是"这次为什么慢、有没有换账号"——排查多账号场景时最先要看的东西。
     */
    @Volatile var lastAccountRoute: String = ""

    @Volatile var lastRouteSummary: String = ""
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
    /**
     * 账号仓储（多账号路由）。
     *
     * 默认值为"空账号列表的内存实现"而不是生产实现：构造器里有 `Context?`，
     * 而单测传的是 null（纯 JVM）。默认空列表使"没有账号"成为最自然的初始状态 ——
     * 此时路由退化到只用 `cfg.qwenToken`，与改动前行为一致。
     * 生产构造器（带 context 那个）会注入真正的持久化实现。
     */
    private val accountRepo: AccountRepository = MemoryAccountRepository(),
    /**
     * 调用日志仓储。
     *
     * 同上：默认内存实现便于测试，生产构造器注入落盘实现。
     */
    private val logStore: ApiLogStore = ApiLogStore.inMemory(),
) {
    /** 生产环境构造器：使用 SharedPreferences 持久化 */
    constructor(context: Context) : this(
        context.applicationContext,
        ConfigStore.repository(context),
        FileRegistry(context),
        accountRepo = AccountStore.repository(context),
        logStore = ApiLogStore.forContext(context),
    )

    /**
     * 账号路由：把凭证选择/健康度/冷却收敛到一个对象里。
     *
     * 与 [GatewayRouter] 一起构造（而不是每请求新建）：健康度与冷却本身就是
     * **跨请求**的状态，每次新建就等于每次忘记。
     */
    private val accountRouter = AccountRouter(
        accountRepo,
        configRepo,
        log = { msg -> logRoute(msg) },
    )

    /** UI/管理接口读取账号与日志的入口 */
    fun accountRepository(): AccountRepository = accountRepo

    fun logStoreRef(): ApiLogStore = logStore

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
        pathname == "/admin/api/accounts" -> listOf("GET", "HEAD")
        pathname == "/admin/api/logs" -> listOf("GET", "HEAD")
        pathname == "/admin/api/logs/export" -> listOf("GET", "HEAD")
        // 这几个都是 POST-only；写成 || 串联而不是逗号：本 when 是无主语的
        // 条件式（`when { 条件 -> }`），逗号只在 `when (主语) { 值, 值 -> }` 里合法。
        pathname == "/admin/api/accounts/add" ||
            pathname == "/admin/api/accounts/update" ||
            pathname == "/admin/api/accounts/remove" ||
            pathname == "/admin/api/accounts/reset" ||
            pathname == "/admin/api/logs/clear" -> listOf("POST")
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

    // ---------------- 多账号路由 ----------------

    /**
     * 按当前配置算出本次请求的凭证尝试顺序。
     *
     * 关闭多账号（`cfg.multiAccount == false`）时**退化成单账号**：
     * 只返回默认账号，因此后续所有上下文错误处理行为与改动前完全一致。
     * 这是刻意留的回退开关 —— 多账号路由一旦出问题，用户需要一个
     * "立刻变回老行为"的动作，而不是等修版本。
     */
    private fun credentialPlan(cfg: GatewayConfig): List<CredentialRoute> {
        if (!cfg.multiAccount) {
            return if (cfg.qwenToken.isBlank()) {
                emptyList()
            } else {
                listOf(CredentialRoute("", "默认账号", cfg.qwenToken))
            }
        }
        val all = accountRouter.candidates(cfg)
        // 切换次数上限在这里生效，而不是靠调用方各自 break：
        // 五条链路（models/图片/改图/非流式/流式/上传）都从这一个入口拿计划，
        // 在此截断才能保证"上限"对**所有**链路一致生效。上限语义是"最多切换 N 次"，
        // 即最多尝试 N+1 个凭证。
        val maxAttempts = cfg.maxAccountSwitches + 1
        return if (all.size <= maxAttempts) all else all.subList(0, maxAttempts)
    }

    /**
     * 对话链路的凭证计划。
     *
     * 与图片/文件链路**刻意不同**：后者在改动前就预检 token 并回 400/`no_token`，
     * 而对话链路从不预检 —— 未配置时由 [QwenClient] 抛 `NO_TOKEN`（HTTP 401）。
     * 这个差异被既有 E2E 测试固化（「chat without qwen token reports actionable
     * error」断言 401 + NO_TOKEN），因此这里在无候选时返回一个**空凭证的默认路由**
     * 让客户端照旧抛错，而不是换成 400。顺带一个副作用是好的：流式请求在
     * 未配置凭证时仍能先建立 SSE（否则客户端拿到的是一段 JSON，而不是可解析的
     * 错误帧），与改动前逐字节一致。
     */
    private fun chatCredentialPlan(cfg: GatewayConfig): List<CredentialRoute> {
        val plan = credentialPlan(cfg)
        if (plan.isNotEmpty()) return plan
        return listOf(CredentialRoute("", "默认账号", ""))
    }

    /** 记一次成功：清空该账号的失败记录（退出冷却） */
    private fun noteRouteOk(route: CredentialRoute) {
        try {
            accountRouter.noteOk(route)
        } catch (e: Exception) {
            debugLog("noteRouteOk failed: ${e.message}")
        }
    }

    /** 记一次失败（仅可切换类错误才写冷却，见 [AccountRouter.shouldSwitch]） */
    private fun noteRouteFail(route: CredentialRoute, e: QwenException, switchable: Boolean) {
        try {
            accountRouter.noteFail(route, e, switchable)
        } catch (ex: Exception) {
            debugLog("noteRouteFail failed: ${ex.message}")
        }
    }

    /** 记一次"用过"（写 lastUsedAt，供轮转与 UI 展示） */
    private fun noteRouteUsed(route: CredentialRoute) {
        try {
            accountRouter.noteUsed(route)
        } catch (e: Exception) {
            debugLog("noteRouteUsed failed: ${e.message}")
        }
    }

    /** 无可用凭证时向调用方回一个可读错误（而不是让上游返回 401 让人猜） */
    private fun noCredentialError(res: HttpResponse) {
        sendJson(
            res, 400,
            mapOf(
                "error" to mapOf(
                    "message" to "请先在应用内配置 Qwen token 或添加账号",
                    "type" to "invalid_request_error",
                    "code" to "no_token",
                ),
            ),
        )
    }

    /**
     * 创建文生图客户端并**接上日志出口**。
     *
     * 这两个 sink 此前声明了却全仓零赋值，于是图片链路在 logcat 里一行都没有：
     * 发真实文生图（含 4 次重试）只看到基类打的 `REQ/DELETE /api/v2/chats/new`，
     * `t2i payload: …`、`edit source uploaded: …`、`retryable […]` 全部静默。
     * 排查「上游说 Model not found」时最需要的恰恰是这些行，结果是「像代码没执行」。
     *
     * 接线点放在这里而不是客户端内部：客户端跑在网关进程里，不该依赖
     * android.util.Log（单测会抛 `RuntimeException: Stub!`），日志出口由网关提供。
     * 同时记一份到 [GatewayState.lastImageLog]，让 `/admin/api/status` 也能读到
     * 最近一条图片链路日志 —— 手机上看 logcat 并不总是方便。
     */
    private fun newImageClient(cfg: GatewayConfig): QwenImageClient {
        val client = imageClientFactory(cfg)
        val sink: (String) -> Unit = { msg ->
            GatewayState.lastImageLog = msg
            debugLog("IMG $msg")
        }
        client.logSink = sink
        client.payloadDebugSink = sink
        return client
    }

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
        val trace = logBegin(req)
        val plan = credentialPlan(fresh)
        if (plan.isEmpty()) {
            logFinish(trace, 400, errorCode = "no_token", message = "未配置 Qwen token")
            noCredentialError(res)
            return
        }
        var attempts = 0
        var switches = 0
        var lastErr: QwenException? = null
        var lastRoute: CredentialRoute? = null
        var ci = 0
        while (ci < plan.size) {
            val route = plan[ci]
            val hasNext = ci < plan.size - 1
            attempts++
            lastRoute = route
            try {
                val models = newClient(fresh).also { it.credentialOverride = route.credential }.listModels()
                GatewayState.modelsCache = models
                GatewayState.modelsCacheAt = System.currentTimeMillis()
                GatewayState.qwenOk = true
                GatewayState.qwenCheckAt = System.currentTimeMillis()
                noteRouteOk(route)
                trace.line("账号 ${route.label} 拉到 ${models.size} 个模型")
                logFinish(trace, 200, route, attempts, switches, summary = "${models.size} models")
                return sendJson(res, 200, openAiModels(models))
            } catch (e: QwenException) {
                lastErr = e
                val switchable = hasNext && accountRouter.shouldSwitch(e)
                noteRouteFail(route, e, switchable)
                trace.line("账号 ${route.label} 失败: [${e.code}] ${e.message}")
                // AUTH_FAILED 是这个账号自己的问题，正好是多账号路由最有价值的场景：
                // 旧实现直接把它抛给调用方（调用方拿着一个可用的备用账号却什么都不知道）。
                if (!switchable) break
                switches++
                kotlinx.coroutines.delay(SWITCH_PAUSE_MS)
                ci++
            }
        }
        val err = lastErr
        if (err != null && err.code == "AUTH_FAILED") {
            GatewayState.qwenOk = false
            GatewayState.qwenCheckAt = System.currentTimeMillis()
            logFinish(trace, err.status, lastRoute, attempts, switches, err.code, err.message)
            return sendError(res, err)
        }
        // 拉取失败 -> 兜底旧缓存 / 静态列表（仍可调用）。
        // 注意这不记为失败：对调用方而言这是一次成功的响应（拿到了可用模型列表），
        // 把它记成 FAIL 会让日志里"失败率"虚高，掩盖真正的错误。
        val fallback = cached ?: QwenClient.FALLBACK_MODELS
        logFinish(
            trace, 200, lastRoute, attempts, switches,
            summary = "fallback ${fallback.size} models" +
                (err?.message?.let { " (上游: ${it.take(80)})" } ?: ""),
        )
        sendJson(res, 200, openAiModels(fallback))
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
        val plan = credentialPlan(fresh)
        if (plan.isEmpty()) {
            logFinish(logBegin(req), 400, errorCode = "no_token", message = "未配置 Qwen token")
            noCredentialError(res)
            return
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

        // 出图在整个过程中**一个字节都没写给调用方**（响应是最后一次性 sendJson），
        // 因此任何可切换类失败都能换账号重来。这与对话链路的约束完全不同 ——
        // 那里一旦下发过 thinking/content 就锁住了。
        val trace = logBegin(req, model = reqModel)
        var attempts = 0
        var switches = 0
        var lastErr: QwenException? = null
        var lastRoute: CredentialRoute? = null
        var ci = 0
        while (ci < plan.size) {
            val route = plan[ci]
            val hasNext = ci < plan.size - 1
            attempts++
            lastRoute = route
            val client = newImageClient(fresh).also { it.credentialOverride = route.credential }
            try {
                val result = withContext(Dispatchers.IO) { client.generateImage(imageReq) }
                val data = buildImageData(client, result, prompt, wantB64)
                trace.line("账号 ${route.label} 出图 ${result.images.size} 张 · 内部重试 ${result.retries} 次")
                noteRouteOk(route)
                val payload = LinkedHashMap<String, Any?>()
                payload["created"] = Util.nowSec()
                payload["data"] = data
                // 网关扩展字段：不放标准位，只作为排查线索
                payload["qwen"] = mapOf(
                    "model" to result.model,
                    "size" to result.size,
                    "caption" to result.caption,
                    // 与 edits 对齐：排查「偶发 502」时必须能看出网关重试了没有
                    "retries" to result.retries,
                    "downloaded_b64" to if (wantB64) {
                        data.count { (it["b64_json"] as? String).orEmpty().isNotEmpty() }
                    } else {
                        null
                    },
                )
                logReqTrack(
                    req, 200, System.currentTimeMillis() - t0,
                    "img ${result.images.size}x ${result.model}", route, attempts, switches, reqModel,
                )
                logFinish(trace, 200, route, attempts, switches, summary = "img ${result.images.size}x")
                return sendJson(res, 200, payload)
            } catch (e: Exception) {
                val err = if (e is QwenException) e else {
                    val (c, m, s) = Util.normalizeError(e); QwenException(c, m, s)
                }
                lastErr = err
                val switchable = hasNext && accountRouter.shouldSwitch(err)
                noteRouteFail(route, err, switchable)
                trace.line("账号 ${route.label} 失败: [${err.code}] ${err.message}")
                if (!switchable) break
                switches++
                logRoute("${route.label} 出图失败(${err.code}) → 切换到下一个账号")
                kotlinx.coroutines.delay(SWITCH_PAUSE_MS)
                ci++
            }
        }
        val err = lastErr ?: QwenException("UPSTREAM_ERROR", "出图失败", 502)
        logReqTrack(
            req, err.status, System.currentTimeMillis() - t0,
            "[${err.code}] retries=${err.attempts}", lastRoute, attempts, switches, reqModel,
        )
        logFinish(trace, err.status, lastRoute, attempts, switches, err.code, err.message)
        sendError(res, err)
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
        val plan = credentialPlan(fresh)
        if (plan.isEmpty()) {
            logFinish(logBegin(req), 400, errorCode = "no_token", message = "未配置 Qwen token")
            noCredentialError(res)
            return
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

        // 同文生图：源图会随账号重新上传（文件 id 与凭证绑定），因此每轮各自处理。
        val trace = logBegin(req, model = reqModel)
        var attempts = 0
        var switches = 0
        var lastErr: QwenException? = null
        var lastRoute: CredentialRoute? = null
        var ci = 0
        while (ci < plan.size) {
            val route = plan[ci]
            val hasNext = ci < plan.size - 1
            attempts++
            lastRoute = route
            val client = newImageClient(fresh).also { it.credentialOverride = route.credential }
            try {
                val result = withContext(Dispatchers.IO) { client.generateImageEdit(editReq) }
                val data = buildImageData(client, result, prompt, wantB64)
                trace.line(
                    "账号 ${route.label} 改图 ${result.images.size} 张 · 源图 ${sources.size} 张" +
                        " · 内部重试 ${result.retries} 次",
                )
                noteRouteOk(route)
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
                logReqTrack(
                    req, 200, System.currentTimeMillis() - t0,
                    "edit ${result.images.size}x ${result.model} src=${sources.size}",
                    route, attempts, switches, reqModel,
                )
                logFinish(trace, 200, route, attempts, switches, summary = "edit ${result.images.size}x")
                return sendJson(res, 200, payload)
            } catch (e: Exception) {
                val err = if (e is QwenException) e else {
                    val (c, m, s) = Util.normalizeError(e); QwenException(c, m, s)
                }
                lastErr = err
                val switchable = hasNext && accountRouter.shouldSwitch(err)
                noteRouteFail(route, err, switchable)
                trace.line("账号 ${route.label} 失败: [${err.code}] ${err.message}")
                if (!switchable) break
                switches++
                logRoute("${route.label} 改图失败(${err.code}) → 切换到下一个账号")
                kotlinx.coroutines.delay(SWITCH_PAUSE_MS)
                ci++
            }
        }
        val err = lastErr ?: QwenException("UPSTREAM_ERROR", "图生图失败", 502)
        logReqTrack(
            req, err.status, System.currentTimeMillis() - t0,
            "[${err.code}] retries=${err.attempts}", lastRoute, attempts, switches, reqModel,
        )
        logFinish(trace, err.status, lastRoute, attempts, switches, err.code, err.message)
        sendError(res, err)
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

    private suspend fun nonStreamChat(req: HttpRequest, res: HttpResponse, cfg: GatewayConfig, body: JSONObject) {
        val t1 = System.currentTimeMillis()
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
        // 注入只做一次（原地改写 messages），账号轮次之间复用
        val prepared = messages

        // 对话链路用 chatCredentialPlan：无凭证时不预检，交给 QwenClient 抛 NO_TOKEN。
        val plan = chatCredentialPlan(cfg)

        val trace = logBegin(req, model = model)
        if (plan.isEmpty()) {
            logFinish(trace, 400, errorCode = "no_token", message = "未配置 Qwen token")
            noCredentialError(res)
            return
        }

        // 非流式的换账号比流式干净得多：**一个字节都还没写给调用方**，
        // 因此任何可切换类失败都能换号重来，不存在"半截内容"的顾虑。
        var attempts = 0
        var switches = 0
        var lastErr: QwenException? = null
        var result: com.qwen2api.tx.core.ChatResult? = null
        var usedRoute: CredentialRoute? = null

        var ci = 0
        while (ci < plan.size) {
            val route = plan[ci]
            val hasNext = ci < plan.size - 1
            val client = newClient(cfg).also { it.credentialOverride = route.credential }
            attempts++
            // 无论成败都记录"本轮实际尝试的账号"：失败日志必须归因到真正失败的那个，
            // 而不是 null（否则 UI/导出会回退成"默认账号"，误导用户去换默认 token）。
            usedRoute = route
            // 在**发请求前**记"用过"：失败同样消耗了账号的可用性（对风控而言），
            // 若只在成功时记，同一个坏账号会因 lastUsedAt 永远最旧而被反复优先选中。
            noteRouteUsed(route)
            trace.line("轮次 ${ci + 1}/${plan.size}: 使用 ${route.label}" +
                (if (route.cooling) "（冷却中，其余候选均不可用）" else ""))
            try {
                result = withContext(Dispatchers.IO) {
                    val files = AttachmentResolver.resolveSuspend(client, prepared, fileStore)
                    client.chatStream(model, prepared, thinking, files, null)
                }
                noteRouteOk(route)
                break
            } catch (e: Exception) {
                val err = AttachmentResolver.humanize(e, emptyList())
                lastErr = err
                val switchable = hasNext && accountRouter.shouldSwitch(err)
                noteRouteFail(route, err, switchable)
                trace.line("账号 ${route.label} 失败: [${err.code}] ${err.message}")
                debugLog("nonstream account=${route.label} failed code=${err.code} switchable=$switchable")
                if (!switchable) break
                switches++
                logRoute("${route.label} 失败(${err.code}) → 切换到下一个账号")
                // 换号前留一点喘息，理由同流式分支：短时间连续打多个账号更像自动化
                kotlinx.coroutines.delay(SWITCH_PAUSE_MS)
                ci++
            }
        }

        val r = result
        if (r == null) {
            val err = lastErr ?: QwenException("UPSTREAM_ERROR", "上游调用失败", 502)
            logReqTrack(
                req, err.status, System.currentTimeMillis() - t1, "[${err.code}]",
                usedRoute, attempts, switches, model,
            )
            logFinish(trace, err.status, usedRoute, attempts, switches, err.code, err.message)
            return sendError(res, err)
        }

        // 解析模型输出里的工具调用（若有），并把调用块从正文中剥离
        val parsed = if (toolCtx.enabled) {
            val p = com.qwen2api.tx.core.ToolPrompt.parse(r.answer)
            ToolInjector.filterValid(p.calls, toolCtx) to p.remainingText
        } else {
            emptyList<com.qwen2api.tx.core.ToolPrompt.ParsedCall>() to r.answer
        }
        val toolCalls = parsed.first
        val answerText = parsed.second

        val msg = JSONObject().put("role", "assistant").put("content", answerText)
        if (r.thinking.isNotEmpty()) msg.put("reasoning_content", r.thinking)

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

        logReqTrack(
            req, 200, System.currentTimeMillis() - t1, "${answerText.length}ch",
            usedRoute, attempts, switches, model,
        )
        logFinish(trace, 200, usedRoute, attempts, switches, summary = "${answerText.length}ch")
        sendJson(
            res, 200,
            linkedMapOf(
                "id" to ("chatcmpl-" + Util.uuid().replace("-", "").take(24)),
                "object" to "chat.completion",
                "created" to Util.nowSec(),
                "model" to r.model,
                "system_fingerprint" to ("qwen2api-tx/" + GatewayConfig.VERSION),
                "choices" to listOf(
                    linkedMapOf(
                        "index" to 0,
                        "message" to Json.toMap(msg),
                        "finish_reason" to finishReason,
                    ),
                ),
                "usage" to usageOf(r.usage),
            ),
        )
    }

    private suspend fun streamChat(req: HttpRequest, res: HttpResponse, cfg: GatewayConfig, body: JSONObject) {
        val t0 = System.currentTimeMillis()
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

        // 提示词注入只做一次：它**原地改写** messages（追加工具说明、合并 system），
        // 若在账号切换的每一轮里重跑，同一份说明会被反复追加，上下文越来越长。
        val prepared = messages

        // 对话链路用 chatCredentialPlan：无凭证时仍建立 SSE 再回错误帧，
        // 与改动前一致（客户端拿到的必须是可解析的 SSE，而不是一段 JSON）。
        val plan = chatCredentialPlan(cfg)

        // 多账号路由的"可切换"边界（决定了哪些失败能换号）：
        //  - 只有**还没往客户端写过一个字节**时才能换号。一旦下发过 thinking/content，
        //    客户端已在累积内容，换号重发会让它看到两段拼接的回复 —— 比直接报错更糟；
        //  - 因此这里把「按账号尝试」做成外层循环，把「工具格式跑偏重试」留在内层。
        // 代价说明：工具注入模式下正文是先缓冲的（等剥完调用块才下发），
        // 所以那一轮里换号是安全的；但思维链是**实时下发**的，它一发就锁住重试/换号。
        var contentEmitted = false
        var clientGone = false
        var answerLen = 0
        var attempts = 0
        var switches = 0
        var lastErr: QwenException? = null
        var lastRoute: CredentialRoute? = null

        // 日志句柄必须在**写响应头之前**建立：流式链路一旦写出 200 + SSE 头，
        // 后面任何失败都只能以错误帧收尾，此时若才发现"没有日志句柄"，
        // 这条失败的请求就永远进不了日志 —— 而失败恰恰是这份日志存在的理由。
        val trace = logBegin(req, model = model)

        res.header("Content-Type", "text/event-stream; charset=utf-8")
        res.header("Cache-Control", "no-cache")
        res.header("X-Accel-Buffering", "no")
        res.writeHead(200)

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

        // 首帧：让客户端立刻知道流已建立（role 分片，OpenAI 流式约定）
        send(mapOf("role" to "assistant", "content" to ""), null, null)

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

        var ci = 0
        while (ci < plan.size && !clientGone) {
            val route = plan[ci]
            val hasNext = ci < plan.size - 1
            val client = newClient(cfg).also { it.credentialOverride = route.credential }
            // 无论成败都记录"本轮实际尝试的账号"：失败日志必须归因到真正失败的那个。
            lastRoute = route
            // 在**发请求前**记"用过"：失败同样消耗了账号的可用性（对风控而言），
            // 若只在成功时记，同一个坏账号会被反复优先选中（它的 lastUsedAt 永远最旧）。
            noteRouteUsed(route)
            logRoute("轮次 ${ci + 1}/${plan.size}: 使用 ${route.label}" +
                (if (route.cooling) "（冷却中，其余候选均不可用）" else ""))
            trace.line("轮次 ${ci + 1}/${plan.size}: 使用 ${route.label}" +
                (if (route.cooling) "（冷却中，其余候选均不可用）" else ""))

            try {
                // 附件解析/上传在**每轮各自**做：文件的可见性与凭证绑定
                //（不同账号上传得到的文件 id 不通用），因此不能跨账号复用。
                val files = withContext(Dispatchers.IO) {
                    AttachmentResolver.resolveSuspend(client, prepared, fileStore)
                }

                var attempt = 0
                var valid: List<ToolPrompt.ParsedCall> = emptyList()
                var text = ""
                var usage: Map<String, Any?>? = null

                while (attempt < maxAttempts) {
                    attempt++
                    val isLastAttempt = attempt >= maxAttempts
                    val buffer = if (toolCtx.enabled) com.qwen2api.tx.core.ToolCallStreamBuffer() else null
                    val textCollector = StringBuilder()

                    val result = try {
                        client.chatStream(model, prepared, thinking, files) { evt ->
                            when (evt) {
                                is QwenEvent.Thinking -> {
                                    // 思维链实时下发（让用户看到「正在思考」，避免空白体验）。
                                    // 注意：一旦下发就置 contentEmitted，本轮失败将不再重试/换号。
                                    if (!clientGone) {
                                        send(mapOf("reasoning_content" to evt.delta), null, null)
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
                                        contentEmitted = true
                                    }
                                }
                                else -> Unit
                            }
                        }
                    } catch (e: Exception) {
                        val err = if (e is QwenException) e else {
                            val (c, m, st) = Util.normalizeError(e); QwenException(c, m, st)
                        }
                        attempts++
                        lastErr = err
                        debugLog("stream attempt=$attempt account=${route.label} error: ${err.code} ${err.message}")
                        // 已吐过内容：换号会让客户端看到拼接的回复，重试同理，只能如实报错
                        if (contentEmitted) throw err
                        val risk = isRiskControlBlock(err.code, err.message)
                        debugLog("stream attempt=$attempt riskControl=$risk")
                        // 单凭证（或已是最后一个候选）时保留原有的「冷却阶梯重试」行为：
                        // 多账号场景下原地等待是纯损失（等待时间应当花在下一个账号上）。
                        if (risk && !hasNext && !isLastAttempt) {
                            val waitMs = THROTTLE_WAIT_STEPS_MS[
                                attempt.coerceAtMost(THROTTLE_WAIT_STEPS_MS.size - 1),
                            ]
                            debugLog("stream throttle wait ${waitMs}ms then retry (${attempt + 1}/$maxAttempts)")
                            // 等待期间发 SSE 注释行保活，避免客户端超时断连
                            keepAlive(res, waitMs) { clientGone }
                            if (clientGone) throw err
                            continue
                        }
                        // 非最后一轮且未吐内容：静默重试一次（应对模型格式跑偏）
                        if (!isLastAttempt) continue
                        throw err
                    }

                    val tail = buffer?.flushRemaining().orEmpty()
                    textCollector.append(tail)
                    if (buffer == null && textCollector.isEmpty() && result.answer.isNotEmpty()) {
                        textCollector.append(result.answer)
                    }
                    usage = usageOf(result.usage)
                    text = textCollector.toString()
                    valid = ToolInjector.filterValid(buffer?.calls.orEmpty(), toolCtx)

                    debugLog(
                        "stream attempt=$attempt/$maxAttempts parsed=${buffer?.calls?.size ?: 0} " +
                            "valid=${valid.size} textLen=${text.length} calls=${valid.map { it.name }}",
                    )

                    if (valid.isNotEmpty()) break
                    // 没注入工具，或模型确实无需工具（正常回答），都不重试
                    if (!toolCtx.enabled) break
                    // 启发式判断：正文里若出现「工具/调用/tool_call」等字样，
                    // 说明模型想调工具但格式跑偏了，值得重试一次
                    val looksLikeMissedCall = ToolPrompt.looksLikeMissedToolCall(text)
                    debugLog("stream attempt=$attempt missedCall=$looksLikeMissedCall")
                    if (!looksLikeMissedCall) break
                    // 客户端已断开则不再重试（否则白白多打一次上游）
                    if (clientGone) break
                    if (attempt < maxAttempts) {
                        debugLog("stream retrying (attempt ${attempt + 1})")
                    }
                }

                // 走到这里本账号轮次成功（无论模型是否调了工具）。
                noteRouteOk(route)
                if (!clientGone) {
                    if (text.isNotEmpty()) {
                        answerLen += text.length
                        send(mapOf("content" to text), null, null)
                    }
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
                                val e2 = minOf(p + 256, args.length)
                                val piece = args.substring(p, e2)
                                val d = JSONArray().put(
                                    JSONObject()
                                        .put("index", idx)
                                        .put("function", JSONObject().put("arguments", piece)),
                                )
                                send(mapOf("tool_calls" to d), null, null)
                                p = e2
                            }
                        }
                        send(emptyMap(), "tool_calls", usage)
                    } else {
                        send(emptyMap(), "stop", usage)
                    }
                }
                res.writeChunk("data: [DONE]\n\n")
                res.end()
                logReqTrack(
                    req, 200, System.currentTimeMillis() - t0, "${answerLen}ch",
                    route, attempts, switches, model,
                )
                logFinish(trace, 200, route, attempts, switches, summary = "${answerLen}ch")
                return
            } catch (e: Exception) {
                val err = if (e is QwenException) e else {
                    val (c, m, st) = Util.normalizeError(e); QwenException(c, m, st)
                }
                lastErr = err
                val switchable = !contentEmitted && hasNext && accountRouter.shouldSwitch(err)
                noteRouteFail(route, err, switchable)
                trace.line("账号 ${route.label} 失败: [${err.code}] ${err.message}" +
                    if (contentEmitted) "（已下发内容，无法换号）" else "")
                debugLog("stream account=${route.label} failed code=${err.code} switchable=$switchable hasNext=$hasNext")
                if (switchable) {
                    switches++
                    logRoute("${route.label} 失败(${err.code}) → 切换到下一个账号")
                    // 换号前留一点喘息：同一台设备/出口 IP 在极短时间内连续打多个账号，
                    // 对上游风控而言分辨率更低（看起来更像自动化）。
                    kotlinx.coroutines.delay(SWITCH_PAUSE_MS)
                    ci++
                    continue
                }
                break
            }
        }

        // 所有候选都失败，或客户端断开：如实把错误回报给客户端。
        val err = lastErr ?: QwenException(
            if (clientGone) "CLIENT_GONE" else "UPSTREAM_ERROR",
            if (clientGone) "客户端已断开连接" else "上游调用失败",
            502,
        )
        val safe = AttachmentResolver.humanize(err, emptyList())
        if (!clientGone) {
            try {
                res.writeChunk(
                    "data: " + JSONObject()
                        .put(
                            "error",
                            JSONObject()
                                .put("message", safe.message)
                                .put("type", "api_error")
                                .put("code", safe.code)
                                .put("status", safe.status),
                        ) + "\n\n",
                )
                res.writeChunk("data: [DONE]\n\n")
            } catch (e: Exception) {
                // 连接已断：不再尝试写入
            }
        }
        res.end()
        logReqTrack(
            req, safe.status, System.currentTimeMillis() - t0, "[${safe.code}]",
            lastRoute, attempts, switches, model,
        )
        logFinish(trace, safe.status, lastRoute, attempts, switches, safe.code, safe.message)
    }

    // ---------------- /v1/files ----------------

    private suspend fun handleFileUpload(req: HttpRequest, res: HttpResponse, cfg: GatewayConfig) {
        GatewayState.reqCount++
        if (!checkAuth(req, cfg)) return apiKeyError(res)
        val fresh = configRepo.load()
        if (credentialPlan(fresh).isEmpty()) {
            noCredentialError(res)
            return
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

        val guessedKind = QwenClient.mimeToKind(filePart.contentType, filePart.filename)
        val contentType = filePart.contentType.ifEmpty {
            when (guessedKind) {
                AttachmentKind.IMAGE -> "image/png"
                AttachmentKind.VIDEO -> "video/mp4"
                AttachmentKind.AUDIO -> "audio/wav"
                else -> "application/octet-stream"
            }
        }
        val plan = credentialPlan(fresh)
        if (plan.isEmpty()) {
            noCredentialError(res)
            return
        }
        val trace = logBegin(req)
        var attempts = 0
        var switches = 0
        var lastErr: QwenException? = null
        var lastRoute: CredentialRoute? = null
        var ci = 0
        // 上传同样是"一个字节都还没回给调用方"，因此可换账号。
        // 换账号后 file_id 会变（文件属于上传它的那个账号），所以必须重新上传 ——
        // 不能把上一次失败的 qwenId 拿来用，那样调用方后续引用会命中"文件不存在"。
        while (ci < plan.size) {
            val route = plan[ci]
            val hasNext = ci < plan.size - 1
            attempts++
            lastRoute = route
            val client = newClient(fresh).also { it.credentialOverride = route.credential }
            try {
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
                noteRouteOk(route)
                logReqTrack(
                    req, 200, System.currentTimeMillis() - t0,
                    "${rec.kind} ${"%.1f".format(up.size / 1024.0)}KB", route, attempts, switches,
                )
                logFinish(trace, 200, route, attempts, switches, summary = "${rec.kind} ${up.size}B")
                return sendJson(res, 200, Json.toMap(rec.toOpenAi()))
            } catch (e: Exception) {
                val err = if (e is QwenException) e else {
                    val (c, m, s) = Util.normalizeError(e); QwenException(c, m, s)
                }
                lastErr = err
                val switchable = hasNext && accountRouter.shouldSwitch(err)
                noteRouteFail(route, err, switchable)
                trace.line("账号 ${route.label} 上传失败: [${err.code}] ${err.message}")
                if (!switchable) break
                switches++
                logRoute("${route.label} 上传失败(${err.code}) → 切换到下一个账号")
                kotlinx.coroutines.delay(SWITCH_PAUSE_MS)
                ci++
            }
        }
        val err = lastErr ?: QwenException("UPSTREAM_ERROR", "上传失败", 502)
        logReqTrack(req, err.status, 0, "[${err.code}]", lastRoute, attempts, switches)
        logFinish(trace, err.status, lastRoute, attempts, switches, err.code, err.message)
        sendError(res, err)
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
                        // ---- 多账号路由 ----
                        // 只回掩码与健康度，**绝不回凭证明文**：本接口免鉴权（仅限本机），
                        // 常被脚本/浏览器插件轮询，回明文等于凭空多一个泄漏面。
                        "multiAccount" to cfg.multiAccount,
                        "accountCooldownMs" to cfg.accountCooldownMs,
                        "maxAccountSwitches" to cfg.maxAccountSwitches,
                        "accounts" to accountRouter.snapshot(cfg).map { a ->
                            linkedMapOf(
                                "id" to a.accountId,
                                "label" to a.label,
                                "enabled" to a.enabled,
                                "healthy" to a.healthy,
                                "lastUsedAt" to a.lastUsedAt,
                                "lastErrorCode" to a.lastErrorCode,
                                "lastError" to a.lastError,
                                "cooldownLeftMs" to a.cooldownLeftMs,
                            )
                        },
                        // ---- 调用日志 ----
                        // 只给统计与最近一条失败，不给全量（全量走 /admin/api/logs 或导出文件）
                        "logCount" to logStore.count(),
                        "logTotal" to logStore.totalCount,
                        "logFailTotal" to logStore.failCount,
                        "lastFailure" to logStore.lastFailure,
                        "lastAccountRoute" to GatewayState.lastAccountRoute,
                        "lastRouteSummary" to GatewayState.lastRouteSummary,
                        // 图片链路最近一条过程日志（重试/payload/源图上传）。
                        // 图片链路的失败原因常常只能在这几行里看出来，
                        // 而手机上看 logcat 并不方便，所以顺手透到状态接口。
                        "lastImageLog" to GatewayState.lastImageLog,
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
                val plan = credentialPlan(fresh)
                if (plan.isEmpty()) {
                    return sendJson(res, 200, mapOf("ok" to false, "message" to "未配置 token 或账号"))
                }
                // 逐个账号检测并如实汇报。只测"当前选中的那个"会误导：
                // 用户在多账号下想知道的恰恰是"有哪几个是坏的"。
                val detail = ArrayList<Map<String, Any?>>()
                var goodModels: List<com.qwen2api.tx.core.QwenModel>? = null
                var lastMsg = ""
                for (route in plan) {
                    try {
                        val models = withContext(Dispatchers.IO) {
                            newClient(fresh)
                                .also { it.credentialOverride = route.credential }
                                .listModels()
                        }
                        noteRouteOk(route)
                        if (goodModels == null) goodModels = models
                        detail.add(
                            mapOf(
                                "id" to route.accountId,
                                "label" to route.label,
                                "ok" to true,
                                "models" to models.size,
                            ),
                        )
                    } catch (e: Exception) {
                        val err = if (e is QwenException) e else {
                            val (c, m, st) = Util.normalizeError(e); QwenException(c, m, st)
                        }
                        lastMsg = err.message
                        noteRouteFail(route, err, accountRouter.shouldSwitch(err))
                        detail.add(
                            mapOf(
                                "id" to route.accountId,
                                "label" to route.label,
                                "ok" to false,
                                "message" to err.message,
                            ),
                        )
                    }
                }
                val models = goodModels
                if (models != null) {
                    GatewayState.modelsCache = models
                    GatewayState.modelsCacheAt = System.currentTimeMillis()
                    GatewayState.qwenOk = true
                    GatewayState.qwenCheckAt = System.currentTimeMillis()
                    val okN = detail.count { it["ok"] == true }
                    sendJson(
                        res, 200,
                        mapOf(
                            "ok" to true,
                            "message" to "连接正常 ($okN/${detail.size} 个账号可用, ${models.size} 个模型)",
                            "models" to models.map { it.id },
                            "accounts" to detail,
                        ),
                    )
                } else {
                    GatewayState.qwenOk = false
                    GatewayState.qwenCheckAt = System.currentTimeMillis()
                    sendJson(
                        res, 200,
                        mapOf(
                            "ok" to false,
                            "message" to "全部账号不可用: $lastMsg",
                            "accounts" to detail,
                        ),
                    )
                }
            }

            // ---------------- 多账号管理 ----------------

            method == "GET" && pathname == "/admin/api/accounts" -> {
                sendJson(
                    res, 200,
                    mapOf(
                        "ok" to true,
                        "enabled" to cfg.multiAccount,
                        "cooldownMs" to cfg.accountCooldownMs,
                        "maxSwitches" to cfg.maxAccountSwitches,
                        "defaultTokenMask" to ConfigStore.maskToken(cfg.qwenToken),
                        "defaultConfigured" to cfg.qwenToken.isNotBlank(),
                        "accounts" to accountRepo.all().map { a ->
                            // 凭证只回掩码：这个接口是"看有哪些账号"用的，
                            // 明文没有任何展示价值，却会在任何一次抓包/日志里永久留下。
                            linkedMapOf(
                                "id" to a.id,
                                "label" to a.displayName(),
                                "mask" to ConfigStore.maskToken(a.credential),
                                "type" to ConfigStore.detectTokenType(a.credential),
                                "enabled" to a.enabled,
                                "createdAt" to a.createdAt,
                                "lastUsedAt" to a.lastUsedAt,
                                "lastErrorCode" to a.lastErrorCode,
                                "lastError" to a.lastError,
                                "lastErrorAt" to a.lastErrorAt,
                            )
                        },
                    ),
                )
            }

            method == "POST" && pathname == "/admin/api/accounts/add" -> {
                val san = AccountStore.build(
                    Json.strOrNull(body, "token").orEmpty(),
                    Json.strOrNull(body, "label").orEmpty(),
                )
                if (!san.ok) return sendJson(res, 400, mapOf("ok" to false, "message" to san.message))
                // 与既有账号/默认 token 重复时直接拒绝，而不是加进去静默无用：
                // 凭证重复意味着"切换账号"会切到同一个账号，表现为"换了但还是同样的错误"，
                // 而用户会以为是路由坏了。
                val dup = accountRepo.all().firstOrNull { it.credential == san.token }
                if (dup != null) {
                    return sendJson(
                        res, 200,
                        mapOf("ok" to false, "message" to "该凭证已存在于账号「${dup.displayName()}」，无需重复添加"),
                    )
                }
                if (cfg.qwenToken.isNotBlank() && cfg.qwenToken == san.token) {
                    return sendJson(
                        res, 200,
                        mapOf("ok" to false, "message" to "该凭证就是当前的默认账号，无需重复添加"),
                    )
                }
                val acc = QwenAccount(
                    id = AccountStore.newId(),
                    label = Json.strOrNull(body, "label").orEmpty(),
                    credential = san.token,
                    enabled = body.optBoolean("enabled", true),
                    createdAt = System.currentTimeMillis(),
                )
                accountRepo.upsert(acc)
                // 新增即验证：不验证的话，用户要等到真正发请求才知道 token 是坏的，
                // 而那时错误会混在业务错误里，很难归因到"某个账号配错了"。
                var verified = false
                var verifyMsg = ""
                try {
                    val models = withContext(Dispatchers.IO) {
                        QwenClient("", 0).also { it.credentialOverride = acc.credential }.listModels()
                    }
                    verified = true
                    verifyMsg = "验证通过 (${models.size} 个模型)"
                    accountRouter.noteOk(CredentialRoute(acc.id, acc.displayName(), acc.credential))
                } catch (e: Exception) {
                    verifyMsg = "已保存，但验证未通过: ${e.message}"
                }
                sendJson(
                    res, 200,
                    mapOf(
                        "ok" to true,
                        "verified" to verified,
                        "message" to ("账号已添加。" + verifyMsg + if (san.note.isNotEmpty()) "。${san.note}" else ""),
                        "account" to mapOf(
                            "id" to acc.id,
                            "label" to acc.displayName(),
                            "mask" to ConfigStore.maskToken(acc.credential),
                        ),
                    ),
                )
            }

            method == "POST" && pathname == "/admin/api/accounts/update" -> {
                val id = Json.strOrNull(body, "id").orEmpty()
                val cur = accountRepo.find(id)
                    ?: return sendJson(res, 404, mapOf("ok" to false, "message" to "账号不存在: $id"))
                var next = cur
                Json.strOrNull(body, "label")?.let { next = next.copy(label = it) }
                body.opt("enabled")?.let { if (it is Boolean) next = next.copy(enabled = it) }
                Json.strOrNull(body, "token")?.takeIf { it.isNotBlank() }?.let { raw ->
                    val san = ConfigStore.sanitizeQwenToken(raw)
                    if (!san.ok) return sendJson(res, 400, mapOf("ok" to false, "message" to san.message))
                    next = next.copy(credential = san.token, lastError = "", lastErrorCode = "")
                }
                accountRepo.upsert(next)
                sendJson(res, 200, mapOf("ok" to true, "message" to "已更新", "id" to id))
            }

            method == "POST" && pathname == "/admin/api/accounts/remove" -> {
                val id = Json.strOrNull(body, "id").orEmpty()
                val gone = accountRepo.remove(id)
                    ?: return sendJson(res, 404, mapOf("ok" to false, "message" to "账号不存在: $id"))
                sendJson(res, 200, mapOf("ok" to true, "message" to "已删除「${gone.displayName()}」"))
            }

            method == "POST" && pathname == "/admin/api/accounts/reset" -> {
                // 用途：用户过完滑块验证后想立刻让账号回到可用状态。
                // 没有这个动作的话，只能等冷却自然结束（默认 10 分钟）——
                // 而用户刚刚手工证明了自己能过验证，却被网关继续挡着，体验上说不通。
                accountRouter.resetHealth()
                sendJson(res, 200, mapOf("ok" to true, "message" to "已清空全部账号的失败记录与冷却"))
            }

            // ---------------- 调用日志 ----------------

            method == "GET" && pathname == "/admin/api/logs" -> {
                // 查询参数走 query（GET 无 body）。limit 必须夹紧：
                // 日志里带 trace，几千条一次性回给浏览器插件会直接把内存打满。
                val limit = (req.queryParam("limit")?.toIntOrNull() ?: 100).coerceIn(1, ApiLogStore.MAX_ENTRIES)
                val onlyFail = req.queryParam("level")?.lowercase() == "fail"
                val list = logStore.recent(limit).filter { !onlyFail || it.level == ApiLogLevel.FAIL }
                sendJson(
                    res, 200,
                    linkedMapOf(
                        "ok" to true,
                        "count" to list.size,
                        "total" to logStore.totalCount,
                        "failTotal" to logStore.failCount,
                        "entries" to list.map { Json.toMap(it.toJson()) },
                    ),
                )
            }

            method == "POST" && pathname == "/admin/api/logs/clear" -> {
                logStore.clear()
                sendJson(res, 200, mapOf("ok" to true, "message" to "调用日志已清空"))
            }

            method == "GET" && pathname == "/admin/api/logs/export" -> {
                // 导出走 text/markdown 而不是 JSON：这份文件的用途是"发给别人/AI 帮忙定位"，
                // Markdown 在聊天窗口与 issue 里都能直接读，JSON 反而要对方自己解析。
                val body = logStore.exportText(
                    includeTrace = req.queryParam("trace")?.lowercase() != "0",
                )
                // 参数取值只允许无歧义的 utf8 / base64：任意取值会让下游脚本收到
                // 意料之外的编码而无从判断，这类"看起来成功但内容不对"最难查。
                val enc = req.queryParam("encoding")?.lowercase()
                if (enc != null && enc != "utf8" && enc != "base64") {
                    return sendJson(
                        res, 400,
                        mapOf("error" to mapOf("message" to "encoding 只支持 utf8 或 base64")),
                    )
                }
                if (enc == "base64") {
                    val text = body.toByteArray(Charsets.UTF_8)
                    val b64 = B64.encode(text)
                    return sendJson(
                        res, 200,
                        mapOf(
                            "ok" to true,
                            "encoding" to "base64",
                            "bytes" to text.size,
                            "content" to b64,
                        ),
                    )
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                res.header("Content-Type", "text/markdown; charset=utf-8")
                res.header("Content-Disposition", "attachment; filename=\"qwen2api-logs.md\"")
                res.header("Content-Length", bytes.size.toString())
                res.writeHead(200)
                return res.end(bytes)
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

    // ---------------- 调用日志（可视化 + 导出） ----------------

    /**
     * 开始记录一次调用。
     *
     * 只有**走到 finish** 的请求才会出现在日志里，因此列表里每条都是确定结果。
     * 注意：这是给"排查与导出"用的旁路，任何写入失败都不应影响请求本身，
     * 因此所有操作都包了 runCatching。
     */
    private fun logBegin(req: HttpRequest, model: String = "", route: CredentialRoute? = null): ApiLogStore.Trace =
        logStore.begin(
            method = req.effectiveMethod,
            path = rawPathOf(req),
            model = model,
            accountId = route?.accountId.orEmpty(),
            accountLabel = relativeLabel(route),
        )

    /** 结束记录（成功/失败一条通路，避免两处分别构造导致字段漏填） */
    private fun logFinish(
        trace: ApiLogStore.Trace?,
        status: Int,
        route: CredentialRoute? = null,
        attempts: Int = 1,
        switches: Int = 0,
        errorCode: String = "",
        message: String = "",
        summary: String = "",
    ) {
        val t = trace ?: return
        runCatching {
            route?.let { t.useAccount(it.accountId, relativeLabel(it)) }
            t.attempts = attempts
            t.switches = switches
            if (message.isNotEmpty()) t.line("错误: [$errorCode] $message")
            t.finish(
                level = if (status in 200..299) ApiLogLevel.OK else ApiLogLevel.FAIL,
                status = status,
                errorCode = errorCode,
                message = message,
                summary = summary,
            )
        }
    }

    /**
     * 与 [logReq] 同款，但额外带上账号与尝试次数。
     *
     * 为什么不让 [logReq] 也带这些参数：它被十几处调用（文件、图片、admin），
     * 大部分链路根本没有"账号轮换"的概念，强行加参数会让那些调用点传一堆默认值。
     * 这里保留两个入口，各自表达自己的语义。
     */
    private fun logReqTrack(
        req: HttpRequest,
        status: Int,
        ms: Long,
        note: String,
        route: CredentialRoute?,
        attempts: Int,
        switches: Int,
        model: String = "",
    ) {
        val acct = route?.let { " · 账号 ${relativeLabel(it)}" } ?: ""
        val retry = if (attempts > 1 || switches > 0) " · 上游尝试 $attempts 次/切换 $switches 次" else ""
        logReq(req, status, ms, note + acct + retry)
        GatewayState.lastRouteSummary = "HTTP $status · ${ms}ms" + acct + retry
    }

    /**
     * 账号在日志里的相对称呼。
     *
     * 用"默认账号"而不是"默认"：日志导出后会被贴到 issue 里，
     * "默认"容易被误读成"默认行为"而不知道指的是凭证。
     */
    private fun relativeLabel(route: CredentialRoute?): String = route?.label.orEmpty()

    /** 去掉 query 的路径（日志按路径聚合时不该被 chat_id 之类打散） */
    private fun rawPathOf(req: HttpRequest): String {
        val t = req.rawTarget
        val i = t.indexOf('?')
        return if (i >= 0) t.substring(0, i) else t
    }

    /** 写一条账号路由诊断（同时进 trace、logcat 与服务状态） */
    private fun logRoute(msg: String) {
        debugLog("ROUTE $msg")
        GatewayState.lastAccountRoute = msg
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
         * 判据**实现在 [com.qwen2api.tx.core.RiskControl]**，这里只做转发：
         * 图片客户端在 core 包、不能反向依赖 server，而「哪些措辞算风控」
         * 一旦有两份实现必然分叉 —— 实测过的代价是文本链路认得出阿里盾处罚页、
         * 图片链路却把它当瞬时故障重试 4 次（同一请求 8s 变 58s）。
         * 保留这层转发是为了不动既有的 `GatewayRouter.isRiskControlBlock` 调用点。
         */
        fun isRiskControlBlock(msg: String?): Boolean = RiskControl.isRiskControlBlock(msg)

        /** 带错误码的判定（优先看 code，其次看 message）。 */
        fun isRiskControlBlock(code: String?, msg: String?): Boolean =
            RiskControl.isRiskControlBlock(code, msg)

        /** 折中等待阶梯（毫秒）：8s / 15s / 25s —— 兼顾成功率与响应速度。 */
        val THROTTLE_WAIT_STEPS_MS: LongArray = RiskControl.WAIT_STEPS_MS

        /** 风控拦截时给用户的友好中文提示（替代原始的处罚页 JSON）。 */
        const val RISK_CONTROL_HINT =
            "上游触发了安全验证/限流（风控）。这通常因短时间内请求过多导致，并非网关故障。" +
                "请稍后重试；若持续出现，请在浏览器打开 chat.qwen.ai 手动过一次滑块验证。"

        /**
         * 换账号前的停顿（毫秒）。
         *
         * 不是"退避重试"，而是**切换节奏**：同一台设备、同一个出口 IP 在短短
         * 几百毫秒内连续打两个不同账号，对上游风控来说分辨率更低 ——
         * 看起来更像自动化而不是"两个人在不同时间用各自的账号"。
         *
         * 取值 600ms：小到不影响用户感知（多账号切换本身是失败路径，
         * 用户已经在等），大到足以让两次请求落在不同的秒级时间片上。
         */
        const val SWITCH_PAUSE_MS = 600L
    }
}
