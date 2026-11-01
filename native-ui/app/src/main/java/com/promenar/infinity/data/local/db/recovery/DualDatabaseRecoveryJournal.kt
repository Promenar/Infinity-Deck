package com.promenar.infinity.data.local.db.recovery

import com.promenar.infinity.data.backup.RestoreJournalAuthenticator
import com.promenar.infinity.data.repository.PersistentFileIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64

internal interface DualDatabaseRecoveryJournal {
    fun read(): DualDatabaseRecoveryRecord?
    fun write(record: DualDatabaseRecoveryRecord)
}

@Serializable
private data class AuthenticatedDualDatabaseJournal(
    val payloadBase64: String,
    val signatureBase64: String,
)

internal class FileDualDatabaseRecoveryJournal(
    base: Path,
    private val authenticator: RestoreJournalAuthenticator,
) : DualDatabaseRecoveryJournal {
    private val root = base.toAbsolutePath().normalize().also {
        require(Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it)) {
            "双库恢复 journal 根目录无效"
        }
    }
    private val path = root.resolve(FILE_NAME)

    override fun read(): DualDatabaseRecoveryRecord? {
        cleanupTemporary()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw IllegalStateException("双库恢复 journal 不是普通文件")
        }
        val size = Files.size(path)
        check(size in 1..MAX_BYTES) { "双库恢复 journal 大小无效" }
        val envelope = runCatching {
            json.decodeFromString<AuthenticatedDualDatabaseJournal>(
                Files.readAllBytes(path).toString(Charsets.UTF_8),
            )
        }.getOrElse { throw IllegalStateException("双库恢复 journal 格式损坏", it) }
        val payload = runCatching { Base64.getDecoder().decode(envelope.payloadBase64) }
            .getOrElse { throw IllegalStateException("双库恢复 journal payload 非法", it) }
        val signature = runCatching { Base64.getDecoder().decode(envelope.signatureBase64) }
            .getOrElse { payload.fill(0); throw IllegalStateException("双库恢复 journal 签名非法", it) }
        try {
            check(authenticator.verify(payload, signature)) { "双库恢复 journal 认证失败" }
            return json.decodeFromString<DualDatabaseRecoveryRecord>(payload.toString(Charsets.UTF_8))
                .also(::validate)
        } catch (error: IllegalStateException) {
            throw error
        } catch (error: Exception) {
            throw IllegalStateException("双库恢复 journal payload 损坏", error)
        } finally {
            payload.fill(0)
            signature.fill(0)
        }
    }

    override fun write(record: DualDatabaseRecoveryRecord) {
        validate(record)
        val payload = json.encodeToString(record).toByteArray(Charsets.UTF_8)
        val signature = authenticator.sign(payload)
        val encoded = json.encodeToString(
            AuthenticatedDualDatabaseJournal(
                Base64.getEncoder().encodeToString(payload),
                Base64.getEncoder().encodeToString(signature),
            ),
        ).toByteArray(Charsets.UTF_8)
        check(encoded.size <= MAX_BYTES) { "双库恢复 journal 超过大小上限" }
        val temporary = root.resolve("$FILE_NAME.${record.transactionId}.tmp")
        try {
            Files.deleteIfExists(temporary)
            FileChannel.open(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                var offset = 0
                while (offset < encoded.size) {
                    offset += channel.write(ByteBuffer.wrap(encoded, offset, encoded.size - offset))
                }
                channel.force(true)
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (error: AtomicMoveNotSupportedException) {
                throw IllegalStateException("双库恢复 journal 需要原子替换", error)
            }
            forceDirectory(root)
        } finally {
            payload.fill(0)
            signature.fill(0)
            encoded.fill(0)
            Files.deleteIfExists(temporary)
        }
    }

    private fun validate(record: DualDatabaseRecoveryRecord) {
        check(record.formatVersion == DUAL_DATABASE_RECOVERY_FORMAT) { "双库恢复 journal 版本不支持" }
        check(record.transactionId.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "双库恢复 transactionId 无效" }
        check(record.legacy.role == "legacy" && record.current.role == "current") { "双库来源角色无效" }
        check(record.legacy.userVersion == 17 && record.legacy.roomIdentity == PUBLIC_V17_IDENTITY) {
            "双库历史来源合同无效"
        }
        check(
            (record.current.userVersion == 2 && record.current.roomIdentity == PUBLIC_V2_IDENTITY) ||
                (record.current.userVersion == 5 && record.current.roomIdentity == INTERNAL_V5_IDENTITY),
        ) { "双库当前来源合同无效" }
        listOf(record.legacy, record.current).forEach { source ->
            check(source.files.isNotEmpty() && source.files == source.files.sortedBy(RecoveryFileFingerprint::relativePath)) {
                "双库来源文件清单无效"
            }
            check(source.files.all { it.relativePath.isSafeRecoveryRelative() && it.size >= 0L && it.sha256.isSha256() }) {
                "双库来源文件条目无效"
            }
            check(source.manifestSha256 == recoveryManifestSha256(source.files)) { "双库来源 manifest 摘要不匹配" }
        }
        check(record.snapshotManifestSha256.isSha256()) { "双库快照摘要无效" }
        check(!record.writerGateOpened || record.stage == DualDatabaseRecoveryStage.COMPLETE) {
            "双库恢复完成前禁止开放 writer"
        }
        record.treePublications.forEach { tree ->
            check(tree.role in setOf("legacy", "current") &&
                tree.snapshotTransactionId.matches(Regex("[A-Za-z0-9-]{1,128}")) &&
                tree.sourcePath.startsWith('/') && tree.targetRelativePath.isSafeRecoveryRelative() &&
                tree.sourceRootIdentity.isSha256() && tree.sourceTreeSha256.isSha256()) {
                "双库恢复文件树记录无效"
            }
            check(tree.activeWorkspace == !tree.ownerSessionId.isNullOrBlank() &&
                !(tree.activeWorkspace && tree.assetBundle)) {
                "双库恢复工作区认领记录无效"
            }
            check(!(tree.activeWorkspace || tree.assetBundle) || tree.targetRootIdentity == null ||
                PersistentFileIdentity.isValid(tree.targetRootIdentity)) {
                "双库恢复工作区目标身份无效"
            }
            check(tree.activeWorkspace || tree.assetBundle || tree.targetRootIdentity == null) {
                "保全树不得携带目标身份"
            }
            check(tree.selectedRelativeFiles == tree.selectedRelativeFiles.distinct().sorted() &&
                tree.selectedRelativeFiles.all(String::isSafeRecoveryRelative) &&
                (tree.assetBundle || tree.selectedRelativeFiles.isEmpty())) {
                "双库恢复资产选择清单无效"
            }
            check(!tree.published || tree.targetTreeSha256.isSha256()) {
                "双库恢复文件树发布回执无效"
            }
            check(!tree.published || !(tree.activeWorkspace || tree.assetBundle) || tree.targetRootIdentity != null) {
                "双库恢复文件树发布缺少身份回执"
            }
            check(!tree.published || tree.activeWorkspace || tree.assetBundle ||
                tree.targetTreeSha256 == tree.sourceTreeSha256) {
                "双库恢复保全树发布摘要不一致"
            }
        }
        check(record.treeArchiveAttempts.size <= 64) { "双库恢复树归档尝试过多" }
        record.treeArchiveAttempts.forEach { attempt ->
            check(attempt.role in setOf("legacy", "current") && attempt.sourcePath.startsWith('/') &&
                attempt.targetRelativePath.isSafeRecoveryRelative() &&
                attempt.snapshotTransactionId.matches(Regex("[A-Za-z0-9_-]{1,96}")) &&
                attempt.activeWorkspace == !attempt.ownerSessionId.isNullOrBlank() &&
                !(attempt.activeWorkspace && attempt.assetBundle) &&
                attempt.selectedRelativeFiles == attempt.selectedRelativeFiles.distinct().sorted() &&
                attempt.selectedRelativeFiles.all(String::isSafeRecoveryRelative) &&
                (attempt.assetBundle || attempt.selectedRelativeFiles.isEmpty())) {
                "双库恢复树归档意图无效"
            }
        }
        check(record.stage < DualDatabaseRecoveryStage.FILES_PUBLISHED ||
            record.treeArchivesComplete && record.treePublications.all(RecoveryTreePublication::published)) {
            "双库恢复文件树阶段缺少完整回执"
        }
        record.databaseMoves.forEach { move ->
            check(move.sourceRelativePath.isSafeRecoveryRelative() && move.retainedRelativePath.isSafeRecoveryRelative() &&
                move.sha256.isSha256() && move.size >= 0 && PersistentFileIdentity.isValid(move.sourceFileKey)) {
                "双库恢复数据库移动回执无效"
            }
        }
        if (record.stage >= DualDatabaseRecoveryStage.STAGED) {
            check(record.workingAttemptId?.matches(Regex("attempt-[A-Za-z0-9_-]{1,96}")) == true &&
                record.workingLegacySha256.isSha256() && record.workingCurrentSha256.isSha256()) {
                "双库 working 摘要缺失"
            }
        }
        if (record.stage >= DualDatabaseRecoveryStage.DB_PUBLISHING) {
            check(record.candidateRelativePath?.isSafeRecoveryRelative() == true &&
                record.candidateSha256.isSha256() &&
                record.candidateRoomIdentity in setOf(CURRENT_V18_IDENTITY, CURRENT_V19_IDENTITY, CURRENT_V20_IDENTITY, CURRENT_V21_IDENTITY, CURRENT_V22_IDENTITY, CURRENT_V23_IDENTITY, CURRENT_V24_IDENTITY, CURRENT_V25_IDENTITY, CURRENT_V26_IDENTITY, CURRENT_V27_IDENTITY, CURRENT_V28_IDENTITY, CURRENT_V29_IDENTITY, CURRENT_V30_IDENTITY, CURRENT_V31_IDENTITY, CURRENT_V32_IDENTITY)) {
                "双库候选数据库证明缺失"
            }
            check(!record.candidateCommitMarker.isNullOrBlank() && record.idMappingSha256.isSha256()) {
                "双库候选事务回执缺失"
            }
            check(!record.unresolvedUniqueData) { "双库恢复仍有唯一数据不可达" }
        }
    }

    private fun cleanupTemporary() {
        Files.newDirectoryStream(root, "$FILE_NAME.*.tmp").use { entries ->
            entries.forEach { temporary ->
                if (Files.isSymbolicLink(temporary)) throw IllegalStateException("双库恢复临时 journal 是符号链接")
                Files.deleteIfExists(temporary)
            }
        }
    }

    private fun forceDirectory(directory: Path) {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    }

    private companion object {
        const val FILE_NAME = ".dual-database-recovery-v1.json"
        const val MAX_BYTES = 2L * 1024L * 1024L
        val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = false }
    }
}

private fun String?.isSha256(): Boolean = this?.matches(Regex("[0-9a-f]{64}")) == true

private fun String.isSafeRecoveryRelative(): Boolean =
    isNotBlank() && !startsWith('/') && !contains('\\') && split('/').none { it.isBlank() || it == "." || it == ".." }
