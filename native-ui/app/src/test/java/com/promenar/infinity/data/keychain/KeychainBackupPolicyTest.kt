package com.promenar.infinity.data.keychain

import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.data.security.SecretId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeychainBackupPolicyTest {
    @Test fun `允许密钥目录和既有凭据标识并排除自动备份口令`() {
        val hash = "a".repeat(64)
        val valid = listOf("keychain_v1:abc-123", "keychain_v1:abc-123:passphrase", "ssh_credential_v1:$hash",
            "git_credential_index_v1", "git_credential_token_v1:$hash", "tailnet_auth_key_v1", "mcp_bearer_v1:$hash", "webdav_password")
        valid.forEach { assertTrue(it, SecretCatalog.isBackupEligible(SecretId(it), emptySet())) }
        assertFalse(SecretCatalog.isBackupEligible(SecretCatalog.automaticBackupPassword, emptySet()))
        assertFalse(SecretCatalog.isBackupEligible(SecretId("ssh_credential_v1:bad"), emptySet()))
        assertFalse(SecretCatalog.isBackupEligible(SecretId("backup:transaction"), emptySet()))
    }
}
