package com.noisedetected.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noisedetected.app.SurveyState
import com.noisedetected.core.survey.LocationKind
import com.noisedetected.core.survey.LocationSummary
import com.noisedetected.core.survey.SurveyAnalyzer
import com.noisedetected.core.survey.SurveyLocation
import com.noisedetected.core.survey.SurveyReport
import com.noisedetected.core.survey.SurveyTarget

@Composable
fun SurveyScreen(
    state: SurveyState,
    hasIdentifiedTone: Boolean,
    onUseIdentifiedTone: () -> Unit,
    onSetTarget: (SurveyTarget) -> Unit,
    onAddLocation: (String, LocationKind) -> Unit,
    onMeasure: (Long) -> Unit,
    onRemoveLastPoint: (Long) -> Unit,
    onRemoveLocation: (Long) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAdd by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    val busy = state.measuringId != null

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TargetCard(state.target, hasIdentifiedTone, busy, onUseIdentifiedTone) { showManual = true }

        if (state.target != null) {
            Text(
                "每个位置测 3–5 个点：角落、房间中间、靠墙处。每个点约 10 秒，测量时保持安静、手机放稳。" +
                    "也可以去楼上、楼下、楼道、设备间附近测，范围越大越容易判断方向。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.report?.let { ReportCard(it) }
            state.locations.forEach { location ->
                LocationCard(
                    location = location,
                    measuring = state.measuringId == location.id,
                    progress = state.progress,
                    enabled = !busy,
                    onMeasure = { onMeasure(location.id) },
                    onRemoveLastPoint = { onRemoveLastPoint(location.id) },
                    onRemove = { onRemoveLocation(location.id) },
                    onStop = onStop,
                )
            }
            OutlinedButton(onClick = { showAdd = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("+ 添加测量位置")
            }
        }
    }

    if (showAdd) {
        AddLocationDialog(onDismiss = { showAdd = false }) { name, kind ->
            onAddLocation(name, kind)
            showAdd = false
        }
    }
    if (showManual) {
        ManualTargetDialog(onDismiss = { showManual = false }) {
            onSetTarget(it)
            showManual = false
        }
    }
}

@Composable
private fun TargetCard(
    target: SurveyTarget?,
    hasIdentifiedTone: Boolean,
    busy: Boolean,
    onUseIdentified: () -> Unit,
    onManual: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("巡测目标", style = MaterialTheme.typography.labelLarge)
            if (target == null) {
                Text(
                    "巡测只盯住一个频率，比较它在各位置的强弱。先在「识别」页测出主频，再回到这里。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    String.format("%.1f Hz", target.f0Hz) +
                        if (target.harmonics.size > 1) "（含 ${target.harmonics.joinToString("、")} 次谐波）" else "",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onUseIdentified, enabled = hasIdentifiedTone && !busy) { Text("使用识别结果") }
                OutlinedButton(onClick = onManual, enabled = !busy) { Text("手动输入") }
            }
            if (target != null) {
                Text("更换目标会清空已测的点。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ReportCard(report: SurveyReport) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("巡测结论", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(report.confidence.label, style = MaterialTheme.typography.labelLarge)
            }
            Text(report.conclusion, style = MaterialTheme.typography.bodyLarge)
            RankingBars(report.ranking)
            report.tips.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
            Text(
                "声级为手机未校准的相对值，只用于各位置之间比较。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 各位置的能量平均声级，以最强位置为满格、向下 30 dB 为空。 */
@Composable
private fun RankingBars(ranking: List<LocationSummary>) {
    val max = ranking.firstOrNull()?.meanDb ?: return
    ranking.forEach { s ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.location.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
            LinearProgressIndicator(
                progress = { (1 - (max - s.meanDb) / 30.0).toFloat().coerceIn(0.02f, 1f) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (s.meanDb == max) "最强" else String.format("-%.1f dB", max - s.meanDb),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(56.dp),
            )
        }
    }
}

@Composable
private fun LocationCard(
    location: SurveyLocation,
    measuring: Boolean,
    progress: Float,
    enabled: Boolean,
    onMeasure: () -> Unit,
    onRemoveLastPoint: () -> Unit,
    onRemove: () -> Unit,
    onStop: () -> Unit,
) {
    val summary = SurveyAnalyzer.summarize(location)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(location.name, style = MaterialTheme.typography.titleMedium)
                    Text(location.kind.label, style = MaterialTheme.typography.bodySmall)
                }
                Text("${location.points.size} 个点", style = MaterialTheme.typography.bodyMedium)
            }
            if (summary != null) {
                val undetected = location.points.count { !it.detected }
                Text(
                    String.format("平均 %.1f dB，点间差异 ±%.1f dB", summary.meanDb, summary.spreadDb) +
                        if (undetected > 0) "；$undetected 个点未明显测到目标" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (measuring) {
                Text("正在测量，请保持安静…", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = onStop) { Text("取消") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onMeasure, enabled = enabled) { Text("测一个点") }
                    if (location.points.isNotEmpty()) {
                        TextButton(onClick = onRemoveLastPoint, enabled = enabled) { Text("删上一点") }
                    }
                    Box(Modifier.weight(1f))
                    TextButton(onClick = onRemove, enabled = enabled) { Text("删除") }
                }
            }
        }
    }
}

@Composable
private fun AddLocationDialog(onDismiss: () -> Unit, onConfirm: (String, LocationKind) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(LocationKind.HOME_ROOM) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加测量位置") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称，如「主卧」「楼下 302」") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                LocationKind.entries.forEach { k ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = kind == k, onClick = { kind = k }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = kind == k, onClick = { kind = k })
                        Text(k.label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim(), kind) }) { Text("添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ManualTargetDialog(onDismiss: () -> Unit, onConfirm: (SurveyTarget) -> Unit) {
    var text by remember { mutableStateOf("") }
    val freq = text.toDoubleOrNull()?.takeIf { it in 10.0..500.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动输入目标频率") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("频率（10–500 Hz）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = {
            TextButton(onClick = { freq?.let { onConfirm(SurveyTarget(it)) } }, enabled = freq != null) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
