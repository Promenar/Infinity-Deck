package com.promenar.infinity.data.backup

import com.promenar.infinity.data.security.SecretId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 可移植数据库备份结构版本，独立于 Room 数据库版本。 */
internal const val DATABASE_BACKUP_SCHEMA_VERSION = 13

enum class BackupContent {
    DATABASE,
    PREFERENCES,
    FILES,
    SECRETS,
}

data class BackupOptions(
    val content: Set<BackupContent>,
    val includeSecrets: Boolean = false,
    val password: CharArray? = null,
    val encryptPackage: Boolean = false,
)

/** 面向 UI/CLI 的规范全量导出选项；密钥默认关闭且必须双次确认密码。 */
data class BackupExportOptions(
    val includeSecrets: Boolean = false,
    val password: CharArray? = null,
    val passwordConfirmation: CharArray? = null,
    val encryptPackage: Boolean = false,
)

data class BackupSnapshot(
    val database: ByteArray = ByteArray(0),
    val preferences: ByteArray = ByteArray(0),
    val files: Map<String, ByteArray> = emptyMap(),
    val secrets: Map<SecretId, ByteArray> = emptyMap(),
    val databaseSchemaVersion: Int,
    val appVersion: String,
    val createdAt: Long = System.currentTimeMillis(),
    val skills: SkillBackupPartition? = null,
    val avatars: Map<String, ByteArray> = emptyMap(),
)

class ValidatedBackup(
    val manifest: BackupManifest,
    val database: ByteArray = ByteArray(0),
    val preferences: ByteArray = ByteArray(0),
    val files: Map<String, ByteArray> = emptyMap(),
    val secrets: Map<SecretId, ByteArray> = emptyMap(),
    val skills: SkillBackupPartition? = null,
    val avatars: Map<String, ByteArray> = emptyMap(),
) : AutoCloseable {
    private val consumed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    internal fun beginConsumption() {
        if (!consumed.compareAndSet(false, true)) throw BackupValidationException("ValidatedBackup 已被消费")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        database.fill(0)
        preferences.fill(0)
        files.values.forEach { it.fill(0) }
        secrets.values.forEach { it.fill(0) }
        skills?.wipe()
        avatars.values.forEach { it.fill(0) }
    }
}

@Serializable
data class BackupManifest(
    val formatVersion: Int,
    val databaseSchemaVersion: Int,
    val appVersion: String,
    val createdAt: Long,
    val entries: List<BackupManifestEntry>,
    val encrypted: Boolean,
    val containsSecrets: Boolean,
    val kdf: BackupKdfMetadata? = null,
    val skills: SkillBackupMetadata? = null,
)

data class SkillBackupPartition(
    val files: Map<String, ByteArray>,
    val metadata: SkillBackupMetadata,
) {
    internal fun wipe() = files.values.forEach { it.fill(0) }
}

@Serializable
data class SkillBackupMetadata(
    val directories: List<String> = emptyList(),
    val summary: SkillBackupSummary = SkillBackupSummary(),
)

@Serializable
data class SkillBackupSummary(
    val skillCount: Int = 0,
    val skippedSymbolicLinks: Int = 0,
    val skippedGitRepositories: List<SkippedSkillGitRepository> = emptyList(),
)

@Serializable
data class SkippedSkillGitRepository(
    val relativePath: String,
    val remotes: List<SkillGitRemote> = emptyList(),
    val branch: String? = null,
    val commit: String? = null,
)

@Serializable
data class SkillGitRemote(val name: String, val url: String, val push: Boolean = false)

data class BackupExportResult(val packageBytes: ByteArray, val skills: SkillBackupSummary?)
data class BackupRestoreResult(val skills: SkillBackupSummary?)

@Serializable
data class BackupManifestEntry(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
)

@Serializable
data class BackupKdfMetadata(
    val algorithm: String,
    val saltBase64: String,
    val iterations: Int,
    val keySizeBits: Int,
)

@Serializable
internal data class SecretBackupEntry(
    val id: String,
    val valueBase64: String,
)

object BackupPackageLimits {
    const val MAX_TOTAL_BYTES: Long = 512L * 1024 * 1024
    const val MAX_ENTRY_BYTES: Long = 128L * 1024 * 1024
    const val MAX_ENTRIES: Int = 10_000
    internal const val MAX_MANIFEST_BYTES: Long = 4L * 1024 * 1024
    // ByteArray 初版按 16 MiB 限制内容物化；已考虑加密输入、解密 ZIP、JSON/文件结果等多副本峰值。
    // 512 MiB 只保留为归档协议上限；未来支持更大包时必须升级为流式 spool，不能放宽此常量硬扛。
    const val MAX_IN_MEMORY_BYTES: Long = 16L * 1024 * 1024
}

class BackupValidationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

interface BackupDataSource {
    suspend fun snapshot(content: Set<BackupContent>): BackupSnapshot
    suspend fun restore(validated: ValidatedBackup)
    suspend fun restore(validated: ValidatedBackup, operationId: String) = restore(validated)
    suspend fun restoreWithResult(validated: ValidatedBackup, operationId: String): BackupRestoreResult {
        val result = BackupRestoreResult(validated.skills?.metadata?.summary)
        restore(validated, operationId)
        return result
    }
    suspend fun hasCompletedRestore(operationId: String): Boolean = false
    /**
     * 失败的 restore 是否已确定未改动现有数据：没有残留 restore journal，且该 operationId 没有完成回执。
     * 只有能证明此事实的实现才可返回 true；默认 false 让调用方保持 fail closed。
     */
    suspend fun isRestoreSettledWithoutApplying(operationId: String): Boolean = false
    suspend fun recoverInterruptedRestore()
}

internal fun BackupSnapshot.wipe() {
    database.fill(0)
    preferences.fill(0)
    files.values.forEach { it.fill(0) }
    secrets.values.forEach { it.fill(0) }
    skills?.wipe()
    avatars.values.forEach { it.fill(0) }
}

@Serializable
data class BackupPreferenceSnapshot(
    val entries: List<BackupPreferenceEntry>,
    val providerIds: Set<String>,
)

@Serializable
data class BackupPreferenceEntry(
    val namespace: String,
    val key: String,
    val value: String,
    val type: PreferenceValueType = PreferenceValueType.STRING,
)

@Serializable
enum class PreferenceValueType {
    STRING,
    STRING_SET,
    BOOLEAN,
    INT,
    LONG,
    FLOAT,
}

@Serializable
internal data class DatabaseBackupPayload(
    val schemaVersion: Int,
    val tables: Map<String, List<JsonObject>>,
)
