package com.rikka.voice.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rikka.voice.core.EngineState
import com.rikka.voice.core.EngineView
import com.rikka.voice.core.Engines
import com.rikka.voice.core.SynthParams
import com.rikka.voice.core.VoiceInfo
import kotlinx.coroutines.launch

@Composable
fun VoicesScreen(modifier: Modifier) {
    val engines by Engines.engines.collectAsStateWithLifecycle()
    val voices by Engines.voices.collectAsStateWithLifecycle()
    val tasks by Installer.tasks.collectAsStateWithLifecycle()
    val loading by Engines.loading.collectAsStateWithLifecycle()
    var importHint by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) Installer.import(uri, importHint)
    }
    fun pick(hint: String) { importHint = hint; picker.launch(arrayOf("*/*")) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("本机声音", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Hint(if (loading) "正在载入…" else "模型都在本机运行，不联网也能读")
                }
                IconButton(onClick = { Engines.refresh(release = true) }) { Icon(Icons.Rounded.Refresh, "刷新") }
            }
        }
        if (tasks.isNotEmpty()) item {
            SectionCard("进行中") { tasks.forEach { (id, t) -> TaskRow(id, t) } }
        }
        items(engines.filter { !it.engine.isRemote }, key = { it.engine.id }) { ev ->
            EngineCard(ev, voices.filter { it.engineId == ev.engine.id }, tasks, ::pick)
        }
        item {
            SectionCard("用电脑下载再导入", "手机直连 GitHub / Hugging Face 慢的话：在电脑下载好，传到手机，再点各引擎的“导入”。") {
                Hint("· 词典：open_jtalk_dic_utf_8-1.11.tar.gz\n· VOICEVOX：*.vvm（官方下载器 download --only models 得到）\n· Supertonic：sherpa-onnx-supertonic-3-tts-*.tar.bz2\n· piper-plus：模型 .onnx 和 config.json")
            }
        }
    }
}

@Composable
private fun EngineCard(ev: EngineView, voices: List<VoiceInfo>, tasks: Map<String, Task>, pick: (String) -> Unit) {
    val e = ev.engine
    SectionCard(e.displayName, e.description) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StateChip(if (ev.lastError != null) EngineState.ERROR else ev.status.state)
            Text(ev.lastError?.let { "载入失败：$it" } ?: ev.status.message, style = MaterialTheme.typography.bodySmall,
                color = if (ev.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (ev.status.state == EngineState.NOT_BUILT) return@SectionCard

        when (e.id) {
            "voicevox" -> {
                DictRow(tasks, pick)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .4f))
                val installed = Installer.installedVvms()
                Engines.catalog.vvmItems().forEach { item ->
                    val has = item.target in installed
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, fontWeight = FontWeight.Medium)
                            Hint(item.note)
                        }
                        if (has) TextButton(onClick = { Installer.delete("vvm:${item.target}") }) { Text("删除") }
                        else FilledTonalButton(enabled = tasks["vvm:${item.target}"] == null, onClick = { Installer.installVvm(item.target) }) { Text("下载") }
                    }
                }
                (installed - Engines.catalog.vvmItems().map { it.target }.toSet()).forEach { extra ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已导入 $extra", Modifier.weight(1f))
                        TextButton(onClick = { Installer.delete("vvm:$extra") }) { Text("删除") }
                    }
                }
                OutlinedButton(onClick = { pick("vvm") }) { Text("导入 .vvm 文件") }
                Hint("声音包来自 VOICEVOX 各角色，下载即表示同意对应角色的使用规约；公开使用朗读音频时要标注“VOICEVOX:角色名”。")
            }
            "supertonic" -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(Engines.catalog.item("supertonic").title, fontWeight = FontWeight.Medium)
                        Hint(Engines.catalog.item("supertonic").note)
                    }
                    if (Installer.hasSupertonic()) TextButton(onClick = { Installer.delete("supertonic") }) { Text("删除") }
                    else FilledTonalButton(enabled = tasks["supertonic"] == null, onClick = { Installer.installSupertonic() }) { Text("下载") }
                }
                OutlinedButton(onClick = { pick("supertonic") }) { Text("导入 .tar.bz2 模型包") }
            }
            "piperplus" -> {
                DictRow(tasks, pick)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("つくよみちゃん（piper-plus 官方示例声音）", fontWeight = FontWeight.Medium)
                        Hint("模型权重另有使用条款，请看 Hugging Face 模型页")
                    }
                    if (Installer.hasPiper()) TextButton(onClick = { Installer.delete("piper") }) { Text("删除") }
                    else FilledTonalButton(enabled = tasks["piper"] == null, onClick = { Installer.installPiper() }) { Text("下载") }
                }
                OutlinedButton(onClick = { pick("piperplus") }) { Text("导入 .onnx / config.json") }
            }
        }

        if (voices.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .4f))
            VoiceList(voices)
        }
    }
}

@Composable
private fun DictRow(tasks: Map<String, Task>, pick: (String) -> Unit) {
    val item = Engines.catalog.item("openjtalk")
    val has = Engines.store.hasOpenJtalkDict()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(item.title + if (has) " ✓" else "", fontWeight = FontWeight.Medium)
            Hint(item.note + "　" + item.size)
        }
        if (!has) {
            FilledTonalButton(enabled = tasks["dict"] == null, onClick = { Installer.installDict() }) { Text("下载") }
        }
    }
    if (!has) TextButton(onClick = { pick("dict") }) { Text("或导入词典压缩包") }
}

@Composable
fun VoiceList(voices: List<VoiceInfo>) {
    var def by remember { mutableIntStateOf(Engines.prefs.defaultVoice) }
    var playing by remember { mutableStateOf<Int?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    Text("声音（点圆点设为默认）", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    voices.forEach { v ->
        Row(Modifier.fillMaxWidth().clickable { def = v.id; Engines.prefs.defaultVoice = v.id }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = def == v.id, onClick = { def = v.id; Engines.prefs.defaultVoice = v.id })
            Column(Modifier.weight(1f)) {
                Text(v.label)
                Hint("编号 ${v.id}" + (v.credit?.let { " · $it" } ?: ""))
            }
            IconButton(enabled = playing == null, onClick = {
                playing = v.id; err = null
                Engines.scope.launch {
                    runCatching { Engines.synthesize(v.id, "こんにちは。今日も一緒に読みましょう。", SynthParams()) }
                        .onSuccess { Player.play(it) }.onFailure { err = "${v.label}：${it.message}" }
                    playing = null
                }
            }) { Icon(Icons.Rounded.PlayArrow, "试听") }
        }
    }
    err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}
