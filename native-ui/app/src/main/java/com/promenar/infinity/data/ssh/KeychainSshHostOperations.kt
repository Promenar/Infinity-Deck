package com.promenar.infinity.data.ssh

import com.promenar.infinity.data.keychain.DefaultKeychainRepository
import com.promenar.infinity.data.keychain.KeychainDao
import com.promenar.infinity.data.keychain.KeychainSecretAccess
import com.promenar.infinity.data.keychain.KeychainUsageEntity
import com.promenar.infinity.data.local.db.dao.SshHostDao
import com.promenar.infinity.data.security.*
import com.promenar.infinity.domain.keychain.*
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class SshKeyDeploymentSnapshot(val host: SshHost, val credentialVersion: ByteArray?) : AutoCloseable {
    override fun close() { credentialVersion?.fill(0) }
    override fun toString() = "SshKeyDeploymentSnapshot([已隐藏])"
}
internal class SshDeploymentKey(val fingerprint: String, val publicKey: String, val credential: SshCredential) : AutoCloseable {
    override fun close() = credential.close()
    override fun toString() = "SshDeploymentKey([已隐藏])"
}

internal class KeychainSshHostOperations(private val dao: SshHostDao, private val secrets: SecretStore, private val keys: KeychainDao?) {
    private val credentials = SshCredentialStore(secrets)

    suspend fun save(host: SshHost, entryId: String, password: ByteArray?): SshHost = withContext(Dispatchers.IO) {
        SecretMutationRevision.suspending {
            RoomSshHostStore.mutationMutex.withLock {
                readKey(entryId).use {
                    val previous = dao.findById(host.id)?.toHost()
                    val sameEndpoint = previous != null && previous.hostname == host.hostname && previous.port == host.port && previous.username == host.username
                    val next = host.copy(authType = SshAuthType.KEY, keyEntryId = entryId,
                        trustedKeyType = if (sameEndpoint) previous?.trustedKeyType else null,
                        trustedFingerprint = if (sameEndpoint) previous?.trustedFingerprint else null)
                    val old = previous?.let(::storedCredential)
                    try {
                        SshCredential(password = password?.copyOf() ?: old?.password?.copyOf() ?: ByteArray(0)).use { replacement ->
                            write(next, previous, replacement)
                        }
                    } finally { old?.close() }
                    next
                }
            }
        }
    }

    suspend fun snapshot(hostId: String): SshKeyDeploymentSnapshot = withContext(Dispatchers.IO) {
        RoomSshHostStore.mutationMutex.withLock {
            val host = dao.findById(hostId)?.toHost() ?: throw SshFailure("HOST_NOT_FOUND")
            SshKeyDeploymentSnapshot(host, credentialVersion(host.id))
        }
    }

    suspend fun key(entryId: String): SshDeploymentKey = withContext(Dispatchers.IO) {
        RoomSshHostStore.mutationMutex.withLock { readKey(entryId) }
    }

    suspend fun passwordCredential(host: SshHost): SshCredential = withContext(Dispatchers.IO) {
        RoomSshHostStore.mutationMutex.withLock {
            if (dao.findById(host.id)?.toHost() != host) throw SshFailure("HOST_CONFIGURATION_CHANGED")
            val stored = storedCredential(host) ?: throw SshFailure("CREDENTIAL_MISSING")
            stored.use {
                if (it.password.isEmpty()) throw SshFailure("CREDENTIAL_MISSING")
                SshCredential(password = it.password.copyOf())
            }
        }
    }

    suspend fun commit(snapshot: SshKeyDeploymentSnapshot, entryId: String, fingerprint: String, pinned: SshHostKey, options: DeployOptions) =
        withContext(Dispatchers.IO) {
            SecretMutationRevision.suspending {
                RoomSshHostStore.mutationMutex.withLock {
                    val previous = snapshot.host
                    if (dao.findById(previous.id)?.toHost() != previous) throw SshFailure("HOST_CONFIGURATION_CHANGED")
                    val version = credentialVersion(previous.id)
                    try {
                        val same = if (version == null || snapshot.credentialVersion == null) version == null && snapshot.credentialVersion == null
                            else MessageDigest.isEqual(version, snapshot.credentialVersion)
                        if (!same) throw SshFailure("HOST_CONFIGURATION_CHANGED")
                    } finally { version?.fill(0) }
                    readKey(entryId).use { current ->
                        if (current.fingerprint != fingerprint) throw SshFailure("KEY_FINGERPRINT_MISMATCH")
                        if (previous.trustedFingerprint != null && previous.trustedKeyType != null &&
                            (previous.trustedFingerprint != pinned.fingerprint || previous.trustedKeyType != pinned.type)) throw SshFailure("HOST_KEY_CHANGED")
                        val next = previous.copy(authType = if (options.switchToKey) SshAuthType.KEY else previous.authType,
                            keyEntryId = if (options.switchToKey) entryId else previous.keyEntryId,
                            trustedFingerprint = pinned.fingerprint, trustedKeyType = pinned.type)
                        val old = storedCredential(previous)
                        try {
                            SshCredential(
                                privateKey = if (options.switchToKey) ByteArray(0) else old?.privateKey?.copyOf() ?: ByteArray(0),
                                password = if (options.deleteStoredPassword) ByteArray(0) else old?.password?.copyOf() ?: ByteArray(0),
                                passphrase = if (options.switchToKey) ByteArray(0) else old?.passphrase?.copyOf() ?: ByteArray(0),
                            ).use { replacement ->
                                write(next, previous, replacement, KeychainUsageEntity(entryRef = entryId, action = KeychainAction.DEPLOY.name,
                                    context = previous.id, at = System.currentTimeMillis()))
                            }
                        } finally { old?.close() }
                    }
                }
            }
        }

    private suspend fun readKey(entryId: String): SshDeploymentKey {
        val entry = (keys ?: throw SshFailure("KEYCHAIN_UNAVAILABLE")).find(entryId) ?: throw SshFailure("KEYCHAIN_ENTRY_NOT_FOUND")
        if (entry.category != KeychainCategory.SSH_KEY.name) throw SshFailure("NOT_SSH_KEY")
        val access = KeychainSecretAccess(secrets)
        val value = access.reveal(entryId) ?: throw SshFailure("KEYCHAIN_VALUE_MISSING")
        var phrase: ByteArray? = null
        try {
            phrase = access.revealPassphrase(entryId) ?: ByteArray(0)
            val publicKey = SshKeyGenerator.publicKey(value, phrase)
            val fingerprint = DefaultKeychainRepository.fingerprint(publicKey)
            if (fingerprint != entry.fingerprintSha256) throw SshFailure("KEY_FINGERPRINT_MISMATCH")
            return SshDeploymentKey(fingerprint, publicKey, SshCredential(privateKey = value, passphrase = phrase))
        } catch (failure: Throwable) { value.fill(0); phrase?.fill(0); throw failure }
    }

    private fun storedCredential(host: SshHost): SshCredential? {
        val binding = host.binding()
        return try { credentials.get(host.id, binding) } finally { binding.fill(0) }
    }

    private fun credentialVersion(hostId: String): ByteArray? {
        val raw = secrets.get(SecretCatalog.sshCredential(hostId)) ?: return null
        return try { MessageDigest.getInstance("SHA-256").digest(raw) } finally { raw.fill(0) }
    }

    private suspend fun write(next: SshHost, previous: SshHost?, replacement: SshCredential, usage: KeychainUsageEntity? = null) {
        currentCoroutineContext().ensureActive()
        val binding = next.binding()
        try {
            withContext(NonCancellable) {
                try {
                    credentials.replace(next.id, binding, replacement) {
                        dao.saveKeychainSnapshot(next.toEntity(), previous?.toEntity(), keys, usage)
                    }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { throw SshFailure("HOST_SAVE_FAILED") }
            }
        } finally { binding.fill(0) }
    }
}
