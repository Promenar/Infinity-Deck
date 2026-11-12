package com.promenar.infinity.ui.production

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.production.Production
import com.promenar.infinity.data.production.ProductionUserAction
import com.promenar.infinity.data.production.ReviewKind
import com.promenar.infinity.data.production.ReviewTarget
import com.promenar.infinity.data.production.unsentReviews
import com.promenar.infinity.data.production.unsentSuggestions
import com.promenar.infinity.data.production.queuedReviews
import com.promenar.infinity.data.production.verdictOf
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.theme.InfinitySpacing

/* 回合制审核的界面构件：页头“发送”、结论标签、批准/否决/建议三个动作与审核弹层。全界面只用这一组。 */

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

/** 结论标签一行：结论、未发送与未发送的建议。 */
@Composable
internal fun ProductionReviewChips(production: Production, target: ReviewTarget, modifier: Modifier = Modifier, settled: Boolean = false) {
    val chips = production.reviewChips(target, settled)
    if (chips.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { chip -> chip.spec().let { ProductionStatusChip(it.text, it.tone) } }
    }
}

/** 结论的原因与未发送的建议：只读文字。 */
@Composable
internal fun ProductionReviewNotes(production: Production, target: ReviewTarget, modifier: Modifier = Modifier) {
    val reason = production.verdictOf(target)?.takeIf { it.kind == ReviewKind.REJECT }?.note?.takeIf(String::isNotBlank)
    val suggestions = production.unsentSuggestions(target).mapNotNull { it.note?.takeIf(String::isNotBlank) }
    if (reason == null && suggestions.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        reason?.let { ReviewNoteLine(stringResource(R.string.production_review_reason), it) }
        suggestions.forEach { ReviewNoteLine(stringResource(R.string.production_review_suggest), it) }
    }
}

@Composable
private fun ReviewNoteLine(label: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

/**
 * 同一组审核动作：否决、建议、批准。只接受建议的对象只有“建议”。
 * 否决与建议打开同一个文本弹层，否决的原因可以留空；未发送的同种结论再点一次即撤回。
 * [label] 为弹层里写明的对象称呼，按段排版（例如镜头编号与候选序号）。
 */
@Composable
internal fun ProductionReviewActions(
    production: Production,
    target: ReviewTarget,
    label: List<String>,
    busy: Boolean,
    onRun: (ProductionUserAction) -> Unit,
    onSubmit: suspend (ProductionUserAction) -> String?,
    modifier: Modifier = Modifier,
) {
    var dialog by remember(target) { mutableStateOf<ReviewKind?>(null) }
    val verdict = production.verdictOf(target)?.kind
    ProductionActionRow(
        buildList {
            if (target.acceptsVerdict) add(
                ProductionButton(stringResource(R.string.production_review_reject), InfinityPillTone.DANGER, !busy, ProductionUiTags.REVIEW_REJECT, icon = Icons.Rounded.Close.takeIf { verdict == ReviewKind.REJECT }) {
                    val action = production.verdictAction(target, ReviewKind.REJECT)
                    if (action is ProductionUserAction.RetractReview) onRun(action) else dialog = ReviewKind.REJECT
                },
            )
            add(ProductionButton(stringResource(R.string.production_review_suggest), InfinityPillTone.NEUTRAL, !busy, ProductionUiTags.REVIEW_SUGGEST) { dialog = ReviewKind.SUGGEST })
            if (target.acceptsVerdict) add(
                ProductionButton(stringResource(R.string.production_review_approve), InfinityPillTone.PRIMARY, !busy, ProductionUiTags.REVIEW_APPROVE, icon = Icons.Rounded.Check.takeIf { verdict == ReviewKind.APPROVE }) {
                    onRun(production.verdictAction(target, ReviewKind.APPROVE))
                },
            )
        },
        modifier = modifier,
    )
    dialog?.let { kind ->
        val reject = kind == ReviewKind.REJECT
        val word = stringResource(if (reject) R.string.production_review_reject else R.string.production_review_suggest)
        ProductionTextDialog(
            title = word,
            messageSegments = label,
            label = stringResource(if (reject) R.string.production_review_reason else R.string.production_review_content),
            initial = "",
            confirmText = word,
            maxLength = PRODUCTION_NOTE_MAX,
            minLines = 3,
            fieldTag = ProductionUiTags.REASON_FIELD,
            onDismiss = { dialog = null },
            onSubmit = { note -> onSubmit(ProductionUserAction.Review(target, kind, note.ifBlank { null })) },
            allowEmpty = reject,
        )
    }
}

/** 审核内容块：结论标签、原因与建议、三个动作。用于镜头候选、资产版本、剪辑版本等页内对象。 */
@Composable
internal fun ProductionReviewBlock(
    production: Production,
    target: ReviewTarget,
    label: List<String>,
    busy: Boolean,
    onRun: (ProductionUserAction) -> Unit,
    onSubmit: suspend (ProductionUserAction) -> String?,
    modifier: Modifier = Modifier,
    settled: Boolean = false,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
        ProductionReviewChips(production, target, settled = settled)
        ProductionReviewNotes(production, target)
        ProductionReviewActions(production, target, label, busy, onRun, onSubmit)
    }
}

/** 列表里的对象（剧本、场、改动请求）点开后的审核弹层。 */
@Composable
internal fun ProductionReviewSheet(
    title: String,
    production: Production,
    target: ReviewTarget,
    busy: Boolean,
    onRun: (ProductionUserAction) -> Unit,
    onSubmit: suspend (ProductionUserAction) -> String?,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    settled: Boolean = false,
) {
    InfinityBottomSheet(show = true, onDismiss = onDismiss, title = title, scrollContent = true) {
        Column(Modifier.fillMaxWidth().padding(bottom = InfinitySpacing.Small).testTag(ProductionUiTags.REVIEW_SHEET), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            ProductionReviewBlock(production, target, listOf(title), busy, onRun, onSubmit, settled = settled)
        }
    }
}

/** 列表行与场标题上打开审核弹层的图标按钮。 */
@Composable
internal fun ProductionReviewIconButton(enabled: Boolean, modifier: Modifier = Modifier.size(40.dp), onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(Icons.Rounded.RateReview, contentDescription = stringResource(R.string.production_review_actions), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
