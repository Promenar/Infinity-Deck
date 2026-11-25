package com.promenar.infinity.data.remote

import com.promenar.infinity.data.remote.lifecycle.ToolCallLifecycleHandler
import com.promenar.infinity.data.remote.middleware.LlmMiddleware
import com.promenar.infinity.data.remote.middleware.LlmMiddlewareChain
import com.promenar.infinity.data.remote.middleware.StreamTextParams
import com.promenar.infinity.data.remote.parser.ErrorNormalizer
import com.promenar.infinity.data.remote.parser.HttpStatusException
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.LlmProtocol
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.ProtocolToolCall
import com.promenar.infinity.data.remote.protocol.StreamChunk
import com.promenar.infinity.data.remote.protocol.ProtocolFactory
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.protocol.toStreamChunkError
import com.promenar.infinity.data.remote.protocol.toProxyStreamError
import com.promenar.infinity.data.proxy.BuiltInProxyUnavailableException
import com.promenar.infinity.data.remote.protocol.VERTEX_DEFAULT_LOCATION
import com.promenar.infinity.domain.generation.GenerationFailureCode
import com.promenar.infinity.domain.generation.GenerationFailedException
import com.promenar.infinity.startup.diagnostics.AppDiagnostics
import com.promenar.infinity.startup.diagnostics.DiagnosticRedactor
import com.promenar.infinity.utils.InfinityLogger
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

data class UnifiedProviderConfig(
    val protocolType: ProtocolType,
    val baseUrl: String,
    val apiKey: String,
    val defaultModel: String,
    val serviceAccountJson: String = "",
    val projectId: String = "",
    val location: String = VERTEX_DEFAULT_LOCATION,
    val sessionHeaderName: String? = null,
    val useBuiltInProxy: Boolean = false,
    val providerId: String? = null,
) {
    override fun toString(): String =
        "UnifiedProviderConfig(protocolType=$protocolType, " +
            "hasApiKey=${apiKey.isNotEmpty()}, hasServiceAccount=${serviceAccountJson.isNotEmpty()}, " +
            "hasProjectId=${projectId.isNotEmpty()})"
}

data class StreamConfig(
    val tools: Map<String, ProtocolTool> = emptyMap(),
    val maxToolCalls: Int = 10,
    val enableWebSearch: Boolean = false,
    val enableUrlContext: Boolean = false,
    val webSearchProviderId: String? = null,
    val knowledgeBaseIds: List<String> = emptyList(),
    val enableMemory: Boolean = false,
    val assistantId: String = "",
    val topicId: String = "",
    val sessionId: String? = null,
    internal val latencyTrace: ModelLatencyTrace? = null,
)

internal class ProviderConfigurationUnavailableException :
    IllegalStateException("Provider configuration is unavailable")

class UnifiedLlmClient private constructor(
    private val providerConfigResolver: () -> UnifiedProviderConfig?,
    private val middlewares: List<LlmMiddleware>,
    private val protocolFactory: (UnifiedProviderConfig) -> LlmProtocol,
    private val sessionIdentifier: suspend (String) -> String?,
    private val providerId: String?,
    private val routeModelId: String?,
) {
    constructor(
        providerConfigResolver: () -> UnifiedProviderConfig?,
        middlewares: List<LlmMiddleware> = emptyList(),
        protocolFactory: (UnifiedProviderConfig) -> LlmProtocol = { config ->
            ProtocolFactory.create(
                type = config.protocolType,
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                model = config.defaultModel,
                serviceAccountJson = config.serviceAccountJson,
                projectId = config.projectId,
                location = config.location,
                useBuiltInProxy = config.useBuiltInProxy,
                providerId = config.providerId,
            )
        },
        sessionIdentifier: suspend (String) -> String? = { null },
    ) : this(providerConfigResolver, middlewares, protocolFactory, sessionIdentifier, null, null)

    internal fun withDiagnosticRoute(providerId: String, modelId: String? = null): UnifiedLlmClient =
        UnifiedLlmClient(providerConfigResolver, middlewares, protocolFactory, sessionIdentifier, providerId, modelId)

    suspend fun sendStream(
        params: StreamTextParams,
        config: StreamConfig
    ): Flow<StreamChunk> = channelFlow {
        val diagnostic = LlmRequestDiagnostic(config.sessionId, providerId, routeModelId ?: params.model,
            attempt = if (config.latencyTrace == null) 1 else null)
        diagnostic.record {
            if (config.latencyTrace == null) collectStream(params, config, diagnostic)
            else AppDiagnostics.captureGenerationAttempt(config.sessionId, { diagnostic.attempt = it }) {
                collectStream(params, config, diagnostic)
            }
        }
    }

    private suspend fun ProducerScope<StreamChunk>.collectStream(
        params: StreamTextParams,
        config: StreamConfig,
        diagnostic: LlmRequestDiagnostic,
    ) {
        val providerConfig = providerConfigResolver()
            ?: throw ProviderConfigurationUnavailableException()
        val protocol = protocolFactory(providerConfig)

        val chain = LlmMiddlewareChain(middlewares)

        chain.onRequestStart(params)
        val finalParams = chain.transformParams(params)

        val allTools = (config.tools + (finalParams.tools ?: emptyMap())).values.toList()

        val request = PromptRequest(
            messages = finalParams.messages,
            model = finalParams.model,
            temperature = finalParams.temperature,
            topP = finalParams.topP,
            maxTokens = finalParams.maxOutputTokens,
            frequencyPenalty = finalParams.frequencyPenalty,
            presencePenalty = finalParams.presencePenalty,
            topK = finalParams.topK,
            repetitionPenalty = finalParams.repetitionPenalty,
            reasoningEffort = finalParams.reasoningEffort,
            tools = allTools.ifEmpty { null },
            toolChoice = finalParams.toolChoice,
            stream = true,
            webSearch = finalParams.enableWebSearch || config.enableWebSearch,
            streamTimeout = finalParams.streamTimeout,
            sessionHeaders = resolveSessionRequestHeaders(providerConfig.sessionHeaderName, config.sessionId, sessionIdentifier),
            latencyTrace = config.latencyTrace,
        )
        diagnostic.modelId = routeModelId?.takeIf { finalParams.model == providerConfig.defaultModel } ?: finalParams.model
        diagnostic.route(providerConfig.protocolType, request.sessionHeaders?.name)

        val lifecycleHandler = ToolCallLifecycleHandler(
            onChunk = { send(it) },
            knownTools = config.tools
        )

        suspend fun emitTransformed(rawChunk: StreamChunk) {
            diagnostic.observeFirstToken(rawChunk)
            chain.transformStreamChunk(rawChunk) { transformed ->
                when (transformed) {
                    is StreamChunk.ToolCallDelta -> {
                        lifecycleHandler.handleToolInputDelta(
                            transformed.id,
                            transformed.arguments,
                        )
                        send(transformed)
                    }
                    else -> send(transformed)
                }
            }
            diagnostic.observeTerminal(rawChunk)
        }

        var pendingCompletion: StreamChunk.Completed? = null
        var terminalErrorSeen = false
        var contractViolation = false
        val rawToolCalls = linkedMapOf<String, ProtocolToolCall>()
        try {
            val rawFlow = protocol.sendPrompt(request)
            rawFlow.collect { rawChunk ->
                when (rawChunk) {
                    is StreamChunk.Completed -> {
                        when {
                            terminalErrorSeen -> Unit
                            pendingCompletion != null -> contractViolation = true
                            else -> {
                                val structurallyValidated =
                                    com.promenar.infinity.data.remote.protocol.validatedCompletion(
                                        rawChunk.reason,
                                        rawToolCalls.values.toList(),
                                    )
                                if (
                                    structurallyValidated !is StreamChunk.Completed ||
                                    structurallyValidated.completedToolCallIds != rawChunk.completedToolCallIds
                                ) {
                                    contractViolation = true
                                } else {
                                    pendingCompletion = rawChunk
                                }
                            }
                        }
                    }
                    is StreamChunk.Error -> {
                        if (!terminalErrorSeen) {
                            pendingCompletion = null
                            emitTransformed(rawChunk)
                            terminalErrorSeen = true
                        }
                    }
                    else -> when {
                        terminalErrorSeen -> Unit
                        pendingCompletion != null -> contractViolation = true
                        else -> {
                            if (rawChunk is StreamChunk.ToolCallDelta) {
                                val previous = rawToolCalls[rawChunk.id]
                                rawToolCalls[rawChunk.id] = ProtocolToolCall(
                                    id = rawChunk.id,
                                    name = rawChunk.name.ifBlank { previous?.name.orEmpty() },
                                    arguments = previous?.arguments.orEmpty() + rawChunk.arguments,
                                )
                            }
                            emitTransformed(rawChunk)
                        }
                    }
                }
            }
            if (!terminalErrorSeen) {
                when {
                    contractViolation -> emitTransformed(com.promenar.infinity.data.remote.protocol.streamContractError(
                        "Provider stream emitted duplicate terminal or data after terminal",
                    ))
                    pendingCompletion != null -> emitTransformed(requireNotNull(pendingCompletion))
                    else -> emitTransformed(com.promenar.infinity.data.remote.protocol.truncatedStreamError(
                        "Provider stream ended without an explicit terminal",
                    ))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unavailable: BuiltInProxyUnavailableException) {
            if (!terminalErrorSeen) {
                val error = unavailable.toProxyStreamError()
                send(error)
                diagnostic.observeTerminal(error)
            }
        } catch (e: Exception) {
            InfinityLogger.logError("[UnifiedLlmClient] Stream error", e)
            if (!terminalErrorSeen) {
                val error = ErrorNormalizer.normalize(e).toStreamChunkError(e)
                send(error)
                diagnostic.observeTerminal(error)
            }
        }

        chain.onRequestEnd(finalParams)
        // 不使用 awaitClose {}：底层协议流结束后 channelFlow 自动关闭。
        // awaitClose {} 会导致 Flow 永不完成，使 ChatViewModel 的 collect {} 永远挂起，
        // isGenerating 卡在 true 无法恢复。
    }
}

/** 每次实际调用独立计时，并在上层生成循环提供序号时沿用该序号。 */
internal class LlmRequestDiagnostic(
    private val sessionId: String?,
    private val providerId: String?,
    var modelId: String?,
    var attempt: Int? = 1,
) {
    private val startedAt = System.nanoTime()
    private var firstTokenMs: Long? = null
    private var finishReason: String? = null
    private var failureCode: String? = null
    private var retryAfterSec: Int? = null
    private var httpStatus: Int? = null

    fun route(protocol: ProtocolType, sessionHeaderName: String?) {
        InfinityLogger.diagnostic("llm.route", mapOf(
            "sessionId" to sessionId,
            "providerId" to providerId,
            "protocol" to protocol.persistedId,
            "sessionHeaderInjected" to (sessionHeaderName != null),
            "sessionHeaderName" to sessionHeaderName,
        ))
    }

    fun observeFirstToken(chunk: StreamChunk) {
        if (firstTokenMs != null) return
        val hasDelta = when (chunk) {
            is StreamChunk.TextDelta -> chunk.content.isNotEmpty() || !chunk.reasoning.isNullOrEmpty()
            is StreamChunk.Thinking -> chunk.content.isNotEmpty()
            is StreamChunk.ToolCallDelta -> chunk.name.isNotEmpty() || chunk.arguments.isNotEmpty()
            else -> false
        }
        if (hasDelta) firstTokenMs = elapsedMs()
    }

    fun observeTerminal(chunk: StreamChunk) {
        if (failureCode != null) return
        when (chunk) {
            is StreamChunk.Completed -> finishReason = chunk.reason.name
            is StreamChunk.Error -> {
                finishReason = null
                failureCode = chunk.code.name
                retryAfterSec = chunk.retryAfterSeconds
                httpStatus = typedHttpStatus(chunk.cause)
            }
            else -> Unit
        }
    }

    fun observeStreamEnd() {
        if (finishReason == null && failureCode == null) failureCode = GenerationFailureCode.NETWORK.name
    }

    suspend fun <T> record(block: suspend () -> T): T {
        try {
            return block()
        } catch (cancelled: CancellationException) {
            // 收集者可以在收到协议终态后主动结束收集，保留已经观测到的结果。
            if (finishReason == null && failureCode == null) failureCode = "CANCELLED"
            throw cancelled
        } catch (error: Exception) {
            finishReason = null
            val failure = (error as? GenerationFailedException)?.failure
                ?: runCatching { ErrorNormalizer.normalize(error).toFailure(error) }.getOrNull()
            failureCode = failure?.code?.name ?: GenerationFailureCode.UNKNOWN.name
            retryAfterSec = failure?.retryAfterSeconds
            httpStatus = typedHttpStatus(error) ?: typedHttpStatus(failure?.cause)
            throw error
        } finally {
            InfinityLogger.diagnostic(if (failureCode == null) "llm.complete" else "llm.error", mapOf(
                "sessionId" to sessionId,
                "providerId" to providerId,
                "modelId" to modelId?.let(DiagnosticRedactor::safeLabel),
                "httpStatus" to httpStatus,
                "firstTokenMs" to firstTokenMs,
                "elapsedMs" to elapsedMs(),
                "finishReason" to finishReason,
                "failureCode" to failureCode,
                "retryAfterSec" to retryAfterSec,
                "attempt" to attempt,
            ))
        }
    }

    private fun elapsedMs(): Long = (System.nanoTime() - startedAt) / 1_000_000

    private fun typedHttpStatus(error: Throwable?): Int? = when (error) {
        is HttpStatusException -> error.statusCode
        is ResponseException -> error.response.status.value
        else -> null
    }

}
