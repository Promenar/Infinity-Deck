package com.promenar.infinity.data.keychain

import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.data.security.SecretStore

/** 读取后由调用方持有并清零。 */
internal class KeychainSecretAccess(private val secrets: SecretStore) {
    fun reveal(entryId: String): ByteArray? = secrets.get(SecretCatalog.keychain(entryId))
    fun revealPassphrase(entryId: String): ByteArray? = secrets.get(SecretCatalog.keychainPassphrase(entryId))
}
