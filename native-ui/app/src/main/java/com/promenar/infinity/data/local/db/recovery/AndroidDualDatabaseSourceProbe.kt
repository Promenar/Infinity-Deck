package com.promenar.infinity.data.local.db.recovery

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.core.content.ContextCompat
import com.promenar.infinity.data.local.db.hasCompleteCurrentRoomSchema
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.security.MessageDigest
import java.util.UUID

/** 隔离副本完成WAL恢复后允许出现的精确身份；包含所有可迁移来源和可信当前版本。 */
internal val CURRENT_RECOVERY_SOURCE_IDENTITIES = setOf(
    2 to PUBLIC_V2_IDENTITY, 5 to INTERNAL_V5_IDENTITY,
    18 to CURRENT_V18_IDENTITY, 19 to CURRENT_V19_IDENTITY, 20 to CURRENT_V20_IDENTITY,
    21 to CURRENT_V21_IDENTITY, 22 to CURRENT_V22_IDENTITY, 23 to CURRENT_V23_IDENTITY,
    24 to CURRENT_V24_IDENTITY, 25 to CURRENT_V25_IDENTITY, 26 to CURRENT_V26_IDENTITY, 27 to CURRENT_V27_IDENTITY,
    28 to CURRENT_V28_IDENTITY, 29 to CURRENT_V29_IDENTITY, 30 to CURRENT_V30_IDENTITY, 31 to CURRENT_V31_IDENTITY, 32 to CURRENT_V32_IDENTITY, 33 to CURRENT_V33_IDENTITY,
)

/** 测试hook只用于注入真实文件系统变化，不替换生产descriptor实现。 */
internal enum class SourceProbePhase { SOURCE_SCANNED, BEFORE_COPY, COPY_CHUNK, COPIED, BEFORE_SQLITE, BEFORE_CLEANUP }

internal class AndroidDualDatabaseSourceProbe(
    context: Context,
    private val hook: ((SourceProbePhase, Path) -> Unit)? = null,
    private val maxDatabaseBytes: Long = 16L * 1024 * 1024 * 1024,
    private val minimumFreeBytes: Long = 16L * 1024 * 1024,
) : DualDatabaseRecoverySourceProbe {
    private val appContext = context.applicationContext
    private val databaseDirectory = requireNotNull(appContext.getDatabasePath(CURRENT_DATABASE_NAME).parentFile).toPath()
    private val noBackupDirectory = requireNotNull(ContextCompat.getNoBackupFilesDir(appContext)).toPath()
    private val fileSystem by lazy { AndroidSnapshotFileSystem(appContext.dataDir.toPath()) }

    override fun startupBlocker(): String? = when {
        Files.exists(noBackupDirectory.resolve(".pending-restore-v1.bin"), LinkOption.NOFOLLOW_LINKS) ->
            "存在尚未完成的备份包恢复，请先完成或取消该恢复"
        Files.exists(noBackupDirectory.resolve("backup-restore-runtime-v1/.restore-journal.json"), LinkOption.NOFOLLOW_LINKS) ->
            "存在尚未完成的旧备份恢复 journal，双库恢复已安全阻断"
        else -> null
    }

    override fun inspectDualSources(): DualDatabaseSourcePair? {
        if (!Files.exists(databaseDirectory.resolve(LEGACY_DATABASE_NAME), LinkOption.NOFOLLOW_LINKS) ||
            !Files.exists(databaseDirectory.resolve(CURRENT_DATABASE_NAME), LinkOption.NOFOLLOW_LINKS)) return null
        return fileSystem.openDirectory(databaseDirectory).use { source ->
            // 仅决定是否交回既有Promoter完整验真，不以SQLite头证明schema/Room identity可信。
            if (routesToCurrentSchemaValidation(source)) return@use null
            val before = fingerprintDatabaseFiles(source)
            hook?.invoke(SourceProbePhase.SOURCE_SCANNED, source.path)
            val validationBase = noBackupDirectory.resolve("dual-db-probes-v1")
            fileSystem.openDirectory(validationBase, createParents = true).use { parent ->
                check(parent.names(3).size < 2) { "已保留两次未完成探测，请先检查现场；停止继续占用空间" }
                val requiredBytes = Math.addExact(Math.multiplyExact(before.sumOf { it.fingerprint.size }, 2L), minimumFreeBytes)
                ProbeDirectory.open(parent).use {
                    check(it.availableBytes() >= requiredBytes) { "剩余空间不足以隔离探测和恢复WAL，未创建新副本" }
                }
                val name = "probe-${UUID.randomUUID()}"
                parent.createDirectory(name).use { created ->
                    ProbeDirectory.open(created).use { validation ->
                        try {
                            before.forEach { file -> copyFrozen(source, validation, file) }
                            val fingerprints = before.map { it.fingerprint }
                            check(fingerprintDatabaseFiles(source) == before) { "数据库源在复制期间发生变化" }
                            requireBinding(source)
                            validation.requireCopiedInventory(fingerprints)
                            hook?.invoke(SourceProbePhase.BEFORE_SQLITE, created.path)
                            requireValidationBinding(parent, name, created.key)
                            validation.requireCopiedInventory(fingerprints)
                            val legacy = inspectCopied(validation, "legacy", LEGACY_DATABASE_NAME,
                                setOf(17 to PUBLIC_V17_IDENTITY), fingerprints)
                            val current = inspectCopied(validation, "current", CURRENT_DATABASE_NAME,
                                CURRENT_RECOVERY_SOURCE_IDENTITIES, fingerprints)
                            check(fingerprintDatabaseFiles(source) == before) { "数据库源在探测期间发生变化" }
                            requireBinding(source)
                            hook?.invoke(SourceProbePhase.BEFORE_CLEANUP, created.path)
                            requireValidationBinding(parent, name, created.key)
                            validation.cleanup(parent, name)
                            // 精确验证的当前库由 Promoter 接管，保留的 v17 不触发合并。
                            if (requiresDualDatabaseRecovery(current)) DualDatabaseSourcePair(legacy, current) else null
                        } catch (failure: Exception) {
                            // 不按临时路径猜测归属，不递归清理失败或替换后的内容。
                            throw IllegalStateException("双库探测未完成；隔离目录已安全保留: ${created.path}", failure)
                        }
                    }
                }
            }
        }
    }

    private fun routesToCurrentSchemaValidation(source: SnapshotDirectory): Boolean {
        val before = source.node(CURRENT_DATABASE_NAME)?.takeUnless { it.directory } ?: return false
        if (before.size < 100) return false
        val header = ByteBuffer.allocate(100)
        source.read(CURRENT_DATABASE_NAME).use { channel ->
            while (header.hasRemaining()) check(channel.read(header) > 0) { "当前数据库头读取提前结束" }
        }
        check(source.node(CURRENT_DATABASE_NAME) == before) { "当前数据库头读取期间身份变化" }
        requireBinding(source)
        val magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        return header.array().copyOfRange(0, magic.size).contentEquals(magic) && header.getInt(60) in setOf(18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33)
    }

    private fun requireBinding(source: SnapshotDirectory) {
        fileSystem.openDirectory(databaseDirectory).use { check(it.key == source.key) { "数据库源目录绑定变化" } }
    }

    private fun requireValidationBinding(parent: SnapshotDirectory, name: String, key: String) {
        fileSystem.openDirectory(parent.path).use { check(it.key == parent.key) { "探测隔离父目录绑定变化" } }
        check(parent.node(name)?.key == key) { "探测隔离目录已替换" }
    }

    private fun fingerprintDatabaseFiles(source: SnapshotDirectory): List<FrozenProbeFile> {
        val names = listOf(LEGACY_DATABASE_NAME, CURRENT_DATABASE_NAME).flatMap { main ->
            listOf(main, "$main-wal", "$main-journal")
        }.sorted()
        var total = 0L
        return names.mapNotNull { name ->
            val node = source.node(name)
            if (node == null) {
                check(name != LEGACY_DATABASE_NAME && name != CURRENT_DATABASE_NAME) { "数据库主文件缺失" }
                return@mapNotNull null
            }
            check(!node.directory && node.size >= 0 && node.size <= maxDatabaseBytes - total) { "数据库源超出探测字节预算" }
            total += node.size
            val hash = source.read(name).use { digestExactly(it, node.size) }
            check(source.node(name) == node) { "数据库源文件在读取期间变化" }
            FrozenProbeFile(RecoveryFileFingerprint(name, node.size, hash), node)
        }
    }

    private fun copyFrozen(source: SnapshotDirectory, target: ProbeDirectory, frozen: FrozenProbeFile) {
        val file = frozen.fingerprint
        hook?.invoke(SourceProbePhase.BEFORE_COPY, source.path.resolve(file.relativePath))
        val before = source.node(file.relativePath)
        check(before == frozen.node) { "数据库复制源身份或大小变化" }
        val copiedHash = MessageDigest.getInstance("SHA-256")
        source.read(file.relativePath).use { input ->
            target.createFile(file.relativePath).use { output ->
                val buffer = ByteBuffer.allocate(64 * 1024)
                var remaining = file.size
                while (remaining > 0) {
                    buffer.clear(); buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
                    val count = input.read(buffer)
                    check(count > 0) { "数据库复制源提前结束或无进展" }
                    remaining -= count
                    buffer.flip(); copiedHash.update(buffer.asReadOnlyBuffer())
                    while (buffer.hasRemaining()) check(output.write(buffer) > 0) { "数据库复制写入无进展" }
                    hook?.invoke(SourceProbePhase.COPY_CHUNK, source.path.resolve(file.relativePath))
                }
                check(input.read(ByteBuffer.allocate(1)) < 0) { "数据库复制源增长超过预算" }
                output.force(true)
            }
        }
        hook?.invoke(SourceProbePhase.COPIED, source.path.resolve(file.relativePath))
        check(copiedHash.hex() == file.sha256 && source.node(file.relativePath) == before) { "数据库复制字节与冻结源不符" }
        target.verifyFile(file)
    }

    private fun inspectCopied(
        directory: ProbeDirectory, role: String, name: String, accepted: Set<Pair<Int, String>>,
        fingerprints: List<RecoveryFileFingerprint>,
    ): RecoveryDatabaseSource {
        directory.prepareSqliteSidecars(name)
        val contract = SQLiteDatabase.openDatabase(directory.sqlitePath(name), null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
            sqlite.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use {
                check(it.moveToFirst() && it.getInt(0) == 0) { "数据库WAL无法checkpoint" }
            }
            sqlite.rawQuery("PRAGMA quick_check(1)", null).use {
                check(it.moveToFirst() && it.getString(0) == "ok") { "数据库完整性校验失败" }
            }
            val contract = sqlite.version to sqlite.roomIdentity()
            check(contract in accepted) { "${name}版本或Room identity不属于已验证公开合同" }
            validatePublishedShape(sqlite, role, contract.first)
            if (role == "current" && contract.first !in setOf(18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33) && sqlite.hasTable("workspace_mutations")) {
                sqlite.rawQuery("SELECT 1 FROM workspace_mutations LIMIT 1", null).use {
                    check(!it.moveToFirst()) { "当前数据库存在尚未完成的工作区文件 journal" }
                }
            }
            contract
        }
        val files = fingerprints.filter { it.relativePath == name || it.relativePath == "$name-wal" || it.relativePath == "$name-journal" }
        return RecoveryDatabaseSource(role, name, contract.first, contract.second, files, recoveryManifestSha256(files))
    }

    private fun validatePublishedShape(sqlite: SQLiteDatabase, role: String, version: Int) {
            fun has(table: String, column: String): Boolean = sqlite.rawQuery(
                "PRAGMA table_info(`${table.replace("`", "``")}`)", null,
            ).use { cursor ->
                val index = cursor.getColumnIndex("name")
                var found = false
                while (cursor.moveToNext()) if (index >= 0 && cursor.getString(index) == column) found = true
                found
            }
            val valid = when {
                role == "legacy" && version == 17 ->
                    has("messages", "attachments") && !has("messages", "legacy_attachments") &&
                        !has("agents", "name_customized") && !has("workspace_files", "workspace_root_uuid")
                role == "current" && version in setOf(2, 5) ->
                    !has("messages", "attachments") && !has("messages", "legacy_attachments") &&
                        has("agents", "name_customized") && has("workspace_files", "workspace_root_uuid") &&
                        has("vectorization_tasks", "workspace_root_uuid")
                role == "current" && version in setOf(18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33) ->
                    sqlite.hasCompleteCurrentRoomSchema(version, sqlite.roomIdentity())
                else -> false
            }
            if (!valid) throw IOException("${role}数据库结构与已验证发布合同不一致")
    }

    private fun SQLiteDatabase.roomIdentity(): String = rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use {
        check(it.moveToFirst()) { "数据库缺少Room identity" }; it.getString(0)
    }
    private fun SQLiteDatabase.hasTable(name: String): Boolean =
        rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use { it.moveToFirst() }
}

private data class FrozenProbeFile(val fingerprint: RecoveryFileFingerprint, val node: SnapshotNode)

/** 持有私有目录与每个新文件的FD；清理只接受这些存活FD对应的inode。 */
private class ProbeDirectory private constructor(
    private val fd: ParcelFileDescriptor,
    private val stream: SecureDirectoryStream<Path>,
    private val key: String,
) : Closeable {
    private val pins = linkedMapOf<String, ParcelFileDescriptor>()
    private val anchor get() = Paths.get("/proc/self/fd/${fd.fd}")

    fun availableBytes(): Long {
        val status = Os.fstatvfs(fd.fileDescriptor)
        check(status.f_bavail >= 0 && status.f_frsize > 0) { "探测文件系统空间信息无效" }
        return if (status.f_bavail > Long.MAX_VALUE / status.f_frsize) Long.MAX_VALUE
            else status.f_bavail * status.f_frsize
    }

    fun createFile(name: String): java.nio.channels.FileChannel {
        safeSnapshotName(name)
        val raw = Os.open(anchor.resolve(name).toString(), OsConstants.O_CREAT or OsConstants.O_EXCL or
            OsConstants.O_RDWR or OsConstants.O_NOFOLLOW, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        val pin = try { ParcelFileDescriptor.dup(raw) } finally { Os.close(raw) }
        pins[name] = pin
        check(nodeKey(name) == pinKey(pin)) { "探测新文件身份变化" }
        return ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(pin.fileDescriptor)).channel
    }

    fun sqlitePath(name: String): String {
        safeSnapshotName(name)
        check(nodeKey(name) == pinKey(requireNotNull(pins[name]))) { "SQLite探测副本身份变化" }
        return anchor.resolve(name).toString()
    }

    fun prepareSqliteSidecars(main: String) {
        // WAL恢复会生成SHM，SQLite也可能留下空WAL或journal。由本操作先排他创建并持有FD，
        // 不在SQLite关闭后仅凭文件名认领它生成或被外部替换的节点。
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            val name = main + suffix
            if (name in pins) {
                check(nodeKey(name) == pinKey(pins.getValue(name))) { "SQLite sidecar副本身份变化" }
            } else {
                check(nodeKey(name) == null) { "SQLite sidecar位置已有未知内容" }
                createFile(name).use { it.force(true) }
            }
        }
        Os.fsync(fd.fileDescriptor)
    }

    fun verifyFile(file: RecoveryFileFingerprint) {
        check(nodeKey(file.relativePath) == pinKey(requireNotNull(pins[file.relativePath]))) { "探测副本被替换" }
        stream.newByteChannel(Paths.get(file.relativePath), setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use {
            check(digestExactly(it, file.size) == file.sha256) { "探测副本实际字节不匹配" }
        }
    }

    fun requireCopiedInventory(files: List<RecoveryFileFingerprint>) {
        check(names().toSet() == files.map { it.relativePath }.toSet()) { "探测副本含未知文件" }
        files.forEach(::verifyFile)
    }

    fun cleanup(parent: SnapshotDirectory, name: String) {
        val names = names()
        check(names.all { it in pins && nodeKey(it) == pinKey(pins.getValue(it)) }) {
            "探测目录含未知或被替换内容，保留现场；未认领=${names.count { it !in pins }}，身份变化=${names.count { it in pins && nodeKey(it) != pinKey(pins.getValue(it)) }}"
        }
        // rename后才验证并unlink，不能把被替换的源名字直接当成本操作文件删除。
        names.forEach { original ->
            val quarantine = Paths.get(".cleanup-${UUID.randomUUID()}")
            stream.move(Paths.get(original), stream, quarantine)
            check(nodeKey(quarantine.toString()) == pinKey(pins.getValue(original))) { "探测清理rename期间节点变化，保留现场" }
            stream.deleteFile(quarantine)
        }
        check(names().isEmpty()) { "探测清理期间出现未知内容" }
        Os.fsync(fd.fileDescriptor)
        open(parent).use { anchoredParent ->
            check(anchoredParent.nodeKey(name) == key) { "探测目录在清理前替换" }
            val quarantine = Paths.get(".empty-${UUID.randomUUID()}")
            anchoredParent.stream.move(Paths.get(name), anchoredParent.stream, quarantine)
            check(anchoredParent.nodeKey(quarantine.toString()) == key) { "探测目录rename期间替换，保留现场" }
            anchoredParent.stream.deleteDirectory(quarantine)
            Os.fsync(anchoredParent.fd.fileDescriptor)
        }
    }

    private fun names(): List<String> = stream.newDirectoryStream(Paths.get("."), LinkOption.NOFOLLOW_LINKS).use { entries ->
        buildList {
            entries.forEach { entry ->
                check(size < 12) { "探测目录条目超过严格预算" }
                add(entry.fileName.toString())
            }
        }
    }
    private fun nodeKey(name: String): String? = try {
        val attrs = stream.getFileAttributeView(Paths.get(name), BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS).readAttributes()
        check(!attrs.isSymbolicLink) { "探测目录包含符号链接" }
        attrs.fileKey()?.toString()
    } catch (_: java.nio.file.NoSuchFileException) { null }

    override fun close() {
        try { pins.values.forEach { it.close() } } finally { try { stream.close() } finally { fd.close() } }
    }
    companion object {
        fun open(directory: SnapshotDirectory): ProbeDirectory {
            val raw = Os.open(directory.path.toString(), OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
            val fd = try { ParcelFileDescriptor.dup(raw) } finally { Os.close(raw) }
            try {
                check(OsConstants.S_ISDIR(Os.fstat(fd.fileDescriptor).st_mode) && pinKey(fd) == directory.key) {
                    "探测目录能力绑定变化"
                }
                val opened = Files.newDirectoryStream(Paths.get("/proc/self/fd/${fd.fd}"))
                @Suppress("UNCHECKED_CAST")
                val stream = opened as? SecureDirectoryStream<Path> ?: run {
                    opened.close(); throw SecurityException("探测文件系统缺少安全目录能力")
                }
                return ProbeDirectory(fd, stream, directory.key)
            } catch (failure: Throwable) { fd.close(); throw failure }
        }
        private fun pinKey(fd: ParcelFileDescriptor): String = Files.readAttributes(
            Paths.get("/proc/self/fd/${fd.fd}"), java.nio.file.attribute.BasicFileAttributes::class.java,
        ).fileKey()?.toString() ?: throw SecurityException("探测FD缺少稳定身份")
    }
}

private fun digestExactly(channel: SeekableByteChannel, size: Long): String {
    check(channel.size() == size) { "探测文件大小不匹配" }
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteBuffer.allocate(64 * 1024)
    var remaining = size
    while (remaining > 0) {
        buffer.clear(); buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
        val count = channel.read(buffer)
        check(count > 0) { "探测文件读取提前结束或无进展" }
        remaining -= count
        buffer.flip(); digest.update(buffer)
    }
    check(channel.read(ByteBuffer.allocate(1)) < 0 && channel.size() == size) { "探测文件增长超过预算" }
    return digest.hex()
}
private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun requiresDualDatabaseRecovery(current: RecoveryDatabaseSource): Boolean =
    !isTrustedCurrentRoomSchema(current.userVersion, current.roomIdentity)
