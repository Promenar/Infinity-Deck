package com.promenar.infinity.data.media

import android.content.Context
import androidx.core.net.toUri
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.rag.DocumentReferenceExtractor
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.data.storage.*
import com.promenar.infinity.data.workspace.WorkspaceFileTarget as Target
import com.promenar.infinity.data.workspace.WorkspaceFileTargetResolver
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.domain.media.*
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

internal class DefaultWorkspaceMedia(
    private val context: Context,
    private val sessions: ISessionRepository,
    private val workspace: IWorkspaceRepository,
    private val mounts: ExternalWorkspaceAccess,
    private val sessionWorkspace: suspend (String) -> SessionWorkspace,
    private val indexEvents: FileIndexEventSink,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val fileSystem: RealPathFileSystem = AndroidRealPathFileSystem,
    private val userSkillsDirectory: File = File(context.filesDir, "agent_skills").canonicalFile,
    private val pathPresenter: com.promenar.infinity.data.workspace.WorkspaceFilePathPresenter = com.promenar.infinity.data.workspace.BuiltinWorkspaceFilePathPresenter,
    private val modeFor: (suspend (String) -> com.promenar.infinity.domain.model.SessionRunMode?)? = null,
    private val linuxFiles: com.promenar.infinity.domain.linux.LinuxFileSystem? = null,
    directoryBackend: ((SessionWorkspace.Directory) -> ExternalFolderBackend)? = null,
) : WorkspaceMediaResolver, WorkspaceMediaStore {
    private val targets = WorkspaceFileTargetResolver(sessions, workspace, mounts, sessionWorkspace, userSkillsDirectory, linuxDirectoryBackend = directoryBackend, pathPresenter = pathPresenter, modeFor = modeFor, linuxFiles = linuxFiles)
    private val local = MediaLocalAccess(fileSystem)
    private val metadata = MediaMetadataReader(context)
    // 弱键不延长卡片生命周期；open 必须重新核验会话和挂载身份，不能接受任意本机文件。
    private val expiredBindings = ReferenceQueue<MediaResolution.Available>()
    private val bindings = HashMap<ResolutionKey, Target>()

    override suspend fun resolve(sessionId: String, path: String): MediaResolution = withContext(Dispatchers.IO) {
        var normalized = path
        try {
            normalized = normalize(sessionId, path)
            val target = target(sessionId, normalized, writing = false)
            withContext(ExternalMountOperationBindings(target.mountIds)) {
                val entry = entry(target, normalized) ?: return@withContext MediaResolution.Unavailable(normalized, MediaUnavailableReason.NOT_FOUND)
                if (entry.isDirectory) return@withContext MediaResolution.Unavailable(normalized, MediaUnavailableReason.IS_DIRECTORY)
                val source = source(target, normalized)
                val header = openSource(target, normalized, source).use { readHeader(it) }
                val type = MediaTypeSniffer.sniff(header, entry.name)
                val info = metadata.read(type.kind, source) { openSource(target, normalized, source) }
                MediaResolution.Available(
                    MediaRef(normalized, type.mimeType, type.kind, entry.sizeBytes, entry.hash.takeIf(String::isNotBlank), info.width, info.height, info.durationMs), source,
                ).also { binding(it, target) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            MediaResolution.Unavailable(normalized, reason(failure))
        }
    }

    override suspend fun open(resolution: MediaResolution.Available): InputStream = withContext(Dispatchers.IO) {
        val bound = binding(resolution) ?: fail("INVALID_PATH", "媒体引用未经过当前解析器核验，请重新解析路径")
        try {
            val current = target(bound.sessionId, resolution.ref.path, writing = false)
            if (current.identity != bound.identity) fail("WORKSPACE_UNAVAILABLE", "会话工作区或挂载身份已变化，请重新解析媒体")
            withContext(ExternalMountOperationBindings(current.mountIds)) {
                val entry = entry(current, resolution.ref.path) ?: fail("IO", "媒体文件已不存在")
                if (entry.isDirectory) fail("IO", "媒体路径当前指向目录")
                val source = source(current, resolution.ref.path)
                if (source != resolution.source) fail("WORKSPACE_UNAVAILABLE", "媒体来源已变化，请重新解析路径")
                openSource(current, resolution.ref.path, source)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw storageFailure(failure)
        }
    }

    override suspend fun canSave(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val destination = targets.defaultDestination(sessionId)
            val path = targets.present(sessionId, destination, "/media")
            val target = targets.resolve(sessionId, path, writing = true, destination)
            target.directoryBackend != null || target.directory?.canWrite() == true || target.mount != null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    override suspend fun save(
        sessionId: String,
        payload: MediaPayload,
        origin: MediaOrigin,
        mimeTypeHint: String?,
        suggestedName: String?,
        targetPath: String?,
    ): MediaRef = withContext(Dispatchers.IO) {
        locks[sessionId.hashCode() and (locks.size - 1)].withLock {
            try {
                val explicit = targetPath?.takeUnless(String::isEmpty)?.let { normalize(sessionId, it) }
                val destination = if (explicit == null) targets.defaultDestination(sessionId) else null
                val initialPath = explicit ?: targets.present(sessionId, requireNotNull(destination), "/media")
                protect(sessionId, initialPath)
                val target = targets.resolve(sessionId, initialPath, writing = true, destination)
                withContext(ExternalMountOperationBindings(target.mountIds)) {
                    if (explicit != null && exists(target, explicit)) fail("EXISTS", "目标文件或目录已存在，媒体保存不会覆盖")
                    val temporary = File.createTempFile("workspace-media-", ".tmp", context.cacheDir.canonicalFile)
                    try {
                        val staged = stage(payload, temporary)
                        val type = MediaTypeSniffer.forStorage(staged.header, mimeTypeHint, suggestedName)
                        val info = metadata.read(type.kind, MediaSource.LocalFile(temporary)) { local.open(temporary.parentFile!!, "/${temporary.name}") }
                        val now = LocalDateTime.now(clock)
                        val extension = MediaTypeSniffer.extension(type.mimeType, suggestedName)
                        val base = "$initialPath/${now.toLocalDate()}/${origin.prefix}-${now.format(TIME)}-${staged.sha256.take(6)}"
                        var suffix = 1
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val path = explicit ?: "$base${if (suffix == 1) "" else "-$suffix"}.$extension"
                            checkCurrent(target, path)
                            if (exists(target, path)) {
                                if (explicit != null) fail("EXISTS", "目标文件或目录已存在，媒体保存不会覆盖")
                                suffix++
                                continue
                            }
                            ensureParents(target, path)
                            try {
                                create(target, path, temporary, type.mimeType)
                            } catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                if (explicit == null && (failure as? WorkspaceMediaException)?.code == "EXISTS") { suffix++; continue }
                                throw failure
                            }
                            return@withContext MediaRef(path, type.mimeType, type.kind, staged.size, staged.sha256, info.width, info.height, info.durationMs)
                        }
                        @Suppress("UNREACHABLE_CODE")
                        error("媒体保存未返回")
                    } finally {
                        Files.deleteIfExists(temporary.toPath())
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw storageFailure(failure)
            }
        }
    }

    private class ResolutionKey(resolution: MediaResolution.Available, queue: ReferenceQueue<MediaResolution.Available>? = null) :
        WeakReference<MediaResolution.Available>(resolution, queue) {
        private val identityHash = System.identityHashCode(resolution)
        override fun hashCode() = identityHash
        override fun equals(other: Any?): Boolean = this === other || other is ResolutionKey && get()?.let { it === other.get() } == true
    }

    private fun binding(resolution: MediaResolution.Available, target: Target? = null): Target? = synchronized(bindings) {
        while (true) { val expired = expiredBindings.poll() ?: break; bindings.remove(expired) }
        if (target != null) {
            bindings[ResolutionKey(resolution, expiredBindings)] = target
            target
        } else bindings[ResolutionKey(resolution)]
    }

    private suspend fun target(sessionId: String, path: String, writing: Boolean): Target =
        targets.resolve(sessionId, path, writing)

    private suspend fun checkCurrent(bound: Target, path: String) {
        targets.checkCurrent(bound, path)
    }

    private suspend fun entry(target: Target, path: String): FileEntry? = when {
        target.directoryBackend != null -> target.directoryBackend.stat(target.relative(path))?.let {
            FileEntry(it.identity ?: it.path, "", null, it.name, it.sha256.orEmpty(), sizeBytes = it.size, isDirectory = it.isDirectory,
                physicalRootPath = "", materializedPath = path, createdAt = it.lastModified, updatedAt = it.lastModified)
        }
        path == "/mnt" || target.mount != null -> mounts.entry(target.sessionId, target.accessPath(path))
        else -> workspace.getByMaterializedPath(target.root!!.uuid, target.materialized(path))
    }

    private suspend fun exists(target: Target, path: String): Boolean = entry(target, path) != null ||
        target.root != null && Files.exists(local.file(target.directory!!, target.relative(path)).toPath(), NOFOLLOW_LINKS)

    private suspend fun source(target: Target, path: String): MediaSource = if (target.directory != null) {
        MediaSource.LocalFile(local.file(target.directory, target.relative(path)))
    } else {
        val mount = target.mount ?: fail("INVALID_PATH", "媒体路径没有可读来源")
        val uri = SafExternalFolderBackend(context.contentResolver, mount.sourceId.toUri()).documentUri(target.relative(path))
            ?: fail("IO", "媒体文件已不存在")
        MediaSource.ContentUri(uri.toString())
    }

    private fun openSource(target: Target, path: String, source: MediaSource): InputStream = when (source) {
        is MediaSource.LocalFile -> local.open(target.directory!!, target.relative(path))
        is MediaSource.ContentUri -> context.contentResolver.openInputStream(source.uri.toUri())
            ?: fail("IO", "无法打开媒体文件，请检查目录与授权")
    }

    private suspend fun ensureParents(target: Target, path: String) {
        var parent = target.virtualRoot
        for (component in target.relative(path).trim('/').split('/').dropLast(1)) {
            parent = if (parent == "/") "/$component" else "$parent/$component"
            val existing = entry(target, parent)
            if (existing != null) {
                if (!existing.isDirectory) fail("INVALID_PATH", "媒体父路径包含文件，无法创建目录")
                continue
            }
            try {
                if (target.directoryBackend != null) target.directoryBackend.createDirectory(target.relative(parent))
                else if (target.mount != null) mounts.createDirectory(target.sessionId, target.accessPath(parent))
                else {
                    val parentEntry = entry(target, parent.substringBeforeLast('/').ifEmpty { "/" }) ?: fail("IO", "媒体父目录不可用")
                    workspace.createDirectoryInWorkspace(target.root!!.uuid, UUID.randomUUID().toString(), component, parentEntry.uuid, target.materialized(parent))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (entry(target, parent)?.isDirectory != true) throw failure
            }
        }
    }

    private suspend fun create(target: Target, path: String, file: File, mime: String) {
        var created: FileEntry? = null
        try {
            checkCurrent(target, path)
            if (target.directoryBackend != null) {
                target.directoryBackend.write(target.relative(path), file.readBytes(), expectedSha256 = null, createOnly = true)
            } else if (target.mount != null) {
                // 外部后端只接受整块字节；暂存阶段已用统一上限约束大小。
                mounts.write(target.sessionId, target.accessPath(path), file.readBytes(), expectedSha256 = null, createOnly = true)
            } else {
                val root = target.root ?: fail("INVALID_PATH", "不能向挂载列表目录保存媒体")
                val parent = entry(target, path.substringBeforeLast('/').ifEmpty { "/" }) ?: fail("IO", "媒体父目录不存在")
                val coroutine = currentCoroutineContext()
                created = workspace.createFileInWorkspaceStreaming(root.uuid, UUID.randomUUID().toString(), path.substringAfterLast('/'), mime, parent.uuid, target.materialized(path), MAX_WORKSPACE_MEDIA_BYTES) { output ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            coroutine.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                // 与其它写入路径一致只为可索引文档发布事件；向量化队列会直接拒绝图片、音视频 MIME。
                if (DocumentReferenceExtractor.supports(created)) {
                    indexEvents.publish(FileIndexEvent.Changed(root.uuid, created.uuid, created.hash, created.updatedAt.coerceAtLeast(1)))
                }
                workspace.confirmCreatedEntry(root.uuid, created.uuid)
            }
        } catch (failure: Exception) {
            created?.let { entry ->
                withContext(NonCancellable) {
                    try { workspace.rollbackCreatedEntry(entry.workspaceRootUuid, entry.uuid) }
                    catch (rollback: Exception) { failure.addSuppressed(rollback) }
                    try { indexEvents.publish(FileIndexEvent.Deleted(entry.workspaceRootUuid, entry.uuid)) }
                    catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                }
            }
            if (failure is CancellationException) throw failure
            if (failure is ExternalFolderException && failure.code == "CONFLICT" ||
                failure is java.nio.file.FileAlreadyExistsException || created == null && exists(target, path)) {
                throw WorkspaceMediaException("EXISTS", "目标文件或目录已存在，媒体保存不会覆盖").apply { initCause(failure) }
            }
            throw failure
        }
    }

    private data class Staged(val header: ByteArray, val sha256: String, val size: Long)

    private suspend fun stage(payload: MediaPayload, file: File): Staged {
        if (payload is MediaPayload.Bytes && payload.bytes.size.toLong() > MAX_WORKSPACE_MEDIA_BYTES) fail("TOO_LARGE", "单个媒体不能超过 256 MiB")
        val input = when (payload) { is MediaPayload.Bytes -> payload.bytes.inputStream(); is MediaPayload.Stream -> payload.open() }
        val digest = MessageDigest.getInstance("SHA-256")
        val header = ByteArray(HEADER_SIZE)
        var headerSize = 0
        var size = 0L
        input.use { source -> file.outputStream().use { output ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                var count = source.read(buffer, 0, minOf(buffer.size.toLong(), MAX_WORKSPACE_MEDIA_BYTES - size + 1).toInt())
                if (count < 0) break
                if (count == 0) { val byte = source.read(); if (byte < 0) break; buffer[0] = byte.toByte(); count = 1 }
                size += count
                if (size > MAX_WORKSPACE_MEDIA_BYTES) fail("TOO_LARGE", "单个媒体不能超过 256 MiB")
                val copied = minOf(count, header.size - headerSize)
                buffer.copyInto(header, headerSize, 0, copied)
                headerSize += copied
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
        } }
        return Staged(header.copyOf(headerSize), digest.digest().joinToString("") { "%02x".format(it) }, size)
    }

    private suspend fun readHeader(input: InputStream): ByteArray {
        val bytes = ByteArray(HEADER_SIZE)
        var count = 0
        while (count < bytes.size) {
            currentCoroutineContext().ensureActive()
            val read = input.read(bytes, count, bytes.size - count)
            if (read < 0) break
            if (read == 0) { val byte = input.read(); if (byte < 0) break; bytes[count++] = byte.toByte() } else count += read
        }
        return bytes.copyOf(count)
    }

    private suspend fun normalize(sessionId: String, path: String): String {
        if (SCHEME.containsMatchIn(path.trimStart())) fail("INVALID_PATH", "媒体只接受工作区虚拟路径，不接受 URI")
        return if (targets.presenter(sessionId) == com.promenar.infinity.data.workspace.LinuxWorkspaceFilePathPresenter) {
            com.promenar.infinity.data.workspace.LinuxSessionPaths.normalize(path, targets.linuxWorkingDirectory(sessionId))
        } else ExternalFolderPaths.normalize(path).also { ExternalMountPaths.parse(it) }
    }

    private suspend fun protect(sessionId: String, path: String) {
        val selectedRelative = if (targets.presenter(sessionId) == com.promenar.infinity.data.workspace.LinuxWorkspaceFilePathPresenter) {
            val cwd = targets.linuxWorkingDirectory(sessionId)
            path.takeIf { it == cwd || it.startsWith("$cwd/") }?.removePrefix(cwd)
        } else null
        val relative = (targets.presenter(sessionId).privateRelativePath(path) ?: selectedRelative ?: if (path == "/mnt/skills" || path.startsWith("/mnt/skills/")) "/skills" else ExternalMountPaths.parse(path)?.second ?: path).lowercase(Locale.ROOT)
        if (path == "/mnt/skills" || path.startsWith("/mnt/skills/") || path == "/skills" || path.startsWith("/skills/") || relative == "/.agents/skills" || relative.startsWith("/.agents/skills/")) {
            fail("PROTECTED_PATH", "技能目录写入需要逐次审批，媒体存储不能直接写入")
        }
    }

    private fun reason(failure: Exception) = when {
        failure is WorkspaceMediaException && failure.code == "INVALID_PATH" || failure is ExternalFolderException && failure.code == "INVALID_PATH" -> MediaUnavailableReason.INVALID_PATH
        failure is ExternalFolderException && failure.code in setOf("NOT_FOUND", "NOT_DIRECTORY") || failure is java.io.FileNotFoundException || failure is java.nio.file.NoSuchFileException -> MediaUnavailableReason.NOT_FOUND
        failure is ExternalFolderException && failure.code == "IS_DIRECTORY" -> MediaUnavailableReason.IS_DIRECTORY
        else -> MediaUnavailableReason.UNREADABLE
    }

    private fun storageFailure(failure: Exception): WorkspaceMediaException {
        if (failure is WorkspaceMediaException) return failure
        val code = when ((failure as? ExternalFolderException)?.code) {
            "INVALID_PATH", "READ_ONLY", "TOO_LARGE" -> failure.code
            "UNAVAILABLE" -> "WORKSPACE_UNAVAILABLE"
            "CONFLICT" -> "EXISTS"
            else -> "IO"
        }
        val message = when (code) {
            "INVALID_PATH" -> "媒体路径无效、越出工作区或包含保留目录"
            "READ_ONLY" -> "目标位置为只读，无法保存媒体"
            "TOO_LARGE" -> "单个媒体不能超过 256 MiB"
            "WORKSPACE_UNAVAILABLE" -> "工作区或挂载不可用，请检查目录与授权"
            "EXISTS" -> "目标文件或目录已存在，媒体保存不会覆盖"
            else -> "媒体读写失败，请检查存储空间、目录与授权"
        }
        return WorkspaceMediaException(code, message).apply { initCause(failure) }
    }

    companion object {
        private const val HEADER_SIZE = 64 * 1024
        private const val BUFFER_SIZE = 64 * 1024
        private val TIME = DateTimeFormatter.ofPattern("HHmm", Locale.ROOT)
        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
        // 有界锁池避免随会话数量增长；跨实例的同一会话也共享新建命名临界区。
        private val locks = Array(64) { Mutex() }
        private fun fail(code: String, message: String): Nothing = throw WorkspaceMediaException(code, message)
    }
}
