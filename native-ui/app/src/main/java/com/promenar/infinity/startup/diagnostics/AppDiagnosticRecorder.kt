package com.promenar.infinity.startup.diagnostics

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 私有诊断目录只管理固定命名的滚动记录；不读取其它文件。 */
class AppDiagnosticRecorder(
    private val directory: File,
    private val maxTotalBytes: Long = 512 * 1024L,
    private val maxFileBytes: Long = 128 * 1024L,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val managedName = Regex("events-[0-9]{20}\\.jsonl")
    private val json = Json { encodeDefaults = true }

    init {
        require(maxFileBytes >= 512 && maxTotalBytes >= maxFileBytes) { "诊断容量配置无效" }
    }

    suspend fun recordError(step: String, error: Throwable) = record(DiagnosticRedactor.snapshot(step, error))

    internal suspend fun record(event: DiagnosticEvent) = withContext(ioDispatcher) {
        mutex.withLock {
            ensureDirectory()
            prune()
            val bytes = encodeBounded(DiagnosticRedactor.sanitize(event))
            val files = managedFiles()
            val last = files.lastOrNull()
            val target = if (last != null && last.length() + bytes.size <= maxFileBytes) {
                last
            } else {
                val previous = last?.name?.removePrefix("events-")?.removeSuffix(".jsonl")?.toLongOrNull() ?: 0
                if (previous == Long.MAX_VALUE) throw IOException("诊断记录编号已耗尽")
                File(directory, "events-${(previous + 1).toString().padStart(20, '0')}.jsonl")
            }
            Files.newOutputStream(
                target.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS,
            ).use { it.write(bytes) }
            prune()
        }
    }

    suspend fun recent(): String = withContext(ioDispatcher) {
        mutex.withLock { readRecent() }
    }

    suspend fun export(output: OutputStream, issueSummary: String) = withContext(ioDispatcher) {
        mutex.withLock {
            writeDiagnosticExport(output, issueSummary, readRecent())
        }
    }

    private fun ensureDirectory() {
        if (Files.isSymbolicLink(directory.toPath())) throw IOException("诊断目录不能是符号链接")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建诊断目录")
    }

    private fun managedFiles(): List<File> = directory.listFiles().orEmpty().filter { file ->
        managedName.matches(file.name) && file.isFile && !Files.isSymbolicLink(file.toPath())
    }.sortedBy(File::getName)

    private fun prune() {
        val files = managedFiles().toMutableList()
        files.filter { it.length() > maxFileBytes }.forEach { file ->
            if (!file.delete()) throw IOException("无法淘汰超限诊断记录")
            files.remove(file)
        }
        var total = files.sumOf(File::length)
        while (total > maxTotalBytes && files.isNotEmpty()) {
            val oldest = files.removeAt(0)
            val size = oldest.length()
            if (!oldest.delete()) throw IOException("无法淘汰旧诊断记录")
            total -= size
        }
    }

    private fun encodeBounded(event: DiagnosticEvent): ByteArray {
        var bounded = event
        var bytes = (json.encodeToString(bounded) + "\n").toByteArray(Charsets.UTF_8)
        while (bytes.size > maxFileBytes && bounded.frames.isNotEmpty()) {
            bounded = bounded.copy(frames = bounded.frames.dropLast(1))
            bytes = (json.encodeToString(bounded) + "\n").toByteArray(Charsets.UTF_8)
        }
        if (bytes.size > maxFileBytes) {
            bounded = bounded.copy(summary = "[内容已隐藏]")
            bytes = (json.encodeToString(bounded) + "\n").toByteArray(Charsets.UTF_8)
        }
        check(bytes.size <= maxFileBytes) { "单条诊断记录超过容量" }
        return bytes
    }

    private fun readRecent(): String {
        if (!directory.exists()) return ""
        ensureDirectory()
        prune()
        return buildString {
            managedFiles().forEach { file ->
                file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        val event = runCatching { json.decodeFromString<DiagnosticEvent>(line) }.getOrNull()
                        if (event != null) {
                            append(json.encodeToString(DiagnosticRedactor.sanitize(event)))
                            append('\n')
                        }
                    }
                }
            }
        }
    }
}

internal fun writeDiagnosticExport(output: OutputStream, issueSummary: String, recent: String) {
    val text = "Infinity Deck 诊断\n\n启动问题\n${DiagnosticRedactor.sanitizeIssueSummary(issueSummary)}\n\n最近记录\n$recent"
    output.write(text.toByteArray(Charsets.UTF_8))
    output.flush()
}
