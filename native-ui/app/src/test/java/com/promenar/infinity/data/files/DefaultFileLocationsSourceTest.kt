package com.promenar.infinity.data.files

import com.promenar.infinity.data.git.GitChangeList
import com.promenar.infinity.data.git.GitInspectError
import com.promenar.infinity.data.git.GitInspectResult
import com.promenar.infinity.data.git.GitStatus
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.domain.storage.ExternalMount
import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultFileLocationsSourceTest {
    @Test fun `存储空间挂载优先于同源会话引用且保留其他会话位置`() = runBlocking {
        val library = ExternalMount("lib", "docs", "real_path", "/fictional/docs", ExternalMountPaths.LIBRARY_SCOPE, 1)
        val reference = library.copy(id = "ref", sessionId = "session")
        val other = reference.copy(id = "other", sourceId = "/fictional/other")
        val locations = DefaultFileLocationsSource(
            sessions = flowOf(listOf(SessionEntity("session", "agent", createdAt = 1, updatedAt = 1))),
            workspaces = flowOf(emptyList()), linuxReady = flowOf(false),
            mounts = { flowOf(if (it == ExternalMountPaths.LIBRARY_SCOPE) listOf(library) else listOf(reference, other)) },
            resolveSession = { SessionWorkspace.Private }, resolveWorkspace = { SessionWorkspace.Private },
            mountAvailable = { true }, gitStatus = { GitInspectResult.Failure(GitInspectError.NotRepository) },
            workspaceGitStatus = { GitInspectResult.Failure(GitInspectError.NotRepository) },
            titles = LocationTitles("资料", "会话", "Linux"),
        )
        assertEquals(setOf("kb", "sessions", "external:lib", "external:other"), locations.observeLibraryLocationIds().first())
        assertEquals(setOf("kb", "sessions", "external:lib", "external:other"), locations.observeLibraryLocations().first().map { it.id }.toSet())
        assertEquals(setOf("session:session", "external:ref", "external:other"), locations.observeSessionLocations("session").first().map { it.id }.toSet())
    }
    @Test fun `重新打开时先发出共享缓存并触发刷新`() = runBlocking {
        val workspace = WorkspaceEntity("project", WorkspaceEntity.KIND_PROJECT, "项目", "project", 1)
        val directory = SessionWorkspace.Directory(workspace, File("/fictional/project"))
        var invalidations = 0
        val locations = DefaultFileLocationsSource(
            sessions = flowOf(emptyList()), workspaces = flowOf(listOf(workspace)), linuxReady = flowOf(false),
            mounts = { flowOf(emptyList()) }, resolveSession = { SessionWorkspace.Private },
            resolveWorkspace = { directory }, mountAvailable = { true },
            gitStatus = { awaitCancellation() }, workspaceGitStatus = { awaitCancellation() },
            titles = LocationTitles("资料", "会话", "Linux"),
            cachedWorkspaceGitStatus = { GitInspectResult.Success(status("cached")) },
            invalidateGitStatus = { invalidations++ },
        )
        val first = withTimeout(5_000) { locations.observeLibraryLocations().first() }
        assertEquals("cached", first.last().gitBranch)
        assertEquals(1, invalidations)
    }

    @Test fun `手动刷新位置时先使共享 Git 缓存失效`() {
        var invalidations = 0
        val locations = DefaultFileLocationsSource(
            sessions = flowOf(emptyList()), workspaces = flowOf(emptyList()), linuxReady = flowOf(false),
            mounts = { flowOf(emptyList()) }, resolveSession = { SessionWorkspace.Private },
            resolveWorkspace = { SessionWorkspace.Private }, mountAvailable = { true },
            gitStatus = { GitInspectResult.Failure(GitInspectError.NotRepository) },
            workspaceGitStatus = { GitInspectResult.Failure(GitInspectError.NotRepository) },
            titles = LocationTitles("资料", "会话", "Linux"), invalidateGitStatus = { invalidations++ },
        )
        locations.refreshGitStatus()
        assertEquals(1, invalidations)
    }

    @Test fun `未绑定会话的项目读取现有 Git 状态`() = runBlocking {
        val workspace = WorkspaceEntity("project", WorkspaceEntity.KIND_PROJECT, "项目", "project", 1)
        val directory = SessionWorkspace.Directory(workspace, File("/fictional/project"))
        val status = GitStatus(branch = "main", detached = false, head = null, unborn = false,
            staged = GitChangeList.Empty, unstaged = GitChangeList.Empty, untracked = GitChangeList.Empty)
        val locations = DefaultFileLocationsSource(
            sessions = flowOf(emptyList()), workspaces = flowOf(listOf(workspace)), linuxReady = flowOf(false),
            mounts = { flowOf(emptyList()) }, resolveSession = { SessionWorkspace.Private },
            resolveWorkspace = { directory }, mountAvailable = { true },
            gitStatus = { GitInspectResult.Failure(GitInspectError.NotRepository) },
            workspaceGitStatus = { GitInspectResult.Success(status) }, titles = LocationTitles("资料", "会话", "Linux"),
        )
        val items = locations.observeLibraryLocations().first { it.last().gitBranch != null }
        assertEquals(listOf("kb", "sessions", "workspace:project"), items.map { it.id })
        assertTrue(items.last().capabilities.git)
        assertFalse(items.last().capabilities.terminal)
        assertEquals("main", items.last().gitBranch)
        assertEquals(0, items.last().gitChangeCount ?: -1)
    }

    @Test fun `Git 状态未返回时位置列表先行发出`() = runBlocking {
        val workspace = WorkspaceEntity("project", WorkspaceEntity.KIND_PROJECT, "项目", "project", 1)
        val directory = SessionWorkspace.Directory(workspace, File("/fictional/project"))
        val locations = DefaultFileLocationsSource(
            sessions = flowOf(emptyList()), workspaces = flowOf(listOf(workspace)), linuxReady = flowOf(false),
            mounts = { flowOf(emptyList()) }, resolveSession = { SessionWorkspace.Private },
            resolveWorkspace = { directory }, mountAvailable = { true },
            gitStatus = { awaitCancellation() }, workspaceGitStatus = { awaitCancellation() },
            titles = LocationTitles("资料", "会话", "Linux"),
        )
        val items = withTimeout(5_000) { locations.observeLibraryLocations().first() }
        assertEquals(listOf("kb", "sessions", "workspace:project"), items.map { it.id })
        assertTrue(items.last().capabilities.writable)
        assertEquals(null, items.last().gitBranch)
    }

    @Test fun `会话位置在 Git 状态返回前先行发出`() = runBlocking {
        val workspace = WorkspaceEntity("project", WorkspaceEntity.KIND_PROJECT, "项目", "project", 1)
        val directory = SessionWorkspace.Directory(workspace, File("/fictional/project"))
        val sessions = listOf(
            SessionEntity("bound", "agent", title = "绑定项目", createdAt = 1, updatedAt = 1),
            SessionEntity("private", "agent", title = "专用空间", createdAt = 1, updatedAt = 1),
        )
        val locations = DefaultFileLocationsSource(
            sessions = flowOf(sessions), workspaces = flowOf(listOf(workspace)), linuxReady = flowOf(false),
            mounts = { flowOf(emptyList()) },
            resolveSession = { id -> if (id == "bound") directory else SessionWorkspace.Private },
            resolveWorkspace = { directory }, mountAvailable = { true },
            gitStatus = { awaitCancellation() }, workspaceGitStatus = { awaitCancellation() },
            titles = LocationTitles("资料", "会话", "Linux"),
        )
        val bound = withTimeout(5_000) { locations.observeSessionLocations("bound").first() }
        assertEquals(listOf("workspace:project"), bound.map { it.id })
        assertTrue(bound.single().capabilities.writable)
        val private = withTimeout(5_000) { locations.observeSessionLocations("private").first() }
        assertEquals(listOf("session:private"), private.map { it.id })
        assertFalse(private.single().capabilities.git)
    }

    @Test fun `会话表持续写入其它字段时位置仍能发出`() = runBlocking {
        // 会话表每 5ms 写一次时间戳，位置的快速通道读缓存要 50ms：只按影响位置的字段去重才不会被反复取消。
        val writes = kotlinx.coroutines.flow.flow {
            var tick = 1L
            while (true) {
                emit(listOf(SessionEntity("private", "agent", title = "专用空间", createdAt = 1, updatedAt = tick++)))
                delay(5)
            }
        }
        val locations = DefaultFileLocationsSource(
            sessions = writes, workspaces = flowOf(emptyList()), linuxReady = flowOf(true),
            mounts = { flowOf(emptyList()) }, resolveSession = { SessionWorkspace.Private },
            resolveWorkspace = { SessionWorkspace.Private }, mountAvailable = { true },
            gitStatus = { awaitCancellation() }, workspaceGitStatus = { awaitCancellation() },
            titles = LocationTitles("资料", "会话", "Linux"),
            cachedGitStatus = { delay(50); null },
        )
        val items = withTimeout(5_000) { locations.observeSessionLocations("private").first() }
        assertEquals(listOf("session:private"), items.map { it.id })
    }

    @Test fun `刷新 Git 状态后位置补发新分支`() = runBlocking {
        val workspace = WorkspaceEntity("project", WorkspaceEntity.KIND_PROJECT, "项目", "project", 1)
        val directory = SessionWorkspace.Directory(workspace, File("/fictional/project"))
        var branch = "main"
        val locations = DefaultFileLocationsSource(
            sessions = flowOf(listOf(SessionEntity("bound", "agent", createdAt = 1, updatedAt = 1))),
            workspaces = flowOf(listOf(workspace)), linuxReady = flowOf(false),
            mounts = { flowOf(emptyList()) }, resolveSession = { directory },
            resolveWorkspace = { directory }, mountAvailable = { true },
            gitStatus = { GitInspectResult.Success(status(branch)) },
            workspaceGitStatus = { GitInspectResult.Success(status(branch)) },
            titles = LocationTitles("资料", "会话", "Linux"),
        )
        val seen = java.util.concurrent.CopyOnWriteArrayList<String?>()
        val job = launch(Dispatchers.Default) {
            locations.observeSessionLocations("bound").collect { seen += it.singleOrNull()?.gitBranch }
        }
        withTimeout(5_000) { while ("main" !in seen) delay(10) }
        branch = "feature"
        locations.refreshGitStatus()
        withTimeout(5_000) { while ("feature" !in seen) delay(10) }
        job.cancel()
    }

    @Test fun `游离 HEAD 位置带短提交号`() = runBlocking {
        val workspace = WorkspaceEntity("project", WorkspaceEntity.KIND_PROJECT, "项目", "project", 1)
        val directory = SessionWorkspace.Directory(workspace, File("/fictional/project"))
        val detached = status(null).copy(detached = true, head = "0123456789abcdef")
        val locations = DefaultFileLocationsSource(
            sessions = flowOf(listOf(SessionEntity("bound", "agent", createdAt = 1, updatedAt = 1))),
            workspaces = flowOf(listOf(workspace)), linuxReady = flowOf(false),
            mounts = { flowOf(emptyList()) }, resolveSession = { directory },
            resolveWorkspace = { directory }, mountAvailable = { true },
            gitStatus = { GitInspectResult.Success(detached) },
            workspaceGitStatus = { GitInspectResult.Success(detached) },
            titles = LocationTitles("资料", "会话", "Linux"),
        )
        val item = withTimeout(5_000) { locations.observeSessionLocations("bound").first { it.single().capabilities.git } }.single()
        assertTrue(item.gitDetached)
        assertEquals("0123456", item.gitHeadShortSha)
        assertEquals(null, item.gitBranch)
    }

    @Test fun `置顶校验只取位置 ID 不读取 Git 状态`() = runBlocking {
        val workspace = WorkspaceEntity("project", WorkspaceEntity.KIND_PROJECT, "项目", "project", 1)
        val directory = SessionWorkspace.Directory(workspace, File("/fictional/project"))
        val gitCalls = java.util.concurrent.atomic.AtomicInteger()
        val locations = DefaultFileLocationsSource(
            sessions = flowOf(emptyList()), workspaces = flowOf(listOf(workspace)), linuxReady = flowOf(false),
            mounts = { flowOf(emptyList()) }, resolveSession = { SessionWorkspace.Private },
            resolveWorkspace = { directory }, mountAvailable = { true },
            gitStatus = { gitCalls.incrementAndGet(); GitInspectResult.Success(status("main")) },
            workspaceGitStatus = { gitCalls.incrementAndGet(); GitInspectResult.Success(status("main")) },
            titles = LocationTitles("资料", "会话", "Linux"),
        )
        val ids = withTimeout(5_000) { locations.observeLibraryLocationIds().first() }
        delay(200)
        assertEquals(setOf("kb", "sessions", "workspace:project"), ids)
        assertEquals(0, gitCalls.get())
    }

    @Test fun `工作区与外部挂载填充完整路径来源分类与引用计数`() = runBlocking {
        val retained = WorkspaceEntity("w-ret", WorkspaceEntity.KIND_PROJECT, "执行测试文件操作", "session-20261115-0002", 1)
        val project = WorkspaceEntity("w-proj", WorkspaceEntity.KIND_PROJECT, "自建项目", "demo", 2)
        val shared = WorkspaceEntity("w-shared", WorkspaceEntity.KIND_SHARED, "Personal-Archive", "/storage/emulated/0/Personal-Archive", 3)
        val mount = ExternalMount("m1", "文档集", "real_path", "/storage/emulated/0/Docs/Work/Specs", ExternalMountPaths.LIBRARY_SCOPE, 4)
        val locations = DefaultFileLocationsSource(
            sessions = flowOf(listOf(SessionEntity("s1", "agent", workspaceId = "w-shared", createdAt = 1, updatedAt = 1))),
            workspaces = flowOf(listOf(retained, project, shared)),
            linuxReady = flowOf(true),
            mounts = { flowOf(if (it == ExternalMountPaths.LIBRARY_SCOPE) listOf(mount) else emptyList()) },
            resolveSession = { SessionWorkspace.Private },
            resolveWorkspace = { id ->
                when (id) {
                    "w-ret" -> SessionWorkspace.Directory(retained, File("/fictional/projects/session-20261115-0002"))
                    "w-proj" -> SessionWorkspace.Directory(project, File("/fictional/projects/demo"))
                    "w-shared" -> SessionWorkspace.Directory(shared, File("/storage/emulated/0/Personal-Archive"))
                    else -> SessionWorkspace.Private
                }
            },
            mountAvailable = { true },
            gitStatus = { GitInspectResult.Failure(GitInspectError.NotRepository) },
            workspaceGitStatus = { GitInspectResult.Failure(GitInspectError.NotRepository) },
            titles = LocationTitles("存储空间", "会话工作区", "Linux 主目录"),
            agentWorkspaceIds = flowOf(listOf("w-shared", "w-proj")),
        )
        val items = withTimeout(5_000) { locations.observeLibraryLocations().first() }.associateBy { it.id }

        val retLoc = items.getValue("workspace:w-ret")
        assertEquals(com.promenar.infinity.domain.files.FileLocationStorageKind.RETAINED_SESSION, retLoc.storageKind)
        assertEquals("w-ret", retLoc.workspaceId)
        assertEquals("session-20261115-0002", retLoc.storageName)
        assertEquals(0, retLoc.referenceSessions)
        assertEquals(0, retLoc.referenceAgents)
        assertTrue(retLoc.displayPath!!.endsWith("/fictional/projects/session-20261115-0002"))

        val projLoc = items.getValue("workspace:w-proj")
        assertEquals(com.promenar.infinity.domain.files.FileLocationStorageKind.APP_PROJECT, projLoc.storageKind)
        assertEquals(1, projLoc.referenceAgents)

        val sharedLoc = items.getValue("workspace:w-shared")
        assertEquals(com.promenar.infinity.domain.files.FileLocationStorageKind.SHARED_STORAGE, sharedLoc.storageKind)
        assertEquals("/Personal-Archive", sharedLoc.storageName)
        assertEquals(1, sharedLoc.referenceSessions)
        assertEquals(1, sharedLoc.referenceAgents)

        val extLoc = items.getValue("external:m1")
        assertEquals(com.promenar.infinity.domain.files.FileLocationStorageKind.EXTERNAL_MOUNT, extLoc.storageKind)
        assertEquals("m1", extLoc.mountId)
        assertEquals("…/Work/Specs", extLoc.storageName)
        assertEquals("/storage/emulated/0/Docs/Work/Specs", extLoc.displayPath)

        val linuxLoc = items.getValue("linux:home")
        assertEquals(com.promenar.infinity.domain.files.FileLocationStorageKind.LINUX_HOME, linuxLoc.storageKind)
        assertEquals("/root", linuxLoc.displayPath)
    }

    private fun status(branch: String?) = GitStatus(branch = branch, detached = false, head = null, unborn = false,
        staged = GitChangeList.Empty, unstaged = GitChangeList.Empty, untracked = GitChangeList.Empty)
}
