package com.promenar.infinity.data.generation

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.ProtocolToolFunction
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test

class DefaultSessionToolResolverTest {
    @Test fun `配置工具只在主会话广告且子代理工具集排除`() {
        val tools = listOf("agents_read", "agents_write").map { name -> safeReadTool.copy(
            function = ProtocolToolFunction(name, "配置", "{}"), runtimeToolId = name,
            risk = if (name == "agents_write") ToolRisk.AGENT_CONFIG else ToolRisk.SAFE_READ,
        ) }
        every { skillRegistry.getAllTools(null) } returns tools
        every { settings.getStringSet(BuiltinToolPreferences.ENABLED_KEY, null) } returns null
        val resolver = DefaultSessionToolResolver(settings, skillRegistry)
        val main = Session("s", "a")
        assertThat(resolver.resolve(main).map { it.function.name }).containsExactly("agents_read", "agents_write")
        for (session in listOf(main.copy(parentSessionId = "parent"), main.copy(branchPlanJson = "{}"),
            main.copy(parentSessionDeleted = true), main.copy(id = "sa_child"))) assertThat(resolver.resolve(session)).isEmpty()
        for (profile in com.promenar.infinity.data.subagent.SubagentToolProfile.entries)
            assertThat(com.promenar.infinity.data.subagent.SubagentLimits.toolset(tools, profile)).isEmpty()
    }

    @Test
    fun `没有媒体能力的模型不广告查看工具`() {
        val view = safeReadTool.copy(
            function = ProtocolToolFunction("view_media", "查看媒体", "{}"), runtimeToolId = "view_media",
        )
        every { skillRegistry.getAllTools(null) } returns listOf(view)
        every { settings.getStringSet(BuiltinToolPreferences.ENABLED_KEY, null) } returns null
        assertThat(DefaultSessionToolResolver(settings, skillRegistry).resolve(Session("s", "a"))).isEmpty()
    }

    @Test
    fun `Termux工具默认隐藏且关闭集成立即撤销暴露`() {
        val termux = ProtocolTool(
            function = ProtocolToolFunction(name = "termux_exec", description = "本机终端", parameters = "{}"),
            sourceId = "builtin", runtimeToolId = "termux_exec", risk = ToolRisk.PROCESS,
        )
        every { skillRegistry.getAllTools(null) } returns listOf(safeReadTool, termux)
        every { settings.getStringSet("enabled_skills", null) } returns null
        val resolver = DefaultSessionToolResolver(settings, skillRegistry)
        val session = Session(id = "termux", agentId = "a")
        assertThat(resolver.resolve(session).map { it.runtimeToolId }).containsExactly("calculator")
        every { settings.getBoolean("termux_integration_enabled", false) } returns true
        assertThat(resolver.resolve(session).map { it.runtimeToolId }).containsExactly("calculator", "termux_exec")
        every { settings.getBoolean("termux_integration_enabled", false) } returns false
        assertThat(resolver.resolve(session).map { it.runtimeToolId }).containsExactly("calculator")
    }


    private val settings = mockk<SharedPreferences>(relaxed = true).also {
        every { it.getStringSet(BuiltinToolPreferences.KNOWN_KEY, null) } returns null
    }
    private val skillRegistry = mockk<SkillRegistry>(relaxed = true)

    private val safeReadTool = ProtocolTool(
        function = ProtocolToolFunction(name = "calculator", description = "Calc", parameters = "{}"),
        sourceId = "builtin",
        runtimeToolId = "calculator",
        risk = ToolRisk.SAFE_READ,
    )

    private val imageGenTool = ProtocolTool(
        function = ProtocolToolFunction(name = "generate_image", description = "Image Gen", parameters = "{}"),
        sourceId = "builtin",
        runtimeToolId = "image_generation",
        risk = ToolRisk.EXTERNAL_WRITE,
    )

    private val fileWriteTool = ProtocolTool(
        function = ProtocolToolFunction(name = "write_file", description = "Write file", parameters = "{}"),
        sourceId = "builtin",
        runtimeToolId = "write_file",
        risk = ToolRisk.FILE_WRITE,
    )

    @Test
    fun `关闭工具只保留全局启用的内置搜索并支持撤销`() {
        val web = safeReadTool.copy(function = ProtocolToolFunction("web_search", "搜索", "{}"), runtimeToolId = "web_search")
        val spoof = web.copy(sourceId = "mcp", mcpServerId = "server")
        every { skillRegistry.getAllTools(null) } returns listOf(web, spoof, safeReadTool, fileWriteTool)
        every { settings.getStringSet(BuiltinToolPreferences.ENABLED_KEY, null) } returns setOf("web_search", "calculator")
        every { settings.getStringSet(BuiltinToolPreferences.KNOWN_KEY, null) } returns setOf("web_search", "calculator")
        val resolver = DefaultSessionToolResolver(settings, skillRegistry)
        val off = Session("s", "a", activeMcpServerIds = listOf("server"),
            options = com.promenar.infinity.data.model.SessionOptions(toolsEnabled = false))
        assertThat(resolver.resolve(off)).containsExactly(web)
        assertThat(resolver.resolve(off.copy(options = off.options.copy(keepWebSearchWhenToolsOff = false)))).isEmpty()
        every { settings.getStringSet(BuiltinToolPreferences.ENABLED_KEY, null) } returns setOf("calculator")
        assertThat(resolver.resolve(off)).isEmpty()
    }

    @Before
    fun setUp() {
        every { skillRegistry.getAllTools(null) } returns listOf(
            safeReadTool,
            imageGenTool,
            fileWriteTool,
        )
    }

    @Test
    fun `empty session selection still exposes every globally enabled builtin tool`() {
        every { settings.getStringSet("enabled_skills", null) } returns setOf(
            "calculator",
            "image_generation",
            "file_write",
        )

        val resolver = DefaultSessionToolResolver(settings, skillRegistry)
        val session = Session(id = "s1", agentId = "agent", activeSkillIds = emptyList())

        val resolvedIds = resolver.resolve(session).map { it.runtimeToolId }

        assertThat(resolvedIds).containsExactly("calculator", "image_generation", "write_file")
    }

    @Test
    fun `custom skill selection never changes builtin tool exposure`() {
        every { settings.getStringSet("enabled_skills", null) } returns setOf("calculator")
        val customTool = ProtocolTool(
            function = ProtocolToolFunction(name = "my_tool", description = "Custom", parameters = "{}"),
            sourceId = "custom",
            runtimeToolId = "user_1",
            risk = ToolRisk.UNKNOWN,
        )
        every { skillRegistry.getAllTools(null) } returns listOf(safeReadTool, fileWriteTool, customTool)

        val resolver = DefaultSessionToolResolver(settings, skillRegistry)
        val empty = resolver.resolve(Session(id = "s1", agentId = "agent"))
        val selected = resolver.resolve(
            Session(id = "s2", agentId = "agent", activeSkillIds = listOf("user_1")),
        )

        assertThat(empty.map { it.runtimeToolId }).containsExactly("calculator")
        assertThat(selected.map { it.runtimeToolId }).containsExactly("calculator")
    }

    @Test
    fun `tools disabled for session exposes nothing`() {
        val resolver = DefaultSessionToolResolver(settings, skillRegistry)
        val session = Session(id = "s1", agentId = "agent")
        val disabled = session.copy(options = session.options.copy(toolsEnabled = false))

        assertThat(resolver.resolve(disabled)).isEmpty()
    }

    @Test
    fun `resolve filters out tools not enabled in settings`() {
        every { settings.getStringSet("enabled_skills", null) } returns setOf("calculator")

        val resolver = DefaultSessionToolResolver(settings, skillRegistry)
        val session = Session(id = "s1", agentId = "agent", activeSkillIds = emptyList())

        val resolved = resolver.resolve(session)
        assertThat(resolved.map { it.runtimeToolId }).containsExactly("calculator")
    }


    @Test
    fun `builtin tools unknown to a legacy enabled set default to enabled`() {
        val createFile = ProtocolTool(
            function = ProtocolToolFunction(name = "create_file", description = "Create", parameters = "{}"),
            sourceId = "builtin",
            runtimeToolId = "create_file",
            risk = ToolRisk.FILE_WRITE,
        )
        every { skillRegistry.getAllTools(null) } returns listOf(safeReadTool, fileWriteTool, createFile)
        every { settings.getStringSet("enabled_skills", null) } returns setOf("calculator")

        val resolved = DefaultSessionToolResolver(settings, skillRegistry).resolve(Session(id = "s", agentId = "a"))

        // file_write 在旧版设置中出现过且被关闭；create_file 是新工具，默认启用。
        assertThat(resolved.map { it.runtimeToolId }).containsExactly("calculator", "create_file")
    }

    @Test
    fun `tools listed as known but not enabled stay disabled`() {
        val createFile = ProtocolTool(
            function = ProtocolToolFunction(name = "create_file", description = "Create", parameters = "{}"),
            sourceId = "builtin",
            runtimeToolId = "create_file",
            risk = ToolRisk.FILE_WRITE,
        )
        every { skillRegistry.getAllTools(null) } returns listOf(safeReadTool, createFile)
        every { settings.getStringSet("enabled_skills", null) } returns setOf("calculator")
        every { settings.getStringSet(BuiltinToolPreferences.KNOWN_KEY, null) } returns setOf("calculator", "create_file")

        val resolved = DefaultSessionToolResolver(settings, skillRegistry).resolve(Session(id = "s", agentId = "a"))

        assertThat(resolved.map { it.runtimeToolId }).containsExactly("calculator")
    }

}
