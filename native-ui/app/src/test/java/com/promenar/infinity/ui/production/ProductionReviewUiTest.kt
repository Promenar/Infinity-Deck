package com.promenar.infinity.ui.production

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.production.Episode
import com.promenar.infinity.data.production.ManifestSync
import com.promenar.infinity.data.production.Production
import com.promenar.infinity.data.production.ProductionKind
import com.promenar.infinity.data.production.ProductionSnapshot
import com.promenar.infinity.data.production.ProductionUserAction
import com.promenar.infinity.data.production.ReviewEntry
import com.promenar.infinity.data.production.ReviewKind
import com.promenar.infinity.data.production.ReviewTarget
import com.promenar.infinity.data.production.Scene
import com.promenar.infinity.data.production.Season
import com.promenar.infinity.data.production.Shot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ProductionReviewUiTest {
    private val take = ReviewTarget.take("S1E01", "SC01-01", "t1")
    private val scene = ReviewTarget.scene("S1E01", "SC01")
    private val shot = ReviewTarget.shot("S1E01", "SC01-01")
    private val production = Production("p", "雾港", ProductionKind.FILM, "productions/p",
        seasons = listOf(Season("S1", 1, episodes = listOf(Episode("S1E01", 1, "集", scenes = listOf(Scene("SC01", 1, "场", shots = listOf(Shot("SC01-01", "回头", durationSeconds = 4.0)))))))),
        createdAt = 1, updatedAt = 1)

    private fun withReviews(vararg entries: ReviewEntry) = production.copy(reviews = entries.toList())

    @Test fun `没有结论时可审对象待审只收建议的对象不显示标签`() {
        assertThat(production.reviewChips(take)).containsExactly(ReviewChip.PENDING)
        assertThat(production.reviewChips(take, settled = true)).containsExactly(ReviewChip.APPROVED)
        assertThat(production.reviewChips(shot)).isEmpty()
    }

    @Test fun `结论未发送叠加未发送标记已发送只留结论`() {
        assertThat(withReviews(ReviewEntry(take, ReviewKind.APPROVE, at = 1)).reviewChips(take)).containsExactly(ReviewChip.APPROVED, ReviewChip.UNSENT).inOrder()
        assertThat(withReviews(ReviewEntry(take, ReviewKind.REJECT, "穿帮", at = 1, round = 1)).reviewChips(take)).containsExactly(ReviewChip.REJECTED)
        // 已发送的否决之后再批准：以最后一条结论为准。
        val changed = withReviews(ReviewEntry(take, ReviewKind.REJECT, at = 1, round = 1), ReviewEntry(take, ReviewKind.APPROVE, at = 2))
        assertThat(changed.reviewChips(take)).containsExactly(ReviewChip.APPROVED, ReviewChip.UNSENT).inOrder()
    }

    @Test fun `未发送的建议单独标出且不顶替结论`() {
        val suggested = withReviews(ReviewEntry(scene, ReviewKind.SUGGEST, "暖一点", at = 1))
        assertThat(suggested.reviewChips(scene)).containsExactly(ReviewChip.PENDING, ReviewChip.SUGGESTION_UNSENT).inOrder()
        assertThat(withReviews(ReviewEntry(shot, ReviewKind.SUGGEST, "慢一点", at = 1)).reviewChips(shot)).containsExactly(ReviewChip.SUGGESTION_UNSENT)
        assertThat(withReviews(ReviewEntry(scene, ReviewKind.SUGGEST, "暖一点", at = 1, round = 1)).reviewChips(scene)).containsExactly(ReviewChip.PENDING)
    }

    @Test fun `再点同种未发送结论即撤回其余情况记新结论`() {
        val unsentApprove = withReviews(ReviewEntry(take, ReviewKind.APPROVE, at = 1))
        assertThat(unsentApprove.verdictAction(take, ReviewKind.APPROVE)).isEqualTo(ProductionUserAction.RetractReview(take, ReviewKind.APPROVE))
        assertThat(unsentApprove.verdictAction(take, ReviewKind.REJECT, "穿帮")).isEqualTo(ProductionUserAction.Review(take, ReviewKind.REJECT, "穿帮"))
        val sentApprove = withReviews(ReviewEntry(take, ReviewKind.APPROVE, at = 1, round = 1))
        assertThat(sentApprove.verdictAction(take, ReviewKind.APPROVE)).isEqualTo(ProductionUserAction.Review(take, ReviewKind.APPROVE, null))
        assertThat(production.verdictAction(take, ReviewKind.REJECT)).isEqualTo(ProductionUserAction.Review(take, ReviewKind.REJECT, null))
    }

    @Test fun `未发送条数计入结论与建议不计已发送`() {
        val p = withReviews(
            ReviewEntry(take, ReviewKind.APPROVE, at = 1),
            ReviewEntry(shot, ReviewKind.SUGGEST, "慢一点", at = 2),
            ReviewEntry(scene, ReviewKind.REJECT, at = 0, round = 1),
        )
        assertThat(p.unsentCount()).isEqualTo(2)
        assertThat(production.unsentCount()).isEqualTo(0)
    }

    @Test fun `发送态只在有未发送且不在发送中时可点`() {
        assertThat(ProductionSendState(2, false) {}.enabled).isTrue()
        assertThat(ProductionSendState(0, false) {}.enabled).isFalse()
        assertThat(ProductionSendState(2, true) {}.enabled).isFalse()
    }

    private val unsent = ProductionSnapshot(withReviews(ReviewEntry(take, ReviewKind.APPROVE, at = 1), ReviewEntry(shot, ReviewKind.SUGGEST, "慢一点", at = 2)), 5, ManifestSync.InSync)

    @Test fun `没有可发送内容时不调用会话`() = runTest {
        var sent = 0
        val outcome = sendReviewRound({ unsent }, compose = { null }, send = { sent++; ReviewDelivery.ACCEPTED }, apply = { _, _ -> error("不归档") })
        assertThat(outcome).isEqualTo(ReviewSendOutcome.Empty)
        assertThat(sent).isEqualTo(0)
    }

    @Test fun `会话不接收时带原因返回且不归档意见保持未发送`() = runTest {
        listOf(ReviewDelivery.IMPORTING, ReviewDelivery.APPROVAL_PENDING, ReviewDelivery.UNAVAILABLE).forEach { reason ->
            var applied = 0
            val outcome = sendReviewRound({ unsent }, compose = { "消息" }, send = { reason }, apply = { _, _ -> applied++; null })
            assertThat(outcome).isEqualTo(ReviewSendOutcome.Busy(reason))
            assertThat(applied).isEqualTo(0)
        }
    }

    @Test fun `会话接收后按当前修订归档本轮`() = runTest {
        val messages = mutableListOf<String>()
        val applied = mutableListOf<Pair<Long, ProductionUserAction>>()
        val outcome = sendReviewRound({ unsent }, compose = { "第 ${it.nextReviewRound} 轮" }, send = { messages += it; ReviewDelivery.ACCEPTED },
            apply = { revision, action -> applied += revision to action; null }, now = { 42 })
        assertThat(outcome).isEqualTo(ReviewSendOutcome.Sent(round = 1, count = 2))
        assertThat(messages).containsExactly("第 1 轮")
        assertThat(applied).containsExactly(5L to ProductionUserAction.MarkReviewSent(1, 42))
    }

    @Test fun `归档遇到修订冲突时取最新快照重试`() = runTest {
        var current = unsent
        val revisions = mutableListOf<Long>()
        val outcome = sendReviewRound({ current }, compose = { "消息" }, send = { ReviewDelivery.ACCEPTED }, apply = { revision, _ ->
            revisions += revision
            if (revisions.size == 1) { current = current.copy(revision = 6); "面板已更新" } else null
        })
        assertThat(outcome).isEqualTo(ReviewSendOutcome.Sent(1, 2))
        assertThat(revisions).containsExactly(5L, 6L).inOrder()
    }

    @Test fun `最新快照已越过本轮视为已归档`() = runTest {
        var current = unsent
        var applied = 0
        val outcome = sendReviewRound({ current }, compose = { "消息" }, send = { ReviewDelivery.ACCEPTED }, apply = { _, _ ->
            applied++
            current = current.copy(production = current.production.copy(nextReviewRound = 2), revision = 7)
            "面板已更新"
        })
        assertThat(outcome).isEqualTo(ReviewSendOutcome.Sent(1, 2))
        assertThat(applied).isEqualTo(1)
    }

    @Test fun `归档始终失败时报告最后一个原因且消息不重发`() = runTest {
        var sent = 0
        var applied = 0
        val outcome = sendReviewRound({ unsent }, compose = { "消息" }, send = { sent++; ReviewDelivery.ACCEPTED }, apply = { _, _ -> applied++; "写入失败 $applied" })
        assertThat(outcome).isEqualTo(ReviewSendOutcome.Unarchived("写入失败 3"))
        assertThat(sent).isEqualTo(1)
        assertThat(applied).isEqualTo(3)
    }
}
