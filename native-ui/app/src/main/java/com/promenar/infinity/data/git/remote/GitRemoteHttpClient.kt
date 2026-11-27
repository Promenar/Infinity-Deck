package com.promenar.infinity.data.git.remote

import com.promenar.infinity.data.proxy.BuiltInProxyRouting
import com.promenar.infinity.data.proxy.shouldBypassOptionalBuiltInProxy
import com.promenar.infinity.data.tailnet.SystemDefaultProxySelector
import com.promenar.infinity.data.tailnet.TailnetEndpointSource
import com.promenar.infinity.data.tailnet.TailnetHosts
import com.promenar.infinity.data.tailnet.TailnetRouting
import com.promenar.infinity.domain.network.PublicHttpPolicy
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/** Git HTTPS 与 GitHub 请求共用的出站路由，按当前代理状态为新连接选路。 */
internal object GitRemoteHttpClient {
    private const val PROXY_AUTHENTICATE = "Proxy-Authenticate"
    private const val PREEMPTIVE_CHALLENGE = "OkHttp-Preemptive"

    fun create(
        tailnet: TailnetRouting,
        builtInProxy: BuiltInProxyRouting,
        fallback: ProxySelector = SystemDefaultProxySelector,
    ): OkHttpClient {
        val tailnetAuthenticator = tailnet.proxyAuthenticator
        val tailnetEndpoints = tailnet as? TailnetEndpointSource
        val builtInAuthenticator = builtInProxy.proxyAuthenticator
        return OkHttpClient.Builder()
            .proxySelector(GitRemoteProxySelector(tailnet, builtInProxy, fallback))
            .proxyAuthenticator { route, response ->
                if (!response.isProxyChallenge()) return@proxyAuthenticator null
                authenticateTailnet(tailnetAuthenticator, tailnetEndpoints, route, response)
                    ?: builtInAuthenticator.authenticate(route, response)
            }
            .addNetworkInterceptor(tailnet.proxyAuthInterceptor)
            .addNetworkInterceptor(builtInProxy.proxyAuthInterceptor)
            .build()
    }

    private fun Response.isProxyChallenge(): Boolean {
        if (code != 407) return false
        val connect = request.method == "CONNECT"
        // HTTPS 建隧道后的 407 来自源站，代理凭据只用于 CONNECT。
        if (request.url.isHttps && !connect) return false
        if (header(PROXY_AUTHENTICATE) != PREEMPTIVE_CHALLENGE) return true
        // OkHttp 为合成质询设置两个 -1 时间戳，在线路上收到的 407 无法设置这两个字段。
        return connect && sentRequestAtMillis == -1L && receivedResponseAtMillis == -1L
    }

    private fun authenticateTailnet(
        authenticator: Authenticator,
        endpoints: TailnetEndpointSource?,
        route: Route?,
        response: Response,
    ): Request? {
        if (response.header(PROXY_AUTHENTICATE) == PREEMPTIVE_CHALLENGE) {
            return authenticator.authenticate(route, response)
        }
        // Tailscale 认证器校验代理地址。这里补上与 tailnet/proxy.go 一致的 realm 校验。
        val endpoint = endpoints?.currentEndpoint() ?: return null
        val realm = "infinity-tailnet-${endpoint.user}"
        if (response.challenges().none { it.scheme.equals("Basic", ignoreCase = true) && it.realm == realm }) {
            return null
        }
        return authenticator.authenticate(route, response).takeIf { endpoints.currentEndpoint() === endpoint }
    }
}

private class GitRemoteProxySelector(
    private val tailnet: TailnetRouting,
    private val builtInProxy: BuiltInProxyRouting,
    private val fallback: ProxySelector,
) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> {
        val host = uri?.host?.let(TailnetHosts::normalize) ?: return fallback.select(uri)
        val literal = TailnetHosts.parseAddressLiteral(host)
        if (isTailnetHost(host, literal)) return tailnet.proxySelector.select(uri)
        if (shouldBypassOptionalBuiltInProxy(host)) {
            return fallback.select(uri)
        }
        val proxy = builtInProxy.endpoint.value?.proxy ?: return fallback.select(uri)
        return listOf(proxy)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        val host = uri?.host?.let(TailnetHosts::normalize)
        when {
            host != null && isTailnetHost(host, TailnetHosts.parseAddressLiteral(host)) ->
                tailnet.proxySelector.connectFailed(uri, sa, ioe)
            sa != null && sa == builtInProxy.endpoint.value?.address -> Unit
            else -> fallback.connectFailed(uri, sa, ioe)
        }
    }

    private fun isTailnetHost(host: String, literal: ByteArray?): Boolean =
        tailnet.isTailnetHost(host) || (literal != null && TailnetHosts.isTailnetAddress(literal))
}
