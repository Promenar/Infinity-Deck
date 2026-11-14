package com.promenar.infinity.ui.settings

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.Subject
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.R
import com.promenar.infinity.data.local.inference.LocalModelRevision
import com.promenar.infinity.data.local.inference.RecordingModelPackage
import com.promenar.infinity.data.local.inference.SlotState
import com.promenar.infinity.data.local.inference.SlotType
import com.promenar.infinity.data.local.inference.StoredModel
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityIdentifierText
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinitySettingsGroup
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.SettingsSectionHeader
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.common.bettboxListGroupItem
import com.promenar.infinity.ui.theme.InfinitySpacing
import kotlinx.coroutines.launch

@Composable
fun LocalModelsScreen(
    onNavigateBack: () -> Unit,
    viewModel: LocalModelsViewModel = viewModel(
        factory = LocalModelsViewModel.factory(LocalContext.current.applicationContext as Application)
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
    formatFileSize: (Long) -> String = { it.toString() },
    onInstall: (() -> Unit)? = null,
    onCancelInstall: () -> Unit = {},
    onTogglePin: ((String) -> Unit)? = null,
    pinned: LocalModelRevision? = null,
    modelError: Int? = null,
    downloadProgress: Int? = null,
) {
    var openRole by rememberSaveable { mutableStateOf<SlotType?>(null) }
    var openModelPath by rememberSaveable { mutableStateOf<String?>(null) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var deletePath by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val slots = mapOf(SlotType.MAIN to mainSlot, SlotType.EMBEDDING to embeddingSlot, SlotType.RERANK to rerankSlot)
    val modelByPath = availableModels.associateBy(StoredModel::filePath)
    val showDownload = onInstall != null && availableModels.none { it.fileName == RecordingModelPackage.filename }
    val downloadRows = if (showDownload) 1 else 0
    val libraryRows = availableModels.size + downloadRows

    InfinitySettingsPageLayout(
        title = stringResource(R.string.local_models_title),
        onBack = onNavigateBack,
    ) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth(), contentPadding = padding) {
            item {
                InfinitySettingsGroup {
                    SettingsToggle(
                        title = stringResource(R.string.local_models_inference),
                        description = if (gpuAvailable) "llama.cpp · Vulkan" else "llama.cpp · CPU",
                        icon = Icons.Rounded.Memory,
                        checked = engineEnabled,
                        onCheckedChange = onEngineEnabledChange,
                    )
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = InfinitySpacing.RowHorizontal),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsSectionHeader(
                        title = stringResource(R.string.local_models_library),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onImport, enabled = !isImporting) {
                        if (isImporting) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                        }
                        Text(
                            stringResource(if (isImporting) R.string.local_models_importing else R.string.local_models_import),
                            Modifier.padding(start = InfinitySpacing.XSmall),
                        )
                    }
                }
            }
            if (showDownload) {
                item(key = "download") {
                    Column(Modifier.bettboxListGroupItem(0, libraryRows)) {
                        RecordingModelDownloadRow(downloadProgress, !isImporting, formatFileSize, requireNotNull(onInstall), onCancelInstall)
                    }
                }
            }
            itemsIndexed(availableModels, key = { _, model -> model.id }) { index, model ->
                Column(Modifier.bettboxListGroupItem(index + downloadRows, libraryRows)) {
                    if (showDownload || index > 0) HorizontalDivider()
                    val label = model.displayName()
                    val fixed = pinned?.path == model.filePath
                    InfinitySettingsRowLayout(
                        title = label,
                        icon = Icons.Rounded.Layers,
                        titleMaxLines = 2,
                        subtitleContent = {
                            InfinityMetaLine(
                                segments = model.specs(formatFileSize),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (fixed || model.fileName == RecordingModelPackage.filename) {
                                Text(
                                    stringResource(if (fixed) R.string.local_models_recording_pinned else R.string.local_models_downloaded),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        trailingContent = {
                            val action = stringResource(R.string.local_models_actions, label)
                            IconButton(onClick = { openModelPath = model.filePath; showDetails = false }) {
                                Icon(Icons.Rounded.MoreHoriz, contentDescription = action)
                            }
                        },
                    )
                }
            }
            item {
                val note = modelError?.let { stringResource(it) }
                    ?: if (availableModels.isEmpty() && downloadProgress == null) stringResource(R.string.local_models_empty_library) else null
                if (note != null) {
                    Text(
                        note,
                        Modifier.padding(horizontal = InfinitySpacing.RowHorizontal).padding(top = InfinitySpacing.FooterTop),
                        color = if (modelError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(InfinitySpacing.SectionGap))
            }
            item {
                SettingsSectionHeader(
                    stringResource(R.string.local_models_assignment),
                    modifier = Modifier.padding(
                        start = InfinitySpacing.RowHorizontal,
                        bottom = InfinitySpacing.SectionHeaderBottom,
                    ),
                )
                InfinitySettingsGroup(footer = if (engineEnabled) null else stringResource(R.string.local_models_engine_disabled)) {
                    slots.entries.forEachIndexed { index, (role, state) ->
                        if (index > 0) HorizontalDivider()
                        val subtitle = modelByPath[state.modelPath]?.displayName()
                            ?: state.modelName.takeIf(String::isNotBlank)?.substringBeforeLast('.')
                            ?: stringResource(role.description)
                        val status = when {
                            engineEnabled && state.isLoading -> R.string.shared_loading
                            engineEnabled && state.isLoaded -> R.string.local_models_loaded
                            else -> R.string.local_models_unassigned
                        }
                        InfinitySettingsItem(
                            title = stringResource(role.title),
                            subtitle = subtitle,
                            icon = role.icon,
                            titleMaxLines = 2,
                            subtitleMaxLines = 2,
                            onClick = { openRole = role },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        stringResource(status),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (engineEnabled && state.isLoaded && !state.isLoading)
                                            MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Icon(
                                        Icons.Rounded.ChevronRight, null,
                                        Modifier.padding(start = 4.dp).size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    openRole?.let { role ->
        val state = slots.getValue(role)
        val models = availableModels.filter { it.supportsAssignment(role) }
        AssignmentSheet(
            role = role,
            state = state,
            models = models,
            pinned = pinned.takeIf { role == SlotType.MAIN },
            engineEnabled = engineEnabled,
            importEnabled = !isImporting,
            formatFileSize = formatFileSize,
            onClose = { openRole = null },
            onEnable = { onEngineEnabledChange(true) },
            onLoad = { onLoadModel(role, it) },
            onUnload = { onUnloadModel(role); openRole = null },
            onImport = { openRole = null; onImport() },
            onShowLibrary = { openRole = null; scope.launch { listState.animateScrollToItem(1) } },
        )
    }

    modelByPath[openModelPath]?.let { model ->
        val fixed = pinned?.path == model.filePath
        InfinityBottomSheet(
            show = true,
            onDismiss = { openModelPath = null },
            title = if (showDetails) stringResource(R.string.local_models_details) else model.displayName(),
            height = InfinitySheetHeight.Content,
            scrollContent = true,
            dragGesturesEnabled = false,
        ) {
            if (showDetails) {
                Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                    Text(model.displayName(), style = MaterialTheme.typography.titleMedium)
                    SelectionContainer {
                        InfinityIdentifierText(
                            text = model.fileName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    InfinityMetaLine(
                        model.specs(formatFileSize) + model.architecture,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (fixed) {
                        SelectionContainer {
                            Text(stringResource(R.string.recording_model_revision, pinned.sha256), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                InfinitySettingsItem(
                    icon = Icons.Rounded.Description,
                    title = stringResource(R.string.local_models_details),
                    onClick = { showDetails = true },
                    horizontalPadding = 0.dp,
                )
                if (onTogglePin != null && model.supportsAssignment(SlotType.MAIN)) {
                    InfinitySettingsItem(
                        icon = Icons.Rounded.PushPin,
                        title = stringResource(if (fixed) R.string.local_models_unpin else R.string.recording_library_ui_pin_model),
                        enabled = pinned == null || fixed,
                        titleMaxLines = 2,
                        onClick = { onTogglePin(model.filePath); openModelPath = null },
                        horizontalPadding = 0.dp,
                    )
                }
                if (!fixed) {
                    InfinitySettingsItem(
                        icon = Icons.Rounded.DeleteOutline,
                        title = stringResource(R.string.local_models_delete),
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = { deletePath = model.filePath; openModelPath = null },
                        horizontalPadding = 0.dp,
                    )
                }
            }
        }
    }

    modelByPath[deletePath]?.let { model ->
        val active = slots.values.any { it.modelPath == model.filePath && it.isLoaded }
        InfinityConfirmDialog(
            title = stringResource(R.string.local_models_delete),
            message = stringResource(
                if (active) R.string.local_models_delete_loaded else R.string.local_models_delete_message,
                model.displayName(),
            ),
            confirmText = stringResource(R.string.local_models_cd_delete),
            onConfirm = { deletePath = null; onDeleteModel(model.filePath) },
            onCancel = { deletePath = null },
            isDestructive = true,
        )
    }
}

@Composable
private fun RecordingModelDownloadRow(
    progress: Int?,
    enabled: Boolean,
    formatFileSize: (Long) -> String,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    InfinitySettingsRowLayout(
        title = stringResource(R.string.recording_library_ui_local_model),
        icon = Icons.Rounded.Layers,
        titleMaxLines = 2,
        subtitle = if (progress == null) stringResource(
            R.string.recording_model_download_details,
            RecordingModelPackage.displayName, formatFileSize(RecordingModelPackage.bytes), RecordingModelPackage.quantization,
        )
            else stringResource(R.string.local_models_downloading, RecordingModelPackage.displayName),
        subtitleMaxLines = 2,
        trailingContent = {
            TextButton(onClick = if (progress == null) onInstall else onCancel, enabled = enabled || progress != null) {
                Text(stringResource(if (progress == null) R.string.local_models_download else R.string.common_btn_cancel))
            }
        },
    )
    if (progress != null) {
        Row(
            Modifier.fillMaxWidth().padding(
                start = InfinitySpacing.RowHorizontal, end = InfinitySpacing.RowHorizontal, bottom = InfinitySpacing.Medium,
            ),
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0, 100) / 100f },
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.local_models_progress, progress),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun AssignmentSheet(
    role: SlotType,
    state: SlotState,
    models: List<StoredModel>,
    pinned: LocalModelRevision?,
    engineEnabled: Boolean,
    importEnabled: Boolean,
    formatFileSize: (Long) -> String,
    onClose: () -> Unit,
    onEnable: () -> Unit,
    onLoad: (String) -> Unit,
    onUnload: () -> Unit,
    onImport: () -> Unit,
    onShowLibrary: () -> Unit,
) {
    InfinityBottomSheet(
        show = true,
        onDismiss = onClose,
        title = stringResource(role.title),
        height = InfinitySheetHeight.Content,
        scrollContent = true,
        dragGesturesEnabled = false,
    ) {
        if (!engineEnabled) {
            SheetHint(stringResource(R.string.local_models_engine_disabled))
            InfinitySettingsItem(
                icon = Icons.Rounded.Memory,
                title = stringResource(R.string.local_models_enable_engine),
                onClick = onEnable,
                horizontalPadding = 0.dp,
            )
        } else if (state.isLoading) {
            LinearProgressIndicator(progress = { state.loadProgress }, modifier = Modifier.fillMaxWidth())
            SheetHint(stringResource(R.string.shared_loading))
        } else {
            if (state.error != null) SheetHint(stringResource(R.string.local_models_load_failed), error = true)
            if (state.isLoaded) {
                val name = models.firstOrNull { it.filePath == state.modelPath }?.displayName()
                    ?: state.modelName.substringBeforeLast('.')
                SheetHint(stringResource(R.string.local_models_active_assignment, name))
                InfinitySettingsItem(
                    icon = Icons.Rounded.VerticalAlignTop,
                    title = stringResource(R.string.local_models_unload),
                    onClick = onUnload,
                    horizontalPadding = 0.dp,
                )
            }
            val options = models.filter { pinned == null || pinned.path == it.filePath }
            options.filterNot { state.isLoaded && it.filePath == state.modelPath }.forEach { model ->
                InfinitySettingsItem(
                    icon = Icons.Rounded.Layers,
                    title = model.displayName(),
                    subtitleSegments = model.specs(formatFileSize),
                    titleMaxLines = 2,
                    onClick = { onLoad(model.filePath) },
                    horizontalPadding = 0.dp,
                )
            }
            if (options.isEmpty() && !state.isLoaded) {
                SheetHint(stringResource(
                    if (pinned == null) R.string.local_models_no_compatible else R.string.recording_model_locked,
                ))
                val seeModelLibrary = role == SlotType.MAIN
                InfinitySettingsItem(
                    icon = if (seeModelLibrary) Icons.Rounded.Layers else Icons.Rounded.Add,
                    title = stringResource(if (seeModelLibrary) R.string.local_models_show_library else R.string.local_models_import),
                    onClick = if (seeModelLibrary) onShowLibrary else onImport,
                    enabled = seeModelLibrary || importEnabled,
                    horizontalPadding = 0.dp,
                )
            }
        }
    }
}

@Composable
private fun SheetHint(text: String, error: Boolean = false) {
    Text(
        text, Modifier.padding(vertical = InfinitySpacing.Small),
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun StoredModel.displayName(): String = if (fileName == RecordingModelPackage.filename) {
    RecordingModelPackage.displayName
} else {
    modelName.takeIf(String::isNotBlank) ?: fileName.substringBeforeLast('.')
}

private fun StoredModel.specs(formatSize: (Long) -> String) = listOf(formatSize(sizeBytes), quantization, format)

@get:StringRes
private val SlotType.title: Int get() = when (this) {
    SlotType.MAIN -> R.string.local_models_slot_main
    SlotType.EMBEDDING -> R.string.local_models_slot_embeddings
    SlotType.RERANK -> R.string.local_models_slot_reranker
}

@get:StringRes
private val SlotType.description: Int get() = when (this) {
    SlotType.MAIN -> R.string.local_models_assign_main_description
    SlotType.EMBEDDING -> R.string.local_models_assign_embedding_description
    SlotType.RERANK -> R.string.local_models_assign_rerank_description
}

private val SlotType.icon: ImageVector get() = when (this) {
    SlotType.MAIN -> Icons.AutoMirrored.Rounded.Subject
    SlotType.EMBEDDING -> Icons.Rounded.Hub
    SlotType.RERANK -> Icons.AutoMirrored.Rounded.Sort
}
