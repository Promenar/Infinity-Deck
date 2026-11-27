package com.promenar.infinity.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.ssh.SshHost
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.domain.storage.SftpRemoteDirEntry
import com.promenar.infinity.domain.storage.SftpRemoteMountSpec
import com.promenar.infinity.domain.storage.SftpRemoteProbeResult
import com.promenar.infinity.ui.chat.components.isGeneratedMountAlias
import com.promenar.infinity.ui.chat.components.mountAliasFromFolderName
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinityLoadingIndicator
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityNoticeSeverity
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.LocalInfinityNoticeController
import com.promenar.infinity.ui.production.ProductionStatusChip
import com.promenar.infinity.ui.production.ProductionTone
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.breakableIdentifier
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.hierarchicalTransition
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 添加或编辑资料库 SFTP 远程目录的底部全高表单面板。 */
@Composable
internal fun SftpMountSheet(
    editingMount: ExternalMount? = null,
    existingAliases: Collection<String> = emptyList(),
    onOpenSshSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as InfinityApplication
    val hosts by app.sshHostStore.observeHosts().collectAsStateWithLifecycle(initialValue = emptyList())
    val noticeController = LocalInfinityNoticeController.current
    val scope = rememberCoroutineScope()

    val editingSpec = remember(editingMount?.id, editingMount?.sourceId) {
        editingMount?.let { SftpRemoteMountSpec.decode(it.sourceId) }
    }
    var selectedHostAlias by rememberSaveable(editingMount?.id) {
        mutableStateOf(editingSpec?.hostAlias.orEmpty())
    }
    var remotePath by rememberSaveable(editingMount?.id) {
        mutableStateOf(editingSpec?.rootPath ?: "/")
    }
    var mountAlias by rememberSaveable(editingMount?.id) {
        mutableStateOf(editingMount?.alias ?: "")
    }
    var aliasCustomized by rememberSaveable(editingMount?.id) {
        mutableStateOf(editingMount != null)
    }
    var readOnly by rememberSaveable(editingMount?.id) {
        mutableStateOf(editingMount?.readOnly ?: true)
    }
    var browsingRemote by rememberSaveable(editingMount?.id) { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var probeResult by remember { mutableStateOf<SftpRemoteProbeResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(hosts) {
        if (selectedHostAlias.isBlank() && hosts.isNotEmpty()) {
            selectedHostAlias = hosts.first().alias
        }
    }

    val otherAliases = remember(existingAliases, editingMount?.alias) {
        existingAliases.filterNot { it == editingMount?.alias }
    }
    val applySuggestedAlias: (String) -> Unit = { path ->
        if (!aliasCustomized || mountAlias.isBlank() || isGeneratedMountAlias(mountAlias)) {
            val segment = path.trim().trimEnd('/').substringAfterLast('/')
            val suggested = if (segment.isNotBlank()) {
                mountAliasFromFolderName(segment, otherAliases)
            } else {
                selectedHostAlias.takeIf { it.isNotBlank() }?.let { mountAliasFromFolderName(it, otherAliases) }
            }
            if (suggested != null) mountAlias = suggested
        }
    }

    val savedNoticeText = stringResource(R.string.sftp_mount_notice_saved)
    val probeFailedFallback = stringResource(R.string.sftp_mount_probe_failed)

    InfinityBottomSheet(
        show = true,
        onDismiss = { if (!saving) onDismiss() },
        height = InfinitySheetHeight.Large,
        dismissEnabled = !saving,
        dragGesturesEnabled = false,
    ) {
        BackHandler(enabled = browsingRemote) {
            browsingRemote = false
        }
        AnimatedContent(
            targetState = browsingRemote,
            transitionSpec = { hierarchicalTransition { if (it) 1 else 0 } },
            label = "sftp_mount_sheet_page",
        ) { inBrowser ->
            if (inBrowser) {
                SftpRemoteDirectoryBrowser(
                    hostAlias = selectedHostAlias,
                    initialPath = remotePath.ifBlank { "/" },
                    onListDirs = { path -> app.externalFolderManager.browseSftpDirectories(selectedHostAlias, path) },
                    onBack = { browsingRemote = false },
                    onSelectPath = { picked ->
                        remotePath = picked
                        probeResult = null
                        errorMessage = null
                        applySuggestedAlias(picked)
                        browsingRemote = false
                    },
                )
            } else {
                SftpMountFormBody(
                    editing = editingMount != null,
                    hosts = hosts,
                    selectedHostAlias = selectedHostAlias,
                    remotePath = remotePath,
                    mountAlias = mountAlias,
                    readOnly = readOnly,
                    probing = probing,
                    saving = saving,
                    probeResult = probeResult,
                    errorMessage = errorMessage,
                    onBack = { if (!saving) onDismiss() },
                    onSelectHost = { alias ->
                        selectedHostAlias = alias
                        probeResult = null
                        errorMessage = null
                        applySuggestedAlias(remotePath)
                    },
                    onOpenSshSettings = {
                        onDismiss()
                        onOpenSshSettings()
                    },
                    onRemotePathChange = { next ->
                        remotePath = next
                        probeResult = null
                        errorMessage = null
                        applySuggestedAlias(next)
                    },
                    onAliasChange = { next ->
                        mountAlias = next
                        aliasCustomized = next.isNotBlank()
                        errorMessage = null
                    },
                    onReadOnlyChange = { readOnly = it },
                    onBrowseRemote = {
                        errorMessage = null
                        browsingRemote = true
                    },
                    onProbe = {
                        if (probing || saving || selectedHostAlias.isBlank() || remotePath.isBlank()) return@SftpMountFormBody
                        probing = true
                        errorMessage = null
                        probeResult = null
                        scope.launch {
                            try {
                                val res = app.externalFolderManager.probeSftpRemote(selectedHostAlias, remotePath.trim())
                                probeResult = res
                                if (!res.reachable) {
                                    errorMessage = res.errorMessage ?: res.errorCode ?: probeFailedFallback
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                errorMessage = error.message ?: probeFailedFallback
                            } finally {
                                probing = false
                            }
                        }
                    },
                    onSave = {
                        if (saving || selectedHostAlias.isBlank() || remotePath.isBlank() || mountAlias.isBlank()) return@SftpMountFormBody
                        saving = true
                        errorMessage = null
                        scope.launch {
                            try {
                                val spec = SftpRemoteMountSpec(selectedHostAlias.trim(), remotePath.trim()).normalized()
                                if (editingMount != null) {
                                    app.externalFolderManager.updateSftpRemote(
                                        mountId = editingMount.id,
                                        alias = mountAlias.trim(),
                                        spec = spec,
                                        readOnly = readOnly,
                                    )
                                } else {
                                    app.externalFolderManager.addSftpRemote(
                                        alias = mountAlias.trim(),
                                        sessionId = ExternalMountPaths.LIBRARY_SCOPE,
                                        spec = spec,
                                        readOnly = readOnly,
                                    )
                                }
                                noticeController?.show(InfinityNoticeSeverity.SUCCESS, savedNoticeText)
                                onDismiss()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                errorMessage = error.message ?: error.javaClass.simpleName
                            } finally {
                                saving = false
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SftpMountFormBody(
    editing: Boolean,
    hosts: List<SshHost>,
    selectedHostAlias: String,
    remotePath: String,
    mountAlias: String,
    readOnly: Boolean,
    probing: Boolean,
    saving: Boolean,
    probeResult: SftpRemoteProbeResult?,
    errorMessage: String?,
    onBack: () -> Unit,
    onSelectHost: (String) -> Unit,
    onOpenSshSettings: () -> Unit,
    onRemotePathChange: (String) -> Unit,
    onAliasChange: (String) -> Unit,
    onReadOnlyChange: (Boolean) -> Unit,
    onBrowseRemote: () -> Unit,
    onProbe: () -> Unit,
    onSave: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val busy = probing || saving
    val validHost = selectedHostAlias.isNotBlank() && hosts.any { it.alias == selectedHostAlias }
    val validPath = remember(remotePath) {
        runCatching { SftpRemoteMountSpec.canonicalPath(remotePath.trim()) }.isSuccess
    }
    val validAlias = remember(mountAlias) {
        runCatching { ExternalMountPaths.validateAlias(mountAlias.trim()) }.isSuccess
    }
    val canProbe = !busy && validHost && validPath
    val canSave = !busy && validHost && validPath && validAlias

    Column(Modifier.fillMaxSize().testTag("sftp_mount_sheet_form")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, enabled = !saving) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.fm_back), tint = scheme.onSurface)
            }
            Text(
                text = stringResource(if (editing) R.string.sftp_mount_sheet_edit_title else R.string.sftp_mount_sheet_add_title),
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
        ) {
            // 1. 选择 SSH 主机（直接复用设置 -> SSH）
            InfinitySettingsSection(
                title = stringResource(R.string.sftp_mount_section_host),
                footer = stringResource(R.string.sftp_mount_section_host_footer),
                typographicFooter = true,
            ) {
                if (hosts.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(InfinitySpacing.Large),
                        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                    ) {
                        Text(
                            text = stringResource(R.string.sftp_mount_no_hosts),
                            style = MaterialTheme.typography.titleSmall,
                            color = scheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.sftp_mount_no_hosts_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        InfinityPillButton(
                            text = stringResource(R.string.sftp_mount_open_ssh_settings),
                            icon = Icons.Rounded.Settings,
                            onClick = onOpenSshSettings,
                            tone = InfinityPillTone.PRIMARY,
                            compact = true,
                        )
                    }
                } else {
                    hosts.forEachIndexed { index, host ->
                        if (index > 0) InfinityGroupDivider()
                        SshHostOptionRow(
                            host = host,
                            selected = host.alias == selectedHostAlias,
                            enabled = !busy,
                            onClick = { onSelectHost(host.alias) },
                        )
                    }
                }
            }

            // 2. 远端根目录与别名
            Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                ) {
                    Text(
                        text = stringResource(R.string.sftp_mount_section_path),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    InfinityPillButton(
                        text = stringResource(R.string.sftp_mount_browse_remote),
                        icon = Icons.Rounded.FolderOpen,
                        onClick = onBrowseRemote,
                        enabled = !busy && validHost,
                        tone = InfinityPillTone.NEUTRAL,
                        compact = true,
                        modifier = Modifier.testTag("sftp_mount_browse_remote"),
                    )
                }
                OutlinedTextField(
                    value = remotePath,
                    onValueChange = onRemotePathChange,
                    label = { Text(stringResource(R.string.sftp_mount_remote_path_label)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = InfinityMonospace).forInput(),
                    singleLine = true,
                    enabled = !busy,
                    isError = remotePath.isNotBlank() && !validPath,
                    modifier = Modifier.fillMaxWidth().testTag("sftp_mount_remote_path"),
                )
                OutlinedTextField(
                    value = mountAlias,
                    onValueChange = onAliasChange,
                    label = { Text(stringResource(R.string.sftp_mount_alias_label)) },
                    supportingText = {
                        Text(
                            stringResource(
                                R.string.sftp_mount_alias_supporting,
                                mountAlias.trim().ifBlank { "alias" },
                            ),
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.forInput(),
                    singleLine = true,
                    enabled = !busy,
                    isError = mountAlias.isNotBlank() && !validAlias,
                    modifier = Modifier.fillMaxWidth().testTag("sftp_mount_alias"),
                )
            }

            // 3. 访问权限（新建默认只读）
            InfinitySettingsSection(
                title = stringResource(R.string.sftp_mount_section_permission),
                footer = stringResource(R.string.sftp_mount_section_permission_footer),
                typographicFooter = true,
            ) {
                PermissionModeRow(
                    icon = Icons.Rounded.Lock,
                    title = stringResource(R.string.sftp_mount_mode_readonly_title),
                    subtitle = stringResource(R.string.sftp_mount_mode_readonly_desc),
                    selected = readOnly,
                    enabled = !busy,
                    onClick = { onReadOnlyChange(true) },
                )
                InfinityGroupDivider()
                PermissionModeRow(
                    icon = Icons.Rounded.LockOpen,
                    title = stringResource(R.string.sftp_mount_mode_rw_title),
                    subtitle = stringResource(R.string.sftp_mount_mode_rw_desc),
                    selected = !readOnly,
                    enabled = !busy,
                    onClick = { onReadOnlyChange(false) },
                )
            }

            // 4. 连通性探测结果或错误提示
            if (probeResult != null && probeResult.reachable) {
                val capSegments = buildList {
                    add(stringResource(if (probeResult.writable) R.string.sftp_mount_probe_caps_writable else R.string.sftp_mount_probe_caps_readonly))
                    if (probeResult.supportsHardlink) add(stringResource(R.string.sftp_mount_probe_caps_hardlink))
                    probeResult.checksumTool?.takeIf { it.isNotBlank() }?.let { tool ->
                        add(stringResource(R.string.sftp_mount_probe_caps_checksum, tool))
                    }
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = domain.successContainer.copy(alpha = 0.35f),
                    border = BorderStroke(0.75.dp, domain.success.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth().testTag("sftp_mount_probe_success"),
                ) {
                    Row(
                        modifier = Modifier.padding(InfinitySpacing.Medium),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                    ) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = domain.success, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.sftp_mount_probe_ok, probeResult.latencyMs),
                                style = MaterialTheme.typography.labelLarge,
                                color = scheme.onSurface,
                            )
                            InfinityMetaLine(
                                segments = capSegments,
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 3,
                            )
                        }
                    }
                }
            } else if (!errorMessage.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = scheme.errorContainer.copy(alpha = 0.4f),
                    border = BorderStroke(0.75.dp, scheme.error.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth().testTag("sftp_mount_probe_error"),
                ) {
                    Row(
                        modifier = Modifier.padding(InfinitySpacing.Medium),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                    ) {
                        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = scheme.error, modifier = Modifier.size(20.dp))
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(InfinitySpacing.Small))
        }

        // 底部固定操作行
        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.35f))
        Box(Modifier.fillMaxWidth().padding(top = InfinitySpacing.Medium)) {
            InfinityPillRow {
                InfinityPillButton(
                    text = stringResource(if (probing) R.string.sftp_mount_testing else R.string.sftp_mount_test_connection),
                    icon = Icons.Rounded.Refresh,
                    onClick = onProbe,
                    enabled = canProbe,
                    tone = InfinityPillTone.NEUTRAL,
                    modifier = Modifier.testTag("sftp_mount_test_btn"),
                )
                InfinityPillButton(
                    text = stringResource(if (saving) R.string.sftp_mount_saving else R.string.sftp_mount_save),
                    icon = Icons.Rounded.CloudDone,
                    onClick = onSave,
                    enabled = canSave,
                    tone = InfinityPillTone.PRIMARY,
                    modifier = Modifier.testTag("sftp_mount_save_btn"),
                )
            }
        }
    }
}

@Composable
private fun SshHostOptionRow(
    host: SshHost,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val pinned = !host.trustedFingerprint.isNullOrBlank()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Icon(Icons.Rounded.Dns, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = host.alias,
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                ProductionStatusChip(
                    text = stringResource(if (pinned) R.string.sftp_mount_host_pinned else R.string.sftp_mount_host_tofu),
                    tone = if (pinned) ProductionTone.INFO else ProductionTone.NEUTRAL,
                )
            }
            Text(
                text = "${host.username}@${host.hostname}:${host.port}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RadioButton(selected = selected, onClick = null, enabled = enabled)
    }
}

@Composable
private fun PermissionModeRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        RadioButton(selected = selected, onClick = null, enabled = enabled)
    }
}

@Composable
private fun SftpRemoteDirectoryBrowser(
    hostAlias: String,
    initialPath: String,
    onListDirs: suspend (String) -> List<SftpRemoteDirEntry>,
    onBack: () -> Unit,
    onSelectPath: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var currentPath by rememberSaveable(hostAlias) {
        mutableStateOf(runCatching { SftpRemoteMountSpec.canonicalPath(initialPath) }.getOrDefault("/"))
    }
    var dirs by remember { mutableStateOf<List<SftpRemoteDirEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadNonce by remember { mutableStateOf(0) }

    LaunchedEffect(hostAlias, currentPath, reloadNonce) {
        loading = true
        error = null
        try {
            dirs = onListDirs(currentPath).sortedBy { it.name.lowercase() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            dirs = emptyList()
            error = failure.message ?: failure.javaClass.simpleName
        } finally {
            loading = false
        }
    }

    val parentPath = remember(currentPath) {
        if (currentPath == "/") null
        else currentPath.substringBeforeLast('/').ifEmpty { "/" }
    }

    Column(Modifier.fillMaxSize().testTag("sftp_remote_dir_browser")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.fm_back), tint = scheme.onSurface)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.sftp_mount_browser_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$hostAlias:$currentPath".breakableIdentifier(),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { reloadNonce++ }, enabled = !loading) {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.fm_retry), tint = scheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.35f))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { InfinityLoadingIndicator() }
                error != null -> Column(
                    modifier = Modifier.fillMaxSize().padding(InfinitySpacing.Large),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = scheme.error, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(InfinitySpacing.Small))
                    Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(InfinitySpacing.Medium))
                    InfinityPillButton(
                        text = stringResource(R.string.fm_retry),
                        icon = Icons.Rounded.Refresh,
                        onClick = { reloadNonce++ },
                        tone = InfinityPillTone.NEUTRAL,
                        compact = true,
                    )
                }
                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = InfinitySpacing.Small),
                ) {
                    if (parentPath != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { currentPath = parentPath }
                                .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                                .padding(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.Small),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                            Column(Modifier.weight(1f)) {
                                Text("..", style = MaterialTheme.typography.bodyLarge.copy(fontFamily = InfinityMonospace), color = scheme.onSurface)
                                Text(stringResource(R.string.fm_up_level), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (dirs.isEmpty()) {
                        Text(
                            text = stringResource(R.string.sftp_mount_browser_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(InfinitySpacing.Large),
                        )
                    } else {
                        dirs.forEach { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(role = Role.Button) { currentPath = entry.fullPath }
                                    .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                                    .padding(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.Small),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                            ) {
                                Icon(Icons.Rounded.Folder, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = scheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = scheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.35f))
        Box(Modifier.fillMaxWidth().padding(top = InfinitySpacing.Medium)) {
            InfinityPillButton(
                text = stringResource(R.string.sftp_mount_browser_use_here),
                icon = Icons.Rounded.CheckCircle,
                onClick = { onSelectPath(currentPath) },
                tone = InfinityPillTone.PRIMARY,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
