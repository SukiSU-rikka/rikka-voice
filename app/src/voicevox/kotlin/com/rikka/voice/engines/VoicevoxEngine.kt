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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** JNI：app/src/voicevox/cpp/vv_jni.cpp（直接调用 VOICEVOX 官方 C API）。 */
object VvNative {
    init { System.loadLibrary("rikka_vv") }
    /** 返回空串表示成功，否则是错误说明。 */
    external fun init(coreLib: String, ortLib: String, dictDir: String, threads: Int): String
    external fun version(): String
    external fun metas(vvmPath: String): String
    external fun load(vvmPath: String): String
    external fun unload(modelIdHex: String)
    external fun audioQuery(text: String, styleId: Int): String
    external fun synthesis(audioQueryJson: String, styleId: Int): ByteArray
}

/**
 * VOICEVOX CORE（C API 版）。
 * 运行库：jniLibs/arm64-v8a/libvoicevox_core.so + libvoicevox_onnxruntime.so（见构建说明第 2 步）。
 * 内存策略：声音包按需载入，最多同时 2 个。
 */
class VoicevoxEngine(private val ctx: Context) : TtsEngine {
    override val id = "voicevox"
    override val displayName = "VOICEVOX（本机）"
    override val description = "VOICEVOX 官方核心。日语重音最准、角色最多；每个声音包约几十 MB。"
    override val isRemote = false
    override val handlesVolume = true

    private val libDir = File(ctx.applicationInfo.nativeLibraryDir)
    private val coreLib = File(libDir, "libvoicevox_core.so")
    private val ortLib = File(libDir, "libvoicevox_onnxruntime.so")
    private val vvmDir get() = Engines.store.dir("vvm")
    private var inited = false
    private val styleToFile = mutableMapOf<Int, File>()
    private val loaded = LinkedHashMap<String, String>()   // 路径 → 模型 ID（hex），按使用顺序

    fun vvmFiles(): List<File> = vvmDir.listFiles { f -> f.isFile && f.name.endsWith(".vvm") }?.sortedBy { it.name } ?: emptyList()

    override fun status(): EngineStatus = when {
        !coreLib.isFile -> EngineStatus(EngineState.ERROR, "安装包里缺少 libvoicevox_core.so（打包问题，见构建说明第 2 步）")
        !ortLib.isFile -> EngineStatus(EngineState.ERROR, "安装包里缺少 libvoicevox_onnxruntime.so（打包问题，见构建说明第 2 步）")
        !Engines.store.hasOpenJtalkDict() -> EngineStatus(EngineState.NEEDS_MODEL, "缺少 OpenJTalk 日语词典")
        vvmFiles().isEmpty() -> EngineStatus(EngineState.NEEDS_MODEL, "还没有声音包（.vvm）")
        else -> EngineStatus(EngineState.READY, "${vvmFiles().size} 个声音包" + (if (inited) " · 核心 ${VvNative.version()}" else ""))
    }

    private fun ensureInit() {
        if (inited) return
        val err = VvNative.init(coreLib.absolutePath, ortLib.absolutePath, Engines.store.openJtalkDict.absolutePath, Engines.prefs.threads.coerceIn(1, 8))
        check(err.isEmpty()) { err }
        inited = true
    }

    override fun loadVoices(): List<EngineVoice> {
        ensureInit()
        styleToFile.clear()
        val out = mutableListOf<EngineVoice>()
        val bad = mutableListOf<String>()
        for (f in vvmFiles()) {
            val chars = try { JSONArray(VvNative.metas(f.absolutePath)) } catch (t: Throwable) { bad += "${f.name}（${t.message}）"; continue }
            for (i in 0 until chars.length()) {
                val c = chars.getJSONObject(i)
                val name = c.optString("name")
                val styles = c.optJSONArray("styles") ?: continue
                for (j in 0 until styles.length()) {
                    val s = styles.getJSONObject(j)
                    val type = s.optString("type", "talk")
                    if (type.isNotEmpty() && type != "talk") continue   // 跳过唱歌用的风格
                    val sid = s.getInt("id")
                    if (styleToFile.containsKey(sid)) continue
                    styleToFile[sid] = f
                    out += EngineVoice("vv:$sid", name, s.optString("name"), "VOICEVOX:$name")
                }
            }
        }
        if (out.isEmpty() && bad.isNotEmpty()) error("声音包打不开：" + bad.joinToString("；") + "。文件可能没下载完整，删掉重新下载")
        return out
    }

    private fun ensureLoaded(styleId: Int) {
        val f = styleToFile[styleId] ?: error("找不到风格 $styleId 对应的声音包，请在“声音”页刷新")
        val path = f.absolutePath
        loaded.remove(path)?.let { loaded[path] = it; return }
        while (loaded.size >= 2) {
            val (oldPath, oldId) = loaded.entries.first()
            runCatching { VvNative.unload(oldId) }
            loaded.remove(oldPath)
        }
        loaded[path] = VvNative.load(path)
    }

    override fun synthesize(text: String, voiceKey: String, params: SynthParams): Pcm {
        ensureInit()
        val styleId = voiceKey.removePrefix("vv:").toInt()
        ensureLoaded(styleId)
        val q = JSONObject(VvNative.audioQuery(text, styleId))
        q.put("speedScale", params.speed.toDouble())
        q.put("pitchScale", params.pitch.toDouble())
        q.put("intonationScale", params.intonation.toDouble())
        q.put("volumeScale", params.volume.toDouble())
        return Wav.decode(VvNative.synthesis(q.toString(), styleId))
    }

    override fun release() {
        for (id in loaded.values) runCatching { VvNative.unload(id) }
        loaded.clear()
        // 合成器本身常驻（初始化很慢）；改线程数需要重开 App 才生效
    }
}
