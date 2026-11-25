package com.promenar.infinity.data.generation

import com.promenar.infinity.data.remote.ProviderRequestRouter
import com.promenar.infinity.data.remote.ProviderResolution
import com.promenar.infinity.data.remote.UnifiedLlmClient
import com.promenar.infinity.data.remote.protocol.ProtocolType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ChatProviderRoute(
    val client: UnifiedLlmClient?,
    val remoteModelId: String?,
    val failure: ProviderResolution.Failure?,
    val useLocalProvider: Boolean = false,
    val modelId: String? = null,
    val supportsToolCalls: Boolean = true,
    /** 云端接口的主机名，用于判断请求是否发往 tailnet；本地模型为 null。 */
    val endpointHost: String? = null,
    val protocolType: ProtocolType? = null,
)

internal sealed interface ChatRoutePreparation<out T> {
    data class Success<T>(val route: ChatProviderRoute, val context: T) : ChatRoutePreparation<T>
    data class Failure(val failure: ProviderResolution.Failure) : ChatRoutePreparation<Nothing> {
        override fun toString(): String = "ChatRoutePreparation.Failure(reason=${failure.reason})"
    }
}

internal class ChatProviderRouteGate(
    private val router: ProviderRequestRouter,
    private val resolutionDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun resolve(modelId: String): ChatProviderRoute = when (val resolution = router.resolve(modelId)) {
        is ProviderResolution.Failure -> ChatProviderRoute(null, null, resolution)
        is ProviderResolution.Success -> {
            val resolved = resolution.value
            val local = resolved.config.protocolType is ProtocolType.Local
            ChatProviderRoute(
                client = if (local) null else router.createClient(resolved),
                remoteModelId = resolved.remoteModelId,
                failure = null,
                useLocalProvider = local,
                modelId = resolved.modelId,
                protocolType = resolved.config.protocolType,
                endpointHost = if (local) null else runCatching { java.net.URI(resolved.config.baseUrl).host }.getOrNull(),
            )
        }
    }

    suspend fun <T> prepare(modelId: String, buildContext: suspend () -> T): ChatRoutePreparation<T> =
        prepareWithRoute(modelId) { buildContext() }

    /** 路由成功后才构建上下文，并让上下文按路由能力（如是否支持工具调用）组装。 */
    suspend fun <T> prepareWithRoute(
        modelId: String,
        buildContext: suspend (ChatProviderRoute) -> T,
    ): ChatRoutePreparation<T> {
        val route = withContext(resolutionDispatcher) { resolve(modelId) }
        route.failure?.let { return ChatRoutePreparation.Failure(it) }
        return ChatRoutePreparation.Success(route, buildContext(route))
    }
}
