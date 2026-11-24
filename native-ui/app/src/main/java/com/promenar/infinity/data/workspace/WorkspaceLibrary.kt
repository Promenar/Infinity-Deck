package com.promenar.infinity.data.workspace

import com.promenar.infinity.data.local.db.dao.WorkspaceDao
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.storage.externalSha256
import com.promenar.infinity.data.storage.externalSnapshotMime
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.share.core.SharedFileImporter
import com.promenar.infinity.startup.StartupWriteBarrier
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

sealed interface WorkspaceDeleteResult {
    data object Deleted : WorkspaceDeleteResult
    /** 仍被会话或 Agent 引用，登记保留。 */
    data class Referenced(val sessions: Int, val agents: Int) : WorkspaceDeleteResult
    data object Missing : WorkspaceDeleteResult
}

/** 删除确认弹窗展示的实时工作区摘要（引用数、真实路径与本地文件统计）。 */
data class WorkspaceDeleteSummary(
    val workspaceId: String,
    val displayName: String,
    val kind: String,
    val displayPath: String,
    val fileCount: Int,
    val totalBytes: Long,
    val referenceSessions: Int,
    val referenceAgents: Int,
)

/**
 * 知识库与资料页中的已登记工作区管理：只读浏览、单文件加入知识库、重命名，以及按存储类型区分的安全删除。
 * - `delete(id)` 仅删除数据库登记，不触碰磁盘内容；
 * - `deleteWorkspace(id, deleteProjectFiles = true)` 对应用内项目目录（`KIND_PROJECT`）同步安全清理磁盘目录，
 *   对手机共享存储（`KIND_SHARED`）始终只移除登记而不触碰原文件。
 */
class WorkspaceLibrary(
    private val workspaceDao: WorkspaceDao,
    private val resolveWorkspace: suspend (String) -> SessionWorkspace,
    private val backendFor: (SessionWorkspace.Directory) -> ExternalFolderBackend,
    private val knowledge: IWorkspaceRepository,
    private val indexEvents: FileIndexEventSink,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val projectsRoot: File? = null,
    private val onRemoved: suspend (String) -> Unit = {},
) {
    fun observe(): Flow<List<WorkspaceEntity>> = workspaceDao.observeAll()

    suspend fun rename(id: String, rawName: String): Boolean = withContext(io) {
        val name = rawName.trim()
        require(name.isNotEmpty() && name.length <= MAX_DISPLAY_NAME) { "显示名不能为空且不超过 $MAX_DISPLAY_NAME 个字符" }
        workspaceDao.rename(id, name) == 1
    }

    suspend fun inspectDelete(id: String): WorkspaceDeleteSummary? = withContext(io) {
        val row = workspaceDao.getById(id) ?: return@withContext null
        val sessions = workspaceDao.countSessionReferences(id)
        val agents = workspaceDao.countAgentReferences(id)
        val resolvedDir = (resolveWorkspace(id) as? SessionWorkspace.Directory)?.root
        val displayPath = resolvedDir?.let { runCatching { it.canonicalPath }.getOrDefault(it.path) } ?: when (row.kind) {
            WorkspaceEntity.KIND_PROJECT -> projectsRoot?.let {
                runCatching { File(it.canonicalFile, row.location).path }.getOrDefault(File(it, row.location).path)
            } ?: row.location
            else -> row.location
        }
        val (fileCount, totalBytes) = if (row.kind == WorkspaceEntity.KIND_PROJECT && resolvedDir != null) {
            countProjectFiles(resolvedDir.toPath())
        } else 0 to 0L
        WorkspaceDeleteSummary(
            workspaceId = row.id,
            displayName = row.displayName,
            kind = row.kind,
            displayPath = displayPath,
            fileCount = fileCount,
            totalBytes = totalBytes,
            referenceSessions = sessions,
            referenceAgents = agents,
        )
    }

    suspend fun delete(id: String): WorkspaceDeleteResult = deleteWorkspace(id, deleteProjectFiles = false)

    /**
     * 安全移除已登记工作区：
     * - 仍被会话或智能体引用时拒绝并返回 [WorkspaceDeleteResult.Referenced]；
     * - [deleteProjectFiles] 为 true 且为应用内项目目录（`KIND_PROJECT`）时，先经启动写屏障与防符号链接校验隔离重命名，
     *   再原子删除数据库登记（若并发产生新引用则立即恢复目录名），最后不跟随符号链接彻底清理目录树；
     * - 共享存储（`KIND_SHARED`）始终只移除登记，永不触碰手机原文件。
     */
    suspend fun deleteWorkspace(id: String, deleteProjectFiles: Boolean = true): WorkspaceDeleteResult = withContext(io + NonCancellable) {
        val row = workspaceDao.getById(id) ?: return@withContext WorkspaceDeleteResult.Missing
        val root = projectsRoot
        if (row.kind != WorkspaceEntity.KIND_PROJECT || !deleteProjectFiles || root == null) {
            if (workspaceDao.deleteIfUnreferenced(id)) {
                runCatching { onRemoved(id) }
                return@withContext WorkspaceDeleteResult.Deleted
            }
            if (workspaceDao.getById(id) == null) return@withContext WorkspaceDeleteResult.Missing
            return@withContext WorkspaceDeleteResult.Referenced(
                workspaceDao.countSessionReferences(id),
                workspaceDao.countAgentReferences(id),
            )
        }

        val sessions = workspaceDao.countSessionReferences(id)
        val agents = workspaceDao.countAgentReferences(id)
        if (sessions > 0 || agents > 0) return@withContext WorkspaceDeleteResult.Referenced(sessions, agents)

        check(WorkspaceEntity.isValidLocation(WorkspaceEntity.KIND_PROJECT, row.location)) { "无效的项目目录名" }
        val projectsPath = root.toPath()
        check(!Files.isSymbolicLink(projectsPath)) { "项目根不能是符号链接" }
        val canonicalProjects = root.canonicalFile
        val target = File(canonicalProjects, row.location)
        val targetPath = target.toPath()

        var quarantine: File? = null
        if (Files.exists(targetPath) || Files.isSymbolicLink(targetPath)) {
            check(!Files.isSymbolicLink(targetPath)) { "项目目录不能是符号链接" }
            val canonicalTarget = target.canonicalFile
            check(canonicalTarget.parentFile == canonicalProjects) { "项目目录越出项目根" }
            StartupWriteBarrier.requireWritable(sessionId = null, path = canonicalTarget.path)
            val staged = File(canonicalProjects, ".infinity-delete-${UUID.randomUUID()}")
            check(canonicalTarget.renameTo(staged)) { "无法隔离待删除项目目录" }
            quarantine = staged
        }

        val deletedRow = try {
            workspaceDao.deleteIfUnreferenced(id)
        } catch (failure: Throwable) {
            quarantine?.let { staged -> if (!target.exists()) staged.renameTo(target) }
            throw failure
        }
        if (!deletedRow) {
            quarantine?.let { staged -> if (!target.exists()) staged.renameTo(target) }
            if (workspaceDao.getById(id) == null) return@withContext WorkspaceDeleteResult.Missing
            return@withContext WorkspaceDeleteResult.Referenced(
                workspaceDao.countSessionReferences(id),
                workspaceDao.countAgentReferences(id),
            )
        }

        quarantine?.let { deleteTreeWithoutFollowingSymlinks(it.toPath()) }
        runCatching { onRemoved(id) }
        WorkspaceDeleteResult.Deleted
    }

    private fun countProjectFiles(root: Path): Pair<Int, Long> {
        if (!Files.exists(root) || Files.isSymbolicLink(root)) return 0 to 0L
        var count = 0
        var bytes = 0L
        runCatching {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                        if (dir != root && attrs.isSymbolicLink) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile) {
                            count++
                            bytes += attrs.size()
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                },
            )
        }
        return count to bytes
    }

    private fun deleteTreeWithoutFollowingSymlinks(root: Path) {
        if (!Files.exists(root) && !Files.isSymbolicLink(root)) return
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    suspend fun list(workspaceId: String, path: String): List<ExternalFolderEntry> = withContext(io) {
        backend(workspaceId).list(path)
    }

    suspend fun read(workspaceId: String, path: String, maxBytes: Long): ByteArray = withContext(io) {
        backend(workspaceId).read(path, maxBytes)
    }

    /**
     * 把单个文件复制进知识库根目录并发布索引事件，返回新文件 uuid。
     * 与外部文件夹“加入知识库快照”同一规则：受支持的类型、8 MiB 上限、读取前后核对来源未变化。
     */
    suspend fun importToKnowledge(workspaceId: String, path: String, knowledgeRootUuid: String): String = withContext(io) {
        val backend = backend(workspaceId)
        val original = backend.stat(path) ?: throw ExternalFolderException("NOT_FOUND", "源文件不可用")
        if (original.isDirectory) throw ExternalFolderException("IS_DIRECTORY", "请选择单个文件加入资料")
        val mime = externalSnapshotMime(original.name)
        if (mime !in SharedFileImporter.SUPPORTED_MIME_TYPES) {
            throw ExternalFolderException("UNSUPPORTED", "资料支持 TXT、Markdown、CSV、JSON、HTML、PDF、DOC 和 DOCX")
        }
        val bytes = backend.read(path, MAX_IMPORT_BYTES)
        val after = backend.stat(path) ?: throw ExternalFolderException("UNAVAILABLE", "读取后来源不可用")
        val hash = externalSha256(bytes)
        if (after.lastModified != original.lastModified || after.size != original.size ||
            (after.identity != null && after.identity != original.identity) ||
            (!after.sha256.isNullOrEmpty() && after.sha256 != hash)
        ) throw ExternalFolderException("CONFLICT", "读取期间源文件发生变化，请重试")
        val uuid = UUID.randomUUID().toString()
        val safeName = "workspace-${uuid.take(8)}-${original.name}"
        val created = knowledge.createFileInWorkspaceStreaming(
            knowledgeRootUuid, uuid, safeName, mime,
            knowledgeRootUuid, "/$safeName", MAX_IMPORT_BYTES,
        ) { it.write(bytes) }
        try {
            indexEvents.publish(FileIndexEvent.Changed(knowledgeRootUuid, uuid, created.hash, created.updatedAt.coerceAtLeast(1)))
            knowledge.confirmCreatedEntry(knowledgeRootUuid, uuid)
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                try { knowledge.rollbackCreatedEntry(knowledgeRootUuid, uuid) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            }
            throw failure
        }
        uuid
    }

    private suspend fun backend(workspaceId: String): ExternalFolderBackend =
        when (val resolved = resolveWorkspace(workspaceId)) {
            is SessionWorkspace.Directory -> backendFor(resolved)
            is SessionWorkspace.Unavailable -> throw ExternalFolderException("UNAVAILABLE", resolved.reason)
            SessionWorkspace.Private -> throw ExternalFolderException("UNAVAILABLE", "工作区不可用")
        }

    companion object {
        const val MAX_DISPLAY_NAME = 80
        const val MAX_IMPORT_BYTES = 8L * 1024 * 1024
    }
}
