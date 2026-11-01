package com.promenar.infinity.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 会话级计划回放结果：调用步骤 id（`消息id::步骤id`）→ 调用前后的计划树；由会话页按消息列表提供。 */
internal val LocalPlanCallSnapshots = compositionLocalOf<Map<String, PlanCallSnapshot>> { emptyMap() }

private const val PLAN_CARD_TAG = "CHAT_TOOL_PLAN_CARD"
private const val PLAN_RAW_TOGGLE_TAG = "CHAT_TOOL_PLAN_RAW_TOGGLE"
private const val JOB_CARD_TAG = "CHAT_TOOL_JOB_CARD"
private const val JOB_OPEN_LOG_TAG = "CHAT_TOOL_JOB_OPEN_LOG"
private const val JOB_STOP_TAG = "CHAT_TOOL_JOB_STOP"
private const val FIELDS_ARGUMENTS_TAG = "CHAT_TOOL_FIELDS_ARGUMENTS"
private const val FIELDS_RESULT_TAG = "CHAT_TOOL_FIELDS_RESULT"

private val CardShape = RoundedCornerShape(12.dp)
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
    Column(
        modifier = Modifier.fillMaxWidth().cardSurface(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)).padding(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.Small)
            .testTag(PLAN_CARD_TAG),
    ) {
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
            TextButton(
                onClick = { showRaw = !showRaw },
                contentPadding = PaddingValues(horizontal = InfinitySpacing.Small),
                modifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget).testTag(PLAN_RAW_TOGGLE_TAG),
            ) {
                Text(stringResource(if (showRaw) R.string.tool_card_raw_hide else R.string.tool_card_raw_show), style = InfinityTypography.labelLarge)
            }
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

@Composable
internal fun JobToolCard(model: JobCardModel) {
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
    Column(
        modifier = Modifier.fillMaxWidth().cardSurface(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)).padding(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.Medium)
            .testTag(JOB_CARD_TAG),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
            JobStat(
                label = stringResource(R.string.tool_card_job_label_state),
                value = toolTextString(jobStateText(model.state, model.exitCode)),
                color = when {
                    failure -> MaterialTheme.colorScheme.error
                    model.running -> MaterialTheme.infinityDomainColors.success
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
            )
            JobStat(
                label = stringResource(R.string.tool_card_job_label_elapsed),
                value = model.elapsedSeconds?.let(::jobElapsedClock) ?: stringResource(R.string.tool_card_job_value_unknown),
                modifier = Modifier.weight(1f),
            )
            if (model.exitCode != null) {
                JobStat(
                    label = stringResource(R.string.tool_card_job_label_exit_code),
                    value = model.exitCode.toString(),
                    color = if (model.exitCode != 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            } else {
                val started = remember(model.startedAtMillis) {
                    model.startedAtMillis?.let { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it)) }
                }
                JobStat(
                    label = stringResource(R.string.tool_card_job_label_started),
                    value = started ?: stringResource(R.string.tool_card_job_value_unknown),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // 日志末尾：等宽、行数与单行长度都有上限，不在列表内嵌套滚动。
        Column(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLowest, InnerShape)
                .padding(horizontal = 10.dp, vertical = InfinitySpacing.Small),
        ) {
            if (model.logLines.isEmpty()) {
                Text(stringResource(R.string.tool_card_job_log_empty), style = InfinityTypography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                if (model.logTruncated) {
                    Text(
                        stringResource(R.string.tool_card_job_log_truncated),
                        style = InfinityTypography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                model.logLines.forEach { line ->
                    Text(
                        text = line,
                        style = InfinityTypography.bodySmall.copy(fontFamily = InfinityMonospace, fontSize = 12.sp, lineHeight = 18.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val openLog = access?.onOpenLog?.takeIf { model.logReference != null }
        val canStop = liveRunning && jobId != null
        if (openLog != null || canStop) {
            Row(horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall)) {
                if (openLog != null) {
                    TextButton(
                        onClick = { openLog(requireNotNull(model.logReference)) },
                        contentPadding = PaddingValues(horizontal = InfinitySpacing.Small),
                        modifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget).testTag(JOB_OPEN_LOG_TAG),
                    ) { Text(stringResource(R.string.tool_card_job_open_log), style = InfinityTypography.labelLarge) }
                }
                if (canStop) {
                    TextButton(
                        onClick = { scope.launch { access?.jobs?.stop(requireNotNull(jobId)) } },
                        contentPadding = PaddingValues(horizontal = InfinitySpacing.Small),
                        modifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget).testTag(JOB_STOP_TAG),
                    ) {
                        Text(stringResource(R.string.tool_card_job_stop), style = InfinityTypography.labelLarge, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun JobStat(label: String, value: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier = modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = InfinityTypography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(
            value,
            style = InfinityTypography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

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
                // 长值整块可点按展开，点按区域不小于 48dp。
                Column(
                    modifier = Modifier.weight(1f).heightIn(min = InfinitySpacing.MinimumTouchTarget)
                        .clickable(role = Role.Button) { expanded = !expanded },
                ) {
                    Text(
                        text = row.value,
                        style = valueStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(if (expanded) R.string.tool_card_fields_collapse else R.string.tool_card_fields_expand),
                        style = InfinityTypography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp),
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

private fun Modifier.cardSurface(container: Color, outline: Color): Modifier = this
    .background(container, CardShape)
    .border(0.5.dp, outline, CardShape)
