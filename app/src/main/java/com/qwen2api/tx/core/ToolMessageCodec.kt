package com.qwen2api.tx.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 多轮工具对话的消息转换。
 *
 * ## 问题
 * OpenAI 协议里，工具调用会产生这几类消息：
 *  - assistant 带 tool_calls（模型发起的调用）
 *  - role = "tool" + tool_call_id（客户端回传的执行结果）
 *
 * 但上游 chat.qwen.ai **不认识**这些角色/字段，它只接受 user/assistant 的纯文本。
 * 所以必须在这里做「协议翻译」：
 *  - assistant.tool_calls  -> 还原成约定的 fences 文本，让模型看到自己上次的调用
 *  - role=tool 的结果      -> 转成 user 消息，并明确标注"这是工具返回结果"
 *
 * ## 为什么保留 fence 原文
 * 模型是靠这个格式学会调用工具的。如果历史里把 tool_calls 转成自然语言，
 * 模型会逐渐"忘记"格式约定，多轮之后就不按格式输出了。
 * 因此这里刻意**还原成完全一致的 fence 格式**，保持 few-shot 效果。
 */
object ToolMessageCodec {

    /**
     * 把 OpenAI 风格的工具相关消息，转换成上游能吃的普通文本。
     *
     * @return 转换后的文本内容；不属于工具消息时返回 null（调用方按原样处理）
     */
    fun renderForUpstream(msg: ChatMessage): String? {
        when (msg.role) {
            "tool" -> {
                val name = msg.name?.takeIf { it.isNotEmpty() } ?: "unknown"
                val raw = msg.textContent()

                // 优先处理 MCP 结构化错误：还原「缺哪个参数 / 能否重试」，
                // 避免模型把它误当成工具不可用而彻底放弃。
                renderStructuredToolError(raw)?.let { return it }

                if (isToolNotFoundError(raw)) {
                    // 客户端报「工具不存在」时，绝不能把这句话原样喂给下游模型 ——
                    // 实测模型会把它当成事实，之后彻底放弃调用工具，
                    // 并在正文里复读该错误（一次失败会污染整段对话）。
                    // 这里改写成中性的重试提示，把上下文拉回正轨。
                    return buildString {
                        append("[工具执行结果]\n")
                        append("工具名: ").append(name).append('\n')
                        append("返回内容: 上一次调用未成功（客户端未能识别该工具名）。\n")
                        append("请重新按约定格式调用一次，")
                        append("工具名必须逐字复制自可用工具列表。")
                    }
                }
                val body = raw.ifEmpty { "{}" }
                return buildString {
                    append("[工具执行结果]\n")
                    append("工具名: ").append(name).append('\n')
                    msg.toolCallId?.takeIf { it.isNotEmpty() }?.let {
                        append("call_id: ").append(it).append('\n')
                    }
                    append("返回内容:\n").append(body).append('\n')
                    append("请基于以上结果继续完成任务；若还需调用其他工具，继续按约定格式输出。")
                }
            }
            "assistant" -> {
                val calls = msg.toolCalls ?: return null
                if (calls.length() == 0) return null
                // 还原成 fence 文本，保持格式一致性（few-shot 效果）
                val sb = StringBuilder()
                val text = msg.textContent()
                if (text.isNotEmpty()) sb.append(text).append('\n')
                for (i in 0 until calls.length()) {
                    val c = calls.optJSONObject(i) ?: continue
                    val fn = c.optJSONObject("function") ?: c
                    val name = fn.optString("name")
                    if (name.isEmpty()) continue
                    val args = fn.opt("arguments")
                    val argsJson = when (args) {
                        null -> "{}"
                        is JSONObject -> args.toString()
                        is JSONArray -> args.toString()
                        else -> args.toString().ifBlank { "{}" }
                    }
                    sb.append(ToolPrompt.FENCE).append('\n')
                    sb.append(
                        JSONObject().put("name", name).put("arguments", argsObjOf(argsJson)),
                    ).append('\n')
                    sb.append(ToolPrompt.FENCE_END).append('\n')
                }
                return sb.toString().trimEnd()
            }
        }
        return null
    }

    /** arguments 字符串 -> JSONObject（失败则包成 {"value":...}，保证不崩） */
    private fun argsObjOf(argsJson: String): Any =
        runCatching { JSONObject(argsJson) }.getOrElse {
            runCatching { JSONArray(argsJson) }.getOrElse { argsJson }
        }

    /**
     * 判断一段工具返回内容是否为「工具不存在」类错误。
     *
     * 这类错误来自客户端，各客户端措辞不一，这里覆盖常见几种：
     *  - Tool xxx does not exists.
     *  - Tool xxx not found / 工具不存在 / No such tool
     *
     * 之所以要单独识别：实测把这句话原样传给下游模型后，
     * 模型会放弃调用工具并复读该错误，一次失败导致整段对话失效。
     */
    fun isToolNotFoundError(text: String): Boolean {
        if (text.isEmpty()) return false
        val t = text.lowercase()
        if (t.contains("does not exist") || t.contains("doesn't exist")) return true
        if (t.contains("tool not found") || t.contains("no such tool")) return true
        if (text.contains("工具不存在") || text.contains("未找到工具")) return true
        // 形如 "Tool xxx ... exist" 的松散匹配
        if (t.contains("tool ") && t.contains("exist")) return true
        return false
    }

    /**
     * 把下游工具返回的结构化错误，转成模型能看懂、能据以纠正的提示。
     *
     * 背景：MTMCP 等 MCP server 会返回结构化错误，例如
     * ```
     * {"ok":false,"error":{"code":"INVALID_ARGUMENT",
     *   "message":"Missing parameters: editSessionId, view",
     *   "recoverable":true,"allowedValues":["editSessionId","view"]}}
     * ```
     * 但客户端（如 RikkaHub）可能把它压扁成一句「工具不存在」，
     * 导致模型误判为工具不可用而彻底放弃。
     * 这里主动识别这种结构化错误，还原出「缺哪个参数、能否重试」，
     * 引导模型修正参数后重试。
     *
     * @return 改写后的提示文本；不是结构化错误时返回 null
     */
    fun renderStructuredToolError(raw: String): String? {
        if (raw.isEmpty() || !raw.contains("\"ok\"")) return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (o.optBoolean("ok", true)) return null
        val err = o.optJSONObject("error") ?: return null

        val code = err.optString("code")
        val message = err.optString("message")
        val recoverable = err.optBoolean("recoverable", false)
        val sameArgsOk = err.optBoolean("retrySameArguments", true)
        val allowed = err.optJSONArray("allowedValues")

        val sb = StringBuilder()
        sb.append("[工具执行结果]\n")
        sb.append("执行失败（").append(code.ifEmpty { "ERROR" }).append("）")
        if (message.isNotEmpty()) sb.append(": ").append(message)
        sb.append('\n')
        if (allowed != null && allowed.length() > 0) {
            val vals = ArrayList<String>()
            for (i in 0 until minOf(allowed.length(), 12)) vals.add(allowed.optString(i))
            if (vals.isNotEmpty()) {
                sb.append("需要补全的参数: ").append(vals.joinToString(", ")).append('\n')
            }
        }
        if (recoverable) {
            sb.append(
                if (sameArgsOk) {
                    "这是可恢复错误，可以重试。\n"
                } else {
                    "这是可恢复错误，请**修改参数后重新调用**，不要用完全相同的参数重试。\n"
                },
            )
        }
        sb.append("若需继续，请按上面的说明调整参数后重新调用该工具。")
        return sb.toString()
    }

    /**
     * 把一串 ParsedCall 转成 OpenAI 规范的 tool_calls 数组。
     */
    fun toToolCalls(calls: List<ToolPrompt.ParsedCall>, idProvider: () -> String): JSONArray {
        val arr = JSONArray()
        calls.forEach { c ->
            val fn = JSONObject()
                .put("name", c.name)
                .put("arguments", c.argumentsJson)
            arr.put(
                JSONObject()
                    .put("id", idProvider())
                    .put("type", "function")
                    .put("function", fn),
            )
        }
        return arr
    }

    /**
     * 判断一批消息里是否含工具相关内容（决定是否需要注入工具指令）。
     * 只要历史里出现过 tool 消息，即使本次请求没传 tools，也该保留格式约定。
     */
    fun hasToolHistory(messages: List<ChatMessage>): Boolean =
        messages.any { it.role == "tool" || (it.toolCalls?.length() ?: 0) > 0 }
}
