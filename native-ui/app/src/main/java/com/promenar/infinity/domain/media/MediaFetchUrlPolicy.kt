package com.promenar.infinity.domain.media

import com.promenar.infinity.domain.network.AgentNetworkPolicy
import java.net.URI
import java.util.Locale

/** 不做 DNS 或网络访问；节点名由当前 tailnet 路由提供，固定地址段在离线时也能识别。 */
class MediaFetchUrlPolicy(private val isTailnetHost: (String) -> Boolean) {
    fun isTailnet(host: String): Boolean = AgentNetworkPolicy.isTailnetName(AgentNetworkPolicy.normalizeHost(host), isTailnetHost)

    fun validate(raw: String): String? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return "媒体 URL 格式无效"
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme !in setOf("https", "http")) return "媒体 URL 只支持 HTTP 或 HTTPS"
        if (uri.rawUserInfo != null) return "媒体 URL 不允许包含用户信息"
        if (uri.host.isNullOrBlank()) return "媒体 URL 缺少有效主机"
        if (uri.port < -1 || uri.port == 0 || uri.port > 65535) return "媒体 URL 端口无效"
        return null
    }
}
