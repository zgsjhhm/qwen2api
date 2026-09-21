package com.qwen2api.tx.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qwen2api.tx.core.CookieCapturer

/**
 * 内置 WebView 登录弹窗（主推方案）。
 *
 * 流程：App 内直接加载 chat.qwen.ai → 用户登录 → 点「完成，抓取凭证」
 *      → 自动读取 CookieManager 里的 token → 保存。全程无需复制粘贴。
 *
 * 布局要点（踩过的坑）：
 *   把 WebView 放在可滚动 Column 里会导致**页面无法上下滑动** ——
 *   触摸手势被外层的滚动容器抢走。因此这里改为：
 *   整个弹窗就是一个 Box，WebView 铺满全屏，
 *   顶部状态条与底部操作栏用浮层叠在上面（不参与 WebView 的手势区域）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewLoginDialog(
    onDismiss: () -> Unit,
    onImport: (raw: String) -> Unit,
    onToast: (String) -> Unit,
    /** 用户点「改用手动方式」时切回外部浏览器流程 */
    onSwitchToBrowser: () -> Unit,
) {
    var status by remember { mutableStateOf("正在加载 chat.qwen.ai …") }
    var progress by remember { mutableStateOf(0) }
    var failed by remember { mutableStateOf(false) }

    val webRef = remember { arrayOfNulls<WebView>(1) }

    DisposableEffect(Unit) {
        onDispose {
            CookieCapturer.destroyWebView(webRef[0])
            webRef[0] = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(CardBg)
                .systemBarsPadding(),
        ) {
            // ---- 1. WebView：铺满整个弹窗，独占手势 ----
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    CookieCapturer.createWebView(
                        context = c,
                        onLoadStarted = { url ->
                            failed = false
                            status = "加载中… ${url?.take(48) ?: ""}"
                        },
                        onProgress = { p ->
                            progress = p
                            if (p in 1..99) status = "加载中… $p%"
                        },
                        onLoadFinished = { _ ->
                            progress = 100
                            failed = false
                            status = "已加载，请登录 Qwen"
                        },
                        onLoadError = { msg ->
                            failed = true
                            status = "加载失败：$msg"
                        },
                        // 诊断信息不再展示给用户，仅打 logcat 供排查
                        onDebug = { },
                    ).also { wv ->
                        webRef[0] = wv
                        wv.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
            )

            // ---- 2. 顶部浮层：极简单行状态，尽量不挡页面 ----
            // 默认只显示一条细状态；点它可临时隐藏，避免遮挡页面自己的按钮。
            var barVisible by remember { mutableStateOf(true) }
            if (barVisible) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(CardBg.copy(alpha = 0.94f))
                        .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (failed) "✗ $status" else status,
                        color = if (failed) Bad else Dim,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    GhostButton("隐藏") { barVisible = false }
                }
            } else {
                // 隐藏后留一个小小的浮标，用户可点回来
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(CardBg.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                        .clickable { barVisible = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text("▲ 提示", color = Dim2, fontSize = 10.sp)
                }
            }

            // ---- 3. 底部浮层：操作栏（尽量矮，减少遮挡）----
            Column(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(CardBg.copy(alpha = 0.97f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        text = "完成，抓取凭证",
                        modifier = Modifier.weight(1f),
                    ) {
                        val raw = CookieCapturer.readCookieHeader()
                        val token = CookieCapturer.extractToken(raw)
                        when {
                            raw.isNullOrBlank() -> {
                                failed = true
                                status = "还没读到登录 Cookie —— 请确认已在页面里登录成功"
                                onToast("未读到 Cookie，请先在页面内完成登录")
                            }
                            token.isNullOrBlank() -> {
                                failed = true
                                status = "已读到 Cookie 但没找到 token，请确认已登录"
                                onToast("Cookie 里没有 token，可能尚未登录")
                            }
                            else -> {
                                onToast("已抓取登录凭证")
                                onImport(raw)
                            }
                        }
                    }
                    GhostButton("取消", Modifier.weight(1f)) { onDismiss() }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MiniTextButton("重新加载") {
                        failed = false
                        status = "重新加载中…"
                        webRef[0]?.reload()
                    }
                    MiniTextButton("回到登录页") {
                        failed = false
                        status = "正在打开登录页…"
                        webRef[0]?.loadUrl(CookieCapturer.LOGIN_URL)
                    }
                    MiniTextButton("改用外部浏览器") { onSwitchToBrowser() }
                }
            }
        }
    }
}

/**
 * 极简文字按钮：用于次要操作，占用小、不抢视觉焦点。
 */
@Composable
private fun MiniTextButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
    ) {
        Text(text, color = Dim, fontSize = 11.sp)
    }
}
