package com.noisedetected.app.shots

import com.noisedetected.app.CompareItem
import com.noisedetected.app.LiveState
import com.noisedetected.app.SurveyState
import com.noisedetected.app.data.MeasurementMeta
import com.noisedetected.app.data.StoredMeasurement
import com.noisedetected.app.ui.DisplayRenderer
import com.noisedetected.core.analysis.DisplayFrame
import com.noisedetected.core.compare.Condition
import com.noisedetected.core.dsp.Spectrum
import com.noisedetected.core.inference.Candidate
import com.noisedetected.core.inference.InferenceResult
import com.noisedetected.core.inference.SourceType
import com.noisedetected.core.survey.LocationKind
import com.noisedetected.core.survey.PointResult
import com.noisedetected.core.survey.SurveyAnalyzer
import com.noisedetected.core.survey.SurveyLocation
import com.noisedetected.core.survey.SurveyTarget
import java.io.File
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random

/** 截图用的模拟数据：一台约 49.6 Hz 的定速水泵，带 2、3 次谐波。 */
object Fixtures {
    private const val RATE = 2000.0

    /** 噪声底 + 若干高斯形峰；amp 为各峰相对噪声底的功率倍数。 */
    fun spectrum(size: Int, seed: Int, peaks: List<Pair<Double, Double>>, floorScale: Double = 1.0): Spectrum {
        val rnd = Random(seed)
        val binHz = RATE / size
        val power = DoubleArray(size / 2 + 1) { k ->
            val f = (k * binHz).coerceAtLeast(1.0)
            val floor = 2e-10 * floorScale * (40.0 / f).pow(1.2) * (0.3 + rnd.nextDouble() * 1.4)
            var p = floor
            for ((hz, amp) in peaks) {
                val d = (f - hz) / maxOf(binHz * 1.2, 0.35)
                p += 2e-10 * amp * exp(-d * d)
            }
            p
        }
        return Spectrum(RATE, size, power)
    }

    private fun pumpPeaks(t: Int, gain: Double = 1.0): List<Pair<Double, Double>> {
        val wobble = 1.0 + 0.25 * kotlin.math.sin(t / 9.0)
        val on = (t / 70) % 3 != 2 // 间歇的 24.6 Hz 分量
        return buildList {
            add(49.6 to 900.0 * gain * wobble)
            add(99.2 to 260.0 * gain)
            add(148.8 to 70.0 * gain * wobble)
            add(198.4 to 18.0 * gain)
            if (on) add(24.8 to 40.0 * gain)
            add(300.0 to 6.0)
        }
    }

    fun renderer(frames: Int = 320): DisplayRenderer {
        val r = DisplayRenderer()
        for (t in 0 until frames) {
            val fast = spectrum(2048, t, pumpPeaks(t))
            val fine = spectrum(8192, 10_000 + t, pumpPeaks(t))
            r.onDisplay(DisplayFrame(4.0 + t * 0.05, fast, fine))
        }
        return r
    }

    val inference = InferenceResult(
        candidates = listOf(
            Candidate(
                SourceType.FIXED_SPEED_MOTOR, "定速电机设备（水泵 / 风机）", 0.72,
                "主频 49.6 Hz，比 50 Hz 低约 0.8%，这是交流电机转差的特征，相当于 2 极电机约 2976 转/分。还出现了高次谐波，可能是水泵叶轮或风机叶片的通过频率。",
                "住宅里最常见的是二次供水水泵（地下室水泵房）、地暖循环泵、新风或排风机。观察用水高峰（早 7–9 点、晚 7–10 点）是否更明显；先关掉自家冰箱、鱼缸泵等设备排除。",
            ),
            Candidate(SourceType.TRANSFORMER, "变压器 / 电气设备的嗡嗡声", 0.18, "", ""),
            Candidate(SourceType.VARIABLE_SPEED, "变频设备（空调外机 / 多联机 / 变频水泵）", 0.10, "", ""),
        ),
        notes = listOf("继续测量到 30 秒以上，可以判断频率是否变化、是否时有时无。"),
        ready = true,
        observedSec = 42.0,
        mainFrequencyHz = 49.6,
    )

    val liveRunning = LiveState(
        running = true, elapsedSec = 42, sourceLabel = "未处理音源（UNPROCESSED）",
        showPeakHold = true, mainFrequencyHz = 49.6, inference = inference,
    )

    val liveIdle = LiveState()

    val liveAnalyzing = LiveState(
        running = true, elapsedSec = 4, sourceLabel = "未处理音源（UNPROCESSED）",
        inference = InferenceResult(emptyList(), listOf("正在采集，请保持手机静止…"), false, 4.0, null),
    )

    val liveDone = liveRunning.copy(running = false, canSave = true, showPeakHold = false)

    private fun points(vararg db: Double) = db.map { PointResult(it, it - 14, 49.6) }

    private val locations = listOf(
        SurveyLocation(1, "主卧", LocationKind.HOME_ROOM, points(-62.1, -58.4, -60.3, -64.0)),
        SurveyLocation(2, "楼下 302", LocationKind.DOWNSTAIRS, points(-51.2, -53.0, -49.8)),
        SurveyLocation(3, "楼道", LocationKind.STAIRWELL, points(-57.5, -59.9)),
        SurveyLocation(4, "客厅", LocationKind.HOME_ROOM),
    )

    val survey = SurveyState(
        target = SurveyTarget(49.6, listOf(1, 2, 3)),
        locations = locations,
        measuringId = 4,
        progress = 0.62f,
        report = SurveyAnalyzer.report(locations),
    )

    val surveyEmpty = SurveyState()

    private val day = 24 * 3600 * 1000L
    private val base = 1_790_000_000_000L

    private fun record(id: String, label: String, ago: Long, cond: Condition?, hz: Double?, conclusion: String?, dur: Double) =
        StoredMeasurement(
            MeasurementMeta(id, label, cond, base - ago, dur, 48000, null, hz, conclusion, "$id.wav", false),
            File("/tmp"),
        )

    val records = listOf(
        record("a", "主卧床头 · 夜间", 3600_000, Condition.WINDOW_CLOSED, 49.6, "定速电机设备（水泵 / 风机）", 62.0),
        record("b", "主卧床头 · 开窗", 3 * 3600_000, Condition.WINDOW_OPEN, 49.7, "定速电机设备（水泵 / 风机）", 48.0),
        record("c", "客厅中央", day, Condition.CENTER, 99.4, "变压器 / 电气设备的嗡嗡声", 35.0),
        record("d", "书房", 2 * day, null, null, null, 21.0),
        record("e", "楼下 302 门口", 3 * day, null, 49.5, "定速电机设备（水泵 / 风机）", 90.0),
    )

    val compare = listOf(
        CompareItem(records[0], spectrum(8192, 1, pumpPeaks(0))),
        CompareItem(records[1], spectrum(8192, 2, pumpPeaks(0, gain = 0.35), floorScale = 2.5)),
    )
}
