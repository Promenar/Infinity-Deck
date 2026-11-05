package com.promenar.infinity.data.rag

import kotlinx.serialization.json.*
import com.promenar.infinity.data.local.db.KgIdentity

/** 入库、提示词与展示路径共享的已校验图，端点统一使用规范节点的显示名。 */
class ValidatedGraph private constructor(val nodes: List<ExtractedNode>, val edges: List<ExtractedEdge>) {
    val context: String get() = edges.joinToString("\n") { "- ${it.source} --[${it.relation}]--> ${it.target}" }

    fun toExtractionResult() = ExtractionResult(nodes, edges)

    fun resolveEndpoint(name: String, type: String?): ExtractedNode? {
        val candidates = nodes.filter { identity(it.name) == identity(name) }
        return if (type.isNullOrBlank()) candidates.singleOrNull()
        else candidates.singleOrNull { identity(it.name, it.type) == identity(name, type.orEmpty()) }
    }

    companion object {
        fun from(nodes: List<ExtractedNode>, edges: List<ExtractedEdge>): ValidatedGraph {
            val canonical = nodes.asSequence().take(KgGraphValidation.MAX_RAW_NODES)
                .map { it.copy(name = clean(it.name), type = clean(it.type).ifBlank { "concept" }, metadata = it.metadata?.take(4096)) }
                .filter { it.name.isNotBlank() && it.name.length <= 512 && it.type.length <= 512 }
                .sortedWith(compareBy({ identity(it.name) }, { identityType(it.type) }, { it.metadata }, { it.name }))
                .distinctBy { identity(it.name, it.type) }
                .take(KgGraphValidation.MAX_NODES)
                .toList()
            val byName = canonical.groupBy { identity(it.name) }
            fun endpoint(name: String, type: String?): ExtractedNode? {
                val candidates = byName[identity(name)].orEmpty()
                return if (type.isNullOrBlank()) candidates.singleOrNull()
                else candidates.singleOrNull { identityType(it.type) == identityType(type.orEmpty()) }
            }
            val validEdges = edges.asSequence().take(KgGraphValidation.MAX_RAW_EDGES).mapNotNull { edge ->
                val source = endpoint(edge.source, edge.sourceType) ?: return@mapNotNull null
                val target = endpoint(edge.target, edge.targetType) ?: return@mapNotNull null
                val relation = clean(edge.relation)
                if (relation.isBlank() || relation.length > 512 || !edge.weight.isFinite() || edge.weight !in 0.0..1.0) return@mapNotNull null
                edge.copy(source = source.name, target = target.name, relation = relation,
                    sourceType = edge.sourceType?.takeUnless { it.isBlank() }?.let { identityType(source.type) },
                    targetType = edge.targetType?.takeUnless { it.isBlank() }?.let { identityType(target.type) })
            }.distinct().sortedWith(compareBy({ it.source }, { it.sourceType }, { it.target }, { it.targetType }, { it.relation }, { it.weight }))
                .take(KgGraphValidation.MAX_EDGES).toList()
            val connected = validEdges.flatMap { edge ->
                listOfNotNull(endpoint(edge.source, edge.sourceType), endpoint(edge.target, edge.targetType))
            }.map { identity(it.name, it.type) }.toSet()
            return ValidatedGraph(canonical.filter { identity(it.name, it.type) in connected }, validEdges)
        }

        fun identity(name: String): String = KgIdentity.normalize(name)
        fun identity(name: String, type: String): Pair<String, String> = identity(name) to identityType(type)
        private fun identityType(type: String) = KgIdentity.normalize(type).ifBlank { "concept" }
        private fun clean(value: String) = KgIdentity.display(value)
    }
}

object KgGraphValidation {
    const val MAX_NODES = 256
    const val MAX_EDGES = 512
    const val MAX_RAW_NODES = 1024
    const val MAX_RAW_EDGES = 4096
    private const val MAX_RESPONSE_CHARS = 1024 * 1024

    /** 分块先解析；端点在全量分块合并后校验，允许实体与引用位于相邻块。 */
    fun parse(content: String): ExtractionResult {
        if (content.length > MAX_RESPONSE_CHARS) return parseFailure()
        val first = content.indexOf('{')
        val last = content.lastIndexOf('}')
        if (first < 0 || last <= first) return parseFailure()
        return try {
            val root = Json.parseToJsonElement(content.substring(first, last + 1)).jsonObject
            val nodes = root["nodes"] as? JsonArray ?: return parseFailure()
            val edges = root["edges"] as? JsonArray ?: return parseFailure()
            ExtractionResult(
                nodes = nodes.take(MAX_RAW_NODES).mapNotNull { value ->
                    val node = value as? JsonObject ?: return@mapNotNull null
                    ExtractedNode(node.string("name"), node.string("type").ifBlank { "concept" }, node["metadata"]?.toString(),
                        windowIndex = node.int("windowIndex", "window_index"), evidence = node.quote())
                },
                edges = edges.take(MAX_RAW_EDGES).mapNotNull { value ->
                    val edge = value as? JsonObject ?: return@mapNotNull null
                    ExtractedEdge(
                        edge.string("source"), edge.string("target"), edge.string("relation"),
                        if ("weight" in edge) edge.string("weight").toDoubleOrNull() ?: Double.NaN else 1.0,
                        edge.string("sourceType").takeIf { it.isNotBlank() }, edge.string("targetType").takeIf { it.isNotBlank() },
                        windowIndex = edge.int("windowIndex", "window_index"), evidence = edge.quote(),
                        kind = edge.string("kind").takeIf { it in setOf("state", "event") },
                        family = edge.string("family").takeIf { it in KgNarrativeTimeline.families },
                        flashback = (edge["flashback"] as? JsonPrimitive)?.booleanOrNull == true,
                    )
                },
            )
        } catch (_: Exception) {
            parseFailure()
        }
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.int(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
    }
    private fun JsonObject.quote(): String? = string("evidence").takeIf { it.isNotBlank() }?.let { value ->
        val end = value.offsetByCodePoints(0, minOf(30, value.codePointCount(0, value.length)))
        value.substring(0, end)
    }
    private fun parseFailure() = ExtractionResult.failure(KgExtractionStatus.PARSE_FAILURE, "知识图谱响应不是有效的 nodes/edges JSON")
}
