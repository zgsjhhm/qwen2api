package com.qwen2api.tx.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qwen2api.tx.core.ConfigStore
import com.qwen2api.tx.core.GatewayConfig
import com.qwen2api.tx.core.Json
import com.qwen2api.tx.core.LoginCallbackBus
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
                delay(800)
            }
        }
    }

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
}
