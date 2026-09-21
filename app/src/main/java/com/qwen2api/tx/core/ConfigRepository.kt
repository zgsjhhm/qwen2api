package com.qwen2api.tx.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置仓储抽象。
 *
 * 引入这一层的原因：
 *  - 生产环境用 [PrefsConfigRepository]（SharedPreferences 持久化）
 *  - 单元测试用 [MemoryConfigRepository]（纯内存），从而让
 *    MiniHttpServer + GatewayRouter 能在普通 JVM 上做真实端到端验证，
 *    无需 Robolectric 及其不可用的 Conscrypt 原生库。
 */
interface ConfigRepository {
    fun load(): GatewayConfig

    fun save(cfg: GatewayConfig)

    fun update(patch: (GatewayConfig) -> GatewayConfig): GatewayConfig
}

/** 生产实现：SharedPreferences */
class PrefsConfigRepository(context: Context) : ConfigRepository {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var cached: GatewayConfig? = null

    @Synchronized
    override fun load(): GatewayConfig {
        cached?.let { return it }
        var cfg = GatewayConfig(
            port = prefs.getInt(KEY_PORT, GatewayConfig.DEFAULT_PORT),
            host = prefs.getString(KEY_HOST, GatewayConfig.DEFAULT_HOST) ?: GatewayConfig.DEFAULT_HOST,
            apiKey = SecretVault.decrypt(prefs.getString(KEY_API_KEY, "") ?: ""),
            qwenToken = SecretVault.decrypt(prefs.getString(KEY_QWEN_TOKEN, "") ?: ""),
            defaultModel = prefs.getString(KEY_DEFAULT_MODEL, GatewayConfig.DEFAULT_MODEL)
                ?: GatewayConfig.DEFAULT_MODEL,
            thinking = prefs.getBoolean(KEY_THINKING, true),
            throttleMs = prefs.getInt(KEY_THROTTLE, 3200),
            autoOpen = prefs.getBoolean(KEY_AUTO_OPEN, true),
            imageRetryCount = prefs.getInt(
                KEY_IMAGE_RETRY, GatewayConfig.DEFAULT_IMAGE_RETRY,
            ),
            imageRetryBackoffMs = prefs.getInt(
                KEY_IMAGE_RETRY_BACKOFF, GatewayConfig.DEFAULT_IMAGE_RETRY_BACKOFF,
            ),
            systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, "") ?: "",
            systemPromptEnabled = prefs.getBoolean(KEY_SYSTEM_PROMPT_ENABLED, false),
            systemPromptMode = ConfigStore.normalizeSystemPromptMode(
                prefs.getString(KEY_SYSTEM_PROMPT_MODE, GatewayConfig.SYSTEM_PROMPT_MERGE),
            ),
            multiAccount = prefs.getBoolean(
                KEY_MULTI_ACCOUNT, GatewayConfig.DEFAULT_MULTI_ACCOUNT,
            ),
            // 归一化后再落进 config：上限保护必须在这里也生效，
            // 否则旧版本/手工改过的 prefs 能塞进任意大的值，
            // 而 AccountRouter 的冷却判定会因此永久锁死账号。
            accountCooldownMs = ConfigStore.normalizeAccountCooldown(
                prefs.getInt(KEY_ACCOUNT_COOLDOWN, GatewayConfig.DEFAULT_ACCOUNT_COOLDOWN),
            ),
            maxAccountSwitches = ConfigStore.normalizeMaxSwitches(
                prefs.getInt(KEY_MAX_SWITCHES, GatewayConfig.DEFAULT_MAX_SWITCHES),
            ),
        )
        if (cfg.apiKey.isBlank()) {
            cfg = cfg.copyWith(apiKey = ConfigStore.generateApiKey())
            persist(cfg)
        }
        cached = cfg
        return cfg
    }

    @Synchronized
    override fun save(cfg: GatewayConfig) {
        persist(cfg)
        cached = cfg
    }

    private fun persist(cfg: GatewayConfig) {
        prefs.edit()
            .putInt(KEY_PORT, cfg.port)
            .putString(KEY_HOST, cfg.host)
            .putString(KEY_API_KEY, SecretVault.encrypt(cfg.apiKey))
            .putString(KEY_QWEN_TOKEN, SecretVault.encrypt(cfg.qwenToken))
            .putString(KEY_DEFAULT_MODEL, cfg.defaultModel)
            .putBoolean(KEY_THINKING, cfg.thinking)
            .putInt(KEY_THROTTLE, cfg.throttleMs)
            .putBoolean(KEY_AUTO_OPEN, cfg.autoOpen)
            .putInt(KEY_IMAGE_RETRY, cfg.imageRetryCount)
            .putInt(KEY_IMAGE_RETRY_BACKOFF, cfg.imageRetryBackoffMs)
            .putString(KEY_SYSTEM_PROMPT, cfg.systemPrompt)
            .putBoolean(KEY_SYSTEM_PROMPT_ENABLED, cfg.systemPromptEnabled)
            .putString(KEY_SYSTEM_PROMPT_MODE, ConfigStore.normalizeSystemPromptMode(cfg.systemPromptMode))
            .putBoolean(KEY_MULTI_ACCOUNT, cfg.multiAccount)
            .putInt(KEY_ACCOUNT_COOLDOWN, ConfigStore.normalizeAccountCooldown(cfg.accountCooldownMs))
            .putInt(KEY_MAX_SWITCHES, ConfigStore.normalizeMaxSwitches(cfg.maxAccountSwitches))
            .apply()
    }

    @Synchronized
    override fun update(patch: (GatewayConfig) -> GatewayConfig): GatewayConfig {
        val next = patch(load())
        save(next)
        return next
    }

    private companion object {
        const val PREF_NAME = "qwen2api_config"
        const val KEY_PORT = "port"
        const val KEY_HOST = "host"
        const val KEY_API_KEY = "apiKey"
        const val KEY_QWEN_TOKEN = "qwenToken"
        const val KEY_DEFAULT_MODEL = "defaultModel"
        const val KEY_THINKING = "thinking"
        const val KEY_THROTTLE = "throttleMs"
        const val KEY_AUTO_OPEN = "autoOpen"
        const val KEY_IMAGE_RETRY = "imageRetryCount"
        const val KEY_IMAGE_RETRY_BACKOFF = "imageRetryBackoffMs"
        const val KEY_SYSTEM_PROMPT = "systemPrompt"
        const val KEY_SYSTEM_PROMPT_ENABLED = "systemPromptEnabled"
        const val KEY_SYSTEM_PROMPT_MODE = "systemPromptMode"
        const val KEY_MULTI_ACCOUNT = "multiAccount"
        const val KEY_ACCOUNT_COOLDOWN = "accountCooldownMs"
        const val KEY_MAX_SWITCHES = "maxAccountSwitches"
    }
}

/** 内存实现：用于测试 */
class MemoryConfigRepository(initial: GatewayConfig = GatewayConfig(
    apiKey = ConfigStore.generateApiKey(),
)) : ConfigRepository {

    @Volatile
    private var cfg: GatewayConfig = initial

    override fun load(): GatewayConfig = cfg

    override fun save(cfg: GatewayConfig) {
        this.cfg = cfg
    }

    override fun update(patch: (GatewayConfig) -> GatewayConfig): GatewayConfig {
        cfg = patch(cfg)
        return cfg
    }
}

/** 文件注册表抽象（同上，便于测试注入） */
interface FileStore {
    fun all(): List<FileRecord>

    fun find(id: String): FileRecord?

    fun add(rec: FileRecord)

    fun remove(id: String): FileRecord?
}

/**
 * 已上传文件记录（OpenAI 风格 file_id -> Qwen 上传结果）。
 * 同时作为纯数据类使用，兼容 SharedPreferences 与内存两种存储。
 */
data class FileRecord(
    val id: String,
    val qwenId: String,
    val url: String,
    val filename: String,
    val bytes: Long,
    val mime: String,
    val kind: String,
    val createdAt: Long,
    val purpose: String,
    val entry: org.json.JSONObject,
) {
    fun toOpenAi(): org.json.JSONObject = org.json.JSONObject()
        .put("id", id)
        .put("object", "file")
        .put("bytes", bytes)
        .put("created_at", createdAt)
        .put("filename", filename)
        .put("purpose", purpose.ifEmpty { "assistants" })

    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("id", id)
        .put("qwenId", qwenId)
        .put("url", url)
        .put("filename", filename)
        .put("bytes", bytes)
        .put("mime", mime)
        .put("kind", kind)
        .put("created_at", createdAt)
        .put("purpose", purpose)
        .put("entry", entry)
}
