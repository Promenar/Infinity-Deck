package com.promenar.infinity.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpResponseData
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

/** Anthropic 思考块：流中按原顺序收集含签名的块，回传时原样放在助手内容最前，被拒绝时去掉思考块重发一次。 */
class AnthropicThinkingReplayTest {
    private val tool = ProtocolTool(function = ProtocolToolFunction("weather", "weather", "{\"type\":\"object\"}"))

    private fun protocol(
        model: String = "claude-opus-5-5",
        respond: MockRequestHandleScope.(Int) -> HttpResponseData,
        bodies: MutableList<String>,
    ) = AnthropicProtocol(
        baseUrl = "https://api.anthropic.com",
        apiKey = "test-key",
        model = model,
        httpClient = HttpClient(MockEngine { request ->
            bodies += request.body.toByteArray().decodeToString()
            respond(bodies.size)
        }),
    )

    private fun MockRequestHandleScope.sse(body: String) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))

    private fun body(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun `流式思考块连同签名与 redacted 块按出现顺序在完成前发出`() = runBlocking {
        val bodies = mutableListOf<String>()
        val chunks = protocol(respond = { sse(THINKING_TOOL_STREAM) }, bodies = bodies)
            .sendPrompt(PromptRequest(listOf(ProtocolMessage("user", "weather?")), "claude-opus-5-5", tools = listOf(tool)))
            .toList()

        val state = chunks.filterIsInstance<StreamChunk.ReasoningState>().single()
        val blocks = (state.replay as ReasoningReplay.AnthropicBlocks).blocks
        assertThat(blocks).containsExactly(
            AnthropicThinkingBlock(type = "thinking", thinking = "先查天气", signature = "sig-1"),
            AnthropicThinkingBlock(type = "redacted_thinking", data = "enc-2"),
        ).inOrder()
        assertThat(chunks.indexOf(state)).isLessThan(chunks.indexOfFirst { it is StreamChunk.Completed })
        assertThat(chunks.filterIsInstance<StreamChunk.Thinking>().joinToString("") { it.content }).isEqualTo("先查天气")
    }

    @Test
    fun `缺签名的思考块不进入回传状态`() = runBlocking {
        val bodies = mutableListOf<String>()
        val chunks = protocol(respond = { sse(UNSIGNED_THINKING_STREAM) }, bodies = bodies)
            .sendPrompt(PromptRequest(listOf(ProtocolMessage("user", "hi")), "claude-opus-5-5"))
            .toList()
        assertThat(chunks.filterIsInstance<StreamChunk.ReasoningState>()).isEmpty()
        assertThat(chunks.filterIsInstance<StreamChunk.Completed>()).hasSize(1)
    }

    @Test
    fun `回传时思考块原样排在助手文本与工具调用之前`() = runBlocking {
        val bodies = mutableListOf<String>()
        protocol(respond = { sse(TEXT_STREAM) }, bodies = bodies).sendPrompt(replayRequest()).toList()

        val content = body(bodies.single())["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertThat(content.map { it.jsonObject["type"]!!.jsonPrimitive.content })
            .containsExactly("thinking", "redacted_thinking", "text", "tool_use").inOrder()
        val thinking = content[0].jsonObject
        assertThat(thinking["thinking"]!!.jsonPrimitive.content).isEqualTo("先查天气")
        assertThat(thinking["signature"]!!.jsonPrimitive.content).isEqualTo("sig-1")
        assertThat(content[1].jsonObject["data"]!!.jsonPrimitive.content).isEqualTo("enc-2")
    }

    @Test
    fun `思考块签名被拒绝时去掉思考块重发一次且不向上报错`() = runBlocking {
        val bodies = mutableListOf<String>()
        val chunks = protocol(
            respond = { attempt ->
                if (attempt == 1) {
                    respond(
                        SIGNATURE_REJECTED,
                        HttpStatusCode.BadRequest,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    sse(TEXT_STREAM)
                }
            },
            bodies = bodies,
        ).sendPrompt(replayRequest()).toList()

        assertThat(bodies).hasSize(2)
        assertThat(bodies[0]).contains("\"signature\"")
        val retried = body(bodies[1])["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertThat(retried.map { it.jsonObject["type"]!!.jsonPrimitive.content }).containsExactly("text", "tool_use").inOrder()
        assertThat(chunks.filterIsInstance<StreamChunk.Error>()).isEmpty()
        assertThat(chunks.filterIsInstance<StreamChunk.Completed>()).hasSize(1)
    }

    @Test
    fun `其它 400 与没有思考块的请求不重发`() = runBlocking {
        val bodies = mutableListOf<String>()
        val chunks = protocol(
            respond = { respond(SIGNATURE_REJECTED, HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json")) },
            bodies = bodies,
        ).sendPrompt(PromptRequest(listOf(ProtocolMessage("user", "hi")), "claude-opus-5-5")).toList()
        assertThat(bodies).hasSize(1)
        assertThat(chunks.filterIsInstance<StreamChunk.Error>()).hasSize(1)

        val otherBodies = mutableListOf<String>()
        protocol(
            respond = { respond(OTHER_BAD_REQUEST, HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json")) },
            bodies = otherBodies,
        ).sendPrompt(replayRequest()).toList()
        assertThat(otherBodies).hasSize(1)
    }

    @Test
    fun `Claude 5 请求摘要思考且不发送采样参数`() = runBlocking {
        val bodies = mutableListOf<String>()
        protocol(respond = { sse(TEXT_STREAM) }, bodies = bodies).sendPrompt(
            PromptRequest(listOf(ProtocolMessage("user", "hi")), "claude-opus-5-5", temperature = 0.7, topP = 0.9, topK = 40, maxTokens = 8000),
        ).toList()
        val sent = body(bodies.single())
        val thinking = sent["thinking"]!!.jsonObject
        assertThat(thinking["type"]!!.jsonPrimitive.content).isEqualTo("adaptive")
        assertThat(thinking["display"]!!.jsonPrimitive.content).isEqualTo("summarized")
        assertThat(sent.keys).containsNoneOf("temperature", "top_p", "top_k")
        assertThat(sent["max_tokens"]!!.jsonPrimitive.content).isEqualTo("8000")
    }

    @Test
    fun `思考与采样参数按模型代际选择`() {
        assertThat(rejectsSamplingParams("claude-opus-4-7")).isTrue()
        assertThat(rejectsSamplingParams("anthropic.claude-sonnet-5-5-v1:0")).isTrue()
        assertThat(rejectsSamplingParams("claude-sonnet-4-6")).isFalse()
        assertThat(rejectsSamplingParams("claude-3-7-sonnet-latest")).isFalse()

        assertThat(anthropicThinkingConfig("claude-haiku-5-5", null, 4096)!!["type"]!!.jsonPrimitive.content).isEqualTo("adaptive")
        assertThat(anthropicThinkingConfig("claude-sonnet-4-6", null, 4096)).isNull()
        assertThat(anthropicThinkingConfig("claude-sonnet-4-6", true, 4096)!!["type"]!!.jsonPrimitive.content).isEqualTo("adaptive")
        val legacy = anthropicThinkingConfig("claude-3-7-sonnet-latest", true, 4096)!!
        assertThat(legacy["type"]!!.jsonPrimitive.content).isEqualTo("enabled")
        assertThat(legacy["budget_tokens"]!!.jsonPrimitive.content).isEqualTo("4095")
        assertThat(anthropicThinkingConfig("claude-3-7-sonnet-latest", true, 1024)).isNull()
    }

    @Test
    fun `旧模型保留采样参数且默认不开启思考`() = runBlocking {
        val bodies = mutableListOf<String>()
        protocol(model = "claude-3-7-sonnet-latest", respond = { sse(TEXT_STREAM) }, bodies = bodies).sendPrompt(
            PromptRequest(listOf(ProtocolMessage("user", "hi")), "claude-3-7-sonnet-latest", temperature = 0.5),
        ).toList()
        val sent = body(bodies.single())
        assertThat(sent["temperature"]!!.jsonPrimitive.content).isEqualTo("0.5")
        assertThat(sent.keys).doesNotContain("thinking")
    }

    private fun replayRequest() = PromptRequest(
        messages = listOf(
            ProtocolMessage("user", "weather?"),
            ProtocolMessage(
                role = "assistant",
                content = "我来查一下。",
                toolCalls = listOf(ProtocolToolCall("toolu_weather", "weather", "{\"city\":\"Shanghai\"}")),
                reasoningReplay = ReasoningReplay.AnthropicBlocks(
                    listOf(
                        AnthropicThinkingBlock(type = "thinking", thinking = "先查天气", signature = "sig-1"),
                        AnthropicThinkingBlock(type = "redacted_thinking", data = "enc-2"),
                    ),
                ),
            ),
            ProtocolMessage("tool", "sunny", name = "weather", toolCallId = "toolu_weather"),
        ),
        model = "claude-opus-5-5",
        tools = listOf(tool),
    )

    private companion object {
        const val THINKING_TOOL_STREAM = """event: message_start
data: {"type":"message_start","message":{"usage":{"input_tokens":10}}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"先查"}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"天气"}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-1"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: content_block_start
data: {"type":"content_block_start","index":1,"content_block":{"type":"redacted_thinking","data":"enc-2"}}

event: content_block_stop
data: {"type":"content_block_stop","index":1}

event: content_block_start
data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_weather","name":"weather","input":{}}}

event: content_block_delta
data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"city\":\"Shanghai\"}"}}

event: content_block_stop
data: {"type":"content_block_stop","index":2}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":5}}

event: message_stop
data: {"type":"message_stop"}

"""

        const val UNSIGNED_THINKING_STREAM = """event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"想"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

event: message_stop
data: {"type":"message_stop"}

"""

        const val TEXT_STREAM = """event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"晴"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

event: message_stop
data: {"type":"message_stop"}

"""

        const val SIGNATURE_REJECTED = """{"type":"error","error":{"type":"invalid_request_error","message":"messages.1.content.0: Invalid `signature` in `thinking` block. The block is bound to a different conversation."}}"""

        const val OTHER_BAD_REQUEST = """{"type":"error","error":{"type":"invalid_request_error","message":"max_tokens: must be greater than 0"}}"""
    }
}
