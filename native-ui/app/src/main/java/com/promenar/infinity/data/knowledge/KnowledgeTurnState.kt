package com.promenar.infinity.data.knowledge

import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.data.model.RagUsage
import com.promenar.infinity.data.model.Session

/** 工具续轮和审批恢复都追溯到同一条用户消息，不使用会变化的助手消息作为轮次。 */
fun knowledgeTurnId(session: Session, assistantMessageId: String? = null): String {
    val end = assistantMessageId?.let { id -> session.messages.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
        ?: session.messages.size
    val parentId = session.messages.getOrNull(end)?.takeIf { it.role == MessageRole.ASSISTANT }?.parentMessageId
    if (parentId != null && session.messages.take(end).any { it.id == parentId && it.role == MessageRole.USER }) {
        return parentId
    }
    return session.messages.take(end).lastOrNull { it.role == MessageRole.USER }?.id
        ?: session.messages.lastOrNull { it.role == MessageRole.USER }?.id
        ?: assistantMessageId ?: "session:${session.id}"
}

/** 内部通知可插入用户消息；按助手有效父关系恢复逻辑轮次，工具结果跟随助手。 */
private fun knowledgeTurnMessages(session: Session, turnId: String): List<Message> {
    val seenUsers = hashSetOf<String>()
    var latestUser: String? = null
    var owner: String? = null
    return buildList {
        session.messages.forEach { message ->
            when (message.role) {
                MessageRole.USER -> {
                    seenUsers += message.id
                    latestUser = message.id
                    owner = message.id
                }
                MessageRole.ASSISTANT -> owner = message.parentMessageId?.takeIf { it in seenUsers }
                    ?: latestUser ?: message.id
                else -> Unit
            }
            if (owner == turnId) add(message)
        }
    }
}

class KnowledgeTurnState {
    private data class Turn(
        val calls: MutableMap<String, Boolean> = linkedMapOf(),
        var denied: Boolean = false,
        val citations: MutableMap<String, String> = linkedMapOf(),
        val injected: MutableSet<String> = linkedSetOf(),
        var usage: RagUsage? = null,
    )
    private val turns = mutableMapOf<Pair<String, String>, Turn>()

    /** 从已保存的工具步骤恢复次数、编号与拒绝；首次种入后不重复计算。 */
    @Synchronized
    fun seed(session: Session, turnId: String, injected: List<RagReference> = emptyList()) {
        val key = session.id to turnId
        if (key !in turns && turns.size >= 128) turns.remove(turns.keys.first())
        val messages = knowledgeTurnMessages(session, turnId)
        val state = turns.getOrPut(key) {
            Turn().apply {
                this.injected += messages.flatMap { it.ragReferences.orEmpty() }.map { it.id }
                messages.flatMap { it.ragReferences.orEmpty() }.forEach { reference ->
                    reference.citationId?.takeIf { it.isNotBlank() }?.let { citations[reference.id] = it }
                }
                messages.flatMap { it.executionSteps.orEmpty() }
                    .filter { it.type == "tool_call" && it.toolName == "knowledge_search" }
                    .distinctBy { it.toolCallId ?: it.id }.forEach { step ->
                        calls[step.id] = calls.values.count { it } < 6
                    }
                denied = messages.any { it.role == MessageRole.TOOL && it.name == "request_knowledge_access" &&
                    (it.content.contains("用户未授权") || it.content.contains("工具执行已拒绝")) }
                messages.flatMap { it.executionSteps.orEmpty() }.filter { it.toolName == "knowledge_search" }.forEach { step ->
                    runCatching {
                        com.promenar.infinity.data.model.json.decodeFromString<KnowledgeSearchData>(step.data ?: "").results
                    }.getOrNull()?.forEach { hit -> citations[hit.referenceId] = hit.citationId }
                }
                messages.flatMap { it.executionSteps.orEmpty() }.filter { it.toolName == "knowledge_search" && it.type == "tool_result" }
                    .distinctBy { it.toolCallId ?: it.id }.forEach { step ->
                        val saved = runCatching { com.promenar.infinity.data.model.json.decodeFromString<KnowledgeSearchData>(step.data ?: "") }.getOrNull()
                        usage = mergeKnowledgeUsage(usage, saved?.usage)
                    }
            }
        }
        state.denied = state.denied || messages.any { it.role == MessageRole.TOOL &&
            it.name == "request_knowledge_access" && (it.content.contains("用户未授权") || it.content.contains("工具执行已拒绝")) }
        state.injected += injected.map { it.id }
    }

    @Synchronized fun consume(sessionId: String, turnId: String, callId: String = java.util.UUID.randomUUID().toString()): Boolean {
        val state = turns.getOrPut(sessionId to turnId) { Turn() }
        return state.calls.getOrPut(callId) { state.calls.values.count { it } < 6 }
    }
    @Synchronized fun citation(sessionId: String, turnId: String, id: String): String {
        val state = turns.getOrPut(sessionId to turnId) { Turn() }
        return state.citations.getOrPut(id) { "K${(state.citations.values.mapNotNull { it.removePrefix("K").toIntOrNull() }.maxOrNull() ?: 0) + 1}" }
    }
    @Synchronized fun citationMapping(sessionId: String, turnId: String): Map<String, String> =
        turns[sessionId to turnId]?.citations?.toMap().orEmpty()
    @Synchronized fun isInjected(sessionId: String, turnId: String, id: String): Boolean = turns[sessionId to turnId]?.injected?.contains(id) == true
    @Synchronized fun deny(sessionId: String, turnId: String) { turns.getOrPut(sessionId to turnId) { Turn() }.denied = true }
    @Synchronized fun isDenied(sessionId: String, turnId: String): Boolean = turns[sessionId to turnId]?.denied == true
    @Synchronized fun addUsage(sessionId: String, turnId: String, usage: RagUsage?) {
        if (usage == null) return
        val state = turns.getOrPut(sessionId to turnId) { Turn() }
        state.usage = RagUsage((state.usage?.ragSystem ?: 0) + usage.ragSystem, state.usage?.isEstimated == true || usage.isEstimated)
    }
    @Synchronized fun usage(sessionId: String, turnId: String): RagUsage? = turns[sessionId to turnId]?.usage
    @Synchronized fun forget(sessionId: String) { turns.keys.removeAll { it.first == sessionId } }
}

fun mergeKnowledgeUsage(automatic: RagUsage?, tool: RagUsage?): RagUsage? =
    if (automatic == null && tool == null) null else RagUsage(
        (automatic?.ragSystem ?: 0) + (tool?.ragSystem ?: 0),
        automatic?.isEstimated == true || tool?.isEstimated == true,
    )
