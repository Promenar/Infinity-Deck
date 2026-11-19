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

class OpenAIResponsesToolRoundTripTest {
    @Test
    fun `Responses 非流式 function_call 保留调用字段顺序与 usage`() = runBlocking {
        val protocol = OpenAIResponsesProtocol(
            baseUrl = "https://api.openai.com",
            apiKey = "test-key",
            model = "test-model",
            httpClient = HttpClient(MockEngine {
                respond(
                    """{"id":"resp","status":"completed","output":[{"id":"fc_1","type":"function_call","call_id":"call_weather","name":"weather","arguments":"{\"city\":\"Shanghai\"}"},{"id":"fc_2","type":"function_call","call_id":"call_time","name":"time","arguments":"{}"}],"usage":{"input_tokens":5,"output_tokens":3,"total_tokens":8}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }),
        )

        val response = protocol.sendPromptSync(
            PromptRequest(listOf(ProtocolMessage("user", "weather?")), "test-model"),
        )

        assertThat(response.toolCalls).containsExactly(
            ProtocolToolCall("call_weather", "weather", "{\"city\":\"Shanghai\"}"),
            ProtocolToolCall("call_time", "time", "{}"),
        ).inOrder()
        assertThat(response.usage).isEqualTo(ProtocolUsage(input = 5, output = 3, total = 8))
    }

    @Test
    fun `Responses function_call 事件与第二轮 function_call_output 使用同一 call_id`() = runBlocking {
        val bodies = mutableListOf<String>()
        val protocol = OpenAIResponsesProtocol(
            baseUrl = "https://api.openai.com",
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
        assertThat(completed.completedToolCallIds).containsExactly("call_weather")
        assertThat(first.filterIsInstance<StreamChunk.ToolCallDelta>().joinToString("") { it.arguments })
            .isEqualTo("{\"city\":\"Shanghai\"}")

        protocol.sendPrompt(
            PromptRequest(
                messages = listOf(
                    ProtocolMessage("user", "weather?"),
                    ProtocolMessage(
                        role = "assistant",
                        content = "",
                        toolCalls = listOf(
                            ProtocolToolCall("call_weather", "weather", "{\"city\":\"Shanghai\"}"),
                        ),
                    ),
                    ProtocolMessage("tool", "sunny", name = "weather", toolCallId = "call_weather"),
                ),
                model = "test-model",
                tools = listOf(tool),
            ),
        ).toList()

        val input = Json.parseToJsonElement(bodies.single { it.contains("sunny") })
            .jsonObject["input"]!!.jsonArray
        val functionCall = input.single { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call" }.jsonObject
        val output = input.single { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" }.jsonObject
        assertThat(functionCall["call_id"]!!.jsonPrimitive.content).isEqualTo("call_weather")
        assertThat(output["call_id"]!!.jsonPrimitive.content).isEqualTo("call_weather")
    }

    @Test
    fun `Responses EOF 与缺失 call_id 都失败关闭`() = runBlocking {
        fun protocol(body: String) = OpenAIResponsesProtocol(
            baseUrl = "https://api.openai.com",
            apiKey = "test-key",
            model = "test-model",
            httpClient = HttpClient(MockEngine {
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }),
        )
        val eof = protocol("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n")
            .sendPrompt(PromptRequest(listOf(ProtocolMessage("user", "hi")), "test-model"))
            .toList()
        val missingId = protocol(
            "data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"id\":\"item\",\"type\":\"function_call\",\"name\":\"weather\",\"arguments\":\"{}\"}}\n\n" +
                "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"name\":\"weather\",\"arguments\":\"{}\"}]}}\n\n",
        ).sendPrompt(PromptRequest(listOf(ProtocolMessage("user", "hi")), "test-model")).toList()

        assertThat(eof.filterIsInstance<StreamChunk.Error>().single().code)
            .isEqualTo(com.promenar.infinity.domain.generation.GenerationFailureCode.NETWORK)
        assertThat(eof.filterIsInstance<StreamChunk.Completed>()).isEmpty()
        assertThat(missingId.filterIsInstance<StreamChunk.Error>()).hasSize(1)
        assertThat(missingId.filterIsInstance<StreamChunk.Completed>()).isEmpty()
    }

    private companion object {
        val TOOL_STREAM = """
            data: {"type":"response.output_item.added","output_index":0,"item":{"id":"fc_item","type":"function_call","call_id":"call_weather","name":"weather","arguments":""}}

            data: {"type":"response.function_call_arguments.delta","item_id":"fc_item","output_index":0,"delta":"{\"city\":"}

            data: {"type":"response.function_call_arguments.done","item_id":"fc_item","output_index":0,"arguments":"{\"city\":\"Shanghai\"}"}

            data: {"type":"response.output_item.done","output_index":0,"item":{"id":"fc_item","type":"function_call","call_id":"call_weather","name":"weather","arguments":"{\"city\":\"Shanghai\"}"}}

            data: {"type":"response.completed","response":{"status":"completed","output":[{"id":"fc_item","type":"function_call","call_id":"call_weather","name":"weather","arguments":"{\"city\":\"Shanghai\"}"}]}}

        """.trimIndent()
        val TEXT_STREAM = """
            data: {"type":"response.output_text.delta","delta":"sunny"}

            data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"message"}]}}

        """.trimIndent()
    }
}
