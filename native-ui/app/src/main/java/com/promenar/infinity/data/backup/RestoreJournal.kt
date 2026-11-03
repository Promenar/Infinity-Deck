package com.promenar.infinity.data.backup

import com.promenar.infinity.data.repository.PersistentFileIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64

@Serializable
enum class RestoreJournalPhase { PREPARED, COMMITTED }

/** FileKey 字段保存版本化 nonce 与 inode 身份，不保存操作期间的完整 fileKey。 */
@Serializable
data class RestoreJournalRecord(
    val txId: String,
    val expectedDatabaseFingerprint: String,
    val oldRootIdentity: String,
    val newRootIdentity: String,
    val newRootFileKey: String?,
    val phase: RestoreJournalPhase,
    val skills: SkillRestoreJournalRecord? = null,
    val avatars: SkillRestoreJournalRecord? = null,
    val summary: BackupResultSummary? = null,
    val secretsIncluded: Boolean = true,
)

/** 可替换分区根与暂存目录使用相同的持久身份格式。 */
@Serializable
data class SkillRestoreJournalRecord(
    val rootName: String,
    val oldRootFileKey: String?,
    val newRootFileKey: String? = null,
    val stagingFileKey: String? = null,
)

interface RestoreJournalAuthenticator {
    fun sign(payload: ByteArray): ByteArray
    fun verify(payload: ByteArray, signature: ByteArray): Boolean
}

@Serializable
private data class AuthenticatedRestoreJournal(
    val payloadBase64: String,
    val signatureBase64: String,
)

internal interface RestoreJournal {
    fun read(): RestoreJournalRecord?
    fun write(record: RestoreJournalRecord)
    fun delete()
}

internal class FileRestoreJournal(
    base: Path,
    private val authenticator: RestoreJournalAuthenticator,
) : RestoreJournal {
    private val trustedBase = requireTrustedDirectory(base)
    private val journalPath = trustedBase.resolve(FILE_NAME)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    override fun read(): RestoreJournalRecord? {
        cleanupTemporaryFiles()
        if (!Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) return null
        val bytes = readBoundedNoFollow(journalPath, MAX_JOURNAL_BYTES, "恢复 journal")
        return try {
            val envelope = json.decodeFromString<AuthenticatedRestoreJournal>(bytes.toString(Charsets.UTF_8))
            val payload = Base64.getDecoder().decode(envelope.payloadBase64)
            val signature = Base64.getDecoder().decode(envelope.signatureBase64)
            try {
                if (!authenticator.verify(payload, signature)) {
                    throw BackupValidationException("恢复 journal 认证失败")
                }
                json.decodeFromString<RestoreJournalRecord>(payload.toString(Charsets.UTF_8)).also(::validateRecord)
            } finally {
                payload.fill(0)
                signature.fill(0)
            }
        } catch (error: Exception) {
            if (error is BackupValidationException) throw error
            throw BackupValidationException("恢复 journal 损坏，拒绝继续恢复", error)
        } finally {
            bytes.fill(0)
        }
    }

    override fun write(record: RestoreJournalRecord) {
        validateRecord(record)
        val temporary = trustedBase.resolve("$FILE_NAME.${record.txId}.tmp")
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            throw BackupValidationException("恢复 journal 临时文件已存在")
        }
        val payload = json.encodeToString(record).toByteArray(Charsets.UTF_8)
        val signature = authenticator.sign(payload)
        val bytes = json.encodeToString(
            AuthenticatedRestoreJournal(
                payloadBase64 = Base64.getEncoder().encodeToString(payload),
                signatureBase64 = Base64.getEncoder().encodeToString(signature),
            )
        ).toByteArray(Charsets.UTF_8)
        try {
            if (bytes.size > MAX_JOURNAL_BYTES) throw BackupValidationException("恢复journal超过安全限制", reason = BackupFailureReason.CAPACITY_EXCEEDED)
            FileChannel.open(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                channel.write(java.nio.ByteBuffer.wrap(bytes))
                channel.force(true)
            }
            try {
                Files.move(
                    temporary,
                    journalPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (error: AtomicMoveNotSupportedException) {
                throw BackupValidationException("恢复 journal 文件系统不支持原子替换", error)
            }
            syncDirectory(trustedBase)
        } finally {
            bytes.fill(0)
            payload.fill(0)
            signature.fill(0)
            Files.deleteIfExists(temporary)
        }
    }

    override fun delete() {
        if (Files.isSymbolicLink(journalPath)) throw BackupValidationException("拒绝删除符号链接 journal")
        Files.deleteIfExists(journalPath)
        cleanupTemporaryFiles()
        syncDirectory(trustedBase)
    }

    private fun cleanupTemporaryFiles() {
        Files.newDirectoryStream(trustedBase, "$FILE_NAME.*.tmp").use { stream ->
            stream.forEach { temporary ->
                if (Files.isSymbolicLink(temporary) || !Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)) {
                    throw BackupValidationException("恢复 journal 临时文件身份异常")
                }
                Files.delete(temporary)
            }
        }
    }

    private fun validateRecord(record: RestoreJournalRecord) {
        if (!TX_ID.matches(record.txId) || !ROOT_ID.matches(record.newRootIdentity) ||
            !SHA_256.matches(record.expectedDatabaseFingerprint) ||
            record.oldRootIdentity.toByteArray().size > MAX_OLD_ROOT_IDENTITY_BYTES ||
            record.oldRootIdentity.split(',').count(String::isNotBlank) > MAX_MANAGED_ROOTS ||
            record.oldRootIdentity.split(',').filter(String::isNotBlank).any { !OLD_ROOT_ID.matches(it) } ||
            record.newRootFileKey?.length.orZero() > 512
        ) throw BackupValidationException("恢复 journal 元数据无效")
        record.newRootFileKey?.let(::requirePersistentIdentity)
        record.oldRootIdentity.split(',').filter(String::isNotBlank).forEach { encoded ->
            val identity = try {
                String(Base64.getUrlDecoder().decode(encoded.split(':', limit = 3)[1]), Charsets.UTF_8)
            } catch (error: IllegalArgumentException) {
                throw BackupValidationException("恢复 journal 旧根身份编码无效", error)
            }
            requirePersistentIdentity(identity)
        }
        listOfNotNull(record.skills, record.avatars).forEach { skills ->
            if (!Regex("[A-Za-z0-9_-]{1,128}").matches(skills.rootName) ||
                listOf(skills.oldRootFileKey, skills.newRootFileKey, skills.stagingFileKey).any { it != null && (it.isBlank() || it.length > 512) }
            ) throw BackupValidationException("技能恢复 journal 元数据无效")
            listOfNotNull(skills.oldRootFileKey, skills.newRootFileKey, skills.stagingFileKey)
                .forEach(::requirePersistentIdentity)
        }
    }

    private fun requirePersistentIdentity(value: String) {
        try {
            PersistentFileIdentity.nonce(value)
        } catch (error: Exception) {
            throw BackupValidationException("恢复 journal 持久身份无效", error)
        }
    }

    private fun readBoundedNoFollow(path: Path, limit: Long, label: String): ByteArray {
        try {
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val before = Files.readAttributes(
                    path,
                    java.nio.file.attribute.BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                if (!before.isRegularFile || before.isSymbolicLink || before.size() > limit) {
                    throw BackupValidationException("$label 不是受信的有界普通文件")
                }
                val output = java.io.ByteArrayOutputStream(before.size().toInt())
                val buffer = java.nio.ByteBuffer.allocate(4096)
                var total = 0L
                while (true) {
                    buffer.clear()
                    val count = channel.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > limit) throw BackupValidationException("$label 超过大小限制")
                    output.write(buffer.array(), 0, count)
                }
                val after = Files.readAttributes(
                    path,
                    java.nio.file.attribute.BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                if (before.fileKey() != after.fileKey() || before.size() != after.size() || total != after.size()) {
                    throw BackupValidationException("$label 读取期间身份发生变化")
                }
                return output.toByteArray()
            }
        } catch (error: BackupValidationException) {
            throw error
        } catch (error: Exception) {
            throw BackupValidationException("无法安全读取$label", error)
        }
    }

    companion object {
        const val FILE_NAME = ".restore-journal.json"
        private const val MAX_JOURNAL_BYTES = 8L * 1024 * 1024
        private const val MAX_OLD_ROOT_IDENTITY_BYTES = 1024 * 1024
        private const val MAX_MANAGED_ROOTS = 1024
        private val TX_ID = Regex("[A-Za-z0-9-]{1,64}")
        private val ROOT_ID = Regex("restore-[A-Za-z0-9-]{1,72}")
        private val SHA_256 = Regex("[0-9a-f]{64}")
        private val OLD_ROOT_ID = Regex("-?[0-9]+:[A-Za-z0-9_-]{1,768}:(?:-|[0-9a-f]{64}(?:\\.[0-9a-f]{64})*)")

        fun requireTrustedDirectory(path: Path): Path {
            val absolute = path.toAbsolutePath().normalize()
            if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
                throw BackupValidationException("受信恢复目录不存在或不是目录")
            }
            rejectSymlinkAncestors(absolute)
            return absolute.toRealPath(LinkOption.NOFOLLOW_LINKS)
        }

        fun rejectSymlinkAncestors(path: Path) {
            var current: Path? = path.root
            path.forEach { segment ->
                current = current!!.resolve(segment)
                if (Files.isSymbolicLink(current)) {
                    // Android 的 /data/user/0 是由系统控制、父目录不可写的稳定别名。
                    // 只允许这种调用进程无法替换的系统祖先；任何位于可写父目录中的 symlink 仍 fail closed。
                    val parent = current!!.parent
                    if (parent == null || Files.isWritable(parent)) {
                        throw BackupValidationException("受信路径祖先包含可替换的符号链接")
                    }
                }
            }
        }

        fun syncDirectory(path: Path) {
            try {
                FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
            } catch (error: Exception) {
                throw BackupValidationException("当前平台无法 fsync 恢复目录，拒绝降低持久性保证", error)
            }
        }
    }
}

private fun Int?.orZero(): Int = this ?: 0
