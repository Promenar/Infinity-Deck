package com.promenar.infinity.ui.settings

import android.content.SharedPreferences
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.mediagen.comfy.*
import com.promenar.infinity.data.mediagen.registry.ComfyChannelSource
import com.promenar.infinity.data.mediagen.registry.DefaultMediaChannelRegistry
import com.promenar.infinity.data.model.*
import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

internal data class MediaSettingsSnapshot(
    val channels: List<MediaChannel> = emptyList(),
    val models: List<ModelInfo> = emptyList(),
    val templates: List<ComfyTemplateState> = emptyList(),
    val hosts: List<ComfyHost> = emptyList(),
    val credentialHosts: Set<String> = emptySet(),
    val preflightTimes: Map<String, Long> = emptyMap(),
    val submitters: List<ComfySubmitterScript> = emptyList(),
    val workflows: List<ComfyWorkflowState> = emptyList(),
    val pythonAvailable: Boolean = false,
)

internal interface MediaSettingsStore {
    fun observe(): Flow<MediaSettingsSnapshot>
    suspend fun reorder(group: MediaGroup, ids: List<String>)
    suspend fun changeReferences(modelId: String, mode: CharacterReferenceMode, order: List<String>)
    suspend fun saveTariff(modelId: String, draft: MediaTariffDraft)
    suspend fun saveHost(host: ComfyHost, credential: ComfyCredentialInput)
    suspend fun removeHost(id: String)
    suspend fun probe(id: String)
    suspend fun preflight(id: String)
    suspend fun lock(id: String, referenceOrder: List<String>)

    suspend fun saveSubmitter(
        hostId: String,
        scriptId: String,
        fileName: String,
        displayName: String,
        description: String,
        sourceKind: SubmitterSourceKind,
        protocol: SubmitterInvocationProtocol,
        sourceText: String,
        supportFiles: Map<String, ByteArray>,
        runDryRun: Boolean = true,
    ) {}

    suspend fun dryRunSubmitter(hostId: String, scriptId: String) {}
    suspend fun deleteSubmitter(hostId: String, scriptId: String) {}
    suspend fun readSubmitterSource(hostId: String, scriptId: String): String? = null
    suspend fun saveWorkflow(workflow: ComfyWorkflow) {}
    suspend fun preflightWorkflow(workflowId: String) {}
    suspend fun deleteWorkflow(workflowId: String) {}
}

/** 仅适配既有注册表、模型配置、ComfyUI 兼容层与 SecretStore 路径，不另存模型或工作流。 */
internal class ApplicationMediaSettingsStore(
    private val pm: ProviderManager,
    private val repository: ComfyRepository,
    private val registry: MediaChannelRegistry,
    private val uiPreferences: SharedPreferences,
    private val submitterRunner: ComfySubmitterRunner? = null,
    private val submitterFiles: ComfySubmitterFileStore? = null,
    private val now: () -> Long = System::currentTimeMillis,
) : MediaSettingsStore {
    private val timeRevision = MutableStateFlow(0L)

    override fun observe(): Flow<MediaSettingsSnapshot> {
        val templateStates = combine(repository.observeTemplates(), timeRevision) { templates, _ ->
            templates to templates.mapNotNull { state ->
                mediaPreflightTime(state, uiPreferences.getString(timeKey(state.template.id), null))?.let { state.template.id to it }
            }.toMap()
        }
        val layerStates = combine(repository.observeSubmitters(), repository.observeEffectiveWorkflows()) { subs, wfs ->
            subs to wfs
        }
        return combine(registry.observeChannels(), pm.providerModels, templateStates, repository.observeHosts(), layerStates) {
                channels, models, templates, hosts, layer ->
            MediaSettingsSnapshot(
                channels = channels,
                models = models,
                templates = templates.first,
                hosts = hosts,
                credentialHosts = hosts.filter { repository.hasHostCredential(it.id) }.mapTo(mutableSetOf()) { it.id },
                preflightTimes = templates.second,
                submitters = layer.first,
                workflows = layer.second,
                pythonAvailable = submitterRunner?.isPythonAvailable == true,
            )
        }.flowOn(Dispatchers.IO)
    }

    override suspend fun reorder(group: MediaGroup, ids: List<String>) = registry.setOrder(group, ids)

    private fun settings(id: String): MediaModelSettings = pm.providerModels.value.firstOrNull { it.id == id }?.effectiveMediaSettings()
        ?: error("媒体模型不存在")

    override suspend fun changeReferences(modelId: String, mode: CharacterReferenceMode, order: List<String>) = withContext(Dispatchers.IO) {
        val current = settings(modelId)
        require(order.toSet() == current.capabilities.referenceOrder.toSet() && order.size == current.capabilities.referenceOrder.size)
        pm.setMediaModelSettings(modelId, current.withCharacterReferences(mode, order))
    }

    override suspend fun saveTariff(modelId: String, draft: MediaTariffDraft) = withContext(Dispatchers.IO) {
        pm.setMediaModelSettings(modelId, settings(modelId).copy(tariff = draft.toTariff()))
    }

    override suspend fun saveHost(host: ComfyHost, credential: ComfyCredentialInput) = withContext(Dispatchers.IO) {
        repository.saveHost(host, credential.credential(host.authKind))
    }

    override suspend fun removeHost(id: String) = repository.removeHost(id)
    override suspend fun probe(id: String) { repository.checkConnection(id) }
    override suspend fun preflight(id: String) { recordTime(repository.preflight(id)) }

    override suspend fun lock(id: String, referenceOrder: List<String>) {
        val state = repository.templates().first { it.template.id == id }
        check(!state.template.locked)
        val caps = state.template.channel().capabilities
        require(referenceOrder.size == caps.referenceOrder.size && referenceOrder.toSet() == caps.referenceOrder.toSet())
        recordTime(repository.defineTemplateByAgent(state.template.copy(capabilities = caps.copy(referenceOrder = referenceOrder))))
    }

    override suspend fun saveSubmitter(
        hostId: String,
        scriptId: String,
        fileName: String,
        displayName: String,
        description: String,
        sourceKind: SubmitterSourceKind,
        protocol: SubmitterInvocationProtocol,
        sourceText: String,
        supportFiles: Map<String, ByteArray>,
        runDryRun: Boolean,
    ) {
        withContext(Dispatchers.IO) {
            val saved = repository.saveSubmitter(
                hostId = hostId,
                scriptId = scriptId,
                fileName = fileName,
                displayName = displayName,
                description = description,
                sourceKind = sourceKind,
                protocol = protocol,
                sourceText = sourceText,
                supportFiles = supportFiles,
            )
            if (runDryRun && submitterRunner?.isPythonAvailable == true) {
                val res = submitterRunner.dryRunScript(saved, null)
                repository.updateSubmitterDryRun(
                    hostId = hostId,
                    scriptId = scriptId,
                    status = if (res.success) SubmitterDryRunStatus.PASSED else SubmitterDryRunStatus.FAILED,
                    message = res.summary.ifBlank { res.stderrTail },
                    latencyMs = res.latencyMs,
                )
            }
        }
    }

    override suspend fun dryRunSubmitter(hostId: String, scriptId: String) {
        withContext(Dispatchers.IO) {
            val sub = repository.submitter(hostId, scriptId) ?: throw ComfyFailure("提交器脚本不存在：$scriptId")
            val runner = submitterRunner ?: throw ComfyFailure("当前环境不可用内置 Python 3.14 运行时")
            val res = runner.dryRunScript(sub, null)
            repository.updateSubmitterDryRun(
                hostId = hostId,
                scriptId = scriptId,
                status = if (res.success) SubmitterDryRunStatus.PASSED else SubmitterDryRunStatus.FAILED,
                message = res.summary.ifBlank { res.stderrTail },
                latencyMs = res.latencyMs,
            )
        }
    }

    override suspend fun deleteSubmitter(hostId: String, scriptId: String) = withContext(Dispatchers.IO) {
        repository.deleteSubmitter(hostId, scriptId)
    }

    override suspend fun readSubmitterSource(hostId: String, scriptId: String): String? = withContext(Dispatchers.IO) {
        val sub = repository.submitter(hostId, scriptId) ?: return@withContext null
        val store = submitterFiles ?: return@withContext null
        runCatching { store.readEntryText(hostId, scriptId, sub.fileName) }.getOrNull()
    }

    override suspend fun saveWorkflow(workflow: ComfyWorkflow) {
        withContext(Dispatchers.IO) {
            repository.saveWorkflow(workflow, runPreflight = true)
        }
    }

    override suspend fun preflightWorkflow(workflowId: String) {
        withContext(Dispatchers.IO) {
            repository.preflightWorkflow(workflowId)
        }
    }

    override suspend fun deleteWorkflow(workflowId: String) = withContext(Dispatchers.IO) {
        repository.deleteWorkflow(workflowId)
    }

    private suspend fun recordTime(state: ComfyTemplateState) = withContext(Dispatchers.IO) {
        check(uiPreferences.edit().putString(timeKey(state.template.id), mediaPreflightStamp(state, now())).commit())
        timeRevision.value += 1
    }

    private fun timeKey(id: String) = "media_preflight_time:$id"

    companion object {
        fun create(app: InfinityApplication, scope: CoroutineScope, registry: MediaChannelRegistry? = null): MediaSettingsStore {
            val pm = ProviderManager.getInstance()
            val comfy = app.comfyMedia
            val repository = comfy.repository
            val channels = registry ?: DefaultMediaChannelRegistry(
                pm.providerModels,
                pm.providers,
                ComfyChannelSource { repository.observeTemplateChannels() },
                pm.mediaChannelOrders,
                scope,
            )
            return ApplicationMediaSettingsStore(
                pm = pm,
                repository = repository,
                registry = channels,
                uiPreferences = app.getSharedPreferences("infinity_ui_state", 0),
                submitterRunner = comfy.submitterRunner,
                submitterFiles = comfy.submitterFiles,
            )
        }
    }
}
