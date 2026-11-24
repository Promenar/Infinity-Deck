package com.promenar.infinity.data.remote.protocol

import com.promenar.infinity.data.local.inference.LocalInferenceEngine

/**
 * Factory for creating LlmProtocol instances based on ProtocolType.
 */
object ProtocolFactory {
    fun createPersisted(
        persistedType: String,
        baseUrl: String = "",
        apiKey: String = "",
        model: String = "",
        serviceAccountJson: String = "",
        projectId: String = "",
        location: String = VERTEX_DEFAULT_LOCATION,
        localEngine: LocalInferenceEngine? = null,
        useBuiltInProxy: Boolean = false,
        providerId: String? = null,
    ): LlmProtocol = create(
        type = ProtocolType.fromLegacyName(persistedType),
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        serviceAccountJson = serviceAccountJson,
        projectId = projectId,
        location = location,
        localEngine = localEngine,
        useBuiltInProxy = useBuiltInProxy,
        providerId = providerId,
    )

    fun create(
        type: ProtocolType,
        baseUrl: String = "",
        apiKey: String = "",
        model: String = "",
        serviceAccountJson: String = "",
        projectId: String = "",
        location: String = VERTEX_DEFAULT_LOCATION,
        localEngine: LocalInferenceEngine? = null,
        useBuiltInProxy: Boolean = false,
        providerId: String? = null,
    ): LlmProtocol {
        return when (type) {
            ProtocolType.OpenAI_ChatCompletions -> OpenAIProtocol(
                baseUrl, apiKey, model, useBuiltInProxy = useBuiltInProxy, providerId = providerId,
            )
            ProtocolType.OpenAI_Responses -> OpenAIResponsesProtocol(
                baseUrl, apiKey, model, useBuiltInProxy = useBuiltInProxy, providerId = providerId,
            )
            
            ProtocolType.Anthropic_Messages -> AnthropicProtocol(
                baseUrl, apiKey, model, useBuiltInProxy = useBuiltInProxy, providerId = providerId,
            )
            
            ProtocolType.Google_VertexAI -> VertexAIProtocol(
                baseUrl = baseUrl,
                serviceAccountJson = serviceAccountJson,
                projectId = projectId,
                location = location,
                model = model,
                useBuiltInProxy = useBuiltInProxy,
                providerId = providerId,
            )
            
            ProtocolType.Google_Gemini -> GeminiProtocol(
                baseUrl, apiKey, model, useBuiltInProxy = useBuiltInProxy, providerId = providerId,
            )

            ProtocolType.Cohere_Chat,
            ProtocolType.Yi_ZeroOne,
            -> throw UnsupportedProviderProtocolException(type)

            ProtocolType.Mistral_Chat,
            ProtocolType.Generic_OpenAI_Compat,
            ProtocolType.Moonshot_Kimi,
            ProtocolType.Qwen_DashScope,
            ProtocolType.Zhipu_GLM,
            ProtocolType.Doubao_ByteDance,
            ProtocolType.Baichuan,
            ProtocolType.DeepSeek,
            -> GenericOpenAICompatProtocol(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                protocolType = type,
                useBuiltInProxy = useBuiltInProxy,
                providerId = providerId,
            )
            
            ProtocolType.Local -> {
                if (localEngine == null) {
                    throw IllegalArgumentException("LocalInferenceEngine is required for Local protocol")
                }
                LocalProtocol(localEngine, model)
            }
        }
    }
}
