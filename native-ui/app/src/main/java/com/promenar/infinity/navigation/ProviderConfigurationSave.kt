package com.promenar.infinity.navigation

import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.model.CredentialUpdate
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.remote.protocol.ProtocolType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 提供商表单与首次引导共用的保存入口，返回保存后的提供商 ID。
 * [providerId] 为空时：主提供商尚未配置则写入主提供商，使 Tier 3 兜底生效；否则新增额外提供商。
 */
internal suspend fun saveProviderConfiguration(
    app: InfinityApplication,
    providerId: String?,
    protocolType: ProtocolType,
    baseUrl: String,
    credential: CredentialUpdate,
    model: String,
    name: String?,
): String = withContext(Dispatchers.IO) {
    val providerManager = ProviderManager.getInstance()
    val existingSummary = providerId?.let(providerManager::getProviderSummary)
    val credentialPresent = when (credential) {
        is CredentialUpdate.Replace -> true
        CredentialUpdate.Clear -> false
        CredentialUpdate.Preserve -> existingSummary?.let { it.hasApiKey || it.hasVertexCredentials } ?: false
    }
    fun listItem(id: String) = ProviderListItem(
        id = id,
        name = name ?: protocolType.legacyDisplayName,
        baseUrl = baseUrl,
        model = model,
        protocolType = protocolType,
        hasApiKey = credentialPresent && protocolType !is ProtocolType.Google_VertexAI,
        hasVertexCredentials = credentialPresent && protocolType is ProtocolType.Google_VertexAI,
    )
    when (providerId) {
        null -> {
            val mainConfig = providerManager.getProviderSummary(DEFAULT_PROVIDER_ID)
            if (mainConfig == null || (!mainConfig.hasApiKey && !mainConfig.hasVertexCredentials)) {
                app.updateProvider(protocolType, baseUrl, credential, model, name)
                DEFAULT_PROVIDER_ID
            } else {
                val id = "extra_${UUID.randomUUID()}"
                providerManager.addProvider(listItem(id), credential)
                id
            }
        }
        DEFAULT_PROVIDER_ID -> {
            app.updateProvider(protocolType, baseUrl, credential, model, name)
            providerId
        }
        else -> {
            providerManager.updateExtraProvider(providerId, listItem(providerId), credential)
            providerId
        }
    }
}

private const val DEFAULT_PROVIDER_ID = "default"
