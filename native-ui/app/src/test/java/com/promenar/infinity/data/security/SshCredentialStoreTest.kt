package com.promenar.infinity.data.security

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SshCredentialStoreTest {
    @Test
    fun `凭据对象和编码副本均可独立擦除且文本表示严格脱敏`() {
        val credential = SshCredential(
            privateKey = "fake-private-key".toByteArray(),
            password = "fake-password".toByteArray(),
            passphrase = "fake-passphrase".toByteArray(),
        )
        val encoded = SshCredentialCodec.encode(credential)
        val decoded = SshCredentialCodec.decode(encoded)
        encoded.fill(0)
        credential.close()

        assertThat(credential.privateKey).isEqualTo(ByteArray(16))
        assertThat(credential.password).isEqualTo(ByteArray(13))
        assertThat(credential.passphrase).isEqualTo(ByteArray(15))
        assertThat(decoded.privateKey).isEqualTo("fake-private-key".toByteArray())
        assertThat(decoded.password).isEqualTo("fake-password".toByteArray())
        assertThat(decoded.passphrase).isEqualTo("fake-passphrase".toByteArray())
        assertThat(decoded.toString()).doesNotContain("fake-")
        assertThat(decoded.toString()).doesNotContain("102, 97, 107, 101")
        decoded.close()
        assertThat(decoded.privateKey.all { it == 0.toByte() }).isTrue()
        assertThat(decoded.password.all { it == 0.toByte() }).isTrue()
        assertThat(decoded.passphrase.all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun `损坏版本负长度截断尾随内容和过大记录均安全拒绝`() {
        val encoded = SshCredentialCodec.encode(SshCredential(password = "test-only".toByteArray()))
        val malformed = listOf(
            encoded.copyOf(7),
            encoded.copyOf().also { ByteBuffer.wrap(it).putInt(4, 99) },
            encoded.copyOf().also { ByteBuffer.wrap(it).putInt(8, -1) },
            encoded.copyOf(encoded.size - 1),
            encoded + byteArrayOf(0),
            ByteArray(SshCredentialCodec.MAX_RECORD_BYTES + 1),
        )
        malformed.forEach { bytes ->
            val error = assertThrows<IllegalArgumentException> { SshCredentialCodec.decode(bytes) }
            assertThat(error.stackTraceToString()).doesNotContain("test-only")
        }
    }

    @Test
    fun `SecretStore读写错误不把底层消息和cause传出`() = runBlocking {
        val secret = "synthetic-secret-must-not-escape"
        var allowRead = false
        val failing = object : SecretStore {
            override fun put(id: SecretId, value: ByteArray): Unit = throw IllegalStateException(secret)
            override fun get(id: SecretId): ByteArray? = if (allowRead) null else throw IllegalStateException(secret)
            override fun contains(id: SecretId): Boolean = throw IllegalStateException(secret)
            override fun remove(id: SecretId) = Unit
        }
        val store = SshCredentialStore(failing)
        val readFailure = assertThrows<IllegalStateException> { store.get("host-id", ByteArray(32)) }
        allowRead = true
        val writeFailure = assertThrows<IllegalStateException> {
            runBlocking { store.replace("host-id", ByteArray(32), SshCredential(password = secret.toByteArray())) { } }
        }
        listOf(readFailure, writeFailure).forEach {
            assertThat(it.stackTraceToString()).doesNotContain(secret)
            assertThat(it.cause).isNull()
        }
    }

    @Test
    fun `SSH凭据可随密钥层备份`() {
        assertThat(SecretCatalog.isBackupEligible(SecretCatalog.sshCredential("host-id"), listOf("provider"))).isTrue()
    }

    @Test
    fun `配置提交失败后旧绑定凭据恢复且新绑定不能读取`() = runBlocking {
        val records = mutableMapOf<SecretId, ByteArray>()
        val secrets = object : SecretStore {
            override fun put(id: SecretId, value: ByteArray) { records[id] = value.copyOf() }
            override fun get(id: SecretId) = records[id]?.copyOf()
            override fun contains(id: SecretId) = id in records
            override fun remove(id: SecretId) { records.remove(id)?.fill(0) }
        }
        val store = SshCredentialStore(secrets)
        val oldBinding = ByteArray(32) { 1 }
        val newBinding = ByteArray(32) { 2 }
        SshCredential(password = "old-value".toByteArray()).use { store.replace("id", oldBinding, it) { } }
        assertThrows<IllegalStateException> {
            runBlocking {
                SshCredential(password = "new-value".toByteArray()).use {
                    store.replace("id", newBinding, it) { throw IllegalStateException("synthetic-database-failure") }
                }
            }
        }
        store.get("id", oldBinding)!!.use { assertThat(it.password).isEqualTo("old-value".toByteArray()) }
        assertThat(store.get("id", newBinding)).isNull()
    }
}
