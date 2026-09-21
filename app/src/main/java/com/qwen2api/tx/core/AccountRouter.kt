package com.qwen2api.tx.core

/**
 * 一次上游调用要用的凭证（由 [AccountRouter] 选出）。
 *
 * [accountId] 为空串表示"配置里的默认单账号"（[GatewayConfig.qwenToken]）——
 * 保留这个概念是刻意的：绝大多数用户只有一个账号，他们不应该被迫在
 * 账号列表里再录一遍；而默认账号在健康度、冷却、轮转上与列表里的账号完全等价。
 */
data class CredentialRoute(
    val accountId: String,
    val label: String,
    val credential: String,
    /** 是否处于冷却期（全部账号都在冷却时会命中，调用方据此写日志/提示） */
    val cooling: Boolean = false,
    /** 仍在冷却时的剩余毫秒（用于提示"多久后可恢复"） */
    val cooldownLeftMs: Long = 0L,
) {
    val isDefault: Boolean get() = accountId.isEmpty()
}

/** 账号健康快照（UI 展示用） */
data class AccountStatus(
    val accountId: String,
    val label: String,
    val enabled: Boolean,
    val healthy: Boolean,
    val lastUsedAt: Long,
    val lastErrorAt: Long,
    val lastErrorCode: String,
    val lastError: String,
    val cooldownLeftMs: Long,
)

/**
 * 多账号路由核心。
 *
 * ## 职责边界
 *
 * 只回答两个问题：**这次用哪个凭证**、**这次失败算谁的**。
 * 不碰 HTTP、不碰 UI、不构造客户端 —— 这样它能在普通 JVM 上被完整回归。
 * 「流式响应已经推给客户端了还能不能换号」这类问题由调用方（[GatewayRouter]）
 * 依据「是否已向下游写过字节」自行判定，本类不参与。
 *
 * ## 失败为什么要"记在账号上"而不是只记在日志里
 *
 * 自动切换的前提是能区分「这个账号坏了」和「这次请求坏了」。上游的失败绝大多数
 * 是**账号级**的（JWT 过期、今日额度用尽、触发滑块风控），这类失败如果只写日志，
 * 下一次请求会原样再打同一个账号，于是用户的体验是"反复失败"而不是"自动切走"。
 * 因此每次失败都回写到账号健康度，并由 [cooldownMs] 决定它休息多久。
 *
 * ## 冷却到期的语义
 *
 * 冷却不是"永久拉黑"：风控与额度重置都与时间相关，冷却结束后必须自动重新参与
 * 轮转。这靠 `lastErrorAt + cooldownMs` 与当前时刻比较实现，不依赖任何定时器 ——
 * 定时器在 Android 上会被 Doze 掐停，而"读的时候算一下"永远是对的。
 */
class AccountRouter(
    private val accounts: AccountRepository,
    private val configRepo: ConfigRepository,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** 诊断出口（由 server 层接线到日志/Logcat），默认静默 */
    private val log: (String) -> Unit = {},
) {

    /** 上一次实际使用的凭证签名，用于检测"账号变了"并清空上游会话缓存 */
    @Volatile
    private var lastCredential: String = ""

    /**
     * 列出本次请求的候选凭证（按优先级）。
     *
     * 排序规则：
     *  1. 未在冷却期且启用的账号优先；同档内按 `lastUsedAt` 升序 ——
     *     即"最久没用过的先用"，天然形成轮转（round-robin），
     *     避免所有请求都压在列表第一个账号上（那等于没有多账号）；
     *  2. 默认账号参与同一套排序，不享受特殊优先权：给它优先权会让
     *     "多账号分担风控"这个目的失效；
     *  3. 冷却中的账号排到最后，内部按 `lastErrorAt` 升序（最快恢复的在前），
     *     这样"全都在冷却"时至少先试快恢复的那个。
     *
     * 凭证去重：如果某个保存的账号与默认账号是**同一个凭证**（用户先配了默认、
     * 后来又把它加进列表），必须只留一条 —— 否则"切换账号"会切到同一个账号上，
     * 表现为"切换了但错误一模一样"，而这正是用户最难自查的一类现象。
     */
    fun candidates(cfg: GatewayConfig): List<CredentialRoute> {
        val list = ArrayList<CredentialRoute>()
        val seen = HashSet<String>()

        // 局部变量名刻意避开「凭证名 + 赋值号」的形态：日志/诊断链路会对这类行做掩码，
        // 一旦被掩码就会变成非法 Kotlin，编译期直接炸（这个坑已经踩过一次）。
        val defaultCred = cfg.qwenToken
        if (defaultCred.isNotEmpty()) {
            seen.add(defaultCred)
            list.add(
                CredentialRoute(
                    accountId = "",
                    label = "默认账号",
                    credential = defaultCred,
                    cooling = inCooldown(cfg, null),
                ),
            )
        }
        for (a in accounts.all()) {
            if (!a.enabled) continue
            val t = a.credential
            if (t.isEmpty() || !seen.add(t)) continue
            list.add(
                CredentialRoute(
                    accountId = a.id,
                    label = a.displayName(),
                    credential = t,
                    cooling = inCooldown(cfg, a),
                ),
            )
        }
        // 健康优先；同档内「最久没用的先用」形成轮转；再按失败时刻（快恢复的在前）；
        // 最后用 label 兜底，保证顺序稳定（同 label 同一批次也可复现）。
        return list.sortedWith(
            compareBy<CredentialRoute> { it.cooling }
                .thenBy { routeUsedAt(it) }
                .thenBy { routeErrorAt(it) }
                .thenBy { it.label },
        ).map { r ->
            // 冷却剩余时间在排序之后统一补齐（排序只需要次序，不需要精确毫秒）
            if (!r.cooling) r else r.copy(cooldownLeftMs = cooldownLeftMs(cfg, r))
        }
    }

    /**
     * 选出本次请求要用的凭证。
     *
     * 全部候选都在冷却时**不直接失败**，而是返回最快恢复的那一个并标记
     * `cooling = true`：直接失败会让用户在"所有账号都被风控过一次"时彻底不可用，
     * 而实际上上游的瞬时 429 常常过几秒就好了。调用方看到这个标记会写一条
     * 明确的诊断（"所有账号均在冷却，仍在尝试 X"），而不是静默。
     *
     * @return 没有任何可用凭证（未配置 token 且账号列表为空/全禁用）时返回 null
     */
    fun select(cfg: GatewayConfig): CredentialRoute? {
        val all = candidates(cfg)
        if (all.isEmpty()) return null
        val picked = all.firstOrNull { !it.cooling } ?: all.first()
        noteCredentialChanged(picked.credential)
        return picked
    }

    /**
     * 选定该凭证之后调用：记录"用过"，供轮转与 UI 展示。
     *
     * 必须在**真正发起请求前**记时，而不是成功后：失败同样消耗了账号的
     * 使用次数（对风控而言），若失败不记时，同一个账号会被反复优先选中。
     */
    fun noteUsed(route: CredentialRoute) {
        if (route.isDefault) return
        val a = accounts.find(route.accountId) ?: return
        accounts.upsert(a.copy(lastUsedAt = clock()))
    }

    /**
     * 记一次成功：清空该账号的失败记录（= 恢复健康、退出冷却）。
     *
     * 只清"最近失败"而不清"使用统计"：账号被成功调用过就说明它现在是可用的，
     * 继续显示上次的失败只会让用户以为问题还在。
     */
    fun noteOk(route: CredentialRoute) {
        if (route.isDefault || route.accountId.isEmpty()) return
        accounts.noteResult(route.accountId, ok = true)
    }

    /**
     * 记一次失败：写入错误并开始冷却。
     *
     * @param switchable 由 [shouldSwitch] 判定；**不可切换类失败（参数错误、
     *   token 非法字符等）不记冷却** —— 那些是本次请求自己的问题，
     *   与账号健康无关，记上去会让好账号被无谓地拉黑。
     */
    fun noteFail(route: CredentialRoute, e: QwenException, switchable: Boolean) {
        if (route.isDefault || route.accountId.isEmpty()) return
        if (!switchable) return
        accounts.noteResult(
            route.accountId,
            ok = false,
            code = e.code,
            message = e.message,
        )
    }

    /** 本次失败是否值得换账号重试 */
    fun shouldSwitch(e: QwenException): Boolean {
        // HTTP 4xx 里与凭证无关的那些（参数/大小/路径）换账号也不会变好，
        // 换过去只是把同一个错误多打一遍，还会平白多消耗一个账号的请求量。
        if (e.status == 400 || e.status == 413 || e.status == 404) return false
        if (e.code in NON_SWITCHABLE) return false
        return true
    }

    /** 账号健康快照（UI 用，顺序与 [candidates] 一致） */
    fun snapshot(cfg: GatewayConfig): List<AccountStatus> {
        val byId = accounts.all().associateBy { it.id }
        return candidates(cfg).map { r ->
            val a = byId[r.accountId]
            AccountStatus(
                accountId = r.accountId,
                label = r.label,
                enabled = a?.enabled ?: true,
                healthy = !r.cooling && a?.lastError.isNullOrEmpty(),
                lastUsedAt = a?.lastUsedAt ?: 0L,
                lastErrorAt = a?.lastErrorAt ?: 0L,
                lastErrorCode = a?.lastErrorCode.orEmpty(),
                lastError = a?.lastError.orEmpty(),
                cooldownLeftMs = r.cooldownLeftMs,
            )
        }
    }

    /** 清空所有账号的健康记录（用户"手动重置"用：过滑块后想立刻恢复） */
    fun resetHealth() {
        accounts.all().forEach { a ->
            accounts.upsert(a.copy(lastError = "", lastErrorCode = "", lastErrorAt = 0L))
        }
    }

    // ---------------- 内部 ----------------

    private fun routeUsedAt(r: CredentialRoute): Long =
        if (r.isDefault) 0L else accounts.find(r.accountId)?.lastUsedAt ?: 0L

    private fun routeErrorAt(r: CredentialRoute): Long =
        if (r.isDefault) 0L else accounts.find(r.accountId)?.lastErrorAt ?: 0L

    private fun cooldownLeftMs(cfg: GatewayConfig, r: CredentialRoute): Long {
        val a = if (r.isDefault) null else accounts.find(r.accountId)
        return cooldownLeft(cfg, a)
    }

    private fun inCooldown(cfg: GatewayConfig, a: QwenAccount?): Boolean = cooldownLeft(cfg, a) > 0

    private fun cooldownLeft(cfg: GatewayConfig, a: QwenAccount?): Long {
        if (a == null) return 0L
        if (a.lastErrorAt <= 0L) return 0L
        val elapsed = clock() - a.lastErrorAt
        // 时钟回拨（用户改系统时间/NTP 校正）会让 elapsed 变负：此时按"冷却已过"处理，
        // 否则账号会被永久锁死，而这是用户完全无法自行恢复的状态。
        if (elapsed < 0) return 0L
        val left = cfg.accountCooldownMs - elapsed
        return left.coerceAtLeast(0L)
    }

    /**
     * 凭证发生变化时清空上游会话缓存。
     *
     * ⚠ 现状：这条路径**在生产链路上不会被触发**。[GatewayRouter] 的五条链路
     * 都是直接迭代 [candidates] 的候选（每轮新建客户端再赋 `credentialOverride`），
     * 没有任何一处调用 [select]，因此本方法只被单测触达。也就是说
     * "换账号顺带清会话缓存"这个保护在真机上并不存在。
     *
     * 为什么不能顺手把它接到每条链路上：候选顺序是"最久没用的先用"（见 [candidates]），
     * 这意味着**跨请求**就在轮转账号。若每次换号都清一次全局会话缓存，
     * [QwenClient.sessionCache] 会在相邻请求之间被反复清空，增量续接永久失效 ——
     * 每轮都退回扁平模式重发全部历史，token 与延迟同时翻倍，
     * 比"复用旧 chatId 白打一次上游"贵得多。
     *
     * 正确的修法是让会话指纹带上凭证（同一段对话在不同账号下各自建会话），
     * 而不是在换号时清全局缓存。当前未实现，在此记录为已知项。
     *
     * 残留代价（现状下真实存在）：[QwenClient.sessionCache] 的 key 只由对话内容决定，
     * 里面存着**上一个账号**的 chatId。换号后继续用它续接，上游会回 CHAT_NOT_FOUND；
     * [QwenClient.chatStream] 会 catch 住并清掉该条缓存、静默回退扁平模式
     *（客户端看不到错误，只是白打一次上游请求并重发历史）。
     */
    private fun noteCredentialChanged(credential: String) {
        if (credential == lastCredential) return
        if (lastCredential.isNotEmpty()) {
            QwenClient.clearSessions()
            log("account switched -> ${ConfigStore.maskToken(credential)}, upstream session cache cleared")
        }
        lastCredential = credential
    }

    companion object {
        /**
         * 换账号也不会变好的错误码。
         *
         * 判据是「这个错误的信息完全来自本次请求本身」：
         *  - NO_TOKEN       未配置凭证（换账号也没用，得先配）
         *  - BAD_REQUEST    上游判参数非法
         *  - PARSE_FAILED   响应解析失败
         *  - FILE_TOO_LARGE 文件超限
         *  - *_INVALID_CHARS 本地就拦下了，压根没发出去
         */
        val NON_SWITCHABLE = setOf(
            "NO_TOKEN",
            "BAD_REQUEST",
            "PARSE_FAILED",
            "FILE_TOO_LARGE",
            "FILE_NOT_FOUND",
            "TOKEN_INVALID_CHARS",
            "HEADER_INVALID_CHARS",
        )
    }
}
