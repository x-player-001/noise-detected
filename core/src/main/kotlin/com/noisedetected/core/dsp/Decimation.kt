package com.noisedetected.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** 单级 FIR 抗混叠滤波 + 抽取。每输入 [factor] 个样本产生一个输出。 */
class FirDecimator(private val taps: DoubleArray, val factor: Int) {
    private val n = taps.size
    // 双份缓冲，保证点积时窗口在内存中连续
    private val history = DoubleArray(n * 2)
    private var pos = 0
    private var phase = 0

    /** 最近一次 [push] 返回 true 时的输出值。 */
    var output = 0.0
        private set

    fun push(x: Double): Boolean {
        history[pos] = x
        history[pos + n] = x
        pos = if (pos + 1 == n) 0 else pos + 1
        phase++
        if (phase < factor) return false
        phase = 0
        var acc = 0.0
        for (i in 0 until n) acc += taps[i] * history[pos + i]
        output = acc
        return true
    }

    fun reset() {
        history.fill(0.0)
        pos = 0
        phase = 0
    }

    companion object {
        /** Blackman 窗 sinc 低通。[cutoff] 为归一化截止频率（周期/样本），过渡带宽约 5.5/taps。 */
        fun lowpass(numTaps: Int, cutoff: Double): DoubleArray {
            val m = numTaps - 1
            val h = DoubleArray(numTaps) { i ->
                val x = i - m / 2.0
                val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
                val w = 0.42 - 0.5 * cos(2 * PI * i / m) + 0.08 * cos(4 * PI * i / m)
                sinc * w
            }
            val sum = h.sum()
            for (i in h.indices) h[i] /= sum
            return h
        }
    }
}

/**
 * 多级降采样，把麦克风采样率（48 kHz 等）降到约 2 kHz。
 * 每级通带到输出采样率的 0.3 倍，最终有效分析带宽约 600 Hz，覆盖低频噪音关心的范围。
 */
class DecimationChain private constructor(val inputRate: Int, val factors: List<Int>) {
    val outputRate: Double = inputRate.toDouble() / factors.fold(1, Int::times)

    private val stages: List<FirDecimator>

    init {
        var rate = inputRate.toDouble()
        stages = factors.map { factor ->
            val out = rate / factor
            val transition = 0.2 * out
            var numTaps = ceil(5.5 * rate / transition).toInt()
            if (numTaps % 2 == 0) numTaps++
            val stage = FirDecimator(FirDecimator.lowpass(numTaps, 0.4 * out / rate), factor)
            rate = out
            stage
        }
    }

    inline fun process(input: FloatArray, length: Int, sink: (Double) -> Unit) {
        for (i in 0 until length) {
            val y = step(input[i].toDouble())
            if (!y.isNaN()) sink(y)
        }
    }

    /** 送入一个样本；本次有输出时返回输出值，否则返回 NaN。 */
    fun step(x: Double): Double {
        var v = x
        for (stage in stages) {
            if (!stage.push(v)) return Double.NaN
            v = stage.output
        }
        return v
    }

    fun reset() = stages.forEach { it.reset() }

    companion object {
        private const val TARGET_RATE = 2000.0

        fun forInputRate(inputRate: Int): DecimationChain {
            val dMin = ceil(inputRate / 2600.0).toInt().coerceAtLeast(1)
            val dMax = floor(inputRate / 1800.0).toInt().coerceAtLeast(dMin)
            val best = (dMin..dMax)
                .mapNotNull { d -> splitIntoStages(d)?.let { d to it } }
                .minByOrNull { (d, _) -> abs(inputRate.toDouble() / d - TARGET_RATE) }
                ?: throw IllegalArgumentException("不支持的采样率: $inputRate")
            return DecimationChain(inputRate, best.second)
        }

        /** 把总抽取倍数拆成每级不超过 8 的因子，大的放前面；有大于 8 的质因子时返回 null。 */
        internal fun splitIntoStages(total: Int): List<Int>? {
            if (total == 1) return emptyList()
            val primes = mutableListOf<Int>()
            var rest = total
            var p = 2
            while (rest > 1) {
                if (rest % p == 0) { primes += p; rest /= p } else p++
            }
            if (primes.any { it > 8 }) return null
            val stages = mutableListOf<Int>()
            var current = 1
            for (prime in primes.sortedDescending()) {
                if (current * prime > 8) { stages += current; current = 1 }
                current *= prime
            }
            stages += current
            return stages.sortedDescending()
        }
    }
}
