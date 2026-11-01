package com.promenar.infinity.ui.chat.components

import com.promenar.infinity.ui.chat.TaskPanelStep
import com.promenar.infinity.ui.chat.TaskPanelStepStatus
import com.promenar.infinity.ui.chat.TaskPanelUiState

/** 运行坞左侧状态点：运行中脉动、等待审批琥珀色、暂停为空心环、完成为对勾。 */
internal enum class RunDockTone { RUNNING, WAITING, PAUSED, DONE, IDLE }

/** 运行坞主文案的来源；具体文字在界面层按资源取得。 */
internal sealed interface RunDockLabel {
    data class Step(val title: String) : RunDockLabel
    data object Running : RunDockLabel
    data object WaitingApproval : RunDockLabel
    data object WaitingAnswer : RunDockLabel
    data object PlanCompleted : RunDockLabel
    data class Paused(val step: String) : RunDockLabel
    data object BackgroundOnly : RunDockLabel
    data class Board(val title: String) : RunDockLabel
}

/** 点按运行坞整行时的首选页：只有状态面板或只有后台任务时直接打开那一页，其余按详情面板默认页。 */
internal fun runDockDefaultTab(label: RunDockLabel): RunDetailTab? = when (label) {
    is RunDockLabel.Board -> RunDetailTab.STATE
    RunDockLabel.BackgroundOnly -> RunDetailTab.BACKGROUND
    else -> null
}

/**
 * 输入浮岛顶部唯一一行运行信息：计划、生成、审批、后台任务与状态面板合并显示，
 * 清单收进运行详情面板，本次运行统计挂在回复下方。各项都没有时返回 null，浮岛不占位。
 */
internal data class RunDockUiState(
    val tone: RunDockTone,
    val label: RunDockLabel,
    /** 有可见计划时的“已完成/总数”。 */
    val planDone: Int? = null,
    val planTotal: Int? = null,
    /** 生成中已用时长（整分钟）；不足 1 分钟或未在生成时为 null。 */
    val elapsedMinutes: Long? = null,
    val runningJobs: Int = 0,
    val enabledSchedules: Int = 0,
    val hasStateBoard: Boolean = false,
    val pendingApprovals: Int = 0,
) {
    /** 进度线比例；没有计划时不画进度线。 */
    val progress: Float? get() = planTotal?.takeIf { it > 0 }?.let { (planDone ?: 0).toFloat() / it }
}

internal fun runDockState(
    plan: TaskPanelUiState?,
    isGenerating: Boolean,
    pendingApprovals: Int,
    runningJobs: Int,
    enabledSchedules: Int,
    boardTitle: String?,
    elapsedMillis: Long?,
    /** 本轮因提问暂停、等待用户回答。 */
    awaitingAnswer: Boolean = false,
): RunDockUiState? {
    val hasBoard = boardTitle != null
    if (plan == null && !isGenerating && pendingApprovals == 0 && !awaitingAnswer && runningJobs == 0 && enabledSchedules == 0 && !hasBoard) return null
    val (tone, label) = when {
        pendingApprovals > 0 -> RunDockTone.WAITING to RunDockLabel.WaitingApproval
        awaitingAnswer -> RunDockTone.WAITING to RunDockLabel.WaitingAnswer
        isGenerating && plan != null && !plan.isCompleted && plan.currentStepTitle.isNotBlank() ->
            RunDockTone.RUNNING to RunDockLabel.Step(plan.currentStepTitle)
        isGenerating -> RunDockTone.RUNNING to RunDockLabel.Running
        plan != null && plan.isCompleted -> RunDockTone.DONE to RunDockLabel.PlanCompleted
        plan != null -> RunDockTone.PAUSED to RunDockLabel.Paused(plan.currentStepTitle)
        runningJobs > 0 || enabledSchedules > 0 -> RunDockTone.IDLE to RunDockLabel.BackgroundOnly
        else -> RunDockTone.IDLE to RunDockLabel.Board(boardTitle.orEmpty())
    }
    return RunDockUiState(
        tone = tone,
        label = label,
        planDone = plan?.doneCount,
        planTotal = plan?.totalCount,
        elapsedMinutes = elapsedMillis?.takeIf { isGenerating && it >= 60_000L }?.div(60_000L),
        runningJobs = runningJobs,
        enabledSchedules = enabledSchedules,
        hasStateBoard = hasBoard,
        pendingApprovals = pendingApprovals,
    )
}

/** 运行详情的计划分组：顶层父步骤为一组；没有分组的叶子步骤归入无标题组。 */
internal data class RunPlanGroup(
    val title: String?,
    val steps: List<TaskPanelStep>,
) {
    val leafCount: Int get() = steps.count { !it.isGroup }
    val doneCount: Int get() = steps.count { !it.isGroup && it.status in FinishedPanelStatuses }
    /** 全部完成的组只显示组标题与完成数，减少已完成内容占用的高度。 */
    val collapsed: Boolean get() = title != null && leafCount > 0 && doneCount == leafCount
}

private val FinishedPanelStatuses = setOf(TaskPanelStepStatus.DONE, TaskPanelStepStatus.DROPPED)

internal fun runPlanGroups(steps: List<TaskPanelStep>): List<RunPlanGroup> {
    val groups = mutableListOf<RunPlanGroup>()
    var title: String? = null
    var current = mutableListOf<TaskPanelStep>()
    fun flush() {
        if (current.isNotEmpty() || title != null) groups += RunPlanGroup(title, current.toList())
        current = mutableListOf()
    }
    steps.forEach { step ->
        if (step.depth == 0 && step.isGroup) {
            flush()
            title = step.title
        } else if (step.depth == 0) {
            if (title != null) { flush(); title = null }
            current += step
        } else {
            current += step
        }
    }
    flush()
    return groups.filter { it.steps.isNotEmpty() || it.title != null }
}

/**
 * 状态卡的步数口径，与计划页签“已完成/总数”、清单“当前”标记同源：
 * 仍有未完成步骤时，[AtStep.position] 是清单中标“当前”的那一步在全部叶子步骤中的序号（从 1 起），
 * 不用“已完成数 + 1”推算——步骤乱序完成时两者不一致；全部完成或放弃时给出完成数与总数。
 */
internal sealed interface RunPlanStepSummary {
    data class AtStep(val position: Int, val total: Int) : RunPlanStepSummary
    data class Finished(val done: Int, val total: Int) : RunPlanStepSummary
}

/** 清单中标“当前”的步骤：取计划状态里的当前步骤（进行中的叶子优先，否则第一个未完成叶子）；计划全部完成时没有当前步骤。 */
internal fun runPlanCurrentStepId(plan: TaskPanelUiState): String? =
    plan.currentStepId.takeIf { !plan.isCompleted && it.isNotEmpty() }

internal fun runPlanStepSummary(plan: TaskPanelUiState): RunPlanStepSummary? {
    if (plan.totalCount <= 0) return null
    if (plan.isCompleted) return RunPlanStepSummary.Finished(plan.doneCount, plan.totalCount)
    val leaves = plan.steps.filter { !it.isGroup }
    val index = runPlanCurrentStepId(plan)?.let { id -> leaves.indexOfFirst { it.id == id } } ?: -1
    // 只带计数、没有清单的状态退回按完成数推算。
    val position = if (index >= 0) index + 1 else plan.doneCount + 1
    return RunPlanStepSummary.AtStep(position.coerceIn(1, plan.totalCount), plan.totalCount)
}
