package com.promenar.infinity.recording

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class RecordingRecognitionTest {
    private fun wav(samples: Int): File = Files.createTempFile("recognize-", ".wav").toFile().also { path ->
        RandomAccessFile(path, "rw").use { file ->
            file.setLength(44 + samples * 2L)
            updateRecordingWavHeader(file, samples * 2L)
        }
    }

    @Test fun recognitionRequiresSavedAudioAndAnIdleRecorder() {
        val item = RecordingSession("id", 1, "标题", phase = RecordingPhase.Finished, audioBytes = 640)
        assertTrue(recordingCanRecognize(item, null, null))
        assertTrue(recordingCanRecognize(item.copy(phase = RecordingPhase.Interrupted), null, null))
        assertFalse(recordingCanRecognize(item.copy(audioBytes = 0), null, null))
        assertFalse(recordingCanRecognize(item.copy(phase = RecordingPhase.Paused), null, null))
        assertFalse(recordingCanRecognize(item, "active", null))
        assertFalse(recordingCanRecognize(item, null, "recognizing"))
    }

    @Test fun wavUsesCaptureBlocksSegmentationAndFinalTailWithoutRealtimePacing() = runBlocking {
        val audio = wav(RecordingCapturePolicy.BLOCK_SAMPLES * 3 + 20)
        try {
            val sizes = mutableListOf<Int>()
            var calls = 0
            val decoder = object : RecordingDecoder {
                override fun speechWindow(pcm: ShortArray) = RecordingSpeechWindow(end = pcm.size.takeIf { calls == 1 })
                override fun accept(pcm: ShortArray): RecordingRecognition {
                    sizes += pcm.size
                    return RecordingRecognition("第${++calls}段")
                }
                override fun reset() = Unit
                override fun finish() = if (calls == 2) "第2段" else "尾段"
            }
            val progress = mutableListOf<Float>()
            val segments = recognizeRecordingWav(audio, decoder) { progress += it }
            assertEquals(listOf(640, 640, 640, 20), sizes)
            assertEquals(listOf("第2段", "尾段"), segments.map { it.text })
            assertEquals(0f, progress.first())
            assertEquals(1f, progress.last())
            assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
        } finally { audio.delete() }
    }

    @Test fun forcedBoundaryKeepsBufferedAudioForTheNextSegment() = runBlocking {
        val rate = RecordingCapturePolicy.SAMPLE_RATE
        val boundary = RecordingCapturePolicy.FORCE_SEGMENT_MS
        val audio = wav((boundary * rate / 1_000).toInt() + RecordingCapturePolicy.BLOCK_SAMPLES)
        try {
            val decoder = object : RecordingDecoder {
                private var first = true
                override fun accept(pcm: ShortArray) = RecordingRecognition(
                    if (first) "断段前" else "断段前最后几个字", decodedMs = boundary - 480,
                )
                override fun finish(): String {
                    assertFalse("强制分段不能结束音频流", first)
                    return "断段前最后几个字继续"
                }
                override fun reset() { first = false }
            }
            val segments = recognizeRecordingWav(audio, decoder) {}
            assertEquals(listOf(
                RecordingSegment(0, boundary - 480, "断段前"),
                RecordingSegment(boundary - 480, boundary + 40, "断段前最后几个字继续"),
            ), segments)
        } finally { audio.delete() }
    }

    @Test fun writebackPreservesOrganizedDraftUntilReplacementIsReady() {
        val original = RecordingSession("id", 1, "标题", phase = RecordingPhase.Finished, organized = "旧整理稿",
            organizedThrough = 8, reprocessThrough = 2, reprocessText = "暂存", manualEdit = true,
            checkpointText = RecordingSegment(1, 2, "检查点"), documentId = "doc", documentHash = "prior-hash")
        val segments = listOf(RecordingSegment(0, 100, "新逐字稿"))
        val rewrite = recordingRecognitionResult(original, segments, true)
        assertEquals("旧整理稿", rewrite.organized)
        assertFalse(rewrite.manualEdit)
        assertEquals(0, rewrite.reprocessThrough)
        assertEquals("", rewrite.reprocessText)
        val rawOnly = recordingRecognitionResult(original, segments, false)
        assertEquals("旧整理稿", rawOnly.organized)
        assertTrue(rawOnly.manualEdit)
        assertEquals(1, rawOnly.organizedThrough)
        assertNull(rawOnly.reprocessThrough)
        assertEquals("", rawOnly.reprocessText)
        for (item in listOf(rewrite, rawOnly)) {
            assertNull(item.checkpointText)
            assertEquals(segments, item.segments)
            assertEquals("prior-hash", item.documentHash)
        }
    }

    @Test fun cancellationAndDecoderFailureDoNotReturnPartialTranscript() = runBlocking {
        val audio = wav(640 * 4)
        try {
            var returned = false
            val job = launch {
                val decoder = object : RecordingDecoder {
                    override fun accept(pcm: ShortArray) = RecordingRecognition("部分结果")
                    override fun reset() = Unit
                    override fun finish() = "尾段"
                }
                recognizeRecordingWav(audio, decoder) { if (it > 0) awaitCancellation() }
                returned = true
            }
            yield(); job.cancelAndJoin()
            assertFalse(returned)
            val failing = object : RecordingDecoder {
                override fun accept(pcm: ShortArray): RecordingRecognition = error("解码失败")
                override fun reset() = Unit
                override fun finish() = ""
            }
            try { recognizeRecordingWav(audio, failing) {}; fail("应抛出解码失败") } catch (_: IllegalStateException) { }
            audio.writeText("invalid")
            try { recognizeRecordingWav(audio, EmptyRecordingDecoder) {}; fail("应拒绝损坏 WAV") } catch (_: java.io.EOFException) { }
        } finally { audio.delete() }
    }
}
