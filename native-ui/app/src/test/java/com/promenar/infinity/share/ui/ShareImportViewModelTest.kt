package com.promenar.infinity.share.ui

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ShareIntentQueue
import com.promenar.infinity.ShareLease
import com.promenar.infinity.ShareRequest
import com.promenar.infinity.share.core.ShareImportBatchResult
import com.promenar.infinity.share.core.ShareImportItem
import com.promenar.infinity.share.core.ShareImportStatus
import com.promenar.infinity.share.core.ShareImportDestination
import com.promenar.infinity.share.core.ShareImportOptions
import com.promenar.infinity.share.core.ShareKnowledgeFolder
import com.promenar.infinity.share.core.ShareLinuxAvailability
import com.promenar.infinity.share.core.ShareSessionChoice
import com.promenar.infinity.share.core.ShareTargetKind
import com.promenar.infinity.share.core.SharedFileImporter
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import com.promenar.infinity.data.rag.VectorizationQueue
import com.promenar.infinity.data.rag.VectorizationTask
import com.promenar.infinity.share.core.ShareIndexStatus
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ShareImportViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `分享错误状态只暴露稳定类型而不暴露运行时字符串`() {
        val fields = ShareImportUiState::class.java.declaredFields.map { it.name }

        assertThat(fields).contains("error")
        assertThat(fields).doesNotContain("errorMessage")
    }

    @Test
    fun `呈现失败保持可见且重试只重放呈现操作`() = runTest(dispatcher) {
        val uri = Uri.parse("content://fixture/shared.txt")
        val request = ShareRequest(
            uris = listOf(uri),
            mimeType = "text/plain",
            fingerprint = "0".repeat(64),
            canonicalSizeBytes = 1,
            requestId = "request",
        )
        val queue = mockk<ShareIntentQueue>()
        every { queue.durablePendingCount } returns MutableStateFlow(1)
        coJustRun { queue.refreshDurableCount() }
        coEvery { queue.claimNextDurably() } returns ShareLease("lease", request)
        coEvery { queue.nackDurably("lease") } returns true
        val importer = mockk<SharedFileImporter>()
        coEvery { importer.inspect(request) } returns listOf(
            ShareImportItem(uri, "shared.txt", "text/plain", 4),
        )
        val attempts = AtomicInteger(0)
        val viewModel = ShareImportViewModel(
            queue = queue,
            importer = importer,
            targets = FakeTargets(options = {
                if (attempts.incrementAndGet() == 1) throw IOException("targets unavailable")
                KNOWLEDGE_ONLY
            }),
        )

        viewModel.presentNext()

        assertThat(viewModel.state.value.visible).isTrue()
        assertThat(viewModel.state.value.error).isEqualTo(ShareImportErrorCode.PRESENT_FAILED)
        assertThat(viewModel.state.value.presentation).isEqualTo(SharePresentationState.Idle)

        viewModel.retryLastFailure()

        assertThat(viewModel.state.value.visible).isTrue()
        assertThat(viewModel.state.value.error).isNull()
        assertThat(viewModel.state.value.presentation).isEqualTo(SharePresentationState.Visible)
        coVerify(exactly = 2) { queue.claimNextDurably() }
        coVerify(exactly = 1) { importer.inspect(request) }
        coVerify(exactly = 0) { importer.import(any(), any<ShareImportDestination>(), any()) }
    }

    @Test
    fun `稍后处理失败的重试不会误触发导入`() = runTest(dispatcher) {
        val fixture = fixture(null)
        fixture.viewModel.presentNext()
        coEvery { fixture.queue.nackDurably("lease") } returnsMany listOf(false, true)

        fixture.viewModel.postpone()
        assertThat(fixture.viewModel.state.value.error)
            .isEqualTo(ShareImportErrorCode.POSTPONE_FAILED)

        fixture.viewModel.retryLastFailure()

        assertThat(fixture.viewModel.state.value.visible).isFalse()
        assertThat(fixture.viewModel.state.value.error).isNull()
        coVerify(exactly = 2) { fixture.queue.nackDurably("lease") }
        coVerify(exactly = 0) { fixture.importer.import(any(), any<ShareImportDestination>(), any()) }
    }

    @Test
    fun `持久化结果完成前不ack且导入中不可关闭`() = runTest(dispatcher) {
        val uri = Uri.parse("content://fixture/shared.txt")
        val queue = mockk<ShareIntentQueue>()
        val request = ShareRequest(
            uris = listOf(uri),
            mimeType = "text/plain",
            fingerprint = "0".repeat(64),
            canonicalSizeBytes = 1,
            requestId = "request",
        )
        val pendingCount = MutableStateFlow(1)
        every { queue.durablePendingCount } returns pendingCount
        coJustRun { queue.refreshDurableCount() }
        coEvery { queue.claimNextDurably() } returns ShareLease("lease", request)
        coJustRun { queue.recordTargetDurably(any(), any<ShareImportDestination>()) }
        coJustRun { queue.recordCreatedDurably(any(), any()) }
        coEvery { queue.ackDurably("lease") } answers { pendingCount.value = 0; true }
        val importer = mockk<SharedFileImporter>()
        val pending = ShareImportItem(uri, "shared.txt", "text/plain", 4)
        val result = CompletableDeferred<ShareImportBatchResult>()
        coEvery { importer.inspect(any()) } returns listOf(pending)
        coEvery { importer.import(any(), ROOT_DESTINATION, null) } coAnswers { result.await() }
        val viewModel = ShareImportViewModel(queue, importer, targets = FakeTargets())

        repeat(10) { viewModel.presentNext() }
        assertThat(viewModel.state.value.visible).isTrue()
        assertThat(viewModel.state.value.presentation).isEqualTo(SharePresentationState.Visible)
        coVerify(exactly = 1) { queue.claimNextDurably() }
        coVerify(exactly = 1) { importer.inspect(any()) }
        viewModel.importAll()
        coVerify(exactly = 0) { queue.ackDurably(any()) }
        assertThat(viewModel.close()).isFalse()

        result.complete(
            ShareImportBatchResult(listOf(pending.copy(status = ShareImportStatus.Created)))
        )

        coVerify(exactly = 1) { queue.ackDurably("lease") }
        assertThat(viewModel.state.value.importing).isFalse()
        assertThat(viewModel.close()).isTrue()
    }

    @Test
    fun `recordTarget import recordCreated ack任一步失败都退出importing并显示可重试错误`() = runTest(dispatcher) {
        FailurePoint.entries.forEach { failurePoint ->
            val fixture = fixture(failurePoint)
            fixture.viewModel.presentNext()
            fixture.viewModel.importAll()

            assertThat(fixture.viewModel.state.value.importing).isFalse()
            assertThat(fixture.viewModel.state.value.error).isEqualTo(ShareImportErrorCode.IMPORT_FAILED)
            assertThat(fixture.viewModel.state.value.visible).isTrue()
        }
    }

    @Test
    fun `ack失败后重试保留Created结果且最终可确认`() = runTest(dispatcher) {
        val fixture = fixture(FailurePoint.AckReturnsFalse)
        fixture.viewModel.presentNext()
        fixture.viewModel.importAll()
        assertThat(fixture.viewModel.state.value.items.single().status).isEqualTo(ShareImportStatus.Created)
        assertThat(fixture.viewModel.state.value.error).isEqualTo(ShareImportErrorCode.IMPORT_FAILED)

        coEvery { fixture.queue.ackDurably("lease") } returns true
        fixture.viewModel.retryRejected()

        assertThat(fixture.viewModel.state.value.error).isNull()
        assertThat(fixture.viewModel.state.value.items.single().status).isEqualTo(ShareImportStatus.Created)
        coVerify(exactly = 2) { fixture.queue.recordCreatedDurably("request", any()) }
        coVerify(exactly = 2) { fixture.queue.ackDurably("lease") }
    }

    @Test
    fun `打开分享页立即映射已持久化的后台索引失败`() = runTest(dispatcher) {
        val fixture = fixture(null)
        val failedTask = VectorizationTask(
            id = TASK,
            type = VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
            docId = FILE,
            workspaceRootUuid = ROOT,
            status = "failed",
            error = "embedding unavailable",
        )
        fixture.indexState.value = VectorizationQueue.QueueState(
            queue = listOf(failedTask),
            currentTask = failedTask,
            isProcessing = false,
            restored = true,
        )

        fixture.viewModel.presentNext()

        assertThat(fixture.viewModel.state.value.items.single().indexStatus)
            .isEqualTo(ShareIndexStatus.Failed)
    }

    @Test
    fun `索引重试换用新任务后跟踪新任务结果，不把旧任务消失当作完成`() = runTest(dispatcher) {
        val retried = mutableListOf<Pair<String, String>>()
        lateinit var fixture: Fixture
        fixture = fixture(null, retryIndex = { root, file ->
            retried += root to file
            // 队列在重试调用返回前就发布快照：旧任务已被新标识替换，新任务正在排队。
            fixture.indexState.value = indexState(indexTask(RETRY_TASK, "pending"))
            // 真实重试在 IO 线程挂起，界面线程会在返回前处理这份快照。
            kotlinx.coroutines.yield()
            RETRY_TASK
        })
        fixture.viewModel.presentNext()
        fixture.viewModel.importAll()
        fixture.indexState.value = indexState(indexTask(TASK, "failed"))
        assertThat(fixture.viewModel.state.value.items.single().indexStatus).isEqualTo(ShareIndexStatus.Failed)

        fixture.viewModel.retryRejected()

        assertThat(retried).containsExactly(ROOT to FILE)
        // 队列为重试换了新标识，旧任务从快照里消失；新任务仍在排队。
        fixture.indexState.value = indexState(indexTask(RETRY_TASK, "pending"))
        assertThat(fixture.viewModel.state.value.items.single().indexStatus).isEqualTo(ShareIndexStatus.Pending)
        fixture.indexState.value = indexState(indexTask(RETRY_TASK, "failed"))
        assertThat(fixture.viewModel.state.value.items.single().indexStatus).isEqualTo(ShareIndexStatus.Failed)
    }

    @Test
    fun `索引任务无法重试时保留失败状态并报告重试失败`() = runTest(dispatcher) {
        val fixture = fixture(null, retryIndex = { _, _ -> null })
        fixture.viewModel.presentNext()
        fixture.viewModel.importAll()
        fixture.indexState.value = indexState(indexTask(TASK, "failed"))

        fixture.viewModel.retryRejected()

        assertThat(fixture.viewModel.state.value.error).isEqualTo(ShareImportErrorCode.INDEX_RETRY_FAILED)
        assertThat(fixture.viewModel.state.value.items.single().indexStatus).isEqualTo(ShareIndexStatus.Failed)
    }

    private fun indexTask(id: String, status: String) = VectorizationTask(
        id = id,
        type = VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
        docId = FILE,
        workspaceRootUuid = ROOT,
        status = status,
    )

    private fun indexState(task: VectorizationTask) = VectorizationQueue.QueueState(
        queue = listOf(task),
        currentTask = task,
        isProcessing = task.status == "pending",
        restored = true,
    )

    @Test
    fun `有当前会话时默认选当前会话，没有时选存储空间根目录`() = runTest(dispatcher) {
        val withSession = selectionFixture(FakeTargets(options = { WITH_SESSION }))
        withSession.viewModel.presentNext()
        assertThat(withSession.viewModel.state.value.selectedKind).isEqualTo(ShareTargetKind.CurrentSession)

        val knowledgeOnly = selectionFixture(FakeTargets())
        knowledgeOnly.viewModel.presentNext()
        assertThat(knowledgeOnly.viewModel.state.value.selectedKind).isEqualTo(ShareTargetKind.KnowledgeBase)
        assertThat(knowledgeOnly.viewModel.state.value.canImportTo).isTrue()
        knowledgeOnly.viewModel.selectTarget(ShareTargetKind.CurrentSession)
        assertThat(knowledgeOnly.viewModel.state.value.selectedKind).isEqualTo(ShareTargetKind.KnowledgeBase)
    }

    @Test
    fun `当前会话导入到它的本会话文件夹并记录落点`() = runTest(dispatcher) {
        val fixture = selectionFixture(FakeTargets(options = { WITH_SESSION }))
        fixture.viewModel.presentNext()
        fixture.viewModel.importAll()

        val expected = ShareImportDestination.SessionFolder(CURRENT.sessionId, "root-${CURRENT.sessionId}", current = true)
        coVerify { fixture.queue.recordTargetDurably("request", expected) }
        coVerify { fixture.importer.import(any(), expected, null) }
    }

    @Test
    fun `其他会话必须用户亲手选，选之前不能导入`() = runTest(dispatcher) {
        val fixture = selectionFixture(FakeTargets(options = { WITH_SESSION }))
        fixture.viewModel.presentNext()

        fixture.viewModel.selectTarget(ShareTargetKind.OtherSession)
        val state = fixture.viewModel.state.value
        assertThat(state.selectedKind).isEqualTo(ShareTargetKind.OtherSession)
        assertThat(state.otherSession).isNull()
        assertThat(state.canImportTo).isFalse()
        // 会话列表打开但不预选；当前会话不在列表里。
        assertThat(state.sessionPicker?.sessions).containsExactly(OTHER)
        fixture.viewModel.importAll()
        coVerify(exactly = 0) { fixture.importer.import(any(), any<ShareImportDestination>(), any()) }

        fixture.viewModel.pickSession(OTHER)
        assertThat(fixture.viewModel.state.value.sessionPicker).isNull()
        assertThat(fixture.viewModel.state.value.canImportTo).isTrue()
        fixture.viewModel.importAll()
        coVerify {
            fixture.importer.import(any(), ShareImportDestination.SessionFolder(OTHER.sessionId, "root-${OTHER.sessionId}", current = false), null)
        }
    }

    @Test
    fun `会话列表之外的会话不能被选中`() = runTest(dispatcher) {
        val fixture = selectionFixture(FakeTargets(options = { WITH_SESSION }))
        fixture.viewModel.presentNext()
        fixture.viewModel.pickSession(OTHER)
        assertThat(fixture.viewModel.state.value.otherSession).isNull()

        fixture.viewModel.openSessionPicker()
        fixture.viewModel.pickSession(ShareSessionChoice("forged", "伪造", 0L))
        assertThat(fixture.viewModel.state.value.otherSession).isNull()
    }

    @Test
    fun `Linux 不可用时这一项不能选`() = runTest(dispatcher) {
        AVAILABILITY_NOT_READY.forEach { availability ->
            val fixture = selectionFixture(FakeTargets(options = { WITH_SESSION.copy(linux = availability) }))
            fixture.viewModel.presentNext()
            fixture.viewModel.selectTarget(ShareTargetKind.Linux)
            fixture.viewModel.pickLinuxDirectory("/root/inbox")
            assertThat(fixture.viewModel.state.value.selectedKind).isEqualTo(ShareTargetKind.CurrentSession)
            assertThat(fixture.viewModel.state.value.linuxDirectory).isNull()
        }
    }

    @Test
    fun `Linux 默认落到主目录，选目录后导入到该目录`() = runTest(dispatcher) {
        val fixture = selectionFixture(FakeTargets(options = { WITH_SESSION.copy(linux = ShareLinuxAvailability.Ready) }))
        fixture.viewModel.presentNext()
        fixture.viewModel.selectTarget(ShareTargetKind.Linux)
        assertThat(fixture.viewModel.state.value.linuxDirectory).isEqualTo("/root")

        fixture.viewModel.pickLinuxDirectory("/root/inbox")
        fixture.viewModel.importAll()

        val expected = ShareImportDestination.LinuxDirectory("/root/inbox")
        coVerify { fixture.queue.recordTargetDurably("request", expected) }
        coVerify { fixture.importer.import(any(), expected, null) }
    }

    @Test
    fun `存储空间默认根目录，也能逐级选子文件夹`() = runTest(dispatcher) {
        val fixture = selectionFixture(FakeTargets())
        fixture.viewModel.presentNext()

        fixture.viewModel.openFolderPicker()
        assertThat(fixture.viewModel.state.value.folderPicker?.folders).containsExactly(DOCS)
        fixture.viewModel.enterFolder(DOCS)
        assertThat(fixture.viewModel.state.value.folderPicker?.folders).containsExactly(FINANCE)
        fixture.viewModel.enterFolder(FINANCE)
        fixture.viewModel.folderUp()
        assertThat(fixture.viewModel.state.value.folderPicker?.trail).containsExactly(DOCS)
        fixture.viewModel.enterFolder(FINANCE)
        fixture.viewModel.confirmFolder()

        assertThat(fixture.viewModel.state.value.folderPicker).isNull()
        assertThat(fixture.viewModel.state.value.knowledgeFolder).isEqualTo(FINANCE)
        fixture.viewModel.importAll()
        coVerify {
            fixture.importer.import(any(), ShareImportDestination.KnowledgeFolder(ROOT, FINANCE.uuid, FINANCE.path), null)
        }
    }

    @Test
    fun `收件箱里记录的落点在仍可用时恢复`() = runTest(dispatcher) {
        val saved = listOf(
            ShareImportDestination.SessionFolder(OTHER.sessionId, "root-${OTHER.sessionId}", current = false) to ShareTargetKind.OtherSession,
            ShareImportDestination.LinuxDirectory("/root/inbox") to ShareTargetKind.Linux,
            ShareImportDestination.KnowledgeFolder(ROOT, FINANCE.uuid, FINANCE.path) to ShareTargetKind.KnowledgeBase,
        )
        saved.forEach { (destination, kind) ->
            val fixture = selectionFixture(
                FakeTargets(options = { WITH_SESSION.copy(linux = ShareLinuxAvailability.Ready) }),
                saved = destination,
            )
            fixture.viewModel.presentNext()
            val state = fixture.viewModel.state.value
            assertThat(state.selectedKind).isEqualTo(kind)
            assertThat(state.canImportTo).isTrue()
            fixture.viewModel.importAll()
            coVerify { fixture.importer.import(any(), destination, null) }
        }
        // Linux 已不可用时不恢复，退回当前会话。
        val fallback = selectionFixture(FakeTargets(options = { WITH_SESSION }), saved = ShareImportDestination.LinuxDirectory("/root/inbox"))
        fallback.viewModel.presentNext()
        assertThat(fallback.viewModel.state.value.selectedKind).isEqualTo(ShareTargetKind.CurrentSession)
    }

    private fun selectionFixture(targets: FakeTargets, saved: ShareImportDestination? = null): Fixture {
        val uri = Uri.parse("content://fixture/shared.sqlite")
        val request = ShareRequest(
            uris = listOf(uri),
            mimeType = "application/vnd.sqlite3",
            fingerprint = "0".repeat(64),
            canonicalSizeBytes = 1,
            requestId = "request",
        )
        val lease = ShareLease("lease", request)
        val queue = mockk<ShareIntentQueue>()
        every { queue.durablePendingCount } returns MutableStateFlow(1)
        coJustRun { queue.refreshDurableCount() }
        coEvery { queue.claimNextDurably() } returns lease
        coJustRun { queue.recordTargetDurably(any(), any<ShareImportDestination>()) }
        coJustRun { queue.recordCreatedDurably(any(), any()) }
        coEvery { queue.ackDurably(any()) } returns true
        coEvery { queue.nackDurably(any()) } returns true
        val pending = ShareImportItem(uri, "shared.sqlite", "application/vnd.sqlite3", 4, parseable = false)
        val importer = mockk<SharedFileImporter>()
        coEvery { importer.inspect(any()) } returns listOf(pending)
        coEvery { importer.import(any(), any<ShareImportDestination>(), any()) } returns
            ShareImportBatchResult(listOf(pending.copy(status = ShareImportStatus.Created)))
        val viewModel = ShareImportViewModel(queue, importer, targets = targets)
        if (saved != null) {
            // 持久收件箱恢复出的请求带着上次的落点。
            val restored = mockk<ShareRequest>()
            every { restored.uris } returns request.uris
            every { restored.mimeType } returns request.mimeType
            every { restored.requestId } returns request.requestId
            every { restored.targetDestination } returns saved
            coEvery { queue.claimNextDurably() } returns ShareLease("lease", restored)
        }
        return Fixture(viewModel, queue, importer, MutableStateFlow(VectorizationQueue.QueueState(emptyList(), null, false, false)))
    }

    private class FakeTargets(
        private val options: suspend () -> ShareImportOptions = { KNOWLEDGE_ONLY },
    ) : ShareImportTargetSource {
        override suspend fun options() = options.invoke()
        override suspend fun sessions(excludeSessionId: String?) =
            listOf(CURRENT, OTHER).filter { it.sessionId != excludeSessionId }
        override suspend fun knowledgeFolders(parentUuid: String) = when (parentUuid) {
            ROOT -> listOf(DOCS)
            DOCS.uuid -> listOf(FINANCE)
            else -> emptyList()
        }
        override suspend fun sessionDestination(sessionId: String, current: Boolean) =
            ShareImportDestination.SessionFolder(sessionId, "root-$sessionId", current)
    }

    private fun fixture(
        failurePoint: FailurePoint?,
        retryIndex: suspend (String, String) -> String? = { _, _ -> null },
    ): Fixture {
        val uri = Uri.parse("content://fixture/shared.txt")
        val request = ShareRequest(
            uris = listOf(uri),
            mimeType = "text/plain",
            fingerprint = "0".repeat(64),
            canonicalSizeBytes = 1,
            requestId = "request",
        )
        val queue = mockk<ShareIntentQueue>()
        every { queue.durablePendingCount } returns MutableStateFlow(1)
        coJustRun { queue.refreshDurableCount() }
        coEvery { queue.claimNextDurably() } returns ShareLease("lease", request)
        if (failurePoint == FailurePoint.RecordTarget) {
            coEvery { queue.recordTargetDurably(any(), any<ShareImportDestination>()) } throws IOException("target")
        } else coJustRun { queue.recordTargetDurably(any(), any<ShareImportDestination>()) }
        if (failurePoint == FailurePoint.RecordCreated) {
            coEvery { queue.recordCreatedDurably(any(), any()) } throws IOException("created")
        } else coJustRun { queue.recordCreatedDurably(any(), any()) }
        if (failurePoint == FailurePoint.AckThrows) {
            coEvery { queue.ackDurably("lease") } throws IOException("ack")
        } else {
            coEvery { queue.ackDurably("lease") } returns (failurePoint != FailurePoint.AckReturnsFalse)
        }
        coEvery { queue.nackDurably(any()) } returns true

        val importer = mockk<SharedFileImporter>()
        val pending = ShareImportItem(uri, "shared.txt", "text/plain", 4)
        val created = pending.copy(
            status = ShareImportStatus.Created,
            indexStatus = ShareIndexStatus.Pending,
            fileUuid = FILE,
            indexTaskId = TASK,
        )
        coEvery { importer.inspect(any()) } returns listOf(created)
        if (failurePoint == FailurePoint.Import) {
            coEvery { importer.import(any(), any<ShareImportDestination>(), null) } throws IOException("import")
        } else {
            coEvery { importer.import(any(), any<ShareImportDestination>(), null) } returns ShareImportBatchResult(listOf(created))
        }
        val indexState = MutableStateFlow(VectorizationQueue.QueueState(emptyList(), null, false, false))
        val viewModel = ShareImportViewModel(
            queue,
            importer,
            targets = FakeTargets(),
            indexQueueState = indexState,
            retryIndex = retryIndex,
        )
        return Fixture(viewModel, queue, importer, indexState)
    }

    private data class Fixture(
        val viewModel: ShareImportViewModel,
        val queue: ShareIntentQueue,
        val importer: SharedFileImporter,
        val indexState: MutableStateFlow<VectorizationQueue.QueueState>,
    )

    private enum class FailurePoint { RecordTarget, Import, RecordCreated, AckReturnsFalse, AckThrows }

    private companion object {
        const val ROOT = "knowledge-root"
        val ROOT_DESTINATION = ShareImportDestination.KnowledgeFolder(ROOT, ROOT, "/")
        val KNOWLEDGE_ONLY = ShareImportOptions(currentSession = null, knowledgeRootUuid = ROOT, linux = ShareLinuxAvailability.Unsupported)
        val CURRENT = ShareSessionChoice("session-current", "Linux 迁移方案讨论", 2_000L)
        val OTHER = ShareSessionChoice("session-other", "周报整理", 1_000L)
        val WITH_SESSION = KNOWLEDGE_ONLY.copy(currentSession = CURRENT, linux = ShareLinuxAvailability.NeedsInstall)
        val AVAILABILITY_NOT_READY = listOf(ShareLinuxAvailability.NeedsInstall, ShareLinuxAvailability.Unsupported)
        val DOCS = ShareKnowledgeFolder("kb-docs", "资料", "/资料")
        val FINANCE = ShareKnowledgeFolder("kb-finance", "财务", "/资料/财务")
        const val FILE = "11111111-1111-1111-1111-111111111111"
        const val TASK = "doc-ref-fixture"
        const val RETRY_TASK = "doc-ref-fixture-retry"
    }

    @Test
    fun `全部条目导入成功且无错误时面板进入完成态`() {
        val uri = Uri.parse("content://fixture/done.txt")
        val created = ShareImportItem(uri, "done.txt", "text/plain", 4, status = ShareImportStatus.Created)
        val pending = created.copy(status = ShareImportStatus.Pending)
        val rejected = created.copy(status = ShareImportStatus.Rejected)
        assertThat(shareImportFinished(ShareImportUiState(items = listOf(created)))).isTrue()
        assertThat(shareImportFinished(ShareImportUiState(items = listOf(created, pending)))).isFalse()
        assertThat(shareImportFinished(ShareImportUiState(items = listOf(created, rejected)))).isFalse()
        assertThat(shareImportFinished(ShareImportUiState(items = listOf(created), importing = true))).isFalse()
        assertThat(shareImportFinished(ShareImportUiState())).isFalse()
    }
}
