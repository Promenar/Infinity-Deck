package com.promenar.infinity.ui.chat.manager

import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.ToolCall
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.model.UpdateMessageOptions
import com.promenar.infinity.data.model.withinHunkBudget
import com.promenar.infinity.data.generation.SessionToolResolver
import com.promenar.infinity.domain.repository.ITaskRepository
import com.promenar.infinity.data.repository.ToolExecutionKey
import com.promenar.infinity.data.repository.ToolExecutionLedger
import com.promenar.infinity.data.repository.ToolExecutionOutcome
import com.promenar.infinity.data.repository.ToolInvocationIdentityFactory
import com.promenar.infinity.data.repository.ToolInvocationIdentityResolution
import com.promenar.infinity.data.repository.ToolInvocationIdentity
import com.promenar.infinity.data.repository.ToolInvocationIdentityErrorCode
import com.promenar.infinity.data.repository.ToolDefinitionDigestResolution
import com.promenar.infinity.data.repository.ToolRegistrationResult
import com.promenar.infinity.data.repository.ToolLedgerState
import com.promenar.infinity.data.repository.ToolTerminalRequest
import com.promenar.infinity.ui.chat.ChatStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.promenar.infinity.data.session.SessionExecutionGate

import com.promenar.infinity.ui.chat.manager.registry.SkillRegistry
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.registry.toProtocolTool
import com.promenar.infinity.domain.tool.ToolArgumentsValidation
import com.promenar.infinity.domain.tool.ToolArgumentsValidator
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.encodeToString
import com.promenar.infinity.utils.SensitiveDataRedactor
import com.promenar.infinity.utils.HttpsUrlValidator
import java.util.Base64
import java.security.MessageDigest

internal data class ToolExecutorHooks(
    val beforeAppendStep: suspend () -> Unit = {},
    val afterSkillExecution: suspend () -> Unit = {},
    val beforeLedgerFinish: suspend () -> Unit = {},
)

class ToolExecutor internal constructor(
    private val store: ChatStore,
    private val messageManager: MessageManager,
    private val skillRegistry: SkillRegistry?,
    private val toolResolver: SessionToolResolver,
    private val taskRepository: ITaskRepository? = null,
    private val ledger: ToolExecutionLedger? = null,
    private val executionGate: SessionExecutionGate? = null,
    private val hooks: ToolExecutorHooks = ToolExecutorHooks(),
    private val grantVerifier: (suspend (Session, SkillDefinition, JsonObject, String) -> Boolean)? = null,
    /** 运行中命令的实时输出，按工具调用步骤 id 登记。 */
    private val liveOutput: com.promenar.infinity.data.exec.ToolLiveOutputStore? = null,
    private val unavailableLedgerMessage: () -> String = { "TOOL_LEDGER_UNAVAILABLE" },
    private val afterTools: suspend (String) -> Unit = {},
) {
    suspend fun executeTools(
        sessionId: String,
        assistantMessageId: String,
        toolCalls: List<ToolCall>,
        allowedToolCallIds: Set<String> = toolCalls.mapTo(mutableSetOf()) { it.id },
        preparedTools: List<ProtocolTool>? = null,
        expectedGrantTargets: Map<String, String> = emptyMap(),
        timedGrantTargets: Map<String, String?> = emptyMap(),
    ) {
        val grantTargets = expectedGrantTargets.toMap()
        val timedTargets = timedGrantTargets.filterKeys { it in allowedToolCallIds }.toMap()
        val gate = executionGate
        suspend fun executeAndReconcile() {
            try {
                executeToolsAdmitted(sessionId, assistantMessageId, toolCalls, allowedToolCallIds, preparedTools, grantTargets, timedTargets)
            } finally {
                withContext(NonCancellable + kotlinx.coroutines.Dispatchers.IO) { afterTools(sessionId) }
            }
        }
        if (gate == null) executeAndReconcile()
        else gate.withNewSessionAdmission(sessionId) { executeAndReconcile() }
    }

    /**
     * 预算耗尽等运行时原因下不执行本轮调用，为每个调用写入可回传的失败结果，
     * 保证协议上的 tool_call / tool_result 成对且模型能据此收尾。
     */
    suspend fun rejectTools(
        sessionId: String,
        assistantMessageId: String,
        toolCalls: List<ToolCall>,
        reason: String,
    ) {
        if (com.promenar.infinity.startup.StartupWriteBarrier.isSubsystemIsolated(com.promenar.infinity.startup.StartupSubsystem.TOOL_LEDGER)) {
            for (call in toolCalls.distinctBy { it.id }) messageManager.addMessage(sessionId, Message(
                id = "diagnostic_rejected_${java.util.UUID.randomUUID()}", role = MessageRole.TOOL, toolCallId = call.id,
                parentMessageId = assistantMessageId, name = call.name, content = reason, createdAt = System.currentTimeMillis(),
            ))
            return
        }
        val activeLedger = ledger ?: return
        val targetMsg = store.getSession(sessionId)?.messages?.find { it.id == assistantMessageId } ?: return
        for (tc in toolCalls.distinctBy { it.id }) {
            val key = ToolExecutionKey(sessionId, assistantMessageId, tc.id)
            val persisted = activeLedger.invocationIdentity(key)
            if (persisted != null) {
                if (activeLedger.state(key) !in setOf(ToolLedgerState.PENDING_APPROVAL, ToolLedgerState.APPROVED, ToolLedgerState.RUNNING)) continue
                activeLedger.terminalizeWithResults(setOf(ToolTerminalRequest(key, tc.name, targetMsg.thoughtSignature,
                    interruptionReason = reason)), ToolLedgerState.CANCELLED)
                    .forEach { messageManager.mirrorPersistedMessage(sessionId, it) }
            } else {
                finishInvalidCall(activeLedger, key, targetMsg, tc, ToolPreflight.Invalid(
                    identity = rejectedIdentity(tc), persistedIdentity = null, feedback = reason,
                ))
            }
        }
    }

    private fun rejectedIdentity(call: ToolCall) = ToolInvocationIdentity(
        runtimeToolId = "rejected_tool",
        toolName = "rejected_tool",
        argumentsDigest = sha256("infinity:rejected-tool-arguments:v1\u0000${call.name}\u0000${call.arguments}"),
        definitionDigest = sha256("infinity:rejected-tool-definition:v1"),
        requiresApproval = false,
    )

    private suspend fun executeToolsAdmitted(
        sessionId: String,
        assistantMessageId: String,
        toolCalls: List<ToolCall>,
        allowedToolCallIds: Set<String> = toolCalls.mapTo(mutableSetOf()) { it.id },
        preparedTools: List<ProtocolTool>? = null,
        expectedGrantTargets: Map<String, String> = emptyMap(),
        timedGrantTargets: Map<String, String?> = emptyMap(),
    ) {
        val session = store.getSession(sessionId) ?: return
        val targetMsgId = assistantMessageId

        val targetMsg = session.messages.find { it.id == targetMsgId } ?: return

        val sessionAllowedTools = if (session.options.toolsEnabled) emptyList() else toolResolver.resolveForExecution(session).filter {
            com.promenar.infinity.data.generation.SessionToolAvailability.allows(session, it)
        }
        val admittedCalls = toolCalls.filter { call ->
            if (session.options.toolsEnabled || sessionAllowedTools.any { it.function.name == call.name }) true
            else {
                messageManager.addMessage(sessionId, Message(
                    id = "tool_shield_${java.util.UUID.randomUUID()}", role = MessageRole.TOOL,
                    toolCallId = call.id, parentMessageId = targetMsg.id, name = call.name,
                    content = disabledToolsMessage(sessionAllowedTools.any { it.function.name == "web_search" }),
                    thoughtSignature = targetMsg.thoughtSignature, createdAt = System.currentTimeMillis(),
                ))
                false
            }
        }
        if (admittedCalls.isEmpty()) return

        if (com.promenar.infinity.startup.StartupWriteBarrier.isSubsystemIsolated(com.promenar.infinity.startup.StartupSubsystem.TOOL_LEDGER)) {
            executeDiagnosticsWithoutLedger(session, targetMsg, admittedCalls, preparedTools)
            return
        }
        val activeLedger = ledger ?: return

        val uniqueCalls = admittedCalls.distinctBy { it.id }
        for ((index, tc) in uniqueCalls.withIndex()) {
            if (kotlinx.coroutines.currentCoroutineContext()[com.promenar.infinity.data.exec.SteeringContext]?.pending() == true) {
                rejectTools(sessionId, assistantMessageId, uniqueCalls.drop(index), "用户插入了新消息，此调用未执行。")
                break
            }
            val key = ToolExecutionKey(sessionId, targetMsgId, tc.id)
            val persistedIdentity = activeLedger.invocationIdentity(key)
            val preflight = preflight(
                call = tc,
                persistedIdentity = persistedIdentity,
                preparedTools = preparedTools,
                requiresApproval = tc.id !in allowedToolCallIds,
            )
            if (preflight is ToolPreflight.Invalid) {
                try {
                    finishInvalidCall(activeLedger, key, targetMsg, tc, preflight)
                } catch (cancelled: CancellationException) {
                    terminalizeInvocationCancellation(
                        activeLedger = activeLedger,
                        key = key,
                        call = tc,
                        targetMsg = targetMsg,
                        expectedIdentity = preflight.persistedIdentity ?: preflight.identity,
                        cancelled = cancelled,
                    )
                    throw cancelled
                }
                continue
            }
            preflight as ToolPreflight.Valid
            try {
                val registration = activeLedger.register(key, preflight.identity)
                if (registration == ToolRegistrationResult.Conflict) continue
                if (tc.id !in allowedToolCallIds) continue
                val expectedIdentity = activeLedger.invocationIdentity(key)
                    ?.takeIf { it == preflight.identity }
                    ?: continue
                if (!activeLedger.claim(key, expectedIdentity)) continue
                executeClaimedTool(
                    sessionId = sessionId,
                    targetMsgId = targetMsgId,
                    targetMsg = targetMsg,
                    call = tc,
                    arguments = preflight.arguments,
                    key = key,
                    expectedIdentity = expectedIdentity,
                    activeLedger = activeLedger,
                    expectedGrantTargets = expectedGrantTargets,
                    timedGrantTargets = timedGrantTargets,
                )
            } catch (cancelled: CancellationException) {
                terminalizeInvocationCancellation(
                    activeLedger = activeLedger,
                    key = key,
                    call = tc,
                    targetMsg = targetMsg,
                    expectedIdentity = preflight.identity,
                    cancelled = cancelled,
                )
                throw cancelled
            }
        }
    }

    /** 台账不可用时仅执行已广告且定义一致的只读诊断，不触碰损坏台账或批准任何处置。 */
    private suspend fun executeDiagnosticsWithoutLedger(
        session: Session,
        targetMessage: Message,
        calls: List<ToolCall>,
        preparedTools: List<ProtocolTool>?,
    ) {
        val available = toolResolver.resolveForExecution(session)
        for (call in calls.distinctBy { it.id }) {
            val preflight = preflight(call, null, preparedTools, requiresApproval = false)
            val valid = preflight as? ToolPreflight.Valid
            val skill = valid?.identity?.runtimeToolId?.let { skillRegistry?.getSkillByRuntimeToolId(it) }
            val tool = available.singleOrNull { it.function.name == call.name && it.runtimeToolId == valid?.identity?.runtimeToolId }
            val exact = tool?.let(ToolInvocationIdentityFactory::definitionDigest) as? ToolDefinitionDigestResolution.Valid
            val result = if (skill?.name == "app_diagnostics" && skill.runtimeToolId == "app_diagnostics" &&
                skill.sourceId == "builtin" && skill.risk == com.promenar.infinity.domain.tool.ToolRisk.SAFE_READ &&
                valid != null && exact?.digest == valid.identity.definitionDigest) {
                executeSkill(skill, valid.arguments, call, session, null, false, "diagnostic_${call.id}", targetMessage.id)
            } else ToolResult(call.id, unavailableLedgerMessage(), "error")
            messageManager.addMessage(session.id, Message(
                id = "diagnostic_${java.util.UUID.randomUUID()}", role = MessageRole.TOOL, toolCallId = call.id,
                parentMessageId = targetMessage.id, name = call.name, content = result.content,
                thoughtSignature = targetMessage.thoughtSignature, createdAt = System.currentTimeMillis(),
            ))
        }
    }

    private suspend fun executeClaimedTool(
        sessionId: String,
        targetMsgId: String,
        targetMsg: Message,
        call: ToolCall,
        arguments: JsonObject,
        key: ToolExecutionKey,
        expectedIdentity: ToolInvocationIdentity,
        activeLedger: ToolExecutionLedger,
        expectedGrantTargets: Map<String, String>,
        timedGrantTargets: Map<String, String?>,
    ) {
        val currentSession = store.getSession(sessionId)
        val currentResolvedTool = currentSession?.let { toolResolver.resolveForExecution(it) }
            ?.singleOrNull { tool ->
                com.promenar.infinity.data.generation.SessionToolAvailability.allows(currentSession, tool) &&
                    tool.runtimeToolId == expectedIdentity.runtimeToolId &&
                    tool.function.name == expectedIdentity.toolName
            }
        val currentResolvedDigest = currentResolvedTool
            ?.let(ToolInvocationIdentityFactory::definitionDigest)
        val currentSkill = skillRegistry?.getSkillByRuntimeToolId(expectedIdentity.runtimeToolId)
        val currentDefinition = currentSkill?.toProtocolTool()
            ?.let { com.promenar.infinity.data.generation.SessionRunModeToolDefinitions.forSession(it, currentSession?.runMode) }
            ?.let { com.promenar.infinity.data.subagent.SubagentModelToolDefinitions.forExecution(it, currentResolvedTool) }
            ?.let(ToolInvocationIdentityFactory::definitionDigest)
        if (currentSession == null || currentResolvedTool == null ||
            currentResolvedDigest !is ToolDefinitionDigestResolution.Valid ||
            currentResolvedDigest.digest != expectedIdentity.definitionDigest ||
            currentSkill == null || currentSkill.name != expectedIdentity.toolName ||
            currentDefinition !is ToolDefinitionDigestResolution.Valid ||
            currentDefinition.digest != expectedIdentity.definitionDigest
        ) {
            finishClaimedFailure(
                activeLedger = activeLedger,
                key = key,
                toolName = call.name,
                thoughtSignature = targetMsg.thoughtSignature,
            )
            return
        }

        val stepId = "step_${System.currentTimeMillis()}_${call.id}"
        // 工具步骤关联到当前计划焦点；读取失败不影响工具执行。
        val currentFocusId = try {
            taskRepository?.getPlan(sessionId)?.currentFocusStepId
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

        hooks.beforeAppendStep()
        appendStep(sessionId, targetMsgId, ExecutionStep(
            id = stepId,
            type = "tool_call",
            toolName = call.name,
            toolArgs = call.arguments,
            toolCallId = call.id,
            timestamp = System.currentTimeMillis(),
            taskStepId = currentFocusId
        ))

        if (call.id in expectedGrantTargets) {
            val grantStillValid = try {
                grantVerifier?.invoke(currentSession, currentSkill, arguments, expectedGrantTargets.getValue(call.id)) == true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!grantStillValid) {
                finishClaimedFailure(
                    activeLedger = activeLedger,
                    key = key,
                    toolName = call.name,
                    thoughtSignature = targetMsg.thoughtSignature,
                    failureMessage = "工具执行失败：目标授权已失效，已安全终止。",
                    failureReason = "目标授权无效",
                )
                return
            }
        }

        val approvedCall = currentSession.approvalRequest
            ?.takeIf { it.assistantMessageId == targetMsgId }
            ?.calls?.singleOrNull {
                it.toolCallId == call.id && it.runtimeToolId == expectedIdentity.runtimeToolId &&
                    it.argumentsDigest == expectedIdentity.argumentsDigest && it.definitionDigest == expectedIdentity.definitionDigest
            }
        // 限时授权只携带主会话本轮判定的调用目标，不使用持久白名单验证器。
        val timedGrant = call.id in timedGrantTargets
        val approvedTarget = if (timedGrant) timedGrantTargets[call.id] else expectedGrantTargets[call.id] ?: approvedCall?.executionTarget
        val approvalBound = timedGrant || call.id in expectedGrantTargets || expectedIdentity.requiresApproval || approvedCall != null
        val result = executeSkill(currentSkill, arguments, call, currentSession, approvedTarget, approvalBound, stepId, targetMsgId)
        hooks.afterSkillExecution()
        val failed = result.status != "success"
        val safeResultData = sanitizeResultData(result.data, failed, currentSkill)

        // 解析并合并 active search 的 citations 注入消息体，高保真呈现在 UI 上
        var skillCitations: List<com.promenar.infinity.data.model.Citation>? = null
        if (!safeResultData.isNullOrBlank() && !safeResultData.startsWith("data:image/")) {
            try {
                skillCitations = kotlinx.serialization.json.Json.decodeFromString<List<com.promenar.infinity.data.model.Citation>>(safeResultData)
            } catch (_: Exception) {}
        }
        if (!skillCitations.isNullOrEmpty()) {
            // 与执行步骤相同，引用合并须基于最新视图，避免覆盖同批次前序调用尚未刷新的引用。
            messageManager.updateFromLatest(sessionId, targetMsgId) { latest ->
                UpdateMessageOptions(citations = (latest.citations.orEmpty() + skillCitations).distinctBy { it.url })
            }
        }

        val finalContent = if (failed) {
            // 审计缺陷 P0-4：统一文案会让模型失去自我修正依据（盲试三次），透传技能层具体原因
            ToolFailureSummary.format(result.content)
        } else {
            result.content
        }

        appendStep(sessionId, targetMsgId, ExecutionStep(
            id = "res_$stepId",
            type = if (!failed) "tool_result" else "error",
            toolName = call.name,
            toolCallId = call.id,
            content = finalContent,
            data = safeResultData,
            timestamp = System.currentTimeMillis(),
            taskStepId = currentFocusId,
            // 改动摘要只进入界面步骤，不进入下方发给模型的工具结果与账本。
            fileChanges = if (failed) null else result.fileChanges?.takeIf { it.isNotEmpty() }?.withinHunkBudget(),
            media = if (failed) null else result.media?.takeIf { it.isNotEmpty() },
        ))

        hooks.beforeLedgerFinish()
        val toolMessage = activeLedger.finishWithResult(
            key = key,
            toolName = call.name,
            content = finalContent,
            thoughtSignature = targetMsg.thoughtSignature,
            outcome = if (failed) {
                ToolExecutionOutcome.Failed("工具执行失败")
            } else {
                ToolExecutionOutcome.Succeeded
            },
            images = safeResultData?.takeIf { it.startsWith("data:image/") },
            viewedMedia = if (failed) null else result.viewedMedia,
        )
        toolMessage?.let { messageManager.mirrorPersistedMessage(sessionId, it) }
    }

    private suspend fun terminalizeInvocationCancellation(
        activeLedger: ToolExecutionLedger,
        key: ToolExecutionKey,
        call: ToolCall,
        targetMsg: Message,
        expectedIdentity: ToolInvocationIdentity,
        cancelled: CancellationException,
    ) = withContext(NonCancellable) {
        runCatching {
            if (activeLedger.invocationIdentity(key) != expectedIdentity) return@runCatching
            activeLedger.terminalizeWithResults(
                requests = setOf(
                    ToolTerminalRequest(
                        key = key,
                        toolName = call.name,
                        thoughtSignature = targetMsg.thoughtSignature,
                    ),
                ),
                state = ToolLedgerState.CANCELLED,
            ).forEach { message ->
                messageManager.mirrorPersistedMessage(key.sessionId, message)
            }
        }.exceptionOrNull()?.let(cancelled::addSuppressed)
    }

    private suspend fun executeSkill(
        skill: SkillDefinition,
        arguments: JsonObject,
        tc: ToolCall,
        session: com.promenar.infinity.data.model.Session,
        approvedTarget: String?,
        approvalBound: Boolean,
        stepId: String,
        ownerMessageId: String,
    ): ToolResult {
        val sink = liveOutput?.open(stepId) ?: com.promenar.infinity.domain.tool.ToolLiveOutputSink.None
        return try {
            withContext(com.promenar.infinity.data.exec.ToolCallControlContext(session.id, tc.id, stepId)) {
            val execute: suspend () -> ToolResult = { skill.execute(
                arguments,
                object : SkillExecutionContext {
                    override val sessionId = session.id
                    override val runMode = session.runMode
                    override val agentId = session.agentId
                    override val assistantMessageId = ownerMessageId
                    override val workspacePath = session.workspacePath
                    override val approvedExecutionTarget = approvedTarget
                    override val isApprovalBoundExecution = approvalBound
                    override val liveOutput = sink
                    override val toolStepId = stepId
                    override val workspaceRootUuid = session.workspaceRootUuid
                        ?: throw SecurityException("Session workspace root is missing")
                }
            ) }
            val cooperative = skill.sourceId == "builtin" && skill.name in setOf(
                "shell_exec", "python_exec", "termux_exec", "ssh_exec", "job_wait", "agent_task", "agent_wait",
            )
            if (cooperative) execute() else when (val outcome = com.promenar.infinity.data.exec.interruptOnSteering(
                kotlinx.coroutines.currentCoroutineContext()[com.promenar.infinity.data.exec.SteeringContext], execute,
            )) {
                is com.promenar.infinity.data.exec.SteeringStep.Completed -> outcome.value
                com.promenar.infinity.data.exec.SteeringStep.Interrupted -> ToolResult(tc.id, "用户插队打断了此工具调用。", "error")
            }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: IllegalArgumentException) {
            ToolResult(id = tc.id, content = executionFailureDetail(invalid), status = "error")
        } catch (invalid: IllegalStateException) {
            ToolResult(id = tc.id, content = executionFailureDetail(invalid), status = "error")
        } catch (error: Exception) {
            ToolResult(id = tc.id, content = "工具内部错误（${error::class.java.simpleName}）", status = "error")
        } finally {
            liveOutput?.close(stepId, sink)
        }
    }

    /** 参数或状态类错误对模型可纠正：回传脱敏后的原因，而非笼统的“执行失败”。 */
    private fun executionFailureDetail(error: Exception): String =
        error.message?.let(::sanitizeCitationText)?.takeIf(String::isNotBlank) ?: "参数或当前状态不满足工具要求"

    private suspend fun finishInvalidCall(
        activeLedger: ToolExecutionLedger,
        key: ToolExecutionKey,
        targetMessage: Message,
        call: ToolCall,
        preflight: ToolPreflight.Invalid,
    ) {
        preflight.persistedIdentity?.let { persistedIdentity ->
            activeLedger.failAwaitingIdentityConflict(
                key,
                persistedIdentity,
                targetMessage.thoughtSignature,
            )?.let { messageManager.mirrorPersistedMessage(key.sessionId, it) }
            return
        }
        val identity = preflight.identity
        val registration = activeLedger.register(key, identity)
        if (registration == ToolRegistrationResult.Conflict) return
        if (!activeLedger.claim(key, identity)) return
        // 界面只按助手消息的执行步骤展示工具卡片；预检失败也要留下调用与错误两步，否则这次调用在会话里不可见。
        val now = System.currentTimeMillis()
        appendStep(key.sessionId, key.assistantMessageId, ExecutionStep(
            id = "step_${now}_${call.id}",
            type = "tool_call",
            toolName = call.name,
            toolArgs = call.arguments,
            toolCallId = call.id,
            timestamp = now,
        ))
        appendStep(key.sessionId, key.assistantMessageId, ExecutionStep(
            id = "res_step_${now}_${call.id}",
            type = "error",
            toolName = call.name,
            toolCallId = call.id,
            content = preflight.feedback,
            timestamp = now,
        ))
        val terminal = activeLedger.finishWithResult(
            key = key,
            toolName = "invalid_tool",
            content = preflight.feedback,
            thoughtSignature = targetMessage.thoughtSignature,
            outcome = ToolExecutionOutcome.Failed("工具调用预检失败"),
        )
        terminal?.let { messageManager.mirrorPersistedMessage(key.sessionId, it) }
    }

    private suspend fun finishClaimedFailure(
        activeLedger: ToolExecutionLedger,
        key: ToolExecutionKey,
        toolName: String,
        thoughtSignature: String?,
        failureMessage: String = "工具执行失败：工具定义已变化，已安全终止。",
        failureReason: String = "工具定义身份不一致",
    ) {
        val terminal = activeLedger.finishWithResult(
            key = key,
            toolName = toolName,
            content = failureMessage,
            thoughtSignature = thoughtSignature,
            outcome = ToolExecutionOutcome.Failed(failureReason),
        )
        terminal?.let { messageManager.mirrorPersistedMessage(key.sessionId, it) }
    }

    private fun preflight(
        call: ToolCall,
        persistedIdentity: ToolInvocationIdentity?,
        preparedTools: List<ProtocolTool>?,
        requiresApproval: Boolean,
    ): ToolPreflight {
        if (persistedIdentity != null) {
            val arguments = ToolArgumentsValidator().validate(call.arguments)
                as? ToolArgumentsValidation.Valid
            if (arguments == null || call.name != persistedIdentity.toolName ||
                arguments.sha256 != persistedIdentity.argumentsDigest ||
                (requiresApproval && !persistedIdentity.requiresApproval)
            ) {
                return invalidPreflight(
                    call,
                    ToolInvocationIdentityErrorCode.MALFORMED_ARGUMENTS,
                    persistedIdentity,
                )
            }
            return ToolPreflight.Valid(persistedIdentity, arguments.arguments)
        }

        val matchingDefinitions = preparedTools.orEmpty().filter { it.function.name == call.name }
        if (matchingDefinitions.size != 1) {
            val available = preparedTools.orEmpty().map { it.function.name }.sorted()
            return invalidPreflight(
                call,
                ToolInvocationIdentityErrorCode.INVALID_DEFINITION,
                detail = "当前不可用的工具 `${call.name.take(MAX_TOOL_NAME_ECHO_CHARS)}`。" +
                    if (available.isEmpty()) "本轮没有可用工具，请直接回答。"
                    else "可用工具：${available.joinToString(", ")}。",
            )
        }
        return when (val resolved = ToolInvocationIdentityFactory.fromPreparedToolCall(
            call = call,
            tool = matchingDefinitions.single(),
            requiresApproval = requiresApproval,
        )) {
            is ToolInvocationIdentityResolution.Valid -> ToolPreflight.Valid(
                resolved.identity,
                resolved.arguments,
            )
            is ToolInvocationIdentityResolution.Invalid -> invalidPreflight(call, resolved.code, detail = resolved.message)
        }
    }

    private fun invalidPreflight(
        call: ToolCall,
        code: ToolInvocationIdentityErrorCode,
        persistedIdentity: ToolInvocationIdentity? = null,
        detail: String? = null,
    ): ToolPreflight.Invalid = ToolPreflight.Invalid(
        identity = ToolInvocationIdentity(
            runtimeToolId = "invalid_tool",
            toolName = "invalid_tool",
            argumentsDigest = sha256(
                "infinity:invalid-tool-arguments:v1\u0000${call.name}\u0000${call.arguments}",
            ),
            definitionDigest = sha256("infinity:invalid-tool-definition:v1\u0000${code.name}"),
            requiresApproval = false,
        ),
        persistedIdentity = persistedIdentity,
        feedback = invalidCallFeedback(code, detail),
    )

    /** 回传给模型的预检失败说明：给出可纠错的具体原因，不回显参数原文。 */
    private fun invalidCallFeedback(code: ToolInvocationIdentityErrorCode, detail: String?): String {
        val reason = detail?.take(MAX_INVALID_DETAIL_CHARS) ?: when (code) {
            ToolInvocationIdentityErrorCode.MALFORMED_ARGUMENTS -> "参数不是合法 JSON。"
            ToolInvocationIdentityErrorCode.ROOT_ARGUMENTS_NOT_OBJECT -> "参数根节点必须是 JSON object。"
            else -> "工具定义或参数校验未通过。"
        }
        return "$INVALID_TOOL_CALL_PREFIX$reason 请对照工具定义修正后重试，或改用其它可用工具。"
    }

    private sealed interface ToolPreflight {
        data class Valid(
            val identity: ToolInvocationIdentity,
            val arguments: JsonObject,
        ) : ToolPreflight

        data class Invalid(
            val identity: ToolInvocationIdentity,
            val persistedIdentity: ToolInvocationIdentity?,
            val feedback: String,
        ) : ToolPreflight
    }

    private fun sha256(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /**
     * 追加或按 id / (toolCallId, type) 替换执行步骤。
     * 读-改-写必须经 [MessageManager.updateFromLatest] 基于最新视图完成：
     * 若从 store 读取已提交步骤再整体写回，同批次后续调用会用旧列表覆盖节流窗口内尚未刷新的步骤。
     */
    private fun appendStep(sessionId: String, targetMsgId: String, newStep: ExecutionStep) {
        messageManager.updateFromLatest(sessionId, targetMsgId) { latest ->
            val currentSteps = latest.executionSteps.orEmpty()
            val index = currentSteps.indexOfFirst { s ->
                s.id == newStep.id || (newStep.toolCallId != null && s.toolCallId == newStep.toolCallId && s.type == newStep.type)
            }
            val updatedSteps = if (index > -1) {
                currentSteps.toMutableList().apply { set(index, newStep) }
            } else {
                currentSteps + newStep
            }
            UpdateMessageOptions(executionSteps = updatedSteps)
        }
    }

    private fun sanitizeResultData(data: String?, failed: Boolean, skill: SkillDefinition): String? {
        if (failed || data.isNullOrBlank()) return null
        val trimmed = data.trim()
        sanitizeImageData(trimmed)?.let { return it }
        if (trimmed.length > MAX_SAFE_RESULT_DATA_CHARS) return null
        if (skill.sourceId == "builtin" && skill.runtimeToolId == "knowledge_search" && skill.name == "knowledge_search") {
            return runCatching {
                val search = com.promenar.infinity.data.model.json.decodeFromString<com.promenar.infinity.data.knowledge.KnowledgeSearchData>(trimmed)
                require(search.references.size <= 10 && search.references.map { it.id }.distinct().size == search.references.size)
                require(search.results.map { it.referenceId } == search.references.map { it.id })
                require(search.results.all { it.citationId.matches(Regex("K[1-9][0-9]*")) })
                com.promenar.infinity.data.model.json.encodeToString(search)
            }.getOrNull()
        }
        // 生图卡片数据只经契约解码再编码后保存，未知版本与多余字段一律丢弃。
        if (skill.sourceId == "builtin" && skill.name == "generate_image") {
            return com.promenar.infinity.data.model.ImageGenerationResultData.decode(trimmed)?.encode()
        }
        if (skill.sourceId == "builtin" && skill.runtimeToolId == "request_knowledge_access" && skill.name == "request_knowledge_access") {
            return runCatching {
                com.promenar.infinity.data.model.json.encodeToString(
                    com.promenar.infinity.data.model.json.decodeFromString<com.promenar.infinity.data.knowledge.KnowledgeAccessPreview>(trimmed))
            }.getOrNull()
        }
        return runCatching {
            val citations = kotlinx.serialization.json.Json.decodeFromString<
                List<com.promenar.infinity.data.model.Citation>
            >(trimmed)
            val sanitized = citations.take(MAX_SAFE_CITATIONS).mapNotNull { citation ->
                val redactedUrl = SensitiveDataRedactor.redactUrl(citation.url)
                if (!HttpsUrlValidator.isAllowed(redactedUrl)) return@mapNotNull null
                val uri = java.net.URI(redactedUrl)
                val safePath = sanitizeUrlPath(uri.path)
                val safeUrl = java.net.URI(
                    "https",
                    null,
                    uri.host,
                    uri.port,
                    safePath,
                    null,
                    null,
                ).toString()
                com.promenar.infinity.data.model.Citation(
                    title = sanitizeCitationText(citation.title),
                    url = safeUrl,
                    source = citation.source?.let(::sanitizeCitationText),
                )
            }
            sanitized.takeIf { it.isNotEmpty() }?.let {
                kotlinx.serialization.json.Json.encodeToString(it)
            }
        }.getOrNull()?.takeIf { it.length <= MAX_SAFE_RESULT_DATA_CHARS }
    }

    private fun sanitizeImageData(value: String): String? {
        val match = SAFE_IMAGE_DATA_REGEX.matchEntire(value) ?: return null
        val subtype = match.groupValues[1]
        val encoded = match.groupValues[2]
        if (encoded.length > MAX_IMAGE_BASE64_CHARS) return null
        val bytes = runCatching { Base64.getMimeDecoder().decode(encoded) }.getOrNull() ?: return null
        if (bytes.size > MAX_IMAGE_BYTES || !hasExpectedImageMagic(subtype, bytes)) return null
        val binaryProbe = bytes.toString(Charsets.ISO_8859_1)
        if (SENSITIVE_BINARY_MARKER_REGEX.containsMatchIn(binaryProbe)) return null
        return value
    }

    private fun hasExpectedImageMagic(subtype: String, bytes: ByteArray): Boolean = when (subtype) {
        "png" -> bytes.size >= 8 && bytes.take(8) == listOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        ).map { it.toByte() }
        "jpeg" -> bytes.size >= 3 && bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        "webp" -> bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            bytes.copyOfRange(8, 12).decodeToString() == "WEBP"
        else -> false
    }

    private fun sanitizeCitationText(value: String): String = SensitiveDataRedactor.redactMessage(value)
        .replace(BARE_SECRET_REGEX, "[REDACTED]")
        .replace(LOCAL_ABSOLUTE_PATH_REGEX, "[REDACTED]")
        .take(MAX_CITATION_FIELD_CHARS)

    private fun sanitizeUrlPath(path: String?): String {
        if (path.isNullOrBlank()) return ""
        if (LOCAL_ABSOLUTE_PATH_REGEX.containsMatchIn(path)) return "/REDACTED"
        return path.split('/').joinToString("/") { segment ->
            if (segment.length >= 32 || BARE_SECRET_REGEX.containsMatchIn(segment)) "REDACTED" else segment
        }
    }

    private companion object {
        const val INVALID_TOOL_CALL_PREFIX = "工具调用校验失败："
        const val MAX_INVALID_DETAIL_CHARS = 400
        const val MAX_TOOL_NAME_ECHO_CHARS = 64
        const val MAX_SAFE_RESULT_DATA_CHARS = 256 * 1024
        const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
        const val MAX_IMAGE_BASE64_CHARS = ((MAX_IMAGE_BYTES + 2) / 3) * 4 + 1024
        const val MAX_SAFE_CITATIONS = 50
        const val MAX_CITATION_FIELD_CHARS = 512
        val SAFE_IMAGE_DATA_REGEX = Regex(
            "^data:image/(png|jpeg|webp);base64,([A-Za-z0-9+/=\\r\\n]+)$",
        )
        val BARE_SECRET_REGEX = Regex(
            "(?i)(?:bearer\\s+[^\\s]+|sk-[A-Za-z0-9_-]{8,}|api[_-]?key\\s*[:=]\\s*[^\\s,;]+|authorization\\s*[:=]\\s*[^\\s,;]+)",
        )
        val LOCAL_ABSOLUTE_PATH_REGEX = Regex(
            "(?i)(?:/(?:Users|home|private|var|tmp)/[^\\s,;]+|[A-Z]:\\\\[^\\s,;]+)",
        )
        val SENSITIVE_BINARY_MARKER_REGEX = Regex(
            "(?i)(?:authorization|bearer\\s|sk-|api[_-]?key|/(?:Users|home|private|var|tmp)/|[A-Z]:\\\\)",
        )
    }
}

internal fun disabledToolsMessage(webSearchAllowed: Boolean): String = if (webSearchAllowed) {
    """[SYSTEM WARNING]: All tools except web_search are currently DISABLED by the user configuration.
You CANNOT use these other tools in this turn.
Please STOP trying to use these tools and answer the user's request directly using your internal knowledge or the permitted web_search."""
} else {
    """[SYSTEM WARNING]: Tool usage is currently DISABLED by the user configuration.
You CANNOT use tools in this turn.
Please STOP trying to use tools and answer the user's request directly using your internal knowledge."""
}
