package com.promenar.infinity.data.generation

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.data.model.SessionOptions
import com.promenar.infinity.data.model.InferenceParams
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.PostProcessStatus
import com.promenar.infinity.data.model.UpdateMessageOptions
import com.promenar.infinity.data.model.TokenUsage
import com.promenar.infinity.data.model.MessageDocumentAttachment
import com.promenar.infinity.data.remote.DefaultProviderRequestRouter
import com.promenar.infinity.data.remote.ProviderRequestRouter
import com.promenar.infinity.data.remote.ProviderResolution
import com.promenar.infinity.data.remote.ResolvedProviderModel
import com.promenar.infinity.data.remote.UnifiedProviderConfig
import com.promenar.infinity.data.remote.UnifiedLlmClient
import com.promenar.infinity.data.remote.protocol.LlmProtocol
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.PromptResponse
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.ProtocolToolChoice
import com.promenar.infinity.data.remote.protocol.ProtocolToolFunction
import com.promenar.infinity.data.remote.protocol.StreamChunk
import com.promenar.infinity.data.remote.provider.LlmProvider
import com.promenar.infinity.data.rag.MemoryManager
import com.promenar.infinity.data.repository.IMessageRepository
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.data.repository.ToolExecutionLedger
import com.promenar.infinity.domain.generation.GenerationRequest
import com.promenar.infinity.domain.generation.GenerationChunk
import com.promenar.infinity.domain.generation.GenerationFailure
import com.promenar.infinity.domain.generation.GenerationFailureCode
import com.promenar.infinity.domain.generation.GenerationFailureCodec
import com.promenar.infinity.domain.generation.GenerationRuntimePolicy
import com.promenar.infinity.domain.generation.GenerationPreparationOutcome
import com.promenar.infinity.domain.generation.GenerationSnapshot
import com.promenar.infinity.domain.generation.GenerationTerminalStatus
import com.promenar.infinity.domain.generation.GenerationToolCall
import com.promenar.infinity.domain.generation.GenerationToolDecision
import com.promenar.infinity.domain.repository.IAgentRepository
import com.promenar.infinity.domain.usecase.AgentConfigResolver
import com.promenar.infinity.ui.chat.ChatState
import com.promenar.infinity.ui.chat.ChatStore
import com.promenar.infinity.ui.chat.manager.ContextBuilder
import com.promenar.infinity.ui.chat.manager.ContextBuilderResult
import com.promenar.infinity.ui.chat.manager.MessageManager
import com.promenar.infinity.ui.chat.manager.PostProcessor
import com.promenar.infinity.ui.chat.manager.SessionManager
import com.promenar.infinity.ui.chat.manager.SummaryManager
import com.promenar.infinity.ui.chat.manager.ToolExecutor
import com.promenar.infinity.ui.chat.manager.registry.SkillRegistry
import com.promenar.infinity.data.model.TurnMemoryState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.junit.Test

class DefaultChatGenerationRuntimeTest {
    @Test
    fun `只有引用的当前用户轮次写入记忆时包含引文批注且不重复格式化`() = runTest {
        val memory = mockk<MemoryManager> { every { canEmbed } returns true }
        val written = mutableListOf<String>()
        coEvery { memory.addTurnToMemory(any(), any(), any(), any(), any()) } coAnswers {
            written += arg<String>(1); 1
        }
        val fixture = postProcessFixture(ragOptions = RagOptions(enableMemory = true, enableDocs = false), memoryManager = memory)
        val quote = com.promenar.infinity.data.model.MessageQuote("source", "引文", "批注")
        fixture.store.updateSession("session") { it.copy(messages = listOf(Message("user", MessageRole.USER, "", quotes = listOf(quote))) + it.messages) }
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        fixture.messageManager.runTurnMemoryWrites()

        fixture.runtime.postProcess(fixture.request.copy(userContent = ""), GenerationSnapshot(content = "answer"))
        fixture.runtime.postProcess(fixture.request.copy(userContent = "> 引文\n\n批注"), GenerationSnapshot(content = "answer"))

        assertThat(written).containsExactly("> 引文\n\n批注", "> 引文\n\n批注").inOrder()
    }

    @Test
    fun `摘要与归档输入包含用户引用而压缩恢复源保留结构化原消息`() = runTest {
        val fixture = postProcessFixture(ragOptions = RagOptions(enableMemory = true, enableDocs = false),
            inferenceParams = InferenceParams(keepRecentTurns = 1, autoSummaryThreshold = 0.0), messageCount = 6)
        val quote = com.promenar.infinity.data.model.MessageQuote("source", "引文", "批注")
        fixture.store.updateSession("session") { session -> session.copy(messages = session.messages.map {
            if (it.id == "message-0") it.copy(content = "", quotes = listOf(quote)) else it
        }) }
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        val archived = slot<List<Message>>()
        coEvery { fixture.postProcessor.archiveMessagesToRag(any(), capture(archived), any(), any()) } returns Unit
        val summarized = slot<List<Message>>()
        coEvery { fixture.summaryManager.summarizeOrNull(any(), capture(summarized), any(), any(), any(), any()) } returns "摘要"

        fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))

        assertThat(archived.captured.first().content).isEqualTo("> 引文\n\n批注")
        assertThat(summarized.captured.first().content).isEqualTo("> 引文\n\n批注")
        coVerify { fixture.sessionManager.commitContextCompaction(any(), any(), any(), match {
            it.messages.first().content == "" && it.messages.first().quotes == listOf(quote)
        }) }
    }

    @Test fun `主聊天每轮环境模型与实际请求一致且切换后不残留旧ID`() = runTest {
        val requests = mutableListOf<PromptRequest>()
        val local = mockk<LlmProvider> {
            coEvery { sendPrompt(any()) } answers {
                requests += firstArg<PromptRequest>()
                kotlinx.coroutines.flow.flowOf(StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN))
            }
        }
        val catalog = mockk<com.promenar.infinity.data.skills.SkillCatalog> {
            coEvery { scan(any()) } returns com.promenar.infinity.data.skills.SkillSnapshot(null, emptyList())
        }
        val fixture = skillsFixture(catalog, localProvider = local)
        for (model in listOf("skills", "switched-model")) {
            fixture.store.updateSession("skills-session") { it.copy(modelId = "local::$model") }
            assertThat(fixture.runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)
            fixture.runtime.stream(fixture.request, 0).toList()
            assertThat(requests.last().model).isEqualTo(model)
            val prompt = requests.last().messages.first().content
            assertThat(prompt).contains("## Runtime Environment\n- Environment: Infinity Workbench\n")
            assertThat(prompt).contains("\n- Model: $model\n")
            assertThat(prompt).doesNotContain("local::")
        }
        assertThat(requests.last().messages.first().content).doesNotContain("- Model: skills\n")
        assertThat(requests.first().messages.first().content).doesNotContain("switched-model")
    }

    @Test fun `手动估算上下文使用路由后的模型并跟随下一轮切换`() = runTest {
        val catalog = mockk<com.promenar.infinity.data.skills.SkillCatalog> {
            coEvery { scan(any()) } returns com.promenar.infinity.data.skills.SkillSnapshot(null, emptyList())
        }
        val fixture = skillsFixture(catalog)
        fixture.runtime.estimateContext("skills-session")
        assertThat(fixture.prompts.last()).contains("- Model: skills\n")
        fixture.store.updateSession("skills-session") { it.copy(modelId = "local::new-model") }
        fixture.runtime.estimateContext("skills-session")
        assertThat(fixture.prompts.last()).contains("- Model: new-model\n")
        assertThat(fixture.prompts.last()).doesNotContain("- Model: skills\n")
    }

    @Test fun `下一步骤的上下文包含已合并的子代理完成通知`() = runTest {
        val bridge = com.promenar.infinity.data.trigger.SubagentCompletionSteering(backgroundScope)
        val builder = mockk<ContextBuilder> {
            coEvery { buildContext(any()) } returns ContextBuilderResult("", "", emptyList(), emptyList(), null, "system")
        }
        val catalog = mockk<com.promenar.infinity.data.skills.SkillCatalog> {
            coEvery { scan(any()) } returns com.promenar.infinity.data.skills.SkillSnapshot(null, emptyList())
        }
        val fixture = skillsFixture(catalog, contextBuilder = builder, subagentCompletions = bridge)
        val pending = bridge.enqueue(com.promenar.infinity.domain.trigger.SystemTurnRequest(
            "skills-session", com.promenar.infinity.domain.trigger.SystemTurnKind.SUBAGENT_DONE,
            "sa_done", "检索", "结论正文", sequence = 1,
        ), com.promenar.infinity.data.trigger.SystemTurnAdmissionTicket())

        assertThat(fixture.runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)

        assertThat(pending.outcome.await()).isEqualTo(com.promenar.infinity.domain.trigger.SystemTurnOutcome.Completed)
        coVerify(exactly = 1) {
            builder.buildContext(match { params -> params.session.messages.any { it.content.contains("结论正文") } })
        }
        assertThat(fixture.store.getSession("skills-session")!!.messages.count {
            it.name == com.promenar.infinity.domain.trigger.SystemTriggerMessage.MESSAGE_NAME
        }).isEqualTo(1)
        val completion = fixture.store.getSession("skills-session")!!.messages.last()
        assertThat(com.promenar.infinity.domain.trigger.SystemTriggerMessage.parse(completion.content)!!.consumerAssistantId)
            .isEqualTo("assistant")
    }

    @Test fun `提问事务返回时取消也关闭已创建的待回答状态`() = runTest {
        val ledger = mockk<com.promenar.infinity.data.repository.AskUserLedgerRepository>()
        coEvery { ledger.consumeResumption(any(), any()) } returns false
        coEvery { ledger.cancel("skills-session") } returns emptyList()
        coEvery { ledger.createBatch(any(), any(), any(), any()) } coAnswers {
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
            com.promenar.infinity.data.repository.AskUserBatchResult(null, emptyList())
        }
        val catalog = mockk<com.promenar.infinity.data.skills.SkillCatalog> {
            coEvery { scan(any()) } returns com.promenar.infinity.data.skills.SkillSnapshot(null, emptyList())
        }
        val fixture = skillsFixture(catalog, toolNames = listOf("ask_user"), askLedger = ledger)
        assertThat(fixture.runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)
        val task = kotlinx.coroutines.CoroutineScope(coroutineContext).launch {
            fixture.runtime.handleTools(fixture.request, listOf(GenerationToolCall("q", "ask_user", "{}")))
        }
        task.join()

        assertThat(task.isCancelled).isTrue()
        coVerify(exactly = 1) { ledger.cancel("skills-session") }
        coVerify(exactly = 0) { fixture.executor.executeTools(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `预算拒绝首个回答续跑回合前消费恢复标记`() = runTest {
        val ledger = mockk<com.promenar.infinity.data.repository.AskUserLedgerRepository>()
        coEvery { ledger.consumeResumption(any(), any()) } returns true
        val catalog = mockk<com.promenar.infinity.data.skills.SkillCatalog> {
            coEvery { scan(any()) } returns com.promenar.infinity.data.skills.SkillSnapshot(null, emptyList())
        }
        val fixture = skillsFixture(catalog, askLedger = ledger)
        val call = GenerationToolCall("limited", "read_file", "{}")

        fixture.runtime.rejectToolCalls(fixture.request, listOf(call), "工具预算已耗尽")

        io.mockk.coVerifyOrder {
            ledger.consumeResumption("skills-session", "assistant")
            fixture.executor.rejectTools("skills-session", "assistant", any(), "工具预算已耗尽")
        }
    }

    @Test fun `回答续跑准备阶段不能消费持久化恢复标记`() = runTest {
        val ledger = mockk<com.promenar.infinity.data.repository.AskUserLedgerRepository>()
        coEvery { ledger.consumeResumption(any(), any()) } returns true
        val catalog = mockk<com.promenar.infinity.data.skills.SkillCatalog> { coEvery { scan(any()) } returns com.promenar.infinity.data.skills.SkillSnapshot(null, emptyList()) }
        val fixture = skillsFixture(catalog, askLedger = ledger)
        fixture.runtime.prepare(fixture.request)
        coVerify(exactly = 0) { ledger.consumeResumption(any(), any()) }
    }
    private fun discoveredSkill(name: String, scope: com.promenar.infinity.data.skills.SkillScope = com.promenar.infinity.data.skills.SkillScope.USER) =
        com.promenar.infinity.data.skills.DiscoveredSkill(
            "${scope.name}:$name", scope, name, "Description $name", null, null,
            if (scope == com.promenar.infinity.data.skills.SkillScope.USER) "/skills/$name" else "/.agents/skills/$name",
            emptyList(), com.promenar.infinity.data.skills.SkillStatus.ENABLED, false, true, null, null,
        )

    private data class SkillsFixture(
        val runtime: DefaultChatGenerationRuntime,
        val request: GenerationRequest,
        val prompts: List<String>,
        val toolThreads: List<Thread> = emptyList(),
        val executor: ToolExecutor,
        val store: ChatStore,
    )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun kotlinx.coroutines.test.TestScope.skillsFixture(
        catalog: com.promenar.infinity.data.skills.SkillCatalog,
        workspace: suspend (String) -> com.promenar.infinity.data.workspace.SessionWorkspace = { com.promenar.infinity.data.workspace.SessionWorkspace.Private },
        toolNames: List<String> = listOf("read_file"),
        contextTokens: Int = 100_000,
        content: String = "使用 \$demo",
        contextBuilder: ContextBuilder = ContextBuilder(),
        stateBoardRepository: com.promenar.infinity.data.repository.StateBoardRepository? = null,
        askLedger: com.promenar.infinity.data.repository.AskUserLedgerRepository? = null,
        subagentCompletions: com.promenar.infinity.data.trigger.SubagentCompletionSteering? = null,
        knowledgeConfig: com.promenar.infinity.data.agent.AgentRetrievalConfig? = null,
        knowledgeCatalog: com.promenar.infinity.data.knowledge.KnowledgeCatalogSource? = null,
        knowledgePlanner: com.promenar.infinity.data.knowledge.KnowledgeAccessPlanner? = null,
        knowledgeTurns: com.promenar.infinity.data.knowledge.KnowledgeTurnState? = null,
        toolLedger: com.promenar.infinity.data.repository.ToolExecutionLedger = mockk(relaxed = true),
        localProvider: LlmProvider? = null,
        timedGrants: com.promenar.infinity.data.approval.TimedApprovalGrantStore? = null,
        linuxFiles: com.promenar.infinity.ui.chat.manager.skills.LinuxSessionExternalWorkspaceAccess? = null,
    ): SkillsFixture {
        val settings = mockk<SharedPreferences> {
            every { getInt(any(), any()) } answers { secondArg() }
            every { getInt("model_info_local::skills_context", any()) } returns contextTokens
            every { getString(any(), any()) } answers { secondArg() }
            every { getStringSet(any(), any()) } returns emptySet()
            every { getFloat(any(), any()) } answers { secondArg() }
        }
        val session = Session("skills-session", "agent", modelId = "local::skills", inferenceParams = InferenceParams(maxTokens = 16),
            options = SessionOptions(enableTimeInjection = false),
            messages = listOf(Message("user", MessageRole.USER, content), Message("assistant", MessageRole.ASSISTANT, "")))
        val store = ChatStore().apply { update { ChatState(sessions = listOf(session)) } }
        val router = mockk<ProviderRequestRouter> {
            every { resolve(any()) } answers {
                val id = firstArg<String>()
                val remote = id.removePrefix("local::")
                ProviderResolution.Success(ResolvedProviderModel(
                    id, remote, "local", "Local", UnifiedProviderConfig(ProtocolType.Local, "", "", remote),
                ))
            }
        }
        val prompts = mutableListOf<String>()
        val toolThreads = mutableListOf<Thread>()
        val strategy = mockk<ChatGenerationContentStrategy> {
            every { buildTools(any()) } answers {
                toolThreads += Thread.currentThread()
                toolNames.map { ProtocolTool(function = ProtocolToolFunction(it, "", "{}"), runtimeToolId = it) }
            }
            every { buildProtocolMessages(any(), any(), any(), any(), any()) } answers {
                val prompt = secondArg<String>()
                prompts += prompt
                listOf(com.promenar.infinity.data.remote.protocol.ProtocolMessage(role = "system", content = prompt))
            }
        }
        val executor = mockk<ToolExecutor>(relaxed = true)
        val messageManager = mockk<MessageManager>(relaxed = true)
        if (subagentCompletions != null) {
            coEvery { messageManager.addMessage(any(), any()) } coAnswers {
                val sessionId = firstArg<String>()
                val message = secondArg<Message>()
                store.updateSession(sessionId) { it.copy(messages = it.messages + message) }
            }
        }
        val runtime = DefaultChatGenerationRuntime(
            settings, this, store, mockk<IAgentRepository> { coEvery { getById(any()) } returns
                knowledgeConfig?.let { com.promenar.infinity.domain.model.Agent("agent", "智能体", retrievalConfig = it) } },
            AgentConfigResolver(settings), ChatProviderRouteGate(router, UnconfinedTestDispatcher(testScheduler)),
            contextBuilder, messageManager, { localProvider ?: mockk() }, mockk(), toolLedger, executor,
            mockk(), null, mockk(), mockk(), strategy, mockk(relaxed = true), skillCatalog = catalog, sessionWorkspace = workspace,
            stateBoardRepository = stateBoardRepository,
            askUserLedger = askLedger,
            subagentCompletions = subagentCompletions,
            knowledgeCatalog = knowledgeCatalog, knowledgeAccessPlanner = knowledgePlanner, knowledgeTurns = knowledgeTurns,
            timedGrants = timedGrants, linuxFiles = linuxFiles,
        )
        return SkillsFixture(runtime, GenerationRequest("skills-session", "assistant", "user", content, emptyList(), GenerationRuntimePolicy.BACKGROUND_ALLOWED), prompts, toolThreads, executor, store)
    }

    @Test fun `主会话技能授权有效时直接执行且到期异类异会话仍审批`() = runTest {
        var now = 1_000L
        val grants = com.promenar.infinity.data.approval.TimedApprovalGrantStore(backgroundScope) { now }
        grants.grant("skills-session", com.promenar.infinity.domain.tool.ApprovalGrantCategory.SKILLS)
        grants.grant("another-session", com.promenar.infinity.domain.tool.ApprovalGrantCategory.COMMAND)
        val skills = mockk<com.promenar.infinity.data.skills.SkillCatalog> {
            coEvery { scan(any()) } returns com.promenar.infinity.data.skills.SkillSnapshot(null, emptyList())
        }
        val ledger = mockk<ToolExecutionLedger>(relaxed = true) {
            coEvery { createToolApproval(any(), any(), any(), any(), any()) } returns com.promenar.infinity.data.repository.ToolApprovalCreation.CREATED
        }
        val fixture = skillsFixture(skills, toolNames = listOf("write_file", "shell_exec", "request_knowledge_access"), toolLedger = ledger, timedGrants = grants)
        assertThat(fixture.runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)
        val call = GenerationToolCall("write", "write_file", """{"path":"/skills/demo/SKILL.md"}""")
        assertThat(fixture.runtime.handleTools(fixture.request, listOf(call))).isEqualTo(GenerationToolDecision.CONTINUE)
        assertThat(fixture.store.getSession("skills-session")!!.approvalRequest).isNull()
        coVerify(exactly = 0) { ledger.createToolApproval(any(), any(), any(), any(), any()) }
        coVerify { fixture.executor.executeTools(any(), any(), any(), match { "write" in it }, any(), any(), match { "write" in it }) }
        assertThat(fixture.runtime.handleTools(fixture.request, listOf(GenerationToolCall("command", "shell_exec", "{}"))))
            .isEqualTo(GenerationToolDecision.WAIT_FOR_APPROVAL)
        assertThat(fixture.store.getSession("skills-session")!!.approvalRequest!!.calls.single().grantCategory)
            .isEqualTo(com.promenar.infinity.domain.tool.ApprovalGrantCategory.COMMAND)
        fixture.store.updateSession("skills-session") { it.copy(approvalRequest = null) }
        now += 600_000
        assertThat(fixture.runtime.handleTools(fixture.request, listOf(call.copy(id = "expired"))))
            .isEqualTo(GenerationToolDecision.WAIT_FOR_APPROVAL)
        assertThat(fixture.store.getSession("skills-session")!!.approvalRequest!!.calls.single().grantCategory)
            .isEqualTo(com.promenar.infinity.domain.tool.ApprovalGrantCategory.SKILLS)
        grants.grant("skills-session", com.promenar.infinity.domain.tool.ApprovalGrantCategory.COMMAND)
        assertThat(fixture.runtime.handleTools(fixture.request, listOf(GenerationToolCall("knowledge", "request_knowledge_access", "{}"))))
            .isEqualTo(GenerationToolDecision.WAIT_FOR_APPROVAL)
        assertThat(fixture.store.getSession("skills-session")!!.approvalRequest!!.calls.single().grantCategory).isNull()
    }

    @Test fun `同轮判定后到期或撤销不撤回已准入调用而下一轮重新审批`() = runTest {
        for (revoke in listOf(false, true)) {
            var now = 1_000L
            val grants = com.promenar.infinity.data.approval.TimedApprovalGrantStore(backgroundScope) { now }
            grants.grant("skills-session", com.promenar.infinity.domain.tool.ApprovalGrantCategory.SKILLS)
            val catalog = mockk<com.promenar.infinity.data.skills.SkillCatalog> {
                coEvery { scan(any()) } returns com.promenar.infinity.data.skills.SkillSnapshot(null, emptyList())
            }
            val fixture = skillsFixture(catalog, toolNames = listOf("write_file"), timedGrants = grants)
            val admitted = mutableListOf<Set<String>>()
            coEvery { fixture.executor.executeTools(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                admitted += arg<Set<String>>(3)
                if (revoke) grants.revokeAll("skills-session") else now += 600_000
                assertThat(arg<Map<String, String?>>(6).keys).containsExactlyElementsIn(arg<Set<String>>(3))
            }
            assertThat(fixture.runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)
            val calls = listOf("one", "two").map { GenerationToolCall(it, "write_file", """{"path":"/skills/demo/$it.md"}""") }
            assertThat(fixture.runtime.handleTools(fixture.request, calls)).isEqualTo(GenerationToolDecision.CONTINUE)
            assertThat(admitted.single()).containsExactly("one", "two")
            assertThat(fixture.runtime.handleTools(fixture.request, listOf(calls.first().copy(id = "next"))))
                .isEqualTo(GenerationToolDecision.WAIT_FOR_APPROVAL)
            assertThat(admitted.last()).isEmpty()
        }
    }

    @Test fun `有效命令与Linux系统路径授权分别免批且不互相替代`() = runTest {
        for (category in listOf(com.promenar.infinity.domain.tool.ApprovalGrantCategory.COMMAND, com.promenar.infinity.domain.tool.ApprovalGrantCategory.LINUX_ROOTFS)) {
            val grants = com.promenar.infinity.data.approval.TimedApprovalGrantStore(backgroundScope) { testScheduler.currentTime }
            val target = """["path","/etc/profile",{"linuxRootfs":true,"linuxMode":true,"oneShot":true}]"""
            val files = mockk<com.promenar.infinity.ui.chat.manager.skills.LinuxSessionExternalWorkspaceAccess> {
                coEvery { executionTarget(any(), any(), any()) } returns target
            }
            val catalog = mockk<com.promenar.infinity.data.skills.SkillCatalog> {
                coEvery { scan(any()) } returns com.promenar.infinity.data.skills.SkillSnapshot(null, emptyList())
            }
            val fixture = skillsFixture(catalog, toolNames = listOf("write_file", "shell_exec"), timedGrants = grants, linuxFiles = files)
            fixture.store.updateSession("skills-session") { it.copy(runMode = com.promenar.infinity.domain.model.SessionRunMode.LINUX) }
            assertThat(fixture.runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)
            grants.grant("skills-session", category)
            val command = GenerationToolCall("command", "shell_exec", """{"command":"pwd"}""")
            val file = GenerationToolCall("rootfs", "write_file", """{"path":"/etc/profile"}""")
            val allowed = if (category == com.promenar.infinity.domain.tool.ApprovalGrantCategory.COMMAND) command else file
            val decision = fixture.runtime.handleTools(fixture.request, listOf(allowed))
            com.google.common.truth.Truth.assertWithMessage("授权类别：$category")
                .that(decision).isEqualTo(GenerationToolDecision.CONTINUE)
            coVerify { fixture.executor.executeTools(any(), any(), any(), setOf(allowed.id), any(), any(), match { allowed.id in it }) }
            val pending = if (allowed == command) file else command
            assertThat(fixture.runtime.handleTools(fixture.request, listOf(pending))).isEqualTo(GenerationToolDecision.WAIT_FOR_APPROVAL)
            assertThat(fixture.store.getSession("skills-session")!!.approvalRequest!!.calls.single().grantCategory)
                .isEqualTo(if (pending == command) com.promenar.infinity.domain.tool.ApprovalGrantCategory.COMMAND else com.promenar.infinity.domain.tool.ApprovalGrantCategory.LINUX_ROOTFS)
        }
    }

    @Test fun `实际运行时读取智能体工具模式目录并播种自动引用`() = runTest {
        val reference = com.promenar.infinity.data.model.RagReference("injected", "正文", "资料")
        val builder = mockk<ContextBuilder> {
            coEvery { buildContext(any()) } returns ContextBuilderResult("", "", emptyList(), listOf(reference), null, "system")
        }
        val skills = mockk<com.promenar.infinity.data.skills.SkillCatalog> { coEvery { scan(any()) } returns com.promenar.infinity.data.skills.SkillSnapshot(null, emptyList()) }
        val catalog = mockk<com.promenar.infinity.data.knowledge.KnowledgeCatalogSource> {
            coEvery { snapshot(any(), any()) } returns com.promenar.infinity.data.knowledge.KnowledgeCatalogSnapshot(
                groups = listOf(com.promenar.infinity.data.knowledge.KnowledgeCatalogGroup("root", "知识库标题", 1, true)))
        }
        val turns = com.promenar.infinity.data.knowledge.KnowledgeTurnState()
        val fixture = skillsFixture(skills, contextBuilder = builder,
            knowledgeConfig = com.promenar.infinity.data.agent.AgentRetrievalConfig(retrievalMode = com.promenar.infinity.data.agent.AgentRetrievalMode.TOOL),
            knowledgeCatalog = catalog, knowledgeTurns = turns)
        assertThat(fixture.runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)
        coVerify { builder.buildContext(match { it.retrievalMode == com.promenar.infinity.data.agent.AgentRetrievalMode.TOOL && it.knowledgeCatalog.contains("知识库标题") }) }
        assertThat(turns.isInjected("skills-session", "user", "injected")).isTrue()
    }

    @Test fun `自动注入取号前恢复同轮编号且缺少用户ID时回溯用户消息`() = runTest {
        for (userId in listOf("user", null)) {
            val reference = com.promenar.infinity.data.model.RagReference("injected", "正文", "资料")
            val builder = mockk<ContextBuilder> {
                coEvery { buildContext(any()) } coAnswers {
                    val citation = checkNotNull(kotlinx.coroutines.currentCoroutineContext()[
                        com.promenar.infinity.data.knowledge.KnowledgeCitationScope])
                    val number = citation.citation(reference.id)
                    ContextBuilderResult("[$number · 文档]: 正文", "", emptyList(), listOf(reference), null, "system")
                }
            }
            val skills = mockk<com.promenar.infinity.data.skills.SkillCatalog> {
                coEvery { scan(any()) } returns com.promenar.infinity.data.skills.SkillSnapshot(null, emptyList())
            }
            val turns = com.promenar.infinity.data.knowledge.KnowledgeTurnState()
            val fixture = skillsFixture(skills, contextBuilder = builder, knowledgeTurns = turns)
            val data = com.promenar.infinity.data.knowledge.KnowledgeSearchData("q", results = listOf(
                com.promenar.infinity.data.knowledge.KnowledgeSearchHit("saved", "K7")))
            fixture.store.updateSession("skills-session") { session -> session.copy(messages = session.messages.map {
                if (it.id == "assistant") it.copy(executionSteps = listOf(ExecutionStep("result", "tool_result",
                    toolName = "knowledge_search", data = com.promenar.infinity.data.model.json.encodeToString(
                        com.promenar.infinity.data.knowledge.KnowledgeSearchData.serializer(), data), timestamp = 1))) else it
            }) }

            assertThat(fixture.runtime.buildContext(fixture.request.copy(userMessageId = userId)))
                .isEqualTo(GenerationPreparationOutcome.Ready)
            assertThat(turns.citation("skills-session", "user", "injected")).isEqualTo("K8")
            assertThat(turns.isInjected("skills-session", "user", "injected")).isTrue()
            assertThat(kotlinx.coroutines.currentCoroutineContext()[com.promenar.infinity.data.knowledge.KnowledgeCitationScope]).isNull()
        }
    }

    @Test fun `知识库审批绑定预览且同轮拒绝后不再创建审批`() = runTest {
        val skills = mockk<com.promenar.infinity.data.skills.SkillCatalog> { coEvery { scan(any()) } returns com.promenar.infinity.data.skills.SkillSnapshot(null, emptyList()) }
        val preview = com.promenar.infinity.data.knowledge.KnowledgeAccessPreview("skills-session", allowed = true,
            knowledgeBases = listOf(com.promenar.infinity.data.knowledge.KnowledgeAccessTarget("kb", "资料", 3, "knowledge", "/mnt/knowledge")))
        val planner = mockk<com.promenar.infinity.data.knowledge.KnowledgeAccessPlanner> { coEvery { preview("skills-session", null) } returns preview }
        val turns = com.promenar.infinity.data.knowledge.KnowledgeTurnState()
        val ledger = mockk<com.promenar.infinity.data.repository.ToolExecutionLedger>(relaxed = true) {
            coEvery { createToolApproval(any(), any(), any(), any(), any()) } returns com.promenar.infinity.data.repository.ToolApprovalCreation.CREATED
        }
        val fixture = skillsFixture(skills, toolNames = listOf("request_knowledge_access"), knowledgePlanner = planner, knowledgeTurns = turns, toolLedger = ledger)
        assertThat(fixture.runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)
        val first = fixture.runtime.handleTools(fixture.request, listOf(GenerationToolCall("first", "request_knowledge_access", """{"reason":"需要全文"}""")))
        assertThat(first).isEqualTo(GenerationToolDecision.WAIT_FOR_APPROVAL)
        val target = fixture.store.getSession("skills-session")!!.approvalRequest!!.calls.single().executionTarget!!
        assertThat(com.promenar.infinity.data.model.json.decodeFromString<com.promenar.infinity.data.knowledge.KnowledgeAccessPreview>(target)).isEqualTo(preview)
        turns.deny("skills-session", "user")
        fixture.store.updateSession("skills-session") { it.copy(approvalRequest = null) }
        assertThat(fixture.runtime.handleTools(fixture.request, listOf(GenerationToolCall("second", "request_knowledge_access", """{"reason":"再次读取"}""")))).isEqualTo(GenerationToolDecision.CONTINUE)
        coVerify(exactly = 1) { ledger.createToolApproval(any(), any(), any(), any(), any()) }
        coVerify { fixture.executor.executeTools(any(), any(), any(), match { "second" in it }, any(), any()) }
    }

    @Test fun `无界面不广告提问或分支且误提问返回工具错误继续生成`() = runTest {
        val ledger = mockk<com.promenar.infinity.data.repository.AskUserLedgerRepository>()
        coEvery { ledger.consumeResumption(any(), any()) } returns false
        coEvery { ledger.createBatch(any(), any(), any(), null) } returns com.promenar.infinity.data.repository.AskUserBatchResult(null,
            listOf(Message("failure", MessageRole.TOOL, """{"status":"error","error":"ASK_USER_UNAVAILABLE"}""", toolCallId = "q")))
        val catalog = mockk<com.promenar.infinity.data.skills.SkillCatalog> { coEvery { scan(any()) } returns com.promenar.infinity.data.skills.SkillSnapshot(null, emptyList()) }
        val fixture = skillsFixture(catalog, toolNames = listOf("ask_user", "session_branch", "calculator"), askLedger = ledger)
        val request = fixture.request.copy(interactionMode = com.promenar.infinity.domain.generation.GenerationInteractionMode.HEADLESS)
        assertThat(fixture.runtime.buildContext(request)).isEqualTo(GenerationPreparationOutcome.Ready)
        assertThat(fixture.runtime.knownToolNames(request)).containsExactly("calculator")
        fixture.prompts.forEach { assertThat(it).doesNotContain("ask_user"); assertThat(it).doesNotContain("session_branch") }
        assertThat(fixture.runtime.handleTools(request, listOf(GenerationToolCall("q", "ask_user", "{}"))))
            .isEqualTo(GenerationToolDecision.CONTINUE)
        coVerify(exactly = 1) { ledger.createBatch(any(), any(), any(), null) }
    }

    @Test fun `提问批次绕开审批和执行器并返回等待输入`() = runTest {
        val pending = com.promenar.infinity.domain.ask.PendingQuestion("skills-session", "assistant", "q",
            com.promenar.infinity.domain.ask.AskUserRequest(listOf(com.promenar.infinity.domain.ask.AskUserQuestion("方案", "选哪个？", options = listOf(
                com.promenar.infinity.domain.ask.AskUserOption("甲"), com.promenar.infinity.domain.ask.AskUserOption("乙"))))))
        val ledger = mockk<com.promenar.infinity.data.repository.AskUserLedgerRepository>()
        coEvery { ledger.consumeResumption(any(), any()) } returns false
        coEvery { ledger.createBatch(any(), any(), any(), any()) } returns com.promenar.infinity.data.repository.AskUserBatchResult(pending, emptyList())
        val catalog = mockk<com.promenar.infinity.data.skills.SkillCatalog> { coEvery { scan(any()) } returns com.promenar.infinity.data.skills.SkillSnapshot(null, emptyList()) }
        val fixture = skillsFixture(catalog, toolNames = listOf("ask_user", "shell_exec"), askLedger = ledger)
        assertThat(fixture.runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)
        val calls = listOf(GenerationToolCall("before", "shell_exec", "{}"), GenerationToolCall("q", "ask_user", "{}"), GenerationToolCall("after", "shell_exec", "{}"))
        assertThat(fixture.runtime.handleTools(fixture.request, calls)).isEqualTo(GenerationToolDecision.WAIT_FOR_INPUT)
        assertThat(fixture.store.getSession("skills-session")!!.loopStatus).isEqualTo(com.promenar.infinity.data.model.LoopStatus.WAITING_FOR_INPUT)
        coVerify(exactly = 0) { fixture.executor.executeTools(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { ledger.createBatch("skills-session", "assistant", match { it.map { call -> call.id } == listOf("before", "q", "after") }, any()) }
    }

    @Test fun `工具续轮新助手继承请求所见状态版本而不是重新读取最新值`() = runTest {
        val boardRepository = mockk<com.promenar.infinity.data.repository.StateBoardRepository>(relaxed = true)
        val builder = mockk<ContextBuilder> {
            coEvery { buildContext(any()) } returns ContextBuilderResult("", "", emptyList(), emptyList(), null, "system", stateBoardRevision = 7)
        }
        val catalog = mockk<com.promenar.infinity.data.skills.SkillCatalog> {
            coEvery { scan(any()) } returns com.promenar.infinity.data.skills.SkillSnapshot(null, emptyList())
        }
        val fixture = skillsFixture(catalog, contextBuilder = builder, stateBoardRepository = boardRepository)
        assertThat(fixture.runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)
        val nextOwner = fixture.runtime.prepareContinuation(fixture.request)
        assertThat(nextOwner).isNotEqualTo(fixture.request.assistantMessageId)
        verify(exactly = 1) { boardRepository.recordContextRead("skills-session", nextOwner, 7) }
        coVerify(exactly = 0) { boardRepository.get(any(), any()) }
    }

    @Test fun `工具表在调用协程线程之外构建`() = runTest {
        val catalog = mockk<com.promenar.infinity.data.skills.SkillCatalog> {
            coEvery { scan(any()) } returns com.promenar.infinity.data.skills.SkillSnapshot(null, emptyList())
        }
        val fixture = skillsFixture(catalog)
        val caller = Thread.currentThread()
        assertThat(fixture.runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)
        // 工具注册表会同步扫描技能目录；生成作用域在主线程，构建必须切到 IO。
        assertThat(fixture.toolThreads).isNotEmpty()
        assertThat(fixture.toolThreads).doesNotContain(caller)
    }

    @Test fun `每轮重扫同一目录工作区且普通读取工具可暴露两级点名技能`() = runTest {
        val calls = mutableListOf<com.promenar.infinity.data.skills.SkillWorkspace?>()
        val directory = testDirectory("project")
        var skills = listOf(discoveredSkill("demo").copy(allowImplicitInvocation = false), discoveredSkill("demo", com.promenar.infinity.data.skills.SkillScope.PROJECT))
        val catalog = mockk<com.promenar.infinity.data.skills.SkillCatalog> {
            coEvery { scan(any()) } answers {
                val workspace = firstArg<com.promenar.infinity.data.skills.SkillWorkspace?>()
                calls += workspace
                com.promenar.infinity.data.skills.SkillSnapshot(workspace, skills)
            }
        }
        val fixture = skillsFixture(catalog, workspace = { check(it == "skills-session"); directory })
        assertThat(fixture.runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)
        assertThat(fixture.prompts.last()).contains("/skills/demo/SKILL.md")
        assertThat(fixture.prompts.last()).contains("/.agents/skills/demo/SKILL.md")
        skills = listOf(discoveredSkill("new-skill"))
        assertThat(fixture.runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)
        assertThat(fixture.prompts.last()).contains("/skills/new-skill/SKILL.md")
        assertThat(fixture.prompts.last()).doesNotContain("/skills/demo/SKILL.md")
        assertThat(calls.map { it?.key }).containsExactly("project", "project").inOrder()
        assertThat(calls.map { it?.root }).containsExactly(java.io.File("/project/project"), java.io.File("/project/project"))
    }

    @Test fun `扫描失败使用空清单且私有或不可用工作区不扫描项目`() = runTest {
        val calls = mutableListOf<com.promenar.infinity.data.skills.SkillWorkspace?>()
        val catalog = mockk<com.promenar.infinity.data.skills.SkillCatalog> {
            coEvery { scan(any()) } answers {
                calls += firstArg<com.promenar.infinity.data.skills.SkillWorkspace?>()
                throw java.io.IOException("扫描不可用")
            }
        }
        for (workspace in listOf(com.promenar.infinity.data.workspace.SessionWorkspace.Private,
            com.promenar.infinity.data.workspace.SessionWorkspace.Unavailable("missing", null, "不可用"))) {
            val fixture = skillsFixture(catalog, workspace = { workspace })
            assertThat(fixture.runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)
            assertThat(fixture.prompts.last()).doesNotContain("## Skills")
        }
        assertThat(calls).containsExactly(null, null)
    }

    @Test fun `运行时透传保存的模型窗口控制清单预算`() = runTest {
        val catalog = mockk<com.promenar.infinity.data.skills.SkillCatalog> {
            coEvery { scan(any()) } returns com.promenar.infinity.data.skills.SkillSnapshot(null, (1..70).map { discoveredSkill("skill-$it") })
        }
        val fixture = skillsFixture(catalog, contextTokens = 15_000)
        assertThat(fixture.runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)
        val section = fixture.prompts.last().substringAfter("## Skills")
        assertThat(section.length + "## Skills".length).isAtMost(1200)
        assertThat(section).contains("more skills")
        val noReader = skillsFixture(catalog, toolNames = listOf("calculator"))
        assertThat(noReader.runtime.buildContext(noReader.request)).isEqualTo(GenerationPreparationOutcome.Ready)
        assertThat(noReader.prompts.last()).doesNotContain("## Skills")
    }

    @Test fun `扫描取消原样上抛`() = runTest {
        val cancellation = CancellationException("cancelled")
        val catalog = mockk<com.promenar.infinity.data.skills.SkillCatalog> {
            coEvery { scan(any()) } throws cancellation
        }
        val fixture = skillsFixture(catalog)
        assertThat(runCatching { fixture.runtime.buildContext(fixture.request) }.exceptionOrNull()).isSameInstanceAs(cancellation)
    }

    @Test
    fun `全自动模式解析UUID后仍审批两级技能写入且旧白名单不能放行`() = runTest {
        val tool = ProtocolTool(function = ProtocolToolFunction("write_file", "", "{}"), risk = ToolRisk.FILE_WRITE, runtimeToolId = "write_file", sourceId = "builtin")
        for (path in listOf("/skills/demo/SKILL.md", "/.agents/skills/demo/SKILL.md")) {
            val repository = mockk<com.promenar.infinity.domain.repository.IWorkspaceRepository> {
                coEvery { getByUuid("root", "file") } returns com.promenar.infinity.data.local.db.entity.FileEntry(
                    "file", "root", null, "SKILL.md", "", physicalRootPath = "", materializedPath = path, createdAt = 1, updatedAt = 1,
                )
            }
            for (arguments in listOf("""{"path":"$path"}""", """{"uuid":"file"}""")) {
                assertAllowlistDecision(tool, arguments, """["path","$path"]""", "session:current-session",
                    com.promenar.infinity.data.workspace.SessionWorkspace.Private, false,
                    executionMode = "auto", policyPending = false, repository = repository)
            }
        }
    }

    @Test
    fun `未知文件目标不引入额外审批且由文件执行层校验`() = runTest {
        val tool = ProtocolTool(function = ProtocolToolFunction("write_file", "", "{}"), risk = ToolRisk.FILE_WRITE, runtimeToolId = "write_file", sourceId = "builtin")
        val repository = mockk<com.promenar.infinity.domain.repository.IWorkspaceRepository> {
            coEvery { getByUuid(any(), any()) } returns null
        }
        assertAllowlistDecision(tool, """{"uuid":"missing"}""", """["path","/a.md"]""", "session:current-session",
            com.promenar.infinity.data.workspace.SessionWorkspace.Private, true,
            executionMode = "auto", policyPending = false, repository = repository)
    }

    @Test
    fun `全自动模式识别内部技能挂载UUID且只读调用不额外审批`() = runTest {
        val mounts = mockk<com.promenar.infinity.domain.storage.ExternalWorkspaceAccess> {
            coEvery { workspaceMount(any()) } returns null
            coEvery { visible(any()) } returns listOf(com.promenar.infinity.domain.storage.ExternalMount(
                "builtin:skills", "skills", "builtin_skills", "agent_skills", "session", 0,
            ))
        }
        for (name in listOf("write_file", "read_file")) {
            val tool = ProtocolTool(function = ProtocolToolFunction(name, "", "{}"), risk = if (name == "write_file") ToolRisk.FILE_WRITE else ToolRisk.SAFE_READ,
                runtimeToolId = name, sourceId = "builtin")
            assertAllowlistDecision(tool, """{"uuid":"external:builtin:skills:L1NLSUxMLm1k"}""", """["path","/skills/SKILL.md"]""", "mount:builtin:skills",
                com.promenar.infinity.data.workspace.SessionWorkspace.Private, name == "read_file", executionMode = "auto", policyPending = false, mounts = mounts)
        }
    }

    @Test
    fun `旧白名单不影响命令审批且网页与MCP直接放行`() = runTest {
        val cases = listOf(
            Triple(ProtocolTool(function = ProtocolToolFunction("web_fetch", "", "{}"), risk = ToolRisk.SAFE_READ, runtimeToolId = "web_fetch", sourceId = "builtin"),
                """{"url":"https://example.com/a"}""", """["host","example.com"]"""),
            Triple(ProtocolTool(function = ProtocolToolFunction("shell_exec", "", "{}"), risk = ToolRisk.PROCESS, runtimeToolId = "shell_exec", sourceId = "builtin"),
                """{"command":"git status"}""", """["process","builtin","git status"]"""),
            Triple(ProtocolTool(function = ProtocolToolFunction("mcp_call", "", "{}"), risk = ToolRisk.UNKNOWN, runtimeToolId = "mcp:server:tool", sourceId = "mcp", mcpServerId = "server"),
                "{}", """["mcp","server","tool"]"""),
        )
        for ((tool, args, target) in cases) {
            assertAllowlistDecision(tool, args, target, null, testDirectory("other-workspace"), tool.function.name != "shell_exec")
        }
        assertAllowlistDecision(cases[1].first, """{"command":"git status && rm x"}""", cases[1].third,
            null, testDirectory("other-workspace"), false)
    }

    @Test
    fun `普通文件写入不依赖旧白名单作用域`() = runTest {
        val tool = ProtocolTool(function = ProtocolToolFunction("write_file", "", "{}"), risk = ToolRisk.FILE_WRITE, runtimeToolId = "write_file", sourceId = "builtin")
        val args = """{"path":"/a.md"}"""
        val target = """["path","/a.md"]"""
        assertAllowlistDecision(tool, args, target, "workspace:project", testDirectory("project"), true)
        assertAllowlistDecision(tool, args, target, "workspace:project", testDirectory("other"), true)
        assertAllowlistDecision(tool, args, target, "session:other-session", com.promenar.infinity.data.workspace.SessionWorkspace.Private, true)
        assertAllowlistDecision(tool, args, target, "session:current-session", com.promenar.infinity.data.workspace.SessionWorkspace.Private, true)
        assertAllowlistDecision(tool, args, target, "workspace:project", com.promenar.infinity.data.workspace.SessionWorkspace.Unavailable("project", null, "离线"), true)
    }

    @Test
    fun `生成运行时两档全部类别使用统一审批并忽略旧授权`() = runTest {
        listOf("workspace", "full_access").forEach { mode ->
            com.promenar.infinity.domain.tool.permissionPolicyCases.forEach { case ->
                val tool = ProtocolTool(function = ProtocolToolFunction(case.tool, "", "{}"), risk = case.risk,
                    runtimeToolId = case.tool, sourceId = "builtin")
                assertAllowlistDecision(tool, case.arguments, """["host","example.com"]""", null,
                    com.promenar.infinity.data.workspace.SessionWorkspace.Private,
                    !(case.alwaysApprove || case.command && mode == "workspace"), executionMode = mode)
            }
        }
    }

    private fun testDirectory(id: String) = com.promenar.infinity.data.workspace.SessionWorkspace.Directory(
        com.promenar.infinity.data.local.db.entity.WorkspaceEntity(id, "project", id, id, 1), java.io.File("/project/$id"),
    )

    private suspend fun kotlinx.coroutines.test.TestScope.assertAllowlistDecision(
        tool: ProtocolTool,
        arguments: String,
        rememberedTarget: String,
        rememberedScope: String?,
        workspace: com.promenar.infinity.data.workspace.SessionWorkspace,
        bypass: Boolean,
        executionMode: String = "semi",
        policyPending: Boolean = true,
        repository: com.promenar.infinity.domain.repository.IWorkspaceRepository? = null,
        mounts: com.promenar.infinity.domain.storage.ExternalWorkspaceAccess? = null,
    ) {
        val root = java.nio.file.Files.createTempDirectory("runtime-allowlist").toFile()
        try {
            val allowlist = com.promenar.infinity.data.repository.ToolAllowlistStore(root)
            allowlist.add(tool.function.name, rememberedTarget, rememberedScope, "other-session")
            val settings = mockk<SharedPreferences> { every { getInt(any(), any()) } answers { secondArg() } }
            val store = ChatStore().apply {
                update { ChatState(sessions = listOf(Session(id = "current-session", agentId = "agent", executionMode = executionMode, workspaceRootUuid = "root"))) }
            }
            val strategy = mockk<ChatGenerationContentStrategy> {
                every { pendingApprovalIds(any(), any()) } returns if (policyPending) listOf("call") else emptyList()
            }
            val executor = mockk<ToolExecutor>(relaxed = true)
            val ledger = mockk<ToolExecutionLedger>(relaxed = true)
            coEvery { ledger.createToolApproval(any(), any(), any(), any(), any()) } returns com.promenar.infinity.data.repository.ToolApprovalCreation.CREATED
            val runtime = DefaultChatGenerationRuntime(
                settings, this, store, mockk(), mockk(), mockk(), mockk(), mockk(relaxed = true),
                { mockk() }, mockk(), ledger, executor, mockk(), null, mockk(), mockk(), strategy, mockk(relaxed = true),
                toolAllowlistStore = allowlist, sessionWorkspace = { workspace }, workspaceRepository = repository, externalFolders = mounts,
            )
            DefaultChatGenerationRuntime::class.java.getDeclaredField("preparedTools").apply {
                isAccessible = true
                set(runtime, listOf(tool))
            }
            val request = GenerationRequest("current-session", "assistant", "user", "", emptyList(), GenerationRuntimePolicy.BACKGROUND_ALLOWED)
            val result = runtime.handleTools(request, listOf(GenerationToolCall("call", tool.function.name, arguments)))
            assertThat(result).isEqualTo(if (bypass) GenerationToolDecision.CONTINUE else GenerationToolDecision.WAIT_FOR_APPROVAL)
            assertThat(store.getSession("current-session")?.approvalRequest != null).isEqualTo(!bypass)
            coVerify(exactly = 1) {
                executor.executeTools("current-session", "assistant", any(),
                    if (bypass) setOf("call") else emptySet(), listOf(tool),
                    emptyMap())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `准备恢复SSH所有者且审批暂停与终态关闭使用同一会话所有者`() = runTest {
        val sshRuntime = mockk<com.promenar.infinity.data.ssh.SshRuntime>(relaxed = true)
        val fixture = postProcessFixture(sshRuntime = sshRuntime)

        fixture.runtime.prepare(fixture.request)
        fixture.runtime.releaseToolResources(fixture.request, awaitingApproval = true)
        fixture.runtime.releaseToolResources(fixture.request, awaitingApproval = false)

        coVerify(exactly = 1) { sshRuntime.resumeOwner("agent:session") }
        coVerify(exactly = 1) { sshRuntime.pauseOwner("agent:session") }
        coVerify(exactly = 1) { sshRuntime.closeOwner("agent:session") }
    }

    @Test
    fun `初始完整文档最终门禁失败回滚本轮消息且不进入Provider`() = runTest {
        val fixture = documentBudgetFixture(toolRounds = 0)
        val request = fixture.request.copy(rollbackUserOnPreparationFailure = true)

        fixture.runtime.prepare(request)
        val outcome = fixture.runtime.buildContext(request)

        assertThat(outcome).isInstanceOf(GenerationPreparationOutcome.Handled::class.java)
        assertThat((outcome as GenerationPreparationOutcome.Handled).failure.code)
            .isEqualTo(GenerationFailureCode.CONTEXT_LIMIT)
        coVerify(exactly = 1) {
            fixture.messageManager.discardPreparedMessages("budget-session", listOf("assistant", "user"))
        }
        assertThat(fixture.providerEntered()).isFalse()
    }

    @Test
    fun `工具续轮最终门禁失败不删除既有用户或工具历史`() = runTest {
        val fixture = documentBudgetFixture(toolRounds = 1)

        fixture.runtime.prepare(fixture.request)
        val outcome = fixture.runtime.buildContext(fixture.request)

        assertThat(outcome).isInstanceOf(GenerationPreparationOutcome.Handled::class.java)
        coVerify(exactly = 0) { fixture.messageManager.discardPreparedMessages(any(), any()) }
        assertThat(fixture.providerEntered()).isFalse()
    }

    private data class DocumentBudgetFixture(
        val runtime: DefaultChatGenerationRuntime,
        val request: GenerationRequest,
        val messageManager: MessageManager,
        val providerEntered: () -> Boolean,
    )

    private fun kotlinx.coroutines.test.TestScope.documentBudgetFixture(
        toolRounds: Int,
    ): DocumentBudgetFixture {
        val settings = mockk<SharedPreferences>()
        every { settings.getInt(any(), any()) } returns 100
        every { settings.getStringSet(any(), any()) } returns emptySet()
        every { settings.getString(any(), any()) } returns ""
        every { settings.getFloat(any(), any()) } answers { secondArg() }
        val document = MessageDocumentAttachment(
            "doc", "large.txt", "text/plain", "中".repeat(100), 300, "hash", 300,
        )
        val store = ChatStore().apply {
            update {
                ChatState(
                    sessions = listOf(
                        Session(
                            id = "budget-session",
                            agentId = "agent",
                            modelId = "local::model",
                            inferenceParams = InferenceParams(maxTokens = 16),
                            messages = listOf(
                                Message("user", MessageRole.USER, "question", userDocuments = listOf(document)),
                                Message("assistant", MessageRole.ASSISTANT, ""),
                            ),
                        ),
                    ),
                )
            }
        }
        var providerEntered = false
        val protocol = object : LlmProtocol {
            override val protocolType = ProtocolType.Local
            override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> {
                providerEntered = true
                return flow {
                    emit(StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN))
                }
            }
            override suspend fun sendPromptSync(request: PromptRequest) = PromptResponse("")
            override fun cancel() = Unit
        }
        val provider = LlmProvider(protocol)
        val router = object : ProviderRequestRouter {
            override fun resolve(modelId: String): ProviderResolution = ProviderResolution.Success(
                ResolvedProviderModel(
                    modelId,
                    "model",
                    "local",
                    "Local",
                    UnifiedProviderConfig(ProtocolType.Local, "", "", "model"),
                ),
            )
            override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient = error("local")
        }
        val contextBuilder = mockk<ContextBuilder>()
        coEvery { contextBuilder.buildContext(any()) } returns ContextBuilderResult(
            searchContext = "",
            ragContext = "",
            citations = emptyList(),
            ragReferences = emptyList(),
            ragUsage = null,
            finalSystemPrompt = "system",
        )
        val sessionManager = mockk<SessionManager>(relaxed = true)
        val contentStrategy = mockk<ChatGenerationContentStrategy>()
        every { contentStrategy.buildTools(any()) } returns emptyList()
        every { contentStrategy.buildProtocolMessages(any(), any(), any(), any(), any()) } returns listOf(
            com.promenar.infinity.data.remote.protocol.ProtocolMessage(
                role = "user",
                content = "中".repeat(100),
            ),
        )
        val messageManager = mockk<MessageManager>(relaxed = true)
        val runtime = DefaultChatGenerationRuntime(
            settings = settings,
            applicationScope = this,
            store = store,
            agentRepository = mockk<IAgentRepository> { coEvery { getById(any()) } returns null },
            configResolver = AgentConfigResolver(settings),
            routeGate = ChatProviderRouteGate(router, UnconfinedTestDispatcher(testScheduler)),
            contextBuilder = contextBuilder,
            messageManager = messageManager,
            localProviderFactory = { provider },
            provider = provider,
            toolLedger = mockk(relaxed = true),
            toolExecutor = mockk(relaxed = true),
            postProcessor = mockk(relaxed = true),
            memoryManager = null,
            summaryManager = mockk(relaxed = true),
            sessionManager = sessionManager,
            contentStrategy = contentStrategy,
            ui = mockk(relaxed = true),
        )
        DefaultChatGenerationRuntime::class.java.getDeclaredField("toolRounds").apply {
            isAccessible = true
            setInt(runtime, toolRounds)
        }
        return DocumentBudgetFixture(
            runtime,
            GenerationRequest(
                "budget-session",
                "assistant",
                "user",
                "question",
                emptyList(),
                GenerationRuntimePolicy.BACKGROUND_ALLOWED,
            ),
            messageManager,
            { providerEntered },
        )
    }

    @Test
    fun `只有工具调用的让出轮记录用量但不写空记忆或排入抽取`() = runTest {
        val memory = mockk<MemoryManager>()
        every { memory.canEmbed } returns true
        val graph = mockk<com.promenar.infinity.data.rag.SessionGraphScheduler>(relaxed = true)
        val state = mockk<com.promenar.infinity.data.rag.SessionStateScheduler>(relaxed = true)
        val fixture = postProcessFixture(ragOptions = RagOptions(enableMemory = true, enableDocs = false), memoryManager = memory, sessionGraph = graph, sessionState = state)
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        fixture.runtime.postProcess(fixture.request, GenerationSnapshot(toolCalls = listOf(GenerationToolCall("call", "read_file", "{}")), inputTokens = 12, outputTokens = 4, totalTokens = 16))
        coVerify { fixture.postProcessor.updateStats(match { it.assistantContent.isEmpty() && it.accumulatedUsage?.total == 16 }) }
        coVerify(exactly = 0) { memory.addTurnToMemory(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { graph.schedule(any(), any(), any()) }
        coVerify(exactly = 0) { state.schedule(any(), any(), any()) }
    }

    @Test
    fun `postProcess updateStats取消必须传播`() = runTest {
        val fixture = postProcessFixture()
        val cancellation = CancellationException("stats-cancel")
        coEvery { fixture.postProcessor.updateStats(any()) } throws cancellation

        val result = runCatching {
            fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))
        }.exceptionOrNull()

        assertThat(result).isSameInstanceAs(cancellation)
    }

    @Test
    fun `postProcess addTurnToMemory取消必须传播`() = runTest {
        val memory = mockk<MemoryManager>()
        val fixture = postProcessFixture(
            ragOptions = RagOptions(enableMemory = true, enableDocs = false),
            memoryManager = memory,
        )
        val cancellation = CancellationException("memory-cancel")
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        every { memory.canEmbed } returns true
        fixture.messageManager.runTurnMemoryWrites()
        coEvery { memory.addTurnToMemory(any(), any(), any(), any(), any()) } throws cancellation

        val result = runCatching {
            fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))
        }.exceptionOrNull()

        assertThat(result).isSameInstanceAs(cancellation)
    }

    @Test
    fun `postProcess经writeTurnMemory写入本轮，按写入片段数给出最终状态`() = runTest {
        val memory = mockk<MemoryManager>()
        every { memory.canEmbed } returns true
        val fixture = postProcessFixture(ragOptions = RagOptions(enableMemory = true, enableDocs = false), memoryManager = memory)
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        val results = fixture.messageManager.runTurnMemoryWrites()

        coEvery { memory.addTurnToMemory(any(), any(), any(), any(), any()) } returns 2
        fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))
        coEvery { memory.addTurnToMemory(any(), any(), any(), any(), any()) } returns 0
        fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))

        assertThat(results.map { it?.status to it?.chunkCount }).containsExactly(
            TurnMemoryState.Status.STORED to 2,
            null to null,
        ).inOrder()
        // 新的一轮没有旧状态可恢复。
        coVerify(exactly = 2) { fixture.messageManager.writeTurnMemory("session", "assistant", null, any()) }
        coVerify { memory.addTurnToMemory("session", "question", "answer", "user", "assistant") }
    }

    @Test
    fun `postProcess未配置嵌入模型或会话关闭记忆时不写入也不记录状态`() = runTest {
        val memory = mockk<MemoryManager>()
        every { memory.canEmbed } returns false
        val unavailable = postProcessFixture(ragOptions = RagOptions(enableMemory = true, enableDocs = false), memoryManager = memory)
        coEvery { unavailable.postProcessor.updateStats(any()) } returns Unit
        unavailable.runtime.postProcess(unavailable.request, GenerationSnapshot(content = "answer"))

        val disabled = postProcessFixture(ragOptions = RagOptions(enableMemory = false, enableDocs = false), memoryManager = memory)
        coEvery { disabled.postProcessor.updateStats(any()) } returns Unit
        disabled.runtime.postProcess(disabled.request, GenerationSnapshot(content = "answer"))

        coVerify(exactly = 0) { memory.addTurnToMemory(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { unavailable.messageManager.writeTurnMemory(any(), any(), any(), any()) }
        coVerify(exactly = 0) { disabled.messageManager.writeTurnMemory(any(), any(), any(), any()) }
        coVerify(exactly = 0) { unavailable.messageManager.setTurnMemoryState(any(), any(), any()) }
    }

    @Test
    fun `postProcess在会话开启记忆且本轮成功时排入图谱抽取，与嵌入模型无关`() = runTest {
        val scheduled = mutableListOf<Triple<String, String, String?>>()
        val graph = com.promenar.infinity.data.rag.SessionGraphScheduler { sessionId, assistantId, userId ->
            scheduled += Triple(sessionId, assistantId, userId); true
        }
        val enabled = postProcessFixture(ragOptions = RagOptions(enableMemory = true, enableDocs = false), sessionGraph = graph)
        coEvery { enabled.postProcessor.updateStats(any()) } returns Unit
        enabled.runtime.postProcess(enabled.request, GenerationSnapshot(content = "answer"))
        enabled.runtime.postProcess(enabled.request, GenerationSnapshot(content = "partial", failure = GenerationFailure(GenerationFailureCode.RATE_LIMIT)))

        val disabled = postProcessFixture(ragOptions = RagOptions(enableMemory = false, enableDocs = false), sessionGraph = graph)
        coEvery { disabled.postProcessor.updateStats(any()) } returns Unit
        disabled.runtime.postProcess(disabled.request, GenerationSnapshot(content = "answer"))

        assertThat(scheduled).containsExactly(Triple("session", "assistant", "user"))
    }

    @Test
    fun `postProcess图谱排队失败不影响本轮，取消照常传播`() = runTest {
        val failing = postProcessFixture(
            ragOptions = RagOptions(enableMemory = true, enableDocs = false),
            sessionGraph = { _, _, _ -> error("queue down") },
        )
        coEvery { failing.postProcessor.updateStats(any()) } returns Unit
        failing.runtime.postProcess(failing.request, GenerationSnapshot(content = "answer"))

        val cancellation = CancellationException("graph-cancel")
        val cancelled = postProcessFixture(
            ragOptions = RagOptions(enableMemory = true, enableDocs = false),
            sessionGraph = { _, _, _ -> throw cancellation },
        )
        coEvery { cancelled.postProcessor.updateStats(any()) } returns Unit
        val result = runCatching {
            cancelled.runtime.postProcess(cancelled.request, GenerationSnapshot(content = "answer"))
        }.exceptionOrNull()
        assertThat(result).isSameInstanceAs(cancellation)
    }

    @Test
    fun `postProcess仅在成功且可嵌入的记忆轮次于图谱之后排入状态抽取`() = runTest {
        val scheduled = mutableListOf<String>()
        val memory = mockk<MemoryManager> { every { canEmbed } returns true }
        val state = com.promenar.infinity.data.rag.SessionStateScheduler { sessionId, assistantId, userId ->
            scheduled += "state:$sessionId:$assistantId:$userId"; true
        }
        val enabled = postProcessFixture(
            ragOptions = RagOptions(enableMemory = true, enableDocs = false),
            memoryManager = memory,
            sessionGraph = { _, _, _ -> scheduled += "graph"; true },
            sessionState = state,
        )
        coEvery { enabled.postProcessor.updateStats(any()) } returns Unit

        enabled.runtime.postProcess(enabled.request, GenerationSnapshot(content = "answer"))
        enabled.runtime.postProcess(enabled.request, GenerationSnapshot(content = "partial", failure = GenerationFailure(GenerationFailureCode.RATE_LIMIT)))
        enabled.runtime.postProcess(enabled.request.copy(userContent = " "), GenerationSnapshot(content = "answer"))
        enabled.runtime.postProcess(enabled.request, GenerationSnapshot(content = " "))

        val noEmbedding = mockk<MemoryManager> { every { canEmbed } returns false }
        for ((options, manager) in listOf(
            RagOptions(enableMemory = false, enableDocs = false) to memory,
            RagOptions(enableMemory = true, enableDocs = false) to noEmbedding,
            RagOptions(enableMemory = true, enableDocs = false) to null,
        )) {
            val excluded = postProcessFixture(ragOptions = options, memoryManager = manager, sessionState = state)
            coEvery { excluded.postProcessor.updateStats(any()) } returns Unit
            excluded.runtime.postProcess(excluded.request, GenerationSnapshot(content = "answer"))
        }

        assertThat(scheduled).containsExactly("graph", "state:session:assistant:user").inOrder()
    }

    @Test
    fun `postProcess先把本轮正文写入数据库再排入图谱与状态抽取`() = runTest {
        val order = mutableListOf<String>()
        val memory = mockk<MemoryManager> { every { canEmbed } returns true }
        val fixture = postProcessFixture(
            ragOptions = RagOptions(enableMemory = true, enableDocs = false),
            memoryManager = memory,
            sessionGraph = { _, _, _ -> order += "graph"; true },
            sessionState = { _, _, _ -> order += "state"; true },
        )
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        coEvery { fixture.messageManager.flushNonApprovalUpdatesNow("session", "assistant") } answers { order += "flush" }

        fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))

        // 抽取任务从数据库读取本轮正文；流式正文的防抖写入未落库时，抽取会读到残缺正文且结果在写回时被判为过期。
        assertThat(order).containsExactly("flush", "graph", "state").inOrder()
    }

    @Test
    fun `postProcess正文落库失败时不排入抽取`() = runTest {
        val scheduled = mutableListOf<String>()
        val memory = mockk<MemoryManager> { every { canEmbed } returns true }
        val fixture = postProcessFixture(
            ragOptions = RagOptions(enableMemory = true, enableDocs = false),
            memoryManager = memory,
            sessionGraph = { _, _, _ -> scheduled += "graph"; true },
            sessionState = { _, _, _ -> scheduled += "state"; true },
        )
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        coEvery { fixture.messageManager.flushNonApprovalUpdatesNow("session", "assistant") } throws IllegalStateException("disk full")

        fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))

        assertThat(scheduled).isEmpty()
    }

    @Test
    fun `postProcess状态排队异常不影响本轮且取消照常传播`() = runTest {
        val memory = mockk<MemoryManager> { every { canEmbed } returns true }
        val failing = postProcessFixture(
            ragOptions = RagOptions(enableMemory = true, enableDocs = false), memoryManager = memory,
            sessionState = { _, _, _ -> error("状态队列不可用") },
        )
        coEvery { failing.postProcessor.updateStats(any()) } returns Unit
        failing.runtime.postProcess(failing.request, GenerationSnapshot(content = "answer"))

        val cancellation = CancellationException("state-cancel")
        val cancelled = postProcessFixture(
            ragOptions = RagOptions(enableMemory = true, enableDocs = false), memoryManager = memory,
            sessionState = { _, _, _ -> throw cancellation },
        )
        coEvery { cancelled.postProcessor.updateStats(any()) } returns Unit
        assertThat(runCatching {
            cancelled.runtime.postProcess(cancelled.request, GenerationSnapshot(content = "answer"))
        }.exceptionOrNull()).isSameInstanceAs(cancellation)
    }

    @Test
    fun `postProcess把子代理用量并入本轮助手消息的附加用量，只汇入一次`() = runTest {
        val ledger = object : com.promenar.infinity.data.subagent.SubagentUsageLedger {
            var pending: TokenUsage? = TokenUsage(input = 30, output = 12, total = 42, estimated = true)
            override fun add(sessionId: String, usage: TokenUsage) { pending = usage }
            override fun drain(sessionId: String) = pending.also { pending = null }
        }
        val fixture = postProcessFixture(ragOptions = RagOptions(enableMemory = false, enableDocs = false), subagentUsage = ledger)
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        val written = mutableListOf<TokenUsage?>()
        coEvery { fixture.messageManager.updateMessageContent("session", "assistant", any(), any()) } answers {
            written += arg<UpdateMessageOptions>(3).tokens
        }

        fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))

        val tokens = written.single()!!
        assertThat(tokens.summaryTotal).isEqualTo(42)
        assertThat(tokens.summaryInput).isEqualTo(30)
        assertThat(tokens.summaryEstimated).isTrue()
        assertThat(fixture.runtime.additionalTokenUsage(fixture.request).tokens).isEqualTo(42)
        assertThat(ledger.pending).isNull()
    }

    /** 让模拟的 MessageManager 执行交给它的写入块，并记录写入块给出的最终状态。 */
    private fun MessageManager.runTurnMemoryWrites(): MutableList<TurnMemoryState?> {
        val results = mutableListOf<TurnMemoryState?>()
        val manager = this
        coEvery { manager.writeTurnMemory(any(), any(), any(), any()) } coAnswers {
            results += arg<suspend () -> TurnMemoryState?>(3).invoke()
        }
        return results
    }

    @Test
    fun `postProcess archiveMessagesToRag取消必须传播`() = runTest {
        val fixture = postProcessFixture(
            ragOptions = RagOptions(enableMemory = true, enableDocs = false),
            inferenceParams = InferenceParams(keepRecentTurns = 1, autoSummaryThreshold = 0.0),
            messageCount = 6,
        )
        val cancellation = CancellationException("archive-cancel")
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        coEvery {
            fixture.postProcessor.archiveMessagesToRag(any(), any(), any(), any())
        } throws cancellation

        val result = runCatching {
            fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))
        }.exceptionOrNull()

        assertThat(result).isSameInstanceAs(cancellation)
        verify(exactly = 0) {
            fixture.ui.updatePostProcessTask(any(), PostProcessStatus.ERROR, any(), any())
        }
    }

    @Test
    fun `postProcess summarize取消必须传播`() = runTest {
        val fixture = postProcessFixture(
            ragOptions = RagOptions(enableMemory = false, enableDocs = false),
            inferenceParams = InferenceParams(keepRecentTurns = 1, autoSummaryThreshold = 0.0),
            messageCount = 3,
        )
        val cancellation = CancellationException("summary-cancel")
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        coEvery { fixture.summaryManager.summarizeOrNull(any(), any(), any(), any(), any(), any()) } throws cancellation

        val result = runCatching {
            fixture.runtime.postProcess(
                fixture.request,
                GenerationSnapshot(content = "answer", totalTokens = 1),
            )
        }.exceptionOrNull()

        assertThat(result).isSameInstanceAs(cancellation)
        verify(exactly = 0) {
            fixture.ui.updatePostProcessTask(any(), PostProcessStatus.ERROR, any(), any())
        }
    }

    @Test
    fun `低于阈值时即使存在旧轮也不摘要或归档`() = runTest {
        val fixture = postProcessFixture(
            ragOptions = RagOptions(enableMemory = false, enableDocs = false),
            inferenceParams = InferenceParams(keepRecentTurns = 1, autoSummaryThreshold = 0.8),
            messageCount = 3,
        )
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit

        fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))

        coVerify(exactly = 0) { fixture.summaryManager.summarizeOrNull(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { fixture.messageManager.updateMessage(any(), any(), any()) }
    }

    @Test
    fun `超过阈值只总结完整旧轮并保留当前轮`() = runTest {
        val fixture = postProcessFixture(
            ragOptions = RagOptions(enableMemory = false, enableDocs = false),
            inferenceParams = InferenceParams(keepRecentTurns = 1, autoSummaryThreshold = 0.0),
            messageCount = 6,
        )
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        val overflow = slot<List<Message>>()
        coEvery {
            fixture.summaryManager.summarizeOrNull(any(), capture(overflow), any(), any(), any(), "session")
        } returns "summary"

        fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))

        assertThat(overflow.captured.map { it.id })
            .containsExactly("message-0", "message-1", "message-2", "message-3")
        coVerify(exactly = 1) {
            fixture.sessionManager.commitContextCompaction(
                "session", "summary", listOf("message-0", "message-1", "message-2", "message-3"),
                match { it.previousSummary == null && it.messages == overflow.captured },
            )
        }
    }

    @Test
    fun `摘要失败时溢出消息保持未归档以便下次重试`() = runTest {
        val fixture = postProcessFixture(
            ragOptions = RagOptions(enableMemory = false, enableDocs = false),
            inferenceParams = InferenceParams(keepRecentTurns = 1, autoSummaryThreshold = 0.0),
            messageCount = 6,
        )
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        coEvery { fixture.summaryManager.summarizeOrNull(any(), any(), any(), any(), any(), any()) } returns null

        fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))

        coVerify(exactly = 0) { fixture.messageManager.updateMessage(any(), any(), any()) }
    }

    @Test
    fun `postProcess普通归档失败不得把异常原文写入任务detail`() = runTest {
        val fixture = postProcessFixture(
            ragOptions = RagOptions(enableMemory = true, enableDocs = false),
            inferenceParams = InferenceParams(keepRecentTurns = 1, autoSummaryThreshold = 0.0),
            messageCount = 6,
        )
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        coEvery {
            fixture.postProcessor.archiveMessagesToRag(any(), any(), any(), any())
        } throws IllegalStateException("private-provider-marker")

        fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))

        val detail = slot<String?>()
        verify {
            fixture.ui.updatePostProcessTask(
                any(),
                PostProcessStatus.ERROR,
                any(),
                captureNullable(detail),
            )
        }
        assertThat(detail.captured).isNull()
    }

    @Test
    fun `文本 fallback 只清理展示标记不得升级为可执行工具调用`() = runTest {
        val fixture = postProcessFixture()
        every { fixture.contentStrategy.extractFallbackToolCalls("wrapped") } returns listOf(
            com.promenar.infinity.data.model.ToolCall("fallback", "search", "{}"),
        )
        every { fixture.contentStrategy.stripToolCallMarkup("wrapped") } returns "clean"

        val result = fixture.runtime.finalizeStream(
            fixture.request,
            GenerationSnapshot(content = "wrapped"),
        )

        assertThat(result.content).isEqualTo("clean")
        assertThat(result.toolCalls).isEmpty()
    }

    @Test
    fun `重试多轮工具调用失败会回滚本轮创建的全部消息`() = runTest {
        val fixture = postProcessFixture()
        val request = fixture.request.copy(messageIdsToReplace = setOf("old-assistant"))
        fixture.store.update {
            ChatState(
                sessions = listOf(
                    Session(
                        id = "session",
                        agentId = "agent",
                        modelId = "model",
                        messages = listOf(
                            Message("user", MessageRole.USER, "question"),
                            Message("old-assistant", MessageRole.ASSISTANT, "old answer"),
                            Message("assistant", MessageRole.ASSISTANT, ""),
                        ),
                    ),
                ),
            )
        }
        fixture.runtime.prepare(request)
        fixture.store.update { state ->
            state.copy(
                sessions = state.sessions.map { session ->
                    session.copy(
                        messages = session.messages + listOf(
                            Message("assistant-round-2", MessageRole.ASSISTANT, "tool call 1"),
                            Message("tool-result-1", MessageRole.TOOL, "result 1"),
                            Message("assistant-round-3", MessageRole.ASSISTANT, "tool call 2"),
                            Message("tool-result-2", MessageRole.TOOL, "result 2"),
                        ),
                    )
                },
            )
        }

        fixture.runtime.markTerminal(
            request,
            GenerationTerminalStatus.ERROR,
            GenerationSnapshot(content = "partial"),
            IllegalStateException("failed"),
        )

        val rolledBackIds = slot<List<String>>()
        coVerify(exactly = 1) {
            fixture.messageManager.discardPreparedMessages("session", capture(rolledBackIds))
        }
        assertThat(rolledBackIds.captured).containsExactly(
            "assistant",
            "assistant-round-2",
            "tool-result-1",
            "assistant-round-3",
            "tool-result-2",
        )
        assertThat(rolledBackIds.captured).doesNotContain("old-assistant")
    }

    private data class PostProcessFixture(
        val runtime: DefaultChatGenerationRuntime,
        val request: GenerationRequest,
        val postProcessor: PostProcessor,
        val summaryManager: SummaryManager,
        val ui: GenerationUiPort,
        val store: ChatStore,
        val messageManager: MessageManager,
        val contentStrategy: ChatGenerationContentStrategy,
        val sessionManager: SessionManager,
    )

    private fun postProcessFixture(
        ragOptions: RagOptions? = null,
        inferenceParams: InferenceParams? = null,
        messageCount: Int = 1,
        memoryManager: MemoryManager? = null,
        sshRuntime: com.promenar.infinity.data.ssh.SshRuntime? = null,
        sessionGraph: com.promenar.infinity.data.rag.SessionGraphScheduler? = null,
        subagentUsage: com.promenar.infinity.data.subagent.SubagentUsageLedger? = null,
        sessionState: com.promenar.infinity.data.rag.SessionStateScheduler? = null,
    ): PostProcessFixture {
        val messages = (0 until messageCount).map { index ->
            Message(
                id = if (index == messageCount - 1) "assistant" else "message-$index",
                role = if (index == messageCount - 1) MessageRole.ASSISTANT else MessageRole.USER,
                content = "content-$index",
            )
        }
        val store = ChatStore().apply {
            update {
                ChatState(
                    sessions = listOf(
                        Session(
                            id = "session",
                            agentId = "agent",
                            modelId = "model",
                            messages = messages,
                            ragOptions = ragOptions,
                            inferenceParams = inferenceParams,
                        ),
                    ),
                )
            }
        }
        val postProcessor = mockk<PostProcessor>()
        val summaryManager = mockk<SummaryManager>()
        val ui = mockk<GenerationUiPort>(relaxed = true)
        val messageManager = mockk<MessageManager>(relaxed = true)
        every { ui.addPostProcessTask(any(), any()) } returns "task"
        val sessionManager = mockk<SessionManager>(relaxed = true)
        val contentStrategy = mockk<ChatGenerationContentStrategy>()
        every { contentStrategy.buildProtocolMessages(any(), any(), any(), any(), any()) } answers {
            listOf(com.promenar.infinity.data.remote.protocol.ProtocolMessage("system", secondArg())) +
                firstArg<Session>().messages.filterNot { it.isArchived }.map {
                    com.promenar.infinity.data.remote.protocol.ProtocolMessage(it.role.name.lowercase(), it.content)
                }
        }
        val runtime = DefaultChatGenerationRuntime(
            settings = mockk(relaxed = true),
            applicationScope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
            store = store,
            agentRepository = mockk(),
            configResolver = mockk(),
            routeGate = mockk(),
            contextBuilder = mockk(),
            messageManager = messageManager,
            localProviderFactory = { mockk() },
            provider = mockk(relaxed = true),
            toolLedger = mockk(),
            toolExecutor = mockk(),
            postProcessor = postProcessor,
            memoryManager = memoryManager,
            summaryManager = summaryManager,
            sessionManager = sessionManager,
            contentStrategy = contentStrategy,
            ui = ui,
            sshRuntime = sshRuntime,
            sessionGraph = sessionGraph,
            subagentUsage = subagentUsage,
            sessionState = sessionState,
        )
        DefaultChatGenerationRuntime::class.java.getDeclaredField("preparedContext").apply {
            isAccessible = true
            set(
                runtime,
                ContextBuilderResult(
                    searchContext = "",
                    ragContext = "",
                    citations = emptyList(),
                    ragReferences = emptyList(),
                    ragUsage = null,
                    finalSystemPrompt = "system",
                ),
            )
        }
        return PostProcessFixture(
            runtime,
            GenerationRequest(
                "session", "assistant", "user", "question", emptyList(),
                GenerationRuntimePolicy.BACKGROUND_ALLOWED,
            ),
            postProcessor,
            summaryManager,
            ui,
            store,
            messageManager,
            contentStrategy,
            sessionManager,
        )
    }
    @Test
    fun `快照与终态持久化只写失败codec安全信封`() = runTest {
        val messageManager = mockk<MessageManager>(relaxed = true)
        val ui = mockk<GenerationUiPort>(relaxed = true)
        val runtime = DefaultChatGenerationRuntime(
            settings = mockk(relaxed = true),
            applicationScope = this,
            store = ChatStore(),
            agentRepository = mockk(),
            configResolver = mockk(),
            routeGate = mockk(),
            contextBuilder = mockk(),
            messageManager = messageManager,
            localProviderFactory = { mockk() },
            provider = mockk(relaxed = true),
            toolLedger = mockk(),
            toolExecutor = mockk(),
            postProcessor = mockk(),
            memoryManager = null,
            summaryManager = mockk(),
            sessionManager = mockk(),
            contentStrategy = mockk(),
            ui = ui,
        )
        val request = GenerationRequest(
            "s-safe", "a-safe", "u-safe", "question", emptyList(),
            GenerationRuntimePolicy.BACKGROUND_ALLOWED,
        )
        val cause = IllegalStateException("provider 原始失败正文")
        val failure = GenerationFailure(
            code = GenerationFailureCode.RATE_LIMIT,
            formatArgs = mapOf(GenerationFailure.KEY_RETRY_AFTER_SECONDS to "37"),
            technical = "HTTP 429 provider 原始失败正文",
            cause = cause,
        )
        val snapshot = GenerationSnapshot(content = "partial", failure = failure)
        val options = slot<UpdateMessageOptions>()
        val terminalError = slot<String?>()

        runtime.persist(request, snapshot)
        runtime.markTerminal(request, GenerationTerminalStatus.ERROR, snapshot, cause)

        val envelope = GenerationFailureCodec.encode(failure)
        verify(exactly = 1) {
            messageManager.updateMessageContent("s-safe", "a-safe", "partial", capture(options))
        }
        assertThat(options.captured.errorMessage).isEqualTo(envelope)
        assertThat(options.captured.errorMessage).doesNotContain("provider")
        assertThat(options.captured.isError).isTrue()
        assertThat(options.captured.clearToolCalls).isTrue()
        verify(exactly = 1) { ui.setError(failure) }
        coVerify(exactly = 1) {
            messageManager.markGenerationTerminal(
                "s-safe",
                "a-safe",
                "partial",
                "error",
                captureNullable(terminalError),
            )
        }
        assertThat(terminalError.captured).isEqualTo(envelope)
        assertThat(terminalError.captured).doesNotContain("provider")
    }

    @Test
    fun `StreamChunk错误转GenerationFailure保留稳定码参数与诊断`() = runTest {
        val settings = mockk<SharedPreferences>()
        every { settings.getStringSet(any(), any()) } returns emptySet()
        every { settings.getString(any(), any()) } returns ""
        every { settings.getFloat(any(), any()) } answers { secondArg() }
        every { settings.getInt(any(), any()) } answers { secondArg() }
        val store = ChatStore().apply {
            update {
                ChatState(
                    sessions = listOf(
                        Session(
                            id = "s-stream-error",
                            agentId = "agent",
                            modelId = "local::model",
                            messages = listOf(Message("a-stream-error", MessageRole.ASSISTANT, "")),
                        ),
                    ),
                )
            }
        }
        val contextBuilder = mockk<ContextBuilder>()
        coEvery { contextBuilder.buildContext(any()) } returns ContextBuilderResult(
            searchContext = "",
            ragContext = "",
            citations = emptyList(),
            ragReferences = emptyList(),
            ragUsage = null,
            finalSystemPrompt = "system",
        )
        val protocol = object : LlmProtocol {
            override val protocolType = ProtocolType.Local
            override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = flow {
                emit(
                    StreamChunk.Error(
                        code = GenerationFailureCode.RATE_LIMIT,
                        retryable = true,
                        retryAfterSeconds = 37,
                        technical = "HTTP 429 provider raw",
                    ),
                )
            }

            override suspend fun sendPromptSync(request: PromptRequest) = PromptResponse("")
            override fun cancel() = Unit
        }
        val provider = LlmProvider(protocol)
        val router = object : ProviderRequestRouter {
            override fun resolve(modelId: String): ProviderResolution = ProviderResolution.Success(
                ResolvedProviderModel(
                    modelId,
                    "model",
                    "local",
                    "Local",
                    UnifiedProviderConfig(ProtocolType.Local, "", "", "model"),
                ),
            )

            override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient = error("local")
        }
        val runtime = DefaultChatGenerationRuntime(
            settings = settings,
            applicationScope = this,
            store = store,
            agentRepository = mockk<IAgentRepository> { coEvery { getById(any()) } returns null },
            configResolver = AgentConfigResolver(settings),
            routeGate = ChatProviderRouteGate(router, UnconfinedTestDispatcher(testScheduler)),
            contextBuilder = contextBuilder,
            messageManager = mockk(relaxed = true),
            localProviderFactory = { provider },
            provider = provider,
            toolLedger = mockk(relaxed = true),
            toolExecutor = mockk(relaxed = true),
            postProcessor = mockk(relaxed = true),
            memoryManager = null,
            summaryManager = mockk(relaxed = true),
            sessionManager = mockk(relaxed = true),
            contentStrategy = mockk(relaxed = true),
            ui = mockk(relaxed = true),
        )
        val request = GenerationRequest(
            "s-stream-error", "a-stream-error", "u", "question", emptyList(),
            GenerationRuntimePolicy.BACKGROUND_ALLOWED,
        )

        runtime.prepare(request)
        assertThat(runtime.buildContext(request)).isNotNull()
        val chunk = runtime.stream(request, attempt = 0).toList().single() as GenerationChunk.Failure

        assertThat(chunk.failure.code).isEqualTo(GenerationFailureCode.RATE_LIMIT)
        assertThat(chunk.failure.retryAfterSeconds).isEqualTo(37)
        assertThat(chunk.failure.technical).isEqualTo("HTTP 429 provider raw")
    }

    @Test
    fun `StreamChunk错误转GenerationFailure保留同一cause实例`() = runTest {
        val cause = IllegalStateException("provider-private")
        val chunk = StreamChunk.Error(
            code = GenerationFailureCode.SERVER,
            technical = "diagnostic",
            cause = cause,
        )

        val mapper = DefaultChatGenerationRuntime::class.java.getDeclaredMethod(
            "toGenerationChunk",
            StreamChunk::class.java,
        ).apply { isAccessible = true }
        val failure = mapper.invoke(runtimeForPrivateMapping(), chunk) as GenerationChunk.Failure

        assertThat(failure.failure.cause).isSameInstanceAs(cause)
    }

    private fun runtimeForPrivateMapping() = DefaultChatGenerationRuntime(
        settings = mockk(relaxed = true),
        applicationScope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
        store = ChatStore(),
        agentRepository = mockk(),
        configResolver = mockk(),
        routeGate = mockk(),
        contextBuilder = mockk(),
        messageManager = mockk(relaxed = true),
        localProviderFactory = { mockk() },
        provider = mockk(relaxed = true),
        toolLedger = mockk(),
        toolExecutor = mockk(),
        postProcessor = mockk(),
        memoryManager = null,
        summaryManager = mockk(),
        sessionManager = mockk(),
        contentStrategy = mockk(),
        ui = mockk(relaxed = true),
    )

    @Test
    fun `RAG引用必须在网络建连前同步持久化且可从Repository重载`() = runTest {
        val settings = mockk<SharedPreferences>()
        every { settings.getStringSet(any(), any()) } returns emptySet()
        every { settings.getString(any(), any()) } returns ""
        every { settings.getFloat(any(), any()) } answers { secondArg() }
        every { settings.getInt(any(), any()) } answers { secondArg() }
        val assistant = Message("a-rag", MessageRole.ASSISTANT, "")
        val store = ChatStore().apply {
            update {
                ChatState(
                    sessions = listOf(
                        Session(
                            id = "s-rag",
                            agentId = "agent",
                            modelId = "local::model",
                            messages = listOf(
                                Message("u-rag", MessageRole.USER, "question"),
                                assistant,
                            ),
                        ),
                    ),
                )
            }
        }
        val persistedMessages = mutableMapOf(assistant.id to assistant, "u-rag" to Message("u-rag", MessageRole.USER, "question"))
        val messageRepository = object : IMessageRepository {
            override suspend fun insert(message: Message, sessionId: String) {
                persistedMessages[message.id] = message
            }

            override suspend fun updatePartial(messageId: String, updates: Map<String, Any?>) {
                val current = requireNotNull(persistedMessages[messageId])
                @Suppress("UNCHECKED_CAST")
                persistedMessages[messageId] = current.copy(
                    content = updates["content"] as? String ?: current.content,
                    turnContext = if ("turnContext" in updates) updates["turnContext"] as? String else current.turnContext,
                    requestInstruction = if ("requestInstruction" in updates) updates["requestInstruction"] as? String else current.requestInstruction,
                    ragReferences = if ("ragReferences" in updates) {
                        updates["ragReferences"] as? List<RagReference>
                    } else {
                        current.ragReferences
                    },
                )
            }

            override suspend fun delete(messageId: String) {
                persistedMessages.remove(messageId)
            }

            override suspend fun deleteBySessionId(sessionId: String) = Unit
            override suspend fun deleteMessagesAfter(sessionId: String, timestamp: Long) = Unit
            override suspend fun getById(messageId: String): Message? = persistedMessages[messageId]?.copy()
            override suspend fun getBySession(sessionId: String): List<Message> = persistedMessages.values.map { it.copy() }
            override suspend fun updateVectorizationStatus(
                messageId: String,
                status: String,
                isArchived: Boolean?,
            ) = Unit
        }
        val sessionRepository = object : ISessionRepository {
            override suspend fun create(session: Session) = Unit
            override suspend fun updatePartial(id: String, updates: Map<String, Any?>) = Unit
            override suspend fun delete(id: String) = Unit
            override suspend fun getById(id: String): Session? = null
            override suspend fun getAll(): List<Session> = emptyList()
        }
        val messageManager = MessageManager(store, messageRepository, sessionRepository, this)
        val reference = RagReference(
            id = "chunk-1",
            content = "持久化引用正文",
            source = "产品文档",
            score = 0.92f,
            documentId = "doc-1",
        )
        val contextBuilder = mockk<ContextBuilder>()
        coEvery { contextBuilder.buildContext(any()) } returns ContextBuilderResult(
            searchContext = "",
            ragContext = "持久化引用正文",
            citations = emptyList(),
            ragReferences = listOf(reference),
            ragUsage = null,
            finalSystemPrompt = "system with rag",
        )
        var reloadedAtNetworkEntry: Message? = null
        val protocol = object : LlmProtocol {
            override val protocolType = ProtocolType.Local
            override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> {
                reloadedAtNetworkEntry = messageRepository.getById(assistant.id)
                return flow {
                    emit(StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN))
                }
            }

            override suspend fun sendPromptSync(request: PromptRequest) = PromptResponse("")
            override fun cancel() = Unit
        }
        val provider = LlmProvider(protocol)
        val router = object : ProviderRequestRouter {
            override fun resolve(modelId: String): ProviderResolution = ProviderResolution.Success(
                ResolvedProviderModel(
                    modelId,
                    "model",
                    "local",
                    "Local",
                    UnifiedProviderConfig(ProtocolType.Local, "", "", "model"),
                ),
            )

            override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient = error("local")
        }
        val runtime = DefaultChatGenerationRuntime(
            settings = settings,
            applicationScope = this,
            store = store,
            agentRepository = mockk<IAgentRepository> { coEvery { getById(any()) } returns null },
            configResolver = AgentConfigResolver(settings),
            routeGate = ChatProviderRouteGate(router, UnconfinedTestDispatcher(testScheduler)),
            contextBuilder = contextBuilder,
            messageManager = messageManager,
            localProviderFactory = { provider },
            provider = provider,
            toolLedger = mockk(relaxed = true),
            toolExecutor = mockk(relaxed = true),
            postProcessor = mockk(relaxed = true),
            memoryManager = null,
            summaryManager = mockk(relaxed = true),
            sessionManager = SessionManager(store, sessionRepository),
            contentStrategy = mockk(relaxed = true),
            ui = mockk(relaxed = true),
        )
        val request = GenerationRequest(
            "s-rag", "a-rag", "u-rag", "question", emptyList(),
            GenerationRuntimePolicy.BACKGROUND_ALLOWED,
        )

        runtime.prepare(request)
        runtime.buildContext(request)
        runtime.stream(request, attempt = 0)

        assertThat(reloadedAtNetworkEntry).isNotNull()
        assertThat(reloadedAtNetworkEntry!!.ragReferences).isEqualTo(listOf(reference))
        assertThat(store.getSession("s-rag")?.messages?.last()?.ragReferences).containsExactly(reference)
    }

    @Test
    fun `Context构建取消必须上抛且不得写业务error终态`() = runTest {
        val settings = mockk<SharedPreferences>()
        every { settings.getStringSet(any(), any()) } returns emptySet()
        every { settings.getString(any(), any()) } returns ""
        every { settings.getFloat(any(), any()) } answers { secondArg() }
        every { settings.getInt(any(), any()) } answers { secondArg() }
        val store = ChatStore().apply {
            update {
                ChatState(
                    sessions = listOf(
                        Session(
                            id = "s-cancel",
                            agentId = "agent",
                            modelId = "local::model",
                            messages = listOf(Message("a-cancel", MessageRole.ASSISTANT, "")),
                        ),
                    ),
                )
            }
        }
        val router = object : ProviderRequestRouter {
            override fun resolve(modelId: String): ProviderResolution = ProviderResolution.Success(
                ResolvedProviderModel(
                    modelId,
                    "model",
                    "local",
                    "Local",
                    UnifiedProviderConfig(ProtocolType.Local, "", "", "model"),
                ),
            )
            override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient = error("local")
        }
        val contextBuilder = mockk<ContextBuilder>()
        val cancellation = CancellationException("context cancelled")
        coEvery { contextBuilder.buildContext(any()) } throws cancellation
        val messageManager = mockk<MessageManager>(relaxed = true)
        val runtime = DefaultChatGenerationRuntime(
            settings = settings,
            applicationScope = this,
            store = store,
            agentRepository = mockk<IAgentRepository> { coEvery { getById(any()) } returns null },
            configResolver = AgentConfigResolver(settings),
            routeGate = ChatProviderRouteGate(router, UnconfinedTestDispatcher(testScheduler)),
            contextBuilder = contextBuilder,
            messageManager = messageManager,
            localProviderFactory = { mockk() },
            provider = mockk(relaxed = true),
            toolLedger = mockk(relaxed = true),
            toolExecutor = mockk(relaxed = true),
            postProcessor = mockk(relaxed = true),
            memoryManager = null,
            summaryManager = mockk(relaxed = true),
            sessionManager = mockk(relaxed = true),
            contentStrategy = mockk(relaxed = true),
            ui = mockk(relaxed = true),
        )
        val request = GenerationRequest(
            "s-cancel", "a-cancel", "u", "question", emptyList(),
            GenerationRuntimePolicy.BACKGROUND_ALLOWED,
        )
        runtime.prepare(request)

        val failure = runCatching { runtime.buildContext(request) }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(cancellation)
        coVerify(exactly = 0) {
            messageManager.markGenerationTerminal(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `停止本轮时子代理已消耗的用量仍计入本轮助手消息`() = runTest {
        val settings = mockk<SharedPreferences>()
        every { settings.getInt(any(), any()) } answers { secondArg() }
        val store = ChatStore().apply {
            update {
                ChatState(sessions = listOf(Session(
                    id = "s1", agentId = "agent", executionMode = "auto",
                    messages = listOf(Message(id = "a1", role = MessageRole.ASSISTANT, content = "")),
                )))
            }
        }
        val ledger = com.promenar.infinity.data.subagent.SubagentRunStore(java.nio.file.Files.createTempDirectory("subagent-usage").toFile())
        val contentStrategy = mockk<ChatGenerationContentStrategy>(relaxed = true)
        every { contentStrategy.pendingApprovalIds(any(), any()) } returns emptyList()
        val cancellation = CancellationException("user-stop")
        val toolExecutor = mockk<ToolExecutor>(relaxed = true)
        coEvery { toolExecutor.executeTools(any(), any(), any(), any(), any()) } answers {
            // 子代理在取消前已记账，随后父轮被停止。
            ledger.add("s1", TokenUsage(input = 100, output = 20, total = 120))
            throw cancellation
        }
        val messageManager = mockk<MessageManager>(relaxed = true)
        val written = mutableListOf<TokenUsage?>()
        coEvery { messageManager.updateMessageContent("s1", "a1", any(), any()) } answers { written += arg<UpdateMessageOptions>(3).tokens }
        val runtime = DefaultChatGenerationRuntime(
            settings = settings,
            applicationScope = this,
            store = store,
            agentRepository = mockk<IAgentRepository>(),
            configResolver = mockk<AgentConfigResolver>(),
            routeGate = mockk<ChatProviderRouteGate>(),
            contextBuilder = mockk<ContextBuilder>(),
            messageManager = messageManager,
            localProviderFactory = { mockk<LlmProvider>() },
            provider = mockk<LlmProvider>(relaxed = true),
            toolLedger = mockk<ToolExecutionLedger>(),
            toolExecutor = toolExecutor,
            postProcessor = mockk<PostProcessor>(),
            memoryManager = null,
            summaryManager = mockk<SummaryManager>(),
            sessionManager = mockk<SessionManager>(),
            contentStrategy = contentStrategy,
            ui = mockk<GenerationUiPort>(relaxed = true),
            subagentUsage = ledger,
        )
        val request = GenerationRequest("s1", "a1", "u1", "hello", emptyList(), GenerationRuntimePolicy.BACKGROUND_ALLOWED)

        val failure = runCatching { runtime.handleTools(request, listOf(GenerationToolCall("tool-1", "agent_task", "{}"))) }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(cancellation)
        assertThat(written.single()!!.summaryTotal).isEqualTo(120)
        assertThat(ledger.drain("s1")).isNull()
    }

    @Test
    fun `无审批工具由runtime执行并返回CONTINUE而非递归生成`() = runTest {
        val settings = mockk<SharedPreferences>()
        every { settings.getInt(any(), any()) } answers { secondArg() }
        val store = ChatStore().apply {
            update { ChatState(sessions = listOf(Session(id = "s1", agentId = "agent", executionMode = "auto"))) }
        }
        val contentStrategy = mockk<ChatGenerationContentStrategy>(relaxed = true)
        every { contentStrategy.pendingApprovalIds(any(), any()) } returns emptyList()
        val toolExecutor = mockk<ToolExecutor>(relaxed = true)
        coEvery { toolExecutor.executeTools(any(), any(), any(), any(), any()) } returns Unit
        val runtime = DefaultChatGenerationRuntime(
            settings = settings,
            applicationScope = this,
            store = store,
            agentRepository = mockk<IAgentRepository>(),
            configResolver = mockk<AgentConfigResolver>(),
            routeGate = mockk<ChatProviderRouteGate>(),
            contextBuilder = mockk<ContextBuilder>(),
            messageManager = mockk<MessageManager>(relaxed = true),
            localProviderFactory = { mockk<LlmProvider>() },
            provider = mockk<LlmProvider>(relaxed = true),
            toolLedger = mockk<ToolExecutionLedger>(),
            toolExecutor = toolExecutor,
            postProcessor = mockk<PostProcessor>(),
            memoryManager = null,
            summaryManager = mockk<SummaryManager>(),
            sessionManager = mockk<SessionManager>(),
            contentStrategy = contentStrategy,
            ui = mockk<GenerationUiPort>(relaxed = true),
        )
        val request = GenerationRequest(
            sessionId = "s1",
            assistantMessageId = "a1",
            userMessageId = "u1",
            userContent = "hello",
            imageDataUrls = emptyList(),
            runtimePolicy = GenerationRuntimePolicy.BACKGROUND_ALLOWED,
        )
        val calls = listOf(GenerationToolCall("tool-1", "search", "{}"))

        val decision = runtime.handleTools(request, calls)

        assertThat(decision).isEqualTo(GenerationToolDecision.CONTINUE)
        coVerify(exactly = 1) {
            toolExecutor.executeTools(
                "s1",
                "a1",
                match { it.single().id == "tool-1" },
                match { it.isEmpty() },
                match { it.isEmpty() },
            )
        }
    }

    @Test
    fun `CONTINUE强刷assistant后多轮Prompt保留全部工具配对`() = runTest {
        val (prompts, searches, executorPreparedSnapshots) = runThreeRoundToolScenario()
        val advertisedNames = listOf("read_file", "search")

        assertThat(prompts).hasSize(3)
        val third = prompts[2].messages
        assertThat(third.filter { it.role == "assistant" }.flatMap { it.toolCalls.orEmpty() }.map { it.id })
            .containsExactly("call-1", "call-2").inOrder()
        assertThat(third.filter { it.role == "tool" }.map { it.toolCallId })
            .containsExactly("call-1", "call-2").inOrder()
        assertThat(executorPreparedSnapshots).isNotEmpty()
        assertThat(executorPreparedSnapshots.all { tools -> tools.map { it.function.name } == advertisedNames }).isTrue()
        assertThat(prompts.map { it.toolChoice }).containsExactly(
            ProtocolToolChoice.AUTO, ProtocolToolChoice.AUTO, ProtocolToolChoice.AUTO,
        ).inOrder()
        // 检索只依赖用户问题：只执行一次，冻结资料随同一条用户消息重放。
        assertThat(searches).isEqualTo(1)
        assertThat(prompts.all { prompt ->
            prompt.messages.first { it.role == "user" }.turnContext.orEmpty().contains("RAG-CONTEXT-MARKER")
        }).isTrue()
    }

    @Test
    fun `工具预算耗尽后的收尾请求禁止调用工具但仍声明工具`() = runTest {
        val (prompts, _, _) = runThreeRoundToolScenario(loopLimit = 1)

        assertThat(prompts).hasSize(3)
        assertThat(prompts[0].toolChoice).isEqualTo(ProtocolToolChoice.AUTO)
        assertThat(prompts[2].toolChoice).isEqualTo(ProtocolToolChoice.NONE)
        assertThat(prompts[2].tools.orEmpty().map { it.function.name }).containsExactly("search", "read_file")
        assertThat(prompts[2].messages.last().content)
            .contains("## Tool Budget Exhausted")
    }

    @Test
    fun `本轮已消耗工具用量只统计本轮用户消息之后的非目标助手消息`() {
        val call = { id: String -> com.promenar.infinity.data.model.ToolCall(id, "search", "{}") }
        val messages = listOf(
            Message("u0", MessageRole.USER, "old"),
            Message("a-old", MessageRole.ASSISTANT, "", toolCalls = listOf(call("x"))),
            Message("u1", MessageRole.USER, "question"),
            Message("a1", MessageRole.ASSISTANT, "", toolCalls = listOf(call("c1"), call("c2"))),
            Message("t1", MessageRole.TOOL, "r", toolCallId = "c1"),
            Message("a2", MessageRole.ASSISTANT, "", toolCalls = listOf(call("c3"))),
            Message("a-replaced", MessageRole.ASSISTANT, "", toolCalls = listOf(call("c4"))),
            Message("a-target", MessageRole.ASSISTANT, ""),
            Message("u2", MessageRole.USER, "next"),
            Message("a-next", MessageRole.ASSISTANT, "", toolCalls = listOf(call("c5"))),
        )
        val request = GenerationRequest(
            "s1", "a-target", "u1", "question", emptyList(), GenerationRuntimePolicy.BACKGROUND_ALLOWED,
            messageIdsToReplace = setOf("a-replaced"),
        )

        assertThat(consumedToolUsageInTurn(messages, request))
            .isEqualTo(com.promenar.infinity.domain.generation.GenerationToolUsage(rounds = 2, calls = 3, tokens = 6, tokensEstimated = true))
        assertThat(consumedToolUsageInTurn(messages, request.copy(userMessageId = null)))
            .isEqualTo(com.promenar.infinity.domain.generation.GenerationToolUsage())
    }

    @Test
    fun `四轮无正文工具后提醒持久化并保持历史前缀且不进入正文`() = runTest {
        var saved: Session? = null
        val prefixes = mutableListOf<List<com.promenar.infinity.data.remote.protocol.ProtocolMessage>>()
        var latestPrefix = emptyList<com.promenar.infinity.data.remote.protocol.ProtocolMessage>()
        val writes = mutableListOf<String>()
        val (prompts, _, _) = runThreeRoundToolScenario(toolRoundCount = 5,
            onProtocolMessages = { latestPrefix = it }, onPrompt = { prefixes += latestPrefix }, onPersist = { writes += it },
            onCompleted = { saved = it.getSession("s1") })
        assertThat(prompts).hasSize(6)
        assertThat(prompts.take(4).flatMap { it.messages }.any { it.content.contains("[System reminder]") }).isFalse()
        for (prompt in prompts.drop(4)) {
            assertThat(prompt.messages.last().role).isEqualTo("user")
            assertThat(prompt.messages.last().content).contains("[System reminder]")
        }
        assertThat(prefixes).hasSize(prompts.size)
        prompts.zip(prefixes).forEachIndexed { index, (prompt, prefix) ->
            assertThat(prompt.messages).isEqualTo(prefix)
        }
        assertThat(saved!!.messages.none { it.content.contains("[System reminder]") }).isTrue()
        assertThat(saved!!.messages.any { it.requestInstruction.orEmpty().contains("[System reminder]") }).isTrue()
        assertThat(writes.any { it.contains("requestInstruction=") && it.contains("[System reminder]") }).isTrue()
        prompts.zipWithNext().forEach { (before, after) ->
            assertThat(after.messages.take(before.messages.size)).isEqualTo(before.messages)
        }
    }

    @Test
    fun `第四轮工具后预算收尾不附加进展提醒`() = runTest {
        val (prompts, _, _) = runThreeRoundToolScenario(loopLimit = 4, toolRoundCount = 5)
        assertThat(prompts.last().toolChoice).isEqualTo(ProtocolToolChoice.NONE)
        assertThat(prompts.last().messages.last().content).doesNotContain("[System reminder]")
        assertThat(prompts.last().messages.last().content).contains("Tool Budget Exhausted")
        assertThat(prompts.last().messages.take(prompts[prompts.lastIndex - 1].messages.size))
            .isEqualTo(prompts[prompts.lastIndex - 1].messages)
    }

    @Test
    fun `正文重置工具轮数而思考和空白正文不重置`() = runTest {
        val (prompts, _, _) = runThreeRoundToolScenario(toolRoundCount = 6, progressAtRound = 3, reasoningEveryRound = true)
        assertThat(prompts.take(6).flatMap { it.messages }.none { it.content.contains("[System reminder]") }).isTrue()
        assertThat(prompts[6].messages.last().content).contains("[System reminder]")
    }

    @Test
    fun `一轮工具期间超过六十秒也在请求末尾提醒`() = runTest {
        var now = 0L
        val (prompts, _, _) = runThreeRoundToolScenario(toolRoundCount = 1, reasoningEveryRound = true,
            reminder = ToolProgressReminder(clock = { now }), onToolsFinished = { now = 60_001L })
        assertThat(prompts[0].messages.none { it.content.contains("[System reminder]") }).isTrue()
        assertThat(prompts[1].messages.last().content).contains("[System reminder]")
    }

    @Test
    fun `提醒后输出正文但网络重试仍复用相同请求提醒`() = runTest {
        val (prompts, _, _) = runThreeRoundToolScenario(toolRoundCount = 5, failAfterProgressAtRound = 5)
        assertThat(prompts).hasSize(7)
        assertThat(prompts[4].messages.last().content).contains("[System reminder]")
        assertThat(prompts[5].messages).isEqualTo(prompts[4].messages)
        assertThat(prompts[6].messages.last().content).doesNotContain("[System reminder]")
        assertThat(prompts[6].messages.take(prompts[4].messages.size)).isEqualTo(prompts[4].messages)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.runThreeRoundToolScenario(
        loopLimit: Int? = null,
        toolRoundCount: Int = 2,
        progressAtRound: Int? = null,
        failAfterProgressAtRound: Int? = null,
        reasoningEveryRound: Boolean = false,
        reminder: ToolProgressReminder = ToolProgressReminder(),
        onToolsFinished: () -> Unit = {},
        onProtocolMessages: (List<com.promenar.infinity.data.remote.protocol.ProtocolMessage>) -> Unit = {},
        onPrompt: (PromptRequest) -> Unit = {},
        onPersist: (String) -> Unit = {},
        resultContent: String? = null,
        outputStore: com.promenar.infinity.data.repository.ToolOutputStore? = null,
        onCompleted: (ChatStore) -> Unit = {},
    ): Triple<List<PromptRequest>, Int, List<List<ProtocolTool>>> {
        val settings = mockk<SharedPreferences>()
        every { settings.getInt(any(), any()) } answers { secondArg() }
        loopLimit?.let { every { settings.getInt("loop_limit", any()) } returns it }
        every { settings.getStringSet(any(), any()) } returns setOf("search", "read_file")
        every { settings.getString(any(), any()) } returns ""
        every { settings.getBoolean(any(), any()) } returns true
        every { settings.getFloat(any(), any()) } answers { secondArg() }
        val user = Message("u1", MessageRole.USER, "question")
        val initialAssistant = Message("a0", MessageRole.ASSISTANT, "")
        val store = ChatStore().apply {
            update {
                ChatState(
                    sessions = listOf(
                        Session(
                            id = "s1",
                            agentId = "agent",
                            modelId = "local::model",
                            executionMode = "auto",
                            options = SessionOptions(toolsEnabled = true, webSearch = true),
                            messages = listOf(user, initialAssistant),
                        ),
                    ),
                )
            }
        }
        val messageRepository = object : IMessageRepository {
            override suspend fun insert(message: Message, sessionId: String) { onPersist(message.content) }
            override suspend fun updatePartial(messageId: String, updates: Map<String, Any?>) { onPersist(updates.toString()) }
            override suspend fun delete(messageId: String) = Unit
            override suspend fun deleteBySessionId(sessionId: String) = Unit
            override suspend fun deleteMessagesAfter(sessionId: String, timestamp: Long) = Unit
            override suspend fun getById(messageId: String): Message? = null
            override suspend fun getBySession(sessionId: String): List<Message> = emptyList()
            override suspend fun updateVectorizationStatus(messageId: String, status: String, isArchived: Boolean?) = Unit
        }
        val sessionRepository = object : ISessionRepository {
            override suspend fun create(session: Session) = Unit
            override suspend fun updatePartial(id: String, updates: Map<String, Any?>) = Unit
            override suspend fun delete(id: String) = Unit
            override suspend fun getById(id: String): Session? = null
            override suspend fun getAll(): List<Session> = emptyList()
        }
        val messageManager = MessageManager(store, messageRepository, sessionRepository, this)
        val prompts = mutableListOf<PromptRequest>()
        var completedRounds = 0
        var retryIssued = false
        val protocol = object : LlmProtocol {
            override val protocolType = ProtocolType.Local
            override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = flow {
                prompts += request
                onPrompt(request)
                val round = completedRounds + 1
                if (reasoningEveryRound) {
                    emit(StreamChunk.TextDelta("", reasoning = "正在思考"))
                    emit(StreamChunk.TextDelta(" \n"))
                }
                if (round == progressAtRound) emit(StreamChunk.TextDelta("正在核对资料"))
                if (round == failAfterProgressAtRound && !retryIssued) {
                    retryIssued = true
                    emit(StreamChunk.TextDelta("已取得阶段结果"))
                    emit(StreamChunk.Error(code = GenerationFailureCode.NETWORK, retryable = true))
                    return@flow
                }
                if (round <= toolRoundCount) {
                    val name = if (round == 1) "search" else "read_file"
                    emit(StreamChunk.ToolCallDelta("call-$round", name, """{"round":$round}""", 0))
                } else emit(StreamChunk.TextDelta("final"))
                completedRounds++
                emit(StreamChunk.Completed(
                    if (round <= toolRoundCount) {
                        com.promenar.infinity.domain.generation.CompletionReason.TOOL_CALLS
                    } else {
                        com.promenar.infinity.domain.generation.CompletionReason.END_TURN
                    },
                    if (round <= toolRoundCount) listOf("call-$round") else emptyList(),
                ))
            }
            override suspend fun sendPromptSync(request: PromptRequest) = PromptResponse("")
            override fun cancel() = Unit
        }
        val provider = LlmProvider(protocol)
        val router = object : ProviderRequestRouter {
            override fun resolve(modelId: String): ProviderResolution = ProviderResolution.Success(
                ResolvedProviderModel(
                    modelId,
                    "model",
                    "local",
                    "Local",
                    UnifiedProviderConfig(ProtocolType.Local, "", "", "model"),
                ),
            )
            override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient = error("local")
        }
        var searches = 0
        val countingRetrieval = object : com.promenar.infinity.ui.chat.manager.RagProvider {
            override suspend fun retrieveContext(query: String, sessionId: String, options: RagOptions,
                onProgress: ((String, Int, String?) -> Unit)?): com.promenar.infinity.ui.chat.manager.RagRetrievalResult {
                searches++
                return com.promenar.infinity.ui.chat.manager.RagRetrievalResult("RAG-CONTEXT-MARKER", emptyList(), null)
            }
        }
        val toolExecutor = mockk<ToolExecutor>()
        val executorPreparedSnapshots = mutableListOf<List<ProtocolTool>>()
        coEvery { toolExecutor.rejectTools(any(), any(), any(), any()) } returns Unit
        coEvery { toolExecutor.executeTools(any(), any(), any(), any(), any(), any()) } coAnswers {
            val sessionId = firstArg<String>()
            val calls = thirdArg<List<com.promenar.infinity.data.model.ToolCall>>()
            executorPreparedSnapshots += arg<List<ProtocolTool>>(4)
            calls.forEach { call ->
                messageManager.addMessage(sessionId, Message("tool-${call.id}", MessageRole.TOOL,
                    resultContent ?: "result-${call.id}", toolCallId = call.id, name = call.name))
            }
            onToolsFinished()
        }
        val advertisedTools = listOf(
            ProtocolTool(
                function = ProtocolToolFunction("search", "search", "{\"type\":\"object\"}"),
                risk = ToolRisk.SAFE_READ,
                runtimeToolId = "search",
                sourceId = "builtin",
            ),
            ProtocolTool(
                function = ProtocolToolFunction("read_file", "read", "{\"type\":\"object\"}"),
                risk = ToolRisk.SAFE_READ,
                runtimeToolId = "read_file",
                sourceId = "builtin",
            ),
        )
        val skillRegistry = mockk<SkillRegistry>()
        every { skillRegistry.getAllTools(any()) } returns advertisedTools
        val contentStrategy = DefaultChatGenerationContentStrategy(settings, skillRegistry)
        val recordingStrategy = object : ChatGenerationContentStrategy by contentStrategy {
            override fun buildProtocolMessages(session: Session, systemPrompt: String, pinnedUserMessageId: String?,
                excludedMessageIds: Set<String>, replaySource: String?) = contentStrategy.buildProtocolMessages(
                session, systemPrompt, pinnedUserMessageId, excludedMessageIds, replaySource,
            ).also(onProtocolMessages)
        }
        val runtime = DefaultChatGenerationRuntime(
            settings = settings,
            applicationScope = this,
            store = store,
            agentRepository = mockk<IAgentRepository> { coEvery { getById(any()) } returns null },
            configResolver = AgentConfigResolver(settings),
            routeGate = ChatProviderRouteGate(router, UnconfinedTestDispatcher(testScheduler)),
            contextBuilder = ContextBuilder(ragProvider = countingRetrieval),
            messageManager = messageManager,
            localProviderFactory = { provider },
            provider = provider,
            toolLedger = mockk(relaxed = true),
            toolExecutor = toolExecutor,
            postProcessor = mockk(relaxed = true),
            memoryManager = null,
            summaryManager = mockk(relaxed = true),
            sessionManager = SessionManager(store, sessionRepository),
            contentStrategy = recordingStrategy,
            ui = mockk(relaxed = true),
            outputStore = outputStore,
            toolProgressReminder = reminder,
        )
        val request = GenerationRequest(
            "s1", "a0", "u1", "question", emptyList(), GenerationRuntimePolicy.BACKGROUND_ALLOWED,
        )

        ChatGenerationRunner(runtime).run(request) {}
        onCompleted(store)
        return Triple(prompts, searches, executorPreparedSnapshots)
    }
}

class DefaultChatGenerationRuntimeTailnetTest {
    private class OfflineConnector(private val starts: Boolean) : com.promenar.infinity.data.tailnet.TailnetConnector {
        val mutableState = kotlinx.coroutines.flow.MutableStateFlow(
            com.promenar.infinity.data.tailnet.TailnetState(com.promenar.infinity.data.tailnet.TailnetPhase.STOPPED),
        )
        override val state: kotlinx.coroutines.flow.StateFlow<com.promenar.infinity.data.tailnet.TailnetState> = mutableState
        override fun isTailnetHost(host: String): Boolean = host.endsWith(".ts.net")
        override suspend fun ensureStarted(force: Boolean, timeoutMillis: Long): Boolean {
            if (starts) mutableState.value = com.promenar.infinity.data.tailnet.TailnetState(com.promenar.infinity.data.tailnet.TailnetPhase.RUNNING)
            return starts
        }
    }

    private fun kotlinx.coroutines.test.TestScope.runtime(
        connector: com.promenar.infinity.data.tailnet.TailnetConnector,
        ui: GenerationUiPort,
        sent: MutableList<PromptRequest>,
    ): DefaultChatGenerationRuntime {
        val settings = mockk<SharedPreferences>()
        every { settings.getStringSet(any(), any()) } returns emptySet()
        every { settings.getString(any(), any()) } returns ""
        every { settings.getFloat(any(), any()) } answers { secondArg() }
        every { settings.getInt(any(), any()) } answers { secondArg() }
        val store = ChatStore().apply {
            update {
                ChatState(sessions = listOf(Session(
                    id = "s-tailnet", agentId = "agent", modelId = "gateway::qwen",
                    inferenceParams = InferenceParams(streamTimeout = 7),
                    options = SessionOptions(webSearch = true, enableGeminiSearch = true),
                    messages = listOf(Message("u", MessageRole.USER, "question"), Message("a-tailnet", MessageRole.ASSISTANT, "")),
                )))
            }
        }
        val contextBuilder = mockk<ContextBuilder>()
        coEvery { contextBuilder.buildContext(any()) } returns ContextBuilderResult(
            searchContext = "", ragContext = "", citations = emptyList(), ragReferences = emptyList(), ragUsage = null, finalSystemPrompt = "system",
        )
        val protocol = object : LlmProtocol {
            override val protocolType = ProtocolType.Generic_OpenAI_Compat
            override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = flow {
                sent += request
                emit(StreamChunk.TextDelta("ok"))
                emit(StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN))
            }
            override suspend fun sendPromptSync(request: PromptRequest) = PromptResponse("")
            override fun cancel() = Unit
        }
        val config = UnifiedProviderConfig(ProtocolType.Generic_OpenAI_Compat, "https://gpu.tail1.ts.net/v1", "k", "qwen")
        val router = object : ProviderRequestRouter {
            override fun resolve(modelId: String): ProviderResolution = ProviderResolution.Success(
                ResolvedProviderModel(modelId, "qwen", "gateway", "Gateway", config),
            )
            override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient =
                UnifiedLlmClient({ config }, protocolFactory = { protocol })
        }
        return DefaultChatGenerationRuntime(
            settings = settings,
            applicationScope = this,
            store = store,
            agentRepository = mockk<IAgentRepository> { coEvery { getById(any()) } returns null },
            configResolver = AgentConfigResolver(settings),
            routeGate = ChatProviderRouteGate(router, UnconfinedTestDispatcher(testScheduler)),
            contextBuilder = contextBuilder,
            messageManager = mockk(relaxed = true),
            localProviderFactory = { error("cloud") },
            provider = LlmProvider(protocol),
            toolLedger = mockk(relaxed = true),
            toolExecutor = mockk(relaxed = true),
            postProcessor = mockk(relaxed = true),
            memoryManager = null,
            summaryManager = mockk(relaxed = true),
            sessionManager = mockk(relaxed = true),
            contentStrategy = mockk(relaxed = true),
            ui = ui,
            tailnetGate = com.promenar.infinity.data.tailnet.TailnetConnectGate(connector, clock = { testScheduler.currentTime }),
        )
    }

    private val request = GenerationRequest("s-tailnet", "a-tailnet", "u", "question", emptyList(), GenerationRuntimePolicy.BACKGROUND_ALLOWED)

    @Test
    fun `tailnet 接口在内置 Tailscale 起不来时不发请求并给出专用失败`() = runTest {
        val waits = mutableListOf<TailnetWaitState?>()
        val ui = mockk<GenerationUiPort>(relaxed = true)
        every { ui.setTailnetWait(any()) } answers { waits += firstArg<TailnetWaitState?>() }
        val sent = mutableListOf<PromptRequest>()
        val runtime = runtime(OfflineConnector(starts = false), ui, sent)
        runtime.prepare(request)
        assertThat(runtime.buildContext(request)).isEqualTo(GenerationPreparationOutcome.Ready)

        val chunk = runtime.stream(request, attempt = 0).toList().single() as GenerationChunk.Failure

        assertThat(chunk.failure.code).isEqualTo(GenerationFailureCode.TAILNET_UNAVAILABLE)
        assertThat(chunk.failure.formatArgs[GenerationFailure.KEY_TAILNET_REASON]).isEqualTo("FAILED")
        assertThat(chunk.failure.retryable).isFalse()
        assertThat(sent).isEmpty()
        assertThat(waits.first()).isInstanceOf(TailnetWaitState.Waiting::class.java)
        assertThat(waits.last()).isNull()
    }

    @Test
    fun `等到内置 Tailscale 接入后继续发送并提示已连接`() = runTest {
        val waits = mutableListOf<TailnetWaitState?>()
        val ui = mockk<GenerationUiPort>(relaxed = true)
        every { ui.setTailnetWait(any()) } answers { waits += firstArg<TailnetWaitState?>() }
        val sent = mutableListOf<PromptRequest>()
        val runtime = runtime(OfflineConnector(starts = true), ui, sent)
        runtime.prepare(request)
        assertThat(runtime.buildContext(request)).isEqualTo(GenerationPreparationOutcome.Ready)

        val chunks = runtime.stream(request, attempt = 0).toList()

        assertThat(chunks.filterIsInstance<GenerationChunk.Failure>()).isEmpty()
        assertThat(sent).hasSize(1)
        assertThat(sent.single().streamTimeout).isNull()
        assertThat(sent.single().webSearch).isFalse()
        assertThat(sent.single().enableGeminiSearch).isNull()
        assertThat(waits.last()).isEqualTo(TailnetWaitState.Connected)
    }
}
