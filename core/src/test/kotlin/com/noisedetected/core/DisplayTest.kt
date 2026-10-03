package com.noisedetected.core

import com.noisedetected.core.Signals.Tone
import com.noisedetected.core.analysis.LiveAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayTest {
    @Test
    fun displayRefreshesAt20HzWhileAnalysisStaysAt4Hz() {
        val rate = 8000
        val analyzer = LiveAnalyzer(rate)
        var displays = 0
        var peakHz = 0.0
        analyzer.displaySink = com.noisedetected.core.analysis.DisplaySink { f ->
            displays++
            val s = f.fast
            var best = 0
            for (k in s.binOf(20.0)..s.binOf(400.0)) if (s.power[k] > s.power[best]) best = k
            peakHz = s.freqOf(best)
        }
        val signal = Signals.tones(rate, 10.0, listOf(Tone(47.0, 0.2)))
        var analysis = 0
        // 按 50 ms 分块送入，模拟音频回调
        for (i in signal.indices step 400) analysis += analyzer.process(signal.copyOfRange(i, minOf(i + 400, signal.size))).size

        // 快谱窗 1.024 s 之后开始出帧：约 (10 − 1.024) / 0.05 ≈ 180 次显示、45 次分析
        assertEquals(180.0, displays.toDouble(), 3.0)
        assertEquals(displays / 5.0, analysis.toDouble(), 1.0)
        // 快谱分辨率约 1 Hz
        assertEquals(47.0, peakHz, 0.6)
    }
}
