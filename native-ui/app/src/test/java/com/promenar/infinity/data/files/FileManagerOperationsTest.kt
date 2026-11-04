package com.promenar.infinity.data.files

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.promenar.infinity.data.local.db.dao.VectorizationTaskDao
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.session.SessionExecutionGate
import com.promenar.infinity.domain.files.*
import com.promenar.infinity.domain.repository.IWorkspaceRepository
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

    private fun session(id: String, title: String, root: String?, agent: String = "fictional") = SessionEntity(
        id = id, agentId = agent, title = title, workspaceRootUuid = root, createdAt = 1, updatedAt = 1,
    )

    private fun operations(backends: Map<String, MemoryFileBackend>, sessions: List<SessionEntity> = emptyList()): DefaultFileOperations {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return DefaultFileOperations(context, { id ->
            if (id == "sessions") throw FileOpException(FileOpCode.READ_ONLY)
            FileBackendHandle(id, backends[id] ?: throw FileOpException(FileOpCode.NOT_FOUND), emptyList(), true)
        }, { sessions }, mockk<IWorkspaceRepository>(relaxed = true), mockk<VectorizationTaskDao>(relaxed = true),
            FileIndexEventSink.None, FileMutationGuard(SessionExecutionGate()) { false }, mockk(relaxed = true))
    }
}
