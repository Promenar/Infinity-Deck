package com.promenar.infinity.data.local.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption

internal enum class LegacyDatabasePromotionResult {
    NoLegacyDatabase,
    CurrentDatabaseExists,
    Promoted,
}

/**
 * 接管公开 v0.1 使用的数据库。先通过 SQLite 恢复 hot journal、截断 WAL 并校验主库，
 * 再复制到临时文件并原子发布；历史主库保留为只读回退源，不会静默用空库覆盖数据。
 */
internal object LegacyDatabasePromoter {
    private const val LEGACY_NAME = "infinity.db"
    private const val CURRENT_NAME = "infinity_v2.db"
    private const val PUBLISHED_V17_IDENTITY_HASH = "3311ec5f07e8df42c02fd09163c49f6e"
    private const val CURRENT_V5_IDENTITY_HASH = "3c6ffe1572c71bac7e866a33388a5a3b"
    private const val VALIDATION_DIRECTORY_PREFIX = ".infinity-db-validation-"
    private val currentSidecarSuffixes = listOf("-wal", "-shm", "-journal")
    private val validationCopyLock = Any()

    fun promote(context: Context): LegacyDatabasePromotionResult {
        val databaseDirectory = requireNotNull(context.getDatabasePath(CURRENT_NAME).parentFile).toPath()
        val legacyMain = databaseDirectory.resolve(LEGACY_NAME)
        val currentMain = databaseDirectory.resolve(CURRENT_NAME)
        if (Files.exists(currentMain) && Files.exists(legacyMain)) {
            val currentVersion = validateCurrentDatabase(currentMain)
            if (currentVersion in setOf(18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39)) {
                return LegacyDatabasePromotionResult.CurrentDatabaseExists
            }
            validatePublishedV17WithoutSourceWrites(legacyMain)
            if (currentVersion == 5) {
                throw IOException("同时检测到公开 v17 与当前 v5 数据库，无法自动判定权威数据源")
            }
            return LegacyDatabasePromotionResult.CurrentDatabaseExists
        }
        return promote(databaseDirectory, ::checkpointAndValidatePublishedV17)
    }

    @Throws(IOException::class)
    fun promote(
        databaseDirectory: Path,
        prepareLegacyDatabase: (Path) -> Unit,
    ): LegacyDatabasePromotionResult {
        val legacyMain = databaseDirectory.resolve(LEGACY_NAME)
        val currentMain = databaseDirectory.resolve(CURRENT_NAME)
        if (Files.exists(currentMain)) return LegacyDatabasePromotionResult.CurrentDatabaseExists
        if (!Files.exists(legacyMain)) return LegacyDatabasePromotionResult.NoLegacyDatabase
        if (!Files.isRegularFile(legacyMain, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("历史数据库主文件不是普通文件")
        }

        currentSidecarSuffixes.forEach { suffix ->
            if (Files.exists(databaseDirectory.resolve(CURRENT_NAME + suffix))) {
                throw IOException("当前数据库缺少主库但存在未知 sidecar: $suffix")
            }
        }

        prepareLegacyDatabase(legacyMain)
        if (!Files.isRegularFile(legacyMain, LinkOption.NOFOLLOW_LINKS) || Files.exists(currentMain)) {
            throw IOException("历史数据库准备期间文件状态发生变化")
        }
        publishVerifiedCopy(legacyMain, currentMain)
        return LegacyDatabasePromotionResult.Promoted
    }

    private fun checkpointAndValidatePublishedV17(databasePath: Path) {
        val sqlite = SQLiteDatabase.openDatabase(
            databasePath.toString(),
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            sqlite.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getInt(0) != 0) {
                    throw IOException("历史数据库 WAL 无法完整 checkpoint")
                }
            }
            sqlite.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(0) != "ok") {
                    throw IOException("历史数据库完整性校验失败")
                }
            }
            if (sqlite.version != 17 ||
                sqlite.roomIdentityHash() != PUBLISHED_V17_IDENTITY_HASH ||
                !sqlite.hasColumn("messages", "attachments") ||
                sqlite.hasColumn("messages", "legacy_attachments") ||
                sqlite.hasColumn("agents", "name_customized") ||
                sqlite.hasColumn("workspace_files", "workspace_root_uuid") ||
                sqlite.hasColumn("vectorization_tasks", "workspace_root_uuid")
            ) {
                throw IOException("历史数据库不符合公开 v17 结构合同")
            }
        } finally {
            sqlite.close()
        }
    }

    private fun validatePublishedV17WithoutSourceWrites(databasePath: Path) {
        withWritableValidationCopy(databasePath) { sqlite ->
            sqlite.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(0) != "ok") {
                    throw IOException("历史数据库完整性校验失败")
                }
            }
            if (sqlite.version != 17 ||
                sqlite.roomIdentityHash() != PUBLISHED_V17_IDENTITY_HASH ||
                !sqlite.hasColumn("messages", "attachments") ||
                sqlite.hasColumn("messages", "legacy_attachments") ||
                sqlite.hasColumn("agents", "name_customized") ||
                sqlite.hasColumn("workspace_files", "workspace_root_uuid") ||
                sqlite.hasColumn("vectorization_tasks", "workspace_root_uuid")
            ) {
                throw IOException("历史数据库不符合公开 v17 结构合同")
            }
        }
    }

    private fun validateCurrentDatabase(databasePath: Path): Int {
        return withWritableValidationCopy(databasePath) { sqlite ->
            sqlite.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(0) != "ok") {
                    throw IOException("当前数据库完整性校验失败，拒绝遮蔽历史库")
                }
            }
            val version = sqlite.version
            val identity = sqlite.roomIdentityHash()
            val commonContract = sqlite.hasColumn("agents", "name_customized") &&
                sqlite.hasColumn("workspace_files", "workspace_root_uuid") &&
                sqlite.hasColumn("vectorization_tasks", "workspace_root_uuid") &&
                !sqlite.hasColumn("messages", "attachments")
            val exactContract = when (version) {
                5 -> identity == CURRENT_V5_IDENTITY_HASH && commonContract &&
                    !sqlite.hasColumn("messages", "legacy_attachments")
                18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39 -> sqlite.hasCompleteCurrentRoomSchema(version, identity)
                else -> false
            }
            if (!exactContract) {
                throw IOException("当前数据库版本无法证明可安全遮蔽历史库")
            }
            version
        }
    }

    /**
     * Android 的 SQLite/FTS4 会在只读连接执行 quick_check 时尝试写入内部校验状态，
     * 进而把健康数据库误报为只读写失败。校验只在一次性私有副本上以可写方式运行，
     * 原始主库、WAL 与 hot journal 保持零写入，满足双库歧义场景的 fail-closed 合同。
     */
    private inline fun <T> withWritableValidationCopy(
        sourceMain: Path,
        block: (SQLiteDatabase) -> T,
    ): T = synchronized(validationCopyLock) {
        cleanupStaleValidationCopies(sourceMain.parent)
        if (!Files.isRegularFile(sourceMain, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("待校验数据库主文件不是普通文件")
        }
        val validationDirectory = Files.createTempDirectory(sourceMain.parent, VALIDATION_DIRECTORY_PREFIX)
        val validationMain = validationDirectory.resolve("validation.db")
        try {
            Files.copy(sourceMain, validationMain, StandardCopyOption.COPY_ATTRIBUTES)
            listOf("-wal", "-journal").forEach { suffix ->
                val sourceSidecar = sourceMain.resolveSibling(sourceMain.fileName.toString() + suffix)
                if (Files.exists(sourceSidecar, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(sourceSidecar, LinkOption.NOFOLLOW_LINKS)) {
                        throw IOException("待校验数据库 sidecar 不是普通文件: $suffix")
                    }
                    Files.copy(
                        sourceSidecar,
                        validationMain.resolveSibling(validationMain.fileName.toString() + suffix),
                        StandardCopyOption.COPY_ATTRIBUTES,
                    )
                }
            }
            SQLiteDatabase.openDatabase(
                validationMain.toString(),
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use(block)
        } finally {
            validationDirectory.toFile().deleteRecursively()
            if (Files.exists(validationDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("数据库验证副本清理失败")
            }
        }
    }

    private fun cleanupStaleValidationCopies(databaseDirectory: Path) {
        val staleCopies = databaseDirectory.toFile().listFiles { candidate ->
            candidate.name.startsWith(VALIDATION_DIRECTORY_PREFIX)
        }.orEmpty()
        staleCopies.forEach { candidate ->
            if (Files.isSymbolicLink(candidate.toPath())) {
                Files.deleteIfExists(candidate.toPath())
            } else {
                candidate.deleteRecursively()
            }
            if (Files.exists(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("历史数据库验证副本清理失败")
            }
        }
    }

    private fun SQLiteDatabase.hasColumn(table: String, column: String): Boolean =
        rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex < 0) return@use false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return@use true
            }
            false
        }

    private fun SQLiteDatabase.roomIdentityHash(): String? =
        rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun publishVerifiedCopy(source: Path, target: Path) {
        val temporary = Files.createTempFile(target.parent, "$CURRENT_NAME.", ".promoting")
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel -> channel.force(true) }
            moveAtomicallyWhenSupported(temporary, target)
            FileChannel.open(target.parent, StandardOpenOption.READ).use { channel -> channel.force(true) }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun moveAtomicallyWhenSupported(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }
}
