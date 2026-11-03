package com.promenar.infinity.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.repository.IMessageRepository
import com.promenar.infinity.data.repository.MessagePersistenceException
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.ui.chat.ChatStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageManagerTest {
    @Test fun `消息计费更新保留独立的上下文用量基线`() = testScope.runTest {
        val baseline = PromptUsageBaseline("usage-session", "model-key", "summary-hash", "tool-hash", 500, emptyList())
        store.upsertSession(Session("usage-session", "agent", messages = listOf(Message("assistant", MessageRole.ASSISTANT, "")),
            stats = SessionStats(contextUsageBaseline = baseline)))
        messageManager.updateMessageContent("usage-session", "assistant", "回复", UpdateMessageOptions(tokens = TokenUsage(10, 5, 15)))
        advanceUntilIdle()
        assertThat(store.getSession("usage-session")!!.stats!!.contextUsageBaseline).isEqualTo(baseline)
    }

    @Test
    fun `删除用户消息覆盖同轮旧数据中缺少父ID的助手与工具消息`() {
        val messages = listOf(
            Message(id = "u1", role = MessageRole.USER, content = "第一问"),
            Message(id = "a1", role = MessageRole.ASSISTANT, content = "", isError = true),
            Message(id = "t1", role = MessageRole.TOOL, content = "旧工具结果"),
            Message(id = "u2", role = MessageRole.USER, content = "第二问"),
            Message(id = "a2", role = MessageRole.ASSISTANT, content = "第二答"),
        )

        assertThat(messageRemovalIds(messages, "u1"))
            .containsExactly("u1", "a1", "t1")
            .inOrder()
    }

    @Test
    fun `删除助手消息仍只级联其显式子消息`() {
        val messages = listOf(
            Message(id = "u1", role = MessageRole.USER, content = "第一问"),
            Message(id = "a1", role = MessageRole.ASSISTANT, content = "第一答", parentMessageId = "u1"),
            Message(id = "t1", role = MessageRole.TOOL, content = "结果", parentMessageId = "a1"),
            Message(id = "a2", role = MessageRole.ASSISTANT, content = "后续", parentMessageId = "u1"),
        )

        assertThat(messageRemovalIds(messages, "a1"))
            .containsExactly("a1", "t1")
            .inOrder()
    }
    private lateinit var store: ChatStore
    private lateinit var messageManager: MessageManager
    private val testScope = TestScope()
    private lateinit var sessionManager: SessionManager
    private val partialUpdates = mutableListOf<Map<String, Any?>>()
    private val sessionPartialUpdates = mutableListOf<Pair<String, Map<String, Any?>>>()
    private var persistedMessage: Message? = null
    private var persistedSessionId: String? = null
    private var failScopedDelete = false
    private var failPartialUpdate = false
    private var insertFailure: Throwable? = null
    private var failMissingRow = false

    private val stubSessionRepo = object : ISessionRepository {
        override suspend fun create(session: Session) {}
        override suspend fun updatePartial(id: String, updates: Map<String, Any?>) {
            sessionPartialUpdates += id to updates
        }
        override suspend fun delete(id: String) {}
        override suspend fun getById(id: String): Session? = null
        override suspend fun getAll(): List<Session> = emptyList()
    }

    private val stubMessageRepo = object : IMessageRepository {
        override suspend fun insert(message: Message, sessionId: String) {
            insertFailure?.let { throw it }
            persistedMessage = message
            persistedSessionId = sessionId
        }
        override suspend fun updatePartial(messageId: String, updates: Map<String, Any?>) {
            if (failMissingRow) throw MessagePersistenceException("missing row: $messageId")
            if (failPartialUpdate) {
                failPartialUpdate = false
                throw IllegalStateException("db update failed")
            }
            partialUpdates += updates
            persistedMessage = persistedMessage?.let { current ->
                current.copy(
                    content = updates["content"] as? String ?: current.content,
                    status = if ("status" in updates) updates["status"] as String? else current.status,
                    isError = if ("isError" in updates) updates["isError"] as Boolean else current.isError,
                    errorMessage = if ("errorMessage" in updates) updates["errorMessage"] as String? else current.errorMessage,
                    tokens = if ("tokens" in updates) updates["tokens"] as TokenUsage? else current.tokens,
                    kgPaths = if ("kgPaths" in updates) {
                        @Suppress("UNCHECKED_CAST")
                        updates["kgPaths"] as List<KgPath>?
                    } else current.kgPaths,
                    pendingApprovalToolIds = if ("pendingApprovalToolIds" in updates) {
                        @Suppress("UNCHECKED_CAST")
                        updates["pendingApprovalToolIds"] as List<String>?
                    } else current.pendingApprovalToolIds,
                )
            }
        }
        override suspend fun delete(messageId: String) {
            if (persistedMessage?.id == messageId) persistedMessage = null
        }
        override suspend fun deleteInSession(sessionId: String, messageId: String): Boolean {
            if (failScopedDelete) throw IllegalStateException("db delete failed")
            if (persistedSessionId != sessionId || persistedMessage?.id != messageId) return false
            persistedMessage = null
            return true
        }
        override suspend fun deleteBySessionId(sessionId: String) {
            persistedMessage = null
        }
        override suspend fun deleteMessagesAfter(sessionId: String, timestamp: Long) {
            if ((persistedMessage?.createdAt ?: Long.MIN_VALUE) >= timestamp) persistedMessage = null
        }
        override suspend fun getById(messageId: String): Message? = null
        override suspend fun getBySession(sessionId: String): List<Message> = emptyList()
        override suspend fun updateVectorizationStatus(messageId: String, status: String, isArchived: Boolean?) {}
    }

    @Before
    fun setUp() {
        partialUpdates.clear()
        sessionPartialUpdates.clear()
        persistedMessage = null
        persistedSessionId = null
        failScopedDelete = false
        failPartialUpdate = false
        insertFailure = null
        failMissingRow = false
        store = ChatStore()
        messageManager = MessageManager(store, stubMessageRepo, stubSessionRepo, testScope)
        sessionManager = SessionManager(store, stubSessionRepo)
    }

    private suspend fun seedSession(id: String = "s1"): Session {
        val session = Session(id = id, agentId = "a1", title = "Test")
        sessionManager.addSession(session)
        testScope.advanceUntilIdle()
        return session
    }

    @Test
    fun addMessage() = testScope.runTest {
        seedSession()
        val msg = Message(id = "m1", role = MessageRole.USER, content = "hello", createdAt = 1000L)
        messageManager.addMessage("s1", msg)
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        assertThat(session.messages).hasSize(1)
        assertThat(session.messages[0].content).isEqualTo("hello")
    }

    @Test
    fun `新增消息把会话最近活动时间推到消息时间并写库`() = testScope.runTest {
        sessionManager.addSession(Session(id = "s1", agentId = "a1", title = "Test", updatedAt = 100L))
        messageManager.addMessage("s1", Message(id = "m1", role = MessageRole.USER, content = "hello", createdAt = 5_000L))
        advanceUntilIdle()

        assertThat(store.getSession("s1")!!.updatedAt).isEqualTo(5_000L)
        assertThat(sessionPartialUpdates).contains("s1" to mapOf<String, Any?>("lastMessage" to "hello"))
    }

    @Test
    fun `镜像已持久化的消息同样推进最近活动时间且不倒退`() = testScope.runTest {
        sessionManager.addSession(Session(id = "s1", agentId = "a1", title = "Test", updatedAt = 100L))
        messageManager.mirrorPersistedMessage("s1", Message(id = "t1", role = MessageRole.TOOL, content = "ok", createdAt = 7_000L))
        assertThat(store.getSession("s1")!!.updatedAt).isEqualTo(7_000L)

        messageManager.mirrorPersistedMessage("s1", Message(id = "t0", role = MessageRole.TOOL, content = "late", createdAt = 6_000L))
        assertThat(store.getSession("s1")!!.updatedAt).isEqualTo(7_000L)
    }

    @Test
    fun `addMessage插入失败不写Store且传播异常`() = testScope.runTest {
        seedSession()
        insertFailure = IllegalStateException("insert failed")

        val failure = runCatching { messageManager.addMessage("s1", assistantMessage()) }.exceptionOrNull()

        assertThat(failure?.message).isEqualTo("insert failed")
        assertThat(store.getSession("s1")!!.messages).isEmpty()
    }

    @Test
    fun `addMessage取消不写Store且原样传播Cancellation`() = testScope.runTest {
        seedSession()
        val cancelled = CancellationException("cancel insert")
        insertFailure = cancelled

        val failure = runCatching { messageManager.addMessage("s1", assistantMessage()) }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(cancelled)
        assertThat(store.getSession("s1")!!.messages).isEmpty()
    }

    @Test
    fun `准备补偿覆盖DB与Store三种存在组合且只按session删除`() = testScope.runTest {
        seedSession()
        val message = assistantMessage()

        store.update { state ->
            state.copy(sessions = state.sessions.map { it.copy(messages = listOf(message)) })
        }
        messageManager.discardPreparedMessage("s1", message.id)
        assertThat(store.getSession("s1")!!.messages).isEmpty()

        persistedMessage = message
        persistedSessionId = "s1"
        messageManager.discardPreparedMessage("s1", message.id)
        assertThat(persistedMessage).isNull()

        messageManager.addMessage("s1", message)
        messageManager.discardPreparedMessage("s1", message.id)
        assertThat(store.getSession("s1")!!.messages).isEmpty()
        assertThat(persistedMessage).isNull()
    }

    @Test
    fun deleteMessage() = testScope.runTest {
        seedSession()
        val msg = Message(id = "m1", role = MessageRole.USER, content = "hello", createdAt = 1000L)
        messageManager.addMessage("s1", msg)
        advanceUntilIdle()

        messageManager.deleteMessage("s1", "m1")
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        assertThat(session.messages).isEmpty()
    }

    @Test
    fun deleteAssistantRemovesAllToolDescendantsFromStoreImmediately() = testScope.runTest {
        seedSession()
        val assistant = Message(id = "a1", role = MessageRole.ASSISTANT, content = "tools")
        val firstTool = Message(
            id = "t1",
            role = MessageRole.TOOL,
            content = "one",
            parentMessageId = assistant.id,
        )
        val nestedTool = Message(
            id = "t2",
            role = MessageRole.TOOL,
            content = "two",
            parentMessageId = firstTool.id,
        )
        val unrelated = Message(id = "a2", role = MessageRole.ASSISTANT, content = "keep")
        listOf(assistant, firstTool, nestedTool, unrelated).forEach {
            messageManager.addMessage("s1", it)
        }
        persistedMessage = assistant
        persistedSessionId = "s1"

        messageManager.deleteMessage("s1", assistant.id)
        advanceUntilIdle()

        assertThat(store.getSession("s1")!!.messages.map { it.id }).containsExactly("a2")
    }

    @Test
    fun scopedDeleteWithWrongSessionKeepsStoreUntouched() = testScope.runTest {
        seedSession("s1")
        seedSession("s2")
        val message = Message(id = "m1", role = MessageRole.ASSISTANT, content = "keep")
        messageManager.addMessage("s1", message)

        val failure = runCatching { messageManager.deleteMessage("s2", message.id) }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(store.getSession("s1")!!.messages.map { it.id }).containsExactly(message.id)
    }

    @Test
    fun databaseDeleteFailureKeepsStoreAndPropagates() = testScope.runTest {
        seedSession("s1")
        val message = Message(id = "m1", role = MessageRole.ASSISTANT, content = "keep")
        messageManager.addMessage("s1", message)
        failScopedDelete = true

        val failure = runCatching { messageManager.deleteMessage("s1", message.id) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(store.getSession("s1")!!.messages.map { it.id }).containsExactly(message.id)
        assertThat(persistedMessage?.id).isEqualTo(message.id)
    }

    @Test
    fun deleteMessagesAfter() = testScope.runTest {
        seedSession()
        messageManager.addMessage("s1", Message(id = "m1", role = MessageRole.USER, content = "first", createdAt = 1000L))
        advanceUntilIdle()
        messageManager.addMessage("s1", Message(id = "m2", role = MessageRole.ASSISTANT, content = "second", createdAt = 2000L))
        advanceUntilIdle()
        messageManager.addMessage("s1", Message(id = "m3", role = MessageRole.USER, content = "third", createdAt = 3000L))
        advanceUntilIdle()

        messageManager.deleteMessagesAfter("s1", 2000L)
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        assertThat(session.messages).hasSize(1)
        assertThat(session.messages[0].id).isEqualTo("m1")
    }

    @Test
    fun updateMessageProgress() = testScope.runTest {
        seedSession()
        messageManager.addMessage("s1", Message(id = "m1", role = MessageRole.ASSISTANT, content = "hi", createdAt = 1000L))
        advanceUntilIdle()

        val progress = RagProgress(stage = "retrieving", percentage = 50)
        messageManager.updateMessageProgress("s1", "m1", progress)

        val session = store.getSession("s1")!!
        assertThat(session.messages[0].ragProgress!!.stage).isEqualTo("retrieving")
        assertThat(session.messages[0].ragProgress!!.percentage).isEqualTo(50)
    }

    @Test
    fun updateMessageLayout() = testScope.runTest {
        seedSession()
        messageManager.addMessage("s1", Message(id = "m1", role = MessageRole.ASSISTANT, content = "hi", createdAt = 1000L))
        advanceUntilIdle()

        messageManager.updateMessageLayout("s1", "m1", 200.0)

        val session = store.getSession("s1")!!
        assertThat(session.messages[0].layoutHeight).isEqualTo(200.0)
    }

    @Test
    fun updateMessageLayoutIgnoresSmallChanges() = testScope.runTest {
        seedSession()
        messageManager.addMessage("s1", Message(id = "m1", role = MessageRole.ASSISTANT, content = "hi", createdAt = 1000L, layoutHeight = 200.0))
        advanceUntilIdle()

        messageManager.updateMessageLayout("s1", "m1", 205.0)

        val session = store.getSession("s1")!!
        assertThat(session.messages[0].layoutHeight).isEqualTo(200.0)
    }

    @Test
    fun clearPendingApprovalCancelsQueuedUiAndDbUpdatesWithoutLosingOtherFields() = testScope.runTest {
        seedSession()
        val message = Message(
            id = "m1",
            role = MessageRole.ASSISTANT,
            content = "before",
            pendingApprovalToolIds = listOf("old"),
        )
        messageManager.addMessage("s1", message)
        messageManager.updateMessageContent(
            "s1",
            "m1",
            "after",
            UpdateMessageOptions(pendingApprovalToolIds = listOf("queued")),
        )

        messageManager.clearPendingApprovalState("s1", "m1")
        advanceTimeBy(600)
        advanceUntilIdle()

        val stored = store.getSession("s1")!!.messages.single()
        assertThat(stored.content).isEqualTo("after")
        assertThat(stored.pendingApprovalToolIds).isNull()
        assertThat(persistedMessage?.content).isEqualTo("after")
        assertThat(persistedMessage?.pendingApprovalToolIds).isNull()
    }

    @Test
    fun clearPendingApprovalWaitsForInflightUiFlushAndNullRemainsLast() = testScope.runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        messageManager = MessageManager(
            store,
            stubMessageRepo,
            stubSessionRepo,
            testScope,
            MessageManagerHooks(beforeUiApply = { entered.complete(Unit); release.await() }),
        )
        seedSession()
        messageManager.addMessage(
            "s1",
            Message(id = "m1", role = MessageRole.ASSISTANT, content = "before"),
        )
        messageManager.updateMessageContent(
            "s1",
            "m1",
            "after-inflight",
            UpdateMessageOptions(pendingApprovalToolIds = listOf("inflight")),
        )
        advanceTimeBy(100)
        runCurrent()
        entered.await()

        val clearJob = launch { messageManager.clearPendingApprovalState("s1", "m1") }
        runCurrent()
        assertThat(clearJob.isCompleted).isFalse()
        release.complete(Unit)
        advanceTimeBy(500)
        advanceUntilIdle()

        assertThat(clearJob.isCompleted).isTrue()
        val stored = store.getSession("s1")!!.messages.single()
        assertThat(stored.content).isEqualTo("after-inflight")
        assertThat(stored.pendingApprovalToolIds).isNull()
        assertThat(persistedMessage?.pendingApprovalToolIds).isNull()
    }

    @Test
    fun mirrorPersistedApprovalSurvivesNonApprovalFlushFailureAndKeepsContent() = testScope.runTest {
        seedSession()
        messageManager.addMessage(
            "s1",
            Message(id = "m1", role = MessageRole.ASSISTANT, content = "before"),
        )
        messageManager.updateMessageContent(
            "s1",
            "m1",
            "after",
            UpdateMessageOptions(pendingApprovalToolIds = listOf("stale")),
        )
        failPartialUpdate = true

        messageManager.mirrorPersistedPendingApprovalState("s1", "m1", null)
        val retryState = messageManager.coordinationState()
        assertThat(retryState.pendingDb + retryState.dbJobs).isGreaterThan(0)
        advanceTimeBy(600)
        advanceUntilIdle()

        val mirrored = store.getSession("s1")!!.messages.single()
        assertThat(mirrored.content).isEqualTo("after")
        assertThat(mirrored.pendingApprovalToolIds).isNull()
        assertThat(persistedMessage?.content).isEqualTo("after")
    }

    @Test
    fun mirrorNeverWritesApprovalOwnedToolCallsOrPendingIds() = testScope.runTest {
        seedSession()
        messageManager.addMessage(
            "s1",
            Message(id = "m1", role = MessageRole.ASSISTANT, content = "before"),
        )
        messageManager.updateMessageContent(
            "s1",
            "m1",
            "after",
            UpdateMessageOptions(
                toolCalls = listOf(ToolCall("call-1", "write_file", "{}")),
                pendingApprovalToolIds = listOf("call-1"),
            ),
        )

        messageManager.mirrorPersistedPendingApprovalState("s1", "m1", listOf("call-1"))
        advanceUntilIdle()

        assertThat(partialUpdates).isNotEmpty()
        assertThat(partialUpdates.all { "toolCalls" !in it && "pendingApprovalToolIds" !in it }).isTrue()
        assertThat(persistedMessage?.content).isEqualTo("after")
    }

    @Test
    fun synchronousNonApprovalFlushPreservesCompleteRetrievalMetadata() = testScope.runTest {
        seedSession()
        messageManager.addMessage(
            "s1",
            Message(id = "m1", role = MessageRole.ASSISTANT, content = "before"),
        )
        val reference = RagReference(id = "chunk-1", content = "正文", source = "文档")
        val citation = Citation(title = "来源", url = "https://example.test/source")
        val path = kgPath()
        val metadata = RagMetadata(chunkCount = 1, totalTokens = 12, retrievalTimeMs = 5)
        messageManager.updateMessageContent(
            "s1",
            "m1",
            "after",
            UpdateMessageOptions(
                citations = listOf(citation),
                ragReferences = listOf(reference),
                kgPaths = listOf(path),
                ragReferencesLoading = false,
                ragMetadata = metadata,
            ),
        )

        messageManager.flushNonApprovalUpdatesNow("s1", "m1")

        val persisted = partialUpdates.last()
        assertThat(persisted["citations"]).isEqualTo(listOf(citation))
        assertThat(persisted["ragReferences"]).isEqualTo(listOf(reference))
        assertThat(persisted["kgPaths"]).isEqualTo(listOf(path))
        assertThat(persisted["ragReferencesLoading"]).isEqualTo(false)
        assertThat(persisted["ragMetadata"]).isEqualTo(metadata)
    }

    @Test
    fun synchronousNonApprovalFlushFailureThrowsAndKeepsRetryVisibleUntilPersisted() =
        testScope.runTest {
            seedSession("session:with-colon")
            messageManager.addMessage(
                "session:with-colon",
                Message(id = "m1", role = MessageRole.ASSISTANT, content = "before"),
            )
            messageManager.updateMessageContent(
                "session:with-colon",
                "m1",
                "after",
                UpdateMessageOptions(
                    toolCalls = listOf(ToolCall("call-1", "read_file", "{}")),
                    pendingApprovalToolIds = listOf("call-1"),
                ),
            )
            failPartialUpdate = true

            val failure = runCatching {
                messageManager.flushNonApprovalUpdatesNow("session:with-colon", "m1")
            }.exceptionOrNull()

            assertThat(failure).isNotNull()
            val retryState = messageManager.coordinationState()
            assertThat(retryState.pendingDb + retryState.dbJobs).isGreaterThan(0)
            advanceTimeBy(600)
            advanceUntilIdle()
            assertThat(persistedMessage?.content).isEqualTo("after")
            assertThat(partialUpdates.all { "toolCalls" !in it && "pendingApprovalToolIds" !in it }).isTrue()
            assertThat(messageManager.coordinationState()).isEqualTo(MessageCoordinationState())
        }

    @Test
    fun successfulRetryDoesNotRemoveLaneThatReceivedConcurrentUpdate() = testScope.runTest {
        val retryEntered = CompletableDeferred<Unit>()
        val releaseRetry = CompletableDeferred<Unit>()
        var dbWriteAttempt = 0
        messageManager = MessageManager(
            store,
            stubMessageRepo,
            stubSessionRepo,
            testScope,
            MessageManagerHooks(beforeDbWrite = {
                dbWriteAttempt += 1
                if (dbWriteAttempt == 2) {
                    retryEntered.complete(Unit)
                    releaseRetry.await()
                }
            }),
        )
        seedSession()
        messageManager.addMessage(
            "s1",
            Message(id = "m1", role = MessageRole.ASSISTANT, content = "before"),
        )
        messageManager.updateMessageContent("s1", "m1", "first")
        failPartialUpdate = true
        messageManager.mirrorPersistedPendingApprovalState("s1", "m1", null)

        advanceTimeBy(500)
        runCurrent()
        retryEntered.await()
        messageManager.updateMessageContent("s1", "m1", "concurrent")
        releaseRetry.complete(Unit)
        runCurrent()

        val concurrentState = messageManager.coordinationState()
        assertThat(concurrentState.lanes).isEqualTo(1)
        assertThat(concurrentState.pendingUi + concurrentState.uiJobs).isGreaterThan(0)

        advanceUntilIdle()
        assertThat(persistedMessage?.content).isEqualTo("concurrent")
        assertThat(messageManager.coordinationState()).isEqualTo(MessageCoordinationState())
    }

    @Test
    fun setVectorizationStatus() = testScope.runTest {
        seedSession()
        messageManager.addMessage("s1", Message(id = "m1", role = MessageRole.USER, content = "hello", createdAt = 1000L))
        advanceUntilIdle()
        messageManager.addMessage("s1", Message(id = "m2", role = MessageRole.ASSISTANT, content = "world", createdAt = 2000L))
        advanceUntilIdle()

        messageManager.setVectorizationStatus("s1", listOf("m1", "m2"), "processing")
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        assertThat(session.messages[0].vectorizationStatus).isEqualTo("processing")
        assertThat(session.messages[1].vectorizationStatus).isEqualTo("processing")

        messageManager.setVectorizationStatus("s1", listOf("m1", "m2"), "success")
        advanceUntilIdle()

        val session2 = store.getSession("s1")!!
        assertThat(session2.messages[0].isArchived).isFalse()
        assertThat(session2.messages[1].isArchived).isFalse()
    }

    @Test
    fun `updateFromLatest在节流窗口内基于未刷新步骤追加而不覆盖`() = testScope.runTest {
        seedSession()
        messageManager.addMessage("s1", Message(id = "m1", role = MessageRole.ASSISTANT, content = "答复"))
        advanceUntilIdle()
        val append = { id: String ->
            messageManager.updateFromLatest("s1", "m1") { latest ->
                UpdateMessageOptions(executionSteps = latest.executionSteps.orEmpty() + ExecutionStep(id = id, type = "tool_call"))
            }
        }

        append("a")
        append("b")
        // 两次追加都在同一节流窗口内，store 尚未看到任何步骤。
        assertThat(store.getSession("s1")!!.messages.single().executionSteps).isNull()
        advanceUntilIdle()

        val stored = store.getSession("s1")!!.messages.single()
        assertThat(stored.executionSteps!!.map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(stored.content).isEqualTo("答复")
    }

    @Test
    fun `updateFromLatest读取正在刷入的缓冲并沿用其最新正文`() = testScope.runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        messageManager = MessageManager(
            store,
            stubMessageRepo,
            stubSessionRepo,
            testScope,
            MessageManagerHooks(beforeUiApply = { entered.complete(Unit); release.await() }),
        )
        seedSession()
        messageManager.addMessage("s1", Message(id = "m1", role = MessageRole.ASSISTANT, content = "旧正文"))
        messageManager.updateMessageContent(
            "s1",
            "m1",
            "新正文",
            UpdateMessageOptions(executionSteps = listOf(ExecutionStep(id = "inflight", type = "tool_call"))),
        )
        advanceTimeBy(100)
        runCurrent()
        entered.await()

        // 缓冲已转入刷新流程但尚未写入 store。
        messageManager.updateFromLatest("s1", "m1") { latest ->
            UpdateMessageOptions(executionSteps = latest.executionSteps.orEmpty() + ExecutionStep(id = "next", type = "tool_result"))
        }
        release.complete(Unit)
        advanceUntilIdle()

        val stored = store.getSession("s1")!!.messages.single()
        assertThat(stored.executionSteps!!.map { it.id }).containsExactly("inflight", "next").inOrder()
        assertThat(stored.content).isEqualTo("新正文")
    }

    @Test
    fun updateMessageContentBuffersAndFlushes() = testScope.runTest {
        seedSession()
        messageManager.addMessage("s1", Message(id = "m1", role = MessageRole.ASSISTANT, content = "", createdAt = 1000L))
        advanceUntilIdle()

        messageManager.updateMessageContent("s1", "m1", "hello world")
        assertThat(messageManager.hasPendingUpdates("s1", "m1")).isTrue()

        advanceUntilIdle()

        val session = store.getSession("s1")!!
        assertThat(session.messages[0].content).isEqualTo("hello world")
    }

    @Test
    fun updateMessageContentWithTokens() = testScope.runTest {
        seedSession()
        messageManager.addMessage("s1", Message(id = "m1", role = MessageRole.ASSISTANT, content = "", createdAt = 1000L))
        advanceUntilIdle()

        val tokens = TokenUsage(input = 10, output = 20, total = 30)
        messageManager.updateMessageContent("s1", "m1", "response", UpdateMessageOptions(tokens = tokens))
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        assertThat(session.messages[0].tokens).isEqualTo(tokens)
        assertThat(session.messages[0].content).isEqualTo("response")
    }

    @Test
    fun updateMessageContentWithReasoning() = testScope.runTest {
        seedSession()
        messageManager.addMessage("s1", Message(id = "m1", role = MessageRole.ASSISTANT, content = "", createdAt = 1000L))
        advanceUntilIdle()

        messageManager.updateMessageContent("s1", "m1", "result", UpdateMessageOptions(reasoning = "thinking..."))
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        assertThat(session.messages[0].reasoning).isEqualTo("thinking...")
    }

    @Test
    fun kgPathsSurviveLaterStreamingUpdateAndFlushOnce() = testScope.runTest {
        seedSession()
        messageManager.addMessage(
            "s1",
            Message(id = "m1", role = MessageRole.ASSISTANT, content = "", createdAt = 1000L),
        )
        advanceUntilIdle()
        val path = KgPath(
            queryKeywords = listOf("Infinity Workbench"),
            nodes = listOf(KgNode("n1", "Infinity Workbench", "project")),
            edges = emptyList(),
        )

        messageManager.updateMessageContent("s1", "m1", "", UpdateMessageOptions(kgPaths = listOf(path)))
        messageManager.updateMessageContent("s1", "m1", "streamed answer")
        advanceUntilIdle()

        val message = store.getSession("s1")!!.messages.single()
        assertThat(message.content).isEqualTo("streamed answer")
        assertThat(message.kgPaths).containsExactly(path)
        assertThat(partialUpdates.count { "kgPaths" in it }).isEqualTo(1)
        assertThat(partialUpdates.single { "kgPaths" in it }["kgPaths"]).isEqualTo(listOf(path))
    }

    @Test
    fun clearMessageRagStateExplicitlyClearsKgPathsInStoreAndRepository() = testScope.runTest {
        seedSession()
        val path = KgPath(
            queryKeywords = listOf("Infinity Workbench"),
            nodes = listOf(KgNode("n1", "Infinity Workbench", "project")),
            edges = emptyList(),
        )
        messageManager.addMessage(
            "s1",
            Message(
                id = "m1",
                role = MessageRole.ASSISTANT,
                content = "answer",
                kgPaths = listOf(path),
                createdAt = 1000L,
            ),
        )
        advanceUntilIdle()

        messageManager.clearMessageRagState("s1", "m1")
        advanceUntilIdle()

        assertThat(store.getSession("s1")!!.messages.single().kgPaths).isNull()
        assertThat(partialUpdates.single { "kgPaths" in it }["kgPaths"]).isNull()
    }

    @Test
    fun clearBeforeUiThrottlePreservesPendingContentAndTokensButInvalidatesKgPaths() = testScope.runTest {
        seedSession()
        val path = kgPath()
        val tokens = TokenUsage(input = 5, output = 7, total = 12)
        messageManager.addMessage("s1", assistantMessage())

        messageManager.updateMessageContent(
            "s1", "m1", "streamed",
            UpdateMessageOptions(tokens = tokens, kgPaths = listOf(path)),
        )
        messageManager.clearMessageRagState("s1", "m1")
        advanceUntilIdle()

        assertClearedWithContent("streamed", tokens)
    }

    @Test
    fun clearWhileUiFlushIsExtractedStillWinsWithoutLosingContent() = testScope.runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        messageManager = MessageManager(
            store, stubMessageRepo, stubSessionRepo, testScope,
            MessageManagerHooks(beforeUiApply = { entered.complete(Unit); release.await() }),
        )
        seedSession()
        messageManager.addMessage("s1", assistantMessage())
        messageManager.updateMessageContent("s1", "m1", "streamed", UpdateMessageOptions(kgPaths = listOf(kgPath())))

        advanceTimeBy(100)
        runCurrent()
        entered.await()
        messageManager.clearMessageRagState("s1", "m1")
        release.complete(Unit)
        advanceUntilIdle()

        assertClearedWithContent("streamed", null)
    }

    @Test
    fun clearBeforeDbDebounceCancelsStaleKgWriteAndPreservesFlushedContent() = testScope.runTest {
        seedSession()
        messageManager.addMessage("s1", assistantMessage())
        messageManager.updateMessageContent("s1", "m1", "streamed", UpdateMessageOptions(kgPaths = listOf(kgPath())))
        advanceTimeBy(100)
        runCurrent()

        messageManager.clearMessageRagState("s1", "m1")
        advanceUntilIdle()

        assertClearedWithContent("streamed", null)
    }

    @Test
    fun clearWhileDbUpdateIsInFlightSerializesNullAsTheLastWrite() = testScope.runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var firstWrite = true
        messageManager = MessageManager(
            store, stubMessageRepo, stubSessionRepo, testScope,
            MessageManagerHooks(beforeDbWrite = {
                if (firstWrite) {
                    firstWrite = false
                    entered.complete(Unit)
                    release.await()
                }
            }),
        )
        seedSession()
        messageManager.addMessage("s1", assistantMessage())
        messageManager.updateMessageContent("s1", "m1", "streamed", UpdateMessageOptions(kgPaths = listOf(kgPath())))
        advanceTimeBy(600)
        runCurrent()
        entered.await()

        messageManager.clearMessageRagState("s1", "m1")
        release.complete(Unit)
        advanceUntilIdle()

        assertClearedWithContent("streamed", null)
        assertThat(partialUpdates.last()["kgPaths"]).isNull()
    }

    @Test
    fun deleteMessageWaitsForExtractedUiFlushAndMessageNeverRevives() = testScope.runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        messageManager = MessageManager(
            store, stubMessageRepo, stubSessionRepo, testScope,
            MessageManagerHooks(beforeUiApply = { entered.complete(Unit); release.await() }),
        )
        seedSession()
        messageManager.addMessage("s1", assistantMessage())
        messageManager.updateMessageContent("s1", "m1", "late", UpdateMessageOptions(kgPaths = listOf(kgPath())))
        advanceTimeBy(100)
        runCurrent()
        entered.await()

        val deleteJob = launch { messageManager.deleteMessage("s1", "m1") }
        runCurrent()
        release.complete(Unit)
        deleteJob.join()
        advanceUntilIdle()

        assertThat(store.getSession("s1")!!.messages).isEmpty()
        assertThat(persistedMessage).isNull()
    }

    @Test
    fun deleteMessagesAfterWaitsForInFlightDbWriteAndDeleteIsLast() = testScope.runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var firstWrite = true
        messageManager = MessageManager(
            store, stubMessageRepo, stubSessionRepo, testScope,
            MessageManagerHooks(beforeDbWrite = {
                if (firstWrite) {
                    firstWrite = false
                    entered.complete(Unit)
                    release.await()
                }
            }),
        )
        seedSession()
        messageManager.addMessage("s1", assistantMessage())
        messageManager.updateMessageContent("s1", "m1", "late", UpdateMessageOptions(kgPaths = listOf(kgPath())))
        advanceTimeBy(600)
        runCurrent()
        entered.await()

        val deleteJob = launch { messageManager.deleteMessagesAfter("s1", 1000L) }
        runCurrent()
        release.complete(Unit)
        deleteJob.join()
        advanceUntilIdle()

        assertThat(store.getSession("s1")!!.messages).isEmpty()
        assertThat(persistedMessage).isNull()
    }

    @Test
    fun clearMessagesPurgesEveryCoordinationLaneAndPendingJob() = testScope.runTest {
        seedSession()
        messageManager.addMessage("s1", assistantMessage())
        messageManager.updateMessageContent("s1", "m1", "pending", UpdateMessageOptions(kgPaths = listOf(kgPath())))

        messageManager.clearMessages("s1")
        advanceUntilIdle()

        assertThat(store.getSession("s1")!!.messages).isEmpty()
        assertThat(messageManager.coordinationState()).isEqualTo(MessageCoordinationState())
    }

    private fun assistantMessage(): Message =
        Message(id = "m1", role = MessageRole.ASSISTANT, content = "", createdAt = 1000L)

    private fun kgPath(): KgPath = KgPath(
        queryKeywords = listOf("Infinity Workbench"),
        nodes = listOf(KgNode("n1", "Infinity Workbench", "project")),
        edges = emptyList(),
    )

    private fun assertClearedWithContent(expectedContent: String, expectedTokens: TokenUsage?) {
        val stored = store.getSession("s1")!!.messages.single()
        assertThat(stored.content).isEqualTo(expectedContent)
        assertThat(stored.tokens).isEqualTo(expectedTokens ?: TokenUsage())
        assertThat(stored.kgPaths).isNull()
        assertThat(persistedMessage?.content).isEqualTo(expectedContent)
        assertThat(persistedMessage?.tokens).isEqualTo(expectedTokens)
        assertThat(persistedMessage?.kgPaths).isNull()
    }

    @Test
    fun flushMessageUpdates() = testScope.runTest {
        seedSession()
        messageManager.addMessage("s1", Message(id = "m1", role = MessageRole.ASSISTANT, content = "", createdAt = 1000L))
        advanceUntilIdle()

        messageManager.updateMessageContent("s1", "m1", "buffered content")
        assertThat(messageManager.hasPendingUpdates("s1", "m1")).isTrue()

        messageManager.flushMessageUpdates("s1", "m1")

        val session = store.getSession("s1")!!
        assertThat(session.messages[0].content).isEqualTo("buffered content")
        assertThat(messageManager.hasPendingUpdates("s1", "m1")).isFalse()
    }

    @Test
    fun `生成终态同步写入Store与Repository且成功会清除旧错误`() = testScope.runTest {
        seedSession()
        messageManager.addMessage(
            "s1",
            Message(
                id = "m1",
                role = MessageRole.ASSISTANT,
                content = "partial",
                status = "streaming",
                isError = true,
                errorMessage = "old",
            ),
        )
        advanceUntilIdle()

        messageManager.markGenerationTerminal("s1", "m1", "final", "success")

        val stored = store.getSession("s1")!!.messages.single()
        assertThat(stored.content).isEqualTo("final")
        assertThat(stored.status).isEqualTo("success")
        assertThat(stored.isError).isFalse()
        assertThat(stored.errorMessage).isNull()
        assertThat(persistedMessage).isEqualTo(stored)
        assertThat(partialUpdates.last()["status"]).isEqualTo("success")
        assertThat(partialUpdates.last()["isError"]).isEqualTo(false)
        assertThat(partialUpdates.last()).containsKey("errorMessage")
        assertThat(partialUpdates.last()["errorMessage"]).isNull()
    }

    @Test
    fun `recoverable错误后的旧db debounce不得覆盖success终态`() = testScope.runTest {
        seedSession()
        messageManager.addMessage("s1", assistantMessage())
        advanceUntilIdle()
        messageManager.updateMessageContent(
            "s1",
            "m1",
            "partial",
            UpdateMessageOptions(isError = true, errorMessage = "recoverable"),
        )
        advanceTimeBy(100)
        runCurrent()

        messageManager.markGenerationTerminal("s1", "m1", "final", "success")
        advanceUntilIdle()

        val stored = store.getSession("s1")!!.messages.single()
        assertThat(stored.status).isEqualTo("success")
        assertThat(stored.isError).isFalse()
        assertThat(stored.errorMessage).isNull()
        assertThat(persistedMessage?.status).isEqualTo("success")
        assertThat(persistedMessage?.isError).isFalse()
        assertThat(persistedMessage?.errorMessage).isNull()
    }

    @Test
    fun `CONTINUE清理同lane pending中的recoverable错误`() = testScope.runTest {
        seedSession()
        messageManager.addMessage("s1", assistantMessage())
        advanceUntilIdle()
        messageManager.updateMessageContent(
            "s1", "m1", "partial",
            UpdateMessageOptions(isError = true, errorMessage = "recoverable"),
        )
        messageManager.updateMessageContent(
            "s1", "m1", "partial",
            UpdateMessageOptions(clearError = true, clearToolCalls = true),
        )

        messageManager.flushGenerationTerminal("s1", "m1")

        val stored = store.getSession("s1")!!.messages.single()
        assertThat(stored.isError).isFalse()
        assertThat(stored.errorMessage).isNull()
        assertThat(stored.toolCalls).isEmpty()
        assertThat(persistedMessage?.isError).isFalse()
        assertThat(persistedMessage?.errorMessage).isNull()
    }

    @Test
    fun `同lane先清理旧工具再收到新工具时以新工具为准`() = testScope.runTest {
        seedSession()
        messageManager.addMessage("s1", assistantMessage())
        advanceUntilIdle()
        messageManager.updateMessageContent(
            "s1", "m1", "",
            UpdateMessageOptions(clearToolCalls = true),
        )
        messageManager.updateMessageContent(
            "s1", "m1", "",
            UpdateMessageOptions(toolCalls = listOf(ToolCall("call-2", "read_file", "{}"))),
        )

        messageManager.flushGenerationTerminal("s1", "m1")

        assertThat(store.getSession("s1")!!.messages.single().toolCalls?.map { it.id })
            .containsExactly("call-2")
    }

    @Test
    fun `终态屏障等待inflight更新后以终态最后写入`() = testScope.runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manager = MessageManager(
            store,
            stubMessageRepo,
            stubSessionRepo,
            testScope,
            MessageManagerHooks(beforeUiApply = {
                entered.complete(Unit)
                release.await()
            }),
        )
        seedSession()
        manager.addMessage("s1", assistantMessage())
        advanceUntilIdle()
        manager.updateMessageContent(
            "s1",
            "m1",
            "partial",
            UpdateMessageOptions(isError = true, errorMessage = "recoverable"),
        )
        advanceTimeBy(100)
        runCurrent()
        entered.await()
        val terminal = launch {
            manager.markGenerationTerminal("s1", "m1", "final", "success")
        }

        release.complete(Unit)
        terminal.join()
        advanceUntilIdle()

        val stored = store.getSession("s1")!!.messages.single()
        assertThat(stored.content).isEqualTo("final")
        assertThat(stored.status).isEqualTo("success")
        assertThat(stored.isError).isFalse()
        assertThat(persistedMessage?.status).isEqualTo("success")
    }

    @Test
    fun `error与cancelled终态同步Store和Repository`() = testScope.runTest {
        seedSession()
        messageManager.addMessage("s1", assistantMessage())
        advanceUntilIdle()

        messageManager.markGenerationTerminal("s1", "m1", "partial", "error", "network")
        assertThat(store.getSession("s1")!!.messages.single().status).isEqualTo("error")
        assertThat(persistedMessage?.status).isEqualTo("error")
        assertThat(persistedMessage?.isError).isTrue()

        messageManager.markGenerationTerminal("s1", "m1", "partial", "cancelled")
        assertThat(store.getSession("s1")!!.messages.single().status).isEqualTo("cancelled")
        assertThat(persistedMessage?.status).isEqualTo("cancelled")
        assertThat(persistedMessage?.isError).isFalse()
        assertThat(persistedMessage?.errorMessage).isNull()
    }

    @Test
    fun `失败终态在Store与数据库原子清除未确认工具调用`() = testScope.runTest {
        seedSession()
        messageManager.addMessage(
            "s1",
            assistantMessage().copy(
                toolCalls = listOf(ToolCall("call", "search", "{}")),
                status = "streaming",
            ),
        )
        advanceUntilIdle()

        messageManager.markGenerationTerminal("s1", "m1", "partial", "error", "network")

        assertThat(store.getSession("s1")!!.messages.single().toolCalls).isEmpty()
        assertThat(partialUpdates.last()["toolCalls"]).isEqualTo(emptyList<ToolCall>())
    }

    @Test
    fun `批次中途停止保留已有结果的调用并移除未执行调用`() = testScope.runTest {
        val calls = listOf(ToolCall("c1", "read_file", "{}"), ToolCall("c2", "read_file", "{}"))
        val assistant = Message("m1", MessageRole.ASSISTANT, "partial", toolCalls = calls)
        store.update { it.copy(sessions = listOf(Session("s1", "agent", messages = listOf(
            assistant,
            Message("t1", MessageRole.TOOL, "done", toolCallId = "c1", parentMessageId = "m1"),
        )))) }
        persistedMessage = assistant
        messageManager.markGenerationTerminal("s1", "m1", "partial", "cancelled")
        assertThat(store.getSession("s1")!!.messages.first().toolCalls?.map { it.id }).containsExactly("c1")
        assertThat(partialUpdates.last()["toolCalls"]).isEqualTo(listOf(calls.first()))
    }

    @Test
    fun `终态DB写失败时Store不得提前发布success`() = testScope.runTest {
        seedSession()
        messageManager.addMessage(
            "s1",
            Message(id = "m1", role = MessageRole.ASSISTANT, content = "partial", status = "streaming"),
        )
        advanceUntilIdle()
        failPartialUpdate = true

        val failure = runCatching {
            messageManager.markGenerationTerminal("s1", "m1", "final", "success")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(store.getSession("s1")!!.messages.single().status).isEqualTo("streaming")
        assertThat(persistedMessage?.status).isEqualTo("streaming")
    }

    @Test
    fun `终态目标DB行缺失时Store幽灵消息不得发布success`() = testScope.runTest {
        seedSession()
        val ghost = Message(
            id = "m1",
            role = MessageRole.ASSISTANT,
            content = "partial",
            status = "streaming",
        )
        store.update { state ->
            state.copy(sessions = state.sessions.map { it.copy(messages = listOf(ghost)) })
        }
        failMissingRow = true

        val failure = runCatching {
            messageManager.markGenerationTerminal("s1", "m1", "final", "success")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(MessagePersistenceException::class.java)
        assertThat(store.getSession("s1")!!.messages.single().status).isEqualTo("streaming")
        assertThat(store.getSession("s1")!!.messages.single().content).isEqualTo("partial")
    }
}
