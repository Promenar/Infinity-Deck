package com.promenar.infinity.ui.chat.components

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ResourceExplorerWorkspaceModeTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `绑定不可用时清除专用根且重试恢复目录`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        coEvery { repo.ensureSessionRoot("s") } returns root("private")
        val access = FakeAccess(SessionWorkspace.Private)
        val vm = viewModel(repo, access)
        vm.loadSession("s")
        advanceUntilIdle()
        assertThat(vm.workspaceRootUuid.value).isEqualTo("private")

        access.value = SessionWorkspace.Unavailable("ws", null, "目录不可用")
        vm.retryLoadSession()
        advanceUntilIdle()
        assertThat(vm.workspaceMode.value).isEqualTo(
            ResourceExplorerWorkspaceMode.Unavailable("ws", null, "目录不可用"),
        )
        assertThat(vm.workspaceRootUuid.value).isNull()
        coVerify(exactly = 1) { repo.ensureSessionRoot("s") }

        val workspace = WorkspaceEntity("ws", WorkspaceEntity.KIND_PROJECT, "Demo", "demo", 1)
        access.value = SessionWorkspace.Directory(workspace, File("/virtual/demo"))
        vm.retryLoadSession()
        advanceUntilIdle()
        assertThat(vm.workspaceMode.value).isEqualTo(
            ResourceExplorerWorkspaceMode.Directory("ws", "Demo", WorkspaceEntity.KIND_PROJECT, "demo"),
        )
        assertThat(vm.workspaceRootUuid.value).isNull()
        coVerify(exactly = 1) { repo.ensureSessionRoot("s") }
    }

    @Test
    fun `较晚完成的旧会话解析不能覆盖新会话`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        coEvery { repo.ensureSessionRoot("new") } returns root("new-root")
        val gate = CompletableDeferred<Unit>()
        val access = object : ResourceExplorerWorkspaceAccess {
            override suspend fun resolve(sessionId: String): SessionWorkspace = if (sessionId == "old") {
                withContext(NonCancellable) { gate.await() }
                SessionWorkspace.Unavailable("old-ws", null, "不可用")
            } else SessionWorkspace.Private
        }
        val vm = viewModel(repo, access)
        vm.loadSession("old")
        runCurrent()
        vm.loadSession("new")
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.workspaceMode.value).isEqualTo(ResourceExplorerWorkspaceMode.Private)
        assertThat(vm.workspaceRootUuid.value).isEqualTo("new-root")
    }

    @Test
    fun `绑定变化立即清除旧专用根而不等待解析`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        coEvery { repo.ensureSessionRoot("s") } returns root("private")
        val gate = CompletableDeferred<Unit>()
        var pending = false
        val access = object : ResourceExplorerWorkspaceAccess {
            override suspend fun resolve(sessionId: String): SessionWorkspace {
                if (pending) {
                    gate.await()
                    return SessionWorkspace.Unavailable("ws", null, "目录不可用")
                }
                return SessionWorkspace.Private
            }
        }
        val vm = viewModel(repo, access)
        vm.loadSession("s")
        advanceUntilIdle()
        assertThat(vm.workspaceRootUuid.value).isEqualTo("private")
        pending = true
        vm.onWorkspaceBindingChanged()
        assertThat(vm.workspaceRootUuid.value).isNull()
        assertThat(vm.workspaceMode.value).isEqualTo(ResourceExplorerWorkspaceMode.Resolving)
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.workspaceMode.value).isInstanceOf(ResourceExplorerWorkspaceMode.Unavailable::class.java)
    }

    @Test
    fun `解析失败可重试并保持安全的解析状态`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        coEvery { repo.ensureSessionRoot("s") } returns root("root")
        var fail = true
        val access = object : ResourceExplorerWorkspaceAccess {
            override suspend fun resolve(sessionId: String): SessionWorkspace {
                if (fail) throw IllegalStateException("unavailable")
                return SessionWorkspace.Private
            }
        }
        val vm = viewModel(repo, access)
        vm.loadSession("s")
        advanceUntilIdle()
        assertThat(vm.workspaceMode.value).isEqualTo(ResourceExplorerWorkspaceMode.Resolving)
        assertThat(vm.loadError.value?.code).isEqualTo(ResourceExplorerNotice.CODE_WORKSPACE_LOAD_FAILED)
        fail = false
        vm.retryLoadSession()
        advanceUntilIdle()
        assertThat(vm.workspaceMode.value).isEqualTo(ResourceExplorerWorkspaceMode.Private)
        assertThat(vm.loadError.value).isNull()
    }

    private fun viewModel(repo: IWorkspaceRepository, access: ResourceExplorerWorkspaceAccess) =
        ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            injectedWorkspaceRepository = repo,
            injectedWorkspaceAccess = access,
            io = dispatcher,
        )

    private class FakeAccess(var value: SessionWorkspace) : ResourceExplorerWorkspaceAccess {
        override suspend fun resolve(sessionId: String) = value
    }

    private fun root(id: String) = FileEntry(
        uuid = id, workspaceRootUuid = id, parentUuid = null, name = "root", hash = "",
        isDirectory = true, physicalRootPath = "/virtual/$id", materializedPath = "/",
        createdAt = 1, updatedAt = 1,
    )
}
