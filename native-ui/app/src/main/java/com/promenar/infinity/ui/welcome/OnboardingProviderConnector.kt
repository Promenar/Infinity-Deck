package com.promenar.infinity.ui.welcome

import com.promenar.infinity.data.model.CredentialUpdate
import com.promenar.infinity.data.model.ProviderSummary
import com.promenar.infinity.data.remote.UnifiedProviderConfig
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.protocol.VERTEX_DEFAULT_LOCATION
import com.promenar.infinity.data.remote.provider.ProviderConnectionProbeFailure
import com.promenar.infinity.data.remote.provider.ProviderConnectionProbeResult
import com.promenar.infinity.ui.settings.isProviderEndpointAllowed
import com.promenar.infinity.ui.settings.persistVerifiedProviderConnection
import kotlinx.coroutines.CancellationException

/** 引导内联表单的输入；[secret] 对 Vertex 是服务账号 JSON，对其它协议是 API Key。 */
internal data class OnboardingProviderDraft(
    val protocol: ProtocolType,
    val baseUrl: String,
    val secret: String,
    val name: String?,
) {
    override fun toString(): String =
        "OnboardingProviderDraft(protocol=$protocol, hasSecret=${secret.isNotBlank()})"
}

internal sealed interface OnboardingConnectOutcome {
    data class Connected(val providerId: String) : OnboardingConnectOutcome

    /** [reason] 为空表示探测通过但保存或读回校验失败。 */
    data class Failed(
        val reason: ProviderConnectionProbeFailure?,
        val statusCode: Int?,
    ) : OnboardingConnectOutcome

    /** 输入本身不可用（空凭证、无效 HTTP/HTTPS 地址或已退役协议），不发起任何网络请求。 */
    data object Invalid : OnboardingConnectOutcome

    data object LocalModelMissing : OnboardingConnectOutcome
}

internal typealias OnboardingProviderSave = suspend (
    existingProviderId: String?,
    protocol: ProtocolType,
    baseUrl: String,
    credential: CredentialUpdate,
    model: String,
    name: String?,
) -> String

/**
 * 引导里“连接”按钮的唯一动作：探测一次，成功才保存并读回校验。
 * 返回后再次连接传入 [existingProviderId]，更新同一个提供商而不是新增。
 * 协议没有可用探测时同样保存，由选模型步骤的极小请求完成最终验证。
 */
internal suspend fun connectOnboardingProvider(
    draft: OnboardingProviderDraft,
    existingProviderId: String?,
    probe: suspend (UnifiedProviderConfig) -> ProviderConnectionProbeResult,
    save: OnboardingProviderSave,
    readSummary: suspend (String) -> ProviderSummary?,
): OnboardingConnectOutcome {
    val secret = draft.secret.trim()
    val baseUrl = draft.baseUrl.trim()
    if (secret.isEmpty() || !isProviderEndpointAllowed(draft.protocol, baseUrl)) {
        return OnboardingConnectOutcome.Invalid
    }
    val usesVertex = draft.protocol is ProtocolType.Google_VertexAI
    val probeResult = probe(
        UnifiedProviderConfig(
            protocolType = draft.protocol,
            baseUrl = baseUrl,
            apiKey = if (usesVertex) "" else secret,
            defaultModel = "",
            serviceAccountJson = if (usesVertex) secret else "",
            projectId = "",
            location = VERTEX_DEFAULT_LOCATION,
        ),
    )
    if (probeResult is ProviderConnectionProbeResult.Failure) {
        return OnboardingConnectOutcome.Failed(probeResult.reason, probeResult.statusCode)
    }
    return savingOutcome {
        persistVerifiedProviderConnection(
            protocolType = draft.protocol,
            baseUrl = baseUrl,
            credential = CredentialUpdate.Replace(secret),
            model = "",
            name = draft.name?.takeIf(String::isNotBlank),
            onSave = { protocol, url, credential, model, name ->
                save(existingProviderId, protocol, url, credential, model, name)
            },
            readSummary = readSummary,
        )
    }
}

/** 本地推理不经网络探测；没有已加载的本地模型时不保存。 */
internal suspend fun connectOnboardingLocalProvider(
    models: List<String>,
    existingProviderId: String?,
    save: OnboardingProviderSave,
): OnboardingConnectOutcome {
    val model = models.firstOrNull(String::isNotBlank) ?: return OnboardingConnectOutcome.LocalModelMissing
    return savingOutcome {
        save(existingProviderId, ProtocolType.Local, "", CredentialUpdate.Preserve, model, null)
    }
}

private inline fun savingOutcome(save: () -> String): OnboardingConnectOutcome = try {
    OnboardingConnectOutcome.Connected(save())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    OnboardingConnectOutcome.Failed(null, null)
}
