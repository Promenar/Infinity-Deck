package com.promenar.infinity.recording

internal data class RecordingSpeechWindow(
    val onset: Int? = null,
    val end: Int? = null,
) {
    companion object {
        val None = RecordingSpeechWindow()
    }
}

/** 在连续音频中定位已确认的起声和停顿，返回相对于当前 PCM 块的样本偏移。 */
internal class RecordingSpeechBoundary(private val classify: (FloatArray) -> Float) {
    private val window = FloatArray(WINDOW_SAMPLES)
    private var used = 0
    private var speech = 0
    private var silence = 0

    // 每次最多输入一个采集块。保留不足一窗的样本，下次接着判定。
    fun accept(pcm: ShortArray): RecordingSpeechWindow {
        var onset: Int? = null
        var endpoint: Int? = null
        for (index in pcm.indices) {
            window[used++] = pcm[index] / 32768f
            if (used < window.size) continue
            used = 0
            val probability = classify(window)
            if (speech < MIN_SPEECH_WINDOWS) {
                speech = if (probability >= ONSET) speech + 1 else 0
                if (speech == MIN_SPEECH_WINDOWS) onset = index + 1 - speech * WINDOW_SAMPLES
            } else {
                silence = if (probability < CONTINUATION) silence + 1 else 0
                if (silence == MIN_SILENCE_WINDOWS) {
                    endpoint = index + 1
                    speech = 0
                    silence = 0
                }
            }
        }
        return if (onset == null && endpoint == null) RecordingSpeechWindow.None else RecordingSpeechWindow(onset, endpoint)
    }

    companion object {
        const val WINDOW_SAMPLES = 512
        private const val ONSET = .5f
        private const val CONTINUATION = .35f
        private const val MIN_SPEECH_WINDOWS = 3
        private const val MIN_SILENCE_WINDOWS = 14
    }
}
