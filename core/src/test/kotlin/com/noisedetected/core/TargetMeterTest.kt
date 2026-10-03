package com.noisedetected.core

import com.noisedetected.core.Signals.Tone
import com.noisedetected.core.analysis.DisplaySink
import com.noisedetected.core.analysis.LiveAnalyzer
import com.noisedetected.core.survey.MeterReading
import com.noisedetected.core.survey.SurveyTarget
import com.noisedetected.core.survey.TargetMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetMeterTest {
    private val rate = 8000
    private val target = SurveyTarget(130.0, listOf(1, 2))

    private fun tone(amplitude: Double, seconds: Double, seed: Long) =
        Signals.tones(rate, seconds, listOf(Tone(130.0, amplitude), Tone(260.0, amplitude / 2)), noise = 0.01, seed = seed)

    /** 像 App 一样按 50 ms 分块送入，每个显示帧读一次计量器。 */
    private fun run(meter: TargetMeter, vararg parts: FloatArray): List<MeterReading> {
        val analyzer = LiveAnalyzer(rate)
        val readings = mutableListOf<MeterReading>()
        analyzer.displaySink = DisplaySink { f -> readings += meter.add(f.fast, f.timeSec) }
        val signal = parts.reduce { a, b -> a + b }
        for (i in signal.indices step 400) analyzer.process(signal.copyOfRange(i, minOf(i + 400, signal.size)))
        return readings
    }

    @Test
    fun walkingCloserRaisesReadingBy6dB() {
        val readings = run(TargetMeter(target), tone(0.05, 6.0, 1), tone(0.1, 6.0, 2))
        val far = readings.first { it.timeSec >= 5.5 }
        val near = readings.last()
        assertTrue(far.detected && near.detected)
        // 振幅翻倍 = +6 dB；平滑约 1 s，5 s 后应已稳定
        assertEquals(6.0, near.levelDb - far.levelDb, 1.0)
        assertEquals(near.levelDb, near.maxDb!!, 0.5)
    }

    @Test
    fun readingRespondsWithinTwoSeconds() {
        val readings = run(TargetMeter(target), tone(0.05, 6.0, 1), tone(0.1, 6.0, 2))
        val before = readings.last { it.timeSec <= 6.0 }.levelDb
        val after2s = readings.first { it.timeSec >= 8.0 }.levelDb
        assertTrue("2 秒内应完成大部分变化：${after2s - before} dB", after2s - before >= 4.5)
    }

    @Test
    fun maxIsKeptAfterWalkingAwayAndCanBeReset() {
        val meter = TargetMeter(target)
        val readings = run(meter, tone(0.1, 6.0, 1), tone(0.03, 6.0, 2))
        val last = readings.last()
        assertNotNull(last.maxDb)
        assertTrue("离开后读数应低于最高值", last.maxDb!! - last.levelDb > 8.0)

        meter.resetMax()
        assertNull(run(meter, Signals.tones(rate, 2.0, emptyList(), noise = 0.01)).last().maxDb)
    }

    @Test
    fun noiseOnlyIsNotDetectedAndSetsNoMax() {
        val readings = run(TargetMeter(target), Signals.tones(rate, 6.0, emptyList(), noise = 0.02))
        assertFalse(readings.last().detected)
        assertNull(readings.last().maxDb)
    }
}
