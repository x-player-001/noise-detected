package com.noisedetected.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noisedetected.app.CompareItem
import com.noisedetected.app.data.StoredMeasurement
import com.noisedetected.app.ui.theme.Palette
import com.noisedetected.app.ui.theme.Type
import com.noisedetected.core.compare.SpectrumCompare
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 叠加对比的曲线颜色，最多 5 段：首段用强调色作为基准，其余在深色底上易区分。 */
val COMPARE_COLORS = listOf(
    Palette.Accent, Color(0xFF6FB7FF), Color(0xFF7ED4A8), Color(0xFFC79BFF), Color(0xFFFF7F8A),
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

    Column(modifier.fillMaxSize().background(Palette.Bg)) {
        ScreenHeader("测量记录", "ARCHIVE") {
            if (records.isNotEmpty()) {
                Readout("${records.size}", "段", style = Type.Number.copy(fontSize = Type.Heading.fontSize), color = Palette.TextMid)
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (records.isEmpty()) {
                EmptyState(
                    "还没有保存的测量",
                    "在「识别」页测量 10 秒以上并停止后，点「保存」。想对比开窗和关窗、墙边和房间中央，就分别测一次，保存时选好对应的用途。",
                )
                return@Column
            }
            Text(
                "选择 1–5 段查看或叠加对比，第一个选中的作为基准。只有同一部手机、同样放法录的才能比较声级。",
                style = Type.Caption,
                color = Palette.TextLow,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
            records.forEach { r ->
                val index = selected.indexOf(r.meta.id)
                RecordRow(
                    record = r,
                    selectedIndex = index,
                    canSelectMore = selected.size < COMPARE_COLORS.size,
                    onToggle = { selected = if (index >= 0) selected - r.meta.id else selected + r.meta.id },
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        if (chosen.isNotEmpty()) {
            SelectionBar(
                count = chosen.size,
                onCompare = { onCompare(selected) },
                onShare = { onShare(chosen) },
                onDelete = { confirmDelete = true },
                onClear = { selected = emptyList() },
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Palette.SurfaceHigh,
            title = { Text("删除 ${chosen.size} 段测量？", style = Type.Heading, color = Palette.TextHigh) },
            text = { Text("录音和频谱都会删除，无法恢复。", style = Type.BodySmall, color = Palette.TextMid) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(chosen.map { it.meta.id }.toSet())
                    selected = emptyList()
                    confirmDelete = false
                }) { Text("删除", color = Palette.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消", color = Palette.TextMid) } },
        )
    }
}

/** 选中后出现在底部的操作条。 */
@Composable
private fun SelectionBar(count: Int, onCompare: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit, onClear: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding, vertical = 12.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Palette.SurfaceHigh)
            .border(1.dp, Palette.LineStrong, RoundedCornerShape(28.dp))
            .padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextAction("✕", onClear, color = Palette.TextMid)
        Text("已选 $count", style = Type.BodySmall, color = Palette.TextHigh)
        Spacer(Modifier.weight(1f))
        TextAction("删除", onDelete, color = Palette.Danger)
        TextAction("分享", onShare, color = Palette.TextHigh)
        Spacer(Modifier.width(4.dp))
        PrimaryButton(if (count == 1) "查看频谱" else "叠加对比", onCompare, height = 44.dp)
    }
}

@Composable
private fun RecordRow(record: StoredMeasurement, selectedIndex: Int, canSelectMore: Boolean, onToggle: () -> Unit) {
    val m = record.meta
    val isSelected = selectedIndex >= 0
    val enabled = isSelected || canSelectMore
    val color = if (isSelected) COMPARE_COLORS[selectedIndex] else Palette.Line
    Row(
        Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(if (isSelected) color.copy(alpha = 0.06f) else Palette.Surface)
            .border(1.dp, if (isSelected) color.copy(alpha = 0.6f) else Palette.Line, PanelShape)
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(start = 16.dp, end = 18.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectBadge(selectedIndex)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(m.label, style = Type.Body.copy(fontWeight = Type.Heading.fontWeight), color = Palette.TextHigh, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(DATE_FORMAT.format(Date(m.createdAt)), style = Type.NumberSmall, color = Palette.TextLow)
                Text(formatClock(m.durationSec.toInt()), style = Type.NumberSmall, color = Palette.TextLow)
                m.condition?.let { Tag(it.label) }
            }
            m.conclusion?.let { Text(it, style = Type.Caption, color = Palette.TextMid, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (m.mainFrequencyHz != null) {
                Readout(String.format("%.1f", m.mainFrequencyHz), "Hz", style = Type.Number.copy(fontSize = Type.Heading.fontSize))
            } else {
                Text("—", style = Type.Heading, color = Palette.TextLow)
            }
        }
    }
}

/** 选择标记：未选为空心圆，选中后显示对比颜色和序号。 */
@Composable
private fun SelectBadge(index: Int) {
    if (index < 0) {
        Box(Modifier.size(22.dp).border(1.dp, Palette.LineStrong, CircleShape))
    } else {
        Box(Modifier.size(22.dp).background(COMPARE_COLORS[index], CircleShape), contentAlignment = Alignment.Center) {
            Text("${index + 1}", style = Type.NumberSmall, color = Palette.Bg)
        }
    }
}

@Composable
private fun ColorBar(color: Color) {
    Box(Modifier.width(3.dp).height(28.dp).background(color, RoundedCornerShape(2.dp)))
}

@Composable
fun CompareScreen(items: List<CompareItem>, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val reference = items.firstOrNull()
    val initial = reference?.let { it.record.meta.mainFrequencyHz ?: SpectrumCompare.strongestPeakHz(it.spectrum) } ?: 50.0
    var cursor by remember(items) { mutableDoubleStateOf(initial) }
    val readings = items.map { SpectrumCompare.levelAt(it.spectrum, cursor) }

    Column(modifier.fillMaxSize().background(Palette.Bg)) {
        Row(Modifier.statusBarsPadding().padding(start = 8.dp, top = 12.dp)) {
            TextAction("‹  记录", onBack, color = Palette.TextMid)
        }
        ScreenHeader(if (items.size == 1) "频谱详情" else "叠加对比", if (items.size == 1) "SPECTRUM" else "COMPARE", Modifier.padding(top = 0.dp))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (items.isEmpty()) {
                EmptyState("读取失败", "记录可能已损坏。")
                return@Column
            }

            Panel(padding = PaddingValues(start = 14.dp, end = 16.dp, top = 18.dp, bottom = 16.dp), spacing = 12.dp) {
                SectionLabel("平均频谱", "AVERAGE SPECTRUM", Modifier.padding(start = 6.dp))
                OverlayChart(items.mapIndexed { i, it -> it.spectrum to COMPARE_COLORS[i] }, cursor, { cursor = it })
                Text("点图上任意位置移动光标。纵轴为相对声级 dB（整段测量的平均）。", style = Type.Caption, color = Palette.TextLow, modifier = Modifier.padding(start = 6.dp))
            }

            Panel {
                SectionLabel("光标", "CURSOR") {
                    SpectrumCompare.strongestPeakHz(reference!!.spectrum)?.let { peak ->
                        TextAction(String.format("跳到主峰 %.1f Hz", peak), { cursor = peak }, color = Palette.Accent)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Readout(String.format("%.1f", cursor), "Hz", style = Type.Display, modifier = Modifier.weight(1f))
                }
                Stepper { step -> cursor = (cursor + step).coerceIn(10.0, 500.0) }
                Hairline()
                items.forEachIndexed { i, item ->
                    val r = readings[i]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ColorBar(COMPARE_COLORS[i])
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(item.record.meta.label, style = Type.BodySmall, color = Palette.TextHigh, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            item.record.meta.condition?.let { Text(it.label, style = Type.Caption, color = Palette.TextLow) }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(String.format("%.1f dB", r.levelDb), style = Type.Number, color = Palette.TextHigh)
                            Text(
                                when {
                                    !r.detected -> "未明显测到"
                                    i == 0 -> "基准"
                                    else -> String.format("%+.1f dB", r.levelDb - readings[0].levelDb)
                                },
                                style = Type.NumberSmall,
                                color = if (i > 0 && r.detected) COMPARE_COLORS[i] else Palette.TextLow,
                            )
                        }
                    }
                }
            }

            if (items.size == 2) {
                SpectrumCompare.interpret(
                    items[0].record.meta.condition, readings[0],
                    items[1].record.meta.condition, readings[1],
                    cursor,
                )?.let { interpretation ->
                    Panel(border = Palette.Accent.copy(alpha = 0.35f)) {
                        SectionLabel("解读", "INTERPRETATION")
                        Text(interpretation.title, style = Type.Heading, color = Palette.Accent)
                        Text(interpretation.text, style = Type.Body, color = Palette.TextHigh)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 光标微调：分段按钮。 */
@Composable
private fun Stepper(onStep: (Double) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.fillMaxWidth().height(40.dp).clip(shape).border(1.dp, Palette.LineStrong, shape)) {
        listOf(-1.0 to "−1", -0.1 to "−0.1", 0.1 to "+0.1", 1.0 to "+1").forEachIndexed { i, (step, text) ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.LineStrong))
            Box(Modifier.weight(1f).fillMaxSize().clickable { onStep(step) }, contentAlignment = Alignment.Center) {
                Text(text, style = Type.Number, color = Palette.TextHigh)
            }
        }
    }
}

private val DATE_FORMAT = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
