package com.promenar.infinity.ui.chat.components

import com.promenar.infinity.ui.common.LocalInfinityHaptics
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.state.StateBoardSnapshot
import com.promenar.infinity.data.state.StateOperation
import com.promenar.infinity.ui.chat.ChatViewModel
import com.promenar.infinity.ui.chat.TaskPanelStep
import com.promenar.infinity.ui.chat.TaskPanelStepStatus
import com.promenar.infinity.ui.chat.TaskPanelUiState
import com.promenar.infinity.ui.common.InfinityModalSheet
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.theme.InfinitySpacing
import kotlinx.coroutines.delay
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import androidx.compose.animation.AnimatedContent
import com.promenar.infinity.ui.theme.InfinityMotion
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.ui.semantics.contentDescription
import com.promenar.infinity.domain.jobs.BackgroundJob
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.common.InfinityMetaSeparator
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.clearAndSetSemantics

enum class RunDetailTab { PLAN, BACKGROUND, STATE }

/** 详情面板可显示的页：没有内容的页不出现；首选页不可用时退回第一页。 */
internal fun runDetailTabs(hasPlan: Boolean, hasBackground: Boolean, hasState: Boolean): List<RunDetailTab> = buildList {
    if (hasPlan) add(RunDetailTab.PLAN)
    if (hasBackground) add(RunDetailTab.BACKGROUND)
    if (hasState) add(RunDetailTab.STATE)
}

/**
 * 运行详情：计划、后台任务与状态面板分页查看；本次运行的统计挂在对应回复下方，这里不显示。
 * 标题区写清当前进度与用时；隐藏或清除计划收进右上角菜单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RunDetailSheet(
    initialTab: RunDetailTab?,
    plan: TaskPanelUiState?,
    isGenerating: Boolean,
    turnStartedAt: Long?,
    activity: AgentActivityState?,
    stateBoard: StateBoardSnapshot?,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
    onComplete: () -> Unit,
    onHidePlan: () -> Unit,
    onClearPlan: () -> Unit,
    onEditState: suspend (Long, StateOperation) -> String?,
    onRemoveState: suspend (Long) -> String?,
) {
    InfinityModalSheet(onDismissRequest = onDismiss, height = InfinitySheetHeight.Full, sheetGesturesEnabled = false) {
        RunDetailContent(
            initialTab = initialTab,
            plan = plan,
            isGenerating = isGenerating,
            turnStartedAt = turnStartedAt,
            activity = activity,
            stateBoard = stateBoard,
            onDismiss = onDismiss,
            onContinue = onContinue,
            onComplete = onComplete,
            onHidePlan = onHidePlan,
            onClearPlan = onClearPlan,
            onEditState = onEditState,
            onRemoveState = onRemoveState,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

/** 运行详情正文；[nowMillis] 固定时（截图预览）不再按秒刷新用时。 */
@Composable
internal fun RunDetailContent(
    initialTab: RunDetailTab?,
    plan: TaskPanelUiState?,
    isGenerating: Boolean,
    turnStartedAt: Long?,
    activity: AgentActivityState?,
    stateBoard: StateBoardSnapshot?,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
    onComplete: () -> Unit,
    onHidePlan: () -> Unit,
    onClearPlan: () -> Unit,
    onEditState: suspend (Long, StateOperation) -> String?,
    onRemoveState: suspend (Long) -> String?,
    modifier: Modifier = Modifier,
    nowMillis: Long? = null,
) {
    val tabs = runDetailTabs(
        hasPlan = plan != null,
        hasBackground = activity?.isEmpty == false,
        hasState = stateBoard?.board != null,
    )
    var selected by remember { mutableStateOf(initialTab?.takeIf { it in tabs } ?: tabs.firstOrNull()) }
    val tab = selected?.takeIf { it in tabs } ?: tabs.firstOrNull()
    var now by remember { mutableLongStateOf(nowMillis ?: System.currentTimeMillis()) }
    LaunchedEffect(isGenerating, nowMillis) {
        while (isGenerating && nowMillis == null) { now = System.currentTimeMillis(); delay(1_000) }
    }
    Column(modifier.fillMaxWidth().testTag(UiTags.CHAT_RUN_SHEET)) {
        RunDetailHeader(
            plan = plan,
            isGenerating = isGenerating,
            elapsedMillis = turnStartedAt?.takeIf { isGenerating }?.let { (now - it).coerceAtLeast(0) },
            activity = activity,
            onHidePlan = onHidePlan.takeIf { plan != null && !plan.isCompleted },
            onClearPlan = onClearPlan.takeIf { plan?.isCompleted == true && !isGenerating },
        )
        if (tabs.size > 1) {
            RunDetailTabs(
                tabs = tabs,
                selected = tab,
                plan = plan,
                activity = activity,
                onSelect = { selected = it },
            )
        }
        // 计划、后台与状态之间按页签顺序横向过渡，与检视器页签一致。
        AnimatedContent(
            targetState = tab,
            transitionSpec = { InfinityMotion.sharedAxisX(forward = (targetState?.ordinal ?: 0) > (initialState?.ordinal ?: 0)) },
            label = "run_detail_tab",
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { shownTab ->
        Box(Modifier.fillMaxSize()) {
            when (shownTab) {
                RunDetailTab.PLAN -> plan?.let {
                    RunPlanTab(it, isGenerating, onContinue = { onDismiss(); onContinue() }, onComplete = onComplete)
                }
                RunDetailTab.BACKGROUND -> activity?.let { state ->
                    AgentActivityTabContent(state, onOpenLog = { path -> onDismiss(); state.access.onOpenLog(path) }, modifier = Modifier.fillMaxSize())
                }
                RunDetailTab.STATE -> stateBoard?.let { snapshot ->
                    StateBoardContent(
                        snapshot = snapshot,
                        isGenerating = isGenerating,
                        onEdit = onEditState,
                        onRemove = onRemoveState,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                null -> Unit
            }
        }
        }
    }
}

/** 状态卡的状态标签：进行中带呼吸圆点，完成用成功色，暂停用中性色。 */
private enum class RunStatus { RUNNING, DONE, PAUSED }

/**
 * 状态卡，固定两行：
 * - 第一行：状态标签、标题（单行省略）、更多菜单（隐藏或清除计划）；
 * - 第二行：摘要（第 N / M 步 · 后台任务数 · 用时）与同行右侧的细进度条。
 * 没有状态、菜单或进度时对应部分直接省略，不留空位。
 */
@Composable
private fun RunDetailHeader(
    plan: TaskPanelUiState?,
    isGenerating: Boolean,
    elapsedMillis: Long?,
    activity: AgentActivityState?,
    onHidePlan: (() -> Unit)?,
    onClearPlan: (() -> Unit)?,
) {
    val status = when {
        isGenerating -> RunStatus.RUNNING
        plan?.isCompleted == true -> RunStatus.DONE
        plan != null -> RunStatus.PAUSED
        else -> null
    }
    val jobs = activity?.jobs.orEmpty()
    val groups = backgroundJobGroups(jobs)
    val summary = listOfNotNull(
        plan?.let { runPlanStepSummary(it) }?.let {
            when (it) {
                is RunPlanStepSummary.AtStep -> stringResource(R.string.run_detail_step_of, it.position, it.total)
                is RunPlanStepSummary.Finished -> stringResource(R.string.run_card_steps_completed, it.done, it.total)
            }
        },
        groups.running.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.run_detail_jobs_running, it, it) },
        groups.finished.size.takeIf { it > 0 }?.let { stringResource(R.string.run_detail_jobs_finished, it) },
    )
    val progress = plan?.takeIf { it.totalCount > 0 }?.let { it.doneCount.toFloat() / it.totalCount }
    val hasMenu = onHidePlan != null || onClearPlan != null
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp).testTag(UiTags.CHAT_RUN_STATUS_CARD),
    ) {
        // 有菜单时第一行由 48dp 按钮撑高，上下内边距收窄；没有菜单时按文字行高留出对称留白。
        Column(
            Modifier.padding(start = 14.dp, end = if (hasMenu) 4.dp else 14.dp, top = if (hasMenu) 2.dp else 10.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                status?.let { RunStatusPill(it) }
                Text(
                    text = plan?.title?.takeIf(String::isNotBlank) ?: stringResource(R.string.run_detail_title_default),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (hasMenu) RunDetailMenu(onHidePlan, onClearPlan)
            }
            val clock = elapsedMillis?.let { runClockText(it) }
            val spokenClock = elapsedMillis?.let { runElapsedText(it) }
            if (summary.isNotEmpty() || clock != null || progress != null) {
                RunCardMetaRow(
                    summary = summary.joinToString(" $InfinityMetaSeparator ").takeIf(String::isNotEmpty),
                    clock = clock,
                    spokenClock = spokenClock,
                    progress = progress,
                    modifier = Modifier.padding(top = if (hasMenu) 0.dp else 6.dp, end = if (hasMenu) 10.dp else 0.dp),
                )
            }
        }
    }
}

@Composable
private fun RunDetailMenu(onHidePlan: (() -> Unit)?, onClearPlan: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).testTag(UiTags.CHAT_RUN_SHEET_MENU)) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.run_detail_more))
        }
        InfinityDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            onHidePlan?.let { hide ->
                InfinityMenuItem(
                    text = { Text(stringResource(R.string.chat_task_hide)) },
                    onClick = { menu = false; hide() },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.CHAT_TASK_CLOSE),
                )
            }
            onClearPlan?.let { clear ->
                InfinityMenuItem(
                    text = { Text(stringResource(R.string.chat_task_clear_completed)) },
                    onClick = { menu = false; clear() },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.CHAT_TASK_CLOSE),
                )
            }
        }
    }
}

private val RunCardBarGap = 10.dp
private val RunCardBarMinWidth = 32.dp
private val RunCardBarHeight = 3.dp

/**
 * 状态卡第二行：左侧摘要与等宽用时，右侧进度条占满剩余宽度。摘要过长时先省略摘要，
 * 进度条至少保留 [RunCardBarMinWidth]；用时朗读为“用时 N 分 M 秒”，分隔符不朗读。
 */
@Composable
private fun RunCardMetaRow(
    summary: String?,
    clock: String?,
    spokenClock: String?,
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                summary?.let {
                    Text(it, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
                if (summary != null && clock != null) {
                    Text(" $InfinityMetaSeparator ", style = style, color = color, maxLines = 1, softWrap = false, modifier = Modifier.clearAndSetSemantics { })
                }
                clock?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = InfinityMonospace, fontFeatureSettings = "tnum"),
                        color = color,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.semantics { spokenClock?.let { spoken -> contentDescription = spoken } },
                    )
                }
            }
            progress?.let { value ->
                LinearProgressIndicator(
                    progress = { value },
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                )
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val bar = measurables.getOrNull(1)
        val gap = RunCardBarGap.roundToPx()
        val textMax = if (bar != null) (width - gap - RunCardBarMinWidth.roundToPx()).coerceAtLeast(0) else width
        val text = measurables[0].measure(Constraints(maxWidth = textMax))
        val barPlaceable = bar?.measure(
            Constraints.fixed(
                width = (width - (if (text.width > 0) text.width + gap else 0)).coerceAtLeast(0),
                height = RunCardBarHeight.roundToPx(),
            ),
        )
        val height = maxOf(text.height, barPlaceable?.height ?: 0)
        layout(width, height) {
            text.place(0, (height - text.height) / 2)
            barPlaceable?.place(width - barPlaceable.width, (height - barPlaceable.height) / 2)
        }
    }
}

@Composable
private fun RunStatusPill(status: RunStatus) {
    val scheme = MaterialTheme.colorScheme
    val (label, container, content) = when (status) {
        RunStatus.RUNNING -> Triple(stringResource(R.string.run_detail_status_running), scheme.primaryContainer, scheme.onPrimaryContainer)
        RunStatus.DONE -> Triple(stringResource(R.string.run_detail_status_done), MaterialTheme.infinityDomainColors.successContainer, MaterialTheme.infinityDomainColors.onSuccessContainer)
        RunStatus.PAUSED -> Triple(stringResource(R.string.run_detail_status_paused), scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
    }
    val dotAlpha = if (status == RunStatus.RUNNING) {
        val pulse = rememberInfiniteTransition(label = "run_status_pulse")
        pulse.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "run_status_dot",
        ).value
    } else 1f
    Surface(shape = CircleShape, color = container, contentColor = content) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).graphicsLayer { alpha = dotAlpha }.background(content, CircleShape))
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** 状态卡用时：不足一小时为“分:秒”，否则“时:分:秒”。 */
internal fun runClockText(millis: Long): String {
    val total = (millis / 1_000).coerceAtLeast(0)
    val hours = total / 3_600
    val minutes = total % 3_600 / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** 后台任务分组：运行中在前、已结束在后，组内保持原顺序。 */
internal data class BackgroundJobGroups(val running: List<BackgroundJob>, val finished: List<BackgroundJob>)

internal fun backgroundJobGroups(jobs: List<BackgroundJob>): BackgroundJobGroups {
    val (finished, running) = jobs.partition { it.state.isTerminal }
    return BackgroundJobGroups(running, finished)
}

@Composable
private fun RunDetailTabs(
    tabs: List<RunDetailTab>,
    selected: RunDetailTab?,
    plan: TaskPanelUiState?,
    activity: AgentActivityState?,
    onSelect: (RunDetailTab) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(24.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val haptics = LocalInfinityHaptics.current
        tabs.forEach { tab ->
            val active = tab == selected
            val (label, count) = when (tab) {
                RunDetailTab.PLAN -> stringResource(R.string.run_detail_tab_plan) to plan?.let { "${it.doneCount}/${it.totalCount}" }
                RunDetailTab.BACKGROUND -> stringResource(R.string.run_detail_tab_background) to
                    activity?.let { (it.jobs.size + it.schedules.size).takeIf { n -> n > 0 }?.toString() }
                RunDetailTab.STATE -> stringResource(R.string.run_detail_tab_state) to null
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerHighest,
                shadowElevation = if (active) 1.dp else 0.dp,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clickable(role = Role.Tab) { haptics.select(active); onSelect(tab) }
                    .semantics { this.selected = active }
                    .testTag(UiTags.runSheetTab(tab.name)),
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    count?.let {
                        Text(
                            " $it",
                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

// 两个按钮各占一半宽度、等高；窄的检视器与大字号下文字可换成两行，不截断。
private val RunPlanFooterButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

@Composable
private fun ColumnScope.RunPlanFooter(
    plan: TaskPanelUiState,
    isGenerating: Boolean,
    onContinue: () -> Unit,
    onComplete: () -> Unit,
) {
    if (plan.isCompleted) return
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedButton(
            onClick = onComplete,
            enabled = !isGenerating,
            modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp).testTag(UiTags.CHAT_TASK_COMPLETE),
            contentPadding = RunPlanFooterButtonPadding,
        ) { Text(stringResource(R.string.chat_task_mark_completed), textAlign = TextAlign.Center) }
        Button(
            onClick = onContinue,
            enabled = !isGenerating,
            modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp).testTag(UiTags.CHAT_TASK_CONTINUE),
            contentPadding = RunPlanFooterButtonPadding,
        ) { Text(stringResource(R.string.chat_task_continue), textAlign = TextAlign.Center) }
    }
}

@Composable
private fun RunPlanTab(
    plan: TaskPanelUiState,
    isGenerating: Boolean,
    onContinue: () -> Unit,
    onComplete: () -> Unit,
) {
    val groups = remember(plan.steps) { runPlanGroups(plan.steps) }
    // 与状态卡“第 N / M 步”同一来源，避免清单的“当前”与卡片步数各说各话。
    val currentStepId = runPlanCurrentStepId(plan)
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).testTag(UiTags.CHAT_TASK_STEP_LIST),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
        ) {
            groups.forEachIndexed { index, group ->
                group.title?.let { title ->
                    item(key = "group:$index") { RunPlanGroupHeader(title, group) }
                }
                if (!group.collapsed) {
                    items(group.steps.filter { !(it.isGroup && it.depth == 0) }, key = { it.id }) { step ->
                        RunPlanStepRow(step, current = step.id == currentStepId, pulsing = plan.shouldPulse)
                    }
                }
            }
        }
        RunPlanFooter(plan, isGenerating, onContinue, onComplete)
    }
}

@Composable
private fun RunPlanGroupHeader(title: String, group: RunPlanGroup) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (group.collapsed) {
            Text(
                stringResource(R.string.run_detail_group_done, group.doneCount, group.leafCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.infinityDomainColors.success,
            )
        }
    }
}

@Composable
private fun RunPlanStepRow(step: TaskPanelStep, current: Boolean, pulsing: Boolean) {
    val statusLabel = stringResource(
        when (step.status) {
            TaskPanelStepStatus.TODO -> R.string.chat_task_step_todo
            TaskPanelStepStatus.DOING -> R.string.chat_task_step_doing
            TaskPanelStepStatus.DONE -> R.string.chat_task_step_done
            TaskPanelStepStatus.DROPPED -> R.string.chat_task_step_dropped
        },
    )
    val finished = step.status == TaskPanelStepStatus.DONE || step.status == TaskPanelStepStatus.DROPPED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .then(
                if (current) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                else Modifier,
            )
            .padding(start = 10.dp + InfinitySpacing.Medium * (step.depth - 1).coerceAtLeast(0), end = 10.dp)
            .semantics(mergeDescendants = true) { if (!step.isGroup) stateDescription = statusLabel },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            when {
                step.isGroup -> Unit
                step.status == TaskPanelStepStatus.DONE -> Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                step.status == TaskPanelStepStatus.DROPPED -> Icon(Icons.Rounded.Block, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                step.status == TaskPanelStepStatus.DOING || current -> TaskStatusDot(pulsing = pulsing, color = MaterialTheme.colorScheme.primary)
                else -> TaskStatusDot(pulsing = false, color = MaterialTheme.colorScheme.outline)
            }
        }
        Text(
            step.title,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            style = if (step.isGroup) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
            fontWeight = if (current) FontWeight.Medium else null,
            color = if (finished) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (step.status == TaskPanelStepStatus.DROPPED && !step.isGroup) TextDecoration.LineThrough else null,
        )
        if (current) {
            Text(stringResource(R.string.run_detail_current), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun runElapsedText(millis: Long): String {
    val seconds = millis / 1_000
    return if (seconds < 60) stringResource(R.string.run_detail_elapsed_seconds, seconds)
    else stringResource(R.string.run_detail_elapsed_minutes, seconds / 60, seconds % 60)
}
