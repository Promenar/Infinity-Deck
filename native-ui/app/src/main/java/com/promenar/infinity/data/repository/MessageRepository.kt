package com.promenar.infinity.data.repository

import com.promenar.infinity.data.local.db.dao.MessageDao
import com.promenar.infinity.data.local.db.entity.MessageEntity
import com.promenar.infinity.data.model.Citation
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.KgPath
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.MessageQuote
import com.promenar.infinity.data.model.RagMetadata
import com.promenar.infinity.data.model.RagProgress
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.data.model.TaskState
import com.promenar.infinity.data.model.TokenUsage
import com.promenar.infinity.data.model.ToolCall
import com.promenar.infinity.data.model.ToolResultArtifact
import com.promenar.infinity.data.model.json
import com.promenar.infinity.data.model.toDomain
import com.promenar.infinity.data.model.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.serializer
import java.util.UUID

open class MessageRepository(
    private val messageDao: MessageDao
) : IMessageRepository,
    com.promenar.infinity.domain.repository.IMessageRepository {

    private inline fun <reified T> encode(value: T): String =
        json.encodeToString(serializer<T>(), value)

    // ── Data-layer IMessageRepository (existing) ────────────────────────

    override suspend fun insert(message: Message, sessionId: String) {
        messageDao.insert(message.toEntity(sessionId))
    }

    open suspend fun update(message: Message, sessionId: String) {
        checkUpdated(message.id, messageDao.update(message.toEntity(sessionId)))
    }

    override suspend fun updatePartial(messageId: String, updates: Map<String, Any?>) {
        val affected = messageDao.updateWith(messageId) { applyPartialUpdates(it, updates) }
        if (affected == -1) throw MessagePersistenceException("消息不存在，无法持久化更新：$messageId")
        checkUpdated(messageId, affected)
    }

    override suspend fun editContentInSession(sessionId: String, messageId: String, content: String) {
        if (!messageDao.editContentInSessionWithContextSummary(sessionId, messageId, content)) {
            throw MessagePersistenceException("消息不存在或不属于当前会话，无法保存正文编辑：$messageId")
        }
    }

    override suspend fun delete(messageId: String) {
        messageDao.deleteByIdWithContextSummary(messageId)
    }

    override suspend fun deleteInSession(sessionId: String, messageId: String): Boolean =
        messageDao.deleteByIdsInSessionWithContextSummary(sessionId, listOf(messageId)) == 1

    override suspend fun deleteManyInSession(sessionId: String, messageIds: List<String>): Int =
        if (messageIds.isEmpty()) 0 else messageDao.deleteByIdsInSessionWithContextSummary(sessionId, messageIds)

    override suspend fun deleteBySessionId(sessionId: String) {
        messageDao.deleteBySessionIdWithContextSummary(sessionId)
    }

    override suspend fun deleteMessagesAfter(sessionId: String, timestamp: Long) {
        messageDao.deleteBySessionIdAndTimestampAfterWithContextSummary(sessionId, timestamp)
    }

    override suspend fun getById(messageId: String): Message? {
        return messageDao.getById(messageId)?.toDomain()
    }

    override suspend fun getBySession(sessionId: String): List<Message> {
        return messageDao.getBySession(sessionId).map { it.toDomain() }
    }

    override suspend fun updateVectorizationStatus(messageId: String, status: String, isArchived: Boolean?) {
        messageDao.updateVectorizationStatus(messageId, status, isArchived?.let { if (it) 1 else 0 })
    }

    override suspend fun updateTurnMemoryState(messageId: String, encoded: String?) {
        messageDao.updateTurnMemoryState(messageId, encoded)
    }

    override suspend fun compareAndSetTurnMemoryState(messageId: String, expected: String?, encoded: String?): Boolean =
        messageDao.compareAndSetTurnMemoryState(messageId, expected, encoded) == 1

    override suspend fun updateArchiveVectorizationStatus(messageId: String, status: String) {
        messageDao.updateArchiveVectorizationStatus(messageId, status)
    }

    private fun applyPartialUpdates(
        entity: MessageEntity,
        updates: Map<String, Any?>
    ): MessageEntity {
        var result = entity
        updates.forEach { (key, value) ->
            result = when (key) {
                "content" -> result.copy(content = value as? String ?: result.content)
                "modelId" -> result.copy(modelId = value as? String)
                "turnContext" -> result.copy(turnContext = value as? String)
                "requestInstruction" -> result.copy(requestInstruction = value as? String)
                "quotes" -> result.copy(quotes = (value as? List<MessageQuote>)?.takeIf { it.isNotEmpty() }?.let { encode(it) })
                "status" -> result.copy(status = value as? String)
                "reasoning" -> result.copy(reasoning = value as? String)
                "thoughtSignature" -> result.copy(thoughtSignature = value as? String)
                "tokens" -> result.copy(tokens = value?.let { encode(it as TokenUsage) })
                "citations" -> result.copy(citations = value?.let { encode(it as List<Citation>) })
                "ragReferences" -> result.copy(ragReferences = value?.let { encode(it as List<RagReference>) })
                "kgPaths" -> result.copy(kgPaths = value?.let { encode(it as List<KgPath>) })
                "ragProgress" -> result.copy(ragProgress = value?.let { encode(it as RagProgress) })
                "ragMetadata" -> result.copy(ragMetadata = value?.let { encode(it as RagMetadata) })
                "ragReferencesLoading" -> result.copy(ragReferencesLoading = if (value as? Boolean == true) 1 else 0)
                "executionSteps" -> result.copy(executionSteps = value?.let { encode(it as List<ExecutionStep>) })
                "toolCalls" -> result.copy(toolCalls = value?.let { encode(it as List<ToolCall>) })
                "pendingApprovalToolIds" -> result.copy(pendingApprovalToolIds = value?.let { encode(it as List<String>) })
                "planningTask" -> result.copy(planningTask = value?.let { encode(it as TaskState) })
                "isArchived" -> result.copy(isArchived = if (value as? Boolean == true) 1 else 0)
                "vectorizationStatus" -> result.copy(vectorizationStatus = value as? String)
                "layoutHeight" -> result.copy(layoutHeight = value as? Double)
                "toolResults" -> result.copy(toolResults = value?.let { encode(it as List<ToolResultArtifact>) })
                "isError" -> result.copy(isError = if (value as? Boolean == true) 1 else 0)
                "errorMessage" -> result.copy(errorMessage = value as? String)
                else -> result
            }
        }
        return result
    }

    // ── Domain-layer IMessageRepository ─────────────────────────────────

    override fun observeBySession(sessionId: String): Flow<List<com.promenar.infinity.domain.model.Message>> {
        return messageDao.observeBySession(sessionId).map { entities ->
            entities.map { it.toDomainMessage() }
        }
    }

    override suspend fun send(
        sessionId: String,
        content: String,
        role: com.promenar.infinity.domain.model.MessageRole
    ): com.promenar.infinity.domain.model.Message {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val dataMessage = Message(
            id = id,
            role = MessageRole.valueOf(role.name),
            content = content,
            createdAt = now
        )
        messageDao.insert(dataMessage.toEntity(sessionId))
        return dataMessage.toDomainMessage(sessionId)
    }

    override suspend fun appendContent(messageId: String, chunk: String) {
        val affected = messageDao.updateWith(messageId) { it.copy(content = it.content + chunk) }
        if (affected != -1) checkUpdated(messageId, affected)
    }

    private fun checkUpdated(messageId: String, affectedRows: Int) {
        if (affectedRows != 1) {
            throw MessagePersistenceException(
                "消息持久化更新影响行数异常：messageId=$messageId, affectedRows=$affectedRows",
            )
        }
    }

    // ── Mappers ─────────────────────────────────────────────────────────

    private fun MessageEntity.toDomainMessage(): com.promenar.infinity.domain.model.Message =
        toDomain().toDomainMessage(sessionId)

    private fun Message.toDomainMessage(sessionId: String): com.promenar.infinity.domain.model.Message =
        com.promenar.infinity.domain.model.Message(
            id = id,
            sessionId = sessionId,
            role = com.promenar.infinity.domain.model.MessageRole.valueOf(role.name),
            content = content,
            modelId = modelId,
            quotes = quotes,
            turnContext = turnContext,
            requestInstruction = requestInstruction,
            documents = userDocuments.orEmpty().map { document ->
                com.promenar.infinity.domain.model.MessageDocumentAttachment(
                    name = document.name,
                    mimeType = document.mimeType,
                    content = document.content,
                )
            },
            thinking = reasoning,
            toolCalls = toolCalls?.map { it.toDomainToolCall() },
            ragReferences = ragReferences?.map { it.toDomainRagReference() },
            tokenUsage = tokens?.toDomainTokenUsage(),
            timestamp = createdAt
        )

    private fun ToolCall.toDomainToolCall(): com.promenar.infinity.domain.model.ToolCall =
        com.promenar.infinity.domain.model.ToolCall(
            id = id,
            name = name,
            arguments = arguments,
            result = null,
            status = com.promenar.infinity.domain.model.ToolCallStatus.COMPLETED
        )

    private fun RagReference.toDomainRagReference(): com.promenar.infinity.domain.model.RagReference =
        com.promenar.infinity.domain.model.RagReference(
            chunkId = id,
            documentTitle = source,
            snippet = content,
            score = score.toDouble()
        )

    private fun TokenUsage.toDomainTokenUsage(): com.promenar.infinity.domain.model.TokenUsage =
        com.promenar.infinity.domain.model.TokenUsage(
            input = input,
            output = output
        )
}
