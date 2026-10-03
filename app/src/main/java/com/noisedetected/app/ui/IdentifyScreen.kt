package com.noisedetected.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.noisedetected.app.LiveState
import com.noisedetected.app.ui.theme.Palette
import com.noisedetected.app.ui.theme.Type
import com.noisedetected.core.compare.Condition
import com.noisedetected.core.inference.InferenceGate
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
    val result = state.inference

    Column(modifier.fillMaxSize().background(Palette.Bg)) {
        ScreenHeader("声源识别", "LOW FREQUENCY ANALYZER") {
            StatusPill(state.running, state.elapsedSec)
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Hero(state)
            if (result == null) GuidePanel()

            Panel(padding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 16.dp, top = 18.dp, bottom = 16.dp), spacing = 12.dp) {
                SectionLabel("频谱", "SPECTRUM", Modifier.padding(start = 6.dp)) {
                    ToggleChip("峰值保持", state.showPeakHold, onTogglePeakHold)
                }
                LiveSpectrumChart(renderer, state.showPeakHold, state.mainFrequencyHz)
                Hairline(Modifier.padding(vertical = 4.dp))
                SectionLabel("瀑布图", "WATERFALL · 15 S", Modifier.padding(start = 6.dp)) {
                    Text("越亮越响 · 最新在上", style = Type.Caption, color = Palette.TextLow)
                }
                WaterfallView(renderer)
            }

            when {
                result == null -> {}
                result.candidates.isEmpty() -> AnalyzingPanel(state, result)
                else -> ResultPanel(result)
            }

            state.sourceLabel?.let {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("音源", style = Type.Caption, color = Palette.TextLow)
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = Type.Caption, color = Palette.TextMid)
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // 固定在底部的操作区
        Column(
            Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.error?.let { Banner(it, Palette.Danger, null, onDismissMessage) }
            state.message?.let { Banner(it, Palette.Accent, "知道了", onDismissMessage) }
            if (state.running) {
                PrimaryButton("停止测量", onStop, Modifier.fillMaxWidth(), danger = true)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.canSave) {
                        GhostButton("保存", { showSave = true }, Modifier.weight(0.38f), height = 56.dp, accent = true)
                    }
                    PrimaryButton(if (result == null) "开始测量" else "重新测量", onStart, Modifier.weight(0.62f))
                }
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

/** 主频大读数 + 三项指标。 */
@Composable
private fun Hero(state: LiveState) {
    val result = state.inference
    val top = result?.top
    val hz = state.mainFrequencyHz
    Column {
        SectionLabel("主频", "FUNDAMENTAL")
        Spacer(Modifier.height(6.dp))
        Readout(
            if (hz != null) String.format("%.1f", hz) else "00.0",
            "Hz",
            color = if (hz != null) Palette.TextHigh else Palette.LineStrong,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            top?.title ?: when {
                !state.running -> "等待测量"
                state.elapsedSec < InferenceGate.FIRST_RESULT_SEC -> "正在分析，约 ${InferenceGate.FIRST_RESULT_SEC.toInt() - state.elapsedSec} 秒后给出结论"
                else -> "正在确认结论…"
            },
            style = Type.Heading,
            color = if (top != null) Palette.Accent else Palette.TextMid,
        )
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth().height(44.dp)) {
            Stat("时长", formatClock(state.elapsedSec), Modifier.weight(1f))
            VerticalHairline()
            Stat("可能性", top?.let { "${(it.confidence * 100).roundToInt()}%" } ?: "—", Modifier.weight(1f).padding(start = 16.dp))
            VerticalHairline()
            Stat(
                "结论",
                when {
                    top == null -> "—"
                    result.ready -> "已确定"
                    else -> "初步"
                },
                Modifier.weight(1f).padding(start = 16.dp),
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = Type.Caption, color = Palette.TextLow)
        Text(value, style = Type.Number.copy(fontSize = Type.Heading.fontSize), color = Palette.TextHigh)
    }
}

@Composable
private fun VerticalHairline() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.Line))
}

@Composable
private fun GuidePanel() {
    Panel {
        SectionLabel("测量方法", "HOW TO MEASURE")
        Step(1, "到声音最明显的房间", "关掉家里的空调、冰箱、风扇等设备，排除自家干扰。")
        Step(2, "手机平放在桌面或地板上", "不要拿在手里，手的动作会带来低频干扰。")
        Step(3, "开始测量并保持安静", "持续 30 秒以上，结论会随时间越来越可靠。")
    }
}

@Composable
private fun AnalyzingPanel(state: LiveState, result: InferenceResult) {
    Panel {
        // 结论要等稳定后才发布，可能比预计晚几秒，进度停在 95% 等待
        val progress = (state.elapsedSec / InferenceGate.FIRST_RESULT_SEC.toFloat()).coerceIn(0f, 0.95f)
        SectionLabel("分析中", "ANALYZING") {
            Text("${(progress * 100).roundToInt()}%", style = Type.NumberSmall, color = Palette.Accent)
        }
        Meter(progress)
        result.notes.forEach { Text(it, style = Type.BodySmall, color = Palette.TextMid) }
    }
}

@Composable
private fun ResultPanel(result: InferenceResult) {
    val top = result.top!!
    Panel {
        SectionLabel("判断依据", "REASONING") {
            Tag(if (result.ready) "推断结果" else "继续测量中", color = if (result.ready) Palette.Positive else Palette.Accent, filled = true)
        }
        Text(top.reason, style = Type.Body, color = Palette.TextHigh)
        Hairline()
        SectionLabel("排查建议", "NEXT STEPS")
        Text(top.advice, style = Type.BodySmall, color = Palette.TextMid)

        val others = result.candidates.drop(1)
        if (others.isNotEmpty()) {
            Hairline()
            SectionLabel("其他可能", "ALTERNATIVES")
            others.forEach { c ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.title, style = Type.BodySmall, color = Palette.TextHigh, modifier = Modifier.weight(1f))
                        Text("${(c.confidence * 100).roundToInt()}%", style = Type.NumberSmall, color = Palette.TextMid)
                    }
                    Meter(c.confidence.toFloat(), color = Palette.TextMid, height = 2.dp)
                }
            }
        }
        result.notes.forEach {
            Text("※ $it", style = Type.Caption, color = Palette.TextLow)
        }
    }
}

@Composable
private fun Banner(text: String, color: Color, action: String?, onAction: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.10f), shape).border(1.dp, color.copy(alpha = 0.35f), shape)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Type.BodySmall, color = Palette.TextHigh, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
        if (action != null) TextAction(action, onAction, color = color)
    }
}

@Composable
private fun SaveDialog(onDismiss: () -> Unit, onConfirm: (String, Condition?) -> Unit) {
    var label by remember { mutableStateOf("") }
    var condition by remember { mutableStateOf<Condition?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.SurfaceHigh,
        title = { Text("保存测量", style = Type.Heading, color = Palette.TextHigh) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("名称，如「主卧床头」") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.height(18.dp))
                SectionLabel("对比用途", "OPTIONAL")
                Spacer(Modifier.height(4.dp))
                Text(
                    "成对保存后，在「记录」页对比时会自动解读，比如开窗和关窗各测一次。",
                    style = Type.Caption,
                    color = Palette.TextLow,
                )
                Spacer(Modifier.height(6.dp))
                ConditionOption("不用于对比", condition == null) { condition = null }
                Condition.entries.forEach { c ->
                    ConditionOption("${c.template.title}：${c.label}", condition == c) { condition = c }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(label.trim(), condition) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Palette.TextMid) } },
    )
}

@Composable
private fun ConditionOption(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text, style = Type.BodySmall, color = if (selected) Palette.TextHigh else Palette.TextMid)
    }
}
