package com.promenar.infinity.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** 工具预算耗尽后的收尾轮：仍声明工具，但各协议都必须以自身格式禁止再调用。 */
class ToolChoiceSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val tool = ProtocolTool(
        function = ProtocolToolFunction("search", "search the web", "{\"type\":\"object\",\"properties\":{}}"),
    )

    @Test
    fun `OpenAI 与兼容协议在 NONE 时发送 none，默认发送 auto`() = runTest {
        val openAi = { client: HttpClient -> OpenAIProtocol("https://api.openai.com", "test-key", "gpt", httpClient = client) }
        val compat = { client: HttpClient -> GenericOpenAICompatProtocol("https://example.com/v1", "test-key", "m", httpClient = client) }
        val responses = { client: HttpClient -> OpenAIResponsesProtocol("https://api.openai.com", "test-key", "gpt", httpClient = client) }
        for (factory in listOf(openAi, compat, responses)) {
            val none = captureBody(factory, ProtocolToolChoice.NONE)
            assertThat(none["tool_choice"]?.jsonPrimitive?.content).isEqualTo("none")
            assertThat(none.containsKey("tools")).isTrue()
            val auto = captureBody(factory, ProtocolToolChoice.AUTO)
            assertThat(auto["tool_choice"]?.jsonPrimitive?.content).isEqualTo("auto")
        }
    }

    @Test
    fun `Anthropic 在 NONE 时发送 type none，默认不发送 tool_choice`() = runTest {
        val anthropic = { client: HttpClient -> AnthropicProtocol("https://api.anthropic.com", "test-key", "claude", httpClient = client) }

        val none = captureBody(anthropic, ProtocolToolChoice.NONE)
        assertThat(none["tool_choice"]?.jsonObject?.get("type")?.jsonPrimitive?.content).isEqualTo("none")
        assertThat(none.containsKey("tools")).isTrue()
        assertThat(captureBody(anthropic, ProtocolToolChoice.AUTO).containsKey("tool_choice")).isFalse()
    }

    private suspend fun captureBody(
        factory: (HttpClient) -> LlmProtocol,
        choice: ProtocolToolChoice,
    ): JsonObject {
        var captured: String? = null
        val engine = MockEngine { request ->
            captured = request.body.toByteArray().decodeToString()
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val request = PromptRequest(
            messages = listOf(ProtocolMessage(role = "user", content = "hi")),
            model = "m",
            tools = listOf(tool),
            toolChoice = choice,
            stream = false,
        )
        runCatching { factory(HttpClient(engine)).sendPromptSync(request) }
        return json.parseToJsonElement(requireNotNull(captured)).jsonObject
    }
}
