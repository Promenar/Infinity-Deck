package com.promenar.infinity.data.files

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.promenar.infinity.data.local.db.dao.VectorizationTaskDao
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.session.SessionExecutionGate
import com.promenar.infinity.domain.files.*
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FileManagerOperationsTest {
    @Test fun `Linux主目录删除与移动源删除通知实际路径并在失败后失效`() = runBlocking<Unit> {
        val backend = mockk<ExternalFolderBackend>()
        val expected = ExternalFolderEntry("/b.txt", "b.txt", false, 1, 1, "hash", "identity")
        coEvery { backend.delete("/a.txt", null) } returns Unit
        coEvery { backend.delete("/failed.txt", null) } throws IllegalStateException("注入失败")
        coEvery { backend.deleteForTransfer("/b.txt", expected, "hash") } returns Unit
        val invalidated = mutableListOf<String>()
        val guarded = LinuxHomeVersionCleanupBackend(backend) { invalidated += it }
        guarded.delete("/a.txt")
        assertTrue(runCatching { guarded.delete("/failed.txt") }.isFailure)
        guarded.deleteForTransfer("/b.txt", expected, "hash")
        assertEquals(listOf("/root/a.txt", "/root/failed.txt", "/root/b.txt"), invalidated)
    }

    @Test fun `私有文件删除使用选中条目的身份与摘要`() = runBlocking {
        val hash = "a".repeat(64)
        val row = FileEntry(uuid = "item", workspaceRootUuid = "root", parentUuid = "root", name = "a.txt",
            hash = hash, physicalRootPath = "/fixture", materializedPath = "/a.txt", createdAt = 1, updatedAt = 1)
        val backend = mockk<ExternalFolderBackend> {
            coEvery { stat("/a.txt") } returns ExternalFolderEntry("/a.txt", "a.txt", false, 1, 1, hash, "item")
        }
        val repository = mockk<IWorkspaceRepository> {
            coEvery { getByMaterializedPath("root", "/a.txt") } returns row
            coEvery { permanentDeleteIfHash("root", "item", hash) } returns Unit
        }
        val operations = roomOperations(backend, repository)
        val result = operations.delete(listOf(FileRef("private", "/a.txt")))
        assertTrue(result.failed.isEmpty())
        coVerify(exactly = 1) { repository.permanentDeleteIfHash("root", "item", hash) }
        coVerify(exactly = 0) { repository.permanentDelete(any(), any()) }
    }

    @Test fun `私有文件删除拒绝路径对应身份已变化`() = runBlocking {
        val hash = "a".repeat(64)
        val row = FileEntry(uuid = "replacement", workspaceRootUuid = "root", parentUuid = "root", name = "a.txt",
            hash = hash, physicalRootPath = "/fixture", materializedPath = "/a.txt", createdAt = 1, updatedAt = 1)
        val backend = mockk<ExternalFolderBackend> {
            coEvery { stat("/a.txt") } returns ExternalFolderEntry("/a.txt", "a.txt", false, 1, 1, hash, "item")
        }
        val repository = mockk<IWorkspaceRepository> {
            coEvery { getByMaterializedPath("root", "/a.txt") } returns row
        }
        val result = roomOperations(backend, repository).delete(listOf(FileRef("private", "/a.txt")))
        assertEquals(FileOpCode.CONFLICT, result.failed.single().code)
        coVerify(exactly = 0) { repository.permanentDelete(any(), any()) }
        coVerify(exactly = 0) { repository.permanentDeleteIfHash(any(), any(), any()) }
    }

    private fun roomOperations(backend: ExternalFolderBackend, repository: IWorkspaceRepository) = DefaultFileOperations(
        ApplicationProvider.getApplicationContext<Context>(),
        { FileBackendHandle("private", backend, emptyList(), true, roomRoot = "root") },
        { emptyList() }, repository, mockk<VectorizationTaskDao>(relaxed = true), FileIndexEventSink.None,
        FileMutationGuard(SessionExecutionGate()) { false },
    )

    @Test fun `删除目录及子文件且不影响同级文件`() = runBlocking {
        val backend = MemoryFileBackend().apply {
            addDirectory("/folder")
            addFile("/folder/remove.txt", "remove".toByteArray())
            addFile("/keep.txt", "keep".toByteArray())
        }
        val ref = FileRef("external:test", "/folder")
        val result = operations(mapOf("external:test" to backend)).delete(listOf(ref))
        assertEquals(listOf(ref), result.succeeded)
        assertTrue(result.failed.isEmpty())
        assertEquals(null, backend.stat("/folder"))
        assertEquals(null, backend.stat("/folder/remove.txt"))
        assertEquals("keep", backend.bytes("/keep.txt").decodeToString())
    }

    @Test fun `文件管理器预览GBK和UTF16中文`() = runBlocking {
        val chinese = "虚构中文资料"
        val backend = MemoryFileBackend().apply {
            addFile("/gbk.txt", chinese.toByteArray(java.nio.charset.Charset.forName("GBK")))
            addFile("/utf16.txt", byteArrayOf(0xff.toByte(), 0xfe.toByte()) + chinese.toByteArray(Charsets.UTF_16LE))
        }
        val operations = operations(mapOf("external:test" to backend))
        for (name in listOf("gbk.txt", "utf16.txt")) {
            val preview = operations.preview(FileRef("external:test", "/$name"), 100) as FilePreview.Text
            assertEquals(chinese, preview.content)
        }
    }

    @Test fun `搜索限制深度数量与筛选`() = runBlocking {
        val backend = MemoryFileBackend()
        backend.addFile("/Report.TXT", "a".toByteArray(), 200)
        backend.addFile("/report.png", byteArrayOf(1), 300)
        var parent = "/"
        repeat(33) { depth ->
            parent = joinFilePath(parent, "d$depth")
            backend.addDirectory(parent)
        }
        backend.addFile(joinFilePath(parent, "report-hidden.txt"), byteArrayOf(1), 400)
        val operations = operations(mapOf("external:test" to backend))
        val all = operations.search("external:test", FileSearchQuery("report"), 1)
        assertEquals(1, all.size)
        val filtered = operations.search("external:test", FileSearchQuery("REPORT", types = setOf(FileTypeFilter.DOCUMENT), modifiedAfter = 100))
        assertEquals(listOf("Report.TXT"), filtered.map { it.item.name })
        assertTrue(operations.search("external:test", FileSearchQuery("report", withinPath = "/d0", modifiedAfter = 100)).isEmpty())
    }

    @Test fun `移动同名时失败并保留来源和目标`() = runBlocking {
        val source = MemoryFileBackend().apply { addFile("/same.txt", "source".toByteArray()) }
        val target = MemoryFileBackend().apply { addFile("/same.txt", "target".toByteArray()) }
        val operations = operations(mapOf("external:source" to source, "external:target" to target))
        val result = operations.move(listOf(FileRef("external:source", "/same.txt")), FileRef("external:target", "/"))
        assertTrue(result.succeeded.isEmpty())
        assertEquals(FileOpCode.NAME_EXISTS, result.failed.single().code)
        assertEquals("source", source.bytes("/same.txt").decodeToString())
        assertEquals("target", target.bytes("/same.txt").decodeToString())
    }


    @Test fun `复制自动避让同名文件并保留内容`() = runBlocking {
        val source = MemoryFileBackend().apply { addFile("/note.txt", "new".toByteArray()) }
        val target = MemoryFileBackend().apply {
            addFile("/note.txt", "first".toByteArray())
            addFile("/note (2).txt", "second".toByteArray())
        }
        val operations = operations(mapOf("external:source" to source, "external:target" to target))
        val result = operations.copy(listOf(FileRef("external:source", "/note.txt")), FileRef("external:target", "/"))
        assertEquals(listOf(FileRef("external:target", "/note (3).txt")), result.succeeded)
        assertTrue(result.failed.isEmpty())
        assertEquals("new", target.bytes("/note (3).txt").decodeToString())
        assertEquals("first", target.bytes("/note.txt").decodeToString())
    }


    @Test fun `同一底层目录的不同位置别名不能把目录复制到自身`() = runBlocking {
        val backend = MemoryFileBackend().apply {
            addDirectory("/folder")
            addDirectory("/folder/sub")
            addFile("/folder/sub/file.txt", "safe".toByteArray())
        }
        val operations = operations(mapOf("external:first" to backend, "external:second" to backend))
        val result = operations.copy(listOf(FileRef("external:first", "/folder")), FileRef("external:second", "/folder/sub"))
        assertTrue(result.succeeded.isEmpty())
        assertEquals(FileOpCode.CONFLICT, result.failed.single().code)
        assertEquals("safe", backend.bytes("/folder/sub/file.txt").decodeToString())
        assertEquals(null, backend.stat("/folder/sub/folder"))
    }

    @Test fun `会话汇总只列私有根且根目录只读`() = runBlocking {
        val rows = listOf(
            session("one", "相同", "root-one"), session("two", "相同", "root-two"),
            session("empty", "空", null), session("system", "系统", "root-system", "__system__"),
        )
        val operations = operations(emptyMap(), rows)
        val folders = operations.list("sessions", "/")
        assertEquals(setOf("相同", "相同 (2)"), folders.map { it.name }.toSet())
        assertEquals(setOf("session:one", "session:two"), folders.map { it.ref.locationId }.toSet())
        val failed = operations.delete(listOf(FileRef("sessions", "/")))
        assertEquals(FileOpCode.READ_ONLY, failed.failed.single().code)
        assertFalse(folders.any { it.name == "系统" })
    }

    @Test fun `绑定项目或分支的会话不列入私有会话汇总`() = runBlocking {
        val rows = listOf(
            session("private", "私有", "root-private"),
            session("project", "项目", "root-project").copy(workspaceId = "workspace-example"),
            session("branch", "分支", "root-branch").copy(worktreeDirectory = "/fixture/branch"),
        )

        val folders = operations(emptyMap(), rows).list("sessions", "/")

        assertEquals(listOf("session:private"), folders.map { it.ref.locationId })
    }

    @Test
    fun privateSessionFolderExcludesBoundWorktreeSystemAndRootlessSessions() {
        assertTrue(isPrivateSessionFolder(session("a", "甲", "root-a")))
        assertFalse(isPrivateSessionFolder(session("b", "乙", null)))
        assertFalse(isPrivateSessionFolder(session("c", "丙", "root-c").copy(workspaceId = "project")))
        assertFalse(isPrivateSessionFolder(session("d", "丁", "root-d").copy(worktreeDirectory = "branch")))
        assertFalse(isPrivateSessionFolder(session("e", "戊", "root-e", agent = "__system__")))
    }

    private fun session(id: String, title: String, root: String?, agent: String = "fictional") = SessionEntity(
        id = id, agentId = agent, title = title, workspaceRootUuid = root, createdAt = 1, updatedAt = 1,
    )

    private fun operations(backends: Map<String, MemoryFileBackend>, sessions: List<SessionEntity> = emptyList()): DefaultFileOperations {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return DefaultFileOperations(context, { id ->
            if (id == "sessions") throw FileOpException(FileOpCode.READ_ONLY)
            FileBackendHandle(id, backends[id] ?: throw FileOpException(FileOpCode.NOT_FOUND), emptyList(), true)
        }, { sessions }, mockk<IWorkspaceRepository>(relaxed = true), mockk<VectorizationTaskDao>(relaxed = true),
            FileIndexEventSink.None, FileMutationGuard(SessionExecutionGate()) { false })
    }
}
