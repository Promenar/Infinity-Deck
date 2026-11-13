package com.promenar.infinity.recording

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.RandomAccessFile
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

internal interface RecordingDecoder {
    fun accept(pcm: ShortArray): RecordingRecognition
    fun reset()
    fun finish(): String
}

internal data class RecordingBacklog(val maxQueuedMs: Long, val durationMs: Long)

/** 读取线程只向无界队列交付独占的 PCM 块；业务回调只在消费协程执行。 */
private suspend fun drainRecordingAudio(
    source: RecordingAudioSource,
    availableBytes: Long,
    command: () -> CaptureCommand,
    onReaderStart: () -> Unit,
    onBacklog: (RecordingBacklog) -> Unit,
    consume: suspend (ShortArray) -> Unit,
    finish: suspend () -> Unit,
): CaptureCommand {
    val queue = Channel<ShortArray>(Channel.UNLIMITED)
    val stopping = AtomicBoolean(false)
    val stopped = AtomicBoolean(false)
    val failure = AtomicReference<Throwable?>(null)
    val samplesRead = AtomicLong(0)
    val queuedSamples = AtomicLong(0)
    val maxQueuedSamples = AtomicLong(0)
    fun stop() {
        if (stopped.compareAndSet(false, true)) source.stop()
    }
    val reader = thread(start = false, name = "recording-audio-reader") {
        try {
            onReaderStart()
            while (!stopping.get() && command() == CaptureCommand.Continue) {
                val remaining = availableBytes / 2 - samplesRead.get()
                if (remaining <= 0) break
                val pcm = ShortArray(minOf(RecordingCapturePolicy.BLOCK_SAMPLES.toLong(), remaining).toInt())
                val count = source.read(pcm)
                if (count <= 0) {
                    if (stopping.get()) break
                    throw IOException("录音读取失败：$count")
                }
                check(count <= pcm.size)
                samplesRead.addAndGet(count.toLong())
                val backlog = queuedSamples.addAndGet(count.toLong())
                maxQueuedSamples.accumulateAndGet(backlog) { previous, current -> maxOf(previous, current) }
                queue.trySend(if (count == pcm.size) pcm else pcm.copyOf(count)).getOrThrow()
            }
        } catch (error: Throwable) {
            if (!stopping.get()) failure.compareAndSet(null, error)
        } finally {
            try { stop() } catch (error: Throwable) { failure.compareAndSet(null, error) }
            queue.close()
        }
    }
    var readerStarted = false
    try {
        currentCoroutineContext().ensureActive()
        if (command() == CaptureCommand.Continue && availableBytes >= 2) source.start()
        reader.start()
        readerStarted = true
        for (pcm in queue) {
            currentCoroutineContext().ensureActive()
            queuedSamples.addAndGet(-pcm.size.toLong())
            consume(pcm)
        }
        // 读取失败也先排空、取尾段，再把原始异常交给控制器归类。
        finish()
        failure.get()?.let { throw it }
        return command().takeUnless { it == CaptureCommand.Continue } ?: CaptureCommand.Finish
    } finally {
        stopping.set(true)
        withContext(NonCancellable + Dispatchers.IO) {
            try { stop() } finally {
                if (readerStarted) reader.join()
                queue.cancel()
                onBacklog(RecordingBacklog(
                    maxQueuedSamples.get() * 1_000 / RecordingCapturePolicy.SAMPLE_RATE,
                    samplesRead.get() * 1_000 / RecordingCapturePolicy.SAMPLE_RATE,
                ))
            }
        }
    }
}

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
    var written = initialBytes
    file.setLength(44 + written)
    updateRecordingWavHeader(file, written)
    try {
        return drainRecordingAudio(source, maxBytes - initialBytes, command, onReaderStart, onBacklog,
            consume = { pcm ->
                val encoded = ByteArray(pcm.size * 2)
                for (i in pcm.indices) {
                    encoded[2 * i] = pcm[i].toByte()
                    encoded[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
                }
                file.write(encoded)
                written += encoded.size
                val recognized = decoder.accept(pcm)
                val step = state.accept(pcm, recognized.text, recognized.endpoint)
                if (step.reset) decoder.reset()
                if (step.frame.checkpoint) {
                    updateRecordingWavHeader(file, written)
                    file.fd.sync()
                }
                onFrame(step.frame)
            },
            finish = {
                val tail = state.finish(decoder.finish())
                updateRecordingWavHeader(file, written)
                file.fd.sync()
                onFrame(tail)
            },
        )
    } finally {
        // 取消或下游异常时也让文件头与已成功写入的数据一致。
        updateRecordingWavHeader(file, written)
        file.fd.sync()
    }
}
