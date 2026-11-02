package com.promenar.infinity.data.backup

import android.content.ContentValues
import android.database.Cursor
import androidx.room.withTransaction
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.resetSessionSamplingJson
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.memory.LongTermMemoryKind
import com.promenar.infinity.data.memory.LongTermMemoryValidation
import com.promenar.infinity.data.repository.PersistentFileIdentity
import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.data.security.SecretId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal const val ROOM_SCHEMA_V1_IDENTITY_HASH = "1cec46d28d19744e8cb885fe6abdfcf1"
internal const val ROOM_SCHEMA_V2_IDENTITY_HASH = "7777303c63145d5bbb9b161b38f94495"
internal const val ROOM_SCHEMA_V4_IDENTITY_HASH = "a48dd118f5212b60e657cbb257b75d92"
internal const val ROOM_SCHEMA_V19_IDENTITY_HASH = "694f9ee43b8b6fbca993a11d4a789f64"
internal const val ROOM_SCHEMA_V20_IDENTITY_HASH = "4cbc9d57e418ec09a3469f1fcdc6bac6"
internal const val ROOM_SCHEMA_V21_IDENTITY_HASH = com.promenar.infinity.data.local.db.INFINITY_SCHEMA_V21_IDENTITY_HASH
internal const val ROOM_SCHEMA_V26_IDENTITY_HASH = com.promenar.infinity.data.local.db.INFINITY_SCHEMA_V26_IDENTITY_HASH
internal const val ROOM_SCHEMA_V27_IDENTITY_HASH = com.promenar.infinity.data.local.db.INFINITY_SCHEMA_V27_IDENTITY_HASH
internal const val ROOM_SCHEMA_V28_IDENTITY_HASH = com.promenar.infinity.data.local.db.INFINITY_SCHEMA_V28_IDENTITY_HASH
internal const val ROOM_SCHEMA_V29_IDENTITY_HASH = com.promenar.infinity.data.local.db.INFINITY_SCHEMA_V29_IDENTITY_HASH
internal const val ROOM_SCHEMA_V32_IDENTITY_HASH = com.promenar.infinity.data.local.db.INFINITY_SCHEMA_V32_IDENTITY_HASH
internal const val ROOM_SCHEMA_V31_IDENTITY_HASH = com.promenar.infinity.data.local.db.INFINITY_SCHEMA_V31_IDENTITY_HASH
internal const val ROOM_SCHEMA_V30_IDENTITY_HASH = com.promenar.infinity.data.local.db.INFINITY_SCHEMA_V30_IDENTITY_HASH
internal const val ROOM_SCHEMA_V25_IDENTITY_HASH = com.promenar.infinity.data.local.db.INFINITY_SCHEMA_V25_IDENTITY_HASH
internal const val ROOM_SCHEMA_V24_IDENTITY_HASH = com.promenar.infinity.data.local.db.INFINITY_SCHEMA_V24_IDENTITY_HASH
internal const val ROOM_SCHEMA_V18_IDENTITY_HASH = "c32f59706ea482fd5697681569c6fdb5"

class RoomBackupDataSource(
    private val database: InfinityDatabase,
    private val preferences: TransactionalBackupPreferenceStore,
    private val secrets: TransactionalBackupSecretStore,
    trustedSourceBases: Set<Path>,
    trustedRestoreBase: Path,
    private val appVersion: String,
    private val crashHook: RestoreCrashHook = RestoreCrashHook {},
    private val snapshotReadHook: (Path) -> Unit = {},
    private val configurationRevision: () -> Long = { 0L },
    journalAuthenticator: RestoreJournalAuthenticator,
    private val restoreFileOperations: RestoreFileOperations = SecureBackupFileOps,
    userSkillsRoot: Path? = null,
    private val avatarRoot: Path? = null,
) : BackupDataSource {
    private val trustedSourceBases = trustedSourceBases.map(FileRestoreJournal::requireTrustedDirectory)
    private val restoreParent = FileRestoreJournal.requireTrustedDirectory(trustedRestoreBase)
    private val journal: RestoreJournal = FileRestoreJournal(restoreParent, journalAuthenticator)
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }
    private val restoreMutex = Mutex()
    private val skillSnapshotter = userSkillsRoot?.let(::SkillBackupSnapshotter)
    private val skillRestore = userSkillsRoot?.let { SkillRestoreTransaction(it, restoreFileOperations, crashHook) }
    private val avatarRestore = avatarRoot?.let { SkillRestoreTransaction(it, restoreFileOperations, crashHook, "avatars") }

    override suspend fun snapshot(content: Set<BackupContent>): BackupSnapshot = withContext(Dispatchers.IO) {
        repeat(MAX_CONFIGURATION_SNAPSHOT_ATTEMPTS) {
            val before = configurationRevision()
            if (before and 1L != 0L) return@repeat
            val captured = snapshotOnce(content)
            val after = configurationRevision()
            if (before == after && after and 1L == 0L) return@withContext captured
            captured.wipe()
        }
        throw BackupValidationException("Provider 配置在备份期间持续变化，请稍后重试")
    }

    private suspend fun snapshotOnce(content: Set<BackupContent>): BackupSnapshot {
        requireCanonicalSnapshotContent(content)
        requireWorkspaceMutationJournalRecovered()
        val safePreferences = sanitizePreferences(preferences.snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES))
        var preferenceBytes = json.encodeToString(safePreferences).toByteArray(Charsets.UTF_8)
        if (preferenceBytes.size.toLong() > BackupPackageLimits.MAX_IN_MEMORY_BYTES) {
            throw BackupValidationException("偏好数据超过内存安全限制", reason = BackupFailureReason.CAPACITY_EXCEEDED)
        }
        var files: Map<String, ByteArray> = emptyMap()
        var avatars: Map<String, ByteArray> = emptyMap()
        var secretSnapshot: Map<SecretId, ByteArray> = emptyMap()
        var skillSnapshot: SkillBackupPartition? = null
        var databaseBytes = ByteArray(0)
        return try {
            secretSnapshot = if (BackupContent.SECRETS in content) {
                snapshotSecrets(
                    safePreferences.providerIds,
                    BackupPackageLimits.MAX_IN_MEMORY_BYTES - preferenceBytes.size,
                )
            } else emptyMap()
            val secretBytes = secretSnapshot.values.sumOf { it.size.toLong() }
            val databaseAndFiles =
            database.withTransaction {
                requireWorkspaceMutationJournalRecovered()
                val payload = readDatabasePayload()
                val preliminaryDatabase = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
                val nonDirectoryCount = payload.rows(FILE_TABLE).count { !it.requiredBoolean("is_directory") }
                val remaining = BackupPackageLimits.MAX_IN_MEMORY_BYTES - preferenceBytes.size - secretBytes -
                    preliminaryDatabase.size - nonDirectoryCount * 64L
                preliminaryDatabase.fill(0)
                if (remaining < 0) throw BackupValidationException("数据库与偏好已超过内存安全限制", reason = BackupFailureReason.CAPACITY_EXCEEDED)
                val result = snapshotFiles(payload, remaining)
                result.payload to result.files
            }
            files = databaseAndFiles.second
            databaseBytes = json.encodeToString(databaseAndFiles.first).toByteArray(Charsets.UTF_8)
            val coreTotal = databaseBytes.size.toLong() + preferenceBytes.size + secretBytes +
                files.values.sumOf { it.size.toLong() }
            skillSnapshot = skillSnapshotter?.snapshot(
                BackupPackageLimits.MAX_IN_MEMORY_BYTES - coreTotal,
                BackupPackageLimits.MAX_ENTRIES - files.size - 2 - (if (BackupContent.SECRETS in content) 1 else 0),
            )
            // 工作区与技能先占预算，照片仅使用余量，避免可选照片挤占既有备份内容。
            val skillBytes = skillSnapshot?.files.orEmpty().values.sumOf { it.size.toLong() }
            val skillEntries = skillSnapshot?.let { it.files.size + it.metadata.directories.size } ?: 0
            val avatarReferenceBudget = (databaseAndFiles.first.rows("agents").size + 1) * 512L
            val avatarSnapshot = AvatarBackupFiles.snapshot(
                databaseAndFiles.first, safePreferences, avatarRoot,
                BackupPackageLimits.MAX_IN_MEMORY_BYTES - coreTotal - skillBytes - avatarReferenceBudget,
                BackupPackageLimits.MAX_ENTRIES - files.size - skillEntries - 2 - (if (BackupContent.SECRETS in content) 1 else 0),
                ::readStableFile,
            )
            avatars = avatarSnapshot.files
            databaseBytes.fill(0)
            databaseBytes = json.encodeToString(avatarSnapshot.payload).toByteArray(Charsets.UTF_8)
            preferenceBytes.fill(0)
            preferenceBytes = json.encodeToString(avatarSnapshot.preferences).toByteArray(Charsets.UTF_8)
            validateSnapshotLimits(databaseBytes, preferenceBytes, files, secretSnapshot, skillSnapshot, avatars)
            BackupSnapshot(
                database = databaseBytes,
                preferences = preferenceBytes,
                files = files,
                secrets = secretSnapshot,
                databaseSchemaVersion = DATABASE_SCHEMA_VERSION,
                appVersion = appVersion,
                skills = skillSnapshot,
                avatars = avatars,
            )
        } catch (error: Throwable) {
            files.values.forEach { it.fill(0) }
            avatars.values.forEach { it.fill(0) }
            secretSnapshot.values.forEach { it.fill(0) }
            skillSnapshot?.wipe()
            preferenceBytes.fill(0)
            databaseBytes.fill(0)
            throw error
        }
    }

    override suspend fun restore(validated: ValidatedBackup) =
        restore(validated, UUID.randomUUID().toString())

    override suspend fun restore(validated: ValidatedBackup, operationId: String) {
        requireOperationId(operationId)
        validated.beginConsumption()
        var lockAcquired = false
        try {
            restoreMutex.lock()
            lockAcquired = true
            withContext(Dispatchers.IO) {
        if (hasCompletedRestoreLocked(operationId)) return@withContext
        requireWorkspaceMutationJournalRecovered()
        requireCanonicalRestoreContent(validated)
        if (journal.read() != null) throw BackupValidationException("存在未恢复的 restore journal，请先执行 recoverInterruptedRestore")
        validateAvatarPartition(validated)
        val avatarPlan = AvatarBackupFiles.planRestore(
            parseDatabase(validated.database), parsePreferences(validated.preferences), validated.avatars,
            avatarRoot, validated.manifest.formatVersion >= 3,
        )
        val parsedPayload = avatarPlan.payload
        val preferenceSnapshot = avatarPlan.preferences
        validatePayload(parsedPayload, validated.files)
        validateSecrets(validated.secrets, preferenceSnapshot.providerIds)
        val payload = normalizeBranchSessions(parsedPayload)

        val restoreId = operationId
        val stagingRoot = restoreParent.resolve(".restore-$restoreId.tmp")
        val finalRoot = restoreParent.resolve("restore-$restoreId")
        var transformed = transformPaths(payload, finalRoot)
        validatePayload(transformed, validated.files)
        var expectedFingerprint = fingerprint(transformed)
        val plannedSkills = validated.skills?.let {
            (skillRestore ?: throw BackupValidationException("技能恢复根目录未配置")).plan(restoreId)
        }
        val plannedAvatars = avatarPlan.files.takeIf { it.isNotEmpty() }?.let {
            requireAvatarRestore().plan(restoreId)
        }
        val beforePreferences = preferences.snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
        preferences.preflightRestore(restoreId, beforePreferences, preferenceSnapshot)
        val eligibleIds = eligibleSecretIds(preferenceSnapshot.providerIds + beforePreferences.providerIds)
        val beforeSecrets = secrets.snapshot(eligibleIds, BackupPackageLimits.MAX_IN_MEMORY_BYTES)
        val afterSecrets = validated.secrets.mapValues { it.value.copyOf() }
        var record = RestoreJournalRecord(
            txId = restoreId,
            expectedDatabaseFingerprint = expectedFingerprint,
            oldRootIdentity = currentOldRootIdentity(),
            newRootIdentity = finalRoot.fileName.toString(),
            newRootFileKey = null,
            phase = RestoreJournalPhase.PREPARED,
            skills = plannedSkills,
            avatars = plannedAvatars,
        )

        try {
            journal.write(record)
            preferences.prepare(restoreId, beforePreferences, preferenceSnapshot)
            secrets.prepare(restoreId, beforeSecrets, afterSecrets)
            crashHook.hit(RestoreCrashPoint.JOURNAL_PREPARED)
            val staged = stageFiles(stagingRoot, transformed, validated.files)
            transformed = staged.payload
            validatePayload(transformed, validated.files)
            expectedFingerprint = fingerprint(transformed)
            record = record.copy(expectedDatabaseFingerprint = expectedFingerprint)
            journal.write(record)
            val expectedTree = staged.expectedTree
            moveAtomically(stagingRoot, finalRoot)
            restoreFileOperations.verifyAndSync(finalRoot, expectedTree)
            record = record.copy(newRootFileKey = PersistentFileIdentity.read(finalRoot))
            journal.write(record)
            crashHook.hit(RestoreCrashPoint.FILES_MOVED)
            validated.skills?.let { partition ->
                record = record.copy(skills = skillRestore!!.stage(restoreId, partition, record.skills!!, finalRoot))
                journal.write(record)
                crashHook.hit(RestoreCrashPoint.SKILLS_STAGED)
            }

            record.avatars?.let { planned ->
                record = record.copy(avatars = requireAvatarRestore().stage(
                    restoreId, SkillBackupPartition(avatarPlan.files, SkillBackupMetadata()), planned, finalRoot,
                ))
                journal.write(record)
                crashHook.hit(RestoreCrashPoint.AVATARS_STAGED)
            }

            // Task 8 仍须在应用层冻结其它 DB/文件 writer；本地 mutex 只串行化 restore/recovery。
            restoreFileOperations.verifyAndSync(finalRoot, expectedTree)
            if (currentOldRootIdentity() != record.oldRootIdentity) {
                throw BackupValidationException("恢复期间旧 managed root 身份发生变化")
            }
            database.withTransaction {
                clearAllRestorableTables()
                insertPayload(transformed)
                record.skills?.let { skillRestore!!.commit(restoreId, it) }
                record.avatars?.let { requireAvatarRestore().commit(restoreId, it) }
                preferences.commitPrepared(restoreId)
                secrets.commitPrepared(restoreId)
                crashHook.hit(RestoreCrashPoint.EXTERNAL_COMMITTED)
                writeDatabaseCommitMarker(record)
            }
            crashHook.hit(RestoreCrashPoint.ROOM_COMMITTED)
            record = record.copy(phase = RestoreJournalPhase.COMMITTED)
            journal.write(record)
            crashHook.hit(RestoreCrashPoint.JOURNAL_COMMITTED)
            finishCommitted(record)
        } catch (error: Throwable) {
            if (error is SimulatedRestoreProcessDeath) throw error
            val recoveryFailure = runCatching {
                withContext(NonCancellable) { recoverInterruptedRestoreLocked() }
            }.exceptionOrNull()
            recoveryFailure?.let { error.addSuppressed(it) }
            throw error
        } finally {
            beforeSecrets.values.forEach { it.fill(0) }
            afterSecrets.values.forEach { it.fill(0) }
        }
            }
        } finally {
            if (lockAcquired) restoreMutex.unlock()
            validated.close()
        }
    }

    override suspend fun recoverInterruptedRestore() = restoreMutex.withLock { recoverInterruptedRestoreLocked() }

    override suspend fun hasCompletedRestore(operationId: String): Boolean = withContext(Dispatchers.IO) {
        requireOperationId(operationId)
        hasCompletedRestoreLocked(operationId)
    }

    // journal 只在补偿全部成功（或提交收尾完成并写回执）后删除；无 journal 且无回执即证明现有数据未被改动。
    override suspend fun isRestoreSettledWithoutApplying(operationId: String): Boolean = restoreMutex.withLock {
        withContext(Dispatchers.IO) {
            requireOperationId(operationId)
            journal.read() == null && !hasCompletedRestoreLocked(operationId)
        }
    }

    private suspend fun recoverInterruptedRestoreLocked() = withContext(Dispatchers.IO + NonCancellable) {
        var record = journal.read() ?: return@withContext
        record.skills?.let { skills ->
            val recovered = requireSkillRestore().recoverRecord(record.txId, skills)
            if (recovered != skills) {
                record = record.copy(skills = recovered)
                journal.write(record)
            }
        }
        record.avatars?.let { avatars ->
            val recovered = requireAvatarRestore().recoverRecord(record.txId, avatars)
            if (recovered != avatars) {
                record = record.copy(avatars = recovered)
                journal.write(record)
            }
        }
        val failures = mutableListOf<Throwable>()
        val databaseCommitted = when (record.phase) {
            RestoreJournalPhase.COMMITTED -> true
            RestoreJournalPhase.PREPARED -> {
                if (record.newRootFileKey == null) false else hasDatabaseCommitMarker(record)
            }
        }

        if (databaseCommitted) {
            runCatching { preferences.commitPrepared(record.txId) }.onFailure(failures::add)
            runCatching { secrets.commitPrepared(record.txId) }.onFailure(failures::add)
            if (failures.isEmpty()) {
                runCatching { finishCommitted(record.copy(phase = RestoreJournalPhase.COMMITTED)) }
                    .onFailure(failures::add)
            }
        } else {
            runCatching { preferences.rollbackPrepared(record.txId) }.onFailure(failures::add)
            runCatching { secrets.rollbackPrepared(record.txId) }.onFailure(failures::add)
            record.skills?.let { skills ->
                runCatching { requireSkillRestore().rollback(record.txId, skills) }.onFailure(failures::add)
            }
            record.avatars?.let { avatars ->
                runCatching { requireAvatarRestore().rollback(record.txId, avatars) }.onFailure(failures::add)
            }
            runCatching { deleteOwnedRestoreRoots(record) }.onFailure(failures::add)
            if (failures.isEmpty()) {
                runCatching { preferences.finalizePrepared(record.txId) }.onFailure(failures::add)
                runCatching { secrets.finalizePrepared(record.txId) }.onFailure(failures::add)
            }
            if (failures.isEmpty()) runCatching { journal.delete() }.onFailure(failures::add)
        }
        if (failures.isNotEmpty()) {
            val composite = BackupValidationException("中断恢复补偿失败，journal 已保留")
            failures.forEach(composite::addSuppressed)
            throw composite
        }
    }

    private fun readDatabasePayload(): DatabaseBackupPayload {
        val sqlite = database.openHelper.writableDatabase
        val budget = MaterializationBudget(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
        val tables = INSERT_ORDER.associateWith { table ->
            sqlite.query("SELECT * FROM `$table`").use { cursor -> cursorRows(cursor, budget) }
        }
        return DatabaseBackupPayload(DATABASE_SCHEMA_VERSION, tables).also(::validateLongTermMemories)
    }

    private fun cursorRows(cursor: Cursor, budget: MaterializationBudget): List<JsonObject> = buildList {
        while (cursor.moveToNext()) {
            add(JsonObject(buildMap {
                cursor.columnNames.forEachIndexed { index, name ->
                    val value = when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> JsonNull
                        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(cursor.getLong(index))
                        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(cursor.getDouble(index))
                        Cursor.FIELD_TYPE_STRING -> JsonPrimitive(cursor.getString(index))
                        Cursor.FIELD_TYPE_BLOB -> throw BackupValidationException("用户源表不允许未声明的 BLOB 字段: $name")
                        else -> throw BackupValidationException("无法备份数据库字段: $name")
                    }
                    budget.consume(name.toByteArray().size.toLong() + value.toString().toByteArray().size + 32L)
                    put(name, value)
                }
            }))
        }
    }

    private fun snapshotFiles(payload: DatabaseBackupPayload, memoryBudget: Long): SnapshotFilesResult {
        val rows = payload.rows(FILE_TABLE)
        val fileRows = rows.filterNot { it.requiredBoolean("is_directory") }
        if (fileRows.size > BackupPackageLimits.MAX_ENTRIES - 2) throw BackupValidationException("备份文件项过多", reason = BackupFailureReason.CAPACITY_EXCEEDED)
        var declaredTotal = 0L
        fileRows.forEach { row ->
            val size = row.requiredLong("size_bytes")
            if (size < 0 || size > BackupPackageLimits.MAX_ENTRY_BYTES) {
                throw BackupValidationException("文件声明大小超过备份限制", reason = BackupFailureReason.CAPACITY_EXCEEDED)
            }
            declaredTotal = Math.addExact(declaredTotal, size)
            if (declaredTotal > BackupPackageLimits.MAX_TOTAL_BYTES || declaredTotal > memoryBudget) {
                throw BackupValidationException("文件声明总大小超过备份或内存安全限制", reason = BackupFailureReason.CAPACITY_EXCEEDED)
            }
        }
        val result = linkedMapOf<String, ByteArray>()
        val hashes = linkedMapOf<String, String>()
        try {
            rows.forEach { row ->
                val id = row.requiredString("uuid")
                if (row.requiredBoolean("is_directory")) return@forEach
                if (result.containsKey(id)) throw BackupValidationException("文件 UUID 重复")
                val path = resolveSnapshotPath(row)
                val (bytes, actualHash) = readStableFile(path, row.requiredLong("size_bytes"), id)
                val declaredHash = row.requiredString("hash")
                if (declaredHash.isNotBlank() && !declaredHash.equals(actualHash, ignoreCase = true)) {
                    bytes.fill(0)
                    throw BackupValidationException("文件摘要不一致", reason = BackupFailureReason.WORKSPACE_FILE_MISMATCH)
                }
                result[id] = bytes
                hashes[id] = actualHash
            }
            val updatedRows = rows.map { row ->
                val hash = hashes[row.requiredString("uuid")]
                if (hash == null) row else JsonObject(row + ("hash" to JsonPrimitive(hash)))
            }
            return SnapshotFilesResult(
                payload.copy(tables = payload.tables + (FILE_TABLE to updatedRows)),
                result,
            )
        } catch (error: Throwable) {
            result.values.forEach { it.fill(0) }
            throw error
        }
    }

    private fun readStableFile(path: Path, declaredSize: Long, id: String): Pair<ByteArray, String> {
        val before = Files.readAttributes(
            path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
        )
        if (!before.isRegularFile || before.isSymbolicLink || before.size() != declaredSize) {
            throw BackupValidationException("文件读取前属性与声明不一致: $id", reason = BackupFailureReason.WORKSPACE_FILE_MISMATCH)
        }
        val realBefore = path.toRealPath(LinkOption.NOFOLLOW_LINKS)
        val bytes = ByteArray(declaredSize.toInt())
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            openNoFollow(path).use { channel ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = channel.read(java.nio.ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                    if (count < 0) break
                    if (count == 0) continue
                    digest.update(bytes, offset, count)
                    offset += count
                }
                if (offset != bytes.size || channel.read(java.nio.ByteBuffer.allocate(1)) != -1) {
                    throw BackupValidationException("文件读取大小发生变化: $id", reason = BackupFailureReason.WORKSPACE_FILE_MISMATCH)
                }
            }
            snapshotReadHook(path)
            val after = Files.readAttributes(
                path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
            )
            val realAfter = path.toRealPath(LinkOption.NOFOLLOW_LINKS)
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val afterHash = hashFileWithoutAccumulating(path, declaredSize)
            val finalAttributes = Files.readAttributes(
                path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
            )
            val finalReal = path.toRealPath(LinkOption.NOFOLLOW_LINKS)
            if (before.fileKey() != after.fileKey() || before.size() != after.size() ||
                before.lastModifiedTime() != after.lastModifiedTime() || realBefore != realAfter || hash != afterHash ||
                after.fileKey() != finalAttributes.fileKey() || after.size() != finalAttributes.size() ||
                after.lastModifiedTime() != finalAttributes.lastModifiedTime() || realAfter != finalReal
            ) throw BackupValidationException("文件在 snapshot 期间发生变化: $id", reason = BackupFailureReason.WORKSPACE_FILE_MISMATCH)
            return bytes to hash
        } catch (error: BackupValidationException) {
            bytes.fill(0)
            throw error
        } catch (error: Exception) {
            bytes.fill(0)
            throw BackupValidationException("无法稳定读取备份源文件: $id", error, reason = BackupFailureReason.WORKSPACE_FILE_MISMATCH)
        }
    }

    private fun hashFileWithoutAccumulating(path: Path, expectedSize: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        openNoFollow(path).use { channel ->
            val buffer = java.nio.ByteBuffer.allocate(64 * 1024)
            while (true) {
                buffer.clear()
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > expectedSize) throw BackupValidationException("文件复核读取超过声明大小", reason = BackupFailureReason.WORKSPACE_FILE_MISMATCH)
                digest.update(buffer.array(), 0, count)
            }
        }
        if (total != expectedSize) throw BackupValidationException("文件复核读取大小变化", reason = BackupFailureReason.WORKSPACE_FILE_MISMATCH)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun openNoFollow(path: Path): java.nio.channels.SeekableByteChannel {
        val stream = Files.newDirectoryStream(path.parent)
        if (stream is java.nio.file.SecureDirectoryStream<Path>) {
            return try {
                val channel = stream.newByteChannel(
                    path.fileName,
                    setOf(java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                )
                ClosingSeekableByteChannel(channel, stream)
            } catch (error: Throwable) {
                stream.close()
                throw error
            }
        }
        stream.close()
        return java.nio.channels.FileChannel.open(
            path,
            java.nio.file.StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        )
    }

    private fun resolveSnapshotPath(row: JsonObject): Path {
        val root = requireTrustedSourceRoot(row.requiredString("physical_root_path"))
        val rootReal = root.toRealPath()
        val relative = normalizeMaterializedPath(row.requiredString("materialized_path"))
        var current = root
        relative.forEach { segment ->
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) throw BackupValidationException("备份文件路径包含符号链接", reason = BackupFailureReason.WORKSPACE_FILE_MISMATCH)
        }
        val candidate = current.normalize()
        val real = try {
            candidate.toRealPath()
        } catch (error: Exception) {
            throw BackupValidationException("备份源文件不存在", error, reason = BackupFailureReason.WORKSPACE_FILE_MISMATCH)
        }
        if (!real.startsWith(rootReal) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw BackupValidationException("备份文件越出根目录或不是普通文件", reason = BackupFailureReason.WORKSPACE_FILE_MISMATCH)
        }
        return real
    }

    private suspend fun snapshotSecrets(providerIds: Set<String>, maxTotalBytes: Long): Map<SecretId, ByteArray> =
        secrets.snapshot(eligibleSecretIds(providerIds), maxTotalBytes)

    private fun parseDatabase(bytes: ByteArray): DatabaseBackupPayload {
        if (bytes.isEmpty()) throw BackupValidationException("恢复包缺少 database.json")
        val decoded: DatabaseBackupPayload = try {
            json.decodeFromString(bytes.toString(Charsets.UTF_8))
        } catch (error: SerializationException) {
            throw BackupValidationException("数据库 payload 无效", error)
        } catch (error: IllegalArgumentException) {
            throw BackupValidationException("数据库 payload 无效", error)
        }
        val upgraded = when (decoded.schemaVersion) {
            DATABASE_SCHEMA_VERSION -> return decoded
            12 -> decoded
            11 -> decoded
            10 -> upgradeV10Payload(decoded)
            9 -> upgradeV10Payload(upgradeV9Payload(decoded))
            8 -> upgradeV10Payload(upgradeV9Payload(upgradeV8Payload(decoded)))
            7 -> upgradeV10Payload(upgradeV9Payload(upgradeV8Payload(upgradeV7Payload(decoded))))
            1 -> upgradeV5Payload(upgradeV4Payload(upgradeV3Payload(upgradeV1Payload(decoded))))
            2 -> upgradeV5Payload(upgradeV4Payload(upgradeV3Payload(upgradeV2Payload(decoded))))
            3 -> upgradeV5Payload(upgradeV4Payload(upgradeV3Payload(decoded)))
            4 -> upgradeV5Payload(upgradeV4Payload(decoded))
            5 -> upgradeV5Payload(decoded)
            6 -> decoded
            else -> return decoded
        }
        val current = if (decoded.schemaVersion in 1..6) {
            if ("state_board_versions" in upgraded.tables) throw BackupValidationException("历史备份不应包含状态面板表")
            upgradeV10Payload(upgradeV9Payload(upgradeV8Payload(upgradeV7Payload(
                upgraded.copy(tables = upgraded.tables + ("state_board_versions" to emptyList())),
            ))))
        } else upgraded
        val messages = current.tables["messages"] ?: throw BackupValidationException("数据库 payload 缺少表: messages")
        return current.copy(schemaVersion = DATABASE_SCHEMA_VERSION, tables = current.tables +
            ("messages" to messages.map { row -> JsonObject(mapOf("quotes" to JsonNull, "turn_context" to JsonNull, "request_instruction" to JsonNull) + row) }))
    }

    /** 历史包没有运行模式，恢复为未确定状态，首次进入会话时再检查。 */
    private fun upgradeV7Payload(payload: DatabaseBackupPayload): DatabaseBackupPayload {
        val sessions = payload.tables["sessions"] ?: throw BackupValidationException("数据库 payload 缺少表: sessions")
        return payload.copy(schemaVersion = 8, tables = payload.tables +
            ("sessions" to sessions.map { row -> JsonObject(row + ("run_mode" to (row["run_mode"] ?: JsonNull))) }))
    }

    private fun upgradeV8Payload(payload: DatabaseBackupPayload): DatabaseBackupPayload {
        if (LONG_TERM_MEMORY_TABLE in payload.tables) throw BackupValidationException("历史备份不应包含长期记忆表")
        return payload.copy(schemaVersion = 9, tables = payload.tables + (LONG_TERM_MEMORY_TABLE to emptyList()))
    }

    private fun upgradeV9Payload(payload: DatabaseBackupPayload): DatabaseBackupPayload {
        val sessions = payload.tables["sessions"] ?: throw BackupValidationException("数据库 payload 缺少表: sessions")
        val defaults = mapOf(
            "parent_session_id" to JsonNull,
            "branch_plan_json" to JsonNull,
            "worktree_directory" to JsonNull,
            "worktree_branch" to JsonNull,
            "worktree_base" to JsonNull,
            "parent_session_deleted" to JsonPrimitive(0),
            "session_auto_wake_count" to JsonPrimitive(0),
            "session_auto_processing_paused" to JsonPrimitive(0),
        )
        return payload.copy(schemaVersion = 10, tables = payload.tables +
            ("sessions" to sessions.map { row -> JsonObject(defaults + row) }))
    }

    /** 历史备份的智能体沿用新会话默认开启工具的行为，已有显式值保持原样。 */
    private fun upgradeV10Payload(payload: DatabaseBackupPayload): DatabaseBackupPayload {
        val agents = payload.tables["agents"] ?: throw BackupValidationException("数据库 payload 缺少表: agents")
        return payload.copy(schemaVersion = DATABASE_SCHEMA_VERSION, tables = payload.tables +
            ("agents" to agents.map { row -> JsonObject(mapOf("tools_enabled_default" to JsonPrimitive(1)) + row) }))
    }

    /** 分支只关联同助手的普通主会话；无效关系保留为带删除标记的独立会话。 */
    private fun normalizeBranchSessions(payload: DatabaseBackupPayload): DatabaseBackupPayload {
        val sessions = payload.tables["sessions"] ?: return payload
        val byId = sessions.associateBy { it.requiredString("id") }
        return payload.copy(tables = payload.tables + ("sessions" to sessions.map { row ->
            val parentId = row.optionalString("parent_session_id")
            val parent = parentId?.let(byId::get)
            val valid = parent != null && parentId != row.requiredString("id") &&
                parent.requiredString("agent_id") == row.requiredString("agent_id") &&
                parent.optionalString("parent_session_id") == null && parent.optionalString("branch_plan_json") == null &&
                !parent.requiredBoolean("parent_session_deleted") && !row.requiredBoolean("parent_session_deleted")
            if (parentId != null && !valid) {
                JsonObject(row + mapOf("parent_session_id" to JsonNull, "parent_session_deleted" to JsonPrimitive(1)))
            } else row
        }))
    }

    private fun upgradeV1Payload(payload: DatabaseBackupPayload): DatabaseBackupPayload {
        val agents = payload.tables["agents"]
            ?: throw BackupValidationException("数据库 payload 缺少表: agents")
        return payload.copy(
            schemaVersion = DATABASE_SCHEMA_VERSION,
            tables = payload.tables + (
                "agents" to agents.map { row ->
                    JsonObject(
                        row + mapOf(
                            "execution_mode" to (row["execution_mode"] ?: JsonPrimitive("semi")),
                            "skill_ids" to (row["skill_ids"] ?: JsonPrimitive("[]")),
                            "mcp_server_ids" to (row["mcp_server_ids"] ?: JsonPrimitive("[]")),
                        ),
                    )
                }
            ),
        )
    }

    private fun upgradeV2Payload(payload: DatabaseBackupPayload): DatabaseBackupPayload {
        val agents = payload.tables["agents"]
            ?: throw BackupValidationException("数据库 payload 缺少表: agents")
        return payload.copy(
            schemaVersion = DATABASE_SCHEMA_VERSION,
            tables = payload.tables + (
                "agents" to agents.map { row ->
                    JsonObject(
                        row + mapOf(
                            "skill_ids" to (row["skill_ids"] ?: JsonPrimitive("[]")),
                            "mcp_server_ids" to (row["mcp_server_ids"] ?: JsonPrimitive("[]")),
                        ),
                    )
                }
            ),
        )
    }

    /** 历史备份没有建图意图列，补空值以保全源数据，不推断新的付费抽取授权。 */
    private fun upgradeV3Payload(payload: DatabaseBackupPayload): DatabaseBackupPayload {
        val files = payload.tables["workspace_files"]
            ?: throw BackupValidationException("数据库 payload 缺少表: workspace_files")
        return payload.copy(
            schemaVersion = DATABASE_SCHEMA_VERSION,
            tables = payload.tables + ("workspace_files" to files.map { row ->
                JsonObject(row + ("kg_strategy" to (row["kg_strategy"] ?: JsonNull)))
            }),
        )
    }

    /**
     * v5 增加自选工作区登记与绑定、Agent 用户规则。历史备份没有这些数据：
     * 会话与 Agent 回到会话私有工作区，用户规则为空。
     */
    private fun upgradeV4Payload(payload: DatabaseBackupPayload): DatabaseBackupPayload {
        if (WORKSPACE_TABLE in payload.tables) throw BackupValidationException("v4 数据库 payload 不应包含工作区表")
        val agents = payload.tables["agents"]
            ?: throw BackupValidationException("数据库 payload 缺少表: agents")
        val sessions = payload.tables["sessions"]
            ?: throw BackupValidationException("数据库 payload 缺少表: sessions")
        return payload.copy(
            schemaVersion = DATABASE_SCHEMA_VERSION,
            tables = payload.tables + mapOf(
                WORKSPACE_TABLE to emptyList(),
                "agents" to agents.map { row ->
                    JsonObject(row + mapOf(
                        "default_workspace_id" to (row["default_workspace_id"] ?: JsonNull),
                        "user_rules" to (row["user_rules"] ?: JsonPrimitive("")),
                    ))
                },
                "sessions" to sessions.map { row ->
                    JsonObject(row + ("workspace_id" to (row["workspace_id"] ?: JsonNull)))
                },
            ),
        )
    }

    /**
     * v5 → v6：助手新增采样参数与推理强度列，新增参数方案表；
     * 与数据库迁移一致，旧备份里的助手与会话采样参数恢复为模型默认。
     */
    private fun upgradeV5Payload(payload: DatabaseBackupPayload): DatabaseBackupPayload {
        if (PARAMETER_SCHEME_TABLE in payload.tables) throw BackupValidationException("v5 数据库 payload 不应包含参数方案表")
        val agents = payload.tables["agents"]
            ?: throw BackupValidationException("数据库 payload 缺少表: agents")
        val sessions = payload.tables["sessions"]
            ?: throw BackupValidationException("数据库 payload 缺少表: sessions")
        val clearedAgentColumns = listOf(
            "temperature", "top_p", "max_tokens",
            "top_k", "frequency_penalty", "presence_penalty", "repetition_penalty", "reasoning_effort",
        ).associateWith { JsonNull }
        return payload.copy(
            schemaVersion = DATABASE_SCHEMA_VERSION,
            tables = payload.tables + mapOf(
                PARAMETER_SCHEME_TABLE to emptyList(),
                "agents" to agents.map { row -> JsonObject(row + clearedAgentColumns) },
                "sessions" to sessions.map { row ->
                    val params = (row["inference_params"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val reset = params?.let(::resetSessionSamplingJson) ?: return@map row
                    JsonObject(row + ("inference_params" to JsonPrimitive(reset)))
                },
            ),
        )
    }

    private fun parsePreferences(bytes: ByteArray): BackupPreferenceSnapshot {
        if (bytes.isEmpty()) return BackupPreferenceSnapshot(emptyList(), emptySet())
        val parsed = try {
            json.decodeFromString<BackupPreferenceSnapshot>(bytes.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            throw BackupValidationException("偏好 payload 无效", error)
        }
        return sanitizePreferences(parsed, rejectUnsafe = true)
    }

    private fun sanitizePreferences(
        snapshot: BackupPreferenceSnapshot,
        rejectUnsafe: Boolean = false,
    ): BackupPreferenceSnapshot {
        if (snapshot.providerIds.any { it.isBlank() || it.length > 200 }) {
            throw BackupValidationException("Provider ID 无效")
        }
        val seen = mutableSetOf<Pair<String, String>>()
        val safe = snapshot.entries.map { it.copy(namespace = BackupPreferencePolicy.normalizeNamespace(it.namespace)) }.mapNotNull { entry ->
            val identity = entry.namespace to entry.key
            if (!seen.add(identity)) throw BackupValidationException("偏好键重复")
            val allowed = BackupPreferencePolicy.isAllowed(entry.namespace, entry.key)
            if (!allowed && rejectUnsafe) throw BackupValidationException("偏好 payload 包含非白名单字段")
            entry.takeIf { allowed }
        }
        return BackupPreferenceSnapshot(safe, snapshot.providerIds.toSortedSet())
    }

    private fun validatePayload(payload: DatabaseBackupPayload, files: Map<String, ByteArray>) {
        if (payload.schemaVersion != DATABASE_SCHEMA_VERSION) {
            throw BackupValidationException("数据库 schema 版本不兼容")
        }
        if (payload.tables.keys != INSERT_ORDER.toSet()) {
            throw BackupValidationException("数据库 payload 表清单不完整或包含派生表")
        }
        validateRowsAgainstRoomSchema(payload)
        validateUnique(payload, "agents", "id")
        validateUnique(payload, "sessions", "id")
        validateUnique(payload, "messages", "id")
        validateUnique(payload, "attachments", "id")
        validateUnique(payload, "artifacts", "id")
        validateUnique(payload, "context_summaries", "id")
        validateUnique(payload, "tags", "id")
        validateCompositeUnique(payload, "document_tags", listOf("doc_id", "tag_id"))
        validateUnique(payload, "task_nodes", "id")
        validateUnique(payload, "state_board_versions", "id")
        validateUnique(payload, LONG_TERM_MEMORY_TABLE, "id")
        validateLongTermMemories(payload)
        validateUnique(payload, "custom_skills", "id")
        validateUnique(payload, "mcp_servers", "id")
        validateUnique(payload, FILE_TABLE, "uuid")
        validateUnique(payload, "workspace_seq", "date_key")
        validateUnique(payload, WORKSPACE_TABLE, "id")
        validateCompositeUnique(payload, WORKSPACE_TABLE, listOf("kind", "location"))
        payload.rows(WORKSPACE_TABLE).forEach { row ->
            if (!WorkspaceEntity.isValidLocation(row.requiredString("kind"), row.requiredString("location"))) {
                throw BackupValidationException("工作区记录的类型或位置无效")
            }
        }
        val workspaces = payload.ids(WORKSPACE_TABLE, "id")
        payload.rows("agents").forEach { requireOptionalReference(it, "default_workspace_id", workspaces, "Agent 默认工作区") }
        payload.rows("sessions").forEach { requireOptionalReference(it, "workspace_id", workspaces, "Session 工作区") }

        val agents = payload.ids("agents", "id")
        val sessions = payload.ids("sessions", "id")
        val messages = payload.ids("messages", "id")
        val tags = payload.ids("tags", "id")
        val fileIds = payload.ids(FILE_TABLE, "uuid")
        val taskIds = payload.ids("task_nodes", "id")
        payload.rows("sessions").forEach { requireReference(it, "agent_id", agents, "Agent") }
        val messageRows = payload.rows("messages")
        val messageById = messageRows.associateBy { it.requiredString("id") }
        messageRows.forEach { row ->
            requireReference(row, "session_id", sessions, "Session")
            row.optionalString("parent_message_id")?.let { parentId ->
                val parent = messageById[parentId]
                    ?: throw BackupValidationException("Message parent 不存在")
                if (parent.requiredString("session_id") != row.requiredString("session_id")) {
                    throw BackupValidationException("Message parent 跨 session")
                }
            }
        }
        payload.rows("state_board_versions").forEach { row ->
            requireReference(row, "session_id", sessions, "状态面板会话")
            val anchor = messageById[row.requiredString("anchor_message_id")]
                ?: throw BackupValidationException("状态面板所属消息不存在")
            if (anchor.requiredString("session_id") != row.requiredString("session_id")) throw BackupValidationException("状态面板所属消息跨会话")
            if (row.requiredString("source") !in setOf("assistant", "user")) throw BackupValidationException("状态面板来源无效")
            row.optionalString("snapshot")?.let { raw ->
                val board = try { json.decodeFromString<com.promenar.infinity.data.state.StateBoard>(raw) }
                    catch (_: IllegalArgumentException) { throw BackupValidationException("状态面板快照无效") }
                try {
                    com.promenar.infinity.data.state.StateBoardEngine.define(null, board.title, board.fields, 0)
                    board.fields.forEach { com.promenar.infinity.data.state.StateBoardEngine.validateValue(it.type, it.value) }
                } catch (_: IllegalArgumentException) { throw BackupValidationException("状态面板字段值无效") }
                if (board.manualKeys.distinct().size != board.manualKeys.size ||
                    board.manualKeys.any { key -> board.fields.none { it.key == key } } ||
                    board.manualRevisions.any { (key, revision) -> key !in board.manualKeys || revision <= 0 || revision > (row.requiredString("id").toLongOrNull() ?: 0) }
                ) throw BackupValidationException("状态面板用户修改标记无效")
            }
        }
        sortMessagesParentFirst(messageRows)
        payload.rows("attachments").forEach { requireReference(it, "message_id", messages, "Message") }
        payload.rows("artifacts").forEach {
            requireReference(it, "session_id", sessions, "Session")
            requireReference(it, "message_id", messages, "Message")
        }
        payload.rows("context_summaries").forEach {
            requireReference(it, "session_id", sessions, "Session")
            requireReference(it, "start_message_id", messages, "Message")
            requireReference(it, "end_message_id", messages, "Message")
        }
        payload.rows("document_tags").forEach {
            requireReference(it, "tag_id", tags, "Tag")
            requireReference(it, "doc_id", fileIds, "FileEntry")
        }
        payload.rows("task_nodes").forEach {
            requireReference(it, "session_id", sessions, "Session")
            it.optionalString("parent_id")?.let { parent ->
                if (parent !in taskIds) throw BackupValidationException("Task parent 外键无效")
            }
        }
        payload.rows(FILE_TABLE).forEach { row ->
            row.optionalString("parent_uuid")?.let { parent ->
                if (parent !in fileIds) throw BackupValidationException("FileEntry parent 外键无效")
            }
            normalizeMaterializedPath(row.requiredString("materialized_path"))
            row.optionalString("original_materialized_path")?.let(::normalizeMaterializedPath)
        }
        validateWorkspaceIdentity(payload)

        val nonDirectories = payload.rows(FILE_TABLE)
            .filterNot { it.requiredBoolean("is_directory") }
            .associateBy { it.requiredString("uuid") }
        if (files.keys != nonDirectories.keys) throw BackupValidationException("文件清单与数据库不一致")
        if (files.size > BackupPackageLimits.MAX_ENTRIES - 2) throw BackupValidationException("恢复文件项过多")
        var total = 0L
        files.values.forEach { bytes ->
            if (bytes.size.toLong() > BackupPackageLimits.MAX_ENTRY_BYTES) {
                throw BackupValidationException("恢复文件超过单项大小限制")
            }
            total = Math.addExact(total, bytes.size.toLong())
            if (total > BackupPackageLimits.MAX_TOTAL_BYTES) throw BackupValidationException("恢复文件超过总大小限制")
        }
        nonDirectories.forEach { (id, row) -> validateFileBytes(row, files.getValue(id)) }
    }

    private fun validateWorkspaceIdentity(payload: DatabaseBackupPayload) {
        val files = payload.rows(FILE_TABLE)
        val byId = files.associateBy { it.requiredString("uuid") }
        val roots = files.filter { row ->
            row.requiredString("uuid") == row.requiredString("workspace_root_uuid")
        }.associateBy { it.requiredString("uuid") }
        files.forEach { row ->
            val rootId = row.requiredString("workspace_root_uuid")
            val root = roots[rootId] ?: throw BackupValidationException("FileEntry workspace root 不存在")
            if (!root.requiredBoolean("is_directory") ||
                root.requiredString("physical_root_path") != row.requiredString("physical_root_path")
            ) throw BackupValidationException("FileEntry workspace 所属不一致")
            row.optionalString("parent_uuid")?.let { parentId ->
                val parent = byId.getValue(parentId)
                if (parent.requiredString("workspace_root_uuid") != rootId) {
                    throw BackupValidationException("FileEntry parent 跨 workspace")
                }
            }
        }
        payload.rows("sessions").forEach { session ->
            session.optionalString("workspace_root_uuid")?.let { rootId ->
                val root = roots[rootId] ?: throw BackupValidationException("Session workspace root 不存在")
                session.optionalString("workspace_path")?.let { path ->
                    if (Paths.get(path).toAbsolutePath().normalize() !=
                        Paths.get(root.requiredString("physical_root_path")).toAbsolutePath().normalize()
                    ) throw BackupValidationException("Session workspace path 与 root 不一致")
                }
            }
        }
    }

    private fun validateLongTermMemories(payload: DatabaseBackupPayload) {
        val memories = payload.rows(LONG_TERM_MEMORY_TABLE)
        if (memories.size > MAX_LONG_TERM_MEMORIES) throw BackupValidationException("长期记忆数量超过上限")
        memories.forEach { row ->
            try {
                LongTermMemoryValidation.validateId(row.requiredString("id"))
                LongTermMemoryKind.fromStorage(row.requiredString("kind"))
                val title = row.requiredString("title")
                val content = row.requiredString("content")
                val (cleanTitle, cleanContent) = LongTermMemoryValidation.validate(title, content)
                if (title != cleanTitle || content != cleanContent) throw BackupValidationException("长期记忆字段未规范化")
            } catch (_: IllegalArgumentException) {
                throw BackupValidationException("长期记忆记录无效")
            }
        }
    }

    private fun validateRowsAgainstRoomSchema(payload: DatabaseBackupPayload) {
        val sqlite = database.openHelper.writableDatabase
        val identityHash = sqlite.query(
            "SELECT identity_hash FROM room_master_table WHERE id = 42"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        if (sqlite.version != 32 || identityHash != ROOM_SCHEMA_V32_IDENTITY_HASH) {
            throw BackupValidationException("当前 Room schema 不是受支持的精确 schema v32")
        }
        INSERT_ORDER.forEach { table ->
            val columns = sqlite.query("PRAGMA table_info(`$table`)").use { cursor ->
                buildList {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    val typeIndex = cursor.getColumnIndexOrThrow("type")
                    val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
                    val pkIndex = cursor.getColumnIndexOrThrow("pk")
                    while (cursor.moveToNext()) {
                        add(
                            RoomColumn(
                                name = cursor.getString(nameIndex),
                                affinity = cursor.getString(typeIndex).uppercase(),
                                notNull = cursor.getInt(notNullIndex) != 0,
                                primaryKeyOrder = cursor.getInt(pkIndex),
                            )
                        )
                    }
                }
            }
            if (columns.isEmpty()) throw BackupValidationException("当前 Room schema 缺少表: $table")
            val byName = columns.associateBy(RoomColumn::name)
            val rows = if (table == "messages") {
                sortMessagesParentFirst(payload.rows(table))
            } else {
                payload.rows(table)
            }
            rows.forEach { row ->
                if (row.keys != byName.keys) throw BackupValidationException("$table 行列集合与当前 Room schema 不一致")
                row.forEach { (name, value) -> validateSqlitePrimitive(table, byName.getValue(name), value) }
            }
            val pkColumns = columns.filter { it.primaryKeyOrder > 0 }.sortedBy { it.primaryKeyOrder }
            if (pkColumns.isNotEmpty()) {
                val keys = payload.rows(table).map { row -> pkColumns.map { row.getValue(it.name).toString() } }
                if (keys.size != keys.toSet().size) throw BackupValidationException("$table Room 主键重复")
            }
        }
    }

    private fun sortMessagesParentFirst(rows: List<JsonObject>): List<JsonObject> {
        val byId = rows.associateBy { it.requiredString("id") }
        if (byId.size != rows.size) throw BackupValidationException("Message id 重复")
        val childrenByParent = mutableMapOf<String, MutableList<JsonObject>>()
        val indegree = rows.associate { it.requiredString("id") to 0 }.toMutableMap()
        rows.forEach { row ->
            val id = row.requiredString("id")
            row.optionalString("parent_message_id")?.let { parentId ->
                if (parentId == id) throw BackupValidationException("Message 不得自引用")
                val parent = byId[parentId] ?: throw BackupValidationException("Message parent 不存在")
                if (parent.requiredString("session_id") != row.requiredString("session_id")) {
                    throw BackupValidationException("Message parent 跨 session")
                }
                indegree[id] = 1
                childrenByParent.getOrPut(parentId) { mutableListOf() } += row
            }
        }
        val stableOrder = compareBy<JsonObject> { it.requiredLong("created_at") }
            .thenBy { it.requiredString("id") }
        var layer = rows.filter { indegree.getValue(it.requiredString("id")) == 0 }.sortedWith(stableOrder)
        val ordered = ArrayList<JsonObject>(rows.size)
        while (layer.isNotEmpty()) {
            ordered += layer
            val nextLayer = mutableListOf<JsonObject>()
            layer.forEach { parent ->
                childrenByParent[parent.requiredString("id")].orEmpty().forEach { child ->
                    val childId = child.requiredString("id")
                    indegree[childId] = indegree.getValue(childId) - 1
                    if (indegree.getValue(childId) == 0) nextLayer += child
                }
            }
            layer = nextLayer.sortedWith(stableOrder)
        }
        if (ordered.size != rows.size) throw BackupValidationException("Message parent 存在环")
        return ordered
    }

    private fun validateSqlitePrimitive(table: String, column: RoomColumn, value: kotlinx.serialization.json.JsonElement) {
        if (value is JsonNull) {
            if (column.notNull || column.primaryKeyOrder > 0) {
                throw BackupValidationException("$table.${column.name} 不允许 NULL")
            }
            return
        }
        val primitive = value as? JsonPrimitive
            ?: throw BackupValidationException("$table.${column.name} 不是 SQLite primitive")
        val valid = when {
            column.affinity.contains("INT") -> !primitive.isString && primitive.longOrNull != null
            column.affinity.contains("CHAR") || column.affinity.contains("CLOB") || column.affinity.contains("TEXT") ->
                primitive.isString
            column.affinity.contains("REAL") || column.affinity.contains("FLOA") || column.affinity.contains("DOUB") ->
                !primitive.isString && primitive.doubleOrNull != null
            column.affinity.contains("BLOB") || column.affinity.isBlank() -> false
            else -> !primitive.isString && primitive.doubleOrNull != null
        }
        if (!valid) throw BackupValidationException("$table.${column.name} SQLite 类型不匹配")
    }

    private fun validateSecrets(secrets: Map<SecretId, ByteArray>, providerIds: Set<String>) {
        val eligible = eligibleSecretIds(providerIds)
        if (secrets.keys.any { it !in eligible || it == SecretCatalog.automaticBackupPassword }) {
            throw BackupValidationException("恢复包包含不可备份的密钥")
        }
    }

    private fun eligibleSecretIds(providerIds: Collection<String>): Set<SecretId> =
        SecretCatalog.backupEligible(providerIds) - SecretCatalog.automaticBackupPassword

    private fun validateFileBytes(row: JsonObject, bytes: ByteArray) {
        val expectedSize = row.requiredLong("size_bytes")
        if (expectedSize != bytes.size.toLong()) throw BackupValidationException("文件大小不一致")
        val expectedHash = row.requiredString("hash")
        if (!Regex("[0-9a-fA-F]{64}").matches(expectedHash) ||
            !expectedHash.equals(sha256(bytes), ignoreCase = true)
        ) {
            throw BackupValidationException("文件摘要不一致", reason = BackupFailureReason.WORKSPACE_FILE_MISMATCH)
        }
    }

    private fun transformPaths(payload: DatabaseBackupPayload, finalRoot: Path): DatabaseBackupPayload {
        val rootTokens = linkedMapOf<String, String>()
        val rootIdentities = linkedMapOf<String, String>()
        payload.rows(FILE_TABLE).forEach { row ->
            val logicalRoot = logicalRoot(row)
            rootTokens.getOrPut(logicalRoot) { safeRootToken(logicalRoot) }
            rootIdentities.getOrPut(logicalRoot) { UUID.randomUUID().toString() }
        }
        if (rootTokens.values.size != rootTokens.values.toSet().size) {
            throw BackupValidationException("workspace root token 冲突")
        }
        val transformedTables = payload.tables.toMutableMap()
        transformedTables[FILE_TABLE] = payload.rows(FILE_TABLE).map { row ->
            val token = rootTokens.getValue(logicalRoot(row))
            val rewritten = row + mapOf(
                    "physical_root_path" to JsonPrimitive(finalRoot.resolve(token).toString()),
                    "vectorized_at" to JsonNull,
                    "kg_extracted_at" to JsonNull,
                )
            JsonObject(
                if (row.requiredString("uuid") == row.requiredString("workspace_root_uuid") &&
                    row.optionalString("parent_uuid") == null
                ) rewritten + ("hash" to JsonPrimitive(rootIdentities.getValue(logicalRoot(row))))
                else rewritten
            )
        }
        transformedTables["messages"] = payload.rows("messages").map { row ->
            JsonObject(row + ("vectorization_status" to JsonNull))
        }
        transformedTables["sessions"] = payload.rows("sessions").map { row ->
            val workspaceRootUuid = row.optionalString("workspace_root_uuid")
            val token = workspaceRootUuid?.let(rootTokens::get)
            if (token == null) row else JsonObject(
                row + ("workspace_path" to JsonPrimitive(finalRoot.resolve(token).toString()))
            )
        }
        val sessionPathMapping = payload.rows("sessions").associate { row ->
            val sessionId = row.requiredString("id")
            val rootId = row.optionalString("workspace_root_uuid")
            val token = rootId?.let(rootTokens::get)
            val oldRoot = row.optionalString("workspace_path")?.let(Paths::get)?.toAbsolutePath()?.normalize()
            val newRoot = token?.let(finalRoot::resolve)
            sessionId to (oldRoot to newRoot)
        }
        transformedTables["artifacts"] = payload.rows("artifacts").map { row ->
            val artifactPath = row.optionalString("workspace_path") ?: return@map row
            val (oldRoot, newRoot) = sessionPathMapping.getValue(row.requiredString("session_id"))
            if (oldRoot == null || newRoot == null) throw BackupValidationException("Artifact 缺少可重写的 workspace root")
            val oldArtifact = Paths.get(artifactPath).toAbsolutePath().normalize()
            if (!oldArtifact.startsWith(oldRoot)) throw BackupValidationException("Artifact workspace path 越界")
            JsonObject(row + ("workspace_path" to JsonPrimitive(newRoot.resolve(oldRoot.relativize(oldArtifact)).toString())))
        }
        return payload.copy(tables = transformedTables)
    }

    private data class StagedWorkspaceFiles(
        val expectedTree: Set<RestoreTreeEntry>,
        val payload: DatabaseBackupPayload,
    )

    private fun stageFiles(
        stagingRoot: Path,
        transformed: DatabaseBackupPayload,
        files: Map<String, ByteArray>,
    ): StagedWorkspaceFiles {
        if (Files.exists(stagingRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw BackupValidationException("staging 目录已存在")
        }
        restoreFileOperations.createTransactionRoot(restoreParent, stagingRoot.fileName.toString())
        val txId = stagingRoot.fileName.toString().removePrefix(".restore-").removeSuffix(".tmp")
        restoreFileOperations.writeNew(stagingRoot, listOf(OWNER_MARKER), txId.toByteArray())
        val seen = mutableSetOf<String>()
        val directories = linkedSetOf<List<String>>()
        val pendingFiles = mutableListOf<Pair<List<String>, ByteArray>>()
        transformed.rows(FILE_TABLE).forEach { row ->
            val finalPhysicalRoot = Paths.get(row.requiredString("physical_root_path"))
            val rootToken = finalPhysicalRoot.fileName.toString()
            val relative = normalizeMaterializedPath(row.requiredString("materialized_path"))
            val treePath = listOf(rootToken) + relative
            val identity = treePath.joinToString("/")
            if (!seen.add(identity)) {
                throw BackupValidationException("恢复文件路径重复或越界")
            }
            (1..treePath.size).forEach { depth ->
                if (depth < treePath.size || row.requiredBoolean("is_directory")) {
                    directories += treePath.take(depth)
                }
            }
            if (row.requiredBoolean("is_directory")) {
                // 目录统一在 descriptor-relative mkdir 阶段创建。
            } else {
                pendingFiles += treePath to files.getValue(row.requiredString("uuid"))
            }
        }
        directories.sortedBy { it.size }.forEach { restoreFileOperations.createDirectory(stagingRoot, it) }
        val restoredIdentities = linkedMapOf<String, String>()
        transformed.rows(FILE_TABLE)
            .filter { row ->
                row.requiredString("uuid") == row.requiredString("workspace_root_uuid") &&
                    row.optionalString("parent_uuid") == null
            }
            .forEach { rootRow ->
                val rootToken = Paths.get(rootRow.requiredString("physical_root_path")).fileName.toString()
                restoredIdentities[rootRow.requiredString("uuid")] = restoreFileOperations.initializeWorkspaceRootIdentity(
                    stagingRoot,
                    listOf(rootToken),
                    rootRow.requiredString("hash"),
                )
            }
        pendingFiles.forEach { (relative, bytes) -> restoreFileOperations.writeNew(stagingRoot, relative, bytes) }
        val expected = buildSet {
            add(RestoreTreeEntry(OWNER_MARKER, false))
            directories.forEach { add(RestoreTreeEntry(it.joinToString("/"), true)) }
            transformed.rows(FILE_TABLE)
                .filter { it.requiredString("uuid") == it.requiredString("workspace_root_uuid") && it.optionalString("parent_uuid") == null }
                .forEach { row ->
                    val token = Paths.get(row.requiredString("physical_root_path")).fileName.toString()
                    add(RestoreTreeEntry("$token/.infinity_root_identity", false))
                }
            pendingFiles.forEach { (path, _) -> add(RestoreTreeEntry(path.joinToString("/"), false)) }
        }
        restoreFileOperations.verifyAndSync(stagingRoot, expected)
        val rewrittenTables = transformed.tables.toMutableMap()
        rewrittenTables[FILE_TABLE] = transformed.rows(FILE_TABLE).map { row ->
            restoredIdentities[row.requiredString("uuid")]?.let { identity ->
                JsonObject(row + ("hash" to JsonPrimitive(identity)))
            } ?: row
        }
        return StagedWorkspaceFiles(expected, transformed.copy(tables = rewrittenTables))
    }

    private fun moveAtomically(source: Path, target: Path) {
        try {
            restoreFileOperations.moveTree(
                target.parent,
                source.fileName.toString(),
                target.fileName.toString(),
            )
        } catch (error: AtomicMoveNotSupportedException) {
            throw BackupValidationException("恢复目录所在文件系统不支持原子移动", error)
        }
        FileRestoreJournal.syncDirectory(target.parent)
    }

    private fun clearAllRestorableTables() {
        val sqlite = database.openHelper.writableDatabase
        (DERIVED_DELETE_ORDER + DELETE_ORDER).forEach { sqlite.execSQL("DELETE FROM `$it`") }
    }

    private fun insertPayload(payload: DatabaseBackupPayload) {
        val sqlite = database.openHelper.writableDatabase
        INSERT_ORDER.forEach { table ->
            val rows = if (table == "messages") {
                sortMessagesParentFirst(payload.rows(table))
            } else {
                payload.rows(table)
            }
            rows.forEach { row ->
                val values = ContentValues(row.size)
                row.forEach { (column, value) ->
                    when (value) {
                        JsonNull -> values.putNull(column)
                        is JsonPrimitive -> when {
                            value.isString -> values.put(column, value.content)
                            value.booleanOrNull != null -> values.put(column, if (value.booleanOrNull == true) 1L else 0L)
                            value.content.contains('.') || value.content.contains('e', true) ->
                                values.put(column, value.doubleOrNull ?: throw BackupValidationException("数值字段无效"))
                            else -> values.put(column, value.longOrNull ?: throw BackupValidationException("整数字段无效"))
                        }
                        else -> throw BackupValidationException("数据库字段类型无效")
                    }
                }
                if (sqlite.insert(table, 0, values) == -1L) {
                    throw BackupValidationException("恢复数据库表失败: $table")
                }
            }
        }
    }

    private fun validateUnique(payload: DatabaseBackupPayload, table: String, column: String) {
        val values = payload.rows(table).map { it.requiredString(column) }
        if (values.any(String::isBlank) || values.size != values.toSet().size) {
            throw BackupValidationException("$table 主键为空或重复")
        }
    }

    private fun validateCompositeUnique(payload: DatabaseBackupPayload, table: String, columns: List<String>) {
        val values = payload.rows(table).map { row -> columns.map { column -> row.requiredString(column) } }
        if (values.size != values.toSet().size) throw BackupValidationException("$table 复合主键重复")
    }

    private fun requireReference(row: JsonObject, column: String, ids: Set<String>, label: String) {
        if (row.requiredString(column) !in ids) throw BackupValidationException("$label 外键无效")
    }

    private fun requireOptionalReference(row: JsonObject, column: String, ids: Set<String>, label: String) {
        row.optionalString(column)?.let { if (it !in ids) throw BackupValidationException("$label 外键无效") }
    }

    private fun requireCanonicalSnapshotContent(content: Set<BackupContent>) {
        val required = setOf(BackupContent.DATABASE, BackupContent.PREFERENCES, BackupContent.FILES)
        if (!content.containsAll(required) || content.any { it !in required && it != BackupContent.SECRETS }) {
            throw BackupValidationException("初版备份只支持 DATABASE + PREFERENCES + FILES，SECRETS 可选")
        }
    }

    private fun validateSnapshotLimits(
        databaseBytes: ByteArray,
        preferenceBytes: ByteArray,
        files: Map<String, ByteArray>,
        secretValues: Map<SecretId, ByteArray>,
        skills: SkillBackupPartition? = null,
        avatars: Map<String, ByteArray> = emptyMap(),
    ) {
        if (databaseBytes.size.toLong() > BackupPackageLimits.MAX_ENTRY_BYTES ||
            preferenceBytes.size.toLong() > BackupPackageLimits.MAX_ENTRY_BYTES ||
            files.size + avatars.size + 2 + (if (secretValues.isEmpty()) 0 else 1) +
                (skills?.let { it.files.size + it.metadata.directories.size } ?: 0) > BackupPackageLimits.MAX_ENTRIES
        ) throw BackupValidationException("snapshot 超过备份 entry 限制", reason = BackupFailureReason.CAPACITY_EXCEEDED)
        var total = databaseBytes.size.toLong() + preferenceBytes.size.toLong()
        (files.values + secretValues.values + skills?.files.orEmpty().values + avatars.values).forEach { bytes ->
            if (bytes.size.toLong() > BackupPackageLimits.MAX_ENTRY_BYTES) {
                throw BackupValidationException("snapshot 内容项超过大小限制", reason = BackupFailureReason.CAPACITY_EXCEEDED)
            }
            total = Math.addExact(total, bytes.size.toLong())
            if (total > BackupPackageLimits.MAX_TOTAL_BYTES) throw BackupValidationException("snapshot 超过总大小限制", reason = BackupFailureReason.CAPACITY_EXCEEDED)
        }
        if (total > BackupPackageLimits.MAX_IN_MEMORY_BYTES) {
            throw BackupValidationException("snapshot 超过 Android 内存安全限制", reason = BackupFailureReason.CAPACITY_EXCEEDED)
        }
    }

    private fun requireCanonicalRestoreContent(validated: ValidatedBackup) {
        val paths = validated.manifest.entries.map { it.path }.toSet()
        if (validated.manifest.formatVersion !in 1..DefaultBackupPackageCodec.FORMAT_VERSION ||
            "database.json" !in paths || "preferences.json" !in paths ||
            validated.database.isEmpty() || validated.preferences.isEmpty() ||
            validated.files.keys.any { "files/$it" !in paths } ||
            paths.filter { it.startsWith("files/") }.map { it.removePrefix("files/") }.toSet() != validated.files.keys ||
            (("secrets.json" in paths) != validated.manifest.containsSecrets) ||
            (validated.secrets.isNotEmpty() && "secrets.json" !in paths)
        ) throw BackupValidationException("恢复包不是规范全量包")
        val skills = validated.skills
        val skillPaths = paths.filter { it.startsWith(SkillBackupPaths.PREFIX) }
            .map { it.removePrefix(SkillBackupPaths.PREFIX) }.toSet()
        if (validated.manifest.skills != skills?.metadata || skillPaths != skills?.files.orEmpty().keys ||
            paths.size + (skills?.metadata?.directories?.size ?: 0) > BackupPackageLimits.MAX_ENTRIES
        ) throw BackupValidationException("恢复技能分区与清单不一致")
        skills?.let { SkillBackupPaths.validate(it.metadata, it.files.keys, it.files.values.sumOf { bytes -> bytes.size.toLong() }) }
        val materializedTotal = validated.database.size.toLong() + validated.preferences.size +
            validated.files.values.sumOf { it.size.toLong() } + validated.secrets.values.sumOf { it.size.toLong() } +
            skills?.files.orEmpty().values.sumOf { it.size.toLong() } + validated.avatars.values.sumOf { it.size.toLong() }
        if (materializedTotal > BackupPackageLimits.MAX_IN_MEMORY_BYTES) {
            throw BackupValidationException("恢复包超过 Android 内存安全限制")
        }
    }

    private fun requireTrustedSourceRoot(value: String): Path {
        val root = Paths.get(value).toAbsolutePath().normalize()
        FileRestoreJournal.rejectSymlinkAncestors(root)
        val real = root.toRealPath(LinkOption.NOFOLLOW_LINKS)
        if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(real) ||
            !SAFE_TOKEN.matches(real.fileName.toString())
        ) throw BackupValidationException("文件根目录不存在或身份无效")
        val expectedKey = fileKey(real)
        fun sameDirectory(left: Path?, right: Path): Boolean = left != null &&
            runCatching { Files.isSameFile(left, right) }.getOrDefault(false)
        val directBases = trustedSourceBases.filter { sameDirectory(real.parent, it) }
        if (directBases.size > 1) throw BackupValidationException("文件根目录匹配多个受信父目录")
        // 系统别名只在父目录身份相同后重拼；保留 base 顺序和拼法，兼容旧 journal 的 baseIndex。
        val rebound = directBases.singleOrNull()?.resolve(real.fileName) ?: run {
            val container = real.parent ?: throw BackupValidationException("文件根目录没有父目录")
            if (!sameDirectory(container.parent, restoreParent) ||
                !RESTORE_ROOT.matches(container.fileName.toString()) || !isOwnedRestoreContainer(container)
            ) throw BackupValidationException("文件根目录不属于受信 app-private base 的直接子目录")
            restoreParent.resolve(container.fileName).resolve(real.fileName)
        }
        FileRestoreJournal.rejectSymlinkAncestors(rebound)
        if (!Files.isDirectory(rebound, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(rebound) ||
            fileKey(rebound) != expectedKey || fileKey(real) != expectedKey
        ) throw BackupValidationException("文件根目录在系统别名核验期间发生变化")
        return rebound
    }

    private fun fingerprint(payload: DatabaseBackupPayload): String {
        val digest = MessageDigest.getInstance("SHA-256")
        INSERT_ORDER.sorted().forEach { table ->
            digest.update(table.toByteArray())
            payload.rows(table).map(::canonicalRow).sorted().forEach { digest.update(it.toByteArray()) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun canonicalRow(row: JsonObject): String = row.entries.sortedBy { it.key }
        .joinToString(prefix = "{", postfix = "}") { (key, value) -> "$key=$value" }

    private fun fileKey(path: Path): String = Files.readAttributes(
        path,
        java.nio.file.attribute.BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS,
    ).fileKey()?.toString() ?: throw BackupValidationException("文件系统未提供稳定 fileKey")

    private fun currentOldRootIdentity(): String {
        val rows = readDatabasePayload().rows(FILE_TABLE)
        val groups = rows.groupBy { row ->
            managedContainer(requireTrustedSourceRoot(row.requiredString("physical_root_path")))
        }
        return groups.entries.joinToString(",") { (managed, managedRows) ->
            val expected = expectedManagedInventory(managed, managedRows)
            val actual = restoreFileOperations.inventory(managed.path.parent, managed.path.fileName.toString())
            if (actual != expected) throw BackupValidationException(
                "旧 managed root 包含未登记、缺失或异常内容；missing=${expected - actual}；unexpected=${actual - expected}",
            )
            val encodedKey = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(PersistentFileIdentity.read(managed.path).toByteArray())
            "${managed.baseIndex}:$encodedKey:${inventoryToken(expected)}"
        }
    }

    private suspend fun requireWorkspaceMutationJournalRecovered() {
        if (database.workspaceMutationDao().getPrepared().isNotEmpty()) {
            throw BackupValidationException("存在未恢复的 workspace mutation journal")
        }
    }

    private suspend fun finishCommitted(record: RestoreJournalRecord) {
        requireOwnedRestoreRoot(restoreParent.resolve(record.newRootIdentity), record)
        record.skills?.let { requireSkillRestore().finish(record.txId, it) }
        record.avatars?.let { requireAvatarRestore().finish(record.txId, it) }
        preferences.finalizePrepared(record.txId)
        secrets.finalizePrepared(record.txId)
        deleteOldRoots(record)
        writeCompletedReceipt(record)
        crashHook.hit(RestoreCrashPoint.RECEIPT_PERSISTED)
        journal.delete()
    }

    private fun validateAvatarPartition(validated: ValidatedBackup) {
        val entries = validated.manifest.entries.filter { it.path.startsWith(AvatarBackupFiles.PREFIX) }
        if (entries.map { it.path.removePrefix(AvatarBackupFiles.PREFIX) }.toSet() != validated.avatars.keys ||
            entries.size != validated.avatars.size || validated.manifest.formatVersion < 3 && entries.isNotEmpty()
        ) throw BackupValidationException("头像分区与清单不一致")
        AvatarBackupFiles.validateSizes(validated.avatars.mapValues { it.value.size.toLong() })
        entries.forEach { entry ->
            val bytes = validated.avatars.getValue(entry.path.removePrefix(AvatarBackupFiles.PREFIX))
            if (entry.sizeBytes != bytes.size.toLong() || entry.sha256 != sha256(bytes)) {
                throw BackupValidationException("头像文件大小或摘要不一致")
            }
        }
    }

    private fun requireAvatarRestore(): SkillRestoreTransaction = avatarRestore
        ?: throw BackupValidationException("头像恢复 journal 存在，但头像根目录未配置")

    private fun requireSkillRestore(): SkillRestoreTransaction = skillRestore
        ?: throw BackupValidationException("技能恢复 journal 存在，但技能根目录未配置")

    private fun writeDatabaseCommitMarker(record: RestoreJournalRecord) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO audit_logs(id,action,resource_type,status,metadata,created_at) VALUES(?,?,?,?,?,?)",
            arrayOf<Any?>(
                databaseMarkerId(record.txId),
                DATABASE_MARKER_ACTION,
                "restore_transaction",
                "committed",
                record.expectedDatabaseFingerprint,
                System.currentTimeMillis(),
            ),
        )
    }

    private fun hasDatabaseCommitMarker(record: RestoreJournalRecord): Boolean =
        database.openHelper.writableDatabase.query(
            "SELECT metadata FROM audit_logs WHERE id = ? AND action = ? AND status = ?",
            arrayOf(databaseMarkerId(record.txId), DATABASE_MARKER_ACTION, "committed"),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use false
            if (cursor.getString(0) != record.expectedDatabaseFingerprint) {
                throw BackupValidationException("Room restore marker 与 journal 不一致")
            }
            true
        }

    private fun writeCompletedReceipt(record: RestoreJournalRecord) {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE audit_logs SET status = ?, metadata = ?, created_at = ? WHERE id = ? AND action = ?",
            arrayOf<Any?>(
                "completed",
                record.expectedDatabaseFingerprint,
                System.currentTimeMillis(),
                databaseMarkerId(record.txId),
                DATABASE_MARKER_ACTION,
            ),
        )
        if (!hasCompletedRestoreLocked(record.txId)) {
            throw BackupValidationException("恢复完成回执持久化失败")
        }
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM audit_logs WHERE action = ? AND status = ? AND id NOT IN " +
                "(SELECT id FROM audit_logs WHERE action = ? AND status = ? AND id != ? " +
                "ORDER BY created_at DESC, id DESC LIMIT ?) AND id != ?",
            arrayOf<Any?>(
                DATABASE_MARKER_ACTION,
                "completed",
                DATABASE_MARKER_ACTION,
                "completed",
                databaseMarkerId(record.txId),
                MAX_COMPLETED_RECEIPTS - 1,
                databaseMarkerId(record.txId),
            ),
        )
    }

    private fun hasCompletedRestoreLocked(operationId: String): Boolean =
        database.openHelper.writableDatabase.query(
            "SELECT 1 FROM audit_logs WHERE id = ? AND action = ? AND status = ? LIMIT 1",
            arrayOf(databaseMarkerId(operationId), DATABASE_MARKER_ACTION, "completed"),
        ).use { it.moveToFirst() }

    private fun requireOperationId(operationId: String) {
        if (!OPERATION_ID.matches(operationId)) throw BackupValidationException("恢复 operationId 无效")
    }

    private fun databaseMarkerId(txId: String): String = "__infinity_restore_$txId"

    private fun deleteOldRoots(record: RestoreJournalRecord) {
        val identity = record.oldRootIdentity
        if (identity.isBlank()) return
        identity.split(',').forEach { encoded ->
            val parts = encoded.split(':', limit = 3)
            if (parts.size != 3) throw BackupValidationException("旧 root identity 无效")
            val baseIndex = parts[0].toIntOrNull() ?: throw BackupValidationException("旧 root base identity 无效")
            val base = if (baseIndex == RESTORE_BASE_INDEX) restoreParent else {
                trustedSourceBases.getOrNull(baseIndex) ?: throw BackupValidationException("旧 root base identity 无效")
            }
            val expectedKey = try {
                String(java.util.Base64.getUrlDecoder().decode(parts[1]))
            } catch (error: IllegalArgumentException) {
                throw BackupValidationException("旧 root identity 编码无效", error)
            }
            val expectedNonce = PersistentFileIdentity.nonce(expectedKey)
            val matches = Files.newDirectoryStream(base).use { stream ->
                stream.filter { child ->
                    !Files.isSymbolicLink(child) && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) &&
                        PersistentFileIdentity.encode(expectedNonce, fileKey(child)) == expectedKey &&
                        requireOldRootIdentity(child, expectedKey)
                }.toList()
            }
            if (matches.size > 1) throw BackupValidationException("旧 root fileKey 匹配不唯一")
            val root = matches.singleOrNull() ?: return@forEach
            val actualInventory = restoreFileOperations.inventory(base, root.fileName.toString())
            val expectedHashes = if (parts[2] == "-") emptySet() else parts[2].split('.').toSet()
            val cleanupMarker = root.resolve(OLD_CLEANUP_MARKER)
            val markerPresent = Files.exists(cleanupMarker, LinkOption.NOFOLLOW_LINKS) &&
                runCatching { readSmallNoFollow(cleanupMarker, 128).toString(Charsets.UTF_8) == record.txId }
                    .getOrDefault(false)
            val actualHashes = inventoryEntryHashes(actualInventory.filterNot { it.path == OLD_CLEANUP_MARKER }.toSet())
            val tombstoneName = ".restore-delete-${record.txId}-${sha256(expectedKey.toByteArray()).take(12)}"
            val terminalPartial = root.fileName.toString() == tombstoneName && actualInventory.isEmpty()
            if (!terminalPartial && ((!markerPresent && actualHashes != expectedHashes) ||
                (markerPresent && !expectedHashes.containsAll(actualHashes))
            )) {
                throw BackupValidationException("旧 managed root inventory 已变化，拒绝整根删除")
            }
            if (!terminalPartial && !markerPresent) {
                restoreFileOperations.writeNew(root, listOf(OLD_CLEANUP_MARKER), record.txId.toByteArray())
            }
            val expectedDeleteInventory = when {
                terminalPartial -> emptySet()
                markerPresent -> actualInventory
                else -> actualInventory + RestoreTreeEntry(OLD_CLEANUP_MARKER, false)
            }
            val deleteRoot = if (root.fileName.toString() == tombstoneName) root else {
                restoreFileOperations.moveTree(base, root.fileName.toString(), tombstoneName)
                FileRestoreJournal.syncDirectory(base)
                base.resolve(tombstoneName)
            }
            val deleteMarker = if (expectedDeleteInventory.any { it.path == OLD_CLEANUP_MARKER }) {
                OLD_CLEANUP_MARKER to record.txId
            } else null
            restoreFileOperations.deleteTree(
                base, deleteRoot.fileName.toString(), expectedKey, deleteMarker, expectedDeleteInventory
            )
            FileRestoreJournal.syncDirectory(base)
        }
    }

    private fun requireOldRootIdentity(path: Path, expected: String): Boolean {
        val actual = try {
            PersistentFileIdentity.read(path)
        } catch (error: Exception) {
            throw BackupValidationException("旧 managed root 持久身份读取失败", error)
        }
        if (actual != expected) throw BackupValidationException("旧 managed root nonce 与 journal 不一致")
        return true
    }

    private fun managedContainer(root: Path): ManagedContainer {
        val directIndex = trustedSourceBases.indexOf(root.parent)
        if (directIndex >= 0) return ManagedContainer(root, directIndex)
        val container = root.parent
        if (container?.parent == restoreParent && RESTORE_ROOT.matches(container.fileName.toString()) &&
            isOwnedRestoreContainer(container)
        ) return ManagedContainer(container, RESTORE_BASE_INDEX)
        throw BackupValidationException("无法确定 workspace root 的受信容器")
    }

    private fun expectedManagedInventory(
        managed: ManagedContainer,
        rows: List<JsonObject>,
    ): Set<RestoreTreeEntry> = buildSet {
        if (managed.baseIndex == RESTORE_BASE_INDEX) add(RestoreTreeEntry(OWNER_MARKER, false))
        rows.forEach { row ->
            val physicalRoot = requireTrustedSourceRoot(row.requiredString("physical_root_path"))
            val prefix = if (physicalRoot == managed.path) emptyList() else listOf(physicalRoot.fileName.toString())
            val relative = prefix + normalizeMaterializedPath(row.requiredString("materialized_path"))
            val isDirectory = row.requiredBoolean("is_directory")
            if (row.requiredString("uuid") == row.requiredString("workspace_root_uuid") &&
                row.optionalString("parent_uuid") == null &&
                PersistentFileIdentity.isValid(row.requiredString("hash"))
            ) {
                add(RestoreTreeEntry((prefix + ".infinity_root_identity").joinToString("/"), false))
            }
            val ancestorLimit = if (isDirectory) relative.size else relative.size - 1
            (1..ancestorLimit.coerceAtLeast(0)).forEach { depth ->
                add(RestoreTreeEntry(relative.take(depth).joinToString("/"), true))
            }
            if (!isDirectory) add(RestoreTreeEntry(relative.joinToString("/"), false))
        }
    }

    private fun inventoryEntryHashes(entries: Set<RestoreTreeEntry>): Set<String> = entries.mapTo(linkedSetOf()) {
        sha256("${if (it.directory) 'd' else 'f'}:${it.path}".toByteArray())
    }

    private fun inventoryToken(entries: Set<RestoreTreeEntry>): String =
        inventoryEntryHashes(entries).sorted().joinToString(".").ifEmpty { "-" }

    private fun isOwnedRestoreContainer(container: Path): Boolean {
        val txId = container.fileName.toString().removePrefix("restore-")
        return runCatching { readSmallNoFollow(container.resolve(OWNER_MARKER), 128).toString(Charsets.UTF_8) == txId }
            .getOrDefault(false)
    }

    private fun deleteOwnedRestoreRoots(record: RestoreJournalRecord) {
        listOf("staging" to ".restore-${record.txId}.tmp", "new" to record.newRootIdentity)
            .forEach { (kind, originalName) ->
                val tombstoneName = ".restore-delete-${record.txId}-$kind"
                var root = listOf(restoreParent.resolve(originalName), restoreParent.resolve(tombstoneName))
                    .firstOrNull { Files.exists(it, LinkOption.NOFOLLOW_LINKS) } ?: return@forEach
                val terminalPartial = root.fileName.toString() == tombstoneName &&
                    restoreFileOperations.inventory(restoreParent, tombstoneName).isEmpty()
                if (!terminalPartial) {
                    requireOwnedRestoreRoot(root, record)
                }
                val expectedInventory = if (terminalPartial) emptySet() else {
                    restoreFileOperations.inventory(restoreParent, root.fileName.toString())
                }
                val expectedKey = if (kind == "new") {
                    record.newRootFileKey ?: PersistentFileIdentity.read(root)
                } else PersistentFileIdentity.read(root)
                if (root.fileName.toString() != tombstoneName) {
                    restoreFileOperations.moveTree(restoreParent, root.fileName.toString(), tombstoneName)
                    FileRestoreJournal.syncDirectory(restoreParent)
                    root = restoreParent.resolve(tombstoneName)
                }
                val marker = if (expectedInventory.any { it.path == OWNER_MARKER }) OWNER_MARKER to record.txId else null
                restoreFileOperations.deleteTree(
                    restoreParent,
                    tombstoneName,
                    expectedKey,
                    marker,
                    expectedInventory,
                )
            }
        FileRestoreJournal.syncDirectory(restoreParent)
    }

    private fun requireOwnedRestoreRoot(root: Path, record: RestoreJournalRecord) {
        if (root.parent != restoreParent || Files.isSymbolicLink(root) ||
            !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
        ) throw BackupValidationException("恢复 root 身份无效")
        record.newRootFileKey?.let { expected ->
            if (root.fileName.toString() in setOf(record.newRootIdentity, ".restore-delete-${record.txId}-new") &&
                PersistentFileIdentity.read(root) != expected
            ) {
                throw BackupValidationException("恢复 root fileKey 已变化")
            }
        }
        val markerValue = readSmallNoFollow(root.resolve(OWNER_MARKER), 128).toString(Charsets.UTF_8)
        if (markerValue != record.txId) throw BackupValidationException("恢复 root owner marker 无效")
    }

    private fun readSmallNoFollow(path: Path, limit: Long): ByteArray {
        java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val attributes = Files.readAttributes(
                path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
            )
            if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.size() > limit) {
                throw BackupValidationException("受信元数据文件无效")
            }
            val buffer = java.nio.ByteBuffer.allocate((limit + 1).toInt())
            var total = 0
            while (true) {
                val count = channel.read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) throw BackupValidationException("受信元数据文件超过限制")
            }
            return buffer.array().copyOf(total)
        }
    }

    private fun logicalRoot(row: JsonObject): String =
        row.optionalString("workspace_root_uuid")?.takeIf(String::isNotBlank)
            ?: "path:${row.requiredString("physical_root_path")}"

    private fun safeRootToken(logicalRoot: String): String {
        return "root-${sha256(logicalRoot.toByteArray())}"
    }

    private fun normalizeMaterializedPath(value: String): List<String> {
        if (value.indexOf('\u0000') >= 0) throw BackupValidationException("文件路径包含 NUL")
        val normalizedSeparators = value.replace('\\', '/')
        val parts = normalizedSeparators.split('/').filter(String::isNotEmpty)
        if (parts.any { it == "." || it == ".." }) throw BackupValidationException("文件路径越界")
        if (parts.any { com.promenar.infinity.domain.workspace.WorkspaceInternalPaths.hasInternalPrefix(it) }) {
            throw BackupValidationException("文件路径占用系统保留名称")
        }
        return parts
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun DatabaseBackupPayload.rows(table: String): List<JsonObject> =
        tables[table] ?: throw BackupValidationException("数据库 payload 缺少表: $table")

    private fun DatabaseBackupPayload.ids(table: String, column: String): Set<String> =
        rows(table).map { it.requiredString(column) }.toSet()

    private fun JsonObject.requiredString(column: String): String =
        (get(column) as? JsonPrimitive)?.contentOrNull
            ?: throw BackupValidationException("数据库字段缺失或类型无效: $column")

    private fun JsonObject.optionalString(column: String): String? {
        val value = get(column) ?: throw BackupValidationException("数据库字段缺失: $column")
        return if (value is JsonNull) null else (value as? JsonPrimitive)?.contentOrNull
            ?: throw BackupValidationException("数据库字段类型无效: $column")
    }

    private fun JsonObject.requiredLong(column: String): Long =
        (get(column) as? JsonPrimitive)?.longOrNull
            ?: throw BackupValidationException("数据库整数字段无效: $column")

    private fun JsonObject.requiredBoolean(column: String): Boolean {
        val primitive = get(column) as? JsonPrimitive
            ?: throw BackupValidationException("数据库布尔字段无效: $column")
        return primitive.booleanOrNull ?: when (primitive.longOrNull) {
            0L -> false
            1L -> true
            else -> throw BackupValidationException("数据库布尔字段无效: $column")
        }
    }

    private data class SnapshotFilesResult(
        val payload: DatabaseBackupPayload,
        val files: Map<String, ByteArray>,
    )

    private data class RoomColumn(
        val name: String,
        val affinity: String,
        val notNull: Boolean,
        val primaryKeyOrder: Int,
    )

    private data class ManagedContainer(val path: Path, val baseIndex: Int)

    private class MaterializationBudget(private val limit: Long) {
        private var used = 0L
        fun consume(bytes: Long) {
            used = Math.addExact(used, bytes)
            if (used > limit) throw BackupValidationException("数据库快照超过 Android 内存安全限制", reason = BackupFailureReason.CAPACITY_EXCEEDED)
        }
    }

    private class ClosingSeekableByteChannel(
        private val delegate: java.nio.channels.SeekableByteChannel,
        private val directory: java.nio.file.DirectoryStream<Path>,
    ) : java.nio.channels.SeekableByteChannel by delegate {
        override fun close() {
            var failure: Throwable? = null
            runCatching { delegate.close() }.onFailure { failure = it }
            runCatching { directory.close() }.onFailure { error -> failure?.addSuppressed(error) ?: run { failure = error } }
            failure?.let { throw it }
        }
    }

    private companion object {
        const val MAX_CONFIGURATION_SNAPSHOT_ATTEMPTS = 3
        const val DATABASE_SCHEMA_VERSION = DATABASE_BACKUP_SCHEMA_VERSION
        const val LONG_TERM_MEMORY_TABLE = "long_term_memories"
        const val MAX_LONG_TERM_MEMORIES = LongTermMemoryValidation.MAX_MEMORIES
        const val PARAMETER_SCHEME_TABLE = "parameter_schemes"
        const val FILE_TABLE = "workspace_files"
        const val WORKSPACE_TABLE = "workspaces"
        const val OWNER_MARKER = ".restore-owner"
        const val OLD_CLEANUP_MARKER = ".restore-cleanup-owner"
        const val DATABASE_MARKER_ACTION = "__infinity_restore_commit"
        val SAFE_TOKEN = Regex("[A-Za-z0-9._-]{1,128}")
        val RESTORE_ROOT = Regex("restore-[A-Za-z0-9-]{1,72}")
        const val RESTORE_BASE_INDEX = -1
        const val MAX_COMPLETED_RECEIPTS = 64
        val OPERATION_ID = Regex("[A-Za-z0-9-]{1,64}")

        // 用户源数据。明确排除 vectors/FTS/KG/JIT/vectorization_tasks/audit_logs/
        // tool_execution_ledger/file_versions，它们均为可重建派生数据、运行态账本或版本缓存。
        // external_mounts/external_snapshots 依赖本机目录授权，不进入可移植备份。
        // workspaces 只是目录登记：恢复后目录不存在时由使用方显示不可用，不随备份携带目录内容。
        // agent_rule_revisions 与 file_versions 一样是版本缓存，不进入备份，恢复时清空。
        val INSERT_ORDER = listOf(
            WORKSPACE_TABLE,
            "agents",
            "sessions",
            "messages",
            "attachments",
            "artifacts",
            "context_summaries",
            "tags",
            "document_tags",
            "task_nodes",
            "state_board_versions",
            LONG_TERM_MEMORY_TABLE,
            "custom_skills",
            "mcp_servers",
            PARAMETER_SCHEME_TABLE,
            FILE_TABLE,
            "workspace_seq",
        )
        val DELETE_ORDER = INSERT_ORDER.reversed()
        val DERIVED_DELETE_ORDER = listOf(
            "agent_rule_revisions",
            "external_snapshots",
            "mcp_tool_snapshots", "workspace_mutations", "tool_execution_ledger", "file_versions", "vectorization_tasks",
            "kg_evidence", "kg_jit_cache_sources", "kg_edges", "kg_nodes", "kg_jit_cache", "vectors_fts", "vectors", "audit_logs",
        )
    }
}
