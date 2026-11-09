package com.promenar.infinity.data.ssh

import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.util.Base64
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Sequence

/** PEM 仅作为导入格式，持久化及运行时始终使用受限的 OpenSSH 信封。 */
internal object SshPrivateKeyCodec {
    class Normalized(val bytes: ByteArray, val publicKey: String, val encrypted: Boolean) : AutoCloseable {
        override fun close() = bytes.fill(0)
        override fun toString() = "NormalizedSshKey([已隐藏])"
    }

    fun normalizeImport(bytes: ByteArray, phrase: ByteArray?): Normalized = withKey(bytes, phrase, allowPem = true) { key, encrypted ->
        if (key.keyType !in setOf(KeyPair.ED25519, KeyPair.ECDSA, KeyPair.RSA) || (key.keyType == KeyPair.RSA && key.keySize < 2048)) {
            throw SshFailure("UNSUPPORTED_USER_KEY")
        }
        val openSsh = bytes.toString(Charsets.US_ASCII).trimStart().startsWith("-----BEGIN OPENSSH PRIVATE KEY-----")
        val effectivePhrase = if (openSsh) phrase?.takeIf { encrypted } else phrase?.takeIf { it.isNotEmpty() }
        Normalized(if (openSsh) bytes.copyOf() else write(key, effectivePhrase), publicText(key), if (openSsh) encrypted else effectivePhrase != null)
    }

    fun rewrite(bytes: ByteArray, oldPhrase: ByteArray?, newPhrase: ByteArray?): ByteArray =
        withKey(bytes, oldPhrase) { key, _ -> write(key, newPhrase?.takeIf { it.isNotEmpty() }) }

    fun isEncrypted(bytes: ByteArray, phrase: ByteArray?): Boolean = withKey(bytes, phrase) { _, encrypted -> encrypted }

    private fun write(key: KeyPair, phrase: ByteArray?): ByteArray {
        val output = object : ByteArrayOutputStream() { fun wipe() { buf.fill(0) } }
        try { key.writeOpenSSHv1PrivateKey(output, phrase); return output.toByteArray() }
        finally { output.wipe() }
    }

    private fun publicText(key: KeyPair): String = ByteArrayOutputStream().use {
        key.writePublicKey(it, "infinity"); it.toString(Charsets.UTF_8.name()).trim()
    }

    private inline fun <T> withKey(bytes: ByteArray, phrase: ByteArray?, allowPem: Boolean = false, block: (KeyPair, Boolean) -> T): T {
        var key: KeyPair? = null
        try {
            if (allowPem) validateImportEnvelope(bytes) else SshPolicy.validatePrivateKey(bytes)
            key = KeyPair.load(SshKeyGenerator.configuredClient(), bytes, null)
            val encrypted = key.isEncrypted
            if (encrypted) {
                if ((phrase == null || phrase.isEmpty())) throw SshFailure("KEY_PASSPHRASE_REQUIRED")
                if (!key.decrypt(phrase)) throw SshFailure("INVALID_KEY_PASSPHRASE")
            }
            return block(key, encrypted)
        } catch (failure: SshFailure) { throw failure
        } catch (_: Exception) { throw SshFailure("INVALID_PRIVATE_KEY")
        } finally { key?.dispose() }
    }

    private fun validateImportEnvelope(bytes: ByteArray) {
        if (bytes.size !in 64..65_536) throw SshFailure("INVALID_PRIVATE_KEY")
        val text = bytes.toString(Charsets.US_ASCII).trim()
        if (text.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----")) { SshPolicy.validatePrivateKey(bytes); return }
        val label = listOf("RSA PRIVATE KEY", "EC PRIVATE KEY", "PRIVATE KEY", "ENCRYPTED PRIVATE KEY", "DSA PRIVATE KEY")
            .firstOrNull { text.startsWith("-----BEGIN $it-----") && text.endsWith("-----END $it-----") }
            ?: throw SshFailure("INVALID_PRIVATE_KEY")
        if (label == "ENCRYPTED PRIVATE KEY") {
            val der = Base64.getDecoder().decode(text.removePrefix("-----BEGIN $label-----").removeSuffix("-----END $label-----").filterNot(Char::isWhitespace))
            try { validatePkcs8Kdf(der) } finally { der.fill(0) }
        }
    }

    /** 在交给 JSch 前限定 PKCS8 的口令派生成本，防止文件参数占用过量资源。 */
    private fun validatePkcs8Kdf(der: ByteArray) {
        val info = ASN1Sequence.getInstance(der)
        val algorithm = ASN1Sequence.getInstance(info.getObjectAt(0))
        if (ASN1ObjectIdentifier.getInstance(algorithm.getObjectAt(0)).id != "1.2.840.113549.1.5.13") throw SshFailure("UNSUPPORTED_KEY_CIPHER")
        val params = ASN1Sequence.getInstance(algorithm.getObjectAt(1))
        val kdf = ASN1Sequence.getInstance(params.getObjectAt(0))
        val values = ASN1Sequence.getInstance(kdf.getObjectAt(1))
        val salt = ASN1OctetString.getInstance(values.getObjectAt(0)).octets
        if (salt.size !in 1..256) throw SshFailure("KEY_KDF_LIMIT")
        fun positive(index: Int, max: Long): Long {
            val value = ASN1Integer.getInstance(values.getObjectAt(index)).value
            if (value.signum() <= 0 || value.bitLength() > 63 || value.toLong() > max) throw SshFailure("KEY_KDF_LIMIT")
            return value.toLong()
        }
        when (ASN1ObjectIdentifier.getInstance(kdf.getObjectAt(0)).id) {
            "1.2.840.113549.1.5.12" -> positive(1, 1_000_000)
            "1.3.6.1.4.1.11591.4.11" -> {
                val n = positive(1, 65_536); val r = positive(2, 8); val p = positive(3, 4)
                if (n < 2 || n and (n - 1) != 0L || 128L * n * r > 64L * 1024 * 1024 || n * r * p > 2_097_152) throw SshFailure("KEY_KDF_LIMIT")
            }
            else -> throw SshFailure("UNSUPPORTED_KEY_CIPHER")
        }
    }
}
