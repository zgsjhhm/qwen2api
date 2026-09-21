package com.qwen2api.tx

import com.qwen2api.tx.core.ApiLogLevel
import com.qwen2api.tx.core.ApiLogStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 调用日志仓库的行为回归。
 *
 * ## 为什么这组用例值得单写一份
 *
 * 这个仓库的每一条设计取舍都对应一个「不报错但信息错」的失效模式：
 *
 *  - 落盘写成整份重写 -> 一次被杀进程就丢光全部历史（用户最需要的"刚才那次失败"）
 *  - trace 全量保留 -> 文件涨到几十 MB，导出后根本发不出去
 *  - 未 finish 的请求也进列表 -> 列表里出现"空白/进行中"条目，用户以为请求成功了
 *  - 单行损坏让整份文件解析失败 -> 一条坏行报废全部历史
 *
 * 这些都不会抛异常，只会在真正要排查问题时才发现日志不可用。因此逐条钉住。
 */
class ApiLogStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(file: File? = null, maxEntries: Int = 400, maxTrace: Int = 60) =
        ApiLogStore(file, maxEntries, maxTrace)

    private fun finishOk(s: ApiLogStore, path: String = "/v1/chat/completions", ms: Long = 10) {
        val t = s.begin("POST", path, model = "qwen3.8-max")
        t.attempts = 1
        t.line("first")
        t.finish(ApiLogLevel.OK, 200, summary = "12ch")
    }

    private fun finishFail(s: ApiLogStore, code: String = "AUTH_FAILED", status: Int = 401) {
        val t = s.begin("POST", "/v1/chat/completions")
        t.attempts = 3
        t.switches = 2
        t.useAccount("acc-1", "备用")
        t.line("switch")
        t.finish(ApiLogLevel.FAIL, status, errorCode = code, message = "凭证失效")
    }

    // ---------------- 1. 基本写入与查询语义 ----------------

    @Test
    fun `未 finish 的请求不进列表`() {
        val s = store()
        s.begin("POST", "/v1/chat/completions")
        assertEquals("进行中的请求不该出现在结果列表里", 0, s.count())
        assertTrue(s.recent().isEmpty())
    }

    @Test
    fun `recent 由新到旧 且为空时不为 null`() {
        val s = store()
        finishOk(s, "/a")
        finishOk(s, "/b")
        val list = s.recent()
        assertEquals(listOf("/b", "/a"), list.map { it.path })
    }

    @Test
    fun `finish 只生效一次 重复调用不会产生两条`() {
        val s = store()
        val t = s.begin("POST", "/x")
        t.finish(ApiLogLevel.OK, 200)
        t.finish(ApiLogLevel.FAIL, 500, errorCode = "X")
        assertEquals(1, s.count())
        assertEquals(ApiLogLevel.OK, s.recent()[0].level)
    }

    @Test
    fun `finish 之后追加诊断行被忽略`() {
        val s = store()
        val t = s.begin("POST", "/x")
        t.finish(ApiLogLevel.OK, 200)
        t.line("迟到的行")
        assertNull("已提交的条目不该再变", s.recent()[0].trace)
    }

    @Test
    fun `统计随成功失败分别累加`() {
        val s = store()
        finishOk(s)
        finishFail(s)
        finishFail(s, code = "THROTTLE")
        assertEquals(3L, s.totalCount)
        assertEquals(2L, s.failCount)
        assertTrue("最近一次失败要能被状态接口读到", s.lastFailure.contains("THROTTLE"))
    }

    @Test
    fun `清空会连统计一起归零`() {
        val s = store()
        finishOk(s)
        finishFail(s)
        s.clear()
        assertEquals(0, s.count())
        assertEquals(0L, s.totalCount)
        assertEquals(0L, s.failCount)
        assertEquals("", s.lastFailure)
    }

    @Test
    fun `多账号信息被完整记录`() {
        val s = store()
        finishFail(s)
        val e = s.recent()[0]
        assertEquals("acc-1", e.accountId)
        assertEquals("备用", e.accountLabel)
        assertEquals(3, e.attempts)
        assertEquals(2, e.switches)
        assertEquals("AUTH_FAILED", e.errorCode)
    }

    // ---------------- 2. 环形缓冲与 trace 上限 ----------------

    @Test
    fun `超出上限时淘汰最旧的条目`() {
        val s = store(maxEntries = 3)
        (1..5).forEach { finishOk(s, "/$it") }
        assertEquals(3, s.count())
        assertEquals(listOf("/5", "/4", "/3"), s.recent().map { it.path })
    }

    @Test
    fun `累计统计不受淘汰影响`() {
        // 用户关心的是"一共失败过几次"，而不是"缓冲区里还剩几条失败"。
        val s = store(maxEntries = 2)
        repeat(5) { finishOk(s) }
        assertEquals(5L, s.totalCount)
    }

    @Test
    fun `trace 只保留最近的若干条`() {
        val s = store(maxEntries = 50, maxTrace = 3)
        (1..5).forEach { i ->
            val t = s.begin("POST", "/$i")
            t.line("line-$i")
            t.finish(ApiLogLevel.OK, 200)
        }
        val list = s.recent()   // 新 -> 旧
        assertTrue("最近 3 条应有诊断", list.take(3).all { it.trace != null })
        assertNull("更早的条目诊断应被丢弃以控制体积", list[3].trace)
    }

    @Test
    fun `没有诊断行时 trace 为 null 而不是空列表`() {
        // 空列表与非空列表在导出时会渲染出不同的东西，用 null 明确表达"本就没有"。
        val s = store()
        val t = s.begin("GET", "/healthz")
        t.finish(ApiLogLevel.OK, 200)
        assertNull(s.recent()[0].trace)
    }

    // ---------------- 3. 落盘与重启恢复 ----------------

    @Test
    fun `落盘后重建实例能读回历史`() {
        val f = File(tmp.root, "api_call_log.jsonl")
        val s1 = store(f)
        finishOk(s1, "/a")
        finishFail(s1)
        assertTrue("日志必须落盘，否则用户只能守在 App 前复现", f.exists())

        val s2 = store(f)
        assertEquals(2, s2.count())
        assertEquals(2L, s2.totalCount)
        assertEquals(1L, s2.failCount)
        assertTrue(s2.lastFailure.contains("AUTH_FAILED"))
        assertEquals(listOf("/v1/chat/completions", "/a"), s2.recent().map { it.path })
    }

    @Test
    fun `新实例的条目 id 不会与历史冲突`() {
        // seq 从历史最大 id 续接：否则新条目 id 与磁盘上的旧条目重复，
        // 任何按 id 去重/引用的下游逻辑都会错。
        val f = File(tmp.root, "api_call_log.jsonl")
        val s1 = store(f)
        finishOk(s1)
        finishOk(s1)
        val maxId = s1.recent()[0].id

        val s2 = store(f)
        finishOk(s2)
        assertTrue("新 id 必须大于历史最大 id", s2.recent()[0].id > maxId)
    }

    @Test
    fun `单行损坏只丢那一条 不报废整份历史`() {
        val f = File(tmp.root, "api_call_log.jsonl")
        val s1 = store(f)
        finishOk(s1, "/good-1")
        finishOk(s1, "/good-2")

        val lines = f.readLines().toMutableList()
        lines.add(1, "{ 这不是合法 JSON")
        f.writeText(lines.joinToString("\n") + "\n")

        val s2 = store(f)
        assertEquals("坏行只该丢自己那一条", 2, s2.count())
        assertEquals(listOf("/good-2", "/good-1"), s2.recent().map { it.path })
    }

    @Test
    fun `文件不存在时读盘静默返回空`() {
        val s = store(File(tmp.root, "never-created.jsonl"))
        assertEquals(0, s.count())
        assertTrue(s.recent().isEmpty())
    }

    @Test
    fun `纯内存模式不落盘也不报错`() {
        val s = store(null)
        finishOk(s)
        assertEquals(1, s.count())
        assertEquals(0, tmp.root.listFiles()!!.size)
    }

    @Test
    fun `文件超限后压缩为最近若干条`() {
        // 用极小的 maxEntries 触发压缩路径：验证压缩后仍可解析、条数收敛到上限内。
        val f = File(tmp.root, "api_call_log.jsonl")
        val s = store(f, maxEntries = 4)
        repeat(20) { i ->
            val t = s.begin("POST", "/p$i")
            // 撑大单条体积，让文件尽早越过体积阈值
            t.line("x".repeat(5000))
            t.finish(ApiLogLevel.OK, 200)
        }
        val s2 = store(f, maxEntries = 4)
        assertTrue("压缩后条数不应超过上限", s2.count() <= 4)
        assertTrue("压缩后仍能读出内容", s2.count() > 0)
    }

    // ---------------- 4. 导出 ----------------

    @Test
    fun `导出在无记录时也给出可读说明`() {
        val text = store().exportText()
        assertTrue(text.contains("# Qwen2API 调用日志导出"))
        assertTrue("空日志要说清为什么空，而不是给个空文件", text.contains("暂无调用记录"))
    }

    @Test
    fun `导出包含失败汇总与账号切换信息`() {
        val s = store()
        finishFail(s)
        finishOk(s, "/v1/models")
        val text = s.exportText()
        assertTrue("失败必须聚合成一眼能看懂的汇总", text.contains("## 失败汇总"))
        assertTrue(text.contains("`AUTH_FAILED` × 1"))
        assertTrue("切换次数是排查自动切换的关键线索", text.contains("切换账号 2 次"))
        assertTrue(text.contains("备用"))
        assertTrue(text.contains("上游尝试 3 次"))
    }

    @Test
    fun `导出可以关闭诊断以减小体积`() {
        val s = store()
        finishOk(s)
        assertTrue("默认带诊断", s.exportText(includeTrace = true).contains("first"))
        assertFalse("关掉后不该出现诊断块", s.exportText(includeTrace = false).contains("first"))
    }

    @Test
    fun `超出诊断保留范围的条目在导出里有明确说明`() {
        val s = store(maxEntries = 50, maxTrace = 1)
        finishOk(s, "/old")
        finishOk(s, "/new")
        val text = s.exportText()
        assertTrue("不能静默省略，否则用户会以为这条本来就没有诊断", text.contains("超出诊断保留范围"))
    }

    @Test
    fun `导出按新到旧排列`() {
        val s = store()
        finishOk(s, "/older")
        finishOk(s, "/newer")
        val text = s.exportText()
        assertTrue(text.indexOf("/newer") < text.indexOf("/older"))
    }

    // ---------------- 5. 序列化兼容 ----------------

    @Test
    fun `条目序列化往返不丢字段`() {
        val s = store()
        finishFail(s)
        val e = s.recent()[0]
        val back = com.qwen2api.tx.core.ApiLogEntry.fromJson(JSONObject(e.toJson().toString()))!!
        assertEquals(e.id, back.id)
        assertEquals(e.level, back.level)
        assertEquals(e.method, back.method)
        assertEquals(e.path, back.path)
        assertEquals(e.status, back.status)
        assertEquals(e.accountId, back.accountId)
        assertEquals(e.attempts, back.attempts)
        assertEquals(e.switches, back.switches)
        assertEquals(e.errorCode, back.errorCode)
        assertEquals(e.trace, back.trace)
    }

    @Test
    fun `缺少 trace 字段的历史条目仍能解析`() {
        val s = store()
        finishOk(s)
        val json = JSONObject(s.recent()[0].toJson().toString())
        json.remove("trace")
        val back = com.qwen2api.tx.core.ApiLogEntry.fromJson(json)!!
        assertNull(back.trace)
    }
}
