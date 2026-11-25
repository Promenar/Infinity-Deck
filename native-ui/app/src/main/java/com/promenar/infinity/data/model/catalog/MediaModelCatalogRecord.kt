package com.promenar.infinity.data.model.catalog

import com.promenar.infinity.data.model.CloudMediaApi
import com.promenar.infinity.data.model.mediaModelPreset
import com.promenar.infinity.domain.mediagen.MediaGroup

/** 已核验媒体与实时流式家族的生成类型元数据；接入点别名由用户配置。 */
internal fun mediaModelCatalogRecord(remoteId: String): ModelMetadataRecord? {
    val cleanId = remoteId.substringAfterLast('/').removePrefix("models/").trim()
    val lower = cleanId.lowercase()
    if (isRealtimeModelId(lower)) {
        val family = when {
            lower.startsWith("lyria") -> "Lyria"
            lower.startsWith("gemini") -> "Gemini"
            lower.startsWith("gpt") -> "OpenAI"
            else -> null
        }
        return ModelMetadataRecord(
            canonicalModelId = cleanId,
            exactAliases = setOf(remoteId, cleanId),
            displayName = friendlyAudioDisplayName(cleanId),
            familyName = family,
            workload = ModelWorkload.REALTIME,
            capabilities = mapOf(ModelCapability.CHAT_ENDPOINT to SupportState.UNSUPPORTED),
            contextTokens = null,
            inputTokens = null,
            outputTokens = null,
            knowledgeCutoff = null,
            source = MetadataSource.INFINITY_OVERRIDE,
        )
    }
    val preset = mediaModelPreset(cleanId) ?: return null
    val family = when (preset.api) {
        CloudMediaApi.SEEDANCE -> "Seedance"
        CloudMediaApi.GEMINI_TTS -> "Gemini"
        CloudMediaApi.GEMINI_LYRIA -> "Lyria"
        CloudMediaApi.OPENAI_SPEECH -> "OpenAI"
        else -> "MiniMax"
    }
    val workload = when (preset.group) {
        MediaGroup.IMAGE -> ModelWorkload.IMAGE_GENERATION
        MediaGroup.VIDEO -> ModelWorkload.VIDEO
        MediaGroup.SPEECH -> ModelWorkload.SPEECH
        MediaGroup.MUSIC -> ModelWorkload.MUSIC
    }
    val caps = buildMap {
        put(ModelCapability.CHAT_ENDPOINT, SupportState.UNSUPPORTED)
        if (preset.api == CloudMediaApi.GEMINI_LYRIA) {
            put(ModelCapability.VISION_INPUT, SupportState.SUPPORTED)
        }
    }
    return ModelMetadataRecord(
        canonicalModelId = cleanId,
        exactAliases = setOf(remoteId, cleanId),
        displayName = friendlyAudioDisplayName(cleanId),
        familyName = family,
        workload = workload,
        capabilities = caps,
        contextTokens = null,
        inputTokens = null,
        outputTokens = null,
        knowledgeCutoff = null,
        source = MetadataSource.INFINITY_OVERRIDE,
    )
}

internal fun isRealtimeModelId(lowerId: String): Boolean =
    "realtime" in lowerId ||
        (lowerId.startsWith("gemini-") && Regex("""(?:^|[-_])live(?:$|[-_])""").containsMatchIn(lowerId))

private fun friendlyAudioDisplayName(id: String): String {
    val lower = id.lowercase()
    return when {
        lower == "lyria-3.5" -> "Lyria 3.5"
        lower == "lyria-3-clip-preview" -> "Lyria 3 Clip Preview"
        lower == "lyria-3-pro-preview" -> "Lyria 3 Pro Preview"
        lower == "lyria-realtime-exp" -> "Lyria Realtime Experimental"
        lower.startsWith("gemini-") && (lower.endsWith("-tts") || "-tts-" in lower || Regex("""(?:^|[-_])live(?:$|[-_])""").containsMatchIn(lower)) ->
            id.split('-').joinToString(" ") { part ->
                when (part.lowercase()) {
                    "tts" -> "TTS"
                    "exp" -> "Experimental"
                    else -> part.replaceFirstChar { it.uppercaseChar() }
                }
            }
        else -> id
    }
}
