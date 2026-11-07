package com.rikka.voice.core

import android.content.Context
import android.util.Log
import com.rikka.voice.engines.remote.RemoteEngines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class VoiceInfo(
    val id: Int,
    val engineId: String,
    val engineName: String,
    val key: String,
    val character: String,
    val style: String,
    val credit: String?,
    val remote: Boolean,
) {
    val label: String get() = if (style.isBlank()) character else "$character（$style）"
}

data class EngineView(val engine: TtsEngine, val status: EngineStatus, val voices: Int, val lastError: String?)

/**
 * 全局引擎表。HTTP 接口、系统 TTS、界面都通过这里合成，保证：
 *  - 同一个引擎同一时间只合成一段（本地模型大多不是线程安全的）
 *  - 声音 ID 固定（六花里选好的声音不会因为多装了模型而变）
 */
object Engines {
    private const val TAG = "RikkaEngines"
    lateinit var app: Context; private set
    lateinit var prefs: Prefs; private set
    lateinit var store: ModelStore; private set
    lateinit var catalog: Catalog; private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _engines = MutableStateFlow<List<EngineView>>(emptyList())
    val engines: StateFlow<List<EngineView>> = _engines
    private val _voices = MutableStateFlow<List<VoiceInfo>>(emptyList())
    val voices: StateFlow<List<VoiceInfo>> = _voices
    @Volatile private var firstLoadDone = false
    /** 进程刚被拉起时（例如六花通过直连通道唤醒），等第一次声音列表载入完成。 */
    fun awaitFirstLoad(timeoutMs: Long = 25_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!firstLoadDone && System.currentTimeMillis() < end) Thread.sleep(50)
    }
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private var list: List<TtsEngine> = emptyList()
    private val errors = mutableMapOf<String, String>()
    private val counts = mutableMapOf<String, Int>()
    private val locks = mutableMapOf<String, Any>()
    private val refreshMutex = Mutex()
    private val cache = object : LinkedHashMap<String, Pcm>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pcm>?) = size > 40
    }

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        prefs = Prefs(app); store = ModelStore(app); catalog = Catalog(app)
        rebuild()
        refresh()
    }

    /** 本地引擎按类名反射创建：没编进 APK 的显示为“未编入”。 */
    private fun localEngines(): List<TtsEngine> = listOf(
        Triple("com.rikka.voice.engines.VoicevoxEngine", "voicevox", "VOICEVOX（本机）"),
        Triple("com.rikka.voice.engines.SupertonicEngine", "supertonic", "Supertonic 3（本机）"),
        Triple("com.rikka.voice.engines.PiperPlusEngine", "piperplus", "piper-plus（本机 · 实验）"),
    ).map { (cls, id, name) ->
        runCatching { Class.forName(cls).getConstructor(Context::class.java).newInstance(app) as TtsEngine }
            .getOrElse {
                Log.i(TAG, "$cls not built: $it")
                NotBuiltEngine(id, name, notBuiltDescription(id), "构建时在 gradle.properties 打开 rikka.engine.$id 即可编入")
            }
    }

    private fun notBuiltDescription(id: String) = when (id) {
        "voicevox" -> "VOICEVOX 官方核心，日语重音准，角色多。"
        "supertonic" -> "sherpa-onnx 运行的 Supertonic 3，轻快，31 种语言。"
        else -> "piper-plus，体积最小、速度最快的日语 VITS。"
    }

    fun rebuild() {
        list.forEach { runCatching { it.release() } }
        list = localEngines() + prefs.remotes.map { RemoteEngines.create(it) }
        publish()
    }

    fun engine(id: String): TtsEngine? = list.firstOrNull { it.id == id }

    private fun publish() {
        _engines.value = list.map { EngineView(it, runCatching { it.status() }.getOrElse { e -> EngineStatus(EngineState.ERROR, e.message ?: "$e") }, counts[it.id] ?: 0, errors[it.id]) }
    }

    /** 重新加载所有（或某个）引擎的声音列表。 */
    fun refresh(onlyEngine: String? = null, release: Boolean = false) {
        scope.launch {
            refreshMutex.withLock {
                _loading.value = true
                try {
                    val idMap = prefs.idMap()
                    val keep = _voices.value.filter { onlyEngine != null && it.engineId != onlyEngine }.toMutableList()
                    val used = idMap.values.toMutableSet()
                    for (e in list) {
                        if (onlyEngine != null && e.id != onlyEngine) continue
                        synchronized(lockOf(e.id)) {
                            if (release) runCatching { e.release() }
                            errors.remove(e.id); counts[e.id] = 0
                            val st = runCatching { e.status() }.getOrNull()
                            if (st?.state != EngineState.READY) return@synchronized
                            try {
                                val vs = e.loadVoices()
                                counts[e.id] = vs.size
                                for (v in vs) {
                                    val gk = e.id + "|" + v.key
                                    val id = idMap[gk] ?: run {
                                        val preferred = if (e.id == "voicevox") v.key.removePrefix("vv:").toIntOrNull() else null
                                        val pick = if (preferred != null && preferred !in used && preferred < 100000) preferred
                                        else (maxOf(100000, (used.maxOrNull() ?: 0) + 1))
                                        idMap[gk] = pick; used += pick; pick
                                    }
                                    keep += VoiceInfo(id, e.id, e.displayName, v.key, v.character, v.style, v.credit, e.isRemote)
                                }
                            } catch (t: Throwable) {
                                Log.w(TAG, "load ${e.id}", t)
                                errors[e.id] = explain(t)
                            }
                        }
                    }
                    prefs.saveIdMap(idMap)
                    _voices.value = keep.sortedWith(compareBy({ it.remote }, { it.engineId }, { it.id }))
                    if (_voices.value.none { it.id == prefs.defaultVoice }) _voices.value.firstOrNull()?.let { prefs.defaultVoice = it.id }
                    synchronized(cache) { cache.clear() }
                } finally {
                    publish(); _loading.value = false; firstLoadDone = true
                }
            }
        }
    }

    /** 把常见的底层错误翻译成人话。 */
    fun explain(t: Throwable): String {
        val raw = "${t.javaClass.simpleName}: ${t.message ?: ""}"
        return when (t) {
            is NoSuchFieldError, is NoSuchMethodError ->
                "引擎库和代码对不上（$raw）。通常是打包时开了混淆，或 jar 与 .so 版本不一致——见构建说明“常见错误”"
            is UnsatisfiedLinkError -> "缺少原生库（$raw）。检查 jniLibs/arm64-v8a 里的 .so"
            is OutOfMemoryError -> "内存不足：声音包太多或线程太多，试试在“关于”页减少线程"
            else -> t.message ?: raw
        }
    }

    private fun lockOf(id: String) = synchronized(locks) { locks.getOrPut(id) { Any() } }

    fun voice(id: Int): VoiceInfo? = _voices.value.firstOrNull { it.id == id }
    fun defaultVoice(): VoiceInfo? = voice(prefs.defaultVoice) ?: _voices.value.firstOrNull()

    /** 阻塞合成（在后台线程调用）。 */
    fun synthesize(voiceId: Int?, text: String, params: SynthParams = SynthParams()): Pcm {
        val v = (if (voiceId != null && voiceId >= 0) voice(voiceId) else null) ?: defaultVoice()
            ?: error("还没有可用的声音：请先在“声音”页下载模型，或在“电脑”页连接电脑上的引擎")
        val clean = TextSplit.clean(text)
        require(clean.isNotEmpty()) { "没有要朗读的文字" }
        val ck = "${v.id}|${params.cacheKey()}|$clean"
        synchronized(cache) { cache[ck] }?.let { return it }
        val e = engine(v.engineId) ?: error("引擎 ${v.engineId} 不存在")
        val pcm = synchronized(lockOf(e.id)) {
            val raw = e.synthesize(clean, v.key, params)
            if (e.handlesVolume) raw else Wav.scaleVolume(raw, params.volume)
        }
        synchronized(cache) { cache[ck] = pcm }
        return pcm
    }

    fun clearCache() = synchronized(cache) { cache.clear() }
}
