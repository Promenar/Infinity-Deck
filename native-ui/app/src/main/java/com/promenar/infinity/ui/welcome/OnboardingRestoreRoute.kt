package com.promenar.infinity.ui.welcome

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.onboarding.OnboardingStateStore
import com.promenar.infinity.ui.settings.BackupErrorCode
import com.promenar.infinity.ui.settings.BackupOperation
import com.promenar.infinity.ui.settings.BackupSuccessCode
import com.promenar.infinity.ui.settings.BackupViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 引导页持有的恢复面板：[state] 为 null 表示面板关闭。 */
internal class OnboardingRestoreController(
    val state: OnboardingRestoreSheetState?,
    val actions: OnboardingRestoreSheetActions,
    val open: () -> Unit,
)

private const val RESTORE_VIEW_MODEL_KEY = "onboarding_restore"

/**
 * 装配“从备份恢复”面板。恢复本身完全走设置页的 [BackupViewModel]：本机文件用 restoreLocal，
 * WebDAV 先保存配置再列出远端、选中后用 restoreSelectedRemote；暂存成功后由 [OnboardingRestoreOperations]
 * 记下向导恢复标记，随后视图模型请求重启，启动时提交并完成引导。
 */
@Composable
internal fun rememberOnboardingRestore(store: OnboardingStateStore): OnboardingRestoreController {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf(OnboardingRestorePage.SOURCES) }
    var selected by remember { mutableStateOf<OnboardingRestorePackage?>(null) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var inspecting by remember { mutableStateOf(false) }
    var listAfterSave by remember { mutableStateOf(false) }
    // 换页时把当前状态视为已读，避免上一份备份的错误留在新页面上；按引用比较，新的失败照常显示。
    var dismissedOperation by remember { mutableStateOf<BackupOperation?>(null) }

    val backup: BackupViewModel? = if (open) {
        viewModel(
            key = RESTORE_VIEW_MODEL_KEY,
            factory = BackupViewModel.factory(application) { OnboardingRestoreOperations(it, store) },
        )
    } else {
        null
    }
    val backupState = backup?.uiState?.collectAsState()?.value

    // 保存 WebDAV 配置是异步的，保存成功后再列出远端备份。
    LaunchedEffect(backup, backupState?.operation, listAfterSave) {
        val vm = backup ?: return@LaunchedEffect
        val operation = backupState?.operation ?: return@LaunchedEffect
        if (!listAfterSave) return@LaunchedEffect
        when {
            operation is BackupOperation.Success && operation.code == BackupSuccessCode.CONFIG_SAVED -> {
                listAfterSave = false
                vm.listRemote()
            }
            operation is BackupOperation.Error || operation is BackupOperation.Blocked -> listAfterSave = false
        }
    }

    val goTo: (OnboardingRestorePage) -> Unit = { target ->
        dismissedOperation = backup?.uiState?.value?.operation
        page = target
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        dismissedOperation = backup?.uiState?.value?.operation
        inspecting = true
        scope.launch {
            val inspected = withContext(Dispatchers.IO) { inspectLocalBackup(context.contentResolver, uri) }
            inspecting = false
            if (inspected == null) {
                dismissedOperation = null
                backup?.reportDocumentError(BackupErrorCode.DOCUMENT_OPEN_FAILED)
            } else {
                selectedUri = uri
                selected = inspected
                goTo(OnboardingRestorePage.CONFIRM)
            }
        }
    }

    val actions = OnboardingRestoreSheetActions(
        onPickFile = { picker.launch("*/*") },
        onOpenWebDav = { goTo(OnboardingRestorePage.WEBDAV) },
        onListRemote = { url, user, password ->
            backup?.let { vm ->
                dismissedOperation = null
                if (!vm.uiState.value.webdavEnabled) vm.setWebdavEnabled(true)
                val accepted = vm.saveWebDavConfig(url, user, password.takeIf(String::isNotEmpty)?.toCharArray())
                if (accepted) {
                    if (vm.uiState.value.operation is BackupOperation.SavingConfig) listAfterSave = true else vm.listRemote()
                }
            }
        },
        onRevealWebDavPassword = { backup?.revealWebDavPassword() },
        onSelectRemote = { remote ->
            if (backup?.selectRemote(remote) == true) {
                selectedUri = null
                selected = OnboardingRestorePackage(
                    name = remote.fileName,
                    sizeBytes = remote.sizeBytes,
                    peek = BackupPackagePeek.Unknown,
                    modifiedAt = remote.lastModifiedEpochMillis.takeIf { it > 0 },
                    remote = remote,
                )
                goTo(OnboardingRestorePage.CONFIRM)
            }
        },
        onBackToSources = {
            selected = null
            selectedUri = null
            goTo(OnboardingRestorePage.SOURCES)
        },
        onRestore = { password ->
            val owned = password.takeIf(String::isNotEmpty)?.toCharArray()
            val vm = backup
            val uri = selectedUri
            dismissedOperation = null
            when {
                vm == null -> owned?.fill('\u0000')
                selected?.remote != null -> vm.restoreSelectedRemote(owned)
                uri != null -> openAndRestoreLocal(context.contentResolver, uri, vm, owned)
                else -> owned?.fill('\u0000')
            }
        },
        onCancelOperation = { backup?.cancelOperation() },
        onRetryRestart = { backup?.retryRestart() },
        onCancelRestore = { backup?.cancelPendingRestart() },
        onDismiss = {
            open = false
            page = OnboardingRestorePage.SOURCES
            selected = null
            selectedUri = null
            listAfterSave = false
            dismissedOperation = null
        },
    )

    val state = if (open && backupState != null) {
        OnboardingRestoreSheetState(
            page = page,
            selected = selected,
            inspecting = inspecting,
            operation = backupState.operation.takeUnless { it === dismissedOperation } ?: BackupOperation.Idle,
            canExecute = backupState.canExecute && !inspecting,
            webdavUrl = backupState.webdavUrl,
            webdavUser = backupState.webdavUser,
            hasWebDavPassword = backupState.hasWebDavPassword,
            remoteBackups = backupState.remoteBackups,
            remoteListed = backupState.remoteConfigRevision != null,
        )
    } else {
        null
    }
    return OnboardingRestoreController(
        state = state,
        actions = actions,
        open = {
            page = OnboardingRestorePage.SOURCES
            open = true
        },
    )
}

/** 与设置页一致：打开失败报告“无法打开文件”，成功则把输入流交给恢复管线，由管线负责关闭。 */
private fun openAndRestoreLocal(resolver: ContentResolver, uri: Uri, vm: BackupViewModel, password: CharArray?) {
    var input: java.io.InputStream? = null
    var handedOff = false
    try {
        input = resolver.openInputStream(uri)
        if (input == null) {
            vm.reportDocumentError(BackupErrorCode.DOCUMENT_OPEN_FAILED)
        } else {
            handedOff = true
            vm.restoreLocal(input, password)
        }
    } catch (_: SecurityException) {
        vm.reportDocumentError(BackupErrorCode.DOCUMENT_OPEN_FAILED)
    } catch (_: java.io.IOException) {
        vm.reportDocumentError(BackupErrorCode.DOCUMENT_OPEN_FAILED)
    } finally {
        if (!handedOff) {
            runCatching { input?.close() }
            password?.fill('\u0000')
        }
    }
}

/** 读取所选文件的显示名、大小与包头；无法打开时返回 null。 */
private fun inspectLocalBackup(resolver: ContentResolver, uri: Uri): OnboardingRestorePackage? {
    var name: String? = null
    var size: Long? = null
    try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
    } catch (_: Exception) {
        // 显示名与大小只用于展示，查询失败时退回 URI 末段。
    }
    val peek = try {
        resolver.openInputStream(uri)?.use { peekBackupPackage(it) } ?: return null
    } catch (_: SecurityException) {
        return null
    } catch (_: java.io.IOException) {
        return null
    }
    return OnboardingRestorePackage(
        name = name ?: uri.lastPathSegment.orEmpty(),
        sizeBytes = size,
        peek = peek,
    )
}
