package com.promenar.infinity.ui.production

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.data.production.AndroidReviewMessageStrings
import com.promenar.infinity.data.production.Production
import com.promenar.infinity.data.production.ProductionReviewMessage
import com.promenar.infinity.data.production.ProductionSnapshot
import com.promenar.infinity.data.production.ProductionUserAction
import com.promenar.infinity.data.production.unsentReviews
import com.promenar.infinity.domain.mediagen.MediaTask
import com.promenar.infinity.ui.common.InfinityStatusKind
import com.promenar.infinity.ui.common.InfinityStatusStrip
import com.promenar.infinity.ui.theme.InfinitySpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 发送一轮审核的结果。 */
internal sealed interface ReviewSendOutcome {
    /** 消息已开轮或入队，本轮已归档。 */
    data class Sent(val round: Int, val count: Int) : ReviewSendOutcome
    /** 会话暂不能接收，意见保持未发送；[reason] 为原因。 */
    data class Busy(val reason: ReviewDelivery) : ReviewSendOutcome
    /** 没有未发送的意见。 */
    data object Empty : ReviewSendOutcome
    /** 消息已交给会话，但归档本轮失败；[error] 为可显示的原因。 */
    data class Unarchived(val error: String) : ReviewSendOutcome
}

/**
 * 发送一轮审核：按当前快照合成消息交给会话；开轮或入队成功后才归档本轮（轮次加 1）。
 * 归档遇到修订冲突时取最新快照重试；最新快照的轮次已经越过本轮时视为已归档。
 * [current] 每次调用返回面板看到的最新快照，[send] 返回会话是否接收（开轮或入队）或暂不能接收的原因。
 */
internal suspend fun sendReviewRound(
    current: () -> ProductionSnapshot,
    compose: (Production) -> String?,
    send: suspend (String) -> ReviewDelivery,
    apply: suspend (Long, ProductionUserAction) -> String?,
    now: () -> Long = System::currentTimeMillis,
    attempts: Int = 3,
    retryDelayMillis: Long = 150,
): ReviewSendOutcome {
    val production = current().production
    val text = compose(production) ?: return ReviewSendOutcome.Empty
    val round = production.nextReviewRound
    val count = production.unsentReviews().size
    val delivery = send(text)
    if (delivery != ReviewDelivery.ACCEPTED) return ReviewSendOutcome.Busy(delivery)
    val action = ProductionUserAction.MarkReviewSent(round, now())
    var lastError = ""
    repeat(attempts) { attempt ->
        val latest = current()
        if (latest.production.nextReviewRound > round) return ReviewSendOutcome.Sent(round, count)
        lastError = apply(latest.revision, action) ?: return ReviewSendOutcome.Sent(round, count)
        if (attempt < attempts - 1) delay(retryDelayMillis)
    }
    return ReviewSendOutcome.Unarchived(lastError)
}

/** 面板底部的短提示：发送结果与暂不能发送。 */
internal data class ProductionNotice(val kind: InfinityStatusKind, val message: String)

@Composable
internal fun ProductionNoticeStrip(notice: ProductionNotice, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(notice) {
        delay(NOTICE_MILLIS)
        dismiss()
    }
    InfinityStatusStrip(notice.kind, notice.message, onDismiss = onDismiss, modifier = modifier.padding(InfinitySpacing.Large))
}

private const val NOTICE_MILLIS = 3_000L

/**
 * 页头“发送”的状态与动作。宿主没有提供会话发送入口时返回 null，页头不画“发送”。
 * 发送一旦开始就不随面板关闭而中断，避免消息已发出而本轮未归档。
 */
@Composable
internal fun rememberProductionSend(
    snapshot: ProductionSnapshot,
    tasks: List<MediaTask>,
    services: ProductionPanelServices,
    onAction: ProductionActionHandler,
    onNotice: (ProductionNotice) -> Unit,
): ProductionSendState? {
    val sendMessage = services.sendReviewMessage ?: return null
    val context = LocalContext.current
    val strings = remember(context) { AndroidReviewMessageStrings(context) }
    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestTasks by rememberUpdatedState(tasks)
    val latestAction by rememberUpdatedState(onAction)
    val latestNotice by rememberUpdatedState(onNotice)
    val latestSend by rememberUpdatedState(sendMessage)
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val importingText = stringResource(R.string.production_review_busy_importing)
    val approvalText = stringResource(R.string.production_review_busy_approval)
    val unavailableText = stringResource(R.string.production_review_busy)
    val failedText = stringResource(R.string.production_panel_action_failed)
    val resources = context.resources
    return ProductionSendState(count = snapshot.production.unsentCount(), sending = sending) {
        if (!sending) {
            sending = true
            scope.launch {
                try {
                    val outcome = withContext(NonCancellable) {
                        try {
                            sendReviewRound(
                                current = { latestSnapshot },
                                compose = { ProductionReviewMessage.compose(it, latestTasks, strings) },
                                send = { latestSend(it) },
                                apply = { revision, action -> latestAction(revision, action) },
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    }
                    when (outcome) {
                        is ReviewSendOutcome.Sent -> latestNotice(ProductionNotice(InfinityStatusKind.Success, resources.getQuantityString(R.plurals.production_review_sent, outcome.count, outcome.round, outcome.count)))
                        is ReviewSendOutcome.Busy -> latestNotice(ProductionNotice(InfinityStatusKind.Warning, when (outcome.reason) {
                            ReviewDelivery.IMPORTING -> importingText
                            ReviewDelivery.APPROVAL_PENDING -> approvalText
                            else -> unavailableText
                        }))
                        ReviewSendOutcome.Empty -> Unit
                        is ReviewSendOutcome.Unarchived -> latestNotice(ProductionNotice(InfinityStatusKind.Error, resources.getString(R.string.production_review_unarchived, outcome.error.ifBlank { failedText })))
                        null -> latestNotice(ProductionNotice(InfinityStatusKind.Error, failedText))
                    }
                } finally {
                    sending = false
                }
            }
        }
    }
}
