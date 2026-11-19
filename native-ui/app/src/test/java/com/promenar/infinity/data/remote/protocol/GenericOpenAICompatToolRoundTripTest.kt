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

class GenericOpenAICompatToolRoundTripTest {
    @Test
    fun `兼容协议确认完整工具 ID 并在第二轮回传 assistant call 与 tool result`() = runBlocking {
        val bodies = mutableListOf<String>()
        val protocol = GenericOpenAICompatProtocol(
            baseUrl = "https://compat.example/v1",
            apiKey = "test-key",
            model = "test-model",
            httpClient = HttpClient(MockEngine { request ->
                bodies += request.body.toByteArray().decodeToString()
                respond(
                    if (bodies.size == 1) """
                        data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":"{\"path\":\"a\"}"}}]},"finish_reason":"tool_calls"}]}

                        data: [DONE]

                    """.trimIndent() else """
                        data: {"choices":[{"delta":{"content":"done"},"finish_reason":"stop"}]}

                        data: [DONE]

                    """.trimIndent(),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            }),
        )

        val chunks = protocol.sendPrompt(
            PromptRequest(
                messages = listOf(ProtocolMessage("user", "read")),
                model = "test-model",
                tools = listOf(
                    ProtocolTool(function = ProtocolToolFunction("read_file", "read", "{\"type\":\"object\"}")),
                ),
            ),
        ).toList()

        val completed = chunks.filterIsInstance<StreamChunk.Completed>().single()
        assertThat(completed.reason).isEqualTo(CompletionReason.TOOL_CALLS)
        assertThat(completed.completedToolCallIds).containsExactly("call_1")

        protocol.sendPrompt(
            PromptRequest(
                messages = listOf(
                    ProtocolMessage("user", "read"),
                    ProtocolMessage(
                        role = "assistant",
                        content = "",
                        toolCalls = listOf(ProtocolToolCall("call_1", "read_file", "{\"path\":\"a\"}")),
                    ),
                    ProtocolMessage("tool", "content", name = "read_file", toolCallId = "call_1"),
                ),
                model = "test-model",
            ),
        ).toList()

        val messages = Json.parseToJsonElement(bodies.single { it.contains("\"content\":\"content\"") })
            .jsonObject["messages"]!!.jsonArray
        val assistant = messages[1].jsonObject
        val result = messages[2].jsonObject
        assertThat(assistant["tool_calls"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
            .isEqualTo("call_1")
        assertThat(result["role"]!!.jsonPrimitive.content).isEqualTo("tool")
        assertThat(result["tool_call_id"]!!.jsonPrimitive.content).isEqualTo("call_1")
    }

    @Test
    fun `兼容协议数组参数不产生 Completed`() = runBlocking {
        val protocol = GenericOpenAICompatProtocol(
            baseUrl = "https://compat.example/v1",
            apiKey = "test-key",
            model = "test-model",
            httpClient = HttpClient(MockEngine {
                respond(
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call\",\"function\":{\"name\":\"read_file\",\"arguments\":\"[]\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            }),
        )

        val chunks = protocol.sendPrompt(
            PromptRequest(listOf(ProtocolMessage("user", "read")), "test-model"),
        ).toList()

        assertThat(chunks.filterIsInstance<StreamChunk.Error>()).hasSize(1)
        assertThat(chunks.filterIsInstance<StreamChunk.Completed>()).isEmpty()
    }
}
