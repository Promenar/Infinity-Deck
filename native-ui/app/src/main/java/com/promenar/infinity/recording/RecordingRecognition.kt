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
    !item.captureRollbackPending && activeId == null && recognizingId == null && item.audioBytes > 0 &&
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
            val step = decoder.advanceSegment(segmentation, pcm)
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
    draftBatches = emptyList(),
    summaryPending = rewriteOrganized,
    summaryProgress = null,
)

internal suspend fun <T> withRecordingRecognitionWakeLock(context: Context, action: suspend () -> T): T {
    val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:recording-recognize")
    lock.acquire(30 * 60 * 1000L)
    try { return action() } finally { if (lock.isHeld) lock.release() }
}

/** 在连续接话处定位文本切点。短句另行解码只用于定位，原始内容、热词替换结果与标点直接保留。 */
internal suspend fun refineRecordingSpeakerSegments(
    audio: File,
    segments: List<RecordingSegment>,
    turns: List<RecordingSpeakerTurn>,
    decoder: RecordingDecoder,
    protectAssignments: Boolean = false,
): List<RecordingSegment> {
    val context = currentCoroutineContext()
    val buffer = ByteArray(RecordingCapturePolicy.BLOCK_SAMPLES * 2)
    return RandomAccessFile(audio, "r").use { file ->
        var firstTurn = 0
        segments.flatMap { segment ->
            while (firstTurn < turns.size && turns[firstTurn].endMs <= segment.startMs) firstTurn++
            val local = turns.asSequence().drop(firstTurn).takeWhile { it.startMs < segment.endMs }.toList()
            val spans = recordingSpeakerSpans(segment, local, minimumOverlapMs = if (protectAssignments) 1 else 350)
            check(!protectAssignments || local.filter {
                minOf(segment.endMs, it.endMs) > maxOf(segment.startMs, it.startMs)
            }.map { it.speakerId }.distinct().size <= maxOf(1, spans.size)) { "无法安全保留重叠的手工说话人归属" }
            if (segment.speakerId != null || spans.isEmpty()) return@flatMap listOf(segment)
            val hints = spans.map { span ->
                file.seek(RecordingStore.WAV_HEADER_BYTES + span.startMs * 32L)
                var remaining = (span.endMs - span.startMs) * 32L
                var partial = ""
                while (remaining > 0) {
                    context.ensureActive()
                    val count = minOf(buffer.size.toLong(), remaining).toInt()
                    file.readFully(buffer, 0, count)
                    val pcm = ShortArray(count / 2) { index ->
                        ((buffer[index * 2].toInt() and 0xff) or (buffer[index * 2 + 1].toInt() shl 8)).toShort()
                    }
                    partial = decoder.accept(pcm).text
                    remaining -= count
                }
                decoder.finish().ifBlank { partial }.also { decoder.reset() }
            }
            val text = splitRecordingSpeakerText(segment.text, hints)
            if (text == null) {
                check(!protectAssignments) { "无法安全保留手工说话人归属" }
                return@flatMap listOf(segment)
            }
            spans.zip(text) { span, part -> RecordingSegment(span.startMs, span.endMs, part) }
        }
    }
}
