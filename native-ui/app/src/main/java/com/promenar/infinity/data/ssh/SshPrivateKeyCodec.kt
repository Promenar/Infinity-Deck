package com.promenar.infinity.data.ssh

import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.util.Base64

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
        var prepared: ByteArray? = null
        try {
            prepared = if (allowPem) prepareImport(bytes, phrase) else { SshPolicy.validatePrivateKey(bytes); null }
            key = KeyPair.load(SshKeyGenerator.configuredClient(), prepared ?: bytes, null)
            val encrypted = key.isEncrypted
            if (encrypted) {
                if ((phrase == null || phrase.isEmpty())) throw SshFailure("KEY_PASSPHRASE_REQUIRED")
                if (!key.decrypt(phrase)) throw SshFailure("INVALID_KEY_PASSPHRASE")
            }
            return block(key, encrypted)
        } catch (failure: SshFailure) { throw failure
        } catch (_: Exception) { throw SshFailure("INVALID_PRIVATE_KEY")
        } finally { key?.dispose(); prepared?.fill(0) }
    }

    /** 校验导入信封；PKCS8 由 [SshPkcs8Import] 解密或转换，返回需交给 JSch 的替换 PEM。 */
    private fun prepareImport(bytes: ByteArray, phrase: ByteArray?): ByteArray? {
        if (bytes.size !in 64..65_536) throw SshFailure("INVALID_PRIVATE_KEY")
        val text = bytes.toString(Charsets.US_ASCII).trim()
        if (text.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----")) { SshPolicy.validatePrivateKey(bytes); return null }
        val label = listOf("RSA PRIVATE KEY", "EC PRIVATE KEY", "PRIVATE KEY", "ENCRYPTED PRIVATE KEY", "DSA PRIVATE KEY")
            .firstOrNull { text.startsWith("-----BEGIN $it-----") && text.endsWith("-----END $it-----") }
            ?: throw SshFailure("INVALID_PRIVATE_KEY")
        if (label != "PRIVATE KEY" && label != "ENCRYPTED PRIVATE KEY") return null
        val der = Base64.getDecoder().decode(text.removePrefix("-----BEGIN $label-----").removeSuffix("-----END $label-----").filterNot(Char::isWhitespace))
        try { return SshPkcs8Import.prepare(label, der, phrase) } finally { der.fill(0) }
    }
}
