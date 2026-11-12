package com.promenar.infinity.data.mediagen.queue

import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MediaConfigurationFreezeTest {
    private val channel = MediaChannel("c", MediaGroup.VIDEO, MediaCapabilities(setOf(GenerationMode.TEXT)))
    private val registry = object : MediaChannelRegistry {
        override fun observeChannels() = flowOf(listOf(channel))
        override fun observeOrder(group: MediaGroup) = flowOf(listOf(channel.id))
        override suspend fun channel(id: String) = channel.takeIf { it.id == id }
        override suspend fun setOrder(group: MediaGroup, channelIds: List<String>) = Unit
    }
    private val request = MediaGenerationRequest("c", GenerationMode.TEXT, "镜头", emptyList(), "project", sessionId = "s", outputDir = "takes")
    @Test fun `排队任务的模型选项变化不会发出新请求`() = runTest {
        var configuration = "first"
        var submitted = 0
        val store = MemoryMediaTaskStore()
        val backend = object : MediaGenerationBackend {
            override fun handles(channel: MediaChannel) = true
            override suspend fun estimate(channel: MediaChannel, request: MediaGenerationRequest) = Money(BigDecimal.ONE, "CNY")
            override suspend fun submit(channel: MediaChannel, request: MediaGenerationRequest): String { submitted++; return "remote" }
            override suspend fun poll(channel: MediaChannel, remoteId: String) = RemoteStatus.Succeeded(listOf(RemoteOutput("video", InputKind.VIDEO)))
            override suspend fun download(channel: MediaChannel, request: MediaGenerationRequest, remoteId: String, outputs: List<RemoteOutput>) = listOf("takes/output.mp4")
            override fun actualCost(channel: MediaChannel, usage: Map<String, Long>) = Money(BigDecimal.ONE, "CNY")
        }
        val binding = MediaBackendBinding("b", "p", { "model" }, backend, configurationFor = { configuration })
        val queue = PersistentMediaGenerationQueue(store, registry, listOf(binding), ioDispatcher = StandardTestDispatcher(testScheduler))
        val task = queue.submit(request)
        assertEquals("first", store.get(task.id)?.configurationFingerprint)
        configuration = "changed"
        queue.runUntilIdle()
        assertEquals(0, submitted)
        assertEquals(MediaTaskFailure.CHANNEL_UNAVAILABLE, queue.observeTask(task.id).first()?.failure)
    }
    @Test fun `动态资源组解析失败只使该任务失败`() = runTest {
        var configured = true
        var submitted = 0
        val store = MemoryMediaTaskStore()
        val backend = object : MediaGenerationBackend {
            override fun handles(channel: MediaChannel) = true
            override suspend fun estimate(channel: MediaChannel, request: MediaGenerationRequest) = Money(BigDecimal.ZERO, "CNY")
            override suspend fun submit(channel: MediaChannel, request: MediaGenerationRequest): String { submitted++; return "remote" }
            override suspend fun poll(channel: MediaChannel, remoteId: String) = RemoteStatus.Failed("failed")
            override suspend fun download(channel: MediaChannel, request: MediaGenerationRequest, remoteId: String, outputs: List<RemoteOutput>) = emptyList<String>()
            override fun actualCost(channel: MediaChannel, usage: Map<String, Long>) = null
        }
        val binding = MediaBackendBinding("b", "p", { "model" }, backend, providerFor = { check(configured); "p" })
        val queue = PersistentMediaGenerationQueue(store, registry, listOf(binding), ioDispatcher = StandardTestDispatcher(testScheduler))
        val task = queue.submit(request)
        configured = false
        queue.runUntilIdle()
        assertEquals(0, submitted)
        assertEquals(MediaTaskState.FAILED, queue.observeTask(task.id).first()?.state)
    }
    @Test fun `运行期间计费配置变化不会使用新价格入账`() = runTest {
        var configuration = "first"
        val store = MemoryMediaTaskStore()
        val backend = object : MediaGenerationBackend {
            override fun handles(channel: MediaChannel) = true
            override suspend fun estimate(channel: MediaChannel, request: MediaGenerationRequest) = Money(BigDecimal.ONE, "CNY")
            override suspend fun submit(channel: MediaChannel, request: MediaGenerationRequest) = "remote"
            override suspend fun poll(channel: MediaChannel, remoteId: String): RemoteStatus { configuration = "different-price"; return RemoteStatus.Succeeded(listOf(RemoteOutput("video", InputKind.VIDEO))) }
            override suspend fun download(channel: MediaChannel, request: MediaGenerationRequest, remoteId: String, outputs: List<RemoteOutput>) = listOf("takes/output.mp4")
            override fun actualCost(channel: MediaChannel, usage: Map<String, Long>) = Money(BigDecimal.TEN, "CNY")
        }
        val queue = PersistentMediaGenerationQueue(store, registry, listOf(MediaBackendBinding("b", "p", { "model" }, backend, configurationFor = { configuration })),
            ioDispatcher = StandardTestDispatcher(testScheduler))
        val task = queue.submit(request)
        queue.runUntilIdle()
        assertEquals(MediaTaskState.COMPLETED, queue.observeTask(task.id).first()?.state)
        assertNull(queue.observeTask(task.id).first()?.actualCost)
        assertNull(store.usage[task.id]?.cost)
    }
    @Test fun `入队冻结配置期间变化使提交保持空记录`() = runTest {
        var reads = 0
        var submitted = 0
        val store = MemoryMediaTaskStore()
        val backend = object : MediaGenerationBackend {
            override fun handles(channel: MediaChannel) = true
            override suspend fun estimate(channel: MediaChannel, request: MediaGenerationRequest): Money? = error("不应在队列入队时估价")
            override suspend fun submit(channel: MediaChannel, request: MediaGenerationRequest): String { submitted++; return "remote" }
            override suspend fun poll(channel: MediaChannel, remoteId: String) = RemoteStatus.Failed("失败")
            override suspend fun download(channel: MediaChannel, request: MediaGenerationRequest, remoteId: String, outputs: List<RemoteOutput>) = emptyList<String>()
            override fun actualCost(channel: MediaChannel, usage: Map<String, Long>) = null
        }
        val binding = MediaBackendBinding("b", "p", { "model" }, backend, configurationFor = { if (++reads == 1) "first" else "changed" })
        val queue = PersistentMediaGenerationQueue(store, registry, listOf(binding), ioDispatcher = StandardTestDispatcher(testScheduler))
        try { queue.submit(request); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(store.all().isEmpty())
        assertEquals(0, submitted)
    }
}
