package com.promenar.infinity.ui.welcome

import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.remote.*

/** 引导允许验证尚未启用的当前候选；提供商及其它路由门禁保持一致。 */
internal fun resolveOnboardingModelRoute(manager: ProviderManager, providerId: String, modelId: String): ProviderResolution {
    val candidate = manager.providerModels.value.firstOrNull { it.id == modelId }
        ?: return ProviderResolution.Failure(ProviderResolutionError.MODEL_NOT_FOUND, modelId)
    if (candidate.providerId != providerId) {
        return ProviderResolution.Failure(ProviderResolutionError.MODEL_PROVIDER_MISMATCH, modelId, providerId)
    }
    return DefaultProviderRequestRouter(
        modelResolver = { id -> candidate.takeIf { it.id == id }?.copy(enabled = true) },
        providerResolver = { id -> manager.providers.value.firstOrNull { it.id == id } },
        configResolver = manager::getProviderConfig,
        unsupportedPersistedProtocolResolver = manager::isPersistedProtocolUnsupported,
    ).resolve(modelId)
}
