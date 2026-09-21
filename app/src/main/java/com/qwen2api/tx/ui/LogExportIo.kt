package com.qwen2api.tx.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把导出的调用日志落到可分享的位置。
 *
 * 单独放在 UI 层而不是 [com.qwen2api.tx.core.ApiLogStore]：这里要用
 * Context / FileProvider / Intent 这类 Android 类型，而导出文本的生成逻辑
 * 必须在 JVM 单测里裸跑（见 `ApiLogStoreTest`）。
 *
 * ## 为什么必须经 FileProvider
 *
 * Android 7 起禁止把 `file://` URI 放进 Intent（`FileUriExposedException`），
 * 而日志文件在应用私有目录里、外部应用没有读权限。FileProvider 把
 * `cacheDir/log-export/` 映射成 `content://<包名>.fileprovider/...` 并授予
 * 一次性读权限，目标应用（微信、邮件、笔记）才能拿到。
 *
 * ## 为什么带时间戳文件名
 *
 * 用户常常连发几份日志（"刚才那次失败"→"又失败了"）。同名会互相覆盖，
 * 也让人分不清微信里收到的是哪一次导出的。时间戳让"哪份是哪次"有据可查。
 */
internal object LogExportIo {

    /** 私有目录里的导出子目录名，与 `res/xml/file_paths.xml` 的映射必须一致 */
    private const val DIR = "log-export"

    /**
     * 写出导出文本。
     *
     * @return 供分享的 `content://` URI；写出失败时返回 null（调用方给提示，不崩）
     */
    fun writeShareable(context: Context, text: String): Uri? {
        return try {
            val dir = File(context.cacheDir, DIR)
            if (!dir.exists() && !dir.mkdirs()) return null
            // 清掉上一次的产物：这些文件只服务于"刚导出那一下"，
            // 留着只会让 cacheDir 里堆一串再没人看的日志（还都是敏感内容）。
            dir.listFiles()?.forEach { it.delete() }
            val f = File(dir, fileName())
            f.writeText(text, Charsets.UTF_8)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        } catch (e: Exception) {
            null
        }
    }

    /** 分享 Intent */
    fun shareIntent(uri: Uri): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            // 用 text/plain 而不是 text/markdown：后者更"语义正确"，
            // 但不少分享目标（部分 IM、邮件客户端）不声明支持该 MIME，
            // 结果是从选择器里消失——用户看到的"导不出去"就是这么来的。
            // 内容本身是纯文本 Markdown，任何目标都能原样发出。
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Qwen2API 调用日志")
            putExtra(Intent.EXTRA_TEXT, "Qwen2API 调用日志导出（含失败汇总与账号切换链路）")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "导出调用日志").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun fileName(): String {
        val fmt = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
        return "qwen2api-log-${fmt.format(java.util.Date())}.md"
    }
}
