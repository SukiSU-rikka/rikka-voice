package com.rikka.voice.engines

import android.content.Context
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.rikka.voice.core.EngineState
import com.rikka.voice.core.EngineStatus
import com.rikka.voice.core.EngineVoice
import com.rikka.voice.core.Engines
import com.rikka.voice.core.Pcm
import com.rikka.voice.core.SynthParams
import com.rikka.voice.core.TtsEngine
import com.rikka.voice.core.Wav
import java.io.File

/**
 * sherpa-onnx 运行 Supertonic 3（31 语种，这里固定 lang=ja）。
 * Supertonic 直接吃文字、没有 OpenJTalk，汉字读音靠模型自己判断——
 * 所以六花那边默认会把“需要按注音读的词”替换成片假名再发过来。
 *
 * 需要 sherpa-onnx ≥ 1.13（Tts.kt 里有 OfflineTtsSupertonicModelConfig 和 GenerationConfig）。
 * 如果字段名和下面不一致，只改标了【API】的地方。
 */
class SupertonicEngine(private val ctx: Context) : TtsEngine {
    override val id = "supertonic"
    override val displayName = "Supertonic 3（本机）"
    override val description = "sherpa-onnx 运行的 Supertonic 3。轻快、多声线，可读 31 种语言；日语重音不如 VOICEVOX。"
    override val isRemote = false

    private val dir: File get() = Engines.store.dir("supertonic-3")
    private var tts: OfflineTts? = null

    private fun pick(prefix: String, ext: String = ".onnx"): File? =
        dir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(ext) }
            ?.sortedBy { if (it.name.contains("int8")) 0 else 1 }?.firstOrNull()

    private val required get() = listOf(
        pick("duration_predictor"), pick("text_encoder"), pick("vector_estimator"), pick("vocoder"),
        File(dir, "tts.json").takeIf { it.isFile }, File(dir, "unicode_indexer.bin").takeIf { it.isFile },
        File(dir, "voice.bin").takeIf { it.isFile },
    )

    override fun status(): EngineStatus =
        if (required.all { it != null }) EngineStatus(EngineState.READY, "模型已就绪")
        else EngineStatus(EngineState.NEEDS_MODEL, "还没有 Supertonic 3 模型包")

    private fun engine(): OfflineTts {
        tts?.let { return it }
        val r = required.map { it!!.absolutePath }
        // 【API】
        val cfg = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = r[0],
                    textEncoder = r[1],
                    vectorEstimator = r[2],
                    vocoder = r[3],
                    ttsJson = r[4],
                    unicodeIndexer = r[5],
                    voiceStyle = r[6],
                ),
                numThreads = Engines.prefs.threads.coerceIn(1, 8),
                debug = false,
                provider = "cpu",
            ),
            maxNumSentences = 1,
        )
        return OfflineTts(config = cfg).also { tts = it }
    }

    override fun loadVoices(): List<EngineVoice> {
        val n = engine().numSpeakers().coerceAtLeast(1)
        return (0 until n).map { EngineVoice("st:$it", "Supertonic", "声线 ${it + 1}") }
    }

    override fun synthesize(text: String, voiceKey: String, params: SynthParams): Pcm {
        val sid = voiceKey.removePrefix("st:").toIntOrNull() ?: 0
        // 【API】GenerationConfig.extra 传语言（JS/C++ 文档：extra = { lang: 'ja' }）
        val gen = GenerationConfig(sid = sid, speed = params.speed, extra = mapOf("lang" to "ja"))
        val audio = engine().generateWithConfig(text = text, config = gen)
        return Wav.fromFloat(audio.samples, audio.sampleRate)
    }

    override fun release() {
        runCatching { tts?.release() }
        tts = null
    }
}
