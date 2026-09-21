package com.qwen2api.tx.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具按需注入：按「任务场景」过滤 tools，避免把用不上的工具塞进提示词。
 *
 * 为什么需要：
 *   太墟一次发送 49 个工具（实测 104KB），压缩后仍有 ~11K 字符。
 *   其中「APK 逆向」组独占 **69.6%**（7.8K 字符 / 约 3.5K tokens）。
 *   而绝大多数对话（写代码、查资料、跑命令）根本不碰 APK 逆向，
 *   这部分开销纯属浪费，还拖慢上游处理。
 *
 * 设计原则（保守优先）：
 *   1. **不确定就全量** —— 宁可多花 token，也不能让模型找不到工具。
 *      提示词级工具调用一旦漏注入，模型会直接说「没有这个能力」，
 *      对用户是硬失败，代价远高于多点 token。
 *   2. **只在明确命中场景时裁剪** —— 关键词必须够特征化（如 "apk"、"smali"）。
 *   3. **保留历史工具** —— 若对话里已经调用过某工具，即使本轮关键词不匹配
 *      也要保留，否则多轮工具调用会中途断掉。
 *   4. **开关可控** —— [enabled] 关闭时走原全量逻辑。
 */
object ToolFilter {

    /** 按需注入总开关（默认开）。关闭 = 全量注入，行为与改动前一致。 */
    @Volatile
    var enabled: Boolean = true

    /**
     * 工具分组定义：组名 → 名称前缀列表。
     *
     * 用前缀匹配而非精确名单，是为了兼容太墟动态注册的 MCP 工具
     * （服务器启动时才确定具体名字）。
     */
    private val GROUPS: Map<String, List<String>> = linkedMapOf(
        // APK 逆向族：占体积 70%，且高度专用
        "apk" to listOf("mcp__MTmcp__mt_apk_", "mcp__MTmcp__mt_dex_", "mcp__MTmcp__mt_native_"),
        // 文件/代码操作族
        "file" to listOf("mcp__MTmcp__mt_file_", "mcp__MTmcp__mt_text_"),
        // 浏览器族
        "browser" to listOf("mcp__taixu-browser-"),
        // 代码图谱族
        "codegraph" to listOf("mcp__mcp_codegraph__"),
        // APK 拆包/重打包（apktool MCP）
        "apktool" to listOf("mcp__mcp_apktool__"),
        // 网络检索族
        "search" to listOf("mcp__mcp_websearch__"),
        // 宿主/设备控制族
        "host" to listOf("mcp__MTmcp__mt_device_", "mcp__MTmcp__mt_shell_"),
    )

    /**
     * 允许被裁剪的组。**只放「体积大 + 高度专用」的组**。
     *
     * 判定依据（实测数据）：
     *   apk 组 29 个工具 = 压缩后 7,851 字符，占总量的 69.6%，
     *   且只在 APK 逆向任务里用得上 —— 收益最大、误伤概率最低。
     *
     * 其余组（file/browser/search 等）体积小、场景重叠多，
     * 裁剪它们的风险收益比不划算，一律保留。
     */
    private val PRUNABLE: Set<String> = setOf("apk", "apktool")

    /**
     * 组 → 触发关键词。命中任一即认为该组「可能要用」。
     *
     * 关键词必须足够特征化：
     *   - ✅ "apk"、"smali"、"反编译" —— 不会误伤普通对话
     *   - ❌ "分析"、"文件" —— 太泛，几乎每轮都命中，等于没过滤
     */
    private val TRIGGERS: Map<String, List<String>> = linkedMapOf(
        "apk" to listOf(
            "apk", "dex", "smali", "反编译", "反编", "逆向", "脱壳", "加固",
            "androidmanifest", "包名", "native", "so 文件", "so文件",
            "反汇编", "字节码", "mt管理器", "mt://", "classes.dex",
        ),
        "apktool" to listOf(
            "apktool", "重打包", "重新打包", "回编译", "签名 apk", "签名apk",
        ),
        "file" to listOf(
            "读文件", "写文件", "改文件", "替换文本", "搜索文件", "找文件",
            "grep", "工作区", "workspace", "文件内容",
        ),
        "browser" to listOf(
            "打开网页", "打开网站", "浏览器", "访问 http", "网站", "网页",
            "百度", "google", "github", "知乎", "微博",
        ),
        "codegraph" to listOf(
            "调用链", "调用关系", "谁调用", "代码图谱", "符号", "定义在哪",
            "影响面", "重构",
        ),
        "search" to listOf(
            "搜索一下", "查一下", "百度一下", "搜一下", "最新消息", "联网搜",
        ),
        "host" to listOf(
            "手机", "安装 app", "安装app", "截屏", "截图", "点屏幕", "打开应用",
            "logcat", "adb", "宿主",
        ),
    )

    /**
     * 判断某工具名属于哪个组。
     * @return 组名；不属于任何已知组时返回 null（视为「通用工具」，永远保留）
     */
    private fun groupOf(name: String): String? {
        for ((g, prefixes) in GROUPS) {
            if (prefixes.any { name.startsWith(it) }) return g
        }
        return null
    }

    /**
     * 从工具数组里挑出本轮需要注入的子集。
     *
     * 策略：**减法**（裁掉明确用不上的组）而不是加法（只保留命中的组）。
     *
     * 为什么用减法：加法在「命中组恰好没有对应工具」时会过滤成空，
     * 只能回退全量，白白浪费过滤机会。减法更稳：
     *   只针对「体积最大且高度专用」的组做裁剪（当前是 APK 逆向组），
     *   其余一律保留 —— 收益集中、风险最小。
     *
     * @param tools 客户端发来的完整 tools
     * @param messages 当前对话消息（用于提取关键词 + 历史工具名）
     * @return 过滤后的 tools；不需要过滤 / 不确定时返回原数组
     */
    fun filter(tools: JSONArray?, messages: List<ChatMessage>): JSONArray? {
        if (tools == null || tools.length() == 0) return tools
        if (!enabled) return tools

        // ---- 1. 收集「本轮上下文关键词」----
        // 只看最近若干条消息，避免远古对话里的无关词长期命中
        val recent = messages.takeLast(6)
        val text = recent.joinToString("\n") { it.textContent() }.lowercase()

        // ---- 2. 已调用过的工具组必须保留（避免多轮工具链中断）----
        val usedGroups = HashSet<String>()
        for (m in messages) {
            val name = m.toolName()
            if (name.isNotEmpty()) groupOf(name)?.let { usedGroups.add(it) }
        }

        // ---- 3. 判定哪些「可裁剪组」本轮确实需要 ----
        // 只有列在 PRUNABLE 里的组才参与裁剪，其余组永远保留。
        val needed = HashSet<String>(usedGroups)
        for (g in PRUNABLE) {
            val words = TRIGGERS[g] ?: continue
            if (words.any { w -> text.contains(w.lowercase()) }) needed.add(g)
        }

        // ---- 4. 组装结果：裁掉「可裁剪组中未命中」的工具 ----
        val out = JSONArray()
        var dropped = 0
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val fn = t.optJSONObject("function") ?: continue
            val name = fn.optString("name")
            val g = groupOf(name)
            if (g != null && g in PRUNABLE && g !in needed) {
                dropped++
            } else {
                out.put(t)
            }
        }

        // ---- 5. 没裁掉任何东西 / 全被裁光 → 保持原样（防御）----
        if (dropped == 0 || out.length() == 0) {
            record(tools.length(), tools.length(), if (dropped == 0) "无可裁剪" else "会裁空")
            return tools
        }
        // 记录「哪些可裁剪组被裁掉了」——这才是本次节省的来源
        val cutGroups = PRUNABLE.filter { it !in needed }
        record(tools.length(), out.length(), "裁掉 " + cutGroups.joinToString(","))
        return out
    }

    /**
     * 生成一行诊断信息，便于在 logcat 里核对过滤效果。
     * 格式：`tools 49→20 (裁掉 29 个) | 命中可裁剪组: 无`
     */
    fun describe(before: Int, after: Int, messages: List<ChatMessage>): String {
        if (before == after) return "tools $before (全量)"
        val recent = messages.takeLast(6).joinToString("\n") { it.textContent() }.lowercase()
        val hit = PRUNABLE.filter { g ->
            TRIGGERS[g]?.any { recent.contains(it.lowercase()) } == true
        }
        return "tools $before→$after (裁掉 ${before - after} 个) | 命中可裁剪组: " +
            hit.joinToString(",").ifEmpty { "无（apk 组已裁剪）" }
    }

    // ---------------- 统计（用于观察真实场景表现） ----------------

    /** 过滤总次数 */
    @Volatile
    var statTotal: Int = 0
        private set

    /** 实际发生裁剪的次数 */
    @Volatile
    var statPruned: Int = 0
        private set

    /** 累计裁掉的工具数 */
    @Volatile
    var statDroppedTools: Int = 0
        private set

    /** 因「可裁剪组命中」而保留全量的次数（说明判定生效） */
    @Volatile
    var statKeptByTrigger: Int = 0
        private set

    /** 记录一次过滤结果（供 [stats] 汇总）。 */
    fun record(before: Int, after: Int, reason: String = "") {
        statTotal++
        if (after < before) {
            statPruned++
            statDroppedTools += (before - after)
            lastReason = "裁剪($reason)"
        } else {
            statKeptByTrigger++
            lastReason = "全量($reason)"
        }
        lastBefore = before
        lastAfter = after
    }

    /** 最近一次判定的说明，便于对照日志排查。 */
    @Volatile
    var lastReason: String = ""
        private set

    @Volatile
    var lastBefore: Int = 0
        private set

    @Volatile
    var lastAfter: Int = 0
        private set

    /** 汇总统计，形如 `总 12 次，裁剪 9 次（75%），累计省 261 个工具`。 */
    fun stats(): String {
        if (statTotal == 0) return "尚无请求"
        val rate = if (statTotal > 0) statPruned * 100 / statTotal else 0
        return "总 ${statTotal} 次，裁剪 ${statPruned} 次(${rate}%)，" +
            "累计省 ${statDroppedTools} 个工具，保留全量 ${statKeptByTrigger} 次"
    }

    /** 从 ChatMessage 里取工具名（兼容 tool 角色的 name 与 assistant 的 tool_calls）。 */
    private fun ChatMessage.toolName(): String {
        // tool 角色消息带 name 字段
        name?.takeIf { it.isNotEmpty() }?.let { return it }
        // assistant 消息里的 tool_calls
        toolCalls?.let { calls ->
            if (calls.length() > 0) {
                calls.optJSONObject(0)?.optJSONObject("function")
                    ?.optString("name")?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return ""
    }
}
