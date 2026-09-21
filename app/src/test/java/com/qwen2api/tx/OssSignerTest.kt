package com.qwen2api.tx

import com.qwen2api.tx.core.OssSigner
import com.qwen2api.tx.core.Util
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

/**
 * OSS 直传签名（V1）回归测试。
 *
 * ## 这个文件是为一个真实事故补的
 * `/v1/files` 与 `/v1/images/edits` 上传源图时**全部**返回
 * `502 UPLOAD_FAIL ... HTTP 403 SignatureDoesNotMatch`，而链路本身没有任何问题。
 * 根因是 `CanonicalizedOSSHeaders` 里 `x-oss-security-token` 后面写成了 `=`
 * （规范与移植来源 reference/lib/qwen.js 都是 `:`）。
 *
 * 这个 bug 能同时满足「看起来一切正常」和「一定会失败」，原因值得记下来：
 *  - **只有一个字符不同**，且紧跟其后就是几十字符的随机令牌，肉眼比对 canonical 串时几乎隐形；
 *  - **服务端不给任何定位信息**。OSS 是拿自己拼的串去比 HMAC，
 *    差异点对它不可见，所以只回一句 `SignatureDoesNotMatch`，
 *    既不说哪个字段错、也不给期望值；
 *  - **当时的测试结构完全覆盖不到**：canonical 串是 private 方法里的局部变量，
 *    要断言它必须真发一次 PUT，而单测环境没有 OSS 可打 ——
 *    于是整条签名逻辑在 618 条测试里只被"上游拒绝了"这种分支碰到过。
 *
 * 所以这里做两件事：
 *  1. 用**金标准向量**（独立算出的 canonical 串与签名）钉住字节级输出，
 *     任何人把 `:` 改成 `=`（或改动字段顺序、多一个换行）都会立刻红；
 *  2. 显式断言"`=` 形态的签名不能通过"，把这次事故的形态固化成一条负例，
 *     避免以后有人"顺手改成 = 看起来更整齐"。
 */
class OssSignerTest {

    // 与实现无关的固定输入（不是真实凭证，仅用于金标准向量）
    private val secret = "test_secret_key"
    private val securityToken = "CAIS_test_token_0123456789"
    private val bucket = "test-bucket"
    private val contentType = "image/png"
    private val date = "Wed, 28 Dec 2022 10:27:41 GMT"
    private val objectKey = "uploads/2022/photo one.png"

    /** 独立实现的 HMAC-SHA1 → base64，用来交叉验证 [Util.hmacSha1Base64] 本身 */
    private fun independentHmacSha1Base64(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return Base64.getEncoder().encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
    }

    // ---------------- 1. CanonicalizedOSSHeaders 分隔符 ----------------

    @Test
    fun `canonical oss headers use a colon separator`() {
        // 核心断言：这是本次事故的单点根因。
        assertEquals(
            "CanonicalizedOSSHeaders 必须是原始 header 行形态 (name:value)",
            "x-oss-security-token:$securityToken",
            OssSigner.canonicalOssHeaders(securityToken),
        )
    }

    @Test
    fun `equals sign variant is not the canonical form`() {
        // 负例：把事故形态固化成断言。若有人把实现改回 "="，上面那条会红；
        // 这条则进一步说明"两者确实不同"，防止被误当成等价写法合并掉。
        val wrong = "x-oss-security-token=$securityToken"
        assertFalse(
            "用 = 拼出来的串不是合法 canonical 形态",
            wrong == OssSigner.canonicalOssHeaders(securityToken),
        )
    }

    // ---------------- 2. 完整签名串（金标准） ----------------

    @Test
    fun `canonical string matches the golden vector`() {
        // 逐字段钉死：VERB / Content-MD5(空) / Content-Type / Date /
        // CanonicalizedOSSHeaders / CanonicalizedResource，且末行不带换行。
        val expected = listOf(
            "PUT",
            "",
            "image/png",
            "Wed, 28 Dec 2022 10:27:41 GMT",
            "x-oss-security-token:$securityToken",
            "/test-bucket/uploads/2022/photo one.png",
        ).joinToString("\n")

        assertEquals(expected, OssSigner.canonicalString(contentType, date, securityToken, bucket, objectKey))
    }

    @Test
    fun `canonical string carries no trailing newline`() {
        // joinToString 天然不带尾换行；单独断言是因为"多一个 \n"在肉眼上完全看不出来，
        // 但会让 HMAC 结果整体不同 —— 与本次事故同类的一类坑。
        val s = OssSigner.canonicalString(contentType, date, securityToken, bucket, objectKey)
        assertFalse("末尾不能有换行", s.endsWith("\n"))
        assertFalse("开头不能有换行", s.startsWith("\n"))
        assertEquals("必须是 6 行", 6, s.split("\n").size)
    }

    @Test
    fun `canonical resource is bucket plus raw key`() {
        // 签名用**原始** key（空格保持空格），与 URL 的编码形态刻意不同。
        assertEquals("/test-bucket/uploads/2022/photo one.png", OssSigner.canonicalResource(bucket, objectKey))
        assertTrue(
            "签名里的 key 不能被百分号编码",
            OssSigner.canonicalResource(bucket, objectKey).contains(" "),
        )
    }

    // ---------------- 3. 签名值 ----------------

    @Test
    fun `signature matches the golden vector`() {
        val canonical = OssSigner.canonicalString(contentType, date, securityToken, bucket, objectKey)
        val expected = independentHmacSha1Base64(secret, canonical)
        // 金标准值（由 Python hmac/hashlib 独立算出）
        assertEquals("YJrU8iDOzG9sgSmCjG9jnWRsnvQ=", expected)
        assertEquals(expected, OssSigner.signature(secret, canonical))
    }

    @Test
    fun `signature differs when the separator is an equals sign`() {
        // 证明"一个字符之差"确实会改变签名 —— 这正是线上 403 的直接原因。
        val good = OssSigner.canonicalString(contentType, date, securityToken, bucket, objectKey)
        val bad = good.replace("x-oss-security-token:", "x-oss-security-token=")
        assertFalse("两种形态的签名必须不同", OssSigner.signature(secret, good) == OssSigner.signature(secret, bad))
    }

    @Test
    fun `hmac helper agrees with an independent implementation`() {
        // Util.hmacSha1Base64 是自研实现，顺手交叉验证一次，
        // 避免"签名串对了但 HMAC 实现本身错"这种同样只报 403 的情况。
        val canonical = OssSigner.canonicalString(contentType, date, securityToken, bucket, objectKey)
        assertEquals(independentHmacSha1Base64(secret, canonical), Util.hmacSha1Base64(secret, canonical))
    }

    @Test
    fun `signature is stable for a UTF-8 secret and token`() {
        // 中文/非 ASCII 凭证按 UTF-8 参与签名，不能按平台默认编码。
        val c = OssSigner.canonicalString(contentType, date, "令牌", "桶", "目录/文件.png")
        assertEquals(independentHmacSha1Base64("密钥", c), OssSigner.signature("密钥", c))
    }

    // ---------------- 4. object key 归一化 ----------------

    @Test
    fun `bucket prefix is stripped from file path`() {
        assertEquals("2026/a.png", OssSigner.objectKeyOf("test-bucket/2026/a.png", "test-bucket"))
    }

    @Test
    fun `file path without bucket prefix is kept as is`() {
        assertEquals("2026/a.png", OssSigner.objectKeyOf("2026/a.png", "test-bucket"))
    }

    @Test
    fun `only a leading bucket segment is stripped`() {
        // 中间出现同名段不能被剥掉，否则对象会落到错误的 key 上。
        assertEquals("a/test-bucket/b.png", OssSigner.objectKeyOf("a/test-bucket/b.png", "test-bucket"))
    }

    @Test
    fun `empty file path stays empty so the caller can fail early`() {
        assertEquals("", OssSigner.objectKeyOf("", "test-bucket"))
        assertEquals("", OssSigner.objectKeyOf("test-bucket/", "test-bucket"))
    }

    // ---------------- 5. endpoint / URL ----------------

    @Test
    fun `endpoint already containing the bucket is used verbatim`() {
        assertEquals(
            "https://test-bucket.oss-cn-hangzhou.aliyuncs.com",
            OssSigner.endpointFor("https://test-bucket.oss-cn-hangzhou.aliyuncs.com", "test-bucket"),
        )
    }

    @Test
    fun `bare endpoint gets the bucket prefixed as a virtual host`() {
        // 上游只给 region 形态时必须补 bucket，否则请求打到 bucket 根域名。
        // 注意：这种错误同样表现为 403，故单独立测。
        assertEquals(
            "https://test-bucket.oss-cn-hangzhou.aliyuncs.com",
            OssSigner.endpointFor("oss-cn-hangzhou.aliyuncs.com", "test-bucket"),
        )
    }

    @Test
    fun `endpoint with a scheme and trailing slash is normalized`() {
        assertEquals(
            "https://test-bucket.oss-cn-hangzhou.aliyuncs.com",
            OssSigner.endpointFor("https://oss-cn-hangzhou.aliyuncs.com/", "test-bucket"),
        )
    }

    @Test
    fun `object url path percent-encodes each segment and keeps slashes`() {
        // 中文与空格必须编码；斜杠是路径分隔符，不能编码成 %2F。
        assertEquals(
            "uploads/%E4%B8%AD%E6%96%87%20%E7%9B%AE%E5%BD%95/%E5%9B%BE.png",
            OssSigner.objectUrlPath("uploads/中文 目录/图.png"),
        )
        assertFalse(OssSigner.objectUrlPath("a/b").contains("%2F"))
    }

    @Test
    fun `space becomes percent twenty not plus`() {
        // application/x-www-form-urlencoded 默认会把空格变成 "+"，
        // 而 OSS 路径里 "+" 是字面加号 —— 直接错对象名。
        val encoded = OssSigner.objectUrlPath("my file.png")
        assertEquals("my%20file.png", encoded)
        assertFalse("不能出现加号", encoded.contains("+"))
    }

    @Test
    fun `full url combines normalized endpoint and encoded key`() {
        assertEquals(
            "https://test-bucket.oss-cn-hangzhou.aliyuncs.com/uploads/%E4%B8%AD%E6%96%87%20%E7%9B%AE%E5%BD%95/%E5%9B%BE.png",
            OssSigner.urlFor("oss-cn-hangzhou.aliyuncs.com", "test-bucket", "uploads/中文 目录/图.png"),
        )
    }

    @Test
    fun `url is signed against the raw key`() {
        // 关键不变式：签名里的 key 与 URL 里的 key 编码形态不同，
        // 但都指向同一个对象。这里显式记录该差异，避免以后有人"统一"两者。
        val key = "uploads/中文 目录/图.png"
        val canonical = OssSigner.canonicalString(contentType, date, securityToken, bucket, key)
        val url = OssSigner.urlFor("oss-cn-hangzhou.aliyuncs.com", bucket, key)

        assertTrue("签名用原始 key", canonical.contains("/test-bucket/$key"))
        assertFalse("签名里不该出现编码后的 key", canonical.contains("%E4%B8%AD%E6%96%87"))
        assertTrue("URL 用编码后的 key", url.contains("%E4%B8%AD%E6%96%87%20%E7%9B%AE%E5%BD%95"))
    }

    @Test
    fun `golden vector for a non ascii key matches an independent calculation`() {
        // 中文 + 空格 key 的完整金标准（Python 独立算出）
        val key = "uploads/中文 目录/图.png"
        val canonical = OssSigner.canonicalString(contentType, date, securityToken, bucket, key)
        assertEquals(
            listOf(
                "PUT", "", contentType, date,
                "x-oss-security-token:$securityToken",
                "/test-bucket/uploads/中文 目录/图.png",
            ).joinToString("\n"),
            canonical,
        )
        assertEquals("gEqJ4hpkchi+7DU40ZeuewTgvME=", OssSigner.signature(secret, canonical))
    }

    // ---------------- 6. METHOD 常量 ----------------

    @Test
    fun `upload uses the PUT verb in the canonical string`() {
        assertEquals("PUT", OssSigner.METHOD)
        assertTrue(
            "首行必须是 PUT",
            OssSigner.canonicalString(contentType, date, securityToken, bucket, objectKey).startsWith("PUT\n"),
        )
    }

    // ---------------- 8. diagnose：归因器本身必须能看见根因 ----------------

    @Test
    fun `diagnose exposes the separator difference`() {
        // 事故回归：归因器的唯一用途就是指出"我方与服务端哪一段不一样"。
        // 早期实现把整行 token 抹成 `x-oss-security-token[NNNB] head=...**`，
        // 分隔符一起被抹掉，于是最可能的根因成了唯一看不见的东西。
        // 这条用例锁死：`:` 与 `=` 的差异必须在输出里**可见**。
        val mine = "PUT\n\nimage/png\n$date\nx-oss-security-token:$securityToken\n/$bucket/$objectKey"
        val theirs = mine.replace("x-oss-security-token:", "x-oss-security-token=")

        val out = OssSigner.diagnose(mine, theirs)
        assertTrue("必须标出 DIFF", out.contains("DIFF"))
        assertTrue("我方分隔符 : 必须可见", out.contains("x-oss-security-token:"))
        assertTrue("服务端分隔符 = 必须可见", out.contains("x-oss-security-token="))
    }

    @Test
    fun `diagnose never leaks the security token value`() {
        // 归因输出会被写进日志与 HTTP 错误响应，临时凭证不能整条外泄。
        val token = "CAISgQJ1q6Ft5B2yfSjIr5b" + "X".repeat(60)
        val line = "PUT\n\nimage/png\n$date\nx-oss-security-token:$token\n/$bucket/$objectKey"
        val out = OssSigner.diagnose(line, line)

        assertFalse("完整 token 不能出现在输出里", out.contains(token))
        assertTrue("应给出值长度用于判断截断", out.contains("[值长=${token.length}B]"))
        assertTrue("应保留前 8 位以便比对", out.contains(token.take(8)))
    }

    @Test
    fun `diagnose reports all OK when strings match`() {
        val c = OssSigner.canonicalString(contentType, date, securityToken, bucket, objectKey)
        val out = OssSigner.diagnose(c, c)
        assertFalse("完全一致时不该误报 DIFF", out.contains("DIFF"))
    }

    @Test
    fun `diagnose survives a missing server string to sign`() {
        // OSS 未返回 <StringToSign>（例如响应体被中间层改写）时不能抛异常，
        // 否则原本要报 403 详情的地方会变成 500。
        val c = OssSigner.canonicalString(contentType, date, securityToken, bucket, objectKey)
        val out = OssSigner.diagnose(c, null)
        assertTrue("应说明服务端字段缺失", out.contains("服务端字段数=-1"))
    }

    @Test
    fun `server string to sign is extracted and unescaped`() {
        // 服务端把 canonical 串放在 XML 文本节点里，其中 `<` `>` `&` 等会被实体化，
        // 不还原就会拿一个被转义过的串去比对，越比越乱。
        val body = "<Error><Code>SignatureDoesNotMatch</Code>" +
            "<StringToSign>PUT\n\nimage/png\n$date\n" +
            "x-oss-security-token:$securityToken\n/test-bucket/a&lt;b&gt;c&amp;d.png" +
            "</StringToSign></Error>"
        val extracted = OssSigner.serverStringToSign(body)
        assertTrue("必须解出内容", extracted != null)
        assertTrue("XML 实体必须还原", extracted!!.contains("/test-bucket/a<b>c&d.png"))
        assertTrue("换行必须保留", extracted.contains("\n"))
    }

    @Test
    fun `server string to sign returns null when absent`() {
        assertEquals(null, OssSigner.serverStringToSign("<Error><Code>AccessDenied</Code></Error>"))
    }

    // ---------------- 9. 事故复盘锚点（防止归因被再次推翻） ----------------

    @Test
    fun `canonicalized oss headers contain exactly one separator character`() {
        // 现场特征：`x-oss-security-token` 后面**只能有一个**分隔符字符。
        // `:` -> 正确；`=` -> 必然 403。这里把"只有一个字符"的形态钉住，
        // 任何多余/缺失的分隔符（`: :`、`::`、`:=`）都会立刻红。
        val headers = OssSigner.canonicalOssHeaders("TOKENVALUE")
        val afterName = headers.removePrefix("x-oss-security-token")
        assertEquals("分隔符必须恰好一个字符", ":", afterName.take(1))
        assertEquals("分隔符之后必须紧接 token", "TOKENVALUE", afterName.drop(1))
    }

    @Test
    fun `equals separator flips the signature and must never be reintroduced`() {
        // 这次 403 事故的**唯一**根因就是这一个字符。
        // 该断言用真实签名值证明：改回 `=` 会让签名整体变化（即线上必失败），
        // 因此它不是"等价写法"，不能以"看起来更整齐"为由合并回来。
        val goodCanonical = OssSigner.canonicalString(contentType, date, securityToken, bucket, objectKey)
        val badCanonical = goodCanonical.replace(
            "x-oss-security-token:", "x-oss-security-token=",
        )
        assertEquals("替换必须命中（否则断言本身失效）", 1, goodCanonical.split("x-oss-security-token:").size - 1)
        assertFalse(
            "两种分隔符产生不同签名 -> `=` 形态在线上一定 403",
            OssSigner.signature(secret, goodCanonical) == OssSigner.signature(secret, badCanonical),
        )
    }

    @Test
    fun `signature depends on every canonical field`() {
        // 逐字段扰动，确认没有哪一段被实现忽略。
        // 排查时最坑的情形是"某个字段压根没进 HMAC"，
        // 那样服务端怎么算都对不上，而我们改任何别的字段都无效。
        val base = OssSigner.canonicalString(contentType, date, securityToken, bucket, objectKey)
        val variants = mapOf(
            "method" to base.replaceFirst("PUT", "POST"),
            "content-type" to base.replaceFirst(contentType, "image/jpeg"),
            "date" to base.replaceFirst(date, "Wed, 28 Dec 2022 10:27:42 GMT"),
            "token" to base.replaceFirst(securityToken, securityToken + "x"),
            "resource" to base.replaceFirst("/$bucket/", "/other-bucket/"),
        )
        val baseSig = OssSigner.signature(secret, base)
        variants.forEach { (field, variant) ->
            assertFalse("改动 $field 必须改变签名", OssSigner.signature(secret, variant) == baseSig)
        }
    }

    @Test
    fun `empty content md5 line is present and empty`() {
        // 空 Content-MD5 是**一行空字符串**，不是被省略的一行。
        // 少了这一行会让字段整体前移，签名同样对不上 —— 与本次事故同类的坑。
        val lines = OssSigner.canonicalString(contentType, date, securityToken, bucket, objectKey).split("\n")
        assertEquals("第二行必须是空 Content-MD5", "", lines[1])
        assertEquals("第三行是 Content-Type", contentType, lines[2])
        assertEquals("第四行是 Date", date, lines[3])
    }
}
