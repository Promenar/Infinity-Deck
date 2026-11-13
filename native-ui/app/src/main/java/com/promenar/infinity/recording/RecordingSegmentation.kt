package com.promenar.infinity.recording

import kotlin.math.sqrt

internal object RecordingCapturePolicy {
    const val SAMPLE_RATE = 16_000
    const val BLOCK_SAMPLES = 640
    const val RECORDER_BUFFER_SECONDS = 2
    const val TAIL_SILENCE_SECONDS = 1
    const val ENDPOINT_EMPTY_SILENCE_SECONDS = 2.4f
    const val ENDPOINT_TEXT_SILENCE_SECONDS = 2.0f
    const val ENDPOINT_LONG_SILENCE_SECONDS = 0.6f
    const val ENDPOINT_LONG_UTTERANCE_SECONDS = 30f
    const val ENDPOINT_NO_MIN_UTTERANCE_SECONDS = 0f
    const val FORCE_SEGMENT_MS = 60_000L
    const val CHECKPOINT_SECONDS = 5
    const val QUIET_SECONDS = 4
    const val SPEECH_ENERGY = 0.00005
}

internal enum class CaptureCommand { Continue, Pause, Finish }

internal data class CaptureFrame(
    val bytes: Long,
    val partial: String,
    val provisional: RecordingSegment?,
    val segment: RecordingSegment?,
    val quiet: Boolean,
    val checkpoint: Boolean,
    val level: Float,
)

internal data class RecordingCaptureStep(val frame: CaptureFrame, val reset: Boolean)

internal class RecordingSegmentation(initialBytes: Long) {
    var bytes = initialBytes
        private set
    private var segmentStart = initialBytes / 32
    private var lastCheckpoint = initialBytes
    private var lastVoice = initialBytes
    private var speechAt: Long? = null

    fun accept(
        pcm: ShortArray,
        rawText: String,
        endpoint: Boolean,
        finalizeSegment: (() -> String)? = null,
    ): RecordingCaptureStep {
        var energy = 0.0
        for (sample in pcm) {
            val normalized = sample / 32768f
            energy += normalized * normalized
        }
        bytes += pcm.size * 2L
        if (energy / pcm.size >= RecordingCapturePolicy.SPEECH_ENERGY) {
            if (speechAt == null) speechAt = (bytes - pcm.size * 2L) / 32
            lastVoice = bytes
        }
        val end = endpoint || bytes / 32 - segmentStart >= RecordingCapturePolicy.FORCE_SEGMENT_MS
        val finalText = if (end) finalizeSegment?.invoke()?.takeIf(String::isNotBlank) else null
        val text = normalizeRecordingText(finalText ?: rawText)
        val segment = if (end && text.isNotEmpty()) segment(text) else null
        if (end) {
            segmentStart = bytes / 32
            speechAt = null
        }
        val checkpoint = segment != null || bytes - lastCheckpoint >=
            RecordingCapturePolicy.SAMPLE_RATE * 2L * RecordingCapturePolicy.CHECKPOINT_SECONDS
        val provisional = if (checkpoint && !end && text.isNotEmpty()) segment(text) else null
        if (checkpoint) lastCheckpoint = bytes
        return RecordingCaptureStep(CaptureFrame(
            bytes = bytes, partial = if (end) "" else text, provisional = provisional, segment = segment,
            quiet = bytes - lastVoice >= RecordingCapturePolicy.SAMPLE_RATE * 2L * RecordingCapturePolicy.QUIET_SECONDS,
            checkpoint = checkpoint,
            level = (sqrt(energy / pcm.size).toFloat() * 8).coerceIn(0f, 1f),
        ), reset = end)
    }

    fun finish(rawText: String): CaptureFrame {
        val tail = normalizeRecordingText(rawText).takeIf(String::isNotEmpty)?.let(::segment)
        return CaptureFrame(bytes, "", null, tail, quiet = true, checkpoint = true, level = 0f)
    }

    private fun segment(text: String) =
        RecordingSegment((speechAt ?: segmentStart).coerceAtLeast(segmentStart), bytes / 32, text)
}
