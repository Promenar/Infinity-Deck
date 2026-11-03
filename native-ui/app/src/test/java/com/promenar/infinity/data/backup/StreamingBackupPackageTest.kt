package com.promenar.infinity.data.backup

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files

class StreamingBackupPackageTest {
    @Test fun `三十MiB技能采用小缓冲打包和磁盘恢复`() {
        val root = Files.createTempDirectory("stream-backup-test")
        try {
            val skill = root.resolve("large.bin")
            Files.newOutputStream(skill).use { out -> repeat(480) { out.write(ByteArray(64 * 1024) { 7 }) } }
            val source = BackupSnapshot(databaseSchemaVersion = DATABASE_BACKUP_SCHEMA_VERSION, appVersion = "test",
                layers = setOf(BackupLayer.CORE),
                streamEntries = listOf(BackupSourceEntry("skills/demo/large.bin", BackupLayer.CORE, BackupBlob.File(skill)),
                    BackupSourceEntry("preferences.json", BackupLayer.CORE, BackupBlob.Bytes("{\"entries\":[],\"providerIds\":[]}".toByteArray()))),
                skills = SkillBackupPartition(emptyMap(), SkillBackupMetadata(directories = listOf("demo"))))
            val codec = DefaultBackupPackageCodec()
            val target = root.resolve("backup.iwb")
            Files.newOutputStream(target).use { codec.encodeTo(source, BackupOptions(emptySet()), it) }
            Files.newInputStream(target).use { input -> codec.decodeFrom(input, null, root).use { restored ->
                val blob = restored.blobs.getValue("skills/demo/large.bin")
                assertEquals(30L * 1024 * 1024, blob.size)
                assertEquals(BackupStreams.digest(BackupBlob.File(skill)), BackupStreams.digest(blob))
                assertTrue(blob is BackupBlob.File)
            } }
        } finally { BackupStreams.deleteTree(root) }
    }
    @Test fun `复制三十MiB时每次读取保持固定小缓冲`() {
        var remaining = 30L * 1024 * 1024
        var maximumRead = 0
        val source = object : java.io.InputStream() {
            override fun read(): Int = error("应使用有界批量读取")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                maximumRead = maxOf(maximumRead, length)
                assertTrue(length <= 64 * 1024)
                if (remaining == 0L) return -1
                val count = minOf(length.toLong(), remaining).toInt()
                bytes.fill(1, offset, offset + count); remaining -= count
                return count
            }
        }
        assertEquals(30L * 1024 * 1024, BackupStreams.copy(source, BackupStreams.DISCARD, BackupPackageLimits.MAX_TOTAL_BYTES))
        assertEquals(64 * 1024, maximumRead)
    }

    @Test fun `空间不足和容量上限有明确分类`() {
        val root = Files.createTempDirectory("backup-capacity")
        try {
            val exception = assertThrows(BackupValidationException::class.java) { BackupStreams.requireSpace(root, 10, available = { 9 }) }
            assertEquals(BackupFailureDetail.INSUFFICIENT_STORAGE, exception.detail)
            val overflow = assertThrows(BackupValidationException::class.java) {
                BackupStreams.copy(java.io.ByteArrayInputStream(ByteArray(12)), java.io.ByteArrayOutputStream(), 11)
            }
            assertEquals(BackupFailureReason.CAPACITY_EXCEEDED, overflow.reason)
        } finally { BackupStreams.deleteTree(root) }
    }
}
