package com.promenar.infinity.data.backup

import com.promenar.infinity.data.security.SecretId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 可移植数据库备份结构版本，独立于 Room 数据库版本。 */
internal const val DATABASE_BACKUP_SCHEMA_VERSION = 14

enum class BackupContent {
    DATABASE,
    PREFERENCES,
    FILES,
    SECRETS,
}

@Serializable
enum class BackupLayer {
    CORE, KNOWLEDGE, SESSIONS, WORKSPACE_FILES, SECRETS;
    companion object {
        val DEFAULT = setOf(CORE, KNOWLEDGE, SESSIONS)
        val OPTIONAL = setOf(KNOWLEDGE, SESSIONS, WORKSPACE_FILES, SECRETS)
    }
}

@Serializable
data class BackupLayerStats(val entryCount: Int = 0, val sizeBytes: Long = 0)
@Serializable
data class BackupSkippedItem(val layer: BackupLayer, val name: String, val reason: String) {
    companion object {
        /** 原因写进清单，界面按这两个已知值显示本地化文案，其余原样显示。 */
        const val REASON_FILE_UNAVAILABLE = "文件缺失、内容变化或无法读取"
        const val REASON_AVATAR_DANGLING = "头像文件不存在或不在头像目录中，已清除该引用"
    }
}
@Serializable
data class BackupDegradedReference(val category: String, val count: Int)
@Serializable
data class BackupResultSummary(
    val layers: Map<BackupLayer, BackupLayerStats> = emptyMap(),
    val skipped: List<BackupSkippedItem> = emptyList(),
    val degraded: List<BackupDegradedReference> = emptyList(),
    val skills: SkillBackupSummary? = null,
)

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
    val layers: Set<BackupLayer> = BackupLayer.DEFAULT,
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
    val layers: Set<BackupLayer>? = null,
    val streamEntries: List<BackupSourceEntry> = emptyList(),
    val skipped: List<BackupSkippedItem> = emptyList(),
    val ownedDirectory: java.nio.file.Path? = null,
)

class ValidatedBackup(
    val manifest: BackupManifest,
    val database: ByteArray = ByteArray(0),
    val preferences: ByteArray = ByteArray(0),
    val files: Map<String, ByteArray> = emptyMap(),
    val secrets: Map<SecretId, ByteArray> = emptyMap(),
    val skills: SkillBackupPartition? = null,
    val avatars: Map<String, ByteArray> = emptyMap(),
    val blobs: Map<String, BackupBlob> = emptyMap(),
    val ownedDirectory: java.nio.file.Path? = null,
) : AutoCloseable {
    private val consumed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    internal var restoreSummary = BackupResultSummary(manifest.layers.orEmpty(), manifest.skipped, skills = manifest.skills?.summary)

    internal fun fileBlobs() = files.mapValues { BackupBlob.Bytes(it.value) } + blobs.filterKeys { it.startsWith("files/") }.mapKeys { it.key.removePrefix("files/") }
    internal fun avatarBlobs() = avatars.mapValues { BackupBlob.Bytes(it.value) } + blobs.filterKeys { it.startsWith("avatars/") }.mapKeys { it.key.removePrefix("avatars/") }

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
        ownedDirectory?.let(BackupStreams::deleteTree)
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
    val layers: Map<BackupLayer, BackupLayerStats>? = null,
    val skipped: List<BackupSkippedItem> = emptyList(),
)

data class SkillBackupPartition(
    val files: Map<String, ByteArray>,
    val metadata: SkillBackupMetadata,
    val blobs: Map<String, BackupBlob> = emptyMap(),
) {
    internal fun allBlobs(): Map<String, BackupBlob> = files.mapValues { BackupBlob.Bytes(it.value) } + blobs
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

data class BackupExportResult(val packageBytes: ByteArray, val skills: SkillBackupSummary?, val summary: BackupResultSummary = BackupResultSummary(skills = skills))
data class BackupRestoreResult(val skills: SkillBackupSummary?, val summary: BackupResultSummary = BackupResultSummary(skills = skills)) {
    /** 是否需要在启动后告知：分层包总要说明替换范围；旧包只在技能有待处理事项时提示。 */
    val needsNotice: Boolean
        get() = summary.layers.isNotEmpty() || skills?.let { it.skippedGitRepositories.isNotEmpty() || it.skippedSymbolicLinks > 0 } == true
}

@Serializable
data class BackupManifestEntry(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
    val layer: BackupLayer? = null,
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
    // 旧版整包接口保留物化预算；应用路径通过磁盘暂存和固定缓冲处理 v3。
    const val MAX_IN_MEMORY_BYTES: Long = 16L * 1024 * 1024
}

/** 用户可以自行处理的备份失败分类；其它失败只显示通用文案。 */
enum class BackupFailureReason {
    /** 内容超过协议容量或旧版物化接口的预算。 */
    CAPACITY_EXCEEDED,
    /** 会话文件缺失、被替换或与登记的大小、摘要不一致。 */
    WORKSPACE_FILE_MISMATCH,
    /** 技能目录含特殊文件、不支持的文件名或超过技能分区上限。 */
    SKILL_CONTENT_UNSUPPORTED,
}

enum class BackupFailureDetail { INSUFFICIENT_STORAGE, CORE_CONTENT_UNREADABLE }

class BackupValidationException(
    message: String,
    cause: Throwable? = null,
    val reason: BackupFailureReason? = null,
    val detail: BackupFailureDetail? = null,
) : IllegalArgumentException(message, cause)

/** 沿原因链取第一个可归类的失败；内存耗尽按容量超限处理。 */
fun Throwable.backupFailureReason(): BackupFailureReason? =
    generateSequence(this) { it.cause }.take(8).firstNotNullOfOrNull { error ->
        (error as? BackupValidationException)?.reason
            ?: if (error is OutOfMemoryError) BackupFailureReason.CAPACITY_EXCEEDED else null
    }

interface BackupDataSource {
    suspend fun snapshot(content: Set<BackupContent>): BackupSnapshot
    suspend fun estimate(): Map<BackupLayer, BackupLayerStats> = emptyMap()
    suspend fun snapshotTo(layers: Set<BackupLayer>, directory: java.nio.file.Path): BackupSnapshot =
        snapshot(setOf(BackupContent.DATABASE, BackupContent.PREFERENCES, BackupContent.FILES) +
            if (BackupLayer.SECRETS in layers) setOf(BackupContent.SECRETS) else emptySet())
    suspend fun validateStaged(validated: ValidatedBackup) = Unit
    suspend fun restore(validated: ValidatedBackup)
    suspend fun restore(validated: ValidatedBackup, operationId: String) = restore(validated)
    suspend fun restoreWithResult(validated: ValidatedBackup, operationId: String): BackupRestoreResult {
        val result = BackupRestoreResult(validated.skills?.metadata?.summary)
        restore(validated, operationId)
        return result
    }
    suspend fun hasCompletedRestore(operationId: String): Boolean = false
    suspend fun completedRestoreSummary(operationId: String): BackupResultSummary? = null
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
    ownedDirectory?.let(BackupStreams::deleteTree)
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
