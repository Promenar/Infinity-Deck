package com.promenar.infinity.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.coroutines.job

/** 按请求读取设置源，已创建的长期客户端也能立即使用新设置。 */
internal object ModelRequestTimeouts {
    @Volatile private var source: () -> ModelTimeoutSettings = { ModelTimeoutSettings() }

    fun install(settings: () -> ModelTimeoutSettings) { source = settings }

    fun socketMillis(kind: ModelTimeoutKind): Long = source().seconds(kind).toLong() * 1000L
}

internal fun HttpRequestBuilder.applyModelTimeout(kind: ModelTimeoutKind) {
    timeout {
        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        socketTimeoutMillis = ModelRequestTimeouts.socketMillis(kind)
    }
}

/** 注入的传输也必须声明超时能力；派生配置共用原引擎，不重建连接池。 */
internal fun HttpClient.withModelTimeoutSupport(): HttpClient {
    if (pluginOrNull(HttpTimeout) != null) return this
    val configured = config { install(HttpTimeout) }
    coroutineContext.job.invokeOnCompletion { configured.close() }
    return configured
}
