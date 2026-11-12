package com.promenar.infinity.data.mediagen.queue

import com.promenar.infinity.domain.mediagen.*
import java.math.BigDecimal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PersistentMediaGenerationQueueTest {
    private val channel = MediaChannel("video", MediaGroup.VIDEO, MediaCapabilities(setOf(GenerationMode.TEXT, GenerationMode.REFERENCE), maxImages = 1))
    private val request = MediaGenerationRequest(channel.id, GenerationMode.TEXT, "风吹过树林", emptyList(), "project/shot1", sessionId = "session", outputDir = "takes")
    private val money = Money(BigDecimal("1.25"), "USD")
    private val approved = AutoSubmitPolicy { _, _ -> true }
    private val store = MemoryMediaTaskStore()
    private val registry = object : MediaChannelRegistry {
        override fun observeChannels() = flowOf(listOf(channel))
        override fun observeOrder(group: MediaGroup) = flowOf(listOf(channel.id))
        override suspend fun setOrder(group: MediaGroup, channelIds: List<String>) = Unit
        override suspend fun channel(id: String) = channel.takeIf { it.id == id }
    }
    private inner class Backend : MediaGenerationBackend {
        var estimate: Money? = money
        var actual: Money? = money
        var submitted = 0
        var polled = 0
        var downloaded = 0
        var downloadFails = false
        var submitFails = false
        var statuses = ArrayDeque<RemoteStatus>()
        var onSubmit: suspend () -> Unit = {}
        var onPoll: suspend () -> Unit = {}
        override fun handles(channel: MediaChannel) = true
        override suspend fun estimate(channel: MediaChannel, request: MediaGenerationRequest) = estimate
        override suspend fun submit(channel: MediaChannel, request: MediaGenerationRequest): String {
            submitted++
            assertTrue(store.all().any { it.task.state == MediaTaskState.SUBMITTING })
            onSubmit()
            if (submitFails) error("传输中断")
            return "remote-$submitted"
        }
        override suspend fun poll(channel: MediaChannel, remoteId: String): RemoteStatus {
            polled++
            assertEquals(remoteId, store.all().first { it.task.remoteId == remoteId }.task.remoteId)
            onPoll()
            return statuses.removeFirstOrNull() ?: RemoteStatus.Succeeded(listOf(RemoteOutput("remote.mp4", InputKind.VIDEO)), mapOf("seconds" to 5))
        }
        override suspend fun download(channel: MediaChannel, request: MediaGenerationRequest, remoteId: String, outputs: List<RemoteOutput>): List<String> {
            downloaded++
            assertEquals(MediaTaskState.DOWNLOADING, store.all().first { it.task.remoteId == remoteId }.task.state)
            if (downloadFails) error("磁盘不可写")
            return listOf("${request.outputDir}/$remoteId.mp4")
        }
        override fun actualCost(channel: MediaChannel, usage: Map<String, Long>) = actual
    }
    private fun TestScope.queue(backend: Backend, policy: AutoSubmitPolicy = approved, concurrency: MediaQueueConcurrency = MediaQueueConcurrency(), wake: () -> Unit = {}) =
        PersistentMediaGenerationQueue(store, registry, listOf(MediaBackendBinding("backend", "provider", { "model" }, backend)), policy,
            concurrency = concurrency, clock = { testScheduler.currentTime }, ioDispatcher = StandardTestDispatcher(testScheduler), wake = wake,
            polling = MediaPollingPolicy(initialMillis = 100, maximumMillis = 400, maxConsecutiveErrors = 2))

    @Test fun `超限请求返回清单且不估价入队`() = runTest {
        val backend = Backend()
        val inputs = listOf("one.png", "two.png", "three.png").map { MediaInput(it, InputKind.IMAGE, InputRole.REFERENCE) }
        try { queue(backend).submit(request.copy(mode = GenerationMode.REFERENCE, inputs = inputs)); fail() }
        catch (error: InvalidMediaRequestException) { assertEquals(inputs.drop(1), error.validation.excessInputs) }
        assertTrue(store.all().isEmpty())
        assertEquals(0, backend.submitted)
    }
    @Test fun `默认需批准批准后入队拒绝删除`() = runTest {
        val backend = Backend()
        val queue = queue(backend, RequireApproval)
        val first = queue.submit(request)
        val second = queue.submit(request)
        assertEquals(MediaTaskState.AWAITING_APPROVAL, first.state)
        queue.reject(second.id)
        assertNull(queue.observeTask(second.id).first())
        queue.approve(first.id)
        queue.runUntilIdle()
        assertEquals(MediaTaskState.COMPLETED, queue.observeTask(first.id).first()!!.state)
        assertEquals(1, backend.submitted)
    }
    @Test fun `未知估价不能自动提交且未知实际费用不入零`() = runTest {
        val backend = Backend().apply { estimate = null; actual = null }
        val queue = queue(backend)
        val task = queue.submit(request)
        assertEquals(MediaTaskState.AWAITING_APPROVAL, task.state)
        assertNull(task.estimate)
        queue.approve(task.id)
        queue.runUntilIdle()
        assertNull(store.usage.values.single().cost)
        assertNull(queue.observeTask(task.id).first()!!.actualCost)
    }
    @Test fun `成功先持久化再唤醒且费用只入账一次`() = runTest {
        val backend = Backend()
        val queue = queue(backend, wake = { assertTrue(store.snapshots.value.isNotEmpty()) })
        val task = queue.submit(request)
        assertEquals(MediaTaskState.QUEUED, task.state)
        queue.runUntilIdle()
        queue.runUntilIdle()
        val completed = queue.observeTask(task.id).first()!!
        assertEquals(listOf("takes/remote-1.mp4"), completed.outputPaths)
        assertEquals(money, store.usage.values.single().cost)
        assertEquals("provider", store.usage.values.single().providerId)
        assertEquals("model", store.usage.values.single().modelId)
        assertEquals(1, backend.submitted)
    }
    @Test fun `下载失败保留远端成功与费用只重试下载`() = runTest {
        val backend = Backend().apply { downloadFails = true }
        val queue = queue(backend)
        val task = queue.submit(request)
        queue.runUntilIdle()
        assertEquals(MediaTaskState.DOWNLOAD_FAILED, queue.observeTask(task.id).first()!!.state)
        assertEquals(1, store.usage.size)
        backend.downloadFails = false
        queue.retry(task.id)
        queue.runUntilIdle()
        assertEquals(MediaTaskState.COMPLETED, queue.observeTask(task.id).first()!!.state)
        assertEquals(1, backend.submitted)
        assertEquals(1, backend.polled)
        assertEquals(2, backend.downloaded)
        assertEquals(1, store.usage.size)
    }
    @Test fun `轮询逐步退避并持久化下一次查询时间`() = runTest {
        val backend = Backend().apply { repeat(4) { statuses.add(RemoteStatus.Running(.5f)) } }
        val queue = queue(backend)
        val task = queue.submit(request)
        val times = mutableListOf<Long>()
        backend.onPoll = { times += testScheduler.currentTime }
        queue.runUntilIdle()
        assertEquals(listOf(0L, 100L, 300L, 700L, 1100L), times)
        assertEquals(1f, queue.observeTask(task.id).first()!!.progress)
    }
    @Test fun `提供商与通道上限控制远端在途任务`() = runTest {
        val backend = Backend()
        var active = 0
        var peak = 0
        backend.onSubmit = { active++; peak = maxOf(peak, active) }
        backend.onPoll = { kotlinx.coroutines.delay(100); active-- }
        val queue = queue(backend, concurrency = MediaQueueConcurrency(maxTotal = 3, defaultProvider = 2, defaultChannel = 1))
        repeat(3) { queue.submit(request) }
        queue.runUntilIdle()
        assertEquals(1, peak)
        assertEquals(3, backend.submitted)
    }
    @Test fun `有远端ID的恢复只续查`() = runTest {
        val backend = Backend()
        val first = queue(backend)
        val task = first.submit(request)
        val saved = store.get(task.id)!!
        store.save(saved.copy(task = task.started("remote-recovered")))
        queue(backend).runUntilIdle()
        assertEquals(0, backend.submitted)
        assertEquals(1, backend.polled)
        assertEquals(MediaTaskState.COMPLETED, store.get(task.id)!!.task.state)
    }
    @Test fun `提交中恢复和提交异常标为结果未知禁止重试`() = runTest {
        val backend = Backend()
        val first = queue(backend)
        val task = first.submit(request)
        store.save(store.get(task.id)!!.copy(task = task.submitting()))
        val recovered = queue(backend)
        recovered.runUntilIdle()
        assertEquals(MediaTaskState.RESULT_UNKNOWN, store.get(task.id)!!.task.state)
        assertEquals(0, backend.submitted)
        try { recovered.retry(task.id); fail() } catch (_: IllegalStateException) { }
        recovered.archiveUnknown(task.id)
        val failedBackend = Backend().apply { submitFails = true }
        val next = queue(failedBackend)
        val second = next.submit(request)
        next.runUntilIdle()
        assertEquals(MediaTaskState.RESULT_UNKNOWN, store.get(second.id)!!.task.state)
    }
    @Test fun `查询失败保留远端ID手动重试不重提`() = runTest {
        val backend = Backend().apply { onPoll = { error("离线") } }
        val queue = queue(backend)
        val task = queue.submit(request)
        queue.runUntilIdle()
        assertEquals(MediaTaskFailure.POLL, store.get(task.id)!!.task.failure)
        backend.onPoll = {}
        queue.retry(task.id)
        queue.runUntilIdle()
        assertEquals(1, backend.submitted)
        assertEquals(MediaTaskState.COMPLETED, store.get(task.id)!!.task.state)
    }
    @Test fun `远端失败重试重新估价走批准且不沿用失败ID`() = runTest {
        val backend = Backend().apply { statuses.add(RemoteStatus.Failed("失败")) }
        var auto = true
        val queue = queue(backend, AutoSubmitPolicy { _, _ -> auto })
        val task = queue.submit(request)
        queue.runUntilIdle()
        assertEquals(MediaTaskFailure.REMOTE, store.get(task.id)!!.task.failure)
        auto = false
        queue.retry(task.id)
        assertEquals(MediaTaskState.AWAITING_APPROVAL, store.get(task.id)!!.task.state)
        assertNull(store.get(task.id)!!.task.remoteId)
        queue.approve(task.id)
        queue.runUntilIdle()
        assertEquals(2, backend.submitted)
    }
    @Test fun `运行中加入的任务可以立即调度并按来源过滤`() = runTest {
        val backend = Backend()
        val queue = queue(backend, concurrency = MediaQueueConcurrency(maxTotal = 2, defaultProvider = 2, defaultChannel = 2))
        val gate = CompletableDeferred<Unit>()
        backend.onPoll = { if (backend.polled == 1) gate.await() }
        queue.submit(request)
        val running = launch { queue.runUntilIdle() }
        runCurrent()
        queue.submit(request.copy(sourceRef = "other/shot"))
        runCurrent()
        assertEquals(2, backend.submitted)
        assertEquals(1, queue.observeTasks("project/").first().size)
        gate.complete(Unit)
        running.join()
    }
    @Test fun `查询失败和结果未知仍占用在途额度`() = runTest {
        val backend = Backend().apply { onPoll = { error("离线") } }
        val queue = queue(backend)
        val first = queue.submit(request)
        val second = queue.submit(request)
        queue.runUntilIdle()
        assertEquals(MediaTaskFailure.POLL, store.get(first.id)!!.task.failure)
        assertEquals(MediaTaskState.QUEUED, store.get(second.id)!!.task.state)
        assertEquals(1, backend.submitted)
        assertFalse(queue.hasRunnableTasks())
        backend.onPoll = {}
        queue.retry(first.id)
        queue.runUntilIdle()
        assertEquals(2, backend.submitted)
        assertEquals(MediaTaskState.COMPLETED, store.get(second.id)!!.task.state)
    }
    @Test fun `下载阶段缺后端恢复后只下载不查询不重提`() = runTest {
        val backend = Backend().apply { downloadFails = true }
        val firstQueue = queue(backend)
        val task = firstQueue.submit(request)
        firstQueue.runUntilIdle()
        val missing = PersistentMediaGenerationQueue(store, registry, emptyList(), ioDispatcher = StandardTestDispatcher(testScheduler))
        missing.retry(task.id)
        missing.runUntilIdle()
        assertEquals(MediaTaskState.DOWNLOAD_FAILED, store.get(task.id)!!.task.state)
        backend.downloadFails = false
        val restored = queue(backend)
        restored.retry(task.id)
        restored.runUntilIdle()
        assertEquals(1, backend.polled)
        assertEquals(1, backend.submitted)
        assertEquals(MediaTaskState.COMPLETED, store.get(task.id)!!.task.state)
    }
    @Test fun `未知提交核实远端编号后只续查并释放排队项`() = runTest {
        val backend = Backend().apply { submitFails = true }
        val queue = queue(backend)
        val first = queue.submit(request)
        queue.runUntilIdle()
        val second = queue.submit(request)
        queue.runUntilIdle()
        assertEquals(MediaTaskState.QUEUED, store.get(second.id)!!.task.state)
        assertEquals(1, backend.submitted)
        backend.submitFails = false
        queue.attachRemoteId(first.id, "found-remote")
        queue.runUntilIdle()
        assertEquals(2, backend.submitted)
        assertEquals(MediaTaskState.COMPLETED, store.get(first.id)!!.task.state)
    }
    @Test fun `恢复下载中的任务不查询远端且历史不可重试`() = runTest {
        val backend = Backend().apply { downloadFails = true }
        val first = queue(backend)
        val task = first.submit(request)
        first.runUntilIdle()
        store.save(store.get(task.id)!!.let { it.copy(task = it.task.retry()) })
        backend.downloadFails = false
        queue(backend).runUntilIdle()
        assertEquals(1, backend.polled)
        assertEquals(1, backend.submitted)
        val saved = store.get(task.id)!!
        store.save(saved.copy(historyOnly = true, task = saved.task.copy(state = MediaTaskState.DOWNLOAD_FAILED)))
        try { first.retry(task.id); fail() } catch (_: IllegalStateException) { }
    }
    @Test fun `取消提交后恢复不会自动再提交`() = runTest {
        val backend = Backend().apply { onSubmit = { CompletableDeferred<Unit>().await() } }
        val queue = queue(backend)
        val task = queue.submit(request)
        val running = launch { queue.runUntilIdle() }
        runCurrent()
        running.cancelAndJoin()
        queue(backend).runUntilIdle()
        assertEquals(MediaTaskState.RESULT_UNKNOWN, store.get(task.id)!!.task.state)
        assertEquals(1, backend.submitted)
    }
}

internal class MemoryMediaTaskStore : MediaTaskStore {
    val snapshots = MutableStateFlow<List<PersistentMediaTask>>(emptyList())
    val usage = linkedMapOf<String, MediaUsageEntry>()
    override fun observe() = snapshots.asStateFlow()
    override suspend fun all() = snapshots.value
    override suspend fun get(id: String) = snapshots.value.find { it.task.id == id }
    override suspend fun save(record: PersistentMediaTask, entry: MediaUsageEntry?) {
        entry?.let { usage.putIfAbsent(it.taskId, it) }
        snapshots.value = snapshots.value.filterNot { it.task.id == record.task.id } + record
    }
    override suspend fun delete(id: String) { snapshots.value = snapshots.value.filterNot { it.task.id == id } }
}
