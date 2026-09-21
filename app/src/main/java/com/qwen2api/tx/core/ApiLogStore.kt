package com.qwen2api.tx.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** 一条调用日志的结果级别 */
enum class ApiLogLevel { OK, FAIL }

/**
 * 一次 API 调用的结果摘要（供 UI 列表展示）。
 */
data class ApiLogEntry(
    val id: Long,
    /** 结束时刻（epoch ms） */
    val at: Long,
    val level: ApiLogLevel,
    val method: String,
    val path: String,
    /** 返回给调用方的 HTTP 状态码 */
    val status: Int,
    /** 端到端耗时（含切换账号后的重试） */
    val ms: Long,
    val model: String,
    val accountId: String,
    val accountLabel: String,
    /** 上游尝试次数（1 = 一次成功；>1 说明切换/重试过） */
    val attempts: Int,
    /** 账号切换次数 */
    val switches: Int,
    val errorCode: String,
    val message: String,
    /** 成功时的结果摘要（如 "412ch" / "img 1x"），失败时为空 */
    val summary: String,
    /** 全量诊断行；只对最近若干条保留，早期条目为 null（见 [ApiLogStore.MAX_TRACE]） */
    val trace: List<String>?,
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("id", id)
            .put("at", at)
            .put("level", level.name)
            .put("method", method)
            .put("path", path)
            .put("status", status)
            .put("ms", ms)
            .put("model", model)
            .put("accountId", accountId)
            .put("accountLabel", accountLabel)
            .put("attempts", attempts)
            .put("switches", switches)
            .put("errorCode", errorCode)
            .put("message", message)
            .put("summary", summary)
        if (trace != null) {
            val arr = JSONArray()
            trace.forEach { arr.put(it) }
            o.put("trace", arr)
        }
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): ApiLogEntry? {
            return try {
                val level = if (o.optString("level") == "FAIL") ApiLogLevel.FAIL else ApiLogLevel.OK
                val arr = o.optJSONArray("trace")
                ApiLogEntry(
                    id = o.optLong("id"),
                    at = o.optLong("at"),
                    level = level,
                    method = o.optString("method"),
                    path = o.optString("path"),
                    status = o.optInt("status"),
                    ms = o.optLong("ms"),
                    model = o.optString("model"),
                    accountId = o.optString("accountId"),
                    accountLabel = o.optString("accountLabel"),
                    attempts = o.optInt("attempts", 1),
                    switches = o.optInt("switches", 0),
                    errorCode = o.optString("errorCode"),
                    message = o.optString("message"),
                    summary = o.optString("summary"),
                    trace = arr?.let { a -> (0 until a.length()).map { a.optString(it) } },
                )
            } catch (e: Throwable) {
                null
            }
        }
    }
}

/**
 * 调用日志仓库：环形内存缓冲 + JSONL 落盘 + 导出。
 *
 * ## 为什么不只是内存
 *
 * 目标里的核心诉求是「失败时能导出日志去完善 bug」——而网关最典型的排查场景
 * 恰恰是「跑了半天、刚才那次失败了」，用户再去复现往往复现不出来（上游风控、
 * 偶发 5xx 都是概率性的）。只在内存里留日志，等于要求用户「打开 App 守着看」，
 * 这是做不到的。因此每条结束的请求都追加落盘，App 重启后仍在。
 *
 * ## 为什么是 JSONL 追加而不是整份重写
 *
 * 每个请求都会写一次，而一个聊天请求的正文可能很大。整份重写会随日志条数
 * 线性放大写入量（400 条 × 每条约 1KB = 每次请求重写 400KB），且在写入过程中
 * 被杀进程会损坏整份文件。逐行追加则是 O(1) 写、单行损坏只丢一条。
 * 文件超过 [MAX_FILE_BYTES] 时整体压缩为最近 [MAX_ENTRIES] 条 —— 这一步是
 * 低频的，代价可以接受。
 *
 * ## trace 为什么只留最近若干条
 *
 * 诊断行（上游状态码、账号切换、重试等待）对排查价值最高，但体积是摘要行的
 * 十几倍。全量保留会让落盘文件迅速膨胀到几十 MB —— 手机存储、导出后的
 * 分享体积都受不了。折中是：摘要永久保留、trace 只给最近的
 * [MAX_TRACE] 条（默认 60 条，足够覆盖"刚才那次失败"）。
 */
class ApiLogStore(
    /** 落盘文件；为 null 表示纯内存模式（测试/无存储权限场景） */
    private val file: File? = null,
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxTrace: Int = MAX_TRACE,
) {
    private val lock = Any()
    private val seq = AtomicLong(0)

    /** 由旧到新排列；导出与展示时反向遍历 */
    private var entries: MutableList<ApiLogEntry> = ArrayList()

    /** 累计统计（进程生命周期内，不受环形缓冲淘汰影响） */
    @Volatile
    var totalCount: Long = 0

    @Volatile
    var failCount: Long = 0

    /** 最近一次失败（供状态接口/通知栏透出） */
    @Volatile
    var lastFailure: String = ""

    init {
        seq.set(loadFromDisk())
    }

    /**
     * 开启一次请求的日志记录。
     *
     * 返回的 [Trace] 在这个请求的所有阶段（含账号切换、重试）被写入诊断行，
     * 最后由调用方 [Trace.finish] 收尾 —— **只有 finish 过的请求才会出现在日志里**。
     * 这样"请求还在进行中"与"请求已完成"在列表里不会混在一起，
     * 用户看到的每一条都是确定的结果。
     */
    fun begin(
        method: String,
        path: String,
        model: String = "",
        accountId: String = "",
        accountLabel: String = "",
    ): Trace = Trace(
        id = seq.incrementAndGet(),
        store = this,
        startedAt = System.currentTimeMillis(),
        method = method,
        path = path,
        model = model,
        accountId = accountId,
        accountLabel = accountLabel,
    )

    /** 最近 [limit] 条，**由新到旧** */
    fun recent(limit: Int = 100): List<ApiLogEntry> {
        synchronized(lock) {
            if (entries.isEmpty()) return emptyList()
            val from = (entries.size - limit).coerceAtLeast(0)
            return entries.subList(from, entries.size).reversed().toList()
        }
    }

    fun count(): Int = synchronized(lock) { entries.size }

    fun clear() {
        synchronized(lock) {
            entries = ArrayList()
            totalCount = 0
            failCount = 0
            lastFailure = ""
        }
        runCatching { file?.delete() }
    }

    /**
     * 导出为 Markdown 文本。
     *
     * 选择 Markdown 而不是 CSV/纯文本：这份日志的用途是**发给别人（或发给 AI）
     * 帮忙定位 bug**，Markdown 表格在聊天窗口、issue、代码仓库里都能直接读；
     * 而纯文本在手机窄屏上会把每条记录折成难以对齐的十几行。
     *
     * @param includeTrace 是否附带每条请求的诊断行（体积大但定位价值高）
     */
    fun exportText(includeTrace: Boolean = true, limit: Int = maxEntries): String {
        val list = recent(limit)
        val sb = StringBuilder()
        val now = System.currentTimeMillis()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        sb.append("# Qwen2API 调用日志导出\n\n")
        sb.append("- 导出时间: ").append(fmt.format(java.util.Date(now))).append('\n')
        sb.append("- 应用版本: ").append(GatewayConfig.VERSION).append('\n')
        sb.append("- 记录条数: ").append(list.size)
            .append(" (累计请求 ").append(totalCount)
            .append(" / 失败 ").append(failCount).append(")\n")
        if (list.isEmpty()) {
            sb.append("\n（暂无调用记录：服务启动后每处理一次请求都会记录一条）\n")
            return sb.toString()
        }
        val okN = list.count { it.level == ApiLogLevel.OK }
        sb.append("- 本批成功 ").append(okN).append(" / 失败 ").append(list.size - okN).append("\n\n")

        // 失败优先：排查时最想看的是"哪几次错了"，而不是在几百条成功里翻
        val fails = list.filter { it.level == ApiLogLevel.FAIL }
        if (fails.isNotEmpty()) {
            sb.append("## 失败汇总\n\n")
            val byCode = fails.groupBy { it.errorCode.ifEmpty { "UNKNOWN" } }
                .entries.sortedByDescending { it.value.size }
            byCode.forEach { (code, group) ->
                sb.append("- `").append(code).append("` × ").append(group.size).append("：")
                sb.append(group.first().message.replace("\n", " ").take(180)).append('\n')
            }
            sb.append('\n')
        }

        sb.append("## 明细（新 → 旧）\n\n")
        list.forEach { e ->
            sb.append("### ").append(fmt.format(java.util.Date(e.at))).append("  ")
                .append(if (e.level == ApiLogLevel.OK) "✅ 成功" else "❌ 失败").append('\n')
            sb.append("- ").append(e.method).append(' ').append(e.path)
                .append(" → HTTP ").append(e.status)
                .append(" · ").append(e.ms).append("ms\n")
            if (e.model.isNotEmpty()) {
                sb.append("- 模型: `").append(e.model).append("`\n")
            }
            sb.append("- 账号: ").append(e.accountLabel.ifEmpty { "(默认账号)" })
                .append(" · 上游尝试 ").append(e.attempts).append(" 次")
            if (e.switches > 0) sb.append(" · 切换账号 ").append(e.switches).append(" 次")
            sb.append('\n')
            if (e.summary.isNotEmpty()) sb.append("- 结果: ").append(e.summary).append('\n')
            if (e.errorCode.isNotEmpty() || e.message.isNotEmpty()) {
                sb.append("- 错误: `").append(e.errorCode).append("` ")
                    .append(e.message.replace("\n", " ")).append('\n')
            }
            if (includeTrace) {
                if (e.trace.isNullOrEmpty()) {
                    sb.append("- 诊断: （该条超出诊断保留范围）\n")
                } else {
                    sb.append("- 诊断:\n\n```text\n")
                    e.trace.forEach { sb.append(it).append('\n') }
                    sb.append("```\n")
                }
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    // ---------------- 内部：写入 ----------------

    private fun commit(entry: ApiLogEntry) {
        val evicted: List<ApiLogEntry>
        synchronized(lock) {
            entries.add(entry)
            totalCount++
            if (entry.level == ApiLogLevel.FAIL) {
                failCount++
                lastFailure = "${entry.method} ${entry.path} -> ${entry.status} " +
                    "[${entry.errorCode}] ${entry.message.take(200)}"
            }
            val overflow = entries.size - maxEntries
            evicted = if (overflow > 0) {
                val cut = entries.subList(0, overflow)
                val copy = cut.toList()
                entries = ArrayList(entries.subList(overflow, entries.size))
                copy
            } else {
                emptyList()
            }
            // trace 只保留最近 N 条：超龄的条目在**写入时**就丢掉 trace，
            // 而不是等到查询时才过滤 —— 后者会让内存里一直压着几十份大 trace。
            for (i in entries.indices.reversed()) {
                if (entries.size - i > maxTrace && entries[i].trace != null) {
                    entries[i] = entries[i].copy(trace = null)
                }
            }
        }
        // 先落到局部变量：file 是属性，属性在 lambda（runCatching）里不做智能转换，
        // 直接 file.parentFile 会编译不过 —— 这不是风格问题，是 Kotlin 的智能转换规则。
        val f = file
        if (f != null) {
            runCatching {
                f.parentFile?.mkdirs()
                f.appendText(entry.toJson().toString() + "\n", Charsets.UTF_8)
            }
            if (evicted.isNotEmpty()) compactIfNeeded()
        }
    }

    /** 文件超过上限时整体重写为最近 [maxEntries] 条 */
    private fun compactIfNeeded() {
        val f = file ?: return
        runCatching {
            if (f.length() <= MAX_FILE_BYTES) return
            val keep = recent(maxEntries).reversed()
            val text = StringBuilder()
            keep.forEach { text.append(it.toJson().toString()).append('\n') }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                // rename 失败（少数文件系统/权限）时退回直写，避免日志永久不落盘
                f.writeText(text.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    private fun loadFromDisk(): Long {
        val f = file ?: return 0
        if (!f.exists()) return 0
        val loaded = ArrayList<ApiLogEntry>()
        var maxId = 0L
        runCatching {
            f.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val e = runCatching { ApiLogEntry.fromJson(JSONObject(line)) }.getOrNull() ?: return@forEachLine
                loaded.add(e)
                if (e.id > maxId) maxId = e.id
            }
        }
        val trimmed = if (loaded.size > maxEntries) loaded.subList(loaded.size - maxEntries, loaded.size) else loaded
        synchronized(lock) {
            entries = ArrayList(trimmed)
            totalCount = loaded.size.toLong()
            failCount = loaded.count { it.level == ApiLogLevel.FAIL }.toLong()
            lastFailure = loaded.lastOrNull { it.level == ApiLogLevel.FAIL }?.let {
                "${it.method} ${it.path} -> ${it.status} [${it.errorCode}] ${it.message.take(200)}"
            }.orEmpty()
        }
        return maxId
    }

    /**
     * 单次请求的日志记录句柄。
     *
     * 生命周期：`begin` 之后可以随时 [line] 追加诊断行（账号切换、上游状态码、
     * 重试等待…），最后由 [finish] 提交。**未 finish 的请求不会出现在日志里**，
     * 因此列表里不存在"进行中"这种半成品状态。
     */
    inner class Trace internal constructor(
        val id: Long,
        private val store: ApiLogStore,
        private val startedAt: Long,
        private val method: String,
        private val path: String,
        private val model: String,
        private var accountId: String,
        private var accountLabel: String,
    ) {
        private val lines = ArrayList<String>(16)

        @Volatile
        private var finished = false

        /** 实际发生过的上游尝试次数（作者在调用点累加） */
        var attempts: Int = 1

        /** 账号切换次数 */
        var switches: Int = 0

        /**
         * 追加一行诊断。
         *
         * 时间戳由这里统一打，而不是让每个调用点自己算：调用点分散在
         * 账号切换、流式重试、附件上传等处，各自格式一时长短不一，
         * 导出后完全没法按时间对齐。
         */
        fun line(msg: String) {
            if (finished) return
            val t = System.currentTimeMillis() - startedAt
            lines.add("[+" + t + "ms] " + msg)
        }

        /** 记录本次请求最终落在哪个账号上（切换后要更新） */
        fun useAccount(id: String, label: String) {
            accountId = id
            accountLabel = label
        }

        fun finish(
            level: ApiLogLevel,
            status: Int,
            errorCode: String = "",
            message: String = "",
            summary: String = "",
        ) {
            if (finished) return
            finished = true
            store.commit(
                ApiLogEntry(
                    id = id,
                    at = System.currentTimeMillis(),
                    level = level,
                    method = method,
                    path = path,
                    status = status,
                    ms = System.currentTimeMillis() - startedAt,
                    model = model,
                    accountId = accountId,
                    accountLabel = accountLabel,
                    attempts = attempts,
                    switches = switches,
                    errorCode = errorCode,
                    message = message,
                    summary = summary,
                    trace = if (lines.isEmpty()) null else lines.toList(),
                ),
            )
        }
    }

    companion object {
        /** 环形缓冲与落盘上限（条） */
        const val MAX_ENTRIES = 400

        /** 保留诊断行的最近条数 */
        const val MAX_TRACE = 60

        /** 落盘文件大小上限，超过则压缩重写 */
        const val MAX_FILE_BYTES = 2L * 1024 * 1024

        const val FILE_NAME = "api_call_log.jsonl"

        /** 生产：落到应用私有目录（`filesDir/api_call_log.jsonl`） */
        fun forContext(context: Context): ApiLogStore =
            ApiLogStore(File(context.applicationContext.filesDir, FILE_NAME))

        /** 纯内存（测试、或存储不可用时） */
        fun inMemory(): ApiLogStore = ApiLogStore(null)
    }
}
