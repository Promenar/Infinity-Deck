package com.promenar.infinity.ui.settings

import androidx.compose.ui.text.style.TextOverflow
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySplitPillButton
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Key
import com.promenar.infinity.ui.common.UnavailableReasons
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.testTag
import com.promenar.infinity.ui.common.InfinitySettingsGroup
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.promenar.infinity.ui.common.SecretKeyboardOptions
import com.promenar.infinity.ui.common.SecretTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.data.security.SshCredential
import com.promenar.infinity.data.ssh.SshAuthType
import com.promenar.infinity.data.ssh.SshAgentPreferences
import com.promenar.infinity.data.ssh.SshActiveConnection
import com.promenar.infinity.data.ssh.SshHost
import com.promenar.infinity.data.ssh.SshHostKey
import com.promenar.infinity.data.ssh.SshKeyGenerator
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinitySnackbar
import com.promenar.infinity.ui.common.InfinitySnackbarData
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.common.SnackbarType
import com.promenar.infinity.ui.ssh.SshHostDraft
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Stable
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.promenar.infinity.data.ssh.SshPolicy
import com.promenar.infinity.ui.ssh.SshErrorMessages
import com.promenar.infinity.ui.ssh.sshErrorText
import com.promenar.infinity.ui.ssh.SshInputException
import com.promenar.infinity.ui.ssh.SshPrivateKeyInput
import com.promenar.infinity.ui.ssh.safeSshError
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.common.InfinityStatusKind
import com.promenar.infinity.ui.common.InfinityStatusStrip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.promenar.infinity.ui.common.InfinityMetaLine
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.runtime.LaunchedEffect
import com.promenar.infinity.data.ssh.SshTerminalIdlePolicy
import com.promenar.infinity.data.tailnet.TailnetPeer
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.ssh.SshHostEditorFieldsTarget
import com.promenar.infinity.ui.ssh.SshTailnetAvailability
import com.promenar.infinity.ui.ssh.SshTailnetCandidate
import com.promenar.infinity.ui.ssh.SshTailnetPickerSheet
import com.promenar.infinity.ui.ssh.applyTailnetCandidate
import com.promenar.infinity.ui.ssh.sshTailnetAvailability
import com.promenar.infinity.ui.ssh.sshTailnetCandidates

private enum class SshPendingAction { VERIFY, TERMINAL, FORWARD }
private data class SshTrustPrompt(val host: SshHost, val key: SshHostKey, val action: SshPendingAction)

/**
 * SSH 主机设置。[leadingContent] 插在列表最前，供会话“远程”面板放“智能体正在使用”；
 * 嵌入面板时由 [com.promenar.infinity.ui.common.LocalInfinitySettingsEmbedded] 去掉顶栏。
 */
@Composable
fun SshSettingsScreen(
    onBack: () -> Unit,
    onOpenTerminal: (String) -> Unit,
    leadingContent: (LazyListScope.() -> Unit)? = null,
    importPeer: TailnetPeer? = null,
    onImportConsumed: () -> Unit = {},
    onOpenTailscale: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext as InfinityApplication
    val store = app.sshHostStore
    val runtime = app.sshRuntime
    val hosts by remember(store) { store.observeHosts() }.collectAsState(initial = emptyList())
    val forwards by runtime.forwardStates.collectAsState()
    val activity by runtime.activity.collectAsState()
    val cleanupWarnings by runtime.cleanupWarnings.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<SshHost?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    // 从“远程 › Tailscale”带来的设备：打开新建面板并预填。
    var editorPeer by remember { mutableStateOf<TailnetPeer?>(null) }
    LaunchedEffect(importPeer) {
        if (importPeer != null) {
            editing = null
            editorPeer = importPeer
            showEditor = true
            onImportConsumed()
        }
    }
    var deleting by remember { mutableStateOf<SshHost?>(null) }
    var trustPrompt by remember { mutableStateOf<SshTrustPrompt?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    val settingsPrefs = remember(context) { context.getSharedPreferences("infinity_settings", Context.MODE_PRIVATE) }
    var agentManagement by remember(settingsPrefs) { mutableStateOf(SshAgentPreferences.isHostManagementEnabled(settingsPrefs)) }
    // 设置页与会话“远程”面板可能同时显示该开关，跟随偏好变化刷新。
    DisposableEffect(settingsPrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == SshAgentPreferences.HOST_MANAGEMENT_KEY) agentManagement = SshAgentPreferences.isHostManagementEnabled(prefs)
        }
        settingsPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { settingsPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val idlePolicy by remember(settingsPrefs) { SshTerminalIdlePolicy.observe(settingsPrefs) }
        .collectAsState(initial = remember(settingsPrefs) { SshTerminalIdlePolicy.read(settingsPrefs) })
    val copiedMessage = stringResource(R.string.ssh_public_key_copied)
    val verifiedMessage = stringResource(R.string.ssh_fingerprint_matches)

    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        status = null
        statusIsError = false
        scope.launch {
            try {
                action()
            } catch (error: Exception) {
                status = SshErrorMessages.message(context, safeSshError(error))
                statusIsError = true
            } finally {
                busy = false
            }
        }
    }

    suspend fun finishAction(host: SshHost, action: SshPendingAction) {
        when (action) {
            SshPendingAction.VERIFY -> {
                status = verifiedMessage
                statusIsError = false
            }
            SshPendingAction.TERMINAL -> {
                // 用户明确打开终端：已断开的终端在此重新连接，其余情况沿用正在运行的终端。
                com.promenar.infinity.ui.ssh.SshUserTerminals.open(app, host.alias)
                onOpenTerminal(host.alias)
            }
            SshPendingAction.FORWARD -> withContext(Dispatchers.IO) { runtime.startForward(host.alias) }
        }
    }

    fun verify(host: SshHost, action: SshPendingAction) = perform {
        val key = withContext(Dispatchers.IO) { runtime.probe(host.alias) }
        if (host.trustedFingerprint == key.fingerprint && host.trustedKeyType == key.type) {
            finishAction(host, action)
        } else {
            trustPrompt = SshTrustPrompt(host, key, action)
        }
    }

    Box(Modifier.fillMaxSize()) {
        SshSettingsContent(
            state = SshSettingsVisualState(
                hosts = hosts,
                forwards = forwards,
                busy = busy,
                cleanupWarning = cleanupWarnings.isNotEmpty(),
                status = status,
                statusIsError = statusIsError,
                agentManagementEnabled = agentManagement,
                activeConnections = activity.connections,
                terminalIdlePolicy = idlePolicy,
            ),
            leadingContent = leadingContent,
            actions = SshSettingsVisualActions(
                onOpenConnectedTerminal = onOpenTerminal,
                onDisconnect = { connection ->
                    perform { withContext(Dispatchers.IO) { runtime.disconnect(connection.owner, connection.alias) } }
                },
                onTerminalIdlePolicyChange = { SshTerminalIdlePolicy.write(settingsPrefs, it) },
                onAgentManagementChange = { value ->
                    settingsPrefs.edit().putBoolean(SshAgentPreferences.HOST_MANAGEMENT_KEY, value).apply()
                    agentManagement = value
                },
                onBack = onBack,
                onAddHost = { editing = null; editorPeer = null; showEditor = true },
                onTerminal = { verify(it, SshPendingAction.TERMINAL) },
                onVerify = { verify(it, SshPendingAction.VERIFY) },
                onEdit = { editing = it; editorPeer = null; showEditor = true },
                onDelete = { deleting = it },
                onCopyPublicKey = { host ->
                    perform {
                        val publicKey = withContext(Dispatchers.IO) {
                            store.credential(host)?.use { credential ->
                                SshKeyGenerator.publicKey(credential.privateKey, credential.passphrase)
                            } ?: throw SshInputException("SSH_CREDENTIAL_MISSING")
                        }
                        copySshPublicKey(context, publicKey)
                        scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                        status = copiedMessage
                        statusIsError = false
                    }
                },
                onToggleForward = { host ->
                    if (forwards.containsKey(host.alias)) perform {
                        withContext(Dispatchers.IO) { runtime.stopForward(host.alias) }
                    } else verify(host, SshPendingAction.FORWARD)
                },
            ),
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        ) { data ->
            InfinitySnackbar(InfinitySnackbarData(data.visuals.message, SnackbarType.SUCCESS))
        }
    }

    if (showEditor) {
        SshHostEditorSheet(
            existing = editing,
            hosts = hosts,
            initialPeer = editorPeer,
            onDismiss = { showEditor = false; editing = null; editorPeer = null },
            onSave = { host, credential, generate ->
                val publicKey = withContext(Dispatchers.IO) {
                    if (generate) {
                        SshKeyGenerator.generate().use { generated ->
                            SshCredential(privateKey = generated.privateKey.copyOf()).use {
                                store.save(host, it)
                            }
                            generated.publicKey
                        }
                    } else {
                        if (credential != null && host.authType == SshAuthType.KEY) {
                            SshKeyGenerator.publicKey(credential.privateKey, credential.passphrase)
                        }
                        store.save(host, credential)
                        null
                    }
                }
                if (publicKey != null) {
                    copySshPublicKey(context, publicKey)
                    scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                    status = copiedMessage
                    statusIsError = false
                }
                showEditor = false
                editing = null
                editorPeer = null
            },
        )
    }

    deleting?.let { host ->
        InfinityConfirmDialog(
            onDismissRequest = { if (!busy) deleting = null },
            title = stringResource(R.string.ssh_delete_title),
            message = stringResource(R.string.ssh_delete_message, host.alias),
            confirmText = stringResource(R.string.ssh_delete),
            cancelText = stringResource(R.string.ssh_cancel),
            onConfirm = {
                perform {
                    withContext(Dispatchers.IO) {
                        runtime.stopForward(host.alias)
                        store.delete(host.alias)
                    }
                    deleting = null
                }
            },
            // 忙碌时取消按钮原为禁用；统一对话框无禁用态，改为忙碌期间忽略点击。
            onCancel = { deleting = null },
            cancelEnabled = !busy,
            isDestructive = true,
            confirmEnabled = !busy,
            confirmButtonModifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget),
            cancelButtonModifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget),
        )
    }

    trustPrompt?.let { prompt ->
        InfinityConfirmDialog(
            onDismissRequest = { if (!busy) trustPrompt = null },
            title = stringResource(if (prompt.host.trustedFingerprint == null) R.string.ssh_trust_title else R.string.ssh_changed_title),
            message = "",
            confirmText = stringResource(if (prompt.host.trustedFingerprint == null) R.string.ssh_trust else R.string.ssh_replace_trust),
            cancelText = stringResource(R.string.ssh_cancel),
            onConfirm = {
                perform {
                    val accepted = withContext(Dispatchers.IO) {
                        store.trust(prompt.host.alias, prompt.host.trustedFingerprint, prompt.key, prompt.host)
                    }
                    if (!accepted) throw SshInputException("SSH_HOST_CHANGED_RETRY")
                    trustPrompt = null
                    finishAction(prompt.host, prompt.action)
                }
            },
            // 忙碌时取消按钮原为禁用；统一对话框无禁用态，改为忙碌期间忽略点击。
            onCancel = { trustPrompt = null },
            cancelEnabled = !busy,
            confirmEnabled = !busy,
            confirmButtonModifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget),
            cancelButtonModifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget),
            content = {
                // 统一对话框正文不自带滚动与高度上限，长指纹在此限高滚动，避免挤掉按钮。
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
                    Text(stringResource(R.string.ssh_compare_fingerprint))
                    prompt.host.trustedFingerprint?.let { previous ->
                        Text(stringResource(R.string.ssh_previous_fingerprint), style = MaterialTheme.typography.labelLarge)
                        SelectionContainer {
                            Text("${prompt.host.trustedKeyType.orEmpty()}\n$previous", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = InfinityMonospace))
                        }
                    }
                    Text(stringResource(R.string.ssh_presented_fingerprint), style = MaterialTheme.typography.labelLarge)
                    SelectionContainer {
                        Text("${prompt.key.type}\n${prompt.key.fingerprint}", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = InfinityMonospace))
                    }
                    status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                }
            },
        )
    }
}

internal data class SshSettingsVisualState(
    val hosts: List<SshHost> = emptyList(),
    val forwards: Map<String, Int> = emptyMap(),
    val busy: Boolean = false,
    val cleanupWarning: Boolean = false,
    val status: String? = null,
    val statusIsError: Boolean = false,
    val agentManagementEnabled: Boolean = true,
    /** 当前活动的 SSH 连接（终端、转发、智能体与后台命令）。 */
    val activeConnections: List<SshActiveConnection> = emptyList(),
    val terminalIdlePolicy: SshTerminalIdlePolicy = SshTerminalIdlePolicy.OFF,
)

internal data class SshSettingsVisualActions(
    val onBack: () -> Unit = {},
    val onAddHost: () -> Unit = {},
    val onTerminal: (SshHost) -> Unit = {},
    val onVerify: (SshHost) -> Unit = {},
    val onEdit: (SshHost) -> Unit = {},
    val onDelete: (SshHost) -> Unit = {},
    val onCopyPublicKey: (SshHost) -> Unit = {},
    val onToggleForward: (SshHost) -> Unit = {},
    val onAgentManagementChange: (Boolean) -> Unit = {},
    val onTerminalIdlePolicyChange: (SshTerminalIdlePolicy) -> Unit = {},
    val onDisconnect: (SshActiveConnection) -> Unit = {},
    /** 回到已连接的终端，不再核验指纹。 */
    val onOpenConnectedTerminal: (String) -> Unit = {},
)

@Composable
internal fun SshSettingsContent(
    state: SshSettingsVisualState,
    actions: SshSettingsVisualActions,
    modifier: Modifier = Modifier,
    initialActionHostId: String? = null,
    leadingContent: (LazyListScope.() -> Unit)? = null,
) {
    InfinitySettingsPageLayout(
        title = stringResource(R.string.ssh_settings_title),
        onBack = actions.onBack,
        modifier = modifier,
    ) { contentPadding ->
        var actionHostId by remember { mutableStateOf(initialActionHostId) }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            leadingContent?.invoke(this)
            if (sortedActiveConnections(state.activeConnections).isNotEmpty()) {
                item("active") {
                    SshActiveConnectionsSection(
                        connections = state.activeConnections,
                        enabled = !state.busy,
                        onOpenTerminal = actions.onOpenConnectedTerminal,
                        onDisconnect = actions.onDisconnect,
                    )
                }
            }
            item("manage") {
                // 主机列表：每台主机一行，常用的“打开终端”在行尾，其余操作收进主机操作面板。
                InfinitySettingsSection(
                    title = stringResource(R.string.ssh_visual_manage_section),
                    footer = stringResource(R.string.ssh_settings_description),
                ) {
                    if (state.hosts.isEmpty()) {
                        InfinitySettingsRowLayout(
                            title = stringResource(R.string.ssh_no_hosts),
                            icon = Icons.Rounded.Terminal,
                        )
                    }
                    state.hosts.forEach { host ->
                        SshHostRow(
                            host = host,
                            forwardPort = state.forwards[host.alias],
                            enabled = !state.busy,
                            onOpen = { actionHostId = host.id },
                            onTerminal = { actions.onTerminal(host) },
                        )
                    }
                    InfinitySettingsItem(
                        icon = Icons.Rounded.Add,
                        title = stringResource(R.string.ssh_add_host),
                        titleColor = MaterialTheme.colorScheme.primary,
                        enabled = !state.busy,
                        onClick = actions.onAddHost,
                        modifier = Modifier.testTag("ssh_add_host"),
                    )
                }
            }
            item("terminal") {
                SshTerminalIdleSection(state.terminalIdlePolicy, enabled = !state.busy, onChange = actions.onTerminalIdlePolicyChange)
            }
            item("agent") {
                InfinitySettingsSection(
                    title = stringResource(R.string.ssh_agent_section),
                    footer = stringResource(R.string.ssh_agent_manage_description),
                ) {
                    SettingsToggle(
                        title = stringResource(R.string.ssh_agent_manage_title),
                        // 副标题描述当前状态，避免开关已打开时仍显示“默认关闭”。
                        description = stringResource(
                            if (state.agentManagementEnabled) R.string.ssh_agent_manage_summary_on
                            else R.string.ssh_agent_manage_summary_off,
                        ),
                        checked = state.agentManagementEnabled,
                        onCheckedChange = actions.onAgentManagementChange,
                        icon = Icons.Rounded.SmartToy,
                        enabled = !state.busy,
                    )
                }
            }
            if (state.busy || state.cleanupWarning || state.status != null) {
                item("status") {
                    InfinitySettingsSection(title = stringResource(R.string.ssh_visual_status_section)) {
                        if (state.busy) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(InfinitySpacing.Large),
                                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(InfinitySpacing.XLarge))
                                Text(stringResource(R.string.ssh_visual_working), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (state.cleanupWarning) {
                            SshInformationRow(
                                icon = Icons.Rounded.WarningAmber,
                                text = stringResource(R.string.ssh_cleanup_warning),
                                color = MaterialTheme.infinityDomainColors.warning,
                            )
                        }
                        state.status?.let { message ->
                            SshInformationRow(
                                icon = if (state.statusIsError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
                                text = message,
                                color = if (state.statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.infinityDomainColors.success,
                            )
                        }
                    }
                }
            }
            item("notes") {
                InfinitySettingsSection(
                    title = stringResource(R.string.ssh_visual_notes_section),
                    footer = stringResource(R.string.ssh_backup_notice),
                ) {}
            }
        }
        state.hosts.firstOrNull { it.id == actionHostId }?.let { host ->
            SshHostActionsSheet(
                host = host,
                forwardPort = state.forwards[host.alias],
                enabled = !state.busy,
                actions = actions,
                onDismiss = { actionHostId = null },
            )
        }
    }
}

/** 主机行：别名、地址与认证方式、主机指纹状态；行尾直接打开终端。 */
@Composable
private fun SshHostRow(
    host: SshHost,
    forwardPort: Int?,
    enabled: Boolean,
    onOpen: () -> Unit,
    onTerminal: () -> Unit,
) {
    val auth = stringResource(if (host.authType == SshAuthType.KEY) R.string.ssh_auth_key else R.string.ssh_auth_password)
    val address = stringResource(R.string.ssh_visual_host_address, host.username, host.hostname, host.port)
    val trust = stringResource(if (host.trustedFingerprint == null) R.string.settings_batch1_ssh_untrusted_summary else R.string.ssh_trusted)
    val forward = forwardPort?.let { stringResource(R.string.settings_batch1_ssh_forward_running_summary, it) }
    InfinitySettingsItem(
        title = host.alias,
        // 固定两行副标题，各主机行等高：地址单行、过长时省略中段；认证、指纹与转发状态另起一行。
        subtitleContent = {
            Column {
                Text(address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                InfinityMetaLine(
                    segments = listOfNotNull(auth, trust, forward),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
        onClick = onOpen,
        enabled = enabled,
        onClickLabel = stringResource(R.string.ssh_host_actions),
        modifier = Modifier.testTag("ssh_host:${host.alias}"),
        leadingContent = {
            Icon(
                if (host.trustedFingerprint == null) Icons.Rounded.WarningAmber else Icons.Rounded.Dns,
                contentDescription = null,
                tint = if (host.trustedFingerprint == null) MaterialTheme.infinityDomainColors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(InfinitySpacing.RowIconSize),
            )
        },
        trailingContent = {
            IconButton(onClick = onTerminal, enabled = enabled) {
                Icon(Icons.Rounded.Terminal, contentDescription = stringResource(R.string.ssh_open_terminal))
            }
        },
    )
}

/** 主机操作面板：连接信息在顶部，下方为核验、复制公钥、编辑、端口转发与删除。 */
@Composable
private fun SshHostActionsSheet(
    host: SshHost,
    forwardPort: Int?,
    enabled: Boolean,
    actions: SshSettingsVisualActions,
    onDismiss: () -> Unit,
) {
    // 打开新界面或弹窗的操作先收起面板；端口转发开关就地切换。
    fun then(action: (SshHost) -> Unit): () -> Unit = {
        onDismiss()
        action(host)
    }
    InfinityBottomSheet(
        show = true,
        onDismiss = onDismiss,
        title = host.alias,
        height = InfinitySheetHeight.Content,
    ) {
        SshHostActionsContent(
            host = host,
            forwardPort = forwardPort,
            enabled = enabled,
            onTerminal = then(actions.onTerminal),
            onVerify = then(actions.onVerify),
            onCopyPublicKey = then(actions.onCopyPublicKey),
            onEdit = then(actions.onEdit),
            onToggleForward = { actions.onToggleForward(host) },
            onDelete = then(actions.onDelete),
        )
    }
}

/** 主机操作面板的内容；无状态，便于截图预览。 */
@Composable
internal fun SshHostActionsContent(
    host: SshHost,
    forwardPort: Int?,
    enabled: Boolean,
    onTerminal: () -> Unit,
    onVerify: () -> Unit,
    onCopyPublicKey: () -> Unit,
    onEdit: () -> Unit,
    onToggleForward: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(bottom = InfinitySpacing.Medium)) {
            InfinityMetaLine(
                segments = listOf(
                    stringResource(R.string.ssh_visual_host_address, host.username, host.hostname, host.port),
                    stringResource(if (host.authType == SshAuthType.KEY) R.string.ssh_auth_key else R.string.ssh_auth_password),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (host.localForwardPort != null) {
                Text(
                    text = stringResource(R.string.ssh_forward_route, host.localForwardPort, host.forwardHost, host.forwardPort ?: 0),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        InfinitySettingsGroup {
            SshActionItem(Icons.Rounded.Terminal, stringResource(R.string.ssh_open_terminal), enabled, onTerminal,
                subtitle = stringResource(if (host.trustedFingerprint == null) R.string.settings_batch1_ssh_untrusted_summary else R.string.ssh_trusted))
            SshActionItem(Icons.Rounded.Security, stringResource(R.string.ssh_verify), enabled, onVerify)
            val publicKeyReason = UnavailableReasons.sshCopyPublicKey(host.authType)
            SshActionItem(
                Icons.Rounded.ContentCopy, stringResource(R.string.ssh_copy_public_key), enabled && publicKeyReason == null, onCopyPublicKey,
                subtitle = publicKeyReason?.let { stringResource(it) },
            )
            SshActionItem(Icons.Rounded.Edit, stringResource(R.string.ssh_edit_host), enabled, onEdit)
            val forwardReason = UnavailableReasons.sshForward(host.localForwardPort)
            SettingsToggle(
                title = stringResource(R.string.ssh_forward_configure),
                description = when {
                    forwardReason != null -> stringResource(forwardReason)
                    forwardPort == null -> stringResource(R.string.ssh_forward_stopped)
                    else -> stringResource(R.string.settings_batch1_ssh_forward_running_summary, forwardPort)
                },
                checked = forwardPort != null,
                icon = Icons.Rounded.SwapHoriz,
                onCheckedChange = { onToggleForward() },
                enabled = enabled && forwardReason == null,
                // 灰显原因较长，允许换到第二行，避免截断。
                descriptionMaxLines = if (forwardReason != null) 2 else 1,
            )
            SshActionItem(Icons.Rounded.DeleteOutline, stringResource(R.string.ssh_delete), enabled, onDelete, destructive = true)
        }
    }
}

@Composable
private fun SshActionItem(
    icon: ImageVector,
    title: String,
    enabled: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    showChevron: Boolean = false,
    destructive: Boolean = false,
) {
    InfinitySettingsItem(
        icon = icon,
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        enabled = enabled,
        showChevron = showChevron,
        titleColor = if (destructive) MaterialTheme.colorScheme.error else null,
        modifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget),
    )
}

@Composable
private fun SshInformationRow(
    icon: ImageVector,
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(InfinitySpacing.Large),
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(InfinitySpacing.XLarge))
        SelectionContainer {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
}

@Composable
private fun SshStatusTag(text: String, icon: ImageVector, color: Color) {
    Surface(
        color = color.copy(alpha = 0.1f),
        contentColor = color,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = InfinitySpacing.Small, vertical = InfinitySpacing.XSmall),
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(InfinitySpacing.Large))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * 主机编辑表单的字段；密码与口令只在面板存续期间保留在内存。
 * 用户终端改为一直保持连接后，表单不再编辑“退出时保留终端”，已有主机的旧值原样保存。
 */
@Stable
internal class SshHostEditorFields(existing: SshHost?, initialCandidate: SshTailnetCandidate? = null) : SshHostEditorFieldsTarget {
    var alias by mutableStateOf(existing?.alias.orEmpty())
    override var hostname by mutableStateOf(existing?.hostname.orEmpty())

    /** 从 tailnet 选择的设备；主机名改成别的地址后清除。 */
    override var tailnetPeer by mutableStateOf<TailnetPeer?>(null)
    var port by mutableStateOf((existing?.port ?: 22).toString())
    var username by mutableStateOf(existing?.username.orEmpty())
    var authType by mutableStateOf(existing?.authType ?: SshAuthType.KEY)
    private val keepTerminal = existing?.keepTerminal ?: false
    var forwarding by mutableStateOf(existing?.localForwardPort != null)
    var localPort by mutableStateOf(existing?.localForwardPort?.toString().orEmpty())
    var forwardHost by mutableStateOf(existing?.forwardHost ?: "127.0.0.1")
    var forwardPort by mutableStateOf(existing?.forwardPort?.toString().orEmpty())
    var password by mutableStateOf("")
    var passphrase by mutableStateOf("")

    init {
        initialCandidate?.let(::applyTailnetCandidate)
    }

    fun editHostname(value: String) {
        hostname = value
        if (tailnetPeer?.ips?.contains(value.trim()) != true) tailnetPeer = null
    }

    fun draft() = SshHostDraft(
        alias, hostname, port, username, authType, keepTerminal, forwarding,
        localPort, forwardHost, forwardPort, aliasSeed = tailnetPeer?.name,
    )
}

/** 私钥的载入来源，决定认证分区的说明文案。 */
internal enum class SshKeySource { FILE, CLIPBOARD }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SshHostEditorSheet(
    existing: SshHost?,
    hosts: List<SshHost>,
    initialPeer: TailnetPeer?,
    onDismiss: () -> Unit,
    onSave: suspend (SshHost, SshCredential?, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val takenAliases = remember(hosts) { hosts.mapTo(mutableSetOf()) { it.alias } }
    val fields = remember {
        SshHostEditorFields(existing, initialPeer?.let { sshTailnetCandidates(listOf(it), hosts).firstOrNull() })
    }
    val tailnetManager = (context.applicationContext as InfinityApplication).tailnetManager
    val tailnet by tailnetManager.state.collectAsState()
    // 只有新建主机时才提供从 tailnet 选择；编辑已有主机改地址仍手动输入。
    val tailnetAvailability = if (existing == null) sshTailnetAvailability(tailnetManager.available, tailnet.phase)
    else SshTailnetAvailability.HIDDEN
    var showTailnetPicker by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    var privateKey by remember { mutableStateOf<ByteArray?>(null) }
    var keySource by remember { mutableStateOf<SshKeySource?>(null) }
    var busy by remember { mutableStateOf(false) }
    var errorCode by remember { mutableStateOf<String?>(null) }

    fun clearSecrets() {
        fields.password = ""
        fields.passphrase = ""
        privateKey?.fill(0)
        privateKey = null
        keySource = null
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) clearSecrets() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            clearSecrets()
        }
    }

    // 错误固定显示在操作按钮上方；指向顶部字段时同时滚回顶部，让出错字段可见。
    fun showError(code: String) {
        errorCode = code
        if (code in SshErrorMessages.CONNECTION_FIELD_CODES) scope.launch { scrollState.animateScrollTo(0) }
    }

    /** 读取并校验私钥信封；成功后替换内存中的旧私钥，失败时清零读入的字节。 */
    fun loadKey(source: SshKeySource, read: () -> ByteArray, afterAccepted: () -> Unit = {}) {
        busy = true
        scope.launch {
            var loaded: ByteArray? = null
            try {
                loaded = withContext(Dispatchers.IO) { read().also(SshPolicy::validatePrivateKey) }
                privateKey?.fill(0)
                privateKey = loaded
                keySource = source
                loaded = null
                errorCode = null
                afterAccepted()
            } catch (error: Exception) {
                showError(safeSshError(error))
            } finally {
                loaded?.fill(0)
                busy = false
            }
        }
    }

    val importKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadKey(SshKeySource.FILE, {
            context.contentResolver.openInputStream(uri)?.use(SshPrivateKeyInput::read)
                ?: throw SshInputException("SSH_KEY_READ_FAILED")
        })
    }

    fun pasteKey() {
        if (busy) return
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)
        if (text.isNullOrBlank()) {
            showError("SSH_KEY_CLIPBOARD_EMPTY")
            return
        }
        // 私钥校验通过后清空剪贴板，避免密钥留在系统剪贴板历史中。
        loadKey(SshKeySource.CLIPBOARD, { SshPrivateKeyInput.read(text.toString().trim().byteInputStream(Charsets.UTF_8)) }) {
            clipboard.clearPrimaryClip()
        }
    }

    fun save(generate: Boolean) {
        if (busy) return
        val host = try {
            fields.draft().toHost(existing, privateKey != null, fields.password.isNotEmpty(), generate, takenAliases)
        } catch (error: SshInputException) {
            showError(safeSshError(error))
            return
        }
        // 主机 ID 留空时回填自动生成的值，保存失败后用户也能看到将使用的 ID。
        fields.alias = host.alias
        busy = true
        errorCode = null
        scope.launch {
            var credential: SshCredential? = null
            try {
                credential = when {
                    generate -> null
                    fields.authType == SshAuthType.PASSWORD && fields.password.isNotEmpty() ->
                        SshCredential(password = fields.password.toByteArray(Charsets.UTF_8))
                    fields.authType == SshAuthType.KEY && privateKey != null ->
                        SshCredential(privateKey = privateKey!!.copyOf(), passphrase = fields.passphrase.toByteArray(Charsets.UTF_8))
                    else -> null
                }
                onSave(host, credential, generate)
                clearSecrets()
            } catch (error: Exception) {
                showError(safeSshError(error))
            } finally {
                credential?.close()
                busy = false
            }
        }
    }

    InfinityBottomSheet(
        show = true,
        onDismiss = { if (!busy) onDismiss() },
        dismissEnabled = !busy,
        dragGesturesEnabled = false,
        title = stringResource(if (existing == null) R.string.ssh_add_host else R.string.ssh_edit_host),
        height = InfinitySheetHeight.Full,
        modifier = Modifier.imePadding(),
    ) {
        SshHostEditorForm(
            fields = fields,
            existing = existing,
            aliasSuggestion = if (existing == null) SshHostDraft.suggestAlias(fields.tailnetPeer?.name ?: fields.hostname, takenAliases) else null,
            busy = busy,
            keySource = keySource,
            errorCode = errorCode,
            scrollState = scrollState,
            onAuthTypeChange = { fields.authType = it; clearSecrets() },
            onImportKey = { importKey.launch(arrayOf("*/*")) },
            onPasteKey = ::pasteKey,
            onGenerate = { save(true) },
            onCancel = onDismiss,
            onSave = { save(false) },
            tailnet = tailnetAvailability,
            onPickTailnet = { showTailnetPicker = true },
        )
    }
    if (showTailnetPicker) {
        SshTailnetPickerSheet(
            candidates = sshTailnetCandidates(tailnet.peers, hosts),
            tailnetName = tailnet.summary?.tailnetName,
            onPick = { candidate ->
                fields.applyTailnetCandidate(candidate)
                if (errorCode == "SSH_HOST_INVALID") errorCode = null
                showTailnetPicker = false
            },
            onDismiss = { showTailnetPicker = false },
        )
    }
}

/** 编辑面板内容：滚动的表单、固定在按钮上方的错误区与操作按钮。不持有秘密，便于截图测试。 */
@Composable
internal fun SshHostEditorForm(
    fields: SshHostEditorFields,
    existing: SshHost?,
    aliasSuggestion: String?,
    busy: Boolean,
    keySource: SshKeySource?,
    errorCode: String?,
    scrollState: ScrollState,
    onAuthTypeChange: (SshAuthType) -> Unit,
    onImportKey: () -> Unit,
    onPasteKey: () -> Unit,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    tailnet: SshTailnetAvailability = SshTailnetAvailability.HIDDEN,
    onPickTailnet: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().fillMaxHeight()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState),
        ) {
            InfinitySettingsSection(title = stringResource(R.string.ssh_visual_connection_section)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(InfinitySpacing.Large),
                    verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                ) {
                    // 规则说明紧贴主机 ID 字段；该字段出错时整段说明随之标红。
                    SshEditorField(
                        fields.alias, { fields.alias = SshHostDraft.normalizeAliasInput(it) }, R.string.ssh_alias,
                        enabled = !busy && existing == null,
                        supportingText = stringResource(R.string.ssh_alias_help),
                        placeholder = aliasSuggestion,
                        isError = errorCode in SshErrorMessages.ALIAS_CODES,
                        testTag = "ssh_editor_alias",
                    )
                    SshEditorField(
                        fields.hostname, fields::editHostname, R.string.ssh_hostname, enabled = !busy, isError = errorCode == "SSH_HOST_INVALID",
                        supportingText = if (tailnet == SshTailnetAvailability.DISCONNECTED) stringResource(R.string.ssh_tailnet_disconnected) else null,
                        trailing = if (tailnet == SshTailnetAvailability.HIDDEN) null else {
                            { SshTailnetPickButton(enabled = !busy && tailnet == SshTailnetAvailability.CONNECTED, onClick = onPickTailnet) }
                        },
                        testTag = "ssh_editor_hostname",
                    )
                    SshEditorField(fields.port, { fields.port = it }, R.string.ssh_port, keyboardType = KeyboardType.Number, enabled = !busy, isError = errorCode == "SSH_PORT_INVALID")
                    SshEditorField(fields.username, { fields.username = it }, R.string.ssh_username, enabled = !busy, isError = errorCode == "SSH_USERNAME_INVALID")
                }
            }
            InfinitySettingsSection(
                title = stringResource(R.string.ssh_authentication),
                footer = listOfNotNull(
                    if (fields.authType == SshAuthType.KEY) stringResource(
                        when (keySource) {
                            null -> R.string.ssh_key_import_help
                            SshKeySource.FILE -> R.string.ssh_key_imported
                            SshKeySource.CLIPBOARD -> R.string.ssh_key_pasted
                        },
                    ) else null,
                    if (fields.authType == SshAuthType.KEY) stringResource(R.string.ssh_generate_help) else null,
                    if (existing != null) stringResource(R.string.ssh_keep_credential_hint) else null,
                ).joinToString("\n"),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(InfinitySpacing.Large),
                    verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                ) {
                    InfinitySegmentedControl(
                        segments = SshAuthType.entries.map { type ->
                            InfinitySegment(type, stringResource(if (type == SshAuthType.KEY) R.string.ssh_auth_key else R.string.ssh_auth_password))
                        },
                        selected = fields.authType,
                        onSelect = onAuthTypeChange,
                        enabled = !busy,
                        fillWidth = true,
                    )
                    if (fields.authType == SshAuthType.PASSWORD) {
                        SshEditorField(fields.password, { fields.password = it }, R.string.ssh_password, secret = true, enabled = !busy, isError = errorCode == "SSH_PASSWORD_REQUIRED")
                    } else {
                        // 导入文件是主要来源；粘贴与生成收进相连的箭头段菜单，不再各占一整行。
                        InfinitySplitPillButton(
                            text = stringResource(R.string.ssh_import_key),
                            onClick = onImportKey,
                            moreContentDescription = stringResource(R.string.ssh_key_more_cd),
                            enabled = !busy,
                            icon = Icons.Rounded.UploadFile,
                            mainModifier = Modifier.testTag("ssh_editor_import_key"),
                            moreModifier = Modifier.testTag("ssh_editor_key_more"),
                            modifier = Modifier.fillMaxWidth(),
                        ) { dismiss ->
                            SshKeySourceMenuItems(
                                onPasteKey = { dismiss(); onPasteKey() },
                                onGenerate = { dismiss(); onGenerate() },
                            )
                        }
                        if (keySource != null) {
                            SshEditorField(fields.passphrase, { fields.passphrase = it }, R.string.ssh_passphrase, secret = true, enabled = !busy)
                        }
                    }
                }
            }
            InfinitySettingsSection(
                title = stringResource(R.string.ssh_forward_configure),
                footer = stringResource(R.string.ssh_forward_loopback_only),
            ) {
                SettingsToggle(
                    title = stringResource(R.string.settings_batch1_network_forwarding),
                    description = stringResource(R.string.settings_batch1_ssh_forward_summary),
                    checked = fields.forwarding,
                    onCheckedChange = { fields.forwarding = it },
                    enabled = !busy,
                )
                if (fields.forwarding) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(InfinitySpacing.Large),
                        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                    ) {
                        SshEditorField(fields.localPort, { fields.localPort = it }, R.string.ssh_forward_local_port, keyboardType = KeyboardType.Number, enabled = !busy)
                        SshEditorField(fields.forwardHost, { fields.forwardHost = it }, R.string.ssh_forward_remote_host, enabled = !busy)
                        SshEditorField(fields.forwardPort, { fields.forwardPort = it }, R.string.ssh_forward_remote_port, keyboardType = KeyboardType.Number, enabled = !busy)
                    }
                }
            }
        }
        // 错误区不随表单滚动：无论点哪个按钮触发，错误都出现在可见位置。
        errorCode?.let { code ->
            Box(Modifier.fillMaxWidth().testTag("ssh_editor_error").semantics { liveRegion = LiveRegionMode.Polite }) {
                SshInformationRow(icon = Icons.Rounded.ErrorOutline, text = sshErrorText(code), color = MaterialTheme.colorScheme.error)
            }
        }
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.padding(top = InfinitySpacing.Small).size(InfinitySpacing.XLarge))
        }
        InfinityPillRow(
            modifier = Modifier.fillMaxWidth().padding(start = InfinitySpacing.Large, top = InfinitySpacing.Medium, end = InfinitySpacing.Large),
            spacing = InfinitySpacing.Small,
        ) {
            InfinityPillButton(
                text = stringResource(R.string.ssh_cancel),
                onClick = onCancel,
                enabled = !busy,
            )
            InfinityPillButton(
                text = stringResource(R.string.ssh_save),
                onClick = onSave,
                tone = InfinityPillTone.PRIMARY,
                enabled = !busy,
            )
        }
    }
}

/** 私钥来源分体按钮的菜单项；截图预览以静态浮层渲染同一内容。 */
@Composable
internal fun ColumnScope.SshKeySourceMenuItems(onPasteKey: () -> Unit, onGenerate: () -> Unit) {
    InfinityMenuItem(
        text = { Text(stringResource(R.string.ssh_paste_key), maxLines = 2) },
        leadingIcon = { Icon(Icons.Rounded.ContentPaste, contentDescription = null) },
        onClick = onPasteKey,
        modifier = Modifier.testTag("ssh_editor_paste_key"),
    )
    InfinityMenuItem(
        text = { Text(stringResource(R.string.ssh_generate_save_copy), maxLines = 2) },
        leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
        onClick = onGenerate,
        modifier = Modifier.testTag("ssh_editor_generate_key"),
    )
}

@Composable
private fun SshEditorField(
    value: String,
    onValueChange: (String) -> Unit,
    label: Int,
    enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    supportingText: String? = null,
    placeholder: String? = null,
    isError: Boolean = false,
    testTag: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val field = @Composable {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(label)) },
            placeholder = placeholder?.let { { Text(it) } },
            trailingIcon = trailing,
            supportingText = supportingText?.let { { Text(it) } },
            isError = isError,
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
            textStyle = MaterialTheme.typography.bodyLarge.forInput(),
            shape = MaterialTheme.shapes.medium,
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            // 主机 ID、主机名与用户名都区分大小写，不让输入法自动大写首字母。
            keyboardOptions = if (secret) SecretKeyboardOptions else KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                keyboardType = keyboardType,
                autoCorrectEnabled = false,
            ),
        )
    }
    if (secret) SecretTextInput(field) else field()
}

/** 主机名输入框行尾的“Tailscale”：打开 tailnet 设备选择；未连接时灰显，原因写在输入框下方。 */
@Composable
private fun SshTailnetPickButton(enabled: Boolean, onClick: () -> Unit) {
    InfinityPillButton(
        text = stringResource(R.string.ssh_tailnet_pick_button),
        onClick = onClick,
        enabled = enabled,
        icon = Icons.Rounded.Hub,
        compact = true,
        modifier = Modifier.padding(end = InfinitySpacing.XSmall).testTag("ssh_editor_tailnet"),
    )
}

/** “终端会话”分区：全局空闲自动断开，只作用于用户自己的终端。 */
@Composable
internal fun SshTerminalIdleSection(policy: SshTerminalIdlePolicy, enabled: Boolean, onChange: (SshTerminalIdlePolicy) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    InfinitySettingsSection(
        title = stringResource(R.string.ssh_visual_terminal_section),
        footer = stringResource(R.string.ssh_idle_footer),
    ) {
        Box {
            InfinitySettingsItem(
                icon = Icons.Rounded.TimerOff,
                title = stringResource(R.string.ssh_idle_title),
                subtitle = stringResource(R.string.ssh_idle_summary),
                onClick = { menuOpen = true },
                enabled = enabled,
                modifier = Modifier.testTag("ssh_terminal_idle"),
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(policy.label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
            InfinityDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                SshTerminalIdlePolicy.entries.forEach { option ->
                    InfinityMenuItem(
                        text = { Text(stringResource(option.label)) },
                        onClick = { onChange(option); menuOpen = false },
                        selected = option == policy,
                    )
                }
            }
        }
    }
}

private val SshTerminalIdlePolicy.label: Int
    get() = when (this) {
        SshTerminalIdlePolicy.OFF -> R.string.ssh_idle_off
        SshTerminalIdlePolicy.MINUTES_30 -> R.string.ssh_idle_30m
        SshTerminalIdlePolicy.HOURS_2 -> R.string.ssh_idle_2h
    }

private fun copySshPublicKey(context: Context, publicKey: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.ssh_public_key_label), publicKey))
}
