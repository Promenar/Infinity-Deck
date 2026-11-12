package com.promenar.infinity.data.production

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.mediagen.*
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
        val op = kotlinx.serialization.json.Json.parseToJsonElement("""{"op":"upsert_episode","season_id":"S1","id":"S1E01","number":1,"title":"雾港","script_path":"p/script.md"}""") as kotlinx.serialization.json.JsonObject
        val changed = ProductionEngine.update(s, listOf(op), s.revision, 60) { true }
        assertThat(changed.production.reviewEpisode("S1E01")!!.changedAt).isEqualTo(changed.revision)
        assertThat(isSentRejectionUnresolved(changed.production, old)).isFalse()
    }
}
