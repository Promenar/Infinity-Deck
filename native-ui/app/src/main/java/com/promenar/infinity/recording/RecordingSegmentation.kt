package com.promenar.infinity.recording

internal object RecordingCapturePolicy {
    const val SAMPLE_RATE = 16_000
    const val BLOCK_SAMPLES = 640
    const val RECORDER_BUFFER_SECONDS = 2
    const val TAIL_SILENCE_SECONDS = 1
    const val FORCE_SEGMENT_MS = 60_000L
    const val CHECKPOINT_SECONDS = 5
    const val QUIET_SECONDS = 2
    const val SPEECH_ENERGY = 0.00005
}

internal enum class CaptureCommand { Continue, Pause, Finish, Discard }

internal data class CaptureFrame(
    val bytes: Long,
    val partial: String,
    val provisional: RecordingSegment?,
    val segment: RecordingSegment?,
    val quiet: Boolean,
    val checkpoint: Boolean,
)

internal data class RecordingCaptureStep(val frame: CaptureFrame, val reset: Boolean)

internal class RecordingSegmentation(private val initialBytes: Long) {
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
        decodedMs: Long? = null,
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
        val text = normalizeRecordingText(rawText)
        val endMs = if (end && decodedMs != null) {
            (initialBytes / 32 + decodedMs).coerceIn(segmentStart, bytes / 32)
        } else bytes / 32
        val segment = if (end && text.isNotEmpty()) segment(text, endMs) else null
        if (end) {
            segmentStart = endMs
            speechAt = endMs.takeIf { lastVoice > endMs * 32 }
        }
        val checkpoint = segment != null || bytes - lastCheckpoint >=
            RecordingCapturePolicy.SAMPLE_RATE * 2L * RecordingCapturePolicy.CHECKPOINT_SECONDS
        val provisional = if (checkpoint && !end && text.isNotEmpty()) segment(text) else null
        if (checkpoint) lastCheckpoint = bytes
        return RecordingCaptureStep(CaptureFrame(
            bytes = bytes, partial = if (end) "" else text, provisional = provisional, segment = segment,
            quiet = bytes - lastVoice >= RecordingCapturePolicy.SAMPLE_RATE * 2L * RecordingCapturePolicy.QUIET_SECONDS,
            checkpoint = checkpoint,
        ), reset = end)
    }

    fun finish(rawText: String): CaptureFrame {
        val tail = normalizeRecordingText(rawText).takeIf(String::isNotEmpty)?.let(::segment)
        return CaptureFrame(bytes, "", null, tail, quiet = true, checkpoint = true)
    }

    private fun segment(text: String, endMs: Long = bytes / 32): RecordingSegment {
        // 能量检测读取的是落盘进度，起声点可能位于本段尚未解码的尾部。
        val start = speechAt?.takeIf { it < endMs } ?: segmentStart
        return RecordingSegment(start, endMs, text)
    }
}
