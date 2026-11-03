package com.promenar.infinity.ui.settings

import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.InfinityModalSheet
import androidx.compose.animation.AnimatedVisibility
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.R
import com.promenar.infinity.data.backup.BackupFailureReason
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.SettingsSectionHeader
import com.promenar.infinity.ui.common.bettboxListGroup
import com.promenar.infinity.ui.common.SettingsToggle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.LocalTextStyle
import com.promenar.infinity.ui.common.SecretField
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.UnavailableReasons
import com.promenar.infinity.ui.common.InfinitySettingsSection
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.forInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: BackupViewModel = viewModel(factory = BackupViewModel.factory(context.applicationContext as android.app.Application))
    BackupSettingsScreen(onNavigateBack, viewModel)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun BackupSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: BackupViewModel,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.operation) {
        if (uiState.operation != BackupOperation.Idle) {
            listState.scrollToItem(1)
        }
    }
    // 进入页面后首次空闲时在后台估算各层大小；估算失败不自动重试，避免反复扫描。
    LaunchedEffect(uiState.canExecute) {
        if (uiState.canExecute && uiState.layerEstimates.isEmpty() && !uiState.estimating && !uiState.estimateFailed) viewModel.refreshEstimates()
    }
    uiState.exportSummary?.let { summary -> BackupExportResultSheet(summary, viewModel::dismissExportSummary) }

    var showWebdavSheet by remember { mutableStateOf(false) }
    var showExportPasswordDialog by remember { mutableStateOf(false) }
    var showUploadPasswordDialog by remember { mutableStateOf(false) }
    var showRestorePasswordDialog by remember { mutableStateOf(false) }
    var restoreRemote by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var backupPassword by remember { mutableStateOf("") }
    var passwordConfirmation by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }

    var tempWebdavUrl by remember(uiState.webdavUrl) { mutableStateOf(uiState.webdavUrl) }
    var tempWebdavUser by remember(uiState.webdavUser) { mutableStateOf(uiState.webdavUser) }
    var tempWebdavPass by remember { mutableStateOf("") }

    fun clearPasswords() {
        backupPassword = ""
        passwordConfirmation = ""
        restorePassword = ""
        tempWebdavPass = ""
    }
    DisposableEffect(Unit) { onDispose { clearPasswords() } }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        var output: java.io.OutputStream? = null
        var handedOff = false
        try {
            if (uri != null) {
                output = context.contentResolver.openOutputStream(uri)
                if (output == null) {
                    viewModel.reportDocumentError(BackupErrorCode.DOCUMENT_CREATE_FAILED)
                } else {
                    handedOff = true
                    viewModel.export(
                        output,
                        backupPassword.takeIf { uiState.encryptBackup || uiState.includeKeys }?.toCharArray(),
                        passwordConfirmation.takeIf { uiState.encryptBackup || uiState.includeKeys }?.toCharArray(),
                    )
                }
            }
        } catch (_: SecurityException) {
            viewModel.reportDocumentError(BackupErrorCode.DOCUMENT_CREATE_FAILED)
        } catch (_: java.io.IOException) {
            viewModel.reportDocumentError(BackupErrorCode.DOCUMENT_CREATE_FAILED)
        } finally {
            if (!handedOff) runCatching { output?.close() }
            clearPasswords()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            pendingRestoreUri = it
            restoreRemote = false
            showRestorePasswordDialog = true
        }
    }
    val onExportClick = {
        if (uiState.encryptBackup || uiState.includeKeys) showExportPasswordDialog = true
        else exportLauncher.launch("infinity_backup_${System.currentTimeMillis()}.iwb")
    }
    val onImportClick = { importLauncher.launch("*/*") }

    BackupSettingsScreenContent(
        uiState = uiState,
        listState = listState,
        onNavigateBack = onNavigateBack,
        actions = BackupScreenActions(
            onCancelOperation = viewModel::cancelOperation,
            onIncludeKeysChange = viewModel::setIncludeKeys,
            onEncryptBackupChange = viewModel::setEncryptBackup,
            onLayerChange = { layer, selected -> viewModel.setLayerSelected(layer, selected) },
            onExport = onExportClick,
            onImport = onImportClick,
            onWebDavEnabledChange = viewModel::setWebdavEnabled,
            onUpload = {
                if (uiState.encryptBackup || uiState.includeKeys) showUploadPasswordDialog = true
                else viewModel.upload(null, null)
            },
            onRefreshRemote = viewModel::listRemote,
            onSelectRemote = viewModel::selectRemote,
            onRestoreRemote = { restoreRemote = true; showRestorePasswordDialog = true },
            onConfigureWebDav = { showWebdavSheet = true },
        ),
    )

    if (showWebdavSheet) {
        InfinityModalSheet(
            onDismissRequest = {
                tempWebdavPass = ""
                showWebdavSheet = false
            },
            height = InfinitySheetHeight.Full,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .safeDrawingPadding()
                    .padding(24.dp)
                    .padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.backup_webdav_config_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                OutlinedTextField(
                    textStyle = LocalTextStyle.current.forInput(),
                    value = tempWebdavUrl,
                    onValueChange = { tempWebdavUrl = it },
                    label = { Text(stringResource(R.string.backup_webdav_url_label)) },
                    placeholder = { Text(stringResource(R.string.backup_webdav_url_hint)) },
                    supportingText = com.promenar.infinity.ui.common.cleartextUrlHelp(tempWebdavUrl)?.let { hint -> { Text(hint) } },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    textStyle = LocalTextStyle.current.forInput(),
                    value = tempWebdavUser,
                    onValueChange = { tempWebdavUser = it },
                    label = { Text(stringResource(R.string.backup_webdav_user_label)) },
                    placeholder = { Text(stringResource(R.string.backup_webdav_user_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = stringResource(R.string.backup_webdav_pass_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SecretField(
                    value = tempWebdavPass,
                    onValueChange = { tempWebdavPass = it },
                    hasStoredSecret = uiState.hasWebDavPassword,
                    onRevealRequest = viewModel::revealWebDavPassword,
                    onClear = {
                        tempWebdavPass = ""
                        viewModel.deleteWebDavPassword()
                    },
                )

                InfinityPillRow(Modifier.fillMaxWidth()) {
                    InfinityPillButton(
                        text = stringResource(R.string.backup_test_connection),
                        onClick = {
                            val accepted = viewModel.saveAndTestWebDavConfig(
                                tempWebdavUrl,
                                tempWebdavUser,
                                tempWebdavPass.takeIf { it.isNotEmpty() }?.toCharArray(),
                            )
                            if (accepted) tempWebdavPass = ""
                        },
                        enabled = uiState.canExecute,
                        icon = Icons.Rounded.Link,
                    )
                    InfinityPillButton(
                        text = stringResource(R.string.backup_save_config),
                        onClick = {
                            val accepted = viewModel.saveWebDavConfig(
                                tempWebdavUrl,
                                tempWebdavUser,
                                tempWebdavPass.takeIf { it.isNotEmpty() }?.toCharArray(),
                            )
                            if (accepted) tempWebdavPass = ""
                        },
                        enabled = uiState.canExecute,
                        icon = Icons.Rounded.CloudSync,
                        tone = InfinityPillTone.PRIMARY,
                    )
                }

                backupOperationText(uiState.operation)?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (uiState.operation is BackupOperation.Blocked ||
                            uiState.operation is BackupOperation.Error
                        ) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val blockedCode = (uiState.operation as? BackupOperation.Blocked)?.code
                if (blockedCode == BackupErrorCode.CONNECTION_FAILED ||
                    blockedCode == BackupErrorCode.CONFIGURATION_MISSING
                ) {
                    InfinitySettingsItem(
                        icon = Icons.Rounded.DeleteForever,
                        title = stringResource(R.string.backup_reset_webdav_security),
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = { tempWebdavPass = ""; viewModel.resetWebDavAuth() },
                    )
                }
            }
        }
    }

    if (showExportPasswordDialog) {
        BackupPasswordDialog(
            title = stringResource(R.string.backup_password_export_title),
            password = backupPassword,
            passwordConfirmation = passwordConfirmation,
            requireConfirmation = true,
            onPasswordChange = { backupPassword = it },
            onConfirmationChange = { passwordConfirmation = it },
            onDismiss = { showExportPasswordDialog = false; clearPasswords() },
            onConfirm = {
                showExportPasswordDialog = false
                exportLauncher.launch("infinity_backup_${System.currentTimeMillis()}.iwb")
            },
        )
    }
    if (showUploadPasswordDialog) {
        BackupPasswordDialog(
            title = stringResource(R.string.backup_password_upload_title),
            password = backupPassword,
            passwordConfirmation = passwordConfirmation,
            requireConfirmation = true,
            onPasswordChange = { backupPassword = it },
            onConfirmationChange = { passwordConfirmation = it },
            onDismiss = { showUploadPasswordDialog = false; clearPasswords() },
            onConfirm = {
                showUploadPasswordDialog = false
                viewModel.upload(backupPassword.toCharArray(), passwordConfirmation.toCharArray())
                clearPasswords()
            },
        )
    }
    if (showRestorePasswordDialog) {
        BackupPasswordDialog(
            title = stringResource(if (restoreRemote) R.string.backup_password_remote_restore_title else R.string.backup_password_restore_title),
            password = restorePassword,
            passwordConfirmation = "",
            requireConfirmation = false,
            onPasswordChange = { restorePassword = it },
            onConfirmationChange = {},
            onDismiss = {
                showRestorePasswordDialog = false
                pendingRestoreUri = null
                clearPasswords()
            },
            onConfirm = {
                showRestorePasswordDialog = false
                if (restoreRemote) {
                    viewModel.restoreSelectedRemote(restorePassword.takeIf(String::isNotEmpty)?.toCharArray())
                } else {
                    pendingRestoreUri?.let { uri ->
                        var input: java.io.InputStream? = null
                        var handedOff = false
                        try {
                            input = context.contentResolver.openInputStream(uri)
                            if (input == null) {
                                viewModel.reportDocumentError(BackupErrorCode.DOCUMENT_OPEN_FAILED)
                            } else {
                                handedOff = true
                                viewModel.restoreLocal(input, restorePassword.takeIf(String::isNotEmpty)?.toCharArray())
                            }
                        } catch (_: SecurityException) {
                            viewModel.reportDocumentError(BackupErrorCode.DOCUMENT_OPEN_FAILED)
                        } catch (_: java.io.IOException) {
                            viewModel.reportDocumentError(BackupErrorCode.DOCUMENT_OPEN_FAILED)
                        } finally {
                            if (!handedOff) runCatching { input?.close() }
                        }
                    }
                }
                pendingRestoreUri = null
                clearPasswords()
            },
        )
    }
}

internal data class BackupScreenActions(
    val onCancelOperation: () -> Unit = {},
    val onIncludeKeysChange: (Boolean) -> Unit = {},
    val onEncryptBackupChange: (Boolean) -> Unit = {},
    val onLayerChange: (com.promenar.infinity.data.backup.BackupLayer, Boolean) -> Unit = { _, _ -> },
    val onExport: () -> Unit = {},
    val onImport: () -> Unit = {},
    val onWebDavEnabledChange: (Boolean) -> Unit = {},
    val onUpload: () -> Unit = {},
    val onRefreshRemote: () -> Unit = {},
    val onSelectRemote: (com.promenar.infinity.data.remote.webdav.RemoteBackup) -> Unit = {},
    val onRestoreRemote: () -> Unit = {},
    val onConfigureWebDav: () -> Unit = {},
)

@Composable
internal fun BackupSettingsScreenContent(
    uiState: BackupUiState,
    actions: BackupScreenActions = BackupScreenActions(),
    onNavigateBack: () -> Unit = {},
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
) {
    var contentExpanded by remember { mutableStateOf(true) }
    InfinitySettingsPageLayout(title = stringResource(R.string.backup_title), onBack = onNavigateBack) { paddingValues ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth(), contentPadding = paddingValues) {
            item {
                backupOperationText(uiState.operation)?.let { status ->
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            text = status,
                            modifier = Modifier.testTag(UiTags.BACKUP_OPERATION_STATUS),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (uiState.operation is BackupOperation.Error || uiState.operation is BackupOperation.Blocked)
                                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (uiState.operation.isCancellable) {
                            InfinityPillButton(
                                text = stringResource(R.string.common_btn_cancel),
                                onClick = actions.onCancelOperation,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
            item {
                InfinitySettingsSection(
                    title = stringResource(R.string.settings_batch1_backup_options),
                    footer = backupLayersFooter(uiState),
                ) {
                    InfinitySettingsItem(
                        title = stringResource(R.string.backup_content_title),
                        subtitle = backupLayerSummary(uiState),
                        subtitleMaxLines = 3,
                        onClick = { contentExpanded = !contentExpanded },
                        trailingContent = {
                            Icon(if (contentExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, contentDescription = null)
                        },
                    )
                    AnimatedVisibility(visible = contentExpanded) {
                        Column {
                            BackupLayerToggles(
                                state = uiState,
                                onLayerChange = actions.onLayerChange,
                                onIncludeKeysChange = actions.onIncludeKeysChange,
                                onEncryptBackupChange = actions.onEncryptBackupChange,
                            )
                        }
                    }
                }
            }
            item {
                InfinitySettingsSection(
                    title = stringResource(R.string.backup_section_local),
                    footer = stringResource(R.string.settings_batch1_backup_local_footer),
                ) {
                    ExportButton(
                        icon = Icons.Rounded.Download,
                        title = stringResource(R.string.backup_export_title),
                        subtitle = stringResource(if (uiState.isExporting) R.string.backup_exporting else R.string.settings_batch1_backup_export_summary),
                        modifier = Modifier.fillMaxWidth().testTag(UiTags.BACKUP_EXPORT),
                        enabled = uiState.canExecute,
                        onClick = actions.onExport,
                    )
                    ExportButton(
                        icon = Icons.Rounded.Upload,
                        title = stringResource(R.string.backup_import_title),
                        subtitle = stringResource(if (uiState.isImporting) R.string.backup_importing else R.string.settings_batch1_backup_import_summary),
                        modifier = Modifier.fillMaxWidth().testTag(UiTags.BACKUP_IMPORT),
                        enabled = uiState.canExecute,
                        onClick = actions.onImport,
                    )
                }
            }
            item {
                InfinitySettingsSection(
                    title = stringResource(R.string.backup_section_webdav),
                    footer = uiState.selectedRemote?.let { remote ->
                        listOf(stringResource(R.string.backup_remote_size, remote.sizeBytes), remote.lastModifiedEpochMillis.toString()).joinToString("\n")
                    },
                ) {
                    SettingsToggle(
                        icon = Icons.Rounded.CloudSync,
                        title = stringResource(R.string.backup_webdav_manual),
                        description = stringResource(when (uiState.webDavReadiness) {
                            WebDavReadiness.Disabled -> R.string.backup_webdav_not_configured
                            WebDavReadiness.Incomplete -> R.string.backup_webdav_incomplete
                            WebDavReadiness.Ready -> R.string.backup_webdav_configured
                        }),
                        checked = uiState.webdavEnabled,
                        onCheckedChange = actions.onWebDavEnabledChange,
                    )
                    // WebDAV 关闭时上传、刷新与恢复保留在原位置并灰显，副标题写明开启方式；云端备份列表只在开启后出现。
                    val webDavOffReason = UnavailableReasons.webDavActions(uiState.webdavEnabled)?.let { stringResource(it) }
                    val webDavReady = uiState.webdavEnabled && uiState.canExecute && uiState.webDavReadiness == WebDavReadiness.Ready
                    InfinitySettingsItem(
                        icon = Icons.Rounded.Upload,
                        title = stringResource(R.string.backup_upload_cloud),
                        subtitle = webDavOffReason,
                        onClick = actions.onUpload,
                        enabled = webDavReady,
                    )
                    InfinitySettingsItem(
                        icon = Icons.Rounded.Refresh,
                        title = stringResource(R.string.backup_remote_refresh),
                        subtitle = webDavOffReason,
                        onClick = actions.onRefreshRemote,
                        enabled = webDavReady,
                    )
                    AnimatedVisibility(visible = uiState.webdavEnabled) {
                        Column {
                            if (uiState.operation is BackupOperation.ListingRemote) {
                                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                }
                            } else if (uiState.remoteBackups.isEmpty()) {
                                InfinitySettingsItem(
                                    icon = Icons.Rounded.CloudOff,
                                    title = stringResource(R.string.backup_remote_empty),
                                    onClick = actions.onRefreshRemote,
                                    enabled = uiState.canExecute && uiState.webDavReadiness == WebDavReadiness.Ready,
                                )
                            }
                            uiState.remoteBackups.forEach { remote ->
                                val selected = uiState.selectedRemote == remote
                                InfinitySettingsItem(
                                    icon = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.CloudSync,
                                    title = remote.fileName,
                                    onClick = { actions.onSelectRemote(remote) },
                                    enabled = uiState.canExecute,
                                    modifier = Modifier.semantics { this.selected = selected },
                                )
                            }
                        }
                    }
                    val restoreReason = UnavailableReasons.webDavActions(uiState.webdavEnabled)
                        ?: UnavailableReasons.cloudRestore(uiState.selectedRemote != null)
                    InfinitySettingsItem(
                        icon = Icons.Rounded.Restore,
                        title = stringResource(R.string.backup_restore_cloud),
                        subtitle = restoreReason?.let { stringResource(it) },
                        enabled = restoreReason == null && uiState.canExecute,
                        onClick = actions.onRestoreRemote,
                    )
                    InfinitySettingsItem(
                        icon = Icons.Rounded.Link,
                        title = stringResource(R.string.backup_config_webdav),
                        enabled = uiState.canExecute,
                        onClick = actions.onConfigureWebDav,
                        showChevron = true,
                    )
                }
            }
        }
    }
}

/** 设置页与引导恢复面板共用的操作状态与错误文案。 */
@Composable
internal fun backupOperationText(operation: BackupOperation): String? = when (operation) {
    BackupOperation.Idle -> null
    BackupOperation.Initializing -> stringResource(R.string.backup_status_initializing)
    BackupOperation.SavingConfig -> stringResource(R.string.backup_status_saving_config)
    BackupOperation.Testing -> stringResource(R.string.backup_status_testing)
    BackupOperation.ListingRemote -> stringResource(R.string.backup_status_listing)
    BackupOperation.Exporting -> stringResource(R.string.backup_exporting)
    BackupOperation.Uploading -> stringResource(R.string.backup_status_uploading)
    BackupOperation.StagingRestore -> stringResource(R.string.backup_status_staging_restore)
    BackupOperation.CancellingRestore -> stringResource(R.string.backup_status_cancelling)
    BackupOperation.Restarting -> stringResource(R.string.backup_status_restarting)
    is BackupOperation.Success -> if (operation.cleanupWarning) {
        stringResource(R.string.backup_status_success_cleanup_warning)
    } else when (operation.code) {
        BackupSuccessCode.REMOTE_LISTED -> stringResource(R.string.backup_status_remote_listed, operation.itemCount)
        BackupSuccessCode.CONFIG_SAVED -> stringResource(R.string.backup_status_config_saved)
        BackupSuccessCode.PASSWORD_CLEARED -> stringResource(R.string.backup_status_password_cleared)
        BackupSuccessCode.CONFIG_RESET -> stringResource(R.string.backup_status_config_reset)
        BackupSuccessCode.CONNECTION_TESTED -> stringResource(R.string.backup_status_connection_success)
        BackupSuccessCode.EXPORTED -> stringResource(R.string.backup_status_export_success)
        BackupSuccessCode.UPLOADED -> stringResource(R.string.backup_status_upload_success)
    }
    is BackupOperation.Blocked -> backupErrorText(operation.code)
    is BackupOperation.Error -> operation.reason?.let { backupFailureReasonText(it) } ?: backupErrorText(operation.code)
}

@Composable
private fun backupFailureReasonText(reason: BackupFailureReason): String = stringResource(
    when (reason) {
        BackupFailureReason.CAPACITY_EXCEEDED -> R.string.backup_error_reason_capacity
        BackupFailureReason.WORKSPACE_FILE_MISMATCH -> R.string.backup_error_reason_workspace_mismatch
        BackupFailureReason.SKILL_CONTENT_UNSUPPORTED -> R.string.backup_error_reason_skill_content
    },
)

@Composable
private fun backupErrorText(code: BackupErrorCode): String = stringResource(
    when (code) {
        BackupErrorCode.PASSWORD_REQUIRED -> R.string.backup_error_password_required
        BackupErrorCode.PASSWORD_MISMATCH -> R.string.backup_password_mismatch
        BackupErrorCode.CONFIGURATION_MISSING -> R.string.backup_error_configuration_missing
        BackupErrorCode.CONNECTION_FAILED -> R.string.backup_error_connection
        BackupErrorCode.REMOTE_LIST_FAILED -> R.string.backup_error_remote_list
        BackupErrorCode.STALE_SELECTION -> R.string.backup_error_stale_selection
        BackupErrorCode.EXPORT_FAILED -> R.string.backup_error_export
        BackupErrorCode.UPLOAD_FAILED -> R.string.backup_error_upload
        BackupErrorCode.RESTORE_FAILED -> R.string.backup_error_restore
        BackupErrorCode.RESTART_FAILED -> R.string.backup_error_restart
        BackupErrorCode.RESTORE_CLEANUP_FAILED -> R.string.backup_error_cleanup
        BackupErrorCode.DOCUMENT_CREATE_FAILED -> R.string.backup_error_document_create
        BackupErrorCode.DOCUMENT_OPEN_FAILED -> R.string.backup_error_document_open
    },
)

@Composable
internal fun BackupPasswordDialog(
    title: String,
    password: String,
    passwordConfirmation: String,
    requireConfirmation: Boolean,
    onPasswordChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val mismatch = requireConfirmation && passwordConfirmation.isNotEmpty() && password != passwordConfirmation
    val valid = !requireConfirmation || (password.isNotEmpty() && password == passwordConfirmation)
    InfinityConfirmDialog(
        onDismissRequest = onDismiss,
        title = title,
        message = "",
        confirmText = stringResource(R.string.common_btn_confirm),
        cancelText = stringResource(R.string.common_btn_cancel),
        onConfirm = onConfirm,
        onCancel = onDismiss,
        confirmEnabled = valid,
        confirmButtonModifier = Modifier.height(48.dp),
        cancelButtonModifier = Modifier.height(48.dp),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.backup_password_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SecretField(
                    value = password,
                    onValueChange = onPasswordChange,
                    hasStoredSecret = false,
                    onRevealRequest = { null },
                    onClear = { onPasswordChange("") },
                    placeholder = stringResource(R.string.backup_password_hint),
                )
                if (requireConfirmation) {
                    Text(stringResource(R.string.backup_password_confirm_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SecretField(
                        value = passwordConfirmation,
                        onValueChange = onConfirmationChange,
                        hasStoredSecret = false,
                        onRevealRequest = { null },
                        onClear = { onConfirmationChange("") },
                        placeholder = stringResource(R.string.backup_password_confirm_hint),
                    )
                    if (mismatch) Text(stringResource(R.string.backup_password_mismatch), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(stringResource(R.string.backup_restore_password_optional), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
    )
}

internal fun shouldStackBackupActions(fontScale: Float): Boolean = fontScale >= 1.5f

@Composable
private fun ExportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    InfinitySettingsItem(
        title = title,
        subtitle = subtitle,
        icon = icon,
        modifier = modifier,
        enabled = enabled,
        onClick = onClick,
        showChevron = true,
    )
}
