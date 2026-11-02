package com.promenar.infinity.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.model.catalog.ModelCapability
import com.promenar.infinity.data.model.catalog.SupportState
import com.promenar.infinity.ui.common.network.ModelNetworkBadge
import com.promenar.infinity.ui.common.network.ModelNetworkLightBadge

@Composable
private fun ModelCapability.selectionLabel(): String? = when (this) {
    ModelCapability.REASONING -> stringResource(R.string.provider_models_type_reasoning)
    ModelCapability.VISION_INPUT -> stringResource(R.string.provider_models_capability_vision)
    ModelCapability.AUDIO_INPUT -> stringResource(R.string.provider_models_capability_audio_input)
    ModelCapability.AUDIO_OUTPUT -> stringResource(R.string.provider_models_capability_audio_output)
    ModelCapability.VIDEO_INPUT -> stringResource(R.string.provider_models_capability_video)
    ModelCapability.TOOL_CALLING -> stringResource(R.string.sheet_tab_tools)
    ModelCapability.STRUCTURED_OUTPUT -> stringResource(R.string.provider_models_capability_structured_output)
    ModelCapability.PROMPT_CACHING -> stringResource(R.string.provider_models_capability_prompt_caching)
    ModelCapability.COMPUTER_USE -> stringResource(R.string.provider_models_capability_computer_use)
    ModelCapability.WEB_ACCESS -> stringResource(R.string.provider_models_capability_internet)
    ModelCapability.CHAT_ENDPOINT -> null
}

/**
 * 模型选择行：自绘紧凑布局（品牌圆标 + 名称 + 单行元信息 + 选中勾）。
 * 不使用 ListItem 默认行高，保证选择器列表的密度与商业应用一致。
 */
@Composable
fun ModelSelectionListItem(
    model: ModelSelectionUiModel,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 品牌图标右下角显示该模型所走网络（公网或 Tailscale 内网）的小灯。 */
    networkBadge: ModelNetworkBadge? = null,
) {
    val haptics = LocalInfinityHaptics.current
    val supportedCapabilities = model.capabilityStates
        .filter { it.value == SupportState.SUPPORTED }
        .keys
        .take(2)
    val capabilitySummary = supportedCapabilities.mapNotNull { it.selectionLabel() }
    val contextSummary = model.contextTokens?.let { tokens ->
        when {
            tokens >= 1_000_000 -> "${tokens / 1_000_000}${stringResource(R.string.common_model_picker_m_context)}"
            tokens >= 1_000 -> "${tokens / 1_000}${stringResource(R.string.common_model_picker_k_context)}"
            else -> tokens.toString()
        }
    }
    val metaText = buildList {
        contextSummary?.let { add(it) }
        addAll(capabilitySummary)
        add(providerDisplayName(model.providerName, protocol = null))
    }.joinToString(" · ") // wrap-check: allow 单行省略显示，不会折行

    Row(
        modifier = modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                } else {
                    Color.Transparent
                },
            )
            .semantics { this.selected = selected }
            .selectable(
                selected = selected,
                onClick = { haptics.select(selected); onClick() },
                role = Role.RadioButton,
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box {
            ModelBrandTile(
                modelId = model.remoteModelId,
                size = 30.dp,
            )
            networkBadge?.let { badge ->
                ModelNetworkLightBadge(
                    badge = badge,
                    ringColor = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = metaText,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
