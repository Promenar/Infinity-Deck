package com.promenar.infinity.data.library.ask

import com.promenar.infinity.data.knowledge.KnowledgeSearchData
import com.promenar.infinity.data.knowledge.knowledgeTurnId
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.json
import kotlinx.serialization.decodeFromString

internal class ChatCitationRecorder(
    private val repository: RoomKnowledgeCitationRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun record(
        session: Session,
        messageId: String,
        answer: String,
        mapping: Map<String, String>,
        currentReferences: List<RagReference> = emptyList(),
    ) {
        val references = session.messages.flatMap { message ->
            message.ragReferences.orEmpty() + message.executionSteps.orEmpty().flatMap { step ->
                if (step.toolName != "knowledge_search") emptyList() else {
                    runCatching { json.decodeFromString<KnowledgeSearchData>(step.data.orEmpty()).references }
                        .getOrDefault(emptyList())
                }
            }
        } + currentReferences
        val rows = selectChatCitations(session.id, messageId, answer, mapping, references, now())
        repository.replaceChat(messageId, rows)
    }
}

internal fun completedCitationMessages(
    session: Session,
    createdIds: Set<String>,
    retainedIds: Set<String>,
    replacedIds: Set<String>,
    currentTurnId: String,
): List<Message> = session.messages.filter { message ->
    message.role == MessageRole.ASSISTANT &&
        (message.id in createdIds || knowledgeTurnId(session, message.id) == currentTurnId) && message.id in retainedIds &&
        message.id !in replacedIds && message.status !in setOf("interrupted", "error", "cancelled")
}

internal fun selectChatCitations(
    sessionId: String,
    messageId: String,
    answer: String,
    mapping: Map<String, String>,
    references: List<RagReference>,
    citedAt: Long,
): List<KnowledgeCitationEntity> {
    val byId = references.associateBy { it.id }
    val idsByLabel = mapping.entries.associate { it.value to it.key }
    val labels = Regex("""\[K\s*(\d+)\]""").findAll(answer).mapNotNull { match ->
        match.groupValues[1].toIntOrNull()?.takeIf { it > 0 }?.let { "K$it" }
    }.distinct()
    return labels.mapNotNull { label ->
        val id = idsByLabel[label] ?: return@mapNotNull null
        val reference = byId[id] ?: return@mapNotNull null
        val documentId = reference.documentId ?: return@mapNotNull null
        KnowledgeCitationEntity(citationId(), documentId, reference.chunkIndex, id, "CHAT",
            sessionId, messageId, null, label, citedAt)
    }.toList()
}
