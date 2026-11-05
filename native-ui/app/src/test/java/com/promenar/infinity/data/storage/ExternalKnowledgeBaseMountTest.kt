package com.promenar.infinity.data.storage

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.branch.BranchLinuxWorkspaceOperation
import com.promenar.infinity.data.local.db.dao.ExternalMountDao
import com.promenar.infinity.data.local.db.entity.ExternalMountEntity
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalMount
import io.mockk.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

class ExternalKnowledgeBaseMountTest {
    private val temporary = Files.createTempDirectory("knowledge-mount").toFile().canonicalFile
    private val root = File(temporary, "knowledge").apply { mkdirs() }
    private val rows = MutableStateFlow(emptyList<ExternalMountEntity>())
    private val dao = mockk<ExternalMountDao> {
        coEvery { visible(any()) } answers { rows.value.filter { it.scopeKey == firstArg<String>() } }
        every { observeVisible(any()) } answers { val id = firstArg<String>(); rows.map { entries -> entries.filter { it.scopeKey == id } } }
        coEvery { all() } answers { rows.value }
        coEvery { insert(any()) } coAnswers { rows.value += firstArg<ExternalMountEntity>() }
        coEvery { update(any()) } coAnswers { val row = firstArg<ExternalMountEntity>(); rows.value = rows.value.map { if (it.id == row.id) row else it } }
        coEvery { get(id = any()) } answers { rows.value.firstOrNull { it.id == firstArg<String>() } }
        coEvery { delete(any()) } coAnswers { val id = firstArg<String>(); rows.value = rows.value.filterNot { it.id == id } }
    }
    private var rootEntry = FileEntry("kb-root", "kb-root", null, "知识库", "identity", isDirectory = true,
        physicalRootPath = root.path, materializedPath = "/", createdAt = 1, updatedAt = 1)
    private val workspace = mockk<IWorkspaceRepository> { coEvery { getByUuid("kb-root", "kb-root") } answers { rootEntry } }
    private val context = mockk<Context> { every { noBackupFilesDir } returns File(temporary, "backup") }
    private val fileSystem = JvmBoundRealPathFileSystem()
    private var knowledgeAllowed = true
    private val manager = ExternalFolderManager(context, dao, workspace, FileIndexEventSink.None,
        knowledgeBaseRoots = { setOf("kb-root") }, knowledgeBaseAllowed = { _, _ -> knowledgeAllowed }, backendOverride = { fileSystem.backend(root) })

    @After fun cleanup() { temporary.deleteRecursively() }

    @Test fun `已批准挂载随权限收紧拒绝读取且放宽后恢复`() = runBlocking<Unit> {
        File(root, "note.md").writeText("全文")
        manager.addKnowledgeBase("knowledge", "session", "kb-root")
        assertThat(manager.read("session", "/mnt/knowledge/note.md").toString(Charsets.UTF_8)).isEqualTo("全文")
        knowledgeAllowed = false
        assertThat(assertThrows(ExternalFolderException::class.java) {
            runBlocking { manager.read("session", "/mnt/knowledge/note.md") }
        }.code).isEqualTo("ACCESS_DENIED")
        knowledgeAllowed = true
        assertThat(manager.read("session", "/mnt/knowledge/note.md").toString(Charsets.UTF_8)).isEqualTo("全文")
    }

    @Test fun `知识库根UUID适配目录后端并持久化只读标志且同根不重复挂载`() = runBlocking {
        File(root, "note.md").writeText("全文")
        val mount = manager.addKnowledgeBase("knowledge", "session", "kb-root")
        assertThat(mount.sourceId).isEqualTo("kb-root")
        assertThat(mount.sourceType).isEqualTo(ExternalMountPaths.KNOWLEDGE_BASE_SOURCE)
        assertThat(mount.readOnly).isTrue()
        assertThat(mount.commandAccessible).isFalse()
        assertThat(ExternalMountPaths.commandAccess(mount).realPath).isNull()
        assertThat(rows.value.single().readOnly).isTrue()
        assertThat(manager.observeMounts("session").first().single().readOnly).isTrue()
        assertThat(manager.addKnowledgeBase("other", "session", "kb-root").id).isEqualTo(mount.id)
        assertThat(rows.value).hasSize(1)
        assertThat(manager.read("session", "/mnt/knowledge/note.md").toString(Charsets.UTF_8)).isEqualTo("全文")
        assertThat(manager.list("session", "/mnt/knowledge").map { it.name }).containsExactly("note.md")
        manager.deleteSessionMounts("session")
        assertThat(rows.value).isEmpty()
        assertThat(File(root, "note.md").readText()).isEqualTo("全文")
    }

    @Test fun `根结构身份不匹配在挂载写库之前拒绝`() = runBlocking {
        for (invalid in listOf(rootEntry.copy(workspaceRootUuid = "other"), rootEntry.copy(parentUuid = "parent"),
            rootEntry.copy(isDirectory = false), rootEntry.copy(materializedPath = "/child"),
            rootEntry.copy(physicalRootPath = "relative"))) {
            rootEntry = invalid
            assertThat(assertThrows(ExternalFolderException::class.java) {
                runBlocking { manager.addKnowledgeBase("knowledge", "session", "kb-root") }
            }.code).isEqualTo("UNAVAILABLE")
        }
        assertThat(rows.value).isEmpty()
    }

    @Test fun `未获系统智能体知识库根身份确认不能挂载其它工作区`() = runBlocking {
        val unconfigured = ExternalFolderManager(context, dao, workspace, FileIndexEventSink.None,
            backendOverride = { fileSystem.backend(root) })
        assertThat(assertThrows(ExternalFolderException::class.java) {
            runBlocking { unconfigured.addKnowledgeBase("knowledge", "session", "kb-root") }
        }.code).isEqualTo("UNAVAILABLE")
        assertThat(rows.value).isEmpty()
        coVerify(exactly = 0) { workspace.getByUuid(any(), any()) }
    }

    @Test fun `知识库来源即使只读列未置位也不能写入或向该根导入快照`() = runBlocking {
        rows.value = listOf(ExternalMountEntity("kb", "knowledge", ExternalMountPaths.KNOWLEDGE_BASE_SOURCE, "kb-root", "session", false, 1),
            ExternalMountEntity("source", "source", "real_path", root.path, "session", false, 1))
        File(root, "note.md").writeText("原文")
        assertThat(manager.visible("session").single { it.id == "kb" }.readOnly).isTrue()
        for (action in listOf<suspend () -> Any?>(
            { manager.write("session", "/mnt/knowledge/note.md", byteArrayOf(), null, false) },
            { manager.importSnapshot("session", "kb-root", "/mnt/source/note.md") },
        )) {
            assertThat(assertThrows(ExternalFolderException::class.java) { runBlocking { action() } }.code).isEqualTo("READ_ONLY")
        }
        assertThat(File(root, "note.md").readText()).isEqualTo("原文")
        coVerify(exactly = 0) { workspace.createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `普通会话不能向未挂载的知识库根导入快照`() = runBlocking {
        rows.value = listOf(ExternalMountEntity("source", "source", "real_path", root.path, "session", false, 1))
        File(root, "note.md").writeText("原文")
        val failure = assertThrows(ExternalFolderException::class.java) {
            runBlocking { manager.importSnapshot("session", "kb-root", "/mnt/source/note.md") }
        }
        assertThat(failure.code).isEqualTo("READ_ONLY")
        assertThat(failure.message).isEqualTo("知识库挂载为只读")
        assertThat(rows.value.none { it.sourceType == ExternalMountPaths.KNOWLEDGE_BASE_SOURCE }).isTrue()
        assertThat(root.list().toList()).containsExactly("note.md")
        coVerify(exactly = 0) { workspace.createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `受信知识库管理会话可以向自己的根导入可写来源快照`() = runBlocking {
        val source = File(temporary, "source").apply { mkdirs() }
        File(source, "note.md").writeText("导入正文")
        rows.value = listOf(ExternalMountEntity("source", "source", "real_path", source.path, "knowledge-session", false, 1))
        coEvery { workspace.createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val target = File(root, thirdArg<String>())
            target.outputStream().use { arg<(java.io.OutputStream) -> Unit>(7)(it) }
            FileEntry(secondArg(), firstArg(), arg(4), target.name, externalSha256(target.readBytes()), mimeType = arg(3),
                sizeBytes = target.length(), physicalRootPath = root.path, materializedPath = arg(5), createdAt = 1, updatedAt = 1)
        }
        coEvery { workspace.confirmCreatedEntry(any(), any()) } just Runs
        coEvery { dao.putSnapshot(any()) } just Runs
        val ownerManager = ExternalFolderManager(context, dao, workspace, FileIndexEventSink.None,
            knowledgeBaseRoots = { setOf("kb-root") },
            knowledgeBaseOwnerRoot = { if (it == "knowledge-session") "kb-root" else null },
            backendOverride = { fileSystem.backend(File(it.sourceId)) })
        val imported = ownerManager.importSnapshot("knowledge-session", "kb-root", "/mnt/source/note.md")
        assertThat(root.listFiles().single().readText()).isEqualTo("导入正文")
        assertThat(File(source, "note.md").readText()).isEqualTo("导入正文")
        coVerify(exactly = 1) { workspace.createFileInWorkspaceStreaming("kb-root", imported, any(), any(), "kb-root", any(), any(), any()) }
        coVerify(exactly = 1) { workspace.confirmCreatedEntry("kb-root", imported) }
    }

    @Test fun `分支Linux替换后端不能绕过只读工作区挂载`() = runBlocking {
        File(root, "note.md").writeText("原文")
        val mount = ExternalMount("branch-root", "", "branch_linux_workspace", "branch", "session", 1, readOnly = true)
        val operation = BranchLinuxWorkspaceOperation("session", mount, fileSystem.backend(root), root, workspacePaths = true)
        withContext(operation) {
            val boundContext = kotlin.coroutines.coroutineContext
            assertThat(manager.read("session", "/note.md").toString(Charsets.UTF_8)).isEqualTo("原文")
            for (action in listOf<suspend () -> Any?>(
                { manager.write("session", "/note.md", byteArrayOf(), null, false) },
                { manager.createDirectory("session", "/new") }, { manager.delete("session", "/note.md", null) },
                { manager.backend("session").move("/note.md", "/new") },
                { manager.backend("session").copy("/note.md", "/new") },
            )) assertThat(assertThrows(ExternalFolderException::class.java) { runBlocking(boundContext) { action() } }.code).isEqualTo("READ_ONLY")
        }
        assertThat(File(root, "note.md").readText()).isEqualTo("原文")
        assertThat(File(root, "new").exists()).isFalse()
    }
}
