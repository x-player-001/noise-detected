package com.noisedetected.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noisedetected.app.ui.theme.Palette
import com.noisedetected.app.ui.theme.Type

val PanelShape = RoundedCornerShape(20.dp)
private val PillShape = RoundedCornerShape(percent = 50)

/** 页面左右留白。 */
val ScreenPadding = 20.dp

/** 带细边框的深色面板，界面的基本容器。 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    border: Color = Palette.Line,
    background: Color = Palette.Surface,
    padding: PaddingValues = PaddingValues(20.dp),
    spacing: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(background)
            .border(1.dp, border, PanelShape)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** 中文标签 + 英文小字，如「频谱  SPECTRUM」。 */
@Composable
fun SectionLabel(cn: String, en: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(cn, style = Type.Label, color = Palette.TextMid)
        Spacer(Modifier.width(8.dp))
        Text(en, style = Type.Micro, color = Palette.TextLow)
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/** 页面标题：大标题 + 英文小字 + 可选的右侧内容。 */
@Composable
fun ScreenHeader(title: String, en: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(start = ScreenPadding, end = ScreenPadding, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(en, style = Type.Micro, color = Palette.Accent)
            Spacer(Modifier.height(4.dp))
            Text(title, style = Type.Title, color = Palette.TextHigh)
        }
        trailing?.invoke(this)
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    height: Dp = 56.dp,
) {
    val bg = when {
        !enabled -> Palette.SurfaceHigh
        danger -> Palette.DangerSoft
        else -> Palette.Accent
    }
    val fg = when {
        !enabled -> Palette.TextLow
        danger -> Palette.Danger
        else -> Palette.OnAccent
    }
    Box(
        modifier
            .height(height)
            .clip(PillShape)
            .background(bg)
            .then(if (danger && enabled) Modifier.border(1.dp, Palette.Danger.copy(alpha = 0.5f), PillShape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (danger) {
                Box(Modifier.size(10.dp).background(fg, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = Type.Button, color = fg)
        }
    }
}

/** 次要按钮：细边框胶囊。 */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = false,
    height: Dp = 44.dp,
) {
    val fg = when {
        !enabled -> Palette.TextLow
        accent -> Palette.Accent
        else -> Palette.TextHigh
    }
    val line = when {
        !enabled -> Palette.Line
        accent -> Palette.Accent.copy(alpha = 0.55f)
        else -> Palette.LineStrong
    }
    Box(
        modifier
            .height(height)
            .clip(PillShape)
            .border(1.dp, line, PillShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = if (height >= 48.dp) Type.Button else Type.BodySmall, color = fg)
    }
}

/** 纯文字操作。 */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Palette.TextMid) {
    Text(
        text,
        style = Type.BodySmall,
        color = if (enabled) color else Palette.TextLow.copy(alpha = 0.6f),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

/** 小标签，如位置类型、对比用途。 */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, color: Color = Palette.TextMid, filled: Boolean = false) {
    val shape = RoundedCornerShape(6.dp)
    Text(
        text,
        style = Type.Caption,
        color = color,
        maxLines = 1,
        modifier = modifier
            .clip(shape)
            .then(if (filled) Modifier.background(color.copy(alpha = 0.12f)) else Modifier.border(1.dp, Palette.LineStrong, shape))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** 可切换的小胶囊，如「峰值保持」。 */
@Composable
fun ToggleChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(PillShape)
            .background(if (selected) Palette.AccentSoft else Color.Transparent)
            .border(1.dp, if (selected) Palette.Accent.copy(alpha = 0.5f) else Palette.LineStrong, PillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(if (selected) Palette.Accent else Palette.TextLow, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = Type.Caption, color = if (selected) Palette.Accent else Palette.TextMid)
    }
}

/** 细进度条。 */
@Composable
fun Meter(fraction: Float, modifier: Modifier = Modifier, color: Color = Palette.Accent, height: Dp = 3.dp) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Palette.Line, cornerRadius = r)
        val w = size.width * fraction.coerceIn(0f, 1f)
        if (w > 0f) drawRoundRect(color, size = Size(w, size.height), cornerRadius = r)
    }
}

fun formatClock(seconds: Int): String = String.format("%02d:%02d", seconds / 60, seconds % 60)

/** 大号读数：数值 + 单位。 */
@Composable
fun Readout(value: String, unit: String, modifier: Modifier = Modifier, style: TextStyle = Type.Hero, color: Color = Palette.TextHigh) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(value, style = style, color = color)
        Spacer(Modifier.width(6.dp))
        Text(unit, style = Type.Unit, color = Palette.TextMid, modifier = Modifier.padding(bottom = (style.fontSize.value * 0.16f).dp))
    }
}

/** 编号步骤，如使用说明。 */
@Composable
fun Step(number: Int, title: String, text: String) {
    Row {
        Text(String.format("%02d", number), style = Type.NumberSmall, color = Palette.Accent, modifier = Modifier.width(32.dp).padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.Body, color = Palette.TextHigh)
            Text(text, style = Type.BodySmall, color = Palette.TextMid)
        }
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
}

/** 空状态：同心圆图形 + 标题 + 说明。 */
@Composable
fun EmptyState(title: String, text: String, modifier: Modifier = Modifier, content: (@Composable ColumnScope.() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(120.dp)) {
            val c = center
            for (i in 1..4) {
                drawCircle(
                    Palette.Accent.copy(alpha = 0.55f / i), radius = size.minDimension / 2 * i / 4f, center = c,
                    style = Stroke(width = 1.dp.toPx(), pathEffect = if (i % 2 == 0) PathEffect.dashPathEffect(floatArrayOf(4f, 6f)) else null),
                )
            }
            drawCircle(Palette.Accent, radius = 4.dp.toPx(), center = c)
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = Type.Heading, color = Palette.TextHigh, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(text, style = Type.BodySmall, color = Palette.TextMid, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
        if (content != null) {
            Spacer(Modifier.height(20.dp))
            content()
        }
    }
}

// ---- 底部导航 ----

enum class NavIcon { SPECTRUM, SURVEY, ARCHIVE }

@Composable
fun BottomNav(selected: Int, onSelect: (Int) -> Unit) {
    val items = listOf(Triple("识别", "ANALYZE", NavIcon.SPECTRUM), Triple("巡测", "SURVEY", NavIcon.SURVEY), Triple("记录", "ARCHIVE", NavIcon.ARCHIVE))
    Column(Modifier.fillMaxWidth().background(Palette.Bg).windowInsetsPadding(WindowInsets.navigationBars)) {
        Hairline()
        Row(Modifier.fillMaxWidth().height(68.dp)) {
            items.forEachIndexed { i, (label, _, icon) ->
                val active = i == selected
                val color = if (active) Palette.Accent else Palette.TextLow
                Column(
                    Modifier
                        .weight(1f)
                        .height(68.dp)
                        .clickable(remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.width(20.dp).height(2.dp).background(if (active) Palette.Accent else Color.Transparent, RoundedCornerShape(1.dp)))
                    Spacer(Modifier.height(12.dp))
                    Canvas(Modifier.size(22.dp)) { drawNavIcon(icon, color) }
                    Spacer(Modifier.height(5.dp))
                    Text(label, style = Type.Caption.copy(letterSpacing = 1.sp), color = if (active) Palette.TextHigh else Palette.TextLow)
                }
            }
        }
    }
}

private fun DrawScope.drawNavIcon(icon: NavIcon, color: Color) {
    val s = size.minDimension
    val w = 1.6.dp.toPx()
    when (icon) {
        NavIcon.SPECTRUM -> {
            // 高低不一的频谱柱
            val hs = listOf(0.35f, 0.75f, 1f, 0.55f, 0.3f)
            val gap = s / (hs.size + 1)
            hs.forEachIndexed { i, h ->
                val x = gap * (i + 1)
                val half = s * 0.42f * h
                drawLine(color, Offset(x, s / 2 - half), Offset(x, s / 2 + half), w, StrokeCap.Round)
            }
        }
        NavIcon.SURVEY -> {
            // 准星：外圈 + 内点 + 四个刻度
            drawCircle(color, radius = s * 0.34f, style = Stroke(w))
            drawCircle(color, radius = s * 0.08f)
            val c = s / 2
            listOf(Offset(0f, -1f), Offset(0f, 1f), Offset(-1f, 0f), Offset(1f, 0f)).forEach { d ->
                drawLine(color, Offset(c + d.x * s * 0.34f, c + d.y * s * 0.34f), Offset(c + d.x * s * 0.5f, c + d.y * s * 0.5f), w, StrokeCap.Round)
            }
        }
        NavIcon.ARCHIVE -> {
            // 叠放的卡片
            drawRoundRect(color, Offset(s * 0.12f, s * 0.3f), Size(s * 0.76f, s * 0.58f), CornerRadius(s * 0.1f), style = Stroke(w))
            drawLine(color, Offset(s * 0.22f, s * 0.18f), Offset(s * 0.78f, s * 0.18f), w, StrokeCap.Round)
            drawLine(color, Offset(s * 0.32f, s * 0.52f), Offset(s * 0.68f, s * 0.52f), w, StrokeCap.Round)
            drawLine(color, Offset(s * 0.32f, s * 0.68f), Offset(s * 0.56f, s * 0.68f), w, StrokeCap.Round)
        }
    }
}
