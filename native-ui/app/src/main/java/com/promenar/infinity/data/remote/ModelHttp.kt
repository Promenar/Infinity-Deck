package com.promenar.infinity.data.remote

import com.promenar.infinity.data.proxy.BuiltInPreemptiveProxyAuthInterceptor
import com.promenar.infinity.data.proxy.BuiltInProxyAuthenticator
import com.promenar.infinity.data.proxy.BuiltInProxyEndpoint
import com.promenar.infinity.data.proxy.BuiltInProxyRouting
import com.promenar.infinity.data.proxy.BuiltInProxyUnavailableException
import com.promenar.infinity.data.proxy.DisabledBuiltInProxyRouting
import com.promenar.infinity.data.tailnet.DisabledTailnetRouting
import com.promenar.infinity.data.tailnet.TailnetConnectGate
import com.promenar.infinity.data.tailnet.TailnetConnectResult
import com.promenar.infinity.data.tailnet.TailnetHosts
import com.promenar.infinity.data.tailnet.TailnetRouting
import com.promenar.infinity.data.tailnet.TailnetUnavailableException
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.callContext
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Url
import io.ktor.util.AttributeKey
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * 模型调用共享 Ktor 引擎，在每一跳选择 OkHttp 传输。Tailnet 优先，其余公网目标可按提供商
 * 启用内置代理。该代理的连接池按提供商与代理端点隔离。
 */
object ModelHttp {
    private data class RoutingSources(
        val tailnet: () -> TailnetRouting,
        val gate: () -> TailnetConnectGate?,
        val proxy: () -> BuiltInProxyRouting,
    )

    @Volatile private var sources = RoutingSources({ DisabledTailnetRouting }, { null }, { DisabledBuiltInProxyRouting })

    /** 保留两参数入口与尾随 lambda 用法。取值函数只在网络请求中求值。 */
    fun install(routing: () -> TailnetRouting, gate: () -> TailnetConnectGate?) {
        install(routing, gate, proxy = { DisabledBuiltInProxyRouting })
    }

    fun install(
        routing: () -> TailnetRouting,
        gate: () -> TailnetConnectGate?,
        proxy: () -> BuiltInProxyRouting,
    ) {
        sources = RoutingSources(routing, gate, proxy)
    }

    private val originalEngine: HttpClientEngine by lazy {
        OkHttp.create {
            addInterceptor(TailnetRouteGuard({ sources.tailnet() }, { sources.gate() }))
            config {
                connectionPool(ConnectionPool())
                proxySelector(DelegatingProxySelector { sources.tailnet().proxySelector })
                proxyAuthenticator { route, response -> sources.tailnet().proxyAuthenticator.authenticate(route, response) }
                addNetworkInterceptor { chain -> sources.tailnet().proxyAuthInterceptor.intercept(chain) }
            }
        }
    }

    val engine: HttpClientEngine by lazy { ModelRouteEngine(originalEngine) { sources } }

    fun client(block: HttpClientConfig<*>.() -> Unit = {}): HttpClient = HttpClient(engine, block)

    internal fun client(
        useBuiltInProxy: Boolean,
        providerId: String?,
        block: HttpClientConfig<*>.() -> Unit = {},
    ): HttpClient = client {
        block()
        if (useBuiltInProxy) {
            val poolKey = providerId ?: Any()
            defaultRequest { attributes.put(BuiltInProxyRequestKey, BuiltInProxyRequest(true, poolKey)) }
        }
    }

    @OptIn(InternalAPI::class)
    private class ModelRouteEngine(
        private val original: HttpClientEngine,
        private val sources: () -> RoutingSources,
    ) : HttpClientEngineBase("model-http") {
        override val config get() = original.config
        override val supportedCapabilities get() = original.supportedCapabilities
        private val proxyTransports = ProxyTransports()

        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            val request = data.attributes.getOrNull(BuiltInProxyRequestKey)
            if (request?.enabled != true) return original.execute(data)
            val routing = sources()
            val host = data.url.host
            val bypass = routing.tailnet().isTailnetHost(host) || routing.gate()?.mayReject(host) == true ||
                TailnetHosts.isTailnetHost(host, magicDnsSuffix = null, peerNames = emptySet()) || isLanOrLoopback(host)
            request.checkRoute(data.url, bypass)
            if (bypass) return original.execute(data)
            val proxy = routing.proxy()
            return proxyTransports.acquire(request.poolKey, proxy, callContext().job).execute(data)
        }
    }
}

private val BuiltInProxyRequestKey = AttributeKey<BuiltInProxyRequest>("model-built-in-proxy")

/** 将选路绑定到本次请求，Ktor 的重定向会复制该属性。 */
internal fun HttpRequestBuilder.routeViaBuiltInProxy(enabled: Boolean, providerId: String? = null) {
    attributes.put(BuiltInProxyRequestKey, BuiltInProxyRequest(enabled, providerId ?: Any()))
}

private class BuiltInProxyRequest(val enabled: Boolean, val poolKey: Any) {
    private var origin: Triple<String, String, Int>? = null
    private var bypassProxy: Boolean? = null

    fun checkRoute(url: Url, bypass: Boolean) {
        val next = Triple(url.protocol.name, url.host, url.port)
        val previous = origin
        if (previous != null && previous != next) throw IOException("模型请求已拒绝跨源重定向")
        if (bypassProxy != null && bypassProxy != bypass) throw BuiltInProxyUnavailableException("模型请求的代理路由已变化")
        origin = next
        bypassProxy = bypass
    }
}

private fun isLanOrLoopback(host: String): Boolean {
    val normalized = TailnetHosts.normalize(host)
    val bytes = TailnetHosts.parseAddressLiteral(normalized.substringBefore('%'))
    if (bytes != null) {
        val address = InetAddress.getByAddress(bytes)
        return address.isLoopbackAddress || address.isAnyLocalAddress || address.isSiteLocalAddress ||
            address.isLinkLocalAddress || (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc)
    }
    // 纯数字和十六进制短字面量可能被系统解释为公网 IPv4 地址。
    val shortName = '.' !in normalized && ':' !in normalized &&
        normalized.any(Char::isLetter) && !normalized.startsWith("0x")
    return shortName || normalized.endsWith(".localhost") || normalized.endsWith(".local") ||
        normalized.endsWith(".lan") || normalized.endsWith(".internal") || normalized.endsWith(".home.arpa")
}

private class ProxyTransports {
    private class Transport(
        val engine: HttpClientEngine,
        var calls: Int = 0,
        var retired: Boolean = false,
    )

    private val entries = LinkedHashMap<Any, Transport>(MAX_TRANSPORTS, 0.75f, true)
    private var currentRouting: BuiltInProxyRouting? = null
    private var currentEndpoint: BuiltInProxyEndpoint? = null

    @Synchronized
    fun acquire(key: Any, routing: BuiltInProxyRouting, call: Job): HttpClientEngine {
        val endpoint = routing.endpoint.value
        if (currentRouting !== routing || currentEndpoint !== endpoint) {
            entries.values.forEach(::retire)
            entries.clear()
            currentRouting = routing
            currentEndpoint = endpoint
        }
        if (endpoint == null) throw BuiltInProxyUnavailableException()
        val transport = entries.getOrPut(key) { Transport(createProxyEngine(routing, endpoint)) }
        if (entries.size > MAX_TRANSPORTS) {
            val oldest = entries.entries.iterator()
            retire(oldest.next().value)
            oldest.remove()
        }
        transport.calls++
        call.invokeOnCompletion { release(transport) }
        return transport.engine
    }

    private fun retire(transport: Transport) {
        transport.retired = true
        if (transport.calls == 0) transport.engine.close()
    }

    /** 流式响应结束后才回收已经淘汰的传输，避免影响在途连接。 */
    @Synchronized
    private fun release(transport: Transport) {
        transport.calls--
        if (transport.retired && transport.calls == 0) transport.engine.close()
    }

    private companion object {
        const val MAX_TRANSPORTS = 16
    }
}

private fun createProxyEngine(routing: BuiltInProxyRouting, endpoint: BuiltInProxyEndpoint): HttpClientEngine {
    val pinned = object : BuiltInProxyRouting {
        override val endpoint = MutableStateFlow<BuiltInProxyEndpoint?>(endpoint)
    }
    val authenticator = BuiltInProxyAuthenticator(pinned, publicTargetOnly = true)
    val authInterceptor = BuiltInPreemptiveProxyAuthInterceptor(pinned, publicTargetOnly = true)

    fun checkEndpoint() {
        if (routing.endpoint.value !== endpoint) throw BuiltInProxyUnavailableException()
    }
    return OkHttp.create {
        config {
            // Ktor 的 OkHttp 原型默认共享池；显式分配并固定代理，避免复用原路由或上次代理的连接。
            connectionPool(ConnectionPool())
            proxy(endpoint.proxy)
            proxyAuthenticator { route, response ->
                checkEndpoint()
                authenticator.authenticate(route, response).also { request ->
                    if (request == null && response.request.header("Proxy-Authorization") != null) {
                        throw BuiltInProxyUnavailableException("内置代理认证失败")
                    }
                }
            }
            addInterceptor { chain ->
                checkEndpoint()
                try {
                    chain.proceed(chain.request()).also { response ->
                        if (!chain.request().url.isHttps && response.code == 407) {
                            response.close()
                            throw BuiltInProxyUnavailableException("内置代理认证失败")
                        }
                    }
                } catch (_: ConnectException) {
                    throw BuiltInProxyUnavailableException("无法连接内置代理")
                } catch (_: NoRouteToHostException) {
                    throw BuiltInProxyUnavailableException("无法连接内置代理")
                }
            }
            addNetworkInterceptor { chain ->
                checkEndpoint()
                authInterceptor.intercept(chain)
            }
        }
    }
}

/** tailnet 目标先经 [TailnetConnectGate] 确认已接入（运行在 OkHttp 调用线程，可阻塞等待）。 */
internal class TailnetRouteGuard(
    private val routing: () -> TailnetRouting,
    private val gate: () -> TailnetConnectGate?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        val current = gate()
        // 内置 Tailscale 关闭时 routing 不认 tailnet 目标，仍要经关口判断是否应立即失败。
        if (current != null && (routing().isTailnetHost(host) || current.mayReject(host))) {
            val result = runBlocking { current.await(host, timeoutMillis = TailnetConnectGate.REQUEST_TIMEOUT_MS) }
            if (result is TailnetConnectResult.Unavailable) throw TailnetUnavailableException(result.reason)
        }
        return chain.proceed(chain.request())
    }
}

private class DelegatingProxySelector(private val delegate: () -> ProxySelector) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> = delegate().select(uri)
    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = delegate().connectFailed(uri, sa, ioe)
}
