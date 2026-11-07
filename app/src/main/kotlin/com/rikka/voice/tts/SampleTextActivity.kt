package com.rikka.voice.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

class SampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(TextToSpeech.LANG_AVAILABLE, Intent().putExtra("sampleText", "こんにちは。六花声庫で、日本語を読み上げます。"))
        finish()
    }
}
