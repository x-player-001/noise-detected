package com.noisedetected.core.survey

import com.noisedetected.core.dsp.Spectrum
import com.noisedetected.core.dsp.toDb
import kotlin.math.exp

data class MeterReading(
    val timeSec: Double,
    /** 平滑后的目标声级（dB，未校准的相对值）。 */
    val levelDb: Double,
    /** 目标比同频段底噪高多少。 */
    val snrDb: Double,
    /** 本次寻声中明显测到目标时的最高声级；还没有时为 null。 */
    val maxDb: Double?,
    val maxAtSec: Double?,
) {
    val detected: Boolean get() = snrDb >= DETECT_SNR_DB

    companion object {
        const val DETECT_SNR_DB = 6.0
    }
}

/**
 * 实时寻声：持续跟踪目标频率（含谐波）的声级，供用户拿着手机走动时看强弱。
 * 功率域指数平滑（时间常数 [tauSec]），过滤手持晃动、脚步和驻波造成的快速跳动。
 * 不是线程安全的：只在采集线程上调用 [add]。
 */
class TargetMeter(val target: SurveyTarget, private val tauSec: Double = 1.0) {
    private var power = Double.NaN
    private var noise = Double.NaN
    private var startSec = Double.NaN
    private var lastSec = Double.NaN
    private var maxDb: Double? = null
    private var maxAtSec: Double? = null

    fun add(spectrum: Spectrum, timeSec: Double): MeterReading {
        val m = TargetLevel.measure(spectrum, target)
        if (power.isNaN()) {
            power = m.power
            noise = m.noisePower
            startSec = timeSec
        } else {
            val a = 1 - exp(-(timeSec - lastSec).coerceAtLeast(0.0) / tauSec)
            power += a * (m.power - power)
            noise += a * (m.noisePower - noise)
        }
        lastSec = timeSec
        val level = toDb(power)
        val snr = level - toDb(noise)
        // 平滑稳定之后、且明显测到目标时才记最高值，避免开头和噪声把最高值抬上去
        val settled = timeSec - startSec >= tauSec
        if (settled && snr >= MeterReading.DETECT_SNR_DB && level > (maxDb ?: Double.NEGATIVE_INFINITY)) {
            maxDb = level
            maxAtSec = timeSec
        }
        return MeterReading(timeSec, level, snr, maxDb, maxAtSec)
    }

    /** 清除最高值记录，从当前位置重新开始比较。 */
    fun resetMax() {
        maxDb = null
        maxAtSec = null
    }
}
