package com.promenar.infinity.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import com.promenar.infinity.data.local.db.entity.KgEvidenceEntity
import com.promenar.infinity.data.local.db.entity.KgJitCacheSourceEntity

// 已知工作区文档必须存活且版本相符；legacy 没有版本时按来源存活状态读取；
// 会话图谱抽取的证据要求所属助手消息仍存在，删除聊天记录即不可见，撤销后恢复。
internal const val ACTIVE_KG_EVIDENCE = "((p.message_id IS NULL OR EXISTS (SELECT 1 FROM messages m WHERE m.id = p.message_id)) AND (p.doc_id IS NULL OR NOT EXISTS (SELECT 1 FROM workspace_files discarded WHERE discarded.uuid = p.doc_id AND (discarded.in_recycle_bin != 0 OR discarded.is_directory != 0))) AND (p.workspace_document = 0 OR EXISTS (SELECT 1 FROM workspace_files w WHERE w.uuid = p.doc_id AND w.in_recycle_bin = 0 AND (p.content_hash = '' OR p.content_hash = w.hash))) AND (p.source_type != 'jit' OR NOT EXISTS (SELECT 1 FROM kg_evidence dependency WHERE dependency.source_type = 'jit' AND dependency.task_id = p.task_id AND dependency.workspace_document = 1 AND NOT EXISTS (SELECT 1 FROM workspace_files input WHERE input.uuid = dependency.doc_id AND input.in_recycle_bin = 0 AND (dependency.content_hash = '' OR input.hash = dependency.content_hash)))))"

@Dao
interface KgEvidenceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(evidence: KgEvidenceEntity)

    @Query("UPDATE kg_evidence SET weight = MAX(COALESCE(weight, 0), :weight) WHERE id = :id")
    suspend fun increaseWeight(id: String, weight: Double)

    @Query("UPDATE kg_evidence SET weight = :weight WHERE edge_id = :edgeId")
    suspend fun setEdgeWeights(edgeId: String, weight: Double)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCacheSources(sources: List<KgJitCacheSourceEntity>)

    @Query("SELECT * FROM kg_evidence WHERE edge_id IN (:edgeIds)")
    suspend fun getForEdges(edgeIds: List<String>): List<KgEvidenceEntity>

    @Query("SELECT p.* FROM kg_evidence p WHERE p.edge_id IN (:edgeIds) AND " + ACTIVE_KG_EVIDENCE)
    suspend fun getActiveForEdges(edgeIds: List<String>): List<KgEvidenceEntity>

    @Query("SELECT * FROM kg_evidence WHERE node_id = :nodeId")
    suspend fun getForNode(nodeId: String): List<KgEvidenceEntity>

    @Query("SELECT p.* FROM kg_evidence p LEFT JOIN kg_nodes n ON n.id=p.node_id LEFT JOIN kg_edges e ON e.id=p.edge_id WHERE p.doc_id = :docId AND (p.source_type IN ('document','legacy') OR (p.source_type = 'jit' AND p.session_id IS NULL)) AND COALESCE(n.collection_id,e.collection_id) != :targetId")
    suspend fun relocatableDocumentEvidence(docId: String, targetId: String): List<KgEvidenceEntity>

    @Query("SELECT p.* FROM kg_evidence p LEFT JOIN kg_nodes n ON n.id=p.node_id LEFT JOIN kg_edges e ON e.id=p.edge_id WHERE p.message_id=:messageId AND COALESCE(n.collection_id,e.collection_id)=:collectionId")
    suspend fun evidenceForMessageInCollection(messageId: String, collectionId: String): List<KgEvidenceEntity>

    @Query("SELECT p.* FROM kg_evidence p LEFT JOIN kg_nodes n ON n.id=p.node_id LEFT JOIN kg_edges e ON e.id=p.edge_id WHERE p.session_id=:sessionId AND p.source_type IN ('session','jit') AND COALESCE(n.collection_id,e.collection_id)=:collectionId")
    suspend fun evidenceForSessionInCollection(sessionId: String, collectionId: String): List<KgEvidenceEntity>

    @Query("DELETE FROM kg_evidence WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    @Query("DELETE FROM kg_collection_members WHERE doc_id=:docId AND NOT EXISTS (SELECT 1 FROM kg_evidence p WHERE p.doc_id=:docId AND (p.source_type IN ('document','legacy') OR (p.source_type='jit' AND p.session_id IS NULL)))")
    suspend fun collectDocumentMember(docId: String): Int

    @Query("DELETE FROM kg_collections WHERE id!='shared' AND NOT EXISTS (SELECT 1 FROM kg_nodes n WHERE n.collection_id=kg_collections.id) AND NOT EXISTS (SELECT 1 FROM kg_edges e WHERE e.collection_id=kg_collections.id) AND NOT EXISTS (SELECT 1 FROM kg_collection_members m WHERE m.collection_id=kg_collections.id)")
    suspend fun collectEmptyCollections(): Int

    @Query("DELETE FROM kg_jit_cache WHERE cache_key IN (SELECT cache_key FROM kg_jit_cache_sources WHERE doc_id = :docId)")
    suspend fun invalidateDocumentCache(docId: String): Int

    // 多片段联合推断无法证明单个片段独立支持关系，任一输入删除时保守撤销整次 JIT 推断。
    @Query("DELETE FROM kg_evidence WHERE doc_id = :docId OR (source_type = 'jit' AND task_id IN (SELECT task_id FROM kg_evidence WHERE source_type = 'jit' AND doc_id = :docId))")
    suspend fun deleteDocumentEvidence(docId: String): Int

    @Query("DELETE FROM kg_evidence WHERE session_id = :sessionId")
    suspend fun deleteSessionEvidence(sessionId: String): Int

    @Query("DELETE FROM kg_evidence WHERE session_id IS NOT NULL AND session_id NOT IN (:activeIds)")
    suspend fun deleteOrphanSessionEvidence(activeIds: List<String>): Int

    @Query("DELETE FROM kg_evidence WHERE doc_id IS NOT NULL AND doc_id NOT IN (:activeIds)")
    suspend fun deleteOrphanDocumentEvidence(activeIds: List<String>): Int

    @Query("DELETE FROM kg_edges WHERE NOT EXISTS (SELECT 1 FROM kg_evidence p WHERE p.edge_id = kg_edges.id)")
    suspend fun collectEdges(): Int

    @Query("DELETE FROM kg_nodes WHERE NOT EXISTS (SELECT 1 FROM kg_evidence p WHERE p.node_id = kg_nodes.id) AND NOT EXISTS (SELECT 1 FROM kg_edges e WHERE e.source_id = kg_nodes.id OR e.target_id = kg_nodes.id)")
    suspend fun collectNodes(): Int

    @Query("SELECT * FROM kg_evidence WHERE doc_id = :docId OR (source_type = 'jit' AND task_id IN (SELECT task_id FROM kg_evidence WHERE source_type = 'jit' AND doc_id = :docId))")
    suspend fun affectedByDocument(docId: String): List<KgEvidenceEntity>

    @Query("SELECT * FROM kg_evidence WHERE session_id = :sessionId")
    suspend fun affectedBySession(sessionId: String): List<KgEvidenceEntity>

    @Query("SELECT * FROM kg_evidence WHERE session_id IS NOT NULL AND session_id NOT IN (:activeIds)")
    suspend fun orphanSessionEvidence(activeIds: List<String>): List<KgEvidenceEntity>

    @Query("SELECT DISTINCT doc_id FROM kg_evidence WHERE workspace_document = 1 AND doc_id NOT IN (:activeIds)")
    suspend fun orphanWorkspaceDocumentIds(activeIds: List<String>): List<String>

    @Query("DELETE FROM kg_edges WHERE id IN (:ids) AND NOT EXISTS (SELECT 1 FROM kg_evidence p WHERE p.edge_id = kg_edges.id)")
    suspend fun collectEdgesByIds(ids: List<String>): Int

    @Query("DELETE FROM kg_nodes WHERE id IN (:ids) AND NOT EXISTS (SELECT 1 FROM kg_evidence p WHERE p.node_id = kg_nodes.id) AND NOT EXISTS (SELECT 1 FROM kg_edges e WHERE e.source_id = kg_nodes.id OR e.target_id = kg_nodes.id)")
    suspend fun collectNodesByIds(ids: List<String>): Int

    @Query("SELECT source_id FROM kg_edges WHERE id IN (:ids) UNION SELECT target_id FROM kg_edges WHERE id IN (:ids)")
    suspend fun endpointsForEdges(ids: List<String>): List<String>

    suspend fun collectAffected(rows: List<KgEvidenceEntity>): Pair<Int, Int> {
        val edgeIds = rows.mapNotNull { it.edgeId }.distinct()
        val endpoints = edgeIds.chunked(400).flatMap { endpointsForEdges(it) }
        val edges = edgeIds.chunked(400).sumOf { collectEdgesByIds(it) }
        val nodes = (rows.mapNotNull { it.nodeId } + endpoints).distinct().chunked(400).sumOf { collectNodesByIds(it) }
        return edges to nodes
    }

    @Transaction
    suspend fun clearDocument(docId: String): Pair<Int, Int> {
        val affected = affectedByDocument(docId)
        invalidateDocumentCache(docId)
        deleteDocumentEvidence(docId)
        val result = collectAffected(affected)
        collectDocumentMember(docId)
        collectEmptyCollections()
        return result
    }

    @Transaction
    suspend fun clearSession(sessionId: String) {
        val affected = affectedBySession(sessionId)
        deleteSessionEvidence(sessionId)
        collectAffected(affected)
        affected.mapNotNull { it.docId }.distinct().forEach { collectDocumentMember(it) }
        collectEmptyCollections()
    }

    /**
     * 即时抽取把同一任务的整张图归给任务内每个来源，因此只要任务用过本会话的对话记忆，
     * 该任务的全部即时证据（含文档来源）都随对话记录删除；即时证据可在下次检索时重新抽取。
     */
    @Query("SELECT * FROM kg_evidence WHERE source_type = 'jit' AND task_id IN (SELECT task_id FROM kg_evidence WHERE source_type = 'jit' AND session_id = :sessionId AND doc_id IS NULL)")
    suspend fun affectedBySessionMemoryJit(sessionId: String): List<KgEvidenceEntity>

    @Query("DELETE FROM kg_evidence WHERE source_type = 'jit' AND task_id IN (SELECT task_id FROM kg_evidence WHERE source_type = 'jit' AND session_id = :sessionId AND doc_id IS NULL)")
    suspend fun deleteSessionMemoryJitEvidence(sessionId: String): Int

    @Transaction
    suspend fun clearSessionMemoryJit(sessionId: String): Pair<Int, Int> {
        val affected = affectedBySessionMemoryJit(sessionId)
        if (affected.isEmpty()) return 0 to 0
        deleteSessionMemoryJitEvidence(sessionId)
        val result = collectAffected(affected)
        collectEmptyCollections()
        return result
    }

    @Query("SELECT * FROM kg_evidence WHERE message_id = :messageId")
    suspend fun affectedByMessage(messageId: String): List<KgEvidenceEntity>

    @Query("DELETE FROM kg_evidence WHERE message_id = :messageId")
    suspend fun deleteMessageEvidence(messageId: String): Int

    /** 删除一轮会话图谱证据并回收不再有证据的实体与关系。 */
    @Transaction
    suspend fun clearMessage(messageId: String): Pair<Int, Int> {
        val affected = affectedByMessage(messageId)
        if (affected.isEmpty()) return 0 to 0
        deleteMessageEvidence(messageId)
        val result = collectAffected(affected)
        collectEmptyCollections()
        return result
    }

    // 撤销窗口内的消息仍有记忆片段引用（stale 标记后保留 24 小时），这里只清理两者都已不存在的轮次。
    @Query("SELECT * FROM kg_evidence WHERE message_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM messages m WHERE m.id = kg_evidence.message_id) AND NOT EXISTS (SELECT 1 FROM vectors v WHERE v.end_message_id = kg_evidence.message_id)")
    suspend fun orphanTurnEvidence(): List<KgEvidenceEntity>

    @Query("DELETE FROM kg_evidence WHERE message_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM messages m WHERE m.id = kg_evidence.message_id) AND NOT EXISTS (SELECT 1 FROM vectors v WHERE v.end_message_id = kg_evidence.message_id)")
    suspend fun deleteOrphanTurnEvidence(): Int

    @Transaction
    suspend fun purgeOrphanTurns(): Pair<Int, Int> {
        val affected = orphanTurnEvidence()
        if (affected.isEmpty()) return 0 to 0
        deleteOrphanTurnEvidence()
        val result = collectAffected(affected)
        collectEmptyCollections()
        return result
    }

    /** 本会话每轮会话图谱证据涉及的实体与关系数。 */
    @Query("WITH visible AS (SELECT p.message_id AS messageId,p.node_id AS nodeId,p.edge_id AS edgeId FROM kg_evidence p WHERE p.session_id=:sessionId AND p.message_id IS NOT NULL AND (:collectionId IS NULL OR p.node_id IN (SELECT id FROM kg_nodes WHERE collection_id=:collectionId) OR p.edge_id IN (SELECT id FROM kg_edges WHERE collection_id=:collectionId)) AND " + ACTIVE_KG_EVIDENCE + "), " +
        "entities AS (SELECT messageId,nodeId FROM visible WHERE nodeId IS NOT NULL UNION SELECT v.messageId,e.source_id FROM visible v JOIN kg_edges e ON e.id=v.edgeId UNION SELECT v.messageId,e.target_id FROM visible v JOIN kg_edges e ON e.id=v.edgeId), " +
        "relations AS (SELECT messageId,COUNT(DISTINCT edgeId) AS total FROM visible GROUP BY messageId) " +
        "SELECT r.messageId AS messageId,(SELECT COUNT(*) FROM entities x WHERE x.messageId=r.messageId) AS entities,r.total AS relations FROM relations r")
    fun observeTurnCounts(sessionId: String, collectionId: String? = null): Flow<List<KgTurnCount>>

    @Query("SELECT id FROM kg_collections ORDER BY id")
    fun observeCollectionIds(): Flow<List<String>>

    @Transaction
    suspend fun pruneSessions(activeIds: List<String>) {
        val affected = orphanSessionEvidence(activeIds)
        deleteOrphanSessionEvidence(activeIds)
        collectAffected(affected)
        affected.mapNotNull { it.docId }.distinct().forEach { collectDocumentMember(it) }
        collectEmptyCollections()
    }

    @Transaction
    suspend fun pruneDocuments(activeIds: List<String>): Pair<Int, Int> {
        var edges = 0
        var nodes = 0
        for (id in orphanWorkspaceDocumentIds(activeIds)) {
            val count = clearDocument(id)
            edges += count.first
            nodes += count.second
        }
        return edges to nodes
    }

    @Query("DELETE FROM kg_evidence WHERE edge_id = :edgeId")
    suspend fun deleteForEdge(edgeId: String)

    @Query("DELETE FROM kg_evidence WHERE node_id = :nodeId")
    suspend fun deleteForNode(nodeId: String)
}

data class KgTurnCount(val messageId: String, val entities: Int, val relations: Int)
