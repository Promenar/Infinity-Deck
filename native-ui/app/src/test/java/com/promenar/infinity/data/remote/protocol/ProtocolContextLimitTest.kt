package com.promenar.infinity.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.generation.GenerationFailedException
import com.promenar.infinity.domain.generation.GenerationFailureCode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

/** 上下文超限只采用稳定机器码及有官方来源的固定错误句式。 */
class ProtocolContextLimitTest {
    @Test
    fun `稳定上下文机器码映射为不可重试 CONTEXT_LIMIT`() {
        assertContext(ProtocolErrorClassifier.classify(code = "context_length_exceeded"))
        assertContext(ProtocolErrorClassifier.classify(type = "exceed_context_size_error", statusCode = 400))
    }

    @Test
    fun `通用分类器不依据自然语言改变普通参数错误`() {
        val error = ProtocolErrorClassifier.classify(
            statusCode = 400,
            type = "invalid_request_error",
            technical = "prompt is too long: 210000 tokens > 200000 maximum",
        )
        assertThat(error.code).isEqualTo(GenerationFailureCode.INVALID_REQUEST)
    }

    @Test
    fun `Anthropic 仅在请求错误内识别官方 prompt is too long 前缀`() {
        assertContext(ProtocolContextLimit.fromHttp(
            ProtocolType.Anthropic_Messages,
            400,
            body("prompt is too long: 210000 tokens > 200000 maximum", "invalid_request_error"),
        ))
        assertThat(ProtocolContextLimit.fromHttp(
            ProtocolType.Anthropic_Messages, 400,
            body("max_tokens: 20000 exceeds the maximum output tokens", "invalid_request_error"),
        )).isNull()
        assertThat(ProtocolContextLimit.fromHttp(
            ProtocolType.Anthropic_Messages, 500,
            body("prompt is too long: upstream diagnostic", "api_error"),
        )).isNull()
        assertThat(ProtocolContextLimit.fromHttp(
            ProtocolType.Anthropic_Messages, 413,
            body("prompt is too long: rejected request body", "invalid_request_error"),
        )).isNull()
        assertThat(ProtocolContextLimit.fromHttp(
            ProtocolType.Anthropic_Messages, 400,
            body("Invalid tool schema: description contains prompt is too long", "invalid_request_error"),
        )).isNull()
    }

    @Test
    fun `vLLM 官方输入超限句式与输出参数错误分开`() {
        assertContext(ProtocolContextLimit.fromHttp(
            ProtocolType.Generic_OpenAI_Compat, 400,
            body("Input length (4097) exceeds model's maximum context length (4096).", "BadRequestError"),
        ))
        assertContext(ProtocolContextLimit.fromHttp(
            ProtocolType.OpenAI_ChatCompletions, 400,
            body("This model's maximum context length is 4096 tokens. However, your prompt contains at least 4097 input tokens, which exceeds this limit even without any output tokens. Please reduce the length of the input prompt.", "BadRequestError"),
        ))
        assertThat(ProtocolContextLimit.fromHttp(
            ProtocolType.Generic_OpenAI_Compat, 400,
            body("max_tokens must be at most 4096 output tokens", "BadRequestError"),
        )).isNull()
    }

    @Test
    fun `Ollama 固定输入超限错误可识别且普通 413 不代表上下文超限`() {
        assertContext(ProtocolContextLimit.fromHttp(
            ProtocolType.Generic_OpenAI_Compat, 400,
            """{"error":"the input length exceeds the context length"}""",
        ))
        assertThat(ProtocolContextLimit.fromHttp(
            ProtocolType.Generic_OpenAI_Compat, 413,
            """{"error":"request body too large"}""",
        )).isNull()
    }

    @Test
    fun `Google 输入限制固定句式与通用 INVALID_ARGUMENT 分开`() {
        for (protocol in listOf(ProtocolType.Google_Gemini, ProtocolType.Google_VertexAI)) {
            assertContext(ProtocolContextLimit.fromHttp(
                protocol, 400,
                """{"error":{"code":400,"status":"FAILED_PRECONDITION","message":"Request exceeds the model's input token limit."}}""",
            ))
            assertContext(ProtocolContextLimit.fromHttp(
                protocol, 400,
                """{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"The input token count (1048577) exceeds the maximum number of tokens allowed (1048576)."}}""",
            ))
            assertThat(ProtocolContextLimit.fromHttp(
                protocol, 400,
                """{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"maxOutputTokens must be less than 8192"}}""",
            )).isNull()
            assertThat(ProtocolContextLimit.fromHttp(
                protocol, 400,
                """{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"The input token count (many) exceeds the maximum number of tokens allowed (1048576)."}}""",
            )).isNull()
            assertThat(ProtocolContextLimit.fromHttp(
                protocol, 500,
                """{"error":{"code":500,"status":"INTERNAL","message":"Request exceeds the model's input token limit."}}""",
            )).isNull()
        }
    }

    @Test
    fun `输出结束上限保留 OUTPUT_LIMIT 且 Anthropic 上下文终止单独分类`() {
        assertContext(abnormalFinishError("Anthropic", "model_context_window_exceeded"))
        for (reason in listOf("length", "max_tokens", "MAX_TOKENS", "max_output_tokens")) {
            val error = requireNotNull(abnormalFinishError("provider", reason))
            assertThat(error.code).isEqualTo(GenerationFailureCode.OUTPUT_LIMIT)
            assertThat(error.retryable).isFalse()
        }
    }

    @Test
    fun `OpenAI Chat 与兼容协议 HTTP 上下文错误均保留专用分类`() = runBlocking {
        val payload = body("diagnostic", "invalid_request_error", "context_length_exceeded")
        for (protocol in listOf(
            OpenAIProtocol("https://example.test/v1", "", "fixture", client(400, payload)),
            GenericOpenAICompatProtocol("https://example.test/v1", "", "fixture", httpClient = client(400, payload)),
        )) {
            assertContext(protocol.sendPrompt(request()).filterIsInstance<StreamChunk.Error>().first())
        }
    }

    @Test
    fun `OpenAI Chat 流内错误使用稳定上下文 code`() = runBlocking {
        val event = body("diagnostic", "invalid_request_error", "context_length_exceeded")
        val protocol = OpenAIProtocol("https://example.test/v1", "", "fixture", client(200, sse(event)))
        assertContext(protocol.sendPrompt(request()).filterIsInstance<StreamChunk.Error>().first())
    }

    @Test
    fun `Responses 顶层 error 事件识别稳定上下文 code`() = runBlocking {
        val event = """{"type":"error","code":"context_length_exceeded","message":"diagnostic"}"""
        val protocol = OpenAIResponsesProtocol("https://example.test/v1", "", "fixture", client(200, sse(event)))
        assertContext(protocol.sendPrompt(request()).filterIsInstance<StreamChunk.Error>().first())
    }

    @Test
    fun `Anthropic HTTP 与 SSE 固定错误和上下文 stop reason 统一分类`() = runBlocking {
        val payload = body("prompt is too long: 210000 tokens > 200000 maximum", "invalid_request_error")
        for ((status, content) in listOf(
            400 to payload,
            200 to "event: error\ndata: $payload\n\n",
            200 to "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"model_context_window_exceeded\"}}\n\n",
        )) {
            val protocol = AnthropicProtocol("https://example.test", "", "fixture", httpClient = client(status, content))
            assertContext(protocol.sendPrompt(request()).filterIsInstance<StreamChunk.Error>().first())
        }
    }

    @Test
    fun `Gemini HTTP 与流内输入超限统一分类`() = runBlocking {
        val payload = """{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"The input token count (1048577) exceeds the maximum number of tokens allowed (1048576)."}}"""
        for ((status, content) in listOf(400 to payload, 200 to sse(payload))) {
            val protocol = GeminiProtocol("https://example.test", "", "fixture", client(status, content))
            assertContext(protocol.sendPrompt(request()).filterIsInstance<StreamChunk.Error>().first())
        }
        assertContext(geminiHttpError(400, payload))
    }

    @Test
    fun `DeepSeek 通用参数错误与双义 length 不凭推测归为上下文超限`() {
        assertThat(ProtocolContextLimit.fromHttp(
            ProtocolType.DeepSeek, 422, body("Invalid Parameters", "invalid_request_error"),
        )).isNull()
        assertThat(abnormalFinishError("DeepSeek", "length")?.code).isEqualTo(GenerationFailureCode.OUTPUT_LIMIT)
    }

    @Test
    fun `同步 HTTP 上下文失败通过既有结构化异常返回`() = runBlocking {
        val machineError = body("diagnostic", "invalid_request_error", "context_length_exceeded")
        val googleError = """{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"Request exceeds the model's input token limit."}}"""
        val protocols = listOf(
            OpenAIProtocol("https://example.test/v1", "", "fixture", client(400, machineError)),
            GenericOpenAICompatProtocol("https://example.test/v1", "", "fixture", httpClient = client(400, machineError)),
            OpenAIResponsesProtocol("https://example.test/v1", "", "fixture", client(400, machineError)),
            AnthropicProtocol("https://example.test", "", "fixture", httpClient = client(400,
                body("prompt is too long: 210000 tokens > 200000 maximum", "invalid_request_error"))),
            GeminiProtocol("https://example.test", "", "fixture", client(400, googleError)),
        )
        for (protocol in protocols) {
            val failure = runCatching { protocol.sendPromptSync(request()) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(GenerationFailedException::class.java)
            val structured = (failure as GenerationFailedException).failure
            assertThat(structured.code).isEqualTo(GenerationFailureCode.CONTEXT_LIMIT)
            assertThat(structured.formatArgs[com.promenar.infinity.domain.generation.GenerationFailure.KEY_CONTEXT_LIMIT_REASON]).isEqualTo("UPSTREAM")
            assertThat(com.promenar.infinity.domain.generation.GenerationFailureCodec.decode(
                com.promenar.infinity.domain.generation.GenerationFailureCodec.encode(structured))?.formatArgs).isEqualTo(structured.formatArgs)
            assertThat(structured.retryable).isFalse()
        }
    }

    @Test
    fun `同步 Anthropic 上下文 stop 与 Responses 错误体保留结构化上下文失败`() = runBlocking {
        val protocols = listOf(
            AnthropicProtocol("https://example.test", "", "fixture", httpClient = client(200,
                """{"content":[],"stop_reason":"model_context_window_exceeded"}""")),
            OpenAIResponsesProtocol("https://example.test/v1", "", "fixture", client(200,
                """{"status":"failed","error":{"code":"context_length_exceeded","message":"diagnostic"}}""")),
        )
        for (protocol in protocols) {
            val failure = runCatching { protocol.sendPromptSync(request()) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(GenerationFailedException::class.java)
            assertThat((failure as GenerationFailedException).failure.code).isEqualTo(GenerationFailureCode.CONTEXT_LIMIT)
        }
    }

    private fun client(status: Int, payload: String) = HttpClient(MockEngine {
        respond(payload, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType,
            if (status == 200) "text/event-stream" else "application/json"))
    })

    private fun request() = PromptRequest(listOf(ProtocolMessage("user", "synthetic")), "fixture")
    private fun sse(payload: String) = "data: $payload\n\n"
    private fun body(message: String, type: String? = null, code: String? = null): String = buildJsonObject {
        put("error", buildJsonObject {
            put("message", message)
            type?.let { put("type", it) }
            code?.let { put("code", it) }
        })
    }.toString()

    private fun assertContext(error: StreamChunk.Error?) {
        assertThat(error).isNotNull()
        assertThat(error?.code).isEqualTo(GenerationFailureCode.CONTEXT_LIMIT)
        assertThat(error?.retryable).isFalse()
        assertThat(error?.retryAfterSeconds).isNull()
    }
}
