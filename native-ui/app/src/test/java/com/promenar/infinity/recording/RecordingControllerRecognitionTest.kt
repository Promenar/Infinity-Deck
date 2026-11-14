package com.promenar.infinity.recording

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class RecordingControllerRecognitionTest {
    private class Fixture {
        val root = Files.createTempDirectory("recording-controller-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = mockk<InfinityApplication>(relaxed = true)
        val original = RecordingSession(UUID.randomUUID().toString(), 1, "标题", phase = RecordingPhase.Finished,
            audioBytes = 640 * 2L * 4, segments = listOf(RecordingSegment(0, 100, "旧逐字稿")), organized = "手工整理稿",
            organizedThrough = 1, manualEdit = true, documentId = "doc").let { item ->
            item.copy(documentHash = com.promenar.infinity.infra.util.Sha256Utils.hash(recordingMarkdown(item).toByteArray()))
        }
        val published = AtomicInteger()
        init {
            every { app.filesDir } returns root
            every { app.noBackupFilesDir } returns root.resolve("no-backup").also { it.mkdirs() }
            every { app.getSharedPreferences(any(), any()) } answers {
                context.getSharedPreferences("${root.name}-${firstArg<String>()}", 0)
            }
            every { app.assets } returns context.assets
        }
        suspend fun seed(item: RecordingSession = original) {
            val store = RecordingStore(app)
            store.write(item)
            RandomAccessFile(store.audio(item.id), "rw").use { file ->
                file.setLength(44 + item.audioBytes)
                updateRecordingWavHeader(file, item.audioBytes)
            }
        }
        fun install() {
            val spec = RecordingAsrPackage.CURRENT
            val directory = File(root, "asr-models/${spec.version}").also { it.mkdirs() }
            (spec.files + spec.vocab).forEach { expected ->
                RandomAccessFile(File(directory, expected.name), "rw").use { it.setLength(expected.bytes) }
            }
            File(directory, "tokens.txt").writeBytes(ByteArray(spec.files.last().bytes.toInt()) { '\n'.code.toByte() })
            File(directory, "version").writeText(spec.version)
        }
        fun controller(
            factory: RecordingDecoderFactory = RecordingDecoderFactory { decoder() },
            capture: RecordingAudioCapture = { _, _, _, _, _, _, _, _ -> error("不应打开麦克风") },
            publish: suspend (RecordingSession) -> RecordingDocument = { published.incrementAndGet(); RecordingDocument("doc", "hash") },
        ) = RecordingController(app, scope, factory, capture, false, { item, _ -> publish(item) }, { it() })
        suspend fun close() { scope.coroutineContext[Job]!!.cancelAndJoin(); root.deleteRecursively() }
    }
    companion object {
        private fun decoder() = object : RecordingDecoder {
            override fun accept(pcm: ShortArray) = RecordingRecognition("新逐字稿")
            override fun reset() = Unit
            override fun finish() = "新逐字稿"
        }
        private suspend fun waitFor(condition: () -> Boolean) = withTimeout(5_000) {
            while (!condition()) delay(5)
        }
    }

    @Test fun batchDeletionUsesTheConfirmedSnapshotAndPreservesUnselectedAndFailedRecordings() = runBlocking {
        val f = Fixture()
        try {
            val keep = f.original.copy(id = UUID.randomUUID().toString(), title = "保留")
            val interrupted = f.original.copy(id = UUID.randomUUID().toString(), title = "中断", phase = RecordingPhase.Interrupted)
            val blocked = f.original.copy(id = UUID.randomUUID().toString(), title = "暂时无法删除")
            for (item in listOf(f.original, keep, interrupted, blocked)) f.seed(item)
            val publishing = CompletableDeferred<Unit>()
            val published = CompletableDeferred<Unit>()
            val controller = f.controller(publish = {
                publishing.complete(Unit)
                published.await()
                RecordingDocument("doc", "hash")
            })
            withTimeout(5_000) { controller.state.first { it.ready } }
            controller.publish(keep.id)
            withTimeout(5_000) { publishing.await() }
            val occupied = f.root.resolve("recording-sessions/.deleted-${blocked.id}").also { it.writeText("冲突") }
            val request = mutableListOf(f.original.id, blocked.id, interrupted.id, interrupted.id, "missing")
            val deleting = controller.deleteBatch(request)
            request.clear()
            request += keep.id
            published.complete(Unit)
            val result = withTimeout(5_000) { deleting.await() }
            assertEquals(setOf(f.original.id, interrupted.id), result.deleted)
            assertEquals(setOf(blocked.id), result.failed)
            assertEquals(1, result.unavailable)
            assertTrue(controller.store.audio(keep.id).isFile)
            assertTrue(controller.store.audio(blocked.id).isFile)
            assertFalse(controller.store.audio(interrupted.id).exists())
            assertEquals(setOf(keep.id, blocked.id), controller.store.restore().map { it.id }.toSet())
            assertTrue(occupied.delete())
            assertEquals(setOf(blocked.id), withTimeout(5_000) { controller.deleteBatch(listOf(blocked.id)).await() }.deleted)
        } finally { f.close() }
    }

    @Test fun batchDeletionRechecksEligibilityWhenASelectedRecordingHasResumed() = runBlocking {
        val f = Fixture()
        try {
            val other = f.original.copy(id = UUID.randomUUID().toString(), title = "可删除")
            f.seed()
            f.seed(other)
            val capturing = CompletableDeferred<Unit>()
            val controller = f.controller(capture = { _, _, _, _, _, onCaptureStarted, _, _ ->
                onCaptureStarted()
                capturing.complete(Unit)
                awaitCancellation()
            })
            withTimeout(5_000) { controller.state.first { it.ready } }
            controller.capture(f.original.id) {}
            withTimeout(5_000) { capturing.await() }
            val result = withTimeout(5_000) { controller.deleteBatch(listOf(f.original.id, other.id)).await() }
            assertEquals(setOf(other.id), result.deleted)
            assertTrue(result.failed.isEmpty())
            assertEquals(1, result.unavailable)
            assertEquals(f.original.id, controller.state.value.activeId)
            assertTrue(controller.store.audio(f.original.id).isFile)
        } finally { f.close() }
    }

    @Test fun bothWritebackModesCommitSegmentsAndPublish() = runBlocking {
        for (rewrite in listOf(false, true)) {
            val f = Fixture()
            try {
                f.seed(); f.install()
                val controller = f.controller()
                withTimeout(5_000) { controller.state.first { it.ready }; controller.asrModel.first { it == RecordingAsrModelState.Installed } }
                controller.recognize(f.original.id, rewrite)
                waitFor { controller.state.value.sessions.single().segments.firstOrNull()?.text == "新逐字稿" }
                val changed = controller.state.value.sessions.single()
                assertEquals("手工整理稿", changed.organized)
                assertEquals(!rewrite, changed.manualEdit)
                assertEquals(if (rewrite) 0 else null, changed.reprocessThrough)
                assertEquals("", changed.reprocessText)
                assertNull(changed.checkpointText)
                if (!rewrite) assertEquals(changed.segments.size, changed.organizedThrough)
                waitFor { f.published.get() > 0 }
            } finally { f.close() }
        }
    }

    @Test fun continuingFinishedRecordingAppendsAudioAndKeepsItsDocumentAndManualDraft() = runBlocking {
        val f = Fixture()
        try {
            f.seed()
            val captured = CompletableDeferred<RecordingSession>()
            val controller = f.controller(capture = { _, store, item, _, _, onCaptureStarted, _, emit ->
                onCaptureStarted()
                captured.complete(item)
                val end = item.audioBytes + 640 * 2L
                RandomAccessFile(store.audio(item.id), "rw").use { file ->
                    assertEquals(44 + f.original.audioBytes, file.length())
                    file.seek(file.length())
                    file.write(ByteArray(640 * 2) { 17 })
                    updateRecordingWavHeader(file, end)
                }
                val segment = RecordingSegment(item.durationMs, end / 32, "新增逐字稿")
                emit(CaptureFrame(end, "", null, segment, quiet = true, checkpoint = true))
                CaptureCommand.Finish
            })
            withTimeout(5_000) { controller.state.first { it.ready } }
            controller.capture(f.original.id) {}
            val resumed = withTimeout(2_000) { captured.await() }
            assertEquals(f.original.copy(phase = RecordingPhase.Recording), resumed)
            val saved = withTimeout(5_000) {
                controller.state.first { it.activeId == null && it.sessions.single().audioBytes > f.original.audioBytes }
            }.sessions.single()
            assertEquals(f.original.id, saved.id)
            assertEquals(f.original.segments.single(), saved.segments.first())
            assertEquals(f.original.organized, saved.organized)
            assertTrue(saved.manualEdit)
            assertEquals(f.original.documentId, saved.documentId)
            assertEquals(RecordingPhase.Finished, saved.phase)
            assertEquals(44 + saved.audioBytes, RecordingStore(f.app).audio(saved.id).length())
            waitFor { f.published.get() > 0 }
        } finally { f.close() }
    }

    @Test fun failedAndCancelledRecognitionLeaveSessionUnchanged() = runBlocking {
        for (cancel in listOf(false, true)) {
            val f = Fixture()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                f.seed(); f.install()
                val controller = f.controller(RecordingDecoderFactory {
                    object : RecordingDecoder {
                        override fun accept(pcm: ShortArray): RecordingRecognition {
                            entered.countDown()
                            if (!cancel) error("假解码失败")
                            check(release.await(5, TimeUnit.SECONDS))
                            return RecordingRecognition("不会提交")
                        }
                        override fun reset() = Unit
                        override fun finish() = "不会提交"
                    }
                })
                withTimeout(5_000) { controller.state.first { it.ready }; controller.asrModel.first { it == RecordingAsrModelState.Installed } }
                controller.recognize(f.original.id, true)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                if (cancel) { controller.cancelRecognize(); release.countDown() }
                waitFor { controller.state.value.recognizingId == null && (cancel || controller.state.value.failure == R.string.recording_recognize_failed) }
                assertEquals(f.original, controller.state.value.sessions.single())
                assertEquals(0, f.published.get())
            } finally { release.countDown(); f.close() }
        }
    }

    @Test fun activeRecordingIgnoresRecognitionAndMissingModelStillSavesAudioAndCheckpoints() = runBlocking<Unit> {
        val f = Fixture()
        val stop = CompletableDeferred<Unit>()
        try {
            val item = f.original.copy(phase = RecordingPhase.Interrupted, audioBytes = 0, segments = emptyList(), manualEdit = true)
            f.seed(item)
            val captured = CompletableDeferred<RecordingDecoderRequest>()
            val firstCheckpoint = CompletableDeferred<Unit>()
            val factory = RecordingDecoderFactory { request -> captured.complete(request); createRecordingDecoder(request) }
            val capture: RecordingAudioCapture = { _, store, session, _, withDecoder, onCaptureStarted, waveform, frame ->
                var blocks = 0
                val source = object : RecordingAudioSource {
                    override fun start() = Unit
                    override fun read(buffer: ShortArray): Int { buffer.fill(100); blocks++; return buffer.size }
                    override fun stop() = Unit
                }
                RandomAccessFile(store.audio(session.id), "rw").use { file ->
                    recordPcmAudio(source, file, 0, 160_000,
                        command = { if (blocks < 125) CaptureCommand.Continue else CaptureCommand.Finish },
                        withDecoder = withDecoder,
                        onCaptureStarted = onCaptureStarted,
                        waveform = waveform,
                        onFrame = { current -> frame(current); if (current.checkpoint) firstCheckpoint.complete(Unit) })
                }
                stop.await()
                CaptureCommand.Finish
            }
            val controller = f.controller(factory, capture)
            withTimeout(5_000) { controller.state.first { it.ready } }
            controller.capture(item.id) {}
            assertNull(withTimeout(5_000) { captured.await() }.directory)
            withTimeout(5_000) { firstCheckpoint.await() }
            controller.recognize(item.id, true)
            delay(50)
            assertNull(controller.state.value.recognizingId)
            val saved = controller.state.value.sessions.single()
            assertTrue(saved.audioBytes > 0)
            assertTrue(saved.segments.isEmpty())
            assertNull(saved.checkpointText)
            assertEquals("", controller.state.value.partial)
            assertTrue(controller.state.value.audioOnly)
            assertTrue(controller.store.audio(item.id).length() > 44)
            stop.complete(Unit)
            withTimeout(5_000) { controller.state.first { it.activeId == null } }
        } finally { stop.complete(Unit); f.close() }
    }

    @Test fun microphoneStartsWhilePreviousRecognitionReleasesItsDecoder() = runBlocking {
        val f = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val captures = CompletableDeferred<Unit>()
        val live = AtomicInteger()
        val maximum = AtomicInteger()
        try {
            f.seed(f.original.copy(phase = RecordingPhase.Interrupted)); f.install()
            var creations = 0
            val factory = RecordingDecoderFactory {
                val index = ++creations
                maximum.accumulateAndGet(live.incrementAndGet()) { a, b -> maxOf(a, b) }
                object : RecordingDecoder {
                    override fun accept(pcm: ShortArray): RecordingRecognition {
                        if (index == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                        return RecordingRecognition("部分结果")
                    }
                    override fun reset() = Unit
                    override fun finish() = "结果"
                    override fun close() { live.decrementAndGet() }
                }
            }
            val controller = f.controller(factory, { _, _, _, _, withDecoder, onCaptureStarted, _, _ ->
                onCaptureStarted()
                captures.complete(Unit)
                withDecoder { CaptureCommand.Pause }
            })
            withTimeout(5_000) { controller.state.first { it.ready }; controller.asrModel.first { it == RecordingAsrModelState.Installed } }
            controller.recognize(f.original.id, true)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            controller.capture(f.original.id) {}
            withTimeout(5_000) { captures.await() }
            assertFalse(controller.state.value.opening)
            assertEquals(1, live.get())
            release.countDown()
            waitFor { controller.state.value.sessions.single().phase == RecordingPhase.Paused }
            waitFor { controller.state.value.recognizingId == null }
            assertEquals(1, maximum.get())
            assertEquals(f.original.segments, controller.state.value.sessions.single().segments)
        } finally { release.countDown(); f.close() }
    }
    @Test fun publicationMetadataUpdatesDoNotDiscardRecognizedTranscript() = runBlocking {
        val f = Fixture()
        val decoderEntered = CountDownLatch(1)
        val decoderRelease = CountDownLatch(1)
        val publishEntered = CompletableDeferred<Unit>()
        val publishRelease = CompletableDeferred<Unit>()
        try {
            f.seed(); f.install()
            val controller = f.controller(RecordingDecoderFactory {
                object : RecordingDecoder {
                    override fun accept(pcm: ShortArray): RecordingRecognition {
                        decoderEntered.countDown(); check(decoderRelease.await(5, TimeUnit.SECONDS))
                    return RecordingRecognition("新逐字稿")
                    }
                    override fun reset() = Unit
                    override fun finish() = "新逐字稿"
                }
            }, publish = {
                publishEntered.complete(Unit); publishRelease.await(); RecordingDocument("new-doc", "new-hash")
            })
            withTimeout(5_000) { controller.state.first { it.ready }; controller.asrModel.first { it == RecordingAsrModelState.Installed } }
            controller.publish(f.original.id)
            withTimeout(5_000) { publishEntered.await() }
            controller.recognize(f.original.id, false)
            assertTrue(decoderEntered.await(5, TimeUnit.SECONDS))
            publishRelease.complete(Unit)
            waitFor { controller.state.value.sessions.single().documentHash == "new-hash" }
            decoderRelease.countDown()
            waitFor { controller.state.value.sessions.single().segments.single().text == "新逐字稿" }
            assertEquals("new-hash", controller.state.value.sessions.single().documentHash)
        } finally { decoderRelease.countDown(); publishRelease.complete(Unit); f.close() }
    }

    @Test fun missingModelIgnoresRecognizeWithoutCreatingDecoder() = runBlocking {
        val f = Fixture()
        try {
            f.seed()
            val controller = f.controller(RecordingDecoderFactory { error("不应创建识别器") })
            withTimeout(5_000) { controller.state.first { it.ready } }
            controller.recognize(f.original.id, true)
            delay(50)
            assertNull(controller.state.value.recognizingId)
            assertEquals(f.original, controller.state.value.sessions.single())
        } finally { f.close() }
    }

    @Test fun activeCaptureWithInstalledModelIgnoresAnotherFinishedRecording() = runBlocking {
        val f = Fixture()
        val started = CompletableDeferred<Unit>()
        val stop = CompletableDeferred<Unit>()
        val creations = AtomicInteger()
        try {
            f.seed()
            val active = f.original.copy(id = UUID.randomUUID().toString(), phase = RecordingPhase.Interrupted)
            f.seed(active); f.install()
            val controller = f.controller(RecordingDecoderFactory { creations.incrementAndGet(); decoder() },
                { _, _, _, _, withDecoder, onCaptureStarted, _, _ ->
                    onCaptureStarted()
                    withDecoder { started.complete(Unit); stop.await(); CaptureCommand.Pause }
                })
            withTimeout(5_000) { controller.state.first { it.ready }; controller.asrModel.first { it == RecordingAsrModelState.Installed } }
            controller.capture(active.id) {}
            withTimeout(5_000) { started.await() }
            controller.recognize(f.original.id, false)
            delay(50)
            assertNull(controller.state.value.recognizingId)
            assertEquals(1, creations.get())
            assertEquals(f.original, controller.state.value.sessions.first { it.id == f.original.id })
        } finally { stop.complete(Unit); f.close() }
    }

}
