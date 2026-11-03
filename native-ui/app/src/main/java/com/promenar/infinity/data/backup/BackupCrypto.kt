package com.promenar.infinity.data.backup

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupCrypto private constructor(
    private val secureRandom: SecureRandom,
) {
    constructor() : this(SecureRandom())

    fun isStreamEncrypted(prefix: ByteArray): Boolean = prefix.size >= 4 && prefix.take(4).toByteArray().contentEquals(STREAM_MAGIC)

    fun encryptStream(
        input: java.io.InputStream, output: java.io.OutputStream, password: CharArray,
        onKdf: (BackupKdfMetadata) -> Unit = {},
    ) = encryptStream(output, password) { plain, kdf ->
        onKdf(kdf)
        BackupStreams.copy(input, plain, BackupStreams.MAX_WIRE_BYTES)
    }

    fun encryptStream(
        output: java.io.OutputStream, password: CharArray,
        writer: (java.io.OutputStream, BackupKdfMetadata) -> Unit,
    ) {
        val salt = ByteArray(SALT_SIZE_BYTES).also(secureRandom::nextBytes)
        val nonce = ByteArray(IV_SIZE_BYTES).also(secureRandom::nextBytes)
        val owned = password.copyOf()
        var key = ByteArray(0)
        val header = ByteBuffer.allocate(STREAM_HEADER_BYTES).put(STREAM_MAGIC).put(1.toByte()).put(1.toByte())
            .putInt(PBKDF2_ITERATIONS).putInt(KEY_SIZE_BITS).put(salt).put(nonce).array()
        try {
            key = deriveKey(owned, salt)
            output.write(header)
            val sink = ChunkOutput(output, SecretKeySpec(key, "AES"), nonce, header)
            try {
                writer(sink, BackupKdfMetadata(KDF_ALGORITHM, Base64.getEncoder().encodeToString(salt), PBKDF2_ITERATIONS, KEY_SIZE_BITS))
                sink.finish()
            } finally { sink.wipe() }
        } finally {
            key.fill(0); owned.fill('\u0000'); salt.fill(0); nonce.fill(0); header.fill(0)
        }
    }

    @Throws(GeneralSecurityException::class)
    fun decryptStream(input: java.io.InputStream, output: java.io.OutputStream, password: CharArray): BackupKdfMetadata {
        val header = ByteArray(STREAM_HEADER_BYTES)
        val owned = password.copyOf()
        var key = ByteArray(0)
        var nonce = ByteArray(0)
        var salt = ByteArray(0)
        try {
            val source = java.io.DataInputStream(input)
            source.readFully(header)
            val metadata = ByteBuffer.wrap(header)
            val magic = ByteArray(4).also(metadata::get)
            if (!magic.contentEquals(STREAM_MAGIC) || metadata.get().toInt() != 1 || metadata.get().toInt() != 1 ||
                metadata.int != PBKDF2_ITERATIONS || metadata.int != KEY_SIZE_BITS
            ) throw GeneralSecurityException("分块加密信封参数无效")
            salt = ByteArray(SALT_SIZE_BYTES).also(metadata::get)
            nonce = ByteArray(IV_SIZE_BYTES).also(metadata::get)
            key = deriveKey(owned, salt)
            var index = 0L
            var total = 0L
            while (true) {
                val length = source.readInt()
                val last = source.readUnsignedByte()
                if (length !in 0..CHUNK_BYTES || last !in 0..1 || last == 0 && length != CHUNK_BYTES ||
                    last == 1 && length == CHUNK_BYTES || length > BackupStreams.MAX_WIRE_BYTES - total
                ) throw GeneralSecurityException("分块长度或末块标记无效")
                val encrypted = ByteArray(length + TAG_SIZE_BYTES)
                var plain = ByteArray(0)
                try {
                    source.readFully(encrypted)
                    plain = chunkCipher(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), nonce, header, index, last == 1, length).doFinal(encrypted)
                    output.write(plain)
                } finally { encrypted.fill(0); plain.fill(0) }
                total += length
                index++
                if (last == 1) {
                    if (source.read() != -1) throw GeneralSecurityException("末块后存在额外内容")
                    break
                }
            }
            return BackupKdfMetadata(KDF_ALGORITHM, Base64.getEncoder().encodeToString(salt), PBKDF2_ITERATIONS, KEY_SIZE_BITS)
        } catch (error: GeneralSecurityException) {
            throw GeneralSecurityException("备份密码错误或分块认证失败", error)
        } catch (error: java.io.EOFException) {
            throw GeneralSecurityException("加密备份被截断", error)
        } finally {
            owned.fill('\u0000'); key.fill(0); nonce.fill(0); salt.fill(0); header.fill(0)
        }
    }

    private class ChunkOutput(
        private val target: java.io.OutputStream, private val key: SecretKeySpec,
        private val nonce: ByteArray, private val header: ByteArray,
    ) : java.io.OutputStream() {
        private val buffer = ByteArray(CHUNK_BYTES)
        private var used = 0
        private var index = 0L
        private var total = 0L
        override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            var position = offset
            var remaining = length
            while (remaining > 0) {
                val count = minOf(CHUNK_BYTES - used, remaining)
                bytes.copyInto(buffer, used, position, position + count)
                used += count; position += count; remaining -= count
                if (used == CHUNK_BYTES) emit(false)
            }
        }
        fun finish() { emit(true) }
        fun wipe() { buffer.fill(0) }
        private fun emit(last: Boolean) {
            if (used > BackupStreams.MAX_WIRE_BYTES - total) throw BackupValidationException("加密内容超过容量上限", reason = BackupFailureReason.CAPACITY_EXCEEDED)
            val encrypted = chunkCipher(Cipher.ENCRYPT_MODE, key, nonce, header, index, last, used).doFinal(buffer, 0, used)
            try {
                val frame = java.io.DataOutputStream(target)
                frame.writeInt(used); frame.writeByte(if (last) 1 else 0); frame.write(encrypted)
                total += used; index++; used = 0; buffer.fill(0)
            } finally { encrypted.fill(0) }
        }
    }

    fun encrypt(plaintext: ByteArray, password: CharArray): ByteArray =
        encryptInternal(plaintext, password, Parameters.random(secureRandom))

    fun encryptPackage(
        password: CharArray,
        plaintextFactory: (BackupKdfMetadata) -> ByteArray,
    ): ByteArray {
        val parameters = Parameters.random(secureRandom)
        var plaintext: ByteArray? = null
        return try {
            plaintext = plaintextFactory(parameters.toKdfMetadata())
            encryptInternal(plaintext, password, parameters)
        } finally {
            plaintext?.fill(0)
            parameters.wipe()
        }
    }

    @Throws(GeneralSecurityException::class)
    fun decrypt(envelope: ByteArray, password: CharArray): ByteArray {
        val metadata = parseEnvelope(envelope)
        val ownedPassword = password.copyOf()
        var keyBytes: ByteArray? = null
        return try {
            keyBytes = deriveKey(ownedPassword, metadata.salt)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(TAG_SIZE_BITS, metadata.iv),
            )
            cipher.updateAAD(envelope, 0, metadata.headerSize)
            cipher.doFinal(envelope, metadata.headerSize, envelope.size - metadata.headerSize)
        } catch (error: GeneralSecurityException) {
            throw GeneralSecurityException("备份密码错误或备份包认证失败", error)
        } finally {
            keyBytes?.fill(0)
            ownedPassword.fill('\u0000')
            metadata.wipe()
        }
    }

    fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    private fun encryptInternal(plaintext: ByteArray, password: CharArray, parameters: Parameters): ByteArray {
        val ownedPassword = password.copyOf()
        val header = encodeHeader(parameters)
        var keyBytes: ByteArray? = null
        return try {
            keyBytes = deriveKey(ownedPassword, parameters.salt)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(TAG_SIZE_BITS, parameters.iv),
            )
            cipher.updateAAD(header)
            val ciphertext = cipher.doFinal(plaintext)
            try {
                header + ciphertext
            } finally {
                ciphertext.fill(0)
            }
        } finally {
            keyBytes?.fill(0)
            ownedPassword.fill('\u0000')
            header.fill(0)
            parameters.wipe()
        }
    }

    private fun encodeHeader(parameters: Parameters): ByteArray {
        val algorithmBytes = KDF_ALGORITHM.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(
            MAGIC.size + 1 + Short.SIZE_BYTES + algorithmBytes.size +
                Int.SIZE_BYTES + Int.SIZE_BYTES + 1 + parameters.salt.size + 1 + parameters.iv.size,
        )
            .put(MAGIC)
            .put(ENVELOPE_VERSION.toByte())
            .putShort(algorithmBytes.size.toShort())
            .put(algorithmBytes)
            .putInt(PBKDF2_ITERATIONS)
            .putInt(KEY_SIZE_BITS)
            .put(parameters.salt.size.toByte())
            .put(parameters.salt)
            .put(parameters.iv.size.toByte())
            .put(parameters.iv)
            .array()
    }

    private fun parseEnvelope(envelope: ByteArray): EnvelopeMetadata {
        try {
            val buffer = ByteBuffer.wrap(envelope)
            val magic = ByteArray(MAGIC.size).also(buffer::get)
            val version = buffer.get().toInt() and 0xff
            val algorithmSize = buffer.short.toInt() and 0xffff
            if (!magic.contentEquals(MAGIC) || version != ENVELOPE_VERSION || algorithmSize !in 1..MAX_ALGORITHM_BYTES) {
                throw GeneralSecurityException("不支持的加密备份包 header")
            }
            val algorithm = ByteArray(algorithmSize).also(buffer::get).toString(StandardCharsets.UTF_8)
            val iterations = buffer.int
            val keySizeBits = buffer.int
            val saltSize = buffer.get().toInt() and 0xff
            if (saltSize != SALT_SIZE_BYTES) throw GeneralSecurityException("不支持的加密备份包 header")
            val salt = ByteArray(saltSize).also(buffer::get)
            val ivSize = buffer.get().toInt() and 0xff
            if (ivSize != IV_SIZE_BYTES) throw GeneralSecurityException("不支持的加密备份包 header")
            val iv = ByteArray(ivSize).also(buffer::get)
            val headerSize = buffer.position()
            if (algorithm != KDF_ALGORITHM || iterations != PBKDF2_ITERATIONS || keySizeBits != KEY_SIZE_BITS ||
                envelope.size - headerSize < TAG_SIZE_BYTES
            ) throw GeneralSecurityException("不支持的加密备份包 header")
            return EnvelopeMetadata(salt, iv, headerSize)
        } catch (error: GeneralSecurityException) {
            throw error
        } catch (error: RuntimeException) {
            throw GeneralSecurityException("无效的加密备份包 header")
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_SIZE_BITS)
        return try {
            SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private data class Parameters(val salt: ByteArray, val iv: ByteArray) {
        fun toKdfMetadata() = BackupKdfMetadata(
            algorithm = KDF_ALGORITHM,
            saltBase64 = Base64.getEncoder().encodeToString(salt),
            iterations = PBKDF2_ITERATIONS,
            keySizeBits = KEY_SIZE_BITS,
        )

        fun wipe() {
            salt.fill(0)
            iv.fill(0)
        }

        companion object {
            fun random(secureRandom: SecureRandom) = Parameters(
                ByteArray(SALT_SIZE_BYTES).also(secureRandom::nextBytes),
                ByteArray(IV_SIZE_BYTES).also(secureRandom::nextBytes),
            )
        }
    }

    private data class EnvelopeMetadata(
        val salt: ByteArray,
        val iv: ByteArray,
        val headerSize: Int,
    ) {
        fun wipe() {
            salt.fill(0)
            iv.fill(0)
        }
    }

    companion object {
        const val CHUNK_BYTES = 1024 * 1024
        const val STREAM_HEADER_BYTES = 42
        private val STREAM_MAGIC = byteArrayOf(78, 88, 66, 83)
        private fun chunkCipher(mode: Int, key: SecretKeySpec, base: ByteArray, header: ByteArray, index: Long, last: Boolean, length: Int): Cipher {
            val nonce = base.copyOf()
            val counter = ByteBuffer.allocate(8).putLong(index).array()
            for (position in counter.indices) nonce[4 + position] = (nonce[4 + position].toInt() xor counter[position].toInt()).toByte()
            return try {
                Cipher.getInstance(TRANSFORMATION).apply {
                    init(mode, key, GCMParameterSpec(TAG_SIZE_BITS, nonce))
                    updateAAD(header)
                    updateAAD(ByteBuffer.allocate(13).putLong(index).put(if (last) 1.toByte() else 0.toByte()).putInt(length).array())
                }
            } finally { nonce.fill(0); counter.fill(0) }
        }
        const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        const val PBKDF2_ITERATIONS = 600_000
        const val KEY_SIZE_BITS = 256
        const val SALT_SIZE_BYTES = 16
        const val IV_SIZE_BYTES = 12
        private const val ENVELOPE_VERSION = 1
        private const val MAX_ALGORITHM_BYTES = 64
        private const val TAG_SIZE_BITS = 128
        private const val TAG_SIZE_BYTES = TAG_SIZE_BITS / 8
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val MAGIC = byteArrayOf('N'.code.toByte(), 'X'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    }
}
