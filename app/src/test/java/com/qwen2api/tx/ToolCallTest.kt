package com.qwen2api.tx

import com.qwen2api.tx.core.ChatMessage
import com.qwen2api.tx.core.ToolCallStreamBuffer
import com.qwen2api.tx.core.ToolMessageCodec
import com.qwen2api.tx.core.ToolPrompt
import com.qwen2api.tx.server.GatewayRouter
import com.qwen2api.tx.server.ToolInjector
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方案 B（提示词工程模拟工具调用）的核心逻辑测试。
 *
 * 覆盖三层：
 *  1. ToolPrompt       —— 工具定义 -> 提示词；模型输出 -> 工具调用解析
 *  2. ToolCallStreamBuffer —— 流式缓冲状态机（分片、误判、截断）
 *  3. ToolInjector / ToolMessageCodec —— 请求注入与多轮消息转换
 */
class ToolCallTest {

    private fun tools(vararg names: String): JSONArray {
        val arr = JSONArray()
        names.forEach { n ->
            arr.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", n)
                            .put("description", "$n 的说明")
                            .put(
                                "parameters",
                                JSONObject()
                                    .put("type", "object")
                                    .put(
                                        "properties",
                                        JSONObject().put("city", JSONObject().put("type", "string")),
                                    )
                                    .put("required", JSONArray().put("city")),
                            ),
                    ),
            )
        }
        return arr
    }

    private fun fence(json: String) = "${ToolPrompt.FENCE}\n$json\n${ToolPrompt.FENCE_END}"

    // ---------------- 1. ToolPrompt：提示词生成 ----------------

    @Test
    fun `instructions include tool name description and schema`() {
        val p = ToolPrompt.buildInstructions(tools("get_weather"), "auto")
        assertNotNull(p)
        assertTrue(p!!.contains("get_weather"))
        assertTrue("应包含描述", p.contains("get_weather 的说明"))
        assertTrue("应包含参数 schema", p.contains("JSON Schema"))
        assertTrue("应包含格式围栏标记", p.contains(ToolPrompt.FENCE))
        assertTrue("应包含调用规则", p.contains("调用规则"))
    }

    @Test
    fun `instructions return null when no tools`() {
        assertNull(ToolPrompt.buildInstructions(null, null))
        assertNull(ToolPrompt.buildInstructions(JSONArray(), null))
    }

    @Test
    fun `instructions return null when tool_choice is none`() {
        assertNull(ToolPrompt.buildInstructions(tools("get_weather"), "none"))
        assertNull(ToolPrompt.buildInstructions(tools("get_weather"), "NONE"))
    }

    @Test
    fun `forced tool_choice names the function explicitly`() {
        val choice = JSONObject()
            .put("type", "function")
            .put("function", JSONObject().put("name", "get_weather"))
        val p = ToolPrompt.buildInstructions(tools("get_weather", "get_time"), choice)
        assertNotNull(p)
        assertTrue("应强调必须调用指定工具", p!!.contains("必须"))
        assertTrue(p.contains("get_weather"))
        assertEquals("get_weather", ToolPrompt.forcedFunctionName(choice))
        assertNull(ToolPrompt.forcedFunctionName("auto"))
    }

    @Test
    fun `multiple tools are all listed`() {
        val p = ToolPrompt.buildInstructions(tools("get_weather", "get_time", "search"), "auto")!!
        assertTrue(p.contains("get_weather"))
        assertTrue(p.contains("get_time"))
        assertTrue(p.contains("search"))
    }

    @Test
    fun `english instructions when locale is en`() {
        val p = ToolPrompt.buildInstructions(tools("get_weather"), "auto", locale = "en")!!
        assertTrue(p.contains("Available tools"))
        assertTrue(p.contains("Rules"))
    }

    // ---------------- 2. ToolPrompt：输出解析 ----------------

    @Test
    fun `parse single tool call with object arguments`() {
        val text = fence("""{"name":"get_weather","arguments":{"city":"北京"}}""")
        val r = ToolPrompt.parse(text)
        assertEquals(1, r.calls.size)
        assertEquals("get_weather", r.calls[0].name)
        assertEquals("""{"city":"北京"}""", r.calls[0].argumentsJson)
        assertTrue("剩余正文应为空", r.remainingText.isBlank())
        assertTrue(r.sawFence)
    }

    @Test
    fun `parse keeps surrounding prose as remaining text`() {
        val text = "让我查一下。\n\n" +
            fence("""{"name":"get_weather","arguments":{"city":"上海"}}""") +
            "\n\n请稍等"
        val r = ToolPrompt.parse(text)
        assertEquals(1, r.calls.size)
        assertTrue(r.remainingText.contains("让我查一下"))
        assertTrue(r.remainingText.contains("请稍等"))
        assertFalse("围栏内容不应留在正文里", r.remainingText.contains("get_weather"))
    }

    @Test
    fun `parse multiple tool calls in sequence`() {
        val text = fence("""{"name":"get_weather","arguments":{"city":"北京"}}""") +
            "\n" + fence("""{"name":"get_time","arguments":{"city":"北京"}}""")
        val r = ToolPrompt.parse(text)
        assertEquals(2, r.calls.size)
        assertEquals("get_weather", r.calls[0].name)
        assertEquals("get_time", r.calls[1].name)
    }

    @Test
    fun `parse accepts arguments as json string`() {
        // 部分模型会把 arguments 输出成字符串
        val text = fence("""{"name":"get_weather","arguments":"{\"city\":\"北京\"}"}""")
        val r = ToolPrompt.parse(text)
        assertEquals(1, r.calls.size)
        assertEquals("""{"city":"北京"}""", r.calls[0].argumentsJson)
    }

    @Test
    fun `parse accepts openai nested function form`() {
        val text = fence("""{"type":"function","function":{"name":"get_weather","arguments":{"city":"北京"}}}""")
        val r = ToolPrompt.parse(text)
        assertEquals(1, r.calls.size)
        assertEquals("get_weather", r.calls[0].name)
    }

    @Test
    fun `parse accepts wrapped tool_calls form`() {
        // 模型自作主张包了一层
        val text = fence("""{"tool_calls":[{"name":"get_weather","arguments":{"city":"北京"}}]}""")
        val r = ToolPrompt.parse(text)
        assertEquals(1, r.calls.size)
        assertEquals("get_weather", r.calls[0].name)
    }

    @Test
    fun `parse handles missing arguments as empty object`() {
        val r = ToolPrompt.parse(fence("""{"name":"get_weather"}"""))
        assertEquals(1, r.calls.size)
        assertEquals("{}", r.calls[0].argumentsJson)
    }

    @Test
    fun `parse skips malformed json without failing whole parse`() {
        val text = fence("{not valid json") + "\n" +
            fence("""{"name":"get_weather","arguments":{"city":"北京"}}""")
        val r = ToolPrompt.parse(text)
        assertEquals("坏块应被跳过，好块仍要解析出来", 1, r.calls.size)
        assertEquals("get_weather", r.calls[0].name)
    }

    @Test
    fun `parse returns empty when no fence present`() {
        val r = ToolPrompt.parse("今天天气不错，北京晴。")
        assertEquals(0, r.calls.size)
        assertEquals("今天天气不错，北京晴。", r.remainingText)
        assertFalse(r.sawFence)
    }

    @Test
    fun `parse keeps unclosed fence as text`() {
        val text = "前缀" + ToolPrompt.FENCE + "\n{\"name\":\"get_weather\""
        val r = ToolPrompt.parse(text)
        assertEquals(0, r.calls.size)
        assertTrue(r.remainingText.contains("前缀"))
        assertTrue("未闭合内容不应丢失", r.remainingText.contains("get_weather"))
    }

    @Test
    fun `parse handles normal markdown code block as text`() {
        // 普通代码块不是工具调用，必须原样保留（否则会吃掉正文）
        val text = "示例代码：\n```kotlin\nfun main() {}\n```\n完毕"
        val r = ToolPrompt.parse(text)
        assertEquals(0, r.calls.size)
        assertTrue(r.remainingText.contains("fun main()"))
        assertTrue(r.remainingText.contains("完毕"))
    }

    @Test
    fun `tool call ids use call prefix`() {
        val id = ToolPrompt.newToolCallId()
        assertTrue("客户端普遍校验 call_ 前缀: $id", id.startsWith("call_"))
        assertTrue(id.length > 10)
    }

    @Test
    fun `mayBeFormingFence detects partial fence`() {
        assertTrue(ToolPrompt.mayBeFormingFence("`"))
        assertTrue(ToolPrompt.mayBeFormingFence("``"))
        assertTrue(ToolPrompt.mayBeFormingFence("```to"))
        assertTrue(ToolPrompt.mayBeFormingFence("```tool_ca"))
        assertFalse(ToolPrompt.mayBeFormingFence("普通文字"))
        assertFalse(ToolPrompt.mayBeFormingFence(""))
    }

    // ---------------- 3. 流式缓冲状态机 ----------------

    @Test
    fun `buffer passes plain text through immediately`() {
        val b = ToolCallStreamBuffer()
        assertEquals("你好", b.feed("你好"))
        assertEquals("世界", b.feed("世界"))
        assertFalse(b.sawToolCall)
        assertFalse(b.isBuffering())
    }

    @Test
    fun `buffer swallows tool call fence split across chunks`() {
        val b = ToolCallStreamBuffer()
        val json = """{"name":"get_weather","arguments":{"city":"北京"}}"""
        // 把 fence + json 拆成很多小片，模拟真实 token 流
        val whole = fence(json)
        val out = StringBuilder()
        whole.chunked(3).forEach { out.append(b.feed(it)) }
        out.append(b.flushRemaining())

        assertTrue("应识别出工具调用", b.sawToolCall)
        assertEquals(1, b.calls.size)
        assertEquals("get_weather", b.calls[0].name)
        assertEquals("碎片不应泄漏到正文", "", out.toString())
    }

    @Test
    fun `buffer emits text before fence and suppresses fence body`() {
        val b = ToolCallStreamBuffer()
        val out = StringBuilder()
        val whole = "正在查询。" + fence("""{"name":"get_weather","arguments":{"city":"北京"}}""") + "完成"
        whole.chunked(4).forEach { out.append(b.feed(it)) }
        out.append(b.flushRemaining())

        assertTrue(b.sawToolCall)
        val text = out.toString()
        assertTrue("围栏前正文应保留", text.contains("正在查询"))
        assertTrue("围栏后正文应保留", text.contains("完成"))
        assertFalse("围栏内容不应出现", text.contains("get_weather"))
    }

    @Test
    fun `buffer does not misjudge plain backticks as fence`() {
        val b = ToolCallStreamBuffer()
        val out = StringBuilder()
        // 普通行内代码：反引号后跟的是别的字符，不是 tool_call
        "这是 `code` 和 ``双反引号`` 测试".chunked(2).forEach { out.append(b.feed(it)) }
        out.append(b.flushRemaining())
        assertFalse(b.sawToolCall)
        assertTrue(out.toString().contains("code"))
        assertTrue(out.toString().contains("双反引号"))
    }

    @Test
    fun `buffer preserves normal code fence`() {
        val b = ToolCallStreamBuffer()
        val out = StringBuilder()
        "示例：\n```kotlin\nval x = 1\n```\n结束".chunked(3).forEach { out.append(b.feed(it)) }
        out.append(b.flushRemaining())
        assertFalse(b.sawToolCall)
        assertTrue(out.toString().contains("val x = 1"))
        assertTrue(out.toString().contains("结束"))
    }

    @Test
    fun `buffer recovers malformed fence as text`() {
        val b = ToolCallStreamBuffer()
        val out = StringBuilder()
        val whole = fence("{bad json}")
        whole.chunked(3).forEach { out.append(b.feed(it)) }
        out.append(b.flushRemaining())

        assertFalse("解析失败不应算作工具调用", b.sawToolCall)
        assertTrue("应标记格式异常", b.hadMalformedFence)
        assertTrue("内容不应凭空消失", out.toString().contains("bad json"))
    }

    @Test
    fun `buffer collects multiple tool calls`() {
        val b = ToolCallStreamBuffer()
        val whole = fence("""{"name":"get_weather","arguments":{"city":"北京"}}""") +
            "\n" + fence("""{"name":"get_time","arguments":{"city":"北京"}}""")
        whole.chunked(5).forEach { b.feed(it) }
        b.flushRemaining()
        assertEquals(2, b.calls.size)
    }

    @Test
    fun `buffer flushes unclosed fence content at end`() {
        val b = ToolCallStreamBuffer()
        val out = StringBuilder()
        val whole = "回答：" + ToolPrompt.FENCE + "\n{\"name\":\"x\""
        whole.chunked(4).forEach { out.append(b.feed(it)) }
        out.append(b.flushRemaining())
        assertTrue("截断内容应作为正文返回，避免丢失", out.toString().contains("回答"))
        assertTrue(out.toString().contains("x"))
    }

    @Test
    fun `buffer reports buffering state while inside fence`() {
        val b = ToolCallStreamBuffer()
        b.feed(ToolPrompt.FENCE)
        assertTrue("围栏内应处于缓冲态", b.isBuffering())
        b.feed("\n{}" + ToolPrompt.FENCE_END)
        assertFalse("围栏闭合后应恢复", b.isBuffering())
    }

    // ---------------- 4. 请求注入 ----------------

    private fun body(tools: JSONArray? = null, choice: Any? = null): JSONObject {
        val b = JSONObject()
        if (tools != null) b.put("tools", tools)
        if (choice != null) b.put("tool_choice", choice)
        return b
    }

    @Test
    fun `injector appends instructions to last user message`() {
        val msgs = mutableListOf(
            ChatMessage("system", "你是助手"),
            ChatMessage("user", "北京天气怎么样"),
        )
        val ctx = ToolInjector.prepare(msgs, body(tools("get_weather"), "auto"))
        assertTrue(ctx.enabled)
        assertEquals(2, msgs.size)
        assertTrue("应保留原问题", msgs[1].textContent().contains("北京天气怎么样"))
        assertTrue("应注入工具说明", msgs[1].textContent().contains("get_weather"))
        assertTrue("应注入格式约定", msgs[1].textContent().contains(ToolPrompt.FENCE))
    }

    @Test
    fun `injector is no-op without tools`() {
        val msgs = mutableListOf(ChatMessage("user", "你好"))
        val ctx = ToolInjector.prepare(msgs, body())
        assertFalse(ctx.enabled)
        assertEquals("你好", msgs[0].textContent())
    }

    @Test
    fun `injector respects tool_choice none`() {
        val msgs = mutableListOf(ChatMessage("user", "你好"))
        val ctx = ToolInjector.prepare(msgs, body(tools("get_weather"), "none"))
        assertFalse("tool_choice=none 不应注入", ctx.enabled)
        assertEquals("你好", msgs[0].textContent())
    }

    @Test
    fun `injector converts role tool message into user text`() {
        val msgs = mutableListOf(
            ChatMessage("user", "北京天气"),
            ChatMessage("tool", """{"temp":5}""", toolCallId = "call_1", name = "get_weather"),
        )
        val ctx = ToolInjector.prepare(msgs, body(tools("get_weather"), "auto"))
        assertTrue(ctx.enabled)
        val last = msgs.last()
        assertEquals("tool 消息必须转成 user（上游不认 tool 角色）", "user", last.role)
        assertTrue(last.textContent().contains("工具执行结果"))
        assertTrue(last.textContent().contains("get_weather"))
        assertTrue(last.textContent().contains("5"))
    }

    @Test
    fun `injector restores assistant tool_calls as fence text`() {
        val calls = JSONArray().put(
            JSONObject()
                .put("id", "call_1")
                .put("type", "function")
                .put(
                    "function",
                    JSONObject().put("name", "get_weather").put("arguments", """{"city":"北京"}"""),
                ),
        )
        val msgs = mutableListOf(
            ChatMessage("user", "北京天气"),
            ChatMessage("assistant", "", toolCalls = calls),
        )
        ToolInjector.prepare(msgs, body(tools("get_weather"), "auto"))
        val assistant = msgs[1]
        assertEquals("assistant", assistant.role)
        assertTrue("应还原成 fence 格式保持 few-shot", assistant.textContent().contains(ToolPrompt.FENCE))
        assertTrue(assistant.textContent().contains("get_weather"))
        assertTrue(assistant.textContent().contains("北京"))
    }

    @Test
    fun `injector filters hallucinated tool names`() {
        val ctx = ToolInjector.Context(true, tools("get_weather"), "auto", setOf("get_weather"))
        val calls = listOf(
            ToolPrompt.ParsedCall("get_weather", "{}"),
            ToolPrompt.ParsedCall("delete_everything", "{}"),
        )
        val valid = ToolInjector.filterValid(calls, ctx)
        assertEquals("臆造的工具名应被丢弃", 1, valid.size)
        assertEquals("get_weather", valid[0].name)
    }

    // ---------------- 工具名容错与校正（应对客户端前缀混乱） ----------------

    @Test
    fun `resolveName exact match`() {
        val allowed = setOf("mcp__MTmcp__mt_file_list", "get_time_info")
        assertEquals("get_time_info", ToolInjector.resolveName("get_time_info", allowed))
    }

    @Test
    fun `resolveName ignores case`() {
        val allowed = setOf("mcp__MTmcp__mt_file_list")
        assertEquals(
            "mcp__MTmcp__mt_file_list",
            ToolInjector.resolveName("MCP__mtmcp__MT_FILE_LIST", allowed),
        )
    }

    @Test
    fun `resolveName strips prefix when unambiguous`() {
        // 模型常只输出尾部名字，客户端注册的却带 mcp__ 前缀
        val allowed = setOf("mcp__MTmcp__mt_file_list", "mcp__MTmcp__mt_file_read_text")
        assertEquals(
            "mcp__MTmcp__mt_file_list",
            ToolInjector.resolveName("mt_file_list", allowed),
        )
    }

    @Test
    fun `resolveName returns null when ambiguous`() {
        // 两个工具尾部同名 -> 无法确定，必须放弃而不是猜
        val allowed = setOf("mcp__A__mt_file_list", "mcp__B__mt_file_list")
        assertNull(ToolInjector.resolveName("mt_file_list", allowed))
        assertNull(ToolInjector.resolveName("totally_unknown", allowed))
    }

    @Test
    fun `filterValid corrects name to registered form`() {
        val allowed = setOf("mcp__MTmcp__mt_file_list", "get_time_info")
        val ctx = ToolInjector.Context(true, null, null, allowed)
        val calls = listOf(ToolPrompt.ParsedCall("mt_file_list", "{}"))
        val out = ToolInjector.filterValid(calls, ctx)
        assertEquals(1, out.size)
        assertEquals(
            "名字应被校正为客户端注册名，否则客户端报 does not exists",
            "mcp__MTmcp__mt_file_list",
            out[0].name,
        )
    }

    // ---------------- 大工具集：精简注入 ----------------

    @Test
    fun `large tool set uses compact form and stays small`() {
        val many = JSONArray()
        for (i in 1..40) {
            many.put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject()
                        .put("name", "mcp__MTmcp__tool_$i")
                        .put("description", "这是一个很长的工具描述。" + "支持多种操作模式。".repeat(6))
                        .put(
                            "parameters",
                            JSONObject().put("type", "object")
                                .put("properties", JSONObject()
                                    .put("path", JSONObject().put("type", "string")
                                        .put("description", "路径说明" + "很长的描述".repeat(10)))
                                    .put("recursive", JSONObject().put("type", "boolean")))
                                .put("required", JSONArray().put("path")),
                        ),
                ),
            )
        }
        val p = ToolPrompt.buildInstructions(many, "auto")!!
        // 40 个工具的精简注入不应超过 6k 字符（完整 schema 会到 2 万以上）
        assertTrue("精简后应显著变小，实际 ${p.length}", p.length < 6000)
        assertTrue("仍应包含工具名", p.contains("mcp__MTmcp__tool_1"))
        assertTrue("应包含参数签名", p.contains("path:string"))
        assertTrue("应标出必填", p.contains("path:string*"))
        assertTrue("应说明 * 含义", p.contains("必填"))
    }

    @Test
    fun `small tool set keeps full schema`() {
        val p = ToolPrompt.buildInstructions(tools("get_weather"), "auto")!!
        assertTrue("少量工具应保留完整 JSON Schema", p.contains("参数 JSON Schema"))
        assertTrue(p.contains("get_weather 的说明"))
    }

    @Test
    fun `instructions demand verbatim tool names`() {
        val p = ToolPrompt.buildInstructions(tools("get_weather"), "auto")!!
        assertTrue("必须强调工具名逐字复制（模型常改前缀）", p.contains("逐字复制"))
        val pe = ToolPrompt.buildInstructions(tools("get_weather"), "auto", locale = "en")!!
        assertTrue(pe.contains("verbatim"))
    }

    @Test
    fun `compact schema keeps enum values`() {
        val t = JSONArray().put(
            JSONObject().put("type", "function").put(
                "function",
                JSONObject().put("name", "f")
                    .put("parameters", JSONObject().put("type", "object")
                        .put("properties", JSONObject()
                            .put("encoding", JSONObject().put("type", "string")
                                .put("enum", JSONArray().put("utf-8").put("gbk"))))
                        .put("required", JSONArray())),
            ),
        )
        // 构造 >12 个工具以触发精简
        val many = JSONArray()
        for (i in 1..15) many.put(t.optJSONObject(0))
        val p = ToolPrompt.buildInstructions(many, "auto")!!
        assertTrue("枚举对模型很关键，精简时也要保留", p.contains("utf-8|gbk"))
    }

    @Test
    fun `tool_choice required is honored`() {
        val p = ToolPrompt.buildInstructions(tools("get_weather"), "required")!!
        assertTrue("required 应要求必须调用工具", p.contains("必须"))
        assertNotNull(ToolPrompt.buildInstructions(tools("get_weather"), "required"))
    }

    // ---------------- 判定「模型漏掉了工具调用」 ----------------

    @Test
    fun `detects missed tool call when text mentions tool_call`() {
        // 代码形态的 tool_call 标识符：模型很可能真的写了裸调用语法 → 值得重试
        assertTrue(ToolPrompt.looksLikeMissedToolCall("我应该调用 tool_call 来查询"))
        assertTrue(ToolPrompt.looksLikeMissedToolCall("I will make a tool call"))
    }

    @Test
    fun `does NOT retry on natural-language plans mentioning tools`() {
        // 行为变更（修复「思考很久不开始」）：
        // 中文「工具调用」等自然语言表述常见于模型的**计划性叙述**
        // （如「下面进行工具调用」「我将使用工具来…」），并非格式跑偏。
        // 旧逻辑对这类文本也触发重试，导致复杂任务（移植项目、多步规划）
        // 反复重试、单请求耗时从 30s 涨到 180s。现在不再重试。
        assertFalse(ToolPrompt.looksLikeMissedToolCall("下面进行工具调用"))
        assertFalse(ToolPrompt.looksLikeMissedToolCall("我将调用工具来克隆仓库"))
        assertFalse(ToolPrompt.looksLikeMissedToolCall("接下来需要调用一些工具"))
    }

    @Test
    fun `detects missed tool call on bare json`() {
        assertTrue(
            ToolPrompt.looksLikeMissedToolCall(
                """{"name": "get_weather", "arguments": {"city": "北京"}}""",
            ),
        )
    }

    @Test
    fun `detects missed tool call when model complains tool unavailable`() {
        assertTrue(ToolPrompt.looksLikeMissedToolCall("Tool get_weather does not exist."))
        assertTrue(ToolPrompt.looksLikeMissedToolCall("抱歉，无法调用该工具"))
        assertTrue(ToolPrompt.looksLikeMissedToolCall("工具不存在"))
        // 实测模型常用这些变体措辞
        assertTrue(
            ToolPrompt.looksLikeMissedToolCall(
                "抱歉，当前环境中无法访问该目录，且相关的文件系统工具未在当前服务中挂载或未能成功响应。",
            ),
        )
        assertTrue(ToolPrompt.looksLikeMissedToolCall("该工具未启用"))
        assertTrue(ToolPrompt.looksLikeMissedToolCall("tool not found"))
    }

    @Test
    fun `normal answer is not treated as missed tool call`() {
        assertFalse(
            "正常回答不应触发重试（否则会无谓加倍请求）",
            ToolPrompt.looksLikeMissedToolCall("北京今天晴，气温 5 到 15 度。"),
        )
        assertFalse(ToolPrompt.looksLikeMissedToolCall("我是通义千问，很高兴为你服务。"))
        assertFalse(ToolPrompt.looksLikeMissedToolCall(""))
    }

    @Test
    fun `injector does not filter when no allow list`() {
        val ctx = ToolInjector.Context(true, null, null, emptySet())
        val calls = listOf(ToolPrompt.ParsedCall("anything", "{}"))
        assertEquals(1, ToolInjector.filterValid(calls, ctx).size)
    }

    @Test
    fun `toToolCalls produces openai shape`() {
        val calls = listOf(ToolPrompt.ParsedCall("get_weather", """{"city":"北京"}"""))
        val arr = ToolInjector.toToolCalls(calls)
        assertEquals(1, arr.length())
        val c = arr.getJSONObject(0)
        assertTrue(c.getString("id").startsWith("call_"))
        assertEquals("function", c.getString("type"))
        assertEquals("get_weather", c.getJSONObject("function").getString("name"))
        assertEquals("""{"city":"北京"}""", c.getJSONObject("function").getString("arguments"))
    }

    // ---------------- 5. 多轮消息转换 ----------------

    @Test
    fun `codec detects tool history`() {
        assertFalse(ToolMessageCodec.hasToolHistory(listOf(ChatMessage("user", "hi"))))
        assertTrue(ToolMessageCodec.hasToolHistory(listOf(ChatMessage("tool", "{}"))))
        val calls = JSONArray().put(JSONObject().put("id", "call_1"))
        assertTrue(
            ToolMessageCodec.hasToolHistory(listOf(ChatMessage("assistant", "", toolCalls = calls))),
        )
    }

    @Test
    fun `codec returns null for plain messages`() {
        assertNull(ToolMessageCodec.renderForUpstream(ChatMessage("user", "你好")))
        assertNull(ToolMessageCodec.renderForUpstream(ChatMessage("system", "你是助手")))
        assertNull(ToolMessageCodec.renderForUpstream(ChatMessage("assistant", "普通回答")))
    }

    @Test
    fun `codec handles tool message without name`() {
        val out = ToolMessageCodec.renderForUpstream(ChatMessage("tool", "result"))
        assertNotNull(out)
        assertTrue(out!!.contains("unknown"))
        assertTrue(out.contains("result"))
    }

    @Test
    fun `codec handles empty tool result`() {
        val out = ToolMessageCodec.renderForUpstream(
            ChatMessage("tool", "", name = "get_weather"),
        )!!
        assertTrue("空结果应兜底成 {}", out.contains("{}"))
    }

    @Test
    fun `codec keeps assistant content alongside tool calls`() {
        val calls = JSONArray().put(
            JSONObject().put("id", "call_1").put(
                "function",
                JSONObject().put("name", "get_weather").put("arguments", "{}"),
            ),
        )
        val out = ToolMessageCodec.renderForUpstream(
            ChatMessage("assistant", "我来查一下", toolCalls = calls),
        )!!
        assertTrue("应保留正文", out.contains("我来查一下"))
        assertTrue("应含 fence", out.contains(ToolPrompt.FENCE))
    }

    @Test
    fun `codec handles arguments already being object`() {
        val calls = JSONArray().put(
            JSONObject().put("id", "call_1").put(
                "function",
                JSONObject().put("name", "get_weather")
                    .put("arguments", JSONObject().put("city", "北京")),
            ),
        )
        val out = ToolMessageCodec.renderForUpstream(ChatMessage("assistant", "", toolCalls = calls))!!
        assertTrue(out.contains("北京"))
    }

    // ---------------- 工具不存在错误的去污染 ----------------

    @Test
    fun `detects tool not found errors`() {
        assertTrue(ToolMessageCodec.isToolNotFoundError("Tool mt_file_list does not exists."))
        assertTrue(ToolMessageCodec.isToolNotFoundError("Tool mcp__MTmcp__x does not exist"))
        assertTrue(ToolMessageCodec.isToolNotFoundError("tool not found"))
        assertTrue(ToolMessageCodec.isToolNotFoundError("No such tool"))
        assertTrue(ToolMessageCodec.isToolNotFoundError("工具不存在"))
        assertFalse(ToolMessageCodec.isToolNotFoundError("""{"ok":true,"files":[]}"""))
        assertFalse(ToolMessageCodec.isToolNotFoundError(""))
    }

    @Test
    fun `tool not found error is sanitized before feeding model`() {
        // 这是关键防御：原始错误若原样传给模型，模型会放弃调用工具
        val msg = ChatMessage(
            "tool",
            "Tool mcp__MTmcp__mt_file_list does not exists.",
            name = "mcp__MTmcp__mt_file_list",
        )
        val out = ToolMessageCodec.renderForUpstream(msg)!!
        assertFalse(
            "原始错误文本不得出现在注入内容里（会污染模型判断）",
            out.contains("does not exists"),
        )
        assertTrue("应改写为中性的重试提示", out.contains("未成功"))
        assertTrue("应引导模型正确重试", out.contains("逐字复制"))
    }

    @Test
    fun `normal tool result is passed through unchanged`() {
        val msg = ChatMessage("tool", """{"files":["a.txt"]}""", name = "mt_file_list")
        val out = ToolMessageCodec.renderForUpstream(msg)!!
        assertTrue("正常结果必须原样保留", out.contains("a.txt"))
        assertFalse(out.contains("未成功"))
    }

    // ---------------- MCP 结构化错误的还原 ----------------

    @Test
    fun `structured mcp error is rendered with missing params`() {
        // 真实 MTMCP 返回（实测抓取）
        val raw = """{"ok":false,"data":null,"error":{"code":"INVALID_ARGUMENT",""" +
            """"message":"Missing parameters: editSessionId, view","severity":"warning",""" +
            """"recoverable":true,"retrySameArguments":false,"argument":"parameters",""" +
            """"allowedValues":["editSessionId","view"]},"nextActions":[]}"""
        val out = ToolMessageCodec.renderStructuredToolError(raw)
        assertNotNull("应识别为结构化错误", out)
        assertTrue("应保留错误码", out!!.contains("INVALID_ARGUMENT"))
        assertTrue("应指出缺失的参数", out.contains("editSessionId"))
        assertTrue("应指出缺失的参数", out.contains("view"))
        assertTrue("应说明可恢复", out.contains("可恢复"))
        assertTrue("应提示需改参数重试", out.contains("修改参数"))
    }

    @Test
    fun `structured error is used in tool message rendering`() {
        val raw = """{"ok":false,"error":{"code":"INVALID_ARGUMENT",""" +
            """"message":"Missing parameters: view","recoverable":true,""" +
            """"retrySameArguments":false,"allowedValues":["view"]}}"""
        val out = ToolMessageCodec.renderForUpstream(
            ChatMessage("tool", raw, name = "mt_apk_list"),
        )!!
        assertTrue("应还原结构化错误而非笼统报错", out.contains("改") || out.contains("补全"))
        assertTrue("应带上缺失参数名", out.contains("view"))
    }

    @Test
    fun `successful structured result is not treated as error`() {
        val raw = """{"ok":true,"data":{"files":["a.txt"]}}"""
        assertNull(
            "成功结果不应被改写",
            ToolMessageCodec.renderStructuredToolError(raw),
        )
    }

    @Test
    fun `non json text returns null`() {
        assertNull(ToolMessageCodec.renderStructuredToolError("plain text result"))
        assertNull(ToolMessageCodec.renderStructuredToolError(""))
    }

    // ---------------- 6. 端到端：模型输出 -> OpenAI 响应 ----------------

    @Test
    fun `end to end model output becomes tool_calls`() {
        // 模拟模型返回（夹带思考与正文）
        val modelOutput = "我来查一下北京的天气。\n\n" +
            fence("""{"name":"get_weather","arguments":{"city":"北京"}}""")

        val parsed = ToolPrompt.parse(modelOutput)
        assertEquals(1, parsed.calls.size)
        assertTrue("正文应保留", parsed.remainingText.contains("我来查一下"))

        val ctx = ToolInjector.Context(true, tools("get_weather"), "auto", setOf("get_weather"))
        val valid = ToolInjector.filterValid(parsed.calls, ctx)
        val arr = ToolInjector.toToolCalls(valid)

        assertEquals(1, arr.length())
        assertEquals("get_weather", arr.getJSONObject(0).getJSONObject("function").getString("name"))
    }

    @Test
    fun `end to end no tool call yields plain answer`() {
        val modelOutput = "北京今天晴，气温 5 到 15 度。"
        val parsed = ToolPrompt.parse(modelOutput)
        assertEquals(0, parsed.calls.size)
        assertEquals(modelOutput, parsed.remainingText)
    }

    // ---------------- 风控识别与重试阶梯 ----------------

    @Test
    fun `detects aliyun risk control block`() {
        // 实测抓到的真实响应
        val real = """Qwen 拒绝请求: {"ret":["FAIL_SYS_USER_VALIDATE",""" +
            """"RGV587_ERROR::SM::哎哟喂,被挤爆啦,请稍后重试"],""" +
            """"data":{"url":"https://chat.qwen.ai/api/v2/chat/completions/_____tmd_____/punish?x5secdata=..."}}"""
        assertTrue(GatewayRouter.isRiskControlBlock(real))
        assertTrue(GatewayRouter.isRiskControlBlock("HTTP 429 Too Many Requests"))
        assertTrue(GatewayRouter.isRiskControlBlock("rate limit exceeded"))
        assertTrue(GatewayRouter.isRiskControlBlock("请完成滑块验证"))
        assertTrue(GatewayRouter.isRiskControlBlock("系统繁忙，请稍后重试"))
    }

    @Test
    fun `UPSTREAM_EMPTY is treated as risk control`() {
        // 风控掐断最典型的表现是「流建立但无内容」，不识别会静默失败
        assertTrue(GatewayRouter.isRiskControlBlock("UPSTREAM_EMPTY", null))
        assertTrue(GatewayRouter.isRiskControlBlock("UPSTREAM_429", null))
        assertTrue(GatewayRouter.isRiskControlBlock("THROTTLE_RATE_LIMIT", null))
    }

    @Test
    fun `business errors are not retried as risk control`() {
        // 业务错误重试无意义，必须排除
        assertFalse(GatewayRouter.isRiskControlBlock("AUTH_FAILED", null))
        assertFalse(GatewayRouter.isRiskControlBlock("NO_TOKEN", null))
        assertFalse(GatewayRouter.isRiskControlBlock("BAD_REQUEST", null))
        assertFalse(GatewayRouter.isRiskControlBlock("FILE_TOO_LARGE", null))
        assertFalse(GatewayRouter.isRiskControlBlock(null, "invalid api key"))
        assertFalse(GatewayRouter.isRiskControlBlock("", ""))
    }

    @Test
    fun `throttle wait steps are ascending and moderate`() {
        val steps = GatewayRouter.THROTTLE_WAIT_STEPS_MS
        assertTrue("应有 3 档等待", steps.size == 3)
        assertTrue("等待时长应递增", steps[0] < steps[1] && steps[1] < steps[2])
        assertTrue("首档不宜过长（折中方案）", steps[0] <= 10_000)
        assertTrue("末档应足够长以避开风控", steps[2] >= 20_000)
    }

    @Test
    fun `risk control hint mentions slider verification`() {
        assertTrue(GatewayRouter.RISK_CONTROL_HINT.contains("滑块"))
        assertTrue(GatewayRouter.RISK_CONTROL_HINT.contains("chat.qwen.ai"))
    }
}
