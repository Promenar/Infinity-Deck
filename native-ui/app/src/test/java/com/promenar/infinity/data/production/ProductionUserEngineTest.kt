package com.promenar.infinity.data.production

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProductionUserEngineTest {
    private val take = Take("t1", "media/a.mp4", TakeSource("服务"), 1)
    private fun snapshot() = ProductionSnapshot(
        Production("p", "项目", ProductionKind.SERIES, "productions/p", createdAt = 1, updatedAt = 1,
            seasons = listOf(Season("S1", 1, episodes = listOf(Episode("S1E01", 1, "集", scenes = listOf(
                Scene("SC01", 1, "场", shots = listOf(Shot("SC01-01", "镜头", durationSeconds = 2.0, takes = listOf(take))))
            ))))), assets = listOf(Asset("a", AssetKind.PROP, "道具"))), 1, ManifestSync.InSync)
    private fun shot(s: ProductionSnapshot) = s.production.seasons.single().episodes.single().scenes.single().shots.single()
    private fun apply(s: ProductionSnapshot, action: ProductionUserAction) = ProductionUserEngine.apply(s, s.revision, action, 10)

    @Test fun `选定打回解锁与编辑遵守锁定和修订`() {
        val initial = snapshot()
        val selected = apply(initial, ProductionUserAction.SelectTake("S1E01", "SC01-01", "t1"))
        assertThat(shot(selected).status).isEqualTo(ShotStatus.LOCKED)
        assertThat(shot(selected).selectedTakeId).isEqualTo("t1")
        assertThat(shot(selected).changedBy).isEqualTo(ChangeSource.USER)
        assertThat(shot(selected).changedAt).isEqualTo(2)
        assertThrows(IllegalArgumentException::class.java) { apply(selected, ProductionUserAction.EditPrompt("S1E01", "SC01-01", "提示")) }
        val conflict = assertThrows(IllegalArgumentException::class.java) { ProductionUserEngine.apply(selected, 1, ProductionUserAction.UnlockShot("S1E01", "SC01-01"), 10) }
        assertThat(conflict.message).isEqualTo("面板已更新，请重试。")
        val unlocked = apply(selected, ProductionUserAction.UnlockShot("S1E01", "SC01-01"))
        assertThat(shot(unlocked).status).isEqualTo(ShotStatus.REVIEW)
        val edited = apply(unlocked, ProductionUserAction.EditPrompt("S1E01", "SC01-01", "提示"))
        assertThat(shot(edited).prompt).isEqualTo("提示")
        val redo = apply(edited, ProductionUserAction.RequestRedo("S1E01", "SC01-01", "重做"))
        assertThat(shot(redo).status).isEqualTo(ShotStatus.REDO)
        assertThat(shot(redo).selectedTakeId).isNull()
        assertThat(shot(redo).userNote).isEqualTo("重做")
        assertThat(shot(initial).status).isEqualTo(ShotStatus.TODO)
    }

    @Test fun `用户可锁定场剧本资产与阶段`() {
        var s = snapshot()
        s = apply(s, ProductionUserAction.LockScene("S1E01", "SC01", true))
        s = apply(s, ProductionUserAction.LockScript("S1E01", true))
        s = apply(s, ProductionUserAction.SetAssetStatus("a", AssetStatus.LOCKED, null))
        s = apply(s, ProductionUserAction.SetStage(ProductionStage.SCRIPT, StageStatus.LOCKED))
        val episode = s.production.seasons.single().episodes.single()
        assertThat(episode.scriptLocked).isTrue()
        assertThat(episode.scenes.single().storyboardLocked).isTrue()
        assertThat(s.production.assets.single().status).isEqualTo(AssetStatus.LOCKED)
        assertThat(s.production.stages.first { it.stage == ProductionStage.SCRIPT }.status).isEqualTo(StageStatus.LOCKED)
        assertThat(s.revision).isEqualTo(5)
    }
}
