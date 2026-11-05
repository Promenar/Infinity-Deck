package com.promenar.infinity.data.library.ask

import android.content.SharedPreferences
import androidx.sqlite.db.SimpleSQLiteQuery
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.memory.LongTermMemory
import com.promenar.infinity.data.memory.LongTermMemoryRepository
import com.promenar.infinity.data.rag.DocumentReach
import com.promenar.infinity.data.rag.EmbeddingClient
import com.promenar.infinity.data.rag.GraphRetrievalStore
import com.promenar.infinity.data.rag.KgRetrievalScope
import com.promenar.infinity.data.rag.KgCollectionRepository
import com.promenar.infinity.data.model.KgPath
import com.promenar.infinity.data.rag.MemoryManager
import com.promenar.infinity.data.rag.SearchResult
import com.promenar.infinity.data.rag.VectorStore
import com.promenar.infinity.data.rag.documentChunkIndexFromVectorMetadata
import com.promenar.infinity.domain.library.AskHit
import com.promenar.infinity.domain.library.AskHitKind
import com.promenar.infinity.domain.library.AskMatch
import com.promenar.infinity.domain.library.AskMode
import com.promenar.infinity.domain.library.AskScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import java.nio.ByteBuffer
import java.security.MessageDigest

internal class RoomLibraryAskBackend(
    private val database: InfinityDatabase,
    private val manager: ProviderManager,
    private val preferences: SharedPreferences,
    private val embedding: () -> EmbeddingClient,
    private val embeddingSlotChanges: Flow<Unit> = flowOf(Unit),
    private val memoryManager: () -> MemoryManager,
    private val memories: LongTermMemoryRepository,
    private val citations: RoomKnowledgeCitationRepository,
    private val answerStream: (String, AskPrompt) -> Flow<String>,
    private val libraryLocation: () -> String,
    private val workspaceLocation: (String) -> String,
    private val memoryLocation: () -> String,
    private val entityLocation: (Int) -> String,
    private val now: () -> Long = System::currentTimeMillis,
) : LibraryAskBackend {
    private val graph = GraphRetrievalStore(database)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun preferenceChanges(): Flow<Unit> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    override fun observeMode(): Flow<AskMode> =
        combine(combine(manager.summaryModelId, manager.embeddingModelId, manager.providerModels, manager.providers) { _, _, _, _ -> Unit },
            preferenceChanges(), embeddingSlotChanges, manager.configurationChanges.onStart { emit(Unit) }) { _, _, _, _ ->
            settings().mode
        }.distinctUntilChanged().flowOn(Dispatchers.IO)

    private fun embeddingModel(): String = embedding().vectorSpaceKey().orEmpty()

    override fun settings(): AskSettings {
        val quick = manager.summaryModelId.value
        val client = embedding()
        val available = embeddingModel().isNotBlank() && (client.isConfigured || client.hasLocalFallback)
        return AskSettings(determineAskMode(quick, available), quick)
    }

    override suspend fun keywords(query: String, scope: AskScope, limit: Int): List<AskHit> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val reach = reach(scope)
        val files = files(reach)
        val byId = files.associateBy { it.id }
        val terms = terms(query)
        val chunkHits = memoryManager().searchDocumentKeywords(query, reach, limit)
            .mapNotNull { row -> (row.fileUuid ?: row.docId)?.let(byId::get)?.let { row.toHit(it, terms, AskMatch.KEYWORD, null) } }
        val fileHits = files.filter { file -> terms.any { file.name.contains(it, ignoreCase = true) } }
            .map { file -> AskHit("file:${file.id}", AskHitKind.DOCUMENT, file.name, file.location,
                file.name, highlights(file.name, terms), AskMatch.KEYWORD, null, documentId = file.id) }
        val memoryHits = memories.getAllForOwner(null).take(200).filter { memory ->
            terms.any { memory.title.contains(it, true) || memory.content.contains(it, true) }
        }.map { it.toHit(terms, AskMatch.KEYWORD, null) }
        // 融合之前保留各文档的候选块，最终排序后再执行每篇两段的上限。
        return interleaveKeywordCandidates(chunkHits, fileHits.take(limit), memoryHits.take(limit))
    }

    override suspend fun retrieve(query: String, scope: AskScope, keywords: List<AskHit>): List<AskHit> {
        val currentClient = embedding()
        val client = try { currentClient.frozen() } catch (error: Exception) { throw AskEmbeddingException(error) }
        val model = client.vectorSpaceKey() ?: throw AskEmbeddingException(IllegalStateException("向量模型不可用"))
        val generation = client.localModelGeneration()
        fun modelUnchanged() = embeddingModel() == model && currentClient.localModelGeneration() == generation
        val queryVector = try {
            client.embedQuery(query).first
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw AskEmbeddingException(error)
        }
        if (!modelUnchanged()) throw AskEmbeddingException(IllegalStateException("向量模型已变化"))
        val reach = reach(scope)
        val files = files(reach).associateBy { it.id }
        val terms = terms(query)
        val docs = memoryManager().searchResolvedDocuments(query, queryVector, reach, 60)
        val semantic = docs.vector.mapNotNull { row ->
            (row.fileUuid ?: row.docId)?.let(files::get)?.let { row.toHit(it, terms, AskMatch.SEMANTIC, row.similarity.coerceIn(0f, 1f)) }
        }
        val memoryRows = memories.getAllForOwner(null).take(200)
        val semanticMemories = memoryVectors(memoryRows, model, client, ::modelUnchanged).mapNotNull { (memory, vector) ->
            val score = VectorStore.cosineSimilarity(queryVector, vector)
            if (score < memoryManager().ragConfig.docThreshold) null
            else memory.toHit(terms, AskMatch.SEMANTIC, score.coerceIn(0f, 1f))
        }.sortedByDescending { it.similarity }
        if (!modelUnchanged()) throw AskEmbeddingException(IllegalStateException("向量模型已变化"))
        val graphHits = try {
            graphPathsForAskScope(graph, KgCollectionRepository(database), reach, query,
                docs.fused.mapNotNull { it.fileUuid ?: it.docId })
                .flatMap { path -> path.nodes.map { node -> node to path.sourceDocumentIds } }
                .distinctBy { it.first.id }.take(3).map { (node, sourceIds) ->
                    AskHit("entity:${node.id}", AskHitKind.ENTITY, node.label, entityLocation(sourceIds.distinct().size),
                        node.label, highlights(node.label, terms), AskMatch.KEYWORD, null, entityId = node.id)
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        if (!modelUnchanged()) throw AskEmbeddingException(IllegalStateException("向量模型已变化"))
        val vectorHits = semantic + semanticMemories
        val keywordHits = keywords + graphHits
        return mergeAskHits(vectorHits, keywordHits) { vectorRows, keywordRows ->
            memoryManager().rrfFusion(vectorRows, keywordRows, memoryManager().ragConfig)
        }
    }

    override fun answer(modelId: String, prompt: AskPrompt): Flow<String> = answerStream(modelId, prompt)

    override fun modelLabel(modelId: String): String =
        manager.providerModels.value.firstOrNull { it.id == modelId }?.name ?: modelId

    override suspend fun recordAnswer(query: String, hits: List<AskHit>, indexes: List<Int>) {
        val timestamp = now()
        val rows = indexes.mapNotNull { index ->
            val hit = hits.getOrNull(index) ?: return@mapNotNull null
            val documentId = hit.documentId ?: return@mapNotNull null
            KnowledgeCitationEntity(citationId(), documentId, hit.chunkIndex,
                hit.key.takeUnless { it.startsWith("file:") }, "ASK", null, null, query,
                (index + 1).toString(), timestamp)
        }
        citations.recordAsk(rows)
    }

    private suspend fun memoryVectors(
        rows: List<LongTermMemory>, model: String, client: EmbeddingClient, modelUnchanged: () -> Boolean,
    ): List<Pair<LongTermMemory, FloatArray>> {
        return findMemoryVectors(rows, model, { if (modelUnchanged()) model else "" },
            load = { database.knowledgeCitationDao().embeddings(it) },
            save = {
                com.promenar.infinity.startup.StartupWriteBarrier.requireWritable()
                database.knowledgeCitationDao().saveEmbeddings(it)
            },
            embed = { client.embedDocuments(it).embeddings })
    }

    private suspend fun reach(scope: AskScope): DocumentReach = documentReach(scope,
        if (scope == AskScope.Library) database.sessionDao().getById("__infinity_rag_workspace__")?.workspaceRootUuid else null)

    private data class FileInfo(val id: String, val name: String, val path: String, val root: String, val location: String)

    private suspend fun files(reach: DocumentReach): List<FileInfo> {
        val libraryRoot = database.sessionDao().getById("__infinity_rag_workspace__")?.workspaceRootUuid
        val roots = (reach as? DocumentReach.Scoped)?.workspaceRootUuids.orEmpty()
        if (reach is DocumentReach.Scoped && roots.isEmpty()) return emptyList()
        val restriction = if (reach is DocumentReach.Scoped) " AND f.workspace_root_uuid IN (${roots.joinToString { "?" }})" else ""
        val sql = """SELECT f.uuid, f.name, f.materialized_path, f.workspace_root_uuid, root.name
            FROM workspace_files f LEFT JOIN workspace_files root ON root.uuid = f.workspace_root_uuid
            WHERE f.in_recycle_bin = 0 AND f.is_directory = 0
            AND EXISTS (SELECT 1 FROM vectors v WHERE v.stale = 0 AND (v.file_uuid = f.uuid OR (v.file_uuid IS NULL AND v.doc_id = f.uuid)))""" + restriction
        return database.query(SimpleSQLiteQuery(sql, roots.toTypedArray())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val root = cursor.getString(3)
                    val path = cursor.getString(2)
                    val location = if (root == libraryRoot) libraryLocation() else workspaceLocation(cursor.getString(4).orEmpty())
                    add(FileInfo(cursor.getString(0), cursor.getString(1), path, root, "$location · $path"))
                }
            }
        }
    }

    private fun SearchResult.toHit(file: FileInfo, terms: List<String>, match: AskMatch, similarity: Float?): AskHit {
        val excerpt = snippet(content, terms)
        return AskHit(id, AskHitKind.DOCUMENT, file.name, file.location, excerpt, highlights(excerpt, terms),
            match, similarity, documentId = file.id, chunkIndex = documentChunkIndexFromVectorMetadata(metadata),
            chunkContent = content)
    }

    private fun LongTermMemory.toHit(terms: List<String>, match: AskMatch, similarity: Float?): AskHit {
        val excerpt = snippet(content, terms)
        return AskHit("memory:$id", AskHitKind.MEMORY, title, memoryLocation(), excerpt,
            highlights(excerpt, terms), match, similarity, memoryId = id)
    }
}

/** 按问资料的文档范围选择图谱，图谱文档可独立于向量索引存在。 */
internal suspend fun collectionIdsForAskScope(
    graph: GraphRetrievalStore,
    collections: KgCollectionRepository,
    reach: DocumentReach,
): List<String> = collections.collectionIdsForDocuments(
    graph.visibleDocumentIds(KgRetrievalScope(sessionId = "", documents = reach, allowConversation = false)),
)

/** 每张命中图谱独立扩展，再按得分交给原有的结果融合与预算。 */
internal suspend fun graphPathsForAskScope(
    graph: GraphRetrievalStore,
    collections: KgCollectionRepository,
    reach: DocumentReach,
    query: String,
    ragDocumentIds: List<String>,
): List<KgPath> = collectionIdsForAskScope(graph, collections, reach).flatMap { collectionId ->
    graph.retrieve(query, KgRetrievalScope(sessionId = "", documents = reach, allowConversation = false, collectionId = collectionId),
        ragDocumentIds = ragDocumentIds).paths
}.sortedByDescending { it.score }

internal fun interleaveKeywordCandidates(vararg groups: List<AskHit>): List<AskHit> {
    val iterators = groups.map { it.iterator() }
    val seen = hashSetOf<String>()
    return buildList {
        while (iterators.any { it.hasNext() }) {
            iterators.forEach { iterator ->
                if (iterator.hasNext()) iterator.next().let { if (seen.add(it.key)) add(it) }
            }
        }
    }
}

internal fun determineAskMode(quickModel: String, embeddingAvailable: Boolean): AskMode = when {
    !embeddingAvailable -> AskMode.KEYWORD_ONLY
    quickModel.isBlank() -> AskMode.NO_QUICK_MODEL
    else -> AskMode.FULL
}

internal fun documentReach(scope: AskScope, libraryRoot: String?): DocumentReach = when (scope) {
    AskScope.AllIndexed -> DocumentReach.All
    AskScope.Library -> DocumentReach.Scoped(setOfNotNull(libraryRoot?.takeIf(String::isNotBlank)), emptySet())
    is AskScope.Workspace -> DocumentReach.Scoped(setOf(scope.workspaceRootUuid), emptySet())
}

internal suspend fun findMemoryVectors(
    rows: List<LongTermMemory>,
    model: String,
    currentModel: () -> String,
    load: suspend (List<String>) -> List<MemoryEmbeddingEntity>,
    save: suspend (List<MemoryEmbeddingEntity>) -> Unit,
    embed: suspend (List<String>) -> List<FloatArray>,
): List<Pair<LongTermMemory, FloatArray>> {
        if (rows.isEmpty()) return emptyList()
        val cached = try {
            load(rows.map { it.id }).associateBy { it.memoryId }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
        val ready = mutableMapOf<String, FloatArray>()
        val missing = rows.filter { memory ->
            val entry = cached[memory.id]
            val bytes = entry?.embedding
            if (entry != null && entry.modelId == model && entry.contentHash == memoryHash(memory) &&
                bytes != null && bytes.isNotEmpty() && bytes.size % 4 == 0
            ) {
                ready[memory.id] = ByteBuffer.wrap(bytes).asFloatBuffer().let { buffer ->
                    FloatArray(buffer.remaining()).also { buffer.get(it) }
                }
                false
            } else true
        }
        if (missing.isNotEmpty()) {
            try {
                val vectors = embed(missing.map { "${it.title}\n${it.content}" })
                check(vectors.size == missing.size && vectors.all { it.isNotEmpty() })
                check(model == currentModel())
                val entities = missing.zip(vectors).map { (memory, vector) ->
                    ready[memory.id] = vector
                    val buffer = ByteBuffer.allocate(vector.size * 4)
                    buffer.asFloatBuffer().put(vector)
                    MemoryEmbeddingEntity(memory.id, memoryHash(memory), model, buffer.array())
                }
                save(entities)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 本轮保留命中的缓存，失败的记忆仍可通过关键词召回。
            }
        }
        return rows.mapNotNull { row -> ready[row.id]?.let { row to it } }
}

internal fun mergeAskHits(
    vectorHits: List<AskHit>,
    keywordHits: List<AskHit>,
    fuse: (List<SearchResult>, List<SearchResult>) -> List<SearchResult>,
): List<AskHit> {
    val semanticById = vectorHits.associateBy { it.key }
    val keywordById = keywordHits.associateBy { it.key }
    fun row(hit: AskHit, score: Float) = SearchResult(
        id = hit.key, docId = hit.documentId, content = hit.snippet, createdAt = 0, similarity = score,
    )
    val ranked = fuse(vectorHits.map { row(it, it.similarity ?: 0f) }, keywordHits.map { row(it, 1f) })
        .mapNotNull { result ->
            val semantic = semanticById[result.id]
            val keyword = keywordById[result.id]
            (semantic ?: keyword)?.copy(
                match = if (semantic != null && keyword != null) AskMatch.BOTH
                    else if (semantic != null) AskMatch.SEMANTIC else AskMatch.KEYWORD,
                similarity = semantic?.similarity,
            )
        }
    return limitAskHits(ranked, 12)
}

internal fun memoryHash(memory: LongTermMemory): String =
    MessageDigest.getInstance("SHA-256").digest((memory.title + "\u0000" + memory.content).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun terms(query: String): List<String> = query.trim().split(Regex("\\s+")).filter(String::isNotBlank).distinct()
internal fun highlights(text: String, terms: List<String>): List<String> = terms
    .mapNotNull { term -> text.indexOf(term, ignoreCase = true).takeIf { it >= 0 }?.let { it to term } }
    .sortedBy { it.first }.map { it.second }.distinct()

internal fun snippet(text: String, terms: List<String>): String {
    val first = terms.mapNotNull { text.indexOf(it, ignoreCase = true).takeIf { at -> at >= 0 } }.minOrNull() ?: 0
    val start = (first - 60).coerceAtLeast(0)
    return text.substring(start, (start + 240).coerceAtMost(text.length))
}
