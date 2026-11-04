package com.promenar.infinity.data.files

import com.promenar.infinity.data.branch.BranchWorktreeWorkspaceResolver
import com.promenar.infinity.data.git.GitInspectResult
import com.promenar.infinity.data.git.GitStatus
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.domain.files.FileLocation
import com.promenar.infinity.domain.files.FileLocationCapabilities
import com.promenar.infinity.domain.files.FileLocationKind
import com.promenar.infinity.domain.files.FileLocationsSource
import com.promenar.infinity.domain.storage.ExternalMount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
) : FileLocationsSource {
    private val mountSnapshot: Flow<List<ExternalMount>> = sessions.flatMapLatest { rows ->
        if (rows.isEmpty()) flowOf(emptyList())
        else combine(rows.map { mounts(it.id) }) { values -> values.flatMap { it } }
    }

    override fun observeLibraryLocations(): Flow<List<FileLocation>> =
        combine(sessions, workspaces, linuxReady, mountSnapshot) { rows, registered, linux, attached ->
            withContext(Dispatchers.IO) {
                val visibleSessions = rows.filterNot(::isSystemSession)
                val result = mutableListOf(libraryLocation(), sessionsLocation())
                val worktreeIds = mutableSetOf<String>()
                val sessionByWorkspace = visibleSessions.filter { it.workspaceId != null && it.worktreeDirectory == null }.groupBy { it.workspaceId }
                registered.filter { it.kind in setOf(WorkspaceEntity.KIND_PROJECT, WorkspaceEntity.KIND_SHARED) }.forEach { workspace ->
                    val directory = resolveWorkspace(workspace.id) as? SessionWorkspace.Directory
                    result += workspaceLocation(workspace, sessionByWorkspace[workspace.id]?.firstOrNull()?.id, linux, directory)
                }
                visibleSessions.filter { it.worktreeDirectory != null }.forEach { session ->
                    val resolved = resolveSession(session.id)
                    val directory = resolved as? SessionWorkspace.Directory
                    if (directory?.kind == BranchWorktreeWorkspaceResolver.KIND && worktreeIds.add(directory.workspace.id)) {
                        result += workspaceLocation(directory.workspace, session.id, linux, directory)
                    }
                }
                if (linux) result += linuxLocation()
                result += attached.filter { it.sourceType in setOf("saf_tree", "real_path") }
                    .distinctBy { it.id }.map(::externalLocation)
                result
            }
        }

    override fun observeSessionLocations(sessionId: String): Flow<List<FileLocation>> =
        combine(sessions, linuxReady, mounts(sessionId)) { rows, linux, attached ->
            withContext(Dispatchers.IO) {
                val session = rows.firstOrNull { it.id == sessionId && !isSystemSession(it) } ?: return@withContext emptyList()
                val current = when (val workspace = resolveSession(session.id)) {
                    SessionWorkspace.Private -> sessionLocation(session, linux)
                    is SessionWorkspace.Directory -> workspaceLocation(workspace.workspace, session.id, linux, workspace)
                    is SessionWorkspace.Unavailable -> null
                }
                listOfNotNull(current) + attached.filter { it.sourceType in setOf("saf_tree", "real_path", "knowledge_base") }
                    .distinctBy { it.id }.map(::externalLocation)
            }
        }

    override fun observeLinuxHome(): Flow<FileLocation?> = linuxReady.map { if (it) linuxLocation() else null }

    internal fun libraryLocation() = FileLocation("kb", FileLocationKind.KNOWLEDGE_BASE, titles.library, WRITABLE)
    internal fun sessionsLocation() = FileLocation("sessions", FileLocationKind.SESSION_WORKSPACE, titles.sessions, READ_ONLY)
    internal suspend fun sessionLocation(session: SessionEntity, linux: Boolean): FileLocation {
        val status = (gitStatus(session.id) as? GitInspectResult.Success<GitStatus>)?.value
        return FileLocation(
            "session:${session.id}", FileLocationKind.SESSION_WORKSPACE, session.title,
            WRITABLE.copy(git = status != null, terminal = linux && session.workspaceId == null && session.worktreeDirectory == null),
            sessionId = session.id, gitBranch = status?.branch,
            gitChangeCount = status?.changeCount?.takeUnless { status.truncated },
        )
    }
    internal fun linuxLocation() = FileLocation("linux:home", FileLocationKind.LINUX_HOME, titles.linux,
        FileLocationCapabilities(writable = true, recycle = true, archive = true, terminal = true))

    private suspend fun workspaceLocation(workspace: WorkspaceEntity, sessionId: String?, linux: Boolean, directory: SessionWorkspace.Directory?): FileLocation {
        val available = directory != null
        val status = if (directory == null) null else {
            when (val result = if (sessionId != null) gitStatus(sessionId) else workspaceGitStatus(directory)) {
                is GitInspectResult.Success -> result.value
                is GitInspectResult.Failure -> null
            }
        }
        return FileLocation(
            id = "workspace:${workspace.id}",
            kind = if (workspace.kind == BranchWorktreeWorkspaceResolver.KIND) FileLocationKind.BRANCH_WORKTREE else FileLocationKind.PROJECT,
            title = workspace.displayName,
            capabilities = FileLocationCapabilities(writable = available, recycle = available, archive = available,
                git = status != null, terminal = available && linux && sessionId != null),
            gitBranch = status?.branch,
            gitChangeCount = status?.changeCount?.takeUnless { status.truncated },
        )
    }

    private fun externalLocation(mount: ExternalMount): FileLocation {
        val lost = mount.unavailableReason != null || !mountAvailable(mount)
        val writable = !mount.readOnly && mount.sourceType != "knowledge_base" && !lost
        return FileLocation("external:${mount.id}", FileLocationKind.EXTERNAL_FOLDER, mount.alias,
            FileLocationCapabilities(writable = writable, recycle = writable, archive = writable, terminal = false),
            accessLost = lost)
    }

    companion object {
        private val WRITABLE = FileLocationCapabilities(writable = true, recycle = true, archive = true)
        private val READ_ONLY = FileLocationCapabilities(writable = false, recycle = false, archive = false)
    }
}

internal fun isSystemSession(session: SessionEntity) = session.agentId == "__system__"

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
