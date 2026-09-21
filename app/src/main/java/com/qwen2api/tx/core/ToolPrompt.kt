package com.qwen2api.tx.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具调用提示词工程（方案 B 的核心）。
 *
 * ## 背景
 * 上游 chat.qwen.ai 的对话接口**不实现 OpenAI 的 tools 协议**：
 * 实测传 tools / tool_choice 会被静默忽略，模型转而使用内置联网搜索。
 * 因此本网关需要在服务端「模拟」工具调用能力，对外仍暴露标准 OpenAI 协议。
 *
 * ## 做法
 * 1. 请求侧：把 tools 定义翻译成一段系统指令，附加到最后一条 user 消息后面
 * 2. 响应侧：解析模型输出里约定格式的工具调用块，剥离该块后
 *    转成标准 tool_calls，并把 finish_reason 置为 "tool_calls"
 * 3. 多轮：把 role=tool 的结果转换成上游能理解的 user 文本
 *
 * ## 格式约定
 * 要求模型把工具调用放在独立的 fenced code block 里，语言标记用 `tool_call`：
 *
 * ```tool_call
 * {"name": "get_weather", "arguments": {"city": "北京"}}
 * ```
 *
 * 选这个格式的原因：
 *  - 有明确的起止边界，便于流式缓冲时判断「是否进入工具调用区」
 *  - 与正文天然分离，不会和模型正常回答混在一起
 *  - JSON 体可以让模型自由换行/缩进，解析容错度高
 */
object ToolPrompt {

    /** 工具调用块的围栏标记（起止成对） */
    const val FENCE = "```tool_call"
    const val FENCE_END = "```"

    /** 同时发送多个工具调用时，块可以连续出现多个 */

    /**
     * 工具说明的详细程度。
     *
     * 实测：50 个工具完整注入 schema 会产生约 2.3 万字符（真实 MT 工具更甚），
     * 导致上游响应 17 秒、模型注意力稀释并开始「猜」工具名。
     * 因此当工具数量超过阈值时自动切换到精简模式。
     */
    private const val COMPACT_THRESHOLD = 12

    /**
     * 把工具定义压缩成紧凑形式。
     *
     * 压缩原则（踩过坑后修正）：
     *  1. 保留参数名 + 类型 + 是否必填
     *  2. 保留枚举取值（对模型选参至关重要）
     *  3. **保留参数说明的「关键短句」** —— 这里曾经全部丢弃，导致模型
     *     不知道 `editSessionId` 可以传空字符串，进而调错参数、
     *     被客户端误报为「工具不存在」。现在改为「截断而非丢弃」。
     *  4. 丢弃冗长的工具级 description（那个才是膨胀主因）
     */
    private fun compactSchema(params: JSONObject?): String {
        if (params == null) return "{}"
        val props = params.optJSONObject("properties") ?: return "{}"
        val required = HashSet<String>()
        params.optJSONArray("required")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotEmpty() }?.let { required.add(it) }
        }
        val parts = ArrayList<String>()
        val keys = props.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val p = props.optJSONObject(k) ?: continue
            val type = p.optString("type").ifEmpty { "any" }
            val sb = StringBuilder(k).append(':').append(type)
            if (k in required) sb.append('*')
            // 枚举值对模型很关键，保留（截断过长枚举）
            p.optJSONArray("enum")?.let { en ->
                val vals = ArrayList<String>()
                for (i in 0 until minOf(en.length(), 8)) vals.add(en.optString(i))
                if (vals.isNotEmpty()) sb.append('=').append(vals.joinToString("|"))
            }
            // 关键：保留说明里的「可操作短句」，而不是整个丢弃。
            // 优先抽取含"空字符串/默认/必须/可选/pass/empty/default"等
            // 实操提示的短句，它们直接决定模型怎么填这个参数。
            val hint = keyHint(p.optString("description"))
            if (hint.isNotEmpty()) sb.append('「').append(hint).append('」')
            parts.add(sb.toString())
        }
        return parts.joinToString(", ")
    }

    /**
     * 从参数描述里抽取对「怎么填」最有用的短句。
     *
     * 只保留与取值直接相关的提示（默认值、空串、可选、必须等），
     * 避免把整段说明塞回去导致体积反弹。
     */
    private fun keyHint(desc: String): String {
        if (desc.isEmpty()) return ""
        // 按句子切分，挑含操作性关键词的句子
        val sentences = desc.split(Regex("(?<=[.;。；])\\s*"))
        val keywords = listOf(
            "empty string", "pass ", "default", "must ", "optional", "required",
            "if ", "when ", "use ", "set to", "null",
            "空字符串", "默认为", "默认", "必须", "可选", "省略", "留空", "传入",
        )
        // 纯指导性废话：告诉模型「什么时候用这个工具」，对「怎么填参数」无帮助。
        // 这类句子往往最长（实测常占描述 60%+），砍掉能显著减小提示词。
        val useless = listOf(
            "exactly when", "should be used", "use this", "this tool",
            "you should", "make sure", "used to", "is used",
            "注意：", "建议", "推荐使用", "适用于",
        )
        val picked = sentences
            .map { it.trim() }
            .filter { it.isNotEmpty() && !useless.any { u -> it.lowercase().contains(u) } }
            .firstOrNull { s ->
                val low = s.lowercase()
                keywords.any { low.contains(it.lowercase()) }
            } ?: return ""
        // 截断过长描述，保证体积可控
        return if (picked.length <= 54) picked else picked.take(51) + "..."
    }

    /**
     * 把 OpenAI tools 定义翻译成注入用的系统指令。
     *
     * @param tools OpenAI 格式的 tools 数组，可能为 null
     * @param toolChoice OpenAI 的 tool_choice，可能为 "auto"/"none"/"required" 或对象
     * @return 要注入的提示词；没有工具时返回 null
     */
    fun buildInstructions(
        tools: JSONArray?,
        toolChoice: Any? = null,
        locale: String = "zh",
    ): String? {
        if (tools == null || tools.length() == 0) return null
        // tool_choice = "none" 表示禁止调用工具
        val choiceStr = (toolChoice as? String)?.lowercase()
        if (choiceStr == "none") return null

        val compact = tools.length() > COMPACT_THRESHOLD
        val zh = locale.startsWith("zh")

        val sb = StringBuilder()
        if (zh) {
            sb.append("你可以调用下列外部工具。\n\n## 可用工具\n\n")
        } else {
            sb.append("You can call the following external tools.\n\n## Available tools\n\n")
        }

        val names = ArrayList<String>()
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val fn = t.optJSONObject("function") ?: continue
            val name = fn.optString("name").ifEmpty { continue }
            names.add(name)
            val params = fn.optJSONObject("parameters")

            if (compact) {
                // 精简：一行一个工具，只给名字+参数签名，避免提示词爆炸
                sb.append("- `").append(name).append("`")
                val sig = compactSchema(params)
                if (sig != "{}") sb.append(" (").append(sig).append(')')
                sb.append('\n')
            } else {
                val desc = fn.optString("description").ifEmpty { "(no description)" }
                sb.append("### ").append(name).append('\n')
                sb.append(desc).append('\n')
                if (params != null) {
                    sb.append("参数 JSON Schema: ").append(params.toString()).append('\n')
                }
                sb.append('\n')
            }
        }
        if (names.isEmpty()) return null

        if (compact) {
            sb.append('\n')
            if (zh) sb.append("（参数后带 * 为必填；格式为 名称:类型）\n\n")
        }

        // 强制调用：把指定函数名写进指令
        val forcedName = forcedFunctionName(toolChoice)
        if (forcedName != null && names.contains(forcedName)) {
            sb.append(
                if (locale.startsWith("zh")) {
                    "用户要求**必须**调用 `$forcedName` 工具。\n\n"
                } else {
                    "The user requires you to call `$forcedName`.\n\n"
                },
            )
        } else if (isRequired(toolChoice)) {
            // tool_choice="required"：必须调用工具，但不指定哪一个
            sb.append(
                if (locale.startsWith("zh")) {
                    "用户要求**必须**调用上面某个工具（任选最合适的），" +
                        "不要直接回答。\n\n"
                } else {
                    "The user requires you to call one of the tools above " +
                        "(pick the most suitable). Do not answer directly.\n\n"
                },
            )
        }

        sb.append(
            if (zh) {
                "## 调用规则（务必严格遵守）\n\n" +
                    "1. 需要调用工具时，**只输出**下面这种代码块，不要加任何解释文字：\n\n" +
                    "$FENCE\n" +
                    "{\"name\": \"工具名\", \"arguments\": {\"参数名\": \"参数值\"}}\n" +
                    "$FENCE_END\n\n" +
                    "2. 可以连续输出多个这样的代码块来一次调用多个工具。\n" +
                    "3. 参数的键名和类型必须与上面的定义一致。\n" +
                    "4. 若不需要工具、或已有足够信息直接回答，就正常回答，**不要**输出代码块。\n" +
                    "5. 不要在代码块之外输出任何类似工具调用的内容。\n" +
                    "6. **工具名必须从上面的列表里逐字复制**，不要改写、不要增删前缀、" +
                    "不要凭记忆拼写。名字错误会导致调用失败。\n"
            } else {
                "## Rules (strictly follow)\n\n" +
                    "1. To call a tool, output ONLY this code block with no extra prose:\n\n" +
                    "$FENCE\n" +
                    "{\"name\": \"tool_name\", \"arguments\": {\"arg\": \"value\"}}\n" +
                    "$FENCE_END\n\n" +
                    "2. Emit multiple such blocks to call several tools at once.\n" +
                    "3. Argument names/types must match the definitions above.\n" +
                    "4. If no tool is needed, answer normally and emit NO code block.\n" +
                    "5. Never output tool-call-like text outside the code block.\n" +
                    "6. The tool name MUST be copied verbatim from the list above. " +
                    "Do not rewrite, add or remove prefixes, or guess. " +
                    "A wrong name will fail.\n"
            },
        )
        return sb.toString()
    }

    /** 从 tool_choice 里取出被强制指定的函数名（仅对象形式） */
    fun forcedFunctionName(toolChoice: Any?): String? {
        val o = toolChoice as? JSONObject ?: return null
        if (o.optString("type") != "function") return null
        return o.optJSONObject("function")?.optString("name")?.takeIf { it.isNotEmpty() }
    }

    /** tool_choice 是否为 "required"（要求必须调用某个工具） */
    fun isRequired(toolChoice: Any?): Boolean =
        (toolChoice as? String)?.lowercase() == "required"

    /**
     * 从模型输出里解析出所有工具调用，并返回移除这些块之后的剩余正文。
     *
     * 设计为**容错优先**：任何一块解析失败都跳过它而不是整体失败，
     * 避免模型偶尔格式跑偏导致整个请求报错。
     */
    fun parse(text: String): ParseResult {
        if (text.isEmpty()) return ParseResult(emptyList(), text, false)

        val calls = ArrayList<ParsedCall>()
        val sb = StringBuilder()
        var idx = 0
        var sawOpenFence = false

        while (idx < text.length) {
            val start = text.indexOf(FENCE, idx)
            if (start < 0) {
                sb.append(text, idx, text.length)
                break
            }
            sawOpenFence = true
            // 输出围栏之前的内容
            sb.append(text, idx, start)

            // 找结束围栏
            val bodyStart = start + FENCE.length
            val end = text.indexOf(FENCE_END, bodyStart)
            if (end < 0) {
                // 未闭合：把剩余内容当作正文（流式未结束时可能出现，由调用方决定是否继续缓冲）
                sb.append(text, start, text.length)
                break
            }
            val jsonText = text.substring(bodyStart, end).trim()
            parseCallJson(jsonText)?.let { calls.add(it) }
            idx = end + FENCE_END.length
        }

        return ParseResult(calls, sb.toString(), sawOpenFence)
    }

    /**
     * 解析单个工具调用 JSON。
     * 兼容多种形态：
     *  - {"name":..,"arguments":{...}}
     *  - {"name":..,"arguments":"{...}"}   （arguments 是字符串）
     *  - {"function":{"name":..,"arguments":..}}（OpenAI 嵌套风格）
     *  - {"tool_calls":[{...}]}            （模型自作主张包了一层）
     */
    private fun parseCallJson(json: String): ParsedCall? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null

        // 形态 4：被包了一层 tool_calls
        val nested = o.optJSONArray("tool_calls")
        if (nested != null && nested.length() > 0) {
            return parseCallJson(nested.optJSONObject(0)?.toString() ?: return null)
        }

        // 形态 3：OpenAI 嵌套
        val fn = o.optJSONObject("function")
        val name = (fn?.optString("name") ?: o.optString("name")).ifEmpty { return null }

        // arguments 可能是对象、字符串或缺失
        val rawArgs: Any? = fn?.opt("arguments") ?: o.opt("arguments")
        val argsJson = normalizeArguments(rawArgs)
        return ParsedCall(name, argsJson)
    }

    /** 把 arguments 归一化成 JSON 字符串（OpenAI 协议里 arguments 是字符串） */
    private fun normalizeArguments(raw: Any?): String = when (raw) {
        null -> "{}"
        is JSONObject -> raw.toString()
        is JSONArray -> raw.toString()
        else -> {
            val s = raw.toString().trim()
            if (s.isEmpty()) {
                "{}"
            } else if (s.startsWith("{") || s.startsWith("[")) {
                // 已是 JSON 文本，规范化一下（解析失败则原样保留）
                runCatching { JSONObject(s).toString() }.getOrDefault(s)
            } else {
                // 裸字符串参数：包成对象，避免下游解析崩溃
                JSONObject().put("value", s).toString()
            }
        }
    }

    /**
     * 判断给定文本是否「可能正在形成」工具调用块。
     * 用于流式场景：当正文尾部出现部分围栏时，先不吐给客户端，等后续数据。
     */
    fun mayBeFormingFence(tail: String): Boolean {
        if (tail.isEmpty()) return false
        // 取 FENCE 的所有前缀，检查 tail 是否以其中之一结尾（忽略大小写差异不做，保持精确）
        val maxCheck = minOf(tail.length, FENCE.length - 1)
        for (n in maxCheck downTo 1) {
            if (tail.endsWith(FENCE.substring(0, n))) return true
        }
        return false
    }

    /**
     * 解析**单个工具调用块的内容**（不含围栏标记本身）。
     *
     * 与 [parse] 的区别：parse 处理完整文本（含成对围栏），
     * 而流式缓冲器已经自己剥掉了围栏，只把内容交给这里。
     *
     * 内容里可能包含多个 JSON（换行分隔），也可能是单个 JSON，
     * 因此按「逐个 JSON 对象」的方式宽松提取。
     */
    fun parseBlock(body: String): ParseResult {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return ParseResult(emptyList(), "", true)

        val calls = ArrayList<ParsedCall>()

        // 优先整体解析（最常见：块内就一个 JSON）
        parseCallJson(trimmed)?.let { calls.add(it) }

        // 整体失败时尝试逐个 JSON 提取（模型可能在一个块里写了多个）
        if (calls.isEmpty()) {
            extractJsonObjects(trimmed).forEach { obj ->
                parseCallJson(obj)?.let { calls.add(it) }
            }
        }

        return ParseResult(calls, "", true)
    }

    /**
     * 从文本里逐个提取顶层 JSON 对象（按花括号配平扫描，跳过字符串内的括号）。
     */
    private fun extractJsonObjects(text: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        var inStr = false
        var escaped = false
        for (i in text.indices) {
            val c = text[i]
            if (inStr) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        out.add(text.substring(start, i + 1))
                        start = -1
                    }
                }
            }
        }
        return out
    }

    /** 一个解析出来的工具调用 */
    data class ParsedCall(val name: String, val argumentsJson: String)

    /**
     * 判断一段正文是否为「本应调用工具但格式跑偏」的迹象。
     *
     * ⚠ 这个判定直接决定是否重试，而重试的代价很大（实测一次重试可让单请求
     * 从 30 秒涨到 180 秒）。因此必须**只在高置信度时才返回 true**。
     *
     * 历史教训：早期版本只要正文出现「工具调用 / 调用工具」就重试，
     * 结果把模型的**正常表述**（如「我需要调用工具来克隆仓库」）误判成格式跑偏，
     * 导致复杂任务（移植项目、多步规划）反复重试，用户感受是「思考很久不开始」。
     *
     * 现在只认两种高置信信号：
     *  A. **裸 JSON 调用形态** —— `{"name":..., "arguments":...}` 出现在正文里，
     *     说明模型想发起调用但没套围栏，几乎不可能看错。
     *  B. **明确的失败陈述** —— 模型在抱怨工具不可用，如「工具不存在 / 无法调用
     *     工具 / 工具未挂载」。是"抱怨"而非"计划"，措辞特征明确。
     *
     * 不再因为单独出现「工具」「调用」等词就重试。
     */
    fun looksLikeMissedToolCall(text: String): Boolean {
        if (text.isEmpty()) return false
        val t = text.lowercase()

        // A. 裸 JSON 调用形态（最强信号）：同时出现 name 与 arguments 字段
        if ((t.contains("\"name\"") || t.contains("\"tool\"")) &&
            (t.contains("\"arguments\"") || t.contains("\"parameters\""))
        ) {
            return true
        }

        // B. 显式的 tool_call 语法残留。
        //    注意：必须是**代码形态**（tool_call 这种带下划线的标识符），
        //    而不是中文「工具调用」这类自然语言表述 —— 后者常见于模型的
        //    「我接下来要调用工具…」计划性叙述，误判会造成无谓重试。
        if (t.contains("tool_call") || t.contains("tool call")) return true

        // C. 明确的失败/抱怨陈述（"做不了"，而非"准备做"）。
        //    要求与「工具/tool/function」共现，避免误伤普通叙述。
        val complaints = listOf(
            "工具不存在", "无法调用", "工具不可用", "调用失败",
            "未挂载", "未启用", "未正确加载", "不可访问", "无法访问",
            "未能成功", "未能响应", "无权限访问",
            "does not exist", "not available", "not found",
            "cannot call", "unable to call", "failed to call",
        )
        if (complaints.any { t.contains(it.lowercase()) }) {
            return t.contains("工具") || t.contains("tool") || t.contains("function")
        }
        return false
    }

    /**
     * @param calls 解析出的工具调用（可能为空）
     * @param remainingText 移除工具调用块后的正文
     * @param sawFence 是否出现过起围栏（即使没解析成功，也说明模型意图调用工具）
     */
    data class ParseResult(
        val calls: List<ParsedCall>,
        val remainingText: String,
        val sawFence: Boolean,
    )

    // ---------------- 把客户端 tools 转成上游可用的透传结构 ----------------

    /**
     * 生成 OpenAI 规范的 tool_call id。
     * 前缀用 "call_" 以兼容多数客户端（LangChain / OpenAI SDK 的校验）。
     */
    fun newToolCallId(): String = "call_" + Util.randomBase64Url(16).take(22)
}
