package com.qwen2api.tx.server

import com.qwen2api.tx.core.ChatMessage
import com.qwen2api.tx.core.ToolFilter
import com.qwen2api.tx.core.ToolMessageCodec
import com.qwen2api.tx.core.ToolPrompt
import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具调用的请求侧准备与响应侧转换（方案 B）。
 *
 * 上游 chat.qwen.ai 不支持 OpenAI 的 tools 协议（实测会被静默忽略），
 * 因此本网关在服务端模拟：
 *
 *  请求侧：
 *    1. 把 tools 定义翻译成一段系统指令，拼到最后一条 user 消息末尾
 *    2. 把历史里的 assistant.tool_calls / role=tool 转换成上游认识的文本
 *
 *  响应侧：
 *    3. 从模型输出里解析出约定格式的工具调用，转成标准 OpenAI tool_calls
 *    4. 若一条都没解析到，则按普通文本返回（自动降级，不给客户端添麻烦）
 */
object ToolInjector {

    /**
     * 一次请求的工具上下文。
     *
     * @param enabled 本次是否需要工具能力
     * @param tools 原始 tools 定义（用于校验模型返回的工具名是否合法）
     * @param toolChoice 原始 tool_choice
     * @param allowedNames 允许被调用的工具名集合
     */
    data class Context(
        val enabled: Boolean,
        val tools: JSONArray?,
        val toolChoice: Any?,
        val allowedNames: Set<String>,
    ) {
        companion object {
            val DISABLED = Context(false, null, null, emptySet())
        }
    }

    /**
     * 准备请求：原地改写 messages（注入工具指令 + 转换工具历史消息）。
     *
     * 直接改 mutableList 的原因：解析出来的 messages 本来就是这个请求私有的，
     * 避免多一次深拷贝开销。
     */
    fun prepare(messages: MutableList<ChatMessage>, body: JSONObject): Context {
        // 按需注入：先用任务场景过滤掉用不上的工具（详见 ToolFilter 注释）。
        // 过滤是「保守」的 —— 关键词不命中就保持全量，绝不因裁剪导致模型找不到工具。
        val rawTools = body.optJSONArray("tools")
        val topTools = rawTools?.let { ToolFilter.filter(it, messages) }
        val tools = topTools

        // 诊断日志：记录每次过滤的判定与累计统计，便于观察真实场景表现
        runCatching {
            android.util.Log.i(
                "Qwen2API",
                "tool filter: ${ToolFilter.lastBefore}->${ToolFilter.lastAfter} " +
                    "${ToolFilter.lastReason} | 累计: ${ToolFilter.stats()}",
            )
        }
        val toolChoice: Any? = body.opt("tool_choice")
        val hasTools = tools != null && tools.length() > 0
        val hasHistory = ToolMessageCodec.hasToolHistory(messages)

        // 没有本次工具定义、也没有工具历史 -> 完全走原逻辑，零开销
        if (!hasTools && !hasHistory) return Context.DISABLED

        val allowed = HashSet<String>()
        if (tools != null) {
            for (i in 0 until tools.length()) {
                val fn = tools.optJSONObject(i)?.optJSONObject("function") ?: continue
                fn.optString("name").takeIf { it.isNotEmpty() }?.let { allowed.add(it) }
            }
        }

        val instructions = ToolPrompt.buildInstructions(tools, toolChoice)

        // 1) 转换工具相关历史消息为上游可读文本
        val converted = ArrayList<ChatMessage>(messages.size)
        for (msg in messages) {
            val rendered = ToolMessageCodec.renderForUpstream(msg)
            if (rendered != null) {
                // 工具消息统一降级为 user 角色（上游只认 user/assistant）
                val role = if (msg.role == "assistant") "assistant" else "user"
                converted.add(ChatMessage(role, rendered))
            } else {
                converted.add(msg)
            }
        }

        // 2) 注入工具指令：追加到最后一条 user 消息，而不是单开 system，
        //    因为上游对 system 角色支持不稳定，混在 user 里最可靠。
        if (instructions != null) {
            val lastUserIdx = converted.indexOfLast { it.role == "user" }
            if (lastUserIdx >= 0) {
                val target = converted[lastUserIdx]
                val merged = buildString {
                    append(target.textContent())
                    append("\n\n---\n\n")
                    append(instructions)
                }
                converted[lastUserIdx] = ChatMessage("user", merged)
            } else {
                // 没有 user 消息（少见）：补一条承载指令
                converted.add(ChatMessage("user", instructions))
            }
        }

        messages.clear()
        messages.addAll(converted)

        return Context(
            enabled = instructions != null || hasHistory,
            tools = tools,
            toolChoice = toolChoice,
            allowedNames = allowed,
        )
    }

    /**
     * 判断模型返回的工具调用是否合法，并把名字**校正**为客户端注册的真实名字。
     *
     * 为什么需要校正：模型经常不确定该不该带前缀（例如注册名是
     * `mcp__MTmcp__mt_file_list`，模型却输出 `mt_file_list`）。
     * 直接丢弃会让用户看到「Tool xxx does not exists」，
     * 因此这里做三级匹配：
     *   1. 精确匹配
     *   2. 忽略大小写匹配
     *   3. 去前缀后缀匹配（唯一命中才认，避免歧义）
     * 命中后统一替换成注册名，保证客户端能查到。
     */
    fun filterValid(calls: List<ToolPrompt.ParsedCall>, ctx: Context): List<ToolPrompt.ParsedCall> {
        if (calls.isEmpty()) return calls
        // 没有允许列表（纯历史场景）时不校验
        if (ctx.allowedNames.isEmpty()) return calls

        return calls.mapNotNull { call ->
            resolveName(call.name, ctx.allowedNames)?.let { real ->
                if (real == call.name) call else call.copy(name = real)
            }
        }
    }

    /**
     * 把模型输出的名字解析成注册表里的真实名字。
     *
     * @return null 表示无法唯一确定（应当丢弃，避免调用错工具）
     */
    fun resolveName(raw: String, allowed: Set<String>): String? {
        if (raw.isEmpty()) return null
        // 1) 精确
        if (raw in allowed) return raw
        // 2) 忽略大小写
        allowed.firstOrNull { it.equals(raw, ignoreCase = true) }?.let { return it }
        // 3) 去前缀匹配：取最后一段（按 __ 或 _ 切分后比对尾部）
        val tail = raw.substringAfterLast("__").ifEmpty { raw }
        val tailMatches = allowed.filter { a ->
            val at = a.substringAfterLast("__").ifEmpty { a }
            at == tail || at.equals(tail, ignoreCase = true)
        }
        if (tailMatches.size == 1) return tailMatches[0]
        // 4) 双向包含匹配：注册名以 raw 结尾，或 raw 以注册名结尾（唯一时才认）
        val suffixMatches = allowed.filter { a ->
            a.endsWith("__$raw") || a.endsWith("_$raw") || a == raw ||
                raw.endsWith("__$a") || raw.endsWith("_$a")
        }
        if (suffixMatches.size == 1) return suffixMatches[0]
        return null
    }

    /** 把解析结果转成 OpenAI tool_calls 数组（id 由调用方生成，保证可追溯） */
    fun toToolCalls(calls: List<ToolPrompt.ParsedCall>): JSONArray =
        ToolMessageCodec.toToolCalls(calls) { ToolPrompt.newToolCallId() }
}
