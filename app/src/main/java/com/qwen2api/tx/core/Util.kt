package com.qwen2api.tx.core

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/** 通用工具（移植自 lib/util.js） */
object Util {

    fun uuid(): String = UUID.randomUUID().toString()

    fun nowSec(): Long = System.currentTimeMillis() / 1000L

    fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        java.security.SecureRandom().nextBytes(b)
        val sb = StringBuilder(bytes * 2)
        for (x in b) sb.append(String.format("%02x", x))
        return sb.toString()
    }

    /** RFC4648 base64url 无填充（对应 Node 的 base64url 编码） */
    fun base64Url(bytes: ByteArray): String = B64.encodeUrlNoPad(bytes)

    fun randomBase64Url(bytes: Int): String {
        val b = ByteArray(bytes)
        java.security.SecureRandom().nextBytes(b)
        return base64Url(b)
    }

    /** HMAC-SHA1 -> base64（OSS 签名用） */
    fun hmacSha1Base64(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return B64.encode(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
    }

    fun sha1Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        val d = md.digest(data)
        val sb = StringBuilder()
        for (x in d) sb.append(String.format("%02x", x))
        return sb.toString()
    }

    /** HTTP Date 头（RFC1123，必须固定 GMT 英语区域，否则中文环境会生成非法头值） */
    fun httpDate(): String {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT")
        return fmt.format(Date())
    }

    /**
     * 时区头：必须纯 ASCII。
     * 原项目踩过的坑：中文 Windows 上 Date.toString() 含"中国标准时间"，导致 undici 抛 ByteString。
     * 这里直接手工计算 GMT 偏移，天然全 ASCII。
     */
    fun asciiTimezoneHeader(): String {
        val offMin = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
        val sign = if (offMin >= 0) "+" else "-"
        val absMin = abs(offMin)
        return "GMT$sign${"%02d".format(absMin / 60)}:${"%02d".format(absMin % 60)}"
    }

    /** 任意值安全转字符串：对象转 JSON（截断），根治 "[object Object]" */
    fun safeStr(v: Any?, limit: Int = 400): String {
        if (v == null) return ""
        if (v is String) return v
        return try {
            val s = Json.encode(v)
            if (s.length > limit) s.substring(0, limit) + "...(已截断)" else s
        } catch (e: Exception) {
            "[无法序列化: ${e.message}]"
        }
    }

    /** 错误归一化：保证 code/message/status 均非空 */
    fun normalizeError(e: Throwable): Triple<String, String, Int> {
        return if (e is QwenException) {
            Triple(e.code, e.message, e.status)
        } else {
            val msg = e.message?.takeIf { it.isNotBlank() }
                ?: e.cause?.message
                ?: "未知错误"
            Triple("UPSTREAM_ERROR", safeStr(msg), 502)
        }
    }

    /**
     * 网络异常的友好中文描述（移植自 describeFetchError）。
     * Android 侧异常体系与 Node 不同，按类型与消息特征归类。
     */
    fun describeNetworkError(e: Throwable): String {
        val base = e.message ?: e::class.java.simpleName
        val lower = base.lowercase(Locale.US)
        val hint = when {
            e is UnknownHostException -> "(DNS 解析失败: 域名无法解析, 检查网络/DNS, 可尝试切换手机热点验证)"
            lower.contains("econnrefused") || lower.contains("connection refused") ->
                "(连接被拒绝: 常见于本机代理软件已关闭但系统代理仍指向它, 或防火墙拦截)"
            lower.contains("reset") || lower.contains("econnreset") || lower.contains("epipe") ->
                "(连接被重置: 网络中途断开, 常见于代理拦截或网络限制)"
            e is SocketTimeoutException || lower.contains("timeout") || lower.contains("timed out") ->
                "(连接超时: 网络不通或响应过慢, 检查网络与代理设置)"
            lower.contains("cert") || lower.contains("ssl") || lower.contains("tls") ->
                "(TLS 证书校验失败: 检查系统时间是否正确, 安全软件是否拦截 HTTPS)"
            !isNetAvailable() -> "(当前设备无可用网络: 请检查 Wi-Fi / 移动数据是否已连接)"
            else -> ""
        }
        return if (hint.isEmpty()) base else "$base $hint"
    }

    private fun isNetAvailable(): Boolean = try {
        val addrs = java.net.NetworkInterface.getNetworkInterfaces()
        var up = false
        for (ni in addrs) {
            if (ni.isUp && !ni.isLoopback) { up = true; break }
        }
        up
    } catch (e: Exception) {
        true
    }
}
