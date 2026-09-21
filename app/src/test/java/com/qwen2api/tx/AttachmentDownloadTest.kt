package com.qwen2api.tx

import com.qwen2api.tx.core.QwenException
import com.qwen2api.tx.server.AttachmentResolver
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * 附件远程下载链路的**地址判定与 DNS 钉扎**测试。
 *
 * ## 为什么单独开一个类
 * `AttachmentResolveTest` 覆盖的是「解析 content 数组 -> 上传 -> 扁平化」这条主路，
 * 而 `loadBytes` 里真正跟**网络**打交道的部分——远程 URL 的地址校验与重定向——
 * 此前只有「白名单域名放行 / 非白名单拒绝 / 明文 http 拒绝」三个粗糙断言。
 * 剩下的全是盲区，且失效方式极其隐蔽：
 *
 * ```
 *   image_url: https://<某个白名单域名>/<path>
 *        │
 *        ├─ ① 字面量 IP 判定      ← 此前只拦了部分形态
 *        ├─ ② 域名白名单           ← 覆盖了
 *        ├─ ③ DNS -> 内网判定      ← 覆盖了「拦不拦」，没覆盖「拦完还用不用同一批地址」
 *        └─ ④ 302 逐跳重校验       ← 零覆盖（需要真实网络才能跑到）
 * ```
 *
 * 本轮实测钉死的三件事：
 *
 * **1）三种 IPv6 过渡形态会直接穿过内网判定。**
 *    实测 `InetAddress.getAllByName` 的归一化行为：
 *    - `::ffff:10.0.0.1` 会被压成 4 字节 `10.0.0.1`，`isSiteLocalAddress()` 正常生效；
 *    - 但 `::10.0.0.1`（IPv4-compatible）、`64:ff9b::a9fe:a9fe`（NAT64 映射
 *      169.254.169.254）、`2002:a9fe:a9fe::`（6to4）**保持 16 字节**，
 *      且 loopback/siteLocal/linkLocal/anyLocal/multicast **全部为 false**。
 *    对一个能读到云元数据的 SSRF 面来说，这是三条后门。
 *
 * **2）校验与连接之间是两次 DNS 解析 → DNS-rebinding 窗口。**
 *    校验时解析一次，OkHttp 建连时又解析一次。TTL=0 的记录可以让第二次
 *    返回 `127.0.0.1`。修法是把校验通过的地址集合**钉给 OkHttp 的 [Dns]**。
 *
 * **3）字面量 IP 主机名此前会给出误导性错误。**
 *    `https://[::1]/x` 走的是「域名不在白名单」，用户以为该换个域名，
 *    而真相是「你在打内网」。两者必须能区分。
 */
class AttachmentDownloadTest {

    private fun badRequest(block: () -> Unit): String {
        try {
            block()
        } catch (e: QwenException) {
            assertEquals("错误码必须是 BAD_REQUEST，实际 ${e.code}", "BAD_REQUEST", e.code)
            return e.message
        }
        throw AssertionError("本应抛 QwenException 却通过了")
    }

    private fun addr(lit: String): InetAddress = InetAddress.getByName(lit)

    // ================= 1. IPv4 保留段 =================

    @Test
    fun `ipv4 private and reserved ranges are all blocked`() {
        val blocked = listOf(
            "0.0.0.0", "0.1.2.3",
            "10.0.0.1", "10.255.255.255",
            "172.16.0.1", "172.31.255.254",
            "192.168.0.1", "192.168.255.255",
            "127.0.0.1", "127.255.255.255",
            "169.254.169.254", "169.254.170.2",
            "100.64.0.1", "100.127.255.255",
            "192.0.0.1", "192.0.2.1",
            "198.18.0.1", "198.19.255.255", "198.51.100.1",
            "203.0.113.1",
            "192.88.99.1",
            "224.0.0.1", "239.255.255.255",
            "240.0.0.1", "255.255.255.255",
        )
        blocked.forEach {
            assertTrue("应判定为受限地址: $it", AttachmentResolver.isBlockedAddress(addr(it)))
        }
    }

    @Test
    fun `ipv4 public addresses are not blocked`() {
        listOf("8.8.8.8", "1.1.1.1", "223.5.5.5", "13.107.42.14", "100.63.255.255", "100.128.0.1")
            .forEach {
                assertFalse("公网地址不应被拦: $it", AttachmentResolver.isBlockedAddress(addr(it)))
            }
    }

    // ================= 2. IPv6 基本段 + 过渡形态 =================

    @Test
    fun `ipv6 special ranges are blocked`() {
        listOf(
            "::1", "::",
            "fe80::1", "fe80::dead:beef",
            "fc00::1", "fd12:3456::1",
            "fec0::1",
            "ff02::1", "ff05::2",
        ).forEach {
            assertTrue("应判定为受限地址: $it", AttachmentResolver.isBlockedAddress(addr(it)))
        }
    }

    /**
     * 这三种形态是本轮实测发现**真正漏掉**的。
     * 它们都是 16 字节、且所有 `isXxxAddress()` 均为 false。
     */
    @Test
    fun `ipv4 compatible ipv6 form is blocked`() {
        listOf("::10.0.0.1", "::127.0.0.1", "::169.254.169.254", "::192.168.1.1").forEach {
            val a = addr(it)
            assertEquals("该形态应保持 16 字节: $it", 16, a.address.size)
            assertTrue(
                "IPv4-compatible 形态必须把内嵌 IPv4 抠出来判定，否则 $it 会穿过内网校验",
                AttachmentResolver.isBlockedAddress(a),
            )
        }
    }

    @Test
    fun `nat64 mapped address is blocked`() {
        // 64:ff9b::/96 是 NAT64 的标准前缀：纯 IPv6 视角下完全像公网地址
        listOf("64:ff9b::7f00:1", "64:ff9b::a9fe:a9fe", "64:ff9b::a00:1", "64:ff9b:1::a00:1").forEach {
            assertTrue("NAT64 形态必须被拦: $it", AttachmentResolver.isBlockedAddress(addr(it)))
        }
    }

    @Test
    fun `6to4 and teredo tunnel forms are blocked`() {
        // 6to4（2002::/16）内嵌 IPv4 在第 2~5 字节
        listOf("2002:a9fe:a9fe::1", "2002:a00:1::1", "2002:7f00:1::1").forEach {
            assertTrue("6to4 形态必须被拦: $it", AttachmentResolver.isBlockedAddress(addr(it)))
        }
        // Teredo（2001::/32）内嵌 IPv4 经过 XOR，直接整段拒绝
        listOf("2001:0:0:0:0:0:a00:1", "2001:0:1234:5678:9abc:def0:1234:5678").forEach {
            assertTrue("Teredo 形态必须被拦: $it", AttachmentResolver.isBlockedAddress(addr(it)))
        }
    }

    @Test
    fun `ipv4 mapped ipv6 with public address is still allowed`() {
        // 不能过度拦截：映射的公网地址应当放行，否则正常 CDN 直连会被误伤
        listOf("::ffff:8.8.8.8", "::8.8.8.8").forEach {
            assertFalse("公网映射地址不应被拦: $it", AttachmentResolver.isBlockedAddress(addr(it)))
        }
    }

    // ================= 3. 字面量主机名：拒因必须准确 =================

    @Test
    fun `literal loopback hostname is rejected as internal address not as whitelist miss`() {
        // 关键区分：`https://[::1]/x` 此前走「域名不在白名单」，用户会去换域名，
        // 而真相是「你在打内网」。两者必须给出不同的可行动信息。
        listOf(
            "https://127.0.0.1/a.png",
            "https://[::1]/a.png",
            "https://169.254.169.254/latest/meta-data/",
            "https://[64:ff9b::a9fe:a9fe]/x.png",
        ).forEach { url ->
            val msg = badRequest { AttachmentResolver.assertRemoteUrlAllowed(url, "image_url") }
            assertTrue(
                "字面量内网地址应报「内网/本机网段」而不是白名单缺失: $url -> $msg",
                msg.contains("内网") || msg.contains("本机"),
            )
        }
    }

    @Test
    fun `domain hostname still fails on whitelist with actionable message`() {
        val msg = badRequest {
            AttachmentResolver.assertRemoteUrlAllowed("https://example.com/a.png", "image_url")
        }
        assertTrue("非白名单域名应提示白名单限制: $msg", msg.contains("安全") || msg.contains("域名"))
        assertFalse("不应把域名问题误报成内网地址问题: $msg", msg.contains("内网/本机"))
    }

    @Test
    fun `literalAddresses only parses literals and never touches dns for domains`() {
        // 离线环境的关键保证：域名不能在这里触发 DNS 查询
        assertTrue("域名必须返回空列表", AttachmentResolver.literalAddresses("cdn.qwen.ai").isEmpty())
        assertTrue("域名必须返回空列表", AttachmentResolver.literalAddresses("example.com").isEmpty())
        assertEquals(1, AttachmentResolver.literalAddresses("127.0.0.1").size)
        assertEquals(1, AttachmentResolver.literalAddresses("[::1]").size)
        assertTrue("空主机名返回空", AttachmentResolver.literalAddresses("").isEmpty())
        assertTrue("方括号内为空返回空", AttachmentResolver.literalAddresses("[]").isEmpty())
    }

    @Test
    fun `scheme and host shape validation stays ahead of address checks`() {
        // 顺序很重要：scheme 不合法时不该去做地址/域名判定
        assertTrue(badRequest {
            AttachmentResolver.assertRemoteUrlAllowed("http://127.0.0.1/a.png", "image_url")
        }.contains("https"))
        assertTrue(badRequest {
            AttachmentResolver.assertRemoteUrlAllowed("https:///a.png", "image_url")
        }.contains("主机名"))
        assertTrue(badRequest {
            AttachmentResolver.assertRemoteUrlAllowed("not a url", "image_url")
        }.contains("解析"))
    }

    // ================= 4. DNS 钉扎：rebinding 窗口 =================

    /**
     * 这是本类最核心的一条断言。
     *
     * 校验通过的地址必须被**钉进连接层**。否则 OkHttp 建连时会自行再解析一次，
     * 那次解析可以返回与校验时不同的结果（TTL=0），内网判定形同虚设。
     */
    @Test
    fun `download client pins the validated address instead of re resolving`() {
        val pinned = listOf(addr("13.107.42.14"))
        val client = AttachmentResolver.newDownloadClient(pinned)

        val dns: Dns = client.dns
        assertNotNull("客户端必须有 Dns 实例", dns)

        // 无论问什么主机名，都必须返回同一批已校验地址
        listOf("cdn.qwen.ai", "oss-cn-hangzhou.aliyuncs.com", "anything.example").forEach { host ->
            val got = dns.lookup(host)
            assertEquals("钉扎后不应按主机名重新解析: $host", pinned, got)
            assertEquals("解析结果条数必须与校验时一致", 1, got.size)
        }
    }

    @Test
    fun `pinned dns must not silently fall back to system resolver`() {
        // 反向断言：如果这段防护被改回默认 Dns，那么对同一个域名，
        // 钉扎客户端得到的地址应当是**我们给的**，而系统解析器给的是别的东西。
        val pinned = listOf(addr("1.1.1.1"))
        val client = AttachmentResolver.newDownloadClient(pinned)
        val viaPinned = client.dns.lookup("cdn.qwen.ai")
        assertEquals(listOf(addr("1.1.1.1")), viaPinned)

        // 系统解析器在同一主机名上的行为不受我们控制 —— 因此两者相等纯属巧合，
        // 这里只钉住「钉扎路径确实生效」，不去断言系统解析结果（离线环境会抛异常）。
        val system = runCatching { Dns.SYSTEM.lookup("cdn.qwen.ai") }.getOrNull()
        if (system != null) {
            assertFalse(
                "若钉扎失效，客户端会拿到系统解析结果 —— 那正是 rebinding 窗口",
                viaPinned == system,
            )
        }
    }

    @Test
    fun `download client does not follow redirects automatically`() {
        // 自动跟随重定向会绕过逐跳校验：302 到内网是这类 SSRF 的经典收尾
        val client = AttachmentResolver.newDownloadClient(listOf(addr("1.1.1.1")))
        assertFalse("必须禁止自动跟随重定向", client.followRedirects)
        assertFalse("必须禁止自动跟随 SSL 重定向", client.followSslRedirects)
    }

    // ================= 5. 白名单后缀匹配的边界 =================

    @Test
    fun `whitelist suffix matching resists lookalike domains`() {
        listOf("qwen.ai", "cdn.qwen.ai", "oss-cn-hangzhou.aliyuncs.com", "dashscope.aliyuncs.com")
            .forEach { assertTrue("应命中白名单: $it", AttachmentResolver.isHostAllowed(it)) }

        listOf(
            "qwen.ai.evil.com",
            "notqwen.ai",
            "evilqwen.ai",
            "qwen.ai ",              // 带空格不应被 trim 后放行
            "aliyuncs.com.evil.io",
            "example.com",
            "",
            ".",
        ).forEach { assertFalse("不应命中白名单: '$it'", AttachmentResolver.isHostAllowed(it)) }
    }

    // ================= 6. 重定向逐跳决策（纯逻辑，离线可测） =================

    /**
     * 这些用例覆盖的是**跟不跟这一跳**的判定本身。
     *
     * 之所以值得单独测：这段判定此前内联在 `download()` 里，只有真发 HTTP 才能跑到，
     * 而 302 到内网正是这类 SSRF 的经典收尾 —— 一条零覆盖的路径正好是最危险的路径。
     */
    @Test
    fun `only 301 302 303 307 308 count as redirects`() {
        listOf(301, 302, 303, 307, 308).forEach {
            assertTrue("HTTP $it 应判定为重定向", AttachmentResolver.isRedirectStatus(it))
        }
        listOf(200, 201, 204, 206, 300, 304, 305, 306, 400, 401, 403, 404, 500, 502, 503).forEach {
            assertFalse("HTTP $it 不应判定为重定向", AttachmentResolver.isRedirectStatus(it))
        }
    }

    /**
     * 本类第二个核心断言：**304 不是重定向**。
     *
     * 旧实现写的是 `resp.code in 301..308`，把 304 卷了进来。304 是条件请求的
     * 缓存命中答复，本来就没有 Location，于是必然走进「重定向缺少 Location」分支：
     * 现场看到的是「重定向格式错误」，真相却是「上游说缓存没变」。
     * 300 同理（只有带 Location 才谈得上跟跳）。
     */
    @Test
    fun `not modified and bare multiple choices are not treated as redirects`() {
        val d304 = AttachmentResolver.decideHop(
            currentUrl = "https://cdn.qwen.ai/a.png",
            code = 304,
            location = null,
            hop = 0,
            ctx = "image_url",
        )
        assertEquals(
            "304 必须原样透传（随后按非 2xx 报「下载失败 HTTP 304」），而不是「缺少 Location」",
            AttachmentResolver.HopDecision.PassThrough,
            d304,
        )

        val d300 = AttachmentResolver.decideHop(
            currentUrl = "https://cdn.qwen.ai/a.png",
            code = 300,
            location = null,
            hop = 0,
            ctx = "image_url",
        )
        assertEquals("300 无 Location 时应透传", AttachmentResolver.HopDecision.PassThrough, d300)

        // 300 带 Location 时才是重定向语义
        val d300loc = AttachmentResolver.decideHop(
            currentUrl = "https://cdn.qwen.ai/a.png",
            code = 300,
            location = "https://cdn.qwen.ai/b.png",
            hop = 0,
            ctx = "image_url",
        )
        assertTrue("300 带 Location 应跟跳: $d300loc", d300loc is AttachmentResolver.HopDecision.Follow)
    }

    @Test
    fun `hop decision resolves relative protocol relative and absolute locations`() {
        fun follow(code: Int, loc: String, hop: Int = 0) =
            AttachmentResolver.decideHop("https://cdn.qwen.ai/dir/a.png", code, loc, hop, "image_url")
                as? AttachmentResolver.HopDecision.Follow
                ?: throw AssertionError("本应跟跳: code=$code loc=$loc")

        // 绝对地址
        assertEquals(
            "https://other.qwen.ai/b.png",
            follow(302, "https://other.qwen.ai/b.png").nextUrl,
        )
        // 根相对
        assertEquals("https://cdn.qwen.ai/root.png", follow(302, "/root.png").nextUrl)
        // 目录相对
        assertEquals("https://cdn.qwen.ai/dir/sub.png", follow(302, "sub.png").nextUrl)
        // 上跳
        assertEquals("https://cdn.qwen.ai/up.png", follow(302, "../up.png").nextUrl)
        // 协议相对：**继承当前 scheme**（https），不能变成明文 http
        val pr = follow(302, "//other.qwen.ai/x.png")
        assertEquals("https://other.qwen.ai/x.png", pr.nextUrl)
        assertTrue("协议相对目标必须继承 https，不得退化成明文", pr.nextUrl.startsWith("https://"))
        // hop 自增
        assertEquals(1, follow(307, "/a", hop = 0).nextHop)
        assertEquals(3, follow(308, "/a", hop = 2).nextHop)
    }

    @Test
    fun `hop decision rejects unsupported schemes and unparseable targets`() {
        fun reject(loc: String): String {
            val d = AttachmentResolver.decideHop(
                "https://cdn.qwen.ai/a.png", 302, loc, 0, "image_url",
            )
            return (d as? AttachmentResolver.HopDecision.Reject)?.message
                ?: throw AssertionError("本应拒绝: $loc -> $d")
        }
        // 这三类根本不该有资格成为下一跳目标
        listOf("javascript:alert(1)", "file:///etc/passwd", "data:text/plain,hi", "ftp://x/y")
            .forEach {
                val m = reject(it)
                assertTrue("非 http(s) 目标必须被拒: $it -> $m", m.contains("协议") || m.contains("解析"))
            }
    }

    @Test
    fun `hop decision rejects missing location and exhausted hops`() {
        fun code(location: String?, hop: Int): AttachmentResolver.HopDecision =
            AttachmentResolver.decideHop("https://cdn.qwen.ai/a.png", 302, location, hop, "image_url")

        val missing = code(null, 0) as? AttachmentResolver.HopDecision.Reject
        assertNotNull("缺 Location 必须拒绝", missing)
        assertTrue("文案要指明缺 Location: ${missing!!.message}", missing.message.contains("Location"))

        val blank = code("   ", 0) as? AttachmentResolver.HopDecision.Reject
        assertNotNull("空白 Location 必须拒绝", blank)

        // 上限用尽：hop == MAX_REDIRECTS(3) 时不再跟跳
        val capped = code("https://cdn.qwen.ai/b.png", 3) as? AttachmentResolver.HopDecision.Reject
        assertNotNull("跳数用尽必须拒绝", capped)
        assertTrue("文案要指明次数过多: ${capped!!.message}", capped.message.contains("重定向次数过多"))

        // 边界：hop=2 仍可跟跳（第 3 跳），正好用满上限
        assertTrue("hop=2 应仍可跟跳", code("https://cdn.qwen.ai/b.png", 2)
            is AttachmentResolver.HopDecision.Follow)
    }

    /**
     * 跟跳决策与地址校验的**分离**必须成立：
     * 决策层放行的下一跳，仍要过白名单/内网判定这道闸。
     */
    @Test
    fun `following a hop still requires passing address validation`() {
        // 决策层只看「能不能解析」，不管目标是谁 —— 于是内网目标会被这里放行
        val d = AttachmentResolver.decideHop(
            "https://cdn.qwen.ai/a.png", 302, "http://127.0.0.1/evil", 0, "image_url",
        )
        assertTrue("决策层不负责白名单，应给出 Follow: $d", d is AttachmentResolver.HopDecision.Follow)

        // 真正的闸在下一步：白名单域名 302 到内网/明文，必须在这里断掉
        val toInternal = badRequest {
            AttachmentResolver.assertRemoteUrlAllowed(
                (d as AttachmentResolver.HopDecision.Follow).nextUrl, "image_url",
            )
        }
        assertTrue("302 到内网必须被拦: $toInternal", toInternal.contains("内网") || toInternal.contains("https"))

        val toEvil = badRequest {
            AttachmentResolver.assertRemoteUrlAllowed("https://evil.com/x.png", "image_url")
        }
        assertTrue("302 到非白名单域名必须被拦: $toEvil", toEvil.contains("安全") || toEvil.contains("域名"))
    }

    // ================= 7. 有界读取 / 扩展名 / 体积文案 =================

    /**
     * `readBounded` 是「边读边判」的实现。旧实现 `resp.body?.bytes()` 先缓冲再比大小，
     * 上限永远来得太晚：一个白名单域名（或它被攻陷后的 302 目标）返回超大响应即可
     * 撑爆 Android 应用进程内存，表现为网关"无缘无故掉线"。
     */
    @Test
    fun `bounded read accepts exactly the limit and rejects one byte over`() {
        val limit = 8L
        val exact = ByteArray(8) { 0x41 }
        val got = AttachmentResolver.readBounded(exact.inputStream(), limit, "image_url")
        assertEquals("正好等于上限必须放行", 8, got.size)
        assertTrue("内容不能被截断", got.contentEquals(exact))

        val over = badRequest {
            AttachmentResolver.readBounded(ByteArray(9) { 0x41 }.inputStream(), limit, "image_url")
        }
        assertTrue("超 1 字节也必须中止: $over", over.contains("上限"))
    }

    @Test
    fun `bounded read handles empty stream and zero limit`() {
        assertTrue(
            "空流应得到空数组",
            AttachmentResolver.readBounded(ByteArray(0).inputStream(), 1024, "image_url").isEmpty(),
        )
        // limit<=0 直接短路，不读流
        assertTrue(
            "limit=0 应短路返回空",
            AttachmentResolver.readBounded(ByteArray(100).inputStream(), 0, "image_url").isEmpty(),
        )
    }

    @Test
    fun `bounded read enforces limit across many small chunks without buffering everything`() {
        // 用 16KB 以上的数据走多轮缓冲，确保判定不是「只看第一块」
        val big = ByteArray(64 * 1024) { 0x42 }
        val msg = badRequest { AttachmentResolver.readBounded(big.inputStream(), 1024, "file") }
        assertTrue("跨块累计必须生效: $msg", msg.contains("上限"))

        // 分块但总量在上限内：内容必须完整
        val ok = AttachmentResolver.readBounded(big.inputStream(), big.size.toLong(), "file")
        assertEquals("上限内的分块读取不得丢数据", big.size, ok.size)
        assertTrue("内容必须逐字节一致", ok.contentEquals(big))
    }

    @Test
    fun `mime to extension strips parameters and falls back safely`() {
        // 旧实现直接 substringAfter('/') 且不剥分号 -> "file.plain; charset=utf-8"，
        // 文档按扩展名判类型 -> 解析不出内容（静默失效）
        assertEquals("分号参数必须剥掉", "txt", AttachmentResolver.extFromMime("text/plain; charset=utf-8"))
        assertEquals("大小写不敏感", "png", AttachmentResolver.extFromMime("IMAGE/PNG"))
        assertEquals("前后空白应容忍", "jpg", AttachmentResolver.extFromMime("  image/jpeg  "))
        assertEquals("未知子类型走字符集过滤", "xyz", AttachmentResolver.extFromMime("app/xyz"))
        assertEquals("非法字符被剔除", "abcd", AttachmentResolver.extFromMime("app/a-b c?d"))
        assertEquals("空 / null 退回 bin", "bin", AttachmentResolver.extFromMime(null))
        assertEquals("空串退回 bin", "bin", AttachmentResolver.extFromMime(""))
        assertEquals("缺子类型退回 bin", "bin", AttachmentResolver.extFromMime("text/"))
        assertEquals("无斜杠退回 bin", "bin", AttachmentResolver.extFromMime("plain"))
        assertEquals("超长子类型退回 bin", "bin", AttachmentResolver.extFromMime("app/" + "a".repeat(17)))
        assertEquals("纯符号子类型退回 bin", "bin", AttachmentResolver.extFromMime("app/---"))
        // 长文档类型的映射必须命中（否则 docx 会被当成 bin 走错解析分支）
        assertEquals(
            "docx 必须映射到 docx",
            "docx",
            AttachmentResolver.extFromMime(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ),
        )
    }

    @Test
    fun `limit formatting is human readable`() {
        assertEquals("0B", AttachmentResolver.formatLimit(0))
        assertEquals("1KB", AttachmentResolver.formatLimit(1024))
        assertEquals("64MB", AttachmentResolver.formatLimit(64L * 1024 * 1024))
        assertEquals("1MB", AttachmentResolver.formatLimit(1024L * 1024))
        assertEquals("1536B", AttachmentResolver.formatLimit(1536))
        assertEquals("2KB", AttachmentResolver.formatLimit(2048))
    }

    @Test
    fun `whitelist check runs on the host not the whole url`() {
        // `https://evil.com@cdn.qwen.ai/x` 的 URI.host 是 cdn.qwen.ai（userinfo 被剥离），
        // 这是正确的：真正的连接目标就是 cdn.qwen.ai。这里钉住这个语义，
        // 免得有人"为了安全"改成对整串 URL 做 contains 匹配。
        val uri = java.net.URI("https://evil.com@cdn.qwen.ai/x")
        assertEquals("cdn.qwen.ai", uri.host)
        assertTrue(AttachmentResolver.isHostAllowed(uri.host))

        // 反过来：userinfo 里伪装成白名单、真实目标是别处的，必须被白名单拦下
        val spoof = java.net.URI("https://cdn.qwen.ai@evil.com/x")
        assertEquals("evil.com", spoof.host)
        assertFalse(AttachmentResolver.isHostAllowed(spoof.host))
    }
}
