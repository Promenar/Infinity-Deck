package com.promenar.infinity.recording

import com.promenar.infinity.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class RecordingAsrModelTest {
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private class Fixture {
        val root = Files.createTempDirectory("asr-install-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val payload = linkedMapOf("encoder.int8.onnx" to "encoder".toByteArray(), "decoder.onnx" to "decoder".toByteArray(),
            "joiner.int8.onnx" to "joiner".toByteArray(), "tokens.txt" to "张 1\n▁永 2\n".toByteArray())
        val vocab = "vocab".toByteArray()
        val archive = File(root, "source.tar.bz2")
        suspend fun close() { scope.coroutineContext[Job]!!.cancelAndJoin(); root.deleteRecursively() }
    }
    private fun spec(f: Fixture, corrupt: Boolean = false): RecordingAsrPackage {
        BZip2CompressorOutputStream(f.archive.outputStream()).use { bz ->
            TarArchiveOutputStream(bz).use { tar ->
                f.payload.forEach { (name, bytes) ->
                    val entry = TarArchiveEntry("upstream/$name").also { it.size = bytes.size.toLong() }
                    tar.putArchiveEntry(entry); tar.write(bytes); tar.closeArchiveEntry()
                }
                // 未列入描述的文件不会落盘。
                tar.putArchiveEntry(TarArchiveEntry("../outside").also { it.size = 1 }); tar.write(1); tar.closeArchiveEntry()
            }
        }
        return RecordingAsrPackage("test-model", "upstream", "https://example.invalid/model", f.archive.length(), hash(f.archive.readBytes()),
            f.payload.map { (name, bytes) -> RecordingAsrFile(name, bytes.size.toLong(), if (corrupt && name.startsWith("encoder")) "0".repeat(64) else hash(bytes)) },
            RecordingAsrFile("bpe.vocab", f.vocab.size.toLong(), hash(f.vocab)))
    }
    private fun copied(f: Fixture) = object : RecordingAsrDownload {
        override suspend fun download(spec: RecordingAsrPackage, directory: File, progress: (Long, Long) -> Unit): File {
            directory.mkdirs()
            progress(spec.bytes, spec.bytes)
            return f.archive.copyTo(File(directory, spec.archiveName), overwrite = true)
        }
        override fun cancel() = Unit
    }
    private suspend fun done(manager: RecordingAsrModelManager, f: Fixture) = withTimeout(5_000) {
        val state = manager.state.first { it is RecordingAsrModelState.Installed || it is RecordingAsrModelState.Failed }
        f.scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        state
    }

    @Test fun successfulInstallHasFiveVerifiedFilesAndStartupOnlyChecksSizes() = runBlocking {
        val f = Fixture()
        try {
            val spec = spec(f)
            val manager = RecordingAsrModelManager(f.root, f.scope, copied(f), { f.vocab.inputStream() }, spec) { Long.MAX_VALUE }
            manager.start()
            assertEquals(RecordingAsrModelState.Installed, done(manager, f))
            assertTrue(manager.isInstalled())
            assertEquals(listOf('蔺'), manager.unknownCharacters("张蔺"))
            val target = requireNotNull(manager.installedDirectory())
            assertEquals(setOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt", "bpe.vocab", "version"), target.list()!!.toSet())
            assertFalse(File(f.root, ".download/${spec.archiveName}").exists())
            assertFalse(File(f.root.parentFile, "outside").exists())
            File(target, "encoder.int8.onnx").writeText("altered")
            assertTrue(manager.isInstalled())
            File(target, "version").writeText("wrong")
            assertFalse(manager.isInstalled())
        } finally { f.close() }
    }

    @Test fun failedFileOrVocabDigestCleansStagingAndLeavesNoInstalledDirectory() = runBlocking {
        for (badVocab in listOf(false, true)) {
            val f = Fixture()
            try {
                val spec = spec(f, corrupt = !badVocab)
                val manager = RecordingAsrModelManager(f.root, f.scope, copied(f), { (if (badVocab) "wrong".toByteArray() else f.vocab).inputStream() }, spec) { Long.MAX_VALUE }
                manager.start()
                assertEquals(RecordingAsrModelState.Failed(R.string.recording_asr_integrity), done(manager, f))
                assertFalse(File(f.root, spec.version).exists())
                assertFalse(File(f.root, ".${spec.version}.install").exists())
            } finally { f.close() }
        }
    }

    @Test fun insufficientSpaceDoesNotStartNetwork() = runBlocking {
        val f = Fixture()
        try {
            val spec = spec(f)
            var started = false
            val fake = object : RecordingAsrDownload {
                override suspend fun download(spec: RecordingAsrPackage, directory: File, progress: (Long, Long) -> Unit): File { started = true; error("不应下载") }
                override fun cancel() = Unit
            }
            val manager = RecordingAsrModelManager(f.root, f.scope, fake, { f.vocab.inputStream() }, spec) { 0 }
            manager.start()
            assertEquals(RecordingAsrModelState.Failed(R.string.recording_asr_storage), done(manager, f))
            assertFalse(started)
            assertNull(manager.unknownCharacters("张"))
        } finally { f.close() }
    }

    @Test fun cancellationPreservesPartAndNextManagerResumesIt() = runBlocking {
        val f = Fixture()
        try {
            val spec = spec(f)
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val fake = object : RecordingAsrDownload {
                override suspend fun download(spec: RecordingAsrPackage, directory: File, progress: (Long, Long) -> Unit): File {
                    directory.mkdirs(); File(directory, "${spec.archiveName}.part").writeBytes(byteArrayOf(1, 2))
                    started.complete(Unit)
                    try { awaitCancellation() } finally { stopped.complete(Unit) }
                }
                override fun cancel() = Unit
            }
            val manager = RecordingAsrModelManager(f.root, f.scope, fake, { f.vocab.inputStream() }, spec) { Long.MAX_VALUE }
            manager.start(); withTimeout(5_000) { started.await() }; manager.cancel(); withTimeout(5_000) { stopped.await() }
            withTimeout(5_000) { f.scope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
            assertEquals(2L, File(f.root, ".download/${spec.archiveName}.part").length())
            var resumed = false
            val resume = object : RecordingAsrDownload {
                override suspend fun download(spec: RecordingAsrPackage, directory: File, progress: (Long, Long) -> Unit): File {
                    resumed = File(directory, "${spec.archiveName}.part").length() == 2L
                    return copied(f).download(spec, directory, progress)
                }
                override fun cancel() = Unit
            }
            val next = RecordingAsrModelManager(f.root, f.scope, resume, { f.vocab.inputStream() }, spec) { Long.MAX_VALUE }
            next.start(); assertEquals(RecordingAsrModelState.Installed, done(next, f)); assertTrue(resumed)
        } finally { f.close() }
    }

    @Test fun startupRejectsMissingOrWrongSizedFilesAndMatchingVersionIsRequired() = runBlocking {
        val f = Fixture()
        try {
            val spec = spec(f)
            val dir = File(f.root, spec.version).also { it.mkdirs() }
            f.payload.forEach { (name, bytes) -> File(dir, name).writeBytes(bytes) }
            File(dir, "bpe.vocab").writeBytes(f.vocab)
            File(dir, "version").writeText(spec.version)
            val manager = RecordingAsrModelManager(f.root, f.scope, copied(f), { f.vocab.inputStream() }, spec)
            assertEquals(RecordingAsrModelState.Installed, done(manager, f))
            File(dir, "tokens.txt").delete(); assertFalse(manager.isInstalled())
            File(dir, "tokens.txt").writeText("short"); assertFalse(manager.isInstalled())
        } finally { f.close() }
    }
    @Test fun immediateRetryWaitsForCancelledInstallToFinishCleanup() = runBlocking {
        val f = Fixture()
        val released = java.util.concurrent.CountDownLatch(1)
        try {
            val spec = spec(f)
            val entered = CompletableDeferred<Unit>()
            var calls = 0
            val fake = object : RecordingAsrDownload {
                override suspend fun download(spec: RecordingAsrPackage, directory: File, progress: (Long, Long) -> Unit): File {
                    calls++
                    if (calls == 1) {
                        entered.complete(Unit)
                        check(released.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        currentCoroutineContext().ensureActive()
                    }
                    return copied(f).download(spec, directory, progress)
                }
                override fun cancel() = Unit
            }
            val manager = RecordingAsrModelManager(f.root, f.scope, fake, { f.vocab.inputStream() }, spec) { Long.MAX_VALUE }
            manager.start(); withTimeout(5_000) { entered.await() }
            manager.cancel(); manager.start()
            assertEquals(1, calls)
            released.countDown()
            withTimeout(5_000) { f.scope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
            manager.start()
            assertEquals(RecordingAsrModelState.Installed, done(manager, f))
            assertEquals(2, calls)
            assertFalse(File(f.root, ".${spec.version}.install").exists())
        } finally { released.countDown(); f.close() }
    }

}
