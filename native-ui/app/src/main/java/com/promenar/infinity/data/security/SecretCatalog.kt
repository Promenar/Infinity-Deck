package com.promenar.infinity.data.security

import java.security.MessageDigest

object SecretCatalog {
    fun providerApiKey(providerId: String): SecretId =
        SecretId("provider_api_key:${providerHash(providerId)}")

    fun vertexServiceAccount(providerId: String): SecretId =
        SecretId("vertex_service_account:${providerHash(providerId)}")

    val tavilyApiKey = SecretId("tavily_api_key")
    val keenableApiKey = SecretId("keenable_api_key")
    val embeddingApiKey = SecretId("embedding_api_key")
    val webDavPassword = SecretId("webdav_password")
    val webDavAuthRecord = SecretId("webdav_auth_record_v1")
    val automaticBackupPassword = SecretId("automatic_backup_password")

    fun sshCredential(hostId: String): SecretId = SecretId("ssh_credential_v1:${providerHash(hostId)}")
    fun keychain(id: String): SecretId = SecretId("keychain_v1:$id")
    fun keychainPassphrase(id: String): SecretId = SecretId("keychain_v1:$id:passphrase")

    fun backupEligible(providerIds: Collection<String>): Set<SecretId> = buildSet {
        add(tavilyApiKey)
        add(keenableApiKey)
        add(embeddingApiKey)
        add(webDavAuthRecord)
        add(webDavPassword)
        add(automaticBackupPassword)
        add(SecretId("tailnet_auth_key_v1"))
        add(SecretId("git_credential_index_v1"))
        providerIds.forEach { providerId ->
            add(providerApiKey(providerId))
            add(vertexServiceAccount(providerId))
        }
    }

    fun isBackupEligible(id: SecretId, providerIds: Collection<String>): Boolean {
        if (id == automaticBackupPassword) return false
        if (id in backupEligible(providerIds)) return true
        return addedInBackupV19(id)
    }

    fun addedInBackupV19(id: SecretId): Boolean =
        id == webDavPassword || id.value in setOf("tailnet_auth_key_v1", "git_credential_index_v1") || dynamicBackupId.matches(id.value)

    private val dynamicBackupId = Regex(
        "(?:keychain_v1:[A-Za-z0-9_-]{1,128}(?::passphrase)?|(?:ssh_credential_v1|git_credential_token_v1|mcp_bearer_v1):[0-9a-f]{64})",
    )

    private fun providerHash(providerId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(providerId.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
