package com.promenar.infinity.data.remote.mcp

import android.content.SharedPreferences
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** MCP 连接的独立凭据。 */
class McpConnectionSettings(
    private val preferences: SharedPreferences,
    private val secretStore: SecretStore,
) {
    init { preferences.edit().remove("mcp_allow_loopback_http").apply() }

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun hasBearerToken(serverId: String): Boolean =
        serverId.isNotBlank() && runCatching { secretStore.contains(secretId(serverId)) }.getOrDefault(false)

    fun isAllowed(serverId: String, type: String, url: String): Boolean = McpEndpointPolicy.isAllowed(type, url)

    suspend fun saveBearerToken(serverId: String, token: String) {
        require(serverId.isNotBlank()) { "MCP_SERVER_ID_REQUIRED" }
        require(McpEndpointPolicy.isValidBearerToken(token)) { "MCP_BEARER_TOKEN_INVALID" }
        withContext(Dispatchers.IO) {
            val bytes = token.toByteArray(Charsets.UTF_8)
            try {
                secretStore.put(secretId(serverId), bytes)
                _revision.update { it + 1 }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw McpProtocolException("MCP_CREDENTIAL_UNAVAILABLE")
            } finally {
                bytes.fill(0)
            }
        }
    }

    suspend fun removeBearerToken(serverId: String) = withContext(Dispatchers.IO) {
        try {
            secretStore.remove(secretId(serverId))
            _revision.update { it + 1 }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw McpProtocolException("MCP_CREDENTIAL_UNAVAILABLE")
        }
    }

    suspend fun readBearerToken(serverId: String): String? = withContext(Dispatchers.IO) {
        val bytes = try {
            secretStore.get(secretId(serverId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw McpProtocolException("MCP_CREDENTIAL_UNAVAILABLE")
        } ?: return@withContext null
        try {
            bytes.toString(Charsets.UTF_8).also { token ->
                if (!McpEndpointPolicy.isValidBearerToken(token)) {
                    throw McpProtocolException("MCP_BEARER_TOKEN_INVALID")
                }
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun secretId(serverId: String): SecretId {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(serverId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return SecretId("mcp_bearer_v1:$hash")
    }

}
