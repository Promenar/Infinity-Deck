package com.promenar.infinity.data.rag

import com.promenar.infinity.data.local.db.dao.KgJitCacheDao
import com.promenar.infinity.data.local.db.entity.KgJitCacheEntity
import com.promenar.infinity.data.session.SessionExecutionGate
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json

class MicroGraphExtractor(
    private val requestClient: KgRequestClient,
    private val graphStore: GraphStore,
    private val jitCacheDao: KgJitCacheDao,
    /** 抽取所用模型的稳定 ID，供检索轨迹展示。 */
    val modelId: String? = null,
    private val systemPrompt: String = GraphExtractor.DEFAULT_KG_PROMPT,
    private val cacheTtlSeconds: Long = 3600,
    private val timeoutMs: Long = 5000,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val executionGate: SessionExecutionGate? = null,
) : AutoCloseable {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val pendingMerges = Channel<PendingMerge>(capacity = 8)
    private val worker = scope.launch {
        for (pending in pendingMerges) {
            try {
                val merge: suspend () -> Unit = {
                    graphStore.persistJit(pending.result, pending.sources, pending.sessionId, cacheEntry(pending.key, pending.result), pending.collectionId)
                }
                val gate = executionGate
                if (gate == null) merge() else gate.withSessionAdmission(pending.sessionId, merge)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                InfinityLogger.log("[KG] JIT 后台归并失败")
            }
        }
    }

    init {
        require(timeoutMs > 0 && cacheTtlSeconds > 0) { "JIT 超时和缓存有效期必须大于 0" }
    }

    suspend fun extract(
        topKResults: List<SearchResult>,
        query: String,
        sessionId: String,
        timeout: Long = timeoutMs,
        maxChars: Int = 6000,
        expectedCollectionId: String? = null,
    ): MicroGraphResult {
        require(timeout > 0 && maxChars > 0) { "JIT 超时和文本预算必须大于 0" }
        if (!scope.isActive) return MicroGraphResult.failure(KgExtractionStatus.CANCELLED, "JIT 抽取器已关闭", query)
        return withContext(dispatcher) { withTimeoutOrNull(timeout) {
            val binding = KgCollectionRepository(graphStore.database).readBinding(sessionId)
            if (expectedCollectionId != null && binding.collectionId != expectedCollectionId) {
                return@withTimeoutOrNull MicroGraphResult.failure(KgExtractionStatus.CANCELLED, "图谱绑定已变化", query)
            }
            val input = prepareInput(topKResults, maxChars)
            val sources = graphStore.captureJitSources(input.sources)
            val text = input.text
            val key = kgSha256(listOf(sessionId, binding.collectionId, query, text, modelId.orEmpty(), kgSha256(systemPrompt),
                Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()),
                    topKResults.map { Json.encodeToString(SearchResult.serializer(), it.copy(embedding = FloatArray(0))) }),
                sources.joinToString("\u0000") { "${it.docId}:${it.contentHash}:${it.epoch}" }, maxChars.toString(), "validation-v2").joinToString("\u0000"))
            getFromCache(key)?.let { cached ->
                if (binding.writable) pendingMerges.send(PendingMerge(key, cached, sessionId, sources, binding.collectionId))
                return@withTimeoutOrNull cached
            }
            val extracted = if (text.isBlank()) ExtractionResult(emptyList(), emptyList())
            else requestClient.extract(modelId, systemPrompt, "检索问题：$query\n\n请从以下文本片段提取关系：\n$text", timeout, sessionId = sessionId)
            if (!extracted.isSuccess) return@withTimeoutOrNull MicroGraphResult.failure(extracted.status, extracted.error.orEmpty(), query)
            val graph = ValidatedGraph.from(extracted.nodes, extracted.edges)
            val result = MicroGraphResult(graph.nodes, graph.edges, graph.context, input.sources.map { it.id }, query, System.currentTimeMillis(), sourceDocumentIds = sources.mapNotNull { it.docId }.distinct().sorted(), modelCalled = text.isNotBlank())
            currentCoroutineContext().ensureActive()
            if (!scope.isActive) return@withTimeoutOrNull MicroGraphResult.failure(KgExtractionStatus.CANCELLED, "JIT 抽取器已关闭", query)
            // 单个消费者与固定容量限制后台工作；队列繁忙时保留当前请求结果，跳过可选持久化。
            if (binding.writable) pendingMerges.trySend(PendingMerge(key, result, sessionId, sources, binding.collectionId))
            result
        } ?: MicroGraphResult.failure(KgExtractionStatus.TIMEOUT, "知识图谱 JIT 抽取超时", query) }
    }

    private suspend fun getFromCache(key: String): MicroGraphResult? = try {
        val cached = jitCacheDao.getByKey(key)
        if (cached == null || cached.expiresAt < System.currentTimeMillis()) null else {
            val result = Json.decodeFromString<MicroGraphResult>(cached.resultJson)
            if (!result.isSuccess) null else {
                val graph = ValidatedGraph.from(result.nodes, result.edges)
                result.copy(nodes = graph.nodes, edges = graph.edges, context = graph.context)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { null }

    private fun cacheEntry(key: String, result: MicroGraphResult): KgJitCacheEntity {
        val now = System.currentTimeMillis()
        return KgJitCacheEntity(
            cacheKey = key,
            queryHash = kgSha256(result.query),
            chunkIdsHash = kgSha256(result.sourceChunkIds.joinToString(",")),
            resultJson = Json.encodeToString(MicroGraphResult.serializer(), result),
            createdAt = now,
            expiresAt = now + cacheTtlSeconds * 1000,
        )
    }

    private fun prepareInput(results: List<SearchResult>, maxChars: Int): PreparedInput {
        val sources = mutableListOf<SearchResult>()
        val text = buildString {
            for (result in results) {
                if (length >= maxChars) break
                if (isNotEmpty()) append("\n---\n".take(maxChars - length))
                val content = result.content.take(maxChars - length)
                if (content.isNotEmpty()) sources += result
                append(content)
            }
        }
        return PreparedInput(text, sources)
    }

    private data class PreparedInput(val text: String, val sources: List<SearchResult>)

    override fun close() {
        pendingMerges.close()
        scope.cancel()
    }

    private data class PendingMerge(val key: String, val result: MicroGraphResult, val sessionId: String, val sources: List<KgJitSource>, val collectionId: String)
}
