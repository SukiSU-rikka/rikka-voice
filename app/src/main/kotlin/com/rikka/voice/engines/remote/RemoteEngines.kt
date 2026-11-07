package com.rikka.voice.engines.remote

import com.rikka.voice.core.EngineState
import com.rikka.voice.core.EngineStatus
import com.rikka.voice.core.EngineVoice
import com.rikka.voice.core.Pcm
import com.rikka.voice.core.RemoteConfig
import com.rikka.voice.core.SynthParams
import com.rikka.voice.core.TextSplit
import com.rikka.voice.core.TtsEngine
import com.rikka.voice.core.Wav
import org.json.JSONArray
import org.json.JSONObject

object RemoteEngines {
    val types = listOf(
        "voicevox" to "VOICEVOX 兼容（AivisSpeech / VOICEVOX ENGINE 等）",
        "sbv2" to "Style-Bert-VITS2 API（server_fastapi.py）",
        "gptsovits" to "GPT-SoVITS api_v2",
    )

    fun defaultPort(type: String) = when (type) { "voicevox" -> 10101; "sbv2" -> 5000; else -> 9880 }

    fun create(c: RemoteConfig): TtsEngine = when (c.type) {
        "sbv2" -> SbvRemote(c)
        "gptsovits" -> GptSovitsRemote(c)
        else -> VoicevoxRemote(c)
    }
}

private abstract class RemoteBase(val cfg: RemoteConfig) : TtsEngine {
    override val id = "remote:" + cfg.id
    override val displayName = cfg.name.ifBlank { "电脑引擎" }
    override val isRemote = true
    override fun status(): EngineStatus =
        if (cfg.base.startsWith("http")) EngineStatus(EngineState.READY, cfg.base)
        else EngineStatus(EngineState.NEEDS_MODEL, "地址需要以 http:// 开头")
}

/** AivisSpeech / VOICEVOX ENGINE / 以及其他实现了 VOICEVOX API 的引擎。 */
private class VoicevoxRemote(cfg: RemoteConfig) : RemoteBase(cfg) {
    override val description = "电脑上的 VOICEVOX 兼容引擎：AivisSpeech（端口 10101）或 VOICEVOX（端口 50021）"
    override val handlesVolume = true

    override fun loadVoices(): List<EngineVoice> {
        val arr = JSONArray(String(Http.get(Http.join(cfg.base, "/speakers"), 15000), Charsets.UTF_8))
        val out = mutableListOf<EngineVoice>()
        for (i in 0 until arr.length()) {
            val sp = arr.getJSONObject(i)
            val name = sp.getString("name")
            val styles = sp.getJSONArray("styles")
            for (j in 0 until styles.length()) {
                val st = styles.getJSONObject(j)
                val type = st.optString("type", "talk")
                if (type != "talk" && type.isNotBlank()) continue
                out += EngineVoice("r:" + st.get("id").toString(), name, st.optString("name"), if (cfg.base.contains(":50021")) "VOICEVOX:$name" else null)
            }
        }
        return out
    }

    override fun synthesize(text: String, voiceKey: String, params: SynthParams): Pcm {
        val speaker = voiceKey.removePrefix("r:")
        val q = JSONObject(String(Http.post(Http.join(cfg.base, "/audio_query?speaker=$speaker&text=" + Http.enc(text)), null, null, 60000), Charsets.UTF_8))
        q.put("speedScale", params.speed.toDouble())
        q.put("pitchScale", params.pitch.toDouble())
        q.put("intonationScale", params.intonation.toDouble())
        q.put("volumeScale", params.volume.toDouble())
        val wav = Http.post(Http.join(cfg.base, "/synthesis?speaker=$speaker"), q.toString().toByteArray())
        return Wav.decode(wav)
    }
}

/** litagin02/Style-Bert-VITS2 的 server_fastapi.py。 */
private class SbvRemote(cfg: RemoteConfig) : RemoteBase(cfg) {
    override val description = "电脑上的 Style-Bert-VITS2 API（默认端口 5000）"

    override fun loadVoices(): List<EngineVoice> {
        val info = JSONObject(String(Http.get(Http.join(cfg.base, "/models/info"), 15000), Charsets.UTF_8))
        val out = mutableListOf<EngineVoice>()
        for (modelId in info.keys()) {
            val m = info.getJSONObject(modelId)
            val modelName = m.optString("model_path").substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').ifBlank { "模型 $modelId" }
            val id2spk = m.optJSONObject("id2spk") ?: JSONObject().put("0", modelName)
            val styles = m.optJSONObject("style2id")?.keys()?.asSequence()?.toList() ?: listOf("Neutral")
            for (spkId in id2spk.keys()) for (style in styles) {
                out += EngineVoice("sbv:$modelId:$spkId:$style", id2spk.getString(spkId), style)
            }
        }
        return out
    }

    override fun synthesize(text: String, voiceKey: String, params: SynthParams): Pcm {
        val (_, model, spk, style) = voiceKey.split(":", limit = 4)
        // 服务器默认单次最多 100 字，按句切开再拼
        val parts = TextSplit.sentences(text, 90).map { s ->
            val url = Http.join(cfg.base, "/voice") + "?text=" + Http.enc(s) + "&model_id=$model&speaker_id=$spk" +
                "&style=" + Http.enc(style) + "&language=JP&length=" + String.format(java.util.Locale.US, "%.3f", 1.0 / params.speed.coerceIn(.3f, 3f)) +
                "&auto_split=true"
            Wav.decode(Http.get(url))
        }
        return Wav.concat(parts, 80)
    }
}

/** RVC-Boss/GPT-SoVITS 的 api_v2.py。 */
private class GptSovitsRemote(cfg: RemoteConfig) : RemoteBase(cfg) {
    override val description = "电脑上的 GPT-SoVITS api_v2（默认端口 9880），需要参考音频"

    override fun status(): EngineStatus = when {
        !cfg.base.startsWith("http") -> EngineStatus(EngineState.NEEDS_MODEL, "地址需要以 http:// 开头")
        cfg.refAudio.isBlank() -> EngineStatus(EngineState.NEEDS_MODEL, "请填写电脑上的参考音频路径")
        else -> EngineStatus(EngineState.READY, cfg.base)
    }

    override fun loadVoices() = listOf(EngineVoice("gsv:0", cfg.name.ifBlank { "GPT-SoVITS" }, "参考音频音色"))

    override fun synthesize(text: String, voiceKey: String, params: SynthParams): Pcm {
        val body = JSONObject()
            .put("text", text).put("text_lang", "ja")
            .put("ref_audio_path", cfg.refAudio).put("prompt_text", cfg.promptText).put("prompt_lang", cfg.promptLang.ifBlank { "ja" })
            .put("text_split_method", "cut5").put("batch_size", 1).put("media_type", "wav")
            .put("streaming_mode", false).put("speed_factor", params.speed.toDouble())
            .put("top_k", 5).put("top_p", 1).put("temperature", 0.8).put("seed", -1)
        return Wav.decode(Http.post(Http.join(cfg.base, "/tts"), body.toString().toByteArray()))
    }
}
