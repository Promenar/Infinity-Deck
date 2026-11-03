package com.promenar.infinity.data.backup

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest

sealed class BackupBlob {
    abstract val size: Long
    abstract fun open(): InputStream
    class File(val path: Path) : BackupBlob() {
        override val size get() = Files.size(path)
        override fun open(): InputStream {
            FileRestoreJournal.rejectSymlinkAncestors(path)
            if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw BackupValidationException("暂存内容不是普通文件")
            return Files.newInputStream(path, NOFOLLOW_LINKS)
        }
    }
    class Bytes(val bytes: ByteArray) : BackupBlob() {
        override val size get() = bytes.size.toLong()
        override fun open() = bytes.inputStream()
    }
}

data class BackupSourceEntry(val path: String, val layer: BackupLayer, val blob: BackupBlob)

internal object BackupStreams {
    const val BUFFER_BYTES = 64 * 1024
    // ZIP 中央目录、清单与分块认证的传输余量独立于解压内容预算。
    const val MAX_WIRE_BYTES = BackupPackageLimits.MAX_TOTAL_BYTES + 8L * 1024 * 1024

    fun copy(input: InputStream, output: OutputStream, limit: Long, digest: MessageDigest? = null): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (count > limit - total) throw BackupValidationException("备份内容超过容量上限", reason = BackupFailureReason.CAPACITY_EXCEEDED)
                output.write(buffer, 0, count)
                digest?.update(buffer, 0, count)
                total += count
            }
            return total
        } finally { buffer.fill(0) }
    }
    fun stableCopy(source: Path, target: Path, expectedSize: Long, hook: (Path) -> Unit = {}): BackupBlob.File {
        if (expectedSize < 0 || expectedSize > BackupPackageLimits.MAX_ENTRY_BYTES) throw BackupValidationException("文件超过128MiB单项上限", reason = BackupFailureReason.CAPACITY_EXCEEDED)
        FileRestoreJournal.rejectSymlinkAncestors(source)
        val before = Files.readAttributes(source, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!before.isRegularFile || before.isSymbolicLink || before.size() != expectedSize) {
            throw BackupValidationException("源文件属性与登记不一致", reason = BackupFailureReason.WORKSPACE_FILE_MISMATCH)
        }
        try {
            requireSpace(target.parent, expectedSize)
            val hasher = MessageDigest.getInstance("SHA-256")
            val size = Files.newInputStream(source, NOFOLLOW_LINKS).use { input -> Files.newOutputStream(target).use {
                copy(input, it, expectedSize, hasher)
            } }
            hook(source)
            FileRestoreJournal.rejectSymlinkAncestors(source)
            val after = Files.readAttributes(source, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (size != expectedSize || before.fileKey() != after.fileKey() || before.size() != after.size() ||
                before.lastModifiedTime() != after.lastModifiedTime() || hex(hasher.digest()) != digest(BackupBlob.File(source))) {
                throw BackupValidationException("源文件在读取期间发生变化", reason = BackupFailureReason.WORKSPACE_FILE_MISMATCH)
            }
            FileRestoreJournal.rejectSymlinkAncestors(source)
            val final = Files.readAttributes(source, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (after.fileKey() != final.fileKey() || after.size() != final.size() || after.lastModifiedTime() != final.lastModifiedTime()) throw BackupValidationException("文件复核期间发生变化", reason = BackupFailureReason.WORKSPACE_FILE_MISMATCH)
            return BackupBlob.File(target)
        } catch (error: Throwable) { Files.deleteIfExists(target); throw error }
    }
    fun digest(blob: BackupBlob): String {
        val digest = MessageDigest.getInstance("SHA-256")
        blob.open().use { copy(it, DISCARD, BackupPackageLimits.MAX_TOTAL_BYTES, digest) }
        return hex(digest.digest())
    }
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun requireSpace(path: Path, bytes: Long, available: () -> Long = { path.toFile().usableSpace }) {
        if (bytes < 0 || available() < bytes) throw BackupValidationException("可用存储空间不足", reason = BackupFailureReason.CAPACITY_EXCEEDED, detail = BackupFailureDetail.INSUFFICIENT_STORAGE)
    }
    fun cleanCache(path: Path) {
        FileRestoreJournal.rejectSymlinkAncestors(path)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        val root = FileRestoreJournal.requireTrustedDirectory(path)
        Files.newDirectoryStream(root).use { entries -> entries.forEach(::deleteTree) }
    }

    fun limited(input: InputStream, length: Long): InputStream = object : java.io.FilterInputStream(input) {
        private var remaining = length
        override fun read(): Int {
            if (remaining == 0L) return -1
            val value = super.read()
            if (value < 0) throw java.io.EOFException("数据库行读取被截断")
            remaining--; return value
        }
        override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
            if (count == 0) return 0
            if (remaining == 0L) return -1
            val size = super.read(bytes, offset, minOf(count.toLong(), remaining).toInt())
            if (size < 0) throw java.io.EOFException("数据库行读取被截断")
            remaining -= size; return size
        }
    }

    fun deleteTree(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        Files.walkFileTree(path, object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                Files.delete(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): java.nio.file.FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }
    val DISCARD = object : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
    }
}
