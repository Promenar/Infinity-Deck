package com.promenar.infinity.data.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Logger
import com.jcraft.jsch.UserInfo
import com.promenar.infinity.data.security.SshCredential
import com.promenar.infinity.domain.keychain.SshKeyType
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/** 只发布稳定错误代码，底层异常及认证材料不穿过边界。 */
class SshFailure(val code: String) : Exception(code)

object SshPolicy {
    fun alias(value: String): String = value.takeIf { Regex("[a-z][a-z0-9_-]{0,47}").matches(it) }
        ?: throw SshFailure("INVALID_HOST_ALIAS")
    fun path(value: String): String = value.takeIf { it.isNotBlank() && it.length <= 4096 && it.none(Character::isISOControl) }
        ?: throw SshFailure("INVALID_REMOTE_PATH")
    fun timeout(value: Int): Int = value.takeIf { it in 1..600 } ?: throw SshFailure("INVALID_TIMEOUT")
    fun outputLimit(value: Int): Int = value.takeIf { it in 1..256_000 } ?: throw SshFailure("INVALID_OUTPUT_LIMIT")
    fun key(bytes: ByteArray): SshHostKey {
        if (bytes.size !in 8..65_536) throw SshFailure("INVALID_HOST_KEY")
        val buffer = ByteBuffer.wrap(bytes)
        val size = buffer.int
        if (size !in 1..128 || size > buffer.remaining()) throw SshFailure("INVALID_HOST_KEY")
        val type = ByteArray(size).also(buffer::get).toString(Charsets.US_ASCII)
        if (type !in setOf("ssh-ed25519", "ssh-rsa", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521")) {
            throw SshFailure("UNSUPPORTED_HOST_KEY")
        }
        return SshHostKey(type, "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes)))
    }

    fun redact(value: String, credential: SshCredential): String = SshOutputFilter(credential).use { it.whole(value) }

    /** 对私钥信封先限量、限算法与 KDF 成本，再交给库解析。 */
    fun validatePrivateKey(privateKey: ByteArray) {
        if (privateKey.size !in 64..65_536) throw SshFailure("INVALID_PRIVATE_KEY")
        val text = privateKey.toString(Charsets.US_ASCII).trim()
        val begin = "-----BEGIN OPENSSH PRIVATE KEY-----"
        val end = "-----END OPENSSH PRIVATE KEY-----"
        if (!text.startsWith(begin) || !text.endsWith(end)) throw SshFailure("UNSUPPORTED_KEY_FORMAT")
        var decoded = ByteArray(0)
        try {
            decoded = Base64.getDecoder().decode(text.removePrefix(begin).removeSuffix(end).filterNot(Char::isWhitespace))
            val magic = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
            if (decoded.size < magic.size || !decoded.copyOfRange(0, magic.size).contentEquals(magic)) throw SshFailure("INVALID_PRIVATE_KEY")
            val buffer = ByteBuffer.wrap(decoded).apply { position(magic.size) }
            fun bytes(max: Int): ByteArray {
                if (buffer.remaining() < 4) throw SshFailure("INVALID_PRIVATE_KEY")
                val size = buffer.int
                if (size !in 0..max || size > buffer.remaining()) throw SshFailure("INVALID_PRIVATE_KEY")
                return ByteArray(size).also(buffer::get)
            }
            val cipher = bytes(128).toString(Charsets.US_ASCII)
            val kdf = bytes(128).toString(Charsets.US_ASCII)
            val options = bytes(1024)
            try {
                if (cipher == "none" && kdf == "none" && options.isEmpty()) return
                if (cipher !in setOf("aes128-ctr", "aes192-ctr", "aes256-ctr", "aes128-gcm@openssh.com", "aes256-gcm@openssh.com", "chacha20-poly1305@openssh.com") || kdf != "bcrypt") {
                    throw SshFailure("UNSUPPORTED_KEY_CIPHER")
                }
                val params = ByteBuffer.wrap(options)
                if (params.remaining() < 8) throw SshFailure("INVALID_PRIVATE_KEY")
                val salt = params.int
                if (salt !in 1..256 || params.remaining() != salt + 4) throw SshFailure("INVALID_PRIVATE_KEY")
                params.position(params.position() + salt)
                if (params.int !in 1..128) throw SshFailure("KEY_KDF_LIMIT")
            } finally { options.fill(0) }
        } catch (failure: SshFailure) { throw failure
        } catch (_: Exception) { throw SshFailure("INVALID_PRIVATE_KEY")
        } finally { decoded.fill(0) }
    }
}

class PinnedSshHostKeys(private val host: SshHost, private val probe: Boolean = false) : HostKeyRepository {
    var observed: SshHostKey? = null
        private set
    var rejection: String? = null
        private set
    override fun check(hostname: String?, key: ByteArray): Int {
        val candidate = SshPolicy.key(key)
        observed = candidate
        if (probe || host.trustedFingerprint == null || host.trustedKeyType == null) {
            rejection = "HOST_KEY_UNTRUSTED"
            return HostKeyRepository.NOT_INCLUDED
        }
        if (candidate.type == host.trustedKeyType && candidate.fingerprint == host.trustedFingerprint) return HostKeyRepository.OK
        rejection = "HOST_KEY_CHANGED"
        return HostKeyRepository.CHANGED
    }
    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "infinity-pinned-ssh"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

class GeneratedSshKey(val privateKey: ByteArray, val publicKey: String) : AutoCloseable {
    override fun close() { privateKey.fill(0) }
    override fun toString(): String = "GeneratedSshKey([已隐藏])"
}

object SshKeyGenerator {
    private const val SIGNATURES = "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256"
    init {
        mapOf(
            "ssh-ed25519" to "com.jcraft.jsch.bc.SignatureEd25519",
            "keypairgen.eddsa" to "com.jcraft.jsch.bc.KeyPairGenEdDSA",
            "keypairgen_fromprivate.eddsa" to "com.jcraft.jsch.bc.KeyPairGenEdDSA",
            "xdh" to "com.jcraft.jsch.bc.XDH",
            "kex" to "curve25519-sha256,ecdh-sha2-nistp256,diffie-hellman-group14-sha256",
            "server_host_key" to SIGNATURES,
            "PubkeyAcceptedAlgorithms" to SIGNATURES,
            "CheckSignatures" to SIGNATURES,
            "cipher.c2s" to "aes256-ctr,aes128-ctr,aes256-gcm@openssh.com,aes128-gcm@openssh.com",
            "cipher.s2c" to "aes256-ctr,aes128-ctr,aes256-gcm@openssh.com,aes128-gcm@openssh.com",
            "mac.c2s" to "hmac-sha2-256-etm@openssh.com,hmac-sha2-512-etm@openssh.com,hmac-sha2-256,hmac-sha2-512",
            "mac.s2c" to "hmac-sha2-256-etm@openssh.com,hmac-sha2-512-etm@openssh.com,hmac-sha2-256,hmac-sha2-512",
            "compression.c2s" to "none", "compression.s2c" to "none",
            "PreferredAuthentications" to "publickey,password", "StrictHostKeyChecking" to "yes",
            "FingerprintHash" to "sha256", "HashKnownHosts" to "no",
        ).forEach { (key, value) -> JSch.setConfig(key, value) }
        JSch.setLogger(object : Logger {
            override fun isEnabled(level: Int) = false
            override fun log(level: Int, message: String?) = Unit
        })
    }
    fun configuredClient(): JSch = JSch()
    fun generate(): GeneratedSshKey = generate(SshKeyType.ED25519, null)
    fun generate(type: SshKeyType, passphrase: ByteArray?): GeneratedSshKey {
        val key = when (type) {
            SshKeyType.ED25519 -> KeyPair.genKeyPair(configuredClient(), KeyPair.ED25519)
            SshKeyType.RSA_4096 -> KeyPair.genKeyPair(configuredClient(), KeyPair.RSA, 4096)
        }
        return try {
            val private = object : ByteArrayOutputStream() { fun wipe() { buf.fill(0) } }
            try {
                key.writeOpenSSHv1PrivateKey(private, passphrase?.takeIf { it.isNotEmpty() })
                GeneratedSshKey(private.toByteArray(), publicText(key))
            } finally { private.wipe() }
        } catch (_: Exception) { throw SshFailure("KEY_GENERATION_FAILED")
        } finally { key.dispose() }
    }
    fun publicKey(privateKey: ByteArray, passphrase: ByteArray): String {
        SshPolicy.validatePrivateKey(privateKey)
        var key: KeyPair? = null
        try {
            key = KeyPair.load(configuredClient(), privateKey, null)
            if (key.isEncrypted && !key.decrypt(passphrase)) throw SshFailure("INVALID_KEY_PASSPHRASE")
            if (key.keyType !in setOf(KeyPair.ED25519, KeyPair.ECDSA, KeyPair.RSA)) throw SshFailure("UNSUPPORTED_USER_KEY")
            return publicText(key)
        } catch (failure: SshFailure) { throw failure
        } catch (_: Exception) { throw SshFailure("INVALID_PRIVATE_KEY")
        } finally { key?.dispose() }
    }
    private fun publicText(key: KeyPair): String = ByteArrayOutputStream().use {
        key.writePublicKey(it, "infinity")
        it.toString(Charsets.UTF_8.name()).trim()
    }
}

/** 脱敏材料与认证连接同生共灭；流式尾部保留跨分片的敏感前缀。 */
internal class SshOutputFilter(credential: SshCredential) : AutoCloseable {
    private class Pattern(val text: String) {
        val prefix = IntArray(text.length).also { table ->
            var matched = 0
            for (index in 1 until text.length) {
                while (matched > 0 && text[index] != text[matched]) matched = table[matched - 1]
                if (text[index] == text[matched]) matched++
                table[index] = matched
            }
        }
        fun scan(value: String, ranges: IntArray): Int {
            var matched = 0
            value.forEachIndexed { index, char ->
                while (matched > 0 && char != text[matched]) matched = prefix[matched - 1]
                if (char == text[matched]) matched++
                if (matched == text.length) {
                    ranges[index + 1 - matched]++
                    ranges[index + 1]--
                    matched = prefix[matched - 1]
                }
            }
            return matched
        }
    }
    private var closed = false
    private var patterns = buildList {
        listOf(credential.password, credential.passphrase).filter { it.isNotEmpty() }.forEach { add(it.toString(Charsets.UTF_8)) }
        credential.privateKey.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).forEach { add(it.trim()) }
        addAll(PRIVATE_MARKERS)
    }.distinct().filter(String::isNotEmpty).sortedByDescending(String::length).map(::Pattern)
    fun stream() = Stream(this)
    fun whole(value: String): String = stream().feed(value, end = true)
    private data class Scan(val masked: BooleanArray, val hold: Int, val privateStart: Int?)
    @Synchronized private fun scan(value: String, retainedMask: BooleanArray): Scan {
        if (closed) return Scan(BooleanArray(value.length) { true }, 0, null)
        val masked = retainedMask.copyOf(value.length)
        val ranges = IntArray(value.length + 1)
        val hold = patterns.maxOfOrNull { it.scan(value, ranges) } ?: 0
        // 差分区间一次合并；密集重叠的长凭据也不逐个重复填充正文。
        var coverage = 0
        for (index in value.indices) {
            coverage += ranges[index]
            if (coverage > 0) masked[index] = true
        }
        val privateStart = PRIVATE_MARKERS.map(value::indexOf).filter { it >= 0 }.minOrNull()
        return Scan(masked, hold, privateStart)
    }
    override fun close() = synchronized(this) { closed = true; patterns.forEach { it.prefix.fill(0) }; patterns = emptyList() }

    class Stream internal constructor(private val parent: SshOutputFilter) {
        private var pending = ""
        private var pendingMask = BooleanArray(0)
        private var privateBlock = false
        @Synchronized fun feed(value: String, end: Boolean = false): String {
            val input = pending + value
            pending = ""
            if (privateBlock) return "█".repeat(input.length)
            val scan = parent.scan(input, pendingMask)
            pendingMask = BooleanArray(0)
            if (scan.privateStart != null) {
                privateBlock = true
                // 标记之前尚未完成的凭据前缀也不能因进入私钥区而提前发布。
                scan.masked.fill(true, minOf(scan.privateStart, input.length - scan.hold))
            }
            val hold = if (privateBlock || end) 0 else scan.hold
            val emitted = input.length - hold
            if (end && scan.hold > 0) scan.masked.fill(true, input.length - scan.hold)
            pending = input.substring(emitted)
            pendingMask = scan.masked.copyOfRange(emitted, input.length)
            return buildString(emitted) {
                for (index in 0 until emitted) append(if (scan.masked[index]) '█' else input[index])
            }
        }
    }
    private companion object {
        val PRIVATE_MARKERS = listOf("-----BEGIN OPENSSH PRIVATE KEY-----", "-----BEGIN RSA PRIVATE KEY-----", "-----BEGIN EC PRIVATE KEY-----", "-----BEGIN PRIVATE KEY-----", "-----BEGIN ENCRYPTED PRIVATE KEY-----")
    }
}
