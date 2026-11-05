package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.dao.FileEntryDao
import com.promenar.infinity.data.local.db.dao.VectorDao
import com.promenar.infinity.data.local.db.dao.VectorizationTaskDao
import com.promenar.infinity.data.local.db.dao.VectorizationTaskTargetUpsertOutcome
import com.promenar.infinity.data.local.db.dao.VectorizationTaskTargetUpsertResult
import com.promenar.infinity.data.local.db.entity.VectorizationTaskEntity
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class VectorizationQueueReferenceTest {
    @Test
    fun `记忆索引的限流错误按 Retry-After 重试`() = runTest {
        var calls = 0
        val embedding = mockk<EmbeddingClient>()
        coEvery { embedding.embedDocuments(any()) } coAnswers {
            if (++calls == 1) throw EmbeddingBatchException("HTTP 429：服务限流", true, 429, 7_000)
            EmbeddingResult(firstArg<List<String>>().map { floatArrayOf(1f) })
        }
        val queue = VectorizationQueue(
            vectorStore = mockk(relaxed = true), embeddingClient = embedding, graphExtractor = null,
            vectorDao = mockk(relaxed = true), vectorizationTaskDao = mockk(relaxed = true),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            queue.enqueueMemory("session", "user", "assistant", "u", "a")
            runCurrent()
            assertThat(calls).isEqualTo(1)
            advanceTimeBy(6_999)
            runCurrent()
            assertThat(calls).isEqualTo(1)
            advanceTimeBy(2)
            runCurrent()
            assertThat(calls).isEqualTo(2)
        } finally { queue.shutdown() }
    }

    @Test
    fun `session delete barrier取消并等待memory worker且持有期间拒绝重新入队`() = runTest {
        val taskDao = mockk<VectorizationTaskDao>(relaxed = true)
        val embeddingStarted = CompletableDeferred<Unit>()
        val embeddingCancelled = CompletableDeferred<Unit>()
        val embeddingClient = mockk<EmbeddingClient>()
        coEvery { embeddingClient.embedDocuments(any()) } coAnswers {
            embeddingStarted.complete(Unit)
            try { awaitCancellation() } finally { embeddingCancelled.complete(Unit) }
        }
        val queue = VectorizationQueue(
            vectorStore = mockk(relaxed = true),
            embeddingClient = embeddingClient,
            graphExtractor = null,
            vectorDao = mockk(relaxed = true),
            vectorizationTaskDao = taskDao,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        queue.enqueueMemory("session-1", "user", "assistant", "u1", "a1")
        runCurrent()
        embeddingStarted.await()

        val barrier = queue.acquireSessionDeleteBarrier("session-1", ROOT, emptyList())
        barrier.awaitReady()

        assertThat(embeddingCancelled.isCompleted).isTrue()
        assertThat(runCatching {
            queue.enqueueMemory("session-1", "late", "late", "u2", "a2")
        }.exceptionOrNull()).isNotNull()
        barrier.commit()
        queue.shutdown()
    }

    @Test
    fun `状态订阅token关闭后不再接收队列变化`() = runTest {
        val taskDao = mockk<VectorizationTaskDao>(relaxed = true)
        stubTargetUpsert(taskDao)
        val queue = queue(taskDao, StandardTestDispatcher(testScheduler))
        var calls = 0

        val subscription = queue.setOnStateChange { _, _ -> calls += 1 }
        subscription.close()
        queue.enqueueDocumentReference(ROOT, DOC, "note.txt", "text/plain", HASH, EPOCH)

        assertThat(calls).isEqualTo(1)
        queue.shutdown()
    }

    @Test
    fun `跨workspace相同docId的文件引用任务互不移除`() = runTest {
        val taskDao = mockk<VectorizationTaskDao>(relaxed = true)
        stubIndependentTargetUpserts(taskDao)
        val queue = queue(taskDao, StandardTestDispatcher(testScheduler))

        queue.enqueueDocumentReference("other-root", DOC, "other.txt", "text/plain", "other-hash", 1)
        queue.enqueueDocumentReference(ROOT, DOC, "note.txt", "text/plain", HASH, EPOCH)

        assertThat(queue.snapshotState().queue.map { it.workspaceRootUuid })
            .containsExactly("other-root", ROOT)
            .inOrder()
        queue.shutdown()
    }

    @Test
    fun `newer文件引用入队不移除同docId的legacy document任务`() = runTest {
        val taskDao = mockk<VectorizationTaskDao>(relaxed = true)
        stubTargetUpsert(taskDao)
        val queue = queue(taskDao, StandardTestDispatcher(testScheduler))

        queue.enqueueDocument(ROOT, DOC, "legacy.txt", "legacy content")
        queue.enqueueDocumentReference(ROOT, DOC, "note.txt", "text/plain", HASH, EPOCH)

        assertThat(queue.snapshotState().queue.map { it.type })
            .containsExactly("document", VectorizationQueue.TYPE_DOCUMENT_REFERENCE)
            .inOrder()
        queue.shutdown()
    }

    @Test
    fun `public cancelAndJoin会主动取消并等待永不返回的legacy document worker`() = runTest {
        val taskDao = mockk<VectorizationTaskDao>(relaxed = true)
        val embeddingStarted = CompletableDeferred<Unit>()
        val embeddingCancelled = CompletableDeferred<Unit>()
        val embeddingClient = mockk<EmbeddingClient>()
        coEvery { embeddingClient.embedDocuments(any()) } coAnswers {
            embeddingStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                embeddingCancelled.complete(Unit)
            }
        }
        val queue = VectorizationQueue(
            vectorStore = mockk(relaxed = true),
            embeddingClient = embeddingClient,
            graphExtractor = null,
            vectorDao = mockk(relaxed = true),
            vectorizationTaskDao = taskDao,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        queue.enqueueDocument(ROOT, DOC, "legacy.txt", "legacy content")
        runCurrent()
        embeddingStarted.await()

        queue.cancelAndJoin(ROOT, listOf(DOC))

        embeddingCancelled.await()
        assertThat(queue.snapshotState().queue).isEmpty()
        coVerify(exactly = 1) { embeddingClient.embedDocuments(any()) }
        queue.shutdown()
    }

    @Test
    fun `Deleted必须等待legacy vector worker取消完成后才清理派生数据`() = runTest {
        val taskDao = mockk<VectorizationTaskDao>(relaxed = true)
        val embeddingStarted = CompletableDeferred<Unit>()
        val embeddingCancelled = CompletableDeferred<Unit>()
        val embeddingClient = mockk<EmbeddingClient>()
        coEvery { embeddingClient.embedDocuments(any()) } coAnswers {
            embeddingStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                embeddingCancelled.complete(Unit)
            }
        }
        val service = mockk<DocumentIndexService>()
        coEvery { service.delete(ROOT, DOC) } coAnswers {
            check(embeddingCancelled.isCompleted) { "删除派生数据前必须等待 legacy worker 取消" }
            DocumentIndexResult.Deleted(DOC)
        }
        val queue = VectorizationQueue(
            vectorStore = mockk(relaxed = true),
            embeddingClient = embeddingClient,
            graphExtractor = null,
            vectorDao = mockk(relaxed = true),
            vectorizationTaskDao = taskDao,
            dispatcher = StandardTestDispatcher(testScheduler),
            documentIndexService = service,
        )

        queue.enqueueDocument(ROOT, DOC, "legacy.txt", "legacy content")
        runCurrent()
        embeddingStarted.await()

        queue.publish(FileIndexEvent.Deleted(ROOT, DOC))

        embeddingCancelled.await()
        coVerify(exactly = 1) { service.delete(ROOT, DOC) }
        assertThat(queue.snapshotState().queue).isEmpty()
        queue.shutdown()
    }

    @Test
    fun `document reference未配置事务索引服务时拒绝持久入队`() = runTest {
        val taskDao = mockk<VectorizationTaskDao>(relaxed = true)
        val queue = VectorizationQueue(
            vectorStore = mockk(relaxed = true),
            embeddingClient = mockk(relaxed = true),
            graphExtractor = null,
            vectorDao = mockk(relaxed = true),
            vectorizationTaskDao = taskDao,
            dispatcher = StandardTestDispatcher(testScheduler),
            fileEntryDao = mockk(relaxed = true),
            documentIndexService = null,
        )

        val failure = runCatching {
            queue.enqueueDocumentReference(ROOT, DOC, "note.txt", "text/plain", HASH, EPOCH)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        coVerify(exactly = 0) { taskDao.upsertTarget(any()) }
    }

    @Test
    fun `文件索引事件sink将变更持久入队并将删除委托事务清理`() = runTest {
        val taskDao = mockk<VectorizationTaskDao>(relaxed = true)
        stubTargetUpsert(taskDao)
        val fileDao = mockk<FileEntryDao>()
        coEvery { fileDao.getActiveByUuid(ROOT, DOC) } returns com.promenar.infinity.data.local.db.entity.FileEntry(
            uuid = DOC, workspaceRootUuid = ROOT, parentUuid = ROOT, name = "note.txt", hash = "hash-v1",
            mimeType = "text/plain", physicalRootPath = "/tmp", materializedPath = "/note.txt",
            createdAt = 1, updatedAt = 1,
        )
        val service = mockk<DocumentIndexService>()
        coEvery { service.delete(ROOT, DOC) } returns DocumentIndexResult.Deleted(DOC)
        val queue = VectorizationQueue(
            vectorStore = mockk(relaxed = true),
            embeddingClient = mockk(relaxed = true),
            graphExtractor = null,
            vectorDao = mockk(relaxed = true),
            vectorizationTaskDao = taskDao,
            dispatcher = StandardTestDispatcher(testScheduler),
            fileEntryDao = fileDao,
            documentIndexService = service,
        )

        queue.publish(FileIndexEvent.Changed(ROOT, DOC, HASH, EPOCH))
        queue.publish(FileIndexEvent.Deleted(ROOT, DOC))

        coVerify(exactly = 1) { taskDao.upsertTarget(match { it.docId == DOC }) }
        coVerify(exactly = 1) { service.delete(ROOT, DOC) }
    }

    @Test
    fun `文件引用任务委托事务索引服务且hash竞态不伪装完成`() = runTest {
        val taskDao = mockk<VectorizationTaskDao>(relaxed = true)
        stubTargetUpsert(taskDao)
        val fileDao = mockk<FileEntryDao>()
        coEvery { fileDao.getActiveByUuid(ROOT, DOC) } returns com.promenar.infinity.data.local.db.entity.FileEntry(
            uuid = DOC, workspaceRootUuid = ROOT, parentUuid = ROOT, name = "note.txt", hash = "hash-v1",
            mimeType = "text/plain", physicalRootPath = "/tmp", materializedPath = "/note.txt",
            createdAt = 1, updatedAt = 1,
        )
        val service = mockk<DocumentIndexService>()
        coEvery { service.rebuild(match {
            it is FileIndexEvent.Changed && it.contentHash == HASH && it.targetEpoch == EPOCH &&
                it.skipVectorization && it.kgStrategy == "semantic" && it.activeTaskId != null
        }) } returns
            DocumentIndexResult.HashChanged("hash-v2")
        val queue = VectorizationQueue(
            vectorStore = mockk(relaxed = true),
            embeddingClient = mockk(relaxed = true),
            graphExtractor = null,
            vectorDao = mockk(relaxed = true),
            vectorizationTaskDao = taskDao,
            dispatcher = StandardTestDispatcher(testScheduler),
            fileEntryDao = fileDao,
            documentIndexService = service,
        )

        queue.enqueueDocumentReference(
            ROOT, DOC, "note.txt", "text/plain", HASH, EPOCH,
            kgStrategy = "semantic", skipVectorization = true,
        )
        advanceUntilIdle()

        coVerify(exactly = 1) {
            service.rebuild(match {
                it is FileIndexEvent.Changed && it.contentHash == HASH && it.targetEpoch == EPOCH &&
                    it.activeTaskId != null
            })
        }
        coVerify(exactly = 0) { fileDao.update(any()) }
        assertThat(queue.state.value.queue.single().status).isEqualTo("failed")
    }

    @Test
    fun `失败部分与中断文件任务收到Changed后重置并重新入队`() = runTest {
        listOf("failed", "partial", "interrupted").forEach { status ->
            val taskDao = mockk<VectorizationTaskDao>(relaxed = true)
            stubTargetUpsert(
                taskDao,
                entity("doc-ref-$status").copy(status = status),
                VectorizationTaskTargetUpsertOutcome.IDEMPOTENT,
            )
            val queue = queue(taskDao, StandardTestDispatcher(testScheduler))

            val id = queue.enqueueDocumentReference(ROOT, DOC, "note.txt", "text/plain", HASH, EPOCH)

            assertThat(id).isNotEqualTo("doc-ref-$status")
            assertThat(queue.getQueueLength()).isEqualTo(1)
            coVerify(exactly = 1) {
                taskDao.replaceTargetIfExpected(match { it.id == "doc-ref-$status" }, match {
                    it.id == id && it.status == "pending" && it.targetContentHash == HASH && it.targetEpoch == EPOCH
                })
            }
            queue.shutdown()
        }
    }

    @Test
    fun `失败任务reset CAS丢失竞态时重读active并返回真实id`() = runTest {
        val taskDao = mockk<VectorizationTaskDao>(relaxed = true)
        val stale = entity("stale-id").copy(status = "failed")
        val active = entity("active-new-id").copy(
            targetContentHash = "hash-v2",
            targetEpoch = 2,
        )
        coEvery { taskDao.upsertTarget(any()) } returns VectorizationTaskTargetUpsertResult(
            VectorizationTaskTargetUpsertOutcome.IDEMPOTENT,
            stale.id,
        )
        coEvery { taskDao.getById(stale.id) } returns stale
        coEvery { taskDao.updateForTarget(any()) } returns 0
        coEvery {
            taskDao.getByWorkspaceFile(ROOT, DOC, VectorizationQueue.TYPE_DOCUMENT_REFERENCE)
        } returns active
        coEvery { taskDao.getCompletedDocumentReferenceTasks() } returns emptyList()
        val queue = queue(taskDao, StandardTestDispatcher(testScheduler))

        val returnedId = queue.enqueueDocumentReference(
            ROOT, DOC, "note.txt", "text/plain", HASH, EPOCH,
        )

        assertThat(returnedId).isEqualTo("active-new-id")
        assertThat(queue.snapshotState().queue.single().id).isEqualTo("active-new-id")
        assertThat(queue.snapshotState().queue.single().targetEpoch).isEqualTo(2)
        queue.shutdown()
    }

    @Test
    fun `文件引用任务先持久化且document不再滥用session外键`() = runTest {
        val taskDao = mockk<VectorizationTaskDao>()
        stubTargetUpsert(taskDao)
        // StandardTestDispatcher 会在 runTest 收尾执行后台 processor；缺失文件会持久化失败状态并清理 completed。
        coJustRun { taskDao.insert(any()) }
        coJustRun { taskDao.deleteCompletedNonReferenceTasks() }
        val queue = queue(taskDao, StandardTestDispatcher(testScheduler))

        val id = queue.enqueueDocumentReference(ROOT, DOC, "note.txt", "text/plain", HASH, EPOCH)

        assertThat(id).startsWith("doc-ref-")
        assertThat(queue.getQueueLength()).isEqualTo(1)
        coVerify(exactly = 1) {
            taskDao.upsertTarget(match {
                it.id == id && it.workspaceRootUuid == ROOT && it.docId == DOC &&
                    it.sessionId == null && it.userContent == null &&
                    !it.assistantMessageId.isNullOrBlank() &&
                    it.type == VectorizationQueue.TYPE_DOCUMENT_REFERENCE
            })
        }
        advanceUntilIdle()
        coVerify(exactly = 1) { taskDao.deleteCompletedNonReferenceTasks() }
    }

    @Test
    fun `任务持久化失败时不得进入内存队列或伪装成功`() = runTest {
        val taskDao = mockk<VectorizationTaskDao>()
        coEvery { taskDao.upsertTarget(any()) } throws IllegalStateException("disk full")
        val queue = queue(taskDao, StandardTestDispatcher(testScheduler))

        val failure = runCatching {
            queue.enqueueDocumentReference(ROOT, DOC, "note.txt", "text/plain", HASH, EPOCH)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(queue.getQueueLength()).isEqualTo(0)
    }

    @Test
    fun `同一workspace文件任务按复合键幂等`() = runTest {
        val taskDao = mockk<VectorizationTaskDao>()
        val existing = entity("doc-ref-existing")
        stubTargetUpsert(taskDao, existing, VectorizationTaskTargetUpsertOutcome.IDEMPOTENT)
        val queue = queue(taskDao, StandardTestDispatcher(testScheduler))

        val id = queue.enqueueDocumentReference(ROOT, DOC, "note.txt", "text/plain", HASH, EPOCH)

        assertThat(id).isEqualTo(existing.id)
        assertThat(queue.getQueueLength()).isEqualTo(1)
        coVerify(exactly = 1) { taskDao.upsertTarget(any()) }
    }

    @Test
    fun `恢复完成后新注册观察者立即收到持久化失败任务`() = runTest {
        val taskDao = mockk<VectorizationTaskDao>()
        val failed = entity("doc-ref-failed").copy(status = "failed", error = "embedding unavailable")
        coEvery { taskDao.getCompletedDocumentReferenceTasks() } returns emptyList()
        coJustRun { taskDao.deleteCompletedNonReferenceTasks() }
        coJustRun { taskDao.markStaleAsInterrupted(any(), any()) }
        coEvery { taskDao.recoverDocumentReferenceProcessing(any()) } returns 0
        coEvery { taskDao.deleteOrphanSessionStateTasks() } returns 0
        coEvery { taskDao.recoverSessionStateProcessing(any()) } returns 0
        coEvery { taskDao.getRecoverableTasks() } returns emptyList()
        coEvery { taskDao.getAttentionTasks() } returns listOf(failed)
        val fileDao = mockk<FileEntryDao>()
        coEvery { fileDao.getUnvectorizedSupportedFiles(any()) } returns emptyList()
        val queue = VectorizationQueue(
            vectorStore = mockk(relaxed = true),
            embeddingClient = mockk(relaxed = true),
            graphExtractor = null,
            vectorDao = mockk(relaxed = true),
            vectorizationTaskDao = taskDao,
            dispatcher = StandardTestDispatcher(testScheduler),
            fileEntryDao = fileDao,
        )
        assertThat(queue.resumeInterruptedTasks().isSuccess).isTrue()

        var observed: VectorizationTask? = null
        queue.setOnStateChange { _, current -> observed = current }

        assertThat(observed?.id).isEqualTo(failed.id)
        assertThat(queue.state.value.restored).isTrue()
        assertThat(queue.state.value.currentTask?.status).isEqualTo("failed")
        assertThat(queue.snapshotState()).isEqualTo(queue.state.value)
    }

    @Test
    fun `恢复会清理身份完整但文件已删除的legacy失败任务及其产物`() = runTest {
        val taskDao = mockk<VectorizationTaskDao>()
        val failed = VectorizationTaskEntity(
            id = "legacy-deleted-file",
            type = "document",
            status = "failed",
            docId = DOC,
            workspaceRootUuid = ROOT,
            docTitle = "deleted.txt",
            error = "embedding unavailable",
            createdAt = 1,
            updatedAt = 1,
        )
        coEvery { taskDao.getCompletedDocumentReferenceTasks() } returns emptyList()
        coJustRun { taskDao.deleteCompletedNonReferenceTasks() }
        coJustRun { taskDao.markStaleAsInterrupted(any(), any()) }
        coEvery { taskDao.recoverDocumentReferenceProcessing(any()) } returns 0
        coEvery { taskDao.deleteOrphanSessionStateTasks() } returns 0
        coEvery { taskDao.recoverSessionStateProcessing(any()) } returns 0
        coEvery { taskDao.getRecoverableTasks() } returns emptyList()
        coEvery { taskDao.getAttentionTasks() } returns listOf(failed)
        coJustRun { taskDao.delete(failed) }
        val fileDao = mockk<FileEntryDao>()
        coEvery { fileDao.getActiveByUuid(ROOT, DOC) } returns null
        coEvery { fileDao.getUnvectorizedSupportedFiles(any()) } returns emptyList()
        val vectorDao = mockk<VectorDao>()
        coJustRun { vectorDao.deleteByDocId(DOC) }
        val graphExtractor = mockk<GraphExtractor>()
        coJustRun { graphExtractor.clearPersistedGraphForDoc(DOC) }
        val queue = VectorizationQueue(
            vectorStore = mockk(relaxed = true),
            embeddingClient = mockk(relaxed = true),
            graphExtractor = graphExtractor,
            vectorDao = vectorDao,
            vectorizationTaskDao = taskDao,
            dispatcher = StandardTestDispatcher(testScheduler),
            fileEntryDao = fileDao,
        )

        assertThat(queue.resumeInterruptedTasks().isSuccess).isTrue()

        coVerify(exactly = 1) { vectorDao.deleteByDocId(DOC) }
        coVerify(exactly = 1) { graphExtractor.clearPersistedGraphForDoc(DOC) }
        coVerify(exactly = 1) { taskDao.delete(failed) }
        assertThat(queue.snapshotState().queue).isEmpty()
        queue.shutdown()
    }

    @Test
    fun `失败文件任务重新入队时Entity到内存模型保留target`() = runTest {
        val taskDao = mockk<VectorizationTaskDao>(relaxed = true)
        val existing = entity("target-retry").copy(
            status = "failed",
            targetContentHash = "content-hash",
            targetEpoch = 42,
        )
        stubTargetUpsert(taskDao, existing, VectorizationTaskTargetUpsertOutcome.IDEMPOTENT)
        val queue = queue(taskDao, StandardTestDispatcher(testScheduler))

        queue.enqueueDocumentReference(ROOT, DOC, "note.txt", "text/plain", "content-hash", 42)

        val restored = queue.snapshotState().queue.single()
        assertThat(restored.targetContentHash).isEqualTo("content-hash")
        assertThat(restored.targetEpoch).isEqualTo(42)
    }

    @Test
    fun `启动恢复后的任务再次保存仍保留迁移target`() = runTest {
        val saved = mutableListOf<VectorizationTaskEntity>()
        val taskDao = mockk<VectorizationTaskDao>(relaxed = true)
        val recoverable = entity("target-recover").copy(
            status = "interrupted",
            targetContentHash = "migrated-hash",
            targetEpoch = 99,
        )
        coEvery { taskDao.getRecoverableTasks() } returns listOf(recoverable)
        coEvery { taskDao.getAttentionTasks() } returns emptyList()
        coEvery { taskDao.updateForTarget(capture(saved)) } returns 1
        val fileDao = mockk<FileEntryDao>(relaxed = true)
        coEvery { fileDao.getUnvectorizedSupportedFiles(any()) } returns emptyList()
        coEvery { fileDao.getByUuid(ROOT, DOC) } returns null
        val queue = VectorizationQueue(
            vectorStore = mockk(relaxed = true),
            embeddingClient = mockk(relaxed = true),
            graphExtractor = null,
            vectorDao = mockk(relaxed = true),
            vectorizationTaskDao = taskDao,
            dispatcher = StandardTestDispatcher(testScheduler),
            fileEntryDao = fileDao,
            documentIndexService = mockk<DocumentIndexService>().also { service ->
                coEvery { service.rebuild(any()) } returns DocumentIndexResult.Failed(IllegalStateException("stop"))
            },
        )

        assertThat(queue.resumeInterruptedTasks().isSuccess).isTrue()
        assertThat(queue.snapshotState().queue.single().targetContentHash).isEqualTo("migrated-hash")
        assertThat(queue.snapshotState().queue.single().targetEpoch).isEqualTo(99)

        advanceUntilIdle()

        assertThat(saved).isNotEmpty()
        assertThat(saved.last().targetContentHash).isEqualTo("migrated-hash")
        assertThat(saved.last().targetEpoch).isEqualTo(99)
    }

    @Test
    fun `处理协程取消不标记failed且不进入内部重试`() = runTest {
        val taskDao = mockk<VectorizationTaskDao>(relaxed = true)
        val saved = mutableListOf<VectorizationTaskEntity>()
        coEvery { taskDao.insert(capture(saved)) } just Runs
        val embeddingClient = mockk<EmbeddingClient>()
        coEvery { embeddingClient.embedDocuments(any()) } throws CancellationException("stop")
        val queue = VectorizationQueue(
            vectorStore = mockk(relaxed = true),
            embeddingClient = embeddingClient,
            graphExtractor = null,
            vectorDao = mockk(relaxed = true),
            vectorizationTaskDao = taskDao,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        queue.enqueueMemory("session", "user", "assistant", "user-id", "assistant-id")
        advanceUntilIdle()

        assertThat(saved.map { it.status }).doesNotContain("failed")
        assertThat(queue.getQueueLength()).isEqualTo(0)
        coVerify(exactly = 1) { embeddingClient.embedDocuments(any()) }
    }

    private fun queue(
        taskDao: VectorizationTaskDao,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ) = VectorizationQueue(
        vectorStore = mockk(relaxed = true),
        embeddingClient = mockk(relaxed = true),
        graphExtractor = null,
        vectorDao = mockk<VectorDao>(relaxed = true),
        vectorizationTaskDao = taskDao,
        dispatcher = dispatcher,
        fileEntryDao = mockk<FileEntryDao>(relaxed = true),
        documentIndexService = mockk<DocumentIndexService>(relaxed = true),
    )

    private fun stubTargetUpsert(
        taskDao: VectorizationTaskDao,
        initial: VectorizationTaskEntity? = null,
        outcome: VectorizationTaskTargetUpsertOutcome = VectorizationTaskTargetUpsertOutcome.INSERTED,
    ) {
        var active = initial
        coJustRun { taskDao.deleteCompletedNonReferenceTasks() }
        coEvery { taskDao.getCompletedDocumentReferenceTasks() } returns emptyList()
        coEvery { taskDao.upsertTarget(any()) } answers {
            val incoming = firstArg<VectorizationTaskEntity>()
            if (outcome == VectorizationTaskTargetUpsertOutcome.INSERTED ||
                outcome == VectorizationTaskTargetUpsertOutcome.REPLACED_NEWER
            ) {
                active = incoming
            }
            val current = checkNotNull(active)
            VectorizationTaskTargetUpsertResult(outcome, current.id)
        }
        coEvery { taskDao.getById(any()) } answers {
            active?.takeIf { it.id == firstArg<String>() }
        }
        coEvery {
            taskDao.getByWorkspaceFile(ROOT, DOC, VectorizationQueue.TYPE_DOCUMENT_REFERENCE)
        } answers { active }
        coEvery { taskDao.replaceTargetIfExpected(any(), any()) } answers {
            if (active == firstArg<VectorizationTaskEntity>()) {
                active = secondArg()
                1
            } else 0
        }
        coEvery { taskDao.updateForTarget(any()) } answers {
            active = firstArg()
            1
        }
    }

    private fun stubIndependentTargetUpserts(taskDao: VectorizationTaskDao) {
        val active = mutableMapOf<String, VectorizationTaskEntity>()
        coEvery { taskDao.upsertTarget(any()) } answers {
            firstArg<VectorizationTaskEntity>().let { incoming ->
                active[incoming.id] = incoming
                VectorizationTaskTargetUpsertResult(
                    VectorizationTaskTargetUpsertOutcome.INSERTED,
                    incoming.id,
                )
            }
        }
        coEvery { taskDao.getById(any()) } answers { active[firstArg()] }
        coEvery { taskDao.updateForTarget(any()) } answers {
            firstArg<VectorizationTaskEntity>().let { active[it.id] = it }
            1
        }
        coEvery { taskDao.getCompletedDocumentReferenceTasks() } returns emptyList()
    }

    private fun entity(id: String) = VectorizationTaskEntity(
        id = id,
        type = VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
        status = "pending",
        docId = DOC,
        docTitle = "note.txt",
        workspaceRootUuid = ROOT,
        sourceMimeType = "text/plain",
        targetContentHash = HASH,
        targetEpoch = EPOCH,
        createdAt = 1,
        updatedAt = 1,
    )

    private companion object {
        const val ROOT = "workspace-root"
        const val DOC = "doc-id"
        const val HASH = "hash-v1"
        const val EPOCH = 1L
    }
}
