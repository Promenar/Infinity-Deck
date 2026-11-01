package com.promenar.infinity.data.local.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.promenar.infinity.data.local.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow
import com.promenar.infinity.domain.repository.CacheUsageAggregate

/** 缓存统计仅包含助手主模型请求；总输入输出保留原有口径。 */
private const val CACHE_USAGE_COLUMNS = """
    COALESCE(SUM(CASE WHEN role = 'assistant' THEN 1 ELSE 0 END), 0) AS cache_requests,
    COALESCE(SUM(CASE WHEN role = 'assistant' AND json_type(tokens, '$.cacheReadInput') = 'integer' AND json_extract(tokens, '$.cacheReadInput') >= 0 AND json_extract(tokens, '$.cacheReadInput') <= (COALESCE(CAST(json_extract(tokens, '$.input') AS INTEGER), 0) - COALESCE(CAST(json_extract(tokens, '$.summaryInput') AS INTEGER), 0)) THEN 1 ELSE 0 END), 0) AS cache_reportedRequests,
    COALESCE(SUM(CASE WHEN role = 'assistant' AND json_type(tokens, '$.cacheReadInput') = 'integer' AND json_extract(tokens, '$.cacheReadInput') >= 0 AND json_extract(tokens, '$.cacheReadInput') <= (COALESCE(CAST(json_extract(tokens, '$.input') AS INTEGER), 0) - COALESCE(CAST(json_extract(tokens, '$.summaryInput') AS INTEGER), 0)) THEN (COALESCE(CAST(json_extract(tokens, '$.input') AS INTEGER), 0) - COALESCE(CAST(json_extract(tokens, '$.summaryInput') AS INTEGER), 0)) ELSE 0 END), 0) AS cache_reportedInputTokens,
    COALESCE(SUM(CASE WHEN role = 'assistant' AND json_type(tokens, '$.cacheReadInput') = 'integer' AND json_extract(tokens, '$.cacheReadInput') >= 0 AND json_extract(tokens, '$.cacheReadInput') <= (COALESCE(CAST(json_extract(tokens, '$.input') AS INTEGER), 0) - COALESCE(CAST(json_extract(tokens, '$.summaryInput') AS INTEGER), 0)) THEN json_extract(tokens, '$.cacheReadInput') ELSE 0 END), 0) AS cache_cacheReadTokens,
    COALESCE(SUM(CASE WHEN role = 'assistant' AND json_type(tokens, '$.cacheWriteInput') = 'integer' THEN MAX(0, json_extract(tokens, '$.cacheWriteInput')) ELSE 0 END), 0) AS cache_cacheWriteTokens,
    COALESCE(SUM(CASE WHEN role = 'assistant' AND json_type(tokens, '$.cacheWriteLongTtlInput') = 'integer' THEN MAX(0, json_extract(tokens, '$.cacheWriteLongTtlInput')) ELSE 0 END), 0) AS cache_cacheWriteLongTtlTokens,
    COALESCE(SUM(CASE WHEN role = 'assistant' AND json_type(tokens, '$.cacheReadInput') = 'integer' AND json_extract(tokens, '$.cacheReadInput') > (COALESCE(CAST(json_extract(tokens, '$.input') AS INTEGER), 0) - COALESCE(CAST(json_extract(tokens, '$.summaryInput') AS INTEGER), 0)) THEN 1 ELSE 0 END), 0) AS cache_anomalousRequests
"""

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Update
    suspend fun update(message: MessageEntity): Int

    @Delete
    suspend fun delete(message: MessageEntity)

    /**
     * 局部更新按“读出、修改、写回”整行执行，必须在同一事务内完成，
     * 否则并发写入的单列更新（如一轮的记忆状态）会被读出时的旧值覆盖。返回 -1 表示消息不存在。
     */
    @Transaction
    suspend fun updateWith(messageId: String, transform: (MessageEntity) -> MessageEntity): Int {
        val existing = getById(messageId) ?: return -1
        val updated = transform(existing)
        val affected = update(updated)
        if (affected == 1 && existing.quotes != updated.quotes && existing.isArchived != 0) {
            clearContextSummaries(existing.sessionId)
            restoreArchivedContextMessages(existing.sessionId)
        }
        return affected
    }

    @Query("UPDATE messages SET content = :content WHERE session_id = :sessionId AND id = :messageId")
    suspend fun updateEditedContentInSession(sessionId: String, messageId: String, content: String): Int

    /** 编辑已摘要消息时，正文与摘要失效必须在同一个事务中生效。 */
    @Transaction
    suspend fun editContentInSessionWithContextSummary(sessionId: String, messageId: String, content: String): Boolean {
        val original = getById(messageId)?.takeIf { it.sessionId == sessionId } ?: return false
        check(updateEditedContentInSession(sessionId, messageId, content) == 1) { "正文编辑目标已变化" }
        if (original.isArchived != 0) {
            clearContextSummaries(sessionId)
            restoreArchivedContextMessages(sessionId)
        }
        return true
    }

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: String)

    @Query("DELETE FROM messages WHERE session_id = :sessionId AND id = :messageId")
    suspend fun deleteByIdInSession(sessionId: String, messageId: String): Int

    @Query("DELETE FROM messages WHERE session_id = :sessionId AND id IN (:messageIds)")
    suspend fun deleteByIdsInSession(sessionId: String, messageIds: List<String>): Int

    @Query("DELETE FROM messages WHERE session_id = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE session_id = :sessionId AND is_archived != 0")
    suspend fun countArchivedBySession(sessionId: String): Int

    @Query(
        """SELECT EXISTS (
             SELECT 1 FROM context_summaries AS summary
             WHERE summary.session_id = :sessionId
               AND (NOT EXISTS (
                      SELECT 1 FROM messages
                      WHERE session_id = summary.session_id AND id = summary.start_message_id
                    ) OR NOT EXISTS (
                      SELECT 1 FROM messages
                      WHERE session_id = summary.session_id AND id = summary.end_message_id
                    ))
           )""",
    )
    suspend fun hasDanglingContextSummary(sessionId: String): Boolean

    @Query("DELETE FROM context_summaries WHERE session_id = :sessionId")
    suspend fun clearContextSummaries(sessionId: String)

    @Query("UPDATE messages SET is_archived = 0 WHERE session_id = :sessionId AND is_archived != 0")
    suspend fun restoreArchivedContextMessages(sessionId: String)

    @Transaction
    suspend fun deleteByIdWithContextSummary(messageId: String) {
        val message = getById(messageId) ?: return
        deleteByIdsInSessionWithContextSummary(message.sessionId, listOf(messageId))
    }

    @Transaction
    suspend fun deleteByIdsInSessionWithContextSummary(sessionId: String, messageIds: List<String>): Int {
        if (messageIds.isEmpty()) return 0
        val archivedBefore = countArchivedBySession(sessionId)
        val deleted = messageIds.distinct().chunked(900).sumOf { ids ->
            deleteByIdsInSession(sessionId, ids)
        }
        // 比较实际删除后的归档数量，覆盖数据库 CASCADE 删除的全部后裔。
        if (countArchivedBySession(sessionId) < archivedBefore || hasDanglingContextSummary(sessionId)) {
            clearContextSummaries(sessionId)
            restoreArchivedContextMessages(sessionId)
        }
        return deleted
    }

    @Transaction
    suspend fun deleteBySessionIdWithContextSummary(sessionId: String) {
        val archivedBefore = countArchivedBySession(sessionId)
        deleteBySessionId(sessionId)
        if (countArchivedBySession(sessionId) < archivedBefore || hasDanglingContextSummary(sessionId)) {
            clearContextSummaries(sessionId)
            restoreArchivedContextMessages(sessionId)
        }
    }

    @Transaction
    suspend fun deleteBySessionIdAndTimestampAfterWithContextSummary(sessionId: String, timestamp: Long) {
        val archivedBefore = countArchivedBySession(sessionId)
        deleteBySessionIdAndTimestampAfter(sessionId, timestamp)
        if (countArchivedBySession(sessionId) < archivedBefore || hasDanglingContextSummary(sessionId)) {
            clearContextSummaries(sessionId)
            restoreArchivedContextMessages(sessionId)
        }
    }

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getById(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY created_at ASC, rowid ASC")
    fun observeBySession(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY created_at ASC, rowid ASC")
    suspend fun getBySession(sessionId: String): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE session_id = :sessionId")
    suspend fun countBySession(sessionId: String): Int

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateStatus(messageId: String, status: String)

    @Query("UPDATE messages SET pending_approval_tool_ids = :pendingApprovalToolIds WHERE id = :messageId")
    suspend fun updatePendingApprovalToolIds(messageId: String, pendingApprovalToolIds: String?): Int

    @Query("UPDATE messages SET tool_calls = :toolCalls WHERE id = :messageId AND session_id = :sessionId")
    suspend fun updateQuestionToolCalls(sessionId: String, messageId: String, toolCalls: String): Int

    @Query(
        """UPDATE messages
           SET tool_calls = :toolCalls,
               pending_approval_tool_ids = :pendingApprovalToolIds
           WHERE id = :messageId""",
    )
    suspend fun updateToolApprovalPayload(
        messageId: String,
        toolCalls: String,
        pendingApprovalToolIds: String,
    ): Int

    @Query(
        """UPDATE messages SET pending_approval_tool_ids = NULL
           WHERE pending_approval_tool_ids IS NOT NULL
             AND NOT EXISTS (
               SELECT 1 FROM tool_execution_ledger ledger
               WHERE ledger.session_id = messages.session_id
                 AND ledger.assistant_message_id = messages.id
                 AND ledger.status IN ('PENDING_APPROVAL', 'APPROVED')
             )""",
    )
    suspend fun clearPendingApprovalWithoutLedger(): Int

    @Query("DELETE FROM messages WHERE session_id = :sessionId AND created_at >= :timestamp")
    suspend fun deleteBySessionIdAndTimestampAfter(sessionId: String, timestamp: Long)

    @Query("SELECT * FROM messages WHERE session_id = :sessionId AND created_at < :timestamp ORDER BY created_at ASC, rowid ASC")
    suspend fun getBySessionBeforeTimestamp(sessionId: String, timestamp: Long): List<MessageEntity>

    @Query("UPDATE messages SET vectorization_status = :status, is_archived = :isArchived WHERE id = :messageId")
    suspend fun updateVectorizationStatus(messageId: String, status: String, isArchived: Int?)

    @Query("UPDATE messages SET vectorization_status = :encoded WHERE id = :messageId")
    suspend fun updateTurnMemoryState(messageId: String, encoded: String?)

    /** 仅当状态列仍为 [expected] 时改写一轮的记忆状态；返回影响行数。 */
    @Query("UPDATE messages SET vectorization_status = :encoded WHERE id = :messageId AND vectorization_status IS :expected")
    suspend fun compareAndSetTurnMemoryState(messageId: String, expected: String?, encoded: String?): Int

    /** 摘要归档的纯文本向量化状态；该列已记有一轮的记忆状态（JSON）时保留后者。 */
    @Query("UPDATE messages SET vectorization_status = :status WHERE id = :messageId AND (vectorization_status IS NULL OR vectorization_status NOT LIKE '{%')")
    suspend fun updateArchiveVectorizationStatus(messageId: String, status: String)

    @Query("UPDATE messages SET content = :content, tokens = :tokens, reasoning = :reasoning, citations = :citations, rag_references = :ragReferences, rag_metadata = :ragMetadata, thought_signature = :thoughtSignature, planning_task = :planningTask, execution_steps = :executionSteps, pending_approval_tool_ids = :pendingApprovalToolIds, tool_results = :toolResults, tool_calls = :toolCalls, is_error = :isError, error_message = :errorMessage WHERE id = :messageId")
    suspend fun updateContent(
        messageId: String,
        content: String?,
        tokens: String?,
        reasoning: String?,
        citations: String?,
        ragReferences: String?,
        ragMetadata: String?,
        thoughtSignature: String?,
        planningTask: String?,
        executionSteps: String?,
        pendingApprovalToolIds: String?,
        toolResults: String?,
        toolCalls: String?,
        isError: Int?,
        errorMessage: String?
    )

    @Query("SELECT * FROM messages WHERE tokens IS NOT NULL")
    suspend fun getMessagesWithTokens(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE tokens IS NOT NULL AND session_id = :sessionId")
    suspend fun getMessagesWithTokensBySession(sessionId: String): List<MessageEntity>

    @Query("UPDATE messages SET tokens = NULL")
    suspend fun clearAllTokenData()

    // ── SQL 级聚合查询（替代 Kotlin 层全表扫描）──

    @Query("""
        SELECT COALESCE(SUM(CAST(json_extract(tokens, '$.input') AS INTEGER)), 0) as total_input,
               COALESCE(SUM(CAST(json_extract(tokens, '$.output') AS INTEGER)), 0) as total_output,
        """ + CACHE_USAGE_COLUMNS + """
        FROM messages
        WHERE tokens IS NOT NULL
    """)
    suspend fun getTotalTokenUsage(): TokenTotalRow

    @Query("""
        SELECT COALESCE(model_id, 'unknown') as model_id,
               COALESCE(SUM(CAST(json_extract(tokens, '$.input') AS INTEGER)), 0) as total_input,
               COALESCE(SUM(CAST(json_extract(tokens, '$.output') AS INTEGER)), 0) as total_output,
        """ + CACHE_USAGE_COLUMNS + """
        FROM messages
        WHERE tokens IS NOT NULL
        GROUP BY model_id
        ORDER BY total_input + total_output DESC
    """)
    suspend fun getTokenUsageByModel(): List<ModelTokenRow>

    @Query("""
        SELECT m.session_id, s.title,
               COALESCE(SUM(CAST(json_extract(m.tokens, '$.input') AS INTEGER)), 0) as total_input,
               COALESCE(SUM(CAST(json_extract(m.tokens, '$.output') AS INTEGER)), 0) as total_output
        FROM messages m
        LEFT JOIN sessions s ON m.session_id = s.id
        WHERE m.tokens IS NOT NULL
        GROUP BY m.session_id
        ORDER BY total_input + total_output DESC
        LIMIT :limit
    """)
    suspend fun getSessionTokenRanking(limit: Int): List<SessionTokenRow>

    @Query("""
        SELECT date(m.created_at / 1000, 'unixepoch') as day,
               COALESCE(SUM(CAST(json_extract(m.tokens, '$.input') AS INTEGER)), 0) as total_input,
               COALESCE(SUM(CAST(json_extract(m.tokens, '$.output') AS INTEGER)), 0) as total_output,
        """ + CACHE_USAGE_COLUMNS + """
        FROM messages m
        WHERE m.tokens IS NOT NULL AND m.created_at >= :sinceTimestamp
        GROUP BY day
        ORDER BY day ASC
    """)
    suspend fun getDailyTokenTrend(sinceTimestamp: Long): List<DailyTokenRow>

    /** 对话记忆的时间线按所属消息的发生时间排列。 */
    @Query("SELECT id, created_at FROM messages WHERE id IN (:ids)")
    suspend fun getCreatedAt(ids: List<String>): List<MessageTimeRow>

    @Query("SELECT id FROM messages WHERE created_at >= :from AND created_at < :to")
    suspend fun getIdsCreatedBetween(from: Long, to: Long): List<String>
}

data class MessageTimeRow(
    val id: String,
    val created_at: Long,
)

data class TokenTotalRow(
    val total_input: Long,
    val total_output: Long,
    @Embedded(prefix = "cache_") val cache: CacheUsageAggregate = CacheUsageAggregate(),
)

data class ModelTokenRow(
    val model_id: String,
    val total_input: Long,
    val total_output: Long,
    @Embedded(prefix = "cache_") val cache: CacheUsageAggregate = CacheUsageAggregate(),
)

data class SessionTokenRow(
    val session_id: String,
    val title: String?,
    val total_input: Long,
    val total_output: Long
)

data class DailyTokenRow(
    val day: String?,
    val total_input: Long,
    val total_output: Long,
    @Embedded(prefix = "cache_") val cache: CacheUsageAggregate = CacheUsageAggregate(),
)
