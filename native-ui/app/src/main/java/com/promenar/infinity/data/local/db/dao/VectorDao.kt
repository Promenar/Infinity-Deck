package com.promenar.infinity.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.promenar.infinity.data.local.db.entity.VectorEntity
import com.promenar.infinity.data.library.LibraryChunkRow
import com.promenar.infinity.domain.library.ConversationFragment
import com.promenar.infinity.domain.library.FragmentGroup
import kotlinx.coroutines.flow.Flow

private const val ACTIVE_FILE_VECTOR = "((file_uuid IS NOT NULL AND file_uuid IN (SELECT uuid FROM workspace_files WHERE in_recycle_bin = 0)) OR (file_uuid IS NULL AND (doc_id IS NULL OR doc_id NOT IN (SELECT uuid FROM workspace_files) OR doc_id IN (SELECT uuid FROM workspace_files WHERE in_recycle_bin = 0))))"
private const val ACTIVE_FILE_VECTOR_ALIAS = "((vectors.file_uuid IS NOT NULL AND vectors.file_uuid IN (SELECT uuid FROM workspace_files WHERE in_recycle_bin = 0)) OR (vectors.file_uuid IS NULL AND (vectors.doc_id IS NULL OR vectors.doc_id NOT IN (SELECT uuid FROM workspace_files) OR vectors.doc_id IN (SELECT uuid FROM workspace_files WHERE in_recycle_bin = 0))))"


// 对话记忆按一轮写入，start/end_message_id 是该轮的用户消息与助手消息；任一消息被删除、丢弃或回退后该轮记忆不可见。
// 撤销删除以原 id 恢复消息，记忆随之可见；物理删除由孤儿清理在撤销窗口后执行。文档向量的这两列存放文档 id，不受此约束。
private const val MEMORY_ROW = "substr(COALESCE(vectors.metadata, ''), 1, 16) = '{\"type\":\"memory\"'"
private const val STATE_MEMORY_ROW = "(substr(COALESCE(vectors.metadata, ''), 1, 17) = '{\"type\":\"summary\"' OR substr(COALESCE(vectors.metadata, ''), 1, 15) = '{\"type\":\"state\"')"
private const val TURN_MEMORY_ROW = "($MEMORY_ROW OR $STATE_MEMORY_ROW)"
private const val MEMORY_TURN_PRESENT = "((vectors.start_message_id IS NULL OR EXISTS (SELECT 1 FROM messages turn_message WHERE turn_message.id = vectors.start_message_id)) AND (vectors.end_message_id IS NULL OR EXISTS (SELECT 1 FROM messages turn_message WHERE turn_message.id = vectors.end_message_id)))"
private const val MEMORY_VISIBLE = "(NOT ($TURN_MEMORY_ROW) OR $MEMORY_TURN_PRESENT)"
private const val ACTIVE_VECTOR = "($ACTIVE_FILE_VECTOR AND $MEMORY_VISIBLE)"
private const val ACTIVE_VECTOR_ALIAS = "($ACTIVE_FILE_VECTOR_ALIAS AND $MEMORY_VISIBLE)"

// 检索范围过滤（见 data/rag/RetrievalScope.kt）：对话行按所属会话的助手判定，文档行按工作区根与显式文档判定。
private const val TYPE_IS = "substr(vectors.metadata, 1, length('{\"type\":\"') + length(:type) + 1) = '{\"type\":\"' || :type || '\"'"
private const val CONVERSATION_ROW = "vectors.doc_id IS NULL AND vectors.stale = 0"
private const val SESSIONS_OF_AGENT = "vectors.session_id IN (SELECT id FROM sessions WHERE agent_id = :agentId)"
private const val SESSIONS_OF_SHARED_AGENTS = "vectors.session_id IN (SELECT id FROM sessions WHERE agent_id = :agentId OR agent_id NOT IN (:privateAgentIds))"
private const val DOCUMENT_ROW = "vectors.doc_id IS NOT NULL AND vectors.stale = 0"
private const val DOCUMENT_IN_SCOPE = "(vectors.doc_id IN (:documentIds) OR EXISTS (SELECT 1 FROM workspace_files AS scope_file WHERE scope_file.workspace_root_uuid IN (:rootUuids) AND scope_file.in_recycle_bin = 0 AND scope_file.uuid = CASE WHEN vectors.file_uuid IS NOT NULL THEN vectors.file_uuid ELSE vectors.doc_id END))"
private const val FTS_JOIN = "SELECT vectors.* FROM vectors_fts JOIN vectors ON vectors.rowid = vectors_fts.rowid WHERE vectors_fts MATCH :query AND "

@Dao
interface VectorDao {
    @Query("SELECT COUNT(*) FROM vectors WHERE " + MEMORY_ROW + " AND " + ACTIVE_VECTOR_ALIAS)
    fun observeLibraryMemoryCount(): Flow<Int>

    @Query("""SELECT vectors.session_id AS sessionId, sessions.title AS sessionTitle, agents.name AS agentName,
        COUNT(*) AS fragmentCount, MAX(vectors.created_at) AS lastAt
        FROM vectors LEFT JOIN sessions ON sessions.id = vectors.session_id
        LEFT JOIN agents ON agents.id = sessions.agent_id
        WHERE vectors.session_id IS NOT NULL AND """ + MEMORY_ROW + " AND " + ACTIVE_VECTOR_ALIAS +
        " GROUP BY vectors.session_id, sessions.title, agents.name ORDER BY lastAt DESC, sessionId ASC")
    fun observeLibraryFragmentGroups(): Flow<List<FragmentGroup>>

    @Query("""SELECT vectors.id AS id, vectors.session_id AS sessionId, vectors.content AS content,
        vectors.created_at AS createdAt FROM vectors WHERE vectors.session_id = :sessionId AND """ +
        MEMORY_ROW + " AND " + ACTIVE_VECTOR_ALIAS +
        " ORDER BY vectors.created_at DESC, vectors.id DESC LIMIT 100")
    fun observeLibraryFragments(sessionId: String): Flow<List<ConversationFragment>>

    @Query("SELECT session_id FROM vectors WHERE id = :id AND " + MEMORY_ROW + " LIMIT 1")
    suspend fun getLibraryFragmentSession(id: String): String?

    @Query("DELETE FROM vectors WHERE id = :id AND " + MEMORY_ROW)
    suspend fun deleteLibraryFragment(id: String): Int

    @Query("DELETE FROM vectors WHERE session_id = :sessionId AND " + MEMORY_ROW)
    suspend fun clearLibraryFragments(sessionId: String): Int

    @Query("""SELECT id, content, metadata, created_at AS createdAt FROM vectors
        WHERE stale = 0 AND (file_uuid = :documentId OR (file_uuid IS NULL AND doc_id = :documentId))
        ORDER BY created_at, id""")
    suspend fun getLibraryDocumentChunks(documentId: String): List<LibraryChunkRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vector: VectorEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(vectors: List<VectorEntity>)

    @Delete
    suspend fun delete(vector: VectorEntity)

    @Query("DELETE FROM vectors WHERE id = :vectorId")
    suspend fun deleteById(vectorId: String)

    @Query("DELETE FROM vectors WHERE doc_id = :docId")
    suspend fun deleteByDocId(docId: String)

    @Query("DELETE FROM vectors WHERE session_id = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)

    @Query("DELETE FROM vectors")
    suspend fun deleteAll()

    @Query("DELETE FROM vectors WHERE session_id IS NOT NULL AND session_id NOT IN (:activeSessionIds)")
    suspend fun deleteOrphanBySessionIds(activeSessionIds: List<String>)

    @Query("DELETE FROM vectors WHERE session_id IS NOT NULL AND json_extract(metadata, '$.type') = 'memory'")
    suspend fun deleteAllMemoryVectors()

    @Query("DELETE FROM vectors WHERE session_id = :sessionId AND json_extract(metadata, '$.type') = 'memory' AND start_message_id >= :startMsgId AND end_message_id <= :endMsgId")
    suspend fun deleteMemoryRange(sessionId: String, startMsgId: String, endMsgId: String): Int

    @Query("SELECT * FROM vectors WHERE id = :vectorId AND " + ACTIVE_VECTOR)
    suspend fun getById(vectorId: String): VectorEntity?

    @Query("SELECT * FROM vectors WHERE doc_id = :docId AND " + ACTIVE_VECTOR)
    suspend fun getByDocId(docId: String): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE session_id = :sessionId AND " + ACTIVE_VECTOR)
    suspend fun getBySessionId(sessionId: String): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE " + ACTIVE_VECTOR)
    suspend fun getAll(): List<VectorEntity>

    @Query("""
        SELECT vectors.* FROM vectors
        WHERE vectors.stale = 0
          AND EXISTS (
            SELECT 1 FROM workspace_files AS file
            WHERE file.workspace_root_uuid = :workspaceRootUuid
              AND file.in_recycle_bin = 0
              AND file.uuid = CASE
                  WHEN vectors.file_uuid IS NOT NULL THEN vectors.file_uuid
                  ELSE vectors.doc_id
              END
        )
    """)
    suspend fun getByWorkspaceRoot(workspaceRootUuid: String): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE doc_id IN (:docIds) AND " + ACTIVE_VECTOR)
    suspend fun getByDocIds(docIds: List<String>): List<VectorEntity>

    // 向量 metadata 由应用统一编码为以根字段 type 开头的紧凑 JSON；使用标准 substr
    // 避免依赖并非所有 SQLite runtime 都编译启用的 JSON1 扩展。
    @Query("SELECT * FROM vectors WHERE substr(metadata, 1, length('{\"type\":\"') + length(:type) + 1) = '{\"type\":\"' || :type || '\"' AND " + ACTIVE_VECTOR)
    suspend fun getByType(type: String): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE session_id = :sessionId AND substr(metadata, 1, length('{\"type\":\"') + length(:type) + 1) = '{\"type\":\"' || :type || '\"' AND " + ACTIVE_VECTOR)
    suspend fun getBySessionIdAndType(sessionId: String, type: String): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE substr(metadata, 1, length('{\"type\":\"') + length(:type) + 1) = '{\"type\":\"' || :type || '\"' AND doc_id IN (:docIds) AND " + ACTIVE_VECTOR)
    suspend fun getByTypeAndDocIds(type: String, docIds: List<String>): List<VectorEntity>

    @Query("SELECT vectors.* FROM vectors_fts JOIN vectors ON vectors.rowid = vectors_fts.rowid WHERE vectors_fts MATCH :query AND " + ACTIVE_VECTOR_ALIAS)
    suspend fun searchFts(query: String): List<VectorEntity>

    @Query("""
        SELECT vectors.*
        FROM vectors_fts
        JOIN vectors ON vectors.rowid = vectors_fts.rowid
        WHERE vectors_fts MATCH :query
          AND vectors.stale = 0
          AND EXISTS (
              SELECT 1 FROM workspace_files AS file
              WHERE file.workspace_root_uuid = :workspaceRootUuid
                AND file.in_recycle_bin = 0
                AND file.uuid = CASE
                    WHEN vectors.file_uuid IS NOT NULL THEN vectors.file_uuid
                    ELSE vectors.doc_id
                END
          )
    """)
    suspend fun searchFtsByWorkspaceRoot(
        query: String,
        workspaceRootUuid: String,
    ): List<VectorEntity>

    @Query("SELECT vectors.* FROM vectors_fts JOIN vectors ON vectors.rowid = vectors_fts.rowid WHERE vectors_fts MATCH :query AND vectors.session_id = :sessionId AND " + ACTIVE_VECTOR_ALIAS)
    suspend fun searchFtsBySession(query: String, sessionId: String): List<VectorEntity>

    @Query("SELECT vectors.* FROM vectors_fts JOIN vectors ON vectors.rowid = vectors_fts.rowid WHERE vectors_fts MATCH :query AND vectors.doc_id IN (:docIds) AND " + ACTIVE_VECTOR_ALIAS)
    suspend fun searchFtsByDocIds(query: String, docIds: List<String>): List<VectorEntity>

    @Query("SELECT vectors.* FROM vectors_fts JOIN vectors ON vectors.rowid = vectors_fts.rowid WHERE vectors_fts MATCH :query AND vectors.doc_id IS NULL AND " + MEMORY_VISIBLE)
    suspend fun searchFtsExcludeDocs(query: String): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE content LIKE '%' || :keyword || '%' AND " + ACTIVE_VECTOR)
    suspend fun searchByKeyword(keyword: String): List<VectorEntity>

    @Query("SELECT COUNT(*) FROM vectors WHERE " + ACTIVE_VECTOR)
    suspend fun getCount(): Int

    @Query("SELECT json_extract(metadata, '$.type') as type, COUNT(*) as count FROM vectors WHERE metadata IS NOT NULL AND " + ACTIVE_VECTOR + " GROUP BY type")
    suspend fun countByType(): List<TypeCount>

    @Query("SELECT session_id, COUNT(*) as count FROM vectors WHERE session_id IS NOT NULL AND " + ACTIVE_VECTOR + " GROUP BY session_id ORDER BY count DESC LIMIT :limit")
    suspend fun countBySession(limit: Int = 10): List<SessionCount>

    @Query("SELECT * FROM vectors WHERE file_uuid = :fileUuid AND stale = 0 AND file_uuid IN (SELECT uuid FROM workspace_files WHERE in_recycle_bin = 0) ORDER BY updated_at")
    suspend fun getActiveChunks(fileUuid: String): List<VectorEntity>

    @Query("DELETE FROM vectors WHERE stale = 1 AND updated_at < :cutoff")
    suspend fun cleanupStaleChunks(cutoff: Long)

    @Query("SELECT * FROM vectors WHERE session_id = :sessionId AND start_message_id = :messageId AND " + MEMORY_ROW)
    suspend fun getMemoryByUserMessage(sessionId: String, messageId: String): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE session_id = :sessionId AND end_message_id = :messageId AND " + MEMORY_ROW)
    suspend fun getMemoryByAssistantMessage(sessionId: String, messageId: String): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE session_id = :sessionId AND end_message_id = :messageId AND " + STATE_MEMORY_ROW)
    suspend fun getStateMemoryByAssistantMessage(sessionId: String, messageId: String): List<VectorEntity>

    @Query("DELETE FROM vectors WHERE session_id = :sessionId AND end_message_id = :messageId AND " + STATE_MEMORY_ROW)
    suspend fun deleteStateMemoryByAssistantMessage(sessionId: String, messageId: String): Int

    @Query("DELETE FROM vectors WHERE session_id = :sessionId AND start_message_id = :messageId AND " + STATE_MEMORY_ROW)
    suspend fun deleteStateMemoryByUserMessage(sessionId: String, messageId: String): Int

    @Query("DELETE FROM vectors WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    /** 恢复的消息重新使对应记忆生效，撤销孤儿标记。 */
    @Query("UPDATE vectors SET stale = 0 WHERE stale = 1 AND " + TURN_MEMORY_ROW + " AND " + MEMORY_TURN_PRESENT)
    suspend fun reviveRestoredMemory(): Int

    /** 以 stale 与 updated_at 记录记忆失去所属消息的时间，作为撤销窗口起点。 */
    @Query("UPDATE vectors SET stale = 1, updated_at = :now WHERE stale = 0 AND " + TURN_MEMORY_ROW + " AND NOT " + MEMORY_TURN_PRESENT)
    suspend fun markOrphanMemory(now: Long): Int

    @Query("DELETE FROM vectors WHERE stale = 1 AND updated_at < :cutoff AND " + TURN_MEMORY_ROW + " AND NOT " + MEMORY_TURN_PRESENT)
    suspend fun purgeOrphanMemory(cutoff: Long): Int

    @Query("SELECT * FROM vectors WHERE $TYPE_IS AND $CONVERSATION_ROW AND vectors.session_id = :sessionId AND $ACTIVE_VECTOR_ALIAS")
    suspend fun getConversationBySession(type: String, sessionId: String): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE $TYPE_IS AND $CONVERSATION_ROW AND $SESSIONS_OF_AGENT AND $ACTIVE_VECTOR_ALIAS")
    suspend fun getConversationByAgent(type: String, agentId: String): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE $TYPE_IS AND $CONVERSATION_ROW AND $SESSIONS_OF_SHARED_AGENTS AND $ACTIVE_VECTOR_ALIAS")
    suspend fun getConversationBySharedAgents(type: String, agentId: String, privateAgentIds: List<String>): List<VectorEntity>

    @Query(FTS_JOIN + "$CONVERSATION_ROW AND vectors.session_id = :sessionId AND $ACTIVE_VECTOR_ALIAS")
    suspend fun searchFtsConversationBySession(query: String, sessionId: String): List<VectorEntity>

    @Query(FTS_JOIN + "$CONVERSATION_ROW AND $SESSIONS_OF_AGENT AND $ACTIVE_VECTOR_ALIAS")
    suspend fun searchFtsConversationByAgent(query: String, agentId: String): List<VectorEntity>

    @Query(FTS_JOIN + "$CONVERSATION_ROW AND $SESSIONS_OF_SHARED_AGENTS AND $ACTIVE_VECTOR_ALIAS")
    suspend fun searchFtsConversationBySharedAgents(query: String, agentId: String, privateAgentIds: List<String>): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE $DOCUMENT_ROW AND $ACTIVE_VECTOR_ALIAS")
    suspend fun getAllDocuments(): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE $DOCUMENT_ROW AND $DOCUMENT_IN_SCOPE AND $ACTIVE_VECTOR_ALIAS")
    suspend fun getDocumentsInScope(rootUuids: List<String>, documentIds: List<String>): List<VectorEntity>

    @Query(FTS_JOIN + "$DOCUMENT_ROW AND $ACTIVE_VECTOR_ALIAS")
    suspend fun searchFtsDocuments(query: String): List<VectorEntity>

    @Query(FTS_JOIN + "$DOCUMENT_ROW AND $DOCUMENT_IN_SCOPE AND $ACTIVE_VECTOR_ALIAS")
    suspend fun searchFtsDocumentsInScope(query: String, rootUuids: List<String>, documentIds: List<String>): List<VectorEntity>

    data class TypeCount(val type: String, val count: Int)
    data class SessionCount(val session_id: String, val count: Int)
}
