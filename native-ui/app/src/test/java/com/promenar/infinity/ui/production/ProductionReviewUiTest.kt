package com.promenar.infinity.ui.production

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.production.Episode
import com.promenar.infinity.data.production.Production
import com.promenar.infinity.data.production.ProductionKind
import com.promenar.infinity.data.production.ProductionUserAction
import com.promenar.infinity.data.production.ReviewEntry
import com.promenar.infinity.data.production.ReviewKind
import com.promenar.infinity.data.production.ReviewTarget
import com.promenar.infinity.data.production.Scene
import com.promenar.infinity.data.production.Season
import com.promenar.infinity.data.production.Shot
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

    @Test fun `排队期间发送不可点并显示已排队`() {
        assertThat(ProductionSendState(2, false, queued = true) {}.enabled).isFalse()
        assertThat(ProductionSendState(0, false, queued = true) {}.enabled).isFalse()
    }

    @Test fun `已排队的结论与建议标为已排队且不计入未发送`() {
        val queued = withReviews(
            ReviewEntry(take, ReviewKind.APPROVE, at = 1, queuedRound = 1),
            ReviewEntry(shot, ReviewKind.SUGGEST, "慢一点", at = 2, queuedRound = 1),
        )
        assertThat(queued.reviewChips(take)).containsExactly(ReviewChip.APPROVED, ReviewChip.QUEUED).inOrder()
        assertThat(queued.reviewChips(shot)).containsExactly(ReviewChip.QUEUED)
        assertThat(queued.unsentCount()).isEqualTo(0)
        // 排队期间新的建议属于下一轮，标为未发送。
        val next = queued.copy(reviews = queued.reviews + ReviewEntry(shot, ReviewKind.SUGGEST, "再慢一点", at = 3))
        assertThat(next.reviewChips(shot)).containsExactly(ReviewChip.SUGGESTION_UNSENT)
        assertThat(next.unsentCount()).isEqualTo(1)
    }

    @Test fun `已排队的结论再点同种结论记为新结论不撤回`() {
        val queued = withReviews(ReviewEntry(take, ReviewKind.APPROVE, at = 1, queuedRound = 1))
        assertThat(queued.verdictAction(take, ReviewKind.APPROVE)).isEqualTo(ProductionUserAction.Review(take, ReviewKind.APPROVE, null))
    }
}
