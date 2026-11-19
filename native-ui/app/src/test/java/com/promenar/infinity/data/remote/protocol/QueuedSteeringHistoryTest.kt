package com.promenar.infinity.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** 工具轮让出后，新用户消息紧跟已配对的工具结果，历史中没有虚构的最终回答。 */
class QueuedSteeringHistoryTest {
    private fun request(replay: ReasoningReplay? = null) = PromptRequest(
        model = "test-model",
        messages = listOf(
            ProtocolMessage("user", "先查询"),
            ProtocolMessage("assistant", "", toolCalls = listOf(ProtocolToolCall("call-1", "lookup", "{}")), reasoningReplay = replay),
            ProtocolMessage("tool", "已查询", toolCallId = "call-1", name = "lookup"),
            ProtocolMessage("user", "按新方向分析"),
        ),
    )

    private suspend fun capture(factory: (HttpClient) -> LlmProtocol): JsonObject {
        var body = ""
        val client = HttpClient(MockEngine { incoming ->
            body = incoming.body.toByteArray().decodeToString()
            respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        })
        try { factory(client).sendPrompt(request()).toList() } finally { client.close() }
        return Json.parseToJsonElement(body).jsonObject
    }

    @Test fun `OpenAI Chat保留空正文工具调用及对应结果后面的用户消息`() = runBlocking {
        val messages = capture { OpenAIProtocol("https://example.test/v1", "test-key", "test-model", it) }["messages"]!!.jsonArray
        assertThat(messages.map { it.jsonObject["role"]!!.jsonPrimitive.content }).containsExactly("user", "assistant", "tool", "user").inOrder()
        assertThat(messages[1].jsonObject["tool_calls"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content).isEqualTo("call-1")
        assertThat(messages[2].jsonObject["tool_call_id"]!!.jsonPrimitive.content).isEqualTo("call-1")
        assertThat(messages.last().jsonObject["content"]!!.jsonPrimitive.content).isEqualTo("按新方向分析")
    }

    @Test fun `Responses保留function_call和output且不构造空助手正文`() = runBlocking {
        val input = capture { OpenAIResponsesProtocol("https://example.test/v1", "test-key", "test-model", it) }["input"]!!.jsonArray
        val call = input.single { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call" }.jsonObject
        val output = input.single { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" }.jsonObject
        assertThat(call["call_id"]!!.jsonPrimitive.content).isEqualTo("call-1")
        assertThat(output["call_id"]!!.jsonPrimitive.content).isEqualTo("call-1")
        assertThat(output["output"]!!.jsonPrimitive.content).isEqualTo("已查询")
        assertThat(input.last().jsonObject["role"]!!.jsonPrimitive.content).isEqualTo("user")
        assertThat(input).hasSize(4)
    }

    @Test fun `Anthropic工具结果映射为user且邻接的新用户内容位于所有结果之后`() = runBlocking {
        val messages = capture { AnthropicProtocol("https://example.test", "test-key", "test-model", httpClient = it) }["messages"]!!.jsonArray
        assertThat(messages.map { it.jsonObject["role"]!!.jsonPrimitive.content }).containsExactly("user", "assistant", "user", "user").inOrder()
        val call = messages[1].jsonObject["content"]!!.jsonArray.single().jsonObject
        assertThat(call["type"]!!.jsonPrimitive.content).isEqualTo("tool_use")
        val result = messages[2].jsonObject["content"]!!.jsonArray.single().jsonObject
        assertThat(result["type"]!!.jsonPrimitive.content).isEqualTo("tool_result")
        assertThat(result["tool_use_id"]!!.jsonPrimitive.content).isEqualTo("call-1")
        assertThat(messages[3].jsonObject["content"]!!.jsonPrimitive.content).isEqualTo("按新方向分析")
    }

    @Test fun `Gemini合并工具结果与新用户消息且保留原调用签名`() {
        val replay = ReasoningReplay.GeminiSignatures(calls = mapOf("call-1" to "signature"))
        for (isVertex in listOf(false, true)) {
            val contents = Json.parseToJsonElement(GeminiContentCodec.buildRequestBody(request(replay), Json, isVertex)).jsonObject["contents"]!!.jsonArray
            assertThat(contents.map { it.jsonObject["role"]!!.jsonPrimitive.content }).containsExactly("user", "model", "user").inOrder()
            val call = contents[1].jsonObject["parts"]!!.jsonArray.single().jsonObject
            assertThat(call["thoughtSignature"]!!.jsonPrimitive.content).isEqualTo("signature")
            val parts = contents[2].jsonObject["parts"]!!.jsonArray
            assertThat(parts[0].jsonObject["functionResponse"]!!.jsonObject["name"]!!.jsonPrimitive.content).isEqualTo("lookup")
            assertThat(parts[1].jsonObject["text"]!!.jsonPrimitive.content).isEqualTo("按新方向分析")
        }
    }
}
