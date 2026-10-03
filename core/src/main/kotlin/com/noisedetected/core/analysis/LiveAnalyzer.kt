package com.noisedetected.core.analysis

import com.noisedetected.core.dsp.DecimationChain
import com.noisedetected.core.dsp.RingBuffer
import com.noisedetected.core.dsp.Spectrum
import com.noisedetected.core.dsp.SpectrumAnalyzer
import com.noisedetected.core.dsp.toDb
import com.noisedetected.core.inference.InferenceGate
import com.noisedetected.core.inference.InferenceResult
import com.noisedetected.core.inference.SourceInference
import kotlin.math.max
import kotlin.math.roundToInt

data class AnalyzerConfig(
    /** 曲线谱长度：2 kHz 下约 0.5 s、2 Hz 分辨率，用于实时频谱曲线，跟得上变化。 */
    val curveSize: Int = 1024,
    /** 快谱长度：2 kHz 下约 1 s、1 Hz 分辨率，用于瀑布图。 */
    val fastSize: Int = 2048,
    /** 细谱长度：2 kHz 下约 4 s、0.24 Hz 分辨率，用于峰值和推断。 */
    val fineSize: Int = 8192,
    /** 显示刷新间隔：只算 FFT，开销小。 */
    val displayHopSec: Double = 0.05,
    /** 分析间隔：峰值、谐波、跟踪和推断，取整为显示间隔的整数倍。 */
    val hopSec: Double = 0.25,
    val minHz: Double = 12.0,
    val maxHz: Double = 500.0,
    val trackWindowSec: Double = 60.0,
)

/** 每 hopSec 产生一帧分析结果。 */
class AnalysisFrame(
    val timeSec: Double,
    /** 当前这一帧的细谱（独立数组）；采集满 fineSize 之前为 null。 */
    val fineRaw: Spectrum?,
    val peaks: List<Peak>,
    val harmonics: HarmonicSet?,
    val features: SoundFeatures?,
    val inference: InferenceResult,
)

/**
 * 每 displayHopSec 一次的显示数据。显示不用 4 s 窗的细谱：变化要 4 s 才完全反映出来，会像慢动作。
 * 谱引用分析器内部复用的数组，只在回调期间有效。
 */
class DisplayFrame(
    val timeSec: Double,
    /** 0.5 s 窗，用于频谱曲线。 */
    val curve: Spectrum,
    /** 1 s 窗，用于瀑布图（分辨率高，相近的线能分开）。 */
    val fast: Spectrum,
)

fun interface DisplaySink {
    /** 在采集线程上调用，应尽快返回。 */
    fun onDisplay(frame: DisplayFrame)
}

/**
 * 实时分析流水线：降采样 → 环形缓冲 → 快/细两种分辨率的谱 → 峰值与谐波 → 频率跟踪 → 推断。
 * 多分辨率：低频需要长窗才能分开相近频率，瀑布图需要短窗才跟得上变化。
 * 显示与分析分频：显示每 50 ms 刷新一次，耗时的峰值检测和推断每 0.25 s 一次。
 */
class LiveAnalyzer(private val inputRate: Int, val config: AnalyzerConfig = AnalyzerConfig()) {
    private val decimator = DecimationChain.forInputRate(inputRate)
    val sampleRate: Double = decimator.outputRate

    /** 设置后每个显示节拍回调一次；不设置时只做分析（测试、离线分析）。 */
    @Volatile var displaySink: DisplaySink? = null

    // 原始采样率下的短窗谱（约 40 ms），只用来看中高频：判断是否混有人声、音乐等日常声音
    private val rawSize = Integer.highestOneBit((inputRate * 0.05).toInt().coerceAtLeast(64))
    private val rawRing = RingBuffer(rawSize)
    private val rawAnalyzer = SpectrumAnalyzer(inputRate.toDouble(), rawSize)
    private val rawScratch = DoubleArray(rawSize)
    private val midTopHz = minOf(3500.0, 0.45 * inputRate)

    private val ring = RingBuffer(config.fineSize)
    private val curveAnalyzer = SpectrumAnalyzer(sampleRate, config.curveSize)
    private val fastAnalyzer = SpectrumAnalyzer(sampleRate, config.fastSize)
    private val fineAnalyzer = SpectrumAnalyzer(sampleRate, config.fineSize)
    private val scratch = DoubleArray(config.fineSize)
    private val curvePower = DoubleArray(config.curveSize / 2 + 1)
    private val fastPower = DoubleArray(config.fastSize / 2 + 1)
    private val finePower = DoubleArray(config.fineSize / 2 + 1)
    private val curveSpectrum = Spectrum(sampleRate, config.curveSize, curvePower)
    private val fastSpectrum = Spectrum(sampleRate, config.fastSize, fastPower)

    private val displayHopSamples = max(1, (sampleRate * config.displayHopSec).roundToInt())
    private val analysisEvery = max(1, (config.hopSec / config.displayHopSec).roundToInt())
    private var sinceTick = 0
    private var ticks = 0L
    private val tracker = ToneTracker(config.trackWindowSec)
    private val gate = InferenceGate()
    private val pending = ArrayList<AnalysisFrame>()

    fun process(input: FloatArray, length: Int = input.size): List<AnalysisFrame> {
        pending.clear()
        for (i in 0 until length) rawRing.push(input[i].toDouble())
        decimator.process(input, length) { push(it) }
        return pending.toList()
    }

    fun reset() {
        decimator.reset()
        ring.clear()
        rawRing.clear()
        tracker.clear()
        gate.reset()
        sinceTick = 0
        ticks = 0
    }

    private fun push(sample: Double) {
        ring.push(sample)
        sinceTick++
        if (sinceTick >= displayHopSamples && ring.count >= config.fastSize) {
            sinceTick = 0
            tick()
        }
    }

    private fun tick() {
        val time = ring.count / sampleRate
        val analysisDue = ++ticks % analysisEvery == 0L
        val sink = displaySink
        if (sink == null && !analysisDue) return

        if (sink != null) {
            ring.latest(config.fastSize, scratch)
            fastAnalyzer.computeInto(scratch, 0, fastPower)
            // 最新的 curveSize 个样本在 scratch 末尾
            curveAnalyzer.computeInto(scratch, config.fastSize - config.curveSize, curvePower)
            sink.onDisplay(DisplayFrame(time, curveSpectrum, fastSpectrum))
        }
        if (!analysisDue) return
        val hasFine = ring.count >= config.fineSize
        if (hasFine) {
            ring.latest(config.fineSize, scratch)
            fineAnalyzer.computeInto(scratch, 0, finePower)
        }
        pending += analyze(time, hasFine)
    }

    private fun analyze(time: Double, hasFine: Boolean): AnalysisFrame {
        if (!hasFine) return AnalysisFrame(time, null, emptyList(), null, null, gate.accept(SourceInference.infer(null)))
        val fineRaw = Spectrum(sampleRate, config.fineSize, finePower.copyOf())

        val peaks = PeakDetector.find(fineRaw, config.minHz, config.maxHz)
        val harmonics = HarmonicAnalyzer.fundamental(peaks, config.minHz, fineRaw.binHz)
        var midDb = Double.NaN
        var lowDb = Double.NaN
        if (rawRing.count >= rawSize) {
            rawRing.latest(rawSize, rawScratch)
            val raw = rawAnalyzer.compute(rawScratch)
            midDb = toDb(raw.bandPower(300.0, midTopHz))
            lowDb = toDb(raw.bandPower(20.0, 200.0))
        }
        tracker.add(
            FrameObservation(
                timeSec = time,
                f0Hz = harmonics?.f0Hz,
                harmonics = harmonics?.harmonicNumbers ?: emptyList(),
                prominenceDb = harmonics?.strongest?.prominenceDb ?: 0.0,
                bandLevelDb = toDb(fineRaw.bandPower(20.0, 200.0)),
                midLevelDb = midDb,
                lowLevelDb = lowDb,
            ),
        )
        val features = tracker.features()
        return AnalysisFrame(
            timeSec = time,
            fineRaw = fineRaw,
            peaks = peaks,
            harmonics = harmonics,
            features = features,
            inference = gate.accept(SourceInference.infer(features)),
        )
    }
}

/** 离线分析整段信号，返回最后一帧。用于测试和导入录音。 */
fun analyzeSignal(samples: FloatArray, inputRate: Int, config: AnalyzerConfig = AnalyzerConfig()): AnalysisFrame? {
    val analyzer = LiveAnalyzer(inputRate, config)
    val chunk = max(1, inputRate / 10)
    val buffer = FloatArray(chunk)
    var last: AnalysisFrame? = null
    var offset = 0
    while (offset < samples.size) {
        val n = minOf(chunk, samples.size - offset)
        System.arraycopy(samples, offset, buffer, 0, n)
        analyzer.process(buffer, n).lastOrNull()?.let { last = it }
        offset += n
    }
    return last
}
