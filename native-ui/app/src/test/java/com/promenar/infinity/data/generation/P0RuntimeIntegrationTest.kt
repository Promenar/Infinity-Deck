package com.promenar.infinity.data.generation

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.model.InferenceParams
import com.promenar.infinity.data.model.LoopStatus
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.SessionOptions
import com.promenar.infinity.data.model.TokenUsage
import com.promenar.infinity.data.model.ToolCall
import com.promenar.infinity.data.model.json
import com.promenar.infinity.data.remote.ProviderRequestRouter
import com.promenar.infinity.data.remote.ProviderResolution
import com.promenar.infinity.data.remote.ResolvedProviderModel
import com.promenar.infinity.data.remote.UnifiedLlmClient
import com.promenar.infinity.data.remote.UnifiedProviderConfig
import com.promenar.infinity.data.remote.protocol.LlmProtocol
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.PromptResponse
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.ProtocolToolChoice
import com.promenar.infinity.data.remote.protocol.ProtocolToolFunction
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.protocol.ProtocolUsage
import com.promenar.infinity.data.remote.protocol.StreamChunk
import com.promenar.infinity.data.remote.provider.LlmProvider
import com.promenar.infinity.data.repository.MessageRepository
import com.promenar.infinity.data.repository.SessionRepository
import com.promenar.infinity.data.repository.ToolExecutionKey
import com.promenar.infinity.data.repository.ToolExecutionLedgerRepository
import com.promenar.infinity.data.repository.ToolExecutionOutcome
import com.promenar.infinity.data.repository.ToolInvocationIdentityFactory
import com.promenar.infinity.data.repository.ToolInvocationIdentityResolution
import com.promenar.infinity.data.repository.ToolOutputStore
import com.promenar.infinity.domain.generation.CompletionReason
import com.promenar.infinity.domain.generation.GenerationEvent
import com.promenar.infinity.domain.generation.GenerationPreparationOutcome
import com.promenar.infinity.domain.generation.GenerationPhase
import com.promenar.infinity.domain.generation.GenerationRequest
import com.promenar.infinity.domain.generation.GenerationRuntimePolicy
import com.promenar.infinity.domain.repository.IAgentRepository
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.domain.usecase.AgentConfigResolver
import com.promenar.infinity.ui.chat.ChatStore
import com.promenar.infinity.ui.chat.manager.ApprovalManager
import com.promenar.infinity.ui.chat.manager.ContextBuilder
import com.promenar.infinity.ui.chat.manager.ContextBuilderResult
import com.promenar.infinity.ui.chat.manager.MessageManager
import com.promenar.infinity.ui.chat.manager.SessionManager
import com.promenar.infinity.ui.chat.manager.ToolExecutor
import com.promenar.infinity.ui.chat.manager.registry.SkillRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.coVerify
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlinx.serialization.encodeToString
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class P0RuntimeIntegrationTest {
    @get:Rule
    val temporaryFiles = TemporaryFolder()
    private val databases = mutableListOf<InfinityDatabase>()

    @After
    fun closeDatabases() {
        databases.forEach(InfinityDatabase::close)
    }

    @Test
    fun `首轮工具续轮和轮后均检查实际将发送的上下文`() = runTest {
        val fixture = fixture()
        val pipeline = spyk(ContextCompactionPipeline())

        ChatGenerationRunner(fixture.runtime(pipeline)).run(fixture.request) {}

        assertThat(fixture.prompts).hasSize(2)
        assertThat(fixture.prompts[1].messages.any { it.role == "tool" }).isTrue()
        coVerify(exactly = 3) { pipeline.compact(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `协议上报的缓存与推理用量和每轮计时写入对应助手消息`() = runTest {
        val fixture = fixture()
        fixture.rounds = { index ->
            if (index == 1) listOf(
                StreamChunk.ToolCallDelta("call-1", "write_file", """{"path":"/a.md"}""", 0),
                StreamChunk.Usage(ProtocolUsage(500, 20, 520, cacheReadInput = 400, cacheWriteInput = 50, cacheWriteLongTtlInput = 20)),
                StreamChunk.Completed(CompletionReason.TOOL_CALLS, listOf("call-1")),
            ) else listOf(
                StreamChunk.TextDelta("任务完成"),
                StreamChunk.Usage(ProtocolUsage(600, 30, 630, reasoningOutput = 12)),
                StreamChunk.Completed(CompletionReason.END_TURN),
            )
        }

        ChatGenerationRunner(fixture.runtime()).run(fixture.request) {}

        val assistants = fixture.messageRepository.getBySession("session").filter { it.role == MessageRole.ASSISTANT }
        val first = requireNotNull(assistants.single { it.id == "assistant" }.tokens)
        assertThat(first.input).isEqualTo(500)
        assertThat(first.cacheReadInput).isEqualTo(400)
        assertThat(first.cacheWriteInput).isEqualTo(50)
        assertThat(first.cacheWriteLongTtlInput).isEqualTo(20)
        assertThat(first.reasoningOutput).isNull()
        val firstTiming = requireNotNull(first.timing)
        assertThat(firstTiming.firstOutputAt).isAtLeast(firstTiming.requestStartedAt!!)
        assertThat(firstTiming.endedAt).isAtLeast(firstTiming.firstOutputAt!!)
        val second = requireNotNull(assistants.single { it.id != "assistant" }.tokens)
        assertThat(second.reasoningOutput).isEqualTo(12)
        assertThat(second.cacheReadInput).isNull()
        assertThat(second.cacheWriteLongTtlInput).isNull()
        assertThat(requireNotNull(second.timing).requestStartedAt).isAtLeast(firstTiming.endedAt!!)
        val raw = requireNotNull(fixture.database.messageDao().getById("assistant")!!.tokens)
        assertThat(raw).contains("\"cacheReadInput\":400")
        assertThat(raw).doesNotContain("reasoningOutput")
    }

    @Test
    fun `阈值触发先折叠再归档摘要且两类压缩只使用活跃模型并记录费用`() = runTest {
        val fixture = fixture(priorToolResults = listOf("旧工具事实".repeat(500), "最近一", "最近二", "最近三"), historicalTurn = true)
        fixture.preferences.edit().putString("preset_summary_model", "quick::different").commit()
        fixture.sessions.updateSessionInferenceParams("session", InferenceParams(maxTokens = 64, keepRecentTurns = 1, autoSummaryThreshold = 0.0))
        fixture.sessions.updateSession("session", mapOf("ragOptions" to RagOptions(enableMemory = true, enableDocs = false)))
        fixture.summaryResponse = PromptResponse("保留关键事实的历史摘要", usage = ProtocolUsage(7, 3, 10))
        fixture.rounds = { listOf(StreamChunk.TextDelta("回答"), StreamChunk.Completed(CompletionReason.END_TURN)) }

        withContext(Dispatchers.Default) { ChatGenerationRunner(fixture.runtime()).run(fixture.request) {} }

        assertThat(fixture.summaryPrompts).hasSize(2)
        assertThat(fixture.summaryPrompts.map { it.model }).containsExactly("p0-integration-model", "p0-integration-model")
        assertThat(fixture.resolvedModels).doesNotContain("quick::different")
        assertThat(fixture.postEvents).containsExactly("tool-summary", "archive", "history-summary").inOrder()
        val session = fixture.store.getSession("session")!!
        assertThat(session.summary).isEqualTo("保留关键事实的历史摘要")
        assertThat(session.messages.filter { it.id.startsWith("historical-") }.all { it.isArchived }).isTrue()
        assertThat(session.messages.first { it.id == "user" }.isArchived).isFalse()
        assertThat(fixture.messageRepository.getById("assistant")!!.tokens!!.summaryTotal).isEqualTo(20)
        assertThat(fixture.prompts.single().messages.first().content).contains("<history_summary>")
        assertThat(fixture.prompts.single().messages.none { it.role == "tool" }).isTrue()
    }

    @Test
    fun `历史摘要失败后轮后检查不重试且原消息保持未摘要`() = runTest {
        val fixture = fixture(priorToolResults = listOf("完整旧结果"), historicalTurn = true)
        fixture.sessions.updateSessionInferenceParams("session", InferenceParams(maxTokens = 64, keepRecentTurns = 1, autoSummaryThreshold = 0.0))
        fixture.summaryResponse = PromptResponse("", usage = ProtocolUsage(4, 0, 4))
        fixture.rounds = { listOf(StreamChunk.TextDelta("回答"), StreamChunk.Completed(CompletionReason.END_TURN)) }

        withContext(Dispatchers.Default) { ChatGenerationRunner(fixture.runtime()).run(fixture.request) {} }

        assertThat(fixture.summaryPrompts).hasSize(1)
        assertThat(fixture.store.getSession("session")!!.summary).isNull()
        assertThat(fixture.messageRepository.getBySession("session").none { it.isArchived }).isTrue()
        assertThat(fixture.prompts.single().messages.first { it.role == "tool" }.content).isEqualTo("完整旧结果")
        assertThat(fixture.messageRepository.getById("assistant")!!.tokens!!.summaryTotal).isEqualTo(4)
    }

    @Test
    fun `手动压缩忽略高阈值执行工具折叠和历史摘要且不创建生成消息`() = runTest {
        val fixture = fixture(priorToolResults = listOf("工具原始内容".repeat(200), "最近一", "最近二", "最近三"), historicalTurn = true)
        fixture.sessions.updateSessionInferenceParams("session", InferenceParams(maxTokens = 64, keepRecentTurns = 1, autoSummaryThreshold = 0.95))
        fixture.preferences.edit().putString("preset_summary_model", "quick::different").commit()
        fixture.summaryResponse = PromptResponse("手动摘要", usage = ProtocolUsage(7, 3, 10))
        for ((index, message) in fixture.messageRepository.getBySession("session").withIndex()) {
            fixture.messageRepository.update(message.copy(
                createdAt = 1000L + index,
                modelId = if (message.role == MessageRole.ASSISTANT) "old::model-a" else message.modelId,
                tokens = if (message.id == "assistant") TokenUsage(100, 50, 150) else message.tokens,
            ), "session")
        }
        fixture.rebuildFromRepositories()
        val beforeIds = fixture.messageRepository.getBySession("session").map { it.id }
        val startedAt = System.currentTimeMillis()

        val result = withContext(Dispatchers.Default) { fixture.runtime().compactNow("session") {} }

        assertThat(result.newlyFoldedToolResults).isEqualTo(1)
        assertThat(result.summarizedTurns).isEqualTo(1)
        assertThat(result.afterTokens).isLessThan(result.beforeTokens)
        assertThat(fixture.prompts).isEmpty()
        assertThat(fixture.summaryPrompts).hasSize(2)
        assertThat(fixture.resolvedModels).doesNotContain("quick::different")
        assertThat(fixture.messageRepository.getBySession("session").map { it.id }).isEqualTo(beforeIds)
        assertThat(fixture.messageRepository.getById("historical-result-0")!!.content).isEqualTo("工具原始内容".repeat(200))
        assertThat(fixture.messageRepository.getById("assistant")!!.tokens).isEqualTo(TokenUsage(100, 50, 150))
        val usageStore = com.promenar.infinity.data.repository.RolledBackSummaryUsageStore(fixture.preferences)
        val records = usageStore.withRecords { it.toList() }
        assertThat(records).hasSize(2)
        assertThat(records.map { it.modelId }.distinct()).containsExactly("local::p0-integration-model")
        assertThat(records.all { it.createdAt in startedAt..System.currentTimeMillis() }).isTrue()
        assertThat(records.sumOf { it.usage.input }).isEqualTo(14)
        assertThat(records.sumOf { it.usage.output }).isEqualTo(6)
        val statistics = com.promenar.infinity.data.repository.TokenStatsRepository(
            fixture.database.messageDao(), rolledBackSummaryUsageStore = usageStore,
        )
        val sessionUsage = statistics.getUsageBySession("session")
        assertThat(sessionUsage.inputTokens).isEqualTo(118L)
        assertThat(sessionUsage.outputTokens).isEqualTo(60L)
    }

    @Test
    fun `短工具结果无候选时模型不可用仍返回空手动结果`() = runTest {
        val fixture = fixture(priorToolResults = listOf("短一", "短二", "短三", "短四"))
        fixture.providerUnavailable = true

        val result = fixture.runtime().compactNow("session") {}

        assertThat(result.newlyFoldedToolResults).isEqualTo(0)
        assertThat(result.summarizedTurns).isEqualTo(0)
        assertThat(result.summaryFailed).isFalse()
        assertThat(fixture.summaryPrompts).isEmpty()
    }

    @Test
    fun `用量估算复用路由能力和工具技能组装且等于实际准备请求`() = runTest {
        for (type in listOf(ProtocolType.Local, ProtocolType.Google_VertexAI, ProtocolType.Google_Gemini)) {
            val fixture = fixture()
            fixture.providerProtocolType = type
            fixture.skillCatalog = mockk<com.promenar.infinity.data.skills.SkillCatalog>().also { catalog ->
                coEvery { catalog.scan(any()) } throws java.io.IOException("目录不可用")
            }
            val runtime = fixture.runtime()
            runtime.prepare(fixture.request)
            assertThat(runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)
            val field = DefaultChatGenerationRuntime::class.java.getDeclaredField("preparedPrompt").apply { isAccessible = true }
            val actual = field.get(runtime) as PromptRequest

            val estimate = runtime.estimateContext("session")

            assertThat(estimate.totalTokens).isEqualTo(ApproximateTokenEstimator.estimate(actual))
            assertThat(fixture.builtContexts.last().availableSkills).isEmpty()
            assertThat(actual.tools.orEmpty()).hasSize(1)
            assertThat(fixture.builtContexts.last().availableToolNames).isNotEmpty()
        }
    }

    @Test
    fun `不限半自动 prepare 后切全自动需等待确认且接受结果持久化`() = runTest {
        val fixture = fixture(mode = "semi", confirmed = false)
        fixture.onPrompt = { round, _ ->
            if (round == 1) fixture.sessions.updateSessionExecutionMode("session", "auto")
        }
        val events = mutableListOf<GenerationEvent>()
        val job = async { ChatGenerationRunner(fixture.runtime()).run(fixture.request) { events += it } }
        val confirmation = fixture.interactions.pending.first { it != null }!!

        assertThat(confirmation.kind).isEqualTo(GenerationInteractionKind.UNLIMITED_AUTO)
        assertThat(fixture.prompts).hasSize(1)
        assertThat(fixture.executedCalls).isEmpty()
        assertThat(fixture.messageRepository.getBySession("session").filter { it.role == MessageRole.TOOL }).isEmpty()
        assertThat(fixture.preferences.getBoolean(UnlimitedAutomationConsent.KEY, false)).isFalse()

        assertThat(fixture.interactions.respond(confirmation.id, true)).isTrue()
        job.await()

        assertThat(fixture.executedCalls).containsExactly("call-1")
        assertThat(fixture.messageRepository.getBySession("session").single { it.role == MessageRole.TOOL }.content)
            .isEqualTo("完整工具结果")
        val reopenedPreferences = fixture.context.getSharedPreferences(fixture.preferencesName, Context.MODE_PRIVATE)
        assertThat(reopenedPreferences.getBoolean(UnlimitedAutomationConsent.KEY, false)).isTrue()
        assertThat(UnlimitedAutomationConsent(reopenedPreferences).requiresConfirmation(true, "auto")).isFalse()
        assertThat(events.filterIsInstance<GenerationEvent.PhaseChanged>().last().phase).isEqualTo(GenerationPhase.COMPLETED)
    }

    @Test
    fun `无 Usage 估算落库后审批恢复用相同用户消息累计 token 并触发收尾`() = runTest {
        val fixture = fixture(mode = "workspace")
        fixture.rounds = { index ->
            if (index == 1) listOf(
                StreamChunk.ToolCallDelta("call-1", "write_file", """{"path":"/skills/demo/SKILL.md"}""", 0),
                StreamChunk.Completed(CompletionReason.TOOL_CALLS, listOf("call-1")),
            ) else listOf(StreamChunk.TextDelta("任务完成"), StreamChunk.Completed(CompletionReason.END_TURN))
        }
        ChatGenerationRunner(fixture.runtime()).run(fixture.request) {}
        assertThat(fixture.store.getSession("session")!!.loopStatus).isEqualTo(LoopStatus.WAITING_FOR_APPROVAL)
        assertThat(fixture.executedCalls).isEmpty()
        val firstAssistant = fixture.messageRepository.getById("assistant")!!
        val storedUsage = requireNotNull(firstAssistant.tokens)
        assertThat(storedUsage.estimated).isTrue()
        assertThat(storedUsage.total).isGreaterThan(0)
        val tokensJson = requireNotNull(fixture.database.messageDao().getById("assistant")!!.tokens)
        assertThat(json.decodeFromString<TokenUsage>(tokensJson)).isEqualTo(storedUsage)

        val approval = fixture.store.getSession("session")!!.approvalRequest!!
        val approvals = ApprovalManager(fixture.store, fixture.ledger, fixture.messages, fixture.sessionRepository)
        approvals.setCallbacks(null) { sessionId, messageId, allowed ->
            val calls = fixture.store.getSession(sessionId)!!.messages.first { it.id == messageId }.toolCalls!!
            fixture.executor.executeTools(sessionId, messageId, calls, allowed, fixture.advertisedTools)
        }
        approvals.resumeGeneration("session", approval)
        assertThat(fixture.executedCalls).containsExactly("call-1")
        fixture.preferences.edit().putInt("task_token_limit", storedUsage.total).commit()
        fixture.rebuildFromRepositories()
        fixture.messages.addMessage("session", Message("resumed", MessageRole.ASSISTANT, "", parentMessageId = "user"))
        fixture.prompts.clear()
        fixture.rounds = { listOf(StreamChunk.TextDelta("预算收尾"), StreamChunk.Completed(CompletionReason.END_TURN)) }
        val resumedRequest = fixture.request.copy(assistantMessageId = "resumed")
        val resumedEvents = mutableListOf<GenerationEvent>()

        ChatGenerationRunner(fixture.runtime()).run(resumedRequest) { resumedEvents += it }

        val firstProgress = resumedEvents.filterIsInstance<GenerationEvent.ProgressChanged>().first().progress
        assertThat(firstProgress.tokens).isEqualTo(storedUsage.total.toLong())
        assertThat(firstProgress.estimated).isTrue()
        assertThat(fixture.prompts).hasSize(1)
        assertThat(fixture.prompts.single().toolChoice).isEqualTo(ProtocolToolChoice.NONE)
        assertThat(fixture.prompts.single().messages.last().content).contains("Tool Budget Exhausted")
        assertThat(fixture.executedCalls).containsExactly("call-1")
        assertThat(fixture.messageRepository.getById("assistant")!!.tokens).isEqualTo(storedUsage)
        assertThat(fixture.messageRepository.getById("resumed")!!.content).isEqualTo("预算收尾")
    }

    @Test
    fun `大输出仅在发送请求中卸载且原始消息与私有完整输出保留`() = runTest {
        val output = ToolOutputStore(temporaryFiles.newFolder("tool-output"))
        val original = "H".repeat(6000) + "原始中段唯一标记" + "X".repeat(20000) + "T".repeat(6000)
        val fixture = fixture(outputStore = output)
        fixture.toolResult = original

        ChatGenerationRunner(fixture.runtime()).run(fixture.request) {}

        assertThat(fixture.prompts).hasSize(2)
        val sentResult = fixture.prompts[1].messages.single { it.role == "tool" }
        assertThat(sentResult.toolCallId).isEqualTo("call-1")
        assertThat(sentResult.content).contains("output_id=out_")
        assertThat(sentResult.content).contains("total_chars=${original.length}")
        assertThat(sentResult.content).doesNotContain("原始中段唯一标记")
        assertThat(sentResult.content.length).isLessThan(original.length)
        assertThat(fixture.store.getSession("session")!!.messages.single { it.role == MessageRole.TOOL }.content).isEqualTo(original)
        assertThat(fixture.messageRepository.getBySession("session").single { it.role == MessageRole.TOOL }.content).isEqualTo(original)
        val outputId = Regex("output_id=(out_[a-f0-9]{64})").find(sentResult.content)!!.groupValues[1]
        val restored = buildString {
            for (offset in 0 until original.length step 8000) append(output.read("session", outputId, offset, minOf(8000, original.length - offset)))
        }
        assertThat(restored).isEqualTo(original)
        val sentCalls = fixture.prompts[1].messages.filter { it.role == "assistant" }.flatMap { it.toolCalls.orEmpty() }
        assertThat(sentCalls.map { it.id }).containsExactly("call-1")
        assertThat(fixture.messageRepository.getById("assistant")!!.toolCalls!!.single().id).isEqualTo("call-1")
    }

    @Test
    fun `下一轮上下文挂起时取消仍保留已执行调用与结果的持久配对`() = runTest {
        val fixture = fixture()
        val continuationEntered = CompletableDeferred<Unit>()
        fixture.onContext = { round ->
            if (round == 3) {
                continuationEntered.complete(Unit)
                awaitCancellation()
            }
        }
        val events = mutableListOf<GenerationEvent>()
        val job = launch { ChatGenerationRunner(fixture.runtime()).run(fixture.request) { events += it } }
        continuationEntered.await()
        assertThat(fixture.executedCalls).containsExactly("call-1")

        job.cancel(CancellationException("用户停止"))
        job.join()

        assertThat(fixture.prompts).hasSize(1)
        val persisted = fixture.messageRepository.getBySession("session")
        assertThat(persisted.first { it.id == "assistant" }.toolCalls!!.map { it.id }).containsExactly("call-1")
        assertThat(persisted.first { it.id == "assistant" }.status).isNull()
        val cancelled = persisted.single { it.role == MessageRole.ASSISTANT && it.id != "assistant" }
        assertThat(cancelled.status).isEqualTo("cancelled")
        assertThat(cancelled.content).isEmpty()
        assertThat(cancelled.toolCalls).isEmpty()
        assertThat(persisted.single { it.role == MessageRole.TOOL }.toolCallId).isEqualTo("call-1")
        assertThat(persisted.single { it.role == MessageRole.TOOL }.content).isEqualTo("完整工具结果")
        assertThat(fixture.store.getSession("session")!!.messages.first { it.id == "assistant" }.toolCalls!!.single().id)
            .isEqualTo("call-1")
        assertThat(events.filterIsInstance<GenerationEvent.PhaseChanged>().last().phase).isEqualTo(GenerationPhase.CANCELLED)
    }

    @Test
    fun `空摘要的实报用量经真实上下文压缩持久化并使首个主请求进入收尾`() = runTest {
        val original = "旧工具结果".repeat(3000)
        val fixture = fixture(priorToolResults = listOf(original, "最近结果一", "最近结果二", "最近结果三"))
        fixture.preferences.edit()
            .putInt("model_info_local::p0-integration-model_context", 4096)
            .putInt("task_token_limit", 5000)
            .putString("preset_summary_model", "local::p0-integration-model")
            .commit()
        fixture.summaryResponse = PromptResponse("", usage = ProtocolUsage(4000, 1000, 5000))
        fixture.rounds = { listOf(StreamChunk.TextDelta("已按预算收尾"), StreamChunk.Completed(CompletionReason.END_TURN)) }
        val runtime = fixture.runtime()

        // 摘要路由使用真实 IO 线程；使用真实时钟避免测试调度器提前耗尽摘要超时。
        withContext(Dispatchers.Default) {
            ChatGenerationRunner(runtime).run(fixture.request) {}
        }

        assertThat(fixture.summaryPrompts).hasSize(1)
        assertThat(fixture.summaryPrompts.single().toolChoice).isEqualTo(ProtocolToolChoice.NONE)
        assertThat(runtime.additionalTokenUsage(fixture.request).tokens).isEqualTo(5000L)
        val usage = fixture.messageRepository.getById("assistant")!!.tokens!!
        assertThat(usage.summaryInput).isEqualTo(4000)
        assertThat(usage.summaryOutput).isEqualTo(1000)
        assertThat(usage.summaryTotal).isEqualTo(5000)
        assertThat(usage.summaryEstimated).isFalse()
        assertThat(usage.total).isAtLeast(5000)
        val rawTokens = fixture.database.messageDao().getById("assistant")!!.tokens!!
        assertThat(json.decodeFromString<TokenUsage>(rawTokens).summaryTotal).isEqualTo(5000)
        assertThat(fixture.prompts).hasSize(1)
        assertThat(fixture.prompts.single().toolChoice).isEqualTo(ProtocolToolChoice.NONE)
        assertThat(PreparedPromptBudgetGate.evaluate(
            fixture.store.getSession("session")!!, fixture.prompts.single(), fixture.preferences, false,
            "local::p0-integration-model",
        )).isInstanceOf(com.promenar.infinity.data.document.ContextBudgetDecision.Allowed::class.java)
        val compressed = fixture.prompts.single().messages.first { it.role == "tool" }.content
        assertThat(compressed).contains("Truncated tool result")
        assertThat(compressed.length).isLessThan(original.length)
        assertThat(fixture.messageRepository.getById("historical-result-0")!!.content).isEqualTo(original)
        assertThat(fixture.executedCalls).isEmpty()
    }

    @Test
    fun `SSE 多次 usage 快照经协议和运行时只持久化本轮最大值`() = runTest {
        val fixture = fixture()
        val body = """
            data: {"choices":[{"delta":{"content":"完成"},"finish_reason":null}]}

            data: {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,"prompt_tokens_details":{"cached_tokens":80,"cache_write_tokens":20,"cache_write_tokens_details":{"ttl_12h_tokens":12}}}}

            data: {"choices":[],"usage":{"prompt_tokens":90,"completion_tokens":10,"total_tokens":100,"prompt_tokens_details":{"cached_tokens":60,"cache_write_tokens":10,"cache_write_tokens_details":{"ttl_12h_tokens":4}}}}

            data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

        """.trimIndent() + "\n"
        val client = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine {
            respond(body, io.ktor.http.HttpStatusCode.OK, io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "text/event-stream"))
        })
        try {
            val protocol = com.promenar.infinity.data.remote.protocol.OpenAIProtocol("https://fixture.invalid/v1/chat/completions", "", "fixture", client)
            val chunks = protocol.sendPrompt(PromptRequest(listOf(com.promenar.infinity.data.remote.protocol.ProtocolMessage("user", "合成输入")), "fixture"))
                .toList()
            assertThat(chunks.filterIsInstance<StreamChunk.Usage>()).hasSize(2)
            fixture.rounds = { chunks }
            ChatGenerationRunner(fixture.runtime()).run(fixture.request) {}
            val usage = fixture.messageRepository.getById("assistant")!!.tokens!!
            assertThat(usage.input).isEqualTo(100)
            assertThat(usage.cacheReadInput).isEqualTo(80)
            assertThat(usage.cacheWriteInput).isEqualTo(20)
            assertThat(usage.cacheWriteLongTtlInput).isEqualTo(12)
        } finally { client.close() }
    }

    @Test
    fun `前缀基础_同一轮两次工具继续保持完整前缀`() = runTest {
        val fixture = prefixFixture()
        fixture.rounds = { index -> if (index <= 2) listOf(
            StreamChunk.ToolCallDelta("prefix-$index", "write_file", """{"path":"/$index.md"}""", 0),
            StreamChunk.Completed(CompletionReason.TOOL_CALLS, listOf("prefix-$index")),
        ) else listOf(StreamChunk.TextDelta("完成"), StreamChunk.Completed(CompletionReason.END_TURN)) }
        ChatGenerationRunner(fixture.runtime()).run(fixture.request) {}
        assertThat(fixture.prompts).hasSize(3)
        assertPrefix(fixture.prompts[0], fixture.prompts[1])
        assertPrefix(fixture.prompts[1], fixture.prompts[2])
    }

    @Test
    fun `前缀基础_相邻用户轮保持完整前缀`() = runTest {
        val fixture = prefixFixture()
        adjacentTurns(fixture)
        assertPrefix(fixture.prompts[0], fixture.prompts[1])
    }

    @Test
    fun `前缀P1_时间跨分钟`() = runTest {
        val fixture = prefixFixture()
        fixture.sessions.updateSessionOptions("session", fixture.store.getSession("session")!!.options.copy(enableTimeInjection = true))
        var now = 1_800_000_000_000L
        io.mockk.mockkConstructor(java.util.Date::class)
        try {
            every { anyConstructed<java.util.Date>().time } answers { now }
            fixture.onPrompt = { index, _ -> if (index == 1) now += 60_000 }
            ChatGenerationRunner(fixture.runtime()).run(fixture.request) {}
            assertThat(fixture.prompts).hasSize(2)
            assertThat(fixture.prompts[0].messages.first { it.role == "user" }.turnContext).contains("System Time")
            assertPrefix(fixture.prompts[0], fixture.prompts[1])
        } finally { io.mockk.unmockkConstructor(java.util.Date::class) }
    }

    @Test
    fun `前缀P1_轮内更新任务计划`() = runTest {
        val fixture = prefixFixture()
        var plan = com.promenar.infinity.data.model.TaskState("plan", "目标", status = "in-progress",
            steps = listOf(com.promenar.infinity.data.model.TaskStep(id = "step", title = "步骤", status = "pending")))
        val tasks = mockk<com.promenar.infinity.domain.repository.ITaskRepository>()
        coEvery { tasks.getPlan("session") } answers { plan }
        fixture.realContextBuilder = ContextBuilder(taskRepository = tasks)
        fixture.onPrompt = { index, _ -> if (index == 1) plan = plan.copy(steps = plan.steps.map { it.copy(status = "completed") }) }
        ChatGenerationRunner(fixture.runtime()).run(fixture.request) {}
        assertThat(fixture.prompts).hasSize(2)
        assertThat(fixture.prompts[0].messages.first { it.role == "user" }.turnContext).contains("目标")
        assertPrefix(fixture.prompts[0], fixture.prompts[1])
    }

    @Test
    fun `前缀P1_轮内更新状态板`() = runTest {
        val fixture = prefixFixture()
        val states = com.promenar.infinity.data.repository.StateBoardRepository(fixture.database)
        states.define("session", "assistant", "状态", listOf(com.promenar.infinity.data.state.StateField(
            "progress", "进度", "number", kotlinx.serialization.json.JsonPrimitive(0),
        )))
        fixture.realContextBuilder = ContextBuilder(stateBoardRepository = states)
        fixture.onPrompt = { index, _ -> if (index == 1) states.update("session", "assistant", listOf(
            com.promenar.infinity.data.state.StateOperation("progress", "set", kotlinx.serialization.json.JsonPrimitive(1)),
        )) }
        ChatGenerationRunner(fixture.runtime()).run(fixture.request) {}
        assertThat(fixture.prompts).hasSize(2)
        assertThat(fixture.prompts[0].messages.first { it.role == "user" }.turnContext).contains("会话状态面板")
        assertPrefix(fixture.prompts[0], fixture.prompts[1])
    }

    @Test
    fun `前缀P1_本条消息点名技能`() = runTest {
        val fixture = prefixFixture()
        val skill = com.promenar.infinity.data.skills.DiscoveredSkill(
            "user:demo", com.promenar.infinity.data.skills.SkillScope.USER, "demo", "演示技能", null, null,
            "/skills/demo", emptyList(), com.promenar.infinity.data.skills.SkillStatus.ENABLED, false, true, null, null,
        )
        fixture.skillCatalog = mockk<com.promenar.infinity.data.skills.SkillCatalog>().also { catalog ->
            coEvery { catalog.scan(any()) } returns com.promenar.infinity.data.skills.SkillSnapshot(null, listOf(skill))
            coEvery { catalog.readDocument(skill) } returns com.promenar.infinity.data.skills.ParsedSkillDocument(
                com.promenar.infinity.data.skills.AgentSkillMetadata("demo", "演示技能"), "只使用合成夹具",
            )
        }
        adjacentTurns(fixture, secondContent = "/demo 后续任务")
        assertThat(fixture.prompts[1].messages.last { it.role == "user" }.turnContext).contains("Requested Skill")
        assertPrefix(fixture.prompts[0], fixture.prompts[1])
    }

    @Test
    fun `本轮快照先持久化且重建运行时按模型投影重放资料`() = runTest {
        val fixture = prefixFixture()
        fixture.sessions.updateSessionOptions("session", fixture.store.getSession("session")!!.options.copy(enableTimeInjection = true))
        fixture.onPrompt = { _, prompt ->
            val sent = prompt.messages.first { it.role == "user" }.turnContext
            val stored = fixture.database.messageDao().getById("user")!!.turnContext
            assertThat(com.promenar.infinity.ui.chat.manager.turnContextForModel(stored)).isEqualTo(sent)
            assertThat(stored).contains("configuration")
            assertThat(sent).doesNotContain("configuration")
        }
        fixture.rounds = { listOf(StreamChunk.TextDelta("完成"), StreamChunk.Completed(CompletionReason.END_TURN)) }
        ChatGenerationRunner(fixture.runtime()).run(fixture.request) {}
        val saved = fixture.messageRepository.getById("user")!!.turnContext
        assertThat(saved).contains("<turn_context>")
        fixture.rebuildFromRepositories()
        fixture.messages.addMessage("session", Message("user-next", MessageRole.USER, "新的问题"))
        fixture.messages.addMessage("session", Message("assistant-next", MessageRole.ASSISTANT, "", parentMessageId = "user-next"))
        ChatGenerationRunner(fixture.runtime()).run(fixture.request.copy(
            userMessageId = "user-next", assistantMessageId = "assistant-next", userContent = "新的问题",
        )) {}
        assertThat(fixture.prompts[1].messages.first { it.role == "user" }.turnContext!!.toByteArray())
            .isEqualTo(com.promenar.infinity.ui.chat.manager.turnContextForModel(saved)!!.toByteArray())
        assertPrefix(fixture.prompts[0], fixture.prompts[1])
    }

    @Test
    fun `自动重试与工具续轮复用冻结块且不重新读取计划`() = runTest {
        val fixture = prefixFixture()
        val tasks = mockk<com.promenar.infinity.domain.repository.ITaskRepository>()
        coEvery { tasks.getPlan("session") } returns com.promenar.infinity.data.model.TaskState("plan", "冻结目标", status = "doing")
        fixture.realContextBuilder = ContextBuilder(taskRepository = tasks)
        fixture.preferences.edit().putInt("stream_retry_limit", 1).commit()
        fixture.rounds = { index -> when (index) {
            1 -> listOf(StreamChunk.Error(code = com.promenar.infinity.domain.generation.GenerationFailureCode.NETWORK, retryable = true))
            2 -> listOf(StreamChunk.ToolCallDelta("retry-tool", "write_file", """{"path":"/retry.md"}""", 0), StreamChunk.Completed(CompletionReason.TOOL_CALLS, listOf("retry-tool")))
            else -> listOf(StreamChunk.TextDelta("完成"), StreamChunk.Completed(CompletionReason.END_TURN))
        } }
        ChatGenerationRunner(fixture.runtime()).run(fixture.request) {}
        assertThat(fixture.prompts).hasSize(3)
        assertThat(fixture.prompts[1]).isEqualTo(fixture.prompts[0])
        assertPrefix(fixture.prompts[1], fixture.prompts[2])
        coVerify(exactly = 1) { tasks.getPlan("session") }
    }

    @Test
    fun `恢复续写复用块而重新生成覆盖对应用户消息的块`() = runTest {
        val fixture = prefixFixture()
        var goal = "原目标"
        val tasks = mockk<com.promenar.infinity.domain.repository.ITaskRepository>()
        coEvery { tasks.getPlan("session") } answers { com.promenar.infinity.data.model.TaskState("plan", goal, status = "doing") }
        fixture.realContextBuilder = ContextBuilder(taskRepository = tasks)
        fixture.rounds = { listOf(StreamChunk.TextDelta("完成"), StreamChunk.Completed(CompletionReason.END_TURN)) }
        ChatGenerationRunner(fixture.runtime()).run(fixture.request) {}
        val original = fixture.messageRepository.getById("user")!!.turnContext
        goal = "新的目标"
        fixture.rebuildFromRepositories()
        fixture.messages.addMessage("session", Message("resumed", MessageRole.ASSISTANT, "", parentMessageId = "user"))
        ChatGenerationRunner(fixture.runtime()).run(fixture.request.copy(assistantMessageId = "resumed")) {}
        assertThat(fixture.messageRepository.getById("user")!!.turnContext).isEqualTo(original)
        coVerify(exactly = 1) { tasks.getPlan("session") }
        fixture.messages.addMessage("session", Message("regenerated", MessageRole.ASSISTANT, "", parentMessageId = "user"))
        ChatGenerationRunner(fixture.runtime()).run(fixture.request.copy(
            assistantMessageId = "regenerated", messageIdsToReplace = setOf("assistant", "resumed"),
        )) {}
        val regenerated = fixture.messageRepository.getById("user")!!.turnContext
        assertThat(regenerated).contains("新的目标")
        assertThat(regenerated).isNotEqualTo(original)
        assertThat(fixture.prompts.last().messages.first { it.role == "user" }.turnContext)
            .isEqualTo(com.promenar.infinity.ui.chat.manager.turnContextForModel(regenerated))
        coVerify(exactly = 2) { tasks.getPlan("session") }
    }

    @Test
    fun `自动重试沿用本轮模型并重验最新权限和工具开关且复用用户块`() = runTest {
        val fixture = prefixFixture()
        fixture.preferences.edit().putInt("stream_retry_limit", 1).commit()
        fixture.onPrompt = { index, _ -> if (index == 1) {
            fixture.sessions.updateSession("session", mapOf("executionMode" to "workspace", "modelId" to "local::changed-model"))
            fixture.sessions.updateSessionOptions("session", fixture.store.getSession("session")!!.options.copy(toolsEnabled = false))
        } }
        fixture.rounds = { index -> if (index == 1) listOf(
            StreamChunk.Error(code = com.promenar.infinity.domain.generation.GenerationFailureCode.NETWORK, retryable = true),
        ) else listOf(StreamChunk.TextDelta("完成"), StreamChunk.Completed(CompletionReason.END_TURN)) }
        ChatGenerationRunner(fixture.runtime()).run(fixture.request) {}
        assertThat(fixture.prompts).hasSize(2)
        assertThat(fixture.prompts.first().tools).isNotEmpty()
        assertThat(fixture.prompts.last().tools).isNull()
        assertThat(fixture.prompts.last().messages.first().content).contains("权限档：工作区")
        assertThat(fixture.resolvedModels.last()).isEqualTo("local::p0-integration-model")
        assertThat(fixture.messageRepository.getById("assistant")!!.modelId).isEqualTo("local::p0-integration-model")
        assertThat(fixture.prompts.last().messages.first { it.role == "user" }.turnContext)
            .isEqualTo(fixture.prompts.first().messages.first { it.role == "user" }.turnContext)
    }

    @Test
    fun `旧用户无块的续写保留空字段且不补生成当前时间`() = runTest {
        val fixture = fixture(priorToolResults = listOf("升级前的工具结果"))
        fixture.realContextBuilder = ContextBuilder()
        fixture.rounds = { listOf(StreamChunk.TextDelta("续写完成"), StreamChunk.Completed(CompletionReason.END_TURN)) }
        ChatGenerationRunner(fixture.runtime()).run(fixture.request) {}
        assertThat(fixture.database.messageDao().getById("user")!!.turnContext).isNull()
        assertThat(fixture.prompts.single().messages.first { it.role == "user" }.turnContext).isNull()
        assertThat(fixture.prompts.single().messages.none { it.content.contains("System Time") }).isTrue()
    }

    @Test
    fun `删除全部旧回复后重新生成也覆盖用户块`() = runTest {
        val fixture = prefixFixture()
        fixture.rounds = { listOf(StreamChunk.TextDelta("完成"), StreamChunk.Completed(CompletionReason.END_TURN)) }
        ChatGenerationRunner(fixture.runtime()).run(fixture.request) {}
        val original = fixture.messageRepository.getById("user")!!.turnContext
        fixture.messages.deleteMessage("session", "assistant")
        fixture.messages.addMessage("session", Message("new-answer", MessageRole.ASSISTANT, "", parentMessageId = "user"))
        ChatGenerationRunner(fixture.runtime()).run(fixture.request.copy(assistantMessageId = "new-answer")) {}
        val saved = fixture.messageRepository.getById("user")!!.turnContext
        assertThat(saved).isNotEqualTo(original)
        assertThat(saved).contains("new-answer")
    }

    @Test
    fun `重新生成失败恢复原用户块及其对应旧回复`() = runTest {
        val fixture = prefixFixture()
        fixture.rounds = { listOf(StreamChunk.TextDelta("原回答"), StreamChunk.Completed(CompletionReason.END_TURN)) }
        ChatGenerationRunner(fixture.runtime()).run(fixture.request) {}
        val original = fixture.messageRepository.getById("user")!!.turnContext
        fixture.preferences.edit().putInt("stream_retry_limit", 0).commit()
        fixture.messages.addMessage("session", Message("failed-regenerate", MessageRole.ASSISTANT, "", parentMessageId = "user"))
        fixture.rounds = { listOf(StreamChunk.Error(code = com.promenar.infinity.domain.generation.GenerationFailureCode.NETWORK, retryable = false)) }
        try {
            ChatGenerationRunner(fixture.runtime()).run(fixture.request.copy(
                assistantMessageId = "failed-regenerate", messageIdsToReplace = setOf("assistant"),
            )) {}
        } catch (_: com.promenar.infinity.domain.generation.GenerationFailedException) { }
        assertThat(fixture.messageRepository.getById("user")!!.turnContext).isEqualTo(original)
        assertThat(fixture.messageRepository.getById("assistant")!!.content).isEqualTo("原回答")
        assertThat(fixture.messageRepository.getById("failed-regenerate")).isNull()
    }

    @Test
    fun `状态工具实际读到的新版本经续轮与重建上下文继承且初始块不变`() = runTest {
        val fixture = prefixFixture()
        val states = com.promenar.infinity.data.repository.StateBoardRepository(fixture.database)
        fixture.stateBoards = states
        fixture.messages.addMessage("session", Message("excluded", MessageRole.ASSISTANT, "旧状态读取", toolCalls = listOf(ToolCall("old-state", "state_get", "{}"))))
        fixture.messages.addMessage("session", Message("excluded-result", MessageRole.TOOL,
            """{"action":"state_get","revision":999,"board":null}""", toolCallId = "old-state", name = "state_get", parentMessageId = "excluded"))
        states.define("session", "assistant", "面板", listOf(com.promenar.infinity.data.state.StateField("hp", "生命值", "number", kotlinx.serialization.json.JsonPrimitive(80))))
        fixture.realContextBuilder = ContextBuilder(stateBoardRepository = states)
        val get = com.promenar.infinity.ui.chat.manager.skills.StateGetSkill(states)
        val update = com.promenar.infinity.ui.chat.manager.skills.StateUpdateSkill(states)
        val definitions = listOf(get, update)
        fixture.advertisedTools = definitions.map { ProtocolTool(
            function = ProtocolToolFunction(it.name, it.description, it.parametersSchema), runtimeToolId = it.id, risk = it.risk,
        ) }
        val names = definitions.map { it.name }.toSet()
        fixture.preferences.edit().putStringSet("enabled_skills", names).putStringSet("known_builtin_skills", names).commit()
        fixture.toolAction = { owner, call ->
            val context = object : com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext {
                override val sessionId = "session"
                override val agentId = "agent"
                override val workspacePath: String? = null
                override val workspaceRootUuid = "root"
                override val assistantMessageId = owner
            }
            val result = definitions.single { it.name == call.name }.execute(
                json.parseToJsonElement(call.arguments) as kotlinx.serialization.json.JsonObject, context,
            )
            assertThat(result.status).isEqualTo(if (call.id == "stale-update") "error" else "success")
            result.content
        }
        fixture.onPrompt = { index, _ -> if (index == 1) states.edit("session", states.get("session")!!.revision,
            listOf(com.promenar.infinity.data.state.StateOperation("hp", "set", kotlinx.serialization.json.JsonPrimitive(90)))) }
        fixture.rounds = { index -> when(index) {
            1 -> listOf(StreamChunk.ToolCallDelta("stale-update", "state_update", """{"operations":[{"key":"hp","op":"increment","value":-5}]}""", 0),
                StreamChunk.Completed(CompletionReason.TOOL_CALLS, listOf("stale-update")))
            2 -> listOf(StreamChunk.ToolCallDelta("get", "state_get", "{}", 0), StreamChunk.Completed(CompletionReason.TOOL_CALLS, listOf("get")))
            3 -> listOf(StreamChunk.ToolCallDelta("update", "state_update", """{"operations":[{"key":"hp","op":"increment","value":-5}]}""", 0),
                StreamChunk.Completed(CompletionReason.TOOL_CALLS, listOf("update")))
            else -> listOf(StreamChunk.TextDelta("完成"), StreamChunk.Completed(CompletionReason.END_TURN))
        } }
        ChatGenerationRunner(fixture.runtime()).run(fixture.request.copy(messageIdsToReplace = setOf("excluded"))) {}
        assertThat(fixture.prompts).hasSize(4)
        assertThat(states.get("session")!!.board!!.fields.single().value).isEqualTo(kotlinx.serialization.json.JsonPrimitive(85))
        fixture.prompts.zipWithNext().forEach { (before, after) -> assertPrefix(before, after) }
    }

    @Test
    fun `用户块持久化失败时不得发送首个模型请求`() = runTest {
        val fixture = prefixFixture()
        fixture.useMessageRepositoryForManager(object : com.promenar.infinity.data.repository.IMessageRepository by fixture.messageRepository {
            override suspend fun updatePartial(messageId: String, updates: Map<String, Any?>) {
                if ("turnContext" in updates) throw java.io.IOException("模拟上下文写入失败")
                fixture.messageRepository.updatePartial(messageId, updates)
            }
        })
        val result = runCatching { ChatGenerationRunner(fixture.runtime()).run(fixture.request) {} }
        assertThat(result.isFailure).isTrue()
        assertThat(fixture.prompts).isEmpty()
        assertThat(fixture.database.messageDao().getById("user")!!.turnContext).isNull()
    }

    @Test
    fun `续轮模型字段写入失败不能把上一助手正文复制到新失败占位`() = runTest {
        val fixture = prefixFixture()
        fixture.useMessageRepositoryForManager(object : com.promenar.infinity.data.repository.IMessageRepository by fixture.messageRepository {
            override suspend fun updatePartial(messageId: String, updates: Map<String, Any?>) {
                if (messageId != "assistant" && "requestInstruction" in updates) throw java.io.IOException("模拟续轮字段写入失败")
                fixture.messageRepository.updatePartial(messageId, updates)
            }
        })
        fixture.rounds = { index -> if (index == 1) listOf(
            StreamChunk.TextDelta("阶段正文"),
            StreamChunk.ToolCallDelta("continue-failure", "write_file", """{"path":"/continue.md"}""", 0),
            StreamChunk.Completed(CompletionReason.TOOL_CALLS, listOf("continue-failure")),
        ) else listOf(StreamChunk.TextDelta("完成"), StreamChunk.Completed(CompletionReason.END_TURN)) }
        val result = runCatching { ChatGenerationRunner(fixture.runtime()).run(fixture.request) {} }
        assertThat(result.exceptionOrNull()?.message).isEqualTo("模拟续轮字段写入失败")
        assertThat(fixture.prompts).hasSize(1)
        assertThat(fixture.messageRepository.getById("assistant")!!.content).isEqualTo("阶段正文")
        val next = fixture.messageRepository.getBySession("session").single { it.role == MessageRole.ASSISTANT && it.id != "assistant" }
        assertThat(next.content).isEmpty()
        assertThat(next.toolCalls).isEmpty()
        assertThat(fixture.prompts).hasSize(1)
    }

    @Test
    fun `发送前配置刷新拒绝可重试且不得提前删除重新生成目标`() = runTest {
        val fixture = prefixFixture()
        fixture.rounds = { listOf(StreamChunk.TextDelta("原回答"), StreamChunk.Completed(CompletionReason.END_TURN)) }
        ChatGenerationRunner(fixture.runtime()).run(fixture.request) {}
        fixture.messages.addMessage("session", Message("reroute-target", MessageRole.ASSISTANT, "", parentMessageId = "user"))
        val request = fixture.request.copy(assistantMessageId = "reroute-target", messageIdsToReplace = setOf("assistant"))
        val runtime = fixture.runtime()
        runtime.prepare(request)
        assertThat(runtime.buildContext(request)).isEqualTo(GenerationPreparationOutcome.Ready)
        fixture.providerUnavailable = true
        assertThat(runtime.stream(request, 0).toList().single()).isInstanceOf(com.promenar.infinity.domain.generation.GenerationChunk.Failure::class.java)
        assertThat(fixture.messageRepository.getById("reroute-target")).isNotNull()
        assertThat(fixture.messageRepository.getById("assistant")!!.content).isEqualTo("原回答")
        fixture.providerUnavailable = false
        assertThat(runtime.stream(request, 0).toList().any { it is com.promenar.infinity.domain.generation.GenerationChunk.Text }).isTrue()
        assertThat(fixture.prompts).hasSize(2)
    }

    private suspend fun TestScope.prefixFixture(): Fixture = fixture().also {
        it.realContextBuilder = ContextBuilder()
        it.sessions.updateSessionOptions("session", it.store.getSession("session")!!.options.copy(enableTimeInjection = false))
    }

    private suspend fun adjacentTurns(fixture: Fixture, secondContent: String = "后续任务") {
        fixture.rounds = { listOf(StreamChunk.TextDelta("完成"), StreamChunk.Completed(CompletionReason.END_TURN)) }
        ChatGenerationRunner(fixture.runtime()).run(fixture.request) {}
        fixture.messages.addMessage("session", Message("user-next", MessageRole.USER, secondContent))
        fixture.messages.addMessage("session", Message("assistant-next", MessageRole.ASSISTANT, "", parentMessageId = "user-next"))
        ChatGenerationRunner(fixture.runtime()).run(fixture.request.copy(
            userMessageId = "user-next", assistantMessageId = "assistant-next", userContent = secondContent,
        )) {}
        assertThat(fixture.prompts).hasSize(2)
    }

    private fun assertPrefix(before: PromptRequest, after: PromptRequest) {
        fun system(request: PromptRequest) = json.encodeToString(request.messages.filter { it.role == "system" }).toByteArray(Charsets.UTF_8)
        fun tools(request: PromptRequest) = json.encodeToString(request.tools.orEmpty()).toByteArray(Charsets.UTF_8)
        fun history(request: PromptRequest) = request.messages.filter { it.role != "system" }.map { json.encodeToString(it) }
        com.google.common.truth.Truth.assertWithMessage("工具定义字节必须一致").that(tools(after)).isEqualTo(tools(before))
        com.google.common.truth.Truth.assertWithMessage("系统提示字节必须一致").that(system(after)).isEqualTo(system(before))
        val previousHistory = history(before)
        com.google.common.truth.Truth.assertWithMessage("前一次消息序列必须是后一次的前缀")
            .that(history(after).take(previousHistory.size)).containsExactlyElementsIn(previousHistory).inOrder()
    }

    private suspend fun TestScope.fixture(
        mode: String = "auto",
        confirmed: Boolean = true,
        outputStore: ToolOutputStore? = null,
        priorToolResults: List<String> = emptyList(),
        historicalTurn: Boolean = false,
    ): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java).allowMainThreadQueries().build()
        databases += database
        return Fixture(this, context, database, outputStore).also { fixture ->
            fixture.preferences.edit().putInt("loop_limit", 0)
                .putBoolean(UnlimitedAutomationConsent.KEY, confirmed)
                .putStringSet("enabled_skills", setOf("write_file"))
                .putStringSet("known_builtin_skills", setOf("write_file"))
                .commit()
            fixture.sessions.addSession(Session(
                id = "session", agentId = "agent", modelId = "local::p0-integration-model", executionMode = mode,
                options = SessionOptions(toolsEnabled = true), ragOptions = RagOptions(enableMemory = false, enableDocs = false),
                inferenceParams = InferenceParams(maxTokens = 64, keepRecentTurns = 20), workspaceRootUuid = "root",
            ))
            val historicalUser = if (historicalTurn) "historical-user" else "user"
            fixture.messages.addMessage("session", Message(historicalUser, MessageRole.USER, "完成工具任务"))
            priorToolResults.forEachIndexed { index, content ->
                val assistantId = "historical-assistant-$index"
                val callId = "historical-call-$index"
                fixture.messages.addMessage("session", Message(
                    assistantId, MessageRole.ASSISTANT, "", tokens = TokenUsage(1, 1, 2), parentMessageId = historicalUser,
                    toolCalls = listOf(ToolCall(callId, "write_file", """{"path":"/history-$index.md"}""")),
                ))
                fixture.messages.addMessage("session", Message(
                    "historical-result-$index", MessageRole.TOOL, content,
                    toolCallId = callId, parentMessageId = assistantId, name = "write_file",
                ))
            }
            if (historicalTurn) fixture.messages.addMessage("session", Message("user", MessageRole.USER, "当前用户请求"))
            fixture.messages.addMessage("session", Message("assistant", MessageRole.ASSISTANT, "", parentMessageId = "user"))
        }
    }

    /** 只替换外部工具副作用；审批策略、台账、消息与请求构建均使用生产实现。 */
    private class Fixture(
        private val scope: TestScope,
        val context: Context,
        val database: InfinityDatabase,
        private val outputStore: ToolOutputStore?,
    ) {
        val preferencesName = "p0-integration-${UUID.randomUUID()}"
        val preferences: SharedPreferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val sessionRepository = SessionRepository(database.sessionDao(), database.messageDao())
        val messageRepository = MessageRepository(database.messageDao())
        val ledger = ToolExecutionLedgerRepository(database)
        var store = ChatStore()
            private set
        var messages = MessageManager(store, messageRepository, sessionRepository, scope)
            private set
        var sessions = SessionManager(store, sessionRepository)
            private set
        val interactions = GenerationInteractionStore()
        val prompts = mutableListOf<PromptRequest>()
        val executedCalls = mutableListOf<String>()
        val summaryPrompts = mutableListOf<PromptRequest>()
        val resolvedModels = mutableListOf<String>()
        val postEvents = mutableListOf<String>()
        val builtContexts = mutableListOf<com.promenar.infinity.ui.chat.manager.ContextBuilderParams>()
        var providerProtocolType: ProtocolType = ProtocolType.Local
        var providerUnavailable = false
        var skillCatalog: com.promenar.infinity.data.skills.SkillCatalog? = null
        var realContextBuilder: ContextBuilder? = null
        var stateBoards: com.promenar.infinity.data.repository.StateBoardRepository? = null
        var toolAction: (suspend (String, ToolCall) -> String)? = null
        var summaryResponse = PromptResponse("")
        var toolResult = "完整工具结果"
        var onPrompt: suspend (Int, PromptRequest) -> Unit = { _, _ -> }
        var onContext: suspend (Int) -> Unit = {}
        var rounds: (Int) -> List<StreamChunk> = { index ->
            if (index == 1) listOf(
                StreamChunk.ToolCallDelta("call-1", "write_file", """{"path":"/a.md"}""", 0),
                StreamChunk.Completed(CompletionReason.TOOL_CALLS, listOf("call-1")),
            ) else listOf(StreamChunk.TextDelta("任务完成"), StreamChunk.Completed(CompletionReason.END_TURN))
        }
        var advertisedTools = listOf(ProtocolTool(
            function = ProtocolToolFunction("write_file", "写入文件", """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""),
            risk = ToolRisk.FILE_WRITE, runtimeToolId = "write_file",
        ))
        val request = GenerationRequest("session", "assistant", "user", "完成工具任务", emptyList(), GenerationRuntimePolicy.BACKGROUND_ALLOWED)
        val executor = mockk<ToolExecutor>()

        init {
            coEvery { executor.executeTools(any(), any(), any(), any(), any(), any()) } coAnswers {
                val sessionId = firstArg<String>()
                val messageId = secondArg<String>()
                val calls = thirdArg<List<ToolCall>>()
                val allowed = arg<Set<String>>(3)
                val definitions = arg<List<ProtocolTool>>(4)
                for (call in calls) {
                    val key = ToolExecutionKey(sessionId, messageId, call.id)
                    val identity = ledger.invocationIdentity(key) ?: (ToolInvocationIdentityFactory.fromPreparedToolCall(
                        call, definitions.single { it.function.name == call.name }, call.id !in allowed,
                    ) as ToolInvocationIdentityResolution.Valid).identity
                    ledger.register(key, identity)
                    if (call.id in allowed && ledger.claim(key, identity)) {
                        executedCalls += call.id
                        val result = ledger.finishWithResult(key, call.name, toolAction?.invoke(messageId, call) ?: toolResult, null, ToolExecutionOutcome.Succeeded)
                        result?.let { messages.mirrorPersistedMessage(sessionId, it) }
                    }
                }
            }
            coEvery { executor.rejectTools(any(), any(), any(), any()) } coAnswers {
                val sessionId = firstArg<String>()
                val messageId = secondArg<String>()
                for (call in thirdArg<List<ToolCall>>()) messages.addMessage(sessionId, Message(
                    id = "rejected-$messageId-${call.id}", role = MessageRole.TOOL, content = arg<String>(3),
                    toolCallId = call.id, parentMessageId = messageId, name = call.name,
                ))
            }
        }

        fun useMessageRepositoryForManager(repository: com.promenar.infinity.data.repository.IMessageRepository) {
            messages = MessageManager(store, repository, sessionRepository, scope)
        }

        suspend fun rebuildFromRepositories() {
            val restored = requireNotNull(sessionRepository.getById("session"))
            store = ChatStore().apply { upsertSession(restored) }
            messages = MessageManager(store, messageRepository, sessionRepository, scope)
            sessions = SessionManager(store, sessionRepository)
        }

        fun runtime(pipeline: ContextCompactionPipeline = ContextCompactionPipeline()): DefaultChatGenerationRuntime {
            val protocol = object : LlmProtocol {
                override val protocolType = ProtocolType.Local
                override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = flow {
                    prompts += request
                    val round = prompts.size
                    onPrompt(round, request)
                    rounds(round).forEach { emit(it) }
                }
                override suspend fun sendPromptSync(request: PromptRequest): PromptResponse {
                    summaryPrompts += request
                    postEvents += if (request.messages.any { it.content.startsWith("你是一个信息压缩助手") }) "history-summary" else "tool-summary"
                    return summaryResponse
                }
                override fun cancel() = Unit
            }
            val provider = LlmProvider(protocol)
            val router = object : ProviderRequestRouter {
                override fun resolve(modelId: String): ProviderResolution {
                    resolvedModels += modelId
                    if (providerUnavailable) return ProviderResolution.Failure(
                        com.promenar.infinity.data.remote.ProviderResolutionError.MODEL_NOT_FOUND, modelId = modelId,
                    )
                    return ProviderResolution.Success(ResolvedProviderModel(
                        modelId, "p0-integration-model", "local", "Local",
                        UnifiedProviderConfig(providerProtocolType, "", "", "p0-integration-model"),
                    ))
                }
                override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient = UnifiedLlmClient(
                    providerConfigResolver = { resolved.config }, protocolFactory = { protocol },
                )
            }
            val builder = mockk<ContextBuilder>()
            var contextRound = 0
            coEvery { builder.buildContext(any()) } coAnswers {
                builtContexts += firstArg<com.promenar.infinity.ui.chat.manager.ContextBuilderParams>()
                onContext(++contextRound)
                ContextBuilderResult("", "", emptyList(), emptyList(), null, "执行用户任务")
            }
            val registry = mockk<SkillRegistry>()
            every { registry.getAllTools(any()) } returns advertisedTools
            return DefaultChatGenerationRuntime(
                settings = preferences, applicationScope = scope, store = store,
                agentRepository = mockk<IAgentRepository> { coEvery { getById(any()) } returns null },
                configResolver = AgentConfigResolver(preferences),
                routeGate = ChatProviderRouteGate(router, UnconfinedTestDispatcher(scope.testScheduler)),
                contextBuilder = realContextBuilder ?: builder, messageManager = messages,
                localProviderFactory = { provider }, provider = provider, toolLedger = ledger, toolExecutor = executor,
                postProcessor = mockk<com.promenar.infinity.ui.chat.manager.PostProcessor>(relaxed = true) {
                    coEvery { archiveMessagesToRag(any(), any(), any(), any()) } coAnswers { postEvents += "archive" }
                }, memoryManager = null,
                summaryManager = RoutedToolResultSummarizer(ChatProviderRouteGate(router)) { provider }.historySummaryManager(provider),
                sessionManager = sessions, contentStrategy = DefaultChatGenerationContentStrategy(preferences, registry),
                ui = mockk(relaxed = true), outputStore = outputStore, interactionStore = interactions,
                compactionPipeline = pipeline, skillCatalog = skillCatalog, stateBoardRepository = stateBoards,
            )
        }
    }
}
