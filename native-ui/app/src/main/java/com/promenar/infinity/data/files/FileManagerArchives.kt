package com.promenar.infinity.data.files

import com.promenar.infinity.data.storage.copyExternalStream
import com.promenar.infinity.data.storage.externalStreamHash
import com.promenar.infinity.data.storage.streamHash
import com.promenar.infinity.domain.files.FileOpCode
import com.promenar.infinity.domain.files.FileOpException
import com.promenar.infinity.domain.files.FilePreview
import com.promenar.infinity.domain.files.FileRef
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class ArchiveLimits(
    val singleBytes: Long = DefaultFileOperations.MAX_SINGLE_BYTES,
    val totalBytes: Long = 1024L * 1024 * 1024,
)

internal object FileManagerArchives {
    private const val MAX_ENTRIES = 10_000
    private const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024
    private const val BUFFER = 64 * 1024

    suspend fun compress(cache: File, sources: List<Pair<FileRef, FileBackendHandle>>, target: ExternalFolderBackend, path: String) {
        if (sources.isEmpty()) throw FileOpException(FileOpCode.NOT_FOUND)
        val temp = File.createTempFile("file-manager-", ".zip", cache)
        try {
            var total = 0L
            var count = 0
            val names = mutableSetOf<String>()
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                suspend fun visit(backend: ExternalFolderBackend, source: String, relative: String, depth: Int) {
                    currentCoroutineContext().ensureActive()
                    if (depth > DefaultFileOperations.MAX_DEPTH || ++count > MAX_ENTRIES) throw FileOpException(FileOpCode.TOO_LARGE)
                    safeZipPath(relative)
                    val entry = requireEntry(backend, source)
                    val zipName = relative + if (entry.isDirectory) "/" else ""
                    if (!names.add(zipName)) throw FileOpException(FileOpCode.NAME_EXISTS)
                    zip.putNextEntry(ZipEntry(zipName))
                    if (!entry.isDirectory) {
                        if (entry.size > DefaultFileOperations.MAX_SINGLE_BYTES || entry.size > MAX_TOTAL_BYTES - total) throw FileOpException(FileOpCode.TOO_LARGE)
                        val digest = backend.streamHash(source)
                        backend.readStream(source, DefaultFileOperations.MAX_SINGLE_BYTES) { input ->
                            val written = copyLimited(input, zip, DefaultFileOperations.MAX_SINGLE_BYTES)
                            total += written
                            if (written != entry.size || total > MAX_TOTAL_BYTES) throw FileOpException(FileOpCode.TOO_LARGE)
                        }
                        val after = requireEntry(backend, source)
                        if (entry.identity != after.identity || entry.size != after.size || backend.streamHash(source) != digest) throw FileOpException(FileOpCode.CONFLICT)
                    }
                    zip.closeEntry()
                    if (entry.isDirectory) {
                        for (child in backend.list(source)) visit(backend, joinFilePath(source, child.name), "$relative/${child.name}", depth + 1)
                    }
                }
                for ((ref, handle) in sources) {
                    val source = normalizeFilePath(ref.path)
                    if (source == "/") throw FileOpException(FileOpCode.INVALID_NAME)
                    handle.backend.validateTree(source)
                    val entry = requireEntry(handle.backend, source)
                    visit(handle.backend, source, entry.name, 0)
                }
            }
            if (temp.length() > MAX_ARCHIVE_BYTES) throw FileOpException(FileOpCode.TOO_LARGE)
            target.writeStream(path, temp.length(), true) { output -> temp.inputStream().use { copyExternalStream(it, output) } }
        } finally { temp.delete() }
    }

    suspend fun extract(cache: File, source: ExternalFolderBackend, path: String, target: ExternalFolderBackend, destination: String, limits: ArchiveLimits = ArchiveLimits()) {
        val archive = spoolArchive(cache, source, path)
        val staging = createTempDir(prefix = "file-manager-unzip-", directory = cache)
        data class Staged(val parts: List<String>, val directory: Boolean, val file: File?)
        val staged = mutableListOf<Staged>()
        val created = mutableListOf<Triple<String, ExternalFolderEntry, String?>>()
        try {
            var total = 0L
            val paths = mutableSetOf<String>()
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    currentCoroutineContext().ensureActive()
                    if (staged.size >= MAX_ENTRIES) throw FileOpException(FileOpCode.TOO_LARGE)
                    val entry = entries.nextElement()
                    val parts = safeZipPath(entry.name)
                    if (parts.size > DefaultFileOperations.MAX_DEPTH || !paths.add(parts.joinToString("/"))) throw FileOpException(FileOpCode.INVALID_NAME)
                    if (!entry.isDirectory && entry.size > limits.singleBytes) throw FileOpException(FileOpCode.TOO_LARGE)
                    val stagedFile = if (entry.isDirectory) null else File(staging, "${staged.size}.data").also { file ->
                        zip.getInputStream(entry).use { input -> file.outputStream().use { output ->
                            val count = copyLimited(input, output, minOf(limits.singleBytes, limits.totalBytes - total))
                            total += count
                        } }
                    }
                    staged += Staged(parts, entry.isDirectory, stagedFile)
                }
            }
            val root = target.createDirectory(destination)
            created += Triple(destination, root, null)
            val directories = mutableSetOf(destination)
            for (item in staged) {
                currentCoroutineContext().ensureActive()
                val parents = if (item.directory) item.parts else item.parts.dropLast(1)
                var parent = destination
                for (part in parents) {
                    parent = joinFilePath(parent, part)
                    if (directories.add(parent)) created += Triple(parent, target.createDirectory(parent), null)
                }
                if (!item.directory) {
                    val file = requireNotNull(item.file)
                    val output = joinFilePath(parent, item.parts.last())
                    val hash = file.inputStream().use { externalStreamHash(it) }
                    val receipt = target.writeStream(output, file.length(), true) { stream -> file.inputStream().use { copyExternalStream(it, stream) } }
                    created += Triple(output, receipt, hash)
                    if (target.streamHash(output) != hash) throw FileOpException(FileOpCode.CONFLICT)
                }
            }
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                try {
                    for ((createdPath, receipt, hash) in created.asReversed()) {
                        val current = target.stat(createdPath) ?: continue
                        if (receipt.identity != null && current.identity != receipt.identity) throw FileOpException(FileOpCode.CONFLICT)
                        if (current.isDirectory && target.list(createdPath).isNotEmpty()) throw FileOpException(FileOpCode.CONFLICT)
                        if (!current.isDirectory && target.streamHash(createdPath) != hash) throw FileOpException(FileOpCode.CONFLICT)
                        target.deleteForTransfer(createdPath, receipt, hash)
                    }
                } catch (cleanup: Exception) {
                    val recovery = FileOpException(FileOpCode.CONFLICT, "目标清理未完成，请检查已创建的文件", failure)
                    recovery.addSuppressed(cleanup)
                    if (failure is kotlinx.coroutines.CancellationException) failure.addSuppressed(recovery) else throw recovery
                }
            }
            throw failure
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    suspend fun preview(cache: File, source: ExternalFolderBackend, path: String): FilePreview.Archive {
        val archive = spoolArchive(cache, source, path)
        try {
            return ZipFile(archive).use { zip ->
                val entries = zip.entries()
                val names = mutableListOf<String>()
                var total = 0
                while (entries.hasMoreElements()) {
                    currentCoroutineContext().ensureActive()
                    val item = entries.nextElement()
                    if (++total > MAX_ENTRIES) throw FileOpException(FileOpCode.TOO_LARGE)
                    if (names.size < 200) names += item.name
                }
                FilePreview.Archive(names, total)
            }
        } finally { archive.delete() }
    }

    private suspend fun spoolArchive(cache: File, source: ExternalFolderBackend, path: String): File {
        val entry = requireEntry(source, path)
        if (entry.isDirectory) throw FileOpException(FileOpCode.UNSUPPORTED)
        if (entry.size > MAX_ARCHIVE_BYTES) throw FileOpException(FileOpCode.TOO_LARGE)
        val file = File.createTempFile("file-manager-", ".zip", cache)
        try {
            source.readStream(path, MAX_ARCHIVE_BYTES) { input -> file.outputStream().use { copyLimited(input, it, MAX_ARCHIVE_BYTES) } }
            return file
        } catch (failure: Exception) { file.delete(); throw failure }
    }

    private suspend fun copyLimited(input: InputStream, output: OutputStream, limit: Long): Long {
        if (limit < 0) throw FileOpException(FileOpCode.TOO_LARGE)
        val bytes = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(bytes)
            if (count < 0) break
            if (count.toLong() > limit - total) throw FileOpException(FileOpCode.TOO_LARGE)
            output.write(bytes, 0, count)
            total += count
        }
        return total
    }
}
