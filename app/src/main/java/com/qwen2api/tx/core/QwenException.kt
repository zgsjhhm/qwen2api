package com.qwen2api.tx.core

/**
 * 统一业务错误（移植自原项目 lib/util.js 的 QwenError）。
 *
 * @param code   业务错误码，透传给 OpenAI 客户端的 error.code，如 AUTH_FAILED / NO_TOKEN / NETWORK
 * @param message 面向用户的中文可读信息
 * @param status  映射到 HTTP 状态码，默认 502
 */
class QwenException(
    val code: String,
    override val message: String,
    val status: Int = 502,
) : Exception(message) {
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
