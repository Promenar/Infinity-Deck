package com.promenar.infinity.data.library.ask

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.MessageEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.memory.LongTermMemoryEntity
import com.promenar.infinity.domain.library.CitationSource
import com.promenar.infinity.domain.library.CitedDocument
import com.promenar.infinity.domain.library.DocumentCitation
import com.promenar.infinity.domain.library.KnowledgeCitationRepository
import com.promenar.infinity.startup.StartupWriteBarrier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

@Entity(
    tableName = "knowledge_citations",
    foreignKeys = [
        ForeignKey(entity = FileEntry::class, parentColumns = ["uuid"], childColumns = ["document_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["session_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["message_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("document_id", "cited_at"), Index("session_id"), Index("message_id")],
)
data class KnowledgeCitationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "document_id") val documentId: String,
    @ColumnInfo(name = "chunk_index") val chunkIndex: Int?,
    @ColumnInfo(name = "vector_id") val vectorId: String?,
    @ColumnInfo(name = "source_kind") val sourceKind: String,
    @ColumnInfo(name = "session_id") val sessionId: String?,
    @ColumnInfo(name = "message_id") val messageId: String?,
    @ColumnInfo(name = "ask_query") val askQuery: String?,
    val label: String,
    @ColumnInfo(name = "cited_at") val citedAt: Long,
)

@Entity(
    tableName = "memory_embedding_cache",
    foreignKeys = [ForeignKey(entity = LongTermMemoryEntity::class, parentColumns = ["id"],
        childColumns = ["memory_id"], onDelete = ForeignKey.CASCADE)],
)
data class MemoryEmbeddingEntity(
    @PrimaryKey @ColumnInfo(name = "memory_id") val memoryId: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "model_id") val modelId: String,
    val embedding: ByteArray,
)

data class CitationRow(
    val documentId: String,
    val chunkIndex: Int?,
    val sourceKind: String,
    val sessionId: String?,
    val sessionTitle: String?,
    val messageId: String?,
    val label: String,
    val askQuery: String?,
    val agentName: String?,
    val citedAt: Long,
)

data class CitedDocumentRow(
    val documentId: String,
    val name: String,
    val count: Int,
    val lastCitedAt: Long,
    val lastSessionTitle: String?,
    val lastAgentName: String?,
)

@Dao
interface KnowledgeCitationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(citations: List<KnowledgeCitationEntity>)

    @Query("DELETE FROM knowledge_citations WHERE message_id = :messageId AND source_kind = 'CHAT'")
    suspend fun deleteForMessage(messageId: String)

    @Query("SELECT * FROM knowledge_citations WHERE session_id = :sessionId AND source_kind = 'CHAT'")
    suspend fun forSession(sessionId: String): List<KnowledgeCitationEntity>

    @Query("SELECT * FROM knowledge_citations WHERE message_id IN (:messageIds) AND source_kind = 'CHAT'")
    suspend fun forMessages(messageIds: List<String>): List<KnowledgeCitationEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE id = :messageId AND session_id = :sessionId)")
    suspend fun messageExists(messageId: String, sessionId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissing(citations: List<KnowledgeCitationEntity>)

    @Query("SELECT EXISTS(SELECT 1 FROM workspace_files WHERE uuid = :id AND in_recycle_bin = 0)")
    suspend fun activeDocumentExists(id: String): Boolean

    @Query("""SELECT c.document_id AS documentId, c.chunk_index AS chunkIndex, c.source_kind AS sourceKind,
        c.session_id AS sessionId, s.title AS sessionTitle, c.message_id AS messageId, c.label AS label,
        c.ask_query AS askQuery, a.name AS agentName, c.cited_at AS citedAt
        FROM knowledge_citations c JOIN workspace_files f ON f.uuid = c.document_id
        LEFT JOIN sessions s ON s.id = c.session_id LEFT JOIN agents a ON a.id = s.agent_id
        WHERE c.document_id = :documentId AND f.in_recycle_bin = 0
        ORDER BY c.cited_at DESC, c.id DESC LIMIT :limit""")
    fun observeForDocument(documentId: String, limit: Int): Flow<List<CitationRow>>

    @Query("""SELECT c.document_id AS documentId, f.name AS name, COUNT(*) AS count,
        MAX(c.cited_at) AS lastCitedAt,
        (SELECT s.title FROM knowledge_citations latest LEFT JOIN sessions s ON s.id = latest.session_id
            WHERE latest.document_id = c.document_id ORDER BY latest.cited_at DESC, latest.id DESC LIMIT 1) AS lastSessionTitle,
        (SELECT a.name FROM knowledge_citations latest LEFT JOIN sessions s ON s.id = latest.session_id
            LEFT JOIN agents a ON a.id = s.agent_id
            WHERE latest.document_id = c.document_id ORDER BY latest.cited_at DESC, latest.id DESC LIMIT 1) AS lastAgentName
        FROM knowledge_citations c JOIN workspace_files f ON f.uuid = c.document_id
        WHERE f.in_recycle_bin = 0 GROUP BY c.document_id, f.name
        ORDER BY lastCitedAt DESC, c.document_id ASC LIMIT :limit""")
    fun observeRecent(limit: Int): Flow<List<CitedDocumentRow>>

    @Query("""SELECT c.document_id AS documentId, f.name AS name, COUNT(*) AS count,
        MAX(c.cited_at) AS lastCitedAt,
        (SELECT s.title FROM knowledge_citations latest LEFT JOIN sessions s ON s.id = latest.session_id
            WHERE latest.document_id = c.document_id ORDER BY latest.cited_at DESC, latest.id DESC LIMIT 1) AS lastSessionTitle,
        (SELECT a.name FROM knowledge_citations latest LEFT JOIN sessions s ON s.id = latest.session_id
            LEFT JOIN agents a ON a.id = s.agent_id
            WHERE latest.document_id = c.document_id ORDER BY latest.cited_at DESC, latest.id DESC LIMIT 1) AS lastAgentName
        FROM knowledge_citations c JOIN workspace_files f ON f.uuid = c.document_id
        WHERE f.in_recycle_bin = 0 GROUP BY c.document_id, f.name
        ORDER BY count DESC, lastCitedAt DESC, c.document_id ASC LIMIT :limit""")
    fun observeTop(limit: Int): Flow<List<CitedDocumentRow>>

    @Query("SELECT * FROM memory_embedding_cache WHERE memory_id IN (:ids)")
    suspend fun embeddings(ids: List<String>): List<MemoryEmbeddingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveEmbeddings(rows: List<MemoryEmbeddingEntity>)
}

class RoomKnowledgeCitationRepository(private val database: InfinityDatabase) : KnowledgeCitationRepository {
    private val dao get() = database.knowledgeCitationDao()

    override fun observeForDocument(documentId: String, limit: Int): Flow<List<DocumentCitation>> =
        dao.observeForDocument(documentId, limit.coerceAtLeast(0)).map { rows ->
            rows.map { row ->
                DocumentCitation(row.documentId, row.chunkIndex, CitationSource.valueOf(row.sourceKind),
                    row.sessionId, row.sessionTitle, row.messageId, row.label, row.askQuery, row.agentName, row.citedAt)
            }
        }

    override fun observeRecentDocuments(limit: Int): Flow<List<CitedDocument>> =
        dao.observeRecent(limit.coerceAtLeast(0)).map { rows -> rows.map(CitedDocumentRow::toDomain) }

    override fun observeTopDocuments(limit: Int): Flow<List<CitedDocument>> =
        dao.observeTop(limit.coerceAtLeast(0)).map { rows -> rows.map(CitedDocumentRow::toDomain) }

    suspend fun replaceChat(messageId: String, citations: List<KnowledgeCitationEntity>) {
        StartupWriteBarrier.requireWritable()
        database.withTransaction {
            dao.deleteForMessage(messageId)
            val existing = citations.filter { dao.activeDocumentExists(it.documentId) }
            if (existing.isNotEmpty()) dao.insertAll(existing)
        }
    }

    /** 删除消息前保存其对话引用，供撤销删除时放回。 */
    suspend fun snapshotChat(messageIds: Collection<String>): List<KnowledgeCitationEntity> =
        if (messageIds.isEmpty()) emptyList() else messageIds.chunked(SQL_IN_CHUNK).flatMap { dao.forMessages(it) }

    /** 撤销删除：消息已重新写入后放回引用；文档已删除或消息未恢复的引用跳过。 */
    suspend fun restoreChat(citations: List<KnowledgeCitationEntity>) {
        if (citations.isEmpty()) return
        StartupWriteBarrier.requireWritable()
        database.withTransaction {
            val restorable = citations.filter { row ->
                val message = row.messageId
                val session = row.sessionId
                message != null && session != null && dao.messageExists(message, session) && dao.activeDocumentExists(row.documentId)
            }
            if (restorable.isNotEmpty()) dao.insertMissing(restorable)
        }
    }

    suspend fun recordAsk(citations: List<KnowledgeCitationEntity>) {
        StartupWriteBarrier.requireWritable()
        database.withTransaction {
            val existing = citations.filter { dao.activeDocumentExists(it.documentId) }
            if (existing.isNotEmpty()) dao.insertAll(existing)
        }
    }
}

private fun CitedDocumentRow.toDomain() = CitedDocument(
    documentId, name, count, lastCitedAt, lastSessionTitle, lastAgentName,
)

private const val SQL_IN_CHUNK = 500

internal fun citationId(): String = UUID.randomUUID().toString()
