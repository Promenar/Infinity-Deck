package com.promenar.infinity.recording

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest

class RecordingRefineTest {

    @Test
    fun `sanitizeSenseVoiceOutput removes language emotion and event tags`() {
        assertEquals(
            "你好，Infinity Deck！",
            sanitizeSenseVoiceOutput("<|zh|><|NEUTRAL|><|Speech|><|withitn|>你好，Infinity Deck！", emptyList()),
        )
        assertEquals(
            "Hello world",
            sanitizeSenseVoiceOutput("<|en|>  Hello world  <|HAPPY|>", emptyList()),
        )
        assertNull(
            sanitizeSenseVoiceOutput("<|nospeech|><|BGM|>", emptyList()),
        )
    }

    @Test
    fun `readRecordingWavSlice extracts matching pcm16 16khz samples for time window`() {
        val dir = Files.createTempDirectory("refine_wav_test").toFile()
        try {
            val wavFile = File(dir, "sample.wav")
            val totalSamples = 16_000 * 2 // 2 seconds @ 16kHz
            val samples = ShortArray(totalSamples) { i -> ((i % 200) - 100).toShort() }
            writePcm16Wav(wavFile, samples)

            val midSlice = readRecordingWavSlice(wavFile, startMs = 500L, endMs = 1_500L, marginMs = 0L)
            assertNotNull(midSlice)
            assertEquals(16_000, midSlice!!.size)
            for (i in 0 until 16_000) {
                val expected = (((i + 8_000) % 200) - 100).toShort()
                assertEquals(expected, midSlice[i])
            }

            val emptySlice = readRecordingWavSlice(wavFile, startMs = 1_500L, endMs = 1_500L, marginMs = 0L)
            assertNull(emptySlice)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `refineRecordingSegments updates recognized text and keeps streaming fallback on blank output`() = runBlocking {
        val dir = Files.createTempDirectory("refine_segments_test").toFile()
        try {
            val wavFile = File(dir, "sample.wav")
            val samples = ShortArray(16_000 * 3) { 1200 }
            writePcm16Wav(wavFile, samples)

            val segments = listOf(
                RecordingSegment(startMs = 0L, endMs = 1_500L, text = "流式初稿一"),
                RecordingSegment(startMs = 1_500L, endMs = 3_000L, text = "流式初稿二保留"),
            )
            val fakeRefiner = object : RecordingRefiner {
                private var callIndex = 0
                override fun refine(pcm: ShortArray): String? {
                    callIndex++
                    return if (callIndex == 1) "精修校准文本一。" else null
                }

                override fun close() = Unit
            }

            val refined = refineRecordingSegments(
                audio = wavFile,
                segments = segments,
                refiner = fakeRefiner,
            )

            assertEquals(2, refined.size)
            assertEquals("精修校准文本一。", refined[0].text)
            assertEquals("流式初稿二保留", refined[1].text)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `refineModelManager verifies sha256 on install and rejects tampered payload`() = runBlocking {
        val root = Files.createTempDirectory("refine_mgr_test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val modelBytes = "fake-sensevoice-onnx".toByteArray()
            val tokensBytes = "a 0\nb 1\n".toByteArray()
            val spec = RecordingRefinePackage(
                version = "sensevoice-test-v1",
                baseUrl = "https://example.invalid/",
                files = listOf(
                    RecordingAsrFile("model.int8.onnx", modelBytes.size.toLong(), sha256Hex(modelBytes)),
                    RecordingAsrFile("tokens.txt", tokensBytes.size.toLong(), sha256Hex(tokensBytes)),
                ),
            )
            val okDownload = object : RecordingRefineDownload {
                override suspend fun downloadFile(
                    url: String,
                    fileName: String,
                    directory: File,
                    expectedSha256: String,
                    expectedBytes: Long,
                    progress: (Long, Long) -> Unit,
                ): File {
                    directory.mkdirs()
                    val payload = if (fileName == "model.int8.onnx") modelBytes else tokensBytes
                    val target = File(directory, fileName)
                    target.writeBytes(payload)
                    progress(payload.size.toLong(), payload.size.toLong())
                    return target
                }

                override fun cancel() = Unit
            }

            val manager = RecordingRefineModelManager(
                root = root,
                scope = scope,
                download = okDownload,
                spec = spec,
                availableBytes = { Long.MAX_VALUE },
            )
            manager.start()
            val installedState = withTimeout(5_000) {
                manager.state.first { it == RecordingAsrModelState.Installed || it is RecordingAsrModelState.Failed }
            }
            assertEquals(RecordingAsrModelState.Installed, installedState)
            assertTrue(manager.isInstalled())

            val corruptRoot = Files.createTempDirectory("refine_mgr_corrupt").toFile()
            try {
                val badDownload = object : RecordingRefineDownload {
                    override suspend fun downloadFile(
                        url: String,
                        fileName: String,
                        directory: File,
                        expectedSha256: String,
                        expectedBytes: Long,
                        progress: (Long, Long) -> Unit,
                    ): File {
                        directory.mkdirs()
                        val payload = if (fileName == "model.int8.onnx") "tampered".toByteArray() else tokensBytes
                        val target = File(directory, fileName)
                        target.writeBytes(payload)
                        return target
                    }

                    override fun cancel() = Unit
                }
                val failingManager = RecordingRefineModelManager(
                    root = corruptRoot,
                    scope = scope,
                    download = badDownload,
                    spec = spec,
                    availableBytes = { Long.MAX_VALUE },
                )
                failingManager.start()
                val failedState = withTimeout(5_000) {
                    failingManager.state.first { it == RecordingAsrModelState.Installed || it is RecordingAsrModelState.Failed }
                }
                assertTrue(failedState is RecordingAsrModelState.Failed)
                assertFalse(failingManager.isInstalled())
            } finally {
                corruptRoot.deleteRecursively()
            }
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private fun writePcm16Wav(file: File, samples: ShortArray, sampleRate: Int = 16_000) {
        val dataSize = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(1) // Mono
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2) // Block align
            putShort(16) // Bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()
        val out = ByteArrayOutputStream(44 + dataSize)
        out.write(header)
        val pcmBuf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) pcmBuf.putShort(s)
        out.write(pcmBuf.array())
        file.writeBytes(out.toByteArray())
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
