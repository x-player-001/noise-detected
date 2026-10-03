package com.noisedetected.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noisedetected.app.LiveState
import com.noisedetected.app.ui.theme.Palette
import com.noisedetected.app.ui.theme.Type
import com.noisedetected.core.compare.Condition
import com.noisedetected.core.inference.InferenceGate
import com.noisedetected.core.inference.InferenceResult
import kotlinx.coroutines.launch
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
    onFindSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSave by remember { mutableStateOf(false) }
    val result = state.inference
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var detailsY by remember { mutableIntStateOf(0) }

    Column(modifier.fillMaxSize().background(Palette.Bg)) {
        ScreenHeader("声源识别", "LOW FREQUENCY ANALYZER") {
            RecordControl(state.running, state.elapsedSec, if (result == null) "开始测量" else "重新测量", onStart, onStop)
        }

        Column(
            Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            state.error?.let { Banner(it, Palette.Danger, null, onDismissMessage) }
            state.message?.let { Banner(it, Palette.Accent, "知道了", onDismissMessage) }

            if (result == null && !state.running) {
                GuidePanel(onStart)
            } else {
                ConclusionCard(
                    state = state,
                    onFindSource = onFindSource,
                    onSave = { showSave = true },
                    onShowDetails = { scope.launch { scroll.animateScrollTo(detailsY) } },
                )
            }

            Panel(padding = PaddingValues(start = 14.dp, end = 16.dp, top = 18.dp, bottom = 16.dp), spacing = 12.dp) {
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

            if (result?.top != null) {
                Box(Modifier.onGloballyPositioned { detailsY = it.positionInParent().y.toInt() }) { DetailsPanel(result) }
            }

            state.sourceLabel?.let {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("音源", style = Type.Caption, color = Palette.TextLow)
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = Type.Caption, color = Palette.TextMid)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showSave) {
        SaveDialog(onDismiss = { showSave = false }) { label, condition ->
            onSave(label, condition)
            showSave = false
        }
    }
}

/** 标题栏右侧的测量控制：空闲时琥珀色「开始」，测量中红色「停止」并显示计时。 */
@Composable
private fun RecordControl(running: Boolean, seconds: Int, idleLabel: String, onStart: () -> Unit, onStop: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    if (running) {
        val pulse = rememberInfiniteTransition(label = "rec")
        val alpha by pulse.animateFloat(
            initialValue = 1f, targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "alpha",
        )
        Row(
            Modifier.height(40.dp).clip(shape).background(Palette.DangerSoft)
                .border(1.dp, Palette.Danger.copy(alpha = 0.45f), shape)
                .clickable(onClick = onStop).padding(start = 14.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).alpha(alpha).background(Palette.Danger, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(formatClock(seconds), style = Type.NumberSmall.copy(fontSize = 14.sp), color = Palette.TextHigh)
            Box(Modifier.padding(horizontal = 10.dp).width(1.dp).height(16.dp).background(Palette.Danger.copy(alpha = 0.4f)))
            Box(Modifier.size(9.dp).background(Palette.Danger, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(6.dp))
            Text("停止", style = Type.BodySmall, color = Palette.Danger)
        }
    } else {
        Row(
            Modifier.height(40.dp).clip(shape).background(Palette.Accent)
                .clickable(onClick = onStart).padding(start = 14.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(Palette.Danger, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(idleLabel, style = Type.BodySmall.copy(fontWeight = Type.Button.fontWeight), color = Palette.OnAccent)
        }
    }
}

/** 页面顶部的结论卡：主频 + 声源类型 + 可能性 + 依据摘要 + 操作，第一屏就能看到推断结果。 */
@Composable
private fun ConclusionCard(state: LiveState, onFindSource: () -> Unit, onSave: () -> Unit, onShowDetails: () -> Unit) {
    // 刚开始测量时还没有分析结果，按"分析中"显示
    val result = state.inference
    val top = result?.top
    val hz = state.mainFrequencyHz
    Panel(border = if (top != null) Palette.Accent.copy(alpha = 0.35f) else Palette.Line, spacing = 12.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                SectionLabel("主频", "FUNDAMENTAL")
                Spacer(Modifier.height(4.dp))
                Readout(
                    if (hz != null) String.format("%.1f", hz) else "00.0", "Hz",
                    style = Type.Display,
                    color = if (hz != null) Palette.TextHigh else Palette.LineStrong,
                )
            }
            if (top != null) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("可能性", style = Type.Caption, color = Palette.TextLow)
                    Text("${(top.confidence * 100).roundToInt()}%", style = Type.Number.copy(fontSize = 22.sp), color = Palette.TextHigh)
                    Tag(
                        when {
                            !result!!.ready -> "初步"
                            top.confidence >= 0.6 -> "较可信"
                            else -> "仅供参考"
                        },
                        color = if (result!!.ready && top.confidence >= 0.6) Palette.Positive else Palette.Accent,
                        filled = true,
                    )
                }
            }
        }

        if (top == null) {
            // 结论还没发布：显示剩余时间和进度
            val progress = (state.elapsedSec / InferenceGate.FIRST_RESULT_SEC.toFloat()).coerceIn(0f, 0.95f)
            Text(
                when {
                    !state.running -> "测量时间太短，没有得出结论。请重新测量 30 秒以上。"
                    state.elapsedSec < InferenceGate.FIRST_RESULT_SEC -> "正在分析，约 ${InferenceGate.FIRST_RESULT_SEC.toInt() - state.elapsedSec} 秒后给出结论"
                    else -> "正在确认结论…"
                },
                style = Type.Body,
                color = Palette.TextMid,
            )
            if (state.running) {
                Meter(progress)
                Text("正在排除电视、说话、音乐等干扰，请保持安静、手机放稳。", style = Type.Caption, color = Palette.TextLow)
            }
            result?.notes?.filter { it != InferenceGate.PENDING_NOTE }?.forEach { Text(it, style = Type.Caption, color = Palette.TextLow) }
            return@Panel
        }

        Text(top.title, style = Type.Heading, color = Palette.Accent)
        Text(top.reason, style = Type.BodySmall, color = Palette.TextMid, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (hz != null) GhostButton("寻找这个声源 →", onFindSource, accent = true, height = 36.dp)
            if (state.canSave) GhostButton("保存", onSave, height = 36.dp)
            Spacer(Modifier.weight(1f))
            TextAction("排查建议 ↓", onShowDetails, color = Palette.TextMid)
        }
    }
}

@Composable
private fun GuidePanel(onStart: () -> Unit) {
    Panel {
        SectionLabel("测量方法", "HOW TO MEASURE")
        Step(1, "到声音最明显的房间", "关掉家里的空调、冰箱、风扇等设备，排除自家干扰。")
        Step(2, "手机平放在桌面或地板上", "不要拿在手里，手的动作会带来低频干扰。")
        Step(3, "开始测量并保持安静", "约 20 秒后给出结论，测得越久越可靠。")
        PrimaryButton("开始测量", onStart, Modifier.fillMaxWidth(), height = 50.dp)
    }
}

/** 结论详情：完整依据、排查建议、其他可能。 */
@Composable
private fun DetailsPanel(result: InferenceResult) {
    val top = result.top!!
    Panel {
        SectionLabel("判断依据", "REASONING")
        Text(top.reason, style = Type.Body, color = Palette.TextHigh)
        Hairline()
        SectionLabel("排查建议", "NEXT STEPS")
        Text(top.advice, style = Type.Body, color = Palette.TextMid)

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
