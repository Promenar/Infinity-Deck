package com.promenar.infinity.data.rag

import kotlinx.serialization.Serializable

@Serializable
data class VectorRecord(
    val id: String,
    val docId: String? = null,
    val sessionId: String? = null,
    val content: String,
    val embedding: FloatArray,
    val metadata: String? = null,
    val startMessageId: String? = null,
    val endMessageId: String? = null,
    val createdAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VectorRecord
        if (id != other.id) return false
        if (!embedding.contentEquals(other.embedding)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}

@Serializable
data class SearchResult(
    val id: String,
    val docId: String? = null,
    val sessionId: String? = null,
    val content: String,
    val embedding: FloatArray = FloatArray(0),
    val metadata: String? = null,
    val startMessageId: String? = null,
    val endMessageId: String? = null,
    val createdAt: Long,
    val similarity: Float,
    val originalSimilarity: Float? = null,
    val documentContentHash: String? = null,
    val fileUuid: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SearchResult
        if (id != other.id) return false
        if (!embedding.contentEquals(other.embedding)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}

data class ResolvedDocumentSearch(
    val vector: List<SearchResult>,
    val keywords: List<SearchResult>,
    val fused: List<SearchResult>,
)

@Serializable
data class RagConfiguration(
    val enableMemory: Boolean = true,
    val enableDocs: Boolean = true,
    val enableKnowledgeGraph: Boolean = false,
    val enableQueryRewrite: Boolean = false,
    val enableHybridSearch: Boolean = true,
    val enableRerank: Boolean = true,  // 默认开启：用户通过会话级 toggle 控制，此处为全局配置门
    val enableIncrementalHash: Boolean = true,
    val enableLocalPreprocess: Boolean = false,
    val currentPreset: String? = null,
    val queryRewriteStrategy: String = "multi-query",
    val queryRewriteModel: String? = null,
    val queryRewriteCount: Int = 3,
    val memoryLimit: Int = 5,
    val memoryThreshold: Float = 0.7f,
    val docLimit: Int = 8,
    val docThreshold: Float = 0.45f,
    val docChunkSize: Int = 800,
    val chunkOverlap: Int = 100,
    val memoryChunkSize: Int = 1000,
    val rerankTopK: Int = 30,
    val rerankFinalK: Int = 5,
    val hybridAlpha: Float = 0.6f,
    val hybridBM25Boost: Float = 1.0f,
    val kgExtractionModel: String? = null,
    val kgExtractionPrompt: String? = null,
    val kgEntityTypes: List<String> = emptyList(),
    val kgFreeMode: Boolean = false,
    val kgDomainAuto: Boolean = false,
    val kgDomainHint: String? = null,
    /** 每次 KG 请求的超时（秒）。 */
    val kgExtractionTimeoutSeconds: Int = 120,
    val kgRetrievalHops: Int = 1,
    val kgMaxSeeds: Int = 12,
    val kgMaxNodes: Int = 64,
    val kgMaxEdges: Int = 24,
    val kgContextMaxTokens: Int = 2048,
    val kgJitFallbackEnabled: Boolean = true,
    /** 每轮回复后从本轮问答抽取知识图谱；每轮额外调用一次模型，默认关闭。 */
    val kgSessionExtractionEnabled: Boolean = false,
    /** 每轮后台抽取事件与可变状态，默认关闭。 */
    val memoryStateExtractionEnabled: Boolean = false,
    val kgJitMinSeeds: Int = 2,
    val kgWindowChars: Int = 16000,
    val kgConcurrency: Int = 4,
    val kgGleaningPasses: Int = 1,
    val kgMaxCalls: Int = 64,
    val kgMaxOutputTokens: Int = 262144,
    val kgRequestMaxOutputTokens: Int = 16384,
    val kgMaxDurationSeconds: Int = 600,
    val kgLargeDocumentConfirmCalls: Int = 16,
    val costStrategy: String = "on-demand",
    val trackRetrievalMetrics: Boolean = false,
    val showRetrievalProgress: Boolean = true,
    val showRetrievalDetails: Boolean = true,
    val jitMaxChunks: Int = 0,
    val jitTimeoutMs: Long = 5000,
    val jitMaxCharsPerChunk: Int = 2000,
    val jitCacheTTL: Long = 3600,
    val summaryTemplate: String = "System: You are given the following retrieved chunks:\n\n{retrieved_chunks}\n\nBased on these, answer the user query.\n\nUser Query: {query}",
    // === Rerank 模型设置 ===
    /** Rerank 模型单次调用最大文档数，超过则自动分批。
     *  大多数 rerank 模型限制在 32-100 篇之间；默认 100 适应主流服务。 */
    val rerankMaxPerCall: Int = 100
)

@Serializable
data class EmbeddingResult(
    val embeddings: List<FloatArray>,
    val usage: EmbeddingUsage? = null
)

@Serializable
data class EmbeddingUsage(
    val totalTokens: Int
)

@Serializable
data class RewriteResult(
    val variants: List<String>,
    val usage: RewriteUsage? = null
)

@Serializable
data class RewriteUsage(
    val input: Int,
    val output: Int,
    val total: Int
)

@Serializable
enum class RewriteStrategy {
    HYDE, MULTI_QUERY, EXPANSION
}

@Serializable
data class KgNode(
    val id: String,
    val name: String,
    val type: String = "concept",
    val metadata: String? = null,
    val sourceType: String = "full",
    val createdAt: Long
)

@Serializable
data class KgEdge(
    val id: String,
    val sourceId: String,
    val targetId: String,
    val relation: String,
    val weight: Double = 1.0,
    val docId: String? = null,
    val sourceType: String = "full",
    val createdAt: Long,
    val documentIds: List<String> = emptyList(),
)

@Serializable
enum class KgExtractionStatus { SUCCESS, PARTIAL, PARSE_FAILURE, TIMEOUT, CANCELLED, PROVIDER_ERROR }

@Serializable
data class ExtractionResult(
    val nodes: List<ExtractedNode>,
    val edges: List<ExtractedEdge>,
    val error: String? = null,
    val status: KgExtractionStatus = if (error == null) KgExtractionStatus.SUCCESS else KgExtractionStatus.PROVIDER_ERROR,
    val progress: KgExtractionProgress? = null,
    val usage: KgTokenUsage = KgTokenUsage(),
) {
    val isSuccess: Boolean get() = status in setOf(KgExtractionStatus.SUCCESS, KgExtractionStatus.PARTIAL) && error == null

    companion object {
        fun failure(status: KgExtractionStatus, message: String) =
            ExtractionResult(emptyList(), emptyList(), message, status)
    }
}

@Serializable
data class ExtractedNode(
    val name: String,
    val type: String,
    val metadata: String? = null,
    val windowIndex: Int? = null,
    val evidence: String? = null,
    val charStart: Int? = null,
    val charEnd: Int? = null,
    val positionCoarse: Boolean = false,
)

@Serializable
data class ExtractedEdge(
    val source: String,
    val target: String,
    val relation: String,
    val weight: Double = 1.0,
    val sourceType: String? = null,
    val targetType: String? = null,
    val windowIndex: Int? = null,
    val evidence: String? = null,
    val charStart: Int? = null,
    val charEnd: Int? = null,
    val positionCoarse: Boolean = false,
    val kind: String? = null,
    val family: String? = null,
    val flashback: Boolean = false,
)

@Serializable
data class MicroGraphResult(
    val nodes: List<ExtractedNode>,
    val edges: List<ExtractedEdge>,
    val context: String,
    val sourceChunkIds: List<String>,
    val query: String,
    val extractedAt: Long,
    val status: KgExtractionStatus = KgExtractionStatus.SUCCESS,
    val error: String? = null,
    val sourceDocumentIds: List<String> = emptyList(),
    /** 本次是否实际请求了抽取模型；命中缓存或无可抽取文本时为 false，不写入缓存。 */
    @kotlinx.serialization.Transient val modelCalled: Boolean = false,
) {
    val isSuccess: Boolean get() = status == KgExtractionStatus.SUCCESS && error == null

    companion object {
        fun failure(status: KgExtractionStatus, message: String, query: String) =
            MicroGraphResult(emptyList(), emptyList(), "", emptyList(), query, System.currentTimeMillis(), status, message)
    }
}

@Serializable
data class VectorizationTask(
    val id: String,
    val type: String,
    val docId: String? = null,
    val docTitle: String? = null,
    val workspaceRootUuid: String? = null,
    val sessionId: String? = null,
    val userContent: String? = null,
    val aiContent: String? = null,
    val userMessageId: String? = null,
    val assistantMessageId: String? = null,
    var status: String = "pending",
    var progress: Double = 0.0,
    var totalChunks: Int? = null,
    var lastChunkIndex: Int = 0,
    var error: String? = null,
    var subStatus: String? = null,
    var kgStrategy: String? = null,
    var skipVectorization: Boolean = false,
    var sourceMimeType: String? = null,
    var contentTruncated: Boolean = false,
    val targetContentHash: String? = null,
    val targetEpoch: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class RerankResult(
    val index: Int,
    val relevanceScore: Float,
    val document: RerankDocument
)

@Serializable
data class RerankDocument(
    val text: String
)

@Serializable
data class VectorStats(
    val total: Int,
    val byType: VectorTypeStats,
    val bySession: List<SessionVectorCount>,
    val redundancyRate: Float,
    val storageSizeMb: Float
)

@Serializable
data class VectorTypeStats(
    var memory: Int = 0,
    var summary: Int = 0,
    var doc: Int = 0
)

@Serializable
data class SessionVectorCount(
    val sessionId: String,
    val count: Int
)
