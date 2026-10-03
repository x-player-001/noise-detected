package com.noisedetected.core

import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/** 测试用合成信号。 */
object Signals {
    data class Tone(val freqHz: Double, val amplitude: Double)

    fun tones(rate: Int, seconds: Double, tones: List<Tone>, noise: Double = 0.01, seed: Long = 1): FloatArray {
        val n = (rate * seconds).toInt()
        val rnd = Random(seed)
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            var v = noise * rnd.nextGaussian()
            for (tone in tones) v += tone.amplitude * sin(2 * PI * tone.freqHz * t)
            v.toFloat()
        }
    }

    /** 基频随时间线性变化（带谐波），相位连续积分。 */
    fun sweep(rate: Int, seconds: Double, f0Start: Double, f0End: Double, harmonicAmps: List<Double>, noise: Double = 0.01): FloatArray {
        val n = (rate * seconds).toInt()
        val rnd = Random(2)
        var phase = 0.0
        return FloatArray(n) { i ->
            val f = f0Start + (f0End - f0Start) * i / n
            phase += 2 * PI * f / rate
            var v = noise * rnd.nextGaussian()
            harmonicAmps.forEachIndexed { k, a -> v += a * sin((k + 1) * phase) }
            v.toFloat()
        }
    }

    /** 一阶低通的“红”噪声，模拟宽频低频声；可选按周期开关。 */
    fun lowNoise(rate: Int, seconds: Double, amplitude: Double, onSec: Double = 0.0, offSec: Double = 0.0, floor: Double = 0.002): FloatArray {
        val n = (rate * seconds).toInt()
        val rnd = Random(3)
        val alpha = 2 * PI * 150.0 / rate
        var y = 0.0
        return FloatArray(n) { i ->
            y += alpha * (rnd.nextGaussian() - y)
            val t = i.toDouble() / rate
            val on = offSec <= 0.0 || (t % (onSec + offSec)) < onSec
            ((if (on) amplitude * y else 0.0) + floor * rnd.nextGaussian()).toFloat()
        }
    }

    /** 把信号按周期开关（关时只剩底噪）。 */
    fun gate(signal: FloatArray, rate: Int, onSec: Double, offSec: Double, floor: Double = 0.003): FloatArray {
        val rnd = Random(4)
        return FloatArray(signal.size) { i ->
            val t = i.toDouble() / rate
            val on = (t % (onSec + offSec)) < onSec
            ((if (on) signal[i].toDouble() else 0.0) + floor * rnd.nextGaussian()).toFloat()
        }
    }
}
