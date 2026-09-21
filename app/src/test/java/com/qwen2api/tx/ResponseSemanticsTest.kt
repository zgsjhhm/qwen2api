package com.qwen2api.tx

import com.qwen2api.tx.core.FileRecord
import com.qwen2api.tx.core.FileStore
import com.qwen2api.tx.core.MemoryConfigRepository
import com.qwen2api.tx.server.GatewayRouter
import com.qwen2api.tx.server.HttpRequest
import com.qwen2api.tx.server.HttpResponse
import com.qwen2api.tx.server.MemoryFileStore
import com.qwen2api.tx.server.MiniHttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * 响应语义层测试：**头与正文的关系**，以及"方法不对"与"路径不存在"的区分。
 *
 * ## 为什么单独开一个类
 * F 阶段把连接层（keep-alive、请求分帧、畸形请求）打透了，但响应侧只剩一个
 * 隐式决策没被验证过：`writeHead` 里"200 且调用方没声明长度就自动改 chunked"。
 * 这条规则在三种情况下会给出**语法正确、语义错误**的响应：
 *
 *  1. **HEAD**。按 RFC 7231 §4.3.2，HEAD 的响应头必须与同一 URI 的 GET 一致，
 *     只是没有正文。旧实现把 handler 写出的正文原样发出去 —— 客户端按
 *     `Content-Length` 去读，读到的却是**下一个响应**的字节（响应走私的成立条件）。
 *     更糟的是流式路径：GET 是 `Transfer-Encoding: chunked`，HEAD 也照发 chunked
 *     头却没有任何 chunk 数据，客户端会一直等块长度行。
 *  2. **空正文的 200**。`writeHead(200); end()` 没有任何正文，旧实现仍声明
 *     `chunked` 并写下 `0\r\n\r\n`。语义是"一条已经结束的流"，而事实是"长度为零的正文"，
 *     两者对客户端、日志与反代都是不同的东西 —— 后者应显式 `Content-Length: 0`。
 *  3. **HTTP/1.0**。1.0 不认识 `Transfer-Encoding`。旧实现对 1.0 客户端照样发
 *     `chunked` 头并把 `3\r\nabc\r\n0\r\n\r\n` 原样写进正文，客户端看到的是
 *     **字面量块长度**混在业务数据里（curl --http1.0、老 SDK、嵌入式 HTTP 库全部中招）。
 *     1.0 下没有长度声明时的唯一合法选择是"靠关闭连接定界"。
 *
 * 另一半是路由语义：`GET /v1/chat/completions`（POST-only 路径）此前回 **404 not found**，
 * 于是调用方以为"网关没这个接口"，实际是自己方法用错了 —— 正确语义是
 * **405 + `Allow` 头**。相反，真正不存在的路径必须仍是 404，两者不能混为一谈。
 */
class HttpResponseSemanticsTest {

    private lateinit var server: MiniHttpServer
    private var port: Int = 0

    /** 用例可替换的处理器（lambda 在调用时读取，替换即时生效） */
    @Volatile
    private var handler: (HttpRequest, HttpResponse) -> Unit = { _, res ->
        res.end("ok".toByteArray())
    }

    @Before
    fun setUp() {
        server = MiniHttpServer(0, "127.0.0.1", LIMIT_2MB, 30_000) { req, res ->
            handler(req, res)
        }
        server.start()
        port = server.boundPort
    }

    @After
    fun tearDown() {
        server.stop()
    }

    /** [Connection: close] 请求：响应由关闭定界，读侧立刻拿到 EOF，用例不必等超时 */
    private fun req(method: String, target: String = "/x"): String =
        "$method $target HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n"

    // ---------------- 1. 空正文 200 的成帧 ----------------

    @Test
    fun `empty 200 declares zero content length instead of an empty chunked stream`() {
        handler = { _, res ->
            res.writeHead(200)
            res.end()
        }
        val r = rawSend(port, req("GET"))
        assertEquals(200, statusOf(r.text))
        assertNull(
            "没有任何正文的响应不该被声明成 chunked 流（那是「还没写完」的语义）",
            headerOf(r.text, "Transfer-Encoding"),
        )
        assertEquals("空正文应精确声明为 0 长度", "0", headerOf(r.text, "Content-Length"))
        assertEquals("", bodyOf(r.text))
    }

    @Test
    fun `declared empty body keeps content length zero`() {
        // 走"先声明、后收尾"的常见写法，结论必须与上一条一致
        handler = { _, res ->
            res.header("Content-Type", "application/json")
            res.writeHead(200)
            res.end(ByteArray(0))
        }
        val r = rawSend(port, req("GET"))
        assertEquals(200, statusOf(r.text))
        assertNull(headerOf(r.text, "Transfer-Encoding"))
        assertEquals("0", headerOf(r.text, "Content-Length"))
    }

    // ---------------- 2. HEAD ----------------

    @Test
    fun `head mirrors get headers and suppresses only the body`() {
        handler = { _, res ->
            res.header("Content-Type", "text/plain")
            res.header("Content-Length", "5")
            res.writeHead(200)
            res.end("hello".toByteArray())
        }
        val get = rawSend(port, req("GET"))
        val head = rawSend(port, req("HEAD"))
        assertEquals("hello", bodyOf(get.text))
        assertEquals(
            "HEAD 必须报告与 GET 完全相同的 Content-Length（客户端靠它预判体积）",
            headerOf(get.text, "Content-Length"),
            headerOf(head.text, "Content-Length"),
        )
        assertEquals("其它头也要一致", "text/plain", headerOf(head.text, "Content-Type"))
        assertEquals("HEAD 响应不得带正文", "", bodyOf(head.text))
    }

    @Test
    fun `head on a streaming response is neither chunked nor accompanied by a body`() {
        handler = { _, res ->
            res.writeHead(200)
            res.writeChunk("abc")
            res.end()
        }
        val get = rawSend(port, req("GET"))
        val head = rawSend(port, req("HEAD"))
        assertEquals("chunked", headerOf(get.text, "Transfer-Encoding"))
        assertEquals("abc", bodyOf(get.text))
        assertNull(
            "HEAD 不能用 chunked 成帧：没有正文，也就没有块长度行可等",
            headerOf(head.text, "Transfer-Encoding"),
        )
        assertEquals("", bodyOf(head.text))
        assertFalse(
            "不得把 chunk 终结块写给 HEAD",
            bodyOf(head.text).contains("0\r\n\r\n"),
        )
    }

    @Test
    fun `head keeps the connection reusable`() {
        handler = { _, res ->
            res.header("Content-Length", "5")
            res.writeHead(200)
            res.end("hello".toByteArray())
        }
        // 同一连接：HEAD 之后必须还能正常服务下一个请求
        val r = rawSend(
            port,
            "HEAD /x HTTP/1.1\r\nHost: h\r\n\r\nGET /x HTTP/1.1\r\nHost: h\r\n\r\n",
            timeoutMs = 400,
        )
        assertEquals("HEAD 不得吃掉连接", 2, responseCount(r.text))
        assertEquals("", bodyOf(r.text, 0))
        assertEquals("hello", bodyOf(r.text, 1))
    }

    // ---------------- 3. HTTP/1.0 ----------------

    @Test
    fun `http10 response is not chunked and is delimited by close`() {
        handler = { _, res ->
            res.writeHead(200)
            res.writeChunk("abc")
            res.end()
        }
        val r = rawSend(port, "GET /x HTTP/1.0\r\nHost: h\r\n\r\n")
        assertNull(
            "HTTP/1.0 不认 chunked，绝不能发 Transfer-Encoding",
            headerOf(r.text, "Transfer-Encoding"),
        )
        assertEquals("无长度声明时只能靠关闭定界", "close", headerOf(r.text, "Connection"))
        assertEquals(
            "正文必须原样写出：1.0 客户端会把块长度当业务数据",
            "abc",
            bodyOf(r.text),
        )
        assertTrue("声明了 close 就必须真的关闭", r.sawEof)
    }

    @Test
    fun `http10 keep alive request without a length is forced to close`() {
        handler = { _, res ->
            res.writeHead(200)
            res.writeChunk("abc")
            res.end()
        }
        val r = rawSend(port, "GET /x HTTP/1.0\r\nHost: h\r\nConnection: keep-alive\r\n\r\n")
        assertEquals(
            "1.0 下没有长度就无法复用连接，必须压下 keep-alive",
            "close",
            headerOf(r.text, "Connection"),
        )
        assertNull(headerOf(r.text, "Transfer-Encoding"))
        assertEquals("abc", bodyOf(r.text))
        assertTrue(r.sawEof)
    }

    @Test
    fun `http10 with declared length still reuses the connection`() {
        // 反向保护：有长度声明时 1.0 的 keep-alive 是合法的，不能被上面的规则误杀
        handler = { _, res ->
            res.header("Content-Length", "2")
            res.writeHead(200)
            res.end("ok".toByteArray())
        }
        val r = rawSend(
            port,
            "GET /x HTTP/1.0\r\nHost: h\r\nConnection: keep-alive\r\n\r\n" +
                "GET /x HTTP/1.0\r\nHost: h\r\nConnection: keep-alive\r\n\r\n",
            timeoutMs = 400,
        )
        assertEquals(2, responseCount(r.text))
        assertEquals("keep-alive", headerOf(r.text, "Connection"))
    }

    // ---------------- 4. 收尾后的越界写入 ----------------

    @Test
    fun `write after end is rejected and does not corrupt the response`() {
        handler = { _, res ->
            res.header("Content-Length", "1")
            res.writeHead(200)
            res.end("a".toByteArray())
            // 收尾之后再写：旧实现会把这 1 个字节静默追加进已声明长度的正文里，
            // 客户端读到的正文比 Content-Length 多，后续字节被当成下一个响应。
            // 这里**不吞异常**：正是要让服务器看到它并关闭连接。
            res.writeChunk("b")
        }
        val r = rawSend(port, req("GET"))
        assertEquals("响应已收尾后不得再追加正文", 1, responseCount(r.text))
        assertEquals("已声明的正文不得被污染", "a", bodyOf(r.text))
        assertTrue("越界写入后连接不可复用，必须关闭", r.sawEof)
        assertNotNull("应留下诊断信息，否则线上表现为「偶发乱码」", server.lastError)
    }

    @Test
    fun `writeHead after end is rejected`() {
        handler = { _, res ->
            res.header("Content-Length", "0")
            res.writeHead(200)
            res.end()
            res.writeHead(500)
        }
        val r = rawSend(port, req("GET"))
        assertEquals(1, responseCount(r.text))
        assertEquals("第二次 writeHead 不得改写线路上的状态行", 200, statusOf(r.text))
        assertTrue("越界的 writeHead 也说明处理器行为异常，连接不可复用", r.sawEof)
        assertEquals("0", headerOf(r.text, "Content-Length"))
    }

    @Test
    fun `header after writeHead is rejected`() {
        handler = { _, res ->
            res.writeHead(200)
            // 迟到的显式长度声明必须**报错**而不能生效：framing 已经锁定，
            // 若它生效，客户端会按新长度去读而正文其实是 chunked 的 —— 直接错位。
            // 静默丢弃同样是隐性故障（调用方以为设置成功），因此这里选择响亮失败。
            runCatching { res.header("Content-Length", "3") }
            res.writeChunk("abc")
            res.end()
        }
        val r = rawSend(port, req("GET"))
        assertEquals("chunked", headerOf(r.text, "Transfer-Encoding"))
        assertNull("锁定后追加的 Content-Length 必须被拒绝", headerOf(r.text, "Content-Length"))
        assertEquals("abc", bodyOf(r.text))
        assertTrue("越界设置头部后连接不可复用", r.sawEof)
        assertNotNull(server.lastError)
    }

    @Test
    fun `repeated writeHead keeps the first status`() {
        handler = { _, res ->
            res.writeHead(200)
            res.writeHead(500)
            res.writeChunk("abc")
            res.end()
        }
        val r = rawSend(port, req("GET"))
        assertEquals("状态行只写一次，以第一次为准", 200, statusOf(r.text))
        assertEquals(1, responseCount(r.text))
        assertEquals("abc", bodyOf(r.text))
        assertEquals("framing 在第一次 writeHead 时已定下", "chunked", headerOf(r.text, "Transfer-Encoding"))
    }

    @Test
    fun `end is idempotent`() {
        handler = { _, res ->
            res.header("Content-Length", "2")
            res.writeHead(200)
            res.end("ok".toByteArray())
            res.end()
            res.end()
        }
        val r = rawSend(port, req("GET"))
        assertEquals(1, responseCount(r.text))
        assertEquals("ok", bodyOf(r.text))
    }
}

/**
 * 路由方法语义测试：**405（方法不对）** 与 **404（路径不存在）** 必须分开。
 *
 * 症状区别很清楚，也正因如此才值得单独立规矩：
 *  - 404 会让调用方去翻文档找路径，怀疑网关版本不对（而其实是自己把 POST 写成了 GET）；
 *  - 405 + `Allow` 一眼就能看出"这个路径只收 POST"。
 *
 * 同时验证 HEAD 的路由等价性：HEAD 必须走 GET 的那条路由（否则 `/v1/models` 的
 * HEAD 探活会得到 404），而 POST-only 路径上的 HEAD 仍然应该是 405。
 */
class RoutingMethodSemanticsTest {

    private lateinit var configRepo: MemoryConfigRepository
    private lateinit var fileStore: FileStore
    private lateinit var server: MiniHttpServer
    private var port: Int = 0

    private val fileId = "file-sem1"

    @Before
    fun setUp() {
        configRepo = MemoryConfigRepository()
        fileStore = MemoryFileStore()
        fileStore.add(
            FileRecord(
                id = fileId,
                qwenId = "q1",
                url = "https://example.com/1.bin",
                filename = "f.bin",
                bytes = 3L,
                mime = "application/octet-stream",
                kind = "document",
                createdAt = 1700000000,
                purpose = "assistants",
                entry = JSONObject().put("type", "document").put("id", "q1"),
            ),
        )
        val router = GatewayRouter(configRepo, fileStore)
        server = MiniHttpServer(0, "127.0.0.1") { req, res -> router.handle(req, res) }
        server.start()
        port = server.boundPort
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun raw(method: String, target: String, auth: Boolean = true): Raw {
        val head = buildString {
            append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
            append("Host: 127.0.0.1\r\n")
            if (auth) append("Authorization: Bearer ").append(configRepo.load().apiKey).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        return rawSend(port, head)
    }

    // ---------------- 405 与 Allow ----------------

    @Test
    fun `get on a post only route is 405 with allow`() {
        val r = raw("GET", "/v1/chat/completions")
        assertEquals("路径存在但方法不对，是 405 而不是 404", 405, statusOf(r.text))
        assertEquals("POST", headerOf(r.text, "Allow"))
        assertTrue("405 也应给可读的 JSON 错误体", bodyOf(r.text).contains("error"))
        assertTrue(
            "错误体需要 Content-Length，便于客户端处理",
            (headerOf(r.text, "Content-Length")?.toIntOrNull() ?: 0) > 0,
        )
    }

    @Test
    fun `head on a post only route is 405 not 404`() {
        val r = raw("HEAD", "/v1/chat/completions")
        assertEquals(405, statusOf(r.text))
        assertEquals("POST", headerOf(r.text, "Allow"))
        assertEquals("405 也必须遵守 HEAD 无正文", "", bodyOf(r.text))
    }

    @Test
    fun `post on a read only route is 405 with allow get`() {
        val r = raw("POST", "/v1/models")
        assertEquals(405, statusOf(r.text))
        val allow = headerOf(r.text, "Allow").orEmpty()
        assertTrue("Allow 应含 GET: $allow", allow.contains("GET"))
        assertTrue("HEAD 与 GET 同义，Allow 里应一并列出: $allow", allow.contains("HEAD"))
    }

    @Test
    fun `post on healthz is 405 with allow get`() {
        val r = raw("POST", "/healthz")
        assertEquals(405, statusOf(r.text))
        assertTrue(headerOf(r.text, "Allow").orEmpty().contains("GET"))
    }

    @Test
    fun `post on file detail is 405 with allow get and delete`() {
        val r = raw("POST", "/v1/files/$fileId")
        assertEquals(405, statusOf(r.text))
        val allow = headerOf(r.text, "Allow").orEmpty()
        assertTrue("Allow 应含 GET: $allow", allow.contains("GET"))
        assertTrue("Allow 应含 DELETE: $allow", allow.contains("DELETE"))
    }

    @Test
    fun `delete on the content sub resource is 405 with allow get only`() {
        val r = raw("DELETE", "/v1/files/$fileId/content")
        assertEquals(405, statusOf(r.text))
        assertEquals("GET, HEAD", headerOf(r.text, "Allow"))
    }

    @Test
    fun `wrong method on admin path is 405`() {
        val r = raw("POST", "/admin/api/status")
        assertEquals(405, statusOf(r.text))
        assertEquals("GET, HEAD", headerOf(r.text, "Allow"))
    }

    // ---------------- 404 仍然只用于"路径不存在" ----------------

    @Test
    fun `unknown path is still 404 regardless of method`() {
        assertEquals(404, statusOf(raw("GET", "/nope").text))
        assertEquals("方法不对与路径不存在是两件事", 404, statusOf(raw("POST", "/nope").text))
        assertEquals(404, statusOf(raw("DELETE", "/nope").text))
    }

    @Test
    fun `unknown admin path is still 404`() {
        assertEquals(404, statusOf(raw("POST", "/admin/api/nope").text))
    }

    @Test
    fun `malformed file path shape is still 404`() {
        assertEquals(404, statusOf(raw("GET", "/v1/files/$fileId/extra").text))
        assertEquals(404, statusOf(raw("GET", "/v1/files/$fileId/content/more").text))
    }

    // ---------------- HEAD 的路由等价性 ----------------

    @Test
    fun `head is routed like get`() {
        val noAuth = raw("HEAD", "/v1/models", auth = false)
        assertEquals(
            "HEAD 必须走 GET 的那条路由（未鉴权 -> 401），而不是掉进 404",
            401,
            statusOf(noAuth.text),
        )
        assertEquals("HEAD 响应不得带正文", "", bodyOf(noAuth.text))
    }

    @Test
    fun `head reports the same content length as get`() {
        val get = raw("GET", "/v1/files/$fileId")
        val head = raw("HEAD", "/v1/files/$fileId")
        assertEquals(200, statusOf(get.text))
        assertEquals(200, statusOf(head.text))
        assertEquals(
            "HEAD 的价值就在于让客户端先拿到 GET 的头",
            headerOf(get.text, "Content-Length"),
            headerOf(head.text, "Content-Length"),
        )
        assertTrue("GET 的正文必须还在", bodyOf(get.text).contains(fileId))
        assertEquals("", bodyOf(head.text))
    }

    @Test
    fun `head on admin status is routed like get`() {
        val r = raw("HEAD", "/admin/api/status")
        assertEquals(200, statusOf(r.text))
        assertTrue((headerOf(r.text, "Content-Length")?.toIntOrNull() ?: 0) > 0)
        assertEquals("", bodyOf(r.text))
    }

    // ---------------- 正常路径不被误伤 ----------------

    @Test
    fun `collection still accepts both get and post`() {
        assertEquals(200, statusOf(raw("GET", "/v1/files").text))
        // 未配置 token -> 400，但说明"方法本身被允许、请求已进入业务逻辑"
        assertEquals(400, statusOf(raw("POST", "/v1/files").text))
    }

    @Test
    fun `valid json routes still work`() {
        val h = raw("GET", "/healthz")
        assertEquals(200, statusOf(h.text))
        assertTrue(bodyOf(h.text).contains("\"ok\""))
    }

    @Test
    fun `options preflight is still 204 on any path`() {
        listOf("/v1/models", "/v1/chat/completions", "/nope").forEach { p ->
            val r = raw("OPTIONS", p)
            assertEquals("OPTIONS $p 应保持 CORS 预检语义", 204, statusOf(r.text))
            assertEquals("*", headerOf(r.text, "Access-Control-Allow-Origin"))
        }
    }
}

// ---------------- 原始 HTTP 读取工具（两个测试类共用，file 级可见） ----------------

private const val LIMIT_2MB = 2L * 1024 * 1024

private class Raw(val text: String, val sawEof: Boolean)

/**
 * 原始收发：写完读到 EOF 或超时为止。
 *
 * [Raw.sawEof] 是关键信号 —— "服务端保持连接"与"服务端已关闭"在读侧都表现为
 * "暂时没有更多数据"，只有 EOF 能区分二者；只看读取超时会把 keep-alive 误判成失败。
 */
private fun rawSend(port: Int, payload: String, timeoutMs: Int = 500): Raw {
    Socket("127.0.0.1", port).use { s ->
        s.soTimeout = timeoutMs
        s.tcpNoDelay = true
        s.getOutputStream().write(payload.toByteArray(Charsets.ISO_8859_1))
        s.getOutputStream().flush()
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(16384)
        var eof = false
        try {
            while (true) {
                val n = s.getInputStream().read(tmp)
                if (n < 0) { eof = true; break }
                buf.write(tmp, 0, n)
            }
        } catch (e: SocketTimeoutException) {
            // 连接仍开着且没有新数据：keep-alive 的正常静默
        }
        return Raw(buf.toByteArray().toString(Charsets.ISO_8859_1), eof)
    }
}

/** 响应个数（正文里不会出现字面量 `HTTP/1.1 `） */
private fun responseCount(raw: String): Int = raw.split("HTTP/1.1 ").size - 1

/** 第 [idx] 个响应（0 起）的头部块 */
private fun headOf(raw: String, idx: Int = 0): String {
    val parts = raw.split("HTTP/1.1 ")
    if (idx + 1 >= parts.size) return ""
    return parts[idx + 1].substringBefore("\r\n\r\n")
}

private fun statusOf(raw: String, idx: Int = 0): Int =
    headOf(raw, idx).substringBefore("\r\n").trim().split(" ")[0].toInt()

private fun headerOf(raw: String, name: String, idx: Int = 0): String? =
    headOf(raw, idx).split("\r\n").drop(1)
        .firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }
        ?.substringAfter(':')?.trim()

/** 第 [idx] 个响应的正文（自动处理 chunked 成帧） */
private fun bodyOf(raw: String, idx: Int = 0): String {
    val parts = raw.split("HTTP/1.1 ")
    if (idx + 1 >= parts.size) return ""
    var rest = parts[idx + 1].substringAfter("\r\n\r\n")
    if (idx + 2 < parts.size) rest = rest.substringBefore("HTTP/1.1 ")
    if (headerOf(raw, "Transfer-Encoding", idx)?.contains("chunked", true) == true) {
        return dechunkText(rest)
    }
    return rest
}

private fun dechunkText(s: String): String {
    var rest = s
    val out = StringBuilder()
    while (true) {
        val nl = rest.indexOf("\r\n")
        if (nl < 0) break
        val size = rest.substring(0, nl).trim().substringBefore(';').toIntOrNull(16) ?: break
        if (size == 0) break
        val start = nl + 2
        if (start + size > rest.length) break
        out.append(rest, start, start + size)
        rest = rest.substring(start + size + 2)
    }
    return out.toString()
}
