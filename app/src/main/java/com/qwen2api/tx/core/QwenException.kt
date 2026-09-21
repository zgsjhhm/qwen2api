package com.qwen2api.tx.core

/**
 * 统一业务错误（移植自原项目 lib/util.js 的 QwenError）。
 *
 * @param code   业务错误码，透传给 OpenAI 客户端的 error.code，如 AUTH_FAILED / NO_TOKEN / NETWORK
 * @param message 面向用户的中文可读信息
 * @param status  映射到 HTTP 状态码，默认 502
 */
open class QwenException(
    val code: String,
    override val message: String,
    val status: Int = 502,
) : Exception(message) {
    /**
     * 实际发生的重试次数（0 = 首次即尝试，未重试）。
     *
     * 失败路径上由 [withRetries] 填入；成功路径看 `ImageResult.retries`。
     * 两者的意义相同：让「网关到底重试了没有」变成可观测事实，而不是靠猜。
     */
    open val attempts: Int = 0

    /** 附带重试次数（≤0 时原样返回，避免制造无意义的子类实例） */
    fun withRetries(n: Int): QwenException =
        if (n <= 0 || this is RetryExhausted) this else RetryExhausted(this, n)

    fun toErrorJson(): Map<String, Any> = linkedMapOf(
        "code" to code,
        "message" to message,
        "status" to status,
    )

    /** 转成 OpenAI 风格的 error 对象 */
    fun toOpenAiError(): Map<String, Any> {
        val type = when {
            status == 401 -> "authentication_error"
            status == 400 || status == 413 -> "invalid_request_error"
            status == 404 -> "invalid_request_error"
            else -> "api_error"
        }
        return linkedMapOf(
            "message" to message,
            "type" to type,
            "code" to code,
        )
    }
}

/**
 * 重试耗尽后抛出的异常：**保持原错误码/文案/状态码不变**，只额外带上尝试次数。
 *
 * 为什么不是简单 `throw e`：对外回显的 `retries` 字段需要如实反映「到底打了几次」。
 * 风控场景下这个数字正是判断「要不要先把账号缓一缓」的依据，而它在成功与失败
 * 两条路径上都应当可见。子类化而不是改成包装对象，是为了让所有既有的
 * `catch (e: QwenException)` 分支（错误码归一化、HTTP 状态映射、SSE 错误帧）
 * 一行都不用改。
 *
 * @param original 原始错误（不叫 `cause`：`Throwable.cause` 已是既有成员，
 *                 同名会让子类必须 override 且语义混淆）
 * @param attempts 实际发生的重试次数（0 = 首次即失败）
 */
class RetryExhausted(
    val original: QwenException,
    override val attempts: Int,
) : QwenException(original.code, original.message, original.status)
