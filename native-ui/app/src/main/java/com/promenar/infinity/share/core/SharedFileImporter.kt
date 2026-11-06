package com.promenar.infinity.share.core

import android.net.Uri
import com.promenar.infinity.ShareRequest
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.repository.WorkspaceFileTooLargeException
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import com.promenar.infinity.data.rag.DocumentTextEncoding
import java.io.OutputStream
import java.util.UUID

interface SharedContentSource {
    fun metadata(uri: Uri): SharedContentMetadata
    fun open(uri: Uri): InputStream
    fun preflightReason(uri: Uri): ShareRejectReason? = null
    fun preflightItem(uri: Uri): ShareImportItem? = null
    fun contentSha256(uri: Uri): String? = null
}

fun interface ShareIndexScheduler {
    suspend fun schedule(workspaceRootUuid: String, entry: FileEntry): ShareIndexReceipt
}

class SharedFileImporter(
    private val source: SharedContentSource,
    private val workspace: IWorkspaceRepository,
    private val indexScheduler: ShareIndexScheduler = ShareIndexScheduler { _, entry ->
        ShareIndexReceipt(taskId = "no-op-${entry.uuid}", fileUuid = entry.uuid)
    },
    private val linuxFiles: com.promenar.infinity.domain.linux.LinuxFileSystem? = null,
    private val linuxReady: () -> Boolean = { false },
) {
    internal var linuxFileSystem: com.promenar.infinity.data.storage.RealPathFileSystem = com.promenar.infinity.data.storage.AndroidRealPathFileSystem

    suspend fun import(
        request: ShareRequest,
        destination: ShareImportDestination,
        retryUris: Set<Uri>? = null,
    ): ShareImportBatchResult = when (destination) {
        is ShareImportDestination.SessionFolder -> {
            val root = withContext(Dispatchers.IO) { workspace.ensureSessionRoot(destination.sessionId) }
            if (root.uuid != destination.workspaceRootUuid) rejectedBatch(request, retryUris, ShareRejectReason.TargetRequired)
            else import(request, root.uuid, retryUris)
        }
        is ShareImportDestination.KnowledgeFolder -> import(request, destination.workspaceRootUuid, retryUris, destination.folderUuid)
        is ShareImportDestination.LinuxDirectory -> importShareToLinux(request, destination.path, retryUris, source, linuxFiles, linuxReady, linuxFileSystem) { inspect(request) }
    }

    private suspend fun rejectedBatch(request: ShareRequest, retryUris: Set<Uri>?, reason: ShareRejectReason) =
        ShareImportBatchResult(inspect(request).filter { retryUris == null || it.uri in retryUris }.map { it.reject(reason) })

    suspend fun inspect(request: ShareRequest): List<ShareImportItem> = withContext(Dispatchers.IO) {
        request.uris.map { uri ->
            source.preflightItem(uri)?.let { return@map it.copy(parseable = isParseable(it.mimeType, it.displayName)) }
            val metadata = try { source.metadata(uri) }
            catch (_: SecurityException) { return@map fallback(uri, request.mimeType).reject(ShareRejectReason.PermissionDenied) }
            catch (_: IOException) { return@map fallback(uri, request.mimeType).reject(ShareRejectReason.ReadFailed) }
            catch (_: IllegalArgumentException) { return@map fallback(uri, request.mimeType).reject(ShareRejectReason.ReadFailed) }
            val item = ShareImportItem(
                uri = uri,
                displayName = metadata.displayName?.takeIf { it.isNotBlank() }
                    ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
                    ?: "shared-file",
                mimeType = metadata.mimeType ?: request.mimeType,
                sizeBytes = metadata.sizeBytes,
                parseable = isParseable(metadata.mimeType ?: request.mimeType, metadata.displayName ?: uri.lastPathSegment.orEmpty()),
            )
            source.preflightReason(uri)?.let { item.reject(it) } ?: item
        }
    }

    suspend fun import(
        request: ShareRequest,
        workspaceRootUuid: String,
        retryUris: Set<Uri>? = null,
        parentUuid: String = workspaceRootUuid,
    ): ShareImportBatchResult = withContext(Dispatchers.IO) {
        if (workspaceRootUuid.isBlank() || parentUuid.isBlank()) {
            return@withContext ShareImportBatchResult(
                inspect(request).map { it.reject(ShareRejectReason.TargetRequired) }
            )
        }
        val target = resolveTarget(workspaceRootUuid, parentUuid)
            ?: return@withContext ShareImportBatchResult(
                inspect(request).map { it.reject(ShareRejectReason.TargetRequired) }
            )
        var acceptedBytes = 0L
        val results = mutableListOf<ShareImportItem>()
        for (uri in request.uris) {
            if (retryUris != null && uri !in retryUris) continue
            source.preflightItem(uri)?.let { completed ->
                results += completed
                continue
            }
            source.preflightReason(uri)?.let { reason ->
                val metadata = metadataOrNull(uri)
                results += ShareImportItem(
                    uri = uri,
                    displayName = metadata?.displayName ?: "shared-file",
                    mimeType = metadata?.mimeType ?: request.mimeType,
                    sizeBytes = metadata?.sizeBytes,
                ).reject(reason)
                continue
            }
            val metadata = try {
                source.metadata(uri)
            } catch (_: SecurityException) {
                results += fallback(uri, request.mimeType).reject(ShareRejectReason.PermissionDenied)
                continue
            } catch (_: IOException) {
                results += fallback(uri, request.mimeType).reject(ShareRejectReason.ReadFailed)
                continue
            } catch (_: IllegalArgumentException) {
                results += fallback(uri, request.mimeType).reject(ShareRejectReason.ReadFailed)
                continue
            }
            val initial = ShareImportItem(
                uri = uri,
                displayName = metadata.displayName ?: uri.lastPathSegment ?: "shared-file",
                mimeType = metadata.mimeType ?: request.mimeType,
                sizeBytes = metadata.sizeBytes,
                status = ShareImportStatus.Importing,
                parseable = isParseable(metadata.mimeType ?: request.mimeType, metadata.displayName ?: uri.lastPathSegment.orEmpty()),
            )
            val safeName = normalizeName(initial.displayName)
            if (safeName == null) {
                results += initial.reject(ShareRejectReason.InvalidName)
                continue
            }
            val declared = normalizeMime(request.mimeType)
            val resolved = normalizeMime(metadata.mimeType ?: request.mimeType)
            val size = metadata.sizeBytes
            if (size != null && size <= 0L) {
                results += initial.reject(ShareRejectReason.EmptyFile)
                continue
            }
            if (size != null && size > MAX_ITEM_BYTES) {
                results += initial.reject(ShareRejectReason.ItemTooLarge)
                continue
            }
            if (size != null && acceptedBytes > MAX_BATCH_BYTES - size) {
                results += initial.reject(ShareRejectReason.BatchTooLarge)
                continue
            }
            try {
                val contentMime = parseableMime(resolved, safeName)
                val sniffed = contentMime?.let { sniffSource(uri, it) }
                if (contentMime != null && !mimeCompatible(declared, resolved, sniffed, safeName)) {
                    results += initial.reject(ShareRejectReason.MimeMismatch)
                    continue
                }
                var validatedDigest: ByteArray? = null
                if (contentMime in TEXT_MIME_TYPES) {
                    val limit = minOf(MAX_ITEM_BYTES, MAX_BATCH_BYTES - acceptedBytes)
                    DocumentTextEncoding.detect { check ->
                        openSource(uri).use { source ->
                            val digest = java.security.MessageDigest.getInstance("SHA-256")
                            val limited = object : java.io.FilterInputStream(progressStream(source)) {
                            private var consumed = 0L
                            override fun read(): Int {
                                val value = super.read()
                                if (value >= 0 && ++consumed > limit) throw WorkspaceFileTooLargeException(limit)
                                return value
                            }
                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                val count = super.read(buffer, offset, length)
                                if (count > 0) { consumed += count; if (consumed > limit) throw WorkspaceFileTooLargeException(limit) }
                                return count
                            }
                            }
                            val valid = check(java.security.DigestInputStream(limited, digest))
                            validatedDigest = digest.digest()
                            valid
                        }
                    }
                }
                val remainingBatch = MAX_BATCH_BYTES - acceptedBytes
                val itemLimit = minOf(MAX_ITEM_BYTES, remainingBatch)
                val creation = createWithStableName(
                    workspaceRootUuid = workspaceRootUuid,
                    parentUuid = target.uuid,
                    parentPath = target.materializedPath,
                    requestedName = safeName,
                    mimeType = contentMime ?: resolved,
                    maxBytes = itemLimit,
                    expectedHash = source.contentSha256(uri),
                ) {
                    openSource(uri).use { input ->
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        java.security.DigestInputStream(progressStream(input), digest).copyTo(it, DEFAULT_BUFFER_SIZE)
                        if (validatedDigest?.contentEquals(digest.digest()) == false) throw ShareContentMismatchException()
                    }
                }
                val entry = creation.entry
                if (entry.sizeBytes == 0L) {
                    rollbackIfCreatedNow(workspaceRootUuid, creation)
                    results += initial.reject(ShareRejectReason.EmptyFile)
                    continue
                }
                val stagedHash = source.contentSha256(uri)
                if (stagedHash != null && entry.hash != stagedHash) {
                    rollbackIfCreatedNow(workspaceRootUuid, creation)
                    results += initial.reject(ShareRejectReason.ReadFailed)
                    continue
                }
                acceptedBytes += entry.sizeBytes
                try {
                    val receipt = if (initial.parseable) indexScheduler.schedule(workspaceRootUuid, entry) else null
                    if (creation.createdNow) {
                        workspace.confirmCreatedEntry(workspaceRootUuid, entry.uuid)
                    }
                    results += initial.copy(
                        displayName = entry.name,
                        mimeType = contentMime ?: resolved,
                        sizeBytes = entry.sizeBytes,
                        status = ShareImportStatus.Created,
                        created = entry,
                        indexStatus = receipt?.let { ShareIndexStatus.Pending },
                        fileUuid = receipt?.fileUuid ?: entry.uuid,
                        indexTaskId = receipt?.taskId,
                    )
                } catch (cancelled: CancellationException) {
                    try {
                        rollbackIfCreatedNow(workspaceRootUuid, creation, nonCancellable = true)
                    } catch (cleanupFailure: Exception) {
                        cancelled.addSuppressed(cleanupFailure)
                    }
                    throw cancelled
                } catch (failure: Exception) {
                    try {
                        rollbackIfCreatedNow(workspaceRootUuid, creation)
                        // 已成功回滚的项目不占成功导入批次预算；清理失败时保留占额。
                        acceptedBytes -= entry.sizeBytes
                    } catch (cleanupFailure: Exception) {
                        failure.addSuppressed(cleanupFailure)
                    }
                    if (failure is IOException || failure is IllegalArgumentException ||
                        failure is IllegalStateException || failure is android.database.SQLException
                    ) {
                        results += initial.reject(ShareRejectReason.IndexScheduleFailed)
                    } else {
                        throw failure
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: com.promenar.infinity.data.rag.DocumentFormatException) {
                results += initial.reject(ShareRejectReason.MimeMismatch)
            } catch (_: ShareContentMismatchException) {
                results += initial.reject(ShareRejectReason.MimeMismatch)
            } catch (_: WorkspaceFileTooLargeException) {
                results += initial.reject(
                    if (acceptedBytes > 0L) ShareRejectReason.BatchTooLarge
                    else ShareRejectReason.ItemTooLarge
                )
            } catch (_: SecurityException) {
                results += initial.reject(ShareRejectReason.PermissionDenied)
            } catch (_: ShareSourceReadException) {
                results += initial.reject(ShareRejectReason.ReadFailed)
            } catch (_: IOException) {
                results += initial.reject(ShareRejectReason.WriteFailed)
            } catch (_: IllegalArgumentException) {
                results += initial.reject(ShareRejectReason.WriteFailed)
            } catch (_: IllegalStateException) {
                results += initial.reject(ShareRejectReason.WriteFailed)
            } catch (_: android.database.SQLException) {
                results += initial.reject(ShareRejectReason.WriteFailed)
            }
        }
        ShareImportBatchResult(results)
    }

    private fun openSource(uri: Uri): InputStream = source.openForShare(uri)

    private fun metadataOrNull(uri: Uri): SharedContentMetadata? = try {
        source.metadata(uri)
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun sniffSource(uri: Uri, expectedMime: String): String? = try {
        openSource(uri).use { sniffMime(it, expectedMime) }
    } catch (failure: ShareSourceReadException) {
        throw failure
    } catch (_: IOException) {
        throw ShareContentMismatchException()
    } catch (_: IllegalArgumentException) {
        throw ShareContentMismatchException()
    }

    private suspend fun createWithStableName(
        workspaceRootUuid: String,
        parentUuid: String,
        parentPath: String,
        requestedName: String,
        mimeType: String,
        maxBytes: Long,
        expectedHash: String?,
        writer: (java.io.OutputStream) -> Unit,
    ): FileCreation {
        repeat(MAX_NAME_ATTEMPTS) { collisionIndex ->
            val name = collisionName(requestedName, collisionIndex)
            val path = childPath(parentPath, name)
            workspace.getByMaterializedPath(workspaceRootUuid, path)?.let { existing ->
                if (expectedHash != null && existing.hash == expectedHash) {
                    return FileCreation(existing, createdNow = false)
                }
                return@repeat
            }
            try {
                return FileCreation(workspace.createFileInWorkspaceStreaming(
                    workspaceRootUuid = workspaceRootUuid,
                    uuid = UUID.randomUUID().toString(),
                    name = name,
                    mimeType = mimeType,
                    parentUuid = parentUuid,
                    materializedPath = path,
                    maxBytes = maxBytes,
                    writer = writer,
                ), createdNow = true)
            } catch (collision: IllegalStateException) {
                if (workspace.getByMaterializedPath(workspaceRootUuid, path) == null) throw collision
            }
        }
        throw IllegalStateException("同名文件过多")
    }

    private suspend fun resolveTarget(workspaceRootUuid: String, parentUuid: String): ImportTarget? {
        if (parentUuid == workspaceRootUuid) {
            return ImportTarget(parentUuid, "/")
        }
        val entry = try {
            workspace.getByUuid(workspaceRootUuid, parentUuid)
        } catch (_: IOException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: IllegalStateException) {
            return null
        } catch (_: android.database.SQLException) {
            return null
        } ?: return null
        if (!entry.isDirectory || entry.inRecycleBin ||
            (entry.workspaceRootUuid.isNotBlank() && entry.workspaceRootUuid != workspaceRootUuid) ||
            !isSafeParentPath(entry.materializedPath)
        ) return null
        return ImportTarget(entry.uuid, entry.materializedPath)
    }

    private data class ImportTarget(val uuid: String, val materializedPath: String)

    private suspend fun rollbackIfCreatedNow(
        workspaceRootUuid: String,
        creation: FileCreation,
        nonCancellable: Boolean = false,
    ) {
        if (!creation.createdNow) return
        if (nonCancellable) {
            withContext(NonCancellable) {
                workspace.rollbackCreatedEntry(workspaceRootUuid, creation.entry.uuid)
            }
        } else {
            workspace.rollbackCreatedEntry(workspaceRootUuid, creation.entry.uuid)
        }
    }

    private data class FileCreation(val entry: FileEntry, val createdNow: Boolean)

    companion object {
        const val MAX_ITEM_BYTES = 100L * 1024 * 1024
        const val MAX_BATCH_BYTES = 100L * 1024 * 1024
        private const val MAX_NAME_ATTEMPTS = 10_000
        val SUPPORTED_MIME_TYPES = setOf(
            "text/plain",
            "text/markdown",
            "text/csv",
            "text/html",
            "application/json",
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )

        internal fun normalizeName(raw: String): String? {
            val name = raw.trim()
            if (name.isEmpty() || name == "." || name == ".." || name.length > 255 ||
                name.any { it.isISOControl() } || '/' in name || '\\' in name ||
                com.promenar.infinity.domain.workspace.WorkspaceInternalPaths.isReservedName(name)
            ) return null
            val stem = name.substringBeforeLast('.').uppercase()
            if (stem in WINDOWS_RESERVED || RESERVED_DEVICE.matches(stem)) return null
            return name
        }

        internal fun collisionName(original: String, index: Int): String {
            if (index == 0) return original
            val dot = original.lastIndexOf('.').takeIf { it > 0 }
            val base = if (dot == null) original else original.substring(0, dot)
            val extension = if (dot == null) "" else original.substring(dot)
            return "$base ($index)$extension"
        }

        private fun childPath(parentPath: String, name: String): String =
            if (parentPath == "/") "/$name" else "${parentPath.trimEnd('/')}/$name"

        private fun isSafeParentPath(path: String): Boolean =
            path.startsWith('/') && path.split('/').none { it == "." || it == ".." || com.promenar.infinity.domain.workspace.WorkspaceInternalPaths.isReservedName(it) }

        internal fun normalizeMime(value: String): String = value.substringBefore(';').trim().lowercase()

        internal fun sniffMime(input: InputStream, expectedMime: String): String? {
            if (expectedMime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document") {
                var contentTypes = false
                var wordDocument = false
                java.util.zip.ZipInputStream(input).use { zip ->
                    var inspected = 0
                    var expandedBytes = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        inspected += 1
                        if (inspected > 2_048) throw IOException("DOCX预检条目数超过2048")
                        when (entry.name) {
                            "[Content_Types].xml" -> contentTypes = true
                            "word/document.xml" -> wordDocument = true
                        }
                        // nextEntry会隐式读完上一条目；先按实际展开字节有界消费，避免压缩小包绕过导入限额。
                        while (true) {
                            val remaining = DOCX_SNIFF_EXPANDED_BYTES - expandedBytes
                            val count = zip.read(buffer, 0, minOf(buffer.size.toLong(), remaining + 1).toInt())
                            if (count < 0) break
                            if (count == 0) throw IOException("DOCX预检读取无进展")
                            expandedBytes += count
                            if (expandedBytes > DOCX_SNIFF_EXPANDED_BYTES) {
                                throw IOException("DOCX预检解压量超过64MiB")
                            }
                        }
                    }
                }
                return if (contentTypes && wordDocument) expectedMime else "application/zip"
            }
            val sampled = java.io.ByteArrayOutputStream(SNIFF_BYTES + MAX_UTF8_SEQUENCE_BYTES - 1)
            var eof = false
            while (sampled.size() < SNIFF_BYTES) {
                val count = readSome(input, sampled, SNIFF_BYTES - sampled.size())
                if (count < 0) {
                    eof = true
                    break
                }
            }
            if (sampled.size() == 0) return null
            var bytes = sampled.toByteArray()
            if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(), 0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte()))) return DOC_MIME
            if (bytes.size >= 5 && bytes.copyOfRange(0, 5).toString(Charsets.US_ASCII) == "%PDF-") {
                return "application/pdf"
            }
            if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
                bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
            ) return "application/zip"
            // 样本末尾可能截断多字节字符，尝试去掉最多三个尾字节。
            for (trim in 0..if (eof) 0 else minOf(3, bytes.size)) {
                val sample = bytes.copyOf(bytes.size - trim)
                if (sample.isNotEmpty() && runCatching {
                    DocumentTextEncoding.detect { check -> sample.inputStream().use(check) }
                }.isSuccess) return "text/plain"
            }
            return "application/octet-stream"
        }

        private fun progressStream(input: InputStream): InputStream = object : java.io.FilterInputStream(input) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                val count = super.read(buffer, offset, length)
                if (count != 0) return count
                val value = super.read()
                if (value < 0) return -1
                buffer[offset] = value.toByte()
                return 1
            }
        }

        private fun readSome(input: InputStream, target: java.io.ByteArrayOutputStream, limit: Int): Int {
            val buffer = ByteArray(limit.coerceAtLeast(1))
            val count = input.read(buffer, 0, buffer.size)
            if (count > 0) target.write(buffer, 0, count)
            if (count != 0) return count
            val single = input.read()
            if (single >= 0) target.write(single)
            return if (single >= 0) 1 else -1
        }

        internal fun mimeCompatible(declared: String, resolved: String, sniffed: String?, name: String): Boolean {
            val effective = parseableMime(resolved, name) ?: return true
            val extension = extensionMime(name)
            if (resolved in SUPPORTED_MIME_TYPES && extension != null && family(resolved) != family(extension)) return false
            val declaration = normalizeMime(declared)
            if ('*' !in declaration && declaration in SUPPORTED_MIME_TYPES && family(declaration) != family(effective)) return false
            return when (effective) {
                "application/pdf" -> sniffed == effective && name.endsWith(".pdf", true)
                DOCX_MIME -> sniffed == effective && name.endsWith(".docx", true)
                DOC_MIME -> sniffed == effective && name.endsWith(".doc", true)
                else -> sniffed == "text/plain"
            }
        }

        fun isParseable(mime: String?, name: String): Boolean = parseableMime(mime, name) != null

        internal fun parseableMime(mime: String?, name: String): String? =
            mime?.let(::normalizeMime)?.takeIf { it in SUPPORTED_MIME_TYPES } ?: extensionMime(name)

        private fun extensionMime(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
            "txt" -> "text/plain"
            "md", "markdown" -> "text/markdown"
            "csv" -> "text/csv"
            "htm", "html" -> "text/html"
            "json" -> "application/json"
            "pdf" -> "application/pdf"
            "docx" -> DOCX_MIME
            "doc" -> DOC_MIME
            else -> null
        }

        private fun family(mime: String): String = if (mime in TEXT_MIME_TYPES) "text" else mime

        private fun fallback(uri: Uri, mime: String) = ShareImportItem(
            uri = uri,
            displayName = uri.lastPathSegment ?: "shared-file",
            mimeType = mime,
            sizeBytes = null,
        )

        private fun ShareImportItem.reject(reason: ShareRejectReason) = copy(
            status = ShareImportStatus.Rejected,
            reason = reason,
            created = null,
        )

        private val WINDOWS_RESERVED = setOf("CON", "PRN", "AUX", "NUL")
        private val RESERVED_DEVICE = Regex("(?:COM|LPT)[1-9]")
        private const val SNIFF_BYTES = 8_192
        private const val MAX_UTF8_SEQUENCE_BYTES = 4
        private const val DOCX_SNIFF_EXPANDED_BYTES = 64L * 1024 * 1024
        internal const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        internal const val DOC_MIME = "application/msword"
        internal val TEXT_MIME_TYPES = setOf(
            "text/plain",
            "text/markdown",
            "text/csv",
            "text/html",
            "application/json",
        )
    }
}
