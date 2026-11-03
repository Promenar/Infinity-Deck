package com.promenar.infinity.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.generation.GenerationFailureCode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class OpenAIResponsesProtocolTest {

    @Test
    fun `response failed 的 invalid_api_key 映射为不可重试 AUTH`() = runBlocking {
        assertResponseFailed("invalid_api_key", GenerationFailureCode.AUTH, retryable = false)
    }

    @Test
    fun `response failed 的 rate_limit_exceeded 映射为可重试 RATE_LIMIT`() = runBlocking {
        assertResponseFailed("rate_limit_exceeded", GenerationFailureCode.RATE_LIMIT, retryable = true)
    }

    @Test
    fun `response failed 的 insufficient_quota 映射为不可重试 QUOTA`() = runBlocking {
        assertResponseFailed("insufficient_quota", GenerationFailureCode.QUOTA, retryable = false)
    }

    @Test
    fun `response failed 的 context_length_exceeded 映射为不可重试 CONTEXT_LIMIT`() = runBlocking {
        assertResponseFailed("context_length_exceeded", GenerationFailureCode.CONTEXT_LIMIT, retryable = false)
    }

    @Test
    fun `response failed 的 server_error 映射为可重试 SERVER`() = runBlocking {
        assertResponseFailed("server_error", GenerationFailureCode.SERVER, retryable = true)
    }

    @Test
    fun `response failed 未知 code 仅映射为可重试 UNKNOWN`() = runBlocking {
        assertResponseFailed(
            code = "future_error_code",
            expectedCode = GenerationFailureCode.UNKNOWN,
            retryable = true,
            message = "rate limit exceeded 但没有稳定机器码",
        )
    }

    @Test
    fun `顶层 error 事件优先使用稳定 type 分类`() = runBlocking {
        val event = """{"type":"error","error":{"type":"invalid_request_error","code":"bad_parameter","message":"provider-private-detail"}}"""
        val error = protocolError(protocolResponding(HttpStatusCode.OK, sse(event)))

        assertThat(error.code).isEqualTo(GenerationFailureCode.INVALID_REQUEST)
        assertThat(error.retryable).isFalse()
        assertInternalOnly(error)
    }

    @Test
    fun `HTTP 429 使用错误 code 并保留 Retry-After 秒数`() = runBlocking {
        val body = """{"error":{"type":"rate_limit_error","code":"rate_limit_exceeded","message":"provider-private-detail"}}"""
        val protocol = protocolResponding(
            status = HttpStatusCode.TooManyRequests,
            body = body,
            headers = headersOf(
                HttpHeaders.ContentType to listOf("application/json"),
                HttpHeaders.RetryAfter to listOf("23"),
            ),
        )

        val error = protocolError(protocol)

        assertThat(error.code).isEqualTo(GenerationFailureCode.RATE_LIMIT)
        assertThat(error.retryable).isTrue()
        assertThat(error.retryAfterSeconds).isEqualTo(23)
        assertInternalOnly(error)
    }

    @Test
    fun `HTTP 429 的 insufficient_quota 优先映射 QUOTA`() = runBlocking {
        val body = """{"error":{"type":"rate_limit_error","code":"insufficient_quota","message":"provider-private-detail"}}"""
        val error = protocolError(protocolResponding(HttpStatusCode.TooManyRequests, body))

        assertThat(error.code).isEqualTo(GenerationFailureCode.QUOTA)
        assertThat(error.retryable).isFalse()
        assertThat(error.retryAfterSeconds).isNull()
    }

    @Test
    fun `请求取消异常不会被转换为错误分片`() = runBlocking {
        val protocol = OpenAIResponsesProtocol(
            baseUrl = "https://example.test/v1",
            apiKey = "test-key",
            model = "test-model",
            httpClient = HttpClient(MockEngine { throw CancellationException("cancelled") }),
        )

        try {
            protocol.sendPrompt(testRequest()).first()
            fail("预期 CancellationException 继续传播")
        } catch (_: CancellationException) {
            // 预期路径：取消不得被包装为 StreamChunk.Error。
        }
    }

    private suspend fun assertResponseFailed(
        code: String,
        expectedCode: GenerationFailureCode,
        retryable: Boolean,
        message: String = "provider-private-detail",
    ) {
        val event = """{"type":"response.failed","response":{"error":{"code":"$code","message":"$message"}}}"""
        val error = protocolError(protocolResponding(HttpStatusCode.OK, sse(event)))

        assertThat(error.code).isEqualTo(expectedCode)
        assertThat(error.retryable).isEqualTo(retryable)
        assertThat(error.retryAfterSeconds).isNull()
        assertInternalOnly(error)
    }

    private fun protocolResponding(
        status: HttpStatusCode,
        body: String,
        headers: io.ktor.http.Headers = headersOf(HttpHeaders.ContentType, "application/json"),
    ): OpenAIResponsesProtocol = OpenAIResponsesProtocol(
        baseUrl = "https://example.test/v1",
        apiKey = "test-key",
        model = "test-model",
        httpClient = HttpClient(MockEngine { respond(body, status, headers) }),
    )

    private suspend fun protocolError(protocol: OpenAIResponsesProtocol): StreamChunk.Error =
        protocol.sendPrompt(testRequest()).filterIsInstance<StreamChunk.Error>().first()

    private fun testRequest() = PromptRequest(
        messages = listOf(ProtocolMessage(role = "user", content = "test")),
        model = "test-model",
    )

    private fun sse(event: String): String = "data: $event\n\n"

    private fun assertInternalOnly(error: StreamChunk.Error) {
        assertThat(error.message).isEmpty()
        error.technical?.let { assertThat(error.toString()).doesNotContain(it) }
    }
}
