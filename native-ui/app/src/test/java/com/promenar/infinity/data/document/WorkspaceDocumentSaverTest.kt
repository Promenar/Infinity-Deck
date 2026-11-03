package com.promenar.infinity.data.document

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.model.MessageDocumentAttachment
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.data.workspace.WorkspaceFileTargetResolver
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class WorkspaceDocumentSaverTest {
    private val directory = Files.createTempDirectory("document-workspace-test").toFile().canonicalFile
    private val workspace = mockk<IWorkspaceRepository>()
    private val sessions = mockk<ISessionRepository>()
    private val mounts = mockk<ExternalWorkspaceAccess>()
    private val backend = MemoryBackend()
    private val entries = mutableMapOf<String, FileEntry>()
    private val privateContents = mutableMapOf<String, String>()
    private val indexEvents = mutableListOf<FileIndexEvent>()
    private val root = entry("root", "/", isDirectory = true)
    private var current: SessionWorkspace = SessionWorkspace.Private
    private var mode = SessionRunMode.BUILTIN
    private var worktreeDirectory: String? = null
    private var afterPrivateCreate: (() -> Unit)? = null
    private val clock = Clock.fixed(Instant.parse("2026-11-03T08:00:00Z"), ZoneOffset.UTC)

    @Before fun setup() {
        entries["/"] = root
        coEvery { sessions.getById("s") } returns Session(
            id = "s", agentId = "a", workspaceRootUuid = root.uuid, createdAt = 1, updatedAt = 1,
        )
        coEvery { workspace.getByUuid(root.uuid, root.uuid) } returns root
        coEvery { workspace.getByMaterializedPath(root.uuid, any()) } answers { entries[secondArg()] }
        coEvery { workspace.createDirectoryInWorkspace(root.uuid, any(), any(), any(), any()) } answers {
            entry(secondArg(), arg(4), isDirectory = true).also { entries[it.materializedPath] = it }
        }
        coEvery { workspace.createFileInWorkspaceStreaming(root.uuid, any(), any(), any(), any(), any(), any(), any()) } answers {
            val path = arg<String>(5)
            if (entries.containsKey(path)) throw IllegalStateException("occupied")
            val output = ByteArrayOutputStream()
            arg<(OutputStream) -> Unit>(7)(output)
            privateContents[path] = output.toString(Charsets.UTF_8.name())
            entry(secondArg(), path).also {
                entries[path] = it
                afterPrivateCreate?.invoke()
            }
        }
        coEvery { workspace.confirmCreatedEntry(root.uuid, any()) } returns Unit
        coEvery { workspace.rollbackCreatedEntry(root.uuid, any()) } answers {
            entries.values.firstOrNull { it.uuid == secondArg<String>() }?.let {
                entries.remove(it.materializedPath)
                privateContents.remove(it.materializedPath)
            }
            Unit
        }
        coEvery { mounts.workspaceMount("s") } returns null
        coEvery { mounts.visible("s") } returns emptyList()
    }

    @After fun cleanup() {
        com.promenar.infinity.startup.StartupWriteBarrier.resetForTesting()
        directory.deleteRecursively()
    }

    private fun saver(index: FileIndexEventSink = FileIndexEventSink { indexEvents += it }) = WorkspaceDocumentSaver(
        WorkspaceFileTargetResolver(
            sessions, workspace, mounts, { current }, directory,
            registeredWorkspace = { id ->
                SessionWorkspace.Directory(WorkspaceEntity(id, WorkspaceEntity.KIND_PROJECT, "项目", "project", 1), directory)
            },
            directoryBackend = { backend }, modeFor = { mode },
        ),
        workspace, mounts, index, clock,
        sessionFor = { Session(it, "a", worktreeDirectory = worktreeDirectory) },
    )

    private fun selectDirectory(id: String = "project") {
        current = SessionWorkspace.Directory(
            WorkspaceEntity(id, WorkspaceEntity.KIND_PROJECT, "项目", "project", 1), directory,
        )
    }

    @Test fun `私有文档保存到日期目录并确认文件树且引用清空正文`() = runBlocking {
        val result = saver().save("s", document()) as DocumentWorkspaceSaveResult.Success

        assertThat(privateContents["/attachments/2026-11-03/report.md"]).isEqualTo("甲\r\n乙\n")
        assertThat(result.attachment.content).isEmpty()
        assertThat(result.attachment.workspaceReference?.path).isEqualTo("/session/attachments/2026-11-03/report.md")
        assertThat(result.attachment.workspaceReference?.sourceSessionId).isEqualTo("s")
        assertThat(result.attachment.workspaceReference?.workspaceId).isNull()
        assertThat(result.attachment.workspaceReference?.isPrivate).isTrue()
        assertThat(result.attachment.workspaceReference?.linuxNamespace).isFalse()
        assertThat(result.attachment.workspaceReference?.lineCount).isEqualTo(3)
        assertThat(result.attachment.workspaceReference?.estimatedTokens).isEqualTo(17)
        assertThat(result.attachment.sha256).isEqualTo("document-hash")
        coVerify(exactly = 1) { workspace.confirmCreatedEntry(root.uuid, any()) }
        assertThat(indexEvents.filterIsInstance<FileIndexEvent.Changed>()).hasSize(1)
    }

    @Test fun `私有重名文档保留已有文件并追加序号`() = runBlocking {
        val saver = saver()
        val first = saver.save("s", document()) as DocumentWorkspaceSaveResult.Success
        val second = saver.save("s", document().copy(content = "第二份")) as DocumentWorkspaceSaveResult.Success

        assertThat(first.attachment.workspaceReference?.path).endsWith("/report.md")
        assertThat(second.attachment.workspaceReference?.path).endsWith("/report-2.md")
        assertThat(privateContents["/attachments/2026-11-03/report.md"]).isEqualTo("甲\r\n乙\n")
        assertThat(privateContents["/attachments/2026-11-03/report-2.md"]).isEqualTo("第二份")
    }

    @Test fun `目录工作区竞争新建时使用createOnly并换名且项目规则目录直接生效`() = runBlocking {
        selectDirectory()
        backend.beforeWrite = { path ->
            backend.contents[path] = "并发文件"
            throw ExternalFolderException("CONFLICT", "occupied")
        }

        val result = saver().save("s", document(), "docs/input") as DocumentWorkspaceSaveResult.Success

        assertThat(result.attachment.workspaceReference?.path).isEqualTo("/docs/input/report-2.md")
        assertThat(result.attachment.workspaceReference?.sourceSessionId).isEqualTo("s")
        assertThat(result.attachment.workspaceReference?.workspaceId).isEqualTo("project")
        assertThat(result.attachment.workspaceReference?.isPrivate).isFalse()
        assertThat(backend.contents["/docs/input/report.md"]).isEqualTo("并发文件")
        assertThat(backend.contents["/docs/input/report-2.md"]).isEqualTo("甲\r\n乙\n")
        assertThat(backend.createOnlyWrites).containsExactly(true, true)
        coVerify(exactly = 0) { workspace.createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `工作区不可用时返回固定原因且不创建私有根`() = runBlocking {
        current = SessionWorkspace.Unavailable("missing", null, "目录不可用")

        assertThat(saver().save("s", document())).isEqualTo(
            DocumentWorkspaceSaveResult.Failure(DocumentWorkspaceSaveFailure.WorkspaceUnavailable),
        )
        coVerify(exactly = 0) { workspace.ensureSessionRoot(any()) }
        assertThat(privateContents).isEmpty()
        assertThat(backend.contents).isEmpty()
    }

    @Test fun `绝对越界保留目录和命名空间目录均在新建前拒绝`() = runBlocking {
        val invalidDirectories = listOf(
            "/tmp", "../outside", "docs/../outside", "C:/outside", "docs\\outside",
            ".infinity/cache", ".nexara/cache", ".recycle_bin", "docs/.infinity_state",
            "session", "workspace", "mnt/source", "skills", "home/session", "docs/\nline",
        )
        for (path in invalidDirectories) {
            assertThat(saver().save("s", document(), path)).isEqualTo(
                DocumentWorkspaceSaveResult.Failure(DocumentWorkspaceSaveFailure.InvalidPath),
            )
        }
        assertThat(entries.keys).containsExactly("/")
        coVerify(exactly = 0) { workspace.ensureSessionRoot(any()) }
    }

    @Test fun `文件名中的路径片段控制字符和保留名称拒绝保存`() = runBlocking {
        for (name in listOf("../report.md", "/report.md", "folder/report.md", "folder\\report.md", ".infinity_file", ".recycle_bin", "C:report.md", "line\nreport.md")) {
            assertThat(saver().save("s", document().copy(name = name))).isEqualTo(
                DocumentWorkspaceSaveResult.Failure(DocumentWorkspaceSaveFailure.InvalidPath),
            )
        }
        assertThat(privateContents).isEmpty()
    }

    @Test fun `目录和文件名组合也不能形成私有命名空间入口`() = runBlocking {
        assertThat(saver().save("s", document().copy(name = "session"), "home")).isEqualTo(
            DocumentWorkspaceSaveResult.Failure(DocumentWorkspaceSaveFailure.InvalidPath),
        )
        assertThat(entries.keys).containsExactly("/")
        Unit
    }

    @Test fun `Builtin和Linux私有与目录引用路径均按运行模式配对`() = runBlocking {
        for (linux in listOf(false, true)) {
            mode = if (linux) SessionRunMode.LINUX else SessionRunMode.BUILTIN
            current = SessionWorkspace.Private
            val privateResult = saver().save("s", document(), "input-${mode.name}") as DocumentWorkspaceSaveResult.Success
            assertThat(privateResult.attachment.workspaceReference?.path)
                .isEqualTo(if (linux) "/home/session/input-LINUX/report.md" else "/session/input-BUILTIN/report.md")
            assertThat(privateResult.attachment.workspaceReference?.linuxNamespace).isEqualTo(linux)
            selectDirectory()
            val directoryResult = saver().save("s", document(), "input-${mode.name}") as DocumentWorkspaceSaveResult.Success
            assertThat(directoryResult.attachment.workspaceReference?.path)
                .isEqualTo(if (linux) "/workspace/input-LINUX/report.md" else "/input-BUILTIN/report.md")
        }
    }

    @Test fun `准备目录期间切换工作区后停止文件写入`() = runBlocking {
        selectDirectory("first")
        backend.afterCreateDirectory = { selectDirectory("second") }

        assertThat(saver().save("s", document())).isEqualTo(
            DocumentWorkspaceSaveResult.Failure(DocumentWorkspaceSaveFailure.WorkspaceUnavailable),
        )
        assertThat(backend.contents).isEmpty()
    }

    @Test fun `worktree保存记录分支来源和原目录`() = runBlocking {
        selectDirectory("branch-worktree:s")
        mode = SessionRunMode.LINUX
        worktreeDirectory = "/workspace/branches/one"

        val result = saver().save("s", document()) as DocumentWorkspaceSaveResult.Success

        assertThat(result.attachment.workspaceReference?.workspaceId).isEqualTo("branch-worktree:s")
        assertThat(result.attachment.workspaceReference?.worktreeDirectory).isEqualTo("/workspace/branches/one")
        assertThat(result.attachment.workspaceReference?.linuxNamespace).isTrue()
    }

    @Test fun `worktree来源快照缺少原目录时保存前拒绝写入`() = runBlocking {
        selectDirectory("branch-worktree:s")

        assertThat(saver().save("s", document())).isEqualTo(
            DocumentWorkspaceSaveResult.Failure(DocumentWorkspaceSaveFailure.WorkspaceUnavailable),
        )
        assertThat(backend.contents).isEmpty()
    }

    @Test fun `worktree目录逻辑来源改变后撤回自己新建文件`() = runBlocking {
        selectDirectory("branch-worktree:s")
        mode = SessionRunMode.LINUX
        worktreeDirectory = "/workspace/branches/one"
        backend.afterWrite = { worktreeDirectory = "/workspace/branches/two" }

        assertThat(saver().save("s", document())).isEqualTo(
            DocumentWorkspaceSaveResult.Failure(DocumentWorkspaceSaveFailure.WorkspaceUnavailable),
        )
        assertThat(backend.contents).isEmpty()
    }

    @Test fun `目录文件写出后切换工作区撤回自己新建的文件`() = runBlocking {
        selectDirectory("first")
        backend.afterWrite = { selectDirectory("second") }

        assertThat(saver().save("s", document())).isEqualTo(
            DocumentWorkspaceSaveResult.Failure(DocumentWorkspaceSaveFailure.WorkspaceUnavailable),
        )
        assertThat(backend.contents).isEmpty()
    }

    @Test fun `准备目录期间切换运行模式后停止文件写入`() = runBlocking {
        selectDirectory()
        backend.afterCreateDirectory = { mode = SessionRunMode.LINUX }

        assertThat(saver().save("s", document())).isEqualTo(
            DocumentWorkspaceSaveResult.Failure(DocumentWorkspaceSaveFailure.WorkspaceUnavailable),
        )
        assertThat(backend.contents).isEmpty()
    }

    @Test fun `目录只读错误返回固定原因`() = runBlocking {
        selectDirectory()
        backend.beforeWrite = { throw ExternalFolderException("READ_ONLY", "internal details") }

        assertThat(saver().save("s", document())).isEqualTo(
            DocumentWorkspaceSaveResult.Failure(DocumentWorkspaceSaveFailure.ReadOnly),
        )
    }

    @Test fun `私有新建后工作区身份切换撤回创建并保持原附件可重试`() = runBlocking {
        afterPrivateCreate = { selectDirectory() }

        assertThat(saver().save("s", document())).isEqualTo(
            DocumentWorkspaceSaveResult.Failure(DocumentWorkspaceSaveFailure.WorkspaceUnavailable),
        )
        assertThat(privateContents).isEmpty()
        coVerify(exactly = 1) { workspace.rollbackCreatedEntry(root.uuid, any()) }
        coVerify(exactly = 0) { workspace.confirmCreatedEntry(root.uuid, any()) }
    }

    @Test fun `索引失败撤回私有文件且失败结果不携带异常内容`() = runBlocking {
        val result = saver(FileIndexEventSink { throw IllegalStateException("internal details") }).save("s", document())

        assertThat(result).isEqualTo(DocumentWorkspaceSaveResult.Failure(DocumentWorkspaceSaveFailure.SaveFailed))
        assertThat(result.toString()).doesNotContain("internal details")
        assertThat(privateContents).isEmpty()
        coVerify(exactly = 1) { workspace.rollbackCreatedEntry(root.uuid, any()) }
    }

    @Test fun `协程取消向上传递`() = runBlocking {
        selectDirectory()
        backend.beforeWrite = { throw CancellationException("cancelled") }

        val failure = runCatching { saver().save("s", document()) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
    }

    @Test fun `字节入口保存原二进制并记录实际字节hash`() = runBlocking<Unit> {
        selectDirectory()
        val bytes = byteArrayOf(0xff.toByte(), 0, 1, 2, 0xfe.toByte())

        val result = saver().saveBytes("s", "image.png", "image/png", bytes) as DocumentWorkspaceBytesSaveResult.Success

        assertThat(backend.binaryContents.getValue("/attachments/2026-11-03/image.png").toList()).containsExactlyElementsIn(bytes.toList()).inOrder()
        assertThat(result.reference.sha256).isEqualTo(hash(bytes))
        assertThat(result.reference.lineCount).isEqualTo(0)
        assertThat(backend.createOnlyWrites).containsExactly(true)
    }

    @Test fun `复用核验读取当前真实字节而不只相信元数据hash`() = runBlocking {
        selectDirectory()
        val bytes = "first".toByteArray()
        val saver = saver()
        val saved = saver.saveBytes("s", "note.txt", "text/plain", bytes) as DocumentWorkspaceBytesSaveResult.Success
        val session = Session("s", "a", workspaceId = "project", runMode = SessionRunMode.BUILTIN)

        assertThat(saver.isReusable(session, saved.reference, hash(bytes), bytes.size.toLong())).isTrue()
        backend.binaryContents["/attachments/2026-11-03/note.txt"] = "other".toByteArray()
        assertThat(saver.isReusable(session, saved.reference, hash(bytes), bytes.size.toLong())).isFalse()
        backend.contents.remove("/attachments/2026-11-03/note.txt")
        backend.binaryContents.remove("/attachments/2026-11-03/note.txt")
        assertThat(saver.isReusable(session, saved.reference, hash(bytes), bytes.size.toLong())).isFalse()
    }

    @Test fun `旧引用没有hash时仍实际读取核验且保留大小来源边界`() = runBlocking {
        selectDirectory()
        val bytes = "first".toByteArray()
        val saver = saver()
        val saved = saver.saveBytes("s", "note.txt", "text/plain", bytes) as DocumentWorkspaceBytesSaveResult.Success
        val legacy = saved.reference.copy(sha256 = null)
        val session = Session("s", "a", workspaceId = "project", runMode = SessionRunMode.BUILTIN)

        assertThat(saver.isReusable(session, legacy, hash(bytes), bytes.size.toLong())).isTrue()
        assertThat(saver.isReusable(session, legacy, hash("other".toByteArray()), bytes.size.toLong())).isFalse()
        assertThat(saver.isReusable(session, legacy, hash(bytes), bytes.size.toLong() + 1)).isFalse()
        assertThat(saver.isReusable(session.copy(workspaceId = "different"), legacy, hash(bytes), bytes.size.toLong())).isFalse()
    }

    @Test fun `复用核验拒绝换工作区模式或Unavailable且不建私有根`() = runBlocking {
        selectDirectory()
        val bytes = "data".toByteArray()
        val saver = saver()
        val saved = saver.saveBytes("s", "note.txt", "text/plain", bytes) as DocumentWorkspaceBytesSaveResult.Success
        val session = Session("s", "a", workspaceId = "project", runMode = SessionRunMode.BUILTIN)

        selectDirectory("another")
        assertThat(saver.isReusable(session, saved.reference, hash(bytes))).isFalse()
        selectDirectory()
        mode = SessionRunMode.LINUX
        assertThat(saver.isReusable(session, saved.reference, hash(bytes))).isFalse()
        current = SessionWorkspace.Unavailable("missing", null, "不可用")
        assertThat(saver.isReusable(session, saved.reference, hash(bytes))).isFalse()
        coVerify(exactly = 0) { workspace.ensureSessionRoot(any()) }
    }

    @Test fun `核验过程中来源变化后不可复用`() = runBlocking {
        selectDirectory()
        val bytes = "data".toByteArray()
        val saver = saver()
        val saved = saver.saveBytes("s", "note.txt", "text/plain", bytes) as DocumentWorkspaceBytesSaveResult.Success
        backend.afterRead = { selectDirectory("another") }

        val reusable = saver.isReusable(Session("s", "a", workspaceId = "project", runMode = SessionRunMode.BUILTIN), saved.reference, hash(bytes))

        assertThat(reusable).isFalse()
    }

    @Test fun `字节入口仍拒绝目录越界和文件名路径注入`() = runBlocking<Unit> {
        assertThat(saver().saveBytes("s", "../image.png", "image/png", byteArrayOf(1))).isEqualTo(
            DocumentWorkspaceBytesSaveResult.Failure(DocumentWorkspaceSaveFailure.InvalidPath),
        )
        assertThat(saver().saveBytes("s", "image.png", "image/png", byteArrayOf(1), relativeDirectory = "../outside")).isEqualTo(
            DocumentWorkspaceBytesSaveResult.Failure(DocumentWorkspaceSaveFailure.InvalidPath),
        )
        assertThat(entries.keys).containsExactly("/")
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun entry(id: String, path: String, isDirectory: Boolean = false) = FileEntry(
        uuid = id, workspaceRootUuid = "root", parentUuid = if (path == "/") null else "root",
        name = path.substringAfterLast('/'), hash = "hash", mimeType = "text/markdown", isDirectory = isDirectory,
        physicalRootPath = directory.path, materializedPath = path, createdAt = 1, updatedAt = 1,
    )

    private fun document() = MessageDocumentAttachment(
        id = "document", name = "report.md", mimeType = "text/markdown", content = "甲\r\n乙\n",
        sizeBytes = 9, sha256 = "document-hash", estimatedTokens = 17,
    )

    private class MemoryBackend : ExternalFolderBackend {
        val contents = mutableMapOf<String, String>()
        val binaryContents = mutableMapOf<String, ByteArray>()
        private val directories = mutableSetOf("/")
        val createOnlyWrites = mutableListOf<Boolean>()
        var beforeWrite: ((String) -> Unit)? = null
        var afterWrite: (() -> Unit)? = null
        var afterCreateDirectory: (() -> Unit)? = null
        var afterRead: (() -> Unit)? = null

        override suspend fun stat(path: String): ExternalFolderEntry? = when {
            path in directories -> folderEntry(path, true)
            path in contents -> folderEntry(path, false)
            else -> null
        }
        override suspend fun list(path: String): List<ExternalFolderEntry> = emptyList()
        override suspend fun read(path: String, maxBytes: Long): ByteArray {
            val bytes = binaryContents[path] ?: contents.getValue(path).toByteArray()
            afterRead?.also { afterRead = null }?.invoke()
            return bytes
        }
        override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean): ExternalFolderEntry {
            createOnlyWrites += createOnly
            beforeWrite?.also { beforeWrite = null }?.invoke(path)
            if (path in contents || path in directories) throw ExternalFolderException("CONFLICT", "occupied")
            contents[path] = content.toString(Charsets.UTF_8)
            binaryContents[path] = content.copyOf()
            afterWrite?.also { afterWrite = null }?.invoke()
            return folderEntry(path, false)
        }
        override suspend fun createDirectory(path: String): ExternalFolderEntry {
            directories += path
            afterCreateDirectory?.also { afterCreateDirectory = null }?.invoke()
            return folderEntry(path, true)
        }
        override suspend fun delete(path: String, expectedSha256: String?) { contents.remove(path); binaryContents.remove(path) }
        private fun folderEntry(path: String, directory: Boolean) = ExternalFolderEntry(
            path, path.substringAfterLast('/'), directory, binaryContents[path]?.size?.toLong() ?: contents[path]?.toByteArray()?.size?.toLong() ?: 0, 1,
        )
    }
}
