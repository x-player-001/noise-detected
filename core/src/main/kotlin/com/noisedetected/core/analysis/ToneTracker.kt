package com.noisedetected.core.analysis

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** 每帧（约 0.25 s）的观测结果。 */
data class FrameObservation(
    val timeSec: Double,
    val f0Hz: Double?,
    val harmonics: List<Int>,
    val prominenceDb: Double,
    /** 20–200 Hz 频带总声级（dB，相对值）。 */
    val bandLevelDb: Double,
    /** 原始采样率下的短窗声级：300–3500 Hz（人声、音乐所在频段）和 20–200 Hz。 */
    val midLevelDb: Double = Double.NaN,
    val lowLevelDb: Double = Double.NaN,
)

/** 主音调在观测窗内的统计特征。 */
data class ToneFeatures(
    val f0Hz: Double,
    val f0StdHz: Double,
    val f0MinHz: Double,
    val f0MaxHz: Double,
    val driftHzPerMin: Double,
    /** 有该音调的帧占全部帧的比例。 */
    val presence: Double,
    /** 观测窗内的停→启次数。 */
    val restarts: Int,
    val harmonics: List<Int>,
    val prominenceDb: Double,
    val spanSec: Double,
) {
    val f0RangeHz: Double get() = f0MaxHz - f0MinHz
}

data class SoundFeatures(
    val observedSec: Double,
    val tone: ToneFeatures?,
    val bandLevelDb: Double,
    /** 频带声级 90 分位与 10 分位之差。 */
    val bandLevelSpreadDb: Double,
    /** 频带声级明显的起落次数（宽频间歇声）。 */
    val levelBursts: Int,
    /** 中高频（300–3500 Hz）声级的 90 分位与 10 分位之差：人声、音乐起伏大，设备噪声平稳。 */
    val midSpreadDb: Double = 0.0,
    /** 中高频比低频高出的 dB 数（中位数）。 */
    val midOverLowDb: Double = Double.NEGATIVE_INFINITY,
    /** 相邻帧（0.25 s）中高频声级变化的中位数：音节、音符快速更替时大，车辆、地铁缓慢升降时小。 */
    val midFlickerDb: Double = 0.0,
)

/**
 * 跨帧跟踪基频，判断它是稳定、缓慢漂移还是时有时无。
 * 基频与某轨迹最近值接近（含倍频误判）的归入该轨迹，取帧数最多的轨迹作为主音调。
 */
class ToneTracker(private val windowSec: Double = 60.0) {
    private val observations = ArrayDeque<FrameObservation>()

    fun add(obs: FrameObservation) {
        observations.addLast(obs)
        while (observations.first().timeSec < obs.timeSec - windowSec) observations.removeFirst()
    }

    fun clear() = observations.clear()

    fun features(): SoundFeatures? {
        if (observations.size < 4) return null
        val first = observations.first().timeSec
        val last = observations.last().timeSec
        val observed = last - first
        val levels = observations.map { it.bandLevelDb }.sorted()
        val p10 = percentile(levels, 0.1)
        val p90 = percentile(levels, 0.9)
        // 声级设下限，避免数字静音（-200 dB）把起伏统计撑爆
        val midSeries = observations.map { it.midLevelDb }.filter { !it.isNaN() }.map { maxOf(it, LEVEL_FLOOR_DB) }
        val mids = midSeries.sorted()
        val midOverLow = observations.filter { !it.midLevelDb.isNaN() }
            .map { maxOf(it.midLevelDb, LEVEL_FLOOR_DB) - maxOf(it.lowLevelDb, LEVEL_FLOOR_DB) }.sorted()
        val flicker = midSeries.zipWithNext { a, b -> kotlin.math.abs(b - a) }.sorted()
        return SoundFeatures(
            observedSec = observed,
            tone = dominantTone(first, last),
            bandLevelDb = percentile(levels, 0.5),
            bandLevelSpreadDb = p90 - p10,
            levelBursts = countBursts(p10, p90),
            midSpreadDb = if (mids.isEmpty()) 0.0 else percentile(mids, 0.9) - percentile(mids, 0.1),
            midOverLowDb = if (midOverLow.isEmpty()) Double.NEGATIVE_INFINITY else percentile(midOverLow, 0.5),
            midFlickerDb = if (flicker.isEmpty()) 0.0 else percentile(flicker, 0.5),
        )
    }

    private class Track {
        val times = mutableListOf<Double>()
        val f0s = mutableListOf<Double>()
        val obs = mutableListOf<FrameObservation>()
    }

    private fun dominantTone(windowStart: Double, windowEnd: Double): ToneFeatures? {
        val tracks = mutableListOf<Track>()
        for (o in observations) {
            val f0 = o.f0Hz ?: continue
            var target: Track? = null
            var normalized = f0
            // 按频率归轨，不限时间间隔：同一设备停一会儿再启动仍算同一条轨迹，停顿计为启停
            for (t in tracks.sortedByDescending { it.times.last() }) {
                val ref = t.f0s.last()
                val match = listOf(f0, f0 * 2, f0 / 2).firstOrNull { close(it, ref) } ?: continue
                target = t
                normalized = match
                break
            }
            val track = target ?: Track().also { tracks += it }
            track.times += o.timeSec
            track.f0s += normalized
            track.obs += o
        }
        val main = tracks.maxByOrNull { it.times.size } ?: return null
        if (main.times.size < 3) return null

        val sorted = main.f0s.sorted()
        val mean = main.f0s.average()
        val std = sqrt(main.f0s.sumOf { (it - mean) * (it - mean) } / main.f0s.size)

        var restarts = 0
        for (i in 1 until main.times.size) if (main.times[i] - main.times[i - 1] > 2.0) restarts++
        if (main.times.first() - windowStart > 2.0) restarts++

        val harmonicCounts = HashMap<Int, Int>()
        for (o in main.obs) for (n in o.harmonics) harmonicCounts[n] = (harmonicCounts[n] ?: 0) + 1
        val harmonics = harmonicCounts.filter { it.value >= 0.4 * main.obs.size }.keys.sorted()

        return ToneFeatures(
            f0Hz = percentile(sorted, 0.5),
            f0StdHz = std,
            f0MinHz = percentile(sorted, 0.05),
            f0MaxHz = percentile(sorted, 0.95),
            driftHzPerMin = slope(main.times, main.f0s) * 60.0,
            presence = main.times.size.toDouble() / observations.size,
            restarts = restarts,
            harmonics = harmonics,
            prominenceDb = percentile(main.obs.map { it.prominenceDb }.sorted(), 0.5),
            spanSec = main.times.last() - main.times.first(),
        )
    }

    /** 以 p10–p90 中点为门限、带 2 dB 回差统计“起”的次数。声级几乎不变时为 0。 */
    private fun countBursts(p10: Double, p90: Double): Int {
        if (p90 - p10 < 8.0) return 0
        val mid = (p10 + p90) / 2
        var high = observations.first().bandLevelDb > mid
        var bursts = 0
        for (o in observations) {
            if (!high && o.bandLevelDb > mid + 2) { high = true; bursts++ }
            else if (high && o.bandLevelDb < mid - 2) high = false
        }
        return bursts
    }

    companion object {
        private const val LEVEL_FLOOR_DB = -110.0

        fun close(a: Double, b: Double): Boolean = abs(a - b) <= max(0.6, 0.04 * b)

        fun percentile(sorted: List<Double>, q: Double): Double {
            if (sorted.isEmpty()) return Double.NaN
            val pos = q * (sorted.size - 1)
            val i = pos.toInt()
            val frac = pos - i
            return if (i + 1 < sorted.size) sorted[i] * (1 - frac) + sorted[i + 1] * frac else sorted[i]
        }

        fun slope(x: List<Double>, y: List<Double>): Double {
            val mx = x.average()
            val my = y.average()
            var num = 0.0
            var den = 0.0
            for (i in x.indices) {
                num += (x[i] - mx) * (y[i] - my)
                den += (x[i] - mx) * (x[i] - mx)
            }
            return if (den == 0.0) 0.0 else num / den
        }
    }
}
