package com.qwen2api.tx.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个可被路由使用的 Qwen 上游账号。
 *
 * 凭证形态与 [GatewayConfig.qwenToken] 完全一致：既可以是 JWT（`eyJ...`），
 * 也可以是整段 Cookie。不做形态区分是因为 [QwenClient] 的 `headers()` 已经
 * 按前缀自动决定放进 `Authorization` 还是 `Cookie`，这里再分一次类只会多一份
 * 可能分叉的判据。
 */
data class QwenAccount(
    val id: String,
    val label: String,
    val credential: String,
    val enabled: Boolean = true,
    val createdAt: Long = 0L,
    /** 最近一次被选中使用的时刻（用于轮转公平性：优先选最久没用的） */
    val lastUsedAt: Long = 0L,
    /** 最近一次失败文案（空串 = 无失败记录，即"当前健康"） */
    val lastError: String = "",
    /** 最近一次失败时刻，与配置里的冷却时长一起决定"是否还在冷却" */
    val lastErrorAt: Long = 0L,
    /** 最近一次失败的错误码，用于冷却后回显"上次为什么失败" */
    val lastErrorCode: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("credential", credential)
        .put("enabled", enabled)
        .put("createdAt", createdAt)
        .put("lastUsedAt", lastUsedAt)
        .put("lastError", lastError)
        .put("lastErrorAt", lastErrorAt)
        .put("lastErrorCode", lastErrorCode)

    /** 展示用标签：用户没填名字时用掩码凭证兜底，避免列表里出现一行空白 */
    fun displayName(): String =
        label.ifBlank { "账号 " + ConfigStore.maskToken(credential).ifBlank { id } }

    companion object {
        fun fromJson(o: JSONObject): QwenAccount = QwenAccount(
            id = o.optString("id"),
            label = o.optString("label"),
            credential = o.optString("credential"),
            enabled = o.optBoolean("enabled", true),
            createdAt = o.optLong("createdAt"),
            lastUsedAt = o.optLong("lastUsedAt"),
            lastError = o.optString("lastError"),
            lastErrorAt = o.optLong("lastErrorAt"),
            lastErrorCode = o.optString("lastErrorCode"),
        )
    }
}

/**
 * 账号仓储抽象。
 *
 * 与 [ConfigRepository] 同样必须抽成接口：多账号切换的回归测试要能在普通 JVM 上
 * 跑（见 `AccountRoutingTest`），而生产实现依赖 SharedPreferences —— 直接依赖具体类
 * 会让这部分逻辑只能在真机上验证。
 */
interface AccountRepository {
    fun all(): List<QwenAccount>

    fun find(id: String): QwenAccount?

    /** 新增或按 id 覆盖 */
    fun upsert(acc: QwenAccount): QwenAccount

    fun remove(id: String): QwenAccount?

    /** 回写健康度（成功后清空失败记录，失败后记录错误与时刻） */
    fun noteResult(id: String, ok: Boolean, code: String = "", message: String = ""): QwenAccount?
}

/** 生产实现：SharedPreferences（凭证经 [SecretVault] 静态加密后落盘） */
class PrefsAccountRepository(context: Context) : AccountRepository {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var cached: List<QwenAccount>? = null

    @Synchronized
    override fun all(): List<QwenAccount> {
        cached?.let { return it }
        val list = parse(SecretVault.decrypt(prefs.getString(KEY_ACCOUNTS, "") ?: ""))
        cached = list
        return list
    }

    @Synchronized
    override fun find(id: String): QwenAccount? = all().firstOrNull { it.id == id }

    @Synchronized
    override fun upsert(acc: QwenAccount): QwenAccount {
        val cur = all().toMutableList()
        val idx = cur.indexOfFirst { it.id == acc.id }
        if (idx >= 0) cur[idx] = acc else cur.add(acc)
        persist(cur)
        return acc
    }

    @Synchronized
    override fun remove(id: String): QwenAccount? {
        val cur = all().toMutableList()
        val idx = cur.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val gone = cur.removeAt(idx)
        persist(cur)
        return gone
    }

    @Synchronized
    override fun noteResult(id: String, ok: Boolean, code: String, message: String): QwenAccount? {
        val cur = all().toMutableList()
        val idx = cur.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val now = System.currentTimeMillis()
        val next = if (ok) {
            // lastErrorAt 必须一起归零：冷却判定是 `lastErrorAt + cooldownMs > now`，
            // 只清文案不请时刻，账号会在"看起来健康"的同时继续被判为冷却中 ——
            // 于是它永远排在候选末尾，表现为"这个账号明明好了却再也不被用"。
            cur[idx].copy(lastError = "", lastErrorCode = "", lastErrorAt = 0L)
        } else {
            cur[idx].copy(
                lastError = message.take(300),
                lastErrorCode = code,
                lastErrorAt = now,
            )
        }
        cur[idx] = next
        persist(cur)
        return next
    }

    private fun persist(list: List<QwenAccount>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_ACCOUNTS, SecretVault.encrypt(arr.toString())).apply()
        cached = list
    }

    /**
     * 解析历史数据。
     *
     * 单条损坏不能让整份账号列表消失 —— 账号是用户手工配置的资产，
     * "因为第 3 条 JSON 有问题所以 1、2 条也没了"是不可接受的。因此逐条 try。
     */
    private fun parse(raw: String): List<QwenAccount> {
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o -> runCatching { QwenAccount.fromJson(o) }.getOrNull() }
            }.filter { it.id.isNotBlank() && it.credential.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val PREF_NAME = "qwen2api_accounts"
        const val KEY_ACCOUNTS = "accounts"
    }
}

/** 内存实现：测试与"日志/UI 的纯内存模式"用 */
class MemoryAccountRepository(initial: List<QwenAccount> = emptyList()) : AccountRepository {

    @Volatile
    private var items: List<QwenAccount> = initial

    override fun all(): List<QwenAccount> = items

    override fun find(id: String): QwenAccount? = items.firstOrNull { it.id == id }

    @Synchronized
    override fun upsert(acc: QwenAccount): QwenAccount {
        items = items.filterNot { it.id == acc.id } + acc
        return acc
    }

    @Synchronized
    override fun remove(id: String): QwenAccount? {
        val gone = find(id) ?: return null
        items = items.filterNot { it.id == id }
        return gone
    }

    @Synchronized
    override fun noteResult(id: String, ok: Boolean, code: String, message: String): QwenAccount? {
        val cur = find(id) ?: return null
        val now = System.currentTimeMillis()
        val next = if (ok) {
            cur.copy(lastError = "", lastErrorCode = "", lastErrorAt = 0L)
        } else {
            cur.copy(lastError = message.take(300), lastErrorCode = code, lastErrorAt = now)
        }
        items = items.map { if (it.id == id) next else it }
        return next
    }
}

/**
 * 账号仓库门面（与 [ConfigStore] 同构：UI 与网关共享同一份实现）。
 */
object AccountStore {

    @Volatile
    private var defaultRepo: AccountRepository? = null

    fun repository(context: Context): AccountRepository {
        defaultRepo?.let { return it }
        synchronized(this) {
            defaultRepo?.let { return it }
            val repo = PrefsAccountRepository(context)
            defaultRepo = repo
            return repo
        }
    }

    /** 测试专用：替换仓储实现 */
    fun overrideRepository(repo: AccountRepository?) {
        synchronized(this) { defaultRepo = repo }
    }

    fun newId(): String = "acc-" + Util.randomBase64Url(9)

    /**
     * 从用户粘贴内容构造账号。
     *
     * 复用 [ConfigStore.sanitizeQwenToken] 的清洗与校验：账号凭证与单账号模式的
     * token 是同一种东西，若这里另写一套规则，会出现「同一个 token 在配置页能存、
     * 在账号页存不进去」的分叉。清洗附带的中文说明也会提示"已自动提取 token 本体"。
     */
    fun build(
        raw: String,
        label: String,
        id: String = newId(),
        enabled: Boolean = true,
    ): ConfigStore.SanitizeResult {
        val san = ConfigStore.sanitizeQwenToken(raw)
        if (!san.ok) return san
        return ConfigStore.SanitizeResult(
            ok = true,
            token = san.token,
            type = san.type,
            note = san.note,
        )
    }

    /** 掩码列表（UI 展示用，绝不把明文凭证交给界面层以外的地方） */
    fun maskedLabel(acc: QwenAccount): String =
        acc.displayName() + " · " + ConfigStore.maskToken(acc.credential)
}
