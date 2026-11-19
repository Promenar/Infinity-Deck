package com.promenar.infinity.data.workspace

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import io.mockk.coEvery
import io.mockk.mockk
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkspaceFileTargetReadOnlyTest {
    private val root = Files.createTempDirectory("readonly-media-target").toFile().canonicalFile
    private var mount = ExternalMount("kb", "knowledge", ExternalMountPaths.KNOWLEDGE_BASE_SOURCE, "kb-root", "session", 1, readOnly = true)
    private val mounts = mockk<ExternalWorkspaceAccess> {
        coEvery { workspaceMount("session") } returns null
        coEvery { visible("session") } answers { listOf(mount) }
    }
    @After fun cleanup() { root.deleteRecursively() }

    private fun targets(linux: Boolean) = WorkspaceFileTargetResolver(mockk(), mockk(), mounts, { SessionWorkspace.Private }, root,
        pathPresenter = if (linux) LinuxWorkspaceFilePathPresenter else BuiltinWorkspaceFilePathPresenter)

    @Test fun `媒体画布共享目标解析在普通和Linux模式都提前拒绝知识库写入`() = runBlocking {
        for (rwMount in listOf(
            mount,
            ExternalMount("kb-rw", "knowledge", ExternalMountPaths.KNOWLEDGE_BASE_RW_SOURCE, "kb-root", "session", 1, readOnly = false),
        )) {
            mount = rwMount
            for (linux in listOf(false, true)) {
                val failure = assertThrows(ExternalFolderException::class.java) {
                    runBlocking { targets(linux).resolve("session", "/mnt/knowledge/new.png", writing = true) }
                }
                assertThat(failure.code).isEqualTo("READ_ONLY")
                assertThat(failure.message).isEqualTo("知识库挂载为只读")
            }
        }
        assertThat(root.list()).isEmpty()
    }

    @Test fun `操作期间只读属性改变使媒体目标身份失效`() = runBlocking {
        mount = mount.copy(sourceType = "real_path", sourceId = root.path, readOnly = false)
        val resolver = targets(false)
        val target = resolver.resolve("session", "/mnt/knowledge/new.png", writing = true)
        mount = mount.copy(readOnly = true)
        assertThat(assertThrows(ExternalFolderException::class.java) {
            runBlocking { resolver.checkCurrent(target, "/mnt/knowledge/new.png") }
        }.code).isEqualTo("UNAVAILABLE")
        assertThat(root.list()).isEmpty()
    }
}
