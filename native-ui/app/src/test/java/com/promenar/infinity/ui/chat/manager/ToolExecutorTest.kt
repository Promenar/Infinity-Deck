package com.promenar.infinity.ui.chat.manager

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.generation.BuiltinToolPreferences
import com.promenar.infinity.data.generation.DefaultSessionToolResolver
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.generation.SessionToolResolver
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.repository.IMessageRepository
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.data.repository.ToolExecutionKey
import com.promenar.infinity.data.repository.ToolExecutionLedger
import com.promenar.infinity.data.repository.ToolExecutionOutcome
import com.promenar.infinity.data.repository.ToolInvocationIdentity
import com.promenar.infinity.data.repository.ToolInvocationIdentityFactory
import com.promenar.infinity.data.repository.ToolInvocationIdentityResolution
import com.promenar.infinity.data.repository.ToolLedgerState
import com.promenar.infinity.data.repository.ToolRegistrationResult
import com.promenar.infinity.data.repository.ToolTerminalRequest
import com.promenar.infinity.data.repository.ToolAllowlistStore
import com.promenar.infinity.ui.chat.ChatStore
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.registry.SkillRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import com.promenar.infinity.data.remote.protocol.ProtocolToolFunction
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.domain.tool.ToolExecutionTargets
import kotlinx.coroutines.CancellationException
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk

@OptIn(ExperimentalCoroutinesApi::class)
class ToolExecutorTest {
    @get:Rule
    val grantFiles = TemporaryFolder()
    private lateinit var store: ChatStore
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var messageManager: MessageManager
    private lateinit var sessionManager: SessionManager
    private val testScope = TestScope()

    private class RecordingLedger(
        private val beforeInvocationIdentity: suspend (Int) -> Unit = {},
        private val afterRegisterPersisted: suspend (ToolRegistrationResult) -> Unit = {},
    ) : ToolExecutionLedger {
        private val states = mutableMapOf<ToolExecutionKey, ToolLedgerState>()
        private val identities = mutableMapOf<ToolExecutionKey, ToolInvocationIdentity>()
        private var invocationIdentityReads = 0

        override suspend fun register(
            key: ToolExecutionKey,
            identity: ToolInvocationIdentity,
        ): ToolRegistrationResult {
            val result = synchronized(states) {
                val existing = identities[key]
                if (existing != null) {
                    return@synchronized if (existing == identity) {
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
                ToolRegistrationResult.Registered(state)
            }
            afterRegisterPersisted(result)
            return result
        }

        override suspend fun approve(keys: Set<ToolExecutionKey>): Int = transition(
            keys,
            setOf(ToolLedgerState.PENDING_APPROVAL),
            ToolLedgerState.APPROVED,
        )

        override suspend fun reject(keys: Set<ToolExecutionKey>): Int = transition(
            keys,
            setOf(ToolLedgerState.PENDING_APPROVAL),
            ToolLedgerState.REJECTED,
        )

        override suspend fun cancel(keys: Set<ToolExecutionKey>): Int = transition(
            keys,
            setOf(ToolLedgerState.PENDING_APPROVAL, ToolLedgerState.APPROVED),
            ToolLedgerState.CANCELLED,
        )

        override suspend fun timeout(keys: Set<ToolExecutionKey>): Int = transition(
            keys,
            setOf(ToolLedgerState.PENDING_APPROVAL, ToolLedgerState.APPROVED),
            ToolLedgerState.TIMED_OUT,
        )

        override suspend fun claim(
            key: ToolExecutionKey,
            expectedIdentity: ToolInvocationIdentity,
        ): Boolean = synchronized(states) {
            if (identities[key] == expectedIdentity && states[key] == ToolLedgerState.APPROVED) {
                states[key] = ToolLedgerState.RUNNING
                true
            } else {
                false
            }
        }

        override suspend fun finish(key: ToolExecutionKey, outcome: ToolExecutionOutcome): Boolean =
            synchronized(states) {
                if (states[key] != ToolLedgerState.RUNNING) return@synchronized false
                states[key] = outcome.state
                true
            }

        override suspend fun finishWithResult(
            key: ToolExecutionKey,
            toolName: String,
            content: String,
            thoughtSignature: String?,
            outcome: ToolExecutionOutcome,
            images: String?,
            viewedMedia: List<com.promenar.infinity.domain.media.ViewedMedia>?,
        ): Message? = if (finish(key, outcome)) {
            Message(
                id = "tool-result-${key.toolCallId}",
                role = MessageRole.TOOL,
                toolCallId = key.toolCallId,
                name = toolName,
                content = content,
                images = images,
                viewedMedia = viewedMedia,
                thoughtSignature = thoughtSignature,
            )
        } else null

        override suspend fun terminalizeWithResults(
            requests: Set<ToolTerminalRequest>,
            state: ToolLedgerState,
        ): List<Message> = synchronized(states) {
            requests.mapNotNull { request ->
                val allowed = when (state) {
                    ToolLedgerState.CANCELLED -> setOf(
                        ToolLedgerState.PENDING_APPROVAL,
                        ToolLedgerState.APPROVED,
                        ToolLedgerState.RUNNING,
                    )
                    else -> emptySet()
                }
                if (states[request.key] !in allowed) return@mapNotNull null
                states[request.key] = state
                Message(
                    id = "tool-terminal-${request.key.toolCallId}",
                    role = MessageRole.TOOL,
                    toolCallId = request.key.toolCallId,
                    parentMessageId = request.key.assistantMessageId,
                    name = request.toolName,
                    content = request.interruptionReason ?: "工具执行已取消。",
                    thoughtSignature = request.thoughtSignature,
                )
            }
        }

        override suspend fun state(key: ToolExecutionKey): ToolLedgerState? = synchronized(states) { states[key] }

        override suspend fun invocationIdentity(key: ToolExecutionKey): ToolInvocationIdentity? {
            val read = synchronized(states) { ++invocationIdentityReads }
            beforeInvocationIdentity(read)
            return synchronized(states) { identities[key] }
        }

        override suspend fun failAwaitingIdentityConflict(
            key: ToolExecutionKey,
            expectedIdentity: ToolInvocationIdentity,
            thoughtSignature: String?,
        ): Message? = synchronized(states) {
            if (identities[key] != expectedIdentity || states[key] !in setOf(
                    ToolLedgerState.PENDING_APPROVAL,
                    ToolLedgerState.APPROVED,
                )
            ) return@synchronized null
            states[key] = ToolLedgerState.FAILED
            Message(
                id = "tool-conflict-${key.toolCallId}",
                role = MessageRole.TOOL,
                toolCallId = key.toolCallId,
                parentMessageId = key.assistantMessageId,
                name = expectedIdentity.toolName,
                content = "工具调用与已登记身份不一致，已安全终止。",
                thoughtSignature = thoughtSignature,
            )
        }

        override suspend fun recoverInterruptedRunning(error: String): Int = synchronized(states) {
            val running = states.filterValues { it == ToolLedgerState.RUNNING }.keys
            running.forEach { states[it] = ToolLedgerState.FAILED }
            running.size
        }
        override suspend fun createToolApproval(
            keySessionId: String,
            assistantMessageId: String,
            toolCalls: List<ToolCall>,
            pendingToolCallIds: Set<String>,
            request: ApprovalRequest,
        ): com.promenar.infinity.data.repository.ToolApprovalCreation {
            toolCalls.forEach {
                val identity = ToolInvocationIdentityFactory.fromLegacyToolCall(
                    it,
                    it.id in pendingToolCallIds,
                )
                if (identity !is ToolInvocationIdentityResolution.Valid) {
                    return com.promenar.infinity.data.repository.ToolApprovalCreation.CONFLICT
                }
                if (register(
                        ToolExecutionKey(keySessionId, assistantMessageId, it.id),
                        identity.identity,
                    ) == ToolRegistrationResult.Conflict
                ) return com.promenar.infinity.data.repository.ToolApprovalCreation.CONFLICT
            }
            return com.promenar.infinity.data.repository.ToolApprovalCreation.CREATED
        }
        override suspend fun completeToolApproval(sessionId: String, assistantMessageId: String) =
            com.promenar.infinity.data.repository.ApprovalTransition(
                sessionId,
                assistantMessageId,
                LoopStatus.RUNNING,
                null,
            )
        override suspend fun recoverApprovalState(): Int = 0

        private fun transition(
            keys: Set<ToolExecutionKey>,
            from: Set<ToolLedgerState>,
            to: ToolLedgerState,
        ): Int = synchronized(states) {
            keys.count { key ->
                if (states[key] in from) {
                    states[key] = to
                    true
                } else {
                    false
                }
            }
        }
    }

    private val stubSessionRepo = object : ISessionRepository {
        override suspend fun create(session: Session) {}
        override suspend fun updatePartial(id: String, updates: Map<String, Any?>) {}
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

    @Before
    fun setUp() {
        store = ChatStore()
        messageManager = MessageManager(store, stubMessageRepo, stubSessionRepo, testScope)
        sessionManager = SessionManager(store, stubSessionRepo)
        toolExecutor = ToolExecutor(
            store,
            messageManager,
            null,
            testToolResolver(null),
            ledger = RecordingLedger(),
        )
    }

    private suspend fun seedSessionWithAssistant(
        sessionId: String = "s1",
        toolsEnabled: Boolean = true
    ): Session {
        val session = Session(
            id = sessionId,
            agentId = "a1",
            title = "Test",
            options = SessionOptions(toolsEnabled = toolsEnabled),
            workspaceRootUuid = "workspace-root-1",
        )
        sessionManager.addSession(session)
        testScope.advanceUntilIdle()

        val msg = Message(
            id = "m1",
            role = MessageRole.ASSISTANT,
            content = "I'll use a tool",
            createdAt = 1000L
        )
        store.updateSession(sessionId) { s -> s.copy(messages = s.messages + msg) }
        return session
    }

    @Test fun `批内插队跳过剩余调用并为每个调用保留结果配对`() = testScope.runTest {
        seedSessionWithAssistant()
        val queue = kotlinx.coroutines.flow.MutableStateFlow(com.promenar.infinity.data.chat.queue.ComposerQueueState())
        var executions = 0
        val skill = testSkill("steering_tool") {
            executions++
            queue.value = com.promenar.infinity.data.chat.queue.ComposerQueueState(items = listOf(
                com.promenar.infinity.data.chat.queue.QueuedMessage("q", "新指令", createdAt = 0, steering = true)))
            ToolResult("result", "done", "success")
        }
        val registry = singleSkillRegistry(skill)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = RecordingLedger())
        val calls = listOf("one", "two", "three").map { ToolCall(it, "steering_tool", "{}") }
        kotlinx.coroutines.withContext(com.promenar.infinity.data.exec.SteeringContext(queue)) {
            executor.executeTools("s1", "m1", calls, preparedTools = registry.getAllTools())
        }
        assertThat(executions).isEqualTo(1)
        val results = store.getSession("s1")!!.messages.filter { it.role == MessageRole.TOOL }
        assertThat(results.map { it.toolCallId }).containsExactly("one", "two", "three")
        assertThat(results.filter { it.toolCallId != "one" }.all { it.content.contains("用户插入了新消息，此调用未执行") }).isTrue()
    }

    @Test fun `插队立即取消当前普通工具且整批调用各有一次结果`() = testScope.runTest {
        seedSessionWithAssistant()
        val queue = kotlinx.coroutines.flow.MutableStateFlow(com.promenar.infinity.data.chat.queue.ComposerQueueState())
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        var cancelled = false
        var executions = 0
        val skill = testSkill("cancellable_tool") {
            executions++
            entered.complete(Unit)
            try { kotlinx.coroutines.awaitCancellation() } finally { cancelled = true }
        }
        val registry = singleSkillRegistry(skill)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = RecordingLedger())
        val calls = listOf("one", "two", "three").map { ToolCall(it, skill.name, "{}") }
        val operation = async(com.promenar.infinity.data.exec.SteeringContext(queue)) {
            executor.executeTools("s1", "m1", calls, preparedTools = registry.getAllTools())
        }
        entered.await()
        queue.value = com.promenar.infinity.data.chat.queue.ComposerQueueState(items = listOf(
            com.promenar.infinity.data.chat.queue.QueuedMessage("normal", "普通排队", createdAt = 0)))
        kotlinx.coroutines.yield()
        assertThat(operation.isCompleted).isFalse()
        queue.value = com.promenar.infinity.data.chat.queue.ComposerQueueState(items = listOf(
            com.promenar.infinity.data.chat.queue.QueuedMessage("urgent", "插队", createdAt = 0, steering = true)))
        operation.await()
        assertThat(cancelled).isTrue()
        assertThat(executions).isEqualTo(1)
        val results = store.getSession("s1")!!.messages.filter { it.role == MessageRole.TOOL }
        assertThat(results.map { it.toolCallId }).containsExactly("one", "two", "three")
        assertThat(results.single { it.toolCallId == "one" }.content).contains("用户插队打断")
        assertThat(results.filter { it.toolCallId != "one" }.all { it.content.contains("此调用未执行") }).isTrue()
    }

    @Test fun `普通排队在批内到达时执行同批全部工具`() = testScope.runTest {
        seedSessionWithAssistant()
        val queue = kotlinx.coroutines.flow.MutableStateFlow(com.promenar.infinity.data.chat.queue.ComposerQueueState())
        var executions = 0
        val skill = testSkill("queued_tool") {
            executions++
            queue.value = com.promenar.infinity.data.chat.queue.ComposerQueueState(items = listOf(
                com.promenar.infinity.data.chat.queue.QueuedMessage("normal", "普通排队", createdAt = 0)))
            ToolResult("result", "done", "success")
        }
        val registry = singleSkillRegistry(skill)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = RecordingLedger())
        kotlinx.coroutines.withContext(com.promenar.infinity.data.exec.SteeringContext(queue)) {
            executor.executeTools("s1", "m1", listOf("one", "two", "three").map { ToolCall(it, skill.name, "{}") }, preparedTools = registry.getAllTools())
        }
        assertThat(executions).isEqualTo(3)
        assertThat(store.getSession("s1")!!.messages.filter { it.role == MessageRole.TOOL }.map { it.toolCallId })
            .containsExactly("one", "two", "three")
    }

    @Test
    fun `已批准工具成功写文件后按会话执行一次收尾`() = testScope.runTest {
        verifyApprovedToolFinalization(fail = false)
    }

    @Test
    fun `已批准工具写文件后抛错仍按会话执行一次收尾`() = testScope.runTest {
        verifyApprovedToolFinalization(fail = true)
    }

    private suspend fun verifyApprovedToolFinalization(fail: Boolean) {
        val sessionId = "branch-finalize-$fail"
        seedSessionWithAssistant(sessionId)
        val file = grantFiles.newFile("tool-finalize-$fail.txt")
        var executions = 0
        val skill = testSkill("write_file", risk = ToolRisk.FILE_WRITE) {
            executions++
            file.writeText("工具已写入")
            if (fail) throw IllegalStateException("写入后的工具异常")
            ToolResult("approved-write", "写入完成", status = "success")
        }
        val registry = singleSkillRegistry(skill)
        val ledger = RecordingLedger()
        val call = ToolCall("approved-write", skill.name, "{}")
        val key = ToolExecutionKey(sessionId, "m1", call.id)
        val identity = (ToolInvocationIdentityFactory.fromPreparedToolCall(call, registry.getAllTools().single(), true)
            as ToolInvocationIdentityResolution.Valid).identity
        ledger.register(key, identity)
        assertThat(ledger.approve(setOf(key))).isEqualTo(1)
        val finalized = mutableListOf<String>()
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = ledger,
            afterTools = { id ->
                assertThat(file.readText()).isEqualTo("工具已写入")
                finalized += id
            })

        executor.executeTools(sessionId, "m1", listOf(call), preparedTools = registry.getAllTools())

        assertThat(executions).isEqualTo(1)
        assertThat(finalized).containsExactly(sessionId)
        assertThat(ledger.state(key)).isEqualTo(if (fail) ToolLedgerState.FAILED else ToolLedgerState.SUCCEEDED)
    }

    @Test
    fun `已批准工具协程取消后仍在不可取消上下文完成一次收尾`() = testScope.runTest {
        val sessionId = "branch-finalize-cancelled"
        seedSessionWithAssistant(sessionId)
        val file = grantFiles.newFile("tool-finalize-cancelled.txt")
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val skill = testSkill("write_file", risk = ToolRisk.FILE_WRITE) {
            file.writeText("取消前已写入")
            entered.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }
        val registry = singleSkillRegistry(skill)
        val ledger = RecordingLedger()
        val call = ToolCall("cancelled-write", skill.name, "{}")
        val key = ToolExecutionKey(sessionId, "m1", call.id)
        val identity = (ToolInvocationIdentityFactory.fromPreparedToolCall(call, registry.getAllTools().single(), true)
            as ToolInvocationIdentityResolution.Valid).identity
        ledger.register(key, identity)
        assertThat(ledger.approve(setOf(key))).isEqualTo(1)
        val finalized = mutableListOf<String>()
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = ledger,
            afterTools = { id ->
                assertThat(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.isActive).isTrue()
                kotlinx.coroutines.delay(1)
                assertThat(file.readText()).isEqualTo("取消前已写入")
                finalized += id
            })
        val execution = async {
            executor.executeTools(sessionId, "m1", listOf(call), preparedTools = registry.getAllTools())
        }
        entered.await()

        execution.cancel()
        execution.join()

        assertThat(execution.isCancelled).isTrue()
        assertThat(finalized).containsExactly(sessionId)
        assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.CANCELLED)
    }

    @Test
    fun `会话删除锁定后工具入口在注册ledger前拒绝执行`() = testScope.runTest {
        seedSessionWithAssistant()
        val gate = com.promenar.infinity.data.session.SessionExecutionGate()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val deletion = async {
            gate.withDeletion("s1", "workspace-root-1") {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val ledger = RecordingLedger()
        val executor = ToolExecutor(
            store, messageManager, null, testToolResolver(null), ledger = ledger,
            executionGate = gate,
        )

        val failure = runCatching {
            executor.executeTools("s1", "m1", emptyList())
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(com.promenar.infinity.data.session.SessionDeletingException::class.java)
        release.complete(Unit)
        deletion.await()
    }

    @Test
    fun `知识检索结构化数据经过执行器持久化仍可供详情读取`() = testScope.runTest {
        seedSessionWithAssistant()
        val reference = com.promenar.infinity.data.model.RagReference("r", "文档内容", "资料", score = .8f)
        val body = com.promenar.infinity.data.knowledge.KnowledgeSearchData("问题", listOf("documents"),
            references = listOf(reference), results = listOf(com.promenar.infinity.data.knowledge.KnowledgeSearchHit("r", "K1")))
        val encoded = com.promenar.infinity.data.model.json.encodeToString(com.promenar.infinity.data.knowledge.KnowledgeSearchData.serializer(), body)
        val search = testSkill("knowledge_search") { ToolResult("search", "[K1] 文档内容", data = encoded) }
        val registry = skillsRegistry(search)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = RecordingLedger())
        executor.executeTools("s1", "m1", listOf(ToolCall("search", "knowledge_search", "{}")), preparedTools = registry.getAllTools(null))
        messageManager.flushNonApprovalUpdatesNow("s1", "m1")
        val stored = store.getSession("s1")!!.messages.single { it.id == "m1" }.executionSteps.orEmpty().single { it.type == "tool_result" }
        assertThat(com.promenar.infinity.data.model.json.decodeFromString<com.promenar.infinity.data.knowledge.KnowledgeSearchData>(requireNotNull(stored.data))).isEqualTo(body)
    }

    @Test
    fun `生图结构化数据经过执行器持久化供卡片读取，未知版本被丢弃`() = testScope.runTest {
        seedSessionWithAssistant()
        val body = com.promenar.infinity.data.model.ImageGenerationResultData(
            mode = "generate", model = "gpt-image-2",
            actual = com.promenar.infinity.data.model.ImageGenerationActual(size = "1536x1024", quality = "high"),
        )
        val skill = testSkill("generate_image") { args ->
            val data = if (args.containsKey("legacy")) """{"schema":"infinity-image-generation-v0","mode":"generate","model":"m"}""" else body.encode()
            ToolResult("img", "已保存", data = data)
        }
        val registry = skillsRegistry(skill)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = RecordingLedger())
        executor.executeTools("s1", "m1", listOf(ToolCall("img", "generate_image", "{}"), ToolCall("old", "generate_image", """{"legacy":true}""")),
            preparedTools = registry.getAllTools(null))
        messageManager.flushNonApprovalUpdatesNow("s1", "m1")
        val results = store.getSession("s1")!!.messages.single { it.id == "m1" }.executionSteps.orEmpty().filter { it.type == "tool_result" }
        assertThat(com.promenar.infinity.data.model.ImageGenerationResultData.decode(results.single { it.toolCallId == "img" }.data)).isEqualTo(body)
        assertThat(results.single { it.toolCallId == "old" }.data).isNull()
    }

    @Test
    fun `关闭工具时搜索经过真实解析与账本执行且拒绝其它工具`() = testScope.runTest {
        seedSessionWithAssistant(toolsEnabled = false)
        var searches = 0
        var reads = 0
        val web = testSkill("web_search") { searches++; ToolResult("web", "搜索结果") }
        val read = testSkill("read_file") { reads++; ToolResult("read", "不应执行") }
        val registry = skillsRegistry(web, read)
        val settings = mockk<SharedPreferences>(relaxed = true)
        every { settings.getStringSet(any(), any()) } returns null
        val resolver = DefaultSessionToolResolver(settings, registry)
        val ledger = RecordingLedger()
        val executor = ToolExecutor(store, messageManager, registry, resolver, ledger = ledger)
        val prepared = resolver.resolve(store.getSession("s1")!!)
        assertThat(prepared.map { it.function.name }).containsExactly("web_search")
        executor.executeTools("s1", "m1", listOf(ToolCall("web", "web_search", "{}"), ToolCall("read", "read_file", "{}")), preparedTools = prepared)
        assertThat(searches).isEqualTo(1)
        assertThat(reads).isEqualTo(0)
        assertThat(store.getSession("s1")!!.messages.single { it.toolCallId == "read" }.content).isEqualTo(disabledToolsMessage(true))
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "web"))).isEqualTo(ToolLedgerState.SUCCEEDED)
        store.updateSession("s1") { it.copy(options = it.options.copy(keepWebSearchWhenToolsOff = false)) }
        executor.executeTools("s1", "m1", listOf(ToolCall("off", "web_search", "{}")), preparedTools = prepared)
        assertThat(store.getSession("s1")!!.messages.single { it.toolCallId == "off" }.content).isEqualTo(disabledToolsMessage(false))
        assertThat(searches).isEqualTo(1)
        store.updateSession("s1") { it.copy(options = it.options.copy(keepWebSearchWhenToolsOff = true)) }
        every { settings.getStringSet(BuiltinToolPreferences.ENABLED_KEY, null) } returns emptySet()
        every { settings.getStringSet(BuiltinToolPreferences.KNOWN_KEY, null) } returns setOf("web_search")
        executor.executeTools("s1", "m1", listOf(ToolCall("global-off", "web_search", "{}")), preparedTools = prepared)
        assertThat(searches).isEqualTo(1)
    }

    @Test
    fun executeToolsBlockedWhenDisabled() = testScope.runTest {
        seedSessionWithAssistant(toolsEnabled = false)

        val toolCalls = listOf(ToolCall(id = "tc1", name = "read_file", arguments = """{"path":"/tmp"}"""))
        toolExecutor.executeTools(
            "s1",
            "m1",
            toolCalls,
            preparedTools = listOf(testSkill("read_file") { ToolResult("unused", "unused") }.toPreparedTool()),
        )
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        val toolMessages = session.messages.filter { it.role == MessageRole.TOOL }
        assertThat(toolMessages).hasSize(1)
        assertThat(toolMessages[0].content).contains("DISABLED")
    }

    @Test
    fun toolStepLinksToCurrentPlanFocus() = testScope.runTest {
        seedSessionWithAssistant()
        val skill = testSkill(name = "plain_tool", schema = """{"type":"object","properties":{}}""") {
            ToolResult("plain", "ok")
        }
        val registry = singleSkillRegistry(skill)
        val plan = com.promenar.infinity.data.model.TaskState(id = "root", title = "目标", currentFocusStepId = "step-2")
        val taskRepository = mockk<com.promenar.infinity.domain.repository.ITaskRepository>(relaxed = true) {
            coEvery { getPlan("s1") } returns plan
        }
        val executor = ToolExecutor(
            store,
            messageManager,
            registry,
            testToolResolver(registry),
            taskRepository = taskRepository,
            ledger = RecordingLedger(),
        )

        executor.executeTools(
            "s1",
            "m1",
            listOf(ToolCall("linked", "plain_tool", "{}")),
            setOf("linked"),
            listOf(skill.toPreparedTool()),
        )
        advanceUntilIdle()

        // 消息步骤经 MessageManager 节流通道写回 store，按真实时间等待落地。
        val step = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) {
                var found: com.promenar.infinity.data.model.ExecutionStep? = null
                while (found == null) {
                    found = store.getSession("s1")!!.messages.first { it.id == "m1" }.executionSteps.orEmpty()
                        .firstOrNull { it.toolCallId == "linked" }
                    if (found == null) kotlinx.coroutines.delay(20)
                }
                found
            }
        }
        assertThat(step.taskStepId).isEqualTo("step-2")
    }

    @Test
    fun `Linux命令定义通过账本校验后到达执行后端`() = testScope.runTest {
        seedSessionWithAssistant()
        store.updateSession("s1") { it.copy(runMode = com.promenar.infinity.domain.model.SessionRunMode.LINUX) }
        val backend = mockk<com.promenar.infinity.data.exec.BuiltinExecService>()
        every { backend.pipAvailable } returns false
        coEvery { backend.runShell(any(), any(), any(), any(), any(), any(), any()) } returns
            com.promenar.infinity.domain.tool.CommandToolResult(exitCode = 0, stdout = "linux")
        val skill = com.promenar.infinity.ui.chat.manager.skills.ShellExecSkill(backend)
        val registry = singleSkillRegistry(skill)
        val settings = mockk<SharedPreferences>(relaxed = true)
        every { settings.getStringSet(any(), any()) } returns null
        val resolver = DefaultSessionToolResolver(settings, registry)
        val ledger = RecordingLedger()
        val executor = ToolExecutor(store, messageManager, registry, resolver, ledger = ledger)
        executor.executeTools("s1", "m1", listOf(ToolCall("linux-shell", "shell_exec", "{\"command\":\"printf linux\"}")),
            preparedTools = resolver.resolve(store.getSession("s1")!!))
        io.mockk.coVerify(exactly = 1) {
            backend.runShell("workspace-root-1", "s1", "printf linux", null, 60, any(), com.promenar.infinity.domain.model.SessionRunMode.LINUX)
        }
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "linux-shell"))).isEqualTo(ToolLedgerState.SUCCEEDED)
    }

    @Test
    fun `模式切换窗口保留准入时的命令模式快照`() = testScope.runTest {
        seedSessionWithAssistant()
        store.updateSession("s1") { it.copy(runMode = com.promenar.infinity.domain.model.SessionRunMode.BUILTIN) }
        val backend = mockk<com.promenar.infinity.data.exec.BuiltinExecService>()
        every { backend.pipAvailable } returns false
        coEvery { backend.runShell(any(), any(), any(), any(), any(), any(), any()) } returns
            com.promenar.infinity.domain.tool.CommandToolResult(errorCode = "EXEC_MODE_CHANGED")
        val registry = singleSkillRegistry(com.promenar.infinity.ui.chat.manager.skills.ShellExecSkill(backend))
        val settings = mockk<SharedPreferences>(relaxed = true)
        every { settings.getStringSet(any(), any()) } returns null
        val resolver = DefaultSessionToolResolver(settings, registry)
        val executor = ToolExecutor(store, messageManager, registry, resolver, ledger = RecordingLedger(), hooks = ToolExecutorHooks(
            beforeAppendStep = { store.updateSession("s1") { it.copy(runMode = com.promenar.infinity.domain.model.SessionRunMode.LINUX) } },
        ))
        executor.executeTools("s1", "m1", listOf(ToolCall("mode-race", "shell_exec", "{\"command\":\"touch forbidden\"}")),
            preparedTools = resolver.resolve(store.getSession("s1")!!))
        io.mockk.coVerify(exactly = 1) {
            backend.runShell("workspace-root-1", "s1", "touch forbidden", null, 60, any(), com.promenar.infinity.domain.model.SessionRunMode.BUILTIN)
        }
    }

    @Test
    fun preparedSnapshotPreservesNestedTypedArguments() = testScope.runTest {
        seedSessionWithAssistant()
        var recorded: JsonObject? = null
        val skill = testSkill(
            name = "nested_tool",
            schema = """{"type":"object","properties":{"object":{"type":"object","additionalProperties":true},"array":{"type":"array","items":{}},"decimal":{"type":"number"},"flag":{"type":"boolean"},"nil":{"type":"null"}},"required":["object","array","decimal","flag","nil"]}""",
        ) { args ->
            recorded = args
            ToolResult("nested", "ok")
        }
        val registry = singleSkillRegistry(skill)
        val prepared = skill.toPreparedTool()
        val executor = ToolExecutor(
            store,
            messageManager,
            registry,
            testToolResolver(registry),
            ledger = RecordingLedger(),
        )

        executor.executeTools(
            "s1",
            "m1",
            listOf(ToolCall("nested", "nested_tool", """{"object":{"x":1},"array":[true,null],"decimal":1.5,"flag":false,"nil":null}""")),
            preparedTools = listOf(prepared),
        )

        val args = requireNotNull(recorded)
        assertThat(args.getValue("object").jsonObject.getValue("x").jsonPrimitive.content).isEqualTo("1")
        assertThat(args.getValue("array").jsonArray[0].jsonPrimitive.boolean).isTrue()
        assertThat(args.getValue("array").jsonArray[1]).isEqualTo(JsonNull)
        assertThat(args.getValue("decimal").jsonPrimitive.content).isEqualTo("1.5")
        assertThat(args.getValue("flag").jsonPrimitive.boolean).isFalse()
        assertThat(args.getValue("nil")).isEqualTo(JsonNull)
    }

    @Test
    fun prompt后会话取消Mcp授权必须在执行副作用前失败关闭() = testScope.runTest {
        seedSessionWithAssistant()
        store.updateSession("s1") { it.copy(activeMcpServerIds = listOf("server-a")) }
        var executions = 0
        val skill = object : SkillDefinition {
            override val id = "mcp:server-a:search"
            override val runtimeToolId = id
            override val sourceId = "mcp"
            override val name = "mcp_alias_search"
            override val description = "search"
            override val mcpServerId = "server-a"
            override val parametersSchema = """{"type":"object"}"""
            override val risk = ToolRisk.SAFE_READ
            override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
                executions++
                return ToolResult("result", "unexpected")
            }
        }
        val prepared = skill.toPreparedTool()
        val ledger = RecordingLedger()
        val registry = singleSkillRegistry(skill)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = ledger)
        store.updateSession("s1") { it.copy(activeMcpServerIds = emptyList()) }

        executor.executeTools(
            "s1",
            "m1",
            listOf(ToolCall("revoked", skill.name, "{}")),
            preparedTools = listOf(prepared),
        )

        assertThat(executions).isEqualTo(0)
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "revoked")))
            .isEqualTo(ToolLedgerState.FAILED)
    }

    @Test
    fun `运行中的实时输出按调用步骤登记，结束后移除且不进入工具结果`() = testScope.runTest {
        seedSessionWithAssistant()
        val live = com.promenar.infinity.data.exec.ToolLiveOutputStore()
        var seenDuringRun: com.promenar.infinity.data.exec.ToolLiveOutputStore.Snapshot? = null
        var stepId: String? = null
        val base = testSkill("shell_like") { ToolResult("result", "final") }
        val skill = object : SkillDefinition by base {
            override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
                context.liveOutput.emit(com.promenar.infinity.domain.tool.ToolOutputStream.STDOUT, "progress\n")
                stepId = live.keys().single()
                seenDuringRun = live.snapshot(stepId!!)
                return ToolResult("result", "final")
            }
        }
        val registry = singleSkillRegistry(skill)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = RecordingLedger(), liveOutput = live)

        executor.executeTools("s1", "m1", listOf(ToolCall("c1", skill.name, "{}")), preparedTools = registry.getAllTools())

        advanceUntilIdle()
        assertThat(seenDuringRun?.text).isEqualTo("progress\n")
        assertThat(live.keys()).isEmpty()
        val steps = store.getSession("s1")!!.messages.first { it.id == "m1" }.executionSteps!!
        assertThat(stepId).isEqualTo(steps.single { it.type == "tool_call" }.id)
        assertThat(steps.single { it.type == "tool_result" }.content).isEqualTo("final")
    }

    @Test
    fun `模型切换后撤销媒体能力必须在查看前拒绝执行`() = testScope.runTest {
        seedSessionWithAssistant()
        var executions = 0
        val skill = testSkill("view_media") { executions++; ToolResult("result", "不应执行") }
        val registry = singleSkillRegistry(skill)
        val settings = mockk<SharedPreferences>(relaxed = true)
        every { settings.getStringSet(any(), null) } returns null
        var caps = com.promenar.infinity.data.model.ModelCapabilities(vision = true)
        val resolver = DefaultSessionToolResolver(settings, registry) { caps }
        val prepared = resolver.resolve(com.promenar.infinity.data.model.Session("s1", "a")).single()
        val ledger = RecordingLedger()
        val executor = ToolExecutor(store, messageManager, registry, resolver, ledger = ledger)
        caps = com.promenar.infinity.data.model.ModelCapabilities()
        executor.executeTools("s1", "m1", listOf(ToolCall("revoked-media", skill.name, "{}")), preparedTools = listOf(prepared))
        assertThat(executions).isEqualTo(0)
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "revoked-media"))).isEqualTo(ToolLedgerState.FAILED)
    }

    @Test
    fun prompt后全局关闭内置工具必须在执行副作用前失败关闭() = testScope.runTest {
        seedSessionWithAssistant()
        var executions = 0
        val skill = testSkill("read_file") {
            executions++
            ToolResult("result", "unexpected")
        }
        val registry = singleSkillRegistry(skill)
        val settings = mockk<SharedPreferences>()
        var enabledSkills = setOf("read_file")
        every { settings.getStringSet("enabled_skills", null) } answers { enabledSkills }
        every { settings.getStringSet("known_builtin_skills", null) } returns setOf("read_file")
        val prepared = skill.toPreparedTool()
        val ledger = RecordingLedger()
        val executor = ToolExecutor(
            store,
            messageManager,
            registry,
            DefaultSessionToolResolver(settings, registry),
            ledger = ledger,
        )
        enabledSkills = emptySet()

        executor.executeTools(
            "s1",
            "m1",
            listOf(ToolCall("revoked-builtin", skill.name, "{}")),
            preparedTools = listOf(prepared),
        )

        assertThat(executions).isEqualTo(0)
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "revoked-builtin")))
            .isEqualTo(ToolLedgerState.FAILED)
    }

    @Test
    fun `批次内移动 uuid 后仅持旧目标授权的写入和删除在副作用前拒绝`() = testScope.runTest {
        seedSessionWithAssistant()
        val paths = mutableMapOf("entry" to "/before/a.md")
        val grants = ToolAllowlistStore(grantFiles.newFolder("grants"))
        val oldTarget = """["path","/before/a.md"]"""
        grants.add("write_file", oldTarget, "session:s1", "s1")
        grants.add("delete_file", oldTarget, "session:s1", "s1")
        val sideEffects = mutableListOf<String>()
        val move = testSkill(
            name = "move_file",
            schema = """{"type":"object","properties":{"uuid":{"type":"string"},"newParentPath":{"type":"string"}},"required":["uuid","newParentPath"]}""",
            risk = ToolRisk.FILE_WRITE,
        ) { args ->
            paths[args.getValue("uuid").jsonPrimitive.content] = "/after/a.md"
            sideEffects += "move"
            ToolResult("move-result", "moved", status = "success")
        }
        val write = testSkill(
            "write_file", schema = """{"type":"object","properties":{"uuid":{"type":"string"}},"required":["uuid"]}""",
            risk = ToolRisk.FILE_WRITE,
        ) {
            sideEffects += "write"
            ToolResult("write-result", "written", status = "success")
        }
        val delete = testSkill(
            "delete_file", schema = """{"type":"object","properties":{"uuid":{"type":"string"}},"required":["uuid"]}""",
            risk = ToolRisk.DELETE,
        ) {
            sideEffects += "delete"
            ToolResult("delete-result", "deleted", status = "success")
        }
        val registry = skillsRegistry(move, write, delete)
        val ledger = RecordingLedger()
        val executor = ToolExecutor(
            store, messageManager, registry, testToolResolver(registry), ledger = ledger,
            grantVerifier = { session, skill, args, expected ->
                ToolExecutionTargets.extract(skill.toPreparedTool(), args.toString(), paths) == expected &&
                    grants.contains(skill.name, expected, "session:${session.id}")
            },
        )
        val calls = listOf(
            ToolCall("move", "move_file", """{"uuid":"entry","newParentPath":"/after"}"""),
            ToolCall("write", "write_file", """{"uuid":"entry"}"""),
            ToolCall("delete", "delete_file", """{"uuid":"entry"}"""),
        )

        executor.executeTools(
            "s1", "m1", calls, preparedTools = registry.getAllTools(),
            expectedGrantTargets = mapOf("write" to oldTarget, "delete" to oldTarget),
        )

        assertThat(paths["entry"]).isEqualTo("/after/a.md")
        assertThat(sideEffects).containsExactly("move")
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "move"))).isEqualTo(ToolLedgerState.SUCCEEDED)
        listOf("write", "delete").forEach { id ->
            assertThat(ledger.state(ToolExecutionKey("s1", "m1", id))).isEqualTo(ToolLedgerState.FAILED)
            val message = store.getSession("s1")!!.messages.single { it.role == MessageRole.TOOL && it.toolCallId == id }
            assertThat(message.content).contains("授权")
        }
    }

    @Test
    fun `授权撤销后已准备的免审批调用在执行前拒绝`() = testScope.runTest {
        seedSessionWithAssistant()
        val target = """["path","/a.md"]"""
        val grants = ToolAllowlistStore(grantFiles.newFolder("grants"))
        grants.add("write_file", target, "session:s1", "s1")
        var writes = 0
        val skill = testSkill("write_file") { writes++; ToolResult("write", "written", status = "success") }
        val registry = singleSkillRegistry(skill)
        val ledger = RecordingLedger()
        val executor = ToolExecutor(
            store, messageManager, registry, testToolResolver(registry), ledger = ledger,
            hooks = ToolExecutorHooks(beforeAppendStep = { grants.remove(grants.entries.value.single().id) }),
            grantVerifier = { session, currentSkill, _, expected -> grants.contains(currentSkill.name, expected, "session:${session.id}") },
        )
        val prepared = registry.getAllTools()

        executor.executeTools(
            "s1", "m1", listOf(ToolCall("write", "write_file", "{}")), preparedTools = prepared,
            expectedGrantTargets = mapOf("write" to target),
        )

        assertThat(writes).isEqualTo(0)
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "write"))).isEqualTo(ToolLedgerState.FAILED)
        assertThat(store.getSession("s1")!!.messages.single { it.role == MessageRole.TOOL }.content).contains("授权")
    }

    @Test
    fun `目标与持久授权未变的免审批调用正常执行一次`() = testScope.runTest {
        seedSessionWithAssistant()
        val paths = mapOf("entry" to "/docs/a.md")
        val target = """["path","/docs/a.md"]"""
        val grants = ToolAllowlistStore(grantFiles.newFolder("grants"))
        grants.add("write_file", target, "session:s1", "s1")
        var writes = 0
        val skill = testSkill(
            "write_file", schema = """{"type":"object","properties":{"uuid":{"type":"string"}},"required":["uuid"]}""",
            risk = ToolRisk.FILE_WRITE,
        ) { writes++; ToolResult("write", "written", status = "success") }
        val registry = singleSkillRegistry(skill)
        val ledger = RecordingLedger()
        val executor = ToolExecutor(
            store, messageManager, registry, testToolResolver(registry), ledger = ledger,
            grantVerifier = { session, currentSkill, args, expected ->
                ToolExecutionTargets.extract(currentSkill.toPreparedTool(), args.toString(), paths) == expected &&
                    grants.contains(currentSkill.name, expected, "session:${session.id}")
            },
        )

        executor.executeTools(
            "s1", "m1", listOf(ToolCall("write", "write_file", """{"uuid":"entry"}""")),
            preparedTools = registry.getAllTools(), expectedGrantTargets = mapOf("write" to target),
        )

        assertThat(writes).isEqualTo(1)
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "write"))).isEqualTo(ToolLedgerState.SUCCEEDED)
        assertThat(store.getSession("s1")!!.messages.single { it.role == MessageRole.TOOL }.content).isEqualTo("written")
    }

    @Test fun `限时授权按调用绑定目标且不改变台账免审批身份或其它调用上下文`() = testScope.runTest {
        seedSessionWithAssistant()
        val target = """["path","/skills/demo/SKILL.md"]"""
        val contexts = mutableListOf<Pair<String?, Boolean>>()
        val base = testSkill("write_file") { ToolResult("write", "written", status = "success") }
        val skill = object : SkillDefinition by base {
            override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
                contexts += context.approvedExecutionTarget to context.isApprovalBoundExecution
                return ToolResult("write", "written", status = "success")
            }
        }
        val registry = singleSkillRegistry(skill)
        val ledger = RecordingLedger()
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = ledger,
            grantVerifier = { _, _, _, _ -> error("限时授权不得查询持久白名单") })
        executor.executeTools("s1", "m1", listOf(ToolCall("write", "write_file", "{}"), ToolCall("ordinary", "write_file", "{}"), ToolCall("pending", "write_file", "{}")),
            allowedToolCallIds = setOf("write", "ordinary"), preparedTools = registry.getAllTools(), timedGrantTargets = mapOf("write" to target))
        assertThat(contexts).containsExactly(target to true, null to false).inOrder()
        assertThat(ledger.invocationIdentity(ToolExecutionKey("s1", "m1", "write"))!!.requiresApproval).isFalse()
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "write"))).isEqualTo(ToolLedgerState.SUCCEEDED)
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "pending"))).isEqualTo(ToolLedgerState.PENDING_APPROVAL)
    }

    @Test
    fun `持久授权目标原样传给技能执行上下文`() = testScope.runTest {
        seedSessionWithAssistant()
        val target = """["path","/mnt/termux/a.txt",{"mountIds":{"termux":"mount"}}]"""
        var received: String? = null
        var approvalBound = false
        val base = testSkill("write_file") { ToolResult("write", "written", status = "success") }
        val skill = object : SkillDefinition by base {
            override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
                received = context.approvedExecutionTarget
                approvalBound = context.isApprovalBoundExecution
                return ToolResult("write", "written", status = "success")
            }
        }
        val registry = singleSkillRegistry(skill)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = RecordingLedger(),
            grantVerifier = { _, _, _, _ -> true })
        executor.executeTools("s1", "m1", listOf(ToolCall("write", "write_file", "{}")),
            preparedTools = registry.getAllTools(), expectedGrantTargets = mapOf("write" to target))
        assertThat(received).isEqualTo(target)
        assertThat(approvalBound).isTrue()
    }

    @Test
    fun `真实画布调用记录助手消息而非工具调用ID`() = testScope.runTest {
        seedSessionWithAssistant()
        val canvases = com.promenar.infinity.data.canvas.FileCanvasStore(grantFiles.newFolder(), kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val skill = com.promenar.infinity.ui.chat.manager.skills.CanvasCreateSkill(canvases)
        val registry = singleSkillRegistry(skill)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = RecordingLedger())
        executor.executeTools("s1", "m1", listOf(ToolCall("canvas-call", skill.name, """{"title":"统计","html":"<p>42</p>"}""")), preparedTools = registry.getAllTools())
        org.junit.Assert.assertEquals("工具结果：${store.getSession("s1")!!.messages.filter { it.role == MessageRole.TOOL }.map { it.content }}", 1, canvases.list("s1").size)
        assertThat(canvases.list("s1").single().createdByMessageId).isEqualTo("m1")
        assertThat(canvases.read("s1", canvases.list("s1").single().id)!!.html).isEqualTo("<p>42</p>")
    }

    @Test
    fun `显式批准目标只传递给身份完全匹配的调用`() = testScope.runTest {
        val target = """["path","/mnt/termux/a.txt",{"mountIds":{"termux":"mount"}}]"""
        for (matches in listOf(true, false)) {
            val sessionId = "approval-$matches"
            seedSessionWithAssistant(sessionId)
            var received: String? = null
            val base = testSkill("write_file") { ToolResult("write", "written", status = "success") }
            val skill = object : SkillDefinition by base {
                override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
                    received = context.approvedExecutionTarget
                    return ToolResult("write", "written", status = "success")
                }
            }
            val registry = singleSkillRegistry(skill)
            val call = ToolCall("write", "write_file", "{}")
            val identity = (ToolInvocationIdentityFactory.fromPreparedToolCall(call, registry.getAllTools().single(), true)
                as ToolInvocationIdentityResolution.Valid).identity
            val request = com.promenar.infinity.data.repository.ToolApprovalRequestFactory.create("m1", listOf(
                com.promenar.infinity.data.repository.ApprovalCallCandidate(call, ToolRisk.FILE_WRITE, identity, target)))
            val stored = if (matches) request else request.copy(calls = request.calls.map { it.copy(argumentsDigest = "different") })
            store.updateSession(sessionId) { it.copy(approvalRequest = stored) }
            ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = RecordingLedger())
                .executeTools(sessionId, "m1", listOf(call), preparedTools = registry.getAllTools())
            assertThat(received).isEqualTo(if (matches) target else null)
        }
    }

    @Test
    fun `免审批调用缺失授权验证器时失败关闭而普通批准保持可执行`() = testScope.runTest {
        seedSessionWithAssistant()
        var writes = 0
        val skill = testSkill("write_file") { writes++; ToolResult("write", "written", status = "success") }
        val registry = singleSkillRegistry(skill)
        val ledger = RecordingLedger()
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = ledger)

        executor.executeTools(
            "s1", "m1", listOf(ToolCall("grant", "write_file", "{}")), preparedTools = registry.getAllTools(),
            expectedGrantTargets = mapOf("grant" to """["path","/a.md"]"""),
        )
        executor.executeTools(
            "s1", "m1", listOf(ToolCall("approved", "write_file", "{}")), preparedTools = registry.getAllTools(),
        )

        assertThat(writes).isEqualTo(1)
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "grant"))).isEqualTo(ToolLedgerState.FAILED)
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "approved"))).isEqualTo(ToolLedgerState.SUCCEEDED)
    }

    @Test
    fun `授权验证异常受控拒绝且不暴露异常详情`() = testScope.runTest {
        seedSessionWithAssistant()
        var writes = 0
        val skill = testSkill("write_file") { writes++; ToolResult("write", "written", status = "success") }
        val registry = singleSkillRegistry(skill)
        val ledger = RecordingLedger()
        val executor = ToolExecutor(
            store, messageManager, registry, testToolResolver(registry), ledger = ledger,
            grantVerifier = { _, _, _, _ -> throw IllegalStateException("授权读取内部详情") },
        )

        executor.executeTools(
            "s1", "m1", listOf(ToolCall("grant", "write_file", "{}")), preparedTools = registry.getAllTools(),
            expectedGrantTargets = mapOf("grant" to """["path","/a.md"]"""),
        )

        assertThat(writes).isEqualTo(0)
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "grant"))).isEqualTo(ToolLedgerState.FAILED)
        val message = store.getSession("s1")!!.messages.single { it.role == MessageRole.TOOL }
        assertThat(message.content).contains("授权")
        assertThat(message.content).doesNotContain("授权读取内部详情")
    }

    @Test
    fun `授权验证取消沿用取消补偿且不会执行工具`() = testScope.runTest {
        seedSessionWithAssistant()
        var writes = 0
        val skill = testSkill("write_file") { writes++; ToolResult("write", "written", status = "success") }
        val registry = singleSkillRegistry(skill)
        val ledger = RecordingLedger()
        val cancellation = CancellationException("授权验证取消")
        val executor = ToolExecutor(
            store, messageManager, registry, testToolResolver(registry), ledger = ledger,
            grantVerifier = { _, _, _, _ -> throw cancellation },
        )

        val failure = runCatching {
            executor.executeTools(
                "s1", "m1", listOf(ToolCall("grant", "write_file", "{}")), preparedTools = registry.getAllTools(),
                expectedGrantTargets = mapOf("grant" to """["path","/a.md"]"""),
            )
        }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(cancellation)
        assertThat(writes).isEqualTo(0)
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "grant"))).isEqualTo(ToolLedgerState.CANCELLED)
    }

    @Test
    fun malformedUnknownAndSchemaMismatchFailDeterministicallyWithoutExecution() = testScope.runTest {
        seedSessionWithAssistant()
        var executions = 0
        val skill = testSkill(
            name = "safe_tool",
            schema = """{"type":"object","properties":{"value":{"type":"string"}},"required":["value"],"additionalProperties":false}""",
        ) {
            executions++
            ToolResult("unexpected", "unexpected")
        }
        val ledger = RecordingLedger()
        val registry = singleSkillRegistry(skill)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = ledger)
        val prepared = listOf(skill.toPreparedTool())
        val calls = listOf(
            ToolCall("malformed", "safe_tool", "{"),
            ToolCall("root-array", "safe_tool", "[]"),
            ToolCall("unknown", "unknown_tool", "{}"),
            ToolCall("schema", "safe_tool", """{"value":1,"secret":"do-not-echo"}"""),
        )

        executor.executeTools("s1", "m1", calls, preparedTools = prepared)
        advanceUntilIdle()

        assertThat(executions).isEqualTo(0)
        val messages = store.getSession("s1")!!.messages.filter { it.role == MessageRole.TOOL }
        assertThat(messages.map { it.toolCallId }).containsExactlyElementsIn(calls.map { it.id })
        val byCall = messages.associate { it.toolCallId to it.content }
        assertThat(byCall.values.all { it.startsWith("工具调用校验失败：") }).isTrue()
        assertThat(byCall["unknown"]).contains("可用工具：safe_tool")
        assertThat(byCall["schema"]).contains("ADDITIONAL_PROPERTY_NOT_ALLOWED")
        assertThat(byCall["schema"]).contains("\$.secret")
        assertThat(messages.joinToString { it.content }).doesNotContain("do-not-echo")
        calls.forEach { call ->
            assertThat(ledger.state(ToolExecutionKey("s1", "m1", call.id))).isEqualTo(ToolLedgerState.FAILED)
        }
    }

    @Test
    fun `预检失败的调用也在助手消息里留下调用与错误步骤供界面展示`() = testScope.runTest {
        seedSessionWithAssistant()
        val skill = testSkill(
            name = "safe_tool",
            schema = """{"type":"object","properties":{"value":{"type":"string"}},"required":["value"],"additionalProperties":false}""",
        ) { ToolResult("unexpected", "unexpected") }
        val registry = singleSkillRegistry(skill)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = RecordingLedger())
        val calls = listOf(
            ToolCall("schema", "safe_tool", """{"value":1}"""),
            ToolCall("unknown", "unknown_tool", "{}"),
        )

        executor.executeTools("s1", "m1", calls, preparedTools = listOf(skill.toPreparedTool()))
        advanceUntilIdle()

        val steps = store.getSession("s1")!!.messages.first { it.id == "m1" }.executionSteps.orEmpty()
        calls.forEach { call ->
            val forCall = steps.filter { it.toolCallId == call.id }
            assertThat(forCall.map { it.type }).containsExactly("tool_call", "error").inOrder()
            assertThat(forCall.map { it.toolName }.distinct()).containsExactly(call.name)
            assertThat(forCall.last().content).startsWith("工具调用校验失败：")
        }
    }

    @Test
    fun concurrentInvalidInvocationHasOneTerminalAndModifiedSameKeyConflicts() = testScope.runTest {
        seedSessionWithAssistant()
        val skill = testSkill(
            name = "validated_tool",
            schema = """{"type":"object","properties":{"value":{"type":"string"}},"required":["value"],"additionalProperties":false}""",
        ) { ToolResult("unexpected", "unexpected") }
        val ledger = RecordingLedger()
        val registry = singleSkillRegistry(skill)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = ledger)
        val prepared = listOf(skill.toPreparedTool())
        val invalid = ToolCall("same-invalid", "validated_tool", """{"value":1}""")

        listOf(
            async { executor.executeTools("s1", "m1", listOf(invalid), preparedTools = prepared) },
            async { executor.executeTools("s1", "m1", listOf(invalid), preparedTools = prepared) },
        ).awaitAll()
        executor.executeTools(
            "s1",
            "m1",
            listOf(invalid.copy(arguments = """{"value":false}""")),
            preparedTools = prepared,
        )
        advanceUntilIdle()

        assertThat(store.getSession("s1")!!.messages.count {
            it.role == MessageRole.TOOL && it.toolCallId == invalid.id
        }).isEqualTo(1)
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", invalid.id)))
            .isEqualTo(ToolLedgerState.FAILED)
    }

    @Test
    fun persistedPendingParameterMismatchFailsClosedWithoutExecutionOrSensitiveEcho() = testScope.runTest {
        seedSessionWithAssistant()
        var executions = 0
        val skill = testSkill(
            name = "write_file",
            schema = """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}""",
        ) {
            executions++
            ToolResult("unexpected", "unexpected")
        }
        val prepared = skill.toPreparedTool()
        val original = ToolCall("persisted-pending", "write_file", """{"path":"safe.txt"}""")
        val identity = (ToolInvocationIdentityFactory.fromPreparedToolCall(
            original,
            prepared,
            requiresApproval = true,
        ) as ToolInvocationIdentityResolution.Valid).identity
        val ledger = RecordingLedger()
        val key = ToolExecutionKey("s1", "m1", original.id)
        ledger.register(key, identity)
        val registry = singleSkillRegistry(skill)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = ledger)

        executor.executeTools(
            "s1",
            "m1",
            listOf(original.copy(arguments = """{"path":"sk-sensitive-token"}""")),
            allowedToolCallIds = emptySet(),
            preparedTools = listOf(prepared),
        )
        advanceUntilIdle()

        assertThat(executions).isEqualTo(0)
        assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.FAILED)
        assertThat(ledger.approve(setOf(key))).isEqualTo(0)
        val terminal = store.getSession("s1")!!.messages.single {
            it.role == MessageRole.TOOL && it.toolCallId == original.id
        }
        assertThat(terminal.content).isEqualTo("工具调用与已登记身份不一致，已安全终止。")
        assertThat(terminal.content).doesNotContain("sk-sensitive-token")
    }

    @Test
    fun persistedApprovedNameMismatchFailsClosedWithoutExecutingReplacement() = testScope.runTest {
        seedSessionWithAssistant()
        var executions = 0
        val originalSkill = testSkill("write_file") { ToolResult("unused", "unused") }
        val replacementSkill = testSkill("delete_file") {
            executions++
            ToolResult("unexpected", "unexpected")
        }
        val original = ToolCall("persisted-approved", "write_file", "{}")
        val identity = (ToolInvocationIdentityFactory.fromPreparedToolCall(
            original,
            originalSkill.toPreparedTool(),
            requiresApproval = true,
        ) as ToolInvocationIdentityResolution.Valid).identity
        val ledger = RecordingLedger()
        val key = ToolExecutionKey("s1", "m1", original.id)
        ledger.register(key, identity)
        ledger.approve(setOf(key))
        val replacementRegistry = singleSkillRegistry(replacementSkill)
        val executor = ToolExecutor(
            store,
            messageManager,
            replacementRegistry,
            testToolResolver(replacementRegistry),
            ledger = ledger,
        )

        executor.executeTools(
            "s1",
            "m1",
            listOf(original.copy(name = "delete_file")),
            allowedToolCallIds = setOf(original.id),
            preparedTools = listOf(replacementSkill.toPreparedTool()),
        )
        advanceUntilIdle()

        assertThat(executions).isEqualTo(0)
        assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.FAILED)
        assertThat(store.getSession("s1")!!.messages.single {
            it.role == MessageRole.TOOL && it.toolCallId == original.id
        }.content).isEqualTo("工具调用与已登记身份不一致，已安全终止。")
    }

    @Test
    fun concurrentPersistedApprovalAttributeMismatchCreatesOneRedactedTerminal() = testScope.runTest {
        seedSessionWithAssistant()
        var executions = 0
        val skill = testSkill("read_file") {
            executions++
            ToolResult("unexpected", "unexpected")
        }
        val prepared = skill.toPreparedTool()
        val original = ToolCall("persisted-safe", "read_file", """{"secret":"sk-sensitive-token"}""")
        val identity = (ToolInvocationIdentityFactory.fromPreparedToolCall(
            original,
            prepared,
            requiresApproval = false,
        ) as ToolInvocationIdentityResolution.Valid).identity
        val ledger = RecordingLedger()
        val key = ToolExecutionKey("s1", "m1", original.id)
        ledger.register(key, identity)
        val registry = singleSkillRegistry(skill)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = ledger)

        listOf(
            async {
                executor.executeTools(
                    "s1", "m1", listOf(original), emptySet(), listOf(prepared),
                )
            },
            async {
                executor.executeTools(
                    "s1", "m1", listOf(original), emptySet(), listOf(prepared),
                )
            },
        ).awaitAll()
        advanceUntilIdle()

        assertThat(executions).isEqualTo(0)
        assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.FAILED)
        val terminals = store.getSession("s1")!!.messages.filter {
            it.role == MessageRole.TOOL && it.toolCallId == original.id
        }
        assertThat(terminals).hasSize(1)
        assertThat(terminals.single().content)
            .isEqualTo("工具调用与已登记身份不一致，已安全终止。")
        assertThat(terminals.single().content).doesNotContain("sk-sensitive-token")
    }

    @Test
    fun changedRegistryDefinitionFailsAfterClaimWithoutExecutingReplacement() = testScope.runTest {
        seedSessionWithAssistant()
        var executions = 0
        val preparedSkill = testSkill("replaceable", description = "prompt definition") { ToolResult("x", "x") }
        val currentSkill = testSkill("replaceable", description = "changed definition") {
            executions++
            ToolResult("unexpected", "unexpected")
        }
        val ledger = RecordingLedger()
        val currentRegistry = singleSkillRegistry(currentSkill)
        val executor = ToolExecutor(
            store,
            messageManager,
            currentRegistry,
            testToolResolver(currentRegistry),
            ledger = ledger,
        )

        executor.executeTools(
            "s1",
            "m1",
            listOf(ToolCall("changed", "replaceable", "{}")),
            preparedTools = listOf(preparedSkill.toPreparedTool()),
        )
        advanceUntilIdle()

        assertThat(executions).isEqualTo(0)
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "changed"))).isEqualTo(ToolLedgerState.FAILED)
        assertThat(store.getSession("s1")!!.messages.last().content).contains("工具执行失败")
    }

    @Test
    fun skillCancellationPropagatesWithoutSyntheticSuccess() = testScope.runTest {
        seedSessionWithAssistant()
        val skill = testSkill("cancel_tool") { throw CancellationException("cancel") }
        val ledger = RecordingLedger()
        val registry = singleSkillRegistry(skill)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = ledger)

        var thrown: Throwable? = null
        try {
            executor.executeTools(
                "s1",
                "m1",
                listOf(ToolCall("cancelled", "cancel_tool", "{}")),
                preparedTools = listOf(skill.toPreparedTool()),
            )
        } catch (error: Throwable) {
            thrown = error
        }

        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        val key = ToolExecutionKey("s1", "m1", "cancelled")
        assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.CANCELLED)
        val terminalMessages = store.getSession("s1")!!.messages.filter {
            it.role == MessageRole.TOOL && it.toolCallId == "cancelled"
        }
        assertThat(terminalMessages).hasSize(1)
        assertThat(terminalMessages.single().content).isEqualTo("工具执行已取消。")
    }

    @Test
    fun cancellationBeforeAppendStepTerminalizesClaimExactlyOnce() = testScope.runTest {
        val expected = CancellationException("append-step-cancel")
        assertPostClaimCancellation(
            hooks = ToolExecutorHooks(
                beforeAppendStep = { throw expected },
            ),
            expectedCancellation = expected,
            expectedExecutions = 0,
        )
    }

    @Test
    fun cancellationAfterSkillSideEffectTerminalizesClaimWithoutReexecution() = testScope.runTest {
        val expected = CancellationException("post-skill-cancel")
        assertPostClaimCancellation(
            hooks = ToolExecutorHooks(
                afterSkillExecution = { throw expected },
            ),
            expectedCancellation = expected,
            expectedExecutions = 1,
        )
    }

    @Test
    fun cancellationBeforeLedgerFinishTerminalizesClaimWithoutReexecution() = testScope.runTest {
        val expected = CancellationException("finish-cancel")
        assertPostClaimCancellation(
            hooks = ToolExecutorHooks(
                beforeLedgerFinish = { throw expected },
            ),
            expectedCancellation = expected,
            expectedExecutions = 1,
        )
    }

    @Test
    fun cancellationAfterRegistrationBeforeClaimTerminalizesWithoutLaterExecution() = testScope.runTest {
        seedSessionWithAssistant()
        val expected = CancellationException("registered-before-identity-cancel")
        var executions = 0
        val skill = testSkill("registered_cancel_tool") {
            executions++
            ToolResult("registered-cancel", "unexpected")
        }
        val ledger = RecordingLedger(
            beforeInvocationIdentity = { read ->
                if (read == 2) throw expected
            },
        )
        val registry = singleSkillRegistry(skill)
        val executor = ToolExecutor(
            store = store,
            messageManager = messageManager,
            skillRegistry = registry,
            toolResolver = testToolResolver(registry),
            ledger = ledger,
        )
        val call = ToolCall("registered-cancel", "registered_cancel_tool", "{}")

        val cancellation = runCatching {
            executor.executeTools(
                "s1",
                "m1",
                listOf(call),
                preparedTools = listOf(skill.toPreparedTool()),
            )
        }.exceptionOrNull()

        assertThat(cancellation).isSameInstanceAs(expected)
        val key = ToolExecutionKey("s1", "m1", call.id)
        assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.CANCELLED)
        assertThat(executions).isEqualTo(0)
        assertThat(store.getSession("s1")!!.messages.filter {
            it.role == MessageRole.TOOL && it.toolCallId == call.id
        }).hasSize(1)

        executor.executeTools(
            "s1",
            "m1",
            listOf(call),
            preparedTools = listOf(skill.toPreparedTool()),
        )
        assertThat(executions).isEqualTo(0)
        assertThat(store.getSession("s1")!!.messages.filter {
            it.role == MessageRole.TOOL && it.toolCallId == call.id
        }).hasSize(1)
    }

    @Test
    fun cancellationAfterRegisterCommitTerminalizesExactInvocationWithoutLaterExecution() = testScope.runTest {
        seedSessionWithAssistant()
        val expected = CancellationException("register-commit-cancel")
        var registerReturns = 0
        var executions = 0
        val skill = testSkill("register_commit_cancel_tool") {
            executions++
            ToolResult("register-commit-cancel", "unexpected")
        }
        val ledger = RecordingLedger(
            afterRegisterPersisted = {
                registerReturns++
                if (registerReturns == 1) throw expected
            },
        )
        val registry = singleSkillRegistry(skill)
        val executor = ToolExecutor(
            store = store,
            messageManager = messageManager,
            skillRegistry = registry,
            toolResolver = testToolResolver(registry),
            ledger = ledger,
        )
        val call = ToolCall("register-commit-cancel", "register_commit_cancel_tool", "{}")

        val cancellation = runCatching {
            executor.executeTools(
                "s1",
                "m1",
                listOf(call),
                preparedTools = listOf(skill.toPreparedTool()),
            )
        }.exceptionOrNull()

        assertThat(cancellation).isSameInstanceAs(expected)
        val key = ToolExecutionKey("s1", "m1", call.id)
        assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.CANCELLED)
        assertThat(executions).isEqualTo(0)
        assertThat(store.getSession("s1")!!.messages.filter {
            it.role == MessageRole.TOOL && it.toolCallId == call.id
        }).hasSize(1)

        executor.executeTools(
            "s1",
            "m1",
            listOf(call),
            preparedTools = listOf(skill.toPreparedTool()),
        )
        assertThat(executions).isEqualTo(0)
        assertThat(store.getSession("s1")!!.messages.filter {
            it.role == MessageRole.TOOL && it.toolCallId == call.id
        }).hasSize(1)
    }

    @Test
    fun invalidCallCancellationAfterRegisterCommitTerminalizesWithoutRetryMessage() = testScope.runTest {
        seedSessionWithAssistant()
        val expected = CancellationException("invalid-register-commit-cancel")
        var registerReturns = 0
        var executions = 0
        val skill = testSkill("invalid_register_cancel_tool") {
            executions++
            ToolResult("invalid-register-cancel", "unexpected")
        }
        val ledger = RecordingLedger(
            afterRegisterPersisted = {
                registerReturns++
                if (registerReturns == 1) throw expected
            },
        )
        val registry = singleSkillRegistry(skill)
        val executor = ToolExecutor(
            store = store,
            messageManager = messageManager,
            skillRegistry = registry,
            toolResolver = testToolResolver(registry),
            ledger = ledger,
        )
        val call = ToolCall("invalid-register-cancel", "invalid_register_cancel_tool", "not-json")

        val cancellation = runCatching {
            executor.executeTools(
                "s1",
                "m1",
                listOf(call),
                preparedTools = listOf(skill.toPreparedTool()),
            )
        }.exceptionOrNull()

        assertThat(cancellation).isSameInstanceAs(expected)
        val key = ToolExecutionKey("s1", "m1", call.id)
        assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.CANCELLED)
        assertThat(executions).isEqualTo(0)
        assertThat(store.getSession("s1")!!.messages.filter {
            it.role == MessageRole.TOOL && it.toolCallId == call.id
        }).hasSize(1)

        executor.executeTools(
            "s1",
            "m1",
            listOf(call),
            preparedTools = listOf(skill.toPreparedTool()),
        )
        assertThat(executions).isEqualTo(0)
        assertThat(store.getSession("s1")!!.messages.filter {
            it.role == MessageRole.TOOL && it.toolCallId == call.id
        }).hasSize(1)
    }

    private suspend fun assertPostClaimCancellation(
        hooks: ToolExecutorHooks,
        expectedCancellation: CancellationException,
        expectedExecutions: Int,
    ) {
        seedSessionWithAssistant()
        var executions = 0
        val skill = testSkill("cancel_boundary_tool") {
            executions++
            ToolResult("cancel-boundary", "side effect completed")
        }
        val ledger = RecordingLedger()
        val registry = singleSkillRegistry(skill)
        val executor = ToolExecutor(
            store = store,
            messageManager = messageManager,
            skillRegistry = registry,
            toolResolver = testToolResolver(registry),
            ledger = ledger,
            hooks = hooks,
        )
        val call = ToolCall("cancel-boundary", "cancel_boundary_tool", "{}")
        val cancellation = runCatching {
            executor.executeTools(
                "s1",
                "m1",
                listOf(call),
                preparedTools = listOf(skill.toPreparedTool()),
            )
        }.exceptionOrNull()

        assertThat(cancellation).isSameInstanceAs(expectedCancellation)
        val key = ToolExecutionKey("s1", "m1", call.id)
        assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.CANCELLED)
        assertThat(executions).isEqualTo(expectedExecutions)
        val terminalMessages = store.getSession("s1")!!.messages.filter {
            it.role == MessageRole.TOOL && it.toolCallId == call.id
        }
        assertThat(terminalMessages).hasSize(1)
        assertThat(terminalMessages.single().content).isEqualTo("工具执行已取消。")

        executor.executeTools(
            "s1",
            "m1",
            listOf(call),
            preparedTools = listOf(skill.toPreparedTool()),
        )
        assertThat(executions).isEqualTo(expectedExecutions)
        assertThat(store.getSession("s1")!!.messages.filter {
            it.role == MessageRole.TOOL && it.toolCallId == call.id
        }).hasSize(1)
    }

    @Test
    fun executeToolsWithNoRegistry() = testScope.runTest {
        seedSessionWithAssistant(toolsEnabled = true)

        val toolCalls = listOf(ToolCall(id = "tc1", name = "read_file", arguments = """{"path":"/tmp"}"""))
        toolExecutor.executeTools(
            "s1",
            "m1",
            toolCalls,
            preparedTools = listOf(testSkill("read_file") { ToolResult("unused", "unused") }.toPreparedTool()),
        )
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        val toolMessages = session.messages.filter { it.role == MessageRole.TOOL }
        assertThat(toolMessages).hasSize(1)
        assertThat(toolMessages[0].content).contains("工具执行失败")
    }

    @Test
    fun executeToolsWithCustomRegistry() = testScope.runTest {
        seedSessionWithAssistant(toolsEnabled = true)

        val customRegistry = object : SkillRegistry {
            override fun getSkill(name: String): SkillDefinition? {
                if (name == "read_file") {
                    return object : SkillDefinition {
                        override val id = "read_file"
                        override val name = "read_file"
                        override val description = "Read a file"
                        override val mcpServerId: String? = null
                        override val parametersSchema = "{}"
                        override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
                            return ToolResult(id = "tc1", content = "File contents here", status = "success")
                        }
                    }
                }
                return null
            }
            override fun getAllSkills(): List<SkillDefinition> = emptyList()
            override fun getAllTools(allowedIds: List<String>?) = emptyList<ProtocolTool>()
        }

        val executorWithRegistry = ToolExecutor(
            store,
            messageManager,
            customRegistry,
            testToolResolver(customRegistry),
            ledger = RecordingLedger(),
        )
        val toolCalls = listOf(ToolCall(id = "tc1", name = "read_file", arguments = """{"path":"/tmp"}"""))
        executorWithRegistry.executeTools(
            "s1",
            "m1",
            toolCalls,
            preparedTools = preparedFor(customRegistry, "read_file"),
        )
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        val toolMessages = session.messages.filter { it.role == MessageRole.TOOL }
        assertThat(toolMessages).hasSize(1)
        assertThat(toolMessages[0].content).isEqualTo("File contents here")
    }

    @Test
    fun executeToolsSkillNotFound() = testScope.runTest {
        seedSessionWithAssistant(toolsEnabled = true)

        val customRegistry = object : SkillRegistry {
            override fun getSkill(name: String) = null
            override fun getAllSkills(): List<SkillDefinition> = emptyList()
            override fun getAllTools(allowedIds: List<String>?) = emptyList<ProtocolTool>()
        }

        val executorWithRegistry = ToolExecutor(
            store,
            messageManager,
            customRegistry,
            testToolResolver(customRegistry),
            ledger = RecordingLedger(),
        )
        val toolCalls = listOf(ToolCall(id = "tc1", name = "unknown_tool", arguments = "{}"))
        executorWithRegistry.executeTools(
            "s1",
            "m1",
            toolCalls,
            preparedTools = listOf(testSkill("unknown_tool") { ToolResult("unused", "unused") }.toPreparedTool()),
        )
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        val toolMessages = session.messages.filter { it.role == MessageRole.TOOL }
        assertThat(toolMessages).hasSize(1)
        assertThat(toolMessages[0].content).contains("工具执行失败")
    }

    @Test
    fun executeToolsSkillThrows() = testScope.runTest {
        seedSessionWithAssistant(toolsEnabled = true)

        val customRegistry = object : SkillRegistry {
            override fun getSkill(name: String): SkillDefinition {
                return object : SkillDefinition {
                    override val id = "fail_tool"
                    override val name = "fail_tool"
                    override val description = "Fails"
                    override val mcpServerId: String? = null
                    override val parametersSchema = "{}"
                    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
                        throw RuntimeException("Something went wrong")
                    }
                }
            }
            override fun getAllSkills(): List<SkillDefinition> = emptyList()
            override fun getAllTools(allowedIds: List<String>?) = emptyList<ProtocolTool>()
        }

        val executorWithRegistry = ToolExecutor(
            store,
            messageManager,
            customRegistry,
            testToolResolver(customRegistry),
            ledger = RecordingLedger(),
        )
        val toolCalls = listOf(ToolCall(id = "tc1", name = "fail_tool", arguments = "{}"))
        executorWithRegistry.executeTools(
            "s1",
            "m1",
            toolCalls,
            preparedTools = preparedFor(customRegistry, "fail_tool"),
        )
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        val toolMessages = session.messages.filter { it.role == MessageRole.TOOL }
        assertThat(toolMessages).hasSize(1)
        assertThat(toolMessages[0].content).contains("工具执行失败")
    }

    @Test
    fun executeToolsSkipsPendingApproval() = testScope.runTest {
        seedSessionWithAssistant(toolsEnabled = true)

        val msgWithPending = store.getSession("s1")!!.messages
            .first { it.role == MessageRole.ASSISTANT }
            .copy(pendingApprovalToolIds = listOf("tc1"))
        store.updateSession("s1") { s ->
            s.copy(messages = s.messages.map { if (it.id == "m1") msgWithPending else it })
        }

        var readFileExecuted = false
        val customRegistry = object : SkillRegistry {
            override fun getSkill(name: String): SkillDefinition? {
                if (name == "file_read") {
                    return object : SkillDefinition {
                        override val id = "file_read"
                        override val name = "file_read"
                        override val description = "Read"
                        override val mcpServerId: String? = null
                        override val parametersSchema = "{}"
                        override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
                            readFileExecuted = true
                            return ToolResult(id = "tc2", content = "file data", status = "success")
                        }
                    }
                }
                return null
            }
            override fun getAllSkills() = emptyList<SkillDefinition>()
            override fun getAllTools(allowedIds: List<String>?) = emptyList<ProtocolTool>()
        }

        val executorWithRegistry = ToolExecutor(
            store,
            messageManager,
            customRegistry,
            testToolResolver(customRegistry),
            ledger = RecordingLedger(),
        )
        val toolCalls = listOf(
            ToolCall(id = "tc1", name = "file_write", arguments = """{"path":"/tmp"}"""),
            ToolCall(id = "tc2", name = "file_read", arguments = """{"path":"/tmp"}""")
        )
        executorWithRegistry.executeTools(
            "s1",
            "m1",
            toolCalls,
            allowedToolCallIds = setOf("tc2"),
            preparedTools = listOf(
                testSkill("file_write") { ToolResult("unused", "unused") }.toPreparedTool(),
                requireNotNull(customRegistry.getSkill("file_read")).toPreparedTool(),
            ),
        )
        advanceUntilIdle()

        assertThat(readFileExecuted).isTrue()
        val session = store.getSession("s1")!!
        val toolMessages = session.messages.filter { it.role == MessageRole.TOOL }
        assertThat(toolMessages).hasSize(1)
        assertThat(toolMessages[0].toolCallId).isEqualTo("tc2")
    }

    @Test
    fun mixedBatchExecutesSafeOnceAndApprovedRiskyOnly() = testScope.runTest {
        seedSessionWithAssistant()
        val ledger = RecordingLedger()
        val counts = mutableMapOf<String, Int>()
        val registry = countingRegistry(counts)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = ledger)
        val calls = listOf(
            ToolCall("safe", "read_file", "{}"),
            ToolCall("risky", "write_file", "{}"),
        )

        executor.executeTools(
            "s1",
            "m1",
            calls,
            allowedToolCallIds = setOf("safe"),
            preparedTools = preparedFor(registry, "read_file", "write_file"),
        )
        val riskyKey = ToolExecutionKey("s1", "m1", "risky")
        ledger.approve(setOf(riskyKey))
        executor.executeTools("s1", "m1", calls, allowedToolCallIds = setOf("risky"))
        val rebuiltExecutor = ToolExecutor(
            store,
            messageManager,
            registry,
            testToolResolver(registry),
            ledger = ledger,
        )
        rebuiltExecutor.executeTools("s1", "m1", calls, allowedToolCallIds = setOf("safe", "risky"))
        advanceUntilIdle()

        assertThat(counts).containsExactly("safe", 1, "risky", 1)
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "safe")))
            .isEqualTo(ToolLedgerState.SUCCEEDED)
        assertThat(ledger.state(riskyKey)).isEqualTo(ToolLedgerState.SUCCEEDED)
    }

    @Test
    fun concurrentExecutorsClaimSameToolOnlyOnce() = testScope.runTest {
        seedSessionWithAssistant()
        val ledger = RecordingLedger()
        val counts = mutableMapOf<String, Int>()
        val registry = countingRegistry(counts)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = ledger)
        val calls = listOf(ToolCall("same", "write_file", "{}"))

        (1..2).map {
            async {
                executor.executeTools(
                    "s1",
                    "m1",
                    calls,
                    setOf("same"),
                    preparedFor(countingRegistry(counts), "write_file"),
                )
            }
        }.awaitAll()
        advanceUntilIdle()

        assertThat(counts["same"]).isEqualTo(1)
        assertThat(store.getSession("s1")!!.messages.count {
            it.role == MessageRole.TOOL && it.toolCallId == "same"
        }).isEqualTo(1)
    }

    @Test
    fun skillFailureIsTerminalAndDoesNotExposeExceptionDetails() = testScope.runTest {
        seedSessionWithAssistant()
        val ledger = RecordingLedger()
        val registry = object : SkillRegistry {
            override fun getSkill(name: String) = object : SkillDefinition {
                override val id = name
                override val name = name
                override val description = "失败工具"
                override val mcpServerId: String? = null
                override val parametersSchema = "{}"
                override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
                    throw IllegalStateException("Authorization: Bearer secret-token")
                }
            }
            override fun getAllSkills() = emptyList<SkillDefinition>()
            override fun getAllTools(allowedIds: List<String>?) = emptyList<ProtocolTool>()
        }
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = ledger)

        executor.executeTools(
            "s1",
            "m1",
            listOf(ToolCall("failed", "remote_tool", "{}")),
            setOf("failed"),
            preparedFor(registry, "remote_tool"),
        )
        advanceUntilIdle()

        val message = store.getSession("s1")!!.messages.last { it.role == MessageRole.TOOL }
        assertThat(message.content).contains("工具执行失败")
        assertThat(message.content).doesNotContain("secret-token")
        assertThat(message.content).doesNotContain("Authorization")
        assertThat(message.images).isNull()
        val failedStep = store.getSession("s1")!!.messages.first { it.id == "m1" }
            .executionSteps!!.last { it.toolCallId == "failed" }
        assertThat(failedStep.data).isNull()
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "failed")))
            .isEqualTo(ToolLedgerState.FAILED)
    }

    @Test
    fun failedCommandResultKeepsExitCodeAndStderrForModelAndStep() = testScope.runTest {
        seedSessionWithAssistant()
        val content = com.promenar.infinity.data.termux.TermuxCommandResult(
            stdout = "progress\n".repeat(3_000),
            stderr = "error: pathspec 'x' did not match",
            exitCode = 1,
        ).toJson().toString()
        val skill = testSkill("termux_exec") { ToolResult("r1", content, status = "error") }
        val registry = object : SkillRegistry {
            override fun getSkill(name: String) = skill
            override fun getAllSkills() = listOf<SkillDefinition>(skill)
            override fun getAllTools(allowedIds: List<String>?) = emptyList<ProtocolTool>()
        }
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = RecordingLedger())

        executor.executeTools("s1", "m1", listOf(ToolCall("cmd", "termux_exec", "{}")), preparedTools = preparedFor(registry, "termux_exec"))
        advanceUntilIdle()

        val message = store.getSession("s1")!!.messages.last { it.role == MessageRole.TOOL }
        assertThat(message.content).startsWith("工具执行失败：")
        assertThat(message.content).contains("\"exit_code\":1")
        assertThat(message.content).contains("error: pathspec 'x' did not match")
        assertThat(message.content.length).isLessThan(ToolFailureSummary.MAX_CHARS + 20)
        val step = store.getSession("s1")!!.messages.first { it.id == "m1" }.executionSteps!!.last { it.toolCallId == "cmd" }
        assertThat(step.content).isEqualTo(message.content)
    }

    @Test
    fun successfulUnstructuredResultDataIsDiscardedBeforePersistence() = testScope.runTest {
        seedSessionWithAssistant()
        val ledger = RecordingLedger()
        val registry = object : SkillRegistry {
            override fun getSkill(name: String) = object : SkillDefinition {
                override val id = name
                override val name = name
                override val description = "返回敏感data"
                override val mcpServerId: String? = null
                override val parametersSchema = "{}"
                override suspend fun execute(args: JsonObject, context: SkillExecutionContext) =
                    ToolResult(
                        id = "secret-data",
                        content = "done",
                        status = "success",
                        data = "Authorization: Bearer secret-token\n/private/path\nraw-response-body",
                    )
            }
            override fun getAllSkills() = emptyList<SkillDefinition>()
            override fun getAllTools(allowedIds: List<String>?) = emptyList<ProtocolTool>()
        }
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = ledger)

        executor.executeTools(
            "s1",
            "m1",
            listOf(ToolCall("secret-data", "remote_tool", "{}")),
            preparedTools = preparedFor(registry, "remote_tool"),
        )
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        val toolMessage = session.messages.last { it.role == MessageRole.TOOL }
        assertThat(toolMessage.images).isNull()
        assertThat(session.messages.first { it.id == "m1" }.executionSteps!!.last().data).isNull()
        assertThat(session.messages.joinToString { it.images.orEmpty() }).doesNotContain("secret-token")
    }

    @Test
    fun fakePngContainingAuthorizationMarkerIsDiscarded() = testScope.runTest {
        seedSessionWithAssistant()
        val ledger = RecordingLedger()
        val payload = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        ) + "Authorization: Bearer secret-token".toByteArray()
        val data = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(payload)
        val registry = resultDataRegistry(data)
        val executor = ToolExecutor(
            store,
            messageManager,
            registry,
            testToolResolver(registry),
            ledger = ledger,
        )

        executor.executeTools(
            "s1",
            "m1",
            listOf(ToolCall("fake-image", "remote_tool", "{}")),
            preparedTools = preparedFor(registry, "remote_tool"),
        )
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        assertThat(session.messages.last { it.role == MessageRole.TOOL }.images).isNull()
        assertThat(session.messages.first { it.id == "m1" }.executionSteps!!.last().data).isNull()
    }

    @Test
    fun citationSecretsAndLocalPathsAreRedactedAndUrlIsHttpsOnly() = testScope.runTest {
        seedSessionWithAssistant()
        val ledger = RecordingLedger()
        val data = """[{"title":"Authorization: Bearer secret-token /Users/alice/key","url":"https://user:pass@example.com/sk-abcdefghijklmnopqrstuvwxyz?q=api_key#frag","source":"C:\\\\private\\\\sk-secretvalue"}]"""
        val registry = resultDataRegistry(data)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = ledger)

        executor.executeTools(
            "s1",
            "m1",
            listOf(ToolCall("citations", "remote_tool", "{}")),
            preparedTools = preparedFor(registry, "remote_tool"),
        )
        advanceUntilIdle()

        val safeData = store.getSession("s1")!!.messages.first { it.id == "m1" }
            .executionSteps!!.last().data!!
        assertThat(safeData).doesNotContain("secret-token")
        assertThat(safeData).doesNotContain("/Users/")
        assertThat(safeData).doesNotContain("user:pass")
        assertThat(safeData).doesNotContain("?q=")
        assertThat(safeData).doesNotContain("sk-abcdefghijklmnopqrstuvwxyz")
        assertThat(safeData).contains("https://example.com/REDACTED")
    }

    private fun resultDataRegistry(data: String) = object : SkillRegistry {
        override fun getSkill(name: String) = object : SkillDefinition {
            override val id = name
            override val name = name
            override val description = "data"
            override val mcpServerId: String? = null
            override val parametersSchema = "{}"
            override suspend fun execute(args: JsonObject, context: SkillExecutionContext) =
                ToolResult(id = name, content = "done", status = "success", data = data)
        }
        override fun getAllSkills() = emptyList<SkillDefinition>()
        override fun getAllTools(allowedIds: List<String>?) = emptyList<ProtocolTool>()
    }

    private fun countingRegistry(counts: MutableMap<String, Int>) = object : SkillRegistry {
        override fun getSkill(name: String) = object : SkillDefinition {
            override val id = name
            override val name = name
            override val description = "计数工具"
            override val mcpServerId: String? = null
            override val parametersSchema = "{}"
            override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
                synchronized(counts) { counts[contextualToolCallId(name)] = (counts[contextualToolCallId(name)] ?: 0) + 1 }
                return ToolResult(id = name, content = "ok", status = "success")
            }
        }
        override fun getAllSkills() = emptyList<SkillDefinition>()
        override fun getAllTools(allowedIds: List<String>?) = emptyList<ProtocolTool>()

        private fun contextualToolCallId(name: String) = when (name) {
            "read_file" -> "safe"
            "write_file" -> if (counts.containsKey("safe")) "risky" else "same"
            else -> name
        }
    }

    @Test
    fun `成功结果的文件改动摘要只复制到执行步骤而不进入模型内容`() = testScope.runTest {
        seedSessionWithAssistant()
        val change = FileChangeSummary(
            path = "/src/a.kt",
            change = FileChangeType.MODIFIED,
            added = 1,
            removed = 1,
            hunks = listOf(FileChangeHunk(1, 1, 1, 1, listOf("-old", "+new"))),
        )
        val oversized = FileChangeSummary(
            path = "/src/big.kt",
            change = FileChangeType.MODIFIED,
            added = 1,
            removed = 0,
            hunks = listOf(FileChangeHunk(1, 0, 1, 1, listOf("+" + "x".repeat(40 * 1024)))),
        )
        val success = testSkill("safe_tool") { ToolResult("ok", "saved", fileChanges = listOf(change, oversized)) }
        val failure = testSkill("fail_tool") { ToolResult("err", "failed", status = "error", fileChanges = listOf(change)) }
        val registry = skillsRegistry(success, failure)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = RecordingLedger())

        executor.executeTools(
            "s1",
            "m1",
            listOf(ToolCall("ok", "safe_tool", "{}"), ToolCall("bad", "fail_tool", "{}")),
            preparedTools = preparedFor(registry, "safe_tool", "fail_tool"),
        )
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        val steps = session.messages.single { it.id == "m1" }.executionSteps.orEmpty()
        val resultStep = steps.single { it.toolCallId == "ok" && it.type == "tool_result" }
        assertThat(resultStep.content).isEqualTo("saved")
        assertThat(resultStep.fileChanges!!.first()).isEqualTo(change)
        // 单文件 hunk 超过 32 KiB 时丢弃 hunk，计数保留。
        assertThat(resultStep.fileChanges!![1].hunks).isNull()
        assertThat(resultStep.fileChanges!![1].hunksOmitted).isEqualTo(FileChangeHunkOmission.SIZE_LIMIT)
        assertThat(resultStep.fileChanges!![1].added).isEqualTo(1)
        assertThat(steps.single { it.toolCallId == "bad" && it.type == "error" }.fileChanges).isNull()
        steps.filter { it.type == "tool_call" }.forEach { assertThat(it.fileChanges).isNull() }
        val toolMessages = session.messages.filter { it.role == MessageRole.TOOL }.associateBy { it.toolCallId }
        assertThat(toolMessages.getValue("ok").content).isEqualTo("saved")
        assertThat(toolMessages.values.joinToString { it.content }).doesNotContain("/src/a.kt")
    }

    @Test
    fun `同一批次多个工具调用的tool_call与tool_result步骤全部保存`() = testScope.runTest {
        seedSessionWithAssistant()
        val read = testSkill("file_read") { ToolResult("r", "read-ok") }
        val write = testSkill("file_write") { ToolResult("w", "write-ok") }
        val failure = testSkill("fail_tool") { ToolResult("f", "failed", status = "error") }
        val registry = skillsRegistry(read, write, failure)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = RecordingLedger())

        // 同一批次内连续执行，期间不推进虚拟时间，节流缓冲中的步骤尚未刷入 store。
        executor.executeTools(
            "s1",
            "m1",
            listOf(
                ToolCall("c1", "file_read", "{}"),
                ToolCall("c2", "file_write", "{}"),
                ToolCall("c3", "fail_tool", "{}"),
            ),
            preparedTools = preparedFor(registry, "file_read", "file_write", "fail_tool"),
        )
        advanceUntilIdle()

        val steps = store.getSession("s1")!!.messages.single { it.id == "m1" }.executionSteps.orEmpty()
        listOf("c1", "c2", "c3").forEach { id ->
            assertThat(steps.count { it.toolCallId == id && it.type == "tool_call" }).isEqualTo(1)
        }
        assertThat(steps.single { it.toolCallId == "c1" && it.type == "tool_result" }.content).isEqualTo("read-ok")
        assertThat(steps.single { it.toolCallId == "c2" && it.type == "tool_result" }.content).isEqualTo("write-ok")
        assertThat(steps.single { it.toolCallId == "c3" && it.type == "error" }.content).contains("failed")
        // 步骤顺序与调用顺序一致：每个调用先 tool_call 后结果。
        assertThat(steps.map { it.toolCallId to it.type }).containsExactly(
            "c1" to "tool_call", "c1" to "tool_result",
            "c2" to "tool_call", "c2" to "tool_result",
            "c3" to "tool_call", "c3" to "error",
        ).inOrder()
    }

    @Test
    fun `同一批次多个搜索调用的引用全部合并`() = testScope.runTest {
        seedSessionWithAssistant()
        fun citationsJson(vararg urls: String) = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(Citation.serializer()),
            urls.map { Citation(title = it.substringAfterLast('/'), url = it) },
        )
        val first = testSkill("safe_tool") { ToolResult("a", "ok", data = citationsJson("https://a.example.com/one")) }
        val second = testSkill("file_read") { ToolResult("b", "ok", data = citationsJson("https://b.example.com/two")) }
        // 第三个调用只返回与第一个重复的引用：合并须去重且不能丢失第二个调用的引用。
        val third = testSkill("file_write") { ToolResult("c", "ok", data = citationsJson("https://a.example.com/one")) }
        val registry = skillsRegistry(first, second, third)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = RecordingLedger())

        executor.executeTools(
            "s1",
            "m1",
            listOf(ToolCall("c1", "safe_tool", "{}"), ToolCall("c2", "file_read", "{}"), ToolCall("c3", "file_write", "{}")),
            preparedTools = preparedFor(registry, "safe_tool", "file_read", "file_write"),
        )
        advanceUntilIdle()

        val message = store.getSession("s1")!!.messages.single { it.id == "m1" }
        assertThat(message.citations!!.map { it.url })
            .containsExactly("https://a.example.com/one", "https://b.example.com/two").inOrder()
        assertThat(message.content).isEqualTo("I'll use a tool")
    }

    @Test
    fun `台账隔离时只读诊断通过且写工具与处置均不触碰台账`() = testScope.runTest {
        seedSessionWithAssistant()
        val manager = com.promenar.infinity.startup.StartupIssueManager()
        com.promenar.infinity.startup.StartupWriteBarrier.install(manager)
        try {
            manager.recover(com.promenar.infinity.startup.StartupStep.TOOL_LEDGER,
                com.promenar.infinity.startup.StartupIssueScope.subsystem(com.promenar.infinity.startup.StartupSubsystem.TOOL_LEDGER)) { error("台账冲突") }
            var reads = 0
            var writes = 0
            val read = testSkill("app_diagnostics") { reads++; ToolResult("diag", "安全诊断", "success") }
            val write = testSkill("write_file", risk = ToolRisk.FILE_WRITE) { writes++; ToolResult("write", "不应执行", "success") }
            val resolve = testSkill("resolve_startup_issue", risk = ToolRisk.SESSION_STATE) { writes++; ToolResult("resolve", "不应执行", "success") }
            val registry = com.promenar.infinity.startup.StartupGuardedSkillRegistry(skillsRegistry(read, write, resolve))
            val executor = ToolExecutor(store, messageManager, registry, SessionToolResolver { registry.getAllTools() },
                ledger = mockk<ToolExecutionLedger>(), unavailableLedgerMessage = { "只读诊断通道" })
            executor.executeTools("s1", "m1", listOf(ToolCall("read", "app_diagnostics", "{}"),
                ToolCall("write", "write_file", "{}"), ToolCall("resolve", "resolve_startup_issue", "{}")), preparedTools = registry.getAllTools())
            assertThat(reads).isEqualTo(1)
            assertThat(writes).isEqualTo(0)
            assertThat(store.getSession("s1")!!.messages.filter { it.role == MessageRole.TOOL }.map { it.content })
                .containsExactly("安全诊断", "只读诊断通道", "只读诊断通道")
        } finally { com.promenar.infinity.startup.StartupWriteBarrier.resetForTesting() }
    }

    private fun testSkill(
        name: String,
        description: String = "test",
        schema: String = """{"type":"object","properties":{}}""",
        risk: ToolRisk = ToolRisk.SAFE_READ,
        execute: suspend (JsonObject) -> ToolResult,
    ) = object : SkillDefinition {
        override val id = name
        override val name = name
        override val description = description
        override val mcpServerId: String? = null
        override val parametersSchema = schema
        override val risk = risk
        override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = execute(args)
    }

    private fun singleSkillRegistry(skill: SkillDefinition) = object : SkillRegistry {
        override fun getSkill(name: String): SkillDefinition? = skill.takeIf { it.name == name }
        override fun getAllSkills(): List<SkillDefinition> = listOf(skill)
        override fun getAllTools(allowedIds: List<String>?): List<ProtocolTool> = listOf(skill.toPreparedTool())
    }

    private fun skillsRegistry(vararg skills: SkillDefinition) = object : SkillRegistry {
        override fun getSkill(name: String): SkillDefinition? = skills.singleOrNull { it.name == name }
        override fun getAllSkills(): List<SkillDefinition> = skills.toList()
        override fun getAllTools(allowedIds: List<String>?): List<ProtocolTool> = skills.map { it.toPreparedTool() }
    }

    private fun testToolResolver(registry: SkillRegistry?) = SessionToolResolver { session ->
        if (!session.options.toolsEnabled) return@SessionToolResolver emptyList()
        val candidateNames = setOf(
            "cancel_tool",
            "fail_tool",
            "file_read",
            "file_write",
            "nested_tool",
            "read_file",
            "remote_tool",
            "replaceable",
            "safe_tool",
            "unknown_tool",
            "validated_tool",
            "write_file",
        )
        (registry?.getAllTools(null).orEmpty() + candidateNames.mapNotNull { name ->
            registry?.getSkill(name)?.toPreparedTool()
        }).distinctBy { it.runtimeToolId }.filter { tool ->
            tool.sourceId != "custom" &&
                (tool.sourceId != "mcp" || tool.mcpServerId in session.activeMcpServerIds)
        }
    }

    private fun preparedFor(registry: SkillRegistry, vararg names: String): List<ProtocolTool> =
        names.map { name -> requireNotNull(registry.getSkill(name)).toPreparedTool() }

    private fun SkillDefinition.toPreparedTool() = ProtocolTool(
        function = ProtocolToolFunction(name, description, parametersSchema),
        risk = risk,
        runtimeToolId = runtimeToolId,
        sourceId = sourceId,
        mcpServerId = mcpServerId,
    )
}
