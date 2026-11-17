package com.promenar.infinity.recording

import android.app.Application
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.local.inference.SlotState
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeoutException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class RecordingControllerOrganizeTest {
    private val root = Files.createTempDirectory("recording-organize-").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app = mockk<InfinityApplication>(relaxed = true)
    private val original = RecordingSession(UUID.randomUUID().toString(), 1, "标题", phase = RecordingPhase.Finished,
        audioBytes = 32_000, mode = RecordingMode.Local, segments = listOf(RecordingSegment(0, 1000, "讨论事项")))

    @Before fun setup() {
        every { app.filesDir } returns root
        every { app.noBackupFilesDir } returns root.resolve("no-backup").also { it.mkdirs() }
        every { app.getSharedPreferences(any(), any()) } answers { context.getSharedPreferences("${root.name}-${firstArg<String>()}", 0) }
        every { app.assets } returns context.assets
        mockkConstructor(RecordingModel::class)
        every { anyConstructed<RecordingModel>().cloud() } returns null
        every { anyConstructed<RecordingModel>().local() } returns SlotState(isLoaded = true, modelName = "本地模型", modelPath = "local")
        val lifecycle = mockk<Lifecycle>()
        every { lifecycle.currentStateFlow } returns MutableStateFlow(Lifecycle.State.STARTED)
        val owner = mockk<LifecycleOwner>()
        every { owner.lifecycle } returns lifecycle
        mockkObject(ProcessLifecycleOwner.Companion)
        every { ProcessLifecycleOwner.get() } returns owner
    }

    @After fun teardown() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        unmockkConstructor(RecordingModel::class)
        unmockkObject(ProcessLifecycleOwner.Companion)
        root.deleteRecursively()
        Unit
    }

    private suspend fun seed(item: RecordingSession = original) {
        val store = RecordingStore(app)
        store.write(item)
        RandomAccessFile(store.audio(item.id), "rw").use { it.setLength(44 + item.audioBytes); updateRecordingWavHeader(it, item.audioBytes) }
    }

    private fun controller(
        enabled: Boolean = true,
        scope: CoroutineScope = this.scope,
        decoderFactory: RecordingDecoderFactory = RecordingDecoderFactory { error("不应启动识别") },
    ) = RecordingController(app, scope, decoderFactory, organizationEnabled = enabled,
        publishDocument = { _, _ -> RecordingDocument("doc", "hash") }, recognizeWithWakeLock = { it() })

    @Suppress("UNCHECKED_CAST")
    private fun mutableState(controller: RecordingController) =
        RecordingController::class.java.getDeclaredField("_state").also { it.isAccessible = true }
            .get(controller) as MutableStateFlow<RecordingScreenState>

    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(5000) { while (!condition()) delay(5) }

    private suspend fun schedule(controller: RecordingController) {
        val lock = RecordingController::class.java.getDeclaredField("lock").also { it.isAccessible = true }.get(controller) as Mutex
        lock.withLock {
            RecordingController::class.java.getDeclaredMethod("scheduleOrganization").also { it.isAccessible = true }.invoke(controller)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun finishSchedulesImmediatelyAfterReleasingThePausedRecording() = runBlocking {
        seed()
        every { anyConstructed<RecordingModel>().local() } returns null
        // 虚拟时间保持在零，周期调度器无法越过三秒；前置阶段必须主动接棒。
        val immediateScope = CoroutineScope(scope.coroutineContext + UnconfinedTestDispatcher())
        val controller = controller(scope = immediateScope)
        withTimeout(5000) { controller.state.first { it.ready } }
        val state = mutableState(controller)
        state.value = state.value.copy(activeId = original.id,
            sessions = state.value.sessions.map { it.copy(phase = RecordingPhase.Paused) })
        val entered = CompletableDeferred<Unit>()
        coEvery { anyConstructed<RecordingModel>().summarizeBatch(any(), any(), any(), any(), any()) } coAnswers {
            assertNull(controller.state.value.activeId)
            assertEquals(RecordingPhase.Finished, firstArg<RecordingSession>().phase)
            entered.complete(Unit)
            awaitCancellation()
        }
        every { anyConstructed<RecordingModel>().local() } returns SlotState(isLoaded = true, modelName = "本地模型", modelPath = "local")
        val idle = CompletableDeferred<Unit>()
        controller.finish { idle.complete(Unit) }
        withTimeout(2000) { idle.await(); entered.await() }
        assertEquals(original.id, controller.state.value.organizingId)
        assertEquals(original.id, controller.state.value.organizeRun?.sessionId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun recognitionRewriteClearsFailureAndCancellationThenSchedulesAfterReleasingRecognition() = runBlocking {
        val failed = original.copy(organized = "旧稿", organizedThrough = 1,
            lastOrganizeFailure = RecordingOrganizeFailure(RecordingOrganizeErrorKind.Timeout, failedAt = 1))
        seed(failed)
        val spec = RecordingAsrPackage.CURRENT
        val directory = root.resolve("asr-models/${spec.version}").also { it.mkdirs() }
        (spec.files + spec.vocab).forEach { expected ->
            RandomAccessFile(directory.resolve(expected.name), "rw").use { it.setLength(expected.bytes) }
        }
        directory.resolve("tokens.txt").writeBytes(ByteArray(spec.files.last().bytes.toInt()) { '\n'.code.toByte() })
        directory.resolve("version").writeText(spec.version)
        every { anyConstructed<RecordingModel>().local() } returns null
        val immediateScope = CoroutineScope(scope.coroutineContext + UnconfinedTestDispatcher())
        val decoder = object : RecordingDecoder {
            override fun accept(pcm: ShortArray) = RecordingRecognition("新逐字稿")
            override fun reset() = Unit
            override fun finish() = "新逐字稿"
        }
        val controller = controller(scope = immediateScope, decoderFactory = RecordingDecoderFactory { decoder })
        withTimeout(5000) {
            controller.state.first { it.ready }
            controller.asrModel.first { it == RecordingAsrModelState.Installed }
        }
        controller.cancelOrganize(original.id)
        waitFor { original.id in controller.state.value.organizeCanceledIds }
        val entered = CompletableDeferred<Unit>()
        coEvery { anyConstructed<RecordingModel>().summarizeBatch(any(), any(), any(), any(), any()) } coAnswers {
            assertNull(controller.state.value.recognizingId)
            assertNull(firstArg<RecordingSession>().lastOrganizeFailure)
            assertFalse(original.id in controller.state.value.organizeCanceledIds)
            entered.complete(Unit)
            awaitCancellation()
        }
        every { anyConstructed<RecordingModel>().local() } returns SlotState(isLoaded = true, modelName = "本地模型", modelPath = "local")
        controller.recognize(original.id, rewriteOrganized = true)
        withTimeout(2000) { entered.await() }
        val recognized = controller.state.value.sessions.single()
        assertEquals("新逐字稿", recognized.segments.single().text)
        assertEquals("旧稿", recognized.organized)
        assertTrue(recognized.summaryPending)
        assertNull(RecordingStore(app).restore().single().lastOrganizeFailure)
        assertEquals(original.id, controller.state.value.organizingId)
    }

    @Test fun pointsOverviewAndPublishContinueImmediatelyWithStableStartTime() = runBlocking {
        seed()
        val pointEntered = CompletableDeferred<Unit>()
        val pointRelease = CompletableDeferred<Unit>()
        val overviewEntered = CompletableDeferred<Unit>()
        val overviewRelease = CompletableDeferred<Unit>()
        coEvery { anyConstructed<RecordingModel>().summarizeBatch(any(), any(), any(), any(), any()) } coAnswers {
            pointEntered.complete(Unit); pointRelease.await()
            secondArg<RecordingSummaryProgress>().copy(cursor = 1, points = listOf(RecordingSummaryPoint(null, "讨论事项", listOf(0))))
        }
        coEvery { anyConstructed<RecordingModel>().advanceOverview(any(), any(), any(), any()) } coAnswers {
            overviewEntered.complete(Unit); overviewRelease.await()
            secondArg<RecordingSummaryProgress>().copy(overviewCursor = 1, overview = "会议摘要")
        }
        val controller = controller()
        withTimeout(5000) { pointEntered.await() }
        val first = controller.state.value.organizeRun!!
        assertEquals(RecordingOrganizeStage.Points, first.stage)
        assertEquals(3, first.totalSteps)
        val snapshots = CopyOnWriteArrayList<RecordingScreenState>()
        val observer = scope.launch(Dispatchers.Unconfined) { controller.state.collect { snapshots += it } }
        pointRelease.complete(Unit)
        withTimeout(2000) { overviewEntered.await() }
        val second = controller.state.value.organizeRun!!
        assertEquals(RecordingOrganizeStage.Overview, second.stage)
        assertEquals(first.startedAtEpochMs, second.startedAtEpochMs)
        assertEquals(original.id, controller.state.value.organizingId)
        overviewRelease.complete(Unit)
        waitFor { controller.state.value.sessions.single().organizedSource != null && controller.state.value.organizeRun == null }
        val completed = controller.state.value.sessions.single()
        assertFalse(completed.summaryPending)
        assertNull(completed.lastOrganizeFailure)
        assertEquals(1, completed.organizedThrough)
        assertEquals(RecordingOrganizeEngine(false, model = "本地模型"), completed.organizedSource!!.engine)
        assertEquals(original.id, controller.state.value.organizeUpdatedId)
        assertEquals(completed.organizedSource, RecordingStore(app).restore().single().organizedSource)
        observer.cancelAndJoin()
        assertTrue(snapshots.takeWhile { it.sessions.single().organizedSource == null }.all {
            it.organizingId == original.id && it.organizeRun?.startedAtEpochMs == first.startedAtEpochMs
        })
        assertTrue(snapshots.any { it.organizeRun?.stage == RecordingOrganizeStage.Publish })
        withTimeout(8000) { controller.state.first { it.organizeUpdatedId == null }; Unit }
    }

    @Test fun classifiesFailuresPersistsThemAndPreservesOldTextWithoutSnackbar() = runBlocking {
        val errors = listOf(
            TimeoutException() to RecordingOrganizeErrorKind.Timeout,
            RecordingOutputLanguageMismatch() to RecordingOrganizeErrorKind.LanguageMismatch,
            RecordingEmptyOutputException() to RecordingOrganizeErrorKind.EmptyOutput,
            IOException() to RecordingOrganizeErrorKind.StorageError,
            IllegalStateException() to RecordingOrganizeErrorKind.ModelError,
        )
        val item = original.copy(organized = "旧稿", summaryPending = true)
        seed(item)
        val controller = controller(enabled = false)
        withTimeout(5000) { controller.state.first { it.ready } }
        // 显式触发调度，隔离轮询和失败冷却对分类测试的影响。
        for ((exception, kind) in errors) {
            coEvery { anyConstructed<RecordingModel>().summarizeBatch(any(), any(), any(), any(), any()) } throws exception
            if (controller.state.value.sessions.single().lastOrganizeFailure != null) {
                controller.retry(item.id)
                waitFor { controller.state.value.sessions.single().lastOrganizeFailure == null }
            }
            schedule(controller)
            waitFor { controller.state.value.sessions.single().lastOrganizeFailure?.kind == kind && controller.state.value.organizeRun == null }
            val failed = controller.state.value.sessions.single()
            assertEquals("旧稿", failed.organized)
            assertNull(controller.state.value.failure)
            assertEquals(60_000L, failed.lastOrganizeFailure!!.retryAtEpochMs!! - failed.lastOrganizeFailure.failedAt)
            assertEquals(failed.lastOrganizeFailure, RecordingStore(app).restore().single().lastOrganizeFailure)
        }
    }

    @Test fun cancelStopsWorkUntilRetryAndOldFinallyCannotClearSuccessor() = runBlocking {
        seed()
        val firstEntered = CompletableDeferred<Unit>()
        val canceled = CompletableDeferred<Unit>()
        val letOldFinish = CompletableDeferred<Unit>()
        var calls = 0
        val secondEntered = CompletableDeferred<Unit>()
        coEvery { anyConstructed<RecordingModel>().summarizeBatch(any(), any(), any(), any(), any()) } coAnswers {
            if (++calls == 1) {
                firstEntered.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { canceled.complete(Unit); letOldFinish.await() } }
            } else { secondEntered.complete(Unit); awaitCancellation() }
        }
        try {
            val controller = controller()
            withTimeout(5000) { firstEntered.await() }
            controller.cancelOrganize(original.id)
            withTimeout(5000) { canceled.await() }
            waitFor { original.id in controller.state.value.organizeCanceledIds }
            assertNull(controller.state.value.organizeRun)
            controller.retry(original.id)
            withTimeout(2000) { secondEntered.await() }
            val successor = controller.state.value.organizeRun
            letOldFinish.complete(Unit)
            delay(100)
            assertEquals(successor, controller.state.value.organizeRun)
            assertEquals(original.id, controller.state.value.organizingId)
            assertFalse(original.id in controller.state.value.organizeCanceledIds)
            controller.cancelOrganize(original.id)
            waitFor { controller.state.value.organizeRun == null }
        } finally { letOldFinish.complete(Unit) }
    }

    @Test fun explicitActionsClearFailureCancellationAndRetryState() = runBlocking {
        val failure = RecordingOrganizeFailure(RecordingOrganizeErrorKind.Timeout, failedAt = 1)
        val old = original.copy(lastOrganizeFailure = failure, summaryPending = true)
        seed(old)
        val controller = controller(enabled = false)
        withTimeout(5000) { controller.state.first { it.ready } }
        controller.cancelOrganize(old.id)
        waitFor { old.id in controller.state.value.organizeCanceledIds && !controller.state.value.sessions.single().summaryPending }
        assertFalse(controller.state.value.sessions.single().summaryPending)
        schedule(controller)
        assertNull(controller.state.value.organizeRun)
        controller.switchToLocalAndRetry(old.id)
        waitFor { old.id !in controller.state.value.organizeCanceledIds }
        val retried = controller.state.value.sessions.single()
        assertEquals(RecordingMode.Local, retried.mode)
        assertNull(retried.cloudConsent)
        assertNull(retried.lastOrganizeFailure)
        assertTrue(retried.summaryPending)
        assertNull(retried.summaryProgress)
    }

    private suspend fun verifyActionReschedules(action: (RecordingController, String) -> Unit) {
        every { anyConstructed<RecordingModel>().local() } returns null
        seed(original.copy(lastOrganizeFailure = RecordingOrganizeFailure(RecordingOrganizeErrorKind.Timeout, failedAt = 1)))
        val controller = controller()
        withTimeout(5000) { controller.state.first { it.ready } }
        controller.cancelOrganize(original.id)
        waitFor { original.id in controller.state.value.organizeCanceledIds }
        every { anyConstructed<RecordingModel>().local() } returns SlotState(isLoaded = true, modelName = "本地模型", modelPath = "local")
        val entered = CompletableDeferred<Unit>()
        coEvery { anyConstructed<RecordingModel>().summarizeBatch(any(), any(), any(), any(), any()) } coAnswers {
            entered.complete(Unit); awaitCancellation()
        }
        action(controller, original.id)
        withTimeout(2000) { entered.await() }
        assertFalse(original.id in controller.state.value.organizeCanceledIds)
        assertNull(controller.state.value.sessions.single().lastOrganizeFailure)
        assertNull(RecordingStore(app).restore().single().lastOrganizeFailure)
    }

    @Test fun retryClearsFailureAndSchedulesWithoutPolling() = runBlocking {
        verifyActionReschedules { controller, id -> controller.retry(id, RecordingLanguage.English) }
    }

    @Test fun setModeClearsFailureAndSchedulesWithoutPolling() = runBlocking {
        verifyActionReschedules { controller, id -> controller.setMode(id, RecordingMode.Local) }
    }

    @Test fun allowCloudClearsFailureAndSchedulesWithoutPolling() = runBlocking {
        verifyActionReschedules { controller, id -> controller.allowCloud(id, "consent") }
    }

    @Test fun configureClearsFailureAndSchedulesWithoutPolling() = runBlocking {
        verifyActionReschedules { controller, id -> controller.configure(id, RecordingMode.Local, RecordingLanguage.Chinese, true, false) }
    }

    @Test fun updateDraftClearsFailureAndSchedulesWithoutPolling() = runBlocking {
        verifyActionReschedules { controller, id -> controller.updateDraft(id, "新标题", emptyList(), false, null) }
    }

    @Test fun switchToLocalClearsFailureAndSchedulesWithoutPolling() = runBlocking {
        verifyActionReschedules { controller, id -> controller.switchToLocalAndRetry(id) }
    }

    @Test fun storageFailureStillKeepsStructuredModelFailureInMemory() = runBlocking {
        // 使用已发布快照，隔离恢复时的资料发布，单独验证整理失败的写盘兜底。
        seed(original.copy(documentId = "doc", documentHash =
            com.promenar.infinity.infra.util.Sha256Utils.hash(recordingMarkdown(original).toByteArray())))
        mockkConstructor(RecordingStore::class)
        try {
            coEvery { anyConstructed<RecordingStore>().write(any()) } throws IOException("写入失败")
            coEvery { anyConstructed<RecordingModel>().summarizeBatch(any(), any(), any(), any(), any()) } throws RecordingEmptyOutputException()
            val controller = controller(enabled = false)
            withTimeout(5000) { controller.state.first { it.ready } }
            schedule(controller)
            waitFor { controller.state.value.sessions.single().lastOrganizeFailure != null && controller.state.value.organizeRun == null }
            assertEquals(RecordingOrganizeErrorKind.EmptyOutput, controller.state.value.sessions.single().lastOrganizeFailure!!.kind)
            assertNull(controller.state.value.failure)
        } finally { unmockkConstructor(RecordingStore::class) }
    }

    @Test fun liveDraftExposesCoverageAndLagWithoutPersistingTransientFailure() = runBlocking {
        seed()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { anyConstructed<RecordingModel>().organize(any(), any(), any(), any(), any(), any()) } coAnswers {
            entered.complete(Unit); release.await(); throw IOException("临时草稿失败")
        }
        val controller = controller(enabled = false)
        withTimeout(5000) { controller.state.first { it.ready } }
        @Suppress("UNCHECKED_CAST")
        val state = RecordingController::class.java.getDeclaredField("_state").also { it.isAccessible = true }
            .get(controller) as MutableStateFlow<RecordingScreenState>
        state.value = state.value.copy(activeId = original.id, durationMs = 7000)
        schedule(controller)
        withTimeout(5000) { entered.await() }
        val run = controller.state.value.organizeRun!!
        assertEquals(RecordingOrganizeStage.LiveDraft, run.stage)
        assertEquals(1, run.step)
        assertEquals(1, run.totalSteps)
        assertEquals(1000L, run.coveredAudioMs)
        assertEquals(6000L, run.lagMs)
        release.complete(Unit)
        waitFor { controller.state.value.organizeRun == null }
        assertNull(controller.state.value.sessions.single().lastOrganizeFailure)
        assertNull(controller.state.value.failure)
    }

    @Test fun cloudCompletionRecordsProviderModelAndPublishedLanguage() = runBlocking {
        val item = original.copy(mode = RecordingMode.Auto, cloudConsent = "key", outputLanguage = RecordingLanguage.English)
        seed(item)
        every { anyConstructed<RecordingModel>().cloud() } returns RecordingModel.Cloud("key", "提供商 · 云模型", mockk())
        coEvery { anyConstructed<RecordingModel>().summarizeBatch(any(), any(), any(), any(), any()) } coAnswers {
            secondArg<RecordingSummaryProgress>().copy(cursor = 1, points = listOf(RecordingSummaryPoint(null, "Discussion", listOf(0))))
        }
        coEvery { anyConstructed<RecordingModel>().advanceOverview(any(), any(), any(), any()) } coAnswers {
            secondArg<RecordingSummaryProgress>().copy(overviewCursor = 1, overview = "Overview")
        }
        val controller = controller()
        waitFor { controller.state.value.sessions.firstOrNull()?.organizedSource != null }
        val completed = controller.state.value.sessions.single()
        assertEquals(RecordingOrganizeEngine(true, "提供商", "云模型"), completed.organizedSource!!.engine)
        assertEquals(RecordingLanguage.English, completed.organizedSource.language)
        assertEquals(RecordingLanguage.English, completed.publishedLanguage)
        assertTrue(completed.organizedSource.completedAt > 0)
        assertNull(completed.summaryProgress)
        assertEquals(emptyList<RecordingDraftBatch>(), completed.draftBatches)
    }
}
