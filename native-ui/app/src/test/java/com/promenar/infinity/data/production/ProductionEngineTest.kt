package com.promenar.infinity.data.production

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProductionEngineTest {
    private fun base() = ProductionSnapshot(Production("p", "项目", ProductionKind.SERIES, "productions/p", createdAt = 1, updatedAt = 1), 1, ManifestSync.InSync)
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
        val s = fixture()
        val redone = ProductionUserEngine.apply(s, s.revision, ProductionUserAction.RequestRedo("S1E01", "SC01-01", "调整"), 3)
        val operation = """[{"op":"upsert_shot","episode_id":"S1E01","scene_code":"SC01","code":"SC01-01","prompt":"新提示"}]"""
        assertThrows(IllegalArgumentException::class.java) { update(redone, operation, s.revision) }
        assertThat(shot(update(redone, operation)).prompt).isEqualTo("新提示")
        val withTake = update(redone, """[{"op":"add_take","episode_id":"S1E01","shot_code":"SC01-01","path":"a.png","service":"服务"}]""")
        val locked = ProductionUserEngine.apply(withTake, withTake.revision, ProductionUserAction.SelectTake("S1E01", "SC01-01", shot(withTake).takes.single().id), 4)
        assertThrows(IllegalArgumentException::class.java) { update(locked, operation) }
        val added = update(locked, """[{"op":"add_take","episode_id":"S1E01","shot_code":"SC01-01","path":"b.png","service":"服务"}]""")
        assertThat(shot(added).status).isEqualTo(ShotStatus.LOCKED)
        assertThat(shot(added).takes).hasSize(2)
    }

    @Test fun `锁定场禁止增删重排而允许更新说明`() {
        val s = fixture()
        val locked = ProductionUserEngine.apply(s, s.revision, ProductionUserAction.LockScene("S1E01", "SC01", true), 3)
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
        val user = ProductionUserEngine.apply(s, s.revision, ProductionUserAction.SetAssetStatus("a", AssetStatus.LOCKED, "定稿"), 3)
        assertThrows(IllegalArgumentException::class.java) { update(user, """[{"op":"add_asset_version","asset_id":"a","path":"a.png"}]""", s.revision) }
        val result = update(user, """[{"op":"add_asset_version","asset_id":"a","path":"a.png"}]""")
        assertThat(result.production.assets.single().status).isEqualTo(AssetStatus.LOCKED)
        assertThat(result.production.assets.single().currentVersion).isNull()
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
        val locked = ProductionUserEngine.apply(s, s.revision, ProductionUserAction.LockScript("S1E01", true), 3)
        for (target in listOf("episode", "season")) {
            val id = if (target == "episode") "S1E01" else "S1"
            assertThrows(IllegalArgumentException::class.java) { update(locked, """[{"op":"remove","target":"$target","ids":["$id"]}]""") }
        }
    }
    @Test fun `锁定资产追加版本保持当前版本且不能改名称或种类`() {
        val s = update(fixture(), """[{"op":"add_asset_version","asset_id":"a","path":"a.png"}]""")
        val locked = ProductionUserEngine.apply(s, s.revision, ProductionUserAction.SetAssetStatus("a", AssetStatus.LOCKED, null), 3)
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

    @Test fun `场集资产阶段的智能体更新保留用户修订标记`() {
        var s = fixture()
        s = ProductionUserEngine.apply(s, s.revision, ProductionUserAction.LockScript("S1E01", false), 3)
        s = ProductionUserEngine.apply(s, s.revision, ProductionUserAction.LockScene("S1E01", "SC01", false), 3)
        s = ProductionUserEngine.apply(s, s.revision, ProductionUserAction.SetAssetStatus("a", AssetStatus.REVIEW, null), 3)
        s = ProductionUserEngine.apply(s, s.revision, ProductionUserAction.SetStage(ProductionStage.SCRIPT, StageStatus.NOT_STARTED), 3)
        val episode = s.production.seasons.single().episodes.single()
        val scene = episode.scenes.single()
        val asset = s.production.assets.single()
        val stage = s.production.stages.first()
        val out = update(s, """[{"op":"upsert_episode","season_id":"S1","id":"S1E01","number":1,"title":"新集"},{"op":"upsert_scene","episode_id":"S1E01","code":"SC01","number":1,"heading":"新场"},{"op":"upsert_asset","id":"a","kind":"prop","name":"道具","description":"说明"},{"op":"set_stage","stage":"script","status":"in_progress"}]""")
        val afterEpisode = out.production.seasons.single().episodes.single()
        assertThat(afterEpisode.changedAt).isEqualTo(episode.changedAt)
        assertThat(afterEpisode.scenes.single().changedAt).isEqualTo(scene.changedAt)
        assertThat(out.production.assets.single().changedAt).isEqualTo(asset.changedAt)
        assertThat(out.production.stages.first().changedAt).isEqualTo(stage.changedAt)
        assertThat(listOf(afterEpisode.changedBy, afterEpisode.scenes.single().changedBy, out.production.assets.single().changedBy, out.production.stages.first().changedBy)).containsExactly(ChangeSource.USER, ChangeSource.USER, ChangeSource.USER, ChangeSource.USER)
    }

}
