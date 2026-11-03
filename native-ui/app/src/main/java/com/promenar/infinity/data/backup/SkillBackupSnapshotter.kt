package com.promenar.infinity.data.backup

import com.promenar.infinity.domain.workspace.WorkspaceInternalPaths
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

internal class SkillBackupSnapshotter(root: Path) {
    private val parent = FileRestoreJournal.requireTrustedDirectory(root.toAbsolutePath().normalize().parent)
    private val root = parent.resolve(root.fileName)
    private data class SourceFile(val path: Path, val attributes: BasicFileAttributes)

    fun snapshot(memoryBudget: Long, entryBudget: Int, directory: Path? = null): SkillBackupPartition {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return SkillBackupPartition(emptyMap(), SkillBackupMetadata())
        if (Files.isSymbolicLink(root)) {
            return SkillBackupPartition(emptyMap(), SkillBackupMetadata(summary = SkillBackupSummary(skippedSymbolicLinks = 1)))
        }
        FileRestoreJournal.requireTrustedDirectory(root)
        val rootBefore = attributes(root)
        val files = linkedMapOf<String, SourceFile>()
        val directories = linkedMapOf<String, BasicFileAttributes>()
        val gitRoots = mutableListOf<Path>()
        var skippedLinks = 0
        var total = 0L
        // .git 使用整包剩余字节与条目预算，超限时记录仓库信息。
        val gitByteLimit = minOf(SkillBackupPaths.MAX_BYTES, memoryBudget)
        val gitEntryLimit = minOf(SkillBackupPaths.MAX_GIT_ENTRIES, entryBudget)

        fun relative(path: Path) = root.relativize(path).joinToString("/")
        fun visit(start: Path, git: Boolean): Boolean {
            var complete = true
            Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir == root) return FileVisitResult.CONTINUE
                    // 挂载后端在技能根下保留 .infinity 内部区（写入暂存、删除隔离），这类内部名称不属于技能内容。
                    if (WorkspaceInternalPaths.hasInternalPrefix(dir.fileName.toString())) return FileVisitResult.SKIP_SUBTREE
                    val path = relative(dir)
                    if (dir.parent == root && SkillBackupPaths.isTemporaryDirectory(dir.fileName.toString())) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    if (!git && dir.fileName.toString() == ".git") {
                        gitRoots.add(dir)
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    SkillBackupPaths.parts(path)
                    directories[path] = attrs
                    return checkLimits()
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (WorkspaceInternalPaths.hasInternalPrefix(file.fileName.toString())) return FileVisitResult.CONTINUE
                    if (attrs.isSymbolicLink) {
                        skippedLinks++
                    } else if (attrs.isRegularFile) {
                        if (SkillBackupPaths.isCredential(file.fileName.toString())) return FileVisitResult.CONTINUE
                        val path = relative(file)
                        SkillBackupPaths.parts(path)
                        files[path] = SourceFile(file, attrs)
                        total = Math.addExact(total, attrs.size())
                    } else throw BackupValidationException("技能目录包含非普通文件", reason = BackupFailureReason.SKILL_CONTENT_UNSUPPORTED)
                    return checkLimits()
                }

                private fun checkLimits(): FileVisitResult {
                    if (!git && total > SkillBackupPaths.MAX_BYTES) {
                        throw BackupValidationException("用户级技能文件超过整包容量，备份已取消", reason = BackupFailureReason.SKILL_CONTENT_UNSUPPORTED)
                    }
                    if (!git && directories.size + files.size > entryBudget) {
                        throw BackupValidationException("用户级技能条目超过备份包上限", reason = BackupFailureReason.CAPACITY_EXCEEDED)
                    }
                    if (git && (total > gitByteLimit || directories.size + files.size > gitEntryLimit)) {
                        complete = false
                        return FileVisitResult.TERMINATE
                    }
                    return FileVisitResult.CONTINUE
                }
            })
            return complete
        }

        visit(root, git = false)
        val baseFiles = files.toMap()
        val baseDirectories = directories.toMap()
        val baseTotal = total
        var includeGit = total <= gitByteLimit && directories.size + files.size <= gitEntryLimit
        if (includeGit) {
            for (git in gitRoots) {
                if (!visit(git, git = true)) {
                    includeGit = false
                    break
                }
            }
        }
        var metadataBytes = 0L
        val skippedRepositories = if (includeGit) emptyList() else {
            files.clear()
            files.putAll(baseFiles)
            directories.clear()
            directories.putAll(baseDirectories)
            total = baseTotal
            gitRoots.map { git ->
                val repository = relative(git.parent).ifEmpty { "." }
                val record = SkillGitMetadata.parseLines(repository) { name, consume ->
                    readStableLines(git.resolve(name), consume)
                }
                metadataBytes += record.relativePath.toByteArray().size + (record.branch?.toByteArray()?.size ?: 0) +
                    record.remotes.sumOf { it.name.toByteArray().size + it.url.toByteArray().size + 64L } + 128L
                if (metadataBytes > BackupPackageLimits.MAX_MANIFEST_BYTES) throw BackupValidationException("Git 仓库信息超过备份清单限制")
                record
            }
        }
        if (total > memoryBudget || files.size + directories.size > entryBudget) {
            throw BackupValidationException("用户级技能超过备份包条目或 Android 内存安全限制", reason = BackupFailureReason.CAPACITY_EXCEEDED)
        }
        val result = linkedMapOf<String, ByteArray>()
        val diskFiles = linkedMapOf<String, BackupBlob>()
        try {
            var actualTotal = 0L
            files.toSortedMap().forEach { (relative, source) ->
                if (directory != null && !SkillGitMetadata.isConfigPath(relative)) {
                    val target = Files.createTempFile(directory, "skill-", ".bin")
                    diskFiles[relative] = BackupStreams.stableCopy(source.path, target, source.attributes.size())
                    actualTotal += source.attributes.size()
                    if (actualTotal > memoryBudget) throw BackupValidationException("技能内容超过整包容量", reason = BackupFailureReason.CAPACITY_EXCEEDED)
                    return@forEach
                }
                val bytes = readStable(source.path, source.attributes, minOf(memoryBudget, if (SkillGitMetadata.isConfigPath(relative)) BackupPackageLimits.MAX_MANIFEST_BYTES else BackupPackageLimits.MAX_ENTRY_BYTES))
                val isConfig = SkillGitMetadata.isConfigPath(relative)
                val safeBytes = if (isConfig) {
                    try { SkillGitMetadata.sanitizeConfig(bytes.toString(Charsets.UTF_8)).toByteArray() }
                    finally { bytes.fill(0) }
                } else bytes
                if (directory == null) result[relative] = safeBytes else {
                    try {
                        val target = Files.createTempFile(directory, "git-config-", ".bin")
                        Files.write(target, safeBytes)
                        diskFiles[relative] = BackupBlob.File(target)
                    } finally { safeBytes.fill(0) }
                }
                actualTotal += safeBytes.size
                if (actualTotal > memoryBudget) throw BackupValidationException("用户级技能超过 Android 内存安全限制", reason = BackupFailureReason.CAPACITY_EXCEEDED)
            }
            directories.forEach { (relative, before) ->
                FileRestoreJournal.rejectSymlinkAncestors(root.resolve(relative))
                val after = attributes(root.resolve(relative))
                if (!after.isDirectory || before.fileKey() != after.fileKey() ||
                    before.lastModifiedTime() != after.lastModifiedTime()) {
                    throw BackupValidationException("技能目录在备份期间发生变化")
                }
            }
            val rootAfter = attributes(root)
            if (rootAfter.fileKey() != rootBefore.fileKey() || rootAfter.lastModifiedTime() != rootBefore.lastModifiedTime()) {
                throw BackupValidationException("技能根目录在备份期间发生变化")
            }
            val metadata = SkillBackupMetadata(
                directories = directories.keys.sorted(),
                summary = SkillBackupSummary(
                    skillCount = (result.keys + diskFiles.keys).count { path ->
                        val parts = path.split('/')
                        parts.size == 2 && parts.last() == "SKILL.md" && parts.first() != ".git"
                    },
                    skippedSymbolicLinks = skippedLinks,
                    skippedGitRepositories = skippedRepositories,
                ),
            )
            SkillBackupPaths.validate(metadata, result.keys + diskFiles.keys, actualTotal)
            return SkillBackupPartition(result, metadata, diskFiles)
        } catch (error: Throwable) {
            result.values.forEach { it.fill(0) }
            throw error
        }
    }

    private fun attributes(path: Path): BasicFileAttributes =
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)

    private fun readStableLines(path: Path, consume: (Sequence<String>) -> Unit) {
        if (!Files.exists(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            consume(emptySequence())
            return
        }
        FileRestoreJournal.rejectSymlinkAncestors(path)
        val before = attributes(path)
        if (!before.isRegularFile) throw BackupValidationException("Git 元数据不是普通文件")
        FileChannel.open(path, StandardOpenOption.READ, NOFOLLOW_LINKS).use { channel ->
            Channels.newReader(boundedSkillMetadataChannel(channel, before.size()), Charsets.UTF_8.newDecoder(), 8192).use { reader ->
                val buffer = CharArray(8192)
                try {
                    consume(sequence {
                        val line = StringBuilder()
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            for (index in 0 until count) {
                                val character = buffer[index]
                                if (character == '\n') {
                                    yield(line.toString().removeSuffix("\r"))
                                    line.setLength(0)
                                } else {
                                    if (line.length >= BackupPackageLimits.MAX_MANIFEST_BYTES) {
                                        throw BackupValidationException("Git 元数据单行超过备份清单限制")
                                    }
                                    line.append(character)
                                }
                            }
                        }
                        if (line.isNotEmpty()) yield(line.toString().removeSuffix("\r"))
                    })
                } finally { buffer.fill('\u0000') }
            }
        }
        FileRestoreJournal.rejectSymlinkAncestors(path)
        val after = attributes(path)
        if (!after.isRegularFile || before.fileKey() != after.fileKey() || before.size() != after.size() ||
            before.lastModifiedTime() != after.lastModifiedTime()) {
            throw BackupValidationException("Git 元数据在备份期间发生变化")
        }
    }

    private fun readStable(path: Path, before: BasicFileAttributes, limit: Long): ByteArray {
        FileRestoreJournal.rejectSymlinkAncestors(path)
        if (!before.isRegularFile || before.size() > limit || before.size() > Int.MAX_VALUE) {
            throw BackupValidationException("技能元数据或文件超过允许大小")
        }
        val result = ByteArray(before.size().toInt())
        try {
            FileChannel.open(path, StandardOpenOption.READ, NOFOLLOW_LINKS).use { channel ->
                var offset = 0
                while (offset < result.size) {
                    val count = channel.read(ByteBuffer.wrap(result, offset, result.size - offset))
                    if (count < 0) break
                    offset += count
                }
                if (offset != result.size || channel.read(ByteBuffer.allocate(1)) != -1) {
                    throw BackupValidationException("技能文件在备份期间大小发生变化")
                }
            }
            FileRestoreJournal.rejectSymlinkAncestors(path)
            val after = attributes(path)
            if (!after.isRegularFile || before.fileKey() != after.fileKey() || before.size() != after.size() ||
                before.lastModifiedTime() != after.lastModifiedTime()) {
                throw BackupValidationException("技能文件在备份期间发生变化")
            }
            return result
        } catch (error: Throwable) {
            result.fill(0)
            throw error
        }
    }
}

internal fun boundedSkillMetadataChannel(source: ReadableByteChannel, size: Long): ReadableByteChannel {
    require(size >= 0)
    return object : ReadableByteChannel {
        private var remaining = size

        override fun read(destination: ByteBuffer): Int {
            if (!destination.hasRemaining()) return 0
            if (remaining == 0L) return -1
            val originalLimit = destination.limit()
            val allowed = minOf(remaining, destination.remaining().toLong()).toInt()
            destination.limit(destination.position() + allowed)
            try {
                val count = source.read(destination)
                if (count < 0) throw BackupValidationException("Git 元数据在读取期间被截断")
                remaining -= count
                return count
            } finally {
                destination.limit(originalLimit)
            }
        }

        override fun isOpen(): Boolean = source.isOpen
        override fun close() = source.close()
    }
}
