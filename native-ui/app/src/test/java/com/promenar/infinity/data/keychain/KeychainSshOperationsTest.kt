package com.promenar.infinity.data.keychain

import com.jcraft.jsch.KeyPair
import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.data.ssh.*
import com.promenar.infinity.domain.keychain.*
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.bouncycastle.asn1.*
import org.junit.Assert.*
import org.junit.Test

class KeychainSshOperationsTest {
    @Test fun `生成条目按算法与口令保存并仅返回元数据`() = runBlocking {
        val f = K2MemoryFixture()
        for (type in SshKeyType.entries) for (phrase in listOf(null, "fixture phrase".toByteArray())) {
            val item = f.repository.generateSshKey("生成", type, phrase)
            val bytes = f.repository.reveal(item.ref)!!
            val key = KeyPair.load(SshKeyGenerator.configuredClient(), bytes, null)
            try {
                assertEquals(phrase != null, key.isEncrypted)
                if (phrase != null) { assertFalse(key.decrypt(ByteArray(0))); assertTrue(key.decrypt(phrase)) }
                assertEquals(if (type == SshKeyType.RSA_4096) KeyPair.RSA else KeyPair.ED25519, key.keyType)
                assertEquals(item.fingerprintSha256, DefaultKeychainRepository.fingerprint(SshKeyGenerator.publicKey(bytes, phrase ?: ByteArray(0))))
                assertEquals(KeychainOrigin.LOCAL_GENERATED, item.origin)
                assertFalse(item.toString().contains("PRIVATE KEY"))
            } finally { bytes.fill(0); key.dispose() }
        }
    }

    @Test fun `导入两种格式与三种算法并按指纹去重`() = runBlocking {
        for (type in listOf(KeyPair.ED25519, KeyPair.ECDSA, KeyPair.RSA)) {
            val f = K2MemoryFixture()
            val key = KeyPair.genKeyPair(SshKeyGenerator.configuredClient(), type, if (type == KeyPair.RSA) 2048 else 256)
            try {
                val open = ByteArrayOutputStream().also { key.writeOpenSSHv1PrivateKey(it, null as ByteArray?) }.toByteArray()
                val pem = if (type == KeyPair.ED25519) {
                    val pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                    "-----BEGIN PRIVATE KEY-----\n${Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(pair.private.encoded)}\n-----END PRIVATE KEY-----\n".toByteArray()
                } else ByteArrayOutputStream().also { key.writePrivateKey(it) }.toByteArray()
                try {
                    val imported = f.repository.importSshKey("导入", open, null, KeychainOrigin.FILE_IMPORT) as ImportSshKeyResult.Imported
                    assertTrue(f.repository.importSshKey("重复", open, null, KeychainOrigin.CLIPBOARD_IMPORT) is ImportSshKeyResult.AlreadyExists)
                    assertArrayEquals(open, f.repository.reveal(imported.item.ref))
                    assertEquals(1, f.keys.rows.value.size)
                    val pemResult = f.repository.importSshKey("PEM", pem, null, KeychainOrigin.CLIPBOARD_IMPORT)
                    val item = when (pemResult) { is ImportSshKeyResult.Imported -> pemResult.item; is ImportSshKeyResult.AlreadyExists -> pemResult.item; else -> error("PEM 导入失败：$pemResult") }
                    assertTrue(f.repository.reveal(item.ref)!!.toString(Charsets.US_ASCII).startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
                } finally { open.fill(0); pem.fill(0) }
            } finally { key.dispose() }
        }
    }

    @Test fun `导入区分口令与格式并拒绝弱密钥和不支持算法`() = runBlocking {
        val f = K2MemoryFixture()
        val phrase = "fixture phrase".toByteArray()
        SshKeyGenerator.generate(SshKeyType.ED25519, phrase).use { generated ->
            assertEquals(ImportSshKeyResult.PassphraseRequired, f.repository.importSshKey("加密", generated.privateKey, null, KeychainOrigin.FILE_IMPORT))
            assertEquals(ImportSshKeyResult.WrongPassphrase, f.repository.importSshKey("加密", generated.privateKey, "wrong".toByteArray(), KeychainOrigin.FILE_IMPORT))
            assertTrue(f.repository.importSshKey("加密", generated.privateKey, phrase, KeychainOrigin.FILE_IMPORT) is ImportSshKeyResult.Imported)
        }
        assertTrue(f.repository.importSshKey("坏格式", "not a key".toByteArray(), null, KeychainOrigin.FILE_IMPORT) is ImportSshKeyResult.InvalidFormat)
        for (type in listOf(KeyPair.RSA, KeyPair.DSA)) {
            val key = KeyPair.genKeyPair(SshKeyGenerator.configuredClient(), type, 1024)
            try {
                val bytes = ByteArrayOutputStream().also { key.writePrivateKey(it) }.toByteArray()
                assertEquals(ImportSshKeyResult.UnsupportedKeyType, f.repository.importSshKey("拒绝", bytes, null, KeychainOrigin.FILE_IMPORT))
                bytes.fill(0)
            } finally { key.dispose() }
        }
    }

    @Test fun `加密 PEM 转换后仍需原口令且公钥一致`() = runBlocking {
        val f = K2MemoryFixture()
        val key = KeyPair.genKeyPair(SshKeyGenerator.configuredClient(), KeyPair.RSA, 2048)
        val phrase = "pem fixture phrase".toByteArray()
        try {
            val pem = ByteArrayOutputStream().also { key.writePrivateKey(it, phrase) }.toByteArray()
            val result = f.repository.importSshKey("PEM", pem, phrase, KeychainOrigin.FILE_IMPORT) as ImportSshKeyResult.Imported
            val stored = f.repository.reveal(result.item.ref)!!
            val loaded = KeyPair.load(SshKeyGenerator.configuredClient(), stored, null)
            try {
                assertTrue(loaded.isEncrypted); assertFalse(loaded.decrypt(ByteArray(0))); assertTrue(loaded.decrypt(phrase))
                assertArrayEquals(key.publicKeyBlob, loaded.publicKeyBlob)
            } finally { loaded.dispose(); stored.fill(0); pem.fill(0) }
        } finally { key.dispose() }
    }

}

class KeychainSshExportTest {
    @Test fun `导出始终加密不修改原值并记录使用`() = runBlocking {
        val f = K2MemoryFixture()
        val plain = f.repository.generateSshKey("无口令")
        val before = f.repository.reveal(plain.ref)!!
        assertTrue(runCatching { f.repository.exportOpenSsh(plain.ref) }.isFailure)
        assertTrue(runCatching { f.repository.exportOpenSsh(plain.ref, "short".toByteArray()) }.isFailure)
        val phrase = "export fixture".toByteArray()
        val exported = f.repository.exportOpenSsh(plain.ref, phrase)
        val key = KeyPair.load(SshKeyGenerator.configuredClient(), exported, null)
        try {
            assertTrue(key.isEncrypted); assertFalse(key.decrypt(ByteArray(0))); assertTrue(key.decrypt(phrase))
            assertEquals(plain.fingerprintSha256, DefaultKeychainRepository.fingerprint(SshKeyGenerator.publicKey(exported, phrase)))
            assertArrayEquals(before, f.repository.reveal(plain.ref))
            assertEquals(KeychainAction.EXPORT, f.repository.usage(plain.ref).first().single().action)
        } finally { key.dispose(); exported.fill(0); before.fill(0) }
        val protected = f.repository.generateSshKey("有口令", passphrase = phrase)
        assertArrayEquals(f.repository.reveal(protected.ref), f.repository.exportOpenSsh(protected.ref))
    }

    @Test fun `并发导入同一密钥只创建一个条目`() = runBlocking {
        val f = K2MemoryFixture()
        SshKeyGenerator.generate().use { key ->
            val results = coroutineScope { List(8) { async(kotlinx.coroutines.Dispatchers.Default) {
                f.repository.importSshKey("并发", key.privateKey, null, KeychainOrigin.FILE_IMPORT)
            } }.map { it.await() } }
            assertEquals(1, results.count { it is ImportSshKeyResult.Imported })
            assertEquals(7, results.count { it is ImportSshKeyResult.AlreadyExists })
            assertEquals(1, f.keys.rows.value.size)
        }
    }

    @Test fun `加密 PKCS8 可导入且限制派生成本`() = runBlocking {
        val f = K2MemoryFixture()
        val phrase = "pkcs8 fixture phrase".toByteArray()
        val private = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair().private.encoded
        val salt = ByteArray(16) { 1 }; val iv = ByteArray(16) { 2 }
        val chars = phrase.toString(Charsets.UTF_8).toCharArray()
        val spec = javax.crypto.spec.PBEKeySpec(chars, salt, 2048, 256)
        val derived = try { javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { chars.fill('\u0000'); spec.clearPassword() }
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(derived, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(private)
        fun sequence(vararg values: ASN1Encodable) = DERSequence(values)
        fun pem(rounds: Long): ByteArray {
            val kdf = sequence(ASN1ObjectIdentifier("1.2.840.113549.1.5.12"), sequence(DEROctetString(salt), ASN1Integer(rounds),
                sequence(ASN1ObjectIdentifier("1.2.840.113549.2.9"), DERNull.INSTANCE)))
            val encryption = sequence(ASN1ObjectIdentifier("2.16.840.1.101.3.4.1.42"), DEROctetString(iv))
            val der = sequence(sequence(ASN1ObjectIdentifier("1.2.840.113549.1.5.13"), sequence(kdf, encryption)), DEROctetString(ciphertext)).encoded
            return "-----BEGIN ENCRYPTED PRIVATE KEY-----\n${Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(der)}\n-----END ENCRYPTED PRIVATE KEY-----\n".toByteArray()
        }
        val valid = pem(2048)
        try {
            assertEquals(ImportSshKeyResult.PassphraseRequired, f.repository.importSshKey("PKCS8", valid, null, KeychainOrigin.FILE_IMPORT))
            assertEquals(ImportSshKeyResult.WrongPassphrase, f.repository.importSshKey("PKCS8", valid, "wrong".toByteArray(), KeychainOrigin.FILE_IMPORT))
            val result = f.repository.importSshKey("PKCS8", valid, phrase, KeychainOrigin.FILE_IMPORT) as ImportSshKeyResult.Imported
            assertTrue(result.item.hasPassphrase)
            val expensive = pem(1_000_001)
            try { assertEquals(ImportSshKeyResult.InvalidFormat("KEY_KDF_LIMIT"), f.repository.importSshKey("限制", expensive, phrase, KeychainOrigin.FILE_IMPORT)) }
            finally { expensive.fill(0) }
        } finally { valid.fill(0); private.fill(0); derived.fill(0); ciphertext.fill(0) }
    }

}

class KeychainSshPassphraseTest {
    @Test fun `修改与去掉口令保留指纹主机引用且写入失败回滚`() = runBlocking {
        val f = K2MemoryFixture()
        val old = "old fixture phrase".toByteArray(); val new = "new fixture phrase".toByteArray()
        val item = f.repository.generateSshKey("改口令", passphrase = old)
        val host = SshHost(alias = "host", hostname = "host.test", username = "u", keyEntryId = item.ref)
        f.hosts.save(host, com.promenar.infinity.data.security.SshCredential())
        f.repository.changePassphrase(item.ref, new)
        val after = f.repository.reveal(item.ref)!!
        val loaded = KeyPair.load(SshKeyGenerator.configuredClient(), after, null)
        try { assertFalse(loaded.decrypt(old)); assertTrue(loaded.decrypt(new)) } finally { loaded.dispose() }
        assertEquals(item.fingerprintSha256, f.keys.find(item.ref)!!.fingerprintSha256)
        assertEquals(item.ref, f.hosts.find(host.alias)!!.keyEntryId)
        val beforeEntry = f.keys.find(item.ref)
        for (failure in listOf("phrase", "key", "metadata")) {
            f.keys.failUpdate = failure == "metadata"
            f.secrets.failNextWrite = when (failure) { "phrase" -> SecretCatalog.keychainPassphrase(item.ref); "key" -> SecretCatalog.keychain(item.ref); else -> null }
            assertTrue(runCatching { f.repository.changePassphrase(item.ref, old) }.isFailure)
            assertArrayEquals(after, f.repository.reveal(item.ref))
            assertArrayEquals(new, f.repository.reveal("${item.ref}:passphrase"))
            assertEquals(beforeEntry, f.keys.find(item.ref))
            f.keys.failUpdate = false
        }
        val changed = f.repository.changePassphrase(item.ref, null)
        assertFalse(changed.hasPassphrase)
        assertNull(f.repository.reveal("${item.ref}:passphrase"))
        assertEquals(item.fingerprintSha256, changed.fingerprintSha256)
        after.fill(0)
    }
}
