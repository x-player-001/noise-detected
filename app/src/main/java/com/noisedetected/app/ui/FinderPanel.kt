package com.noisedetected.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.noisedetected.app.FinderState
import com.noisedetected.app.NoiseViewModel
import com.noisedetected.app.ui.theme.Palette
import com.noisedetected.app.ui.theme.Type
import com.noisedetected.core.survey.MeterReading

/** 强度条和走势曲线显示最高值以下多少 dB。 */
private const val RANGE_DB = 20.0

/** 实时寻声：拿着手机走动，大读数 + 强度条 + 30 秒走势，越靠近声源数值越大。 */
@Composable
fun FinderPanel(
    state: FinderState,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onResetMax: () -> Unit,
) {
    val reading = state.reading
    Panel(border = if (state.running) Palette.Accent.copy(alpha = 0.6f) else Palette.Line) {
        SectionLabel("实时寻声", "LIVE FINDER") {
            if (state.running) Tag("LIVE", color = Palette.Danger, filled = true)
        }
        if (!state.running) {
            Text(
                "拿着手机在各个房间之间走动，屏幕实时显示目标声音的强弱，数值越大越靠近声源。",
                style = Type.BodySmall,
                color = Palette.TextMid,
            )
            PrimaryButton("开始寻声", onStart, Modifier.fillMaxWidth(), enabled = enabled, height = 52.dp)
            return@Panel
        }

        if (reading == null) {
            Text("正在采集…", style = Type.Body, color = Palette.TextMid)
        } else {
            LiveReadout(reading)
            StrengthBar(reading)
            Sparkline(state.history, reading.maxDb)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    reading.maxDb?.let { String.format("最高 %.1f dB · %s", it, formatClock(reading.maxAtSec!!.toInt())) } ?: "还没有最高值",
                    style = Type.NumberSmall,
                    color = Palette.TextLow,
                    modifier = Modifier.weight(1f),
                )
                TextAction("重置最高值", onResetMax, enabled = reading.maxDb != null)
            }
        }
        Hairline()
        Text(
            "走到一个位置后停下 2–3 秒再看读数。低频在房间里有驻波，同一房间内挪动半米也可能差 10 dB，" +
                "看房间之间的整体趋势；每个房间都去墙角比一比，低频在墙角最强。",
            style = Type.Caption,
            color = Palette.TextLow,
        )
        PrimaryButton("停止寻声", onStop, Modifier.fillMaxWidth(), danger = true, height = 52.dp)
    }
}

@Composable
private fun LiveReadout(r: MeterReading) {
    val max = r.maxDb
    Row(verticalAlignment = Alignment.Bottom) {
        Readout(
            String.format("%.1f", r.levelDb), "dB",
            style = Type.Hero,
            color = if (r.detected) Palette.TextHigh else Palette.TextLow,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 12.dp)) {
            Text("距最高", style = Type.Caption, color = Palette.TextLow)
            Text(
                if (max == null) "—" else String.format("%.1f dB", r.levelDb - max),
                style = Type.Number.copy(fontSize = Type.Heading.fontSize),
                color = when {
                    max == null -> Palette.TextLow
                    max - r.levelDb <= 1.5 -> Palette.Accent
                    else -> Palette.TextMid
                },
            )
        }
    }
    if (!r.detected) {
        Text(
            "未明显测到目标声音（比背景只高 ${String.format("%.0f", r.snrDb.coerceAtLeast(0.0))} dB），读数仅供参考。",
            style = Type.Caption,
            color = Palette.Danger.copy(alpha = 0.85f),
        )
    }
}

/** 强度条：以最高值为满格、向下 [RANGE_DB] 为空；没有最高值时以当前值为满格。 */
@Composable
private fun StrengthBar(r: MeterReading) {
    val top = maxOf(r.maxDb ?: r.levelDb, r.levelDb)
    val fraction = ((r.levelDb - (top - RANGE_DB)) / RANGE_DB).toFloat().coerceIn(0f, 1f)
    val color = if (r.detected) Palette.Accent else Palette.TextLow
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(Palette.Line, cornerRadius = radius)
        val w = size.width * fraction
        if (w > 0f) {
            drawRoundRect(
                Brush.horizontalGradient(listOf(color.copy(alpha = 0.35f), color), endX = w),
                size = Size(w, size.height),
                cornerRadius = radius,
            )
        }
    }
}

/** 最近 30 秒的读数走势，虚线为最高值。 */
@Composable
private fun Sparkline(history: FloatArray, maxDb: Double?) {
    val path = remember { Path() }
    Canvas(Modifier.fillMaxWidth().height(72.dp)) {
        if (history.size < 2) return@Canvas
        var hi = history.max().toDouble()
        if (maxDb != null) hi = maxOf(hi, maxDb)
        hi += 2
        val lo = hi - RANGE_DB - 4
        fun y(db: Double) = (size.height * (hi - db) / (hi - lo)).toFloat().coerceIn(0f, size.height)
        // 固定 30 秒的横轴，最新的点在右端；数据不足 30 秒时左边留空
        val step = size.width / (NoiseViewModel.FINDER_HISTORY - 1)
        val x0 = size.width - (history.size - 1) * step
        path.reset()
        history.forEachIndexed { i, v ->
            val x = x0 + i * step
            if (i == 0) path.moveTo(x, y(v.toDouble())) else path.lineTo(x, y(v.toDouble()))
        }
        drawLine(Palette.Line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        if (maxDb != null) {
            drawLine(
                Palette.Accent.copy(alpha = 0.5f), Offset(0f, y(maxDb)), Offset(size.width, y(maxDb)), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
        }
        drawPath(path, Palette.Accent, style = Stroke(width = 1.8.dp.toPx(), join = StrokeJoin.Round))
        drawCircle(Palette.Accent, radius = 3.dp.toPx(), center = Offset(size.width, y(history.last().toDouble())))
    }
    Row {
        Text("30 秒前", style = Type.Caption, color = Palette.TextLow)
        Spacer(Modifier.weight(1f))
        Text("现在", style = Type.Caption, color = Palette.TextLow)
    }
}
