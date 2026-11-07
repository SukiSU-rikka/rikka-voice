package com.rikka.voice.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rikka.voice.core.Engines

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Engines.init(ctx)
        val p = Engines.prefs
        if (p.serverEnabled && (p.startOnBoot || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)) {
            runCatching { HostService.start(ctx) }
        }
    }
}
