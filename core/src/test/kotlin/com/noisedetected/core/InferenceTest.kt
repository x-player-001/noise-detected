package com.noisedetected.core

import com.noisedetected.core.Signals.Tone
import com.noisedetected.core.analysis.analyzeSignal
import com.noisedetected.core.inference.InferenceResult
import com.noisedetected.core.inference.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceTest {
    // 8 kHz 输入同样经过降采样链，测试比 48 kHz 快得多
    private val rate = 8000

    private fun infer(signal: FloatArray): InferenceResult {
        val frame = analyzeSignal(signal, rate)
        assertNotNull(frame)
        return frame!!.inference
    }

    private fun describe(r: InferenceResult) =
        r.candidates.joinToString { "${it.type}=${"%.2f".format(it.confidence)}" } + " f0=${r.mainFrequencyHz}"

    @Test
    fun transformerHum() {
        val r = infer(Signals.tones(rate, 40.0, listOf(Tone(100.0, 0.2), Tone(200.0, 0.08), Tone(300.0, 0.05))))
        assertEquals(describe(r), SourceType.TRANSFORMER, r.top?.type)
        assertEquals(100.0, r.mainFrequencyHz!!, 0.1)
        assertTrue(r.ready)
    }

    @Test
    fun fixedSpeedPumpWithSlip() {
        val r = infer(
            Signals.tones(rate, 40.0, listOf(Tone(48.6, 0.2), Tone(97.2, 0.1), Tone(145.8, 0.05), Tone(291.6, 0.06))),
        )
        assertEquals(describe(r), SourceType.FIXED_SPEED_MOTOR, r.top?.type)
        assertEquals(48.6, r.mainFrequencyHz!!, 0.1)
        assertTrue(r.top!!.reason.contains("2 极"))
    }

    @Test
    fun pumpSeenOnlyAtTwiceShaftFrequency() {
        val r = infer(Signals.tones(rate, 40.0, listOf(Tone(97.2, 0.2))))
        assertEquals(describe(r), SourceType.FIXED_SPEED_MOTOR, r.top?.type)
        assertFalse(r.candidates.any { it.type == SourceType.TRANSFORMER })
    }

    @Test
    fun variableSpeedCompressor() {
        val r = infer(Signals.sweep(rate, 60.0, 40.0, 55.0, listOf(0.2, 0.08)))
        assertEquals(describe(r), SourceType.VARIABLE_SPEED, r.top?.type)
    }

    @Test
    fun intermittentDevice() {
        val tone = Signals.tones(rate, 60.0, listOf(Tone(48.6, 0.2), Tone(97.2, 0.1)), noise = 0.0)
        val r = infer(Signals.gate(tone, rate, onSec = 12.0, offSec = 12.0))
        assertEquals(describe(r), SourceType.INTERMITTENT, r.top?.type)
    }

    @Test
    fun broadbandNoise() {
        val r = infer(Signals.lowNoise(rate, 30.0, 0.3))
        assertEquals(describe(r), SourceType.BROADBAND, r.top?.type)
    }

    @Test
    fun intermittentBroadbandBursts() {
        val r = infer(Signals.lowNoise(rate, 60.0, 0.3, onSec = 8.0, offSec = 12.0))
        assertEquals(describe(r), SourceType.INTERMITTENT, r.top?.type)
    }

    @Test
    fun digitalSilenceIsReportedAsNoSignal() {
        val r = infer(FloatArray(rate * 15))
        assertEquals("没有收到声音信号", r.top?.title)
        assertFalse(r.ready)
    }

    @Test
    fun shortObservationGivesNoConclusionYet() {
        val r = infer(Signals.tones(rate, 12.0, listOf(Tone(100.0, 0.2))))
        assertFalse(r.ready)
        assertTrue(describe(r), r.candidates.isEmpty())
        assertNull(r.mainFrequencyHz)
    }
}
