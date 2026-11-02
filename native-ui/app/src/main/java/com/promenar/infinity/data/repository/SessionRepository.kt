package com.promenar.infinity.data.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.promenar.infinity.data.local.db.dao.MessageDao
import com.promenar.infinity.data.local.db.dao.SessionDao
import com.promenar.infinity.data.local.db.dao.SessionContextSnapshot
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.model.ApprovalRequest
import com.promenar.infinity.data.model.ContextCompactionSource
import com.promenar.infinity.data.model.InferenceParams
import com.promenar.infinity.data.model.LoopStatus
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.SessionOptions
import com.promenar.infinity.data.model.SessionStats
import com.promenar.infinity.data.model.TaskState
import com.promenar.infinity.data.model.json
import com.promenar.infinity.data.model.toDomain
import com.promenar.infinity.data.model.toEntity
import com.promenar.infinity.domain.model.ExecutionModeCodec
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.serializer
import java.util.UUID

open class SessionRepository(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val newSessionRunMode: () -> SessionRunMode = { SessionRunMode.BUILTIN },
    private val onSessionCreated: suspend (Session) -> Unit = {},
    private val database: RoomDatabase? = null,
) : ISessionRepository,
    com.promenar.infinity.domain.repository.ISessionRepository {

    private inline fun <reified T> encode(value: T): String =
        json.encodeToString(serializer<T>(), value)

    // ── Data-layer ISessionRepository (existing) ────────────────────────

    override suspend fun create(session: Session) {
        val isNew = sessionDao.getById(session.id) == null
        val created = session.copy(runMode = session.runMode ?: newSessionRunMode())
        sessionDao.insert(created.toEntity())
        // 创建后附加动作（知识库自动挂载）失败不影响会话本身的创建。
        if (isNew) try {
            onSessionCreated(created)
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            InfinityLogger.logError("[SessionRepository] 新会话创建回调失败", failure)
        }
    }

    open suspend fun update(session: Session) {
        sessionDao.update(session.toEntity())
    }

    override suspend fun updatePartial(id: String, updates: Map<String, Any?>) {
        if (database != null) database.withTransaction { updatePartialInTransaction(id, updates) }
        else updatePartialInTransaction(id, updates)
    }

    private suspend fun updatePartialInTransaction(id: String, updates: Map<String, Any?>) {
        val existing = sessionDao.getById(id) ?: return
        // updatedAt 是首页排序与显示的最近消息时间：只随新消息推进，草稿、置顶、运行状态等元数据不改它。
        val touched = if ("lastMessage" in updates) existing.copy(updatedAt = System.currentTimeMillis()) else existing
        val updated = applyPartialUpdates(touched, updates)
        if (updates.containsKey("summary")) {
            val summary = updates["summary"]
            require(summary == null || summary is String) { "上下文摘要类型无效" }
            sessionDao.updateWithContextSummary(updated, summary as? String)
        } else {
            sessionDao.update(updated)
        }
    }

    override suspend fun commitContextCompaction(
        sessionId: String,
        summary: String,
        messageIds: List<String>,
        source: ContextCompactionSource,
    ) {
        sessionDao.commitContextCompaction(sessionId, summary, messageIds, source)
    }

    override suspend fun delete(id: String) {
        messageDao.deleteBySessionId(id)
        sessionDao.deleteById(id)
    }

    override suspend fun getById(id: String): Session? {
        return sessionDao.getContextSnapshot(id)?.toSession()
    }

    /** 配置工具不加载消息、草稿或上下文摘要。 */
    suspend fun getConfigurationById(id: String): Session? = sessionDao.getById(id)?.toDomain()?.configurationOnly()

    suspend fun getAllConfigurations(): List<Session> = sessionDao.getAll().map { it.toDomain().configurationOnly() }

    private fun Session.configurationOnly() = copy(messages = emptyList(), draft = null, summary = null, lastMessage = null)

    override suspend fun getAll(): List<Session> {
        return sessionDao.getAll().mapNotNull { entity ->
            try {
                sessionDao.getContextSnapshot(entity.id)?.toSession()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // 日志器仅保留异常类别，不输出 JSON 或消息正文。
                InfinityLogger.logError("SessionRepository.getAll sessionId=${entity.id}", failure)
                null
            }
        }
    }

    private fun SessionContextSnapshot.toSession(): Session = session.toDomain().copy(
        messages = messages.map { it.toDomain() },
        summary = summary?.summaryContent?.takeIf(String::isNotBlank),
    )

    private fun applyPartialUpdates(
        entity: SessionEntity,
        updates: Map<String, Any?>
    ): SessionEntity {
        var result = entity
        updates.forEach { (key, value) ->
            result = when (key) {
                "title" -> result.copy(title = value as? String ?: result.title)
                "lastMessage" -> result.copy(lastMessage = value as? String)
                "draft" -> result.copy(draft = value as? String)
                "isPinned" -> result.copy(isPinned = if (value as? Boolean == true) 1 else 0)
                "modelId" -> result.copy(modelId = value as? String)
                "customPrompt" -> result.copy(customPrompt = value as? String)
                "executionMode" -> result.copy(
                    executionMode = ExecutionModeCodec.serialize(
                        ExecutionModeCodec.parseOrWorkspace(value as? String),
                    ),
                )
                "loopStatus" -> result.copy(loopStatus = (value as? LoopStatus)?.toSerializedName() ?: result.loopStatus)
                "pendingIntervention" -> result.copy(pendingIntervention = value as? String)
                "approvalRequest" -> result.copy(approvalRequest = value?.let { encode(it as ApprovalRequest) })
                "inferenceParams" -> result.copy(inferenceParams = value?.let { encode(it as InferenceParams) })
                "activeTask" -> result.copy(activeTask = value?.let { encode(it as TaskState) })
                "stats" -> result.copy(stats = value?.let { encode(it as SessionStats) })
                "options" -> result.copy(options = value?.let { encode(it as SessionOptions) })
                "ragOptions" -> result.copy(ragOptions = value?.let { encode(it as RagOptions) })
                "activeMcpServerIds" -> result.copy(activeMcpServerIds = value?.let { encode(it as List<String>) })
                "activeSkillIds" -> result.copy(activeSkillIds = value?.let { encode(it as List<String>) })
                "workspacePath" -> result.copy(workspacePath = value as? String)
                "workspaceRootUuid" -> result.copy(workspaceRootUuid = value as? String)
                // null 表示改回会话私有工作区。
                "workspaceId" -> result.copy(workspaceId = value as? String)
                "activeTaskTreeId" -> result.copy(activeTaskTreeId = value as? String)
                else -> result
            }
        }
        return result
    }

    // ── Domain-layer ISessionRepository ─────────────────────────────────

    override fun observeByAgent(agentId: String): Flow<List<com.promenar.infinity.domain.model.Session>> {
        return sessionDao.observeAll().map { entities ->
            entities
                .filter { it.agentId == agentId }
                .map { it.toDomainSession() }
        }
    }

    override fun observeById(id: String): Flow<com.promenar.infinity.domain.model.Session?> {
        return sessionDao.observeById(id).map { it?.toDomainSession() }
    }

    override suspend fun create(
        agentId: String,
        modelId: String
    ): com.promenar.infinity.domain.model.Session {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val dataSession = Session(
            id = id,
            agentId = agentId,
            modelId = modelId,
            runMode = newSessionRunMode(),
            createdAt = now,
            updatedAt = now
        )
        create(dataSession)
        return dataSession.toDomainSession()
    }

    override suspend fun updateTitle(id: String, title: String) {
        sessionDao.updateTitle(id, title, System.currentTimeMillis())
    }

    // ── Mappers ─────────────────────────────────────────────────────────

    private fun SessionEntity.toDomainSession(): com.promenar.infinity.domain.model.Session =
        com.promenar.infinity.domain.model.Session(
            id = id,
            agentId = agentId,
            title = title,
            modelId = modelId ?: "",
            runMode = SessionRunMode.fromStored(runMode),
            parentSessionId = parentSessionId,
            branchPlanJson = branchPlanJson,
            worktreeDirectory = worktreeDirectory,
            worktreeBranch = worktreeBranch,
            worktreeBase = worktreeBase,
            parentSessionDeleted = parentSessionDeleted,
            sessionAutoWakeCount = sessionAutoWakeCount,
            sessionAutoProcessingPaused = sessionAutoProcessingPaused,
            isPinned = isPinned == 1,
            createdAt = createdAt,
            updatedAt = updatedAt
        )

    private fun Session.toDomainSession(): com.promenar.infinity.domain.model.Session =
        com.promenar.infinity.domain.model.Session(
            id = id,
            agentId = agentId,
            title = title,
            modelId = modelId ?: "",
            runMode = runMode,
            parentSessionId = parentSessionId,
            branchPlanJson = branchPlanJson,
            worktreeDirectory = worktreeDirectory,
            worktreeBranch = worktreeBranch,
            worktreeBase = worktreeBase,
            parentSessionDeleted = parentSessionDeleted,
            sessionAutoWakeCount = sessionAutoWakeCount,
            sessionAutoProcessingPaused = sessionAutoProcessingPaused,
            isPinned = isPinned,
            createdAt = createdAt,
            updatedAt = updatedAt,
            messageCount = messages.size
        )
}
