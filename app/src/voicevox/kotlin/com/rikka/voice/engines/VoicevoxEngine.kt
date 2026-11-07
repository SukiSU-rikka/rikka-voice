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
        val notes = mutableListOf<String>()
        for (f in vvmFiles()) {
            val raw = try { VvNative.metas(f.absolutePath) } catch (t: Throwable) { notes += "${f.name} 打不开：${t.message}"; continue }
            // 原样存一份，方便排查（Android/data/com.rikka.voice/files/models/vvm/*.metas.json）
            runCatching { File(f.parentFile, f.name + ".metas.json").writeText(raw) }
            val found = parseMetas(raw)
            var talk = 0
            for (st in found) {
                if (st.isSing) continue
                talk++
                if (styleToFile.containsKey(st.id)) continue
                styleToFile[st.id] = f
                out += EngineVoice("vv:${st.id}", st.character, st.style, "VOICEVOX:${st.character}")
            }
            if (talk == 0) notes += "${f.name} 里没读到朗读用的声音（共 ${found.size} 个风格）。元数据开头：" + raw.take(160)
        }
        if (out.isEmpty()) error(if (notes.isEmpty()) "声音包里没有可用的声音" else notes.joinToString("；"))
        return out
    }

    private class MetaStyle(val id: Int, val character: String, val style: String, val isSing: Boolean)

    /**
     * 宽松解析 metas JSON：不依赖具体字段名（不同版本可能叫 styles / talk_styles 等），
     * 只要“角色对象”下面有一个元素带整数 id 的数组，就当作它的风格列表。
     */
    private fun parseMetas(raw: String): List<MetaStyle> {
        val root: Any = try { org.json.JSONTokener(raw).nextValue() } catch (t: Throwable) { return emptyList() }
        val characters: JSONArray = when (root) {
            is JSONArray -> root
            is JSONObject -> root.keys().asSequence().map { root.opt(it) }.filterIsInstance<JSONArray>().firstOrNull() ?: JSONArray().put(root)
            else -> return emptyList()
        }
        val out = mutableListOf<MetaStyle>()
        for (i in 0 until characters.length()) {
            val c = characters.optJSONObject(i) ?: continue
            val name = c.optString("name").ifBlank { c.optString("speaker_name") }.ifBlank { "角色${i + 1}" }
            for (key in c.keys()) {
                val arr = c.optJSONArray(key) ?: continue
                val sing = key.contains("sing", ignoreCase = true)
                for (j in 0 until arr.length()) {
                    val s = arr.optJSONObject(j) ?: continue
                    if (!s.has("id")) continue
                    val id = s.optInt("id", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } ?: continue
                    val type = s.optString("type").lowercase()
                    val isSing = sing || type.contains("sing") || type.contains("frame")
                    out += MetaStyle(id, name, s.optString("name"), isSing)
                }
            }
        }
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
        val pcm = Wav.decode(VvNative.synthesis(q.toString(), styleId))
        return Pcm(pcm.samples, pcm.sampleRate, runCatching { marksOf(q) }.getOrNull())
    }

    /** 按 AudioQuery 里每个音拍的长度算出时间轴（与 VOICEVOX 合成用的是同一组数值）。 */
    private fun marksOf(q: JSONObject): List<com.rikka.voice.core.Mark> {
        val speed = q.optDouble("speedScale", 1.0).takeIf { it > 0 } ?: 1.0
        val pauseFixed = if (q.has("pauseLength") && !q.isNull("pauseLength")) q.optDouble("pauseLength") else Double.NaN
        val pauseScale = q.optDouble("pauseLengthScale", 1.0)
        var t = q.optDouble("prePhonemeLength", 0.1) / speed
        val out = mutableListOf<com.rikka.voice.core.Mark>()
        val phrases = q.optJSONArray("accent_phrases") ?: return out
        for (i in 0 until phrases.length()) {
            val ph = phrases.getJSONObject(i)
            val moras = ph.optJSONArray("moras") ?: continue
            for (j in 0 until moras.length()) {
                val m = moras.getJSONObject(j)
                val d = (if (m.isNull("consonant_length")) 0.0 else m.optDouble("consonant_length", 0.0)) + m.optDouble("vowel_length", 0.0)
                out += com.rikka.voice.core.Mark(m.optString("text"), t, t + d / speed, false)
                t += d / speed
            }
            val pm = ph.optJSONObject("pause_mora")
            if (pm != null) {
                val d = if (!pauseFixed.isNaN()) pauseFixed else pm.optDouble("vowel_length", 0.0) * pauseScale
                out += com.rikka.voice.core.Mark(pm.optString("text", "、"), t, t + d / speed, true)
                t += d / speed
            }
        }
        return out
    }

    override fun release() {
        for (id in loaded.values) runCatching { VvNative.unload(id) }
        loaded.clear()
        // 合成器本身常驻（初始化很慢）；改线程数需要重开 App 才生效
    }
}
