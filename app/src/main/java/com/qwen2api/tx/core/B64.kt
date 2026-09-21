package com.qwen2api.tx.core

import java.util.Base64 as JBase64

/**
 * Base64 编解码门面。
 *
 * 为什么不直接用 android.util.Base64：
 *  - 它的桩实现导致纯 JVM 单元测试无法运行（Method not mocked），
 *    而 GatewayRouter / 附件解析 / 签名逻辑都依赖它；
 *  - java.util.Base64 自 API 26 起可用，行为与 Android 实现一致，
 *    且能在 JVM 与 Android 上同时工作，因此统一走这里。
 */
object B64 {

    fun encodeUrlNoPad(bytes: ByteArray): String =
        JBase64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun encode(bytes: ByteArray): String =
        JBase64.getEncoder().encodeToString(bytes)

    /**
     * 解码 base64（自动兼容标准 / URL-safe / 带换行的 MIME 变体）。
     *
     * 【为什么最后要补一层严格校验】
     * `java.util.Base64` 的解码器族**静默忽略非法字符**：
     * `getMimeDecoder().decode("!!!!")` 不抛异常，而是返回空数组。
     * 后果是本该报「内容损坏」的输入被当成「合法的空内容」继续往下走，
     * 现场只能看到后面的兜底报错（"附件内容为空"），用户拿到的原因指错了方向。
     *
     * 因此这里保留原本的容错兼容能力，但把「解出来是空的、而输入里确有非空白内容」
     * 判定为非法输入 —— 合法的 base64 空串只可能是空白/填充字符。
     */
    fun decode(s: String): ByteArray {
        val text = s.trim()
        val raw = runCatching {
            JBase64.getDecoder().decode(text)
        }.recoverCatching {
            // 容错：URL-safe 变体或带换行/空格的 base64
            JBase64.getMimeDecoder().decode(text)
        }.recoverCatching {
            JBase64.getUrlDecoder().decode(text)
        }.getOrElse { throw IllegalArgumentException("invalid base64") }
        if (raw.isEmpty() && text.any { !it.isWhitespace() && it != '=' }) {
            throw IllegalArgumentException("invalid base64")
        }
        return raw
    }
}
