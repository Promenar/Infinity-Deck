package com.promenar.infinity.recording

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RecordingCapturePipelineTest {
    private class Source(
        val command: AtomicReference<CaptureCommand>,
        val blocks: List<ShortArray>,
        val ending: CaptureCommand = CaptureCommand.Finish,
        val failure: IOException? = null,
        val failureCode: Int? = null,
    ) : RecordingAudioSource {
        val stopped = CountDownLatch(1)
        var index = 0
        override fun start() = Unit
        override fun read(buffer: ShortArray): Int {
            if (index == blocks.size) {
                failure?.let { throw it }
                return requireNotNull(failureCode)
            }
            val block = blocks[index++]
            val count = minOf(block.size, buffer.size)
            block.copyInto(buffer, endIndex = count)
            if (index == blocks.size && failure == null && failureCode == null) command.set(ending)
            return count
        }
        override fun stop() { stopped.countDown() }
    }

    private class Decoder(private val sourceStopped: CountDownLatch, private val delayMs: Long = 0) : RecordingDecoder {
        val samples = mutableListOf<Short>()
        var finished = false
        override fun accept(pcm: ShortArray): RecordingRecognition {
            if (delayMs > 0) Thread.sleep(delayMs)
            samples.addAll(pcm.toList())
            return RecordingRecognition("当前 文本", false)
        }
        override fun reset() = Unit
        override fun finish(): String {
            assertEquals(0L, sourceStopped.count)
            finished = true
            return "尾段， 完整。"
        }
    }

    private suspend fun withWav(block: suspend (RandomAccessFile) -> Unit) {
        val path = File.createTempFile("recording-pipeline-", ".wav")
        try { RandomAccessFile(path, "rw").use { block(it) } } finally { path.delete() }
    }

    private fun assertWav(file: RandomAccessFile, samples: List<Short>) {
        assertEquals(44L + samples.size * 2, file.length())
        file.seek(0)
        val bytes = ByteArray(file.length().toInt()).also(file::readFully)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(bytes.size - 8, buffer.getInt(4))
        assertEquals(samples.size * 2, buffer.getInt(40))
        val actual = ShortArray(samples.size) { buffer.getShort(44 + it * 2) }
        assertArrayEquals(samples.toShortArray(), actual)
    }

    @Test(timeout = 2_000) fun slowConsumerPreservesEverySampleAndDrainsBeforeTail() = runBlocking {
        for (ending in listOf(CaptureCommand.Pause, CaptureCommand.Finish)) withWav { file ->
            val command = AtomicReference(CaptureCommand.Continue)
            val blocks = List(30) { block -> ShortArray(640) { (block * 640 + it).toShort() } }
            val source = Source(command, blocks, ending)
            val decoder = Decoder(source.stopped, delayMs = 1)
            val frames = mutableListOf<CaptureFrame>()
            var stats: RecordingBacklog? = null
            val result = withTimeout(1_000) {
                recordPcmAudio(source, decoder, file, 0, 1_000_000, command::get,
                    onBacklog = { stats = it }, onFrame = {
                        assertTrue(source.stopped.await(500, TimeUnit.MILLISECONDS))
                        if (!decoder.finished) delay(1)
                        frames.add(it)
                    })
            }
            val expected = blocks.flatMap { it.toList() }
            assertEquals(ending, result)
            assertEquals(expected, decoder.samples)
            assertWav(file, expected)
            assertEquals(31, frames.size)
            assertEquals(RecordingSegment(0, 1_200, "尾段，完整。"), frames.last().segment)
            assertTrue(requireNotNull(stats).maxQueuedMs > 0)
            assertEquals(1_200L, requireNotNull(stats).durationMs)
        }
    }

    @Test(timeout = 2_000) fun readExceptionOrErrorCodePreservesAudioAndPropagatesFailure() = runBlocking {
        for (code in listOf<Int?>(null, 0, -3)) withWav { file ->
            val command = AtomicReference(CaptureCommand.Continue)
            val failure = IOException("假音源失败")
            val source = Source(command, listOf(shortArrayOf(1, -2, 3)), failure = failure.takeIf { code == null }, failureCode = code)
            val decoder = Decoder(source.stopped)
            var caught: IOException? = null
            try {
                withTimeout(1_000) { recordPcmAudio(source, decoder, file, 0, 1_000, command::get, onFrame = {}) }
            } catch (error: IOException) { caught = error }
            assertTrue(caught != null)
            if (code == null) assertEquals(failure.message, caught?.message)
            assertEquals(listOf<Short>(1, -2, 3), decoder.samples)
            assertTrue(decoder.finished)
            assertWav(file, listOf(1, -2, 3))
        }
    }

    @Test(timeout = 2_000) fun limitStopsReadingAndFinalizesAnExactlyFullWav() = runBlocking {
        withWav { file ->
            val command = AtomicReference(CaptureCommand.Continue)
            val source = Source(command, List(4) { ShortArray(640) { 7 } })
            val decoder = Decoder(source.stopped)
            val result = withTimeout(1_000) {
                recordPcmAudio(source, decoder, file, 0, 1_300, command::get, onFrame = {})
            }
            assertEquals(CaptureCommand.Finish, result)
            assertEquals(2, source.index)
            assertEquals(650, decoder.samples.size)
            assertTrue(decoder.finished)
            assertWav(file, List(650) { 7 })
        }
    }

    @Test(timeout = 2_000) fun cancellationUnblocksTheReaderAndRepairsTheWrittenHeader() = runBlocking {
        withWav { file ->
            val released = CountDownLatch(1)
            val readBlocked = CountDownLatch(1)
            val frameReceived = CompletableDeferred<Unit>()
            val source = object : RecordingAudioSource {
                var first = true
                override fun start() = Unit
                override fun read(buffer: ShortArray): Int {
                    if (first) {
                        first = false
                        buffer[0] = 42
                        return 1
                    }
                    readBlocked.countDown()
                    check(released.await(500, TimeUnit.MILLISECONDS))
                    return -3
                }
                override fun stop() { released.countDown() }
            }
            val decoder = Decoder(released)
            val job = launch(Dispatchers.IO) {
                recordPcmAudio(source, decoder, file, 0, 1_000, { CaptureCommand.Continue }, onFrame = {
                    frameReceived.complete(Unit)
                    awaitCancellation()
                })
            }
            withTimeout(1_000) { frameReceived.await() }
            assertTrue(readBlocked.await(500, TimeUnit.MILLISECONDS))
            withTimeout(1_000) { job.cancelAndJoin() }
            assertEquals(0L, released.count)
            assertFalse(decoder.finished)
            assertWav(file, listOf(42))
        }
    }

    @Test(timeout = 2_000) fun initializationOrConsumerFailureStopsReaderAndRepairsHeader() = runBlocking {
        for (stage in listOf("start", "priority", "decode", "frame")) withWav { file ->
            val failure = IOException("假故障：$stage")
            val stopped = CountDownLatch(1)
            val reader = AtomicReference<Thread?>(null)
            val source = object : RecordingAudioSource {
                var first = true
                override fun start() { if (stage == "start") throw failure }
                override fun read(buffer: ShortArray): Int {
                    if (first) {
                        first = false
                        buffer[0] = 42
                        return 1
                    }
                    check(stopped.await(300, TimeUnit.MILLISECONDS))
                    return -3
                }
                override fun stop() { stopped.countDown() }
            }
            val decoder = object : RecordingDecoder {
                override fun accept(pcm: ShortArray): RecordingRecognition {
                    if (stage == "decode") throw failure
                    return RecordingRecognition("测试", false)
                }
                override fun reset() = Unit
                override fun finish() = ""
            }
            var caught: IOException? = null
            try {
                withTimeout(400) {
                    recordPcmAudio(source, decoder, file, 0, 1_000, { CaptureCommand.Continue },
                        onReaderStart = {
                            reader.set(Thread.currentThread())
                            if (stage == "priority") throw failure
                        },
                        onFrame = { if (stage == "frame") throw failure },
                    )
                }
            } catch (error: IOException) { caught = error }
            assertEquals(failure.message, caught?.message)
            assertEquals(0L, stopped.count)
            assertFalse(reader.get()?.isAlive == true)
            assertWav(file, if (stage in listOf("decode", "frame")) listOf(42) else emptyList())
        }
    }
}
