package com.promenar.infinity.domain.keychain

import kotlinx.coroutines.flow.Flow

enum class KeychainCategory { CUSTOM, WEB_LOGIN, SSH_KEY, GIT, SSH_PASSWORD, PROVIDER, SERVICE }
enum class KeychainOrigin { LOCAL_GENERATED, FILE_IMPORT, CLIPBOARD_IMPORT, AGENT_GENERATED, AGENT_SAVED, SECRET_OUTPUT, MANUAL, MIGRATION }
enum class KeychainAction { FILL, COPY, REVEAL, EXPORT, SIGN, DEPLOY, AGENT_UPDATE }

/** 条目只携带目录元数据；不同的密钥分量以引用区分。 */
data class KeychainItem(
    val ref: String,
    val category: KeychainCategory,
    val name: String,
    val url: String? = null,
    val username: String? = null,
    val note: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val origin: KeychainOrigin? = null,
    val addedBySessionId: String? = null,
    val keyType: String? = null,
    val fingerprintSha256: String? = null,
    val publicKey: String? = null,
    val hasPassphrase: Boolean = false,
    val agentEnabled: Boolean = true,
    val gitSource: String? = null,
    val gitRejectedAt: Long? = null,
    val secretRefs: List<String> = emptyList(),
)

data class KeychainMetadata(
    val name: String,
    val url: String? = null,
    val username: String? = null,
    val note: String? = null,
    val agentEnabled: Boolean = true,
)

data class KeychainUsage(val entryRef: String, val action: KeychainAction, val context: String?, val at: Long)

data class KeychainHostReference(val id: String, val alias: String)
sealed interface KeychainDeleteResult {
    data object Deleted : KeychainDeleteResult
    data object NotFound : KeychainDeleteResult
    data class InUse(val hosts: List<KeychainHostReference>) : KeychainDeleteResult
}

/** 输入和返回的字节数组由调用方持有，使用结束后必须清零。调用方负责设备验证。 */
interface KeychainRepository {
    fun entries(): Flow<List<KeychainItem>>
    suspend fun reveal(ref: String): ByteArray?
    suspend fun recordUsage(entryRef: String, action: KeychainAction, context: String?, at: Long = System.currentTimeMillis())
    fun usage(entryRef: String): Flow<List<KeychainUsage>>
    suspend fun createCustom(metadata: KeychainMetadata, value: ByteArray, origin: KeychainOrigin = KeychainOrigin.MANUAL, sessionId: String? = null): KeychainItem
    suspend fun createWebLogin(metadata: KeychainMetadata, value: ByteArray, origin: KeychainOrigin = KeychainOrigin.MANUAL, sessionId: String? = null): KeychainItem
    suspend fun createSshKey(name: String, privateKeyPem: ByteArray, passphrase: ByteArray?, origin: KeychainOrigin, sessionId: String? = null): KeychainItem
    suspend fun update(ref: String, metadata: KeychainMetadata, value: ByteArray? = null, passphrase: ByteArray? = null): KeychainItem
    suspend fun delete(ref: String): KeychainDeleteResult
    suspend fun matchWebLogin(host: String, port: Int?): List<KeychainItem>
    suspend fun assignKey(hostId: String, entryId: String)
    suspend fun saveGit(host: String, username: String, token: ByteArray)
    suspend fun saveGitHubLogin(username: String, token: ByteArray)
    suspend fun deleteGit(host: String)
}
