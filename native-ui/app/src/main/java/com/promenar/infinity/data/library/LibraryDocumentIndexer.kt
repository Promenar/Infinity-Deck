package com.promenar.infinity.data.library

import com.promenar.infinity.data.local.db.dao.FileEntryDao
import com.promenar.infinity.data.local.db.dao.VectorizationTaskDao
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.VectorizationTaskEntity
import com.promenar.infinity.data.rag.DocumentReferenceExtractor
import com.promenar.infinity.data.rag.KgExtractionBudget
import com.promenar.infinity.data.rag.VectorizationQueue
import com.promenar.infinity.data.session.SessionExecutionGate
import com.promenar.infinity.startup.StartupSubsystem
import com.promenar.infinity.startup.StartupWriteBarrier
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 新旧资料入口共用文件引用索引与旧任务的原子迁移。 */
class LibraryDocumentIndexer(
    private val queue: suspend () -> VectorizationQueue,
    private val taskDao: VectorizationTaskDao,
    private val fileDao: FileEntryDao,
    private val sessionGate: SessionExecutionGate,
    private val graphEnabled: () -> Boolean,
) {
    suspend fun enqueue(root: String, entry: FileEntry) {
        queue().enqueueDocumentReference(
            workspaceRootUuid = root,
            docId = entry.uuid,
            docTitle = entry.name,
            sourceMimeType = requireNotNull(entry.mimeType) { "重新索引文件缺少 MIME" },
            targetContentHash = entry.hash,
            targetEpoch = entry.updatedAt,
            kgStrategy = if (graphEnabled()) "full" else null,
        )
    }

    suspend fun enqueueGraph(root: String, entry: FileEntry, confirmed: Boolean): Boolean {
        val mime = entry.mimeType ?: return false
        if (mime !in DocumentReferenceExtractor.SUPPORTED_MIME_TYPES) return false
        queue().enqueueDocumentReference(
            workspaceRootUuid = root, docId = entry.uuid, docTitle = entry.name,
            sourceMimeType = mime, targetContentHash = entry.hash, targetEpoch = entry.updatedAt,
            kgStrategy = if (confirmed) KgExtractionBudget.CONFIRMED_STRATEGY else "full",
            skipVectorization = true,
        )
        return true
    }

    suspend fun retryLegacy(root: String, entry: FileEntry): Boolean {
        StartupWriteBarrier.requireWritable(rootId = root, subsystem = StartupSubsystem.VECTOR_QUEUE)
        return sessionGate.withWorkspaceAdmission(root) {
            val activeQueue = queue()
            val barrier = activeQueue.acquireDeleteBarrier(root, listOf(entry.uuid))
            var migrated: VectorizationTaskEntity? = null
            try {
                barrier.awaitReady()
                val current = fileDao.getActiveByUuid(root, entry.uuid)?.takeUnless { it.isDirectory }
                    ?: return@withWorkspaceAdmission false
                val mime = current.mimeType
                if (mime !in DocumentReferenceExtractor.SUPPORTED_MIME_TYPES || current.hash.isBlank() || current.updatedAt <= 0) {
                    return@withWorkspaceAdmission false
                }
                if (taskDao.getByWorkspaceFile(root, entry.uuid, VectorizationQueue.TYPE_DOCUMENT_REFERENCE) != null) {
                    return@withWorkspaceAdmission false
                }
                val legacy = taskDao.getByWorkspaceFile(root, entry.uuid, "document")
                    ?.takeIf { it.status in setOf("failed", "partial", "interrupted") }
                    ?: return@withWorkspaceAdmission false
                val now = System.currentTimeMillis()
                val replacement = VectorizationTaskEntity(
                    id = "library-retry-${UUID.randomUUID()}", type = VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
                    status = "pending", docId = current.uuid, docTitle = current.name, workspaceRootUuid = root,
                    sourceMimeType = mime, targetContentHash = current.hash, targetEpoch = current.updatedAt,
                    kgStrategy = legacy.kgStrategy?.takeUnless {
                        it == KgExtractionBudget.CONFIRMED_STRATEGY &&
                            (legacy.targetContentHash != current.hash || legacy.targetEpoch != current.updatedAt)
                    } ?: "full".takeIf { legacy.kgStrategy != null },
                    skipVectorization = legacy.skipVectorization, createdAt = now, updatedAt = now,
                )
                (taskDao.migrateLegacyToTarget(legacy.id, replacement) != null).also { success ->
                    if (success) migrated = replacement
                }
            } finally {
                // abort 根据事务后的持久记录重建队列；失败时保留原任务，成功时投递新任务。
                try {
                    withContext(NonCancellable) { barrier.abort() }
                } catch (failure: Exception) {
                    val target = migrated ?: throw failure
                    try {
                        withContext(NonCancellable) { enqueuePending(activeQueue, target) }
                    } catch (recovery: Exception) {
                        failure.addSuppressed(recovery)
                        throw failure
                    }
                }
            }
        }
    }

    suspend fun resumePending(root: String, entry: FileEntry, task: VectorizationTaskEntity): Boolean {
        if (task.status != "pending" || task.workspaceRootUuid != root || task.docId != entry.uuid ||
            task.targetContentHash != entry.hash || task.targetEpoch != entry.updatedAt) return false
        enqueuePending(queue(), task)
        return true
    }

    private suspend fun enqueuePending(activeQueue: VectorizationQueue, task: VectorizationTaskEntity) {
        activeQueue.enqueueDocumentReference(
            workspaceRootUuid = requireNotNull(task.workspaceRootUuid), docId = requireNotNull(task.docId),
            docTitle = requireNotNull(task.docTitle), sourceMimeType = requireNotNull(task.sourceMimeType),
            targetContentHash = requireNotNull(task.targetContentHash), targetEpoch = task.targetEpoch,
            kgStrategy = task.kgStrategy, skipVectorization = task.skipVectorization,
        )
    }

    suspend fun retryReference(root: String, documentId: String): Boolean =
        queue().retryDocumentReference(root, documentId)
}
