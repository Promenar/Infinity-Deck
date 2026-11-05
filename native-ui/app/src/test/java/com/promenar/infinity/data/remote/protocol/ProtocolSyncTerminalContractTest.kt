package com.promenar.infinity.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.security.KeyPairGenerator
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ProtocolSyncTerminalContractTest {
    @Test
    fun `OpenAI 与 Generic 同步响应只接受和工具调用一致的 finish reason`() = runBlocking {
        val valid = """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"","tool_calls":[{"id":"call","type":"function","function":{"name":"search","arguments":"{}"}}]}}]}"""
        val invalid = listOf(
            """{"choices":[{"finish_reason":"tool_calls","message":{"content":""}}]}""",
            """{"choices":[{"finish_reason":"future","message":{"content":"ok"}}]}""",
            """{"choices":[{"finish_reason":"tool_calls","message":{"content":"","tool_calls":[{"id":"","function":{"name":"search","arguments":"{}"}}]}}]}""",
            """{"choices":[{"finish_reason":"tool_calls","message":{"content":"","tool_calls":[{"id":"call","function":{"name":"","arguments":"{}"}}]}}]}""",
            """{"choices":[{"finish_reason":"tool_calls","message":{"content":"","tool_calls":[{"id":"same","function":{"name":"search","arguments":"{}"}},{"id":"same","function":{"name":"time","arguments":"{}"}}]}}]}""",
            """{"choices":[{"finish_reason":"tool_calls","message":{"content":"","tool_calls":[{"id":"call","function":{"name":"search","arguments":"[]"}}]}}]}""",
        )

        listOf<(String) -> LlmProtocol>(
            { body -> openAi(body) },
            { body -> generic(body) },
        ).forEach { factory ->
            val response = factory(valid).sendPromptSync(request())
            assertThat(response.toolCalls).containsExactly(ProtocolToolCall("call", "search", "{}"))
            // 兼容层以 stop 结束但携带完整工具调用时按工具调用处理。
            val stopWithTools = """{"choices":[{"finish_reason":"stop","message":{"content":"ok","tool_calls":[{"id":"call","function":{"name":"search","arguments":"{}"}}]}}]}"""
            assertThat(factory(stopWithTools).sendPromptSync(request()).toolCalls)
                .containsExactly(ProtocolToolCall("call", "search", "{}"))
            invalid.forEach { body ->
                assertThat(runCatching { factory(body).sendPromptSync(request()) }.exceptionOrNull())
                    .isNotNull()
            }
        }
    }

    @Test
    fun `Anthropic 同步 stop reason 与 tool_use 必须一致且参数保持 JSON object`() = runBlocking {
        val validTool = """{"stop_reason":"tool_use","content":[{"type":"tool_use","id":"toolu","name":"search","input":{"q":"x"}}]}"""
        val toolResponse = anthropic(validTool).sendPromptSync(request())
        assertThat(toolResponse.toolCalls)
            .containsExactly(ProtocolToolCall("toolu", "search", "{\"q\":\"x\"}"))

        val invalid = listOf(
            """{"stop_reason":"tool_use","content":[{"type":"text","text":"no tool"}]}""",
            """{"stop_reason":"tool_use","content":[{"type":"tool_use","id":"","name":"search","input":{}}]}""",
            """{"stop_reason":"tool_use","content":[{"type":"tool_use","id":"toolu","name":"","input":{}}]}""",
            """{"stop_reason":"tool_use","content":[{"type":"tool_use","id":"same","name":"search","input":{}},{"type":"tool_use","id":"same","name":"time","input":{}}]}""",
            """{"stop_reason":"tool_use","content":[{"type":"tool_use","id":"toolu","name":"search","input":[]}]}""",
            """{"stop_reason":"future","content":[{"type":"text","text":"text"}]}""",
        )
        invalid.forEach { body ->
            assertThat(runCatching { anthropic(body).sendPromptSync(request()) }.exceptionOrNull())
                .isNotNull()
        }

        var requests = 0
        val invalidHistory = AnthropicProtocol(
            baseUrl = "https://api.anthropic.com",
            apiKey = "key",
            model = "model",
            httpClient = HttpClient(MockEngine {
                requests++
                respondJson(validTool)
            }),
        )
        val failure = runCatching {
            invalidHistory.sendPromptSync(
                request().copy(messages = listOf(
                    ProtocolMessage(
                        role = "assistant",
                        content = "",
                        toolCalls = listOf(ProtocolToolCall("toolu", "search", "[]")),
                    ),
                )),
            )
        }.exceptionOrNull()
        assertThat(failure).isNotNull()
        assertThat(requests).isEqualTo(0)
    }

    @Test
    fun `Responses 同步只接受 completed root status`() = runBlocking {
        val valid = """{"status":"completed","output_text":"ok","output":[{"type":"message"}]}"""
        assertThat(responses(valid).sendPromptSync(request()).content).isEqualTo("ok")
        val validTool = """{"status":"completed","output":[{"type":"function_call","call_id":"call","name":"search","arguments":"{}"}]}"""
        assertThat(responses(validTool).sendPromptSync(request()).toolCalls)
            .containsExactly(ProtocolToolCall("call", "search", "{}"))

        listOf("failed", "incomplete", "future").forEach { status ->
            val body = """{"status":"$status","output_text":"must not pass","output":[{"type":"message"}]}"""
            assertThat(runCatching { responses(body).sendPromptSync(request()) }.exceptionOrNull())
                .isNotNull()
        }
        listOf(
            """{"status":"completed","output":[{"type":"function_call","call_id":"","name":"search","arguments":"{}"}]}""",
            """{"status":"completed","output":[{"type":"function_call","call_id":"call","name":"","arguments":"{}"}]}""",
            """{"status":"completed","output":[{"type":"function_call","call_id":"same","name":"search","arguments":"{}"},{"type":"function_call","call_id":"same","name":"time","arguments":"{}"}]}""",
            """{"status":"completed","output":[{"type":"function_call","call_id":"call","name":"search","arguments":"[]"}]}""",
        ).forEach { body ->
            assertThat(runCatching { responses(body).sendPromptSync(request()) }.exceptionOrNull())
                .isNotNull()
        }
    }

    @Test
    fun `Vertex 同步即使已有文本也只接受 STOP finish reason`() = runBlocking {
        assertThat(vertex("STOP").sendPromptSync(request()).content).isEqualTo("text")

        listOf("SAFETY", "MAX_TOKENS", "future").forEach { finishReason ->
            assertThat(runCatching { vertex(finishReason).sendPromptSync(request()) }.exceptionOrNull())
                .isNotNull()
        }
    }

    @Test
    fun `同步响应输出达到上限时抛出带 OUTPUT_LIMIT 的非正常结束`() = runBlocking {
        val cases = listOf<Pair<LlmProtocol, String>>(
            openAi("""{"choices":[{"finish_reason":"length","message":{"content":"{\"nodes\":["}}]}""") to "OpenAI",
            generic("""{"choices":[{"finish_reason":"length","message":{"content":"{\"nodes\":["}}]}""") to "Generic",
            anthropic("""{"stop_reason":"max_tokens","content":[{"type":"text","text":"{"}]}""") to "Anthropic",
            responses("""{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}""") to "Responses",
            vertex("MAX_TOKENS") to "Vertex",
        )
        cases.forEach { (protocol, label) ->
            val failure = runCatching { protocol.sendPromptSync(request()) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(AbnormalFinishException::class.java)
            assertThat((failure as AbnormalFinishException).error.code)
                .isEqualTo(com.promenar.infinity.domain.generation.GenerationFailureCode.OUTPUT_LIMIT)
            assertThat(label).isNotEmpty()
        }
    }

    private fun request() = PromptRequest(
        messages = listOf(ProtocolMessage("user", "test")),
        model = "model",
    )

    private fun openAi(body: String) = OpenAIProtocol(
        "https://api.openai.com", "key", "model", jsonClient(body),
    )

    private fun generic(body: String) = GenericOpenAICompatProtocol(
        "https://compat.example/v1", "key", "model", httpClient = jsonClient(body),
    )

    private fun anthropic(body: String) = AnthropicProtocol(
        "https://api.anthropic.com", "key", "model", httpClient = jsonClient(body),
    )

    private fun responses(body: String) = OpenAIResponsesProtocol(
        "https://api.openai.com", "key", "model", jsonClient(body),
    )

    private fun vertex(finishReason: String): VertexAIProtocol {
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "oauth2.googleapis.com") {
                respondJson("""{"access_token":"token","expires_in":3600}""")
            } else {
                respondJson(
                    """{"candidates":[{"finishReason":"$finishReason","content":{"parts":[{"text":"text"}]}}]}""",
                )
            }
        })
        return VertexAIProtocol(
            baseUrl = "https://us-central1-aiplatform.googleapis.com",
            serviceAccountJson = validVertexCredentialJson(),
            projectId = "",
            model = "model",
            httpClient = client,
        )
    }

    private fun jsonClient(body: String) = HttpClient(MockEngine { respondJson(body) })

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(body: String) = respond(
        content = body,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun validVertexCredentialJson(): String {
        val encoded = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
            .generateKeyPair().private.encoded
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getEncoder().encodeToString(encoded) +
            "\n-----END PRIVATE KEY-----"
        encoded.fill(0)
        return """{"project_id":"project-safe","client_email":"service@example.invalid","private_key":${jsonString(pem)}}"""
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }
}
