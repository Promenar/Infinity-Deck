package com.promenar.infinity.data.backup

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException

class BackupChunkStreamTest {
    private val password = "分块备份测试".toCharArray()
    private fun encrypt(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also {
        BackupCrypto().encryptStream(ByteArrayInputStream(bytes), it, password) { }
    }.toByteArray()
    private fun decrypt(bytes: ByteArray, secret: CharArray = password): ByteArray = ByteArrayOutputStream().also {
        BackupCrypto().decryptStream(ByteArrayInputStream(bytes), it, secret)
    }.toByteArray()

    @Test fun `跨多个分块往返且精确分块边界有经过认证的末块`() {
        listOf(0, 1, 1024 * 1024, 2 * 1024 * 1024 + 19).forEach { size ->
            val plain = ByteArray(size) { (it * 31).toByte() }
            assertArrayEquals(plain, decrypt(encrypt(plain)))
        }
    }
    @Test fun `截断和追加另一个包均被拒绝`() {
        val encrypted = encrypt(ByteArray(1024 * 1024 + 11))
        rejected(encrypted.copyOf(encrypted.size - 1))
        rejected(encrypted + encrypt(byteArrayOf(1)))
    }
    @Test fun `重排拼接和末块标记篡改均被拒绝`() {
        val a = encrypt(ByteArray(3 * 1024 * 1024) { 1 })
        val b = encrypt(ByteArray(3 * 1024 * 1024) { 2 })
        val frame = 4 + 1 + 1024 * 1024 + 16
        val header = BackupCrypto.STREAM_HEADER_BYTES
        rejected(a.copyOf().also { a.copyInto(it, header + frame, header, header + frame); a.copyInto(it, header, header + frame, header + 2 * frame) })
        rejected(a.copyOf().also { b.copyInto(it, header, header, header + frame) })
        rejected(a.copyOf().also { it[header + 4] = 1 })
    }
    @Test fun `密码错误拒绝`() {
        assertThrows(GeneralSecurityException::class.java) { decrypt(encrypt(byteArrayOf(9)), "错误".toCharArray()) }
    }
    private fun rejected(bytes: ByteArray) {
        assertThrows(GeneralSecurityException::class.java) { decrypt(bytes) }
    }
}
