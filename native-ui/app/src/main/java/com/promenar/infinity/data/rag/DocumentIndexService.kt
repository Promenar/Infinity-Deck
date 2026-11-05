package com.promenar.infinity.data.rag

import androidx.room.withTransaction
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.KgEdgeEntity
import com.promenar.infinity.data.local.db.entity.KgNodeEntity
import com.promenar.infinity.data.local.db.entity.VectorEntity
import com.promenar.infinity.data.local.db.dao.FileEntryDao
import com.promenar.infinity.data.session.SessionExecutionGate
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.promenar.infinity.utils.InfinityLogger

private val noGraphCommit: suspend () -> Unit = {}
private val noIndexValidation: () -> Unit = {}

interface DocumentIndexService {
    suspend fun rebuild(event: FileIndexEvent): DocumentIndexResult
    suspend fun delete(workspaceRootUuid: String, fileUuid: String): DocumentIndexResult
    suspend fun clearEmbeddingCheckpoints(workspaceRootUuid: String, fileUuid: String) = Unit
    suspend fun prepareLegacyRetry(
        workspaceRootUuid: String,
        fileUuid: String,
        legacyTaskId: String,
    ): Boolean = false
}

sealed interface DocumentIndexResult {
    data class Rebuilt(
        val fileUuid: String, val contentTruncated: Boolean = false,
        val kgProgress: KgExtractionProgress? = null,
        val kgFailure: KgExtractionStatus? = null,
    ) : DocumentIndexResult
    data class Deleted(val fileUuid: String) : DocumentIndexResult
    data class HashChanged(val currentHash: String?) : DocumentIndexResult
    data class Failed(val failure: Throwable) : DocumentIndexResult
}

data class DocumentIndexCandidate(
    val vectors: List<VectorEntity>,
    val nodes: List<KgNodeEntity> = emptyList(),
    val edges: List<KgEdgeEntity> = emptyList(),
    val graphExtracted: Boolean = false,
    val contentTruncated: Boolean = false,
    val kgProgress: KgExtractionProgress? = null,
    val kgFailure: KgExtractionStatus? = null,
    val preserveExistingGraph: Boolean = false,
    val onGraphCommitted: suspend () -> Unit = noGraphCommit,
    val onEmbeddingCommitted: suspend () -> Unit = noGraphCommit,
    val validateBeforeCommit: () -> Unit = noIndexValidation,
)

fun interface DocumentIndexCandidateBuilder {
    suspend fun build(event: FileIndexEvent.Changed): DocumentIndexCandidate
    suspend fun clearEmbeddingCheckpoints(workspaceRootUuid: String, fileUuid: String) = Unit
}

fun interface KnowledgeGraphCandidateBuilder {
    suspend fun build(text: String, fileUuid: String, contentHash: String): ExtractionResult

    suspend fun build(text: String, fileUuid: String, contentHash: String, onProgress: suspend (KgExtractionProgress) -> Unit): ExtractionResult =
        build(text, fileUuid, contentHash)

    suspend fun build(text: String, fileUuid: String, contentHash: String, runId: String?, onProgress: suspend (KgExtractionProgress) -> Unit): ExtractionResult =
        build(text, fileUuid, contentHash, onProgress)

    fun estimate(text: String, config: RagConfiguration): KgExtractionEstimate =
        KgExtractionBudget.from(config).estimate(text, config.kgWindowChars, config.kgGleaningPasses,
            systemPrompt = config.kgExtractionPrompt?.takeIf { it.isNotBlank() } ?: GraphExtractor.DEFAULT_KG_PROMPT)

    suspend fun onCommitted(text: String, fileUuid: String, contentHash: String) = Unit
    suspend fun onCommitted(text: String, fileUuid: String, contentHash: String, runId: String?) = onCommitted(text, fileUuid, contentHash)
}

class GraphExtractorKnowledgeGraphCandidateBuilder(
    private val graphExtractor: GraphExtractor,
) : KnowledgeGraphCandidateBuilder {
    override suspend fun build(text: String, fileUuid: String, contentHash: String): ExtractionResult =
        graphExtractor.extractCandidate(text, fileUuid, contentHash, retainCompletedCheckpoints = true)

    override suspend fun build(text: String, fileUuid: String, contentHash: String, onProgress: suspend (KgExtractionProgress) -> Unit): ExtractionResult =
        graphExtractor.extractCandidate(text, fileUuid, contentHash, onProgress, retainCompletedCheckpoints = true)

    override suspend fun build(text: String, fileUuid: String, contentHash: String, runId: String?, onProgress: suspend (KgExtractionProgress) -> Unit): ExtractionResult =
        graphExtractor.extractCandidate(text, fileUuid, contentHash, onProgress, retainCompletedCheckpoints = true,
            runId = runId ?: UUID.randomUUID().toString())

    override fun estimate(text: String, config: RagConfiguration) = graphExtractor.estimate(text)

    override suspend fun onCommitted(text: String, fileUuid: String, contentHash: String) {
        graphExtractor.clearCommittedCheckpoints(text, fileUuid, contentHash)
    }

    override suspend fun onCommitted(text: String, fileUuid: String, contentHash: String, runId: String?) {
        graphExtractor.clearCommittedCheckpoints(text, fileUuid, contentHash, runId)
    }
}

class WorkspaceDocumentIndexCandidateBuilder(
    private val fileEntryDao: FileEntryDao,
    private val embeddingClient: EmbeddingClient,
    private val ragConfig: RagConfiguration,
    private val now: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val graphCandidateBuilder: KnowledgeGraphCandidateBuilder? = null,
    private val checkpointRoot: File? = null,
) : DocumentIndexCandidateBuilder {
    private fun checkpoints() = EmbeddingCheckpoints(checkpointRoot ?: File(
        com.promenar.infinity.InfinityApplication.instance.cacheDir, "embedding_checkpoint",
    ))

    override suspend fun clearEmbeddingCheckpoints(workspaceRootUuid: String, fileUuid: String) = withContext(dispatcher) {
        checkpoints().clear(workspaceRootUuid, fileUuid)
    }

    override suspend fun build(event: FileIndexEvent.Changed): DocumentIndexCandidate = withContext(dispatcher) {
        val entry = fileEntryDao.getActiveByUuid(event.workspaceRootUuid, event.fileUuid)
            ?: throw java.io.FileNotFoundException("索引文件不存在")
        require(entry.hash == event.contentHash) { "索引事件 hash 已过期" }
        require(entry.updatedAt == event.targetEpoch) { "索引事件 epoch 已过期" }
        val file = File(entry.physicalRootPath, entry.materializedPath.trimStart('/')).canonicalFile
        val before = file.sha256()
        require(before == event.contentHash) { "索引前文件内容与数据库 hash 不一致" }
        val extraction = DocumentReferenceExtractor(
            chunkSize = ragConfig.docChunkSize,
            chunkOverlap = ragConfig.chunkOverlap,
        ).extract(entry)
        var embeddingIdentity: EmbeddingCheckpointIdentity? = null
        var validateEmbeddingModel: () -> Unit = noIndexValidation
        val embedding = if (event.skipVectorization || extraction.chunks.isEmpty()) emptyList()
        else {
            val client = embeddingClient.frozenForIndex()
            val modelKey = client.vectorSpaceKey() ?: throw IllegalStateException(client.diagnosticMessage())
            val generation = client.localModelGeneration()
            val identity = EmbeddingCheckpointIdentity(event.contentHash, modelKey, ragConfig.docChunkSize, ragConfig.chunkOverlap)
            embeddingIdentity = identity
            fun checkModel() {
                check(embeddingClient.vectorSpaceKey() == modelKey && embeddingClient.localModelGeneration() == generation) {
                    "向量模型在索引期间发生变化"
                }
            }
            validateEmbeddingModel = ::checkModel
            checkModel()
            CheckpointedEmbeddingRunner(checkpoints()) { batch ->
                checkModel()
                val result = client.embedDocuments(batch).embeddings
                checkModel()
                result
            }.embed(event.workspaceRootUuid, event.fileUuid, identity, extraction.chunks, event.onEmbeddingProgress)
                .also { checkModel() }
        }
        require(event.skipVectorization || embedding.size == extraction.chunks.size) { "Embedding 数量与切块不一致" }
        val shouldExtractGraph = event.kgStrategy != null ||
            (event.useConfiguredKgStrategy && ragConfig.enableKnowledgeGraph)
        val graph = if (shouldExtractGraph) try {
            event.onGraphStart()
            val builder = requireNotNull(graphCandidateBuilder) { "启用知识图谱后必须配置候选构建器" }
            val estimate = builder.estimate(extraction.graphText, ragConfig)
            val result = if (estimate.requiresConfirmation && event.kgStrategy != KgExtractionBudget.CONFIRMED_STRATEGY) {
                ExtractionResult(emptyList(), emptyList(), status = KgExtractionStatus.PARTIAL,
                    progress = KgExtractionProgress(0, estimate.windows, KgExtractionStopReason.CONFIRMATION_REQUIRED))
            } else builder.build(extraction.graphText, event.fileUuid, event.contentHash, event.kgRunId, event.onKgProgress)
            result.progress?.let { event.onKgProgress(it) }
            if (!result.isSuccess || result.status == KgExtractionStatus.PARTIAL || result.progress?.isPartial == true) {
                result.copy(nodes = emptyList(), edges = emptyList())
            } else ValidatedGraph.from(result.nodes, result.edges).let { result.copy(nodes = it.nodes, edges = it.edges) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ExtractionResult.failure(KgExtractionStatus.PROVIDER_ERROR, "知识图谱抽取失败")
        } else null
        require(file.sha256() == event.contentHash) { "候选构建期间文件内容已变化" }
        val timestamp = now()
        val graphComplete = graph != null && graph.isSuccess && graph.status == KgExtractionStatus.SUCCESS && graph.progress?.isPartial != true
        val graphFailure = graph?.takeUnless { it.isSuccess }?.status
            ?: KgExtractionStatus.PROVIDER_ERROR.takeIf { graph != null && !graphComplete && graph.progress?.reason == null }
        val validated = graph?.let { ValidatedGraph.from(it.nodes, it.edges) }
        val nodeIds = validated?.nodes.orEmpty().associate { ValidatedGraph.identity(it.name, it.type) to UUID.randomUUID().toString() }
        DocumentIndexCandidate(
            graphExtracted = graphComplete,
            kgProgress = graph?.progress,
            kgFailure = graphFailure,
            preserveExistingGraph = graph != null && !graphComplete,
            onGraphCommitted = {
                if (graphComplete) {
                    graphCandidateBuilder?.onCommitted(extraction.graphText, event.fileUuid, event.contentHash, event.kgRunId)
                }
            },
            onEmbeddingCommitted = {
                embeddingIdentity?.let { checkpoints().clear(event.workspaceRootUuid, event.fileUuid, it) }
            },
            validateBeforeCommit = validateEmbeddingModel,
            contentTruncated = extraction.truncated,
            vectors = if (event.skipVectorization) emptyList() else extraction.chunks.mapIndexed { index, content ->
                VectorEntity(
                    id = UUID.randomUUID().toString(),
                    docId = event.fileUuid,
                    content = content,
                    embedding = embedding[index].toBlob(),
                    metadata = documentVectorMetadata(event.fileUuid, index, entry.name, event.contentHash),
                    startMessageId = event.fileUuid,
                    endMessageId = event.fileUuid,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                    stale = false,
                    fileUuid = event.fileUuid,
                )
            },
            nodes = validated?.nodes.orEmpty().mapNotNull { node ->
                nodeIds[ValidatedGraph.identity(node.name, node.type)]?.let { id ->
                    KgNodeEntity(
                        id = id,
                        name = node.name,
                        type = node.type,
                        metadata = node.metadata,
                        createdAt = timestamp,
                        updatedAt = timestamp,
                    )
                }
            },
            edges = validated?.edges.orEmpty().mapNotNull { edge ->
                val source = validated?.resolveEndpoint(edge.source, edge.sourceType)
                val target = validated?.resolveEndpoint(edge.target, edge.targetType)
                val sourceId = source?.let { nodeIds[ValidatedGraph.identity(it.name, it.type)] }
                val targetId = target?.let { nodeIds[ValidatedGraph.identity(it.name, it.type)] }
                if (sourceId == null || targetId == null) null else KgEdgeEntity(
                    id = UUID.randomUUID().toString(),
                    sourceId = sourceId,
                    targetId = targetId,
                    relation = edge.relation,
                    weight = edge.weight,
                    createdAt = timestamp,
                )
            },
        )
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun FloatArray.toBlob(): ByteArray = ByteBuffer.allocate(size * Float.SIZE_BYTES)
        .also { it.asFloatBuffer().put(this) }
        .array()
}

class RoomDocumentIndexService(
    private val database: InfinityDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    private val executionGate: SessionExecutionGate? = null,
    private val onDeleted: (String) -> Unit = {},
    private val candidateBuilder: DocumentIndexCandidateBuilder,
) : DocumentIndexService {
    private val artifacts = RoomDocumentArtifacts(database)
    override suspend fun clearEmbeddingCheckpoints(workspaceRootUuid: String, fileUuid: String) =
        candidateBuilder.clearEmbeddingCheckpoints(workspaceRootUuid, fileUuid)
    override suspend fun rebuild(event: FileIndexEvent): DocumentIndexResult {
        if (event is FileIndexEvent.Deleted) return delete(event.workspaceRootUuid, event.fileUuid)
        event as FileIndexEvent.Changed
        val activeTaskId = event.activeTaskId
            ?: return DocumentIndexResult.Failed(
                IllegalArgumentException("索引提交缺少活动任务标识"),
            )
        val candidate = try {
            candidateBuilder.build(event)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return DocumentIndexResult.Failed(failure)
        }
        return try {
            event.onSaving()
            val result = withWorkspaceAdmission(event.workspaceRootUuid) {
                database.withTransaction {
                val current = database.fileEntryDao().getActiveByUuid(event.workspaceRootUuid, event.fileUuid)
                if (current?.hash != event.contentHash ||
                    current.updatedAt != event.targetEpoch
                ) {
                    return@withTransaction DocumentIndexResult.HashChanged(current?.hash)
                }
                val activeTask = database.vectorizationTaskDao().getByWorkspaceFile(
                    event.workspaceRootUuid,
                    event.fileUuid,
                    DOCUMENT_REFERENCE_TYPE,
                )
                if (activeTask?.id != activeTaskId ||
                    activeTask.targetContentHash != event.contentHash ||
                    activeTask.targetEpoch != event.targetEpoch
                ) {
                    return@withTransaction DocumentIndexResult.HashChanged(current.hash)
                }
                candidate.validateBeforeCommit()
                artifacts.clear(event.workspaceRootUuid, event.fileUuid, deleteTags = false,
                    clearVectors = !event.skipVectorization, clearGraph = !candidate.preserveExistingGraph)
                if (candidate.vectors.isNotEmpty()) database.vectorDao().insertAll(candidate.vectors)
                GraphStore(database).commitCandidate(candidate, KgSource(
                    type = "document", docId = event.fileUuid, contentHash = event.contentHash,
                    taskId = activeTaskId, workspaceDocument = true,
                ))
                val timestamp = maxOf(now(), event.targetEpoch)
                database.fileEntryDao().update(current.copy(
                    vectorizedAt = if (event.skipVectorization) current.vectorizedAt else timestamp,
                    kgExtractedAt = when {
                        candidate.preserveExistingGraph -> current.kgExtractedAt
                        candidate.kgProgress?.isPartial == true -> null
                        else -> timestamp.takeIf { candidate.graphExtracted || event.kgStrategy != null }
                    },
                    kgStrategy = when {
                        candidate.preserveExistingGraph -> current.kgStrategy
                        candidate.kgProgress?.isPartial == true -> "partial:${candidate.kgProgress.processedChunks}:${candidate.kgProgress.totalChunks}:${event.targetEpoch}"
                        candidate.graphExtracted -> "full"
                        else -> current.kgStrategy
                    },
                ))
                val kgProgress = candidate.kgProgress
                val partial = candidate.contentTruncated || kgProgress?.isPartial == true || candidate.kgFailure != null
                // 图谱、文档标记与任务终态一起提交，提交后的进程死亡不会重新消费整份预算。
                check(database.vectorizationTaskDao().update(activeTask.copy(
                    status = if (partial) "partial" else "completed",
                    totalChunks = kgProgress?.totalChunks ?: activeTask.totalChunks,
                    lastChunkIndex = kgProgress?.processedChunks ?: activeTask.lastChunkIndex,
                    progress = if (kgProgress?.isPartial == true && kgProgress.totalChunks > 0) {
                        100.0 * kgProgress.processedChunks / kgProgress.totalChunks
                    } else if (kgProgress?.isPartial == true) 0.0 else if (candidate.kgFailure != null) activeTask.progress else 100.0,
                    error = candidate.kgFailure?.let { VectorizationQueue.graphFailureCode(it) } ?: when (kgProgress?.reason) {
                        KgExtractionStopReason.CONFIRMATION_REQUIRED -> VectorizationQueue.KG_CONFIRMATION_REQUIRED
                        KgExtractionStopReason.CALLS -> "kg.budget.calls"
                        KgExtractionStopReason.OUTPUT_TOKENS -> "kg.budget.output_tokens"
                        KgExtractionStopReason.DURATION -> "kg.budget.duration"
                        else -> null
                    },
                    subStatus = if (candidate.kgFailure != null) "知识图谱抽取失败，可单独重试" else kgProgress?.description ?: "处理完成",
                    contentTruncated = candidate.contentTruncated,
                    updatedAt = timestamp,
                )) == 1) { "索引任务终态提交失败" }
                DocumentIndexResult.Rebuilt(event.fileUuid, candidate.contentTruncated, candidate.kgProgress, candidate.kgFailure)
                }
            }
            if (result is DocumentIndexResult.Rebuilt) {
                withContext(NonCancellable + Dispatchers.IO) {
                    try { candidate.onEmbeddingCommitted() } catch (_: Exception) { InfinityLogger.log("[向量] 已提交断点清理失败") }
                    try { candidate.onGraphCommitted() } catch (_: Exception) { InfinityLogger.log("[KG] 已提交断点清理失败") }
                }
            }
            result
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            DocumentIndexResult.Failed(failure)
        }
    }

    override suspend fun delete(workspaceRootUuid: String, fileUuid: String): DocumentIndexResult = try {
        withWorkspaceAdmission(workspaceRootUuid) {
            val result = database.withTransaction {
            artifacts.clear(workspaceRootUuid, fileUuid, deleteTags = true)
            database.vectorizationTaskDao().deleteByWorkspaceFile(workspaceRootUuid, fileUuid)
            DocumentIndexResult.Deleted(fileUuid)
            }
            onDeleted(fileUuid)
            withContext(NonCancellable + Dispatchers.IO) {
                try { clearEmbeddingCheckpoints(workspaceRootUuid, fileUuid) }
                catch (_: Exception) { InfinityLogger.log("[向量] 删除后的断点清理失败") }
            }
            result
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        DocumentIndexResult.Failed(failure)
    }

    override suspend fun prepareLegacyRetry(
        workspaceRootUuid: String,
        fileUuid: String,
        legacyTaskId: String,
    ): Boolean = try {
        withWorkspaceAdmission(workspaceRootUuid) {
            database.withTransaction {
            val task = database.vectorizationTaskDao().getById(legacyTaskId)
                ?: return@withTransaction false
            if (task.type != "document" || task.workspaceRootUuid != workspaceRootUuid || task.docId != fileUuid) {
                return@withTransaction false
            }
            artifacts.clear(workspaceRootUuid, fileUuid, deleteTags = false)
            database.vectorizationTaskDao().update(
                task.copy(
                    status = "interrupted",
                    error = null,
                    subStatus = "删除回滚，等待安全重建",
                    updatedAt = now(),
                ),
            ) == 1
            }
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    }

    private suspend fun <T> withWorkspaceAdmission(
        workspaceRootUuid: String,
        block: suspend () -> T,
    ): T {
        val gate = executionGate
        return if (gate == null) block() else gate.withWorkspaceAdmission(workspaceRootUuid, block)
    }

}

private const val DOCUMENT_REFERENCE_TYPE = "document_reference"

class RoomDocumentArtifacts(
    private val database: InfinityDatabase,
) {
    suspend fun clear(workspaceRootUuid: String, fileUuid: String, deleteTags: Boolean, clearVectors: Boolean = true, clearGraph: Boolean = true) {
        if (clearVectors) database.vectorDao().deleteByDocId(fileUuid)
        if (clearGraph) database.kgEvidenceDao().clearDocument(fileUuid)
        if (deleteTags) database.documentTagDao().deleteByDocId(fileUuid)
    }
}
