package com.qwen2api.tx.server

import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 解析后的 HTTP 请求 */
class HttpRequest(
    val method: String,
    val rawTarget: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
    /** 请求行里的协议版本字面量（`HTTP/1.0` / `HTTP/1.1`）；缺失时按 1.1 处理 */
    val httpVersion: String = "HTTP/1.1",
) {
    val hostHeader: String get() = headers["host"].orEmpty()
    fun header(name: String): String? = headers[name.lowercase()]
    fun bodyText(): String = String(body, Charsets.UTF_8)
    fun queryParam(name: String): String? = query[name]

    /**
     * 用于**路由判定**的方法。
     *
     * HEAD 与 GET 指向同一个资源表示，区别只在"不要正文"（RFC 7231 §4.3.2），
     * 因此路由必须把 HEAD 当作 GET 处理。否则 `HEAD /v1/models` 会掉进 404 ——
     * 而 HEAD 恰恰是探活/连通性预检最常用的方法，调用方会误判成"网关没有这个接口"。
     * 正文的抑制由 [HttpResponse] 负责（它知道本次请求是不是 HEAD）。
     */
    val effectiveMethod: String get() = if (method.equals("HEAD", true)) "GET" else method

    /**
     * 取第 [index] 个路径段并做**百分号解码**（0 起，忽略空段）。
     *
     * 路由比对用的是编码原样的 [path]（原因见 [MiniHttpServer.readRequest]），
     * 但取出来的标识（文件 id 等）最终要还原成用户看到的字符串。
     * 因此解码推迟到这一层，且只对**单个路径段**做：
     *  - 不对整条路径解码：否则段内的 `%2F` 会被还原成 `/`，与真实分隔符混同；
     *  - 段内不做 `+` -> 空格：path 的规则与 query 的表单编码不同，
     *    先把 `+` 转义成 `%2B` 再解，保证字面加号不被吞掉。
     */
    fun pathParam(index: Int): String? {
        val segs = path.split('/').filter { it.isNotEmpty() }
        val raw = segs.getOrNull(index) ?: return null
        return try {
            URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            raw
        }
    }
}

/**
 * 响应写出器。
 *
 * ## 头部为什么要延迟到"第一次写正文"才落盘
 * 调用方有两种风格：
 *  - `sendJson` 型：先 `header("Content-Length", n)` 再 `writeHead()`，元信息齐全；
 *  - `SSE` 型：`writeHead(200)` 之后才 `writeChunk(...)`，长度事先不知。
 *
 * 而 HEAD 的语义要求在**收尾时**才知道正文总长（头部要与同一 URI 的 GET 一致），
 * 空正文的 200 也一样（该不该声明 0 长度取决于最终有没有写字节）。
 * 若 `writeHead` 直接落盘，这两件事都来不及判断 —— 于是旧实现把正文照样发给 HEAD、
 * 把空 200 声明成 chunked 流。
 *
 * 因此这里改成：`writeHead` 只**锁定**状态行与头部并把本次响应的 framing 定下来，
 * 真正的字节在第一个正文字节、或 [end] 收尾时一次性写出（[flushHead]）。
 * 对客户端完全透明（HTTP 头部本来就该在正文前完整的到达），但服务端获得了
 * "在写出任何字节之前还能修正 framing" 的窗口。
 */
class HttpResponse(val socket: Socket) {
    private var status = 200
    private val headers = LinkedHashMap<String, String>()
    var keepAlive: Boolean = true

    /**
     * 锁定后的头部快照；null 表示尚未 [writeHead]。
     *
     * "已发出"对外**只看这个字段** —— 它不是"字节已落盘"，而是"客户端一定会看到这些头"
     *（落盘时机最晚在 [end]）。调用方据此判断"还能不能补一个 500 兜底"，
     * 语义上正是它需要的那个问题。
     *
     * 之所以存**快照**而不是预渲染的字符串：收尾时可能还要修正 framing
     *（空正文 200 从 chunked 改回 `Content-Length: 0`）。若在 [writeHead] 里就把
     * 头部渲染成字符串，后面的修正就写不进去 —— 线路上的头与内部状态会分叉。
     */
    private var lockedHeaders: LinkedHashMap<String, String>? = null

    /** 锁定时的状态码（状态行只写一次，第二次 writeHead 不得改写它） */
    private var lockedStatus = 200

    /** 头部字节是否真的已经在线路上（用于避免重复落盘） */
    private var headFlushed = false

    /** 是否已经有正文字节写出 —— 决定收尾时还能不能把 chunked 改成 `Content-Length: 0` */
    private var bodyWritten = false

    /**
     * 本次响应对应的**真实请求方法**（不是 [HttpRequest.effectiveMethod]）。
     *
     * 只有 framing 决策需要它：HEAD 的头部必须与同一 URI 的 GET 一致，但绝不能带正文。
     * 由 [MiniHttpServer] 在解析出请求后立刻赋值。
     */
    @Volatile
    var requestMethod: String = "GET"

    /**
     * 本次响应对应的请求协议版本。
     *
     * `HTTP/1.0` 不认识 `Transfer-Encoding`，因此 1.0 下**禁止**自动改用 chunked。
     */
    @Volatile
    var httpVersion: String = "HTTP/1.1"

    private val isHead: Boolean get() = requestMethod.equals("HEAD", true)

    /** 本次响应是否使用 chunked 成帧（由 [writeHead] 决定，锁定后恒定） */
    private var isChunked = false

    /** 响应正文是否已收尾；保证终结块/一致性校验只做一次 */
    private var ended = false

    /** 响应头是否已锁定。锁定后状态行/头部都不可再改，调用方据此决定能否补 500 兜底。 */
    val headerSent: Boolean
        get() = lockedHeaders != null

    fun status(code: Int): HttpResponse {
        status = code; return this
    }

    /**
     * 追加一个响应头。
     *
     * 头部一旦锁定（[writeHead] 之后）本调用**直接报错**：framing 已经定下来，
     * 迟到的 `Content-Length` 若生效就会与已写出的正文对不上 ——
     * 客户端按新长度去读，多出来的字节被当成下一个响应（响应走私的成立条件）。
     * 静默丢弃也没好到哪去：调用方以为自己设置成功了，实际线路上的头与它的预期不一致，
     * 而这类差异在测试里**永远不会**显现。所以宁可把它变成一次响亮的失败。
     * （Node 的 `setHeader` 在 `writeHead` 之后同样抛 ERR_HTTP_HEADERS_SENT。）
     */
    fun header(k: String, v: String): HttpResponse {
        if (lockedHeaders != null) {
            failedWriteAfterEnd = true
            throw IllegalStateException(
                "响应头已锁定（writeHead 已调用），不能再设置 $k；本次响应的 framing 已不可信",
            )
        }
        headers[k] = v
        return this
    }

    /**
     * 锁定状态行与头部，并决定本次响应的 framing。
     *
     * 决策顺序（每条都有明确的外部症状，见各分支注释）：
     *  1. 无消息体状态码（204/304/1xx）→ 不加任何 framing；
     *  2. HEAD → 不加任何 framing（GET 已有的长度声明照原样保留）；
     *  3. HTTP/1.0 且没有长度声明 → 禁止 chunked，靠关闭连接定界；
     *  4. `200` 且调用方未声明长度 → 自动 chunked；
     *  5. 其余 → 显式 `Content-Length: 0`。
     *
     * 重复调用只以第一次为准：状态行只能写一次，第二次改写等于伪造响应。
     */
    @Synchronized
    fun writeHead(code: Int) {
        status = code
        if (lockedHeaders != null) return
        if (!headers.containsKey("Content-Length") && !headers.containsKey("Transfer-Encoding")) {
            when {
                // 这几个状态码按规范不带消息体
                code == 204 || code == 304 || code in 100..199 -> Unit
                // HEAD：头部要与同一 URI 的 GET 一致，但**没有正文**。
                // 这里既不能声明 0 长度（会和 GET 对不上，客户端无法据此预判体积），
                // 也不能改 chunked（没有正文就没有块可发，客户端会一直等块长度行）。
                isHead -> Unit
                // HTTP/1.0 不认识 Transfer-Encoding。若照 1.1 的默认改 chunked，
                // 客户端会把 `3\r\nabc\r\n0\r\n\r\n` 当成**业务数据**（字面量块长度混进正文），
                // curl --http1.0、老 SDK、嵌入式 HTTP 库全部中招。
                // 1.0 下没有长度声明时的唯一合法选择是靠关闭连接定界。
                !httpVersion.equals("HTTP/1.1", true) -> keepAlive = false
                code == 200 -> {
                    headers["Transfer-Encoding"] = "chunked"
                    isChunked = true
                }
                // 302 这类无正文响应：显式声明 0 长度，不留歧义
                else -> headers["Content-Length"] = "0"
            }
        }
        if (!headers.containsKey("Connection")) {
            headers["Connection"] = if (keepAlive) "keep-alive" else "close"
        }
        lockedStatus = code
        lockedHeaders = LinkedHashMap(headers)
    }

    /**
     * 把已锁定的头部写到线路上（幂等）。
     *
     * 调用点只有三处：第一次写正文前、HEAD 收尾时、以及没有正文的收尾。
     * 头部字节与正文的顺序由 HTTP 自身保证，因此"延迟落盘"对客户端不可见。
     */
    private fun flushHead() {
        if (headFlushed) return
        val head = lockedHeaders ?: return
        headFlushed = true
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(lockedStatus).append(' ').append(reason(lockedStatus))
            .append("\r\n")
        for ((k, v) in head) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        val out = socket.getOutputStream()
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    /**
     * 收尾后是否发生过"越界写入"（[writeChunk] / [header]）。
     *
     * 服务器据此关闭连接：调用方的写入已经失败，说明这条响应不可信，
     * 而同连接上的下一个请求会被错位的边界解释。
     */
    @Volatile
    var failedWriteAfterEnd: Boolean = false
        private set

    /**
     * 写入一段正文。语义分为三条互斥的路径：
     *
     * 1) 已用 [header] 显式给出 `Content-Length` 时，本方法只写正文、不做任何成帧。
     *    这是 sendJson 的路径：调用方已算好长度，再补 chunked 头会直接破坏响应。
     *    代价是调用方必须保证「声明长度 == 实际正文长度」，否则走 [end] 时会在下方直接抛错。
     *
     * 2) 未声明长度且协议为 1.1 时按 chunked 成帧 —— 这是 SSE 路径，
     *    能边生成边下发而不必预知总长。
     *
     * 3) 未声明长度且协议为 1.0 时**裸写字节**（framing 交给关闭连接）。
     *
     * HEAD 请求下正文一律丢弃（头照发）。
     */
    @Synchronized
    fun writeChunk(text: String) {
        ensureFraming()
        // 已经收尾过就绝不能再往线路追加字节。旧实现把这次写入直接追加进流里，
        // 正文于是比已声明的 Content-Length 长 —— 多出来的字节会被客户端
        // 当成**下一个响应**的开头（响应走私的成立条件）。
        // 这里显式报错，让调用方（网关）知道响应已不可信并把连接关掉。
        if (ended) {
            failedWriteAfterEnd = true
            throw IllegalStateException(
                "响应已收尾（end() 已调用），不能再追加正文；本次响应的 framing 已不可信",
            )
        }
        if (isHead) return // HEAD：头（已锁定）照发，正文丢弃
        val body = text.toByteArray(Charsets.UTF_8)
        if (body.isEmpty()) return
        bodyWritten = true
        flushHead()
        val out = socket.getOutputStream()
        if (isChunked) {
            out.write(body.size.toString(16).toByteArray(Charsets.ISO_8859_1))
            out.write(CRLF)
            out.write(body)
            out.write(CRLF)
        } else {
            out.write(body)
        }
        out.flush()
    }

    /**
     * 收尾并写出正文（若还有未写出的部分）。
     *
     * 关键顺序：**先修正 framing，再落盘头部**，最后写正文/终结块。
     *  - HEAD：头部已锁定，正文整体丢弃，不写任何终结块；
     *  - 空正文且头部已按 chunked 锁定、且**一个正文字节都没写过**
     *   → 改回 `Content-Length: 0`（`0\r\n\r\n` 的语义是"一条已经结束的流"，
     *     与"长度为零的正文"是两件事，对客户端、日志与反代都不同）；
     *    但如果已经推过流（SSE 已发了几帧），就必须保持 chunked 并写终结块，
     *    否则客户端会一直等块长度行 —— 对 SSE 长连接就是挂死；
     *  - 非 chunked 且声明长度与实际不符 → 抛错，连接不可复用。
     *
     * 幂等：重复调用只生效一次。
     */
    @Synchronized
    fun end(bytes: ByteArray) {
        if (ended) return
        if (lockedHeaders == null) {
            // 无正文的兜底终态：不写 0 长度上限，让 writeHead 按状态码补 framing。
            // 例如 `res.status(404); res.end()` 应得到 404 + Content-Length: 0。
            if (bytes.isNotEmpty() && !isHead) headers["Content-Length"] = bytes.size.toString()
            writeHead(status)
        }
        // 204/304/1xx 按规范不带消息体，也不允许出现 chunked 终结块
        if (lockedStatus == 204 || lockedStatus == 304 || lockedStatus in 100..199) {
            flushHead()
            ended = true
            return
        }
        // HEAD：正文整体丢弃（头已锁定），也不写终结块
        if (isHead) {
            flushHead()
            ended = true
            return
        }
        val head = lockedHeaders!!
        if (isChunked && bytes.isEmpty() && !bodyWritten) {
            // 重分类为空正文：去掉 Transfer-Encoding，改显式 0 长度。
            // 此时头部还**没落盘**（writeHead 只锁定），因此这次修正是可见且无副作用的。
            head.remove("Transfer-Encoding")
            head["Content-Length"] = "0"
            isChunked = false
        }
        if (!isChunked && bytes.isEmpty() && !head.containsKey("Content-Length") && !keepAlive) {
            // HTTP/1.0 的空正文（见 writeHead 的 1.0 分支）：既没有长度也没 chunked，
            // 客户端无从判断正文到哪结束，必须靠关闭连接定界。
            head["Content-Length"] = "0"
        }
        val declared = head["Content-Length"]?.toIntOrNull()
        if (!isChunked && declared != null && declared != bytes.size) {
            // 声明与实际不一致时连接已不可信：把 framing 交给 close，并让日志留下证据。
            // 必须**先**标记结束，否则本异常会被当作"处理器失败"再走一次收尾路径，
            // 把已经错位的响应再补一刀。
            //
            // 这里**不能**"以实际字节数为准"去改写 Content-Length：那等于替调用方
            // 掩盖了它的计算错误（典型是按字符数而非字节数），而客户端可能已经在
            // 按声明值预分配/预判进度。把它暴露出来才是可修的。
            flushHead()
            ended = true
            throw IllegalStateException(
                "Content-Length=$declared 与实际正文 ${bytes.size} 字节不一致（响应 framing 已损坏）",
            )
        }
        flushHead()
        val out = socket.getOutputStream()
        if (isChunked) {
            // chunked 收尾：0 长度块 + 空 trailer
            if (bytes.isNotEmpty()) {
                out.write(bytes.size.toString(16).toByteArray(Charsets.ISO_8859_1))
                out.write(CRLF)
                out.write(bytes)
                out.write(CRLF)
            }
            out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        } else if (bytes.isNotEmpty()) {
            out.write(bytes)
        }
        out.flush()
        ended = true
    }

    @Synchronized
    fun end() = end(ByteArray(0))

    /** 首次写正文前确保响应头已锁定（framing 决策在 [writeHead] 内完成） */
    private fun ensureFraming() {
        if (lockedHeaders == null) writeHead(status)
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> "Status"
    }

    private companion object {
        val CRLF = "\r\n".toByteArray(Charsets.ISO_8859_1)
    }
}

/**
 * 请求解析阶段的确定性错误 —— 区别于 socket 异常。
 *
 * 之所以需要区分：解析失败必须**先回一个明确的 4xx 再关连接**，
 * 而 socket 读出异常（对端先关、RST）往往已无法写出响应，只能静默收尾。
 * 旧实现把所有情况混在 `catch (e: Exception)` 里回 400，
 * 且把 `null`（连接正常结束）也当成"检查错误"，两类语义互相污染。
 */
internal class MalformedRequest(val status: Int, message: String) : Exception(message)

internal object HttpStatus {
    /** 请求实体过大 —— 对本服务而言专指请求体超过 [MiniHttpServer] 的 maxBodyBytes */
    const val PAYLOAD_TOO_LARGE = 413

    /** 请求头字段过大 —— 超长请求头块属于"头部"而非"实体" */
    const val REQUEST_HEADER_FIELDS_TOO_LARGE = 431

    /** 方法不被该路径允许 —— 与"路径不存在"（404）严格区分：调用方据此判断是改方法还是改路径 */
    const val METHOD_NOT_ALLOWED = 405
}

/**
 * 极简 HTTP/1.1 服务器（零依赖，支持流式 chunked 响应）。
 *
 * 用途：在 Android 设备上提供 OpenAI 兼容端点 + 管理页，
 * 取代原项目基于 Node http 模块的服务端。
 */
class MiniHttpServer(
    private val port: Int,
    private val host: String,
    /**
     * 单个请求体的上限（字节），对 `Content-Length` 与 `chunked` **两条路径一致生效**。
     *
     * 放成构造参数只为可测性：默认即生产值，单测可压到 2MB 来覆盖"超限"分支，
     * 否则那几条用例得真发 120MB，慢到没法进常规回归。
     */
    private val maxBodyBytes: Long = MAX_BODY_BYTES,
    /**
     * **空闲**连接的超时（毫秒）：只作用于"等待一个新请求的第一个字节"这段。
     *
     * 为什么必须与请求读取期分开：
     *  - 旧实现 `SOCKET_TIMEOUT_MS = 0`（永不过期）是想照顾 SSE 长连接，
     *    但线程池只有 [MAX_WORKERS] 个 worker —— 12 个不发数据的空连接
     *    就能把 worker 全部占死，之后所有请求都排队等不到处理，
     *    表现是"网关突然不响应且没有任何日志"。这属于最廉价的一类拒绝服务；
     *  - 但也不能对整条连接设短超时：一个正常的大文件上传中间停顿几秒
     *    就会被误杀。因此超时只加在**空闲等待阶段**，请求一旦开始
     *    （读到第一个字节）就解除，读写耗时不设限。
     */
    private val idleTimeoutMs: Int = IDLE_TIMEOUT_MS,
    private val handler: suspend (HttpRequest, HttpResponse) -> Unit,
) {
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** 实际监听端口（端口为 0 时由系统分配） */
    @Volatile
    var boundPort: Int = port
        private set

    fun start() {
        if (isRunning) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(java.net.InetSocketAddress(host, port), 64)
        serverSocket = ss
        boundPort = ss.localPort
        val pool = Executors.newFixedThreadPool(MAX_WORKERS) { r ->
            Thread(r, "http-worker").apply { isDaemon = true }
        }
        executor = pool
        isRunning = true
        Thread({ acceptLoop(ss, pool) }, "http-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) { /* ignore */ }
        serverSocket = null
        executor?.shutdownNow()
        executor = null
    }

    private fun acceptLoop(ss: ServerSocket, pool: ExecutorService) {
        while (isRunning) {
            val socket = try {
                ss.accept()
            } catch (e: Exception) {
                if (isRunning) lastError = e.message
                break
            }
            // 线程池饱和时 execute 会直接抛 RejectedExecutionException：
            // 此时必须**主动关闭**这条新连接，不能只是让它挂在 accept 队列里。
            // 客户端会立刻看到连接被关闭（可重试），而不是等到读超时才失败。
            try {
                pool.execute { handleConnection(socket) }
            } catch (e: Exception) {
                lastError = e.message
                try { socket.close() } catch (e2: Exception) { /* ignore */ }
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        // 空闲超时见 [idleTimeoutMs] 注释：只防"占着连接不发请求"，
        // 请求开始读取后由 [awaitRequestStart] 解除，避免误杀慢上传/SSE。
        socket.soTimeout = idleTimeoutMs
        try {
            socket.tcpNoDelay = true
            val input = java.io.PushbackInputStream(socket.getInputStream(), 1)
            // HTTP/1.0 的默认连接语义是 close，只有 1.1 才是 keep-alive；
            // 且 1.0 客户端必须显式发送 `Connection: keep-alive` 才允许复用连接。
            // 旧实现一律按 1.1 的默认值处理，1.0 客户端会把"响应写完了"当成
            // "响应结束"，而服务端还在等同一条连接上的下一个请求 —— 双方对消息体
            // 边界的理解分歧，现场表现就是老客户端/嵌入式 SDK 挂住等超时。
            var stop = false
            while (isRunning && !socket.isClosed && !stop) {
                val res = HttpResponse(socket)
                val readable = try {
                    // 空闲等待阶段：超时说明这条连接只是"占着不放"，按正常结束处理（不记错误）
                    awaitRequestStart(socket, input)
                    readRequest(input)
                } catch (e: MalformedRequest) {
                    // 畸形请求：先回明确的 4xx 再关连接，且**不得**进入业务处理器。
                    // 用标志而不是 break —— 让 finally 里的 socket.close() 统一收尾，
                    // 也避免把"解析错误"记进 lastError（它对排障没有增量信息）。
                    res.keepAlive = false
                    stop = true
                    sendSimpleError(res, e.status, e.message.orEmpty())
                    continue
                } catch (e: java.net.SocketTimeoutException) {
                    // 空闲超时：这是一条"连上了却不发请求"的连接，正常收尾即可。
                    // 不记 lastError —— 它不是异常，是防 worker 占用的预期行为。
                    res.keepAlive = false
                    stop = true
                    continue
                } catch (e: Exception) {
                    // socket 层异常（对端先关、RST、读超时）：此时通常已写不出响应，
                    // 只记录原因并结束连接。
                    res.keepAlive = false
                    stop = true
                    lastError = e.message
                    continue
                }
                val req = readable ?: break
                // 把请求的方法与协议版本交给响应写出器：HEAD 要抑制正文，
                // HTTP/1.0 不能使用 chunked。这两件事都只能在响应侧决定。
                res.requestMethod = req.method
                res.httpVersion = req.httpVersion
                val keepAliveRequested =
                    req.headers["connection"]?.contains("keep-alive", true) == true
                res.keepAlive = when {
                    // 显式 close 永远优先
                    req.headers["connection"]?.contains("close", true) == true -> false
                    // 1.0 客户端只有显式 keep-alive 才能复用
                    !req.httpVersion.equals("HTTP/1.1", true) -> keepAliveRequested
                    // 1.1：默认复用，除非上面命中了显式 close
                    else -> true
                }
                var handlerFailed = false
                try {
                    kotlinx.coroutines.runBlocking { handler(req, res) }
                } catch (e: Exception) {
                    handlerFailed = true
                    // 关键：异常之后必须关闭连接。
                    // 500 的正文长度与本次请求体无关，客户端无从知道请求有没有被消费；
                    // 若继续 keep-alive，下一个请求会被服务端/中间反代按错位的边界解析，
                    // 正是响应走私的成立条件。
                    //
                    // 必须在写出响应**之前**置位：`Connection` 头是在 writeHead 里
                    // 依据 res.keepAlive 决定的，先写头再改标志等于白改 ——
                    // 客户端会看到 `Connection: keep-alive` 而连接随即被关闭。
                    res.keepAlive = false
                    if (!res.headerSent) {
                        // 头未锁定：可以安全地回一个完整的 500
                        sendSimpleError(res, 500, "internal error: ${e.message}")
                    } else {
                        // 头已锁定（典型是 SSE 已推了几帧）：任何 500 都只会污染
                        // 客户端已解析的正文。此时唯一能做的是**把流正确收尾**：
                        // chunked 缺终结块会让客户端一直读到连接关闭才知道正文结束，
                        // 对 SSE 长连接就是挂死。
                        lastError = "handler failed after headers sent: ${e.message}"
                    }
                }
                try {
                    // 无论处理器成败都要走 end()：它负责 chunked 终结块和
                    // Content-Length 一致性校验。旧代码在"头已发出后异常"这条路径上
                    // 完全跳过 end()，于是**声明长度与实际不符永远查不出来**，
                    // 连接还会被当成健康的继续复用 —— 下一个请求读到错位的响应。
                    res.end()
                } catch (e: Exception) {
                    // end() 会校验 Content-Length 一致性；不一致说明这轮响应已经坏掉，
                    // 不能再复用连接（否则下一个请求会读到错位的响应）。
                    res.keepAlive = false
                    lastError = e.message
                }
                // 处理器在收尾后仍尝试写入（writeChunk/header）：这次写入**没有**生效，
                // 但它说明调用方的响应状态机和实际线路已经不同步，这条连接不能再复用。
                // 旧实现把这类写入静默吞掉，症状是"偶发乱码/多出几个字节"，
                // 而根因（调用方时序错误）永远不会被发现。
                if (res.failedWriteAfterEnd) {
                    res.keepAlive = false
                    lastError = "响应收尾后仍尝试写入：调用方状态机与线路不同步"
                }
                if (handlerFailed) res.keepAlive = false
                if (!res.keepAlive) stop = true
            }
        } catch (e: Exception) {
            lastError = e.message
        } finally {
            try { socket.close() } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun escape(s: String?): String = (s ?: "").replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * 等待一个新请求的第一个字节，然后**解除空闲超时**。
     *
     * 用 `PushbackInputStream` 把探测用的那个字节还回流里，后续解析逻辑不必知道
     * 这里做过探测。解除超时是必须的：大文件上传/SSE 期间的自然停顿
     * 不该被当成空闲连接掐掉。
     */
    private fun awaitRequestStart(socket: Socket, input: java.io.InputStream) {
        socket.soTimeout = idleTimeoutMs
        val first = input.read()
        if (first < 0) throw java.io.EOFException("connection closed by peer")
        socket.soTimeout = 0 // 请求已开始，读写不限时
        (input as? java.io.PushbackInputStream)?.unread(first)
    }

    /** 直接写一个最小的 JSON 错误响应（用于请求解析失败/处理器异常这类兜底路径） */
    private fun sendSimpleError(res: HttpResponse, code: Int, message: String) {
        try {
            val body = "{\"error\":{\"message\":\"${escape(message)}\"}}".toByteArray(Charsets.UTF_8)
            res.header("Content-Type", "application/json; charset=utf-8")
            res.header("Content-Length", body.size.toString())
            res.writeHead(code)
            res.end(body)
        } catch (e: Exception) { /* 连接已断，忽略 */ }
    }

    /**
     * 读取一个完整请求；返回 null 表示连接正常结束（对端关闭）。
     *
     * ## 错误分类
     * - 请求格式错误 -> 抛 [MalformedRequest]（调用方回 4xx 后关连接）。
     *   旧实现统一抛 `IllegalStateException` 且外层一律回 400：连"请求体超限"
     *   也报 400，调用方无法据此调整（正确语义是 413），也分不清
     *   "我发错了"与"服务端限制太小"。
     * - socket 异常 -> 原样上抛，由调用方记录并收尾。
     *
     * ## path 不解码
     * 移植自 Node 的 `new URL(req.url, base).pathname`，它**保持百分号编码**。
     * 解码推迟到 [HttpRequest.pathParam]，且只对单个路径段做：
     * 对整条路径解码会把段内的 `%2F` 还原成 `/`，
     * 于是"id 里带斜杠"与"多一级路径"不可区分。
     */
    private fun readRequest(input: java.io.InputStream): HttpRequest? {
        val headerBytes = ArrayList<Byte>()
        var last4 = IntArray(4) { -1 }
        while (true) {
            val b = input.read()
            if (b < 0) return null
            headerBytes.add(b.toByte())
            last4[0] = last4[1]; last4[1] = last4[2]; last4[2] = last4[3]; last4[3] = b
            if (last4[0] == 13 && last4[1] == 10 && last4[2] == 13 && last4[3] == 10) break
            if (headerBytes.size > MAX_HEADER_BYTES) {
                // 旧实现这里 return null，而 null 表示"连接结束"：
                // 服务端既不回 4xx 也不回 5xx，直接关连接，客户端只看到空响应，
                // 无从判断"我请求头太大了"还是"网关崩了"。
                throw MalformedRequest(
                    HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE,
                    "request header too large (> $MAX_HEADER_BYTES bytes)",
                )
            }
        }
        val headerText = String(headerBytes.toByteArray(), Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) throw MalformedRequest(400, "empty request")
        val parts = lines[0].split(" ")
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw MalformedRequest(400, "malformed request line: ${lines[0].take(64)}")
        }
        val method = parts[0].uppercase()
        val target = parts[1]
        val httpVersion = parts.getOrNull(2) ?: "HTTP/1.1"

        val headers = LinkedHashMap<String, String>()
        val counts = HashMap<String, Int>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx <= 0) continue
            val name = lines[i].substring(0, idx).trim().lowercase()
            val value = lines[i].substring(idx + 1).trim()
            counts[name] = (counts[name] ?: 0) + 1
            headers[name] = value
        }

        // ---- 请求体分帧：先判非法组合，再定长度 ----
        // `Content-Length` 与 `Transfer-Encoding` 并存是典型的请求走私构造：
        // 不同中间件对"以哪个为准"的选择不同，于是同一个请求在网关这边读完 5 字节、
        // 在反代那边按 chunked 读完整个体，两者对"下一请求从哪开始"判断不一致，
        // 同连接后续流量即被错位解释。旧实现优先取 CL 并**完全忽略 TE**。
        val hasCl = headers.containsKey("content-length")
        val hasTe = headers.containsKey("transfer-encoding")
        if (hasCl && hasTe) {
            throw MalformedRequest(400, "Content-Length 与 Transfer-Encoding 不得并存（请求走私构造）")
        }
        if ((counts["content-length"] ?: 0) > 1) {
            // 重复 CL 同理：头部用 map 存时旧值被静默覆盖，取到哪个取决于实现细节
            throw MalformedRequest(400, "重复的 Content-Length（请求走私构造）")
        }
        if ((counts["transfer-encoding"] ?: 0) > 1) {
            throw MalformedRequest(400, "重复的 Transfer-Encoding")
        }
        val te = headers["transfer-encoding"]
        if (hasTe && te?.contains("chunked", true) != true) {
            throw MalformedRequest(400, "不支持的 Transfer-Encoding: ${te?.take(32)}")
        }

        var body = ByteArray(0)
        if (hasCl) {
            val raw = headers["content-length"]!!
            val cl = raw.toLongOrNull()
                ?: throw MalformedRequest(400, "非法的 Content-Length: ${raw.take(24)}")
            if (cl < 0) throw MalformedRequest(400, "非法的 Content-Length: $cl")
            // 先看声明值再决定是否读取：声明超限时**一个字节都不读**，
            // 否则得读满 120MB 才发现超限，等于把上限白送出去。
            if (cl > maxBodyBytes) {
                throw MalformedRequest(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "request body too large: declared $cl > limit $maxBodyBytes",
                )
            }
            if (cl > 0) body = readFully(input, cl.toInt(), "request body")
        } else if (hasTe) {
            // chunked 分支此前**完全没有上限**：客户端可以一直灌数据把进程撑爆。
            // 对应用进程而言 OOM 的现场症状是"网关无故掉线"，与真实的崩溃长得一样，
            // 极难定位。因此这里把同一条上限也应用到 chunked。
            body = readChunked(input)
        }

        val qIdx = target.indexOf('?')
        val rawPath = if (qIdx >= 0) target.substring(0, qIdx) else target
        val queryStr = if (qIdx >= 0) target.substring(qIdx + 1) else ""

        // 查询参数是**表单编码**语义：`+` 与 `%20` 都表示空格。
        // 这与 path 的规则不同（path 里 `+` 就是加号），两条规则必须分开。
        val query = LinkedHashMap<String, String>()
        if (queryStr.isNotEmpty()) {
            for (pair in queryStr.split("&")) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                if (eq < 0) query[decodeForm(pair)] = ""
                else query[decodeForm(pair.substring(0, eq))] = decodeForm(pair.substring(eq + 1))
            }
        }
        return HttpRequest(method, target, rawPath, query, headers, body, httpVersion)
    }

    /** 表单编码解码（query 段）：`+` 表示空格；非法百分号序列原样保留 */
    private fun decodeForm(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }

    /**
     * 读满 [len] 字节。
     *
     * 对端在读体途中关闭时**必须报错**，不能返回截断数据：
     * 旧实现 `break` 后 `copyOf(off)` 把残缺 body 当正常值返回，
     * 调用方完全无从判断。对 JSON 请求症状是"偶发解析失败"，
     * 对上传请求症状是"文件偶尔损坏" —— 都是最难查的那类。
     */
    private fun readFully(input: java.io.InputStream, len: Int, what: String): ByteArray {
        val out = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(out, off, len - off)
            if (n < 0) {
                throw MalformedRequest(400, "$what 读取中断: 期望 $len 字节，实际 $off 字节")
            }
            off += n
        }
        return out
    }

    /**
     * 读 chunked 请求体，并**边读边判**上限。
     *
     * 判断点放在块边界上：块尺寸本身已确定下一次读的量，
     * 因此块边界是能提前拒绝的最早位置。超限请求不会进入业务处理器，
     * 调用方据此回 413。
     */
    private fun readChunked(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val sizeLine = readLine(input) ?: break
            // 非法尺寸此前是 break（被当成"空正文"继续处理），畸形请求因此变成
            // 一个看起来正常的业务请求（401/400），排障时完全看不出是分帧错误。
            val size = sizeLine.trim().substringBefore(';').toIntOrNull(16)
                ?: throw MalformedRequest(400, "invalid chunk size: ${sizeLine.take(32)}")
            if (size < 0) throw MalformedRequest(400, "negative chunk size: $sizeLine")
            if (size == 0) {
                readLine(input) // 尾部 CRLF
                break
            }
            total += size
            if (total > maxBodyBytes) {
                throw MalformedRequest(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "request body too large: chunked 累计 $total 超过上限 $maxBodyBytes",
                )
            }
            out.write(readFully(input, size, "chunk"))
            readLine(input) // chunk 后的 CRLF
        }
        return out.toByteArray()
    }

    /**
     * 读一行（以 LF 结束，CR 丢弃，顺带兼容只发 LF 的简易客户端）。
     *
     * 超长行必须报错而不是静默截断：旧实现在 8192 字符处 `return sb.toString()`
     * 把半行当成完整行交给上层 —— chunk 尺寸行被截断会解析出**错误的块长度**，
     * 后续所有分帧全部错位，而同连接的下一个请求就会读到错位的数据。
     * 由于 chunk 尺寸行合法长度不超过几十字节，8KB 已远超合理上限。
     */
    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == 13) continue
            if (b == 10) return sb.toString()
            sb.append(b.toChar())
            if (sb.length > MAX_LINE_CHARS) {
                throw MalformedRequest(400, "line too long (> $MAX_LINE_CHARS chars)")
            }
        }
    }

    companion object {
        private const val MAX_WORKERS = 12
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_BODY_BYTES = 120L * 1024 * 1024

        /**
         * 空闲连接超时（"连上但不发请求"最多占多久）。
         * 见 [MiniHttpServer] 构造参数注释：worker 只有 [MAX_WORKERS] 个，
         * 无止境的空闲等待等于最廉价的一类拒绝服务。
         */
        private const val IDLE_TIMEOUT_MS = 30_000

        private const val MAX_LINE_CHARS = 8192
    }
}
