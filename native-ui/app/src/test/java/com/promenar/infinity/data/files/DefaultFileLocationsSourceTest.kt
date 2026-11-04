package com.promenar.infinity.data.files

import com.promenar.infinity.data.git.GitChangeList
import com.promenar.infinity.data.git.GitInspectError
import com.promenar.infinity.data.git.GitInspectResult
import com.promenar.infinity.data.git.GitStatus
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.workspace.SessionWorkspace
import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultFileLocationsSourceTest {
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
}
