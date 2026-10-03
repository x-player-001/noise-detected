package com.noisedetected.core.compare

import com.noisedetected.core.analysis.PeakDetector
import com.noisedetected.core.dsp.Spectrum
import com.noisedetected.core.dsp.toDb
import com.noisedetected.core.survey.SurveyTarget
import com.noisedetected.core.survey.TargetLevel
import java.util.Locale
import kotlin.math.abs

/** 成对对比的模板：同一个频率在两种条件下的声级差，用来区分传播途径。 */
enum class CompareTemplate(val title: String) {
    WINDOW("开窗 vs 关窗"),
    POSITION("墙边 vs 房间中央"),
    BED("枕头处 vs 坐起"),
}

/** 保存测量时选的“对比用途”。每个模板有 A、B 两个条件，解读时用 B − A。 */
enum class Condition(val label: String, val template: CompareTemplate, val isB: Boolean) {
    WINDOW_OPEN("开窗", CompareTemplate.WINDOW, false),
    WINDOW_CLOSED("关窗", CompareTemplate.WINDOW, true),
    WALL("床头 / 墙边", CompareTemplate.POSITION, false),
    CENTER("房间中央", CompareTemplate.POSITION, true),
    PILLOW("枕头处", CompareTemplate.BED, false),
    ABOVE("坐起位置（枕头上方约 50 cm）", CompareTemplate.BED, true),
}

data class LevelReading(val levelDb: Double, val snrDb: Double) {
    val detected: Boolean get() = snrDb >= 6.0
}

data class Interpretation(val title: String, val text: String)

object SpectrumCompare {
    /** 目标频率处的声级：在 ±4% 内找峰（跟随录音间的轻微频率差），取主瓣能量。 */
    fun levelAt(spectrum: Spectrum, freqHz: Double): LevelReading {
        val m = TargetLevel.measure(spectrum, SurveyTarget(freqHz))
        return LevelReading(toDb(m.power), toDb(m.power) - toDb(m.noisePower))
    }

    /** 最突出的谱峰频率，作为对比光标的默认位置。 */
    fun strongestPeakHz(spectrum: Spectrum): Double? =
        PeakDetector.find(spectrum, 12.0, 500.0).firstOrNull()?.freqHz

    /**
     * 两段测量构成同一模板的 A、B 两个条件时给出解读，否则返回 null。
     * 阈值：±3 dB 以内视为没有差别（手机放置、环境起伏就能造成这么大的变化）。
     */
    fun interpret(
        first: Condition?, firstLevel: LevelReading,
        second: Condition?, secondLevel: LevelReading,
        freqHz: Double,
    ): Interpretation? {
        if (first == null || second == null || first.template != second.template || first.isB == second.isB) return null
        val (a, b) = if (first.isB) secondLevel to firstLevel else firstLevel to secondLevel
        val template = first.template
        val hz = fmt(freqHz)
        if (!a.detected && !b.detected) {
            return Interpretation(template.title, "两段录音在 $hz Hz 处都没有明显的声音，换到主峰频率再看，或在声音明显时重新录。")
        }
        val d = b.levelDb - a.levelDb
        val ad = fmt(abs(d))
        val text = when (template) {
            CompareTemplate.WINDOW -> when {
                d <= -3 -> "关窗后 $hz Hz 减弱 $ad dB：声音主要经门窗以空气声传入。加强门窗密封、换隔音窗会有效果。"
                d < 3 -> "开窗、关窗几乎没有差别（相差 $ad dB）：声音主要不是从窗户进来的，更可能经地面、墙体等建筑结构传入，" +
                    "或者被房间驻波放大。换隔音窗帮助不大。"
                else -> "关窗后 $hz Hz 反而增强 $ad dB：房间封闭后驻波更明显，问题和房间声学有关，可以考虑低频吸声处理。"
            }
            CompareTemplate.POSITION -> when {
                d <= -6 -> "房间中央比墙边弱 $ad dB，符合驻波“贴墙强、中间弱”的分布，$hz Hz 很可能被房间放大了。" +
                    "床头、沙发等常待的位置尽量离开墙面，或在墙角加低频吸声。"
                d < 3 && d > -3 -> "墙边和中央差别不大（$ad dB），没有明显的驻波分布，声音在房间里比较均匀。"
                d >= 3 -> "房间中央反而比墙边强 $ad dB。可能是其他方向的驻波，或者声源离房间中央更近；沿房间另一个方向再对比一次。"
                else -> "墙边比中央强 $ad dB，有一定的位置差异，可能存在驻波。沿房间不同方向多测几个点确认。"
            }
            CompareTemplate.BED -> when {
                d <= -6 -> "枕头处比上方 50 cm 强 $ad dB。房间驻波很难在这么短的距离内差这么多，更可能是振动经床体、墙体传到头部。" +
                    "试着把床挪离墙 20 cm，或者换个方向躺，再测一次。"
                d < 3 && d > -3 -> "枕头处和坐起位置声级接近（相差 $ad dB）。如果躺下听得到、坐起听不到，可能是这个频率接近听阈，" +
                    "几 dB 的变化就会影响能不能听见。"
                d >= 3 -> "坐起位置反而比枕头处强 $ad dB，说明床体没有额外传入振动，声音主要来自空气。"
                else -> "枕头处比坐起位置强 $ad dB，有一定差异。把床挪离墙面后再对比，可以判断是否有振动经床体传入。"
            }
        }
        return Interpretation(template.title, text)
    }

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.1f", v)
}
