package com.promenar.infinity.data.remote.protocol

import com.promenar.infinity.data.local.inference.GenerateConfig
import kotlinx.serialization.json.*

object ProtocolParamAdapter {

    fun mapCommonParams(body: JsonObjectBuilder, request: PromptRequest) {
        request.temperature?.let { body.put("temperature", it) }
        request.topP?.let { if (it < 1.0) body.put("top_p", it) }
        request.maxTokens?.let { body.put("max_tokens", it) }
    }

    fun mapPenaltyParams(body: JsonObjectBuilder, request: PromptRequest, protocolType: ProtocolType) {
        when (protocolType) {
            is ProtocolType.Anthropic_Messages -> {
            }
            is ProtocolType.Local -> {
                request.repetitionPenalty?.let { body.put("repetition_penalty", it) }
            }
            else -> {
                request.frequencyPenalty?.let { if (it != 0.0) body.put("frequency_penalty", it) }
                request.presencePenalty?.let { if (it != 0.0) body.put("presence_penalty", it) }
                request.repetitionPenalty?.let { body.put("repetition_penalty", it) }
            }
        }
    }

    fun mapSamplingParams(body: JsonObjectBuilder, request: PromptRequest) {
        request.topK?.let { body.put("top_k", it) }
    }

    fun buildGenerateConfig(request: PromptRequest): GenerateConfig {
        return clampGenerateConfig(GenerateConfig(
            maxTokens = request.maxTokens ?: 1536,
            temperature = (request.temperature ?: 0.7).toFloat(),
            topP = (request.topP ?: 0.9).toFloat(),
            topK = request.topK ?: 40,
            repeatPenalty = (request.repetitionPenalty ?: 1.0).toFloat()
        ))
    }

    fun clampGenerateConfig(config: GenerateConfig): GenerateConfig {
        return config.copy(
            temperature = config.temperature.coerceIn(0.0f, 2.0f),
            topK = if (config.topK <= 0) 1 else config.topK,
            repeatPenalty = config.repeatPenalty.coerceIn(1.0f, 1.5f)
        )
    }
}
