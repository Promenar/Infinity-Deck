package com.promenar.infinity.ui.chat.manager

import com.promenar.infinity.data.model.ApprovalRequest
import com.promenar.infinity.data.model.ApprovalCallIdentity
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.LoopStatus
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.repository.ToolExecutionKey
import com.promenar.infinity.data.repository.ToolExecutionLedger
import com.promenar.infinity.data.repository.ToolLedgerState
import com.promenar.infinity.data.repository.ToolTerminalRequest
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.data.repository.ApprovalTransition
import com.promenar.infinity.data.repository.ExactToolApprovalCompletion
import com.promenar.infinity.data.repository.ExactToolApprovalDecision
import com.promenar.infinity.data.repository.ToolApprovalRequestFactory
import com.promenar.infinity.data.repository.ToolAllowlistStore
import com.promenar.infinity.data.repository.ToolAllowlistScope
import com.promenar.infinity.data.repository.ToolAllowlistScopes
import com.promenar.infinity.domain.model.ExecutionModeCodec
import com.promenar.infinity.ui.chat.ChatStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import com.promenar.infinity.data.session.SessionExecutionGate

class ApprovalManager(
    private val store: ChatStore,
    private val ledger: ToolExecutionLedger,
    private val messageManager: MessageManager,
    private val sessionRepository: ISessionRepository,
    private val executionGate: SessionExecutionGate? = null,
    private val toolAllowlistStore: ToolAllowlistStore? = null,
    private val resolveExecutionTarget: (suspend (Session, String, ApprovalCallIdentity) -> String?)? = null,
    private val resolveAllowlistScope: suspend (String, String) -> ToolAllowlistScope? = { id, target ->
        ToolAllowlistScopes.resolveForSession(id, target)
    },
    private val timedGrants: com.promenar.infinity.data.approval.TimedApprovalGrantStore? = null,
) {
    private val approvalLocks = ConcurrentHashMap<String, Mutex>()
    private var onGenerateMessage: (suspend (sessionId: String, content: String, isResumption: Boolean) -> Unit)? = null
    private var onExecuteTools: (suspend (
        sessionId: String,
        targetMessageId: String,
        allowedToolCallIds: Set<String>,
    ) -> Unit)? = null

    fun setCallbacks(
        onGenerateMessage: (suspend (sessionId: String, content: String, isResumption: Boolean) -> Unit)?,
        onExecuteTools: (suspend (
            sessionId: String,
            targetMessageId: String,
            allowedToolCallIds: Set<String>,
        ) -> Unit)?
    ) {
        this.onGenerateMessage = onGenerateMessage
        this.onExecuteTools = onExecuteTools
    }

    suspend fun setApprovalRequest(sessionId: String, request: ApprovalRequest?) {
        if (request != null) {
            val gate = executionGate
            if (gate != null) return gate.withNewSessionAdmission(sessionId) {
                setApprovalRequestAdmitted(sessionId, request)
            }
        }
        setApprovalRequestAdmitted(sessionId, request)
    }

    private suspend fun setApprovalRequestAdmitted(sessionId: String, request: ApprovalRequest?) {
        sessionRepository.updatePartial(sessionId, mapOf("approvalRequest" to request))
        store.updateSession(sessionId) { s ->
            s.copy(approvalRequest = request)
        }
    }

    suspend fun resumeGeneration(
        sessionId: String,
        expectedRequest: ApprovalRequest? = null,
        approved: Boolean = true,
        intervention: String? = null,
        rememberTarget: Boolean = false,
    ) {
        val action: suspend () -> Unit = {
            store.turnAdmission.withLock(sessionId) {
                approvalLock(sessionId).withLock {
                    resumeGenerationLocked(sessionId, expectedRequest, approved, intervention, rememberTarget)
                }
            }
        }
        val gate = executionGate
        if (gate == null) action() else gate.withNewSessionAdmission(sessionId, action)
    }

    /**
     * 依次精确批准 [expectedRequest] 所在批次的全部剩余调用；最后一个决定后按常规流程继续生成。
     * 继续生成后产生的新审批属于其它助手消息，不在本次批准范围内。
     */
    suspend fun approveAll(sessionId: String, expectedRequest: ApprovalRequest) {
        val assistantMessageId = expectedRequest.assistantMessageId ?: return
        var request: ApprovalRequest? = expectedRequest
        var remaining = expectedRequest.calls.size.coerceAtLeast(1)
        while (request != null && remaining-- > 0) {
            val current: ApprovalRequest = request
            resumeGeneration(sessionId, expectedRequest = current, approved = true)
            request = store.getSession(sessionId)?.approvalRequest
                ?.takeIf { it.assistantMessageId == assistantMessageId && it != current }
        }
    }

    /** 只批准当前批次连续的同类调用；下一张异类卡片保留待审批。 */
    suspend fun approveWithTimedGrant(sessionId: String, expectedRequest: ApprovalRequest) {
        val grants = timedGrants ?: return
        val category = expectedRequest.calls.firstOrNull()?.grantCategory ?: return
        val assistantMessageId = expectedRequest.assistantMessageId ?: return
        val action: suspend () -> Unit = {
            store.turnAdmission.withLock(sessionId) {
                approvalLock(sessionId).withLock {
                    var request = expectedRequest
                    var remaining = expectedRequest.calls.size
                    var granted = false
                    while (remaining-- > 0 && request.calls.firstOrNull()?.grantCategory == category) {
                        val current = request
                        resumeGenerationLocked(sessionId, current, true, null, false) {
                            if (!granted) {
                                grants.grant(sessionId, category)
                                granted = true
                            }
                        }
                        request = store.getSession(sessionId)?.approvalRequest?.takeIf {
                            it.assistantMessageId == assistantMessageId && it != current
                        } ?: break
                    }
                }
            }
        }
        val gate = executionGate
        if (gate == null) action() else gate.withNewSessionAdmission(sessionId, action)
    }

    private suspend fun resumeGenerationLocked(
        sessionId: String,
        expectedRequest: ApprovalRequest?,
        approved: Boolean,
        intervention: String?,
        rememberTarget: Boolean,
        onApproved: (() -> Unit)? = null,
    ) {
        val session = store.getSession(sessionId) ?: return
        val approvalRequest = session.approvalRequest ?: return
        if (approvalRequest.type != ToolApprovalRequestFactory.TOOL_APPROVAL_TYPE || approvalRequest.calls.isEmpty()) {
            // 旧版本遗留、没有精确调用身份的审批（含已废弃的续跑确认）不再有执行语义：
            // 待审批调用写入取消结果并关闭请求，循环暂停，由用户重新发起。
            terminateCurrentApproval(sessionId, ToolLedgerState.CANCELLED)
            // 遗留请求可能不在审批台账中，显式持久化清空，避免重启后卡片复现。
            setApprovalRequest(sessionId, null)
            setLoopStatus(sessionId, LoopStatus.PAUSED)
            return
        }
        val exact = expectedRequest ?: return
        if (approvalRequest != exact || !ToolApprovalRequestFactory.isValid(exact)) return
        resumeExactToolApproval(sessionId, session, exact, approved, intervention, rememberTarget, onApproved)
    }

    private suspend fun resumeExactToolApproval(
        sessionId: String,
        session: com.promenar.infinity.data.model.Session,
        request: ApprovalRequest,
        approved: Boolean,
        intervention: String?,
        rememberTarget: Boolean,
        onApproved: (() -> Unit)? = null,
    ) {
        if (approved && onExecuteTools == null) return
        val assistantMessageId = request.assistantMessageId ?: return
        val targetMessage = session.messages.firstOrNull {
            it.id == assistantMessageId && it.role == MessageRole.ASSISTANT
        } ?: return
        val currentCall = request.calls.first()
        val resolve = resolveExecutionTarget
        if (approved && currentCall.executionTarget != null && resolve != null) {
            val resolved = try {
                resolve(session, assistantMessageId, currentCall)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (resolved != currentCall.executionTarget) {
                terminateCurrentApproval(sessionId, ToolLedgerState.CANCELLED, assistantMessageId)
                setApprovalRequest(sessionId, null)
                setLoopStatus(sessionId, LoopStatus.PAUSED)
                return
            }
        }
        val decision = ledger.decideExactToolApproval(sessionId, request, approved)
        if (decision !is ExactToolApprovalDecision.Decided) return
        if (approved) onApproved?.invoke()

        val decisionStep = ExecutionStep(
            id = "dec_${System.currentTimeMillis()}",
            type = "intervention_result",
            content = when {
                intervention != null -> "Human Instruction: $intervention"
                approved -> "User Approved"
                else -> "User Rejected"
            },
            timestamp = System.currentTimeMillis(),
        )
        store.updateMessageInSession(sessionId, assistantMessageId) { message ->
            message.copy(
                executionSteps = message.executionSteps.orEmpty()
                    .filter { it.type != "intervention_required" } + decisionStep,
            )
        }
        if (intervention != null) setPendingIntervention(sessionId, intervention)

        decision.terminalMessages.forEach { message ->
            messageManager.mirrorPersistedMessage(sessionId, message)
        }
        if (approved) {
            try {
                onExecuteTools?.invoke(
                    sessionId,
                    assistantMessageId,
                    setOf(decision.key.toolCallId),
                )
            } catch (cancelled: CancellationException) {
                try {
                    withContext(NonCancellable) {
                        completeOrCompensate(
                            sessionId,
                            request,
                            ToolLedgerState.CANCELLED,
                            "工具执行协程已取消",
                        )
                    }
                } catch (cleanupFailure: Throwable) {
                    if (cleanupFailure !== cancelled) cancelled.addSuppressed(cleanupFailure)
                }
                throw cancelled
            } catch (error: Exception) {
                val completed = completeOrCompensate(
                    sessionId,
                    request,
                    ToolLedgerState.FAILED,
                    error.message ?: error::class.java.simpleName,
                )
                if (completed != null && completed.transition.loopStatus != LoopStatus.WAITING_FOR_APPROVAL) {
                    onGenerateMessage?.invoke(sessionId, intervention.orEmpty(), true)
                }
                return
            }
        }
        val directCompletion = ledger.completeExactToolApproval(sessionId, request)
        val completion = if (directCompletion is ExactToolApprovalCompletion.Completed) {
            mirrorApprovalTransition(directCompletion.transition)
            directCompletion
        } else if (approved) {
            completeOrCompensate(
                sessionId,
                request,
                ToolLedgerState.FAILED,
                "工具执行未写入稳定终态",
            ) ?: return
        } else {
            return
        }
        // 批次中仍有待决定的调用时继续等待；全部决定后（含拒绝）都继续生成，
        // 模型据已拒绝/已执行的结果收尾或改用其它做法，与系统提示的审批约定一致。
        if (completion.transition.loopStatus == LoopStatus.WAITING_FOR_APPROVAL) return
        onGenerateMessage?.invoke(sessionId, intervention.orEmpty(), true)
    }

    suspend fun cancelPendingApproval(sessionId: String) {
        approvalLock(sessionId).withLock {
            terminateCurrentApproval(sessionId, ToolLedgerState.CANCELLED)
        }
    }

    private fun approvalLock(sessionId: String): Mutex =
        approvalLocks.computeIfAbsent(sessionId) { Mutex() }

    private suspend fun completeOrCompensate(
        sessionId: String,
        request: ApprovalRequest,
        state: ToolLedgerState,
        error: String,
    ): ExactToolApprovalCompletion.Completed? {
        val existing = ledger.completeExactToolApproval(sessionId, request)
        if (existing is ExactToolApprovalCompletion.Completed) {
            mirrorApprovalTransition(existing.transition)
            return existing
        }
        val compensation = ledger.compensateExactToolApproval(sessionId, request, state, error)
        if (compensation !is ExactToolApprovalDecision.Decided) return null
        compensation.terminalMessages.forEach { message ->
            messageManager.mirrorPersistedMessage(sessionId, message)
        }
        val completion = ledger.completeExactToolApproval(sessionId, request)
        if (completion !is ExactToolApprovalCompletion.Completed) return null
        mirrorApprovalTransition(completion.transition)
        return completion
    }

    private suspend fun terminateCurrentApproval(
        sessionId: String,
        state: ToolLedgerState,
        assistantMessageId: String? = null,
    ) {
        val session = store.getSession(sessionId) ?: return
        val target = session.messages.lastOrNull {
            it.role == MessageRole.ASSISTANT && !it.pendingApprovalToolIds.isNullOrEmpty() &&
                (assistantMessageId == null || it.id == assistantMessageId)
        } ?: return
        terminalPendingTools(sessionId, target.id, state)
        mirrorClosedApproval(sessionId, target.id, LoopStatus.PAUSED)
    }

    private suspend fun mirrorClosedApproval(
        sessionId: String,
        assistantMessageId: String,
        loopStatus: LoopStatus,
    ) {
        messageManager.mirrorPersistedPendingApprovalState(sessionId, assistantMessageId, null)
        store.updateSession(sessionId) {
            it.copy(approvalRequest = null, loopStatus = loopStatus)
        }
    }

    private suspend fun mirrorApprovalTransition(transition: ApprovalTransition) {
        messageManager.mirrorPersistedPendingApprovalState(
            transition.sessionId,
            transition.currentAssistantMessageId,
            null,
        )
        transition.terminalMessages.forEach { message ->
            messageManager.mirrorPersistedMessage(transition.sessionId, message)
        }
        transition.nextAssistantMessageId?.let { nextId ->
            messageManager.mirrorPersistedPendingApprovalState(
                transition.sessionId,
                nextId,
                transition.nextPendingToolCallIds,
            )
        }
        store.updateSession(transition.sessionId) {
            it.copy(
                approvalRequest = transition.approvalRequest,
                loopStatus = transition.loopStatus,
            )
        }
    }

    private suspend fun terminalPendingTools(
        sessionId: String,
        assistantMessageId: String,
        state: ToolLedgerState,
    ) {
        val session = store.getSession(sessionId) ?: return
        val target = session.messages.firstOrNull { it.id == assistantMessageId } ?: return
        val pendingIds = target.pendingApprovalToolIds.orEmpty().toSet()
        if (pendingIds.isEmpty()) return
        val requests = target.toolCalls.orEmpty()
            .filter { it.id in pendingIds }
            .mapTo(mutableSetOf()) { call ->
                ToolTerminalRequest(
                    key = ToolExecutionKey(sessionId, assistantMessageId, call.id),
                    toolName = call.name,
                    thoughtSignature = target.thoughtSignature,
                )
            }
        ledger.terminalizeWithResults(requests, state).forEach { message ->
            messageManager.mirrorPersistedMessage(sessionId, message)
        }
    }

    suspend fun setExecutionMode(sessionId: String, mode: String) {
        val normalized = ExecutionModeCodec.serialize(ExecutionModeCodec.parseOrWorkspace(mode))
        sessionRepository.updatePartial(sessionId, mapOf("executionMode" to normalized))
        store.updateSession(sessionId) { s ->
            s.copy(executionMode = normalized)
        }
    }

    suspend fun setLoopStatus(sessionId: String, status: LoopStatus) {
        sessionRepository.updatePartial(sessionId, mapOf("loopStatus" to status))
        store.updateSession(sessionId) { s ->
            s.copy(loopStatus = status)
        }
    }

    fun setPendingIntervention(sessionId: String, intervention: String?) {
        store.updateSession(sessionId) { s ->
            s.copy(pendingIntervention = intervention)
        }
    }
}
