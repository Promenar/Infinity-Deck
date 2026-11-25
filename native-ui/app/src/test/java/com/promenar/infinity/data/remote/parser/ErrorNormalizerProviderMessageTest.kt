package com.promenar.infinity.data.remote.parser

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.generation.isRetryableStreamFailure
import com.promenar.infinity.data.remote.protocol.streamErrorEvent
import com.promenar.infinity.data.remote.protocol.toStreamChunkError
import com.promenar.infinity.domain.generation.GenerationFailureCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class ErrorNormalizerProviderMessageTest {
    private fun failureOf(status: Int, body: String) =
        ErrorNormalizer.normalize(HttpStatusException(status, body)).toFailure()

    @Test
    fun `网关把上游限流包成 400 时归为限流并自动重试，保留原因`() {
        val failure = failureOf(400, """{"error":{"message":"当前分组上游负载已饱和，请稍后再试","type":"new_api_error"}}""")
        assertThat(failure.code).isEqualTo(GenerationFailureCode.RATE_LIMIT)
        assertThat(isRetryableStreamFailure(failure)).isTrue()
        assertThat(failure.providerMessage).isEqualTo("当前分组上游负载已饱和，请稍后再试")

        assertThat(failureOf(400, """{"error":{"message":"Rate limit reached for requests"}}""").code)
            .isEqualTo(GenerationFailureCode.RATE_LIMIT)
        assertThat(failureOf(403, """{"error":{"message":"Too many requests, please slow down"}}""").code)
            .isEqualTo(GenerationFailureCode.RATE_LIMIT)
    }

    @Test
    fun `真正的参数错误仍不重试，但带上服务商原因`() {
        val failure = failureOf(400, """{"error":{"message":"Invalid 'messages[3].tool_call_id' (request id: 2026102811014031)"}}""")
        assertThat(failure.code).isEqualTo(GenerationFailureCode.INVALID_REQUEST)
        assertThat(isRetryableStreamFailure(failure)).isFalse()
        assertThat(failure.providerMessage).contains("tool_call_id")
    }

    @Test
    fun `响应体里的请求编号含 401 或 403 时不误判为鉴权失败`() {
        assertThat(failureOf(400, """{"error":{"message":"bad input (request id: 20261028401403)"}}""").code)
            .isEqualTo(GenerationFailureCode.INVALID_REQUEST)
        assertThat(failureOf(401, """{"error":{"message":"Invalid token"}}""").code).isEqualTo(GenerationFailureCode.AUTH)
    }

    @Test
    fun `额度不足的中文说明归为额度`() {
        assertThat(failureOf(400, """{"error":{"message":"用户额度不足，剩余额度：0"}}""").code)
            .isEqualTo(GenerationFailureCode.QUOTA)
    }

    @Test
    fun `非 HTTP 异常只认明确的状态码前缀`() {
        assertThat(ErrorNormalizer.normalize(IllegalStateException("Unexpected JSON token at offset 412")).category)
            .isEqualTo(ErrorCategory.UNKNOWN)
        assertThat(ErrorNormalizer.normalize(IllegalStateException("HTTP 503 from upstream")).category)
            .isEqualTo(ErrorCategory.SERVER_ERROR)
        assertThat(ErrorNormalizer.normalize(IllegalStateException("status code: 429")).category)
            .isEqualTo(ErrorCategory.RATE_LIMIT)
    }

    @Test
    fun `流内错误事件按内容分类并保留原因，无法归类时可重试`() {
        val limited = streamErrorEvent(Json.parseToJsonElement("""{"error":{"message":"Rate limit exceeded","code":429}}""").jsonObject)
        assertThat(limited.code).isEqualTo(GenerationFailureCode.RATE_LIMIT)
        assertThat(limited.providerMessage).isEqualTo("Rate limit exceeded")

        val unknown = streamErrorEvent(Json.parseToJsonElement("""{"error":{"type":"upstream_error","message":"synthetic failure"}}""").jsonObject)
        assertThat(unknown.code).isEqualTo(GenerationFailureCode.SERVER)
        assertThat(unknown.retryable).isTrue()
        assertThat(unknown.providerMessage).isEqualTo("synthetic failure")
    }

    @Test
    fun `余额耗尽超越网关限流封装并透传三类诊断`() {
        val examples = listOf(
            Triple(402, """{"error":{"message":"Payment required"}}""", null),
            Triple(429, """{"base_resp":{"status_code":1008,"status_msg":"rate limit"}}""", "1008"),
            Triple(429, """{"base_resp":{"status_code":1008},"error":{"code":"gateway_error","message":"rate limit"}}""", "gateway_error"),
            Triple(429, """{"error":{"code":"gateway_error","type":"insufficient_funds","message":"rate limit"}}""", "gateway_error"),
            Triple(429, """{"error":{"code":"balance_not_enough","message":"rate limit"}}""", "balance_not_enough"),
            Triple(429, """{"code":"insufficient_balance","message":"rate limit"}""", "insufficient_balance"),
            Triple(429, """  {"code":"insufficient_funds","error":{"code":"gateway_error"}}""", "gateway_error"),
            Triple(403, """{"status_msg":"insufficient balance"}""", null),
            Triple(502, """{"status_msg":"credit balance is too low"}""", null),
        )
        for ((status, body, errorCode) in examples) {
            val normalized = ErrorNormalizer.normalize(HttpStatusException(status, body))
            val chunk = normalized.toStreamChunkError()
            val failure = normalized.toFailure()
            assertThat(normalized).isInstanceOf(NormalizedError.QuotaExceeded::class.java)
            assertThat(chunk.retryable).isFalse()
            assertThat(chunk.httpStatus).isEqualTo(status)
            assertThat(chunk.errorCode).isEqualTo(errorCode)
            assertThat(chunk.rawDiagnostic).isEqualTo(failure.rawDiagnostic)
            assertThat(failure.httpStatus).isEqualTo(status)
            assertThat(failure.errorCode).isEqualTo(errorCode)
        }
    }
}
