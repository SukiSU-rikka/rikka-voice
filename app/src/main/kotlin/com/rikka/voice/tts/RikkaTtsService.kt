package com.rikka.voice.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import com.rikka.voice.core.Engines
import com.rikka.voice.core.SynthParams
import com.rikka.voice.core.TextSplit
import com.rikka.voice.core.Wav
import java.util.Locale

/**
 * 系统 TTS 引擎：在“设置 → 文字转语音输出”里选“六花声库”后，
 * 任何调用系统朗读的 App（阅读器、无障碍等）都能用这里的声音读日语。
 */
class RikkaTtsService : TextToSpeechService() {
    @Volatile private var stopped = false

    override fun onCreate() {
        Engines.init(this)
        super.onCreate()
    }

    private fun isJa(lang: String?) = lang != null && (lang.equals("jpn", true) || lang.equals("ja", true))

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = when {
        !isJa(lang) -> TextToSpeech.LANG_NOT_SUPPORTED
        country.isNullOrEmpty() -> TextToSpeech.LANG_AVAILABLE
        country.equals("JPN", true) || country.equals("JP", true) -> TextToSpeech.LANG_COUNTRY_AVAILABLE
        else -> TextToSpeech.LANG_AVAILABLE
    }

    override fun onGetLanguage(): Array<String> = arrayOf("jpn", "JPN", "")

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = onIsLanguageAvailable(lang, country, variant)

    override fun onStop() { stopped = true }

    private fun voiceName(id: Int) = "rikka-$id"

    override fun onGetVoices(): MutableList<Voice> {
        val list = Engines.voices.value.map {
            Voice(voiceName(it.id), Locale.JAPAN, Voice.QUALITY_HIGH, if (it.remote) Voice.LATENCY_HIGH else Voice.LATENCY_NORMAL, it.remote, emptySet())
        }.toMutableList()
        list += Voice("rikka-default", Locale.JAPAN, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, emptySet())
        return list
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? =
        if (isJa(lang)) "rikka-default" else null

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (voiceName == "rikka-default" || Engines.voices.value.any { voiceName(it.id) == voiceName }) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int = onIsValidVoiceName(voiceName)

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopped = false
        val text = request.charSequenceText?.toString() ?: request.text ?: ""
        val id = request.voiceName?.removePrefix("rikka-")?.toIntOrNull()
        val voice = id?.let { Engines.voice(it) } ?: Engines.defaultVoice()
        if (voice == null) { callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET); return }
        val speed = (request.speechRate / 100f) * Engines.prefs.systemSpeed
        val pitch = ((request.pitch - 100) / 100f * 0.15f).coerceIn(-.15f, .15f)
        val params = SynthParams(speed = speed.coerceIn(.5f, 2f), pitch = pitch)

        val sentences = TextSplit.sentences(text, 120)
        if (sentences.isEmpty()) { callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1); callback.done(); return }
        var started = false
        var rate = 0
        var framesWritten = 0
        var cursor = 0
        try {
            for (s in sentences) {
                if (stopped) break
                val pcm0 = Engines.synthesize(voice.id, s, params)
                if (!started) {
                    rate = pcm0.sampleRate
                    if (callback.start(rate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return
                    started = true
                }
                val pcm = if (pcm0.sampleRate == rate) pcm0 else Wav.resample(pcm0, rate)
                // 句子高亮（Android 8+）
                val start = text.indexOf(s.take(4), cursor).takeIf { it >= 0 } ?: cursor
                val end = (start + s.length).coerceAtMost(text.length)
                runCatching { callback.rangeStart(framesWritten, start, end) }
                cursor = end
                val bytes = Wav.pcmBytes(pcm)
                val max = callback.maxBufferSize
                var off = 0
                while (off < bytes.size && !stopped) {
                    val n = minOf(max, bytes.size - off)
                    if (callback.audioAvailable(bytes, off, n) != TextToSpeech.SUCCESS) { stopped = true; break }
                    off += n
                }
                framesWritten += pcm.samples.size
            }
            if (started) callback.done()
        } catch (t: Throwable) {
            Log.w("RikkaTts", "synth failed", t)
            if (!started) callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.error(if (voice.remote) TextToSpeech.ERROR_NETWORK else TextToSpeech.ERROR_SYNTHESIS)
        }
    }
}
