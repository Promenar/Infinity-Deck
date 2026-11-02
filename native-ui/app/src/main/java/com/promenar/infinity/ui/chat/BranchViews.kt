package com.promenar.infinity.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.branch.BranchActivity
import com.promenar.infinity.domain.branch.BranchContextMode
import com.promenar.infinity.domain.branch.BranchControls
import com.promenar.infinity.domain.branch.BranchDecision
import com.promenar.infinity.domain.branch.BranchLimits
import com.promenar.infinity.domain.branch.BranchPlan
import com.promenar.infinity.domain.branch.BranchPlanAnswer
import com.promenar.infinity.domain.branch.BranchWorkMode
import com.promenar.infinity.domain.branch.PendingBranchPlan
import com.promenar.infinity.domain.branch.SessionMessageEntry
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.domain.model.ExecutionModeCodec
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import com.promenar.infinity.ui.common.InfinityHapticEvent
import com.promenar.infinity.ui.common.InfinityDisclosureRow
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.DateFormat
import java.util.Date

/** 当前会话的分支服务与跳转入口；预览与测试中为空，此时分支卡片只显示方案标题。 */
class BranchAccess(
    /** 当前会话：主会话里它是分支的父会话。 */
    val sessionId: String,
    val controls: BranchControls,
    val onOpenSession: (sessionId: String) -> Unit,
    /** 当前会话是分支时的主会话；主会话已删除时为空。 */
    val parentSessionId: String? = null,
    val parentTitle: String? = null,
    /** 当前会话是分支时的方案标题，显示在会话开头的方案入口上。 */
    val branchPlanTitle: String? = null,
)

val LocalBranchAccess = staticCompositionLocalOf<BranchAccess?> { null }

// ── 方案选项（纯函数，界面与测试共用）──

/** 方案卡片上三项可改取值的当前选择。 */
internal data class BranchPlanChoice(
    val workMode: BranchWorkMode,
    val contextMode: BranchContextMode,
    val approvalMode: ExecutionMode,
)

internal fun branchPlanInitialChoice(pending: PendingBranchPlan): BranchPlanChoice = BranchPlanChoice(
    // 方案没有给出 worktree 细节时无法新建，只能共用工作区。
    workMode = if (pending.plan.worktree == null) BranchWorkMode.SHARED else pending.plan.workMode,
    contextMode = pending.plan.contextMode,
    approvalMode = ExecutionModeCodec.parseOrWorkspace(pending.plan.approvalMode.ifBlank { pending.parentApprovalMode }),
)

/** 只有方案带了 worktree 细节时工作方式才能切换。 */
internal fun branchPlanCanToggleWorkMode(plan: BranchPlan): Boolean = plan.worktree != null

internal fun branchPlanAnswerOf(decision: BranchDecision, choice: BranchPlanChoice): BranchPlanAnswer = BranchPlanAnswer(
    decision = decision,
    workMode = choice.workMode,
    contextMode = choice.contextMode,
    approvalMode = ExecutionModeCodec.serialize(choice.approvalMode),
)

private fun BranchWorkMode.toggled() = if (this == BranchWorkMode.SHARED) BranchWorkMode.WORKTREE else BranchWorkMode.SHARED
private fun BranchContextMode.toggled() = if (this == BranchContextMode.FRESH) BranchContextMode.INHERIT else BranchContextMode.FRESH
private fun ExecutionMode.toggled() = if (this == ExecutionMode.WORKSPACE) ExecutionMode.FULL_ACCESS else ExecutionMode.WORKSPACE

// ── 已处理方案 ──

/** 回合中已处理的方案：取方案标题与用户的选择，派发时带分支会话 ID。 */
internal data class BranchPlanResolved(val title: String, val answer: BranchPlanAnswer)

private val branchJson = Json { ignoreUnknownKeys = true }

/** 从 `session_branch` 调用的参数与成功结果还原已处理方案；无法解析时返回 null，回退为普通工具行。 */
internal fun branchPlanResolved(invocation: ToolInvocationPresentation): BranchPlanResolved? {
    if (invocation.name != BranchLimits.TOOL_NAME || invocation.failed || !invocation.complete) return null
    // 只取标题：模型可能省略审批模式等字段，不按完整方案解码。
    val title = invocation.call?.toolArgs
        ?.let { runCatching { branchJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        ?.get("title")?.let { (it as? JsonPrimitive)?.content }
        ?.takeIf(String::isNotBlank)
        ?: return null
    val answer = invocation.results.firstNotNullOfOrNull { result -> result.content?.let(::branchPlanAnswerOf) } ?: return null
    return BranchPlanResolved(title, answer)
}

/** 分支会话持久化的方案 JSON 中的标题。 */
internal fun branchPlanTitleOf(planJson: String): String? = runCatching {
    ((branchJson.parseToJsonElement(planJson) as? JsonObject)?.get("title") as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)
}.getOrNull()

/** 工具结果为 `{"status", "answer", "plan", "message"}`，也兼容直接是 [BranchPlanAnswer] 的结果。 */
internal fun branchPlanAnswerOf(result: String): BranchPlanAnswer? = runCatching {
    val root = branchJson.parseToJsonElement(result) as? JsonObject ?: return null
    branchJson.decodeFromJsonElement(BranchPlanAnswer.serializer(), root["answer"] as? JsonObject ?: root)
}.getOrNull()

/** 已成功的 `session_send` 由会话记录里的“已发给「…」”呈现，不再重复为工具行；失败与进行中的照常显示。 */
internal fun sessionSendHiddenSteps(steps: List<com.promenar.infinity.data.model.ExecutionStep>): Set<String> =
    toolInvocations(steps)
        .filter { it.name == SESSION_SEND_TOOL && it.complete && !it.failed }
        .flatMapTo(mutableSetOf()) { invocation -> invocation.steps.map { it.id } }

private const val SESSION_SEND_TOOL = "session_send"

/** 一组工具步骤中的方案：[resolved] 渲染为分支卡片或选择摘要，[hidden] 是不再显示为工具行的步骤 ID。 */
internal data class BranchPlanSteps(val resolved: List<BranchPlanResolved>, val hidden: Set<String>)

internal fun branchPlanSteps(steps: List<com.promenar.infinity.data.model.ExecutionStep>): BranchPlanSteps {
    val invocations = toolInvocations(steps).filter { it.name == BranchLimits.TOOL_NAME }
    if (invocations.isEmpty()) return BranchPlanSteps(emptyList(), emptySet())
    val resolved = mutableListOf<BranchPlanResolved>()
    val hidden = mutableSetOf<String>()
    invocations.forEach { invocation ->
        val parsed = branchPlanResolved(invocation)
        // 参数不合法等失败保留为工具行；已处理与待处理的不再重复成工具行。
        if (parsed != null || !invocation.complete) invocation.steps.mapTo(hidden) { it.id }
        parsed?.let(resolved::add)
    }
    return BranchPlanSteps(resolved, hidden)
}

// ── 待处理方案卡 ──

/**
 * 待处理的工作方案卡：工作方式、上下文、审批模式三项轻点切换，完整方案可展开；
 * [onResolve] 交回用户的选择与三项最终取值。
 */
@Composable
internal fun BranchPlanCard(
    pending: PendingBranchPlan,
    onResolve: (BranchPlanAnswer) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    initiallyExpanded: Boolean = false,
) {
    val haptics = LocalInfinityHaptics.current
    val plan = pending.plan
    val initial = remember(pending.toolCallId) { branchPlanInitialChoice(pending) }
    var workMode by rememberSaveable(pending.toolCallId) { mutableStateOf(initial.workMode) }
    var contextMode by rememberSaveable(pending.toolCallId) { mutableStateOf(initial.contextMode) }
    var approvalMode by rememberSaveable(pending.toolCallId) { mutableStateOf(initial.approvalMode) }
    var expanded by rememberSaveable(pending.toolCallId) { mutableStateOf(initiallyExpanded) }
    val parentMode = ExecutionModeCodec.parseOrWorkspace(pending.parentApprovalMode)
    val choice = BranchPlanChoice(workMode, contextMode, approvalMode)
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .animateContentSize()
            .testTag("CHAT_BRANCH_PLAN_CARD"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.AccountTree, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.branch_plan_header),
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(plan.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium))
                Text(
                    plan.goal,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column {
                        BranchOptionRow(
                            label = stringResource(R.string.branch_plan_work_mode),
                            value = stringResource(
                                if (workMode == BranchWorkMode.WORKTREE) R.string.branch_work_worktree else R.string.branch_work_shared,
                            ),
                            enabled = enabled && branchPlanCanToggleWorkMode(plan),
                            tag = "CHAT_BRANCH_OPTION_WORK",
                            onToggle = { workMode = workMode.toggled() },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        BranchOptionRow(
                            label = stringResource(R.string.branch_plan_context),
                            value = stringResource(
                                if (contextMode == BranchContextMode.INHERIT) R.string.branch_context_inherit else R.string.branch_context_fresh,
                            ),
                            enabled = enabled,
                            tag = "CHAT_BRANCH_OPTION_CONTEXT",
                            onToggle = { contextMode = contextMode.toggled() },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        val modeName = stringResource(
                            if (approvalMode == ExecutionMode.FULL_ACCESS) R.string.branch_mode_full_access else R.string.branch_mode_workspace,
                        )
                        BranchOptionRow(
                            label = stringResource(R.string.branch_plan_approval),
                            value = if (approvalMode == parentMode) stringResource(R.string.branch_mode_same_as_parent, modeName) else modeName,
                            enabled = enabled,
                            tag = "CHAT_BRANCH_OPTION_APPROVAL",
                            onToggle = { approvalMode = approvalMode.toggled() },
                        )
                    }
                }
                val worktree = plan.worktree
                if (workMode == BranchWorkMode.WORKTREE && worktree != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            worktree.branch,
                            style = InfinityTypography.bodySmall.copy(fontFamily = InfinityMonospace, fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            stringResource(R.string.branch_worktree_line, worktree.base, worktree.directory),
                            style = InfinityTypography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (worktree.reason.isBlank()) stringResource(R.string.branch_worktree_approve_note)
                            else stringResource(R.string.branch_worktree_reason, worktree.reason, stringResource(R.string.branch_worktree_approve_note)),
                            style = InfinityTypography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (approvalMode != parentMode) {
                    Text(
                        stringResource(
                            if (approvalMode == ExecutionMode.WORKSPACE) R.string.branch_mode_differs_stricter else R.string.branch_mode_differs_looser,
                        ),
                        style = InfinityTypography.bodySmall,
                        color = MaterialTheme.infinityDomainColors.warning,
                        modifier = Modifier.testTag("CHAT_BRANCH_MODE_WARNING"),
                    )
                }
                val hasDetails = plan.steps.isNotEmpty() || plan.exclusions.isNotBlank() || plan.communication.isNotBlank()
                if (hasDetails && expanded) BranchPlanDetails(plan)
                if (hasDetails) {
                    InfinityDisclosureRow(
                        text = stringResource(if (expanded) R.string.branch_plan_collapse else R.string.branch_plan_expand),
                        expanded = expanded,
                        onClick = { expanded = !expanded },
                        modifier = Modifier.testTag("CHAT_BRANCH_PLAN_EXPAND"),
                    )
                }
            }
            // 派发是主操作，放最右；窄屏大字号放不下时整组竖排，派发在最上。
            InfinityPillRow(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                spacing = 8.dp,
            ) {
                InfinityPillButton(
                    text = stringResource(R.string.branch_plan_decline),
                    onClick = { onResolve(branchPlanAnswerOf(BranchDecision.DECLINE, choice)) },
                    enabled = enabled,
                    modifier = Modifier.testTag("CHAT_BRANCH_DECLINE"),
                )
                InfinityPillButton(
                    text = stringResource(R.string.branch_plan_here),
                    onClick = { onResolve(branchPlanAnswerOf(BranchDecision.HERE, choice)) },
                    enabled = enabled,
                    modifier = Modifier.testTag("CHAT_BRANCH_HERE"),
                )
                InfinityPillButton(
                    text = stringResource(R.string.branch_plan_dispatch),
                    onClick = {
                        haptics.perform(InfinityHapticEvent.CONFIRM)
                        onResolve(branchPlanAnswerOf(BranchDecision.DISPATCH, choice))
                    },
                    tone = InfinityPillTone.PRIMARY,
                    enabled = enabled,
                    modifier = Modifier.testTag("CHAT_BRANCH_DISPATCH"),
                )
            }
        }
    }
}

/** 方案卡片上的一行可切换选项：左侧名称，右侧当前取值与切换箭头。 */
@Composable
private fun BranchOptionRow(label: String, value: String, enabled: Boolean, tag: String, onToggle: () -> Unit) {
    val haptics = LocalInfinityHaptics.current
    val toggleLabel = stringResource(R.string.branch_option_toggle)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = InfinitySpacing.MinimumTouchTarget)
            .then(
                if (enabled) Modifier.clickable(onClickLabel = toggleLabel, role = Role.Button) {
                    haptics.perform(InfinityHapticEvent.SELECTION)
                    onToggle()
                } else Modifier,
            )
            .semantics(mergeDescendants = true) {}
            .padding(start = 12.dp, end = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(end = 12.dp),
        )
        // 取值占满名称之外的宽度并右对齐，窄屏大字号时换行而不是被挤成几个字。
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
        )
        // 不可切换时仍占位，三行取值保持右对齐。
        Box(Modifier.padding(start = 6.dp).size(18.dp), contentAlignment = Alignment.Center) {
            if (enabled) Icon(Icons.Rounded.UnfoldMore, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BranchPlanDetails(plan: BranchPlan) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("CHAT_BRANCH_PLAN_DETAILS"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (plan.steps.isNotEmpty()) {
            BranchDetailSection(
                stringResource(R.string.branch_plan_steps),
                plan.steps.mapIndexed { index, step -> "${index + 1}. $step" }.joinToString("\n"),
            )
        }
        if (plan.exclusions.isNotBlank()) BranchDetailSection(stringResource(R.string.branch_plan_exclusions), plan.exclusions)
        if (plan.communication.isNotBlank()) BranchDetailSection(stringResource(R.string.branch_plan_communication), plan.communication)
    }
}

@Composable
private fun BranchDetailSection(title: String, body: String) {
    Column {
        Text(title, style = InfinityTypography.bodySmall.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
        Text(body.trim(), style = InfinityTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── 已处理方案与分支卡片 ──

/** 已处理方案：派发的显示分支卡片，在此执行与不需要的显示一行摘要。 */
@Composable
internal fun BranchPlanResolvedView(resolved: BranchPlanResolved, modifier: Modifier = Modifier) {
    val branchId = resolved.answer.branchSessionId
    if (resolved.answer.decision == BranchDecision.DISPATCH && branchId != null) {
        val access = LocalBranchAccess.current
        BranchCard(
            sessionId = branchId,
            fallbackTitle = resolved.title,
            modifier = modifier,
            onOpen = access?.let { { it.onOpenSession(branchId) } },
        )
        return
    }
    val here = resolved.answer.decision == BranchDecision.HERE
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("CHAT_BRANCH_DECISION"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (here) Icons.Rounded.Done else Icons.Rounded.Close,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(if (here) R.string.branch_decision_here else R.string.branch_decision_declined, resolved.title),
            style = InfinityTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 主会话里的分支卡片：读取分支实时状态；分支服务不可用或分支已删除时只显示方案标题。 */
@Composable
internal fun BranchCard(sessionId: String, fallbackTitle: String, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null) {
    val access = LocalBranchAccess.current
    val summary = access?.let {
        val flow = remember(it.sessionId, sessionId) {
            it.controls.observeBranches(it.sessionId).map { branches -> branches.firstOrNull { branch -> branch.sessionId == sessionId } }
        }
        flow.collectAsState(initial = null).value
    }
    BranchCardContent(
        title = summary?.title ?: fallbackTitle,
        activity = summary?.activity,
        completedRounds = summary?.completedRounds ?: 0,
        worktreeBranch = summary?.worktreeBranch,
        modifier = modifier,
        onOpen = onOpen,
    )
}

@Composable
internal fun BranchCardContent(
    title: String,
    activity: BranchActivity?,
    completedRounds: Int,
    worktreeBranch: String?,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
) {
    val openLabel = stringResource(R.string.branch_open_cd)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(if (onOpen != null) Modifier.clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen) else Modifier)
            .heightIn(min = InfinitySpacing.MinimumTouchTarget)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
            .testTag("CHAT_BRANCH_CARD"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BranchActivityIcon(activity)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.branch_label),
                style = InfinityTypography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                title,
                style = InfinityTypography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            worktreeBranch?.let { BranchWorktreeTag(it, Modifier.padding(start = 6.dp)) }
            if (onOpen != null) Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            else Spacer(Modifier.width(8.dp))
        }
        branchActivityText(activity, completedRounds)?.let { status ->
            Text(
                status,
                style = InfinityTypography.labelSmall,
                color = if (activity == BranchActivity.WAITING_INPUT || activity == BranchActivity.WAITING_APPROVAL) {
                    MaterialTheme.infinityDomainColors.warning
                } else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, end = 8.dp),
            )
        }
    }
}

/** worktree 分支名标签，用于分支卡片、首页分组与会话顶栏。 */
@Composable
internal fun BranchWorktreeTag(branch: String, modifier: Modifier = Modifier) {
    Text(
        branch,
        style = InfinityTypography.labelSmall.copy(fontFamily = InfinityMonospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
        modifier = modifier
            .widthIn(max = 160.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun BranchActivityIcon(activity: BranchActivity?) {
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        when (activity) {
            BranchActivity.GENERATING -> CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            BranchActivity.QUEUED -> Icon(Icons.Rounded.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            BranchActivity.WAITING_INPUT -> Icon(Icons.AutoMirrored.Rounded.HelpOutline, null, Modifier.size(16.dp), tint = MaterialTheme.infinityDomainColors.warning)
            BranchActivity.WAITING_APPROVAL -> Icon(Icons.Rounded.VerifiedUser, null, Modifier.size(16.dp), tint = MaterialTheme.infinityDomainColors.warning)
            BranchActivity.ROUND_DONE -> Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            BranchActivity.IDLE, null -> Icon(Icons.Rounded.AccountTree, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun branchActivityText(activity: BranchActivity?, completedRounds: Int): String? = when (activity) {
    BranchActivity.GENERATING -> stringResource(R.string.branch_activity_generating)
    BranchActivity.QUEUED -> stringResource(R.string.branch_activity_queued)
    BranchActivity.WAITING_INPUT -> stringResource(R.string.branch_activity_waiting_input)
    BranchActivity.WAITING_APPROVAL -> stringResource(R.string.branch_activity_waiting_approval)
    BranchActivity.IDLE -> stringResource(R.string.branch_activity_idle)
    BranchActivity.ROUND_DONE -> stringResource(R.string.branch_activity_round_done, completedRounds.coerceAtLeast(1))
    null -> null
}

// ── 会话间消息 ──

/** 会话记录中的会话间消息：收到的显示来源分隔线与正文卡片，发出的显示“已发给「…」”。 */
@Composable
internal fun SessionMessageRow(entry: SessionMessageEntry, modifier: Modifier = Modifier, onOpenPeer: (() -> Unit)? = null) {
    if (entry.direction == SessionMessageEntry.Direction.SENT) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .testTag("CHAT_SESSION_MESSAGE_SENT"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.Send,
                contentDescription = null,
                modifier = Modifier.padding(top = 2.dp).size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.session_message_sent_to, entry.peerTitle),
                    style = InfinityTypography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(entry.text.trim(), style = InfinityTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    val source = when (entry.peerRelation) {
        SessionMessageEntry.PeerRelation.PARENT -> stringResource(R.string.session_message_from_parent)
        SessionMessageEntry.PeerRelation.BRANCH -> stringResource(R.string.session_message_from_branch, entry.peerTitle)
        SessionMessageEntry.PeerRelation.SUBAGENT -> stringResource(R.string.session_message_from_subagent, entry.peerTitle)
    }
    val locale = LocalConfiguration.current.locales[0]
    val time = remember(entry.at, locale) { DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(entry.at)) }
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("CHAT_SESSION_MESSAGE_RECEIVED"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                stringResource(R.string.session_message_source_line, time, source),
                style = InfinityTypography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(4f, fill = false),
            )
            HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp))
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = if (onOpenPeer != null) 2.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(source, style = InfinityTypography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(entry.text.trim(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            if (onOpenPeer != null) {
                InfinityPillButton(
                    text = stringResource(
                        when (entry.peerRelation) {
                            SessionMessageEntry.PeerRelation.PARENT -> R.string.session_message_open_parent
                            SessionMessageEntry.PeerRelation.BRANCH -> R.string.session_message_open_branch
                            SessionMessageEntry.PeerRelation.SUBAGENT -> R.string.session_message_open_subagent
                        },
                    ),
                    onClick = onOpenPeer,
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    compact = true,
                    modifier = Modifier.testTag("CHAT_SESSION_MESSAGE_OPEN"),
                )
            }
        }
    }
}

/** 会话记录条目：发送副本、发给子代理的记录、收到的会话消息，以及自动处理暂停标记。 */
@Composable
internal fun SessionRecordRows(message: com.promenar.infinity.data.model.Message, modifier: Modifier = Modifier) {
    if (message.name == com.promenar.infinity.data.branch.SessionMessageStore.PAUSED_MESSAGE_NAME) {
        BranchAutoWakePausedNotice(modifier)
        return
    }
    val access = LocalBranchAccess.current
    val entries = remember(message.id, message.content) { com.promenar.infinity.data.branch.SessionMessageProtocol.entries(message) }
    Column(modifier.fillMaxWidth()) {
        entries.forEach { entry ->
            // 子代理没有独立会话页，只有主会话与分支可以跳转。
            val open = access?.takeIf { entry.peerRelation != SessionMessageEntry.PeerRelation.SUBAGENT }
                ?.let { target -> { target.onOpenSession(entry.peerId) } }
            SessionMessageRow(entry, onOpenPeer = open)
        }
    }
}

/** 连续被会话消息唤醒达到上限后的提示：之后的消息只写入，等用户回来继续。 */
@Composable
internal fun BranchAutoWakePausedNotice(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(MaterialTheme.infinityDomainColors.warningContainer.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("CHAT_BRANCH_WAKE_PAUSED"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.PauseCircle, contentDescription = null, modifier = Modifier.padding(top = 1.dp).size(16.dp), tint = MaterialTheme.infinityDomainColors.warning)
        Text(
            stringResource(R.string.branch_auto_wake_paused, BranchLimits.MAX_AUTO_WAKES),
            style = InfinityTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 分支会话开头的方案入口：“由「主会话」派发 · 工作方案”，点开回到主会话。 */
@Composable
internal fun BranchOriginCard(parentTitle: String?, goal: String, modifier: Modifier = Modifier, onOpenParent: (() -> Unit)? = null) {
    val openLabel = stringResource(R.string.session_message_open_parent)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (onOpenParent != null) Modifier.clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpenParent) else Modifier)
            .heightIn(min = InfinitySpacing.MinimumTouchTarget)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
            .testTag("CHAT_BRANCH_ORIGIN"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.AccountTree, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(
                if (parentTitle == null) stringResource(R.string.branch_origin_parent_deleted)
                else stringResource(R.string.branch_origin_from, parentTitle),
                style = InfinityTypography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(goal, style = InfinityTypography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onOpenParent != null) Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
