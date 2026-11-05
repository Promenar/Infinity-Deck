package com.promenar.infinity.data.local.db.recovery

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val DUAL_DATABASE_RECOVERY_FORMAT = 1
internal const val LEGACY_DATABASE_NAME = "infinity.db"
internal const val CURRENT_DATABASE_NAME = "infinity_v2.db"
internal const val PUBLIC_V17_IDENTITY = "3311ec5f07e8df42c02fd09163c49f6e"
internal const val PUBLIC_V2_IDENTITY = "7777303c63145d5bbb9b161b38f94495"
internal const val INTERNAL_V5_IDENTITY = "3c6ffe1572c71bac7e866a33388a5a3b"
internal const val CURRENT_V19_IDENTITY = "694f9ee43b8b6fbca993a11d4a789f64"
internal const val CURRENT_V18_IDENTITY = "c32f59706ea482fd5697681569c6fdb5"
internal const val CURRENT_V20_IDENTITY = "4cbc9d57e418ec09a3469f1fcdc6bac6"
internal const val CURRENT_V21_IDENTITY = com.promenar.infinity.data.local.db.INFINITY_SCHEMA_V21_IDENTITY_HASH
internal const val CURRENT_V22_IDENTITY = com.promenar.infinity.data.local.db.INFINITY_SCHEMA_V22_IDENTITY_HASH
internal const val CURRENT_V23_IDENTITY = com.promenar.infinity.data.local.db.INFINITY_SCHEMA_V23_IDENTITY_HASH
internal const val CURRENT_V26_IDENTITY = com.promenar.infinity.data.local.db.INFINITY_SCHEMA_V26_IDENTITY_HASH
internal const val CURRENT_V27_IDENTITY = com.promenar.infinity.data.local.db.INFINITY_SCHEMA_V27_IDENTITY_HASH
internal const val CURRENT_V28_IDENTITY = com.promenar.infinity.data.local.db.INFINITY_SCHEMA_V28_IDENTITY_HASH
internal const val CURRENT_V29_IDENTITY = com.promenar.infinity.data.local.db.INFINITY_SCHEMA_V29_IDENTITY_HASH
internal const val CURRENT_V32_IDENTITY = com.promenar.infinity.data.local.db.INFINITY_SCHEMA_V32_IDENTITY_HASH
internal const val CURRENT_V33_IDENTITY = com.promenar.infinity.data.local.db.INFINITY_SCHEMA_V33_IDENTITY_HASH
internal const val CURRENT_V31_IDENTITY = com.promenar.infinity.data.local.db.INFINITY_SCHEMA_V31_IDENTITY_HASH
internal const val CURRENT_V30_IDENTITY = com.promenar.infinity.data.local.db.INFINITY_SCHEMA_V30_IDENTITY_HASH
internal const val CURRENT_V25_IDENTITY = com.promenar.infinity.data.local.db.INFINITY_SCHEMA_V25_IDENTITY_HASH
internal const val CURRENT_V24_IDENTITY = com.promenar.infinity.data.local.db.INFINITY_SCHEMA_V24_IDENTITY_HASH
internal const val DUAL_DATABASE_RECOVERY_INDEX_HOLD = "dual-database-recovery-hold-v1"
internal const val RAG_WORKSPACE_SESSION_ID = "__infinity_rag_workspace__"
internal const val RAG_SYSTEM_AGENT_ID = "__system__"

internal fun isTrustedCurrentRoomSchema(version: Int, identity: String): Boolean =
    (version == 18 && identity == CURRENT_V18_IDENTITY) ||
        (version == 19 && identity == CURRENT_V19_IDENTITY) ||
        (version == 20 && identity == CURRENT_V20_IDENTITY) ||
        (version == 21 && identity == CURRENT_V21_IDENTITY) ||
        (version == 22 && identity == CURRENT_V22_IDENTITY) ||
        (version == 23 && identity == CURRENT_V23_IDENTITY) ||
        (version == 24 && identity == CURRENT_V24_IDENTITY) ||
        (version == 25 && identity == CURRENT_V25_IDENTITY) ||
        (version == 26 && identity == CURRENT_V26_IDENTITY) ||
        (version == 27 && identity == CURRENT_V27_IDENTITY) ||
        (version == 28 && identity == CURRENT_V28_IDENTITY) ||
        (version == 29 && identity == CURRENT_V29_IDENTITY) ||
        (version == 30 && identity == CURRENT_V30_IDENTITY) ||
        (version == 31 && identity == CURRENT_V31_IDENTITY) ||
        (version == 32 && identity == CURRENT_V32_IDENTITY) ||
        (version == 33 && identity == CURRENT_V33_IDENTITY)

@Serializable
enum class DualDatabaseRecoveryStage {
    SNAPSHOTTED,
    STAGED,
    FILES_PUBLISHED,
    DB_PUBLISHING,
    DB_PUBLISHED,
    COMPLETE,
}

@Serializable
data class RecoveryFileFingerprint(
    val relativePath: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class RecoveryDatabaseSource(
    val role: String,
    val databaseName: String,
    val userVersion: Int,
    val roomIdentity: String,
    val files: List<RecoveryFileFingerprint>,
    val manifestSha256: String,
)

@Serializable
data class RecoveryMoveReceipt(
    val sourceRelativePath: String,
    val retainedRelativePath: String,
    val sha256: String,
    val size: Long,
    val sourceFileKey: String,
    val completed: Boolean,
)

@Serializable
data class RecoveryTreePublication(
    val role: String,
    val activeWorkspace: Boolean = false,
    val assetBundle: Boolean = false,
    val ownerSessionId: String? = null,
    val snapshotTransactionId: String,
    val sourcePath: String,
    val sourceRootIdentity: String,
    val sourceTreeSha256: String,
    val selectedRelativeFiles: List<String> = emptyList(),
    val targetRelativePath: String,
    val targetTreeSha256: String? = null,
    val targetRootIdentity: String? = null,
    val published: Boolean = false,
)

@Serializable
data class RecoveryTreeArchiveAttempt(
    val role: String,
    val sourcePath: String,
    val targetRelativePath: String,
    val activeWorkspace: Boolean,
    val assetBundle: Boolean,
    val ownerSessionId: String? = null,
    val selectedRelativeFiles: List<String> = emptyList(),
    val snapshotTransactionId: String,
)

@Serializable
data class DualDatabaseRecoveryRecord(
    val formatVersion: Int = DUAL_DATABASE_RECOVERY_FORMAT,
    val transactionId: String,
    val stage: DualDatabaseRecoveryStage,
    val legacy: RecoveryDatabaseSource,
    val current: RecoveryDatabaseSource,
    val snapshotManifestSha256: String,
    val workingAttemptId: String? = null,
    val workingLegacySha256: String? = null,
    val workingCurrentSha256: String? = null,
    val candidateRelativePath: String? = null,
    val candidateSha256: String? = null,
    val candidateRoomIdentity: String? = null,
    val candidateCommitMarker: String? = null,
    val idMappingSha256: String? = null,
    val unresolvedReferenceReportSha256: String? = null,
    val unresolvedUniqueData: Boolean = false,
    val treePublications: List<RecoveryTreePublication> = emptyList(),
    val treeArchiveAttempts: List<RecoveryTreeArchiveAttempt> = emptyList(),
    val treeArchivesComplete: Boolean = false,
    val databaseMoves: List<RecoveryMoveReceipt> = emptyList(),
    val writerGateOpened: Boolean = false,
)

data class DualDatabaseRecoveryOffer(
    val offerToken: String,
    val legacyVersion: Int,
    val currentVersion: Int,
    val summary: String,
)

sealed interface DualDatabaseBootstrapResult {
    data object Normal : DualDatabaseBootstrapResult
    data class RecoveryRequired(val offer: DualDatabaseRecoveryOffer) : DualDatabaseBootstrapResult
    data class Completed(val transactionId: String) : DualDatabaseBootstrapResult
    data class Blocked(val detail: String) : DualDatabaseBootstrapResult
}

/** 旧侧所有标识符始终进入来源命名空间；不依赖碰撞与否，保证重试得到相同映射。 */
internal object RecoveryStableId {
    fun map(table: String, column: String, oldValue: String): String {
        require(table.matches(Regex("[a-z0-9_]+")) && column.matches(Regex("[a-z0-9_]+")))
        require(oldValue.isNotEmpty())
        return UUID.nameUUIDFromBytes(
            "infinity-dual-db-v1\u0000legacy17\u0000$table\u0000$column\u0000$oldValue"
                .toByteArray(StandardCharsets.UTF_8),
        ).toString()
    }
}

internal fun recoverySha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun recoveryManifestSha256(files: List<RecoveryFileFingerprint>): String = recoverySha256(
    files.sortedBy(RecoveryFileFingerprint::relativePath).joinToString("\n") {
        "${it.relativePath}\t${it.size}\t${it.sha256}"
    }.toByteArray(StandardCharsets.UTF_8),
)

internal fun rewriteWorkspacePaths(database: java.nio.file.Path, replacements: Map<String, String>) {
    SQLiteDatabase.openDatabase(database.toString(), null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
        sqlite.beginTransaction()
        try {
            val ordered = replacements.entries.sortedByDescending { it.key.length }
            rewriteExactColumn(sqlite, "sessions", "workspace_path", ordered)
            rewriteExactColumn(sqlite, "workspace_files", "physical_root_path", ordered)
            listOf(
                "file_versions" to "content_path",
                "agents" to "avatar_path",
                "attachments" to "uri",
                "attachments" to "local_uri",
                "artifacts" to "workspace_path",
                "messages" to "images",
            ).forEach { (table, column) -> rewritePathColumn(sqlite, table, column, ordered) }
            rewriteCurrentStructuredPaths(sqlite, ordered)
            sqlite.setTransactionSuccessful()
        } finally { sqlite.endTransaction() }
    }
}

/** 仅供已通过应用数据根身份认证的系统别名使用；正式恢复root发布仍使用上面的精确映射。 */
internal fun rewriteTrustedAliasPaths(database: java.nio.file.Path, aliases: Map<String, String>) {
    require(aliases.isNotEmpty() && aliases.all { (alias, declared) ->
        alias.isNotBlank() && declared.isNotBlank() && alias != declared
    })
    val replacements = aliases.entries.sortedByDescending { it.key.length }
    SQLiteDatabase.openDatabase(database.toString(), null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
        sqlite.beginTransaction()
        try {
            listOf(
                "sessions" to "workspace_path",
                "workspace_files" to "physical_root_path",
                "file_versions" to "content_path",
                "agents" to "avatar_path",
                "attachments" to "uri",
                "attachments" to "local_uri",
                "artifacts" to "workspace_path",
                "messages" to "images",
            ).forEach { (table, column) -> rewritePathColumn(sqlite, table, column, replacements) }
            rewriteCurrentStructuredPaths(sqlite, replacements)
            sqlite.setTransactionSuccessful()
        } finally { sqlite.endTransaction() }
    }
}

private fun rewriteCurrentStructuredPaths(
    sqlite: SQLiteDatabase,
    replacements: List<Map.Entry<String, String>>,
) {
    fun rewrite(value: String): String {
        val prefix = if (value.startsWith("file://")) "file://" else ""
        val path = value.removePrefix(prefix)
        val match = replacements.firstOrNull { (old) -> path == old || path.startsWith("$old/") }
            ?: return value
        return prefix + match.value + path.removePrefix(match.key)
    }
    fun visit(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map(::visit))
        is JsonObject -> JsonObject(element.mapValues { visit(it.value) })
        is JsonPrimitive -> if (element.isString) JsonPrimitive(rewrite(element.content)) else element
    }
    val columns = listOf("files", "user_images", "legacy_attachments")
    sqlite.rawQuery("SELECT id,${columns.joinToString(",")} FROM messages", null).use { cursor ->
        while (cursor.moveToNext()) {
            val id = cursor.getString(0)
            columns.forEachIndexed { index, column ->
                if (!cursor.isNull(index + 1)) {
                    val raw = cursor.getString(index + 1)
                    val rewritten = runCatching { visit(Json.parseToJsonElement(raw)).toString() }.getOrDefault(raw)
                    if (rewritten != raw) sqlite.execSQL(
                        "UPDATE messages SET $column=? WHERE id=?",
                        arrayOf(rewritten, id),
                    )
                }
            }
        }
    }
}

private fun rewriteExactColumn(
    sqlite: SQLiteDatabase,
    table: String,
    column: String,
    replacements: List<Map.Entry<String, String>>,
) {
    if (replacements.isEmpty()) return
    val sql = buildString {
        append("UPDATE $table SET $column=CASE")
        replacements.forEach { append(" WHEN $column=? THEN ?") }
        append(" ELSE $column END")
    }
    sqlite.execSQL(sql, replacements.flatMap { listOf(it.key, it.value) }.toTypedArray())
}

private fun rewritePathColumn(
    sqlite: SQLiteDatabase,
    table: String,
    column: String,
    replacements: List<Map.Entry<String, String>>,
) {
    if (replacements.isEmpty()) return
    val plain = mutableListOf<Any>()
    val plainSql = buildString {
        append("UPDATE $table SET $column=CASE")
        replacements.forEach { (old, next) ->
            append(" WHEN $column=? THEN ?")
            plain += old; plain += next
            append(" WHEN substr($column,1,length(?) + 1)=? || '/' THEN ? || substr($column,length(?) + 1)")
            plain += old; plain += old; plain += next; plain += old
        }
        append(" ELSE $column END")
    }
    sqlite.execSQL(plainSql, plain.toTypedArray())
    val file = mutableListOf<Any>()
    val fileSql = buildString {
        append("UPDATE $table SET $column=CASE")
        replacements.forEach { (old, next) ->
            append(" WHEN $column='file://' || ? THEN 'file://' || ?")
            file += old; file += next
            append(" WHEN substr($column,1,length(?) + 8)='file://' || ? || '/' " +
                "THEN 'file://' || ? || substr($column,length(?) + 8)")
            file += old; file += old; file += next; file += old
        }
        append(" ELSE $column END")
    }
    sqlite.execSQL(fileSql, file.toTypedArray())
}
