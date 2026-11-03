package com.promenar.infinity.ui.welcome

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.backup.BackupLayer
import com.promenar.infinity.data.remote.webdav.RemoteBackup
import com.promenar.infinity.ui.common.CompactOutlinedTextField
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.SecretField
import com.promenar.infinity.ui.settings.BackupErrorCode
import com.promenar.infinity.ui.settings.BackupOperation
import com.promenar.infinity.ui.settings.backupLayerName
import com.promenar.infinity.ui.settings.backupOperationText
import com.promenar.infinity.ui.settings.formatBackupBytes
import com.promenar.infinity.ui.settings.isCancellable
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors

/** 恢复面板的页面；恢复进行中时无论停在哪一页都显示进度。 */
internal enum class OnboardingRestorePage { SOURCES, WEBDAV, CONFIRM }

/** 恢复面板的界面状态：页面与选中的备份由引导页持有，其余取自设置页的备份视图模型。 */
internal data class OnboardingRestoreSheetState(
    val page: OnboardingRestorePage = OnboardingRestorePage.SOURCES,
    val selected: OnboardingRestorePackage? = null,
    /** 正在读取本机文件的包头。 */
    val inspecting: Boolean = false,
    val operation: BackupOperation = BackupOperation.Idle,
    val canExecute: Boolean = true,
    val webdavUrl: String = "",
    val webdavUser: String = "",
    val hasWebDavPassword: Boolean = false,
    val remoteBackups: List<RemoteBackup> = emptyList(),
    val remoteListed: Boolean = false,
)

internal class OnboardingRestoreSheetActions(
    val onPickFile: () -> Unit = {},
    val onOpenWebDav: () -> Unit = {},
    val onListRemote: (url: String, user: String, password: String) -> Unit = { _, _, _ -> },
    val onRevealWebDavPassword: suspend () -> CharArray? = { null },
    val onSelectRemote: (RemoteBackup) -> Unit = {},
    val onBackToSources: () -> Unit = {},
    val onRestore: (password: String) -> Unit = {},
    val onCancelOperation: () -> Unit = {},
    val onRetryRestart: () -> Unit = {},
    val onCancelRestore: () -> Unit = {},
    val onDismiss: () -> Unit = {},
)

/** 暂存、重启或重启受阻期间面板锁定在进度页，不能下滑关闭。 */
internal val BackupOperation.isRestoreInFlight: Boolean
    get() = this is BackupOperation.StagingRestore || this is BackupOperation.Restarting ||
        this is BackupOperation.CancellingRestore || isRestoreBlocked

private val BackupOperation.isRestoreBlocked: Boolean
    get() = this is BackupOperation.Blocked &&
        (code == BackupErrorCode.RESTART_FAILED || code == BackupErrorCode.RESTORE_CLEANUP_FAILED)

private val BackupOperation.isFailure: Boolean
    get() = this is BackupOperation.Error || this is BackupOperation.Blocked

internal object OnboardingRestoreTags {
    const val ENTRY = "onboarding_restore_entry"
    const val SHEET = "onboarding_restore_sheet"
    const val SOURCE_FILE = "onboarding_restore_source_file"
    const val SOURCE_WEBDAV = "onboarding_restore_source_webdav"
    const val WEBDAV_URL = "onboarding_restore_webdav_url"
    const val WEBDAV_USER = "onboarding_restore_webdav_user"
    const val WEBDAV_PASSWORD = "onboarding_restore_webdav_password"
    const val WEBDAV_LIST = "onboarding_restore_webdav_list"
    const val REMOTE_PREFIX = "onboarding_restore_remote_"
    const val PASSWORD = "onboarding_restore_password"
    const val CONFIRM = "onboarding_restore_confirm"
    const val STATUS = "onboarding_restore_status"
}

/** 第一个配置步骤标题下方的“从备份恢复”入口：图标、标题、副标题与右侧箭头。 */
@Composable
internal fun OnboardingRestoreEntry(onClick: () -> Unit, enabled: Boolean = true) {
    OnboardingSheetOption(
        icon = { Icon(Icons.Rounded.Restore, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) },
        title = stringResource(R.string.onboarding_restore_entry_title),
        subtitle = stringResource(R.string.onboarding_restore_entry_subtitle),
        onClick = onClick,
        enabled = enabled,
        chevron = true,
        color = onboardingCardColor,
        border = BorderStroke(1.dp, onboardingLineColor),
        modifier = Modifier.testTag(OnboardingRestoreTags.ENTRY),
    )
}

/** 从备份恢复的底部面板；恢复进行中拦截下滑与返回关闭。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OnboardingRestoreSheet(state: OnboardingRestoreSheetState, actions: OnboardingRestoreSheetActions) {
    val locked by rememberUpdatedState(state.operation.isRestoreInFlight)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !locked },
    )
    ModalBottomSheet(
        onDismissRequest = { if (!locked) actions.onDismiss() },
        sheetState = sheetState,
        modifier = Modifier.testTag(OnboardingRestoreTags.SHEET),
    ) {
        OnboardingRestoreSheetContent(state, actions)
    }
}

@Composable
internal fun OnboardingRestoreSheetContent(
    state: OnboardingRestoreSheetState,
    actions: OnboardingRestoreSheetActions,
    timeText: @Composable (Long) -> String = { restoreTimeText(it) },
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = InfinitySpacing.XLarge)
            .padding(bottom = InfinitySpacing.XLarge),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when {
            state.operation.isRestoreInFlight -> ProgressPage(state.operation, actions)
            state.page == OnboardingRestorePage.WEBDAV -> WebDavPage(state, actions, timeText)
            state.page == OnboardingRestorePage.CONFIRM && state.selected != null -> ConfirmPage(state, state.selected, actions, timeText)
            else -> SourcesPage(state, actions)
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun SheetLead(text: String) {
    Text(text, style = OnboardingType.body(), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SourcesPage(state: OnboardingRestoreSheetState, actions: OnboardingRestoreSheetActions) {
    SheetTitle(stringResource(R.string.onboarding_restore_sheet_title))
    SheetLead(stringResource(R.string.onboarding_restore_sheet_desc))
    OnboardingSheetOption(
        icon = { Icon(Icons.Rounded.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
        title = stringResource(R.string.onboarding_restore_source_file),
        subtitle = stringResource(R.string.onboarding_restore_source_file_desc),
        onClick = actions.onPickFile,
        enabled = state.canExecute && !state.inspecting,
        modifier = Modifier.testTag(OnboardingRestoreTags.SOURCE_FILE),
    )
    OnboardingSheetOption(
        icon = { Icon(Icons.Rounded.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
        title = stringResource(R.string.onboarding_restore_source_webdav),
        subtitle = stringResource(R.string.onboarding_restore_source_webdav_desc),
        onClick = actions.onOpenWebDav,
        enabled = !state.inspecting,
        modifier = Modifier.testTag(OnboardingRestoreTags.SOURCE_WEBDAV),
    )
    if (state.inspecting) {
        StatusText(stringResource(R.string.onboarding_restore_reading), error = false)
    } else if (state.operation.isFailure) {
        backupOperationText(state.operation)?.let { StatusText(it, error = true) }
    }
    SheetGhostButton(stringResource(R.string.common_btn_cancel), actions.onDismiss)
}

@Composable
private fun WebDavPage(
    state: OnboardingRestoreSheetState,
    actions: OnboardingRestoreSheetActions,
    timeText: @Composable (Long) -> String,
) {
    var url by rememberSaveable(state.webdavUrl) { mutableStateOf(state.webdavUrl) }
    var user by rememberSaveable(state.webdavUser) { mutableStateOf(state.webdavUser) }
    // WebDAV 密码只留在内存，离开面板即清空。
    var password by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { password = "" } }
    val busy = state.operation is BackupOperation.SavingConfig || state.operation is BackupOperation.ListingRemote
    val ready = url.isNotBlank() && user.isNotBlank() && (password.isNotEmpty() || state.hasWebDavPassword)

    SheetTitle(stringResource(R.string.onboarding_restore_source_webdav))
    CompactOutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = stringResource(R.string.backup_webdav_url_label),
        placeholder = stringResource(R.string.backup_webdav_url_hint),
        supportingText = com.promenar.infinity.ui.common.cleartextUrlHelp(url),
        enabled = !busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().testTag(OnboardingRestoreTags.WEBDAV_URL),
    )
    CompactOutlinedTextField(
        value = user,
        onValueChange = { user = it },
        label = stringResource(R.string.backup_webdav_user_label),
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().testTag(OnboardingRestoreTags.WEBDAV_USER),
    )
    SecretField(
        value = password,
        onValueChange = { password = it },
        hasStoredSecret = state.hasWebDavPassword,
        onRevealRequest = actions.onRevealWebDavPassword,
        onClear = { password = "" },
        label = stringResource(R.string.backup_webdav_pass_label),
        compact = true,
        modifier = Modifier.fillMaxWidth().testTag(OnboardingRestoreTags.WEBDAV_PASSWORD),
    )
    OnboardingPrimaryButton(
        onClick = {
            actions.onListRemote(url.trim(), user.trim(), password)
            password = ""
        },
        enabled = ready && !busy && state.canExecute,
        modifier = Modifier.testTag(OnboardingRestoreTags.WEBDAV_LIST),
    ) { Text(stringResource(R.string.onboarding_restore_webdav_list), style = OnboardingType.button(), maxLines = 1) }
    backupOperationText(state.operation)?.let { StatusText(it, error = state.operation.isFailure) }
    if (state.remoteListed) {
        Text(stringResource(R.string.onboarding_restore_webdav_backups), style = OnboardingType.eyebrow(), color = onboardingFaintText)
        if (state.remoteBackups.isEmpty()) {
            Text(stringResource(R.string.onboarding_restore_webdav_empty), style = OnboardingType.caption(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.remoteBackups.forEach { remote ->
            OnboardingSheetOption(
                icon = { Icon(Icons.Rounded.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
                title = remote.fileName,
                subtitle = "",
                meta = listOfNotNull(
                    remote.lastModifiedEpochMillis.takeIf { it > 0 }?.let { timeText(it) },
                    remote.sizeBytes.takeIf { it >= 0 }?.let { formatBackupBytes(it) },
                ),
                onClick = { actions.onSelectRemote(remote) },
                enabled = state.canExecute,
                modifier = Modifier.testTag(OnboardingRestoreTags.REMOTE_PREFIX + remote.fileName),
            )
        }
    }
    SheetGhostButton(stringResource(R.string.onboarding_restore_back), actions.onBackToSources)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfirmPage(
    state: OnboardingRestoreSheetState,
    selected: OnboardingRestorePackage,
    actions: OnboardingRestoreSheetActions,
    timeText: @Composable (Long) -> String,
) {
    // 备份密码只留在内存，不进入可持久化的界面保存状态。
    var password by remember(selected) { mutableStateOf("") }
    DisposableEffect(selected) { onDispose { password = "" } }
    val peek = selected.peek
    val warning = MaterialTheme.infinityDomainColors.warning

    SheetTitle(stringResource(R.string.onboarding_restore_confirm_title))
    Surface(
        shape = RoundedCornerShape(OnboardingDimens.CardRadius),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(selected.name, style = OnboardingType.rowTitle(), color = MaterialTheme.colorScheme.onSurface)
            val meta = listOfNotNull(
                (peek.createdAt ?: selected.modifiedAt)?.let { timeText(it) },
                selected.sizeBytes?.takeIf { it >= 0 }?.let { formatBackupBytes(it) },
                peek.appVersion?.let { stringResource(R.string.onboarding_restore_meta_version, it) },
            )
            if (meta.isNotEmpty()) {
                InfinityMetaLine(segments = meta, style = OnboardingType.rowSubtitle(), color = onboardingFaintText)
            }
            peek.layers?.let { layers ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BackupLayer.entries.filter { it in layers }.forEach { LayerChip(backupLayerName(it), included = true) }
                    if (BackupLayer.SECRETS !in layers) LayerChip(stringResource(R.string.onboarding_restore_keys_missing), included = false)
                }
            }
            when {
                peek.encrypted == true -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = warning, modifier = Modifier.size(14.dp))
                    Text(stringResource(R.string.onboarding_restore_encrypted_hint), style = OnboardingType.caption(), color = warning)
                }
                peek.encrypted == null -> Text(
                    stringResource(R.string.onboarding_restore_unknown_hint),
                    style = OnboardingType.caption(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (peek.needsPassword) {
        SecretField(
            value = password,
            onValueChange = { password = it },
            hasStoredSecret = false,
            onRevealRequest = { null },
            onClear = { password = "" },
            label = stringResource(R.string.backup_password_label),
            placeholder = stringResource(R.string.backup_password_hint),
            supportingText = if (peek.passwordOptional) stringResource(R.string.backup_restore_password_optional) else null,
            compact = true,
            modifier = Modifier.fillMaxWidth().testTag(OnboardingRestoreTags.PASSWORD),
        )
    }
    if (state.operation.isFailure) {
        backupOperationText(state.operation)?.let { StatusText(it, error = true) }
    }
    OnboardingPrimaryButton(
        onClick = {
            actions.onRestore(password)
            password = ""
        },
        enabled = state.canExecute && (peek.encrypted != true || password.isNotEmpty()),
        modifier = Modifier.testTag(OnboardingRestoreTags.CONFIRM),
    ) { Text(stringResource(R.string.onboarding_restore_confirm), style = OnboardingType.button(), maxLines = 1) }
    SheetGhostButton(stringResource(R.string.onboarding_restore_change), actions.onBackToSources)
}

@Composable
private fun ProgressPage(operation: BackupOperation, actions: OnboardingRestoreSheetActions) {
    SheetTitle(stringResource(R.string.onboarding_restore_progress_title))
    if (!operation.isRestoreBlocked) {
        val label = stringResource(R.string.onboarding_restore_progress_title)
        LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = label })
    }
    backupOperationText(operation)?.let { StatusText(it, error = operation.isRestoreBlocked) }
    SheetLead(stringResource(R.string.onboarding_restore_progress_note))
    when {
        operation is BackupOperation.Blocked && operation.code == BackupErrorCode.RESTART_FAILED -> {
            OnboardingPrimaryButton(onClick = actions.onRetryRestart) {
                Text(stringResource(R.string.onboarding_restore_retry_restart), style = OnboardingType.button(), maxLines = 1)
            }
            SheetGhostButton(stringResource(R.string.onboarding_restore_cancel_restore), actions.onCancelRestore)
        }
        operation.isCancellable -> SheetGhostButton(stringResource(R.string.common_btn_cancel), actions.onCancelOperation)
    }
}

@Composable
private fun StatusText(text: String, error: Boolean) {
    Text(
        text,
        style = OnboardingType.caption(),
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OnboardingRestoreTags.STATUS)
            .semantics { liveRegion = if (error) LiveRegionMode.Assertive else LiveRegionMode.Polite },
    )
}

@Composable
private fun LayerChip(text: String, included: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (included) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (included) MaterialTheme.colorScheme.onPrimaryContainer else onboardingFaintText,
        border = if (included) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(text, style = OnboardingType.caption(), maxLines = 1, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

/** 面板底部的文字按钮：整宽、单行、主色文字。 */
@Composable
private fun SheetGhostButton(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        shape = CircleShape,
        modifier = Modifier.fillMaxWidth().height(OnboardingDimens.ButtonHeight),
    ) { Text(text, style = OnboardingType.button(), maxLines = 1) }
}

/** 面板与入口共用的选项行：图标块、主副文字，可选右侧箭头。 */
@Composable
private fun OnboardingSheetOption(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    meta: List<String> = emptyList(),
    enabled: Boolean = true,
    chevron: Boolean = false,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainer,
    border: BorderStroke? = null,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(OnboardingDimens.CardRadius),
        color = color,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = border,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.heightIn(min = OnboardingDimens.RowMinHeight).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OnboardingIconTile { icon() }
            Column(Modifier.weight(1f)) {
                Text(title, style = OnboardingType.rowTitle(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = OnboardingType.rowSubtitle(), color = onboardingFaintText)
                }
                InfinityMetaLine(meta, style = OnboardingType.rowSubtitle(), color = onboardingFaintText)
            }
            if (chevron) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowForwardIos,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun restoreTimeText(millis: Long): String = DateUtils.formatDateTime(
    LocalContext.current,
    millis,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_NUMERIC_DATE,
)
