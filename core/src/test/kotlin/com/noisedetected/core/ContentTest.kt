package com.noisedetected.core

import com.noisedetected.core.Signals.Tone
import com.noisedetected.core.analysis.analyzeSignal
import com.noisedetected.core.inference.InferenceResult
import com.noisedetected.core.inference.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 电视、说话、音乐等日常声音不能被当成设备噪声。语音素材由 Windows 语音合成生成，音乐取自系统铃声。 */
class ContentTest {
    private val rate = 16000
    private val female = Wav.load("speech_female.wav")
    private val male = Wav.load("speech_male.wav")
    private val music = Wav.loop(Wav.resample(Wav.load("music.wav"), rate), 45.0).samples

    private fun infer(signal: FloatArray): InferenceResult = analyzeSignal(signal, rate)!!.inference

    private fun describe(r: InferenceResult) =
        r.candidates.joinToString { "${it.type}=${"%.2f".format(it.confidence)}" } + " notes=${r.notes}"

    @Test
    fun chineseSpeech() {
        val r = infer(female.samples)
        assertEquals(describe(r), SourceType.AUDIBLE_CONTENT, r.top?.type)
    }

    @Test
    fun englishSpeech() {
        val r = infer(Wav.loop(male, 45.0).samples)
        assertEquals(describe(r), SourceType.AUDIBLE_CONTENT, r.top?.type)
    }

    @Test
    fun music() {
        val r = infer(music)
        assertEquals(describe(r), SourceType.AUDIBLE_CONTENT, r.top?.type)
    }

    @Test
    fun tvSpeechOverMusic() {
        val r = infer(Wav.mix(female.samples, music, 0.3f))
        assertEquals(describe(r), SourceType.AUDIBLE_CONTENT, r.top?.type)
    }

    /** 用户实测的情况：电视里时有时无的约 200 Hz 音，曾被判成变压器。 */
    @Test
    fun tvToneNear200HzIsNotTransformer() {
        val tone = Signals.gate(Signals.tones(rate, 45.0, listOf(Tone(200.0, 0.05)), noise = 0.0), rate, 10.0, 10.0, floor = 0.0)
        val r = infer(Wav.mix(female.samples, tone))
        assertEquals(describe(r), SourceType.AUDIBLE_CONTENT, r.top?.type)
        assertNotEquals(describe(r), SourceType.TRANSFORMER, r.candidates.getOrNull(1)?.type)
    }

    @Test
    fun pumpBehindSpeechIsStillFoundWithWarning() {
        val pump = Signals.tones(rate, 45.0, listOf(Tone(48.6, 0.05), Tone(97.2, 0.03)), noise = 0.001)
        val r = infer(Wav.mix(female.samples, pump))
        assertEquals(describe(r), SourceType.FIXED_SPEED_MOTOR, r.top?.type)
        assertTrue(describe(r), r.notes.any { it.contains("电视") })
    }

    @Test
    fun transformerBehindQuietSpeech() {
        val hum = Signals.tones(rate, 45.0, listOf(Tone(100.0, 0.05), Tone(200.0, 0.02)), noise = 0.001)
        val r = infer(Wav.mix(hum, female.samples, 0.1f))
        assertEquals(describe(r), SourceType.TRANSFORMER, r.top?.type)
    }
}
