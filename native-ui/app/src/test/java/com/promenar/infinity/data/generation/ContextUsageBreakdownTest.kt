package com.promenar.infinity.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.remote.protocol.*
import com.promenar.infinity.ui.chat.manager.ContextBuilder
import com.promenar.infinity.ui.chat.manager.ContextBuilderParams
import com.promenar.infinity.ui.chat.manager.ContextRetrieval
import com.promenar.infinity.ui.chat.manager.skills.RequestedSkill
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ContextUsageBreakdownTest {
    @Test fun `系统真实段落与每个MCP服务器分别统计且ASCII余数不增加总量`() {
        val sections = listOf(ContextPromptSection("assistant_prompt", "a"), ContextPromptSection("session_instructions", "b"),
            ContextPromptSection("history_summary", "c\n"))
        val prompt = PromptRequest(model = "demo", messages = listOf(ProtocolMessage("system", sections.joinToString("") { it.content })),
            tools = listOf(tool("builtin"), tool("mcp", "alpha"), tool("mcp", "beta"), tool("builtin", runtimeId = "mcp:legacy:read")))
        val result = ContextUsageBreakdown.estimate(prompt, sections, 2, 10000)
        assertConsistent(result, prompt)
        assertThat(result.categories[0].children.map { it.id }).containsExactly("assistant_prompt", "session_instructions", "project_rules",
            "runtime", "permissions", "tool_guidance", "skill_catalog", "knowledge_catalog", "history_summary").inOrder()
        assertThat(result.categories[1].children.map { it.id }).containsExactly("builtin", "mcp:alpha", "mcp:beta", "mcp:legacy").inOrder()
        assertThat(result.foldedToolResults).isEqualTo(2)
    }

    @Test fun `用户助手思考工具媒体及上下文分别统计且知识只计一次`() {
        val context = "<turn_context>\n<snapshot_metadata>{}</snapshot_metadata>\n## Reference Material\n说明\n<reference_material>\n" +
            "### Long-term memory\n记忆正文\n### Retrieved Context\n检索正文\n### Knowledge Graph Relations\n知识关系\n" +
            "### Web Search Results\n网页资料\n</reference_material>\n## Active Task Plan\n任务\n</turn_context>"
        val prompt = PromptRequest(model = "demo", messages = listOf(
            ProtocolMessage("user", "用户正文", turnContext = context, imageUrls = listOf(ImageInput(url = "workspace/image.png"))),
            ProtocolMessage("assistant", "助手正文", reasoning = "思考", toolCalls = listOf(ProtocolToolCall("call", "read", "{}"))),
            ProtocolMessage("tool", "工具结果"),
        ))
        val result = ContextUsageBreakdown.estimate(prompt, emptyList(), 0, 10000)
        assertConsistent(result, prompt)
        val conversation = result.categories.last()
        listOf("user_body", "assistant_body", "reasoning", "tool_calls", "tool_results", "media").forEach { id ->
            assertThat(conversation.children.single { it.id == id }.tokens).isGreaterThan(0)
        }
        listOf("memory", "retrieval", "knowledge_graph", "web_search", "task_plan").forEach { id ->
            assertThat(conversation.children.single { it.id == id }.tokens).isGreaterThan(0)
        }
        assertThat(result.ragTokens).isGreaterThan(0)
        assertThat(result.ragTokens).isAtMost(conversation.children.filter { it.id in setOf("snapshot", "retrieval", "knowledge_graph", "web_search") }.sumOf { it.tokens })
        assertThat(result.conversationTokens + result.ragTokens).isEqualTo(conversation.tokens)
        assertThat(ContextUsageBreakdown.estimate(prompt, emptyList(), 0, 10000)).isEqualTo(result)
    }

    @Test fun `非用户上下文忽略且附件计入媒体类别`() {
        val prompt = PromptRequest(model = "demo", messages = listOf(ProtocolMessage("assistant", "回复", turnContext = "不发送"),
            ProtocolMessage("user", "文档", documentData = listOf(DocumentInput(name = "demo.pdf")),
                audioData = listOf(AudioInput("sample")), videoData = listOf(VideoInput("sample")))))
        val result = ContextUsageBreakdown.estimate(prompt, emptyList(), 0, 10000)
        assertConsistent(result, prompt)
        assertThat(result.categories.last().children.filter { it.id !in setOf("user_body", "assistant_body", "media") }.sumOf { it.tokens }).isEqualTo(0)
        assertThat(result.categories.last().children.single { it.id == "media" }.tokens)
            .isEqualTo(ApproximateTokenEstimator.DOCUMENT_TOKENS + ApproximateTokenEstimator.AUDIO_TOKENS + ApproximateTokenEstimator.VIDEO_TOKENS)
    }

    @Test fun `未知系统提示回退到指令段且空请求总量为零`() {
        val prompt = PromptRequest(model = "demo", messages = listOf(ProtocolMessage("system", "自定义系统提示")))
        assertConsistent(ContextUsageBreakdown.estimate(prompt, listOf(ContextPromptSection("project_rules", "不匹配")), 0, 10000), prompt)
        val empty = PromptRequest(model = "demo", messages = emptyList())
        assertConsistent(ContextUsageBreakdown.estimate(empty, emptyList(), 0, 10000), empty)
    }

    @Test fun `巨大媒体列表与混合类别饱和后保持非负且总和一致`() {
        val prompt = PromptRequest(model = "demo", messages = listOf(
            ProtocolMessage("system", "系统说明"),
            ProtocolMessage("user", "输入", videoData = List(32768) { VideoInput("") }),
            ProtocolMessage("assistant", "回复", reasoning = "思考"),
        ), tools = listOf(tool("builtin")))
        val result = ContextUsageBreakdown.estimate(prompt, emptyList(), 1, 10000)
        assertConsistent(result, prompt)
        assertThat(result.totalTokens).isEqualTo(Int.MAX_VALUE)
    }

    @Test fun `每种轮次块独立分类且结构开销跟随所属叶子`() {
        val cases = mapOf(
            "system_time" to "[System Time: 2030-01-01 (UTC)]\n",
            "snapshot" to "## Reference Material\n边界说明\n<reference_material>\n### Available Material Snapshot\n资料目录\n</reference_material>\n",
            "memory" to "## Reference Material\n边界说明\n<reference_material>\n### Long-term memory\n记忆正文\n</reference_material>\n",
            "retrieval" to "## Reference Material\n边界说明\n<reference_material>\n### Retrieved Context\n检索正文\n</reference_material>\n",
            "knowledge_graph" to "## Reference Material\n边界说明\n<reference_material>\n### Knowledge Graph Relations\n关系正文\n</reference_material>\n",
            "web_search" to "## Reference Material\n边界说明\n<reference_material>\n### Web Search Results\n搜索正文\n</reference_material>\n",
            "task_plan" to "## Active Task Plan\n计划正文\n### Task Tree\n```\n步骤\n```\n",
            "status_board" to "<state_board revision=\"1\">\n## 会话状态面板\n{\"title\":\"状态\"}\n</state_board>\n",
            "skill_body" to "## Requested Skill\n<skill_activation>\n# Skill: demo\n技能正文\n</skill_activation>\n",
        )
        cases.forEach { (id, body) ->
            val block = "<turn_context>\n<snapshot_metadata>{}</snapshot_metadata>\n$body</turn_context>"
            val prompt = PromptRequest(model = "demo", messages = listOf(ProtocolMessage("user", "", turnContext = block)))
            val result = ContextUsageBreakdown.estimate(prompt, emptyList(), 0, 10000)
            assertConsistent(result, prompt)
            val leaves = result.categories.last().children
            assertThat(leaves.single { it.id == id }.tokens).isEqualTo(ApproximateTokenEstimator.estimate(block) + 1)
            assertThat(leaves.filter { it.id != id && it.id != "user_body" }.sumOf { it.tokens }).isEqualTo(0)
            if (id == "memory") assertThat(result.ragTokens).isEqualTo(0)
        }
    }

    @Test fun `技能正文内的同名标题不生成新的叶子且元数据独立出现时属于快照`() {
        val block = "<turn_context>\n<snapshot_metadata>{}</snapshot_metadata>\n## Requested Skill\n<skill_activation>\n" +
            "## Reference Material\n<reference_material>\n### Retrieved Context\n示例\n</reference_material>\n" +
            "## Active Task Plan\n示例计划\n<state_board revision=\"2\">\n示例状态\n</state_board>\n</skill_activation>\n</turn_context>"
        val prompt = PromptRequest(model = "demo", messages = listOf(ProtocolMessage("user", "", turnContext = block)))
        val result = ContextUsageBreakdown.estimate(prompt, emptyList(), 0, 10000)
        assertConsistent(result, prompt)
        assertThat(result.categories.last().children.single { it.id == "skill_body" }.tokens).isEqualTo(ApproximateTokenEstimator.estimate(block) + 1)
        assertThat(result.ragTokens).isEqualTo(0)
        val metadataOnly = prompt.copy(messages = listOf(ProtocolMessage("user", "", turnContext = "<turn_context>\n<snapshot_metadata>{}</snapshot_metadata>\n</turn_context>")))
        val snapshot = ContextUsageBreakdown.estimate(metadataOnly, emptyList(), 0, 10000)
        assertConsistent(snapshot, metadataOnly)
        assertThat(snapshot.categories.last().children.single { it.id == "snapshot" }.tokens).isGreaterThan(0)
        assertThat(snapshot.ragTokens).isEqualTo(0)
    }

    @Test fun `MCP使用服务器ID且无法识别的定义合入内置`() {
        val builtin = tool("builtin")
        val unknown = tool("mcp")
        val prompt = PromptRequest(model = "demo", messages = emptyList(), tools = listOf(builtin, unknown,
            tool("mcp", "alpha"), tool("mcp", runtimeId = "mcp:beta:read"), tool("mcp", runtimeId = "mcp::read")))
        val result = ContextUsageBreakdown.estimate(prompt, emptyList(), 0, 10000)
        assertConsistent(result, prompt)
        val leaves = result.categories[1].children
        assertThat(leaves.map { it.id }).containsExactly("builtin", "mcp:alpha", "mcp:beta").inOrder()
        val each = ApproximateTokenEstimator.estimate(builtin.function.name + builtin.function.description + builtin.function.parameters)
        assertThat(leaves.first().tokens).isEqualTo(each * 3)
    }

    @Test fun `没有助手提示时系统开销跟随首个实际系统段`() {
        val sections = listOf(ContextPromptSection("runtime", "运行环境\n"), ContextPromptSection("permissions", "权限\n"))
        val prompt = PromptRequest(model = "demo", messages = listOf(ProtocolMessage("system", sections.joinToString("") { it.content })))
        val result = ContextUsageBreakdown.estimate(prompt, sections, 0, 10000)
        assertConsistent(result, prompt)
        assertThat(result.categories.first().children.single { it.id == "assistant_prompt" }.tokens).isEqualTo(0)
        assertThat(result.categories.first().children.single { it.id == "runtime" }.tokens).isAtLeast(ApproximateTokenEstimator.MESSAGE_OVERHEAD_TOKENS)
    }

    @Test fun `真实构建器段落与轮次输入可直接平铺且知识不重复计数`() = runTest {
        val context = ContextBuilder().buildContext(ContextBuilderParams(
            "s", "输入", assistantMsgId = "a",
            session = com.promenar.infinity.data.model.Session("s", "agent", customPrompt = "会话指令", summary = "历史摘要",
                options = com.promenar.infinity.data.model.SessionOptions(enableTimeInjection = true)),
            agentSystemPrompt = "助手提示词", availableToolNames = setOf("read_file"),
            retrievalMode = com.promenar.infinity.data.agent.AgentRetrievalMode.TOOL,
            knowledgeCatalog = "资料目录",
            requestedSkills = listOf(RequestedSkill.Loaded("demo", "技能正文")),
            cachedRetrieval = ContextRetrieval("网页内容", emptyList(), "检索正文", emptyList(), null, "图谱内容", emptyList()),
        ))
        val prompt = PromptRequest(model = "demo", messages = listOf(
            ProtocolMessage("system", context.finalSystemPrompt), ProtocolMessage("user", "输入", turnContext = context.turnContext)))
        val result = ContextUsageBreakdown.estimate(prompt, context.systemPromptSections, 0, 10000)
        assertConsistent(result, prompt)
        listOf("assistant_prompt", "session_instructions", "runtime", "permissions", "tool_guidance", "knowledge_catalog", "history_summary")
            .forEach { id -> assertThat(result.categories.first().children.single { it.id == id }.tokens).isGreaterThan(0) }
        listOf("system_time", "snapshot", "retrieval", "knowledge_graph", "web_search", "skill_body")
            .forEach { id -> assertThat(result.categories.last().children.single { it.id == id }.tokens).isGreaterThan(0) }
        assertThat(result.ragTokens).isGreaterThan(0)
    }

    @Test fun `检索片段的未闭合代码围栏不吞掉后续生成块`() {
        val block = "<turn_context>\n<snapshot_metadata>{}</snapshot_metadata>\n## Reference Material\n<reference_material>\n" +
            "### Retrieved Context\n```\n检索代码\n### Knowledge Graph Relations\n图谱\n</reference_material>\n" +
            "## Active Task Plan\n计划\n### Task Tree\n```\n<state_board revision=\"9\">\n代码示例\n```\n" +
            "[Use get_plan to re-read the full task tree when context is truncated.]\n" +
            "<state_board revision=\"1\">\n状态\n</state_board>\n## Requested Skill\n技能正文\n</turn_context>"
        val prompt = PromptRequest(model = "demo", messages = listOf(ProtocolMessage("user", "", turnContext = block)))
        val result = ContextUsageBreakdown.estimate(prompt, emptyList(), 0, 10000)
        assertConsistent(result, prompt)
        listOf("retrieval", "knowledge_graph", "task_plan", "status_board", "skill_body").forEach { id ->
            assertThat(result.categories.last().children.single { it.id == id }.tokens).isGreaterThan(0)
        }
    }

    @Test fun `真实任务步骤含内部代码围栏时后续状态板与技能仍独立分类`() = runTest {
        val task = com.promenar.infinity.data.model.TaskState(id = "task", title = "计划", status = "in_progress",
            steps = listOf(com.promenar.infinity.data.model.TaskStep(id = "step", title = "执行", description = "```python\nprint(1)")))
        val tasks = io.mockk.mockk<com.promenar.infinity.domain.repository.ITaskRepository>()
        io.mockk.coEvery { tasks.getPlan("s") } returns task
        val board = io.mockk.mockk<com.promenar.infinity.data.repository.StateBoardRepository>(relaxed = true)
        io.mockk.coEvery { board.get("s", any()) } returns com.promenar.infinity.data.state.StateBoardSnapshot(
            1, com.promenar.infinity.data.state.StateBoard("状态", emptyList()))
        val context = ContextBuilder(taskRepository = tasks, stateBoardRepository = board).buildContext(ContextBuilderParams(
            "s", "输入", assistantMsgId = "a",
            session = com.promenar.infinity.data.model.Session("s", "agent",
                options = com.promenar.infinity.data.model.SessionOptions(enableTimeInjection = false)),
            requestedSkills = listOf(RequestedSkill.Loaded("demo", "技能正文")),
            cachedRetrieval = ContextRetrieval("", emptyList(), "", emptyList(), null, "", emptyList()),
        ))
        val prompt = PromptRequest(model = "demo", messages = listOf(ProtocolMessage("user", "", turnContext = context.turnContext)))
        val result = ContextUsageBreakdown.estimate(prompt, emptyList(), 0, 10000)
        assertConsistent(result, prompt)
        listOf("task_plan", "status_board", "skill_body").forEach { id ->
            assertThat(result.categories.last().children.single { it.id == id }.tokens).isGreaterThan(0)
        }
    }

    private fun assertConsistent(result: ContextUsageEstimate, prompt: PromptRequest) {
        fun check(category: ContextUsageCategory) {
            assertThat(category.tokens).isAtLeast(0)
            if (category.children.isNotEmpty()) assertThat(category.children.sumOf { it.tokens }).isEqualTo(category.tokens)
            category.children.forEach(::check)
        }
        result.categories.forEach(::check)
        assertThat(result.categories.map { it.id }).containsExactly("system_prompt", "tool_definitions", "conversation").inOrder()
        assertThat(result.categories.flatMap { it.children }.all { it.children.isEmpty() }).isTrue()
        assertThat(result.categories.flatMap { it.children }.map { it.id }).containsNoneOf("instructions", "runtime_permissions", "turn_context", "knowledge", "other", "mcp:unknown")
        assertThat(result.categories.sumOf { it.tokens }).isEqualTo(result.totalTokens)
        assertThat(result.systemTokens + result.summaryTokens + result.conversationTokens + result.ragTokens).isEqualTo(result.totalTokens)
        assertThat(result.totalTokens).isEqualTo(ApproximateTokenEstimator.estimate(prompt))
    }

    private fun tool(source: String, server: String? = null, runtimeId: String = "") = ProtocolTool(
        function = ProtocolToolFunction("read", "读取", "{}"), sourceId = source, mcpServerId = server, runtimeToolId = runtimeId,
    )
}
