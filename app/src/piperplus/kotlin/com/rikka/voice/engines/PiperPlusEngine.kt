package com.rikka.voice.engines

import android.content.Context
import com.rikka.voice.core.EngineState
import com.rikka.voice.core.EngineStatus
import com.rikka.voice.core.EngineVoice
import com.rikka.voice.core.Engines
import com.rikka.voice.core.Pcm
import com.rikka.voice.core.SynthParams
import com.rikka.voice.core.TtsEngine
import com.rikka.voice.core.Wav
import org.json.JSONObject
import java.io.File

/** JNI 桥（实现在 src/piperplus/cpp/piper_plus_jni.cpp）。 */
object PiperPlusNative {
    init {
        System.loadLibrary("piper_plus")       // 来自 piper-plus 的 Android 预编译包
        System.loadLibrary("rikka_piper_jni")  // 本项目的薄封装
    }
    external fun create(model: String, config: String, dictDir: String, threads: Int): Long
    /** 返回 float PCM（-1..1）；失败抛 RuntimeException。 */
    external fun synthesize(handle: Long, text: String, speakerId: Int, lengthScale: Float): FloatArray
    external fun sampleRate(handle: Long): Int
    external fun destroy(handle: Long)
}

/**
 * piper-plus（MIT，Piper 的日语强化分支）。模型 + config.json 放在 models/piper-plus/ 下，
 * 可以放多个模型：每个 xxx.onnx 配一个 xxx.onnx.json 或同目录 config.json。
 */
class PiperPlusEngine(private val ctx: Context) : TtsEngine {
    override val id = "piperplus"
    override val displayName = "piper-plus（本机 · 实验）"
    override val description = "piper-plus 日语 VITS。模型最小（约 40 MB）、最快，适合老手机。"
    override val isRemote = false

    private val dir get() = Engines.store.dir("piper-plus")
    private val handles = mutableMapOf<String, Long>()

    private fun models(): List<Pair<File, File>> =
        dir.listFiles { f -> f.name.endsWith(".onnx") }?.mapNotNull { m ->
            val c = File(m.path + ".json").takeIf { it.isFile } ?: File(dir, "config.json").takeIf { it.isFile }
            c?.let { m to it }
        }?.sortedBy { it.first.name } ?: emptyList()

    override fun status(): EngineStatus = when {
        !Engines.store.hasOpenJtalkDict() -> EngineStatus(EngineState.NEEDS_MODEL, "缺少 OpenJTalk 日语词典")
        models().isEmpty() -> EngineStatus(EngineState.NEEDS_MODEL, "还没有 piper-plus 模型（.onnx + config.json）")
        else -> EngineStatus(EngineState.READY, "${models().size} 个模型")
    }

    override fun loadVoices(): List<EngineVoice> = models().flatMap { (m, c) ->
        val cfg = JSONObject(c.readText())
        val name = m.name.removeSuffix(".onnx")
        val map = cfg.optJSONObject("speaker_id_map")
        if (map != null && map.length() > 1)
            map.keys().asSequence().map { EngineVoice("pp:${m.name}:${map.getInt(it)}", name, it) }.toList()
        else listOf(EngineVoice("pp:${m.name}:0", name, ""))
    }

    private fun handle(modelName: String): Long = handles.getOrPut(modelName) {
        val pair = models().first { it.first.name == modelName }
        val h = PiperPlusNative.create(pair.first.absolutePath, pair.second.absolutePath, Engines.store.openJtalkDict.absolutePath, Engines.prefs.threads.coerceIn(1, 8))
        check(h != 0L) { "piper-plus 模型载入失败：$modelName" }
        h
    }

    override fun synthesize(text: String, voiceKey: String, params: SynthParams): Pcm {
        val parts = voiceKey.removePrefix("pp:")
        val model = parts.substringBeforeLast(':')
        val sid = parts.substringAfterLast(':').toIntOrNull() ?: 0
        val h = handle(model)
        val samples = PiperPlusNative.synthesize(h, text, sid, 1f / params.speed.coerceIn(.3f, 3f))
        return Wav.fromFloat(samples, PiperPlusNative.sampleRate(h))
    }

    override fun release() {
        handles.values.forEach { runCatching { PiperPlusNative.destroy(it) } }
        handles.clear()
    }
}
