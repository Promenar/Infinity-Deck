package com.promenar.infinity.data.repository

import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.model.toCredentialUpdate
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.protocol.VERTEX_DEFAULT_LOCATION
import com.promenar.infinity.data.remote.provider.LlmProvider
import com.promenar.infinity.data.remote.provider.ProviderConnectionProbe
import com.promenar.infinity.data.remote.provider.ProviderConnectionProbeResult
import com.promenar.infinity.data.remote.UnifiedProviderConfig
import com.promenar.infinity.data.remote.protocol.RemoteModelDescriptor
import com.promenar.infinity.domain.model.ConnectionResult
import com.promenar.infinity.domain.model.ModelCapability
import com.promenar.infinity.domain.model.ModelSpec
import com.promenar.infinity.domain.model.ModelType
import com.promenar.infinity.domain.model.ProviderConfig
import com.promenar.infinity.domain.repository.IProviderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException

class ProviderRepository(
    private val providerManager: ProviderManager,
    private val connectionProbe: ProviderConnectionProbe = ProviderConnectionProbe.processScoped,
    private val modelListFetcher: (suspend (UnifiedProviderConfig) -> List<String>)? = null,
    private val modelDescriptorFetcher: (suspend (UnifiedProviderConfig) -> List<RemoteModelDescriptor>)? = null,
) : IProviderRepository {

    override fun observeAll(): Flow<List<ProviderConfig>> {
        return providerManager.providers.map { items ->
            items.map { it.toDomain() }
        }
    }

    override suspend fun testConnection(providerId: String): ConnectionResult {
        val config = providerManager.getProviderConfig(providerId)
            ?: return ConnectionResult(false, null, "Provider not found: $providerId")
        val start = System.currentTimeMillis()
        return try {
            val result = connectionProbe.probe(
                UnifiedProviderConfig(
                    protocolType = config.protocolType,
                    baseUrl = config.baseUrl,
                    apiKey = config.apiKey,
                    defaultModel = config.model,
                    serviceAccountJson = config.vertexServiceAccountJson,
                    projectId = "",
                    location = VERTEX_DEFAULT_LOCATION,
                ),
            )
            val latency = System.currentTimeMillis() - start
            when (result) {
                ProviderConnectionProbeResult.Success -> ConnectionResult(true, latency, null)
                ProviderConnectionProbeResult.Unsupported -> ConnectionResult(
                    success = false,
                    latencyMs = latency,
                    error = null,
                    supported = false,
                )
                is ProviderConnectionProbeResult.Failure -> ConnectionResult(
                    success = false,
                    latencyMs = latency,
                    error = result.reason.name,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val latency = System.currentTimeMillis() - start
            ConnectionResult(false, latency, "CONNECTION_PROBE_FAILED")
        }
    }

    override suspend fun fetchModels(providerId: String): List<ModelSpec> {
        return fetchModelDescriptors(providerId).map { descriptor ->
            ModelSpec(
                id = descriptor.id,
                name = descriptor.metadata.displayName ?: descriptor.id,
                type = descriptor.metadata.workload.toDomainModelType(),
                capabilities = emptyList(),
                providerId = providerId,
            )
        }
    }

    suspend fun fetchModelDescriptors(providerId: String): List<RemoteModelDescriptor> {
        val config = providerManager.getProviderConfig(providerId)
            ?: return emptyList()
        val unifiedConfig = UnifiedProviderConfig(
            protocolType = config.protocolType,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            defaultModel = config.model,
            serviceAccountJson = config.vertexServiceAccountJson,
            projectId = "",
            location = VERTEX_DEFAULT_LOCATION,
        )
        return try {
            modelDescriptorFetcher?.invoke(unifiedConfig)
                ?: modelListFetcher?.invoke(unifiedConfig)?.map { RemoteModelDescriptor(id = it) }
                ?: LlmProvider.builder()
                    .protocolType(unifiedConfig.protocolType)
                    .baseUrl(unifiedConfig.baseUrl)
                    .apiKey(unifiedConfig.apiKey)
                    .model(unifiedConfig.defaultModel)
                    .serviceAccountJson(unifiedConfig.serviceAccountJson)
                    .projectId(unifiedConfig.projectId)
                    .location(unifiedConfig.location)
                    .build()
                    .listModelDescriptors()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun com.promenar.infinity.data.model.catalog.ModelWorkload?.toDomainModelType(): ModelType = when (this) {
        com.promenar.infinity.data.model.catalog.ModelWorkload.EMBEDDING -> ModelType.EMBEDDING
        com.promenar.infinity.data.model.catalog.ModelWorkload.RERANK -> ModelType.RERANK
        com.promenar.infinity.data.model.catalog.ModelWorkload.IMAGE_GENERATION -> ModelType.IMAGE
        else -> ModelType.CHAT
    }

    override suspend fun save(config: ProviderConfig) {
        val dataProtocolType = config.protocolType.toData()
        if (config.id == "default") {
            providerManager.updateMainProvider(
                protocolType = dataProtocolType,
                baseUrl = config.baseUrl,
                credentialUpdate = config.apiKey.toCredentialUpdate(),
                model = config.defaultModel,
                name = config.name
            )
        } else {
            val item = ProviderListItem(
                id = config.id,
                name = config.name,
                baseUrl = config.baseUrl,
                model = config.defaultModel,
                protocolType = dataProtocolType,
                hasApiKey = config.apiKey.isNotBlank() && dataProtocolType !is ProtocolType.Google_VertexAI,
                hasVertexCredentials = config.apiKey.isNotBlank() && dataProtocolType is ProtocolType.Google_VertexAI,
            )
            val credentialUpdate = config.apiKey.toCredentialUpdate()
            if (providerManager.getProviderConfig(config.id) != null) {
                providerManager.updateExtraProvider(config.id, item, credentialUpdate)
            } else {
                providerManager.addProvider(item, credentialUpdate)
            }
        }
    }

    override suspend fun delete(id: String) {
        providerManager.deleteProvider(id)
    }

    private fun ProviderListItem.toDomain(): ProviderConfig {
        return ProviderConfig(
        id = id,
        name = name,
        protocolType = protocolType.toDomain(),
        baseUrl = baseUrl,
        apiKey = "",
        defaultModel = model,
        isEnabled = hasApiKey || hasVertexCredentials,
        )
    }

    private fun ProtocolType.toDomain(): com.promenar.infinity.domain.model.ProtocolType = when (this) {
        is ProtocolType.Anthropic_Messages -> com.promenar.infinity.domain.model.ProtocolType.ANTHROPIC
        is ProtocolType.Google_VertexAI -> com.promenar.infinity.domain.model.ProtocolType.VERTEX_AI
        else -> com.promenar.infinity.domain.model.ProtocolType.OPENAI
    }

    private fun com.promenar.infinity.domain.model.ProtocolType.toData(): ProtocolType = when (this) {
        com.promenar.infinity.domain.model.ProtocolType.OPENAI -> ProtocolType.Generic_OpenAI_Compat
        com.promenar.infinity.domain.model.ProtocolType.ANTHROPIC -> ProtocolType.Anthropic_Messages
        com.promenar.infinity.domain.model.ProtocolType.VERTEX_AI -> ProtocolType.Google_VertexAI
    }
}
