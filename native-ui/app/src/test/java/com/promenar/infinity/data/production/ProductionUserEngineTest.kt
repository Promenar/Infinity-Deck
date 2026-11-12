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
        assertThat(shot(apply(selected, ProductionUserAction.EditPrompt("S1E01", "SC01-01", "提示"))).promptUserEdited).isTrue()
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
    @Test fun `新增镜头按场内最大编号递增并保留规划默认值`() {
        val initial = snapshot()
        val result = apply(initial, ProductionUserAction.AddShot("S1E01", "SC01", "新镜头"))
        val added = result.production.seasons.single().episodes.single().scenes.single().shots.last()
        assertThat(added.code).isEqualTo("SC01-02")
        assertThat(added.title).isEqualTo("新镜头")
        assertThat(added.durationSeconds).isGreaterThan(0.0)
        assertThat(added.channelId).isNull()
        assertThat(added.mode).isEqualTo(ShotMode.TEXT)
        assertThat(added.appearances).isEmpty()
        assertThat(added.changedBy).isEqualTo(ChangeSource.USER)
        assertThat(added.changedAt).isEqualTo(2)
        assertThat(result.production.seasons.single().episodes.single().scenes.single().shots.first()).isEqualTo(shot(initial))
    }

    @Test fun `新增镜头拒绝锁定场或过期修订且空场从零一开始`() {
        val initial = snapshot()
        val locked = apply(initial, ProductionUserAction.LockScene("S1E01", "SC01", true))
        assertThrows(IllegalArgumentException::class.java) { apply(locked, ProductionUserAction.AddShot("S1E01", "SC01", "新镜头")) }
        assertThrows(IllegalArgumentException::class.java) { ProductionUserEngine.apply(locked, 1, ProductionUserAction.AddShot("S1E01", "SC01", "新镜头"), 10) }
        assertThrows(IllegalArgumentException::class.java) { apply(initial, ProductionUserAction.AddShot("S1E01", "SC02", "新镜头")) }
        assertThat(ProductionRules.nextShotCode(Scene("SC03", 3, "场"))).isEqualTo("SC03-01")
        assertThat(ProductionRules.nextShotCode(Scene("SC01", 1, "场", shots = listOf(
            Shot("SC01-009", "九", durationSeconds = 1.0), Shot("SC01-002", "二", durationSeconds = 1.0)
        )))).isEqualTo("SC01-010")
        assertThat(ProductionRules.nextShotCode(Scene("SC01", 1, "场", shots = listOf(Shot("SC01-99", "九十九", durationSeconds = 1.0))))).isEqualTo("SC01-100")
    }

    @Test fun `切换造型选中默认三位并允许用户减少参考位`() {
        val initial = snapshot().let { it.copy(production = it.production.copy(assets = listOf(Asset("char", AssetKind.CHARACTER, "角色", looks = listOf(CharacterLook("new", "新造型", slots = listOf(
            ReferenceSlot("detail", "细节"), ReferenceSlot("back", "背面"), ReferenceSlot("front", "正面"), ReferenceSlot("side", "侧面")
        ))))))) }
        val appeared = apply(initial, ProductionUserAction.SetAppearances("S1E01", "SC01-01", listOf(ShotAppearance("char", "new", listOf("detail")))))
        val switched = apply(appeared, ProductionUserAction.SetCharacterLook("S1E01", "SC01-01", "char", "new"))
        assertThat(shot(switched).appearances.single().referenceSlotIds).containsExactly("front", "side", "back").inOrder()
        val reduced = apply(switched, ProductionUserAction.ToggleReferenceSlot("S1E01", "SC01-01", "char", "side", false))
        assertThat(shot(reduced).appearances.single().referenceSlotIds).containsExactly("front", "back").inOrder()
        assertThat(ProductionRules.defaultSlotIds(CharacterLook("partial", "部分", listOf(ReferenceSlot("side", "侧面"), ReferenceSlot("extra", "细节"))))).containsExactly("side")
    }

}
