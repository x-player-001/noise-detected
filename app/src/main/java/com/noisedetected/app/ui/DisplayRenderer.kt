package com.noisedetected.app.ui

import android.graphics.Bitmap
import com.noisedetected.core.analysis.DisplayFrame
import com.noisedetected.core.analysis.DisplaySink
import com.noisedetected.core.dsp.Spectrum
import com.noisedetected.core.dsp.toDb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln

/**
 * 实时显示数据：在采集线程上把每 50 ms 的频谱压缩成按屏幕列（对数频率）排列的数据，
 * 界面只需按列画线、把瀑布图像素拷进位图。缓冲区全部复用，不在每帧分配内存。
 */
class DisplayRenderer(
    val columns: Int = 360,
    val waterfallRows: Int = 300,
    val minHz: Double = 10.0,
    val maxHz: Double = 500.0,
) : DisplaySink {
    private val lock = Any()

    /** 各列中心频率，供界面计算横坐标。 */
    val columnHz = DoubleArray(columns) { i -> minHz * exp(ln(maxHz / minHz) * (i + 0.5) / columns) }
    private val edgeHz = DoubleArray(columns + 1) { i -> minHz * exp(ln(maxHz / minHz) * i / columns) }

    // 采集线程独占
    private val colPower = DoubleArray(columns)
    private val smoothed = DoubleArray(columns)
    private val held = DoubleArray(columns)
    private val row = DoubleArray(columns)
    private val rowDb = DoubleArray(columns)
    private val sortBuf = DoubleArray(columns)
    private var hasData = false
    @Volatile private var holdResetRequested = false
    private var floorDb = Double.NaN
    private var axisChangedAt = 0.0

    // 受 lock 保护，界面读取
    private val curveDb = FloatArray(columns)
    private val holdDb = FloatArray(columns)
    private val pixels = IntArray(columns * waterfallRows) { COLORS[0] }
    private var head = 0
    private var ready = false

    /** 纵轴上限（dB），带回差：变大立即跟上，变小要持续 2 秒，避免坐标轴来回跳。 */
    @Volatile var axisTopDb = -60.0
        private set

    private val _version = MutableStateFlow(0L)
    /** 每次有新数据加一，界面在绘制阶段读取它以触发重绘。 */
    val version: StateFlow<Long> = _version.asStateFlow()

    override fun onDisplay(frame: DisplayFrame) {
        // 曲线优先用细谱（低频分辨率高），细谱就绪前用快谱
        project(frame.fine ?: frame.fast, colPower)
        val resetHold = holdResetRequested
        holdResetRequested = false
        var maxDb = -200.0
        for (i in 0 until columns) {
            val p = colPower[i]
            if (resetHold) held[i] = smoothed[i]
            if (!hasData) {
                smoothed[i] = p
                held[i] = p
            } else {
                smoothed[i] += SMOOTHING * (p - smoothed[i])
                if (p > held[i]) held[i] = p
            }
            maxDb = maxOf(maxDb, toDb(smoothed[i]))
        }
        hasData = true
        updateAxis(maxDb, frame.timeSec)

        // 瀑布图用快谱（1 s 窗），跟得上变化
        project(frame.fast, row)
        for (i in 0 until columns) rowDb[i] = toDb(row[i])
        System.arraycopy(rowDb, 0, sortBuf, 0, columns)
        sortBuf.sort()
        val p20 = sortBuf[columns / 5]
        floorDb = if (floorDb.isNaN()) p20 else floorDb + 0.02 * (p20 - floorDb)

        synchronized(lock) {
            for (i in 0 until columns) {
                curveDb[i] = toDb(smoothed[i]).toFloat()
                holdDb[i] = toDb(held[i]).toFloat()
            }
            head = if (head == 0) waterfallRows - 1 else head - 1
            val base = head * columns
            for (i in 0 until columns) {
                val v = ((rowDb[i] - floorDb) / WATERFALL_RANGE_DB).coerceIn(0.0, 1.0)
                pixels[base + i] = COLORS[(v * (COLORS.size - 1)).toInt()]
            }
            ready = true
        }
        _version.value++
    }

    /** 拷出当前曲线（dB）；holdDest 为 null 时不拷峰值保持。尚无数据时返回 false。 */
    fun copyCurve(dest: FloatArray, holdDest: FloatArray?): Boolean = synchronized(lock) {
        if (!ready) return false
        System.arraycopy(curveDb, 0, dest, 0, columns)
        if (holdDest != null) System.arraycopy(holdDb, 0, holdDest, 0, columns)
        true
    }

    /** 把瀑布图拷进位图（宽 columns、高 waterfallRows），最新一行在最上方。 */
    fun copyWaterfall(bitmap: Bitmap) = synchronized(lock) {
        val newest = waterfallRows - head
        bitmap.setPixels(pixels, head * columns, columns, 0, 0, columns, newest)
        if (head > 0) bitmap.setPixels(pixels, 0, columns, 0, newest, columns, head)
    }

    /** 下一帧起峰值保持从当前值重新累积（由采集线程执行）。 */
    fun resetHold() {
        holdResetRequested = true
    }

    fun clear() {
        synchronized(lock) {
            pixels.fill(COLORS[0])
            head = 0
            ready = false
            hasData = false
            floorDb = Double.NaN
            axisTopDb = -60.0
        }
        _version.value++
    }

    /** 每列取所覆盖频点的最大功率；列比频点还窄时（低频段）按中心频率在相邻频点间插值，避免台阶。 */
    private fun project(spectrum: Spectrum, out: DoubleArray) {
        val power = spectrum.power
        for (i in 0 until columns) {
            val lo = spectrum.binOf(edgeHz[i])
            val hi = spectrum.binOf(edgeHz[i + 1])
            out[i] = if (hi - lo >= 2) {
                var m = 0.0
                for (k in lo..hi) if (power[k] > m) m = power[k]
                m
            } else {
                val x = columnHz[i] / spectrum.binHz
                val k = x.toInt().coerceIn(0, power.size - 2)
                val f = x - k
                // 对数域插值：峰附近更自然
                exp(ln(maxOf(power[k], 1e-20)) * (1 - f) + ln(maxOf(power[k + 1], 1e-20)) * f)
            }
        }
    }

    private fun updateAxis(maxDb: Double, timeSec: Double) {
        val target = ceil((maxDb + 5) / 10) * 10
        if (target > axisTopDb || timeSec < axisChangedAt) {
            axisTopDb = target
            axisChangedAt = timeSec
        } else if (target < axisTopDb - 10) {
            if (timeSec - axisChangedAt > 2.0) {
                axisTopDb = target
                axisChangedAt = timeSec
            }
        } else {
            axisChangedAt = timeSec
        }
    }

    companion object {
        /** 每 50 ms 的平滑系数，时间常数约 0.15 s。 */
        private const val SMOOTHING = 0.3
        private const val WATERFALL_RANGE_DB = 50.0

        /** 暖色调色带（黑 → 深棕 → 琥珀 → 奶白），与界面的琥珀强调色一致。 */
        val COLORS: IntArray = run {
            val stops = listOf(
                0.0 to 0x0B0B0D, 0.22 to 0x1E1409, 0.42 to 0x4A2A0B, 0.6 to 0x8F4E0E,
                0.76 to 0xD9821E, 0.88 to 0xFFB547, 1.0 to 0xFFF1D2,
            )
            IntArray(256) { i ->
                val t = i / 255.0
                val j = stops.indexOfLast { it.first <= t }.coerceAtMost(stops.size - 2)
                val (t0, c0) = stops[j]
                val (t1, c1) = stops[j + 1]
                val f = ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0)
                fun ch(c: Int, s: Int) = (c shr s) and 0xFF
                fun mix(s: Int) = (ch(c0, s) + (ch(c1, s) - ch(c0, s)) * f).toInt()
                (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
            }
        }
    }
}
