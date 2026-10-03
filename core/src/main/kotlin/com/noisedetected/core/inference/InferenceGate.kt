package com.noisedetected.core.inference

/**
 * 结论的发布闸门：观测时间不够、或首位结论还在变时，不把结论交给界面，避免误导。
 *
 * 电视、说话、音乐的中高频起伏要十几秒才能统计出来，在那之前其中的音高常被当成设备频率
 * （实测电视里时有时无的 200 Hz 音，前几秒会被判成变压器）。所以：
 * - 观测满 [showAfterSec] 秒之前只显示"分析中"；
 * - 首位结论连续保持 [stableSec] 秒才发布；之后首位变了，也要新首位稳定同样时长才切换，期间保持原结论。
 * 「没有收到声音信号」不是声源判断，立即放行。
 */
class InferenceGate(
    private val showAfterSec: Double = SHOW_AFTER_SEC,
    private val stableSec: Double = STABLE_SEC,
) {
    private var leader: SourceType? = null
    private var leaderSince = 0.0
    private var shown: InferenceResult? = null

    fun reset() {
        leader = null
        leaderSince = 0.0
        shown = null
    }

    fun accept(r: InferenceResult): InferenceResult {
        val top = r.top ?: return shown ?: r
        // 麦克风没有输入要立即提示，让用户检查权限或占用
        if (top.type == SourceType.NO_SIGNAL) return r
        if (top.type != leader) {
            leader = top.type
            leaderSince = r.observedSec
        }
        val stable = r.observedSec - leaderSince >= stableSec
        val current = shown
        when {
            r.observedSec >= showAfterSec && stable -> shown = r
            // 首位没变：更新细节（置信度、说明、频率）
            current != null && current.top?.type == top.type -> shown = r
        }
        return shown ?: pending(r.observedSec)
    }

    private fun pending(observedSec: Double) = InferenceResult(
        candidates = emptyList(),
        notes = listOf(PENDING_NOTE),
        ready = false,
        observedSec = observedSec,
        mainFrequencyHz = null,
    )

    companion object {
        const val SHOW_AFTER_SEC = 15.0
        const val STABLE_SEC = 5.0

        /** 从开始测量到最早可能出结论的大致时间（含填满 4 s 分析窗），供界面显示进度。 */
        const val FIRST_RESULT_SEC = 20.0

        const val PENDING_NOTE = "正在排除电视、说话、音乐等干扰，约 20 秒后给出结论。请保持安静、手机放稳。"
    }
}
