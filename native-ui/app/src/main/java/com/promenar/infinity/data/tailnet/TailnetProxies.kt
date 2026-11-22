package com.promenar.infinity.data.tailnet

import com.jcraft.jsch.ProxySOCKS5
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

/** 路由组件读取的本机代理状态；由 [TailnetManager] 实现，测试使用假实现。 */
internal interface TailnetEndpointSource {
    fun isTailnetHost(host: String): Boolean
    /** 当前代理端点；子进程未运行时为 null，不触发启动。 */
    fun currentEndpoint(): TailnetProxyEndpoint?
    /** 需要 tailnet 路由时调用：按需启动子进程，最多等待 [timeoutMillis]。 */
    fun awaitEndpoint(timeoutMillis: Long): TailnetProxyEndpoint?
}

internal object TailnetProxyAddress {
    /** 127.0.0.1 字节构造，不经 DNS。 */
    private val LOOPBACK_V4: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))

    fun of(endpoint: TailnetProxyEndpoint): InetSocketAddress = InetSocketAddress(LOOPBACK_V4, endpoint.port)

    fun matches(address: SocketAddress?, endpoint: TailnetProxyEndpoint): Boolean {
        val socket = address as? InetSocketAddress ?: return false
        if (socket.port != endpoint.port) return false
        val bytes = socket.address?.address ?: return false
        return bytes.size == 4 && bytes[0] == 127.toByte() && bytes[1] == 0.toByte() && bytes[2] == 0.toByte() && bytes[3] == 1.toByte()
    }
}

/**
 * OkHttp/Ktor 的分流选择器：tailnet 目标返回本机 HTTP 代理（HTTPS 走 CONNECT，域名由代理端解析），
 * 其它目标交给 [fallback]——默认是系统默认选择器，与未接入内置 Tailscale 时 OkHttp 的行为相同
 * （没有系统代理时即 DIRECT）。
 */
class TailnetProxySelector internal constructor(
    private val source: TailnetEndpointSource,
    private val fallback: (URI?) -> List<Proxy> = { uri -> SystemDefaultProxySelector.selectFallback(uri, SystemDefaultProxySelector) },
    private val routeWaitMillis: Long = ROUTE_WAIT_MS,
) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> {
        val host = uri?.host
        if (host != null && source.isTailnetHost(host)) {
            source.awaitEndpoint(routeWaitMillis)?.let { endpoint ->
                return listOf(Proxy(Proxy.Type.HTTP, TailnetProxyAddress.of(endpoint)))
            }
        }
        return fallback(uri)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        val endpoint = source.currentEndpoint()
        if (endpoint != null && TailnetProxyAddress.matches(sa, endpoint)) return
        SystemDefaultProxySelector.connectFailed(uri, sa, ioe)
    }

    companion object {
        const val ROUTE_WAIT_MS = 5_000L
    }
}

/**
 * 只为本机 tailnet 代理的 407 补上本次启动的 Basic 凭据（OkHttp 对 CONNECT 先以合成 407 询问）；
 * 其它代理与源站认证一律返回 null，与 [Authenticator.NONE] 相同。已带凭据仍被拒绝时不再重试。
 */
class TailnetProxyAuthenticator internal constructor(
    private val source: TailnetEndpointSource,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.code != 407) return null
        val endpoint = source.currentEndpoint() ?: return null
        val proxy = route?.proxy ?: return null
        if (proxy.type() != Proxy.Type.HTTP || !TailnetProxyAddress.matches(proxy.address(), endpoint)) return null
        val credential = Credentials.basic(endpoint.user, endpoint.password, Charsets.UTF_8)
        if (response.request.header(PROXY_AUTHORIZATION) == credential) return null
        return response.request.newBuilder().header(PROXY_AUTHORIZATION, credential).build()
    }

    private companion object {
        const val PROXY_AUTHORIZATION = "Proxy-Authorization"
    }
}

/**
 * 必须作为 OkHttp `addNetworkInterceptor` 装配：仅在真实路由命中本机 tailnet 明文 HTTP 代理且请求尚未携带
 * `Proxy-Authorization` 时，在首跳直接注入本次启动的 Basic 凭据。
 * 这避免了所有明文 HTTP 请求先空发请求体再因 407 重试（以及 `isOneShot()` 流式上传在 407 挑战时直接失败），
 * 同时因严格校验 `route.proxy` 地址端口与 `!req.url.isHttps` 而绝不向外部代理或 HTTPS 源站泄漏凭据。
 */
class TailnetPreemptiveProxyAuthInterceptor internal constructor(
    private val source: TailnetEndpointSource,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.isHttps || request.header(PROXY_AUTHORIZATION) != null) {
            return chain.proceed(request)
        }
        val endpoint = source.currentEndpoint() ?: return chain.proceed(request)
        val proxy = chain.connection()?.route()?.proxy ?: return chain.proceed(request)
        if (proxy.type() != Proxy.Type.HTTP || !TailnetProxyAddress.matches(proxy.address(), endpoint)) {
            return chain.proceed(request)
        }
        val credential = Credentials.basic(endpoint.user, endpoint.password, Charsets.UTF_8)
        return chain.proceed(request.newBuilder().header(PROXY_AUTHORIZATION, credential).build())
    }

    private companion object {
        const val PROXY_AUTHORIZATION = "Proxy-Authorization"
    }
}

internal object TailnetProxyConfig {
    const val NO_PROXY = "localhost,127.0.0.1,::1"

    /** JSch：SOCKS5 带用户名口令；JSch 以域名形式（ATYP 3）发送主机名，由代理端解析 MagicDNS。 */
    fun jsch(endpoint: TailnetProxyEndpoint): com.jcraft.jsch.Proxy =
        ProxySOCKS5(TailnetProxyEndpoint.HOST, endpoint.port).apply { setUserPasswd(endpoint.user, endpoint.password) }

    /**
     * 子进程代理变量：dulwich（urllib3）与 Python urllib 读取小写 `https_proxy`/`http_proxy`（取不到再读 `all_proxy`），
     * 凭据写在 URL 中，由客户端转成 Proxy-Authorization；`ALL_PROXY` 的 socks5h 供支持 SOCKS 的工具远端解析域名。
     * 代理对非 tailnet 目标直连（系统 DNS），本机地址经 NO_PROXY 不进代理。
     */
    fun environment(endpoint: TailnetProxyEndpoint): Map<String, String> {
        val authority = "${endpoint.user}:${endpoint.password}@${TailnetProxyEndpoint.HOST}:${endpoint.port}"
        val http = "http://$authority"
        val socks = "socks5h://$authority"
        return linkedMapOf(
            "HTTPS_PROXY" to http,
            "https_proxy" to http,
            "HTTP_PROXY" to http,
            "http_proxy" to http,
            "ALL_PROXY" to socks,
            "all_proxy" to socks,
            "NO_PROXY" to NO_PROXY,
            "no_proxy" to NO_PROXY,
        )
    }
}
