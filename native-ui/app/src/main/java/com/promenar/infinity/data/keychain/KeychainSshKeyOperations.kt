package com.promenar.infinity.data.keychain

import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.data.security.SecretMutationRevision
import com.promenar.infinity.data.security.SecretStore
import com.promenar.infinity.data.ssh.RoomSshHostStore
import com.promenar.infinity.data.ssh.SshFailure
import com.promenar.infinity.data.ssh.SshKeyGenerator
import com.promenar.infinity.data.ssh.SshPrivateKeyCodec
import com.promenar.infinity.domain.keychain.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class KeychainSshKeyOperations(
    private val dao: KeychainDao,
    private val secrets: SecretStore,
    private val clock: () -> Long,
) {
    suspend fun generate(name: String, type: SshKeyType, passphrase: ByteArray?): KeychainItem = withContext(Dispatchers.IO) {
        SshKeyGenerator.generate(type, passphrase).use { generated ->
            RoomSshHostStore.mutationMutex.withLock {
                val entry = entry(name, generated.publicKey, (passphrase != null && passphrase.isNotEmpty()), KeychainOrigin.LOCAL_GENERATED)
                persist(entry, generated.privateKey, passphrase)
                entry.toItem()
            }
        }
    }

    suspend fun import(name: String, privateKey: ByteArray, passphrase: ByteArray?, origin: KeychainOrigin): ImportSshKeyResult = withContext(Dispatchers.IO) {
        require(origin == KeychainOrigin.FILE_IMPORT || origin == KeychainOrigin.CLIPBOARD_IMPORT) { "导入来源无效" }
        val normalized = try { SshPrivateKeyCodec.normalizeImport(privateKey, passphrase) }
        catch (failure: SshFailure) {
            return@withContext when (failure.code) {
                "KEY_PASSPHRASE_REQUIRED" -> ImportSshKeyResult.PassphraseRequired
                "INVALID_KEY_PASSPHRASE" -> ImportSshKeyResult.WrongPassphrase
                "UNSUPPORTED_USER_KEY" -> ImportSshKeyResult.UnsupportedKeyType
                else -> ImportSshKeyResult.InvalidFormat(failure.code)
            }
        }
        normalized.use {
            RoomSshHostStore.mutationMutex.withLock {
                val fingerprint = DefaultKeychainRepository.fingerprint(it.publicKey)
                dao.findByFingerprint(fingerprint)?.let { previous -> return@withLock ImportSshKeyResult.AlreadyExists(previous.toItem()) }
                val entry = entry(name, it.publicKey, it.encrypted, origin)
                persist(entry, it.bytes, passphrase?.takeIf { _ -> it.encrypted })
                ImportSshKeyResult.Imported(entry.toItem())
            }
        }
    }

    suspend fun export(entryId: String, exportPassphrase: ByteArray?): ByteArray = withContext(Dispatchers.IO) {
        RoomSshHostStore.mutationMutex.withLock {
            val entry = requireEntry(entryId)
            val value = access.reveal(entryId) ?: throw SshFailure("KEYCHAIN_VALUE_MISSING")
            var phrase: ByteArray? = null
            var output: ByteArray? = null
            try {
                phrase = access.revealPassphrase(entryId)
                validateFingerprint(entry, value, phrase)
                val encrypted = SshPrivateKeyCodec.isEncrypted(value, phrase)
                if (exportPassphrase != null && exportPassphrase.size < 8) throw SshFailure("EXPORT_PASSPHRASE_REQUIRED")
                if (exportPassphrase == null && (!encrypted || (phrase == null || phrase.isEmpty()))) throw SshFailure("EXPORT_PASSPHRASE_REQUIRED")
                output = if (exportPassphrase == null) value.copyOf() else SshPrivateKeyCodec.rewrite(value, phrase, exportPassphrase)
                dao.appendUsage(KeychainUsageEntity(entryRef = entryId, action = KeychainAction.EXPORT.name, context = null, at = clock()))
                output.also { output = null }
            } finally { value.fill(0); phrase?.fill(0); output?.fill(0) }
        }
    }

    suspend fun changePassphrase(entryId: String, newPassphrase: ByteArray?): KeychainItem = withContext(Dispatchers.IO) {
        RoomSshHostStore.mutationMutex.withLock {
            val old = requireEntry(entryId)
            val value = access.reveal(entryId) ?: throw SshFailure("KEYCHAIN_VALUE_MISSING")
            var phrase: ByteArray? = null
            var replacement: ByteArray? = null
            try {
                phrase = access.revealPassphrase(entryId)
                validateFingerprint(old, value, phrase)
                replacement = SshPrivateKeyCodec.rewrite(value, phrase, newPassphrase)
                validateFingerprint(old, replacement, newPassphrase)
                val next = old.copy(hasPassphrase = newPassphrase != null && newPassphrase.isNotEmpty(), updatedAt = clock())
                SecretMutationRevision.suspending {
                    withContext(NonCancellable) {
                        try {
                            secrets.put(SecretCatalog.keychain(entryId), replacement)
                            if (newPassphrase == null || newPassphrase.isEmpty()) secrets.remove(SecretCatalog.keychainPassphrase(entryId))
                            else secrets.put(SecretCatalog.keychainPassphrase(entryId), newPassphrase)
                            check(dao.update(next) == 1)
                        } catch (failure: Exception) {
                            try {
                                secrets.put(SecretCatalog.keychain(entryId), value)
                                if (phrase == null) secrets.remove(SecretCatalog.keychainPassphrase(entryId))
                                else secrets.put(SecretCatalog.keychainPassphrase(entryId), phrase)
                            } catch (_: Exception) { throw SshFailure("KEYCHAIN_ROLLBACK_FAILED") }
                            if (failure is CancellationException) throw failure
                            throw SshFailure("KEYCHAIN_WRITE_FAILED")
                        }
                    }
                }
                next.toItem()
            } finally { value.fill(0); phrase?.fill(0); replacement?.fill(0) }
        }
    }

    private val access = KeychainSecretAccess(secrets)
    private suspend fun requireEntry(id: String) = dao.find(id)?.also {
        if (it.category != KeychainCategory.SSH_KEY.name) throw SshFailure("NOT_SSH_KEY")
    } ?: throw SshFailure("KEYCHAIN_ENTRY_NOT_FOUND")

    private fun validateFingerprint(entry: KeychainEntryEntity, value: ByteArray, phrase: ByteArray?) {
        if (DefaultKeychainRepository.fingerprint(SshKeyGenerator.publicKey(value, phrase ?: ByteArray(0))) != entry.fingerprintSha256) {
            throw SshFailure("KEY_FINGERPRINT_MISMATCH")
        }
    }

    private fun entry(name: String, publicKey: String, encrypted: Boolean, origin: KeychainOrigin): KeychainEntryEntity {
        val valid = name.trim().also { require(it.isNotEmpty() && it.length <= 256 && it.none(Char::isISOControl)) }
        val now = clock()
        return KeychainEntryEntity(UUID.randomUUID().toString(), KeychainCategory.SSH_KEY.name, valid, null, null, null, now, now,
            origin.name, null, publicKey.substringBefore(' '), DefaultKeychainRepository.fingerprint(publicKey), publicKey, encrypted)
    }

    private suspend fun persist(entry: KeychainEntryEntity, value: ByteArray, phrase: ByteArray?) = SecretMutationRevision.suspending {
        withContext(NonCancellable) {
            try {
                secrets.put(SecretCatalog.keychain(entry.id), value)
                if (phrase != null && phrase.isNotEmpty()) secrets.put(SecretCatalog.keychainPassphrase(entry.id), phrase)
                dao.insert(entry)
            } catch (failure: Exception) {
                try { secrets.remove(SecretCatalog.keychain(entry.id)); secrets.remove(SecretCatalog.keychainPassphrase(entry.id)) }
                catch (_: Exception) { throw SshFailure("KEYCHAIN_ROLLBACK_FAILED") }
                if (failure is CancellationException) throw failure
                throw SshFailure("KEYCHAIN_WRITE_FAILED")
            }
        }
    }
}
