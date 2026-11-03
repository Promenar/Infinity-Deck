package com.promenar.infinity.data.document

import com.promenar.infinity.data.branch.BranchWorktreeWorkspaceResolver
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.model.MessageDocumentAttachment
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.WorkspaceDocumentReference
import com.promenar.infinity.data.model.isBoundTo
import com.promenar.infinity.data.rag.DocumentReferenceExtractor
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.repository.WorkspaceLifecycleErrorCode
import com.promenar.infinity.data.repository.WorkspaceLifecycleException
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.data.storage.AndroidRealPathFileSystem
import com.promenar.infinity.data.storage.RealPathExternalFolderBackend
import com.promenar.infinity.data.storage.RealPathFileSystem
import com.promenar.infinity.data.workspace.WorkspaceFileDestinationBinding
import com.promenar.infinity.data.workspace.WorkspaceDefaultFileDestination
import com.promenar.infinity.data.workspace.WorkspaceFileTarget
import com.promenar.infinity.data.workspace.WorkspaceFileTargetResolver
import com.promenar.infinity.data.workspace.workspaceFileRelativePath
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalFolderPaths
import com.promenar.infinity.domain.storage.ExternalMountOperationBindings
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import com.promenar.infinity.startup.StartupIsolationException
import com.promenar.infinity.startup.StartupWriteBarrier
import java.nio.file.FileAlreadyExistsException
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

interface DocumentWorkspaceSaver {
    suspend fun save(
        sessionId: String,
        document: MessageDocumentAttachment,
        relativeDirectory: String? = null,
    ): DocumentWorkspaceSaveResult

    suspend fun saveBytes(
        sessionId: String,
        name: String,
        mimeType: String,
        bytes: ByteArray,
        lineCount: Int = 0,
        estimatedTokens: Int = 0,
        relativeDirectory: String? = null,
    ): DocumentWorkspaceBytesSaveResult = DocumentWorkspaceBytesSaveResult.Failure(DocumentWorkspaceSaveFailure.SaveFailed)

    suspend fun isReusable(
        session: Session,
        reference: WorkspaceDocumentReference,
        expectedSha256: String,
        expectedSizeBytes: Long? = null,
    ): Boolean = false
}

sealed interface DocumentWorkspaceSaveResult {
    data class Success(val attachment: MessageDocumentAttachment) : DocumentWorkspaceSaveResult
    data class Failure(val reason: DocumentWorkspaceSaveFailure) : DocumentWorkspaceSaveResult
}

sealed interface DocumentWorkspaceBytesSaveResult {
    data class Success(val reference: WorkspaceDocumentReference) : DocumentWorkspaceBytesSaveResult
    data class Failure(val reason: DocumentWorkspaceSaveFailure) : DocumentWorkspaceBytesSaveResult
}

enum class DocumentWorkspaceSaveFailure {
    InvalidPath,
    WorkspaceUnavailable,
    ReadOnly,
    NameConflict,
    AlreadySaved,
    DocumentNotFound,
    DraftChanged,
    SaveFailed,
}

/** 以新建语义转存正文；保存期间固定主工作区与运行模式，不覆盖同名文件。 */
internal class WorkspaceDocumentSaver(
    private val targets: WorkspaceFileTargetResolver,
    private val workspace: IWorkspaceRepository,
    private val mounts: ExternalWorkspaceAccess,
    private val indexEvents: FileIndexEventSink,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val sessionFor: suspend (String) -> Session? = { null },
    private val fileSystem: RealPathFileSystem = AndroidRealPathFileSystem,
) : DocumentWorkspaceSaver {
    override suspend fun save(
        sessionId: String,
        document: MessageDocumentAttachment,
        relativeDirectory: String?,
    ): DocumentWorkspaceSaveResult = withContext(Dispatchers.IO) {
        if (document.workspaceReference != null) {
            return@withContext DocumentWorkspaceSaveResult.Failure(DocumentWorkspaceSaveFailure.AlreadySaved)
        }
        when (val saved = saveBytes(
            sessionId, document.name, document.mimeType, document.content.toByteArray(Charsets.UTF_8),
            lineCount = if (document.content.isEmpty()) 0 else document.content.lineSequence().count(),
            estimatedTokens = document.estimatedTokens, relativeDirectory = relativeDirectory,
        )) {
            is DocumentWorkspaceBytesSaveResult.Success -> DocumentWorkspaceSaveResult.Success(
                document.copy(content = "", workspaceReference = saved.reference, inlineInCurrentTurn = false),
            )
            is DocumentWorkspaceBytesSaveResult.Failure -> DocumentWorkspaceSaveResult.Failure(saved.reason)
        }
    }

    override suspend fun saveBytes(
        sessionId: String,
        name: String,
        mimeType: String,
        bytes: ByteArray,
        lineCount: Int,
        estimatedTokens: Int,
        relativeDirectory: String?,
    ): DocumentWorkspaceBytesSaveResult = withContext(Dispatchers.IO) {
        try {
            AttachmentWorkspacePaths.name(name)
            val directory = AttachmentWorkspacePaths.directory(relativeDirectory ?: "attachments/${LocalDate.now(clock)}")
            AttachmentWorkspacePaths.namespace("$directory/$name")
            val destination = targets.primaryDestination(sessionId)
                ?: throw ExternalFolderException("UNAVAILABLE", "会话工作区不可用")
            val binding = bindSource(sessionId, destination)
            // 固定无歧义的解析锚点，文件相对路径交给同一个目标后端处理。
            val target = targets.resolve(sessionId, TARGET_ANCHOR, writing = true, destination)
            withContext(ExternalMountOperationBindings(target.mountIds)) saving@ {
                checkCurrent(target, binding)
                val sha256 = workspaceAttachmentSha256(bytes)
                for (sequence in 1..MAX_NAME_ATTEMPTS) {
                    val path = "$directory/${numberedName(name, sequence)}"
                    checkCurrent(target, binding)
                    if (entry(target, path) != null) continue
                    ensureParents(target, binding, path)
                    checkCurrent(target, binding)
                    val presented = targets.presenter(sessionId).present(destination.destination, path, privatePrimary = false)
                    checkCurrent(target, binding)
                    try {
                        create(target, binding, path, bytes, mimeType)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        if (isNameConflict(failure)) continue
                        throw failure
                    }
                    return@saving DocumentWorkspaceBytesSaveResult.Success(
                        WorkspaceDocumentReference(
                            path = presented,
                            lineCount = lineCount,
                            estimatedTokens = estimatedTokens,
                            sourceSessionId = sessionId,
                            workspaceId = destination.workspaceId,
                            isPrivate = destination.destination == WorkspaceDefaultFileDestination.SESSION_PRIVATE,
                            linuxNamespace = target.linuxNamespace,
                            worktreeDirectory = binding.worktreeDirectory,
                            sha256 = sha256,
                        ),
                    )
                }
                DocumentWorkspaceBytesSaveResult.Failure(DocumentWorkspaceSaveFailure.NameConflict)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            DocumentWorkspaceBytesSaveResult.Failure(failureReason(failure))
        }
    }

    override suspend fun isReusable(
        session: Session,
        reference: WorkspaceDocumentReference,
        expectedSha256: String,
        expectedSizeBytes: Long?,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if ((reference.sha256 != null && reference.sha256 != expectedSha256) || !reference.isBoundTo(session)) return@withContext false
            val destination = targets.primaryDestination(session.id) ?: return@withContext false
            if (reference.workspaceId != destination.workspaceId ||
                reference.isPrivate != (destination.destination == WorkspaceDefaultFileDestination.SESSION_PRIVATE)
            ) return@withContext false
            val binding = bindSource(session.id, destination)
            if (reference.worktreeDirectory != binding.worktreeDirectory) return@withContext false
            val path = workspaceFileRelativePath(reference.path)
            AttachmentWorkspacePaths.namespace(path)
            val target = targets.resolve(session.id, TARGET_ANCHOR, writing = false, destination)
            if (target.linuxNamespace != reference.linuxNamespace) return@withContext false
            withContext(ExternalMountOperationBindings(target.mountIds)) checking@ {
                checkCurrent(target, binding)
                val file = entry(target, path) ?: return@checking false
                if (file.isDirectory || file.sizeBytes < 0 || expectedSizeBytes != null && file.sizeBytes != expectedSizeBytes) return@checking false
                val limit = expectedSizeBytes ?: file.sizeBytes
                val backend = target.directoryBackend ?: target.mount?.let { mounts.backend(target.sessionId, target.mountIds) }
                    ?: RealPathExternalFolderBackend(requireNotNull(target.directory), emptySet(), { true }, fileSystem)
                val backendPath = if (target.mount != null) target.accessPath(path) else path
                val hash = backend.readStream(backendPath, limit) { input ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        if (total > limit - count) throw ExternalFolderException("TOO_LARGE", "附件字节数已变化")
                        total += count
                        digest.update(buffer, 0, count)
                    }
                    if (total != limit) return@readStream null
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
                checkCurrent(target, binding)
                hash == expectedSha256
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private data class SourceBinding(val destination: WorkspaceFileDestinationBinding, val worktreeDirectory: String?)

    private suspend fun bindSource(sessionId: String, destination: WorkspaceFileDestinationBinding): SourceBinding {
        val worktree = if (destination.workspaceId?.startsWith(BranchWorktreeWorkspaceResolver.ID_PREFIX) == true) {
            val session = sessionFor(sessionId)
            if (destination.workspaceId != BranchWorktreeWorkspaceResolver.ID_PREFIX + sessionId || session == null || session.id != sessionId || session.worktreeDirectory.isNullOrBlank()) {
                throw ExternalFolderException("UNAVAILABLE", "worktree 来源尚不可用")
            }
            session.worktreeDirectory
        } else null
        return SourceBinding(destination, worktree)
    }

    private suspend fun checkCurrent(target: WorkspaceFileTarget, binding: SourceBinding) {
        // 显式绑定可用于读取历史工作区，此处另外核验会话当前选择，避免保存到已切走的目录。
        if (targets.primaryDestination(target.sessionId) != binding.destination) {
            throw ExternalFolderException("UNAVAILABLE", "会话工作区已变化")
        }
        if (binding.worktreeDirectory != null) {
            val session = sessionFor(target.sessionId)
            if (session == null || session.id != target.sessionId || session.worktreeDirectory != binding.worktreeDirectory) {
                throw ExternalFolderException("UNAVAILABLE", "worktree 来源已变化")
            }
        }
        targets.checkCurrent(target, TARGET_ANCHOR)
    }

    private suspend fun entry(target: WorkspaceFileTarget, path: String): FileEntry? = when {
        target.directoryBackend != null -> target.directoryBackend.stat(path)?.let {
            FileEntry(
                uuid = it.identity ?: it.path, parentUuid = null, name = it.name, hash = it.sha256.orEmpty(),
                sizeBytes = it.size, isDirectory = it.isDirectory, physicalRootPath = "", materializedPath = path,
                createdAt = it.lastModified, updatedAt = it.lastModified,
            )
        }
        target.mount != null -> mounts.entry(target.sessionId, target.accessPath(path))
        else -> workspace.getByMaterializedPath(requireNotNull(target.root).uuid, path)
    }

    private suspend fun ensureParents(
        target: WorkspaceFileTarget,
        binding: SourceBinding,
        path: String,
    ) {
        var parent = "/"
        for (component in path.trimStart('/').split('/').dropLast(1)) {
            val previous = parent
            parent = if (parent == "/") "/$component" else "$parent/$component"
            checkCurrent(target, binding)
            val existing = entry(target, parent)
            if (existing != null) {
                if (!existing.isDirectory) throw ExternalFolderException("INVALID_PATH", "文档父路径包含文件")
                continue
            }
            checkCurrent(target, binding)
            try {
                when {
                    target.directoryBackend != null -> target.directoryBackend.createDirectory(parent)
                    target.mount != null -> mounts.createDirectory(target.sessionId, target.accessPath(parent))
                    else -> {
                        val root = requireNotNull(target.root)
                        val parentEntry = entry(target, previous)
                            ?: throw ExternalFolderException("UNAVAILABLE", "文档父目录不可用")
                        workspace.createDirectoryInWorkspace(root.uuid, UUID.randomUUID().toString(), component, parentEntry.uuid, parent)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                checkCurrent(target, binding)
                if (entry(target, parent)?.isDirectory != true) throw failure
            }
        }
    }

    private suspend fun create(
        target: WorkspaceFileTarget,
        binding: SourceBinding,
        path: String,
        bytes: ByteArray,
        mimeType: String,
    ) {
        StartupWriteBarrier.requireWritable(sessionId = target.sessionId, rootId = target.root?.uuid, path = target.directory?.canonicalPath)
        checkCurrent(target, binding)
        val backend = target.directoryBackend ?: target.mount?.let { mounts.backend(target.sessionId, target.mountIds) }
        if (backend != null) {
            val backendPath = if (target.directoryBackend != null) path else target.accessPath(path)
            val created = backend.write(backendPath, bytes, expectedSha256 = null, createOnly = true)
            try {
                checkCurrent(target, binding)
            } catch (failure: Exception) {
                withContext(NonCancellable) {
                    try {
                        val hash = workspaceAttachmentSha256(bytes)
                        backend.deleteForTransfer(backendPath, created, hash)
                    } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                }
                throw failure
            }
        } else {
            createPrivate(target, binding, path, bytes, mimeType)
        }
    }

    private suspend fun createPrivate(
        target: WorkspaceFileTarget,
        binding: SourceBinding,
        path: String,
        bytes: ByteArray,
        mimeType: String,
    ) {
        val root = requireNotNull(target.root)
        val parent = entry(target, path.substringBeforeLast('/'))
            ?: throw ExternalFolderException("UNAVAILABLE", "文档父目录不可用")
        var created: FileEntry? = null
        try {
            created = workspace.createFileInWorkspaceStreaming(
                root.uuid, UUID.randomUUID().toString(), path.substringAfterLast('/'), mimeType, parent.uuid,
                path, bytes.size.toLong().coerceAtLeast(1),
            ) { it.write(bytes) }
            checkCurrent(target, binding)
            if (DocumentReferenceExtractor.supports(created)) {
                indexEvents.publish(FileIndexEvent.Changed(root.uuid, created.uuid, created.hash, created.updatedAt.coerceAtLeast(1)))
            }
            checkCurrent(target, binding)
            workspace.confirmCreatedEntry(root.uuid, created.uuid)
        } catch (failure: Exception) {
            created?.let { createdEntry ->
                withContext(NonCancellable) {
                    try { workspace.rollbackCreatedEntry(root.uuid, createdEntry.uuid) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                    try { indexEvents.publish(FileIndexEvent.Deleted(root.uuid, createdEntry.uuid)) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                }
            }
            if (failure is CancellationException) throw failure
            if (created == null && failure is IllegalStateException && entry(target, path) != null) {
                throw ExternalFolderException("NAME_EXISTS", "同名文档已存在")
            }
            throw failure
        }
    }

    private fun numberedName(name: String, sequence: Int): String {
        if (sequence == 1) return name
        val extension = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        return name.take(extension) + "-$sequence" + name.substring(extension)
    }

    private fun isNameConflict(failure: Exception): Boolean = when (failure) {
        is ExternalFolderException -> failure.code == "CONFLICT" || failure.code == "NAME_EXISTS"
        is WorkspaceLifecycleException -> failure.code == WorkspaceLifecycleErrorCode.TARGET_COLLISION
        is FileAlreadyExistsException -> true
        else -> false
    }

    private fun failureReason(failure: Exception): DocumentWorkspaceSaveFailure = when (failure) {
        is ExternalFolderException -> when (failure.code) {
            "INVALID_PATH" -> DocumentWorkspaceSaveFailure.InvalidPath
            "UNAVAILABLE", "NOT_FOUND" -> DocumentWorkspaceSaveFailure.WorkspaceUnavailable
            "READ_ONLY", "PERMISSION_DENIED" -> DocumentWorkspaceSaveFailure.ReadOnly
            else -> DocumentWorkspaceSaveFailure.SaveFailed
        }
        is SecurityException, is StartupIsolationException -> DocumentWorkspaceSaveFailure.ReadOnly
        else -> DocumentWorkspaceSaveFailure.SaveFailed
    }

    companion object {
        private const val TARGET_ANCHOR = "/attachments"
        private const val MAX_NAME_ATTEMPTS = 1_000
    }
}

internal object AttachmentWorkspacePaths {
    fun directory(path: String): String {
        if (path.startsWith('/') || path.isBlank()) invalid()
        path.split('/').forEach(::name)
        return ExternalFolderPaths.normalize(path).also(::namespace)
    }

    fun namespace(path: String) {
        if (workspaceFileRelativePath(path) != path || ExternalMountPaths.isMountedPath(path)) invalid()
    }

    fun name(name: String) {
        ExternalFolderPaths.validateName(name)
        if (':' in name || name.any(Char::isISOControl) || name == ".l2s" || name.startsWith(".l2s.") || name.startsWith(".proot.l2s.")) invalid()
    }

    private fun invalid(): Nothing = throw ExternalFolderException("INVALID_PATH", "附件保存路径无效")
}

internal fun workspaceAttachmentSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
