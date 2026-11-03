package com.promenar.infinity.data.backup

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path

class AvatarBackupFilesTest {
    @Test
    fun `共享照片只收一次且容量不足明确失败`() = withRoot { root ->
        val photo = Files.write(root.resolve("photo.img"), byteArrayOf(1, 2, 3))
        val payload = payload(photo.toString())
        val preferences = BackupPreferenceSnapshot(listOf(BackupPreferenceEntry("settings", "user_avatar", photo.toString())), emptySet())
        val snapshot = AvatarBackupFiles.snapshot(payload, preferences, root, 3, 1, ::read)
        assertEquals(1, snapshot.files.size)
        val reference = snapshot.payload.tables.getValue("agents").single().getValue("avatar_path")
        assertEquals(JsonPrimitive(snapshot.preferences.entries.single().value), reference)
        assertThrows<BackupValidationException> { AvatarBackupFiles.snapshot(payload, preferences, root, 2, 1, ::read) }
    }

    @Test
    fun `不稳定核心头像读取失败且导入只接受受约束引用`() = withRoot { root ->
        val photo = Files.write(root.resolve("photo.img"), byteArrayOf(1))
        val empty = BackupPreferenceSnapshot(emptyList(), emptySet())
        assertThrows<BackupValidationException> {
            AvatarBackupFiles.snapshot(payload(photo.toString()), empty, root, 10, 10) { _, _, _ ->
                throw BackupValidationException("读取中发生变化")
            }
        }
        assertThrows<BackupValidationException> {
            AvatarBackupFiles.planRestore(payload("avatar:missing"), empty, emptyMap(), root, true)
        }
        assertThrows<BackupValidationException> {
            AvatarBackupFiles.planRestore(payload("avatar:../escape"), empty, emptyMap(), root, true)
        }
        assertThrows<BackupValidationException> {
            AvatarBackupFiles.planRestore(payload(null), empty, mapOf("unused" to byteArrayOf(1)), root, true)
        }
    }

    @Test
    fun `单文件总量标识和旧格式校验`() {
        assertThrows<BackupValidationException> { AvatarBackupFiles.validateSizes(mapOf("photo" to AvatarBackupFiles.MAX_FILE_BYTES + 1)) }
        assertThrows<BackupValidationException> { AvatarBackupFiles.validateSizes((1..5).associate { "photo$it" to AvatarBackupFiles.MAX_FILE_BYTES }) }
        assertThrows<BackupValidationException> { AvatarBackupFiles.validateSizes(mapOf("../escape" to 1L)) }
        val empty = BackupPreferenceSnapshot(emptyList(), emptySet())
        val legacy = AvatarBackupFiles.planRestore(payload("/old/install/photo.img"), empty, emptyMap(), null, false)
        assertEquals(JsonNull, legacy.payload.tables.getValue("agents").single()["avatar_path"])
        assertThrows<BackupValidationException> {
            AvatarBackupFiles.planRestore(payload(null), empty, mapOf("photo" to byteArrayOf(1)), null, false)
        }
    }

    private fun payload(path: String?) = DatabaseBackupPayload(DATABASE_BACKUP_SCHEMA_VERSION, mapOf(
        "agents" to listOf(JsonObject(mapOf("id" to JsonPrimitive("agent"), "avatar_path" to (path?.let(::JsonPrimitive) ?: JsonNull)))),
    ))
    private fun read(path: Path, size: Long, id: String): Pair<ByteArray, String> = Files.readAllBytes(path) to ""
    private fun withRoot(block: (Path) -> Unit) {
        val root = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".avatar-files-test")
        try { block(root) } finally { root.toFile().deleteRecursively() }
    }
}
