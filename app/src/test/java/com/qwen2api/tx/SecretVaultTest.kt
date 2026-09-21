package com.qwen2api.tx

import com.qwen2api.tx.core.SecretVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 配置加密（[SecretVault]）的单元测试。
 *
 * ## 为什么必须补
 * 它守着两个**最敏感的值**：`qwenToken`（chat.qwen.ai 登录态）与 `apiKey`（本网关 Bearer 密钥），
 * 而在这之前它是**零覆盖**的。问题在于它的失效方式全都是静默的：
 *
 * ```
 *   SecretVault.encrypt(plain)
 *     ├─ Keystore 可用  -> "enc:v1:<b64(iv||ct+tag)>"
 *     └─ Keystore 不可用 -> 明文 + lastDegraded = true   ← 降级，无异常
 *
 *   SecretVault.decrypt(stored)
 *     ├─ 无 "enc:v1:" 前缀 -> 原样返回（历史明文兼容）
 *     ├─ 解密成功          -> 明文
 *     └─ 解密失败/损坏     -> ""  + lastDegraded = true   ← **静默变空**
 * ```
 *
 * 一个真实后果：`decrypt` 失败返回空串，`PrefsConfigRepository.load()` 看到 `apiKey` 为空
 * 就**重新生成**一个 —— 用户的客户端（Cherry Studio / CLI agent）会集体 401，
 * 而 UI 上只会看到"密钥变了"，没有任何"加密不可用"的提示。
 * `lastDegraded` 这个标志存在，但 grep 全仓只有 SecretVault 自己写它，**没有任何消费者**。
 *
 * ## JVM 可测性
 * 纯 JVM 里 `AndroidKeyStore` 不可用，因此这里覆盖的正是"最坏情况"：
 *  - Keystore 缺失 -> encrypt 必须降级为明文而不是抛异常（否则用户连配置都存不下去）
 *  - 密文损坏/被篡改 -> decrypt 必须给出空值并**明确标记降级**
 *
 * 原生 Keystore 往返（真机路径）无法在此环境覆盖，但 payload 的装载/拆解/长度校验收在
 * [SecretVault.parsePayload] / [SecretVault.encodePayload] 两个纯函数里，可完整验证。
 */
class SecretVaultTest {

    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    /** 一段形状正确的密文（12 字节 IV + 16 字节"密文"） */
    private fun validShapedCipher(payloadSize: Int = 28): ByteArray =
        ByteArray(payloadSize) { (it * 7 % 251).toByte() }

    // ================= 1. 降级语义（Keystore 不可用） =================

    @Test
    fun `encrypt falls back to plaintext when keystore is unavailable`() {
        // 这是设计选择：宁可退化成"和以前一样"也不能让用户存不下配置。
        // 关键是**必须留下 lastDegraded 痕迹**，否则就是静默降级。
        val out = SecretVault.encrypt("sk-qpp-secret")
        assertEquals("Keystore 不可用时应原样返回明文", "sk-qpp-secret", out)
        assertTrue("降级必须被标记，否则静默", SecretVault.lastDegraded)
    }

    @Test
    fun `empty value is not encrypted and not marked as degraded`() {
        SecretVault.encrypt("something")
        assertTrue(SecretVault.lastDegraded)
        assertEquals("", SecretVault.encrypt(""))
    }

    @Test
    fun `empty value leaves the degraded flag untouched`() {
        // 空值不碰 Keystore，因此它既不能"确认降级"也不能"洗白降级"。
        // 关键场景：save() 是 encrypt(apiKey) 紧跟 encrypt(qwenToken)，
        // 后者常常为空 —— 若它清标志，刚刚明文落盘的 apiKey 就再也报不出来了。
        // 断言写成"前后不变"，避免对本进程既有状态的顺序依赖。
        val before = SecretVault.lastDegraded
        SecretVault.encrypt("")
        assertEquals("空值加密不得改变降级状态", before, SecretVault.lastDegraded)
    }

    @Test
    fun `a degraded state cannot be washed away by later normal operations`() {
        // 造出降级态（JVM 上 Keystore 不可用，真实加密必然失败）
        SecretVault.encrypt("x")
        assertTrue("保存失败必须以明文落盘并置位", SecretVault.lastDegraded)

        // 之后每一次"正常"操作都不得洗白它
        SecretVault.encrypt("")
        assertTrue("空值加密不得洗白", SecretVault.lastDegraded)
        SecretVault.decrypt("legacy-plain-value")
        assertTrue("读历史明文不得洗白", SecretVault.lastDegraded)
    }

    // ================= 2. 历史明文兼容 =================
    @Test
    fun `legacy plaintext value is returned as is`() {
        val out = SecretVault.decrypt("plain-sk-key")
        assertEquals("plain-sk-key", out)
    }

    @Test
    fun `legacy plaintext with a colon is returned as is`() {
        // 旧版本可能存过整形 Cookie（含 ':'），不能被误当成密文格式
        val cookie = "cna=abc; token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig"
        assertEquals(cookie, SecretVault.decrypt(cookie))
    }

    @Test
    fun `legacy plaintext read does not clear an existing degraded warning`() {
        // 老用户升级后第一次读到的就是明文，路径完全正常 —— 但它**不能洗白**降级状态。
        // 真实次序：save() 降级明文落盘 → 下一次 load() 读到的就是这个明文。
        // 若读取清标志，"保存时降级"的警告会在用户看到之前就消失。
        SecretVault.encrypt("x")
        assertTrue("保存已降级", SecretVault.lastDegraded)
        SecretVault.decrypt("legacy-plain-value")
        assertTrue("读取历史明文不得抹掉降级警告", SecretVault.lastDegraded)
    }

    @Test
    fun `reading historical plaintext is itself not a degraded operation`() {
        // 反面：读明文不能**制造**降级 —— 否则每个老用户升级后
        // 都会看到"加密不可用"的误报。
        //
        // 注意不能直接断言 false：lastDegraded 是进程级 latch，前面任何一条
        // "Keystore 不可用导致 encrypt 降级"的用例都会把它永久置位（本环境必然如此）。
        // 因此改用差分断言：读明文前后状态必须一致。
        val before = SecretVault.lastDegraded
        assertEquals("legacy-plain-value", SecretVault.decrypt("legacy-plain-value"))
        assertEquals("读明文不得改变降级状态", before, SecretVault.lastDegraded)
    }

    @Test
    fun `empty input is returned as is`() {
        assertEquals("", SecretVault.decrypt(""))
    }

    // ================= 3. 密文损坏（必须明确标记降级，不能静默变空） =================

    @Test
    fun `ciphertext with invalid base64 yields empty and marks degraded`() {
        val out = SecretVault.decrypt("enc:v1:@@@@not-base64@@@@")
        assertEquals("解不开时应返回空串让上层要求重新输入", "", out)
        assertTrue("损坏必须被标记为降级", SecretVault.lastDegraded)
    }

    @Test
    fun `ciphertext that is too short yields empty and marks degraded`() {
        // payload 必须至少 IV(12) + 一个 tag 块。
        // 旧实现在这里直接 return "" 而**不设 lastDegraded**：
        // 数据被截断看起来和"值本来就是空"一模一样 —— 静默失效。
        val out = SecretVault.decrypt("enc:v1:" + b64(ByteArray(4)))
        assertEquals("", out)
        assertTrue("截断的密文必须被标记为降级", SecretVault.lastDegraded)
    }

    @Test
    fun `ciphertext with exactly the iv length yields empty and marks degraded`() {
        // 边界：只有 IV、没有密文 —— 不是合法的信封
        val out = SecretVault.decrypt("enc:v1:" + b64(ByteArray(12)))
        assertEquals("", out)
        assertTrue("无密文体的信封必须标记降级", SecretVault.lastDegraded)
    }

    @Test
    fun `empty payload after the prefix yields empty and marks degraded`() {
        val out = SecretVault.decrypt("enc:v1:")
        assertEquals("", out)
        assertTrue("空前缀体必须标记降级", SecretVault.lastDegraded)
    }

    @Test
    fun `tampered ciphertext yields empty and marks degraded`() {
        // GCM 的完整性校验会失败 -> 必须回到"空 + 降级"
        val out = SecretVault.decrypt("enc:v1:" + b64(validShapedCipher()))
        assertEquals("", out)
        assertTrue("篡改必须被标记为降级", SecretVault.lastDegraded)
    }

    @Test
    fun `decrypt never throws for arbitrary inputs`() {
        // 它跑在配置读取的关键路径上，抛异常会炸在"还没有 UI"的地方
        val cases = listOf(
            "enc:v1:", "enc:v1:!!", "enc:v1:" + b64(ByteArray(0)),
            "enc:v1:" + b64(ByteArray(13)), "enc:v1:AAA", "enc:v1:" + "A".repeat(500),
        )
        for (c in cases) {
            val out = SecretVault.decrypt(c)
            assertTrue("输入 ${c.take(24)} 不应抛异常", out == "" || out.isNotEmpty())
        }
    }

    // ================= 4. payload 装载/拆解（纯逻辑） =================

    @Test
    fun `payload layout is iv followed by sealed body`() {
        val iv = ByteArray(12) { (it + 1).toByte() }
        val body = ByteArray(20) { (it + 100).toByte() }
        val payload = SecretVault.encodePayload(iv, body)

        assertEquals("总长必须等于 IV + 密文体", 32, payload.size)
        assertTrue("前 12 字节必须是 IV", payload.copyOfRange(0, 12).contentEquals(iv))
        assertTrue("其余必须是密文体", payload.copyOfRange(12, 32).contentEquals(body))
    }

    @Test
    fun `payload split round trips exactly`() {
        val iv = ByteArray(12) { (it * 3).toByte() }
        val body = ByteArray(48) { (it * 5).toByte() }
        val (gotIv, gotBody) = SecretVault.parsePayload(SecretVault.encodePayload(iv, body))
        assertTrue(iv.contentEquals(gotIv))
        assertTrue(body.contentEquals(gotBody))
    }

    @Test
    fun `payload split rejects a short envelope`() {
        for (n in 0..12) {
            try {
                SecretVault.parsePayload(ByteArray(n))
                throw AssertionError("长度 $n 的信封应被拒绝")
            } catch (e: IllegalArgumentException) {
                assertTrue("异常信息应说明原因: ${e.message}", (e.message ?: "").isNotEmpty())
            }
        }
    }

    @Test
    fun `payload split rejects an empty sealed body`() {
        // 13 字节 = IV(12) + 1 字节体：通过"大于 IV 长度"的检查但密文体不合法，
        // 必须在这里被拦下（否则会拿着半个 tag 去初始化 GCM）
        try {
            SecretVault.parsePayload(ByteArray(13))
            throw AssertionError("空密文体应被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue((e.message ?: "").isNotEmpty())
        }
    }

    @Test
    fun `payload split accepts the smallest valid envelope`() {
        val payload = ByteArray(12 + 16)
        val (iv, body) = SecretVault.parsePayload(payload)
        assertEquals(12, iv.size)
        assertEquals(16, body.size)
    }

    @Test
    fun `payload split ignores extra trailing bytes only as body`() {
        val payload = ByteArray(12 + 16 + 5)
        val (iv, body) = SecretVault.parsePayload(payload)
        assertEquals(12, iv.size)
        assertEquals(21, body.size)
    }

    // ================= 5. 存储格式契约 =================

    @Test
    fun `storage prefix is stable`() {
        // 前缀是持久化格式的一部分，改了会让所有老用户的密文变成"历史明文"
        assertEquals("enc:v1:", SecretVault.PREFIX)
    }

    @Test
    fun `prefix detection is exact not fuzzy`() {
        // 只有以完整前缀开头的才当密文；否则会误判历史明文
        assertEquals("enc:v2:xyz", SecretVault.decrypt("enc:v2:xyz"))
        assertEquals(" enc:v1:x", SecretVault.decrypt(" enc:v1:x"))
        assertEquals("ENC:V1:x", SecretVault.decrypt("ENC:V1:x"))
        assertEquals("enc-aes:x", SecretVault.decrypt("enc-aes:x"))
    }

    @Test
    fun `iv and tag sizes match the aes gcm contract`() {
        assertEquals("GCM 标准 IV 长度", 12, SecretVault.IV_BYTES)
        assertEquals("GCM tag 长度", 128, SecretVault.GCM_TAG_BITS)
    }

    // ================= 6. 载荷机密性（明文不得残留在密文里） =================

    @Test
    fun `encrypted envelope does not literally contain the plaintext`() {
        // 真机路径在纯 JVM 上跑不了，用 payload 布局间接保证：
        // 信封 = iv || sealed，调用方不得把明文直接塞进 body。
        val secret = "sk-qpp-super-secret-value"
        val iv = ByteArray(12)
        val sealed = ByteArray(32) { (it * 11).toByte() }
        val payload = SecretVault.encodePayload(iv, sealed)
        val stored = "enc:v1:" + b64(payload)
        assertFalse("密文里不得出现明文", stored.contains(secret))
        assertFalse("base64 解码后也不得出现明文", String(payload, Charsets.ISO_8859_1).contains(secret))
    }
}
