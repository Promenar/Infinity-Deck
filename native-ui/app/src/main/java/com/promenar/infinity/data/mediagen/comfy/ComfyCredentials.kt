package com.promenar.infinity.data.mediagen.comfy

import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Credentials
import java.security.MessageDigest

/** 凭据只由界面传入密钥存储，不提供给智能体读取。 */
sealed class ComfyCredential {
    class Bearer(val token: String) : ComfyCredential()
    class Basic(val username: String, val password: String) : ComfyCredential()
    final override fun toString() = "ComfyCredential(已隐藏)"
}
class ComfyCredentials(private val secrets: SecretStore) {
    suspend fun put(host: ComfyHost, credential: ComfyCredential) = withContext(Dispatchers.IO) {
        val value = when (credential) {
            is ComfyCredential.Bearer -> {
                require(host.authKind == ComfyAuthKind.BEARER && credential.token.isNotBlank() && credential.token.none { it <= ' ' || it == '\u007f' }) { "令牌格式无效" }
                buildJsonObject { put("kind", "BEARER"); put("token", credential.token) }
            }
            is ComfyCredential.Basic -> {
                require(host.authKind == ComfyAuthKind.BASIC && credential.username.isNotBlank() && !credential.username.contains(':') &&
                    (credential.username + credential.password).none { it < ' ' || it == '\u007f' }) { "基本认证格式无效" }
                buildJsonObject { put("kind", "BASIC"); put("username", credential.username); put("password", credential.password) }
            }
        }.toString().toByteArray()
        try { secrets.put(secretId(host.id), value) } finally { value.fill(0) }
    }
    suspend fun authorization(host: ComfyHost): String? = withContext(Dispatchers.IO) {
        if (host.authKind == ComfyAuthKind.NONE) return@withContext null
        val bytes = secrets.get(secretId(host.id)) ?: throw ComfyFailure("主机鉴权未配置，请用户在主机设置中填写")
        try {
            val record = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            check(record["kind"]?.jsonPrimitive?.content == host.authKind.name) { "鉴权类型不匹配" }
            when (host.authKind) {
                ComfyAuthKind.BEARER -> "Bearer ${record.getValue("token").jsonPrimitive.content}"
                ComfyAuthKind.BASIC -> Credentials.basic(record.getValue("username").jsonPrimitive.content, record.getValue("password").jsonPrimitive.content, Charsets.UTF_8)
                ComfyAuthKind.NONE -> null
            }
        } catch (_: Exception) { throw ComfyFailure("主机鉴权不可用，请用户重新配置") }
        finally { bytes.fill(0) }
    }
    suspend fun contains(hostId: String): Boolean = withContext(Dispatchers.IO) { secrets.contains(secretId(hostId)) }
    suspend fun remove(hostId: String) = withContext(Dispatchers.IO) { secrets.remove(secretId(hostId)) }
    companion object {
        fun secretId(hostId: String) = SecretId("comfy_auth_v1:" + MessageDigest.getInstance("SHA-256").digest(hostId.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) })
    }
}
