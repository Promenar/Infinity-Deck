package com.promenar.infinity.data.mediagen.queue

import com.promenar.infinity.domain.mediagen.*
import java.math.BigDecimal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PersistentMediaGenerationQueueTest {
    private val channel = MediaChannel("video", MediaGroup.VIDEO, MediaCapabilities(setOf(GenerationMode.TEXT, GenerationMode.REFERENCE), maxImages = 1))
    private val request = MediaGenerationRequest(channel.id, GenerationMode.TEXT, "风吹过树林", emptyList(), "project/shot1", sessionId = "session", outputDir = "takes")
    private val money = Money(BigDecimal("1.25"), "USD")
    private val store = MemoryMediaTaskStore()
    private val registry = object : MediaChannelRegistry {
        override fun observeChannels() = flowOf(listOf(channel))
        override fun observeOrder(group: MediaGroup) = flowOf(listOf(channel.id))
        override suspend fun setOrder(group: MediaGroup, channelIds: List<String>) = Unit
        override suspend fun channel(id: String) = channel.takeIf { it.id == id }
    }
    private inner class Backend : MediaGenerationBackend {
        var actual: Money? = money
        var estimated = 0
        var submitted = 0
        var polled = 0
        var downloaded = 0
        val canceled = mutableListOf<String>()
        var downloadFails = false
        var submitFails = false
        var cancelFails = false
        var statuses = ArrayDeque<RemoteStatus>()
        var onSubmit: suspend () -> Unit = {}
        var onPoll: suspend () -> Unit = {}
        var onDownload: suspend () -> Unit = {}
        var onCancel: suspend () -> Unit = {}
        override fun handles(channel: MediaChannel) = true
        override suspend fun estimate(channel: MediaChannel, request: MediaGenerationRequest): Money { estimated++; return money }
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
            onDownload()
            if (downloadFails) error("磁盘不可写")
            return listOf("${request.outputDir}/$remoteId.mp4")
        }
        override suspend fun cancel(channel: MediaChannel, remoteId: String): Boolean {
            assertEquals(MediaTaskState.CANCELED, store.all().first { it.task.remoteId == remoteId }.task.state)
            canceled += remoteId
            onCancel()
            if (cancelFails) error("远端不可达")
            return true
        }
        override fun actualCost(channel: MediaChannel, usage: Map<String, Long>) = actual
    }
    private fun TestScope.queue(backend: Backend, concurrency: MediaQueueConcurrency = MediaQueueConcurrency(), wake: () -> Unit = {}, requestAvailable: suspend (MediaGenerationRequest) -> Boolean = { true }) =
        PersistentMediaGenerationQueue(store, registry, listOf(MediaBackendBinding("backend", "provider", { "model" }, backend)),
            concurrency = concurrency, clock = { testScheduler.currentTime }, ioDispatcher = StandardTestDispatcher(testScheduler), wake = wake,
            requestAvailable = requestAvailable, polling = MediaPollingPolicy(initialMillis = 100, maximumMillis = 400, maxConsecutiveErrors = 2))

    @Test fun `超限请求返回清单且不估价入队`() = runTest {
        val backend = Backend()
        val inputs = listOf("one.png", "two.png", "three.png").map { MediaInput(it, InputKind.IMAGE, InputRole.REFERENCE) }
        try { queue(backend).submit(request.copy(mode = GenerationMode.REFERENCE, inputs = inputs)); fail() }
        catch (error: InvalidMediaRequestException) { assertEquals(inputs.drop(1), error.validation.excessInputs) }
        assertTrue(store.all().isEmpty())
        assertEquals(0, backend.estimated)
        assertEquals(0, backend.submitted)
    }
    @Test fun `提交直接排队不估价并记录创建时间`() = runTest {
        val backend = Backend()
        advanceTimeBy(42)
        val queue = queue(backend)
        val task = queue.submit(request)
        assertEquals(MediaTaskState.QUEUED, task.state)
        assertNull(task.estimate)
        assertEquals(42L, task.createdAt)
        assertEquals(task.createdAt, store.get(task.id)!!.updatedAt)
        assertEquals(0, backend.estimated)
        assertEquals(0, backend.submitted)
    }
    @Test fun `取消排队任务保留快照且不提交并可重复取消`() = runTest {
        val backend = Backend()
        val queue = queue(backend)
        val task = queue.submit(request)
        queue.cancel(task.id)
        queue.cancel(task.id)
        queue.runUntilIdle()
        queue(backend).runUntilIdle()
        assertEquals(MediaTaskState.CANCELED, store.get(task.id)!!.task.state)
        assertEquals(0, backend.submitted)
        assertTrue(backend.canceled.isEmpty())
    }
    @Test fun `未知实际费用不入零`() = runTest {
        val backend = Backend().apply { actual = null }
        val queue = queue(backend)
        val task = queue.submit(request)
        queue.runUntilIdle()
        assertNull(store.usage.values.single().cost)
        assertNull(store.get(task.id)!!.task.actualCost)
    }
    @Test fun `成功先持久化再唤醒且费用只入账一次`() = runTest {
        val backend = Backend()
        val queue = queue(backend, wake = { assertTrue(store.snapshots.value.isNotEmpty()) })
        val task = queue.submit(request)
        queue.runUntilIdle()
        queue.runUntilIdle()
        val completed = queue.observeTask(task.id).first()!!
        assertEquals(listOf("takes/remote-1.mp4"), completed.outputPaths)
        assertEquals(money, store.usage.values.single().cost)
        assertEquals("provider", store.usage.values.single().providerId)
        assertEquals("model", store.usage.values.single().modelId)
        assertEquals(1, backend.submitted)
    }
    @Test fun `下载失败保留远端成功与费用且重启不自动重新执行`() = runTest {
        val backend = Backend().apply { downloadFails = true }
        val queue = queue(backend)
        val task = queue.submit(request)
        queue.runUntilIdle()
        assertEquals(MediaTaskState.DOWNLOAD_FAILED, store.get(task.id)!!.task.state)
        backend.downloadFails = false
        queue(backend).runUntilIdle()
        assertEquals(1, backend.submitted)
        assertEquals(1, backend.polled)
        assertEquals(1, backend.downloaded)
        assertEquals(money, store.usage.values.single().cost)
    }
    @Test fun `轮询逐步退避并持久化下一次查询时间`() = runTest {
        val backend = Backend().apply { repeat(4) { statuses.add(RemoteStatus.Running(.5f)) } }
        val queue = queue(backend)
        val task = queue.submit(request)
        val times = mutableListOf<Long>()
        backend.onPoll = { times += testScheduler.currentTime }
        queue.runUntilIdle()
        assertEquals(listOf(0L, 100L, 300L, 700L, 1100L), times)
        assertEquals(1f, store.get(task.id)!!.task.progress)
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
    @Test fun `有远端编号的恢复只续查`() = runTest {
        val backend = Backend()
        val task = queue(backend).submit(request)
        val saved = store.get(task.id)!!
        store.save(saved.copy(task = task.started("remote-recovered")))
        queue(backend).runUntilIdle()
        assertEquals(0, backend.submitted)
        assertEquals(1, backend.polled)
        assertEquals(MediaTaskState.COMPLETED, store.get(task.id)!!.task.state)
    }
    @Test fun `提交中恢复和提交异常标为结果未知`() = runTest {
        val backend = Backend()
        val task = queue(backend).submit(request)
        store.save(store.get(task.id)!!.copy(task = task.submitting()))
        val recovered = queue(backend)
        recovered.runUntilIdle()
        assertEquals(MediaTaskState.RESULT_UNKNOWN, store.get(task.id)!!.task.state)
        assertEquals(0, backend.submitted)
        assertTrue(task.id in recovered.unresolvedTaskIds())
        recovered.archiveUnknown(task.id)
        assertFalse(task.id in recovered.unresolvedTaskIds())
        val failedBackend = Backend().apply { submitFails = true }
        val next = queue(failedBackend)
        val second = next.submit(request)
        next.runUntilIdle()
        assertEquals(MediaTaskState.RESULT_UNKNOWN, store.get(second.id)!!.task.state)
    }
    @Test fun `查询失败保留远端编号并持续阻挡同资源组新提交`() = runTest {
        val backend = Backend().apply { onPoll = { error("离线") } }
        val queue = queue(backend)
        val first = queue.submit(request)
        val second = queue.submit(request)
        queue.runUntilIdle()
        assertEquals(MediaTaskFailure.POLL, store.get(first.id)!!.task.failure)
        assertEquals("remote-1", store.get(first.id)!!.task.remoteId)
        assertEquals(MediaTaskState.QUEUED, store.get(second.id)!!.task.state)
        assertFalse(queue.hasRunnableTasks())
        queue(backend).runUntilIdle()
        assertEquals(1, backend.submitted)
    }
    @Test fun `远端失败的新生成使用独立任务编号`() = runTest {
        val backend = Backend().apply { statuses.add(RemoteStatus.Failed("失败")) }
        val queue = queue(backend)
        val first = queue.submit(request)
        queue.runUntilIdle()
        val second = queue.submit(request)
        queue.runUntilIdle()
        assertNotEquals(first.id, second.id)
        assertEquals(MediaTaskFailure.REMOTE, store.get(first.id)!!.task.failure)
        assertEquals("remote-1", store.get(first.id)!!.task.remoteId)
        assertEquals(MediaTaskState.COMPLETED, store.get(second.id)!!.task.state)
        assertEquals(2, backend.submitted)
        assertEquals(0, backend.estimated)
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
    @Test fun `下载中的快照恢复只下载不查询或提交`() = runTest {
        val backend = Backend().apply { downloadFails = true }
        val task = queue(backend).submit(request)
        queue(backend).runUntilIdle()
        val saved = store.get(task.id)!!
        store.save(saved.copy(task = saved.task.copy(state = MediaTaskState.DOWNLOADING, error = null, failure = null)))
        backend.downloadFails = false
        queue(backend).runUntilIdle()
        assertEquals(1, backend.submitted)
        assertEquals(1, backend.polled)
        assertEquals(2, backend.downloaded)
        assertEquals(MediaTaskState.COMPLETED, store.get(task.id)!!.task.state)
        assertEquals(1, store.usage.size)
    }
    @Test fun `下载中的快照缺少后端会保留费用与失败状态`() = runTest {
        val backend = Backend().apply { downloadFails = true }
        val task = queue(backend).submit(request)
        queue(backend).runUntilIdle()
        val saved = store.get(task.id)!!
        store.save(saved.copy(task = saved.task.copy(state = MediaTaskState.DOWNLOADING)))
        PersistentMediaGenerationQueue(store, registry, emptyList(), ioDispatcher = StandardTestDispatcher(testScheduler)).runUntilIdle()
        assertEquals(MediaTaskState.DOWNLOAD_FAILED, store.get(task.id)!!.task.state)
        assertEquals(money, store.get(task.id)!!.task.actualCost)
        assertEquals(1, backend.polled)
    }
    @Test fun `未知提交核实远端编号后只续查并释放排队项`() = runTest {
        val backend = Backend().apply { submitFails = true }
        val queue = queue(backend)
        val first = queue.submit(request)
        queue.runUntilIdle()
        val second = queue.submit(request)
        queue.runUntilIdle()
        assertEquals(MediaTaskState.QUEUED, store.get(second.id)!!.task.state)
        backend.submitFails = false
        queue.attachRemoteId(first.id, "found-remote")
        queue.runUntilIdle()
        assertEquals(2, backend.submitted)
        assertEquals(MediaTaskState.COMPLETED, store.get(first.id)!!.task.state)
    }
    @Test fun `服务中断提交后恢复不会自动再提交`() = runTest {
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
    @Test fun `旧待批准任务在启动检查时取消并保留兼容时序`() = runTest {
        val backend = Backend()
        val task = queue(backend).submit(request)
        store.save(store.get(task.id)!!.copy(task = task.copy(state = MediaTaskState.AWAITING_APPROVAL, createdAt = 0), updatedAt = 23))
        val recovered = queue(backend)
        assertFalse(recovered.hasRunnableTasks())
        val canceled = store.get(task.id)!!.task
        assertEquals(MediaTaskState.CANCELED, canceled.state)
        assertEquals("升级后取消", canceled.error)
        assertEquals(23L, canceled.createdAt)
        assertEquals(0, backend.submitted)
    }
    @Test fun `取消资源检查中的排队任务不会提交远端`() = runTest {
        val backend = Backend()
        val queue = queue(backend, requestAvailable = { CompletableDeferred<Boolean>().await() })
        val task = queue.submit(request)
        val running = launch { queue.runUntilIdle() }
        runCurrent()
        assertEquals(MediaTaskState.QUEUED, store.get(task.id)!!.task.state)
        queue.cancel(task.id)
        running.join()
        assertEquals(MediaTaskState.CANCELED, store.get(task.id)!!.task.state)
        assertEquals(0, backend.submitted)
    }
    @Test fun `取消提交中的任务保留终态而非结果未知`() = runTest {
        val backend = Backend().apply { onSubmit = { CompletableDeferred<Unit>().await() } }
        val queue = queue(backend)
        val task = queue.submit(request)
        val running = launch { queue.runUntilIdle() }
        runCurrent()
        assertEquals(MediaTaskState.SUBMITTING, store.get(task.id)!!.task.state)
        queue.cancel(task.id)
        running.join()
        queue(backend).runUntilIdle()
        assertEquals(MediaTaskState.CANCELED, store.get(task.id)!!.task.state)
        assertNull(store.get(task.id)!!.task.remoteId)
        assertEquals(1, backend.submitted)
        assertEquals(0, backend.polled)
    }
    @Test fun `取消后迟到的提交编号只补写编号并取消远端`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backend = Backend().apply { onSubmit = { withContext(NonCancellable) { gate.await() } } }
        val queue = queue(backend)
        val task = queue.submit(request)
        val running = launch { queue.runUntilIdle() }
        runCurrent()
        queue.cancel(task.id)
        gate.complete(Unit)
        running.join()
        assertEquals(MediaTaskState.CANCELED, store.get(task.id)!!.task.state)
        assertEquals("remote-1", store.get(task.id)!!.task.remoteId)
        assertEquals(listOf("remote-1"), backend.canceled)
        assertEquals(0, backend.polled)
    }
    @Test fun `取消运行中的任务中止轮询并让后续排队任务调度`() = runTest {
        val backend = Backend().apply { onPoll = { if (polled == 1) CompletableDeferred<Unit>().await() } }
        val queue = queue(backend)
        val first = queue.submit(request)
        val second = queue.submit(request)
        val running = launch { queue.runUntilIdle() }
        runCurrent()
        queue.cancel(first.id)
        running.join()
        assertEquals(MediaTaskState.CANCELED, store.get(first.id)!!.task.state)
        assertEquals(listOf("remote-1"), backend.canceled)
        assertEquals(MediaTaskState.COMPLETED, store.get(second.id)!!.task.state)
        assertEquals(2, backend.submitted)
        assertEquals(1, backend.downloaded)
    }
    @Test fun `取消后迟到的进度轮询不能覆盖终态`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backend = Backend().apply {
            statuses.add(RemoteStatus.Running(.8f))
            onPoll = { withContext(NonCancellable) { gate.await() } }
        }
        val queue = queue(backend)
        val task = queue.submit(request)
        val running = launch { queue.runUntilIdle() }
        runCurrent()
        queue.cancel(task.id)
        gate.complete(Unit)
        running.join()
        assertEquals(MediaTaskState.CANCELED, store.get(task.id)!!.task.state)
        assertNull(store.get(task.id)!!.task.progress)
        assertEquals(1, backend.polled)
    }
    @Test fun `取消后迟到的成功轮询保留费用且不下载`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backend = Backend().apply { onPoll = { withContext(NonCancellable) { gate.await() } } }
        val queue = queue(backend)
        val task = queue.submit(request)
        val running = launch { queue.runUntilIdle() }
        runCurrent()
        queue.cancel(task.id)
        gate.complete(Unit)
        running.join()
        assertEquals(MediaTaskState.CANCELED, store.get(task.id)!!.task.state)
        assertEquals(money, store.get(task.id)!!.task.actualCost)
        assertEquals(money, store.usage[task.id]!!.cost)
        assertEquals(0, backend.downloaded)
    }
    @Test fun `取消下载停止本地工作并保留已入账费用`() = runTest {
        val backend = Backend().apply { onDownload = { CompletableDeferred<Unit>().await() } }
        val queue = queue(backend)
        val task = queue.submit(request)
        val running = launch { queue.runUntilIdle() }
        runCurrent()
        assertEquals(MediaTaskState.DOWNLOADING, store.get(task.id)!!.task.state)
        queue.cancel(task.id)
        running.join()
        queue(backend).runUntilIdle()
        assertEquals(MediaTaskState.CANCELED, store.get(task.id)!!.task.state)
        assertTrue(store.get(task.id)!!.task.outputPaths.isEmpty())
        assertEquals(money, store.usage[task.id]!!.cost)
        assertEquals(1, backend.downloaded)
    }
    @Test fun `取消后迟到的下载结果不能覆盖终态`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backend = Backend().apply { onDownload = { withContext(NonCancellable) { gate.await() } } }
        val queue = queue(backend)
        val task = queue.submit(request)
        val running = launch { queue.runUntilIdle() }
        runCurrent()
        queue.cancel(task.id)
        gate.complete(Unit)
        running.join()
        assertEquals(MediaTaskState.CANCELED, store.get(task.id)!!.task.state)
        assertTrue(store.get(task.id)!!.task.outputPaths.isEmpty())
        assertEquals(1, store.usage.size)
    }
    @Test fun `远端取消失败不撤销本地取消`() = runTest {
        val backend = Backend().apply { onPoll = { CompletableDeferred<Unit>().await() }; cancelFails = true }
        val queue = queue(backend)
        val task = queue.submit(request)
        val running = launch { queue.runUntilIdle() }
        runCurrent()
        queue.cancel(task.id)
        running.join()
        assertEquals(MediaTaskState.CANCELED, store.get(task.id)!!.task.state)
        assertEquals(listOf("remote-1"), backend.canceled)
        assertEquals(0, backend.downloaded)
    }
    @Test fun `远端取消超时有界且保持本地终态`() = runTest {
        val backend = Backend().apply {
            onPoll = { CompletableDeferred<Unit>().await() }
            onCancel = { CompletableDeferred<Unit>().await() }
        }
        val queue = queue(backend)
        val task = queue.submit(request)
        val running = launch { queue.runUntilIdle() }
        runCurrent()
        queue.cancel(task.id)
        running.join()
        assertEquals(10_000L, testScheduler.currentTime)
        assertEquals(MediaTaskState.CANCELED, store.get(task.id)!!.task.state)
        assertEquals(0, backend.downloaded)
    }
    @Test fun `取消调用方在持久化提交后中断仍停止本地并取消远端`() = runTest {
        val persisted = CompletableDeferred<Unit>()
        val finishSave = CompletableDeferred<Unit>()
        var pollStopped = false
        val backend = Backend().apply { onPoll = {
            try { CompletableDeferred<Unit>().await() } finally { pollStopped = true }
        } }
        val gatedStore = object : MediaTaskStore by store {
            override suspend fun save(record: PersistentMediaTask, entry: MediaUsageEntry?) {
                store.save(record, entry)
                if (record.task.state == MediaTaskState.CANCELED) {
                    persisted.complete(Unit)
                    finishSave.await()
                }
            }
        }
        val queue = PersistentMediaGenerationQueue(gatedStore, registry,
            listOf(MediaBackendBinding("backend", "provider", { "model" }, backend)), ioDispatcher = StandardTestDispatcher(testScheduler))
        val task = queue.submit(request)
        val running = launch { queue.runUntilIdle() }
        runCurrent()
        val canceling = launch { queue.cancel(task.id) }
        runCurrent()
        assertTrue(persisted.isCompleted)
        assertEquals(MediaTaskState.CANCELED, store.get(task.id)!!.task.state)
        canceling.cancel()
        finishSave.complete(Unit)
        runCurrent()
        try {
            assertTrue(canceling.isCompleted)
            assertTrue(pollStopped)
            assertEquals(listOf("remote-1"), backend.canceled)
            assertEquals(MediaTaskState.CANCELED, store.get(task.id)!!.task.state)
            assertEquals(0, backend.downloaded)
        } finally {
            running.cancelAndJoin()
        }
    }
    @Test fun `取消持久化失败时保留运行任务且不取消远端`() = runTest {
        val finishPoll = CompletableDeferred<Unit>()
        val backend = Backend().apply { onPoll = { finishPoll.await() } }
        val failingStore = object : MediaTaskStore by store {
            override suspend fun save(record: PersistentMediaTask, entry: MediaUsageEntry?) {
                check(record.task.state != MediaTaskState.CANCELED) { "持久化失败" }
                store.save(record, entry)
            }
        }
        val queue = PersistentMediaGenerationQueue(failingStore, registry,
            listOf(MediaBackendBinding("backend", "provider", { "model" }, backend)), ioDispatcher = StandardTestDispatcher(testScheduler))
        val task = queue.submit(request)
        val running = launch { queue.runUntilIdle() }
        runCurrent()
        try { queue.cancel(task.id); fail() } catch (_: IllegalStateException) { }
        assertEquals(MediaTaskState.RUNNING, store.get(task.id)!!.task.state)
        assertTrue(backend.canceled.isEmpty())
        finishPoll.complete(Unit)
        running.join()
        assertEquals(MediaTaskState.COMPLETED, store.get(task.id)!!.task.state)
    }
    @Test fun `完成任务和历史任务的取消保持原快照`() = runTest {
        val backend = Backend()
        val queue = queue(backend)
        val task = queue.submit(request)
        queue.runUntilIdle()
        val completed = store.get(task.id)!!
        queue.cancel(task.id)
        assertEquals(completed, store.get(task.id))
        val history = completed.copy(task = task, historyOnly = true)
        store.save(history)
        queue.cancel(task.id)
        assertEquals(history, store.get(task.id))
        assertTrue(backend.canceled.isEmpty())
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
