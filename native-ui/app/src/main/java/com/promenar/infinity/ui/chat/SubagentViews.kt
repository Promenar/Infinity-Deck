package com.promenar.infinity.ui.chat

import com.promenar.infinity.ui.theme.InfinityMonospace
import androidx.compose.foundation.layout.widthIn
import com.promenar.infinity.domain.tool.CommandToolClassification
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import com.promenar.infinity.ui.common.InfinityHapticEvent
import com.promenar.infinity.ui.theme.forReading
import java.text.NumberFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import androidx.compose.foundation.background
import com.promenar.infinity.ui.testing.UiTags
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.CallMade
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.data.subagent.SubagentApproval
import com.promenar.infinity.data.subagent.SubagentApprovalBroker
import com.promenar.infinity.data.subagent.SubagentBatch
import com.promenar.infinity.data.subagent.SubagentEvent
import com.promenar.infinity.data.subagent.SubagentPreset
import com.promenar.infinity.data.subagent.SubagentRun
import com.promenar.infinity.data.subagent.SubagentStatus
import com.promenar.infinity.data.subagent.SubagentToolProfile
import com.promenar.infinity.domain.tool.ToolExecutionTargets
import com.promenar.infinity.ui.common.MarkdownText
import com.promenar.infinity.ui.common.InfinityPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.contentDescription
import com.promenar.infinity.ui.common.InfinityMetaLine

/** 当前会话的子代理记录与查看入口；预览与测试中为空，此时不显示分组卡。 */
class SubagentRunsAccess(
    val observe: (stepId: String) -> Flow<SubagentBatch?>,
    val snapshot: (stepId: String) -> SubagentBatch?,
    val onOpen: (stepId: String, index: Int) -> Unit,
    /** 子代理实际模型的显示名；查不到时显示模型 ID。 */
    val modelName: (modelId: String) -> String = { it },
)

val LocalSubagentRuns = staticCompositionLocalOf<SubagentRunsAccess?> { null }

/** 子代理卡片与查看页上的“转入后台”“停止”；预览与测试中为空，此时不显示这两个按钮。 */
val LocalSubagentControls = staticCompositionLocalOf<com.promenar.infinity.data.subagent.SubagentControls?> { null }

@Composable
internal fun subagentName(preset: String?, index: Int): String = when (SubagentPreset.parse(preset)) {
    // 同批可能有多个同类子代理，名称始终带序号以便区分。
    SubagentPreset.EXPLORER -> stringResource(R.string.subagent_preset_explorer, index + 1)
    null -> stringResource(R.string.subagent_default_name, index + 1)
}

/** `agent_task` 调用下的子代理分组卡；按该调用的执行步骤 id 读取。 */
@Composable
internal fun SubagentGroupCard(stepId: String, modifier: Modifier = Modifier) {
    val access = LocalSubagentRuns.current ?: return
    val initial = remember(access, stepId) { access.snapshot(stepId) }
    val batch by remember(access, stepId) { access.observe(stepId) }.collectAsState(initial = initial)
    val controls = LocalSubagentControls.current
    val scope = rememberCoroutineScope()
    batch?.let { current ->
        SubagentGroupCardContent(
            current,
            onOpen = { index -> access.onOpen(stepId, index) },
            modifier = modifier,
            modelName = access.modelName,
            onDetach = controls?.let { { it.releaseWait(current.sessionId, stepId) } },
            onStop = controls?.let { { run: SubagentRun -> scope.launch {
                // 停止一旦发出就做完，卡片滚出屏幕时也不中途取消。
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { it.stop(current.sessionId, run.id) }
            } } },
        )
    }
}

@Composable
internal fun SubagentGroupCardContent(
    batch: SubagentBatch,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
    now: Long? = null,
    onDetach: (() -> Unit)? = null,
    onStop: ((SubagentRun) -> Unit)? = null,
    modelName: (String) -> String = { it },
) {
    val active = batch.runs.any { it.status.isActive }
    var clock by remember { mutableLongStateOf(now ?: System.currentTimeMillis()) }
    if (now == null && active) {
        LaunchedEffect(batch.stepId) {
            while (true) {
                clock = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }
    Column(
        modifier = modifier.fillMaxWidth().padding(bottom = ExpandedStepBottomPadding).testTag("CHAT_SUBAGENT_GROUP"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val wait = batch.wait?.takeIf { active }
        // 主会话在等这批子代理时显示剩余时间；不等时（后台派生、转入后台或超时）说明仍在后台运行。
        val waitText = when {
            wait != null -> stringResource(R.string.subagent_wait_remaining, subagentCountdown((wait.deadline - (now ?: clock)).coerceAtLeast(0)))
            active -> stringResource(R.string.subagent_running_background)
            else -> null
        }
        Row(
            modifier = Modifier.heightIn(min = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Rounded.AccountTree, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = if (batch.runs.size == 1) stringResource(R.string.subagent_group_single)
                else pluralStringResource(R.plurals.subagent_group_parallel, batch.runs.size, batch.runs.size),
                style = InfinityTypography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 有“转入后台”按钮时状态文字换到下一行，不和按钮抢宽度。
            waitText?.takeUnless { wait != null && onDetach != null }?.let {
                Text(
                    text = "· $it",  // wrap-check: allow 行内单行省略的状态后缀
                    style = InfinityTypography.labelMedium,
                    color = if (wait != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (wait != null && onDetach != null) {
                Spacer(Modifier.weight(1f))
                val detachLabel = stringResource(R.string.subagent_detach_cd)
                FilledTonalButton(
                    onClick = onDetach,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.height(32.dp).semantics { contentDescription = detachLabel }.testTag("CHAT_SUBAGENT_DETACH"),
                ) {
                    Icon(Icons.Rounded.CallMade, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.subagent_detach), style = InfinityTypography.labelLarge)
                }
            }
        }
        if (wait != null && onDetach != null && waitText != null) {
            Text(
                text = waitText,
                style = InfinityTypography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 22.dp),
            )
        }
        batch.runs.forEach { run ->
            key(run.index) {
                SubagentRunRow(
                    run,
                    now ?: clock,
                    modelName = modelName(run.modelId),
                    onClick = { onOpen(run.index) },
                    onStop = onStop?.takeIf { run.status.isActive && run.id.isNotEmpty() }?.let { stop -> { stop(run) } },
                )
            }
        }
    }
}

@Composable
private fun SubagentRunRow(run: SubagentRun, now: Long, modelName: String, onClick: () -> Unit, onStop: (() -> Unit)? = null) {
    val name = subagentName(run.preset, run.index)
    val openLabel = stringResource(R.string.subagent_open_cd)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toolCardSurface()
            .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick)
            .heightIn(min = InfinitySpacing.MinimumTouchTarget)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
            .testTag("CHAT_SUBAGENT_ROW"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SubagentStatusIcon(run.status)
            Spacer(Modifier.width(8.dp))
            Text(
                text = name,
                style = InfinityTypography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = run.description,
                style = InfinityTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onStop != null) {
                val stopLabel = stringResource(R.string.subagent_stop_cd, name)
                IconButton(onClick = onStop, modifier = Modifier.size(40.dp).testTag("CHAT_SUBAGENT_STOP")) {
                    Icon(Icons.Rounded.StopCircle, contentDescription = stopLabel, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val statusSegments = subagentStatusSegments(run, now)
        val statusColor = if (run.status == SubagentStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        // 单段状态（如失败原因）可能很长，仍按两行省略；带用时的状态分段排版。
        if (statusSegments.size == 1) {
            Text(
                text = statusSegments.single(),
                style = InfinityTypography.labelSmall,
                color = statusColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, end = 8.dp),
            )
        } else {
            InfinityMetaLine(
                segments = statusSegments,
                style = InfinityTypography.labelSmall,
                color = statusColor,
                maxLines = 2,
                modifier = Modifier.padding(start = 24.dp, end = 8.dp),
            )
        }
        SubagentModelTag(subagentModelLabel(run, modelName), Modifier.padding(start = 24.dp, end = 8.dp, top = 2.dp))
        if (run.status == SubagentStatus.COMPLETED) {
            run.result?.let(::subagentResultSummary)?.let { summary ->
                Text(
                    text = summary,
                    style = InfinityTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 24.dp, end = 8.dp),
                )
            }
        }
        if (run.status.isActive) {
            run.liveOutputKey?.let { liveKey ->
                Box(Modifier.padding(start = 24.dp, end = 8.dp, top = 4.dp)) { RunningToolLiveOutput(liveKey) }
            }
        }
    }
}

/** 卡片上的一行结论：跳过 Markdown 标题取第一行正文，全文只有标题时退回标题；去掉列表符号与行内强调标记。 */
internal fun subagentResultSummary(result: String): String? {
    val lines = result.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    val line = lines.firstOrNull { !it.startsWith("#") } ?: lines.firstOrNull() ?: return null
    return line.trimStart('#', ' ')
        .replace(LIST_MARKER, "")
        .replace(INLINE_MARKUP, "")
        .trim()
        .takeIf(String::isNotEmpty)
}

private val LIST_MARKER = Regex("""^([-*+]|\d+[.)])\s+""")
private val INLINE_MARKUP = Regex("""\*\*|__|`|~~""")

/** 子代理实际使用的模型；由智能体在任务里指定时加注“智能体指定”。 */
@Composable
internal fun subagentModelLabel(run: SubagentRun, modelName: String): String =
    if (run.modelChosenByAgent) stringResource(R.string.subagent_model_chosen_by_agent, modelName) else modelName

/** 卡片上的模型标签：始终显示，次要容器色的小圆角块。 */
@Composable
private fun SubagentModelTag(label: String, modifier: Modifier = Modifier) {
    if (label.isBlank()) return
    Text(
        text = label,
        style = InfinityTypography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag("CHAT_SUBAGENT_MODEL"),
    )
}

@Composable
private fun SubagentStatusIcon(status: SubagentStatus) {
    when (status) {
        SubagentStatus.RUNNING, SubagentStatus.WAITING_APPROVAL, SubagentStatus.WRAPPING_UP -> CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        SubagentStatus.COMPLETED -> Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        SubagentStatus.QUEUED -> Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        SubagentStatus.STOPPED, SubagentStatus.CANCELLED -> Icon(Icons.Rounded.StopCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Icon(Icons.Rounded.ErrorOutline, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (status == SubagentStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 子 Agent 状态的分段文字：运行与收尾阶段在状态之后另起一段显示用时，交由 [InfinityMetaLine] 排版。 */
@Composable
internal fun subagentStatusSegments(run: SubagentRun, now: Long): List<String> = when (run.status) {
    SubagentStatus.RUNNING -> {
        // 旧记录有固定步数上限，新记录只在主 Agent 设了轮数上限时显示“第 N/M 步”。
        val limit = run.maxTurns ?: run.maxSteps.takeIf { it > 0 }
        listOf(when {
            run.step == 0 -> stringResource(R.string.subagent_status_starting)
            run.currentTool != null && limit != null -> stringResource(R.string.subagent_status_tool, toolFriendlyName(run.currentTool), run.step, limit)
            run.currentTool != null -> stringResource(R.string.subagent_status_tool_open, toolFriendlyName(run.currentTool), run.step)
            limit != null -> stringResource(R.string.subagent_status_thinking, run.step, limit)
            else -> stringResource(R.string.subagent_status_thinking_open, run.step)
        }, subagentElapsed((now - run.startedAt).coerceAtLeast(0)))
    }
    SubagentStatus.WAITING_APPROVAL -> listOf(stringResource(R.string.subagent_status_waiting, toolFriendlyName(run.currentTool)))
    SubagentStatus.COMPLETED -> listOf(stringResource(R.string.subagent_status_done, subagentElapsed(((run.endedAt ?: now) - run.startedAt).coerceAtLeast(0))))
    SubagentStatus.FAILED -> listOf(stringResource(R.string.subagent_status_failed, run.error.orEmpty()))
    SubagentStatus.STOPPED -> listOf(stringResource(R.string.subagent_status_stopped, subagentElapsed(((run.endedAt ?: now) - run.startedAt).coerceAtLeast(0))))
    SubagentStatus.CANCELLED -> listOf(stringResource(R.string.subagent_status_cancelled))
    SubagentStatus.INTERRUPTED -> listOf(stringResource(R.string.subagent_status_interrupted))
    SubagentStatus.QUEUED -> listOf(stringResource(R.string.subagent_status_queued))
    SubagentStatus.WRAPPING_UP -> listOf(
        stringResource(R.string.subagent_status_wrapping_up_step, run.step),
        subagentElapsed((now - run.startedAt).coerceAtLeast(0)),
    )
}

/** 等待倒计时：分:秒。 */
internal fun subagentCountdown(ms: Long): String {
    val seconds = (ms + 999) / 1_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun subagentElapsed(ms: Long): String {
    val seconds = (ms / 1_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return if (seconds < 60) stringResource(R.string.subagent_elapsed_seconds, seconds)
    else stringResource(R.string.subagent_elapsed_minutes, seconds / 60, seconds % 60)
}

/** 子代理运行中的审批卡：标注来源子代理，允许、允许并记住或拒绝该次调用。 */
@Composable
fun SubagentApprovalDialog(
    broker: SubagentApprovalBroker,
    sessionTitle: (String) -> String? = { null },
    /** 正在前台显示的会话；它的审批已在会话流内以卡片呈现，不再弹窗。 */
    inlineSessionId: String? = null,
) {
    val requests by broker.pending.collectAsState()
    val request = requests.firstOrNull { it.sessionId != inlineSessionId } ?: return
    val title = sessionTitle(request.sessionId)?.takeIf(String::isNotBlank)
    val haptics = LocalInfinityHaptics.current
    LaunchedEffect(request.id) { haptics.perform(InfinityHapticEvent.ATTENTION) }
    InfinityConfirmDialog(
        onDismissRequest = {},
        title = stringResource(R.string.subagent_approval_title, subagentName(request.subagentPreset, request.subagentIndex)),
        message = "",
        confirmText = stringResource(R.string.subagent_approval_allow),
        cancelText = stringResource(R.string.subagent_approval_deny),
        onConfirm = {
            haptics.perform(InfinityHapticEvent.CONFIRM)
            broker.resolve(request.id, true)
        },
        onCancel = {
            haptics.perform(InfinityHapticEvent.REJECT)
            broker.resolve(request.id, false)
        },
        confirmButtonModifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget).testTag("SUBAGENT_APPROVAL_ALLOW"),
        cancelButtonModifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget).testTag("SUBAGENT_APPROVAL_DENY"),
        content = { SubagentApprovalContent(request, title) },
    )
}

/** 弹窗正文：先交代来源会话与子代理任务，再给出与会话内审批卡相同的类别条与预览。 */
@Composable
internal fun SubagentApprovalContent(
    request: SubagentApproval,
    sessionTitle: String?,
) {
    Column(
        modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).testTag("SUBAGENT_APPROVAL"),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            sessionTitle?.let { SubagentApprovalSourceRow(stringResource(R.string.subagent_approval_session_label), it) }
            SubagentApprovalSourceRow(stringResource(R.string.subagent_approval_task_label), request.subagentDescription)
        }
        Column(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(14.dp)),
        ) {
            SubagentApprovalBand(request)
            Box(Modifier.padding(12.dp)) { SubagentApprovalBody(request) }
        }
    }
}

@Composable
private fun SubagentApprovalBand(request: SubagentApproval) {
    val target = request.target
    // 命令正文在下方完整展示时，类别条只给执行位置。
    val where = target?.let { ToolExecutionTargets.processScope(it) ?: ToolExecutionTargets.display(it) }
        ?: approvalFixedWriteTarget(request.toolName)
    ApprovalKindBand(
        toolName = request.toolName,
        where = approvalHeaderTarget(request.toolName, where),
        riskLabel = approvalRiskLabel(request.risk.name.lowercase()),
        executionTarget = request.target,
    )
}

@Composable
private fun SubagentApprovalBody(request: SubagentApproval) {
    val highRisk = request.risk == ToolRisk.PROCESS || request.risk == ToolRisk.DELETE
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ApprovalCallTitle(request.toolName, pending = true)
        if (request.preview.isNotBlank()) ApprovalTextBlock(request.preview)
        ApprovalKindDetail(request.toolName, request.target)
        // 命令的访问范围已由类别说明交代。
        if (highRisk && request.target != null && CommandToolClassification.backendFor(request.toolName) == null) {
            ApprovalRiskLine(stringResource(R.string.chat_approval_high_risk_target_warning))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
            Text(
                stringResource(R.string.subagent_approval_timeout_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 会话流内的子代理审批：外层是子代理分组（名称、任务与“等你批准”标签），
 * 内层每项待批准调用一张审批卡，按钮为“拒绝 / 允许”。
 */
@Composable
internal fun SubagentApprovalGroup(
    requests: List<SubagentApproval>,
    onResolve: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val head = requests.firstOrNull() ?: return
    val name = subagentName(head.subagentPreset, head.subagentIndex)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = InfinitySpacing.Small)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp))
            .padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 10.dp)
            .testTag(UiTags.CHAT_SUBAGENT_APPROVAL),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.AccountTree, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                if (head.subagentDescription.isNotBlank()) {
                    Text(
                        head.subagentDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ApprovalTag(stringResource(R.string.subagent_approval_waiting))
        }
        requests.forEach { request ->
            key(request.id) { SubagentApprovalCard(request, onResolve) }
        }
    }
}

@Composable
private fun SubagentApprovalCard(request: SubagentApproval, onResolve: (String, Boolean) -> Unit) {
    var submitted by remember(request.id) { mutableStateOf(false) }
    val tool = approvalToolTitle(request.toolName)
    val risk = approvalRiskLabel(request.risk.name.lowercase())
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().testTag(UiTags.subagentApproval(request.id)),
    ) {
        Column {
            SubagentApprovalBand(request)
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SubagentApprovalBody(request)
                ApprovalActionBar(
                    remainingCount = 0,
                    totalCount = 1,
                    enabled = !submitted,
                    approveA11y = stringResource(R.string.subagent_approval_allow_a11y, tool, risk),
                    declineA11y = stringResource(R.string.subagent_approval_deny_a11y, tool, risk),
                    onApprove = { submitted = true; onResolve(request.id, true) },
                    onDecline = { submitted = true; onResolve(request.id, false) },
                    onApproveAll = null,
                    approveLabel = stringResource(R.string.subagent_approval_allow),
                )
            }
        }
    }
}

@Composable
private fun SubagentApprovalSourceRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 32.dp).padding(top = 1.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

/** 子代理只读查看页：任务、过程时间线与交回结果；没有输入入口。 */
@Composable
fun SubagentViewerScreen(
    batch: SubagentBatch?,
    index: Int,
    modelName: (String) -> String,
    onBack: () -> Unit,
    loading: Boolean = false,
    onStop: ((SubagentRun) -> Unit)? = null,
) {
    val run = batch?.runs?.getOrNull(index)
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (run?.status?.isActive == true) {
        LaunchedEffect(Unit) {
            while (true) {
                clock = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }
    InfinityPageLayout(
        title = run?.let { subagentName(it.preset, it.index) } ?: stringResource(R.string.subagent_group_single),
        onBack = onBack,
        scrollable = false,
        imePadding = false,
    ) {
        if (run == null) {
            if (loading) return@InfinityPageLayout
            Text(stringResource(R.string.subagent_viewer_missing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@InfinityPageLayout
        }
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SubagentViewerContent(run, modelName(run.modelId), now = clock)
        }
        if (onStop != null && run.status.isActive && run.id.isNotEmpty()) SubagentViewerFooter(onStop = { onStop(run) })
    }
}

@Composable
internal fun SubagentViewerContent(run: SubagentRun, modelName: String, now: Long = System.currentTimeMillis()) {
    Row(
        modifier = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal).padding(bottom = InfinitySpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SubagentStatusIcon(run.status)
        InfinityMetaLine(
            segments = subagentViewerStatusSegments(run, now),
            style = MaterialTheme.typography.bodyMedium,
            color = if (run.status == SubagentStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    InfinitySettingsSection(title = stringResource(R.string.subagent_viewer_task_section)) {
        Column(Modifier.padding(InfinitySpacing.Large), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
            Text(run.description, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            ExpandableToolText(text = run.prompt, collapsedLines = 8)
        }
        SubagentFact(stringResource(R.string.subagent_viewer_profile), stringResource(
            if (run.toolProfile == SubagentToolProfile.READ_ONLY) R.string.subagent_profile_read_only else R.string.subagent_profile_inherit,
        ))
        SubagentFact(stringResource(R.string.subagent_viewer_model), subagentModelLabel(run, modelName))
        SubagentFact(
            stringResource(R.string.subagent_viewer_turn_limit),
            (run.maxTurns ?: run.maxSteps.takeIf { it > 0 })?.toString() ?: stringResource(R.string.subagent_viewer_unlimited),
        )
        if (run.totalTokens > 0) {
            val tokens = remember(run.totalTokens) { NumberFormat.getIntegerInstance().format(run.totalTokens) }
            SubagentFact(stringResource(R.string.subagent_viewer_usage), stringResource(R.string.subagent_tokens, tokens) + if (run.tokensEstimated) stringResource(R.string.p0_estimated) else "")
        }
    }
    InfinitySettingsSection(title = stringResource(R.string.subagent_viewer_timeline)) {
        Column(Modifier.padding(InfinitySpacing.Large).testTag("SUBAGENT_TIMELINE"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val items = remember(run.events, run.error) { subagentTimeline(run.events, run.error) }
            if (items.isEmpty()) Text(stringResource(R.string.subagent_viewer_timeline_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            items.forEach { item ->
                when (item) {
                    is SubagentTimelineItem.Narration -> Text(
                        item.text,
                        style = MaterialTheme.typography.bodyMedium.forReading(),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    is SubagentTimelineItem.Tool -> SubagentToolStep(item, running = run.status.isActive)
                    is SubagentTimelineItem.Failure -> ToolErrorBlock(item.text)
                    is SubagentTimelineItem.Intervention -> SubagentInterventionRow(item.event, run.startedAt)
                }
            }
            run.liveOutputKey?.takeIf { run.status.isActive }?.let { RunningToolLiveOutput(it) }
        }
    }
    InfinitySettingsSection(title = stringResource(R.string.subagent_viewer_result)) {
        Box(Modifier.padding(InfinitySpacing.Large).testTag("SUBAGENT_RESULT")) {
            when {
                run.result != null -> SelectionContainer { MarkdownText(markdown = run.result, isStreaming = false, modifier = Modifier.fillMaxWidth()) }
                run.error != null -> ToolErrorBlock(run.error)
                else -> Text(stringResource(R.string.subagent_viewer_no_result), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 查看页顶部状态：失败原因已在结果区完整展示，这里只给状态与用时。 */
@Composable
private fun subagentViewerStatusSegments(run: SubagentRun, now: Long): List<String> = when (run.status) {
    SubagentStatus.FAILED -> listOf(stringResource(R.string.subagent_status_failed_elapsed, subagentElapsed(((run.endedAt ?: now) - run.startedAt).coerceAtLeast(0))))
    else -> subagentStatusSegments(run, now)
}

@Composable
private fun SubagentFact(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = InfinitySpacing.MinimumTouchTarget).padding(horizontal = InfinitySpacing.Large),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 时间线中的一次工具调用：动作名与对象在一行，补充参数、审批与结果缩进在下方。 */
@Composable
private fun SubagentToolStep(step: SubagentTimelineItem.Tool, running: Boolean) {
    val failed = step.result?.failed == true
    val waiting = step.result == null && running
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                waiting && step.approval != null -> Icon(Icons.Rounded.Shield, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                waiting -> CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                failed -> Icon(Icons.Rounded.ErrorOutline, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                step.result == null -> Icon(Icons.Rounded.ErrorOutline, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                toolFriendlyName(step.toolName),
                style = InfinityTypography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            step.call.subject?.takeIf { it.fitsInline() }?.let { subject ->
                Text(
                    subject,
                    style = InfinityTypography.labelMedium.copy(fontFamily = InfinityMonospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        Column(Modifier.padding(start = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            step.call.subject?.takeUnless { it.fitsInline() }?.let { ApprovalTextBlock(it) }
            step.call.details?.let { ApprovalTextBlock(it) }
            if (step.approval != null) {
                Text(
                    stringResource(if (waiting) R.string.subagent_event_waiting_approval else R.string.subagent_event_needed_approval),
                    style = InfinityTypography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            step.result?.takeIf { it.text.isNotBlank() }?.let { result ->
                val command = remember(result.text, step.toolName) { if (result.failed) null else subagentCommandResult(step.toolName, result.text) }
                when {
                    result.failed -> ToolErrorBlock(result.text)
                    command != null -> {
                        command.output?.let { ApprovalTextBlock(it) } ?: Text(
                            stringResource(R.string.linux_output_empty),
                            style = InfinityTypography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        command.exitCode?.takeIf { it != 0 }?.let {
                            Text(
                                stringResource(R.string.termux_exit_code, it.toString()),
                                style = InfinityTypography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    else -> ApprovalTextBlock(result.text)
                }
            }
        }
    }
}

/** 查看页底部：子代理运行中时只给一个停止按钮。 */
@Composable
internal fun SubagentViewerFooter(onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = InfinitySpacing.Medium).testTag("SUBAGENT_VIEWER_FOOTER"), horizontalArrangement = Arrangement.End) {
        OutlinedButton(
            onClick = onStop,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            modifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget),
        ) {
            Icon(Icons.Rounded.StopCircle, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.subagent_viewer_stop))
        }
    }
}

/**
 * 时间线上主会话一侧的事件：追加指令、请收尾、继续以“来自主会话”卡片显示正文；
 * 转入后台与停止是中性的分隔行，说明是谁做的。时间为相对子代理开始的用时。
 */
@Composable
private fun SubagentInterventionRow(event: SubagentEvent, startedAt: Long) {
    val elapsed = subagentElapsed((event.at - startedAt).coerceAtLeast(0))
    val fromParent = when (event.kind) {
        SubagentEvent.Kind.PARENT_MESSAGE -> R.string.subagent_event_parent_message
        SubagentEvent.Kind.WRAP_UP -> R.string.subagent_event_wrap_up
        SubagentEvent.Kind.RESUME -> R.string.subagent_event_resume
        else -> null
    }
    if (fromParent != null) {
        val accent = MaterialTheme.colorScheme.primary
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .semantics(mergeDescendants = true) {}
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f), RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp, topStart = 4.dp, bottomStart = 4.dp))
                .testTag("SUBAGENT_EVENT_PARENT"),
        ) {
            // 左侧 3dp 主色竖条标出来自主智能体的干预
            Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(stringResource(fromParent, elapsed), style = InfinityTypography.labelMedium, color = accent)
                if (event.text.isNotBlank()) {
                    Text(event.text.trim(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        return
    }
    val label = when (event.kind) {
        SubagentEvent.Kind.DETACH -> when (event.by) {
            SubagentEvent.Actor.USER -> R.string.subagent_event_detach_user
            SubagentEvent.Actor.TIMEOUT -> R.string.subagent_event_detach_timeout
            else -> R.string.subagent_event_detach_parent
        }
        else -> if (event.by == SubagentEvent.Actor.USER) R.string.subagent_event_stop_user else R.string.subagent_event_stop_parent
    }
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("SUBAGENT_EVENT_MARKER"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Icon(
            if (event.kind == SubagentEvent.Kind.DETACH) Icons.Rounded.CallMade else Icons.Rounded.StopCircle,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(label, elapsed),
            style = InfinityTypography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** 查看页时间线的展示单元：模型说明、一次工具调用（含审批与结果）或终止前的错误。 */
internal sealed interface SubagentTimelineItem {
    data class Narration(val text: String) : SubagentTimelineItem
    data class Tool(
        val toolName: String?,
        val call: SubagentCallDisplay,
        val approval: SubagentEvent? = null,
        val result: SubagentEvent? = null,
    ) : SubagentTimelineItem
    data class Failure(val text: String) : SubagentTimelineItem
    /** 主会话的干预或等待变化：追加指令、软停止、硬停止、恢复、转入后台。 */
    data class Intervention(val event: SubagentEvent) : SubagentTimelineItem
}

/** 工具调用的展示形式：最能说明对象的一项参数单独给出，其余参数格式化后作为补充。 */
internal data class SubagentCallDisplay(val subject: String?, val details: String?)

/**
 * 把按时间记录的事件合并为时间线：调用、审批与结果按顺序归入同一步；
 * 与交回结果同文的终止错误只在结果区展示一次。
 */
/** 查看页里命令工具的结果：只取输出正文与退出码，不展示原始 JSON。 */
internal data class SubagentCommandResult(val output: String?, val exitCode: Int?)

/** 不是命令工具或结果不是结构化 JSON 时返回 null，由调用方原样展示。 */
internal fun subagentCommandResult(toolName: String?, text: String): SubagentCommandResult? {
    if (!com.promenar.infinity.ui.linux.output.CommandOutputParser.isCommandTool(toolName)) return null
    if (!text.trimStart().startsWith("{")) return null
    val parsed = runCatching { com.promenar.infinity.ui.linux.output.CommandOutputParser.parse(toolName, null, text, null) }.getOrNull() ?: return null
    val output = listOf(parsed.stdout.raw, parsed.stderr.raw).map(String::trimEnd).filter(String::isNotEmpty).joinToString("\n")
    return SubagentCommandResult(output.ifEmpty { null }, parsed.exitCode)
}

internal fun subagentTimeline(events: List<SubagentEvent>, runError: String?): List<SubagentTimelineItem> {
    val items = mutableListOf<SubagentTimelineItem>()
    var open: Int? = null
    fun attach(event: SubagentEvent, update: (SubagentTimelineItem.Tool) -> SubagentTimelineItem.Tool): Boolean {
        val index = open ?: return false
        val tool = items[index] as SubagentTimelineItem.Tool
        if (tool.result != null || (event.toolName != null && tool.toolName != null && event.toolName != tool.toolName)) return false
        items[index] = update(tool)
        return true
    }
    events.forEachIndexed { position, event ->
        when (event.kind) {
            SubagentEvent.Kind.TEXT -> if (event.text.isNotBlank()) items += SubagentTimelineItem.Narration(event.text.trim())
            SubagentEvent.Kind.TOOL_CALL -> {
                items += SubagentTimelineItem.Tool(event.toolName, subagentCallDisplay(event.text))
                open = items.lastIndex
            }
            SubagentEvent.Kind.APPROVAL -> if (!attach(event) { it.copy(approval = event) }) {
                items += SubagentTimelineItem.Tool(event.toolName, SubagentCallDisplay(null, null), approval = event)
                open = items.lastIndex
            }
            SubagentEvent.Kind.TOOL_RESULT -> {
                if (!attach(event) { it.copy(result = event) }) {
                    items += SubagentTimelineItem.Tool(event.toolName, SubagentCallDisplay(null, null), result = event)
                }
                open = null
            }
            SubagentEvent.Kind.PARENT_MESSAGE, SubagentEvent.Kind.WRAP_UP, SubagentEvent.Kind.STOP,
            SubagentEvent.Kind.RESUME, SubagentEvent.Kind.DETACH -> {
                // 干预发生在调用进行中时，随后到达的结果仍归该调用，不另起一项。
                items += SubagentTimelineItem.Intervention(event)
            }
            SubagentEvent.Kind.ERROR -> {
                val terminal = position == events.lastIndex && event.text == runError
                // 审批超时等错误属于正在进行的调用，作为该步结果展示。
                if (!terminal && !attach(event) { it.copy(result = event.copy(failed = true)) }) {
                    items += SubagentTimelineItem.Failure(event.text)
                } else if (terminal) {
                    open?.let { index ->
                        val tool = items[index] as SubagentTimelineItem.Tool
                        if (tool.result == null) items[index] = tool.copy(result = event.copy(failed = true, text = ""))
                    }
                }
                open = null
            }
        }
    }
    return items
}

internal fun subagentCallDisplay(arguments: String): SubagentCallDisplay {
    val trimmed = arguments.trim()
    if (trimmed.isEmpty() || trimmed == "{}") return SubagentCallDisplay(null, null)
    val json = runCatching { Json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
        ?: return SubagentCallDisplay(null, trimmed)
    val key = SUBJECT_KEYS.firstOrNull { key ->
        (json[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.isNotBlank() == true
    }
    val subject = key?.let { (json.getValue(it) as JsonPrimitive).content.trim() }
    val rest = if (key == null) json else json - key
    // 其余参数逐行写成“名称: 值”，嵌套结构保持紧凑 JSON。
    val details = rest.entries.joinToString("\n") { (name, value) ->
        "$name: " + ((value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: value.toString())
    }.takeIf(String::isNotEmpty)
    return SubagentCallDisplay(subject, details)
}

/** 单行且不长的对象（路径、查询词）跟在动作名后；命令与长文本放进下方代码块完整展示。 */
private fun String.fitsInline(): Boolean = '\n' !in this && length <= INLINE_SUBJECT_CHARS

private const val INLINE_SUBJECT_CHARS = 48

/** 按“最能说明调用对象”排序的参数名。 */
private val SUBJECT_KEYS = listOf("command", "code", "path", "file_path", "query", "url", "pattern", "name", "uri")

