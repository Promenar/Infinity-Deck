package com.promenar.infinity.data.production

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.mediagen.CharacterReferenceMode
import com.promenar.infinity.domain.mediagen.GenerationMode
import com.promenar.infinity.domain.mediagen.MediaCapabilities
import com.promenar.infinity.domain.mediagen.MediaChannel
import com.promenar.infinity.domain.mediagen.MediaGroup
import com.promenar.infinity.ui.production.findAsset
import com.promenar.infinity.ui.production.findShot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProductionDualModeTest {
    private fun ops(text: String) = Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }
    private fun update(s: ProductionSnapshot, text: String, seen: Long = s.revision, now: Long = s.production.updatedAt + 1) =
        ProductionEngine.update(s, ops(text), seen, now) { true }

    private fun baseFixture(): ProductionSnapshot {
        val initial = ProductionSnapshot(
            Production("p", "雾港", ProductionKind.SERIES, "productions/p", createdAt = 1, updatedAt = 1, experimental = true),
            1,
            ManifestSync.InSync,
        )
        return update(
            initial,
            """[
                {"op":"upsert_season","id":"S1","number":1},
                {"op":"upsert_episode","season_id":"S1","id":"S1E01","number":1,"title":"雾港"},
                {"op":"upsert_scene","episode_id":"S1E01","code":"SC01","number":1,"heading":"码头 夜"},
                {"op":"upsert_asset","id":"lin","kind":"character","name":"林晚"},
                {"op":"upsert_asset","id":"pier","kind":"location","name":"雾港码头"}
            ]""",
        )
    }

    @Test fun `项目级默认视频通道与参考入参模式支持智能体及用户修改与清单往返`() {
        val s1 = update(
            baseFixture(),
            """[{"op":"set_video_settings","default_video_channel_id":"comfyui:h3-ref","reference_input_mode":"separate"}]""",
        )
        assertThat(s1.production.defaultVideoChannelId).isEqualTo("comfyui:h3-ref")
        assertThat(s1.production.referenceInputMode).isEqualTo(ReferenceInputMode.SEPARATE)

        val s2 = ProductionUserEngine.apply(
            s1,
            s1.revision,
            ProductionUserAction.SetVideoSettings("seedance:2.5", ReferenceInputMode.SHEET),
            now = 20L,
        )
        assertThat(s2.production.defaultVideoChannelId).isEqualTo("seedance:2.5")
        assertThat(s2.production.referenceInputMode).isEqualTo(ReferenceInputMode.SHEET)
        assertThat(s2.production.policyChangedBy).isEqualTo(ChangeSource.USER)

        val roundTrip = ProductionManifestCodec.decode(ProductionManifestCodec.encode(s2.production, s2.revision)).production
        assertThat(roundTrip).isEqualTo(s2.production)

        val compositeChannel = MediaChannel("sd", MediaGroup.VIDEO, MediaCapabilities(setOf(GenerationMode.REFERENCE), characterReferenceMode = CharacterReferenceMode.COMPOSITE))
        assertThat(s1.production.copy(referenceInputMode = null).effectiveReferenceInputMode(compositeChannel))
            .isEqualTo(ReferenceInputMode.SHEET)
        assertThat(s1.production.effectiveReferenceInputMode(compositeChannel))
            .isEqualTo(ReferenceInputMode.SEPARATE)
    }

    @Test fun `增量造型与槽位操作支持仅整卡、局部更新槽位及内容变更时间戳刷新`() {
        // 先只登记整卡，不强制填充三视图单图
        val withSheet = update(
            baseFixture(),
            """[
                {"op":"upsert_look","asset_id":"lin","look_id":"coat","name":"玄鳞飞鱼服","note":"正装造型","sheet_path":"ref/coat-sheet.png","sheet_note":"三视图整卡"}
            ]""",
        )
        val lookAfterSheet = withSheet.production.findAsset("lin")!!.looks.single()
        assertThat(lookAfterSheet.sheetPath).isEqualTo("ref/coat-sheet.png")
        assertThat(lookAfterSheet.sheetNote).isEqualTo("三视图整卡")
        assertThat(lookAfterSheet.populatedSlots()).isEmpty()
        assertThat(lookAfterSheet.slots.map { it.id }).containsExactly("front", "side", "back").inOrder()

        // 再用 set_look_slot 增量补入正面、侧面单图与自定义特写位
        val withSlots = update(
            withSheet,
            """[
                {"op":"set_look_slot","asset_id":"lin","look_id":"coat","slot_id":"front","path":"ref/coat-front.png","note":"正面标准立姿"},
                {"op":"set_look_slot","asset_id":"lin","look_id":"coat","slot_id":"closeup","name":"面部特写","path":"ref/coat-face.png"}
            ]""",
        )
        val updatedLook = withSlots.production.findAsset("lin")!!.looks.single()
        assertThat(updatedLook.sheetPath).isEqualTo("ref/coat-sheet.png")
        assertThat(updatedLook.slots.first { it.id == "front" }.path).isEqualTo("ref/coat-front.png")
        assertThat(updatedLook.slots.first { it.id == "front" }.note).isEqualTo("正面标准立姿")
        assertThat(updatedLook.slots.first { it.id == "closeup" }.name).isEqualTo("面部特写")
        assertThat(updatedLook.slots.first { it.id == "side" }.path).isNull()

        // 再通过 upsert_look 局部改名并改 back 槽位时，保留已有 front 与 closeup
        val partialLookUpdate = update(
            withSlots,
            """[
                {"op":"upsert_look","asset_id":"lin","look_id":"coat","name":"玄鳞飞鱼服 V3","slots":[{"id":"back","name":"背面全身","path":"ref/coat-back.png"}]}
            ]""",
        )
        val mergedLook = partialLookUpdate.production.findAsset("lin")!!.looks.single()
        assertThat(mergedLook.name).isEqualTo("玄鳞飞鱼服 V3")
        assertThat(mergedLook.sheetPath).isEqualTo("ref/coat-sheet.png")
        assertThat(mergedLook.slots.first { it.id == "front" }.path).isEqualTo("ref/coat-front.png")
        assertThat(mergedLook.slots.first { it.id == "closeup" }.path).isEqualTo("ref/coat-face.png")
        assertThat(mergedLook.slots.first { it.id == "back" }.path).isEqualTo("ref/coat-back.png")
    }

    @Test fun `分镜缺失 referenceSlotIds 自动补全现有有图位并尊重显式空数组`() {
        val withLooks = update(
            baseFixture(),
            """[
                {"op":"upsert_look","asset_id":"lin","look_id":"coat","name":"风衣"},
                {"op":"set_look_slot","asset_id":"lin","look_id":"coat","slot_id":"front","path":"ref/f.png"},
                {"op":"set_look_slot","asset_id":"lin","look_id":"coat","slot_id":"side","path":"ref/s.png"}
            ]""",
        )
        val omitted = update(
            withLooks,
            """[
                {"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","title":"出场","duration_seconds":4,"mode":"reference","appearances":[{"characterAssetId":"lin","lookId":"coat"}]}
            ]""",
        )
        val autoSlots = omitted.production.findShot("S1E01", "SC01-01")!!.shot.appearances.single().referenceSlotIds
        assertThat(autoSlots).containsExactly("front", "side").inOrder()

        val explicitEmpty = update(
            omitted,
            """[
                {"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","appearances":[{"characterAssetId":"lin","lookId":"coat","referenceSlotIds":[]}]}
            ]""",
        )
        assertThat(explicitEmpty.production.findShot("S1E01", "SC01-01")!!.shot.appearances.single().referenceSlotIds).isEmpty()
    }

    @Test fun `非角色视觉资产自由多视角增删与校验生效`() {
        val added = update(
            baseFixture(),
            """[
                {"op":"set_asset_view","asset_id":"pier","view_id":"wide","name":"晨雾全景","path":"ref/pier-wide.png","note":"主景别"},
                {"op":"set_asset_view","asset_id":"pier","view_id":"gate","name":"拱门近景","path":"ref/pier-gate.png"}
            ]""",
        )
        val pier = added.production.findAsset("pier")!!
        assertThat(pier.referenceViews.map { it.id }).containsExactly("wide", "gate").inOrder()
        assertThat(pier.coverPreviewPath()).isEqualTo("ref/pier-wide.png")

        // 用户增删视角与移除视角均正常工作，且拒绝向角色添加 referenceViews
        val userAdded = ProductionUserEngine.apply(
            added,
            added.revision,
            ProductionUserAction.AddAssetView("pier", ReferenceSlot("corner", "街角侧视")),
            now = 20L,
        )
        assertThat(userAdded.production.findAsset("pier")!!.referenceViews.map { it.id }).contains("corner")
        val userRemoved = ProductionUserEngine.apply(
            userAdded,
            userAdded.revision,
            ProductionUserAction.RemoveAssetView("pier", "corner"),
            now = 21L,
        )
        assertThat(userRemoved.production.findAsset("pier")!!.referenceViews.map { it.id }).containsExactly("wide", "gate")

        assertThrows(IllegalArgumentException::class.java) {
            update(added, """[{"op":"set_asset_view","asset_id":"lin","view_id":"extra","name":"越界"}]""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProductionUserEngine.apply(added, added.revision, ProductionUserAction.AddAssetView("lin", ReferenceSlot("x", "越界")), 30L)
        }
    }

    @Test fun `多造型与自由视角独立审批驱动资产定稿并按内容刷新清理旧驳回`() {
        val setup = update(
            baseFixture(),
            """[
                {"op":"upsert_look","asset_id":"lin","look_id":"coat","name":"风衣","sheet_path":"ref/coat-sheet.png"},
                {"op":"upsert_look","asset_id":"lin","look_id":"suit","name":"常服","sheet_path":"ref/suit-sheet.png"},
                {"op":"set_asset_view","asset_id":"pier","view_id":"wide","name":"全景","path":"ref/pier-wide.png"}
            ]""",
        )
        val coatTarget = ReviewTarget.assetLook("lin", "coat")
        val suitTarget = ReviewTarget.assetLook("lin", "suit")
        val viewsTarget = ReviewTarget.assetViews("pier")

        // 先批准风衣、否决常服、批准码头视角
        var s = reviewApply(setup, ProductionUserAction.Review(coatTarget, ReviewKind.APPROVE, null), now = 10L)
        s = reviewApply(s, ProductionUserAction.Review(suitTarget, ReviewKind.REJECT, "袖口需要收窄"), now = 11L)
        s = reviewApply(s, ProductionUserAction.Review(viewsTarget, ReviewKind.APPROVE, null), now = 12L)

        val lin1 = s.production.findAsset("lin")!!
        assertThat(lin1.looks.first { it.id == "coat" }.locked).isTrue()
        assertThat(lin1.looks.first { it.id == "suit" }.locked).isFalse()
        assertThat(lin1.status).isEqualTo(AssetStatus.REDO)
        assertThat(s.production.findAsset("pier")!!.viewsLocked).isTrue()
        assertThat(s.production.findAsset("pier")!!.status).isEqualTo(AssetStatus.LOCKED)

        // 发送审核并校验消息拼装涵盖 ASSET_LOOK 与 ASSET_VIEWS
        val strings = object : ReviewMessageStrings {
            override fun text(key: String, vararg args: Any): String = when (key) {
                "intro" -> "intro"
                "title" -> "轮${args[0]}"
                "approve" -> "批准"
                "reject" -> "否决"
                "suggest" -> "建议"
                "asset_look" -> "${args[0]} / 造型 ${args[1]}"
                "asset_views" -> "${args[0]} / 参考视角"
                else -> key
            }
        }
        val msg = ProductionReviewMessage.compose(s.production, emptyList(), strings)!!
        assertThat(msg).contains("林晚 / 造型 风衣")
        assertThat(msg).contains("林晚 / 造型 常服: 袖口需要收窄")
        assertThat(msg).contains("雾港码头 / 参考视角")

        s = reviewSend(s, round = 1, sentAt = 15L)

        // 智能体修改常服槽位后，该已发送否决自动结清（isSentRejectionUnresolved 为 false）；再由 set_asset_status 置为待审，风衣锁定不受影响
        val revised = update(
            s,
            """[
                {"op":"set_look_slot","asset_id":"lin","look_id":"suit","slot_id":"front","path":"ref/suit-front-v2.png"},
                {"op":"set_asset_status","asset_id":"lin","status":"review"}
            ]""",
            now = 20L,
        )
        val lin2 = revised.production.findAsset("lin")!!
        assertThat(lin2.looks.first { it.id == "coat" }.locked).isTrue()
        assertThat(lin2.looks.first { it.id == "suit" }.contentChangedAt).isEqualTo(revised.revision)
        assertThat(lin2.status).isEqualTo(AssetStatus.REVIEW)
        val sentSuitReject = revised.production.verdictOf(suitTarget)!!
        assertThat(isSentRejectionUnresolved(revised.production, sentSuitReject)).isFalse()

        // 再批准常服后整角色状态转为 LOCKED
        val allApproved = reviewApply(revised, ProductionUserAction.Review(suitTarget, ReviewKind.APPROVE, null), now = 21L)
        assertThat(allApproved.production.findAsset("lin")!!.status).isEqualTo(AssetStatus.LOCKED)

        // 撤回刚批准的常服结论恢复未锁定
        val retracted = reviewApply(allApproved, ProductionUserAction.RetractReview(suitTarget, ReviewKind.APPROVE), now = 22L)
        assertThat(retracted.production.findAsset("lin")!!.looks.first { it.id == "suit" }.locked).isFalse()
    }
}
