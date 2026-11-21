package com.promenar.infinity.data.production

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProductionLockApprovalTest {
    private fun ops(text: String): List<JsonObject> =
        Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }

    private fun sampleSnapshot(revision: Long = 5L): ProductionSnapshot {
        val look1 = CharacterLook(
            id = "casual",
            name = "常服",
            note = "青布长衫",
            sheetPath = "assets/lin_casual_sheet.png",
            slots = listOf(
                ReferenceSlot("front", "正面", "assets/lin_front.png", note = "正视"),
                ReferenceSlot("side", "侧面", "assets/lin_side.png"),
                ReferenceSlot("back", "背面", null),
            ),
            locked = true,
        )
        val look2 = CharacterLook(
            id = "night",
            name = "夜行服",
            sheetPath = "assets/lin_night_sheet.png",
            locked = true,
        )
        val charAsset = Asset(
            id = "char-lin",
            kind = AssetKind.CHARACTER,
            name = "林墨",
            description = "主角",
            looks = listOf(look1, look2),
            status = AssetStatus.LOCKED,
        )
        val locAsset = Asset(
            id = "loc-gate",
            kind = AssetKind.LOCATION,
            name = "城东老街",
            referenceViews = listOf(
                ReferenceSlot("wide", "全景", "assets/gate_wide.png", note = "晨光"),
            ),
            viewsLocked = true,
            status = AssetStatus.LOCKED,
        )
        val propAsset = Asset(
            id = "prop-bag",
            kind = AssetKind.PROP,
            name = "纸包",
            referenceViews = listOf(ReferenceSlot("front", "正面", "assets/bag.png")),
            viewsLocked = true,
            status = AssetStatus.LOCKED,
        )
        val draftAsset = Asset(
            id = "prop-unlocked",
            kind = AssetKind.PROP,
            name = "蒸笼",
            status = AssetStatus.REVIEW,
        )
        val shot1 = Shot(
            code = "SC01-01",
            title = "晨雾门前",
            durationSeconds = 3.5,
            locationAssetId = "loc-gate",
            propAssetIds = listOf("prop-bag"),
            appearances = listOf(ShotAppearance("char-lin", "casual")),
            takes = listOf(Take("t1", "shots/sc01_01_t1.mp4", TakeSource("local"), createdAt = 1L)),
            selectedTakeId = "t1",
            status = ShotStatus.LOCKED,
        )
        val scene1 = Scene(
            code = "SC01",
            number = 1,
            heading = "日外·包子铺门前",
            shots = listOf(shot1),
            storyboardLocked = true,
        )
        val ep1 = Episode(
            id = "S1E01",
            number = 1,
            title = "第一集",
            scriptPath = "scripts/s1e01_v1.md",
            scriptLocked = true,
            scenes = listOf(scene1),
        )
        val queuedPropApprove = ReviewEntry(ReviewTarget.assetViews("prop-bag"), ReviewKind.APPROVE, at = 2L, queuedRound = 2)
        val prod = Production(
            id = "prod-1",
            title = "陆昭晨间问候",
            kind = ProductionKind.FILM,
            rootPath = "productions/luzhao",
            seasons = listOf(Season("S1", 1, episodes = listOf(ep1))),
            assets = listOf(charAsset, locAsset, propAsset, draftAsset),
            stages = ProductionStage.entries.map { stage ->
                StageState(
                    stage = stage,
                    status = if (stage == ProductionStage.SCRIPT) StageStatus.LOCKED else StageStatus.IN_PROGRESS,
                )
            },
            reviews = listOf(
                ReviewEntry(ReviewTarget.script("S1E01"), ReviewKind.APPROVE, "定稿", at = 1L, round = 1),
                ReviewEntry(ReviewTarget.scene("S1E01", "SC01"), ReviewKind.APPROVE, at = 1L, round = 1),
                ReviewEntry(ReviewTarget.take("S1E01", "SC01-01", "t1"), ReviewKind.APPROVE, at = 1L, round = 1),
                ReviewEntry(ReviewTarget.assetLook("char-lin", "casual"), ReviewKind.APPROVE, at = 1L, round = 1),
                ReviewEntry(ReviewTarget.assetLook("char-lin", "night"), ReviewKind.APPROVE, at = 1L, round = 1),
                ReviewEntry(ReviewTarget.assetViews("loc-gate"), ReviewKind.APPROVE, at = 1L, round = 1),
                // 已排队的审核结论在重开非排队组时必须保留。
                ReviewEntry(ReviewTarget.script("S1E01"), ReviewKind.SUGGEST, "尾声略紧凑", at = 2L, queuedRound = 2),
                queuedPropApprove,
            ),
            reviewRounds = listOf(ReviewRound(number = 1, sentAt = 1L, entries = listOf(ReviewEntry(ReviewTarget.script("S1E01"), ReviewKind.APPROVE, "定稿", at = 1L, round = 1)), revision = 1L)),
            reviewDrafts = listOf(
                ReviewDraft(
                    group = ReviewTarget.assetViews("prop-bag"),
                    previousVerdicts = emptyList(),
                    pendingVerdicts = listOf(queuedPropApprove),
                    assetStatus = AssetStatus.REVIEW,
                    locked = false,
                    queued = true,
                ),
            ),
            nextReviewRound = 2,
            createdAt = 1L,
            updatedAt = 1L,
        )
        return ProductionSnapshot(prod, revision, ManifestSync.InSync)
    }

    private fun preflight(
        snapshot: ProductionSnapshot,
        opsJson: String,
        seen: Long? = snapshot.revision,
        reason: String = "",
    ): ProductionLockPreflight? {
        val opElements = ops(opsJson)
        val prepared = prepareProductionOperations(opElements, snapshot.production) { it }
        return ProductionLockApprovalPlanner.analyze(snapshot, prepared, seen, { true }, reason)
    }

    @Test
    fun `未锁定项预检返回null且陈旧reads拒绝预检`() {
        val snap = sampleSnapshot()
        assertThat(
            preflight(snap, """[{"op":"upsert_asset","id":"prop-unlocked","kind":"prop","name":"竹蒸笼"}]"""),
        ).isNull()

        val userModifiedSnap = snap.copy(
            production = snap.production.copy(
                assets = snap.production.assets.map {
                    if (it.id == "char-lin") it.copy(changedBy = ChangeSource.USER, changedAt = 6L) else it
                },
            ),
        )
        assertThat(
            preflight(
                userModifiedSnap,
                """[{"op":"upsert_asset","id":"char-lin","kind":"character","name":"林墨新名"}]""",
                seen = 5L,
            ),
        ).isNull()
    }

    @Test
    fun `已锁定资产改名保留锁定状态且无实质改动noop返回null`() {
        val snap = sampleSnapshot()
        // 锁定造型与锁定资产无任何变化时不弹审批、引擎也能直接跳过。
        val noopOps = """[
            {"op":"upsert_asset","id":"char-lin","kind":"character","name":"林墨","description":"主角"},
            {"op":"upsert_look","asset_id":"char-lin","look_id":"casual","name":"常服","note":"青布长衫","sheet_path":"assets/lin_casual_sheet.png"}
        ]"""
        assertThat(preflight(snap, noopOps)).isNull()
        val noopRes = ProductionEngine.update(snap, ops(noopOps), snap.revision, 10L) { true }
        assertThat(noopRes.production.assets.first { it.id == "char-lin" }.status).isEqualTo(AssetStatus.LOCKED)

        // 改名 + 改描述触发 KEEP_LOCKED 预检；未授权直接执行仍抛错。
        val renameJson = """[{"op":"upsert_asset","id":"char-lin","kind":"character","name":"林大夫","description":"医者主角"}]"""
        assertThrows(IllegalArgumentException::class.java) {
            ProductionEngine.update(snap, ops(renameJson), snap.revision, 10L) { true }
        }
        val auth = preflight(snap, renameJson, reason = "统一剧本角色姓氏")!!
        assertThat(auth.reason).isEqualTo("统一剧本角色姓氏")
        assertThat(auth.showsVisualOrScriptRevertWarning).isFalse()
        assertThat(auth.lockedItems.single().outcome).isEqualTo(ProductionLockOutcome.KEEP_LOCKED)
        val prepared = prepareProductionOperations(ops(renameJson), snap.production) { it }
        val updated = ProductionEngine.update(snap, prepared, snap.revision, 10L, { true }, auth)
        val charAfter = updated.production.assets.first { it.id == "char-lin" }
        assertThat(charAfter.name).isEqualTo("林大夫")
        assertThat(charAfter.status).isEqualTo(AssetStatus.LOCKED)
    }

    @Test
    fun `纯文字改造型与参考视角说明维持锁定而改图自动退回待审并重开审核组`() {
        val snap = sampleSnapshot()
        val noteJson = """[
            {"op":"upsert_look","asset_id":"char-lin","look_id":"casual","name":"常服","note":"微调袖口描述","sheet_path":"assets/lin_casual_sheet.png"},
            {"op":"set_look_slot","asset_id":"char-lin","look_id":"casual","slot_id":"front","name":"正视图","note":"改槽位名"},
            {"op":"set_asset_view","asset_id":"loc-gate","view_id":"wide","note":"补充阳光角度"}
        ]"""
        val noteAuth = preflight(snap, noteJson)!!
        assertThat(noteAuth.lockedItems).hasSize(3)
        assertThat(noteAuth.lockedItems.all { it.outcome == ProductionLockOutcome.KEEP_LOCKED }).isTrue()
        assertThat(noteAuth.showsVisualOrScriptRevertWarning).isFalse()
        val noteUpdated = ProductionEngine.update(snap, prepareProductionOperations(ops(noteJson), snap.production) { it }, snap.revision, 10L, { true }, noteAuth)
        val linNoteAfter = noteUpdated.production.assets.first { it.id == "char-lin" }
        assertThat(linNoteAfter.looks.first { it.id == "casual" }.locked).isTrue()
        assertThat(linNoteAfter.status).isEqualTo(AssetStatus.LOCKED)
        assertThat(noteUpdated.production.assets.first { it.id == "loc-gate" }.viewsLocked).isTrue()

        // 更换 casual 造型首图，但 night 造型仍锁定 -> 资产总状态维持 LOCKED，casual 退回待审。
        val swapOneLookImg = """[{"op":"set_look_slot","asset_id":"char-lin","look_id":"casual","slot_id":"front","path":"assets/lin_front_v2.png"}]"""
        val oneLookAuth = preflight(snap, swapOneLookImg)!!
        assertThat(oneLookAuth.showsVisualOrScriptRevertWarning).isTrue()
        assertThat(oneLookAuth.lockedItems.single().outcome).isEqualTo(ProductionLockOutcome.RETURN_TO_REVIEW)
        val oneLookAfter = ProductionEngine.update(snap, prepareProductionOperations(ops(swapOneLookImg), snap.production) { it }, snap.revision, 11L, { true }, oneLookAuth)
        val charPartial = oneLookAfter.production.assets.first { it.id == "char-lin" }
        assertThat(charPartial.looks.first { it.id == "casual" }.locked).isFalse()
        assertThat(charPartial.looks.first { it.id == "night" }.locked).isTrue()
        assertThat(charPartial.status).isEqualTo(AssetStatus.LOCKED)

        // 再把 night 造型整卡换新图，并更新剧本文件、向已锁定分镜场加镜头 -> 全部退回待审，且保留排队轮次。
        val multiRevertJson = """[
            {"op":"upsert_look","asset_id":"char-lin","look_id":"night","name":"夜行服","sheet_path":"assets/lin_night_v2.png"},
            {"op":"upsert_episode","season_id":"S1","id":"S1E01","number":1,"title":"第一集","script_path":"scripts/s1e01_v2.md"},
            {"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-02","title":"新特写","duration_seconds":2.5}
        ]"""
        val fullAuth = preflight(oneLookAfter, multiRevertJson, reason = "替换为高清参考与新分镜")!!
        assertThat(fullAuth.lockedItems).hasSize(3)
        assertThat(fullAuth.showsVisualOrScriptRevertWarning).isTrue()
        val finalSnap = ProductionEngine.update(oneLookAfter, prepareProductionOperations(ops(multiRevertJson), oneLookAfter.production) { it }, oneLookAfter.revision, 12L, { true }, fullAuth)
        val charAllUnlocked = finalSnap.production.assets.first { it.id == "char-lin" }
        assertThat(charAllUnlocked.looks.any { it.locked }).isFalse()
        assertThat(charAllUnlocked.status).isEqualTo(AssetStatus.REVIEW)
        val epAfter = finalSnap.production.seasons.single().episodes.single()
        assertThat(epAfter.scriptLocked).isFalse()
        assertThat(epAfter.scenes.single().storyboardLocked).isFalse()
        // 非排队 S1E01 script APPROVE 已清除，但同目标 queuedRound == 2 SUGGEST 与 prop-bag queuedRound == 2 APPROVE 均保留。
        assertThat(finalSnap.production.reviews.any {
            it.target == ReviewTarget.script("S1E01") && it.kind == ReviewKind.APPROVE && it.queuedRound == null
        }).isFalse()
        assertThat(finalSnap.production.reviews.any {
            it.target == ReviewTarget.script("S1E01") && it.kind == ReviewKind.SUGGEST && it.queuedRound == 2
        }).isTrue()
        assertThat(finalSnap.production.reviews.any {
            it.target == ReviewTarget.assetViews("prop-bag") && it.kind == ReviewKind.APPROVE && it.queuedRound == 2
        }).isTrue()
    }

    @Test
    fun `移除被锁定镜头引用的资产返回PERMANENT_REMOVE并校验target与摘要确定性`() {
        val snap = sampleSnapshot(revision = 9L)
        val removeJson = """[{"op":"remove","target":"asset","ids":["prop-bag"]}]"""
        val p1 = preflight(snap, removeJson, reason = "剧情删减该道具")!!
        val p2 = preflight(snap, removeJson, reason = "换个写法的原因不影响授权摘要")!!
        assertThat(p1.authorizationTarget).isEqualTo(p2.authorizationTarget)
        assertThat(ProductionLockApprovalPlanner.parseRevision(p1.authorizationTarget)).isEqualTo(9L)
        assertThat(ProductionLockApprovalPlanner.parseRevision("other:r9:abc")).isNull()
        val item = p1.lockedItems.single()
        assertThat(item.outcome).isEqualTo(ProductionLockOutcome.PERMANENT_REMOVE)
        assertThat(item.referencedByLockedShots).isEqualTo(1)
        assertThat(item.summary).contains("1 个已锁定镜头")

        val otherOp = """[{"op":"remove","target":"asset","ids":["loc-gate"]}]"""
        assertThat(preflight(snap, otherOp)!!.authorizationTarget).isNotEqualTo(p1.authorizationTarget)
    }

    @Test
    fun `修改已锁定视觉资产或分镜预演阶段时审批卡标题使用统一术语`() {
        val base = sampleSnapshot(revision = 9L)
        val snap = base.copy(
            production = base.production.copy(
                stages = base.production.stages.map { state ->
                    if (state.stage == ProductionStage.VISUAL_DEV || state.stage == ProductionStage.ANIMATIC) {
                        state.copy(status = StageStatus.LOCKED)
                    } else state
                },
            ),
        )
        val p = preflight(
            snap,
            """[{"op":"set_stage","stage":"visual_dev","status":"in_progress"},{"op":"set_stage","stage":"animatic","status":"skipped"}]""",
        )!!
        assertThat(p.lockedItems.map { it.title }).containsExactly("视觉资产", "分镜预演").inOrder()
        assertThat(p.lockedItems.map { it.kindAndId }).containsExactly("制作阶段 · 视觉资产", "制作阶段 · 分镜预演").inOrder()
        p.lockedItems.forEach { item ->
            assertThat(item.title).doesNotContain("视觉开发")
            assertThat(item.title).doesNotContain("动态预演")
        }
    }
}
