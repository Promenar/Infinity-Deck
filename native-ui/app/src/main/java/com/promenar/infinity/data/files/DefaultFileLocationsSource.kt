package com.promenar.infinity.data.files

import com.promenar.infinity.data.branch.BranchWorktreeWorkspaceResolver
import com.promenar.infinity.data.git.GitInspectResult
import com.promenar.infinity.data.git.GitStatus
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.domain.files.FileLocation
import com.promenar.infinity.domain.files.FileLocationCapabilities
import com.promenar.infinity.domain.files.FileLocationKind
import com.promenar.infinity.domain.files.FileLocationsSource
import com.promenar.infinity.domain.storage.ExternalMount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext

internal data class LocationTitles(val library: String, val sessions: String, val linux: String)

/** 接收可替换的数据源，位置观察不拥有应用级协程。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DefaultFileLocationsSource(
    private val sessions: Flow<List<SessionEntity>>,
    private val workspaces: Flow<List<WorkspaceEntity>>,
    private val linuxReady: Flow<Boolean>,
    private val mounts: (String) -> Flow<List<ExternalMount>>,
    private val resolveSession: suspend (String) -> SessionWorkspace,
    private val resolveWorkspace: suspend (String) -> SessionWorkspace,
    private val mountAvailable: (ExternalMount) -> Boolean,
    private val gitStatus: suspend (String) -> GitInspectResult<GitStatus>,
    private val workspaceGitStatus: suspend (SessionWorkspace.Directory) -> GitInspectResult<GitStatus>,
    private val titles: LocationTitles,
    private val invalidateGitStatus: () -> Unit = {},
    private val cachedGitStatus: suspend (String) -> GitInspectResult<GitStatus>? = { null },
    private val cachedWorkspaceGitStatus: suspend (SessionWorkspace.Directory) -> GitInspectResult<GitStatus>? = { null },
) : FileLocationsSource {
    private val knownGitStatus = java.util.concurrent.ConcurrentHashMap<String, GitStatus>()
    private val gitRefresh = MutableStateFlow(0)

    override fun refreshGitStatus() { invalidateGitStatus(); gitRefresh.value++ }

    private val mountSnapshot: Flow<List<ExternalMount>> = sessions.flatMapLatest { rows ->
        combine(listOf(mounts(ExternalMountPaths.LIBRARY_SCOPE)) + rows.filterNot(::isSystemSession).map { mounts(it.id) }) { values -> values.flatMap { it } }
    }

    /** 先发出不含 Git 信息的位置，避免逐个项目读取 Git 状态时侧栏长时间为空；随后补发带 Git 信息的结果。 */
    override fun observeLibraryLocations(): Flow<List<FileLocation>> =
        combine(sessions, workspaces, linuxReady, mountSnapshot, gitRefresh) { rows, registered, linux, attached, _ ->
            LibrarySnapshot(rows, registered, linux, attached)
        }.onStart { invalidateGitStatus() }.transformLatest { snapshot ->
            emit(libraryLocations(snapshot, git = false))
            emit(libraryLocations(snapshot, git = true))
        }.distinctUntilChanged()

    /** 置顶校验只需要位置 ID：沿用资料位置的组成规则，但不读取 Git 状态，避免每个资源管理器都为全部项目跑一遍 Git。 */
    fun observeLibraryLocationIds(): Flow<Set<String>> =
        combine(sessions, workspaces, linuxReady, mountSnapshot) { rows, registered, linux, attached ->
            libraryLocations(LibrarySnapshot(rows, registered, linux, attached), git = false, useCached = false).mapTo(mutableSetOf()) { it.id }
        }.distinctUntilChanged()

    private data class LibrarySnapshot(
        val rows: List<SessionEntity>,
        val registered: List<WorkspaceEntity>,
        val linux: Boolean,
        val attached: List<ExternalMount>,
    )

    private suspend fun libraryLocations(snapshot: LibrarySnapshot, git: Boolean, useCached: Boolean = true): List<FileLocation> {
        val (rows, registered, linux, attached) = snapshot
        return withContext(Dispatchers.IO) {
            val visibleSessions = rows.filterNot(::isSystemSession)
            val result = mutableListOf(libraryLocation(), sessionsLocation())
            val worktreeIds = mutableSetOf<String>()
            val sessionByWorkspace = visibleSessions.filter { it.workspaceId != null && it.worktreeDirectory == null }.groupBy { it.workspaceId }
            registered.filter { it.kind in setOf(WorkspaceEntity.KIND_PROJECT, WorkspaceEntity.KIND_SHARED) }.forEach { workspace ->
                val directory = resolveWorkspace(workspace.id) as? SessionWorkspace.Directory
                result += workspaceLocation(workspace, sessionByWorkspace[workspace.id]?.firstOrNull()?.id, linux, directory, git, useCached)
            }
            visibleSessions.filter { it.worktreeDirectory != null }.forEach { session ->
                val resolved = resolveSession(session.id)
                val directory = resolved as? SessionWorkspace.Directory
                if (directory?.kind == BranchWorktreeWorkspaceResolver.KIND && worktreeIds.add(directory.workspace.id)) {
                    result += workspaceLocation(directory.workspace, session.id, linux, directory, git, useCached)
                }
            }
            if (linux) result += linuxLocation()
            val folders = attached.filter { it.sourceType in setOf("saf_tree", "real_path") }
            val librarySources = folders.filter { ExternalMountPaths.isLibraryScope(it.sessionId) }.map { it.sourceType to it.sourceId }.toSet()
            result += folders.filter { ExternalMountPaths.isLibraryScope(it.sessionId) || (it.sourceType to it.sourceId) !in librarySources }
                .distinctBy { it.id }.map(::externalLocation)
            result
        }
    }

    /** 与资料页同样先发出不含 Git 信息的位置，Git 状态较慢时不会先显示“此位置暂不可用”。 */
    override fun observeSessionLocations(sessionId: String): Flow<List<FileLocation>> =
        combine(sessions, linuxReady, mounts(sessionId), gitRefresh) { rows, linux, attached, _ ->
            Triple(rows, linux, attached)
        }.onStart { invalidateGitStatus() }.transformLatest { (rows, linux, attached) ->
            emit(sessionLocations(sessionId, rows, linux, attached, git = false))
            emit(sessionLocations(sessionId, rows, linux, attached, git = true))
        }.distinctUntilChanged()

    private suspend fun sessionLocations(
        sessionId: String, rows: List<SessionEntity>, linux: Boolean, attached: List<ExternalMount>, git: Boolean,
    ): List<FileLocation> = withContext(Dispatchers.IO) {
        val session = rows.firstOrNull { it.id == sessionId && !isSystemSession(it) } ?: return@withContext emptyList()
        val current = when (val workspace = resolveSession(session.id)) {
            SessionWorkspace.Private -> sessionLocation(session, linux, git)
            is SessionWorkspace.Directory -> workspaceLocation(workspace.workspace, session.id, linux, workspace, git)
            is SessionWorkspace.Unavailable -> null
        }
        listOfNotNull(current) + attached.filter { it.sourceType in setOf("saf_tree", "real_path", "knowledge_base") }
            .distinctBy { it.id }.map(::externalLocation)
    }

    override fun observeLinuxHome(): Flow<FileLocation?> = linuxReady.map { if (it) linuxLocation() else null }

    internal fun libraryLocation() = FileLocation("kb", FileLocationKind.KNOWLEDGE_BASE, titles.library, WRITABLE)
    internal fun sessionsLocation() = FileLocation("sessions", FileLocationKind.SESSION_WORKSPACE, titles.sessions, READ_ONLY)
    internal suspend fun sessionLocation(session: SessionEntity, linux: Boolean, git: Boolean = true): FileLocation {
        val key = "session:${session.id}"
        val status = if (!git) knownGitStatus[key] ?: (cachedGitStatus(session.id) as? GitInspectResult.Success<GitStatus>)?.value else {
            (gitStatus(session.id) as? GitInspectResult.Success<GitStatus>)?.value
                .also { if (it == null) knownGitStatus.remove(key) else knownGitStatus[key] = it }
        }
        return FileLocation(
            "session:${session.id}", FileLocationKind.SESSION_WORKSPACE, session.title,
            WRITABLE.copy(git = status != null, terminal = linux && session.workspaceId == null && session.worktreeDirectory == null),
            sessionId = session.id, gitBranch = status?.branch,
            gitChangeCount = status?.changeCount?.takeUnless { status.truncated },
            gitDetached = status?.detached == true, gitHeadShortSha = status?.head?.take(7),
        )
    }
    internal fun linuxLocation() = FileLocation("linux:home", FileLocationKind.LINUX_HOME, titles.linux,
        FileLocationCapabilities(writable = true, deletable = true, archive = true, terminal = true))

    private suspend fun workspaceLocation(
        workspace: WorkspaceEntity, sessionId: String?, linux: Boolean, directory: SessionWorkspace.Directory?, git: Boolean = true,
        useCached: Boolean = true,
    ): FileLocation {
        val available = directory != null
        val key = "workspace:${workspace.id}"
        val status = when {
            directory == null -> null
            // 快速通道沿用上次读到的 Git 状态，会话列表刷新时分支名不会闪烁消失。
            !git -> knownGitStatus[key] ?: if (!useCached) null else
                ((if (sessionId != null) cachedGitStatus(sessionId) else cachedWorkspaceGitStatus(directory))
                    as? GitInspectResult.Success<GitStatus>)?.value
            else -> when (val result = if (sessionId != null) gitStatus(sessionId) else workspaceGitStatus(directory)) {
                is GitInspectResult.Success -> result.value
                is GitInspectResult.Failure -> null
            }.also { if (it == null) knownGitStatus.remove(key) else knownGitStatus[key] = it }
        }
        return FileLocation(
            id = "workspace:${workspace.id}",
            kind = if (workspace.kind == BranchWorktreeWorkspaceResolver.KIND) FileLocationKind.BRANCH_WORKTREE else FileLocationKind.PROJECT,
            title = workspace.displayName,
            capabilities = FileLocationCapabilities(writable = available, deletable = available, archive = available,
                git = status != null, terminal = available && linux && sessionId != null),
            gitBranch = status?.branch,
            gitChangeCount = status?.changeCount?.takeUnless { status.truncated },
            gitDetached = status?.detached == true,
            gitHeadShortSha = status?.head?.take(7),
        )
    }

    private fun externalLocation(mount: ExternalMount): FileLocation {
        val lost = mount.unavailableReason != null || !mountAvailable(mount)
        val writable = !mount.readOnly && mount.sourceType != "knowledge_base" && !lost
        return FileLocation("external:${mount.id}", FileLocationKind.EXTERNAL_FOLDER, mount.alias,
            FileLocationCapabilities(writable = writable, deletable = writable, archive = writable, terminal = false),
            accessLost = lost)
    }

    companion object {
        private val WRITABLE = FileLocationCapabilities(writable = true, deletable = true, archive = true)
        private val READ_ONLY = FileLocationCapabilities(writable = false, deletable = false, archive = false)
    }
}

internal fun isSystemSession(session: SessionEntity) = session.agentId == "__system__"

/**
 * “会话工作区”汇总根列出的会话：普通会话、有私有工作区、未绑定项目或分支工作区。
 * 绑定项目或分支的会话，其文件在对应的项目位置里；固定文件夹的有效性校验用同一规则，改绑后旧置顶随之失效。
 */
internal fun isPrivateSessionFolder(session: SessionEntity) =
    !isSystemSession(session) && !session.workspaceRootUuid.isNullOrBlank() &&
        session.workspaceId == null && session.worktreeDirectory == null

/** 汇总名称按稳定会话 id 排序去重，路径片段始终可通过后端校验。 */
internal fun sessionFolderNames(rows: List<SessionEntity>): Map<String, String> {
    val result = linkedMapOf<String, String>()
    val used = mutableSetOf<String>()
    rows.sortedBy { it.id }.forEach { session ->
        val cleaned = session.title.map { if (it == '/' || it == '\\' || it == '\u0000') ' ' else it }.joinToString("").trim()
            .takeUnless { it.isBlank() || it == "." || it == ".." }
            ?.take(120) ?: session.id
        val fallback = "session-" + session.id.map { if (it.isLetterOrDigit() || it in "-_") it else '_' }.joinToString("").take(100)
        val base = runCatching { validateFileName(cleaned) }.getOrElse { fallback }
        var name = base
        var suffix = 2
        while (!used.add(name.lowercase(java.util.Locale.ROOT))) name = collisionName(base, true, suffix++)
        result[session.id] = name
    }
    return result
}
