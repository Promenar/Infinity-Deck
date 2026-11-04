package com.promenar.infinity.ui.rag

import androidx.lifecycle.ViewModelStore
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.ShareRequest
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.dao.SessionDao
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IKnowledgeGraphRepository
import com.promenar.infinity.domain.repository.IVectorRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.repository.VectorTypeCount
import com.promenar.infinity.data.rag.KeywordSearcher
import com.promenar.infinity.data.rag.KeywordSearchMode
import com.promenar.infinity.data.rag.KeywordSearchOutcome
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.rag.PendingDocumentIndexCoordinator
import com.promenar.infinity.domain.model.Document
import com.promenar.infinity.domain.model.Folder
import com.promenar.infinity.domain.usecase.RagConfigPersistence
import com.promenar.infinity.domain.repository.ReadResult
import com.promenar.infinity.domain.repository.RenameIndexTarget
import com.promenar.infinity.domain.repository.RenameResult
import com.promenar.infinity.share.core.ShareImportBatchResult
import com.promenar.infinity.share.core.ShareImportItem
import com.promenar.infinity.share.core.ShareImportStatus
import com.promenar.infinity.share.core.ShareRejectReason
import com.promenar.infinity.share.core.SharedFileImporter
import com.promenar.infinity.ui.common.status.NoticeSeverity
import com.promenar.infinity.ui.common.KgStatus
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RagViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val workspaceRepository: IWorkspaceRepository = mockk(relaxed = true)
    private val vectorRepository: IVectorRepository = mockk(relaxed = true)
    private val kgRepository: IKnowledgeGraphRepository = mockk(relaxed = true)
    private val fileOperationRepository: IFileOperationRepository = mockk(relaxed = true)
    private val keywordSearcher: KeywordSearcher = mockk(relaxed = true)

    private lateinit var app: InfinityApplication
    private lateinit var sessionDao: SessionDao
    private lateinit var vectorizationQueue: com.promenar.infinity.data.rag.VectorizationQueue
    private lateinit var filesDir: java.io.File
    private lateinit var ensureRagWorkspaceRoot: suspend () -> FileEntry

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        val database = mockk<InfinityDatabase>(relaxed = true)
        sessionDao = mockk(relaxed = true)
        app = mockk<InfinityApplication>(relaxed = true)

        every { app.database } returns database
        every { app.sessionExecutionGate } returns com.promenar.infinity.data.session.SessionExecutionGate()
        every { database.sessionDao() } returns sessionDao
        every { app.getSharedPreferences(any(), any()) } returns mockk(relaxed = true)
        ensureRagWorkspaceRoot = mockk()
        coEvery { ensureRagWorkspaceRoot.invoke() } returns rootEntry()
        vectorizationQueue = mockk(relaxed = true)
        every { vectorizationQueue.state } returns MutableStateFlow(
            com.promenar.infinity.data.rag.VectorizationQueue.QueueState(
                queue = emptyList(),
                currentTask = null,
                isProcessing = false,
            )
        )
        every { app.vectorizationQueue } returns vectorizationQueue
        every { app.vectorizationQueueState } answers { vectorizationQueue.state }
        coEvery { app.awaitVectorizationQueue() } answers { vectorizationQueue }
        every { app.observeVectorizationQueueInstances(any()) } answers {
            firstArg<(com.promenar.infinity.data.rag.VectorizationQueue) -> Unit>().invoke(vectorizationQueue)
            AutoCloseable { }
        }
        filesDir = java.nio.file.Files.createTempDirectory("infinity-rag-vm").toFile()
        every { app.filesDir } returns filesDir

        coEvery { kgRepository.getNodeCount() } returns 0
        coEvery { workspaceRepository.ensureSessionRoot(any()) } returns rootEntry()
        every { workspaceRepository.observeChildren(any(), any()) } returns flowOf(emptyList())
        coEvery { vectorRepository.getCount() } returns 0
        coEvery { vectorRepository.countByType() } returns emptyList()
        coEvery { vectorRepository.countBySession(any()) } returns emptyList()
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
        filesDir.deleteRecursively()
    }

    @Test
    fun `手动PDF与DOCX抽取使用文件引用队列且不经过文本工具`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        listOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document").forEachIndexed { i, mime ->
            val file = fileEntry("kg-$i", "kg-$i", materializedPath = "/kg-$i").copy(mimeType = mime)
            coEvery { workspaceRepository.getByUuid("rag-root", file.uuid) } returns file
            vm.extractKG(file.uuid)
            advanceUntilIdle()
            coVerify(exactly = 1) {
                vectorizationQueue.enqueueDocumentReference("rag-root", file.uuid, file.name, mime, file.hash, file.updatedAt, "full", true)
            }
            assertThat(vm.kgExtractionStates.value[file.uuid]).isEqualTo(KgStatus.IN_PROGRESS)
        }
        coVerify(exactly = 0) { fileOperationRepository.readFileRange(any(), any(), any(), any()) }
    }

    @Test
    fun `保存KG设置使缓存抽取器失效`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.updateConfig { it.copy(kgExtractionModel = "owner::model", kgExtractionPrompt = "提取实体") }
        verify(exactly = 1) { app.rebuildGraphExtractor() }
    }

    @Test
    fun `大文档先展示调用与token估算且只有确认后才以已确认策略入队`() = runTest {
        val file = fileEntry("large-kg", "large.txt", materializedPath = "/large.txt").copy(mimeType = "text/plain")
        coEvery { workspaceRepository.getByUuid("rag-root", file.uuid) } returns file
        val vm = createViewModel(kgEstimator = { com.promenar.infinity.data.rag.KgExtractionEstimate(20, 14_000, 40_960, 20, true) })
        advanceUntilIdle()

        vm.extractKG(file.uuid)
        advanceUntilIdle()
        assertThat(vm.kgExtractionConfirmation.value!!.estimate.calls).isEqualTo(20)
        assertThat(vm.kgExtractionConfirmation.value!!.estimate.inputTokens).isEqualTo(14_000)
        coVerify(exactly = 0) { vectorizationQueue.enqueueDocumentReference(any(), any(), any(), any(), any(), any(), any(), any()) }

        vm.confirmKnowledgeGraphExtraction()
        advanceUntilIdle()
        assertThat(vm.kgExtractionConfirmation.value).isNull()
        coVerify(exactly = 1) {
            vectorizationQueue.enqueueDocumentReference("rag-root", file.uuid, file.name, "text/plain", file.hash, file.updatedAt, "full_confirmed", true)
        }
    }

    @Test
    fun `确认期间文档版本变化会重新展示估算且不复用授权`() = runTest {
        val file = fileEntry("changed-kg", "large.txt", materializedPath = "/large.txt").copy(mimeType = "text/plain")
        coEvery { workspaceRepository.getByUuid("rag-root", file.uuid) } returns file
        val vm = createViewModel(kgEstimator = { com.promenar.infinity.data.rag.KgExtractionEstimate(20, 14_000, 40_960, 20, true) })
        advanceUntilIdle()
        vm.extractKG(file.uuid)
        advanceUntilIdle()

        coEvery { workspaceRepository.getByUuid("rag-root", file.uuid) } returns file.copy(hash = "changed", updatedAt = 2)
        vm.confirmKnowledgeGraphExtraction()
        advanceUntilIdle()

        assertThat(vm.kgExtractionConfirmation.value!!.contentHash).isEqualTo("changed")
        coVerify(exactly = 0) { vectorizationQueue.enqueueDocumentReference(any(), any(), any(), any(), any(), any(), any(), any()) }
        vm.dismissKnowledgeGraphConfirmation()
        assertThat(vm.kgExtractionConfirmation.value).isNull()
    }

    @Test
    fun `新版本只做向量索引时清除旧版本部分KG状态且文件图标遵守epoch`() = runTest {
        var listener: ((List<com.promenar.infinity.data.rag.VectorizationTask>, com.promenar.infinity.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            listener = firstArg()
            AutoCloseable { }
        }
        val vm = createViewModel()
        advanceUntilIdle()
        val old = com.promenar.infinity.data.rag.VectorizationTask(
            "version-a", "document_reference", docId = "versioned-kg", workspaceRootUuid = "rag-root",
            kgStrategy = "full", status = "partial", targetContentHash = "hash-a", targetEpoch = 1,
            lastChunkIndex = 2, totalChunks = 5, updatedAt = 1,
        )
        val versionA = fileEntry("versioned-kg", "versioned.txt", materializedPath = "/versioned.txt")
            .copy(hash = "hash-a", mimeType = "text/plain", kgStrategy = "partial:2:5:1")
        listener!!.invoke(listOf(old), old)
        assertThat(com.promenar.infinity.ui.chat.components.resolveKgStatus(versionA, vm.kgExtractionStates.value))
            .isEqualTo(KgStatus.PARTIAL)

        val current = old.copy(id = "version-b", status = "pending", kgStrategy = null,
            targetContentHash = "hash-b", targetEpoch = 2, updatedAt = 2)
        listener!!.invoke(listOf(old, current), current)

        assertThat(vm.kgExtractionStates.value).doesNotContainKey("versioned-kg")
        assertThat(com.promenar.infinity.ui.chat.components.resolveKgStatus(
            versionA.copy(hash = "hash-b", updatedAt = 2), vm.kgExtractionStates.value))
            .isEqualTo(KgStatus.NOT_STARTED)
    }

    @Test
    fun `手动KG队列失败在UI显示失败且ViewModel重建不丢失持久完成态`() = runTest {
        var listener: ((List<com.promenar.infinity.data.rag.VectorizationTask>, com.promenar.infinity.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            listener = firstArg()
            AutoCloseable { }
        }
        val file = fileEntry("kg", "kg.txt", materializedPath = "/kg.txt").copy(mimeType = "text/plain")
        coEvery { workspaceRepository.getByUuid("rag-root", "kg") } returns file
        val vm = createViewModel()
        advanceUntilIdle()
        vm.extractKG("kg")
        advanceUntilIdle()
        val task = com.promenar.infinity.data.rag.VectorizationTask("t", "document_reference", docId = "kg",
            workspaceRootUuid = "rag-root", kgStrategy = "full", status = "failed", error = "transaction failed")
        listener!!.invoke(listOf(task), task)
        assertThat(vm.kgExtractionStates.value["kg"]).isEqualTo(KgStatus.FAILED)
        assertThat(vm.indexingNotice.value?.severity).isEqualTo(NoticeSeverity.Error)

        val recreated = createViewModel()
        advanceUntilIdle()
        val persisted = file.copy(kgExtractedAt = file.updatedAt)
        assertThat(com.promenar.infinity.ui.chat.components.resolveKgStatus(persisted, recreated.kgExtractionStates.value))
            .isEqualTo(KgStatus.COMPLETED)
        assertThat(com.promenar.infinity.ui.chat.components.resolveKgStatus(persisted.copy(updatedAt = persisted.updatedAt + 1), emptyMap()))
            .isEqualTo(KgStatus.NOT_STARTED)
        assertThat(com.promenar.infinity.ui.chat.components.resolveKgStatus(file.copy(kgExtractedAt = null), mapOf("kg" to KgStatus.COMPLETED)))
            .isEqualTo(KgStatus.NOT_STARTED)
        assertThat(com.promenar.infinity.ui.chat.components.resolveKgStatus(persisted.copy(updatedAt = persisted.updatedAt + 1), mapOf("kg" to KgStatus.COMPLETED)))
            .isEqualTo(KgStatus.NOT_STARTED)
    }

    @Test
    fun `知识库初始化使用共享根提供器并发布返回的根`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.workspaceRootUuid.value).isEqualTo("rag-root")
        coVerify(exactly = 1) { ensureRagWorkspaceRoot.invoke() }
    }

    @Test
    fun `同名知识库按Session与根身份切换而不是按标题`() = runTest {
        val sources = listOf(
            RagWorkspaceSource(
                sessionId = RagWorkspaceProvisioner.SESSION_ID,
                workspaceRootUuid = "rag-root",
                title = "同名知识库",
            ),
            RagWorkspaceSource(
                sessionId = "legacy-session",
                workspaceRootUuid = "legacy-root",
                title = "同名知识库",
            ),
        )
        val ensuredSessions = mutableListOf<String>()
        val viewModel = createViewModel(
            listRoots = { sources },
            ensureForSession = { sessionId ->
                ensuredSessions += sessionId
                rootEntry().copy(uuid = if (sessionId == "legacy-session") "legacy-root" else "rag-root")
            },
        )
        advanceUntilIdle()

        viewModel.selectWorkspaceSource("legacy-session")
        advanceUntilIdle()

        assertThat(viewModel.workspaceRootUuid.value).isEqualTo("legacy-root")
        assertThat(viewModel.selectedWorkspaceSessionId.value).isEqualTo("legacy-session")
        assertThat(ensuredSessions).containsExactly("legacy-session")
    }

    @Test
    fun `快速切换时忽略已取消来源的晚到根`() = runTest {
        val lateResultGate = CompletableDeferred<Unit>()
        val firstSwitchStarted = CompletableDeferred<Unit>()
        val sources = listOf(
            RagWorkspaceSource(
                sessionId = RagWorkspaceProvisioner.SESSION_ID,
                workspaceRootUuid = "rag-root",
                title = "当前",
            ),
            RagWorkspaceSource("legacy-a", "legacy-root-a", "旧库 A"),
            RagWorkspaceSource("legacy-b", "legacy-root-b", "旧库 B"),
        )
        val viewModel = createViewModel(
            listRoots = { sources },
            ensureForSession = { sessionId ->
                when (sessionId) {
                    "legacy-a" -> {
                        firstSwitchStarted.complete(Unit)
                        withContext(NonCancellable) { lateResultGate.await() }
                        rootEntry().copy(uuid = "legacy-root-a")
                    }
                    "legacy-b" -> rootEntry().copy(uuid = "legacy-root-b")
                    else -> rootEntry().copy(uuid = "rag-root")
                }
            },
        )
        advanceUntilIdle()

        viewModel.selectWorkspaceSource("legacy-a")
        firstSwitchStarted.await()
        viewModel.selectWorkspaceSource("legacy-b")
        advanceUntilIdle()
        assertThat(viewModel.workspaceRootUuid.value).isEqualTo("legacy-root-b")

        lateResultGate.complete(Unit)
        advanceUntilIdle()

        assertThat(viewModel.workspaceRootUuid.value).isEqualTo("legacy-root-b")
        assertThat(viewModel.selectedWorkspaceSessionId.value).isEqualTo("legacy-b")
    }

    @Test
    fun `queue callback在resource reset latch期间只读捕获实例不自锁`() = runTest {
        var callback: ((List<com.promenar.infinity.data.rag.VectorizationTask>, com.promenar.infinity.data.rag.VectorizationTask?) -> Unit)? = null
        val firstQueueTokenClosed = java.util.concurrent.atomic.AtomicInteger()
        val secondQueueTokenClosed = java.util.concurrent.atomic.AtomicInteger()
        val resourceTokenClosed = java.util.concurrent.atomic.AtomicInteger()
        val firstQueue = vectorizationQueue
        val secondQueue = mockk<com.promenar.infinity.data.rag.VectorizationQueue>(relaxed = true)
        every { firstQueue.setOnStateChange(any()) } answers {
            callback = firstArg()
            AutoCloseable { firstQueueTokenClosed.incrementAndGet() }
        }
        every { secondQueue.state } returns MutableStateFlow(
            com.promenar.infinity.data.rag.VectorizationQueue.QueueState(emptyList(), null, false),
        )
        val resource = com.promenar.infinity.utils.SynchronizedResettableResource<com.promenar.infinity.data.rag.VectorizationQueue> {}
        resource.getOrCreate { firstQueue }
        every { app.observeVectorizationQueueInstances(any()) } answers {
            val delegate = resource.observeInstances(firstArg())
            AutoCloseable {
                resourceTokenClosed.incrementAndGet()
                delegate.close()
            }
        }
        val vm = createViewModel()
        val reset = checkNotNull(resource.beginReset())
        val completed = java.util.concurrent.CountDownLatch(1)
        val callbackThread = Thread {
            callback?.invoke(emptyList(), null)
            completed.countDown()
        }
        try {
            callbackThread.start()
            assertThat(completed.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
        } finally {
            resource.completeReset(reset)
            callbackThread.join(1_000)
        }

        var replacementCallback: ((List<com.promenar.infinity.data.rag.VectorizationTask>, com.promenar.infinity.data.rag.VectorizationTask?) -> Unit)? = null
        every { secondQueue.setOnStateChange(any()) } answers {
            replacementCallback = firstArg()
            AutoCloseable { secondQueueTokenClosed.incrementAndGet() }
        }
        resource.getOrCreate { secondQueue }
        assertThat(firstQueueTokenClosed.get()).isEqualTo(1)
        replacementCallback?.invoke(
            listOf(
                com.promenar.infinity.data.rag.VectorizationTask(
                    id = "replacement-task",
                    type = com.promenar.infinity.data.rag.VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
                    workspaceRootUuid = "rag-root",
                    docId = "replacement-doc",
                    status = "vectorizing",
                    progress = 50.0,
                ),
            ),
            com.promenar.infinity.data.rag.VectorizationTask(
                id = "replacement-task",
                type = com.promenar.infinity.data.rag.VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
                workspaceRootUuid = "rag-root",
                docId = "replacement-doc",
                status = "vectorizing",
                progress = 50.0,
            ),
        )
        assertThat(vm.indexingDocIds.value).contains("replacement-doc")
        assertThat(vm.indexingProgress.value).isWithin(0.001f).of(0.5f)

        callback?.invoke(
            listOf(
                com.promenar.infinity.data.rag.VectorizationTask(
                    id = "stale-old-task",
                    type = com.promenar.infinity.data.rag.VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
                    workspaceRootUuid = "rag-root",
                    docId = "stale-old-doc",
                    status = "vectorizing",
                    progress = 100.0,
                ),
            ),
            null,
        )
        assertThat(vm.indexingDocIds.value).containsExactly("replacement-doc")
        assertThat(vm.indexingProgress.value).isWithin(0.001f).of(0.5f)

        ViewModelStore().also { it.put("rag", vm) }.clear()
        assertThat(secondQueueTokenClosed.get()).isEqualTo(1)
        assertThat(resourceTokenClosed.get()).isEqualTo(1)
    }

    @Test
    fun `全局知识库根初始化失败会转为可见错误而不会逃逸到主线程`() = runTest {
        coEvery { ensureRagWorkspaceRoot.invoke() } throws SecurityException("fixture identity failure")

        val result = runCatching {
            createViewModel().also { advanceUntilIdle() }
        }

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().indexingNotice.value).isNotNull()
        assertThat(result.getOrThrow().indexingNotice.value!!.severity)
            .isEqualTo(NoticeSeverity.Error)
    }

    @Test
    fun `创建文件夹失败会显示操作提示`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        coEvery { workspaceRepository.createDirectoryInWorkspace(any(), any(), any(), any(), any()) } throws IllegalStateException("fixture")
        vm.createFolder("测试目录")
        advanceUntilIdle()
        assertThat(vm.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_OPERATION_FAILED)
        assertThat(vm.indexingNotice.value?.severity).isEqualTo(NoticeSeverity.Error)
    }

    @Test
    fun `创建文件夹的协程取消会向外传播`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        var operationJob: kotlinx.coroutines.Job? = null
        coEvery { workspaceRepository.createDirectoryInWorkspace(any(), any(), any(), any(), any()) } coAnswers {
            operationJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            throw CancellationException("fixture cancellation")
        }
        vm.createFolder("测试目录")
        advanceUntilIdle()
        assertThat(operationJob).isNotNull()
        assertThat(operationJob!!.isCancelled).isTrue()
    }

    @Test
    fun `索引进度不会覆盖普通操作失败提示`() = runTest {
        var callback: ((List<com.promenar.infinity.data.rag.VectorizationTask>, com.promenar.infinity.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers { callback = firstArg(); AutoCloseable { } }
        val vm = createViewModel()
        coEvery { workspaceRepository.createDirectoryInWorkspace(any(), any(), any(), any(), any()) } throws IllegalStateException("fixture")
        vm.createFolder("测试目录")
        advanceUntilIdle()
        val running = com.promenar.infinity.data.rag.VectorizationTask(id = "running", type = "document", status = "vectorizing")
        callback?.invoke(listOf(running), running)
        assertThat(vm.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_OPERATION_FAILED)
    }

    @Test
    fun `其它操作更新索引状态不会覆盖尚未关闭的操作错误`() = runTest {
        val vm = createViewModel()
        coEvery { workspaceRepository.createDirectoryInWorkspace(any(), any(), any(), any(), any()) } throws IllegalStateException("fixture")
        vm.createFolder("测试目录")
        advanceUntilIdle()
        vm.reindexDocuments(emptyList())
        assertThat(vm.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_OPERATION_FAILED)
    }

    @Test
    fun `关闭操作错误不会取消其它文档的失败任务`() = runTest {
        var callback: ((List<com.promenar.infinity.data.rag.VectorizationTask>, com.promenar.infinity.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers { callback = firstArg(); AutoCloseable { } }
        val vm = createViewModel()
        val failed = com.promenar.infinity.data.rag.VectorizationTask(id = "failed", type = "document_reference", workspaceRootUuid = "rag-root", docId = "doc-ref", status = "failed")
        callback?.invoke(listOf(failed), failed)
        coEvery { workspaceRepository.createDirectoryInWorkspace(any(), any(), any(), any(), any()) } throws IllegalStateException("fixture")
        vm.createFolder("测试目录")
        advanceUntilIdle()
        vm.dismissQueueError()
        advanceUntilIdle()
        coVerify(exactly = 0) { vectorizationQueue.cancelAndJoin(any(), any()) }
        assertThat(vm.canRetryLastFailedIndex.value).isTrue()
        assertThat(vm.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_FAILED)
    }

    private fun createViewModel(
        importer: SharedFileImporter? = null,
        requestFactory: ((android.net.Uri, String) -> ShareRequest)? = null,
        pendingIndexCoordinator: PendingDocumentIndexCoordinator? = null,
        configSaver: ((com.promenar.infinity.data.rag.RagConfiguration) -> Unit)? = null,
        listRoots: (suspend () -> List<RagWorkspaceSource>)? = null,
        ensureForSession: (suspend (String) -> FileEntry)? = null,
        kgEstimator: suspend (FileEntry) -> com.promenar.infinity.data.rag.KgExtractionEstimate = {
            com.promenar.infinity.data.rag.KgExtractionEstimate(1, 100, 2048, 1, false)
        },
        indexBaseline: IndexedChunkBaseline = FakeIndexBaseline(),
    ): RagViewModel {
        assertThat(app.vectorizationQueue).isSameInstanceAs(vectorizationQueue)
        val ragPrefs = mockk<android.content.SharedPreferences>(relaxed = true)
        val ragConfigPersistence = RagConfigPersistence(ragPrefs)
        return RagViewModel(
            app, workspaceRepository, vectorRepository, kgRepository,
            fileOperationRepository, ragConfigPersistence, keywordSearcher,
            injectedImporter = importer,
            injectedRequestFactory = requestFactory,
            injectedPendingIndexCoordinator = pendingIndexCoordinator,
            injectedConfigSaver = configSaver,
            injectedEnsureRagWorkspaceRoot = ensureRagWorkspaceRoot,
            injectedListRagWorkspaceRoots = listRoots,
            injectedEnsureRagWorkspaceRootForSession = ensureForSession,
            injectedKgEstimator = kgEstimator,
            injectedIndexBaseline = indexBaseline,
        )
    }

    private class FakeIndexBaseline(var stored: ChunkSettings? = null) : IndexedChunkBaseline {
        override fun load(): ChunkSettings? = stored
        override fun save(settings: ChunkSettings) {
            stored = settings
        }
    }

    @Test
    fun `并发配置变换会串行持久化且最终落盘状态与内存一致`() {
        val firstSaveEntered = CountDownLatch(1)
        val releaseFirstSave = CountDownLatch(1)
        val secondUpdateAttempted = CountDownLatch(1)
        val secondTransformEntered = CountDownLatch(1)
        val saved = Collections.synchronizedList(
            mutableListOf<com.promenar.infinity.data.rag.RagConfiguration>(),
        )
        val vm = createViewModel(configSaver = { config ->
            if (saved.isEmpty()) {
                firstSaveEntered.countDown()
                assertThat(releaseFirstSave.await(2, TimeUnit.SECONDS)).isTrue()
            }
            saved += config
        })
        val initialConfig = vm.config.value

        val memoryUpdate = Thread {
            vm.updateConfig { current -> current.copy(memoryLimit = 9) }
        }
        val documentUpdate = Thread {
            secondUpdateAttempted.countDown()
            vm.updateConfig { current ->
                secondTransformEntered.countDown()
                current.copy(docLimit = 14)
            }
        }

        memoryUpdate.start()
        assertThat(firstSaveEntered.await(2, TimeUnit.SECONDS)).isTrue()
        documentUpdate.start()
        assertThat(secondUpdateAttempted.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(secondTransformEntered.await(200, TimeUnit.MILLISECONDS)).isFalse()
        releaseFirstSave.countDown()
        memoryUpdate.join(2_000)
        documentUpdate.join(2_000)

        assertThat(memoryUpdate.isAlive).isFalse()
        assertThat(documentUpdate.isAlive).isFalse()
        assertThat(vm.config.value.memoryLimit).isEqualTo(9)
        assertThat(vm.config.value.docLimit).isEqualTo(14)
        assertThat(saved).containsExactly(
            initialConfig.copy(memoryLimit = 9),
            vm.config.value,
        ).inOrder()
    }

    @Test
    fun `RAG configuration UI and debug screen do not expose unused embedding or no-op cleanup controls`() {
        val configScreen = java.nio.file.Files.readAllBytes(
            java.nio.file.Path.of("app/src/main/java/com/promenar/infinity/ui/rag/KnowledgeDebugSettingsScreen.kt"),
        ).toString(Charsets.UTF_8)
        val debugScreen = java.nio.file.Files.readAllBytes(
            java.nio.file.Path.of("app/src/main/java/com/promenar/infinity/ui/rag/RagDebugScreen.kt"),
        ).toString(Charsets.UTF_8)

        assertThat(configScreen).doesNotContain("rag_global_embed_dimension_slider")
        assertThat(configScreen).doesNotContain("rag_global_embed_tokens_slider")
        assertThat(debugScreen).doesNotContain(".clickable { }")
        assertThat(debugScreen).doesNotContain("R.string.rag_debug_cleanup")
    }

    @Test
    fun `Home重命名失败会让新建Folder ViewModel看到共享pending并完成重试`() = runTest {
        var shouldFail = true
        val coordinator = PendingDocumentIndexCoordinator(
            FileIndexEventSink { if (shouldFail) error("queue unavailable") },
        )
        val target = RenameIndexTarget("doc-a", "hash-a", 101L)
        coEvery {
            workspaceRepository.rename("rag-root", "folder", "renamed", null)
        } returns RenameResult.Success(
            name = "renamed",
            targetHash = "folder-hash",
            targetEpoch = 100L,
            changed = true,
            affectedTargets = listOf(target),
        )
        val homeVm = createViewModel(pendingIndexCoordinator = coordinator)
        advanceUntilIdle()
        homeVm.renameFolder("folder", "renamed")
        advanceUntilIdle()
        assertThat(homeVm.pendingRenameIndexTargets.value).containsExactly(target)

        val folderVm = createViewModel(pendingIndexCoordinator = coordinator)
        advanceUntilIdle()
        assertThat(folderVm.pendingRenameIndexTargets.value).containsExactly(target)

        shouldFail = false
        folderVm.retryPendingRenameIndexNow()
        advanceUntilIdle()
        assertThat(folderVm.pendingRenameIndexTargets.value).isEmpty()
        assertThat(homeVm.pendingRenameIndexTargets.value).isEmpty()
    }

    @Test
    fun `queue失败notice不会被共享pending增减覆盖或清除`() = runTest {
        var callback: ((List<com.promenar.infinity.data.rag.VectorizationTask>, com.promenar.infinity.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            callback = firstArg()
            AutoCloseable { }
        }
        var shouldFail = true
        val coordinator = PendingDocumentIndexCoordinator(
            FileIndexEventSink { if (shouldFail) error("queue unavailable") },
        )
        val event = com.promenar.infinity.data.rag.FileIndexEvent.Changed(
            workspaceRootUuid = "rag-root",
            fileUuid = "doc-a",
            contentHash = "hash-a",
            targetEpoch = 101L,
        )
        val vm = createViewModel(pendingIndexCoordinator = coordinator)
        advanceUntilIdle()
        val failedTask = com.promenar.infinity.data.rag.VectorizationTask(
            id = "failed-ref",
            type = com.promenar.infinity.data.rag.VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
            workspaceRootUuid = "rag-root",
            docId = "queue-doc",
            status = "failed",
            error = "embedding unavailable",
        )
        callback?.invoke(listOf(failedTask), failedTask)

        runCatching { coordinator.publish(event) }
        advanceUntilIdle()
        assertThat(vm.pendingRenameIndexTargets.value).hasSize(1)
        assertThat(vm.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_FAILED)

        shouldFail = false
        assertThat(coordinator.retry(event)).isTrue()
        advanceUntilIdle()
        assertThat(vm.pendingRenameIndexTargets.value).isEmpty()
        assertThat(vm.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_FAILED)
        assertThat(vm.isIndexing.value).isTrue()
    }

    @Test
    fun `queue处理中共享pending清空仍保持indexing`() = runTest {
        val processingState = MutableStateFlow(
            com.promenar.infinity.data.rag.VectorizationQueue.QueueState(
                queue = emptyList(),
                currentTask = null,
                isProcessing = true,
            ),
        )
        every { vectorizationQueue.state } returns processingState
        var shouldFail = true
        val coordinator = PendingDocumentIndexCoordinator(
            FileIndexEventSink { if (shouldFail) error("queue unavailable") },
        )
        val event = com.promenar.infinity.data.rag.FileIndexEvent.Changed(
            workspaceRootUuid = "rag-root",
            fileUuid = "doc-a",
            contentHash = "hash-a",
            targetEpoch = 101L,
        )
        val vm = createViewModel(pendingIndexCoordinator = coordinator)
        advanceUntilIdle()
        runCatching { coordinator.publish(event) }
        advanceUntilIdle()
        assertThat(vm.isIndexing.value).isTrue()

        shouldFail = false
        assertThat(coordinator.retry(event)).isTrue()
        advanceUntilIdle()

        assertThat(vm.pendingRenameIndexTargets.value).isEmpty()
        assertThat(vm.isIndexing.value).isTrue()
    }

    @Test
    fun `共享pending存在时idle queue回调不会隐藏重试入口`() = runTest {
        var callback: ((List<com.promenar.infinity.data.rag.VectorizationTask>, com.promenar.infinity.data.rag.VectorizationTask?) -> Unit)? = null
        val queueState = MutableStateFlow(
            com.promenar.infinity.data.rag.VectorizationQueue.QueueState(
                queue = emptyList(),
                currentTask = null,
                isProcessing = true,
            ),
        )
        every { vectorizationQueue.state } returns queueState
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            callback = firstArg()
            AutoCloseable { }
        }
        val coordinator = PendingDocumentIndexCoordinator(
            FileIndexEventSink { error("queue unavailable") },
        )
        val event = com.promenar.infinity.data.rag.FileIndexEvent.Changed(
            workspaceRootUuid = "rag-root",
            fileUuid = "doc-a",
            contentHash = "hash-a",
            targetEpoch = 101L,
        )
        val vm = createViewModel(pendingIndexCoordinator = coordinator)
        advanceUntilIdle()
        runCatching { coordinator.publish(event) }
        advanceUntilIdle()
        assertThat(vm.isIndexing.value).isTrue()

        val active = com.promenar.infinity.data.rag.VectorizationTask(
            id = "active-ref",
            type = com.promenar.infinity.data.rag.VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
            workspaceRootUuid = "rag-root",
            docId = "queue-doc",
            status = "vectorizing",
        )
        callback?.invoke(listOf(active), active)
        assertThat(vm.isIndexing.value).isTrue()

        queueState.value = queueState.value.copy(isProcessing = false)
        callback?.invoke(emptyList(), null)

        assertThat(vm.pendingRenameIndexTargets.value).hasSize(1)
        assertThat(vm.isIndexing.value).isTrue()
    }

    @Test
    fun `共享pending存在时dismiss会立即保持可见重试入口`() = runTest {
        val coordinator = PendingDocumentIndexCoordinator(
            FileIndexEventSink { error("queue unavailable") },
        )
        val target = RenameIndexTarget("doc-a", "hash-a", 101L)
        coEvery {
            workspaceRepository.rename("rag-root", "folder", "renamed", null)
        } returns RenameResult.Success(
            name = "renamed",
            targetHash = "folder-hash",
            targetEpoch = 100L,
            changed = true,
            affectedTargets = listOf(target),
        )
        val vm = createViewModel(pendingIndexCoordinator = coordinator)
        advanceUntilIdle()
        vm.renameFolder("folder", "renamed")
        advanceUntilIdle()

        vm.dismissQueueError()
        advanceUntilIdle()

        assertThat(vm.pendingRenameIndexTargets.value).containsExactly(target)
        assertThat(vm.indexingNotice.value).isNull()
        assertThat(vm.isIndexing.value).isTrue()
    }

    @Test
    fun `知识库导入复用共享安全管线并保留目标文件夹`() = runTest {
        val uri = mockk<android.net.Uri>()
        every { uri.lastPathSegment } returns "unsafe.bin"
        every { uri.toString() } returns "content://fixture/unsafe.bin"
        val request = ShareRequest(
            uris = listOf(uri),
            mimeType = "application/octet-stream",
            fingerprint = "fixture",
            canonicalSizeBytes = 7,
            requestId = "request",
            targetWorkspaceRootUuid = "rag-root",
        )
        val importer = mockk<SharedFileImporter>()
        coEvery {
            importer.import(request, "rag-root", null, "folder-docs")
        } returns ShareImportBatchResult(
            listOf(
                ShareImportItem(
                    uri = uri,
                    displayName = "unsafe.bin",
                    mimeType = "application/octet-stream",
                    sizeBytes = 4,
                    status = ShareImportStatus.Rejected,
                    reason = ShareRejectReason.UnsupportedMime,
                )
            )
        )
        val vm = createViewModel(
            importer = importer,
            requestFactory = { requestedUri, root ->
                assertThat(requestedUri).isEqualTo(uri)
                assertThat(root).isEqualTo("rag-root")
                request
            },
        )

        vm.importDocuments(listOf(uri), folderId = "folder-docs")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            importer.import(request, "rag-root", null, "folder-docs")
        }
        assertThat(vm.indexingNotice.value).isNotNull()
        assertThat(vm.indexingNotice.value!!.code).isEqualTo(IndexingNotice.CODE_IMPORT_FAILED)
        assertThat(vm.indexingNotice.value!!.severity).isEqualTo(NoticeSeverity.Error)
        assertThat(vm.indexingNotice.value!!.technical).contains("unsafe.bin")
        assertThat(vm.indexingNotice.value!!.technical).contains("UnsupportedMime")
    }

    @Test
    fun `共享导入器异常会成为可见失败且不会被部分成功清除`() = runTest {
        val good = mockk<android.net.Uri>()
        val failed = mockk<android.net.Uri>()
        every { good.lastPathSegment } returns "good.txt"
        every { good.toString() } returns "content://fixture/good.txt"
        every { failed.lastPathSegment } returns "failed.txt"
        every { failed.toString() } returns "content://fixture/failed.txt"
        val importer = mockk<SharedFileImporter>()
        val goodRequest = requestFor(good)
        val failedRequest = requestFor(failed)
        coEvery { importer.import(goodRequest, "rag-root", null, "rag-root") } returns
            ShareImportBatchResult(
                listOf(
                    ShareImportItem(
                        uri = good,
                        displayName = "good.txt",
                        mimeType = "text/plain",
                        sizeBytes = 4,
                        status = ShareImportStatus.Created,
                    )
                )
            )
        coEvery { importer.import(failedRequest, "rag-root", null, "rag-root") } throws
            java.io.IOException("provider died")
        val vm = createViewModel(
            importer = importer,
            requestFactory = { uri, _ ->
                if (uri.lastPathSegment == "good.txt") goodRequest else failedRequest
            },
        )

        vm.importDocuments(listOf(good, failed))
        advanceUntilIdle()

        assertThat(vm.indexingNotice.value).isNotNull()
        assertThat(vm.indexingNotice.value!!.code).isEqualTo(IndexingNotice.CODE_IMPORT_FAILED)
        assertThat(vm.indexingNotice.value!!.technical).contains("failed.txt")
        assertThat(vm.indexingNotice.value!!.technical).contains("provider died")
    }

    @Test
    fun `批量重新索引会委托到仓库读取并入队`() = runTest {
        val root = "rag-root"
        val doc = FileEntry(
            uuid = "doc-1",
            parentUuid = root,
            name = "doc.txt",
            hash = "current-hash",
            mimeType = "text/plain",
            physicalRootPath = "/tmp/doc.txt",
            materializedPath = "/doc.txt",
            isDirectory = false,
            createdAt = 1L,
            updatedAt = 1L,
        )
        coEvery { workspaceRepository.getByUuid(root, "doc-1") } returns doc
        val vm = createViewModel()
        advanceUntilIdle()
        vm.reindexDocuments(listOf("doc-1"))
        advanceUntilIdle()

        coVerify(exactly = 1) { workspaceRepository.getByUuid(root, "doc-1") }
        coVerify(exactly = 0) { fileOperationRepository.readFileRange(root, "doc-1") }
        coVerify(exactly = 1) {
            vectorizationQueue.enqueueDocumentReference(
                workspaceRootUuid = root,
                docId = "doc-1",
                docTitle = "doc.txt",
                sourceMimeType = "text/plain",
                targetContentHash = "current-hash",
                targetEpoch = 1L,
                kgStrategy = any(),
            )
        }
        assertThat(vm.indexingNotice.value).isNull()
    }

    @Test
    fun `批量重新索引空输入给用户级反馈并不执行操作`() = runTest {
        val vm = createViewModel()
        vm.reindexDocuments(emptyList())
        advanceUntilIdle()

        coVerify(exactly = 0) { fileOperationRepository.readFileRange(any(), any()) }
        assertThat(vm.indexingNotice.value).isNotNull()
        assertThat(vm.indexingNotice.value!!.code).isEqualTo(IndexingNotice.CODE_WARNING)
    }

    @Test
    fun `移动多个文档成功时会触发回调并清空失败列表`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        coEvery { workspaceRepository.updateParent("rag-root", "doc-1", "folder-a") } returns Unit
        coEvery { workspaceRepository.updateParent("rag-root", "doc-2", "folder-a") } returns Unit

        var result = false
        var failed = listOf<String>()
        vm.moveDocuments(listOf("doc-1", "doc-2"), "folder-a") { ok, failedIds ->
            result = ok
            failed = failedIds
        }
        advanceUntilIdle()

        coVerify(exactly = 1) { workspaceRepository.updateParent("rag-root", "doc-1", "folder-a") }
        coVerify(exactly = 1) { workspaceRepository.updateParent("rag-root", "doc-2", "folder-a") }
        assertThat(result).isTrue()
        assertThat(failed).isEmpty()
    }

    @Test
    fun `移动失败时回调 false 并记录失败列表`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        coEvery { workspaceRepository.updateParent("rag-root", "doc-1", "folder-a") } returns Unit
        coEvery {
            workspaceRepository.updateParent("rag-root", "doc-2", "folder-a")
        } throws RuntimeException("move failed")

        var result = true
        var failed = listOf<String>()
        vm.moveDocuments(listOf("doc-1", "doc-2"), "folder-a") { ok, failedIds ->
            result = ok
            failed = failedIds
        }
        advanceUntilIdle()

        coVerify(exactly = 1) { workspaceRepository.updateParent("rag-root", "doc-1", "folder-a") }
        coVerify(exactly = 1) { workspaceRepository.updateParent("rag-root", "doc-2", "folder-a") }
        assertThat(result).isFalse()
        assertThat(failed).containsExactly("doc-2")
        assertThat(vm.indexingNotice.value).isNotNull()
        assertThat(vm.indexingNotice.value!!.code).isEqualTo(IndexingNotice.CODE_MOVE_FAILED)
        assertThat(vm.indexingNotice.value!!.technical).doesNotContain("move failed")
    }

    @Test
    fun `移动错误会替换旧索引失败且不误暴露索引重试`() = runTest {
        var callback: ((List<com.promenar.infinity.data.rag.VectorizationTask>, com.promenar.infinity.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            callback = firstArg()
            AutoCloseable { }
        }
        val vm = createViewModel()
        callback?.invoke(
            emptyList(),
            com.promenar.infinity.data.rag.VectorizationTask(
                id = "failed-reference",
                type = com.promenar.infinity.data.rag.VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
                workspaceRootUuid = "rag-root",
                docId = "doc-1",
                status = "failed",
            ),
        )
        assertThat(vm.canRetryLastFailedIndex.value).isTrue()
        coEvery { workspaceRepository.updateParent("rag-root", "doc-1", "folder-a") } throws
            IllegalStateException("private move detail")

        vm.moveDocuments(listOf("doc-1"), "folder-a")
        advanceUntilIdle()

        assertThat(vm.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_MOVE_FAILED)
        assertThat(vm.canRetryLastFailedIndex.value).isFalse()
    }

    @Test
    fun `移动失败提示不会被空队列状态回调意外清除`() = runTest {
        var callback: ((List<com.promenar.infinity.data.rag.VectorizationTask>, com.promenar.infinity.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            callback = firstArg()
            AutoCloseable { }
        }
        val vm = createViewModel()
        coEvery { workspaceRepository.updateParent("rag-root", "doc-1", "folder-a") } throws
            IllegalStateException("private move detail")

        vm.moveDocuments(listOf("doc-1"), "folder-a")
        advanceUntilIdle()
        callback?.invoke(emptyList(), null)

        assertThat(vm.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_MOVE_FAILED)
        assertThat(vm.isIndexing.value).isTrue()
    }

    @Test
    fun `移动进行中重复提交被门禁且不会二次写入`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { workspaceRepository.updateParent("rag-root", "doc-1", "folder-a") } coAnswers {
            gate.await()
        }
        val vm = createViewModel()
        advanceUntilIdle()

        var secondFailedIds = emptyList<String>()
        vm.moveDocuments(listOf("doc-1"), "folder-a")
        testDispatcher.scheduler.runCurrent()
        assertThat(vm.isMovingDocuments.value).isTrue()
        vm.moveDocuments(listOf("doc-1"), "folder-a") { _, failedIds ->
            secondFailedIds = failedIds
        }
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { workspaceRepository.updateParent("rag-root", "doc-1", "folder-a") }
        assertThat(secondFailedIds).containsExactly("doc-1")

        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.isMovingDocuments.value).isFalse()
    }

    @Test
    fun `批量删除部分失败会继续处理并逐项返回失败标识`() = runTest {
        coEvery { workspaceRepository.moveToRecycleBin("rag-root", "doc-1") } returns Unit
        coEvery { workspaceRepository.moveToRecycleBin("rag-root", "doc-2") } throws
            IllegalStateException("private delete detail")
        coEvery { workspaceRepository.moveToRecycleBin("rag-root", "doc-3") } returns Unit
        val vm = createViewModel()
        advanceUntilIdle()

        var failedIds = emptyList<String>()
        vm.deleteDocuments(listOf("doc-1", "doc-2", "doc-3")) { _, failed ->
            failedIds = failed
        }
        advanceUntilIdle()

        coVerify(exactly = 1) { workspaceRepository.moveToRecycleBin("rag-root", "doc-1") }
        coVerify(exactly = 1) { workspaceRepository.moveToRecycleBin("rag-root", "doc-2") }
        coVerify(exactly = 1) { workspaceRepository.moveToRecycleBin("rag-root", "doc-3") }
        coVerify(exactly = 0) { workspaceRepository.permanentDelete(any(), any()) }
        assertThat(failedIds).containsExactly("doc-2")
        assertThat(vm.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_DELETE_FAILED)
        assertThat(vm.indexingNotice.value?.technical).doesNotContain("private delete detail")
    }

    @Test
    fun `批量删除收到取消信号时立即传播且不继续处理后续项`() = runTest {
        coEvery { workspaceRepository.moveToRecycleBin("rag-root", "doc-1") } throws
            kotlinx.coroutines.CancellationException("cancel delete")
        val vm = createViewModel()
        advanceUntilIdle()
        var callbackInvoked = false

        vm.deleteDocuments(listOf("doc-1", "doc-2")) { _, _ -> callbackInvoked = true }
        advanceUntilIdle()

        coVerify(exactly = 1) { workspaceRepository.moveToRecycleBin("rag-root", "doc-1") }
        coVerify(exactly = 0) { workspaceRepository.moveToRecycleBin("rag-root", "doc-2") }
        coVerify(exactly = 0) { workspaceRepository.permanentDelete(any(), any()) }
        assertThat(callbackInvoked).isFalse()
        assertThat(vm.isDeletingDocuments.value).isFalse()
    }

    private fun requestFor(uri: android.net.Uri) = ShareRequest(
        uris = listOf(uri),
        mimeType = "text/plain",
        fingerprint = uri.toString(),
        canonicalSizeBytes = uri.toString().length,
        requestId = uri.lastPathSegment.orEmpty(),
        targetWorkspaceRootUuid = "rag-root",
    )

    @Test
    fun `init loadStats uses document count from workspace`() = runTest {
        coEvery { kgRepository.getNodeCount() } returns 5

        val vm = createViewModel()

        coVerify { kgRepository.getNodeCount() }
        assertThat(vm.stats.value.graphEntityCount).isEqualTo(5)
    }

    @Test
    fun `loadCollections refreshes stats from repositories`() = runTest {
        coEvery { kgRepository.getNodeCount() } returns 0

        val vm = createViewModel()

        coEvery { kgRepository.getNodeCount() } returns 7

        vm.loadCollections()

        assertThat(vm.stats.value.graphEntityCount).isEqualTo(7)
    }

    @Test
    fun `search clears results on blank query`() = runTest {
        val vm = createViewModel()
        vm.search("  ")

        assertThat(vm.searchResults.value).isEmpty()
        assertThat(vm.searchState.value).isEqualTo(RagSearchUiState.Idle)
    }

    @Test
    fun `正文FTS命中展示围绕查询词的snippet并标记正文来源`() = runTest {
        every { workspaceRepository.searchByName("rag-root", "needle") } returns flowOf(emptyList())
        coEvery { workspaceRepository.getByUuid("rag-root", "nested-doc") } returns fileEntry(
            uuid = "nested-doc",
            name = "没有标题命中.md",
            parentUuid = "nested-folder",
            materializedPath = "/nested/没有标题命中.md",
        )
        coEvery {
            keywordSearcher.searchWithOutcome(
                "needle",
                limit = 20,
                options = KeywordSearcher.SearchOptions(workspaceRootUuid = "rag-root"),
            )
        } returns
            KeywordSearchOutcome(
                results = listOf(
                    com.promenar.infinity.data.rag.SearchResult(
                        id = "chunk-1",
                        docId = "nested-doc",
                        content = "前文".repeat(30) + " needle 正文命中 " + "后文".repeat(30),
                        createdAt = 1L,
                        similarity = 1f,
                    ),
                ),
                mode = KeywordSearchMode.Fts,
            )
        val vm = createViewModel()
        advanceUntilIdle()

        vm.search("needle")
        advanceTimeBy(RagSearchContract.DEBOUNCE_MILLIS)
        advanceUntilIdle()

        val state = vm.searchState.value as RagSearchUiState.Results
        assertThat(state.items).hasSize(1)
        assertThat(state.items.single().document.id).isEqualTo("nested-doc")
        assertThat(state.items.single().matchKind).isEqualTo(RagSearchMatchKind.FtsBody)
        assertThat(state.items.single().snippet).contains("needle")
        assertThat(state.items.single().snippet!!.length).isAtMost(RagSearchContract.SNIPPET_MAX_CHARS)
        coVerify(exactly = 1) {
            keywordSearcher.searchWithOutcome(
                "needle",
                limit = 20,
                options = KeywordSearcher.SearchOptions(workspaceRootUuid = "rag-root"),
            )
        }
    }

    @Test
    fun `标题回退会显式标注且FTS异常转为typed warning`() = runTest {
        every { workspaceRepository.searchByName("rag-root", "Alpha") } returns flowOf(
            listOf(fileEntry(uuid = "title-doc", name = "Alpha.md", materializedPath = "/Alpha.md")),
        )
        coEvery {
            keywordSearcher.searchWithOutcome(
                "Alpha",
                limit = 20,
                options = KeywordSearcher.SearchOptions(workspaceRootUuid = "rag-root"),
            )
        } returns
            KeywordSearchOutcome(
                results = listOf(searchHit("stale-like-doc", "Alpha body")),
                mode = KeywordSearchMode.LikeFallback,
                technical = "IllegalStateException",
            )
        val vm = createViewModel()
        advanceUntilIdle()

        vm.search("Alpha")
        advanceTimeBy(RagSearchContract.DEBOUNCE_MILLIS)
        advanceUntilIdle()

        val state = vm.searchState.value as RagSearchUiState.Warning
        assertThat(state.code).isEqualTo(RagSearchErrorCode.FtsUnavailable)
        assertThat(state.fallbackItems.single().matchKind).isEqualTo(RagSearchMatchKind.TitleFallback)
        assertThat(state.fallbackItems.map { it.document.id }).doesNotContain("stale-like-doc")
        assertThat(state.technical).doesNotContain("private fts detail")
    }

    @Test
    fun `新搜索取消旧搜索且忽略不合作旧请求的迟到结果`() = runTest {
        val releaseFirst = CompletableDeferred<Unit>()
        every { workspaceRepository.searchByName("rag-root", any()) } returns flowOf(emptyList())
        coEvery { workspaceRepository.getByUuid("rag-root", any()) } coAnswers {
            fileEntry(uuid = secondArg(), name = "${secondArg<String>()}.md", materializedPath = "/${secondArg<String>()}.md")
        }
        coEvery {
            keywordSearcher.searchWithOutcome(
                "first", 20, KeywordSearcher.SearchOptions(workspaceRootUuid = "rag-root"),
            )
        } coAnswers {
            withContext(NonCancellable) { releaseFirst.await() }
            KeywordSearchOutcome(
                results = listOf(searchHit("first-doc", "first body")),
                mode = KeywordSearchMode.Fts,
            )
        }
        coEvery {
            keywordSearcher.searchWithOutcome(
                "second", 20, KeywordSearcher.SearchOptions(workspaceRootUuid = "rag-root"),
            )
        } returns
            KeywordSearchOutcome(
                results = listOf(searchHit("second-doc", "second body")),
                mode = KeywordSearchMode.Fts,
            )
        val vm = createViewModel()
        advanceUntilIdle()

        vm.search("first")
        advanceTimeBy(RagSearchContract.DEBOUNCE_MILLIS)
        runCurrent()
        vm.search("second")
        advanceTimeBy(RagSearchContract.DEBOUNCE_MILLIS)
        runCurrent()
        releaseFirst.complete(Unit)
        advanceUntilIdle()

        val state = vm.searchState.value as RagSearchUiState.Results
        assertThat(state.query).isEqualTo("second")
        assertThat(state.items.map { it.document.id }).containsExactly("second-doc")
    }

    @Test
    fun `输入新查询后立即离开旧结果态但仍保持debounce`() = runTest {
        every { workspaceRepository.searchByName("rag-root", any()) } returns flowOf(emptyList())
        coEvery { workspaceRepository.getByUuid("rag-root", "first-doc") } returns
            fileEntry(uuid = "first-doc", name = "first.md", materializedPath = "/first.md")
        coEvery {
            keywordSearcher.searchWithOutcome(
                "first", 20, KeywordSearcher.SearchOptions(workspaceRootUuid = "rag-root"),
            )
        } returns
            KeywordSearchOutcome(
                results = listOf(searchHit("first-doc", "first body")),
                mode = KeywordSearchMode.Fts,
            )
        val vm = createViewModel()
        advanceUntilIdle()
        vm.search("first")
        advanceTimeBy(RagSearchContract.DEBOUNCE_MILLIS)
        advanceUntilIdle()
        assertThat(vm.searchState.value).isInstanceOf(RagSearchUiState.Results::class.java)

        vm.search("second")

        assertThat(vm.searchState.value).isEqualTo(RagSearchUiState.Loading("second"))
        coVerify(exactly = 0) {
            keywordSearcher.searchWithOutcome(
                "second", 20, KeywordSearcher.SearchOptions(workspaceRootUuid = "rag-root"),
            )
        }
    }

    @Test
    fun `folders comes from root scoped children`() = runTest {
        val dirs = listOf(
            FileEntry(
                uuid = "f1", parentUuid = null, name = "Folder 1", hash = "",
                physicalRootPath = "/", materializedPath = "/Folder 1",
                isDirectory = true, createdAt = 100L, updatedAt = 100L
            ),
            FileEntry(
                uuid = "f2", parentUuid = null, name = "Folder 2", hash = "",
                physicalRootPath = "/", materializedPath = "/Folder 2",
                isDirectory = true, createdAt = 200L, updatedAt = 200L
            )
        )
        every { workspaceRepository.observeChildren("rag-root", "rag-root") } returns flowOf(dirs)

        val vm = createViewModel()

        assertThat(vm.folders.value).hasSize(2)
        assertThat(vm.folders.value[0].id).isEqualTo("f1")
    }

    @Test
    fun `documents comes from root scoped children non-directory entries`() = runTest {
        val entries = listOf(
            FileEntry(
                uuid = "d1", parentUuid = null, name = "Doc 1.txt", hash = "abc",
                physicalRootPath = "/", materializedPath = "/Doc 1.txt",
                isDirectory = false, createdAt = 100L, updatedAt = 100L
            )
        )
        every { workspaceRepository.observeChildren("rag-root", "rag-root") } returns flowOf(entries)

        val vm = createViewModel()

        assertThat(vm.documents.value).hasSize(1)
        assertThat(vm.documents.value[0].id).isEqualTo("d1")
    }

    @Test
    fun `目录重命名会为全部受影响文件发布当前target事件`() = runTest {
        val targets = listOf(
            RenameIndexTarget("doc-a", "hash-a", 101L),
            RenameIndexTarget("doc-b", "hash-b", 102L),
        )
        coEvery {
            workspaceRepository.rename("rag-root", "folder", "renamed", null)
        } returns RenameResult.Success(
            name = "renamed",
            targetHash = "folder-hash",
            targetEpoch = 100L,
            changed = true,
            affectedTargets = targets,
        )
        val vm = createViewModel()
        advanceUntilIdle()

        vm.renameFolder("folder", "renamed")
        advanceUntilIdle()

        targets.forEach { target ->
            coVerify(exactly = 1) {
                vectorizationQueue.publish(
                    com.promenar.infinity.data.rag.FileIndexEvent.Changed(
                        workspaceRootUuid = "rag-root",
                        fileUuid = target.fileUuid,
                        contentHash = target.targetHash,
                        targetEpoch = target.targetEpoch,
                    ),
                )
            }
        }
    }

    @Test
    fun `目录重命名提交后索引发布失败只显示索引警告`() = runTest {
        val target = RenameIndexTarget("doc-a", "hash-a", 101L)
        coEvery {
            workspaceRepository.rename("rag-root", "folder", "renamed", null)
        } returns RenameResult.Success(
            name = "renamed",
            targetHash = "folder-hash",
            targetEpoch = 100L,
            changed = true,
            affectedTargets = listOf(target),
        )
        coEvery { vectorizationQueue.publish(any()) } throws
            IllegalStateException("queue unavailable")
        val vm = createViewModel()
        advanceUntilIdle()

        vm.renameFolder("folder", "renamed")
        advanceUntilIdle()

        assertThat(vm.indexingNotice.value?.severity).isEqualTo(NoticeSeverity.Warning)
        assertThat(vm.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_WARNING)
        coVerify(exactly = 1) {
            workspaceRepository.rename("rag-root", "folder", "renamed", null)
        }
    }

    @Test
    fun `目录重命名发布失败会跨no-op重命名保留待补偿target`() = runTest {
        val target = RenameIndexTarget("doc-a", "hash-a", 101L)
        coEvery {
            workspaceRepository.rename("rag-root", "folder", "renamed", null)
        } returnsMany listOf(
            RenameResult.Success(
                name = "renamed",
                targetHash = "folder-hash",
                targetEpoch = 100L,
                changed = true,
                affectedTargets = listOf(target),
            ),
            RenameResult.Success(
                name = "renamed",
                targetHash = "folder-hash",
                targetEpoch = 100L,
                changed = false,
                affectedTargets = emptyList(),
            ),
        )
        coEvery { vectorizationQueue.publish(any()) } throws IllegalStateException("queue unavailable")
        val vm = createViewModel()
        advanceUntilIdle()

        vm.renameFolder("folder", "renamed")
        advanceUntilIdle()
        assertThat(vm.pendingRenameIndexTargets.value).containsExactly(target)

        vm.renameFolder("folder", "renamed")
        advanceUntilIdle()
        assertThat(vm.pendingRenameIndexTargets.value).containsExactly(target)
    }

    @Test
    fun `目录重命名补偿重试会保留部分失败并在全部成功后清除`() = runTest {
        val first = RenameIndexTarget("doc-a", "hash-a", 101L)
        val second = RenameIndexTarget("doc-b", "hash-b", 102L)
        val firstEvent = com.promenar.infinity.data.rag.FileIndexEvent.Changed(
            workspaceRootUuid = "rag-root",
            fileUuid = first.fileUuid,
            contentHash = first.targetHash,
            targetEpoch = first.targetEpoch,
        )
        val secondEvent = com.promenar.infinity.data.rag.FileIndexEvent.Changed(
            workspaceRootUuid = "rag-root",
            fileUuid = second.fileUuid,
            contentHash = second.targetHash,
            targetEpoch = second.targetEpoch,
        )
        coEvery {
            workspaceRepository.rename("rag-root", "folder", "renamed", null)
        } returns RenameResult.Success(
            name = "renamed",
            targetHash = "folder-hash",
            targetEpoch = 100L,
            changed = true,
            affectedTargets = listOf(first, second),
        )
        coEvery { vectorizationQueue.publish(any()) } throws IllegalStateException("queue unavailable")
        val vm = createViewModel()
        advanceUntilIdle()
        vm.renameFolder("folder", "renamed")
        advanceUntilIdle()
        assertThat(vm.pendingRenameIndexTargets.value).containsExactly(first, second).inOrder()

        coEvery { vectorizationQueue.publish(firstEvent) } returns Unit
        coEvery { vectorizationQueue.publish(secondEvent) } throws IllegalStateException("still unavailable")
        vm.retryPendingRenameIndexNow()
        assertThat(vm.pendingRenameIndexTargets.value).containsExactly(second)

        coEvery { vectorizationQueue.publish(secondEvent) } returns Unit
        vm.retryPendingRenameIndexNow()
        assertThat(vm.pendingRenameIndexTargets.value).isEmpty()
        assertThat(vm.indexingNotice.value).isNull()
    }

    @Test
    fun `目录重命名补偿重试遇到取消会传播且保留未发布target`() = runTest {
        val target = RenameIndexTarget("doc-a", "hash-a", 101L)
        coEvery {
            workspaceRepository.rename("rag-root", "folder", "renamed", null)
        } returns RenameResult.Success(
            name = "renamed",
            targetHash = "folder-hash",
            targetEpoch = 100L,
            changed = true,
            affectedTargets = listOf(target),
        )
        coEvery { vectorizationQueue.publish(any()) } throws IllegalStateException("queue unavailable")
        val vm = createViewModel()
        advanceUntilIdle()
        vm.renameFolder("folder", "renamed")
        advanceUntilIdle()

        coEvery { vectorizationQueue.publish(any()) } throws CancellationException("cancel retry")
        var cancellationPropagated = false
        try {
            vm.retryPendingRenameIndexNow()
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }

        assertThat(cancellationPropagated).isTrue()
        assertThat(vm.pendingRenameIndexTargets.value).containsExactly(target)
    }

    @Test
    fun `套用检索深度档位只改四个检索数量`() = runTest {
        val saved = mutableListOf<com.promenar.infinity.data.rag.RagConfiguration>()
        val vm = createViewModel(configSaver = { saved += it })
        advanceUntilIdle()
        val before = vm.config.value

        vm.applyRetrievalDepth(com.promenar.infinity.data.rag.RetrievalDepth.COMPREHENSIVE)
        advanceUntilIdle()

        val after = vm.config.value
        assertThat(listOf(after.memoryLimit, after.docLimit, after.rerankTopK, after.rerankFinalK))
            .containsExactly(8, 15, 60, 10).inOrder()
        assertThat(after.copy(memoryLimit = before.memoryLimit, docLimit = before.docLimit,
            rerankTopK = before.rerankTopK, rerankFinalK = before.rerankFinalK)).isEqualTo(before)
        assertThat(selectedRetrievalDepthChoice(after))
            .isEqualTo(RetrievalDepthChoice.Level(com.promenar.infinity.data.rag.RetrievalDepth.COMPREHENSIVE))
        assertThat(saved.last()).isEqualTo(after)
    }

    @Test
    fun `首次加载以当前分块参数为索引基线，改分块后提示且改回后消失`() = runTest {
        val baseline = FakeIndexBaseline()
        val vm = createViewModel(indexBaseline = baseline)
        advanceUntilIdle()
        val initial = vm.config.value.chunkSettings()
        assertThat(baseline.stored).isEqualTo(initial)
        assertThat(vm.indexedChunkSettings.value).isEqualTo(initial)

        vm.updateConfig { it.copy(docChunkSize = initial.docChunkSize + 100) }
        advanceUntilIdle()
        assertThat(chunkSettingsChanged(vm.config.value, vm.indexedChunkSettings.value)).isTrue()

        vm.updateConfig { it.copy(docChunkSize = initial.docChunkSize) }
        advanceUntilIdle()
        assertThat(chunkSettingsChanged(vm.config.value, vm.indexedChunkSettings.value)).isFalse()
    }

    @Test
    fun `已有基线时不被当前配置覆盖`() = runTest {
        val old = ChunkSettings(docChunkSize = 400, chunkOverlap = 50, memoryChunkSize = 1000)
        val vm = createViewModel(indexBaseline = FakeIndexBaseline(old))
        advanceUntilIdle()
        assertThat(vm.indexedChunkSettings.value).isEqualTo(old)
        assertThat(chunkSettingsChanged(vm.config.value, vm.indexedChunkSettings.value))
            .isEqualTo(vm.config.value.chunkSettings() != old)
    }

    @Test
    fun `全部重新向量化提交知识库内可索引文档并改写基线`() = runTest {
        val old = ChunkSettings(docChunkSize = 400, chunkOverlap = 50, memoryChunkSize = 1000)
        val baseline = FakeIndexBaseline(old)
        val folder = rootEntry().copy(uuid = "dir-1", parentUuid = "rag-root", name = "notes", materializedPath = "/notes")
        val top = fileEntry("doc-1", "a.txt", materializedPath = "/a.txt").copy(mimeType = "text/plain")
        val nested = fileEntry("doc-2", "b.md", parentUuid = "dir-1", materializedPath = "/notes/b.md").copy(mimeType = "text/markdown")
        val unsupported = fileEntry("doc-3", "c.bin", materializedPath = "/c.bin").copy(mimeType = "application/octet-stream")
        val unhashed = fileEntry("doc-4", "d.txt", materializedPath = "/d.txt").copy(mimeType = "text/plain", hash = "")
        every { workspaceRepository.observeRoots("rag-root") } returns flowOf(listOf(folder, top, unsupported, unhashed))
        coEvery { workspaceRepository.getSubtree("rag-root", "/notes") } returns listOf(folder, nested)
        val vm = createViewModel(indexBaseline = baseline)
        advanceUntilIdle()

        var submitted = -1
        vm.reindexAllDocuments { submitted = it }
        advanceUntilIdle()

        assertThat(submitted).isEqualTo(2)
        coVerify(exactly = 1) {
            vectorizationQueue.enqueueDocumentReference("rag-root", "doc-1", "a.txt", "text/plain", "hash-doc-1", 1L, any())
        }
        coVerify(exactly = 1) {
            vectorizationQueue.enqueueDocumentReference("rag-root", "doc-2", "b.md", "text/markdown", "hash-doc-2", 1L, any())
        }
        coVerify(exactly = 0) {
            vectorizationQueue.enqueueDocumentReference(any(), "doc-3", any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) {
            vectorizationQueue.enqueueDocumentReference(any(), "doc-4", any(), any(), any(), any(), any())
        }
        assertThat(baseline.stored).isEqualTo(vm.config.value.chunkSettings())
        assertThat(vm.indexedChunkSettings.value).isEqualTo(vm.config.value.chunkSettings())
        assertThat(vm.indexingNotice.value).isNull()
    }

    @Test
    fun `全部重新向量化部分失败时保留旧基线并提示`() = runTest {
        val old = ChunkSettings(docChunkSize = 400, chunkOverlap = 50, memoryChunkSize = 1000)
        val baseline = FakeIndexBaseline(old)
        val ok = fileEntry("doc-1", "a.txt", materializedPath = "/a.txt").copy(mimeType = "text/plain")
        val bad = fileEntry("doc-2", "b.txt", materializedPath = "/b.txt").copy(mimeType = "text/plain")
        every { workspaceRepository.observeRoots("rag-root") } returns flowOf(listOf(ok, bad))
        coEvery {
            vectorizationQueue.enqueueDocumentReference(any(), "doc-2", any(), any(), any(), any(), any())
        } throws IllegalStateException("queue full")
        val vm = createViewModel(indexBaseline = baseline)
        advanceUntilIdle()

        var submitted = -1
        vm.reindexAllDocuments { submitted = it }
        advanceUntilIdle()

        assertThat(submitted).isEqualTo(1)
        assertThat(baseline.stored).isEqualTo(old)
        assertThat(vm.indexingNotice.value!!.code).isEqualTo(IndexingNotice.CODE_WARNING)
    }

    @Test
    fun `清除全部向量后把当前分块参数记为基线`() = runTest {
        val baseline = FakeIndexBaseline(ChunkSettings(400, 50, 1000))
        val vm = createViewModel(indexBaseline = baseline)
        advanceUntilIdle()

        vm.clearAllVectors()
        advanceUntilIdle()

        assertThat(baseline.stored).isEqualTo(vm.config.value.chunkSettings())
    }

    private fun rootEntry() = FileEntry(
        uuid = "rag-root",
        workspaceRootUuid = "rag-root",
        parentUuid = null,
        name = "root",
        hash = "",
        isDirectory = true,
        physicalRootPath = "/tmp/rag-root",
        materializedPath = "/",
        createdAt = 1,
        updatedAt = 1,
    )

    private fun fileEntry(
        uuid: String,
        name: String,
        parentUuid: String? = "rag-root",
        materializedPath: String,
    ) = FileEntry(
        uuid = uuid,
        workspaceRootUuid = "rag-root",
        parentUuid = parentUuid,
        name = name,
        hash = "hash-$uuid",
        isDirectory = false,
        physicalRootPath = "/tmp/rag-root",
        materializedPath = materializedPath,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun searchHit(docId: String, content: String) =
        com.promenar.infinity.data.rag.SearchResult(
            id = "chunk-$docId",
            docId = docId,
            content = content,
            createdAt = 1L,
            similarity = 1f,
        )

    @Test
    fun `indexingNotice is initially null`() = runTest {
        val vm = createViewModel()

        assertThat(vm.indexingNotice.value).isNull()
    }

    @Test
    fun `indexingNotice captures task failed state`() = runTest {
        var capturedCallback: ((List<com.promenar.infinity.data.rag.VectorizationTask>, com.promenar.infinity.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            capturedCallback = firstArg()
            AutoCloseable { }
        }

        val vm2 = createViewModel()

        val failedTask = com.promenar.infinity.data.rag.VectorizationTask(
            id = "task-err",
            type = "document",
            docId = "doc-1",
            docTitle = "Test",
            status = "failed",
            progress = 0.0,
            error = "API key invalid"
        )

        capturedCallback?.invoke(listOf(failedTask), failedTask)

        assertThat(vm2.indexingNotice.value).isNotNull()
        assertThat(vm2.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_FAILED)
        assertThat(vm2.indexingNotice.value?.severity).isEqualTo(NoticeSeverity.Error)
        assertThat(vm2.indexingNotice.value?.technical).contains("API key")
    }

    @Test
    fun `indexingNotice updates when non-failed task starts`() = runTest {
        var capturedCallback: ((List<com.promenar.infinity.data.rag.VectorizationTask>, com.promenar.infinity.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            capturedCallback = firstArg()
            AutoCloseable { }
        }

        val vm2 = createViewModel()

        val failedTask = com.promenar.infinity.data.rag.VectorizationTask(
            id = "task-err",
            type = "document",
            docId = "doc-1",
            docTitle = "Test",
            status = "failed",
            progress = 0.0,
            error = "API key invalid"
        )
        capturedCallback?.invoke(listOf(failedTask), failedTask)
        assertThat(vm2.indexingNotice.value).isNotNull()
        assertThat(vm2.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_FAILED)

        val newTask = com.promenar.infinity.data.rag.VectorizationTask(
            id = "task-new",
            type = "document",
            docId = "doc-2",
            docTitle = "New",
            status = "chunking",
            progress = 10.0
        )
        capturedCallback?.invoke(listOf(newTask), newTask)

        assertThat(vm2.indexingNotice.value).isNotNull()
        assertThat(vm2.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_CHUNKING)
    }

    @Test
    fun `isIndexing stays true when task fails with error`() = runTest {
        var capturedCallback: ((List<com.promenar.infinity.data.rag.VectorizationTask>, com.promenar.infinity.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            capturedCallback = firstArg()
            AutoCloseable { }
        }

        val vm = createViewModel()

        val failedTask = com.promenar.infinity.data.rag.VectorizationTask(
            id = "task-err",
            type = "document",
            docId = "doc-1",
            docTitle = "Test",
            status = "failed",
            progress = 0.0,
            error = "Network error"
        )
        capturedCallback?.invoke(listOf(failedTask), failedTask)

        assertThat(vm.isIndexing.value).isTrue()
    }

    @Test
    fun `indexingNotice reflects current task chunking state`() = runTest {
        var capturedCallback: ((List<com.promenar.infinity.data.rag.VectorizationTask>, com.promenar.infinity.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            capturedCallback = firstArg()
            AutoCloseable { }
        }
        val vm = createViewModel()

        val chunkingTask = com.promenar.infinity.data.rag.VectorizationTask(
            id = "task-1",
            type = "document",
            docId = "doc-1",
            docTitle = "Test",
            status = "chunking",
            progress = 10.0
        )
        capturedCallback?.invoke(listOf(chunkingTask), chunkingTask)

        assertThat(vm.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_CHUNKING)
        assertThat(vm.indexingNotice.value?.severity).isEqualTo(NoticeSeverity.Info)
    }

    @Test
    fun `indexingNotice retains format args for vectorizing task`() = runTest {
        var capturedCallback: ((List<com.promenar.infinity.data.rag.VectorizationTask>, com.promenar.infinity.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            capturedCallback = firstArg()
            AutoCloseable { }
        }
        val vm = createViewModel()

        val task = com.promenar.infinity.data.rag.VectorizationTask(
            id = "task-1",
            type = "document",
            docId = "doc-1",
            docTitle = "Test",
            status = "vectorizing",
            progress = 30.0,
            totalChunks = 20,
            subStatus = "Vectorizing 5/20 chunks"
        )
        capturedCallback?.invoke(listOf(task), task)

        assertThat(vm.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_VECTORIZING)
        assertThat(vm.indexingNotice.value?.formatArgs).containsExactly("20")
    }

    @Test
    fun `普通文档失败会建立真实重试目标并重新读取后入队`() = runTest {
        var callback: ((List<com.promenar.infinity.data.rag.VectorizationTask>, com.promenar.infinity.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            callback = firstArg()
            AutoCloseable { }
        }
        val entry = FileEntry(
            uuid = "doc-1",
            workspaceRootUuid = "rag-root",
            parentUuid = "rag-root",
            name = "doc.txt",
            hash = "hash",
            mimeType = "text/plain",
            physicalRootPath = "/tmp/doc.txt",
            materializedPath = "/doc.txt",
            isDirectory = false,
            createdAt = 1L,
            updatedAt = 1L,
        )
        coEvery { workspaceRepository.getByUuid("rag-root", "doc-1") } returns entry
        val taskDao = app.database.vectorizationTaskDao()
        val fileDao = app.database.fileEntryDao()
        val barrier = mockk<com.promenar.infinity.data.repository.WorkspaceDeleteBarrierLease>(relaxed = true)
        coEvery { vectorizationQueue.acquireDeleteBarrier("rag-root", listOf("doc-1")) } returns barrier
        coEvery { fileDao.getActiveByUuid("rag-root", "doc-1") } returns entry
        coEvery { taskDao.getByWorkspaceFile("rag-root", "doc-1", "document_reference") } returns null
        coEvery { taskDao.getByWorkspaceFile("rag-root", "doc-1", "document") } returns com.promenar.infinity.data.local.db.entity.VectorizationTaskEntity(
            id = "failed-document", type = "document", status = "failed", workspaceRootUuid = "rag-root", docId = "doc-1", createdAt = 1, updatedAt = 1,
        )
        coEvery { taskDao.migrateLegacyToTarget(any(), any()) } returns com.promenar.infinity.data.local.db.dao.VectorizationTaskTargetUpsertResult(
            com.promenar.infinity.data.local.db.dao.VectorizationTaskTargetUpsertOutcome.INSERTED, "new-task",
        )
        coEvery { fileOperationRepository.readFileRange("rag-root", "doc-1") } returns ReadResult(
            uuid = "doc-1",
            name = "doc.txt",
            totalLines = 1,
            startLine = 0,
            endLine = 0,
            content = "fresh content",
            hash = "hash",
            lastModified = 1L,
        )
        val vm = createViewModel()
        callback?.invoke(
            emptyList(),
            com.promenar.infinity.data.rag.VectorizationTask(
                id = "failed-document",
                type = "document",
                workspaceRootUuid = "rag-root",
                docId = "doc-1",
                docTitle = "doc.txt",
                status = "failed",
                error = "provider secret detail",
            ),
        )

        assertThat(vm.canRetryLastFailedIndex.value).isTrue()
        vm.retryLastFailedIndex()
        advanceUntilIdle()

        coVerify(exactly = 1) { taskDao.migrateLegacyToTarget("failed-document", match { it.docId == "doc-1" && it.targetContentHash == "hash" }) }
        coVerify(exactly = 1) { barrier.abort() }
        coVerify(exactly = 0) { vectorizationQueue.cancelAndJoin("rag-root", listOf("doc-1")) }
        assertThat(vm.canRetryLastFailedIndex.value).isFalse()
        assertThat(vm.isRetryingLastFailedIndex.value).isFalse()
        assertThat(vm.indexingNotice.value).isNull()
    }

    @Test
    fun `文件引用失败会调用持久化引用重试且重试中拒绝重复触发`() = runTest {
        var callback: ((List<com.promenar.infinity.data.rag.VectorizationTask>, com.promenar.infinity.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            callback = firstArg()
            AutoCloseable { }
        }
        val retryGate = CompletableDeferred<Unit>()
        coEvery { vectorizationQueue.retryDocumentReference("rag-root", "doc-ref") } coAnswers {
            retryGate.await()
            true
        }
        val vm = createViewModel()
        callback?.invoke(
            emptyList(),
            com.promenar.infinity.data.rag.VectorizationTask(
                id = "failed-reference",
                type = com.promenar.infinity.data.rag.VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
                workspaceRootUuid = "rag-root",
                docId = "doc-ref",
                docTitle = "ref.pdf",
                status = "partial",
            ),
        )

        vm.retryLastFailedIndex()
        vm.retryLastFailedIndex()

        assertThat(vm.isRetryingLastFailedIndex.value).isTrue()
        coVerify(exactly = 1) { vectorizationQueue.retryDocumentReference("rag-root", "doc-ref") }

        retryGate.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.isRetryingLastFailedIndex.value).isFalse()
        assertThat(vm.canRetryLastFailedIndex.value).isFalse()
    }

    @Test
    fun `缺少稳定文档身份的失败状态不会暴露重试入口`() = runTest {
        var callback: ((List<com.promenar.infinity.data.rag.VectorizationTask>, com.promenar.infinity.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            callback = firstArg()
            AutoCloseable { }
        }
        val vm = createViewModel()

        callback?.invoke(
            emptyList(),
            com.promenar.infinity.data.rag.VectorizationTask(
                id = "failed-without-target",
                type = "document",
                status = "failed",
            ),
        )

        assertThat(vm.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_FAILED)
        assertThat(vm.canRetryLastFailedIndex.value).isFalse()
        vm.retryLastFailedIndex()
        advanceUntilIdle()
        coVerify(exactly = 0) { vectorizationQueue.enqueueDocument(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { vectorizationQueue.retryDocumentReference(any(), any()) }
    }

    @Test
    fun `关闭失败提示会同步清除重试目标`() = runTest {
        var callback: ((List<com.promenar.infinity.data.rag.VectorizationTask>, com.promenar.infinity.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            callback = firstArg()
            AutoCloseable { }
        }
        val vm = createViewModel()
        callback?.invoke(
            emptyList(),
            com.promenar.infinity.data.rag.VectorizationTask(
                id = "failed-reference",
                type = com.promenar.infinity.data.rag.VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
                workspaceRootUuid = "rag-root",
                docId = "doc-ref",
                status = "failed",
            ),
        )
        assertThat(vm.canRetryLastFailedIndex.value).isTrue()

        vm.dismissQueueError()
        advanceUntilIdle()

        assertThat(vm.indexingNotice.value).isNull()
        assertThat(vm.isIndexing.value).isFalse()
        assertThat(vm.canRetryLastFailedIndex.value).isFalse()
        coVerify(exactly = 1) { vectorizationQueue.cancelAndJoin("rag-root", listOf("doc-ref")) }
    }
}
