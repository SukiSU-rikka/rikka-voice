package com.rikka.voice.core

/** 一段 16-bit 单声道 PCM。所有引擎最终都转换成这个格式。 */
class Pcm(val samples: ShortArray, val sampleRate: Int) {
    val seconds: Double get() = samples.size.toDouble() / sampleRate
}

/** 合成参数，取值含义与 VOICEVOX AudioQuery 一致，方便直接转发。 */
data class SynthParams(
    val speed: Float = 1f,        // speedScale 0.5–2.0
    val pitch: Float = 0f,        // pitchScale -0.15–0.15（0 = 原调）
    val intonation: Float = 1f,   // intonationScale 0–2
    val volume: Float = 1f,       // volumeScale 0–2
) {
    fun cacheKey() = String.format(java.util.Locale.US, "%.2f|%.3f|%.2f|%.2f", speed, pitch, intonation, volume)
}

data class EngineVoice(
    val key: String,          // 引擎内部的声音标识，如 "vv:3"
    val character: String,    // 角色/模型名
    val style: String,        // 风格名
    val credit: String? = null,
)

enum class EngineState { READY, NEEDS_MODEL, NOT_BUILT, ERROR }

data class EngineStatus(val state: EngineState, val message: String)

/**
 * 每个“项目”实现一个引擎。
 * 线程安全由 [Engines] 保证：同一个引擎同一时间只会有一个合成调用。
 */
interface TtsEngine {
    val id: String
    val displayName: String
    val description: String
    val isRemote: Boolean

    /** 只检查文件/配置，不要做重活。 */
    fun status(): EngineStatus

    /** 加载模型并返回可用声音。可能耗时，会在后台线程调用；失败直接抛异常。 */
    fun loadVoices(): List<EngineVoice>

    fun synthesize(text: String, voiceKey: String, params: SynthParams): Pcm

    /** 释放模型占用的内存（模型文件变化后会调用）。 */
    fun release() {}

    /** 引擎自己能处理哪些参数；不能处理的由 [Engines] 在 PCM 上补做（音量）。 */
    val handlesVolume: Boolean get() = false
}

/** 没有编进当前 APK 的引擎，用来在界面上展示“可以开启”。 */
class NotBuiltEngine(
    override val id: String,
    override val displayName: String,
    override val description: String,
    private val hint: String,
) : TtsEngine {
    override val isRemote = false
    override fun status() = EngineStatus(EngineState.NOT_BUILT, hint)
    override fun loadVoices() = emptyList<EngineVoice>()
    override fun synthesize(text: String, voiceKey: String, params: SynthParams): Pcm =
        throw IllegalStateException("$displayName 没有编入这个版本：$hint")
}
