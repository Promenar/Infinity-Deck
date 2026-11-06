package com.promenar.infinity.data.library

import android.content.SharedPreferences
import androidx.room.withTransaction
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.VectorizationTaskEntity
import com.promenar.infinity.data.rag.VectorizationQueue
import com.promenar.infinity.data.rag.ConversationMemoryWriteCoordinator
import com.promenar.infinity.data.rag.VectorizationTask
import com.promenar.infinity.data.rag.documentContentHashFromVectorMetadata
import com.promenar.infinity.domain.files.FileRef
import com.promenar.infinity.domain.library.ConversationFragment
import com.promenar.infinity.domain.library.DocumentBody
import com.promenar.infinity.domain.library.DocumentInsight
import com.promenar.infinity.domain.library.DocumentGraphState
import com.promenar.infinity.domain.library.GraphEstimate
import com.promenar.infinity.domain.library.GraphStopReason
import com.promenar.infinity.domain.library.FragmentGroup
import com.promenar.infinity.domain.library.LibraryActionResult
import com.promenar.infinity.domain.library.LibraryDocumentSummary
import com.promenar.infinity.domain.library.LibraryKnowledgeSource
import com.promenar.infinity.domain.library.LibraryOverview
import com.promenar.infinity.domain.library.PendingDocument
import com.promenar.infinity.domain.library.PendingKind
import com.promenar.infinity.domain.library.PendingStage
import com.promenar.infinity.domain.workspace.WorkspaceInternalPaths
import com.promenar.infinity.data.repository.WorkspaceTextContentPolicy
import com.promenar.infinity.data.session.SessionExecutionGate
import com.promenar.infinity.startup.StartupSubsystem
import com.promenar.infinity.startup.StartupWriteBarrier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class LibraryChunkRow(val id: String, val content: String, val metadata: String?, val createdAt: Long)

data class LibraryExtractedText(val text: String, val truncated: Boolean)

class LibraryFragmentsBusyException(message: String) : IllegalStateException(message)

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultLibraryKnowledgeSource internal constructor(
    private val database: InfinityDatabase,
    private val ensureRoot: suspend () -> FileEntry,
    private val queueState: StateFlow<VectorizationQueue.QueueState>,
    private val readText: suspend (String, String, Int) -> LibraryExtractedText,
    private val retryIndex: suspend (String, FileEntry) -> Boolean,
    private val rebuildIndex: suspend (String, FileEntry) -> Unit,
    private val preferences: SharedPreferences,
    private val libraryLabel: () -> String,
    private val failedReason: () -> String,
    private val actionFailedReason: () -> String,
    private val confirmationReason: () -> String,
    private val memoryBusyReason: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
    private val sessionGate: SessionExecutionGate,
    private val extractDocument: suspend (FileEntry, Int) -> LibraryExtractedText? = { _, _ -> null },
    private val estimateGraph: suspend (FileEntry) -> GraphEstimate? = { null },
    private val enqueueGraph: suspend (String, FileEntry, Boolean) -> Boolean = { _, _, _ -> false },
    private val graphReason: (String?) -> String = { failedReason() },
    /** 索引失败的格式错误码换成说明文字；未知错误返回 null，沿用通用失败文案。 */
    private val formatReason: (String?) -> String? = { null },
) : LibraryKnowledgeSource {
    init {
        synchronized(preferences) {
            if (!preferences.contains(LAST_SEEN_KEY)) {
                preferences.edit().putLong(LAST_SEEN_KEY, now()).apply()
            }
        }
    }

    private fun rootId(): Flow<String> = flow { emit(ensureRoot().uuid) }

    private fun files(): Flow<List<FileEntry>> = rootId().flatMapLatest { root ->
        database.fileEntryDao().observeLibraryFiles(root)
    }

    override fun observeOverview(): Flow<LibraryOverview> {
        val graph = combine(database.kgNodeDao().observeLibraryCount(), database.kgEdgeDao().observeLibraryCount()) { nodes, edges ->
            nodes to edges
        }
        val indexed = rootId().flatMapLatest { root -> database.fileEntryDao().observeLibraryIndexedCount(root) }
        return combine(indexed, database.longTermMemoryDao().observeLibraryCount(), observeNewMemoryIds(), graph,
            database.vectorDao().observeLibraryMemoryCount()) { documents, memories, fresh, counts, fragments ->
            LibraryOverview(documents, memories, fresh.size,
                counts.first, counts.second, fragments)
        }.distinctUntilChanged()
    }

    override fun observePending(): Flow<List<PendingDocument>> = rootId().flatMapLatest { root ->
        combine(database.fileEntryDao().observeLibraryFiles(root),
            database.vectorizationTaskDao().observeLibraryTasks(root), queueState) { entries, stored, queue ->
            pendingDocuments(root, entries, stored, queue, libraryLabel(), failedReason(), confirmationReason(), graphReason, formatReason)
        }.distinctUntilChanged()
    }

    override fun observeRecent(limit: Int): Flow<List<LibraryDocumentSummary>> = files().map { entries ->
        entries.take(limit.coerceAtLeast(0)).map { it.toSummary(libraryLabel()) }
    }.distinctUntilChanged()

    override suspend fun retry(documentId: String): LibraryActionResult = withContext(Dispatchers.IO) {
        try {
            val root = ensureRoot().uuid
            if (latestTask(root, documentId)?.error == VectorizationQueue.KG_CONFIRMATION_REQUIRED) {
                LibraryActionResult.Failed(confirmationReason())
            } else action(documentId) { workspace, entry -> retryIndex(workspace, entry) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LibraryActionResult.Failed(actionFailedReason())
        }
    }

    override suspend fun rebuild(documentId: String): LibraryActionResult = action(documentId) { root, entry ->
        rebuildIndex(root, entry)
        true
    }

    private suspend fun action(documentId: String, block: suspend (String, FileEntry) -> Boolean): LibraryActionResult =
        withContext(Dispatchers.IO) {
            try {
                val root = ensureRoot().uuid
                val entry = database.fileEntryDao().getActiveByUuid(root, documentId)?.takeUnless { it.isDirectory }
                    ?: return@withContext LibraryActionResult.Failed(actionFailedReason())
                if (block(root, entry)) LibraryActionResult.Done else LibraryActionResult.Failed(actionFailedReason())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                LibraryActionResult.Failed(actionFailedReason())
            }
        }

    override suspend fun graphEstimate(documentId: String): GraphEstimate? = withContext(Dispatchers.IO) {
        try {
            val root = ensureRoot().uuid
            val entry = database.fileEntryDao().getActiveByUuid(root, documentId)?.takeUnless { it.isDirectory }
                ?: return@withContext null
            if (entry.vectorizedAt == null) return@withContext null
            estimateGraph(entry)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun extractGraph(documentId: String, confirmed: Boolean): LibraryActionResult = withContext(Dispatchers.IO) {
        try {
            val root = ensureRoot().uuid
            val entry = database.fileEntryDao().getActiveByUuid(root, documentId)?.takeUnless { it.isDirectory }
                ?: return@withContext LibraryActionResult.Failed(actionFailedReason())
            if (entry.vectorizedAt == null) {
                return@withContext LibraryActionResult.Failed(actionFailedReason())
            }
            val estimate = estimateGraph(entry) ?: return@withContext LibraryActionResult.Failed(actionFailedReason())
            if (estimate.requiresConfirmation && !confirmed) return@withContext LibraryActionResult.Failed(confirmationReason())
            if (enqueueGraph(root, entry, confirmed)) LibraryActionResult.Done else LibraryActionResult.Failed(actionFailedReason())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LibraryActionResult.Failed(actionFailedReason())
        }
    }

    private suspend fun latestTask(root: String, documentId: String): LibraryTask? {
        val stored = database.vectorizationTaskDao().getByDocId(documentId)
            .filter { it.workspaceRootUuid == root && it.type in setOf("document", VectorizationQueue.TYPE_DOCUMENT_REFERENCE) }
            .mapNotNull { it.libraryTask() }
        val live = queueState.value.queue.filter {
            it.workspaceRootUuid == root && it.docId == documentId && it.type in setOf("document", VectorizationQueue.TYPE_DOCUMENT_REFERENCE)
        }.mapNotNull { it.libraryTask() }
        return (stored + live).associateBy { it.id }.values.maxWithOrNull(
            compareBy<LibraryTask> { it.targetEpoch }.thenBy { it.updatedAt }.thenBy { it.createdAt },
        )
    }

    override suspend fun insight(documentId: String): DocumentInsight? = withContext(Dispatchers.IO) {
        val root = ensureRoot().uuid
        val entry = database.fileEntryDao().getActiveByUuid(root, documentId)?.takeUnless { it.isDirectory }
            ?: return@withContext null
        val summary = entry.toSummary(libraryLabel())
        val pending = observePending().first().firstOrNull { it.documentId == documentId }
        val chunks = database.vectorDao().getLibraryDocumentChunks(documentId)
        val entities = database.kgNodeDao().getLibraryDocumentEntityNames(documentId).distinct()
            .sortedWith(compareByDescending<String> { name -> chunks.sumOf { countMentions(it.content, name) } }
                .thenBy { it }).take(30)
        val task = latestTask(root, documentId)
        val completedGraph = graphStateFor(entry.kgExtractedAt, entry.updatedAt, task?.error)
        val graph = when {
            task?.status == "extracting" -> DocumentGraphState.Extracting(task.processed, task.total)
            completedGraph == com.promenar.infinity.domain.files.FileGraphState.CONFIRMATION_REQUIRED ->
                graphEstimate(documentId)?.let { DocumentGraphState.ConfirmationRequired(it) }
                    ?: DocumentGraphState.Failed(actionFailedReason())
            completedGraph == com.promenar.infinity.domain.files.FileGraphState.PARTIAL -> {
                val reason = when (task?.error) {
                    "kg.budget.calls" -> GraphStopReason.CALLS
                    "kg.budget.output_tokens" -> GraphStopReason.OUTPUT_TOKENS
                    else -> GraphStopReason.DURATION
                }
                DocumentGraphState.Partial(task?.processed ?: 0, task?.total ?: 0, reason)
            }
            completedGraph == com.promenar.infinity.domain.files.FileGraphState.FAILED -> DocumentGraphState.Failed(graphReason(task?.error))
            completedGraph == com.promenar.infinity.domain.files.FileGraphState.EXTRACTED -> DocumentGraphState.Complete(
                database.kgNodeDao().getByDocIds(listOf(documentId)).size,
                database.kgEdgeDao().getByDocIds(listOf(documentId)).size,
            )
            else -> DocumentGraphState.NotExtracted
        }
        DocumentInsight(
            documentId = entry.uuid, ref = summary.ref, name = entry.name, folderPath = summary.folderPath,
            mimeType = entry.mimeType, sizeBytes = entry.sizeBytes, lastModified = entry.updatedAt,
            pending = pending?.kind, failureReason = pending?.reason,
            chunkCount = chunks.size,
            entities = entities,
            source = null, versionCount = database.fileVersionDao().getByFile(root, documentId).size,
            graph = graph,
        )
    }

    override suspend fun body(documentId: String, maxChars: Int): DocumentBody? = withContext(Dispatchers.IO) {
        val root = ensureRoot().uuid
        val entry = database.fileEntryDao().getActiveByUuid(root, documentId)?.takeUnless { it.isDirectory }
            ?: return@withContext null
        val path = normalizedLibraryPath(entry.materializedPath) ?: return@withContext null
        if (path == "/" || WorkspaceInternalPaths.containsReservedPath(path)) return@withContext null
        val limit = maxChars.coerceAtLeast(0)
        val extension = entry.name.substringAfterLast('.', "").lowercase()
        val isExtracted = entry.mimeType in EXTRACTED_MIMES || extension in setOf("pdf", "doc", "docx")
        if (isExtracted) {
            val chunks = database.vectorDao().getLibraryDocumentChunks(documentId)
            val chunksMatch = (entry.vectorizedAt == null || entry.updatedAt <= entry.vectorizedAt) &&
                chunks.all { chunk -> documentContentHashFromVectorMetadata(chunk.metadata)?.let { it == entry.hash } ?: true }
            val extracted = if (chunks.isNotEmpty() && chunksMatch) {
                val task = database.vectorizationTaskDao().getByWorkspaceFile(root, documentId, VectorizationQueue.TYPE_DOCUMENT_REFERENCE)
                val indexingTruncated = task?.takeIf { it.targetContentHash == entry.hash && it.targetEpoch == entry.updatedAt }
                    ?.contentTruncated == true
                LibraryExtractedText(chunks.sortedWith(compareBy<LibraryChunkRow> { chunkIndex(it.metadata) ?: Int.MAX_VALUE }
                    .thenBy { it.createdAt }.thenBy { it.id }).joinToString("\n\n") { it.content }, indexingTruncated)
            } else extractDocument(entry, limit) ?: return@withContext null
            return@withContext toDocumentBody(extracted.text, limit, markdown = false, extracted = true,
                alreadyTruncated = extracted.truncated)
        }
        if (entry.mimeType?.startsWith("text/") != true && !WorkspaceTextContentPolicy.isTextExtension(extension)) {
            return@withContext null
        }
        val content = readText(root, documentId, limit)
        toDocumentBody(content.text, limit, markdown = extension in setOf("md", "markdown"), extracted = false,
            alreadyTruncated = content.truncated)
    }

    override suspend fun documentIdFor(ref: FileRef): String? = withContext(Dispatchers.IO) {
        if (ref.locationId != LOCATION_ID) return@withContext null
        val path = normalizedLibraryPath(ref.path) ?: return@withContext null
        database.fileEntryDao().getActiveByMaterializedPath(ensureRoot().uuid, path)?.takeUnless { it.isDirectory }?.uuid
    }

    override fun observeFragmentGroups(): Flow<List<FragmentGroup>> =
        database.vectorDao().observeLibraryFragmentGroups().distinctUntilChanged()

    override fun observeFragments(sessionId: String): Flow<List<ConversationFragment>> =
        database.vectorDao().observeLibraryFragments(sessionId).map { rows ->
            rows.sortedWith(compareBy<ConversationFragment> { it.createdAt }.thenBy { it.id })
        }.distinctUntilChanged()

    override suspend fun deleteFragment(id: String) = withContext(Dispatchers.IO) {
        val sessionId = database.vectorDao().getLibraryFragmentSession(id)
        withMemoryWrite(sessionId) {
            database.withTransaction {
                check(database.vectorDao().getLibraryFragmentSession(id) == sessionId) { "片段所属会话已变化" }
                database.vectorDao().deleteLibraryFragment(id)
            }
        }
        Unit
    }

    override suspend fun clearFragments(sessionId: String) = withContext(Dispatchers.IO) {
        withMemoryWrite(sessionId) { database.vectorDao().clearLibraryFragments(sessionId) }
        Unit
    }

    private suspend fun <T> withMemoryWrite(sessionId: String?, block: suspend () -> T): T {
        StartupWriteBarrier.requireWritable(sessionId = sessionId, subsystem = StartupSubsystem.VECTOR_QUEUE)
        return if (sessionId != null) sessionGate.withNewSessionAdmission(sessionId) {
            ConversationMemoryWriteCoordinator.serialized(sessionId) { activeWriters ->
                StartupWriteBarrier.requireWritable(sessionId = sessionId, subsystem = StartupSubsystem.VECTOR_QUEUE)
                val queued = database.vectorizationTaskDao().getBySessionId(sessionId).any {
                    it.type == "memory" && it.status !in setOf("completed", "failed")
                }
                if (activeWriters > 0 || queued) throw LibraryFragmentsBusyException(memoryBusyReason())
                block()
            }
        } else block()
    }

    override fun observeNewMemoryIds(): Flow<Set<String>> {
        val lastSeen = callbackFlow {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == LAST_SEEN_KEY) trySend(preferences.getLong(LAST_SEEN_KEY, 0L))
            }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            trySend(preferences.getLong(LAST_SEEN_KEY, 0L))
            awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
        }.distinctUntilChanged()
        val clock = flow { while (true) { emit(now()); delay(60_000L) } }
        return combine(database.longTermMemoryDao().observeLibraryMemoryTimes(), lastSeen, clock) { memories, seen, _ ->
            val current = now()
            memories.asSequence().filter { it.createdAt > seen && it.createdAt >= current - SEVEN_DAYS && it.createdAt <= current }
                .map { it.id }.toSet()
        }.distinctUntilChanged()
    }

    override suspend fun markMemoriesSeen() {
        preferences.edit().putLong(LAST_SEEN_KEY, now()).apply()
    }

    companion object {
        private const val LOCATION_ID = "kb"
        private const val LAST_SEEN_KEY = "library_memories_last_seen"
        private const val SEVEN_DAYS = 7L * 24 * 60 * 60 * 1000
        private val EXTRACTED_MIMES = setOf("application/pdf", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    }
}

internal fun normalizedLibraryPath(path: String): String? {
    if (!path.startsWith('/') || '\\' in path || '\u0000' in path) return null
    val parts = path.split('/').filter { it.isNotEmpty() }
    if (parts.any { it == "." || it == ".." }) return null
    return "/" + parts.joinToString("/")
}

private fun FileEntry.toSummary(rootLabel: String): LibraryDocumentSummary {
    val path = normalizedLibraryPath(materializedPath) ?: materializedPath
    val folder = path.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }
    return LibraryDocumentSummary(uuid, FileRef("kb", path), name,
        (listOf(rootLabel) + folder).joinToString(" › "), mimeType, sizeBytes, updatedAt)
}

private fun countMentions(text: String, name: String): Int {
    if (name.isEmpty()) return 0
    var count = 0
    var offset = 0
    while (offset < text.length) {
        val found = text.indexOf(name, startIndex = offset, ignoreCase = true)
        if (found < 0) break
        count++
        offset = found + name.length
    }
    return count
}

private fun chunkIndex(metadata: String?): Int? = try {
    metadata?.let { Json.parseToJsonElement(it).jsonObject["chunkIndex"]?.jsonPrimitive?.intOrNull }
} catch (_: Exception) { null }

internal fun toDocumentBody(text: String, maxChars: Int, markdown: Boolean, extracted: Boolean,
    alreadyTruncated: Boolean = false): DocumentBody {
    val limit = maxChars.coerceAtLeast(0)
    var end = minOf(text.length, limit)
    if (end > 0 && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
    return DocumentBody(text.substring(0, end), markdown, extracted, alreadyTruncated || end < text.length)
}

private data class LibraryTask(
    val id: String,
    val docId: String,
    val status: String,
    val error: String?,
    val processed: Int,
    val total: Int?,
    val targetEpoch: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

private fun VectorizationTaskEntity.libraryTask(): LibraryTask? = docId?.let {
    LibraryTask(id, it, status, error, lastChunkIndex, totalChunks, targetEpoch, createdAt, updatedAt)
}

private fun VectorizationTask.libraryTask(): LibraryTask? = docId?.let {
    LibraryTask(id, it, status, error, lastChunkIndex, totalChunks, targetEpoch, createdAt, updatedAt)
}

internal fun pendingDocuments(
    root: String,
    entries: List<FileEntry>,
    stored: List<VectorizationTaskEntity>,
    queue: VectorizationQueue.QueueState,
    label: String,
    failedReason: String,
    confirmationReason: String,
    graphReason: (String?) -> String = { failedReason },
    formatReason: (String?) -> String? = { null },
): List<PendingDocument> {
    val live = queue.queue.filter { it.workspaceRootUuid == root && it.type in setOf("document", VectorizationQueue.TYPE_DOCUMENT_REFERENCE) }
    val tasks = (stored.mapNotNull { it.libraryTask() } + live.mapNotNull { it.libraryTask() })
        .associateBy { it.id }.values.groupBy { it.docId }
        .mapValues { (_, values) -> values.maxWithOrNull(compareBy<LibraryTask> { it.targetEpoch }
            .thenBy { it.updatedAt }.thenBy { it.createdAt })!! }
    val pendingQueue = queue.queue.filter { it.status == "pending" && !(queue.isProcessing && it.id == queue.currentTask?.id) }
    val positions = pendingQueue.mapIndexed { index, task -> task.id to index + 1 }.toMap()
    val waitingStored = tasks.values.filter { it.status == "pending" && it.id !in positions }
        .sortedWith(compareBy<LibraryTask> { it.createdAt }.thenBy { it.id })
        .mapIndexed { index, task -> task.id to (pendingQueue.size + index + 1) }.toMap()
    return entries.mapNotNull { entry ->
        val task = tasks[entry.uuid]
        val kind = when {
            entry.vectorizedAt != null && task?.status in setOf("failed", "partial", "interrupted") &&
                (task?.error == VectorizationQueue.KG_CONFIRMATION_REQUIRED || VectorizationQueue.isGraphRetryError(task?.error)) -> PendingKind.GRAPH_ATTENTION
            task?.status in setOf("failed", "partial", "interrupted") -> PendingKind.FAILED
            task != null && queue.isProcessing && task.id == queue.currentTask?.id -> PendingKind.INDEXING
            task?.status == "pending" -> PendingKind.QUEUED
            task?.status in setOf("processing", "extracting_source", "chunking", "vectorizing", "saving", "extracting") -> PendingKind.INDEXING
            entry.vectorizedAt != null && entry.updatedAt > entry.vectorizedAt -> PendingKind.STALE
            else -> null
        } ?: return@mapNotNull null
        val summary = entry.toSummary(label)
        val stage = if (kind == PendingKind.INDEXING) when (task?.status) {
            "vectorizing" -> PendingStage.VECTORIZING
            "extracting" -> PendingStage.EXTRACTING_GRAPH
            "saving" -> PendingStage.SAVING
            else -> PendingStage.PREPARING
        } else null
        val total = task?.total?.takeUnless { stage == PendingStage.PREPARING || stage == PendingStage.SAVING }
        PendingDocument(entry.uuid, summary.ref, entry.name, summary.folderPath, kind,
            reason = when (kind) {
                PendingKind.GRAPH_ATTENTION -> if (task?.error == VectorizationQueue.KG_CONFIRMATION_REQUIRED) confirmationReason else graphReason(task?.error)
                PendingKind.FAILED -> if (task?.error == VectorizationQueue.KG_CONFIRMATION_REQUIRED) confirmationReason else formatReason(task?.error) ?: failedReason
                else -> null
            },
            queuePosition = if (kind == PendingKind.QUEUED) task?.let { positions[it.id] ?: waitingStored[it.id] } else null,
            stage = stage,
            processed = total?.let { task?.processed },
            total = total,
            lastError = task?.error?.takeUnless { it.startsWith("kg.") }?.let { formatReason(it) ?: it },
            graphConfirmation = kind == PendingKind.GRAPH_ATTENTION && task?.error == VectorizationQueue.KG_CONFIRMATION_REQUIRED,
        )
    }.sortedWith(compareBy<PendingDocument> { when (it.kind) {
        PendingKind.FAILED -> 0
        PendingKind.GRAPH_ATTENTION -> 0
        PendingKind.INDEXING -> 1
        PendingKind.QUEUED -> 2
        PendingKind.STALE -> 3
    } }.thenBy { it.queuePosition ?: Int.MAX_VALUE }.thenBy { it.name }.thenBy { it.documentId })
}
