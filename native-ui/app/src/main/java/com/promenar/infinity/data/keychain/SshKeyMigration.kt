package com.promenar.infinity.data.keychain

import com.promenar.infinity.data.local.db.dao.SshHostDao
import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.data.security.SecretStore
import com.promenar.infinity.data.security.SecretMutationRevision
import com.promenar.infinity.data.security.SshCredential
import com.promenar.infinity.data.security.SshCredentialStore
import com.promenar.infinity.data.ssh.RoomSshHostStore
import com.promenar.infinity.data.ssh.SshAuthType
import com.promenar.infinity.data.ssh.SshHostStore
import com.promenar.infinity.data.ssh.SshKeyGenerator
import com.promenar.infinity.data.ssh.binding
import com.promenar.infinity.data.ssh.toHost
import com.promenar.infinity.domain.keychain.KeychainCategory
import com.promenar.infinity.domain.keychain.KeychainMetadata
import com.promenar.infinity.domain.keychain.KeychainOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 每个主机独立迁移；只在新引用可用后清除旧私钥，重复运行可恢复中断。 */
class SshKeyMigration(
    private val hosts: SshHostStore,
    private val hostDao: SshHostDao,
    private val dao: KeychainDao,
    private val repository: DefaultKeychainRepository,
    private val secrets: SecretStore,
    private val reportFailure: (hostId: String, alias: String, kind: String) -> Unit,
) {
    suspend fun migrate() = withContext(Dispatchers.IO) {
        val ids = hosts.observeHosts().first().filter { it.authType == SshAuthType.KEY }.map { it.id }
        for (id in ids) migrateOne(id)
    }

    suspend fun migrateOne(id: String) = SecretMutationRevision.suspending {
        withContext(Dispatchers.IO) {
            try {
                RoomSshHostStore.mutationMutex.withLock { migrateHost(id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val alias = hostDao.findById(id)?.alias ?: "unknown"
                reportFailure(id, alias, failure.javaClass.simpleName)
            }
        }
    }

    private suspend fun migrateHost(id: String) {
        val host = hostDao.findById(id)?.toHost() ?: return
        if (host.authType != SshAuthType.KEY) return
        val binding = host.binding()
        val credentialStore = SshCredentialStore(secrets)
        var credential: SshCredential? = null
        try {
            credential = credentialStore.get(host.id, binding) ?: run {
                check(!secrets.contains(SecretCatalog.sshCredential(host.id))) { "SSH 凭据绑定不匹配" }
                return
            }
            if (credential.privateKey.isEmpty()) return
            val publicKey = SshKeyGenerator.publicKey(credential.privateKey, credential.passphrase)
            val fingerprint = DefaultKeychainRepository.fingerprint(publicKey)
            val assigned = host.keyEntryId?.let { dao.find(it) }
            if (assigned != null && assigned.fingerprintSha256 != fingerprint) return
            val existing = assigned ?: dao.findByFingerprint(fingerprint)
            val entry = if (existing != null && secrets.contains(SecretCatalog.keychain(existing.id))) {
                verifyExisting(existing, fingerprint)
                existing
            } else {
                repository.newEntry(KeychainCategory.SSH_KEY, KeychainMetadata(host.alias), KeychainOrigin.MIGRATION, null).copy(
                    keyType = publicKey.substringBefore(' '), fingerprintSha256 = fingerprint, publicKey = publicKey,
                    hasPassphrase = credential.passphrase.isNotEmpty(),
                ).also { repository.persistMigrated(it, credential.privateKey, credential.passphrase) }
            }
            withContext(NonCancellable) {
                if (host.keyEntryId != entry.id) {
                    check(hostDao.assignKey(host.id, entry.id, host.keyEntryId) == 1) { "SSH 主机已变化" }
                }
                try {
                    SshCredential(password = credential.password.copyOf()).use { retained ->
                        credentialStore.replace(host.id, binding, retained) { }
                    }
                } catch (failure: Throwable) {
                    if (host.keyEntryId != entry.id) {
                        check(hostDao.assignKey(host.id, host.keyEntryId, entry.id) == 1) { "SSH 主机回滚失败" }
                    }
                    throw failure
                }
            }
        } finally {
            credential?.close()
            binding.fill(0)
        }
    }

    private fun verifyExisting(entry: KeychainEntryEntity, fingerprint: String) {
        val access = KeychainSecretAccess(secrets)
        val key = access.reveal(entry.id) ?: throw IllegalStateException("钥匙串私钥缺失")
        var phrase: ByteArray? = null
        try {
            phrase = access.revealPassphrase(entry.id) ?: ByteArray(0)
            check(DefaultKeychainRepository.fingerprint(SshKeyGenerator.publicKey(key, phrase)) == fingerprint) { "钥匙串私钥与指纹不一致" }
        } finally { key.fill(0); phrase?.fill(0) }
    }
}
