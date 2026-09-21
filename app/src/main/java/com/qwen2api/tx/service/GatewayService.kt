package com.qwen2api.tx.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.qwen2api.tx.MainActivity
import com.qwen2api.tx.R
import com.qwen2api.tx.core.ConfigStore
import com.qwen2api.tx.core.GatewayConfig
import com.qwen2api.tx.server.GatewayRouter
import com.qwen2api.tx.server.GatewayState
import com.qwen2api.tx.server.MiniHttpServer

/**
 * 前台服务：托管本地 OpenAI 兼容 HTTP 网关。
 * Android 会在应用退到后台后限制/杀死普通进程，前台服务保证网关持续可用。
 */
class GatewayService : android.app.Service() {

    companion object {
        private const val TAG = "GatewayService"
        private const val CHANNEL_ID = "qwen2api_gateway"
        private const val NOTIF_ID = 1001

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var boundPort: Int = 0
            private set

        @Volatile
        var lastError: String? = null
            private set

        const val ACTION_START = "com.qwen2api.tx.START"
        const val ACTION_STOP = "com.qwen2api.tx.STOP"
    }

    private var server: MiniHttpServer? = null
    private var router: GatewayRouter? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopGateway()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startForeground(NOTIF_ID, buildNotification("正在启动…"))
        startGateway()
        return START_STICKY
    }

    private fun startGateway() {
        if (server?.isRunning == true) return
        val cfg = ConfigStore.load(this)
        val port = cfg.port
        val host = cfg.host.ifBlank { GatewayConfig.DEFAULT_HOST }
        try {
            val r = GatewayRouter(applicationContext)
            val s = MiniHttpServer(port, host) { req, res -> r.handle(req, res) }
            s.start()
            router = r
            server = s
            isRunning = true
            boundPort = s.boundPort
            lastError = null
            Log.i(TAG, "gateway started on $host:${s.boundPort}")
            updateNotification("http://127.0.0.1:${s.boundPort} 已就绪")
        } catch (e: Exception) {
            isRunning = false
            lastError = e.message ?: e.toString()
            Log.e(TAG, "gateway start failed", e)
            updateNotification("启动失败: $lastError")
        }
    }

    private fun stopGateway() {
        try { server?.stop() } catch (e: Exception) { /* ignore */ }
        server = null
        router = null
        isRunning = false
        boundPort = 0
        Log.i(TAG, "gateway stopped")
    }

    override fun onDestroy() {
        stopGateway()
        super.onDestroy()
    }

    fun currentRouter(): GatewayRouter? = router

    // ---------------- 通知 ----------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "本地 API 网关",
                    NotificationManager.IMPORTANCE_LOW,
                )
                ch.description = "保持 Qwen2API 网关在后台运行"
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, GatewayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Qwen2API 网关运行中")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_gateway)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "停止", stopIntent)
        return builder.build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (e: Exception) {
            // 通知权限缺失等，忽略
        }
    }

    /** 供 UI 调用：重启网关（端口/绑定地址变更后生效） */
    fun restart() {
        stopGateway()
        startGateway()
    }

    /** 供 UI 查询流量统计 */
    fun stats(): Triple<Long, Boolean?, Long> =
        Triple(GatewayState.reqCount, GatewayState.qwenOk, GatewayState.startTime)
}
