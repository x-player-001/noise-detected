package com.noisedetected.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

fun toDb(power: Double): Double = 10.0 * log10(max(power, 1e-20))

/**
 * 单边功率谱。power[k] 是第 k 个频点上的均方值：
 * 幅度为 A 的正弦，主瓣内各频点之和为 A²/2；白噪声各频点之和为其方差。
 */
class Spectrum(val sampleRate: Double, val fftSize: Int, val power: DoubleArray) {
    val binHz: Double get() = sampleRate / fftSize

    fun freqOf(bin: Int): Double = bin * binHz

    fun binOf(freqHz: Double): Int = (freqHz / binHz).roundToInt().coerceIn(0, power.size - 1)

    fun db(bin: Int): Double = toDb(power[bin])

    /** [f1, f2] 频带内的总均方值。 */
    fun bandPower(f1: Double, f2: Double): Double {
        var sum = 0.0
        for (k in binOf(f1)..binOf(f2)) sum += power[k]
        return sum
    }
}

/** 汉宁窗 FFT 功率谱，去直流。 */
class SpectrumAnalyzer(val sampleRate: Double, val fftSize: Int) {
    private val fft = Fft(fftSize)
    private val window = DoubleArray(fftSize) { 0.5 - 0.5 * cos(2.0 * PI * it / fftSize) }
    private val scale = 2.0 / (fftSize * window.sumOf { it * it })
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)

    /** 对 samples 中从 offset 开始的 fftSize 个样本求谱。返回的 Spectrum 持有独立数组。 */
    fun compute(samples: DoubleArray, offset: Int = 0): Spectrum {
        val power = DoubleArray(fftSize / 2 + 1)
        computeInto(samples, offset, power)
        return Spectrum(sampleRate, fftSize, power)
    }

    /** 同 [compute]，但写入调用方提供的数组（长度 fftSize/2+1），不分配内存，供高频刷新的显示使用。 */
    fun computeInto(samples: DoubleArray, offset: Int, power: DoubleArray) {
        var mean = 0.0
        for (i in 0 until fftSize) mean += samples[offset + i]
        mean /= fftSize
        for (i in 0 until fftSize) {
            re[i] = (samples[offset + i] - mean) * window[i]
            im[i] = 0.0
        }
        fft.transform(re, im)
        val half = fftSize / 2
        for (k in 0..half) power[k] = (re[k] * re[k] + im[k] * im[k]) * scale
        power[0] *= 0.5
        power[half] *= 0.5
    }
}

/** 定长环形缓冲，保存最近 capacity 个样本。 */
class RingBuffer(val capacity: Int) {
    private val data = DoubleArray(capacity)
    private var write = 0
    var count = 0L
        private set

    fun push(x: Double) {
        data[write] = x
        write = if (write + 1 == capacity) 0 else write + 1
        count++
    }

    /** 把最近 n 个样本按时间顺序复制到 dest[0 until n]。 */
    fun latest(n: Int, dest: DoubleArray) {
        require(n <= capacity && n <= count)
        var start = write - n
        if (start < 0) start += capacity
        val first = minOf(n, capacity - start)
        System.arraycopy(data, start, dest, 0, first)
        if (first < n) System.arraycopy(data, 0, dest, first, n - first)
    }

    fun clear() {
        write = 0
        count = 0
    }
}
