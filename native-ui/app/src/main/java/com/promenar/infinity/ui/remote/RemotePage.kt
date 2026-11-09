package com.promenar.infinity.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.tailnet.TailnetPhase
import com.promenar.infinity.ui.chat.LocalToolLiveOutput
import com.promenar.infinity.ui.chat.RunningToolLiveOutput
import com.promenar.infinity.ui.common.InfinityChip
import com.promenar.infinity.ui.common.InfinityPageLayout
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.LocalInfinitySettingsEmbedded
import com.promenar.infinity.ui.common.InfinitySegmentTrackInset
import com.promenar.infinity.ui.common.inspectorTopGap
import com.promenar.infinity.domain.keychain.KeychainCategory
import com.promenar.infinity.ui.keychain.KeychainEntryScreen
import com.promenar.infinity.ui.keychain.KeychainScreen
import com.promenar.infinity.ui.settings.NetworkAccessSettingsScreen
import com.promenar.infinity.ui.settings.SshSettingsScreen
import com.promenar.infinity.data.tailnet.TailnetPeer
import com.promenar.infinity.ui.settings.TailscaleSettingsScreen
import com.promenar.infinity.ui.ssh.SshTerminalScreen
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography

/** “远程”面板的分段，顺序即显示顺序。 */
internal enum class RemoteSegment(val token: String) { SSH("ssh"), GIT("git"), TAILSCALE("tailscale"), NETWORK("network") }

/** Tailscale 需要登录、等待批准或启动失败时，远程页签与分段挂橙点。 */
internal fun tailscaleNeedsAttention(phase: TailnetPhase?): Boolean = phase == TailnetPhase.NEEDS_LOGIN || phase == TailnetPhase.FAILED

/** 设置页中与远程相关、但不在面板里管理的入口。 */
enum class RemoteSettingsLink { LINUX, TERMUX, BACKGROUND }

/**
 * 会话“远程”面板：SSH、Git 凭据、Tailscale、网络四段，各段复用设置页的内容与状态。
 * 窄窗口整页显示（[onBack] 非空），宽窗口在检视器“远程”页签中显示，根层不画顶栏。
 * SSH 终端在面板内作为下一层打开，返回回到面板；连接由 [com.promenar.infinity.ui.ssh.SshUserTerminals] 持有，离开不断开。
 */
@Composable
internal fun RemotePage(
    sessionId: String,
    usage: RemoteAgentUsage,
    linuxMode: Boolean,
    onBack: (() -> Unit)?,
    onOpenSettings: ((RemoteSettingsLink) -> Unit)?,
    onOpenBackgroundJobs: (() -> Unit)? = null,
) {
    val app = LocalContext.current.applicationContext as InfinityApplication
    val tailscale by app.tailnetManager.state.collectAsState()
    var segment by rememberSaveable(sessionId) { mutableStateOf(RemoteSegment.SSH.token) }
    var terminalAlias by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var terminalTab by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    // Git 分段里打开的钥匙串条目：整页显示详情，返回回到分段。
    var gitEntry by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    // SSH 分段顶部进入的 SSH 密钥列表：整页显示，条目沿用上面的整页详情。
    var sshKeys by rememberSaveable(sessionId) { mutableStateOf(false) }
    // Tailscale 分段里点“SSH”的设备：切到 SSH 分段后由主机设置打开预填的新建面板。
    var importPeer by remember(sessionId) { mutableStateOf<TailnetPeer?>(null) }
    // 打开面板时智能体正在用 SSH，直接落在 SSH 分段。
    LaunchedEffect(Unit) { if (usage.active) segment = RemoteSegment.SSH.token }

    val openTerminal: (String, String?) -> Unit = { alias, tab ->
        terminalAlias = alias
        terminalTab = tab
    }

    terminalAlias?.let { alias ->
        val closeTerminal = { terminalAlias = null; terminalTab = null }
        BackHandler(onBack = closeTerminal)
        SshTerminalScreen(
            alias = alias,
            onBack = closeTerminal,
            readOnlyTabs = usage.readOnlyTabs(alias),
            initialTab = terminalTab,
        )
        return
    }

    gitEntry?.let { ref ->
        val closeEntry = { gitEntry = null }
        BackHandler(onBack = closeEntry)
        KeychainEntryScreen(ref = ref, onBack = closeEntry, onEdit = {}, onOpenSettings = {})
        return
    }

    if (sshKeys) {
        val closeKeys = { sshKeys = false }
        BackHandler(onBack = closeKeys)
        KeychainScreen(
            initialFilter = KeychainCategory.SSH_KEY,
            lockedFilter = KeychainCategory.SSH_KEY,
            onBack = closeKeys,
            onOpenEntry = { gitEntry = it },
            onNewEntry = {},
        )
        return
    }

    val agentItems: LazyListScope.() -> Unit = { remoteAgentUsageItems(usage, onOpenTerminal = openTerminal, onOpenBackgroundJobs = onOpenBackgroundJobs) }
    // 窄窗口整页在会话内容之外组合，实时输出存储需在此重新提供。
    CompositionLocalProvider(LocalToolLiveOutput provides app.toolLiveOutputStore) {
    RemotePageContent(
        segment = RemoteSegment.entries.firstOrNull { it.token == segment } ?: RemoteSegment.SSH,
        onSelectSegment = { segment = it.token },
        agentActive = usage.active,
        tailscaleAttention = tailscaleNeedsAttention(tailscale.phase),
        linuxMode = linuxMode,
        onBack = onBack,
        onOpenSettings = onOpenSettings,
    ) { selected ->
        when (selected) {
            RemoteSegment.SSH -> SshSettingsScreen(
                onBack = {},
                onOpenTerminal = { alias -> openTerminal(alias, null) },
                leadingContent = agentItems.takeIf { usage.active },
                importPeer = importPeer,
                onImportConsumed = { importPeer = null },
                onOpenTailscale = { segment = RemoteSegment.TAILSCALE.token },
                onOpenSshKeys = { sshKeys = true },
            )
            RemoteSegment.GIT -> KeychainScreen(
                initialFilter = KeychainCategory.GIT,
                lockedFilter = KeychainCategory.GIT,
                onBack = {},
                onOpenEntry = { gitEntry = it },
                onNewEntry = {},
            )
            RemoteSegment.TAILSCALE -> TailscaleSettingsScreen(
                onBack = {},
                onAddSshHost = { peer ->
                    importPeer = peer
                    segment = RemoteSegment.SSH.token
                },
            )
            RemoteSegment.NETWORK -> NetworkAccessSettingsScreen(onBack = {}, onOpenTailscale = { segment = RemoteSegment.TAILSCALE.token })
        }
    }
    }
}

/** 面板外壳：分段、Linux 模式提示、分段内容与底部设置入口；无应用依赖，供截图测试使用。 */
@Composable
internal fun RemotePageContent(
    segment: RemoteSegment,
    onSelectSegment: (RemoteSegment) -> Unit,
    agentActive: Boolean,
    tailscaleAttention: Boolean,
    linuxMode: Boolean,
    onBack: (() -> Unit)?,
    onOpenSettings: ((RemoteSettingsLink) -> Unit)?,
    content: @Composable (RemoteSegment) -> Unit,
) {
    InfinityPageLayout(
        title = stringResource(R.string.remote_panel_title),
        onBack = onBack,
        scrollable = false,
        imePadding = false,
        showTopBar = onBack != null,
        bodyPadding = PaddingValues(0.dp),
        modifier = Modifier.testTag(RemoteTags.PAGE),
    ) {
        InfinitySegmentedControl(
            segments = RemoteSegment.entries.map { value ->
                val dot = when (value) {
                    RemoteSegment.SSH -> if (agentActive) MaterialTheme.colorScheme.primary else null
                    RemoteSegment.TAILSCALE -> if (tailscaleAttention) MaterialTheme.colorScheme.tertiary else null
                    else -> null
                }
                InfinitySegment(
                    value = value,
                    label = stringResource(value.label),
                    tag = RemoteTags.segment(value),
                    leading = dot?.let { color -> { Box(Modifier.size(8.dp).background(color, CircleShape)) } },
                )
            },
            selected = segment,
            onSelect = onSelectSegment,
            fillWidth = true,
            contentPadding = PaddingValues(
                start = InfinitySpacing.Large,
                end = InfinitySpacing.Large,
                top = if (onBack == null) inspectorTopGap(InfinitySpacing.Small, visualInset = InfinitySegmentTrackInset) else InfinitySpacing.Small,
                bottom = InfinitySpacing.XSmall,
            ),
        )
        val hint = when {
            !linuxMode -> null
            segment == RemoteSegment.GIT -> R.string.remote_linux_git_hint
            segment == RemoteSegment.NETWORK -> R.string.remote_linux_network_hint
            else -> null
        }
        hint?.let { RemoteHint(stringResource(it)) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CompositionLocalProvider(LocalInfinitySettingsEmbedded provides PaddingValues(top = InfinitySpacing.XSmall)) {
                content(segment)
            }
        }
        onOpenSettings?.let { open ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            RemoteSettingsLinks(open)
        }
    }
}

private val RemoteSegment.label: Int
    get() = when (this) {
        RemoteSegment.SSH -> R.string.remote_segment_ssh
        RemoteSegment.GIT -> R.string.remote_segment_git
        RemoteSegment.TAILSCALE -> R.string.remote_segment_tailscale
        RemoteSegment.NETWORK -> R.string.remote_segment_network
    }

@Composable
private fun RemoteHint(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = InfinitySpacing.XSmall)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .padding(InfinitySpacing.Medium)
            .testTag(RemoteTags.LINUX_HINT),
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(InfinitySpacing.Large))
        Text(text, style = InfinityTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 底部一行：Linux 环境、Termux、后台与定时仍在设置中管理。 */
@Composable
private fun RemoteSettingsLinks(onOpen: (RemoteSettingsLink) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small),
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.remote_more_settings),
            style = InfinityTypography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        listOf(
            RemoteSettingsLink.LINUX to R.string.remote_link_linux,
            RemoteSettingsLink.TERMUX to R.string.remote_link_termux,
            RemoteSettingsLink.BACKGROUND to R.string.remote_link_background,
        ).forEach { (link, label) ->
            InfinityChip(
                label = stringResource(label),
                onClick = { onOpen(link) },
                trailing = { Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp)) },
                modifier = Modifier.testTag(RemoteTags.link(link)),
            )
        }
    }
}

/**
 * SSH 分段最前的“智能体正在使用”：运行中的命令带实时尾部，可停止；交互会话可在终端只读页签查看。
 * SSH 后台命令与本地后台任务同在运行详情的“后台”列表，这里只给一行计数入口。用户不能向智能体的会话输入。
 */
internal fun LazyListScope.remoteAgentUsageItems(
    usage: RemoteAgentUsage,
    onOpenTerminal: (alias: String, tab: String?) -> Unit,
    onOpenBackgroundJobs: (() -> Unit)?,
) {
    item("agent_usage") {
        InfinitySettingsSection(
            title = stringResource(R.string.remote_agent_section),
            footer = stringResource(R.string.remote_agent_footer),
            modifier = Modifier.testTag(RemoteTags.AGENT_USAGE),
        ) {
            usage.execs.forEach { exec ->
                InfinitySettingsItem(
                    icon = Icons.Rounded.SmartToy,
                    title = exec.host,
                    subtitle = exec.command,
                    subtitleIsIdentifier = true,
                    onClick = { onOpenTerminal(exec.host, "exec:${exec.stepId}") },
                    onClickLabel = stringResource(R.string.remote_agent_view_in_terminal),
                    showChevron = true,
                )
                Box(Modifier.padding(horizontal = InfinitySpacing.Medium)) { RunningToolLiveOutput(exec.stepId) }
            }
            usage.sessions.forEach { session ->
                InfinitySettingsItem(
                    icon = Icons.Rounded.Terminal,
                    title = session.alias,
                    subtitle = stringResource(R.string.remote_agent_session),
                    onClick = { onOpenTerminal(session.alias, "pty:${session.id}") },
                    onClickLabel = stringResource(R.string.remote_agent_view_in_terminal),
                    showChevron = true,
                )
            }
            if (usage.jobs.isNotEmpty()) {
                InfinitySettingsItem(
                    icon = Icons.Rounded.Schedule,
                    title = stringResource(R.string.remote_agent_jobs),
                    subtitle = pluralStringResource(R.plurals.remote_agent_jobs_running, usage.jobs.size, usage.jobs.size),
                    onClick = onOpenBackgroundJobs ?: {},
                    enabled = onOpenBackgroundJobs != null,
                    onClickLabel = stringResource(R.string.remote_agent_jobs_open),
                    showChevron = onOpenBackgroundJobs != null,
                    modifier = Modifier.testTag(RemoteTags.AGENT_JOBS),
                )
            }
        }
    }
}

internal object RemoteTags {
    const val PAGE = "remote_page"
    const val AGENT_USAGE = "remote_agent_usage"
    const val AGENT_JOBS = "remote_agent_jobs"
    const val LINUX_HINT = "remote_linux_hint"
    fun segment(segment: RemoteSegment) = "remote_segment_${segment.token}"
    fun link(link: RemoteSettingsLink) = "remote_link_${link.name.lowercase()}"
}
