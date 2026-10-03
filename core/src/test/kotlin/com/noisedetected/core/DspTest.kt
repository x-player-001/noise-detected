package com.noisedetected.core

import com.noisedetected.core.analysis.HarmonicAnalyzer
import com.noisedetected.core.analysis.PeakDetector
import com.noisedetected.core.dsp.DecimationChain
import com.noisedetected.core.dsp.Fft
import com.noisedetected.core.dsp.SpectrumAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class DspTest {
    @Test
    fun fftMatchesNaiveDft() {
        val n = 64
        val rnd = Random(7)
        val x = DoubleArray(n) { rnd.nextGaussian() }
        val re = x.copyOf()
        val im = DoubleArray(n)
        Fft(n).transform(re, im)
        for (k in 0 until n) {
            var sr = 0.0
            var si = 0.0
            for (t in 0 until n) {
                sr += x[t] * cos(2 * PI * k * t / n)
                si -= x[t] * sin(2 * PI * k * t / n)
            }
            assertEquals(sr, re[k], 1e-9)
            assertEquals(si, im[k], 1e-9)
        }
    }

    @Test
    fun decimationChainChoosesAbout2kHz() {
        assertEquals(2000.0, DecimationChain.forInputRate(48000).outputRate, 1e-9)
        assertEquals(2100.0, DecimationChain.forInputRate(44100).outputRate, 1e-9)
        assertEquals(2000.0, DecimationChain.forInputRate(16000).outputRate, 1e-9)
        assertEquals(2000.0, DecimationChain.forInputRate(2000).outputRate, 1e-9)
    }

    @Test
    fun decimationKeepsPassbandAndRejectsAliases() {
        fun outputRms(freq: Double): Double {
            val chain = DecimationChain.forInputRate(48000)
            val input = Signals.tones(48000, 2.0, listOf(Signals.Tone(freq, 1.0)), noise = 0.0)
            val out = mutableListOf<Double>()
            chain.process(input, input.size) { out += it }
            val tail = out.drop(out.size / 2)
            return sqrt(tail.sumOf { it * it } / tail.size)
        }
        assertEquals(1 / sqrt(2.0), outputRms(100.0), 0.01)
        assertEquals(1 / sqrt(2.0), outputRms(450.0), 0.01)
        // 5 kHz 和 2.1 kHz 若不滤除，会混叠到低频
        assertTrue(20 * log10(outputRms(5000.0) * sqrt(2.0)) < -60)
        assertTrue(20 * log10(outputRms(2100.0) * sqrt(2.0)) < -60)
    }

    @Test
    fun spectrumPowerAndPeakFrequencyAreAccurate() {
        val fs = 2000
        val amp = 0.5
        val x = Signals.tones(fs, 4.096, listOf(Signals.Tone(48.3, amp)), noise = 0.001)
        val spec = SpectrumAnalyzer(fs.toDouble(), 8192).compute(DoubleArray(8192) { x[it].toDouble() })
        assertEquals(amp * amp / 2, spec.bandPower(46.0, 50.0), 0.005)
        val peak = PeakDetector.find(spec, 12.0, 500.0).first()
        assertEquals(48.3, peak.freqHz, 0.03)
    }

    @Test
    fun separatesTwoCloseTones() {
        val fs = 2000
        val x = Signals.tones(fs, 4.096, listOf(Signals.Tone(48.3, 0.3), Signals.Tone(49.3, 0.3)), noise = 0.001)
        val spec = SpectrumAnalyzer(fs.toDouble(), 8192).compute(DoubleArray(8192) { x[it].toDouble() })
        val freqs = PeakDetector.find(spec, 12.0, 500.0).map { it.freqHz }.sorted()
        assertTrue(freqs.any { abs(it - 48.3) < 0.05 })
        assertTrue(freqs.any { abs(it - 49.3) < 0.05 })
    }

    @Test
    fun harmonicAnalyzerFindsFundamentalEvenWhenItIsWeak() {
        val fs = 2000
        val x = Signals.tones(
            fs, 4.096,
            listOf(Signals.Tone(48.6, 0.02), Signals.Tone(97.2, 0.3), Signals.Tone(145.8, 0.2), Signals.Tone(194.4, 0.1)),
            noise = 0.001,
        )
        val spec = SpectrumAnalyzer(fs.toDouble(), 8192).compute(DoubleArray(8192) { x[it].toDouble() })
        val set = HarmonicAnalyzer.fundamental(PeakDetector.find(spec, 12.0, 500.0), binHz = spec.binHz)
        assertNotNull(set)
        assertEquals(48.6, set!!.f0Hz, 0.05)
        assertEquals(listOf(1, 2, 3, 4), set.harmonicNumbers)
    }
}
