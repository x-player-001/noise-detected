package com.noisedetected.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 测试用：读取 16 位 PCM WAV（多声道取平均），可重采样到指定采样率。 */
object Wav {
    class Audio(val rate: Int, val samples: FloatArray)

    fun load(resource: String): Audio = parse(Wav::class.java.getResourceAsStream("/$resource")!!.readBytes(), resource)

    fun load(file: java.io.File): Audio = parse(file.readBytes(), file.name)

    private fun parse(bytes: ByteArray, resource: String): Audio {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var channels = 1
        var rate = 0
        var bits = 16
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    channels = buf.getShort(body + 2).toInt()
                    rate = buf.getInt(body + 4)
                    bits = buf.getShort(body + 14).toInt()
                }
                "data" -> {
                    require(bits == 16) { "只支持 16 位 PCM" }
                    val frames = minOf(size, bytes.size - body) / (2 * channels)
                    val out = FloatArray(frames) { i ->
                        var sum = 0.0
                        for (c in 0 until channels) sum += buf.getShort(body + 2 * (i * channels + c)) / 32768.0
                        (sum / channels).toFloat()
                    }
                    return Audio(rate, out)
                }
            }
            pos = body + size + (size and 1)
        }
        error("WAV 中没有 data 块: $resource")
    }

    /** 线性插值重采样（测试够用）。 */
    fun resample(audio: Audio, rate: Int): Audio {
        if (audio.rate == rate) return audio
        val n = (audio.samples.size.toLong() * rate / audio.rate).toInt()
        val ratio = audio.rate.toDouble() / rate
        return Audio(rate, FloatArray(n) { i ->
            val x = i * ratio
            val j = x.toInt().coerceAtMost(audio.samples.size - 2)
            val f = (x - j).toFloat()
            audio.samples[j] * (1 - f) + audio.samples[j + 1] * f
        })
    }

    /** 循环拼接到指定时长。 */
    fun loop(audio: Audio, seconds: Double): Audio {
        val n = (audio.rate * seconds).toInt()
        return Audio(audio.rate, FloatArray(n) { audio.samples[it % audio.samples.size] })
    }

    fun mix(a: FloatArray, b: FloatArray, gainB: Float = 1f): FloatArray =
        FloatArray(minOf(a.size, b.size)) { a[it] + gainB * b[it] }
}
