package com.promenar.infinity.ui.common

import androidx.compose.ui.platform.LocalResources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.model.catalog.ModelCapability
import com.promenar.infinity.data.model.catalog.ModelWorkload
import com.promenar.infinity.data.model.catalog.SupportState
import com.promenar.infinity.ui.common.network.NetworkLightsRow
import com.promenar.infinity.ui.common.network.NetworkLightsState
import com.promenar.infinity.ui.common.network.badgeFor
import com.promenar.infinity.ui.common.network.rememberNetworkLightsState
import com.promenar.infinity.ui.common.network.rememberTailscaleRetry
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPicker(
    show: Boolean,
    onDismiss: () -> Unit,
    onSelect: (modelId: String, modelName: String) -> Unit,
    currentModelId: String = "",
    models: List<ModelSelectionUiModel> = emptyList(),
    title: String? = null,
    filterTag: String? = null,
    /** 所在页面能跳转 Tailscale 设置时传入；为 null 时 Tailscale 灯只在失败时可点（重试）。 */
    onOpenTailscaleSettings: (() -> Unit)? = null,
) {
    if (!show) return
    val networkLights = rememberNetworkLightsState()

    var searchQuery by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }

    LaunchedEffect(searchQuery) {
        delay(150)
        debouncedQuery = searchQuery
    }

    val resources = LocalResources.current
    val filteredModels = remember(debouncedQuery, models, filterTag, resources) {
        val baseList = if (filterTag == null) models
        else models.filter { item -> item.matchesModelPickerFilter(filterTag) }

        if (debouncedQuery.isBlank()) baseList
        else baseList.filter {
            it.displayName.contains(debouncedQuery, ignoreCase = true) ||
                    providerDisplayName(it.providerName, protocol = null, resources).contains(debouncedQuery, ignoreCase = true)
        }
    }

    InfinityModalSheet(onDismissRequest = onDismiss, height = InfinitySheetHeight.Full) {
        ModelPickerSheetContent(
            title = title ?: stringResource(R.string.common_model_picker_title),
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            models = filteredModels,
            currentModelId = currentModelId,
            onSelect = onSelect,
            networkLights = networkLights,
            onOpenTailscaleSettings = onOpenTailscaleSettings,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

@Composable
internal fun ModelPickerSheetContent(
    title: String,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    models: List<ModelSelectionUiModel>,
    currentModelId: String,
    onSelect: (modelId: String, modelName: String) -> Unit,
    modifier: Modifier = Modifier,
    networkLights: NetworkLightsState? = null,
    onOpenTailscaleSettings: (() -> Unit)? = null,
    onRetryTailscale: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .testTag("model_picker_sheet")
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(16.dp))

        InfinitySearchBar(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = stringResource(R.string.common_model_picker_search),
        )

        if (networkLights != null) {
            NetworkLightsRow(
                state = networkLights,
                onOpenTailscaleSettings = onOpenTailscaleSettings,
                onRetryTailscale = onRetryTailscale ?: rememberTailscaleRetry(),
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (models.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.DeveloperBoard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.common_model_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(models, key = { _, model -> model.selectionId }) { index, model ->
                    val isSelected = model.selectionId == currentModelId

                    ModelSelectionListItem(
                        model = model,
                        selected = isSelected,
                        onClick = { onSelect(model.selectionId, model.displayName) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("model_picker_item:${model.selectionId}"),
                        networkBadge = networkLights?.badgeFor(model),
                    )
                }
            }
        }
    }
}

internal fun ModelSelectionUiModel.matchesModelPickerFilter(filterTag: String): Boolean = when (filterTag) {
    "chat" -> isChatSelectionCandidate()
    "multimodal" -> capabilityStates[ModelCapability.VISION_INPUT] == SupportState.SUPPORTED
    "reasoning" -> capabilityStates[ModelCapability.REASONING] == SupportState.SUPPORTED
    "image" -> workload == ModelWorkload.IMAGE_GENERATION
    "embedding" -> workload == ModelWorkload.EMBEDDING
    "rerank" -> workload == ModelWorkload.RERANK
    "audio" -> workload == ModelWorkload.AUDIO ||
        capabilityStates[ModelCapability.AUDIO_INPUT] == SupportState.SUPPORTED ||
        capabilityStates[ModelCapability.AUDIO_OUTPUT] == SupportState.SUPPORTED
    "video" -> workload == ModelWorkload.VIDEO ||
        capabilityStates[ModelCapability.VIDEO_INPUT] == SupportState.SUPPORTED
    "vision" -> capabilityStates[ModelCapability.VISION_INPUT] == SupportState.SUPPORTED
    else -> true
}
