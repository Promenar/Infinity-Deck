package com.promenar.infinity.data.remote

import com.promenar.infinity.data.remote.lifecycle.ToolCallLifecycleHandler
import com.promenar.infinity.data.remote.middleware.LlmMiddleware
import com.promenar.infinity.data.remote.middleware.LlmMiddlewareChain
import com.promenar.infinity.data.remote.middleware.StreamTextParams
import com.promenar.infinity.data.remote.parser.ErrorNormalizer
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.LlmProtocol
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.ProtocolToolCall
import com.promenar.infinity.data.remote.protocol.StreamChunk
import com.promenar.infinity.data.remote.protocol.ProtocolFactory
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.protocol.toStreamChunkError
import com.promenar.infinity.data.remote.protocol.VERTEX_DEFAULT_LOCATION
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.CancellationException
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

class UnifiedLlmClient(
    private val providerConfigResolver: () -> UnifiedProviderConfig?,
    private val middlewares: List<LlmMiddleware> = emptyList(),
    private val protocolFactory: (UnifiedProviderConfig) -> LlmProtocol = { config ->
        ProtocolFactory.create(
            type = config.protocolType,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            model = config.defaultModel,
            serviceAccountJson = config.serviceAccountJson,
            projectId = config.projectId,
            location = config.location,
        )
    },
    private val sessionIdentifier: suspend (String) -> String? = { null },
) {
    suspend fun sendStream(
        params: StreamTextParams,
        config: StreamConfig
    ): Flow<StreamChunk> = channelFlow {
        val providerConfig = providerConfigResolver()
            ?: throw IllegalStateException("Provider configuration is unavailable")
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

        val lifecycleHandler = ToolCallLifecycleHandler(
            onChunk = { send(it) },
            knownTools = config.tools
        )

        suspend fun emitTransformed(rawChunk: StreamChunk) {
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
        } catch (e: Exception) {
            InfinityLogger.logError("[UnifiedLlmClient] Stream error", e)
            if (!terminalErrorSeen) {
                send(ErrorNormalizer.normalize(e).toStreamChunkError(e))
            }
        }

        chain.onRequestEnd(finalParams)
        // 不使用 awaitClose {}：底层协议流结束后 channelFlow 自动关闭。
        // awaitClose {} 会导致 Flow 永不完成，使 ChatViewModel 的 collect {} 永远挂起，
        // isGenerating 卡在 true 无法恢复。
    }
}
