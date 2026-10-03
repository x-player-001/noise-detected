package com.noisedetected.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noisedetected.app.FinderState
import com.noisedetected.app.SurveyState
import com.noisedetected.app.ui.theme.Palette
import com.noisedetected.app.ui.theme.Type
import com.noisedetected.core.survey.Confidence
import com.noisedetected.core.survey.LocationKind
import com.noisedetected.core.survey.LocationSummary
import com.noisedetected.core.survey.SurveyAnalyzer
import com.noisedetected.core.survey.SurveyLocation
import com.noisedetected.core.survey.SurveyReport
import com.noisedetected.core.survey.SurveyTarget
import kotlin.math.roundToInt

@Composable
fun SurveyScreen(
    state: SurveyState,
    finder: FinderState,
    hasIdentifiedTone: Boolean,
    onUseIdentifiedTone: () -> Unit,
    onSetTarget: (SurveyTarget) -> Unit,
    onAddLocation: (String, LocationKind) -> Unit,
    onMeasure: (Long) -> Unit,
    onRemoveLastPoint: (Long) -> Unit,
    onRemoveLocation: (Long) -> Unit,
    onStop: () -> Unit,
    onStartFinder: () -> Unit,
    onResetFinderMax: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAdd by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    val busy = state.measuringId != null || finder.running

    Column(modifier.fillMaxSize().background(Palette.Bg)) {
        ScreenHeader("位置巡测", "SOURCE LOCATOR")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            val target = state.target
            if (target == null) {
                EmptyState(
                    "先锁定一个目标频率",
                    "巡测只盯住一个频率，比较它在各个位置的强弱，从而推断声源方向。先在「识别」页测出主频，再回到这里。",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("使用识别结果", onUseIdentifiedTone, enabled = hasIdentifiedTone, height = 48.dp)
                        GhostButton("手动输入", { showManual = true }, height = 48.dp)
                    }
                }
            } else {
                TargetPanel(target, hasIdentifiedTone, state.measuringId != null, onUseIdentifiedTone) { showManual = true }
                FinderPanel(finder, enabled = state.measuringId == null, onStart = onStartFinder, onStop = onStop, onResetMax = onResetFinderMax)

                SectionLabel("逐点记录", "POINT SURVEY · 可选", Modifier.padding(top = 12.dp, start = 4.dp))
                Text(
                    "要给物业或邻居看可靠的对比，就在每个位置测几个点取平均，自动排名并推断方向。",
                    style = Type.Caption,
                    color = Palette.TextLow,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                state.report?.let { ReportPanel(it) }
                state.locations.forEachIndexed { i, location ->
                    LocationPanel(
                        index = i + 1,
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
                AddLocationButton(enabled = !busy) { showAdd = true }

                Text(
                    "每个位置测 3–5 个点：角落、房间中间、靠墙处，每点约 10 秒，测量时保持安静、手机放稳。" +
                        "去楼上、楼下、楼道或设备间附近测，范围越大越容易判断方向。",
                    style = Type.Caption,
                    color = Palette.TextLow,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
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
private fun TargetPanel(
    target: SurveyTarget,
    hasIdentifiedTone: Boolean,
    busy: Boolean,
    onUseIdentified: () -> Unit,
    onManual: () -> Unit,
) {
    Panel {
        SectionLabel("目标频率", "TARGET")
        Row(verticalAlignment = Alignment.Bottom) {
            Readout(String.format("%.1f", target.f0Hz), "Hz", style = Type.Display, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                target.harmonics.forEach { Tag("×$it", color = Palette.Accent, filled = true) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            GhostButton("使用识别结果", onUseIdentified, enabled = hasIdentifiedTone && !busy, height = 38.dp)
            GhostButton("手动输入", onManual, enabled = !busy, height = 38.dp)
        }
        Text("更换目标会清空已测的点。", style = Type.Caption, color = Palette.TextLow)
    }
}

@Composable
private fun ReportPanel(report: SurveyReport) {
    val tone = when (report.confidence) {
        Confidence.HIGH -> Palette.Positive
        Confidence.MEDIUM -> Palette.Accent
        Confidence.LOW -> Palette.TextMid
    }
    Panel(border = Palette.Accent.copy(alpha = 0.35f), background = Palette.Surface) {
        SectionLabel("巡测结论", "VERDICT") { Tag(report.confidence.label, color = tone, filled = true) }
        Text(report.conclusion, style = Type.Body.copy(fontSize = Type.Heading.fontSize, lineHeight = Type.Heading.lineHeight), color = Palette.TextHigh)
        Spacer(Modifier.height(2.dp))
        RankingBars(report.ranking)
        if (report.tips.isNotEmpty()) {
            Hairline()
            report.tips.forEach { Text("· $it", style = Type.Caption, color = Palette.TextMid) }
        }
        Text("声级为手机未校准的相对值，只用于各位置之间比较。", style = Type.Caption, color = Palette.TextLow)
    }
}

/** 各位置的能量平均声级，以最强位置为满格、向下 30 dB 为空。 */
@Composable
private fun RankingBars(ranking: List<LocationSummary>) {
    val max = ranking.firstOrNull()?.meanDb ?: return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ranking.forEachIndexed { i, s ->
            val first = i == 0
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        String.format("%02d", i + 1), style = Type.NumberSmall,
                        color = if (first) Palette.Accent else Palette.TextLow, modifier = Modifier.width(26.dp),
                    )
                    Text(s.location.name, style = Type.BodySmall, color = if (first) Palette.TextHigh else Palette.TextMid, modifier = Modifier.weight(1f))
                    Text(
                        if (first) "最强" else String.format("−%.1f dB", max - s.meanDb),
                        style = Type.NumberSmall, color = if (first) Palette.Accent else Palette.TextMid,
                    )
                }
                Meter(
                    (1 - (max - s.meanDb) / 30.0).toFloat().coerceIn(0.02f, 1f),
                    Modifier.padding(start = 26.dp),
                    color = if (first) Palette.Accent else Palette.TextLow,
                    height = 4.dp,
                )
            }
        }
    }
}

@Composable
private fun LocationPanel(
    index: Int,
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
    Panel(
        border = if (measuring) Palette.Accent.copy(alpha = 0.6f) else Palette.Line,
        padding = androidx.compose.foundation.layout.PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 12.dp),
        spacing = 12.dp,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(String.format("%02d", index), style = Type.NumberSmall, color = Palette.TextLow, modifier = Modifier.width(28.dp).padding(top = 4.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(location.name, style = Type.Heading, color = Palette.TextHigh)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tag(location.kind.label)
                    PointDots(location)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (summary != null) {
                    Readout(String.format("%.1f", summary.meanDb), "dB", style = Type.Number.copy(fontSize = Type.Heading.fontSize))
                    Text(String.format("±%.1f dB", summary.spreadDb), style = Type.NumberSmall, color = Palette.TextLow)
                } else {
                    Text("未测量", style = Type.Caption, color = Palette.TextLow, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        val undetected = location.points.count { !it.detected }
        if (undetected > 0) {
            Text("$undetected 个点未明显测到目标", style = Type.Caption, color = Palette.Danger.copy(alpha = 0.85f), modifier = Modifier.padding(start = 28.dp))
        }
        if (measuring) {
            Column(Modifier.padding(start = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("正在测量，请保持安静", style = Type.BodySmall, color = Palette.Accent, modifier = Modifier.weight(1f))
                    Text("${(progress * 100).roundToInt()}%", style = Type.NumberSmall, color = Palette.Accent)
                }
                Meter(progress)
                Row {
                    Spacer(Modifier.weight(1f))
                    TextAction("取消", onStop)
                }
            }
        } else {
            Row(Modifier.padding(start = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                GhostButton("测一个点", onMeasure, enabled = enabled, accent = true, height = 36.dp)
                Spacer(Modifier.weight(1f))
                if (location.points.isNotEmpty()) TextAction("撤销上一点", onRemoveLastPoint, enabled = enabled)
                TextAction("删除", onRemove, enabled = enabled, color = Palette.TextLow)
            }
        }
    }
}

/** 测点进度：建议 5 个点；未明显测到目标的点用空心标出。 */
@Composable
private fun PointDots(location: SurveyLocation) {
    val total = maxOf(5, location.points.size)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until total) {
            val p = location.points.getOrNull(i)
            val m = Modifier.size(6.dp)
            when {
                p == null -> Box(m.border(1.dp, Palette.LineStrong, CircleShape))
                p.detected -> Box(m.background(Palette.Accent, CircleShape))
                else -> Box(m.border(1.dp, Palette.Danger, CircleShape))
            }
        }
        Spacer(Modifier.width(4.dp))
        Text("${location.points.size} 点", style = Type.NumberSmall, color = Palette.TextLow)
    }
}

@Composable
private fun AddLocationButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(64.dp).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRoundRect(
                Palette.LineStrong,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()),
                style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(14.dp)) {
                val c = if (enabled) Palette.Accent else Palette.TextLow
                val w = 1.6.dp.toPx()
                drawLine(c, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), w)
                drawLine(c, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), w)
            }
            Spacer(Modifier.width(10.dp))
            Text("添加测量位置", style = Type.Body, color = if (enabled) Palette.TextHigh else Palette.TextLow)
        }
    }
}

@Composable
private fun AddLocationDialog(onDismiss: () -> Unit, onConfirm: (String, LocationKind) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(LocationKind.HOME_ROOM) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.SurfaceHigh,
        title = { Text("添加测量位置", style = Type.Heading, color = Palette.TextHigh) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称，如「主卧」「楼下 302」") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.height(12.dp))
                SectionLabel("位置类型", "KIND")
                LocationKind.entries.forEach { k ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = kind == k, onClick = { kind = k }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = kind == k, onClick = { kind = k })
                        Text(k.label, style = Type.BodySmall, color = if (kind == k) Palette.TextHigh else Palette.TextMid)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim(), kind) }) { Text("添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Palette.TextMid) } },
    )
}

@Composable
private fun ManualTargetDialog(onDismiss: () -> Unit, onConfirm: (SurveyTarget) -> Unit) {
    var text by remember { mutableStateOf("") }
    val freq = text.toDoubleOrNull()?.takeIf { it in 10.0..500.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.SurfaceHigh,
        title = { Text("手动输入目标频率", style = Type.Heading, color = Palette.TextHigh) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("频率（10–500 Hz）") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = {
            TextButton(onClick = { freq?.let { onConfirm(SurveyTarget(it)) } }, enabled = freq != null) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Palette.TextMid) } },
    )
}
