package com.qwen2api.tx.server

import com.qwen2api.tx.core.AttachmentKind
import com.qwen2api.tx.core.B64
import com.qwen2api.tx.core.ChatMessage
import com.qwen2api.tx.core.FileRecord
import com.qwen2api.tx.core.FileStore
import com.qwen2api.tx.core.Json
import com.qwen2api.tx.core.QwenClient
import com.qwen2api.tx.core.QwenException
import com.qwen2api.tx.core.Util
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit


/**
 * 附件解析与上传（移植自 lib/server.js 的 resolveAttachments / loadAttachmentBytes）。
 *
 * 支持 OpenAI 多模态 content 数组：
 *  text / image_url / video_url / input_audio / file(file_id|file_data)
 * 二进制部分自动上传 Qwen，返回 files[] 条目数组；各消息 content 原地扁平化为纯文本。
 */
object AttachmentResolver {

    data class Loaded(val bytes: ByteArray, val mime: String)

    /** 允许访问的 Qwen/阿里云附件域名后缀（含 CDN） */
    private val ALLOWED_HOST_SUFFIXES = listOf(
        "qwen.ai",
        "aliyuncs.com",
        "aliyun.com",
        "qianwen.com",
        "tongyi.aliyun.com",
    )

    /**
     * 远程 URL 白名单校验（SSRF 防护）。
     *
     * 背景：`image_url` / `video_url` / `file.file_data` 里的 URL 直接来自调用方
     * （本地 CLI、局域网里的其它机器），此前实现会无条件代拉。
     * 在「允许局域网设备访问」(绑定 0.0.0.0) 模式下，等于给同网段任意主机
     * 提供了一个能读本机内网服务的 HTTP 代理（内网探测 / 元数据接口读取）。
     *
     * 策略：
     *  1. 只允许 https（附件直链没有走明文 HTTP 的必要）；
     *  2. host 必须在 Qwen/阿里云域名后缀内；
     *  3. 解析后任一 IP 落在内网/回环/链路本地段即拒绝（防 DNS 指向内网）；
     *  4. 重定向逐跳复用同一套校验（OkHttp 不会替我们检查跳转目标）。
     */
    /** 域名白名单判定（抽出来便于单测，无 DNS 依赖） */
    internal fun isHostAllowed(host: String): Boolean {
        val h = host.lowercase()
        return ALLOWED_HOST_SUFFIXES.any { h == it || h.endsWith(".$it") }
    }

    internal fun assertRemoteUrlAllowed(raw: String, ctx: String): List<java.net.InetAddress> {
        val uri = try {
            java.net.URI(raw)
        } catch (e: Exception) {
            throw QwenException("BAD_REQUEST", "$ctx: URL 无法解析", 400)
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw QwenException(
                "BAD_REQUEST",
                "$ctx: 仅支持 https 远程附件 URL（data URI 不受限）",
                400,
            )
        }
        val host = uri.host?.lowercase()
        if (host.isNullOrEmpty()) {
            throw QwenException("BAD_REQUEST", "$ctx: URL 缺少主机名", 400)
        }
        // 主机名是**字面量 IP** 时（`https://[::1]/x`、`https://169.254.169.254/x`），
        // 白名单根本无从谈起 —— 直接给出「目标地址受限」而不是「域名不在白名单」，
        // 后者会把「你在打内网」误导成「你该换个域名」。
        literalAddresses(host).forEach { a ->
            if (isBlockedAddress(a)) {
                throw QwenException(
                    "BAD_REQUEST",
                    "$ctx: 目标地址 ${a.hostAddress} 属于内网/本机网段，已拒绝",
                    400,
                )
            }
        }
        if (!isHostAllowed(host)) {
            throw QwenException(
                "BAD_REQUEST",
                "$ctx: 出于安全考虑仅允许从 Qwen/阿里云域名拉取远程附件" +
                    "（当前主机 $host）。其它来源请改用 data URI 或先 POST /v1/files 上传",
                400,
            )
        }
        // 一次解析 -> 校验 -> 返回，供调用方钉给连接层（见 pinnedDns）。
        // 绝不能在这里解析一次、连接时再解析一次 —— 那就是 DNS-rebinding 窗口。
        return resolveAndValidate(host, ctx)
    }

    /**
     * 解析域名并拒绝内网/回环/链路本地地址，返回**校验通过的地址集合**。
     *
     * 调用方必须把这批地址钉给连接层（见 [pinnedDns]）：单单在这里判一次，
     * 与真正建立连接时的第二次解析之间存在 DNS-rebinding 窗口
     * （TTL=0 的记录可以在两次解析之间换答案）。
     */
    internal fun resolveAndValidate(host: String, ctx: String): List<java.net.InetAddress> {
        val addrs = try {
            java.net.InetAddress.getAllByName(host).toList()
        } catch (e: Exception) {
            throw QwenException("BAD_REQUEST", "$ctx: 域名无法解析 ($host)", 400)
        }
        if (addrs.isEmpty()) {
            throw QwenException("BAD_REQUEST", "$ctx: 域名无法解析 ($host)", 400)
        }
        for (a in addrs) {
            if (isBlockedAddress(a)) {
                throw QwenException("BAD_REQUEST", "$ctx: 目标地址属于内网/本机网段，已拒绝", 400)
            }
        }
        return addrs
    }

    /**
     * 内网/本机/链路本地地址判定。
     * 独立出来是为了可单测：整个判定不依赖真实网络。
     *
     * ## 为什么不能只信 `InetAddress.isXxx()` 系列
     * 实测（JDK/Android 的 InetAddress 归一化行为）：
     *  - `::ffff:10.0.0.1` 会被**归一化成 4 字节** `10.0.0.1`，于是 `isSiteLocalAddress()`
     *    正常返回 true —— 这一形态本来就没问题；
     *  - 但 `::10.0.0.1`（IPv4-compatible，RFC 4291 已废弃）、`64:ff9b::a9fe:a9fe`
     *      （NAT64 转换 169.254.169.254）、`2002:a9fe:a9fe::`（6to4）**保持 16 字节**，
     *    且 `isLoopbackAddress / isSiteLocalAddress / isLinkLocalAddress / isAnyLocalAddress /
     *     isMulticastAddress` **全部返回 false**。原来的实现只额外查了 `fc00::/7`，
     *     这三种形态会直接穿过白名单校验。
     *    对一个能读云元数据（169.254.169.254）的 SSRF 面来说，这等于后门。
     *
     * 因此这里做三层判定：
     *  1. JDK 自带的语义判定（覆盖主流直连形态）；
     *  2. 逐字节的 16 字节 IPv6 前缀表，**包括把内嵌的 IPv4 抠出来再判一遍**；
     *  3. 4 字节 IPv4 的显式保留段表。
     */
    internal fun isBlockedAddress(a: java.net.InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isAnyLocalAddress || a.isLinkLocalAddress ||
            a.isSiteLocalAddress || a.isMulticastAddress
        ) {
            return true
        }
        val b = a.address
        if (b.size == 16) return isBlockedV6(b)
        if (b.size == 4) return isBlockedV4(b)
        return true
    }

    /** IPv4 保留段判定（4 字节） */
    internal fun isBlockedV4(b: ByteArray): Boolean {
        val v0 = b[0].toInt() and 0xFF
        val v1 = b[1].toInt() and 0xFF
        val v2 = b[2].toInt() and 0xFF
        // 0.0.0.0/8（本网络）
        if (v0 == 0) return true
        // 10/8、172.16/12、192.168/16
        if (v0 == 10) return true
        if (v0 == 172 && v1 in 16..31) return true
        if (v0 == 192 && v1 == 168) return true
        // 100.64.0.0/10（CGNAT，运营商内网）
        if (v0 == 100 && v1 in 64..127) return true
        // 127/8（回环）
        if (v0 == 127) return true
        // 169.254/16（链路本地，含云元数据 169.254.169.254 / 169.254.170.2）
        if (v0 == 169 && v1 == 254) return true
        // 192.0.0.0/24（IETF 协议分配）、192.0.2.0/24（TEST-NET-1）
        if (v0 == 192 && v1 == 0 && v2 in intArrayOf(0, 2)) return true
        // 198.18.0.0/15（基准测试）、198.51.100.0/24（TEST-NET-2）
        if (v0 == 198 && (v1 == 18 || v1 == 19)) return true
        if (v0 == 198 && v1 == 51 && v2 == 100) return true
        // 203.0.113.0/24（TEST-NET-3）
        if (v0 == 203 && v1 == 0 && v2 == 113) return true
        // 192.88.99.0/24（6to4 中继，已废弃）、224/4 组播、240/4 保留、255.255.255.255
        if (v0 == 192 && v1 == 88 && v2 == 99) return true
        if (v0 in 224..255) return true
        return false
    }

    /**
     * IPv6 判定（16 字节）。
     *
     * 关键在于「内嵌 IPv4」的三种过渡形态必须把后 4 字节抠出来复用
     * [isBlockedV4]，否则 `64:ff9b::a9fe:a9fe` 这种 NAT64 地址
     * 在纯 IPv6 视角下看起来完全是人畜无害的公网地址。
     */
    internal fun isBlockedV6(b: ByteArray): Boolean {
        fun u(i: Int) = b[i].toInt() and 0xFF

        // ::1 回环、:: 未指定 —— JDK 已覆盖，但这里再钉一次（Android 各版本行为不完全一致）
        var allZeroExceptLast = true
        for (i in 0 until 15) if (b[i].toInt() != 0) { allZeroExceptLast = false; break }
        if (allZeroExceptLast && u(15) <= 1) return true

        // fc00::/7 唯一本地地址
        if ((u(0) and 0xFE) == 0xFC) return true
        // fe80::/10 链路本地
        if (u(0) == 0xFE && (u(1) and 0xC0) == 0x80) return true
        // fec0::/10 站点本地（已废弃）
        if (u(0) == 0xFE && (u(1) and 0xC0) == 0xC0) return true
        // ff00::/8 组播
        if (u(0) == 0xFF) return true

        // ---- 过渡/映射形态：把内嵌 IPv4 抠出来再判 ----
        // ::a.b.c.d 与 ::ffff:a.b.c.d（IPv4-compatible / IPv4-mapped）
        var headZero = true
        for (i in 0 until 10) if (b[i].toInt() != 0) { headZero = false; break }
        if (headZero) {
            val isCompat = b[10].toInt() == 0 && b[11].toInt() == 0
            val isMapped = u(10) == 0xFF && u(11) == 0xFF
            if (isCompat || isMapped) {
                // ::0.0.0.0 / ::1 已在上面拦下；其余交给 IPv4 表
                if (isBlockedV4(b.copyOfRange(12, 16))) return true
            }
        }

        // 64:ff9b::/96 与 64:ff9b:1::/48（NAT64）
        if (u(0) == 0x00 && u(1) == 0x64 && u(2) == 0xFF && u(3) == 0x9B) return true
        // 2002::/16（6to4）：内嵌的 IPv4 就在第 2~5 字节
        if (u(0) == 0x20 && u(1) == 0x02) {
            if (isBlockedV4(byteArrayOf(b[2], b[3], b[4], b[5]))) return true
        }
        // 2001::/32（Teredo）：内嵌的 IPv4 是被 XOR 过的，直接整段拒绝 ——
        // 这个隧道机制在移动端网关场景没有任何正当用途
        if (u(0) == 0x20 && u(1) == 0x01 && u(2) == 0x00 && u(3) == 0x00) return true
        return false
    }

    /**
     * 把用户给的 host 字面量（含 `[::1]` / `::ffff:10.0.0.1` 这类方括号包裹的
     * IPv6 字面量）解析成地址列表。
     *
     * 与 [java.net.URI.getHost] 不同：**剥掉方括号**再交给 InetAddress，
     * 否则 `getAllByName("[::1]")` 会走 DNS 并大概率失败，得到
     * 「域名无法解析」这种误导性错误 —— 真正的拒因是"目标是内网地址"。
     *
     * **只处理字面量**：域名在这里返回空列表，交给白名单 + 后续的
     * [resolveAndValidate] 处理。若不区分，就会在校验白名单**之前**
     * 对一个大概率被拒的域名发起一次无谓的 DNS 查询（离线环境下还会白等超时）。
     */
    internal fun literalAddresses(host: String): List<java.net.InetAddress> {
        val h = host.trim().removePrefix("[").removeSuffix("]").trim()
        if (h.isEmpty()) return emptyList()
        // IPv6 字面量必含冒号；IPv4 字面量是纯点分十进制。
        // 两者都不满足就是域名 —— 不在这里做解析。
        val looksV6 = h.contains(':')
        val looksV4 = h.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))
        if (!looksV6 && !looksV4) return emptyList()
        return try {
            listOf(java.net.InetAddress.getByName(h))
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * MIME -> 文件扩展名（用于构造上传文件名）。
     *
     * 为什么不能再用 `mime.substringAfter('/')`：
     *  - 上游/客户端给的 mime 常带参数（`text/plain; charset=utf-8`），
     *    直接切出来会得到 `plain; charset=utf-8`，拼成
     *    `file.plain; charset=utf-8` 这种文件名。Qwen 侧按扩展名判定文档类型，
     *    于是「文件上传成功了但模型读不到内容」——静默失效，现场只看到模型答非所问；
     *  - 未知子类型可能含空格、`+`、`?` 等字符，进入文件名后行为不可预期。
     *
     * 因此这里做两件事：先剥参数，再查显式映射表；查不到就用严格字符集
     * （仅 `[a-z0-9]`）过滤，仍然为空或过长则退回 `bin`。
     *
     * 注意 mapping 的取值必须与 [QwenClient.mimeToKind] 的扩展名清单保持一致：
     * 附件类型判定是按扩展名做的，两边漂移会让「图片被当成文档」。
     */
    internal fun extFromMime(mime: String?): String {
        val mt = mime.orEmpty().substringBefore(';').trim().lowercase()
        val slash = mt.indexOf('/')
        if (slash <= 0 || slash == mt.length - 1) return "bin"
        MIME_EXT[mt]?.let { return it }
        val sub = mt.substring(slash + 1).replace(Regex("[^a-z0-9]"), "")
        if (sub.isEmpty() || sub.length > 16) return "bin"
        return sub
    }

    /** 人类可读的体积上限（1MB 的整数倍显示为 MB） */
    internal fun formatLimit(limit: Long): String = when {
        limit <= 0 -> "0B"
        limit % (1024 * 1024) == 0L -> "${limit / (1024 * 1024)}MB"
        limit % 1024 == 0L -> "${limit / 1024}KB"
        else -> "${limit}B"
    }

    /**
     * 有界读取：**边读边判**，一超过上限立刻中止。
     *
     * 旧实现是 `resp.body?.bytes()` 之后再比大小。上限虽然存在，但**永远来得太晚**：
     * `bytes()` 会先把整个响应体缓冲进内存，一个白名单域名（或它被攻陷后的 302 目标，
     * 白名单是按 host 校验的）只要返回一个超大响应，进程内存就被吃掉。
     * 本机是 Android 应用进程，直接 OOM 被杀 —— 表现为网关"无缘无故掉线"。
     */
    internal fun readBounded(input: java.io.InputStream, limit: Long, ctx: String): ByteArray {
        if (limit <= 0) return ByteArray(0)
        val out = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024L).toInt())
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) {
                throw QwenException(
                    "BAD_REQUEST",
                    "$ctx: 附件超过 ${formatLimit(limit)} 上限",
                    400,
                )
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * 把「校验过的地址集合」钉给 OkHttp 的连接层。
     *
     * 为什么必须这么做：此前是
     * `assertRemoteUrlAllowed()` 里解析一次 DNS 判定是否内网，然后
     * `downloadClient.newCall(req).execute()` 时 OkHttp 内部**再解析一次**。
     * 两次解析之间就是一个 DNS-rebinding 窗口：攻击者控制的域名（或一个被攻陷的
     * 白名单 CDN 子域）只要把 TTL 设成 0，第一次解析返回公网地址骗过校验，
     * 第二次返回 `127.0.0.1` / `169.254.169.254`，请求就打到内网去了。
     * 白名单只能保证「域名长得像 Qwen 的」，挡不住这种时间差。
     *
     * 钉住之后，OkHttp 不再自行解析，用的就是**我们已经逐字节判定过的那批地址**，
     * 校验与连接指向同一目标，窗口消失。
     */
    private fun pinnedDns(pinned: List<java.net.InetAddress>): okhttp3.Dns =
        object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> = pinned
        }

    /**
     * 构造下载客户端。
     *
     * 禁止自动跟随重定向：改为手动逐跳校验，避免白名单域名 302 到内网。
     * [Dns] 必须是「钉住」的那一批地址（见 [pinnedDns]）。
     *
     * 可见性为 internal 而非 private 是**为了可测**：单测需要断言
     * "客户端真的用了钉住的地址"，否则这段防护一旦被改回默认 DNS
     * 也没有任何东西会红。
     */
    internal fun newDownloadClient(pinned: List<java.net.InetAddress>): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .dns(pinnedDns(pinned))
            .build()

    /** data URI / 远程 URL -> {bytes, mime} */
    fun loadBytes(url: String?, ctx: String): Loaded {
        val s = url.orEmpty()
        if (s.startsWith("data:", ignoreCase = true)) {
            val comma = s.indexOf(',')
            if (comma < 0) throw QwenException("BAD_REQUEST", "$ctx: data URI 格式不正确", 400)
            val meta = s.substring(5, comma)
            val payload = s.substring(comma + 1)
            val isBase64 = meta.contains(";base64", ignoreCase = true)
            val mime = meta.substringBefore(';').ifEmpty { "application/octet-stream" }
            val bytes = if (isBase64) {
                try {
                    B64.decode(payload)
                } catch (e: Exception) {
                    throw QwenException("BAD_REQUEST", "$ctx: base64 解码失败", 400)
                }
            } else {
                java.net.URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
            }
            return Loaded(bytes, mime)
        }
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) {
            throw QwenException("BAD_REQUEST", "$ctx: 仅支持 data URI 或 http(s) URL", 400)
        }
        val first = assertRemoteUrlAllowed(s, ctx)
        return try {
            download(s, ctx, first)
        } catch (e: QwenException) {
            throw e
        } catch (e: Exception) {
            throw QwenException("BAD_REQUEST", "$ctx: 下载失败 (${e.message})", 400)
        }
    }

    /**
     * 单跳重定向的判定结果。
     *
     * 抽成密封类是为了让「这一跳到底该不该跟、跟到哪、不跟时给什么错」
     * 变成**不依赖网络**的纯函数 —— 否则这段逻辑只有真发 HTTP 请求才能跑到，
     * 而重定向恰恰是 SSRF 最常见的收尾动作。
     */
    internal sealed class HopDecision {
        /** 不是重定向：调用方按普通响应继续处理（成功读 body / 失败报 HTTP 状态码）。 */
        object PassThrough : HopDecision()

        /** 需要跟跳，[nextUrl] 已是解析完成的绝对地址。 */
        data class Follow(val nextUrl: String, val nextHop: Int) : HopDecision()

        /** 是重定向但拒绝跟跳，[message] 为可直接抛给调用方的错误文案。 */
        data class Reject(val message: String) : HopDecision()
    }

    /**
     * 是否为「应当跟跳」的状态码。
     *
     * **这里刻意不用 `in 301..308`**：那个区间把两个语义完全不同的码卷了进来——
     *  - `300 Multiple Choices`：只有**带 Location** 时才算重定向，否则就是一个正常响应；
     *  - `304 Not Modified`：**永远不是重定向**，它是条件请求的缓存命中答复，本就没有
     *    也不需要 Location。按区间判定会让 304 走进「重定向缺少 Location」分支，
     *    把「上游告诉你缓存没变」误报成「重定向格式错误」——现场排查会一路跑偏。
     */
    internal fun isRedirectStatus(code: Int): Boolean =
        code == 301 || code == 302 || code == 303 || code == 307 || code == 308

    /**
     * 判定一次 HTTP 响应该不该跟跳，并解析出下一跳的绝对地址。
     *
     * 纯逻辑、无网络、无副作用，`currentUrl` / `location` 都以字符串进出，
     * 便于覆盖这些容易出错的分支：
     *  - `Location` 缺失或空白；
     *  - 相对路径（`/a/b.png`）、协议相对（`//evil.com/x`）、绝对地址；
     *  - 非 http(s) scheme（`javascript:` / `file:` / `data:`）；
     *  - 跳数用尽。
     *
     * **注意这里不校验白名单**：目标是哪个地址由调用方随后交给
     * [assertRemoteUrlAllowed] 逐跳校验并钉住 DNS。判定与校验分离，
     * 是为了让「跟不跟」和「能不能跟」分别可测。
     */
    internal fun decideHop(
        currentUrl: String,
        code: Int,
        location: String?,
        hop: Int,
        ctx: String,
    ): HopDecision {
        // 304 / 305 / 306 等不在重定向集合内：按普通响应交给调用方（最终多半是非 2xx 拒绝，
        // 但错误文案必须是「下载失败 HTTP 304」而不是「重定向缺少 Location」）。
        if (!isRedirectStatus(code)) {
            // 300 特例：带 Location 才有跟跳语义，否则也是普通响应
            if (code != 300 || location.isNullOrBlank()) return HopDecision.PassThrough
        }
        if (location.isNullOrBlank()) {
            return HopDecision.Reject("$ctx: 重定向缺少 Location")
        }
        if (hop >= MAX_REDIRECTS) {
            return HopDecision.Reject("$ctx: 重定向次数过多")
        }
        val base = currentUrl.toHttpUrlOrNull()
            ?: return HopDecision.Reject("$ctx: 重定向目标无法解析")
        // HttpUrl.resolve 只接受 http/https 的绝对地址或相对引用：
        // `Location: javascript:alert(1)` / `file:///etc/passwd` 会在这里得到 null。
        val next = base.resolve(location)
            ?: return HopDecision.Reject("$ctx: 重定向目标协议不受支持")
        return HopDecision.Follow(next.toString(), hop + 1)
    }

    /**
     * 带重定向次数上限的下载：每跳都重新做白名单 + 内网地址校验，
     * 并把该跳校验通过的地址**钉给连接层**。
     *
     * 手动跟跳的原因见 [newDownloadClient] 的 `followRedirects(false)`；
     * 跟跳决策见 [decideHop]。
     */
    private fun download(
        url: String,
        ctx: String,
        pinned: List<java.net.InetAddress>,
        hop: Int = 0,
    ): Loaded {
        val req = Request.Builder().url(url).get().build()
        newDownloadClient(pinned).newCall(req).execute().use { resp ->
            when (val d = decideHop(
                currentUrl = resp.request.url.toString(),
                code = resp.code,
                location = resp.header("Location"),
                hop = hop,
                ctx = ctx,
            )) {
                is HopDecision.Reject -> throw QwenException("BAD_REQUEST", d.message, 400)
                is HopDecision.Follow -> {
                    // 每一跳都重新校验 + 重新钉住地址：302 到内网是这类 SSRF 的经典收尾
                    val nextPinned = assertRemoteUrlAllowed(d.nextUrl, ctx)
                    return download(d.nextUrl, ctx, nextPinned, d.nextHop)
                }
                HopDecision.PassThrough -> Unit
            }
            if (!resp.isSuccessful) {
                throw QwenException("BAD_REQUEST", "$ctx: 下载失败 HTTP ${resp.code}", 400)
            }
            val mime = (resp.header("Content-Type") ?: "").substringBefore(';').trim()
                .ifEmpty { "application/octet-stream" }
            // 有界读取：先看声明的 Content-Length 快速拒绝，再边读边判（防 chunked 无长度声明）
            resp.header("Content-Length")?.toLongOrNull()?.let { declared ->
                if (declared > MAX_ATTACHMENT_BYTES) {
                    throw QwenException(
                        "BAD_REQUEST",
                        "$ctx: 附件超过 ${formatLimit(MAX_ATTACHMENT_BYTES)} 上限" +
                            "（上游声明 $declared 字节）",
                        400,
                    )
                }
            }
            val body = resp.body ?: return Loaded(ByteArray(0), mime)
            val bytes = body.byteStream().use { readBounded(it, MAX_ATTACHMENT_BYTES, ctx) }
            return Loaded(bytes, mime)
        }
    }

    /**
     * 解析并上传所有附件，同时把每条消息的 content 扁平化为纯文本。
     * @return files[] 条目列表
     */
    fun resolve(
        client: QwenClient,
        messages: MutableList<ChatMessage>,
        fileRegistry: FileStore,
    ): List<JSONObject> = runBlocking { resolveSuspend(client, messages, fileRegistry) }

    /**
     * 协程版本：解析并上传所有附件，同时把每条消息的 content 扁平化为纯文本。
     * @return files[] 条目列表
     */
    suspend fun resolveSuspend(
        client: QwenClient,
        messages: MutableList<ChatMessage>,
        fileRegistry: FileStore,
    ): List<JSONObject> {
        val files = ArrayList<JSONObject>()
        for (i in messages.indices) {
            val m = messages[i]
            val c = m.content
            if (c !is List<*>) {
                if (c != null && c !is String) messages[i] = m.copy(content = c.toString())
                continue
            }
            val texts = ArrayList<String>()
            for (raw in c) {
                val part = raw as? Map<*, *> ?: continue
                when (val type = part["type"]?.toString()) {
                    "text" -> texts.add(part["text"]?.toString().orEmpty())

                    "image_url", "video_url" -> {
                        val holder = part[type]
                        val src = when (holder) {
                            is String -> holder
                            is Map<*, *> -> holder["url"]?.toString()
                            else -> null
                        }
                        if (src.isNullOrEmpty()) {
                            throw QwenException("BAD_REQUEST", "$type.url 缺失", 400)
                        }
                        val kind = if (type == "image_url") AttachmentKind.IMAGE else AttachmentKind.VIDEO
                        val loaded = loadBytes(src, type)
                        val up = client.uploadFile(
                            loaded.bytes, "media.${extFromMime(loaded.mime)}", loaded.mime, kind,
                        )
                        files.add(up.entry)
                    }

                    "input_audio" -> {
                        val ia = part["input_audio"] as? Map<*, *>
                            ?: throw QwenException("BAD_REQUEST", "input_audio 结构错误", 400)
                        val data = ia["data"]?.toString()
                        if (data.isNullOrEmpty()) {
                            throw QwenException("BAD_REQUEST", "input_audio.data 缺失", 400)
                        }
                        val bytes = try {
                            B64.decode(data)
                        } catch (e: Exception) {
                            throw QwenException("BAD_REQUEST", "input_audio.data base64 解码失败", 400)
                        }
                        val fmt = (ia["format"]?.toString() ?: "wav")
                            .replace(Regex("[^A-Za-z0-9]"), "").ifEmpty { "wav" }
                        val up = client.uploadFile(
                            bytes, "audio.$fmt", "audio/$fmt", AttachmentKind.AUDIO,
                        )
                        files.add(up.entry)
                    }

                    "file" -> {
                        val f = part["file"] as? Map<*, *>
                            ?: throw QwenException("BAD_REQUEST", "file part 结构错误", 400)
                        val fileId = f["file_id"]?.toString()
                        val fileData = f["file_data"]?.toString()
                        if (!fileId.isNullOrEmpty()) {
                            val rec = fileRegistry.find(fileId)
                                ?: throw QwenException(
                                    "BAD_REQUEST",
                                    "file_id 未找到: $fileId (请先 POST /v1/files 上传)",
                                    400,
                                )
                            files.add(rec.entry)
                        } else if (!fileData.isNullOrEmpty()) {
                            val loaded = loadBytes(fileData, "file.file_data")
                            // 扩展名必须走 extFromMime：旧实现直接 substringAfter('/') 且**不剥分号**，
                            // `text/plain; charset=utf-8` 会生成 `file.plain; charset=utf-8`，
                            // 文档解析按扩展名判类型 → 解析不出内容（静默失效）。
                            val name = f["filename"]?.toString()?.takeIf { it.isNotBlank() }
                                ?: "file.${extFromMime(loaded.mime)}"
                            val up = client.uploadFile(loaded.bytes, name, loaded.mime)
                            files.add(up.entry)
                        } else {
                            throw QwenException("BAD_REQUEST", "file part 需要 file_id 或 file_data", 400)
                        }
                    }

                    else -> {
                        // 未知 part 类型：忽略，保持向后兼容
                    }
                }
            }
            messages[i] = m.copy(content = texts.joinToString("\n"))
        }
        return files
    }

    /**
     * 附件相关错误的友好化：视频附件发给非 omni 模型时上游报 invalid_input，给出换模型提示。
     */
    fun humanize(e: Throwable, files: List<JSONObject>): QwenException {
        val base = if (e is QwenException) e else {
            val (code, msg, status) = Util.normalizeError(e)
            QwenException(code, msg, status)
        }
        if (files.isNotEmpty() &&
            (base.code == "invalid_input" || Regex("attachment|附件", RegexOption.IGNORE_CASE).containsMatchIn(base.message))
        ) {
            val hasVideo = files.any { Json.str(it, "type") == "video" }
            if (hasVideo) {
                return QwenException(
                    base.code,
                    base.message + "。提示: 视频附件实测仅 qwen3.5-omni-plus 模型支持, " +
                        "请将 model 参数改为 qwen3.5-omni-plus 后重试",
                    base.status,
                )
            }
        }
        return base
    }

    private const val MAX_REDIRECTS = 3
    private const val MAX_ATTACHMENT_BYTES = 64 * 1024 * 1024L

    /**
     * 显式 MIME -> 扩展名映射（常用类型）。
     * 值必须落在 [QwenClient.mimeToKind] 认得的扩展名清单里：
     *  - image: png/jpg/jpeg/gif/webp/bmp/svg
     *  - video: mp4/mov/avi/mkv/webm/m4v
     *  - audio: mp3/wav/m4a/aac/ogg/flac
     *  - 其它一律按文档（document）走解析流程
     */
    private val MIME_EXT = mapOf(
        "image/png" to "png",
        "image/jpeg" to "jpg",
        "image/jpg" to "jpg",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "image/bmp" to "bmp",
        "image/svg+xml" to "svg",
        "image/heic" to "heic",
        "video/mp4" to "mp4",
        "video/quicktime" to "mov",
        "video/webm" to "webm",
        "video/x-matroska" to "mkv",
        "video/x-msvideo" to "avi",
        "audio/mpeg" to "mp3",
        "audio/wav" to "wav",
        "audio/x-wav" to "wav",
        "audio/wave" to "wav",
        "audio/mp4" to "m4a",
        "audio/aac" to "aac",
        "audio/ogg" to "ogg",
        "audio/flac" to "flac",
        "text/plain" to "txt",
        "text/markdown" to "md",
        "text/csv" to "csv",
        "text/html" to "html",
        "application/pdf" to "pdf",
        "application/json" to "json",
        "application/zip" to "zip",
        "application/xml" to "xml",
        "application/msword" to "doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.ms-excel" to "xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.ms-powerpoint" to "ppt",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/octet-stream" to "bin",
    )
}

/**
 * 本地文件注册表（移植自 lib/server.js 的 files.json 部分）。
 * OpenAI 风格 file_id -> Qwen 上传结果（含可直接引用的 files[] 条目）。
 */
class FileRegistry(private val storage: android.content.Context) : FileStore {

    private val pref = storage.applicationContext
        .getSharedPreferences("qwen2api_files", android.content.Context.MODE_PRIVATE)

    @Volatile
    private var cache: MutableList<FileRecord>? = null

    @Synchronized
    private fun records(): MutableList<FileRecord> {
        cache?.let { return it }
        val list = ArrayList<FileRecord>()
        val raw = pref.getString("files", null)
        if (!raw.isNullOrEmpty()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    list.add(
                        FileRecord(
                            id = Json.str(o, "id"),
                            qwenId = Json.str(o, "qwenId"),
                            url = Json.str(o, "url"),
                            filename = Json.str(o, "filename"),
                            bytes = Json.long(o, "bytes"),
                            mime = Json.str(o, "mime"),
                            kind = Json.str(o, "kind"),
                            createdAt = Json.long(o, "created_at"),
                            purpose = Json.str(o, "purpose", "assistants"),
                            entry = Json.obj(o, "entry") ?: JSONObject(),
                        ),
                    )
                }
            } catch (e: Exception) {
                // 损坏则重建
            }
        }
        cache = list
        return list
    }

    @Synchronized
    private fun persist() {
        val arr = JSONArray()
        records().forEach { arr.put(it.toJson()) }
        pref.edit().putString("files", arr.toString()).apply()
    }

    @Synchronized
    override fun all(): List<FileRecord> = records().toList()

    @Synchronized
    override fun find(id: String): FileRecord? {
        val key = id.removePrefix("file-")
        return records().firstOrNull { it.id == id || it.id == "file-$key" }
    }

    @Synchronized
    override fun add(rec: FileRecord) {
        records().add(rec)
        persist()
    }

    @Synchronized
    override fun remove(id: String): FileRecord? {
        val rec = find(id) ?: return null
        records().remove(rec)
        persist()
        return rec
    }
}

/** 内存文件注册表：用于测试 */
class MemoryFileStore : FileStore {
    private val list = java.util.Collections.synchronizedList(ArrayList<FileRecord>())

    override fun all(): List<FileRecord> = synchronized(list) { list.toList() }

    override fun find(id: String): FileRecord? {
        val key = id.removePrefix("file-")
        return synchronized(list) {
            list.firstOrNull { it.id == id || it.id == "file-$key" }
        }
    }

    override fun add(rec: FileRecord) {
        list.add(rec)
    }

    override fun remove(id: String): FileRecord? {
        val rec = find(id) ?: return null
        list.remove(rec)
        return rec
    }
}
