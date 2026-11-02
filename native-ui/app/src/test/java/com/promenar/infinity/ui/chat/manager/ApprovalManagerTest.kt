package com.promenar.infinity.ui.chat.manager

import io.mockk.coEvery
import io.mockk.mockk

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.data.repository.IMessageRepository
import com.promenar.infinity.data.repository.ToolExecutionKey
import com.promenar.infinity.data.repository.ToolExecutionLedger
import com.promenar.infinity.data.repository.ToolExecutionOutcome
import com.promenar.infinity.data.repository.ToolInvocationIdentity
import com.promenar.infinity.data.repository.ToolInvocationIdentityFactory
import com.promenar.infinity.data.repository.ToolInvocationIdentityResolution
import com.promenar.infinity.data.repository.ToolLedgerState
import com.promenar.infinity.data.repository.ToolRegistrationResult
import com.promenar.infinity.data.repository.ToolTerminalRequest
import com.promenar.infinity.data.repository.ApprovalCallCandidate
import com.promenar.infinity.data.repository.ExactToolApprovalCompletion
import com.promenar.infinity.data.repository.ExactToolApprovalDecision
import com.promenar.infinity.data.repository.ToolApprovalRequestFactory
import com.promenar.infinity.data.repository.ToolAllowlistStore
import com.promenar.infinity.data.repository.toInvocationIdentity
import com.promenar.infinity.domain.tool.ToolExecutionTargets
import com.promenar.infinity.ui.chat.ChatStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

private fun preparedApprovalIdentity(
    call: ToolCall,
    requiresApproval: Boolean = true,
    risk: com.promenar.infinity.domain.tool.ToolRisk = com.promenar.infinity.domain.tool.ToolRisk.UNKNOWN,
): ToolInvocationIdentity {
    val tool = com.promenar.infinity.data.remote.protocol.ProtocolTool(
        function = com.promenar.infinity.data.remote.protocol.ProtocolToolFunction(
            name = call.name,
            description = "test definition",
            parameters = """{"type":"object","additionalProperties":true}""",
        ),
        risk = risk,
        runtimeToolId = "test:${call.name}",
        sourceId = "test",
    )
    return when (val resolution = ToolInvocationIdentityFactory.fromPreparedToolCall(
        call,
        tool,
        requiresApproval,
    )) {
        is ToolInvocationIdentityResolution.Valid -> resolution.identity
        is ToolInvocationIdentityResolution.Invalid -> error(resolution.message)
    }
}

private fun ApprovalCallCandidate(
    call: ToolCall,
    risk: com.promenar.infinity.domain.tool.ToolRisk,
): ApprovalCallCandidate = ApprovalCallCandidate(
    call = call,
    risk = risk,
    identity = preparedApprovalIdentity(call),
)

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalManagerTest {
    @get:Rule
    val grantFiles = TemporaryFolder()
    private lateinit var store: ChatStore
    private lateinit var approvalManager: ApprovalManager
    private lateinit var sessionManager: SessionManager
    private lateinit var ledger: ApprovalLedger
    private lateinit var messageManager: MessageManager
    private val testScope = TestScope()
    private val sessionUpdates = mutableListOf<Map<String, Any?>>()

    private val stubSessionRepo = object : ISessionRepository {
        override suspend fun create(session: Session) {}
        override suspend fun updatePartial(id: String, updates: Map<String, Any?>) {
            sessionUpdates += updates
        }
        override suspend fun delete(id: String) {}
        override suspend fun getById(id: String): Session? = null
        override suspend fun getAll(): List<Session> = emptyList()
    }

    private val stubMessageRepo = object : IMessageRepository {
        override suspend fun insert(message: Message, sessionId: String) {}
        override suspend fun updatePartial(messageId: String, updates: Map<String, Any?>) {}
        override suspend fun delete(messageId: String) {}
        override suspend fun deleteBySessionId(sessionId: String) {}
        override suspend fun deleteMessagesAfter(sessionId: String, timestamp: Long) {}
        override suspend fun getById(messageId: String): Message? = null
        override suspend fun getBySession(sessionId: String): List<Message> = emptyList()
        override suspend fun updateVectorizationStatus(messageId: String, status: String, isArchived: Boolean?) {}
    }

    private class ApprovalLedger : ToolExecutionLedger {
        val states = mutableMapOf<ToolExecutionKey, ToolLedgerState>()
        var suspendCompensationCancellably = false
        var compensationFailure: Throwable? = null
        private val identities = mutableMapOf<ToolExecutionKey, ToolInvocationIdentity>()
        var completionTransition: com.promenar.infinity.data.repository.ApprovalTransition? = null
        override suspend fun register(
            key: ToolExecutionKey,
            identity: ToolInvocationIdentity,
        ): ToolRegistrationResult {
            val existing = identities[key]
            if (existing != null) {
                return if (existing == identity) {
                    ToolRegistrationResult.Existing(states.getValue(key))
                } else {
                    ToolRegistrationResult.Conflict
                }
            }
            identities[key] = identity
            val state = if (identity.requiresApproval) {
                ToolLedgerState.PENDING_APPROVAL
            } else {
                ToolLedgerState.APPROVED
            }
            states[key] = state
            return ToolRegistrationResult.Registered(state)
        }

        suspend fun register(key: ToolExecutionKey, toolName: String, requiresApproval: Boolean) =
            register(
                key,
                preparedApprovalIdentity(
                    ToolCall(key.toolCallId, toolName, "{}"),
                    requiresApproval,
                ),
            )
        override suspend fun approve(keys: Set<ToolExecutionKey>) = transition(keys, ToolLedgerState.APPROVED)
        override suspend fun reject(keys: Set<ToolExecutionKey>) = transition(keys, ToolLedgerState.REJECTED)
        override suspend fun cancel(keys: Set<ToolExecutionKey>) = transition(keys, ToolLedgerState.CANCELLED)
        override suspend fun timeout(keys: Set<ToolExecutionKey>) = transition(keys, ToolLedgerState.TIMED_OUT)
        override suspend fun claim(key: ToolExecutionKey, expectedIdentity: ToolInvocationIdentity) = false
        override suspend fun finish(key: ToolExecutionKey, outcome: ToolExecutionOutcome) = false
        override suspend fun finishWithResult(
            key: ToolExecutionKey,
            toolName: String,
            content: String,
            thoughtSignature: String?,
            outcome: ToolExecutionOutcome,
            images: String?,
            viewedMedia: List<com.promenar.infinity.domain.media.ViewedMedia>?,
        ): Message? = null
        override suspend fun terminalizeWithResults(
            requests: Set<ToolTerminalRequest>,
            state: ToolLedgerState,
        ): List<Message> {
            val keys = requests.mapTo(mutableSetOf()) { it.key }
            transition(keys, state)
            return requests.filter { states[it.key] == state }.map {
                Message(
                    id = "terminal-${state.name}-${it.key.toolCallId}",
                    role = MessageRole.TOOL,
                    toolCallId = it.key.toolCallId,
                    name = it.toolName,
                    content = when (state) {
                        ToolLedgerState.REJECTED -> "工具执行已拒绝。"
                        ToolLedgerState.CANCELLED -> "工具执行已取消。"
                        ToolLedgerState.TIMED_OUT -> "工具审批已超时。"
                        else -> "工具执行未完成。"
                    },
                )
            }
        }
        override suspend fun state(key: ToolExecutionKey) = states[key]
        override suspend fun invocationIdentity(key: ToolExecutionKey) = identities[key]
        override suspend fun failAwaitingIdentityConflict(
            key: ToolExecutionKey,
            expectedIdentity: ToolInvocationIdentity,
            thoughtSignature: String?,
        ): Message? = null
        override suspend fun recoverInterruptedRunning(error: String) = 0
        override suspend fun createToolApproval(
            keySessionId: String,
            assistantMessageId: String,
            toolCalls: List<ToolCall>,
            pendingToolCallIds: Set<String>,
            request: ApprovalRequest,
        ): com.promenar.infinity.data.repository.ToolApprovalCreation {
            request.calls.forEach { approvalCall ->
                val identity = approvalCall.toInvocationIdentity()
                if (approvalCall.toolCallId !in pendingToolCallIds || !identity.requiresApproval) {
                    return com.promenar.infinity.data.repository.ToolApprovalCreation.CONFLICT
                }
                if (register(
                        ToolExecutionKey(keySessionId, assistantMessageId, approvalCall.toolCallId),
                        identity,
                    ) == ToolRegistrationResult.Conflict
                ) return com.promenar.infinity.data.repository.ToolApprovalCreation.CONFLICT
            }
            return com.promenar.infinity.data.repository.ToolApprovalCreation.CREATED
        }
        override suspend fun completeToolApproval(sessionId: String, assistantMessageId: String) =
            completionTransition ?: com.promenar.infinity.data.repository.ApprovalTransition(
                sessionId,
                assistantMessageId,
                LoopStatus.RUNNING,
                null,
            )
        override suspend fun recoverApprovalState() = 0
        override suspend fun decideExactToolApproval(
            sessionId: String,
            request: ApprovalRequest,
            approved: Boolean,
        ): ExactToolApprovalDecision {
            if (!ToolApprovalRequestFactory.isValid(request)) return ExactToolApprovalDecision.Conflict
            val current = request.calls.firstOrNull() ?: return ExactToolApprovalDecision.Conflict
            val key = ToolExecutionKey(sessionId, request.assistantMessageId!!, current.toolCallId)
            val identity = identities[key] ?: return ExactToolApprovalDecision.Conflict
            if (identity != current.toInvocationIdentity() || states[key] != ToolLedgerState.PENDING_APPROVAL) {
                return ExactToolApprovalDecision.Conflict
            }
            states[key] = if (approved) ToolLedgerState.APPROVED else ToolLedgerState.REJECTED
            val terminalMessages = if (approved) emptyList() else listOf(
                Message(
                    id = "terminal-rejected-${current.toolCallId}",
                    role = MessageRole.TOOL,
                    toolCallId = current.toolCallId,
                    parentMessageId = request.assistantMessageId,
                    name = current.toolName,
                    content = "工具执行已拒绝。",
                ),
            )
            return ExactToolApprovalDecision.Decided(key, terminalMessages)
        }
        override suspend fun completeExactToolApproval(
            sessionId: String,
            request: ApprovalRequest,
        ): ExactToolApprovalCompletion {
            completionTransition?.let { return ExactToolApprovalCompletion.Completed(it) }
            val currentKey = ToolExecutionKey(
                sessionId,
                request.assistantMessageId!!,
                request.calls.first().toolCallId,
            )
            if (states[currentKey] !in setOf(
                    ToolLedgerState.SUCCEEDED,
                    ToolLedgerState.FAILED,
                    ToolLedgerState.REJECTED,
                    ToolLedgerState.CANCELLED,
                    ToolLedgerState.TIMED_OUT,
                )
            ) return ExactToolApprovalCompletion.Conflict
            val remaining = request.calls.drop(1)
            val next = remaining.takeIf { it.isNotEmpty() }?.let {
                ToolApprovalRequestFactory.createFromIdentities(
                    assistantMessageId = request.assistantMessageId!!,
                    calls = it,
                    reason = request.reason,
                )
            }
            return ExactToolApprovalCompletion.Completed(
                com.promenar.infinity.data.repository.ApprovalTransition(
                    sessionId = sessionId,
                    currentAssistantMessageId = request.assistantMessageId!!,
                    loopStatus = when {
                        next != null -> LoopStatus.WAITING_FOR_APPROVAL
                        states[ToolExecutionKey(sessionId, request.assistantMessageId!!, request.calls.first().toolCallId)] ==
                            ToolLedgerState.REJECTED -> LoopStatus.PAUSED
                        else -> LoopStatus.RUNNING
                    },
                    approvalRequest = next,
                    nextAssistantMessageId = next?.assistantMessageId,
                    nextPendingToolCallIds = next?.calls?.map { it.toolCallId }.orEmpty(),
                ),
            )
        }
        override suspend fun compensateExactToolApproval(
            sessionId: String,
            request: ApprovalRequest,
            state: ToolLedgerState,
            error: String,
        ): ExactToolApprovalDecision {
            if (suspendCompensationCancellably) yield()
            compensationFailure?.let { throw it }
            val current = request.calls.firstOrNull() ?: return ExactToolApprovalDecision.Conflict
            val key = ToolExecutionKey(sessionId, request.assistantMessageId!!, current.toolCallId)
            if (states[key] !in setOf(ToolLedgerState.APPROVED, ToolLedgerState.RUNNING)) {
                return ExactToolApprovalDecision.Conflict
            }
            states[key] = state
            return ExactToolApprovalDecision.Decided(
                key,
                listOf(
                    Message(
                        id = "terminal-${state.name}-${key.toolCallId}",
                        role = MessageRole.TOOL,
                        toolCallId = key.toolCallId,
                        parentMessageId = key.assistantMessageId,
                        name = current.toolName,
                        content = if (state == ToolLedgerState.FAILED) "工具执行失败：$error" else "工具执行已取消。",
                    ),
                ),
            )
        }
        private fun transition(keys: Set<ToolExecutionKey>, target: ToolLedgerState): Int = keys.count { key ->
            if (states[key] == ToolLedgerState.PENDING_APPROVAL ||
                (target == ToolLedgerState.CANCELLED && states[key] == ToolLedgerState.APPROVED) ||
                (target == ToolLedgerState.TIMED_OUT && states[key] == ToolLedgerState.APPROVED)
            ) {
                states[key] = target
                true
            } else false
        }
    }

    @Before
    fun setUp() {
        sessionUpdates.clear()
        store = ChatStore()
        sessionManager = SessionManager(store, stubSessionRepo)
        ledger = ApprovalLedger()
        messageManager = MessageManager(store, stubMessageRepo, stubSessionRepo, testScope)
        approvalManager = ApprovalManager(store, ledger, messageManager, stubSessionRepo)
    }

    private suspend fun seedSessionWithAssistant(
        sessionId: String = "s1",
        toolCalls: List<ToolCall>? = null,
        pendingApprovalToolIds: List<String>? = null
    ): Session {
        val session = Session(id = sessionId, agentId = "a1", title = "Test")
        sessionManager.addSession(session)
        testScope.advanceUntilIdle()

        val msg = Message(
            id = "m1",
            role = MessageRole.ASSISTANT,
            content = "I'll help you",
            createdAt = 1000L,
            toolCalls = toolCalls,
            pendingApprovalToolIds = pendingApprovalToolIds
        )
        store.updateSession(sessionId) { s ->
            s.copy(messages = s.messages + msg)
        }
        return session
    }

    @Test
    fun `会话删除锁定后拒绝创建新的审批请求`() = testScope.runTest {
        seedSessionWithAssistant()
        val gate = com.promenar.infinity.data.session.SessionExecutionGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val deletion = launch {
            gate.withDeletion("s1", "root-1") {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val guarded = ApprovalManager(store, ledger, messageManager, stubSessionRepo, gate)

        val failure = runCatching {
            guarded.setApprovalRequest("s1", ApprovalRequest(toolName = "read"))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(com.promenar.infinity.data.session.SessionDeletingException::class.java)
        release.complete(Unit)
        deletion.join()
    }

    private suspend fun seedExactApproval(
        calls: List<ToolCall> = listOf(ToolCall("risk", "write_file", "{}")),
        executionTarget: String? = null,
    ): ApprovalRequest {
        seedSessionWithAssistant(toolCalls = calls, pendingApprovalToolIds = calls.map { it.id })
        calls.forEach { call ->
            ledger.register(
                ToolExecutionKey("s1", "m1", call.id),
                preparedApprovalIdentity(call),
            )
        }
        return ToolApprovalRequestFactory.create(
            "m1",
            calls.map {
                ApprovalCallCandidate(
                    call = it,
                    risk = com.promenar.infinity.domain.tool.ToolRisk.UNKNOWN,
                    identity = preparedApprovalIdentity(it),
                    executionTarget = executionTarget,
                )
            },
        ).also { approvalManager.setApprovalRequest("s1", it) }
    }

    @Test
    fun `批准精确调用时忽略记住目标标志`() = testScope.runTest {
        val root = grantFiles.newFolder("grants")
        val grants = ToolAllowlistStore(root)
        approvalManager = ApprovalManager(
            store, ledger, messageManager, stubSessionRepo,
            toolAllowlistStore = grants,
            resolveAllowlistScope = { id, target -> com.promenar.infinity.data.repository.ToolAllowlistScopes.resolve(target, id, com.promenar.infinity.data.workspace.SessionWorkspace.Private) },
            resolveExecutionTarget = { _, _, call -> call.executionTarget },
        )
        val target = """["path","/docs/a.md"]"""
        val request = seedExactApproval(
            listOf(ToolCall("first", "write_file", "{}"), ToolCall("next", "delete_file", "{}")),
            executionTarget = target,
        )
        val executed = mutableListOf<String>()
        approvalManager.setCallbacks(null) { session, message, ids ->
            ids.forEach { id ->
                executed += id
                ledger.states[ToolExecutionKey(session, message, id)] = ToolLedgerState.SUCCEEDED
            }
        }

        approvalManager.resumeGeneration("s1", request, rememberTarget = true)

        assertThat(executed).containsExactly("first")
        assertThat(ToolAllowlistStore(root).contains("write_file", target, "session:s1")).isFalse()
        assertThat(grants.contains("delete_file", target, "session:s1")).isFalse()
        assertThat(grants.contains("write_file", target, "session:s2")).isFalse()
        assertThat(store.getSession("s1")!!.approvalRequest!!.calls.single().toolCallId).isEqualTo("next")
    }

    @Test
    fun `拒绝和过期审批即使传入记住标志也不会产生授权`() = testScope.runTest {
        val grants = ToolAllowlistStore(grantFiles.newFolder("grants"))
        approvalManager = ApprovalManager(store, ledger, messageManager, stubSessionRepo, toolAllowlistStore = grants,
            resolveAllowlistScope = { id, target -> com.promenar.infinity.data.repository.ToolAllowlistScopes.resolve(target, id, com.promenar.infinity.data.workspace.SessionWorkspace.Private) })
        val target = """["path","/docs/a.md"]"""
        val request = seedExactApproval(executionTarget = target)
        approvalManager.setCallbacks(null) { _, _, _ -> error("不应执行") }

        approvalManager.resumeGeneration("s1", request.copy(identityHash = "expired"), rememberTarget = true)
        assertThat(grants.entries.value).isEmpty()
        assertThat(ledger.states[ToolExecutionKey("s1", "m1", "risk")]).isEqualTo(ToolLedgerState.PENDING_APPROVAL)

        approvalManager.resumeGeneration("s1", request, approved = false, rememberTarget = true)
        assertThat(grants.entries.value).isEmpty()
        assertThat(ledger.states[ToolExecutionKey("s1", "m1", "risk")]).isEqualTo(ToolLedgerState.REJECTED)
    }

    @Test
    fun `拒绝知识库授权时目标漂移不阻断拒绝决定`() = testScope.runTest {
        approvalManager = ApprovalManager(store, ledger, messageManager, stubSessionRepo,
            resolveExecutionTarget = { _, _, _ -> error("拒绝不应再读取授权目标") })
        val request = seedExactApproval(calls = listOf(ToolCall("risk", "request_knowledge_access", "{}")), executionTarget = "原知识库预览")
        approvalManager.setCallbacks(null) { _, _, _ -> error("拒绝不应执行") }
        approvalManager.resumeGeneration("s1", request, approved = false)
        assertThat(ledger.states[ToolExecutionKey("s1", "m1", "risk")]).isEqualTo(ToolLedgerState.REJECTED)
    }

    @Test
    fun `台账已取消的精确审批不能产生目标授权`() = testScope.runTest {
        val grants = ToolAllowlistStore(grantFiles.newFolder("grants"))
        approvalManager = ApprovalManager(store, ledger, messageManager, stubSessionRepo, toolAllowlistStore = grants,
            resolveAllowlistScope = { id, target -> com.promenar.infinity.data.repository.ToolAllowlistScopes.resolve(target, id, com.promenar.infinity.data.workspace.SessionWorkspace.Private) })
        val request = seedExactApproval(executionTarget = """["path","/docs/a.md"]""")
        ledger.states[ToolExecutionKey("s1", "m1", "risk")] = ToolLedgerState.CANCELLED
        approvalManager.setCallbacks(null) { _, _, _ -> error("不应执行") }

        approvalManager.resumeGeneration("s1", request, rememberTarget = true)

        assertThat(grants.entries.value).isEmpty()
    }

    @Test
    fun `审批不访问不可写的旧授权存储`() = testScope.runTest {
        val grants = ToolAllowlistStore(grantFiles.newFile("not-a-directory"))
        approvalManager = ApprovalManager(store, ledger, messageManager, stubSessionRepo, toolAllowlistStore = grants,
            resolveAllowlistScope = { _, _ -> error("不得查询记忆授权作用域") })
        val request = seedExactApproval(executionTarget = """["path","/docs/a.md"]""")
        var executed = false
        approvalManager.setCallbacks(null) { _, _, _ -> executed = true }
        approvalManager.resumeGeneration("s1", request, rememberTarget = true)
        assertThat(executed).isTrue()
    }

    @Test
    fun `审批期间 uuid 目标移动时取消调用并暂停且不授予旧目标`() = testScope.runTest {
        val grants = ToolAllowlistStore(grantFiles.newFolder("grants"))
        approvalManager = ApprovalManager(
            store, ledger, messageManager, stubSessionRepo,
            toolAllowlistStore = grants,
            resolveAllowlistScope = { id, target -> com.promenar.infinity.data.repository.ToolAllowlistScopes.resolve(target, id, com.promenar.infinity.data.workspace.SessionWorkspace.Private) },
            resolveExecutionTarget = { session, assistantMessageId, call ->
                assertThat(session.id).isEqualTo("s1")
                assertThat(assistantMessageId).isEqualTo("m1")
                assertThat(call.toolName).isEqualTo("write_file")
                """["path","/moved/a.md"]"""
            },
        )
        val request = seedExactApproval(executionTarget = """["path","/docs/a.md"]""")
        var executed = false
        approvalManager.setCallbacks(null) { _, _, _ -> executed = true }

        approvalManager.resumeGeneration("s1", request, rememberTarget = true)

        assertThat(executed).isFalse()
        assertThat(grants.entries.value).isEmpty()
        assertThat(ledger.states[ToolExecutionKey("s1", "m1", "risk")]).isEqualTo(ToolLedgerState.CANCELLED)
        assertThat(store.getSession("s1")!!.loopStatus).isEqualTo(LoopStatus.PAUSED)
        assertThat(store.getSession("s1")!!.approvalRequest).isNull()
        assertThat(sessionUpdates).containsAtLeast(
            mapOf("approvalRequest" to null), mapOf("loopStatus" to LoopStatus.PAUSED),
        )
    }

    @Test
    fun `审批期间 uuid 目标删除不可解析时不能执行或记住授权`() = testScope.runTest {
        val grants = ToolAllowlistStore(grantFiles.newFolder("grants"))
        approvalManager = ApprovalManager(
            store, ledger, messageManager, stubSessionRepo,
            toolAllowlistStore = grants,
            resolveAllowlistScope = { id, target -> com.promenar.infinity.data.repository.ToolAllowlistScopes.resolve(target, id, com.promenar.infinity.data.workspace.SessionWorkspace.Private) },
            resolveExecutionTarget = { _, _, _ -> null },
        )
        val request = seedExactApproval(executionTarget = """["path","/docs/a.md"]""")
        approvalManager.setCallbacks(null) { _, _, _ -> error("不应执行") }

        approvalManager.resumeGeneration("s1", request, rememberTarget = true)

        assertThat(grants.entries.value).isEmpty()
        assertThat(ledger.states[ToolExecutionKey("s1", "m1", "risk")]).isEqualTo(ToolLedgerState.CANCELLED)
        assertThat(store.getSession("s1")!!.loopStatus).isEqualTo(LoopStatus.PAUSED)
        assertThat(store.getSession("s1")!!.approvalRequest).isNull()
    }

    @Test
    fun `跨助手消息复用调用 id 时旧消息参数不能掩盖当前 uuid 目标变化`() = testScope.runTest {
        val grants = ToolAllowlistStore(grantFiles.newFolder("grants"))
        approvalManager = ApprovalManager(
            store, ledger, messageManager, stubSessionRepo,
            toolAllowlistStore = grants,
            resolveAllowlistScope = { id, target -> com.promenar.infinity.data.repository.ToolAllowlistScopes.resolve(target, id, com.promenar.infinity.data.workspace.SessionWorkspace.Private) },
            resolveExecutionTarget = { session, assistantMessageId, call ->
                targetForApprovalMessage(session, assistantMessageId, call, mapOf("current-file" to "/moved.md"))
            },
        )
        val currentCall = ToolCall("shared-call", "write_file", """{"uuid":"current-file"}""")
        val request = seedExactApproval(listOf(currentCall), executionTarget = """["path","/approved.md"]""")
        val historicalCall = ToolCall("shared-call", "write_file", """{"path":"/approved.md"}""")
        store.updateSession("s1") { session ->
            session.copy(messages = listOf(Message(
                id = "historical-assistant",
                role = MessageRole.ASSISTANT,
                content = "历史调用",
                toolCalls = listOf(historicalCall),
            )) + session.messages)
        }
        var executions = 0
        approvalManager.setCallbacks(null) { _, _, _ -> executions++ }

        approvalManager.resumeGeneration("s1", request, rememberTarget = true)

        assertThat(executions).isEqualTo(0)
        assertThat(grants.entries.value).isEmpty()
        assertThat(ledger.states[ToolExecutionKey("s1", "m1", "shared-call")]).isEqualTo(ToolLedgerState.CANCELLED)
        assertThat(store.getSession("s1")!!.loopStatus).isEqualTo(LoopStatus.PAUSED)
        assertThat(store.getSession("s1")!!.approvalRequest).isNull()
        assertThat(store.getSession("s1")!!.messages.first { it.id == "historical-assistant" }.toolCalls)
            .containsExactly(historicalCall)
    }

    @Test
    fun `跨助手消息复用调用 id 时合法当前目标不会被旧消息参数误拒`() = testScope.runTest {
        val grants = ToolAllowlistStore(grantFiles.newFolder("grants"))
        approvalManager = ApprovalManager(
            store, ledger, messageManager, stubSessionRepo,
            toolAllowlistStore = grants,
            resolveAllowlistScope = { id, target -> com.promenar.infinity.data.repository.ToolAllowlistScopes.resolve(target, id, com.promenar.infinity.data.workspace.SessionWorkspace.Private) },
            resolveExecutionTarget = { session, assistantMessageId, call ->
                targetForApprovalMessage(session, assistantMessageId, call)
            },
        )
        val target = """["path","/current.md"]"""
        val currentCall = ToolCall("shared-call", "write_file", """{"path":"/current.md"}""")
        val request = seedExactApproval(listOf(currentCall), executionTarget = target)
        store.updateSession("s1") { session ->
            session.copy(messages = listOf(Message(
                id = "historical-assistant",
                role = MessageRole.ASSISTANT,
                content = "历史调用",
                toolCalls = listOf(ToolCall("shared-call", "write_file", """{"path":"/old.md"}""")),
            )) + session.messages)
        }
        val executedMessages = mutableListOf<String>()
        approvalManager.setCallbacks(null) { sessionId, messageId, callIds ->
            executedMessages += messageId
            callIds.forEach { id -> ledger.states[ToolExecutionKey(sessionId, messageId, id)] = ToolLedgerState.SUCCEEDED }
        }

        approvalManager.resumeGeneration("s1", request, rememberTarget = true)

        assertThat(executedMessages).containsExactly("m1")
        assertThat(grants.contains("write_file", target, "session:s1")).isFalse()
        assertThat(grants.contains("write_file", """["path","/old.md"]""", "session:s1")).isFalse()
        assertThat(ledger.states[ToolExecutionKey("s1", "m1", "shared-call")]).isEqualTo(ToolLedgerState.SUCCEEDED)
        assertThat(store.getSession("s1")!!.approvalRequest).isNull()
    }

    private fun targetForApprovalMessage(
        session: Session,
        assistantMessageId: String,
        identity: ApprovalCallIdentity,
        resolvedPaths: Map<String, String> = emptyMap(),
    ): String? {
        assertThat(assistantMessageId).isEqualTo("m1")
        val call = session.messages.first { it.id == assistantMessageId && it.role == MessageRole.ASSISTANT }
            .toolCalls.orEmpty().single { it.id == identity.toolCallId && it.name == identity.toolName }
        val tool = com.promenar.infinity.data.remote.protocol.ProtocolTool(
            function = com.promenar.infinity.data.remote.protocol.ProtocolToolFunction(call.name, "测试工具", "{}"),
        )
        return ToolExecutionTargets.extract(tool, call.arguments, resolvedPaths)
    }

    @Test
    fun `SSH会话发送初次审批与批准复验使用同一owner别名和输入`() = testScope.runTest {
        val leases = mutableMapOf(("agent:s1" to "pty-1") to "local")
        val tool = sshSendTool()
        val call = ToolCall("ssh-send", "ssh_session_send", """{"session_id":"pty-1","input":"echo ok"}""")
        val initialTarget = com.promenar.infinity.data.generation.resolveSessionToolTarget(
            Session(id = "s1", agentId = "a1"), tool, call.arguments, null, sshHostStore = sshHosts,
            sshSessionHost = { owner, id -> leases[owner to id] },
        )
        assertThat(ToolExecutionTargets.display(requireNotNull(initialTarget))).isEqualTo("local · echo ok")
        assertThat(initialTarget).contains("\"sshHostId\":\"local-id\"")
        val grants = ToolAllowlistStore(grantFiles.newFolder("ssh-grants"))
        approvalManager = sshApprovalManager(tool, leases, grants)
        val request = seedExactApproval(listOf(call), initialTarget)
        val executed = mutableListOf<String>()
        approvalManager.setCallbacks(null) { sessionId, messageId, ids ->
            executed += ids
            ids.forEach { ledger.states[ToolExecutionKey(sessionId, messageId, it)] = ToolLedgerState.SUCCEEDED }
        }

        approvalManager.resumeGeneration("s1", request, rememberTarget = true)

        assertThat(executed).containsExactly("ssh-send")
        assertThat(ledger.states[ToolExecutionKey("s1", "m1", "ssh-send")]).isEqualTo(ToolLedgerState.SUCCEEDED)
        assertThat(grants.contains("ssh_session_send", initialTarget!!, null)).isFalse()
        assertThat(store.getSession("s1")!!.approvalRequest).isNull()
    }

    @Test
    fun `SSH会话审批后已不存在时取消且不记住授权`() = testScope.runTest {
        rejectUnavailableSshSession(otherOwner = false)
    }

    @Test
    fun `其它owner的同名SSH会话不能通过当前会话审批复验`() = testScope.runTest {
        rejectUnavailableSshSession(otherOwner = true)
    }

    private suspend fun rejectUnavailableSshSession(otherOwner: Boolean) {
        val leases = mutableMapOf(("agent:s1" to "pty-1") to "local")
        val tool = sshSendTool()
        val call = ToolCall("ssh-send", "ssh_session_send", """{"session_id":"pty-1","input":"echo ok"}""")
        val initialTarget = com.promenar.infinity.data.generation.resolveSessionToolTarget(
            Session(id = "s1", agentId = "a1"), tool, call.arguments, null, sshHostStore = sshHosts,
            sshSessionHost = { owner, id -> leases[owner to id] },
        )
        val grants = ToolAllowlistStore(grantFiles.newFolder("ssh-rejected-grants"))
        approvalManager = sshApprovalManager(tool, leases, grants)
        val request = seedExactApproval(listOf(call), initialTarget)
        leases.clear()
        if (otherOwner) leases["agent:other" to "pty-1"] = "local"
        var executed = false
        approvalManager.setCallbacks(null) { _, _, _ -> executed = true }

        approvalManager.resumeGeneration("s1", request, rememberTarget = true)

        assertThat(executed).isFalse()
        assertThat(grants.entries.value).isEmpty()
        assertThat(ledger.states[ToolExecutionKey("s1", "m1", "ssh-send")]).isEqualTo(ToolLedgerState.CANCELLED)
        assertThat(store.getSession("s1")!!.loopStatus).isEqualTo(LoopStatus.PAUSED)
        assertThat(store.getSession("s1")!!.approvalRequest).isNull()
    }

    private fun sshSendTool() = com.promenar.infinity.data.remote.protocol.ProtocolTool(
        function = com.promenar.infinity.data.remote.protocol.ProtocolToolFunction("ssh_session_send", "SSH 测试工具", "{}"),
        risk = com.promenar.infinity.domain.tool.ToolRisk.PROCESS,
        runtimeToolId = "test:ssh_session_send",
        sourceId = "test",
    )

    private val sshHosts = mockk<com.promenar.infinity.data.ssh.SshHostStore> {
        coEvery { find("local") } returns com.promenar.infinity.data.ssh.SshHost(
            id = "local-id", alias = "local", hostname = "192.0.2.1", username = "user",
        )
    }

    private fun sshApprovalManager(
        tool: com.promenar.infinity.data.remote.protocol.ProtocolTool,
        leases: Map<Pair<String, String>, String>,
        grants: ToolAllowlistStore,
    ) = ApprovalManager(
        store, ledger, messageManager, stubSessionRepo,
        toolAllowlistStore = grants,
            resolveAllowlistScope = { id, target -> com.promenar.infinity.data.repository.ToolAllowlistScopes.resolve(target, id, com.promenar.infinity.data.workspace.SessionWorkspace.Private) },
        resolveExecutionTarget = { session, messageId, identity ->
            com.promenar.infinity.ui.chat.resolveChatApprovalExecutionTarget(
                session, messageId, identity,
                toolLookup = { runtimeId -> tool.takeIf { it.runtimeToolId == runtimeId } },
                workspaceRepository = null,
                sshSessionHost = { owner, id -> leases[owner to id] },
                sshHostStore = sshHosts,
            )
        },
    )

    @Test
    fun setApprovalRequest() = testScope.runTest {
        seedSessionWithAssistant()
        val request = ApprovalRequest(toolName = "read_file", args = "{}", reason = "Needs approval")
        approvalManager.setApprovalRequest("s1", request)

        val session = store.getSession("s1")!!
        assertThat(session.approvalRequest).isNotNull()
        assertThat(session.approvalRequest!!.toolName).isEqualTo("read_file")
        assertThat(sessionUpdates.last()["approvalRequest"]).isEqualTo(request)
    }

    @Test
    fun clearApprovalRequest() = testScope.runTest {
        seedSessionWithAssistant()
        approvalManager.setApprovalRequest("s1", ApprovalRequest(toolName = "read"))
        approvalManager.setApprovalRequest("s1", null)

        val session = store.getSession("s1")!!
        assertThat(session.approvalRequest).isNull()
    }

    @Test
    fun setExecutionMode() = testScope.runTest {
        seedSessionWithAssistant()
        approvalManager.setExecutionMode("s1", "manual")

        val session = store.getSession("s1")!!
        assertThat(session.executionMode).isEqualTo("workspace")
    }

    @Test
    fun setLoopStatus() = testScope.runTest {
        seedSessionWithAssistant()
        approvalManager.setLoopStatus("s1", LoopStatus.RUNNING)

        val session = store.getSession("s1")!!
        assertThat(session.loopStatus).isEqualTo(LoopStatus.RUNNING)
        assertThat(sessionUpdates.last()["loopStatus"]).isEqualTo(LoopStatus.RUNNING)
    }

    @Test
    fun setPendingIntervention() = testScope.runTest {
        seedSessionWithAssistant()
        approvalManager.setPendingIntervention("s1", "Use alternative approach")

        val session = store.getSession("s1")!!
        assertThat(session.pendingIntervention).isEqualTo("Use alternative approach")
    }

    @Test
    fun `限时授权顺序执行同类前缀异类或空类别保留待审批且不提前续跑`() = testScope.runTest {
        for (nextCategory in listOf(com.promenar.infinity.domain.tool.ApprovalGrantCategory.COMMAND, null)) {
            val grants = com.promenar.infinity.data.approval.TimedApprovalGrantStore(backgroundScope) { testScheduler.currentTime }
            approvalManager = ApprovalManager(store, ledger, messageManager, stubSessionRepo, timedGrants = grants)
            val seeded = seedExactApproval(listOf("w1", "w2", "other", "w3").map { ToolCall(it, "write_file", "{}") })
            val request = ToolApprovalRequestFactory.createFromIdentities("m1", seeded.calls.mapIndexed { index, call ->
                call.copy(grantCategory = if (index == 2) nextCategory else com.promenar.infinity.domain.tool.ApprovalGrantCategory.SKILLS)
            })
            approvalManager.setApprovalRequest("s1", request)
            val executed = mutableListOf<String>()
            var resumed = 0
            approvalManager.setCallbacks({ _, _, _ -> resumed++ }) { session, message, ids ->
                ids.forEach { id -> executed += id; ledger.states[ToolExecutionKey(session, message, id)] = ToolLedgerState.SUCCEEDED }
            }
            approvalManager.approveWithTimedGrant("s1", request)
            assertThat(executed).containsExactly("w1", "w2").inOrder()
            assertThat(store.getSession("s1")!!.approvalRequest!!.calls.map { it.toolCallId }).containsExactly("other", "w3").inOrder()
            assertThat(grants.isValid("s1", com.promenar.infinity.domain.tool.ApprovalGrantCategory.SKILLS)).isTrue()
            assertThat(resumed).isEqualTo(0)
            ledger = ApprovalLedger()
        }
    }

    @Test
    fun `全批同类授权只续跑一次且过时卡片与空类别不授予权限`() = testScope.runTest {
        val grants = com.promenar.infinity.data.approval.TimedApprovalGrantStore(backgroundScope) { testScheduler.currentTime }
        approvalManager = ApprovalManager(store, ledger, messageManager, stubSessionRepo, timedGrants = grants)
        val seeded = seedExactApproval(listOf("w1", "w2").map { ToolCall(it, "write_file", "{}") })
        approvalManager.approveWithTimedGrant("s1", seeded)
        assertThat(grants.observe("s1").value).isEmpty()
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "w1"))).isEqualTo(ToolLedgerState.PENDING_APPROVAL)
        val request = ToolApprovalRequestFactory.createFromIdentities("m1", seeded.calls.map {
            it.copy(grantCategory = com.promenar.infinity.domain.tool.ApprovalGrantCategory.SKILLS)
        })
        approvalManager.approveWithTimedGrant("s1", request)
        assertThat(grants.observe("s1").value).isEmpty()
        approvalManager.setApprovalRequest("s1", request)
        var resumed = 0
        approvalManager.setCallbacks({ _, _, _ -> resumed++ }) { session, message, ids ->
            ids.forEach { ledger.states[ToolExecutionKey(session, message, it)] = ToolLedgerState.SUCCEEDED }
        }
        approvalManager.approveWithTimedGrant("s1", request)
        assertThat(resumed).isEqualTo(1)
        assertThat(store.getSession("s1")!!.approvalRequest).isNull()
        grants.revokeAll("s1")
        approvalManager.approveWithTimedGrant("s1", request)
        assertThat(grants.observe("s1").value).isEmpty()
        assertThat(resumed).isEqualTo(1)
    }

    @Test fun `执行目标变化或执行回调缺失时不创建限时授权`() = testScope.runTest {
        val grants = com.promenar.infinity.data.approval.TimedApprovalGrantStore(backgroundScope) { testScheduler.currentTime }
        approvalManager = ApprovalManager(store, ledger, messageManager, stubSessionRepo, timedGrants = grants,
            resolveExecutionTarget = { _, _, _ -> "changed" })
        val seeded = seedExactApproval(executionTarget = "original")
        val request = ToolApprovalRequestFactory.createFromIdentities("m1", seeded.calls.map {
            it.copy(grantCategory = com.promenar.infinity.domain.tool.ApprovalGrantCategory.SKILLS)
        })
        approvalManager.setApprovalRequest("s1", request)
        approvalManager.approveWithTimedGrant("s1", request)
        assertThat(grants.observe("s1").value).isEmpty()
        assertThat(store.getSession("s1")!!.approvalRequest).isEqualTo(request)
        approvalManager.setCallbacks(null) { _, _, _ -> error("目标变化不能执行") }
        approvalManager.approveWithTimedGrant("s1", request)
        assertThat(grants.observe("s1").value).isEmpty()
        assertThat(store.getSession("s1")!!.approvalRequest).isNull()
    }

    @Test fun `并发点击同一卡片不重复执行不延长授权且只续跑一次`() = testScope.runTest {
        var now = 1_000L
        val grants = com.promenar.infinity.data.approval.TimedApprovalGrantStore(backgroundScope) { now }
        approvalManager = ApprovalManager(store, ledger, messageManager, stubSessionRepo, timedGrants = grants)
        val seeded = seedExactApproval()
        val request = ToolApprovalRequestFactory.createFromIdentities("m1", seeded.calls.map {
            it.copy(grantCategory = com.promenar.infinity.domain.tool.ApprovalGrantCategory.SKILLS)
        })
        approvalManager.setApprovalRequest("s1", request)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var executed = 0
        var resumed = 0
        approvalManager.setCallbacks({ _, _, _ -> resumed++ }) { session, message, ids ->
            executed++
            entered.complete(Unit)
            release.await()
            ids.forEach { ledger.states[ToolExecutionKey(session, message, it)] = ToolLedgerState.SUCCEEDED }
        }
        val first = launch { approvalManager.approveWithTimedGrant("s1", request) }
        entered.await()
        now += 1_000
        val duplicate = launch { approvalManager.approveWithTimedGrant("s1", request) }
        yield()
        release.complete(Unit)
        joinAll(first, duplicate)
        assertThat(executed).isEqualTo(1)
        assertThat(resumed).isEqualTo(1)
        assertThat(grants.observe("s1").value).containsExactly(com.promenar.infinity.domain.tool.ApprovalGrantCategory.SKILLS, 601_000L)
    }

    @Test
    fun approveAllExecutesEveryCallInBatchAndResumesOnce() = testScope.runTest {
        val calls = listOf(ToolCall("w1", "write_file", "{}"), ToolCall("w2", "write_file", "{}"))
        val request = seedExactApproval(calls)
        val executed = mutableListOf<String>()
        var resumed = 0
        approvalManager.setCallbacks(
            onGenerateMessage = { _, _, _ -> resumed++ },
            onExecuteTools = { sessionId, messageId, ids ->
                ids.forEach { id ->
                    executed += id
                    ledger.states[ToolExecutionKey(sessionId, messageId, id)] = ToolLedgerState.SUCCEEDED
                }
            },
        )

        approvalManager.approveAll("s1", request)

        assertThat(executed).containsExactly("w1", "w2").inOrder()
        assertThat(resumed).isEqualTo(1)
        assertThat(store.getSession("s1")!!.approvalRequest).isNull()
    }

    @Test
    fun resumeGenerationRejectsAndContinuesWithRejectedResult() = testScope.runTest {
        val request = seedExactApproval()
        var resumed = 0
        approvalManager.setCallbacks(
            onGenerateMessage = { _, _, _ -> resumed++ },
            onExecuteTools = { _, _, _ -> },
        )

        approvalManager.resumeGeneration("s1", request, approved = false)

        val session = store.getSession("s1")!!
        assertThat(session.approvalRequest).isNull()
        assertThat(session.messages.filter { it.role == MessageRole.TOOL }.all { it.content.contains("已拒绝") }).isTrue()
        assertThat(resumed).isEqualTo(1)
    }

    @Test
    fun legacyApprovalWithoutExactIdentityIsClosedWithoutResuming() = testScope.runTest {
        seedSessionWithAssistant(
            toolCalls = listOf(ToolCall("legacy-call", "write_file", "{}")),
            pendingApprovalToolIds = listOf("legacy-call"),
        )
        ledger.register(ToolExecutionKey("s1", "m1", "legacy-call"), "write_file", true)
        var resumed = 0
        approvalManager.setCallbacks(onGenerateMessage = { _, _, _ -> resumed++ }, onExecuteTools = { _, _, _ -> })
        approvalManager.setApprovalRequest("s1", ApprovalRequest(type = "continuation"))

        approvalManager.resumeGeneration("s1", approved = true)

        val session = store.getSession("s1")!!
        assertThat(session.approvalRequest).isNull()
        assertThat(session.loopStatus).isEqualTo(LoopStatus.PAUSED)
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "legacy-call"))).isEqualTo(ToolLedgerState.CANCELLED)
        assertThat(resumed).isEqualTo(0)
    }

    @Test
    fun resumeGenerationApprovedAddsDecisionStep() = testScope.runTest {
        val request = seedExactApproval()
        approvalManager.setCallbacks(
            onGenerateMessage = { _, _, _ -> },
            onExecuteTools = { sessionId, messageId, ids ->
                ids.forEach { ledger.states[ToolExecutionKey(sessionId, messageId, it)] = ToolLedgerState.SUCCEEDED }
            },
        )

        approvalManager.resumeGeneration("s1", request, approved = true)

        val session = store.getSession("s1")!!
        val lastMsg = session.messages.find { it.id == "m1" }!!
        val decisionStep = lastMsg.executionSteps?.lastOrNull()
        assertThat(decisionStep).isNotNull()
        assertThat(decisionStep!!.type).isEqualTo("intervention_result")
        assertThat(decisionStep.content).contains("Approved")
    }

    @Test
    fun resumeGenerationWithIntervention() = testScope.runTest {
        val request = seedExactApproval()
        approvalManager.setCallbacks(
            onGenerateMessage = { _, _, _ -> },
            onExecuteTools = { sessionId, messageId, ids ->
                ids.forEach { ledger.states[ToolExecutionKey(sessionId, messageId, it)] = ToolLedgerState.SUCCEEDED }
            },
        )

        approvalManager.resumeGeneration("s1", request, approved = true, intervention = "Do it differently")

        val session = store.getSession("s1")!!
        assertThat(session.pendingIntervention).isEqualTo("Do it differently")
        val lastMsg = session.messages.find { it.id == "m1" }!!
        val decisionStep = lastMsg.executionSteps?.lastOrNull()
        assertThat(decisionStep!!.content).contains("Human Instruction: Do it differently")
    }

    @Test
    fun setKGExtractionStatus() = testScope.runTest {
        seedSessionWithAssistant()

        sessionManager.setKGExtractionStatus("s1", true)
        assertThat(store.get().activeKGExtractions).contains("s1")

        sessionManager.setKGExtractionStatus("s1", false)
        assertThat(store.get().activeKGExtractions).doesNotContain("s1")
    }

    @Test
    fun approvalPassesOnlyPendingToolCallIdsAndDoesNotReplaySafeCalls() = testScope.runTest {
        val calls = listOf(
            ToolCall("safe", "read_file", "{}"),
            ToolCall("risky", "write_file", "{}"),
        )
        seedSessionWithAssistant(toolCalls = calls, pendingApprovalToolIds = listOf("risky"))
        val riskyKey = ToolExecutionKey("s1", "m1", "risky")
        ledger.register(riskyKey, "write_file", requiresApproval = true)
        val request = ToolApprovalRequestFactory.create(
            "m1",
            listOf(ApprovalCallCandidate(calls[1], com.promenar.infinity.domain.tool.ToolRisk.FILE_WRITE)),
        )
        approvalManager.setApprovalRequest("s1", request)
        var executedIds: Set<String>? = null
        approvalManager.setCallbacks(
            onGenerateMessage = { _, _, _ -> },
            onExecuteTools = { sessionId, messageId, allowedIds ->
                executedIds = allowedIds
                allowedIds.forEach { ledger.states[ToolExecutionKey(sessionId, messageId, it)] = ToolLedgerState.SUCCEEDED }
            },
        )

        approvalManager.resumeGeneration("s1", request, approved = true)

        assertThat(executedIds).containsExactly("risky")
        assertThat(ledger.state(riskyKey)).isEqualTo(ToolLedgerState.SUCCEEDED)
        assertThat(store.getSession("s1")!!.messages.first { it.id == "m1" }.pendingApprovalToolIds)
            .isNull()
    }

    @Test
    fun rejectionOnlyTerminalizesCurrentVisibleIdentityAndKeepsNextQueued() = testScope.runTest {
        val calls = listOf(
            ToolCall("r1", "write_file", "{}"),
            ToolCall("r2", "delete_file", "{}"),
        )
        seedSessionWithAssistant(toolCalls = calls, pendingApprovalToolIds = listOf("r1", "r2"))
        val keys = calls.map { ToolExecutionKey("s1", "m1", it.id) }.toSet()
        calls.zip(keys).forEach { (call, key) -> ledger.register(key, call.name, true) }
        val request = ToolApprovalRequestFactory.create(
            "m1",
            calls.map { ApprovalCallCandidate(it, com.promenar.infinity.domain.tool.ToolRisk.UNKNOWN) },
        )
        approvalManager.setApprovalRequest("s1", request)

        approvalManager.resumeGeneration("s1", request, approved = false)
        advanceUntilIdle()

        assertThat(ledger.state(keys.first())).isEqualTo(ToolLedgerState.REJECTED)
        assertThat(ledger.state(keys.last())).isEqualTo(ToolLedgerState.PENDING_APPROVAL)
        val toolMessages = store.getSession("s1")!!.messages.filter { it.role == MessageRole.TOOL }
        assertThat(toolMessages.mapNotNull { it.toolCallId }).containsExactly("r1")
        assertThat(toolMessages.all { it.content.contains("已拒绝") }).isTrue()
        assertThat(store.getSession("s1")!!.approvalRequest!!.calls.map { it.toolCallId })
            .containsExactly("r2")
    }

    @Test
    fun cancelHasExplicitTerminalEntrypoint() = testScope.runTest {
        suspend fun seedPending(sessionId: String, messageId: String, toolCallId: String): ToolExecutionKey {
            seedSessionWithAssistant(
                sessionId = sessionId,
                toolCalls = listOf(ToolCall(toolCallId, "write_file", "{}")),
                pendingApprovalToolIds = listOf(toolCallId),
            )
            val key = ToolExecutionKey(sessionId, messageId, toolCallId)
            ledger.register(key, "write_file", true)
            approvalManager.setApprovalRequest(sessionId, ApprovalRequest(type = "tool_approval"))
            return key
        }
        val cancelled = seedPending("cancel-session", "m1", "cancelled")

        approvalManager.cancelPendingApproval("cancel-session")
        advanceUntilIdle()

        assertThat(ledger.state(cancelled)).isEqualTo(ToolLedgerState.CANCELLED)
        assertThat(store.getSession("cancel-session")!!.messages.last().content).contains("已取消")
    }

    @Test
    fun callbackFailureWritesDeterministicFailedToolMessageAndClosesApproval() = testScope.runTest {
        val call = ToolCall("risky", "write_file", "{}")
        seedSessionWithAssistant(
            toolCalls = listOf(call),
            pendingApprovalToolIds = listOf(call.id),
        )
        val key = ToolExecutionKey("s1", "m1", call.id)
        ledger.register(key, call.name, true)
        val request = ToolApprovalRequestFactory.create(
            "m1",
            listOf(ApprovalCallCandidate(call, com.promenar.infinity.domain.tool.ToolRisk.UNKNOWN)),
        )
        approvalManager.setApprovalRequest("s1", request)
        approvalManager.setLoopStatus("s1", LoopStatus.WAITING_FOR_APPROVAL)
        approvalManager.setCallbacks(
            onGenerateMessage = null,
            onExecuteTools = { _, _, _ -> throw IllegalStateException("模拟进程在claim前终止") },
        )

        approvalManager.resumeGeneration("s1", request, approved = true)

        assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.FAILED)
        assertThat(store.getSession("s1")!!.approvalRequest).isNull()
        assertThat(store.getSession("s1")!!.loopStatus).isEqualTo(LoopStatus.RUNNING)
        assertThat(store.getSession("s1")!!.messages.first().pendingApprovalToolIds).isNull()
        assertThat(store.getSession("s1")!!.messages.last().content).contains("工具执行失败")
        val persistedApprovalUpdates = sessionUpdates
            .filter { "approvalRequest" in it }
            .map { it["approvalRequest"] }
        assertThat(persistedApprovalUpdates).containsExactly(request)
    }

    @Test
    fun callbackExceptionAfterSucceededTerminalCompletesAndAdvancesWithoutRewritingOutcome() = testScope.runTest {
        val calls = listOf(
            ToolCall("succeeded", "write_file", "{}"),
            ToolCall("next", "delete_file", "{}"),
        )
        seedSessionWithAssistant(toolCalls = calls, pendingApprovalToolIds = calls.map { it.id })
        calls.forEach { ledger.register(ToolExecutionKey("s1", "m1", it.id), it.name, true) }
        val request = ToolApprovalRequestFactory.create(
            "m1",
            calls.map { ApprovalCallCandidate(it, com.promenar.infinity.domain.tool.ToolRisk.FILE_WRITE) },
        )
        approvalManager.setApprovalRequest("s1", request)
        val key = ToolExecutionKey("s1", "m1", calls.first().id)
        approvalManager.setCallbacks(
            onGenerateMessage = { _, _, _ -> },
            onExecuteTools = { _, _, _ ->
                ledger.states[key] = ToolLedgerState.SUCCEEDED
                throw IllegalStateException("result mirror failed")
            },
        )

        approvalManager.resumeGeneration("s1", request, approved = true)

        assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.SUCCEEDED)
        assertThat(store.getSession("s1")!!.approvalRequest!!.calls.map { it.toolCallId })
            .containsExactly("next")
        assertThat(store.getSession("s1")!!.messages.first().pendingApprovalToolIds)
            .containsExactly("next")
        assertThat(store.getSession("s1")!!.messages.filter { it.role == MessageRole.TOOL }).isEmpty()
    }

    @Test
    fun callbackExceptionAfterFailedTerminalCompletesAndAdvancesWithoutRewritingOutcome() = testScope.runTest {
        val calls = listOf(
            ToolCall("failed", "write_file", "{}"),
            ToolCall("next", "delete_file", "{}"),
        )
        seedSessionWithAssistant(toolCalls = calls, pendingApprovalToolIds = calls.map { it.id })
        calls.forEach { ledger.register(ToolExecutionKey("s1", "m1", it.id), it.name, true) }
        val request = ToolApprovalRequestFactory.create(
            "m1",
            calls.map { ApprovalCallCandidate(it, com.promenar.infinity.domain.tool.ToolRisk.FILE_WRITE) },
        )
        approvalManager.setApprovalRequest("s1", request)
        val key = ToolExecutionKey("s1", "m1", calls.first().id)
        approvalManager.setCallbacks(
            onGenerateMessage = { _, _, _ -> },
            onExecuteTools = { _, _, _ ->
                ledger.states[key] = ToolLedgerState.FAILED
                throw IllegalStateException("result mirror failed")
            },
        )

        approvalManager.resumeGeneration("s1", request, approved = true)

        assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.FAILED)
        assertThat(store.getSession("s1")!!.approvalRequest!!.calls.map { it.toolCallId })
            .containsExactly("next")
        assertThat(store.getSession("s1")!!.messages.first().pendingApprovalToolIds)
            .containsExactly("next")
    }

    @Test
    fun callbackCancellationCompensatesThenRethrows() = testScope.runTest {
        val call = ToolCall("cancelled", "write_file", "{}")
        seedSessionWithAssistant(toolCalls = listOf(call), pendingApprovalToolIds = listOf(call.id))
        val key = ToolExecutionKey("s1", "m1", call.id)
        ledger.register(key, call.name, true)
        val request = ToolApprovalRequestFactory.create(
            "m1",
            listOf(ApprovalCallCandidate(call, com.promenar.infinity.domain.tool.ToolRisk.FILE_WRITE)),
        )
        approvalManager.setApprovalRequest("s1", request)
        approvalManager.setCallbacks(
            onGenerateMessage = null,
            onExecuteTools = { _, _, _ -> throw CancellationException("cancel") },
        )

        val failure = runCatching {
            approvalManager.resumeGeneration("s1", request, approved = true)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.CANCELLED)
        assertThat(store.getSession("s1")!!.approvalRequest).isNull()
        assertThat(store.getSession("s1")!!.messages.last().content).contains("已取消")
    }

    @Test
    fun realJobCancellationCompletesCompensationInNonCancellableContextAndRethrows() = testScope.runTest {
        val call = ToolCall("job-cancelled", "write_file", "{}")
        seedSessionWithAssistant(toolCalls = listOf(call), pendingApprovalToolIds = listOf(call.id))
        val key = ToolExecutionKey("s1", "m1", call.id)
        ledger.register(key, call.name, true)
        ledger.suspendCompensationCancellably = true
        val request = ToolApprovalRequestFactory.create(
            "m1",
            listOf(ApprovalCallCandidate(call, com.promenar.infinity.domain.tool.ToolRisk.FILE_WRITE)),
        )
        approvalManager.setApprovalRequest("s1", request)
        val callbackEntered = CompletableDeferred<Unit>()
        val propagated = CompletableDeferred<Throwable>()
        approvalManager.setCallbacks(
            onGenerateMessage = null,
            onExecuteTools = { _, _, _ ->
                callbackEntered.complete(Unit)
                CompletableDeferred<Unit>().await()
            },
        )

        val job = launch {
            try {
                approvalManager.resumeGeneration("s1", request, approved = true)
            } catch (error: Throwable) {
                propagated.complete(error)
                throw error
            }
        }
        callbackEntered.await()
        job.cancel(CancellationException("real cancellation"))
        job.join()

        assertThat(propagated.await()).isInstanceOf(CancellationException::class.java)
        assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.CANCELLED)
        assertThat(store.getSession("s1")!!.approvalRequest).isNull()
        assertThat(store.getSession("s1")!!.messages.last().content).contains("已取消")
    }

    @Test
    fun realJobCancellationKeepsOriginalCancellationAndSuppressesCleanupFailure() = testScope.runTest {
        val call = ToolCall("cleanup-fails", "write_file", "{}")
        seedSessionWithAssistant(toolCalls = listOf(call), pendingApprovalToolIds = listOf(call.id))
        val key = ToolExecutionKey("s1", "m1", call.id)
        ledger.register(key, call.name, true)
        val cleanupFailure = IllegalStateException("cleanup failed")
        ledger.compensationFailure = cleanupFailure
        val request = ToolApprovalRequestFactory.create(
            "m1",
            listOf(ApprovalCallCandidate(call, com.promenar.infinity.domain.tool.ToolRisk.FILE_WRITE)),
        )
        approvalManager.setApprovalRequest("s1", request)
        val callbackEntered = CompletableDeferred<Unit>()
        val propagated = CompletableDeferred<Throwable>()
        approvalManager.setCallbacks(
            onGenerateMessage = null,
            onExecuteTools = { _, _, _ ->
                callbackEntered.complete(Unit)
                CompletableDeferred<Unit>().await()
            },
        )

        val job = launch {
            try {
                approvalManager.resumeGeneration("s1", request, approved = true)
            } catch (error: Throwable) {
                propagated.complete(error)
                throw error
            }
        }
        callbackEntered.await()
        val originalCancellation = CancellationException("original cancellation")
        job.cancel(originalCancellation)
        job.join()

        val failure = propagated.await()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(failure).hasMessageThat().isEqualTo("original cancellation")
        assertThat(failure.suppressed).hasLength(1)
        assertThat(failure.suppressed.single()).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure.suppressed.single()).hasMessageThat().isEqualTo("cleanup failed")
        assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.APPROVED)
    }

    @Test
    fun concurrentDoubleSubmitExecutesCurrentApprovalOnlyOnce() = testScope.runTest {
        val call = ToolCall("once", "write_file", "{}")
        seedSessionWithAssistant(toolCalls = listOf(call), pendingApprovalToolIds = listOf(call.id))
        val key = ToolExecutionKey("s1", "m1", call.id)
        ledger.register(key, call.name, true)
        val request = ToolApprovalRequestFactory.create(
            "m1",
            listOf(ApprovalCallCandidate(call, com.promenar.infinity.domain.tool.ToolRisk.FILE_WRITE)),
        )
        approvalManager.setApprovalRequest("s1", request)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var executions = 0
        approvalManager.setCallbacks(
            onGenerateMessage = { _, _, _ -> },
            onExecuteTools = { sessionId, messageId, ids ->
                executions += 1
                entered.complete(Unit)
                release.await()
                ids.forEach { ledger.states[ToolExecutionKey(sessionId, messageId, it)] = ToolLedgerState.SUCCEEDED }
            },
        )

        val first = launch { approvalManager.resumeGeneration("s1", request, approved = true) }
        entered.await()
        val duplicate = launch { approvalManager.resumeGeneration("s1", request, approved = false) }
        release.complete(Unit)
        listOf(first, duplicate).joinAll()

        assertThat(executions).isEqualTo(1)
        assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.SUCCEEDED)
        assertThat(store.getSession("s1")!!.approvalRequest).isNull()
    }

    @Test
    fun approvalTransitionToNextGroupKeepsWaitingAndDoesNotGenerate() = testScope.runTest {
        val first = ToolCall("first", "write_file", "{}")
        val next = ToolCall("next", "write_file", "{}")
        seedSessionWithAssistant(toolCalls = listOf(first), pendingApprovalToolIds = listOf(first.id))
        store.updateSession("s1") { session ->
            session.copy(messages = session.messages + Message(
                id = "m2",
                role = MessageRole.ASSISTANT,
                content = "next",
                toolCalls = listOf(next),
            ))
        }
        ledger.register(ToolExecutionKey("s1", "m1", first.id), first.name, true)
        val request = ToolApprovalRequestFactory.create(
            "m1",
            listOf(ApprovalCallCandidate(first, com.promenar.infinity.domain.tool.ToolRisk.UNKNOWN)),
        )
        approvalManager.setApprovalRequest("s1", request)
        val nextRequest = ToolApprovalRequestFactory.create(
            "m2",
            listOf(ApprovalCallCandidate(next, com.promenar.infinity.domain.tool.ToolRisk.UNKNOWN)),
        )
        ledger.completionTransition = com.promenar.infinity.data.repository.ApprovalTransition(
            sessionId = "s1",
            currentAssistantMessageId = "m1",
            loopStatus = LoopStatus.WAITING_FOR_APPROVAL,
            approvalRequest = nextRequest,
            nextAssistantMessageId = "m2",
            nextPendingToolCallIds = listOf(next.id),
        )
        var generateCount = 0
        approvalManager.setCallbacks(
            onGenerateMessage = { _, _, _ -> generateCount += 1 },
            onExecuteTools = { _, _, _ -> },
        )

        approvalManager.resumeGeneration("s1", request, approved = true)

        assertThat(generateCount).isEqualTo(0)
        assertThat(store.getSession("s1")!!.loopStatus).isEqualTo(LoopStatus.WAITING_FOR_APPROVAL)
        assertThat(store.getSession("s1")!!.approvalRequest?.calls?.single()?.toolName)
            .isEqualTo(next.name)
        assertThat(store.getSession("s1")!!.messages.first { it.id == "m2" }.pendingApprovalToolIds)
            .containsExactly(next.id)
    }

    @Test
    fun consecutiveExactApprovalsAlwaysDecideTheCurrentlyVisibleFirstIdentity() = testScope.runTest {
        val calls = listOf(
            ToolCall("first", "write_file", "{\"path\":\"a\"}"),
            ToolCall("second", "delete_file", "{\"path\":\"b\"}"),
        )
        seedSessionWithAssistant(toolCalls = calls, pendingApprovalToolIds = calls.map { it.id })
        calls.forEach { call ->
            ledger.register(
                ToolExecutionKey("s1", "m1", call.id),
                preparedApprovalIdentity(call),
            )
        }
        val request = ToolApprovalRequestFactory.create(
            "m1",
            listOf(
                ApprovalCallCandidate(calls[0], com.promenar.infinity.domain.tool.ToolRisk.FILE_WRITE),
                ApprovalCallCandidate(calls[1], com.promenar.infinity.domain.tool.ToolRisk.DELETE),
            ),
        )
        approvalManager.setApprovalRequest("s1", request)
        val executed = mutableListOf<Set<String>>()
        approvalManager.setCallbacks(
            onGenerateMessage = { _, _, _ -> },
            onExecuteTools = { sessionId, messageId, ids ->
                executed += ids
                ids.forEach { ledger.states[ToolExecutionKey(sessionId, messageId, it)] = ToolLedgerState.SUCCEEDED }
            },
        )

        approvalManager.resumeGeneration("s1", request, approved = true)
        val nextRequest = store.getSession("s1")!!.approvalRequest!!
        approvalManager.resumeGeneration("s1", nextRequest, approved = true)
        approvalManager.resumeGeneration("s1", request, approved = true)

        assertThat(executed).containsExactly(setOf("first"), setOf("second")).inOrder()
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "first")))
            .isEqualTo(ToolLedgerState.SUCCEEDED)
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "second")))
            .isEqualTo(ToolLedgerState.SUCCEEDED)
        assertThat(store.getSession("s1")!!.approvalRequest).isNull()
    }

    @Test
    fun staleHashMessageIdAndQueueMismatchFailClosedWithoutExecution() = testScope.runTest {
        val call = ToolCall("risk", "write_file", "{\"path\":\"a\"}")
        seedSessionWithAssistant(toolCalls = listOf(call), pendingApprovalToolIds = listOf(call.id))
        ledger.register(
            ToolExecutionKey("s1", "m1", call.id),
            preparedApprovalIdentity(call),
        )
        val request = ToolApprovalRequestFactory.create(
            "m1",
            listOf(ApprovalCallCandidate(call, com.promenar.infinity.domain.tool.ToolRisk.FILE_WRITE)),
        )
        approvalManager.setApprovalRequest("s1", request)
        var executionCount = 0
        approvalManager.setCallbacks(
            onGenerateMessage = { _, _, _ -> },
            onExecuteTools = { _, _, _ -> executionCount += 1 },
        )

        approvalManager.resumeGeneration("s1", request.copy(identityHash = "old"), approved = true)
        approvalManager.resumeGeneration("s1", request.copy(assistantMessageId = "old-message"), approved = true)
        approvalManager.resumeGeneration("s1", request.copy(calls = emptyList()), approved = false)

        assertThat(executionCount).isEqualTo(0)
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", call.id)))
            .isEqualTo(ToolLedgerState.PENDING_APPROVAL)
        assertThat(store.getSession("s1")!!.approvalRequest).isEqualTo(request)
    }
}
