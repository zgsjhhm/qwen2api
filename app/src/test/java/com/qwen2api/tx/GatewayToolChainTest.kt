package com.qwen2api.tx

import com.qwen2api.tx.core.ChatMessage
import com.qwen2api.tx.core.ChatResult
import com.qwen2api.tx.core.GatewayConfig
import com.qwen2api.tx.core.MemoryConfigRepository
import com.qwen2api.tx.core.QwenClient
import com.qwen2api.tx.core.QwenEvent
import com.qwen2api.tx.core.QwenModel
import com.qwen2api.tx.core.ToolPrompt
import com.qwen2api.tx.server.GatewayRouter
import com.qwen2api.tx.server.MemoryFileStore
import com.qwen2api.tx.server.MiniHttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 工具调用链路的端到端测试（真实 HTTP + 真实 SSE 分片）。
 *
 * ## 为什么必须补这一层
 * [ToolCallTest] 覆盖的是纯函数（提示词生成、解析、缓冲），
 * [GatewayE2ETest] 覆盖的是网关的非工具路径（鉴权/错误/文件/管理接口），
 * 两者之间**整条工具链路没有任何回归保护**：
 *  - 请求侧：tools 定义是否真的被注入到最后一条 user 消息
 *  - 响应侧：解析出的调用是否真的按 OpenAI 规范分片发成 SSE
 *  - 降级：模型没按格式输出时，正文是否完整还给客户端（而不是凭空消失）
 *
 * 这里用一个假的 [QwenClient] 替换上游，让链路可以**离线、确定性**地跑完。
 *
 * 注意：ToolInjector 注入的分隔符 `\n\n---\n\n` 是 QwenClient.sessionKey
 * 剥离逻辑的依赖项，本测试顺带把它钉住（改动会立刻红）。
 */
class GatewayToolChainTest {

    private lateinit var configRepo: MemoryConfigRepository
    private lateinit var fileStore: com.qwen2api.tx.core.FileStore
    private lateinit var server: MiniHttpServer
    private var port: Int = 0

    // ---------------- 假上游 ----------------

    /** 记录一次 chatStream 收到的请求，供断言注入行为 */
    private data class Seen(
        val model: String,
        val messages: List<ChatMessage>,
        val thinking: Boolean,
    )

    private val seen = ArrayList<Seen>()

    /**
     * 假上游：按脚本依次吐出各轮输出。
     *
     * @param scripts 每轮要下发的内容分片；索引 = 第几次 chatStream 调用
     *                （工具链路的「格式跑偏重试」会调用多次，用轮次区分）
     */
    private inner class FakeClient(
        private val scripts: List<List<String>>,
        private val throwOn: List<Throwable?> = emptyList(),
    ) : QwenClient("fake-token", 0) {

        override suspend fun chatStream(
            model: String,
            messages: List<ChatMessage>,
            thinking: Boolean,
            files: List<JSONObject>,
            onEvent: (suspend (QwenEvent) -> Unit)?,
        ): ChatResult {
            val idx = seen.size
            seen.add(Seen(model, messages.map { it.copy() }, thinking))
            throwOn.getOrNull(idx)?.let { throw it }

            val chunks = scripts.getOrElse(idx) { emptyList() }
            val answer = StringBuilder()
            chunks.forEach { c ->
                answer.append(c)
                onEvent?.invoke(QwenEvent.Content(c))
            }
            return ChatResult(
                chatId = "fake-chat", answer = answer.toString(), thinking = "",
                steps = emptyList(), docs = emptyList(), queries = emptyList(),
                usage = JSONObject().put("total_tokens", 42),
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

    private fun startServer(scripts: List<List<String>>, throwOn: List<Throwable?> = emptyList()) {
        configRepo = MemoryConfigRepository()
        configRepo.update { it.copyWith(qwenToken = "fake-token", defaultModel = "qwen3.8-max") }
        fileStore = MemoryFileStore()
        val router = GatewayRouter(configRepo, fileStore) { FakeClient(scripts, throwOn) }
        server = MiniHttpServer(0, "127.0.0.1") { req, res -> router.handle(req, res) }
        server.start()
        port = server.boundPort
    }

    @Before
    fun setUp() {
        startServer(emptyList())
    }

    @After
    fun tearDown() {
        server.stop()
    }

    // ---------------- HTTP 辅助 ----------------

    private fun key(): String = configRepo.load().apiKey

    /** 发一次 /v1/chat/completions 请求，返回原始响应体（SSE 文本或 JSON） */
    private fun chat(body: String): String {
        val conn = URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000
        conn.readTimeout = 20000
        conn.setRequestProperty("Authorization", "Bearer ${key()}")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        conn.disconnect()
        assertEquals("请求应成功", 200, code)
        return text
    }

    /** 从 SSE 文本里取出所有 data: 帧（已解析为 JSONObject，[DONE] 跳过） */
    private fun frames(sse: String): List<JSONObject> =
        sse.lineSequence()
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.isNotEmpty() && it != "[DONE]" }
            .map { JSONObject(it) }
            .toList()

    /** 从 SSE 帧里取出 delta 对象（tool_calls / content 都在这一层） */
    private fun delta(f: JSONObject): JSONObject? =
        f.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")

    /** 从 SSE 帧里取 content 增量 */
    private fun contentOf(f: JSONObject): String =
        delta(f)?.optString("content").orEmpty()

    /** 从 SSE 帧里取 tool_calls 数组 */
    private fun toolCallsOf(f: JSONObject): JSONArray? = delta(f)?.optJSONArray("tool_calls")

    private fun toolName(f: JSONObject): String? = toolCallsOf(f)
        ?.optJSONObject(0)?.optJSONObject("function")?.optString("name")

    private fun argPieces(f: JSONObject): List<String> {
        val arr = toolCallsOf(f) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optJSONObject("function")?.optString("arguments")
        }
    }

    /** 把 SSE 里所有 content 增量拼成完整正文 */
    private fun contentAll(fs: List<JSONObject>): String =
        fs.mapNotNull { contentOf(it).takeIf { c -> c.isNotEmpty() } }.joinToString("")

    /**
     * 拼回客户端视角的完整 tool_calls（按 index 累积 name/arguments）。
     * 返回 `index -> (name, arguments)`，按 index 升序。
     */
    private fun assembleToolCalls(fs: List<JSONObject>): List<Pair<Int, Pair<String, String>>> {
        val names = LinkedHashMap<Int, String>()
        val args = LinkedHashMap<Int, StringBuilder>()
        fs.forEach { f ->
            val arr = toolCallsOf(f) ?: return@forEach
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val idx = c.optInt("index", i)
                c.optJSONObject("function")?.let { fn ->
                    fn.optString("name").takeIf { it.isNotEmpty() }?.let { names[idx] = it }
                    fn.optString("arguments").takeIf { it.isNotEmpty() }?.let {
                        args.getOrPut(idx) { StringBuilder() }.append(it)
                    }
                }
            }
        }
        return names.keys.sorted().map { it to (names[it]!! to args[it]?.toString().orEmpty()) }
    }

    private fun toolsBody(vararg names: String, toolChoice: String = "auto"): JSONArray {
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

    /** 构造一次带 tools 的流式请求体 */
    private fun streamReq(
        userText: String,
        tools: JSONArray?,
        extra: JSONObject = JSONObject(),
    ): String {
        val body = JSONObject()
            .put("model", "qwen3.8-max")
            .put("stream", true)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", userText)))
        tools?.let { body.put("tools", it).put("tool_choice", "auto") }
        extra.keys().forEach { body.put(it, extra.opt(it)) }
        return body.toString()
    }

    private fun fence(json: String) = "${ToolPrompt.FENCE}\n$json\n${ToolPrompt.FENCE_END}"

    // ---------------- 1. 请求侧：注入 ----------------

    @Test
    fun `tools definition is injected into last user message`() {
        startServer(listOf(listOf("好的")))
        chat(streamReq("帮我查天气", toolsBody("get_weather")))

        assertEquals(1, seen.size)
        val injected = seen[0].messages.last { it.role == "user" }.textContent()
        assertTrue("应包含工具名", injected.contains("get_weather"))
        assertTrue("应包含工具说明", injected.contains("get_weather 的说明"))
        assertTrue("应保留原始用户文本", injected.startsWith("帮我查天气"))
        assertTrue(
            "必须用约定的分隔符（QwenClient.sessionKey 靠它剥离注入，改了会破坏会话续接）",
            injected.contains("\n\n---\n\n"),
        )
    }

    @Test
    fun `no tools means no injection`() {
        startServer(listOf(listOf("你好")))
        chat(streamReq("你好", null))
        val text = seen[0].messages.last { it.role == "user" }.textContent()
        assertFalse("未传 tools 时不应注入任何工具说明", text.contains("---"))
    }

    @Test
    fun `tool_choice none suppresses injection`() {
        startServer(listOf(listOf("你好")))
        val body = JSONObject()
            .put("model", "qwen3.8-max").put("stream", true)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "你好")))
            .put("tools", toolsBody("get_weather"))
            .put("tool_choice", "none")
        chat(body.toString())
        val text = seen[0].messages.last { it.role == "user" }.textContent()
        assertFalse("tool_choice=none 时不应注入", text.contains("---"))
    }

    @Test
    fun `pruned tools are absent from the injected instructions`() {
        // 普通对话（无 apk 关键词）应裁掉 apk 组，注入文本里不能出现这些工具名
        startServer(listOf(listOf("好的")))
        chat(streamReq("帮我写个快排", toolsBody("mcp__MTmcp__mt_apk_manifest", "get_weather")))
        val injected = seen[0].messages.last { it.role == "user" }.textContent()
        assertTrue("保留的工具应在", injected.contains("get_weather"))
        assertFalse("被裁剪的工具不应出现", injected.contains("mt_apk_manifest"))
    }

    @Test
    fun `apk keyword keeps apk tools in instructions`() {
        startServer(listOf(listOf("好的")))
        chat(streamReq("帮我分析这个 apk", toolsBody("mcp__MTmcp__mt_apk_manifest", "get_weather")))
        val injected = seen[0].messages.last { it.role == "user" }.textContent()
        assertTrue("命中场景时必须保留 apk 工具", injected.contains("mt_apk_manifest"))
    }

    // ---------------- 2. 响应侧：SSE tool_calls 分片 ----------------

    @Test
    fun `tool call becomes openai tool_calls sse frame`() {
        val modelOut = fence("""{"name":"get_weather","arguments":{"city":"北京"}}""")
        startServer(listOf(listOf(modelOut)))
        val fs = frames(chat(streamReq("北京天气", toolsBody("get_weather"))))

        val calls = assembleToolCalls(fs)
        assertEquals(1, calls.size)
        assertEquals("get_weather", calls[0].second.first)
        assertEquals("""{"city":"北京"}""", calls[0].second.second)

        val finish = fs.last().optJSONArray("choices")!!.optJSONObject(0)!!
        assertEquals("tool_calls", finish.optString("finish_reason"))
    }

    @Test
    fun `tool call id uses call prefix for sdk compatibility`() {
        startServer(listOf(listOf(fence("""{"name":"get_weather","arguments":{}}"""))))
        val fs = frames(chat(streamReq("天气", toolsBody("get_weather"))))
        val id = fs.firstNotNullOfOrNull { f ->
            toolCallsOf(f)?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotEmpty() }
        }
        assertNotNull("必须下发 tool_call id", id)
        assertTrue("LangChain/OpenAI SDK 要求 call_ 前缀", id!!.startsWith("call_"))
    }

    @Test
    fun `first frame carries name and later frames carry arguments pieces`() {
        // 造一个超过 256 字符的参数，验证分片下发与客户端累积的一致性
        val bigCity = "x".repeat(400)
        val modelOut = fence("""{"name":"get_weather","arguments":{"city":"$bigCity"}}""")
        startServer(listOf(listOf(modelOut)))
        val fs = frames(chat(streamReq("天气", toolsBody("get_weather"))))

        val callFrames = fs.filter { (toolCallsOf(it)?.length() ?: 0) > 0 }
        assertTrue("至少要有首帧 + 多个参数分片", callFrames.size > 2)

        val args = argPieces(callFrames[0]).firstOrNull().orEmpty()
        assertEquals("首帧必须是空 arguments（OpenAI 规范）", "", args)

        val pieces = callFrames.drop(1).mapNotNull { argPieces(it).firstOrNull() }
        assertTrue("参数应分多片下发", pieces.size >= 2)
        pieces.forEach { assertTrue("每片不超过 256 字符", it.length <= 256) }
        assertEquals("分片拼回应与模型输出一致", """{"city":"$bigCity"}""", pieces.joinToString(""))
    }

    @Test
    fun `multiple tool calls are emitted with distinct indexes`() {
        val out = fence("""{"name":"get_weather","arguments":{"city":"北京"}}""") + "\n" +
            fence("""{"name":"get_time","arguments":{"zone":"Asia/Shanghai"}}""")
        startServer(listOf(listOf(out)))
        val fs = frames(chat(streamReq("天气和时间", toolsBody("get_weather", "get_time"))))
        val calls = assembleToolCalls(fs)
        assertEquals(2, calls.size)
        assertEquals(listOf("get_weather", "get_time"), calls.map { it.second.first })
        assertEquals(setOf(0, 1), calls.map { it.first }.toSet())
    }

    @Test
    fun `content gets a human readable notice when no text was produced`() {
        // 不解析 tool_calls 的客户端若 content 为空会表现为「没有任何回应」
        startServer(listOf(listOf(fence("""{"name":"get_weather","arguments":{}}"""))))
        val fs = frames(chat(streamReq("天气", toolsBody("get_weather"))))
        val content = contentAll(fs)
        assertTrue("应给出可见的自然语言反馈", content.contains("get_weather"))
    }

    // ---------------- 3. 工具名校验与幻觉剔除 ----------------

    @Test
    fun `hallucinated tool name is dropped and degrades to plain text`() {
        startServer(listOf(listOf(fence("""{"name":"no_such_tool","arguments":{}}"""))))
        val fs = frames(chat(streamReq("天气", toolsBody("get_weather"))))
        assertTrue("不在允许列表里的工具必须丢弃", assembleToolCalls(fs).isEmpty())
        val finish = fs.last().optJSONArray("choices")!!.optJSONObject(0)!!
        assertEquals("丢弃后应降级为普通回答", "stop", finish.optString("finish_reason"))
    }

    @Test
    fun `model name without prefix is corrected to registered name`() {
        // 模型常丢掉 mcp__xxx__ 前缀；校正后仍应能正常调用
        startServer(
            listOf(listOf(fence("""{"name":"mt_file_read","arguments":{"path":"a.txt"}}"""))),
        )
        val fs = frames(chat(streamReq("读文件", toolsBody("mcp__MTmcp__mt_file_read"))))
        val calls = assembleToolCalls(fs)
        assertEquals(1, calls.size)
        assertEquals("应校正为注册名", "mcp__MTmcp__mt_file_read", calls[0].second.first)
    }

    // ---------------- 4. 降级：模型没按格式输出 ----------------

    @Test
    fun `plain answer passes through when model ignores tool format`() {
        startServer(listOf(listOf("北京今天晴，26 度。")))
        val fs = frames(chat(streamReq("北京天气", toolsBody("get_weather"))))
        assertEquals("北京今天晴，26 度。", contentAll(fs))
        assertTrue("没有工具调用时不应产生 tool_calls", assembleToolCalls(fs).isEmpty())
    }

    @Test
    fun `normal code fence is not swallowed by tool buffer`() {
        val modelOut = "示例代码：\n```kotlin\nfun main() {}\n```\n完毕"
        startServer(listOf(listOf(modelOut)))
        val fs = frames(chat(streamReq("给我个例子", toolsBody("get_weather"))))
        val content = contentAll(fs)
        assertTrue("普通代码块必须原样返回", content.contains("fun main()"))
        assertTrue("结尾正文也必须保留", content.contains("完毕"))
    }

    @Test
    fun `unclosed tool fence content is not lost`() {
        // 模型被截断（最后一轮）：围栏未闭合，累积到的内容不能凭空消失。
        // 注：流中途的未闭合围栏会被下一段 chunk 继续累积，所以只有「流结束」
        // 时的 flushRemaining 才会真正决定这段文本的去留。
        val modelOut = "前文\n${ToolPrompt.FENCE}\n{\"name\":\"get_weather\",\"arguments\":{}}"
        startServer(listOf(listOf(modelOut)))
        val fs = frames(chat(streamReq("天气", toolsBody("get_weather"))))
        val content = contentAll(fs)
        assertTrue("前缀正文应保留", content.contains("前文"))
        // 未闭合时模型其实已经给出了完整调用信息（只是忘了写结束围栏），
        // flushRemaining 会尝试解析一次，解析成功即转成 tool_calls。
        val calls = assembleToolCalls(fs)
        assertTrue(
            "内容要么作为正文返回、要么被解析成 tool_calls，两者都不能丢",
            content.contains("get_weather") || calls.isNotEmpty(),
        )
    }

    // ---------------- 5. 重试链路 ----------------

    @Test
    fun `format deviation triggers one retry and reports the retry result`() {
        // 第 1 轮：裸 JSON（高置信「想调工具但格式跑偏」）-> 应重试
        // 第 2 轮：正确围栏 -> 应直接采用
        val bad = """{"name":"get_weather","arguments":{"city":"上海"}}"""
        val good = fence("""{"name":"get_weather","arguments":{"city":"上海"}}""")
        startServer(listOf(listOf(bad), listOf(good)))
        val fs = frames(chat(streamReq("上海天气", toolsBody("get_weather"))))

        assertEquals("应恰好重试一次", 2, seen.size)
        val calls = assembleToolCalls(fs)
        assertEquals(1, calls.size)
        assertEquals("get_weather", calls[0].second.first)
    }

    @Test
    fun `natural language plan does not trigger retry`() {
        // 回归点：早期版本因正文含「调用工具」就重试，导致复杂任务反复重试
        startServer(listOf(listOf("我需要调用工具来克隆仓库，先规划一下步骤。")))
        chat(streamReq("移植项目", toolsBody("get_weather")))
        assertEquals("自然语言计划不应触发重试", 1, seen.size)
    }

    @Test
    fun `no retry when tools are not enabled`() {
        startServer(listOf(listOf("""{"name":"get_weather","arguments":{}}""")))
        chat(streamReq("你好", null))
        assertEquals("未启用工具时不应重试", 1, seen.size)
    }

    @Test
    fun `failed attempt text is not leaked to client`() {
        // 失败轮的半截文本不能泄漏；最终只应看到成功轮的内容
        val bad = "先说明一下：\n{\"name\":\"get_weather\",\"arguments\":{\"city\":\"广州\"}}"
        val good = "已为你查询。" + fence("""{"name":"get_weather","arguments":{"city":"广州"}}""")
        startServer(listOf(listOf(bad), listOf(good)))
        val fs = frames(chat(streamReq("广州天气", toolsBody("get_weather"))))
        val content = contentAll(fs)
        assertTrue("成功轮正文应在", content.contains("已为你查询"))
        assertFalse("失败轮文本不应泄漏", content.contains("先说明一下"))
    }

    // ---------------- 6. 非流式一致性 ----------------

    @Test
    fun `non stream returns tool_calls in message and finish_reason`() {
        val out = fence("""{"name":"get_weather","arguments":{"city":"深圳"}}""")
        startServer(listOf(listOf(out)))
        val body = JSONObject()
            .put("model", "qwen3.8-max").put("stream", false)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "深圳天气")))
            .put("tools", toolsBody("get_weather"))
            .put("tool_choice", "auto")
        val j = JSONObject(chat(body.toString()))

        val choice = j.optJSONArray("choices")!!.optJSONObject(0)!!
        assertEquals("tool_calls", choice.optString("finish_reason"))
        val msg = choice.optJSONObject("message")!!
        val call = msg.optJSONArray("tool_calls")!!.optJSONObject(0)!!
        assertEquals("get_weather", call.optJSONObject("function")!!.optString("name"))
        assertEquals(
            """{"city":"深圳"}""",
            call.optJSONObject("function")!!.optString("arguments"),
        )
        assertTrue("不解析 tool_calls 的客户端也要有可见反馈", msg.optString("content").isNotEmpty())
    }

    @Test
    fun `non stream keeps content when no tool call parsed`() {
        startServer(listOf(listOf("今天晴。")))
        val body = JSONObject()
            .put("model", "qwen3.8-max").put("stream", false)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "天气")))
            .put("tools", toolsBody("get_weather"))
            .put("tool_choice", "auto")
        val j = JSONObject(chat(body.toString()))
        val choice = j.optJSONArray("choices")!!.optJSONObject(0)!!
        assertEquals("stop", choice.optString("finish_reason"))
        assertEquals("今天晴。", choice.optJSONObject("message")!!.optString("content"))
        assertEquals(0, choice.optJSONObject("message")!!.optJSONArray("tool_calls")?.length() ?: 0)
    }

    // ---------------- 7. 多轮：tool 结果回灌 ----------------

    @Test
    fun `tool result message is converted to upstream readable text`() {
        startServer(listOf(listOf("好的")))
        val body = JSONObject()
            .put("model", "qwen3.8-max").put("stream", true)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "user").put("content", "北京天气"))
                    .put(
                        JSONObject().put("role", "assistant").put("content", "")
                            .put(
                                "tool_calls",
                                JSONArray().put(
                                    JSONObject().put("id", "call_1").put("type", "function")
                                        .put(
                                            "function",
                                            JSONObject().put("name", "get_weather")
                                                .put("arguments", """{"city":"北京"}"""),
                                        ),
                                ),
                            ),
                    )
                    .put(
                        JSONObject().put("role", "tool").put("tool_call_id", "call_1")
                            .put("name", "get_weather").put("content", """{"temp":26}"""),
                    ),
            )
            .put("tools", toolsBody("get_weather"))
            .put("tool_choice", "auto")
        chat(body.toString())

        // 上游只认 user/assistant，且工具历史要被还原成可读文本
        val sent = seen[0].messages
        assertTrue("上游不应收到 role=tool", sent.none { it.role == "tool" })
        val joined = sent.joinToString("\n") { it.textContent() }
        assertTrue("工具结果应带标注", joined.contains("[工具执行结果]"))
        assertTrue("工具名应保留", joined.contains("get_weather"))
        assertTrue("结果内容应保留", joined.contains("temp"))
        assertTrue(
            "assistant 的 tool_calls 应还原成 fence 以维持 few-shot",
            joined.contains(ToolPrompt.FENCE),
        )
    }
}
