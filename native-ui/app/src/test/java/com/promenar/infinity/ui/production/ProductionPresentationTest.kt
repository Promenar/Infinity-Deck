package com.promenar.infinity.ui.production

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.production.AssetStatus
import com.promenar.infinity.data.production.ProductionStage
import com.promenar.infinity.data.production.ShotStatus
import com.promenar.infinity.data.production.StageState
import com.promenar.infinity.data.production.StageStatus
import com.promenar.infinity.data.production.Take
import com.promenar.infinity.data.production.TakeSource
import org.junit.jupiter.api.Test

class ProductionPresentationTest {
    private val production = ProductionSamples.production

    @Test
    fun `镜头统计按状态计数且已定比例只算锁定`() {
        val stats = production.shotStats()
        assertThat(stats).isEqualTo(ShotStats(total = 5, todo = 1, generating = 1, review = 1, locked = 1, redo = 1))
        assertThat(stats.progress).isWithin(1e-6f).of(0.2f)
        assertThat(ShotStats.Empty.progress).isEqualTo(0f)
    }

    @Test
    fun `待处理先列待审镜头再列待审资产`() {
        val pending = production.pendingItems()
        assertThat(pending.map { item ->
            when (item) {
                is PendingItem.ShotReview -> item.location.shot.code
                is PendingItem.AssetReview -> item.asset.id
            }
        }).containsExactly("SC01-02", "char-zhou").inOrder()
        assertThat(production.pendingCount()).isEqualTo(2)
        assertThat(production.isGenerating()).isTrue()
        assertThat(production.redoShots().map { it.shot.code }).containsExactly("SC01-03")
    }

    @Test
    fun `当前阶段跳过已锁定与已跳过的阶段`() {
        assertThat(production.currentStage()).isEqualTo(ProductionStage.STORYBOARD)
        val allDone = production.copy(stages = production.stages.map { StageState(it.stage, StageStatus.LOCKED) })
        assertThat(allDone.currentStage()).isNull()
    }

    @Test
    fun `关闭动态预演时不展示该阶段`() {
        assertThat(production.visibleStages().map { it.stage }).contains(ProductionStage.ANIMATIC)
        assertThat(production.copy(previewEnabled = false).visibleStages().map { it.stage }).doesNotContain(ProductionStage.ANIMATIC)
    }

    @Test
    fun `展示候选优先已选定否则最新`() {
        val review = production.findShot("S1E01", "SC01-02")!!.shot
        assertThat(review.selectedTake()).isNull()
        assertThat(review.displayTake()?.id).isEqualTo("t03")
        val locked = production.findShot("S1E01", "SC01-01")!!.shot
        assertThat(locked.displayTake()?.id).isEqualTo("t01")
        val older = review.copy(takes = review.takes + Take("t00", "a.mp4", TakeSource("x"), createdAt = 0), selectedTakeId = "t02")
        assertThat(older.displayTake()?.id).isEqualTo("t02")
    }

    @Test
    fun `缩略图依次取候选预演与首个输入`() {
        val redo = production.findShot("S1E01", "SC01-03")!!.shot
        assertThat(redo.thumbnailPath()).isEqualTo("productions/harbor/keyframes/sc01-03-a.png")
        val todo = production.findShot("S1E01", "SC02-02")!!.shot
        assertThat(todo.thumbnailPath()).isNull()
        val review = production.findShot("S1E01", "SC01-02")!!.shot
        assertThat(review.copy(takes = emptyList()).thumbnailPath()).isEqualTo(review.previewPath)
    }

    @Test
    fun `镜头操作随状态与候选变化`() {
        val review = production.findShot("S1E01", "SC01-02")!!.shot
        assertThat(review.actions("t02")).isEqualTo(ShotActions(canSelect = true, canRedo = true, canUnlock = false, canEditPrompt = true))
        assertThat(review.actions("missing").canSelect).isFalse()
        assertThat(review.actions(null).canSelect).isFalse()

        val locked = production.findShot("S1E01", "SC01-01")!!.shot
        assertThat(locked.actions("t01")).isEqualTo(ShotActions(canSelect = false, canRedo = false, canUnlock = true, canEditPrompt = false))

        val generating = review.copy(status = ShotStatus.GENERATING)
        assertThat(generating.actions("t02").canSelect).isFalse()
        assertThat(generating.actions("t02").canRedo).isFalse()

        val redo = review.copy(status = ShotStatus.REDO)
        assertThat(redo.actions("t02").canRedo).isFalse()
        assertThat(redo.actions("t02").canSelect).isTrue()
    }

    @Test
    fun `资产操作要求有版本才能通过`() {
        val locked = production.findAsset("char-linwan")!!
        assertThat(locked.actions()).isEqualTo(AssetActions(canApprove = false, canRedo = false, canUnlock = true))
        val review = production.findAsset("char-zhou")!!
        assertThat(review.actions()).isEqualTo(AssetActions(canApprove = true, canRedo = true, canUnlock = false))
        val noVersion = production.findAsset("prop-letter")!!
        assertThat(noVersion.actions().canApprove).isFalse()
        assertThat(noVersion.actions().canRedo).isFalse()
        assertThat(noVersion.copy(status = AssetStatus.DRAFT).actions().canRedo).isTrue()
    }

    @Test
    fun `资产当前版本与引用计数`() {
        val asset = production.findAsset("char-linwan")!!
        assertThat(asset.currentPath()).isEqualTo("productions/harbor/assets/linwan-v2.png")
        assertThat(asset.copy(currentVersion = null).currentPath()).isEqualTo("productions/harbor/assets/linwan-v2.png")
        assertThat(production.findAsset("prop-letter")!!.currentPath()).isNull()
        assertThat(production.shotsUsingAsset("char-linwan")).isEqualTo(1)
        assertThat(production.shotsUsingAsset("loc-pier")).isEqualTo(0)
    }

    @Test
    fun `分镜默认打开有待审镜头的集`() {
        assertThat(production.defaultEpisodeId()).isEqualTo("S1E01")
        val noPending = production.copy(seasons = production.seasons.map { season ->
            season.copy(episodes = season.episodes.reversed())
        })
        assertThat(noPending.defaultEpisodeId()).isEqualTo("S1E01")
        assertThat(production.copy(seasons = emptyList()).defaultEpisodeId()).isNull()
    }

    @Test
    fun `阶段菜单只有动态预演可跳过`() {
        assertThat(stageChoices(ProductionStage.ANIMATIC)).contains(StageStatus.SKIPPED)
        assertThat(stageChoices(ProductionStage.GENERATION)).doesNotContain(StageStatus.SKIPPED)
    }

    @Test
    fun `入口状态区分从未绑定已解绑与进行中`() {
        assertThat(productionEntryState(null, everBound = false)).isEqualTo(ProductionEntryState.Disabled)
        assertThat(productionEntryState(null, everBound = true))
            .isEqualTo(ProductionEntryState(enabled = true, hasProduction = false, pending = 0, running = false))
        assertThat(productionEntryState(ProductionSamples.snapshot(), everBound = true))
            .isEqualTo(ProductionEntryState(enabled = true, hasProduction = true, pending = 2, running = true, title = ProductionSamples.snapshot().production?.title))
    }

    @Test
    fun `时长整数秒不带小数`() {
        assertThat(formatShotSeconds(5.0)).isEqualTo("5")
        assertThat(formatShotSeconds(4.5)).isEqualTo("4.5")
        assertThat(formatShotSeconds(2.04)).isEqualTo("2")
        assertThat(formatShotSeconds(2.06)).isEqualTo("2.1")
    }
}
