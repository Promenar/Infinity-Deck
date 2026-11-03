package com.promenar.infinity.data.remote.protocol

import com.promenar.infinity.domain.generation.GenerationFailureCode

/**
 * 依据协议提供的稳定 type/code 与 HTTP 状态分类错误。
 *
 * 自然语言 message 只保留在 [StreamChunk.Error.technical] 供内部诊断，绝不参与分类或展示。
 */
internal object ProtocolErrorClassifier {
    private val quotaIdentifiers = setOf(
        "billing_error",
        "billing_hard_limit_reached",
        "insufficient_quota",
        "quota_exceeded",
    )
    private val authIdentifiers = setOf(
        "authentication_error",
        "invalid_api_key",
        "invalid_authentication",
        "permission_error",
    )
    private val rateLimitIdentifiers = setOf(
        "rate_limit_error",
        "rate_limit_exceeded",
        "too_many_requests",
    )
    private val invalidRequestIdentifiers = setOf(
        "bad_parameter",
        "invalid_request",
        "invalid_request_error",
        "model_not_found",
    )
    private val contextLimitIdentifiers = setOf(
        // OpenAI Responses 与 llama.cpp 的官方稳定错误码/类型。
        // https://github.com/openai/codex/blob/main/codex-rs/codex-api/src/sse/responses_error.rs
        // https://github.com/ggml-org/llama.cpp/blob/master/tools/server/server-common.cpp
        "context_length_exceeded",
        "exceed_context_size_error",
    )
    private val serverIdentifiers = setOf(
        "api_error",
        "internal_server_error",
        "overloaded_error",
        "server_error",
        "service_unavailable",
    )

    fun classify(
        statusCode: Int? = null,
        type: String? = null,
        code: String? = null,
        retryAfterHeader: String? = null,
        technical: String? = null,
    ): StreamChunk.Error {
        val identifiers = listOfNotNull(code.normalizedIdentifier(), type.normalizedIdentifier()).toSet()
        val failureCode = when {
            identifiers.any(quotaIdentifiers::contains) -> GenerationFailureCode.QUOTA
            identifiers.any(authIdentifiers::contains) -> GenerationFailureCode.AUTH
            identifiers.any(rateLimitIdentifiers::contains) -> GenerationFailureCode.RATE_LIMIT
            identifiers.any(contextLimitIdentifiers::contains) -> GenerationFailureCode.CONTEXT_LIMIT
            identifiers.any(invalidRequestIdentifiers::contains) -> GenerationFailureCode.INVALID_REQUEST
            identifiers.any(serverIdentifiers::contains) -> GenerationFailureCode.SERVER
            statusCode == 401 || statusCode == 403 -> GenerationFailureCode.AUTH
            statusCode == 429 -> GenerationFailureCode.RATE_LIMIT
            statusCode != null && statusCode in 400..499 -> GenerationFailureCode.INVALID_REQUEST
            statusCode != null && statusCode >= 500 -> GenerationFailureCode.SERVER
            else -> GenerationFailureCode.UNKNOWN
        }
        return StreamChunk.Error(
            code = failureCode,
            retryable = failureCode.isRetryable(),
            retryAfterSeconds = if (failureCode == GenerationFailureCode.RATE_LIMIT) {
                retryAfterHeader.parseRetryAfterSeconds()
            } else {
                null
            },
            technical = technical,
        )
    }

    private fun String?.normalizedIdentifier(): String? = this
        ?.trim()
        ?.lowercase()
        ?.takeIf(String::isNotEmpty)

    private fun String?.parseRetryAfterSeconds(): Int? {
        val seconds = this?.trim()?.toLongOrNull() ?: return null
        return seconds.takeIf { it > 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
    }

    private fun GenerationFailureCode.isRetryable(): Boolean = when (this) {
        GenerationFailureCode.RATE_LIMIT,
        GenerationFailureCode.SERVER,
        GenerationFailureCode.UNKNOWN,
        -> true

        else -> false
    }
}
