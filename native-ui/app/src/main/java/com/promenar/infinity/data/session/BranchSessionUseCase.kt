package com.promenar.infinity.data.session

import androidx.room.withTransaction
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.dao.MessageDao
import com.promenar.infinity.data.local.db.dao.SessionDao
import com.promenar.infinity.data.local.db.entity.MessageEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.model.ToolCall
import com.promenar.infinity.data.model.decodeMessageQuotesOrNull
import java.util.UUID
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.promenar.infinity.data.canvas.CanvasStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class BranchSessionPlan(
    val session: SessionEntity,
    val messages: List<MessageEntity>,
    val messageIdMap: Map<String, String> = emptyMap(),
)

sealed interface BranchSessionPlanResult {
    data class Ready(val plan: BranchSessionPlan) : BranchSessionPlanResult
    data class Rejected(val reason: BranchSessionRejectReason) : BranchSessionPlanResult
}

enum class BranchSessionRejectReason {
    SourceNotFound,
    TargetNotFound,
    TargetNotStable,
}

object BranchSessionPlanner {
    fun plan(
        source: SessionEntity,
        sourceMessages: List<MessageEntity>,
        targetMessageId: String,
        newSessionId: String,
        now: Long,
        branchTitle: String = "${source.title} · Branch",
        newMessageId: (String) -> String,
    ): BranchSessionPlanResult {
        val targetIndex = sourceMessages.indexOfFirst { it.id == targetMessageId }
        if (targetIndex < 0) {
            return BranchSessionPlanResult.Rejected(BranchSessionRejectReason.TargetNotFound)
        }
        val selectedMessages = sourceMessages.take(targetIndex + 1)
        if (!selectedMessages.isStableBranchPrefix()) {
            return BranchSessionPlanResult.Rejected(BranchSessionRejectReason.TargetNotStable)
        }
        val target = selectedMessages.last()
        val idMap = selectedMessages.associate { message -> message.id to newMessageId(message.id) }
        val branchedMessages = selectedMessages.map { message ->
            message.copy(
                id = idMap.getValue(message.id),
                sessionId = newSessionId,
                parentMessageId = message.parentMessageId?.let(idMap::get),
                quotes = message.quotes?.let(::decodeMessageQuotesOrNull)?.map { quote ->
                    quote.copy(sourceMessageId = idMap[quote.sourceMessageId] ?: quote.sourceMessageId)
                }?.let { JSON.encodeToString(it) },
                tokens = null,
                ragProgress = null,
                ragReferencesLoading = 0,
                pendingApprovalToolIds = null,
                planningTask = null,
                isArchived = 0,
                vectorizationStatus = null,
                layoutHeight = null,
            )
        }
        val branchedSession = source.copy(
            id = newSessionId,
            title = branchTitle,
            lastMessage = target.content.take(240).takeIf(String::isNotBlank),
            time = null,
            unread = 0,
            isPinned = 0,
            scrollOffset = null,
            draft = null,
            loopStatus = "idle",
            pendingIntervention = null,
            approvalRequest = null,
            activeTask = null,
            stats = null,
            workspacePath = null,
            workspaceRootUuid = null,
            activeTaskTreeId = null,
            parentSessionId = null,
            branchPlanJson = null,
            worktreeDirectory = null,
            worktreeBranch = null,
            worktreeBase = null,
            parentSessionDeleted = false,
            sessionAutoWakeCount = 0,
            sessionAutoProcessingPaused = false,
            createdAt = now,
            updatedAt = now,
        )
        return BranchSessionPlanResult.Ready(BranchSessionPlan(branchedSession, branchedMessages, idMap))
    }

    private fun MessageEntity.isStableBranchMessage(): Boolean {
        if (status?.lowercase() in UNSTABLE_STATUSES) return false
        if (ragReferencesLoading != 0) return false
        if (!pendingApprovalToolIds.isNullOrBlank()) return false
        if (role == "assistant" && content.isBlank() && toolCalls.isNullOrBlank()) return false
        return true
    }

    private fun List<MessageEntity>.isStableBranchPrefix(): Boolean {
        if (any { !it.isStableBranchMessage() }) return false
        val selectedIds = mapTo(mutableSetOf()) { it.id }
        if (any { it.parentMessageId != null && it.parentMessageId !in selectedIds }) return false

        val declaredToolCalls = mutableMapOf<String, Int>()
        forEachIndexed { index, message ->
            if (message.role != "assistant" || message.toolCalls.isNullOrBlank()) return@forEachIndexed
            val calls = runCatching {
                JSON.decodeFromString<List<ToolCall>>(message.toolCalls)
            }.getOrNull() ?: return false
            if (calls.any { it.id.isBlank() || declaredToolCalls.put(it.id, index) != null }) return false
        }
        val toolResults = filter { it.role == "tool" }
        if (toolResults.any { result ->
                val callId = result.toolCallId ?: return@any true
                val assistantIndex = declaredToolCalls[callId] ?: return@any true
                indexOf(result) <= assistantIndex
            }
        ) return false
        return declaredToolCalls.keys == toolResults.mapNotNullTo(mutableSetOf()) { it.toolCallId }
    }

    private val UNSTABLE_STATUSES = setOf(
        "pending",
        "streaming",
        "generating",
        "thinking",
        "waiting_for_approval",
    )

    private val JSON = Json { ignoreUnknownKeys = true }
}

sealed interface BranchSessionResult {
    data class Success(val sessionId: String) : BranchSessionResult
    data class Rejected(val reason: BranchSessionRejectReason) : BranchSessionResult
}

class BranchSessionUseCase(
    private val database: InfinityDatabase,
    private val sessionDao: SessionDao = database.sessionDao(),
    private val messageDao: MessageDao = database.messageDao(),
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val branchTitle: (String) -> String = { title -> "$title · Branch" },
    private val canvasStore: CanvasStore? = null,
) {
    suspend fun branch(sourceSessionId: String, targetMessageId: String): BranchSessionResult = withContext(Dispatchers.IO) {
        val planned = database.withTransaction {
            val source = sessionDao.getById(sourceSessionId)
                ?: return@withTransaction BranchSessionPlanResult.Rejected(BranchSessionRejectReason.SourceNotFound)
            BranchSessionPlanner.plan(
                source = source,
                sourceMessages = messageDao.getBySession(sourceSessionId),
                targetMessageId = targetMessageId,
                newSessionId = newId(),
                now = now(),
                branchTitle = branchTitle(source.title),
                newMessageId = { newId() },
            )
        }
        if (planned is BranchSessionPlanResult.Rejected) return@withContext BranchSessionResult.Rejected(planned.reason)
        val plan = (planned as BranchSessionPlanResult.Ready).plan
        var copiedSession: String? = null
        suspend fun cleanupCopy() {
            copiedSession?.let { sessionId ->
                withContext(NonCancellable) { canvasStore?.deleteSession(sessionId) }
                copiedSession = null
            }
        }
        try {
            // 画布同步会访问 Room；复制在事务外执行，统一锁顺序，避免 Room 与画布 Mutex 反向等待。
            if (canvasStore != null) withContext(NonCancellable) {
                canvasStore.copySession(sourceSessionId, plan.session.id, plan.messageIdMap)
                copiedSession = plan.session.id
            }
            currentCoroutineContext().ensureActive()
            val result = withContext(NonCancellable) {
                val committed = database.withTransaction {
                    val source = sessionDao.getById(sourceSessionId)
                        ?: return@withTransaction BranchSessionResult.Rejected(BranchSessionRejectReason.SourceNotFound)
                    val refreshed = BranchSessionPlanner.plan(
                        source = source,
                        sourceMessages = messageDao.getBySession(sourceSessionId),
                        targetMessageId = targetMessageId,
                        newSessionId = plan.session.id,
                        now = plan.session.createdAt,
                        branchTitle = plan.session.title,
                        newMessageId = { plan.messageIdMap[it] ?: it },
                    )
                    if (refreshed is BranchSessionPlanResult.Rejected) return@withTransaction BranchSessionResult.Rejected(refreshed.reason)
                    if ((refreshed as BranchSessionPlanResult.Ready).plan != plan) {
                        return@withTransaction BranchSessionResult.Rejected(BranchSessionRejectReason.TargetNotStable)
                    }
                    sessionDao.insert(plan.session)
                    messageDao.insertAll(plan.messages)
                    val citations = database.knowledgeCitationDao().forSession(sourceSessionId).mapNotNull { citation ->
                        citation.messageId?.let(plan.messageIdMap::get)?.let { messageId ->
                            citation.copy(id = UUID.randomUUID().toString(), sessionId = plan.session.id, messageId = messageId)
                        }
                    }
                    if (citations.isNotEmpty()) database.knowledgeCitationDao().insertAll(citations)
                    val versions = database.stateBoardDao().versions(sourceSessionId).mapNotNull { version ->
                        plan.messageIdMap[version.anchorMessageId]?.let { anchor ->
                            version.copy(id = 0, sessionId = plan.session.id, anchorMessageId = anchor)
                        }
                    }
                    database.stateBoardDao().insertAll(versions)
                    BranchSessionResult.Success(plan.session.id)
                }
                // 事务结果与副本所有权一同确认，取消不能清理已经提交的分支。
                if (committed is BranchSessionResult.Success) copiedSession = null
                committed
            }
            if (result is BranchSessionResult.Rejected) cleanupCopy()
            result
        } catch (failure: Throwable) {
            // 文件与 Room 无共享事务，拒绝、取消或数据库提交失败时补偿已发布的画布副本。
            try { cleanupCopy() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
}
