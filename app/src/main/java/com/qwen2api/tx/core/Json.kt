package com.qwen2api.tx.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 轻量 JSON 封装：基于 Android 内置 org.json（零第三方依赖），
 * 提供与原项目 JS 代码更接近的读写语义，避免到处写 try/catch。
 */
object Json {

    fun encode(v: Any?): String = when (v) {
        null -> "null"
        is String -> JSONObject.quote(v)
        is Number, is Boolean -> v.toString()
        is JSONObject -> v.toString()
        is JSONArray -> v.toString()
        is Map<*, *> -> toJsonObject(v).toString()
        is List<*> -> toJsonArray(v).toString()
        else -> JSONObject.quote(v.toString())
    }

    fun toJsonObject(map: Map<*, *>): JSONObject {
        val o = JSONObject()
        for ((k, v) in map) {
            val key = k?.toString() ?: continue
            when (v) {
                null -> o.put(key, JSONObject.NULL)
                is Map<*, *> -> o.put(key, toJsonObject(v))
                is List<*> -> o.put(key, toJsonArray(v))
                is Array<*> -> o.put(key, toJsonArray(v.toList()))
                else -> o.put(key, v)
            }
        }
        return o
    }

    fun toJsonArray(list: List<*>): JSONArray {
        val a = JSONArray()
        for (v in list) {
            when (v) {
                null -> a.put(JSONObject.NULL)
                is Map<*, *> -> a.put(toJsonObject(v))
                is List<*> -> a.put(toJsonArray(v))
                else -> a.put(v)
            }
        }
        return a
    }

    /** 解析失败返回 null 而不抛异常（对应 JS 的 try{JSON.parse}catch） */
    fun parse(text: String?): JSONObject? {
        if (text.isNullOrBlank()) return null
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            null
        }
    }

    /** 宽松解析：对象或数组都能拿到 JSONObject（数组包装为 {"data": [...]}） */
    fun parseAny(text: String?): Any? {
        if (text.isNullOrBlank()) return null
        val t = text.trim()
        return try {
            if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
        } catch (e: Exception) {
            null
        }
    }

    // ---------- 安全读取 ----------

    fun obj(o: JSONObject?, key: String): JSONObject? {
        if (o == null) return null
        val v = o.opt(key) ?: return null
        return if (v is JSONObject) v else null
    }

    fun arr(o: JSONObject?, key: String): JSONArray? {
        if (o == null) return null
        val v = o.opt(key)
        return if (v is JSONArray) v else null
    }

    fun arr(v: Any?): JSONArray? = v as? JSONArray

    fun str(o: JSONObject?, key: String, def: String = ""): String {
        if (o == null) return def
        val v = o.opt(key) ?: return def
        if (v == JSONObject.NULL) return def
        return if (v is String) v else v.toString()
    }

    fun strOrNull(o: JSONObject?, key: String): String? {
        if (o == null) return null
        val v = o.opt(key) ?: return null
        if (v == JSONObject.NULL) return null
        return if (v is String) v else v.toString()
    }

    fun objAt(o: JSONObject?, key: String): JSONObject? = obj(o, key)

    fun int(o: JSONObject?, key: String, def: Int = 0): Int {
        if (o == null) return def
        val v = o.opt(key) ?: return def
        return when (v) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: def
            else -> def
        }
    }

    fun long(o: JSONObject?, key: String, def: Long = 0L): Long {
        if (o == null) return def
        val v = o.opt(key) ?: return def
        return when (v) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: def
            else -> def
        }
    }

    fun dbl(o: JSONObject?, key: String, def: Double = 0.0): Double {
        if (o == null) return def
        val v = o.opt(key) ?: return def
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: def
            else -> def
        }
    }

    fun bool(o: JSONObject?, key: String, def: Boolean = false): Boolean {
        if (o == null) return def
        val v = o.opt(key) ?: return def
        return when (v) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.equals("true", true)
            else -> def
        }
    }

    fun boolOrNull(o: JSONObject?, key: String): Boolean? {
        if (o == null) return null
        val v = o.opt(key) ?: return null
        return when (v) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.equals("true", true)
            else -> null
        }
    }

    /** 把 JSONObject 转成可直接嵌套的 Map，便于统一构造响应 */
    fun toMap(o: JSONObject?): LinkedHashMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        if (o == null) return m
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            val v = o.opt(k)
            m[k] = unwrap(v)
        }
        return m
    }

    fun unwrap(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> toMap(v)
        is JSONArray -> {
            val list = ArrayList<Any?>()
            for (i in 0 until v.length()) list.add(unwrap(v.opt(i)))
            list
        }
        else -> v
    }
}
