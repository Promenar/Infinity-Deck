package com.promenar.infinity.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import com.promenar.infinity.ui.common.InfinityIdentifierText
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.data.local.inference.RecordingModelPackage
import com.promenar.infinity.data.local.inference.LocalModelRevision
import com.promenar.infinity.data.local.inference.SlotState
import com.promenar.infinity.data.local.inference.SlotType
import com.promenar.infinity.data.local.inference.StoredModel
import com.promenar.infinity.ui.common.SettingsSectionHeader
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.bettboxListGroup
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.theme.InfinitySpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelsScreen(
    onNavigateBack: () -> Unit,
    viewModel: LocalModelsViewModel = viewModel(
        factory = LocalModelsViewModel.factory(LocalContext.current.applicationContext as android.app.Application)
    )
) {
    val availableModels by viewModel.availableModels.collectAsState()
    val engineEnabled by viewModel.isEngineEnabled.collectAsState()
    val mainSlot by viewModel.mainSlot.collectAsState()
    val embeddingSlot by viewModel.embeddingSlot.collectAsState()
    val rerankSlot by viewModel.rerankSlot.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val pinned by viewModel.pinnedRevision.collectAsState()
    val modelError by viewModel.modelError.collectAsState()
    val download by viewModel.downloadState.collectAsState()

    val ggufPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importModel(uri)
    }

    LocalModelsScreenContent(
        availableModels = availableModels,
        engineEnabled = engineEnabled,
        mainSlot = mainSlot,
        embeddingSlot = embeddingSlot,
        rerankSlot = rerankSlot,
        isImporting = isImporting,
        gpuAvailable = viewModel.gpuAvailable,
        onNavigateBack = onNavigateBack,
        onImport = { ggufPickerLauncher.launch(arrayOf("*/*")) },
        onInstall = viewModel::installRecordingModel,
        onCancelInstall = viewModel::cancelDownload,
        onTogglePin = viewModel::toggleRevision,
        pinned = pinned,
        modelError = modelError,
        downloadProgress = download?.takeIf { it.isDownloading && it.totalBytes > 0 }
            ?.let { (it.downloadedBytes * 100 / it.totalBytes).toInt() },

        onEngineEnabledChange = viewModel::setEngineEnabled,
        onLoadModel = viewModel::loadModel,
        onUnloadModel = viewModel::unloadModel,
        onDeleteModel = viewModel::deleteModel,
        isModelLoadedInSlot = viewModel::isModelLoadedInSlot,
        findSlotForModel = viewModel::findSlotForModel,
        formatFileSize = viewModel::formatFileSize,
    )
}

@Composable
internal fun LocalModelsScreenContent(
    availableModels: List<StoredModel> = emptyList(),
    engineEnabled: Boolean = false,
    mainSlot: SlotState = SlotState(),
    embeddingSlot: SlotState = SlotState(),
    rerankSlot: SlotState = SlotState(),
    isImporting: Boolean = false,
    gpuAvailable: Boolean = false,
    onNavigateBack: () -> Unit = {},
    onImport: () -> Unit = {},
    onEngineEnabledChange: (Boolean) -> Unit = {},
    onLoadModel: (SlotType, String) -> Unit = { _, _ -> },
    onUnloadModel: (SlotType) -> Unit = {},
    onDeleteModel: (String) -> Unit = {},
    isModelLoadedInSlot: (String) -> Boolean = { false },
    findSlotForModel: (String) -> SlotType? = { null },
    formatFileSize: (Long) -> String = { it.toString() },
    onInstall: (() -> Unit)? = null,
    onCancelInstall: () -> Unit = {},
    onTogglePin: ((String) -> Unit)? = null,
    pinned: LocalModelRevision? = null,
    modelError: Int? = null,
    downloadProgress: Int? = null,
) {
    InfinitySettingsPageLayout(
        title = stringResource(R.string.local_models_title),
        onBack = onNavigateBack,
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = paddingValues,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                InfinitySettingsSection(
                    title = stringResource(R.string.local_models_engine_status),
                ) {
                    SettingsToggle(
                        title = stringResource(R.string.local_models_enable_engine),
                        description = stringResource(R.string.settings_batch1_local_engine_summary),
                        icon = Icons.Rounded.Memory,
                        checked = engineEnabled,
                        onCheckedChange = onEngineEnabledChange,
                    )
                    InfinitySettingsItem(
                        icon = Icons.Rounded.UploadFile,
                        title = stringResource(if (isImporting) R.string.local_models_importing else R.string.local_models_import_title),
                        subtitle = stringResource(if (isImporting) R.string.local_models_please_wait else R.string.settings_batch1_local_import_summary),
                        onClick = onImport,
                        enabled = !isImporting,
                        showChevron = true,
                        trailingContent = if (isImporting) ({ CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }) else null,
                    )
                    if (onInstall != null && availableModels.none { it.fileName == RecordingModelPackage.filename }) {
                        RecordingModelDownloadRow(
                            progress = downloadProgress,
                            enabled = !isImporting,
                            onInstall = onInstall,
                            onCancel = onCancelInstall,
                        )
                    }
                    if (modelError != null) {
                        Text(
                            text = stringResource(modelError),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(
                                horizontal = InfinitySpacing.RowHorizontal,
                                vertical = InfinitySpacing.Small,
                            ),
                        )
                    }

                }
            }

            item {
                InfinitySettingsSection(
                    title = stringResource(R.string.local_models_active_slots),
                    footer = listOf(
                        mainSlot to R.string.local_models_slot_main,
                        embeddingSlot to R.string.local_models_slot_embeddings,
                        rerankSlot to R.string.local_models_slot_reranker,
                    ).mapNotNull { (slot, label) ->
                        (slot.error ?: slot.modelSize.takeIf(String::isNotBlank))?.let { stringResource(label) + ": " + it }
                    }.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                ) {
                    listOf(
                        Triple(SlotType.MAIN, mainSlot, R.string.local_models_slot_main),
                        Triple(SlotType.EMBEDDING, embeddingSlot, R.string.local_models_slot_embeddings),
                        Triple(SlotType.RERANK, rerankSlot, R.string.local_models_slot_reranker),
                    ).forEach { (type, slot, labelRes) ->
                        SlotCard(
                            label = stringResource(labelRes),
                            slotState = slot,
                            enabled = engineEnabled,
                            onLoadClick = { path -> onLoadModel(type, path) },
                            models = availableModels,
                            formatFileSize = formatFileSize,
                        )
                    }
                }
            }

            item { SettingsSectionHeader(stringResource(R.string.local_models_imported)) }

            if (availableModels.isEmpty()) {
                item {
                    InfinitySettingsItem(
                        title = stringResource(R.string.local_models_none_imported),
                        enabled = !isImporting,
                        onClick = onImport,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            } else {
                items(availableModels, key = { it.id }) { model ->
                    ModelCard(
                        model = model,
                        isLoadedInSlot = isModelLoadedInSlot(model.filePath),
                        loadedSlot = findSlotForModel(model.filePath),
                        engineEnabled = engineEnabled,
                        formatFileSize = { formatFileSize(it) },
                        onLoad = { slot ->
                            if (isModelLoadedInSlot(model.filePath)) {
                                val s = findSlotForModel(model.filePath)
                                if (s != null) onUnloadModel(s)
                            } else {
                                onLoadModel(slot, model.filePath)
                            }
                        },
                        onDelete = { onDeleteModel(model.filePath) },
                        revision = pinned?.takeIf { it.path == model.filePath },
                        onTogglePin = onTogglePin?.let { { it(model.filePath) } },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            item { SettingsSectionHeader(stringResource(R.string.local_models_engine_status)) }

            item {
                Column(modifier = Modifier.fillMaxWidth().bettboxListGroup()) {
                    InfinityMetaLine(
                        segments = listOf("llama.cpp", if (gpuAvailable) "Vulkan GPU" else "CPU"),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    EngineSlotStatus(
                        label = stringResource(R.string.local_models_slot_main),
                        modelName = mainSlot.modelName.ifEmpty { null },
                        active = mainSlot.isLoaded,
                        isLoading = mainSlot.isLoading,
                        badge = mainSlot.backendType.displayName
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    EngineSlotStatus(
                        label = stringResource(R.string.local_models_slot_embeddings),
                        modelName = embeddingSlot.modelName.ifEmpty { null },
                        active = embeddingSlot.isLoaded,
                        isLoading = embeddingSlot.isLoading,
                        badge = embeddingSlot.backendType.displayName
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    EngineSlotStatus(
                        label = stringResource(R.string.local_models_slot_reranker),
                        modelName = rerankSlot.modelName.ifEmpty { null },
                        active = rerankSlot.isLoaded,
                        isLoading = rerankSlot.isLoading,
                        badge = if (rerankSlot.isLoaded) rerankSlot.backendType.displayName else stringResource(R.string.local_models_slot_idle)
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun RecordingModelDownloadRow(
    progress: Int?,
    enabled: Boolean,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = InfinitySpacing.RowHorizontal,
            vertical = InfinitySpacing.Small,
        ),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        Text(
            text = stringResource(R.string.recording_library_ui_local_model),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = if (progress == null) stringResource(R.string.recording_model_download_details)
                else stringResource(R.string.recording_model_download_progress, progress),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        InfinityPillRow(Modifier.fillMaxWidth()) {
            InfinityPillButton(
                text = stringResource(if (progress == null) R.string.recording_library_ui_download else R.string.recording_library_ui_cancel_download),
                onClick = if (progress == null) onInstall else onCancel,
                enabled = enabled,
                tone = if (progress == null) InfinityPillTone.PRIMARY else InfinityPillTone.NEUTRAL,
            )
        }
    }
}

@Composable
private fun SlotCard(
    label: String,
    slotState: SlotState,
    enabled: Boolean,
    onLoadClick: (String) -> Unit,
    models: List<StoredModel>,
    formatFileSize: (Long) -> String,
) {
    var showModelPicker by remember { mutableStateOf(false) }
    Column {
        InfinitySettingsItem(
            icon = Icons.Rounded.Memory,
            title = label,
            subtitle = when {
                slotState.isLoading -> stringResource(R.string.shared_loading)
                slotState.isLoaded -> slotState.modelName
                slotState.error != null -> stringResource(R.string.common_cd_failed)
                models.isEmpty() -> stringResource(R.string.local_models_slot_empty)
                else -> stringResource(R.string.local_models_load_model)
            },
            onClick = {
                if (!slotState.isLoaded) {
                    if (models.size == 1) onLoadClick(models.first().filePath) else showModelPicker = true
                }
            },
            enabled = enabled && !slotState.isLoading && (slotState.isLoaded || models.isNotEmpty()),
            showChevron = !slotState.isLoaded,
            trailingContent = {
                if (slotState.isLoading) {
                    CircularProgressIndicator(progress = { slotState.loadProgress }, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(slotState.backendType.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )

    }
    if (showModelPicker) {
        ModelPickerDialog(
            models = models,
            formatFileSize = formatFileSize,
            onDismiss = { showModelPicker = false },
            onSelect = { model -> showModelPicker = false; onLoadClick(model.filePath) },
        )
    }
}

@Composable
private fun ModelPickerDialog(
    models: List<StoredModel>,
    formatFileSize: (Long) -> String,
    onDismiss: () -> Unit,
    onSelect: (StoredModel) -> Unit
) {
    // 选择列表用底部面板，不放进对话框。
    InfinityBottomSheet(
        show = true,
        scrollContent = true,
        onDismiss = onDismiss,
        title = stringResource(R.string.common_model_picker_title),
    ) {
        models.forEach { model ->
            InfinitySettingsItem(
                horizontalPadding = 0.dp,
                title = model.fileName,
                subtitle = formatFileSize(model.sizeBytes),
                subtitleIsIdentifier = false,
                onClick = { onSelect(model) },
            )
        }
    }
}

@Composable
private fun ModelCard(
    model: StoredModel,
    isLoadedInSlot: Boolean,
    loadedSlot: SlotType?,
    engineEnabled: Boolean,
    formatFileSize: (Long) -> String,
    onLoad: (SlotType) -> Unit,
    onDelete: () -> Unit,
    revision: LocalModelRevision? = null,
    onTogglePin: (() -> Unit)? = null,
) {
    InfinitySettingsSection(
        title = stringResource(R.string.local_models_imported),
        footer = listOfNotNull(
            formatFileSize(model.sizeBytes), model.quantization.ifEmpty { "N/A" }, model.architecture.ifEmpty { null },
            revision?.let { stringResource(R.string.recording_model_revision, it.sha256.take(12)) },
            if (model.quantization.contains("Q8", ignoreCase = true)) stringResource(R.string.local_models_memory_heavy) else null,
            if (isLoadedInSlot && loadedSlot != null) stringResource(when (loadedSlot) {
                SlotType.MAIN -> R.string.local_models_active_main
                SlotType.EMBEDDING -> R.string.settings_batch1_local_active_embeddings
                SlotType.RERANK -> R.string.settings_batch1_local_active_reranker
            }) else null,
        ).joinToString(", "),
    ) {
        InfinitySettingsItem(
            title = model.fileName,
            enabled = engineEnabled,
            onClick = {
                onLoad(if (isLoadedInSlot && loadedSlot != null) loadedSlot else SlotType.MAIN)
            },
        )
        InfinityPillRow(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = InfinitySpacing.RowHorizontal,
                vertical = InfinitySpacing.XSmall,
            ),
        ) {
            InfinityPillButton(
                text = stringResource(R.string.local_models_cd_delete),
                onClick = onDelete,
                tone = InfinityPillTone.DANGER,
                enabled = revision == null,
            )
            InfinityPillButton(
                text = stringResource(if (isLoadedInSlot) R.string.recording_library_ui_unload else R.string.local_models_load),
                onClick = {
                    onLoad(if (isLoadedInSlot && loadedSlot != null) loadedSlot else SlotType.MAIN)
                },
                tone = InfinityPillTone.PRIMARY,
                enabled = engineEnabled,
            )
        }
        if (onTogglePin != null) {
            SettingsToggle(
                title = stringResource(R.string.recording_library_ui_pin_model),
                checked = revision != null,
                onCheckedChange = { onTogglePin() },
            )
        }
    }
}

@Composable
private fun EngineSlotStatus(
    label: String,
    modelName: String?,
    active: Boolean,
    isLoading: Boolean,
    badge: String
) {
    InfinitySettingsRowLayout(
        title = label,
        subtitle = when {
            isLoading -> stringResource(R.string.shared_loading)
            modelName != null -> modelName
            else -> stringResource(R.string.local_models_not_loaded)
        },
                trailingContent = {
            Text(
                text = if (isLoading) "…" else if (active) badge else stringResource(R.string.local_models_slot_idle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
