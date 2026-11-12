package com.promenar.infinity.data.production

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal fun reviewFixture(): ProductionSnapshot {
    val takes = listOf(Take("t1", "p/1.mp4", TakeSource("服务"), 10), Take("t2", "p/2.mp4", TakeSource("服务"), 20))
    val shot = Shot("SC01-01", "交信", durationSeconds = 3.0, takes = takes, status = ShotStatus.REVIEW)
    val episode = Episode("S1E01", 1, "雾港", scriptPath = "p/script.md", scenes = listOf(Scene("SC01", 1, "码头 夜", shots = listOf(shot))))
    val request = ShotChangeRequest("r", episode.id, "SC01", shot.code, mapOf("title" to ShotFieldChange(JsonPrimitive("交信"), JsonPrimitive("告别"))), ChangeSource.AGENT)
    return ProductionSnapshot(Production("p", "项目", ProductionKind.SERIES, "p", createdAt = 1, updatedAt = 20,
        seasons = listOf(Season("S1", 1, episodes = listOf(episode))),
        assets = listOf(Asset("char", AssetKind.CHARACTER, "林晚", versions = listOf(AssetVersion(1, "p/1.png", 10), AssetVersion(2, "p/2.png", 20)), currentVersion = 1, status = AssetStatus.REVIEW)),
        cuts = listOf(CutVersion("c", episode.id, 1, 10, listOf(CutClip(episode.id, shot.code, seconds = 3.0)))), changeRequests = listOf(request)), 1, ManifestSync.InSync)
}
internal fun reviewApply(s: ProductionSnapshot, action: ProductionUserAction, now: Long = s.production.updatedAt + 1) = ProductionUserEngine.apply(s, s.revision, action, now)
internal fun reviewShot(s: ProductionSnapshot) = s.production.seasons.single().episodes.single().scenes.single().shots.single()

class ProductionReviewTest {
    private val a = ReviewTarget.take("S1E01", "SC01-01", "t1")
    private val b = ReviewTarget.take("S1E01", "SC01-01", "t2")
    private fun verdict(s: ProductionSnapshot, target: ReviewTarget, kind: ReviewKind) = reviewApply(s, ProductionUserAction.Review(target, kind, null))
    private fun sent(s: ProductionSnapshot) = reviewApply(s, ProductionUserAction.MarkReviewSent(s.production.nextReviewRound, s.production.updatedAt + 1))

    @Test fun `候选结论只保留最后一次并推导全部状态`() {
        var s = verdict(reviewFixture(), a, ReviewKind.APPROVE)
        assertThat(reviewShot(s).selectedTakeId).isEqualTo("t1")
        assertThat(reviewShot(s).status).isEqualTo(ShotStatus.LOCKED)
        s = verdict(s, a, ReviewKind.REJECT)
        assertThat(s.production.reviews).hasSize(1)
        assertThat(reviewShot(s).selectedTakeId).isNull()
        assertThat(reviewShot(s).status).isEqualTo(ShotStatus.REVIEW)
        s = verdict(s, b, ReviewKind.REJECT)
        assertThat(reviewShot(s).status).isEqualTo(ShotStatus.REDO)
        assertThat(reviewShot(s).changedBy).isEqualTo(ChangeSource.USER)
        assertThat(reviewShot(s).changedAt).isEqualTo(s.revision)
        assertThat(deriveShotReviewStatus(Shot("empty", "空", durationSeconds = 1.0, status = ShotStatus.GENERATING), emptyList())).isEqualTo(ShotStatus.GENERATING)
    }

    @Test fun `新候选批准排他且撤回恢复已发送候选及效果`() {
        val old = sent(verdict(reviewFixture(), a, ReviewKind.APPROVE))
        val changed = verdict(old, b, ReviewKind.APPROVE)
        assertThat(changed.production.verdictOf(a)).isNull()
        assertThat(reviewShot(changed).selectedTakeId).isEqualTo("t2")
        val restored = reviewApply(changed, ProductionUserAction.RetractReview(b, ReviewKind.APPROVE))
        assertThat(restored.production.verdictOf(a)).isEqualTo(old.production.verdictOf(a))
        assertThat(restored.production.verdictOf(b)).isNull()
        assertThat(reviewShot(restored).selectedTakeId).isEqualTo("t1")
        assertThat(restored.production.unsentReviews()).isEmpty()
    }

    @Test fun `新候选改为否决不会提前恢复被替换的已发送批准`() {
        val old = sent(verdict(reviewFixture(), a, ReviewKind.APPROVE))
        val rejected = verdict(verdict(old, b, ReviewKind.APPROVE), b, ReviewKind.REJECT)
        assertThat(rejected.production.verdictOf(a)).isNull()
        assertThat(reviewShot(rejected).status).isEqualTo(ShotStatus.REVIEW)
        assertThat(reviewShot(rejected).selectedTakeId).isNull()
        val restored = reviewApply(rejected, ProductionUserAction.RetractReview(b, ReviewKind.REJECT))
        assertThat(reviewShot(restored).selectedTakeId).isEqualTo("t1")
    }

    @Test fun `旧快照选定候选在无关否决时保留且连续替换可撤回`() {
        val initial = reviewFixture().let { it.copy(production = it.production.mapShot("S1E01", "SC01-01") { shot -> shot.copy(selectedTakeId = "t1", status = ShotStatus.LOCKED) }) }
        assertThat(reviewShot(verdict(initial, b, ReviewKind.REJECT)).selectedTakeId).isEqualTo("t1")
        val changed = verdict(verdict(initial, b, ReviewKind.APPROVE), b, ReviewKind.REJECT)
        assertThat(reviewShot(changed).selectedTakeId).isNull()
        assertThat(reviewShot(reviewApply(changed, ProductionUserAction.RetractReview(b, ReviewKind.REJECT))).selectedTakeId).isEqualTo("t1")
    }

    @Test fun `资产版本批准排他否决当前版本重做撤回恢复`() {
        val first = ReviewTarget.assetVersion("char", 1)
        val second = ReviewTarget.assetVersion("char", 2)
        val old = sent(verdict(reviewFixture(), first, ReviewKind.APPROVE))
        val changed = verdict(old, second, ReviewKind.APPROVE)
        assertThat(changed.production.verdictOf(first)).isNull()
        assertThat(changed.production.assets.single().currentVersion).isEqualTo(2)
        val rejected = verdict(changed, second, ReviewKind.REJECT)
        assertThat(rejected.production.assets.single().currentVersion).isEqualTo(2)
        assertThat(rejected.production.assets.single().status).isEqualTo(AssetStatus.REDO)
        val restored = reviewApply(rejected, ProductionUserAction.RetractReview(second, ReviewKind.REJECT))
        assertThat(restored.production.assets.single().currentVersion).isEqualTo(1)
        assertThat(restored.production.assets.single().status).isEqualTo(AssetStatus.LOCKED)
        assertThat(restored.production.verdictOf(first)?.round).isEqualTo(1)
    }

    @Test fun `剧本和场的结论与撤回分别恢复锁定`() {
        listOf(ReviewTarget.script("S1E01"), ReviewTarget.scene("S1E01", "SC01")).forEach { target ->
            val old = sent(verdict(reviewFixture(), target, ReviewKind.APPROVE))
            val rejected = verdict(old, target, ReviewKind.REJECT)
            fun locked(s: ProductionSnapshot) = s.production.seasons.single().episodes.single().let { if (target.kind == ReviewTargetKind.SCRIPT) it.scriptLocked else it.scenes.single().storyboardLocked }
            assertThat(locked(rejected)).isFalse()
            val restored = reviewApply(rejected, ProductionUserAction.RetractReview(target, ReviewKind.REJECT))
            assertThat(locked(restored)).isTrue()
            assertThat(restored.production.verdictOf(target)).isEqualTo(old.production.verdictOf(target))
        }
    }

    @Test fun `剪辑只记录结论删除时清理当前意见并保留轮次历史`() {
        val target = ReviewTarget.cut("c")
        val initial = reviewFixture()
        val approved = verdict(initial, target, ReviewKind.APPROVE)
        assertThat(approved.production.cuts).isEqualTo(initial.production.cuts)
        val archived = sent(approved)
        val pending = verdict(archived, target, ReviewKind.REJECT)
        val removed = reviewApply(pending, ProductionUserAction.DeleteCut("c"))
        assertThat(removed.production.reviews).isEmpty()
        assertThat(removed.production.reviewDrafts).isEmpty()
        assertThat(removed.production.reviewRounds).isEqualTo(archived.production.reviewRounds)
    }

    @Test fun `改动批准可还原旧值否决标记保留并可撤回`() {
        val target = ReviewTarget.change("r")
        val initial = reviewFixture()
        val approved = verdict(initial, target, ReviewKind.APPROVE)
        assertThat(reviewShot(approved).title).isEqualTo("告别")
        assertThat(approved.production.changeRequests.single().approved).isTrue()
        val reverted = reviewApply(approved, ProductionUserAction.RetractReview(target, ReviewKind.APPROVE))
        assertThat(reviewShot(reverted).title).isEqualTo("交信")
        assertThat(reverted.production.changeRequests.single().approved).isFalse()
        val rejected = verdict(initial, target, ReviewKind.REJECT)
        assertThat(rejected.production.changeRequests.single().rejected).isTrue()
        assertThat(rejected.production.changeRequests.single().changedBy).isEqualTo(ChangeSource.USER)
        assertThat(rejected.production.changeRequests.single().changedAt).isEqualTo(rejected.revision)
        val undone = reviewApply(rejected, ProductionUserAction.RetractReview(target, ReviewKind.REJECT))
        assertThat(undone.production.changeRequests.single().rejected).isFalse()
        val old = sent(approved)
        val changed = verdict(old, target, ReviewKind.REJECT)
        assertThat(reviewShot(changed).title).isEqualTo("交信")
        assertThat(reviewShot(reviewApply(changed, ProductionUserAction.RetractReview(target, ReviewKind.REJECT))).title).isEqualTo("告别")
    }

    @Test fun `项目集镜头仅接受非空建议且建议全部撤回`() {
        listOf(ReviewTarget.project(), ReviewTarget.episode("S1E01"), ReviewTarget.shot("S1E01", "SC01-01")).forEach { target ->
            assertThrows(IllegalArgumentException::class.java) { verdict(reviewFixture(), target, ReviewKind.APPROVE) }
            assertThrows(IllegalArgumentException::class.java) { reviewApply(reviewFixture(), ProductionUserAction.Review(target, ReviewKind.SUGGEST, "  ")) }
            var s = reviewApply(reviewFixture(), ProductionUserAction.Review(target, ReviewKind.SUGGEST, " 建议一 "))
            s = reviewApply(s, ProductionUserAction.Review(target, ReviewKind.SUGGEST, "建议二"))
            assertThat(s.production.unsentSuggestions(target).map { it.note }).containsExactly("建议一", "建议二").inOrder()
            assertThat(reviewApply(s, ProductionUserAction.RetractReview(target, ReviewKind.SUGGEST)).production.unsentReviews()).isEmpty()
        }
        assertThrows(IllegalArgumentException::class.java) { verdict(reviewFixture(), ReviewTarget.take("S1E01", "SC01-01", "missing"), ReviewKind.REJECT) }
        assertThrows(IllegalArgumentException::class.java) { verdict(reviewFixture(), a.copy(assetId = "char"), ReviewKind.REJECT) }
    }

    @Test fun `未发意见及回退谱系快照往返后可撤回`() {
        val old = sent(verdict(reviewFixture(), a, ReviewKind.APPROVE))
        var pending = verdict(old, b, ReviewKind.APPROVE)
        pending = reviewApply(pending, ProductionUserAction.Review(ReviewTarget.project(), ReviewKind.SUGGEST, "建议"))
        val roundTrip = ProductionManifestCodec.decode(ProductionManifestCodec.encode(pending.production, pending.revision)).production
        assertThat(roundTrip).isEqualTo(pending.production)
        assertThat(Json.decodeFromString<Production>(Json.encodeToString(pending.production))).isEqualTo(pending.production)
        val restored = reviewApply(pending.copy(production = roundTrip), ProductionUserAction.RetractReview(b, ReviewKind.APPROVE))
        assertThat(reviewShot(restored).selectedTakeId).isEqualTo("t1")
    }

    @Test fun `发送归档轮次清理建议并拒绝冲突保留最多五十轮`() {
        var s = verdict(reviewFixture(), a, ReviewKind.REJECT)
        s = reviewApply(s, ProductionUserAction.Review(ReviewTarget.project(), ReviewKind.SUGGEST, "建议"))
        assertThrows(ProductionRevisionConflictException::class.java) { reviewApply(s, ProductionUserAction.MarkReviewSent(2, 50)) }
        s = sent(s)
        assertThat(s.production.nextReviewRound).isEqualTo(2)
        assertThat(s.production.reviewRounds.single().entries).hasSize(2)
        assertThat(s.production.reviewRounds.single().entries.all { it.round == 1 }).isTrue()
        assertThat(s.production.reviews.single().round).isEqualTo(1)
        assertThat(s.production.reviewDrafts).isEmpty()
        repeat(51) {
            s = reviewApply(s, ProductionUserAction.Review(ReviewTarget.project(), ReviewKind.SUGGEST, "下一轮"))
            s = sent(s)
        }
        assertThat(s.production.reviewRounds).hasSize(50)
        assertThat(s.production.reviewRounds.first().number).isEqualTo(52)
        assertThat(s.production.verdictOf(a)?.sentAt).isNotNull()
    }

    @Test fun `旧预算字段兼容但其它未知清单字段拒绝`() {
        val initial = reviewFixture()
        val root = Json.parseToJsonElement(ProductionManifestCodec.encode(initial.production, 1).decodeToString()).jsonObject
        val old = JsonObject(root + ("production" to JsonObject(root.getValue("production").jsonObject + ("budgetCents" to JsonPrimitive(1000)))))
        assertThat(ProductionManifestCodec.decode(old.toString().toByteArray()).production).isEqualTo(initial.production)
        val unknown = JsonObject(root + ("production" to JsonObject(root.getValue("production").jsonObject + ("unknown" to JsonPrimitive(true)))))
        assertThrows(IllegalArgumentException::class.java) { ProductionManifestCodec.decode(unknown.toString().toByteArray()) }
    }

    @Test fun `撤回已发否决的临时批准时按最新候选重新推导状态`() {
        var s = reviewApply(reviewFixture(), ProductionUserAction.Review(a, ReviewKind.REJECT, null))
        s = reviewApply(s, ProductionUserAction.Review(b, ReviewKind.REJECT, null))
        s = sent(s)
        s = verdict(s, a, ReviewKind.APPROVE)
        s = s.copy(production = s.production.mapShot("S1E01", "SC01-01") { shot -> shot.copy(takes = shot.takes + Take("t3", "p/3.mp4", TakeSource("服务"), 50)) })
        s = reviewApply(s, ProductionUserAction.RetractReview(a, ReviewKind.APPROVE))
        assertThat(reviewShot(s).takes).hasSize(3)
        assertThat(reviewShot(s).status).isEqualTo(ShotStatus.REVIEW)
        assertThat(reviewShot(s).selectedTakeId).isNull()
    }

    @Test fun `所有审核对象的建议不改变制作效果且可整组撤回`() {
        val initial = reviewFixture()
        listOf(a, ReviewTarget.assetVersion("char", 1), ReviewTarget.script("S1E01"), ReviewTarget.scene("S1E01", "SC01"), ReviewTarget.cut("c"), ReviewTarget.change("r")).forEach { target ->
            val changed = reviewApply(initial, ProductionUserAction.Review(target, ReviewKind.SUGGEST, "说明"))
            assertThat(changed.production.seasons).isEqualTo(initial.production.seasons)
            assertThat(changed.production.assets).isEqualTo(initial.production.assets)
            assertThat(changed.production.cuts).isEqualTo(initial.production.cuts)
            assertThat(changed.production.changeRequests).isEqualTo(initial.production.changeRequests)
            assertThat(reviewApply(changed, ProductionUserAction.RetractReview(target, ReviewKind.SUGGEST)).production.unsentReviews()).isEmpty()
        }
    }

    @Test fun `非法发送修订和缺少回退锁定状态的清单拒绝读入`() {
        var s = verdict(reviewFixture(), ReviewTarget.script("S1E01"), ReviewKind.REJECT)
        s = sent(s)
        val root = Json.parseToJsonElement(ProductionManifestCodec.encode(s.production, s.revision).decodeToString()).jsonObject
        val production = root.getValue("production").jsonObject
        val invalidEntry = JsonObject(production.getValue("reviews").jsonArray.single().jsonObject + ("sentRevision" to JsonPrimitive(-1)))
        val invalid = JsonObject(root + ("production" to JsonObject(production + ("reviews" to JsonArray(listOf(invalidEntry))))))
        assertThrows(IllegalArgumentException::class.java) { ProductionManifestCodec.decode(invalid.toString().toByteArray()) }
        val pending = verdict(reviewFixture(), ReviewTarget.script("S1E01"), ReviewKind.APPROVE)
        val broken = pending.production.copy(reviewDrafts = pending.production.reviewDrafts.map { it.copy(locked = null) })
        assertThrows(IllegalArgumentException::class.java) { ProductionManifestCodec.encode(broken, pending.revision) }
    }
}
