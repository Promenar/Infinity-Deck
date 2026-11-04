package com.promenar.infinity.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.share.core.ShareImportItem
import com.promenar.infinity.share.core.ShareImportStatus
import com.promenar.infinity.share.core.ShareRejectReason
import com.promenar.infinity.share.core.SharedFileImporter
import com.promenar.infinity.ui.chat.components.ExternalFoldersPanel
import com.promenar.infinity.ui.chat.components.ResourceExplorerViewModel
import com.promenar.infinity.ui.chat.components.ResourceExplorerWorkspaceMode
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinityIdentifierText
import com.promenar.infinity.ui.common.InfinityRetryButton
import com.promenar.infinity.ui.common.InfinitySearchTopBar
import com.promenar.infinity.ui.common.inspectorTopGap
import com.promenar.infinity.ui.files.FileFolderAction
import com.promenar.infinity.ui.files.FileManagerHostSpec
import com.promenar.infinity.ui.files.FileManagerLeading
import com.promenar.infinity.ui.files.FileManagerPane
import com.promenar.infinity.ui.files.state.FileManagerScope
import com.promenar.infinity.ui.files.state.FileManagerViewModel
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography

/** 会话资源管理器由统一文件组件承载。 */
@Composable
fun ResourceExplorerPage(
    onBack: (() -> Unit)?,
    onOpenGit: () -> Unit,
    gitRefreshKey: Int,
    sessionId: String,
    chatViewModel: ChatViewModel,
    initialFolderPath: String? = null,
    viewModel: ResourceExplorerViewModel = viewModel(
        factory = ResourceExplorerViewModel.factory(
            LocalContext.current.applicationContext as android.app.Application
        )
    )
) {
    val app = LocalContext.current.applicationContext as InfinityApplication
    val controller: FileManagerViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "resource-explorer-$sessionId",
        factory = FileManagerViewModel.factory(app.fileManagerServices, FileManagerScope.Session(sessionId), null),
    )
    val fileState by controller.state.collectAsStateWithLifecycle()
    val workspaceMode by viewModel.workspaceMode.collectAsStateWithLifecycle()
    val workspaceRootUuid by viewModel.workspaceRootUuid.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val importItems by viewModel.importItems.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val chatState by chatViewModel.uiState.collectAsStateWithLifecycle()
    var managerOpen by rememberSaveable(sessionId) { mutableStateOf(false) }
    var initialPathHandled by rememberSaveable(sessionId, initialFolderPath) { mutableStateOf(false) }

    DisposableEffect(sessionId, viewModel) {
        viewModel.loadSession(sessionId)
        onDispose { viewModel.deactivateSession() }
    }
    val boundWorkspace = chatState.session?.takeIf { it.id == sessionId }?.let {
        Triple(it.workspaceId, it.branchPlanJson, it.worktreeDirectory)
    }
    val shownWorkspace = remember(sessionId) { arrayOfNulls<Triple<String?, String?, String?>>(1) }
    LaunchedEffect(boundWorkspace) {
        if (boundWorkspace == null) return@LaunchedEffect
        val previous = shownWorkspace[0]
        shownWorkspace[0] = boundWorkspace
        if (previous != null && previous != boundWorkspace) viewModel.onWorkspaceBindingChanged()
    }
    LaunchedEffect(gitRefreshKey) { controller.refresh() }
    LaunchedEffect(importItems, isImporting) {
        if (importItems.isNotEmpty() && !isImporting) controller.refresh()
    }
    LaunchedEffect(sessionId, initialFolderPath, fileState.location?.id, workspaceMode) {
        val path = initialFolderPath?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val location = fileState.location ?: return@LaunchedEffect
        if (!initialPathHandled && workspaceMode != ResourceExplorerWorkspaceMode.Resolving &&
            workspaceMode !is ResourceExplorerWorkspaceMode.Unavailable
        ) {
            initialPathHandled = true
            controller.openLocation(location.id, path)
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty() && viewModel.workspaceMode.value == ResourceExplorerWorkspaceMode.Private &&
            canImportIntoSessionLocation(sessionId, controller.state.value.location?.id, viewModel.isImporting.value)
        ) viewModel.importDocuments(uris)
    }
    val importLabel = stringResource(R.string.resource_explorer_import_files)
    val externalLabel = stringResource(R.string.mount_title)
    val actions = buildList {
        if (workspaceMode == ResourceExplorerWorkspaceMode.Private &&
            canImportIntoSessionLocation(sessionId, fileState.location?.id, isImporting)
        ) {
            add(FileFolderAction("session-import", Icons.Rounded.UploadFile, importLabel) { _, _ ->
                filePicker.launch(SharedFileImporter.SUPPORTED_MIME_TYPES.toTypedArray())
            })
        }
        add(FileFolderAction("session-external-folders", Icons.Rounded.FolderOpen, externalLabel) { _, _ ->
            managerOpen = true
        })
    }
    ResourceExplorerBody(
        workspaceMode = workspaceMode,
        isLoading = isLoading,
        hasLoadError = loadError != null,
        importItems = importItems,
        isImporting = isImporting,
        onRetryLoad = viewModel::retryLoadSession,
        onRetryImport = viewModel::retryImport,
        onClearImportResults = viewModel::clearImportResults,
        onBack = onBack,
        // 整页覆盖时自行避开状态栏；检视器根层沿用检视器顶部间隔。
        modifier = if (onBack == null) Modifier.padding(top = inspectorTopGap()) else Modifier.statusBarsPadding(),
    ) {
        FileManagerPane(
            controller = controller,
            host = resourceExplorerHost(onBack, onOpenGit, actions),
            modifier = Modifier.fillMaxSize(),
        )
    }
    if (managerOpen) {
        ExternalFoldersPanel(
            sessionId = sessionId,
            workspaceRootUuid = workspaceRootUuid,
            manager = app.externalFolderManager,
            onReindex = null,
            managerOnly = true,
            onManagerDismiss = { managerOpen = false },
        )
    }
}

internal fun resourceExplorerHost(
    onBack: (() -> Unit)?,
    onOpenGit: () -> Unit,
    actions: List<FileFolderAction>,
): FileManagerHostSpec = FileManagerHostSpec(
    leading = onBack?.let { FileManagerLeading.Leave(it) } ?: FileManagerLeading.None,
    allowLocationSwitch = true,
    onOpenGit = onOpenGit,
    folderActions = actions,
)

@Composable
internal fun ResourceExplorerBody(
    workspaceMode: ResourceExplorerWorkspaceMode,
    isLoading: Boolean,
    hasLoadError: Boolean,
    importItems: List<ShareImportItem>,
    isImporting: Boolean,
    onRetryLoad: () -> Unit,
    onRetryImport: (android.net.Uri) -> Unit,
    onClearImportResults: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    filesContent: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize().testTag(UiTags.RESOURCE_EXPLORER_ROOT)) {
        if (onBack != null && (workspaceMode is ResourceExplorerWorkspaceMode.Unavailable ||
                workspaceMode == ResourceExplorerWorkspaceMode.Resolving)
        ) {
            InfinitySearchTopBar(title = stringResource(R.string.resource_explorer_title), search = null, onBack = onBack)
        }
        val shownImports = remember { arrayOfNulls<List<ShareImportItem>>(1) }
        if (importItems.isNotEmpty()) shownImports[0] = importItems
        AnimatedVisibility(visible = importItems.isNotEmpty(), enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
            ImportStatusList(shownImports[0].orEmpty(), isImporting || workspaceMode != ResourceExplorerWorkspaceMode.Private, onRetryImport, onClearImportResults)
        }
        when (workspaceMode) {
            is ResourceExplorerWorkspaceMode.Unavailable -> WorkspaceUnavailableState(workspaceMode, onRetryLoad)
            ResourceExplorerWorkspaceMode.Resolving -> ResourceExplorerSessionState(isLoading, onRetryLoad.takeIf { hasLoadError })
            else -> Box(Modifier.weight(1f).fillMaxWidth()) { filesContent() }
        }
    }
}

@Composable
internal fun WorkspaceUnavailableState(
    mode: ResourceExplorerWorkspaceMode.Unavailable,
    onRetry: () -> Unit,
) {
    val reason = stringResource(R.string.workspace_unavailable_reason, mode.reason)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.RESOURCE_EXPLORER_WORKSPACE_UNAVAILABLE)
            .semantics {
                liveRegion = LiveRegionMode.Assertive
                stateDescription = reason
            }
            .padding(horizontal = InfinitySpacing.XLarge, vertical = InfinitySpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        // 顶栏状态行只显示名称与警示色，原因在这里写明；无障碍即时播报原因。
        Text(
            text = reason,
            style = InfinityTypography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = stringResource(R.string.resource_explorer_unavailable_help),
            style = InfinityTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InfinityRetryButton(
            onClick = onRetry,
            modifier = Modifier.testTag(UiTags.RESOURCE_EXPLORER_RETRY_LOAD),
        )
    }
}

@Composable
private fun ResourceExplorerSessionState(
    isLoading: Boolean,
    onRetry: (() -> Unit)? = null,
) {
    val message = stringResource(
        if (isLoading) R.string.shared_loading
        else R.string.resource_explorer_error_workspace_load_failed,
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.RESOURCE_EXPLORER_SESSION_STATUS)
            .semantics {
                liveRegion = if (isLoading) LiveRegionMode.Polite else LiveRegionMode.Assertive
                stateDescription = message
            }
            .padding(InfinitySpacing.XXLarge),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(InfinitySpacing.Medium))
        }
        Text(
            text = message,
            style = InfinityTypography.bodyMedium,
            color = if (isLoading) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
        if (!isLoading && onRetry != null) {
            Spacer(modifier = Modifier.height(InfinitySpacing.Medium))
            InfinityRetryButton(
                onClick = onRetry,
                modifier = Modifier.testTag(UiTags.RESOURCE_EXPLORER_RETRY_LOAD),
            )
        }
    }
}

@Composable
internal fun ImportStatusList(
    items: List<ShareImportItem>,
    isImporting: Boolean,
    onRetry: (android.net.Uri) -> Unit,
    onClear: () -> Unit,
) {
    BettboxListGroup(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(InfinitySpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.resource_explorer_import_status),
                    style = InfinityTypography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onClear,
                    enabled = !isImporting,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.testTag(UiTags.RESOURCE_EXPLORER_CLEAR_RESULTS),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.resource_explorer_clear_results))
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 224.dp)
                    .testTag(UiTags.RESOURCE_EXPLORER_IMPORT_RESULTS),
            ) {
                items(items, key = { it.uri.toString() }) { item ->
                    ImportStatusRow(
                        item = item,
                        isImporting = isImporting,
                        onRetry = onRetry,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun ImportStatusRow(
    item: ShareImportItem,
    isImporting: Boolean,
    onRetry: (android.net.Uri) -> Unit,
) {
    val statusText = when (item.status) {
        ShareImportStatus.Pending -> stringResource(R.string.resource_explorer_status_pending)
        ShareImportStatus.Importing -> stringResource(R.string.resource_explorer_status_importing)
        ShareImportStatus.Created -> stringResource(R.string.resource_explorer_status_created)
        ShareImportStatus.Rejected -> stringResource(
            R.string.resource_explorer_status_rejected,
            rejectReasonLabel(item.reason),
        )
    }
    val statusColor = if (item.status == ShareImportStatus.Rejected) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    // 行尾重试是 48dp 图标，任何宽度与字号下都与状态并排，不再整行堆叠。
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = InfinitySpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        ) {
            Icon(
                imageVector = when (item.status) {
                    ShareImportStatus.Pending, ShareImportStatus.Importing -> Icons.Rounded.HourglassTop
                    ShareImportStatus.Created -> Icons.Rounded.CheckCircle
                    ShareImportStatus.Rejected -> Icons.Rounded.ErrorOutline
                },
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall)) {
                InfinityIdentifierText(
                    text = item.displayName,
                    style = InfinityTypography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = statusText,
                    style = InfinityTypography.bodySmall,
                    color = statusColor,
                    modifier = Modifier.testTag(
                        UiTags.resourceExplorerImportStatus(item.uri.toString()),
                    ),
                )
            }
        }
        if (shouldOfferImportRetry(item)) {
            ImportRetryAction(item, isImporting, onRetry)
        }
    }
}

@Composable
private fun ImportRetryAction(
    item: ShareImportItem,
    isImporting: Boolean,
    onRetry: (android.net.Uri) -> Unit,
) {
    val actionLabel = stringResource(R.string.resource_explorer_retry_document, item.displayName)
    IconButton(
        onClick = { onRetry(item.uri) },
        enabled = !isImporting,
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        // 点击语义带上文档名，读屏能分清是哪一项的重试。
        modifier = Modifier
            .semantics { onClick(label = actionLabel) { if (!isImporting) onRetry(item.uri); !isImporting } }
            .testTag(UiTags.resourceExplorerImportRetry(item.uri.toString())),
    ) {
        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.shared_btn_retry))
    }
}

@Composable
private fun rejectReasonLabel(reason: ShareRejectReason?): String = stringResource(
    when (reason) {
        ShareRejectReason.TargetRequired -> R.string.share_import_reason_target
        ShareRejectReason.UnsupportedMime -> R.string.share_import_reason_mime
        ShareRejectReason.MimeMismatch -> R.string.share_import_reason_mismatch
        ShareRejectReason.InvalidName -> R.string.share_import_reason_name
        ShareRejectReason.PermissionDenied -> R.string.share_import_reason_permission
        ShareRejectReason.EmptyFile -> R.string.share_import_reason_empty
        ShareRejectReason.ItemTooLarge -> R.string.share_import_reason_item_large
        ShareRejectReason.BatchTooLarge -> R.string.share_import_reason_batch_large
        ShareRejectReason.ReadFailed -> R.string.share_import_reason_read
        ShareRejectReason.WriteFailed -> R.string.share_import_reason_write
        ShareRejectReason.IndexScheduleFailed -> R.string.share_import_reason_index
        ShareRejectReason.LinuxUnavailable -> R.string.share_import_ext_reason_linux_unavailable
        ShareRejectReason.LinuxPathInvalid -> R.string.share_import_ext_reason_linux_path
        null -> R.string.resource_explorer_reason_unknown
    },
)

internal fun shouldOfferImportRetry(item: ShareImportItem): Boolean =
    item.status == ShareImportStatus.Rejected && item.reason?.retryable == true
