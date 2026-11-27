package com.promenar.infinity.data.proxy

import com.promenar.infinity.data.tailnet.SystemDefaultProxySelector
import com.promenar.infinity.data.tailnet.TailnetHosts
import com.promenar.infinity.domain.network.PublicHttpPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

data class BuiltInProxyEndpoint(val port: Int, val proxyUser: String, val proxyPassword: String) {
    init { require(port in 1..65535) }

    val host: String get() = HOST
    val realm: String get() = "infinity-proxy-$proxyUser"
    internal val address: InetSocketAddress get() = InetSocketAddress(LOOPBACK, port)
    val proxy: Proxy get() = Proxy(Proxy.Type.HTTP, address)

    override fun toString(): String = "BuiltInProxyEndpoint(port=$port)"

    internal fun matches(proxy: Proxy?): Boolean =
        proxy?.type() == Proxy.Type.HTTP && proxy.address() == address

    companion object {
        const val HOST = "127.0.0.1"
        private val LOOPBACK = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    }
}

interface BuiltInProxyEndpointSource {
    val endpoint: StateFlow<BuiltInProxyEndpoint?>
}

interface BuiltInProxyRouting : BuiltInProxyEndpointSource {
    val proxySelector: ProxySelector get() = BuiltInProxySelector(this)
    val proxyAuthenticator: Authenticator get() = BuiltInProxyAuthenticator(this)
    val proxyAuthInterceptor: Interceptor get() = BuiltInPreemptiveProxyAuthInterceptor(this)
}

object DisabledBuiltInProxyRouting : BuiltInProxyRouting {
    override val endpoint: StateFlow<BuiltInProxyEndpoint?> = MutableStateFlow(null)
}

object InstalledBuiltInProxyRouting {
    @Volatile var routing: BuiltInProxyRouting = DisabledBuiltInProxyRouting
}

/** 供明确要求走内置代理的客户端使用，端点消失时拒绝回退。 */
class BuiltInProxySelector(private val source: BuiltInProxyEndpointSource) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> =
        listOf(source.endpoint.value?.proxy ?: throw BuiltInProxyUnavailableException())

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
}

class BuiltInProxyAuthenticator(
    private val source: BuiltInProxyEndpointSource,
    private val publicTargetOnly: Boolean = false,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.code != 407 || response.request.header(PROXY_AUTHORIZATION) != null) return null
        val endpoint = source.endpoint.value ?: return null
        if (!endpoint.matches(route?.proxy)) return null
        // CONNECT 会先经 OkHttp 的合成质询；真正的 407 必须属于当前进程的 realm。
        val matches = response.challenges().any {
            it.scheme.equals("Basic", ignoreCase = true) && it.realm == endpoint.realm ||
                it.scheme.equals("OkHttp-Preemptive", ignoreCase = true) &&
                response.request.method == "CONNECT" && response.header("Proxy-Authenticate") == "OkHttp-Preemptive"
        }
        return if (matches) response.request.withProxyCredentials(endpoint, publicTargetOnly) else null
    }
}

/** 明文 HTTP 在首跳发送凭据，避免流式请求体先被 407 拒绝。 */
class BuiltInPreemptiveProxyAuthInterceptor(
    private val source: BuiltInProxyEndpointSource,
    private val publicTargetOnly: Boolean = false,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val endpoint = source.endpoint.value ?: return chain.proceed(request)
        if (!endpoint.matches(chain.connection()?.route()?.proxy)) return chain.proceed(request)
        if (request.url.isHttps) {
            return chain.proceed(request.newBuilder().removeHeader(PROXY_AUTHORIZATION).removeHeader(PUBLIC_TARGET_HEADER).build())
        }
        return chain.proceed(request.withProxyCredentials(endpoint, publicTargetOnly))
    }
}

private const val PUBLIC_TARGET_HEADER = "X-Infinity-Public-Target"
private const val PROXY_AUTHORIZATION = "Proxy-Authorization"
private const val PROXY_AUTHENTICATE = "Proxy-Authenticate"
private const val PREEMPTIVE_CHALLENGE = "OkHttp-Preemptive"

/**
 * 为公网出站 OkHttpClient 挂载按需内置代理路由：
 * - 内置代理运行时，公网目标自动经由内置代理并附加 realm/地址严格校验的认证凭据；
 * - 内置代理关闭，或目标属于本机、局域网、私网、Tailnet 地址时，回退至 [fallback]（默认系统代理/直连）。
 */
internal fun OkHttpClient.Builder.applyOptionalBuiltInProxy(
    routing: () -> BuiltInProxyRouting = { InstalledBuiltInProxyRouting.routing },
    publicTargetOnly: Boolean = true,
    fallback: ProxySelector = SystemDefaultProxySelector,
): OkHttpClient.Builder {
    val dynamicSource = object : BuiltInProxyEndpointSource {
        override val endpoint: StateFlow<BuiltInProxyEndpoint?> get() = routing().endpoint
    }
    val authenticator = BuiltInProxyAuthenticator(dynamicSource, publicTargetOnly = publicTargetOnly)
    return proxySelector(OptionalBuiltInProxySelector(routing, fallback))
        .proxyAuthenticator { route, response ->
            if (!response.isBuiltInProxyChallenge()) return@proxyAuthenticator null
            authenticator.authenticate(route, response)
        }
        .addNetworkInterceptor(BuiltInPreemptiveProxyAuthInterceptor(dynamicSource, publicTargetOnly = publicTargetOnly))
}

internal fun Response.isBuiltInProxyChallenge(): Boolean {
    if (code != 407) return false
    val connect = request.method == "CONNECT"
    if (request.url.isHttps && !connect) return false
    if (header(PROXY_AUTHENTICATE) != PREEMPTIVE_CHALLENGE) return true
    return connect && sentRequestAtMillis == -1L && receivedResponseAtMillis == -1L
}

internal class OptionalBuiltInProxySelector(
    private val routing: () -> BuiltInProxyRouting,
    private val fallback: ProxySelector = SystemDefaultProxySelector,
) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> {
        val host = uri?.host?.let(TailnetHosts::normalize) ?: return fallback.select(uri)
        if (shouldBypassOptionalBuiltInProxy(host)) return fallback.select(uri)
        val proxy = routing().endpoint.value?.proxy ?: return fallback.select(uri)
        return listOf(proxy)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        if (sa != null && sa == routing().endpoint.value?.address) return
        fallback.connectFailed(uri, sa, ioe)
    }
}

internal fun shouldBypassOptionalBuiltInProxy(normalizedHost: String): Boolean {
    if (normalizedHost.isEmpty()) return true
    val literal = TailnetHosts.parseAddressLiteral(normalizedHost)
    if (literal != null) {
        if (TailnetHosts.isTailnetAddress(literal)) return true
        return PublicHttpPolicy.isPrivateOrLocalAddress(InetAddress.getByAddress(literal))
    }
    if (normalizedHost == "localhost" || normalizedHost.endsWith(".localhost") ||
        normalizedHost.endsWith(".local") || normalizedHost.endsWith(".lan") ||
        normalizedHost.endsWith(".internal") || normalizedHost.endsWith(".home.arpa") ||
        normalizedHost.endsWith(".ts.net")
    ) {
        return true
    }
    return '.' !in normalizedHost && ':' !in normalizedHost &&
        normalizedHost.any(Char::isLetter) && !normalizedHost.startsWith("0x")
}

private fun Request.withProxyCredentials(endpoint: BuiltInProxyEndpoint, publicTargetOnly: Boolean): Request =
    newBuilder().header(
        PROXY_AUTHORIZATION, Credentials.basic(endpoint.proxyUser, endpoint.proxyPassword, Charsets.UTF_8),
    ).apply {
        if (publicTargetOnly) header(PUBLIC_TARGET_HEADER, "1") else removeHeader(PUBLIC_TARGET_HEADER)
    }.build()

object BuiltInProxyConfig {
    const val HTTP = "INFINITY_PROXY_HTTP"
    const val SOCKS = "INFINITY_PROXY_SOCKS"
    const val NO_PROXY = "INFINITY_PROXY_NO_PROXY"
    val environmentKeys: Set<String> = setOf(HTTP, SOCKS, NO_PROXY)

    fun environment(endpoint: BuiltInProxyEndpoint): Map<String, String> {
        fun url(scheme: String) = URI(
            scheme, "${endpoint.proxyUser}:${endpoint.proxyPassword}", endpoint.host, endpoint.port, null, null, null,
        ).toASCIIString()
        return mapOf(
            HTTP to url("http"),
            SOCKS to url("socks5h"),
            NO_PROXY to "localhost,127.0.0.1,::1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,100.64.0.0/10",
        )
    }
}
