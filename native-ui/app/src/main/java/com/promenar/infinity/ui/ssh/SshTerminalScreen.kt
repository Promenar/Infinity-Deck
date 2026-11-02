package com.promenar.infinity.ui.ssh

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.exec.ToolLiveOutputStore
import com.promenar.infinity.ui.common.InfinityPageLayout
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.linux.terminal.ExtraKeys
import com.promenar.infinity.ui.linux.terminal.JobOutputPane
import com.promenar.infinity.ui.linux.terminal.TerminalExtraKey
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors

/** 终端里的只读页签：智能体在该主机上运行的命令（ssh_exec）或交互会话（ssh_session）。 */
internal data class SshReadOnlyTab(
    val key: String,
    val kind: Kind,
    /** 在 [ToolLiveOutputStore] 中的实时输出键。 */
    val liveOutputKey: String,
    /** 运行中显示的命令摘要；交互会话为 null。 */
    val summary: String?,
) {
    enum class Kind { EXEC, SESSION }
}

/** 页签键：用户自己的终端固定为此值，只读页签用 [SshReadOnlyTab.key]。 */
internal const val SSH_TERMINAL_MINE_TAB = "mine"

/**
 * SSH 终端页：标题为主机别名，副标题是连接状态与地址；断开在标题栏，按键行与 Linux 终端相同。
 * [readOnlyTabs] 非空时上方出现“我的终端 / 智能体 · 只读 / 交互会话 · 只读”页签，只读页签只能查看，命令可停止。
 * 连接由 [SshUserTerminals] 持有，离开本页不断开。
 */
@Composable
internal fun SshTerminalScreen(
    alias: String,
    onBack: (() -> Unit)?,
    readOnlyTabs: List<SshReadOnlyTab> = emptyList(),
    initialTab: String? = null,
) {
    val app = LocalContext.current.applicationContext as InfinityApplication
    val terminal = remember(app, alias) { SshUserTerminals.get(app, alias) }
    val state by terminal.state.collectAsState()
    var terminalView by remember { mutableStateOf<SshTerminalView?>(null) }
    var atBottom by remember { mutableStateOf(true) }
    var selectedTab by rememberSaveable(alias) { mutableStateOf(initialTab ?: SSH_TERMINAL_MINE_TAB) }
    val store = app.toolLiveOutputStore
    val currentTerminal by rememberUpdatedState(terminal)

    DisposableEffect(Unit) {
        onDispose { terminalView?.detach(); terminalView = null }
    }

    SshTerminalContent(
        alias = alias,
        state = state,
        readOnlyTabs = readOnlyTabs,
        selectedTab = selectedTab,
        atBottom = atBottom,
        onSelectTab = { selectedTab = it },
        onBack = onBack,
        onDisconnect = terminal::disconnect,
        onReconnect = terminal::reconnect,
        onKey = terminal::sendKey,
        onToggleCtrl = terminal::toggleCtrl,
        onToggleAlt = terminal::toggleAlt,
        onToggleKeyboard = { visible -> if (visible) terminalView?.hideKeyboard() else terminalView?.showKeyboard() },
        onScrollToBottom = { terminalView?.scrollToLatest() },
        onStopExec = store::requestStop,
        shellPane = { modifier ->
            state.buffer?.let { buffer ->
                AndroidView(
                    factory = { context -> SshTerminalView(context).also { terminalView = it } },
                    update = { view ->
                        view.isEnabled = !state.connecting && !state.closed
                        view.onInput = { text, control, alt -> currentTerminal.type(text, control, alt) }
                        view.onGridChanged = currentTerminal::resize
                        view.onAtBottomChanged = { atBottom = it }
                        view.attach(buffer)
                    },
                    modifier = modifier,
                )
            }
        },
        readOnlyPane = { tab, scrollState, follow, onFollowChange, modifier ->
            val snapshot by remember(store, tab.liveOutputKey) { store.observe(tab.liveOutputKey) }
                .collectAsState(initial = store.snapshot(tab.liveOutputKey))
            JobOutputPane(snapshot?.text, scrollState, follow, onFollowChange, modifier)
        },
    )
}

/** 无状态界面；[shellPane] 渲染真实终端视图，截图预览中传入示例文本占位。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SshTerminalContent(
    alias: String,
    state: SshTerminalUiState,
    readOnlyTabs: List<SshReadOnlyTab>,
    selectedTab: String,
    atBottom: Boolean,
    onSelectTab: (String) -> Unit,
    onBack: (() -> Unit)?,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onKey: (TerminalExtraKey) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleKeyboard: (visible: Boolean) -> Unit,
    onScrollToBottom: () -> Unit,
    onStopExec: (String) -> Unit,
    shellPane: @Composable (Modifier) -> Unit,
    readOnlyPane: @Composable (SshReadOnlyTab, ScrollState, Boolean, (Boolean) -> Unit, Modifier) -> Unit,
    imeVisible: Boolean = WindowInsets.isImeVisible,
) {
    // 只读页签结束后从列表消失，选中项回到自己的终端。
    val readOnly = readOnlyTabs.firstOrNull { it.key == selectedTab }
    val connected = !state.connecting && !state.closed
    val readOnlyScroll = remember(readOnly?.key) { ScrollState(0) }
    var readOnlyFollow by remember(readOnly?.key) { mutableStateOf(true) }

    InfinityPageLayout(
        title = alias,
        subtitle = terminalStatus(state),
        onBack = onBack,
        scrollable = false,
        bodyPadding = PaddingValues(
            start = InfinitySpacing.ScreenHorizontal,
            end = InfinitySpacing.ScreenHorizontal,
            top = InfinitySpacing.XSmall,
            bottom = InfinitySpacing.Small,
        ),
        modifier = Modifier.testTag(SshTerminalTags.SCREEN),
        actions = {
            if (connected) {
                IconButton(onClick = onDisconnect, modifier = Modifier.testTag(SshTerminalTags.DISCONNECT)) {
                    Icon(Icons.Rounded.LinkOff, contentDescription = stringResource(R.string.ssh_terminal_disconnect))
                }
            }
        },
    ) {
        if (readOnlyTabs.isNotEmpty()) {
            TerminalTabs(readOnlyTabs, readOnly?.key ?: SSH_TERMINAL_MINE_TAB, onSelectTab)
        }
        if (readOnly == null) {
            if (state.closed) ClosedBanner(state.error, state.connecting, onReconnect)
            else if (state.truncated) TruncatedBanner()
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = InfinitySpacing.Small),
        ) {
            if (readOnly != null) {
                readOnlyPane(readOnly, readOnlyScroll, readOnlyFollow, { readOnlyFollow = it }, Modifier.fillMaxSize())
            } else if (state.buffer != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    shellPane(Modifier.fillMaxSize().heightIn(min = InfinitySpacing.MinimumTouchTarget))
                }
            } else if (state.connecting) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(InfinitySpacing.XLarge))
            }
            val showBottom = if (readOnly != null) !readOnlyFollow else state.buffer != null && !atBottom
            if (showBottom) {
                SmallFloatingActionButton(
                    onClick = { if (readOnly != null) readOnlyFollow = true else onScrollToBottom() },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(InfinitySpacing.Medium)
                        .size(InfinitySpacing.MinimumTouchTarget)
                        .testTag(SshTerminalTags.BOTTOM),
                ) {
                    Icon(Icons.Rounded.KeyboardDoubleArrowDown, contentDescription = stringResource(R.string.ssh_terminal_bottom))
                }
            }
        }
        if (readOnly != null) {
            ReadOnlyStatusLine(readOnly, onStop = { onStopExec(readOnly.liveOutputKey) })
        } else if (state.buffer != null) {
            ExtraKeys(
                modifiers = state.modifiers,
                enabled = connected,
                imeVisible = imeVisible,
                onKey = onKey,
                onToggleCtrl = onToggleCtrl,
                onToggleAlt = onToggleAlt,
                onToggleKeyboard = { onToggleKeyboard(imeVisible) },
            )
        }
    }
}

@Composable
private fun terminalStatus(state: SshTerminalUiState): String = when {
    state.connecting -> stringResource(R.string.ssh_terminal_connecting)
    state.closed -> stringResource(R.string.ssh_term_status_closed)
    else -> state.host?.let { host ->
        stringResource(R.string.ssh_term_status_connected, stringResource(R.string.ssh_visual_host_address, host.username, host.hostname, host.port))
    } ?: stringResource(R.string.ssh_visual_terminal_connected)
}

@Composable
private fun TerminalTabs(tabs: List<SshReadOnlyTab>, selected: String, onSelect: (String) -> Unit) {
    val execCount = tabs.count { it.kind == SshReadOnlyTab.Kind.EXEC }
    val sessionCount = tabs.count { it.kind == SshReadOnlyTab.Kind.SESSION }
    var execIndex = 0
    var sessionIndex = 0
    val segments = buildList {
        add(InfinitySegment(SSH_TERMINAL_MINE_TAB, stringResource(R.string.ssh_term_tab_mine), tag = SshTerminalTags.tab(SSH_TERMINAL_MINE_TAB)))
        tabs.forEach { tab ->
            val label = when (tab.kind) {
                SshReadOnlyTab.Kind.EXEC -> if (execCount > 1) stringResource(R.string.ssh_term_tab_agent_n, ++execIndex) else stringResource(R.string.ssh_term_tab_agent)
                SshReadOnlyTab.Kind.SESSION -> if (sessionCount > 1) stringResource(R.string.ssh_term_tab_session_n, ++sessionIndex) else stringResource(R.string.ssh_term_tab_session)
            }
            add(
                InfinitySegment(
                    value = tab.key,
                    label = label,
                    tag = SshTerminalTags.tab(tab.key),
                    leading = {
                        Box(Modifier.size(8.dp).background(MaterialTheme.infinityDomainColors.warning, CircleShape))
                    },
                ),
            )
        }
    }
    InfinitySegmentedControl(
        segments = segments,
        selected = selected,
        onSelect = onSelect,
        compact = true,
        contentPadding = PaddingValues(bottom = InfinitySpacing.XSmall),
    )
}

/** 连接断开时的细横幅：有错误时给出原因；不自动重连，由用户点“重新连接”。 */
@Composable
private fun ClosedBanner(error: String?, connecting: Boolean, onReconnect: () -> Unit) {
    val context = LocalContext.current
    Surface(
        color = if (error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (error != null) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(bottom = InfinitySpacing.Small).testTag(SshTerminalTags.CLOSED_BANNER),
    ) {
        Row(
            modifier = Modifier.padding(start = InfinitySpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        ) {
            if (error != null) Icon(Icons.Rounded.ErrorOutline, contentDescription = null, modifier = Modifier.size(InfinitySpacing.Large))
            Text(
                text = error?.let { SshErrorMessages.message(context, it) } ?: stringResource(R.string.ssh_term_closed_banner),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 横幅底色随错误状态切换，图标沿用横幅的内容色。
            IconButton(onClick = onReconnect, enabled = !connecting, modifier = Modifier.testTag(SshTerminalTags.RECONNECT)) {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.ssh_terminal_reconnect))
            }
        }
    }
}

@Composable
private fun TruncatedBanner() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = InfinitySpacing.Small),
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.infinityDomainColors.warning,
            modifier = Modifier.size(InfinitySpacing.Large),
        )
        Text(
            stringResource(R.string.ssh_terminal_truncated),
            color = MaterialTheme.infinityDomainColors.warning,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 只读页签底部：命令显示“运行中：命令”与停止；交互会话只说明只读。 */
@Composable
private fun ReadOnlyStatusLine(tab: SshReadOnlyTab, onStop: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = InfinitySpacing.MinimumTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = tab.summary?.let { stringResource(R.string.ssh_term_running, it) } ?: stringResource(R.string.ssh_term_readonly),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (tab.kind == SshReadOnlyTab.Kind.EXEC) {
            IconButton(onClick = onStop, modifier = Modifier.testTag(SshTerminalTags.STOP)) {
                Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.ssh_term_stop), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

internal object SshTerminalTags {
    const val SCREEN = "ssh_terminal_screen"
    const val DISCONNECT = "ssh_terminal_disconnect"
    const val RECONNECT = "ssh_terminal_reconnect"
    const val CLOSED_BANNER = "ssh_terminal_closed_banner"
    const val BOTTOM = "ssh_terminal_bottom"
    const val STOP = "ssh_terminal_stop"
    fun tab(key: String) = "ssh_terminal_tab:$key"
}
