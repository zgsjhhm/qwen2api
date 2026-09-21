package com.qwen2api.tx.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qwen2api.tx.core.ClipboardUtil
import com.qwen2api.tx.core.ConfigStore
import com.qwen2api.tx.core.GatewayConfig
import com.qwen2api.tx.core.QwenClient
import com.qwen2api.tx.core.QwenModel
import com.qwen2api.tx.core.SystemPromptImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------- 主题色 ----------------

internal val Bg = Color(0xFF0F1115)
internal val CardBg = Color(0xFF17191F)
internal val Line = Color(0xFF2A2D36)
internal val Acc = Color(0xFF8D7FFF)
internal val AccSoft = Color(0x228D7FFF)
internal val Txt = Color(0xFFEDEDF0)
internal val Dim = Color(0xFF9C9CA6)
internal val Dim2 = Color(0xFF62626C)
internal val Ok = Color(0xFF3DDC84)
internal val Bad = Color(0xFFFF6B6B)
internal val Warn = Color(0xFFE2B93B)
private val CodeBg = Color(0xFF0C0C0F)

@Composable
fun Qwen2ApiApp() {
    val vm: MainViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val ctx = LocalContext.current
    // 当前选中的 Tab（进程内保留，切页不丢）
    var tab by rememberSaveable { mutableIntStateOf(0) }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg, surface = CardBg, primary = Acc,
            onBackground = Txt, onSurface = Txt,
        ),
    ) {
        Scaffold(
            containerColor = Bg,
            bottomBar = { BottomNav(tab) { tab = it } },
        ) { padding ->
            // 每个 Tab 独立滚动，内容不足时也不强撑高度
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
            ) {
                when (tab) {
                    0 -> {   // 概览
                        item { Header(ui, vm, ctx) }
                        item { OverviewCard(ui, vm, ctx) }
                    }
                    1 -> {   // 配置
                        item { SectionTitle("连接与密钥") }
                        item { TokenCard(ui, vm) }
                        item { KeyCard(ui, vm, ctx) }
                    }
                    2 -> {   // 测试
                        item { SectionTitle("在线测试") }
                        item { TestCard(ui, vm) }
                    }
                    3 -> {   // 接入
                        item { SectionTitle("接入外部工具") }
                        item { GuideCard(ui, vm, ctx) }
                        item { SettingsCard(ui, vm) }
                    }
                }
            }
        }
    }
}

// ---------------- 底部导航 ----------------

private data class NavItem(val label: String, val icon: String)

private val NAV_ITEMS = listOf(
    NavItem("概览", "◎"),
    NavItem("配置", "⚙"),
    NavItem("测试", "▶"),
    NavItem("接入", "⇄"),
)

/**
 * 底部卡片式导航。
 *
 * 用「图标 + 文字」竖排，选中项加浅底与描边，
 * 各项等宽平分（weight），条目少时不会挤在一侧。
 */
@Composable
private fun BottomNav(current: Int, onSelect: (Int) -> Unit) {
    Column {
        HorizontalDivider(thickness = 1.dp, color = Line)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardBg)
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NAV_ITEMS.forEachIndexed { i, it ->
                val on = i == current
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (on) AccSoft else Color.Transparent)
                        .border(
                            width = 1.dp,
                            color = if (on) Acc.copy(alpha = 0.45f) else Color.Transparent,
                            shape = RoundedCornerShape(14.dp),
                        )
                        .clickable { onSelect(i) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        it.icon,
                        color = if (on) Acc else Dim2,
                        fontSize = 17.sp,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        it.label,
                        color = if (on) Txt else Dim,
                        fontSize = 11.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = Dim2, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 2.dp, top = 4.dp),
    )
}

// ---------------- 概览卡（服务状态速览） ----------------

@Composable
private fun OverviewCard(ui: UiState, vm: MainViewModel, ctx: Context) {
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(8.dp).background(
                    if (ui.serviceRunning) Ok else Dim2, RoundedCornerShape(4.dp),
                ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (ui.serviceRunning) "网关运行中" else "网关未启动",
                color = if (ui.serviceRunning) Ok else Dim,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            if (ui.serviceRunning) Badge("健康", Ok)
        }
        SubText("OpenAI 兼容地址  http://127.0.0.1:${ui.port}/v1")

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            PrimaryButton(
                text = "启动",
                enabled = !ui.checking,
                modifier = Modifier.weight(1f),
            ) { vm.checkConnection() }
            GhostButton("复制地址", Modifier.weight(1f)) {
                copyToClipboard(ctx, "地址", "http://127.0.0.1:${ui.port}/v1")
            }
        }
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            GhostButton(
                text = if (ui.serviceRunning) "停止网关服务" else "启动网关服务",
                modifier = Modifier.weight(1f),
            ) { if (ui.serviceRunning) vm.stopService() else vm.startService() }
            GhostButton("网络诊断", Modifier.weight(1f)) { vm.runNetDiag() }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            StatTile("模型", "${ui.models.size}", "可用", Modifier.weight(1f))
            StatTile("请求", "${ui.reqCount}", "本次会话", Modifier.weight(1f))
        }
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            StatTile(
                "账号",
                when (ui.qwenOk) {
                    true -> "已连接"
                    false -> "未连接"
                    null -> "待检测"
                },
                "Qwen 状态",
                Modifier.weight(1f),
                valueColor = when (ui.qwenOk) {
                    true -> Ok
                    false -> Bad
                    null -> Dim
                },
            )
            StatTile("端口", "${ui.port}", "本机监听", Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "数据不出本机（除对 chat.qwen.ai 的直连请求）",
            color = Dim2, fontSize = 11.sp,
        )
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    hint: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Txt,
) {
    Column(
        modifier = modifier
            .background(CardBg, RoundedCornerShape(14.dp))
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Text(label, color = Dim2, fontSize = 11.sp)
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            color = valueColor, fontSize = 19.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(hint, color = Dim2, fontSize = 10.5.sp)
    }
}

// ---------------- 通用组件 ----------------

@Composable
private fun CardBox(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBg, RoundedCornerShape(16.dp))
            .border(1.dp, Line, RoundedCornerShape(16.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun CardTitle(step: Int?, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (step != null) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(AccSoft, RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$step", color = Acc, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SubText(text: String) {
    Spacer(Modifier.height(6.dp))
    Text(text, color = Dim, fontSize = 12.5.sp, lineHeight = 19.sp)
}

@Composable
private fun Badge(text: String, color: Color) {
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(color, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 11.5.sp)
    }
}

@Composable
internal fun PrimaryButton(
    text: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Acc, contentColor = Color(0xFF0F1115),
            disabledContainerColor = Acc.copy(alpha = 0.3f),
            disabledContentColor = Dim,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
internal fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Txt),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) { Text(text, fontSize = 13.sp) }
}

@Composable
private fun MessageBanner(notice: Notice?) {
    if (notice == null) return
    val color = when (notice.kind) {
        Notice.Kind.OK -> Ok
        Notice.Kind.BAD -> Bad
        Notice.Kind.INFO -> Acc
    }
    Spacer(Modifier.height(10.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(11.dp),
    ) {
        Text(notice.text, color = color, fontSize = 12.5.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun CodeBlock(code: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(CodeBg, RoundedCornerShape(12.dp))
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .padding(13.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(
            code, color = Color(0xFFD6D6DE), fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace, lineHeight = 18.sp, softWrap = false,
        )
    }
}

private fun copyToClipboard(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

// ---------------- 头部 ----------------

@Composable
private fun Header(ui: UiState, vm: MainViewModel, ctx: Context) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Qwen2API", color = Txt, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "本地 OpenAI 兼容网关 · v${ConfigStore.let { com.qwen2api.tx.core.GatewayConfig.VERSION }}",
                    color = Dim2, fontSize = 11.5.sp,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.serviceRunning) {
                Badge("服务运行中 :${ui.port}", Ok)
            } else {
                Badge("服务未启动", Bad)
            }
            when (ui.qwenOk) {
                true -> Badge("Qwen 已连接", Ok)
                false -> Badge("Qwen 未连接", Bad)
                null -> Badge("Qwen 待检测", Dim2)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "已处理请求 ${ui.reqCount} 次 · 数据不出本机（除对 chat.qwen.ai 的直连请求）",
            color = Dim2, fontSize = 11.sp,
        )
    }
}

// ---------------- 1. Token ----------------

@Composable
private fun TokenCard(ui: UiState, vm: MainViewModel) {
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(false) }
    var loginOpen by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    CardBox {
        CardTitle(1, "配置 Qwen Studio Token")
        SubText(
            "点「① 用浏览器登录 Qwen」跳转系统浏览器登录 chat.qwen.ai；" +
                "登录成功后把取凭证脚本粘贴到浏览器地址栏执行，回到 App 点「从剪贴板导入」即可。" +
                "也可以直接粘贴 Token（JWT）或整段 Cookie，保存前会自动验证连通性。" +
                if (ui.config.qwenToken.isNotBlank()) {
                    "\n当前已配置: ${ConfigStore.maskToken(ui.config.qwenToken)}" +
                        " (${ConfigStore.detectTokenType(ui.config.qwenToken)})"
                } else {
                    ""
                },
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            placeholder = { Text("eyJhbGciOiJIUzI1NiIs... 或整段 cookie", color = Dim2, fontSize = 12.5.sp) },
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            textStyle = LocalTextStyle.current.copy(
                fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, color = Txt,
            ),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Acc, unfocusedBorderColor = Line,
                focusedContainerColor = Bg, unfocusedContainerColor = Bg,
            ),
        )
        Spacer(Modifier.height(10.dp))
        // 主入口：用外部浏览器登录 Qwen，再一键把凭证导入（绕过内嵌 WebView 的限制）
        PrimaryButton(if (ui.savingToken) "验证中…" else "① 用浏览器登录 Qwen", !ui.savingToken) {
            loginOpen = true
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(if (ui.savingToken) "验证中…" else "测试并保存", enabled = !ui.savingToken) {
                vm.saveToken(token)
                if (!ui.savingToken) token = ""
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(if (helpOpen) "收起获取方法" else "如何获取 Token") { helpOpen = !helpOpen }
            GhostButton(if (showToken) "隐藏" else "显示") { showToken = !showToken }
        }
        MessageBanner(ui.tokenNotice)

        if (ui.diag.isNotEmpty() || ui.diagHint.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            ui.diag.forEach { s ->
                val c = if (s.ok) Ok else Bad
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .background(c.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (s.ok) "✓" else "✗", color = c, fontSize = 12.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(s.name, color = c, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        s.detail, color = Dim2, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Text("${s.ms}ms", color = Dim2, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
                }
            }
            if (ui.diagHint.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.fillMaxWidth().background(AccSoft, RoundedCornerShape(10.dp)).padding(11.dp),
                ) { Text(ui.diagHint, color = Acc, fontSize = 12.sp, lineHeight = 18.sp) }
            }
        }

        if (helpOpen) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().background(Bg, RoundedCornerShape(10.dp))
                    .border(1.dp, Line, RoundedCornerShape(10.dp)).padding(12.dp),
            ) {
                Column {
                    Text("方式 A · 一键脚本（推荐）", color = Txt, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "1. 点上方「① 用浏览器登录 Qwen」打开系统浏览器并登录\n" +
                            "2. 在弹窗里点「复制取凭证脚本」，粘贴到浏览器地址栏回车\n" +
                            "3. 页面提示已复制后，回到弹窗点「从剪贴板导入」",
                        color = Dim, fontSize = 12.sp, lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("方式 B · JWT Token（手动）", color = Txt, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "1. 浏览器登录 chat.qwen.ai\n" +
                            "2. 地址栏输入以下内容回车：\n" +
                            "   javascript:alert(localStorage.token)\n" +
                            "3. 复制弹出的 eyJ 开头字符串（不要带引号）粘贴进来",
                        color = Dim, fontSize = 12.sp, lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("方式 C · 整段 Cookie（Chrome 需桌面版网站）", color = Txt, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "开发者工具 → Network → 刷新 → 点任意 chat.qwen.ai 请求 →\n" +
                            "Request Headers 里复制整行 cookie: 的值粘贴进来",
                        color = Dim, fontSize = 12.sp, lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "⚠ token 是你的账号凭证，仅保存在本机应用私有存储中，不会上传到任何第三方。",
                        color = Warn, fontSize = 12.sp, lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    GhostButton("打开 chat.qwen.ai") {
                        try {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://chat.qwen.ai"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } catch (e: Exception) {
                            // 无浏览器
                        }
                    }
                }
            }
        }
    }

    // 登录弹窗：默认内置 WebView（推荐），可切换为外部浏览器手动流程
    if (loginOpen) {
        var webMode by remember { mutableStateOf(true) }
        if (webMode) {
            WebViewLoginDialog(
                onDismiss = { loginOpen = false },
                onImport = { raw ->
                    vm.saveToken(raw, fromBrowser = true)
                    loginOpen = false
                },
                onToast = { msg -> vm.notifyToast(msg) },
                onSwitchToBrowser = { webMode = false },
            )
        } else {
            BrowserLoginDialog(
                onDismiss = { loginOpen = false },
                onImport = { raw ->
                    vm.saveToken(raw, fromBrowser = true)
                    loginOpen = false
                },
                onToast = { msg -> vm.notifyToast(msg) },
                onSwitchToWebView = { webMode = true },
            )
        }
    }
}

// ---------------- 2. 密钥 ----------------

@Composable
private fun KeyCard(ui: UiState, vm: MainViewModel, ctx: Context) {
    var confirmRegen by remember { mutableStateOf(false) }

    CardBox {
        CardTitle(2, "本地 API 密钥")
        SubText("调用本地 API 时使用的密钥（Bearer），首次启动自动生成。外部工具里填这个密钥即可。")
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().background(Bg, RoundedCornerShape(10.dp))
                .border(1.dp, Line, RoundedCornerShape(10.dp)).padding(12.dp),
        ) {
            Text(
                ui.config.apiKey, color = Acc, fontSize = 12.5.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("复制密钥") {
                copyToClipboard(ctx, "apiKey", ui.config.apiKey)
                vm.setCopiedHint("已复制")
            }
            GhostButton("重新生成") { confirmRegen = true }
        }
        if (ui.copiedHint != null) {
            Spacer(Modifier.height(8.dp))
            Text(ui.copiedHint!!, color = Ok, fontSize = 12.sp)
        }
        Text(
            "重新生成后，已保存到 Cherry Studio / zcode 等工具里的旧密钥会立即失效。",
            color = Dim2, fontSize = 11.5.sp, lineHeight = 17.sp,
            modifier = Modifier.padding(top = 8.dp),
        )

        if (confirmRegen) {
            AlertDialog(
                onDismissRequest = { confirmRegen = false },
                title = { Text("重新生成密钥？", color = Txt) },
                text = { Text("旧密钥将立即失效，需要同步更新到外部工具中。", color = Dim) },
                confirmButton = {
                    TextButton(onClick = { vm.regenerateKey(); confirmRegen = false }) {
                        Text("确定", color = Bad)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmRegen = false }) { Text("取消", color = Dim) }
                },
                containerColor = CardBg,
            )
        }
    }
}

// ---------------- 3. 在线测试 ----------------

@Composable
private fun TestCard(ui: UiState, vm: MainViewModel) {
    var msg by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(ui.config.thinking) }
    var model by remember { mutableStateOf(ui.config.defaultModel) }
    val models = ui.models

    LaunchedEffect(ui.config.defaultModel, models) {
        if (models.none { it.id == model }) model = ui.config.defaultModel
    }

    CardBox {
        CardTitle(3, "在线测试")
        SubText("走完整链路（本地服务 → Qwen → 流式返回），直接验证 Token 和密钥是否就绪，思考过程实时可见。")

        Spacer(Modifier.height(12.dp))
        ModelPicker(models, model) { model = it }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = thinking, onCheckedChange = { thinking = it },
                colors = CheckboxDefaults.colors(checkedColor = Acc, uncheckedColor = Dim2),
            )
            Text("思考摘要", color = Dim, fontSize = 13.sp)
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = msg,
                onValueChange = { msg = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入测试消息，如：唐朝开国皇帝是谁？", color = Dim2, fontSize = 12.5.sp) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = Txt),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Acc, unfocusedBorderColor = Line,
                    focusedContainerColor = Bg, unfocusedContainerColor = Bg,
                ),
            )
            Spacer(Modifier.width(8.dp))
            PrimaryButton(if (ui.test.running) "生成中…" else "发送", !ui.test.running && msg.isNotBlank()) {
                vm.sendTest(msg.trim(), model, thinking)
            }
        }

        val t = ui.test
        if (t.running || t.answer.isNotEmpty() || t.error != null || t.thinking.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().background(Bg, RoundedCornerShape(12.dp))
                    .border(1.dp, Line, RoundedCornerShape(12.dp)),
            ) {
                Column(Modifier.padding(13.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MiniTag(model)
                        MiniTag("${"%.1f".format(t.elapsedMs / 1000.0)}s")
                        MiniTag(t.status)
                        Spacer(Modifier.weight(1f))
                        if (t.inputTokens > 0 || t.outputTokens > 0) {
                            MiniTag("in ${t.inputTokens} · out ${t.outputTokens}")
                        }
                    }
                    if (t.thinking.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Column(
                            Modifier.fillMaxWidth().background(Warn.copy(alpha = 0.07f), RoundedCornerShape(10.dp))
                                .border(1.dp, Warn.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                .padding(11.dp),
                        ) {
                            Text(
                                "思考摘要${if (t.steps > 0) " · ${t.steps} 步" else ""}",
                                color = Warn, fontSize = 11.5.sp,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                t.thinking, color = Dim, fontSize = 12.sp, lineHeight = 18.sp,
                                modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                    if (t.answer.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            t.answer, color = Txt, fontSize = 13.5.sp, lineHeight = 21.sp,
                        )
                    }
                    if (t.error != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(t.error!!, color = Bad, fontSize = 12.5.sp, lineHeight = 19.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            GhostButton("清空结果") { vm.clearTest() }
        }
    }
}

@Composable
private fun MiniTag(text: String) {
    Box(
        Modifier.background(Line.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(text, color = Dim, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

@Composable
private fun ModelPicker(models: List<QwenModel>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Line),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Txt),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(selected.ifEmpty { "选择模型" }, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
            Text("▾", color = Dim, fontSize = 12.sp)
        }
        DropdownMenu(
            expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.background(CardBg).heightIn(max = 320.dp),
        ) {
            models.forEach { m ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${m.name.ifEmpty { m.id }}  (${m.id})",
                            color = if (m.id == selected) Acc else Txt, fontSize = 12.5.sp,
                        )
                    },
                    onClick = { onSelect(m.id); expanded = false },
                )
            }
        }
    }
}

// ---------------- 4. 接入指南 ----------------

@Composable
private fun GuideCard(ui: UiState, vm: MainViewModel, ctx: Context) {
    val tabs = listOf("Cherry Studio", "zcode / CLI", "curl", "Python", "Node.js")
    val (code, note) = vm.guideText(ui.guideTab)

    CardBox {
        CardTitle(4, "接入外部工具")
        SubText("服务完全兼容 OpenAI Completion 协议。Base URL 与密钥已自动填入下方示例。注意：外部工具需与手机在同一网络，若手机仅监听 127.0.0.1，请在本机使用。")

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tabs.forEachIndexed { i, label ->
                val on = ui.guideTab == i
                Box(
                    Modifier
                        .background(if (on) AccSoft else Color.Transparent, RoundedCornerShape(9.dp))
                        .border(1.dp, if (on) Color.Transparent else Line, RoundedCornerShape(9.dp))
                        .clickable { vm.setGuideTab(i) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(label, color = if (on) Acc else Dim, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        CodeBlock(code)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("复制示例") {
                copyToClipboard(ctx, "guide", code)
                vm.setCopiedHint("已复制")
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(note, color = Dim2, fontSize = 11.5.sp, lineHeight = 17.sp)
    }
}

// ---------------- 5. 高级设置 ----------------

@Composable
private fun SettingsCard(ui: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var model by remember(ui.config.defaultModel) { mutableStateOf(ui.config.defaultModel) }
    var thinking by remember(ui.config.thinking) { mutableStateOf(ui.config.thinking) }
    var autoOpen by remember(ui.config.autoOpen) { mutableStateOf(ui.config.autoOpen) }
    var throttle by remember(ui.config.throttleMs) { mutableStateOf(ui.config.throttleMs.toString()) }
    var port by remember(ui.config.port) { mutableStateOf(ui.config.port.toString()) }
    var lan by remember(ui.config.host) { mutableStateOf(ui.config.host == "0.0.0.0") }
    var imgRetry by remember(ui.config.imageRetryCount) {
        mutableStateOf(ui.config.imageRetryCount.toString())
    }
    var imgBackoff by remember(ui.config.imageRetryBackoffMs) {
        mutableStateOf(ui.config.imageRetryBackoffMs.toString())
    }
    var sysPrompt by remember(ui.config.systemPrompt) { mutableStateOf(ui.config.systemPrompt) }
    var sysPromptOn by remember(ui.config.systemPromptEnabled) {
        mutableStateOf(ui.config.systemPromptEnabled)
    }
    var sysPromptReplace by remember(ui.config.systemPromptMode) {
        mutableStateOf(ui.config.systemPromptMode == GatewayConfig.SYSTEM_PROMPT_REPLACE)
    }
    // 导入提示：临时文案，明说「本文件为什么没进来」
    var importNote by remember { mutableStateOf<String?>(null) }
    var importNoteBad by remember { mutableStateOf(false) }

    fun applyImported(outcome: SystemPromptImport.Outcome, source: String) {
        when (outcome) {
            is SystemPromptImport.Outcome.Ok -> {
                // 只填进输入框，不直接落盘：选错文件不会覆盖线上正在用的提示词，
                // 用户还能在框里改两笔再保存。
                sysPrompt = outcome.value.text
                importNoteBad = false
                importNote = "$source：${importSummary(outcome.value)}"
            }
            is SystemPromptImport.Outcome.Failed -> {
                importNoteBad = true
                importNote = "$source 导入失败：${outcome.message}"
            }
        }
    }

    // SAF 选择器。这里用 */* 而不是 text/plain：.md/.json/.yaml 在多数 ROM 上
    // 报成 application/*，限定 text/plain 会让用户在文件选择器里看到一片灰。
    val pickPromptFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 读文件 + 解码是 IO，挪到 IO 线程，别让大文件卡住主线程
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { readSystemPrompt(ctx, uri) }
            applyImported(outcome, "文件")
        }
    }

    CardBox {
        CardTitle(5, "高级设置")

        Spacer(Modifier.height(10.dp))
        Text("默认模型（请求未指定 model 时使用）", color = Dim, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        ModelPicker(ui.models, model) { model = it }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("服务端口", color = Dim, fontSize = 12.sp)
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = Txt),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Acc, unfocusedBorderColor = Line,
                        focusedContainerColor = Bg, unfocusedContainerColor = Bg,
                    ),
                )
            }
            Column(Modifier.weight(1f)) {
                Text("请求最小间隔 (ms)", color = Dim, fontSize = 12.sp)
                OutlinedTextField(
                    value = throttle, onValueChange = { throttle = it.filter { c -> c.isDigit() }.take(5) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = Txt),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Acc, unfocusedBorderColor = Line,
                        focusedContainerColor = Bg, unfocusedContainerColor = Bg,
                    ),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("出图重试次数", color = Dim, fontSize = 12.sp)
                OutlinedTextField(
                    value = imgRetry,
                    onValueChange = { imgRetry = it.filter { c -> c.isDigit() }.take(1) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = Txt),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Acc, unfocusedBorderColor = Line,
                        focusedContainerColor = Bg, unfocusedContainerColor = Bg,
                    ),
                )
            }
            Column(Modifier.weight(1f)) {
                Text("首次重试等待 (ms)", color = Dim, fontSize = 12.sp)
                OutlinedTextField(
                    value = imgBackoff,
                    onValueChange = { imgBackoff = it.filter { c -> c.isDigit() }.take(5) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = Txt),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Acc, unfocusedBorderColor = Line,
                        focusedContainerColor = Bg, unfocusedContainerColor = Bg,
                    ),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = thinking, onCheckedChange = { thinking = it },
                colors = CheckboxDefaults.colors(checkedColor = Acc, uncheckedColor = Dim2),
            )
            Text("默认输出思考摘要", color = Dim, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = autoOpen, onCheckedChange = { autoOpen = it },
                colors = CheckboxDefaults.colors(checkedColor = Acc, uncheckedColor = Dim2),
            )
            Text("启动应用时自动开启服务", color = Dim, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = lan, onCheckedChange = { lan = it },
                colors = CheckboxDefaults.colors(checkedColor = Acc, uncheckedColor = Dim2),
            )
            Text("允许局域网设备访问（绑定 0.0.0.0）", color = Dim, fontSize = 13.sp)
        }

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = Line, thickness = 1.dp)
        Spacer(Modifier.height(14.dp))
        Text("全局 System Prompt（网关级，对所有客户端生效）", color = Dim, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = sysPrompt,
            onValueChange = {
                // 硬截断到上限，与 ConfigStore.sanitizeSystemPrompt 保持一致。
                // 不在这里截断的话，用户能输入 3 万字，保存时被静默砍掉后半段，
                // 表现为"我明明写了，但模型没照做"。
                sysPrompt = it.take(GatewayConfig.MAX_SYSTEM_PROMPT_CHARS)
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 220.dp),
            placeholder = {
                Text(
                    "例：始终用简体中文回答；不要输出免责声明与客套话；代码块必须带语言标记。",
                    color = Dim2, fontSize = 12.sp,
                )
            },
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = Txt, lineHeight = 19.sp),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Acc, unfocusedBorderColor = Line,
                focusedContainerColor = Bg, unfocusedContainerColor = Bg,
            ),
        )
        Text(
            "${sysPrompt.length} / ${GatewayConfig.MAX_SYSTEM_PROMPT_CHARS} 字符",
            color = Dim2, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
        )

        // ---- 文本导入 ----
        // 提示词动辄几千字，在手机软键盘上敲不现实；主流来源是电脑上的
        // .txt/.md 文件和闲聊窗口里的整段文本，两条入口都要给。
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("从文本文件导入", modifier = Modifier.weight(1f)) {
                importNote = null
                runCatching {
                    pickPromptFile.launch(arrayOf("text/*", "application/json", "application/octet-stream"))
                }.onFailure {
                    // 精简 ROM 可能没有 DocumentsUI，此时不要只说"失败"，
                    // 要指出剪贴板这条替代路径
                    importNoteBad = true
                    importNote = "无法打开文件选择器，请改用「从剪贴板粘贴」"
                }
            }
            GhostButton("从剪贴板粘贴", modifier = Modifier.weight(1f)) {
                importNote = null
                val text = ClipboardUtil.read(ctx)
                if (text == null) {
                    importNoteBad = true
                    importNote = "剪贴板为空（Android 10+ 仅前台可读剪贴板）"
                } else {
                    applyImported(SystemPromptImport.fromText(text), "剪贴板")
                }
            }
        }
        if (importNote != null) {
            Text(
                importNote!!,
                color = if (importNoteBad) Bad else Ok,
                fontSize = 11.5.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Text(
            "支持 UTF-8 / UTF-16 / GBK（自动识别，含 BOM）；.md 的整份代码围栏会自动剥掉，" +
                "JSON 里会优先取 systemPrompt / prompt / instructions 等字段。" +
                "导入只填进输入框，仍需点「保存设置」才落盘。",
            color = Dim2, fontSize = 11.5.sp, lineHeight = 17.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = sysPromptOn, onCheckedChange = { sysPromptOn = it },
                colors = CheckboxDefaults.colors(checkedColor = Acc, uncheckedColor = Dim2),
            )
            Text("启用（注入每一次对话）", color = Dim, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = sysPromptReplace, onCheckedChange = { sysPromptReplace = it },
                colors = CheckboxDefaults.colors(checkedColor = Acc, uncheckedColor = Dim2),
            )
            Text("覆盖调用方自带的 system（默认合并）", color = Dim, fontSize = 13.sp)
        }
        Text(
            "合并 = 全局提示词在前、客户端自己的 system 在后（客户端更贴合本次请求，\n" +
                "约束更强，因此放后面）。覆盖 = 丢弃客户端发的 system，只留这一份，\n" +
                "用于客户端自带提示词行为不可控的场景。注意：修改提示词会改变会话指纹，\n" +
                "下一轮会重新建立上游会话（不会丢历史，只是多一次上行请求）。",
            color = Dim2, fontSize = 11.5.sp, lineHeight = 17.sp,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("保存设置") {
                vm.saveSettings(
                    defaultModel = model,
                    thinking = thinking,
                    autoOpen = autoOpen,
                    throttleMs = throttle.toIntOrNull() ?: 3200,
                    port = port.toIntOrNull() ?: 8818,
                    // 空串/非法输入回默认值而不是 0：0 是「关掉重试」的显式语义，
                    // 不能因为用户清空了输入框就静默把重试关掉。
                    imageRetryCount = imgRetry.toIntOrNull() ?: GatewayConfig.DEFAULT_IMAGE_RETRY,
                    imageRetryBackoffMs = imgBackoff.toIntOrNull()
                        ?: GatewayConfig.DEFAULT_IMAGE_RETRY_BACKOFF,
                    systemPrompt = sysPrompt,
                    systemPromptEnabled = sysPromptOn,
                    systemPromptMode = if (sysPromptReplace) {
                        GatewayConfig.SYSTEM_PROMPT_REPLACE
                    } else {
                        GatewayConfig.SYSTEM_PROMPT_MERGE
                    },
                )            }
            Spacer(Modifier.width(10.dp))
            if (ui.settingsNotice != null) {
                Text(ui.settingsNotice!!, color = Ok, fontSize = 12.sp)
            }
        }
        Text(
            "修改「请求最小间隔」可缓解连续调用触发 Qwen 滑块风控的概率（默认 3200ms）。" +
                "出图重试次数只对上游 5xx / 网络中断 / 空结果生效（默认 2 次），" +
                "次数设 0 即关闭重试；改这几个值立刻对下一次请求生效，无需重启服务。",
            color = Dim2, fontSize = 11.5.sp, lineHeight = 17.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

// ---------------- 页脚 ----------------

// （原 Footer 已移除：版本与声明文案不再在每页底部展示）
