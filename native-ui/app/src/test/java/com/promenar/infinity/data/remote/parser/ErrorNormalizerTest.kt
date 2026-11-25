package com.promenar.infinity.data.remote.parser

import com.promenar.infinity.domain.generation.GenerationFailureCode
import com.google.common.truth.Truth.assertThat
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.utils.io.ClosedByteChannelException
import io.ktor.utils.io.ConnectionClosedException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.ClosedChannelException
import javax.net.ssl.SSLException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ErrorNormalizerTest {

    private fun normalize(error: Throwable?) = ErrorNormalizer.normalize(error)

    private fun assertFailure(
        normalized: NormalizedError,
        code: GenerationFailureCode,
        retryAfter: Int? = null,
    ) {
        val failure = normalized.toFailure()
        assertThat(failure.code).isEqualTo(code)
        assertThat(failure.retryAfterSeconds).isEqualTo(retryAfter)
        assertThat(failure.technical).isNotEmpty()
        assertThat(failure.technical).isEqualTo(normalized.technicalMessage)
    }

    @Nested
    @DisplayName("null error")
    inner class NullError {

        @Test
        fun `null returns Unknown`() {
            val result = normalize(null)
            assertThat(result).isInstanceOf(NormalizedError.Unknown::class.java)
            assertThat(result.retryable).isTrue()
        }
    }

    @Nested
    @DisplayName("Network errors")
    inner class NetworkErrors {

        @Test
        fun `断流和传输异常可重试且支持包装异常`() {
            val failures = listOf(
                EOFException(),
                SocketException(),
                ConnectException(),
                UnknownHostException(),
                SSLException("peer closed"),
                ClosedChannelException(),
                ClosedReceiveChannelException("Channel closed"),
                ClosedByteChannelException(),
                ConnectionClosedException(),
            )
            for (failure in failures) {
                for (error in listOf(failure, IllegalStateException("流读取失败", failure))) {
                    val result = normalize(error)
                    assertThat(result).isInstanceOf(NormalizedError.Network::class.java)
                    assertThat(result.retryable).isTrue()
                    assertFailure(result, GenerationFailureCode.NETWORK)
                }
            }
        }

        @Test
        fun `按传输异常消息识别提前结束与重置`() {
            val messages = listOf(
                "unexpected end of stream", "unexpected EOF", "premature end of response",
                "stream was reset: CANCEL", "broken pipe", "connection reset",
                "software caused connection abort", "EOF",
            )
            for (message in messages) {
                assertThat(normalize(Exception("流读取失败", IOException(message))).category)
                    .isEqualTo(ErrorCategory.NETWORK)
            }
        }

        @Test
        fun `异常链循环保留已找到的传输原因并能终止`() {
            val outer = Exception("流读取失败")
            val reset = EOFException()
            outer.initCause(reset)
            reset.initCause(outer)
            assertThat(normalize(outer).category).isEqualTo(ErrorCategory.NETWORK)

            val unrelated = Exception("输入失败")
            val cause = IllegalStateException("无效状态", unrelated)
            unrelated.initCause(cause)
            assertThat(normalize(unrelated).category).isEqualTo(ErrorCategory.UNKNOWN)
        }

        @Test
        fun `network in message`() {
            val result = normalize(Exception("Network error"))
            assertThat(result).isInstanceOf(NormalizedError.Network::class.java)
            assertThat(result.retryable).isTrue()
            assertFailure(result, GenerationFailureCode.NETWORK)
            assertThat(result.category).isEqualTo(ErrorCategory.NETWORK)
        }

        @Test
        fun `connection in message`() {
            val result = normalize(Exception("Connection refused"))
            assertThat(result).isInstanceOf(NormalizedError.Network::class.java)
        }

        @Test
        fun `fetch in message`() {
            val result = normalize(Exception("Failed to fetch"))
            assertThat(result).isInstanceOf(NormalizedError.Network::class.java)
        }

        @Test
        fun `ECONNREFUSED in message`() {
            val result = normalize(Exception("Error: ECONNREFUSED 127.0.0.1:443"))
            assertThat(result).isInstanceOf(NormalizedError.Network::class.java)
        }

        @Test
        fun `NetworkError class name`() {
            val result = normalize(NetworkError("err_network"))
            assertThat(result).isInstanceOf(NormalizedError.Network::class.java)
        }
    }

    @Nested
    @DisplayName("Auth errors")
    inner class AuthErrors {

        @Test
        fun `401 status code`() {
            val result = normalize(HttpStatusException(401, "Unauthorized"))
            assertThat(result).isInstanceOf(NormalizedError.Auth::class.java)
            assertThat(result.retryable).isFalse()
            assertFailure(result, GenerationFailureCode.AUTH)
            assertThat(result.technicalMessage).contains("401")
        }

        @Test
        fun `403 status code`() {
            val result = normalize(HttpStatusException(403, "Forbidden"))
            assertThat(result).isInstanceOf(NormalizedError.Auth::class.java)
            assertThat(result.retryable).isFalse()
        }

        @Test
        fun `unauthorized in message`() {
            val result = normalize(Exception("Unauthorized access"))
            assertThat(result).isInstanceOf(NormalizedError.Auth::class.java)
        }

        @Test
        fun `api key in message`() {
            val result = normalize(Exception("Invalid API key provided"))
            assertThat(result).isInstanceOf(NormalizedError.Auth::class.java)
        }

        @Test
        fun `401 in message text`() {
            val result = normalize(Exception("HTTP 401: Auth failed"))
            assertThat(result).isInstanceOf(NormalizedError.Auth::class.java)
        }

        @Test
        fun `forbidden in message`() {
            val result = normalize(Exception("Access forbidden"))
            assertThat(result).isInstanceOf(NormalizedError.Auth::class.java)
        }
    }

    @Nested
    @DisplayName("Rate limit errors")
    inner class RateLimitErrors {

        @Test
        fun `429 status code`() {
            val result = normalize(HttpStatusException(429, "Too Many Requests"))
            assertThat(result).isInstanceOf(NormalizedError.RateLimit::class.java)
            assertThat(result.retryable).isTrue()
            assertThat(result.retryAfter).isEqualTo(60)
            assertFailure(result, GenerationFailureCode.RATE_LIMIT, 60)
        }

        @Test
        fun `429 with retry-after in message`() {
            val result = normalize(
                HttpStatusException(429, "Rate limited. Retry after 30 seconds.")
            )
            assertThat(result).isInstanceOf(NormalizedError.RateLimit::class.java)
            assertThat(result.retryAfter).isEqualTo(30)
            assertFailure(result, GenerationFailureCode.RATE_LIMIT, 30)
        }

        @Test
        fun `429 with wait seconds in message`() {
            val result = normalize(
                HttpStatusException(429, "Please wait 120 seconds before retrying")
            )
            assertThat(result).isInstanceOf(NormalizedError.RateLimit::class.java)
            assertThat(result.retryAfter).isEqualTo(120)
            assertFailure(result, GenerationFailureCode.RATE_LIMIT, 120)
        }

        @Test
        fun `rate limit in message`() {
            val result = normalize(Exception("Rate limit exceeded"))
            assertThat(result).isInstanceOf(NormalizedError.RateLimit::class.java)
        }

        @Test
        fun `throttle in message`() {
            val result = normalize(Exception("Request throttled"))
            assertThat(result).isInstanceOf(NormalizedError.RateLimit::class.java)
        }

        @Test
        fun `RetryAfterHolder provides retry time`() {
            val error = object : Exception("rate limited"), RetryAfterHolder {
                override val retryAfter = 45
            }
            val result = normalize(error)
            assertThat(result).isInstanceOf(NormalizedError.RateLimit::class.java)
            assertThat(result.retryAfter).isEqualTo(45)
        }

        @Test
        fun `wait time format - seconds`() {
            val result = ErrorNormalizer.normalize(
                HttpStatusException(429, "Retry after 30 seconds")
            ) as NormalizedError.RateLimit
            assertThat(result.retryAfter).isEqualTo(30)
            assertThat(result.technicalMessage).contains("30 seconds")
        }

        @Test
        fun `wait time format - minutes`() {
            val result = ErrorNormalizer.normalize(
                HttpStatusException(429, "Retry after 120 seconds")
            ) as NormalizedError.RateLimit
            assertThat(result.technicalMessage).contains("120 seconds")
            assertThat(result.retryAfter).isEqualTo(120)
        }

        @Test
        fun `wait time format - hours`() {
            val result = ErrorNormalizer.normalize(
                HttpStatusException(429, "Retry after 7200 seconds")
            ) as NormalizedError.RateLimit
            assertThat(result.technicalMessage).contains("7200 seconds")
            assertThat(result.retryAfter).isEqualTo(7200)
        }

        @Test
        fun `wait time format - days`() {
            val result = ErrorNormalizer.normalize(
                HttpStatusException(429, "Retry after 172800 seconds")
            ) as NormalizedError.RateLimit
            assertThat(result.technicalMessage).contains("172800 seconds")
            assertThat(result.retryAfter).isEqualTo(172800)
        }
    }

    @Nested
    @DisplayName("Quota errors")
    inner class QuotaErrors {

        @Test
        fun `quota in message`() {
            val result = normalize(Exception("You exceeded your quota"))
            assertThat(result).isInstanceOf(NormalizedError.QuotaExceeded::class.java)
            assertThat(result.retryable).isFalse()
        }

        @Test
        fun `insufficient_quota in message`() {
            val result = normalize(Exception("Error: insufficient_quota"))
            assertThat(result).isInstanceOf(NormalizedError.QuotaExceeded::class.java)
        }

        @Test
        fun `billing in message`() {
            val result = normalize(Exception("Billing limit exceeded"))
            assertThat(result).isInstanceOf(NormalizedError.QuotaExceeded::class.java)
        }

        @Test
        fun `limit exceeded in message`() {
            val result = normalize(Exception("Usage limit exceeded"))
            assertThat(result).isInstanceOf(NormalizedError.QuotaExceeded::class.java)
        }
    }

    @Nested
    @DisplayName("Timeout errors")
    inner class TimeoutErrors {

        @Test
        fun `超时优先于异常链任一层的传输特征`() {
            val failures = listOf(
                SocketTimeoutException("Read timed out"),
                HttpRequestTimeoutException("https://example.test", 123L),
                ConnectTimeoutException("peer unreachable", null),
                IOException("connection timed out"),
            )
            for (failure in failures) {
                val wrapper = SocketException("connection reset").apply { initCause(failure) }
                assertThat(normalize(wrapper).category).isEqualTo(ErrorCategory.TIMEOUT)
            }
            assertThat(normalize(HttpStatusException(408, "Request expired")).category)
                .isEqualTo(ErrorCategory.TIMEOUT)
        }

        @Test
        fun `timeout in message`() {
            val result = normalize(Exception("Request timeout"))
            assertThat(result).isInstanceOf(NormalizedError.Timeout::class.java)
            assertThat(result.retryable).isTrue()
        }

        @Test
        fun `ETIMEDOUT in message`() {
            val result = normalize(Exception("Error: ETIMEDOUT"))
            assertThat(result).isInstanceOf(NormalizedError.Timeout::class.java)
        }

        @Test
        fun `TimeoutError class name`() {
            val result = normalize(TimeoutError("Request took too long"))
            assertThat(result).isInstanceOf(NormalizedError.Timeout::class.java)
        }
    }

    @Nested
    @DisplayName("Invalid request errors (4xx)")
    inner class InvalidRequestErrors {

        @Test
        fun `400 status code`() {
            val result = normalize(HttpStatusException(400, "Bad request"))
            assertThat(result).isInstanceOf(NormalizedError.InvalidRequest::class.java)
            assertThat(result.retryable).isFalse()
        }

        @Test
        fun `422 status code`() {
            val result = normalize(HttpStatusException(422, "Unprocessable entity"))
            assertThat(result).isInstanceOf(NormalizedError.InvalidRequest::class.java)
        }

        @Test
        fun `404 status code is not auth`() {
            val result = normalize(HttpStatusException(404, "Not found"))
            assertThat(result).isInstanceOf(NormalizedError.InvalidRequest::class.java)
        }
    }

    @Nested
    @DisplayName("Server errors (5xx)")
    inner class ServerErrors {

        @Test
        fun `500 status code`() {
            val result = normalize(HttpStatusException(500, "Internal server error"))
            assertThat(result).isInstanceOf(NormalizedError.ServerError::class.java)
            assertThat(result.retryable).isTrue()
        }

        @Test
        fun `502 status code`() {
            val result = normalize(HttpStatusException(502, "Bad gateway"))
            assertThat(result).isInstanceOf(NormalizedError.ServerError::class.java)
        }

        @Test
        fun `503 status code`() {
            val result = normalize(HttpStatusException(503, "Service unavailable"))
            assertThat(result).isInstanceOf(NormalizedError.ServerError::class.java)
        }
    }

    @Nested
    @DisplayName("Unknown errors")
    inner class UnknownErrors {

        @Test
        fun `解析与业务异常不会因字段文本或普通IO异常被判成断流`() {
            val failures = listOf(
                SerializationException("Unexpected JSON token at offset 501: EOF"),
                IllegalStateException("premature state transition"),
                IllegalArgumentException("Invalid connection field"),
                IllegalStateException("local workspace validation timed out"),
                IOException("Cannot read local file"),
                IOException("Invalid EOF marker in local file"),
            )
            for (failure in failures) {
                assertThat(normalize(failure).category).isEqualTo(ErrorCategory.UNKNOWN)
            }
        }

        @Test
        fun `unrecognized error returns Unknown`() {
            val result = normalize(Exception("Something went wrong"))
            assertThat(result).isInstanceOf(NormalizedError.Unknown::class.java)
            assertThat(result.retryable).isTrue()
        }

        @Test
        fun `null message returns Unknown`() {
            val result = normalize(Exception(null as String?))
            assertThat(result).isInstanceOf(NormalizedError.Unknown::class.java)
        }
    }

    @Nested
    @DisplayName("Priority / precedence")
    inner class Precedence {

        @Test
        fun `显式HTTP状态维持既有分类`() {
            val expected = mapOf(
                400 to ErrorCategory.INVALID_REQUEST, 401 to ErrorCategory.AUTH,
                403 to ErrorCategory.AUTH, 408 to ErrorCategory.TIMEOUT,
                429 to ErrorCategory.RATE_LIMIT, 502 to ErrorCategory.SERVER_ERROR,
            )
            for ((status, category) in expected) {
                val typed = HttpStatusException(status, "connection reset").apply { initCause(EOFException()) }
                val withStatus = IOException("HTTP $status: connection reset", EOFException())
                assertThat(normalize(typed).category).isEqualTo(category)
                assertThat(normalize(withStatus).category).isEqualTo(category)
            }
            assertThat(normalize(HttpStatusException(400, "insufficient_quota")).category)
                .isEqualTo(ErrorCategory.QUOTA_EXCEEDED)
            val wrapped = IOException("流读取失败", HttpStatusException(502, """{"error":{"message":"Upstream unavailable"}}"""))
            assertThat(normalize(wrapped).category).isEqualTo(ErrorCategory.SERVER_ERROR)
            assertThat(normalize(wrapped).providerMessage).isEqualTo("Upstream unavailable")

            val rateLimit = HttpStatusException(429, """{"error":{"message":"Wait 23 seconds"}}""")
            val mismatchedStatus = IOException("gateway status 502", rateLimit)
            assertThat(normalize(mismatchedStatus).category).isEqualTo(ErrorCategory.RATE_LIMIT)
            assertThat(normalize(mismatchedStatus).retryAfter).isEqualTo(23)
        }

        @Test
        fun `network takes priority over rate limit text`() {
            val result = normalize(Exception("Network rate limit connection error"))
            assertThat(result).isInstanceOf(NormalizedError.Network::class.java)
        }

        @Test
        fun `auth status 401 takes priority over timeout text`() {
            val result = normalize(HttpStatusException(401, "Timeout waiting for auth"))
            assertThat(result).isInstanceOf(NormalizedError.Auth::class.java)
        }

        @Test
        fun `rate limit 429 takes priority over quota text`() {
            val result = normalize(
                HttpStatusException(429, "quota exceeded rate limit")
            )
            assertThat(result).isInstanceOf(NormalizedError.RateLimit::class.java)
        }
    }

    @Nested
    @DisplayName("Provider-specific error patterns")
    inner class ProviderSpecific {

        @Test
        fun `OpenAI-style error`() {
            val result = normalize(
                HttpStatusException(429, "You exceeded your current quota. Please check your plan and billing details.")
            )
            assertThat(result).isInstanceOf(NormalizedError.RateLimit::class.java)
        }

        @Test
        fun `DeepSeek-style rate limit`() {
            val result = normalize(
                HttpStatusException(429, "Rate limit reached. Retry after 60 seconds.")
            )
            assertThat(result).isInstanceOf(NormalizedError.RateLimit::class.java)
            assertThat(result.retryAfter).isEqualTo(60)
        }

        @Test
        fun `Anthropic-style auth error`() {
            val result = normalize(
                HttpStatusException(401, "invalid x-api-key")
            )
            assertThat(result).isInstanceOf(NormalizedError.Auth::class.java)
        }

        @Test
        fun `VertexAI-style server error`() {
            val result = normalize(
                HttpStatusException(500, "Internal server error in Vertex AI")
            )
            assertThat(result).isInstanceOf(NormalizedError.ServerError::class.java)
        }

        @Test
        fun `MiniMax-style 429 with wait seconds`() {
            val result = normalize(
                HttpStatusException(429, "Please wait 43877 seconds before retrying")
            )
            assertThat(result).isInstanceOf(NormalizedError.RateLimit::class.java)
            assertThat(result.retryAfter).isEqualTo(43877)
        }
    }

    @Nested
    @DisplayName("NormalizedError sealed class properties")
    inner class SealedClassProperties {

        @Test
        fun `all subtypes have correct category`() {
            val errors: List<NormalizedError> = listOf(
                NormalizedError.Network("net"),
                NormalizedError.Auth("auth"),
                NormalizedError.RateLimit("rate", 30),
                NormalizedError.InvalidRequest("inv"),
                NormalizedError.ServerError("srv"),
                NormalizedError.QuotaExceeded("quota"),
                NormalizedError.Timeout("tim"),
                NormalizedError.Unknown("unk")
            )
            val expected = listOf(
                ErrorCategory.NETWORK,
                ErrorCategory.AUTH,
                ErrorCategory.RATE_LIMIT,
                ErrorCategory.INVALID_REQUEST,
                ErrorCategory.SERVER_ERROR,
                ErrorCategory.QUOTA_EXCEEDED,
                ErrorCategory.TIMEOUT,
                ErrorCategory.UNKNOWN
            )
            assertThat(errors.map { it.category }).isEqualTo(expected)
        }

        @Test
        fun `全部 subtype 映射为稳定 GenerationFailureCode`() {
            val errors: List<NormalizedError> = listOf(
                NormalizedError.Network("net"),
                NormalizedError.Auth("auth"),
                NormalizedError.RateLimit("rate", 30),
                NormalizedError.InvalidRequest("inv"),
                NormalizedError.ServerError("srv"),
                NormalizedError.QuotaExceeded("quota"),
                NormalizedError.Timeout("tim"),
                NormalizedError.Unknown("unk"),
            )

            assertThat(errors.map { it.toFailure().code }).containsExactly(
                GenerationFailureCode.NETWORK,
                GenerationFailureCode.AUTH,
                GenerationFailureCode.RATE_LIMIT,
                GenerationFailureCode.INVALID_REQUEST,
                GenerationFailureCode.SERVER,
                GenerationFailureCode.QUOTA,
                GenerationFailureCode.TIMEOUT,
                GenerationFailureCode.UNKNOWN,
            ).inOrder()
            assertThat(errors[2].toFailure().retryAfterSeconds).isEqualTo(30)
        }

        @Test
        fun `retryable flags are correct`() {
            assertThat(NormalizedError.Network("").retryable).isTrue()
            assertThat(NormalizedError.Auth("").retryable).isFalse()
            assertThat(NormalizedError.RateLimit("", null).retryable).isTrue()
            assertThat(NormalizedError.InvalidRequest("").retryable).isFalse()
            assertThat(NormalizedError.ServerError("").retryable).isTrue()
            assertThat(NormalizedError.QuotaExceeded("").retryable).isFalse()
            assertThat(NormalizedError.Timeout("").retryable).isTrue()
            assertThat(NormalizedError.Unknown("").retryable).isTrue()
        }

        @Test
        fun `RateLimit preserves retryAfter`() {
            val err = NormalizedError.RateLimit("msg", 120)
            assertThat(err.retryAfter).isEqualTo(120)
        }

        @Test
        fun `RateLimit null retryAfter`() {
            val err = NormalizedError.RateLimit("msg", null)
            assertThat(err.retryAfter).isNull()
        }
    }
}

private class NetworkError(message: String) : Exception(message)
private class TimeoutError(message: String) : Exception(message)
