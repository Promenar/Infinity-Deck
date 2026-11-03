package com.promenar.infinity.ui.chat.manager

import com.promenar.infinity.data.model.InferenceParams
import com.promenar.infinity.data.model.ContextCompactionSource
import com.promenar.infinity.data.model.LoopStatus
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.SessionOptions
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.data.session.SessionDeletionResult
import com.promenar.infinity.domain.model.ExecutionModeCodec
import com.promenar.infinity.domain.usecase.withResolvedContextTurns
import com.promenar.infinity.ui.chat.ChatStore

class SessionManager(
    private val store: ChatStore,
    private val sessionRepository: ISessionRepository,
    private val coordinatedDelete: (suspend (String, Boolean) -> SessionDeletionResult)? = null,
    private val timedGrants: com.promenar.infinity.data.approval.TimedApprovalGrantStore? = null,
) {
    constructor(
        store: ChatStore,
        sessionRepository: ISessionRepository,
        coordinatedDelete: suspend (String, Boolean) -> SessionDeletionResult,
    ) : this(store, sessionRepository, coordinatedDelete, null)

    constructor(
        store: ChatStore,
        sessionRepository: ISessionRepository,
        coordinatedDelete: (suspend (String) -> SessionDeletionResult)?,
    ) : this(store, sessionRepository, adaptDelete(coordinatedDelete))

    companion object {
        private fun adaptDelete(delete: (suspend (String) -> SessionDeletionResult)?):
            (suspend (String, Boolean) -> SessionDeletionResult)? {
            if (delete == null) return null
            return { id, keepFiles ->
                check(!keepFiles) { "保留会话文件需要支持保留参数的删除协调器" }
                delete(id)
            }
        }
    }

    suspend fun addSession(session: Session) {
        val enrichedSession = session.copy(
            executionMode = ExecutionModeCodec.serialize(ExecutionModeCodec.parseOrWorkspace(session.executionMode)),
            loopStatus = if (session.loopStatus == LoopStatus.IDLE) LoopStatus.COMPLETED else session.loopStatus,
        )

        sessionRepository.create(enrichedSession)

        store.upsertSession(sessionRepository.getById(enrichedSession.id) ?: enrichedSession)
    }

    suspend fun updateSession(id: String, updates: Map<String, Any?>) {
        val current = store.getSession(id)
        val invalidates = ("modelId" in updates && updates["modelId"] != current?.modelId) ||
            ("summary" in updates && updates["summary"] != current?.summary)
        val effective = if (invalidates && current?.stats?.contextUsageBaseline != null)
            updates + ("stats" to current.stats.copy(contextUsageBaseline = null)) else updates
        val preserveCurrentStats = invalidates && "stats" !in updates
        if ("summary" in updates) {
            withContextMutation(id) { persistSessionUpdates(id, effective, preserveCurrentStats) }
        } else {
            persistSessionUpdates(id, effective, preserveCurrentStats)
        }
    }

    suspend fun updateContextUsageBaseline(id: String, baseline: com.promenar.infinity.data.model.PromptUsageBaseline?) {
        // 先原子合并基线；数据库挂起期间的新计费不能被整份旧统计回写覆盖。
        store.updateSession(id) { session -> session.copy(stats =
            (session.stats ?: com.promenar.infinity.data.model.SessionStats()).copy(contextUsageBaseline = baseline)) }
        val stats = store.getSession(id)?.stats ?: return
        sessionRepository.updatePartial(id, mapOf("stats" to stats))
    }

    private suspend fun persistSessionUpdates(id: String, updates: Map<String, Any?>, preserveCurrentStats: Boolean = false) {
        sessionRepository.updatePartial(id, updates)

        store.updateSession(id) { s ->
            if (!preserveCurrentStats) applyUpdatesToSession(s, updates) else {
                val updated = applyUpdatesToSession(s, updates - "stats")
                updated.copy(stats = updated.stats?.copy(contextUsageBaseline = null))
            }
        }
    }

    suspend fun commitContextCompaction(
        sessionId: String,
        summary: String,
        messageIds: List<String>,
        source: ContextCompactionSource,
    ) =
        withContextMutation(sessionId) {
            val ids = messageIds.toList()
            sessionRepository.commitContextCompaction(sessionId, summary, ids, source)

            val archivedIds = ids.toSet()
            // 摘要、归档与基线失效在同一次状态更新中发布；没有基线时不改写统计。
            val hadBaseline = store.getSession(sessionId)?.stats?.contextUsageBaseline != null
            store.updateSession(sessionId) { session ->
                session.copy(
                    summary = summary,
                    messages = session.messages.map { message ->
                        if (message.id in archivedIds) message.copy(isArchived = true) else message
                    },
                    stats = session.stats?.copy(contextUsageBaseline = null),
                )
            }
            if (hadBaseline) {
                store.getSession(sessionId)?.stats?.let { sessionRepository.updatePartial(sessionId, mapOf("stats" to it)) }
            }
        }

    suspend fun deleteSession(id: String, keepFiles: Boolean = false): SessionDeletionResult {
        val result = coordinatedDelete?.invoke(id, keepFiles) ?: run {
            check(!keepFiles) { "保留会话文件需要删除协调器" }
            sessionRepository.delete(id)
            SessionDeletionResult.Deleted
        }
        when (result) {
            SessionDeletionResult.Deleted,
            SessionDeletionResult.AlreadyDeleted -> Unit
            is SessionDeletionResult.DeletedKeepingFiles -> Unit
            is SessionDeletionResult.Failed -> throw SessionDeletionFailedException(result.error)
        }

        timedGrants?.revokeAll(id)
        store.removeSession(id)
        return result
    }

    fun getSession(id: String): Session? {
        return store.getSession(id)
    }

    suspend fun updateSessionDraft(sessionId: String, draft: String?) {
        updateSession(sessionId, mapOf("draft" to draft))
    }

    suspend fun toggleSessionPin(sessionId: String) {
        val session = store.getSession(sessionId) ?: return
        val newPinned = !session.isPinned
        updateSession(sessionId, mapOf("isPinned" to newPinned))
    }

    suspend fun updateSessionInferenceParams(id: String, params: InferenceParams) {
        updateSession(id, mapOf("inferenceParams" to params.withResolvedContextTurns()))
    }

    suspend fun updateSessionTitle(id: String, title: String) {
        updateSession(id, mapOf("title" to title))
    }

    suspend fun updateSessionPrompt(id: String, prompt: String?) {
        updateSession(id, mapOf("customPrompt" to prompt))
    }

    suspend fun updateSessionExecutionMode(id: String, mode: String) {
        val normalized = ExecutionModeCodec.serialize(ExecutionModeCodec.parseOrWorkspace(mode))
        updateSession(id, mapOf("executionMode" to normalized))
    }

    suspend fun updateSessionModel(id: String, modelId: String?) {
        updateSession(id, mapOf("modelId" to modelId))
    }

    suspend fun updateSessionOptions(id: String, options: SessionOptions, ragOptions: RagOptions? = null) {
        val updates = mutableMapOf<String, Any?>("options" to options)
        if (ragOptions != null) {
            updates["ragOptions"] = ragOptions
        }
        updateSession(id, updates)
    }

    fun updateSessionScrollOffset(id: String, offset: Double) {
        store.updateSession(id) { s -> s.copy(scrollOffset = offset) }
    }

    fun getSessionsByAgent(agentId: String): List<Session> {
        return store.get().sessions
            .filter { it.agentId == agentId }
            .sortedWith(compareBy { if (it.isPinned) -1 else 1 })
    }

    fun setKGExtractionStatus(sessionId: String, isExtracting: Boolean) {
        store.update { state ->
            state.copy(
                activeKGExtractions = if (isExtracting) {
                    state.activeKGExtractions + sessionId
                } else {
                    state.activeKGExtractions - sessionId
                }
            )
        }
    }

    suspend fun toggleMcpServer(sessionId: String, serverId: String) {
        val session = store.getSession(sessionId) ?: return
        val current = session.activeMcpServerIds
        val next = if (current.contains(serverId)) {
            current.filter { it != serverId }
        } else {
            current + serverId
        }
        updateSession(sessionId, mapOf("activeMcpServerIds" to next))
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyUpdatesToSession(session: Session, updates: Map<String, Any?>): Session {
        var result = session
        updates.forEach { (key, value) ->
            result = when (key) {
                "summary" -> result.copy(summary = value as? String)
                "title" -> result.copy(title = value as? String ?: result.title)
                "lastMessage" -> result.copy(lastMessage = value as? String)
                "draft" -> result.copy(draft = value as? String)
                "isPinned" -> result.copy(isPinned = value as? Boolean ?: result.isPinned)
                "modelId" -> result.copy(modelId = value as? String)
                "customPrompt" -> result.copy(customPrompt = value as? String)
                "executionMode" -> result.copy(
                    executionMode = ExecutionModeCodec.serialize(
                        ExecutionModeCodec.parseOrWorkspace(value as? String),
                    ),
                )
                "loopStatus" -> result.copy(loopStatus = value as? LoopStatus ?: result.loopStatus)
                "pendingIntervention" -> result.copy(pendingIntervention = value as? String)
                "approvalRequest" -> result.copy(approvalRequest = value as? com.promenar.infinity.data.model.ApprovalRequest)
                "inferenceParams" -> result.copy(inferenceParams = value as? InferenceParams)
                "activeTask" -> result.copy(activeTask = value as? com.promenar.infinity.data.model.TaskState)
                "stats" -> result.copy(stats = value as? com.promenar.infinity.data.model.SessionStats)
                "options" -> result.copy(options = (value as? SessionOptions) ?: result.options)
                "ragOptions" -> result.copy(ragOptions = value as? RagOptions)
                "activeMcpServerIds" -> result.copy(activeMcpServerIds = value as? List<String> ?: result.activeMcpServerIds)
                "workspacePath" -> result.copy(workspacePath = value as? String)
                "workspaceRootUuid" -> result.copy(workspaceRootUuid = value as? String)
                // null 表示改回会话私有工作区。
                "workspaceId" -> result.copy(workspaceId = value as? String)
                else -> result
            }
        }
        return result
    }
}

class SessionDeletionFailedException(error: com.promenar.infinity.data.session.SessionDeletionError) :
    IllegalStateException("会话删除失败（${error.code}），可安全重试", error.cause)
