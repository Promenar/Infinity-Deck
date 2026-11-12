package com.promenar.infinity.data.mediagen

import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.mediagen.cloud.*
import com.promenar.infinity.data.mediagen.registry.DefaultMediaChannelRegistry
import com.promenar.infinity.data.mediagen.queue.MediaQueueConcurrency
import com.promenar.infinity.data.production.*
import com.promenar.infinity.domain.media.MediaResolution
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

/** 启动恢复完成后构造；界面与工具共享这些实例。 */
class ProductionMediaRuntime(private val app: InfinityApplication) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val providers = ProviderManager.getInstance()
    private val files = DefaultProductionWorkspaceFiles(app.workspaceRepository, app.fileOperationRepository, app.externalFolderManager, app.workspaceMediaResolver)
    private val models = ProviderCloudMediaModelSource(providers)
    private val http = KtorCloudMediaHttp(providers)
    private val inputs = WorkspaceCloudMediaInputSource(app.workspaceMediaResolver)
    private val outputs = WorkspaceCloudMediaOutputStore(app.workspaceMediaDownloader, app.workspaceMediaStore, app.workspaceMediaResolver)
    val registry = DefaultMediaChannelRegistry(providers.providerModels, providers.providers, app.comfyMedia.channels.asRegistrySource(), providers.mediaChannelOrders, scope)
    val bindings = productionMediaBindings(models, SeedanceMediaGenerationBackend(models, http, inputs, outputs),
        MiniMaxMediaGenerationBackend(models, http, inputs, outputs), app.comfyMedia.backend,
        expectedOutputsFor = { channel -> if (channel.id.startsWith("comfy:")) comfyExpectedOutputCount(app.comfyMedia.repository.templateByChannel(channel.id).template) else 1 },
        configurationFor = { channel ->
            if (channel.id.startsWith("comfy:")) {
                val template = app.comfyMedia.repository.templateByChannel(channel.id).template
                comfyConfigurationFingerprint(template, app.comfyMedia.repository.host(template.hostId).endpoint)
            } else cloudConfigurationFingerprint(requireNotNull(models.find(channel.id)))
        }) { channel ->
        val template = app.comfyMedia.repository.templateByChannel(channel.id).template
        app.comfyMedia.repository.host(template.hostId).endpoint
    }
    val store: ShotGenerationStore = RepositoryShotGenerationStore(app.productionRepository,
        byId = { id -> app.database.productionDao().get(id)?.let { Json { ignoreUnknownKeys = true }.decodeFromString(Production.serializer(), it.snapshot) } },
        activeSession = { app.database.productionDao().activeSession(it) })
    val queue: MediaGenerationQueue get() = requireNotNull(app.mediaGenerationModule).queue
    val planner: ShotGenerationPlanner by lazy {
        DefaultShotGenerationPlanner(store, registry, queue, files, CachedCharacterCompositor(files),
            backendFor = { channel -> bindings.filter { it.backend.handles(channel) }.singleOrNull()?.backend },
            specsFor = { channel, shot -> if (channel.id.startsWith("comfy:")) comfyPlanningSpecs(app.comfyMedia.repository.templateByChannel(channel.id).template)
                else cloudPlanningSpecs(requireNotNull(models.find(channel.id)), shot) },
            validateBackend = { channel, request ->
                if (channel.id.startsWith("comfy:")) {
                    val state = app.comfyMedia.repository.templateByChannel(channel.id)
                    if (!state.template.locked || state.preflight.status != PreflightStatus.PASS) listOf("模板需要锁定并通过预检") else validateComfyShotPlan(state.template, request)
                } else models.find(channel.id)?.let { validateCloudShotPlan(it, channel, request) } ?: emptyList()
            },
            expectedOutputs = { channel -> if (channel.id.startsWith("comfy:")) comfyExpectedOutputCount(app.comfyMedia.repository.templateByChannel(channel.id).template) else 1 },
            kindOf = { session, path ->
                ProductionManifestCodec.validatePath(path)
                val resolved = app.workspaceMediaResolver.resolve(session, app.workspaceMediaResolver.productionPath(session, path)) as? MediaResolution.Available
                when (resolved?.ref?.kind) { MediaKind.IMAGE -> InputKind.IMAGE; MediaKind.VIDEO -> InputKind.VIDEO; MediaKind.AUDIO -> InputKind.AUDIO; else -> null }
            },
            labelOf = { channel ->
                if (channel.id.startsWith("comfy:")) app.comfyMedia.repository.templateByChannel(channel.id).template.name
                else providers.providerModels.value.firstOrNull { "cloud:${it.id}" == channel.id }?.let { "${it.name} · ${it.providerName}" } ?: channel.id
            },
            messages = AndroidShotPlanningMessages(app))
    }
    private var registrarJob: Job? = null
    private val completionRegistrar by lazy { ShotTaskCompletionRegistrar(queue, store) }
    val pendingRegistrations get() = completionRegistrar.pending
    fun install() {
        if (app.mediaGenerationModule == null) app.installMediaGeneration(registry, bindings,
            MediaQueueConcurrency(maxTotal = 3, defaultProvider = 1, defaultChannel = 1),
            requestAvailable = { request ->
                productionRequestAvailable(request, store)
            })
        if (registrarJob?.isActive != true) registrarJob = completionRegistrar.start(scope)
        app.mediaGenerationModule?.resume()
    }
}
