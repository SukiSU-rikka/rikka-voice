package com.rikka.voice.ui

import android.net.Uri
import com.rikka.voice.core.Engines
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

data class Task(val title: String, val progress: Float?, val error: String? = null, val done: Boolean = false)

/** 下载 / 导入 / 删除模型。进度放在 tasks 里给界面显示。 */
object Installer {
    private val _tasks = MutableStateFlow<Map<String, Task>>(emptyMap())
    val tasks: StateFlow<Map<String, Task>> = _tasks
    private val cancelled = mutableSetOf<String>()

    private val store get() = Engines.store
    private val catalog get() = Engines.catalog

    private fun set(id: String, t: Task?) { _tasks.value = _tasks.value.toMutableMap().apply { if (t == null) remove(id) else put(id, t) } }
    fun dismiss(id: String) = set(id, null)
    fun cancel(id: String) { synchronized(cancelled) { cancelled += id } }

    private fun job(id: String, title: String, affects: List<String>, block: (progress: (Float?) -> Unit, isCancelled: () -> Boolean) -> Unit) {
        if (_tasks.value[id]?.let { !it.done && it.error == null } == true) return
        synchronized(cancelled) { cancelled -= id }
        set(id, Task(title, null))
        Engines.scope.launch {
            try {
                block({ p -> set(id, Task(title, p)) }, { synchronized(cancelled) { id in cancelled } })
                set(id, Task(title, 1f, done = true))
                affects.forEach { Engines.refresh(it, release = true) }
            } catch (t: Throwable) {
                set(id, Task(title, null, error = t.message ?: t.toString()))
            }
        }
    }

    private fun downloadTo(url: String, dest: File, progress: (Float?) -> Unit, isCancelled: () -> Boolean) {
        store.download(url, dest, { done, total -> progress(if (total > 0) done.toFloat() / total else null) }, isCancelled)
    }

    fun installDict() {
        val item = catalog.item("openjtalk")
        job("dict", item.title, listOf("voicevox", "piperplus")) { p, c ->
            val tmp = store.cacheFile(item.url.substringAfterLast('/'))
            downloadTo(item.url, tmp, p, c)
            p(null); store.extract(tmp, store.openJtalkDict); tmp.delete()
        }
    }

    fun installVvm(file: String) {
        val item = catalog.vvmItems().first { it.target == file }
        job("vvm:$file", "VOICEVOX ${item.title}", listOf("voicevox")) { p, c ->
            downloadTo(item.url, File(store.dir("vvm"), file), p, c)
        }
    }

    fun installSupertonic() {
        val item = catalog.item("supertonic")
        job("supertonic", item.title, listOf("supertonic")) { p, c ->
            val tmp = store.cacheFile(item.url.substringAfterLast('/'))
            downloadTo(item.url, tmp, p, c)
            p(null); store.extract(tmp, store.dir(item.target)); tmp.delete()
        }
    }

    fun installPiper() {
        val (model, config) = catalog.piperFiles()
        job("piper", "piper-plus つくよみちゃん", listOf("piperplus")) { p, c ->
            val dir = store.dir("piper-plus")
            val name = model.substringAfterLast('/').substringBefore('?')
            downloadTo(config, File(dir, "$name.json"), { }, c)
            downloadTo(model, File(dir, name), p, c)
        }
    }

    /** 导入：按文件名自动判断放哪里。 */
    fun import(uri: Uri, hint: String) {
        val name = store.displayName(uri)
        job("import:$name", "导入 $name", listOf("voicevox", "supertonic", "piperplus")) { p, _ ->
            val lower = name.lowercase()
            when {
                lower.endsWith(".vvm") -> store.copyIn(uri, File(store.dir("vvm"), name))
                lower.endsWith(".onnx") || (hint == "piperplus" && lower.endsWith(".json")) -> {
                    val dir = store.dir("piper-plus")
                    val target = if (lower.endsWith(".json") && !lower.endsWith(".onnx.json")) "config.json" else name
                    store.copyIn(uri, File(dir, target))
                }
                store.isArchive(lower) -> {
                    val tmp = store.cacheFile(name)
                    store.copyIn(uri, tmp)
                    p(null)
                    val dest = when {
                        lower.contains("open_jtalk") || hint == "dict" -> store.openJtalkDict
                        lower.contains("supertonic") || hint == "supertonic" -> store.dir("supertonic-3")
                        else -> error("不知道这个压缩包是什么模型：$name（词典请保留 open_jtalk 字样）")
                    }
                    store.extract(tmp, dest, name); tmp.delete()
                }
                else -> error("不认识的文件：$name")
            }
        }
    }

    fun delete(what: String) {
        val target: File = when {
            what == "dict" -> store.openJtalkDict
            what.startsWith("vvm:") -> File(store.dir("vvm"), what.removePrefix("vvm:"))
            what == "supertonic" -> store.dir("supertonic-3")
            what == "piper" -> store.dir("piper-plus")
            else -> return
        }
        Engines.scope.launch {
            listOf("voicevox", "supertonic", "piperplus").forEach { Engines.engine(it)?.let { e -> runCatching { e.release() } } }
            target.deleteRecursively()
            listOf("voicevox", "supertonic", "piperplus").forEach { Engines.refresh(it, release = true) }
        }
    }

    fun installedVvms(): Set<String> = store.dir("vvm").listFiles()?.filter { it.name.endsWith(".vvm") }?.map { it.name }?.toSet() ?: emptySet()
    fun hasSupertonic() = File(store.dir("supertonic-3"), "tts.json").isFile
    fun hasPiper() = store.dir("piper-plus").listFiles()?.any { it.name.endsWith(".onnx") } == true
}
