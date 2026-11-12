package com.promenar.infinity.data.production

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProductionProgressTest {
    @Test fun `旧快照缺少进度来源字段可读新增字段完整往返`() {
        val json = Json { encodeDefaults = false }
        val old = json.encodeToString(reviewFixture().production)
        assertThat(old).doesNotContain("chains")
        assertThat(old).doesNotContain("runs")
        val p = json.decodeFromString<Production>(old)
        assertThat(p.runs).isEmpty(); assertThat(p.unregisteredFiles).isEmpty()
        val derived = Take("derived", "p/derived.mp4", TakeSource("处理"), 30, derivedFrom = TakeDerivation("t1", "超分", "up", mapOf("scale" to JsonPrimitive(2))), spec = MediaSpec(640, 480, 25.0, 3.0),
            planSnapshot = TakePlanSnapshot(mode = ShotMode.TEXT, inputs = emptyList(), prompt = "云", templateFingerprint = "abc", params = mapOf("scale" to JsonPrimitive(2))))
        val chain = ShotChain("chain", ChainState.RUNNING, listOf(ChainSegment(0, 3.0)), createdAt = 30, updatedAt = 30)
        val run = ProductionRun("run", RunTarget("S1E01", "SC01-01"), "脚本", startedAt = 30, reportedAt = 30)
        val full = p.mapShot("S1E01", "SC01-01") { it.copy(takes = it.takes + derived, chains = listOf(chain)) }.copy(runs = listOf(run), unregisteredFiles = listOf("p/orphan.mp4"))
        assertThat(ProductionManifestCodec.decode(ProductionManifestCodec.encode(full, 1)).production).isEqualTo(full)
        val asset = AssetVersion(1, "p/a.wav", 30, seed = 4, durationSeconds = 3.5, derivedFrom = derived.derivedFrom)
        assertThat(json.decodeFromString<AssetVersion>(json.encodeToString(asset))).isEqualTo(asset)
    }
    @Test fun `校验拒绝链段非连续越界已完成无候选重复活动链及错误登记目标`() {
        val p = reviewFixture().production
        val chain = ShotChain("c", ChainState.PLANNED, listOf(ChainSegment(0, 3.0)), createdAt = 0, updatedAt = 0)
        val invalidChains = listOf(chain.copy(segments = listOf(ChainSegment(1, 3.0))), chain.copy(current = 1), chain.copy(state = ChainState.COMPLETED), chain.copy(segments = emptyList()))
        invalidChains.forEach { bad -> assertThrows(IllegalArgumentException::class.java) { ProductionManifestCodec.validate(p.mapShot("S1E01", "SC01-01") { it.copy(chains = listOf(bad)) }) } }
        assertThrows(IllegalArgumentException::class.java) { ProductionManifestCodec.validate(p.mapShot("S1E01", "SC01-01") { it.copy(chains = listOf(chain, chain.copy(id = "other"))) }) }
        val run = ProductionRun("r", RunTarget("S1E01", "SC01-01"), "脚本", steps = 1, startedAt = 0, reportedAt = 0)
        listOf(run.copy(completedSteps = 2), run.copy(target = RunTarget(assetId = "missing")), run.copy(target = RunTarget("S1E01", "missing")), run.copy(target = RunTarget("S1E01", "SC01-01", "char"))).forEach {
            assertThrows(IllegalArgumentException::class.java) { ProductionManifestCodec.validate(p.copy(runs = listOf(it))) }
        }
        assertThrows(IllegalArgumentException::class.java) { ProductionManifestCodec.validate(p.copy(runs = listOf(run, run))) }
    }
    @Test fun `登记完整动作与已结束后拒绝回报用户取消未回报边界`() = runTest {
        val store = ProgressStore(); var auditCount = 0; var id = 0; var clock = 30L
        val service = ProductionRunService(store, { _, _ -> MediaSpec(320, 240, 25.0, 3.0) }, { _, _ -> true }, { auditCount++ }, now = { clock }, newId = { "r${id++}" })
        val started = service.start("s", RunTarget("S1E01", "SC01-01"), "三步编排", 3)
        clock = 40; val progress = service.progress("s", started.id, 1, "首段完成")
        assertThat(progress.reportedAt).isEqualTo(40)
        assertThat(progress.isUnreported(40 + RUN_UNREPORTED_MILLIS - 1)).isFalse()
        assertThat(progress.isUnreported(40 + RUN_UNREPORTED_MILLIS)).isTrue()
        assertThat(progress.isUnreported(0)).isFalse()
        val finished = service.finish("s", started.id, "p/result.mp4", "t1", "续接", mapOf("scale" to JsonPrimitive(2)), listOf("p/segment.mp4"))
        val take = store.shot().takes.last()
        assertThat(finished.resultTakeId).isEqualTo(take.id)
        assertThat(take.runId).isEqualTo(started.id)
        assertThat(take.derivedFrom?.sourceTakeId).isEqualTo("t1")
        assertThat(take.segments.single()).isEqualTo(TakeSegment(0, "p/segment.mp4", 0.0, 3.0))
        assertThat(finished.isUnreported(Long.MAX_VALUE)).isFalse()
        val ended = try { service.progress("s", started.id, 2, "晚回报"); null } catch (e: IllegalArgumentException) { e }
        assertThat(ended?.message).contains("COMPLETED")
        val failed = service.start("s", RunTarget(assetId = "music"), "配乐")
        service.fail("s", failed.id, "脚本失败")
        val lateFinish = try { service.finish("s", failed.id, "p/music.wav"); null } catch (e: IllegalArgumentException) { e }
        assertThat(lateFinish?.message).contains("FAILED")
        val canceled = service.start("s", RunTarget(assetId = "music"), "配乐")
        store.snapshot = reviewApply(store.snapshot, ProductionUserAction.CancelRun(canceled.id), 100)
        assertThat(store.snapshot.production.runs.last().message).isEqualTo("用户已取消")
        val lateFail = try { service.fail("s", canceled.id, "迟到"); null } catch (e: IllegalArgumentException) { e }
        assertThat(lateFail?.message).contains("CANCELED")
        assertThat(auditCount).isEqualTo(2)
    }
    @Test fun `登记声音资产遵循定稿版本规则且缺失产物不结束登记`() = runTest {
        val store = ProgressStore()
        store.snapshot = store.snapshot.copy(production = store.snapshot.production.copy(assets = store.snapshot.production.assets.map { if (it.id == "music") it.copy(versions = listOf(AssetVersion(1, "p/music.wav", 20)), currentVersion = 1, status = AssetStatus.LOCKED) else it }))
        val service = ProductionRunService(store, { _, _ -> MediaSpec(durationSeconds = 8.0) }, { _, path -> path != "p/missing.wav" }, now = { 30 }, newId = { "run" })
        service.start("s", RunTarget(assetId = "music"), "配乐")
        val missing = try { service.finish("s", "run", "p/missing.wav"); null } catch (e: IllegalArgumentException) { e }
        assertThat(missing).isNotNull(); assertThat(store.snapshot.production.runs.single().isActive()).isTrue()
        val result = service.finish("s", "run", "p/result.wav")
        assertThat(result.resultAssetVersion).isEqualTo(2)
        val asset = store.snapshot.production.assets.first { it.id == "music" }
        assertThat(asset.currentVersion).isEqualTo(1); assertThat(asset.status).isEqualTo(AssetStatus.LOCKED)
        assertThat(asset.versions.last().durationSeconds).isEqualTo(8.0)
    }
    @Test fun `文件审计排除候选资产链段登记产物剪辑等全部引用且仅变化写入`() = runTest {
        val store = ProgressStore()
        val p = store.snapshot.production
        val chain = ShotChain("chain", ChainState.FAILED, listOf(ChainSegment(0, 3.0, state = ChainSegmentState.COMPLETED, path = "p/chain.mp4", firstFramePath = "p/frame.png")), createdAt = 0, updatedAt = 0)
        val run = ProductionRun("run", RunTarget(assetId = "music"), "外部", state = RunState.FAILED, startedAt = 0, reportedAt = 0, resultPath = "p/run.wav")
        store.snapshot = store.snapshot.copy(production = p.mapShot("S1E01", "SC01-01") { it.copy(chains = listOf(chain), takes = it.takes.map { t -> if (t.id == "t1") t.copy(segments = listOf(TakeSegment(0, "p/part.mp4", 0.0, 3.0))) else t }) }.copy(runs = listOf(run), cuts = p.cuts.map { it.copy(export = CutExportRecord("p/cuts/video.mp4", "p/cuts/a.srt", 3.0, 1, 30)) }))
        val paths = listOf("p/1.mp4", "p/2.mp4", "p/1.png", "p/2.png", "p/chain.mp4", "p/frame.png", "p/run.wav", "p/part.mp4", "p/cuts/video.mp4", "p/z.mp4", "p/a.wav", "else/file.mp4", "p/text.txt")
        val audit = ProductionFileAudit(store) { _, _ -> paths }
        audit.scan("s"); assertThat(store.snapshot.production.unregisteredFiles).containsExactly("p/a.wav", "p/z.mp4").inOrder()
        val writes = store.writes; audit.scan("s"); assertThat(store.writes).isEqualTo(writes)
    }
    @Test fun `未结事项包含未回报链失败未登记取消并限制篇幅`() {
        val p = reviewFixture().production
        val run = ProductionRun("run", RunTarget(assetId = "char"), "外部编排", startedAt = 0, reportedAt = 0)
        val canceled = run.copy(id = "cancel", state = RunState.CANCELED)
        val chain = ShotChain("chain", ChainState.FAILED, listOf(ChainSegment(0, 3.0)), failure = "模板已变化", createdAt = 0, updatedAt = 0)
        val full = p.mapShot("S1E01", "SC01-01") { it.copy(chains = listOf(chain)) }.copy(runs = listOf(run, canceled), unregisteredFiles = List(30) { "p/file-$it.mp4" }.sorted())
        val items = productionOpenItems(full, emptyList(), productionCostStats(full, emptyList()), RUN_UNREPORTED_MILLIS)
        assertThat(items.filterIsInstance<OpenItem.Run>().first().unreported).isTrue()
        val summary = productionOpenItemsSummary(full, now = RUN_UNREPORTED_MILLIS, maxChars = 600)
        assertThat(summary.length).isAtMost(600)
        assertThat(summary).contains("未回报"); assertThat(summary).contains("用户已取消")
        assertThat(summary).contains("production_get 的 changes")
        assertThat(summary).contains("共 33 条")
    }
}

class ProductionReportAuditRegressionTest {
    @Test fun `审计异常不把已成功登记的回报转换成失败`() = runTest {
        val store = ProgressStore()
        val service = ProductionRunService(store, { _, _ -> MediaSpec(durationSeconds = 3.0) }, { _, _ -> true }, { throw IllegalArgumentException("目录已失效") }, now = { 30 }, newId = { "r" })
        service.start("s", RunTarget(assetId = "music"), "配乐")
        assertThat(service.finish("s", "r", "p/audio.wav").state).isEqualTo(RunState.COMPLETED)
        assertThat(store.snapshot.production.runs.single().state).isEqualTo(RunState.COMPLETED)
    }
    @Test fun `冻结规划仍引用的历史素材不会被视为未登记`() {
        val p = reviewFixture().production.mapShot("S1E01", "SC01-01") { shot -> shot.copy(inputs = emptyList(), takes = shot.takes.map { take -> take.copy(planSnapshot = TakePlanSnapshot(mode = ShotMode.REFERENCE, inputs = listOf(ShotInput(InputRole.REFERENCE, "p/old.png")), prompt = "图1", mediaInputs = listOf(com.promenar.infinity.domain.mediagen.MediaInput("p/voice.wav", com.promenar.infinity.domain.mediagen.InputKind.AUDIO, com.promenar.infinity.domain.mediagen.InputRole.REFERENCE_VOICE)))) }) }
        assertThat(findUnregisteredProductionFiles(p, listOf("p/old.png", "p/voice.wav", "p/orphan.png"))).containsExactly("p/orphan.png")
    }
}
