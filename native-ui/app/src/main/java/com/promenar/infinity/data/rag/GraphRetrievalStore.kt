package com.promenar.infinity.data.rag

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.promenar.infinity.data.local.db.KgIdentity
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.dao.ACTIVE_KG_EVIDENCE
import com.promenar.infinity.data.model.KgEdge
import com.promenar.infinity.data.model.KgNode
import com.promenar.infinity.data.model.KgPath
import com.promenar.infinity.data.model.RagOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class KgRetrievalScope(
    val sessionId: String,
    val documentIds: List<String> = emptyList(),
    val folderIds: List<String> = emptyList(),
    val globalKnowledge: Boolean = false,
    /** 保留调用方兼容；图谱证据可见性由 collectionId 决定。 */
    val conversation: ConversationReach? = null,
    /** 已解析的文档范围，用于过滤即时抽取的输入片段。 */
    val documents: DocumentReach? = null,
    /** 保留调用方兼容；不限制绑定图谱内的存活证据。 */
    val allowConversation: Boolean = true,
    val collectionId: String = KgCollectionRepository.sessionCollectionId(sessionId),
    val restriction: String? = null,
    val asOf: KgAsOf? = null,
) {
    companion object {
        fun of(scope: ResolvedRetrievalScope) = KgRetrievalScope(
            sessionId = scope.sessionId,
            conversation = scope.graph,
            documents = scope.documents,
            collectionId = scope.graphBinding.collectionId,
            restriction = scope.graphRestriction,
        )
    }
}

sealed interface KgAsOf {
    data class Document(val docId: String) : KgAsOf
    data class Ordinal(val ordinal: Int) : KgAsOf
}

data class PersistentGraphResult(val seedCount: Int, val paths: List<KgPath>, val temporalRestricted: Boolean = false)

/** 图谱种子与关系扩展都限定在同一图谱中。 */
class GraphRetrievalStore(private val database: InfinityDatabase) {
    suspend fun retrieve(
        query: String,
        scope: KgRetrievalScope,
        ragDocumentIds: List<String> = emptyList(),
        config: RagConfiguration = RagConfiguration(),
    ): PersistentGraphResult = withContext(Dispatchers.IO) {
        if (scope.restriction != null) return@withContext PersistentGraphResult(0, emptyList())
        database.withTransaction {
            val (visibleCte, visibleArgs) = scopeQuery(scope)
            val visibleDocs = database.query(SimpleSQLiteQuery("$visibleCte SELECT id FROM visible_docs", visibleArgs.toTypedArray())).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            val articles = KgTimelineStore(database).ticks(scope.collectionId)
            val queryChapter = KgNaturalOrder.chapterNumber(query)
            val requested = when (val asOf = scope.asOf) {
                is KgAsOf.Document -> asOf.docId
                is KgAsOf.Ordinal -> requireNotNull(articles.firstOrNull { it.ordinal == asOf.ordinal }) { "篇目序号不存在" }.docId
                null -> queryChapter?.let { wanted ->
                    val matches = articles.filter { it.docId in visibleDocs && wanted in KgNaturalOrder.chapterNumbers(it.title) }
                    (if (wanted.second == "卷") matches.lastOrNull() else matches.singleOrNull())?.docId
                }
            }
            val timeline = KgTimelineStore(database).snapshot(scope.collectionId, visibleDocs, requested)
            // 即时抽取没有位置元数据，不能用于覆盖已经建立的时间线或明确的时间点。
            val temporalRestricted = timeline.knownEdgeIds.isNotEmpty() || queryChapter != null || scope.asOf != null
            val current = timeline.states.associateBy { it.edgeId }
            val events = timeline.events.groupBy { it.edgeId }
            val unresolvedChapter = scope.asOf == null && queryChapter != null && requested == null
            val allowedEdges = if (unresolvedChapter) timeline.unknownEdgeIds else current.keys + events.keys + timeline.unknownEdgeIds
            val (seedSql, seedArgs) = seedQuery(query, scope, ragDocumentIds, config.kgMaxSeeds.coerceIn(1, 32))
            val rawSeeds = database.query(SimpleSQLiteQuery(seedSql, seedArgs.toTypedArray())).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            val canonical = KgEntityResolutionStore(database).canonicalIds(scope.collectionId)
            val groups = canonical.entries.groupBy({ it.value }, { it.key })
            val seeds = rawSeeds.map { canonical[it] ?: it }.distinct()
            if (seeds.isEmpty()) return@withTransaction PersistentGraphResult(0, emptyList(), temporalRestricted)
            val nodeLimit = config.kgMaxNodes.coerceIn(2, 256)
            val edgeLimit = config.kgMaxEdges.coerceIn(1, 32)
            val acceptedNodes = seeds.take(nodeLimit).toMutableSet()
            var frontier = acceptedNodes.flatMap { groups[it] ?: listOf(it) }.distinct()
            val paths = mutableListOf<KgPath>()
            val seenEdges = hashSetOf<String>()
            val relevantDocs = articles.filter { it.docId in visibleDocs && (timeline.asOfOrdinal == null || it.ordinal <= timeline.asOfOrdinal) }.map { it.docId }
            for (distance in 1..config.kgRetrievalHops.coerceIn(1, 2)) {
                if (frontier.isEmpty()) break
                val (cte, args) = scopeQuery(scope)
                val ids = sqlList(frontier)
                val sql = "$cte SELECT e.id,e.source_id,e.target_id,e.relation,MAX(COALESCE(p.weight,0))," +
                    "COUNT(p.id)," +
                    "(0.35*COUNT(p.id)/(COUNT(p.id)+1.0)+0.65*MAX(COALESCE(p.weight,0))) AS score " +
                    "FROM kg_edges e JOIN kg_evidence p ON p.edge_id=e.id " +
                    "WHERE e.id IN (SELECT id FROM kg_edges WHERE source_id IN ($ids) UNION SELECT id FROM kg_edges WHERE target_id IN ($ids)) " +
                    "AND e.collection_id=? AND e.id IN (${sqlList(allowedEdges)}) AND ${visibleEvidence()} " +
                    "AND (e.id NOT IN (${sqlList(timeline.knownEdgeIds)}) OR p.doc_id IN (${sqlList(relevantDocs)})) GROUP BY e.id ORDER BY score DESC,e.id LIMIT ?"
                val next = linkedSetOf<String>()
                database.query(SimpleSQLiteQuery(sql, (args + listOf(scope.collectionId, edgeLimit * 8)).toTypedArray())).use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0)
                        if (!seenEdges.add(id)) continue
                        val rawSource = cursor.getString(1)
                        val rawTarget = cursor.getString(2)
                        val source = canonical[rawSource] ?: rawSource
                        val target = canonical[rawTarget] ?: rawTarget
                        val added = listOf(source, target).filterNot { it in acceptedNodes }.distinct()
                        if (acceptedNodes.size + added.size > nodeLimit) continue
                        val nodes = listOf(source, target).distinct().mapNotNull { database.kgNodeDao().getRawById(it) }
                            .filter { it.collectionId == scope.collectionId }.map {
                            // metadata 聚合跨来源，不能作为当前可见来源的描述泄漏出去。
                            KgNode(it.id, it.name, it.type)
                        }
                        if (nodes.map { it.id }.toSet().containsAll(listOf(source, target))) {
                            val knownFilter = if (id in timeline.knownEdgeIds) " AND p.doc_id IN (${sqlList(relevantDocs)})" else ""
                            val documents = database.query(SimpleSQLiteQuery(
                                "$cte SELECT DISTINCT p.doc_id FROM kg_evidence p WHERE p.edge_id=? AND p.doc_id IS NOT NULL AND ${visibleEvidence()}$knownFilter ORDER BY p.doc_id",
                                (args + id).toTypedArray(),
                            )).use { sources -> buildList { while (sources.moveToNext()) add(sources.getString(0)) } }
                            val basePath = KgPath(queryKeywords = listOf(query.take(512)), nodes = nodes,
                                edges = listOf(KgEdge(source, target, cursor.getString(3), cursor.getDouble(4))),
                                sourceDocumentIds = documents,
                                origins = listOf("persistent"), score = cursor.getDouble(6) / distance,
                                evidenceCount = cursor.getInt(5), distance = distance)
                            current[id]?.let { state ->
                                paths += basePath.copy(reasoning = timelineDescription(id, state, null, false))
                            }
                            events[id].orEmpty().take(edgeLimit).forEach { event ->
                                paths += basePath.copy(
                                    reasoning = timelineDescription(id, null, event, false), sourceDocumentIds = listOf(event.docId),
                                    narrativeEventOrdinal = event.startOrdinal, narrativeEventDocId = event.docId,
                                    narrativeEventWindow = event.windowIndex, narrativeEventOffset = event.charStart,
                                )
                            }
                            if (id in timeline.unknownEdgeIds) {
                                paths += basePath.copy(reasoning = timelineDescription(id, null, null, true))
                            }
                            acceptedNodes += added
                            added.forEach { next += groups[it] ?: listOf(it) }
                        }
                    }
                }
                frontier = next.toList()
            }
            PersistentGraphResult(seeds.size, paths.sortedWith(compareBy<KgPath> { it.narrativeEventOrdinal != null }
                .thenByDescending { it.narrativeEventOrdinal ?: Int.MIN_VALUE }
                .thenByDescending { it.narrativeEventWindow ?: Int.MIN_VALUE }
                .thenByDescending { it.narrativeEventOffset ?: Int.MIN_VALUE }).take(edgeLimit), temporalRestricted)
        }
    }

    private fun timelineDescription(id: String, state: KgTimelineCurrent?, event: KgTimelineHistory?, unknown: Boolean): String? {
        if (unknown) return "位置未知"
        if (event != null) return "事件，第 ${event.startOrdinal} 篇"
        if (state == null || state.edgeId != id) return null
        val history = state.history.joinToString("；") { previous ->
            val range = previous.endOrdinal?.let { end -> if (end == previous.startOrdinal) "${previous.startOrdinal}" else "${previous.startOrdinal}–$end" }
                ?: previous.startOrdinal.toString()
            "${previous.relation}，第 $range 篇"
        }
        val flashbacks = state.flashbacks.joinToString("；") { "倒叙：${it.relation}，第 ${it.startOrdinal} 篇" }
        return "第 ${state.startOrdinal} 篇起" + (if (history.isEmpty()) "" else "；此前：$history") +
            (if (flashbacks.isEmpty()) "" else "；$flashbacks")
    }

    internal suspend fun explainSeedQuery(query: String, scope: KgRetrievalScope): List<String> = withContext(Dispatchers.IO) {
        val (sql, args) = seedQuery(query, scope, emptyList(), 12)
        database.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN $sql", args.toTypedArray()))
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(3)) } }
    }

    suspend fun visibleDocumentIds(scope: KgRetrievalScope): Set<String> = withContext(Dispatchers.IO) {
        val (cte, args) = scopeQuery(scope)
        database.query(SimpleSQLiteQuery("$cte SELECT id FROM visible_docs", args.toTypedArray())).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }

    private fun seedQuery(query: String, scope: KgRetrievalScope, ragDocumentIds: List<String>, limit: Int): Pair<String, List<Any>> {
        val normalized = KgIdentity.normalize(query).take(512)
        val tokens = Regex("[\\p{L}\\p{N}]+").findAll(normalized).map { it.value }.filter { it.length >= 2 }.distinct().take(24).toList()
        val matches = mutableListOf("(n.normalized_name=? AND ?!='')", "(instr(n.normalized_name,?)>0 AND ?!='')", "(instr(?,n.normalized_name)>0 AND n.normalized_name!='')")
        val matchArgs = mutableListOf<Any>(normalized, normalized, normalized, normalized, normalized)
        tokens.forEach { token -> matches += "instr(n.normalized_name,?)>0"; matchArgs += token }
        val ragIds = ragDocumentIds.distinct().take(64)
        val ragMatch = "n.id IN (SELECT p.node_id FROM kg_evidence p WHERE p.doc_id IN (${placeholders(ragIds.size)}) AND ${visibleEvidence()})"
        val (cte, args) = scopeQuery(scope)
        val sql = "$cte SELECT n.id FROM kg_nodes n WHERE n.collection_id=? AND (n.id IN (SELECT p.node_id FROM kg_evidence p " +
            "WHERE p.node_id IS NOT NULL AND ${visibleEvidence()}) OR EXISTS (SELECT 1 FROM kg_edges x JOIN kg_evidence p ON p.edge_id=x.id WHERE x.collection_id=n.collection_id AND (x.source_id=n.id OR x.target_id=n.id) AND ${visibleEvidence()})) AND (${matches.joinToString(" OR ")} OR $ragMatch) " +
            "ORDER BY (n.normalized_name=?) DESC, instr(?,n.normalized_name)>0 DESC,n.normalized_name,n.type LIMIT ?"
        return sql to (args + listOf(scope.collectionId) + matchArgs + ragIds + listOf(normalized, normalized, limit))
    }

    private fun scopeQuery(scope: KgRetrievalScope): Pair<String, List<Any>> {
        val documents = scope.documents
        val docs = (if (documents is DocumentReach.Scoped) documents.documentIds.toList() else scope.documentIds).distinct()
        val folders = if (documents == null) scope.folderIds.distinct() else emptyList()
        // 标识符仅转换为十六进制字节常量，不把原始文本作为 SQL 语法，也不消耗绑定参数。
        // 完整来源保留在同一个 CTE 中，联合 JIT 依赖不因分批而被误判不可见。
        val values = if (docs.isEmpty()) "SELECT NULL WHERE 0" else "VALUES " + docs.joinToString { "(${sqlText(it)})" }
        val folderValues = if (folders.isEmpty()) "NULL" else folders.joinToString { sqlText(it) }
        val liveFiles = "SELECT w.uuid FROM workspace_files w WHERE w.in_recycle_bin=0 AND w.is_directory=0"
        val explicitLiveDocs = "SELECT d.id FROM explicit_docs d WHERE NOT EXISTS " +
            "(SELECT 1 FROM workspace_files rejected WHERE rejected.uuid=d.id AND (rejected.in_recycle_bin!=0 OR rejected.is_directory!=0))"
        val folderLiveDocs = "SELECT f.id FROM folder_tree f JOIN workspace_files w ON w.uuid=f.id WHERE w.in_recycle_bin=0 AND w.is_directory=0"
        val visibleDocs = when (documents) {
            null -> "$explicitLiveDocs UNION $folderLiveDocs UNION $liveFiles AND w.workspace_root_uuid IN " +
                "(SELECT workspace_root_uuid FROM sessions WHERE id=(SELECT session_id FROM ctx)" +
                (if (scope.globalKnowledge) " OR agent_id='__system__'" else "") + ")"
            // 与向量检索一致：未登记到工作区的旧文档同样可见。
            DocumentReach.All -> "$liveFiles UNION SELECT doc_id FROM kg_evidence WHERE doc_id IS NOT NULL AND doc_id NOT IN (SELECT uuid FROM workspace_files) " +
                "UNION SELECT doc_id FROM vectors WHERE doc_id IS NOT NULL AND file_uuid IS NULL AND stale=0 AND doc_id NOT IN (SELECT uuid FROM workspace_files)"
            is DocumentReach.Scoped -> "$explicitLiveDocs UNION $liveFiles AND w.workspace_root_uuid IN (${sqlList(documents.workspaceRootUuids)})"
        }
        val sql = "WITH RECURSIVE ctx(session_id,allow_conversation) AS (VALUES (?,?)), explicit_docs(id) AS ($values), " +
            "folder_tree(id,root) AS (SELECT uuid,workspace_root_uuid FROM workspace_files WHERE uuid IN ($folderValues) AND in_recycle_bin=0 " +
            "UNION SELECT w.uuid,w.workspace_root_uuid FROM workspace_files w JOIN folder_tree f ON w.parent_uuid=f.id AND w.workspace_root_uuid=f.root WHERE w.in_recycle_bin=0), " +
            "visible_docs(id) AS ($visibleDocs) "
        return sql to listOf(scope.sessionId, if (scope.allowConversation) 1 else 0)
    }

    private fun visibleEvidence(): String = "($ACTIVE_KG_EVIDENCE) AND " +
        "(p.session_id IS NULL OR p.doc_id IS NOT NULL OR (SELECT allow_conversation FROM ctx)=1) AND " +
        "(p.doc_id IS NULL OR p.doc_id IN (SELECT id FROM visible_docs)) AND " +
        "(p.source_type!='jit' OR NOT EXISTS (SELECT 1 FROM kg_evidence dependency " +
        "WHERE dependency.source_type='jit' AND dependency.task_id=p.task_id AND " +
        "((dependency.doc_id IS NOT NULL AND dependency.doc_id NOT IN (SELECT id FROM visible_docs)) OR " +
        "(dependency.session_id IS NOT NULL AND dependency.doc_id IS NULL AND (SELECT allow_conversation FROM ctx)=0))))"

    /** 空列表写成空子查询：`IN (NULL)` 恒为假，`NOT IN (NULL)` 也恒为假，都不能表达“无元素”。 */
    private fun sqlList(values: Collection<String>): String =
        if (values.isEmpty()) "SELECT NULL WHERE 0" else values.joinToString { sqlText(it) }

    private fun placeholders(size: Int) = if (size == 0) "NULL" else List(size) { "?" }.joinToString()

    private fun sqlText(value: String): String = buildString {
        val hex = "0123456789abcdef"
        append("CAST(X'")
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val unsigned = byte.toInt() and 255
            append(hex[unsigned ushr 4])
            append(hex[unsigned and 15])
        }
        append("' AS TEXT)")
    }
}
