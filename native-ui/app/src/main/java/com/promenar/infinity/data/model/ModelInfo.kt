package com.promenar.infinity.data.model

import com.promenar.infinity.data.model.catalog.SupportState
import com.promenar.infinity.data.model.catalog.ModelCapability
import com.promenar.infinity.data.model.catalog.ModelWorkload
import com.promenar.infinity.data.model.catalog.ResolvedModelMetadata
import com.promenar.infinity.data.model.catalog.MetadataSource
import com.promenar.infinity.data.model.catalog.ModelMetadataOverride
import com.promenar.infinity.data.remote.stableModelId

internal val USER_EDITABLE_MODEL_FIELDS = setOf(
    "remoteModelId",
    "name",
    "type",
    "capabilities",
    "contextLength",
    "maxOutputTokens",
    "mediaSettings",
)

data class ModelInfo(
    val name: String,
    val id: String,
    val description: String,
    val enabled: Boolean,
    val type: String = "unknown",
    val contextLength: Int = 0,
    val capabilities: List<String> = emptyList(),
    val providerName: String = "Cloud",
    val providerId: String? = null,
    val remoteModelId: String = id,
    val testStatus: String? = null,
    val maxOutputTokens: Int = 0,
    val inputTokens: Int = 0,
    val knowledgeCutoff: String? = null,
    val familyName: String? = null,
    val canonicalModelId: String? = null,
    val chatEndpointCompatible: SupportState = SupportState.UNKNOWN,
    val autoMetadataFingerprint: String? = null,
    val userEditedFields: Set<String> = emptySet(),
    val metadataSourceByField: Map<String, MetadataSource> = emptyMap(),
    val metadataDiagnostics: Set<String> = emptySet(),
    val providerOwnedBy: String? = null,
    val sourceProviderId: String? = null,
    val providerMetadata: ModelMetadataOverride? = null,
    val sessionHeaderMode: SessionHeaderMode = SessionHeaderMode.INHERIT,
    val mediaSettings: MediaModelSettings? = null,
    val mediaSettingsInvalid: Boolean = false,
)

internal fun ResolvedModelMetadata.toLegacyType(): String = mediaModelPreset(remoteModelId)?.group?.name?.lowercase() ?: when (workload) {
    ModelWorkload.GENERATIVE_TEXT -> if (
        capabilities[ModelCapability.REASONING] == SupportState.SUPPORTED
    ) {
        "reasoning"
    } else {
        "chat"
    }
    ModelWorkload.EMBEDDING -> "embedding"
    ModelWorkload.RERANK -> "rerank"
    ModelWorkload.IMAGE_GENERATION -> "image"
    ModelWorkload.AUDIO -> "audio"
    ModelWorkload.VIDEO -> "video"
    ModelWorkload.UNKNOWN -> "unknown"
}

internal fun ResolvedModelMetadata.toLegacySupportedCapabilities(): List<String> = buildList {
    when (toLegacyType()) {
        "chat" -> add("chat")
        "reasoning" -> addAll(listOf("chat", "reasoning"))
        "image" -> add("image")
        "embedding" -> add("embedding")
        "rerank" -> add("rerank")
        "audio" -> add("audio")
        "video" -> add("video")
    }
    val supportedCapabilities = mapOf(
        ModelCapability.VISION_INPUT to "vision",
        ModelCapability.AUDIO_INPUT to "audioinput",
        ModelCapability.AUDIO_OUTPUT to "audiooutput",
        ModelCapability.VIDEO_INPUT to "videounderstanding",
        ModelCapability.STRUCTURED_OUTPUT to "structuredoutput",
        ModelCapability.TOOL_CALLING to "toolcalling",
        ModelCapability.PROMPT_CACHING to "promptcaching",
        ModelCapability.COMPUTER_USE to "computeruse",
        ModelCapability.WEB_ACCESS to "internet",
    )
    supportedCapabilities.forEach { (capability, legacyName) ->
        if (capabilities[capability] == SupportState.SUPPORTED && legacyName !in this) {
            add(legacyName)
        }
    }
}

internal fun ResolvedModelMetadata.autoFingerprint(): String = listOf(
    "v1",
    displayName,
    toLegacyType(),
    toLegacySupportedCapabilities().joinToString(","),
    contextTokens?.toString().orEmpty(),
    outputTokens?.toString().orEmpty(),
    inputTokens?.toString().orEmpty(),
    knowledgeCutoff.orEmpty(),
    familyName.orEmpty(),
    canonicalModelId.orEmpty(),
    (capabilities[ModelCapability.CHAT_ENDPOINT] ?: SupportState.UNKNOWN).name,
).joinToString("|")

internal fun ResolvedModelMetadata.toModelInfo(
    providerId: String,
    providerName: String,
    enabled: Boolean,
    description: String = familyName ?: remoteModelId,
    providerOwnedBy: String? = null,
    sourceProviderId: String? = null,
    providerMetadata: ModelMetadataOverride? = null,
): ModelInfo = ModelInfo(
    name = displayName,
    id = stableModelId(providerId, remoteModelId),
    description = description,
    enabled = enabled,
    type = toLegacyType(),
    contextLength = contextTokens ?: 0,
    capabilities = toLegacySupportedCapabilities(),
    providerName = providerName,
    providerId = providerId,
    remoteModelId = remoteModelId,
    maxOutputTokens = outputTokens ?: 0,
    inputTokens = inputTokens ?: 0,
    knowledgeCutoff = knowledgeCutoff,
    familyName = familyName,
    canonicalModelId = canonicalModelId,
    chatEndpointCompatible = if (mediaModelPreset(remoteModelId) != null) SupportState.UNSUPPORTED else capabilities[ModelCapability.CHAT_ENDPOINT] ?: SupportState.UNKNOWN,
    autoMetadataFingerprint = autoFingerprint(),
    metadataSourceByField = sourceByField,
    metadataDiagnostics = diagnostics,
    providerOwnedBy = providerOwnedBy,
    sourceProviderId = sourceProviderId,
    providerMetadata = providerMetadata,
)

internal fun ModelInfo.mergeResolvedMetadata(resolved: ResolvedModelMetadata): ModelInfo {
    val userFields = userEditedFields.intersect(USER_EDITABLE_MODEL_FIELDS)
    val resolvedChatEndpoint = if (mediaSettings != null || mediaModelPreset(remoteModelId) != null) SupportState.UNSUPPORTED
        else resolved.capabilities[ModelCapability.CHAT_ENDPOINT] ?: SupportState.UNKNOWN
    return copy(
        name = if ("name" in userFields) name else resolved.displayName,
        description = resolved.familyName ?: resolved.remoteModelId,
        type = if ("type" in userFields) type else resolved.toLegacyType(),
        contextLength = if ("contextLength" in userFields) contextLength else resolved.contextTokens ?: 0,
        capabilities = if ("capabilities" in userFields) capabilities.toList() else resolved.toLegacySupportedCapabilities(),
        maxOutputTokens = if ("maxOutputTokens" in userFields) {
            maxOutputTokens
        } else {
            resolved.outputTokens ?: 0
        },
        inputTokens = resolved.inputTokens ?: 0,
        knowledgeCutoff = resolved.knowledgeCutoff,
        familyName = resolved.familyName,
        canonicalModelId = resolved.canonicalModelId,
        chatEndpointCompatible = if (resolvedChatEndpoint == SupportState.UNKNOWN) {
            chatEndpointCompatible
        } else {
            resolvedChatEndpoint
        },
        autoMetadataFingerprint = resolved.autoFingerprint(),
        metadataSourceByField = resolved.sourceByField,
        metadataDiagnostics = resolved.diagnostics,
        userEditedFields = userFields,
    )
}

internal fun ModelInfo.withRecordedUserEdits(previous: ModelInfo): ModelInfo {
    val changedFields = buildSet {
        if (remoteModelId != previous.remoteModelId) add("remoteModelId")
        if (name != previous.name) add("name")
        if (type != previous.type) add("type")
        if (capabilities.toSet() != previous.capabilities.toSet()) add("capabilities")
        if (contextLength != previous.contextLength) add("contextLength")
        if (maxOutputTokens != previous.maxOutputTokens) add("maxOutputTokens")
        if (mediaSettings != previous.mediaSettings) add("mediaSettings")
    }
    return copy(
        userEditedFields = previous.userEditedFields.intersect(USER_EDITABLE_MODEL_FIELDS) +
            userEditedFields.intersect(USER_EDITABLE_MODEL_FIELDS) +
            changedFields,
    )
}

internal fun ModelInfo.migrateLegacyMetadata(
    resolved: ResolvedModelMetadata,
    storedFieldPresence: Set<String>,
): ModelInfo {
    val legacyCandidates = legacyAutoMetadataCandidates(remoteModelId)
    val changedFields = buildSet {
        if ("name" in storedFieldPresence && legacyCandidates.none { name == it.name }) add("name")
        if ("type" in storedFieldPresence && legacyCandidates.none { type == it.type }) add("type")
        if (
            "capabilities" in storedFieldPresence &&
            legacyCandidates.none { capabilities.toSet() == it.capabilities.toSet() }
        ) {
            add("capabilities")
        }
        if (
            "contextLength" in storedFieldPresence &&
            legacyCandidates.none { contextLength == it.contextLength }
        ) {
            add("contextLength")
        }
        if (
            "maxOutputTokens" in storedFieldPresence &&
            legacyCandidates.none { maxOutputTokens == it.maxOutputTokens }
        ) {
            add("maxOutputTokens")
        }
    }
    return copy(
        userEditedFields = userEditedFields.intersect(USER_EDITABLE_MODEL_FIELDS) + changedFields,
    )
        .mergeResolvedMetadata(resolved)
}

private data class LegacyAutoMetadataCandidate(
    val name: String,
    val type: String,
    val capabilities: List<String>,
    val contextLength: Int,
    val maxOutputTokens: Int,
)

private fun legacyAutoMetadataCandidates(remoteModelId: String): List<LegacyAutoMetadataCandidate> =
    buildList {
        MODEL_SPECS.firstOrNull { it.pattern.matches(remoteModelId) }
            ?.let { add(it.toLegacyAutoMetadataCandidate(remoteModelId)) }
        findRemoteModelSpec(remoteModelId)
            ?.let { add(it.toLegacyAutoMetadataCandidate(remoteModelId)) }
        add(
            LegacyAutoMetadataCandidate(
                name = remoteModelId,
                type = "unknown",
                capabilities = emptyList(),
                contextLength = 8192,
                maxOutputTokens = 0,
            )
        )
        add(
            LegacyAutoMetadataCandidate(
                name = remoteModelId,
                type = "chat",
                capabilities = listOf("chat"),
                contextLength = 8192,
                maxOutputTokens = 0,
            )
        )
    }.distinct()

private fun ModelSpec.toLegacyAutoMetadataCandidate(remoteModelId: String): LegacyAutoMetadataCandidate {
    val legacyType = type?.name?.lowercase() ?: "chat"
    return LegacyAutoMetadataCandidate(
        name = note ?: remoteModelId,
        type = legacyType,
        capabilities = legacyModelCapabilities(legacyType, this),
        contextLength = contextLength,
        maxOutputTokens = maxOutputTokens,
    )
}

private fun legacyModelCapabilities(type: String, spec: ModelSpec?): List<String> = buildList {
    when (type) {
        "chat" -> add("chat")
        "reasoning" -> addAll(listOf("chat", "reasoning"))
        "image" -> add("image")
        "embedding" -> add("embedding")
        "rerank" -> add("rerank")
        "audio" -> add("audio")
        "video" -> add("video")
    }
    spec?.capabilities?.let { capabilities ->
        if (capabilities.vision) add("vision")
        if (capabilities.internet) add("internet")
        if (capabilities.reasoning) add("reasoning")
        if (capabilities.image) add("image")
        if (capabilities.embedding) add("embedding")
        if (capabilities.rerank) add("rerank")
        if (capabilities.audioInput) add("audioinput")
        if (capabilities.audioOutput) add("audiooutput")
        if (capabilities.videoUnderstanding) add("videounderstanding")
        if (capabilities.structuredOutput) add("structuredoutput")
        if (capabilities.promptCaching) add("promptcaching")
        if (capabilities.computerUse) add("computeruse")
    }
}
