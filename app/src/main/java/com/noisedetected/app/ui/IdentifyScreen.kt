package com.noisedetected.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import com.noisedetected.app.LiveState
import com.noisedetected.core.compare.Condition
import com.noisedetected.core.inference.Candidate
import com.noisedetected.core.inference.InferenceResult
import kotlin.math.roundToInt

@Composable
fun IdentifyScreen(
    state: LiveState,
    renderer: DisplayRenderer,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTogglePeakHold: () -> Unit,
    onSave: (String, Condition?) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSave by remember { mutableStateOf(false) }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConclusionCard(state)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.running) "已测量 ${state.elapsedSec} 秒" else "未在测量",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.weight(1f))
            FilterChip(selected = state.showPeakHold, onClick = onTogglePeakHold, label = { Text("峰值保持") })
        }

        LiveSpectrumChart(renderer, state.showPeakHold, state.mainFrequencyHz)
        WaterfallView(renderer)
        Text(
            "上：频谱（横轴为频率 Hz，纵轴为相对声级 dB）；下：瀑布图，越亮越响，最新在上。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.sourceLabel?.let {
            Text("音源：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (state.running) {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("停止") }
        } else {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (state.inference == null) "开始测量" else "重新测量")
            }
            if (state.canSave) {
                OutlinedButton(onClick = { showSave = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("保存这次测量（录音 + 频谱）")
                }
            }
        }
        state.message?.let { msg ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(msg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismissMessage) { Text("知道了") }
            }
        }
    }

    if (showSave) {
        SaveDialog(onDismiss = { showSave = false }) { label, condition ->
            onSave(label, condition)
            showSave = false
        }
    }
}

@Composable
private fun SaveDialog(onDismiss: () -> Unit, onConfirm: (String, Condition?) -> Unit) {
    var label by remember { mutableStateOf("") }
    var condition by remember { mutableStateOf<Condition?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存测量") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("名称，如「主卧床头」") },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                Text("对比用途（可选）", style = MaterialTheme.typography.labelLarge)
                Text(
                    "成对保存后，在「记录」页对比时会自动解读，比如开窗和关窗各测一次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ConditionOption("不用于对比", condition == null) { condition = null }
                Condition.entries.forEach { c ->
                    ConditionOption("${c.template.title}：${c.label}", condition == c) { condition = c }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(label.trim(), condition) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ConditionOption(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ConclusionCard(state: LiveState) {
    val result = state.inference
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                result == null -> Guide()
                result.candidates.isEmpty() -> {
                    Text("正在分析…", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(
                        progress = { (state.elapsedSec / 20f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    result.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                else -> Result(result)
            }
        }
    }
}

@Composable
private fun Guide() {
    Text("怎么测", style = MaterialTheme.typography.titleMedium)
    Text(
        "1. 到声音最明显的房间，关掉家里的空调、冰箱、风扇等设备。\n" +
            "2. 把手机平放在桌面或地板上，不要拿在手里。\n" +
            "3. 点「开始测量」，保持安静 30 秒以上。",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun Result(result: InferenceResult) {
    val top = result.top!!
    Text(if (result.ready) "推断结果" else "初步推断（继续测量中）", style = MaterialTheme.typography.labelLarge)
    Text(top.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    ConfidenceBar(top)
    Text(top.reason, style = MaterialTheme.typography.bodyMedium)
    Text("建议：${top.advice}", style = MaterialTheme.typography.bodyMedium)

    val others = result.candidates.drop(1)
    if (others.isNotEmpty()) {
        HorizontalDivider()
        Text("其他可能", style = MaterialTheme.typography.labelLarge)
        others.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("${(c.confidence * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    result.notes.forEach {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConfidenceBar(c: Candidate) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(progress = { c.confidence.toFloat() }, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text("可能性 ${(c.confidence * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
    }
}
