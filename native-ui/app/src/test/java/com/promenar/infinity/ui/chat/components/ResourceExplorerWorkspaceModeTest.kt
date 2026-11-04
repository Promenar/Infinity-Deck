package com.promenar.infinity.ui.chat.components

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.storage.JvmBoundRealPathFileSystem
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.workspace.DirectoryBatchResult
import com.promenar.infinity.domain.workspace.DirectoryFileOpCode
import com.promenar.infinity.domain.workspace.DirectoryFileOpException
import com.promenar.infinity.domain.workspace.DirectoryWorkspaceFiles
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 资源管理器文件区按会话绑定的工作区（私有 / 目录 / 不可用）加载。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ResourceExplorerWorkspaceModeTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `private workspace keeps room observers`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val files = listOf(file("a.txt"))
        coEvery { repo.ensureSessionRoot("s") } returns root("root")
        every { repo.observeChildren("root", "root") } returns flowOf(files)
        val access = FakeAccess(SessionWorkspace.Private)
        val viewModel = viewModel(repo, access)

        viewModel.loadSession("s")
        advanceUntilIdle()

        assertThat(viewModel.workspaceMode.value).isEqualTo(ResourceExplorerWorkspaceMode.Private)
        assertThat(viewModel.workspaceRootUuid.value).isEqualTo("root")
        assertThat(viewModel.rootFiles.value).isEqualTo(files)
        assertThat(viewModel.directory.value).isEqualTo(DirectoryBrowserState())
        assertThat(access.backendRequests).isEqualTo(0)
    }

    @Test
    fun `directory workspace lists bound folder through validated backend without private root`() = runTest(dispatcher) {
        val folder = temporaryFolder.newFolder("p4-accept").canonicalFile
        File(folder, "notes.md").writeText("# 笔记\n内容")
        File(folder, "src").mkdir()
        File(folder, "src/main.kt").writeText("fun main() {}")
        File(folder, ".infinity-write-tmp").writeText("internal")
        val outside = temporaryFolder.newFolder("outside").canonicalFile
        File(outside, "secret.txt").writeText("outside")
        Files.createSymbolicLink(File(folder, "link").toPath(), outside.toPath())
        val repo = mockk<IWorkspaceRepository>(relaxed = true)
        val access = FakeAccess(directory("ws1", "p4-accept", folder)) { JvmBoundRealPathFileSystem().backend(it.root) }
        val viewModel = viewModel(repo, access)

        viewModel.loadSession("s")
        settle { !viewModel.directory.value.isLoading && viewModel.directory.value.entries.isNotEmpty() }

        assertThat(viewModel.workspaceMode.value).isEqualTo(
            ResourceExplorerWorkspaceMode.Directory("ws1", "p4-accept", WorkspaceEntity.KIND_PROJECT, location = "p4-accept"),
        )
        // 符号链接与内部临时文件不出现在列表中，与 Agent 文件工具使用同一目录后端规则。
        assertThat(viewModel.directory.value.entries.map(ExternalFolderEntry::name)).containsExactly("src", "notes.md").inOrder()
        assertThat(viewModel.workspaceRootUuid.value).isNull()
        assertThat(viewModel.rootFiles.value).isEmpty()
        coVerify(exactly = 0) { repo.ensureSessionRoot(any()) }

        viewModel.openDirectory("/src")
        settle { viewModel.directory.value.path == "/src" && !viewModel.directory.value.isLoading }
        assertThat(viewModel.directory.value.entries.map(ExternalFolderEntry::path)).containsExactly("/src/main.kt")

        viewModel.openDirectory("/")
        settle { viewModel.directory.value.path == "/" && !viewModel.directory.value.isLoading }
        val notes = viewModel.directory.value.entries.single { it.name == "notes.md" }
        viewModel.openDirectoryFile(notes)
        settle { viewModel.directory.value.preview !is ExternalTextPreview.Loading }
        assertThat(viewModel.directory.value.selectedFile).isEqualTo(notes)
        assertThat((viewModel.directory.value.preview as ExternalTextPreview.Text).content).isEqualTo("# 笔记\n内容")

        val escaped = runCatching { viewModel.readDirectoryFile("/link/secret.txt", 1024) }.exceptionOrNull()
        assertThat((escaped as ExternalFolderException).code).isEqualTo("INVALID_PATH")
        viewModel.openDirectory("/link")
        settle { viewModel.directory.value.path == "/link" && !viewModel.directory.value.isLoading }
        assertThat(viewModel.directory.value.entries).isEmpty()
        assertThat(viewModel.directory.value.error?.reason).isNotNull()
    }

    @Test
    fun `unavailable workspace exposes reason and never falls back to private workspace`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>(relaxed = true)
        val workspace = WorkspaceEntity("ws1", WorkspaceEntity.KIND_SHARED, "Docs", "/storage/emulated/0/Docs", 1)
        val access = FakeAccess(SessionWorkspace.Unavailable("ws1", workspace, "需要“所有文件访问”授权"))
        val viewModel = viewModel(repo, access)

        viewModel.loadSession("s")
        advanceUntilIdle()

        assertThat(viewModel.workspaceMode.value).isEqualTo(
            ResourceExplorerWorkspaceMode.Unavailable(
                "ws1", "Docs", "需要“所有文件访问”授权",
                kind = WorkspaceEntity.KIND_SHARED, location = "/storage/emulated/0/Docs",
            ),
        )
        assertThat(viewModel.isLoading.value).isFalse()
        assertThat(viewModel.loadError.value).isNull()
        assertThat(viewModel.workspaceRootUuid.value).isNull()
        assertThat(viewModel.directory.value.entries).isEmpty()
        assertThat(access.backendRequests).isEqualTo(0)
        coVerify(exactly = 0) { repo.ensureSessionRoot(any()) }
        coVerify(exactly = 0) { repo.observeChildren(any(), any()) }
    }

    @Test
    fun `rebinding private session to directory stops private observers on reopen`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val files = MutableSharedFlow<List<FileEntry>>()
        coEvery { repo.ensureSessionRoot("s") } returns root("root")
        every { repo.observeChildren("root", "root") } returns files
        val backend = FakeBackend(mapOf("/" to listOf(entry("/notes.md"))))
        val access = FakeAccess(SessionWorkspace.Private) { backend }
        val viewModel = viewModel(repo, access)

        viewModel.loadSession("s")
        advanceUntilIdle()
        assertThat(files.subscriptionCount.value).isEqualTo(1)

        access.workspace = directory("ws1", "p4-accept", temporaryFolder.newFolder())
        viewModel.deactivateSession()
        viewModel.loadSession("s")
        advanceUntilIdle()

        assertThat(files.subscriptionCount.value).isEqualTo(0)
        assertThat(viewModel.workspaceRootUuid.value).isNull()
        assertThat(viewModel.workspaceMode.value).isInstanceOf(ResourceExplorerWorkspaceMode.Directory::class.java)
        assertThat(viewModel.directory.value.entries.map(ExternalFolderEntry::name)).containsExactly("notes.md")
    }

    @Test
    fun `directory that becomes unavailable while browsing switches to unavailable state`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>(relaxed = true)
        val backend = FakeBackend(mapOf("/" to listOf(entry("/src", isDirectory = true)), "/src" to emptyList()))
        val access = FakeAccess(directory("ws1", "p4-accept", temporaryFolder.newFolder())) { backend }
        val viewModel = viewModel(repo, access)
        viewModel.loadSession("s")
        advanceUntilIdle()
        assertThat(viewModel.directory.value.entries).hasSize(1)

        access.workspace = SessionWorkspace.Unavailable("ws1", null, "项目文件夹不存在")
        viewModel.openDirectory("/src")
        advanceUntilIdle()

        assertThat(viewModel.workspaceMode.value).isEqualTo(
            ResourceExplorerWorkspaceMode.Unavailable("ws1", null, "项目文件夹不存在"),
        )
        assertThat(viewModel.directory.value).isEqualTo(DirectoryBrowserState())
        assertThat(backend.listed).containsExactly("/")
        coVerify(exactly = 0) { repo.ensureSessionRoot(any()) }
    }

    @Test
    fun `resolution failure reports load error without showing any workspace`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>(relaxed = true)
        val access = FakeAccess(SessionWorkspace.Private).apply { failure = IllegalStateException("db closed") }
        val viewModel = viewModel(repo, access)

        viewModel.loadSession("s")
        advanceUntilIdle()

        assertThat(viewModel.workspaceMode.value).isEqualTo(ResourceExplorerWorkspaceMode.Resolving)
        assertThat(viewModel.loadError.value?.code).isEqualTo(ResourceExplorerNotice.CODE_WORKSPACE_LOAD_FAILED)
        assertThat(viewModel.isLoading.value).isFalse()
        coVerify(exactly = 0) { repo.ensureSessionRoot(any()) }

        access.failure = null
        viewModel.retryLoadSession()
        advanceUntilIdle()
        assertThat(viewModel.workspaceMode.value).isEqualTo(ResourceExplorerWorkspaceMode.Private)
        coVerify(exactly = 1) { repo.ensureSessionRoot("s") }
    }

    @Test
    fun `parent directory path stops at workspace root`() {
        assertThat(parentDirectoryPath("/")).isNull()
        assertThat(parentDirectoryPath("/src")).isEqualTo("/")
        assertThat(parentDirectoryPath("/src/main")).isEqualTo("/src")
    }

    @Test
    fun `clipboard refuses pasting an entry into itself or its descendants`() {
        val copy = DirectoryClipboard(listOf("/src", "/notes.md"), cut = false)
        assertThat(copy.canPasteInto("/src")).isFalse()
        assertThat(copy.canPasteInto("/src/main")).isFalse()
        // 前缀相同的兄弟目录不算子目录；复制回原目录由后端自动改名。
        assertThat(copy.canPasteInto("/src2")).isTrue()
        assertThat(copy.canPasteInto("/")).isTrue()

        val cut = copy.copy(cut = true)
        assertThat(cut.canPasteInto("/")).isFalse()
        assertThat(cut.canPasteInto("/docs")).isTrue()
    }

    @Test
    fun `batch outcome counts results and keeps first failure reason`() {
        val result = DirectoryBatchResult(
            succeeded = listOf("/a"),
            failed = linkedMapOf("/b" to DirectoryFileOpCode.NAME_EXISTS, "/c" to DirectoryFileOpCode.IO),
        )
        assertThat(result.toOutcome(DirectoryOp.MOVE))
            .isEqualTo(DirectoryOpOutcome(DirectoryOp.MOVE, succeeded = 1, failed = 2, reason = DirectoryFileOpCode.NAME_EXISTS))
        assertThat(DirectoryBatchResult(listOf("/a"), emptyMap()).toOutcome(DirectoryOp.DELETE).reason).isNull()
    }

    @Test
    fun `cut and paste moves selection and keeps only failed entries on clipboard`() = runTest(dispatcher) {
        val backend = FakeBackend(
            mapOf(
                "/" to listOf(entry("/docs", isDirectory = true), entry("/a.md"), entry("/b.md")),
                "/docs" to emptyList(),
            ),
        )
        val files = FakeFiles().apply { moveResult = DirectoryBatchResult(listOf("/a.md"), mapOf("/b.md" to DirectoryFileOpCode.NAME_EXISTS)) }
        val viewModel = directoryViewModel(backend, files)

        viewModel.toggleDirectorySelection("/b.md")
        viewModel.toggleDirectorySelection("/a.md")
        viewModel.copyDirectorySelection(cut = true)
        // 剪贴板按列表顺序记录，选择随之清空。
        assertThat(viewModel.directory.value.clipboard).isEqualTo(DirectoryClipboard(listOf("/a.md", "/b.md"), cut = true))
        assertThat(viewModel.directory.value.selection).isEmpty()

        viewModel.pasteDirectoryClipboard()
        advanceUntilIdle()
        assertThat(files.calls).isEmpty()

        viewModel.openDirectory("/docs")
        advanceUntilIdle()
        viewModel.pasteDirectoryClipboard()
        advanceUntilIdle()

        assertThat(files.calls).containsExactly("move s [/a.md, /b.md] -> /docs")
        val state = viewModel.directory.value
        assertThat(state.operating).isFalse()
        assertThat(state.outcome).isEqualTo(DirectoryOpOutcome(DirectoryOp.MOVE, 1, 1, DirectoryFileOpCode.NAME_EXISTS))
        assertThat(state.clipboard).isEqualTo(DirectoryClipboard(listOf("/b.md"), cut = true))
        assertThat(backend.listed.last()).isEqualTo("/docs")
    }

    @Test
    fun `delete failure reports reason and rename result reaches dialog callback`() = runTest(dispatcher) {
        val backend = FakeBackend(mapOf("/" to listOf(entry("/a.md"))))
        val files = FakeFiles().apply {
            deleteFailure = DirectoryFileOpException(DirectoryFileOpCode.SESSION_BUSY)
            renameFailure = DirectoryFileOpException(DirectoryFileOpCode.NAME_EXISTS)
        }
        val viewModel = directoryViewModel(backend, files)

        viewModel.toggleDirectorySelection("/a.md")
        viewModel.deleteDirectorySelection()
        advanceUntilIdle()
        assertThat(viewModel.directory.value.outcome)
            .isEqualTo(DirectoryOpOutcome(DirectoryOp.DELETE, 0, 1, DirectoryFileOpCode.SESSION_BUSY))
        assertThat(viewModel.directory.value.selection).isEmpty()

        val results = mutableListOf<DirectoryFileOpCode?>()
        viewModel.toggleDirectorySelection("/a.md")
        viewModel.renameDirectoryEntry("/a.md", "b.md") { results += it }
        advanceUntilIdle()
        // 失败时保留选择，用户可在对话框里改名重试。
        assertThat(results).containsExactly(DirectoryFileOpCode.NAME_EXISTS)
        assertThat(viewModel.directory.value.selection).containsExactly("/a.md")

        files.renameFailure = null
        viewModel.renameDirectoryEntry("/a.md", "b.md") { results += it }
        viewModel.createDirectoryFolder("new") { results += it }
        advanceUntilIdle()
        // 前一项进行中时新的单项操作被忽略，不会并发写同一目录。
        assertThat(results).containsExactly(DirectoryFileOpCode.NAME_EXISTS, null).inOrder()
        assertThat(viewModel.directory.value.selection).isEmpty()
        assertThat(files.calls).containsExactly("delete s [/a.md]", "rename s /a.md -> b.md", "rename s /a.md -> b.md").inOrder()
    }

    @Test
    fun `删除批次部分失败时展示实际结果`() = runTest(dispatcher) {
        val backend = FakeBackend(mapOf("/" to listOf(entry("/big", isDirectory = true), entry("/a.md"))))
        val files = FakeFiles().apply {
            deleteResult = { paths ->
                DirectoryBatchResult(paths - "/big", paths.filter { it == "/big" }.associateWith { DirectoryFileOpCode.TOO_LARGE })
            }
        }
        val viewModel = directoryViewModel(backend, files)

        viewModel.toggleDirectorySelection("/big")
        viewModel.toggleDirectorySelection("/a.md")
        viewModel.deleteDirectorySelection()
        advanceUntilIdle()
        assertThat(viewModel.directory.value.outcome)
            .isEqualTo(DirectoryOpOutcome(DirectoryOp.DELETE, 1, 1, DirectoryFileOpCode.TOO_LARGE))
    }

    private fun TestScope.directoryViewModel(backend: FakeBackend, files: DirectoryWorkspaceFiles): ResourceExplorerViewModel {
        val access = FakeAccess(directory("ws1", "p4-accept", temporaryFolder.newFolder())) { backend }
        return ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            injectedWorkspaceRepository = mockk(relaxed = true),
            injectedImporter = mockk(relaxed = true),
            injectedWorkspaceAccess = access,
            injectedDirectoryFiles = files,
            io = dispatcher,
        ).also {
            it.loadSession("s")
            advanceUntilIdle()
        }
    }

    private class FakeFiles : DirectoryWorkspaceFiles {
        val calls = mutableListOf<String>()
        var moveResult = DirectoryBatchResult(emptyList(), emptyMap())
        var deleteFailure: DirectoryFileOpException? = null
        var renameFailure: DirectoryFileOpException? = null
        var deleteResult: (List<String>) -> DirectoryBatchResult = { DirectoryBatchResult(it, emptyMap()) }
        override suspend fun createDirectory(sessionId: String, parent: String, name: String): ExternalFolderEntry {
            calls += "mkdir $sessionId $parent/$name"
            return ExternalFolderEntry("$parent/$name", name, true, 0, 0)
        }
        override suspend fun rename(sessionId: String, path: String, newName: String): ExternalFolderEntry {
            calls += "rename $sessionId $path -> $newName"
            renameFailure?.let { throw it }
            return ExternalFolderEntry("/$newName", newName, false, 0, 0)
        }
        override suspend fun move(sessionId: String, paths: List<String>, targetDirectory: String): DirectoryBatchResult {
            calls += "move $sessionId $paths -> $targetDirectory"
            return moveResult
        }
        override suspend fun copy(sessionId: String, paths: List<String>, targetDirectory: String): DirectoryBatchResult {
            calls += "copy $sessionId $paths -> $targetDirectory"
            return DirectoryBatchResult(paths, emptyMap())
        }
        override suspend fun delete(sessionId: String, paths: List<String>): DirectoryBatchResult {
            calls += "delete $sessionId $paths"
            deleteFailure?.let { throw it }
            return deleteResult(paths)
        }
    }

    private fun TestScope.viewModel(repo: IWorkspaceRepository, access: ResourceExplorerWorkspaceAccess) =
        ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            injectedWorkspaceRepository = repo,
            injectedImporter = mockk(relaxed = true),
            injectedWorkspaceAccess = access,
            io = dispatcher,
        )

    /** 真实目录后端在 Dispatchers.IO 上执行，需等待其回到测试调度器。 */
    private fun TestScope.settle(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            advanceUntilIdle()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "等待目录状态超时" }
            Thread.sleep(10)
        }
    }

    private class FakeAccess(
        var workspace: SessionWorkspace,
        private val backendFor: (SessionWorkspace.Directory) -> ExternalFolderBackend = { error("未提供目录后端") },
    ) : ResourceExplorerWorkspaceAccess {
        var failure: Exception? = null
        var backendRequests = 0
        override suspend fun resolve(sessionId: String): SessionWorkspace {
            failure?.let { throw it }
            return workspace
        }
        override fun backend(directory: SessionWorkspace.Directory): ExternalFolderBackend {
            backendRequests++
            return backendFor(directory)
        }
    }

    private class FakeBackend(private val children: Map<String, List<ExternalFolderEntry>>) : ExternalFolderBackend {
        val listed = mutableListOf<String>()
        override suspend fun stat(path: String): ExternalFolderEntry? = null
        override suspend fun list(path: String): List<ExternalFolderEntry> {
            listed += path
            return children[path] ?: throw ExternalFolderException("NOT_FOUND", "目录不存在")
        }
        override suspend fun read(path: String, maxBytes: Long): ByteArray = ByteArray(0)
        override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean) =
            error("只读测试")
        override suspend fun createDirectory(path: String) = error("只读测试")
        override suspend fun delete(path: String, expectedSha256: String?) = error("只读测试")
    }

    private fun directory(id: String, name: String, root: File) = SessionWorkspace.Directory(
        WorkspaceEntity(id, WorkspaceEntity.KIND_PROJECT, name, name, 1),
        root,
    )

    private fun entry(path: String, isDirectory: Boolean = false) =
        ExternalFolderEntry(path, path.substringAfterLast('/'), isDirectory, 0, 0)

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

    private fun file(name: String) = FileEntry(
        uuid = name,
        workspaceRootUuid = "root",
        parentUuid = "root",
        name = name,
        hash = "",
        isDirectory = false,
        physicalRootPath = "/tmp/root",
        materializedPath = "/$name",
        createdAt = 1,
        updatedAt = 1,
    )
}
