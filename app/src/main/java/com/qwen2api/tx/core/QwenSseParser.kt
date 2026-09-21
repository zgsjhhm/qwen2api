package com.qwen2api.tx.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Qwen SSE 事件解析器（移植自 lib/qwen.js 的 QwenSseParser）。
 *
 * 有状态：支持跨 chunk 行缓冲；思考摘要做快照去重。
 */
sealed class QwenEvent {
    data class Created(val chatId: String, val responseId: String) : QwenEvent()
    data object Completed : QwenEvent()
    data class Thinking(val delta: String) : QwenEvent()
    data class Steps(val titles: List<String>) : QwenEvent()
    data class SearchQueries(val queries: List<String>) : QwenEvent()
    data class SearchDoc(
        val idx: Int,
        val url: String,
        val title: String,
        val snippet: String,
        val hostname: String,
    ) : QwenEvent()
    data class SearchDocs(val docs: List<SearchDoc>) : QwenEvent()
    data class Content(val delta: String) : QwenEvent()
    data class UpstreamError(val code: String, val message: String) : QwenEvent()
}

class QwenSseParser {

    private val buf = StringBuilder()

    /** 思考全文（快照去重基准） */
    var thinking: String = ""
        private set

    /** 思考步骤标题 */
    var steps: List<String> = emptyList()
        private set

    /** function_call.arguments 拼接缓冲 */
    private var queriesBuf = ""
    private val docsByUrl = LinkedHashMap<String, QwenEvent.SearchDoc>()
    var usage: JSONObject? = null
        private set

    /** 喂入 chunk 文本，返回解析出的事件列表 */
    fun feed(chunk: String): List<QwenEvent> {
        buf.append(chunk)
        val out = ArrayList<QwenEvent>()
        while (true) {
            val idx = buf.indexOf("\n")
            if (idx < 0) break
            val line = buf.substring(0, idx).trim()
            buf.delete(0, idx + 1)
            if (!line.startsWith("data:")) continue
            val raw = line.substring(5).trim()
            if (raw.isEmpty()) continue
            val obj = Json.parse(raw) ?: continue
            out.addAll(handle(obj))
        }
        return out
    }

    /** 流结束后残留缓冲（可能没有换行结尾） */
    fun tail(): String = buf.toString().trim().also { buf.setLength(0) }

    private fun handle(obj: JSONObject): List<QwenEvent> {
        val out = ArrayList<QwenEvent>()
        Json.obj(obj, "usage")?.let { usage = it }

        // 流中错误帧：风控/服务端异常常以 success:false 或 error 字段出现（无 choices）
        val successFalse = Json.boolOrNull(obj, "success") == false
        val errObj = Json.obj(obj, "error")
        val choicesArr = Json.arr(obj, "choices")
        val codeField = Json.strOrNull(obj, "code")
        val messageField = Json.strOrNull(obj, "message")
        if (successFalse || errObj != null ||
            (codeField != null && messageField != null && choicesArr == null)
        ) {
            val d = Json.obj(obj, "data")
            val code = codeField
                ?: Json.strOrNull(d, "code")
                ?: Json.strOrNull(errObj, "code")
                ?: "UPSTREAM_ERROR"
            val rawMsg = messageField
                ?: Json.strOrNull(errObj, "message")
                ?: Json.strOrNull(d, "details")
                ?: Json.strOrNull(d, "message")
                ?: obj.toString()
            out.add(QwenEvent.UpstreamError(code, Util.safeStr(rawMsg, 400)))
            return out
        }

        // 生命周期
        Json.obj(obj, "response.created")?.let { rc ->
            out.add(
                QwenEvent.Created(
                    chatId = Json.str(rc, "chat_id"),
                    responseId = Json.str(rc, "response_id"),
                ),
            )
        }
        if (obj.has("response.completed")) out.add(QwenEvent.Completed)

        val choices = choicesArr ?: return out
        if (choices.length() == 0) return out
        val choice0 = choices.optJSONObject(0) ?: return out
        val d = Json.obj(choice0, "delta") ?: JSONObject()
        val phase = Json.str(d, "phase")

        when (phase) {
            "thinking_summary" -> {
                val extra = Json.obj(d, "extra")
                val stArr = Json.arr(Json.obj(extra, "summary_thought"), "content")
                val full = buildString {
                    if (stArr != null) for (i in 0 until stArr.length()) append(stArr.optString(i, ""))
                }
                if (full.isNotEmpty()) {
                    val delta: String = when {
                        full == thinking -> ""
                        thinking.isNotEmpty() && full.startsWith(thinking) -> {
                            val d2 = full.substring(thinking.length)
                            thinking = full
                            d2
                        }
                        thinking.isEmpty() -> {
                            thinking = full
                            full
                        }
                        else -> {
                            // 快照变化无法前缀匹配 -> 全量覆盖式追加
                            thinking = full
                            full
                        }
                    }
                    if (delta.isNotEmpty()) out.add(QwenEvent.Thinking(delta))
                }
                val tiArr = Json.arr(Json.obj(extra, "summary_title"), "content")
                if (tiArr != null) {
                    val joined = ArrayList<String>()
                    for (i in 0 until tiArr.length()) joined.add(tiArr.optString(i, ""))
                    if (joined != steps) {
                        steps = joined
                        out.add(QwenEvent.Steps(joined))
                    }
                }
                return out
            }

            "web_search" -> {
                val status = Json.str(d, "status")
                when (status) {
                    "typing" -> Json.strOrNull(Json.obj(d, "function_call"), "arguments")
                        ?.let { queriesBuf += it }

                    "finished" -> {
                        if (queriesBuf.isNotEmpty()) {
                            val qArr = Json.arr(Json.parse(queriesBuf), "queries")
                            if (qArr != null) {
                                val list = ArrayList<String>()
                                for (i in 0 until qArr.length()) list.add(qArr.optString(i, ""))
                                out.add(QwenEvent.SearchQueries(list))
                            }
                            queriesBuf = ""
                        }
                        val docArr = Json.arr(Json.obj(Json.obj(d, "extra"), "tool_result"), "docs")
                        if (docArr != null) {
                            val fresh = ArrayList<QwenEvent.SearchDoc>()
                            for (i in 0 until docArr.length()) {
                                val doc = docArr.optJSONObject(i) ?: continue
                                val url = Json.str(doc, "url")
                                if (url.isEmpty() || docsByUrl.containsKey(url)) continue
                                val item = QwenEvent.SearchDoc(
                                    idx = docsByUrl.size + 1,
                                    url = url,
                                    title = Json.str(doc, "title", url),
                                    snippet = Json.str(doc, "snippet"),
                                    hostname = Json.str(doc, "hostname"),
                                )
                                docsByUrl[url] = item
                                fresh.add(item)
                            }
                            if (fresh.isNotEmpty()) out.add(QwenEvent.SearchDocs(fresh))
                        }
                    }
                }
                return out
            }

            "answer" -> {
                val content = Json.strOrNull(d, "content")
                if (!content.isNullOrEmpty()) out.add(QwenEvent.Content(content))
                return out
            }
        }

        // 兜底：无 phase 但有 content（老版本格式）
        if (phase.isEmpty()) {
            val content = Json.strOrNull(d, "content")
            if (!content.isNullOrEmpty() && Json.str(d, "role") == "assistant") {
                out.add(QwenEvent.Content(content))
            }
        }
        return out
    }
}

/** 便于遍历 JSONArray 的扩展 */
fun JSONArray.forEachObj(action: (JSONObject) -> Unit) {
    for (i in 0 until length()) optJSONObject(i)?.let(action)
}
