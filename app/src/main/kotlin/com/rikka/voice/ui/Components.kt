package com.rikka.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rikka.voice.core.EngineState
import com.rikka.voice.core.VoiceInfo

@Composable
fun SectionCard(title: String, subtitle: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
fun Chip(text: String, color: Color, onColor: Color) {
    Text(
        text, fontSize = 12.sp, color = onColor, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
fun StateChip(state: EngineState) {
    val cs = MaterialTheme.colorScheme
    when (state) {
        EngineState.READY -> Chip("就绪", cs.primaryContainer, cs.onPrimaryContainer)
        EngineState.NEEDS_MODEL -> Chip("待安装", cs.tertiaryContainer, cs.onSurface)
        EngineState.NOT_BUILT -> Chip("未编入", cs.surfaceVariant, cs.onSurfaceVariant)
        EngineState.ERROR -> Chip("出错", cs.error, cs.onPrimary)
    }
}

@Composable
fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun TaskRow(id: String, task: Task) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(task.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            when {
                task.error != null || task.done -> TextButton(onClick = { Installer.dismiss(id) }) { Text("知道了") }
                else -> TextButton(onClick = { Installer.cancel(id) }) { Text("取消") }
            }
        }
        val err = task.error
        val prog = task.progress
        when {
            err != null -> Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            task.done -> Hint("完成")
            prog != null -> LinearProgressIndicator(progress = { prog }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))
            else -> LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePicker(voices: List<VoiceInfo>, selected: Int?, label: String, onPick: (VoiceInfo) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val cur = voices.firstOrNull { it.id == selected }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = cur?.let { "${it.label} · ${it.engineName}" } ?: "未选择",
            onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            voices.groupBy { it.engineName }.forEach { (engine, vs) ->
                DropdownMenuItem(text = { Text(engine, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) }, onClick = {}, enabled = false)
                vs.forEach { v ->
                    DropdownMenuItem(text = { Text("${v.label}  #${v.id}") }, onClick = { onPick(v); open = false })
                }
            }
        }
    }
}

@Composable
fun Gap(w: Int = 8) = Spacer(Modifier.width(w.dp))
