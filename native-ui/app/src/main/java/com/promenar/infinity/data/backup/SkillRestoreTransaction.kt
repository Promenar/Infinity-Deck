package com.promenar.infinity.data.backup

import com.promenar.infinity.data.repository.PersistentFileIdentity
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** 技能与头像目录共用原子替换；before/after 身份由主恢复 journal 认证，不建立独立提交账本。 */
internal class SkillRestoreTransaction(
    root: Path,
    private val operations: RestoreFileOperations,
    private val crashHook: RestoreCrashHook,
    private val partitionName: String = "skills",
) {
    init { require(partitionName == "skills" || partitionName == "avatars") }
    private val parent = FileRestoreJournal.requireTrustedDirectory(root.toAbsolutePath().normalize().parent)
    private val root = parent.resolve(root.fileName)

    fun plan(txId: String): SkillRestoreJournalRecord {
        SkillBackupPaths.parts(root.fileName.toString())
        if (exists(container(txId))) throw BackupValidationException("分区恢复暂存目录已存在")
        return SkillRestoreJournalRecord(root.fileName.toString(), keyOrNull(root))
    }

    fun stage(txId: String, partition: SkillBackupPartition, record: SkillRestoreJournalRecord, preparingParent: Path): SkillRestoreJournalRecord {
        requireRecord(record)
        val blobs = partition.allBlobs()
        SkillBackupPaths.validate(partition.metadata, blobs.keys, blobs.values.sumOf { it.size })
        if (keyOrNull(root) != record.oldRootFileKey) throw BackupValidationException("技能根目录在恢复准备期间发生变化")
        val staging = container(txId)
        // Android 禁止 Files.getFileStore；跨文件系统时 descriptor 间的 renameat 以 EXDEV 失败，由主事务回滚。
        createOwnedContainer(staging, txId, preparingParent)
        operations.createDirectory(staging, listOf(NEW))
        partition.metadata.directories.sortedBy { it.split('/').size }.forEach { path ->
            operations.createDirectory(staging, listOf(NEW) + SkillBackupPaths.parts(path))
        }
        blobs.forEach { (path, blob) -> operations.writeStream(staging, listOf(NEW) + SkillBackupPaths.parts(path), blob) }
        val expected = buildSet {
            add(RestoreTreeEntry(OWNER, false))
            add(RestoreTreeEntry(NEW, true))
            partition.metadata.directories.forEach { add(RestoreTreeEntry("$NEW/$it", true)) }
            blobs.keys.forEach { add(RestoreTreeEntry("$NEW/$it", false)) }
        }
        operations.verifyAndSync(staging, expected)
        FileRestoreJournal.syncDirectory(parent)
        return record.copy(newRootFileKey = keyOrNull(staging.resolve(NEW)), stagingFileKey = keyOrNull(staging))
    }

    fun commit(txId: String, record: SkillRestoreJournalRecord) {
        requireRecord(record)
        val staging = requireContainer(txId, record)
        if (keyOrNull(root) != record.oldRootFileKey || keyOrNull(staging.resolve(NEW)) != record.newRootFileKey ||
            record.newRootFileKey == null
        ) throw BackupValidationException("技能恢复提交前目录身份已变化")
        if (record.oldRootFileKey != null) move(parent, root.fileName.toString(), staging, OLD)
        crashHook.hit(if (partitionName == "avatars") RestoreCrashPoint.AVATARS_OLD_MOVED else RestoreCrashPoint.SKILLS_OLD_MOVED)
        move(staging, NEW, parent, root.fileName.toString())
        crashHook.hit(if (partitionName == "avatars") RestoreCrashPoint.AVATARS_COMMITTED else RestoreCrashPoint.SKILLS_COMMITTED)
    }

    fun rollback(txId: String, record: SkillRestoreJournalRecord) {
        requireRecord(record)
        val staging = container(txId)
        if (!exists(staging)) {
            if (keyOrNull(root) != record.oldRootFileKey) throw BackupValidationException("技能恢复回滚缺少暂存目录")
            return
        }
        requireContainer(txId, record)
        var liveKey = keyOrNull(root)
        if (record.newRootFileKey != null && liveKey == record.newRootFileKey) {
            if (exists(staging.resolve(NEW))) throw BackupValidationException("技能恢复回滚目标已存在")
            move(parent, root.fileName.toString(), staging, NEW)
            liveKey = null
        }
        if (liveKey == null && record.oldRootFileKey != null) {
            if (keyOrNull(staging.resolve(OLD)) != record.oldRootFileKey) {
                throw BackupValidationException("技能恢复旧目录身份无效")
            }
            move(staging, OLD, parent, root.fileName.toString())
        } else if (liveKey != record.oldRootFileKey) throw BackupValidationException("技能恢复拒绝覆盖未知目录")
        if (exists(staging.resolve(OLD))) throw BackupValidationException("技能恢复回滚仍有未归还的旧目录")
        deleteContainer(txId, record)
    }

    fun recoverRecord(txId: String, record: SkillRestoreJournalRecord): SkillRestoreJournalRecord {
        requireRecord(record)
        if (!exists(container(txId)) || record.stagingFileKey != null) return record
        val staging = requireContainer(txId, record)
        return record.copy(stagingFileKey = keyOrNull(staging))
    }

    fun finish(txId: String, record: SkillRestoreJournalRecord) {
        requireRecord(record)
        if (record.newRootFileKey == null || keyOrNull(root) != record.newRootFileKey) {
            throw BackupValidationException("已提交技能目录身份无效")
        }
        if (!exists(container(txId))) return
        val staging = requireContainer(txId, record)
        if (exists(staging.resolve(NEW))) throw BackupValidationException("已提交技能恢复仍含未使用的目录")
        if (exists(staging.resolve(OLD))) {
            if (keyOrNull(staging.resolve(OLD)) != record.oldRootFileKey || record.oldRootFileKey == null) {
                throw BackupValidationException("技能恢复清理拒绝删除未知旧目录")
            }
            // 旧树可能包含符号链接；descriptor-relative 删除只删除链接本身。
            operations.deleteTree(staging, OLD, record.oldRootFileKey)
            FileRestoreJournal.syncDirectory(staging)
        }
        deleteContainer(txId, record)
    }

    private fun deleteContainer(txId: String, record: SkillRestoreJournalRecord) {
        val staging = requireContainer(txId, record)
        val children = Files.newDirectoryStream(staging).use { entries -> entries.map { it.fileName.toString() }.toSet() }
        if (children.any { it != OWNER && it != NEW }) throw BackupValidationException("技能恢复暂存目录含未知内容")
        // marker 最后删除，部分清理失败后仍可按 journal 和持久身份重试。
        val marker = if (exists(staging.resolve(OWNER))) OWNER to txId else null
        operations.deleteTree(parent, staging.fileName.toString(), keyOrNull(staging)!!, marker)
        FileRestoreJournal.syncDirectory(parent)
    }

    private fun requireContainer(txId: String, record: SkillRestoreJournalRecord): Path {
        val staging = container(txId)
        val key = keyOrNull(staging) ?: throw BackupValidationException("技能恢复暂存目录不存在")
        if (record.stagingFileKey != null && key != record.stagingFileKey) {
            throw BackupValidationException("技能恢复暂存目录身份已变化")
        }
        val marker = staging.resolve(OWNER)
        if (!exists(marker) && record.stagingFileKey == key &&
            Files.newDirectoryStream(staging).use { !it.iterator().hasNext() }
        ) return staging
        val attributes = Files.readAttributes(marker, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isRegularFile || attributes.size() > 128) throw BackupValidationException("技能恢复所有者标记无效")
        val bytes = ByteBuffer.allocate(129)
        FileChannel.open(marker, StandardOpenOption.READ, NOFOLLOW_LINKS).use { channel ->
            while (bytes.hasRemaining() && channel.read(bytes) >= 0) { }
        }
        if (bytes.position() > 128 || String(bytes.array(), 0, bytes.position(), Charsets.UTF_8) != txId) {
            throw BackupValidationException("技能恢复所有者标记不匹配")
        }
        return staging
    }

    private fun createOwnedContainer(staging: Path, txId: String, preparingParent: Path) {
        // 未发布的临时目录留在主事务所有者树内，标记写入前中断也能由主 journal 清理。
        val temporary = preparingParent.resolve(".$partitionName-preparing-${java.util.UUID.randomUUID()}")
        operations.createTransactionRoot(preparingParent, temporary.fileName.toString())
        try {
            operations.writeNew(temporary, listOf(OWNER), txId.toByteArray())
            operations.verifyAndSync(temporary, setOf(RestoreTreeEntry(OWNER, false)))
            move(preparingParent, temporary.fileName.toString(), parent, staging.fileName.toString())
        } catch (error: Throwable) {
            if (error is SimulatedRestoreProcessDeath) throw error
            if (exists(temporary)) {
                runCatching { operations.deleteTree(preparingParent, temporary.fileName.toString(), keyOrNull(temporary)!!) }
                    .exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        }
    }

    private fun requireRecord(record: SkillRestoreJournalRecord) {
        FileRestoreJournal.requireTrustedDirectory(parent)
        if (record.rootName != root.fileName.toString()) throw BackupValidationException("技能恢复根目录与 journal 不一致")
    }

    private fun move(from: Path, source: String, to: Path, target: String) {
        if (exists(to.resolve(target))) throw BackupValidationException("技能恢复移动目标已存在")
        operations.moveTreeBetween(from, source, to, target)
        FileRestoreJournal.syncDirectory(from)
        if (to != from) FileRestoreJournal.syncDirectory(to)
    }

    private fun keyOrNull(path: Path): String? {
        if (!exists(path)) return null
        FileRestoreJournal.rejectSymlinkAncestors(path)
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isDirectory || attributes.isSymbolicLink) throw BackupValidationException("技能恢复目录不是受信普通目录")
        return PersistentFileIdentity.read(path)
    }

    private fun exists(path: Path) = Files.exists(path, NOFOLLOW_LINKS)
    private fun container(txId: String): Path {
        val current = parent.resolve(".infinity-$partitionName-restore-$txId")
        if (partitionName != "skills") return current
        val legacy = parent.resolve(".nexara-skills-restore-$txId")
        if (exists(current) && exists(legacy)) throw BackupValidationException("新旧技能恢复暂存目录同时存在")
        return if (exists(legacy)) legacy else current
    }

    private companion object {
        const val OWNER = ".restore-owner"
        const val NEW = "new"
        const val OLD = "old"
    }
}
