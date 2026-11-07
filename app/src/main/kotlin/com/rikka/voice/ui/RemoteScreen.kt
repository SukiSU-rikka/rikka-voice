package com.rikka.voice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rikka.voice.core.Engines
import com.rikka.voice.core.RemoteConfig
import com.rikka.voice.engines.remote.RemoteEngines
import java.util.UUID

@Composable
fun RemoteScreen(modifier: Modifier) {
    val engines by Engines.engines.collectAsStateWithLifecycle()
    val voices by Engines.voices.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<RemoteConfig?>(null) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 10.dp)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            Text("电脑引擎", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Hint("电脑开着、手机和电脑在同一个 Wi-Fi 时，用电脑上更有感情的大模型声音。连上后，这些声音和本机声音一样出现在列表里，六花和系统朗读都能用。")
        }

        engines.filter { it.engine.isRemote }.forEach { ev ->
            val cfg = Engines.prefs.remotes.firstOrNull { "remote:" + it.id == ev.engine.id } ?: return@forEach
            SectionCard(ev.engine.displayName, ev.engine.description) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StateChip(if (ev.lastError != null) com.rikka.voice.core.EngineState.ERROR else ev.status.state)
                    Text(ev.lastError ?: "${cfg.base} · ${ev.voices} 个声音", style = MaterialTheme.typography.bodySmall,
                        color = if (ev.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { Engines.refresh(ev.engine.id) }) { Text("重新连接") }
                    TextButton(onClick = { editing = cfg }) { Text("编辑") }
                    TextButton(onClick = {
                        Engines.prefs.remotes = Engines.prefs.remotes.filter { it.id != cfg.id }
                        Engines.rebuild(); Engines.refresh()
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
                val vs = voices.filter { it.engineId == ev.engine.id }
                if (vs.isNotEmpty()) { HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .4f)); VoiceList(vs) }
            }
        }

        val e = editing
        if (e != null) RemoteForm(e, onDone = { editing = null })
        else SectionCard("添加电脑引擎") {
            Button(onClick = { editing = RemoteConfig(UUID.randomUUID().toString().take(8), "voicevox", "AivisSpeech", "http://192.168.1.10:10101") }) { Text("＋ 添加") }
        }

        SectionCard("电脑端怎么开", null) {
            Hint(
                "· AivisSpeech：设置 → 允许局域网访问（或启动参数 --host 0.0.0.0），端口 10101。\n" +
                "· VOICEVOX：引擎启动参数加 --host 0.0.0.0，端口 50021。\n" +
                "· Style-Bert-VITS2：运行 server_fastapi.py，端口 5000。\n" +
                "· GPT-SoVITS：运行 api_v2.py -a 0.0.0.0，端口 9880，需要参考音频。\n" +
                "· 电脑地址：Windows 在命令行输入 ipconfig，看 IPv4 地址（通常是 192.168.x.x）。\n" +
                "· Windows 防火墙第一次会弹窗，记得允许“专用网络”。"
            )
        }
    }
}

@Composable
private fun RemoteForm(init: RemoteConfig, onDone: () -> Unit) {
    var type by remember { mutableStateOf(init.type) }
    var name by remember { mutableStateOf(init.name) }
    var base by remember { mutableStateOf(init.base) }
    var ref by remember { mutableStateOf(init.refAudio) }
    var prompt by remember { mutableStateOf(init.promptText) }
    val exists = Engines.prefs.remotes.any { it.id == init.id }

    SectionCard(if (exists) "编辑电脑引擎" else "添加电脑引擎") {
        RemoteEngines.types.forEach { (t, label) ->
            FilterChip(selected = type == t, onClick = {
                val oldPort = ":" + RemoteEngines.defaultPort(type)
                if (base.endsWith(oldPort)) base = base.removeSuffix(oldPort) + ":" + RemoteEngines.defaultPort(t)
                if (name.isBlank() || name in listOf("AivisSpeech", "Style-Bert-VITS2", "GPT-SoVITS")) name = when (t) { "voicevox" -> "AivisSpeech"; "sbv2" -> "Style-Bert-VITS2"; else -> "GPT-SoVITS" }
                type = t
            }, label = { Text(label) })
        }
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("名称") }, singleLine = true, shape = RoundedCornerShape(14.dp))
        OutlinedTextField(base, { base = it.trim() }, Modifier.fillMaxWidth(), label = { Text("地址（http://电脑IP:端口）") }, singleLine = true, shape = RoundedCornerShape(14.dp))
        if (type == "gptsovits") {
            OutlinedTextField(ref, { ref = it }, Modifier.fillMaxWidth(), label = { Text("参考音频路径（电脑上的路径）") }, singleLine = true, shape = RoundedCornerShape(14.dp))
            OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text("参考音频里说的日语原文") }, shape = RoundedCornerShape(14.dp))
            Hint("GPT-SoVITS 会模仿参考音频的音色和语气：参考音频开心，读出来也偏开心。")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val c = RemoteConfig(init.id, type, name.trim(), base.trimEnd('/'), ref.trim(), prompt.trim(), "ja")
                Engines.prefs.remotes = Engines.prefs.remotes.filter { it.id != c.id } + c
                Engines.rebuild(); Engines.refresh("remote:" + c.id)
                onDone()
            }) { Text("保存并连接") }
            TextButton(onClick = onDone) { Text("取消") }
        }
    }
}
