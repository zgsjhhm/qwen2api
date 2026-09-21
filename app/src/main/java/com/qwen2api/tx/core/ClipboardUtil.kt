package com.qwen2api.tx.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build

/**
 * 剪贴板读写。
 *
 * 用途：外部浏览器登录方案里，App 无法直接读取浏览器 Cookie（沙箱隔离），
 * 因此引导用户把 Cookie 复制到剪贴板，由 App 读取并自动解析。
 */
object ClipboardUtil {

    /** 读取剪贴板文本；无内容或无权限时返回 null（Android 10+ 后台读取会返回 null） */
    fun read(context: Context): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null
        if (!cm.hasPrimaryClip()) return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        val text = clip.getItemAt(0).coerceToText(context)?.toString()
        return text?.takeIf { it.isNotBlank() }
    }

    /**
     * 读取剪贴板并尝试提取 Qwen 凭证。
     *
     * @return 提取到的原始凭证串（Cookie 或 JWT）；剪贴板里没有凭证时返回 null
     */
    fun readCredential(context: Context): String? {
        val text = read(context) ?: return null
        // 必须真的含凭证才认领，避免把用户剪贴板里的无关内容当成 token
        return if (looksLikeCredential(text)) text else null
    }

    /**
     * 剪贴板内容是否像 Qwen 凭证。
     *
     * 判定口径的**唯一来源**是 [CookieScript.looksLikeCredential]（含最小长度下界）。
     * 这里不再自己维护第二份正则：曾经两处各写一份，长度处理不一致，
     * 导致同一份文本在剪贴板入口被认领、在回调入口却被拒绝 ——
     * 同一输入两个答案，行为无法解释，排查时也找不到单一事实来源。
     *
     * 反向约束同样重要：回调解析出来的凭证必须能通过这里（见 ClipboardUtilTest）。
     */
    fun looksLikeCredential(text: String): Boolean = CookieScript.looksLikeCredential(text)

    /** 写入剪贴板（用于把 API Key 等复制给用户） */
    fun write(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        runCatching { cm.setPrimaryClip(ClipData.newPlainText(label, text)) }
    }

    /** 当前系统版本是否允许前台读取剪贴板（Android 10+ 仅前台可读） */
    fun canReadInForeground(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
}
