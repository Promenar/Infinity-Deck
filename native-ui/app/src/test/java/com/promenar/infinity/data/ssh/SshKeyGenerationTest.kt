package com.promenar.infinity.data.ssh

import com.jcraft.jsch.KeyPair
import com.promenar.infinity.domain.keychain.SshKeyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeyGenerationTest {
    @Test fun `两种算法与两种口令状态均能读回`() {
        for (type in SshKeyType.entries) {
            for (protected in listOf(false, true)) {
                val phrase = if (protected) "temporary test phrase".toByteArray() else null
                SshKeyGenerator.generate(type, phrase).use { generated ->
                    val loaded = KeyPair.load(SshKeyGenerator.configuredClient(), generated.privateKey, null)
                    try {
                        assertEquals(protected, loaded.isEncrypted)
                        if (protected) {
                            assertFalse(loaded.decrypt(ByteArray(0)))
                            assertTrue(loaded.decrypt(phrase))
                        }
                        assertEquals(if (type == SshKeyType.ED25519) KeyPair.ED25519 else KeyPair.RSA, loaded.keyType)
                        if (type == SshKeyType.RSA_4096) assertEquals(4096, loaded.keySize)
                        assertEquals(generated.publicKey, SshKeyGenerator.publicKey(generated.privateKey, phrase ?: ByteArray(0)))
                    } finally { loaded.dispose() }
                }
            }
        }
    }
}
