package com.noisedetected.core

import com.noisedetected.core.Signals.Tone
import com.noisedetected.core.analysis.LiveAnalyzer
import com.noisedetected.core.survey.Confidence
import com.noisedetected.core.survey.LocationKind
import com.noisedetected.core.survey.PointMeasurer
import com.noisedetected.core.survey.PointResult
import com.noisedetected.core.survey.SurveyAnalyzer
import com.noisedetected.core.survey.SurveyLocation
import com.noisedetected.core.survey.SurveyTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyTest {
    private val rate = 8000
    private val target = SurveyTarget(48.6, listOf(1, 2))

    private fun measurePoint(amplitude: Double, seed: Long): PointResult {
        val analyzer = LiveAnalyzer(rate)
        val measurer = PointMeasurer(target)
        val signal = Signals.tones(rate, 10.0, listOf(Tone(48.6, amplitude), Tone(97.2, amplitude / 2)), noise = 0.01, seed = seed)
        analyzer.process(signal).forEach { f -> f.fineRaw?.let { measurer.add(it) } }
        return measurer.result()!!
    }

    @Test
    fun pointLevelTracksAmplitude() {
        val loud = measurePoint(0.4, 1)
        val quiet = measurePoint(0.1, 2)
        assertEquals(12.0, loud.levelDb - quiet.levelDb, 0.5)
        assertTrue(loud.detected && quiet.detected)
        assertEquals(48.6, loud.measuredF0Hz!!, 0.3)
    }

    @Test
    fun pointWithoutTargetIsNotDetected() {
        val analyzer = LiveAnalyzer(rate)
        val measurer = PointMeasurer(target)
        analyzer.process(Signals.tones(rate, 10.0, emptyList(), noise = 0.01)).forEach { f -> f.fineRaw?.let { measurer.add(it) } }
        assertTrue(!measurer.result()!!.detected)
    }

    private fun points(vararg db: Double) = db.map { PointResult(it, -60.0, 48.6) }

    @Test
    fun clearWinnerGivesDirection() {
        val report = SurveyAnalyzer.report(
            listOf(
                SurveyLocation(1, "客厅", LocationKind.HOME_ROOM, points(-30.0, -33.0, -31.0)),
                SurveyLocation(2, "楼下大堂", LocationKind.DOWNSTAIRS, points(-20.0, -21.0, -19.5)),
                SurveyLocation(3, "楼上走廊", LocationKind.UPSTAIRS, points(-38.0, -36.0)),
            ),
        )!!
        assertEquals("楼下大堂", report.ranking.first().location.name)
        assertEquals(Confidence.HIGH, report.confidence)
        assertTrue(report.conclusion, report.conclusion.contains("楼下方向"))
        assertTrue(report.conclusion, report.conclusion.contains("楼下比楼上高"))
    }

    @Test
    fun smallDifferenceIsLowConfidence() {
        val report = SurveyAnalyzer.report(
            listOf(
                SurveyLocation(1, "主卧", LocationKind.HOME_ROOM, points(-38.0, -22.0, -33.0)),
                SurveyLocation(2, "客厅", LocationKind.HOME_ROOM, points(-29.0, -31.0, -27.0)),
            ),
        )!!
        assertEquals(Confidence.LOW, report.confidence)
        assertTrue(report.tips.any { it.contains("驻波") })
    }

    @Test
    fun energyMeanWeightsLoudPoints() {
        assertEquals(-20.0 + 10 * kotlin.math.log10(1.1 / 2), SurveyAnalyzer.energyMean(listOf(-20.0, -30.0)), 1e-9)
    }
}
