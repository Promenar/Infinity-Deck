package com.promenar.infinity.data.production

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.mediagen.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal fun reviewTask(id: String, state: MediaTaskState, source: String = ProductionSourceRef("p", "S1E01", "SC01-01").encode(), createdAt: Long = 30, error: String? = null) =
    MediaTask(id, MediaGenerationRequest("channel", GenerationMode.TEXT, "提示", emptyList(), source), state = state, createdAt = createdAt, error = error)

class ProductionCostAndOpenItemsTest {
    @Test fun `任务按来源阶段归属并且登记产出不重复计数取消不计`() {
        val initial = reviewFixture().production
        val p = initial.mapShot("S1E01", "SC01-01") { shot -> shot.copy(takes = listOf(shot.takes.first(), shot.takes.last().copy(taskId = "video")), previewPath = "p/preview.mp4") }
            .copy(assets = listOf(initial.assets.single().copy(versions = listOf(AssetVersion(1, "p/1.png", 10), AssetVersion(2, "p/2.png", 20, taskId = "asset"))),
                Asset("sound", AssetKind.SOUND, "风声", versions = listOf(AssetVersion(1, "p/wind.wav", 10)))))
        val tasks = listOf(
            reviewTask("video", MediaTaskState.COMPLETED), reviewTask("failed", MediaTaskState.FAILED),
            reviewTask("cancel", MediaTaskState.CANCELED), reviewTask("queued", MediaTaskState.QUEUED),
            reviewTask("speech", MediaTaskState.COMPLETED, ProductionSourceRef("p", "S1E01", "SC01-01", 0).encode()),
            reviewTask("asset", MediaTaskState.COMPLETED, ProductionAssetSourceRef("p", "char").encode()),
            reviewTask("preview", MediaTaskState.COMPLETED).copy(outputPaths = listOf("p/preview.mp4")),
            reviewTask("other", MediaTaskState.COMPLETED, ProductionSourceRef("else", "S1E01", "SC01-01").encode()),
        )
        val stats = productionCostStats(p, tasks + tasks.first())
        assertThat(stats.byStage).containsExactly(ProductionStage.GENERATION, GenerationCount(2, 1),
            ProductionStage.VISUAL_DEV, GenerationCount(2), ProductionStage.SOUND, GenerationCount(2), ProductionStage.ANIMATIC, GenerationCount(1))
        assertThat(stats.shot("S1E01", "SC01-01")).isEqualTo(GenerationCount(4, 1))
        assertThat(stats.asset("char")).isEqualTo(GenerationCount(2))
        assertThat(stats.asset("sound")).isEqualTo(GenerationCount(1))
        assertThat(stats.shot("missing", "shot")).isEqualTo(GenerationCount())
        assertThat(stats.asset("missing")).isEqualTo(GenerationCount())
        assertThat(productionCostStats(initial.copy(seasons = emptyList(), assets = emptyList()), emptyList()).byStage).isEmpty()
    }

    @Test fun `六次阈值与提醒次数降序包含失败资产任务`() {
        val p = reviewFixture().production
        val tasks = List(6) { reviewTask("shot$it", MediaTaskState.FAILED) } + List(7) {
            reviewTask("asset$it", MediaTaskState.FAILED, ProductionAssetSourceRef("p", "char").encode())
        }
        val stats = productionCostStats(p, tasks)
        assertThat(stats.alerts(GenerationCount(5))).isFalse()
        assertThat(stats.alerts(GenerationCount(4, 2))).isTrue()
        assertThat(stats.alertShots.single().second.total).isEqualTo(8)
        assertThat(stats.alertAssets.single().second.total).isEqualTo(9)
        val sorted = ProductionCostStats(emptyMap(), mapOf(ShotKey("a", "1") to GenerationCount(6), ShotKey("b", "2") to GenerationCount(9)), mapOf("a" to GenerationCount(6), "b" to GenerationCount(9)))
        assertThat(sorted.alertShots.map { it.second.total }).containsExactly(9, 6).inOrder()
        assertThat(sorted.alertAssets.map { it.first }).containsExactly("b", "a").inOrder()
    }

    @Test fun `失败升级取消及成本提醒项目隔离且后续任务替换取消`() {
        val p = reviewFixture().production
        val canceled = reviewTask("old", MediaTaskState.CANCELED, createdAt = 30, error = "升级后取消")
        val failed = reviewTask("failed", MediaTaskState.FAILED, createdAt = 20, error = "失败原因")
        val unrelated = reviewTask("foreign", MediaTaskState.FAILED, ProductionSourceRef("foreign", "S1E01", "SC01-01").encode())
        val stats = ProductionCostStats(emptyMap(), mapOf(ShotKey("S1E01", "SC01-01") to GenerationCount(6)), mapOf("char" to GenerationCount(7)))
        val items = productionOpenItems(p, listOf(canceled, failed, unrelated, reviewTask("user", MediaTaskState.CANCELED)), stats)
        assertThat(items.filterIsInstance<OpenItem.FailedTask>().single()).isEqualTo(OpenItem.FailedTask("failed", ReviewTarget.shot("S1E01", "SC01-01"), "失败原因"))
        assertThat(items.filterIsInstance<OpenItem.CanceledTask>().map { it.taskId }).containsExactly("old")
        assertThat(items.filterIsInstance<OpenItem.CostAlert>()).hasSize(2)
        val replaced = productionOpenItems(p, listOf(canceled, reviewTask("new", MediaTaskState.QUEUED, createdAt = 31)), stats)
        assertThat(replaced.filterIsInstance<OpenItem.CanceledTask>()).isEmpty()
        val unknown = productionOpenItems(p, listOf(canceled.copy(createdAt = 0), reviewTask("existing", MediaTaskState.COMPLETED, createdAt = 31)), stats)
        assertThat(unknown.filterIsInstance<OpenItem.CanceledTask>()).hasSize(1)
    }

    @Test fun `候选否决发送后有无新产出以及归档裁剪的结果一致`() {
        val target = ReviewTarget.take("S1E01", "SC01-01", "t1")
        val rejected = reviewApply(reviewFixture(), ProductionUserAction.Review(target, ReviewKind.REJECT, null), 30)
        val sent = reviewSend(rejected, 1, 40)
        val entry = sent.production.verdictOf(target)!!
        assertThat(isSentRejectionUnresolved(rejected.production, rejected.production.verdictOf(target)!!)).isFalse()
        assertThat(isSentRejectionUnresolved(sent.production, entry)).isTrue()
        val changed = sent.production.mapShot("S1E01", "SC01-01") { shot -> shot.copy(takes = shot.takes + Take("t3", "p/3.mp4", TakeSource("服务"), 41)) }
        assertThat(isSentRejectionUnresolved(changed, entry)).isFalse()
        assertThat(isSentRejectionUnresolved(changed.copy(reviewRounds = emptyList()), entry)).isFalse()
        assertThat(isSentRejectionUnresolved(changed.copy(seasons = emptyList()), entry)).isFalse()
        assertThat(productionOpenItems(sent.production, emptyList(), productionCostStats(sent.production, emptyList())).filterIsInstance<OpenItem.Rejected>()).containsExactly(OpenItem.Rejected(target))
    }

    @Test fun `资产更高版本及剧本场剪辑的真实内容修订解决否决`() {
        val targets = listOf(ReviewTarget.assetVersion("char", 2), ReviewTarget.script("S1E01"), ReviewTarget.scene("S1E01", "SC01"), ReviewTarget.cut("c"))
        targets.forEach { target ->
            val pending = reviewApply(reviewFixture(), ProductionUserAction.Review(target, ReviewKind.REJECT, null))
            val sent = reviewSend(pending, 1, 40)
            val entry = sent.production.verdictOf(target)!!
            assertThat(isSentRejectionUnresolved(sent.production, entry)).isTrue()
            val changed = when (target.kind) {
                ReviewTargetKind.ASSET_VERSION -> sent.production.copy(assets = sent.production.assets.map { it.copy(versions = it.versions + AssetVersion(3, "p/3.png", 41)) })
                ReviewTargetKind.SCRIPT -> sent.production.copy(seasons = sent.production.seasons.map { it.copy(episodes = it.episodes.map { ep -> ep.copy(contentChangedAt = sent.revision + 1) }) })
                ReviewTargetKind.SCENE -> sent.production.copy(seasons = sent.production.seasons.map { it.copy(episodes = it.episodes.map { ep -> ep.copy(scenes = ep.scenes.map { sc -> sc.copy(contentChangedAt = sent.revision + 1) }) }) })
                else -> sent.production.copy(cuts = sent.production.cuts.map { it.copy(contentChangedAt = sent.revision + 1) })
            }
            assertThat(isSentRejectionUnresolved(changed, entry)).isFalse()
        }
    }

    @Test fun `撤回临时批准恢复否决后依旧未结真实修改内容才消除`() {
        val target = ReviewTarget.script("S1E01")
        var s = reviewApply(reviewFixture(), ProductionUserAction.Review(target, ReviewKind.REJECT, null))
        s = reviewSend(s, 1, 30)
        val old = s.production.verdictOf(target)!!
        s = reviewApply(s, ProductionUserAction.Review(target, ReviewKind.APPROVE, null))
        s = reviewApply(s, ProductionUserAction.RetractReview(target, ReviewKind.APPROVE))
        assertThat(isSentRejectionUnresolved(s.production, old)).isTrue()
        val op = kotlinx.serialization.json.Json.parseToJsonElement("""{"op":"upsert_episode","season_id":"S1","id":"S1E01","number":1,"title":"新稿","script_path":"p/new.md"}""").let { it as kotlinx.serialization.json.JsonObject }
        val changed = ProductionEngine.update(s, listOf(op), s.revision, 60) { true }
        assertThat(isSentRejectionUnresolved(changed.production, old)).isFalse()
    }

    @Test fun `同路径剧本重新登记也推进内容修订`() {
        val target = ReviewTarget.script("S1E01")
        var s = reviewApply(reviewFixture(), ProductionUserAction.Review(target, ReviewKind.REJECT, null))
        s = reviewSend(s, 1, 30)
        val old = s.production.verdictOf(target)!!
        val op = Json.parseToJsonElement("""{"op":"upsert_episode","season_id":"S1","id":"S1E01","number":1,"title":"雾港","script_path":"p/script.md"}""") as JsonObject
        val changed = ProductionEngine.update(s, listOf(op), s.revision, 60) { true }
        assertThat(changed.production.reviewEpisode("S1E01")!!.changedAt).isEqualTo(changed.revision)
        assertThat(changed.production.verdictOf(target)).isNull()
        assertThat(isSentRejectionUnresolved(changed.production, old)).isFalse()
        assertThat(changed.production.reviewRounds.single().entries.single().target).isEqualTo(target)
    }

    @Test fun `五类就地可改目标在发送否决并更新内容后自动恢复待审并保留历史轮次`() {
        val baseFixture = reviewFixture()
        val lookCoat = CharacterLook("coat", "风衣", locked = true, slots = listOf(ReferenceSlot("front", "正面", "p/coat.png"), ReferenceSlot("side", "侧面"), ReferenceSlot("back", "背面")))
        val lookSuit = CharacterLook("suit", "常服", slots = listOf(ReferenceSlot("front", "正面", "p/suit.png"), ReferenceSlot("side", "侧面"), ReferenceSlot("back", "背面")))
        val charWithLooks = Asset("char", AssetKind.CHARACTER, "林晚", status = AssetStatus.REDO, looks = listOf(lookCoat, lookSuit))
        val locAsset = Asset("loc", AssetKind.LOCATION, "码头", status = AssetStatus.REDO, referenceViews = listOf(ReferenceSlot("wide", "全景", "p/loc.png")))
        val richBase = ProductionSnapshot(
            baseFixture.production.copy(assets = listOf(charWithLooks, locAsset)),
            baseFixture.revision,
            baseFixture.manifest,
        )

        val targets = listOf(
            ReviewTarget.script("S1E01"),
            ReviewTarget.scene("S1E01", "SC01"),
            ReviewTarget.assetLook("char", "suit"),
            ReviewTarget.assetViews("loc"),
            ReviewTarget.cut("c"),
        )
        var s = richBase
        targets.forEach { target ->
            s = reviewApply(s, ProductionUserAction.Review(target, ReviewKind.REJECT, "需修订"))
        }
        s = reviewSend(s, 1, 40)
        targets.forEach { target ->
            assertThat(s.production.verdictOf(target)?.kind).isEqualTo(ReviewKind.REJECT)
            assertThat(isSentRejectionUnresolved(s.production, s.production.verdictOf(target)!!)).isTrue()
        }

        val engineUpdated = ProductionEngine.update(
            s,
            Json.parseToJsonElement("""[
                {"op":"upsert_episode","season_id":"S1","id":"S1E01","number":1,"title":"雾港","script_path":"p/script-v2.md"},
                {"op":"upsert_scene","episode_id":"S1E01","code":"SC01","number":1,"heading":"码头 黎明"},
                {"op":"set_look_slot","asset_id":"char","look_id":"suit","slot_id":"front","path":"p/suit-v2.png"},
                {"op":"set_asset_view","asset_id":"loc","view_id":"wide","path":"p/loc-v2.png"}
            ]""").jsonArray,
            s.revision,
            60,
        ) { true }
        val finalUpdated = ProductionUserEngine.apply(engineUpdated, engineUpdated.revision, ProductionUserAction.SetCutSubtitles("c", true), 65)

        targets.forEach { target ->
            assertThat(finalUpdated.production.verdictOf(target)).isNull()
            val historyEntry = finalUpdated.production.reviewRounds.single().entries.first { it.target == target }
            assertThat(historyEntry.kind).isEqualTo(ReviewKind.REJECT)
            assertThat(isSentRejectionUnresolved(finalUpdated.production, historyEntry)).isFalse()
        }
        // 已锁定风衣不被连带解锁，且唯一其余否决已结清后角色回到 REVIEW（不越权直接 LOCKED）。
        val updatedChar = finalUpdated.production.assets.first { it.id == "char" }
        assertThat(updatedChar.status).isEqualTo(AssetStatus.REVIEW)
        assertThat(updatedChar.looks.first { it.id == "coat" }.locked).isTrue()
        assertThat(finalUpdated.production.assets.first { it.id == "loc" }.status).isEqualTo(AssetStatus.REVIEW)

        // 若同一角色仍有另一套已发送否决造型，仅修订其中一套时另一套与 REDO 状态保持。
        val multiRejectBase = ProductionSnapshot(
            richBase.production.copy(
                assets = listOf(charWithLooks.copy(looks = listOf(lookCoat.copy(locked = false), lookSuit), status = AssetStatus.REDO)),
            ),
            richBase.revision,
            richBase.manifest,
        )
        var multi = reviewApply(multiRejectBase, ProductionUserAction.Review(ReviewTarget.assetLook("char", "coat"), ReviewKind.REJECT, "重画风衣"))
        multi = reviewApply(multi, ProductionUserAction.Review(ReviewTarget.assetLook("char", "suit"), ReviewKind.REJECT, "重画常服"))
        multi = reviewSend(multi, 1, 40)
        val oneLookFixed = ProductionEngine.update(
            multi,
            listOf(Json.parseToJsonElement("""{"op":"set_look_slot","asset_id":"char","look_id":"suit","slot_id":"side","note":"补充侧面要点"}""") as JsonObject),
            multi.revision,
            60,
        ) { true }
        assertThat(oneLookFixed.production.verdictOf(ReviewTarget.assetLook("char", "suit"))).isNull()
        assertThat(oneLookFixed.production.verdictOf(ReviewTarget.assetLook("char", "coat"))?.kind).isEqualTo(ReviewKind.REJECT)
        assertThat(oneLookFixed.production.assets.first { it.id == "char" }.status).isEqualTo(AssetStatus.REDO)

        val bothLooksFixed = ProductionEngine.update(
            oneLookFixed,
            listOf(Json.parseToJsonElement("""{"op":"set_look_slot","asset_id":"char","look_id":"coat","slot_id":"side","note":"补充风衣侧面"}""") as JsonObject),
            oneLookFixed.revision,
            65,
        ) { true }
        assertThat(bothLooksFixed.production.verdictOf(ReviewTarget.assetLook("char", "coat"))).isNull()
        assertThat(bothLooksFixed.production.assets.first { it.id == "char" }.status).isEqualTo(AssetStatus.REVIEW)
    }

    @Test fun `旧快照残留已解决的已发送就地否决时读侧同样按待审计算`() {
        val target = ReviewTarget.script("S1E01")
        val sent = reviewSend(reviewApply(reviewFixture(), ProductionUserAction.Review(target, ReviewKind.REJECT, "改剧本")), 1, 30)
        val staleResolved = sent.production.copy(
            seasons = sent.production.seasons.map { s ->
                s.copy(episodes = s.episodes.map { ep -> ep.copy(contentChangedAt = sent.revision + 1) })
            },
        )
        assertThat(staleResolved.reviews.any { it.target == target && it.kind == ReviewKind.REJECT }).isTrue()
        assertThat(staleResolved.currentReviewVerdicts().none { it.target == target }).isTrue()
        assertThat(staleResolved.verdictOf(target)).isNull()
    }

    @Test fun `未发送草稿与已排队裁决和不可变对象及已批准结论不会被内容更新清除`() {
        val sceneTarget = ReviewTarget.scene("S1E01", "SC01")
        val updateSceneOp = listOf(Json.parseToJsonElement("""{"op":"upsert_scene","episode_id":"S1E01","code":"SC01","number":1,"heading":"码头 雨夜"}""") as JsonObject)

        // 未发送否决草稿不因更新清除。
        val unsentReject = reviewApply(reviewFixture(), ProductionUserAction.Review(sceneTarget, ReviewKind.REJECT, "待发否决"))
        val afterUnsent = ProductionEngine.update(unsentReject, updateSceneOp, unsentReject.revision, 50) { true }
        assertThat(afterUnsent.production.verdictOf(sceneTarget)?.kind).isEqualTo(ReviewKind.REJECT)
        assertThat(afterUnsent.production.verdictOf(sceneTarget)?.round).isNull()

        // 已排队否决不因更新清除。
        val queuedReject = reviewApply(unsentReject, ProductionUserAction.MarkReviewQueued(1, 45))
        val afterQueued = ProductionEngine.update(queuedReject, updateSceneOp, queuedReject.revision, 50) { true }
        assertThat(afterQueued.production.verdictOf(sceneTarget)?.queuedRound).isEqualTo(1)

        // 上轮已发送否决 + 本轮新本地未发送批准草稿：内容更新不得删除新草稿。
        val sentReject = reviewSend(unsentReject, 1, 40)
        val replacedDraft = reviewApply(sentReject, ProductionUserAction.Review(sceneTarget, ReviewKind.APPROVE, "暂存批准"))
        val afterReplaced = ProductionEngine.update(replacedDraft, updateSceneOp, replacedDraft.revision, 55) { true }
        assertThat(afterReplaced.production.verdictOf(sceneTarget)?.kind).isEqualTo(ReviewKind.APPROVE)
        assertThat(afterReplaced.production.verdictOf(sceneTarget)?.round).isNull()

        // 已发送批准结论不因非解锁性文案修订被清除。
        val sentApprove = reviewSend(reviewApply(reviewFixture(), ProductionUserAction.Review(sceneTarget, ReviewKind.APPROVE, "通过")), 1, 40)
        val afterApprove = ProductionEngine.update(sentApprove, updateSceneOp, sentApprove.revision, 55) { true }
        assertThat(afterApprove.production.verdictOf(sceneTarget)?.kind).isEqualTo(ReviewKind.APPROVE)

        // 不可变对象：TAKE 与 ASSET_VERSION 的旧结论始终保留在目标自身上。
        val takeTarget = ReviewTarget.take("S1E01", "SC01-01", "t1")
        val assetVerTarget = ReviewTarget.assetVersion("char", 1)
        var immutable = reviewApply(reviewFixture(), ProductionUserAction.Review(takeTarget, ReviewKind.REJECT, "糊了"))
        immutable = reviewApply(immutable, ProductionUserAction.Review(assetVerTarget, ReviewKind.REJECT, "脸崩"))
        immutable = reviewSend(immutable, 1, 40)
        val afterNewAssetVer = ProductionEngine.update(
            immutable,
            listOf(Json.parseToJsonElement("""{"op":"add_asset_version","asset_id":"char","path":"p/3.png"}""") as JsonObject),
            immutable.revision,
            60,
        ) { true }
        val nextRev = afterNewAssetVer.revision + 1
        val withNewTake = stampProductionContent(
            afterNewAssetVer.production,
            afterNewAssetVer.production.mapShot("S1E01", "SC01-01") {
                it.copy(takes = it.takes + Take("t3", "p/3.mp4", TakeSource("服务"), 61))
            },
            nextRev,
        ).reopenResolvedSentRejections()
        assertThat(withNewTake.verdictOf(takeTarget)?.kind).isEqualTo(ReviewKind.REJECT)
        assertThat(withNewTake.verdictOf(assetVerTarget)?.kind).isEqualTo(ReviewKind.REJECT)
    }

    @Test fun `剧本摘要往返校验通过且非法摘要被清单拒绝`() {
        val validHash = "a1b2c3d4".repeat(8)
        val base = reviewFixture().production
        val withHash = base.copy(
            seasons = base.seasons.map { s -> s.copy(episodes = s.episodes.map { it.copy(scriptSha256 = validHash) }) },
        )
        val bytes = ProductionManifestCodec.encode(withHash, 3)
        val decoded = ProductionManifestCodec.decode(bytes)
        assertThat(decoded.production.reviewEpisode("S1E01")?.scriptSha256).isEqualTo(validHash)

        listOf("abc", "A".repeat(64), "g".repeat(64)).forEach { badHash ->
            val bad = base.copy(
                seasons = base.seasons.map { s -> s.copy(episodes = s.episodes.map { it.copy(scriptSha256 = badHash) }) },
            )
            assertThrows(IllegalArgumentException::class.java) { ProductionManifestCodec.validate(bad) }
        }
    }
}
