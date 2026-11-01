package com.promenar.infinity.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole

/** 已完成一轮的过程摘要：工具调用次数、思考段数、改动文件数与端到端用时。 */
internal data class TurnProcessSummary(
    val toolStepCount: Int,
    val thinkingCount: Int,
    val changedFileCount: Int,
    val durationMs: Long?,
    /** 内置命令报告的关键数据变化；收起后仍在摘要下方提示。 */
    val runtimeChanges: List<String> = emptyList(),
    /** 长期记忆的写入与删除；收起后仍在摘要下方显示，保留撤销入口。 */
    val memoryChanges: List<MemoryChange> = emptyList(),
)

/** 完成态渲染计划：过程步骤保持原顺序，最终正文单独显示且不在过程里重复。 */
internal data class CollapsedTurnPlan(
    val processSteps: List<PipelineStep>,
    val finalContent: PipelineStep.Content,
    val summary: TurnProcessSummary,
) {
    /** 页脚复制按钮只复制最终正文。 */
    val finalCopyText: String get() = finalContent.content
}

/** 复制全部过程：沿用整轮正文按顺序拼接的规则（不含思考与工具参数、结果）。 */
internal fun CollapsedTurnPlan.allProcessCopyText(): String =
    pipelineReplyCopyText(processSteps + finalContent)

/**
 * 判定一轮回复是否进入完成态并返回收起计划；不满足时返回 null，调用方保持逐步平铺。
 *
 * 完成态要求：开关开启、非生成中、没有待审批、组内没有错误且未被紧随其后的错误截断、最后一条助手消息没有工具调用且正文非空、
 * 该正文是渲染步骤的最后一步、过程里至少有一个思考或工具步骤，且全部工具调用都已返回结果。
 */
internal fun buildCollapsedTurnPlan(
    messages: List<Message>,
    steps: List<PipelineStep>,
    isGenerating: Boolean,
    hasPendingApproval: Boolean,
    enabled: Boolean,
    interruptedByError: Boolean = false,
): CollapsedTurnPlan? {
    if (!enabled || isGenerating || hasPendingApproval || interruptedByError) return null
    if (messages.any { it.isError || it.errorMessage != null }) return null
    if (messages.any { !it.pendingApprovalToolIds.isNullOrEmpty() }) return null
    val finalAssistant = messages.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return null
    if (!finalAssistant.toolCalls.isNullOrEmpty() || finalAssistant.content.isBlank()) return null
    // 同一条消息按“正文 → 工具”排列：最后一条助手消息若还带有已完成的工具步骤，它们排在正文之后，
    // 收起时并入过程；正文之后出现其它步骤（如会话记录）则不是可收起的完成态。
    val finalIndex = steps.indexOfLast { it is PipelineStep.Content }
    val finalContent = steps.getOrNull(finalIndex) as? PipelineStep.Content ?: return null
    if (finalContent.messageId != finalAssistant.id || finalContent.content.isBlank()) return null
    if (steps.drop(finalIndex + 1).any { it !is PipelineStep.ToolExec }) return null

    val processSteps = steps.filterIndexed { index, _ -> index != finalIndex }
    val toolGroups = processSteps.filterIsInstance<PipelineStep.ToolExec>()
    val thinkingCount = processSteps.count { it is PipelineStep.Thinking }
    if (toolGroups.isEmpty() && thinkingCount == 0) return null
    val invocations = toolGroups.flatMap { toolInvocations(it.steps) }
    // 未返回结果的调用说明这一轮被中断或仍在等待，不能当成已完成的过程收起。
    if (invocations.any { !it.complete }) return null

    return CollapsedTurnPlan(
        processSteps = processSteps,
        finalContent = finalContent,
        summary = TurnProcessSummary(
            toolStepCount = invocations.size,
            thinkingCount = thinkingCount,
            changedFileCount = toolGroupFileChanges(invocations).map { it.path }.distinct().size,
            durationMs = summarizeGenerationStats(messages).endToEndMs?.takeIf { it > 0 },
            runtimeChanges = toolGroupRuntimeChanges(invocations),
            memoryChanges = invocations.mapNotNull(::memoryChange),
        ),
    )
}

/** 用时拆成小时、分、秒，交给本地化格式串排版。 */
internal data class TurnDurationParts(val hours: Long, val minutes: Long, val seconds: Long)

internal fun turnDurationParts(durationMs: Long): TurnDurationParts {
    val totalSeconds = ((durationMs + 500) / 1_000).coerceAtLeast(1)
    return TurnDurationParts(
        hours = totalSeconds / 3_600,
        minutes = (totalSeconds % 3_600) / 60,
        seconds = totalSeconds % 60,
    )
}

@Composable
private fun turnDurationText(durationMs: Long): String {
    val parts = turnDurationParts(durationMs)
    return when {
        parts.hours > 0 -> stringResource(R.string.turn_collapse_duration_hours, parts.hours, parts.minutes)
        parts.minutes > 0 -> stringResource(R.string.turn_collapse_duration_minutes, parts.minutes, parts.seconds)
        else -> stringResource(R.string.turn_collapse_duration_seconds, parts.seconds)
    }
}

@Composable
internal fun turnProcessSummarySegments(summary: TurnProcessSummary): List<CharSequence> = buildList {
    if (summary.toolStepCount > 0) {
        add(pluralStringResource(R.plurals.turn_collapse_ran_steps, summary.toolStepCount, summary.toolStepCount))
    } else {
        add(stringResource(R.string.turn_collapse_thought))
    }
    if (summary.changedFileCount > 0) {
        add(pluralStringResource(R.plurals.turn_collapse_changed_files, summary.changedFileCount, summary.changedFileCount))
    }
    summary.durationMs?.let { add(turnDurationText(it)) }
}

/** 完成态的过程摘要行：沿用步骤行样式，点按展开或收起完整时间线。 */
@Composable
internal fun TurnProcessSummaryRow(
    summary: TurnProcessSummary,
    expanded: Boolean,
    fontSize: Int,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompactStepRow(
        segments = turnProcessSummarySegments(summary),
        icon = Icons.Rounded.CheckCircle,
        onClick = onToggle,
        modifier = modifier,
        fontSize = fontSize,
        expanded = expanded,
        state = stringResource(if (expanded) R.string.common_state_expanded else R.string.common_state_collapsed),
        clickLabel = stringResource(if (expanded) R.string.turn_collapse_cd_collapse else R.string.turn_collapse_cd_expand),
        tag = TURN_PROCESS_TOGGLE_TAG,
    )
}

internal const val TURN_PROCESS_TOGGLE_TAG = "CHAT_TURN_PROCESS_TOGGLE"
internal const val TURN_COPY_ALL_PROCESS_TAG = "CHAT_TURN_COPY_ALL_PROCESS"
