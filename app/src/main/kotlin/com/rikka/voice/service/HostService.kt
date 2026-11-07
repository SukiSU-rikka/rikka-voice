package com.rikka.voice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rikka.voice.R
import com.rikka.voice.core.Engines
import com.rikka.voice.server.Api
import com.rikka.voice.server.MiniHttpServer
import com.rikka.voice.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 常驻前台服务：托管 127.0.0.1 上的接口。 */
class HostService : Service() {
    companion object {
        private const val CHANNEL = "rikka_voice_host"
        private const val ACTION_STOP = "com.rikka.voice.STOP"
        private val _state = MutableStateFlow<String?>(null)   // null = 未运行；否则为地址或错误
        val state: StateFlow<String?> = _state
        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error
        private val _port = MutableStateFlow<Int?>(null)
        val port: StateFlow<Int?> = _port
        /** 依次尝试的端口；六花那边按同样顺序探测。 */
        val PORTS = listOf(50021, 50031, 52021, 38021, 18021)

        fun start(ctx: Context) {
            Engines.prefs.serverEnabled = true
            ContextCompat.startForegroundService(ctx, Intent(ctx, HostService::class.java))
        }

        fun stop(ctx: Context) {
            Engines.prefs.serverEnabled = false
            ctx.stopService(Intent(ctx, HostService::class.java))
        }
    }

    private var server: MiniHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stop(this); return START_NOT_STICKY }
        startInForeground()
        if (server?.running != true) {
            var lastErr: Exception? = null
            for (p in (listOf(Engines.prefs.port) + PORTS).distinct()) {
                try {
                    server = MiniHttpServer(p) { Api.handle(it) }.also { it.start() }
                    _state.value = "http://127.0.0.1:$p"; _port.value = p; _error.value = null
                    lastErr = null
                    break
                } catch (e: Exception) {
                    Log.w("RikkaHost", "port $p failed: ${e.message}")
                    lastErr = e
                }
            }
            if (lastErr != null) {
                _state.value = null; _port.value = null
                _error.value = "这台手机的系统不允许普通 App 开本机端口（${lastErr.message}）。" +
                    "不影响六花日语：它走“直连通道”，不需要端口；系统朗读也照常可用。只有别的 App 想用 HTTP 接口时才需要端口。"
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "本机朗读接口", NotificationManager.IMPORTANCE_MIN).apply {
                description = "六花日语等 App 通过它在本机朗读"; setShowBadge(false)
            })
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, HostService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_voice)
            .setContentTitle("六花声库正在待命")
            .setContentText("本机朗读接口 127.0.0.1:${Engines.prefs.port}")
            .setOngoing(true).setSilent(true)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    override fun onDestroy() {
        server?.stop(); server = null
        _state.value = null; _port.value = null
        super.onDestroy()
    }
}
