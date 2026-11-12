package com.promenar.infinity.data.production

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProductionPhaseTwoTest {
    private fun fixture(locked: Boolean = false): ProductionSnapshot {
        val shot = Shot("SC01-01", "镜头", durationSeconds = 3.0)
        val scene = Scene("SC01", 1, "场", storyboardLocked = locked, shots = listOf(shot))
        val episode = Episode("S1E01", 1, "集", scenes = listOf(scene))
        return ProductionSnapshot(Production("p", "项目", ProductionKind.FILM, "p", seasons = listOf(Season("S1", 1, episodes = listOf(episode))), createdAt = 1, updatedAt = 1), 1, ManifestSync.InSync)
    }
    private fun update(s: ProductionSnapshot, op: String) = ProductionEngine.update(s, Json.parseToJsonElement("[$op]").jsonArray.map { it.jsonObject }, s.revision, 2) { true }
    private fun shot(s: ProductionSnapshot) = s.production.seasons.single().episodes.single().scenes.single().shots.single()

    @Test fun `未锁场拒绝候选且试验项目可跳过`() {
        val s = fixture()
        val op = """{"op":"add_take","episode_id":"S1E01","shot_code":"SC01-01","path":"a.mp4","service":"服务"}"""
        assertThat(ProductionRules.generationRejection(s.production, "S1E01", "SC01-01")).isNotNull()
        assertThrows(IllegalArgumentException::class.java) { update(s, op) }
        val trial = ProductionUserEngine.apply(s, 1, ProductionUserAction.SetExperimental(true), 2)
        assertThat(shot(update(trial, op)).takes).hasSize(1)
    }

    @Test fun `锁定场修改内容待审批而规划直接生效`() {
        val s = fixture(true)
        val changed = update(s, """{"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","title":"新标题","channel_id":"video-a"}""")
        assertThat(shot(changed).title).isEqualTo("镜头")
        assertThat(shot(changed).channelId).isEqualTo("video-a")
        val id = changed.production.changeRequests.single().id
        val approved = ProductionUserEngine.apply(changed, changed.revision, ProductionUserAction.ResolveChange(id, true), 3)
        assertThat(shot(approved).title).isEqualTo("新标题")
        val rejected = ProductionUserEngine.apply(changed, changed.revision, ProductionUserAction.ResolveChange(id, false), 3)
        assertThat(shot(rejected).title).isEqualTo("镜头")
        assertThat(rejected.production.changeRequests).isEmpty()
    }

    @Test fun `预算参数校验与用户决定保护`() {
        val s = fixture()
        val budget = ProductionUserEngine.apply(s, 1, ProductionUserAction.SetBudget(1000), 2)
        assertThat(budget.production.budgetCents).isEqualTo(1000)
        val invalid = """[{"op":"set_budget","cents":"100"}]"""
        val error = assertThrows(IllegalArgumentException::class.java) { ProductionEngine.update(budget, Json.parseToJsonElement(invalid).jsonArray.map { it.jsonObject }, budget.revision, 3) { true } }
        assertThat(error.message).contains("cents")
        val valid = """[{"op":"set_budget","cents":2000}]"""
        assertThrows(IllegalArgumentException::class.java) { ProductionEngine.update(budget, Json.parseToJsonElement(valid).jsonArray.map { it.jsonObject }, 1, 3) { true } }
    }

    @Test fun `用户改写提示词受到保护`() {
        val s = fixture(true)
        val edited = ProductionUserEngine.apply(s, 1, ProductionUserAction.EditPrompt("S1E01", "SC01-01", "用户文案"), 2)
        val result = update(edited, """{"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","prompt":"智能体文案"}""")
        assertThat(shot(result).prompt).isEqualTo("用户文案")
    }

    @Test fun `锁定场用户编辑记录已确认变更且旧值冲突不能批准`() {
        val locked = fixture(true)
        val edited = ProductionUserEngine.apply(locked, 1, ProductionUserAction.EditShotContent("S1E01", "SC01-01", shot(locked).copy(title = "用户")), 2)
        assertThat(shot(edited).title).isEqualTo("用户")
        assertThat(edited.production.changeRequests.single().approved).isTrue()
        val pending = update(locked, """{"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","title":"智能体"}""")
        val changed = ProductionUserEngine.apply(pending, pending.revision, ProductionUserAction.EditShotContent("S1E01", "SC01-01", shot(pending).copy(title = "用户")), 3)
        assertThrows(IllegalArgumentException::class.java) { ProductionUserEngine.apply(changed, changed.revision, ProductionUserAction.ResolveChange(pending.production.changeRequests.single().id, true), 4) }
    }

    @Test fun `候选保存规划快照`() {
        val planned = update(fixture(true), """{"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","channel_id":"channel","mode":"reference","prompt":"描述"}""")
        val result = update(planned, """{"op":"add_take","episode_id":"S1E01","shot_code":"SC01-01","path":"a.mp4","service":"服务"}""")
        val plan = shot(result).takes.single().planSnapshot!!
        assertThat(plan.channelId).isEqualTo("channel")
        assertThat(plan.mode).isEqualTo(ShotMode.REFERENCE)
        assertThat(plan.prompt).isEqualTo("描述")
    }

    @Test fun `用户清除改写标记后允许更新`() {
        val edited = ProductionUserEngine.apply(fixture(), 1, ProductionUserAction.EditPrompt("S1E01", "SC01-01", "用户"), 2)
        val cleared = ProductionUserEngine.apply(edited, edited.revision, ProductionUserAction.ClearPromptEdited("S1E01", "SC01-01"), 3)
        assertThat(shot(update(cleared, """{"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","prompt":"新文案"}""")).prompt).isEqualTo("新文案")
    }

    @Test fun `音频与图像视频均可作为媒体`() {
        assertThat(isProductionMediaKind(com.promenar.infinity.domain.media.MediaKind.AUDIO)).isTrue()
        assertThat(isProductionMediaKind(com.promenar.infinity.domain.media.MediaKind.IMAGE)).isTrue()
        assertThat(isProductionMediaKind(com.promenar.infinity.domain.media.MediaKind.VIDEO)).isTrue()
        assertThat(isProductionMediaKind(com.promenar.infinity.domain.media.MediaKind.FILE)).isFalse()
    }

    @Test fun `造型默认位不可删除并可增删自定义位`() {
        val outfit = CharacterLook("look", "造型")
        assertThat(outfit.slots.map { it.id }).containsExactly("front", "side", "back").inOrder()
        assertThrows(IllegalArgumentException::class.java) { ProductionRules.removeSlot(outfit, "front") }
        val extra = outfit.copy(slots = outfit.slots + ReferenceSlot("detail", "细节"))
        assertThat(ProductionRules.removeSlot(extra, "detail").slots).hasSize(3)
    }
}
