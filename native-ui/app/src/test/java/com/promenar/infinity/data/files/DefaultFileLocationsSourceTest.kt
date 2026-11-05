package com.promenar.infinity.data.files

import com.promenar.infinity.data.git.GitChangeList
import com.promenar.infinity.data.git.GitInspectError
import com.promenar.infinity.data.git.GitInspectResult
import com.promenar.infinity.data.git.GitStatus
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.workspace.SessionWorkspace
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

    private fun status(branch: String?) = GitStatus(branch = branch, detached = false, head = "abc", unborn = false,
        staged = GitChangeList.Empty, unstaged = GitChangeList.Empty, untracked = GitChangeList.Empty)
}
