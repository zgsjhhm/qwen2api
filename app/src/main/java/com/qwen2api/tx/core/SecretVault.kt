package com.qwen2api.tx.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 敏感配置的静态加密（Android Keystore 信封）。
 *
 * 为什么需要：
 *  - `qwenToken` 是 chat.qwen.ai 的登录凭证，`apiKey` 是本网关的 Bearer 密钥。
 *    两者此前以明文写进 SharedPreferences，只要设备解锁后拿到
 *    `adb backup` / root / 应用私有目录任意一种读取途径，就能直接拿走登录态。
 *  - Manifest 里的 allowBackup 原先还是 true（现已关闭），等于给 adb backup 开了门。
 *
 * 做法：
 *  - 用 AndroidKeyStore 里的一把 AES-256-GCM 密钥（不可导出，硬件支持时受 TEE 保护）加密，
 *    存储格式 `enc:v1:<base64(iv || ciphertext+tag)>`；
 *  - 密钥失效（用户清锁屏、恢复出厂迁移等）或解密失败时**回退为明文**并保留原值，
 *    宁可退化成「和以前一样」也不能让用户直接掉登录；
 *  - [decrypt] 遇到没有 `enc:` 前缀的历史明文值原样返回，因此老用户升级无需迁移脚本。
 *
 * 注意：这里防的是「离线读取存储」，不防「已 root 且应用正在运行」——
 * 那种场景下 Keystore 密钥同样可被调用，属于移动端静态加密的固有限制。
 *
 * 【降级可见性】
 * [lastDegraded] 是唯一的"加密没起作用"信号。此前它只被本对象写入、**没有任何消费者**，
 * 而 [decrypt] 在密文损坏时直接 `return ""` 却**不设该标志** —— 于是
 * 「数据被截断/泄漏」和「这个值本来就是空」在调用方看来完全一样：
 * [PrefsConfigRepository.load] 见到空 apiKey 就会重新生成一把，
 * 用户所有客户端集体 401，而界面上没有任何解释。现在所有异常退出路径都置位。
 */
internal object SecretVault {

    /** 存储前缀（持久化格式的一部分，不可随意变更） */
    const val PREFIX = "enc:v1:"

    const val IV_BYTES = 12
    const val GCM_TAG_BITS = 128

    /** GCM 认证标签长度（字节），用于信封最小长度校验 */
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "qwen2api_config_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    /**
     * 上次操作是否降级为明文（供 UI/日志提示，避免静默失效）。
     *
     * 【语义：latch（保持型），不是"本次调用结果"】
     * 一旦发生过降级就保持为 true，只有**一次成功的真实加密**才能清掉它。
     * 不能设计成"每次调用都重算"，否则下面两条正常路径会立刻抹掉警告：
     *  1. `PrefsConfigRepository.save()` 是 `encrypt(apiKey)` 紧跟 `encrypt(qwenToken)`；
     *     `qwenToken` 常常为空，空值不碰 Keystore —— 如果空值把标志清零，
     *     刚刚以明文落盘的 apiKey 就被"洗白"了，用户永远收不到提示；
     *  2. `load()` 里对历史明文值的 `decrypt()` 是正常路径（老用户升级后必然走到），
     *     它同样不能清标志，否则"保存时降级"的警告会被下一次读取抹掉。
     */
    @Volatile
    var lastDegraded: Boolean = false
        private set

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) {
            // 空值不碰 Keystore，因此这条路径**不改变**降级标志。
            return plain
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.ENCRYPT_MODE, secretKey())
            }
            val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val payload = encodePayload(cipher.iv, sealed)
            // 只有真正加密成功才能清掉降级警告 —— 这是唯一的"恢复正常"证据。
            lastDegraded = false
            PREFIX + b64(payload)
        } catch (e: Throwable) {
            // Keystore 不可用（极少数定制 ROM/测试环境）：退回明文，至少功能不受影响
            lastDegraded = true
            plain
        }
    }

    fun decrypt(stored: String): String {
        if (!stored.startsWith(PREFIX)) {
            // 历史明文：这是一条**完全正常**的读取路径（老用户升级后第一次就会走到）。
            // 不能在这里置位 lastDegraded，否则每个老用户都会看到"加密不可用"的误报。
            return stored
        }
        return try {
            val payload = unb64(stored.removePrefix(PREFIX))
            val (iv, body) = parsePayload(payload)
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Throwable) {
            // 密钥失效/数据被篡改：返回空串让上层重新要求输入 token，
            // 好过抛异常炸在没有 UI 的地方。但**必须标记降级** ——
            // 空值本身不携带"为什么是空"的信息，没有这个标志就无法区分
            // "用户没配" 与 "密文坏了"。
            lastDegraded = true
            ""
        }
    }

    /**
     * 组装信封：`iv || sealed`。
     * 抽成纯函数是为了让 [SecretVaultTest] 能在没有 AndroidKeyStore 的 JVM 上
     * 校验字节布局 —— 布局错了在真机上表现是"偶发解密失败"，很难定位。
     */
    internal fun encodePayload(iv: ByteArray, sealed: ByteArray): ByteArray {
        val payload = ByteArray(iv.size + sealed.size)
        System.arraycopy(iv, 0, payload, 0, iv.size)
        System.arraycopy(sealed, 0, payload, iv.size, sealed.size)
        return payload
    }

    /**
     * 拆解信封并做**长度合法性校验**。
     *
     * 旧实现只有一个 `payload.size <= IV_BYTES` 的判断，然后直接
     * `copyOfRange`；长度刚好等于 IV、或密文体短于一个 GCM tag 时，
     * 会把明显非法的输入送进 Cipher，报错信息完全无法指向真实原因。
     *
     * @throws IllegalArgumentException 信封长度不足时
     */
    internal fun parsePayload(payload: ByteArray): Pair<ByteArray, ByteArray> {
        val min = IV_BYTES + GCM_TAG_BYTES
        if (payload.size < min) {
            throw IllegalArgumentException(
                "加密信封长度不足: ${payload.size} 字节 (至少需要 $min: " +
                    "iv=$IV_BYTES + tag=$GCM_TAG_BYTES)",
            )
        }
        return payload.copyOfRange(0, IV_BYTES) to payload.copyOfRange(IV_BYTES, payload.size)
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun b64(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)

    private fun unb64(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
}
