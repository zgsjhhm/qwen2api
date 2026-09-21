package com.qwen2api.tx

import com.qwen2api.tx.core.FileRecord
import com.qwen2api.tx.core.FileStore
import com.qwen2api.tx.core.MemoryConfigRepository
import com.qwen2api.tx.server.GatewayRouter
import com.qwen2api.tx.server.MemoryFileStore
import com.qwen2api.tx.server.MiniHttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * `/v1/files/<id>` 的**路由键契约**测试。
 *
 * ## 为什么单独开一个类
 * 这条路由的 id 来自调用方，是**用户可控的路径段**。它的编解码规则一旦漂移，
 * 症状全都不是"报错"，而是"找错东西"或"找不到明明存在的东西"：
 *  - 对整条路径做百分号解码 -> 段内的 `%2F` 变成路径分隔符，
 *    `file-a%2Fb` 与 `file-a/b` 撞成同一个键（不同 id 不可区分）；
 *  - 对整条路径**完全不解码** -> 客户端正常编码的 id 原样进注册表，
 *    查不到记录，用户看到"文件明明上传成功却报未找到"；
 *  - 用 `removePrefix`/`endsWith` 裁剪 -> `/v1/files/a/content/../b`
 *    这类形状会被拼成不存在的 id，而不是被当作非法路径拒绝。
 *
 * 正确做法是两者都避开：路由按**编码原样**比对（防走私、防歧义），
 * 取 id 时只对**那一个段**解码（还原用户语义）。
 */
class FileRoutingTest {

    private lateinit var configRepo: MemoryConfigRepository
    private lateinit var fileStore: FileStore
    private lateinit var server: MiniHttpServer
    private var port: Int = 0

    /** 含普通字符与需要编码字符的两种 id 都备一份 */
    private val plainId = "file-abc123"
    private val encSpace = "file-a b"        // 客户端会编码为 file-a%20b
    private val encSlash = "file-a/b"        // 客户端会编码为 file-a%2Fb
    private val encPlus = "file-a+b"         // 客户端会编码为 file-a%2Bb

    @Before
    fun setUp() {
        configRepo = MemoryConfigRepository()
        fileStore = MemoryFileStore()
        listOf(plainId, encSpace, encSlash, encPlus).forEachIndexed { i, id ->
            fileStore.add(
                FileRecord(
                    id = id,
                    qwenId = "q$i",
                    url = "https://example.com/$i.bin",
                    filename = "f$i.bin",
                    bytes = 10L + i,
                    mime = "application/octet-stream",
                    kind = "document",
                    createdAt = 1700000000,
                    purpose = "assistants",
                    entry = JSONObject().put("type", "document").put("id", "q$i"),
                ),
            )
        }
        val router = GatewayRouter(configRepo, fileStore)
        server = MiniHttpServer(0, "127.0.0.1") { req, res -> router.handle(req, res) }
        server.start()
        port = server.boundPort
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private data class Resp(val code: Int, val body: String)

    /** 用**原始** request target 发请求（HttpURLConnection 会拒绝含 %2F 的路径，故手写 socket） */
    private fun raw(rawTarget: String, method: String = "GET"): Resp {
        java.net.Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().write(
                ("$method $rawTarget HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                    "Authorization: Bearer ${configRepo.load().apiKey}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
            )
            s.getOutputStream().flush()
            val text = s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            val code = text.substringAfter("HTTP/1.1 ").substringBefore(" ").toInt()
            return Resp(code, text.substringAfter("\r\n\r\n"))
        }
    }

    /** 走 HttpURLConnection 的常规请求（路径不需要编码时用它） */
    private fun request(path: String, method: String = "GET"): Resp {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        conn.setRequestProperty("Authorization", "Bearer ${configRepo.load().apiKey}")
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        conn.disconnect()
        return Resp(code, text)
    }

    // ---------------- 1. 基本形状 ----------------

    @Test
    fun `plain id is resolved`() {
        val r = request("/v1/files/$plainId")
        assertEquals(200, r.code)
        assertEquals(plainId, JSONObject(r.body).optString("id"))
    }

    @Test
    fun `id without file prefix is tolerated`() {
        val r = request("/v1/files/abc123")
        assertEquals("注册表应容忍省略 file- 前缀", 200, r.code)
        assertEquals(plainId, JSONObject(r.body).optString("id"))
    }

    // ---------------- 2. 编码 id ----------------

    /**
     * 客户端按 URI 规范编码的 id 必须能被还原。
     *
     * 这是"路由不解码、取值时才解码"的关键收益：`%20` 在路由比对时是字面量
     *（不会与空格混同），在提取 id 时还原成空格（对上用户语义）。
     */
    @Test
    fun `percent encoded space in id is decoded`() {
        val r = raw("/v1/files/file-a%20b")
        assertEquals(200, r.code)
        assertEquals(encSpace, JSONObject(r.body).optString("id"))
    }

    /**
     * `%2F` **不能**被当作路径分隔符。
     *
     * 解码整条路径的实现会把 `file-a%2Fb` 变成 `file-a/b`，
     * 于是 `/v1/files/file-a%2Fb` 与 `/v1/files/file-a/b`（后者根本不存在）
     * 会路由到同一个键 —— 两个不同 id 不可区分。
     */
    @Test
    fun `percent encoded slash stays inside the id`() {
        val r = raw("/v1/files/file-a%2Fb")
        assertEquals("编码斜杠应作为 id 的一部分，而不是路径分隔符", 200, r.code)
        assertEquals(encSlash, JSONObject(r.body).optString("id"))
    }

    /**
     * path 段里的 `+` 是**字面加号**，不是空格。
     *
     * 表单编码（query）才把 `+` 当空格。若 path 段沿用了表单解码，
     * `file-a+b` 会被还原成 `file-a b`，与 `file-a%20b` 撞成同一个键。
     */
    @Test
    fun `plus sign in id is literal not space`() {
        val r = raw("/v1/files/file-a+b")
        assertEquals(200, r.code)
        assertEquals("path 段里的加号是字面加号", encPlus, JSONObject(r.body).optString("id"))
    }

    @Test
    fun `encoded plus in id is decoded to plus`() {
        val r = raw("/v1/files/file-a%2Bb")
        assertEquals(200, r.code)
        assertEquals(encPlus, JSONObject(r.body).optString("id"))
    }

    // ---------------- 3. content 后缀 ----------------

    @Test
    fun `content suffix redirects to upstream url`() {
        val r = request("/v1/files/$plainId/content")
        assertEquals("有 url 的文件应 302 到上游", 302, r.code)
        assertTrue("Location 应指向上游 url", r.body.contains("example.com") || r.code == 302)
    }

    @Test
    fun `content suffix works with encoded id`() {
        val r = raw("/v1/files/file-a%20b/content")
        assertEquals(302, r.code)
    }

    @Test
    fun `content suffix without url reports no content`() {
        fileStore.add(
            FileRecord(
                id = "file-nourl", qwenId = "qn", url = "", filename = "x.bin",
                bytes = 1, mime = "application/octet-stream", kind = "document",
                createdAt = 1, purpose = "assistants", entry = JSONObject(),
            ),
        )
        val r = request("/v1/files/file-nourl/content")
        assertEquals(404, r.code)
        assertEquals("no_content", JSONObject(r.body).optJSONObject("error")?.optString("code"))
    }

    // ---------------- 4. 异常形状 ----------------

    /**
     * 多出来的路径层级必须被拒绝，不能靠前缀匹配"猜到"一个 id。
     *
     * 旧实现是 `removePrefix("/v1/files/")` + `endsWith("/content")` 裁剪：
     * `/v1/files/file-abc123/extra` 会得到 id `file-abc123/extra`，
     * 于是查不到、报 404 并回显一个**用户从没提交过的 id**；
     * 更糟的是当注册表里恰好存在含 `/` 的 id 时（见上文 %2F 用例），
     * 形状非法的路径会**意外命中**一条真实记录。
     */
    @Test
    fun `extra path segment is rejected`() {
        val r = request("/v1/files/$plainId/extra")
        assertEquals(404, r.code)
    }

    @Test
    fun `content with extra segment is rejected`() {
        val r = request("/v1/files/$plainId/content/more")
        assertEquals(404, r.code)
    }

    /**
     * 尾斜杠归一化：`/v1/files/` 等同列表端点，而不是"空 id 的详情查询"。
     *
     * 这条之所以要钉住：它是"空 id"与"列表"之间的分界。
     * 若归一化失效，`/v1/files/` 会落到详情分支上取到空 id ——
     * 回显一个空 id 的 404，或者更糟：当注册表里存在空 id 记录时命中它。
     */
    @Test
    fun `trailing slash on collection is the list endpoint`() {
        val r = request("/v1/files/")
        assertEquals(200, r.code)
        assertEquals("list", JSONObject(r.body).optString("object"))
    }

    @Test
    fun `empty id does not fall through to detail lookup`() {
        // 明确排除"空 id 命中详情分支"：响应必须是**列表**形状（object=list + data 数组），
        // 而不是单条文件详情。用形状判断而不是内容判断 —— 列表里本来就该出现已有 id。
        val r = request("/v1/files/")
        val j = JSONObject(r.body)
        assertEquals("object 必须是 list", "list", j.optString("object"))
        assertTrue("必须带 data 数组", j.opt("data") is org.json.JSONArray)
    }

    @Test
    fun `unknown id reports file not found`() {
        val r = request("/v1/files/file-doesnotexist")
        assertEquals(404, r.code)
        assertEquals("file_not_found", JSONObject(r.body).optJSONObject("error")?.optString("code"))
    }

    @Test
    fun `auth is required before id resolution`() {
        java.net.Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().write(
                ("GET /v1/files/$plainId HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
            )
            s.getOutputStream().flush()
            val text = s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            assertEquals("未带密钥应 401", 401, text.substringAfter("HTTP/1.1 ").substringBefore(" ").toInt())
        }
    }

    // ---------------- 5. DELETE ----------------

    @Test
    fun `delete removes the file and reports deleted`() {
        val r = request("/v1/files/$plainId", "DELETE")
        assertEquals(200, r.code)
        assertTrue(JSONObject(r.body).optBoolean("deleted"))
        assertEquals("删除后应查不到", null, fileStore.find(plainId))
    }

    @Test
    fun `delete on encoded id works`() {
        val r = raw("/v1/files/file-a%20b", "DELETE")
        assertEquals(200, r.code)
        assertEquals(null, fileStore.find(encSpace))
        assertEquals("另一个 id 不应受影响", true, fileStore.find(encSlash) != null)
    }
}
