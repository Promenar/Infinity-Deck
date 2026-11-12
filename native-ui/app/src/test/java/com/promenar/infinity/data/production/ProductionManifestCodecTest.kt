package com.promenar.infinity.data.production

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProductionManifestCodecTest {
    private fun production() = Production(
        id = "p1", title = "项目", kind = ProductionKind.SERIES, rootPath = "productions/p1",
        createdAt = 1, updatedAt = 1,
        seasons = listOf(Season("S1", 1, episodes = listOf(Episode("S1E01", 1, "第一集", scenes = listOf(
            Scene("SC01", 1, "场景", shots = listOf(Shot("SC01-01", "镜头", durationSeconds = 3.0)))
        ))))),
    )

    @Test fun `旧清单按现有阶段语义读取并写回新版本`() {
        val legacy = """{"schema":"infinity.production/1","revision":1,"production":{"id":"p1","title":"项目","kind":"series","rootPath":"productions/p1","createdAt":1,"updatedAt":1,"stages":[{"stage":"script"},{"stage":"visual_dev"},{"stage":"storyboard","status":"locked"},{"stage":"animatic"},{"stage":"generation","status":"in_progress"},{"stage":"sound"},{"stage":"edit"}],"seasons":[{"id":"S1","number":1,"episodes":[{"id":"S1E01","number":1,"title":"集","scenes":[{"code":"SC01","number":1,"heading":"场","shots":[{"code":"SC01-01","title":"镜头","durationSeconds":3.0}]}]}]}]}}"""
        val decoded = ProductionManifestCodec.decode(legacy.toByteArray())
        assertThat(decoded.production.stages.map { it.stage }).containsExactlyElementsIn(ProductionStage.entries).inOrder()
        assertThat(decoded.production.stages[2].stage).isEqualTo(ProductionStage.STORYBOARD)
        assertThat(decoded.production.stages[4].stage).isEqualTo(ProductionStage.GENERATION)
        assertThat(decoded.production.stages[2].status).isEqualTo(StageStatus.LOCKED)
        assertThat(decoded.production.stages[4].status).isEqualTo(StageStatus.IN_PROGRESS)
        assertThat(ProductionManifestCodec.encode(decoded.production, 1).toString(Charsets.UTF_8)).contains(ProductionManifestCodec.SCHEMA)
    }

    @Test fun `清单往返保留项目和修订号`() {
        val value = production()
        val bytes = ProductionManifestCodec.encode(value, 7)
        val decoded = ProductionManifestCodec.decode(bytes)
        assertThat(decoded.production).isEqualTo(value)
        assertThat(decoded.revision).isEqualTo(7)
        assertThat(ProductionManifestCodec.sha256(bytes)).hasLength(64)
    }

    @Test fun `拒绝错误 schema 与损坏结构`() {
        val text = ProductionManifestCodec.encode(production(), 1).toString(Charsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) { ProductionManifestCodec.decode(text.replace(ProductionManifestCodec.SCHEMA, "other").toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { ProductionManifestCodec.decode("{".toByteArray()) }
    }

    @Test fun `拒绝重复标识与越界路径`() {
        val p = production()
        assertThrows(IllegalArgumentException::class.java) { ProductionManifestCodec.encode(p.copy(seasons = p.seasons + p.seasons), 1) }
        listOf("../escape", "/absolute", "C:/absolute", "a/../b", "a//b", "a\\b").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { ProductionManifestCodec.encode(p.copy(rootPath = path), 1) }
        }
    }

    @Test fun `拒绝超量与非法资产标识`() {
        val p = production()
        val asset = Asset("char-one", AssetKind.CHARACTER, "人物")
        assertThrows(IllegalArgumentException::class.java) { ProductionManifestCodec.encode(p.copy(assets = List(501) { asset.copy(id = "a$it") }), 1) }
        assertThrows(IllegalArgumentException::class.java) { ProductionManifestCodec.encode(p.copy(assets = listOf(asset.copy(id = "Invalid"))), 1) }
        assertThrows(IllegalArgumentException::class.java) { ProductionManifestCodec.encode(p.copy(title = "字".repeat(121)), 1) }
    }
    @Test fun `清单拒绝超前的用户变更修订`() {
        val p = production().copy(stages = ProductionStage.entries.map { StageState(it, changedBy = ChangeSource.USER, changedAt = 9) })
        assertThrows(IllegalArgumentException::class.java) { ProductionManifestCodec.encode(p, 8) }
        val valid = ProductionManifestCodec.encode(p, 9).toString(Charsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) { ProductionManifestCodec.decode(valid.replace("\"revision\": 9", "\"revision\": 8").toByteArray()) }
    }

    @Test fun `各类节点的变更修订不得超前清单`() {
        val base = production()
        val season = base.seasons.single()
        val ep = season.episodes.single()
        val scene = ep.scenes.single()
        val shot = scene.shots.single()
        fun tree(episode: Episode) = base.copy(seasons = listOf(season.copy(episodes = listOf(episode))))
        val candidates = listOf(
            tree(ep.copy(changedAt = 2)),
            tree(ep.copy(scenes = listOf(scene.copy(changedAt = 2)))),
            tree(ep.copy(scenes = listOf(scene.copy(shots = listOf(shot.copy(changedAt = 2)))))),
            base.copy(assets = listOf(Asset("a", AssetKind.PROP, "资产", changedAt = 2))),
            base.copy(stages = ProductionStage.entries.map { StageState(it, changedAt = 2) }),
        )
        candidates.forEach { assertThrows(IllegalArgumentException::class.java) { ProductionManifestCodec.encode(it, 1) } }
    }

}
