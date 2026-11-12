package com.promenar.infinity.data.production

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProductionCutsTest {
    private val path = "productions/p/takes/a.mp4"
    private fun snapshot() = ProductionSnapshot(
        Production("p", "项目", ProductionKind.SERIES, "productions/p", createdAt = 1, updatedAt = 1,
            seasons = listOf(Season("S1", 1, episodes = listOf(
                Episode("S1E01", 1, "第一集", scenes = listOf(Scene("SC01", 1, "场", shots = listOf(
                    Shot("SC01-02", "已选", durationSeconds = 3.0,
                        takes = listOf(Take("take", path, TakeSource("服务"), 1)), selectedTakeId = "take"),
                    Shot("SC01-01", "缺口", durationSeconds = 2.0),
                )))),
                Episode("S1E02", 2, "第二集", scenes = listOf(Scene("SC02", 2, "场", shots = listOf(
                    Shot("SC02-01", "镜头", durationSeconds = 1.0),
                )))),
            ))),
        ), 1, ManifestSync.InSync,
    )
    private fun apply(snapshot: ProductionSnapshot, action: ProductionUserAction) =
        ProductionUserEngine.apply(snapshot, snapshot.revision, action, 10)

    @Test fun `创建冻结当前选定候选实测时长和当前镜头顺序`() {
        val before = snapshot()
        val result = apply(before, ProductionUserAction.CreateCut("S1E01", mapOf(path to 4.5)))
        val cut = result.production.cuts.single()
        assertThat(cut.number).isEqualTo(1)
        assertThat(cut.createdAt).isEqualTo(10)
        assertThat(cut.clips).containsExactly(
            CutClip("S1E01", "SC01-02", "take", path, 4.5),
            CutClip("S1E01", "SC01-01", seconds = 2.0),
        ).inOrder()
        assertThat(cut.burnSubtitles).isFalse()
        assertThat(cut.export).isNull()
        assertThat(result.revision).isEqualTo(2)
        assertThat(result.manifest).isEqualTo(ManifestSync.Pending)
        assertThat(result.production.cutVersionHighWatermarks).containsExactly("S1E01", 1)
        assertThat(before.production.cuts).isEmpty()
        val redo = apply(result, ProductionUserAction.Review(ReviewTarget.take("S1E01", "SC01-02", "take"), ReviewKind.REJECT, "重新选择"))
        assertThat(redo.production.cuts).containsExactly(cut)
    }

    @Test fun `探测缺失或无效时回落计划时长`() {
        listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { measured ->
            val values = measured?.let { mapOf(path to it) }.orEmpty()
            val cut = apply(snapshot(), ProductionUserAction.CreateCut("S1E01", values)).production.cuts.single()
            assertThat(cut.clips.first().seconds).isEqualTo(3.0)
        }
    }

    @Test fun `编号按集独立递增删除全部版本后不复用`() {
        var state = apply(snapshot(), ProductionUserAction.CreateCut("S1E01"))
        state = apply(state, ProductionUserAction.CreateCut("S1E01"))
        state = apply(state, ProductionUserAction.CreateCut("S1E02"))
        assertThat(state.production.cuts.map { it.number }).containsExactly(1, 2, 1).inOrder()
        state.production.cuts.toList().forEach { state = apply(state, ProductionUserAction.DeleteCut(it.id)) }
        assertThat(state.production.cuts).isEmpty()
        val restored = ProductionManifestCodec.decode(ProductionManifestCodec.encode(state.production, state.revision))
        state = apply(state.copy(production = restored.production), ProductionUserAction.CreateCut("S1E01"))
        assertThat(state.production.cuts.single().number).isEqualTo(3)
        assertThat(state.production.cutVersionHighWatermarks["S1E02"]).isEqualTo(1)
    }

    @Test fun `相同集编号在不同项目中使用不同版本标识`() {
        val first = snapshot()
        val second = first.copy(production = first.production.copy(id = "another-project"))
        val a = apply(first, ProductionUserAction.CreateCut("S1E01")).production.cuts.single()
        val b = apply(second, ProductionUserAction.CreateCut("S1E01")).production.cuts.single()
        assertThat(a.number).isEqualTo(b.number)
        assertThat(a.id).isNotEqualTo(b.id)
    }

    @Test fun `每集最多二十个版本删除后腾出容量且编号继续增加`() {
        var state = snapshot()
        repeat(20) { state = apply(state, ProductionUserAction.CreateCut("S1E01")) }
        assertThrows(IllegalArgumentException::class.java) { apply(state, ProductionUserAction.CreateCut("S1E01")) }
        state = apply(state, ProductionUserAction.CreateCut("S1E02"))
        state = apply(state, ProductionUserAction.DeleteCut(state.production.cuts.first().id))
        state = apply(state, ProductionUserAction.CreateCut("S1E01"))
        assertThat(state.production.cuts.last().number).isEqualTo(21)
        assertThat(state.production.cuts.count { it.episodeId == "S1E01" }).isEqualTo(20)
    }

    @Test fun `拒绝过期修订缺失引用空集编号溢出和无效时长`() {
        val initial = snapshot()
        assertThrows(ProductionRevisionConflictException::class.java) {
            ProductionUserEngine.apply(initial, 0, ProductionUserAction.CreateCut("S1E01"), 10)
        }
        assertThrows(IllegalArgumentException::class.java) { apply(initial, ProductionUserAction.CreateCut("S1E99")) }
        assertThrows(IllegalArgumentException::class.java) { apply(initial, ProductionUserAction.DeleteCut("missing")) }
        val overflow = initial.copy(production = initial.production.copy(cutVersionHighWatermarks = mapOf("S1E01" to Int.MAX_VALUE)))
        assertThrows(IllegalArgumentException::class.java) { apply(overflow, ProductionUserAction.CreateCut("S1E01")) }
        val empty = initial.production.copy(seasons = listOf(Season("S1", 1, episodes = listOf(Episode("S1E01", 1, "空集")))))
        assertThrows(IllegalArgumentException::class.java) { apply(initial.copy(production = empty), ProductionUserAction.CreateCut("S1E01")) }
        assertThrows(IllegalArgumentException::class.java) {
            apply(initial, ProductionUserAction.CreateCut("S1E01", mapOf(path to Double.MAX_VALUE)))
        }
    }

    @Test fun `导出登记校验项目路径与数值字幕配置变化清除旧结果`() {
        var state = apply(snapshot(), ProductionUserAction.CreateCut("S1E01"))
        val cut = state.production.cuts.single()
        val record = CutExportRecord(ProductionCuts.videoPath(state.production, cut), ProductionCuts.subtitlePath(state.production, cut), 5.0, 100, 10)
        state = apply(state, ProductionUserAction.RegisterCutExport(cut.id, record))
        assertThat(state.production.cuts.single().export).isEqualTo(record)
        assertThat(apply(state, ProductionUserAction.SetCutSubtitles(cut.id, false)).production.cuts.single().export).isEqualTo(record)
        state = apply(state, ProductionUserAction.SetCutSubtitles(cut.id, true))
        assertThat(state.production.cuts.single().burnSubtitles).isTrue()
        assertThat(state.production.cuts.single().export).isNull()
        listOf("productions/p-other/cuts/result.mp4", "productions/p/../escape.mp4", "/absolute.mp4").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { apply(state, ProductionUserAction.RegisterCutExport(cut.id, record.copy(videoPath = invalid))) }
        }
        listOf(record.copy(sizeBytes = 0), record.copy(sizeBytes = -1), record.copy(durationSeconds = 0.0),
            record.copy(durationSeconds = Double.NaN), record.copy(exportedAt = -1), record.copy(subtitlePath = record.videoPath)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { apply(state, ProductionUserAction.RegisterCutExport(cut.id, invalid)) }
        }
    }

    @Test fun `清单往返保留版本字幕导出结果和编号记录且兼容缺省字段`() {
        var state = apply(snapshot(), ProductionUserAction.CreateCut("S1E01"))
        val cut = state.production.cuts.single()
        state = apply(state, ProductionUserAction.SetCutSubtitles(cut.id, true))
        state = apply(state, ProductionUserAction.RegisterCutExport(cut.id,
            CutExportRecord(ProductionCuts.videoPath(state.production, cut), ProductionCuts.subtitlePath(state.production, cut), 5.0, 100, 10)))
        val decoded = ProductionManifestCodec.decode(ProductionManifestCodec.encode(state.production, state.revision))
        assertThat(decoded.production).isEqualTo(state.production)
        val root = Json.parseToJsonElement(ProductionManifestCodec.encode(snapshot().production, 1).decodeToString()).jsonObject
        val legacyProduction = JsonObject(root.getValue("production").jsonObject - "cuts" - "cutVersionHighWatermarks")
        val legacy = JsonObject(root + ("production" to legacyProduction))
        val old = ProductionManifestCodec.decode(legacy.toString().toByteArray()).production
        assertThat(old.cuts).isEmpty()
        assertThat(old.cutVersionHighWatermarks).isEmpty()
    }

    @Test fun `清单拒绝跨集候选镜头重复编号和越界素材`() {
        val state = apply(snapshot(), ProductionUserAction.CreateCut("S1E01"))
        val p = state.production
        val cut = p.cuts.single()
        val first = cut.clips.first()
        val invalidCuts = listOf(
            cut.copy(episodeId = "S1E99"), cut.copy(number = 0), cut.copy(createdAt = -1),
            cut.copy(clips = listOf(first.copy(episodeId = "S1E02"))),
            cut.copy(clips = listOf(first.copy(shotCode = "SC01-99"))),
            cut.copy(clips = listOf(first.copy(takeId = "missing"))),
            cut.copy(clips = listOf(first.copy(path = "productions/p-other/a.mp4"))),
            cut.copy(clips = listOf(first.copy(path = null))),
            cut.copy(clips = listOf(first.copy(seconds = 0.0))),
            cut.copy(clips = listOf(first, first)),
        )
        invalidCuts.forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { ProductionManifestCodec.encode(p.copy(cuts = listOf(invalid)), state.revision) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProductionManifestCodec.encode(p.copy(cuts = listOf(cut, cut.copy(id = "other"))), state.revision)
        }
    }
}
