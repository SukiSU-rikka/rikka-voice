package com.rikka.voice.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rikka.voice.core.Engines
import com.rikka.voice.core.SynthParams
import com.rikka.voice.service.HostService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(modifier: Modifier, goVoices: () -> Unit) {
    val ctx = LocalContext.current
    val running by HostService.state.collectAsStateWithLifecycle()
    val hostError by HostService.error.collectAsStateWithLifecycle()
    val voices by Engines.voices.collectAsStateWithLifecycle()
    val loading by Engines.loading.collectAsStateWithLifecycle()
    var defaultId by remember { mutableIntStateOf(Engines.prefs.defaultVoice) }
    var boot by remember { mutableStateOf(Engines.prefs.startOnBoot) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        // ── 顶部 ──
        Box(
            Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF8E74C4), Color(0xFFC6A9E8), Color(0xFFF2C6DA))))
                .padding(22.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("六花声库", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
                Text("给六花日语，也给整台手机的本地日语朗读", color = Color.White.copy(alpha = .92f))
                Text(
                    if (loading) "正在载入声音…" else "${voices.size} 个声音可用 · ${voices.count { !it.remote }} 个在本机",
                    color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // ── 连接状态 ──
        SectionCard("连接方式") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("始终可用", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                Text("六花日语直连通道", fontWeight = FontWeight.Medium)
            }
            Hint("六花日语通过系统直接调用六花声库，不开网络端口，不怕后台被清理，也不需要任何白名单。六花一朗读，系统就会自动把这里唤醒。")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("始终可用", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                Text("系统朗读引擎", fontWeight = FontWeight.Medium)
            }
            OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS")) } }) { Text("设为系统朗读") }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .4f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("HTTP 接口（可选）", fontWeight = FontWeight.Medium)
                    Text(running?.let { "运行中 · $it" } ?: "未开启 · 只有别的 App 要用 VOICEVOX 接口时才需要",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (running != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = running != null, onCheckedChange = { if (it) HostService.start(ctx) else HostService.stop(ctx) })
            }
            hostError?.let { Hint(it) }
            if (running != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("开机后自动开启 HTTP 接口", Modifier.weight(1f))
                Switch(checked = boot, onCheckedChange = { boot = it; Engines.prefs.startOnBoot = it })
            }
        }

        if (voices.isEmpty() && !loading) {
            SectionCard("还没有声音", "先装一个声音才能朗读：本机推荐 VOICEVOX（词典 + 声音包 0 号），也可以连接电脑上的 AivisSpeech。") {
                Button(onClick = goVoices) { Text("去安装声音") }
            }
        }

        // ── 默认声音 ──
        if (voices.isNotEmpty()) {
            SectionCard("默认声音", "六花日语没指定声音、以及系统朗读时用这个。") {
                VoicePicker(voices, defaultId, "默认声音") { defaultId = it.id; Engines.prefs.defaultVoice = it.id }
                voices.firstOrNull { it.id == defaultId }?.credit?.let { Hint("使用时需标注：$it") }
            }
            TryCard(defaultId)
        }
    }
}

@Composable
private fun TryCard(defaultId: Int) {
    val voices by Engines.voices.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf(Engines.prefs.tryText) }
    var voiceId by remember(defaultId) { mutableIntStateOf(defaultId) }
    var speed by remember { mutableFloatStateOf(1f) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    SectionCard("试听") {
        VoicePicker(voices, voiceId, "声音") { voiceId = it.id }
        OutlinedTextField(text, { text = it; Engines.prefs.tryText = it }, Modifier.fillMaxWidth(), minLines = 2, shape = RoundedCornerShape(14.dp), label = { Text("日语文本（会自动保存）") })
        Text("语速 ${"%.2f".format(speed)}×", style = MaterialTheme.typography.bodySmall)
        Slider(speed, { speed = it }, valueRange = .6f..1.6f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(enabled = !busy, onClick = {
                busy = true; status = "合成中…"
                scope.launch {
                    val t0 = System.nanoTime()
                    val r = withContext(Dispatchers.IO) { runCatching { Engines.synthesize(voiceId, text, SynthParams(speed = speed)) } }
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    r.onSuccess { Player.play(it); status = "合成 ${ms} 毫秒 · 音频 ${"%.1f".format(it.seconds)} 秒" }
                        .onFailure { status = "失败：${it.message}" }
                    busy = false
                }
            }) { Icon(Icons.Rounded.PlayArrow, null); Gap(4); Text("试听") }
            OutlinedButton(onClick = { Player.stop() }) { Icon(Icons.Rounded.Stop, null); Gap(4); Text("停止") }
        }
        if (status.isNotEmpty()) Hint(status)
    }
}
