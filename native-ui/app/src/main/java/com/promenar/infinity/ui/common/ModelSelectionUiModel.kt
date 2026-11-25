package com.promenar.infinity.ui.common

import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.catalog.ModelCapability
import com.promenar.infinity.data.model.catalog.ModelCatalogRuntime
import com.promenar.infinity.data.model.catalog.ModelMetadataOverride
import com.promenar.infinity.data.model.catalog.ModelMetadataResolver
import com.promenar.infinity.data.model.catalog.ModelWorkload
import com.promenar.infinity.data.model.catalog.SupportState

data class ModelSelectionUiModel(
    val selectionId: String,
    val remoteModelId: String,
    val displayName: String,
    val providerName: String,
    val contextTokens: Int?,
    val workload: ModelWorkload,
    val capabilityStates: Map<ModelCapability, SupportState>,
    val chatEndpointCompatible: SupportState,
    /** 所属提供商；判断模型是否经 Tailscale 内网访问。 */
    val providerId: String = "",
)

private fun ModelCapability.toLegacyName(): String? = when (this) {
    ModelCapability.REASONING -> "reasoning"
    ModelCapability.VISION_INPUT -> "vision"
    ModelCapability.AUDIO_INPUT -> "audioinput"
    ModelCapability.AUDIO_OUTPUT -> "audiooutput"
    ModelCapability.VIDEO_INPUT -> "videounderstanding"
    ModelCapability.TOOL_CALLING -> "toolcalling"
    ModelCapability.STRUCTURED_OUTPUT -> "structuredoutput"
    ModelCapability.PROMPT_CACHING -> "promptcaching"
    ModelCapability.COMPUTER_USE -> "computeruse"
    ModelCapability.WEB_ACCESS -> "internet"
    else -> null
}

private fun String.toModelWorkload(): ModelWorkload? = when (lowercase()) {
    "reasoning", "chat" -> ModelWorkload.GENERATIVE_TEXT
    "embedding" -> ModelWorkload.EMBEDDING
    "rerank" -> ModelWorkload.RERANK
    "image" -> ModelWorkload.IMAGE_GENERATION
    "speech", "tts", "audio" -> ModelWorkload.SPEECH
    "music" -> ModelWorkload.MUSIC
    "realtime", "live" -> ModelWorkload.REALTIME
    "video" -> ModelWorkload.VIDEO
    else -> null
}

private fun ModelInfo.toModelSelectionProviderMetadata(): ModelMetadataOverride {
    val normalizedCapabilities = capabilities.mapTo(mutableSetOf()) { it.lowercase() }
    val supportedCapabilities = buildMap {
        if (type.equals("reasoning", ignoreCase = true)) {
            put(ModelCapability.REASONING, SupportState.SUPPORTED)
        }
        ModelCapability.values().forEach { capability ->
            val legacyName = capability.toLegacyName() ?: return@forEach
            if (legacyName in normalizedCapabilities) {
                put(capability, SupportState.SUPPORTED)
            }
        }
    }

    return ModelMetadataOverride(
        displayName = name.takeIf {
            it.isNotBlank() && it != id && it != remoteModelId
        },
        workload = type.toModelWorkload(),
        capabilities = supportedCapabilities,
        contextTokens = contextLength.takeIf { it > 0 },
        outputTokens = maxOutputTokens.takeIf { it > 0 },
    )
}

fun ModelInfo.toModelSelectionUserOverride(): ModelMetadataOverride {
    val displayNameOverride = if ("name" in userEditedFields) name else null
    val contextTokensOverride = if ("contextLength" in userEditedFields) contextLength else null
    val outputTokensOverride = if ("maxOutputTokens" in userEditedFields) maxOutputTokens else null

    val workloadOverride = if ("type" in userEditedFields) {
        type.toModelWorkload() ?: ModelWorkload.UNKNOWN
    } else null

    val capabilitiesMap = mutableMapOf<ModelCapability, SupportState>()
    if ("type" in userEditedFields) {
        if (type.equals("reasoning", ignoreCase = true)) {
            capabilitiesMap[ModelCapability.REASONING] = SupportState.SUPPORTED
        }
    }
    if ("capabilities" in userEditedFields) {
        val normalizedCapabilities = capabilities.mapTo(mutableSetOf()) { it.lowercase() }
        for (cap in ModelCapability.values()) {
            val legacyName = cap.toLegacyName() ?: continue
            capabilitiesMap[cap] = if (legacyName in normalizedCapabilities) {
                SupportState.SUPPORTED
            } else SupportState.UNSUPPORTED
        }
    }

    return ModelMetadataOverride(
        displayName = displayNameOverride,
        workload = workloadOverride,
        capabilities = capabilitiesMap,
        contextTokens = contextTokensOverride,
        outputTokens = outputTokensOverride
    )
}

internal fun ModelSelectionUiModel.isChatSelectionCandidate(): Boolean = when (chatEndpointCompatible) {
    SupportState.SUPPORTED -> true
    SupportState.UNSUPPORTED -> false
    SupportState.UNKNOWN -> workload == ModelWorkload.GENERATIVE_TEXT
}

/**
 * 选择器投影。默认使用运行时已加载的完整模型目录：网关同步的模型通常带一份空的供应商元数据，
 * 工作负载与名称只能从目录得到；只用内置覆盖表会把目录内的对话模型判为未知并从选择器中隐藏。
 */
fun ModelInfo.toModelSelectionUiModel(
    resolver: ModelMetadataResolver = ModelCatalogRuntime.resolver,
): ModelSelectionUiModel {
    val userOverride = toModelSelectionUserOverride()
    val resolved = resolver.resolve(
        remoteModelId = remoteModelId,
        providerId = providerId.orEmpty(),
        sourceProviderId = sourceProviderId,
        ownedBy = providerOwnedBy,
        providerMetadata = providerMetadata ?: toModelSelectionProviderMetadata(),
        userOverride = userOverride
    )

    val capabilityStates = resolved.capabilities.filterKeys { it != ModelCapability.CHAT_ENDPOINT }

    return ModelSelectionUiModel(
        selectionId = id,
        remoteModelId = remoteModelId,
        displayName = resolved.displayName,
        providerName = providerName,
        contextTokens = resolved.contextTokens,
        workload = resolved.workload,
        capabilityStates = capabilityStates,
        chatEndpointCompatible = chatEndpointCompatible,
        providerId = providerId.orEmpty(),
    )
}
