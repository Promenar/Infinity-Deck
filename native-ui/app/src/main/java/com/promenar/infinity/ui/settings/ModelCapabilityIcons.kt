package com.promenar.infinity.ui.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.ScatterPlot
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.ui.common.ModelCapabilityBadge
import com.promenar.infinity.ui.common.InfinityChoice
import com.promenar.infinity.ui.common.InfinitySegment
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 模型类型与九项能力的图标、文案单一映射：模型列表行与编辑页共用。
 */
@Immutable
internal data class ModelCapabilitySpec(
    val key: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
)

internal val ModelCapabilitySpecs = listOf(
    ModelCapabilitySpec("toolcalling", R.string.provider_models_capability_tools, Icons.Rounded.Build),
    ModelCapabilitySpec("vision", R.string.provider_models_capability_vision, Icons.Rounded.Visibility),
    ModelCapabilitySpec("internet", R.string.provider_models_capability_internet, Icons.Rounded.Language),
    ModelCapabilitySpec("audioinput", R.string.provider_models_capability_audio_input, Icons.Rounded.Mic),
    ModelCapabilitySpec("audiooutput", R.string.provider_models_capability_audio_output, Icons.AutoMirrored.Rounded.VolumeUp),
    ModelCapabilitySpec("videounderstanding", R.string.provider_models_capability_video, Icons.Rounded.Videocam),
    ModelCapabilitySpec("structuredoutput", R.string.provider_models_capability_structured_output, Icons.Rounded.DataObject),
    ModelCapabilitySpec("promptcaching", R.string.provider_models_capability_prompt_caching, Icons.Rounded.Cached),
    ModelCapabilitySpec("computeruse", R.string.provider_models_capability_computer_use, Icons.Rounded.Laptop),
)

internal val ModelTypeSpecs = listOf(
    ModelCapabilitySpec("chat", R.string.provider_models_type_chat, Icons.Rounded.ChatBubbleOutline),
    ModelCapabilitySpec("reasoning", R.string.provider_models_type_reasoning, Icons.Rounded.Psychology),
    ModelCapabilitySpec("image", R.string.provider_models_type_image, Icons.Rounded.Image),
    ModelCapabilitySpec("embedding", R.string.provider_models_type_embedding, Icons.Rounded.ScatterPlot),
    ModelCapabilitySpec("rerank", R.string.provider_models_type_rerank, Icons.AutoMirrored.Rounded.Sort),
)

@StringRes
internal fun modelTypeLabelRes(type: String): Int =
    ModelTypeSpecs.firstOrNull { it.key.equals(type, ignoreCase = true) }?.labelRes
        ?: R.string.provider_models_type_unknown

/** 列表行能力图标组：按单一映射的顺序列出模型具备的能力，未知能力不显示。 */
@Composable
internal fun modelCapabilityBadges(model: ModelInfo): List<ModelCapabilityBadge> {
    val keys = model.capabilities.mapTo(mutableSetOf()) { it.lowercase() }
    return ModelCapabilitySpecs.filter { it.key in keys }
        .map { ModelCapabilityBadge(icon = it.icon, label = stringResource(it.labelRes)) }
}

@Composable
internal fun modelTypeSegments(modelId: String): List<InfinitySegment<String>> = ModelTypeSpecs.map { spec ->
    InfinitySegment(
        value = spec.key,
        label = stringResource(spec.labelRes),
        tag = com.promenar.infinity.ui.testing.UiTags.providerModelsTypeAction(modelId, spec.key),
        leading = { Icon(spec.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
    )
}

@Composable
internal fun modelCapabilityChoices(modelId: String): List<InfinityChoice<String>> = ModelCapabilitySpecs.map {
    InfinityChoice(
        value = it.key,
        label = stringResource(it.labelRes),
        icon = it.icon,
        tag = com.promenar.infinity.ui.testing.UiTags.providerModelsCapabilityAction(modelId, it.key),
    )
}

/** 类型标签上的小图标（未知类型用通用星标）。 */
internal fun modelTypeIcon(type: String): ImageVector =
    ModelTypeSpecs.firstOrNull { it.key.equals(type, ignoreCase = true) }?.icon ?: Icons.Rounded.AutoAwesome
