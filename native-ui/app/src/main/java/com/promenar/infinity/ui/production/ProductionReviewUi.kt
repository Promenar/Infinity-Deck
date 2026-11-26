package com.promenar.infinity.ui.production

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.production.Production
import com.promenar.infinity.data.production.ProductionUserAction
import com.promenar.infinity.data.production.ReviewKind
import com.promenar.infinity.data.production.ReviewTarget
import com.promenar.infinity.data.production.queuedReviews
import com.promenar.infinity.data.production.unsentReviews
import com.promenar.infinity.data.production.unsentSuggestions
import com.promenar.infinity.data.production.verdictOf
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.network.rememberAnimationsEnabled
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.launch

/* 回合制审核的界面构件：页头“发送”、结论标签、结构化反馈、快捷批准与原地展开内联审批槽。 */

/**
 * 页头“发送”的状态：未发送条数、是否正在发送、是否有一轮正在会话队列里排队，以及点击回调。
 * 由面板提供，各页页头读取。排队的一轮送达或撤回之前不能再发送。
 */
internal data class ProductionSendState(val count: Int, val sending: Boolean, val queued: Boolean = false, val onSend: () -> Unit) {
    val enabled: Boolean get() = count > 0 && !sending && !queued
}

/** 面板内所有页头共用的“发送”；面板以外（加载中、空项目）为 null，页头不画。 */
internal val LocalProductionSend = compositionLocalOf<ProductionSendState?> { null }

/** 本轮未发送的条数：结论与建议都计入。 */
internal fun Production.unsentCount(): Int = unsentReviews().size

@Composable
internal fun ProductionSendButton(state: ProductionSendState, modifier: Modifier = Modifier) {
    InfinityPillButton(
        text = when {
            state.queued -> stringResource(R.string.production_review_queued)
            state.count > 0 -> stringResource(R.string.production_review_send_count, state.count)
            else -> stringResource(R.string.production_review_send)
        },
        onClick = state.onSend,
        tone = InfinityPillTone.PRIMARY,
        enabled = state.enabled,
        compact = true,
        modifier = modifier.testTag(ProductionUiTags.SEND),
    )
}

/** 对象上显示的结论标签。 */
internal enum class ReviewChip { PENDING, APPROVED, REJECTED, UNSENT, QUEUED, SUGGESTION_UNSENT }

/**
 * 对象当前的结论标签：结论（没有结论时为待审）、结论未发送或已排队、有未发送的建议。
 * 只有建议在排队时也标为已排队。
 * [settled] 为对象在审核记录之前已经定稿（例如旧版锁定的剧本），没有结论时显示已批准。
 * 只接受建议的对象（项目、集、镜头）不显示待审。
 */
internal fun Production.reviewChips(target: ReviewTarget, settled: Boolean = false): List<ReviewChip> {
    val verdict = verdictOf(target)
    return buildList {
        when (verdict?.kind) {
            ReviewKind.APPROVE -> add(ReviewChip.APPROVED)
            ReviewKind.REJECT -> add(ReviewChip.REJECTED)
            else -> if (target.acceptsVerdict) add(if (settled) ReviewChip.APPROVED else ReviewChip.PENDING)
        }
        if (verdict != null && verdict.round == null) add(if (verdict.queuedRound != null) ReviewChip.QUEUED else ReviewChip.UNSENT)
        if (unsentSuggestions(target).isNotEmpty()) add(ReviewChip.SUGGESTION_UNSENT)
        else if (ReviewChip.QUEUED !in this && queuedReviews().any { it.target == target && it.kind == ReviewKind.SUGGEST }) add(ReviewChip.QUEUED)
    }
}

/**
 * 点击批准或否决对应的用户动作：同种结论未发送时再点一次即撤回；其余情况（含已排队的结论）记下新结论，
 * 已批准的对象可以直接改为否决，反之亦然。
 */
internal fun Production.verdictAction(target: ReviewTarget, kind: ReviewKind, note: String? = null): ProductionUserAction {
    val current = verdictOf(target)
    return if (current != null && current.kind == kind && current.round == null && current.queuedRound == null) ProductionUserAction.RetractReview(target, kind)
    else ProductionUserAction.Review(target, kind, note)
}

@Composable
internal fun ReviewChip.spec(): ProductionChipSpec = when (this) {
    ReviewChip.PENDING -> ProductionChipSpec(stringResource(R.string.production_review_pending), ProductionTone.WARNING)
    ReviewChip.APPROVED -> ProductionChipSpec(stringResource(R.string.production_review_approved), ProductionTone.SUCCESS)
    ReviewChip.REJECTED -> ProductionChipSpec(stringResource(R.string.production_review_rejected), ProductionTone.ERROR)
    ReviewChip.UNSENT -> ProductionChipSpec(stringResource(R.string.production_review_unsent), ProductionTone.INFO)
    ReviewChip.QUEUED -> ProductionChipSpec(stringResource(R.string.production_review_queued), ProductionTone.INFO)
    ReviewChip.SUGGESTION_UNSENT -> ProductionChipSpec(stringResource(R.string.production_review_suggestion_unsent), ProductionTone.INFO)
}

/** 列表行右侧的单个标签：结论优先，没有结论时显示未发送的建议。 */
@Composable
internal fun Production.reviewChipSpec(target: ReviewTarget, settled: Boolean = false): ProductionChipSpec? {
    val chips = reviewChips(target, settled)
    val primary = chips.firstOrNull() ?: return null
    // 结论未发送或已排队时并进同一个标签，行内只放一个标签。
    return when {
        ReviewChip.UNSENT in chips && primary == ReviewChip.APPROVED -> ProductionChipSpec(stringResource(R.string.production_review_approved_unsent), ProductionTone.SUCCESS)
        ReviewChip.UNSENT in chips && primary == ReviewChip.REJECTED -> ProductionChipSpec(stringResource(R.string.production_review_rejected_unsent), ProductionTone.ERROR)
        ReviewChip.QUEUED in chips && primary == ReviewChip.APPROVED -> ProductionChipSpec(stringResource(R.string.production_review_approved_queued), ProductionTone.SUCCESS)
        ReviewChip.QUEUED in chips && primary == ReviewChip.REJECTED -> ProductionChipSpec(stringResource(R.string.production_review_rejected_queued), ProductionTone.ERROR)
        else -> primary.spec()
    }
}

/** 单个合并态结论标签：结论与未发送/已排队状态并入同一枚徽章。 */
@Composable
internal fun ProductionReviewChips(production: Production, target: ReviewTarget, modifier: Modifier = Modifier, settled: Boolean = false) {
    val spec = production.reviewChipSpec(target, settled) ?: return
    ProductionStatusChip(spec.text, spec.tone, modifier = modifier)
}

/**
 * 结构化审批反馈容器：
 * - 否决原因用浅错误色带边框；
 * - 批准附注用浅成功色带边框；
 * - 待发送建议用中性高表面并配静默弱化“未发送”；
 * - 全 block 随显隐做纵向平滑动画（尊重系统减少动态效果）。
 */
@Composable
internal fun ProductionReviewNotes(production: Production, target: ReviewTarget, modifier: Modifier = Modifier) {
    val verdict = production.verdictOf(target)
    val rejectReason = verdict?.takeIf { it.kind == ReviewKind.REJECT }?.note?.takeIf(String::isNotBlank)
    val approveNote = verdict?.takeIf { it.kind == ReviewKind.APPROVE }?.note?.takeIf(String::isNotBlank)
    val suggestions = production.unsentSuggestions(target).mapNotNull { it.note?.takeIf(String::isNotBlank) }
    val hasContent = rejectReason != null || approveNote != null || suggestions.isNotEmpty()

    var lastRejectReason by remember(target) { mutableStateOf(rejectReason) }
    var lastApproveNote by remember(target) { mutableStateOf(approveNote) }
    var lastSuggestions by remember(target) { mutableStateOf(suggestions) }
    if (hasContent) {
        lastRejectReason = rejectReason
        lastApproveNote = approveNote
        lastSuggestions = suggestions
    }

    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    val enter = if (animate) {
        expandVertically(tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate)) +
            fadeIn(tween(InfinityMotion.DurationShort))
    } else {
        fadeIn(tween(InfinityMotion.DurationShort))
    }
    val exit = if (animate) {
        shrinkVertically(tween(InfinityMotion.DurationShort, easing = InfinityMotion.EmphasizedAccelerate)) +
            fadeOut(tween(InfinityMotion.DurationShort))
    } else {
        fadeOut(tween(InfinityMotion.DurationShort))
    }

    AnimatedVisibility(visible = hasContent, enter = enter, exit = exit, modifier = modifier.fillMaxWidth()) {
        val scheme = MaterialTheme.colorScheme
        val domain = MaterialTheme.infinityDomainColors
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lastRejectReason?.let { text ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = scheme.errorContainer.copy(alpha = 0.24f),
                    border = BorderStroke(0.5.dp, scheme.error.copy(alpha = 0.28f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.production_review_reject_reason),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.error,
                        )
                        Text(text, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
                    }
                }
            }
            lastApproveNote?.let { text ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = domain.successContainer.copy(alpha = 0.28f),
                    border = BorderStroke(0.5.dp, domain.success.copy(alpha = 0.30f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.production_review_approve_note),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = domain.success,
                        )
                        Text(text, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
                    }
                }
            }
            if (lastSuggestions.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = scheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.production_review_pending_suggestion),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                stringResource(R.string.production_review_unsent),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant.copy(alpha = 0.78f),
                            )
                        }
                        lastSuggestions.forEach { text ->
                            Text(text, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

/** 是否展示一键快捷「批准」按钮：可裁决、尚未批准、未定稿且内联审批区收起时展示。 */
internal fun Production.shouldShowQuickApprove(
    target: ReviewTarget,
    settled: Boolean = false,
    inlineOpen: Boolean = false,
): Boolean = target.acceptsVerdict && verdictOf(target)?.kind != ReviewKind.APPROVE && !settled && !inlineOpen

/** 审批表单打开时的默认裁决项。 */
internal fun Production.initialReviewKind(target: ReviewTarget, settled: Boolean = false): ReviewKind {
    val verdict = verdictOf(target)
    val hasUnsentSuggestion = unsentSuggestions(target).isNotEmpty()
    return when {
        !target.acceptsVerdict -> ReviewKind.SUGGEST
        verdict != null -> verdict.kind
        settled -> ReviewKind.APPROVE
        hasUnsentSuggestion -> ReviewKind.SUGGEST
        else -> ReviewKind.APPROVE
    }
}

/** 审批表单在指定裁决项下的默认附注文本。 */
internal fun Production.initialReviewNote(target: ReviewTarget, kind: ReviewKind): String {
    val verdict = verdictOf(target)
    return when (kind) {
        ReviewKind.APPROVE -> verdict?.takeIf { it.kind == ReviewKind.APPROVE }?.note.orEmpty()
        ReviewKind.REJECT -> verdict?.takeIf { it.kind == ReviewKind.REJECT }?.note.orEmpty()
        ReviewKind.SUGGEST -> ""
    }
}

/** 当前表单状态下可直接撤回的未发送项类型。 */
internal fun Production.retractReviewTargetKind(target: ReviewTarget, selectedKind: ReviewKind): ReviewKind? {
    val verdict = verdictOf(target)
    return when {
        verdict != null && verdict.round == null && verdict.queuedRound == null -> verdict.kind
        unsentSuggestions(target).isNotEmpty() && selectedKind == ReviewKind.SUGGEST -> ReviewKind.SUGGEST
        else -> null
    }
}

/**
 * 原地展开内联审批槽：
 * 在当前对象下方平滑展开，提供三段裁决（批准 / 否决 / 建议）、紧凑附注输入框与撤回/收起/保存操作，
 * 避免全屏弹窗暗幕遮挡被审对象正文。
 */
@Composable
internal fun ProductionInlineReviewSlot(
    visible: Boolean,
    production: Production,
    target: ReviewTarget,
    label: List<String>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: suspend (ProductionUserAction) -> String?,
    modifier: Modifier = Modifier,
    settled: Boolean = false,
) {
    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    val enter = if (animate) {
        expandVertically(tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate)) +
            fadeIn(tween(InfinityMotion.DurationShort))
    } else {
        fadeIn(tween(InfinityMotion.DurationShort))
    }
    val exit = if (animate) {
        shrinkVertically(tween(InfinityMotion.DurationShort, easing = InfinityMotion.EmphasizedAccelerate)) +
            fadeOut(tween(InfinityMotion.DurationShort))
    } else {
        fadeOut(tween(InfinityMotion.DurationShort))
    }

    AnimatedVisibility(
        visible = visible,
        enter = enter,
        exit = exit,
        modifier = modifier.fillMaxWidth(),
    ) {
        val verdict = production.verdictOf(target)
        val hasUnsentVerdict = verdict != null && verdict.round == null && verdict.queuedRound == null
        val hasUnsentSuggestion = production.unsentSuggestions(target).isNotEmpty()
        val hasAnyUnsent = hasUnsentVerdict || hasUnsentSuggestion

        var selectedKind by remember(target) { mutableStateOf(production.initialReviewKind(target, settled)) }
        var noteText by remember(target) { mutableStateOf(production.initialReviewNote(target, selectedKind)) }
        var noteEdited by remember(target) { mutableStateOf(false) }
        var error by remember(target) { mutableStateOf<String?>(null) }
        var saving by remember(target) { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(visible, target) {
            if (visible) {
                val initialKind = production.initialReviewKind(target, settled)
                selectedKind = initialKind
                noteText = production.initialReviewNote(target, initialKind)
                noteEdited = false
                error = null
            }
        }

        val scheme = MaterialTheme.colorScheme
        val domain = MaterialTheme.infinityDomainColors
        val fieldLabel = stringResource(
            when (selectedKind) {
                ReviewKind.APPROVE -> R.string.production_review_note_optional_approve
                ReviewKind.REJECT -> R.string.production_review_note_optional_reject
                ReviewKind.SUGGEST -> R.string.production_review_note_required_suggest
            },
        )
        val retractTargetKind = production.retractReviewTargetKind(target, selectedKind)
        val retractLabel = when (retractTargetKind) {
            ReviewKind.APPROVE -> stringResource(R.string.production_review_retract_approve)
            ReviewKind.REJECT -> stringResource(R.string.production_review_retract_reject)
            ReviewKind.SUGGEST -> stringResource(R.string.production_review_retract_suggest)
            null -> null
        }
        val canSave = !saving && (selectedKind != ReviewKind.SUGGEST || noteText.isNotBlank())

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = scheme.surfaceContainerHigh,
            border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.65f)),
            modifier = Modifier.fillMaxWidth().testTag(ProductionUiTags.REVIEW_DIALOG),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (target.acceptsVerdict) {
                    InfinitySegmentedControl(
                        segments = listOf(
                            InfinitySegment(
                                value = ReviewKind.APPROVE,
                                label = stringResource(R.string.production_review_approve),
                                leading = { Box(Modifier.size(7.dp).clip(CircleShape).background(domain.success)) },
                            ),
                            InfinitySegment(
                                value = ReviewKind.REJECT,
                                label = stringResource(R.string.production_review_reject),
                                leading = { Box(Modifier.size(7.dp).clip(CircleShape).background(scheme.error)) },
                            ),
                            InfinitySegment(
                                value = ReviewKind.SUGGEST,
                                label = stringResource(R.string.production_review_suggest),
                                leading = { Box(Modifier.size(7.dp).clip(CircleShape).background(scheme.primary)) },
                            ),
                        ),
                        selected = selectedKind,
                        onSelect = { next ->
                            selectedKind = next
                            error = null
                            if (!noteEdited) {
                                noteText = production.initialReviewNote(target, next)
                            }
                        },
                        enabled = !saving && !busy,
                        fillWidth = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = noteText,
                    onValueChange = {
                        noteText = it.take(PRODUCTION_NOTE_MAX)
                        noteEdited = true
                        error = null
                    },
                    label = { Text(fieldLabel) },
                    placeholder = { Text(stringResource(R.string.production_review_note_placeholder)) },
                    enabled = !saving && !busy,
                    minLines = 2,
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodySmall.forInput(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag(ProductionUiTags.REASON_FIELD),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (hasAnyUnsent) {
                        val activeRetractKind = retractTargetKind
                        val canRetractNow = !saving && !busy && activeRetractKind != null
                        val slotLabel = retractLabel ?: stringResource(R.string.production_review_has_unsent_suggestion)
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val kindToRetract = activeRetractKind ?: return@OutlinedButton
                                    saving = true
                                    scope.launch {
                                        try {
                                            error = onSubmit(ProductionUserAction.RetractReview(target, kindToRetract))
                                            if (error == null) onDismiss()
                                        } finally {
                                            saving = false
                                        }
                                    }
                                },
                                enabled = canRetractNow,
                                shape = CircleShape,
                                border = BorderStroke(
                                    0.8.dp,
                                    if (canRetractNow) scheme.error.copy(alpha = 0.45f) else scheme.outlineVariant.copy(alpha = 0.5f),
                                ),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.error),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.heightIn(min = 34.dp).testTag(ProductionUiTags.REVIEW_RETRACT),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.Undo,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    slotLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    InfinityPillButton(
                        text = stringResource(R.string.production_review_collapse),
                        onClick = onDismiss,
                        tone = InfinityPillTone.NEUTRAL,
                        enabled = !saving,
                        compact = true,
                    )
                    InfinityPillButton(
                        text = stringResource(R.string.production_review_save),
                        onClick = {
                            saving = true
                            scope.launch {
                                try {
                                    val cleanNote = noteText.trim().takeIf { it.isNotEmpty() }
                                    error = onSubmit(ProductionUserAction.Review(target, selectedKind, cleanNote))
                                    if (error == null) onDismiss()
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        tone = InfinityPillTone.PRIMARY,
                        enabled = canSave && !busy,
                        compact = true,
                        modifier = Modifier.testTag(ProductionUiTags.DIALOG_CONFIRM),
                    )
                }
                AnimatedVisibility(
                    visible = error != null,
                    enter = if (animate) InfinityMotion.expandIn else fadeIn(tween(InfinityMotion.DurationShort)),
                    exit = if (animate) InfinityMotion.shrinkOut else fadeOut(tween(InfinityMotion.DurationShort)),
                ) {
                    error?.let { err ->
                        Text(
                            err,
                            color = scheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 统一内联审批操作栏：
 * - 外部托管模式（[externalExpanded] 与 [onExternalExpandedChange] 均非空）：用于场头等紧凑标题行，
 *   只渲染折叠/展开胶囊按钮，内联审批槽由外层紧贴场头下方展示；
 * - 默认自托管模式：未批准时提供一键快捷「批准」与原地展开「审批 / 修改审批 / 收起」按钮，
 *   并在操作栏正下方平滑展开 [ProductionInlineReviewSlot]。
 */
@Composable
internal fun ProductionReviewActions(
    production: Production,
    target: ReviewTarget,
    label: List<String>,
    busy: Boolean,
    onRun: (ProductionUserAction) -> Unit = {},
    onSubmit: suspend (ProductionUserAction) -> String?,
    modifier: Modifier = Modifier,
    settled: Boolean = false,
    compact: Boolean = false,
    statusHint: String? = null,
    externalExpanded: Boolean? = null,
    onExternalExpandedChange: ((Boolean) -> Unit)? = null,
) {
    val verdict = production.verdictOf(target)
    val hasUnsentSuggestion = production.unsentSuggestions(target).isNotEmpty()
    val hasSavedReview = verdict != null || settled || hasUnsentSuggestion

    if (externalExpanded != null && onExternalExpandedChange != null) {
        InfinityPillButton(
            text = stringResource(
                when {
                    externalExpanded -> R.string.production_review_collapse
                    hasSavedReview -> R.string.production_review_edit
                    else -> R.string.production_review_entry
                },
            ),
            onClick = { onExternalExpandedChange(!externalExpanded) },
            tone = if (externalExpanded || hasSavedReview) InfinityPillTone.NEUTRAL else InfinityPillTone.PRIMARY,
            icon = if (externalExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            enabled = !busy,
            compact = true,
            modifier = modifier.testTag(ProductionUiTags.REVIEW_ENTRY),
        )
        return
    }

    var inlineOpen by remember(target) { mutableStateOf(false) }
    var quickSaving by remember(target) { mutableStateOf(false) }
    var quickError by remember(target) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val showQuickApprove = production.shouldShowQuickApprove(target, settled = settled, inlineOpen = inlineOpen)
    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    val quickApproveEnter = if (animate) {
        expandHorizontally(tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate)) +
            fadeIn(tween(InfinityMotion.DurationShort))
    } else {
        fadeIn(tween(InfinityMotion.DurationShort))
    }
    val quickApproveExit = if (animate) {
        shrinkHorizontally(tween(InfinityMotion.DurationShort, easing = InfinityMotion.EmphasizedAccelerate)) +
            fadeOut(tween(InfinityMotion.DurationShort))
    } else {
        fadeOut(tween(InfinityMotion.DurationShort))
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            if (!compact) {
                if (!statusHint.isNullOrBlank()) {
                    Text(
                        text = statusHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
            AnimatedVisibility(
                visible = showQuickApprove,
                enter = quickApproveEnter,
                exit = quickApproveExit,
            ) {
                InfinityPillButton(
                    text = stringResource(R.string.production_review_quick_approve),
                    onClick = {
                        quickSaving = true
                        quickError = null
                        scope.launch {
                            try {
                                quickError = onSubmit(ProductionUserAction.Review(target, ReviewKind.APPROVE, null))
                            } finally {
                                quickSaving = false
                            }
                        }
                    },
                    tone = InfinityPillTone.PRIMARY,
                    icon = Icons.Rounded.Check,
                    enabled = !busy && !quickSaving,
                    compact = true,
                )
            }
            InfinityPillButton(
                text = stringResource(
                    when {
                        inlineOpen -> R.string.production_review_collapse
                        hasSavedReview -> R.string.production_review_edit
                        else -> R.string.production_review_entry
                    },
                ),
                onClick = {
                    quickError = null
                    inlineOpen = !inlineOpen
                },
                tone = when {
                    inlineOpen -> InfinityPillTone.NEUTRAL
                    showQuickApprove || hasSavedReview -> InfinityPillTone.NEUTRAL
                    else -> InfinityPillTone.PRIMARY
                },
                icon = if (inlineOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                enabled = !busy && !quickSaving,
                compact = true,
                modifier = Modifier.testTag(ProductionUiTags.REVIEW_ENTRY),
            )
        }
        AnimatedVisibility(
            visible = quickError != null,
            enter = if (animate) InfinityMotion.expandIn else fadeIn(tween(InfinityMotion.DurationShort)),
            exit = if (animate) InfinityMotion.shrinkOut else fadeOut(tween(InfinityMotion.DurationShort)),
        ) {
            quickError?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        ProductionInlineReviewSlot(
            visible = inlineOpen,
            production = production,
            target = target,
            label = label,
            busy = busy,
            onDismiss = { inlineOpen = false },
            onSubmit = onSubmit,
            settled = settled,
        )
    }
}

/**
 * 统一一步到位审批表单弹窗：
 * 顶部「批准 / 否决 / 建议」三段选择（只接受建议的对象锁定「建议」）+ 直出输入框 + 可选「撤回当前结论」+ 取消/保存。
 */
@Composable
internal fun ProductionUnifiedReviewDialog(
    production: Production,
    target: ReviewTarget,
    label: List<String>,
    settled: Boolean = false,
    onDismiss: () -> Unit,
    onSubmit: suspend (ProductionUserAction) -> String?,
) {
    val verdict = production.verdictOf(target)
    val unsentSuggestions = production.unsentSuggestions(target)
    val hasUnsentVerdict = verdict != null && verdict.round == null && verdict.queuedRound == null
    val hasUnsentSuggestion = unsentSuggestions.isNotEmpty()
    val hasSavedReview = verdict != null || settled || hasUnsentSuggestion

    val initialKind = production.initialReviewKind(target, settled)
    var selectedKind by remember(target) { mutableStateOf(initialKind) }
    var noteText by remember(target) { mutableStateOf(production.initialReviewNote(target, initialKind)) }
    var noteEdited by remember(target) { mutableStateOf(false) }
    var error by remember(target) { mutableStateOf<String?>(null) }
    var saving by remember(target) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val chip = production.reviewChipSpec(target, settled)
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors

    val dialogTitle = stringResource(if (hasSavedReview) R.string.production_review_edit else R.string.production_review_entry)
    val fieldLabel = stringResource(
        when (selectedKind) {
            ReviewKind.APPROVE -> R.string.production_review_note_optional_approve
            ReviewKind.REJECT -> R.string.production_review_note_optional_reject
            ReviewKind.SUGGEST -> R.string.production_review_note_required_suggest
        },
    )
    val retractTargetKind = production.retractReviewTargetKind(target, selectedKind)
    val retractLabel = when (retractTargetKind) {
        ReviewKind.APPROVE -> stringResource(R.string.production_review_retract_approve)
        ReviewKind.REJECT -> stringResource(R.string.production_review_retract_reject)
        ReviewKind.SUGGEST -> stringResource(R.string.production_review_retract_suggest)
        null -> null
    }
    val canSave = !saving && (selectedKind != ReviewKind.SUGGEST || noteText.isNotBlank())

    InfinityConfirmDialog(
        title = dialogTitle,
        message = "",
        messageSegments = label.takeIf { it.isNotEmpty() },
        confirmText = stringResource(R.string.production_review_save),
        onConfirm = {
            saving = true
            scope.launch {
                try {
                    val cleanNote = noteText.trim().takeIf { it.isNotEmpty() }
                    error = onSubmit(ProductionUserAction.Review(target, selectedKind, cleanNote))
                    if (error == null) onDismiss()
                } finally {
                    saving = false
                }
            }
        },
        onCancel = onDismiss,
        onDismissRequest = { if (!saving) onDismiss() },
        confirmEnabled = canSave,
        cancelEnabled = !saving,
        confirmButtonModifier = Modifier.heightIn(min = 46.dp).testTag(ProductionUiTags.DIALOG_CONFIRM),
        cancelButtonModifier = Modifier.heightIn(min = 46.dp),
        modifier = Modifier.testTag(ProductionUiTags.REVIEW_DIALOG),
        titleTrailing = chip?.let { { ProductionStatusChip(it.text, it.tone) } },
        content = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (target.acceptsVerdict) {
                    InfinitySegmentedControl(
                        segments = listOf(
                            InfinitySegment(
                                value = ReviewKind.APPROVE,
                                label = stringResource(R.string.production_review_approve),
                                leading = { Box(Modifier.size(7.dp).clip(CircleShape).background(domain.success)) },
                            ),
                            InfinitySegment(
                                value = ReviewKind.REJECT,
                                label = stringResource(R.string.production_review_reject),
                                leading = { Box(Modifier.size(7.dp).clip(CircleShape).background(scheme.error)) },
                            ),
                            InfinitySegment(
                                value = ReviewKind.SUGGEST,
                                label = stringResource(R.string.production_review_suggest),
                                leading = { Box(Modifier.size(7.dp).clip(CircleShape).background(scheme.primary)) },
                            ),
                        ),
                        selected = selectedKind,
                        onSelect = { next ->
                            selectedKind = next
                            error = null
                            if (!noteEdited) {
                                noteText = production.initialReviewNote(target, next)
                            }
                        },
                        enabled = !saving,
                        fillWidth = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = noteText,
                    onValueChange = {
                        noteText = it.take(PRODUCTION_NOTE_MAX)
                        noteEdited = true
                        error = null
                    },
                    label = { Text(fieldLabel) },
                    placeholder = { Text(stringResource(R.string.production_review_note_placeholder)) },
                    enabled = !saving,
                    minLines = 3,
                    maxLines = 5,
                    textStyle = LocalTextStyle.current.forInput(),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).testTag(ProductionUiTags.REASON_FIELD),
                )
                val hasAnyUnsent = hasUnsentVerdict || hasUnsentSuggestion
                if (hasAnyUnsent) {
                    val activeRetractKind = retractTargetKind
                    val canRetractNow = !saving && activeRetractKind != null
                    val slotLabel = retractLabel ?: stringResource(R.string.production_review_has_unsent_suggestion)
                    OutlinedButton(
                        onClick = {
                            val kindToRetract = activeRetractKind ?: return@OutlinedButton
                            saving = true
                            scope.launch {
                                try {
                                    error = onSubmit(ProductionUserAction.RetractReview(target, kindToRetract))
                                    if (error == null) onDismiss()
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        enabled = canRetractNow,
                        shape = CircleShape,
                        border = BorderStroke(
                            1.dp,
                            if (canRetractNow) scheme.error.copy(alpha = 0.45f) else scheme.outlineVariant.copy(alpha = 0.5f),
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.error),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag(ProductionUiTags.REVIEW_RETRACT),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(slotLabel, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                AnimatedVisibility(
                    visible = error != null,
                    enter = InfinityMotion.expandIn,
                    exit = InfinityMotion.shrinkOut,
                ) {
                    error?.let { err ->
                        Text(
                            err,
                            color = scheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
    )
}

/** 审核内容块：可选结论标签、平滑结构化原因与建议、单入口审核动作。用于镜头候选、资产版本、剪辑版本等页内对象。 */
@Composable
internal fun ProductionReviewBlock(
    production: Production,
    target: ReviewTarget,
    label: List<String>,
    busy: Boolean,
    onRun: (ProductionUserAction) -> Unit = {},
    onSubmit: suspend (ProductionUserAction) -> String?,
    modifier: Modifier = Modifier,
    settled: Boolean = false,
    showStatus: Boolean = true,
    statusHint: String? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
        if (showStatus) ProductionReviewChips(production, target, settled = settled)
        ProductionReviewNotes(production, target)
        ProductionReviewActions(production, target, label, busy, onRun, onSubmit, settled = settled, statusHint = statusHint)
    }
}

/** 独立页内审核卡片：卡头左标题右唯一状态徽章，内部放反馈与统一入口。 */
@Composable
internal fun ProductionReviewCard(
    title: String,
    production: Production,
    target: ReviewTarget,
    label: List<String>,
    busy: Boolean,
    onRun: (ProductionUserAction) -> Unit = {},
    onSubmit: suspend (ProductionUserAction) -> String?,
    modifier: Modifier = Modifier,
    settled: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val chip = production.reviewChipSpec(target, settled)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                chip?.let { ProductionStatusChip(it.text, it.tone) }
            }
            ProductionReviewBlock(production, target, label, busy, onRun, onSubmit, settled = settled, showStatus = false)
        }
    }
}
