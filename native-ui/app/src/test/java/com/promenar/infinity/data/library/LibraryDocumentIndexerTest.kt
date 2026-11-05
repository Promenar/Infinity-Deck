package com.promenar.infinity.data.library

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.dao.FileEntryDao
import com.promenar.infinity.data.local.db.dao.VectorizationTaskDao
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.VectorizationTaskEntity
import com.promenar.infinity.data.rag.VectorizationQueue
import com.promenar.infinity.data.repository.WorkspaceDeleteBarrierLease
import com.promenar.infinity.data.session.SessionExecutionGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class LibraryDocumentIndexerTest {
    private val queue = mockk<VectorizationQueue>()
    private val taskDao = mockk<VectorizationTaskDao>()
    private val fileDao = mockk<FileEntryDao>()
    private val barrier = mockk<WorkspaceDeleteBarrierLease>(relaxed = true)
    private val indexer = LibraryDocumentIndexer({ queue }, taskDao, fileDao, SessionExecutionGate()) { false }
    private val entry = FileEntry("doc", "root", "root", "doc.txt", "hash", "text/plain", 0,
        false, "/fixture", "/doc.txt", createdAt = 1, updatedAt = 1)

    @Test
    fun `显式抽图不受默认开关影响且按确认状态入队`() = runBlocking {
        coEvery { queue.enqueueDocumentReference(any(), any(), any(), any(), any(), any(), any(), any()) } returns "task"

        assertThat(indexer.enqueueGraph("root", entry, false)).isTrue()
        assertThat(indexer.enqueueGraph("root", entry, true)).isTrue()

        coVerify(exactly = 1) { queue.enqueueDocumentReference("root", "doc", "doc.txt", "text/plain", "hash", 1, "full", true) }
        coVerify(exactly = 1) { queue.enqueueDocumentReference("root", "doc", "doc.txt", "text/plain", "hash", 1, "full_confirmed", true) }
    }

    @Test
    fun `不支持的文件类型保留旧失败任务`() = runBlocking {
        coEvery { queue.acquireDeleteBarrier("root", listOf("doc")) } returns barrier
        coEvery { fileDao.getActiveByUuid("root", "doc") } returns entry.copy(mimeType = "application/x-unknown")
        assertThat(indexer.retryLegacy("root", entry)).isFalse()
        coVerify(exactly = 0) { taskDao.migrateLegacyToTarget(any(), any()) }
        coVerify(exactly = 1) { barrier.abort() }
        coVerify(exactly = 0) { queue.cancelAndJoin(any(), any()) }
    }

    @Test
    fun `旧任务迁移保留处理意图并且新版本不继承旧预算确认`() = runBlocking {
        coEvery { queue.acquireDeleteBarrier("root", listOf("doc")) } returns barrier
        coEvery { fileDao.getActiveByUuid("root", "doc") } returns entry
        coEvery { taskDao.getByWorkspaceFile("root", "doc", "document_reference") } returns null
        coEvery { taskDao.getByWorkspaceFile("root", "doc", "document") } returns VectorizationTaskEntity(
            id = "legacy", type = "document", status = "failed", workspaceRootUuid = "root", docId = "doc",
            kgStrategy = "full_confirmed", skipVectorization = true, targetContentHash = "old-hash", targetEpoch = 1,
            createdAt = 1, updatedAt = 1,
        )
        val replacement = slot<VectorizationTaskEntity>()
        coEvery { taskDao.migrateLegacyToTarget("legacy", capture(replacement)) } returns
            com.promenar.infinity.data.local.db.dao.VectorizationTaskTargetUpsertResult(
                com.promenar.infinity.data.local.db.dao.VectorizationTaskTargetUpsertOutcome.INSERTED, "new",
            )
        assertThat(indexer.retryLegacy("root", entry)).isTrue()
        assertThat(replacement.captured.kgStrategy).isEqualTo("full")
        assertThat(replacement.captured.skipVectorization).isTrue()
    }

    @Test
    fun `屏障恢复失败后重新提交已经持久化的引用任务`() = runBlocking {
        coEvery { queue.acquireDeleteBarrier("root", listOf("doc")) } returns barrier
        coEvery { barrier.abort() } throws IllegalStateException("fixture")
        coEvery { fileDao.getActiveByUuid("root", "doc") } returns entry
        coEvery { taskDao.getByWorkspaceFile("root", "doc", "document_reference") } returns null
        coEvery { taskDao.getByWorkspaceFile("root", "doc", "document") } returns VectorizationTaskEntity(
            id = "legacy", type = "document", status = "failed", workspaceRootUuid = "root", docId = "doc", createdAt = 1, updatedAt = 1,
        )
        coEvery { taskDao.migrateLegacyToTarget(any(), any()) } returns
            com.promenar.infinity.data.local.db.dao.VectorizationTaskTargetUpsertResult(
                com.promenar.infinity.data.local.db.dao.VectorizationTaskTargetUpsertOutcome.INSERTED, "new",
            )
        coEvery { queue.enqueueDocumentReference(any(), any(), any(), any(), any(), any(), any(), any()) } returns "new"
        assertThat(indexer.retryLegacy("root", entry)).isTrue()
        coVerify(exactly = 1) { queue.enqueueDocumentReference("root", "doc", "doc.txt", "text/plain", "hash", 1, null, false) }
    }

    @Test
    fun `迁移异常时恢复旧任务并传播错误`() = runBlocking {
        coEvery { queue.acquireDeleteBarrier("root", listOf("doc")) } returns barrier
        coEvery { fileDao.getActiveByUuid("root", "doc") } returns entry
        coEvery { taskDao.getByWorkspaceFile("root", "doc", "document_reference") } returns null
        coEvery { taskDao.getByWorkspaceFile("root", "doc", "document") } returns VectorizationTaskEntity(
            id = "legacy", type = "document", status = "failed", workspaceRootUuid = "root", docId = "doc", createdAt = 1, updatedAt = 1,
        )
        coEvery { taskDao.migrateLegacyToTarget(any(), any()) } throws IllegalStateException("fixture")
        var failed = false
        try { indexer.retryLegacy("root", entry) } catch (_: IllegalStateException) { failed = true }
        assertThat(failed).isTrue()
        coVerify(exactly = 1) { barrier.abort() }
        coVerify(exactly = 0) { queue.cancelAndJoin(any(), any()) }
    }
}
