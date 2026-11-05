package com.promenar.infinity.data.local.db.dao

import androidx.room.*
import com.promenar.infinity.data.local.db.entity.KgNodeEntity
import kotlinx.coroutines.flow.Flow

private const val ACTIVE_KG_NODE = "(EXISTS (SELECT 1 FROM kg_evidence p WHERE p.node_id = kg_nodes.id AND " + ACTIVE_KG_EVIDENCE + ") OR " +
    "EXISTS (SELECT 1 FROM kg_edges e JOIN kg_evidence p ON p.edge_id = e.id WHERE (e.source_id = kg_nodes.id OR e.target_id = kg_nodes.id) AND " + ACTIVE_KG_EVIDENCE + "))"

@Dao
interface KgNodeDao {
    @Query("SELECT COUNT(*) FROM kg_nodes WHERE " + ACTIVE_KG_NODE)
    fun observeLibraryCount(): Flow<Int>

    @Query("SELECT n.name FROM kg_nodes n WHERE EXISTS (SELECT 1 FROM kg_evidence p WHERE p.node_id=n.id AND p.doc_id=:documentId AND " + ACTIVE_KG_EVIDENCE + ") OR EXISTS (SELECT 1 FROM kg_edges e JOIN kg_evidence p ON p.edge_id=e.id WHERE (e.source_id=n.id OR e.target_id=n.id) AND p.doc_id=:documentId AND " + ACTIVE_KG_EVIDENCE + ") ORDER BY n.name ASC, n.id ASC")
    suspend fun getLibraryDocumentEntityNames(documentId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(node: KgNodeEntity): Long

    @Update suspend fun update(node: KgNodeEntity)

    @Query("SELECT * FROM kg_nodes WHERE collection_id = :collectionId AND normalized_name = :normalizedName AND type = :type LIMIT 1")
    suspend fun findIdentity(collectionId: String, normalizedName: String, type: String): KgNodeEntity?

    @Query("SELECT * FROM kg_nodes WHERE id = :nodeId")
    suspend fun getRawById(nodeId: String): KgNodeEntity?

    @Query("SELECT * FROM kg_nodes WHERE id = :nodeId AND " + ACTIVE_KG_NODE)
    suspend fun getById(nodeId: String): KgNodeEntity?

    @Query("SELECT * FROM kg_nodes WHERE normalized_name = :name AND " + ACTIVE_KG_NODE + " ORDER BY type LIMIT 1")
    suspend fun getByName(name: String): KgNodeEntity?

    @Query("SELECT * FROM kg_nodes WHERE EXISTS (SELECT 1 FROM kg_evidence p WHERE p.node_id = kg_nodes.id AND p.session_id = :sessionId AND " + ACTIVE_KG_EVIDENCE + ") OR EXISTS (SELECT 1 FROM kg_edges e JOIN kg_evidence p ON p.edge_id=e.id WHERE (e.source_id=kg_nodes.id OR e.target_id=kg_nodes.id) AND p.session_id=:sessionId AND " + ACTIVE_KG_EVIDENCE + ")")
    suspend fun getBySessionId(sessionId: String): List<KgNodeEntity>

    @Query("SELECT * FROM kg_nodes WHERE (:collectionId IS NULL OR collection_id=:collectionId) AND (EXISTS (SELECT 1 FROM kg_evidence p WHERE p.node_id = kg_nodes.id AND p.message_id = :messageId AND " + ACTIVE_KG_EVIDENCE + ") OR EXISTS (SELECT 1 FROM kg_edges e JOIN kg_evidence p ON p.edge_id=e.id WHERE (e.source_id=kg_nodes.id OR e.target_id=kg_nodes.id) AND p.message_id=:messageId AND " + ACTIVE_KG_EVIDENCE + ")) ORDER BY name LIMIT :limit")
    suspend fun getForMessage(messageId: String, limit: Int, collectionId: String? = null): List<KgNodeEntity>

    @Query("SELECT * FROM kg_nodes WHERE " + ACTIVE_KG_NODE + " ORDER BY id")
    suspend fun getAll(): List<KgNodeEntity>

    @Query("SELECT * FROM kg_nodes WHERE collection_id=:collectionId AND " + ACTIVE_KG_NODE + " ORDER BY id")
    suspend fun getByCollection(collectionId: String): List<KgNodeEntity>

    @Query("SELECT * FROM kg_nodes WHERE id IN (:nodeIds) AND " + ACTIVE_KG_NODE)
    suspend fun getByIds(nodeIds: List<String>): List<KgNodeEntity>

    @Query("SELECT * FROM kg_nodes WHERE EXISTS (SELECT 1 FROM kg_evidence p WHERE p.node_id = kg_nodes.id AND p.doc_id IN (:docIds) AND " + ACTIVE_KG_EVIDENCE + ") OR EXISTS (SELECT 1 FROM kg_edges e JOIN kg_evidence p ON p.edge_id=e.id WHERE (e.source_id=kg_nodes.id OR e.target_id=kg_nodes.id) AND p.doc_id IN (:docIds) AND " + ACTIVE_KG_EVIDENCE + ")")
    suspend fun getByDocIds(docIds: List<String>): List<KgNodeEntity>

    @Query("DELETE FROM kg_nodes WHERE id = :nodeId")
    suspend fun deleteById(nodeId: String)

    @Query("SELECT COUNT(*) FROM kg_nodes WHERE " + ACTIVE_KG_NODE)
    suspend fun getCount(): Int

    @Query("DELETE FROM kg_nodes")
    suspend fun clearAll()
}
