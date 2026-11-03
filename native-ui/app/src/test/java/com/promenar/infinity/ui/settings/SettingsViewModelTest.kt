package com.promenar.infinity.ui.settings

import android.app.Application
import android.content.SharedPreferences
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import com.promenar.infinity.data.repository.ISkillRepository
import com.promenar.infinity.data.local.db.entity.McpServerEntity
import com.promenar.infinity.data.remote.mcp.McpConnectionSettings
import com.promenar.infinity.ui.chat.manager.registry.McpSkillRegistry
import com.promenar.infinity.ui.chat.manager.registry.McpSyncResult
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.ThemePreferenceStore
import com.promenar.infinity.domain.repository.ITokenStatsRepository
import com.promenar.infinity.domain.repository.IVectorRepository
import com.promenar.infinity.domain.repository.TokenUsageAggregate
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var vectorRepo: IVectorRepository
    private lateinit var tokenStatsRepo: ITokenStatsRepository
    private lateinit var mockApp: InfinityApplication
    private lateinit var prefs: SharedPreferences
    private lateinit var skillRepo: ISkillRepository
    private lateinit var themePreferenceState: MutableStateFlow<InfinityThemePreferences>
    private lateinit var themePreferenceStore: ThemePreferenceStore

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        vectorRepo = mockk()
        tokenStatsRepo = mockk()
        val mockEditor = mockk<SharedPreferences.Editor>(relaxed = true) {
            every { putBoolean(any(), any()) } returns this@mockk
            every { putStringSet(any(), any()) } returns this@mockk
            every { putInt(any(), any()) } returns this@mockk
            every { putString(any(), any()) } returns this@mockk
            every { remove(any()) } returns this@mockk
        }
        prefs = mockk {
            every { getString(any(), any()) } answers { secondArg() }
            every { getStringSet(any(), any()) } answers { secondArg() }
            every { getInt(any(), any()) } answers { secondArg() }
            every { getBoolean(any(), any()) } answers { secondArg() }
            every { edit() } returns mockEditor
        }
        mockApp = mockk(relaxed = true)
        skillRepo = mockk()
        themePreferenceState = MutableStateFlow(InfinityThemePreferences())
        themePreferenceStore = mockk(relaxed = true) {
            every { state } returns themePreferenceState
        }

        every { mockApp.getSharedPreferences("infinity_settings", 0) } returns prefs
        every { mockApp.themePreferenceStore } returns themePreferenceStore
        every { mockApp.skillRepository } returns skillRepo
        every { skillRepo.getAllCustomSkills() } returns emptyFlow()
        every { skillRepo.getAllMcpServers() } returns emptyFlow()
        coEvery { skillRepo.getMcpToolSnapshots(any()) } returns emptyList()
        coEvery { tokenStatsRepo.getTotalUsage() } returns com.promenar.infinity.domain.repository.TokenUsageAggregate()
        coEvery { tokenStatsRepo.getUsageByModel() } returns emptyList()

        val initApp = mockk<Application>(relaxed = true)
        every { initApp.applicationContext } returns initApp
        ProviderManager.init(initApp, MemorySecretStore())
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `模型超时设置契约同步状态并夹取范围`() = runTest {
        val vm = SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo)
        vm.setModelTimeout(com.promenar.infinity.data.remote.ModelTimeoutKind.LLM, 901)
        assertThat(vm.modelTimeouts.value.llmSeconds).isEqualTo(901)
        vm.setModelTimeout(com.promenar.infinity.data.remote.ModelTimeoutKind.IMAGE, 0)
        assertThat(vm.modelTimeouts.value.imageSeconds).isEqualTo(5)
        vm.setModelTimeout(com.promenar.infinity.data.remote.ModelTimeoutKind.RERANK, 9000)
        assertThat(vm.modelTimeouts.value.rerankSeconds).isEqualTo(1800)
    }

    @Test
    fun `settings asynchronous failures expose only account scoped typed retry state`() {
        val getters = SettingsViewModel::class.java.methods.map { it.name }

        assertThat(getters).contains("getSettingsError")
        assertThat(getters).contains("retryLastError")
        assertThat(SettingsAsyncErrorCode.entries).containsExactly(
            SettingsAsyncErrorCode.AVATAR_IMPORT_FAILED,
        )
    }

    @Test
    fun `settings home does not load token usage or surface token repository failures`() = runTest {
        coEvery { tokenStatsRepo.getTotalUsage() } throws IllegalStateException("runtime detail")
        val vm = SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo)
        advanceUntilIdle()

        assertThat(vm.settingsError.value).isNull()
        coVerify(exactly = 0) { tokenStatsRepo.getTotalUsage() }
        coVerify(exactly = 0) { tokenStatsRepo.getUsageByModel() }
        coVerify(exactly = 0) { tokenStatsRepo.resetStats() }
    }

    @Test
    fun `loadKnowledgeStats sets zero by default`() = runTest {
        coEvery { tokenStatsRepo.getTotalUsage() } returns TokenUsageAggregate()
        coEvery { tokenStatsRepo.getUsageByModel() } returns emptyList()

        val vm = SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo)

        assertThat(vm.activeSourcesCount.value).isEqualTo(0)
    }

    @Test
    fun `skills list excludes deprecated current_time`() = runTest {
        val vm = SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo)
        val skills = vm.skills.value
        assertThat(skills.find { it.id == "current_time" }).isNull()
    }

    @Test fun `画布四个工具出现在可开关的内置工具列表`() = runTest {
        val vm = SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo)
        assertThat(vm.skills.value.map { it.id }).containsAtLeast("canvas_create", "canvas_update", "canvas_read", "canvas_list", "artifact_publish", "artifact_list")
    }

    @Test
    fun `skills list includes image_generation`() = runTest {
        val vm = SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo)
        val skills = vm.skills.value
        val imgSkill = skills.find { it.id == "image_generation" }
        assertThat(imgSkill).isNotNull()
        assertThat(imgSkill!!.id).isEqualTo("image_generation")
    }

    @Test
    fun `skills list includes file tools`() = runTest {
        val vm = SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo)
        val skills = vm.skills.value
        val toolIds = skills.map { it.id }
        assertThat(toolIds).containsAtLeast("file_read", "file_list", "file_search", "exec_js", "python_sandbox", "web_search")
        // Tavily 与 SearXNG 已并入 web_search，由搜索设置选择引擎，不再单独列出。
        assertThat(toolIds).containsNoneOf("search_tavily", "search_searxng")
    }

    @Test
    fun `mcp server sync uses atomic registry contract`() = runTest {
        val testServer = McpServerEntity(
            id = "srv1", name = "TestServer", url = "https://mcp.example.test"
        )
        every { skillRepo.getAllMcpServers() } returns kotlinx.coroutines.flow.flowOf(listOf(testServer))
        coEvery { tokenStatsRepo.getTotalUsage() } returns TokenUsageAggregate()
        coEvery { tokenStatsRepo.getUsageByModel() } returns emptyList()

        val mockMcpRegistry = mockk<McpSkillRegistry>(relaxed = true)
        coEvery { mockMcpRegistry.syncServer("srv1") } returns McpSyncResult.Success(0)

        val vm = SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo, mockMcpRegistry)
        advanceUntilIdle()

        vm.syncMcpServer("srv1")
        advanceUntilIdle()

        coVerify { mockMcpRegistry.syncServer("srv1") }
    }

    @Test
    fun `MCP服务器接受HTTP传输并拒绝stdio与无效URL`() = runTest {
        coEvery { skillRepo.insertMcpServer(any()) } returns Unit
        val vm = SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo)
        advanceUntilIdle()

        vm.addMcpServer("invalid", "ftp://invalid.example.test", "http")
        vm.addMcpServer("legacy-stdio", "/usr/bin/server", "stdio")
        advanceUntilIdle()
        coVerify(exactly = 0) { skillRepo.insertMcpServer(any()) }

        vm.addMcpServer("modern", "https://mcp.example.test", "http")
        advanceUntilIdle()
        coVerify(exactly = 1) {
            skillRepo.insertMcpServer(match { it.url == "https://mcp.example.test" && it.type == "http" })
        }
    }

    @Test fun `MCP添加入口接受HTTP且Bearer可选`() = runTest {
        val settings = McpConnectionSettings(prefs, MemorySecretStore())
        every { mockApp.mcpConnectionSettings } returns settings
        coEvery { skillRepo.insertMcpServer(any()) } returns Unit
        val vm = SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo)
        for (host in listOf("127.0.0.1", "localhost", "192.168.1.2", "remote.example.test")) {
            val completed = CompletableDeferred<Boolean>()
            vm.addMcpServer("服务", "http://$host:8765/mcp", "http") { completed.complete(it) }
            assertThat(completed.await()).isTrue()
        }
        vm.addMcpServer("无效令牌", "http://localhost:8765/mcp", "http", "invalid token")
        assertThat(vm.mcpConfigError.value).isEqualTo(McpConfigError.BEARER_REQUIRED)
        coVerify(exactly = 4) { skillRepo.insertMcpServer(any()) }
    }

    @Test
    fun `本机MCP先保存SecretStore凭据再添加服务器配置`() = runTest {
        val settings = McpConnectionSettings(prefs, MemorySecretStore())
        every { mockApp.mcpConnectionSettings } returns settings
        val savedServer = io.mockk.slot<McpServerEntity>()
        coEvery { skillRepo.insertMcpServer(capture(savedServer)) } coAnswers {
            assertThat(settings.readBearerToken(firstArg<McpServerEntity>().id)).isEqualTo("test-only-bearer")
            Unit
        }
        val vm = SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo)
        val completed = CompletableDeferred<Boolean>()
        vm.addMcpServer("本机", "http://127.0.0.1:8765/mcp", "http", "test-only-bearer") {
            completed.complete(it)
        }

        assertThat(completed.await()).isTrue()
        assertThat(savedServer.captured.url).isEqualTo("http://127.0.0.1:8765/mcp")
        assertThat(vm.mcpConfigError.value).isNull()
        assertThat(settings.readBearerToken(savedServer.captured.id)).isEqualTo("test-only-bearer")
    }

    @Test
    fun `本机MCP数据库保存失败会清理新建的凭据`() = runTest {
        val settings = McpConnectionSettings(prefs, MemorySecretStore())
        every { mockApp.mcpConnectionSettings } returns settings
        val attemptedServer = io.mockk.slot<McpServerEntity>()
        coEvery { skillRepo.insertMcpServer(capture(attemptedServer)) } throws IllegalStateException("database unavailable")
        val vm = SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo)
        val completed = CompletableDeferred<Boolean>()
        vm.addMcpServer("本机", "http://127.0.0.1:8765/mcp", "http", "test-only-bearer") {
            completed.complete(it)
        }

        assertThat(completed.await()).isFalse()
        assertThat(settings.readBearerToken(attemptedServer.captured.id)).isNull()
        assertThat(vm.mcpConfigError.value).isEqualTo(McpConfigError.SAVE_FAILED)
    }

    @Test
    fun `default loopLimit is 50`() = runTest {
        val vm = SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo)
        assertThat(vm.loopLimit.value).isEqualTo(50)
    }

    @Test
    fun `工具设置不读取已移除的上下文偏好`() = runTest {
        SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo)

        verify(exactly = 0) { prefs.getInt("tool_context_ratio_percent", any()) }
        verify(exactly = 0) { prefs.getInt("tool_context_keep_rounds", any()) }
    }

    @Test
    fun `不限哨兵和长任务设置使用既有整型偏好并限制非法值`() = runTest {
        val vm = SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo)
        val editor = prefs.edit()
        vm.updateLoopLimit(0)
        assertThat(vm.loopLimit.value).isEqualTo(0)
        verify { editor.putInt("loop_limit", 0) }
        vm.updateTaskTokenLimit(20_000)
        vm.updateToolLoopRepeatLimit(1)
        assertThat(vm.taskTokenLimit.value).isEqualTo(20_000)
        assertThat(vm.toolLoopRepeatLimit.value).isEqualTo(2)
    }



    @Test
    fun `preset_skills_migrated_v3 updates SharedPreferences and enables all preset skills`() = runTest {
        val mockEditor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { prefs.getBoolean("preset_skills_migrated_v3", false) } returns false
        every { prefs.edit() } returns mockEditor
        every { mockEditor.putStringSet("enabled_skills", any()) } returns mockEditor
        every { mockEditor.putBoolean("preset_skills_migrated_v3", true) } returns mockEditor

        SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo)

        verify {
            mockEditor.putStringSet("enabled_skills", any())
            mockEditor.putBoolean("preset_skills_migrated_v3", true)
            mockEditor.apply()
        }
    }

    @Test
    fun `settings view model 必须暴露 typed themePreferences StateFlow 且不保留旧属性和 direct theme writer`() {
        val vmSource = java.nio.file.Files.readAllBytes(
            java.nio.file.Path.of("app/src/main/java/com/promenar/infinity/ui/settings/SettingsViewModel.kt"),
        ).toString(Charsets.UTF_8)

        assertThat(vmSource).contains("val themePreferences:")
        assertThat(vmSource).contains("StateFlow<InfinityThemePreferences>")
        assertThat(vmSource).doesNotContain("val themeMode:")
        assertThat(vmSource).doesNotContain("val selectedSettingsTab:")
        assertThat(vmSource).doesNotContain("fun setThemeMode(")
        assertThat(vmSource).doesNotContain("fun setThemeColorSource(")
        assertThat(vmSource).doesNotContain("fun setSelectedSettingsTab(")
    }

    private class MemorySecretStore : SecretStore {
        private val values = mutableMapOf<SecretId, ByteArray>()
        override fun put(id: SecretId, value: ByteArray) { values[id] = value.copyOf() }
        override fun get(id: SecretId): ByteArray? = values[id]?.copyOf()
        override fun contains(id: SecretId): Boolean = id in values
        override fun remove(id: SecretId) { values.remove(id) }
    }
}
