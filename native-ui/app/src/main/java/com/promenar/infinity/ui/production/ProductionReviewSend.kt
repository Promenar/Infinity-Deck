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
import com.promenar.infinity.data.production.ProductionSnapshot
import com.promenar.infinity.data.production.ReviewDispatch
import com.promenar.infinity.data.production.ReviewDispatchResult
import com.promenar.infinity.data.production.queuedReviewRound
import com.promenar.infinity.ui.common.InfinityStatusKind
import com.promenar.infinity.ui.common.InfinityStatusStrip
import com.promenar.infinity.ui.theme.InfinitySpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * 合成消息、标记已排队与会话拒收时的回退都由应用级派发器完成；面板只显示结果。
 * 发送一旦开始就不随面板关闭而中断。
 */
@Composable
internal fun rememberProductionSend(
    snapshot: ProductionSnapshot,
    services: ProductionPanelServices,
    onNotice: (ProductionNotice) -> Unit,
): ProductionSendState? {
    val sendReview = services.sendReview ?: return null
    val context = LocalContext.current
    val latestNotice by rememberUpdatedState(onNotice)
    val latestSend by rememberUpdatedState(sendReview)
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val importingText = stringResource(R.string.production_review_busy_importing)
    val approvalText = stringResource(R.string.production_review_busy_approval)
    val unavailableText = stringResource(R.string.production_review_busy)
    val alreadyQueuedText = stringResource(R.string.production_review_already_queued)
    val failedText = stringResource(R.string.production_panel_action_failed)
    val resources = context.resources
    val production = snapshot.production
    return ProductionSendState(count = production.unsentCount(), sending = sending, queued = production.queuedReviewRound != null) {
        if (!sending) {
            sending = true
            scope.launch {
                try {
                    val dispatch = withContext(NonCancellable) {
                        try { latestSend() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    }
                    reviewNotice(dispatch, resources, importingText, approvalText, unavailableText, alreadyQueuedText, failedText)?.let { latestNotice(it) }
                } finally {
                    sending = false
                }
            }
        }
    }
}

/** 派发结果对应的提示；没有可发送内容时不提示。 */
internal fun reviewNotice(
    dispatch: ReviewDispatch?,
    resources: android.content.res.Resources,
    importingText: String,
    approvalText: String,
    unavailableText: String,
    alreadyQueuedText: String,
    failedText: String,
): ProductionNotice? = when (dispatch?.result) {
    null -> ProductionNotice(InfinityStatusKind.Error, failedText)
    ReviewDispatchResult.STARTED -> ProductionNotice(InfinityStatusKind.Success, resources.getQuantityString(R.plurals.production_review_sent, dispatch.count, dispatch.round, dispatch.count))
    ReviewDispatchResult.QUEUED -> ProductionNotice(InfinityStatusKind.Info, resources.getQuantityString(R.plurals.production_review_queued_round, dispatch.count, dispatch.round, dispatch.count))
    ReviewDispatchResult.EMPTY -> null
    ReviewDispatchResult.ALREADY_QUEUED -> ProductionNotice(InfinityStatusKind.Info, alreadyQueuedText)
    ReviewDispatchResult.IMPORTING -> ProductionNotice(InfinityStatusKind.Warning, importingText)
    ReviewDispatchResult.APPROVAL_PENDING -> ProductionNotice(InfinityStatusKind.Warning, approvalText)
    ReviewDispatchResult.UNAVAILABLE -> ProductionNotice(InfinityStatusKind.Warning, unavailableText)
}
