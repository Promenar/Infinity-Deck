package com.promenar.infinity.data.proxy

import com.promenar.infinity.data.network.awaitAgentResponse
import com.promenar.infinity.data.tailnet.TailnetHosts
import com.promenar.infinity.domain.network.PublicHttpPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** 每次跳转重新检查公网地址，直连使用已经校验的 DNS 结果。 */
class BuiltInProxySubscriptionFetcher(
    private val routing: BuiltInProxyRouting,
    private val dns: Dns = Dns.SYSTEM,
    private val base: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(12, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun fetch(rawUrl: String): String = withContext(Dispatchers.IO) {
        val starting = subscriptionUrl(rawUrl)
        try {
            fetchChain(starting, null)
        } catch (_: SubscriptionTransportFailure) {
            val proxy = routing.endpoint.value ?: throw IOException("订阅下载失败")
            try {
                fetchChain(starting, proxy)
            } catch (_: SubscriptionTransportFailure) {
                throw IOException("订阅下载失败")
            }
        }
    }

    private suspend fun fetchChain(starting: HttpUrl, endpoint: BuiltInProxyEndpoint?): String {
        var url = starting
        repeat(MAX_REDIRECTS + 1) {
            val host = url.host
            if (TailnetHosts.isTailnetHost(host, null, emptySet())) rejectLocal()
            val addresses = try { dns.lookup(host) } catch (_: UnknownHostException) {
                throw SubscriptionTransportFailure()
            }
            if (addresses.isEmpty() || addresses.any(PublicHttpPolicy::isPrivateOrLocalAddress)) rejectLocal()
            val builder = base.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .dns(object : Dns {
                    override fun lookup(hostname: String) = if (hostname.equals(host, true)) addresses
                    else throw UnknownHostException("订阅主机未通过校验")
                })
            if (endpoint != null) {
                if (routing.endpoint.value != endpoint) throw SubscriptionTransportFailure()
                builder.proxy(endpoint.proxy)
                    .proxyAuthenticator(BuiltInProxyAuthenticator(routing, publicTargetOnly = true))
                    .addNetworkInterceptor(BuiltInPreemptiveProxyAuthInterceptor(routing, publicTargetOnly = true))
            } else {
                builder.proxy(Proxy.NO_PROXY).proxyAuthenticator(Authenticator.NONE)
            }
            val response = try {
                builder.build().newCall(Request.Builder().url(url).build()).awaitAgentResponse()
            } catch (_: IOException) {
                throw SubscriptionTransportFailure()
            }
            response.use {
                if (it.code in REDIRECT_CODES) {
                    val location = it.header("Location") ?: throw IOException("订阅跳转缺少目标")
                    val next = url.resolve(location)?.let { resolved -> subscriptionUrl(resolved.toString()) }
                        ?: throw IOException("订阅跳转无效")
                    if (url.isHttps && !next.isHttps) throw IOException("订阅跳转要求保持 HTTPS")
                    url = next
                } else {
                    if (!it.isSuccessful) throw IOException("订阅服务器返回 HTTP ${it.code}")
                    val body = it.body ?: throw IOException("订阅内容为空")
                    if (body.contentLength() > MAX_BYTES) throw IOException("订阅内容超过大小限制")
                    val source = body.source()
                    val oversized = try { source.request(MAX_BYTES + 1) } catch (_: IOException) {
                        throw SubscriptionTransportFailure()
                    }
                    if (oversized) throw IOException("订阅内容超过大小限制")
                    return source.readUtf8().takeIf { content -> content.isNotBlank() } ?: throw IOException("订阅内容为空")
                }
            }
        }
        throw IOException("订阅跳转次数超过限制")
    }

    private fun rejectLocal(): Nothing = throw IOException("订阅地址必须指向公网")

    private class SubscriptionTransportFailure : IOException()

    companion object {
        internal const val MAX_BYTES = 1_048_576L
        private const val MAX_REDIRECTS = 4
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        internal fun subscriptionUrl(rawUrl: String): HttpUrl {
            val value = rawUrl.trim()
            val url = value.takeIf { it.length <= 4096 && it.none(Char::isISOControl) }?.toHttpUrlOrNull()
            require(url != null && url.encodedUsername.isEmpty() && url.encodedPassword.isEmpty() && url.fragment == null) {
                "订阅地址须为不含用户凭据和片段的 HTTP 或 HTTPS URL"
            }
            return url
        }
    }
}
