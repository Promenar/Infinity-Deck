package com.promenar.infinity.ui.workspace

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import com.promenar.infinity.ui.theme.infinityDomainColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.R
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.domain.model.ExecutionModeCodec
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinitySettingsGroup
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.settings.NetworkAccessRows
import com.promenar.infinity.ui.settings.NetworkRunMode
import com.promenar.infinity.ui.settings.tailnetSummarySegments
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.breakableIdentifier
import com.promenar.infinity.ui.theme.forReading
import com.promenar.infinity.ui.common.InfinityMetaLine

internal data class AgentReachActions(
    /** 局域网白名单是全局设置：非 null 时该行关闭面板并打开全局网络访问页（R3），为 null 时只显示项数。 */
    val onOpenNetworkAccess: (() -> Unit)? = null,
    /** 所在页面无法跳转设置时为 null，Tailscale 行只显示状态。 */
    val onOpenTailscale: (() -> Unit)? = null,
    /** 非 null 时 Linux 行可点按，进入 Linux 环境页安装、修复或开关。 */
    val onOpenLinux: (() -> Unit)? = null,
    /** 非 null 时工作区一行提供切换；从会话顶栏打开面板时传入。 */
    val workspaceSwitch: AgentReachWorkspaceSwitch? = null,
    /** 非 null 时“未启用”一行带“设置”，打开 Termux 与 SSH 所在的工具设置。 */
    val onOpenToolSettings: (() -> Unit)? = null,
)

/**
 * 摘要卡内的工作区切换：生成进行中或有待审批时停用，切换失败时在工作区下方提示。
 * [onStopGeneration] 非 null 时，停用说明条右侧提供“停止生成”，停止当前会话的生成。
 */
internal data class AgentReachWorkspaceSwitch(
    val busy: Boolean,
    val failed: Boolean,
    val onChoose: () -> Unit,
    val onStopGeneration: (() -> Unit)? = null,
)

/** 工作区切换下方说明条的内容：切换失败优先于生成中停用。 */
internal enum class ReachSwitchHintKind { BUSY, FAILED }

internal data class ReachSwitchHint(val kind: ReachSwitchHintKind, val showStop: Boolean)

/** 说明条：切换失败时显示错误；生成中停用时说明原因，取得停止生成入口时带“停止生成”；其余情况不显示。 */
internal fun reachSwitchHint(switch: AgentReachWorkspaceSwitch?): ReachSwitchHint? = when {
    switch == null -> null
    switch.failed -> ReachSwitchHint(ReachSwitchHintKind.FAILED, showStop = false)
    switch.busy -> ReachSwitchHint(ReachSwitchHintKind.BUSY, showStop = switch.onStopGeneration != null)
    else -> null
}

private const val REACH_SWITCH_HINT_TAG = "agent_reach_workspace_switch_hint"
private const val REACH_STOP_GENERATION_TAG = "agent_reach_stop_generation"

/**
 * 会话的工作区与“Agent 可达范围”面板，从顶栏状态行的工作区打开；[executionMode] 为会话保存的执行模式原值。
 * [workspaceKey] 变化（切换了工作区）时重新解析工作区。
 */
@Composable
internal fun AgentReachSheet(
    sessionId: String,
    executionMode: String?,
    onDismiss: () -> Unit,
    onOpenTailscaleSettings: (() -> Unit)? = null,
    workspaceSwitch: AgentReachWorkspaceSwitch? = null,
    workspaceKey: Any? = null,
) {
    val application = LocalContext.current.applicationContext as Application
    val viewModel: AgentReachViewModel = viewModel(
        key = "agent-reach-$sessionId",
        factory = AgentReachViewModel.factory(application, sessionId),
    )
    LaunchedEffect(viewModel, workspaceKey) { viewModel.refresh() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val runMode by viewModel.runMode.collectAsStateWithLifecycle()
    val linuxNavigation = com.promenar.infinity.ui.linux.LocalLinuxNavigation.current
    val sessionSettingsNavigation = com.promenar.infinity.ui.chat.LocalSessionSettingsNavigation.current
    InfinityBottomSheet(
        show = true,
        onDismiss = onDismiss,
        title = stringResource(R.string.agent_reach_title),
        height = InfinitySheetHeight.Full,
    ) {
        AgentReachContent(
            state = state.copy(executionMode = ExecutionModeCodec.parseOrWorkspace(executionMode)),
            runMode = runMode,
            actions = AgentReachActions(
                onOpenNetworkAccess = sessionSettingsNavigation?.let { navigation -> { onDismiss(); navigation.openNetworkAccess() } },
                onOpenTailscale = onOpenTailscaleSettings?.let { open -> { onDismiss(); open() } },
                onOpenLinux = linuxNavigation?.let { navigation -> { onDismiss(); navigation.openEnvironment() } },
                workspaceSwitch = workspaceSwitch,
            ),
        )
    }
}

/**
 * 面板正文，按“当前是什么 → 文件在哪 → 谁能运行代码 → 什么时候问你 → 能连到哪里”的顺序排列，
 * 每一节只陈述当前会话的事实，与执行层规则一一对应。所有分区以同一个运行模式为准。
 */
@Composable
internal fun AgentReachContent(
    state: AgentReachState,
    actions: AgentReachActions,
    modifier: Modifier = Modifier,
    /** 会话保存的运行模式；未取得时按 Linux 环境是否接管 shell 推断。 */
    runMode: SessionRunMode? = null,
    /** “各工具明细”的初始展开状态；截图预览用来检查总表。 */
    networkDetailsExpanded: Boolean = false,
) {
    val mode = reachRunMode(runMode, state.backends)
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = InfinitySpacing.XLarge)
            .testTag("agent_reach_content"),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.SectionGap),
    ) {
        SummaryCard(state, mode, actions.workspaceSwitch)
        PathSection(state, mode)
        RunnerSection(state, mode, actions)
        ApprovalSection(state)
        NetworkSection(state, actions, mode, networkDetailsExpanded)
    }
}

internal fun SessionRunMode.toNetworkRunMode(): NetworkRunMode = when (this) {
    SessionRunMode.LINUX -> NetworkRunMode.LINUX
    SessionRunMode.BUILTIN -> NetworkRunMode.BUILTIN
}

/** 摘要卡：运行模式与权限模式两个小标签，下面是当前工作区、存放位置与是否进备份，右侧切换。 */
@Composable
private fun SummaryCard(state: AgentReachState, mode: SessionRunMode, switch: AgentReachWorkspaceSwitch?) {
    val workspace = state.workspace
    val hint = reachSwitchHint(switch)
    InfinitySettingsGroup {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Medium)
                .testTag("agent_reach_workspace"),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
            ) {
                ReachPill(
                    stringResource(if (mode == SessionRunMode.LINUX) R.string.agent_reach_run_mode_linux else R.string.agent_reach_run_mode_builtin),
                    Modifier.testTag("agent_reach_run_mode"),
                )
                ReachPill(
                    stringResource(
                        when (state.executionMode) {
                            ExecutionMode.WORKSPACE -> R.string.agent_reach_permission_workspace
                            ExecutionMode.FULL_ACCESS -> R.string.agent_reach_permission_full_access
                        },
                    ),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when (workspace) {
                            SessionWorkspaceUi.Loading -> "…"
                            SessionWorkspaceUi.Private -> privateWorkspaceLabel()
                            is SessionWorkspaceUi.Directory -> workspace.name
                            is SessionWorkspaceUi.Unavailable -> workspace.name ?: stringResource(R.string.workspace_missing_name)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val traits = workspace.traits
                    val subtitle = when (workspace) {
                        is SessionWorkspaceUi.Unavailable -> workspace.reason.ifBlank { stringResource(R.string.agent_reach_workspace_unavailable) }
                        else -> traits?.let {
                            stringResource(
                                R.string.agent_reach_workspace_storage,
                                workspaceStorageLabel((workspace as? SessionWorkspaceUi.Directory)?.kind),
                                stringResource(if (it.backedUp) R.string.workspace_tag_backed_up else R.string.workspace_tag_not_backed_up),
                            )
                        }
                    }
                    if (subtitle != null) {
                        Spacer(Modifier.height(InfinitySpacing.RowTwoLineGap))
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (workspace is SessionWorkspaceUi.Unavailable) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (switch != null) {
                    val label = stringResource(R.string.agent_reach_workspace_switch)
                    // 停用或失败时把说明条的文字挂到按钮的状态描述上，读屏聚焦按钮即可听到原因。
                    val reason = hint?.let { reachSwitchHintText(it.kind) }
                    TextButton(
                        onClick = switch.onChoose,
                        enabled = !switch.busy && workspace != SessionWorkspaceUi.Loading,
                        modifier = Modifier
                            .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                            .semantics {
                                contentDescription = label
                                if (reason != null) stateDescription = reason
                            }
                            .testTag("agent_reach_workspace_switch"),
                    ) {
                        Text(
                            stringResource(
                                if (workspace is SessionWorkspaceUi.Unavailable) R.string.workspace_session_reselect
                                else R.string.workspace_bar_switch,
                            ),
                        )
                    }
                }
            }
            hint?.let { ReachSwitchHintStrip(it, onStop = switch?.onStopGeneration) }
        }
    }
}

@Composable
private fun reachSwitchHintText(kind: ReachSwitchHintKind): String = stringResource(
    when (kind) {
        ReachSwitchHintKind.BUSY -> R.string.agent_reach_switch_busy_hint
        ReachSwitchHintKind.FAILED -> R.string.workspace_switch_failed
    },
)

/**
 * 工作区切换下方的说明条：圆角浅色容器，左侧图标、中间说明，生成中停用时右侧可带“停止生成”。
 * 切换失败用错误色并立即播报，生成中停用礼貌播报。
 */
@Composable
private fun ReachSwitchHintStrip(hint: ReachSwitchHint, onStop: (() -> Unit)?) {
    val failed = hint.kind == ReachSwitchHintKind.FAILED
    val accent = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.infinityDomainColors.info
    val container = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.infinityDomainColors.infoContainer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(container.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .heightIn(min = InfinitySpacing.MinimumTouchTarget)
            .padding(start = 12.dp, end = if (hint.showStop && onStop != null) 4.dp else 12.dp, top = 6.dp, bottom = 6.dp)
            .semantics { liveRegion = if (failed) LiveRegionMode.Assertive else LiveRegionMode.Polite }
            .testTag(REACH_SWITCH_HINT_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (failed) Icons.Rounded.ErrorOutline else Icons.Rounded.Schedule,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = reachSwitchHintText(hint.kind),
            style = MaterialTheme.typography.bodySmall.forReading(),
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (hint.showStop && onStop != null) {
            TextButton(
                onClick = onStop,
                modifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget).testTag(REACH_STOP_GENERATION_TAG),
            ) {
                Text(stringResource(R.string.agent_reach_stop_generation), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ReachPill(label: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = CircleShape,
        modifier = modifier,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = InfinitySpacing.Small + 2.dp, vertical = 2.dp),
        )
    }
}

/** 等宽路径标签，放在行的右侧；长路径只在分隔符后折行。 */
@Composable
private fun ReachPathChip(path: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            path.breakableIdentifier(),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = InfinityMonospace),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .semantics { contentDescription = path }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * 面板的通用一行：主标题说含义，副行说范围；[note] 是标题下的小号等宽工具 ID，[path] 是右侧的等宽路径。
 * [onClick] 非 null 时整行可点并带箭头。
 */
@Composable
private fun ReachRow(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    /** 多段元数据副标题：非空时以 [InfinityMetaLine] 排版并取代 [subtitle]。 */
    subtitleSegments: List<String>? = null,
    icon: ImageVector? = null,
    note: String? = null,
    path: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.38f)
            .heightIn(min = InfinitySpacing.RowTwoLineHeight)
            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.RowTwoLineVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.width(InfinitySpacing.RowIconSize + InfinitySpacing.RowIconGap), contentAlignment = Alignment.CenterStart) {
                Icon(icon, null, Modifier.size(InfinitySpacing.RowIconSize), MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitleSegments != null) {
                Spacer(Modifier.height(InfinitySpacing.RowTwoLineGap))
                InfinityMetaLine(subtitleSegments, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (subtitle != null) {
                Spacer(Modifier.height(InfinitySpacing.RowTwoLineGap))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (note != null) {
                Spacer(Modifier.height(InfinitySpacing.XSmall))
                Text(
                    note,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (path != null) {
            Spacer(Modifier.width(InfinitySpacing.Medium))
            Box(Modifier.widthIn(max = 168.dp)) { ReachPathChip(path) }
        }
        if (trailing != null) {
            Spacer(Modifier.width(InfinitySpacing.Small))
            trailing()
        }
        if (onClick != null && trailing == null) {
            Spacer(Modifier.width(InfinitySpacing.Small))
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 格式串中的参数用等宽字体显示。 */
@Composable
private fun monospaceArgument(template: Int, argument: String): AnnotatedString {
    val text = stringResource(template, argument)
    val start = text.indexOf(argument)
    return buildAnnotatedString {
        append(text)
        if (start >= 0) addStyle(SpanStyle(fontFamily = InfinityMonospace), start, start + argument.length)
    }
}

@Composable
private fun PathSection(state: AgentReachState, mode: SessionRunMode) {
    val linux = mode == SessionRunMode.LINUX
    InfinitySettingsSection(
        title = stringResource(R.string.agent_reach_section_paths),
        linkedFooter = if (linux) AnnotatedString(stringResource(R.string.agent_reach_paths_footer_linux))
        else monospaceArgument(R.string.agent_reach_paths_footer_builtin, "\$INFINITY_WORKSPACE"),
    ) {
        reachPaths(mode, state.workspace, state.mounts).forEach { entry ->
            when (entry.kind) {
                ReachPathKind.WORKSPACE -> ReachRow(
                    title = stringResource(R.string.agent_reach_path_workspace),
                    subtitle = stringResource(R.string.agent_reach_path_workspace_desc),
                    path = entry.path,
                    modifier = Modifier.testTag("agent_reach_path_workspace"),
                )
                ReachPathKind.SESSION -> ReachRow(
                    title = privateWorkspaceLabel(),
                    subtitle = stringResource(R.string.agent_reach_path_session_desc),
                    path = entry.path,
                    modifier = Modifier.testTag("agent_reach_path_session"),
                )
                ReachPathKind.MOUNT -> {
                    val mount = checkNotNull(entry.mount)
                    val attributes = listOfNotNull(
                        if (mount.knowledgeBase) null
                        else stringResource(if (mount.source == ReachMountSource.PATH) R.string.agent_reach_mount_path else R.string.agent_reach_mount_saf),
                        stringResource(if (mount.commandAccessible) R.string.agent_reach_mount_access_commands else R.string.agent_reach_mount_access_files_only),
                        if (mount.unavailable) stringResource(R.string.agent_reach_mount_unavailable) else null,
                    )
                    ReachRow(
                        title = mount.alias,
                        subtitle = null,
                        subtitleSegments = attributes,
                        path = entry.path,
                        trailing = if (mount.knowledgeBase) ({ KnowledgeReadOnlyTag() }) else null,
                        modifier = Modifier.testTag("agent_reach_path_mount"),
                    )
                }
                ReachPathKind.SKILLS -> ReachRow(
                    title = stringResource(R.string.agent_reach_path_skills),
                    subtitle = stringResource(R.string.agent_reach_path_skills_desc),
                    path = entry.path,
                    modifier = Modifier.testTag("agent_reach_path_skills"),
                )
            }
        }
    }
}

@Composable
private fun RunnerSection(state: AgentReachState, mode: SessionRunMode, actions: AgentReachActions) {
    val runners = reachRunners(mode, state.backends)
    val traits = state.workspace.traits
    InfinitySettingsSection(
        title = stringResource(R.string.agent_reach_section_backends),
        footer = stringResource(R.string.agent_reach_backends_footer),
    ) {
        runners.shown.forEach { backend ->
            val ready = backend.status == ReachBackendStatus.READY
            val (icon, title) = when (backend.kind) {
                ReachBackendKind.LINUX -> Icons.Rounded.Terminal to R.string.agent_reach_runner_linux
                ReachBackendKind.BUILTIN -> Icons.Rounded.Terminal to R.string.agent_reach_runner_builtin
                ReachBackendKind.SANDBOX -> Icons.Rounded.Code to R.string.agent_reach_runner_sandbox
                ReachBackendKind.TERMUX -> Icons.Rounded.PhoneAndroid to R.string.agent_reach_runner_termux
                ReachBackendKind.SSH -> Icons.Rounded.Dns to R.string.agent_reach_runner_ssh
            }
            val note = when (backend.kind) {
                ReachBackendKind.LINUX -> "shell_exec"
                ReachBackendKind.BUILTIN -> if (backend.status == ReachBackendStatus.SHELL_ONLY) "shell_exec" else "shell_exec · python_exec"
                ReachBackendKind.SANDBOX -> if (backend.status == ReachBackendStatus.JS_ONLY) "exec_js" else "exec_js · python_sandbox"
                ReachBackendKind.TERMUX -> "termux_exec"
                ReachBackendKind.SSH -> "ssh_exec · sftp"
            }
            val scope = when (backend.kind) {
                ReachBackendKind.LINUX -> stringResource(R.string.agent_reach_runner_linux_scope)
                ReachBackendKind.BUILTIN -> stringResource(R.string.agent_reach_backend_builtin_scope)
                ReachBackendKind.SANDBOX -> stringResource(R.string.agent_reach_backend_sandbox_scope)
                ReachBackendKind.TERMUX -> stringResource(
                    if (traits?.termuxVisible == true) R.string.agent_reach_backend_termux_scope_shared else R.string.agent_reach_backend_termux_scope,
                )
                ReachBackendKind.SSH -> stringResource(R.string.agent_reach_backend_ssh_scope)
            }
            // 可用时说明作用范围；缺 Python 时范围之后补一句状态；其它不可用状态只说状态。
            val subtitleSegments = if (ready && backend.count != null) listOf(scope, backendStatusText(backend)) else null
            val subtitle = when {
                subtitleSegments != null -> null
                ready -> scope
                backend.status == ReachBackendStatus.SHELL_ONLY || backend.status == ReachBackendStatus.JS_ONLY ->
                    scope + "\n" + backendStatusText(backend)
                else -> backendStatusText(backend)
            }
            // Linux 的安装、修复与开关都在环境页完成，行本身就是去处理的入口。
            val openLinux = actions.onOpenLinux.takeIf { backend.kind == ReachBackendKind.LINUX && !backend.status.isUnavailable() }
            ReachRow(
                title = stringResource(title),
                subtitle = subtitle,
                subtitleSegments = subtitleSegments,
                icon = icon,
                note = note,
                enabled = backend.status != ReachBackendStatus.DISABLED && !backend.status.isUnavailable(),
                onClick = openLinux,
                modifier = Modifier.testTag("agent_reach_backend_${backend.kind.name.lowercase()}"),
            )
        }
        if (runners.linuxOmitsBuiltins) {
            ReachNoteRow(stringResource(R.string.agent_reach_runners_linux_omits), Modifier.testTag("agent_reach_runners_linux_omits"))
        }
        if (runners.inactive.isNotEmpty()) {
            val names = runners.inactive.map { kind ->
                stringResource(if (kind == ReachBackendKind.TERMUX) R.string.agent_reach_runner_termux else R.string.agent_reach_runner_ssh)
            }
            ReachNoteRow(
                text = stringResource(R.string.agent_reach_runners_inactive, names.joinToString(stringResource(R.string.agent_reach_list_separator))),
                modifier = Modifier.testTag("agent_reach_runners_inactive"),
                action = actions.onOpenToolSettings?.let { open -> stringResource(R.string.agent_reach_runners_settings) to open },
            )
        }
    }
}

/** 合并说明的一行：小号次要文字，可带右侧文字按钮。 */
@Composable
private fun ReachNoteRow(text: String, modifier: Modifier = Modifier, action: Pair<String, () -> Unit>? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = InfinitySpacing.MinimumTouchTarget)
            .padding(start = InfinitySpacing.RowHorizontal, end = if (action == null) InfinitySpacing.RowHorizontal else InfinitySpacing.XSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.forReading(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(vertical = InfinitySpacing.Small),
        )
        if (action != null) TextButton(onClick = action.second) { Text(action.first) }
    }
}

@Composable
private fun backendStatusText(backend: ReachBackend): String = when (backend.status) {
    ReachBackendStatus.READY -> if (backend.kind == ReachBackendKind.SSH && backend.count != null) {
        pluralStringResource(R.plurals.agent_reach_ssh_hosts, backend.count, backend.count)
    } else stringResource(R.string.agent_reach_status_ready)
    ReachBackendStatus.SHELL_ONLY -> stringResource(R.string.agent_reach_status_shell_only)
    ReachBackendStatus.JS_ONLY -> stringResource(R.string.agent_reach_status_js_only)
    ReachBackendStatus.DISABLED -> stringResource(R.string.agent_reach_status_disabled)
    ReachBackendStatus.NOT_INSTALLED -> stringResource(R.string.settings_termux_not_installed)
    ReachBackendStatus.PERMISSION_REQUIRED -> stringResource(R.string.settings_termux_permission_required)
    ReachBackendStatus.UNSUPPORTED_VERSION -> stringResource(R.string.settings_termux_unsupported_version)
    ReachBackendStatus.NO_HOSTS -> stringResource(R.string.agent_reach_status_no_hosts)
    ReachBackendStatus.LOADING -> stringResource(R.string.settings_status_loading)
    ReachBackendStatus.INSTALLING -> stringResource(R.string.linux_reach_installing)
    ReachBackendStatus.BROKEN -> stringResource(R.string.linux_reach_broken)
    ReachBackendStatus.UNAVAILABLE -> stringResource(R.string.linux_reach_unavailable)
    ReachBackendStatus.CHANNEL_UNAVAILABLE -> stringResource(R.string.ux2_unavailable_linux_environment_channel)
}

private fun ReachBackendStatus.isUnavailable(): Boolean =
    this == ReachBackendStatus.UNAVAILABLE || this == ReachBackendStatus.CHANNEL_UNAVAILABLE

@Composable
private fun ApprovalSection(state: AgentReachState) {
    InfinitySettingsSection(
        title = stringResource(R.string.agent_reach_section_approval),
        footer = stringResource(R.string.agent_reach_approval_footer),
    ) {
        InfinitySettingsRowLayout(
            title = stringResource(
                when (state.executionMode) {
                    ExecutionMode.WORKSPACE -> R.string.common_mode_workspace
                    ExecutionMode.FULL_ACCESS -> R.string.common_mode_full_access
                },
            ),
            subtitle = stringResource(
                when (state.executionMode) {
                    ExecutionMode.WORKSPACE -> R.string.common_mode_workspace_desc
                    ExecutionMode.FULL_ACCESS -> R.string.common_mode_full_access_desc
                },
            ),
            icon = Icons.Rounded.VerifiedUser,
            subtitleMaxLines = 3,
            modifier = Modifier.testTag("agent_reach_mode"),
        )
    }
}

/** 按目标分四行说明能连到哪里；原来的工具 × 目标总表收进默认折叠的“各工具明细”。 */
@Composable
private fun NetworkSection(state: AgentReachState, actions: AgentReachActions, mode: SessionRunMode, detailsInitiallyExpanded: Boolean) {
    InfinitySettingsSection(title = stringResource(R.string.agent_reach_section_network)) {
        val network = state.network
        if (network == null) {
            InfinitySettingsRowLayout(title = stringResource(R.string.settings_status_loading))
            return@InfinitySettingsSection
        }
        ReachRow(
            title = stringResource(R.string.network_target_public),
            subtitle = stringResource(R.string.agent_reach_network_public),
            modifier = Modifier.testTag("agent_reach_network_public"),
        )
        val lan = reachLanLines(mode, network)
        val allowlist = if (lan.allowlistSize == 0) stringResource(lan.allowlistRes)
        else pluralStringResource(lan.allowlistRes, lan.allowlistSize, lan.allowlistSize)
        val openNetworkAccess = actions.onOpenNetworkAccess
        ReachRow(
            title = stringResource(R.string.agent_reach_network_lan),
            subtitle = allowlist + "\n" + stringResource(lan.commandsRes),
            onClick = openNetworkAccess,
            trailing = openNetworkAccess?.let {
                {
                    Text(
                        stringResource(R.string.agent_reach_network_change),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            modifier = Modifier.testTag("agent_reach_lan_hosts"),
        )
        ReachRow(
            title = stringResource(R.string.network_tailnet_title),
            subtitle = null,
            subtitleSegments = tailnetSummarySegments(network),
            onClick = actions.onOpenTailscale,
            modifier = Modifier.testTag("agent_reach_network_tailnet"),
        )
        ReachRow(
            title = stringResource(R.string.agent_reach_network_configured),
            subtitle = stringResource(R.string.agent_reach_network_configured_desc),
            modifier = Modifier.testTag("agent_reach_network_configured"),
        )
        var expanded by rememberSaveable { mutableStateOf(detailsInitiallyExpanded) }
        val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "agent_reach_network_details")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded }
                .heightIn(min = InfinitySpacing.RowSingleLineHeight)
                .padding(horizontal = InfinitySpacing.RowHorizontal)
                .testTag("agent_reach_network_details"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.agent_reach_network_details),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(20.dp).rotate(rotation), MaterialTheme.colorScheme.primary)
        }
        AnimatedVisibility(visible = expanded, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
            NetworkAccessRows(network, runMode = mode.toNetworkRunMode())
        }
    }
}
