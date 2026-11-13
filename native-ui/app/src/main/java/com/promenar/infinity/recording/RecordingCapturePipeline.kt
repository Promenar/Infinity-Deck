package com.promenar.infinity.recording

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

internal interface RecordingAudioSource {
    fun start()
    fun read(buffer: ShortArray): Int
    fun stop()
}

internal data class RecordingRecognition(val text: String, val endpoint: Boolean)

internal interface RecordingDecoder : AutoCloseable {
    override fun close() = Unit
    fun accept(pcm: ShortArray): RecordingRecognition
    fun reset()
    fun finish(): String
}

internal fun RecordingDecoder.advanceSegment(state: RecordingSegmentation, pcm: ShortArray): RecordingCaptureStep {
    val result = accept(pcm)
    return state.accept(pcm, result.text, result.endpoint, ::finish)
}

internal data class RecordingBacklog(val maxQueuedMs: Long, val durationMs: Long)

/** 采集线程顺序落盘，识别协程按已写入的偏移读取。通知合并，积压只占用磁盘。 */
internal suspend fun recordPcmAudio(
    source: RecordingAudioSource,
    decoder: RecordingDecoder,
    file: RandomAccessFile,
    initialBytes: Long,
    maxBytes: Long,
    command: () -> CaptureCommand,
    onReaderStart: () -> Unit = {},
    onBacklog: (RecordingBacklog) -> Unit = {},
    onFrame: suspend (CaptureFrame) -> Unit,
): CaptureCommand {
    val state = RecordingSegmentation(initialBytes)
    val changes = Channel<Unit>(Channel.CONFLATED)
    val stopping = AtomicBoolean(false)
    val stopped = AtomicBoolean(false)
    val failure = AtomicReference<Throwable?>(null)
    val written = AtomicLong(initialBytes)
    val consumed = AtomicLong(initialBytes)
    val maxBacklog = AtomicLong(0)
    val channel = file.channel
    val initialLength = RecordingStore.WAV_HEADER_BYTES + initialBytes
    val actualLength = file.length()
    if (actualLength != initialLength && !(actualLength == 0L && initialBytes == 0L)) {
        throw IOException("录音文件长度与已有记录不一致")
    }
    updateRecordingWavHeader(file, initialBytes)
    val checkpointLock = Any()
    var durable = initialBytes

    fun checkpoint(bytes: Long) = synchronized(checkpointLock) {
        if (bytes > durable) {
            updateRecordingWavHeader(file, bytes)
            file.fd.sync()
            durable = bytes
        }
    }

    fun stop() {
        if (stopped.compareAndSet(false, true)) source.stop()
    }

    val reader = thread(start = false, name = "recording-audio-reader") {
        try {
            onReaderStart()
            val samples = ShortArray(RecordingCapturePolicy.BLOCK_SAMPLES)
            val encoded = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            var offset = initialBytes
            var periodicCheckpoint = initialBytes
            while (!stopping.get() && command() == CaptureCommand.Continue) {
                val remaining = (maxBytes - offset) / 2
                if (remaining <= 0) break
                val block = if (remaining >= samples.size) samples else ShortArray(remaining.toInt())
                val count = source.read(block)
                if (count <= 0) {
                    if (stopping.get()) break
                    throw IOException("录音读取失败：$count")
                }
                check(count <= block.size)
                encoded.clear()
                for (index in 0 until count) encoded.putShort(block[index])
                encoded.flip()
                while (encoded.hasRemaining()) {
                    val position = RecordingStore.WAV_HEADER_BYTES + offset + encoded.position()
                    check(channel.write(encoded, position) > 0)
                }
                offset += count * 2L
                written.set(offset)
                val backlog = offset - consumed.get()
                maxBacklog.accumulateAndGet(backlog, ::maxOf)
                changes.trySend(Unit)
                if (offset - periodicCheckpoint >= RecordingCapturePolicy.SAMPLE_RATE * 2L * RecordingCapturePolicy.CHECKPOINT_SECONDS) {
                    checkpoint(offset)
                    periodicCheckpoint = offset
                }
            }
        } catch (error: Throwable) {
            if (!stopping.get()) failure.compareAndSet(null, error)
        } finally {
            try { stop() } catch (error: Throwable) { failure.compareAndSet(null, error) }
            changes.close()
        }
    }

    var readerStarted = false
    try {
        currentCoroutineContext().ensureActive()
        if (command() == CaptureCommand.Continue && maxBytes - initialBytes >= 2) source.start()
        reader.start()
        readerStarted = true
        val blockBytes = RecordingCapturePolicy.BLOCK_SAMPLES * 2
        val encoded = ByteBuffer.allocate(blockBytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = initialBytes
        var done = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val available = written.get() - offset
            if (available > 0) {
                val count = minOf(available, blockBytes.toLong()).toInt()
                encoded.clear()
                encoded.limit(count)
                while (encoded.hasRemaining()) {
                    val position = RecordingStore.WAV_HEADER_BYTES + offset + encoded.position()
                    check(channel.read(encoded, position) > 0)
                }
                encoded.flip()
                val pcm = ShortArray(count / 2) { encoded.getShort() }
                val step = decoder.advanceSegment(state, pcm)
                if (step.frame.checkpoint) checkpoint(step.frame.bytes)
                onFrame(step.frame)
                if (step.reset) decoder.reset()
                offset += count
                consumed.set(offset)
            } else if (done) break
            else done = changes.receiveCatching().isClosed
        }
        // 读取失败时也补齐已落盘的音频，再把异常交给控制器归类。
        val tail = state.finish(decoder.finish())
        checkpoint(tail.bytes)
        onFrame(tail)
        failure.get()?.let { throw it }
        return command().takeUnless { it == CaptureCommand.Continue } ?: CaptureCommand.Finish
    } finally {
        stopping.set(true)
        withContext(NonCancellable + Dispatchers.IO) {
            try { stop() } finally {
                if (readerStarted) reader.join()
                changes.cancel()
                val bytes = written.get()
                val finalLength = RecordingStore.WAV_HEADER_BYTES + bytes
                if (file.length() != finalLength) {
                    file.setLength(finalLength)
                    file.fd.sync()
                }
                checkpoint(bytes)
                onBacklog(RecordingBacklog(
                    maxBacklog.get() * 1_000 / (RecordingCapturePolicy.SAMPLE_RATE * 2),
                    (bytes - initialBytes) * 1_000 / (RecordingCapturePolicy.SAMPLE_RATE * 2),
                ))
            }
        }
    }
}
