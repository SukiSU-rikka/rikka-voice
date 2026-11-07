package com.rikka.voice.server

import com.rikka.voice.BuildConfig
import com.rikka.voice.core.EngineState
import com.rikka.voice.core.Engines
import com.rikka.voice.core.SynthParams
import com.rikka.voice.core.Wav
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 对外接口。
 *
 * 1) VOICEVOX ENGINE 兼容子集（任何支持 VOICEVOX 的 App 都能用）：
 *    GET  /version
 *    GET  /speakers
 *    POST /audio_query?text=…&speaker=ID      → AudioQuery JSON
 *    POST /synthesis?speaker=ID  (body: AudioQuery) → audio/wav
 *    POST /tts?text=…&speaker=ID              → audio/wav（简便接口）
 *
 * 2) 六花专用（一步到位，少一次往返）：
 *    GET  /rikka/info
 *    GET  /rikka/voices
 *    POST /rikka/tts   {"text","speaker","speed","pitch","intonation","volume"} → audio/wav
 */
object Api {
    fun handle(req: HttpRequest): HttpResponse = when (req.method to req.path) {
        "GET" to "/version" -> HttpResponse.json("\"${BuildConfig.VERSION_NAME}-rikka\"")
        "GET" to "/speakers" -> HttpResponse.json(speakers().toString())
        "POST" to "/audio_query" -> audioQuery(req)
        "POST" to "/synthesis" -> synthesis(req)
        "POST" to "/tts", "GET" to "/tts" -> simpleTts(req)
        "GET" to "/rikka/info" -> HttpResponse.json(info().toString())
        "GET" to "/rikka/voices" -> HttpResponse.json(voicesJson().toString())
        "POST" to "/rikka/tts" -> rikkaTts(req)
        "GET" to "/" -> HttpResponse.text("六花声库 ${BuildConfig.VERSION_NAME} 正在运行。接口说明见 App 内“关于”页。")
        else -> HttpResponse.error(404, "没有这个接口：${req.method} ${req.path}")
    }

    private fun speakers(): JSONArray {
        val arr = JSONArray()
        Engines.voices.value.groupBy { it.engineId + "|" + it.character }.forEach { (k, vs) ->
            val styles = JSONArray()
            vs.forEach { styles.put(JSONObject().put("name", it.style.ifBlank { "ノーマル" }).put("id", it.id).put("type", "talk")) }
            arr.put(JSONObject()
                .put("name", vs.first().character)
                .put("speaker_uuid", java.util.UUID.nameUUIDFromBytes(k.toByteArray()).toString())
                .put("styles", styles)
                .put("version", BuildConfig.VERSION_NAME))
        }
        return arr
    }

    private fun voicesJson(): JSONArray {
        val arr = JSONArray()
        val def = Engines.defaultVoice()?.id
        for (v in Engines.voices.value) arr.put(JSONObject()
            .put("id", v.id).put("name", v.label).put("character", v.character).put("style", v.style)
            .put("engine", v.engineId).put("engineName", v.engineName).put("remote", v.remote)
            .put("credit", v.credit ?: JSONObject.NULL).put("default", v.id == def))
        return arr
    }

    private fun info(): JSONObject {
        val engines = JSONArray()
        Engines.engines.value.forEach { e ->
            engines.put(JSONObject().put("id", e.engine.id).put("name", e.engine.displayName)
                .put("state", e.status.state.name).put("message", e.lastError ?: e.status.message).put("voices", e.voices))
        }
        return JSONObject().put("app", "rikka-voice").put("version", BuildConfig.VERSION_NAME)
            .put("defaultSpeaker", Engines.defaultVoice()?.id ?: JSONObject.NULL)
            .put("ready", Engines.engines.value.any { it.status.state == EngineState.READY && it.voices > 0 })
            .put("engines", engines)
    }

    private fun speakerOf(req: HttpRequest): Int? = req.query["speaker"]?.toIntOrNull()

    private fun audioQuery(req: HttpRequest): HttpResponse {
        val text = req.query["text"] ?: return HttpResponse.error(422, "缺少 text")
        val sp = speakerOf(req)
        val v = (sp?.let { Engines.voice(it) } ?: Engines.defaultVoice()) ?: return HttpResponse.error(404, "没有可用的声音")
        // 只实现朗读需要的字段；accent_phrases 留空（本 App 合成时以 kana/rikkaText 为准）
        val q = JSONObject()
            .put("accent_phrases", JSONArray())
            .put("speedScale", 1.0).put("pitchScale", 0.0).put("intonationScale", 1.0).put("volumeScale", 1.0)
            .put("prePhonemeLength", 0.1).put("postPhonemeLength", 0.1)
            .put("pauseLength", JSONObject.NULL).put("pauseLengthScale", 1.0)
            .put("outputSamplingRate", 24000).put("outputStereo", false)
            .put("kana", "")
            .put("rikkaText", text).put("rikkaSpeaker", v.id)
        return HttpResponse.json(q.toString())
    }

    private fun synthesis(req: HttpRequest): HttpResponse {
        val q = runCatching { JSONObject(req.bodyText) }.getOrNull() ?: return HttpResponse.error(422, "body 需要是 AudioQuery JSON")
        val text = q.optString("rikkaText").ifBlank { q.optString("kana") }
        if (text.isBlank()) return HttpResponse.error(422, "这个 AudioQuery 不是本 App 生成的（缺少 rikkaText）")
        val sp = speakerOf(req) ?: q.optInt("rikkaSpeaker", -1)
        val params = SynthParams(
            speed = q.optDouble("speedScale", 1.0).toFloat().coerceIn(.5f, 2f),
            pitch = q.optDouble("pitchScale", 0.0).toFloat().coerceIn(-.15f, .15f),
            intonation = q.optDouble("intonationScale", 1.0).toFloat().coerceIn(0f, 2f),
            volume = q.optDouble("volumeScale", 1.0).toFloat().coerceIn(0f, 2f),
        )
        return wav(sp, text, params)
    }

    private fun simpleTts(req: HttpRequest): HttpResponse {
        val text = req.query["text"] ?: req.bodyText.takeIf { it.isNotBlank() } ?: return HttpResponse.error(422, "缺少 text")
        val speed = req.query["speed"]?.toFloatOrNull() ?: 1f
        return wav(speakerOf(req), text, SynthParams(speed = speed.coerceIn(.5f, 2f)))
    }

    private fun rikkaTts(req: HttpRequest): HttpResponse {
        val o = runCatching { JSONObject(req.bodyText) }.getOrNull() ?: return HttpResponse.error(422, "body 需要是 JSON")
        val text = o.optString("text")
        if (text.isBlank()) return HttpResponse.error(422, "缺少 text")
        val sp = if (o.has("speaker") && !o.isNull("speaker")) o.optInt("speaker", -1) else -1
        val params = SynthParams(
            speed = o.optDouble("speed", 1.0).toFloat().coerceIn(.5f, 2f),
            pitch = o.optDouble("pitch", 0.0).toFloat().coerceIn(-.15f, .15f),
            intonation = o.optDouble("intonation", 1.0).toFloat().coerceIn(0f, 2f),
            volume = o.optDouble("volume", 1.0).toFloat().coerceIn(0f, 2f),
        )
        return wav(sp, text, params)
    }

    private fun wav(speaker: Int?, text: String, params: SynthParams): HttpResponse {
        val v = (speaker?.takeIf { it >= 0 }?.let { Engines.voice(it) }) ?: Engines.defaultVoice()
            ?: return HttpResponse.error(503, "六花声库里还没有可用的声音，请先下载模型或连接电脑引擎")
        if (speaker != null && speaker >= 0 && Engines.voice(speaker) == null)
            return HttpResponse.error(404, "声音 $speaker 不存在（可能刚删掉了模型），请在六花里重新选择")
        val pcm = Engines.synthesize(v.id, text, params)
        val extra = mutableMapOf("X-Rikka-Voice" to v.id.toString())
        v.credit?.let { extra["X-Rikka-Credit"] = URLEncoder.encode(it, "UTF-8") }
        return HttpResponse.wav(Wav.encode(pcm), extra)
    }
}
