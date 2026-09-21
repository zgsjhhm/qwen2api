package com.qwen2api.tx.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qwen2api.tx.core.AccountStore
import com.qwen2api.tx.core.ConfigStore
import com.qwen2api.tx.core.GatewayConfig
import com.qwen2api.tx.core.Json
import com.qwen2api.tx.core.LoginCallbackBus
import com.qwen2api.tx.core.QwenAccount
import com.qwen2api.tx.core.QwenClient
import com.qwen2api.tx.core.QwenEvent
import com.qwen2api.tx.core.QwenException
import com.qwen2api.tx.core.QwenImageClient
import com.qwen2api.tx.core.QwenModel
import com.qwen2api.tx.server.GatewayState
import com.qwen2api.tx.service.GatewayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 界面提示条 */
data class Notice(val kind: Kind, val text: String) {
    enum class Kind { OK, BAD, INFO }
}

/** 测试对话状态 */
data class TestChatState(
    val running: Boolean = false,
    val answer: String = "",
    val thinking: String = "",
    val steps: Int = 0,
    val elapsedMs: Long = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val status: String = "等待响应…",
    val error: String? = null,
)

/** 网络诊断步骤 */
data class DiagStep(val name: String, val ok: Boolean, val ms: Long, val detail: String)

/**
 * 账号行（UI 展示用）。
 *
 * 与 [com.qwen2api.tx.core.AccountStatus] 的区别：后者是路由算出的**健康视图**
 *（含冷却剩余），本类型是用户**管理**账号需要的字段（是否启用、上次用的时间、
 * 掩码后的凭证）。两者合并会让 UI 被迫关心冷却计算，而冷却时长与配置耦合，
 * 不该出现在列表行里。
 */
data class AccountRow(
    val id: String,
    val label: String,
    val masked: String,
    val enabled: Boolean,
    val lastUsedAt: Long = 0L,
    val lastError: String = "",
    val lastErrorCode: String = "",
)

data class UiState(
    val config: GatewayConfig = GatewayConfig(),
    val serviceRunning: Boolean = false,
    val port: Int = 0,
    val qwenOk: Boolean? = null,
    val models: List<QwenModel> = QwenClient.FALLBACK_MODELS,
    val reqCount: Long = 0,
    val tokenNotice: Notice? = null,
    val savingToken: Boolean = false,
    val checking: Boolean = false,
    val diag: List<DiagStep> = emptyList(),
    val diagHint: String = "",
    val guideTab: Int = 0,
    val test: TestChatState = TestChatState(),
    val settingsNotice: String? = null,
    val copiedHint: String? = null,
    /** 账号列表（多账号路由的实际参与方） */
    val accounts: List<AccountRow> = emptyList(),
    /** 账号卡片上的提示（新增/删除/验证结果） */
    val accountNotice: Notice? = null,
    /** 累计调用统计与最近一次失败 */
    val logTotal: Long = 0L,
    val logFails: Long = 0L,
    val lastFailure: String = "",
    /** 最近的调用日志（由新到旧，已按 UI 上限截断） */
    val logRows: List<com.qwen2api.tx.core.ApiLogEntry> = emptyList(),
    /** 0 = 服务进程尚未创建路由（账号/日志为空的原因是"没启动"，不是"没有"） */
    val logSourceReady: Boolean = false,
)

/**
 * 极简 UI 的唯一 ViewModel：承载配置、服务状态、测试对话与诊断。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext

    private val _ui = MutableStateFlow(
        UiState(
            config = ConfigStore.load(ctx),
            serviceRunning = GatewayService.isRunning,
            port = GatewayService.boundPort,
            qwenOk = GatewayState.qwenOk,
            models = GatewayState.modelsCache ?: QwenClient.FALLBACK_MODELS,
        ),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var tickJob: Job? = null

    init {
        startTicker()
        observeLoginCallback()
    }

    /**
     * 监听外部浏览器登录回调：解析到凭证后**自动**校验并保存，
     * 用户无需任何额外操作（这是「全自动闭环」的关键一环）。
     */
    private fun observeLoginCallback() {
        viewModelScope.launch {
            LoginCallbackBus.events.collect { event ->
                // 消费后立刻清空 replay 缓存，避免旋转屏幕等重组时重复导入
                LoginCallbackBus.consume()
                when {
                    !event.credential.isNullOrBlank() -> {
                        _ui.value = _ui.value.copy(
                            tokenNotice = Notice(
                                Notice.Kind.INFO,
                                "已从浏览器登录回调收到凭证，正在自动验证…",
                            ),
                        )
                        saveToken(event.credential, fromBrowser = true)
                    }
                    !event.error.isNullOrBlank() -> {
                        _ui.value = _ui.value.copy(
                            tokenNotice = Notice(Notice.Kind.BAD, event.error),
                        )
                    }
                }
            }
        }
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (isActive) {
                val running = GatewayService.isRunning
                _ui.value = _ui.value.copy(
                    serviceRunning = running,
                    port = GatewayService.boundPort,
                    qwenOk = GatewayState.qwenOk,
                    reqCount = GatewayState.reqCount,
                )
                refreshAccounts()
                delay(800)
            }
        }
    }

    // ---------------- 账号（多账号路由） ----------------

    /**
     * 回读账号列表与调用统计。
     *
     * 走 [GatewayService.activeRouter] 而不是门面单例：账号列表**必须在服务进程
     * 持有的那份仓储上读**，因为路由用的是它。若这里另建一个仓储实例，
     * 会出现「UI 里删掉的账号，网关这一轮还在用」——两者看着是同一份数据，
     * 实际是两个对象，是最难自查的一类不一致。
     */
    fun refreshAccounts() {
        val repo = GatewayService.activeRouter
        val list = repo?.accountRepository()
        val store = repo?.logStoreRef()
        _ui.value = _ui.value.copy(
            accounts = list?.all()?.map { a ->
                AccountRow(
                    id = a.id,
                    label = a.displayName(),
                    masked = ConfigStore.maskToken(a.credential),
                    enabled = a.enabled,
                    lastUsedAt = a.lastUsedAt,
                    lastError = a.lastError,
                    lastErrorCode = a.lastErrorCode,
                )
            }.orEmpty(),
            logSourceReady = repo != null,
            logTotal = store?.totalCount ?: 0L,
            logFails = store?.failCount ?: 0L,
            lastFailure = store?.lastFailure.orEmpty(),
            logRows = store?.recent(LOG_ROWS_LIMIT).orEmpty(),
        )
    }

    /**
     * 新增账号：先验证再落库。
     *
     * 顺序刻意如此：先验证可以避免把坏凭证写进列表（否则用户要在真正发请求时
     * 才看到错误，且错误会混在业务错误里，很难归因到"某个账号配错了"）。
     */
    fun addAccount(raw: String, label: String) {
        val repo = GatewayService.activeRouter?.accountRepository()
        if (repo == null) {
            setAccountNotice(Notice.Kind.BAD, "网关未启动：账号列表由网关持有，请先启动网关服务")
            return
        }
        val san = AccountStore.build(raw, label)
        if (!san.ok) {
            setAccountNotice(Notice.Kind.BAD, san.message)
            return
        }
        // 凭证重复 = 切换账号会切到同一个账号上，表现为"换了但错误一模一样"。
        // 这里直接拦下并说清原因，比让用户自己去发现"两个账号长得差不多"友好得多。
        val dup = repo.all().firstOrNull { it.credential == san.token }
        if (dup != null) {
            setAccountNotice(Notice.Kind.BAD, "该凭证已存在于账号「${dup.displayName()}」，无需重复添加")
            return
        }
        val acc = QwenAccount(
            id = AccountStore.newId(),
            label = label.trim(),
            credential = san.token,
            createdAt = System.currentTimeMillis(),
        )
        repo.upsert(acc)
        refreshAccounts()
        setAccountNotice(Notice.Kind.INFO, "已保存账号「${acc.displayName()}」，正在验证…")
        viewModelScope.launch {
            val msg = try {
                val models = withContext(Dispatchers.IO) {
                    QwenClient("", 0).also { it.credentialOverride = acc.credential }.listModels()
                }
                "✓ 账号「${acc.displayName()}」验证通过（${models.size} 个模型可用）"
                    .let { it + if (san.note.isNotEmpty()) "。${san.note}" else "" }
            } catch (e: Exception) {
                "账号已保存，但验证未通过：${e.message ?: "未知错误"}（该账号会被路由自动跳过并在冷却后重试）"
            }
            setAccountNotice(if (msg.startsWith("✓")) Notice.Kind.OK else Notice.Kind.BAD, msg)
            refreshAccounts()
        }
    }

    fun setAccountEnabled(id: String, enabled: Boolean) {
        val repo = GatewayService.activeRouter?.accountRepository() ?: return
        val a = repo.find(id) ?: return
        repo.upsert(a.copy(enabled = enabled))
        refreshAccounts()
        setAccountNotice(Notice.Kind.INFO, if (enabled) "已启用「${a.displayName()}」" else "已停用「${a.displayName()}」")
    }

    fun removeAccount(id: String) {
        val repo = GatewayService.activeRouter?.accountRepository() ?: return
        val gone = repo.remove(id) ?: return
        refreshAccounts()
        setAccountNotice(Notice.Kind.INFO, "已删除账号「${gone.displayName()}」")
    }

    /** 手动清空全部账号的健康记录（过完滑块后想立刻恢复时用） */
    fun resetAccountHealth() {
        val repo = GatewayService.activeRouter?.accountRepository() ?: return
        var n = 0
        repo.all().forEach { a ->
            if (a.lastError.isNotEmpty() || a.lastErrorAt > 0L) n++
            repo.upsert(a.copy(lastError = "", lastErrorCode = "", lastErrorAt = 0L))
        }
        refreshAccounts()
        setAccountNotice(
            Notice.Kind.OK,
            if (n == 0) "所有账号本就是健康状态" else "已重置 $n 个账号的失败记录，它们会立即重新参与轮转",
        )
    }

    /** 清空调用日志（含累计统计与落盘文件） */
    fun clearLogs() {
        val store = GatewayService.activeRouter?.logStoreRef() ?: return
        store.clear()
        refreshAccounts()
        setAccountNotice(Notice.Kind.INFO, "已清空调用日志")
    }

    private fun setAccountNotice(kind: Notice.Kind, text: String) {
        _ui.value = _ui.value.copy(accountNotice = Notice(kind, text))
    }

    fun dismissAccountNotice() {
        _ui.value = _ui.value.copy(accountNotice = null)
    }

    /**
     * 导出调用日志为可分享的 Markdown 文本。
     *
     * @param includeTrace 是否附带每条请求的诊断行（体积大但定位价值高）
     * @return 日志文本；服务未启动（拿不到仓储）时返回 null
     */
    fun exportLogs(includeTrace: Boolean = true): String? =
        GatewayService.activeRouter?.logStoreRef()?.exportText(includeTrace = includeTrace)

    // ---------------- 配置 ----------------

    fun reloadConfig() {
        _ui.value = _ui.value.copy(config = ConfigStore.load(ctx))
    }

    /** 启动本地网关服务，并同步状态到 UI。 */
    fun startService() {
        runCatching {
            ctx.startService(
                Intent(ctx, GatewayService::class.java).setAction(GatewayService.ACTION_START),
            )
        }
        // 服务启动有延迟，稍后回读真实状态
        viewModelScope.launch {
            delay(700)
            refreshServiceState()
        }
    }

    /** 停止本地网关服务。 */
    fun stopService() {
        runCatching {
            ctx.startService(
                Intent(ctx, GatewayService::class.java).setAction(GatewayService.ACTION_STOP),
            )
        }
        viewModelScope.launch {
            delay(400)
            refreshServiceState()
        }
    }

    /** 从 GatewayService 回读运行状态与端口。 */
    fun refreshServiceState() {
        _ui.value = _ui.value.copy(
            serviceRunning = GatewayService.isRunning,
            port = GatewayService.boundPort,
        )
    }

    /**
     * 保存 token。
     *
     * @param rawToken 用户粘贴的内容，或来自内置浏览器登录后取回的整段 Cookie
     * @param fromBrowser 是否来自「用手机浏览器登录」流程（用于调整提示文案）
     */
    fun saveToken(rawToken: String, fromBrowser: Boolean = false) {
        if (_ui.value.savingToken) return
        val san = ConfigStore.sanitizeQwenToken(rawToken)
        if (!san.ok) {
            _ui.value = _ui.value.copy(tokenNotice = Notice(Notice.Kind.BAD, san.message))
            return
        }
        _ui.value = _ui.value.copy(
            savingToken = true,
            tokenNotice = if (fromBrowser) {
                Notice(Notice.Kind.INFO, "已从浏览器登录取回凭证，正在验证…")
            } else {
                null
            },
        )
        viewModelScope.launch {
            val client = QwenClient(san.token, 0)
            try {
                val models = withContext(Dispatchers.IO) { client.listModels() }
                GatewayState.modelsCache = models
                GatewayState.modelsCacheAt = System.currentTimeMillis()
                GatewayState.qwenOk = true
                GatewayState.qwenCheckAt = System.currentTimeMillis()
                ConfigStore.update(ctx) { it.copyWith(qwenToken = san.token) }
                val note = if (san.note.isNotEmpty()) "。${san.note}" else ""
                val src = if (fromBrowser) "已从手机浏览器登录并自动导入" else "已保存"
                _ui.value = _ui.value.copy(
                    savingToken = false,
                    config = ConfigStore.load(ctx),
                    models = models,
                    qwenOk = true,
                    tokenNotice = Notice(
                        Notice.Kind.OK,
                        "✓ 验证通过，$src (${models.size} 个模型可用)$note",
                    ),
                )
            } catch (e: Exception) {
                GatewayState.qwenOk = false
                GatewayState.qwenCheckAt = System.currentTimeMillis()
                val msg = e.message ?: "验证失败"
                val netFail = msg.contains("无法连接") || msg.contains("NETWORK")
                _ui.value = _ui.value.copy(
                    savingToken = false,
                    qwenOk = false,
                    tokenNotice = Notice(
                        Notice.Kind.BAD,
                        "✗ $msg" + if (netFail) " — 这是网络连接问题（与 token 无关），可点「网络诊断」排查" else "",
                    ),
                )
                if (netFail) runNetDiag()
            }
        }
    }

    /** 用户在内置浏览器里点了「重新登录」，已清除 WebView Cookie */
    fun notifyCookieCleared() {
        _ui.value = _ui.value.copy(
            tokenNotice = Notice(Notice.Kind.INFO, "已清除内置浏览器登录状态，请重新登录"),
        )
    }

    /** 通用提示（用于外部浏览器登录流程的中间反馈） */
    fun notifyToast(msg: String) {
        _ui.value = _ui.value.copy(tokenNotice = Notice(Notice.Kind.INFO, msg))
        viewModelScope.launch {
            delay(4000)
            _ui.value = _ui.value.copy(tokenNotice = null)
        }
    }

    fun checkConnection() {
        if (_ui.value.checking) return
        val cfg = ConfigStore.load(ctx)
        if (cfg.qwenToken.isBlank()) {
            _ui.value = _ui.value.copy(tokenNotice = Notice(Notice.Kind.BAD, "未配置 token"))
            return
        }
        _ui.value = _ui.value.copy(checking = true)
        viewModelScope.launch {
            try {
                val models = withContext(Dispatchers.IO) { QwenClient(cfg.qwenToken, cfg.throttleMs).listModels() }
                GatewayState.modelsCache = models
                GatewayState.modelsCacheAt = System.currentTimeMillis()
                GatewayState.qwenOk = true
                GatewayState.qwenCheckAt = System.currentTimeMillis()
                _ui.value = _ui.value.copy(
                    checking = false, qwenOk = true, models = models,
                    tokenNotice = Notice(Notice.Kind.OK, "✓ 连接正常 (${models.size} 个模型)"),
                )
            } catch (e: Exception) {
                GatewayState.qwenOk = false
                GatewayState.qwenCheckAt = System.currentTimeMillis()
                val msg = e.message ?: "检测失败"
                _ui.value = _ui.value.copy(
                    checking = false, qwenOk = false,
                    tokenNotice = Notice(Notice.Kind.BAD, "✗ $msg"),
                )
                if (msg.contains("无法连接") || msg.contains("NETWORK")) runNetDiag()
            }
        }
    }

    fun regenerateKey() {
        val newKey = ConfigStore.generateApiKey()
        ConfigStore.update(ctx) { it.copyWith(apiKey = newKey) }
        _ui.value = _ui.value.copy(config = ConfigStore.load(ctx), copiedHint = "已重新生成")
        viewModelScope.launch {
            delay(1500)
            _ui.value = _ui.value.copy(copiedHint = null)
        }
    }

    fun saveSettings(
        defaultModel: String,
        thinking: Boolean,
        autoOpen: Boolean,
        throttleMs: Int,
        port: Int,
        imageRetryCount: Int = ConfigStore.load(ctx).imageRetryCount,
        imageRetryBackoffMs: Int = ConfigStore.load(ctx).imageRetryBackoffMs,
        systemPrompt: String = ConfigStore.load(ctx).systemPrompt,
        systemPromptEnabled: Boolean = ConfigStore.load(ctx).systemPromptEnabled,
        systemPromptMode: String = ConfigStore.load(ctx).systemPromptMode,
        multiAccount: Boolean = ConfigStore.load(ctx).multiAccount,
        accountCooldownMs: Int = ConfigStore.load(ctx).accountCooldownMs,
        maxAccountSwitches: Int = ConfigStore.load(ctx).maxAccountSwitches,
    ) {
        val old = ConfigStore.load(ctx)
        ConfigStore.update(ctx) { it.copyWith(
            defaultModel = defaultModel,
            thinking = thinking,
            autoOpen = autoOpen,
            throttleMs = throttleMs.coerceIn(0, 30000),
            port = port.coerceIn(1, 65535),
            // 出图重试与对话流共用同一套「上游偶发错误」判定，
            // 但账号被风控时重试只会火上浇油，所以这两个值必须让用户能调到 0。
            imageRetryCount = imageRetryCount.coerceIn(0, GatewayConfig.MAX_IMAGE_RETRY),
            imageRetryBackoffMs = imageRetryBackoffMs
                .coerceIn(0, QwenImageClient.MAX_RETRY_BACKOFF_MS.toInt()),
            // 全局 System Prompt。清洗统一走 ConfigStore，不在这里手写规则：
            // UI 与 admin API 是两条独立写入路径，归一化逻辑一旦分叉，
            // 就会出现「UI 保存的和 API 保存的生效效果不一样」这种没法解释的问题。
            systemPrompt = ConfigStore.sanitizeSystemPrompt(systemPrompt),
            systemPromptEnabled = systemPromptEnabled,
            systemPromptMode = ConfigStore.normalizeSystemPromptMode(systemPromptMode),
            multiAccount = multiAccount,
            // 冷却时长必须夹紧到 [0, MAX_ACCOUNT_COOLDOWN]，理由见 Config.kt 里
            // MAX_ACCOUNT_COOLDOWN 的说明：填过大的值会让账号在本进程内永远不被选中，
            // 而界面上只显示"冷却中"，用户很难意识到是自己把分钟当秒填了。
            accountCooldownMs = accountCooldownMs.coerceIn(0, GatewayConfig.MAX_ACCOUNT_COOLDOWN),
            maxAccountSwitches = maxAccountSwitches.coerceIn(0, GatewayConfig.MAX_ACCOUNT_SWITCHES),
        ) }
        val portChanged = old.port != port.coerceIn(1, 65535)
        _ui.value = _ui.value.copy(
            config = ConfigStore.load(ctx),
            settingsNotice = if (portChanged) "已保存 · 端口需重启服务生效" else "已保存",
        )
        viewModelScope.launch {
            delay(2500)
            _ui.value = _ui.value.copy(settingsNotice = null)
        }
    }

    fun setGuideTab(i: Int) {
        _ui.value = _ui.value.copy(guideTab = i)
    }

    // ---------------- 网络诊断 ----------------

    fun runNetDiag() {
        _ui.value = _ui.value.copy(diag = emptyList(), diagHint = "正在逐层检测本机到 chat.qwen.ai 的连通性（DNS → TCP → TLS → HTTPS）…")
        viewModelScope.launch(Dispatchers.IO) {
            val host = "chat.qwen.ai"
            val steps = ArrayList<DiagStep>()

            // 1) DNS
            var t0 = System.currentTimeMillis()
            try {
                val addrs = java.net.InetAddress.getAllByName(host)
                val detail = addrs.joinToString(", ") {
                    it.hostAddress + if (it is java.net.Inet6Address) " (IPv6)" else ""
                }
                steps.add(DiagStep("DNS 解析", true, System.currentTimeMillis() - t0, detail.ifEmpty { "无记录" }))
            } catch (e: Exception) {
                steps.add(DiagStep("DNS 解析", false, System.currentTimeMillis() - t0, e.message ?: "解析失败"))
                postDiag(steps, "DNS 无法解析域名: 先确认设备能正常上网; 可尝试切换 Wi-Fi/移动数据 或更换 DNS(如 223.5.5.5)")
                return@launch
            }

            // 2) TCP 443
            t0 = System.currentTimeMillis()
            try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress(host, 443), 5000)
                }
                steps.add(DiagStep("TCP 连接 443", true, System.currentTimeMillis() - t0, "已建立"))
            } catch (e: Exception) {
                steps.add(DiagStep("TCP 连接 443", false, System.currentTimeMillis() - t0, e.message ?: "连接失败"))
                postDiag(steps, "TCP 连接失败: 检查网络是否可达外网, 是否启用代理/VPN 拦截, 或尝试切换网络")
                return@launch
            }

            // 3) TLS
            t0 = System.currentTimeMillis()
            try {
                val factory = javax.net.ssl.SSLContext.getDefault().socketFactory
                (factory.createSocket(host, 443) as javax.net.ssl.SSLSocket).use { s ->
                    s.startHandshake()
                    val proto = s.session.protocol
                    steps.add(DiagStep("TLS 握手", true, System.currentTimeMillis() - t0, proto))
                }
            } catch (e: Exception) {
                steps.add(DiagStep("TLS 握手", false, System.currentTimeMillis() - t0, e.message ?: "握手失败"))
                postDiag(steps, "TLS 握手失败: 检查系统时间是否正确(证书校验依赖时间); 安全软件/VPN 可能拦截 HTTPS")
                return@launch
            }

            // 4) HTTPS
            t0 = System.currentTimeMillis()
            try {
                val client = QwenClient("", 0)
                @Suppress("UNUSED_EXPRESSION") client
                val req = okhttp3.Request.Builder()
                    .url("https://$host/api/v2/models/")
                    .header("User-Agent", QwenClient.UA)
                    .build()
                val code = QwenClient.http.newCall(req).execute().use { it.code }
                steps.add(DiagStep("HTTPS 请求", true, System.currentTimeMillis() - t0, "HTTP $code (网络通畅)"))
            } catch (e: Exception) {
                steps.add(DiagStep("HTTPS 请求", false, System.currentTimeMillis() - t0, e.message ?: "请求失败"))
                postDiag(steps, "DNS/TCP/TLS 都通但 HTTPS 失败(罕见): 请把本页截图反馈")
                return@launch
            }

            postDiag(steps, "本机到 chat.qwen.ai 网络全通。若保存 token 仍报错, 问题与网络无关, 请查看具体报错文本")
        }
    }

    private suspend fun postDiag(steps: List<DiagStep>, hint: String) {
        withContext(Dispatchers.Main) {
            _ui.value = _ui.value.copy(diag = steps, diagHint = hint)
        }
    }

    // ---------------- 测试对话 ----------------

    fun sendTest(message: String, model: String, thinking: Boolean) {
        if (message.isBlank() || _ui.value.test.running) return
        val cfg = ConfigStore.load(ctx)
        if (cfg.qwenToken.isBlank()) {
            _ui.value = _ui.value.copy(test = TestChatState(error = "请先配置 Qwen token"))
            return
        }
        _ui.value = _ui.value.copy(
            test = TestChatState(running = true, status = "连接 Qwen…"),
        )
        viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            val timer = launch {
                while (isActive) {
                    delay(120)
                    _ui.value = _ui.value.copy(
                        test = _ui.value.test.copy(elapsedMs = System.currentTimeMillis() - t0),
                    )
                }
            }
            val answer = StringBuilder()
            val think = StringBuilder()
            try {
                val client = QwenClient(cfg.qwenToken, cfg.throttleMs)
                val result = client.chatStream(
                    model = model,
                    messages = listOf(com.qwen2api.tx.core.ChatMessage("user", message)),
                    thinking = thinking,
                ) { evt ->
                    when (evt) {
                        is QwenEvent.Thinking -> {
                            think.append(evt.delta)
                            _ui.value = _ui.value.copy(
                                test = _ui.value.test.copy(thinking = think.toString()),
                            )
                        }
                        is QwenEvent.Steps -> _ui.value = _ui.value.copy(
                            test = _ui.value.test.copy(steps = evt.titles.size),
                        )
                        is QwenEvent.Content -> {
                            answer.append(evt.delta)
                            _ui.value = _ui.value.copy(
                                test = _ui.value.test.copy(answer = answer.toString()),
                            )
                        }
                        else -> Unit
                    }
                }
                GatewayState.qwenOk = true
                _ui.value = _ui.value.copy(
                    test = _ui.value.test.copy(
                        running = false,
                        answer = result.answer.ifEmpty { answer.toString() },
                        thinking = result.thinking.ifEmpty { think.toString() },
                        steps = result.steps.size,
                        status = "完成",
                        elapsedMs = System.currentTimeMillis() - t0,
                        inputTokens = result.usage?.let { Json.int(it, "input_tokens") } ?: 0,
                        outputTokens = result.usage?.let { Json.int(it, "output_tokens") } ?: 0,
                        error = null,
                    ),
                )
            } catch (e: Exception) {
                val err = if (e is QwenException) e else {
                    QwenException("UPSTREAM_ERROR", e.message ?: "未知错误", 502)
                }
                _ui.value = _ui.value.copy(
                    test = _ui.value.test.copy(
                        running = false, status = "出错", error = err.message,
                        elapsedMs = System.currentTimeMillis() - t0,
                    ),
                )
            } finally {
                timer.cancel()
            }
        }
    }

    fun clearTest() {
        _ui.value = _ui.value.copy(test = TestChatState())
    }

    override fun onCleared() {
        tickJob?.cancel()
        super.onCleared()
    }

    /** 生成接入指南文本 */
    fun guideText(tab: Int): Pair<String, String> {
        val cfg = _ui.value.config
        val base = "http://127.0.0.1:${if (_ui.value.port > 0) _ui.value.port else cfg.port}/v1"
        val key = cfg.apiKey.ifEmpty { "sk-qpp-xxxx" }
        return when (tab) {
            0 -> ("""
 Cherry Studio → 设置 → 模型服务 → 添加「OpenAI 兼容」

   API 地址:  $base
   API 密钥:  $key
   模型:      点「获取模型」自动拉取 (qwen3.8-max 等)

 提示: 模型列表自动来自你的 Qwen 账号, 无需手动录入。
""".trimIndent() to "Cherry Studio 的「AI 伴侣 / 翻译 / 话题命名」等辅助功能会默认调用列表第一个模型，建议把 qwen3.8-max 放在首位。")

            1 -> ("""
 # zcode / 各类 OpenAI 协议 CLI Agent 通用环境变量
 export OPENAI_BASE_URL=$base
 export OPENAI_API_KEY=$key
 export OPENAI_MODEL=qwen3.8-max

 # 或写入工具自己的配置文件 (yaml/json)
 base_url: $base
 api_key:  $key
 model:    qwen3.8-max
""".trimIndent() to "任何支持「自定义 OpenAI 兼容端点」的 agent 工具（zcode、aider、continue、cline…）都按此三件套配置。")

            2 -> ("""
 curl $base/chat/completions \
   -H "Authorization: Bearer $key" \
   -H "Content-Type: application/json" \
   -d '{
     "model": "qwen3.8-max",
     "messages": [{"role":"user","content":"你好"}],
     "stream": true
   }'
""".trimIndent() to "stream: true 时返回 OpenAI 标准增量 chunk；false 时返回完整 JSON（思考文本在 message.reasoning_content）。")

            3 -> ("""
 from openai import OpenAI

 client = OpenAI(
     base_url="$base",
     api_key="$key",
 )

 stream = client.chat.completions.create(
     model="qwen3.8-max",
     messages=[{"role": "user", "content": "写一首七言绝句"}],
     stream=True,
 )
 for chunk in stream:
     d = chunk.choices[0].delta
     if d.content:
         print(d.content, end="", flush=True)
""".trimIndent() to "需要 pip install openai。reasoning_content 里的思考摘要 Cherry Studio 会自动折叠展示。")

            else -> ("""
 import OpenAI from "openai";

 const client = new OpenAI({
   baseURL: "$base",
   apiKey: "$key",
 });

 const stream = await client.chat.completions.create({
   model: "qwen3.8-max",
   messages: [{ role: "user", content: "你好" }],
   stream: true,
 });
 for await (const chunk of stream) {
   process.stdout.write(chunk.choices[0]?.delta?.content ?? "");
 }
""".trimIndent() to "需要 npm i openai。请求体额外支持 \"thinking\": true/false 控制思考摘要输出。")
        }
    }

    fun dismissNotice() {
        _ui.value = _ui.value.copy(tokenNotice = null)
    }

    fun setCopiedHint(text: String?) {
        _ui.value = _ui.value.copy(copiedHint = text)
    }

    private companion object {
        /**
         * 日志页在内存里保留的条数上限。
         *
         * 比 [com.qwen2api.tx.core.ApiLogStore.MAX_ENTRIES]（400）小：列表每 800ms
         * 随 ticker 重建一次，条数越多重组越贵，而用户在手机上翻不了几百条。
         * 需要全量时走「导出」——那里读的是仓储的完整窗口。
         */
        const val LOG_ROWS_LIMIT = 60
    }
}
