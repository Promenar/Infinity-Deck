package com.promenar.infinity.ui.hub

import app.cash.turbine.test
import com.promenar.infinity.data.agent.AgentRagConfig
import com.promenar.infinity.data.agent.AgentRetrievalConfig
import com.promenar.infinity.data.agent.AgentRetrievalMode
import com.promenar.infinity.data.agent.AgentSubagentModelPolicy
import com.promenar.infinity.data.agent.KnowledgeFileAccess
import com.promenar.infinity.data.repository.AgentRepository
import com.promenar.infinity.domain.model.Agent
import com.promenar.infinity.domain.model.AgentField
import com.promenar.infinity.domain.model.AgentFieldUpdate
import com.promenar.infinity.domain.model.ExecutionMode
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentEditViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var repo: AgentRepository

    /** 字段级写入后的“数据库”内容；未写过的智能体按 observeById 的首个值读取。 */
    private val stored = mutableMapOf<String, Agent>()

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repo = mockk(relaxed = true)
        stored.clear()
        coEvery { repo.getById(any()) } coAnswers { stored[firstArg()] ?: repo.observeById(firstArg()).first() }
        stubFieldWrites()
    }

    private fun stubFieldWrites() {
        coEvery { repo.updateFields(any(), any(), any(), any(), any()) } coAnswers {
            applyFieldWrite(firstArg(), secondArg(), thirdArg())
        }
    }

    /** 按仓库契约只写给出的字段。 */
    private suspend fun applyFieldWrite(id: String, fields: Set<AgentField>, values: Agent): AgentFieldUpdate {
        val current = stored[id] ?: repo.observeById(id).first() ?: return AgentFieldUpdate.NotFound
        val written = fields.fold(current) { agent, field -> field.copy(agent, values) }
        stored[id] = written
        return AgentFieldUpdate.Updated(written)
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `无宿主删除及宿主重建后均清理保存的会话路由且重放一次`() = runTest {
        val sessions = mockk<com.promenar.infinity.data.repository.ISessionRepository>()
        coEvery { sessions.getAll() } returns listOf(com.promenar.infinity.data.model.Session("s", "a"))
        val deletion = com.promenar.infinity.domain.usecase.DeleteAgentUseCase(repo, sessions)
        deletion("a")
        val first = mutableListOf<Set<String>>()
        val release = deletion.registerSessionCloser { first += it }
        assertThat(first).containsExactly(setOf("s"))
        deletion("a")
        assertThat(first).hasSize(2)
        release()
        val restored = mutableListOf<Set<String>>()
        val releaseRestored = deletion.registerSessionCloser { restored += it }
        assertThat(restored).containsExactly(setOf("s"))
        releaseRestored()
        deletion.registerSessionCloser { error("关闭记录已重放，不应重复") }
    }

    @Test
    fun `编辑页通过共享删除流程先关闭所属会话再删智能体`() = runTest {
        val events = mutableListOf<String>()
        val sessions = mockk<com.promenar.infinity.data.repository.ISessionRepository>()
        coEvery { sessions.getAll() } returns listOf(
            com.promenar.infinity.data.model.Session("s1", "a1"), com.promenar.infinity.data.model.Session("other", "a2"))
        coEvery { repo.delete("a1") } coAnswers { events += "delete" }
        val deletion = com.promenar.infinity.domain.usecase.DeleteAgentUseCase(repo, sessions)
        deletion.registerSessionCloser { ids ->
            assertThat(ids).containsExactly("s1")
            events += "close"
        }
        val vm = AgentEditViewModel(repo, deleteAgentUseCase = deletion)
        vm.deleteAgent("a1") { events += "callback" }
        assertThat(events).containsExactly("close", "delete", "callback").inOrder()
        assertThat(vm.saveError.value).isNull()
    }

    @Test
    fun `关闭会话失败不删除智能体且可重试`() = runTest {
        val sessions = mockk<com.promenar.infinity.data.repository.ISessionRepository>()
        coEvery { sessions.getAll() } returns emptyList()
        val deletion = com.promenar.infinity.domain.usecase.DeleteAgentUseCase(repo, sessions)
        val release = deletion.registerSessionCloser { error("关闭失败") }
        val vm = AgentEditViewModel(repo, deleteAgentUseCase = deletion)
        var callbacks = 0
        vm.deleteAgent("a1") { callbacks++ }
        coVerify(exactly = 0) { repo.delete(any()) }
        assertThat(vm.saveError.value).isEqualTo(AgentEditErrorCode.DELETE_FAILED)
        release()
        vm.retryLastFailure()
        coVerify(exactly = 1) { repo.delete("a1") }
        assertThat(callbacks).isEqualTo(1)
    }

    @Test
    fun `loadAgent populates state from repository`() = runTest {
        val agent = Agent(
            id = "a1",
            name = "Test Agent",
            description = "desc",
            systemPrompt = "prompt",
            modelId = "gpt-4",
            icon = "🧪",
            color = "#FF0000",
            avatarPath = "/path.png",
            isPinned = true,
            temperature = 0.5,
            topP = 0.8,
            maxTokens = 2048,
            useInheritedConfig = false,
            ragConfig = AgentRagConfig(docChunkSize = 999),
            retrievalConfig = AgentRetrievalConfig(memoryLimit = 3),
            executionMode = ExecutionMode.WORKSPACE,
            createdAt = 100L
        )
        every { repo.observeById("a1") } returns flowOf(agent)

        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")

        assertThat(vm.name.value).isEqualTo("Test Agent")
        assertThat(vm.description.value).isEqualTo("desc")
        assertThat(vm.systemPrompt.value).isEqualTo("prompt")
        assertThat(vm.selectedModel.value).isEqualTo("gpt-4")
        assertThat(vm.selectedIcon.value).isEqualTo("🧪")
        assertThat(vm.selectedColor.value).isEqualTo("#FF0000")
        assertThat(vm.avatarPath.value).isEqualTo("/path.png")
        assertThat(vm.isPinned.value).isTrue()
        assertThat(vm.sampling.value.temperature).isEqualTo(0.5)
        assertThat(vm.sampling.value.topP).isEqualTo(0.8)
        assertThat(vm.sampling.value.maxTokens).isEqualTo(2048)
    }

    @Test
    fun `missing agent exposes typed not found instead of silent empty editor`() = runTest {
        every { repo.observeById("missing") } returns flowOf(null)
        val vm = AgentEditViewModel(repo)

        vm.loadAgent("missing")

        assertThat(vm.saveError.value).isEqualTo(AgentEditErrorCode.NOT_FOUND)
        assertThat(vm.name.value).isEmpty()
    }

    @Test
    fun `avatar importer empty result preserves current avatar and exposes typed failure`() = runTest {
        val agent = Agent(id = "a1", name = "Agent", avatarPath = "/avatar/original.png")
        every { repo.observeById("a1") } returns flowOf(agent)
        val vm = AgentEditViewModel(
            repo,
            avatarImporter = { _, _ -> null },
        )
        vm.loadAgent("a1")

        vm.importAvatar(mockk(relaxed = true))

        assertThat(vm.avatarPath.value).isEqualTo("/avatar/original.png")
        assertThat(vm.saveError.value).isEqualTo(AgentEditErrorCode.AVATAR_IMPORT_FAILED)
        coVerify(exactly = 0) { repo.updateFields(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `successful avatar import persists before leaving editor without hasChanges collector`() = runTest {
        val agent = Agent(id = "a1", name = "Agent", avatarPath = "/avatar/original.png")
        every { repo.observeById("a1") } returns flowOf(agent)
        val vm = AgentEditViewModel(
            repo,
            avatarImporter = { _, _ -> "/avatar/replacement.png" },
        )
        vm.loadAgent("a1")

        vm.importAvatar(mockk(relaxed = true))

        coVerify(exactly = 1) {
            repo.updateFields(any(), any(), match { it.id == "a1" && it.avatarPath == "/avatar/replacement.png" }, any(), any())
        }
        assertThat(vm.avatarPath.value).isEqualTo("/avatar/replacement.png")
    }

    @Test
    fun `delayed field autosave does not depend on observing hasChanges`() = runTest {
        val agent = Agent(id = "a1", name = "Original")
        every { repo.observeById("a1") } returns flowOf(agent)
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")

        vm.setName("Persisted")
        advanceTimeBy(1_001)
        runCurrent()

        coVerify(exactly = 1) { repo.updateFields(any(), any(), match { it.name == "Persisted" }, any(), any()) }
    }

    @Test
    fun `编辑普通字段保留智能体检索方式、文件访问与旧继承标记`() = runTest {
        val config = AgentRetrievalConfig(retrievalMode = AgentRetrievalMode.TOOL,
            knowledgeFileAccess = KnowledgeFileAccess.ALWAYS)
        every { repo.observeById("a1") } returns flowOf(Agent("a1", "名称", useInheritedConfig = true,
            retrievalConfig = config))
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")

        assertThat(vm.retrievalConfig.value.retrievalMode).isEqualTo(AgentRetrievalMode.TOOL)
        assertThat(vm.retrievalConfig.value.knowledgeFileAccess).isEqualTo(KnowledgeFileAccess.ALWAYS)
        vm.setName("新名称")
        advanceTimeBy(1_001)
        runCurrent()

        coVerify(exactly = 1) { repo.updateFields(any(), any(), match {
            it.name == "新名称" && it.useInheritedConfig && it.retrievalConfig == config
        }, any(), any()) }
    }

    @Test
    fun `检索方式与知识库文件访问各自立即持久化且保留旧检索参数`() = runTest {
        val legacyRag = AgentRagConfig(docChunkSize = 123)
        val config = AgentRetrievalConfig(memoryLimit = 11, retrievalMode = AgentRetrievalMode.BOTH,
            knowledgeFileAccess = KnowledgeFileAccess.OFF)
        every { repo.observeById("a1") } returns flowOf(Agent("a1", "名称", useInheritedConfig = false,
            ragConfig = legacyRag, retrievalConfig = config))
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")

        vm.setRetrievalMode(AgentRetrievalMode.TOOL)
        runCurrent()
        coVerify(exactly = 1) { repo.updateFields(any(), any(), match {
            it.retrievalConfig?.retrievalMode == AgentRetrievalMode.TOOL &&
                it.retrievalConfig?.knowledgeFileAccess == KnowledgeFileAccess.OFF &&
                it.retrievalConfig?.memoryLimit == 11 && it.ragConfig == legacyRag && !it.useInheritedConfig
        }, any(), any()) }

        vm.setKnowledgeFileAccess(KnowledgeFileAccess.ALWAYS)
        runCurrent()
        coVerify(exactly = 1) { repo.updateFields(any(), any(), match {
            it.retrievalConfig?.retrievalMode == AgentRetrievalMode.TOOL &&
                it.retrievalConfig?.knowledgeFileAccess == KnowledgeFileAccess.ALWAYS
        }, any(), any()) }
        assertThat(vm.retrievalConfig.value.knowledgeFileAccess).isEqualTo(KnowledgeFileAccess.ALWAYS)
    }

    @Test
    fun `新会话默认的执行模式与 MCP 立即持久化并保留技能名单`() = runTest {
        every { repo.observeById("a1") } returns flowOf(Agent("a1", "名称", skills = listOf("skill-a"),
            mcpServerIds = listOf("m1")))
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")
        assertThat(vm.executionMode.value).isEqualTo(ExecutionMode.WORKSPACE)
        assertThat(vm.mcpServerIds.value).containsExactly("m1")

        vm.setExecutionMode(ExecutionMode.FULL_ACCESS)
        runCurrent()
        coVerify(exactly = 1) { repo.updateFields(any(), any(), match {
            it.executionMode == ExecutionMode.FULL_ACCESS && it.skills == listOf("skill-a") && it.mcpServerIds == listOf("m1")
        }, any(), any()) }

        vm.setMcpServerEnabled("m2", true)
        vm.setMcpServerEnabled("m2", true)
        runCurrent()
        assertThat(vm.mcpServerIds.value).containsExactly("m1", "m2").inOrder()
        vm.setMcpServerEnabled("m1", false)
        runCurrent()
        coVerify { repo.updateFields(any(), any(), match { it.mcpServerIds == listOf("m2") && it.executionMode == ExecutionMode.FULL_ACCESS }, any(), any()) }
    }

    @Test
    fun `子代理模型设置立即持久化到检索配置并保留记忆权限与旧检索参数`() = runTest {
        val policy = com.promenar.infinity.data.agent.AgentMemoryPolicy(shareWithOtherAgents = false)
        every { repo.observeById("a1") } returns flowOf(Agent("a1", "名称",
            retrievalConfig = AgentRetrievalConfig(memoryLimit = 11, memoryPolicy = policy)))
        val vm = AgentEditViewModel(repo)
        backgroundScope.launch { vm.hasChanges.collect {} }
        vm.loadAgent("a1")
        runCurrent()
        assertThat(vm.subagentModels.value).isEqualTo(AgentSubagentModelPolicy())

        vm.updateSubagentModels { it.copy(defaultModelId = "p::cheap") }
        runCurrent()
        coVerify(exactly = 1) { repo.updateFields(any(), any(), match {
            it.subagentModelPolicy == AgentSubagentModelPolicy(defaultModelId = "p::cheap") &&
                it.memoryPolicy == policy && it.retrievalConfig?.memoryLimit == 11
        }, any(), any()) }

        vm.updateSubagentModels { it.copy(allowPerTaskModel = true, selectableModelIds = listOf("p::a", "p::b", "p::a")) }
        runCurrent()
        assertThat(vm.subagentModels.value.selectableModelIds).containsExactly("p::a", "p::b").inOrder()
        coVerify { repo.updateFields(any(), any(), match {
            it.subagentModelPolicy == AgentSubagentModelPolicy("p::cheap", true, listOf("p::a", "p::b"))
        }, any(), any()) }
        // 明确提交后基线随之更新，不留下未保存提示。
        assertThat(vm.hasChanges.value).isFalse()

        vm.updateSubagentModels { it.copy(defaultModelId = null) }
        runCurrent()
        coVerify { repo.updateFields(any(), any(), match { it.subagentModelPolicy.defaultModelId == null && it.subagentModelPolicy.allowPerTaskModel }, any(), any()) }
    }

    @Test
    fun `未设置子代理模型的智能体保存其它字段时不写入检索配置`() = runTest {
        every { repo.observeById("a1") } returns flowOf(Agent("a1", "名称"))
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")
        vm.setExecutionMode(ExecutionMode.FULL_ACCESS)
        runCurrent()
        coVerify(exactly = 1) { repo.updateFields(any(), any(), match { it.retrievalConfig == null && it.subagentModelPolicy == AgentSubagentModelPolicy() }, any(), any()) }
    }

    @Test
    fun `新会话默认改动不计入未保存变更`() = runTest {
        every { repo.observeById("a1") } returns flowOf(Agent("a1", "名称"))
        val vm = AgentEditViewModel(repo)
        backgroundScope.launch { vm.hasChanges.collect {} }
        vm.loadAgent("a1")
        runCurrent()
        assertThat(vm.hasChanges.value).isFalse()

        vm.setExecutionMode(ExecutionMode.FULL_ACCESS)
        vm.setMcpServerEnabled("m1", true)
        runCurrent()
        assertThat(vm.hasChanges.value).isFalse()
    }

    @Test
    fun `删除确认的会话数按智能体统计并可刷新`() = runTest {
        every { repo.observeById("a1") } returns flowOf(Agent("a1", "名称"))
        var count = 3
        val vm = AgentEditViewModel(repo, countAgentSessions = { id -> if (id == "a1") count else 0 })
        assertThat(vm.sessionCount.value).isEqualTo(0)
        vm.loadAgent("a1")
        runCurrent()
        assertThat(vm.sessionCount.value).isEqualTo(3)

        count = 5
        vm.refreshSessionCount()
        runCurrent()
        assertThat(vm.sessionCount.value).isEqualTo(5)
    }

    @Test
    fun `会话数读取失败时保留上次结果`() = runTest {
        every { repo.observeById("a1") } returns flowOf(Agent("a1", "名称"))
        var fail = false
        val vm = AgentEditViewModel(repo, countAgentSessions = { if (fail) error("db") else 2 })
        vm.loadAgent("a1")
        runCurrent()
        fail = true
        vm.refreshSessionCount()
        runCurrent()
        assertThat(vm.sessionCount.value).isEqualTo(2)
    }

    @Test
    fun `同一编辑器加载旧智能体时不沿用其他智能体的检索与文件访问设置`() = runTest {
        every { repo.observeById("a1") } returns flowOf(Agent("a1", "自定义", retrievalConfig =
            AgentRetrievalConfig(retrievalMode = AgentRetrievalMode.TOOL, knowledgeFileAccess = KnowledgeFileAccess.ALWAYS)))
        every { repo.observeById("legacy") } returns flowOf(Agent("legacy", "旧智能体"))
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")

        vm.loadAgent("legacy")
        vm.setName("新名称")
        advanceTimeBy(1_001)
        runCurrent()

        assertThat(vm.retrievalConfig.value.retrievalMode).isEqualTo(AgentRetrievalMode.AUTO)
        assertThat(vm.retrievalConfig.value.knowledgeFileAccess).isEqualTo(KnowledgeFileAccess.ON_REQUEST)
        coVerify(exactly = 1) { repo.updateFields(any(), any(), match { it.id == "legacy" && it.retrievalConfig == null }, any(), any()) }
    }

    @Test
    fun `saveAgent delegates to repository update`() = runTest {
        val agent = Agent(
            id = "a1", name = "Original", modelId = "old",
            executionMode = ExecutionMode.WORKSPACE, skills = listOf("s1"),
            createdAt = 100L
        )
        every { repo.observeById("a1") } returns flowOf(agent)

        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")

        vm.setName("Updated")
        vm.saveAgent("a1")

        coVerify {
            repo.updateFields(any(), any(), match { saved ->
                saved.id == "a1" &&
                saved.name == "Updated" &&
                saved.modelId == "old" &&
                saved.executionMode == ExecutionMode.WORKSPACE &&
                saved.skills == listOf("s1") &&
                saved.createdAt == 100L
            }, any(), any())
        }
    }

    @Test
    fun `saveAgent updates initialAgent state`() = runTest {
        val agent = Agent(id = "a1", name = "Original", executionMode = ExecutionMode.WORKSPACE)
        every { repo.observeById("a1") } returns flowOf(agent)

        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")
        vm.setName("Updated")
        vm.saveAgent("a1")

        assertThat(vm.name.value).isEqualTo("Updated")
    }

    @Test
    fun `deleteAgent delegates to repository delete`() = runTest {
        every { repo.observeById(any()) } returns flowOf(null)
        var deleted = false

        val vm = AgentEditViewModel(repo)
        vm.deleteAgent("a1") { deleted = true }

        coVerify { repo.delete("a1") }
        assertThat(deleted).isTrue()
    }

    @Test
    fun `setName updates state`() = runTest {
        every { repo.observeById(any()) } returns flowOf(null)
        val vm = AgentEditViewModel(repo)
        vm.setName("New Name")
        assertThat(vm.name.value).isEqualTo("New Name")
    }

    @Test
    fun `setModel updates state`() = runTest {
        every { repo.observeById(any()) } returns flowOf(null)
        val vm = AgentEditViewModel(repo)
        vm.setModel("gpt-4o")
        assertThat(vm.selectedModel.value).isEqualTo("gpt-4o")
    }

    @Test
    fun `setIcon immediately persists preset and clears custom avatar`() = runTest {
        val agent = Agent(
            id = "a1",
            name = "Agent",
            icon = "✨",
            avatarPath = "/avatar/custom.png",
        )
        every { repo.observeById("a1") } returns flowOf(agent)
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")

        vm.setIcon("🧪")
        runCurrent()

        assertThat(vm.selectedIcon.value).isEqualTo("🧪")
        assertThat(vm.avatarPath.value).isNull()
        coVerify(exactly = 1) {
            repo.updateFields(any(), any(), match { it.id == "a1" && it.icon == "🧪" && it.avatarPath == null }, any(), any())
        }
    }

    @Test
    fun `hasChanges is false when no changes made`() = runTest {
        val ragConfig = AgentRagConfig()
        val retrievalConfig = AgentRetrievalConfig()
        val agent = Agent(
            id = "a1", name = "Name", executionMode = ExecutionMode.WORKSPACE,
            useInheritedConfig = false, ragConfig = ragConfig, retrievalConfig = retrievalConfig
        )
        every { repo.observeById("a1") } returns flowOf(agent)

        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")

        vm.hasChanges.test {
            assertThat(awaitItem()).isFalse()
        }
    }

    @Test
    fun `hasChanges is true after modifying name`() = runTest {
        val agent = Agent(id = "a1", name = "Name", executionMode = ExecutionMode.WORKSPACE)
        every { repo.observeById("a1") } returns flowOf(agent)

        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")
        vm.setName("Changed")

        vm.hasChanges.test {
            assertThat(awaitItem()).isTrue()
        }
    }

    @Test
    fun `saveAgent persists customized text flag with text in one repository update`() = runTest {
        val agent = Agent(id = "coder", name = "Coder", executionMode = ExecutionMode.WORKSPACE)
        every { repo.observeById("coder") } returns flowOf(agent)
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("coder")
        vm.setName("My Coder")
        vm.saveAgent("coder")

        coVerify { repo.updateFields(any(), any(), match { it.name == "My Coder" && it.nameCustomized }, any(), any()) }
    }

    @Test
    fun `saveAgent persists literal edits for non preset agents`() = runTest {
        val agent = Agent(id = "user-1", name = "Custom", executionMode = ExecutionMode.WORKSPACE)
        every { repo.observeById("user-1") } returns flowOf(agent)
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("user-1")
        vm.setName("Renamed")
        vm.saveAgent("user-1")

        coVerify { repo.updateFields(any(), any(), match { it.name == "Renamed" }, any(), any()) }
    }

    @Test
    fun `uncustomized preset loads current locale text and model-only save does not freeze it`() = runTest {
        val agent = Agent(
            id = "coder",
            name = "Coding Expert",
            description = "Stable fallback",
            modelId = "provider::old",
            executionMode = ExecutionMode.WORKSPACE,
        )
        every { repo.observeById("coder") } returns flowOf(agent)
        val vm = AgentEditViewModel(repo)

        vm.loadAgent("coder", "编程专家", "本地化描述")
        vm.setModel("provider::new")
        vm.saveAgent("coder")

        assertThat(vm.name.value).isEqualTo("编程专家")
        assertThat(vm.description.value).isEqualTo("本地化描述")
        coVerify {
            repo.updateFields(any(), any(), match {
                it.modelId == "provider::new" &&
                    it.name == "Coding Expert" &&
                    it.description == "Stable fallback" &&
                    !it.nameCustomized && !it.descriptionCustomized
            }, any(), any())
        }
    }

    @Test
    fun `localized preset edit baseline has no changes before user input`() = runTest {
        val agent = Agent(
            id = "coder",
            name = "Coding Expert",
            description = "Stable fallback",
            executionMode = ExecutionMode.WORKSPACE,
        )
        every { repo.observeById("coder") } returns flowOf(agent)
        val vm = AgentEditViewModel(repo)

        vm.loadAgent("coder", "编程专家", "本地化描述")

        vm.hasChanges.test { assertThat(awaitItem()).isFalse() }
    }

    @Test
    fun `changing only preset name freezes only name and preserves localized description`() = runTest {
        val agent = Agent(
            id = "coder",
            name = "Coding Expert",
            description = "Stable fallback",
            executionMode = ExecutionMode.WORKSPACE,
        )
        every { repo.observeById("coder") } returns flowOf(agent)
        val vm = AgentEditViewModel(repo)

        vm.loadAgent("coder", "编程专家", "本地化描述")
        vm.setName("我的编程助手")
        vm.saveAgent("coder")

        coVerify {
            repo.updateFields(any(), any(), match {
                it.name == "我的编程助手" && it.description == "Stable fallback" &&
                    it.nameCustomized && !it.descriptionCustomized
            }, any(), any())
        }
    }

    @Test
    fun `changing only preset description freezes only description and preserves localized name`() = runTest {
        val agent = Agent(
            id = "coder",
            name = "Coding Expert",
            description = "Stable fallback",
            executionMode = ExecutionMode.WORKSPACE,
        )
        every { repo.observeById("coder") } returns flowOf(agent)
        val vm = AgentEditViewModel(repo)

        vm.loadAgent("coder", "编程专家", "本地化描述")
        vm.setDescription("我的专属描述")
        vm.saveAgent("coder")

        coVerify {
            repo.updateFields(any(), any(), match {
                it.name == "Coding Expert" && it.description == "我的专属描述" &&
                    !it.nameCustomized && it.descriptionCustomized
            }, any(), any())
        }
    }

    @Test
    fun `room update failure leaves text and customization flags uncommitted`() = runTest {
        val agent = Agent(id = "coder", name = "Coding Expert", executionMode = ExecutionMode.WORKSPACE)
        every { repo.observeById("coder") } returns flowOf(agent)
        coEvery { repo.updateFields(any(), any(), any(), any(), any()) } throws IllegalStateException("room failed")
        val vm = AgentEditViewModel(repo)

        vm.loadAgent("coder", "编程专家", "本地化描述")
        vm.setName("用户文本")
        vm.saveAgent("coder")

        assertThat(vm.saveError.value).isEqualTo(AgentEditErrorCode.SAVE_FAILED)
        coVerify(exactly = 1) {
            repo.updateFields(any(), any(), match { it.name == "用户文本" && it.nameCustomized }, any(), any())
        }
        assertThat(agent.name).isEqualTo("Coding Expert")
        assertThat(agent.nameCustomized).isFalse()

        stubFieldWrites()
        vm.retryLastFailure()

        assertThat(vm.saveError.value).isNull()
        coVerify(exactly = 2) {
            repo.updateFields(any(), any(), match { it.name == "用户文本" && it.nameCustomized }, any(), any())
        }
    }

    @Test
    fun `saveSystemPrompt waits for repository before reporting success`() = runTest {
        val agent = Agent(id = "a1", name = "Agent", systemPrompt = "before")
        every { repo.observeById("a1") } returns flowOf(agent)
        val release = CompletableDeferred<Unit>()
        coEvery { repo.updateFields(any(), any(), any(), any(), any()) } coAnswers {
            release.await()
            applyFieldWrite(firstArg(), secondArg(), thirdArg())
        }
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")

        val saving = async { vm.saveSystemPrompt("after") }
        runCurrent()

        assertThat(saving.isCompleted).isFalse()
        release.complete(Unit)
        assertThat(saving.await().isSuccess).isTrue()
        assertThat(vm.systemPrompt.value).isEqualTo("after")
        coVerify(exactly = 1) { repo.updateFields(any(), any(), match { it.systemPrompt == "after" }, any(), any()) }
    }

    @Test
    fun `saveSystemPrompt repository failure keeps previous persisted state`() = runTest {
        val agent = Agent(id = "a1", name = "Agent", systemPrompt = "before")
        every { repo.observeById("a1") } returns flowOf(agent)
        coEvery { repo.updateFields(any(), any(), any(), any(), any()) } throws IllegalStateException("room failed")
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")

        val result = vm.saveSystemPrompt("after")

        assertThat(result.isFailure).isTrue()
        assertThat(vm.systemPrompt.value).isEqualTo("before")
    }

    @Test
    fun `saveSystemPrompt cancellation propagates`() = runTest {
        val agent = Agent(id = "a1", name = "Agent", systemPrompt = "before")
        every { repo.observeById("a1") } returns flowOf(agent)
        coEvery { repo.updateFields(any(), any(), any(), any(), any()) } throws CancellationException("cancelled")
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")

        var thrown: CancellationException? = null
        try {
            vm.saveSystemPrompt("after")
        } catch (cancelled: CancellationException) {
            thrown = cancelled
        }
        assertThat(thrown?.message).isEqualTo("cancelled")
        assertThat(vm.systemPrompt.value).isEqualTo("before")
    }

    @Test
    fun `编辑保存保留默认工作区与用户规则`() = runTest {
        val agent = Agent(id = "a1", name = "Agent", defaultWorkspaceId = "ws-1", userRules = "只用中文回答")
        every { repo.observeById("a1") } returns flowOf(agent)
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")
        assertThat(vm.defaultWorkspaceId.value).isEqualTo("ws-1")

        vm.setName("Renamed")
        advanceTimeBy(1_001)
        runCurrent()
        coVerify(exactly = 1) {
            repo.updateFields(any(), any(), match { it.name == "Renamed" && it.defaultWorkspaceId == "ws-1" && it.userRules == "只用中文回答" }, any(), any())
        }
        assertThat(vm.saveSystemPrompt("新提示词").isSuccess).isTrue()
        coVerify {
            repo.updateFields(any(), any(), match { it.systemPrompt == "新提示词" && it.defaultWorkspaceId == "ws-1" && it.userRules == "只用中文回答" }, any(), any())
        }
    }

    @Test
    fun `设置与清除默认工作区立即持久化并显示登记`() = runTest {
        val agent = Agent(id = "a1", name = "Agent", userRules = "规则")
        every { repo.observeById("a1") } returns flowOf(agent)
        val workspace = com.promenar.infinity.data.local.db.entity.WorkspaceEntity(
            "ws-2", com.promenar.infinity.data.local.db.entity.WorkspaceEntity.KIND_SHARED, "仓库", "/storage/emulated/0/repo", 1,
        )
        val vm = AgentEditViewModel(repo, workspaces = flowOf(listOf(workspace)))
        vm.loadAgent("a1")
        vm.defaultWorkspace.test {
            assertThat(awaitItem()).isNull()
            vm.setDefaultWorkspace("ws-2")
            runCurrent()
            assertThat(awaitItem()).isEqualTo(workspace)
            coVerify(exactly = 1) { repo.updateFields(any(), any(), match { it.defaultWorkspaceId == "ws-2" && it.userRules == "规则" }, any(), any()) }

            vm.setDefaultWorkspace(null)
            runCurrent()
            assertThat(awaitItem()).isNull()
            coVerify(exactly = 1) { repo.updateFields(any(), any(), match { it.defaultWorkspaceId == null && it.userRules == "规则" }, any(), any()) }
        }
    }

    @Test
    fun `智能体选用方案、改项与保存方案都写入默认参数`() = runTest {
        every { repo.observeById("a1") } returns flowOf(Agent(id = "a1", name = "Agent", modelId = "p::m", temperature = 0.5))
        val dao = object : com.promenar.infinity.data.local.db.dao.ParameterSchemeDao {
            val rows = kotlinx.coroutines.flow.MutableStateFlow<List<com.promenar.infinity.data.local.db.entity.ParameterSchemeEntity>>(emptyList())
            override fun observeForModel(modelKey: String) = rows
            override suspend fun getById(id: String) = rows.value.firstOrNull { it.id == id }
            override suspend fun upsert(scheme: com.promenar.infinity.data.local.db.entity.ParameterSchemeEntity) {
                rows.value = rows.value.filterNot { it.id == scheme.id } + scheme
            }
            override suspend fun delete(id: String) { rows.value = rows.value.filterNot { it.id == id } }
        }
        val schemes = com.promenar.infinity.data.repository.ParameterSchemeRepository(dao, newId = { "s1" })
        val vm = AgentEditViewModel(repo, parameterSchemeRepository = schemes)
        vm.loadAgent("a1")

        vm.applyModelDefaultSampling()
        assertThat(vm.sampling.value.isModelDefault).isTrue()
        assertThat(vm.baseSchemeId.value).isEqualTo(com.promenar.infinity.data.model.params.MODEL_DEFAULT_SCHEME_ID)

        vm.setSamplingParam(com.promenar.infinity.data.model.params.SamplingParam.TOP_K, 20.0)
        vm.saveParameterScheme("写代码", vm.sampling.value)
        runCurrent()
        assertThat(vm.baseSchemeId.value).isEqualTo("s1")
        assertThat(dao.rows.value.single().modelKey).isEqualTo("p::m")

        advanceTimeBy(2_000)
        runCurrent()
        coVerify { repo.updateFields(any(), any(), match { it.topK == 20 && it.temperature == null }, any(), any()) }
    }

    @Test
    fun `智能体修改本地没在编辑的字段时静默刷新，之后只写本地改过的字段`() = runTest {
        val remote = kotlinx.coroutines.flow.MutableStateFlow<Agent?>(Agent("a1", "写作助手", description = "旧描述"))
        every { repo.observeById("a1") } returns remote
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")

        remote.value = remote.value!!.copy(description = "智能体写的描述")
        runCurrent()

        assertThat(vm.description.value).isEqualTo("智能体写的描述")
        assertThat(vm.refreshedFields.value).containsExactly(AgentField.DESCRIPTION)
        assertThat(vm.conflict.value).isNull()
        vm.hasChanges.test { assertThat(awaitItem()).isFalse() }

        vm.setName("新名字")
        advanceTimeBy(1_001)
        runCurrent()
        coVerify(exactly = 1) { repo.updateFields("a1", setOf(AgentField.NAME), any(), any(), any()) }
        coVerify(exactly = 0) { repo.updateFields(any(), match { AgentField.DESCRIPTION in it }, any(), any(), any()) }
    }

    @Test
    fun `两边都改同一字段时不覆盖，保留我的才写入`() = runTest {
        val remote = kotlinx.coroutines.flow.MutableStateFlow<Agent?>(Agent("a1", "写作助手", description = "旧描述"))
        every { repo.observeById("a1") } returns remote
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")

        vm.setDescription("我的描述")
        remote.value = remote.value!!.copy(description = "智能体写的描述")
        runCurrent()
        assertThat(vm.conflict.value?.fields).containsExactly(AgentField.DESCRIPTION)

        advanceTimeBy(1_001)
        runCurrent()
        coVerify(exactly = 0) { repo.updateFields(any(), match { AgentField.DESCRIPTION in it }, any(), any(), any()) }
        assertThat(vm.description.value).isEqualTo("我的描述")

        vm.keepMineConflict()
        runCurrent()
        coVerify(exactly = 1) {
            repo.updateFields("a1", setOf(AgentField.DESCRIPTION), match { it.description == "我的描述" }, any(), any())
        }
        assertThat(vm.conflict.value).isNull()
        assertThat(vm.notice.value).isEqualTo(AgentEditNotice.KEPT_MINE)
    }

    @Test
    fun `冲突时采用新内容替换本地值且不写入`() = runTest {
        val remote = kotlinx.coroutines.flow.MutableStateFlow<Agent?>(Agent("a1", "写作助手", description = "旧描述"))
        every { repo.observeById("a1") } returns remote
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")

        vm.setDescription("我的描述")
        remote.value = remote.value!!.copy(description = "智能体写的描述")
        runCurrent()
        vm.adoptRemoteConflict()
        advanceTimeBy(1_001)
        runCurrent()

        assertThat(vm.description.value).isEqualTo("智能体写的描述")
        assertThat(vm.conflict.value).isNull()
        coVerify(exactly = 0) { repo.updateFields(any(), any(), any(), any(), any()) }
        vm.hasChanges.test { assertThat(awaitItem()).isFalse() }
    }

    @Test
    fun `编辑提示词期间智能体改了提示词，保存改为提示冲突，保留我的会写入并补记对方版本`() = runTest {
        val remote = kotlinx.coroutines.flow.MutableStateFlow<Agent?>(Agent("a1", "写作助手", systemPrompt = "P0"))
        every { repo.observeById("a1") } returns remote
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")
        assertThat(vm.beginPromptEdit()).isEqualTo("P0")

        remote.value = remote.value!!.copy(systemPrompt = "P-agent")
        runCurrent()
        assertThat(vm.promptConflict.value).isEqualTo(AgentPromptConflict("P-agent"))

        val blocked = vm.saveSystemPrompt("P-mine")
        assertThat(blocked.exceptionOrNull()).isInstanceOf(AgentPromptSaveConflictException::class.java)
        coVerify(exactly = 0) { repo.updateFields(any(), setOf(AgentField.SYSTEM_PROMPT), any(), any(), any()) }

        assertThat(vm.keepMyPrompt("P-mine").isSuccess).isTrue()
        coVerify { repo.recordPromptRevision("a1", "P-agent", com.promenar.infinity.domain.model.AgentPromptRevision.Source.AGENT) }
        coVerify(exactly = 1) {
            repo.updateFields("a1", setOf(AgentField.SYSTEM_PROMPT), match { it.systemPrompt == "P-mine" }, any(), any())
        }
        assertThat(vm.systemPrompt.value).isEqualTo("P-mine")
        assertThat(vm.promptConflict.value).isNull()
        assertThat(vm.notice.value).isEqualTo(AgentEditNotice.KEPT_MY_PROMPT)
    }

    @Test
    fun `编辑提示词冲突时采用新内容，草稿存为历史版本，之后按新内容继续编辑`() = runTest {
        val remote = kotlinx.coroutines.flow.MutableStateFlow<Agent?>(Agent("a1", "写作助手", systemPrompt = "P0"))
        every { repo.observeById("a1") } returns remote
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")
        vm.beginPromptEdit()
        remote.value = remote.value!!.copy(systemPrompt = "P-agent")
        runCurrent()

        assertThat(vm.adoptRemotePrompt("P-draft")).isEqualTo("P-agent")
        coVerify { repo.recordPromptRevision("a1", "P-draft", com.promenar.infinity.domain.model.AgentPromptRevision.Source.USER) }
        assertThat(vm.promptConflict.value).isNull()
        assertThat(vm.notice.value).isEqualTo(AgentEditNotice.ADOPTED_REMOTE_PROMPT)

        assertThat(vm.saveSystemPrompt("P-agent 加一句").isSuccess).isTrue()
        assertThat(vm.systemPrompt.value).isEqualTo("P-agent 加一句")
    }

    @Test
    fun `恢复历史版本后界面显示恢复的内容`() = runTest {
        val agent = Agent("a1", "写作助手", systemPrompt = "当前")
        every { repo.observeById("a1") } returns flowOf(agent)
        coEvery { repo.restorePromptRevision("a1", "r1") } returns AgentFieldUpdate.Updated(agent.copy(systemPrompt = "旧版"))
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")

        vm.restorePromptRevision("r1")
        runCurrent()

        assertThat(vm.systemPrompt.value).isEqualTo("旧版")
        assertThat(vm.notice.value).isEqualTo(AgentEditNotice.RESTORED)
        vm.hasChanges.test { assertThat(awaitItem()).isFalse() }
    }
}
