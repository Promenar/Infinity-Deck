package com.promenar.infinity.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.breakableIdentifier
import com.promenar.infinity.ui.common.InfinitySheetHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.storage.RealPathMountSupport
import com.promenar.infinity.data.workspace.CreateFolderResult
import com.promenar.infinity.data.workspace.FolderNameError
import com.promenar.infinity.data.workspace.LinuxHomeAvailability
import com.promenar.infinity.data.workspace.WorkspaceDirectoryBrowser
import com.promenar.infinity.data.workspace.WorkspaceFolder
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.ui.common.FolderBreadcrumbsBar
import com.promenar.infinity.ui.common.FolderCrumb
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityRetryButton
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.theme.InfinityMotion
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.hierarchicalTransition
import com.promenar.infinity.ui.theme.forReading
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** 选择器当前所在层级：起始页、项目根、某个共享卷内的目录或 Linux 环境内的目录。 */
sealed interface WorkspacePickerPage {
    data object Home : WorkspacePickerPage
    data object Projects : WorkspacePickerPage
    data class Shared(val volume: File, val path: File) : WorkspacePickerPage
    /** [path] 为容器内目录；null 为可选起点（`/root`、`/home` 等）列表。 */
    data class Linux(val path: String?) : WorkspacePickerPage
}

/** 页面层级：首页为 0，项目、存储卷根与 Linux 起点列表为 1，目录按路径段数递增。 */
internal val WorkspacePickerPage.depth: Int
    get() = when (this) {
        WorkspacePickerPage.Home -> 0
        WorkspacePickerPage.Projects -> 1
        is WorkspacePickerPage.Shared -> 1 + path.path.removePrefix(volume.path).split('/').count(String::isNotEmpty)
        is WorkspacePickerPage.Linux -> 1 + linuxSegments(path).size
    }

private fun linuxSegments(path: String?): List<String> = path.orEmpty().split('/').filter(String::isNotEmpty)

private const val LINUX_CRUMB_PREFIX = "linux:"

data class WorkspacePickerUiState(
    val page: WorkspacePickerPage = WorkspacePickerPage.Home,
    val registered: List<WorkspaceEntity> = emptyList(),
    val currentWorkspaceId: String? = null,
    /** 为 true 时列出“本会话文件夹”，[currentWorkspaceId] 为 null 即表示当前使用它。 */
    val showPrivate: Boolean = false,
    /** “本会话文件夹”一行的标题；为 null 时显示“本会话文件夹”。 */
    val privateTitle: String? = null,
    val sharedSupported: Boolean = false,
    val sharedPermission: Boolean = false,
    val sharedRoots: List<File> = emptyList(),
    val folders: List<WorkspaceFolder> = emptyList(),
    val loading: Boolean = false,
    val loadError: String? = null,
    val selectedProject: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    /** 侧载渠道才有 Linux 主目录；未就绪时入口可见但不可选。 */
    val linuxHome: LinuxHomeAvailability = LinuxHomeAvailability.UNSUPPORTED,
    /** 会话保存的运行模式；内置模式下 Linux 中的文件夹灰显，null 表示不针对具体会话。 */
    val runMode: SessionRunMode? = null,
    /** 只浏览共享存储卷（挂载外部文件夹选路径）：首页只列存储卷，不列工作区登记、项目与 Linux。 */
    val sharedOnly: Boolean = false,
)

/** Linux 主目录；选中它时沿用“Linux 主目录”显示名，其它目录取目录名。 */
private const val LINUX_HOME_DIRECTORY = "/root"

/** 本会话文件夹在 Linux 中的挂载点。 */
private const val LINUX_SESSION_DIRECTORY = "/home/session"

/** 选定后由 [WorkspaceDirectoryBrowser] 登记；共享卷之下的路径段转成面包屑，根级“位置”回到起始页。 */
internal fun WorkspacePickerPage.crumbs(volumeLabel: (File) -> String, projectsLabel: String, linuxLabel: String = "Linux"): List<FolderCrumb> = when (this) {
    WorkspacePickerPage.Home -> emptyList()
    is WorkspacePickerPage.Linux -> {
        val segments = linuxSegments(path)
        listOf(FolderCrumb(LINUX_CRUMB_PREFIX, linuxLabel)) + segments.mapIndexed { index, name ->
            FolderCrumb(LINUX_CRUMB_PREFIX + "/" + segments.take(index + 1).joinToString("/"), name)
        }
    }
    WorkspacePickerPage.Projects -> listOf(FolderCrumb("projects", projectsLabel))
    is WorkspacePickerPage.Shared -> {
        val relative = volume.toPath().relativize(path.toPath()).toString()
        val segments = relative.split('/').filter(String::isNotEmpty)
        listOf(FolderCrumb(volume.path, volumeLabel(volume))) + segments.mapIndexed { index, name ->
            FolderCrumb(File(volume, segments.take(index + 1).joinToString("/")).path, name)
        }
    }
}

/**
 * 深层目录只显示首级、省略号与当前目录，保证当前所在目录在窄屏上可见；
 * 省略号指向当前目录的上一级，其余层级可经返回按钮逐级回到。
 */
internal fun List<FolderCrumb>.collapsedForDisplay(): List<FolderCrumb> =
    if (size <= 3) this else listOf(first(), this[size - 2].copy(name = "…"), last())

/** 点击 id 为 [crumbId] 的面包屑（null 为根级“位置”）后所在的页面。 */
internal fun WorkspacePickerPage.navigateToCrumb(crumbId: String?): WorkspacePickerPage = when {
    crumbId == null || this == WorkspacePickerPage.Home -> WorkspacePickerPage.Home
    this is WorkspacePickerPage.Linux && crumbId.startsWith(LINUX_CRUMB_PREFIX) ->
        WorkspacePickerPage.Linux(crumbId.removePrefix(LINUX_CRUMB_PREFIX).ifEmpty { null })
    this is WorkspacePickerPage.Shared -> {
        val target = File(crumbId)
        if (target.toPath().startsWith(volume.toPath())) copy(path = target) else this
    }
    else -> this
}

internal fun WorkspacePickerPage.parent(): WorkspacePickerPage = when (this) {
    WorkspacePickerPage.Home, WorkspacePickerPage.Projects -> WorkspacePickerPage.Home
    is WorkspacePickerPage.Shared -> if (path == volume) WorkspacePickerPage.Home else copy(path = path.parentFile ?: volume)
    // 起点目录（如 /home）的上一级回到起点列表，而不是不可选的 `/`。
    is WorkspacePickerPage.Linux -> when {
        path == null -> WorkspacePickerPage.Home
        linuxSegments(path).size <= 1 -> WorkspacePickerPage.Linux(null)
        else -> WorkspacePickerPage.Linux(path.substringBeforeLast('/'))
    }
}

@Composable
internal fun sharedVolumeLabel(roots: List<File>, volume: File): String =
    if (roots.indexOf(volume) <= 0) stringResource(R.string.workspace_picker_shared_volume)
    else stringResource(R.string.workspace_picker_shared_volume_named, volume.name)

/**
 * 可复用的工作区选择器：列出已登记的工作区，并可逐级浏览 Infinity Workbench 项目文件夹与（仅侧载渠道的）共享存储卷。
 * 选定目录后登记并回调 [onPicked]；未授权时共享存储一栏显示授权引导。
 */
@Composable
fun WorkspacePickerSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    onPicked: (WorkspaceEntity) -> Unit,
    currentWorkspaceId: String? = null,
    /** 非 null 时可选回本会话文件夹。 */
    onPickPrivate: (() -> Unit)? = null,
    /** 智能体默认工作区等非会话场景下，“本会话文件夹”一行的标题。 */
    privateTitle: String? = null,
    /** 会话保存的运行模式；智能体默认工作区等非会话场景为 null。 */
    runMode: SessionRunMode? = null,
) {
    if (!show) return
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = context.applicationContext as? InfinityApplication ?: return
    val browser = app.workspaceDirectoryBrowser
    val registeredFlow = remember(app) { runCatching { app.database.workspaceDao().observeAll() }.getOrElse { flowOf(emptyList()) } }
    val registered by registeredFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var page by remember { mutableStateOf<WorkspacePickerPage>(WorkspacePickerPage.Home) }
    var permission by remember { mutableStateOf(browser.hasSharedPermission()) }
    var roots by remember { mutableStateOf(browser.sharedRoots()) }
    var folders by remember { mutableStateOf<List<WorkspaceFolder>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selectedProject by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<FolderNameError?>(null) }
    var linuxHome by remember { mutableStateOf(LinuxHomeAvailability.UNSUPPORTED) }
    LaunchedEffect(browser) { linuxHome = browser.linuxHomeAvailability() }
    val linuxHomeName = stringResource(R.string.workspace_picker_linux_home_name)

    val refreshPermission by rememberUpdatedState {
        permission = browser.hasSharedPermission()
        roots = browser.sharedRoots()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshPermission() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(page, reload, permission) {
        folders = emptyList()
        loadError = null
        val current = page
        if (current == WorkspacePickerPage.Home) return@LaunchedEffect
        loading = true
        try {
            folders = when (current) {
                WorkspacePickerPage.Projects -> browser.listProjects()
                is WorkspacePickerPage.Shared -> browser.listShared(current.path)
                is WorkspacePickerPage.Linux -> current.path?.let { browser.listLinux(it) } ?: browser.linuxRoots()
                WorkspacePickerPage.Home -> emptyList()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            loadError = failure.message ?: failure.javaClass.simpleName
        } finally {
            loading = false
        }
    }

    fun register(action: suspend () -> WorkspaceEntity) {
        busy = true
        error = null
        scope.launch {
            try {
                onPicked(action())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = (failure as? ExternalFolderException)?.message ?: failure.message ?: failure.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }

    val state = WorkspacePickerUiState(
        page = page, registered = registered, currentWorkspaceId = currentWorkspaceId, showPrivate = onPickPrivate != null,
        privateTitle = privateTitle,
        sharedSupported = browser.sharedSupported, sharedPermission = permission, sharedRoots = roots,
        folders = folders, loading = loading, loadError = loadError, selectedProject = selectedProject,
        busy = busy, error = error, linuxHome = linuxHome, runMode = runMode,
    )

    InfinityBottomSheet(
        show = true,
        onDismiss = onDismiss,
        title = stringResource(R.string.workspace_picker_title),
        height = InfinitySheetHeight.Full,
        dismissEnabled = !busy,
        dragGesturesEnabled = false,
    ) {
        // 系统返回先逐级回到上一层目录，回到首页后再关闭面板。
        BackHandler(enabled = page != WorkspacePickerPage.Home && !busy) {
            page = page.parent()
            selectedProject = null
            error = null
        }
        WorkspacePickerContent(
            state = state,
            onNavigate = { next ->
                page = next
                selectedProject = null
                error = null
            },
            onPickRegistered = onPicked,
            onPickPrivate = { onPickPrivate?.invoke() },
            onPickLinuxHome = { onPicked(it) },
            onSelectProject = { selectedProject = it },
            onCreate = {
                createError = null
                creating = true
            },
            onUseFolder = {
                when (val current = page) {
                    WorkspacePickerPage.Projects -> selectedProject?.let { name -> register { browser.registerProject(name) } }
                    is WorkspacePickerPage.Shared -> register { browser.registerShared(current.path) }
                    is WorkspacePickerPage.Linux -> current.path?.let { path ->
                        register { browser.registerLinux(path, linuxHomeName.takeIf { path == LINUX_HOME_DIRECTORY }) }
                    }
                    WorkspacePickerPage.Home -> Unit
                }
            },
            onGrantPermission = {
                try {
                    RealPathMountSupport.permissionIntent(context)?.let(context::startActivity)
                } catch (_: Exception) {
                    error = resources.getString(R.string.mount_operation_error)
                }
            },
            onRetry = { reload++ },
        )
    }

    if (creating) {
        val project = page == WorkspacePickerPage.Projects
        WorkspaceNameDialog(
            title = stringResource(if (project) R.string.workspace_picker_new_project else R.string.workspace_picker_new_folder),
            label = stringResource(if (project) R.string.workspace_picker_project_name else R.string.workspace_picker_folder_name),
            confirmLabel = stringResource(R.string.workspace_picker_create),
            error = createError?.let { folderNameErrorText(it, project) },
            busy = busy,
            onValueChange = { createError = null },
            onDismiss = { creating = false },
            onConfirm = { name ->
                val current = page
                busy = true
                scope.launch {
                    try {
                        val result = when (current) {
                            WorkspacePickerPage.Projects -> browser.createProject(name)
                            is WorkspacePickerPage.Shared -> browser.createSharedFolder(current.path, name)
                            is WorkspacePickerPage.Linux -> current.path?.let { browser.createLinuxFolder(it, name) }
                            WorkspacePickerPage.Home -> null
                        }
                        when (result) {
                            is CreateFolderResult.Created -> {
                                creating = false
                                reload++
                                if (current == WorkspacePickerPage.Projects) selectedProject = result.folder.name
                                else if (current is WorkspacePickerPage.Shared) page = current.copy(path = result.folder.path)
                                else if (current is WorkspacePickerPage.Linux) page = WorkspacePickerPage.Linux(result.folder.path.path)
                            }
                            is CreateFolderResult.Rejected -> createError = result.error
                            null -> creating = false
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        creating = false
                        error = failure.message ?: failure.javaClass.simpleName
                    } finally {
                        busy = false
                    }
                }
            },
        )
    }
}

/** 选择器的无状态内容，供截图预览与 [WorkspacePickerSheet] 共用。 */
@Composable
fun WorkspacePickerContent(
    state: WorkspacePickerUiState,
    modifier: Modifier = Modifier,
    onNavigate: (WorkspacePickerPage) -> Unit = {},
    onPickRegistered: (WorkspaceEntity) -> Unit = {},
    onPickPrivate: () -> Unit = {},
    onPickLinuxHome: (WorkspaceEntity) -> Unit = {},
    onSelectProject: (String) -> Unit = {},
    onCreate: () -> Unit = {},
    onUseFolder: () -> Unit = {},
    onGrantPermission: () -> Unit = {},
    onRetry: () -> Unit = {},
    /** 面包屑首段名称，null 时为“工作区”。 */
    rootTitle: String? = null,
    /** 非 null 时首页也显示页头，返回按钮离开选择器（嵌在其它面板中使用时）。 */
    onExitRoot: (() -> Unit)? = null,
) = Column(modifier.fillMaxSize()) {
    val page = state.page
    val projectsLabel = stringResource(R.string.workspace_picker_shared_folder)
    val volumeLabels = state.sharedRoots.associateWith { sharedVolumeLabel(state.sharedRoots, it) }
    if (page != WorkspacePickerPage.Home || onExitRoot != null) {
        val crumbs = page.crumbs({ volumeLabels[it] ?: it.name }, projectsLabel, stringResource(R.string.workspace_picker_linux_root)).collapsedForDisplay()
        FolderBreadcrumbsBar(
            folderStack = crumbs,
            rootTitle = rootTitle ?: stringResource(R.string.workspace_picker_root),
            onNavigateBack = { onNavigate(page.parent()) },
            onNavigateToCrumb = { index -> onNavigate(page.navigateToCrumb(crumbs.getOrNull(index - 1)?.id)) },
            modifier = Modifier.testTag("workspace_picker_breadcrumbs"),
            backButtonTag = "workspace_picker_back",
            onExitRoot = onExitRoot,
            actions = {
                // 与返回按钮对称地放在页头右侧，不再单独占一行；Linux 起点列表的父目录是不可选的 `/`，首页没有当前目录，都不提供新建。
                if (page != WorkspacePickerPage.Linux(null) && page != WorkspacePickerPage.Home) {
                    Spacer(Modifier.width(InfinitySpacing.Small))
                    FilledTonalIconButton(
                        onClick = onCreate,
                        enabled = !state.busy && !state.loading && state.loadError == null,
                        modifier = Modifier.size(40.dp).testTag("workspace_picker_create"),
                    ) {
                        Icon(
                            Icons.Rounded.CreateNewFolder,
                            contentDescription = stringResource(
                                if (page == WorkspacePickerPage.Projects) R.string.workspace_picker_new_project else R.string.workspace_picker_new_folder,
                            ),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
        )
    }
    AnimatedVisibility(
        visible = page != WorkspacePickerPage.Home && (state.loading || state.busy),
        enter = InfinityMotion.expandIn,
        exit = InfinityMotion.shrinkOut,
    ) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
    // 进入子目录与返回上级按层级方向横向切换；离场页保留自己的列表快照。
    AnimatedContent(
        targetState = state,
        contentKey = { it.page },
        transitionSpec = { hierarchicalTransition { it.page.depth } },
        modifier = Modifier.weight(1f).fillMaxWidth(),
        label = "workspace_picker_page",
    ) { state ->
        val page = state.page
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("workspace_picker_list"),
            contentPadding = PaddingValues(bottom = InfinitySpacing.Large),
        ) {
            when (page) {
                WorkspacePickerPage.Home -> homeItems(state, volumeLabels, onNavigate, onPickRegistered, onPickPrivate, onPickLinuxHome, onGrantPermission)
                WorkspacePickerPage.Projects -> folderItems(state, onRetry, emptyText = R.string.workspace_picker_projects_empty) { folder ->
                    InfinitySettingsItem(
                        title = folder.name,
                        onClick = { onSelectProject(folder.name) },
                        role = Role.RadioButton,
                        selected = state.selectedProject == folder.name,
                        horizontalPadding = 0.dp,
                        leadingContent = { Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = if (state.selectedProject == folder.name) ({
                            Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                        }) else null,
                        modifier = Modifier.testTag("workspace_picker_project_${folder.name}"),
                    )
                }
                is WorkspacePickerPage.Shared -> folderItems(state, onRetry, emptyText = R.string.workspace_picker_folder_empty) { folder ->
                    InfinitySettingsItem(
                        title = folder.name,
                        onClick = { onNavigate(page.copy(path = folder.path)) },
                        showChevron = true,
                        horizontalPadding = 0.dp,
                        leadingContent = { Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                    )
                }
                is WorkspacePickerPage.Linux -> folderItems(state, onRetry, emptyText = R.string.workspace_picker_folder_empty) { folder ->
                    val path = folder.path.path
                    InfinitySettingsItem(
                        // 起点列表显示完整路径（/root、/home），子目录只显示名称。
                        title = if (page.path == null) path else folder.name,
                        onClick = { onNavigate(WorkspacePickerPage.Linux(path)) },
                        showChevron = true,
                        horizontalPadding = 0.dp,
                        leadingContent = {
                            Icon(if (path == LINUX_HOME_DIRECTORY) Icons.Rounded.Home else Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        modifier = Modifier.testTag("workspace_picker_linux_folder_$path"),
                    )
                }
            }
        }
    }
    if (page != WorkspacePickerPage.Home) {
        val ready = when (page) {
            WorkspacePickerPage.Projects -> state.selectedProject != null
            is WorkspacePickerPage.Linux -> page.path != null
            else -> true
        }
        Column(Modifier.fillMaxWidth().padding(top = InfinitySpacing.Small), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall)) {
            state.error?.let {
                Text(
                    stringResource(R.string.workspace_picker_register_failed, it),
                    style = MaterialTheme.typography.bodySmall.forReading(),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = onUseFolder,
                enabled = ready && !state.busy && state.loadError == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = InfinitySpacing.MinimumTouchTarget).testTag("workspace_picker_use"),
            ) {
                Text(
                    state.selectedProject?.takeIf { page is WorkspacePickerPage.Projects }
                        ?.let { stringResource(R.string.workspace_picker_use_named, it) }
                        ?: stringResource(R.string.workspace_picker_use_folder),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun LazyListScope.homeItems(
    state: WorkspacePickerUiState,
    volumeLabels: Map<File, String>,
    onNavigate: (WorkspacePickerPage) -> Unit,
    onPickRegistered: (WorkspaceEntity) -> Unit,
    onPickPrivate: () -> Unit,
    onPickLinuxHome: (WorkspaceEntity) -> Unit,
    onGrantPermission: () -> Unit,
) {
    if (state.sharedOnly) {
        sharedVolumeItems(state, volumeLabels, onNavigate)
        return
    }
    val projects = state.registered.filter { it.kind == WorkspaceEntity.KIND_PROJECT }
    val shared = state.registered.filter { it.kind == WorkspaceEntity.KIND_SHARED }
    // 应用内存储：本会话文件夹与共享文件夹。
    item(key = "app_header") { WorkspaceStorageHeader(null) }
    if (state.showPrivate) {
        item(key = "private") {
            WorkspaceOptionRow(
                title = state.privateTitle ?: stringResource(R.string.workspace_kind_private),
                subtitle = if (state.runMode == SessionRunMode.LINUX) {
                    monospaceArgument(R.string.workspace_picker_private_desc_linux, LINUX_SESSION_DIRECTORY)
                } else AnnotatedString(stringResource(R.string.workspace_picker_private_desc)),
                tagsKind = null,
                selected = state.currentWorkspaceId == null,
                enabled = !state.busy,
                onClick = onPickPrivate,
                leading = { WorkspaceKindIcon(null) },
                modifier = Modifier.testTag("workspace_picker_private"),
            )
        }
    }
    items(projects, key = { "registered:" + it.id }) { workspace -> RegisteredRow(state, workspace, onPickRegistered) }
    item(key = "projects") {
        WorkspaceOptionRow(
            title = stringResource(R.string.workspace_picker_shared_folder),
            subtitle = AnnotatedString(stringResource(R.string.workspace_picker_shared_folder_desc)),
            tagsKind = WorkspaceEntity.KIND_PROJECT,
            onClick = { onNavigate(WorkspacePickerPage.Projects) },
            leading = { Icon(Icons.Rounded.FolderOpen, null, tint = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.testTag("workspace_picker_projects"),
        )
    }
    linuxItems(state, onNavigate, onPickLinuxHome)
    if (!state.sharedSupported && shared.isEmpty()) return
    item(key = "shared_header") { WorkspaceStorageHeader(WorkspaceEntity.KIND_SHARED) }
    items(shared, key = { "registered:" + it.id }) { workspace -> RegisteredRow(state, workspace, onPickRegistered) }
    if (!state.sharedSupported) return
    if (!state.sharedPermission) {
        item(key = "shared_permission") {
            InfinitySettingsItem(
                title = stringResource(R.string.workspace_picker_shared_permission_title),
                onClick = onGrantPermission,
                horizontalPadding = 0.dp,
                leadingContent = { Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = {
                    Text(stringResource(R.string.workspace_picker_grant), color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge)
                },
                modifier = Modifier.testTag("workspace_picker_shared_permission"),
            )
        }
    } else {
        sharedVolumeItems(state, volumeLabels, onNavigate)
    }
}

private fun LazyListScope.sharedVolumeItems(
    state: WorkspacePickerUiState,
    volumeLabels: Map<File, String>,
    onNavigate: (WorkspacePickerPage) -> Unit,
) {
    items(state.sharedRoots, key = { "volume:" + it.path }) { volume ->
        WorkspaceOptionRow(
            title = volumeLabels[volume] ?: volume.name,
            subtitle = AnnotatedString(stringResource(R.string.workspace_picker_shared_volume_desc)),
            tagsKind = WorkspaceEntity.KIND_SHARED,
            // 挂载选路径时不涉及工作区的备份与留存，标签不适用。
            showTags = !state.sharedOnly,
            onClick = { onNavigate(WorkspacePickerPage.Shared(volume, volume)) },
            leading = { Icon(Icons.Rounded.SdStorage, null, tint = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.testTag("workspace_picker_volume"),
        )
    }
}

/**
 * Linux 环境：当前会话已绑定的 Linux 目录排在前面显示为已选，下方“主目录”入口逐级浏览环境内可选目录。
 * 登记按环境代次去重，重置后旧登记不可用，因此不列出其它历史登记。内置模式会话中该入口灰显并说明原因，不隐藏。
 */
private fun LazyListScope.linuxItems(
    state: WorkspacePickerUiState,
    onNavigate: (WorkspacePickerPage) -> Unit,
    onPickCurrent: (WorkspaceEntity) -> Unit,
) {
    if (state.linuxHome == LinuxHomeAvailability.UNSUPPORTED) return
    val ready = state.linuxHome == LinuxHomeAvailability.READY
    val builtinSession = state.runMode == SessionRunMode.BUILTIN
    item(key = "linux_header") { WorkspaceStorageHeader(WorkspaceEntity.KIND_LINUX_HOME) }
    state.registered.firstOrNull { it.kind == WorkspaceEntity.KIND_LINUX_HOME && it.id == state.currentWorkspaceId }?.let { current ->
        item(key = "linux_current") {
            WorkspaceOptionRow(
                title = current.displayName,
                subtitle = workspaceLocationText(current.kind, current.location).takeIf { it != current.displayName }
                    ?.let { AnnotatedString(it.breakableIdentifier()) },
                tagsKind = WorkspaceEntity.KIND_LINUX_HOME,
                selected = true,
                enabled = !state.busy,
                onClick = { onPickCurrent(current) },
                leading = { WorkspaceKindIcon(WorkspaceEntity.KIND_LINUX_HOME) },
                modifier = Modifier.testTag("workspace_picker_linux_current"),
            )
        }
    }
    item(key = "linux_browse") {
        WorkspaceOptionRow(
            title = stringResource(R.string.workspace_picker_linux_home),
            subtitle = when {
                builtinSession -> AnnotatedString(stringResource(R.string.workspace_picker_linux_builtin_session))
                !ready -> AnnotatedString(stringResource(R.string.workspace_picker_linux_not_ready))
                else -> monospaceArgument(R.string.workspace_picker_linux_home_desc, LINUX_HOME_DIRECTORY)
            },
            tagsKind = WorkspaceEntity.KIND_LINUX_HOME,
            showTags = !builtinSession,
            showChevron = !builtinSession,
            onClick = { onNavigate(WorkspacePickerPage.Linux(null)) },
            enabled = ready && !builtinSession && !state.busy,
            leading = { Icon(Icons.Rounded.Home, null, tint = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.testTag("workspace_picker_linux_home"),
        )
    }
}

@Composable
private fun RegisteredRow(state: WorkspacePickerUiState, workspace: WorkspaceEntity, onPick: (WorkspaceEntity) -> Unit) =
    WorkspaceOptionRow(
        title = workspace.displayName,
        // 共享文件夹只在显示名与目录名不同时补目录名；手机存储的路径本身就是有用信息。
        subtitle = when {
            workspace.kind == WorkspaceEntity.KIND_SHARED -> compactSharedPath(workspace.location)
            workspace.displayName != workspace.location -> workspace.location
            else -> null
        }?.let { AnnotatedString(it.breakableIdentifier()) },
        tagsKind = workspace.kind,
        selected = workspace.id == state.currentWorkspaceId,
        enabled = !state.busy,
        onClick = { onPick(workspace) },
        leading = { WorkspaceKindIcon(workspace.kind) },
        modifier = Modifier.testTag("workspace_picker_registered_${workspace.id}"),
    )

/** 格式串中的路径参数用等宽字体显示。 */
@Composable
private fun monospaceArgument(@StringRes template: Int, path: String): AnnotatedString {
    val text = stringResource(template, path)
    val start = text.indexOf(path)
    return buildAnnotatedString {
        append(text)
        if (start >= 0) addStyle(SpanStyle(fontFamily = InfinityMonospace), start, start + path.length)
    }
}

/**
 * 起始页的一行：标题、说明与两条标签（进不进备份、何时消失）。
 * [selected] 非 null 为单选行，选中时带勾；为 null 为入口行，可用时带箭头。
 */
@Composable
private fun WorkspaceOptionRow(
    title: String,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    tagsKind: String?,
    modifier: Modifier = Modifier,
    subtitle: AnnotatedString? = null,
    showTags: Boolean = true,
    showChevron: Boolean = true,
    selected: Boolean? = null,
    enabled: Boolean = true,
) {
    val haptics = LocalInfinityHaptics.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { if (selected != null) this.selected = selected }
            .clickable(enabled = enabled, role = if (selected != null) Role.RadioButton else Role.Button) {
                selected?.let(haptics::select)
                onClick()
            }
            .alpha(if (enabled) 1f else 0.38f)
            .heightIn(min = InfinitySpacing.RowTwoLineHeight)
            .padding(vertical = InfinitySpacing.RowTwoLineVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(InfinitySpacing.RowIconSize + InfinitySpacing.RowIconGap), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.size(InfinitySpacing.RowIconSize), contentAlignment = Alignment.Center) { leading() }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(InfinitySpacing.RowTwoLineGap))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showTags) WorkspaceKindTagRow(tagsKind, Modifier.padding(top = InfinitySpacing.Small - 2.dp))
        }
        when {
            selected == true -> Icon(Icons.Rounded.Check, null, Modifier.padding(start = InfinitySpacing.Medium).size(20.dp), MaterialTheme.colorScheme.primary)
            selected == null && showChevron -> Icon(
                Icons.Rounded.ChevronRight, null,
                Modifier.padding(start = InfinitySpacing.Medium).size(18.dp),
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun LazyListScope.folderItems(
    state: WorkspacePickerUiState,
    onRetry: () -> Unit,
    emptyText: Int,
    row: @Composable (WorkspaceFolder) -> Unit,
) {
    val loadError = state.loadError
    when {
        loadError != null -> item(key = "error") {
            Column(Modifier.fillMaxWidth().padding(vertical = InfinitySpacing.Medium)) {
                Text(
                    stringResource(R.string.workspace_picker_load_failed, loadError),
                    style = MaterialTheme.typography.bodyMedium.forReading(),
                    color = MaterialTheme.colorScheme.error,
                )
                InfinityRetryButton(
                    onClick = onRetry,
                    text = stringResource(R.string.common_retry),
                    modifier = Modifier.padding(top = InfinitySpacing.Small),
                )
            }
        }
        !state.loading && state.folders.isEmpty() -> item(key = "empty") {
            Text(
                stringResource(emptyText),
                style = MaterialTheme.typography.bodyMedium.forReading(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = InfinitySpacing.Medium),
            )
        }
        else -> items(state.folders, key = { it.path.path }) { row(it) }
    }
}

@Composable
internal fun WorkspaceKindIcon(kind: String?) {
    Icon(
        imageVector = when (kind) {
            WorkspaceEntity.KIND_PROJECT -> Icons.Rounded.Folder
            WorkspaceEntity.KIND_SHARED -> Icons.Rounded.PhoneAndroid
            WorkspaceEntity.KIND_LINUX_HOME -> Icons.Rounded.Terminal
            else -> Icons.Rounded.ChatBubbleOutline
        },
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
    )
}
