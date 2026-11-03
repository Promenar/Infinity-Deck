package com.promenar.infinity.data.backup

import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** 每块由 Keystore 独立产生随机 IV，认证明文同时绑定事务、块序号与末块标记。 */
internal class StreamingPendingRestoreStore(base: AtomicFile, private val cryptor: PendingRestoreCryptor) {
    private val parent = FileRestoreJournal.requireTrustedDirectory(base.baseFile.parentFile!!.toPath())
    private val metadataFile = AtomicFile(java.io.File(base.baseFile.path + ".stream"))
    private data class Record(val metadata: PendingRestoreMetadata, val size: Long, val password: CharArray?) : AutoCloseable {
        override fun close() { password?.fill('\u0000'); metadata.packageSha256.fill(0) }
    }
    fun cleanupOrphans() {
        cleanupOpenTrees()
        if (!exists()) Files.newDirectoryStream(parent).use { entries -> entries.filter { it.fileName.toString().startsWith(".pending-package-") }.forEach(BackupStreams::deleteTree) }
    }
    private fun cleanupOpenTrees() {
        FileRestoreJournal.requireTrustedDirectory(parent)
        Files.newDirectoryStream(parent).use { entries -> entries.filter { it.fileName.toString().startsWith(".pending-open-") }.forEach(BackupStreams::deleteTree) }
    }
    fun exists() = metadataFile.baseFile.exists()
    fun begin(tx: String) {
        requireTx(tx)
        if (exists()) {
            readRecord().use { if (it.metadata.txId == tx && it.metadata.phase == PendingRestorePhase.STAGING && it.size == 0L) return }
            throw BackupValidationException("已有待恢复事务")
        }
        writeRecord(Record(PendingRestoreMetadata(tx, ByteArray(32), PendingRestorePhase.STAGING), 0, null))
    }
    fun stage(tx: String, source: Path, password: CharArray?): PendingRestoreMetadata {
        readRecord().use { if (it.metadata.txId != tx || it.metadata.phase != PendingRestorePhase.STAGING || it.size != 0L) throw BackupValidationException("待恢复事务已失效") }
        val size = Files.size(source)
        if (size !in 1..BackupStreams.MAX_WIRE_BYTES) throw BackupValidationException("恢复包大小无效", reason = BackupFailureReason.CAPACITY_EXCEEDED)
        BackupStreams.requireSpace(parent, size + 1024 * 1024)
        val target = AtomicFile(payload(tx).toFile())
        val output = target.startWrite()
        try {
            Files.newInputStream(source).use { encrypt(tx, it, output) }
            output.fd.sync(); target.finishWrite(output)
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(source).use { BackupStreams.copy(it, BackupStreams.DISCARD, size, digest) }
            val record = Record(PendingRestoreMetadata(tx, digest.digest(), PendingRestorePhase.STAGING), size, password?.copyOf())
            try { return writeRecord(record) } finally { record.close() }
        } catch (error: Throwable) { target.failWrite(output); target.delete(); throw error }
    }
    fun authorize(tx: String): PendingRestoreMetadata = readRecord().use { record ->
        if (record.metadata.txId != tx || record.metadata.phase != PendingRestorePhase.STAGING || record.size == 0L) throw BackupValidationException("待恢复事务不可授权")
        verify(record, BackupStreams.DISCARD)
        writeRecord(record.copy(metadata = record.metadata.copy(phase = PendingRestorePhase.STAGED)))
    }
    fun read(): PendingRestorePayload {
        cleanupOpenTrees()
        val record = readRecord()
        try {
            if (record.size == 0L) return PendingRestorePayload(record.metadata.copy(packageSha256 = record.metadata.packageSha256.copyOf()), ByteArray(0), null)
            BackupStreams.requireSpace(parent, record.size * 3 + 8L * 1024 * 1024)
            val root = Files.createTempDirectory(parent, ".pending-open-${record.metadata.txId}-")
            try {
                val path = root.resolve("package.iwb")
                Files.newOutputStream(path).use { verify(record, it) }
                return PendingRestorePayload(record.metadata.copy(packageSha256 = record.metadata.packageSha256.copyOf()), ByteArray(0), record.password?.copyOf(), path, root)
            } catch (error: Throwable) { BackupStreams.deleteTree(root); throw error }
        } finally { record.close() }
    }
    fun clear(tx: String) {
        if (!exists()) return
        readRecord().use { if (it.metadata.txId != tx) throw BackupValidationException("待恢复事务身份不一致") }
        metadataFile.delete()
        AtomicFile(payload(tx).toFile()).delete()
    }
    private fun verify(record: Record, output: OutputStream) {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val sink = object : OutputStream() {
            override fun write(value: Int) { throw UnsupportedOperationException() }
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                if (count > record.size - size) throw BackupValidationException("待恢复内容超过声明大小")
                output.write(bytes, offset, count); digest.update(bytes, offset, count); size += count
            }
        }
        AtomicFile(payload(record.metadata.txId).toFile()).openRead().use { decrypt(record.metadata.txId, it, sink) }
        if (size != record.size || !MessageDigest.isEqual(digest.digest(), record.metadata.packageSha256)) throw BackupValidationException("待恢复包摘要不一致")
    }
    private fun encrypt(tx: String, input: InputStream, output: OutputStream) {
        val buffer = ByteArray(BackupCrypto.CHUNK_BYTES)
        val target = DataOutputStream(output)
        var index = 0L
        try {
            while (true) {
                var used = 0
                while (used < buffer.size) {
                    val read = input.read(buffer, used, buffer.size - used)
                    if (read < 0) break
                    used += read
                }
                val last = used < buffer.size
                val plain = WipeableByteArrayOutputStream()
                try {
                    val frame = DataOutputStream(plain)
                    frame.writeUTF(tx); frame.writeLong(index); frame.writeBoolean(last); frame.writeInt(used); frame.write(buffer, 0, used)
                    val bytes = plain.toByteArray()
                    try {
                        val encrypted = cryptor.encrypt(bytes)
                        try { target.writeInt(encrypted.size); target.write(encrypted) } finally { encrypted.fill(0) }
                    } finally { bytes.fill(0) }
                } finally { plain.wipe(); buffer.fill(0) }
                index++
                if (last) break
            }
        } finally { buffer.fill(0) }
    }
    private fun decrypt(tx: String, input: InputStream, output: OutputStream) {
        val source = DataInputStream(input)
        var index = 0L
        var total = 0L
        while (true) {
            val length = source.readInt()
            if (length !in 1..BackupCrypto.CHUNK_BYTES + 8192) throw BackupValidationException("暂存分块长度无效")
            val encrypted = ByteArray(length)
            var plain = ByteArray(0)
            try {
                source.readFully(encrypted); plain = cryptor.decrypt(encrypted)
                val frame = DataInputStream(plain.inputStream())
                if (frame.readUTF() != tx || frame.readLong() != index) throw BackupValidationException("暂存分块身份无效")
                val last = frame.readBoolean()
                val size = frame.readInt()
                if (size !in 0..BackupCrypto.CHUNK_BYTES || size != frame.available() || !last && size != BackupCrypto.CHUNK_BYTES ||
                    last && size == BackupCrypto.CHUNK_BYTES || size > BackupStreams.MAX_WIRE_BYTES - total) throw BackupValidationException("暂存分块结构无效")
                output.write(plain, plain.size - size, size)
                total += size; index++
                if (last) {
                    if (source.read() != -1) throw BackupValidationException("暂存末块后存在内容")
                    return
                }
            } finally { encrypted.fill(0); plain.fill(0) }
        }
    }
    private fun writeRecord(record: Record): PendingRestoreMetadata {
        val bytes = WipeableByteArrayOutputStream()
        try {
            val out = DataOutputStream(bytes)
            out.writeInt(2); out.writeUTF(record.metadata.txId); out.writeInt(record.metadata.phase.wireCode)
            out.writeLong(record.size); out.write(record.metadata.packageSha256)
            val password = record.password
            if ((password?.size ?: 0) > 4096) throw BackupValidationException("恢复密码过长")
            out.writeInt(password?.size ?: 0); password?.forEach { out.writeChar(it.code) }
            val plain = bytes.toByteArray()
            try {
                val encrypted = cryptor.encrypt(plain)
                try {
                    val target = metadataFile.startWrite()
                    try { target.write(encrypted); target.fd.sync(); metadataFile.finishWrite(target) }
                    catch (error: Throwable) { metadataFile.failWrite(target); throw error }
                } finally { encrypted.fill(0) }
            } finally { plain.fill(0) }
            return record.metadata.copy(packageSha256 = record.metadata.packageSha256.copyOf())
        } finally { bytes.wipe() }
    }
    private fun readRecord(): Record {
        val encrypted = metadataFile.openRead().use {
            val out = WipeableByteArrayOutputStream()
            try { BackupStreams.copy(it, out, 16 * 1024); out.toByteArray() } finally { out.wipe() }
        }
        var plain = ByteArray(0)
        try {
            plain = cryptor.decrypt(encrypted)
            val input = DataInputStream(plain.inputStream())
            if (input.readInt() != 2) throw BackupValidationException("暂存元数据版本无效")
            val tx = input.readUTF().also(::requireTx)
            val code = input.readInt()
            val phase = PendingRestorePhase.entries.singleOrNull { it.wireCode == code } ?: throw BackupValidationException("暂存阶段无效")
            val size = input.readLong()
            val digest = ByteArray(32).also(input::readFully)
            val length = input.readInt()
            if (size !in 0..BackupStreams.MAX_WIRE_BYTES || length !in 0..4096 || input.available() != length * 2 || phase == PendingRestorePhase.STAGED && size == 0L) throw BackupValidationException("暂存元数据长度无效")
            val password = if (length == 0) null else CharArray(length) { input.readChar() }
            return Record(PendingRestoreMetadata(tx, digest, phase), size, password)
        } finally { encrypted.fill(0); plain.fill(0) }
    }
    private fun payload(tx: String): Path = parent.resolve(".pending-package-$tx.bin")
    private fun requireTx(tx: String) { if (!Regex("[A-Za-z0-9-]{1,64}").matches(tx)) throw BackupValidationException("待恢复事务ID无效") }
}
