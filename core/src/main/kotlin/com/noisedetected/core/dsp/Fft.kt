package com.noisedetected.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 原位基 2 复数 FFT，长度必须是 2 的幂。 */
class Fft(val size: Int) {
    private val cosTable: DoubleArray
    private val sinTable: DoubleArray
    private val bitReverse: IntArray

    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT 长度必须是 2 的幂: $size" }
        cosTable = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
        sinTable = DoubleArray(size / 2) { sin(2.0 * PI * it / size) }
        val bits = Integer.numberOfTrailingZeros(size)
        bitReverse = IntArray(size) { Integer.reverse(it) ushr (32 - bits) }
    }

    fun transform(re: DoubleArray, im: DoubleArray) {
        require(re.size == size && im.size == size)
        for (i in 0 until size) {
            val j = bitReverse[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var start = 0
            while (start < size) {
                var k = 0
                for (j in 0 until half) {
                    val wr = cosTable[k]
                    val wi = -sinTable[k]
                    val a = start + j
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    k += step
                }
                start += len
            }
            len = len shl 1
        }
    }
}
