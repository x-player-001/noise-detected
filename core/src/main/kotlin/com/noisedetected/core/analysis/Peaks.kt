package com.noisedetected.core.analysis

import com.noisedetected.core.dsp.Spectrum
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class Peak(
    val freqHz: Double,
    val levelDb: Double,
    /** 高出局部底噪（邻近频点中位数）的 dB 数。 */
    val prominenceDb: Double,
)

object PeakDetector {
    /**
     * 在 [minHz, maxHz] 内找突出的谱峰，按突出度从高到低返回。
     * 频率用对数幅度的抛物线插值，精度远高于频点间隔。
     * 单帧周期图的噪声起伏很大：门限 8 dB 时纯噪声每帧也有数十个假峰，12 dB 时基本没有。
     */
    fun find(
        spectrum: Spectrum,
        minHz: Double,
        maxHz: Double,
        minProminenceDb: Double = 12.0,
        maxPeaks: Int = 12,
        floorHalfWidthHz: Double = 5.0,
    ): List<Peak> {
        val lo = max(spectrum.binOf(minHz), 2)
        val hi = minOf(spectrum.binOf(maxHz), spectrum.power.size - 3)
        if (hi <= lo) return emptyList()
        val db = DoubleArray(spectrum.power.size) { spectrum.db(it) }
        val halfWidth = max(8, (floorHalfWidthHz / spectrum.binHz).roundToInt())
        val floor = localMedian(db, lo, hi, halfWidth)

        val candidates = mutableListOf<Pair<Int, Peak>>()
        for (k in lo..hi) {
            if (db[k] <= db[k - 1] || db[k] < db[k + 1]) continue
            val prominence = db[k] - floor[k - lo]
            if (prominence < minProminenceDb) continue
            val a = db[k - 1]
            val b = db[k]
            val c = db[k + 1]
            val denom = a - 2 * b + c
            val p = if (denom == 0.0) 0.0 else (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5)
            val level = b - 0.25 * (a - c) * p
            candidates += k to Peak((k + p) * spectrum.binHz, level, level - floor[k - lo])
        }

        // 相距不足 3 个频点的只留更强的一个
        val kept = mutableListOf<Pair<Int, Peak>>()
        for (cand in candidates.sortedByDescending { it.second.prominenceDb }) {
            if (kept.none { abs(it.first - cand.first) < 3 }) kept += cand
            if (kept.size >= maxPeaks) break
        }
        return kept.map { it.second }
    }

    private fun localMedian(db: DoubleArray, lo: Int, hi: Int, halfWidth: Int): DoubleArray {
        val out = DoubleArray(hi - lo + 1)
        val window = DoubleArray(2 * halfWidth + 1)
        for (k in lo..hi) {
            val start = max(0, k - halfWidth)
            val end = minOf(db.size - 1, k + halfWidth)
            val n = end - start + 1
            System.arraycopy(db, start, window, 0, n)
            window.sort(0, n)
            out[k - lo] = window[n / 2]
        }
        return out
    }
}

/** 一组谐波：基频 f0 及匹配上的谐波次数和对应谱峰。 */
data class HarmonicSet(
    val f0Hz: Double,
    val members: List<Pair<Int, Peak>>,
) {
    val harmonicNumbers: List<Int> get() = members.map { it.first }
    val strongest: Peak get() = members.maxBy { it.second.prominenceDb }.second
}

object HarmonicAnalyzer {
    private const val MAX_HARMONIC = 10

    /**
     * 从谱峰中找最能解释它们的基频。
     * 候选：各强峰本身及其 1/2、1/3、1/4；得分 = 匹配峰突出度之和 × sqrt(匹配数 / 最高次数)，
     * 后一项惩罚“缺很多次谐波”的过低基频。
     */
    fun fundamental(peaks: List<Peak>, minF0: Double = 12.0, binHz: Double = 0.25): HarmonicSet? {
        if (peaks.isEmpty()) return null
        var best: HarmonicSet? = null
        var bestScore = 0.0
        for (peak in peaks.take(6)) {
            for (d in 1..4) {
                val f0 = peak.freqHz / d
                if (f0 < minF0) break
                val members = match(peaks, f0, binHz)
                if (members.isEmpty() || members.none { it.second === peak }) continue
                val maxN = members.maxOf { it.first }
                val score = members.sumOf { it.second.prominenceDb } * kotlin.math.sqrt(members.size.toDouble() / maxN)
                if (score > bestScore + 1e-9) {
                    bestScore = score
                    best = HarmonicSet(refine(members), members)
                }
            }
        }
        return best
    }

    private fun match(peaks: List<Peak>, f0: Double, binHz: Double): List<Pair<Int, Peak>> {
        val byHarmonic = HashMap<Int, Peak>()
        for (q in peaks) {
            val n = (q.freqHz / f0).roundToInt()
            if (n < 1 || n > MAX_HARMONIC) continue
            val tol = max(1.5 * binHz, 0.006 * q.freqHz)
            if (abs(q.freqHz - n * f0) > tol) continue
            val existing = byHarmonic[n]
            if (existing == null || q.prominenceDb > existing.prominenceDb) byHarmonic[n] = q
        }
        return byHarmonic.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    /** 高次谐波对基频的约束更精确，按 次数 × 突出度 加权；过弱的成员不参与。 */
    private fun refine(members: List<Pair<Int, Peak>>): Double {
        val strongest = members.maxOf { it.second.prominenceDb }
        var num = 0.0
        var den = 0.0
        for ((n, p) in members) {
            if (p.prominenceDb < strongest - 30.0) continue
            val w = n * max(p.prominenceDb, 1.0)
            num += w * p.freqHz / n
            den += w
        }
        return num / den
    }
}
