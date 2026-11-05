package com.promenar.infinity.data.rag

import androidx.room.withTransaction
import com.promenar.infinity.data.local.db.KgIdentity
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.*
import kotlinx.serialization.json.*
import java.util.UUID

/** 实体身份按图谱隔离；业务唯一约束与 Room 事务共同保证并发幂等。 */
class GraphStore(internal val database: InfinityDatabase) {
    suspend fun getGraphSubgraph(request: GraphSubgraphRequest = GraphSubgraphRequest()): GraphSubgraph =
        GraphSubgraphStore(database).load(request)

    suspend fun searchGraphEntities(request: GraphEntitySearchRequest): List<KgNode> =
        GraphSubgraphStore(database).search(request)

    /** 按最近证据时间返回最多 1,000 个会话。 */
    suspend fun getSessionOptions(collectionId: String? = null): List<KgSessionOption> = KgSessionGraphStore(database).getSessionOptions(collectionId)

    /** 返回最早的完整轮次前缀，最多 400 轮、20,000 条实体出现记录。 */
    suspend fun getSessionTurns(sessionId: String): List<KgSessionTurn> = KgSessionGraphStore(database).getSessionTurns(sessionId)

    /** 两类来源分别限额，硬上限各 100 条；非正限额返回空来源。 */
    suspend fun getEntitySources(nodeId: String, limit: Int = 20): KgEntitySources =
        KgSessionGraphStore(database).getEntitySources(nodeId, limit)

    private val nodes get() = database.kgNodeDao()
    private val edges get() = database.kgEdgeDao()
    private val evidence get() = database.kgEvidenceDao()
    private val collections get() = KgCollectionRepository(database)

    suspend fun upsertNode(name: String, type: String = "concept", metadata: String? = null,
        scope: KgScope? = null, sourceType: String = "full", collectionId: String): String = database.withTransaction {
        require(collections.collectionExists(collectionId)) { "图谱不存在" }
        putNode(collectionId, name, type, metadata, source(scope, null, sourceType))
    }

    private suspend fun putNode(collectionId: String, name: String, type: String, metadata: String?, source: KgSource): String {
        val normalized = KgIdentity.normalize(name)
        val normalizedType = KgIdentity.normalize(type).ifBlank { "concept" }
        require(normalized.isNotBlank() && normalized.length <= 512 && normalizedType.length <= 512) { "实体身份无效" }
        val now = System.currentTimeMillis()
        nodes.insert(KgNodeEntity(UUID.randomUUID().toString(), KgIdentity.display(name), normalizedType, metadata,
            now, now, normalized, collectionId))
        val stored = requireNotNull(nodes.findIdentity(collectionId, normalized, normalizedType))
        nodes.update(stored.copy(metadata = mergeMetadata(stored.metadata, metadata), updatedAt = now))
        addEvidence(source, nodeId = stored.id)
        return stored.id
    }

    suspend fun createEdge(sourceId: String, targetId: String, relation: String, docId: String? = null,
        weight: Double = 1.0, scope: KgScope? = null, sourceType: String = "full", collectionId: String): String = database.withTransaction {
        require(collections.collectionExists(collectionId)) { "图谱不存在" }
        putEdge(collectionId, sourceId, targetId, relation, weight, source(scope, docId, sourceType))
    }

    private suspend fun putEdge(collectionId: String, sourceId: String, targetId: String, relation: String, weight: Double, source: KgSource): String {
        val sourceNode = nodes.getRawById(sourceId)
        val targetNode = nodes.getRawById(targetId)
        require(sourceNode?.collectionId == collectionId && targetNode?.collectionId == collectionId) { "关系端点必须属于同一图谱" }
        val normalized = KgIdentity.normalize(relation)
        require(normalized.isNotBlank() && normalized.length <= 512 && weight.isFinite() && weight in 0.0..1.0) { "关系或权重无效" }
        edges.insert(KgEdgeEntity(UUID.randomUUID().toString(), sourceId, targetId, KgIdentity.display(relation), weight,
            System.currentTimeMillis(), normalized, collectionId))
        val stored = requireNotNull(edges.findIdentity(collectionId, sourceId, targetId, normalized))
        if (weight > stored.weight) edges.update(stored.copy(weight = weight))
        addEvidence(source, edgeId = stored.id, weight = weight)
        // 关系本身也证明两个端点存在，不能沿用其它来源的单一节点归属。
        addEvidence(source, nodeId = sourceId)
        addEvidence(source, nodeId = targetId)
        return stored.id
    }

    /** 调用方必须持有候选提交的 hash/epoch 与活动任务准入；内部仍保证整图原子写入。 */
    suspend fun commitCandidate(candidate: DocumentIndexCandidate, source: KgSource) = database.withTransaction {
        if (candidate.nodes.isEmpty() && candidate.edges.isEmpty()) return@withTransaction
        val collectionId = collections.resolveDocumentForWrite(requireNotNull(source.docId) { "文档图谱缺少文档 ID" })
        val mapping = candidate.nodes.associate { it.id to putNode(collectionId, it.name, it.type, it.metadata, source) }
        candidate.edges.forEach { edge ->
            putEdge(collectionId, requireNotNull(mapping[edge.sourceId]) { "候选关系缺少源节点" },
                requireNotNull(mapping[edge.targetId]) { "候选关系缺少目标节点" }, edge.relation, edge.weight, source)
        }
    }

    /**
     * 以一轮会话图谱抽取结果替换该轮已有证据。所属助手消息已不存在（抽取期间被删除）时不写入并返回 false。
     */
    suspend fun replaceTurn(sessionId: String, messageId: String, contentHash: String, graph: ValidatedGraph, expectedCollectionId: String? = null): Boolean =
        database.withTransaction {
            val message = database.messageDao().getById(messageId)
            if (message == null || message.sessionId != sessionId) return@withTransaction false
            val binding = collections.readBinding(sessionId)
            if (!binding.writable || (expectedCollectionId != null && expectedCollectionId != binding.collectionId)) return@withTransaction false
            collections.ensureSessionCollectionIfNeeded(sessionId, binding.collectionId)
            val previous = evidence.evidenceForMessageInCollection(messageId, binding.collectionId)
            previous.map { it.id }.chunked(400).forEach { evidence.deleteByIds(it) }
            val source = KgSource("session", sessionId = sessionId, contentHash = contentHash, messageId = messageId)
            val ids = graph.nodes.associate { ValidatedGraph.identity(it.name, it.type) to putNode(binding.collectionId, it.name, it.type, it.metadata, source) }
            graph.edges.forEach { edge ->
                val from = requireNotNull(graph.resolveEndpoint(edge.source, edge.sourceType))
                val to = requireNotNull(graph.resolveEndpoint(edge.target, edge.targetType))
                putEdge(binding.collectionId, ids.getValue(ValidatedGraph.identity(from.name, from.type)),
                    ids.getValue(ValidatedGraph.identity(to.name, to.type)), edge.relation, edge.weight, source)
            }
            evidence.collectAffected(previous)
            true
        }

    suspend fun captureJitSources(results: List<SearchResult>): List<KgJitSource> = database.withTransaction {
        results.groupBy { it.docId }.map { (docId, chunks) ->
            if (docId == null) KgJitSource(null, kgSha256(chunks.joinToString("\u0000") { it.content }), null)
            else {
                val file = database.fileEntryDao().findActiveByUuid(docId)?.takeUnless { it.inRecycleBin || it.isDirectory }
                // 无法核验文档版本时只返回即时结果，不把未知来源写成会话知识。
                val matches = file != null && chunks.all { chunk ->
                    val expected = chunk.documentContentHash ?: documentContentHashFromVectorMetadata(chunk.metadata)
                    if (expected != null) expected == file.hash else {
                        val vector = database.vectorDao().getById(chunk.id)
                        vector?.docId == docId && vector.content == chunk.content &&
                            (vector.updatedAt ?: vector.createdAt) >= file.updatedAt
                    }
                }
                KgJitSource(docId, file?.hash.orEmpty(), file?.updatedAt?.takeIf { matches })
            }
        }
    }

    /** 缓存与证据在同一事务内提交，删除、回收或版本变化后的后台结果不得重新落库。 */
    suspend fun persistJit(result: MicroGraphResult, sources: List<KgJitSource>, sessionId: String,
        cache: KgJitCacheEntity, collectionId: String): Boolean = database.withTransaction {
        val binding = collections.readBinding(sessionId)
        if (!binding.writable || binding.collectionId != collectionId) return@withTransaction false
        collections.ensureSessionCollectionIfNeeded(sessionId, collectionId)
        if (sources.isEmpty()) return@withTransaction false
        for (source in sources) {
            val docId = source.docId ?: continue
            val file = database.fileEntryDao().findActiveByUuid(docId)
            if (source.epoch == null || file == null || file.inRecycleBin || file.hash != source.contentHash || file.updatedAt != source.epoch) {
                return@withTransaction false
            }
        }
        database.kgJitCacheDao().deleteExpired(System.currentTimeMillis())
        database.kgJitCacheDao().insert(cache)
        evidence.insertCacheSources(sources.mapNotNull { source ->
            source.docId?.let { KgJitCacheSourceEntity(cache.cacheKey, it, source.contentHash) }
        })
        sources.forEach { input ->
            val source = KgSource("jit", input.docId, sessionId, input.contentHash, cache.cacheKey, input.docId != null)
            val graph = ValidatedGraph.from(result.nodes, result.edges)
            val ids = graph.nodes.associate { ValidatedGraph.identity(it.name, it.type) to putNode(collectionId, it.name, it.type, it.metadata, source) }
            graph.edges.forEach { edge ->
                val from = requireNotNull(graph.resolveEndpoint(edge.source, edge.sourceType))
                val to = requireNotNull(graph.resolveEndpoint(edge.target, edge.targetType))
                putEdge(collectionId, ids.getValue(ValidatedGraph.identity(from.name, from.type)),
                    ids.getValue(ValidatedGraph.identity(to.name, to.type)), edge.relation, edge.weight, source)
            }
        }
        true
    }

    suspend fun updateNode(id: String, updates: KgNodeUpdate) = database.withTransaction {
        val current = nodes.getById(id) ?: return@withTransaction
        require(collections.collectionExists(current.collectionId)) { "图谱不存在" }
        val name = KgIdentity.display(updates.name ?: current.name)
        val type = KgIdentity.normalize(updates.type ?: current.type)
        require(name.isNotBlank() && name.length <= 512 && type.isNotBlank() && type.length <= 512)
        val target = nodes.findIdentity(current.collectionId, KgIdentity.normalize(name), type)
        if (target != null && target.id != id) mergeInto(id, target)
        else nodes.update(current.copy(name = name, type = type, normalizedName = KgIdentity.normalize(name), updatedAt = System.currentTimeMillis()))
    }

    suspend fun mergeNodes(sourceId: String, targetName: String) = database.withTransaction {
        val source = nodes.getById(sourceId) ?: return@withTransaction
        require(collections.collectionExists(source.collectionId)) { "图谱不存在" }
        val target = nodes.findIdentity(source.collectionId, KgIdentity.normalize(targetName), source.type)
            ?: throw IllegalArgumentException("目标实体不存在或类型不一致")
        if (sourceId != target.id) mergeInto(sourceId, target)
    }

    private suspend fun mergeInto(sourceId: String, target: KgNodeEntity) {
        require(nodes.getRawById(sourceId)?.collectionId == target.collectionId) { "不能跨图谱合并实体" }
        evidence.getForNode(sourceId).forEach { copyEvidence(it, nodeId = target.id) }
        edges.getRawByNodeId(sourceId).forEach { edge ->
            val source = if (edge.sourceId == sourceId) target.id else edge.sourceId
            val destination = if (edge.targetId == sourceId) target.id else edge.targetId
            val existing = edges.findIdentity(edge.collectionId, source, destination, edge.normalizedRelation)
            if (existing == null || existing.id == edge.id) edges.update(edge.copy(sourceId = source, targetId = destination))
            else {
                evidence.getForEdges(listOf(edge.id)).forEach { copyEvidence(it, edgeId = existing.id) }
                edges.update(existing.copy(weight = maxOf(existing.weight, edge.weight)))
                edges.deleteById(edge.id)
            }
        }
        val original = nodes.getRawById(sourceId)
        nodes.update(target.copy(metadata = mergeMetadata(target.metadata, original?.metadata), updatedAt = System.currentTimeMillis()))
        nodes.deleteById(sourceId)
    }

    private suspend fun copyEvidence(row: KgEvidenceEntity, nodeId: String? = null, edgeId: String? = null) {
        addEvidence(KgSource(row.sourceType, row.docId, row.sessionId, row.contentHash, row.taskId, row.workspaceDocument, row.messageId), nodeId, edgeId, row.createdAt, row.weight)
    }

    /** 只移动已有证据，并保留身份与元数据；调用方在同一事务中删除旧证据。 */
    internal suspend fun relocateEvidence(row: KgEvidenceEntity, collectionId: String) {
        suspend fun relocateNode(originalId: String): String {
            val original = requireNotNull(nodes.getRawById(originalId))
            nodes.insert(original.copy(id = UUID.randomUUID().toString(), collectionId = collectionId))
            val target = requireNotNull(nodes.findIdentity(collectionId, original.normalizedName, original.type))
            nodes.update(target.copy(metadata = mergeMetadata(target.metadata, original.metadata)))
            return target.id
        }
        val node = row.nodeId
        if (node != null) {
            copyEvidence(row, nodeId = relocateNode(node))
        } else {
            val edge = requireNotNull(edges.getRawById(requireNotNull(row.edgeId)))
            val source = relocateNode(edge.sourceId)
            val target = relocateNode(edge.targetId)
            edges.insert(edge.copy(id = UUID.randomUUID().toString(), collectionId = collectionId, sourceId = source, targetId = target))
            val stored = requireNotNull(edges.findIdentity(collectionId, source, target, edge.normalizedRelation))
            if (edge.weight > stored.weight) edges.update(stored.copy(weight = edge.weight))
            copyEvidence(row, edgeId = stored.id)
        }
    }

    suspend fun deleteNode(id: String) = database.withTransaction {
        val attached = edges.getRawByNodeId(id)
        val affected = evidence.getForNode(id) + attached.map { it.id }.chunked(400).flatMap { evidence.getForEdges(it) }
        attached.forEach { edges.deleteById(it.id) }
        nodes.deleteById(id)
        evidence.collectNodes()
        affected.mapNotNull { it.docId }.distinct().forEach { evidence.collectDocumentMember(it) }
        evidence.collectEmptyCollections()
        Unit
    }

    suspend fun updateEdge(id: String, updates: KgEdgeUpdate) = database.withTransaction {
        val current = edges.getById(id) ?: return@withTransaction
        require(collections.collectionExists(current.collectionId)) { "图谱不存在" }
        val relation = KgIdentity.display(updates.relation ?: current.relation)
        val weight = updates.weight ?: current.weight
        require(relation.isNotBlank() && relation.length <= 512 && weight.isFinite() && weight in 0.0..1.0)
        // 手动编辑作用于这条关系当前全部来源；重新归一关系时逐条保留来源置信度。
        if (updates.weight != null) evidence.setEdgeWeights(id, weight)
        val target = edges.findIdentity(current.collectionId, current.sourceId, current.targetId, KgIdentity.normalize(relation))
        if (target != null && target.id != id) {
            evidence.getForEdges(listOf(id)).forEach { copyEvidence(it, edgeId = target.id) }
            edges.update(target.copy(weight = maxOf(target.weight, weight)))
            edges.deleteById(id)
        } else edges.update(current.copy(relation = relation, normalizedRelation = KgIdentity.normalize(relation), weight = weight))
    }

    suspend fun clearGraphForDoc(docId: String) { evidence.clearDocument(docId) }
    suspend fun deleteEdge(id: String) = database.withTransaction {
        val affected = evidence.getForEdges(listOf(id))
        edges.deleteById(id)
        evidence.collectNodes()
        affected.mapNotNull { it.docId }.distinct().forEach { evidence.collectDocumentMember(it) }
        evidence.collectEmptyCollections()
        Unit
    }
    suspend fun getAllNodes(): List<KgNode> = database.withTransaction {
        nodes.getAll().map { it.toModel() }
    }
    suspend fun getEdgesForNode(nodeId: String): List<KgEdge> = database.withTransaction {
        modelEdges(edges.getByNodeId(nodeId))
    }

    suspend fun getGraphData(docIds: List<String>? = null, sessionId: String? = null): GraphData = database.withTransaction {
        val boundCollection = sessionId?.let { collections.readBinding(it).collectionId }
        val selectedEdges = when {
            docIds != null -> docIds.distinct().chunked(400).flatMap { edges.getByDocIds(it) }
                .distinctBy { it.id }.filter { boundCollection == null || it.collectionId == boundCollection }
            boundCollection != null -> edges.getByCollection(boundCollection)
            else -> edges.getAll()
        }
        val selectedNodes = when {
            docIds != null -> docIds.distinct().chunked(400).flatMap { nodes.getByDocIds(it) }
                .distinctBy { it.id }.filter { boundCollection == null || it.collectionId == boundCollection }
            boundCollection != null -> nodes.getByCollection(boundCollection)
            else -> nodes.getAll()
        }
        GraphData(selectedNodes.map { it.toModel() }, modelEdges(selectedEdges, docIds))
    }

    suspend fun getDocumentOptions(): List<KgDocumentOption> = database.withTransaction {
        edges.getActiveDocumentOptions().map { KgDocumentOption(it.docId, it.title) }
    }

    private suspend fun modelEdges(rows: List<KgEdgeEntity>, docIds: List<String>? = null, sessionId: String? = null): List<KgEdge> {
        val sources = rows.chunked(400).flatMap { evidence.getActiveForEdges(it.map { edge -> edge.id }) }.groupBy { it.edgeId }
        return rows.map { edge ->
            val selected = sources[edge.id].orEmpty().filter { (docIds == null || it.docId in docIds) && (sessionId == null || it.sessionId == sessionId) }
            KgEdge(edge.id, edge.sourceId, edge.targetId, edge.relation, selected.mapNotNull { it.weight }.maxOrNull() ?: edge.weight,
                selected.mapNotNull { it.docId }.distinct().singleOrNull(),
                if (selected.any { it.sourceType == "document" }) "full" else selected.firstOrNull()?.sourceType ?: "legacy", edge.createdAt,
                selected.mapNotNull { it.docId }.distinct().sorted())
        }
    }

    private suspend fun addEvidence(source: KgSource, nodeId: String? = null, edgeId: String? = null, createdAt: Long = System.currentTimeMillis(), weight: Double? = null) {
        require((nodeId == null) != (edgeId == null))
        // 消息 id 只在存在时参与证据身份，既有证据的 id 保持不变。
        val key = Json.encodeToString(ListSerializer(), listOf(nodeId.orEmpty(), edgeId.orEmpty(), source.type,
            source.docId.orEmpty(), source.sessionId.orEmpty(), source.contentHash, source.taskId) + listOfNotNull(source.messageId))
        evidence.insert(KgEvidenceEntity(kgSha256(key), nodeId, edgeId, source.type, source.docId, source.sessionId,
            source.contentHash, source.taskId, createdAt, source.workspaceDocument, weight, source.messageId))
        if (weight != null) evidence.increaseWeight(kgSha256(key), weight)
    }

    private fun source(scope: KgScope?, docId: String?, type: String): KgSource {
        val document = docId ?: scope?.docId
        return KgSource(when { type == "jit" -> "jit"; document != null -> "document"; scope?.sessionId != null -> "session"; else -> "legacy" },
            document, scope?.sessionId, scope?.contentHash.orEmpty(), scope?.taskId.orEmpty(), scope?.workspaceDocument ?: false,
            scope?.messageId.takeIf { document == null })
    }

    private fun mergeMetadata(old: String?, added: String?): String {
        fun parse(value: String?) = runCatching { Json.parseToJsonElement(value.orEmpty()).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        return JsonObject(parse(old) + parse(added)).toString()
    }

    private fun KgNodeEntity.toModel() = KgNode(id, name, type, metadata, "full", createdAt)
    private fun ListSerializer() = kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>())
}

data class KgDocumentOption(val docId: String, val title: String)
data class KgSource(val type: String, val docId: String? = null, val sessionId: String? = null,
    val contentHash: String = "", val taskId: String = "", val workspaceDocument: Boolean = false,
    /** 会话证据所属轮次的助手消息；消息删除后证据不可见。 */
    val messageId: String? = null)
data class KgJitSource(val docId: String?, val contentHash: String, val epoch: Long?)
data class KgScope(val sessionId: String? = null, val agentId: String? = null, val messageId: String? = null,
    val docId: String? = null, val contentHash: String = "", val taskId: String = "", val workspaceDocument: Boolean = false)
data class KgNodeUpdate(val name: String? = null, val type: String? = null)
data class KgEdgeUpdate(val relation: String? = null, val weight: Double? = null)
data class GraphData(val nodes: List<KgNode>, val edges: List<KgEdge>)
