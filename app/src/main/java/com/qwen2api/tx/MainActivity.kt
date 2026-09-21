package com.qwen2api.tx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.qwen2api.tx.core.ConfigStore
import com.qwen2api.tx.core.CookieScript
import com.qwen2api.tx.core.LoginCallbackBus
import com.qwen2api.tx.service.GatewayService
import com.qwen2api.tx.ui.Qwen2ApiApp

class MainActivity : ComponentActivity() {

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 拒绝也不影响服务运行，只是没有通知栏图标 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Qwen2ApiApp() }

        // Android 13+ 需要运行时通知权限才能显示前台服务通知
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 冷启动时也可能带着回调 URI（App 未运行，浏览器直接拉起）
        handleLoginCallback(intent)

        // 自动启动网关（用户可在设置中关闭 autoOpen）
        val cfg = ConfigStore.load(this)
        if (cfg.autoOpen && !GatewayService.isRunning) {
            startGatewayService()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // App 已在运行时收到回调（最常见路径：浏览器跳回）
        handleLoginCallback(intent)
    }

    /**
     * 处理外部浏览器跳回的 `qwen2api://callback?c=<cookie>`。
     * 解析出凭证后投递到 [LoginCallbackBus]，由 UI 层自动完成校验与保存。
     */
    private fun handleLoginCallback(intent: Intent?) {
        val data = intent?.data ?: return
        if (!data.toString().startsWith(CookieScript.SCHEME, ignoreCase = true)) return
        val credential = CookieScript.parseCallback(data.toString())
        LoginCallbackBus.publish(
            credential,
            if (credential == null) "回调地址里没有有效的登录凭证" else null,
        )
    }

    override fun onResume() {
        super.onResume()
        val cfg = ConfigStore.load(this)
        if (cfg.autoOpen && !GatewayService.isRunning) startGatewayService()
    }

    private fun startGatewayService() {
        try {
            val intent = Intent(this, GatewayService::class.java)
                .setAction(GatewayService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            // 后台启动限制等场景，忽略
        }
    }
}
