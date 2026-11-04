package com.promenar.infinity.data.storage

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.ui.chat.manager.skills.PrivateWorkspaceTransferBackend
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PrivateTransferLifecycleTest {
    private fun entry(uuid: String, path: String, directory: Boolean) = FileEntry(
        uuid, "root", "root", path.substringAfterLast('/'), "hash", isDirectory = directory,
        physicalRootPath = "", materializedPath = path, createdAt = 1, updatedAt = 1,
    )

    @Test fun `来源删除将摘要条件交给同根事务而不先整块读取`() = runBlocking {
        val file = entry("file", "/large", false).copy(sizeBytes = 80L * 1024 * 1024)
        val workspace = mockk<IWorkspaceRepository> {
            coEvery { getByMaterializedPath("root", "/large") } returns file
            coEvery { permanentDeleteIfHash("root", "file", "expected") } just Runs
        }
        val backend = PrivateWorkspaceTransferBackend(workspace, "root")
        backend.delete("/large", "expected")
        coVerify(exactly = 1) { workspace.permanentDeleteIfHash("root", "file", "expected") }
        coVerify(exactly = 0) { workspace.permanentDelete(any(), any()) }
    }

    @Test fun `新建目录失败补偿使用已有回收语义`() = runBlocking<Unit> {
        val workspace = mockk<IWorkspaceRepository> {
            coEvery { getByMaterializedPath("root", "/") } returns entry("root", "/", true)
            coEvery { getByMaterializedPath("root", "/new") } returns entry("directory", "/new", true)
            coEvery { createDirectoryInWorkspace(any(), any(), any(), any(), any()) } returns entry("directory", "/new", true)
            coEvery { rollbackCreatedEntry(any(), any()) } throws SecurityException("目录没有文件创建授权")
            coEvery { permanentDelete(any(), any()) } just Runs
        }
        val backend = PrivateWorkspaceTransferBackend(workspace, "root")
        backend.createDirectory("/new")
        backend.delete("/new")
        coVerify(exactly = 1) { workspace.permanentDelete("root", "directory") }
        coVerify(exactly = 0) { workspace.rollbackCreatedEntry(any(), any()) }
    }

    @Test fun `移动进入私有工作区保留受支持MIME并排入索引`() = runBlocking<Unit> {
        val created = entry("file", "/new.md", false)
        val workspace = mockk<IWorkspaceRepository> {
            coEvery { getByMaterializedPath("root", "/") } returns entry("root", "/", true)
            coEvery { getByMaterializedPath("root", "/new.md") } returns null
            coEvery { getByUuid("root", "file") } returns created
            coEvery { createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) } returns created
            coEvery { confirmCreatedEntry(any(), any()) } just Runs
        }
        val events = mutableListOf<FileIndexEvent>()
        val backend = spyk(PrivateWorkspaceTransferBackend(workspace, "root", FileIndexEventSink { events.add(it) }))
        coEvery { backend.availableBytes("/") } returns Long.MAX_VALUE
        backend.write("/new.md", "text".toByteArray(), createOnly = true)
        backend.confirmCreated()
        coVerify { workspace.createFileInWorkspaceStreaming("root", any(), "new.md", "text/markdown", "root", "/new.md", any(), any()) }
        assertThat(events.filterIsInstance<FileIndexEvent.Changed>().map { it.fileUuid }).containsExactly("file")
    }
}
