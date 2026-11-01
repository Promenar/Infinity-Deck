package com.promenar.infinity.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.workspace.CreateFolderResult
import com.promenar.infinity.data.workspace.FolderNameError
import com.promenar.infinity.data.workspace.WorkspaceFolder
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 选择器打开时所在的页面：[initialPath] 位于某个存储卷内时直接进入该目录，否则进入首个存储卷的根目录；
 * 没有可用存储卷时停在存储卷列表。
 */
internal fun initialSharedFolderPage(roots: List<File>, initialPath: String?): WorkspacePickerPage {
    val target = initialPath?.trim()?.takeIf(String::isNotEmpty)?.let { File(it).absoluteFile.toPath().normalize() }
    if (target != null) {
        roots.firstOrNull { target.startsWith(it.absoluteFile.toPath().normalize()) }?.let { volume ->
            return WorkspacePickerPage.Shared(volume, target.toFile())
        }
    }
    return roots.firstOrNull()?.let { WorkspacePickerPage.Shared(it, it) } ?: WorkspacePickerPage.Home
}

/**
 * 只有一个存储卷时不显示存储卷列表：从卷根返回或点首段面包屑直接离开选择器（返回 null）。
 * 其它导航原样进行。
 */
internal fun sharedFolderNavigation(next: WorkspacePickerPage, volumeCount: Int): WorkspacePickerPage? =
    if (next == WorkspacePickerPage.Home && volumeCount <= 1) null else next

/**
 * 逐级浏览共享存储卷并选定一个目录，复用主工作区选择器的页面、面包屑与新建文件夹；
 * 选定后以目录路径回调 [onPicked]，不登记工作区。需要“所有文件访问”授权，调用方负责在授权前禁用入口。
 */
@Composable
internal fun SharedFolderPicker(
    initialPath: String?,
    onPicked: (String) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as? InfinityApplication ?: return
    val browser = app.workspaceDirectoryBrowser
    val scope = rememberCoroutineScope()
    val roots = remember(browser) { browser.sharedRoots() }
    var page by remember { mutableStateOf(initialSharedFolderPage(roots, initialPath)) }
    var folders by remember { mutableStateOf<List<WorkspaceFolder>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<FolderNameError?>(null) }

    val navigate: (WorkspacePickerPage) -> Unit = { next ->
        val target = sharedFolderNavigation(next, roots.size)
        if (target == null) onExit() else {
            page = target
            error = null
        }
    }

    LaunchedEffect(page, reload) {
        folders = emptyList()
        loadError = null
        val current = page as? WorkspacePickerPage.Shared ?: return@LaunchedEffect
        loading = true
        try {
            folders = browser.listShared(current.path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            loadError = failure.message ?: failure.javaClass.simpleName
        } finally {
            loading = false
        }
    }

    BackHandler(enabled = !busy) {
        if (page == WorkspacePickerPage.Home) onExit() else navigate(page.parent())
    }
    WorkspacePickerContent(
        state = WorkspacePickerUiState(
            page = page,
            sharedSupported = browser.sharedSupported,
            sharedPermission = browser.hasSharedPermission(),
            sharedRoots = roots,
            folders = folders,
            loading = loading,
            loadError = loadError,
            busy = busy,
            error = error,
            sharedOnly = true,
        ),
        modifier = modifier,
        onNavigate = navigate,
        onCreate = {
            createError = null
            creating = true
        },
        onUseFolder = { (page as? WorkspacePickerPage.Shared)?.let { onPicked(it.path.path) } },
        onRetry = { reload++ },
        rootTitle = stringResource(R.string.mount_picker_root),
        onExitRoot = onExit,
    )

    if (creating) {
        WorkspaceNameDialog(
            title = stringResource(R.string.workspace_picker_new_folder),
            label = stringResource(R.string.workspace_picker_folder_name),
            confirmLabel = stringResource(R.string.workspace_picker_create),
            error = createError?.let { folderNameErrorText(it, project = false) },
            busy = busy,
            onValueChange = { createError = null },
            onDismiss = { creating = false },
            onConfirm = { name ->
                val current = page as? WorkspacePickerPage.Shared ?: return@WorkspaceNameDialog
                busy = true
                scope.launch {
                    try {
                        when (val result = browser.createSharedFolder(current.path, name)) {
                            is CreateFolderResult.Created -> {
                                creating = false
                                page = current.copy(path = result.folder.path)
                            }
                            is CreateFolderResult.Rejected -> createError = result.error
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
