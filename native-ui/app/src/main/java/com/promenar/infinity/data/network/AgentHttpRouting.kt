package com.promenar.infinity.data.network

import com.promenar.infinity.data.tailnet.TailnetHosts
import com.promenar.infinity.data.tailnet.TailnetRouting
import com.promenar.infinity.domain.network.AgentNetworkEndpoint
import com.promenar.infinity.domain.network.NetworkTargetClass
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.net.UnknownHostException
import javax.net.ssl.SSLSocketFactory
import okhttp3.Authenticator
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** 每一跳的客户端与实际连接目标；原始 URL 留给重定向、输出与 TLS 身份验证。 */
class AgentHttpRoute(val client: OkHttpClient, private val requestUrl: String, private val hostHeader: String?) {
    fun request(request: Request): Request = request.newBuilder().url(requestUrl).apply {
        hostHeader?.let {
            header("Host", it)
            // Go 代理按绝对 URL 解析 HTTP Host，另传原始主机供转发时恢复。
            if (!request.url.isHttps) header("X-Infinity-Origin-Host", it)
        }
    }.build()
}

object AgentHttpRouting {
    fun route(base: OkHttpClient, endpoint: AgentNetworkEndpoint, routing: TailnetRouting): AgentHttpRoute {
        val builder = base.newBuilder().followRedirects(false).followSslRedirects(false)
        var url = endpoint.uri.toString()
        var hostHeader: String? = null
        if (endpoint.target == NetworkTargetClass.TAILNET) {
            builder.proxy(AgentTailnetProxy.requireProxy(routing)).proxyAuthenticator(routing.proxyAuthenticator)
            val address = endpoint.addresses.firstOrNull { TailnetHosts.isTailnetAddress(it.address) }
            if (address != null) {
                // 普通域名经系统 DNS 归入 tailnet 时，CONNECT 必须发送固定 IP，避免代理再次按普通域名直连。
                val origin = url.toHttpUrl()
                if (origin.isHttps) {
                    val trustManager = checkNotNull(base.x509TrustManager) { "HTTPS 客户端缺少证书校验器" }
                    builder.sslSocketFactory(OriginTlsSocketFactory(base.sslSocketFactory, endpoint.host), trustManager)
                        .hostnameVerifier { _, session ->
                            base.hostnameVerifier.verify(endpoint.host, session) && runCatching {
                                base.certificatePinner.check(endpoint.host, session.peerCertificates.toList())
                            }.isSuccess
                        }
                }
                hostHeader = origin.host.let { if (':' in it) "[$it]" else it } + if (origin.port == (if (origin.isHttps) 443 else 80)) "" else ":${origin.port}"
                url = origin.newBuilder().host(checkNotNull(address.hostAddress)).build().toString()
            }
        } else {
            // 分类与连接使用相同地址集合；避免系统代理重新解析后改变 tailnet 选路。
            val addresses = endpoint.addresses.filterNot { TailnetHosts.isTailnetAddress(it.address) }
            builder.proxy(Proxy.NO_PROXY).proxyAuthenticator(Authenticator.NONE).dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (hostname.equals(endpoint.host, true)) addresses else throw UnknownHostException("主机未通过校验")
            })
        }
        return AgentHttpRoute(builder.build(), url, hostHeader)
    }
}

/** 隧道连接到固定 IP，但证书链、SNI 与主机校验始终使用原始主机身份。 */
private class OriginTlsSocketFactory(private val delegate: SSLSocketFactory, private val host: String) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun createSocket(socket: Socket, hostname: String, port: Int, autoClose: Boolean): Socket =
        delegate.createSocket(socket, host, port, autoClose)
    override fun createSocket(): Socket = unsupported()
    override fun createSocket(host: String, port: Int): Socket = unsupported()
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()
    override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = unsupported()
    private fun unsupported(): Nothing = throw IOException("仅允许在已建立的内置代理隧道上创建 TLS 连接")
}
