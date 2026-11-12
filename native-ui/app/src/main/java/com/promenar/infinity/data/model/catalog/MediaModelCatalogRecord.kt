package com.promenar.infinity.data.model.catalog

import com.promenar.infinity.data.model.mediaModelPreset
import com.promenar.infinity.domain.mediagen.MediaGroup

/** 已核验媒体家族的生成类型元数据；接入点别名由用户配置。 */
internal fun mediaModelCatalogRecord(remoteId: String): ModelMetadataRecord? {
    val preset = mediaModelPreset(remoteId) ?: return null
    return ModelMetadataRecord(
        canonicalModelId = remoteId,
        exactAliases = setOf(remoteId),
        displayName = remoteId,
        familyName = when (preset.api) {
            com.promenar.infinity.data.model.CloudMediaApi.SEEDANCE -> "Seedance"
            else -> "MiniMax"
        },
        workload = when (preset.group) { MediaGroup.IMAGE -> ModelWorkload.IMAGE_GENERATION; MediaGroup.VIDEO -> ModelWorkload.VIDEO; MediaGroup.AUDIO -> ModelWorkload.AUDIO },
        capabilities = mapOf(ModelCapability.CHAT_ENDPOINT to SupportState.UNSUPPORTED),
        contextTokens = null, inputTokens = null, outputTokens = null, knowledgeCutoff = null,
        source = MetadataSource.INFINITY_OVERRIDE,
    )
}
