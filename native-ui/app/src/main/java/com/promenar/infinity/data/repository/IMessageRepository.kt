package com.promenar.infinity.data.repository

import com.promenar.infinity.data.local.db.dao.ReplacementMessageDeletion
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.TurnMemoryState

class MessagePersistenceException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

interface IMessageRepository {
    suspend fun insert(message: Message, sessionId: String)
    suspend fun updatePartial(messageId: String, updates: Map<String, Any?>)
    suspend fun editContentInSession(sessionId: String, messageId: String, content: String) {
        throw MessagePersistenceException("消息仓库不支持事务化正文编辑")
    }
    suspend fun writeUserEditCandidate(sessionId: String, message: Message) {
        throw MessagePersistenceException("消息仓库不支持暂存用户编辑")
    }
    suspend fun invalidateContextSummaryForArchivedUser(sessionId: String, userMessageId: String): Boolean {
        throw MessagePersistenceException("消息仓库不支持清除失效摘要")
    }
    suspend fun delete(messageId: String)
    suspend fun deleteInSession(sessionId: String, messageId: String): Boolean {
        delete(messageId)
        return true
    }
    suspend fun deleteManyInSession(sessionId: String, messageIds: List<String>): Int {
        var deleted = 0
        for (messageId in messageIds) {
            if (deleteInSession(sessionId, messageId)) deleted += 1
        }
        return deleted
    }
    suspend fun deleteManyForReplacement(
        sessionId: String,
        messageIds: List<String>,
        userMessageId: String,
    ): ReplacementMessageDeletion = ReplacementMessageDeletion(deleteManyInSession(sessionId, messageIds), false)
    suspend fun deleteBySessionId(sessionId: String)
    suspend fun deleteMessagesAfter(sessionId: String, timestamp: Long)
    suspend fun getById(messageId: String): Message?
    suspend fun getBySession(sessionId: String): List<Message>
    suspend fun updateVectorizationStatus(messageId: String, status: String, isArchived: Boolean?)

    /** 只改写一轮的记忆写入状态列，不覆盖同一行的其它字段。 */
    suspend fun updateTurnMemoryState(messageId: String, encoded: String?) {
        updatePartial(messageId, mapOf("vectorizationStatus" to encoded))
    }

    /** 仅当状态列仍为 [expected] 时改写一轮的记忆状态；返回是否改写。 */
    suspend fun compareAndSetTurnMemoryState(messageId: String, expected: String?, encoded: String?): Boolean {
        if (getById(messageId)?.vectorizationStatus != expected) return false
        updateTurnMemoryState(messageId, encoded)
        return true
    }

    /** 摘要归档的纯文本向量化状态；不覆盖该列已有的一轮记忆状态。 */
    suspend fun updateArchiveVectorizationStatus(messageId: String, status: String) {
        if (TurnMemoryState.decode(getById(messageId)?.vectorizationStatus) == null) {
            updatePartial(messageId, mapOf("vectorizationStatus" to status))
        }
    }
}
