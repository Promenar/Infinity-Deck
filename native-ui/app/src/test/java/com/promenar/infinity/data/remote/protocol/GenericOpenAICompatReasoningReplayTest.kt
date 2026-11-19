package com.promenar.infinity.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * OpenAI Chat Completions 兼容族的思考回传：同一模型产生的思考正文按它返回时使用的字段原样回传；
 * DeepSeek 与 Kimi 带 tools 时要求回传 reasoning_content，没有保存来源字段时仍固定回传。
 */
class GenericOpenAICompatReasoningReplayTest {
    private val okStream = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"

    private fun engine(bodies: MutableList<String>, stream: String = okStream) = HttpClient(MockEngine { request ->
        bodies += request.body.toByteArray().decodeToString()
        respond(stream, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
    })

    private fun compat(type: ProtocolType, bodies: MutableList<String>, stream: String = okStream) = GenericOpenAICompatProtocol(
        baseUrl = "https://compat.example/v1",
        apiKey = "test-key",
        model = "test-model",
        protocolType = type,
        httpClient = engine(bodies, stream),
    )

    private fun request(replay: ReasoningReplay? = null) = PromptRequest(
        messages = listOf(
            ProtocolMessage("user", "明天杭州天气"),
            ProtocolMessage(
                role = "assistant",
                content = "",
                reasoning = "需要先知道今天日期。",
                toolCalls = listOf(ProtocolToolCall("call_1", "get_date", "{}")),
                reasoningReplay = replay,
            ),
            ProtocolMessage("tool", "2026-10-19", name = "get_date", toolCallId = "call_1"),
            ProtocolMessage(role = "assistant", content = "明天多云。", reasoning = "  ", reasoningReplay = replay),
            ProtocolMessage("user", "后天呢"),
        ),
        model = "test-model",
        tools = listOf(ProtocolTool(function = ProtocolToolFunction("get_date", "date", "{\"type\":\"object\"}"))),
    )

    private fun assistantMessages(body: String): List<JsonObject> =
        Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["role"]!!.jsonPrimitive.content == "assistant" }

    private fun send(protocol: LlmProtocol, request: PromptRequest, bodies: List<String>): List<JsonObject> = runBlocking {
        protocol.sendPrompt(request).toList()
        assistantMessages(bodies.last())
    }

    @Test
    fun `任意兼容服务按保存的来源字段回传非空思考`() {
        listOf(
            ProtocolType.Generic_OpenAI_Compat to "reasoning_content",
            ProtocolType.Qwen_DashScope to "reasoning_content",
            ProtocolType.Zhipu_GLM to "reasoning_content",
            ProtocolType.Generic_OpenAI_Compat to "reasoning",
        ).forEach { (type, field) ->
            val bodies = mutableListOf<String>()
            val (toolRound, answer) = send(compat(type, bodies), request(ReasoningReplay.ChatField(field)), bodies)
            assertThat(toolRound[field]!!.jsonPrimitive.content).isEqualTo("需要先知道今天日期。")
            assertThat(toolRound["tool_calls"]!!.jsonArray).hasSize(1)
            assertThat(answer.containsKey(field)).isFalse()
            ReasoningReplayCodec.CHAT_FIELDS.minus(field).forEach { assertThat(toolRound.containsKey(it)).isFalse() }
        }
    }

    @Test
    fun `OpenAI 预设同样按来源字段回传`() {
        val bodies = mutableListOf<String>()
        val protocol = OpenAIProtocol("https://gateway.example", "test-key", "test-model", httpClient = engine(bodies))
        val (toolRound) = send(protocol, request(ReasoningReplay.ChatField("reasoning_content")), bodies)
        assertThat(toolRound["reasoning_content"]!!.jsonPrimitive.content).isEqualTo("需要先知道今天日期。")

        val (plain) = send(protocol, request(), bodies)
        assertThat(plain.containsKey("reasoning_content")).isFalse()
    }

    @Test
    fun `没有来源字段时只有 DeepSeek 与 Kimi 固定回传 reasoning_content`() {
        listOf(ProtocolType.DeepSeek, ProtocolType.Moonshot_Kimi).forEach { type ->
            val bodies = mutableListOf<String>()
            val (toolRound) = send(compat(type, bodies), request(), bodies)
            assertThat(toolRound["reasoning_content"]!!.jsonPrimitive.content).isEqualTo("需要先知道今天日期。")
        }
        listOf(ProtocolType.Generic_OpenAI_Compat, ProtocolType.Mistral_Chat, ProtocolType.Qwen_DashScope).forEach { type ->
            val bodies = mutableListOf<String>()
            send(compat(type, bodies), request(), bodies).forEach { message ->
                ReasoningReplayCodec.CHAT_FIELDS.forEach { assertThat(message.containsKey(it)).isFalse() }
            }
        }
    }

    @Test
    fun `流中记录思考字段并在完成前发出回传状态`() = runBlocking {
        val streams = mapOf(
            "reasoning_content" to "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"想\"}}]}\n\n" + okStream,
            "reasoning" to "data: {\"choices\":[{\"delta\":{\"reasoning\":\"想\"}}]}\n\n" + okStream,
        )
        streams.forEach { (field, stream) ->
            val chunks = compat(ProtocolType.Generic_OpenAI_Compat, mutableListOf(), stream)
                .sendPrompt(PromptRequest(listOf(ProtocolMessage("user", "hi")), "test-model")).toList()
            val state = chunks.filterIsInstance<StreamChunk.ReasoningState>().single()
            assertThat(state.replay).isEqualTo(ReasoningReplay.ChatField(field))
            assertThat(chunks.indexOf(state)).isLessThan(chunks.indexOfFirst { it is StreamChunk.Completed })
        }
        val plain = compat(ProtocolType.Generic_OpenAI_Compat, mutableListOf())
            .sendPrompt(PromptRequest(listOf(ProtocolMessage("user", "hi")), "test-model")).toList()
        assertThat(plain.filterIsInstance<StreamChunk.ReasoningState>()).isEmpty()
    }

    @Test
    fun `流未正常完成时不发出回传状态`() = runBlocking {
        val truncated = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"想\"}}]}\n\n"
        val chunks = compat(ProtocolType.DeepSeek, mutableListOf(), truncated)
            .sendPrompt(PromptRequest(listOf(ProtocolMessage("user", "hi")), "test-model")).toList()
        assertThat(chunks.filterIsInstance<StreamChunk.ReasoningState>()).isEmpty()
        assertThat(chunks.filterIsInstance<StreamChunk.Error>()).hasSize(1)
    }
}
