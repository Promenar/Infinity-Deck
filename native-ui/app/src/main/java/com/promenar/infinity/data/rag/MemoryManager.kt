package com.promenar.infinity.data.rag

import com.promenar.infinity.data.knowledge.KnowledgeCitationScope
import kotlinx.coroutines.currentCoroutineContext
import com.promenar.infinity.data.model.ConversationScope
import com.promenar.infinity.data.model.DocumentScope
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.data.model.RagReferenceKind
import com.promenar.infinity.data.model.RagRetrievalTrace
import com.promenar.infinity.data.local.db.entity.VectorEntity
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId

class MemoryManager(
    private val vectorStore: VectorStore,
    private val keywordSearcher: KeywordSearcher,
    private val graphStore: GraphStore,
    private val embeddingClient: EmbeddingClient,
    private val rerankClient: RerankClient? = null,
    private val queryRewriter: QueryRewriter? = null,
    val ragConfig: RagConfiguration = RagConfiguration(),  // 改为 public 以支持 ContextBuilder 读取全局 KG 开关
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val scopeResolver by lazy { RetrievalScopeResolver(graphStore.database) }

    data class RetrieveOptions(
        val enableMemory: Boolean = true,
        val enableDocs: Boolean = true,
        val activeDocIds: List<String> = emptyList(),
        /** 由范围解析展开为文件夹下的文件；文件夹本身绝不触发全库回退。 */
        val activeFolderIds: List<String> = emptyList(),
        val memoryScope: ConversationScope = ConversationScope.SESSION,
        val documentScope: DocumentScope = DocumentScope.WORKSPACE,
        val sessionId: String = "",
        val enableRerank: Boolean = false,  // 从 RagOptions 传递，结合 ragConfig.enableRerank 共同决定
        val configOverride: RagConfiguration? = null,
        /** 工具已提供检索语句时关闭改写，避免隐含模型请求。 */
        val allowQueryRewrite: Boolean = true,
        val ragOptions: RagOptions? = null,
    )

    data class RetrieveResult(
        val context: String,
        val references: List<RagReference>,
        val metadata: RetrieveMetadata,
        /** 检索过程记录；null 表示未执行检索。 */
        val trace: RagRetrievalTrace? = null,
        val resolvedScope: ResolvedRetrievalScope? = null,
    )

    data class RetrieveMetadata(
        val searchTimeMs: Long = 0,
        val rerankTimeMs: Long = 0,
        val recallCount: Int = 0,
        val finalCount: Int = 0,
        val sourceDistribution: SourceDistribution = SourceDistribution()
    )

    data class SourceDistribution(
        val memory: Int = 0,
        val documents: Int = 0
    )

    suspend fun retrieveContext(
        query: String,
        sessionId: String,
        options: RetrieveOptions = RetrieveOptions(),
        onProgress: ((stage: String, percentage: Int, subStage: String?) -> Unit)? = null
    ): RetrieveResult {
        val TAG = "MemoryManager"
        InfinityLogger.log("[$TAG] retrieveContext start: query='${query.take(60)}', session=$sessionId, " +
            "enableMemory=${options.enableMemory}, enableDocs=${options.enableDocs}, memoryScope=${options.memoryScope}, documentScope=${options.documentScope}, " +
            "enableRerank=${options.enableRerank}, activeDocIds=${options.activeDocIds.size}")

        val startTime = System.currentTimeMillis()
        val effectiveConfig = options.configOverride ?: ragConfig

        val canSearchMemory = options.enableMemory
        // Rerank 决策: 用户开关 (RagOptions.enableRerank) AND 配置门 (ragConfig.enableRerank) 两者都开才执行
        val canRerank = options.enableRerank && effectiveConfig.enableRerank && rerankClient != null
        InfinityLogger.log("[$TAG] canRerank=$canRerank (options.enableRerank=${options.enableRerank} && effectiveConfig.enableRerank=${effectiveConfig.enableRerank} && rerankClient=${if (rerankClient != null) "configured" else "null"})")

        val scope = scopeResolver.resolve(
            sessionId,
            options.ragOptions ?: RagOptions(
                activeDocIds = options.activeDocIds,
                activeFolderIds = options.activeFolderIds,
                memoryScope = options.memoryScope,
                documentScope = options.documentScope,
                selectedCollectionIds = if (options.documentScope == DocumentScope.WORKSPACE) emptyList() else null,
                globalMemoryEnabled = options.memoryScope != ConversationScope.SESSION,
                globalMemoryScope = options.memoryScope,
            ),
        )
        if (!canSearchMemory && !options.enableDocs) return emptyResult(0, RagRetrievalTrace.OUTCOME_NO_SCOPE, scope = scope)
        if (!embeddingClient.isConfigured && !embeddingClient.hasLocalFallback) {
            InfinityLogger.log("[$TAG] embedding unavailable, skip retrieval: ${embeddingClient.diagnosticMessage().lineSequence().firstOrNull().orEmpty()}")
            return emptyResult(System.currentTimeMillis() - startTime, RagRetrievalTrace.OUTCOME_UNAVAILABLE, scope = scope)
        }
        val documentReach = scope.documents
        // 覆盖范围计数只用于展示，失败时不影响检索本身。
        val memoryStats = if (!canSearchMemory) null else try {
            scopeResolver.stats(scope.memory)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            InfinityLogger.logError("[MemoryManager] 统计记忆覆盖范围失败", error)
            null
        }
        // 空的限定范围按零文档处理，禁止解释为“搜索全部文档”。
        val canSearchDocs = options.enableDocs && (documentReach !is DocumentReach.Scoped ||
            documentReach.workspaceRootUuids.isNotEmpty() || documentReach.documentIds.isNotEmpty())
        InfinityLogger.log("[$TAG] canSearch: memory=$canSearchMemory docs=$canSearchDocs memoryReach=${scope.memory::class.simpleName} documentReach=${documentReach::class.simpleName}")
        if (!canSearchMemory && !canSearchDocs) {
            InfinityLogger.log("[$TAG] No document scope, returning empty")
            return emptyResult(System.currentTimeMillis() - startTime, RagRetrievalTrace.OUTCOME_NO_SCOPE, scope = scope)
        }

        onProgress?.invoke("Embedding query", 10, "Sending query to embedding model")

        // P0 诊断日志: 记录 vectors 表状态，帮助定位 "0 results" 的根本原因
        try {
            val totalVecCount = vectorStore.getTotalVectorCount()
            val sessionVecCount = vectorStore.getSessionVectorCount(sessionId)
            InfinityLogger.log("[$TAG] vectors DB state: total=$totalVecCount, sessionVecCount=$sessionVecCount, memoryThreshold=${effectiveConfig.memoryThreshold}, docThreshold=${effectiveConfig.docThreshold}, rerankTopK=${effectiveConfig.rerankTopK}")
        } catch (e: Exception) {
            InfinityLogger.log("[$TAG] vectors DB state query failed: ${e.message?.take(80)}")
        }

        val queryVariants = if (options.allowQueryRewrite) buildQueryVariants(query, effectiveConfig) else listOf(query)
        val queryEmbeddings = mutableListOf<Pair<String, FloatArray>>()
        var firstEmbedMs = 0L
        for ((index, searchQuery) in queryVariants.withIndex()) {
            try {
                val startEmbed = System.currentTimeMillis()
                val emb = embeddingClient.embedQuery(searchQuery).first
                val embedMs = System.currentTimeMillis() - startEmbed
                if (index == 0) firstEmbedMs = embedMs
                val storedDim = try { vectorStore.getFirstStoredDimension() } catch (_: Exception) { null }
                InfinityLogger.log("[$TAG] embedQuery success: variant=${index + 1}/${queryVariants.size}, dim=${emb.size}, time=${embedMs}ms, storedDim=${storedDim ?: "N/A (DB empty?)"}")
                queryEmbeddings.add(searchQuery to emb)
            } catch (e: Exception) {
                InfinityLogger.logError("[$TAG] embedQuery failed for variant=${index + 1}", e)
            }
        }
        if (queryEmbeddings.isEmpty()) {
            // 嵌入模型已配置但请求全部失败（网络、鉴权、模型名等），属于真正的检索失败。
            return emptyResult(System.currentTimeMillis() - startTime, RagRetrievalTrace.OUTCOME_FAILED, scope = scope)
        }
        onProgress?.invoke("Embedding done", 25, "${queryEmbeddings.first().second.size}d vector in ${firstEmbedMs}ms")

        onProgress?.invoke("Searching memory", 30, "Retrieving from vector DB")
        val results = mutableListOf<SearchResult>()

        if (canSearchMemory) {
            try {
                val startMem = System.currentTimeMillis()
                val memoryResults = queryEmbeddings.flatMap { (_, queryEmbedding) ->
                    vectorStore.searchConversation(
                        queryEmbedding = queryEmbedding,
                        reach = scope.memory,
                        type = "memory",
                        limit = effectiveConfig.rerankTopK,
                        threshold = effectiveConfig.memoryThreshold,
                        onWarning = { warn -> InfinityLogger.log("[$TAG] memory search warning: $warn") }
                    )
                }
                val memMs = System.currentTimeMillis() - startMem
                InfinityLogger.log("[$TAG] memory search: ${memoryResults.size} results, time=${memMs}ms, threshold=${effectiveConfig.memoryThreshold}${if (memoryResults.isEmpty()) " ⚠️ 0 results — check: session has vectors? dimensions match? similarity ≥ threshold?" else ""}")
                results.addAll(memoryResults)
            } catch (e: Exception) {
                InfinityLogger.logError("[$TAG] searchMemory failed", e)
            }

            try {
                val summaryResults = queryEmbeddings.flatMap { (_, queryEmbedding) ->
                    vectorStore.searchConversation(
                        queryEmbedding = queryEmbedding,
                        reach = scope.memory,
                        type = "summary",
                        limit = if (effectiveConfig.enableRerank) 10 else 5,
                        threshold = effectiveConfig.memoryThreshold - 0.05f,
                        onWarning = { warn -> InfinityLogger.log("[$TAG] summary search warning: $warn") }
                    )
                }
                InfinityLogger.log("[$TAG] summary search: ${summaryResults.size} results, threshold=${effectiveConfig.memoryThreshold - 0.05f}")
                results.addAll(summaryResults)
            } catch (e: Exception) {
                InfinityLogger.logError("[$TAG] searchSummary failed", e)
            }
        }

        val now = clock()
        val zoneId = zone()
        // 问题回顾某段时间的对话时，该时段的记录不受相似度阈值限制，并优先进入结果。
        val recallRange = if (canSearchMemory) parseRecallTimeRange(query, Instant.ofEpochMilli(now).atZone(zoneId)) else null
        val inRangeResults = recallRange?.let { range ->
            try {
                searchConversationInRange(range, scope.memory, queryEmbeddings, effectiveConfig.memoryLimit + EVENT_LIMIT)
                    .also { InfinityLogger.log("[$TAG] recall range search: ${it.size} results in [${range.from}, ${range.to})") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                InfinityLogger.logError("[$TAG] recall range search failed", e)
                null
            }
        }.orEmpty()
        val states = if (!canSearchMemory) emptyList() else try {
            currentStates(scope.memory, queryEmbeddings.first().second)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            InfinityLogger.logError("[$TAG] load states failed", e)
            emptyList()
        }

        if (canSearchDocs) {
            onProgress?.invoke("Searching documents", 50, "Scanning all indexed documents")
            try {
                val startDocs = System.currentTimeMillis()
                val docResults = queryEmbeddings.flatMap { (_, queryEmbedding) ->
                    vectorStore.searchDocuments(
                        queryEmbedding = queryEmbedding,
                        reach = documentReach,
                        limit = effectiveConfig.rerankTopK,
                        threshold = effectiveConfig.docThreshold,
                    )
                }
                val docsMs = System.currentTimeMillis() - startDocs
                InfinityLogger.log("[$TAG] document search: ${docResults.size} results, time=${docsMs}ms, threshold=${effectiveConfig.docThreshold}")
                results.addAll(docResults)
            } catch (e: Exception) {
                InfinityLogger.logError("[$TAG] searchDocs failed", e)
            }
        }

        InfinityLogger.log("[$TAG] total raw results before fusion: ${results.size}")

        // Hybrid search (RRF Fusion)
        onProgress?.invoke("Hybrid fusion", 70, "Merging vector + keyword results")
        var hybridApplied = false
        val finalResults = if (effectiveConfig.enableHybridSearch) {
            try {
                val keywordResults = queryVariants.flatMap { searchQuery ->
                    keywordSearcher.searchScoped(
                        query = searchQuery,
                        limit = effectiveConfig.rerankTopK,
                        memory = scope.memory.takeIf { canSearchMemory },
                        documents = documentReach.takeIf { canSearchDocs },
                        expectedDimension = queryEmbeddings.first().second.size,
                    )
                }
                val fused = rrfFusion(results, keywordResults, effectiveConfig)
                InfinityLogger.log("[$TAG] hybrid fusion: ${results.size} vector + ${keywordResults.size} keyword → ${fused.size} fused")
                hybridApplied = true
                fused
            } catch (e: Exception) {
                InfinityLogger.logError("[$TAG] hybrid fusion failed, using raw results", e)
                results
            }
        } else {
            results
        }

        onProgress?.invoke("Ranking results", 90, "${finalResults.size} candidates found")
        if (finalResults.isEmpty() && inRangeResults.isEmpty() && states.isEmpty()) {
            InfinityLogger.log("[$TAG] No results after fusion, returning empty")
            return emptyResult(System.currentTimeMillis() - startTime, RagRetrievalTrace.OUTCOME_EMPTY, memoryStats, scope)
        }

        val uniqueResults = finalResults
            .sortedByDescending { it.similarity }
            .distinctBy { it.id }

        var actualRerankTimeMs = 0L
        val rerankOutcome = if (canRerank && uniqueResults.isNotEmpty()) {
            InfinityLogger.log("[$TAG] rerank start: ${uniqueResults.size} candidates")
            onProgress?.invoke("Reranking", 92, "Re-scoring ${uniqueResults.size} candidates")
            val rerankStart = System.currentTimeMillis()
            try {
                rerankClient.rerankWithOutcome(query, uniqueResults, effectiveConfig.rerankTopK)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                InfinityLogger.logError("[$TAG] rerank failed", e)
                RerankOutcome(uniqueResults, RerankMethod.FAILED)
            }.also {
                actualRerankTimeMs = System.currentTimeMillis() - rerankStart
                onProgress?.invoke("Rerank complete", 95, "${it.results.size} results in ${actualRerankTimeMs}ms")
            }
        } else {
            null
        }
        val rerankedResults = rerankOutcome?.results ?: uniqueResults
        val reranked = rerankOutcome?.method == RerankMethod.API || rerankOutcome?.method == RerankMethod.LLM

        // P1: 若重排已启用，rerankFinalK 作为最终结果总数的上限（语义上优于 memoryLimit+docLimit 简单加和）
        val finalCap = if (canRerank) effectiveConfig.rerankFinalK
            else effectiveConfig.memoryLimit + effectiveConfig.docLimit

        // 对话记录：时段内的记录优先，其余按相关度加新近度排序；同一记录以重排后的分数为准。
        val rerankedById = rerankedResults.associateBy { it.id }
        val inRange = inRangeResults.map { rerankedById[it.id] ?: it }
        val inRangeIds = inRange.mapTo(HashSet()) { it.id }
        val conversationCandidates = rerankedResults.filter { parseTypeFromMetadata(it.metadata) in CONVERSATION_RECORD_TYPES }
        val occurredAt = occurredAt(conversationCandidates + inRange)
        val bonus = MemoryTimeline.recencyBonus(conversationCandidates.associate { it.id to occurredAt.getValue(it.id) })
        val priority = compareByDescending<SearchResult> { it.id in inRangeIds }
            .thenByDescending { it.similarity + (bonus[it.id] ?: 0f) }
        val conversation = (inRange + conversationCandidates.filterNot { it.id in inRangeIds }).sortedWith(priority)
        val topMemories = conversation
            .filter { parseTypeFromMetadata(it.metadata) == "memory" }
            .take(effectiveConfig.memoryLimit)
        val topEvents = conversation
            .filter { parseTypeFromMetadata(it.metadata) == "summary" }
            .take(EVENT_LIMIT)
        val topDocs = rerankedResults
            .filter { parseTypeFromMetadata(it.metadata) == "doc" }
            .take(effectiveConfig.docLimit)
        val combinedResults = (topMemories + topEvents + topDocs)
            .sortedWith(priority)
            .take(finalCap)

        val endTime = System.currentTimeMillis()
        val retrievedChunks = formatRetrievedChunks(combinedResults, states, occurredAt, now, zoneId)
        val contextBlock = if (effectiveConfig.summaryTemplate.isNotBlank()) {
            effectiveConfig.summaryTemplate
                .replace("{retrieved_chunks}", retrievedChunks)
                .replace("{query}", query)
        } else {
            retrievedChunks
        }

        val recallRanks = uniqueResults.withIndex().associate { (index, result) -> result.id to index + 1 }
        val citationForReference = currentCoroutineContext()[KnowledgeCitationScope]?.citation
        val references = combinedResults.mapIndexed { finalIndex, r ->
            val type = parseTypeFromMetadata(r.metadata)
            val recallRank = recallRanks[r.id]
            val sourceLabel = when {
                type == "memory" -> "对话记忆"
                type == "summary" -> "对话事件"
                r.docId != null -> {
                    documentReferenceSource(r.metadata, r.docId)
                }
                else -> "文档片段"
            }
            RagReference(
                id = r.id,
                content = r.content,
                source = sourceLabel,
                score = if (reranked) r.originalSimilarity ?: r.similarity else r.similarity,
                rerankScore = if (reranked) r.similarity else null,
                rankChange = if (reranked) recallRank?.let { it - (finalIndex + 1) } else null,
                documentId = r.fileUuid ?: r.docId,
                chunkIndex = documentChunkIndexFromVectorMetadata(r.metadata) ?: 0,
                documentContentHash = r.documentContentHash ?: documentContentHashFromVectorMetadata(r.metadata),
                recallRank = recallRank,
                kind = when (type) {
                    "memory" -> RagReferenceKind.MEMORY
                    "doc" -> RagReferenceKind.DOCUMENT
                    "summary" -> RagReferenceKind.SUMMARY
                    else -> null
                },
                occurredAt = occurredAt[r.id],
                citationId = citationForReference?.invoke(r.id),
            )
        } + states.map { state ->
            RagReference(
                id = state.fact.rowId,
                content = state.fact.content,
                source = "对话状态",
                // 当前状态不按相关度入选，不显示相似度。
                kind = RagReferenceKind.STATE,
                occurredAt = state.fact.occurredAt,
                citationId = citationForReference?.invoke(state.fact.rowId),
            )
        }
        val memoryCount = combinedResults.count { parseTypeFromMetadata(it.metadata) in CONVERSATION_RECORD_TYPES } + states.size
        val documentCount = combinedResults.count { parseTypeFromMetadata(it.metadata) == "doc" }
        return RetrieveResult(
            context = "relevant_context_block (参考上下文):\n$contextBlock",
            references = references,
            metadata = RetrieveMetadata(
                searchTimeMs = endTime - startTime,
                rerankTimeMs = actualRerankTimeMs,
                recallCount = uniqueResults.size,
                finalCount = references.size,
                sourceDistribution = SourceDistribution(
                    memory = memoryCount,
                    documents = documentCount,
                )
            ),
            trace = RagRetrievalTrace(
                outcome = if (references.isEmpty()) RagRetrievalTrace.OUTCOME_EMPTY else RagRetrievalTrace.OUTCOME_HIT,
                recallCount = uniqueResults.size,
                selectedCount = references.size,
                scoring = if (hybridApplied) RagRetrievalTrace.SCORING_HYBRID else RagRetrievalTrace.SCORING_VECTOR,
                rerank = when (rerankOutcome?.method) {
                    null -> RagRetrievalTrace.RERANK_OFF
                    RerankMethod.API -> RagRetrievalTrace.RERANK_API
                    RerankMethod.LLM -> RagRetrievalTrace.RERANK_LLM
                    RerankMethod.SKIPPED -> RagRetrievalTrace.RERANK_SKIPPED
                    RerankMethod.FAILED -> RagRetrievalTrace.RERANK_FAILED
                },
                rerankModel = rerankOutcome?.model,
                searchTimeMs = endTime - startTime,
                rerankTimeMs = actualRerankTimeMs,
                memoryCount = memoryCount,
                documentCount = documentCount,
                memorySessionCount = memoryStats?.sessionCount,
                memoryAgentCount = memoryStats?.agentCount,
                memoryScope = when (scope.memory) {
                    is ConversationReach.Session -> ConversationScope.SESSION
                    is ConversationReach.Agent -> ConversationScope.AGENT
                    is ConversationReach.SharedAgents -> ConversationScope.ALL_AGENTS
                }.takeIf { canSearchMemory },
                effectiveDocumentIds = scope.documents.let { if (it is DocumentReach.Scoped) it.documentIds.sorted() else emptyList() },
                effectiveCollectionIds = scope.selectedCollectionIds.sorted(),
                graphCollectionId = scope.graphBinding.collectionId,
                graphTitle = scope.graphTitle,
                graphBindingMode = scope.graphBinding.mode,
                graphRestriction = scope.graphRestriction,
            ),
            resolvedScope = scope,
        )
    }

    /** 嵌入模型可用（远程已配置或有本地模型）时才能写入对话记忆。 */
    val canEmbed: Boolean
        get() = embeddingClient.isConfigured || embeddingClient.hasLocalFallback

    /** 已解析的文档范围复用对话检索的向量、关键词和融合逻辑。 */
    suspend fun searchResolvedDocuments(
        query: String,
        queryEmbedding: FloatArray,
        reach: DocumentReach,
        limit: Int,
    ): ResolvedDocumentSearch {
        val vector = vectorStore.searchDocuments(queryEmbedding, reach, limit, ragConfig.docThreshold)
        val keywords = keywordSearcher.searchScoped(query, limit, null, reach, queryEmbedding.size)
        return ResolvedDocumentSearch(vector, keywords, rrfFusion(vector, keywords, ragConfig))
    }

    suspend fun searchDocumentKeywords(query: String, reach: DocumentReach, limit: Int): List<SearchResult> =
        keywordSearcher.searchScoped(query, limit, null, reach)

    suspend fun addTurnToMemory(
        sessionId: String,
        userContent: String,
        aiContent: String,
        userMessageId: String,
        assistantMessageId: String
    ): Int = ConversationMemoryWriteCoordinator.withDirectWriter(sessionId) {
        addTurnToMemoryCoordinated(sessionId, userContent, aiContent, userMessageId, assistantMessageId)
    }

    private suspend fun addTurnToMemoryCoordinated(
        sessionId: String,
        userContent: String,
        aiContent: String,
        userMessageId: String,
        assistantMessageId: String
    ): Int {
        if (userContent.isBlank() || aiContent.isBlank()) {
            InfinityLogger.log("[MemoryManager] addTurnToMemory skipped: empty content for session=$sessionId")
            return 0
        }

        val startTime = System.currentTimeMillis()
        val sanitizedUser = sanitizeContent(userContent)
        val sanitizedAi = sanitizeContent(aiContent)

        val turnText = "User: $sanitizedUser\nAssistant: $sanitizedAi"
        val splitter = TrigramTextSplitter(
            chunkSize = ragConfig.memoryChunkSize,
            chunkOverlap = ragConfig.chunkOverlap
        )
        val chunks = splitter.splitText(turnText)
        if (chunks.isEmpty()) {
            InfinityLogger.log("[MemoryManager] addTurnToMemory: 0 chunks after split, session=$sessionId")
            return 0
        }

        InfinityLogger.log("[MemoryManager] addTurnToMemory: chunking=${chunks.size} chunks, session=$sessionId, chunkSize=${ragConfig.memoryChunkSize}")
        val (embeddingResult, embeddingSpace) = embeddingClient.embedDocumentsForIndex(chunks)
        InfinityLogger.log("[MemoryManager] addTurnToMemory: embedding done, dim=${embeddingResult.embeddings.firstOrNull()?.size}, chunks=${chunks.size}")

        val vectors = chunks.mapIndexed { i, chunk ->
            VectorStore.NewVectorRecord(
                sessionId = sessionId,
                content = chunk,
                embedding = embeddingResult.embeddings[i],
                metadata = withVectorSpaceMetadata("""{"type":"memory","chunkIndex":$i}""", embeddingSpace),
                startMessageId = userMessageId,
                endMessageId = assistantMessageId
            )
        }

        vectorStore.addVectorRecords(vectors)
        val totalMs = System.currentTimeMillis() - startTime
        InfinityLogger.log("[MemoryManager] addTurnToMemory done: ${vectors.size} vectors stored, session=$sessionId, total=${totalMs}ms")
        return vectors.size
    }

    /** 时段内的对话记录：按所属消息的发生时间筛选，不设相似度阈值，按相似度取前 [limit] 条。 */
    private suspend fun searchConversationInRange(
        range: MemoryTimeRange,
        reach: ConversationReach,
        queryEmbeddings: List<Pair<String, FloatArray>>,
        limit: Int,
    ): List<SearchResult> {
        val messageIds = graphStore.database.messageDao().getIdsCreatedBetween(range.from, range.to).toHashSet()
        val inRange = { row: VectorEntity ->
            val owner = row.startMessageId ?: row.endMessageId
            if (owner != null) owner in messageIds else row.createdAt in range
        }
        return CONVERSATION_RECORD_TYPES.flatMap { type ->
            queryEmbeddings.flatMap { (_, embedding) ->
                vectorStore.searchConversation(embedding, reach, type, limit, threshold = -1f, rowFilter = inRange)
            }
        }.sortedByDescending { it.similarity }.distinctBy { it.id }.take(limit)
    }

    private data class ScoredState(val fact: SessionStateFact, val similarity: Float)

    /**
     * 范围内每个“对象 · 属性”的最新值，较新的覆盖较早的。不超过 [STATE_LIMIT] 条时全部注入；
     * 超过时一半取与问题最相关的，其余取最近更新的。按更新时间先后返回。
     */
    private suspend fun currentStates(reach: ConversationReach, queryEmbedding: FloatArray): List<ScoredState> {
        val scored = vectorStore.conversationRowsScored(queryEmbedding, reach, "state")
            .mapNotNull { (row, similarity) -> parseStateRow(row)?.let { ScoredState(it, similarity) } }
        val similarityById = scored.associate { it.fact.rowId to it.similarity }
        val latest = latestStates(scored.map { it.fact }).map { ScoredState(it, similarityById[it.rowId] ?: 0f) }
        if (latest.size <= STATE_LIMIT) return latest
        val relevant = latest.sortedByDescending { it.similarity }.take(STATE_LIMIT / 2)
        val recent = latest.asReversed().filterNot { it in relevant }.take(STATE_LIMIT - relevant.size)
        return (relevant + recent).sortedBy { it.fact.occurredAt }
    }

    /** 记录 id 到所属轮次用户消息（缺失时为助手消息）的发生时间；消息查不到时用写入时间。 */
    private suspend fun occurredAt(results: List<SearchResult>): Map<String, Long> {
        val owners = results.associate { it.id to (it.startMessageId ?: it.endMessageId) }
        val ids = owners.values.filterNotNull().distinct()
        val messageTimes = HashMap<String, Long>()
        ids.chunked(500).forEach { chunk ->
            graphStore.database.messageDao().getCreatedAt(chunk).forEach { messageTimes[it.id] = it.created_at }
        }
        return results.associate { it.id to (owners[it.id]?.let(messageTimes::get) ?: it.createdAt) }
    }

    /**
     * 注入正文：先写当前时间与先后规则，再写当前状态与按时间先后排列的对话记录，最后是按相关度排列的文档。
     * 时间只出现在这里，不进入向量正文。
     */
    private suspend fun formatRetrievedChunks(
        results: List<SearchResult>,
        states: List<ScoredState>,
        occurredAt: Map<String, Long>,
        now: Long,
        zone: ZoneId,
    ): String {
        val localCitations = linkedMapOf<String, String>()
        val citation = currentCoroutineContext()[KnowledgeCitationScope]?.citation
            ?: { id: String -> localCitations.getOrPut(id) { "K${localCitations.size + 1}" } }
        val conversation = results.filter { parseTypeFromMetadata(it.metadata) in CONVERSATION_RECORD_TYPES }
            .sortedWith(compareBy({ occurredAt[it.id] ?: it.createdAt }, { it.id }))
        val documents = results.filter { parseTypeFromMetadata(it.metadata) !in CONVERSATION_RECORD_TYPES }
        val blocks = buildList {
            if (states.isNotEmpty() || conversation.isNotEmpty()) {
                add(
                    "Current time: ${MemoryTimeline.currentTime(now, zone)}\n" +
                        "Conversation records carry the time they were said and are listed oldest first. " +
                        "When records conflict, the newer one reflects the current state.",
                )
            }
            if (states.isNotEmpty()) {
                add(states.joinToString("\n") { state ->
                    "[${citation(state.fact.rowId)} · 状态 · 更新于 ${MemoryTimeline.stamp(state.fact.occurredAt, now, zone)}]: ${state.fact.content}"
                })
            }
            conversation.forEach { r ->
                val label = if (parseTypeFromMetadata(r.metadata) == "summary") "事件" else "记忆"
                add("[${citation(r.id)} · $label · ${MemoryTimeline.stamp(occurredAt[r.id] ?: r.createdAt, now, zone)}]: ${r.content}")
            }
            documents.forEach { r ->
                val name = documentReferenceSource(r.metadata, r.docId.orEmpty()).removePrefix("文档: ").takeIf(String::isNotBlank)
                val label = name?.let { "文档《$it》" } ?: "文档"
                add("[${citation(r.id)} · $label]: ${r.content}")
            }
        }
        return blocks.joinToString("\n\n")
    }

    internal fun rrfFusion(
        vectorResults: List<SearchResult>,
        keywordResults: List<SearchResult>,
        config: RagConfiguration
    ): List<SearchResult> {
        val rrfK = 60
        val scoreMap = mutableMapOf<String, Float>()
        val nodeMap = mutableMapOf<String, SearchResult>()
        val alpha = config.hybridAlpha
        val bm25Boost = config.hybridBM25Boost

        val addScores = { items: List<SearchResult>, weight: Float ->
            items.forEachIndexed { rank, item ->
                val current = scoreMap[item.id] ?: 0f
                scoreMap[item.id] = current + weight * (1.0f / (rrfK + rank + 1))
                if (!nodeMap.containsKey(item.id)) nodeMap[item.id] = item
            }
        }

        val uniqueVector = vectorResults.distinctBy { it.id }.sortedByDescending { it.similarity }
        addScores(uniqueVector, alpha)
        addScores(keywordResults, (1 - alpha) * bm25Boost)

        val rrfMax = (1.0f / (rrfK + 1)) * 2
        val fusedResults = scoreMap.mapNotNull { (id, score) ->
            nodeMap[id]?.let { node ->
                var normalized = score / rrfMax
                normalized = normalized.coerceIn(0.01f, 0.99f)
                node.copy(similarity = normalized)
            }
        }

        return fusedResults.sortedByDescending { it.similarity }
    }

    private suspend fun buildQueryVariants(query: String, config: RagConfiguration): List<String> {
        val normalizedCount = config.queryRewriteCount.coerceIn(0, 5)
        if (!config.enableQueryRewrite || normalizedCount == 0 || queryRewriter == null) {
            return listOf(query)
        }
        return try {
            val strategy = parseRewriteStrategy(config.queryRewriteStrategy)
            val result = queryRewriter.rewrite(
                query = query,
                count = normalizedCount,
                strategyOverride = strategy,
                modelIdOverride = config.queryRewriteModel
            )
            result.variants
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(normalizedCount + 1)
                .ifEmpty { listOf(query) }
                .also { variants ->
                    InfinityLogger.log("[MemoryManager] query rewrite: enabled strategy=${config.queryRewriteStrategy}, variants=${variants.size}")
                }
        } catch (e: Exception) {
            InfinityLogger.logError("[MemoryManager] query rewrite failed, fallback to original query", e)
            listOf(query)
        }
    }

    private fun parseRewriteStrategy(raw: String): RewriteStrategy {
        return when (raw.trim().lowercase()) {
            "hyde" -> RewriteStrategy.HYDE
            "expansion", "expand" -> RewriteStrategy.EXPANSION
            else -> RewriteStrategy.MULTI_QUERY
        }
    }

    private fun sanitizeContent(text: String): String {
        return text.replace(Regex("!\\[.*?\\]\\(data:image/.*?;base64,.*?\\)"), "[Image]")
    }

    private companion object {
        /** 按时间线注入的对话记录：逐轮记忆片段与事件摘要。 */
        val CONVERSATION_RECORD_TYPES = setOf("memory", "summary")
        const val EVENT_LIMIT = 3
        const val STATE_LIMIT = 24
    }

    private fun parseTypeFromMetadata(metadata: String?): String? {
        val type = vectorMetadataType(metadata)
        return if (type == "document") "doc" else type
    }

    private fun emptyResult(searchTimeMs: Long, outcome: String, memoryStats: ConversationReachStats? = null, scope: ResolvedRetrievalScope? = null) = RetrieveResult(
        context = "",
        references = emptyList(),
        metadata = RetrieveMetadata(searchTimeMs = searchTimeMs),
        trace = RagRetrievalTrace(
            outcome = outcome,
            searchTimeMs = searchTimeMs,
            memorySessionCount = memoryStats?.sessionCount,
            memoryAgentCount = memoryStats?.agentCount,
            effectiveDocumentIds = (scope?.documents as? DocumentReach.Scoped)?.documentIds?.sorted().orEmpty(),
            effectiveCollectionIds = scope?.selectedCollectionIds?.sorted().orEmpty(),
            graphCollectionId = scope?.graphBinding?.collectionId,
            graphTitle = scope?.graphTitle,
            graphBindingMode = scope?.graphBinding?.mode,
            graphRestriction = scope?.graphRestriction,
        ),
        resolvedScope = scope,
    )
}
