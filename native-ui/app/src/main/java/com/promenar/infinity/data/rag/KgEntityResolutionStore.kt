package com.promenar.infinity.data.rag

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.dao.ACTIVE_KG_EVIDENCE
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

data class KgResolutionDecision(val fromNode: String, val toNode: String, val verdict: String)
data class KgResolutionLink(val id: String, val collectionId: String, val fromNode: String, val toNode: String,
                            val verdict: String, val decidedBy: String, val modelId: String?, val createdAt: Long)
data class KgResolutionRun(val calls: Int, val decided: Int, val merged: Int, val uncertain: Int)

fun interface KgResolutionJudge {
    suspend fun decide(modelId: String, pairs: List<KgResolutionPair>, maxOutputTokens: Int): List<KgResolutionDecision>
}

/** 合集内人工触发的实体解析，抽取与候选读取不会调用判定模型。 */
class KgEntityResolutionStore(private val database: InfinityDatabase, private val judge: KgResolutionJudge? = null) {
    suspend fun candidates(collectionId: String): List<KgResolutionPair> = database.withTransaction {
        val nodes = nodes(collectionId)
        val links = links(collectionId)
        val known = links.map { setOf(it.fromNode, it.toNode) }.toSet()
        val canonical = canonical(nodes, links)
        KgEntityResolution.candidates(nodes).filter { setOf(it.first.id, it.second.id) !in known && canonical[it.first.id] != canonical[it.second.id] }
    }

    suspend fun estimate(collectionId: String, maxOutputTokens: Int): KgResolutionEstimate =
        KgEntityResolution.estimate(candidates(collectionId).size, maxOutputTokens)

    suspend fun listLinks(collectionId: String): List<KgResolutionLink> = database.withTransaction {
        val rows = links(collectionId)
        val entities = database.kgNodeDao().getByCollection(collectionId).map { KgResolutionNode(it.id, collectionId, it.name, it.type) }
        val roots = canonical(entities, rows)
        rows.filter { it.verdict == "same" && roots[it.fromNode] != null && roots[it.fromNode] == roots[it.toNode] }
    }

    suspend fun canonicalIds(collectionId: String): Map<String, String> = database.withTransaction {
        val nodes = database.kgNodeDao().getByCollection(collectionId).map {
            KgResolutionNode(it.id, collectionId, it.name, it.type)
        }
        canonical(nodes, links(collectionId))
    }

    suspend fun runManual(collectionId: String, modelId: String, maxOutputTokens: Int): KgResolutionRun {
        require(modelId.isNotBlank() && maxOutputTokens > 0) { "模型和输出上限必须有效" }
        val evaluator = requireNotNull(judge) { "未配置实体判定模型" }
        val pending = candidates(collectionId)
        var calls = 0
        var decided = 0
        var merged = 0
        var uncertain = 0
        pending.chunked(50).forEach { batch ->
            val decisions = evaluator.decide(modelId, batch, maxOutputTokens)
            calls++
            val pairs = batch.map { setOf(it.first.id, it.second.id) }.toSet()
            require(decisions.size == batch.size && decisions.map { setOf(it.fromNode, it.toNode) }.distinct().size == decisions.size &&
                decisions.all { it.fromNode != it.toNode && setOf(it.fromNode, it.toNode) in pairs && it.verdict in setOf("same", "different", "uncertain") }) {
                "模型判定包含无效或重复候选"
            }
            database.withTransaction {
                decisions.forEach { decision ->
                    if (decision.verdict == "uncertain") uncertain++ else {
                        if (writeDecision(collectionId, decision.fromNode, decision.toNode, decision.verdict, "model", modelId)) {
                            decided++
                            if (decision.verdict == "same") merged++
                        }
                    }
                }
                KgTimelineStore(database).recompute(collectionId)
            }
        }
        return KgResolutionRun(calls, decided, merged, uncertain)
    }

    suspend fun setUserDecision(collectionId: String, fromNode: String, toNode: String, same: Boolean) = database.withTransaction {
        writeDecision(collectionId, fromNode, toNode, if (same) "same" else "different", "user", null)
        KgTimelineStore(database).recompute(collectionId)
    }

    internal suspend fun linkAlias(collectionId: String, fromNode: String, toNode: String) {
        writeDecision(collectionId, fromNode, toNode, "same", "alias", null)
    }

    suspend fun revoke(collectionId: String, linkId: String) = database.withTransaction {
        val link = requireNotNull(links(collectionId).firstOrNull { it.id == linkId && it.verdict == "same" }) { "合并记录不存在" }
        writeDecision(collectionId, link.fromNode, link.toNode, "different", "user", null)
        KgTimelineStore(database).recompute(collectionId)
    }

    private suspend fun nodes(collectionId: String): List<KgResolutionNode> = database.kgNodeDao().getByCollection(collectionId).map { node ->
        val metadata = runCatching { Json.parseToJsonElement(node.metadata.orEmpty()) as? JsonObject }.getOrNull()
        val aliases = (metadata?.get("aliases") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val description = (metadata?.get("description") as? JsonPrimitive)?.contentOrNull
        val quotes = database.query(SimpleSQLiteQuery(
            "SELECT DISTINCT m.quote FROM kg_edge_mentions m JOIN kg_edges e ON e.id=m.edge_id JOIN kg_evidence p ON p.id=m.evidence_id " +
                "AND p.edge_id=m.edge_id AND p.doc_id=m.doc_id AND p.task_id=m.task_id AND p.content_hash=m.content_hash " +
                "WHERE e.collection_id=? AND (e.source_id=? OR e.target_id=?) AND m.quote IS NOT NULL AND $ACTIVE_KG_EVIDENCE ORDER BY m.doc_id,m.window_index,m.char_start LIMIT 2",
            arrayOf(collectionId, node.id, node.id),
        )).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        KgResolutionNode(node.id, collectionId, node.name, node.type, description, aliases, quotes)
    }

    private fun links(collectionId: String): List<KgResolutionLink> = database.query(SimpleSQLiteQuery(
        "SELECT id,collection_id,from_node,to_node,verdict,decided_by,model_id,created_at FROM kg_entity_links WHERE collection_id=? ORDER BY decided_by='user' DESC,created_at,id",
        arrayOf(collectionId),
    )).use { c -> buildList {
        while (c.moveToNext()) add(KgResolutionLink(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
            c.getString(5), if (c.isNull(6)) null else c.getString(6), c.getLong(7)))
    } }

    private fun canonical(nodes: List<KgResolutionNode>, links: List<KgResolutionLink>): Map<String, String> {
        val positive = links.filter { it.verdict == "same" }.map { it.fromNode to it.toNode }
        val vetoes = links.filter { it.verdict == "different" && it.decidedBy == "user" }.map { it.fromNode to it.toNode }.toSet()
        return KgEntityResolution.canonicalIds(nodes, positive, vetoes)
    }

    private suspend fun writeDecision(collectionId: String, first: String, second: String, verdict: String, decidedBy: String, modelId: String?): Boolean {
        require(first != second && verdict in setOf("same", "different") && decidedBy in setOf("user", "model", "alias"))
        val a = requireNotNull(database.kgNodeDao().getRawById(first)) { "实体不存在" }
        val b = requireNotNull(database.kgNodeDao().getRawById(second)) { "实体不存在" }
        require(a.collectionId == collectionId && b.collectionId == collectionId && a.type == b.type) { "实体必须属于同一合集且类型一致" }
        val (from, to) = listOf(first, second).sorted()
        val old = links(collectionId).firstOrNull { it.fromNode == from && it.toNode == to }
        if (old?.decidedBy == "user" && decidedBy != "user") return false
        database.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO kg_entity_links(id,collection_id,from_node,to_node,verdict,decided_by,model_id,created_at) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(old?.id ?: UUID.randomUUID().toString(), collectionId, from, to, verdict, decidedBy, modelId, System.currentTimeMillis()),
        )
        return true
    }
}
