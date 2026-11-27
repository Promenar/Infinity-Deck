package com.promenar.infinity.data.remote.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import com.promenar.infinity.data.model.catalog.ModelCapability
import com.promenar.infinity.data.model.catalog.ModelMetadataOverride
import com.promenar.infinity.data.model.catalog.ModelWorkload
import com.promenar.infinity.data.model.catalog.SupportState

/** Generic OpenAI-compatible `/models` 的唯一 envelope 解析合同。 */
internal object GenericModelsEnvelopeParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<String>? = parseDescriptors(body)?.map { it.id }

    fun parseDescriptors(body: String): List<RemoteModelDescriptor>? {
        return try {
            val root = json.parseToJsonElement(body)
            val envelope = when (root) {
                is JsonArray -> ModelEnvelope(root, isGemini = false)
                is JsonObject -> when {
                    root["data"] is JsonArray && root["models"] == null ->
                        ModelEnvelope(root.getValue("data") as JsonArray, isGemini = false)
                    root["models"] is JsonArray && root["data"] == null ->
                        ModelEnvelope(root.getValue("models") as JsonArray, isGemini = true)
                    else -> return null
                }
                else -> return null
            }
            val models = envelope.models
            if (models.size > MAX_REMOTE_MODEL_COUNT) return null
            val descriptors = models.map { element ->
                val model = element as? JsonObject ?: return null
                val id = model.requiredString(if (envelope.isGemini) "name" else "id") ?: return null
                RemoteModelDescriptor(
                    id = id,
                    ownedBy = model.optionalString("owned_by") ?: model.optionalString("ownedBy"),
                    sourceProviderId = model.optionalString("source_provider_id")
                        ?: model.optionalString("sourceProviderId"),
                    metadata = parseMetadata(model, envelope.isGemini) ?: return null,
                )
            }
            val unique = linkedMapOf<String, RemoteModelDescriptor>()
            descriptors.forEach { descriptor ->
                val previous = unique[descriptor.id]
                if (previous != null && previous != descriptor) return null
                unique.putIfAbsent(descriptor.id, descriptor)
            }
            unique.values.toList()
        } catch (_: Exception) {
            null
        }
    }

    private fun parseMetadata(model: JsonObject, isGemini: Boolean): ModelMetadataOverride? {
        val capabilities = linkedMapOf<ModelCapability, SupportState>()
        val explicitCapabilities = model["capabilities"]
        when (explicitCapabilities) {
            null, JsonNull -> Unit
            is JsonObject -> explicitCapabilities.forEach { (key, value) ->
                val capability = capabilityFor(key) ?: return@forEach
                val supported = when (value) {
                    is JsonPrimitive -> value.takeUnless { it.isString }?.booleanOrNull
                    is JsonObject -> value.requiredBoolean("supported")
                    else -> null
                } ?: return null
                capabilities[capability] = supported.toState()
            }
            is JsonArray -> explicitCapabilities.forEach { value ->
                val name = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
                capabilityFor(name)?.let { capabilities[it] = SupportState.SUPPORTED }
            }
            else -> return null
        }
        model.optionalReasoning()?.let { capabilities[ModelCapability.REASONING] = it.toState() }
        mapOf(
            "tool_call" to ModelCapability.TOOL_CALLING,
            "tool_calling" to ModelCapability.TOOL_CALLING,
            "structured_output" to ModelCapability.STRUCTURED_OUTPUT,
            "structured_outputs" to ModelCapability.STRUCTURED_OUTPUT,
            "vision" to ModelCapability.VISION_INPUT,
            "image_input" to ModelCapability.VISION_INPUT,
            "thinking" to ModelCapability.REASONING,
        ).forEach { (field, capability) ->
            model.optionalBoolean(field)?.let { capabilities[capability] = it.toState() }
        }
        val generationMethods = if (isGemini) model.optionalStringArray("supportedGenerationMethods") else null
        val declaredWorkload = model.optionalString("workload")?.let(::workloadFor)
        val isGeminiBidiOnly = isGemini && generationMethods != null && generationMethods.isNotEmpty() &&
            "bidiGenerateContent" in generationMethods &&
            "generateContent" !in generationMethods &&
            "streamGenerateContent" !in generationMethods
        if (isGeminiBidiOnly) {
            capabilities[ModelCapability.CHAT_ENDPOINT] = SupportState.UNSUPPORTED
        }
        val workload = declaredWorkload ?: when {
            isGemini && generationMethods != null && generationMethods.isNotEmpty() &&
                generationMethods.all { it == "embedContent" || it == "batchEmbedContents" } -> ModelWorkload.EMBEDDING
            isGeminiBidiOnly -> ModelWorkload.REALTIME
            else -> null
        }
        return ModelMetadataOverride(
            displayName = model.optionalString("display_name")
                ?: model.optionalString("displayName")
                ?: if (isGemini) null else model.optionalString("name"),
            workload = workload,
            capabilities = capabilities,
            contextTokens = model.optionalTokenLimit("context_tokens")
                ?: model.optionalTokenLimit("context_length"),
            inputTokens = model.optionalTokenLimit("input_tokens")
                ?: model.optionalTokenLimit("max_input_tokens")
                ?: model.optionalTokenLimit("inputTokenLimit"),
            outputTokens = model.optionalTokenLimit("output_tokens")
                ?: model.optionalTokenLimit("max_output_tokens")
                ?: model.optionalTokenLimit("max_tokens")
                ?: model.optionalTokenLimit("outputTokenLimit"),
        )
    }

    private fun JsonObject.requiredString(name: String): String? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        if (!primitive.isString || primitive.content.isBlank()) return null
        return primitive.content
    }

    private fun JsonObject.requiredBoolean(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

    private fun JsonObject.optionalString(name: String): String? {
        val element = this[name] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: throw IllegalArgumentException()
        if (!primitive.isString) throw IllegalArgumentException()
        return primitive.content.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException()
    }

    private fun JsonObject.optionalBoolean(name: String): Boolean? {
        val element = this[name] ?: return null
        if (element is JsonNull) return null
        return (element as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: throw IllegalArgumentException()
    }

    private fun JsonObject.optionalReasoning(): Boolean? {
        val reasoning = this["reasoning"] as? JsonObject ?: return optionalBoolean("reasoning")
        var supported: Boolean? = null
        var hasKnownField = false
        for (field in reasoning.keys) {
            when (field) {
                "supported" -> supported = requireNotNull(reasoning.requiredBoolean(field))
                "mandatory", "default_enabled", "supports_max_tokens" -> reasoning.optionalBoolean(field)
                "default_effort" -> reasoning.optionalString(field)
                "supported_efforts" -> reasoning.optionalStringArray(field)?.let { require(it.isNotEmpty()) }
                else -> continue
            }
            hasKnownField = true
        }
        require(hasKnownField)
        return supported
    }

    private fun JsonObject.optionalTokenLimit(name: String): Int? {
        val element = this[name] ?: return null
        if (element is JsonNull) return null
        val value = (element as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?: throw IllegalArgumentException()
        if (value < 0) throw IllegalArgumentException()
        return value.takeIf { it > 0 }
    }

    private fun JsonObject.optionalStringArray(name: String): List<String>? {
        val element = this[name] ?: return null
        if (element is JsonNull) return null
        val array = element as? JsonArray ?: throw IllegalArgumentException()
        return array.map { value ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException()
        }
    }

    private fun Boolean.toState() = if (this) SupportState.SUPPORTED else SupportState.UNSUPPORTED

    private fun workloadFor(value: String): ModelWorkload? = when (value.uppercase()) {
        "GENERATIVE_TEXT", "CHAT", "TEXT" -> ModelWorkload.GENERATIVE_TEXT
        "EMBEDDING" -> ModelWorkload.EMBEDDING
        "RERANK" -> ModelWorkload.RERANK
        "IMAGE_GENERATION", "IMAGE" -> ModelWorkload.IMAGE_GENERATION
        "SPEECH", "TTS", "AUDIO" -> ModelWorkload.SPEECH
        "MUSIC" -> ModelWorkload.MUSIC
        "REALTIME", "LIVE" -> ModelWorkload.REALTIME
        "VIDEO" -> ModelWorkload.VIDEO
        "UNKNOWN" -> ModelWorkload.UNKNOWN
        else -> null
    }

    private fun capabilityFor(value: String): ModelCapability? = when (value.lowercase()) {
        "reasoning" -> ModelCapability.REASONING
        "chat", "chat_endpoint" -> ModelCapability.CHAT_ENDPOINT
        "vision", "vision_input", "image_input" -> ModelCapability.VISION_INPUT
        "audio_input" -> ModelCapability.AUDIO_INPUT
        "audio_output" -> ModelCapability.AUDIO_OUTPUT
        "video_input" -> ModelCapability.VIDEO_INPUT
        "tool_call", "tool_calling", "tools" -> ModelCapability.TOOL_CALLING
        "structured_output", "json_schema" -> ModelCapability.STRUCTURED_OUTPUT
        "structured_outputs" -> ModelCapability.STRUCTURED_OUTPUT
        "thinking" -> ModelCapability.REASONING
        "prompt_caching" -> ModelCapability.PROMPT_CACHING
        "computer_use" -> ModelCapability.COMPUTER_USE
        "web_access" -> ModelCapability.WEB_ACCESS
        else -> null
    }

    private data class ModelEnvelope(
        val models: JsonArray,
        val isGemini: Boolean,
    )
}
