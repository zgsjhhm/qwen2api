package com.qwen2api.tx.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qwen2api.tx.core.ClipboardUtil
import com.qwen2api.tx.core.CookieCapturer
import com.qwen2api.tx.core.CookieScript

/**
 * 外部浏览器登录引导弹窗。
 *
 * 流程（因为 App 无法读取浏览器的 Cookie —— 沙箱隔离）：
 *   1. 点「打开浏览器登录」→ 跳到系统浏览器打开 chat.qwen.ai
 *   2. 登录成功后，在浏览器地址栏粘贴 [CookieScript.ADDRESS_BAR_SCRIPT]
 *      → 页面把凭证复制到剪贴板
 *   3. 回到 App，点「从剪贴板导入」→ 自动解析并验证保存
 *
 * 也支持用户在开发者工具里手动复制 cookie 后直接导入。
 */
@Composable
fun BrowserLoginDialog(
    onDismiss: () -> Unit,
    onImport: (raw: String) -> Unit,
    onToast: (String) -> Unit,
    /** 切回内置 WebView 登录（更省事，作为首选） */
    onSwitchToWebView: () -> Unit = {},
) {
    val ctx = LocalContext.current
    var step by remember { mutableStateOf(0) }
    var clipboardPreview by remember { mutableStateOf<String?>(null) }
    var clipboardOk by remember { mutableStateOf(false) }

    // 每次打开弹窗/点击刷新时探测剪贴板
    fun probeClipboard() {
        val cred = ClipboardUtil.readCredential(ctx)
        clipboardOk = cred != null
        clipboardPreview = cred?.let { maskForPreview(it) }
    }
    remember { probeClipboard(); 0 }

    fun openBrowser() {
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(CookieCapturer.LOGIN_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            step = 1
        } catch (e: Exception) {
            onToast("没有可用的浏览器，请手动打开 chat.qwen.ai")
        }
    }

    fun copyScript() {
        ClipboardUtil.write(ctx, "qwen-cookie-script", CookieScript.ADDRESS_BAR_SCRIPT)
        // 提示用户去浏览器地址栏粘贴
        onToast("脚本已复制，请在浏览器地址栏长按粘贴并回车")
        step = 2
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 20.dp)
                .background(CardBg, RoundedCornerShape(16.dp))
                .border(1.dp, Line, RoundedCornerShape(16.dp))
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("用外部浏览器登录 Qwen", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text("推荐", color = Acc, fontSize = 10.5.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "App 无法读取浏览器的 Cookie（系统安全限制），所以需要你把凭证「复制」回来。" +
                    "下面的一行脚本可以帮你自动完成复制。",
                color = Dim, fontSize = 12.sp, lineHeight = 18.sp,
            )

            Spacer(Modifier.height(14.dp))

            // 步骤 1
            StepRow(1, "打开浏览器登录", step >= 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("打开浏览器登录") { openBrowser() }
                    GhostButton("复制取凭证脚本") { copyScript() }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 步骤 2
            StepRow(2, "在浏览器地址栏执行脚本", step >= 1) {
                Text(
                    "登录成功后，在浏览器「地址栏」长按 → 粘贴 → 回车，页面会提示已复制凭证。",
                    color = Dim, fontSize = 12.sp, lineHeight = 18.sp,
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().background(Bg, RoundedCornerShape(8.dp))
                        .border(1.dp, Line, RoundedCornerShape(8.dp)).padding(9.dp),
                ) {
                    Text(
                        CookieScript.ADDRESS_BAR_SCRIPT,
                        color = Acc, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        maxLines = 4, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "提示：部分浏览器会去掉开头的 javascript: ，粘贴后请确认它还在；" +
                        "若被拦截，可改用下一步的手动方式。",
                    color = Dim2, fontSize = 10.5.sp, lineHeight = 15.sp,
                )
            }

            Spacer(Modifier.height(12.dp))

            // 步骤 3
            StepRow(3, "回到 App 导入凭证", step >= 1) {
                if (clipboardOk && clipboardPreview != null) {
                    Box(
                        Modifier.fillMaxWidth().background(Ok.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                            .padding(9.dp),
                    ) {
                        Column {
                            Text("✓ 已在剪贴板检测到登录凭证", color = Ok, fontSize = 12.sp)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                clipboardPreview!!,
                                color = Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                } else {
                    Box(
                        Modifier.fillMaxWidth().background(AccSoft, RoundedCornerShape(8.dp))
                            .padding(9.dp),
                    ) {
                        Text(
                            "未在剪贴板检测到凭证。请先完成上面两步，或点「刷新检测」。",
                            color = Acc, fontSize = 11.5.sp, lineHeight = 16.sp,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("从剪贴板导入", clipboardOk) {
                        val cred = ClipboardUtil.readCredential(ctx)
                        if (cred == null) {
                            onToast("剪贴板里没有检测到登录凭证")
                            probeClipboard()
                        } else {
                            onImport(cred)
                        }
                    }
                    GhostButton("刷新检测") { probeClipboard() }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 手动兜底
            Box(
                Modifier.fillMaxWidth().background(Bg, RoundedCornerShape(10.dp))
                    .border(1.dp, Line, RoundedCornerShape(10.dp)).padding(11.dp),
            ) {
                Column {
                    Text("手动方式（脚本被拦截时用）", color = Txt, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "浏览器地址栏输入以下内容回车，页面会弹出一个输入框并全选 Cookie，" +
                            "按「复制」后回到这里导入：\n\n" +
                            "javascript:prompt('复制这段',document.cookie)\n\n" +
                            "若浏览器不支持 javascript: 伪协议（新版 Chrome 常去掉前缀），" +
                            "可改用桌面版/支持书签小工具（Bookmarklet）的浏览器，或直接安装 Chrome 扩展导出 Cookie。",
                        color = Dim, fontSize = 11.5.sp, lineHeight = 17.sp,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "⚠ 凭证是你的账号登录态，仅保存在本机应用私有存储中，不会上传到任何第三方。",
                color = Warn, fontSize = 11.sp, lineHeight = 16.sp,
            )

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("改用内置浏览器登录") { onSwitchToWebView() }
                GhostButton("关闭") { onDismiss() }
            }
        }
    }
}

@Composable
private fun StepRow(index: Int, title: String, active: Boolean, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(22.dp).height(22.dp)
                    .background(if (active) AccSoft else Bg, RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$index",
                    color = if (active) Acc else Dim2,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.width(9.dp))
            Text(title, color = if (active) Txt else Dim2, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(7.dp))
        Box(Modifier.padding(start = 31.dp)) { content() }
    }
}

/** 预览用掩码：只露头尾，避免完整凭证出现在界面上 */
private fun maskForPreview(raw: String): String {
    val t = raw.trim()
    if (t.length <= 24) return t
    return t.take(14) + "……" + t.takeLast(8) + "  (共 ${t.length} 字符)"
}
