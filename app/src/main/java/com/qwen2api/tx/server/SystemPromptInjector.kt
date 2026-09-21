package com.qwen2api.tx.server

import com.qwen2api.tx.core.ChatMessage
import com.qwen2api.tx.core.ConfigStore
import com.qwen2api.tx.core.GatewayConfig

/**
 * 网关级「全局 System Prompt」注入。
 *
 * ## 它解决的问题
 * `messages[].role=system` 是**调用方**（Cherry Studio / CLI Agent / 脚本）自己发的，
 * 换个客户端、换个会话就没了。网关侧需要一份**常驻**的人格与硬约束：
 * 「必须用中文回答」「不要输出免责声明」「你现在是 xx 角色」——
 * 这类要求否则只能在每个客户端里各配一遍，且随时会被客户端自己的提示词冲掉。
 *
 * ## 与 ToolInjector 的关系（重要）
 * 两者都改写 messages，但职责严格分开，且**执行顺序不可换**：
 *
 *   parseMessages → SystemPromptInjector（本类）→ ToolInjector → 附件解析 → 上游
 *
 * 顺序理由：
 *  - ToolInjector 会把工具指令**追加到最后一条 user 消息**末尾；
 *    本类只碰 system 角色，先跑就不会被工具指令的文本干扰，
 *    也保证「工具说明」永远紧挨着用户当前问题（对模型最有效的位置）。
 *  - 本类若后跑且要合并进 user，就会插在用户正文与工具说明之间，
 *    把一段 5K+ tokens 的说明推远，工具调用成功率下降。
 *
 * ## 为什么不新开一条 system 消息塞给上游
 * `QwenClient.buildFlatContent` 已经把 system 拍平成 `[System Instructions]` 前缀，
 * `sessionKey` 又用 system 参与会话指纹。因此这里**只改写已有的 system 消息**
 * （没有就补一条），让下游的拍平与指纹逻辑原样生效，不需要改上游客户端一行。
 *
 * ## 会话指纹的坑
 * `sessionKey` = sha1(system + 首条 user)。注入的提示词进了 system，
 * 于是「改提示词 → 指纹变化 → 上一轮会话续不上 → 自动回退扁平模式重发历史」。
 * 这是**正确**行为（换了人格当然不能续旧会话），但会多一次上行请求。
 * 所以本类不做任何隐藏的稳定化处理（比如给注入文本加固定盐）——
 * 那会让「改完提示词但模型行为还是旧的」这种问题变得无法解释。
 */
object SystemPromptInjector {

    /**
     * 注入结果，用于日志与测试断言。
     *
     * @param applied 本次是否真的注入了内容
     * @param mode 实际生效的模式（merge / replace / 空串表示未注入）
     * @param replacedChars 被覆盖掉的调用方 system 字符数（仅 replace 模式非 0）
     * @param injectedChars 注入的提示词字符数
     */
    data class Result(
        val applied: Boolean,
        val mode: String,
        val replacedChars: Int,
        val injectedChars: Int,
    ) {
        companion object {
            val NONE = Result(false, "", 0, 0)
        }
    }

    /**
     * 原地改写 messages，把全局 System Prompt 注入为 system 消息。
     *
     * @param messages 请求私有的可变列表（与 ToolInjector 同样就地改，省一次深拷贝）
     * @param cfg 当前配置；未启用或文本为空时直接返回 [Result.NONE]，零副作用
     */
    fun prepare(messages: MutableList<ChatMessage>, cfg: GatewayConfig): Result {
        if (!cfg.systemPromptEnabled) return Result.NONE

        val prompt = ConfigStore.sanitizeSystemPrompt(cfg.systemPrompt)
        if (prompt.isEmpty()) return Result.NONE

        val mode = ConfigStore.normalizeSystemPromptMode(cfg.systemPromptMode)

        // 调用方已发的 system：可能多条（少见，但有的 SDK 会拆成多条发）
        val systemIdxs = messages.indices.filter { messages[it].role == "system" }

        if (systemIdxs.isEmpty()) {
            // 没有任何 system：插到最前面。上游拍平后成为 [System Instructions] 前缀，
            // 位置在历史与当前问题之前，符合 system 的语义权重。
            messages.add(0, ChatMessage("system", prompt))
            return Result(true, mode, replacedChars = 0, injectedChars = prompt.length)
        }

        return if (mode == GatewayConfig.SYSTEM_PROMPT_REPLACE) {
            // 覆盖模式：只保留第一条 system 承载全局提示词，其余清空删除。
            // 不清空内容而是删掉多余条目，避免上游看到多条空 system。
            val replacedChars = systemIdxs.sumOf { messages[it].textContent().length }
            messages[systemIdxs.first()] = ChatMessage("system", prompt)
            // 从后往前删，避免索引位移
            systemIdxs.drop(1).reversed().forEach { messages.removeAt(it) }
            Result(true, mode, replacedChars = replacedChars, injectedChars = prompt.length)
        } else {
            // 合并模式：全局提示词在前，调用方提示词在后。
            // 顺序不是随便定的 —— 后出现的指令对模型约束更强，调用方的是「本次请求
            // 的具体要求」（如输出 JSON schema），让它压过常驻人格，才符合调用方预期。
            val idx = systemIdxs.first()
            val parts = ArrayList<String>()
            parts.add(prompt)
            // 调用方可能有多条 system（有的 SDK 会拆开发），全部按原顺序并入同一条，
            // 保证拍平后 upstream 只看到一个 [System Instructions] 区块。
            systemIdxs.forEach { i ->
                messages[i].textContent().takeIf { it.isNotEmpty() }?.let { parts.add(it) }
            }
            // 从后往前删掉多余的 system（保留第一条作为载体），避免索引位移
            systemIdxs.drop(1).reversed().forEach { messages.removeAt(it) }
            messages[idx] = ChatMessage("system", parts.joinToString("\n\n"))
            Result(true, mode, replacedChars = 0, injectedChars = prompt.length)
        }
    }

    /**
     * 计算注入后的会话指纹输入（仅供测试与诊断使用）。
     *
     * 单独暴露的原因：会话续接失效是个**静默**故障 —— 表现为「聊得越久越慢」，
     * 不会有任何报错。有了这个函数，测试可以直接断言
     * 「改提示词必然改指纹」，把这个语义钉死。
     */
    fun fingerprintSeed(messages: List<ChatMessage>): String =
        messages.filter { it.role == "system" }
            .joinToString("\n") { it.textContent() }
}
