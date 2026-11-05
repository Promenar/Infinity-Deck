package com.promenar.infinity.data.local.db.dao

import androidx.room.*
import com.promenar.infinity.data.local.db.entity.KgEdgeEntity
import kotlinx.coroutines.flow.Flow

private const val ACTIVE_KG_EDGE = "EXISTS (SELECT 1 FROM kg_evidence p WHERE p.edge_id = kg_edges.id AND " + ACTIVE_KG_EVIDENCE + ")"
// 关系行上的 weight 只作兼容缓存，检索排序和展示必须按当前作用域的存活证据聚合。
private const val EDGE_SELECT = "SELECT kg_edges.id, source_id, target_id, relation, created_at, normalized_relation, collection_id, COALESCE((SELECT MAX(p.weight) FROM kg_evidence p WHERE p.edge_id = kg_edges.id AND " + ACTIVE_KG_EVIDENCE
private const val EDGE_FROM = "), kg_edges.weight) AS weight FROM kg_edges WHERE "

@Dao
interface KgEdgeDao {
    @Query("SELECT COUNT(*) FROM kg_edges WHERE " + ACTIVE_KG_EDGE)
    fun observeLibraryCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(edge: KgEdgeEntity): Long

    @Update suspend fun update(edge: KgEdgeEntity)

    @Query("SELECT * FROM kg_edges WHERE collection_id = :collectionId AND source_id = :sourceId AND target_id = :targetId AND normalized_relation = :relation LIMIT 1")
    suspend fun findIdentity(collectionId: String, sourceId: String, targetId: String, relation: String): KgEdgeEntity?

    @Query("SELECT * FROM kg_edges WHERE id = :id")
    suspend fun getRawById(id: String): KgEdgeEntity?

    @Query(EDGE_SELECT + EDGE_FROM + "id = :edgeId AND " + ACTIVE_KG_EDGE)
    suspend fun getById(edgeId: String): KgEdgeEntity?

    @Query(EDGE_SELECT + EDGE_FROM + "(source_id = :nodeId OR target_id = :nodeId) AND " + ACTIVE_KG_EDGE)
    suspend fun getByNodeId(nodeId: String): List<KgEdgeEntity>

    @Query("SELECT * FROM kg_edges WHERE source_id = :nodeId OR target_id = :nodeId")
    suspend fun getRawByNodeId(nodeId: String): List<KgEdgeEntity>

    /** 为后续种子实体 1–2 跳读取提供索引入口；这里只做结构化作用域筛选。 */
    @Query(EDGE_SELECT + " AND (:filterDocuments = 0 OR p.doc_id IN (:docIds))" + EDGE_FROM + "(source_id IN (:nodeIds) OR target_id IN (:nodeIds)) AND EXISTS (SELECT 1 FROM kg_evidence p WHERE p.edge_id = kg_edges.id AND (:filterDocuments = 0 OR p.doc_id IN (:docIds)) AND " + ACTIVE_KG_EVIDENCE + ") ORDER BY weight DESC, id LIMIT :limit")
    suspend fun getBySeedIds(nodeIds: List<String>, filterDocuments: Boolean, docIds: List<String>, limit: Int): List<KgEdgeEntity>

    @Query(EDGE_SELECT + " AND p.session_id = :sessionId" + EDGE_FROM + "EXISTS (SELECT 1 FROM kg_evidence p WHERE p.edge_id = kg_edges.id AND p.session_id = :sessionId AND " + ACTIVE_KG_EVIDENCE + ")")
    suspend fun getBySessionId(sessionId: String): List<KgEdgeEntity>

    @Query(EDGE_SELECT + " AND p.doc_id IN (:docIds)" + EDGE_FROM + "EXISTS (SELECT 1 FROM kg_evidence p WHERE p.edge_id = kg_edges.id AND p.doc_id IN (:docIds) AND " + ACTIVE_KG_EVIDENCE + ")")
    suspend fun getByDocIds(docIds: List<String>): List<KgEdgeEntity>

    @Query(EDGE_SELECT + " AND p.doc_id = :docId" + EDGE_FROM + "EXISTS (SELECT 1 FROM kg_evidence p WHERE p.edge_id = kg_edges.id AND p.doc_id = :docId AND " + ACTIVE_KG_EVIDENCE + ")")
    suspend fun getByDocId(docId: String): List<KgEdgeEntity>

    @Query("SELECT DISTINCT p.doc_id AS docId, w.name AS title FROM kg_evidence p JOIN workspace_files w ON w.uuid = p.doc_id WHERE w.in_recycle_bin = 0 AND " + ACTIVE_KG_EVIDENCE + " ORDER BY title, docId")
    suspend fun getActiveDocumentOptions(): List<DocumentOptionRow>

    @Query("SELECT COUNT(*) FROM kg_edges WHERE EXISTS (SELECT 1 FROM kg_evidence p WHERE p.edge_id = kg_edges.id AND p.doc_id IN (:docIds) AND " + ACTIVE_KG_EVIDENCE + ")")
    suspend fun countByDocIds(docIds: List<String>): Int

    @Query("SELECT e.id AS id, s.name AS source, e.relation AS relation, t.name AS target FROM kg_edges e JOIN kg_nodes s ON s.id = e.source_id JOIN kg_nodes t ON t.id = e.target_id WHERE (:collectionId IS NULL OR e.collection_id=:collectionId) AND EXISTS (SELECT 1 FROM kg_evidence p WHERE p.edge_id = e.id AND p.message_id = :messageId AND " + ACTIVE_KG_EVIDENCE + ") ORDER BY e.weight DESC, e.id LIMIT :limit")
    suspend fun getRelationsForMessage(messageId: String, limit: Int, collectionId: String? = null): List<KgTurnRelation>

    @Query("SELECT COUNT(*) FROM kg_edges WHERE " + ACTIVE_KG_EDGE)
    suspend fun getCount(): Int

    @Query(EDGE_SELECT + EDGE_FROM + ACTIVE_KG_EDGE + " ORDER BY id")
    suspend fun getAll(): List<KgEdgeEntity>

    @Query(EDGE_SELECT + EDGE_FROM + "collection_id=:collectionId AND " + ACTIVE_KG_EDGE + " ORDER BY id")
    suspend fun getByCollection(collectionId: String): List<KgEdgeEntity>

    @Query("DELETE FROM kg_edges WHERE id = :edgeId")
    suspend fun deleteById(edgeId: String)

    @Query("DELETE FROM kg_edges")
    suspend fun clearAll()

    @Query("DELETE FROM kg_nodes")
    suspend fun clearNodes()

    @Query("DELETE FROM kg_jit_cache")
    suspend fun clearJitCache()

    @Query("DELETE FROM kg_collection_members")
    suspend fun clearMembers()

    @Query("DELETE FROM kg_collections WHERE id != 'shared'")
    suspend fun clearCollections()

    @Query("INSERT OR IGNORE INTO kg_collections(id, kind, title, root_ref, created_at) VALUES('shared', 'shared', '通用知识', NULL, 0)")
    suspend fun ensureSharedCollection()

    @Transaction
    suspend fun clearGraph() {
        clearAll()
        clearNodes()
        clearJitCache()
        clearMembers()
        clearCollections()
        ensureSharedCollection()
    }

    data class DocumentOptionRow(val docId: String, val title: String)
}

data class KgTurnRelation(val id: String, val source: String, val relation: String, val target: String)
