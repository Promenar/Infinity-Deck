package com.promenar.infinity.data.proxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Interceptor
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
