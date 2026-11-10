package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.repository.ReadResult
import com.promenar.infinity.domain.repository.ReadWindowRequest
import com.promenar.infinity.domain.repository.ReadWindowResult
import com.promenar.infinity.domain.repository.RenameIndexTarget
import com.promenar.infinity.domain.repository.RenameResult
import com.promenar.infinity.domain.repository.WriteResult
import com.promenar.infinity.infra.util.Sha256Utils
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WorkspaceToolBehaviorTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "session"
        override val agentId = "agent"
        override val workspacePath = "/workspace"
        override val workspaceRootUuid = "root"
    }

    private fun entry(uuid: String, path: String, directory: Boolean = false, parent: String? = "root") = FileEntry(
        uuid = uuid,
        workspaceRootUuid = "root",
        parentUuid = parent,
        name = path.substringAfterLast('/').ifEmpty { "/" },
        hash = "hash-$uuid",
        isDirectory = directory,
        physicalRootPath = "/physical",
        materializedPath = path,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private val root = entry("root", "/", directory = true, parent = null)

    @Test
    fun `search by name finds top level files and hides system entries`() = runTest {
        val workspace = mockk<IWorkspaceRepository>()
        every { workspace.searchByName("root", "note") } returns flowOf(
            listOf(
                entry("top", "/note.md"),
                entry("nested", "/docs/note-2.md"),
                entry("recycled", "/.recycle_bin/note.md"),
            ),
        )

        val result = FileSearchSkill(workspace).execute(skillArgs("query" to "note"), context)

        assertThat(result.content).contains("/note.md")
        assertThat(result.content).contains("/docs/note-2.md")
        assertThat(result.content).doesNotContain(".recycle_bin")
    }

    @Test
    fun `content search reports path and line numbers`() = runTest {
        val workspace = mockk<IWorkspaceRepository>()
        val operations = mockk<IFileOperationRepository>()
        every { workspace.observeChildren("root", "root") } returns flowOf(
            listOf(entry("docs", "/docs", directory = true), entry("a", "/a.md")),
        )
        every { workspace.observeChildren("root", "docs") } returns flowOf(listOf(entry("b", "/docs/b.md", parent = "docs")))
        coEvery { operations.readFileRange("root", "a", any(), any()) } returns
            ReadResult("a", "a.md", 2, 1, 2, "alpha\nTODO fix", "h", 0)
        coEvery { operations.readFileRange("root", "b", any(), any()) } returns
            ReadResult("b", "b.md", 1, 1, 1, "todo later", "h", 0)

        val result = FileSearchSkill(workspace, operations).execute(
            skillArgs("query" to "todo", "mode" to "content"),
            context,
        )

        assertThat(result.content).contains("/a.md:2: TODO fix")
        assertThat(result.content).contains("/docs/b.md:1: todo later")
    }

    @Test
    fun `unsupported search mode returns error instead of empty success`() = runTest {
        val result = FileSearchSkill(mockk()).execute(skillArgs("query" to "x", "mode" to "fts"), context)

        assertThat(result.status).isEqualTo("error")
    }

    @Test
    fun `read file numbers lines and resolves path`() = runTest {
        val workspace = mockk<IWorkspaceRepository>()
        val operations = mockk<IFileOperationRepository>()
        coEvery { workspace.getByMaterializedPath("root", "/docs/a.md") } returns entry("a", "/docs/a.md")
        coEvery { operations.readFileWindow("root", "a", ReadWindowRequest(startLine = 9, endLine = 10)) } returns
            ReadWindowResult("a", "a.md", 12, 9, 10, 0, "nine\nten", "hash-a", 0, nextLine = 11, nextLineByteOffset = 0)

        val result = FileReadSkill(operations, workspace).execute(
            skillArgs("path" to "docs/a.md", "mode" to "range", "startLine" to 9, "endLine" to 10),
            context,
        )

        assertThat(result.content).contains(" 9| nine\n10| ten")
        assertThat(result.content).contains("[uuid=a]")
        assertThat(result.content).contains("后续: mode=range startLine=11")
    }

    @Test
    fun `read file 分页参数换算为行窗口并提示超长行续读位置`() = runTest {
        val operations = mockk<IFileOperationRepository>()
        coEvery { operations.readFileWindow("root", "a", ReadWindowRequest(startLine = 5, endLine = 2004, lineByteOffset = 100)) } returns
            ReadWindowResult("a", "a.log", 9, 5, 5, 100, "片段", "hash-a", 0, nextLine = 5, nextLineByteOffset = 300)

        val result = FileReadSkill(operations).execute(
            skillArgs("uuid" to "a", "offset" to 4, "limit" to 9999, "lineByteOffset" to 100),
            context,
        )

        assertThat(result.status).isNotEqualTo("error")
        assertThat(result.content).contains("第 5 行自字节 100 起")
        assertThat(result.content).contains("后续: mode=range startLine=5 lineByteOffset=300")
        assertThat(result.content).contains("5| 片段")
    }

    @Test
    fun `read file 起始行超出末尾时说明总行数`() = runTest {
        val operations = mockk<IFileOperationRepository>()
        coEvery { operations.readFileWindow("root", "a", any()) } returns
            ReadWindowResult("a", "a.md", 3, 50, 49, 0, "", "hash-a", 0, nextLine = null, nextLineByteOffset = 0)

        val result = FileReadSkill(operations).execute(skillArgs("uuid" to "a", "mode" to "range", "startLine" to 50), context)

        assertThat(result.content).contains("后续: 已到文件末尾")
        assertThat(result.content).contains("起始行 50 超出文件总行数 3")
    }

    @Test
    fun `read file rejects inverted range before touching repository`() = runTest {
        val operations = mockk<IFileOperationRepository>()

        val result = FileReadSkill(operations).execute(
            skillArgs("uuid" to "a", "mode" to "range", "startLine" to 5, "endLine" to 2),
            context,
        )

        assertThat(result.status).isEqualTo("error")
        coVerify(exactly = 0) { operations.readFileWindow(any(), any(), any()) }
    }

    @Test
    fun `create file under parent path writes initial content through versioned write`() = runTest {
        val workspace = mockk<IWorkspaceRepository>()
        val operations = mockk<IFileOperationRepository>()
        val docs = entry("docs", "/docs", directory = true)
        coEvery { workspace.getByMaterializedPath("root", "/docs") } returns docs
        coEvery {
            workspace.createFileInWorkspace("root", any(), "plan.md", "", "docs", "/docs/plan.md")
        } answers { entry(secondArg(), "/docs/plan.md", parent = "docs").copy(hash = Sha256Utils.hash("")) }
        coEvery {
            operations.writeFileAtomic("root", any(), "# Plan", "session", Sha256Utils.hash(""))
        } returns WriteResult.Success("new-hash")

        val result = CreateFileSkill(workspace, operations).execute(
            skillArgs("name" to "plan.md", "parentPath" to "/docs", "content" to "# Plan"),
            context,
        )

        assertThat(result.status).isEqualTo("success")
        assertThat(result.content).contains("/docs/plan.md")
        assertThat(result.content).contains("new-hash")
    }

    @Test
    fun `create file rejects invalid names and existing paths`() = runTest {
        val workspace = mockk<IWorkspaceRepository>()
        coEvery { workspace.getByUuid("root", "root") } returns root
        coEvery { workspace.createFileInWorkspace(any(), any(), "dup.md", any(), any(), any()) } throws
            IllegalStateException("工作区文件已存在: /dup.md")
        val skill = CreateFileSkill(workspace, mockk())

        val invalid = skill.execute(skillArgs("name" to "../x"), context)
        val duplicate = skill.execute(skillArgs("name" to "dup.md"), context)

        assertThat(invalid.status).isEqualTo("error")
        assertThat(duplicate.status).isEqualTo("error")
        assertThat(duplicate.content).contains("已存在")
    }

    @Test
    fun `move file moves then renames and publishes rename index targets`() = runTest {
        val workspace = mockk<IWorkspaceRepository>()
        val sink = mockk<FileIndexEventSink>(relaxed = true)
        val file = entry("a", "/a.md")
        val archive = entry("arch", "/archive", directory = true)
        coEvery { workspace.getByMaterializedPath("root", "/a.md") } returns file
        coEvery { workspace.getByMaterializedPath("root", "/archive") } returns archive
        coEvery { workspace.updateParent("root", "a", "arch") } returns Unit
        coEvery { workspace.rename("root", "a", "b.md", "a.md") } returns RenameResult.Success(
            "b.md", "h2", 7L, true, listOf(RenameIndexTarget("a", "h2", 7L)),
        )
        coEvery { workspace.getByUuid("root", "a") } returns entry("a", "/archive/b.md", parent = "arch")

        val result = MoveFileSkill(workspace, sink).execute(
            skillArgs("path" to "/a.md", "newParentPath" to "/archive", "newName" to "b.md"),
            context,
        )

        assertThat(result.status).isEqualTo("success")
        assertThat(result.content).contains("/archive/b.md")
        coVerify { sink.publish(FileIndexEvent.Changed("root", "a", "h2", 7L)) }
    }

    @Test
    fun `move directory into its own subtree is rejected`() = runTest {
        val workspace = mockk<IWorkspaceRepository>()
        coEvery { workspace.getByMaterializedPath("root", "/docs") } returns entry("docs", "/docs", directory = true)
        coEvery { workspace.getByMaterializedPath("root", "/docs/sub") } returns
            entry("sub", "/docs/sub", directory = true, parent = "docs")

        val result = MoveFileSkill(workspace, mockk()).execute(
            skillArgs("path" to "/docs", "newParentPath" to "/docs/sub"),
            context,
        )

        assertThat(result.status).isEqualTo("error")
        coVerify(exactly = 0) { workspace.updateParent(any(), any(), any()) }
    }

    @Test
    fun `delete file permanently removes the entry and never deletes the root`() = runTest {
        val workspace = mockk<IWorkspaceRepository>()
        coEvery { workspace.getByMaterializedPath("root", "/a.md") } returns entry("a", "/a.md")
        coEvery { workspace.getByMaterializedPath("root", "/") } returns root
        coEvery { workspace.permanentDelete("root", "a") } returns Unit
        val skill = DeleteFileSkill(workspace)

        val deleted = skill.execute(skillArgs("path" to "/a.md"), context)
        val rootAttempt = skill.execute(skillArgs("path" to "/"), context)

        assertThat(deleted.status).isEqualTo("success")
        assertThat(rootAttempt.status).isEqualTo("error")
        coVerify(exactly = 1) { workspace.permanentDelete(any(), any()) }
    }

    @Test
    fun `security rejection from repository is reported without physical paths`() = runTest {
        val workspace = mockk<IWorkspaceRepository>()
        coEvery { workspace.getByMaterializedPath("root", "/.infinity_versions") } throws SecurityException("/physical/x")

        val result = DeleteFileSkill(workspace).execute(skillArgs("path" to "/.infinity_versions"), context)

        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).doesNotContain("/physical")
    }

    @Test
    fun `write to a missing path points the model to create_file`() = runTest {
        val workspace = mockk<IWorkspaceRepository>()
        coEvery { workspace.getByMaterializedPath("root", "/mnt/termux/new.txt") } returns null

        val result = FileWriteSkill(mockk(), workspace).execute(
            skillArgs("path" to "/mnt/termux/new.txt", "content" to "x", "expectedHash" to "h"),
            context,
        )

        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("未找到条目：/mnt/termux/new.txt")
        assertThat(result.content).contains("新建文件请用 create_file")
    }

    @Test
    fun `missing path guidance depends on the operation and argument`() = runTest {
        val workspace = mockk<IWorkspaceRepository>()
        coEvery { workspace.getByMaterializedPath("root", any()) } returns null
        coEvery { workspace.getByUuid("root", "gone") } returns null

        val patch = FilePatchSkill(mockk(), workspace).execute(skillArgs("path" to "/a.md", "edits" to "[]"), context)
        val read = FileReadSkill(mockk(), workspace).execute(skillArgs("path" to "/a.md"), context)
        val byUuid = DeleteFileSkill(workspace).execute(skillArgs("uuid" to "gone"), context)
        val create = CreateFileSkill(workspace, mockk()).execute(skillArgs("name" to "a.md", "parentPath" to "/docs"), context)

        assertThat(patch.content).contains("新建文件请用 create_file")
        assertThat(read.content).contains("list_files")
        assertThat(read.content).doesNotContain("create_file")
        assertThat(byUuid.content).contains("uuid 可能已失效")
        assertThat(create.content).contains("父目录不存在")
        assertThat(create.content).contains("create_directory")
    }
}
