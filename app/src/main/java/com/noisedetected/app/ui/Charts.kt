package com.noisedetected.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noisedetected.core.dsp.Spectrum
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln

private const val MIN_HZ = 10.0
private const val MAX_HZ = 500.0
private val FREQ_TICKS = listOf(10.0, 20.0, 50.0, 100.0, 200.0, 500.0)
private val LEFT_PAD = 30.dp
private val RIGHT_PAD = 12.dp
private val BOTTOM_PAD = 16.dp

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
    val lineColor = MaterialTheme.colorScheme.primary
    val holdColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val markerColor = MaterialTheme.colorScheme.error
    val version = renderer.version.collectAsStateWithLifecycle()
    val curve = remember(renderer) { FloatArray(renderer.columns) }
    val hold = remember(renderer) { FloatArray(renderer.columns) }
    val path = remember { Path() }

    Canvas(modifier.fillMaxWidth().height(200.dp)) {
        version.value // 在绘制阶段读取：数据更新只触发重绘
        val axes = drawAxes(measurer, renderer.axisTopDb, gridColor, labelColor)
        if (renderer.copyCurve(curve, if (showPeakHold) hold else null)) {
            if (showPeakHold) drawColumns(path, renderer.columnHz, hold, holdColor, axes)
            drawColumns(path, renderer.columnHz, curve, lineColor, axes)
        }
        if (mainFrequencyHz != null) drawMarker(measurer, mainFrequencyHz, markerColor, axes.plotH)
    }
}

private fun DrawScope.drawColumns(path: Path, freqs: DoubleArray, db: FloatArray, color: Color, axes: Axes) {
    path.reset()
    for (i in freqs.indices) {
        val x = xOf(freqs[i])
        val y = axes.yOf(db[i].toDouble())
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width = 2f))
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
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val markerColor = MaterialTheme.colorScheme.onSurface
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
        val axes = drawAxes(measurer, axisTop(maxDb(curves.map { it.first })), gridColor, labelColor)
        curves.forEach { (spectrum, color) -> drawCurve(spectrum, color, axes::yOf) }
        drawMarker(measurer, cursorHz, markerColor, axes.plotH)
    }
}

private class Axes(val yMax: Double, val yMin: Double, val plotH: Float) {
    fun yOf(db: Double) = (plotH * (yMax - db) / (yMax - yMin)).toFloat().coerceIn(0f, plotH)
}

/** 纵轴上限：最高点留 5 dB 余量后向上取整到 10 dB。 */
private fun axisTop(maxDb: Double) = ceil((maxDb + 5) / 10) * 10

/** 网格和刻度：纵轴从 yMax 向下跨 70 dB。 */
private fun DrawScope.drawAxes(measurer: TextMeasurer, yMax: Double, gridColor: Color, labelColor: Color): Axes {
    val axes = Axes(yMax, yMax - 70, size.height - BOTTOM_PAD.toPx())
    val labelStyle = TextStyle(fontSize = 9.sp, color = labelColor)
    for (db in generateSequence(axes.yMax) { it - 10 }.takeWhile { it >= axes.yMin }) {
        val y = axes.yOf(db)
        drawLine(gridColor, Offset(xOf(MIN_HZ), y), Offset(xOf(MAX_HZ), y), 1f)
        label(measurer, "${db.toInt()}", Offset(0f, (y - 6.dp.toPx()).coerceAtLeast(0f)), labelStyle)
    }
    for (f in FREQ_TICKS) {
        val x = xOf(f)
        drawLine(gridColor, Offset(x, 0f), Offset(x, axes.plotH), 1f)
        label(measurer, "${f.toInt()}", Offset((x - 8.dp.toPx()).coerceAtLeast(xOf(MIN_HZ)), axes.plotH + 2.dp.toPx()), labelStyle)
    }
    return axes
}

private fun DrawScope.drawMarker(measurer: TextMeasurer, freqHz: Double, color: Color, plotH: Float) {
    if (freqHz !in MIN_HZ..MAX_HZ) return
    val x = xOf(freqHz)
    drawLine(color, Offset(x, 0f), Offset(x, plotH), 2f)
    label(
        measurer, String.format("%.1f Hz", freqHz),
        Offset((x + 4.dp.toPx()).coerceAtMost(size.width - 64.dp.toPx()), 2f),
        TextStyle(fontSize = 11.sp, color = color),
    )
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
    drawPath(path, color, style = Stroke(width = 2f))
}

private fun DrawScope.label(measurer: TextMeasurer, text: String, at: Offset, style: TextStyle) {
    drawText(measurer, text, topLeft = at, style = style)
}

/** 瀑布图：横轴对数频率（与频谱图对齐），纵轴时间，最新在上。 */
@Composable
fun WaterfallView(renderer: DisplayRenderer, modifier: Modifier = Modifier) {
    val version = renderer.version.collectAsStateWithLifecycle()
    val bitmap = remember(renderer) {
        Bitmap.createBitmap(renderer.columns, renderer.waterfallRows, Bitmap.Config.ARGB_8888)
    }
    Canvas(modifier.fillMaxWidth().height(160.dp)) {
        version.value
        renderer.copyWaterfall(bitmap)
        val left = xOf(MIN_HZ)
        drawImage(
            bitmap.asImageBitmap(),
            dstOffset = IntOffset(left.toInt(), 0),
            dstSize = IntSize((xOf(MAX_HZ) - left).toInt(), size.height.toInt()),
            filterQuality = FilterQuality.Low,
        )
    }
}
