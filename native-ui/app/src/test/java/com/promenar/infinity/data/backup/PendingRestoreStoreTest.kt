package com.promenar.infinity.data.backup

import android.util.AtomicFile
import com.google.common.truth.Truth.assertThat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.Assert.assertThrows
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PendingRestoreStoreTest {
    @Test
    fun `流式暂存保留旧记录支持的四千零九十六字符密码`() {
        val root = Files.createTempDirectory("pending-password-compat")
        val password = CharArray(4096) { 'a' }
        try {
            val source = Files.write(root.resolve("source.iwb"), byteArrayOf(1))
            val store = AndroidPendingRestoreStore(AtomicFile(root.resolve("pending.bin").toFile()), TestCryptor(ByteArray(32) { 5 }))
            store.begin(TX_ID); store.stageFile(TX_ID, source, password); store.authorize(TX_ID)
            store.read()!!.use { assertThat(it.password).isEqualTo(password) }
        } finally { password.fill('\u0000'); BackupStreams.deleteTree(root) }
    }

    @Test
    fun `重建暂存服务后清理进程中断留下的明文树`() {
        val root = Files.createTempDirectory("pending-orphan")
        try {
            val orphan = Files.createDirectory(root.resolve(".pending-open-abandoned"))
            Files.write(orphan.resolve("secrets.json"), "合成测试数据".toByteArray())
            val store = AndroidPendingRestoreStore(AtomicFile(root.resolve("pending.bin").toFile()), TestCryptor(ByteArray(32) { 6 }))
            assertThat(store.read()).isNull()
            assertThat(Files.exists(orphan)).isFalse()
        } finally { BackupStreams.deleteTree(root) }
    }

    @Test
    fun `大包暂存重启后以文件读取且单次加密载荷有界`() {
        val root = Files.createTempDirectory("pending-stream-test")
        try {
            val file = root.resolve("pending.bin").toFile()
            val source = root.resolve("source.iwb")
            java.io.RandomAccessFile(source.toFile(), "rw").use { it.setLength(30L * 1024 * 1024) }
            val expected = BackupStreams.digest(BackupBlob.File(source))
            val cryptor = TestCryptor(ByteArray(32) { 7 })
            val store = AndroidPendingRestoreStore(AtomicFile(file), cryptor)
            store.begin(TX_ID); store.stageFile(TX_ID, source, "暂存密码".toCharArray()); store.authorize(TX_ID)
            assertThat(cryptor.maximumPlainBytes).isAtMost(BackupCrypto.CHUNK_BYTES + 128)
            AndroidPendingRestoreStore(AtomicFile(file), cryptor).read()!!.use { payload ->
                assertThat(payload.packageBytes).isEmpty()
                assertThat(payload.packageFile).isNotNull()
                assertThat(BackupStreams.digest(BackupBlob.File(payload.packageFile!!))).isEqualTo(expected)
                assertThat(payload.password).isEqualTo("暂存密码".toCharArray())
            }
            store.cancel(TX_ID)
            assertThat(store.read()).isNull()
        } finally { BackupStreams.deleteTree(root) }
    }

    @Test
    fun `分块暂存篡改后拒绝且取消不能被迟到的暂存覆盖`() {
        val root = Files.createTempDirectory("pending-stream-tamper")
        try {
            val file = root.resolve("pending.bin").toFile()
            val source = Files.write(root.resolve("source.iwb"), byteArrayOf(1, 2, 3))
            val store = AndroidPendingRestoreStore(AtomicFile(file), TestCryptor(ByteArray(32) { 8 }))
            store.begin(TX_ID); store.stageFile(TX_ID, source, null); store.authorize(TX_ID)
            val payload = root.resolve(".pending-package-$TX_ID.bin")
            java.io.RandomAccessFile(payload.toFile(), "rw").use { it.setLength(8) }
            assertThrows(Exception::class.java) { store.read() }
            store.cancel(TX_ID)
            assertThrows(BackupValidationException::class.java) { store.stageFile(TX_ID, source, null) }
        } finally { BackupStreams.deleteTree(root) }
    }

    @Test
    fun `staging ticket cannot overwrite another transaction and cancelled ticket cannot commit late stage`() {
        val file = Files.createTempFile("pending-restore", ".bin").toFile().also { it.delete() }
        val store = AndroidPendingRestoreStore(AtomicFile(file), TestCryptor(ByteArray(32) { 4 }))
        store.begin(TX_ID)
        store.read()!!.use { assertThat(it.metadata.phase).isEqualTo(PendingRestorePhase.STAGING) }

        assertThrows(BackupValidationException::class.java) {
            store.begin("123e4567-e89b-12d3-a456-426614174099")
        }
        store.stage(TX_ID, byteArrayOf(1), null)
        assertThrows(BackupValidationException::class.java) { store.begin(TX_ID) }
        store.cancel(TX_ID)
        assertThat(store.read()).isNull()
        assertThrows(BackupValidationException::class.java) {
            store.stage(TX_ID, byteArrayOf(1), null)
        }
    }

    @Test
    fun `stage authorize and cancel write failures preserve only non-restorable staging state`() {
        val file = Files.createTempFile("pending-restore", ".bin").toFile().also { it.delete() }
        val cryptor = SwitchableCryptor(ByteArray(32) { 6 })
        val store = AndroidPendingRestoreStore(AtomicFile(file), cryptor)
        store.begin(TX_ID)

        cryptor.failEncrypt = true
        assertThrows(BackupValidationException::class.java) { store.stage(TX_ID, byteArrayOf(1), null) }
        cryptor.failEncrypt = false
        store.read()!!.use {
            assertThat(it.metadata.phase).isEqualTo(PendingRestorePhase.STAGING)
            assertThat(it.packageBytes).isEmpty()
        }

        store.stage(TX_ID, byteArrayOf(1), null)
        cryptor.failEncrypt = true
        assertThrows(BackupValidationException::class.java) { store.authorize(TX_ID) }
        assertThrows(BackupValidationException::class.java) { store.cancel(TX_ID) }
        cryptor.failEncrypt = false
        store.read()!!.use {
            assertThat(it.metadata.phase).isEqualTo(PendingRestorePhase.STAGING)
            assertThat(it.packageBytes).isEqualTo(byteArrayOf(1))
        }
    }

    @Test
    fun `encrypted pending survives store recreation and contains no package or password plaintext`() {
        val file = Files.createTempFile("pending-restore", ".bin").toFile().also { it.delete() }
        val key = ByteArray(32) { it.toByte() }
        val packageBytes = "package-private-marker".toByteArray()
        val password = "password-private-marker".toCharArray()

        val first = AndroidPendingRestoreStore(AtomicFile(file), TestCryptor(key))
        first.begin(TX_ID)
        first.stage(TX_ID, packageBytes, password)
        val metadata = first.authorize(TX_ID)
        val disk = file.readBytes().toString(Charsets.ISO_8859_1)
        val recreated = AndroidPendingRestoreStore(AtomicFile(file), TestCryptor(key))

        assertThat(disk).doesNotContain("package-private-marker")
        assertThat(disk).doesNotContain("password-private-marker")
        recreated.read()!!.use { payload ->
            assertThat(payload.metadata.txId).isEqualTo(metadata.txId)
            assertThat(payload.packageBytes).isEqualTo(packageBytes)
            assertThat(payload.password).isEqualTo(password)
        }
        assertThat(packageBytes.toString(Charsets.UTF_8)).isEqualTo("package-private-marker")
        assertThat(password.concatToString()).isEqualTo("password-private-marker")
    }

    @Test
    fun `tamper wrong key and truncated atomic file fail closed`() {
        val file = Files.createTempFile("pending-restore", ".bin").toFile().also { it.delete() }
        val key = ByteArray(32) { 1 }
        val store = AndroidPendingRestoreStore(AtomicFile(file), TestCryptor(key))
        stageAuthorized(store, byteArrayOf(1, 2, 3), "pw".toCharArray())

        val tampered = file.readBytes().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        file.writeBytes(tampered)
        assertThrows(BackupValidationException::class.java) { store.read() }

        AtomicFile(file).delete()
        stageAuthorized(store, byteArrayOf(1, 2, 3), "pw".toCharArray())
        assertThrows(BackupValidationException::class.java) {
            AndroidPendingRestoreStore(AtomicFile(file), TestCryptor(ByteArray(32) { 2 })).read()
        }

        file.writeBytes(file.readBytes().copyOf(8))
        assertThrows(BackupValidationException::class.java) { store.read() }
    }

    private class TestCryptor(key: ByteArray) : PendingRestoreCryptor {
        private val key = SecretKeySpec(key.copyOf(), "AES")
        var maximumPlainBytes = 0
        override fun encrypt(plain: ByteArray): ByteArray {
            maximumPlainBytes = maxOf(maximumPlainBytes, plain.size)
            val iv = ByteArray(12) { (it + 9).toByte() }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            return iv + cipher.doFinal(plain)
        }

        override fun decrypt(encrypted: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
            return cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
        }
    }

    private class SwitchableCryptor(key: ByteArray) : PendingRestoreCryptor {
        private val delegate = TestCryptor(key)
        var failEncrypt = false
        override fun encrypt(plain: ByteArray): ByteArray {
            if (failEncrypt) error("simulated encrypt failure")
            return delegate.encrypt(plain)
        }
        override fun decrypt(encrypted: ByteArray) = delegate.decrypt(encrypted)
    }

    private fun stageAuthorized(store: PendingRestoreStore, bytes: ByteArray, password: CharArray?) {
        store.begin(TX_ID)
        store.stage(TX_ID, bytes, password)
        store.authorize(TX_ID)
    }

    private companion object {
        const val TX_ID = "123e4567-e89b-12d3-a456-426614174000"
    }
}
