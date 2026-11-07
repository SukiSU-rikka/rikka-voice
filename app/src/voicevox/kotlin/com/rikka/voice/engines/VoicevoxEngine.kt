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
import java.io.File
import java.util.UUID
import jp.hiroshiba.voicevoxcore.blocking.Onnxruntime
import jp.hiroshiba.voicevoxcore.blocking.OpenJtalk
import jp.hiroshiba.voicevoxcore.blocking.Synthesizer
import jp.hiroshiba.voicevoxcore.blocking.VoiceModelFile

/**
 * VOICEVOX CORE（Java API，blocking 版）。
 *
 * 针对 voicevox_core 0.16.x 的 Java API 编写。如果 muse 用的版本签名不同，
 * 只需要改这个文件里标了【API】的几行，其他代码不用动。
 *
 * 内存策略：VVM 不一次全部载入（每个都要占不少内存），
 * 先只读 metas 建立“风格 ID → 文件”的对照，真正朗读时再载入，最多同时保留 2 个。
 */
class VoicevoxEngine(private val ctx: Context) : TtsEngine {
    override val id = "voicevox"
    override val displayName = "VOICEVOX（本机）"
    override val description = "VOICEVOX 官方核心。日语重音最准、角色最多；每个声音包约几十 MB。"
    override val isRemote = false
    override val handlesVolume = true

    private val vvmDir get() = Engines.store.dir("vvm")
    private var synth: Synthesizer? = null
    private val styleToFile = mutableMapOf<Int, File>()
    private val loaded = LinkedHashMap<String, UUID>()   // 路径 → 模型 ID（按使用顺序）

    fun vvmFiles(): List<File> = vvmDir.listFiles { f -> f.isFile && f.name.endsWith(".vvm") }?.sortedBy { it.name } ?: emptyList()

    override fun status(): EngineStatus = when {
        !Engines.store.hasOpenJtalkDict() -> EngineStatus(EngineState.NEEDS_MODEL, "缺少 OpenJTalk 日语词典")
        vvmFiles().isEmpty() -> EngineStatus(EngineState.NEEDS_MODEL, "还没有声音包（.vvm）")
        else -> EngineStatus(EngineState.READY, "${vvmFiles().size} 个声音包")
    }

    private fun synthesizer(): Synthesizer {
        synth?.let { return it }
        // 【API】Onnxruntime：Android 上从 jniLibs 加载 libvoicevox_onnxruntime.so
        val ort = try {
            Onnxruntime.loadOnce().filename("libvoicevox_onnxruntime.so").perform()
        } catch (t: Throwable) {
            Onnxruntime.loadOnce().perform()
        }
        // 【API】OpenJtalk(词典目录)
        val ojt = OpenJtalk(Engines.store.openJtalkDict.absolutePath)
        // 【API】0.16：builder(ort, openJtalk)；0.17 若改成 builder(ort).textAnalyzer(ojt) 就改这里
        val s = Synthesizer.builder(ort, ojt)
            .cpuNumThreads(Engines.prefs.threads.coerceIn(1, 8))
            .build()
        synth = s
        return s
    }

    override fun loadVoices(): List<EngineVoice> {
        synthesizer()
        styleToFile.clear()
        val out = mutableListOf<EngineVoice>()
        for (f in vvmFiles()) {
            // 【API】VoiceModelFile(path) 打开后可以直接读 metas，不需要载入模型
            VoiceModelFile(f.absolutePath).use { vvm ->
                for (character in vvm.metas) {
                    for (style in character.styles) {
                        val type = runCatching { style.type.toString() }.getOrDefault("talk").lowercase()
                        if (type.isNotEmpty() && !type.contains("talk")) continue   // 跳过唱歌用的风格
                        val sid = style.id
                        if (styleToFile.containsKey(sid)) continue
                        styleToFile[sid] = f
                        out += EngineVoice("vv:$sid", character.name, style.name, "VOICEVOX:${character.name}")
                    }
                }
            }
        }
        return out
    }

    private fun ensureLoaded(styleId: Int) {
        val s = synthesizer()
        val f = styleToFile[styleId] ?: error("找不到风格 $styleId 对应的声音包，请在“声音”页刷新")
        val path = f.absolutePath
        loaded.remove(path)?.let { loaded[path] = it; return }   // 已载入：挪到最近使用
        while (loaded.size >= 2) {
            val (oldPath, oldId) = loaded.entries.first()
            runCatching { s.unloadVoiceModel(oldId) }   // 【API】unloadVoiceModel(UUID)
            loaded.remove(oldPath)
        }
        VoiceModelFile(path).use { vvm ->
            s.loadVoiceModel(vvm)       // 【API】
            loaded[path] = vvm.id       // 【API】VoiceModelFile.id : UUID
        }
    }

    override fun synthesize(text: String, voiceKey: String, params: SynthParams): Pcm {
        val styleId = voiceKey.removePrefix("vv:").toInt()
        ensureLoaded(styleId)
        val s = synthesizer()
        // 【API】AudioQuery 的字段是 public double
        val q = s.createAudioQuery(text, styleId)
        q.speedScale = params.speed.toDouble()
        q.pitchScale = params.pitch.toDouble()
        q.intonationScale = params.intonation.toDouble()
        q.volumeScale = params.volume.toDouble()
        val wav: ByteArray = s.synthesis(q, styleId).perform()
        return Wav.decode(wav)
    }

    override fun release() {
        val s = synth ?: return
        for (id in loaded.values) runCatching { s.unloadVoiceModel(id) }
        loaded.clear()
        runCatching { (s as? AutoCloseable)?.close() }
        synth = null
    }
}
