package com.rikka.voice.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 远程（电脑）引擎的配置。 */
data class RemoteConfig(
    val id: String,
    val type: String,          // voicevox | sbv2 | gptsovits
    val name: String,
    val base: String,
    val refAudio: String = "",
    val promptText: String = "",
    val promptLang: String = "ja",
    val gptWeights: String = "",      // GPT-SoVITS 微调后的 GPT 权重（电脑上的路径，可空）
    val sovitsWeights: String = "",   // GPT-SoVITS 微调后的 SoVITS 权重（电脑上的路径，可空）
) {
    fun toJson() = JSONObject().put("id", id).put("type", type).put("name", name).put("base", base)
        .put("refAudio", refAudio).put("promptText", promptText).put("promptLang", promptLang)
        .put("gptWeights", gptWeights).put("sovitsWeights", sovitsWeights)

    companion object {
        fun from(o: JSONObject) = RemoteConfig(
            o.getString("id"), o.getString("type"), o.optString("name"), o.getString("base"),
            o.optString("refAudio"), o.optString("promptText"), o.optString("promptLang", "ja"),
            o.optString("gptWeights"), o.optString("sovitsWeights"),
        )
    }
}

class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("rikka_voice", Context.MODE_PRIVATE)

    var serverEnabled: Boolean
        get() = sp.getBoolean("server", false)
        set(v) = sp.edit().putBoolean("server", v).apply()

    var startOnBoot: Boolean
        get() = sp.getBoolean("boot", false)
        set(v) = sp.edit().putBoolean("boot", v).apply()

    var port: Int
        get() = sp.getInt("port", 50021)
        set(v) = sp.edit().putInt("port", v).apply()

    /** 系统 TTS 和未指定声音的请求用的默认声音（公开 ID）。 */
    var defaultVoice: Int
        get() = sp.getInt("default_voice", -1)
        set(v) = sp.edit().putInt("default_voice", v).apply()

    var threads: Int
        get() = sp.getInt("threads", 4)
        set(v) = sp.edit().putInt("threads", v).apply()

    /** 首页“试听”里自己输入的文字（以前不保存，切页面就没了） */
    var tryText: String
        get() = sp.getString("try_text", null) ?: "今日も、少しずつ日本語を読んでいこう。物語の続きが、待っているよ。"
        set(v) = sp.edit().putString("try_text", v).apply()

    var systemSpeed: Float
        get() = sp.getFloat("sys_speed", 1f)
        set(v) = sp.edit().putFloat("sys_speed", v).apply()

    var remotes: List<RemoteConfig>
        get() = runCatching {
            val a = JSONArray(sp.getString("remotes", "[]"))
            (0 until a.length()).map { RemoteConfig.from(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        set(v) = sp.edit().putString("remotes", JSONArray().apply { v.forEach { put(it.toJson()) } }.toString()).apply()

    /** 声音 key → 公开 ID 的固定映射，保证六花里选好的声音重启后不会变。 */
    fun idMap(): MutableMap<String, Int> = runCatching {
        val o = JSONObject(sp.getString("id_map", "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getInt(it) }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    fun saveIdMap(m: Map<String, Int>) = sp.edit().putString("id_map", JSONObject(m as Map<*, *>).toString()).apply()
}
