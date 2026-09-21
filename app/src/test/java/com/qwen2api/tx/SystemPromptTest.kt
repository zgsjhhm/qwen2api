package com.qwen2api.tx

import com.qwen2api.tx.core.ChatMessage
import com.qwen2api.tx.core.ChatResult
import com.qwen2api.tx.core.ConfigStore
import com.qwen2api.tx.core.GatewayConfig
import com.qwen2api.tx.core.MemoryConfigRepository
import com.qwen2api.tx.core.QwenClient
import com.qwen2api.tx.core.QwenEvent
import com.qwen2api.tx.core.QwenModel
import com.qwen2api.tx.server.GatewayRouter
import com.qwen2api.tx.server.MemoryFileStore
import com.qwen2api.tx.server.MiniHttpServer
import com.qwen2api.tx.core.ToolPrompt
import com.qwen2api.tx.server.SystemPromptInjector
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 全局 System Prompt 注入的回归测试。
 *
 * 分两层：
 *  1. [SystemPromptInjector] 纯函数行为（合并 / 覆盖 / 开关 / 清洗 / 限长 / 指纹影响）
 *  2. 端到端：提示词是否真的以 system 角色抵达上游，且**没有破坏**工具注入的
 *     「追加到最后一条 user 末尾」约定 —— 两者顺序搞反的话，5K+ tokens 的工具说明
 *     会被挤到提示词中间，工具调用成功率直接掉。
 */
class SystemPromptTest {

    // ==================== 1. 纯函数：注入器 ====================

    private fun cfg(
        prompt: String = "",
        enabled: Boolean = true,
        mode: String = GatewayConfig.SYSTEM_PROMPT_MERGE,
    ) = GatewayConfig(
        apiKey = "sk-test",
        systemPrompt = prompt,
        systemPromptEnabled = enabled,
        systemPromptMode = mode,
    )

    private fun msgs(vararg pairs: Pair<String, String>) =
        pairs.map { ChatMessage(it.first, it.second) }.toMutableList()

    @Test
    fun `disabled by default - no injection`() {
        val m = msgs("user" to "你好")
        val r = SystemPromptInjector.prepare(m, GatewayConfig(apiKey = "k"))
        assertFalse("默认必须不注入", r.applied)
        assertEquals(1, m.size)
        assertEquals("user", m[0].role)
    }

    @Test
    fun `enabled but blank text does not inject`() {
        val m = msgs("user" to "你好")
        val r = SystemPromptInjector.prepare(m, cfg(prompt = "   \n  "))
        assertFalse("空白提示词不应注入（否则白占上游上下文）", r.applied)
        assertEquals(1, m.size)
    }

    @Test
    fun `no existing system - prompt inserted at front`() {
        val m = msgs("user" to "你好")
        val r = SystemPromptInjector.prepare(m, cfg(prompt = "始终用中文回答"))

        assertTrue(r.applied)
        assertEquals(2, m.size)
        assertEquals("system", m[0].role)
        assertEquals("始终用中文回答", m[0].textContent())
        assertEquals("user", m[1].role)
        assertEquals("你好", m[1].textContent())
    }

    @Test
    fun `merge mode keeps client system and puts global prompt first`() {
        val m = msgs("system" to "客户端自己的角色设定", "user" to "你好")
        SystemPromptInjector.prepare(m, cfg(prompt = "全局人格"))

        assertEquals("只应有一条 system（多条会让上游拍平出多个指令区块）", 1,
            m.count { it.role == "system" })
        val sys = m.first { it.role == "system" }.textContent()
        assertEquals("全局提示词在前、客户端在后（后者更贴近本次请求，约束更强）",
            "全局人格\n\n客户端自己的角色设定", sys)
    }

    @Test
    fun `replace mode drops client system`() {
        val m = msgs("system" to "客户端自己的角色设定", "user" to "你好")
        val r = SystemPromptInjector.prepare(
            m, cfg(prompt = "全局人格", mode = GatewayConfig.SYSTEM_PROMPT_REPLACE),
        )

        assertTrue(r.applied)
        assertEquals(GatewayConfig.SYSTEM_PROMPT_REPLACE, r.mode)
        assertTrue("应记录被覆盖的字符数，便于日志解释", r.replacedChars > 0)
        val sys = m.first { it.role == "system" }.textContent()
        assertEquals("覆盖模式下客户端 system 必须消失", "全局人格", sys)
        assertFalse(sys.contains("客户端自己的角色设定"))
        assertEquals("user 内容不能被覆盖模式碰到", "你好", m.last().textContent())
    }

    @Test
    fun `replace mode does not leave empty system entries behind`() {
        val m = msgs(
            "system" to "A", "system" to "B", "user" to "你好",
        )
        SystemPromptInjector.prepare(
            m, cfg(prompt = "G", mode = GatewayConfig.SYSTEM_PROMPT_REPLACE),
        )
        assertEquals(1, m.count { it.role == "system" })
        assertEquals("G", m.first { it.role == "system" }.textContent())
        assertEquals(2, m.size)
    }

    @Test
    fun `multiple client system messages are collapsed`() {
        val m = msgs(
            "system" to "第一段", "system" to "第二段", "user" to "问题",
        )
        SystemPromptInjector.prepare(m, cfg(prompt = "全局"))

        assertEquals(1, m.count { it.role == "system" })
        val sys = m.first { it.role == "system" }.textContent()
        assertTrue(sys.contains("全局"))
        assertTrue("客户端多段 system 要按原顺序并入", sys.indexOf("第一段") < sys.indexOf("第二段"))
        assertEquals("顺序不能乱：system 必须在 user 之前", "user", m.last().role)
    }

    @Test
    fun `dirty mode value falls back to merge instead of silently not injecting`() {
        // 脏值（手工改配置 / 大小写不一致）必须收敛到 merge，而不是变成"未知模式=不注入"。
        // 否则用户看到开关是开的、提示词也在，但实际什么都没注入 —— 最难排查的一类故障。
        val m = msgs("system" to "客户端", "user" to "你好")
        SystemPromptInjector.prepare(m, cfg(prompt = "全局", mode = "REPLACE "))

        assertEquals(GatewayConfig.SYSTEM_PROMPT_REPLACE,
            ConfigStore.normalizeSystemPromptMode("REPLACE "))
        val sys = m.first { it.role == "system" }.textContent()
        assertTrue("归一化后按 replace 生效", sys == "全局")

        val m2 = msgs("system" to "客户端", "user" to "你好")
        SystemPromptInjector.prepare(m2, cfg(prompt = "全局", mode = "banana"))
        assertEquals(GatewayConfig.SYSTEM_PROMPT_MERGE,
            ConfigStore.normalizeSystemPromptMode("banana"))
        assertTrue("未知模式归一为 merge，客户端 system 仍保留",
            m2.first { it.role == "system" }.textContent().contains("客户端"))
    }

    @Test
    fun `zero width chars are stripped and newlines normalized`() {
        val dirty = "第一行\u200B含零宽\r\n第二行\uFEFF"
        val clean = ConfigStore.sanitizeSystemPrompt(dirty)
        assertFalse("零宽字符必须清掉：它们会被原样送上上游，模型看到的是隐形脏字符",
            clean.contains("\u200B") || clean.contains("\uFEFF"))
        assertEquals("第一行含零宽\n第二行", clean)
    }

    @Test
    fun `over long prompt is truncated to the hard cap`() {
        val huge = "啊".repeat(GatewayConfig.MAX_SYSTEM_PROMPT_CHARS + 500)
        val clean = ConfigStore.sanitizeSystemPrompt(huge)
        assertEquals(GatewayConfig.MAX_SYSTEM_PROMPT_CHARS, clean.length)
    }

    @Test
    fun `changing the prompt changes the session fingerprint seed`() {
        // 语义钉子：改提示词 = 换人格 = 不该续旧会话。
        // 这个副作用是刻意的（多一次上行请求换行为正确），
        // 但必须有人守着，否则以后有人"顺手优化"成恒定指纹，
        // 就会变成"改完提示词但模型行为还是旧的"这种无法解释的现象。
        val m1 = msgs("user" to "同一个问题")
        SystemPromptInjector.prepare(m1, cfg(prompt = "人格 A"))
        val m2 = msgs("user" to "同一个问题")
        SystemPromptInjector.prepare(m2, cfg(prompt = "人格 B"))

        assertNotEquals(
            SystemPromptInjector.fingerprintSeed(m1),
            SystemPromptInjector.fingerprintSeed(m2),
        )
    }

    // ==================== 2. 端到端：真实 HTTP + 假上游 ====================

    private data class Seen(val model: String, val messages: List<ChatMessage>, val thinking: Boolean)

    private val seen = ArrayList<Seen>()
    private lateinit var configRepo: MemoryConfigRepository
    private lateinit var server: MiniHttpServer
    private var port = 0

    private inner class FakeClient : QwenClient("fake-token", 0) {
        override suspend fun chatStream(
            model: String,
            messages: List<ChatMessage>,
            thinking: Boolean,
            files: List<JSONObject>,
            onEvent: (suspend (QwenEvent) -> Unit)?,
        ): ChatResult {
            seen.add(Seen(model, messages.map { it.copy() }, thinking))
            onEvent?.invoke(QwenEvent.Content("好的"))
            return ChatResult(
                chatId = "fake-chat", answer = "好的", thinking = "",
                steps = emptyList(), docs = emptyList(), queries = emptyList(),
                usage = JSONObject().put("total_tokens", 7),
                model = model, responseId = "resp-1",
            )
        }

        override suspend fun listModels(): List<QwenModel> = listOf(QwenModel("qwen3.8-max", "Max"))

        override suspend fun uploadFile(
            bytes: ByteArray,
            filename: String,
            contentType: String,
            kind: com.qwen2api.tx.core.AttachmentKind?,
        ): com.qwen2api.tx.core.UploadResult = throw UnsupportedOperationException("not used")
    }

    @Before
    fun setUp() {
        seen.clear()
        configRepo = MemoryConfigRepository()
        configRepo.update {
            it.copyWith(qwenToken = "fake-" + "token", defaultModel = "qwen3.8-max")
        }
        val router = GatewayRouter(configRepo, MemoryFileStore()) { FakeClient() }
        server = MiniHttpServer(0, "127.0.0.1") { req, res -> router.handle(req, res) }
        server.start()
        port = server.boundPort
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun chat(body: JSONObject): String {
        val conn = URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000
        conn.readTimeout = 20000
        conn.setRequestProperty("Authorization", "Bearer ${configRepo.load().apiKey}")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        conn.disconnect()
        assertEquals("请求应成功", 200, code)
        return text
    }

    private fun body(
        userText: String,
        tools: JSONArray? = null,
        systemText: String? = null,
    ): JSONObject {
        val arr = JSONArray()
        systemText?.let { arr.put(JSONObject().put("role", "system").put("content", it)) }
        arr.put(JSONObject().put("role", "user").put("content", userText))
        val b = JSONObject()
            .put("model", "qwen3.8-max").put("stream", true).put("messages", arr)
        tools?.let { b.put("tools", it).put("tool_choice", "auto") }
        return b
    }

    private fun toolsBody(vararg names: String): JSONArray {
        val arr = JSONArray()
        names.forEach { n ->
            arr.put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject().put("name", n).put("description", "$n 的说明")
                        .put("parameters", JSONObject().put("type", "object")),
                ),
            )
        }
        return arr
    }

    @Test
    fun `global prompt reaches upstream as system role`() {
        configRepo.update {
            it.copyWith(systemPrompt = "始终用简体中文回答", systemPromptEnabled = true)
        }
        chat(body("你好"))

        assertEquals(1, seen.size)
        val sys = seen[0].messages.firstOrNull { it.role == "system" }
        assertNotNull("注入的提示词必须以上游认识的 system 角色出现", sys)
        assertEquals("始终用简体中文回答", sys!!.textContent())
    }

    @Test
    fun `client system and global prompt are merged in one message`() {
        configRepo.update {
            it.copyWith(systemPrompt = "全局人格", systemPromptEnabled = true)
        }
        chat(body("你好", systemText = "客户端角色"))

        val systems = seen[0].messages.filter { it.role == "system" }
        assertEquals("多条 system 会被上游拍平成多个指令区块，必须收敛成一条", 1, systems.size)
        assertTrue(systems[0].textContent().contains("全局人格"))
        assertTrue(systems[0].textContent().contains("客户端角色"))
    }

    @Test
    fun `replace mode wins over client system end to end`() {
        configRepo.update {
            it.copyWith(
                systemPrompt = "全局人格", systemPromptEnabled = true,
                systemPromptMode = GatewayConfig.SYSTEM_PROMPT_REPLACE,
            )
        }
        chat(body("你好", systemText = "客户端角色"))

        val sys = seen[0].messages.first { it.role == "system" }.textContent()
        assertEquals("全局人格", sys)
    }

    @Test
    fun `disabled global prompt leaves request untouched`() {
        chat(body("你好", systemText = "客户端角色"))

        assertEquals(1, seen[0].messages.count { it.role == "system" })
        assertEquals("客户端角色", seen[0].messages.first { it.role == "system" }.textContent())
    }

    @Test
    fun `tool instructions stay glued to the last user message when system prompt is on`() {
        // 这条是本功能最容易踩坏的地方：注入顺序若反了，工具说明会被夹在
        // 「全局提示词 / 用户正文」之间，或者用户正文后面又跟一段 system，
        // 上游拍平后工具说明离当前问题很远，工具调用成功率明显下降。
        configRepo.update {
            it.copyWith(systemPrompt = "全局人格", systemPromptEnabled = true)
        }
        chat(body("帮我查天气", tools = toolsBody("get_weather")))

        val msgs = seen[0].messages
        val sys = msgs.first { it.role == "system" }.textContent()
        assertEquals("system 只应承载人格，不应混入工具说明", "全局人格", sys)

        val user = msgs.last { it.role == "user" }.textContent()
        assertTrue("用户正文必须在最前", user.startsWith("帮我查天气"))
        assertTrue("工具说明必须仍追加在最后一条 user 末尾", user.contains("get_weather"))
        assertTrue(
            "工具说明必须仍用约定的分隔符（sessionKey 靠它剥离注入）",
            user.contains("\n\n---\n\n"),
        )
        assertFalse("user 里不应再出现人格文本", user.contains("全局人格"))
    }

    @Test
    fun `admin settings round trip persists prompt through the api`() {
        val conn = URL("http://127.0.0.1:$port/admin/api/settings").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        val payload = JSONObject()
            .put("systemPrompt", "来自 admin 的提示词\u200B")
            .put("systemPromptEnabled", true)
            .put("systemPromptMode", "replace")
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        assertEquals(200, conn.responseCode)
        conn.disconnect()

        val saved = configRepo.load()
        assertTrue(saved.systemPromptEnabled)
        assertEquals(GatewayConfig.SYSTEM_PROMPT_REPLACE, saved.systemPromptMode)
        assertEquals("admin 写入也必须过清洗（零宽字符要清掉）", "来自 admin 的提示词", saved.systemPrompt)

        // 写完立刻生效于下一次对话
        seen.clear()
        chat(body("你好", systemText = "客户端角色"))
        assertEquals("来自 admin 的提示词",
            seen[0].messages.first { it.role == "system" }.textContent())
    }

    @Test
    fun `writing non empty text auto enables and blank text keeps the switch`() {
        // 联动语义：写文本 = 想启用（省一次请求）；清空文本不动开关（
        // 否则"清空输入框"会被误解成"关掉功能"，再写回去发现没生效）。
        configRepo.update { it.copyWith(systemPromptEnabled = false) }

        postSettings(JSONObject().put("systemPrompt", "新提示词"))
        assertTrue("写入非空文本应自动启用", configRepo.load().systemPromptEnabled)

        postSettings(JSONObject().put("systemPrompt", ""))
        assertTrue("清空文本不应顺手关掉开关", configRepo.load().systemPromptEnabled)
        assertEquals("", configRepo.load().systemPrompt)

        postSettings(JSONObject().put("systemPromptEnabled", false))
        assertFalse("显式关开关必须生效", configRepo.load().systemPromptEnabled)
    }

    @Test
    fun `admin status exposes prompt metadata but never the body`() {
        configRepo.update {
            it.copyWith(systemPrompt = "内部业务规则不该外泄", systemPromptEnabled = true)
        }
        val conn = URL("http://127.0.0.1:$port/admin/api/status").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        assertEquals(200, conn.responseCode)
        val text = conn.inputStream.bufferedReader().use(BufferedReader::readText)
        conn.disconnect()

        val o = JSONObject(text)
        assertTrue(o.getBoolean("systemPromptEnabled"))
        assertEquals("内部业务规则不该外泄".length, o.getInt("systemPromptChars"))
        assertFalse(
            "status 是免鉴权（仅限本机）的轮询接口，不能回提示词正文",
            text.contains("内部业务规则不该外泄"),
        )
    }

    private fun postSettings(payload: JSONObject) {
        val conn = URL("http://127.0.0.1:$port/admin/api/settings").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        assertEquals(200, conn.responseCode)
        conn.disconnect()
    }
}
