package com.promenar.infinity.data.files

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.promenar.infinity.data.local.db.dao.VectorizationTaskDao
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.repository.WorkspaceLifecycleErrorCode
import com.promenar.infinity.data.repository.WorkspaceLifecycleException
import com.promenar.infinity.data.session.SessionExecutionGate
import com.promenar.infinity.data.storage.ExternalTreeTransfer
import com.promenar.infinity.data.storage.copyExternalStream
import com.promenar.infinity.data.storage.externalStreamHash
import com.promenar.infinity.data.storage.streamHash
import com.promenar.infinity.domain.files.*
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.repository.RenameResult
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.workspace.DirectoryFileOpException
import com.promenar.infinity.share.core.AndroidSafContentSource
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal class FileMutationGuard(
    private val gate: SessionExecutionGate,
    private val busy: suspend (String) -> Boolean,
) {
    suspend fun <T> run(handles: Collection<FileBackendHandle>, action: suspend () -> T): T {
        if (handles.any { !it.writable }) throw FileOpException(FileOpCode.READ_ONLY)
        val ids = handles.flatMap { it.sessionIds }.distinct().sorted()
        suspend fun lock(index: Int): T = if (index == ids.size) action() else gate.withSessionMutation(ids[index]) {
            if (busy(ids[index])) throw FileOpException(FileOpCode.CONFLICT)
            lock(index + 1)
        }
        return lock(0)
    }
}

internal class DefaultFileOperations(
    private val context: Context,
    private val resolve: suspend (String) -> FileBackendHandle,
    private val sessions: suspend () -> List<SessionEntity>,
    private val repository: IWorkspaceRepository,
    private val tasks: VectorizationTaskDao,
    private val indexEvents: FileIndexEventSink,
    private val guard: FileMutationGuard,
    private val pendingTargets: () -> List<FileIndexEvent.Changed> = { emptyList() },
) : FileOperations {
    private val importer = AndroidSafContentSource(context.contentResolver)

    override suspend fun list(locationId: String, path: String): List<FileItem> = io {
        val normalized = normalizeFilePath(path)
        if (locationId == "sessions") {
            if (normalized != "/") throw FileOpException(FileOpCode.NOT_FOUND)
            val rows = sessions().filter { !isSystemSession(it) && !it.workspaceRootUuid.isNullOrBlank() }
            val names = sessionFolderNames(rows)
            return@io rows.sortedBy { names[it.id]?.lowercase() }.map { row ->
                FileItem(FileRef("session:${row.id}", "/"), names.getValue(row.id), true, 0, row.updatedAt)
            }
        }
        val handle = resolve(locationId)
        val parent = handle.backend.stat(normalized) ?: throw FileOpException(FileOpCode.NOT_FOUND)
        if (!parent.isDirectory) throw FileOpException(FileOpCode.NOT_FOUND)
        items(handle, handle.backend.list(normalized))
    }

    override suspend fun stat(ref: FileRef): FileItem? = io {
        val path = normalizeFilePath(ref.path)
        if (ref.locationId == "sessions") {
            if (path == "/") return@io FileItem(FileRef("sessions", "/"), "", true, 0, 0)
            return@io null
        }
        val handle = resolve(ref.locationId)
        handle.backend.stat(path)?.let { items(handle, listOf(it)).single() }
    }

    override suspend fun search(locationId: String, query: FileSearchQuery, limit: Int): List<FileSearchHit> = io {
        if (limit <= 0) return@io emptyList()
        val within = normalizeFilePath(query.withinPath ?: "/")
        val needle = query.text
        if (locationId == "sessions") {
            if (within != "/") throw FileOpException(FileOpCode.NOT_FOUND)
            val hits = mutableListOf<FileSearchHit>()
            for (folder in list("sessions", "/")) {
                currentCoroutineContext().ensureActive()
                if (folder.name.contains(needle, ignoreCase = true) && query.types.isEmpty() &&
                    (query.modifiedAfter == null || folder.lastModified > query.modifiedAfter)) hits += FileSearchHit(folder, "/")
                if (hits.size >= limit) break
                hits += search(folder.ref.locationId, query.copy(withinPath = "/"), limit - hits.size)
                if (hits.size >= limit) break
            }
            return@io hits.take(limit)
        }
        val handle = resolve(locationId)
        if (handle.roomRoot != null) {
            val base = within.trimEnd('/') + "/"
            val searchContext = currentCoroutineContext()
            val entries = repository.searchByName(handle.roomRoot, "").first().filter { entry ->
                searchContext.ensureActive()
                val path = entry.materializedPath
                runCatching { normalizeFilePath(path) }.isSuccess && entry.name.contains(needle, ignoreCase = true) &&
                    path.startsWith(base) && path.removePrefix(base).count { it == '/' } < MAX_DEPTH &&
                    (query.modifiedAfter == null || entry.updatedAt > query.modifiedAfter) &&
                    (query.types.isEmpty() || !entry.isDirectory && classifyFile(entry.name, entry.mimeType) in query.types)
            }.take(limit)
            val mapped = items(handle, entries.map { ExternalFolderEntry(it.materializedPath, it.name, it.isDirectory, it.sizeBytes, it.updatedAt, it.hash, it.uuid) })
            return@io mapped.map { FileSearchHit(it, parentFilePath(it.ref.path)) }
        }
        val hits = mutableListOf<FileSearchHit>()
        suspend fun visit(path: String, depth: Int) {
            if (depth >= MAX_DEPTH || hits.size >= limit) return
            for (child in handle.backend.list(path)) {
                currentCoroutineContext().ensureActive()
                if (hits.size >= limit) break
                val item = items(handle, listOf(child)).single()
                // 后端若把目录自身列为子项，跳过以免重复遍历。
                if (item.ref.path == normalizeFilePath(path)) continue
                if (item.name.contains(needle, ignoreCase = true) &&
                    (query.modifiedAfter == null || item.lastModified > query.modifiedAfter) &&
                    (query.types.isEmpty() || !item.isDirectory && classifyFile(item.name, item.mimeType) in query.types)) {
                    hits += FileSearchHit(item, path)
                }
                if (item.isDirectory) visit(item.ref.path, depth + 1)
            }
        }
        val parent = handle.backend.stat(within) ?: throw FileOpException(FileOpCode.NOT_FOUND)
        if (!parent.isDirectory) throw FileOpException(FileOpCode.NOT_FOUND)
        visit(within, 0)
        hits
    }

    override suspend fun createFolder(parent: FileRef, name: String): FileItem = io {
        val handle = resolve(parent.locationId)
        val path = joinFilePath(parent.path, name)
        guard.run(listOf(handle)) {
            requireDirectory(handle.backend, parent.path)
            requireAbsent(handle.backend, path)
            handle.backend.createDirectory(path)
            handle.confirmCreated()
            requireNotNull(stat(FileRef(parent.locationId, path)))
        }
    }

    override suspend fun createTextFile(parent: FileRef, name: String, content: String): FileItem = io {
        val handle = resolve(parent.locationId)
        val path = joinFilePath(parent.path, name)
        guard.run(listOf(handle)) {
            requireDirectory(handle.backend, parent.path)
            requireAbsent(handle.backend, path)
            val bytes = content.toByteArray(Charsets.UTF_8)
            handle.backend.writeStream(path, bytes.size.toLong(), true) { it.write(bytes) }
            handle.confirmCreated()
            requireNotNull(stat(FileRef(parent.locationId, path)))
        }
    }

    override suspend fun rename(ref: FileRef, newName: String): FileItem = io {
        val handle = resolve(ref.locationId)
        val source = normalizeFilePath(ref.path)
        if (source == "/") throw FileOpException(FileOpCode.INVALID_NAME)
        val destination = joinFilePath(parentFilePath(source), newName)
        guard.run(listOf(handle)) {
            val entry = requireEntry(handle.backend, source)
            if (source != destination) {
                requireAbsent(handle.backend, destination)
                if (handle.roomRoot != null) {
                    val row = repository.getByMaterializedPath(handle.roomRoot, source) ?: throw FileOpException(FileOpCode.NOT_FOUND)
                    publishRename(handle.roomRoot, repository.rename(handle.roomRoot, row.uuid, newName, expectedName = row.name))
                } else handle.backend.move(source, destination)
            }
            requireNotNull(stat(FileRef(ref.locationId, if (source == destination) entry.path else destination)))
        }
    }

    override suspend fun copy(sources: List<FileRef>, targetDirectory: FileRef): FileOpOutcome =
        transfer(sources, targetDirectory, moving = false)

    override suspend fun move(sources: List<FileRef>, targetDirectory: FileRef): FileOpOutcome =
        transfer(sources, targetDirectory, moving = true)

    private suspend fun transfer(sources: List<FileRef>, targetDirectory: FileRef, moving: Boolean): FileOpOutcome = io {
        val success = mutableListOf<FileRef>()
        val failed = mutableListOf<FileOpFailure>()
        for (sourceRef in sources.distinct()) {
            currentCoroutineContext().ensureActive()
            try {
                val source = resolve(sourceRef.locationId)
                val target = resolve(targetDirectory.locationId)
                guard.run(if (moving) listOf(source, target) else listOf(target)) {
                    val from = normalizeFilePath(sourceRef.path)
                    val parent = normalizeFilePath(targetDirectory.path)
                    if (from == "/") throw FileOpException(FileOpCode.INVALID_NAME)
                    val entry = requireEntry(source.backend, from)
                    requireDirectory(target.backend, parent)
                    if (sourceRef.locationId == targetDirectory.locationId && entry.isDirectory &&
                        (parent == from || parent.startsWith("$from/"))) throw FileOpException(FileOpCode.CONFLICT)
                    var to = joinFilePath(parent, entry.name)
                    if (moving) requireAbsent(target.backend, to)
                    else {
                        var suffix = 2
                        while (target.backend.stat(to) != null) to = joinFilePath(parent, collisionName(entry.name, entry.isDirectory, suffix++))
                    }
                    if (moving && sourceRef.locationId == targetDirectory.locationId && source.roomRoot != null) {
                        val row = repository.getByMaterializedPath(source.roomRoot, from) ?: throw FileOpException(FileOpCode.NOT_FOUND)
                        val parentRow = repository.getByMaterializedPath(source.roomRoot, parent) ?: throw FileOpException(FileOpCode.NOT_FOUND)
                        publishRename(source.roomRoot, repository.relocate(source.roomRoot, row.uuid, parentRow.uuid, row.name,
                            expectedName = row.name, expectedHash = row.hash.takeUnless { row.isDirectory }))
                    } else if (moving && sourceRef.locationId == targetDirectory.locationId) {
                        source.backend.move(from, to)
                    } else if (moving) {
                        try { ExternalTreeTransfer.move(source.backend, from, target.backend, to) }
                        finally { withContext(NonCancellable) { source.confirmCreated(); target.confirmCreated() } }
                    } else {
                        try { copyTree(source.backend, from, target.backend, to) }
                        finally { withContext(NonCancellable) { target.confirmCreated() } }
                    }
                    success += FileRef(targetDirectory.locationId, to)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failed += FileOpFailure(sourceRef, mapped(error).code) }
        }
        FileOpOutcome(success, failed)
    }

    override suspend fun delete(sources: List<FileRef>): FileOpOutcome = io {
        val succeeded = mutableListOf<FileRef>()
        val failed = mutableListOf<FileOpFailure>()
        for (ref in sources.distinct()) {
            try {
                val handle = resolve(ref.locationId)
                guard.run(listOf(handle)) {
                    val path = normalizeFilePath(ref.path)
                    if (path == "/") throw FileOpException(FileOpCode.INVALID_NAME)
                    val entry = requireEntry(handle.backend, path)
                    if (handle.roomRoot != null) {
                        val row = repository.getByMaterializedPath(handle.roomRoot, path) ?: throw FileOpException(FileOpCode.NOT_FOUND)
                        if ((entry.identity != null && entry.identity != row.uuid) || entry.isDirectory != row.isDirectory) {
                            throw FileOpException(FileOpCode.CONFLICT)
                        }
                        if (!entry.isDirectory && entry.sha256 != null) {
                            repository.permanentDeleteIfHash(handle.roomRoot, row.uuid, entry.sha256)
                        } else repository.permanentDelete(handle.roomRoot, row.uuid)
                    } else handle.backend.delete(path, entry.sha256)
                    succeeded += ref.copy(path = path)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failed += FileOpFailure(ref, mapped(error).code) }
        }
        FileOpOutcome(succeeded, failed)
    }

    override suspend fun importText(text: String, targetDirectory: FileRef, name: String) = createTextFile(targetDirectory, name, text)

    override suspend fun importUris(uris: List<String>, targetDirectory: FileRef): FileOpOutcome = io {
        val succeeded = mutableListOf<FileRef>()
        val failed = mutableListOf<FileOpFailure>()
        for (raw in uris) {
            val failureRef = targetDirectory.copy(path = normalizeFilePath(targetDirectory.path))
            try {
                val uri = Uri.parse(raw)
                if (uri.scheme != "content") throw FileOpException(FileOpCode.UNSUPPORTED)
                val metadata = importer.metadata(uri)
                val name = validateFileName(metadata.displayName ?: throw FileOpException(FileOpCode.INVALID_NAME))
                if (metadata.sizeBytes != null && metadata.sizeBytes !in 0..MAX_SINGLE_BYTES) throw FileOpException(FileOpCode.TOO_LARGE)
                val staging = if (metadata.sizeBytes == null) File.createTempFile("file-manager-import-", ".tmp", context.cacheDir) else null
                try {
                    if (staging != null) importer.open(uri).use { input -> staging.outputStream().use { output -> copyImportStream(input, output) } }
                    val size = metadata.sizeBytes ?: requireNotNull(staging).length()
                    val handle = resolve(targetDirectory.locationId)
                    guard.run(listOf(handle)) {
                        requireDirectory(handle.backend, targetDirectory.path)
                        var destination = joinFilePath(targetDirectory.path, name)
                        var suffix = 2
                        while (handle.backend.stat(destination) != null) destination = joinFilePath(targetDirectory.path, collisionName(name, false, suffix++))
                        handle.backend.writeStream(destination, size, true) { output ->
                            (staging?.inputStream() ?: importer.open(uri)).use { input -> copyExternalStream(input, output) }
                        }
                        handle.confirmCreated()
                        succeeded += FileRef(targetDirectory.locationId, destination)
                    }
                } finally { staging?.delete() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failed += FileOpFailure(failureRef, mapped(error).code) }
        }
        FileOpOutcome(succeeded, failed)
    }

    override suspend fun shareUri(ref: FileRef): String = io {
        val handle = resolve(ref.locationId)
        val entry = requireEntry(handle.backend, ref.path)
        if (entry.isDirectory) throw FileOpException(FileOpCode.UNSUPPORTED)
        if (entry.size > MAX_SHARE_BYTES) throw FileOpException(FileOpCode.TOO_LARGE)
        val dir = File(context.cacheDir, "workspace-media-share")
        if (!dir.exists() && !dir.mkdirs()) throw FileOpException(FileOpCode.IO)
        val file = File.createTempFile("share-", "-" + entry.name.takeLast(160), dir)
        try {
            handle.backend.readStream(ref.path, MAX_SHARE_BYTES) { input -> file.outputStream().use { copyExternalStream(input, it) } }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
        } catch (error: Exception) { file.delete(); throw error }
    }

    override suspend fun preview(ref: FileRef, maxTextBytes: Int): FilePreview = io {
        val handle = resolve(ref.locationId)
        val entry = requireEntry(handle.backend, ref.path)
        if (entry.isDirectory) return@io FilePreview.Unsupported
        val type = classifyFile(entry.name, mimeFor(entry.name))
        when {
            entry.name.endsWith(".zip", true) -> FileManagerArchives.preview(context.cacheDir, handle.backend, ref.path)
            entry.name.endsWith(".pdf", true) || mimeFor(entry.name) == "application/pdf" -> FilePreview.Pdf(shareUri(ref), null)
            type == FileTypeFilter.IMAGE -> FilePreview.Image(shareUri(ref))
            type == FileTypeFilter.AUDIO -> FilePreview.Audio(shareUri(ref), null)
            type == FileTypeFilter.VIDEO -> FilePreview.Video(shareUri(ref), null)
            isTextFile(entry.name, mimeFor(entry.name)) -> {
                if (maxTextBytes < 0 || maxTextBytes > MAX_PREVIEW_BYTES) throw FileOpException(FileOpCode.TOO_LARGE)
                val bytes = handle.backend.readStream(ref.path, Long.MAX_VALUE) { input -> readPreviewBytes(input, maxTextBytes + 1) }
                FilePreview.Text(bytes.copyOf(minOf(bytes.size, maxTextBytes)).toString(Charsets.UTF_8),
                    bytes.size > maxTextBytes, entry.name.endsWith(".md", true) || entry.name.endsWith(".markdown", true))
            }
            else -> FilePreview.Unsupported
        }
    }

    override suspend fun compress(sources: List<FileRef>, targetDirectory: FileRef, archiveName: String): FileItem = io {
        val target = resolve(targetDirectory.locationId)
        val inputs = sources.distinct().map { it to resolve(it.locationId) }
        guard.run(listOf(target)) {
            requireDirectory(target.backend, targetDirectory.path)
            val path = joinFilePath(targetDirectory.path, archiveName)
            requireAbsent(target.backend, path)
            FileManagerArchives.compress(context.cacheDir, inputs, target.backend, path)
            target.confirmCreated()
            requireNotNull(stat(FileRef(targetDirectory.locationId, path)))
        }
    }

    override suspend fun extract(archive: FileRef, targetDirectory: FileRef): FileItem = io {
        val source = resolve(archive.locationId)
        val target = resolve(targetDirectory.locationId)
        guard.run(listOf(target)) {
            requireDirectory(target.backend, targetDirectory.path)
            val entry = requireEntry(source.backend, archive.path)
            val base = (if (entry.name.endsWith(".zip", true)) entry.name.dropLast(4) else entry.name).ifBlank { entry.name }
            var destination = joinFilePath(targetDirectory.path, base)
            var suffix = 2
            while (target.backend.stat(destination) != null) destination = joinFilePath(targetDirectory.path, collisionName(base, true, suffix++))
            try { FileManagerArchives.extract(context.cacheDir, source.backend, archive.path, target.backend, destination) }
            finally { withContext(NonCancellable) { target.confirmCreated() } }
            requireNotNull(stat(FileRef(targetDirectory.locationId, destination)))
        }
    }

    private suspend fun items(handle: FileBackendHandle, entries: List<ExternalFolderEntry>): List<FileItem> {
        val rows = if (handle.locationId == "kb" && handle.roomRoot != null) entries.mapNotNull {
            repository.getByMaterializedPath(handle.roomRoot, it.path)
        }.associateBy { it.materializedPath } else emptyMap()
        val taskRows = if (handle.roomRoot != null && rows.isNotEmpty())
            rows.values.map { it.uuid }.chunked(900).flatMap { tasks.getByWorkspaceFiles(handle.roomRoot, it) } else emptyList()
        val pending = pendingTargets().filter { it.workspaceRootUuid == handle.roomRoot }.map { it.fileUuid }.toSet()
        return entries.map { entry ->
            val row = rows[entry.path]
            val mime = row?.mimeType ?: mimeFor(entry.name)
            val knowledge = row?.let { knowledgeOf(it, taskRows.filter { task -> task.docId == it.uuid }, it.uuid in pending) }
            FileItem(FileRef(handle.locationId, normalizeFilePath(entry.path)), entry.name, entry.isDirectory, entry.size,
                entry.lastModified, mime, childCount = null, knowledge = knowledge)
        }
    }

    private suspend fun publishRename(root: String, result: RenameResult) {
        when (result) {
            is RenameResult.Success -> result.affectedTargets.forEach { target ->
                try { indexEvents.publish(FileIndexEvent.Changed(root, target.fileUuid, target.targetHash, target.targetEpoch)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* 已提交文件变更，待索引协调器补偿。 */ }
            }
            is RenameResult.Conflict -> throw FileOpException(FileOpCode.CONFLICT)
            RenameResult.NotFound -> throw FileOpException(FileOpCode.NOT_FOUND)
        }
    }

    private suspend fun <T> io(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { action() } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { throw mapped(error) }
    }

    companion object {
        const val MAX_DEPTH = 32
        const val MAX_SINGLE_BYTES = 256L * 1024 * 1024
        const val MAX_SHARE_BYTES = 256L * 1024 * 1024
        const val MAX_PREVIEW_BYTES = 8 * 1024 * 1024
    }
}

internal suspend fun requireEntry(backend: ExternalFolderBackend, path: String): ExternalFolderEntry =
    backend.stat(normalizeFilePath(path)) ?: throw FileOpException(FileOpCode.NOT_FOUND)
internal suspend fun requireDirectory(backend: ExternalFolderBackend, path: String) {
    if (!requireEntry(backend, path).isDirectory) throw FileOpException(FileOpCode.NOT_FOUND)
}
internal suspend fun requireAbsent(backend: ExternalFolderBackend, path: String) {
    if (backend.stat(path) != null) throw FileOpException(FileOpCode.NAME_EXISTS)
}

internal fun mimeFor(name: String): String? = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
internal fun isTextFile(name: String, mime: String?): Boolean = classifyFile(name, mime) == FileTypeFilter.CODE ||
    mime?.startsWith("text/") == true || name.substringAfterLast('.', "").lowercase() in setOf("txt", "md", "markdown", "csv", "json", "html", "htm", "log")

internal fun knowledgeOf(entry: FileEntry, tasks: List<com.promenar.infinity.data.local.db.entity.VectorizationTaskEntity>, pending: Boolean = false): FileKnowledge {
    val relevant = tasks.filter { it.type in setOf("document", "document_reference") }
    val active = relevant.firstOrNull { it.status !in setOf("failed", "partial", "completed", "interrupted") }
    val failed = relevant.firstOrNull { it.status in setOf("failed", "partial") }
    val state = when {
        entry.isDirectory -> KnowledgeIndexState.NONE
        active?.status == "pending" -> KnowledgeIndexState.QUEUED
        active != null -> KnowledgeIndexState.INDEXING
        pending -> KnowledgeIndexState.QUEUED
        failed != null -> KnowledgeIndexState.FAILED
        !com.promenar.infinity.share.core.SharedFileImporter.isParseable(entry.mimeType, entry.name) -> KnowledgeIndexState.UNSUPPORTED
        entry.vectorizedAt == null && entry.kgExtractedAt == null -> KnowledgeIndexState.NONE
        entry.vectorizedAt != null && entry.updatedAt > entry.vectorizedAt -> KnowledgeIndexState.STALE
        else -> KnowledgeIndexState.INDEXED
    }
    return FileKnowledge(state, documentId = entry.uuid.takeUnless { entry.isDirectory },
        failureReason = failed?.error.takeIf { state == KnowledgeIndexState.FAILED })
}

internal fun mapped(error: Exception): FileOpException {
    if (error is FileOpException) return error
    val code = when (error) {
        is ExternalFolderException -> when (error.code) {
            "NAME_EXISTS" -> FileOpCode.NAME_EXISTS
            "INVALID_PATH", "INVALID_ARGUMENT" -> FileOpCode.INVALID_NAME
            "NOT_FOUND" -> FileOpCode.NOT_FOUND
            "READ_ONLY" -> FileOpCode.READ_ONLY
            "UNAVAILABLE", "MOUNT_CHANGED", "PERMISSION_LOST" -> FileOpCode.PERMISSION_LOST
            "TOO_LARGE", "RECYCLE_LIMIT" -> FileOpCode.TOO_LARGE
            "NO_SPACE" -> FileOpCode.NO_SPACE
            "UNSUPPORTED" -> FileOpCode.UNSUPPORTED
            "CONFLICT", "RECOVERY_REQUIRED" -> FileOpCode.CONFLICT
            else -> FileOpCode.IO
        }
        is DirectoryFileOpException -> when (error.code.name) {
            "NAME_INVALID" -> FileOpCode.INVALID_NAME
            "NAME_EXISTS" -> FileOpCode.NAME_EXISTS
            "NOT_FOUND" -> FileOpCode.NOT_FOUND
            "READ_ONLY" -> FileOpCode.READ_ONLY
            "TOO_LARGE" -> FileOpCode.TOO_LARGE
            "WORKSPACE_UNAVAILABLE" -> FileOpCode.PERMISSION_LOST
            "TARGET_INSIDE_SOURCE", "SESSION_BUSY" -> FileOpCode.CONFLICT
            else -> FileOpCode.IO
        }
        is WorkspaceLifecycleException -> if (error.code == WorkspaceLifecycleErrorCode.TARGET_COLLISION) FileOpCode.NAME_EXISTS else FileOpCode.CONFLICT
        is com.promenar.infinity.startup.StartupIsolationException -> FileOpCode.READ_ONLY
        is SecurityException -> FileOpCode.PERMISSION_LOST
        is UnsupportedOperationException -> FileOpCode.UNSUPPORTED
        is com.promenar.infinity.data.repository.WorkspaceFileTooLargeException -> FileOpCode.TOO_LARGE
        is java.nio.file.FileAlreadyExistsException -> FileOpCode.NAME_EXISTS
        is com.promenar.infinity.data.session.SessionDeletingException -> FileOpCode.CONFLICT
        is IOException -> if (error.message?.contains("ENOSPC", true) == true) FileOpCode.NO_SPACE else FileOpCode.IO
        else -> FileOpCode.IO
    }
    return FileOpException(code, cause = error)
}

internal suspend fun copyTree(source: ExternalFolderBackend, from: String, target: ExternalFolderBackend, to: String) {
    data class Created(val path: String, val receipt: ExternalFolderEntry, val hash: String?)
    data class Snapshot(val path: String, val entry: ExternalFolderEntry, val hash: String?, val children: Set<String>?)
    val created = mutableListOf<Created>()
    val snapshot = mutableListOf<Snapshot>()
    suspend fun scan(path: String, depth: Int) {
        currentCoroutineContext().ensureActive()
        if (depth > DefaultFileOperations.MAX_DEPTH) throw FileOpException(FileOpCode.TOO_LARGE)
        val entry = requireEntry(source, path)
        val children = if (entry.isDirectory) source.list(path) else emptyList()
        if (children.map { it.name }.toSet().size != children.size) throw FileOpException(FileOpCode.CONFLICT)
        val digest = if (entry.isDirectory) null else source.streamHash(path)
        snapshot += Snapshot(path, entry, digest, if (entry.isDirectory) children.map { it.name }.toSet() else null)
        for (child in children) scan(joinFilePath(path, child.name), depth + 1)
    }
    source.validateTree(from)
    scan(from, 0)
    try {
        for (node in snapshot) {
            currentCoroutineContext().ensureActive()
            val destination = to.trimEnd('/') + node.path.removePrefix(from)
            if (node.entry.isDirectory) {
                val receipt = target.createDirectory(destination)
                created += Created(destination, receipt, null)
            } else {
                source.readStream(node.path, Long.MAX_VALUE) { input ->
                    target.writeStream(destination, node.entry.size, true) { output ->
                        if (copyExternalStream(input, output) != node.hash) throw FileOpException(FileOpCode.CONFLICT)
                    }.also { receipt -> created += Created(destination, receipt, node.hash) }
                }
                if (target.streamHash(destination) != node.hash) throw FileOpException(FileOpCode.CONFLICT)
            }
        }
        for (node in created) {
            currentCoroutineContext().ensureActive()
            val current = requireEntry(target, node.path)
            if (node.receipt.identity != null && current.identity != node.receipt.identity) throw FileOpException(FileOpCode.CONFLICT)
            if (!current.isDirectory && target.streamHash(node.path) != node.hash) throw FileOpException(FileOpCode.CONFLICT)
            if (current.isDirectory) {
                val expected = created.filter { parentFilePath(it.path) == node.path }.map { it.path.substringAfterLast('/') }.toSet()
                if (target.list(node.path).map { it.name }.toSet() != expected) throw FileOpException(FileOpCode.CONFLICT)
            }
        }
        source.validateTree(from)
        for (node in snapshot) {
            currentCoroutineContext().ensureActive()
            val current = requireEntry(source, node.path)
            if (current.isDirectory != node.entry.isDirectory || current.identity != node.entry.identity ||
                (!current.isDirectory && (current.size != node.entry.size || source.streamHash(node.path) != node.hash)) ||
                (current.isDirectory && source.list(node.path).map { it.name }.toSet() != node.children)) {
                throw FileOpException(FileOpCode.CONFLICT)
            }
        }
    } catch (failure: Exception) {
        withContext(NonCancellable) {
            try {
                for (node in created.asReversed()) {
                    val current = target.stat(node.path) ?: continue
                    if (node.receipt.identity != null && current.identity != node.receipt.identity) throw FileOpException(FileOpCode.CONFLICT)
                    if (current.isDirectory && target.list(node.path).isNotEmpty()) throw FileOpException(FileOpCode.CONFLICT)
                    if (!current.isDirectory && target.streamHash(node.path) != node.hash) throw FileOpException(FileOpCode.CONFLICT)
                    target.deleteForTransfer(node.path, node.receipt, node.hash)
                }
            } catch (cleanup: Exception) {
                val recovery = FileOpException(FileOpCode.CONFLICT, "目标清理未完成，请检查已创建的文件", failure)
                recovery.addSuppressed(cleanup)
                if (failure is kotlinx.coroutines.CancellationException) failure.addSuppressed(recovery) else throw recovery
            }
        }
        throw failure
    }
}

internal fun readPreviewBytes(input: java.io.InputStream, limit: Int): ByteArray {
    val result = java.io.ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(minOf(limit, 8192).coerceAtLeast(1))
    while (result.size() < limit) {
        val count = input.read(buffer, 0, minOf(buffer.size, limit - result.size()))
        if (count < 0) break
        if (count == 0) continue
        result.write(buffer, 0, count)
    }
    return result.toByteArray()
}

private suspend fun copyImportStream(input: java.io.InputStream, output: java.io.OutputStream) {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        if (count.toLong() > DefaultFileOperations.MAX_SINGLE_BYTES - total) throw FileOpException(FileOpCode.TOO_LARGE)
        output.write(buffer, 0, count)
        total += count
    }
}
