package com.promenar.infinity.ui.linux.terminal

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.domain.jobs.BackgroundJobState
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.domain.linux.LinuxTerminalLocation
import com.promenar.infinity.domain.linux.LinuxUnavailableReason
import com.promenar.infinity.ui.common.ConfirmDialog
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinityPageLayout
import com.promenar.infinity.ui.common.InfinityRetryButton
import com.promenar.infinity.ui.linux.output.AnsiPalette
import com.promenar.infinity.ui.linux.output.AnsiParser
import com.promenar.infinity.ui.linux.output.OutputDocument
import com.promenar.infinity.ui.linux.output.OutputLineColors
import com.promenar.infinity.ui.linux.output.outputLine
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.common.InfinityMetaLine

/**
 * 本地 Linux 终端页。
 *
 * @param request 非 null 时进入页面即新开一个会话（可带自动输入的命令）；null 时展示已有会话，没有会话则在 `/root` 新开一个。
 * @param onOpenEnvironment 环境未就绪时“前往 Linux 环境”按钮的回调。
 */
@Composable
fun LinuxTerminalScreen(
    request: LinuxTerminalRequest?,
    onBack: () -> Unit,
    onOpenEnvironment: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as InfinityApplication
    val viewModel: LinuxTerminalViewModel = viewModel(key = "linux-terminal", factory = LinuxTerminalViewModel.factory(app, request))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bridge = rememberTerminalBridge()

    LinuxTerminalContent(
        state = state,
        atBottom = bridge.atBottom,
        onBack = onBack,
        onOpenEnvironment = onOpenEnvironment,
        onNewTerminal = viewModel::openNew,
        onRetry = viewModel::retryOpen,
        onSelect = viewModel::select,
        onClose = viewModel::close,
        onKey = viewModel::sendKey,
        onToggleCtrl = viewModel::toggleCtrl,
        onToggleAlt = viewModel::toggleAlt,
        onToggleKeyboard = { visible -> if (visible) bridge.hideKeyboard() else bridge.showKeyboard() },
        onScrollToBottom = bridge::scrollToBottom,
        shellPane = { sessionId, modifier ->
            LocalTerminalPane(
                sessionId = sessionId,
                terminals = viewModel.terminals,
                bridge = bridge,
                readControl = viewModel::consumeCtrl,
                readAlt = viewModel::consumeAlt,
                modifier = modifier,
            )
        },
    )
}

/**
 * 无状态界面。[shellPane] 渲染真实终端视图；截图预览中传入示例文本占位。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LinuxTerminalContent(
    state: LinuxTerminalUiState,
    atBottom: Boolean,
    onBack: () -> Unit,
    onOpenEnvironment: () -> Unit,
    onNewTerminal: () -> Unit,
    onRetry: () -> Unit,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onKey: (TerminalExtraKey) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleKeyboard: (visible: Boolean) -> Unit,
    onScrollToBottom: () -> Unit,
    shellPane: @Composable (sessionId: String, modifier: Modifier) -> Unit,
    imeVisible: Boolean = WindowInsets.isImeVisible,
) {
    val selected = state.selectedTab
    var confirmClose by remember { mutableStateOf<TerminalTab.Shell?>(null) }
    // 只读任务页签自己管理滚动位置；切换页签时重置。
    val jobScroll = remember(selected?.key) { ScrollState(0) }
    var jobFollow by remember(selected?.key) { mutableStateOf(true) }

    InfinityPageLayout(
        title = stringResource(R.string.linux_terminal_title),
        subtitle = terminalSubtitle(state),
        onBack = onBack,
        scrollable = false,
        // 终端区尽量占满：主体只留左右边距和很窄的上下留白，键盘弹出时按键行紧贴键盘。
        bodyPadding = PaddingValues(
            start = InfinitySpacing.ScreenHorizontal,
            end = InfinitySpacing.ScreenHorizontal,
            top = InfinitySpacing.XSmall,
            bottom = InfinitySpacing.Small,
        ),
        modifier = Modifier.testTag(UiTags.LINUX_TERMINAL_SCREEN),
        actions = {
            if (state.ready) {
                IconButton(onClick = onNewTerminal, enabled = !state.opening, modifier = Modifier.testTag(UiTags.LINUX_TERMINAL_NEW)) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.linux_terminal_new))
                }
                if (selected != null) {
                    IconButton(
                        onClick = {
                            if (selected is TerminalTab.Shell && selected.info.running) confirmClose = selected else onClose(selected.key)
                        },
                        modifier = Modifier.testTag(UiTags.LINUX_TERMINAL_CLOSE),
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.linux_terminal_close))
                    }
                }
            }
        },
    ) {
        if (!state.ready) {
            EnvironmentNotReady(state.runtime, onOpenEnvironment)
            return@InfinityPageLayout
        }
        TabsRow(state, onSelect)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = InfinitySpacing.Small),
        ) {
            when (selected) {
                is TerminalTab.Shell -> Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    shellPane(selected.info.id, Modifier.fillMaxSize().padding(InfinitySpacing.XSmall))
                }
                is TerminalTab.AgentJob -> JobOutputPane(
                    text = state.jobTails[selected.job.id]?.text,
                    scrollState = jobScroll,
                    follow = jobFollow,
                    onFollowChange = { jobFollow = it },
                    modifier = Modifier.fillMaxSize(),
                )
                null -> OpeningOrFailed(state.opening, state.openFailed, onRetry)
            }
            val showBottom = when (selected) {
                is TerminalTab.Shell -> !atBottom
                is TerminalTab.AgentJob -> !jobFollow
                null -> false
            }
            if (showBottom) {
                SmallFloatingActionButton(
                    onClick = {
                        if (selected is TerminalTab.AgentJob) jobFollow = true else onScrollToBottom()
                    },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(InfinitySpacing.Medium)
                        .size(InfinitySpacing.MinimumTouchTarget)
                        .testTag(UiTags.LINUX_TERMINAL_BOTTOM),
                ) {
                    Icon(Icons.Rounded.KeyboardDoubleArrowDown, contentDescription = stringResource(R.string.linux_terminal_bottom))
                }
            }
        }
        when (selected) {
            is TerminalTab.Shell -> {
                if (!selected.info.running) ExitedBanner(selected, onClose)
                ExtraKeys(
                    modifiers = state.modifiers,
                    enabled = selected.info.running,
                    imeVisible = imeVisible,
                    onKey = onKey,
                    onToggleCtrl = onToggleCtrl,
                    onToggleAlt = onToggleAlt,
                    onToggleKeyboard = { onToggleKeyboard(imeVisible) },
                )
            }
            is TerminalTab.AgentJob -> {
                JobStatusLine(selected, state.jobTails[selected.job.id]?.truncated == true)
            }
            null -> Unit
        }
    }

    ConfirmDialog(
        show = confirmClose != null,
        onDismiss = { confirmClose = null },
        onConfirm = {
            confirmClose?.let { onClose(it.key) }
            confirmClose = null
        },
        title = stringResource(R.string.linux_terminal_close_title, confirmClose?.info?.ordinal ?: 0),
        description = stringResource(R.string.linux_terminal_close_message),
        confirmLabel = stringResource(R.string.linux_terminal_close_confirm),
    )
}

@Composable
private fun terminalSubtitle(state: LinuxTerminalUiState): String? {
    // 目录与发行版已由 shell 提示符给出；副标题只标出绑定的会话工作区。
    val workspaceSessionId = when (val tab = state.selectedTab) {
        is TerminalTab.Shell -> (tab.info.location as? LinuxTerminalLocation.SessionWorkspace)?.sessionId
        is TerminalTab.AgentJob -> tab.job.sessionId
        null -> null
    } ?: return null
    return stringResource(
        R.string.linux_terminal_subtitle_workspace,
        state.sessionTitles[workspaceSessionId] ?: stringResource(R.string.linux_terminal_workspace_untitled),
    )
}

@Composable
private fun TabsRow(state: LinuxTerminalUiState, onSelect: (String) -> Unit) {
    // 单个页签没有切换意义；新开第二个会话后才显示页签行。
    if (state.tabs.size < 2) return
    val segments = state.tabs.map { tab ->
        when (tab) {
            is TerminalTab.Shell -> InfinitySegment(
                value = tab.key,
                label = stringResource(if (tab.info.running) R.string.linux_terminal_tab else R.string.linux_terminal_tab_exited, tab.info.ordinal),
                tag = UiTags.linuxTerminalTab(tab.info.id),
            )
            is TerminalTab.AgentJob -> InfinitySegment(
                value = tab.key,
                label = stringResource(R.string.linux_terminal_job_tab, tab.ordinal),
                tag = UiTags.linuxTerminalJobTab(tab.job.id),
                leading = {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(
                                if (tab.job.state == BackgroundJobState.RUNNING) MaterialTheme.infinityDomainColors.warning
                                else MaterialTheme.colorScheme.outline,
                                CircleShape,
                            ),
                    )
                },
            )
        }
    }
    InfinitySegmentedControl(
        segments = segments,
        selected = state.selectedTab?.key,
        onSelect = onSelect,
        compact = true,
        contentPadding = PaddingValues(bottom = InfinitySpacing.XSmall),
    )
}

@Composable
private fun OpeningOrFailed(opening: Boolean, failed: Boolean, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium, Alignment.CenterVertically),
    ) {
        if (failed && !opening) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.linux_terminal_open_failed), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            InfinityRetryButton(
                onClick = onRetry,
                text = stringResource(R.string.linux_terminal_retry),
                modifier = Modifier.testTag(UiTags.LINUX_TERMINAL_RETRY),
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(InfinitySpacing.XLarge))
            Text(stringResource(R.string.linux_terminal_opening), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ColumnScope.EnvironmentNotReady(runtime: LinuxRuntimeState?, onOpenEnvironment: () -> Unit) {
    val (title, description) = when (runtime) {
        null -> R.string.linux_terminal_state_checking to null
        is LinuxRuntimeState.NotInstalled -> R.string.linux_terminal_state_not_installed to R.string.linux_terminal_state_not_installed_desc
        is LinuxRuntimeState.Installing -> R.string.linux_terminal_state_installing to R.string.linux_terminal_state_installing_desc
        is LinuxRuntimeState.InstallInterrupted -> R.string.linux_terminal_state_interrupted to R.string.linux_terminal_state_interrupted_desc
        is LinuxRuntimeState.Maintaining -> R.string.linux_terminal_state_maintaining to null
        is LinuxRuntimeState.Broken -> R.string.linux_terminal_state_broken to R.string.linux_terminal_state_broken_desc
        is LinuxRuntimeState.Unavailable -> when (runtime.reason) {
            LinuxUnavailableReason.DISTRIBUTION -> R.string.linux_terminal_state_distribution to R.string.linux_terminal_state_distribution_desc
            LinuxUnavailableReason.UNSUPPORTED_ABI -> R.string.linux_terminal_state_abi to null
        }
        is LinuxRuntimeState.Ready -> R.string.linux_terminal_opening to null
    }
    val canOpenEnvironment = runtime != null && runtime !is LinuxRuntimeState.Unavailable
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().testTag(UiTags.LINUX_TERMINAL_EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium, Alignment.CenterVertically),
    ) {
        Icon(
            Icons.Rounded.Terminal,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        description?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (canOpenEnvironment) {
            Button(
                onClick = onOpenEnvironment,
                modifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget).testTag(UiTags.LINUX_TERMINAL_OPEN_ENVIRONMENT),
            ) {
                Text(stringResource(R.string.linux_terminal_open_environment))
            }
        }
    }
}

@Composable
private fun ExitedBanner(tab: TerminalTab.Shell, onClose: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(bottom = InfinitySpacing.Small),
    ) {
        Row(
            modifier = Modifier.padding(start = InfinitySpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tab.info.exitCode?.let { stringResource(R.string.linux_terminal_exited, it) }
                    ?: stringResource(R.string.linux_terminal_exited_unknown),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onClose(tab.key) }) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.linux_terminal_close_confirm),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 额外按键：两行七列的紧凑网格，与 Termux 按键行同为 40dp 高。
 * 上行 `Esc / - | ~ ↑ 键盘`，下行 `Tab Ctrl Alt ← ↓ →`；Ctrl、Alt 各占一格半，使 ↑ 与 ↓ 对齐在同一列。
 * SSH 终端共用同一按键行。
 */
@Composable
internal fun ExtraKeys(
    modifiers: TerminalModifiers,
    enabled: Boolean,
    imeVisible: Boolean,
    onKey: (TerminalExtraKey) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleKeyboard: () -> Unit,
) {
    val gap = InfinitySpacing.XSmall
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides CompactTouchTarget) {
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                KeyButton("Esc", TerminalExtraKey.ESC.id, enabled) { onKey(TerminalExtraKey.ESC) }
                KeyButton("/", TerminalExtraKey.SLASH.id, enabled) { onKey(TerminalExtraKey.SLASH) }
                KeyButton("-", TerminalExtraKey.DASH.id, enabled) { onKey(TerminalExtraKey.DASH) }
                KeyButton("|", TerminalExtraKey.PIPE.id, enabled) { onKey(TerminalExtraKey.PIPE) }
                KeyButton("~", TerminalExtraKey.TILDE.id, enabled) { onKey(TerminalExtraKey.TILDE) }
                ArrowKey("↑", TerminalExtraKey.UP, R.string.linux_terminal_key_up, enabled, onKey)
                KeyButton(
                    label = null,
                    icon = if (imeVisible) Icons.Rounded.KeyboardHide else Icons.Rounded.Keyboard,
                    id = "keyboard",
                    enabled = enabled,
                    selected = imeVisible,
                    description = stringResource(R.string.linux_terminal_keyboard),
                    tag = UiTags.LINUX_TERMINAL_KEYBOARD,
                    onClick = onToggleKeyboard,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                KeyButton("Tab", TerminalExtraKey.TAB.id, enabled) { onKey(TerminalExtraKey.TAB) }
                KeyButton("Ctrl", "ctrl", enabled, selected = modifiers.ctrl, weight = 1.5f, onClick = onToggleCtrl)
                KeyButton("Alt", "alt", enabled, selected = modifiers.alt, weight = 1.5f, onClick = onToggleAlt)
                ArrowKey("←", TerminalExtraKey.LEFT, R.string.linux_terminal_key_left, enabled, onKey)
                ArrowKey("↓", TerminalExtraKey.DOWN, R.string.linux_terminal_key_down, enabled, onKey)
                ArrowKey("→", TerminalExtraKey.RIGHT, R.string.linux_terminal_key_right, enabled, onKey)
            }
        }
    }
}

/** 方向键是按钮符号而非输入字符；JetBrains Mono 的箭头只占一格宽度，在按键上显得过小，改用系统字体。 */
@Composable
private fun RowScope.ArrowKey(label: String, key: TerminalExtraKey, description: Int, enabled: Boolean, onKey: (TerminalExtraKey) -> Unit) =
    KeyButton(label, key.id, enabled, monospace = false, description = stringResource(description)) { onKey(key) }

/** 终端页签与按键行的紧凑触控高度。 */
private val CompactTouchTarget = 40.dp

@Composable
private fun RowScope.KeyButton(
    label: String?,
    id: String,
    enabled: Boolean,
    selected: Boolean = false,
    description: String? = null,
    weight: Float = 1f,
    monospace: Boolean = true,
    icon: ImageVector? = null,
    tag: String = UiTags.linuxTerminalKey(id),
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val contentColor = if (enabled) androidx.compose.ui.graphics.Color.Unspecified else colors.onSurface.copy(alpha = 0.38f)
    Surface(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        color = if (selected) colors.primaryContainer else colors.surfaceContainerHigh,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
        modifier = Modifier
            .weight(weight)
            .height(CompactTouchTarget)
            .testTag(tag)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = if (enabled) LocalContentColor.current else contentColor)
            } else if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = if (monospace) InfinityMonospace else null,
                    maxLines = 1,
                    color = contentColor,
                )
            }
        }
    }
}

@Composable
private fun JobStatusLine(tab: TerminalTab.AgentJob, truncated: Boolean) {
    val job = tab.job
    val status = when {
        job.state == BackgroundJobState.RUNNING -> stringResource(R.string.linux_terminal_job_running, job.summary)
        job.exitCode != null -> stringResource(R.string.linux_terminal_job_ended, job.exitCode)
        else -> stringResource(R.string.linux_terminal_job_ended_unknown)
    }
    val modifier = Modifier.fillMaxWidth().padding(bottom = InfinitySpacing.XSmall)
    // 运行中的状态带命令摘要，可能很长，单独显示时仍按两行省略；有截断提示时分段排版。
    if (truncated) {
        InfinityMetaLine(
            segments = listOf(status, stringResource(R.string.linux_terminal_job_truncated)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            modifier = modifier,
        )
    } else {
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
    }
}

/**
 * 只读的等宽输出区：解析 ANSI 颜色，跟随尾部直到用户向上翻看。也用于预览中代替 TerminalView。
 */
@Composable
fun JobOutputPane(
    text: String?,
    scrollState: ScrollState,
    follow: Boolean,
    onFollowChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val palette = if (scheme.background.luminance() < 0.5f) AnsiPalette.Dark else AnsiPalette.Light
    val colors = OutputLineColors(
        foreground = scheme.onSurface,
        stderr = scheme.error,
        separator = scheme.onSurfaceVariant,
        match = scheme.tertiaryContainer,
        onMatch = scheme.onTertiaryContainer,
        currentMatch = scheme.primary,
        onCurrentMatch = scheme.onPrimary,
    )
    val annotated = remember(text, palette, colors) {
        val document = OutputDocument.of(AnsiParser.parse(text.orEmpty()))
        if (document.text.isEmpty()) null
        else outputLine(document, 0 until document.text.length, emptyList(), null, palette, colors)
    }
    LaunchedEffect(scrollState, follow) {
        if (!follow) return@LaunchedEffect
        snapshotFlow { scrollState.maxValue }.collect { scrollState.scrollTo(it) }
    }
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.isScrollInProgress to scrollState.value }.collect { (scrolling, value) ->
            if (scrolling) onFollowChange(value >= scrollState.maxValue - 8)
        }
    }
    Surface(
        color = scheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.testTag(UiTags.LINUX_TERMINAL_JOB_OUTPUT),
    ) {
        if (annotated == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.linux_terminal_job_waiting), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        } else {
            Text(
                text = annotated,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace, color = scheme.onSurface),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(InfinitySpacing.Medium),
            )
        }
    }
}

/** 预览与截图测试使用：带固定滚动状态的只读输出区。 */
@Composable
fun TerminalTextPreviewPane(text: String, modifier: Modifier = Modifier) {
    JobOutputPane(text = text, scrollState = rememberScrollState(), follow = false, onFollowChange = {}, modifier = modifier)
}
