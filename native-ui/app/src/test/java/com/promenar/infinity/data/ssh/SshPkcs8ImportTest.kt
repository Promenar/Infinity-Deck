package com.promenar.infinity.data.ssh

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jcraft.jsch.KeyPair
import com.promenar.infinity.data.keychain.DefaultKeychainRepository
import java.math.BigInteger
import java.util.Base64
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.sec.ECPrivateKey
import org.bouncycastle.asn1.sec.SECObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** PKCS8 私钥导入：EC 私钥经 BouncyCastle 解密与转换后，指纹须与 ssh-keygen 一致。 */
class SshPkcs8ImportTest {
    private val phrase = "12345678".toByteArray()

    /** 期望指纹来自 `ssh-keygen -l -E sha256`，夹具说明见 test/resources/ssh-pkcs8/README.md。 */
    private val sshKeygen = mapOf(
        256 to "SHA256:8yvSV1RnPOyEKOpL3dupPmGp5FZ5ch8ut9Rzzlng5IA",
        384 to "SHA256:4M++sW/zcxkz+NZPnbHIIzEcsRjZzN2YBIrjxZEk8Jo",
        521 to "SHA256:UgWFLvFRGnljMopUImAlsPZjBbLK37QN3OeMW/Ex5+M",
    )
    private val openssl = mapOf(
        256 to "SHA256:Tio8vk+fTBvHK7tWMk0mht2DRV/ksQlgvjk18uDTFg8",
        384 to "SHA256:FYZL/BAuemgkCQKywRX7zPB+ek/34Nv53dtjlyij/GM",
        521 to "SHA256:hcVOtjbLAbygsEGFNYO/ArkuOTBGWvtEsK9TPGgrnw8",
    )

    private fun fixture(name: String): ByteArray = javaClass.getResource("/ssh-pkcs8/$name.pem")!!.readBytes()

    private fun failure(block: () -> Unit): String = assertThrows<SshFailure> { block() }.code

    /** 导入后存为 OpenSSH v1，有口令时按原口令加密，且公钥指纹不变。 */
    private fun assertImported(name: String, inputPhrase: ByteArray?, fingerprint: String) {
        val bytes = fixture(name)
        SshPrivateKeyCodec.normalizeImport(bytes, inputPhrase).use { normalized ->
            assertWithMessage(name).that(DefaultKeychainRepository.fingerprint(normalized.publicKey)).isEqualTo(fingerprint)
            assertWithMessage(name).that(normalized.publicKey).startsWith("ecdsa-sha2-nistp")
            assertWithMessage(name).that(normalized.encrypted).isEqualTo(inputPhrase != null)
            assertThat(normalized.bytes.toString(Charsets.US_ASCII)).startsWith("-----BEGIN OPENSSH PRIVATE KEY-----")
            val stored = KeyPair.load(SshKeyGenerator.configuredClient(), normalized.bytes, null)
            try {
                assertWithMessage(name).that(stored.keyType).isEqualTo(KeyPair.ECDSA)
                assertWithMessage(name).that(stored.isEncrypted).isEqualTo(inputPhrase != null)
                if (inputPhrase != null) assertWithMessage(name).that(stored.decrypt(inputPhrase)).isTrue()
            } finally { stored.dispose() }
            assertWithMessage(name).that(DefaultKeychainRepository.fingerprint(SshKeyGenerator.publicKey(normalized.bytes, inputPhrase ?: ByteArray(0))))
                .isEqualTo(fingerprint)
        }
    }

    @Test
    fun `ssh-keygen 显式曲线参数的 EC PKCS8 未加密与加密均可导入`() {
        for ((bits, fingerprint) in sshKeygen) {
            assertImported("ssh-keygen-p$bits", null, fingerprint)
            assertImported("ssh-keygen-p$bits-encrypted", phrase, fingerprint)
        }
    }

    @Test
    fun `OpenSSL 命名曲线的 EC PKCS8 支持 PBKDF2 与 scrypt`() {
        for ((bits, fingerprint) in openssl) {
            assertImported("openssl-p$bits", null, fingerprint)
            assertImported("openssl-p$bits-pbkdf2", phrase, fingerprint)
            assertImported("openssl-p$bits-scrypt", phrase, fingerprint)
        }
    }

    @Test
    fun `加密 EC PKCS8 缺口令或口令错误时报口令错误码`() {
        for (name in listOf("ssh-keygen-p256-encrypted", "openssl-p384-pbkdf2", "openssl-p521-scrypt")) {
            val bytes = fixture(name)
            assertWithMessage(name).that(failure { SshPrivateKeyCodec.normalizeImport(bytes, null) }).isEqualTo("KEY_PASSPHRASE_REQUIRED")
            assertWithMessage(name).that(failure { SshPrivateKeyCodec.normalizeImport(bytes, ByteArray(0)) }).isEqualTo("KEY_PASSPHRASE_REQUIRED")
            assertWithMessage(name).that(failure { SshPrivateKeyCodec.normalizeImport(bytes, "87654321".toByteArray()) }).isEqualTo("INVALID_KEY_PASSPHRASE")
        }
    }

    @Test
    fun `加密 RSA PKCS8 经同一解密路径导入且指纹一致`() {
        val bytes = fixture("ssh-keygen-rsa2048-encrypted")
        SshPrivateKeyCodec.normalizeImport(bytes, phrase).use {
            assertThat(DefaultKeychainRepository.fingerprint(it.publicKey)).isEqualTo("SHA256:vlxkaMUBkwppI3OfVWequQ4sFql4mvicS6lTz5qUPRI")
            assertThat(it.encrypted).isTrue()
        }
        assertThat(failure { SshPrivateKeyCodec.normalizeImport(bytes, "87654321".toByteArray()) }).isEqualTo("INVALID_KEY_PASSPHRASE")
    }

    @Test
    fun `非 NIST 曲线报不支持的密钥类型而非口令错误`() {
        val sec1 = ECPrivateKey(256, BigInteger.valueOf(0x1234_5678), SECObjectIdentifiers.secp256k1)
        val der = PrivateKeyInfo(AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, SECObjectIdentifiers.secp256k1), sec1).encoded
        val pem = "-----BEGIN PRIVATE KEY-----\n${Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(der)}\n-----END PRIVATE KEY-----\n"
        assertThat(failure { SshPrivateKeyCodec.normalizeImport(pem.toByteArray(), null) }).isEqualTo("UNSUPPORTED_USER_KEY")
    }

    @Test
    fun `内嵌公钥与私钥不匹配时拒绝导入`() {
        val donor = PrivateKeyInfo.getInstance(pemDer(fixture("openssl-p256")))
        val other = ECPrivateKey.getInstance(PrivateKeyInfo.getInstance(pemDer(fixture("ssh-keygen-p256"))).parsePrivateKey())
        val mixed = ECPrivateKey(256, ECPrivateKey.getInstance(donor.parsePrivateKey()).key, other.publicKey, SECObjectIdentifiers.secp256r1)
        val der = PrivateKeyInfo(donor.privateKeyAlgorithm, mixed).encoded
        val pem = "-----BEGIN PRIVATE KEY-----\n${Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(der)}\n-----END PRIVATE KEY-----\n"
        assertThat(failure { SshPrivateKeyCodec.normalizeImport(pem.toByteArray(), null) }).isEqualTo("INVALID_PRIVATE_KEY")
    }

    private fun pemDer(pem: ByteArray): ByteArray = Base64.getMimeDecoder().decode(
        pem.toString(Charsets.US_ASCII).lines().filterNot { it.startsWith("-----") }.joinToString(""),
    )
}
