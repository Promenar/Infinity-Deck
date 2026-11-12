package com.promenar.infinity.data.production

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProductionReviewQueuedTest {
    private val target = ReviewTarget.take("S1E01", "SC01-01", "t1")
    private val other = ReviewTarget.take("S1E01", "SC01-01", "t2")
    private fun review(s: ProductionSnapshot, t: ReviewTarget = target, kind: ReviewKind = ReviewKind.APPROVE) =
        reviewApply(s, ProductionUserAction.Review(t, kind, if (kind == ReviewKind.SUGGEST) "建议" else null))
    private fun queue(s: ProductionSnapshot) = reviewApply(s, ProductionUserAction.MarkReviewQueued(s.production.nextReviewRound, s.production.updatedAt + 1))
    private fun revert(s: ProductionSnapshot) = reviewApply(s, ProductionUserAction.RevertReviewQueued(1))

    @Test fun `排队校验轮次时间内容且只允许一个轮次`() {
        assertThrows(IllegalArgumentException::class.java) { queue(reviewFixture()) }
        val pending = review(reviewFixture())
        assertThrows(ProductionRevisionConflictException::class.java) { reviewApply(pending, ProductionUserAction.MarkReviewQueued(2, 30)) }
        assertThrows(IllegalArgumentException::class.java) { reviewApply(pending, ProductionUserAction.MarkReviewQueued(1, -1)) }
        val queued = queue(pending)
        assertThat(queued.production.queuedReviewRound).isEqualTo(1)
        assertThat(queued.production.unsentReviews()).isEmpty()
        assertThat(queued.production.nextReviewRound).isEqualTo(1)
        assertThrows(IllegalArgumentException::class.java) { queue(review(queued, other)) }
    }

    @Test fun `排队原文保留并且新结论属于下一轮`() {
        val queued = queue(review(reviewFixture()))
        val changed = review(queued, target, ReviewKind.REJECT)
        assertThat(changed.production.queuedReviews().single().kind).isEqualTo(ReviewKind.APPROVE)
        assertThat(changed.production.unsentReviews().single().kind).isEqualTo(ReviewKind.REJECT)
        val sent = reviewApply(changed, ProductionUserAction.MarkReviewSent(1, 50))
        assertThat(sent.production.reviewRounds.single().entries.single().kind).isEqualTo(ReviewKind.APPROVE)
        assertThat(sent.production.reviewRounds.single().entries.single().sentRevision).isEqualTo(sent.revision)
        assertThat(sent.production.reviewRounds.single().entries.single().queuedRound).isNull()
        assertThat(sent.production.nextReviewRound).isEqualTo(2)
        assertThat(sent.production.unsentReviews().single().kind).isEqualTo(ReviewKind.REJECT)
        val duplicate = reviewApply(sent, ProductionUserAction.MarkReviewSent(1, 60))
        assertThat(duplicate).isEqualTo(sent)
        assertThat(duplicate.production.reviews).isEqualTo(sent.production.reviews)
        assertThat(duplicate.production.reviewRounds).isEqualTo(sent.production.reviewRounds)
        assertThat(duplicate.production.nextReviewRound).isEqualTo(2)
        val restored = reviewApply(sent, ProductionUserAction.RetractReview(target, ReviewKind.REJECT))
        assertThat(restored.production.verdictOf(target)?.round).isEqualTo(1)
        assertThat(reviewShot(restored).selectedTakeId).isEqualTo("t1")
    }

    @Test fun `撤回意见不能撤回排队结论或排队建议`() {
        var queued = queue(review(review(reviewFixture()), ReviewTarget.project(), ReviewKind.SUGGEST))
        queued = reviewApply(queued, ProductionUserAction.RetractReview(target, ReviewKind.APPROVE))
        queued = reviewApply(queued, ProductionUserAction.RetractReview(ReviewTarget.project(), ReviewKind.SUGGEST))
        assertThat(queued.production.queuedReviews()).hasSize(2)
        val restored = revert(queued)
        assertThat(restored.production.unsentReviews()).hasSize(2)
        assertThat(reviewApply(restored, ProductionUserAction.RetractReview(target, ReviewKind.APPROVE)).production.verdictOf(target)).isNull()
        assertThat(revert(restored).production.reviews).isEqualTo(restored.production.reviews)
    }

    @Test fun `回退合并更晚结论并保留全部建议和原始撤回基线`() {
        var s = reviewSend(review(reviewFixture(), other))
        s = review(s)
        s = review(s, ReviewTarget.project(), ReviewKind.SUGGEST)
        val round = s.production.nextReviewRound
        s = queue(s)
        s = review(s, target, ReviewKind.REJECT)
        s = review(s, ReviewTarget.project(), ReviewKind.SUGGEST)
        val restored = reviewApply(s, ProductionUserAction.RevertReviewQueued(round))
        assertThat(restored.production.unsentReviews().count { it.kind == ReviewKind.SUGGEST }).isEqualTo(2)
        assertThat(restored.production.unsentReviews().count { it.kind != ReviewKind.SUGGEST }).isEqualTo(1)
        assertThat(restored.production.verdictOf(target)?.kind).isEqualTo(ReviewKind.REJECT)
        val retracted = reviewApply(restored, ProductionUserAction.RetractReview(target, ReviewKind.REJECT))
        assertThat(retracted.production.verdictOf(other)?.kind).isEqualTo(ReviewKind.APPROVE)
        assertThat(reviewShot(retracted).selectedTakeId).isEqualTo("t2")
    }

    @Test fun `排队中批准另一候选后回退仍只有一个生效批准`() {
        val s = review(queue(review(reviewFixture())), other)
        assertThat(s.production.queuedReviews()).hasSize(1)
        assertThat(s.production.verdictOf(target)).isNull()
        val restored = revert(s)
        assertThat(restored.production.unsentReviews().single().target).isEqualTo(other)
        assertThat(reviewShot(restored).selectedTakeId).isEqualTo("t2")
        val retracted = reviewApply(restored, ProductionUserAction.RetractReview(other, ReviewKind.APPROVE))
        assertThat(reviewShot(retracted).selectedTakeId).isNull()
        assertThat(retracted.production.reviews).isEmpty()
    }

    @Test fun `排队期间撤回新增结论恢复排队基线且允许再次回退`() {
        val queued = queue(review(reviewFixture()))
        val newer = review(queued, other)
        val retracted = reviewApply(newer, ProductionUserAction.RetractReview(other, ReviewKind.APPROVE))
        assertThat(retracted.production.verdictOf(target)?.queuedRound).isEqualTo(1)
        assertThat(reviewShot(retracted).selectedTakeId).isEqualTo("t1")
        assertThat(revert(retracted).production.unsentReviews().single().target).isEqualTo(target)
    }

    @Test fun `排队谱系可序列化且旧意见缺省为未发送`() {
        val queued = review(queue(review(reviewFixture())), other)
        val decoded = ProductionManifestCodec.decode(ProductionManifestCodec.encode(queued.production, queued.revision))
        assertThat(decoded.production).isEqualTo(queued.production)
        assertThat(revert(queued.copy(production = decoded.production)).production.unsentReviews().single().target).isEqualTo(other)
        val entry = Json.decodeFromString<ReviewEntry>("""{"target":{"kind":"project"},"kind":"suggest","note":"旧意见","at":1}""")
        assertThat(entry.queuedRound).isNull()
        val old = Json.decodeFromString<Production>(Json.encodeToString(reviewFixture().production))
        assertThat(old.queuedReviews()).isEmpty()
    }

    @Test fun `排队批准被新批准再否决后回退不能恢复被替换结论`() {
        val third = ReviewTarget.take("S1E01", "SC01-01", "t3")
        var initial = reviewFixture().let { it.copy(production = it.production.mapShot("S1E01", "SC01-01") { shot ->
            shot.copy(takes = shot.takes + Take("t3", "p/3.mp4", TakeSource("服务"), 30))
        }) }
        initial = reviewSend(review(initial, third))
        var s = queue(review(initial, target))
        s = review(s, other)
        s = review(s, other, ReviewKind.REJECT)
        val restored = reviewApply(s, ProductionUserAction.RevertReviewQueued(2))
        assertThat(restored.production.unsentReviews().single().target).isEqualTo(other)
        assertThat(restored.production.verdictOf(target)).isNull()
        assertThat(restored.production.verdictOf(third)).isNull()
        assertThat(reviewShot(restored).selectedTakeId).isNull()
        val retracted = reviewApply(restored, ProductionUserAction.RetractReview(other, ReviewKind.REJECT))
        assertThat(reviewShot(retracted).selectedTakeId).isEqualTo("t3")
    }

    @Test fun `系统时间倒退时回退仍以排队后的新结论为准`() {
        var s = reviewApply(reviewFixture(), ProductionUserAction.Review(target, ReviewKind.APPROVE, null), 100)
        s = queue(s)
        s = reviewApply(s, ProductionUserAction.Review(target, ReviewKind.REJECT, null), 90)
        val restored = revert(s)
        assertThat(restored.production.unsentReviews().single().kind).isEqualTo(ReviewKind.REJECT)
    }

    @Test fun `删除排队审核对象保留原文直到送达且回退不恢复已删对象`() {
        var s = reviewApply(reviewFixture(), ProductionUserAction.Review(ReviewTarget.cut("c"), ReviewKind.APPROVE, null))
        s = queue(s)
        val deleted = reviewApply(s, ProductionUserAction.DeleteCut("c"))
        assertThat(deleted.production.queuedReviewRound).isEqualTo(1)
        assertThat(deleted.production.queuedReviews().single().target).isEqualTo(ReviewTarget.cut("c"))
        val sent = reviewApply(deleted, ProductionUserAction.MarkReviewSent(1, 100))
        assertThat(sent.production.reviewRounds.single().entries.single().target).isEqualTo(ReviewTarget.cut("c"))
        assertThat(sent.production.reviews).isEmpty()
        assertThat(sent.production.nextReviewRound).isEqualTo(2)
        val restored = revert(deleted)
        assertThat(restored.production.unsentReviews()).isEmpty()
        assertThat(restored.production.reviewDrafts).isEmpty()
        assertThat(revert(restored)).isEqualTo(restored)
    }

    @Test fun `资产排队批准后新否决回退仍保留所审版本`() {
        val version = ReviewTarget.assetVersion("char", 2)
        var s = queue(review(reviewFixture(), version))
        s = review(s, version, ReviewKind.REJECT)
        val restored = revert(s)
        assertThat(restored.production.assets.single().currentVersion).isEqualTo(2)
        assertThat(restored.production.assets.single().status).isEqualTo(AssetStatus.REDO)
        val retracted = reviewApply(restored, ProductionUserAction.RetractReview(version, ReviewKind.REJECT))
        assertThat(retracted.production.assets.single().currentVersion).isEqualTo(1)
    }

    @Test fun `校验拒绝排队和已发送并存以及错误轮次发送元数据与基线`() {
        val queued = queue(review(reviewFixture()))
        val entry = queued.production.queuedReviews().single()
        listOf(entry.copy(round = 1), entry.copy(queuedRound = 0), entry.copy(queuedRound = 2), entry.copy(sentAt = 20), entry.copy(sentRevision = 1)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { validateProductionReviews(queued.production.copy(reviews = listOf(invalid)), queued.revision) }
        }
        assertThrows(IllegalArgumentException::class.java) { validateProductionReviews(queued.production.copy(reviewDrafts = emptyList()), queued.revision) }
        val unsent = review(reviewFixture())
        assertThat(reviewApply(unsent, ProductionUserAction.MarkReviewSent(1, 50)).production.reviews).isEqualTo(unsent.production.reviews)
    }
}
