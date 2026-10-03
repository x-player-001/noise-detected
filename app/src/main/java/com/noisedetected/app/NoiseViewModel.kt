package com.noisedetected.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noisedetected.app.audio.AudioEngine
import com.noisedetected.app.data.MeasurementMeta
import com.noisedetected.app.data.MeasurementStore
import com.noisedetected.app.data.StoredMeasurement
import com.noisedetected.app.ui.DisplayRenderer
import com.noisedetected.core.analysis.AnalysisFrame
import com.noisedetected.core.analysis.DisplayFrame
import com.noisedetected.core.analysis.DisplaySink
import com.noisedetected.core.analysis.LiveAnalyzer
import com.noisedetected.core.analysis.ToneFeatures
import com.noisedetected.core.compare.Condition
import com.noisedetected.core.dsp.Spectrum
import com.noisedetected.core.inference.InferenceResult
import com.noisedetected.core.record.LongTermAverager
import com.noisedetected.core.record.WavWriter
import com.noisedetected.core.survey.LocationKind
import com.noisedetected.core.survey.MeterReading
import com.noisedetected.core.survey.PointMeasurer
import com.noisedetected.core.survey.SurveyAnalyzer
import com.noisedetected.core.survey.SurveyLocation
import com.noisedetected.core.survey.SurveyReport
import com.noisedetected.core.survey.SurveyTarget
import com.noisedetected.core.survey.TargetMeter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 识别页的低频状态（每秒约一次）；频谱和瀑布图走 [DisplayRenderer]，不经过这里。 */
data class LiveState(
    val running: Boolean = false,
    val elapsedSec: Int = 0,
    val sourceLabel: String? = null,
    val showPeakHold: Boolean = false,
    val mainFrequencyHz: Double? = null,
    val inference: InferenceResult? = null,
    val error: String? = null,
    /** 刚结束的测量可以保存。 */
    val canSave: Boolean = false,
    val message: String? = null,
)

/** 叠加对比中的一段测量。 */
data class CompareItem(val record: StoredMeasurement, val spectrum: Spectrum)

/** 实时寻声的状态，约每 0.1 s 更新一次。 */
data class FinderState(
    val running: Boolean = false,
    val reading: MeterReading? = null,
    /** 最近 [NoiseViewModel.FINDER_HISTORY] 个读数（dB），用于走势曲线，最旧在前。 */
    val history: FloatArray = FloatArray(0),
)

data class SurveyState(
    val target: SurveyTarget? = null,
    val locations: List<SurveyLocation> = emptyList(),
    val measuringId: Long? = null,
    val progress: Float = 0f,
    val report: SurveyReport? = null,
)

class NoiseViewModel(app: Application) : AndroidViewModel(app) {
    private enum class Mode { IDLE, IDENTIFY, POINT, FIND }

    private val engine = AudioEngine(app)
    val renderer = DisplayRenderer()
    private val analyzer = LiveAnalyzer(engine.sampleRate).apply {
        displaySink = DisplaySink { frame ->
            renderer.onDisplay(frame)
            if (mode == Mode.FIND) onFinderFrame(frame)
        }
    }

    private val _live = MutableStateFlow(LiveState())
    val live: StateFlow<LiveState> = _live.asStateFlow()

    private val _survey = MutableStateFlow(SurveyState())
    val survey: StateFlow<SurveyState> = _survey.asStateFlow()

    private val _finder = MutableStateFlow(FinderState())
    val finder: StateFlow<FinderState> = _finder.asStateFlow()

    private val _records = MutableStateFlow<List<StoredMeasurement>>(emptyList())
    val records: StateFlow<List<StoredMeasurement>> = _records.asStateFlow()

    /** 正在叠加对比的测量；null 表示停留在记录列表。 */
    private val _compare = MutableStateFlow<List<CompareItem>?>(null)
    val compare: StateFlow<List<CompareItem>?> = _compare.asStateFlow()

    private val store = MeasurementStore(app)
    private val tempWav = File(app.cacheDir, "current.wav")
    private val averager = LongTermAverager()
    @Volatile private var recorder: WavWriter? = null
    @Volatile private var pending: PendingMeasurement? = null
    @Volatile private var lastInference: InferenceResult? = null

    private class PendingMeasurement(
        val durationSec: Double,
        val truncated: Boolean,
        val spectrum: Spectrum,
        val inference: InferenceResult?,
        val sourceLabel: String?,
    )

    @Volatile private var job: Job? = null
    @Volatile private var mode = Mode.IDLE
    @Volatile private var lastTone: ToneFeatures? = null
    private var measurer: PointMeasurer? = null
    // 寻声：只在采集线程上读写
    private var meter: TargetMeter? = null
    private val history = FloatArray(FINDER_HISTORY)
    private var historyCount = 0
    private var finderFrames = 0
    @Volatile private var resetMaxRequested = false
    private var nextLocationId = 1L

    // ---- 识别 ----

    init {
        refreshRecords()
    }

    fun startIdentify() = restart {
        renderer.clear()
        pending = null
        lastInference = null
        averager.reset()
        recorder = WavWriter(tempWav, engine.sampleRate, MAX_RECORD_SEC)
        mode = Mode.IDENTIFY
        _finder.update { it.copy(running = false) }
        _live.update { LiveState(running = true, showPeakHold = it.showPeakHold) }
    }

    fun stop() {
        job?.cancel()
        job = null
        mode = Mode.IDLE
        _live.update { it.copy(running = false) }
        _survey.update { it.copy(measuringId = null, progress = 0f) }
        _finder.update { it.copy(running = false) }
    }

    fun togglePeakHold() {
        renderer.resetHold()
        _live.update { it.copy(showPeakHold = !it.showPeakHold) }
    }

    // ---- 保存与对比 ----

    fun saveMeasurement(label: String, condition: Condition?) {
        val p = pending ?: return
        pending = null
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(now))
            val name = label.ifBlank { condition?.label ?: "测量" }
            val meta = MeasurementMeta(
                id = "$stamp-${now % 1000}",
                label = name,
                condition = condition,
                createdAt = now,
                durationSec = p.durationSec,
                inputRate = engine.sampleRate,
                sourceLabel = p.sourceLabel,
                mainFrequencyHz = p.inference?.mainFrequencyHz,
                conclusion = p.inference?.top?.title,
                fileName = "${stamp}_${safeFileName(name)}.wav",
                truncated = p.truncated,
            )
            val message = try {
                withContext(Dispatchers.IO) { store.save(tempWav, meta, p.spectrum) }
                "已保存「$name」，可在「记录」页对比和分享"
            } catch (e: Exception) {
                Log.e(TAG, "保存失败", e)
                "保存失败：$e"
            }
            _live.update { it.copy(canSave = false, message = message) }
            refreshRecords()
        }
    }

    fun dismissMessage() = _live.update { it.copy(message = null) }

    fun deleteRecords(ids: Set<String>) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { _records.value.filter { it.meta.id in ids }.forEach { store.delete(it) } }
            refreshRecords()
        }
    }

    fun openCompare(ids: List<String>) {
        viewModelScope.launch {
            val byId = _records.value.associateBy { it.meta.id }
            _compare.value = withContext(Dispatchers.IO) {
                ids.mapNotNull { byId[it] }.mapNotNull { r -> runCatching { CompareItem(r, store.loadSpectrum(r)) }.getOrNull() }
            }
        }
    }

    fun closeCompare() {
        _compare.value = null
    }

    private fun refreshRecords() {
        viewModelScope.launch { _records.value = withContext(Dispatchers.IO) { store.list() } }
    }

    /** 采集结束后（在采集协程内）关闭录音文件，决定能否保存。 */
    private fun finishRecording() {
        val r = recorder ?: return
        recorder = null
        r.close()
        val spectrum = averager.result()
        pending = if (r.durationSec >= MIN_SAVE_SEC && spectrum != null) {
            PendingMeasurement(r.durationSec, r.truncated, spectrum, lastInference, _live.value.sourceLabel)
        } else {
            null
        }
        _live.update { it.copy(canSave = pending != null) }
    }

    // ---- 巡测 ----

    /** 用识别页最近得到的主音调作为巡测目标。 */
    fun useCurrentToneAsTarget(): Boolean {
        val tone = lastTone ?: return false
        val harmonics = tone.harmonics.filter { it <= 4 }.ifEmpty { listOf(1) }
        setTarget(SurveyTarget(tone.f0Hz, harmonics))
        return true
    }

    fun setTarget(target: SurveyTarget) {
        // 目标变了，旧测点不可比，清空
        _survey.update { SurveyState(target = target, locations = it.locations.map { l -> l.copy(points = emptyList()) }) }
        // 寻声中换了目标：用新目标重新开始
        if (_finder.value.running) startFinder()
    }

    fun addLocation(name: String, kind: LocationKind) {
        val location = SurveyLocation(nextLocationId++, name.ifBlank { kind.label }, kind)
        _survey.update { it.copy(locations = it.locations + location) }
    }

    fun removeLocation(id: Long) {
        _survey.update { s ->
            val locations = s.locations.filter { it.id != id }
            s.copy(locations = locations, report = SurveyAnalyzer.report(locations))
        }
    }

    fun removeLastPoint(id: Long) {
        _survey.update { s ->
            val locations = s.locations.map { if (it.id == id) it.copy(points = it.points.dropLast(1)) else it }
            s.copy(locations = locations, report = SurveyAnalyzer.report(locations))
        }
    }

    fun measurePoint(locationId: Long) {
        val target = _survey.value.target ?: return
        restart {
            measurer = PointMeasurer(target)
            mode = Mode.POINT
            _finder.update { it.copy(running = false) }
            _survey.update { it.copy(measuringId = locationId, progress = 0f) }
            _live.update { it.copy(running = true, elapsedSec = 0) }
        }
    }

    // ---- 实时寻声 ----

    /** 从识别页一键寻找：锁定识别出的主频（与当前目标相同则保留已测的点）并开始寻声。 */
    fun findIdentifiedSource(): Boolean {
        val tone = lastTone ?: return false
        val current = _survey.value.target
        if (current == null || kotlin.math.abs(current.f0Hz - tone.f0Hz) > 0.5) {
            if (!useCurrentToneAsTarget()) return false
        }
        startFinder()
        return true
    }

    fun startFinder() {
        val target = _survey.value.target ?: return
        restart {
            meter = TargetMeter(target)
            historyCount = 0
            finderFrames = 0
            resetMaxRequested = false
            mode = Mode.FIND
            _survey.update { it.copy(measuringId = null, progress = 0f) }
            _finder.value = FinderState(running = true)
            _live.update { it.copy(running = false) }
        }
    }

    fun resetFinderMax() {
        resetMaxRequested = true
    }

    /** 采集线程上每个显示帧（50 ms）调用；每两帧发布一次，界面约 10 Hz 刷新。 */
    private fun onFinderFrame(frame: DisplayFrame) {
        val m = meter ?: return
        if (resetMaxRequested) {
            resetMaxRequested = false
            m.resetMax()
        }
        val reading = m.add(frame.fast, frame.timeSec)
        if (++finderFrames % 2 != 0) return
        if (historyCount < FINDER_HISTORY) {
            history[historyCount++] = reading.levelDb.toFloat()
        } else {
            System.arraycopy(history, 1, history, 0, FINDER_HISTORY - 1)
            history[FINDER_HISTORY - 1] = reading.levelDb.toFloat()
        }
        _finder.value = FinderState(running = true, reading = reading, history = history.copyOf(historyCount))
    }

    // ---- 采集与分析 ----

    /** 等上一轮采集完全结束（分析器不再被音频线程使用）后再重置并重新开始。 */
    private fun restart(setup: () -> Unit) {
        val previous = job
        mode = Mode.IDLE
        job = viewModelScope.launch {
            previous?.cancelAndJoin()
            analyzer.reset()
            setup()
            try {
                engine.run(
                    onStart = { source -> _live.update { it.copy(sourceLabel = source.label, error = null) } },
                    onAudio = { buffer, n -> onAudio(buffer, n) },
                )
            } catch (e: CancellationException) {
                Log.d(TAG, "采集结束", e)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "采集失败", e)
                _live.update { it.copy(running = false, error = "录音失败：$e") }
                _finder.update { it.copy(running = false) }
                mode = Mode.IDLE
            } finally {
                finishRecording()
            }
        }
    }

    private fun onAudio(buffer: FloatArray, n: Int) {
        val identifying = mode == Mode.IDENTIFY
        if (identifying) recorder?.write(buffer, n)
        val frames = analyzer.process(buffer, n)
        if (frames.isEmpty()) return
        for (frame in frames) {
            if (identifying) frame.fineRaw?.let { averager.add(it) }
            if (mode == Mode.POINT) onPointFrame(frame)
        }
        val last = frames.last()
        if (identifying) {
            last.features?.tone?.let { lastTone = it }
            if (last.inference.candidates.isNotEmpty()) lastInference = last.inference
        }
        // 内容不变时 StateFlow 不会发出，界面不重组
        _live.update {
            it.copy(
                elapsedSec = last.timeSec.toInt(),
                mainFrequencyHz = if (identifying) last.inference.mainFrequencyHz else it.mainFrequencyHz,
                inference = if (identifying) last.inference else it.inference,
            )
        }
    }

    private fun onPointFrame(frame: AnalysisFrame) {
        val m = measurer ?: return
        frame.fineRaw?.let { m.add(it) }
        val locationId = _survey.value.measuringId ?: return
        _survey.update { it.copy(progress = (frame.timeSec / POINT_SECONDS).toFloat().coerceIn(0f, 1f)) }
        if (frame.timeSec < POINT_SECONDS) return

        val result = m.result()
        measurer = null
        mode = Mode.IDLE
        job?.cancel()
        job = null
        _live.update { it.copy(running = false) }
        _survey.update { s ->
            val locations = s.locations.map { l ->
                if (l.id == locationId && result != null) l.copy(points = l.points + result) else l
            }
            s.copy(locations = locations, measuringId = null, progress = 0f, report = SurveyAnalyzer.report(locations))
        }
    }

    override fun onCleared() {
        stop()
    }

    companion object {
        private const val TAG = "NoiseViewModel"

        /** 少于这个时长的测量不提供保存（平均谱不稳定）。 */
        const val MIN_SAVE_SEC = 10.0

        /** 录音时长上限，约 58 MB。超过后继续分析，但不再写入录音。 */
        const val MAX_RECORD_SEC = 600.0

        private val UNSAFE_FILE_CHARS = Regex("""[\\/:*?"<>|\s]+""")

        private fun safeFileName(name: String) = name.replace(UNSAFE_FILE_CHARS, "_").take(40)

        /** 寻声走势曲线的长度：30 s × 10 Hz。 */
        const val FINDER_HISTORY = 300

        /** 每个测点：约 4 s 填满细谱缓冲 + 6 s 平均。 */
        const val POINT_SECONDS = 10.0
    }
}
