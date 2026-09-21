package com.qwen2api.tx

import com.qwen2api.tx.core.ApiLogLevel
import com.qwen2api.tx.core.ApiLogStore
import com.qwen2api.tx.core.ChatMessage
import com.qwen2api.tx.core.ChatResult
import com.qwen2api.tx.core.GatewayConfig
import com.qwen2api.tx.core.MemoryAccountRepository
import com.qwen2api.tx.core.MemoryConfigRepository
import com.qwen2api.tx.core.QwenAccount
import com.qwen2api.tx.core.QwenClient
import com.qwen2api.tx.core.QwenEvent
import com.qwen2api.tx.core.QwenException
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
 * 多账号路由 + 调用日志在**网关层**的接线回归。
 *
 * ## 为什么必须在网关层再测一遍
 *
 * [AccountRoutingTest] 钉的是"选谁/何时冷却"的纯逻辑，它无法回答本次改动的
 * 真正风险：**这些规则到底有没有被真实请求链路用上**。
 * 具体有三个只会在这里暴露的错法：
 *
 *  1. 路由选出了第二个账号，但客户端还是用构造时的旧凭证发出去（`credentialOverride`
 *     没赋值）—— 表现是"明明配了备用账号却永远只用第一个"；
 *  2. 失败后换了账号，但错误没有回写到账号健康度 —— 表现是"每次都从同一个坏账号开始试"；
 *  3. 请求结束了却没写日志条目 —— 表现是日志页永远空白。
 *
 * 三者都不会抛异常，只能靠"打一次真实请求、然后检查这两个可观测产物"来发现。
 */
class AccountSwitchE2ETest {

    private val BAD = "eyJbadCredential.payload.sig"
    private val GOOD = "eyJgoodCredential.payload.sig"

    private lateinit var configRepo: MemoryConfigRepository
    private lateinit var accountRepo: MemoryAccountRepository
    private lateinit var logStore: ApiLogStore
    private lateinit var server: MiniHttpServer
    private var port = 0

    /** 每个凭证被用了几次，用于证明"确实换过账号" */
    private val usedCredentials = ArrayList<String>()

    /**
     * 假上游：用坏凭证一律 `AUTH_FAILED`，用备用凭证成功。
     *
     * 抛 [QwenException] 而不是普通异常：路由的"可否切换"判定依赖错误码，
     * 用普通异常会走 `normalizeError` 归一化成别的码，测的就不是这条路径了。
     */
    private inner class SwitchableClient : QwenClient("placeholder", 0) {
        override suspend fun chatStream(
            model: String,
            messages: List<ChatMessage>,
            thinking: Boolean,
            files: List<JSONObject>,
            onEvent: (suspend (QwenEvent) -> Unit)?,
        ): ChatResult {
            val cred = credentialOverride.orEmpty()
            synchronized(usedCredentials) { usedCredentials.add(cred) }
            // 只认 GOOD：这样"坏凭证"不必被钉死成某一个常量，
            // 新增用例可以随便造一个凭证来构造"失败落在命名账号上"的场景。
            if (cred != GOOD) throw QwenException("AUTH_FAILED", "凭证已失效", 401)
            onEvent?.invoke(QwenEvent.Content("备用账号的回复"))
            return ChatResult(
                chatId = "fake-chat", answer = "备用账号的回复", thinking = "",
                steps = emptyList(), docs = emptyList(), queries = emptyList(),
                usage = JSONObject().put("total_tokens", 7),
                model = model, responseId = "resp-1",
            )
        }
    }

    @Before
    fun setUp() {
        configRepo = MemoryConfigRepository(
            GatewayConfig(apiKey = "sk-test-key", qwenToken = BAD, defaultModel = "qwen3.8-max"),
        )
        accountRepo = MemoryAccountRepository(
            listOf(
                QwenAccount(
                    id = "acc-backup",
                    label = "备用",
                    credential = GOOD,
                    // lastUsedAt 设为"刚刚用过"：路由刻意不给默认账号任何优先权，
                    // 全靠「最久没用的先用」形成轮转，因此要让默认凭证先被选中，
                    // 只能让备用账号显得刚被用过。
                    lastUsedAt = System.currentTimeMillis(),
                ),
            ),
        )
        logStore = ApiLogStore.inMemory()
        val router = GatewayRouter(
            context = null,
            configRepo = configRepo,
            fileStore = MemoryFileStore(),
            clientFactory = { SwitchableClient() },
            accountRepo = accountRepo,
            logStore = logStore,
        )
        server = MiniHttpServer(0, "127.0.0.1") { req, res -> router.handle(req, res) }
        server.start()
        port = server.boundPort
    }

    @After
    fun tearDown() {
        runCatching { server.stop() }
    }

    private data class Resp(val code: Int, val body: String)

    private fun postChat(stream: Boolean): Resp {
        val conn = URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000
        conn.readTimeout = 15000
        conn.setRequestProperty("Authorization", "Bearer ${configRepo.load().apiKey}")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        val payload = """{"model":"qwen3.8-max","messages":[{"role":"user","content":"hi"}],"stream":$stream}"""
        conn.outputStream.use { it.write(payload.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        conn.disconnect()
        return Resp(code, text)
    }

    // ---------------- 1. 自动切换 ----------------

    @Test
    fun `默认凭证失效时自动切到备用账号且对调用方透明`() {
        val r = postChat(stream = false)
        assertEquals("换账号后这次请求应当成功: ${r.body}", 200, r.code)
        val content = JSONObject(r.body)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
        assertEquals("备用账号的回复", content)

        synchronized(usedCredentials) {
            assertEquals("应先用默认凭证再换备用", listOf(BAD, GOOD), usedCredentials)
        }
    }

    @Test
    fun `切换后坏账号被记上失败 备用账号被记为健康`() {
        postChat(stream = false)
        val backup = accountRepo.find("acc-backup")!!
        assertEquals("成功的账号不该留失败记录", "", backup.lastError)
        assertTrue("成功应更新 lastUsedAt", backup.lastUsedAt > 0L)
    }

    // ---------------- 2. 日志 ----------------

    @Test
    fun `成功的请求会写一条日志并带上账号与尝试次数`() {
        postChat(stream = false)
        val entries = logStore.recent()
        assertEquals("每个结束的请求都该有一条日志", 1, entries.size)
        val e = entries[0]
        assertEquals(ApiLogLevel.OK, e.level)
        assertEquals(200, e.status)
        assertEquals("/v1/chat/completions", e.path)
        assertEquals("qwen3.8-max", e.model)
        assertEquals("acc-backup", e.accountId)
        assertEquals("备用", e.accountLabel)
        assertEquals("上游应被尝试了两次（默认 + 备用）", 2, e.attempts)
        assertEquals("应记录切换了 1 次", 1, e.switches)
        assertTrue("成功也要给结果摘要", e.summary.isNotEmpty())
        assertNotNull("诊断行是排查自动切换的主要线索", e.trace)
        assertTrue(
            "诊断里应能看到账号轮次",
            e.trace!!.any { it.contains("轮次") },
        )
    }

    @Test
    fun `全部账号失败时日志记为失败并带错误码`() {
        configRepo.update { it.copyWith(multiAccount = false) }
        val r = postChat(stream = false)
        assertEquals("凭证失效应映射成 401", 401, r.code)

        val e = logStore.recent().single()
        assertEquals(ApiLogLevel.FAIL, e.level)
        assertEquals(401, e.status)
        assertEquals("AUTH_FAILED", e.errorCode)
        assertTrue(logStore.failCount >= 1L)
    }

    /**
     * 最终失败必须归因到**真正失败的那个账号**，不能因为"没有成功过"就丢成空标签。
     *
     * 为什么这条单独测：失败条目若 `accountLabel` 为空，UI 与导出都会回退成
     * 「(默认账号)」（见 MainScreen 的 `ifEmpty { "(默认账号)" }` 与 `exportText`），
     * 于是"备用账号坏了"被显示成"默认账号坏了" —— 用户照着这个提示去换默认 token，
     * 换完还是失败。这正是多账号路由存在的意义被抹掉的那种错。
     *
     * 关键构造：让**命名账号**排在候选首位且失败后无处可切（maxAccountSwitches=0），
     * 即"最后一个失败者是命名账号"。默认账号那侧的用例碰巧会被 `ifEmpty` 兜对，
     * 因此只测默认账号的写法发现不了这个缺陷。
     */
    @Test
    fun `最后一个失败者是命名账号时 日志必须归因到它`() {
        val badNamed = "eyJnamedBadCredential.payload.sig"
        accountRepo.upsert(
            QwenAccount(
                id = "acc-bad-named",
                label = "AAA坏号",
                credential = badNamed,
                // 默认账号的 routeUsedAt 恒为 0（设计如此），无法用 lastUsedAt 抢它前面，
                // 所以靠 label 升序把命名账号排到首位（比较器最后一档就是 label）。
                lastUsedAt = 0L,
            ),
        )
        configRepo.update { it.copyWith(maxAccountSwitches = 0) }

        val r = postChat(stream = false)
        assertEquals("没有可切换的账号时应如实失败", 401, r.code)

        val e = logStore.recent().single()
        assertEquals(ApiLogLevel.FAIL, e.level)
        assertEquals(
            "失败必须归因到实际失败的命名账号",
            "acc-bad-named", e.accountId,
        )
        assertEquals("AAA坏号", e.accountLabel)
        assertTrue(
            "导出的失败条目也不该显示成默认账号: ${logStore.exportText()}",
            logStore.exportText().contains("AAA坏号"),
        )
    }

    @Test
    fun `累计统计与列表条目数分别反映总量与窗口`() {
        postChat(stream = false)
        postChat(stream = false)
        assertEquals(2, logStore.count())
        assertEquals(2L, logStore.totalCount)
        assertEquals(0L, logStore.failCount)
    }

    @Test
    fun `导出文本包含本次请求的账号链路`() {
        postChat(stream = false)
        val text = logStore.exportText()
        assertTrue(text.contains("acc-backup") || text.contains("备用"))
        assertTrue(text.contains("切换账号 1 次"))
    }

    // ---------------- 3. 开关与上限 ----------------

    @Test
    fun `关闭多账号后不再切换 行为退回单账号`() {
        configRepo.update { it.copyWith(multiAccount = false) }
        val r = postChat(stream = false)
        assertEquals("关掉开关后不该再尝试备用账号", 401, r.code)
        synchronized(usedCredentials) { assertEquals(listOf(BAD), usedCredentials) }
    }

    @Test
    fun `切换次数上限为 0 时不换账号`() {
        configRepo.update { it.copyWith(maxAccountSwitches = 0) }
        val r = postChat(stream = false)
        assertEquals(401, r.code)
        synchronized(usedCredentials) { assertEquals(listOf(BAD), usedCredentials) }
    }

    @Test
    fun `账号列表为空时使用默认凭证 不报配置错误`() {
        configRepo.update { it.copyWith(qwenToken = GOOD) }
        val r = postChat(stream = false)
        assertEquals(200, r.code)
        synchronized(usedCredentials) { assertEquals(listOf(GOOD), usedCredentials) }
        val e = logStore.recent().single()
        assertEquals("", e.accountId)
        assertEquals("默认账号", e.accountLabel)
    }

    @Test
    fun `管理接口能读到账号健康与日志`() {
        postChat(stream = false)
        val key = configRepo.load().apiKey

        val accConn = URL("http://127.0.0.1:$port/admin/api/accounts").openConnection() as HttpURLConnection
        accConn.setRequestProperty("Authorization", "Bearer $key")
        val accBody = accConn.inputStream.bufferedReader().use { it.readText() }
        accConn.disconnect()
        val accJson = JSONObject(accBody)
        assertTrue(accJson.getBoolean("ok"))
        val acc = accJson.getJSONArray("accounts").getJSONObject(0)
        assertEquals("acc-backup", acc.getString("id"))
        assertTrue("凭证绝不能明文回显", !acc.getString("mask").contains(GOOD))

        val logConn = URL("http://127.0.0.1:$port/admin/api/logs").openConnection() as HttpURLConnection
        logConn.setRequestProperty("Authorization", "Bearer $key")
        val logBody = logConn.inputStream.bufferedReader().use { it.readText() }
        logConn.disconnect()
        assertEquals(1, JSONObject(logBody).getJSONArray("entries").length())
    }

    @Test
    fun `日志导出接口返回 markdown 附件`() {
        postChat(stream = false)
        val key = configRepo.load().apiKey
        val conn = URL("http://127.0.0.1:$port/admin/api/logs/export?trace=1")
            .openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $key")
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val ct = conn.getHeaderField("Content-Type")
        val cd = conn.getHeaderField("Content-Disposition")
        conn.disconnect()
        assertTrue("导出应是可直接读的文本: $ct", ct.contains("text/markdown"))
        assertTrue("应作为附件下载: $cd", cd.contains("attachment"))
        assertTrue(text.contains("# Qwen2API 调用日志导出"))
    }
}
