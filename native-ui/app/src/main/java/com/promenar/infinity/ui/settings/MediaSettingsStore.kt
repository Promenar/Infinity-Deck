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
    suspend fun unlock(id: String)
    suspend fun lock(id: String, referenceOrder: List<String>)
}

/** 仅适配既有注册表、模型配置与 SecretStore 路径，不另存模型或工作流。 */
internal class ApplicationMediaSettingsStore(
    private val pm: ProviderManager,
    private val repository: ComfyRepository,
    private val registry: MediaChannelRegistry,
    private val uiPreferences: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
) : MediaSettingsStore {
    private val timeRevision = MutableStateFlow(0L)
    override fun observe(): Flow<MediaSettingsSnapshot> {
        val templateStates = combine(repository.observeTemplates(), timeRevision) { templates, _ ->
            templates to templates.mapNotNull { state ->
                mediaPreflightTime(state, uiPreferences.getString(timeKey(state.template.id), null))?.let { state.template.id to it }
            }.toMap()
        }
        return combine(registry.observeChannels(), pm.providerModels, templateStates, repository.observeHosts()) { channels, models, templates, hosts ->
            MediaSettingsSnapshot(channels, models, templates.first, hosts,
                hosts.filter { repository.hasHostCredential(it.id) }.mapTo(mutableSetOf()) { it.id }, templates.second)
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
    override suspend fun unlock(id: String) { repository.unlockTemplateByUser(id) }
    override suspend fun lock(id: String, referenceOrder: List<String>) {
        val state = repository.templates().first { it.template.id == id }
        check(!state.template.locked)
        val caps = state.template.channel().capabilities
        require(referenceOrder.size == caps.referenceOrder.size && referenceOrder.toSet() == caps.referenceOrder.toSet())
        recordTime(repository.defineTemplateByAgent(state.template.copy(capabilities = caps.copy(referenceOrder = referenceOrder))))
    }
    private suspend fun recordTime(state: ComfyTemplateState) = withContext(Dispatchers.IO) {
        check(uiPreferences.edit().putString(timeKey(state.template.id), mediaPreflightStamp(state, now())).commit())
        timeRevision.value += 1
    }
    private fun timeKey(id: String) = "media_preflight_time:$id"
    companion object {
        fun create(app: InfinityApplication, scope: CoroutineScope, registry: MediaChannelRegistry? = null): MediaSettingsStore {
            val pm = ProviderManager.getInstance()
            val repository = app.comfyMedia.repository
            val channels = registry ?: DefaultMediaChannelRegistry(pm.providerModels, pm.providers,
                ComfyChannelSource { repository.observeTemplateChannels() }, pm.mediaChannelOrders, scope)
            return ApplicationMediaSettingsStore(pm, repository, channels, app.getSharedPreferences("infinity_ui_state", 0))
        }
    }
}
