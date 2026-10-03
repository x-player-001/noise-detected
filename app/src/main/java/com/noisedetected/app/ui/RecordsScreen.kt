package com.noisedetected.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noisedetected.app.CompareItem
import com.noisedetected.app.data.StoredMeasurement
import com.noisedetected.core.compare.SpectrumCompare
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 叠加对比的曲线颜色，最多 5 段。 */
val COMPARE_COLORS = listOf(
    Color(0xFF3B6FD8), Color(0xFFE07B39), Color(0xFF3A9D5D), Color(0xFF8E5CC4), Color(0xFFD64545),
)

@Composable
fun RecordsScreen(
    records: List<StoredMeasurement>,
    onCompare: (List<String>) -> Unit,
    onShare: (List<StoredMeasurement>) -> Unit,
    onDelete: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 保持勾选顺序：第一个勾选的作为对比基准
    var selected by remember { mutableStateOf(listOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val chosen = records.filter { it.meta.id in selected }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (records.isEmpty()) {
            Text("还没有保存的测量", style = MaterialTheme.typography.titleMedium)
            Text(
                "在「识别」页测量 10 秒以上并停止后，点「保存这次测量」。\n" +
                    "想对比开窗和关窗、墙边和房间中央，就分别测一次，保存时选好对应的用途。",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }

        Text(
            "勾选 2–5 段叠加对比，第一个勾选的作为基准。只有同一部手机、同样放法录的才能比较声级。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onCompare(selected) }, enabled = selected.size in 1..COMPARE_COLORS.size) {
                Text(if (selected.size == 1) "查看频谱" else "叠加对比")
            }
            OutlinedButton(onClick = { onShare(chosen) }, enabled = chosen.isNotEmpty()) { Text("分享录音") }
            Box(Modifier.weight(1f))
            TextButton(onClick = { confirmDelete = true }, enabled = chosen.isNotEmpty()) { Text("删除") }
        }

        records.forEach { r ->
            val index = selected.indexOf(r.meta.id)
            RecordRow(
                record = r,
                selectedIndex = index,
                canSelectMore = selected.size < COMPARE_COLORS.size,
                onToggle = {
                    selected = if (index >= 0) selected - r.meta.id else selected + r.meta.id
                },
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 ${chosen.size} 段测量？") },
            text = { Text("录音和频谱都会删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(chosen.map { it.meta.id }.toSet())
                    selected = emptyList()
                    confirmDelete = false
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun RecordRow(record: StoredMeasurement, selectedIndex: Int, canSelectMore: Boolean, onToggle: () -> Unit) {
    val m = record.meta
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selectedIndex >= 0, onCheckedChange = { onToggle() }, enabled = selectedIndex >= 0 || canSelectMore)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selectedIndex >= 0) {
                        ColorDot(COMPARE_COLORS[selectedIndex])
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(m.label, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    buildString {
                        append(DATE_FORMAT.format(Date(m.createdAt)))
                        append(" · ${m.durationSec.toInt()} 秒")
                        m.condition?.let { append(" · ${it.label}") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                val summary = listOfNotNull(
                    m.mainFrequencyHz?.let { String.format("主频 %.1f Hz", it) },
                    m.conclusion,
                ).joinToString(" · ")
                if (summary.isNotEmpty()) {
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ColorDot(color: Color) {
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

@Composable
fun CompareScreen(items: List<CompareItem>, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val reference = items.firstOrNull()
    val initial = reference?.let { it.record.meta.mainFrequencyHz ?: SpectrumCompare.strongestPeakHz(it.spectrum) } ?: 50.0
    var cursor by remember(items) { mutableDoubleStateOf(initial) }
    val readings = items.map { SpectrumCompare.levelAt(it.spectrum, cursor) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("← 返回记录") }
        if (items.isEmpty()) {
            Text("读取失败，记录可能已损坏。")
            return@Column
        }

        OverlayChart(items.mapIndexed { i, it -> it.spectrum to COMPARE_COLORS[i] }, cursor, { cursor = it })
        Text(
            "点图上任意位置把光标移到该频率；横轴为频率 Hz，纵轴为相对声级 dB（整段测量的平均）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(String.format("光标 %.1f Hz", cursor), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            listOf(-1.0 to "−1", -0.1 to "−0.1", 0.1 to "+0.1", 1.0 to "+1").forEach { (step, text) ->
                OutlinedButton(onClick = { cursor = (cursor + step).coerceIn(10.0, 500.0) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(text)
                }
            }
        }
        reference?.let { ref ->
            SpectrumCompare.strongestPeakHz(ref.spectrum)?.let { peak ->
                TextButton(onClick = { cursor = peak }) { Text(String.format("跳到基准的主峰（%.1f Hz）", peak)) }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEachIndexed { i, item ->
                    val r = readings[i]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ColorDot(COMPARE_COLORS[i])
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.record.meta.label, style = MaterialTheme.typography.bodyMedium)
                            item.record.meta.condition?.let { Text(it.label, style = MaterialTheme.typography.bodySmall) }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(String.format("%.1f dB", r.levelDb), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                when {
                                    !r.detected -> "未明显测到"
                                    i == 0 -> "基准"
                                    else -> String.format("%+.1f dB", r.levelDb - readings[0].levelDb)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (i > 0 && r.detected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                    if (i < items.lastIndex) HorizontalDivider()
                }
            }
        }

        if (items.size == 2) {
            SpectrumCompare.interpret(
                items[0].record.meta.condition, readings[0],
                items[1].record.meta.condition, readings[1],
                cursor,
            )?.let { interpretation ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(interpretation.title, style = MaterialTheme.typography.labelLarge)
                        Text(interpretation.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

private val DATE_FORMAT = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
