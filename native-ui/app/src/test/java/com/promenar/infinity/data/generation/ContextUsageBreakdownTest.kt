package com.promenar.infinity.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.remote.protocol.*
import org.junit.Test

class ContextUsageBreakdownTest {
    @Test fun `系统真实段落与每个MCP服务器分别统计且ASCII余数不增加总量`() {
        val sections = listOf(ContextPromptSection("instructions", "a"), ContextPromptSection("project_rules", "b"),
            ContextPromptSection("history_summary", "c\n"))
        val prompt = PromptRequest(model = "demo", messages = listOf(ProtocolMessage("system", sections.joinToString("") { it.content })),
            tools = listOf(tool("builtin"), tool("mcp", "alpha"), tool("mcp", "beta"), tool("builtin", runtimeId = "mcp:legacy:read")))
        val result = ContextUsageBreakdown.estimate(prompt, sections, 2, 10000)
        assertConsistent(result, prompt)
        assertThat(result.categories[0].children.map { it.id }).containsAtLeast("instructions", "project_rules", "history_summary")
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
        val turn = conversation.children.single { it.id == "turn_context" }
        assertThat(turn.children.map { it.id }).containsExactly("memory", "knowledge", "other").inOrder()
        assertThat(turn.children.all { it.tokens > 0 }).isTrue()
        assertThat(result.ragTokens).isEqualTo(turn.children.single { it.id == "knowledge" }.tokens)
        assertThat(result.conversationTokens + result.ragTokens).isEqualTo(conversation.tokens)
        assertThat(ContextUsageBreakdown.estimate(prompt, emptyList(), 0, 10000)).isEqualTo(result)
    }

    @Test fun `非用户上下文忽略且附件计入媒体类别`() {
        val prompt = PromptRequest(model = "demo", messages = listOf(ProtocolMessage("assistant", "回复", turnContext = "不发送"),
            ProtocolMessage("user", "文档", documentData = listOf(DocumentInput(name = "demo.pdf")),
                audioData = listOf(AudioInput("sample")), videoData = listOf(VideoInput("sample")))))
        val result = ContextUsageBreakdown.estimate(prompt, emptyList(), 0, 10000)
        assertConsistent(result, prompt)
        assertThat(result.categories.last().children.single { it.id == "turn_context" }.tokens).isEqualTo(0)
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

    private fun assertConsistent(result: ContextUsageEstimate, prompt: PromptRequest) {
        fun check(category: ContextUsageCategory) {
            assertThat(category.tokens).isAtLeast(0)
            if (category.children.isNotEmpty()) assertThat(category.children.sumOf { it.tokens }).isEqualTo(category.tokens)
            category.children.forEach(::check)
        }
        result.categories.forEach(::check)
        assertThat(result.categories.sumOf { it.tokens }).isEqualTo(result.totalTokens)
        assertThat(result.systemTokens + result.summaryTokens + result.conversationTokens + result.ragTokens).isEqualTo(result.totalTokens)
        assertThat(result.totalTokens).isEqualTo(ApproximateTokenEstimator.estimate(prompt))
    }

    private fun tool(source: String, server: String? = null, runtimeId: String = "") = ProtocolTool(
        function = ProtocolToolFunction("read", "读取", "{}"), sourceId = source, mcpServerId = server, runtimeToolId = runtimeId,
    )
}
