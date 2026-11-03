package com.promenar.infinity.domain.network

import com.promenar.infinity.data.tailnet.TailnetHosts
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NetworkAccessDenied(val code: String, message: String) : IllegalArgumentException(message)

data class AgentNetworkTarget(val host: String, val target: NetworkTargetClass, val addresses: List<InetAddress>)

data class AgentNetworkEndpoint(val uri: URI, val destination: AgentNetworkTarget) {
    val host: String get() = destination.host
    val target: NetworkTargetClass get() = destination.target
    val addresses: List<InetAddress> get() = destination.addresses
}

/** App 内联网工具共用的目标分类与选路；路由名称先于系统 DNS 判定。 */
class AgentNetworkPolicy(
    private val isTailnetHost: (String) -> Boolean = { false },
    private val tailnetRunning: () -> Boolean = { false },
    private val resolver: suspend (String) -> Array<InetAddress> = { host ->
        withContext(Dispatchers.IO) { InetAddress.getAllByName(host) }
    },
) {
    suspend fun classify(rawHost: String): AgentNetworkTarget {
        val host = normalizeHost(rawHost)
        if (isTailnetName(host, isTailnetHost)) return AgentNetworkTarget(host, NetworkTargetClass.TAILNET, emptyList())
        val addresses = try { resolver(host).toList() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { denied("DNS_FAILED", "主机解析失败，请检查地址与网络连接") }
        if (addresses.isEmpty()) denied("DNS_FAILED", "主机没有可用地址，请检查地址与网络连接")
        if (addresses.any { it.isAnyLocalAddress || it.isMulticastAddress }) denied("ADDRESS_DENIED", "目标包含未指定地址或组播地址，禁止访问")
        val target = when {
            addresses.any { it.isLoopbackAddress } -> NetworkTargetClass.LOOPBACK
            addresses.any { TailnetHosts.isTailnetAddress(it.address) } -> NetworkTargetClass.TAILNET
            addresses.any(PublicHttpPolicy::isPrivateOrLocalAddress) -> NetworkTargetClass.LAN
            else -> NetworkTargetClass.PUBLIC
        }
        return AgentNetworkTarget(host, target, addresses)
    }

    suspend fun validate(raw: String, tool: AgentNetworkTool = AgentNetworkTool.WEB_FETCH): AgentNetworkEndpoint {
        val uri = runCatching { URI(raw) }.getOrNull() ?: denied("URL_POLICY", "URL 格式无效")
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (raw.length > 4096 || scheme !in setOf("http", "https") || uri.rawUserInfo != null || uri.host.isNullOrBlank() ||
            uri.port < -1 || uri.port == 0 || uri.port > 65535) denied("URL_POLICY", "仅支持不含凭据、主机与端口有效的 HTTP/HTTPS URL")
        val destination = classify(uri.host)
        if (stateFor(tool, destination.target) == NetworkAccessState.TAILNET_REQUIRED && !tailnetRunning()) {
            denied("TAILNET_OFFLINE", TAILNET_GUIDANCE)
        }
        return AgentNetworkEndpoint(uri, destination)
    }

    companion object {
        const val TAILNET_GUIDANCE = "内置 Tailscale 未运行或代理不可用，请在 Tailscale 设置中启用并连接；已拒绝回退直连"

        fun normalizeHost(host: String): String = host.lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]")

        fun isTailnetName(host: String, routing: (String) -> Boolean): Boolean =
            TailnetHosts.parseAddressLiteral(host)?.let(TailnetHosts::isTailnetAddress) == true || routing(host) ||
                TailnetHosts.isTailnetHost(host, null, emptySet())

        fun stateFor(tool: AgentNetworkTool, target: NetworkTargetClass): NetworkAccessState = when (tool) {
            AgentNetworkTool.WEB_FETCH, AgentNetworkTool.MEDIA_FETCH, AgentNetworkTool.SCRIPT -> when (target) {
                NetworkTargetClass.PUBLIC, NetworkTargetClass.LAN, NetworkTargetClass.LOOPBACK -> NetworkAccessState.ALLOWED
                NetworkTargetClass.TAILNET -> NetworkAccessState.TAILNET_REQUIRED
            }
            AgentNetworkTool.MCP, AgentNetworkTool.SSH -> NetworkAccessState.CONFIGURED_ONLY
            AgentNetworkTool.BUILTIN_PROCESS -> if (target == NetworkTargetClass.TAILNET) NetworkAccessState.TAILNET_REQUIRED else NetworkAccessState.UNRESTRICTED
            AgentNetworkTool.TERMUX -> NetworkAccessState.UNRESTRICTED
        }

        val rows: List<NetworkAccessRow> = AgentNetworkTool.entries.map { tool ->
            NetworkAccessRow(tool, NetworkTargetClass.entries.map { NetworkAccessCell(it, stateFor(tool, it)) })
        }

        private fun denied(code: String, message: String): Nothing = throw NetworkAccessDenied(code, message)
    }
}
