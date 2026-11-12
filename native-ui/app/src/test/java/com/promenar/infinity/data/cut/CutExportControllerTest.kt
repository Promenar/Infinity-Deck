package com.promenar.infinity.data.cut

import com.promenar.infinity.data.production.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CutExportControllerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val messages = CutExportMessages("繁忙", "无项目", "无版本", "已变化", "失败", "中断")
    private fun production(): Production {
        val shot = Shot("SC01-01", "镜头", durationSeconds = 2.0, takes = listOf(Take("take", "p/take.mp4", TakeSource("test"), 1)), selectedTakeId = "take")
        return Production("project", "项目", ProductionKind.FILM, "p", createdAt = 1, updatedAt = 1,
            seasons = listOf(Season("S1", 1, episodes = listOf(Episode("S1E01", 1, "集", scenes = listOf(Scene("SC01", 1, "场", shots = listOf(shot))))))))
    }
    private class Store(p: Production) : CutProjectStore {
        var snapshot = ProductionSnapshot(p, 1, ManifestSync.InSync)
        override suspend fun load(sessionId: String) = snapshot
        override suspend fun apply(sessionId: String, productionId: String, revision: Long, action: ProductionUserAction): ProductionSnapshot {
            require(snapshot.production.id == productionId)
            snapshot = ProductionUserEngine.apply(snapshot, revision, action, 100)
            return snapshot
        }
    }
    private fun output() = object : CutOutputStore {
        override suspend fun publish(sessionId: String, plan: CutCompositionPlan, video: File, register: suspend (CutExportRecord) -> Unit): CutExportRecord {
            val record = CutExportRecord(plan.videoPath, plan.subtitlePath, plan.durationSeconds, video.length(), 100)
            register(record)
            return record
        }
    }
    private fun artifact(): CutExportArtifact {
        val folder = temporary.newFolder()
        return CutExportArtifact(File(folder, "output.mp4").apply { writeBytes(byteArrayOf(1, 2)) }, folder)
    }

    @Test fun `创建冻结实测时长且测量失败回落计划`() = runBlocking {
        val store = Store(production())
        val unused = CutExporter { _, _, _ -> error("不应导出") }
        val measured = DefaultCutExportController(store, CutMediaProbe { _, _ -> CutMediaMetadata(durationSeconds = 3.4) }, unused, output(), { _, _ -> }, messages)
        assertEquals(3.4, measured.createVersion("s", "S1E01").clips.single().seconds, 0.0)
        val fallback = DefaultCutExportController(store, CutMediaProbe { _, _ -> error("读取失败") }, unused, output(), { _, _ -> }, messages)
        assertEquals(2.0, fallback.createVersion("s", "S1E01").clips.single().seconds, 0.0)
    }

    @Test fun `测量期间修订变化拒绝提交旧快照`() = runBlocking {
        val store = Store(production())
        val controller = DefaultCutExportController(store, CutMediaProbe { _, _ ->
            store.snapshot = store.snapshot.copy(revision = 2)
            CutMediaMetadata(durationSeconds = 3.0)
        }, CutExporter { _, _, _ -> error("不应导出") }, output(), { _, _ -> }, messages)
        try { controller.createVersion("s", "S1E01"); fail() } catch (_: ProductionRevisionConflictException) { }
        assertTrue(store.snapshot.production.cuts.isEmpty())
    }

    @Test fun `导出立即返回且执行成功后登记与清理暂存`() = runBlocking {
        val store = Store(production())
        val exported = artifact()
        var starts = 0
        var executions = 0
        val controller = DefaultCutExportController(store, CutMediaProbe { _, _ -> CutMediaMetadata(2.0, 320, 240, false) },
            CutExporter { _, _, progress -> executions++; progress(2f); exported }, output(), { _, _ -> starts++ }, messages)
        val version = controller.createVersion("s", "S1E01")
        controller.export("s", version.id)
        assertEquals(1, starts)
        assertEquals(0, executions)
        assertTrue(controller.observe().value[version.id] is CutExportState.Running)
        controller.runPending()
        val result = controller.observe().value[version.id] as CutExportState.Succeeded
        assertEquals(result.record, store.snapshot.production.cuts.single().export)
        assertFalse(exported.video.exists())
    }

    @Test fun `全局槽位拒绝重入并且取消释放后允许重试`() = runBlocking {
        withTimeout(5000) {
            val store = Store(production())
            val started = CompletableDeferred<Unit>()
            val controller = DefaultCutExportController(store, CutMediaProbe { _, _ -> CutMediaMetadata(2.0, 320, 240, false) },
                CutExporter { _, _, _ -> started.complete(Unit); awaitCancellation() }, output(), { _, _ -> }, messages)
            val version = controller.createVersion("s", "S1E01")
            controller.export("s", version.id)
            try { controller.export("s", version.id); fail() } catch (_: IllegalArgumentException) { }
            val worker = launch { controller.runPending() }
            started.await()
            try { controller.setBurnSubtitles("s", version.id, true); fail() } catch (_: IllegalArgumentException) { }
            controller.cancel(version.id)
            worker.join()
            assertEquals(CutExportState.Idle, controller.observe().value[version.id])
            assertNull(store.snapshot.production.cuts.single().export)
            controller.export("s", version.id)
            controller.cancel(version.id)
            controller.runPending()
            assertEquals(CutExportState.Idle, controller.observe().value[version.id])
        }
    }

    @Test fun `服务启动失败和服务中止均显示明确失败且释放槽位`() = runBlocking {
        val store = Store(production())
        var throwOnStart = true
        val controller = DefaultCutExportController(store, CutMediaProbe { _, _ -> error("不应测量") }, CutExporter { _, _, _ -> error("不应导出") },
            output(), { _, _ -> if (throwOnStart) error("后台启动被拒绝") }, messages)
        // 创建标题卡版本不需要访问媒体。
        store.snapshot = store.snapshot.copy(production = production().mapShot("S1E01", "SC01-01") { it.copy(selectedTakeId = null) })
        val version = controller.createVersion("s", "S1E01")
        try { controller.export("s", version.id); fail() } catch (_: IllegalStateException) { }
        assertEquals(CutExportState.Failed("中断"), controller.observe().value[version.id])
        throwOnStart = false
        controller.export("s", version.id)
        controller.serviceStopped()
        assertEquals(CutExportState.Failed("中断"), controller.observe().value[version.id])
    }

    @Test fun `旧服务迟到的停止回调不影响新请求`() = runBlocking {
        val store = Store(production())
        val leases = mutableListOf<Long>()
        val controller = DefaultCutExportController(store, CutMediaProbe { _, _ -> CutMediaMetadata(2.0) }, CutExporter { _, _, _ -> error("不应执行") },
            output(), { lease, _ -> leases += lease }, messages)
        val cut = controller.createVersion("s", "S1E01")
        controller.export("s", cut.id)
        controller.cancel(cut.id)
        controller.export("s", cut.id)
        controller.serviceStopped(leases.first())
        controller.runPending(leases.first())
        assertTrue(controller.hasPending(leases.last()))
        assertTrue(controller.observe().value[cut.id] is CutExportState.Running)
        controller.cancel(cut.id)
    }

    @Test fun `繁忙时不修改其它版本的字幕或导出记录`() = runBlocking {
        val store = Store(production())
        val controller = DefaultCutExportController(store, CutMediaProbe { _, _ -> CutMediaMetadata(2.0) }, CutExporter { _, _, _ -> error("不应执行") },
            output(), { _, _ -> }, messages)
        val first = controller.createVersion("s", "S1E01")
        val second = controller.createVersion("s", "S1E01")
        controller.export("s", first.id)
        try { controller.export("s", second.id, true); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(second, store.snapshot.production.cuts.first { it.id == second.id })
        controller.cancel(first.id)
    }

    @Test fun `工作区切换和素材缩短均在启动Transformer前失败`() = runBlocking {
        val store = Store(production())
        var identity = 1
        var duration = 2.0
        var exports = 0
        val controller = DefaultCutExportController(store, CutMediaProbe { _, _ -> CutMediaMetadata(duration) },
            CutExporter { _, _, _ -> exports++; error("不应执行") }, output(), { _, _ -> }, messages,
            captureWorkspace = { val captured = identity; CutWorkspaceBinding(verify = { require(identity == captured) }) })
        val cut = controller.createVersion("s", "S1E01")
        controller.export("s", cut.id)
        identity++
        controller.runPending()
        assertTrue(controller.observe().value[cut.id] is CutExportState.Failed)
        assertFalse(controller.hasPending())
        duration = 1.0
        controller.export("s", cut.id)
        controller.runPending()
        assertTrue(controller.observe().value[cut.id] is CutExportState.Failed)
        assertEquals(0, exports)
    }

    @Test fun `登记已提交后取消保持成功且释放槽位`() = runBlocking {
        withTimeout(5000) {
            val base = Store(production())
            val committed = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val store = object : CutProjectStore by base {
                override suspend fun apply(sessionId: String, productionId: String, revision: Long, action: ProductionUserAction): ProductionSnapshot {
                    val result = base.apply(sessionId, productionId, revision, action)
                    if (action is ProductionUserAction.RegisterCutExport) { committed.complete(Unit); release.await() }
                    return result
                }
            }
            val controller = DefaultCutExportController(store, CutMediaProbe { _, _ -> CutMediaMetadata(2.0) },
                CutExporter { _, _, _ -> artifact() }, output(), { _, _ -> }, messages)
            val cut = controller.createVersion("s", "S1E01")
            controller.export("s", cut.id)
            val worker = launch { controller.runPending() }
            try {
                committed.await()
                controller.cancel(cut.id)
            } finally { release.complete(Unit) }
            worker.join()
            assertNotNull(base.snapshot.production.cuts.single().export)
            assertTrue(controller.observe().value[cut.id] is CutExportState.Succeeded)
            assertFalse(controller.hasPending())
        }
    }

    @Test fun `已知导出失败映射简短原因且不登记结果`() = runBlocking {
        val store = Store(production())
        var reason = CutFailureReason.MEDIA_UNAVAILABLE
        val text = messages.copy(mediaUnavailable = "媒体", unsupportedSize = "尺寸", sourceChanged = "时长", invalidOutput = "产物", encodingFailed = "编码")
        val controller = DefaultCutExportController(store, CutMediaProbe { _, _ -> CutMediaMetadata(2.0) },
            CutExporter { _, _, _ -> throw CutExportFailure(reason) }, output(), { _, _ -> }, text)
        val cut = controller.createVersion("s", "S1E01")
        listOf(CutFailureReason.MEDIA_UNAVAILABLE to "媒体", CutFailureReason.UNSUPPORTED_SIZE to "尺寸", CutFailureReason.SOURCE_CHANGED to "时长",
            CutFailureReason.INVALID_OUTPUT to "产物", CutFailureReason.ENCODING_FAILED to "编码").forEach { (failure, expected) ->
            reason = failure
            controller.export("s", cut.id)
            controller.runPending()
            assertEquals(CutExportState.Failed(expected), controller.observe().value[cut.id])
            assertNull(store.snapshot.production.cuts.single().export)
        }
    }
}
