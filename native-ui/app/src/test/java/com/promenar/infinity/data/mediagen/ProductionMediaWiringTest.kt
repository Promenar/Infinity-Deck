package com.promenar.infinity.data.mediagen

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.promenar.infinity.data.mediagen.comfy.ComfyTemplateChannelSource
import com.promenar.infinity.data.mediagen.cloud.*
import com.promenar.infinity.data.mediagen.queue.*
import com.promenar.infinity.data.mediagen.registry.*
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.production.*
import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProductionMediaWiringTest {
    @Test fun `云端模板队列共用注册表并完成候选登记且预算串行占用`() = runTest {
        val cloud = planningChannel("cloud:seed")
        val comfy = planningChannel("comfy:h3")
        val preferences = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("planning-wiring", 0).also { it.edit().clear().commit() }
        val model = ModelInfo("Seedance", "seed", "方舟", true, providerId = "ark", remoteModelId = "seed-model",
            mediaSettings = MediaModelSettings(MediaGroup.VIDEO, cloud.capabilities, CloudMediaApi.SEEDANCE))
        val templates = object : ComfyTemplateChannelSource { override fun observeTemplateChannels() = flowOf(listOf(comfy)) }
        val registry = DefaultMediaChannelRegistry(flowOf(listOf(model)), flowOf(listOf(ProviderListItem(id = "ark"))), templates.asRegistrySource(),
            MediaChannelOrderStore(preferences), backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertEquals(listOf(cloud, comfy), registry.observeChannels().first())
        val projects = PlanningStore()
        val files = PlanningFiles()
        val persistence = MemoryMediaTaskStore()
        class Backend(private val prefix: String) : MediaGenerationBackend {
            val requests = mutableListOf<MediaGenerationRequest>()
            override fun handles(channel: MediaChannel) = channel.id.startsWith(prefix)
            override suspend fun estimate(channel: MediaChannel, request: MediaGenerationRequest) = Money(BigDecimal("6"), "CNY")
            override suspend fun submit(channel: MediaChannel, request: MediaGenerationRequest): String { if (prefix == "comfy:") require(request.specs.durationSeconds == null || request.specs.frames != null); requests += request; return "remote-${requests.size}" }
            override suspend fun poll(channel: MediaChannel, remoteId: String) = RemoteStatus.Succeeded(listOf(RemoteOutput("video", InputKind.VIDEO)))
            override suspend fun download(channel: MediaChannel, request: MediaGenerationRequest, remoteId: String, outputs: List<RemoteOutput>) = listOf("${request.outputDir}/${channel.id.replace(':', '-')}.mp4")
            override fun actualCost(channel: MediaChannel, usage: Map<String, Long>) = Money(BigDecimal("5"), "CNY")
        }
        val seed = Backend("cloud:"); val mini = Backend("unused:"); val local = Backend("comfy:")
        val models = CloudMediaModelSource { id -> if (id == cloud.id) CloudMediaModel("ark", "seed-model", "https://example.invalid", MediaModelSettings(MediaGroup.VIDEO, cloud.capabilities, CloudMediaApi.SEEDANCE)) else null }
        val bindings = productionMediaBindings(models, seed, mini, local) { "http://comfy.invalid:8188/" }
        lateinit var queue: PersistentMediaGenerationQueue
        val policy = WithinBudgetAutoSubmitPolicy(MediaBudgetReader { ProductionMediaBudgetReader(projects, queue).readBudget(it) })
        queue = PersistentMediaGenerationQueue(persistence, registry, bindings, policy, ioDispatcher = StandardTestDispatcher(testScheduler))
        val planner = DefaultShotGenerationPlanner(projects, registry, queue, files,
            CachedCharacterCompositor(files, HorizontalImageCodec { it.first() }), backendFor = { channel -> bindings.find { it.backend.handles(channel) }?.backend }, ioDispatcher = StandardTestDispatcher(testScheduler))
        val first = async { planner.generate("s", "S1E01", "SC01-01") }
        val second = async { planner.generate("s", "S1E01", "SC01-01") }
        val ids = listOf(first.await(), second.await()).map { (it as ShotGenerateResult.Submitted).taskId }
        assertEquals(listOf(MediaTaskState.QUEUED, MediaTaskState.AWAITING_APPROVAL), persistence.all().map { it.task.state })
        queue.runUntilIdle()
        assertTrue(ShotTaskCompletionRegistrar(queue, projects).reconcile().isEmpty())
        assertEquals(1, projects.production.seasons.single().episodes.single().scenes.single().shots.single().takes.size)
        assertEquals("ark", persistence.all().first { it.task.id == ids.first() }.providerId)
        projects.production = projects.production.mapShot("S1E01", "SC01-01") { it.copy(channelId = comfy.id) }
        val third = planner.generate("s", "S1E01", "SC01-01") as ShotGenerateResult.Submitted
        queue.approve(third.taskId)
        queue.runUntilIdle()
        ShotTaskCompletionRegistrar(queue, projects).reconcile()
        assertEquals(1, local.requests.size)
        assertEquals(2, projects.production.seasons.single().episodes.single().scenes.single().shots.single().takes.size)
    }
    @Test fun `同一主机的模板共用提供商资源组`() = runTest {
        assertEquals(comfyProviderId("http://EXAMPLE.invalid:80/"), comfyProviderId("http://example.invalid"))
        assertNotEquals(comfyProviderId("http://example.invalid:8188"), comfyProviderId("http://example.invalid:8189"))
    }
    @Test fun `多产物任务按冻结输出数量预留候选容量`() = runTest {
        val projects = PlanningStore()
        projects.production = projects.production.mapShot("S1E01", "SC01-01") { shot -> shot.copy(takes = List(27) { Take("take-$it", "takes/$it.mp4", TakeSource("service"), 1) }) }
        val files = PlanningFiles()
        val channel = planningChannel("comfy:batch")
        val registry = PlanningRegistry(channel)
        val persistence = MemoryMediaTaskStore()
        val backend = object : MediaGenerationBackend {
            override fun handles(channel: MediaChannel) = true
            override suspend fun estimate(channel: MediaChannel, request: MediaGenerationRequest) = Money(BigDecimal.ZERO, "CNY")
            override suspend fun submit(channel: MediaChannel, request: MediaGenerationRequest) = "remote"
            override suspend fun poll(channel: MediaChannel, remoteId: String) = RemoteStatus.Failed("unused")
            override suspend fun download(channel: MediaChannel, request: MediaGenerationRequest, remoteId: String, outputs: List<RemoteOutput>) = emptyList<String>()
            override fun actualCost(channel: MediaChannel, usage: Map<String, Long>) = null
        }
        val queue = PersistentMediaGenerationQueue(persistence, registry, listOf(MediaBackendBinding("b", "p", { "model" }, backend, expectedOutputsFor = { 2 })),
            AutoSubmitPolicy { _, _ -> true }, ioDispatcher = StandardTestDispatcher(testScheduler))
        val planner = DefaultShotGenerationPlanner(projects, registry, queue, files, CachedCharacterCompositor(files), expectedOutputs = { 2 }, ioDispatcher = StandardTestDispatcher(testScheduler))
        val first = planner.generate("s", "S1E01", "SC01-01") as ShotGenerateResult.Submitted
        assertEquals(mapOf(first.taskId to 2), queue.outputReservations(setOf(first.taskId)))
        assertTrue(planner.generate("s", "S1E01", "SC01-01") is ShotGenerateResult.Rejected)
        assertEquals(1, persistence.all().size)
    }

}
