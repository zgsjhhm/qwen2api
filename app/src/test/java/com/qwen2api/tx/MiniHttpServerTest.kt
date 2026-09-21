package com.qwen2api.tx

import com.qwen2api.tx.server.HttpRequest
import com.qwen2api.tx.server.HttpResponse
import com.qwen2api.tx.server.MiniHttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * MiniHttpServer 连接层测试（真实回环 TCP，零第三方依赖）。
 *
 * ## 为什么必须补这一层
 * `handleConnection` 里的 `while` 循环此前**只被单请求覆盖过**：所有既有测试
 *（GatewayE2ETest / FileUploadChainTest）都用 `HttpURLConnection` 发一个请求、
 * 读一个响应、断开。于是下面这些"连接级"行为一次都没被执行：
 *
 *  - keep-alive 复用：同一条连接上第二个请求能否被正确读出并回包；
 *  - 畸形/越界请求的**响应语义**：写回了什么？连接还复用吗？后续请求去哪了；
 *  - 请求体分帧：`Content-Length` 与 `Transfer-Encoding: chunked` 两条读数路径；
 *  - 请求行的解码与路由键：`req.path` 到底该不该做百分号解码。
 *
 * 这些点单独看都很小，但它们的用户可见症状全都指向同一类事故：
 * **客户端拿到一个语法正确、语义错位的响应，或者干脆只看到超时**。
 * 前者是响应走私/路由错配的温床（同一连接上的下一个请求会读到错位的响应体），
 * 后者让排障者无法判断是"网关挂了"还是"我请求发错了"。
 *
 * ## 为什么不走 HttpURLConnection
 * 它会把连接管理、重试、chunked 解码全部代劳，恰好把本测试要观察的东西
 *（原始字节、连接是否关闭、体上限是否生效）藏起来。
 * 因此这里直接开 [Socket] 读写字节，并用 `soTimeout` 区分"读完了"和"读超时"。
 *
 * 体上限用构造参数 [MiniHttpServerTest.SMALL_LIMIT] 压小到 2MB，
 * 否则"超限"用例得真发 120MB，测试会慢到不可接受。
 */
class MiniHttpServerTest {

    private lateinit var server: MiniHttpServer
    private var port: Int = 0

    /** 用例可替换的处理器（lambda 在调用时读取该字段，因此替换即时生效） */
    @Volatile
    private var handler: (HttpRequest, HttpResponse) -> Unit = { _, res ->
        res.end("ok".toByteArray())
    }

    /** 被处理器依次观察到的路径（用于断言路由键） */
    private val seenPaths = ArrayList<String>()

    @Before
    fun setUp() {
        synchronized(seenPaths) { seenPaths.clear() }
        server = MiniHttpServer(0, "127.0.0.1", SMALL_LIMIT, IDLE_TIMEOUT_MS) { req, res ->
            synchronized(seenPaths) { seenPaths.add(req.path) }
            handler(req, res)
        }
        server.start()
        port = server.boundPort
    }

    @After
    fun tearDown() {
        server.stop()
    }

    // ---------------- 基础设施 ----------------

    private class Raw(val text: String, val sawEof: Boolean)

    /**
     * 原始收发：写完就用**同一个连接**读到 EOF 或超时为止。
     *
     * [Raw.sawEof] 是这里的关键信号 —— keep-alive 的"活着"与"已关闭"在
     * 读侧都表现为"暂时没有更多数据"，只有 EOF 能区分二者。
     * 单看读取超时就断言，会把"服务端正常保持连接"误判成失败。
     */
    private fun rawSend(payload: String, timeoutMs: Int = 600): Raw {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
            val out = s.getOutputStream()
            out.write(payload.toByteArray(Charsets.ISO_8859_1))
            out.flush()
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
                // 读到超时：连接仍开着且没有新数据，属于 keep-alive 的正常静默
            }
            return Raw(buf.toByteArray().toString(Charsets.ISO_8859_1), eof)
        }
    }

    /** 响应个数（本测试的正文里不会出现字面量 `HTTP/1.1 `） */
    private fun responseCount(raw: String): Int = raw.split("HTTP/1.1 ").size - 1

    /** 取第 [idx] 个响应（0 起）的头部块 */
    private fun head(raw: String, idx: Int = 0): String {
        val parts = raw.split("HTTP/1.1 ")
        if (idx + 1 >= parts.size) return ""
        return parts[idx + 1].substringBefore("\r\n\r\n")
    }

    private fun statusOf(raw: String, idx: Int = 0): Int =
        head(raw, idx).substringBefore("\r\n").trim().split(" ")[0].toInt()

    private fun headerOf(raw: String, name: String, idx: Int = 0): String? =
        head(raw, idx).split("\r\n").drop(1)
            .firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }
            ?.substringAfter(':')?.trim()

    /** 第 [idx] 个响应的正文（自动处理 chunked 成帧） */
    private fun bodyOf(raw: String, idx: Int = 0): String {
        val parts = raw.split("HTTP/1.1 ")
        if (idx + 1 >= parts.size) return ""
        var rest = parts[idx + 1].substringAfter("\r\n\r\n")
        if (idx + 2 < parts.size) rest = rest.substringBefore("HTTP/1.1 ")
        val te = headerOf(raw, "Transfer-Encoding", idx)
        if (te?.contains("chunked", true) == true) return dechunk(rest)
        return rest
    }

    /** 解 chunked 成帧（按声明的字节数切，正文含 CRLF 也不受影响） */
    private fun dechunk(s: String): String {
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

    private fun get(target: String, headers: String = ""): String =
        "GET $target HTTP/1.1\r\nHost: 127.0.0.1\r\n$headers\r\n"

    /** 组 POST 请求：注意入参是**头部块含末尾 CRLF**，正文必须**紧跟空行之后不带额外 CRLF** */
    private fun post(headers: String, body: String): String =
        "POST /p HTTP/1.1\r\nHost: 127.0.0.1\r\n$headers\r\n$body"

    // ---------------- 1. keep-alive 与连接复用 ----------------

    @Test
    fun `two requests are served on the same connection`() {
        val r = rawSend(get("/first") + get("/second"))
        assertEquals("同连接两个请求都应被处理", 2, responseCount(r.text))
        assertEquals(200, statusOf(r.text, 0))
        assertEquals(200, statusOf(r.text, 1))
        assertEquals("两个请求的路径都要被 handlers 看到", 2, synchronized(seenPaths) { seenPaths.size })
    }

    @Test
    fun `keep-alive connection is not closed after one response`() {
        val r = rawSend(get("/only"))
        assertEquals(1, responseCount(r.text))
        assertEquals("keep-alive", headerOf(r.text, "Connection"))
        assertFalse("keep-alive 下不应关闭连接", r.sawEof)
    }

    @Test
    fun `connection close header is honored`() {
        val r = rawSend(get("/x", "Connection: close\r\n"))
        assertEquals("close", headerOf(r.text, "Connection"))
        assertTrue("收到 Connection: close 后必须关闭连接", r.sawEof)
    }

    /**
     * HTTP/1.0 的默认值是 close，而不是 keep-alive。
     *
     * 旧实现只检查 `Connection: close`，于是「HTTP/1.0 且没有 Connection 头」
     * 被当成可复用连接：服务端把响应写完就等下一个请求，而 1.0 客户端认为
     * 响应结束即连接结束，双方对"消息体边界"的理解出现分歧。
     * 现场表现是 1.0 客户端（curl --http1.0、部分老脚本、嵌入式 SDK）挂住等超时。
     */
    @Test
    fun `http10 without connection header closes`() {
        val r = rawSend("GET /x HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n")
        assertEquals("close", headerOf(r.text, "Connection"))
        assertTrue("HTTP/1.0 默认应关闭连接", r.sawEof)
    }

    @Test
    fun `http10 with explicit keep-alive stays open`() {
        val r = rawSend(
            "GET /a HTTP/1.0\r\nHost: 127.0.0.1\r\nConnection: keep-alive\r\n\r\n" +
                "GET /b HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n",
        )
        assertEquals("显式 keep-alive 的 1.0 请求应可复用连接", 2, responseCount(r.text))
        assertEquals("keep-alive", headerOf(r.text, "Connection", 0))
    }

    @Test
    fun `http11 close on second request stops`() {
        val r = rawSend(get("/a") + get("/b", "Connection: close\r\n"))
        assertEquals(2, responseCount(r.text))
        assertEquals("close", headerOf(r.text, "Connection", 1))
        assertTrue(r.sawEof)
    }

    // ---------------- 2. 请求行 / 路由键 ----------------

    /**
     * `req.path` **不能**做百分号解码。
     *
     * 移植自 Node 的 `new URL(req.url, base).pathname`，它同样是**保持编码**的。
     * 旧实现用 `URLDecoder.decode(rawPath, "UTF-8")` 把整串解开，于是
     * `%2F` 变成 `/`：`/v1/files/a%2Fb` 与 `/v1/files/a/b` 路由到同一个位置。
     * 对文件 id 这类**作为路径段传递的用户可控标识**，这意味着
     * "带特殊字符的 id"与"另一个 id"不可区分。
     */
    @Test
    fun `path keeps percent-encoding`() {
        rawSend(get("/v1/files/a%20b"))
        assertEquals(
            "path 应保留原始编码（与 Node 的 URL.pathname 一致）",
            listOf("/v1/files/a%20b"),
            synchronized(seenPaths) { seenPaths.toList() },
        )
    }

    @Test
    fun `encoded slash is not a path separator`() {
        rawSend(get("/v1/files/a%2Fb/content"))
        assertEquals(
            "编码斜杠不得被解码成路径分隔符",
            listOf("/v1/files/a%2Fb/content"),
            synchronized(seenPaths) { seenPaths.toList() },
        )
    }

    /**
     * 查询参数是表单编码语义：`+` 表示空格，`%20` 也表示空格。
     * 这与 path 的规则不同（path 里 `+` 就是加号），两条规则必须分开处理。
     */
    @Test
    fun `query params decode form encoding`() {
        var q: String? = null
        handler = { req, res -> q = req.queryParam("q"); res.end("ok".toByteArray()) }
        rawSend(get("/s?q=a+b%20c"))
        assertEquals("查询参数里 + 与 %20 都是空格", "a b c", q)
    }

    @Test
    fun `path plus sign is preserved literally`() {
        rawSend(get("/v1/files/a+b"))
        assertEquals(
            "path 里的 + 是字面加号，不是空格",
            listOf("/v1/files/a+b"),
            synchronized(seenPaths) { seenPaths.toList() },
        )
    }

    @Test
    fun `query without value yields empty string`() {
        var flag: String? = null
        handler = { req, res -> flag = req.queryParam("verbose"); res.end("ok".toByteArray()) }
        rawSend(get("/s?verbose"))
        assertEquals("", flag)
    }

    @Test
    fun `query separator inside path is respected`() {
        rawSend(get("/v1/files?limit=5"))
        assertEquals(
            "query 不得混进 path",
            listOf("/v1/files"),
            synchronized(seenPaths) { seenPaths.toList() },
        )
    }

    // ---------------- 3. 请求体分帧 ----------------

    @Test
    fun `content length body is read fully`() {
        var got = ""
        handler = { req, res -> got = req.bodyText(); res.end("ok".toByteArray()) }
        val body = "hello=world"
        rawSend(post("Content-Length: ${body.length}\r\n", body))
        assertEquals("hello=world", got)
    }

    @Test
    fun `chunked body is assembled`() {
        var got = ""
        handler = { req, res -> got = req.bodyText(); res.end("ok".toByteArray()) }
        rawSend(
            post(
                "Transfer-Encoding: chunked\r\n",
                "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n",
            ),
        )
        assertEquals("hello world", got)
    }

    @Test
    fun `chunked with extension is parsed`() {
        var got = ""
        handler = { req, res -> got = req.bodyText(); res.end("ok".toByteArray()) }
        rawSend(post("Transfer-Encoding: chunked\r\n", "4;ext=1\r\nabcd\r\n0\r\n\r\n"))
        assertEquals("abcd", got)
    }

    /**
     * 请求体上限必须对 **chunked** 同样生效。
     *
     * 旧实现只对 `Content-Length` 做上限检查；chunked 分支（`readChunked`）
     * 把数据读进一个 `ByteArrayOutputStream`，**全程没有任何上限**。
     * 一个声明 `Transfer-Encoding: chunked` 的客户端可以一直灌数据把进程撑爆，
     * 上限形同虚设 —— 本机是应用进程，OOM 的现场症状是"网关无故掉线"。
     */
    @Test
    fun `oversized chunked body is rejected`() {
        var handled = false
        handler = { _, res -> handled = true; res.end("ok".toByteArray()) }
        // 压小的上限是 2MB，这里推 3MB，必须在读完之前就拒绝
        val block = "a".repeat(64 * 1024)
        val sb = StringBuilder()
        sb.append("POST /p HTTP/1.1\r\nHost: 127.0.0.1\r\nTransfer-Encoding: chunked\r\n\r\n")
        repeat(48) {
            sb.append(Integer.toHexString(block.length)).append("\r\n")
                .append(block).append("\r\n")
        }
        sb.append("0\r\n\r\n")
        val r = rawSend(sb.toString(), timeoutMs = 3000)
        assertFalse("超限的 chunked 请求不得进入业务处理器", handled)
        assertEquals("超限体应回 413", 413, statusOf(r.text))
    }

    @Test
    fun `declared body over limit is rejected without reading it`() {
        var handled = false
        handler = { _, res -> handled = true; res.end("ok".toByteArray()) }
        // 只声明、不发送正文：若实现先去 readFully 会挂住直到超时；
        // 正确行为是看到声明值就立刻拒绝，因此本用例能在毫秒级完成。
        val r = rawSend(
            "POST /p HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 999999999\r\n\r\n",
            timeoutMs = 2000,
        )
        assertFalse(handled)
        assertEquals("声明体积超限应回 413", 413, statusOf(r.text))
    }

    @Test
    fun `invalid content length is rejected`() {
        var handled = false
        handler = { _, res -> handled = true; res.end("ok".toByteArray()) }
        val r = rawSend("POST /p HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: abc\r\n\r\n")
        assertFalse(handled)
        assertEquals("非法 Content-Length 必须显式拒绝", 400, statusOf(r.text))
    }

    /**
     * 重复 `Content-Length` 属于请求走私构造（两个前端/后端取不同值）。
     * 头部用 map 存储时旧值会被**静默覆盖**，取到哪个取决于实现细节 ——
     * 这种"看起来正常"的请求正是不该被静默接受的。
     */
    @Test
    fun `duplicate content length is rejected`() {
        var handled = false
        handler = { _, res -> handled = true; res.end("ok".toByteArray()) }
        val r = rawSend(
            "POST /p HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                "Content-Length: 5\r\nContent-Length: 10\r\n\r\nhello",
        )
        assertFalse("重复 Content-Length 不得进入业务", handled)
        assertEquals(400, statusOf(r.text))
    }

    /**
     * `Content-Length` 与 `Transfer-Encoding: chunked` 并存也是走私构造。
     * 旧实现优先取 CL 并**完全忽略** TE —— 于是同一个请求在网关这边
     * 按 5 字节读体、在中间反代那边按 chunked 读体，两者对"下一个请求
     * 从哪开始"的判断不同，同连接后续流量即被错位解释。
     */
    @Test
    fun `content length and chunked together are rejected`() {
        var handled = false
        handler = { _, res -> handled = true; res.end("ok".toByteArray()) }
        val r = rawSend(
            "POST /p HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                "Content-Length: 5\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n",
        )
        assertFalse("CL 与 TE 并存不得进入业务", handled)
        assertEquals(400, statusOf(r.text))
    }

    @Test
    fun `invalid chunk size is rejected`() {
        var handled = false
        handler = { _, res -> handled = true; res.end("ok".toByteArray()) }
        val r = rawSend(
            post("Transfer-Encoding: chunked\r\n", "zz\r\nhello\r\n0\r\n\r\n"),
        )
        assertFalse(handled)
        assertEquals("非法 chunk 尺寸应回 400", 400, statusOf(r.text))
    }

    // ---------------- 4. 畸形请求的响应语义 ----------------

    /**
     * 超长请求头必须**明确应答**。
     *
     * 旧实现是 `if (headerBytes.size > MAX_HEADER_BYTES) return null`：
     * `null` 在 `handleConnection` 里表示"连接结束"，于是服务端既不回 4xx
     * 也不回 5xx，直接关连接。客户端（以及中间的反向代理）看到的是一个
     * **空响应**，无从判断"我请求头太大了"还是"网关崩了"。
     * 431 这一层语义正是为这种情况准备的。
     */
    @Test
    fun `oversized header block is answered`() {
        val filler = buildString {
            repeat(3000) { append("X-Pad-$it: ").append("a".repeat(32)).append("\r\n") }
        }
        val r = rawSend("GET /x HTTP/1.1\r\nHost: 127.0.0.1\r\n$filler\r\n")
        assertTrue(
            "超长请求头必须有明确响应，实际: ${r.text.take(60)}",
            r.text.startsWith("HTTP/1.1 "),
        )
        assertEquals("超长请求头应答 431", 431, statusOf(r.text))
    }

    @Test
    fun `truncated request line is answered`() {
        // "GET" 后面缺目标：读到了完整头部块但没有合法请求行
        val r = rawSend("GET\r\n\r\n")
        assertTrue("残破请求行应有明确响应（不是静默断连）", r.text.startsWith("HTTP/1.1 "))
        assertEquals(400, statusOf(r.text))
    }

    @Test
    fun `error response closes the connection`() {
        val r = rawSend("POST /p HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: abc\r\n\r\n")
        assertEquals("close", headerOf(r.text, "Connection"))
        assertTrue("错误响应后连接不可复用", r.sawEof)
    }

    /**
     * 请求体读到一半连接断掉：必须拒绝，**不能**把残缺 body 当正常的。
     *
     * 旧实现 `readFully` 在 `read() < 0` 时直接 `copyOf(off)` 返回截断数据，
     * 调用方完全无从知道"这个 body 是残缺的"。对 JSON 请求，症状是
     * "随机某次请求报 JSON 解析失败"；对上传请求，症状是"文件偶尔损坏"。
     */
    @Test
    fun `truncated body is rejected`() {
        var handled = false
        handler = { _, res -> handled = true; res.end("ok".toByteArray()) }
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 2000
            s.getOutputStream().write(
                ("POST /p HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 50\r\n\r\n" +
                    "short").toByteArray(Charsets.ISO_8859_1),
            )
            s.getOutputStream().flush()
            // 半关闭输出：服务端会读到 EOF，此时 body 只有 5/50 字节
            s.shutdownOutput()
            val text = try {
                s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            } catch (e: SocketTimeoutException) {
                ""
            }
            assertFalse("残缺 body 不得进入业务处理器", handled)
            assertTrue("应给出明确错误响应，实际: ${text.take(60)}", text.startsWith("HTTP/1.1 "))
            assertEquals(400, text.substringBefore("\r\n").split(" ")[1].toInt())
        }
    }

    /**
     * handler 抛异常时回 500，**并且必须关闭连接**。
     *
     * 500 的正文长度与请求体无关，客户端无从知道"我这次请求到底有没有被消费"。
     * 若此时保持 keep-alive，客户端可能复用连接发下一个请求，而服务端
     *（或中间反代）对上一个请求体的边界理解已经错位 —— 正是响应走私的经典条件。
     */
    @Test
    fun `handler exception returns 500 and closes`() {
        handler = { _, _ -> throw IllegalStateException("boom") }
        val r = rawSend(get("/x"))
        assertEquals(500, statusOf(r.text))
        assertEquals("500 后必须关闭连接", "close", headerOf(r.text, "Connection"))
        assertTrue(r.sawEof)
    }

    /**
     * 头已发出后 handler 抛异常：**流必须正确收尾，连接必须关闭**。
     *
     * 这里不能断言 `Connection: close` —— 响应头在异常发生**之前**就已经
     * 在线路上了，服务端无法回头修改它。此时唯一可用的信号是**实际的 socket 关闭**，
     * 因此本用例断言的是两件真正能保证的事：
     *  1. chunked 流有终结块。缺终结块时客户端只能一直读到连接关闭才知道正文结束，
     *     对 SSE 长连接就是**挂死**（旧实现正是如此：头发出后的异常路径完全跳过收尾）；
     *  2. 连接被真正关闭（否则客户端可能复用这条"已被污染"的连接发下个请求）。
     *
     * 另外必须保留已推送的正文，且**不得**追加 500 —— 那会把错误 JSON 混进
     * 客户端已按 200 解析的流里。
     */
    @Test
    fun `handler exception after headers keeps stream intact`() {
        handler = { _, res ->
            res.status(200)
            res.writeHead(200)
            res.writeChunk("partial")
            throw IllegalStateException("late boom")
        }
        val r = rawSend(get("/x"))
        assertEquals("正文不能出现 500", 200, statusOf(r.text))
        assertTrue("应保留已推流的内容", bodyOf(r.text).contains("partial"))
        assertTrue("chunked 流必须有终结块，否则客户端会挂死等连接关闭", r.text.contains("0\r\n\r\n"))
        assertFalse("异常后不得把 client 误认为可复用的 Connection 头",
            headerOf(r.text, "Connection").equals("close", true) &&
                !r.sawEof)
        assertTrue("异常后必须真的关闭连接", r.sawEof)
    }

    // ---------------- 5. 响应成帧 ----------------

    @Test
    fun `json response declares content length`() {
        handler = { _, res ->
            val b = "{\"ok\":true}".toByteArray()
            res.header("Content-Type", "application/json")
            res.header("Content-Length", b.size.toString())
            res.writeHead(200)
            res.end(b)
        }
        val r = rawSend(get("/x"))
        assertEquals("字节数（不是字符数）", "11", headerOf(r.text, "Content-Length"))
        assertEquals("{\"ok\":true}", bodyOf(r.text))
    }

    @Test
    fun `204 response has no body and no framing`() {
        handler = { _, res -> res.status(204); res.writeHead(204); res.end() }
        val r = rawSend(get("/x"))
        assertEquals(204, statusOf(r.text))
        assertEquals(null, headerOf(r.text, "Content-Length"))
        assertEquals(null, headerOf(r.text, "Transfer-Encoding"))
    }

    @Test
    fun `default 200 without length uses chunked framing`() {
        handler = { _, res -> res.writeHead(200); res.writeChunk("abc"); res.end() }
        val r = rawSend(get("/x"))
        assertEquals("chunked", headerOf(r.text, "Transfer-Encoding"))
        assertEquals("abc", bodyOf(r.text))
    }

    /**
     * 声明长度与实际正文不符时，连接**不得**被复用。
     *
     * 旧实现的 `end(bytes)` 只检查 `bytes.isNotEmpty()` 时的一致性，
     * 而"声明了长度但一个字节都没写"（`end()` 无参重载）恰好绕过检查：
     * 客户端按声明的 100 字节去读，读到的是**下一个响应**的字节。
     */
    @Test
    fun `mismatched declared length breaks the connection`() {
        handler = { _, res ->
            res.header("Content-Length", "100")
            res.writeHead(200)
            res.end() // 实际 0 字节，却声明 100
        }
        val r = rawSend(get("/a") + get("/b"))
        assertEquals("损坏的响应不得复用连接", 1, responseCount(r.text))
        assertTrue(r.sawEof)
        assertNotNull("应留下诊断信息", server.lastError)
    }

    @Test
    fun `mismatched body length breaks the connection`() {
        handler = { _, res ->
            res.header("Content-Length", "100")
            res.writeHead(200)
            res.end("short".toByteArray())
        }
        val r = rawSend(get("/a") + get("/b"))
        assertEquals(1, responseCount(r.text))
        assertTrue(r.sawEof)
        assertNotNull(server.lastError)
    }

    // ---------------- 6. 大正文与边界 ----------------

    @Test
    fun `large body within limit is read`() {
        var len = 0
        handler = { req, res -> len = req.body.size; res.end("ok".toByteArray()) }
        val body = "z".repeat(1500 * 1024) // 上限 2MB 之内
        rawSend(
            post("Content-Length: ${body.length}\r\n", body),
            timeoutMs = 5000,
        )
        assertEquals(1500 * 1024, len)
    }

    @Test
    fun `exactly at limit is accepted`() {
        var len = 0
        handler = { req, res -> len = req.body.size; res.end("ok".toByteArray()) }
        val body = "z".repeat(SMALL_LIMIT.toInt())
        rawSend(post("Content-Length: ${body.length}\r\n", body), timeoutMs = 5000)
        assertEquals("正好等于上限应被接受（上限是包含语义）", SMALL_LIMIT, len.toLong())
    }

    /**
     * 空闲连接不能永久占住 worker。
     *
     * 旧实现 `soTimeout = 0`（永不过期）+ 固定 12 个 worker 线程 =
     * **12 个"连上但不发任何数据"的连接就能把服务打死**：accept 仍在进行，
     * 但没有任何 worker 可用，后续所有请求都在队列里等，对外表现为
     * "网关突然不响应，日志里什么都没有"。这是最廉价的一类拒绝服务。
     *
     * 把空闲超时压到 300ms 后验证：连接会被服务端主动收尾，worker 立刻释放。
     */
    @Test
    fun `idle connection is timed out`() {
        val idle = MiniHttpServer(0, "127.0.0.1", SMALL_LIMIT, 300) { _, res ->
            res.end("ok".toByteArray())
        }
        try {
            idle.start()
            Socket("127.0.0.1", idle.boundPort).use { s ->
                s.soTimeout = 3000
                val start = System.currentTimeMillis()
                // 连上但不发任何字节：服务端应在空闲超时后关闭连接
                val n = s.getInputStream().read()
                val elapsed = System.currentTimeMillis() - start
                assertEquals("空闲连接应被服务端关闭（read 返回 EOF）", -1, n)
                assertTrue("应在空闲超时附近关闭，实际 ${elapsed}ms", elapsed in 200..2500)
            }
            // worker 已释放：仍能正常服务新请求
            Socket("127.0.0.1", idle.boundPort).use { s ->
                s.soTimeout = 3000
                s.getOutputStream().write(get("/after").toByteArray(Charsets.ISO_8859_1))
                s.getOutputStream().flush()
                val text = s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
                assertTrue("空闲连接被回收后服务应仍可用: ${text.take(40)}", text.startsWith("HTTP/1.1 200"))
            }
        } finally {
            idle.stop()
        }
    }

    /**
     * 请求一旦开始，就不该被空闲超时误杀。
     *
     * 这是上一条的超时机制必须"只作用于空闲阶段"的原因：
     * 大文件上传、SSE 长连接在协议层面本来就会长时间没有新字节，
     * 若对整条连接统一设短超时，它们会被当成空闲连接掐掉。
     */
    @Test
    fun `slow upload is not killed by idle timeout`() {
        val server2 = MiniHttpServer(0, "127.0.0.1", SMALL_LIMIT, 300) { req, res ->
            res.end("got:${req.body.size}".toByteArray())
        }
        try {
            server2.start()
            Socket("127.0.0.1", server2.boundPort).use { s ->
                s.soTimeout = 5000
                val out = s.getOutputStream()
                val body = "x".repeat(32)
                out.write(
                    ("POST /p HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                        "Content-Length: ${body.length}\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
                )
                out.flush()
                // 头部发出后停顿 800ms（> 空闲超时），再发正文
                Thread.sleep(800)
                out.write(body.toByteArray(Charsets.ISO_8859_1))
                out.flush()
                val text = s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
                assertTrue(
                    "请求读取期间的停顿不该被空闲超时误杀: ${text.take(60)}",
                    text.contains("got:32"),
                )
            }
        } finally {
            server2.stop()
        }
    }

    @Test
    fun `stop is idempotent and releases port`() {
        server.stop()
        server.stop() // 第二次不应抛
        assertFalse(server.isRunning)
    }

    private companion object {
        /** 压小的请求体上限：让"超限"用例不必真发 120MB */
        const val SMALL_LIMIT = 2L * 1024 * 1024

        /** 默认用例的空闲超时：给足余量，避免慢环境下的偶发失败 */
        const val IDLE_TIMEOUT_MS = 60_000
    }
}
