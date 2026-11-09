package com.promenar.infinity.data.keychain

import com.promenar.infinity.data.git.remote.GitCredentialStore
import com.promenar.infinity.data.local.db.dao.SshHostDao
import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import com.promenar.infinity.data.security.SecretMutationRevision
import com.promenar.infinity.data.security.SshCredential
import com.promenar.infinity.data.security.SshCredentialStore
import com.promenar.infinity.data.security.WebDavAuthRecordCodec
import com.promenar.infinity.data.ssh.RoomSshHostStore
import com.promenar.infinity.data.ssh.SshAuthType
import com.promenar.infinity.data.ssh.SshHostStore
import com.promenar.infinity.data.ssh.SshKeyGenerator
import com.promenar.infinity.data.ssh.binding
import com.promenar.infinity.data.ssh.toHost
import com.promenar.infinity.domain.keychain.*
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class KeychainProvider(val id: String, val name: String, val hasApiKey: Boolean, val hasVertexCredentials: Boolean)

class DefaultKeychainRepository(
    private val dao: KeychainDao,
    private val hostDao: SshHostDao,
    private val hosts: SshHostStore,
    private val secrets: SecretStore,
    private val git: GitCredentialStore,
    private val providers: Flow<List<KeychainProvider>>,
    private val clock: () -> Long = System::currentTimeMillis,
) : KeychainRepository {
    private val access = KeychainSecretAccess(secrets)
    private val sshCredentials = SshCredentialStore(secrets)

    override fun entries(): Flow<List<KeychainItem>> = combine(
        dao.observeEntries(), hosts.observeHosts(), git.credentials, providers, secrets.changes,
    ) { own, sshHosts, gitHosts, providerList, _ ->
        val ids = secrets.ids()
        val ownItems = own.map { it.toItem() }
        val gitItems = gitHosts.map { item ->
            KeychainItem("git:${item.host}", KeychainCategory.GIT, item.host, username = item.username,
                gitSource = item.source, gitRejectedAt = item.rejectedAtMillis)
        }
        val sshItems = sshHosts.mapNotNull { host ->
            val binding = host.binding()
            val credential = try { sshCredentials.get(host.id, binding) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                finally { binding.fill(0) }
            try {
                if (credential?.password?.isNotEmpty() == true)
                    KeychainItem("ssh-password:${host.id}", KeychainCategory.SSH_PASSWORD, host.alias, username = host.username)
                else null
            } finally { credential?.close() }
        }
        val providerItems = providerList.mapNotNull { provider ->
            val refs = buildList {
                if (provider.hasApiKey) add(secretRef(SecretCatalog.providerApiKey(provider.id)))
                if (provider.hasVertexCredentials) add(secretRef(SecretCatalog.vertexServiceAccount(provider.id)))
            }
            if (refs.isEmpty()) null else KeychainItem(refs.first(), KeychainCategory.PROVIDER, provider.name, secretRefs = refs)
        }
        val serviceItems = ids.mapNotNull { id -> serviceName(id)?.let { name ->
            KeychainItem(secretRef(id), KeychainCategory.SERVICE, name)
        } }
        (ownItems + gitItems + sshItems + providerItems + serviceItems).sortedWith(compareBy({ it.category.name }, { it.name }, { it.ref }))
    }.flowOn(Dispatchers.IO)

    override suspend fun reveal(ref: String): ByteArray? = withContext(Dispatchers.IO) {
        when {
            ref.startsWith("git:") -> git.token(ref.removePrefix("git:"))
            ref.startsWith("ssh-password:") -> {
                val host = hostDao.findById(ref.removePrefix("ssh-password:"))?.toHost() ?: return@withContext null
                val binding = host.binding()
                val credential = try { sshCredentials.get(host.id, binding) } finally { binding.fill(0) }
                try { credential?.password?.takeIf { it.isNotEmpty() }?.copyOf() } finally { credential?.close() }
            }
            ref.startsWith("secret:") -> {
                val id = SecretId(ref.removePrefix("secret:"))
                val providerIds = providers.first().map { it.id }
                require(serviceName(id) != null || providerIds.any {
                    id == SecretCatalog.providerApiKey(it) || id == SecretCatalog.vertexServiceAccount(it)
                }) { "密钥引用无效" }
                if (id == SecretCatalog.webDavAuthRecord) {
                    val bytes = secrets.get(id) ?: return@withContext null
                    try { WebDavAuthRecordCodec.decode(bytes).use { it.passwordBytes.copyOf() } } finally { bytes.fill(0) }
                } else secrets.get(id)
            }
            else -> {
                val passphrase = ref.endsWith(":passphrase")
                val id = if (passphrase) ref.removeSuffix(":passphrase") else ref
                val entry = dao.find(id) ?: return@withContext null
                if (passphrase) {
                    require(entry.category == KeychainCategory.SSH_KEY.name) { "密钥引用无效" }
                    access.revealPassphrase(id)
                } else access.reveal(id)
            }
        }
    }

    override suspend fun recordUsage(entryRef: String, action: KeychainAction, context: String?, at: Long) = withContext(Dispatchers.IO) {
        require(entryRef.isNotBlank() && entryRef.length <= 300 && entryRef.none(Char::isISOControl))
        require(context == null || (context.length <= 300 && context.none(Char::isISOControl)))
        dao.appendUsage(KeychainUsageEntity(entryRef = entryRef, action = action.name, context = context, at = at))
    }

    override fun usage(entryRef: String): Flow<List<KeychainUsage>> = dao.observeUsage(entryRef).map { rows ->
        rows.map { KeychainUsage(it.entryRef, KeychainAction.valueOf(it.action), it.context, it.at) }
    }

    override suspend fun createCustom(metadata: KeychainMetadata, value: ByteArray, origin: KeychainOrigin, sessionId: String?): KeychainItem =
        create(KeychainCategory.CUSTOM, metadata, value, origin, sessionId)

    override suspend fun createWebLogin(metadata: KeychainMetadata, value: ByteArray, origin: KeychainOrigin, sessionId: String?): KeychainItem =
        create(KeychainCategory.WEB_LOGIN, metadata, value, origin, sessionId)

    private suspend fun create(category: KeychainCategory, metadata: KeychainMetadata, value: ByteArray, origin: KeychainOrigin, sessionId: String?): KeychainItem =
        withContext(Dispatchers.IO) {
            validateOrigin(origin, sessionId)
            require(value.isNotEmpty()) { "密钥值为空" }
            val entry = newEntry(category, metadata, origin, sessionId)
            RoomSshHostStore.mutationMutex.withLock { persistNew(entry, value, null) }
            entry.toItem()
        }

    override suspend fun createSshKey(name: String, privateKeyPem: ByteArray, passphrase: ByteArray?, origin: KeychainOrigin, sessionId: String?): KeychainItem =
        withContext(Dispatchers.IO) {
            validateOrigin(origin, sessionId)
            val publicKey = SshKeyGenerator.publicKey(privateKeyPem, passphrase ?: ByteArray(0))
            val fingerprint = fingerprint(publicKey)
            val entry = newEntry(KeychainCategory.SSH_KEY, KeychainMetadata(name), origin, sessionId).copy(
                keyType = publicKey.substringBefore(' '), fingerprintSha256 = fingerprint,
                publicKey = publicKey, hasPassphrase = passphrase != null && passphrase.isNotEmpty(),
            )
            RoomSshHostStore.mutationMutex.withLock { persistNew(entry, privateKeyPem, passphrase) }
            entry.toItem()
        }

    private suspend fun persistNew(entry: KeychainEntryEntity, value: ByteArray, passphrase: ByteArray?) = SecretMutationRevision.suspending {
        withContext(NonCancellable) {
            val secretId = SecretCatalog.keychain(entry.id)
            val phraseId = SecretCatalog.keychainPassphrase(entry.id)
            try {
                secrets.put(secretId, value)
                if (passphrase != null && passphrase.isNotEmpty()) secrets.put(phraseId, passphrase)
                dao.insert(entry)
            } catch (failure: Throwable) {
                runCatching { secrets.remove(secretId) }
                runCatching { secrets.remove(phraseId) }
                throw failure
            }
        }
    }

    override suspend fun update(ref: String, metadata: KeychainMetadata, value: ByteArray?, passphrase: ByteArray?): KeychainItem = SecretMutationRevision.suspending {
        withContext(Dispatchers.IO) {
            RoomSshHostStore.mutationMutex.withLock {
                val old = dao.find(ref) ?: throw IllegalArgumentException("钥匙串条目不存在")
                val category = KeychainCategory.valueOf(old.category)
                require(value == null || value.isNotEmpty()) { "密钥值为空" }
                require(passphrase == null || category == KeychainCategory.SSH_KEY) { "仅 SSH 私钥支持口令" }
                var next = old.copy(name = validName(metadata.name), url = normalizedUrl(category, metadata.url),
                    username = validOptional(metadata.username), note = validOptional(metadata.note), updatedAt = clock(), agentEnabled = metadata.agentEnabled)
                if (category == KeychainCategory.SSH_KEY && (value != null || passphrase != null)) {
                    val privateKey = value ?: access.reveal(ref) ?: throw IllegalStateException("钥匙串私钥缺失")
                    var storedPhrase: ByteArray? = null
                    try {
                        if (passphrase == null) storedPhrase = access.revealPassphrase(ref)
                        val effectivePhrase = passphrase ?: storedPhrase ?: ByteArray(0)
                        val publicKey = SshKeyGenerator.publicKey(privateKey, effectivePhrase)
                        next = next.copy(keyType = publicKey.substringBefore(' '), fingerprintSha256 = fingerprint(publicKey),
                            publicKey = publicKey, hasPassphrase = effectivePhrase.isNotEmpty())
                    } finally {
                        if (value == null) privateKey.fill(0)
                        storedPhrase?.fill(0)
                    }
                }
                withContext(NonCancellable) {
                    val id = SecretCatalog.keychain(old.id)
                    val phraseId = SecretCatalog.keychainPassphrase(old.id)
                    var before: ByteArray? = null
                    var beforePhrase: ByteArray? = null
                    var changed = false
                    try {
                        if (value != null) before = secrets.get(id)
                        if (passphrase != null) beforePhrase = secrets.get(phraseId)
                        changed = true
                        if (value != null) secrets.put(id, value)
                        if (passphrase != null) {
                            if (passphrase.isEmpty()) secrets.remove(phraseId) else secrets.put(phraseId, passphrase)
                        }
                        check(dao.update(next) == 1) { "钥匙串条目已变化" }
                    } catch (failure: Throwable) {
                        if (changed && value != null) { if (before == null) secrets.remove(id) else secrets.put(id, before) }
                        if (changed && passphrase != null) { if (beforePhrase == null) secrets.remove(phraseId) else secrets.put(phraseId, beforePhrase) }
                        throw failure
                    } finally { before?.fill(0); beforePhrase?.fill(0) }
                }
                next.toItem()
            }
        }
    }

    override suspend fun delete(ref: String): KeychainDeleteResult = SecretMutationRevision.suspending {
        withContext(Dispatchers.IO) {
            RoomSshHostStore.mutationMutex.withLock {
                val entry = dao.find(ref) ?: return@withLock KeychainDeleteResult.NotFound
                val users = hostDao.findByKeyEntryId(entry.id)
                if (users.isNotEmpty()) return@withLock KeychainDeleteResult.InUse(users.map { KeychainHostReference(it.id, it.alias) })
                withContext(NonCancellable) {
                    val id = SecretCatalog.keychain(ref)
                    val phraseId = SecretCatalog.keychainPassphrase(ref)
                    val value = secrets.get(id)
                    var phrase: ByteArray? = null
                    try {
                        if (entry.category == KeychainCategory.SSH_KEY.name) phrase = secrets.get(phraseId)
                        check(dao.delete(ref) == 1)
                        try {
                            secrets.remove(id)
                            if (entry.category == KeychainCategory.SSH_KEY.name) secrets.remove(phraseId)
                        } catch (failure: Throwable) {
                            if (value != null) secrets.put(id, value)
                            if (phrase != null) secrets.put(phraseId, phrase)
                            dao.insert(entry)
                            throw failure
                        }
                    } finally { value?.fill(0); phrase?.fill(0) }
                }
                KeychainDeleteResult.Deleted
            }
        }
    }

    override suspend fun matchWebLogin(host: String, port: Int?): List<KeychainItem> = withContext(Dispatchers.IO) {
        val normalized = KeychainHost.normalize(host, port) ?: return@withContext emptyList()
        dao.findWebLogins(normalized).map { it.toItem() }
    }

    override suspend fun assignKey(hostId: String, entryId: String) = SecretMutationRevision.suspending {
        withContext(Dispatchers.IO) {
            RoomSshHostStore.mutationMutex.withLock {
                val entry = dao.find(entryId) ?: throw IllegalArgumentException("密钥不存在")
                require(entry.category == KeychainCategory.SSH_KEY.name && secrets.contains(SecretCatalog.keychain(entryId))) { "密钥不可用" }
                val key = access.reveal(entryId) ?: throw IllegalArgumentException("密钥不可用")
                var phrase: ByteArray? = null
                try {
                    phrase = access.revealPassphrase(entryId) ?: ByteArray(0)
                    require(fingerprint(SshKeyGenerator.publicKey(key, phrase)) == entry.fingerprintSha256) { "密钥指纹不一致" }
                } finally { key.fill(0); phrase?.fill(0) }
                val host = hostDao.findById(hostId)?.toHost() ?: throw IllegalArgumentException("SSH 主机不存在")
                require(host.authType == SshAuthType.KEY) { "SSH 主机未使用密钥认证" }
                val binding = host.binding()
                val old = try { sshCredentials.get(host.id, binding) } catch (failure: Throwable) { binding.fill(0); throw failure }
                try {
                    withContext(NonCancellable) {
                        if (old == null) SshCredential().use { sshCredentials.replace(host.id, binding, it) { } }
                        check(hostDao.assignKey(host.id, entryId, host.keyEntryId) == 1) { "SSH 主机已变化" }
                        try {
                            SshCredential(password = old?.password?.copyOf() ?: ByteArray(0)).use {
                                sshCredentials.replace(host.id, binding, it) { }
                            }
                        } catch (failure: Throwable) {
                            check(hostDao.assignKey(host.id, host.keyEntryId, entryId) == 1) { "SSH 主机回滚失败" }
                            throw failure
                        }
                    }
                } finally { old?.close(); binding.fill(0) }
            }
        }
    }

    override suspend fun saveGit(host: String, username: String, token: ByteArray) = withContext(Dispatchers.IO) { git.save(host, username, token) }
    override suspend fun saveGitHubLogin(username: String, token: ByteArray) = withContext(Dispatchers.IO) { git.saveGitHubLogin(username, token) }
    override suspend fun deleteGit(host: String) = withContext(Dispatchers.IO) { git.delete(host) }

    internal fun newEntry(category: KeychainCategory, metadata: KeychainMetadata, origin: KeychainOrigin, sessionId: String?): KeychainEntryEntity {
        val now = clock()
        return KeychainEntryEntity(UUID.randomUUID().toString(), category.name, validName(metadata.name), normalizedUrl(category, metadata.url),
            validOptional(metadata.username), validOptional(metadata.note), now, now, origin.name, validOptional(sessionId),
            null, null, null, false, metadata.agentEnabled)
    }

    private fun normalizedUrl(category: KeychainCategory, url: String?): String? = if (category == KeychainCategory.WEB_LOGIN)
        KeychainHost.fromUrl(url ?: "") ?: throw IllegalArgumentException("网站地址无效") else validOptional(url)
    private fun validName(name: String): String = name.trim().also { require(it.isNotEmpty() && it.length <= 256 && it.none(Char::isISOControl)) }
    private fun validOptional(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.also { require(it.length <= 4096 && it.none(Char::isISOControl)) }
    private fun validateOrigin(origin: KeychainOrigin, sessionId: String?) {
        if (origin == KeychainOrigin.AGENT_GENERATED || origin == KeychainOrigin.AGENT_SAVED) require(!sessionId.isNullOrBlank()) { "Agent 条目必须关联会话" }
    }

    internal suspend fun persistMigrated(entry: KeychainEntryEntity, key: ByteArray, passphrase: ByteArray?) = persistNew(entry, key, passphrase)

    companion object {
        internal fun fingerprint(publicKey: String): String {
            val blob = Base64.getDecoder().decode(publicKey.split(' ').getOrNull(1) ?: throw IllegalArgumentException("公钥无效"))
            return try { "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob)) }
            finally { blob.fill(0) }
        }
        private fun secretRef(id: SecretId) = "secret:${id.value}"
        private fun serviceName(id: SecretId): String? = when (id.value) {
            "tavily_api_key" -> "Tavily"
            "keenable_api_key" -> "Keenable"
            "embedding_api_key" -> "Embedding"
            "webdav_password", "webdav_auth_record_v1" -> "WebDAV"
            "tailnet_auth_key_v1" -> "Tailscale"
            "automatic_backup_password" -> "自动备份口令"
            else -> if (id.value.matches(Regex("mcp_bearer_v1:[0-9a-f]{64}"))) "MCP Bearer" else null
        }
    }
}

internal fun KeychainEntryEntity.toItem() = KeychainItem(id, KeychainCategory.valueOf(category), name, url, username, note,
    createdAt, updatedAt, KeychainOrigin.valueOf(origin), addedBySessionId, keyType, fingerprintSha256, publicKey, hasPassphrase, agentEnabled)
