package com.noisedetected.core

import com.noisedetected.core.Signals.Tone
import com.noisedetected.core.analysis.LiveAnalyzer
import com.noisedetected.core.compare.Condition
import com.noisedetected.core.compare.LevelReading
import com.noisedetected.core.compare.SpectrumCompare
import com.noisedetected.core.dsp.Spectrum
import com.noisedetected.core.record.LongTermAverager
import com.noisedetected.core.record.WavWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecordCompareTest {
    private val rate = 8000

    @Test
    fun wavRoundTrip() {
        val file = File.createTempFile("rec", ".wav").apply { deleteOnExit() }
        val signal = Signals.tones(rate, 2.0, listOf(Tone(47.0, 0.3)), noise = 0.001)
        WavWriter(file, rate).use { w ->
            // 分块写入，模拟音频回调
            var i = 0
            while (i < signal.size) {
                val n = minOf(400, signal.size - i)
                w.write(signal.copyOfRange(i, i + n), n)
                i += n
            }
        }
        val back = Wav.load(file)
        assertEquals(rate, back.rate)
        assertEquals(signal.size, back.samples.size)
        for (k in signal.indices step 97) assertEquals(signal[k], back.samples[k], 1.0f / 32767)
    }

    @Test
    fun wavStopsAtMaxDuration() {
        val file = File.createTempFile("rec", ".wav").apply { deleteOnExit() }
        val w = WavWriter(file, rate, maxSeconds = 1.0)
        w.write(FloatArray(rate * 3))
        w.close()
        assertTrue(w.truncated)
        assertEquals(1.0, w.durationSec, 1e-9)
        assertEquals(rate, Wav.load(file).samples.size)
    }

    private fun averaged(amplitude: Double, seed: Long): Spectrum {
        val analyzer = LiveAnalyzer(rate)
        val avg = LongTermAverager()
        val x = Signals.tones(rate, 15.0, listOf(Tone(47.0, amplitude), Tone(94.0, amplitude / 3)), noise = 0.005, seed = seed)
        analyzer.process(x).forEach { f -> f.fineRaw?.let { avg.add(it) } }
        assertTrue(avg.frames > 30)
        return avg.result()!!
    }

    @Test
    fun levelDifferenceMatchesAmplitudeRatio() {
        val loud = averaged(0.2, 1)
        val quiet = averaged(0.05, 2)
        val a = SpectrumCompare.levelAt(loud, 47.0)
        val b = SpectrumCompare.levelAt(quiet, 47.0)
        assertEquals(12.0, a.levelDb - b.levelDb, 0.5)
        assertTrue(a.detected && b.detected)
        assertEquals(47.0, SpectrumCompare.strongestPeakHz(loud)!!, 0.05)
        // 正弦幅度 0.2 的均方值是 0.02（-17 dB）
        assertEquals(-17.0, a.levelDb, 0.5)
    }

    private fun reading(db: Double) = LevelReading(db, 20.0)

    @Test
    fun windowTemplate() {
        val same = SpectrumCompare.interpret(Condition.WINDOW_OPEN, reading(-40.0), Condition.WINDOW_CLOSED, reading(-41.0), 47.0)
        assertNotNull(same)
        assertTrue(same!!.text, same.text.contains("建筑结构"))
        // 顺序无关：B − A 始终是 关窗 − 开窗
        val quieter = SpectrumCompare.interpret(Condition.WINDOW_CLOSED, reading(-50.0), Condition.WINDOW_OPEN, reading(-40.0), 47.0)
        assertTrue(quieter!!.text, quieter.text.contains("减弱 10.0 dB"))
    }

    @Test
    fun positionTemplateDetectsStandingWave() {
        val r = SpectrumCompare.interpret(Condition.WALL, reading(-30.0), Condition.CENTER, reading(-42.0), 47.0)
        assertTrue(r!!.text, r.text.contains("驻波"))
    }

    @Test
    fun bedTemplatePointsToVibration() {
        val r = SpectrumCompare.interpret(Condition.PILLOW, reading(-30.0), Condition.ABOVE, reading(-40.0), 47.0)
        assertTrue(r!!.text, r.text.contains("床体"))
    }

    @Test
    fun mismatchedConditionsGiveNoInterpretation() {
        assertNull(SpectrumCompare.interpret(Condition.WINDOW_OPEN, reading(-30.0), Condition.CENTER, reading(-40.0), 47.0))
        assertNull(SpectrumCompare.interpret(Condition.WALL, reading(-30.0), Condition.WALL, reading(-40.0), 47.0))
        assertNull(SpectrumCompare.interpret(null, reading(-30.0), Condition.CENTER, reading(-40.0), 47.0))
    }

    @Test
    fun undetectedTargetIsReported() {
        val r = SpectrumCompare.interpret(Condition.WINDOW_OPEN, LevelReading(-90.0, 1.0), Condition.WINDOW_CLOSED, LevelReading(-91.0, 2.0), 47.0)
        assertTrue(r!!.text, r.text.contains("没有明显"))
    }
}
