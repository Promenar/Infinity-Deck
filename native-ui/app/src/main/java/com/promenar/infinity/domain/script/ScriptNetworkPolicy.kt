package com.promenar.infinity.domain.script

import com.promenar.infinity.domain.network.AgentNetworkPolicy
import com.promenar.infinity.domain.network.AgentNetworkTool
import com.promenar.infinity.domain.network.NetworkAccessDenied
import com.promenar.infinity.domain.network.NetworkTargetClass
import java.net.InetAddress

data class ScriptEndpoint(val url: String, val host: String, val addresses: List<InetAddress>, val target: NetworkTargetClass = NetworkTargetClass.PUBLIC)

object ScriptNetworkPolicy {
    suspend fun validate(url: String, resolver: suspend (String) -> Array<InetAddress>): ScriptEndpoint =
        validate(url, AgentNetworkPolicy(resolver = resolver))

    suspend fun validate(url: String, policy: AgentNetworkPolicy): ScriptEndpoint = try {
        val endpoint = policy.validate(url, AgentNetworkTool.SCRIPT)
        ScriptEndpoint(url, endpoint.host, endpoint.addresses, endpoint.target)
    } catch (failure: NetworkAccessDenied) { denied("${failure.code}：${failure.message}") }

    private fun denied(message: String): Nothing = throw ScriptFailure("NETWORK_DENIED", message)
}

class ScriptNetworkBudget(
    private val maxRequests: Int = ScriptLimits.NETWORK_REQUESTS,
    private val bodyBytes: Int = ScriptLimits.NETWORK_BODY_BYTES,
    private val totalBytes: Int = ScriptLimits.NETWORK_TOTAL_BYTES,
) {
    private var requests = 0
    private var received = 0
    @Synchronized fun request(bytes: Int) {
        if (++requests > maxRequests || bytes > bodyBytes || bytes < 0) throw ScriptFailure("NETWORK_LIMIT", "请求体或请求次数超过限制")
    }
    @Synchronized fun response(bytes: Int) {
        if (bytes < 0 || bytes > bodyBytes || received.toLong() + bytes > totalBytes) throw ScriptFailure("NETWORK_LIMIT", "响应体或累计响应超过限制")
        received += bytes
    }
}
