package com.promenar.infinity.domain.model

data class TokenUsage(val input: Int, val output: Int, val estimated: Boolean = false)
data class RagReference(val chunkId: String, val documentTitle: String, val snippet: String, val score: Double)
data class ToolCall(val id: String, val name: String, val arguments: String, val result: String?, val status: ToolCallStatus)

data class SearchFilters(
    val documentIds: List<String>? = null,
    val sessionId: String? = null,
    val minScore: Double = 0.0
)

data class SearchResult(
    val chunkId: String,
    val documentId: String?,
    val documentTitle: String,
    val chunkText: String,
    val score: Double,
    val source: SearchSource
)

enum class SearchSource { VECTOR, FTS, HYBRID }

data class VectorChunk(
    val index: Int,
    val text: String,
    val embedding: FloatArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VectorChunk) return false
        return index == other.index && text == other.text && (embedding?.contentEquals(other.embedding) ?: (other.embedding == null))
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + text.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        return result
    }
}

data class ProviderConfig(
    val id: String,
    val name: String,
    val protocolType: ProtocolType,
    val baseUrl: String,
    val apiKey: String,
    val defaultModel: String,
    val isEnabled: Boolean = true,
    val useBuiltInProxy: Boolean = false,
)

data class ConnectionResult(
    val success: Boolean,
    val latencyMs: Long?,
    val error: String?,
    val supported: Boolean = true,
)

data class ModelSpec(
    val id: String,
    val name: String,
    val type: ModelType,
    val capabilities: List<ModelCapability>,
    val providerId: String
)

data class KgNode(
    val id: String,
    val label: String,
    val type: String,
    val properties: Map<String, String> = emptyMap()
)

data class KgEdge(
    val id: String,
    val sourceId: String,
    val targetId: String,
    val relation: String,
    val weight: Double = 1.0
)

data class KgTriple(val subject: String, val predicate: String, val `object`: String)

data class ExtractionResult(val nodes: List<KgNode>, val edges: List<KgEdge>)

data class Folder(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val createdAt: Long = 0L
)
