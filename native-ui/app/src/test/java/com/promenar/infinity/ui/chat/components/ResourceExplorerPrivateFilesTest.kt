package com.promenar.infinity.ui.chat.components

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.workspace.DirectoryBatchResult
import com.promenar.infinity.domain.workspace.DirectoryFileOpCode
import com.promenar.infinity.domain.workspace.DirectoryFileOpException
import com.promenar.infinity.domain.workspace.PrivateWorkspaceFiles
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ResourceExplorerPrivateFilesTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val docs = entry("docs", "/docs", directory = true)
    private val note = entry("note", "/docs/note.md")
    private val readme = entry("readme", "/readme.md")

    private fun viewModel(
        files: PrivateWorkspaceFiles?,
        repo: IWorkspaceRepository = repo(),
    ) = ResourceExplorerViewModel(
        ApplicationProvider.getApplicationContext<Application>(),
        repo,
        injectedPrivateFiles = files,
        io = dispatcher,
    )

    private fun repo(vararg sessions: String = arrayOf("s")) = mockk<IWorkspaceRepository> {
        sessions.forEach { session -> coEvery { ensureSessionRoot(session) } returns root("root-$session") }
        every { observeChildren(any(), any()) } returns flowOf(emptyList())
    }

    @Test
    fun `长按切换选择后剪切进入剪贴板并按物化路径判定可粘贴目标`() = runTest(dispatcher) {
        val vm = viewModel(mockk())
        vm.loadSession("s")
        advanceUntilIdle()
        assertThat(vm.privateFileOpsAvailable).isTrue()

        vm.togglePrivateSelection(docs)
        vm.togglePrivateSelection(readme)
        vm.togglePrivateSelection(readme)
        assertThat(vm.privateFiles.value.selection.keys).containsExactly("docs")

        vm.cutPrivateSelection()
        val state = vm.privateFiles.value
        assertThat(state.selection).isEmpty()
        val clipboard = checkNotNull(state.clipboard)
        assertThat(clipboard.uuids).containsExactly("docs")
        // 原目录、自身与子目录都不能作为粘贴目标。
        assertThat(clipboard.canPasteInto("/")).isFalse()
        assertThat(clipboard.canPasteInto("/docs")).isFalse()
        assertThat(clipboard.canPasteInto("/docs/inner")).isFalse()
        assertThat(clipboard.canPasteInto("/archive")).isTrue()
    }

    @Test
    fun `粘贴逐项移动并在部分失败时只保留失败条目`() = runTest(dispatcher) {
        val files = mockk<PrivateWorkspaceFiles>()
        coEvery { files.move("s", listOf("note", "readme"), "archive") } returns
            DirectoryBatchResult(listOf("note"), mapOf("readme" to DirectoryFileOpCode.NAME_EXISTS))
        val vm = viewModel(files)
        vm.loadSession("s")
        advanceUntilIdle()
        vm.togglePrivateSelection(note)
        vm.togglePrivateSelection(readme)
        vm.cutPrivateSelection()

        vm.pastePrivateClipboard("archive")
        assertThat(vm.privateFiles.value.operating).isTrue()
        advanceUntilIdle()

        val state = vm.privateFiles.value
        assertThat(state.operating).isFalse()
        assertThat(state.outcome).isEqualTo(DirectoryOpOutcome(DirectoryOp.MOVE, 1, 1, DirectoryFileOpCode.NAME_EXISTS))
        assertThat(state.clipboard?.uuids).containsExactly("readme")
        assertThat(state.clipboard?.paths).containsExactly("/readme.md")
    }

    @Test
    fun `全部移动成功后清空剪贴板`() = runTest(dispatcher) {
        val files = mockk<PrivateWorkspaceFiles>()
        coEvery { files.move("s", listOf("note"), "root-s") } returns DirectoryBatchResult(listOf("note"), emptyMap())
        val vm = viewModel(files)
        vm.loadSession("s")
        advanceUntilIdle()
        vm.togglePrivateSelection(note)
        vm.cutPrivateSelection()
        vm.pastePrivateClipboard("root-s")
        advanceUntilIdle()
        assertThat(vm.privateFiles.value.clipboard).isNull()
        assertThat(vm.privateFiles.value.outcome).isEqualTo(DirectoryOpOutcome(DirectoryOp.MOVE, 1, 0, null))
    }

    @Test
    fun `永久删除所选条目并清空选择`() = runTest(dispatcher) {
        val files = mockk<PrivateWorkspaceFiles>()
        coEvery { files.delete("s", listOf("docs", "readme")) } returns DirectoryBatchResult(listOf("docs", "readme"), emptyMap())
        val vm = viewModel(files)
        vm.loadSession("s")
        advanceUntilIdle()
        vm.togglePrivateSelection(docs)
        vm.togglePrivateSelection(readme)
        vm.deletePrivateSelection()
        advanceUntilIdle()
        val state = vm.privateFiles.value
        assertThat(state.selection).isEmpty()
        assertThat(state.outcome).isEqualTo(DirectoryOpOutcome(DirectoryOp.DELETE, 2, 0, null))
        coVerify(exactly = 1) { files.delete("s", listOf("docs", "readme")) }
    }

    @Test
    fun `改名把失败原因交给对话框成功时清空选择`() = runTest(dispatcher) {
        val files = mockk<PrivateWorkspaceFiles>()
        coEvery { files.rename("s", "readme", "taken.md") } throws DirectoryFileOpException(DirectoryFileOpCode.NAME_EXISTS)
        coEvery { files.rename("s", "readme", "broken.md") } throws IllegalStateException("磁盘错误")
        coEvery { files.rename("s", "readme", "ok.md") } returns Unit
        val vm = viewModel(files)
        vm.loadSession("s")
        advanceUntilIdle()
        vm.togglePrivateSelection(readme)
        val results = mutableListOf<DirectoryFileOpCode?>()

        vm.renamePrivateEntry("readme", "taken.md") { results += it }
        advanceUntilIdle()
        vm.renamePrivateEntry("readme", "broken.md") { results += it }
        advanceUntilIdle()
        assertThat(vm.privateFiles.value.selection.keys).containsExactly("readme")
        vm.renamePrivateEntry("readme", "ok.md") { results += it }
        advanceUntilIdle()

        assertThat(results).containsExactly(DirectoryFileOpCode.NAME_EXISTS, DirectoryFileOpCode.IO, null).inOrder()
        assertThat(vm.privateFiles.value.selection).isEmpty()
        assertThat(vm.privateFiles.value.operating).isFalse()
    }

    @Test
    fun `操作进行中再次发起的操作被忽略`() = runTest(dispatcher) {
        val files = mockk<PrivateWorkspaceFiles>()
        val gate = CompletableDeferred<Unit>()
        coEvery { files.delete("s", any()) } coAnswers {
            gate.await()
            DirectoryBatchResult(listOf("readme"), emptyMap())
        }
        val vm = viewModel(files)
        vm.loadSession("s")
        advanceUntilIdle()
        vm.togglePrivateSelection(readme)
        vm.deletePrivateSelection()
        advanceUntilIdle()
        vm.togglePrivateSelection(note)
        vm.deletePrivateSelection()
        val renamed = mutableListOf<DirectoryFileOpCode?>()
        vm.renamePrivateEntry("note", "x.md") { renamed += it }
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { files.delete("s", any()) }
        coVerify(exactly = 0) { files.rename(any(), any(), any()) }
        assertThat(renamed).isEmpty()
    }

    @Test
    fun `切换会话清空选择与剪贴板且旧会话的结果不写入新会话`() = runTest(dispatcher) {
        val files = mockk<PrivateWorkspaceFiles>()
        val gate = CompletableDeferred<Unit>()
        coEvery { files.delete("a", any()) } coAnswers {
            gate.await()
            DirectoryBatchResult(emptyList(), mapOf("readme" to DirectoryFileOpCode.IO))
        }
        val vm = viewModel(files, repo("a", "b"))
        vm.loadSession("a")
        advanceUntilIdle()
        vm.togglePrivateSelection(note)
        vm.cutPrivateSelection()
        vm.togglePrivateSelection(readme)
        vm.deletePrivateSelection()
        advanceUntilIdle()

        vm.loadSession("b")
        advanceUntilIdle()
        assertThat(vm.privateFiles.value).isEqualTo(PrivateFileOpsState())
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.privateFiles.value).isEqualTo(PrivateFileOpsState())
    }

    @Test
    fun `未装配私有文件操作时界面保持只读且改名直接报告不可用`() = runTest(dispatcher) {
        val vm = viewModel(null)
        vm.loadSession("s")
        advanceUntilIdle()
        assertThat(vm.privateFileOpsAvailable).isFalse()
        var code: DirectoryFileOpCode? = null
        vm.renamePrivateEntry("readme", "x.md") { code = it }
        assertThat(code).isEqualTo(DirectoryFileOpCode.WORKSPACE_UNAVAILABLE)
    }

    private fun root(uuid: String) = FileEntry(
        uuid = uuid,
        workspaceRootUuid = uuid,
        parentUuid = null,
        name = "root",
        hash = "",
        isDirectory = true,
        physicalRootPath = "/tmp/$uuid",
        materializedPath = "/",
        createdAt = 1,
        updatedAt = 1,
    )

    private fun entry(uuid: String, path: String, directory: Boolean = false) = FileEntry(
        uuid = uuid,
        workspaceRootUuid = "root-s",
        parentUuid = "root-s",
        name = path.substringAfterLast('/'),
        hash = if (directory) "" else uuid,
        isDirectory = directory,
        physicalRootPath = "/tmp/root-s",
        materializedPath = path,
        createdAt = 1,
        updatedAt = 1,
    )
}
