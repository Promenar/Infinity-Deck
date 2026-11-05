package com.promenar.infinity.data.rag

import com.promenar.infinity.data.document.ConservativeContextTokenEstimator
import com.promenar.infinity.data.local.db.KgIdentity
import com.promenar.infinity.data.model.KgEdge
import com.promenar.infinity.data.model.KgNode
import com.promenar.infinity.data.model.KgPath

data class BudgetedKgContext(val context: String, val paths: List<KgPath>)

/** 完整关系是融合和预算的最小单位，文本只由最终快照生成。 */
object KgContextBudget {
    fun tokens(text: String): Int = ConservativeContextTokenEstimator.estimate(text)

    fun fuse(persistent: List<KgPath>, jit: List<KgPath>): List<KgPath> {
        val merged = linkedMapOf<List<String>, KgPath>()
        (persistent + jit).forEach { path ->
            val nodes = path.nodes.associateBy { it.id }
            path.edges.forEach { edge ->
                val source = nodes[edge.sourceId] ?: return@forEach
                val target = nodes[edge.targetId] ?: return@forEach
                val key = listOf(KgIdentity.normalize(source.label), KgIdentity.normalize(source.type),
                    KgIdentity.normalize(target.label), KgIdentity.normalize(target.type), KgIdentity.normalize(edge.relation)) +
                    if (path.narrativeEventOrdinal == null) emptyList() else listOf("event", path.narrativeEventOrdinal.toString(), path.narrativeEventDocId.orEmpty(),
                        path.narrativeEventWindow.toString(), path.narrativeEventOffset.toString())
                val old = merged[key]
                val incomingScore = path.score.takeIf { it > 0.0 } ?: (
                    0.35 * path.evidenceCount / (path.evidenceCount + 1.0) + 0.65 * edge.weight
                ) / path.distance.coerceAtLeast(1)
                val canonical = listOf(source.copy(id = "source", metadata = null), target.copy(id = "target", metadata = null))
                val winner = if (old == null || edge.weight > old.edges.single().weight) edge else old.edges.single()
                merged[key] = path.copy(nodes = canonical, edges = listOf(winner.copy(sourceId = "source", targetId = "target")),
                    reasoning = old?.reasoning ?: path.reasoning,
                    narrativeEventOrdinal = old?.narrativeEventOrdinal ?: path.narrativeEventOrdinal,
                    origins = ((old?.origins.orEmpty()) + path.origins).distinct().sorted(),
                    sourceDocumentIds = (old?.sourceDocumentIds.orEmpty() + path.sourceDocumentIds).distinct().sorted(),
                    score = maxOf(old?.score ?: 0.0, incomingScore),
                    evidenceCount = maxOf(old?.evidenceCount ?: 0, path.evidenceCount),
                    distance = minOf(old?.distance ?: 2, path.distance),
                    truncated = old?.truncated == true || path.truncated,
                    omittedPathCount = maxOf(old?.omittedPathCount ?: 0, path.omittedPathCount))
            }
        }
        return merged.values.sortedWith(compareBy<KgPath> { it.narrativeEventOrdinal != null }
            .thenByDescending { it.narrativeEventOrdinal ?: Int.MIN_VALUE }
            .thenByDescending { it.narrativeEventWindow ?: Int.MIN_VALUE }
            .thenByDescending { it.narrativeEventOffset ?: Int.MIN_VALUE }.thenByDescending { it.score }.thenBy { identity(it) })
    }

    fun fit(paths: List<KgPath>, maxTokens: Int): BudgetedKgContext {
        val canonical = fuse(paths, emptyList())
        val retained = mutableListOf<KgPath>()
        val inheritedOmitted = paths.maxOfOrNull { it.omittedPathCount } ?: 0
        canonical.forEach { path ->
            if (tokens(render(retained + path)) <= maxTokens.coerceAtLeast(0)) retained += path
        }
        val omitted = inheritedOmitted + canonical.size - retained.size
        val snapshots = if (omitted == 0) retained else if (retained.isEmpty()) {
            listOf(KgPath(origins = emptyList(), truncated = true, omittedPathCount = omitted))
        } else retained.map { it.copy(truncated = true, omittedPathCount = omitted) }
        return BudgetedKgContext(render(snapshots), snapshots)
    }

    fun prune(paths: List<KgPath>, maxNodes: Int, maxEdges: Int): List<KgPath> {
        val selected = mutableListOf<KgPath>()
        val identities = hashSetOf<Pair<String, String>>()
        val candidates = fuse(paths, emptyList())
        candidates.forEach { path ->
            if (selected.size >= maxEdges.coerceIn(1, 32)) return@forEach
            val added = path.nodes.map { KgIdentity.normalize(it.label) to KgIdentity.normalize(it.type) }.toSet() - identities
            if (identities.size + added.size <= maxNodes.coerceIn(2, 256)) {
                selected += path
                identities += added
            }
        }
        val omitted = candidates.size - selected.size
        return if (omitted == 0) selected else selected.map { it.copy(truncated = true, omittedPathCount = omitted) }
    }

    fun render(paths: List<KgPath>): String = paths.flatMap { path ->
        val nodes = path.nodes.associateBy(KgNode::id)
        path.edges.mapNotNull { edge ->
            val source = nodes[edge.sourceId] ?: return@mapNotNull null
            val target = nodes[edge.targetId] ?: return@mapNotNull null
            val origin = path.origins.joinToString("+") { if (it == "persistent") "持久图" else "即时抽取" }
            "- [$origin] ${if (path.narrativeEventOrdinal != null) "事件：" else ""}${label(source)} —${edge.relation.line()}→ ${label(target)}" +
                "（置信度 ${"%.2f".format(java.util.Locale.ROOT, edge.weight)}，证据 ${path.evidenceCount}，距离 ${path.distance}" +
                (path.reasoning?.let { "，${it.line()}" } ?: "") + "）"
        }
    }.joinToString("\n")

    private fun label(node: KgNode) = "${node.label.line()}（${node.type.line()}）"
    private fun String.line() = replace('\n', ' ').replace('\r', ' ')
    private fun identity(path: KgPath) = path.nodes.joinToString("\u0000") { KgIdentity.normalize(it.label) + ":" + KgIdentity.normalize(it.type) } +
        path.edges.joinToString("\u0000") { KgIdentity.normalize(it.relation) }
}
