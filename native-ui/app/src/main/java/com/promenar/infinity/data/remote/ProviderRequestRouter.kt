package com.promenar.infinity.data.remote

import com.promenar.infinity.BuildConfig
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.model.ProviderConfig
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.remote.middleware.LlmMiddleware
import com.promenar.infinity.data.remote.protocol.LlmProtocol
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.PromptResponse
import com.promenar.infinity.data.remote.protocol.ProtocolFactory
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.protocol.ProviderEndpointOperation
import com.promenar.infinity.data.remote.protocol.ProviderEndpointResolver
import com.promenar.infinity.data.remote.protocol.ProviderEndpointTarget
import com.promenar.infinity.data.remote.protocol.StreamChunk
import com.promenar.infinity.data.remote.protocol.UnsupportedProviderProtocolException
import com.promenar.infinity.data.remote.protocol.VERTEX_DEFAULT_LOCATION
import com.promenar.infinity.data.remote.provider.VertexCredentialParser
import com.promenar.infinity.data.remote.provider.VertexCredentialException
import com.promenar.infinity.data.model.ModelInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.net.URI

data class ResolvedProviderModel(
    val modelId: String,
    val remoteModelId: String,
    val providerId: String,
    val providerName: String,
    val config: UnifiedProviderConfig,
)

enum class ProviderResolutionError {
    MODEL_NOT_FOUND,
    MODEL_DISABLED,
    PROVIDER_ID_MISSING,
    PROVIDER_NOT_FOUND,
    PROVIDER_DISABLED,
    API_KEY_MISSING,
    BASE_URL_INVALID,
    MODEL_PROVIDER_MISMATCH,
    PROTOCOL_MISMATCH,
    PROTOCOL_UNSUPPORTED,
    LOCAL_INFERENCE_UNAVAILABLE,
    VERTEX_CREDENTIAL_INVALID,
}

sealed interface ProviderResolution {
    data class Success(val value: ResolvedProviderModel) : ProviderResolution
    data class Failure(
        val reason: ProviderResolutionError,
        val modelId: String,
        val providerId: String? = null,
    ) : ProviderResolution {
        override fun toString(): String = "ProviderResolution.Failure(reason=$reason)"
    }
}

interface ProviderRequestRouter {
    fun resolve(modelId: String): ProviderResolution

    /** 允许模型测速等诊断入口在保留正式对话拦截语义的前提下，解析已停用模型的传输配置。 */
    fun resolve(modelId: String, allowDisabledModel: Boolean): ProviderResolution = resolve(modelId)

    fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient

    /** 返回统一包裹会话头自动解析的 [LlmProtocol]；所有非聊天工具流的模型推理均经此出口。 */
    fun createProtocol(
        resolved: ResolvedProviderModel,
        defaultSessionId: String? = null,
        rawProtocolFactory: (ResolvedProviderModel) -> LlmProtocol = { route ->
            val config = route.config
            ProtocolFactory.create(
                type = config.protocolType,
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                model = route.remoteModelId,
                serviceAccountJson = config.serviceAccountJson,
                projectId = config.projectId,
                location = config.location,
            )
        },
    ): LlmProtocol = if (resolved.config.protocolType == ProtocolType.Local) {
        rawProtocolFactory(resolved)
    } else {
        SessionHeaderRoutedProtocol(rawProtocolFactory(resolved), resolved, this, defaultSessionId)
    }

    suspend fun sessionHeaders(resolved: ResolvedProviderModel, sessionId: String?): SessionRequestHeaders? = null
}

/**
 * 统一路由协议装饰器：各业务方仅传入 `sessionId`（或作用域默认值），由路由层按提供商与模型配置
 * 自动决定是否注入会话请求头与客户端 `User-Agent`。
 */
internal class SessionHeaderRoutedProtocol(
    private val delegate: LlmProtocol,
    private val resolved: ResolvedProviderModel,
    private val router: ProviderRequestRouter,
    private val defaultSessionId: String? = null,
) : LlmProtocol by delegate {
    private suspend fun PromptRequest.withResolvedSessionHeaders(): PromptRequest {
        if (sessionHeaders != null) return this
        val effectiveSessionId = sessionId?.takeIf(String::isNotBlank)
            ?: defaultSessionId?.takeIf(String::isNotBlank)
            ?: return this
        val resolvedHeaders = router.sessionHeaders(resolved, effectiveSessionId)
        return copy(sessionId = effectiveSessionId, sessionHeaders = resolvedHeaders)
    }

    override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = flow {
        emitAll(delegate.sendPrompt(request.withResolvedSessionHeaders()))
    }

    override suspend fun sendPromptSync(request: PromptRequest): PromptResponse =
        delegate.sendPromptSync(request.withResolvedSessionHeaders())
}

fun stableModelId(providerId: String, remoteModelId: String): String =
    "$providerId::$remoteModelId"

class DefaultProviderRequestRouter(
    private val modelResolver: (String) -> ModelInfo?,
    private val providerResolver: (String) -> ProviderListItem?,
    private val configResolver: (String) -> ProviderConfig?,
    private val unsupportedPersistedProtocolResolver: (String) -> Boolean = { false },
    private val localInferenceAvailable: () -> Boolean = { BuildConfig.LOCAL_INFERENCE_AVAILABLE },
    private val sessionIdentifier: suspend (String) -> String? = { null },
    private val clientFactory: (UnifiedProviderConfig) -> UnifiedLlmClient = { config ->
        UnifiedLlmClient(providerConfigResolver = { config }, sessionIdentifier = sessionIdentifier)
    },
) : ProviderRequestRouter {

    constructor(
        providerManager: ProviderManager,
        middlewares: List<LlmMiddleware> = emptyList(),
        sessionIdentifier: suspend (String) -> String? = { null },
        sessionHeaderPreferences: com.promenar.infinity.data.local.SessionHeaderPreferences? = null,
        localRuntimeSlot: (() -> com.promenar.infinity.data.local.inference.SlotState?)? = null,
        localRuntimeEnabled: () -> Boolean = { false },
    ) : this(
        modelResolver = { id ->
            val runtimeModel = com.promenar.infinity.data.local.inference.LocalSessionModelBridge.activeLocalModelInfo(
                slot = localRuntimeSlot?.invoke(),
                localModelsEnabled = localRuntimeEnabled(),
            )?.takeIf { it.id == id }
            runtimeModel ?: providerManager.providerModels.value.firstOrNull { it.id == id }?.let { model ->
                sessionHeaderPreferences?.let { model.copy(sessionHeaderMode = it.model(id)) } ?: model
            }
        },
        providerResolver = { id ->
            if (id == com.promenar.infinity.data.local.inference.LocalSessionModelBridge.LOCAL_RUNTIME_PROVIDER_ID) {
                com.promenar.infinity.data.local.inference.LocalSessionModelBridge.activeLocalProviderItem(
                    slot = localRuntimeSlot?.invoke(),
                    localModelsEnabled = localRuntimeEnabled(),
                )
            } else {
                providerManager.providers.value.firstOrNull { it.id == id }
            }
        },
        configResolver = { id ->
            if (id == com.promenar.infinity.data.local.inference.LocalSessionModelBridge.LOCAL_RUNTIME_PROVIDER_ID) {
                com.promenar.infinity.data.local.inference.LocalSessionModelBridge.activeLocalProviderConfig(
                    slot = localRuntimeSlot?.invoke(),
                    localModelsEnabled = localRuntimeEnabled(),
                )
            } else {
                providerManager.getProviderConfig(id)?.let { config ->
                    sessionHeaderPreferences?.provider(id)?.let { headers ->
                        config.copy(sessionHeadersEnabled = headers.enabled, sessionHeaderName = headers.name)
                    } ?: config
                }
            }
        },
        unsupportedPersistedProtocolResolver = providerManager::isPersistedProtocolUnsupported,
        sessionIdentifier = sessionIdentifier,
        clientFactory = { config -> UnifiedLlmClient({ config }, middlewares, sessionIdentifier = sessionIdentifier) },
    )

    override fun resolve(modelId: String): ProviderResolution =
        resolve(modelId, allowDisabledModel = false)

    override fun resolve(modelId: String, allowDisabledModel: Boolean): ProviderResolution {
        val model = modelResolver(modelId)
            ?: return failure(ProviderResolutionError.MODEL_NOT_FOUND, modelId)
        val providerId = model.providerId
            ?: return failure(ProviderResolutionError.PROVIDER_ID_MISSING, modelId)
        if (!model.enabled && !allowDisabledModel) {
            return failure(ProviderResolutionError.MODEL_DISABLED, modelId, providerId)
        }
        if (unsupportedPersistedProtocolResolver(providerId)) {
            return failure(ProviderResolutionError.PROTOCOL_UNSUPPORTED, modelId, providerId)
        }
        val expectedId = stableModelId(providerId, model.remoteModelId)
        if (model.id != expectedId || modelId != expectedId) {
            return failure(ProviderResolutionError.MODEL_PROVIDER_MISMATCH, modelId, providerId)
        }
        val provider = providerResolver(providerId)
            ?: return failure(ProviderResolutionError.PROVIDER_NOT_FOUND, modelId, providerId)
        if (!provider.enabled) {
            return failure(ProviderResolutionError.PROVIDER_DISABLED, modelId, providerId)
        }
        val raw = configResolver(providerId)
            ?: return failure(ProviderResolutionError.PROVIDER_NOT_FOUND, modelId, providerId)
        if (raw.protocolType::class != provider.protocolType::class) {
            return failure(ProviderResolutionError.PROTOCOL_MISMATCH, modelId, providerId)
        }
        if (raw.protocolType == ProtocolType.Cohere_Chat || raw.protocolType == ProtocolType.Yi_ZeroOne) {
            return failure(ProviderResolutionError.PROTOCOL_UNSUPPORTED, modelId, providerId)
        }
        if (raw.protocolType is ProtocolType.Local && !localInferenceAvailable()) {
            return failure(ProviderResolutionError.LOCAL_INFERENCE_UNAVAILABLE, modelId, providerId)
        }
        if (raw.protocolType !is ProtocolType.Local && !isHttpEndpoint(raw.baseUrl)) {
            return failure(ProviderResolutionError.BASE_URL_INVALID, modelId, providerId)
        }
        val credentialMissing = when (raw.protocolType) {
            is ProtocolType.Local -> false
            is ProtocolType.Google_VertexAI -> raw.vertexServiceAccountJson.isBlank()
            else -> raw.apiKey.isBlank()
        }
        if (credentialMissing) {
            return failure(ProviderResolutionError.API_KEY_MISSING, modelId, providerId)
        }
        val vertexProjectId = if (raw.protocolType is ProtocolType.Google_VertexAI) {
            try {
                VertexCredentialParser.parse(raw.vertexServiceAccountJson).projectId
            } catch (_: VertexCredentialException) {
                return failure(
                    ProviderResolutionError.VERTEX_CREDENTIAL_INVALID,
                    modelId,
                    providerId,
                )
            }
        } else {
            ""
        }
        try {
            when (raw.protocolType) {
                ProtocolType.Local -> Unit
                ProtocolType.Google_VertexAI -> ProviderEndpointResolver.resolve(
                    protocol = raw.protocolType,
                    configuredBaseUrl = raw.baseUrl,
                    target = ProviderEndpointTarget.VertexInference(
                        projectId = vertexProjectId,
                        location = VERTEX_DEFAULT_LOCATION,
                        model = model.remoteModelId,
                        streaming = false,
                    ),
                )
                else -> ProviderEndpointResolver.resolve(
                    protocol = raw.protocolType,
                    configuredBaseUrl = raw.baseUrl,
                    operation = ProviderEndpointOperation.INFERENCE,
                )
            }
        } catch (_: UnsupportedProviderProtocolException) {
            return failure(ProviderResolutionError.PROTOCOL_UNSUPPORTED, modelId, providerId)
        } catch (_: IllegalArgumentException) {
            return failure(ProviderResolutionError.BASE_URL_INVALID, modelId, providerId)
        }
        val config = UnifiedProviderConfig(
            protocolType = raw.protocolType,
            baseUrl = raw.baseUrl,
            apiKey = raw.apiKey,
            defaultModel = model.remoteModelId,
            serviceAccountJson = raw.vertexServiceAccountJson,
            projectId = vertexProjectId,
            location = VERTEX_DEFAULT_LOCATION,
            sessionHeaderName = com.promenar.infinity.data.model.SessionHeaderPolicy.resolveName(
                raw.sessionHeadersEnabled, raw.sessionHeaderName, model.sessionHeaderMode,
            ),
        )
        return ProviderResolution.Success(
            ResolvedProviderModel(
                modelId = model.id,
                remoteModelId = model.remoteModelId,
                providerId = providerId,
                providerName = provider.name,
                config = config,
            )
        )
    }

    override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient =
        clientFactory(resolved.config)

    override suspend fun sessionHeaders(resolved: ResolvedProviderModel, sessionId: String?): SessionRequestHeaders? =
        resolveSessionRequestHeaders(resolved.config.sessionHeaderName, sessionId, sessionIdentifier)

    private fun failure(
        reason: ProviderResolutionError,
        modelId: String,
        providerId: String? = null,
    ) = ProviderResolution.Failure(reason, modelId, providerId)

    private fun isHttpEndpoint(value: String): Boolean = runCatching {
        val uri = URI(value)
        if (uri.host.isNullOrBlank()) return@runCatching false
        uri.rawUserInfo == null && uri.port in -1..65535 && uri.port != 0 &&
            (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true))
    }.getOrDefault(false)

}
