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
            val port = Engines.prefs.port
            try {
                server = MiniHttpServer(port) { Api.handle(it) }.also { it.start() }
                _state.value = "http://127.0.0.1:$port"; _error.value = null
            } catch (e: Exception) {
                Log.e("RikkaHost", "server start failed", e)
                _error.value = "端口 $port 被占用或无法监听：${e.message}"
                _state.value = null
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
        _state.value = null
        super.onDestroy()
    }
}
