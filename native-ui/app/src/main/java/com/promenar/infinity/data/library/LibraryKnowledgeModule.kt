package com.promenar.infinity.data.library

import android.content.Context
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.rag.VectorizationQueue
import com.promenar.infinity.domain.library.LibraryKnowledgeSource
import com.promenar.infinity.domain.usecase.RagConfigPersistence

object LibraryKnowledgeModule {
    fun create(application: InfinityApplication): LibraryKnowledgeSource {
        val settings = application.getSharedPreferences("infinity_settings", Context.MODE_PRIVATE)
        val ragSettings = RagConfigPersistence(application.getSharedPreferences("rag_settings", Context.MODE_PRIVATE))
        val indexer = LibraryDocumentIndexer(
            queue = application::awaitVectorizationQueue,
            taskDao = application.database.vectorizationTaskDao(),
            fileDao = application.database.fileEntryDao(),
            sessionGate = application.sessionExecutionGate,
            graphEnabled = { ragSettings.loadFullConfig().enableKnowledgeGraph },
        )
        val textReader = LibraryTextReader(
            fileEntryDao = application.database.fileEntryDao(),
            ensureRoot = { application.globalKnowledgeWorkspaceProvisioner.ensureRoot() },
        )
        val extractedReader = LibraryExtractedDocumentReader(
            fileDao = application.database.fileEntryDao(),
            ensureRoot = { application.globalKnowledgeWorkspaceProvisioner.ensureRoot() },
            cacheDirectory = application.cacheDir,
        )
        return DefaultLibraryKnowledgeSource(
            database = application.database,
            ensureRoot = { application.globalKnowledgeWorkspaceProvisioner.ensureRoot() },
            queueState = application.vectorizationQueueState,
            readText = textReader::read,
            retryIndex = { root, entry ->
                val task = application.database.vectorizationTaskDao().getByDocId(entry.uuid)
                    .filter { it.workspaceRootUuid == root && it.type in setOf("document", VectorizationQueue.TYPE_DOCUMENT_REFERENCE) }
                    .maxWithOrNull(compareBy<com.promenar.infinity.data.local.db.entity.VectorizationTaskEntity> { it.targetEpoch }
                        .thenBy { it.updatedAt }.thenBy { it.createdAt })
                when {
                    task == null -> false
                    task.type == VectorizationQueue.TYPE_DOCUMENT_REFERENCE && task.status == "pending" -> indexer.resumePending(root, entry, task)
                    task.status !in setOf("failed", "partial", "interrupted") -> false
                    task.error == VectorizationQueue.KG_CONFIRMATION_REQUIRED -> throw LibraryKgConfirmationRequired()
                    task.type == VectorizationQueue.TYPE_DOCUMENT_REFERENCE -> indexer.retryReference(root, entry.uuid)
                    else -> indexer.retryLegacy(root, entry)
                }
            },
            rebuildIndex = { root, entry -> indexer.enqueue(root, entry) },
            preferences = settings,
            sessionGate = application.sessionExecutionGate,
            libraryLabel = { application.getString(R.string.library_knowledge_root) },
            failedReason = { application.getString(R.string.library_knowledge_index_failed) },
            actionFailedReason = { application.getString(R.string.library_knowledge_action_failed) },
            confirmationReason = { application.getString(R.string.library_knowledge_confirm_graph) },
            memoryBusyReason = { application.getString(R.string.library_knowledge_fragments_busy) },
            extractDocument = extractedReader::read,
        )
    }
}
