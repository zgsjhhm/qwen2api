package com.qwen2api.tx

import com.qwen2api.tx.core.ChatMessage
import com.qwen2api.tx.core.ToolFilter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 工具按需注入（[ToolFilter]）的单元测试。
 *
 * ## 为什么单独补这个类
 * ToolFilter 决定「哪些工具会出现在提示词里」，一旦裁错就直接导致模型
 * 回答「没有这个能力」——对用户是硬失败。但改动前它在测试里是**零引用**的，
 * 属于整条工具链上唯一没有回归保护的环节。
 *
 * ## 覆盖重点
 *  1. 裁剪边界：只在命中场景保留 APK 组，其余一律不动
 *  2. 误伤防护：不确定 / 会裁空 / 非可裁剪组 → 必须保持全量
 *  3. 多轮连续性：历史里调用过的工具组必须保留（否则工具链中途断掉）
 *  4. 统计：裁剪与保留分别计数，供 logcat 核对
 *
 * 注意：ToolFilter 是带全局状态的 object，统计字段是 `private set` 无法重置，
 * 因此统计断言一律用**前后差值**，不假设绝对初值。
 */
class ToolFilterTest {

    /** 构造一个 OpenAI 形态的工具条目 */
    private fun tool(name: String): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", "$name 的说明")
                .put("parameters", JSONObject().put("type", "object")),
        )

    private fun toolsOf(vararg names: String): JSONArray {
        val arr = JSONArray()
        names.forEach { arr.put(tool(it)) }
        return arr
    }

    private fun names(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            out.add(arr.optJSONObject(i)!!.optJSONObject("function")!!.optString("name"))
        }
        return out
    }

    private fun user(text: String) = ChatMessage("user", text)
    private fun assistantToolCall(name: String): ChatMessage = ChatMessage(
        "assistant",
        "",
        toolCalls = JSONArray().put(
            JSONObject()
                .put("id", "call_x")
                .put("type", "function")
                .put("function", JSONObject().put("name", name).put("arguments", "{}")),
        ),
    )

    /** 常见的 APK 逆向族工具名（来自太墟真实注册名） */
    private val apkTool = "mcp__MTmcp__mt_apk_manifest"
    private val apkTool2 = "mcp__MTmcp__mt_dex_strings"
    private val apktoolTool = "mcp__mcp_apktool__decode_apk"
    private val fileTool = "mcp__MTmcp__mt_file_read"
    private val browserTool = "mcp__taixu-browser-navigate"
    private val searchTool = "mcp__mcp_websearch__web_search"
    private val plainTool = "get_weather"

    @Before
    fun setUp() {
        // 默认开关状态（其他测试可能关过），逐条恢复
        ToolFilter.enabled = true
    }

    @After
    fun tearDown() {
        ToolFilter.enabled = true
    }

    // ---------------- 1. 基本边界 ----------------

    @Test
    fun `null tools returns null`() {
        assertNull(ToolFilter.filter(null, listOf(user("帮我反编译 apk"))))
    }

    @Test
    fun `empty tools returns same empty array`() {
        val empty = JSONArray()
        val out = ToolFilter.filter(empty, listOf(user("apk")))
        assertSame("空数组应原样返回，不新建对象", empty, out)
    }

    @Test
    fun `disabled filter returns original array untouched`() {
        ToolFilter.enabled = false
        val tools = toolsOf(apkTool, fileTool)
        val out = ToolFilter.filter(tools, listOf(user("写个快排")))
        assertSame("关闭开关时必须完全等价于改动前行为", tools, out)
    }

    @Test
    fun `unknown and ungrouped tools are always kept`() {
        val tools = toolsOf(plainTool, fileTool, browserTool, searchTool)
        val out = ToolFilter.filter(tools, listOf(user("写个快排")))
        assertEquals(4, out!!.length())
        assertTrue(names(out).containsAll(listOf(plainTool, fileTool, browserTool, searchTool)))
    }

    // ---------------- 2. 裁剪：正常对话应裁掉 APK 组 ----------------

    @Test
    fun `apk tools are pruned in ordinary conversation`() {
        val tools = toolsOf(apkTool, apkTool2, fileTool, plainTool)
        val out = ToolFilter.filter(tools, listOf(user("帮我写一个快排")))!!
        assertEquals(listOf(fileTool, plainTool), names(out))
    }

    @Test
    fun `apktool group is pruned independently of apk group`() {
        val tools = toolsOf(apkTool, apktoolTool, plainTool)
        val out = ToolFilter.filter(tools, listOf(user("帮我改个 bug")))!!
        assertEquals(listOf(plainTool), names(out))
    }

    @Test
    fun `original tools array is not mutated when pruning`() {
        val tools = toolsOf(apkTool, plainTool)
        ToolFilter.filter(tools, listOf(user("写个快排")))
        assertEquals("输入数组必须保持原样（网关会用它做校验）", 2, tools.length())
    }

    @Test
    fun `never prunes everything - falls back to full set`() {
        // 只有可裁剪组的工具，且无关键词 -> 裁空必须回退全量，
        // 否则模型会拿到 0 个工具，直接回答「没有能力」。
        val tools = toolsOf(apkTool, apkTool2)
        val out = ToolFilter.filter(tools, listOf(user("写个快排")))
        assertSame(tools, out)
    }

    // ---------------- 3. 触发词：命中时必须保留 ----------------

    @Test
    fun `apk keyword keeps apk group`() {
        val tools = toolsOf(apkTool, fileTool)
        val out = ToolFilter.filter(tools, listOf(user("帮我分析这个 apk 的签名校验")))!!
        assertEquals(2, out.length())
    }

    @Test
    fun `all documented apk triggers keep the group`() {
        val triggers = listOf(
            "帮我反编译一下", "这是 smali 代码", "看看 dex 里的字符串",
            "逆向这个应用", "app 加了壳，需要脱壳", "检查 AndroidManifest 权限",
            "包名是什么", "分析 so 文件", "看下 classes.dex",
        )
        triggers.forEach { t ->
            val tools = toolsOf(apkTool, fileTool)
            val out = ToolFilter.filter(tools, listOf(user(t)))!!
            assertEquals("触发词「$t」应保留 apk 组", 2, out.length())
        }
    }

    @Test
    fun `trigger matching is case insensitive`() {
        val tools = toolsOf(apkTool, fileTool)
        val out = ToolFilter.filter(tools, listOf(user("分析这个 APK 的 DEX")))!!
        assertEquals("大写关键词也必须命中", 2, out.length())
    }

    @Test
    fun `apktool trigger keeps only apktool group`() {
        // 注意：这里刻意不出现 "apk" 字样，否则会同时命中 apk 组，
        // 测不出「两组独立判定」这个关键行为。
        val tools = toolsOf(apkTool, apktoolTool, fileTool)
        val out = ToolFilter.filter(tools, listOf(user("帮我重新打包这个应用并重新签名")))!!
        // apktool 命中 -> 保留；apk 组未命中 -> 裁掉
        assertEquals(listOf(apktoolTool, fileTool), names(out))
    }

    @Test
    fun `generic words like analysis alone do not keep apk group`() {
        // "分析" 是刻意排除的泛词：几乎每轮都出现，命中等于没过滤
        val tools = toolsOf(apkTool, fileTool)
        val out = ToolFilter.filter(tools, listOf(user("帮我分析一下这段业务逻辑")))!!
        assertEquals(listOf(fileTool), names(out))
    }

    // ---------------- 4. 关键词只看最近若干条消息 ----------------

    @Test
    fun `trigger keyword outside recent window does not keep group`() {
        // filter 只看最近 6 条：把关键词放在最老的第 1 条，其余 7 条无关 ->
        // 总 8 条，takeLast(6) 不含第 1 条 -> apk 组应被裁掉
        val messages = ArrayList<ChatMessage>()
        messages.add(user("帮我分析这个 apk"))
        repeat(7) { messages.add(user("继续第 $it 步")) }

        val tools = toolsOf(apkTool, fileTool)
        val out = ToolFilter.filter(tools, messages)!!
        assertEquals("远古关键词不应长期占用提示词体积", listOf(fileTool), names(out))
    }

    @Test
    fun `trigger keyword inside recent window keeps group`() {
        val messages = ArrayList<ChatMessage>()
        repeat(7) { messages.add(user("继续第 $it 步")) }
        messages.add(user("现在来分析这个 apk"))

        val tools = toolsOf(apkTool, fileTool)
        val out = ToolFilter.filter(tools, messages)!!
        assertEquals(2, out.length())
    }

    // ---------------- 5. 多轮连续性：历史用过的组必须保留 ----------------

    @Test
    fun `group used in old history is kept even without keyword`() {
        // 关键回归点：上一轮调用过 mt_apk_*，本轮正文完全没提 apk。
        // 若这里被裁掉，模型下一轮就找不到工具名，多轮工具链直接从中间断掉。
        val messages = listOf(
            user("帮我看看这个包"),
            assistantToolCall(apkTool),
            ChatMessage("tool", "{\"ok\":true}", toolCallId = "call_x", name = apkTool),
            user("继续"),
        )
        val tools = toolsOf(apkTool, fileTool)
        val out = ToolFilter.filter(tools, messages)!!
        assertEquals("历史调用过的组必须保留", listOf(apkTool, fileTool), names(out))
    }

    @Test
    fun `group used in history is kept even when far outside recent window`() {
        // usedGroups 扫全部消息（不是最近 6 条），保证长会话中途不失效
        val messages = ArrayList<ChatMessage>()
        messages.add(assistantToolCall(apkTool))
        repeat(10) { messages.add(user("继续第 $it 步")) }

        val tools = toolsOf(apkTool, fileTool)
        val out = ToolFilter.filter(tools, messages)!!
        assertEquals(listOf(apkTool, fileTool), names(out))
    }

    @Test
    fun `tool role message name also marks group as used`() {
        val messages = listOf(
            ChatMessage("tool", "{\"ok\":true}", toolCallId = "c1", name = apktoolTool),
            user("继续"),
        )
        val tools = toolsOf(apktoolTool, apkTool, fileTool)
        val out = ToolFilter.filter(tools, messages)!!
        assertEquals(listOf(apktoolTool, fileTool), names(out))
    }

    // ---------------- 6. 健壮性 ----------------

    @Test
    fun `entries without function object do not crash`() {
        val tools = JSONArray()
        tools.put(JSONObject().put("type", "function"))       // 缺 function
        tools.put(apkTool.let { tool(it) })
        tools.put(JSONObject().put("type", "web_search"))     // 内置类工具，无 function
        val out = ToolFilter.filter(tools, listOf(user("写个快排")))
        // 缺 function 的条目会被跳过（不计入 dropped），不能抛异常
        assertTrue(out!!.length() >= 0)
    }

    @Test
    fun `empty keyword text still keeps full set when nothing prunable`() {
        val tools = toolsOf(fileTool, plainTool)
        val out = ToolFilter.filter(tools, emptyList())
        assertSame(tools, out)
    }

    // ---------------- 7. 统计与描述 ----------------

    @Test
    fun `stats count a pruned run`() {
        val before = ToolFilter.statPruned
        val dropped = ToolFilter.statDroppedTools
        ToolFilter.filter(toolsOf(apkTool, fileTool), listOf(user("写个快排")))
        assertEquals("裁剪次数应 +1", before + 1, ToolFilter.statPruned)
        assertEquals("累计裁掉数应 +1", dropped + 1, ToolFilter.statDroppedTools)
        assertTrue(ToolFilter.lastReason.startsWith("裁剪"))
        assertEquals(2, ToolFilter.lastBefore)
        assertEquals(1, ToolFilter.lastAfter)
    }

    @Test
    fun `stats count a kept run`() {
        val kept = ToolFilter.statKeptByTrigger
        ToolFilter.filter(toolsOf(apkTool, fileTool), listOf(user("分析这个 apk")))
        assertEquals("保留全量次数应 +1", kept + 1, ToolFilter.statKeptByTrigger)
        assertTrue(ToolFilter.lastReason.startsWith("全量"))
    }

    @Test
    fun `describe reports full set when nothing was dropped`() {
        assertEquals("tools 5 (全量)", ToolFilter.describe(5, 5, listOf(user("apk"))))
    }

    @Test
    fun `describe reports pruning with hit groups`() {
        val d = ToolFilter.describe(5, 2, listOf(user("分析 apk")))
        assertTrue("应包含裁剪前后数量", d.contains("5→2"))
        assertTrue("应标注命中的可裁剪组", d.contains("apk"))
    }

    @Test
    fun `describe reports no hit group when apk pruned`() {
        val d = ToolFilter.describe(5, 2, listOf(user("写个快排")))
        assertTrue(d.contains("5→2"))
        assertTrue("未命中时应显式说明 apk 组已裁剪", d.contains("apk"))
    }

    @Test
    fun `stats string is non empty after a recorded run`() {
        ToolFilter.filter(toolsOf(fileTool), listOf(user("hi")))
        assertTrue(ToolFilter.stats().contains("总 "))
        assertFalse(ToolFilter.stats().isEmpty())
    }
}
