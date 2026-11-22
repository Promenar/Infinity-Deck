package com.promenar.infinity.recording

import com.promenar.infinity.R
import com.promenar.infinity.data.local.inference.ModelDownloadIntegrityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class RecordingSpeakerModelTest {
    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun createTarBz2(entryName: String, payload: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        BZip2CompressorOutputStream(output).use { bz ->
            TarArchiveOutputStream(bz).use { tar ->
                val junk = "license-text".toByteArray()
                tar.putArchiveEntry(TarArchiveEntry("sherpa-onnx-pyannote-segmentation-3-0/LICENSE").also { it.size = junk.size.toLong() })
                tar.write(junk)
                tar.closeArchiveEntry()
                tar.putArchiveEntry(TarArchiveEntry(entryName).also { it.size = payload.size.toLong() })
                tar.write(payload)
                tar.closeArchiveEntry()
            }
        }
        return output.toByteArray()
    }

    private class Fixture {
        val root: File = Files.createTempDirectory("speaker-model-test-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val segmentationBytes = "segmentation-onnx-bytes".toByteArray()
        val embeddingBytes = "campplus-onnx-bytes-v2".toByteArray()
        val legacyEmbeddingBytes = "campplus-legacy-v1".toByteArray()
        val archiveBytes: ByteArray
        val spec: RecordingSpeakerPackage

        init {
            val md = MessageDigest.getInstance("SHA-256")
            fun sha(b: ByteArray) = md.digest(b).joinToString("") { "%02x".format(it) }
            val out = ByteArrayOutputStream()
            BZip2CompressorOutputStream(out).use { bz ->
                TarArchiveOutputStream(bz).use { tar ->
                    val entry = "sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx"
                    tar.putArchiveEntry(TarArchiveEntry(entry).also { it.size = segmentationBytes.size.toLong() })
                    tar.write(segmentationBytes)
                    tar.closeArchiveEntry()
                }
            }
            archiveBytes = out.toByteArray()
            spec = RecordingSpeakerPackage(
                segmentation = RecordingAsrFile("segmentation.onnx", segmentationBytes.size.toLong(), sha(segmentationBytes)),
                segmentationUrl = "https://primary.invalid/segmentation.onnx",
                segmentationArchiveFallbackUrl = "https://fallback.invalid/segmentation.tar.bz2",
                segmentationArchiveEntry = "sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx",
                segmentationArchiveBytes = archiveBytes.size.toLong(),
                segmentationArchiveSha256 = sha(archiveBytes),
                embedding = RecordingAsrFile("campplus.onnx", embeddingBytes.size.toLong(), sha(embeddingBytes)),
                embeddingUrl = "https://primary.invalid/campplus.onnx",
                embeddingFallbackUrl = "https://fallback.invalid/campplus.onnx",
                legacyEmbeddingBytes = legacyEmbeddingBytes.size.toLong(),
            )
        }

        suspend fun close() {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            root.deleteRecursively()
        }
    }

    private suspend fun done(manager: RecordingSpeakerModelManager, f: Fixture): RecordingAsrModelState = withTimeout(5_000) {
        val state = manager.state.first { it == RecordingAsrModelState.Installed || it is RecordingAsrModelState.Failed }
        f.scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        state
    }

    @Test fun primaryInstallSucceedsAndCleansDownloadCacheAndLegacyArchive() = runBlocking {
        val f = Fixture()
        try {
            File(f.root, "segmentation.tar.bz2").writeText("legacy-archive")
            File(f.root, "segmentation.tar.bz2.part").writeText("legacy-part")
            File(f.root, "${f.spec.embedding.name}.part").writeText("legacy-emb-part")
            val requestedUrls = mutableListOf<String>()
            val download = object : RecordingSpeakerDownload {
                override suspend fun downloadFile(
                    url: String, fileName: String, directory: File, expectedSha256: String, expectedBytes: Long, progress: (Long, Long) -> Unit,
                ): File {
                    requestedUrls += url
                    directory.mkdirs()
                    val payload = when (url) {
                        f.spec.segmentationUrl -> f.segmentationBytes
                        f.spec.embeddingUrl -> f.embeddingBytes
                        else -> error("unexpected url: $url")
                    }
                    progress(payload.size.toLong(), expectedBytes)
                    return File(directory, fileName).apply { writeBytes(payload) }
                }
                override fun cancel() = Unit
            }
            val manager = RecordingSpeakerModelManager(f.root, f.scope, download, f.spec) { Long.MAX_VALUE }
            manager.start()
            assertEquals(RecordingAsrModelState.Installed, done(manager, f))
            assertEquals(listOf(f.spec.segmentationUrl, f.spec.embeddingUrl), requestedUrls)
            val installed = manager.installed()
            assertNotNull(installed)
            assertArrayEquals(f.segmentationBytes, installed!!.segmentation.readBytes())
            assertArrayEquals(f.embeddingBytes, installed.embedding.readBytes())
            assertFalse(File(f.root, ".download").exists())
            assertFalse(File(f.root, "segmentation.tar.bz2").exists())
            assertFalse(File(f.root, "segmentation.tar.bz2.part").exists())
            assertFalse(File(f.root, "${f.spec.embedding.name}.part").exists())
        } finally { f.close() }
    }

    @Test fun primaryFailureFallsBackToGitHubArchiveAndEmbeddingUrl() = runBlocking {
        val f = Fixture()
        try {
            val requestedUrls = mutableListOf<String>()
            val download = object : RecordingSpeakerDownload {
                override suspend fun downloadFile(
                    url: String, fileName: String, directory: File, expectedSha256: String, expectedBytes: Long, progress: (Long, Long) -> Unit,
                ): File {
                    requestedUrls += url
                    directory.mkdirs()
                    val payload = when (url) {
                        f.spec.segmentationUrl -> throw IOException("primary seg blocked")
                        f.spec.segmentationArchiveFallbackUrl -> createTarBz2(f.spec.segmentationArchiveEntry, f.segmentationBytes)
                        f.spec.embeddingUrl -> throw IOException("primary emb blocked")
                        f.spec.embeddingFallbackUrl -> f.embeddingBytes
                        else -> error("unexpected url: $url")
                    }
                    progress(payload.size.toLong(), expectedBytes)
                    return File(directory, fileName).apply { writeBytes(payload) }
                }
                override fun cancel() = Unit
            }
            val manager = RecordingSpeakerModelManager(f.root, f.scope, download, f.spec) { Long.MAX_VALUE }
            manager.start()
            assertEquals(RecordingAsrModelState.Installed, done(manager, f))
            assertEquals(
                listOf(
                    f.spec.segmentationUrl,
                    f.spec.segmentationArchiveFallbackUrl,
                    f.spec.embeddingUrl,
                    f.spec.embeddingFallbackUrl,
                ),
                requestedUrls,
            )
            val installed = manager.installed()
            assertNotNull(installed)
            assertArrayEquals(f.segmentationBytes, installed!!.segmentation.readBytes())
            assertArrayEquals(f.embeddingBytes, installed.embedding.readBytes())
        } finally { f.close() }
    }

    @Test fun installedAcceptsLegacyEmbeddingByteLengthForExistingUsers() = runBlocking {
        val f = Fixture()
        try {
            File(f.root, f.spec.segmentation.name).writeBytes(f.segmentationBytes)
            File(f.root, f.spec.embedding.name).writeBytes(f.legacyEmbeddingBytes)
            val noopDownload = object : RecordingSpeakerDownload {
                override suspend fun downloadFile(
                    url: String, fileName: String, directory: File, expectedSha256: String, expectedBytes: Long, progress: (Long, Long) -> Unit,
                ): File = error("should not download")
                override fun cancel() = Unit
            }
            val manager = RecordingSpeakerModelManager(f.root, f.scope, noopDownload, f.spec) { Long.MAX_VALUE }
            assertNotNull(manager.installed())
            val state = withTimeout(5_000) {
                manager.state.first { it == RecordingAsrModelState.Installed }
            }
            assertEquals(RecordingAsrModelState.Installed, state)
        } finally { f.close() }
    }

    @Test fun integrityStorageAndNetworkFailuresClassifyCorrectlyAndCleanDownloadsOnIntegrityError() = runBlocking {
        // 1) 校验失败清理 .download 并映射为 recording_speaker_integrity
        val fIntegrity = Fixture()
        try {
            val tamperedDownload = object : RecordingSpeakerDownload {
                override suspend fun downloadFile(
                    url: String, fileName: String, directory: File, expectedSha256: String, expectedBytes: Long, progress: (Long, Long) -> Unit,
                ): File {
                    directory.mkdirs()
                    if (url == fIntegrity.spec.segmentationUrl) {
                        return File(directory, fileName).apply { writeBytes(fIntegrity.segmentationBytes) }
                    }
                    // 故意返回篡改内容由 copyVerifiedFile 触发 ModelDownloadIntegrityException
                    return File(directory, fileName).apply { writeBytes("tampered-payload".toByteArray()) }
                }
                override fun cancel() = Unit
            }
            val integrityMgr = RecordingSpeakerModelManager(fIntegrity.root, fIntegrity.scope, tamperedDownload, fIntegrity.spec) { Long.MAX_VALUE }
            integrityMgr.start()
            assertEquals(RecordingAsrModelState.Failed(R.string.recording_speaker_integrity), done(integrityMgr, fIntegrity))
            assertFalse(File(fIntegrity.root, ".download").exists())
            assertNull(integrityMgr.installed())
        } finally { fIntegrity.close() }

        // 2) 存储不足映射为 recording_speaker_storage
        val fStorage = Fixture()
        try {
            val unusedDownload = object : RecordingSpeakerDownload {
                override suspend fun downloadFile(
                    url: String, fileName: String, directory: File, expectedSha256: String, expectedBytes: Long, progress: (Long, Long) -> Unit,
                ): File = error("storage preflight should block before download")
                override fun cancel() = Unit
            }
            val storageMgr = RecordingSpeakerModelManager(fStorage.root, fStorage.scope, unusedDownload, fStorage.spec) { 0L }
            storageMgr.start()
            assertEquals(RecordingAsrModelState.Failed(R.string.recording_speaker_storage), done(storageMgr, fStorage))
        } finally { fStorage.close() }

        // 3) 主源与兜底均网络异常映射为 recording_speaker_network
        val fNetwork = Fixture()
        try {
            val failingDownload = object : RecordingSpeakerDownload {
                override suspend fun downloadFile(
                    url: String, fileName: String, directory: File, expectedSha256: String, expectedBytes: Long, progress: (Long, Long) -> Unit,
                ): File = throw IOException("network unreachable")
                override fun cancel() = Unit
            }
            val networkMgr = RecordingSpeakerModelManager(fNetwork.root, fNetwork.scope, failingDownload, fNetwork.spec) { Long.MAX_VALUE }
            networkMgr.start()
            assertEquals(RecordingAsrModelState.Failed(R.string.recording_speaker_network), done(networkMgr, fNetwork))
        } finally { fNetwork.close() }

        // 4) ModelDownloadIntegrityException 直抛同样清理 .download
        val fDirectIntegrity = Fixture()
        try {
            val directBad = object : RecordingSpeakerDownload {
                override suspend fun downloadFile(
                    url: String, fileName: String, directory: File, expectedSha256: String, expectedBytes: Long, progress: (Long, Long) -> Unit,
                ): File {
                    directory.mkdirs()
                    File(directory, "stale.part").writeText("leftover")
                    throw ModelDownloadIntegrityException("digest mismatch")
                }
                override fun cancel() = Unit
            }
            val directMgr = RecordingSpeakerModelManager(fDirectIntegrity.root, fDirectIntegrity.scope, directBad, fDirectIntegrity.spec) { Long.MAX_VALUE }
            directMgr.start()
            assertEquals(RecordingAsrModelState.Failed(R.string.recording_speaker_integrity), done(directMgr, fDirectIntegrity))
            assertFalse(File(fDirectIntegrity.root, ".download").exists())
        } finally { fDirectIntegrity.close() }
    }

    @Test fun cancellationRestoresMissingAndDoesNotInvokeFallback() = runBlocking {
        val f = Fixture()
        try {
            val urls = mutableListOf<String>()
            val download = object : RecordingSpeakerDownload {
                override suspend fun downloadFile(
                    url: String, fileName: String, directory: File, expectedSha256: String, expectedBytes: Long, progress: (Long, Long) -> Unit,
                ): File {
                    urls += url
                    throw CancellationException("user cancelled")
                }
                override fun cancel() = Unit
            }
            val manager = RecordingSpeakerModelManager(f.root, f.scope, download, f.spec) { Long.MAX_VALUE }
            manager.start()
            withTimeout(5_000) {
                f.scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
            }
            assertEquals(listOf(f.spec.segmentationUrl), urls)
            assertFalse(urls.any { it == f.spec.segmentationArchiveFallbackUrl || it == f.spec.embeddingFallbackUrl })
            assertEquals(RecordingAsrModelState.Missing, manager.state.value)
        } finally { f.close() }
    }

    @Test fun currentSpecUsesImmutableHuggingFaceCommitAndConsistentByteTotals() {
        val speaker = RecordingSpeakerPackage.CURRENT
        val hfResolvePattern = Regex("^https://huggingface\\.co/[^/]+/[^/]+/resolve/[0-9a-f]{40}/.+")
        assertTrue(hfResolvePattern.matches(speaker.segmentationUrl))
        assertTrue(hfResolvePattern.matches(speaker.embeddingUrl))
        assertEquals(speaker.downloadBytes, RecordingSpeakerModelManager.DOWNLOAD_BYTES)
        assertEquals(speaker.installedBytes, RecordingSpeakerModelManager.INSTALLED_BYTES)
        assertEquals(28_281_139L, speaker.embedding.bytes)
        assertEquals("e6af4d4c444f3dab01d991bb9b7d5af98eb979e329f6c8880a19d5553561ed25", speaker.embedding.sha256)
        assertEquals(28_281_138L, speaker.legacyEmbeddingBytes)

        val refineUrl = RecordingRefinePackage.CURRENT.baseUrl
        assertFalse(refineUrl.contains("/resolve/main/"))
        assertTrue(Regex("^https://huggingface\\.co/[^/]+/[^/]+/resolve/[0-9a-f]{40}/$").matches(refineUrl))
    }
}
