package com.noisedetected.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 麦克风采集。优先用 UNPROCESSED 音源：它绕过降噪、自动增益和高通滤波，低频数据最可靠。
 * 不支持时退到 VOICE_RECOGNITION（多数机型处理较少），再退到 MIC。
 */
class AudioEngine(private val context: Context) {
    val sampleRate = 48000

    data class Source(val id: Int, val label: String)

    private fun candidateSources(): List<Source> {
        val am = context.getSystemService(AudioManager::class.java)
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return buildList {
            if (unprocessed) add(Source(MediaRecorder.AudioSource.UNPROCESSED, "未处理音源"))
            add(Source(MediaRecorder.AudioSource.VOICE_RECOGNITION, "语音识别音源（可能滤掉部分低频）"))
            add(Source(MediaRecorder.AudioSource.MIC, "普通麦克风（系统处理较多）"))
        }
    }

    /** 持续采集直到协程取消。onStart 报告实际使用的音源，onAudio 在后台线程回调。 */
    @SuppressLint("MissingPermission")
    suspend fun run(onStart: (Source) -> Unit, onAudio: (FloatArray, Int) -> Unit) = withContext(Dispatchers.Default) {
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val bufferBytes = maxOf(minBuffer, sampleRate * 4 / 2)
        var record: AudioRecord? = null
        var source: Source? = null
        for (candidate in candidateSources()) {
            val r = AudioRecord(candidate.id, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, bufferBytes)
            if (r.state == AudioRecord.STATE_INITIALIZED) {
                record = r
                source = candidate
                break
            }
            r.release()
        }
        checkNotNull(record) { "无法打开麦克风" }
        onStart(source!!)

        val buffer = FloatArray(sampleRate / 20)
        record.startRecording()
        try {
            while (coroutineContext.isActive) {
                val n = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (n > 0) onAudio(buffer, n) else if (n < 0) error("录音读取失败（错误码 $n）")
            }
        } finally {
            record.stop()
            record.release()
        }
    }
}
