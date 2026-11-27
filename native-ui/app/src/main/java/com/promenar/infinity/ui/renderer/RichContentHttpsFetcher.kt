package com.promenar.infinity.ui.renderer

import com.promenar.infinity.data.proxy.BuiltInProxyRouting
import com.promenar.infinity.data.proxy.InstalledBuiltInProxyRouting
import com.promenar.infinity.data.proxy.applyOptionalBuiltInProxy
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal class RichContentPublicDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (!RichContentRequestPolicy.arePublicAddresses(addresses)) throw UnknownHostException("富内容仅允许公网地址")
        return addresses
    }
}

internal class RichContentHttpsFetcher(
    client: OkHttpClient? = null,
    private val builtInProxy: () -> BuiltInProxyRouting = { InstalledBuiltInProxyRouting.routing },
) {
    private val customClient = client?.newBuilder()?.followRedirects(false)?.followSslRedirects(false)?.build()

    fun fetch(url: String, headers: Map<String, String> = emptyMap()): Response? {
        var current = url
        repeat(6) {
            if (RichContentRequestPolicy.classify(current) != RichContentRequestPolicy.Kind.PUBLIC_HTTPS) return null
            val request = Request.Builder().url(current).apply {
                headers.filterKeys { it.lowercase() in setOf("accept", "accept-language", "user-agent", "origin", "range") }
                    .forEach { (name, value) -> header(name, value) }
            }.build()
            val activeClient = customClient ?: if (builtInProxy().endpoint.value != null) proxiedClient else publicClient
            val response = try {
                activeClient.newCall(request).execute()
            } catch (_: Exception) {
                return null
            }
            if (response.code !in 300..399) return response
            val next = response.header("Location")?.let(response.request.url::resolve)
            response.close()
            current = next?.toString() ?: return null
        }
        return null
    }

    companion object {
        // 直连时解析门禁与实际连接共用同一份地址结果；关闭系统代理，避免未知系统代理代解析绕过校验。
        private val publicClient = OkHttpClient.Builder()
            .dns(RichContentPublicDns())
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

        // 启用受控内置代理时附加 publicTargetOnly 标记，由 libnxproxy 侧同样强校验仅允许公网目标。
        private val proxiedClient = OkHttpClient.Builder()
            .applyOptionalBuiltInProxy(publicTargetOnly = true)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
