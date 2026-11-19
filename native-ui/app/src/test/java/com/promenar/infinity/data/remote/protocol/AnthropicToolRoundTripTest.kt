package com.promenar.infinity.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.generation.CompletionReason
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class AnthropicToolRoundTripTest {
    @Test
    fun `tool_use 流在 message_stop 后确认且第二轮 tool_result 保持 ID`() = runBlocking {
        val bodies = mutableListOf<String>()
        val protocol = AnthropicProtocol(
            baseUrl = "https://api.anthropic.com",
            apiKey = "test-key",
            model = "test-model",
            httpClient = HttpClient(MockEngine { request ->
                bodies += request.body.toByteArray().decodeToString()
                respond(
                    if (bodies.size == 1) TOOL_STREAM else TEXT_STREAM,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            }),
        )
        val tool = ProtocolTool(function = ProtocolToolFunction("weather", "weather", "{\"type\":\"object\"}"))
        val first = protocol.sendPrompt(
            PromptRequest(listOf(ProtocolMessage("user", "weather?")), "test-model", tools = listOf(tool)),
        ).toList()
        val completed = first.filterIsInstance<StreamChunk.Completed>().single()
        assertThat(completed.reason).isEqualTo(CompletionReason.TOOL_CALLS)
        assertThat(completed.completedToolCallIds).containsExactly("toolu_weather")

        protocol.sendPrompt(
            PromptRequest(
                listOf(
                    ProtocolMessage("user", "weather?"),
                    ProtocolMessage(
                        role = "assistant",
                        content = "",
                        toolCalls = listOf(ProtocolToolCall("toolu_weather", "weather", "{\"city\":\"Shanghai\"}")),
                    ),
                    ProtocolMessage("tool", "sunny", name = "weather", toolCallId = "toolu_weather"),
                ),
                "test-model",
                tools = listOf(tool),
            ),
        ).toList()

        val messages = Json.parseToJsonElement(bodies.single { it.contains("sunny") })
            .jsonObject["messages"]!!.jsonArray
        val assistantUse = messages[1].jsonObject["content"]!!.jsonArray
            .single { it.jsonObject["type"]!!.jsonPrimitive.content == "tool_use" }.jsonObject
        val result = messages[2].jsonObject["content"]!!.jsonArray.single().jsonObject
        assertThat(assistantUse["id"]!!.jsonPrimitive.content).isEqualTo("toolu_weather")
        assertThat(messages[2].jsonObject["role"]!!.jsonPrimitive.content).isEqualTo("user")
        assertThat(result["type"]!!.jsonPrimitive.content).isEqualTo("tool_result")
        assertThat(result["tool_use_id"]!!.jsonPrimitive.content).isEqualTo("toolu_weather")
    }

    @Test
    fun `tool_use stop 缺少完整 block 与未知 stop reason 均失败关闭`() = runBlocking {
        fun protocol(body: String) = AnthropicProtocol(
            baseUrl = "https://api.anthropic.com",
            apiKey = "test-key",
            model = "test-model",
            httpClient = HttpClient(MockEngine {
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }),
        )
        val missingBlock = protocol(
            "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}\n\n" +
                "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
        ).sendPrompt(PromptRequest(listOf(ProtocolMessage("user", "hi")), "test-model")).toList()
        val unknown = protocol(
            "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"future_reason\"}}\n\n",
        ).sendPrompt(PromptRequest(listOf(ProtocolMessage("user", "hi")), "test-model")).toList()

        assertThat(missingBlock.filterIsInstance<StreamChunk.Error>()).hasSize(1)
        assertThat(missingBlock.filterIsInstance<StreamChunk.Completed>()).isEmpty()
        assertThat(unknown.filterIsInstance<StreamChunk.Error>()).hasSize(1)
        assertThat(unknown.filterIsInstance<StreamChunk.Completed>()).isEmpty()
    }

    private companion object {
        val TOOL_STREAM = """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":10}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_weather","name":"weather","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"city\":\"Shanghai\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":4}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent()
        val TEXT_STREAM = """
            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"sunny"}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent()
    }
}
