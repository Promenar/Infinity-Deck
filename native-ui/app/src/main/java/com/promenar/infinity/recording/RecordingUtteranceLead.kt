package com.promenar.infinity.recording

/** 保留下一次起声之前的一小段音频。长停顿后重建流时用于保住句首。 */
internal class RecordingUtteranceLead {
    private val samples = ShortArray(HISTORY_SAMPLES)
    private var written = 0L
    private var next = 0

    fun append(pcm: ShortArray) {
        for (sample in pcm) {
            samples[next++] = sample
            if (next == samples.size) next = 0
        }
        written += pcm.size
    }

    fun replayFrom(onsetFromEnd: Int): Replay? {
        val onset = written + onsetFromEnd
        if (onset < LONG_PAUSE_SAMPLES) return null
        val start = onset - PRE_ROLL_SAMPLES
        return Replay(start, ShortArray((written - start).toInt()) { samples[((start + it) % samples.size).toInt()] })
    }

    data class Replay(val start: Long, val pcm: ShortArray)

    private companion object {
        const val LONG_PAUSE_SAMPLES = 24 * RecordingSpeechBoundary.WINDOW_SAMPLES
        const val PRE_ROLL_SAMPLES = 12 * RecordingSpeechBoundary.WINDOW_SAMPLES
        const val HISTORY_SAMPLES = PRE_ROLL_SAMPLES + 3 * RecordingSpeechBoundary.WINDOW_SAMPLES + RecordingCapturePolicy.BLOCK_SAMPLES
    }
}
