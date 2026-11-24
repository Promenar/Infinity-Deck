package com.promenar.infinity.ui.settings

import android.app.Application
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.Subject
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.rememberLastNonNull
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.R
import com.promenar.infinity.data.local.inference.LocalModelRevision
import com.promenar.infinity.data.local.inference.RecordingModelPackage
import com.promenar.infinity.data.local.inference.SlotState
import com.promenar.infinity.data.local.inference.SlotType
import com.promenar.infinity.data.local.inference.StoredModel
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityIdentifierText
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityOptionItem
import com.promenar.infinity.ui.common.InfinityOptionSectionHeader
import com.promenar.infinity.ui.common.InfinityPopoverAnchor
import com.promenar.infinity.ui.common.InfinityPopoverSpec
import com.promenar.infinity.ui.common.InfinitySettingsGroup
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.ModelBrandResolver
import com.promenar.infinity.ui.common.SettingsSectionHeader
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.common.bettboxListGroupItem
import com.promenar.infinity.ui.common.brandAccentColor
import com.promenar.infinity.ui.common.infinityPopoverAnchor
import com.promenar.infinity.ui.common.rememberInfinityPopoverAnchor
import com.promenar.infinity.ui.theme.InfinitySpacing
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun LocalModelsScreen(
    onNavigateBack: () -> Unit,
    viewModel: LocalModelsViewModel = viewModel(
        // 折叠时设置页会在两个导航宿主之间迁移，下载与导入交给 Activity 持有。
        viewModelStoreOwner = LocalActivity.current as ViewModelStoreOwner,
        factory = LocalModelsViewModel.factory(LocalContext.current.applicationContext as Application)
    )
) {
    val availableModels by viewModel.availableModels.collectAsState()
    val modelsLoaded by viewModel.modelsLoaded.collectAsState()
    val engineEnabled by viewModel.isEngineEnabled.collectAsState()
    val autoLoadOnStartup by viewModel.autoLoadOnStartup.collectAsState()
    val mainSlot by viewModel.mainSlot.collectAsState()
    val embeddingSlot by viewModel.embeddingSlot.collectAsState()
    val rerankSlot by viewModel.rerankSlot.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val pinned by viewModel.pinnedRevision.collectAsState()
    val savedSlotPaths by viewModel.savedSlotPaths.collectAsState()
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
        autoLoadOnStartup = autoLoadOnStartup,
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
        onAutoLoadOnStartupChange = viewModel::setAutoLoadOnStartup,
        onLoadModel = viewModel::loadModel,
        onUnloadModel = viewModel::unloadModel,
        onDeleteModel = viewModel::deleteModel,
        formatFileSize = viewModel::formatFileSize,
        savedSlotPaths = savedSlotPaths,
        modelsLoaded = modelsLoaded,
    )
}

@Composable
internal fun LocalModelsScreenContent(
    availableModels: List<StoredModel> = emptyList(),
    engineEnabled: Boolean = false,
    autoLoadOnStartup: Boolean = false,
    mainSlot: SlotState = SlotState(),
    embeddingSlot: SlotState = SlotState(),
    rerankSlot: SlotState = SlotState(),
    isImporting: Boolean = false,
    gpuAvailable: Boolean = false,
    onNavigateBack: () -> Unit = {},
    onImport: () -> Unit = {},
    onEngineEnabledChange: (Boolean) -> Unit = {},
    onAutoLoadOnStartupChange: (Boolean) -> Unit = {},
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
    savedSlotPaths: Map<SlotType, String> = emptyMap(),
    modelsLoaded: Boolean = true,
) {
    var openRole by rememberSaveable { mutableStateOf<SlotType?>(null) }
    var openModelPath by rememberSaveable { mutableStateOf<String?>(null) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var deletePath by rememberSaveable { mutableStateOf<String?>(null) }
    val slotAnchors = mapOf(
        SlotType.MAIN to rememberInfinityPopoverAnchor(),
        SlotType.EMBEDDING to rememberInfinityPopoverAnchor(),
        SlotType.RERANK to rememberInfinityPopoverAnchor(),
    )
    val modelMenuAnchors = remember { mutableStateMapOf<String, InfinityPopoverAnchor>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val slots = mapOf(SlotType.MAIN to mainSlot, SlotType.EMBEDDING to embeddingSlot, SlotType.RERANK to rerankSlot)
    val modelByPath = availableModels.associateBy(StoredModel::filePath)
    val showDownload = modelsLoaded && onInstall != null && availableModels.none { it.fileName == RecordingModelPackage.filename }
    val downloadRows = if (showDownload) 1 else 0
    val libraryRows = availableModels.size + downloadRows

    InfinitySettingsPageLayout(
        title = stringResource(R.string.local_models_title),
        onBack = onNavigateBack,
    ) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth(), contentPadding = padding) {
            item {
                val topFooter = if (engineEnabled) {
                    stringResource(
                        if (autoLoadOnStartup) R.string.local_models_auto_load_footer_on
                        else R.string.local_models_auto_load_footer_off,
                    )
                } else {
                    null
                }
                InfinitySettingsGroup(footer = topFooter) {
                    SettingsToggle(
                        title = stringResource(R.string.local_models_inference),
                        description = if (gpuAvailable) "llama.cpp · Vulkan" else "llama.cpp · CPU",
                        icon = Icons.Rounded.Memory,
                        checked = engineEnabled,
                        onCheckedChange = onEngineEnabledChange,
                    )
                    HorizontalDivider()
                    SettingsToggle(
                        title = stringResource(R.string.local_models_auto_load_on_startup),
                        description = stringResource(
                            if (autoLoadOnStartup) R.string.local_models_auto_load_on_startup_on
                            else R.string.local_models_auto_load_on_startup_off,
                        ),
                        icon = Icons.Rounded.RestartAlt,
                        checked = autoLoadOnStartup,
                        enabled = engineEnabled,
                        descriptionMaxLines = 2,
                        onCheckedChange = onAutoLoadOnStartupChange,
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
                    Surface(
                        onClick = onImport,
                        enabled = !isImporting,
                        shape = CircleShape,
                        color = if (!isImporting) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        contentColor = if (!isImporting) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        modifier = Modifier
                            .padding(end = InfinitySpacing.Small)
                            .minimumInteractiveComponentSize()
                            .heightIn(min = 36.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
                        ) {
                            if (isImporting) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                            }
                            Text(
                                stringResource(if (isImporting) R.string.local_models_importing else R.string.local_models_import),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            item(key = "download") {
                AnimatedVisibility(
                    visible = showDownload,
                    enter = InfinityMotion.expandIn,
                    exit = InfinityMotion.shrinkOut,
                ) {
                    Column(Modifier.bettboxListGroupItem(0, libraryRows.coerceAtLeast(1))) {
                        RecordingModelDownloadRow(downloadProgress, !isImporting, formatFileSize, onInstall ?: {}, onCancelInstall)
                    }
                }
            }
            itemsIndexed(availableModels, key = { _, model -> model.id }) { index, model ->
                Column(Modifier.bettboxListGroupItem(index + downloadRows, libraryRows)) {
                    if (showDownload || index > 0) HorizontalDivider()
                    val label = model.displayName()
                    val fixed = pinned?.path == model.filePath
                    val pinStatus = if (fixed || model.fileName == RecordingModelPackage.filename) {
                        stringResource(if (fixed) R.string.local_models_recording_pinned else R.string.local_models_downloaded)
                    } else {
                        null
                    }
                    InfinitySettingsRowLayout(
                        title = label,
                        icon = Icons.Rounded.Layers,
                        titleMaxLines = 2,
                        subtitleSegments = model.specs(formatFileSize) + listOfNotNull(pinStatus),
                        subtitleMaxLines = 1,
                        trailingContent = {
                            val action = stringResource(R.string.local_models_actions, label)
                            val anchor = remember(model.filePath) {
                                modelMenuAnchors.getOrPut(model.filePath) { InfinityPopoverAnchor() }
                            }
                            IconButton(
                                onClick = {
                                    if (!anchor.consumeRecentDismiss()) {
                                        openModelPath = model.filePath
                                        showDetails = false
                                    }
                                },
                                modifier = Modifier.infinityPopoverAnchor(anchor),
                            ) {
                                Icon(Icons.Rounded.MoreHoriz, contentDescription = action)
                            }
                        },
                    )
                }
            }
            item(key = "library_footer") {
                val lastError = rememberLastNonNull(modelError)
                Column(Modifier.fillMaxWidth()) {
                    AnimatedVisibility(
                        visible = modelError != null,
                        enter = InfinityMotion.expandIn,
                        exit = InfinityMotion.shrinkOut,
                    ) {
                        lastError?.let { err ->
                            Text(
                                stringResource(err),
                                Modifier.padding(horizontal = InfinitySpacing.RowHorizontal).padding(top = InfinitySpacing.FooterTop),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = modelsLoaded && modelError == null && availableModels.isEmpty() && downloadProgress == null,
                        enter = InfinityMotion.expandIn,
                        exit = InfinityMotion.shrinkOut,
                    ) {
                        BettboxListGroup(modifier = Modifier.padding(top = 4.dp)) {
                            InfinitySettingsRowLayout(
                                title = stringResource(R.string.local_models_empty_library),
                                icon = Icons.Rounded.FolderOpen,
                                titleMaxLines = 3,
                            )
                        }
                    }
                    Spacer(Modifier.height(InfinitySpacing.SectionGap))
                }
            }
            item {
                SettingsSectionHeader(
                    stringResource(R.string.local_models_assignment),
                    modifier = Modifier.padding(
                        start = InfinitySpacing.RowHorizontal,
                        bottom = InfinitySpacing.SectionHeaderBottom,
                    ),
                )
                InfinitySettingsGroup(
                    footer = stringResource(
                        when {
                            !engineEnabled -> R.string.local_models_engine_disabled
                            autoLoadOnStartup -> R.string.local_models_assignment_footer
                            else -> R.string.local_models_assignment_footer_manual
                        },
                    ),
                ) {
                    slots.entries.forEachIndexed { index, (role, state) ->
                        if (index > 0) HorizontalDivider()
                        val savedPath = savedSlotPaths[role]
                        val slotAnchor = slotAnchors.getValue(role)
                        val subtitle = modelByPath[state.modelPath]?.displayName()
                            ?: state.modelName.takeIf(String::isNotBlank)?.substringBeforeLast('.')
                            ?: savedPath?.let { path ->
                                modelByPath[path]?.displayName() ?: File(path).nameWithoutExtension
                            }
                            ?: stringResource(role.description)
                        val status = when {
                            engineEnabled && state.isLoading -> R.string.shared_loading
                            engineEnabled && state.isLoaded -> R.string.local_models_loaded
                            savedPath != null -> R.string.local_models_saved_status
                            else -> R.string.local_models_unassigned
                        }
                        InfinitySettingsItem(
                            title = stringResource(role.title),
                            subtitle = subtitle,
                            icon = role.icon,
                            titleMaxLines = 2,
                            subtitleMaxLines = 2,
                            modifier = Modifier.infinityPopoverAnchor(slotAnchor),
                            onClick = { if (!slotAnchor.consumeRecentDismiss()) openRole = role },
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
            anchor = slotAnchors.getValue(role),
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
            popover = modelMenuAnchors[model.filePath]?.let { InfinityPopoverSpec(it, width = 320.dp) },
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
                InfinityOptionItem(
                    icon = Icons.Rounded.Description,
                    title = stringResource(R.string.local_models_details),
                    onClick = { showDetails = true },
                    role = Role.Button,
                )
                if (onTogglePin != null && model.supportsAssignment(SlotType.MAIN)) {
                    InfinityOptionItem(
                        icon = Icons.Rounded.PushPin,
                        title = stringResource(if (fixed) R.string.local_models_unpin else R.string.recording_library_ui_pin_model),
                        enabled = pinned == null || fixed,
                        titleMaxLines = 2,
                        selected = fixed,
                        onClick = { onTogglePin(model.filePath); openModelPath = null },
                        role = Role.Button,
                    )
                }
                if (!fixed) {
                    InfinityOptionItem(
                        icon = Icons.Rounded.DeleteOutline,
                        title = stringResource(R.string.local_models_delete),
                        destructive = true,
                        onClick = { deletePath = model.filePath; openModelPath = null },
                        role = Role.Button,
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
    val lastProgress = rememberLastNonNull(progress)
    Column(Modifier.animateContentSize(InfinityMotion.contentSizeSpec())) {
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
                AnimatedContent(
                    targetState = progress != null,
                    transitionSpec = { InfinityMotion.fadeThrough() },
                    label = "recording_model_download_action",
                ) { active ->
                    InfinityPillButton(
                        text = stringResource(if (!active) R.string.local_models_download else R.string.common_btn_cancel),
                        onClick = if (!active) onInstall else onCancel,
                        tone = if (!active) InfinityPillTone.PRIMARY else InfinityPillTone.NEUTRAL,
                        enabled = enabled || active,
                        compact = true,
                    )
                }
            },
        )
        AnimatedVisibility(
            visible = progress != null,
            enter = InfinityMotion.expandIn,
            exit = InfinityMotion.shrinkOut,
        ) {
            lastProgress?.let { shownProgress ->
                Row(
                    Modifier.fillMaxWidth().padding(
                        start = InfinitySpacing.RowHorizontal, end = InfinitySpacing.RowHorizontal, bottom = InfinitySpacing.Medium,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinearProgressIndicator(
                        progress = { shownProgress.coerceIn(0, 100) / 100f },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.local_models_progress, shownProgress),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
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
    anchor: InfinityPopoverAnchor? = null,
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
        popover = anchor?.let { InfinityPopoverSpec(it, width = 360.dp) },
    ) {
        val sheetPhase = when {
            !engineEnabled -> 0
            state.isLoading -> 1
            else -> 2
        }
        AnimatedContent(
            targetState = sheetPhase,
            transitionSpec = { InfinityMotion.fadeThrough() },
            label = "local_model_assignment_phase",
        ) { phase ->
            Column(Modifier.animateContentSize(InfinityMotion.contentSizeSpec())) {
                if (phase == 0) {
                    SheetHint(stringResource(R.string.local_models_engine_disabled))
                    InfinityOptionItem(
                        icon = Icons.Rounded.Memory,
                        title = stringResource(R.string.local_models_enable_engine),
                        onClick = onEnable,
                        role = Role.Button,
                    )
                } else if (phase == 1) {
                    LinearProgressIndicator(progress = { state.loadProgress }, modifier = Modifier.fillMaxWidth())
                    SheetHint(stringResource(R.string.shared_loading))
                } else {
            if (state.error != null) SheetHint(stringResource(R.string.local_models_load_failed), error = true)
            val options = models.filter { pinned == null || pinned.path == it.filePath }
            if (options.isNotEmpty()) {
                InfinityOptionSectionHeader(stringResource(R.string.local_models_available_models))
                val pinnedLabel = stringResource(R.string.local_models_recording_pinned)
                options.forEach { model ->
                    val isLoaded = state.isLoaded && model.filePath == state.modelPath
                    val brand = ModelBrandResolver.brandKey(model.displayName(), model.architecture)
                    val subtitleSegments = buildList {
                        addAll(model.specs(formatFileSize))
                        if (pinned?.path == model.filePath) add(pinnedLabel)
                    }
                    InfinityOptionItem(
                        icon = Icons.Rounded.Layers,
                        title = model.displayName(),
                        subtitleSegments = subtitleSegments,
                        accentColor = brandAccentColor(brand),
                        selected = isLoaded,
                        onClick = {
                            if (!isLoaded) onLoad(model.filePath)
                            onClose()
                        },
                    )
                }
            }
            if (state.isLoaded) {
                InfinityOptionSectionHeader(stringResource(R.string.local_models_slot_actions))
                InfinityOptionItem(
                    icon = Icons.Rounded.VerticalAlignTop,
                    title = stringResource(R.string.local_models_unload),
                    onClick = onUnload,
                    role = Role.Button,
                )
            }
            if (options.isEmpty() && !state.isLoaded) {
                SheetHint(stringResource(
                    if (pinned == null) R.string.local_models_no_compatible else R.string.recording_model_locked,
                ))
                val seeModelLibrary = role == SlotType.MAIN
                InfinityOptionItem(
                    icon = if (seeModelLibrary) Icons.Rounded.Layers else Icons.Rounded.Add,
                    title = stringResource(if (seeModelLibrary) R.string.local_models_show_library else R.string.local_models_import),
                    onClick = if (seeModelLibrary) onShowLibrary else onImport,
                    enabled = seeModelLibrary || importEnabled,
                    role = Role.Button,
                )
            }
                }
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
