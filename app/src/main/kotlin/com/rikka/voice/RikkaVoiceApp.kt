package com.rikka.voice

import android.app.Application
import com.rikka.voice.core.Engines

class RikkaVoiceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Engines.init(this)
    }
}
