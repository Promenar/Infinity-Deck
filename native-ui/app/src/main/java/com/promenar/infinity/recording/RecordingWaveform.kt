package com.promenar.infinity.recording

import kotlin.math.sqrt

internal data class RecordingWaveformSamples(
    val levels: List<Float> = emptyList(),
    val sequence: Long = 0,
)

private const val SAMPLE_MILLIS = 100
private const val SAMPLE_COUNT = RecordingCapturePolicy.SAMPLE_RATE * SAMPLE_MILLIS / 1_000
private const val HISTORY_SIZE = 160

/** 采集线程按 100 ms 的完整 PCM 窗口取 RMS，不随识别协程的进度采样。 */
internal class RecordingWaveformSampler(
    private var history: RecordingWaveformSamples,
    private val emit: (RecordingWaveformSamples) -> Unit,
) {
    private var count = 0
    private var squares = 0.0

    fun accept(pcm: ShortArray, size: Int) {
        for (index in 0 until size) {
            val sample = pcm[index] / 32768.0
            squares += sample * sample
            if (++count == SAMPLE_COUNT) {
                val level = (sqrt(squares / count) * 8).toFloat().coerceIn(0f, 1f)
                history = RecordingWaveformSamples(history.levels.takeLast(HISTORY_SIZE - 1) + level, history.sequence + 1)
                emit(history)
                count = 0
                squares = 0.0
            }
        }
    }
}

internal data class RecordingWaveformFrame(val levels: List<Float>, val offset: Float)

/** 成对更新柱条和位移，让前一帧的历史柱连续向左移动。 */
internal class RecordingWaveformMotion {
    private var sequence: Long? = null
    private var offset = 0f
    private var previousNanos: Long? = null

    fun frame(samples: RecordingWaveformSamples, nanos: Long): RecordingWaveformFrame {
        previousNanos?.let {
            offset = (offset - (nanos - it) / (SAMPLE_MILLIS * 1_000_000f)).coerceAtLeast(0f)
        }
        sequence?.let { offset = (offset + (samples.sequence - it)).coerceIn(0f, 2f) }
        previousNanos = nanos
        sequence = samples.sequence
        return RecordingWaveformFrame(samples.levels, offset)
    }

    fun pause() {
        previousNanos = null
    }
}
