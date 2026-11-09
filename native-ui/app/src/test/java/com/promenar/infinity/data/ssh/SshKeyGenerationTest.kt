package com.promenar.infinity.data.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.promenar.infinity.domain.keychain.SshKeyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

    /** Release 的 R8 裁掉反射加载的生成类时，生成报“生成失败”而不是笼统的写入失败。 */
    @Test fun `生成类缺失时报生成失败`() {
        SshKeyGenerator.configuredClient()
        for ((type, config) in listOf(SshKeyType.RSA_4096 to "keypairgen.rsa", SshKeyType.ED25519 to "keypairgen.eddsa")) {
            val original = JSch.getConfig(config)
            JSch.setConfig(config, "com.promenar.infinity.MissingKeyPairGen")
            try {
                val failure = assertThrows(SshFailure::class.java) { SshKeyGenerator.generate(type, null) }
                assertEquals("KEY_GENERATION_FAILED", failure.code)
            } finally { JSch.setConfig(config, original) }
        }
    }
}
