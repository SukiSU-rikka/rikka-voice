package com.rikka.voice.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rikka.voice.BuildConfig
import com.rikka.voice.core.Engines

@Composable
fun AboutScreen(modifier: Modifier) {
    val voices by Engines.voices.collectAsStateWithLifecycle()
    var threads by remember { mutableFloatStateOf(Engines.prefs.threads.toFloat()) }
    var sysSpeed by remember { mutableFloatStateOf(Engines.prefs.systemSpeed) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 10.dp)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            Text("关于与设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Hint("六花声库 ${BuildConfig.VERSION_NAME}")
        }

        SectionCard("性能") {
            Text("本机合成线程：${threads.toInt()}", style = MaterialTheme.typography.bodyMedium)
            Slider(threads, { threads = it }, valueRange = 1f..8f, steps = 6, onValueChangeFinished = {
                Engines.prefs.threads = threads.toInt(); Engines.refresh(release = true)
            })
            Hint("线程越多合成越快，但更耗电发热，和播放抢 CPU 时可能出现杂音。默认 2 线程，合成速度已经远快于朗读速度。")
            Text("系统朗读语速倍率：${"%.2f".format(sysSpeed)}×", style = MaterialTheme.typography.bodyMedium)
            Slider(sysSpeed, { sysSpeed = it }, valueRange = .6f..1.6f, onValueChangeFinished = { Engines.prefs.systemSpeed = sysSpeed })
            OutlinedButton(onClick = { Engines.clearCache() }) { Text("清空语音缓存") }
        }

        val credits = voices.mapNotNull { it.credit }.distinct()
        SectionCard("声音署名", "VOICEVOX 的声音在公开使用（发视频、发布音频等）时必须标注下面的署名；只在自己手机上听不需要。") {
            if (credits.isEmpty()) Hint("当前没有需要署名的声音。")
            else Text(credits.joinToString("、"), style = MaterialTheme.typography.bodyMedium)
        }

        SectionCard("给开发者：接口", "其他 App 也可以用（只限本机）。") {
            Text(
                "GET  /speakers            VOICEVOX 兼容\n" +
                "POST /audio_query?text=&speaker=\n" +
                "POST /synthesis?speaker=  → wav\n" +
                "POST /tts?text=&speaker=  → wav\n" +
                "GET  /rikka/voices\n" +
                "POST /rikka/tts {text,speaker,speed,pitch,intonation,volume}",
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionCard("开源组件") {
            Hint(
                "· VOICEVOX CORE — MIT（预编译库、声音包各有使用规约）\n" +
                "· sherpa-onnx — Apache-2.0；Supertonic 3 模型许可见其模型页\n" +
                "· piper-plus — MIT；つくよみちゃん 模型另有条款\n" +
                "· Open JTalk 词典 — 修改 BSD\n" +
                "· Apache Commons Compress — Apache-2.0\n" +
                "· 电脑端 AivisSpeech / VOICEVOX ENGINE / Style-Bert-VITS2 / GPT-SoVITS 由各自项目提供"
            )
        }
        Row(Modifier.padding(20.dp)) { Hint("六花声库只在本机处理文字，不会把阅读内容发到网上（连接电脑引擎时只发给你自己的电脑）。") }
    }
}
