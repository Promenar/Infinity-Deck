package com.promenar.infinity.data.generation

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.model.InferenceParams
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.TokenUsage
import com.promenar.infinity.data.model.ToolCall
import com.promenar.infinity.data.remote.ProviderRequestRouter
import com.promenar.infinity.data.remote.ProviderResolution
import com.promenar.infinity.data.remote.ResolvedProviderModel
import com.promenar.infinity.data.remote.UnifiedLlmClient
import com.promenar.infinity.data.remote.UnifiedProviderConfig
import com.promenar.infinity.data.remote.protocol.LlmProtocol
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.PromptResponse
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.protocol.ProtocolUsage
import com.promenar.infinity.data.remote.protocol.StreamChunk
import com.promenar.infinity.data.remote.provider.LlmProvider
import com.promenar.infinity.data.repository.MessageRepository
import com.promenar.infinity.data.repository.RolledBackSummaryUsageStore
import com.promenar.infinity.data.repository.SessionRepository
import com.promenar.infinity.data.repository.TokenStatsRepository
import com.promenar.infinity.domain.generation.CompletionReason
import com.promenar.infinity.domain.generation.GenerationFailureCode
import com.promenar.infinity.domain.generation.GenerationRequest
import com.promenar.infinity.domain.generation.GenerationRuntimePolicy
import com.promenar.infinity.domain.repository.IAgentRepository
import com.promenar.infinity.domain.usecase.AgentConfigResolver
import com.promenar.infinity.ui.chat.ChatStore
import com.promenar.infinity.ui.chat.manager.ContextBuilder
import com.promenar.infinity.ui.chat.manager.ContextBuilderResult
import com.promenar.infinity.ui.chat.manager.MessageManager
import com.promenar.infinity.ui.chat.manager.SessionManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SummaryBudgetRollbackIntegrationTest {
    private val databases = mutableListOf<InfinityDatabase>()

    @After
    fun closeDatabases() {
        databases.forEach(InfinityDatabase::close)
    }

    @Test
    fun `摘要成功后首轮总预算失败保留持久费用且清除准备消息并展示错误`() = runTest {
        val fixture = fixture()
        val runtime = fixture.runtime()

        // 摘要使用真实 IO 调度，避免虚拟时钟提前触发超时。
        withContext(Dispatchers.Default) {
            ChatGenerationRunner(runtime).run(fixture.request) {}
        }

        assertThat(fixture.summaryPrompts).hasSize(1)
        assertThat(fixture.mainPrompts).isEmpty()
        val persisted = fixture.messageRepository.getBySession("session")
        assertThat(persisted.map { it.id }).containsExactlyElementsIn(fixture.historicalMessageIds).inOrder()
        assertThat(fixture.store.getSession("session")!!.messages.map { it.id })
            .containsExactlyElementsIn(fixture.historicalMessageIds).inOrder()
        assertThat(persisted.any { it.role == MessageRole.ASSISTANT && it.content.isBlank() && it.toolCalls.isNullOrEmpty() })
            .isFalse()
        val presentation = fixture.presentation.observe("session").value!!
        assertThat(presentation.error?.code).isEqualTo(GenerationFailureCode.CONTEXT_LIMIT)
        assertThat(presentation.failureSurface).isEqualTo(GenerationFailureSurface.OVERLAY)
        assertThat(presentation.generating).isFalse()
        assertThat(presentation.handledFailure).isTrue()

        // 新建统计仓库，确保费用来自持久化状态而非生成器的内存累计。
        val usage = fixture.statistics().getUsageBySession("session")
        assertThat(usage.inputTokens).isEqualTo(37L)
        assertThat(usage.outputTokens).isEqualTo(11L)
        assertThat(usage.estimated).isFalse()

        withContext(Dispatchers.Default) { runtime.buildContext(fixture.request) }
        assertThat(fixture.statistics().getUsageBySession("session")).isEqualTo(usage)
        assertThat(fixture.summaryPrompts).hasSize(1)
    }

    @Test
    fun `替换回复准备失败保留旧回复和用户消息并保留摘要费用`() = runTest {
        val fixture = fixture()
        fixture.messages.addMessage("session", Message(
            "old-final", MessageRole.ASSISTANT, "旧回复", tokens = TokenUsage(3, 2, 5),
        ))
        val request = fixture.request.copy(messageIdsToReplace = setOf("old-final"))

        withContext(Dispatchers.Default) {
            ChatGenerationRunner(fixture.runtime()).run(request) {}
        }

        assertThat(fixture.messageRepository.getById("prepared-assistant")).isNull()
        assertThat(fixture.messageRepository.getById("current-user")).isNotNull()
        assertThat(fixture.messageRepository.getById("old-final")?.content).isEqualTo("旧回复")
        val usage = fixture.statistics().getUsageBySession("session")
        assertThat(usage.inputTokens).isEqualTo(40L)
        assertThat(usage.outputTokens).isEqualTo(13L)
        assertThat(fixture.presentation.observe("session").value?.error?.code).isEqualTo(GenerationFailureCode.CONTEXT_LIMIT)
    }

    @Test
    fun `费用账本写盘失败时保留承载真实用量的准备消息`() = runTest {
        val fixture = fixture()
        val failingSettings = spyk(fixture.preferences)
        val editor = mockk<SharedPreferences.Editor>()
        every { failingSettings.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.commit() } returns false
        val runtime = fixture.runtime(failingSettings)

        val failure = withContext(Dispatchers.Default) {
            runtime.prepare(fixture.request)
            runCatching { runtime.buildContext(fixture.request) }.exceptionOrNull()
        }

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(fixture.messageRepository.getById("prepared-assistant")?.tokens?.summaryInput).isEqualTo(37)
        assertThat(fixture.messageRepository.getById("current-user")).isNotNull()
        assertThat(fixture.store.getSession("session")!!.messages.any { it.id == "prepared-assistant" }).isTrue()
        assertThat(fixture.statistics().getUsageBySession("session").inputTokens).isEqualTo(37L)
    }

    @Test
    fun `删除失败时费用不双计且补偿删除后仍计一次`() = runTest {
        val fixture = fixture(failPreparedDeletion = true)
        val runtime = fixture.runtime()
        // 保留父用户消息，避免其 CASCADE 删除掩盖助手消息删除失败的分支。
        val request = fixture.request.copy(rollbackUserOnPreparationFailure = false)

        val failure = withContext(Dispatchers.Default) {
            runtime.prepare(request)
            runCatching { runtime.buildContext(request) }.exceptionOrNull()
        }

        assertThat(failure).isInstanceOf(java.io.IOException::class.java)
        assertThat(fixture.messageRepository.getById("prepared-assistant")).isNotNull()
        assertThat(fixture.statistics().getUsageBySession("session").inputTokens).isEqualTo(37L)
        fixture.messages.discardPreparedMessages("session", listOf("prepared-assistant", "current-user"))
        assertThat(fixture.messageRepository.getById("prepared-assistant")).isNull()
        val usage = fixture.statistics().getUsageBySession("session")
        assertThat(usage.inputTokens).isEqualTo(37L)
        assertThat(usage.outputTokens).isEqualTo(11L)
    }

    @Test
    fun `重置统计清除已回滚的摘要费用`() = runTest {
        val fixture = fixture()
        withContext(Dispatchers.Default) {
            ChatGenerationRunner(fixture.runtime()).run(fixture.request) {}
        }
        assertThat(fixture.statistics().getUsageBySession("session").inputTokens).isEqualTo(37L)

        fixture.statistics().resetStats()

        val usage = fixture.statistics().getUsageBySession("session")
        assertThat(usage.inputTokens).isEqualTo(0L)
        assertThat(usage.outputTokens).isEqualTo(0L)
        assertThat(usage.estimated).isFalse()
    }

    @Test
    fun `替换回复的Runner在账本commit返回失败时不能删除真实费用且保留显式错误`() = runTest {
        assertReplacementLedgerWriteFailure(throwsOnCommit = false)
    }

    @Test
    fun `替换回复的Runner在账本commit抛异常时不能删除真实费用且保留显式错误`() = runTest {
        assertReplacementLedgerWriteFailure(throwsOnCommit = true)
    }

    private suspend fun TestScope.assertReplacementLedgerWriteFailure(throwsOnCommit: Boolean) {
        val fixture = fixture()
        fixture.messages.addMessage("session", Message(
            "old-final", MessageRole.ASSISTANT, "旧回复", tokens = TokenUsage(3, 2, 5),
        ))
        val settings = spyk(fixture.preferences)
        val editor = mockk<SharedPreferences.Editor>()
        every { settings.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        if (throwsOnCommit) every { editor.commit() } throws java.io.IOException("模拟账本写入异常")
        else every { editor.commit() } returns false
        val request = fixture.request.copy(messageIdsToReplace = setOf("old-final"))

        val failure = withContext(Dispatchers.Default) {
            runCatching {
                ChatGenerationRunner(fixture.runtime(settings)).run(request) {
                    fixture.presentation.accept("session", "task", it)
                }
            }.exceptionOrNull()
        }

        assertThat(failure).isNotNull()
        val usage = fixture.statistics().getUsageBySession("session")
        assertThat(usage.inputTokens).isEqualTo(40L)
        assertThat(usage.outputTokens).isEqualTo(13L)
        val paidMessage = fixture.messageRepository.getById("prepared-assistant")!!
        assertThat(paidMessage.status).isEqualTo("error")
        assertThat(paidMessage.isError).isTrue()
        assertThat(paidMessage.errorMessage).isNotEmpty()
        assertThat(fixture.messageRepository.getById("old-final")?.content).isEqualTo("旧回复")
        assertThat(fixture.messageRepository.getById("current-user")).isNotNull()
        val visible = fixture.store.getSession("session")!!.messages
        assertThat(visible.filter { it.role == MessageRole.ASSISTANT && it.content.isBlank() &&
            it.toolCalls.isNullOrEmpty() && !it.isError && it.errorMessage.isNullOrBlank() }).isEmpty()
        assertThat(fixture.presentation.observe("session").value?.error?.code).isEqualTo(GenerationFailureCode.PERSISTENCE)
        assertThat(fixture.presentation.observe("session").value?.failureSurface).isEqualTo(GenerationFailureSurface.OVERLAY)
    }

    private suspend fun TestScope.fixture(failPreparedDeletion: Boolean = false): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java)
            .allowMainThreadQueries().build()
        databases += database
        return Fixture(this, context, database, failPreparedDeletion).also { fixture ->
            fixture.preferences.edit()
                .putInt("model_info_local::summary-budget-model_context", 2048)
                .putString("preset_summary_model", "local::summary-budget-model")
                .commit()
            fixture.sessions.addSession(Session(
                id = "session", agentId = "agent", modelId = "local::summary-budget-model",
                ragOptions = RagOptions(enableMemory = false, enableDocs = false),
                inferenceParams = InferenceParams(maxTokens = 64, keepRecentTurns = 20),
            ))
            fixture.messages.addMessage("session", Message("old-user", MessageRole.USER, "历史任务"))
            listOf("较早的工具结果".repeat(2000), "最近的工具结果一", "最近的工具结果二", "最近的工具结果三").forEachIndexed { index, content ->
                fixture.messages.addMessage("session", Message(
                    id = "old-assistant-$index", role = MessageRole.ASSISTANT, content = "历史工具调用",
                    toolCalls = listOf(ToolCall("old-call-$index", "read_file", "{}")),
                    parentMessageId = "old-user",
                ))
                fixture.messages.addMessage("session", Message(
                    id = "old-tool-$index", role = MessageRole.TOOL, content = content,
                    toolCallId = "old-call-$index", parentMessageId = "old-assistant-$index", name = "read_file",
                ))
            }
            fixture.messages.addMessage("session", Message("current-user", MessageRole.USER, "新任务"))
            fixture.messages.addMessage("session", Message(
                "prepared-assistant", MessageRole.ASSISTANT, "", modelId = "local::summary-budget-model",
                parentMessageId = "current-user",
            ))
        }
    }

    private class Fixture(
        private val scope: TestScope,
        context: Context,
        val database: InfinityDatabase,
        failPreparedDeletion: Boolean,
    ) {
        val preferences: SharedPreferences = context.getSharedPreferences("summary-budget-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val sessionRepository = SessionRepository(database.sessionDao(), database.messageDao())
        val messageRepository = object : MessageRepository(database.messageDao()) {
            private var failNextDelete = failPreparedDeletion
            override suspend fun deleteInSession(sessionId: String, messageId: String): Boolean {
                if (messageId == "prepared-assistant" && failNextDelete) {
                    failNextDelete = false
                    throw java.io.IOException("模拟准备消息删除失败")
                }
                return super.deleteInSession(sessionId, messageId)
            }
        }
        val store = ChatStore()
        val messages = MessageManager(store, messageRepository, sessionRepository, scope)
        val sessions = SessionManager(store, sessionRepository)
        val presentation = GenerationPresentationStore().apply { begin("session", "task") }
        val summaryPrompts = mutableListOf<PromptRequest>()
        val mainPrompts = mutableListOf<PromptRequest>()
        val historicalMessageIds = listOf("old-user") + (0..3).flatMap { listOf("old-assistant-$it", "old-tool-$it") }
        val request = GenerationRequest(
            "session", "prepared-assistant", "current-user", "新任务", emptyList(),
            GenerationRuntimePolicy.BACKGROUND_ALLOWED, rollbackUserOnPreparationFailure = true,
        )

        fun statistics() = TokenStatsRepository(
            database.messageDao(), rolledBackSummaryUsageStore = RolledBackSummaryUsageStore(preferences),
        )

        fun runtime(settings: SharedPreferences = preferences): DefaultChatGenerationRuntime {
            val protocol = object : LlmProtocol {
                override val protocolType = ProtocolType.Local
                override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> {
                    mainPrompts += request
                    return flowOf(StreamChunk.TextDelta("主请求结果"), StreamChunk.Completed(CompletionReason.END_TURN))
                }
                override suspend fun sendPromptSync(request: PromptRequest): PromptResponse {
                    summaryPrompts += request
                    return PromptResponse("工具结果已经读取并核实。".repeat(SUMMARY_REPEAT), usage = ProtocolUsage(37, 11, 48))
                }
                override fun cancel() = Unit
            }
            val provider = LlmProvider(protocol)
            val router = object : ProviderRequestRouter {
                override fun resolve(modelId: String) = ProviderResolution.Success(ResolvedProviderModel(
                    modelId, "summary-budget-model", "local", "Local",
                    UnifiedProviderConfig(ProtocolType.Local, "", "", "summary-budget-model"),
                ))
                override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient = error("本地协议不创建远端客户端")
            }
            val contextBuilder = mockk<ContextBuilder>()
            coEvery { contextBuilder.buildContext(any()) } returns ContextBuilderResult(
                "", "", emptyList(), emptyList(), null, "超出预算的系统上下文".repeat(SYSTEM_REPEAT),
            )
            return DefaultChatGenerationRuntime(
                settings = settings, applicationScope = scope, store = store,
                agentRepository = mockk<IAgentRepository> { coEvery { getById(any()) } returns null },
                configResolver = AgentConfigResolver(preferences),
                routeGate = ChatProviderRouteGate(router, UnconfinedTestDispatcher(scope.testScheduler)),
                contextBuilder = contextBuilder, messageManager = messages, localProviderFactory = { provider },
                provider = provider, toolLedger = mockk(relaxed = true), toolExecutor = mockk(relaxed = true),
                postProcessor = mockk(relaxed = true), memoryManager = null, summaryManager = mockk(relaxed = true),
                sessionManager = sessions, contentStrategy = DefaultChatGenerationContentStrategy(preferences, null),
                ui = presentation.port("session", "task"),
            )
        }
    }
}

// 系统上下文与当前轮放得下，不触发当前轮下限；压缩后的长摘要叠加系统上下文仍超出总预算。
private const val SYSTEM_REPEAT = 20
private const val SUMMARY_REPEAT = 200
