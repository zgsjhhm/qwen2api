package com.qwen2api.tx.core

/**
 * 阿里云 OSS 直传的**纯签名逻辑**（V1 签名，配合 STS 临时凭证）。
 *
 * ## 为什么要从 `QwenClient.ossPut` 里拆出来
 * 这段逻辑以前整体内联在 `ossPut` 的 private 方法体里，于是出现了两个后果：
 *
 *  1. **签名字符串无法被任何测试看到**。它只是方法里临时拼出来的一个局部 String，
 *     要断言它就必须真的发一次 PUT —— 而单元测试里没有 OSS 可打，
 *     结果就是整条上传链路在测试里只覆盖到「上游拒绝」的分支。
 *  2. 出错的**报错面极窄**。签名错、bucket 拼错、URL 与签名不一致、key 编码错，
 *     服务端一律只回 `403 SignatureDoesNotMatch`，既不说哪个字段错也不给期望值
 *     （服务端是拿自己拼的串去比 HMAC，差异点对它本身不可见）。
 *     没有本地断言，就只能靠肉眼比对 canonical 串 —— 而 `=` / `:` 这类
 *     单字符差异在肉眼比对时几乎不可见，实测就漏掉过一次（见
 *     [canonicalString] 的注释）。
 *
 * 因此这里把「拼串 → 签名 → 拼 URL」三步抽成可独立断言的纯函数，
 * [QwenClient] 只负责发请求。
 *
 * ## 与 OSS V4 的关系
 * OSS 现在推荐 V4（`oss.AuthVersion(oss.AuthV4)`），但本工程的上游凭证是
 * chat.qwen.ai 的 `getstsToken` 返回的 STS 三元组，其返回值里**没有 region 之外的
 * 签名所需信息**，而 V4 的 scope 强依赖 region / 规范化查询串 / x-oss-content-sha256。
 * 移植来源 reference/lib/qwen.js 用的是 V1 手写签名且被验证可用，
 * 这里保持同策略，不引入 V4 —— 换算法属于另一个独立变更，不该混在修 bug 里。
 */
internal object OssSigner {

    /** V1 签名的 HTTP 方法，上传固定 PUT */
    const val METHOD = "PUT"

    /**
     * 构造 CanonicalizedOSSHeaders（本次只有一个 header，即 STS 安全令牌）。
     *
     * ## 分隔符按官方规范取 `:`
     * `CanonicalizedOSSHeaders` 的语义是「原始 header 行的集合」，
     * 形如 `name:value`（官方示例：`x-oss-meta-author:alice\n/examplebucket/nelson`）。
     *
     * ## 这里就是那次 403 事故的根因，别再改回 `=`
     * 这里曾写成 `=`（`x-oss-security-token=<token>`），导致 `/v1/files` 与
     * `/v1/images/edits` 的源图上传**必然**返回 `403 SignatureDoesNotMatch`。
     * 改成 `:` 后两条路径端到端实测均返回 200（2026-09-20 真机复现）。
     *
     * 排查时曾被误判为"`=` 不是根因"：当时改完 `:` 再测仍然 403，
     * 于是得出了"分隔符无关"的结论。真因是**那次测的还是旧 APK** ——
     * 构建产物已产出，但 `pm install` 静默失败，手机上跑的一直是改动前的包
     * （`pm path` 的 mtime 比对才暴露：装的是 12:25 的包，而构建产物是 13:06 的）。
     * 参照系错了，结论就反了。教训：改完必须核对**装机产物的 hash/mtime**，
     * 而不是只看构建日志说了 BUILD SUCCESSFUL。
     *
     * 注意 `reference/lib/qwen.js` 里那份 JS 用的是 `=` 且据称能跑通，
     * 这点至今无解释；但 OSS 官方规范与真机实测都指向 `:`，
     * 以规范 + 实测为准。遇到签名问题的归因手段见 [diagnose]。
     */
    fun canonicalOssHeaders(securityToken: String): String =
        "x-oss-security-token:" + securityToken

    /**
     * 字段级归因：把**我们自己算的 canonical 串**与**服务端实际使用的串**做逐行 diff。
     *
     * OSS 的 `SignatureDoesNotMatch` 响应里带 `<StringToSign>`，就是服务端拿去算 HMAC 的原文。
     * 两边一比，差异行会直接暴露是 Date 格式、Content-Type、bucket 前缀还是 token 换行的问题；
     * 靠猜 canonical 串的话，`=` / `:` 这种单字符差异几乎是隐形的，实测白跑一轮真机。
     *
     * ## 掩码为什么必须保留分隔符
     * 早期实现把整行 token 重写成 `x-oss-security-token[NNNB] head=xxxxxxxx**`，
     * 这会**把分隔符本身抹掉** —— 而这次 403 事故的根因恰恰就是那一个字符。
     * 于是最容易出问题的字段成了唯一看不见的字段：两边都显示成
     * `x-oss-security-token[1120B] head=CAISgQ**`，DIFF 标记亮着却无法解释，
     * 专为定位该 bug 造的工具看不见该 bug。
     * 正确做法是**只掩码值，不碰键名与分隔符**：`x-oss-security-token:<len>B:<head>**`。
     *
     * 返回值里**绝不含 access_key_secret**；security token 只保留长度与前 8 位，
     * 足以判断"是否多/少字符"与"分隔符对错"，又不会把临时凭证整条写进日志。
     */
    fun diagnose(canonical: String, serverStringToSign: String?): String {
        fun mask(line: String): String {
            val key = "x-oss-security-token"
            val at = line.indexOf(key)
            if (at < 0) return line
            // 键名之后连续的 `:` / `=` 全部算分隔符，原样呈现。
            // 键名前的缩进/其它内容保持不动，值只留"头部 + 长度"。
            var i = at + key.length
            val sep = StringBuilder()
            while (i < line.length && (line[i] == ':' || line[i] == '=')) {
                sep.append(line[i]); i++
            }
            val value = line.substring(i)
            val head = if (value.length >= 8) value.take(8) + "**" else value
            return line.substring(0, at) + key + sep + head + " [值长=${value.length}B]"
        }

        val mine = canonical.split("\n")
        val theirs = serverStringToSign?.split("\n")
        val sb = StringBuilder("OSS 签名归因:")
        sb.append("\n  我方字段数=").append(mine.size)
        sb.append(" 服务端字段数=").append(theirs?.size ?: -1)
        val n = maxOf(mine.size, theirs?.size ?: 0)
        for (i in 0 until n) {
            val a = mine.getOrNull(i)
            val b = theirs?.getOrNull(i)
            val ok = a == b
            sb.append("\n  [").append(i).append("] ").append(if (ok) "OK  " else "DIFF")
            sb.append(" 我方=").append(a?.let { mask(it) } ?: "<缺失>")
            if (!ok) sb.append("  服务端=").append(b?.let { mask(it) } ?: "<缺失>")
        }
        return sb.toString()
    }

    /**
     * 从 OSS 的错误响应体里取出服务端实际使用的 `StringToSign`。
     *
     * OSS V1 签名失败时会把这串原文回给客户端（XML 文本节点，换行是字面换行）：
     * ```
     * <Error><Code>SignatureDoesNotMatch</Code>
     *   <StringToSign>PUT\n\nimage/png\nSat, 20 Sep 2026 ...\nx-oss-security-token:xxx\n/bucket/key</StringToSign>
     * </Error>
     * ```
     * 它是**唯一**能无歧义定位签名差异的输入；靠读代码猜 canonical 串就是在赌博。
     */
    fun serverStringToSign(errorBody: String): String? {
        val m = Regex("<StringToSign>([\\s\\S]*?)</StringToSign>").find(errorBody) ?: return null
        return m.groupValues[1]
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&amp;", "&")
    }

    /**
     * 构造 CanonicalizedResource：`/<bucket>/<objectKey>`。
     * objectKey 用**未编码**的原始形态（与 ali-oss SDK 一致），
     * URL 里的百分号编码只作用于实际请求行，二者刻意不一致。
     */
    fun canonicalResource(bucket: String, objectKey: String): String = "/$bucket/$objectKey"

    /**
     * 完整签名串：
     * ```
     * VERB \n Content-MD5 \n Content-Type \n Date \n CanonicalizedOSSHeaders \n CanonicalizedResource
     * ```
     * Content-MD5 为空（本次不做内容校验），因此第二段是空字符串。
     *
     * 最后一行**不带**结尾换行 —— `joinToString` 天然满足，官方示例也如此。
     */
    fun canonicalString(
        contentType: String,
        date: String,
        securityToken: String,
        bucket: String,
        objectKey: String,
    ): String = listOf(
        METHOD,
        "",                      // Content-MD5
        contentType,
        date,
        canonicalOssHeaders(securityToken),
        canonicalResource(bucket, objectKey),
    ).joinToString("\n")

    /** 计算签名：base64(HMAC-SHA1(AccessKeySecret, canonicalString)) */
    fun signature(accessKeySecret: String, canonical: String): String =
        Util.hmacSha1Base64(accessKeySecret, canonical)

    /**
     * 从 `file_path` 推出 object key：剥掉可能存在的 `bucket/` 前缀。
     *
     * 上游 `file_path` 有时带 bucket 前缀（`mybucket/2026/a.png`），
     * 有时不带（`2026/a.png`）。不剥离就会把 bucket 名字重复塞进 key，
     * 表现为「上传成功但对象落到 `mybucket/mybucket/...`」或直接 403。
     */
    fun objectKeyOf(filePath: String, bucket: String): String {
        var key = filePath
        val prefix = "$bucket/"
        if (key.startsWith(prefix)) key = key.substring(prefix.length)
        return key
    }

    /**
     * endpoint 归一化：保证是 `https://<bucket>.<host>` 形态（虚拟主机式）。
     *
     * 上游返回的 endpoint 有两种形态：已含 bucket 的完整虚拟主机地址，
     * 或只有 `<region>.aliyuncs.com`。后者需要补 bucket 前缀，
     * 否则请求会打到 bucket 根域名上，报的仍然是 403 —— 与本 bug 症状相同。
     */
    fun endpointFor(rawEndpoint: String, bucket: String): String {
        val endpoint = rawEndpoint.trimEnd('/')
        if (endpoint.contains(bucket)) return endpoint
        return "https://" + bucket + "." + endpoint.replace(Regex("^https?://"), "")
    }

    /**
     * 按段百分号编码 object key（中文文件名必需），段间分隔符 `/` 保留。
     *
     * 注意与 [canonicalResource] 的分工：**签名用原始 key，URL 用编码后的 key**。
     * 这里与 ali-oss SDK 策略一致；两边都用编码值同样会得到 403。
     */
    fun objectUrlPath(objectKey: String): String =
        objectKey.split("/").joinToString("/") { encodeUriComponent(it) }

    /** 完整请求 URL */
    fun urlFor(endpoint: String, bucket: String, objectKey: String): String =
        endpointFor(endpoint, bucket) + "/" + objectUrlPath(objectKey)

    /**
     * 百分号编码（application/x-www-form-urlencoded，空格转 `%20` 而非 `+`）。
     * 原实现内联在 QwenClient 里，抽出来是为了让编码结果可被断言。
     */
    fun encodeUriComponent(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
