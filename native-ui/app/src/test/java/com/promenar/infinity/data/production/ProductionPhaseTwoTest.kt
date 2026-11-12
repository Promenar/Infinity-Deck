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

    @Test fun `锁定场修改内容待审核而规划直接生效`() {
        val s = fixture(true)
        val changed = update(s, """{"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","title":"新标题","channel_id":"video-a"}""")
        assertThat(shot(changed).title).isEqualTo("镜头")
        assertThat(shot(changed).channelId).isEqualTo("video-a")
        val id = changed.production.changeRequests.single().id
        val approved = ProductionUserEngine.apply(changed, changed.revision, ProductionUserAction.Review(ReviewTarget.change(id), ReviewKind.APPROVE, null), 3)
        assertThat(shot(approved).title).isEqualTo("新标题")
        val rejected = ProductionUserEngine.apply(changed, changed.revision, ProductionUserAction.Review(ReviewTarget.change(id), ReviewKind.REJECT, null), 3)
        assertThat(shot(rejected).title).isEqualTo("镜头")
        assertThat(rejected.production.changeRequests.single().id).isEqualTo(id)
        assertThat(rejected.production.changeRequests.single().rejected).isTrue()
        assertThat(rejected.production.changeRequests.single().approved).isFalse()
        assertThat(rejected.production.changeRequests.single().changedBy).isEqualTo(ChangeSource.USER)
        assertThat(rejected.production.changeRequests.single().changedAt).isEqualTo(rejected.revision)
    }

    @Test fun `预算操作作为未知操作拒绝且不改变快照`() {
        val initial = fixture()
        for (cents in listOf("1000", "\"100\"")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                update(initial, """{"op":"set_budget","cents":$cents}""")
            }
            assertThat(error.message).contains("未知 op")
        }
        assertThat(initial).isEqualTo(fixture())
    }

    @Test fun `用户改写提示词受到保护`() {
        val s = fixture(true)
        val edited = ProductionUserEngine.apply(s, 1, ProductionUserAction.EditPrompt("S1E01", "SC01-01", "用户文案"), 2)
        val result = update(edited, """{"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","prompt":"智能体文案"}""")
        assertThat(shot(result).prompt).isEqualTo("用户文案")
    }

    @Test fun `锁定场用户编辑待审核且旧值冲突不能通过`() {
        val locked = fixture(true)
        val edited = ProductionUserEngine.apply(locked, 1, ProductionUserAction.EditShotContent("S1E01", "SC01-01", shot(locked).copy(title = "用户")), 2)
        assertThat(shot(edited)).isEqualTo(shot(locked))
        val userRequest = edited.production.changeRequests.single()
        assertThat(userRequest.approved).isFalse()
        assertThat(userRequest.source).isEqualTo(ChangeSource.USER)
        val pending = update(edited, """{"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","title":"智能体"}""")
        assertThat(shot(pending).title).isEqualTo("镜头")
        assertThat(pending.production.changeRequests).hasSize(2)
        val approved = ProductionUserEngine.apply(pending, pending.revision, ProductionUserAction.Review(ReviewTarget.change(userRequest.id), ReviewKind.APPROVE, null), 3)
        assertThat(shot(approved).title).isEqualTo("用户")
        val agentRequest = approved.production.changeRequests.first { it.source == ChangeSource.AGENT }
        assertThrows(IllegalArgumentException::class.java) { ProductionUserEngine.apply(approved, approved.revision, ProductionUserAction.Review(ReviewTarget.change(agentRequest.id), ReviewKind.APPROVE, null), 4) }
    }

    @Test fun `用户内容审核可通过或否决且修订号冲突不写入`() {
        val initial = fixture(true)
        val action = ProductionUserAction.EditShotContent("S1E01", "SC01-01", shot(initial).copy(title = "修改", visual = "雨夜"))
        val pending = ProductionUserEngine.apply(initial, 1, action, 2)
        val request = pending.production.changeRequests.single()
        assertThat(request.changes.keys).containsExactly("title", "visual")
        assertThrows(IllegalArgumentException::class.java) { ProductionUserEngine.apply(pending, 1, action, 3) }
        assertThrows(IllegalArgumentException::class.java) { ProductionUserEngine.apply(pending, 1, ProductionUserAction.Review(ReviewTarget.change(request.id), ReviewKind.APPROVE, null), 3) }
        val approved = ProductionUserEngine.apply(pending, 2, ProductionUserAction.Review(ReviewTarget.change(request.id), ReviewKind.APPROVE, null), 3)
        assertThat(shot(approved).title).isEqualTo("修改")
        assertThat(shot(approved).visual).isEqualTo("雨夜")
        assertThat(approved.production.changeRequests.single().approved).isTrue()
        val rejected = ProductionUserEngine.apply(pending, 2, ProductionUserAction.Review(ReviewTarget.change(request.id), ReviewKind.REJECT, null), 3)
        assertThat(shot(rejected)).isEqualTo(shot(initial))
        val rejectedRequest = rejected.production.changeRequests.single()
        assertThat(rejectedRequest.id).isEqualTo(request.id)
        assertThat(rejectedRequest.rejected).isTrue()
        assertThat(rejectedRequest.approved).isFalse()
        assertThat(rejectedRequest.changedBy).isEqualTo(ChangeSource.USER)
        assertThat(rejectedRequest.changedAt).isEqualTo(rejected.revision)
        assertThat(rejected.production.reviews.single().kind).isEqualTo(ReviewKind.REJECT)
        assertThat(pending.production.changeRequests.single().approved).isFalse()
    }

    @Test fun `未锁定场用户编辑直接写内容并保留规划候选`() {
        val initial = fixture()
        val old = shot(initial)
        val edited = ProductionUserEngine.apply(initial, 1, ProductionUserAction.EditShotContent("S1E01", old.code, old.copy(title = "修改", channelId = "ignored", prompt = "ignored")), 2)
        assertThat(shot(edited).title).isEqualTo("修改")
        assertThat(shot(edited).channelId).isEqualTo(old.channelId)
        assertThat(shot(edited).prompt).isEqualTo(old.prompt)
        assertThat(shot(edited).changedAt).isEqualTo(2)
        assertThat(edited.production.changeRequests).isEmpty()
    }

    @Test fun `锁定场对白来源作为规划直接调整且越界被拒绝`() {
        val initial = fixture()
        val withContent = ProductionUserEngine.apply(initial, 1, ProductionUserAction.EditShotContent("S1E01", "SC01-01", shot(initial).copy(dialogue = listOf(
            ShotDialogue("char-a", "甲"), ShotDialogue("char-b", "乙")
        ))), 2)
        val locked = ProductionUserEngine.apply(withContent, 2, ProductionUserAction.Review(ReviewTarget.scene("S1E01", "SC01"), ReviewKind.APPROVE, null), 3)
        val changed = ProductionUserEngine.apply(locked, 3, ProductionUserAction.SetDialogueSource("S1E01", "SC01-01", 1, DialogueAudioSource.DUBBED), 4)
        assertThat(shot(changed).dialogue.first()).isEqualTo(shot(locked).dialogue.first())
        assertThat(shot(changed).dialogue.last()).isEqualTo(ShotDialogue("char-b", "乙", audioSource = DialogueAudioSource.DUBBED))
        assertThat(changed.production.changeRequests).isEmpty()
        assertThat(shot(changed).changedBy).isEqualTo(ChangeSource.USER)
        listOf(-1, 2).forEach { index ->
            assertThrows(IllegalArgumentException::class.java) { ProductionUserEngine.apply(changed, 4, ProductionUserAction.SetDialogueSource("S1E01", "SC01-01", index, DialogueAudioSource.NATIVE), 5) }
        }
        assertThat(changed.revision).isEqualTo(4)
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
