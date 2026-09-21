package com.qwen2api.tx.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.qwen2api.tx.core.SystemPromptImport

/**
 * 把 SAF 返回的 `content://` URI 读成一个可用的 System Prompt。
 *
 * 单独放在 UI 层而不是 [SystemPromptImport]：这里要用 ContentResolver 这类
 * Android 类型，而解析逻辑本身必须能在 JVM 单测里裸跑。
 *
 * 失败一律转成 [SystemPromptImport.Outcome.Failed] 文案，不往上抛异常 ——
 * 用户点「导入」后看到的应该是「这个文件为什么不能用」，而不是一个崩溃。
 */
internal fun readSystemPrompt(context: Context, uri: Uri): SystemPromptImport.Outcome {
    val name = displayName(context, uri)
    val prefix = if (name.isNullOrBlank()) "" else "「$name」"

    val bytes = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            SystemPromptImport.readLimited(input)
        } ?: return SystemPromptImport.Outcome.Failed("无法打开该文件（可能已被移动或删除）")
    } catch (e: SystemPromptImport.TooLargeException) {
        return SystemPromptImport.Outcome.Failed("$prefix 超过 ${SystemPromptImport.MAX_BYTES / 1024} KB 上限，请精简后再导入")
    } catch (e: Exception) {
        return SystemPromptImport.Outcome.Failed("读取失败：${e.message ?: "未知错误"}")
    }

    val outcome = SystemPromptImport.fromBytes(bytes)
    // 把文件名贴回失败信息，用户才知道是哪一个文件不达标
    return when (outcome) {
        is SystemPromptImport.Outcome.Ok -> outcome
        is SystemPromptImport.Outcome.Failed ->
            SystemPromptImport.Outcome.Failed(prefix + outcome.message)
    }
}

/** 查询显示名；查不到就返回 null（不影响导入，只影响提示文案的可读性） */
private fun displayName(context: Context, uri: Uri): String? = try {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else {
                null
            }
        }
} catch (e: Exception) {
    null
}

/**
 * 导入成功后给用户的一句回执。
 *
 * 必须回显「编码 + 形态 + 字数」：
 *  - 编码：中文文件出现乱码时，用户第一眼就能判断是不是编码判错了；
 *  - 形态：说明系统对文件做了剥壳（比如 JSON 里取的某个字段），
 *    否则用户会疑惑"我文件里明明还有别的东西，怎么没了"；
 *  - 截断必须显式警告 —— 静默砍掉后半段正是最难排查的那类问题。
 */
internal fun importSummary(value: SystemPromptImport.Imported): String {
    val sb = StringBuilder("已导入 ${value.text.length} 字（${value.encoding} · ${value.shape}）")
    if (value.truncated) {
        sb.append("，超过上限已截断至 ${value.text.length} 字，请确认尾部内容是否需要")
    }
    sb.append("；点「保存设置」后生效")
    return sb.toString()
}
