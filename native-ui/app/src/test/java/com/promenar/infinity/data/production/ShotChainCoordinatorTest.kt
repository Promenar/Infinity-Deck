package com.promenar.infinity.data.production

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

internal class FakeChainMedia : ChainMediaOps {
    val tails = mutableListOf<Pair<String, String>>()
    val copies = mutableListOf<Pair<String, String>>()
    val joined = mutableListOf<List<String>>()
    override suspend fun spec(sessionId: String, path: String) = MediaSpec(320, 240, 25.0, 3.0)
    override suspend fun copy(sessionId: String, source: String, target: String) { copies += source to target }
    override suspend fun tailFrame(sessionId: String, video: String, target: String) { tails += video to target }
    override suspend fun concatenate(sessionId: String, paths: List<String>, target: String): ChainConcatenation {
        joined += paths
        val segments = chainTimeline(paths, paths.map { spec(sessionId, it) })
        return ChainConcatenation(MediaSpec(320, 240, 25.0, segments.last().endSeconds), segments)
    }
}
internal class ChainFixture {
    val store = ProgressStore()
    val queue = ProgressQueue()
    val media = FakeChainMedia()
    var fingerprint = "a"
    val channel = MediaChannel("video", MediaGroup.VIDEO, MediaCapabilities(setOf(GenerationMode.TEXT, GenerationMode.FIRST_FRAME), minDurationSeconds = 1, maxDurationSeconds = 10))
    val planner = object : ShotGenerationPlanner {
        override suspend fun preview(sessionId: String, episodeId: String, shotCode: String) = ShotPlanPreview(channel, true, "视频", ShotMode.TEXT, emptyList(), emptyList(), "云穿过山峰", false, PromptFormat.GENERIC, null, null, emptyList())
        override suspend fun generate(sessionId: String, episodeId: String, shotCode: String): ShotGenerateResult = error("不调用普通镜头提交")
        override suspend fun recompilePrompt(sessionId: String, episodeId: String, shotCode: String) = "云穿过山峰"
    }
    fun coordinator(actualQueue: MediaGenerationQueue = queue) = ShotChainCoordinator(store, ProgressRegistry(channel), actualQueue, planner, media, { fingerprint }, now = { 100 }, newId = { "chain" })
    fun chain() = store.shot().chains.single()
}

class ShotChainCoordinatorTest {
    @Test fun `三段正常推进后续只带首帧最终仅登记一个候选`() = runTest {
        val f = ChainFixture(); val c = f.coordinator()
        c.start("s", "S1E01", "SC01-01", segments = 3)
        repeat(14) { c.reconcile("s"); if (f.queue.tasks.value.lastOrNull()?.state == MediaTaskState.QUEUED) f.queue.completeLast() }
        assertThat(f.chain().state).isEqualTo(ChainState.COMPLETED)
        assertThat(f.queue.tasks.value).hasSize(3)
        assertThat(f.queue.tasks.value.first().request.mode).isEqualTo(GenerationMode.TEXT)
        f.queue.tasks.value.drop(1).forEach { task ->
            assertThat(task.request.mode).isEqualTo(GenerationMode.FIRST_FRAME)
            assertThat(task.request.inputs).hasSize(1)
            assertThat(task.request.inputs.single().role).isEqualTo(com.promenar.infinity.domain.mediagen.InputRole.FIRST_FRAME)
        }
        val take = f.store.shot().takes.last()
        assertThat(take.chainId).isEqualTo("chain")
        assertThat(take.segments.last().endSeconds).isWithin(0.00001).of(8.92)
        assertThat(requireNotNull(take.spec?.durationSeconds)).isWithin(0.00001).of(8.92)
        assertThat(f.store.shot().takes).hasSize(3)
        c.reconcile("s")
        assertThat(f.media.joined).hasSize(1)
        assertThat(f.media.tails).hasSize(2)
    }
    @Test fun `起点候选直接为段零且失败后只重做该段及之后`() = runTest {
        val f = ChainFixture(); val c = f.coordinator()
        c.start("s", "S1E01", "SC01-01", startTakeId = "t1", segments = 3)
        repeat(3) { c.reconcile("s") }
        assertThat(f.media.copies.first().first).isEqualTo("p/1.mp4")
        assertThat(f.queue.tasks.value.single().request.productionContext?.segmentIndex).isEqualTo(1)
        f.queue.tasks.value = f.queue.tasks.value.map { it.copy(state = MediaTaskState.FAILED, error = "段失败") }
        c.reconcile("s")
        assertThat(f.chain().state).isEqualTo(ChainState.FAILED)
        assertThat(f.chain().current).isEqualTo(1)
        c.retry("s", "S1E01", "SC01-01", "chain")
        assertThat(f.chain().segments[0].state).isEqualTo(ChainSegmentState.COMPLETED)
        assertThat(f.chain().segments[1].taskId).isNull()
        repeat(10) { c.reconcile("s"); if (f.queue.tasks.value.lastOrNull()?.state == MediaTaskState.QUEUED) f.queue.completeLast() }
        assertThat(f.chain().state).isEqualTo(ChainState.COMPLETED)
        assertThat(f.queue.tasks.value.map { it.request.productionContext?.segmentIndex }).containsExactly(1, 1, 2).inOrder()
    }
    @Test fun `CancelChain保留已完成段且对账取消正在跑的任务`() = runTest {
        val f = ChainFixture(); val c = f.coordinator()
        c.start("s", "S1E01", "SC01-01", startTakeId = "t1")
        repeat(3) { c.reconcile("s") }
        f.store.snapshot = reviewApply(f.store.snapshot, ProductionUserAction.CancelChain("S1E01", "SC01-01", "chain"), 100)
        assertThat(f.chain().segments[0].state).isEqualTo(ChainSegmentState.COMPLETED)
        assertThat(f.chain().segments.drop(1).map { it.state }.distinct()).containsExactly(ChainSegmentState.CANCELED)
        c.reconcile("s")
        assertThat(f.queue.canceled).containsExactly("q0")
        val failure = try { c.cancel("s", "S1E01", "SC01-01", "chain"); null } catch (e: IllegalArgumentException) { e }
        assertThat(failure?.message).contains("无法取消")
    }
    @Test fun `重启与提交后快照冲突可恢复任务而不重复入队`() = runTest {
        val f = ChainFixture(); val c = f.coordinator()
        c.start("s", "S1E01", "SC01-01")
        f.store.conflictNext = true
        c.reconcile("s")
        assertThat(f.queue.tasks.value).hasSize(1)
        assertThat(f.chain().segments[0].taskId).isNull()
        val restored = f.coordinator()
        restored.reconcile("s")
        assertThat(f.queue.tasks.value).hasSize(1)
        assertThat(f.chain().segments[0].taskId).isEqualTo("q0")
        f.queue.completeLast(); restored.reconcile("s"); restored.reconcile("s")
        assertThat(f.chain().current).isEqualTo(1)
    }
    @Test fun `下一段提交前模板变化停在该段并允许显式重试`() = runTest {
        val f = ChainFixture(); val c = f.coordinator()
        c.start("s", "S1E01", "SC01-01", startTakeId = "t1")
        c.reconcile("s"); f.fingerprint = "b"; c.reconcile("s")
        assertThat(f.chain().state).isEqualTo(ChainState.FAILED)
        assertThat(f.chain().failure).isEqualTo("模板已变化")
        assertThat(f.chain().current).isEqualTo(1)
        assertThat(f.queue.tasks.value).isEmpty()
        c.retry("s", "S1E01", "SC01-01", "chain"); c.reconcile("s")
        assertThat(f.chain().templateFingerprint).isEqualTo("b")
        assertThat(f.queue.tasks.value).hasSize(1)
    }
    @Test fun `每镜头只能有一条进行中链且段数时长受限`() = runTest {
        val f = ChainFixture(); val c = f.coordinator()
        for (segments in listOf(0, 9)) {
            val failure = try { c.start("s", "S1E01", "SC01-01", segments = segments); null } catch (e: IllegalArgumentException) { e }
            assertThat(failure).isNotNull()
        }
        val invalidDuration = try { c.start("s", "S1E01", "SC01-01", segmentSeconds = 11.0); null } catch (e: IllegalArgumentException) { e }
        assertThat(invalidDuration).isNotNull()
        c.start("s", "S1E01", "SC01-01")
        val duplicate = try { c.start("s", "S1E01", "SC01-01"); null } catch (e: IllegalArgumentException) { e }
        assertThat(duplicate?.message).contains("已有进行中")
    }
    @Test fun `按各段帧率裁首帧且时间轴连续`() {
        val timeline = chainTimeline(listOf("a", "b", "c"), listOf(MediaSpec(fps = 25.0, durationSeconds = 2.0), MediaSpec(fps = 50.0, durationSeconds = 3.0), MediaSpec(fps = 25.0, durationSeconds = 4.0)))
        assertThat(timeline.map { it.startSeconds }).containsExactly(0.0, 2.0, 4.98).inOrder()
        assertThat(timeline.last().endSeconds).isWithin(0.00001).of(8.94)
    }
}

class ShotChainUnknownTaskRegressionTest {
    @Test fun `未知提交需要先核实不会直接生成新代次`() = runTest {
        val f = ChainFixture(); val c = f.coordinator()
        c.start("s", "S1E01", "SC01-01"); c.reconcile("s")
        f.queue.tasks.value = f.queue.tasks.value.map { it.copy(state = MediaTaskState.RESULT_UNKNOWN) }
        c.reconcile("s")
        assertThat(f.chain().state).isEqualTo(ChainState.FAILED)
        val error = try { c.retry("s", "S1E01", "SC01-01", "chain"); null } catch (e: IllegalArgumentException) { e }
        assertThat(error?.message).contains("先核实原任务")
        assertThat(f.queue.tasks.value).hasSize(1)
    }
}

class ChainFrameBoundaryRegressionTest {
    @Test fun `B帧解码顺序和可变首帧间隔按呈现时间裁帧`() {
        assertThat(firstFrameEndUs(sequenceOf(0L, 120_000L, 40_000L, 80_000L))).isEqualTo(40_000L)
        val trims = listOf(0.0, firstFrameEndUs(sequenceOf(0L, 120_000L, 20_000L, 80_000L)) / 1_000_000.0)
        val timeline = chainTimeline(listOf("a", "b"), List(2) { MediaSpec(fps = 25.0, durationSeconds = 1.0) }, trims)
        assertThat(timeline.last().endSeconds).isWithin(0.00001).of(1.98)
    }
}

class ShotChainArchivedTaskRegressionTest {
    @Test fun `人工归档后链可重试且新任务使用新的暂存代次`() = runTest {
        val f = ChainFixture(); val c = f.coordinator()
        c.start("s", "S1E01", "SC01-01"); c.reconcile("s")
        f.queue.tasks.value = f.queue.tasks.value.map { it.copy(state = MediaTaskState.RESULT_UNKNOWN) }
        c.reconcile("s")
        val archivedQueue = object : MediaGenerationQueue by f.queue, MediaTaskRecoveryReader {
            override suspend fun unresolvedTaskIds() = emptySet<String>()
        }
        val restored = f.coordinator(archivedQueue)
        restored.retry("s", "S1E01", "SC01-01", "chain"); restored.reconcile("s")
        assertThat(f.queue.tasks.value).hasSize(2)
        assertThat(f.queue.tasks.value.last().request.outputDir).endsWith("/attempt-1")
        assertThat(f.queue.tasks.value.first().request.outputDir).endsWith("/attempt-0")
    }
}
