package com.noisedetected.core.record

import com.noisedetected.core.dsp.Spectrum
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * 边录边写 16 位单声道 PCM WAV，关闭时回填文件头。
 * 不做归一化：同一部手机的不同录音之间声级可以直接比较。
 */
class WavWriter(file: File, val sampleRate: Int, maxSeconds: Double = 600.0) : Closeable {
    private val raf = RandomAccessFile(file, "rw").apply {
        setLength(0)
        write(ByteArray(HEADER_BYTES))
    }
    private val buffer = ByteBuffer.allocate(16384).order(ByteOrder.LITTLE_ENDIAN)
    private val maxSamples = (sampleRate * maxSeconds).toLong()
    private var closed = false

    var samplesWritten = 0L
        private set

    /** 达到时长上限后不再写入。 */
    val truncated: Boolean get() = samplesWritten >= maxSamples

    val durationSec: Double get() = samplesWritten.toDouble() / sampleRate

    fun write(samples: FloatArray, length: Int = samples.size) {
        check(!closed)
        for (i in 0 until length) {
            if (samplesWritten >= maxSamples) break
            buffer.putShort((samples[i] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
            samplesWritten++
            if (!buffer.hasRemaining()) flush()
        }
    }

    private fun flush() {
        raf.write(buffer.array(), 0, buffer.position())
        buffer.clear()
    }

    override fun close() {
        if (closed) return
        closed = true
        flush()
        val dataBytes = (samplesWritten * 2).toInt()
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1) // PCM，单声道
            putInt(sampleRate); putInt(sampleRate * 2)
            putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(dataBytes)
        }
        raf.seek(0)
        raf.write(header.array())
        raf.close()
    }

    companion object {
        private const val HEADER_BYTES = 44
    }
}

/** 整段测量的平均功率谱（各帧细谱的算术平均），用于保存和叠加对比。 */
class LongTermAverager {
    private var sum: DoubleArray? = null
    private var sampleRate = 0.0
    private var fftSize = 0

    var frames = 0
        private set

    fun add(spectrum: Spectrum) {
        val s = sum
        if (s == null || s.size != spectrum.power.size) {
            sum = spectrum.power.copyOf()
            sampleRate = spectrum.sampleRate
            fftSize = spectrum.fftSize
            frames = 1
            return
        }
        for (i in s.indices) s[i] += spectrum.power[i]
        frames++
    }

    fun result(): Spectrum? {
        val s = sum ?: return null
        return Spectrum(sampleRate, fftSize, DoubleArray(s.size) { s[it] / frames })
    }

    fun reset() {
        sum = null
        frames = 0
    }
}
