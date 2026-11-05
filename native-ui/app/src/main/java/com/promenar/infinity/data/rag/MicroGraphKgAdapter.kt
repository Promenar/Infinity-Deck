package com.promenar.infinity.data.rag

import com.promenar.infinity.data.model.KgEdge
import com.promenar.infinity.data.model.KgNode
import com.promenar.infinity.data.model.KgPath
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.ui.chat.manager.KgContextResult
import com.promenar.infinity.ui.chat.manager.KgProvider

class MicroGraphKgAdapter(
    private val extractorProvider: () -> MicroGraphExtractor,
) : KgProvider {
    constructor(microGraphExtractor: MicroGraphExtractor) : this({ microGraphExtractor })

    override suspend fun extractContext(
        query: String,
        sessionId: String,
        topKResults: List<RagReference>
    ): KgContextResult? = extractForGraph(query, sessionId, topKResults, null)

    internal suspend fun extractForGraph(
        query: String,
        sessionId: String,
        topKResults: List<RagReference>,
        collectionId: String?,
    ): KgContextResult? {
        val searchResults = topKResults.map { ref ->
            SearchResult(
                id = ref.id,
                docId = ref.documentId,
                documentContentHash = ref.documentContentHash,
                content = ref.content,
                similarity = ref.score,
                createdAt = UNKNOWN_CREATED_AT_SENTINEL
            )
        }
        val extractor = extractorProvider()
        val result = extractor.extract(
            topKResults = searchResults,
            query = query,
            sessionId = sessionId,
            expectedCollectionId = collectionId,
        )
        if (!result.isSuccess) return KgContextResult(context = "", status = result.status, error = result.error)
        val graph = ValidatedGraph.from(result.nodes, result.edges)
        val validated = result.copy(nodes = graph.nodes, edges = graph.edges, context = graph.context)
        return KgContextResult(
            context = validated.context,
            paths = validated.toKgPaths(),
            jitExtractionCount = if (result.modelCalled) 1 else 0,
            jitModel = extractor.modelId.takeIf { result.modelCalled },
        )
    }

    /**
     * 将 [MicroGraphResult] 的 nodes/edges 确定性映射为可序列化的 [KgPath] 列表。
     *
     * 映射规则（不编造任何节点/关系）：
     * 1. 节点按规范名和类型确定身份；同名不同类型时使用稳定的类型化 ID。
     * 2. 边的名称与类型必须能唯一确定端点；悬挂边和有歧义的边丢弃，自环保留。
     * 3. 去重：仅去重完全相同（含 weight）的边；权重不同的并行边全部保留。KgEdge 是含 weight 的 data class，
     *    LinkedHashSet 天然按全字段去重，保留首次出现。
     * 4. 规范化排序：nodes/edges 按明确 comparator 排序，输入重排得到完全相同结果。
     * 5. 弱连通分量：按无向连通性把存活边拆成多个稳定 KgPath（按各分量最小节点 id 排序）。
     * 6. 空图/无边存活 → emptyList()（无关系即无路径）；孤立节点（不参与任何存活边）丢弃。
     * 7. queryKeywords 取真实非空 result.query，绝不臆造；reasoning 始终为 null。
     */
    private fun MicroGraphResult.toKgPaths(): List<KgPath> {
        if (edges.isEmpty()) return emptyList()

        val graph = ValidatedGraph.from(nodes, edges)
        val sameNames = graph.nodes.groupingBy { ValidatedGraph.identity(it.name) }.eachCount()
        val reserved = graph.nodes.map { it.name }.toSet()
        val ids = LinkedHashMap<Pair<String, String>, String>()
        val nodeById = LinkedHashMap<String, KgNode>()
        for (n in graph.nodes) {
            val identity = ValidatedGraph.identity(n.name, n.type)
            var id = if (sameNames[identity.first] == 1) n.name else {
                "kg-jit:" + kgSha256("${identity.first.length}:${identity.first}${identity.second.length}:${identity.second}")
            }
            if (sameNames[identity.first] != 1) while (id in reserved || id in nodeById) id = "_$id"
            ids[identity] = id
            nodeById[id] = KgNode(id = id, label = n.name, type = n.type, metadata = n.metadata)
        }

        val dedupedEdges = LinkedHashSet<KgEdge>()
        for (e in graph.edges) {
            val from = graph.resolveEndpoint(e.source, e.sourceType)
            val to = graph.resolveEndpoint(e.target, e.targetType)
            if (from != null && to != null) {
                dedupedEdges.add(
                    KgEdge(
                        sourceId = ids.getValue(ValidatedGraph.identity(from.name, from.type)),
                        targetId = ids.getValue(ValidatedGraph.identity(to.name, to.type)),
                        relation = e.relation,
                        weight = e.weight
                    )
                )
            }
        }
        if (dedupedEdges.isEmpty()) return emptyList()

        val queryKeywords = if (query.isNotBlank()) listOf(query) else emptyList()

        return weaklyConnectedComponents(dedupedEdges).map { nodeIds ->
            KgPath(
                queryKeywords = queryKeywords,
                nodes = nodeIds.mapNotNull { nodeById[it] }.sortedWith(NODE_COMPARATOR),
                edges = dedupedEdges.filter { it.sourceId in nodeIds }.sortedWith(EDGE_COMPARATOR),
                reasoning = null,
                sourceDocumentIds = sourceDocumentIds,
            )
        }
    }

    /** 在无向意义上计算 [edges] 覆盖节点的弱连通分量，按各分量最小节点 id 稳定排序返回。 */
    private fun weaklyConnectedComponents(edges: Set<KgEdge>): List<Set<String>> {
        val parent = HashMap<String, String>()

        fun find(x: String): String {
            var root = x
            while (parent[root] != null && parent[root] != root) {
                root = parent[root]!!
            }
            var cur = x
            while (parent[cur] != null && parent[cur] != root) {
                val next = parent[cur]!!
                parent[cur] = root
                cur = next
            }
            return root
        }

        for (e in edges) {
            parent.putIfAbsent(e.sourceId, e.sourceId)
            parent.putIfAbsent(e.targetId, e.targetId)
            val ra = find(e.sourceId)
            val rb = find(e.targetId)
            if (ra != rb) parent[ra] = rb
        }

        val groups = LinkedHashMap<String, MutableSet<String>>()
        for (n in parent.keys) {
            val root = find(n)
            groups.getOrPut(root) { linkedSetOf() }.add(n)
        }
        return groups.values.sortedBy { it.min() }.map { it.toSet() }
    }

    private companion object {
        /** RagReference 无时间字段，未知时间使用 0L 哨兵，绝不使用墙上时钟。 */
        const val UNKNOWN_CREATED_AT_SENTINEL = 0L

        val NODE_COMPARATOR: Comparator<KgNode> =
            compareBy({ it.id }, { it.label }, { it.type }, { it.metadata })
        val EDGE_COMPARATOR: Comparator<KgEdge> =
            compareBy({ it.sourceId }, { it.targetId }, { it.relation }, { it.weight })
    }
}
