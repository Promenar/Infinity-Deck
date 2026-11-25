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

class AnthropicProtocolErrorClassificationTest {

    @Test
    fun `SSE authentication_error 映射为不可重试 AUTH`() = runBlocking {
        assertSseError("authentication_error", GenerationFailureCode.AUTH, retryable = false)
    }

    @Test
    fun `SSE rate_limit_error 映射为可重试 RATE_LIMIT`() = runBlocking {
        assertSseError("rate_limit_error", GenerationFailureCode.RATE_LIMIT, retryable = true)
    }

    @Test
    fun `SSE billing_error 映射为不可重试 QUOTA`() = runBlocking {
        assertSseError("billing_error", GenerationFailureCode.QUOTA, retryable = false)
    }

    @Test
    fun `SSE invalid_request_error 映射为不可重试 INVALID_REQUEST`() = runBlocking {
        assertSseError("invalid_request_error", GenerationFailureCode.INVALID_REQUEST, retryable = false)
    }

    @Test
    fun `SSE overloaded_error 映射为可重试 SERVER`() = runBlocking {
        assertSseError("overloaded_error", GenerationFailureCode.SERVER, retryable = true)
    }

    @Test
    fun `SSE 未知类型仅映射为可重试 UNKNOWN`() = runBlocking {
        assertSseError(
            type = "future_error_type",
            expectedCode = GenerationFailureCode.UNKNOWN,
            retryable = true,
            message = "rate limit exceeded 但没有稳定机器码",
        )
    }

    @Test
    fun `HTTP 429 使用稳定类型并保留 Retry-After 秒数`() = runBlocking {
        val protocol = protocolResponding(
            status = HttpStatusCode.TooManyRequests,
            body = errorPayload("rate_limit_error"),
            headers = headersOf(
                HttpHeaders.ContentType to listOf("application/json"),
                HttpHeaders.RetryAfter to listOf("17"),
            ),
        )

        val error = protocolError(protocol)

        assertThat(error.code).isEqualTo(GenerationFailureCode.RATE_LIMIT)
        assertThat(error.retryable).isTrue()
        assertThat(error.retryAfterSeconds).isEqualTo(17)
        assertInternalOnly(error)
    }

    @Test
    fun `HTTP 429 的 billing_error 优先映射 QUOTA 而非限流`() = runBlocking {
        val protocol = protocolResponding(
            status = HttpStatusCode.TooManyRequests,
            body = errorPayload("billing_error"),
        )

        val error = protocolError(protocol)

        assertThat(error.code).isEqualTo(GenerationFailureCode.QUOTA)
        assertThat(error.retryable).isFalse()
        assertThat(error.retryAfterSeconds).isNull()
    }

    @Test
    fun `HTTP HTML 响应的 SERVER 分类保持可重试语义一致`() = runBlocking {
        val error = protocolError(protocolResponding(
            status = HttpStatusCode.OK,
            body = "<html>gateway error</html>",
            headers = headersOf(HttpHeaders.ContentType, "text/html"),
        ))

        assertThat(error.code).isEqualTo(GenerationFailureCode.SERVER)
        assertThat(error.retryable).isTrue()
    }

    @Test
    fun `SSE 顶层余额错误码保留非重试诊断`() = runBlocking {
        val payload = """{"type":"error","code":"insufficient_balance","message":"请求失败"}"""
        val protocol = protocolResponding(
            status = HttpStatusCode.OK,
            body = "event: error\ndata: $payload\n\n",
            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
        )
        val error = protocolError(protocol)
        assertThat(error.code).isEqualTo(GenerationFailureCode.QUOTA)
        assertThat(error.retryable).isFalse()
        assertThat(error.httpStatus).isNull()
        assertThat(error.errorCode).isEqualTo("insufficient_balance")
        assertThat(error.providerMessage).isEqualTo("请求失败")
    }

    @Test
    fun `请求取消异常不会被转换为错误分片`() = runBlocking {
        val protocol = AnthropicProtocol(
            baseUrl = "https://example.test",
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

    private suspend fun assertSseError(
        type: String,
        expectedCode: GenerationFailureCode,
        retryable: Boolean,
        message: String = "provider-private-detail",
    ) {
        val protocol = protocolResponding(
            status = HttpStatusCode.OK,
            body = "event: error\ndata: ${errorPayload(type, message)}\n\n",
            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
        )

        val error = protocolError(protocol)

        assertThat(error.code).isEqualTo(expectedCode)
        assertThat(error.retryable).isEqualTo(retryable)
        assertThat(error.retryAfterSeconds).isNull()
        assertInternalOnly(error)
    }

    private fun protocolResponding(
        status: HttpStatusCode,
        body: String,
        headers: io.ktor.http.Headers = headersOf(HttpHeaders.ContentType, "application/json"),
    ): AnthropicProtocol = AnthropicProtocol(
        baseUrl = "https://example.test",
        apiKey = "test-key",
        model = "test-model",
        httpClient = HttpClient(MockEngine { respond(body, status, headers) }),
    )

    private suspend fun protocolError(protocol: AnthropicProtocol): StreamChunk.Error =
        protocol.sendPrompt(testRequest()).filterIsInstance<StreamChunk.Error>().first()

    private fun testRequest() = PromptRequest(
        messages = listOf(ProtocolMessage(role = "user", content = "test")),
        model = "test-model",
    )

    private fun errorPayload(type: String, message: String = "provider-private-detail"): String =
        """{"type":"error","error":{"type":"$type","message":"$message"}}"""

    private fun assertInternalOnly(error: StreamChunk.Error) {
        assertThat(error.message).isEmpty()
        error.technical?.let { assertThat(error.toString()).doesNotContain(it) }
    }
}
