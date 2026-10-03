package com.noisedetected.core.survey

import com.noisedetected.core.dsp.Spectrum
import com.noisedetected.core.dsp.toDb
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

enum class LocationKind(val label: String) {
    HOME_ROOM("本户房间"),
    UPSTAIRS("楼上"),
    DOWNSTAIRS("楼下"),
    STAIRWELL("楼道 / 楼梯间"),
    EQUIPMENT("设备间 / 水泵房附近"),
    OUTDOOR("室外"),
}

/** 巡测盯住的目标声音：基频和要计入的谐波次数。 */
data class SurveyTarget(val f0Hz: Double, val harmonics: List<Int> = listOf(1))

data class PointResult(
    val levelDb: Double,
    val noiseDb: Double,
    val measuredF0Hz: Double?,
) {
    val snrDb: Double get() = levelDb - noiseDb
    val detected: Boolean get() = snrDb >= 6.0
}

data class SurveyLocation(
    val id: Long,
    val name: String,
    val kind: LocationKind,
    val points: List<PointResult> = emptyList(),
)

object TargetLevel {
    private const val LOBE_BINS = 2
    private const val NOISE_HALF_WIDTH_HZ = 15.0

    data class Measurement(val power: Double, val noisePower: Double, val f0Hz: Double?)

    /**
     * 目标声音在一帧谱中的能量：在每个谐波附近（±4%，跟随轻微漂移）找峰，累加主瓣能量；
     * 底噪取同一邻域内去掉峰窗后的中位数，按相同频点数折算。
     */
    fun measure(spectrum: Spectrum, target: SurveyTarget, maxHz: Double = 600.0): Measurement {
        var power = 0.0
        var noise = 0.0
        var f0: Double? = null
        for (n in target.harmonics.sorted()) {
            val center = n * target.f0Hz
            if (center > maxHz) break
            val half = max(3 * spectrum.binHz, 0.04 * center)
            val lo = spectrum.binOf(center - half)
            val hi = spectrum.binOf(center + half)
            var peak = lo
            for (k in lo..hi) if (spectrum.power[k] > spectrum.power[peak]) peak = k
            for (k in peak - LOBE_BINS..peak + LOBE_BINS) {
                if (k in spectrum.power.indices) power += spectrum.power[k]
            }
            val around = mutableListOf<Double>()
            for (k in spectrum.binOf(center - NOISE_HALF_WIDTH_HZ)..spectrum.binOf(center + NOISE_HALF_WIDTH_HZ)) {
                if (k < lo || k > hi) around += spectrum.power[k]
            }
            if (around.isNotEmpty()) noise += around.sorted()[around.size / 2] * (2 * LOBE_BINS + 1)
            if (f0 == null) f0 = spectrum.freqOf(peak) / n
        }
        return Measurement(power, noise, f0)
    }
}

/** 一个测点：累积若干帧谱的能量平均。 */
class PointMeasurer(val target: SurveyTarget) {
    private var sumPower = 0.0
    private var sumNoise = 0.0
    private val f0s = mutableListOf<Double>()
    var frames = 0
        private set

    fun add(spectrum: Spectrum) {
        val m = TargetLevel.measure(spectrum, target)
        sumPower += m.power
        sumNoise += m.noisePower
        m.f0Hz?.let { f0s += it }
        frames++
    }

    fun result(): PointResult? {
        if (frames == 0) return null
        return PointResult(
            levelDb = toDb(sumPower / frames),
            noiseDb = toDb(sumNoise / frames),
            measuredF0Hz = f0s.sorted().getOrNull(f0s.size / 2),
        )
    }
}

enum class Confidence(val label: String) {
    HIGH("较可信"),
    MEDIUM("有一定依据"),
    LOW("依据不足"),
}

data class LocationSummary(
    val location: SurveyLocation,
    /** 各测点的能量平均（dB）。 */
    val meanDb: Double,
    /** 各测点 dB 值的标准差，反映驻波造成的点间差异。 */
    val spreadDb: Double,
    val detectedPoints: Int,
)

data class SurveyReport(
    val ranking: List<LocationSummary>,
    val conclusion: String,
    val confidence: Confidence,
    val tips: List<String>,
)

object SurveyAnalyzer {
    fun summarize(location: SurveyLocation): LocationSummary? {
        val levels = location.points.map { it.levelDb }
        if (levels.isEmpty()) return null
        val mean = energyMean(levels)
        val avgDb = levels.average()
        val spread = sqrt(levels.sumOf { (it - avgDb) * (it - avgDb) } / levels.size)
        return LocationSummary(location, mean, spread, location.points.count { it.detected })
    }

    fun report(locations: List<SurveyLocation>): SurveyReport? {
        val ranking = locations.mapNotNull { summarize(it) }.sortedByDescending { it.meanDb }
        if (ranking.isEmpty()) return null
        val tips = mutableListOf<String>()

        if (ranking.size < 2) {
            return SurveyReport(ranking, "至少测 2 个位置才能比较强弱。", Confidence.LOW, defaultTips(ranking))
        }
        if (ranking.all { it.detectedPoints == 0 }) {
            return SurveyReport(
                ranking,
                "各位置都没有明显测到目标频率。可能声音太弱、频率已变化，或目标设备已停止运行。",
                Confidence.LOW,
                listOf("回到识别页重新确认当前的主频，再继续巡测。"),
            )
        }

        val top = ranking[0]
        val second = ranking[1]
        val diff = top.meanDb - second.meanDb
        val spread = max(max(top.spreadDb, second.spreadDb), 1.0)
        val enoughPoints = top.location.points.size >= 3 && second.location.points.size >= 2
        val confidence = when {
            diff >= 6.0 && diff >= 2 * spread && enoughPoints -> Confidence.HIGH
            diff >= 3.0 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        val conclusion = buildString {
            if (confidence == Confidence.LOW) {
                append("各位置差别不大（第一、第二名相差 ${fmt(diff)} dB），还不能确定方向。")
                append("建议扩大范围：去楼上、楼下、楼道或设备间附近测量。")
            } else {
                append("「${top.location.name}」最强，比「${second.location.name}」高 ${fmt(diff)} dB。")
                append(direction(top.location))
            }
            groupComparison(ranking)?.let { append(it) }
        }

        ranking.filter { it.location.points.size < 3 }.take(2).forEach {
            tips += "在「${it.location.name}」再多测几个点（角落、中间、靠墙），结果更可靠。"
        }
        if (ranking.any { it.spreadDb > 6.0 }) {
            tips += "同一位置各点相差较大，这是房间驻波造成的，多测几个点取平均即可。"
        }
        return SurveyReport(ranking, conclusion, confidence, tips)
    }

    private fun direction(location: SurveyLocation): String = when (location.kind) {
        LocationKind.DOWNSTAIRS -> "声源很可能在楼下方向。"
        LocationKind.UPSTAIRS -> "声源很可能在楼上方向。"
        LocationKind.EQUIPMENT -> "声源很可能就是这个设备间里的设备。"
        LocationKind.STAIRWELL -> "声源可能在楼内公共区域，如管井、楼梯间或电梯井附近。"
        LocationKind.OUTDOOR -> "声源可能在室外，如空调外机或室外设备。"
        LocationKind.HOME_ROOM -> "声源可能在这个房间所在的一侧（隔壁、楼上或楼下对应位置）。"
    }

    /** 楼上、楼下都测过时，补一句整体对比。 */
    private fun groupComparison(ranking: List<LocationSummary>): String? {
        fun groupMean(kind: LocationKind): Double? =
            ranking.filter { it.location.kind == kind }.takeIf { it.isNotEmpty() }?.let { g -> energyMean(g.map { it.meanDb }) }
        val up = groupMean(LocationKind.UPSTAIRS) ?: return null
        val down = groupMean(LocationKind.DOWNSTAIRS) ?: return null
        val d = down - up
        if (abs(d) < 3.0) return "楼上楼下差别不大（${fmt(abs(d))} dB）。"
        return if (d > 0) "整体看楼下比楼上高 ${fmt(d)} dB。" else "整体看楼上比楼下高 ${fmt(-d)} dB。"
    }

    private fun defaultTips(ranking: List<LocationSummary>) = listOf(
        "每个位置测 3–5 个点：角落、房间中间、靠墙处；测量时保持安静、手机放稳。",
        if (ranking.isEmpty()) "先添加一个位置。" else "再添加一个位置，比如另一个房间、楼道或楼下。",
    )

    fun energyMean(levelsDb: List<Double>): Double = 10 * log10(levelsDb.sumOf { 10.0.pow(it / 10) } / levelsDb.size)

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.1f", v)
}
