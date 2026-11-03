package com.promenar.infinity.data.backup

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AvatarBackupPackageTest {
    @Test
    fun `照片分区加密往返并在关闭时清零`() {
        val codec = DefaultBackupPackageCodec()
        val original = byteArrayOf(1, 2, 3)
        val snapshot = BackupSnapshot(databaseSchemaVersion = DATABASE_BACKUP_SCHEMA_VERSION, appVersion = "test", avatars = mapOf("photo" to original))
        val bytes = codec.encode(snapshot, BackupOptions(setOf(BackupContent.FILES), password = "test-password".toCharArray(), encryptPackage = true))
        val restored = codec.decode(bytes, "test-password".toCharArray())
        assertEquals(3, restored.manifest.formatVersion)
        assertArrayEquals(original, restored.avatars.getValue("photo"))
        restored.close()
        assertTrue(restored.avatars.getValue("photo").all { it == 0.toByte() })
        assertArrayEquals(byteArrayOf(1, 2, 3), original)
    }

    @Test
    fun `哈希大小篡改越界标识超限与旧格式夹带均拒绝`() {
        val content = byteArrayOf(1, 2, 3)
        assertThrows<BackupValidationException> { DefaultBackupPackageCodec().decode(archive(content, hash = "0".repeat(64)), null) }
        assertThrows<BackupValidationException> { DefaultBackupPackageCodec().decode(archive(content, size = 2), null) }
        assertThrows<BackupValidationException> { DefaultBackupPackageCodec().decode(archive(content, id = "../escape"), null) }
        assertThrows<BackupValidationException> { DefaultBackupPackageCodec().decode(archive(content, size = AvatarBackupFiles.MAX_FILE_BYTES + 1), null) }
        assertThrows<BackupValidationException> { DefaultBackupPackageCodec().decode(archive(content, version = 2), null) }
        assertThrows<BackupValidationException> {
            DefaultBackupPackageCodec().encode(BackupSnapshot(databaseSchemaVersion = 12, appVersion = "test",
                avatars = mapOf("photo" to ByteArray((BackupPackageLimits.MAX_IN_MEMORY_BYTES + 1).toInt()))), BackupOptions(setOf(BackupContent.FILES)))
        }
    }

    @Test
    fun `格式一和二的无照片旧包均可读取`() {
        listOf(1, 2).forEach { version ->
            DefaultBackupPackageCodec().decode(archive(null, version = version), null).use { assertTrue(it.avatars.isEmpty()) }
        }
    }

    private fun archive(content: ByteArray?, hash: String? = null, size: Long? = null, id: String = "photo", version: Int = 3): ByteArray {
        val entries = if (content == null) emptyList() else listOf(BackupManifestEntry("avatars/$id", size ?: content.size.toLong(),
            hash ?: MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it.toInt() and 255) }))
        val manifest = BackupManifest(version, DATABASE_BACKUP_SCHEMA_VERSION, "test", 1, entries, false, false)
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(Json.encodeToString(manifest).toByteArray()); zip.closeEntry()
                if (content != null) { zip.putNextEntry(ZipEntry("avatars/$id")); zip.write(content); zip.closeEntry() }
            }
        }.toByteArray()
    }
}
