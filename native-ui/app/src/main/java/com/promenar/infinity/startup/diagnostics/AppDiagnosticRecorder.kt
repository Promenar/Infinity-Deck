package com.promenar.infinity.startup.diagnostics

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.coroutineContext

/** 私有诊断目录只管理固定命名的滚动记录。写入和快照共享同一把锁。 */
class AppDiagnosticRecorder(
    directory: File,
    maxTotalBytes: Long = 2 * 1024 * 1024L,
    maxFileBytes: Long = 256 * 1024L,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val directory: File = directory
    private val maxTotalBytes: Long = maxTotalBytes
    private val maxFileBytes: Long = maxFileBytes
    private val ioDispatcher: CoroutineDispatcher = ioDispatcher
    private val managedName = Regex("events-([0-9]{20})\\.jsonl")
    private val checkpoint: Path = File(directory, "sequence").toPath()
    private val pendingCheckpoint: Path = File(directory, "sequence.pending").toPath()
    private var reservation: Long? = null
    private var lastSequence = 0L
    private var appendableShard: File? = null

    init {
        require(maxFileBytes >= 512 && maxTotalBytes >= maxFileBytes) { "诊断容量配置无效" }
    }

    suspend fun recordError(step: String, error: Throwable) = record(DiagnosticRedactor.snapshot(step, error))

    internal suspend fun record(event: DiagnosticEvent, sessionReference: String? = null) =
        record(exceptionRecord(event, sessionReference), projected = true)

    internal suspend fun record(event: UnifiedDiagnosticRecord, projected: Boolean = false) = withContext(ioDispatcher) {
        mutex.withLock {
            ensureDirectory()
            withDirectoryLock {
                appendRecord(sanitizeRecord(event, projected))
            }
        }
    }

    private suspend fun appendRecord(safe: UnifiedDiagnosticRecord) {
        val files = managedFiles()
        val durableReservation = readReservation()
        if (reservation != durableReservation) {
            lastSequence = maxOf(durableReservation, scan(files).lastOrNull()?.seq ?: 0)
            val last = files.lastOrNull()
            appendableShard = last?.takeIf { it.length() in 1..maxFileBytes && endsWithNewline(it) }
            reservation = durableReservation
        }
        val sequence = maxOf(lastSequence, files.lastOrNull()?.let(::shardNumber) ?: 0)
        if (sequence == Long.MAX_VALUE) throw IOException("诊断记录编号已耗尽")
        var nextSeq = sequence + 1
        var bytes = encodeBounded(safe.copy(seq = nextSeq))
        val existing = files.lastOrNull()?.takeIf {
            it == appendableShard && it.length() + bytes.size <= maxFileBytes
        }
        val target = existing ?: run {
            var candidate = newShard(nextSeq)
            while (Files.exists(candidate.toPath(), NOFOLLOW_LINKS)) {
                if (nextSeq == Long.MAX_VALUE) throw IOException("诊断记录编号已耗尽")
                candidate = newShard(++nextSeq)
            }
            if (nextSeq != sequence + 1) bytes = encodeBounded(safe.copy(seq = nextSeq))
            candidate
        }
        if (nextSeq > durableReservation) {
            val boundary = nextSeq + minOf(SEQ_BLOCK - 1, Long.MAX_VALUE - nextSeq)
            reserveSequence(boundary, first = durableReservation == 0L)
            reservation = boundary
        }
        prune(files, incomingBytes = bytes.size.toLong(), incomingShard = if (existing == null) 1 else 0)
        appendableShard = null
        try {
            val options = mutableSetOf<OpenOption>(
                StandardOpenOption.WRITE, StandardOpenOption.APPEND, NOFOLLOW_LINKS,
            )
            if (existing == null) options.add(StandardOpenOption.CREATE_NEW)
            Files.newOutputStream(target.toPath(), *options.toTypedArray()).use { it.write(bytes) }
            appendableShard = target
        } finally {
            // 写入中断时保留已用序号，下次写入从新分片开始。
            lastSequence = nextSeq
        }
    }

    internal suspend fun readEvents(
        sinceSeq: Long = 0, sessionId: String? = null, limit: Int = 500, acceptReference: Boolean = false,
    ) = sessionEvents(sessionId, acceptReference).asSequence().filter { it.seq > sinceSeq }
        .take(limit.coerceAtLeast(0)).toList()

    internal suspend fun readRecentEvents(sessionId: String? = null, limit: Int = 200, acceptReference: Boolean = false) =
        sessionEvents(sessionId, acceptReference).takeLast(limit.coerceAtLeast(0))

    private suspend fun sessionEvents(sessionId: String?, acceptReference: Boolean): List<UnifiedDiagnosticRecord> {
        val snapshot = snapshot()
        if (sessionId == null) return snapshot
        val expected = if (acceptReference) DiagnosticRedactor.resolveReference(sessionId)
        else DiagnosticRedactor.correlationId(sessionId)
        val legacy = if (acceptReference && DiagnosticRedactor.isReference(sessionId)) null
        else DiagnosticRedactor.safeIdentifier(sessionId)
        return snapshot.filter { record ->
            val actual = (record.fields["sessionId"] as? JsonPrimitive)?.contentOrNull
            actual == expected || (legacy != null && record.event == "app.exception" && actual == legacy)
        }
    }

    internal suspend fun latestSeq(): Long = snapshot().lastOrNull()?.seq ?: 0

    suspend fun recent(): String = recentText(snapshot())

    suspend fun export(output: OutputStream, issueSummary: String) = withContext(ioDispatcher) {
        writeDiagnosticExport(output, issueSummary, recent())
    }

    internal suspend fun snapshot(): List<UnifiedDiagnosticRecord> = withContext(ioDispatcher) {
        mutex.withLock {
            if (!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) return@withLock emptyList()
            ensureDirectory()
            withDirectoryLock { scan(prune(managedFiles())) }
        }
    }

    private fun ensureDirectory() {
        if (Files.isSymbolicLink(directory.toPath())) throw IOException("诊断目录不能是符号链接")
        if (!directory.isDirectory && !directory.mkdirs() &&
            !Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)
        ) throw IOException("无法创建诊断目录")
    }

    private suspend fun <T> withDirectoryLock(action: suspend () -> T): T =
        FileChannel.open(
            File(directory, "sequence.lock").toPath(),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, NOFOLLOW_LINKS,
        ).use { channel -> channel.lock().use { action() } }

    private fun readReservation(): Long {
        if (!Files.exists(checkpoint, NOFOLLOW_LINKS)) return 0
        return FileChannel.open(checkpoint, StandardOpenOption.READ, NOFOLLOW_LINKS).use { channel ->
            val size = channel.size()
            if (size !in 1L..19L) throw IOException("诊断序号预留损坏")
            val bytes = ByteBuffer.allocate(size.toInt())
            while (bytes.hasRemaining()) {
                if (channel.read(bytes) < 0) throw IOException("诊断序号预留损坏")
            }
            bytes.array().toString(Charsets.UTF_8).toLongOrNull()?.takeIf { it > 0 }
                ?: throw IOException("诊断序号预留损坏")
        }
    }

    private fun reserveSequence(boundary: Long, first: Boolean) {
        // 先持久保留一段序号再写 JSONL，断电丢失尾行时也不会重用已导出的序号。
        FileChannel.open(
            pendingCheckpoint, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE, NOFOLLOW_LINKS,
        ).use { channel ->
            val bytes = ByteBuffer.wrap(boundary.toString().toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        Files.move(pendingCheckpoint, checkpoint, ATOMIC_MOVE, REPLACE_EXISTING)
        syncDirectory(directory)
        if (first) syncDirectory(requireNotNull(directory.absoluteFile.parentFile))
    }

    private fun syncDirectory(dir: File) {
        FileChannel.open(dir.toPath(), StandardOpenOption.READ, NOFOLLOW_LINKS).use { it.force(true) }
    }

    private fun managedFiles(): List<File> = directory.listFiles().orEmpty().filter {
        managedName.matches(it.name) && Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS)
    }.sortedBy(File::getName)

    private fun newShard(sequence: Long) =
        File(directory, "events-${sequence.toString().padStart(20, '0')}.jsonl")

    private fun shardNumber(file: File): Long =
        managedName.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull() ?: 0

    private fun endsWithNewline(file: File): Boolean =
        FileChannel.open(file.toPath(), StandardOpenOption.READ, NOFOLLOW_LINKS).use { channel ->
            val byte = ByteBuffer.allocate(1)
            channel.read(byte, channel.size() - 1) == 1 && byte.get(0) == '\n'.code.toByte()
        }

    private fun prune(managed: List<File>, incomingBytes: Long = 0, incomingShard: Int = 0): List<File> {
        val files = managed.toMutableList()
        files.filter { it.length() > maxFileBytes }.forEach {
            Files.deleteIfExists(it.toPath())
            files.remove(it)
        }
        var total = files.sumOf(File::length) + incomingBytes
        while (files.size + incomingShard > MAX_SHARDS || total > maxTotalBytes) {
            val oldest = files.removeAt(0)
            total -= oldest.length()
            Files.deleteIfExists(oldest.toPath())
        }
        return files
    }

    private fun encodeBounded(record: UnifiedDiagnosticRecord): ByteArray {
        fun encode(value: UnifiedDiagnosticRecord) = (value.toJsonLine() + "\n").toByteArray(Charsets.UTF_8)
        val bytes = encode(record)
        if (bytes.size <= maxFileBytes) return bytes
        val compactError = record.exception?.copy(frames = emptyList(), causes = emptyList())
        val compact = encode(record.copy(exception = compactError))
        if (compact.size <= maxFileBytes) return compact
        return encode(record.copy(
            fields = JsonObject(record.fields.filterKeys { it == "sessionId" }),
            exception = compactError?.copy(summary = "[内容已隐藏]"),
        )).also { check(it.size <= maxFileBytes) { "单条诊断记录超过容量" } }
    }

    private suspend fun scan(files: List<File>): List<UnifiedDiagnosticRecord> {
        val context = coroutineContext
        var previous = 0L
        return buildList {
            files.filter { it.length() <= maxFileBytes }.forEach { file ->
                val shard = shardNumber(file)
                Files.newInputStream(file.toPath(), StandardOpenOption.READ, NOFOLLOW_LINKS)
                    .bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEachIndexed { index, line ->
                            context.ensureActive()
                            val record = decodeLine(line, shard, index) ?: return@forEachIndexed
                            if (record.seq <= previous) return@forEachIndexed
                            previous = record.seq
                            add(record)
                        }
                    }
            }
        }
    }

    private fun decodeLine(line: String, shard: Long, index: Int): UnifiedDiagnosticRecord? {
        val current = runCatching { diagnosticJson.decodeFromString<UnifiedDiagnosticRecord>(line) }.getOrNull()
        if (current != null && current.schemaVersion == 2 && current.seq > 0) {
            return current.takeIf { it.event == "app.exception" || DiagnosticRedactor.isStructuredEvent(it.event) }
                ?.let { sanitizeRecord(it, projected = true) }
        }
        val legacy = runCatching { diagnosticJson.decodeFromString<DiagnosticEvent>(line) }.getOrNull() ?: return null
        // 存量分片使用固定宽度编号空间，淘汰旧分片后游标仍保持稳定。
        if (shard !in 1..Long.MAX_VALUE / LEGACY_SEQ_STRIDE || index >= LEGACY_SEQ_STRIDE) return null
        return exceptionRecord(legacy).copy(seq = (shard - 1) * LEGACY_SEQ_STRIDE + index + 1)
    }

    private fun sanitizeRecord(record: UnifiedDiagnosticRecord, projected: Boolean): UnifiedDiagnosticRecord {
        val fields = DiagnosticRedactor.projectStructuredFields(record.event, record.fields, projected)
        return record.copy(timestampMs = record.timestampMs.coerceAtLeast(0), fields = fields,
            exception = record.exception?.let(DiagnosticRedactor::sanitize))
    }

    private companion object {
        val mutex: Mutex = Mutex()
        const val MAX_SHARDS = 8
        const val SEQ_BLOCK = 128L
        const val LEGACY_SEQ_STRIDE = 1_000_000L
    }
}

private fun exceptionRecord(event: DiagnosticEvent, sessionReference: String? = null): UnifiedDiagnosticRecord {
    val safe = DiagnosticRedactor.sanitize(event)
    return UnifiedDiagnosticRecord(timestampMs = safe.timestamp, event = "app.exception", fields = buildJsonObject {
        if (safe.scopeKind == "SESSION") put("sessionId", sessionReference ?: safe.scopeId)
    }, exception = safe)
}

private fun recentText(events: List<UnifiedDiagnosticRecord>): String {
    var bytesRemaining = 512 * 1024
    val lines = events.asReversed().asSequence().map(UnifiedDiagnosticRecord::toJsonLine).takeWhile {
        bytesRemaining -= it.toByteArray(Charsets.UTF_8).size + 1
        bytesRemaining >= 0
    }.toList()
    return lines.asReversed().joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n")
}

internal fun writeDiagnosticExport(output: OutputStream, issueSummary: String, recent: String) {
    val text = "Infinity Deck 诊断\n\n启动问题\n${DiagnosticRedactor.sanitizeIssueSummary(issueSummary)}\n\n最近记录\n$recent"
    output.write(text.toByteArray(Charsets.UTF_8))
    output.flush()
}
