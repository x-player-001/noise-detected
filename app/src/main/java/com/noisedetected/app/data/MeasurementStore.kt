package com.noisedetected.app.data

import android.content.Context
import com.noisedetected.core.compare.Condition
import com.noisedetected.core.dsp.Spectrum
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class MeasurementMeta(
    val id: String,
    val label: String,
    val condition: Condition?,
    val createdAt: Long,
    val durationSec: Double,
    val inputRate: Int,
    val sourceLabel: String?,
    val mainFrequencyHz: Double?,
    val conclusion: String?,
    val fileName: String,
    val truncated: Boolean,
)

data class StoredMeasurement(val meta: MeasurementMeta, val dir: File) {
    val wavFile: File get() = File(dir, meta.fileName)
}

/**
 * 测量记录存在应用私有目录 files/measurements/<id>/：
 * 录音 WAV、平均频谱（spectrum.f32，小端 float32 功率值）、meta.json。
 */
class MeasurementStore(context: Context) {
    val root: File = File(context.filesDir, "measurements").apply { mkdirs() }

    fun list(): List<StoredMeasurement> =
        root.listFiles()
            ?.mapNotNull { dir -> runCatching { StoredMeasurement(readMeta(File(dir, META)), dir) }.getOrNull() }
            ?.sortedByDescending { it.meta.createdAt }
            ?: emptyList()

    /** 把临时录音移入记录目录，并写入平均频谱和元数据。 */
    fun save(tempWav: File, meta: MeasurementMeta, spectrum: Spectrum): StoredMeasurement {
        val dir = File(root, meta.id).apply { mkdirs() }
        val wav = File(dir, meta.fileName)
        if (!tempWav.renameTo(wav)) {
            tempWav.copyTo(wav, overwrite = true)
            tempWav.delete()
        }
        writeSpectrum(File(dir, SPECTRUM), spectrum)
        File(dir, META).writeText(toJson(meta, spectrum).toString(2))
        return StoredMeasurement(meta, dir)
    }

    fun loadSpectrum(m: StoredMeasurement): Spectrum {
        val json = JSONObject(File(m.dir, META).readText())
        val bytes = File(m.dir, SPECTRUM).readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val power = DoubleArray(buf.remaining()) { buf.get(it).toDouble() }
        return Spectrum(json.getDouble("spectrumRate"), json.getInt("spectrumFftSize"), power)
    }

    fun delete(m: StoredMeasurement) {
        m.dir.deleteRecursively()
    }

    private fun writeSpectrum(file: File, spectrum: Spectrum) {
        val buf = ByteBuffer.allocate(spectrum.power.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (p in spectrum.power) buf.putFloat(p.toFloat())
        file.writeBytes(buf.array())
    }

    private fun toJson(m: MeasurementMeta, spectrum: Spectrum) = JSONObject().apply {
        put("id", m.id)
        put("label", m.label)
        put("condition", m.condition?.name ?: JSONObject.NULL)
        put("createdAt", m.createdAt)
        put("durationSec", m.durationSec)
        put("inputRate", m.inputRate)
        put("sourceLabel", m.sourceLabel ?: JSONObject.NULL)
        put("mainFrequencyHz", m.mainFrequencyHz ?: JSONObject.NULL)
        put("conclusion", m.conclusion ?: JSONObject.NULL)
        put("fileName", m.fileName)
        put("truncated", m.truncated)
        put("spectrumRate", spectrum.sampleRate)
        put("spectrumFftSize", spectrum.fftSize)
    }

    private fun readMeta(file: File): MeasurementMeta {
        val j = JSONObject(file.readText())
        fun optString(key: String) = if (j.isNull(key)) null else j.getString(key)
        return MeasurementMeta(
            id = j.getString("id"),
            label = j.getString("label"),
            condition = optString("condition")?.let { name -> Condition.entries.firstOrNull { it.name == name } },
            createdAt = j.getLong("createdAt"),
            durationSec = j.getDouble("durationSec"),
            inputRate = j.getInt("inputRate"),
            sourceLabel = optString("sourceLabel"),
            mainFrequencyHz = if (j.isNull("mainFrequencyHz")) null else j.getDouble("mainFrequencyHz"),
            conclusion = optString("conclusion"),
            fileName = j.getString("fileName"),
            truncated = j.optBoolean("truncated"),
        )
    }

    companion object {
        private const val META = "meta.json"
        private const val SPECTRUM = "spectrum.f32"
    }
}
