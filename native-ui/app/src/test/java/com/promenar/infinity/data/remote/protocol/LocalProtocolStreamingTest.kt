package com.promenar.infinity.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.inference.LocalInferenceEngine
import com.promenar.infinity.domain.generation.CompletionReason
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class LocalProtocolStreamingTest {

    private fun tool(): ProtocolTool = ProtocolTool(
        function = ProtocolToolFunction(
            name = "read_file",
            description = "读取工作区文件",
            parameters = """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""",
        ),
    )

    @Test
    fun `think and tool_call tags stream cleanly even when split across arbitrary token chunks`(): Unit = runBlocking {
        val prompts = mutableListOf<String>()
        val rawOutput = """<think>先检查参数路径</think>好的，我来读取。<tool_call>{"name":"read_file","arguments":{"path":"notes.md"}}</tool_call>"""
        val chunked = rawOutput.chunked(3).toTypedArray()

        val engine = mockk<LocalInferenceEngine>()
        every { engine.generate(capture(prompts), any()) } returns flowOf(*chunked)

        val protocol = LocalProtocol(engine, "qwen")
        val chunks = protocol.sendPrompt(
            PromptRequest(
                messages = listOf(ProtocolMessage("user", "请读取 notes.md")),
                model = "Qwen3-0.6B.gguf",
                tools = listOf(tool()),
            ),
        ).toList()

        assertThat(prompts).hasSize(1)
        assertThat(prompts.single()).contains("<tools>")
        assertThat(prompts.single()).contains("read_file")

        val thinking = chunks.filterIsInstance<StreamChunk.Thinking>().joinToString("") { it.content }
        val text = chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.content }
        val toolCalls = chunks.filterIsInstance<StreamChunk.ToolCallDelta>()
        val completed = chunks.filterIsInstance<StreamChunk.Completed>().single()

        assertThat(thinking).isEqualTo("先检查参数路径")
        assertThat(text).isEqualTo("好的，我来读取。")
        assertThat(toolCalls).hasSize(1)
        val call = toolCalls.single()
        assertThat(call.name).isEqualTo("read_file")
        assertThat(Json.parseToJsonElement(call.arguments).jsonObject["path"]?.jsonPrimitive?.content)
            .isEqualTo("notes.md")
        assertThat(completed.reason).isEqualTo(CompletionReason.TOOL_CALLS)
        assertThat(completed.completedToolCallIds).containsExactly(call.id)
    }

    @Test
    fun `stringified tool arguments normalize to json object and plain answer stops normally`(): Unit = runBlocking {
        val toolOutput = """<tool_call>{"name":"read_file","arguments":"{\"path\":\"a.txt\"}"}</tool_call>"""
        val answerOutput = """<think>已有结果</think>文件内容已确认。"""

        val engine = mockk<LocalInferenceEngine>()
        every { engine.generate(any(), any()) } returns flowOf(*toolOutput.chunked(5).toTypedArray())

        val protocol = LocalProtocol(engine, "qwen")
        val firstChunks = protocol.sendPrompt(
            PromptRequest(
                messages = listOf(ProtocolMessage("user", "读 a.txt")),
                model = "Qwen3-0.6B.gguf",
                tools = listOf(tool()),
            ),
        ).toList()
        val firstCall = firstChunks.filterIsInstance<StreamChunk.ToolCallDelta>().single()
        val argsObj = Json.parseToJsonElement(firstCall.arguments).jsonObject
        assertThat(argsObj["path"]?.jsonPrimitive?.content).isEqualTo("a.txt")

        val prompts2 = mutableListOf<String>()
        every { engine.generate(capture(prompts2), any()) } returns flowOf(*answerOutput.chunked(4).toTypedArray())
        val secondChunks = protocol.sendPrompt(
            PromptRequest(
                messages = listOf(
                    ProtocolMessage("user", "读 a.txt"),
                    ProtocolMessage(
                        role = "assistant",
                        content = "",
                        toolCalls = listOf(ProtocolToolCall(firstCall.id, "read_file", firstCall.arguments)),
                    ),
                    ProtocolMessage("tool", """{"content":"hello"}""", toolCallId = firstCall.id, name = "read_file"),
                ),
                model = "Qwen3-0.6B.gguf",
                tools = listOf(tool()),
            ),
        ).toList()

        assertThat(prompts2.single()).contains("<tool_response>")
        assertThat(secondChunks.filterIsInstance<StreamChunk.Thinking>().joinToString("") { it.content }).isEqualTo("已有结果")
        assertThat(secondChunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.content }).isEqualTo("文件内容已确认。")
        val completed = secondChunks.filterIsInstance<StreamChunk.Completed>().single()
        assertThat(completed.reason).isEqualTo(CompletionReason.END_TURN)
        assertThat(completed.completedToolCallIds).isEmpty()
    }

    @Test
    fun `malformed tool_call json falls back to plain text and toolChoice none suppresses tool schema`(): Unit = runBlocking {
        val capturedPrompt = mutableListOf<String>()
        val engine = mockk<LocalInferenceEngine>()
        every { engine.generate(capture(capturedPrompt), any()) } returns flowOf("<tool_call>not-valid-json</tool_call>")

        val protocol = LocalProtocol(engine, "qwen")
        val chunks = protocol.sendPrompt(
            PromptRequest(
                messages = listOf(ProtocolMessage("user", "只直接回答")),
                model = "Qwen3-0.6B.gguf",
                tools = listOf(tool()),
                toolChoice = ProtocolToolChoice.NONE,
            ),
        ).toList()

        assertThat(capturedPrompt.single()).doesNotContain("<tools>")
        assertThat(chunks.filterIsInstance<StreamChunk.ToolCallDelta>()).isEmpty()
        assertThat(chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.content })
            .contains("not-valid-json")
        assertThat(chunks.filterIsInstance<StreamChunk.Completed>().single().reason)
            .isEqualTo(CompletionReason.END_TURN)
    }
}
