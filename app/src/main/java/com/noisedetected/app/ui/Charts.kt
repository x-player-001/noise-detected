package com.noisedetected.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noisedetected.app.ui.theme.Palette
import com.noisedetected.core.dsp.Spectrum
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln

private const val MIN_HZ = 10.0
private const val MAX_HZ = 500.0
private val FREQ_TICKS = listOf(10.0, 20.0, 50.0, 100.0, 200.0, 500.0)
private val LEFT_PAD = 28.dp
private val RIGHT_PAD = 4.dp
private val TOP_PAD = 4.dp
private val BOTTOM_PAD = 18.dp

private val axisLabel = TextStyle(fontSize = 9.sp, color = Palette.TextLow, fontFeatureSettings = "tnum", letterSpacing = 0.3.sp)
private val markerLabel = TextStyle(fontSize = 11.sp, color = Palette.OnAccent, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
private val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))

/** 频谱图和瀑布图共用的横轴映射，保证两张图频率对齐。 */
private fun DrawScope.xOf(freq: Double): Float {
    val left = LEFT_PAD.toPx()
    val plotW = size.width - left - RIGHT_PAD.toPx()
    return left + ((ln(freq / MIN_HZ) / ln(MAX_HZ / MIN_HZ)) * plotW).toFloat()
}

/** 实时频谱：按列画 [DisplayRenderer] 的平滑曲线，可叠加峰值保持和主频标记。只在绘制阶段读取数据，不触发重组。 */
@Composable
fun LiveSpectrumChart(
    renderer: DisplayRenderer,
    showPeakHold: Boolean,
    mainFrequencyHz: Double?,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val version = renderer.version.collectAsStateWithLifecycle()
    val curve = remember(renderer) { FloatArray(renderer.columns) }
    val hold = remember(renderer) { FloatArray(renderer.columns) }
    val path = remember { Path() }
    val fill = remember { Path() }

    Canvas(modifier.fillMaxWidth().height(210.dp)) {
        version.value // 在绘制阶段读取：数据更新只触发重绘
        val axes = drawAxes(measurer, renderer.axisTopDb)
        if (renderer.copyCurve(curve, if (showPeakHold) hold else null)) {
            if (showPeakHold) {
                buildPath(path, renderer.columnHz, hold, axes)
                drawPath(path, Palette.TextMid.copy(alpha = 0.45f), style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f))))
            }
            buildPath(path, renderer.columnHz, curve, axes)
            fill.reset()
            fill.addPath(path)
            fill.lineTo(xOf(renderer.columnHz.last()), axes.bottom)
            fill.lineTo(xOf(renderer.columnHz.first()), axes.bottom)
            fill.close()
            drawPath(fill, Brush.verticalGradient(listOf(Palette.Accent.copy(alpha = 0.28f), Palette.Accent.copy(alpha = 0f)), startY = axes.top, endY = axes.bottom))
            drawPath(path, Palette.Accent, style = Stroke(width = 1.6.dp.toPx(), join = StrokeJoin.Round))
        }
        if (mainFrequencyHz != null) drawMarker(measurer, mainFrequencyHz, Palette.Accent, axes)
    }
}

private fun DrawScope.buildPath(path: Path, freqs: DoubleArray, db: FloatArray, axes: Axes) {
    path.reset()
    for (i in freqs.indices) {
        val x = xOf(freqs[i])
        val y = axes.yOf(db[i].toDouble())
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
}

/** 多段平均谱叠加，点击图上任意位置把光标移到该频率。 */
@Composable
fun OverlayChart(
    curves: List<Pair<Spectrum, Color>>,
    cursorHz: Double,
    onCursor: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val latestOnCursor by rememberUpdatedState(onCursor)
    Canvas(
        modifier.fillMaxWidth().height(240.dp).pointerInput(Unit) {
            detectTapGestures { offset ->
                val left = LEFT_PAD.toPx()
                val plotW = size.width - left - RIGHT_PAD.toPx()
                val t = ((offset.x - left) / plotW).coerceIn(0f, 1f)
                latestOnCursor(MIN_HZ * exp(ln(MAX_HZ / MIN_HZ) * t))
            }
        },
    ) {
        val axes = drawAxes(measurer, axisTop(maxDb(curves.map { it.first })))
        curves.forEach { (spectrum, color) -> drawCurve(spectrum, color, axes::yOf) }
        drawMarker(measurer, cursorHz, Palette.TextHigh, axes)
    }
}

private class Axes(val yMax: Double, val yMin: Double, val top: Float, val bottom: Float) {
    fun yOf(db: Double) = (top + (bottom - top) * (yMax - db) / (yMax - yMin)).toFloat().coerceIn(top, bottom)
}

/** 纵轴上限：最高点留 5 dB 余量后向上取整到 10 dB。 */
private fun axisTop(maxDb: Double) = ceil((maxDb + 5) / 10) * 10

/** 网格和刻度：纵轴从 yMax 向下跨 70 dB。 */
private fun DrawScope.drawAxes(measurer: TextMeasurer, yMax: Double): Axes {
    val axes = Axes(yMax, yMax - 70, TOP_PAD.toPx(), size.height - BOTTOM_PAD.toPx())
    val hair = 1.dp.toPx()
    for (db in generateSequence(axes.yMax) { it - 10 }.takeWhile { it >= axes.yMin }) {
        val y = axes.yOf(db)
        drawLine(Palette.Line, Offset(xOf(MIN_HZ), y), Offset(xOf(MAX_HZ), y), hair)
        val text = measurer.measure("${db.toInt()}", axisLabel)
        drawText(text, topLeft = Offset(0f, (y - text.size.height / 2f).coerceIn(0f, size.height - text.size.height)))
    }
    for (f in FREQ_TICKS) {
        val x = xOf(f)
        drawLine(Palette.Line, Offset(x, axes.top), Offset(x, axes.bottom), hair, pathEffect = dash)
        val text = measurer.measure("${f.toInt()}", axisLabel)
        val tx = (x - text.size.width / 2f).coerceIn(xOf(MIN_HZ), size.width - text.size.width)
        drawText(text, topLeft = Offset(tx, axes.bottom + 5.dp.toPx()))
    }
    return axes
}

/** 竖向虚线 + 带底色的频率标签。 */
private fun DrawScope.drawMarker(measurer: TextMeasurer, freqHz: Double, color: Color, axes: Axes) {
    if (freqHz !in MIN_HZ..MAX_HZ) return
    val x = xOf(freqHz)
    drawLine(color.copy(alpha = 0.7f), Offset(x, axes.top), Offset(x, axes.bottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
    val text = measurer.measure(String.format("%.1f Hz", freqHz), markerLabel.copy(color = if (color == Palette.Accent) Palette.OnAccent else Palette.Bg))
    val padH = 6.dp.toPx()
    val padV = 2.dp.toPx()
    val w = text.size.width + padH * 2
    val h = text.size.height + padV * 2
    val left = if (x + 6.dp.toPx() + w <= size.width) x + 6.dp.toPx() else x - 6.dp.toPx() - w
    val top = axes.top + 4.dp.toPx()
    drawRoundRect(color, Offset(left, top), Size(w, h), CornerRadius(h / 2))
    drawText(text, topLeft = Offset(left + padH, top + padV))
    drawCircle(color, radius = 2.5.dp.toPx(), center = Offset(x, top + h / 2))
}

private fun maxDb(spectra: List<Spectrum>): Double {
    var best = -100.0
    for (s in spectra) {
        for (k in s.binOf(MIN_HZ)..s.binOf(MAX_HZ)) best = maxOf(best, s.db(k))
    }
    return best
}

private fun DrawScope.drawCurve(spectrum: Spectrum, color: Color, yOf: (Double) -> Float) {
    val path = Path()
    var started = false
    for (k in spectrum.binOf(MIN_HZ)..spectrum.binOf(MAX_HZ)) {
        val x = xOf(spectrum.freqOf(k))
        val y = yOf(spectrum.db(k))
        if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width = 1.4.dp.toPx(), join = StrokeJoin.Round))
}

/** 瀑布图：横轴对数频率（与频谱图对齐），纵轴时间，最新在上。 */
@Composable
fun WaterfallView(renderer: DisplayRenderer, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val version = renderer.version.collectAsStateWithLifecycle()
    val bitmap = remember(renderer) {
        Bitmap.createBitmap(renderer.columns, renderer.waterfallRows, Bitmap.Config.ARGB_8888)
    }
    val clip = remember { Path() }
    Canvas(modifier.fillMaxWidth().height(150.dp)) {
        version.value
        renderer.copyWaterfall(bitmap)
        val left = xOf(MIN_HZ)
        val right = xOf(MAX_HZ)
        clip.reset()
        clip.addRoundRect(RoundRect(left, 0f, right, size.height, CornerRadius(8.dp.toPx())))
        clipPath(clip) {
            drawImage(
                bitmap.asImageBitmap(),
                dstOffset = IntOffset(left.toInt(), 0),
                dstSize = IntSize((right - left).toInt(), size.height.toInt()),
                filterQuality = FilterQuality.Low,
            )
        }
        // 时间刻度：300 行 = 15 秒，每 5 秒一格
        for (i in 0..2) {
            val y = size.height * i / 3f
            if (i > 0) drawLine(Color.White.copy(alpha = 0.10f), Offset(left, y), Offset(right, y), 1.dp.toPx(), pathEffect = dash)
            val text = measurer.measure("${i * 5}s", axisLabel)
            drawText(text, topLeft = Offset(0f, if (i == 0) 0f else y - text.size.height / 2f))
        }
    }
}
