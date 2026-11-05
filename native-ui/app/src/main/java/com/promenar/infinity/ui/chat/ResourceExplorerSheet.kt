package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.ui.chat.components.ExternalFoldersPanel
import com.promenar.infinity.ui.chat.components.ResourceExplorerViewModel
import com.promenar.infinity.ui.chat.components.ResourceExplorerWorkspaceMode
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
    // 导入统一走文件组件“新建 → 从设备导入…”：写入会话空间的文档由工作区索引事件自动解析，这里不再另设导入入口。
    val externalLabel = stringResource(R.string.mount_title)
    val actions = listOf(
        FileFolderAction("session-external-folders", Icons.Rounded.FolderOpen, externalLabel) { _, _ ->
            managerOpen = true
        },
    )
    ResourceExplorerBody(
        workspaceMode = workspaceMode,
        isLoading = isLoading,
        hasLoadError = loadError != null,
        onRetryLoad = viewModel::retryLoadSession,
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
    onRetryLoad: () -> Unit,
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
