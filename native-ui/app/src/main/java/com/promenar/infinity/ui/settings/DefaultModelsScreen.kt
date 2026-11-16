package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.R
import com.promenar.infinity.data.model.catalog.ModelCatalogRuntime
import com.promenar.infinity.data.remote.ModelTimeoutKind
import com.promenar.infinity.data.remote.ModelTimeoutSettings
import com.promenar.infinity.ui.common.InfinityNumericSetting
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.ModelPicker
import com.promenar.infinity.ui.common.ModelSelectionUiModel
import com.promenar.infinity.ui.common.providerDisplayName
import com.promenar.infinity.ui.common.toModelSelectionUiModel
import com.promenar.infinity.ui.theme.InfinitySpacing

enum class DefaultModelRole(
    val type: String,
    val filter: String,
    val labelRes: Int,
) {
    SUMMARY("summary", "chat", R.string.settings_model_summary),
    IMAGE("image", "image", R.string.settings_model_image),
    EMBEDDING("embedding", "embedding", R.string.settings_model_embedding),
    RERANK("rerank", "rerank", R.string.settings_model_rerank)
}

@Composable
fun DefaultModelsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToMediaModels: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.applicationContext as android.app.Application))

    val allModels by viewModel.providerModels.collectAsState()
    val summaryModelId by viewModel.summaryModelId.collectAsState()
    val imageModelId by viewModel.imageModelId.collectAsState()
    val embeddingModelId by viewModel.embeddingModelId.collectAsState()
    val rerankModelId by viewModel.rerankModelId.collectAsState()
    val timeouts by viewModel.modelTimeouts.collectAsState()

    var activePickerRole by remember { mutableStateOf<DefaultModelRole?>(null) }

    val resolver = ModelCatalogRuntime.resolver
    val resolvedModels = remember(allModels, resolver) {
        allModels.map { model -> model.toModelSelectionUiModel(resolver) }
    }
    val modelItems = remember(allModels, resolvedModels) {
        resolvedModels.filter { resolved ->
            allModels.firstOrNull { it.id == resolved.selectionId }?.enabled == true
        }
    }

    val state = remember(resolvedModels, summaryModelId, imageModelId, embeddingModelId, rerankModelId, timeouts) {
        val summaryInfo = resolvedModels.firstOrNull { it.selectionId == summaryModelId }
        val imageInfo = resolvedModels.firstOrNull { it.selectionId == imageModelId }
        val embeddingInfo = resolvedModels.firstOrNull { it.selectionId == embeddingModelId }
        val rerankInfo = resolvedModels.firstOrNull { it.selectionId == rerankModelId }
        DefaultModelsScreenState(
            summaryModelName = resolveDefaultModelName(resolvedModels, summaryModelId),
            imageModelName = resolveDefaultModelName(resolvedModels, imageModelId),
            embeddingModelName = resolveDefaultModelName(resolvedModels, embeddingModelId),
            rerankModelName = resolveDefaultModelName(resolvedModels, rerankModelId),
            summaryProviderName = summaryInfo?.providerName,
            imageProviderName = imageInfo?.providerName,
            embeddingProviderName = embeddingInfo?.providerName,
            rerankProviderName = rerankInfo?.providerName,
            timeouts = timeouts,
        )
    }

    val actions = remember(viewModel, onNavigateBack) {
        DefaultModelsScreenActions(
            onNavigateBack = onNavigateBack,
            onShowModelPicker = { role ->
                activePickerRole = role
            },
            onClearModel = { role -> viewModel.setPresetModel(role.type, "") },
            onTimeoutChange = viewModel::setModelTimeout,
        )
    }

    DefaultModelsScreenContent(
        state = state,
        actions = actions,
        onMediaModels = onNavigateToMediaModels,
    )

    if (activePickerRole != null) {
        val role = activePickerRole!!
        val currentId = when (role) {
            DefaultModelRole.SUMMARY -> summaryModelId
            DefaultModelRole.IMAGE -> imageModelId
            DefaultModelRole.EMBEDDING -> embeddingModelId
            DefaultModelRole.RERANK -> rerankModelId
        }

        ModelPicker(
            show = true,
            title = stringResource(role.labelRes),
            filterTag = role.filter,
            currentModelId = currentId,
            models = modelItems,
            onDismiss = { activePickerRole = null },
            onSelect = { id, _ ->
                viewModel.setPresetModel(role.type, id)
                activePickerRole = null
            }
        )
    }
}

internal fun resolveDefaultModelName(models: List<ModelSelectionUiModel>, id: String): String {
    if (id.isEmpty()) return ""
    return models.firstOrNull { it.selectionId == id }?.displayName ?: id
}

internal data class DefaultModelsScreenState(
    val summaryModelName: String = "",
    val imageModelName: String = "",
    val embeddingModelName: String = "",
    val rerankModelName: String = "",
    val summaryProviderName: String? = null,
    val imageProviderName: String? = null,
    val embeddingProviderName: String? = null,
    val rerankProviderName: String? = null,
    val timeouts: ModelTimeoutSettings = ModelTimeoutSettings(),
)

internal data class DefaultModelsScreenActions(
    val onNavigateBack: () -> Unit = {},
    val onShowModelPicker: (DefaultModelRole) -> Unit = {},
    val onClearModel: (DefaultModelRole) -> Unit = {},
    val onTimeoutChange: (ModelTimeoutKind, Int) -> Unit = { _, _ -> },
)

@Composable
internal fun DefaultModelsScreenContent(
    state: DefaultModelsScreenState,
    actions: DefaultModelsScreenActions,
    onMediaModels: (() -> Unit)? = null,
) {
    InfinitySettingsPageLayout(
        title = stringResource(R.string.settings_default_models),
        onBack = actions.onNavigateBack,
    ) { contentPadding ->
        LazyColumn(contentPadding = contentPadding) {
            item("models") {
                InfinitySettingsSection(
                    title = stringResource(R.string.settings_section_ai_models),
                    footer = stringResource(R.string.settings_model_quick_description),
                    footerModifier = Modifier.testTag("default_model_quick_description"),
                ) {
                    DefaultModelRole.entries.forEach { role ->
                        val (selectedModelName, providerName) = when (role) {
                            DefaultModelRole.SUMMARY -> state.summaryModelName to state.summaryProviderName
                            DefaultModelRole.IMAGE -> state.imageModelName to state.imageProviderName
                            DefaultModelRole.EMBEDDING -> state.embeddingModelName to state.embeddingProviderName
                            DefaultModelRole.RERANK -> state.rerankModelName to state.rerankProviderName
                        }
                        DefaultModelSettingItem(
                            role = role,
                            selectedModelName = selectedModelName,
                            onSelectModel = { actions.onShowModelPicker(role) },
                            onClearModel = { actions.onClearModel(role) },
                            providerName = providerName,
                        )
                    }
                }
            }
            if (onMediaModels != null) item("media-models") {
                InfinitySettingsSection(title = stringResource(R.string.media_settings_title)) {
                    InfinitySettingsItem(title = stringResource(R.string.media_settings_title), onClick = onMediaModels, showChevron = true)
                }
            }
            item("timeouts") {
                ModelTimeoutSection(timeouts = state.timeouts, onTimeoutChange = actions.onTimeoutChange)
            }
        }
    }
}

/** 四类模型各一行“等待响应超时”，点开为预设加自定义的数值弹层；改动立即写入设置。 */
@Composable
private fun ModelTimeoutSection(
    timeouts: ModelTimeoutSettings,
    onTimeoutChange: (ModelTimeoutKind, Int) -> Unit,
) {
    val unit = stringResource(R.string.settings_model_timeout_unit)
    val description = stringResource(R.string.settings_model_timeout_desc)
    InfinitySettingsSection(
        title = stringResource(R.string.settings_model_timeout_section),
        footer = stringResource(R.string.settings_model_timeout_footer),
    ) {
        ModelTimeoutKind.entries.forEach { kind ->
            val spec = remember(kind, unit) { modelTimeoutSpec(kind, unit) }
            InfinityNumericSetting(
                title = stringResource(kind.labelRes),
                value = timeouts.seconds(kind),
                spec = spec,
                description = description,
                onValueChange = { onTimeoutChange(kind, it.toInt()) },
                modifier = Modifier.testTag("model_timeout:${kind.name.lowercase()}"),
            )
        }
    }
}

private val ModelTimeoutKind.labelRes: Int
    get() = when (this) {
        ModelTimeoutKind.LLM -> R.string.settings_model_timeout_llm
        ModelTimeoutKind.IMAGE -> R.string.settings_model_image
        ModelTimeoutKind.EMBEDDING -> R.string.settings_model_embedding
        ModelTimeoutKind.RERANK -> R.string.settings_model_rerank
    }

private fun DefaultModelRole.vectorIcon() = when (this) {
    DefaultModelRole.SUMMARY -> Icons.AutoMirrored.Rounded.ShortText
    DefaultModelRole.IMAGE -> Icons.Rounded.Image
    DefaultModelRole.EMBEDDING -> Icons.Rounded.Hub
    DefaultModelRole.RERANK -> Icons.AutoMirrored.Rounded.Sort
}

@Composable
internal fun DefaultModelSettingItem(
    role: DefaultModelRole,
    selectedModelName: String,
    onSelectModel: () -> Unit,
    onClearModel: () -> Unit,
    providerName: String? = null,
) {
    val selectModelLabel = stringResource(R.string.common_model_picker_title)
    val clearLabel = stringResource(R.string.common_cd_clear)
    val subtitleText = when {
        selectedModelName.isEmpty() -> stringResource(R.string.settings_not_set)
        !providerName.isNullOrBlank() -> "$selectedModelName · ${providerDisplayName(providerName, protocol = null)}"
        else -> selectedModelName
    }
    Column {
        InfinitySettingsItem(
            icon = role.vectorIcon(),
            title = stringResource(role.labelRes),
            subtitle = subtitleText,
            subtitleIsIdentifier = selectedModelName.isNotEmpty(),
            onClick = onSelectModel,
            showChevron = selectedModelName.isEmpty(),
            modifier = Modifier
                .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                .testTag("default_model_role:${role.type}"),
            onClickLabel = selectModelLabel,
            trailingContent = if (selectedModelName.isEmpty()) null else {
                {
                    IconButton(
                        onClick = onClearModel,
                        modifier = Modifier.testTag("default_model_clear:${role.type}"),
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = clearLabel,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        )
    }
}
