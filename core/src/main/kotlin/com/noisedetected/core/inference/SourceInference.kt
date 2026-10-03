package com.noisedetected.core.inference

import com.noisedetected.core.analysis.SoundFeatures
import com.noisedetected.core.analysis.ToneFeatures
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class SourceType {
    TRANSFORMER,
    ELECTRICAL_50HZ,
    FIXED_SPEED_MOTOR,
    VARIABLE_SPEED,
    INTERMITTENT,
    BROADBAND,
    AUDIBLE_CONTENT,
    UNKNOWN,
}

data class Candidate(
    val type: SourceType,
    val title: String,
    /** 0–1，各候选之和为 1。 */
    val confidence: Double,
    val reason: String,
    val advice: String,
)

data class InferenceResult(
    val candidates: List<Candidate>,
    val notes: List<String>,
    /** 观测时间足够，可以给出初步结论。 */
    val ready: Boolean,
    val observedSec: Double,
    val mainFrequencyHz: Double?,
) {
    val top: Candidate? get() = candidates.firstOrNull()
}

/**
 * 规则库：根据主音调的频率、稳定性、谐波和启停规律推断声源类型。
 * 按中国电网 50 Hz 设计。规则为初版经验值，需要用实测案例持续校正。
 */
object SourceInference {
    const val MIN_OBSERVE_SEC = 6.0
    const val CONFIDENT_OBSERVE_SEC = 20.0

    /** 20–200 Hz 频带声级低于此值（dBFS）视为麦克风没有信号。安静房间的手机实测通常在 -90 dBFS 以上。 */
    const val SILENCE_DB = -115.0

    /** 人声、音乐判定：中高频相邻帧变化中位数和起伏幅度的门限（dB）。设备噪声实测分别约 1 dB 和几 dB。 */
    const val CONTENT_FLICKER_DB = 3.5
    const val CONTENT_SPREAD_DB = 10.0

    /** 有人声、音乐时，低频音调出现比例达到此值才认为背后另有持续运行的设备。 */
    const val CONTINUOUS_PRESENCE = 0.8

    /** 交流异步电机同步转速对应的频率区间（考虑 0.3%–8% 转差）。 */
    private data class PoleRange(val poles: Int, val lo: Double, val hi: Double)

    private val motorRanges = listOf(
        PoleRange(2, 46.0, 49.65),
        PoleRange(4, 23.0, 24.85),
        PoleRange(6, 15.3, 16.55),
        PoleRange(8, 11.5, 12.42),
    )

    fun infer(features: SoundFeatures?): InferenceResult {
        if (features == null || features.observedSec < MIN_OBSERVE_SEC) {
            return InferenceResult(emptyList(), listOf("正在采集，请保持手机静止…"), false, features?.observedSec ?: 0.0, null)
        }
        if (features.bandLevelDb < SILENCE_DB) {
            val silent = Candidate(
                SourceType.UNKNOWN, "没有收到声音信号", 1.0,
                "麦克风几乎没有输入。",
                "检查是否授予了麦克风权限、麦克风是否被其他应用（通话、录音）占用，或者麦克风孔是否被手机壳挡住。",
            )
            return InferenceResult(listOf(silent), emptyList(), false, features.observedSec, null)
        }
        val scores = mutableListOf<Candidate>()
        val notes = mutableListOf<String>()
        val tone = features.tone?.takeIf { it.presence >= 0.25 && it.prominenceDb >= 8.0 }

        if (tone != null) toneRules(tone, features, scores) else broadbandRules(features, scores)

        val content = features.midFlickerDb >= CONTENT_FLICKER_DB && features.midSpreadDb >= CONTENT_SPREAD_DB
        if (content) {
            // 人声、音乐里的音高会冒充设备频率；只有在说话间隙也一直存在的低频音调才算设备
            val deviceContinues = tone != null && tone.presence >= CONTINUOUS_PRESENCE
            scores.replaceAll { it.copy(confidence = it.confidence * if (deviceContinues) 0.9 else 0.25) }
            scores += Candidate(
                SourceType.AUDIBLE_CONTENT, "电视 / 说话 / 音乐等日常声音", if (deviceContinues) 0.3 else 0.9,
                "中高频（人声、音乐所在的频段）声音很强，而且一直在快速起伏，像是电视、说话或音乐。",
                "关掉电视和音响、停止说话后重新测量。低频噪音在夜深人静时最明显，那时测最准。",
            )
            if (deviceContinues) notes += "测量时有电视、说话或音乐声。低频结论仍可参考，但最好安静时再测一次确认。"
        }

        scores += Candidate(
            SourceType.UNKNOWN, "暂时无法判断", 0.15,
            "特征不够典型。",
            "到声音最明显的位置延长测量时间，或换个时段（夜间更安静时）再测。",
        )

        val total = scores.sumOf { it.confidence }
        val normalized = scores
            .map { it.copy(confidence = it.confidence / total) }
            .sortedByDescending { it.confidence }
            .filter { it.confidence >= 0.08 }
            .let { list -> if (list.first().type == SourceType.UNKNOWN) list else list.filter { it.type != SourceType.UNKNOWN } }
            .take(3)

        if (tone != null && tone.f0Hz < 30) {
            notes += "主频低于 30 Hz，手机麦克风在这个频段灵敏度很低，结论仅供参考。"
        }
        if (features.observedSec < CONFIDENT_OBSERVE_SEC) {
            notes += "继续测量到 30 秒以上，可以判断频率是否变化、是否时有时无。"
        }
        return InferenceResult(
            candidates = normalized,
            notes = notes,
            ready = features.observedSec >= CONFIDENT_OBSERVE_SEC,
            observedSec = features.observedSec,
            mainFrequencyHz = tone?.f0Hz,
        )
    }

    private fun toneRules(t: ToneFeatures, f: SoundFeatures, out: MutableList<Candidate>) {
        val f0 = t.f0Hz
        // 变压器、电机是持续运行的，出现比例低时打折
        val continuity = ((t.presence - 0.25) / 0.5).coerceIn(0.4, 1.0)
        val stable = t.f0StdHz < 0.2 && t.f0RangeHz < 0.8
        val longEnough = t.spanSec >= 15.0
        val varying = t.f0RangeHz >= 1.0 || abs(t.driftHzPerMin) >= 0.6
        val hz = fmt(f0)

        if (t.restarts >= 2 && t.presence < 0.75) {
            out += Candidate(
                SourceType.INTERMITTENT, "间歇运行的设备（电梯 / 压缩机 / 间歇水泵）", 0.9,
                "主频约 $hz Hz 的声音时有时无，测量期间出现 ${t.restarts} 次启停。",
                "记下每次出现和消失的时间。每次几十秒、和有人上下楼对应的，多半是电梯曳引机；" +
                    "几分钟到几十分钟一轮的，可能是冰箱、冷柜或其他压缩机；和用水时段对应的，可能是水泵。",
            )
        }

        var matchedKnown = false
        val at100 = near(f0, 100.0, 0.45)
        if (stable && (at100 || near(f0, 200.0, 0.7) || near(f0, 300.0, 1.0))) {
            matchedKnown = true
            // 变压器以 100 Hz 为主；只有 200/300 Hz 时证据弱一些
            out += Candidate(
                SourceType.TRANSFORMER, "变压器 / 电气设备的嗡嗡声", (if (at100) 0.9 else 0.7) * continuity,
                "主频 $hz Hz，正好是电网 50 Hz 的整数倍且非常稳定，这是变压器铁芯磁致伸缩的典型特征。",
                "排查附近的配电房、箱式变电站、楼内配电间（常在地下室或一楼）；家里的镇流器灯具、" +
                    "电源适配器、UPS 也会产生。这类声音通常 24 小时持续，夜间更明显。",
            )
        }
        if (stable && near(f0, 50.0, 0.3)) {
            matchedKnown = true
            out += Candidate(
                SourceType.ELECTRICAL_50HZ, "50 Hz 电气嗡声", 0.75 * continuity,
                "主频 $hz Hz，与电网频率一致且非常稳定。",
                "常见于电源、镇流器、小型变压器和接地不良的电器。先在家里逐个断开电器排查，再看配电设施。",
            )
        }

        val direct = motorRanges.firstOrNull { f0 in it.lo..it.hi }
        val doubled = motorRanges.firstOrNull { f0 / 2 in it.lo..it.hi }
            ?.takeIf { !near(f0, 100.0, 0.45) }
        val motor = direct ?: doubled
        if (stable && motor != null) {
            matchedKnown = true
            val shaftHz = if (direct != null) f0 else f0 / 2
            val rpm = (shaftHz * 60).roundToInt()
            val sync = 100.0 / motor.poles
            val slip = (1 - shaftHz / sync) * 100
            val blade = t.harmonics.any { it in 4..8 }
            val reason = buildString {
                append("主频 $hz Hz")
                if (direct == null) append("（是转频 ${fmt(shaftHz)} Hz 的 2 倍）")
                append("，比 ${fmt(sync)} Hz 低约 ${fmt(slip)}%，这是交流电机转差的特征，")
                append("相当于 ${motor.poles} 极电机约 $rpm 转/分。")
                if (blade) append("还出现了高次谐波，可能是水泵叶轮或风机叶片的通过频率。")
            }
            out += Candidate(
                SourceType.FIXED_SPEED_MOTOR, "定速电机设备（水泵 / 风机）", (if (direct != null) 0.85 else 0.6) * continuity,
                reason,
                "住宅里最常见的是二次供水水泵（地下室水泵房）、地暖循环泵、新风或排风机。" +
                    "观察用水高峰（早 7–9 点、晚 7–10 点）是否更明显；先关掉自家冰箱、鱼缸泵等设备排除。",
            )
        }

        if (varying && f0 in 15.0..150.0) {
            out += Candidate(
                SourceType.VARIABLE_SPEED, "变频设备（空调外机 / 多联机 / 变频水泵）", if (longEnough) 0.85 else 0.5,
                "主频在 ${fmt(t.f0MinHz)}–${fmt(t.f0MaxHz)} Hz 之间变化（约每分钟 ${fmt(abs(t.driftHzPerMin))} Hz），" +
                    "说明设备转速在随负荷调节。",
                "排查楼顶或外墙的空调外机、商铺的多联机或冷水机组、变频供水泵。" +
                    "对比白天和夜间、天气冷热时声音是否不同。",
            )
        } else if (stable && !matchedKnown) {
            out += Candidate(
                SourceType.VARIABLE_SPEED, "恒速运行的变频设备", 0.4,
                "主频 $hz Hz 很稳定，但不符合电网或普通电机的频率，可能是变频器固定在某个频率运行。",
                "排查空调外机、变频水泵、风机等；记录声音在一天中是否变化。",
            )
            out += Candidate(
                SourceType.FIXED_SPEED_MOTOR, "旋转机械（风机 / 泵 / 压缩机）", 0.25,
                "存在稳定的单一频率 $hz Hz，通常来自旋转设备。",
                "从楼内设备间、楼顶设备、相邻商铺开始排查。",
            )
        }
    }

    private fun broadbandRules(f: SoundFeatures, out: MutableList<Candidate>) {
        if (f.levelBursts >= 2) {
            out += Candidate(
                SourceType.INTERMITTENT, "间歇性宽频低频声（地铁 / 车辆 / 电梯）", 0.65,
                "没有明显的单一频率，但声级有 ${f.levelBursts} 次明显起落，起落幅度约 ${fmt(f.bandLevelSpreadDb)} dB。",
                "记录每次出现的时间和持续时长：地铁通常每 2–5 分钟一次、持续 20–40 秒；" +
                    "重型车辆与道路车流对应；电梯与有人上下楼对应。",
            )
        }
        out += Candidate(
            SourceType.BROADBAND, "宽频低频噪声（交通 / 气流 / 远处设备）", if (f.levelBursts >= 2) 0.35 else 0.7,
            "没有明显的单一频率，能量分布在较宽的低频范围。",
            "对照附近道路、地铁、通风管道和楼顶风机；关窗后是否明显减弱可以区分空气传声和结构传声。",
        )
    }

    private fun near(f: Double, target: Double, tol: Double) = abs(f - target) <= tol

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.1f", v)
}
