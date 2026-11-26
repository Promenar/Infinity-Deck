package com.promenar.infinity.data.production

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProductionEngineTest {
    private fun base() = ProductionSnapshot(Production("p", "项目", ProductionKind.SERIES, "productions/p", createdAt = 1, updatedAt = 1, experimental = true), 1, ManifestSync.InSync)
    private fun ops(text: String) = Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }
    private fun update(s: ProductionSnapshot, text: String, seen: Long = s.revision) = ProductionEngine.update(s, ops(text), seen, 2) { true }
    private fun fixture() = update(base(), """[
        {"op":"upsert_season","id":"S1","number":1},
        {"op":"upsert_episode","season_id":"S1","id":"S1E01","number":1,"title":"集"},
        {"op":"upsert_scene","episode_id":"S1E01","code":"SC01","number":1,"heading":"场"},
        {"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","title":"镜头","duration_seconds":3},
        {"op":"upsert_asset","id":"a","kind":"prop","name":"道具"}
    ]""")
    private fun shot(s: ProductionSnapshot) = s.production.seasons.single().episodes.single().scenes.single().shots.single()

    @Test fun `同批删除后重建镜头缺必填项且嵌套报错定位数组下标`() {
        val original = fixture()
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ProductionEngine.update(original, ops("""[
                {"op":"remove","target":"shot","episode_id":"S1E01","ids":["SC01-01"]},
                {"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","inputs":[{"role":"reference","path":"ref.png"}]},
                {"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-02","title":"二","duration_seconds":2,"inputs":[{"role":"reference","path":"ref.png"},{"role":"reference"}]}
            ]"""), original.revision, 3) { error("结构错误应在媒体查询之前发现") }
        }
        assertThat(failure.message).contains("第 2 项（upsert_shot）：新建镜头缺少必填字段")
        assertThat(failure.message).contains("inputs[1]")
        assertThat(failure.message).contains("缺少字段 path")
    }

    @Test fun `新镜头与嵌套拼写错误批量定位而省略 slots 时填充默认三位`() {
        val original = fixture()
        val failure = assertThrows(IllegalArgumentException::class.java) { update(original, """[
            {"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-02"},
            {"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","cameraMovement":"固定"},
            {"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","dialogue":[{"character_asset_id":"hero","text":"台词"}]},
            {"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","inputs":[{"role":"first","path":"ref.png"}]}
        ]""") }
        assertThat(failure.message).contains("新建镜头缺少必填字段 title、duration_seconds")
        assertThat(failure.message).contains("camera_movement")
        assertThat(failure.message).contains("characterAssetId")
        assertThat(failure.message).contains("character_asset_id")
        assertThat(failure.message).contains("first_frame")
        val updated = update(original, """[
            {"op":"upsert_asset","id":"hero","kind":"character","name":"角色","looks":[{"id":"main","name":"常服"}]}
        ]""")
        assertThat(updated.production.assets.last().looks.single().slots.map { it.id }).containsExactly("front", "side", "back").inOrder()
    }

    @Test fun `结构预检汇总多项并先于媒体查询与父级检查`() {
        var accessedMedia = false
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ProductionEngine.update(base(), ops("""[
                {"op":"add_take","episode_id":"S1E01","shot_code":"SC01-01","path":"a.png","service":"服务"},
                {"action":"upsert_season","id":"S1","number":1},
                {"op":"upsert_scene","episode_id":"S1E01","code":"SC01","number":1,"title":"场"},
                {"op":"upsert_asset","id":"hero","kind":"character","name":"主角","looks":[{"id":"main","name":"常服","slots":[{"id":"front","name":"正面"}]}]}
            ]"""), 1, 2) { accessedMedia = true; true }
        }
        assertThat(accessedMedia).isFalse()
        assertThat(failure.message).contains("第 2 项")
        assertThat(failure.message).contains("第 3 项（upsert_scene）")
        assertThat(failure.message).contains("heading")
        assertThat(failure.message).contains("第 4 项（upsert_asset）")
        assertThat(failure.message).contains("side")
        assertThat(failure.message).contains("back")
    }

    @Test fun `批量创建并登记候选与版本`() {
        val result = update(fixture(), """[
            {"op":"add_take","episode_id":"S1E01","shot_code":"SC01-01","path":"media/a.mp4","service":"服务"},
            {"op":"add_asset_version","asset_id":"a","path":"media/b.png"},
            {"op":"set_stage","stage":"script","status":"in_progress"},
            {"op":"set_bible","documents":[{"title":"设定","path":"docs/bible.md"}]},
            {"op":"set_preview_enabled","enabled":false}
        ]""")
        assertThat(shot(result).status).isEqualTo(ShotStatus.REVIEW)
        assertThat(shot(result).takes).hasSize(1)
        assertThat(result.production.assets.single().currentVersion).isEqualTo(1)
        assertThat(result.production.assets.single().status).isEqualTo(AssetStatus.REVIEW)
        assertThat(result.production.previewEnabled).isFalse()
    }

    @Test fun `整批失败不改变原快照`() {
        val original = fixture()
        assertThrows(IllegalArgumentException::class.java) { update(original, """[{"op":"set_stage","stage":"script","status":"in_progress"},{"op":"set_shot_status","episode_id":"S1E01","shot_code":"SC01-01","status":"locked"}]""") }
        assertThat(original.production.stages.first().status).isEqualTo(StageStatus.NOT_STARTED)
    }

    @Test fun `用户变更需要先读取且锁定镜头保护关键字段`() {
        val s = update(fixture(), """[{"op":"add_take","episode_id":"S1E01","shot_code":"SC01-01","path":"initial.mp4","service":"服务"}]""")
        val redone = ProductionUserEngine.apply(s, s.revision, ProductionUserAction.Review(ReviewTarget.take("S1E01", "SC01-01", shot(s).takes.single().id), ReviewKind.REJECT, "调整"), 3)
        val operation = """[{"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","prompt":"新提示"}]"""
        assertThrows(IllegalArgumentException::class.java) { update(redone, operation, s.revision) }
        assertThat(shot(update(redone, operation)).prompt).isEqualTo("新提示")
        val withTake = update(redone, """[{"op":"add_take","episode_id":"S1E01","shot_code":"SC01-01","path":"a.png","service":"服务"}]""")
        val locked = ProductionUserEngine.apply(withTake, withTake.revision, ProductionUserAction.Review(ReviewTarget.take("S1E01", "SC01-01", shot(withTake).takes.last().id), ReviewKind.APPROVE, null), 4)
        assertThat(shot(update(locked, operation)).prompt).isEqualTo("新提示")
        val added = update(locked, """[{"op":"add_take","episode_id":"S1E01","shot_code":"SC01-01","path":"b.png","service":"服务"}]""")
        assertThat(shot(added).status).isEqualTo(ShotStatus.LOCKED)
        assertThat(shot(added).takes).hasSize(3)
    }

    @Test fun `锁定场禁止增删重排而允许更新说明`() {
        val s = fixture()
        val locked = ProductionUserEngine.apply(s, s.revision, ProductionUserAction.Review(ReviewTarget.scene("S1E01", "SC01"), ReviewKind.APPROVE, null), 3)
        val add = """[{"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-02","title":"二","duration_seconds":2}]"""
        val remove = """[{"op":"remove","target":"shot","episode_id":"S1E01","ids":["SC01-01"]}]"""
        assertThrows(IllegalArgumentException::class.java) { update(locked, add) }
        assertThrows(IllegalArgumentException::class.java) { update(locked, remove) }
        val changed = update(locked, """[{"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","agent_note":"说明"}]""")
        assertThat(shot(changed).agentNote).isEqualTo("说明")
    }

    @Test fun `位置排序和删除`() {
        val s = update(fixture(), """[{"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-02","title":"二","duration_seconds":2,"position":0}]""")
        assertThat(s.production.seasons.single().episodes.single().scenes.single().shots.map { it.code }).containsExactly("SC01-02", "SC01-01").inOrder()
        val removed = update(s, """[{"op":"remove","target":"shot","episode_id":"S1E01","ids":["SC01-02"]}]""")
        assertThat(shot(removed).code).isEqualTo("SC01-01")
    }
    @Test fun `智能体状态白名单与用户决定冲突`() {
        val s = fixture()
        val changed = update(s, """[{"op":"set_shot_status","episode_id":"S1E01","shot_code":"SC01-01","status":"generating"},{"op":"set_asset_status","asset_id":"a","status":"review"}]""")
        assertThat(shot(changed).status).isEqualTo(ShotStatus.GENERATING)
        assertThat(changed.production.assets.single().status).isEqualTo(AssetStatus.REVIEW)
        assertThrows(IllegalArgumentException::class.java) { update(s, """[{"op":"set_asset_status","asset_id":"a","status":"locked"}]""") }
        assertThrows(IllegalArgumentException::class.java) { update(s, """[{"op":"set_stage","stage":"script","status":"locked"}]""") }
        val withVersion = update(s, """[{"op":"add_asset_version","asset_id":"a","path":"initial.png"}]""")
        val user = ProductionUserEngine.apply(withVersion, withVersion.revision, ProductionUserAction.Review(ReviewTarget.assetVersion("a", 1), ReviewKind.APPROVE, "定稿"), 3)
        assertThrows(IllegalArgumentException::class.java) { update(user, """[{"op":"add_asset_version","asset_id":"a","path":"a.png"}]""", s.revision) }
        val result = update(user, """[{"op":"add_asset_version","asset_id":"a","path":"a.png"}]""")
        assertThat(result.production.assets.single().status).isEqualTo(AssetStatus.LOCKED)
        assertThat(result.production.assets.single().currentVersion).isEqualTo(1)
        assertThat(result.production.assets.single().versions).hasSize(2)
    }

    @Test fun `无效路径媒体与操作数量被拒绝`() {
        val s = fixture()
        assertThrows(IllegalArgumentException::class.java) { update(s, """[{"op":"add_take","episode_id":"S1E01","shot_code":"SC01-01","path":"../a.mp4","service":"服务"}]""") }
        assertThrows(IllegalArgumentException::class.java) { ProductionEngine.update(s, ops("""[{"op":"add_take","episode_id":"S1E01","shot_code":"SC01-01","path":"a.mp4","service":"服务"}]"""), s.revision, 3) { false } }
        assertThrows(IllegalArgumentException::class.java) { ProductionEngine.update(s, List(101) { buildJsonObject { put("op", "set_preview_enabled"); put("enabled", true) } }, s.revision, 3) { true } }
    }

    @Test fun `删除季集场和资产`() {
        val s = fixture()
        val a = update(s, """[{"op":"remove","target":"asset","ids":["a"]},{"op":"remove","target":"scene","episode_id":"S1E01","ids":["SC01"]}]""")
        assertThat(a.production.assets).isEmpty()
        assertThat(a.production.seasons.single().episodes.single().scenes).isEmpty()
        val b = update(a, """[{"op":"remove","target":"episode","ids":["S1E01"]},{"op":"remove","target":"season","ids":["S1"]}]""")
        assertThat(b.production.seasons).isEmpty()
    }

    @Test fun `已锁剧本阻止删除集及所在季`() {
        val s = fixture()
        val locked = ProductionUserEngine.apply(s, s.revision, ProductionUserAction.Review(ReviewTarget.script("S1E01"), ReviewKind.APPROVE, null), 3)
        for (target in listOf("episode", "season")) {
            val id = if (target == "episode") "S1E01" else "S1"
            assertThrows(IllegalArgumentException::class.java) { update(locked, """[{"op":"remove","target":"$target","ids":["$id"]}]""") }
        }
    }
    @Test fun `锁定资产追加版本保持当前版本且不能改名称或种类`() {
        val s = update(fixture(), """[{"op":"add_asset_version","asset_id":"a","path":"a.png"}]""")
        val locked = ProductionUserEngine.apply(s, s.revision, ProductionUserAction.Review(ReviewTarget.assetVersion("a", 1), ReviewKind.APPROVE, null), 3)
        val appended = update(locked, """[{"op":"add_asset_version","asset_id":"a","path":"b.png"}]""")
        assertThat(appended.production.assets.single().currentVersion).isEqualTo(1)
        assertThat(appended.production.assets.single().versions).hasSize(2)
        for (change in listOf("\"kind\":\"character\",\"name\":\"道具\"", "\"kind\":\"prop\",\"name\":\"新名\"")) {
            assertThrows(IllegalArgumentException::class.java) { update(locked, """[{"op":"upsert_asset","id":"a",$change}]""") }
        }
    }
    @Test fun `智能体更新不能抹去用户变更的修订标记`() {
        val s = fixture()
        val user = ProductionUserEngine.apply(s, s.revision, ProductionUserAction.EditPrompt("S1E01", "SC01-01", "用户提示"), 3)
        val fresh = update(user, """[{"op":"set_shot_status","episode_id":"S1E01","shot_code":"SC01-01","status":"review"}]""", user.revision)
        assertThat(shot(fresh).changedBy).isEqualTo(ChangeSource.USER)
        assertThat(shot(fresh).changedAt).isEqualTo(user.revision)
        assertThrows(IllegalArgumentException::class.java) { update(fresh, """[{"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","prompt":"旧提示"}]""", s.revision) }
    }

    @Test fun `场集内容更新推进修订且各对象保留用户来源`() {
        var s = update(fixture(), """[{"op":"add_asset_version","asset_id":"a","path":"initial.png"}]""")
        s = ProductionUserEngine.apply(s, s.revision, ProductionUserAction.Review(ReviewTarget.script("S1E01"), ReviewKind.REJECT, null), 3)
        s = ProductionUserEngine.apply(s, s.revision, ProductionUserAction.Review(ReviewTarget.scene("S1E01", "SC01"), ReviewKind.REJECT, null), 3)
        s = ProductionUserEngine.apply(s, s.revision, ProductionUserAction.Review(ReviewTarget.assetVersion("a", 1), ReviewKind.REJECT, null), 3)
        s = ProductionUserEngine.apply(s, s.revision, ProductionUserAction.SetStage(ProductionStage.SCRIPT, StageStatus.NOT_STARTED), 3)
        val episode = s.production.seasons.single().episodes.single()
        val scene = episode.scenes.single()
        val asset = s.production.assets.single()
        val stage = s.production.stages.first()
        val out = update(s, """[{"op":"upsert_episode","season_id":"S1","id":"S1E01","number":1,"title":"新集"},{"op":"upsert_scene","episode_id":"S1E01","code":"SC01","number":1,"heading":"新场"},{"op":"upsert_asset","id":"a","kind":"prop","name":"道具","description":"说明"},{"op":"set_stage","stage":"script","status":"in_progress"}]""")
        val afterEpisode = out.production.seasons.single().episodes.single()
        assertThat(afterEpisode.changedAt).isEqualTo(out.revision)
        assertThat(afterEpisode.changedAt).isGreaterThan(episode.changedAt)
        assertThat(afterEpisode.scenes.single().changedAt).isEqualTo(out.revision)
        assertThat(afterEpisode.scenes.single().changedAt).isGreaterThan(scene.changedAt)
        assertThat(out.production.assets.single().changedAt).isEqualTo(asset.changedAt)
        assertThat(out.production.stages.first().changedAt).isEqualTo(stage.changedAt)
        assertThat(listOf(afterEpisode.changedBy, afterEpisode.scenes.single().changedBy, out.production.assets.single().changedBy, out.production.stages.first().changedBy)).containsExactly(ChangeSource.USER, ChangeSource.USER, ChangeSource.USER, ChangeSource.USER)
    }

    @Test fun `upsert_shot 接受正整数 spec_width 与 spec_height 支持 null 清空并拒绝字符串或非正数`() {
        val base = fixture()
        val withSpecs = update(base, """[{"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","spec_width":864,"spec_height":480}]""")
        assertThat(shot(withSpecs).specWidth).isEqualTo(864)
        assertThat(shot(withSpecs).specHeight).isEqualTo(480)

        val cleared = update(withSpecs, """[{"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","spec_width":null,"spec_height":null}]""")
        assertThat(shot(cleared).specWidth).isNull()
        assertThat(shot(cleared).specHeight).isNull()

        listOf(
            """[{"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","spec_width":"864"}]""",
            """[{"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","spec_height":"480"}]""",
            """[{"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","spec_width":0}]""",
            """[{"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","spec_height":-16}]""",
        ).forEach { invalid ->
            val err = assertThrows(IllegalArgumentException::class.java) { update(base, invalid) }
            assertThat(err.message).contains("大于或等于 1 的整数")
        }
    }

    @Test fun `用户改写提示词锁定后 upsert_shot 不覆盖并返回中文告警`() {
        val base = fixture()
        val edited = ProductionUserEngine.apply(base, base.revision, ProductionUserAction.EditPrompt("S1E01", "SC01-01", "用户手写提示词"), 3)
        val blocked = ProductionEngine.updateWithWarnings(
            edited,
            ops("""[{"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","prompt":"智能体企图覆盖"}]"""),
            edited.revision,
            4,
        ) { true }
        assertThat(shot(blocked.snapshot).prompt).isEqualTo("用户手写提示词")
        assertThat(blocked.warnings).containsExactly("镜头 SC01-01 的提示词已由用户手动编辑锁定，本次未覆盖 prompt。")

        val samePrompt = ProductionEngine.updateWithWarnings(
            edited,
            ops("""[{"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","prompt":"用户手写提示词"}]"""),
            edited.revision,
            4,
        ) { true }
        assertThat(samePrompt.warnings).isEmpty()

        val withoutPrompt = ProductionEngine.updateWithWarnings(
            edited,
            ops("""[{"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","agent_note":"仅更新备注"}]"""),
            edited.revision,
            4,
        ) { true }
        assertThat(withoutPrompt.warnings).isEmpty()
    }

    @Test fun `add_asset_version 支持可选正数 duration_seconds 并落库到 AssetVersion`() {
        val base = update(fixture(), """[{"op":"upsert_asset","id":"voice-a","kind":"sound","name":"角色配音"}]""")
        val withDuration = update(base, """[{"op":"add_asset_version","asset_id":"voice-a","path":"audio/voice.wav","duration_seconds":13.55,"note":"云端配音"}]""")
        val v1 = withDuration.production.assets.first { it.id == "voice-a" }.versions.single()
        assertThat(v1.durationSeconds).isEqualTo(13.55)
        assertThat(v1.note).isEqualTo("云端配音")

        val withoutDuration = update(withDuration, """[{"op":"add_asset_version","asset_id":"voice-a","path":"audio/voice2.wav"}]""")
        val v2 = withoutDuration.production.assets.first { it.id == "voice-a" }.versions.last()
        assertThat(v2.durationSeconds).isNull()

        listOf(
            """[{"op":"add_asset_version","asset_id":"voice-a","path":"audio/voice3.wav","duration_seconds":0}]""",
            """[{"op":"add_asset_version","asset_id":"voice-a","path":"audio/voice3.wav","duration_seconds":-2.5}]""",
            """[{"op":"add_asset_version","asset_id":"voice-a","path":"audio/voice3.wav","duration_seconds":"13.55"}]""",
        ).forEach { invalid ->
            val err = assertThrows(IllegalArgumentException::class.java) { update(base, invalid) }
            assertThat(err.message).contains("有限正数")
        }
    }

}
