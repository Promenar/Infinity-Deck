package com.promenar.infinity.data.remote.mcp

import java.net.URI

/** MCP 的传输与凭据边界。 */
object McpEndpointPolicy {
    enum class Kind { HTTPS, HTTP, LOOPBACK_HTTP }

    fun kind(url: String): Kind? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null ||
            uri.port < -1 || uri.port == 0 || uri.port > 65535) return null
        return when {
            uri.scheme.equals("https", ignoreCase = true) -> Kind.HTTPS
            uri.scheme.equals("http", ignoreCase = true) ->
                if (uri.host in setOf("127.0.0.1", "localhost", "::1", "[::1]")) Kind.LOOPBACK_HTTP else Kind.HTTP
            else -> null
        }
    }

    fun isAllowed(type: String, url: String): Boolean = type.equals("http", ignoreCase = true) && kind(url) != null

    fun isValidBearerToken(token: String): Boolean = BEARER_TOKEN.matches(token)

    private val BEARER_TOKEN = Regex("[A-Za-z0-9._~+/-]+=*")
}
