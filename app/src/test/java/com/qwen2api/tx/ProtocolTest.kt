package com.qwen2api.tx

import com.qwen2api.tx.core.ChatMessage
import com.qwen2api.tx.core.Json
import com.qwen2api.tx.core.QwenClient
import com.qwen2api.tx.core.QwenEvent
import com.qwen2api.tx.core.QwenSseParser
import com.qwen2api.tx.core.Util
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议层单元测试（纯 JVM，不需要设备）。
 * 重点覆盖从 Node 移植过来的三处易错逻辑：
 *  1. SSE 快照式思考去重
 *  2. 多轮 messages -> 单条 flat content 拼接
 *  3. 时区头必须是纯 ASCII（原项目 "ByteString index 35" 事故）
 */
class ProtocolTest {

    // ---------------- SSE 解析 ----------------

    @Test
    fun `thinking summary snapshot is deduplicated`() {
        val p = QwenSseParser()
        // 快照式：每次推送整段全文
        val chunk1 = "data: {\"choices\":[{\"delta\":{\"phase\":\"thinking_summary\"," +
            "\"extra\":{\"summary_thought\":{\"content\":[\"我\"]}}}}]}\n"
        val chunk2 = "data: {\"choices\":[{\"delta\":{\"phase\":\"thinking_summary\"," +
            "\"extra\":{\"summary_thought\":{\"content\":[\"我们\"]}}}}]}\n"
        val chunk3 = "data: {\"choices\":[{\"delta\":{\"phase\":\"thinking_summary\"," +
            "\"extra\":{\"summary_thought\":{\"content\":[\"我们\"]}}}}]}\n"

        val e1 = p.feed(chunk1).filterIsInstance<QwenEvent.Thinking>()
        val e2 = p.feed(chunk2).filterIsInstance<QwenEvent.Thinking>()
        val e3 = p.feed(chunk3).filterIsInstance<QwenEvent.Thinking>()

        assertEquals("只应输出增量「我」", "我", e1.joinToString("") { it.delta })
        assertEquals("只应输出新增的「们」", "们", e2.joinToString("") { it.delta })
        assertTrue("完全相同的快照不应再产生增量", e3.isEmpty())
        assertEquals("我们", p.thinking)
    }

    @Test
    fun `answer phase yields content delta`() {
        val p = QwenSseParser()
        val line = "data: {\"choices\":[{\"delta\":{\"phase\":\"answer\",\"content\":\"你好\"}}]}\n"
        val events = p.feed(line).filterIsInstance<QwenEvent.Content>()
        assertEquals(1, events.size)
        assertEquals("你好", events[0].delta)
    }

    @Test
    fun `cross chunk buffering works`() {
        val p = QwenSseParser()
        // 人为把一行 JSON 切成三段喂入
        val full = "data: {\"choices\":[{\"delta\":{\"phase\":\"answer\",\"content\":\"AB\"}}]}\n"
        assertTrue(p.feed(full.substring(0, 20)).isEmpty())
        assertTrue(p.feed(full.substring(20, 40)).isEmpty())
        val events = p.feed(full.substring(40)).filterIsInstance<QwenEvent.Content>()
        assertEquals("AB", events.joinToString("") { it.delta })
    }

    @Test
    fun `upstream error frame is surfaced`() {
        val p = QwenSseParser()
        val line = "data: {\"success\":false,\"data\":{\"code\":\"Baxia\",\"details\":\"风控拦截\"}}\n"
        val err = p.feed(line).filterIsInstance<QwenEvent.UpstreamError>()
        assertEquals(1, err.size)
        assertEquals("Baxia", err[0].code)
        assertTrue(err[0].message.contains("风控"))
    }

    @Test
    fun `usage is captured`() {
        val p = QwenSseParser()
        p.feed("data: {\"usage\":{\"input_tokens\":12,\"output_tokens\":34},\"choices\":[]}\n")
        assertEquals(12, Json.int(p.usage, "input_tokens"))
        assertEquals(34, Json.int(p.usage, "output_tokens"))
    }

    // ---------------- 多轮拼接 ----------------

    @Test
    fun `single user message passes through`() {
        val out = QwenClient.buildFlatContent(listOf(ChatMessage("user", "你好")))
        assertEquals("你好", out)
    }

    @Test
    fun `system plus user message is prefixed`() {
        val out = QwenClient.buildFlatContent(
            listOf(ChatMessage("system", "你是助手"), ChatMessage("user", "你好")),
        )
        assertTrue(out.startsWith("[System Instructions]\n你是助手"))
        assertTrue(out.endsWith("你好"))
    }

    @Test
    fun `multi turn history is flattened`() {
        val out = QwenClient.buildFlatContent(
            listOf(
                ChatMessage("user", "第一问"),
                ChatMessage("assistant", "第一答"),
                ChatMessage("user", "第二问"),
            ),
        )
        assertTrue(out.contains("[Conversation History]"))
        assertTrue(out.contains("User: 第一问"))
        assertTrue(out.contains("Assistant: 第一答"))
        assertTrue(out.endsWith("User: 第二问"))
    }

    @Test
    fun `multimodal content flattens to text`() {
        val parts = listOf(
            mapOf("type" to "text", "text" to "这张图是什么"),
            mapOf("type" to "image_url", "image_url" to mapOf("url" to "data:image/png;base64,AAAA")),
        )
        val msg = ChatMessage("user", parts)
        assertEquals("这张图是什么", msg.textContent())
    }

    // ---------------- 时区头 ASCII 安全 ----------------

    @Test
    fun `timezone header is pure ascii`() {
        val tz = Util.asciiTimezoneHeader()
        assertTrue("时区头必须全 ASCII: $tz", tz.all { it.code <= 0xFF })
        assertTrue("格式应为 GMT±HH:MM", Regex("^GMT[+-]\\d{2}:\\d{2}$").matches(tz))
    }

    @Test
    fun `http date header is ascii and gmt`() {
        val d = Util.httpDate()
        assertTrue(d.all { it.code <= 0xFF })
        assertTrue(d.endsWith("GMT"))
    }

    // ---------------- 浏览器兼容：JSON 编解码 ----------------

    @Test
    fun `json encoding preserves structure`() {
        val obj = JSONObject()
            .put("model", "qwen3.8-max")
            .put("stream", true)
            .put("nested", JSONObject().put("a", 1))
        val encoded = Json.encode(obj)
        val decoded = Json.parse(encoded)
        assertEquals("qwen3.8-max", Json.str(decoded, "model"))
        assertEquals(true, Json.bool(decoded, "stream"))
        assertEquals(1, Json.int(Json.obj(decoded, "nested"), "a"))
    }

    @Test
    fun `safeStr never yields object Object`() {
        val nested = JSONObject().put("details", "内部错误")
        val s = Util.safeStr(nested, 400)
        assertTrue(s.contains("内部错误"))
        assertTrue(!s.contains("[object Object]"))
    }

    @Test
    fun `normalizeError fills all fields`() {
        val (code, msg, status) = Util.normalizeError(RuntimeException())
        assertEquals("UPSTREAM_ERROR", code)
        assertEquals(502, status)
        assertTrue(msg.isNotEmpty())
    }
}
