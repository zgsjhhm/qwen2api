package com.qwen2api.tx

import com.qwen2api.tx.core.ConfigStore
import com.qwen2api.tx.core.FileRecord
import com.qwen2api.tx.core.GatewayConfig
import com.qwen2api.tx.core.MemoryConfigRepository
import com.qwen2api.tx.server.GatewayRouter
import com.qwen2api.tx.server.MemoryFileStore
import com.qwen2api.tx.server.MiniHttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 端到端集成测试：真实启动 MiniHttpServer + GatewayRouter，走真实 TCP 连接。
 *
 * 纯 JVM 运行（依赖通过 MemoryConfigRepository / MemoryFileStore 注入），
 * 无需设备、无需外网、无需 Robolectric。
 *
 * 覆盖：
 *  - 服务器绑定与基础路由
 *  - /v1 下所有端点的 Bearer 鉴权
 *  - 请求体校验与 OpenAI 规范错误格式
 *  - /admin 管理接口与 Token 清洗
 *  - 配置持久化、文件注册表
 *  - 并发与 CORS
 */
class GatewayE2ETest {

    private lateinit var configRepo: MemoryConfigRepository
    private lateinit var fileStore: com.qwen2api.tx.core.FileStore
    private lateinit var server: MiniHttpServer
    private var port: Int = 0

    @Before
    fun setUp() {
        configRepo = MemoryConfigRepository()
        fileStore = MemoryFileStore()
        val router = GatewayRouter(configRepo, fileStore)
        // 端口 0 -> 由系统分配空闲端口，避免冲突
        server = MiniHttpServer(0, "127.0.0.1") { req, res -> router.handle(req, res) }
        server.start()
        port = server.boundPort
        assertTrue("服务器应成功绑定端口", port > 0)
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private data class Resp(val code: Int, val body: String)

    private fun request(
        path: String,
        method: String = "GET",
        bearer: String? = null,
        body: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Resp {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5000
        conn.readTimeout = 15000
        bearer?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        conn.disconnect()
        return Resp(code, text)
    }

    private fun key(): String = configRepo.load().apiKey

    // ---------------- 基础可用性 ----------------

    @Test
    fun `server binds and healthz responds`() {
        val r = request("/healthz")
        assertEquals(200, r.code)
        val j = JSONObject(r.body)
        assertEquals(true, j.optBoolean("ok"))
        assertEquals("1.2.1", j.optString("version"))
    }

    @Test
    fun `unknown route returns 404 json`() {
        val r = request("/nope")
        assertEquals(404, r.code)
        assertTrue(r.body.contains("not found"))
    }

    @Test
    fun `api key is auto generated on first load`() {
        val cfg = configRepo.load()
        assertTrue("首次加载应自动生成 API 密钥", cfg.apiKey.startsWith("sk-qpp-"))
        assertTrue("密钥长度应足够", cfg.apiKey.length > 20)
        assertEquals("二次加载应保持一致", cfg.apiKey, configRepo.load().apiKey)
    }

    // ---------------- 鉴权 ----------------

    @Test
    fun `models without key returns 401`() {
        val r = request("/v1/models")
        assertEquals(401, r.code)
        val err = JSONObject(r.body).optJSONObject("error")
        assertNotNull("应返回 OpenAI 风格 error 对象", err)
        assertEquals("invalid_api_key", err!!.optString("code"))
        assertEquals("authentication_error", err.optString("type"))
    }

    @Test
    fun `models with wrong key returns 401`() {
        val r = request("/v1/models", bearer = "sk-qpp-wrong")
        assertEquals(401, r.code)
        assertEquals("invalid_api_key", JSONObject(r.body).optJSONObject("error")!!.optString("code"))
    }

    @Test
    fun `chat completions without key returns 401`() {
        val r = request(
            "/v1/chat/completions", "POST",
            body = """{"model":"qwen3.8-max","messages":[{"role":"user","content":"hi"}]}""",
        )
        assertEquals(401, r.code)
    }

    @Test
    fun `files list without key returns 401`() {
        assertEquals(401, request("/v1/files").code)
    }

    @Test
    fun `file detail without key returns 401`() {
        assertEquals(401, request("/v1/files/file-abc").code)
    }

    @Test
    fun `bearer scheme is case insensitive`() {
        val conn = URL("http://127.0.0.1:$port/v1/models").openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "bearer ${key()}")
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        // 鉴权应通过（返回 200 或上游失败后的静态回退 200），不会是 401
        assertTrue("小写 bearer 也应被接受", conn.responseCode != 401)
        conn.disconnect()
    }

    // ---------------- 请求体校验 ----------------

    @Test
    fun `chat with empty messages returns 400 invalid_request_error`() {
        val r = request(
            "/v1/chat/completions", "POST", bearer = key(),
            body = """{"model":"qwen3.8-max","messages":[]}""",
        )
        assertEquals(400, r.code)
        val err = JSONObject(r.body).optJSONObject("error")
        assertEquals("invalid_request_error", err!!.optString("type"))
        assertTrue(err.optString("message").contains("messages"))
    }

    @Test
    fun `chat with malformed json returns 400`() {
        val r = request("/v1/chat/completions", "POST", bearer = key(), body = "{not json")
        assertEquals(400, r.code)
        assertTrue(r.body.contains("invalid_request_error"))
    }

    @Test
    fun `chat without messages field returns 400`() {
        val r = request("/v1/chat/completions", "POST", bearer = key(), body = """{"model":"x"}""")
        assertEquals(400, r.code)
    }

    /**
     * 未配置 Qwen token 时请求对话：必须返回可读错误而非崩溃/挂起。
     * 这条链路走到了 QwenClient 的 NO_TOKEN 分支，证明「路由 -> 客户端」已打通。
     */
    @Test
    fun `chat without qwen token reports actionable error`() {
        val r = request(
            "/v1/chat/completions", "POST", bearer = key(),
            body = """{"model":"qwen3.8-max","messages":[{"role":"user","content":"hi"}],"stream":false}""",
        )
        assertEquals(401, r.code)
        val err = JSONObject(r.body).optJSONObject("error")
        assertNotNull(err)
        assertTrue("错误信息应可读", err!!.optString("message").isNotEmpty())
        assertEquals("NO_TOKEN", err.optString("code"))
    }

    @Test
    fun `files upload without token reports no_token`() {
        val boundary = "----testboundary"
        val body = buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n")
            append("Content-Type: text/plain\r\n\r\n")
            append("hello\r\n")
            append("--$boundary--\r\n")
        }
        val r = request(
            "/v1/files", "POST", bearer = key(), body = body,
            extraHeaders = mapOf("Content-Type" to "multipart/form-data; boundary=$boundary"),
        )
        assertEquals(400, r.code)
        assertEquals("no_token", JSONObject(r.body).optJSONObject("error")!!.optString("code"))
    }

    @Test
    fun `files upload with non multipart body returns 400`() {
        configRepo.update { it.copyWith(qwenToken = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.sig") }
        val r = request(
            "/v1/files", "POST", bearer = key(), body = "not-multipart",
            extraHeaders = mapOf("Content-Type" to "application/json"),
        )
        assertEquals(400, r.code)
        assertEquals("bad_request", JSONObject(r.body).optJSONObject("error")!!.optString("code"))
    }

    @Test
    fun `file detail for unknown id returns 404 file_not_found`() {
        val r = request("/v1/files/file-doesnotexist", bearer = key())
        assertEquals(404, r.code)
        assertEquals("file_not_found", JSONObject(r.body).optJSONObject("error")!!.optString("code"))
    }

    @Test
    fun `files list returns empty list initially`() {
        val r = request("/v1/files", bearer = key())
        assertEquals(200, r.code)
        val j = JSONObject(r.body)
        assertEquals("list", j.optString("object"))
        assertEquals(0, j.optJSONArray("data")!!.length())
    }

    // ---------------- 管理接口 ----------------

    @Test
    fun `admin status returns full snapshot`() {
        val r = request("/admin/api/status")
        assertEquals(200, r.code)
        val j = JSONObject(r.body)
        assertEquals("1.2.1", j.optString("version"))
        assertTrue(j.optString("apiKey").startsWith("sk-qpp-"))
        assertNotNull(j.opt("models"))
        assertTrue("应带端口字段", j.has("port"))
        assertTrue("应带 defaultModel", j.has("defaultModel"))
        assertTrue("应带 throttleMs", j.has("throttleMs"))
    }

    @Test
    fun `admin status exposes storage encryption state`() {
        // 回归点：SecretVault.lastDegraded 原先是个只写不读的标志，
        // "用户凭证明文落盘"这件事对用户完全不可见（静默失效）。
        // 现在必须能从管理接口读到，且降级时带可读解释。
        val j = JSONObject(request("/admin/api/status").body)
        assertTrue("必须暴露加密是否降级", j.has("vaultDegraded"))
        assertTrue("必须带降级说明字段", j.has("vaultDegradedHint"))
        if (j.optBoolean("vaultDegraded")) {
            assertTrue(
                "降级时必须给出可读解释: ${j.optString("vaultDegradedHint")}",
                j.optString("vaultDegradedHint").contains("明文"),
            )
        } else {
            assertEquals("未降级时说明应为空", "", j.optString("vaultDegradedHint"))
        }
    }

    @Test
    fun `admin token rejects chinese characters with precise position`() {
        val r = request(
            "/admin/api/token", "POST",
            body = JSONObject().put("token", "abc中文def").toString(),
        )
        assertEquals(400, r.code)
        val j = JSONObject(r.body)
        assertEquals(false, j.optBoolean("ok"))
        assertTrue(
            "应指出具体第几个字符: ${j.optString("message")}",
            j.optString("message").contains("第 4 个字符"),
        )
    }

    @Test
    fun `admin token rejects non credential strings`() {
        val r = request("/admin/api/token", "POST", body = """{"token":"hello world"}""")
        assertEquals(400, r.code)
        assertTrue(JSONObject(r.body).optString("message").isNotEmpty())
    }

    @Test
    fun `admin settings update persists`() {
        val r = request(
            "/admin/api/settings", "POST",
            body = """{"defaultModel":"qwen3.7-plus","thinking":false,"throttleMs":1500}""",
        )
        assertEquals(200, r.code)
        assertEquals(true, JSONObject(r.body).optBoolean("ok"))
        val cfg = configRepo.load()
        assertEquals("qwen3.7-plus", cfg.defaultModel)
        assertEquals(false, cfg.thinking)
        assertEquals(1500, cfg.throttleMs)
    }

    @Test
    fun `admin settings clamps throttle to valid range`() {
        request("/admin/api/settings", "POST", body = """{"throttleMs":999999}""")
        assertEquals(30000, configRepo.load().throttleMs)
    }

    @Test
    fun `admin settings updates image retry policy`() {
        val r = request(
            "/admin/api/settings", "POST",
            body = """{"imageRetryCount":4,"imageRetryBackoffMs":2500}""",
        )
        assertEquals(200, r.code)
        val cfg = configRepo.load()
        assertEquals(4, cfg.imageRetryCount)
        assertEquals(2500, cfg.imageRetryBackoffMs)
        // 回读必须一致：这两个值决定上游出图请求量（风控），
        // 「写进去了但读出来还是旧值」比不写更危险。
        val j = JSONObject(request("/admin/api/status").body)
        assertEquals(4, j.optInt("imageRetryCount"))
        assertEquals(2500, j.optInt("imageRetryBackoffMs"))
    }

    @Test
    fun `admin settings clamps image retry policy`() {
        // 上限 5 次不是随手取的：再多就只是把风控触发得更快
        request("/admin/api/settings", "POST", body = """{"imageRetryCount":99}""")
        assertEquals(GatewayConfig.MAX_IMAGE_RETRY, configRepo.load().imageRetryCount)
        // 退避上限与客户端常量对齐，避免出现「配置允许 30s 但客户端封顶 8s」
        // 这种"配了没用"的静默失效。
        request("/admin/api/settings", "POST", body = """{"imageRetryBackoffMs":999999}""")
        assertEquals(
            com.qwen2api.tx.core.QwenImageClient.MAX_RETRY_BACKOFF_MS.toInt(),
            configRepo.load().imageRetryBackoffMs,
        )
    }

    @Test
    fun `admin settings accepts zero to disable image retry`() {
        // 0 是**合法且有意义**的取值（账号被风控时用户要关掉重试），
        // 因此不能被"缺省/非法"分支吞掉。
        request(
            "/admin/api/settings", "POST",
            body = """{"imageRetryCount":0,"imageRetryBackoffMs":0}""",
        )
        val cfg = configRepo.load()
        assertEquals(0, cfg.imageRetryCount)
        assertEquals(0, cfg.imageRetryBackoffMs)
        // 负数是非法值 -> 收敛回 0 而不是负数（负重试次数会让循环直接不执行，
        // 看起来像"重试无效"，实际是配置写坏了）。
        request("/admin/api/settings", "POST", body = """{"imageRetryCount":-3}""")
        assertEquals(0, configRepo.load().imageRetryCount)
    }

    @Test
    fun `config json export carries image retry policy`() {
        configRepo.update { it.copyWith(imageRetryCount = 3, imageRetryBackoffMs = 800) }
        val j = ConfigStore.toJson(configRepo.load())
        assertEquals(3, j.optInt("imageRetryCount"))
        assertEquals(800, j.optInt("imageRetryBackoffMs"))
    }

    @Test
    fun `admin key regenerate changes and persists key`() {
        val before = key()
        val r = request("/admin/api/key/regenerate", "POST", body = "{}")
        assertEquals(200, r.code)
        val after = JSONObject(r.body).optString("apiKey")
        assertTrue(after.startsWith("sk-qpp-"))
        assertTrue("新密钥应与旧的不同", after != before)
        assertEquals("应已持久化", after, configRepo.load().apiKey)
    }

    @Test
    fun `admin unknown route returns 404`() {
        assertEquals(404, request("/admin/api/nope").code)
    }

    // ---------------- 并发与 CORS ----------------

    @Test
    fun `server handles concurrent requests`() {
        val threads = (1..8).map {
            Thread { request("/healthz") }.also { t -> t.start() }
        }
        threads.forEach { it.join(15000) }
        assertEquals("并发后服务器仍应可用", 200, request("/healthz").code)
        assertEquals("并发后鉴权仍应生效", 401, request("/v1/models").code)
    }

    @Test
    fun `cors preflight returns 204 with allow origin`() {
        val conn = URL("http://127.0.0.1:$port/v1/models").openConnection() as HttpURLConnection
        conn.requestMethod = "OPTIONS"
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        assertEquals(204, conn.responseCode)
        assertEquals("*", conn.getHeaderField("Access-Control-Allow-Origin"))
        conn.disconnect()
    }

    @Test
    fun `json error content type is application json`() {
        val conn = URL("http://127.0.0.1:$port/nope").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        assertEquals(404, conn.responseCode)
        assertTrue(
            "Content-Type 应为 application/json",
            conn.getHeaderField("Content-Type").orEmpty().contains("application/json"),
        )
        conn.disconnect()
    }

    // ---------------- 配置与工具 ----------------

    @Test
    fun `config round trip via json`() {
        val cfg = configRepo.load()
        val json = ConfigStore.toJson(cfg)
        assertEquals(cfg.apiKey, json.optString("apiKey"))
        assertEquals(cfg.port, json.optInt("port"))
        assertEquals(cfg.defaultModel, json.optString("defaultModel"))
        assertEquals(cfg.throttleMs, json.optInt("throttleMs"))
    }

    @Test
    fun `token type detection covers all cases`() {
        assertEquals("jwt", ConfigStore.detectTokenType("eyJhbGciOi.eyJzdWIi.sig"))
        assertEquals("cookie", ConfigStore.detectTokenType("cna=abc; token=xyz"))
        assertEquals("unknown", ConfigStore.detectTokenType("random"))
        assertEquals("empty", ConfigStore.detectTokenType(""))
    }

    @Test
    fun `sanitize extracts jwt from messy paste`() {
        val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVP"
        val san = ConfigStore.sanitizeQwenToken("这是我的token： $jwt  （勿外传）")
        assertTrue("应能从混杂文本中提取: ${san.message}", san.ok)
        assertEquals(jwt, san.token)
        assertEquals("jwt", san.type)
        assertTrue(san.note.contains("提取"))
    }

    @Test
    fun `sanitize strips surrounding quotes`() {
        val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVP"
        val san = ConfigStore.sanitizeQwenToken("\"$jwt\"")
        assertTrue(san.ok)
        assertEquals(jwt, san.token)
    }

    @Test
    fun `sanitize extracts token value from cookie string`() {
        val san = ConfigStore.sanitizeQwenToken("cna=abc123; token=plain-cookie-value; tfstk=zzz")
        assertTrue(san.ok)
        assertEquals("plain-cookie-value", san.token)
        assertTrue(san.note.contains("Cookie"))
    }

    @Test
    fun `maskToken hides middle of long token`() {
        val masked = ConfigStore.maskToken("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9abcdefghij")
        assertTrue(masked.contains("****"))
        assertTrue(masked.length < 30)
    }

    @Test
    fun `file store add find and remove`() {
        val entry = JSONObject().put("type", "image").put("id", "qwen-file-1")
        val rec = FileRecord(
            id = "file-test123",
            qwenId = "qwen-file-1",
            url = "https://example.com/a.png",
            filename = "a.png",
            bytes = 100,
            mime = "image/png",
            kind = "image",
            createdAt = 1700000000,
            purpose = "assistants",
            entry = entry,
        )
        fileStore.add(rec)
        assertNotNull("应能按完整 id 查到", fileStore.find("file-test123"))
        assertNotNull("应能按去掉前缀的 id 查到", fileStore.find("test123"))
        val openAi = rec.toOpenAi()
        assertEquals("file", openAi.optString("object"))
        assertEquals(100L, openAi.optLong("bytes"))
        fileStore.remove(rec.id)
        assertEquals(null, fileStore.find("file-test123"))
    }

    @Test
    fun `file detail endpoint returns registered file`() {
        val rec = FileRecord(
            id = "file-abc123",
            qwenId = "q1",
            url = "https://example.com/x.pdf",
            filename = "x.pdf",
            bytes = 2048,
            mime = "application/pdf",
            kind = "document",
            createdAt = 1700000000,
            purpose = "assistants",
            entry = JSONObject().put("type", "file"),
        )
        fileStore.add(rec)
        val r = request("/v1/files/file-abc123", bearer = key())
        assertEquals(200, r.code)
        val j = JSONObject(r.body)
        assertEquals("file-abc123", j.optString("id"))
        assertEquals("x.pdf", j.optString("filename"))
        assertEquals("assistants", j.optString("purpose"))
    }

    @Test
    fun `file delete removes record and reports deleted true`() {
        val rec = FileRecord(
            id = "file-delme", qwenId = "q2", url = "u", filename = "d.txt",
            bytes = 1, mime = "text/plain", kind = "document",
            createdAt = 1, purpose = "assistants", entry = JSONObject(),
        )
        fileStore.add(rec)
        val r = request("/v1/files/file-delme", "DELETE", bearer = key())
        assertEquals(200, r.code)
        assertEquals(true, JSONObject(r.body).optBoolean("deleted"))
        assertEquals(null, fileStore.find("file-delme"))
    }

    @Test
    fun `file content endpoint redirects to upstream url`() {
        val rec = FileRecord(
            id = "file-redir", qwenId = "q3", url = "https://example.com/content.png",
            filename = "c.png", bytes = 10, mime = "image/png", kind = "image",
            createdAt = 1, purpose = "assistants", entry = JSONObject(),
        )
        fileStore.add(rec)
        val conn = URL("http://127.0.0.1:$port/v1/files/file-redir/content").openConnection()
            as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer ${key()}")
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        assertEquals(302, conn.responseCode)
        assertEquals("https://example.com/content.png", conn.getHeaderField("Location"))
        conn.disconnect()
    }


    // ---------------- B 轮加固回归（admin host / SSRF / 响应 framing） ----------------

    /** 用裸 socket 发请求，才能使用 HttpURLConnection 不允许伪造的 Host 头 */
    private fun rawGet(path: String, hostHeader: String?): String {
        val sock = java.net.Socket("127.0.0.1", port)
        sock.soTimeout = 8000
        val head = StringBuilder("GET $path HTTP/1.1\r\n").append("Connection: close\r\n")
        if (hostHeader != null) head.append("Host: $hostHeader\r\n")
        head.append("\r\n")
        sock.getOutputStream().apply { write(head.toString().toByteArray()); flush() }
        val line = sock.getInputStream().bufferedReader().readLine() ?: ""
        sock.close()
        return line
    }

    @Test
    fun `admin rejects non loopback host header`() {
        // 0.0.0.0 与空串曾经被当作「本机」放进白名单，等于 DNS rebinding 防护失效
        assertTrue("0.0.0.0: ${rawGet("/admin/api/status", "0.0.0.0")}",
            rawGet("/admin/api/status", "0.0.0.0").contains("403"))
        assertTrue("evil.example.com",
            rawGet("/admin/api/status", "evil.example.com").contains("403"))
        assertTrue("127.0.0.1.evil.com（前缀伪装）",
            rawGet("/admin/api/status", "127.0.0.1.evil.com").contains("403"))
        assertTrue("Host 缺失",
            rawGet("/admin/api/status", null).contains("403"))
    }

    @Test
    fun `admin accepts loopback host header with or without port`() {
        assertTrue("127.0.0.1", rawGet("/admin/api/status", "127.0.0.1").contains("200"))
        assertTrue("localhost:8818", rawGet("/admin/api/status", "localhost:8818").contains("200"))
        assertTrue("[::1]:8818", rawGet("/admin/api/status", "[::1]:8818").contains("200"))
    }

    @Test
    fun `admin host check keeps non admin routes unaffected`() {
        // 加固只针对 /admin/*，/v1 与 /healthz 正常（LAN 模式下客户端本来就不发本机 Host）
        assertTrue("healthz", rawGet("/healthz", "192.168.1.9:8818").contains("200"))
        assertTrue("/v1/models 应走到鉴权而非 403",
            rawGet("/v1/models", "192.168.1.9:8818").contains("401"))
    }

    @Test
    fun `remote attachment url rejects internal targets`() {
        // SSRF：此前这些 URL 会被网关无条件代拉
        val blocked = listOf(
            "http://127.0.0.1:7980/admin/api/status",
            "https://127.0.0.1/secret",
            "https://localhost/secret",
            "https://169.254.169.254/latest/meta-data/",
            "https://192.168.1.1/",
            "https://10.0.0.5/",
            "https://evil.example.com/x.png",
        )
        for (url in blocked) {
            try {
                com.qwen2api.tx.server.AttachmentResolver.assertRemoteUrlAllowed(url, "image_url")
                throw AssertionError("应拒绝的 URL 被放行: $url")
            } catch (e: com.qwen2api.tx.core.QwenException) {
                assertEquals("应拒绝: $url", "BAD_REQUEST", e.code)
            }
        }
    }

    @Test
    fun `remote attachment url rejects plaintext http even on allowed domain`() {
        // 白名单域名也必须走 https —— 这一步在 DNS 之前就拦下，所以单测无需联网
        try {
            com.qwen2api.tx.server.AttachmentResolver.assertRemoteUrlAllowed(
                "http://cdn.qwen.ai/a.png", "image_url",
            )
            throw AssertionError("http 明文附件 URL 应被拒绝")
        } catch (e: com.qwen2api.tx.core.QwenException) {
            assertEquals("BAD_REQUEST", e.code)
            assertTrue("应提示仅支持 https: ${e.message}", e.message.contains("https"))
        }
    }

    @Test
    fun `remote attachment url rejects non qwen domains`() {
        try {
            com.qwen2api.tx.server.AttachmentResolver.assertRemoteUrlAllowed(
                "https://example.com/x.png", "image_url",
            )
            throw AssertionError("非白名单域名应被拒绝")
        } catch (e: com.qwen2api.tx.core.QwenException) {
            assertEquals("BAD_REQUEST", e.code)
        }
    }

    @Test
    fun `remote attachment url accepts allowed domain shape`() {
        // 只验白名单匹配逻辑：用一个解析得到的公网 IP 直连字面量，绕开 DNS 依赖
        val ok = com.qwen2api.tx.server.AttachmentResolver.isHostAllowed("cdn.qwen.ai") &&
            com.qwen2api.tx.server.AttachmentResolver.isHostAllowed("oss-cn-hangzhou.aliyuncs.com") &&
            com.qwen2api.tx.server.AttachmentResolver.isHostAllowed("qwen.ai")
        assertTrue("Qwen/阿里云域名应通过白名单", ok)
        val bad = com.qwen2api.tx.server.AttachmentResolver.isHostAllowed("qwen.ai.evil.com") ||
            com.qwen2api.tx.server.AttachmentResolver.isHostAllowed("notqwen.ai") ||
            com.qwen2api.tx.server.AttachmentResolver.isHostAllowed("example.com")
        assertTrue("伪装域名不应通过白名单", !bad)
    }

    @Test
    fun `private address classifier blocks metadata and cgnat ranges`() {
        val mustBlock = listOf(
            "127.0.0.1", "0.0.0.0", "169.254.169.254", "10.1.2.3",
            "172.16.0.1", "192.168.0.1", "100.64.0.1", "::1", "fd00::1",
        )
        for (ip in mustBlock) {
            val a = java.net.InetAddress.getByName(ip)
            assertTrue("应判定为受限地址: $ip", com.qwen2api.tx.server.AttachmentResolver.isBlockedAddress(a))
        }
        for (ip in listOf("8.8.8.8", "1.1.1.1")) {
            val a = java.net.InetAddress.getByName(ip)
            assertTrue("公网地址不应被拦: $ip", !com.qwen2api.tx.server.AttachmentResolver.isBlockedAddress(a))
        }
    }

    @Test
    fun `sse stream declares chunked framing`() {
        // 流式响应此前既无 Content-Length 也无 Transfer-Encoding，
        // 靠 close 表示结束 —— 对反代/同连接下一个请求是响应走私隐患。
        val conn = URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000
        conn.readTimeout = 15000
        conn.setRequestProperty("Authorization", "Bearer " + key())
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.doOutput = true
        conn.outputStream.use {
            it.write("""{"messages":[{"role":"user","content":"hi"}],"stream":true}""".toByteArray())
        }
        // 未配置 token 会以错误帧收尾，但响应头已经发出，足以断言 framing
        val code = conn.responseCode
        conn.errorStream?.close()
        conn.inputStream?.close()
        assertEquals("text/event-stream; charset=utf-8", conn.getHeaderField("Content-Type"))
        assertEquals("chunked", conn.getHeaderField("Transfer-Encoding"))
        assertTrue("chunked 响应不应出现 Content-Length", conn.getHeaderField("Content-Length") == null)
        assertTrue(code in 200..500)
        conn.disconnect()
    }

    @Test
    fun `json response keeps content length and is not chunked`() {
        val conn = URL("http://127.0.0.1:$port/healthz").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        assertEquals(200, conn.responseCode)
        assertTrue("JSON 响应应保留 Content-Length", (conn.getHeaderField("Content-Length")?.toInt() ?: 0) > 0)
        assertTrue("JSON 响应不应被改成 chunked", conn.getHeaderField("Transfer-Encoding") == null)
        conn.inputStream.use { it.readBytes() }
        conn.disconnect()
    }

    @Test
    fun `oversized declared body returns 413 instead of silent hangup`() {
        // 超过 MAX_BODY_BYTES 的声明此前会抛异常并被静默吞掉（客户端只看到空回复）。
        // 修好"必须有响应"之后，状态码本身也要对：请求实体过大是 413，
        // 用 400 会让调用方以为是自己格式写错了，不知道该去调小体积。
        val sock = java.net.Socket("127.0.0.1", port)
        sock.soTimeout = 10000
        val out = sock.getOutputStream()
        out.write(
            ("POST /v1/chat/completions HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Content-Length: 999999999\r\n" +
                "Authorization: Bearer " + key() + "\r\n\r\n").toByteArray(),
        )
        out.flush()
        val resp = sock.getInputStream().bufferedReader().readLine()
        sock.close()
        assertTrue("应返回 413 而不是静默关闭: $resp", resp != null && resp.contains("413"))
    }

    @Test
    fun `malformed chunked body returns 400`() {
        val sock = java.net.Socket("127.0.0.1", port)
        sock.soTimeout = 10000
        val out = sock.getOutputStream()
        out.write(
            ("POST /v1/chat/completions HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Transfer-Encoding: chunked\r\n\r\n" +
                "ZZZZ\r\n").toByteArray(),
        )
        out.flush()
        val resp = sock.getInputStream().bufferedReader().readLine()
        sock.close()
        assertTrue("非法 chunk 尺寸应得到 400 或明确断开: $resp", resp == null || resp.contains("400"))
    }

    @Test
    fun `default model falls back to config when absent in request`() {
        configRepo.update { it.copyWith(defaultModel = "qwen3.6-plus") }
        // messages 为空会先返回 400，不足以证明模型回退；
        // 这里通过未配置 token 的路径确认请求已进入客户端（返回 NO_TOKEN 而非 400）
        val r = request(
            "/v1/chat/completions", "POST", bearer = key(),
            body = """{"messages":[{"role":"user","content":"hi"}]}""",
        )
        assertEquals("应从 config 取默认模型并进入客户端", 401, r.code)
        assertEquals("NO_TOKEN", JSONObject(r.body).optJSONObject("error")!!.optString("code"))
    }
}
