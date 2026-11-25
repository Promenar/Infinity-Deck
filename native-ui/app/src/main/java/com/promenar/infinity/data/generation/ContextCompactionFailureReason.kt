package com.promenar.infinity.data.generation

import com.promenar.infinity.data.remote.ProviderConfigurationUnavailableException
import com.promenar.infinity.data.remote.ProviderResolutionError
import com.promenar.infinity.data.remote.parser.ErrorNormalizer
import com.promenar.infinity.data.remote.protocol.AbnormalFinishException
import com.promenar.infinity.data.remote.protocol.UnsupportedProviderProtocolException
import com.promenar.infinity.domain.generation.GenerationFailureCode
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class ContextCompactionFailureReason {
    GENERATION_ACTIVE,
    ROUTE_UNAVAILABLE,
    OUTPUT_LIMIT,
    CONTEXT_LIMIT,
    TIMEOUT,
    NETWORK,
    RATE_LIMITED,
    AUTH,
    BUDGET_EXHAUSTED,
    EMPTY_OUTPUT,
    PROVIDER_ERROR,
    UNKNOWN,
}

sealed interface ContextCompactionOutcome<out T> {
    data class Success<T>(val value: T) : ContextCompactionOutcome<T>
    data class Failure(val reason: ContextCompactionFailureReason) : ContextCompactionOutcome<Nothing>

    fun getOrNull(): T? = (this as? Success)?.value
}

/** 路由与凭据拒绝时，只采纳提供商实报用量。 */
internal fun ContextCompactionFailureReason.canEstimateUsage(): Boolean =
    this != ContextCompactionFailureReason.ROUTE_UNAVAILABLE && this != ContextCompactionFailureReason.AUTH

internal fun ProviderResolutionError.toCompactionFailure(): ContextCompactionFailureReason = when (this) {
    ProviderResolutionError.API_KEY_MISSING,
    ProviderResolutionError.VERTEX_CREDENTIAL_INVALID -> ContextCompactionFailureReason.AUTH
    else -> ContextCompactionFailureReason.ROUTE_UNAVAILABLE
}

internal fun GenerationFailureCode.toCompactionFailure(): ContextCompactionFailureReason = when (this) {
    GenerationFailureCode.NETWORK -> ContextCompactionFailureReason.NETWORK
    GenerationFailureCode.AUTH -> ContextCompactionFailureReason.AUTH
    GenerationFailureCode.RATE_LIMIT -> ContextCompactionFailureReason.RATE_LIMITED
    GenerationFailureCode.TIMEOUT -> ContextCompactionFailureReason.TIMEOUT
    GenerationFailureCode.CONTEXT_LIMIT -> ContextCompactionFailureReason.CONTEXT_LIMIT
    GenerationFailureCode.OUTPUT_LIMIT -> ContextCompactionFailureReason.OUTPUT_LIMIT
    GenerationFailureCode.BUSY -> ContextCompactionFailureReason.GENERATION_ACTIVE
    GenerationFailureCode.TOOL_BUDGET_EXHAUSTED -> ContextCompactionFailureReason.BUDGET_EXHAUSTED
    GenerationFailureCode.EMPTY_REPLY -> ContextCompactionFailureReason.EMPTY_OUTPUT
    GenerationFailureCode.TAILNET_UNAVAILABLE, GenerationFailureCode.LINUX_UNAVAILABLE ->
        ContextCompactionFailureReason.ROUTE_UNAVAILABLE
    GenerationFailureCode.INVALID_REQUEST, GenerationFailureCode.CONTENT_FILTERED,
    GenerationFailureCode.SERVER, GenerationFailureCode.QUOTA -> ContextCompactionFailureReason.PROVIDER_ERROR
    GenerationFailureCode.UNKNOWN, GenerationFailureCode.PERSISTENCE, GenerationFailureCode.RUN_MODE_REQUIRED ->
        ContextCompactionFailureReason.UNKNOWN
}

internal fun Exception.toCompactionFailure(): ContextCompactionFailureReason = when (this) {
    is AbnormalFinishException -> error.code.toCompactionFailure()
    is SocketTimeoutException -> ContextCompactionFailureReason.TIMEOUT
    is SocketException, is UnknownHostException -> ContextCompactionFailureReason.NETWORK
    is ProviderConfigurationUnavailableException, is UnsupportedProviderProtocolException ->
        ContextCompactionFailureReason.ROUTE_UNAVAILABLE
    else -> ErrorNormalizer.normalize(this).toFailure(this).code.toCompactionFailure()
}
