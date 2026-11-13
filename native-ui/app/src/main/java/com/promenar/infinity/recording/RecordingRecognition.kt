package com.promenar.infinity.recording

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun recordingCanRecognize(item: RecordingSession, activeId: String?, recognizingId: String?): Boolean =
    activeId == null && recognizingId == null && item.audioBytes > 0 &&
        (item.phase == RecordingPhase.Finished || item.phase == RecordingPhase.Interrupted)

/** 只读取本机录音管线生成的 16 kHz、单声道 PCM16 WAV。提交会话前先完整解码。 */
internal suspend fun recognizeRecordingWav(
    audio: File,
    decoder: RecordingDecoder,
    progress: suspend (Float) -> Unit,
): List<RecordingSegment> {
    val segments = mutableListOf<RecordingSegment>()
    val segmentation = RecordingSegmentation(0)
    RandomAccessFile(audio, "r").use { file ->
        val header = ByteArray(44).also(file::readFully)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        require(header.copyOfRange(0, 4).decodeToString() == "RIFF" && header.copyOfRange(8, 16).decodeToString() == "WAVEfmt ")
        require(buffer.getInt(16) == 16 && buffer.getShort(20).toInt() == 1 && buffer.getShort(22).toInt() == 1)
        require(buffer.getInt(24) == RecordingCapturePolicy.SAMPLE_RATE && buffer.getShort(32).toInt() == 2 && buffer.getShort(34).toInt() == 16)
        require(header.copyOfRange(36, 40).decodeToString() == "data")
        val size = buffer.getInt(40).toLong() and 0xffffffffL
        require(size % 2 == 0L && size <= RecordingStore.MAX_AUDIO_BYTES && file.length() == size + 44)
        var processed = 0L
        val bytes = ByteArray(RecordingCapturePolicy.BLOCK_SAMPLES * 2)
        progress(0f)
        while (processed < size) {
            currentCoroutineContext().ensureActive()
            val count = minOf(bytes.size.toLong(), size - processed).toInt()
            file.readFully(bytes, 0, count)
            val pcm = ShortArray(count / 2) { index ->
                ((bytes[index * 2].toInt() and 0xff) or (bytes[index * 2 + 1].toInt() shl 8)).toShort()
            }
            val result = decoder.accept(pcm)
            val step = segmentation.accept(pcm, result.text, result.endpoint)
            step.frame.segment?.let(segments::add)
            if (step.reset) decoder.reset()
            processed += count
            progress(processed.toFloat() / size)
        }
        currentCoroutineContext().ensureActive()
        segmentation.finish(decoder.finish()).segment?.let(segments::add)
        progress(1f)
    }
    return segments
}

internal fun recordingRecognitionResult(
    current: RecordingSession,
    segments: List<RecordingSegment>,
    rewriteOrganized: Boolean,
): RecordingSession = current.copy(
    segments = segments,
    checkpointText = null,
    manualEdit = if (rewriteOrganized) false else current.manualEdit,
    organizedThrough = if (rewriteOrganized) current.organizedThrough.coerceAtMost(segments.size) else segments.size,
    reprocessThrough = if (rewriteOrganized) 0 else null,
    reprocessText = "",
)

internal suspend fun <T> withRecordingRecognitionWakeLock(context: Context, action: suspend () -> T): T {
    val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:recording-recognize")
    lock.acquire(30 * 60 * 1000L)
    try { return action() } finally { if (lock.isHeld) lock.release() }
}
