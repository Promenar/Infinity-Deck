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

class OpenAIChatToolRoundTripTest {
    @Test
    fun `tool_calls 流完成后第二轮原样回传 assistant call 与 tool result`() = runBlocking {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            bodies += request.body.toByteArray().decodeToString()
            val body = if (bodies.size == 1) TOOL_STREAM else TEXT_STREAM
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val protocol = OpenAIProtocol(
            baseUrl = "https://api.openai.com",
            apiKey = "test-key",
            model = "test-model",
            httpClient = HttpClient(engine),
        )
        val first = protocol.sendPrompt(request(listOf(user()), tools())).toList()
        val call = first.filterIsInstance<StreamChunk.ToolCallDelta>()
        val completed = first.filterIsInstance<StreamChunk.Completed>().single()

        assertThat(call.joinToString("") { it.arguments }).isEqualTo("{\"city\":\"Shanghai\"}")
        assertThat(completed.reason).isEqualTo(CompletionReason.TOOL_CALLS)
        assertThat(completed.completedToolCallIds).containsExactly("call_weather")

        protocol.sendPrompt(
            request(
                listOf(
                    user(),
                    ProtocolMessage(
                        role = "assistant",
                        content = "",
                        toolCalls = listOf(
                            ProtocolToolCall("call_weather", "weather", "{\"city\":\"Shanghai\"}"),
                        ),
                    ),
                    ProtocolMessage(
                        role = "tool",
                        content = "sunny",
                        name = "weather",
                        toolCallId = "call_weather",
                    ),
                ),
                tools(),
            ),
        ).toList()

        val messages = Json.parseToJsonElement(bodies.single { it.contains("sunny") })
            .jsonObject["messages"]!!.jsonArray
        val assistant = messages[1].jsonObject
        val tool = messages[2].jsonObject
        assertThat(assistant["tool_calls"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
            .isEqualTo("call_weather")
        assertThat(tool["role"]!!.jsonPrimitive.content).isEqualTo("tool")
        assertThat(tool["tool_call_id"]!!.jsonPrimitive.content).isEqualTo("call_weather")
    }

    @Test
    fun `malformed SSE 与缺失 DONE 均失败关闭且不完成工具调用`() = runBlocking {
        val protocol = protocolResponding(
            "data: {not-json}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call\",\"function\":{\"name\":\"weather\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n",
        )

        val chunks = protocol.sendPrompt(request(listOf(user()), tools())).toList()

        assertThat(chunks.filterIsInstance<StreamChunk.Error>()).hasSize(1)
        assertThat(chunks.filterIsInstance<StreamChunk.Completed>()).isEmpty()
    }

    @Test
    fun `已有 tool_calls finish 但 EOF 缺失 DONE 仍按截断失败`() = runBlocking {
        val protocol = protocolResponding(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call\",\"function\":{\"name\":\"weather\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n",
        )

        val chunks = protocol.sendPrompt(request(listOf(user()), tools())).toList()

        val error = chunks.filterIsInstance<StreamChunk.Error>().single()
        assertThat(error.code).isEqualTo(com.promenar.infinity.domain.generation.GenerationFailureCode.NETWORK)
        assertThat(error.retryable).isTrue()
        assertThat(chunks.filterIsInstance<StreamChunk.Completed>()).isEmpty()
    }

    @Test
    fun `未知 finish reason 与重复工具 ID 均拒绝成功 terminal`() = runBlocking {
        val unknown = protocolResponding(
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"future_reason\"}]}\n\ndata: [DONE]\n\n",
        ).sendPrompt(request(listOf(user()), tools())).toList()
        val duplicateIds = protocolResponding(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[" +
                "{\"index\":0,\"id\":\"same\",\"function\":{\"name\":\"weather\",\"arguments\":\"{}\"}}," +
                "{\"index\":1,\"id\":\"same\",\"function\":{\"name\":\"weather\",\"arguments\":\"{}\"}}]}," +
                "\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n",
        ).sendPrompt(request(listOf(user()), tools())).toList()

        assertThat(unknown.filterIsInstance<StreamChunk.Error>()).hasSize(1)
        assertThat(unknown.filterIsInstance<StreamChunk.Completed>()).isEmpty()
        assertThat(duplicateIds.filterIsInstance<StreamChunk.Error>()).hasSize(1)
        assertThat(duplicateIds.filterIsInstance<StreamChunk.Completed>()).isEmpty()
    }

    private fun protocolResponding(body: String) = OpenAIProtocol(
        baseUrl = "https://api.openai.com",
        apiKey = "test-key",
        model = "test-model",
        httpClient = HttpClient(MockEngine {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }),
    )

    private fun request(messages: List<ProtocolMessage>, tools: List<ProtocolTool>) = PromptRequest(
        messages = messages,
        model = "test-model",
        tools = tools,
    )

    private fun user() = ProtocolMessage(role = "user", content = "weather?")

    private fun tools() = listOf(
        ProtocolTool(function = ProtocolToolFunction("weather", "weather", "{\"type\":\"object\"}")),
    )

    private companion object {
        val TOOL_STREAM = """
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_weather","type":"function","function":{"name":"weather","arguments":"{\"city\":"}}]},"finish_reason":null}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"Shanghai\"}"}}]},"finish_reason":"tool_calls"}]}

            data: [DONE]

        """.trimIndent()
        val TEXT_STREAM = """
            data: {"choices":[{"delta":{"content":"It is sunny."},"finish_reason":"stop"}]}

            data: [DONE]

        """.trimIndent()
    }
}
