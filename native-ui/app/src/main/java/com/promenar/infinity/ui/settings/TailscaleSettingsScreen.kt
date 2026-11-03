package com.promenar.infinity.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.rounded.Add
import com.promenar.infinity.data.ssh.SshHost
import com.promenar.infinity.data.tailnet.TailnetPingResult
import com.promenar.infinity.ui.common.InfinityIdentifierText
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.ssh.SshTailnetCandidate
import com.promenar.infinity.ui.ssh.sshTailnetCandidates
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.InfinityValueRow
import com.promenar.infinity.ui.theme.infinityDomainColors
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.R
import com.promenar.infinity.data.tailnet.TailnetFailure
import com.promenar.infinity.data.tailnet.TailnetPeer
import com.promenar.infinity.data.tailnet.TailnetPhase
import com.promenar.infinity.data.tailnet.TailnetState
import com.promenar.infinity.data.tailnet.TailnetStatusSummary
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.UnavailableReasons
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.SecretField
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forInput

internal data class TailscaleSettingsActions(
    val onEnabledChange: (Boolean) -> Unit = {},
    val onLogin: () -> Unit = {},
    val onLogout: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onSaveAuthKey: (CharArray) -> Unit = { it.fill('\u0000') },
    val onRevealAuthKey: suspend () -> CharArray? = { null },
    val onClearAuthKey: () -> Unit = {},
    val onSaveControlUrl: (String) -> Unit = {},
    val onControlUrlEdited: () -> Unit = {},
    val onEphemeralChange: (Boolean) -> Unit = {},
    val onAuthKeyEdited: () -> Unit = {},
    /** 打开设备面板时开始检测该设备的连接路径。 */
    val onStartPing: (TailnetPeer) -> Unit = {},
    val onStopPing: () -> Unit = {},
    val onDismissPing: () -> Unit = {},
)

@Composable
fun TailscaleSettingsScreen(
    onBack: () -> Unit,
    /** 会话“远程”面板传入：设备行显示“SSH”，点按后在 SSH 分段打开预填的新建主机面板。 */
    onAddSshHost: ((TailnetPeer) -> Unit)? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val viewModel: TailscaleSettingsViewModel =
        viewModel(factory = TailscaleSettingsViewModel.factory(context.applicationContext as android.app.Application))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val noticeHost = remember { SnackbarHostState() }
    val sshHosts by remember(context, onAddSshHost != null) {
        if (onAddSshHost == null) kotlinx.coroutines.flow.flowOf(emptyList())
        else (context.applicationContext as com.promenar.infinity.InfinityApplication).sshHostStore.observeHosts()
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { event ->
            when (event) {
                is TailscaleSettingsEvent.OpenLogin -> try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(event.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    noticeHost.showSnackbar(resources.getString(R.string.tailscale_error_browser))
                }
            }
        }
    }
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        viewModel.consumeNotice()
        noticeHost.currentSnackbarData?.dismiss()
        noticeHost.showSnackbar(resources.getString(tailscaleNoticeText(notice)))
    }

    InfinitySettingsPageLayout(title = stringResource(R.string.tailscale_title), onBack = onBack) { contentPadding ->
        Box(Modifier.fillMaxSize()) {
            TailscaleSettingsContent(
                state = state,
                actions = TailscaleSettingsActions(
                    onEnabledChange = viewModel::setEnabled,
                    onLogin = viewModel::login,
                    onLogout = viewModel::logout,
                    onRetry = viewModel::retry,
                    onRefresh = viewModel::refresh,
                    onSaveAuthKey = viewModel::saveAuthKey,
                    onRevealAuthKey = viewModel::revealAuthKey,
                    onClearAuthKey = viewModel::clearAuthKey,
                    onSaveControlUrl = viewModel::saveControlUrl,
                    onControlUrlEdited = viewModel::clearControlUrlError,
                    onEphemeralChange = viewModel::setEphemeral,
                    onAuthKeyEdited = viewModel::clearAuthKeyError,
                    onStartPing = viewModel::startPing,
                    onStopPing = viewModel::stopPing,
                    onDismissPing = viewModel::dismissPing,
                ),
                contentPadding = contentPadding,
                sshHosts = sshHosts,
                onAddSshHost = onAddSshHost,
            )
            SnackbarHost(
                hostState = noticeHost,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(horizontal = InfinitySpacing.Large)
                    .padding(bottom = contentPadding.calculateBottomPadding()),
            )
        }
    }
}

private fun tailscaleNoticeText(notice: TailscaleNotice): Int = when (notice) {
    TailscaleNotice.AUTH_KEY_SAVED -> R.string.tailscale_notice_auth_key_saved
    TailscaleNotice.AUTH_KEY_CLEARED -> R.string.tailscale_notice_auth_key_cleared
    TailscaleNotice.CONTROL_URL_SAVED -> R.string.tailscale_notice_control_saved
    TailscaleNotice.LOGIN_UNAVAILABLE -> R.string.tailscale_notice_login_unavailable
    TailscaleNotice.LOGGED_OUT -> R.string.tailscale_notice_logged_out
    TailscaleNotice.LOGOUT_FAILED -> R.string.tailscale_notice_logout_failed
    TailscaleNotice.OPERATION_FAILED -> R.string.tailscale_notice_failed
}

/** 阶段文案；入口副标题与设置页共用。 */
internal fun tailscalePhaseText(state: TailnetState): Int = when (state.phase) {
    TailnetPhase.UNAVAILABLE -> R.string.tailscale_phase_unavailable
    TailnetPhase.DISABLED -> R.string.tailscale_phase_disabled
    TailnetPhase.STOPPED -> R.string.tailscale_phase_stopped
    TailnetPhase.STARTING -> R.string.tailscale_phase_starting
    TailnetPhase.NEEDS_LOGIN -> if (state.summary?.backendState == TailnetStatusSummary.STATE_NEEDS_MACHINE_AUTH) {
        R.string.tailscale_phase_needs_approval
    } else {
        R.string.tailscale_phase_needs_login
    }
    TailnetPhase.RUNNING -> R.string.tailscale_phase_running
    TailnetPhase.FAILED -> if (state.failure == TailnetFailure.EXITED) R.string.tailscale_phase_exited else R.string.tailscale_phase_failed
}

/** 设置页里可打开的编辑面板。 */
internal enum class TailscaleEditor { AUTH_KEY, CONTROL_URL }

/** 无应用依赖的设置页内容，供截图测试与真实页面共用。 */
@Composable
internal fun TailscaleSettingsContent(
    state: TailscaleSettingsUiState,
    actions: TailscaleSettingsActions = TailscaleSettingsActions(),
    contentPadding: PaddingValues = PaddingValues(InfinitySpacing.ScreenHorizontal),
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    initialEditor: TailscaleEditor? = null,
    sshHosts: List<SshHost> = emptyList(),
    onAddSshHost: ((TailnetPeer) -> Unit)? = null,
) {
    val settings = state.settings
    val tailnet = state.tailnet
    val summary = tailnet.summary
    val enabled = state.available && settings.enabled
    var editor by remember { mutableStateOf(initialEditor) }
    // 设备面板按名称跟随节点列表刷新；设备从列表消失时面板随之关闭。
    var openPeer by remember { mutableStateOf<String?>(null) }
    // 保存成功后由通知收起对应面板；失败时面板保持打开并在面板内提示。
    LaunchedEffect(state.notice) {
        val closes = when (state.notice) {
            TailscaleNotice.AUTH_KEY_SAVED, TailscaleNotice.AUTH_KEY_CLEARED -> TailscaleEditor.AUTH_KEY
            TailscaleNotice.CONTROL_URL_SAVED -> TailscaleEditor.CONTROL_URL
            else -> null
        }
        if (closes != null && editor == closes) editor = null
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("tailscale_settings_screen"),
        state = listState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
    ) {
        item {
            InfinitySettingsSection(
                title = stringResource(R.string.tailscale_section_node),
                footer = stringResource(if (state.available) R.string.tailscale_node_footer else R.string.tailscale_unavailable_desc),
            ) {
                SettingsToggle(
                    title = stringResource(R.string.tailscale_enable),
                    description = stringResource(R.string.tailscale_enable_desc),
                    checked = enabled,
                    onCheckedChange = actions.onEnabledChange,
                    icon = Icons.Rounded.Hub,
                    enabled = state.available && !state.busy,
                    modifier = Modifier.testTag("tailscale_enable"),
                )
            }
        }
        if (enabled) {
            item { TailscaleStatusSection(state, actions) }
            val health = summary?.health.orEmpty()
            if (health.isNotEmpty()) {
                item {
                    InfinitySettingsSection(title = stringResource(R.string.tailscale_health_title)) {
                        health.take(5).forEach { message -> TailscaleHealthRow(message) }
                    }
                }
            }
            if (tailnet.phase == TailnetPhase.RUNNING) {
                item {
                    TailscalePeersSection(tailnet.peers, sshHosts, onAddSshHost, state.lastPing) { peer ->
                        openPeer = peer.name
                        actions.onStartPing(peer)
                    }
                }
            }
        }
        item {
            InfinitySettingsSection(title = stringResource(R.string.tailscale_section_login)) {
                InfinityValueRow(
                    title = stringResource(R.string.tailscale_auth_key),
                    value = stringResource(if (settings.hasAuthKey) R.string.tailscale_value_saved else R.string.tailscale_value_not_set),
                    onClick = { editor = TailscaleEditor.AUTH_KEY },
                    icon = Icons.Rounded.Key,
                    enabled = state.available,
                    modifier = Modifier.testTag("tailscale_auth_key"),
                )
                InfinityValueRow(
                    title = stringResource(R.string.tailscale_control_url),
                    value = controlServerLabel(settings.controlUrl),
                    onClick = { editor = TailscaleEditor.CONTROL_URL },
                    icon = Icons.Rounded.Public,
                    enabled = state.available,
                    modifier = Modifier.testTag("tailscale_control_url"),
                )
                SettingsToggle(
                    title = stringResource(R.string.tailscale_ephemeral),
                    description = stringResource(R.string.tailscale_ephemeral_desc),
                    checked = settings.ephemeral,
                    onCheckedChange = actions.onEphemeralChange,
                    icon = Icons.Rounded.Timer,
                    enabled = state.available && !state.busy,
                    modifier = Modifier.testTag("tailscale_ephemeral"),
                )
            }
        }

    }
    val dismiss = { editor = null }
    when (editor) {
        TailscaleEditor.AUTH_KEY -> TailscaleEditorSheet(stringResource(R.string.tailscale_auth_key), dismiss) {
            TailscaleAuthKeyEditor(state, actions, onCancel = dismiss)
        }
        TailscaleEditor.CONTROL_URL -> TailscaleEditorSheet(stringResource(R.string.tailscale_control_url), dismiss) {
            TailscaleControlUrlEditor(state, actions, onCancel = dismiss)
        }
        null -> Unit
    }
    val sheetPeer = openPeer?.let { name -> tailnet.peers.firstOrNull { it.name == name } }
        ?.takeIf { tailnet.phase == TailnetPhase.RUNNING }
    if (openPeer != null && sheetPeer == null) {
        LaunchedEffect(openPeer) { openPeer = null; actions.onDismissPing() }
    }
    if (sheetPeer != null) {
        val closeSheet = { openPeer = null; actions.onDismissPing() }
        val candidate = remember(tailnet.peers, sshHosts, onAddSshHost != null) {
            if (onAddSshHost == null) null else sshTailnetCandidates(tailnet.peers, sshHosts).firstOrNull { it.peer == sheetPeer }
        }
        TailscalePeerSheet(
            peer = sheetPeer,
            run = state.ping?.takeIf { it.peerName == sheetPeer.name },
            lastPing = state.lastPing[sheetPeer.name],
            onPing = { actions.onStartPing(sheetPeer) },
            onStop = actions.onStopPing,
            onDismiss = closeSheet,
            sshAction = if (candidate == null || onAddSshHost == null) null else {
                {
                    InfinitySettingsRowLayout(
                        title = stringResource(R.string.tailscale_peer_ssh_host),
                        icon = Icons.Rounded.Dns,
                        trailingContent = {
                            TailscalePeerSshAction(sheetPeer, candidate.existingAlias) { closeSheet(); onAddSshHost(sheetPeer) }
                        },
                    )
                }
            },
        )
    }
}

@Composable
private fun TailscaleStatusSection(state: TailscaleSettingsUiState, actions: TailscaleSettingsActions) {
    val tailnet = state.tailnet
    val summary = tailnet.summary?.takeIf { tailnet.phase == TailnetPhase.RUNNING }
    InfinitySettingsSection(title = stringResource(R.string.tailscale_section_status)) {
        InfinitySettingsRowLayout(
            title = stringResource(tailscalePhaseText(tailnet)),
            subtitle = summary?.tailnetName?.takeIf(String::isNotBlank),
            leadingContent = { TailscaleStatusDot(tailscaleStatusTone(tailnet), Modifier.size(StatusDotSize)) },
            modifier = Modifier.testTag("tailscale_status"),
        ) {
            IconButton(onClick = actions.onRefresh, enabled = !state.busy, modifier = Modifier.testTag("tailscale_refresh")) {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.tailscale_refresh))
            }
        }
        summary?.selfName?.let { name ->
            InfinitySettingsRowLayout(
                title = name,
                subtitleSegments = preferredTailnetAddress(summary.selfIps)?.let { listOf(stringResource(R.string.tailscale_self_label), it) },
                icon = Icons.Rounded.PhoneAndroid,
                modifier = Modifier.testTag("tailscale_self"),
            )
        }
        when (tailnet.phase) {
            TailnetPhase.NEEDS_LOGIN, TailnetPhase.STARTING -> InfinitySettingsItem(
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                title = stringResource(R.string.tailscale_login),
                subtitle = stringResource(R.string.tailscale_login_desc),
                onClick = actions.onLogin,
                enabled = !state.busy,
                modifier = Modifier.testTag("tailscale_login"),
            )
            TailnetPhase.FAILED, TailnetPhase.STOPPED -> InfinitySettingsItem(
                icon = Icons.Rounded.Refresh,
                title = stringResource(R.string.common_retry),
                onClick = actions.onRetry,
                enabled = !state.busy,
                modifier = Modifier.testTag("tailscale_retry"),
            )
            else -> Unit
        }
        val logoutReason = UnavailableReasons.tailscaleLogout(tailnet.phase)
        InfinitySettingsItem(
            icon = Icons.Rounded.CloudOff,
            title = stringResource(R.string.tailscale_logout),
            subtitle = stringResource(logoutReason ?: R.string.tailscale_logout_desc),
            onClick = actions.onLogout,
            enabled = !state.busy && logoutReason == null,
            titleColor = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("tailscale_logout"),
        )
    }
}

@Composable
private fun TailscalePeersSection(
    peers: List<TailnetPeer>,
    sshHosts: List<SshHost>,
    onAddSshHost: ((TailnetPeer) -> Unit)?,
    lastPing: Map<String, TailnetPingResult> = emptyMap(),
    onOpenPeer: (TailnetPeer) -> Unit = {},
) {
    val online = peers.count { it.online }
    // 可添加为 SSH 主机的设备（有 tailnet 地址）及已指向它的主机 ID。
    val candidates = remember(peers, sshHosts, onAddSshHost != null) {
        if (onAddSshHost == null) emptyMap() else sshTailnetCandidates(peers, sshHosts).associateBy { it.peer }
    }
    InfinitySettingsSection(
        title = stringResource(R.string.tailscale_section_peers),
        footer = if (peers.isEmpty()) {
            stringResource(R.string.tailscale_peers_empty)
        } else {
            listOfNotNull(
                pluralStringResource(R.plurals.tailscale_peers_summary, peers.size, online, peers.size),
                if (candidates.isNotEmpty()) stringResource(R.string.ssh_tailnet_add_host_hint) else null,
            ).joinToString(" ")
        },
    ) {
        peers.sortedForDisplay().forEach { peer ->
            TailscalePeerRow(peer, candidates[peer], onAddSshHost, lastPing[peer.name]) { onOpenPeer(peer) }
        }
    }
}

@Composable
private fun TailscalePeerRow(
    peer: TailnetPeer,
    candidate: SshTailnetCandidate? = null,
    onAddSshHost: ((TailnetPeer) -> Unit)? = null,
    lastPing: TailnetPingResult? = null,
    onOpen: () -> Unit = {},
) {
    val onlineText = stringResource(if (peer.online) R.string.tailscale_peer_online else R.string.tailscale_peer_offline)
    // 在线状态由图标角标表达，离线时在副标题写明；读屏通过 stateDescription 读出在线状态。
    val subtitle = listOfNotNull(
        (peer.ips.firstOrNull() ?: peer.dnsName)?.takeIf { it.isNotBlank() },
        tailscaleOsLabel(peer.os),
        if (!peer.online) onlineText else null,
    ).joinToString(" · ").ifEmpty { null }  // wrap-check: allow 单行省略
    val path = peer.pathView(lastPing)
    val openLabel = stringResource(R.string.tailscale_peer_open_cd, peer.name)
    InfinitySettingsRowLayout(
        title = peer.name,
        subtitleContent = { TailscalePeerSubtitle(subtitle, path) },
        titleColor = if (peer.online) null else MaterialTheme.colorScheme.onSurfaceVariant,
        leadingContent = { TailscalePeerIcon(peer) },
        // 在线设备点按打开路径检测面板；离线设备没有可测的路径。
        modifier = Modifier
            .then(if (peer.online) Modifier.clickable(role = Role.Button, onClickLabel = openLabel, onClick = onOpen) else Modifier)
            .semantics(mergeDescendants = candidate == null) { stateDescription = onlineText }
            .testTag("tailscale_peer:${peer.name}"),
        trailingContent = if (candidate == null || onAddSshHost == null) null else {
            { TailscalePeerSshAction(peer, candidate.existingAlias) { onAddSshHost(peer) } }
        },
    )
}

/** 设备行尾：已添加为 SSH 主机时显示主机 ID，否则显示“SSH”添加按钮。 */
@Composable
private fun TailscalePeerSshAction(peer: TailnetPeer, existingAlias: String?, onAdd: () -> Unit) {
    if (existingAlias != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall)) {
            Icon(Icons.Rounded.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            InfinityIdentifierText(existingAlias, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        return
    }
    val description = stringResource(R.string.ssh_tailnet_add_host_cd, peer.name)
    InfinityPillButton(
        text = stringResource(R.string.ssh_tailnet_add_host),
        onClick = onAdd,
        icon = Icons.Rounded.Add,
        compact = true,
        modifier = Modifier.testTag("tailscale_peer_add_ssh:${peer.name}").semantics { contentDescription = description },
    )
}

/** 设备类别图标，右下角小点表示是否在线；提供商表单的设备面板复用。 */
@Composable
internal fun TailscalePeerIcon(peer: TailnetPeer) {
    val icon = when (tailscalePeerKind(peer.os)) {
        TailscalePeerKind.DESKTOP -> Icons.Rounded.DesktopWindows
        TailscalePeerKind.LAPTOP -> Icons.Rounded.LaptopMac
        TailscalePeerKind.PHONE -> Icons.Rounded.PhoneAndroid
        TailscalePeerKind.SERVER -> Icons.Rounded.Dns
        TailscalePeerKind.OTHER -> Icons.Rounded.Devices
    }
    Box(Modifier.size(InfinitySpacing.RowIconSize)) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(InfinitySpacing.RowIconSize),
        )
        TailscaleStatusDot(
            tone = if (peer.online) TailscaleStatusTone.GOOD else TailscaleStatusTone.IDLE,
            modifier = Modifier.align(Alignment.BottomEnd).size(PeerDotSize)
                .border(PeerDotBorder, MaterialTheme.colorScheme.surfaceContainer, CircleShape),
        )
    }
}

@Composable
private fun TailscaleHealthRow(message: String) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Medium),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Rounded.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.infinityDomainColors.warning,
            modifier = Modifier.size(InfinitySpacing.RowIconSize),
        )
        Spacer(Modifier.width(InfinitySpacing.RowIconGap))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

internal enum class TailscaleStatusTone { GOOD, PENDING, ERROR, IDLE }

internal fun tailscaleStatusTone(state: TailnetState): TailscaleStatusTone = when (state.phase) {
    TailnetPhase.RUNNING -> TailscaleStatusTone.GOOD
    TailnetPhase.STARTING, TailnetPhase.NEEDS_LOGIN -> TailscaleStatusTone.PENDING
    TailnetPhase.FAILED -> TailscaleStatusTone.ERROR
    TailnetPhase.STOPPED, TailnetPhase.DISABLED, TailnetPhase.UNAVAILABLE -> TailscaleStatusTone.IDLE
}

@Composable
private fun TailscaleStatusDot(tone: TailscaleStatusTone, modifier: Modifier = Modifier) {
    val domain = MaterialTheme.infinityDomainColors
    val color = when (tone) {
        TailscaleStatusTone.GOOD -> domain.success
        TailscaleStatusTone.PENDING -> domain.warning
        TailscaleStatusTone.ERROR -> MaterialTheme.colorScheme.error
        TailscaleStatusTone.IDLE -> MaterialTheme.colorScheme.outline
    }
    Box(modifier.clip(CircleShape).background(color))
}

/** 控制服务器行的值：留空为官方服务器，否则只显示主机名（含非默认端口）。 */
@Composable
private fun controlServerLabel(controlUrl: String): String {
    if (controlUrl.isBlank()) return stringResource(R.string.tailscale_control_official)
    return runCatching { Uri.parse(controlUrl).let { uri -> uri.host?.let { host -> if (uri.port > 0) "$host:${uri.port}" else host } } }
        .getOrNull() ?: controlUrl
}

@Composable
private fun TailscaleEditorSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    InfinityBottomSheet(
        show = true,
        onDismiss = onDismiss,
        title = title,
        height = InfinitySheetHeight.Content,
        modifier = Modifier.imePadding(),
    ) { content() }
}

/** 编辑面板底部的取消与保存按钮。 */
@Composable
private fun TailscaleEditorButtons(
    saveEnabled: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    saveTag: String,
) {
    InfinityPillRow(Modifier.fillMaxWidth().padding(top = InfinitySpacing.Medium)) {
        InfinityPillButton(text = stringResource(R.string.common_cancel), onClick = onCancel)
        InfinityPillButton(
            text = stringResource(R.string.shared_btn_save),
            onClick = onSave,
            enabled = saveEnabled,
            tone = InfinityPillTone.PRIMARY,
            modifier = Modifier.testTag(saveTag),
        )
    }
}

@Composable
internal fun TailscaleAuthKeyEditor(
    state: TailscaleSettingsUiState,
    actions: TailscaleSettingsActions,
    onCancel: () -> Unit = {},
) {
    var edit by remember { mutableStateOf("") }
    LaunchedEffect(state.settings.authKeyRevision) { edit = "" }
    SecretField(
        value = edit,
        onValueChange = {
            edit = it
            actions.onAuthKeyEdited()
        },
        hasStoredSecret = state.settings.hasAuthKey,
        onRevealRequest = actions.onRevealAuthKey,
        onClear = {
            edit = ""
            actions.onClearAuthKey()
        },
        placeholder = stringResource(R.string.tailscale_auth_key_hint),
        supportingText = stringResource(if (state.authKeyInvalid) R.string.tailscale_notice_auth_key_invalid else R.string.tailscale_auth_key_desc),
        isError = state.authKeyInvalid,
        modifier = Modifier.fillMaxWidth().testTag("tailscale_auth_key_field"),
    )
    TailscaleEditorButtons(
        saveEnabled = edit.isNotBlank() && state.available && !state.busy,
        onCancel = onCancel,
        onSave = { actions.onSaveAuthKey(edit.toCharArray()) },
        saveTag = "tailscale_auth_key_save",
    )
}

@Composable
internal fun TailscaleControlUrlEditor(
    state: TailscaleSettingsUiState,
    actions: TailscaleSettingsActions,
    onCancel: () -> Unit = {},
) {
    var draft by remember(state.settings.controlUrl) { mutableStateOf(state.settings.controlUrl) }
    OutlinedTextField(
        value = draft,
        onValueChange = {
            draft = it
            actions.onControlUrlEdited()
        },
        modifier = Modifier.fillMaxWidth().testTag("tailscale_control_url_field"),
        placeholder = { Text("https://headscale.example.com") },
        supportingText = {
            Text(stringResource(if (state.controlUrlInvalid) R.string.tailscale_control_url_invalid else R.string.tailscale_control_url_desc))
        },
        isError = state.controlUrlInvalid,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.forInput(),
        shape = MaterialTheme.shapes.small,
        enabled = state.available,
    )
    TailscaleEditorButtons(
        saveEnabled = draft.trim() != state.settings.controlUrl && state.available && !state.busy,
        onCancel = onCancel,
        onSave = { actions.onSaveControlUrl(draft) },
        saveTag = "tailscale_control_url_save",
    )
}

private val StatusDotSize = 12.dp
private val PeerDotSize = 9.dp
private val PeerDotBorder = 1.5.dp
