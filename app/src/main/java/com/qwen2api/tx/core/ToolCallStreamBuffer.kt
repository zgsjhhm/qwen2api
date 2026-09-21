package com.qwen2api.tx.core

/**
 * 流式响应下，工具调用块的缓冲与增量分发。
 *
 * ## 为什么需要它
 * 我们靠「模型输出特定格式的文本块」来模拟工具调用。流式场景下，
 * token 是一个个到的，可能出现这些麻烦：
 *
 *  - 围栏标记 ```` ```tool_call ```` 被拆成多次到达（"```to" + "ol_call"）
 *  - 工具调用 JSON 只到了一半，此时解析必然失败
 *  - 正文里可能正好出现三个反引号（正常代码块），容易误判
 *
 * 因此不能收到 token 就直接转发给客户端，必须**缓冲判断**：
 *  1. 正文部分：安全时尽快 flush，让用户看到逐字输出
 *  2. 一旦疑似进入工具调用区：停止 flush，全部缓冲，直到围栏闭合
 *  3. 围栏闭合后：解析出工具调用，转为 tool_calls 分片发给客户端
 *
 * ## 状态机
 * ```
 *  TEXT  ──遇到 FENCE 前缀──▶  MAYBE_FENCE
 *    ▲                            │ 确认是完整 FENCE
 *    │ 不是前缀                    ▼
 *    └──────────────────────  IN_FENCE ──遇到结束围栏──▶ TEXT
 * ```
 */
class ToolCallStreamBuffer {

    private enum class State { TEXT, MAYBE_FENCE, IN_FENCE }

    private var state = State.TEXT

    /** 待确认的文本缓冲（MAYBE_FENCE 状态下累积） */
    private val pending = StringBuilder()

    /** 已进入围栏后累积的 JSON 文本 */
    private val fenceBody = StringBuilder()

    /** 本次流是否解析出过工具调用 */
    var sawToolCall: Boolean = false
        private set

    /** 解析出的工具调用（按出现顺序） */
    val calls = ArrayList<ToolPrompt.ParsedCall>()

    /** 是否存在「出现围栏但解析失败」的情况（用于降级判断） */
    var hadMalformedFence: Boolean = false
        private set

    /** 从输入中抽取出来的纯正文，调用方据此增量发送 content */
    private val textOut = StringBuilder()

    /**
     * 送入一段新到达的文本。
     *
     * @return 本次可以安全下发给客户端的**正文**增量（可能为空字符串）
     */
    fun feed(chunk: String): String {
        if (chunk.isEmpty()) return ""
        textOut.setLength(0)
        for (ch in chunk) {
            when (state) {
                State.TEXT -> handleText(ch)
                State.MAYBE_FENCE -> handleMaybeFence(ch)
                State.IN_FENCE -> handleInFence(ch)
            }
        }
        return textOut.toString()
    }

    private fun handleText(ch: Char) {
        // 正文状态下，只有可能开启围栏才有必要缓冲
        if (ch == '`') {
            state = State.MAYBE_FENCE
            pending.setLength(0)
            pending.append(ch)
        } else {
            textOut.append(ch)
        }
    }

    private fun handleMaybeFence(ch: Char) {
        pending.append(ch)
        val p = pending.toString()

        when {
            // 正好凑成完整围栏 -> 进入围栏
            p == ToolPrompt.FENCE -> {
                state = State.IN_FENCE
                fenceBody.setLength(0)
                entriesCount++
            }
            // 仍然是围栏的前缀 -> 继续等
            ToolPrompt.FENCE.startsWith(p) -> {
                // 继续缓冲
            }
            // 不是围栏 -> 之前缓冲的都是普通正文，下发出去
            else -> {
                textOut.append(p)
                pending.setLength(0)
                state = State.TEXT
            }
        }
    }

    /** 已遇到的围栏数量（含未闭合的） */
    var entriesCount: Int = 0
        private set

    private fun handleInFence(ch: Char) {
        fenceBody.append(ch)
        // 检查是否出现结束围栏
        if (fenceBody.length >= 3 &&
            fenceBody.endsWith(ToolPrompt.FENCE_END)
        ) {
            val body = fenceBody.substring(0, fenceBody.length - ToolPrompt.FENCE_END.length)
            // 注意：不能用 ToolPrompt.parse(FENCE + body) —— parse 需要成对围栏才解析，
            // 这里只有内容，应走 parseBlock 直接解析块内容。
            val parsed = ToolPrompt.parseBlock(body)
            if (parsed.calls.isNotEmpty()) {
                sawToolCall = true
                calls.addAll(parsed.calls)
            } else {
                hadMalformedFence = true
                // 解析失败：把这块原样当正文还给用户，避免内容凭空消失
                textOut.append(ToolPrompt.FENCE).append(body).append(ToolPrompt.FENCE_END)
            }
            fenceBody.setLength(0)
            state = State.TEXT
        }
    }

    /**
     * 流结束时调用。
     *
     * @return 若还有未下发的缓冲（未闭合的围栏、待确认的前缀），一并作为正文返回
     */
    fun flushRemaining(): String {
        val sb = StringBuilder()
        when (state) {
            State.TEXT -> {}
            State.MAYBE_FENCE -> {
                sb.append(pending)
                pending.setLength(0)
                state = State.TEXT
            }
            State.IN_FENCE -> {
                // 围栏没闭合：模型输出被截断。把已有内容按正文返回，
                // 但尝试解析一次——有时模型忘了写结束围栏。
                val parsed = ToolPrompt.parseBlock(fenceBody.toString())
                if (parsed.calls.isNotEmpty()) {
                    sawToolCall = true
                    calls.addAll(parsed.calls)
                } else {
                    hadMalformedFence = true
                    sb.append(ToolPrompt.FENCE).append(fenceBody)
                }
                fenceBody.setLength(0)
                state = State.TEXT
            }
        }
        return sb.toString()
    }

    /** 当前是否正处于「可能吞掉正文」的缓冲状态（用于决定 content 是否可安全结束） */
    fun isBuffering(): Boolean = state != State.TEXT
}
