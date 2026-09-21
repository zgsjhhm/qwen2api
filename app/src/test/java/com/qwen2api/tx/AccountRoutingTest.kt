package com.qwen2api.tx

import com.qwen2api.tx.core.AccountRepository
import com.qwen2api.tx.core.AccountRouter
import com.qwen2api.tx.core.AccountStore
import com.qwen2api.tx.core.CredentialRoute
import com.qwen2api.tx.core.GatewayConfig
import com.qwen2api.tx.core.MemoryAccountRepository
import com.qwen2api.tx.core.MemoryConfigRepository
import com.qwen2api.tx.core.QwenAccount
import com.qwen2api.tx.core.QwenException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多账号路由的行为回归。
 *
 * ## 为什么要有这一组
 *
 * 路由的错法几乎都是**静默的**：选错账号不会报错，只会让请求打到同一个坏账号上、
 * 或者在后风控时一次都不切换。这类问题在端到端测试里表现为"偶尔失败"，
 * 归因成本极高。因此这里把「选谁」「何时算冷却」「什么错值得换号」
 * 三条规则钉死成可执行的断言。
 *
 * ## 依赖注入
 *
 * 全部用内存实现 + 可控时钟（[AccountRouter] 的 `clock` 参数），
 * 因此冷却判定不需要真的等 10 分钟 —— 时间在测试里是一个变量，不是事实。
 */
class AccountRoutingTest {

    private val cfgRepo = MemoryConfigRepository(
        GatewayConfig(apiKey = "sk-test", qwenToken = DEFAULT_CRED),
    )

    /**
     * 可控时钟。
     *
     * 起点取**真实当前时间**而不是构造一个固定值：[MemoryAccountRepository] 在写
     * 失败记录时用的是 `System.currentTimeMillis()`（生产实现同理，落盘时间戳必须
     * 是真实时间），若测试时钟取一个过去的时间，冷却判定会算出负 elapsed 并按
     * "冷却已过"处理 —— 于是所有冷却用例都会以"看起来通过"的方式失真。
     */
    private var now = System.currentTimeMillis()

    private fun router(repo: AccountRepository) =
        AccountRouter(repo, cfgRepo, clock = { now }, log = {})

    private fun cfg() = cfgRepo.load()

    private fun account(
        id: String,
        credential: String,
        label: String = "",
        enabled: Boolean = true,
        lastUsedAt: Long = 0L,
        lastErrorAt: Long = 0L,
        lastError: String = "",
    ) = QwenAccount(
        id = id,
        label = label,
        credential = credential,
        enabled = enabled,
        createdAt = 0L,
        lastUsedAt = lastUsedAt,
        lastError = lastError,
        lastErrorAt = lastErrorAt,
        lastErrorCode = if (lastError.isEmpty()) "" else "AUTH_FAILED",
    )

    // ---------------- 1. 候选集与排序 ----------------

    @Test
    fun `没有保存账号时只有默认凭证`() {
        val r = router(MemoryAccountRepository())
        val plan = r.candidates(cfg())
        assertEquals(1, plan.size)
        assertTrue("默认路由必须被标记为默认", plan[0].isDefault)
        assertEquals(DEFAULT_CRED, plan[0].credential)
    }

    @Test
    fun `默认凭证为空且无账号时没有候选`() {
        cfgRepo.update { it.copyWith(qwenToken = "") }
        val r = router(MemoryAccountRepository())
        assertTrue("无凭证可用的场景必须能被识别出来", r.candidates(cfg()).isEmpty())
        assertNull(r.select(cfg()))
    }

    @Test
    fun `多账号按最久没用过优先形成轮转`() {
        val repo = MemoryAccountRepository(
            listOf(
                account("a", "cred-a", lastUsedAt = 5000L),
                account("b", "cred-b", lastUsedAt = 1000L),
                account("c", "cred-c", lastUsedAt = 3000L),
            ),
        )
        val plan = router(repo).candidates(cfg())
        assertEquals(listOf("b", "c", "a"), plan.filter { !it.isDefault }.map { it.accountId })
    }

    @Test
    fun `默认凭证与保存账号是同一凭证时只出现一次`() {
        // 用户先配了默认 token，后来又把它加进账号列表 —— 这是很自然的操作顺序。
        // 若不去重，"切换账号"会切到同一个凭证上，表现为"换了但错误一模一样"。
        val repo = MemoryAccountRepository(listOf(account("dup", DEFAULT_CRED)))
        val plan = router(repo).candidates(cfg())
        assertEquals("同一凭证只能有一个候选", 1, plan.size)
        assertTrue(plan[0].isDefault)
    }

    @Test
    fun `禁用账号不参与候选`() {
        val repo = MemoryAccountRepository(
            listOf(account("off", "cred-off", enabled = false), account("on", "cred-on")),
        )
        val ids = router(repo).candidates(cfg()).map { it.accountId }
        assertEquals("禁用账号不应出现、默认与启用账号都应出现", setOf("", "on"), ids.toSet())
        assertFalse("禁用账号绝不能被选中", ids.contains("off"))
    }

    @Test
    fun `冷却中的账号排到最后`() {
        val repo = MemoryAccountRepository(
            listOf(
                account("cool", "cred-cool", lastUsedAt = 1000L, lastErrorAt = now, lastError = "boom"),
                account("ok", "cred-ok", lastUsedAt = 9000L),
            ),
        )
        val plan = router(repo).candidates(cfg())
        val last = plan.last()
        assertEquals("冷却账号必须排在最后", "cool", last.accountId)
        assertTrue("冷却状态要如实标出", last.cooling)
        assertTrue("应给出剩余冷却时间", last.cooldownLeftMs > 0)
    }

    @Test
    fun `select 优先选健康账号而不是冷却账号`() {
        val repo = MemoryAccountRepository(
            listOf(
                account("cool", "cred-cool", lastErrorAt = now, lastError = "boom"),
                account("ok", "cred-ok"),
            ),
        )
        val picked = router(repo).select(cfg())
        assertNotNull(picked)
        assertEquals("cred-ok", picked!!.credential)
        assertFalse(picked.cooling)
    }

    @Test
    fun `全部账号冷却时仍返回最快恢复的一个并标记冷却`() {
        // 直接失败会让用户在"所有账号都被风控过一次"时彻底不可用，
        // 而上游的瞬时 429 常常过几秒就好 —— 因此这里必须仍可尝试。
        val repo = MemoryAccountRepository(
            listOf(
                account("older", "cred-older", lastErrorAt = now - 60_000L, lastError = "boom"),
                account("newer", "cred-newer", lastErrorAt = now - 1_000L, lastError = "boom"),
            ),
        )
        cfgRepo.update { it.copyWith(qwenToken = "") }
        val picked = router(repo).select(cfg())
        assertNotNull("全冷却时不该直接放弃", picked)
        assertTrue(picked!!.cooling)
        assertEquals("应先试快恢复的那个（lastErrorAt 更早）", "older", picked.accountId)
        assertEquals("cred-older", picked.credential)
        assertTrue(picked.cooldownLeftMs > 0)
    }

    // ---------------- 2. 冷却的时间语义 ----------------

    @Test
    fun `冷却到期后账号重新参与轮转`() {
        cfgRepo.update { it.copyWith(qwenToken = "", accountCooldownMs = 60_000) }
        val repo = MemoryAccountRepository(
            listOf(account("a", "cred-a", lastErrorAt = now, lastError = "boom")),
        )
        val r = router(repo)
        assertTrue("刚失败后应在冷却中", r.candidates(cfg())[0].cooling)

        now += 60_001
        assertFalse("冷却到期必须自动恢复，不能依赖定时器", r.candidates(cfg())[0].cooling)
    }

    @Test
    fun `冷却时长为 0 表示失败后立刻可用`() {
        cfgRepo.update { it.copyWith(qwenToken = "", accountCooldownMs = 0) }
        val repo = MemoryAccountRepository(
            listOf(account("a", "cred-a", lastErrorAt = now, lastError = "boom")),
        )
        assertFalse(router(repo).candidates(cfg())[0].cooling)
    }

    @Test
    fun `时钟回拨不会把账号永久锁死`() {
        // 用户改系统时间 / NTP 校正会让 elapsed 变负。若按"还没到"处理，
        // 账号会永久冷却，而这是用户完全无法自行恢复的状态。
        cfgRepo.update { it.copyWith(qwenToken = "", accountCooldownMs = 60_000) }
        val repo = MemoryAccountRepository(
            listOf(account("a", "cred-a", lastErrorAt = now, lastError = "boom")),
        )
        now -= 3_600_000L
        assertFalse("时钟回拨后必须视为冷却已过", router(repo).candidates(cfg())[0].cooling)
    }

    // ---------------- 3. 失败归属 ----------------

    @Test
    fun `可切换的失败会写冷却 不可切换的不会`() {
        cfgRepo.update { it.copyWith(qwenToken = "") }
        val repo = MemoryAccountRepository(listOf(account("a", "cred-a")))
        val r = router(repo)
        val route = CredentialRoute("a", "A", "cred-a")

        r.noteFail(route, QwenException("BAD_REQUEST", "参数错", 400), switchable = false)
        assertEquals("参数错误与账号健康无关，不该拉黑好账号", 0L, repo.find("a")!!.lastErrorAt)

        r.noteFail(route, QwenException("AUTH_FAILED", "凭证失效", 401), switchable = true)
        val after = repo.find("a")!!
        // 失败时刻由仓库落盘时取自系统时间（生产实现必须如此），因此只断言"刚刚"
        assertTrue("失败时刻应是刚刚", kotlin.math.abs(after.lastErrorAt - now) < 5_000L)
        assertEquals("AUTH_FAILED", after.lastErrorCode)
        assertEquals("凭证失效", after.lastError)
    }

    @Test
    fun `默认凭证失败不写任何账号健康度`() {
        // 默认凭证没有对应的账号记录；若无脑调用 upsert 会凭空造出一个 id 为空的账号。
        val repo = MemoryAccountRepository()
        router(repo).noteFail(CredentialRoute("", "默认账号", DEFAULT_CRED), QwenException("AUTH_FAILED", "x", 401), true)
        assertTrue(repo.all().isEmpty())
    }

    @Test
    fun `成功会清空失败记录并退出冷却`() {
        cfgRepo.update { it.copyWith(qwenToken = "") }
        val repo = MemoryAccountRepository(
            listOf(account("a", "cred-a", lastErrorAt = now, lastError = "boom")),
        )
        val r = router(repo)
        r.noteOk(CredentialRoute("a", "A", "cred-a"))
        val after = repo.find("a")!!
        assertEquals("", after.lastError)
        assertEquals("", after.lastErrorCode)
        assertFalse(r.candidates(cfg())[0].cooling)
    }

    @Test
    fun `记 used 在发请求前发生并驱动轮转`() {
        // 失败同样消耗账号（对风控而言），若只在成功时记时，
        // 坏账号的 lastUsedAt 永远最旧，会被反复优先选中。
        cfgRepo.update { it.copyWith(qwenToken = "") }
        val repo = MemoryAccountRepository(
            listOf(account("a", "cred-a", lastUsedAt = 0L), account("b", "cred-b", lastUsedAt = 0L)),
        )
        val r = router(repo)
        val first = r.candidates(cfg()).first()
        r.noteUsed(first)
        val second = r.candidates(cfg()).first()
        assertNotEqualsSafe(first.accountId, second.accountId)
        assertEquals(now, repo.find(first.accountId)!!.lastUsedAt)
    }

    @Test
    fun `重置健康度会清空全部失败记录`() {
        cfgRepo.update { it.copyWith(qwenToken = "") }
        val repo = MemoryAccountRepository(
            listOf(
                account("a", "cred-a", lastErrorAt = now, lastError = "boom"),
                account("b", "cred-b", lastErrorAt = now, lastError = "boom"),
            ),
        )
        val r = router(repo)
        r.resetHealth()
        assertTrue(r.candidates(cfg()).none { it.cooling })
    }

    @Test
    fun `健康快照与候选顺序一致且字段完整`() {
        cfgRepo.update { it.copyWith(qwenToken = "") }
        val repo = MemoryAccountRepository(
            listOf(
                account("bad", "cred-bad", label = "备用", lastErrorAt = now, lastError = "boom"),
                account("good", "cred-good", label = "主力"),
            ),
        )
        val snap = router(repo).snapshot(cfg())
        assertEquals(listOf("good", "bad"), snap.map { it.accountId })
        assertTrue("健康账号应为 healthy", snap[0].healthy)
        assertFalse("冷却账号不应是 healthy", snap[1].healthy)
        assertEquals("备用", snap[1].label)
        assertEquals("AUTH_FAILED", snap[1].lastErrorCode)
        assertTrue(snap[1].cooldownLeftMs > 0)
    }

    // ---------------- 4. 什么错值得换账号 ----------------

    @Test
    fun `调用方自身的问题不换账号`() {
        val r = router(MemoryAccountRepository())
        val noSwitch = listOf(
            QwenException("BAD_REQUEST", "参数非法", 400),
            QwenException("FILE_TOO_LARGE", "文件超限", 413),
            QwenException("FILE_NOT_FOUND", "文件不存在", 404),
            QwenException("NO_TOKEN", "未配置凭证", 401),
            QwenException("TOKEN_INVALID_CHARS", "非法字符", 400),
            QwenException("PARSE_FAILED", "解析失败", 502),
        )
        noSwitch.forEach {
            assertFalse("换账号也不会变好: ${it.code}", r.shouldSwitch(it))
        }
    }

    @Test
    fun `账号级失败值得换账号`() {
        val r = router(MemoryAccountRepository())
        val sw = listOf(
            QwenException("AUTH_FAILED", "凭证失效", 401),
            QwenException("THROTTLE", "风控", 429),
            QwenException("QUOTA", "额度用尽", 502),
            QwenException("UPSTREAM_ERROR", "上游 500", 502),
        )
        sw.forEach { assertTrue("应换账号: ${it.code}", r.shouldSwitch(it)) }
    }

    // ---------------- 5. 账号仓库与构造 ----------------

    @Test
    fun `upsert 按 id 覆盖而不是累加`() {
        val repo = MemoryAccountRepository()
        repo.upsert(account("a", "cred-1", label = "一"))
        repo.upsert(account("a", "cred-2", label = "二"))
        assertEquals(1, repo.all().size)
        assertEquals("cred-2", repo.find("a")!!.credential)
    }

    @Test
    fun `remove 返回被删对象 未知 id 返回 null`() {
        val repo = MemoryAccountRepository(listOf(account("a", "cred-a", label = "甲")))
        assertEquals("甲", repo.remove("a")!!.displayName())
        assertNull(repo.remove("a"))
    }

    @Test
    fun `noteResult 对未知账号返回 null 而不抛异常`() {
        val repo = MemoryAccountRepository()
        assertNull(repo.noteResult("ghost", ok = false, code = "X"))
    }

    @Test
    fun `失败文案超长会被截断 避免把日志撑爆`() {
        val repo = MemoryAccountRepository(listOf(account("a", "cred-a")))
        repo.noteResult("a", ok = false, code = "X", message = "长".repeat(2000))
        assertEquals(300, repo.find("a")!!.lastError.length)
    }

    @Test
    fun `展示名在未填标签时回退到掩码凭证`() {
        assertTrue(account("a", "cred-abcdefghijklmn").displayName().startsWith("账号 "))
        assertEquals("主力", account("a", "cred", label = "主力").displayName())
    }

    @Test
    fun `空凭证与非法凭证不能建成账号`() {
        assertFalse("空 token 必须被拒绝", AccountStore.build("", "x").ok)
        // 换行/回车这类控制字符会被上游当成 header 注入，属于必须在本地拦下的输入
        assertFalse("含非法字符的凭证必须被拒绝", AccountStore.build("abc\ndef", "x").ok)
        val ok = AccountStore.build("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig", "主力")
        assertTrue(ok.ok)
        assertEquals("jwt", ok.type)
    }

    @Test
    fun `新增账号 id 稳定带前缀且不重复`() {
        val ids = (1..50).map { AccountStore.newId() }
        assertEquals(50, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("acc-") })
    }

    private fun assertNotEqualsSafe(a: String, b: String) {
        assertTrue("轮转应选中不同账号: $a vs $b", a != b)
    }

    private companion object {
        /** 与 MemoryConfigRepository 的默认 apiKey 区分开，避免误把"默认凭证"当成"没配" */
        const val DEFAULT_CRED = "eyJdefaultCredentialForTest.payload.sig"
    }
}
