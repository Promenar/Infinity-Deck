package com.promenar.infinity.data.model.catalog

enum class ModelWorkload {
    GENERATIVE_TEXT,
    EMBEDDING,
    RERANK,
    IMAGE_GENERATION,
    SPEECH,
    MUSIC,
    REALTIME,
    VIDEO,
    UNKNOWN,
}

enum class ModelCapability {
    REASONING,
    CHAT_ENDPOINT,
    VISION_INPUT,
    AUDIO_INPUT,
    AUDIO_OUTPUT,
    VIDEO_INPUT,
    TOOL_CALLING,
    STRUCTURED_OUTPUT,
    PROMPT_CACHING,
    COMPUTER_USE,
    WEB_ACCESS,
}

enum class SupportState {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
}

enum class MetadataSource {
    USER,
    PROVIDER,
    INFINITY_OVERRIDE,
    MODELS_DEV,
    LITELLM,
    OPENROUTER,
    FAMILY,
    FALLBACK,
}

data class ModelMetadataRecord(
    val canonicalModelId: String,
    val exactAliases: Set<String>,
    val displayName: String,
    val familyName: String?,
    val workload: ModelWorkload,
    val capabilities: Map<ModelCapability, SupportState>,
    val contextTokens: Int?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val knowledgeCutoff: String?,
    val source: MetadataSource,
    val providerScope: String? = null,
)

data class ModelMetadataOverride(
    val displayName: String? = null,
    val workload: ModelWorkload? = null,
    val capabilities: Map<ModelCapability, SupportState> = emptyMap(),
    val contextTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
)

data class ResolvedModelMetadata(
    val remoteModelId: String,
    val canonicalModelId: String?,
    val offeringId: String? = null,
    val displayName: String,
    val familyName: String?,
    val workload: ModelWorkload,
    val capabilities: Map<ModelCapability, SupportState>,
    val contextTokens: Int?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val knowledgeCutoff: String?,
    val sourceByField: Map<String, MetadataSource>,
    val diagnostics: Set<String> = emptySet(),
)
