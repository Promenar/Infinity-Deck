package com.promenar.infinity.data.rag

import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.ui.chat.manager.KgContextResult
import com.promenar.infinity.ui.chat.manager.KgProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 持久图优先，只有可见命中片段且图覆盖不足时才消耗 JIT 请求。 */
class PersistentGraphKgProvider(
    graphStore: GraphStore,
    private val jit: KgProvider,
    private val configProvider: () -> RagConfiguration,
) : KgProvider {
    private val database = graphStore.database
    private val retrieval = GraphRetrievalStore(graphStore.database)
    private val scopeResolver = RetrievalScopeResolver(graphStore.database)

    override suspend fun extractContext(query: String, sessionId: String, topKResults: List<RagReference>) =
        retrieveContext(query, sessionId, topKResults, RagOptions(enableKnowledgeGraph = true))

    override suspend fun retrieveContext(query: String, sessionId: String, topKResults: List<RagReference>, options: RagOptions): KgContextResult =
        retrieve(query, sessionId, topKResults, options)

    override suspend fun retrieveForTool(query: String, sessionId: String, topKResults: List<RagReference>, options: RagOptions): KgContextResult =
        retrieve(query, sessionId, topKResults, options, allowJit = false)

    override suspend fun retrieveWithScope(query: String, sessionId: String, topKResults: List<RagReference>, options: RagOptions,
        scope: ResolvedRetrievalScope?, forTool: Boolean): KgContextResult =
        retrieve(query, sessionId, topKResults, options, allowJit = !forTool, scope = scope)

    override suspend fun retrieveForToolAt(query: String, sessionId: String, topKResults: List<RagReference>, options: RagOptions,
        scope: ResolvedRetrievalScope?, asOf: KgAsOf?): KgContextResult =
        retrieve(query, sessionId, topKResults, options, allowJit = false, scope = scope, asOf = asOf)

    private suspend fun retrieve(
        query: String,
        sessionId: String,
        topKResults: List<RagReference>,
        options: RagOptions,
        allowJit: Boolean = true,
        scope: ResolvedRetrievalScope? = null,
        asOf: KgAsOf? = null,
    ): KgContextResult {
        if (options.enableKnowledgeGraph != true) return KgContextResult("")
        val config = configProvider()
        val resolvedScope = KgRetrievalScope.of(scope ?: scopeResolver.resolve(sessionId, options))
        if (resolvedScope.restriction != null) return KgContextResult("")
        // 文档范围决定即时抽取输入；持久图始终读取绑定图谱中的存活证据。
        val scope = resolvedScope.copy(
            documents = if (allowJit || options.enableDocs) resolvedScope.documents else DocumentReach.Scoped(emptySet(), emptySet()),
            allowConversation = allowJit || options.enableMemory,
            asOf = asOf,
        )
        val documents = retrieval.visibleDocumentIds(scope)
        val visibleReferences = withContext(Dispatchers.IO) {
            // 即时抽取只用可见文档与本会话记忆：证据归属本会话，删除其他会话的聊天记录时无法据此失效。
            topKResults.filter { reference ->
                if (reference.documentId != null) reference.documentId in documents
                else database.vectorDao().getById(reference.id)?.let { vector ->
                    vector.docId == null && vector.sessionId == sessionId && !vector.stale && vector.content == reference.content
                } == true
            }
        }
        val persistent = retrieval.retrieve(query, scope, visibleReferences.mapNotNull { it.documentId }, config)
        val needsJit = persistent.seedCount < config.kgJitMinSeeds.coerceIn(1, 32) || persistent.paths.isEmpty()
        val instant = if (allowJit && !persistent.temporalRestricted && config.kgJitFallbackEnabled && needsJit && visibleReferences.isNotEmpty()) {
            try {
                (if (jit is MicroGraphKgAdapter) jit.extractForGraph(query, sessionId, visibleReferences, scope.collectionId)
                    else jit.extractContext(query, sessionId, visibleReferences))?.takeIf { it.status == KgExtractionStatus.SUCCESS && it.error == null }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        } else null
        if (KgCollectionRepository(database).readBinding(sessionId).collectionId != scope.collectionId) return KgContextResult("")
        val paths = KgContextBudget.prune(KgContextBudget.fuse(persistent.paths, instant?.paths.orEmpty()), config.kgMaxNodes, config.kgMaxEdges)
        val context = KgContextBudget.fit(paths, config.kgContextMaxTokens.coerceIn(0, 16384))
        return KgContextResult(context.context, context.paths, canonicalPaths = true,
            maxContextTokens = config.kgContextMaxTokens.coerceIn(0, 16384),
            jitExtractionCount = instant?.jitExtractionCount ?: 0, jitModel = instant?.jitModel)
    }
}
