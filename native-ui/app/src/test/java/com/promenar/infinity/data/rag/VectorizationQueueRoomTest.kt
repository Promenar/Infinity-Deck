package com.promenar.infinity.data.rag

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.background.indexing.IndexForegroundDecision
import com.promenar.infinity.background.indexing.reduceIndexForeground
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.dao.FileEntryDao
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.VectorEntity
import com.promenar.infinity.data.local.db.entity.VectorizationTaskEntity
import com.promenar.infinity.data.local.db.recovery.DUAL_DATABASE_RECOVERY_INDEX_HOLD
import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.coJustRun
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class VectorizationQueueRoomTest {
    private lateinit var database: InfinityDatabase
    private lateinit var source: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        source = File.createTempFile("infinity-queue-room", ".txt").apply { writeText("content") }
    }

    @After
    fun tearDown() {
        database.close()
        source.delete()
    }

    @Test
    fun `真实Room中的失败任务更换活动标识且不触发主键冲突`() = runTest {
        seedFileAndTask("failed")
        val old = checkNotNull(database.vectorizationTaskDao().getById(TASK))
        database.vectorizationTaskDao().update(old.copy(assistantMessageId = "old-run"))
        val queue = queue(StandardTestDispatcher(TestCoroutineScheduler()))

        val id = queue.enqueueDocumentReference(ROOT, DOC, source.name, "text/plain", "hash-v1", 2)

        assertThat(id).isNotEqualTo(TASK)
        assertThat(database.vectorizationTaskDao().getById(id)?.status).isEqualTo("pending")
        assertThat(database.vectorizationTaskDao().getById(id)?.assistantMessageId).isNotEqualTo("old-run")
        assertThat(queue.getQueueLength()).isEqualTo(1)
        queue.shutdown()
    }

    @Test
    fun `进程重启后立即恢复未超过三十秒的向量化任务`() = runTest {
        seedFileAndTask("vectorizing")
        val existing = checkNotNull(database.vectorizationTaskDao().getById(TASK))
        database.vectorizationTaskDao().update(existing.copy(updatedAt = System.currentTimeMillis(), assistantMessageId = "resume-run"))
        val service = BlockingDocumentIndexService()
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)
        try {
            assertThat(queue.resumeInterruptedTasks().isSuccess).isTrue()
            // Room 写入在真实线程上完成，等待服务实际被调用，而不是只推进虚拟调度器。
            service.firstStarted.await()
            assertThat(service.firstStarted.isCompleted).isTrue()
            assertThat(service.firstRunId).isEqualTo("resume-run")
        } finally {
            queue.shutdown()
        }
    }

    @Test
    fun `恢复扫描不把运行中的慢批次标为中断`() = runTest {
        seedFileAndTask("vectorizing")
        val dao = database.vectorizationTaskDao()
        val task = checkNotNull(dao.getById(TASK))
        dao.update(task.copy(updatedAt = 1))

        dao.markStaleAsInterrupted(2, TASK)
        assertThat(dao.getById(TASK)?.status).isEqualTo("vectorizing")
        dao.markStaleAsInterrupted(2, null)
        assertThat(dao.getById(TASK)?.status).isEqualTo("interrupted")
    }

    @Test
    fun `向量批次进度和重试错误发布为独立状态快照`() = runTest {
        seedFilesWithoutTask()
        val ready = CompletableDeferred<Unit>()
        val advance = CompletableDeferred<Unit>()
        val updated = CompletableDeferred<Unit>()
        val service = object : DocumentIndexService {
            override suspend fun rebuild(event: FileIndexEvent): DocumentIndexResult {
                event as FileIndexEvent.Changed
                event.onEmbeddingProgress(EmbeddingBatchProgress(0, 35))
                ready.complete(Unit)
                advance.await()
                event.onEmbeddingProgress(EmbeddingBatchProgress(16, 35, "HTTP 429：限流"))
                updated.complete(Unit)
                awaitCancellation()
            }
            override suspend fun delete(workspaceRootUuid: String, fileUuid: String) = DocumentIndexResult.Deleted(fileUuid)
        }
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)
        try {
            queue.enqueueDocumentReference(ROOT, DOC, source.name, "text/plain", "hash-v1", 2)
            ready.await()
            val previous = queue.state.value
            assertThat(previous.currentTask?.lastChunkIndex).isEqualTo(0)
            advance.complete(Unit)
            updated.await()
            val current = queue.state.value
            assertThat(current.currentTask?.status).isEqualTo("vectorizing")
            assertThat(current.currentTask?.lastChunkIndex).isEqualTo(16)
            assertThat(current.currentTask?.totalChunks).isEqualTo(35)
            assertThat(current.currentTask?.error).isEqualTo("HTTP 429：限流")
            assertThat(previous.currentTask?.lastChunkIndex).isEqualTo(0)
            assertThat(current).isNotEqualTo(previous)
        } finally {
            queue.shutdown()
        }
    }

    @Test
    fun `批次重试耗尽后队列不再整任务重试并保留错误摘要`() = runTest {
        seedFilesWithoutTask()
        var calls = 0
        val service = object : DocumentIndexService {
            override suspend fun rebuild(event: FileIndexEvent): DocumentIndexResult {
                calls++
                return DocumentIndexResult.Failed(EmbeddingBatchException("HTTP 503：服务繁忙", true, 503))
            }
            override suspend fun delete(workspaceRootUuid: String, fileUuid: String) = DocumentIndexResult.Deleted(fileUuid)
        }
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)
        try {
            val id = queue.enqueueDocumentReference(ROOT, DOC, source.name, "text/plain", "hash-v1", 2)
            // 失败状态由 Room 在真实线程写入；按真实时间等到终态，期间队列的退避延时仍由虚拟时间跳过。
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(10_000) {
                    while (database.vectorizationTaskDao().getById(id)?.status != "failed") kotlinx.coroutines.delay(20)
                }
            }
            advanceUntilIdle()
            val task = checkNotNull(database.vectorizationTaskDao().getById(id))
            assertThat(calls).isEqualTo(1)
            assertThat(task.status).isEqualTo("failed")
            assertThat(task.error).isEqualTo("HTTP 503：服务繁忙")
        } finally {
            queue.shutdown()
        }
    }

    @Test
    fun `启动恢复会把missing scan发现的失败任务重置并重新入队`() = runTest {
        seedFileAndTask("failed")
        val queue = queue(StandardTestDispatcher(TestCoroutineScheduler()))

        val result = queue.resumeInterruptedTasks()

        assertThat(result.isSuccess).isTrue()
        val active = database.vectorizationTaskDao().getByWorkspaceFile(ROOT, DOC, VectorizationQueue.TYPE_DOCUMENT_REFERENCE)!!
        assertThat(active.status).isEqualTo("pending")
        assertThat(queue.snapshotState().queue.map { it.id }).containsExactly(active.id)
        queue.shutdown()
    }

    @Test
    fun `图谱失败时仅重试已提交向量的图谱`() = runTest {
        seedFileAndTask("partial")
        updateTarget("hash-v1", 2, vectorizedAt = 2)
        val original = database.vectorizationTaskDao().getById(TASK)!!
        database.vectorizationTaskDao().update(original.copy(error = VectorizationQueue.KG_FAILED + ".parse", kgStrategy = "full"))
        val queue = queue(StandardTestDispatcher(testScheduler))

        val id = queue.retryDocumentReferenceTask(ROOT, DOC)

        val retried = database.vectorizationTaskDao().getById(id!!)!!
        assertThat(retried.skipVectorization).isTrue()
        assertThat(retried.kgStrategy).isEqualTo("full")
        queue.shutdown()
    }

    @Test
    fun `图谱调用预算耗尽时只重试图谱`() = runTest {
        seedFileAndTask("partial")
        updateTarget("hash-v1", 2, vectorizedAt = 2)
        val original = database.vectorizationTaskDao().getById(TASK)!!
        database.vectorizationTaskDao().update(original.copy(error = "kg.budget.calls", kgStrategy = "full"))
        val queue = queue(StandardTestDispatcher(testScheduler))

        val id = queue.retryDocumentReferenceTask(ROOT, DOC)

        assertThat(database.vectorizationTaskDao().getById(id!!)!!.skipVectorization).isTrue()
        queue.shutdown()
    }

    @Test
    fun `图谱确认必须显式授权且文件变化后重试须重新向量化`() = runTest {
        seedFileAndTask("partial")
        val original = database.vectorizationTaskDao().getById(TASK)!!
        database.vectorizationTaskDao().update(original.copy(error = VectorizationQueue.KG_CONFIRMATION_REQUIRED,
            kgStrategy = KgExtractionBudget.CONFIRMED_STRATEGY))
        val queue = queue(StandardTestDispatcher(testScheduler))
        assertThat(queue.retryDocumentReferenceTask(ROOT, DOC)).isNull()
        updateTarget("hash-v2", 3, vectorizedAt = 2)
        database.vectorizationTaskDao().update(original.copy(error = "kg.budget.calls", kgStrategy = KgExtractionBudget.CONFIRMED_STRATEGY,
            skipVectorization = true))

        val id = queue.retryDocumentReferenceTask(ROOT, DOC)

        val retried = database.vectorizationTaskDao().getById(id!!)!!
        assertThat(retried.skipVectorization).isFalse()
        assertThat(retried.kgStrategy).isEqualTo("full")
        queue.shutdown()
    }

    @Test
    fun `双库恢复hold阻止启动自动索引且显式重试会解除`() = runTest {
        seedFilesWithoutTask()
        database.vectorizationTaskDao().insert(
            VectorizationTaskEntity(
                id = TASK,
                type = VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
                status = "failed",
                docId = DOC,
                docTitle = source.name,
                workspaceRootUuid = ROOT,
                progress = 0.0,
                error = "双库恢复保留了未索引文件；请由用户显式重试",
                subStatus = DUAL_DATABASE_RECOVERY_INDEX_HOLD,
                sourceMimeType = "text/plain",
                targetContentHash = "hash-v1",
                targetEpoch = 2,
                createdAt = 1,
                updatedAt = 2,
            ),
        )
        val service = BlockingDocumentIndexService()
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)

        assertThat(queue.resumeInterruptedTasks().isSuccess).isTrue()
        runCurrent()
        assertThat(service.firstStarted.isCompleted).isFalse()
        assertThat(database.vectorizationTaskDao().getById(TASK)?.subStatus)
            .isEqualTo(DUAL_DATABASE_RECOVERY_INDEX_HOLD)

        val retriedTaskId = queue.retryDocumentReferenceTask(ROOT, DOC)
        val retried = database.vectorizationTaskDao().getByWorkspaceFile(ROOT, DOC, VectorizationQueue.TYPE_DOCUMENT_REFERENCE)
        assertThat(retried?.status).isEqualTo("pending")
        assertThat(retried?.subStatus).isNotEqualTo(DUAL_DATABASE_RECOVERY_INDEX_HOLD)
        // 重试换用新标识，并把它交回调用方继续跟踪。
        assertThat(retriedTaskId).isEqualTo(retried!!.id)
        assertThat(retriedTaskId).isNotEqualTo(TASK)
        assertThat(queue.snapshotState().queue.map { it.id }).containsExactly(retried.id)
        assertThat(queue.retryDocumentReferenceTask(ROOT, DOC)).isNull()
        queue.shutdown()
    }

    @Test
    fun `空库冷启动会清理已删除文档遗留的失败任务`() = runTest {
        database.vectorizationTaskDao().insert(
            VectorizationTaskEntity(
                id = TASK,
                type = "document",
                status = "failed",
                docTitle = source.name,
                progress = 0.0,
                error = "历史索引失败",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        val queue = queue(StandardTestDispatcher(testScheduler))

        assertThat(queue.resumeInterruptedTasks().isSuccess).isTrue()

        assertThat(database.vectorizationTaskDao().getById(TASK)).isNull()
        assertThat(queue.snapshotState().queue).isEmpty()
        assertThat(queue.snapshotState().currentTask).isNull()
        queue.shutdown()
    }

    @Test
    fun `现代文件引用attention不会被legacy空库清理误删`() = runTest {
        database.vectorizationTaskDao().insert(
            VectorizationTaskEntity(
                id = TASK,
                type = VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
                status = "failed",
                docTitle = source.name,
                progress = 0.0,
                error = "等待文件事件协调器处理",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        val queue = queue(
            StandardTestDispatcher(testScheduler),
            documentIndexService = BlockingDocumentIndexService(),
        )

        assertThat(queue.resumeInterruptedTasks().isSuccess).isTrue()

        assertThat(database.vectorizationTaskDao().getById(TASK)).isNotNull()
        assertThat(queue.snapshotState().queue.map { it.id }).contains(TASK)
        queue.shutdown()
    }

    @Test
    fun `配置reset等待旧queue停止并把处理中任务交接给新queue恢复`() = runTest {
        seedFileAndTask("vectorizing")
        val holder = com.promenar.infinity.utils.SynchronizedResettableResource<VectorizationQueue>(
            VectorizationQueue::shutdown,
        )
        val old = holder.getOrCreate { queue(StandardTestDispatcher(testScheduler)) }
        val handle = checkNotNull(holder.beginReset())

        old.shutdownForReplacement()
        assertThat(database.vectorizationTaskDao().getById(TASK)?.status).isEqualTo("interrupted")
        holder.completeReset(handle)
        val replacement = holder.getOrCreate { queue(StandardTestDispatcher(testScheduler)) }
        val finalEntered = CompletableDeferred<Unit>()
        val allowFinal = CompletableDeferred<Unit>()
        replacement.beforeFinalQueueTransitionForTest = {
            finalEntered.complete(Unit)
            withContext(NonCancellable) { allowFinal.await() }
        }
        assertThat(replacement.resumeInterruptedTasks().isSuccess).isTrue()
        finalEntered.await()

        try {
            assertThat(database.vectorizationTaskDao().getById(TASK)).isNull()
            val active = replacement.snapshotState().queue.single()
            assertThat(active.id).isNotEqualTo(TASK)
            assertThat(active.docId).isEqualTo(DOC)
            assertThat(active.targetContentHash).isEqualTo("hash-v1")
            assertThat(active.targetEpoch).isEqualTo(2)
        } finally {
            allowFinal.complete(Unit)
        }
        runCurrent()

        assertThat(replacement.snapshotState().queue).isEmpty()
        assertThat(database.vectorizationTaskDao().getByDocId(DOC)).isEmpty()
        assertThat(replacement).isNotSameInstanceAs(old)
        replacement.shutdown()
    }

    @Test
    fun `恢复缺失扫描完成前不得启动已恢复任务`() = runTest {
        seedFileAndTask("interrupted")
        val service = BlockingDocumentIndexService()
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)
        val beforeScan = CompletableDeferred<Unit>()
        val allowScan = CompletableDeferred<Unit>()
        queue.beforeRecoveryMissingScanForTest = {
            beforeScan.complete(Unit)
            allowScan.await()
        }
        val recovery = async { queue.resumeInterruptedTasks() }
        try {
            beforeScan.await()
            runCurrent()
            assertThat(queue.snapshotState().isProcessing).isFalse()
            assertThat(queue.state.value.queue.single().status).isEqualTo("interrupted")
            assertThat(queue.state.value.activeTaskIds).contains(TASK)
            assertThat(reduceIndexForeground(queue.state.value)).isInstanceOf(IndexForegroundDecision.Active::class.java)
            assertThat(service.firstStarted.isCompleted).isFalse()
            allowScan.complete(Unit)
            assertThat(recovery.await().isSuccess).isTrue()
            service.firstStarted.await()
        } finally {
            allowScan.complete(Unit)
            queue.shutdown()
            recovery.cancel()
        }
    }

    @Test
    fun `恢复扫描失败后普通入队不会提前启动且恢复重试可继续`() = runTest {
        seedFileAndTask("interrupted")
        val service = BlockingDocumentIndexService()
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)
        try {
            queue.beforeRecoveryMissingScanForTest = { error("模拟扫描失败") }
            assertThat(queue.resumeInterruptedTasks().isFailure).isTrue()
            queue.enqueueDocumentReference(ROOT, DOC, source.name, "text/plain", "hash-v1", 2)
            runCurrent()
            assertThat(queue.snapshotState().restored).isFalse()
            assertThat(queue.snapshotState().isProcessing).isFalse()
            assertThat(service.firstStarted.isCompleted).isFalse()

            queue.beforeRecoveryMissingScanForTest = {}
            assertThat(queue.resumeInterruptedTasks().isSuccess).isTrue()
            service.firstStarted.await()
            assertThat(queue.snapshotState().restored).isTrue()
        } finally {
            queue.shutdown()
        }
    }

    @Test
    fun `已有任务在恢复扫描期间结束时排队任务不再显示处理中`() = runTest {
        seedFilesWithoutTask()
        val secondDoc = "second-doc"
        val original = requireNotNull(database.fileEntryDao().getByUuid(ROOT, DOC))
        database.fileEntryDao().insert(original.copy(uuid = secondDoc, name = "second.txt", materializedPath = "/second.txt"))
        val service = BlockingDocumentIndexService()
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)
        val beforeScan = CompletableDeferred<Unit>()
        val allowScan = CompletableDeferred<Unit>()
        val finalEntered = CompletableDeferred<Unit>()
        var recovery: kotlinx.coroutines.Deferred<Result<Unit>>? = null
        try {
            queue.enqueueDocumentReference(ROOT, DOC, source.name, "text/plain", "hash-v1", 2)
            service.firstStarted.await()
            queue.enqueueDocumentReference(ROOT, secondDoc, "second.txt", "text/plain", "hash-v1", 2)
            queue.beforeRecoveryMissingScanForTest = { beforeScan.complete(Unit); allowScan.await() }
            queue.beforeFinalQueueTransitionForTest = { finalEntered.complete(Unit) }
            recovery = async { queue.resumeInterruptedTasks() }
            beforeScan.await()
            updateTarget("hash-v1", 2, vectorizedAt = 2)
            service.firstResult.complete(DocumentIndexResult.Rebuilt(DOC))
            finalEntered.await()
            runCurrent()
            assertThat(queue.snapshotState().queue.map { it.docId }).containsExactly(secondDoc)
            assertThat(queue.snapshotState().isProcessing).isFalse()
            assertThat(service.secondStarted.isCompleted).isFalse()
            allowScan.complete(Unit)
            assertThat(recovery.await().isSuccess).isTrue()
            service.secondStarted.await()
            assertThat(queue.snapshotState().isProcessing).isTrue()
        } finally {
            allowScan.complete(Unit)
            queue.shutdown()
            recovery?.cancel()
        }
    }

    @Test
    fun `文件型Room数据库重开后恢复失败目标并以新活动标识重新入队`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 测试上下文下的唯一数据库文件名，避免与其它用例或历史残留冲突
        val dbName = "infinity-reopen-${java.util.UUID.randomUUID()}.db"
        var firstOpen: InfinityDatabase? = null
        var reopened: InfinityDatabase? = null
        try {
            // 1) 向真实文件型 InfinityDatabase 写入 failed 任务及其 FileEntry
            val initial = Room.databaseBuilder(context, InfinityDatabase::class.java, dbName)
                .allowMainThreadQueries()
                .build()
            firstOpen = initial
            seedFileAndTask("failed", initial)
            // 2) 关闭数据库，随后用同一数据库名重新打开
            initial.close()
            reopened = Room.databaseBuilder(context, InfinityDatabase::class.java, dbName)
                .allowMainThreadQueries()
                .build()

            // 3) failed 状态、task id、workspaceRootUuid/docId 等关键字段确实从 Room 持久层恢复
            val restoredTask = reopened.vectorizationTaskDao().getById(TASK)
            assertThat(restoredTask).isNotNull()
            assertThat(restoredTask!!.status).isEqualTo("failed")
            assertThat(restoredTask.id).isEqualTo(TASK)
            assertThat(restoredTask.workspaceRootUuid).isEqualTo(ROOT)
            assertThat(restoredTask.docId).isEqualTo(DOC)
            assertThat(restoredTask.type).isEqualTo(VectorizationQueue.TYPE_DOCUMENT_REFERENCE)
            assertThat(restoredTask.targetContentHash).isEqualTo("hash-v1")
            assertThat(restoredTask.targetEpoch).isEqualTo(2)

            val restoredDoc = reopened.fileEntryDao().getByUuid(ROOT, DOC)
            assertThat(restoredDoc).isNotNull()
            assertThat(restoredDoc!!.workspaceRootUuid).isEqualTo(ROOT)
            assertThat(restoredDoc.uuid).isEqualTo(DOC)

            // 使用恢复出的目标建立独立重试实例，不发生主键冲突。
            val queue = queue(
                StandardTestDispatcher(TestCoroutineScheduler()),
                reopened,
            )
            val id = queue.enqueueDocumentReference(ROOT, DOC, source.name, "text/plain", "hash-v1", 2)

            assertThat(id).isNotEqualTo(TASK)
            assertThat(reopened.vectorizationTaskDao().getById(id)?.status).isEqualTo("pending")
            assertThat(queue.getQueueLength()).isEqualTo(1)
            queue.shutdown()
        } finally {
            // 可靠关闭并删除文件型数据库，防止残留冒充跨 reopen 持久化
            firstOpen?.close()
            reopened?.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `进程死亡发生于FileEntry提交后Queue接收前时冷启动补建旧值与null的当前reference目标`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "infinity-missing-reference-${java.util.UUID.randomUUID()}.db"
        var firstOpen: InfinityDatabase? = null
        var reopened: InfinityDatabase? = null
        try {
            val initial = Room.databaseBuilder(context, InfinityDatabase::class.java, dbName)
                .allowMainThreadQueries()
                .build()
            firstOpen = initial
            seedFilesWithoutTask(initial)
            val oldVectorizedAtFile = initial.fileEntryDao().getByUuid(ROOT, DOC)!!
            initial.fileEntryDao().insert(
                oldVectorizedAtFile.copy(
                    uuid = NULL_DOC,
                    name = "null-${source.name}",
                    hash = "hash-null",
                    materializedPath = "/null-${source.name}",
                    updatedAt = 4,
                    vectorizedAt = null,
                ),
            )
            assertThat(initial.vectorizationTaskDao().getByDocId(DOC)).isEmpty()
            assertThat(initial.vectorizationTaskDao().getByDocId(NULL_DOC)).isEmpty()
            initial.close()

            reopened = Room.databaseBuilder(context, InfinityDatabase::class.java, dbName)
                .allowMainThreadQueries()
                .build()
            val committedOldFile = reopened.fileEntryDao().getByUuid(ROOT, DOC)
            val committedNullFile = reopened.fileEntryDao().getByUuid(ROOT, NULL_DOC)
            assertThat(committedOldFile).isNotNull()
            assertThat(committedNullFile).isNotNull()
            assertThat(committedOldFile!!.vectorizedAt).isEqualTo(1)
            assertThat(committedOldFile.updatedAt).isEqualTo(2)
            assertThat(committedNullFile!!.vectorizedAt).isNull()
            assertThat(committedNullFile.updatedAt).isEqualTo(4)
            assertThat(reopened.vectorizationTaskDao().getByDocId(DOC)).isEmpty()
            assertThat(reopened.vectorizationTaskDao().getByDocId(NULL_DOC)).isEmpty()

            val coldQueue = queue(
                StandardTestDispatcher(testScheduler),
                reopened,
                documentIndexService = BlockingDocumentIndexService(),
                ragConfig = RagConfiguration(enableKnowledgeGraph = true),
            )
            assertThat(coldQueue.resumeInterruptedTasks().isSuccess).isTrue()

            val recoveredOld = reopened.vectorizationTaskDao().getByWorkspaceFile(
                ROOT,
                DOC,
                VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
            )
            val recoveredNull = reopened.vectorizationTaskDao().getByWorkspaceFile(
                ROOT,
                NULL_DOC,
                VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
            )
            assertThat(recoveredOld).isNotNull()
            assertThat(recoveredNull).isNotNull()
            assertThat(recoveredOld!!.targetContentHash).isEqualTo(committedOldFile.hash)
            assertThat(recoveredOld.targetEpoch).isEqualTo(committedOldFile.updatedAt)
            assertThat(recoveredOld.kgStrategy).isEqualTo("full")
            assertThat(recoveredOld.skipVectorization).isFalse()
            assertThat(recoveredNull!!.targetContentHash).isEqualTo(committedNullFile.hash)
            assertThat(recoveredNull.targetEpoch).isEqualTo(committedNullFile.updatedAt)
            assertThat(recoveredNull.kgStrategy).isEqualTo("full")
            assertThat(recoveredNull.skipVectorization).isFalse()
            coldQueue.shutdown()
        } finally {
            firstOpen?.close()
            reopened?.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `冷启动补建reference在KG关闭时不携带建图策略`() = runTest {
        seedFilesWithoutTask()
        val queue = queue(
            StandardTestDispatcher(testScheduler),
            documentIndexService = BlockingDocumentIndexService(),
            ragConfig = RagConfiguration(enableKnowledgeGraph = false),
        )

        assertThat(queue.resumeInterruptedTasks().isSuccess).isTrue()

        val recovered = database.vectorizationTaskDao().getByWorkspaceFile(
            ROOT,
            DOC,
            VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
        )
        assertThat(recovered).isNotNull()
        assertThat(recovered!!.kgStrategy).isNull()
        queue.shutdown()
    }

    @Test
    fun `processing旧目标完成后不能写回或删除newer目标且newer自动接续`() = runTest {
        seedFilesWithoutTask()
        val service = BlockingDocumentIndexService()
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)

        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v1", 2))
        runCurrent()
        service.firstStarted.await()

        val current = database.fileEntryDao().getByUuid(ROOT, DOC)!!
        database.fileEntryDao().update(current.copy(hash = "hash-v2", updatedAt = 3))
        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v2", 3))

        val during = queue.snapshotState().queue
        assertThat(during.map { it.targetEpoch }).containsExactly(2L, 3L).inOrder()
        service.firstResult.complete(DocumentIndexResult.Rebuilt(DOC))
        runCurrent()

        val active = database.vectorizationTaskDao().getByWorkspaceFile(
            ROOT, DOC, VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
        )
        assertThat(active?.targetContentHash).isEqualTo("hash-v2")
        assertThat(active?.targetEpoch).isEqualTo(3)
        assertThat(active?.status).isNotEqualTo("failed")
        assertThat(active?.error).isNotEqualTo("old failed")
        service.secondStarted.await()
        assertThat(queue.snapshotState().queue.single().targetEpoch).isEqualTo(3)
        queue.shutdown()
    }

    @Test
    fun `processing旧目标失败后不能把newer目标标记failed`() = runTest {
        seedFilesWithoutTask()
        val service = BlockingDocumentIndexService()
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)

        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v1", 2))
        runCurrent()
        service.firstStarted.await()
        val current = database.fileEntryDao().getByUuid(ROOT, DOC)!!
        database.fileEntryDao().update(current.copy(hash = "hash-v2", updatedAt = 3))
        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v2", 3))
        service.firstResult.complete(DocumentIndexResult.Failed(IllegalStateException("old failed")))
        runCurrent()

        val active = database.vectorizationTaskDao().getByWorkspaceFile(
            ROOT, DOC, VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
        )
        assertThat(active?.targetContentHash).isEqualTo("hash-v2")
        assertThat(active?.targetEpoch).isEqualTo(3)
        assertThat(active?.status).isNotEqualTo("failed")
        assertThat(active?.error).isNotEqualTo("old failed")
        service.secondStarted.await()
        assertThat(queue.snapshotState().queue.single().targetEpoch).isEqualTo(3)
        queue.shutdown()
    }

    @Test
    fun `cleanup逐条target CAS删除completed文件引用孤儿`() = runTest {
        seedFilesWithoutTask()
        database.vectorizationTaskDao().insert(
            VectorizationTaskEntity(
                id = "completed-target",
                type = VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
                status = "completed",
                docId = DOC,
                workspaceRootUuid = ROOT,
                sourceMimeType = "text/plain",
                targetContentHash = "hash-v1",
                targetEpoch = 2,
                createdAt = 1,
                updatedAt = 2,
            ),
        )
        val queue = queue(StandardTestDispatcher(testScheduler))

        queue.cleanupCompletedTasks()

        assertThat(database.vectorizationTaskDao().getById("completed-target")).isNull()
    }

    @Test
    fun `newer目标主动取消旧processing service并立即启动newer`() = runTest {
        seedFilesWithoutTask()
        val service = CancellationAwareDocumentIndexService()
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)

        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v1", 2))
        runCurrent()
        service.firstStarted.await()

        val current = database.fileEntryDao().getByUuid(ROOT, DOC)!!
        database.fileEntryDao().update(current.copy(hash = "hash-v2", updatedAt = 3))
        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v2", 3))
        runCurrent()

        service.firstCancelled.await()
        service.secondStarted.await()
        assertThat(database.vectorizationTaskDao().getByWorkspaceFile(
            ROOT, DOC, VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
        )?.targetEpoch).isEqualTo(3)
        queue.shutdown()
        runCurrent()
    }

    @Test
    fun `enqueue发生在旧worker finally边界时newer不会滞留`() = runTest {
        seedFilesWithoutTask()
        val service = FirstCompletesSecondWaitsService()
        val finalEntered = CompletableDeferred<Unit>()
        val allowFinal = CompletableDeferred<Unit>()
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)
        queue.beforeFinalQueueTransitionForTest = {
            if (!finalEntered.isCompleted) {
                finalEntered.complete(Unit)
                withContext(NonCancellable) { allowFinal.await() }
            }
        }

        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v1", 2))
        runCurrent()
        finalEntered.await()

        try {
            val current = database.fileEntryDao().getByUuid(ROOT, DOC)!!
            database.fileEntryDao().update(current.copy(hash = "hash-v2", updatedAt = 3))
            queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v2", 3))
            runCurrent()
            assertThat(service.secondStarted.isCompleted).isFalse()
        } finally {
            allowFinal.complete(Unit)
        }
        runCurrent()

        service.secondStarted.await()
        queue.shutdown()
        runCurrent()
    }

    @Test
    fun `newer到达retry delay时取消旧目标且不残留失败态`() = runTest {
        seedFilesWithoutTask()
        val service = FirstFailsSecondWaitsService("network timeout")
        val retryDelayEntered = CompletableDeferred<Unit>()
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)
        queue.beforeRetryDelayForTest = {
            retryDelayEntered.complete(Unit)
            awaitCancellation()
        }

        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v1", 2))
        runCurrent()
        retryDelayEntered.await()

        updateTarget("hash-v2", 3)
        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v2", 3))
        runCurrent()

        service.secondStarted.await()
        val active = database.vectorizationTaskDao().getByWorkspaceFile(
            ROOT, DOC, VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
        )
        assertThat(active?.targetContentHash).isEqualTo("hash-v2")
        assertThat(active?.targetEpoch).isEqualTo(3)
        assertThat(active?.status).isNotEqualTo("failed")
        assertThat(queue.snapshotState().queue.single().targetEpoch).isEqualTo(3)
        queue.shutdown()
        runCurrent()
    }

    @Test
    fun `newer到达failed display delay时取消旧目标且不残留attention`() = runTest {
        seedFilesWithoutTask()
        val service = FirstFailsSecondWaitsService("fatal rejection")
        val failureDelayEntered = CompletableDeferred<Unit>()
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)
        queue.beforeFailureDisplayDelayForTest = {
            failureDelayEntered.complete(Unit)
            awaitCancellation()
        }

        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v1", 2))
        runCurrent()
        failureDelayEntered.await()

        updateTarget("hash-v2", 3)
        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v2", 3))
        runCurrent()

        service.secondStarted.await()
        val active = database.vectorizationTaskDao().getByWorkspaceFile(
            ROOT, DOC, VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
        )
        assertThat(active?.targetContentHash).isEqualTo("hash-v2")
        assertThat(active?.targetEpoch).isEqualTo(3)
        assertThat(active?.status).isNotEqualTo("failed")
        assertThat(queue.snapshotState().queue.map { it.targetEpoch }).containsExactly(3L)
        queue.shutdown()
        runCurrent()
    }

    @Test
    fun `clear发生在failed display delay时取消后不回填attention`() = runTest {
        seedFilesWithoutTask()
        val service = FirstFailsSecondWaitsService("fatal rejection")
        val failureDelayEntered = CompletableDeferred<Unit>()
        val failureDelayCancelled = CompletableDeferred<Unit>()
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)
        queue.beforeFailureDisplayDelayForTest = {
            failureDelayEntered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                failureDelayCancelled.complete(Unit)
            }
        }

        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v1", 2))
        runCurrent()
        failureDelayEntered.await()

        queue.clear()
        runCurrent()
        failureDelayCancelled.await()

        assertThat(queue.snapshotState().queue).isEmpty()
        assertThat(queue.snapshotState().currentTask).isNull()
        assertThat(queue.snapshotState().isProcessing).isFalse()
        queue.shutdown()
    }

    @Test
    fun `delete barrier阻止同目标新入队且abort精确恢复failed attention`() = runTest {
        seedFilesWithoutTask()
        val service = FirstFailsSecondWaitsService("fatal rejection")
        val failureDelayEntered = CompletableDeferred<Unit>()
        val releaseFailureDelay = CompletableDeferred<Unit>()
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)
        queue.beforeFailureDisplayDelayForTest = {
            failureDelayEntered.complete(Unit)
            releaseFailureDelay.await()
        }

        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v1", 2))
        runCurrent()
        failureDelayEntered.await()
        releaseFailureDelay.complete(Unit)
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        assertThat(queue.snapshotState().queue.single().status).isEqualTo("failed")

        val barrier = queue.acquireDeleteBarrier(ROOT, listOf(DOC))
        barrier.awaitReady()
        assertThat(queue.snapshotState().queue).isEmpty()
        val blocked = runCatching {
            queue.enqueueDocumentReference(ROOT, DOC, source.name, "text/plain", "hash-v1", 2)
        }.exceptionOrNull()
        assertThat(blocked).isInstanceOf(IllegalStateException::class.java)

        barrier.abort()

        val restored = queue.snapshotState().queue.single()
        assertThat(restored.status).isEqualTo("failed")
        assertThat(restored.targetContentHash).isEqualTo("hash-v1")
        queue.enqueueDocumentReference(ROOT, DOC, source.name, "text/plain", "hash-v1", 2)
        runCurrent()
        service.secondStarted.await()
        queue.shutdown()
        runCurrent()
    }

    @Test
    fun `delete barrier abort将active任务CAS为可恢复并重新入队`() = runTest {
        seedFilesWithoutTask()
        val service = CancellationAwareDocumentIndexService()
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)
        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v1", 2))
        runCurrent()
        service.firstStarted.await()

        val barrier = queue.acquireDeleteBarrier(ROOT, listOf(DOC))
        barrier.awaitReady()
        service.firstCancelled.await()
        assertThat(queue.snapshotState().queue).isEmpty()

        barrier.abort()
        runCurrent()

        service.secondStarted.await()
        val restored = database.vectorizationTaskDao().getByWorkspaceFile(
            ROOT, DOC, VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
        )
        assertThat(restored?.targetEpoch).isEqualTo(2)
        assertThat(restored?.status).isNotEqualTo("failed")
        queue.shutdown()
        runCurrent()
    }

    @Test
    fun `queue reset必须等待delete barrier释放后才交接`() = runTest {
        seedFilesWithoutTask()
        val queue = queue(StandardTestDispatcher(testScheduler))
        val barrier = queue.acquireDeleteBarrier(ROOT, listOf(DOC))
        barrier.awaitReady()

        val replacementShutdown = async { queue.shutdownForReplacement() }
        runCurrent()
        assertThat(replacementShutdown.isCompleted).isFalse()

        barrier.commit()
        runCurrent()
        replacementShutdown.await()

        assertThat(replacementShutdown.isCompleted).isTrue()
        assertThat(queue.snapshotState().queue).isEmpty()
    }

    @Test
    fun `workspace cancelAndJoin精确删除持久任务且replacement不恢复`() = runTest {
        seedFileAndTask("failed")
        updateTarget("hash-v1", 2, vectorizedAt = 2)
        val queue = queue(StandardTestDispatcher(testScheduler))

        queue.cancelAndJoin(ROOT, listOf(DOC))

        assertThat(database.vectorizationTaskDao().getByDocId(DOC)).isEmpty()
        queue.shutdown()
        val replacement = queue(StandardTestDispatcher(testScheduler))
        assertThat(replacement.resumeInterruptedTasks().isSuccess).isTrue()
        assertThat(replacement.snapshotState().queue).isEmpty()
        assertThat(database.vectorizationTaskDao().getByDocId(DOC)).isEmpty()
        replacement.shutdown()
    }

    @Test
    fun `workspace cancelAndJoin持久删除前fence拒绝并发enqueue`() = runTest {
        seedFileAndTask("failed")
        val deleteEntered = CompletableDeferred<Unit>()
        val releaseDelete = CompletableDeferred<Unit>()
        val queue = queue(StandardTestDispatcher(testScheduler))
        queue.beforePersistentCancelDeleteForTest = {
            deleteEntered.complete(Unit)
            releaseDelete.await()
        }

        val cancellation = async { queue.cancelAndJoin(ROOT, listOf(DOC)) }
        deleteEntered.await()
        val blocked = runCatching {
            queue.enqueueDocumentReference(ROOT, DOC, source.name, "text/plain", "hash-v1", 2)
        }.exceptionOrNull()
        assertThat(blocked).isInstanceOf(IllegalStateException::class.java)

        releaseDelete.complete(Unit)
        cancellation.await()
        assertThat(database.vectorizationTaskDao().getByDocId(DOC)).isEmpty()
        queue.shutdown()
    }

    @Test
    fun `delete barrier release不传播state callback异常`() = runTest {
        seedFilesWithoutTask()
        val queue = queue(StandardTestDispatcher(testScheduler))
        var shouldThrow = false
        queue.setOnStateChange { _, _ ->
            if (shouldThrow) error("observer failure")
        }
        shouldThrow = true

        val barrier = queue.acquireDeleteBarrier(ROOT, listOf(DOC))
        barrier.awaitReady()
        val failure = runCatching { barrier.commit() }.exceptionOrNull()

        assertThat(failure).isNull()
        shouldThrow = false
        queue.shutdown()
    }

    @Test
    fun `legacyKG缺少可解析文件引用时失败且不产生半成品`() = runTest {
        seedFilesWithoutTask()
        val entry = database.fileEntryDao().getByUuid(ROOT, DOC)!!
        database.fileEntryDao().update(entry.copy(mimeType = null))
        val lookupStarted = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        val fileDao = database.fileEntryDao()
        val delayedFileDao = object : FileEntryDao by fileDao {
            override suspend fun getActiveByUuid(workspaceRootUuid: String, uuid: String): FileEntry? {
                lookupStarted.complete(Unit)
                releaseLookup.await()
                return fileDao.getActiveByUuid(workspaceRootUuid, uuid)
            }
        }
        val queue = queue(StandardTestDispatcher(testScheduler), fileEntryDao = delayedFileDao)
        val failurePersisted = CompletableDeferred<Unit>()
        queue.beforeFailureDisplayDelayForTest = { failurePersisted.complete(Unit) }
        queue.enqueueDocument(ROOT, DOC, source.name, "content", kgStrategy = "full")
        lookupStarted.await()
        advanceUntilIdle()
        assertThat(database.vectorizationTaskDao().getByDocId(DOC).single().status).isEqualTo("pending")
        releaseLookup.complete(Unit)
        failurePersisted.await()

        assertThat(database.vectorDao().getByDocId(DOC)).isEmpty()
        assertThat(database.kgEdgeDao().getByDocId(DOC)).isEmpty()
        assertThat(database.vectorizationTaskDao().getByDocId(DOC).single().status).isEqualTo("failed")
        val barrier = queue.acquireDeleteBarrier(ROOT, listOf(DOC))
        barrier.awaitReady()
        barrier.abort()
        advanceUntilIdle()
        assertThat(database.vectorDao().getByDocId(DOC)).isEmpty()
        queue.shutdown()
    }

    @Test
    fun `Deleted主动取消processing并继续清理artifact与task`() = runTest {
        seedFilesWithoutTask()
        database.vectorDao().insert(
            VectorEntity(
                id = "old-vector",
                docId = DOC,
                content = "old",
                embedding = byteArrayOf(0, 0, 0, 0),
                createdAt = 1,
                fileUuid = DOC,
            ),
        )
        val service = DeleteAwareDocumentIndexService(database)
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)

        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v1", 2))
        runCurrent()
        service.rebuildStarted.await()

        queue.publish(FileIndexEvent.Deleted(ROOT, DOC))
        runCurrent()

        service.rebuildCancelled.await()
        service.deleteCompleted.await()
        assertThat(database.vectorDao().getByDocId(DOC)).isEmpty()
        assertThat(database.vectorizationTaskDao().getByDocId(DOC)).isEmpty()
        assertThat(queue.snapshotState().queue).isEmpty()
        queue.shutdown()

        database.fileEntryDao().getByUuid(ROOT, DOC)?.let { database.fileEntryDao().delete(it) }
        val replacement = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)
        assertThat(replacement.resumeInterruptedTasks().isSuccess).isTrue()
        assertThat(replacement.snapshotState().queue).isEmpty()
        assertThat(database.vectorizationTaskDao().getByDocId(DOC)).isEmpty()
        replacement.shutdown()
    }

    @Test
    fun `resume即使无其他任务也清理completed文件引用孤儿`() = runTest {
        seedFilesWithoutTask()
        updateTarget("hash-v1", 2, vectorizedAt = 2)
        database.vectorizationTaskDao().insert(
            VectorizationTaskEntity(
                id = "completed-crash-orphan",
                type = VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
                status = "completed",
                docId = DOC,
                workspaceRootUuid = ROOT,
                sourceMimeType = "text/plain",
                targetContentHash = "hash-v1",
                targetEpoch = 2,
                createdAt = 1,
                updatedAt = 2,
            ),
        )
        val queue = queue(StandardTestDispatcher(testScheduler))

        assertThat(queue.resumeInterruptedTasks().isSuccess).isTrue()

        assertThat(database.vectorizationTaskDao().getById("completed-crash-orphan")).isNull()
        assertThat(queue.snapshotState().queue).isEmpty()
        queue.shutdown()
    }

    @Test
    fun `resume将可定位legacy任务原子迁移为reference而不盲跑`() = runTest {
        seedFilesWithoutTask()
        database.vectorizationTaskDao().insert(
            VectorizationTaskEntity(
                id = "startup-legacy",
                type = "document",
                status = "interrupted",
                docId = DOC,
                docTitle = source.name,
                workspaceRootUuid = ROOT,
                userContent = "legacy content",
                kgStrategy = "full",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        val queue = queue(
            StandardTestDispatcher(testScheduler),
            documentIndexService = BlockingDocumentIndexService(),
        )

        assertThat(queue.resumeInterruptedTasks().isSuccess).isTrue()

        assertThat(database.vectorizationTaskDao().getById("startup-legacy")).isNull()
        assertThat(queue.snapshotState().queue.single().type)
            .isEqualTo(VectorizationQueue.TYPE_DOCUMENT_REFERENCE)
        queue.shutdown()
    }

    @Test
    fun `resume对不可定位legacy任务安全清理并转failed attention`() = runTest {
        seedFilesWithoutTask()
        val entry = database.fileEntryDao().getByUuid(ROOT, DOC)!!
        database.fileEntryDao().update(entry.copy(mimeType = null))
        database.vectorizationTaskDao().insert(
            VectorizationTaskEntity(
                id = "unsafe-legacy",
                type = "document",
                status = "interrupted",
                docId = DOC,
                docTitle = source.name,
                workspaceRootUuid = ROOT,
                userContent = "legacy content",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        database.vectorDao().insert(
            VectorEntity(
                id = "partial-vector", docId = DOC, content = "partial",
                embedding = byteArrayOf(0, 0, 0, 0), createdAt = 1, fileUuid = DOC,
            ),
        )
        val queue = queue(StandardTestDispatcher(testScheduler))

        assertThat(queue.resumeInterruptedTasks().isSuccess).isTrue()

        assertThat(database.vectorDao().getByDocId(DOC)).isEmpty()
        assertThat(queue.snapshotState().queue.single().status).isEqualTo("failed")
        assertThat(queue.snapshotState().queue.single().id).isEqualTo("unsafe-legacy")
        queue.shutdown()
    }

    @Test
    fun `legacy失败提示经过其他任务排空清理与重启后仍保留`() = runTest {
        seedFilesWithoutTask()
        val entry = database.fileEntryDao().getByUuid(ROOT, DOC)!!
        database.fileEntryDao().update(entry.copy(mimeType = null))
        database.vectorizationTaskDao().insert(
            VectorizationTaskEntity(
                id = "persistent-unsafe-legacy",
                type = "document",
                status = "interrupted",
                docId = DOC,
                docTitle = source.name,
                workspaceRootUuid = ROOT,
                userContent = "legacy content",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        val firstQueue = queue(StandardTestDispatcher(testScheduler))
        assertThat(firstQueue.resumeInterruptedTasks().isSuccess).isTrue()
        assertThat(database.vectorizationTaskDao().getById("persistent-unsafe-legacy")?.status)
            .isEqualTo("failed")

        database.vectorizationTaskDao().insert(
            VectorizationTaskEntity(
                id = "completed-memory",
                type = "memory",
                status = "completed",
                createdAt = 2,
                updatedAt = 2,
            ),
        )
        firstQueue.cleanupCompletedTasks()
        assertThat(database.vectorizationTaskDao().getById("completed-memory")).isNull()
        firstQueue.shutdown()

        val resumedQueue = queue(StandardTestDispatcher(testScheduler))
        assertThat(resumedQueue.resumeInterruptedTasks().isSuccess).isTrue()
        assertThat(resumedQueue.snapshotState().queue.map { it.id })
            .containsExactly("persistent-unsafe-legacy")
        assertThat(resumedQueue.snapshotState().queue.single().status).isEqualTo("failed")
        resumedQueue.shutdown()
    }

    private suspend fun seedFileAndTask(status: String, db: InfinityDatabase = database) {
        seedFilesWithoutTask(db)
        db.vectorizationTaskDao().insert(VectorizationTaskEntity(
            id = TASK, type = VectorizationQueue.TYPE_DOCUMENT_REFERENCE, status = status,
            docId = DOC, docTitle = source.name, workspaceRootUuid = ROOT,
            sourceMimeType = "text/plain", targetContentHash = "hash-v1", targetEpoch = 2,
            createdAt = 1, updatedAt = 1,
        ))
    }

    private suspend fun seedFilesWithoutTask(db: InfinityDatabase = database) {
        db.fileEntryDao().insert(FileEntry(
            uuid = ROOT, workspaceRootUuid = ROOT, parentUuid = null, name = "root", hash = "root",
            isDirectory = true, physicalRootPath = source.parentFile!!.absolutePath,
            materializedPath = "/", createdAt = 1, updatedAt = 1,
        ))
        db.fileEntryDao().insert(FileEntry(
            uuid = DOC, workspaceRootUuid = ROOT, parentUuid = ROOT, name = source.name,
            hash = "hash-v1", mimeType = "text/plain",
            physicalRootPath = source.parentFile!!.absolutePath, materializedPath = "/${source.name}",
            createdAt = 1, updatedAt = 2, vectorizedAt = 1,
        ))
    }

    private suspend fun updateTarget(hash: String, epoch: Long, vectorizedAt: Long = 1) {
        val current = database.fileEntryDao().getByUuid(ROOT, DOC)!!
        database.fileEntryDao().update(
            current.copy(hash = hash, updatedAt = epoch, vectorizedAt = vectorizedAt),
        )
    }

    private fun queue(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        db: InfinityDatabase = database,
        documentIndexService: DocumentIndexService = ImmediateDocumentIndexService,
        ragConfig: RagConfiguration = RagConfiguration(),
        fileEntryDao: FileEntryDao = db.fileEntryDao(),
    ) = VectorizationQueue(
        vectorStore = mockk(relaxed = true),
        embeddingClient = mockk(relaxed = true),
        graphExtractor = null,
        vectorDao = db.vectorDao(),
        vectorizationTaskDao = db.vectorizationTaskDao(),
        ragConfig = ragConfig,
        dispatcher = dispatcher,
        fileEntryDao = fileEntryDao,
        documentIndexService = documentIndexService,
    )

    private class BlockingDocumentIndexService : DocumentIndexService {
        val firstStarted = CompletableDeferred<Unit>()
        val firstResult = CompletableDeferred<DocumentIndexResult>()
        val secondStarted = CompletableDeferred<Unit>()
        var firstRunId: String? = null

        override suspend fun rebuild(event: FileIndexEvent): DocumentIndexResult {
            if (!firstStarted.isCompleted) {
                firstRunId = (event as? FileIndexEvent.Changed)?.kgRunId
                firstStarted.complete(Unit)
                return firstResult.await()
            }
            secondStarted.complete(Unit)
            awaitCancellation()
        }

        override suspend fun delete(workspaceRootUuid: String, fileUuid: String): DocumentIndexResult =
            DocumentIndexResult.Deleted(fileUuid)
    }

    private object ImmediateDocumentIndexService : DocumentIndexService {
        override suspend fun rebuild(event: FileIndexEvent): DocumentIndexResult =
            DocumentIndexResult.Rebuilt(event.fileUuid)

        override suspend fun delete(workspaceRootUuid: String, fileUuid: String): DocumentIndexResult =
            DocumentIndexResult.Deleted(fileUuid)
    }

    private class CancellationAwareDocumentIndexService : DocumentIndexService {
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        private var calls = 0

        override suspend fun rebuild(event: FileIndexEvent): DocumentIndexResult {
            calls += 1
            if (calls == 1) {
                firstStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    firstCancelled.complete(Unit)
                }
            }
            secondStarted.complete(Unit)
            awaitCancellation()
        }

        override suspend fun delete(workspaceRootUuid: String, fileUuid: String): DocumentIndexResult =
            DocumentIndexResult.Deleted(fileUuid)
    }

    private class FirstCompletesSecondWaitsService : DocumentIndexService {
        val secondStarted = CompletableDeferred<Unit>()
        private var calls = 0

        override suspend fun rebuild(event: FileIndexEvent): DocumentIndexResult {
            calls += 1
            if (calls == 1) return DocumentIndexResult.Rebuilt(event.fileUuid)
            secondStarted.complete(Unit)
            awaitCancellation()
        }

        override suspend fun delete(workspaceRootUuid: String, fileUuid: String): DocumentIndexResult =
            DocumentIndexResult.Deleted(fileUuid)
    }

    private class FirstFailsSecondWaitsService(
        private val firstFailureMessage: String,
    ) : DocumentIndexService {
        val secondStarted = CompletableDeferred<Unit>()
        private var calls = 0

        override suspend fun rebuild(event: FileIndexEvent): DocumentIndexResult {
            calls += 1
            if (calls == 1) {
                return DocumentIndexResult.Failed(IllegalStateException(firstFailureMessage))
            }
            secondStarted.complete(Unit)
            awaitCancellation()
        }

        override suspend fun delete(workspaceRootUuid: String, fileUuid: String): DocumentIndexResult =
            DocumentIndexResult.Deleted(fileUuid)
    }

    private class DeleteAwareDocumentIndexService(
        private val database: InfinityDatabase,
    ) : DocumentIndexService {
        val rebuildStarted = CompletableDeferred<Unit>()
        val rebuildCancelled = CompletableDeferred<Unit>()
        val deleteCompleted = CompletableDeferred<Unit>()

        override suspend fun rebuild(event: FileIndexEvent): DocumentIndexResult {
            rebuildStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                rebuildCancelled.complete(Unit)
            }
        }

        override suspend fun delete(workspaceRootUuid: String, fileUuid: String): DocumentIndexResult {
            check(rebuildCancelled.isCompleted) { "delete 必须等待旧 rebuild worker 完成取消" }
            database.vectorDao().deleteByDocId(fileUuid)
            database.vectorizationTaskDao().deleteByWorkspaceFile(workspaceRootUuid, fileUuid)
            deleteCompleted.complete(Unit)
            return DocumentIndexResult.Deleted(fileUuid)
        }
    }

    private companion object {
        const val ROOT = "root"
        const val DOC = "doc"
        const val NULL_DOC = "doc-null"
        const val TASK = "doc-ref-existing"
    }
}
