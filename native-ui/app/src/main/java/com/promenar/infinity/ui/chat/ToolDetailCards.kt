package com.promenar.infinity.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.R
import com.promenar.infinity.domain.jobs.BackgroundJobState
import com.promenar.infinity.ui.chat.components.LocalAgentActivity
import com.promenar.infinity.ui.common.InfinityDisclosureRow
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 会话级计划回放结果：调用步骤 id（`消息id::步骤id`）→ 调用前后的计划树；由会话页按消息列表提供。 */
internal val LocalPlanCallSnapshots = compositionLocalOf<Map<String, PlanCallSnapshot>> { emptyMap() }

private const val PLAN_CARD_TAG = "CHAT_TOOL_PLAN_CARD"
private const val PLAN_RAW_TOGGLE_TAG = "CHAT_TOOL_PLAN_RAW_TOGGLE"
private const val COMMAND_OUTPUT_TAG = "CHAT_TOOL_RESULT"
private const val JOB_STOP_TAG = "CHAT_TOOL_JOB_STOP"
private const val FIELDS_ARGUMENTS_TAG = "CHAT_TOOL_FIELDS_ARGUMENTS"
private const val FIELDS_RESULT_TAG = "CHAT_TOOL_FIELDS_RESULT"

private val InnerShape = RoundedCornerShape(8.dp)
private val TagShape = RoundedCornerShape(8.dp)

@Composable
internal fun toolTextString(text: ToolText): String {
    val args = text.args.map { if (it is ToolText) toolTextString(it) else it }
    return stringResource(text.resId, *args.toTypedArray())
}

/** 计划工具调用的清单模型；不是计划工具、调用失败或没有可知的计划时为 null。 */
@Composable
internal fun rememberPlanCardModel(invocation: ToolInvocationPresentation): PlanCardModel? {
    val name = invocation.name
    val key = invocation.call?.id
    if (name !in PLAN_CARD_TOOLS || key == null || invocation.failed) return null
    val snapshots = LocalPlanCallSnapshots.current
    return remember(invocation, snapshots) {
        val snapshot = snapshots[key]
            ?: standalonePlanSnapshot(key, name, invocation.call?.toolArgs, firstSuccessContent(invocation.results), failed = false)
        snapshot?.let(::planCardModel)
    }
}

/** 查看任务或已结束等待的任务卡模型；结果不是任务状态时为 null。 */
@Composable
internal fun rememberJobCardModel(invocation: ToolInvocationPresentation): JobCardModel? {
    if (!usesJobCard(invocation.name) || !invocation.complete || invocation.failed) return null
    return remember(invocation) { parseJobCard(firstSuccessContent(invocation.results)) }
}

/** 单个计划或任务调用的折叠行摘要；多工具或无法判断变化时为 null，沿用通用摘要。 */
@Composable
internal fun toolCardSummaryText(invocations: List<ToolInvocationPresentation>, jobLabels: Map<String, String>): String? {
    val invocation = invocations.singleOrNull() ?: return null
    if (invocation.name in PLAN_CARD_TOOLS) {
        return rememberPlanCardModel(invocation)?.let(::planSummaryText)?.let { toolTextString(it) }
    }
    val job = rememberJobCardModel(invocation) ?: return null
    val kind = if (invocation.name == "job_wait") CommandStepKind.WAIT else CommandStepKind.STATUS
    val jobId = job.jobId ?: commandStepLabel(invocation.name, invocation.call?.toolArgs, jobLabels)?.jobId
    return toolTextString(jobSummaryText(kind, jobId?.let(jobLabels::get), job.copy(jobId = jobId)))
}

// ─────────────────────────────────────────────────────────────────
//  计划卡
// ─────────────────────────────────────────────────────────────────

@Composable
internal fun PlanToolCard(model: PlanCardModel, arguments: String?, results: List<String>) {
    var showRaw by rememberSaveable { mutableStateOf(false) }
    // 计划卡作为工具卡正文嵌入，容器由 [ToolStepCard] 提供。
    Column(modifier = Modifier.fillMaxWidth().testTag(PLAN_CARD_TAG)) {
        model.rows.forEach { row -> if (row.isGroup) PlanGroupRow(row) else PlanStepRow(row) }
        HorizontalDivider(
            modifier = Modifier.padding(top = InfinitySpacing.XSmall),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (model.allDone) stringResource(R.string.tool_card_plan_all_done, model.total)
                    else stringResource(R.string.tool_card_plan_position, model.position, model.total),
                style = InfinityTypography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            InfinityDisclosureRow(
                text = stringResource(if (showRaw) R.string.tool_card_raw_hide else R.string.tool_card_raw_show),
                expanded = showRaw,
                onClick = { showRaw = !showRaw },
                modifier = Modifier.padding(start = InfinitySpacing.Small).testTag(PLAN_RAW_TOGGLE_TAG),
            )
        }
        AnimatedVisibility(visible = showRaw) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = InfinitySpacing.XSmall)) {
                arguments?.takeIf(String::isNotBlank)?.let { ExpandableToolText(text = it, collapsedLines = 6, arguments = true) }
                results.filter(String::isNotBlank).forEach { ExpandableToolText(text = it, collapsedLines = 12) }
            }
        }
    }
}

@Composable
private fun PlanGroupRow(row: PlanCardRow) {
    Text(
        text = row.title,
        style = InfinityTypography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = (row.depth * 16).dp, top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun PlanStepRow(row: PlanCardRow) {
    val stateText = stringResource(
        when (row.state) {
            PlanRowState.DONE -> R.string.tool_card_plan_state_done
            PlanRowState.CURRENT -> R.string.tool_card_plan_state_current
            PlanRowState.TODO -> R.string.tool_card_plan_state_todo
        },
    )
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(start = (row.depth * 16).dp)
            .semantics(mergeDescendants = true) { stateDescription = stateText },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlanStateMark(row.state)
        Spacer(Modifier.width(10.dp))
        Text(
            text = row.title,
            style = InfinityTypography.bodyMedium.copy(
                fontWeight = if (row.state == PlanRowState.CURRENT) FontWeight.SemiBold else null,
            ),
            color = if (row.state == PlanRowState.DONE) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
        )
        when {
            row.justCompleted -> StateTag(
                stringResource(R.string.tool_card_plan_just_done),
                MaterialTheme.infinityDomainColors.successContainer,
                MaterialTheme.infinityDomainColors.onSuccessContainer,
            )
            row.state == PlanRowState.CURRENT -> StateTag(
                stringResource(R.string.tool_card_plan_current),
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** 完成为实心勾，当前为主色圆环，待办为描边圆环。 */
@Composable
private fun PlanStateMark(state: PlanRowState) {
    val size = 18.dp
    when (state) {
        PlanRowState.DONE -> Box(
            modifier = Modifier.size(size).background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(12.dp))
        }
        PlanRowState.CURRENT -> Box(Modifier.size(size).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape))
        PlanRowState.TODO -> Box(Modifier.size(size).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape))
    }
}

@Composable
private fun StateTag(text: String, container: Color, content: Color) {
    Text(
        text = text,
        style = InfinityTypography.labelSmall,
        color = content,
        maxLines = 1,
        modifier = Modifier.padding(start = InfinitySpacing.Small).background(container, TagShape).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

// ─────────────────────────────────────────────────────────────────
//  后台任务卡
// ─────────────────────────────────────────────────────────────────

/** 命令与后台任务卡片的取值：状态进标题行，耗时、等待上限与退出码进底栏，输出放进限高滚动区。 */
internal data class CommandCardSpec(
    val tone: ToolStepTone,
    val status: String,
    val output: String?,
    val emptyText: String,
    val note: String? = null,
    val footerMeta: String? = null,
    val footerFailed: Boolean = false,
    val onOpenFull: (() -> Unit)? = null,
    /** 任务此刻仍在运行时的停止操作。 */
    val onStop: (() -> Unit)? = null,
)

/**
 * 后台任务卡：查看或等待任务时的状态、日志末尾与耗时。
 * [onOpenFull] 为命令输出页入口；没有时退回打开任务日志文件。[waitLimit] 是等待调用的最长等待说明，跟在耗时后。
 */
@Composable
internal fun rememberJobCardSpec(model: JobCardModel, onOpenFull: (() -> Unit)? = null, waitLimit: String? = null): CommandCardSpec {
    val access = LocalAgentActivity.current
    val jobId = model.jobId
    // 卡片显示调用当时的状态；停止按钮只在任务此刻仍在运行时出现。
    val liveRunning by remember(access, jobId) {
        val jobs = access?.jobs
        if (jobs == null || jobId == null) flowOf(false)
        else jobs.observe(access.sessionId).map { list -> list.any { it.id == jobId && it.state == BackgroundJobState.RUNNING } }.distinctUntilChanged()
    }.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    val failure = jobStateIsFailure(model.state, model.exitCode)
    val openLog = model.logReference?.let { reference -> access?.onOpenLog?.let { open -> { open(reference) } } }
    val elapsed = model.elapsedSeconds?.let { stringResource(R.string.tool_card_run_elapsed, jobElapsedClock(it)) }
    val started = remember(model.startedAtMillis) {
        model.startedAtMillis?.let { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it)) }
    }
    val end = model.exitCode?.let { stringResource(R.string.tool_card_run_exit_code, it.toString()) }
        ?: started?.let { stringResource(R.string.tool_card_run_started, it) }
    return CommandCardSpec(
        tone = when {
            failure -> ToolStepTone.FAILED
            model.running -> ToolStepTone.RUNNING
            model.state == "exited" || model.state == "completed" -> ToolStepTone.SUCCESS
            else -> ToolStepTone.NEUTRAL
        },
        status = toolTextString(jobStateText(model.state, model.exitCode)),
        output = model.logLines.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        emptyText = stringResource(R.string.tool_card_job_log_empty),
        note = if (model.logTruncated && model.logLines.isNotEmpty()) stringResource(R.string.tool_card_job_log_truncated) else null,
        footerMeta = listOfNotNull(elapsed, waitLimit, end).joinToString(" · ").ifEmpty { null }, // wrap-check: allow 单行页脚
        footerFailed = model.exitCode != null && model.exitCode != 0,
        onOpenFull = onOpenFull ?: openLog,
        onStop = if (liveRunning && jobId != null) {{ scope.launch { access?.jobs?.stop(jobId) } }} else null,
    )
}

/** 任务卡底栏的停止按钮。 */
@Composable
internal fun JobStopButton(onStop: () -> Unit) {
    InfinityPillButton(
        text = stringResource(R.string.tool_card_job_stop),
        onClick = onStop,
        tone = InfinityPillTone.DANGER,
        compact = true,
        modifier = Modifier.testTag(JOB_STOP_TAG),
    )
}

internal const val JOB_CARD_TAG = "CHAT_TOOL_JOB_CARD"
internal const val COMMAND_STATUS_TAG = "CHAT_TOOL_COMMAND_STATUS"

/** 命令输出：限高的内嵌滚动区，打开时停在末尾；[note] 为“仅日志末尾”等补充说明。 */
@Composable
internal fun CommandOutputBox(output: String?, emptyText: String, note: String? = null) {
    // 输出在卡内限高滚动，滚到边界后交还会话列表；默认停在末尾，结果通常在最后几行。
    val scroll = rememberScrollState()
    LaunchedEffect(output) {
        val bottom = snapshotFlow { scroll.maxValue }.first { it in 1 until Int.MAX_VALUE }
        scroll.scrollTo(bottom)
    }
    Box(
        Modifier.fillMaxWidth().heightIn(max = CommandOutputMaxHeight).clip(InnerShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest).verticalScroll(scroll)
            .padding(horizontal = 10.dp, vertical = InfinitySpacing.Small),
    ) {
        Text(
            text = output ?: emptyText,
            style = InfinityTypography.bodySmall.copy(fontFamily = if (output != null) InfinityMonospace else null, fontSize = 12.sp, lineHeight = 18.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(COMMAND_OUTPUT_TAG),
        )
    }
    note?.let { Text(it, style = ToolStepMetaStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

private val CommandOutputMaxHeight = 200.dp

// ─────────────────────────────────────────────────────────────────
//  通用字段化展示
// ─────────────────────────────────────────────────────────────────

/** 参数或结果的字段列表；[arguments] 决定标题与测试标签。 */
@Composable
internal fun ToolFieldList(rows: List<ToolFieldRow>, arguments: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, InnerShape)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(if (arguments) FIELDS_ARGUMENTS_TAG else FIELDS_RESULT_TAG),
    ) {
        Text(
            stringResource(if (arguments) R.string.tool_card_fields_arguments else R.string.tool_card_fields_result),
            style = InfinityTypography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        rows.forEach { row -> ToolFieldRowView(row) }
    }
}

@Composable
private fun ToolFieldRowView(row: ToolFieldRow) {
    var expanded by rememberSaveable(row) { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth().padding(start = (row.depth * 12).dp, top = 3.dp, bottom = 3.dp)) {
        Text(
            text = row.key,
            style = InfinityTypography.labelSmall.copy(fontFamily = InfinityMonospace, fontSize = 12.sp, lineHeight = 18.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(0.34f),
        )
        Spacer(Modifier.width(InfinitySpacing.Small))
        if (!row.isHeader) {
            val valueStyle = InfinityTypography.bodySmall.copy(lineHeight = 18.sp)
            if (row.isLong) {
                // 长值下方用折叠行切换展开，点按区域不小于 48dp。
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.value,
                        style = valueStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    InfinityDisclosureRow(
                        text = stringResource(if (expanded) R.string.tool_card_fields_collapse else R.string.tool_card_fields_expand),
                        expanded = expanded,
                        onClick = { expanded = !expanded },
                    )
                }
            } else {
                Text(
                    text = row.value,
                    style = valueStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
