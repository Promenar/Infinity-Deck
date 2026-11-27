package com.promenar.infinity.data.storage

import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.ui.chat.manager.SystemPromptSections
import com.promenar.infinity.ui.chat.manager.skills.SFTP_USE_REMOTE_MOUNT_TOOL
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SftpSessionNamespaceTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun `两个命名空间共用挂载的列目录 属性和文本读取 SFTP 不进入命令绑定`() = runBlocking<Unit> {
        val fixture = RemoteMountTestFixture(directory.root)
        val content = "远程文本"
        fixture.transport.put("/mount/notes.txt", content.toByteArray())
        val sessionId = "s1"
        val path = "/mnt/drive/notes.txt"
        for (access in listOf(fixture.builtin, fixture.linux)) {
            assertEquals(listOf("/mnt/drive"), access.list(sessionId, "/mnt").map { it.path })
            assertEquals(listOf(path), access.list(sessionId, "/mnt/drive").map { it.path })
            assertEquals(content.toByteArray().size.toLong(), access.statEntry(sessionId, path)?.sizeBytes)
            assertEquals(content, access.read(sessionId, path, 100).decodeToString())
            assertEquals(content, access.readStream(sessionId, path, 100) { it.readBytes().decodeToString() })
            assertFalse(access.isPathWritable(sessionId, path))
            val denied = runCatching { access.write(sessionId, path, byteArrayOf(0), null, false) }.exceptionOrNull()
            assertEquals(SFTP_USE_REMOTE_MOUNT_TOOL, (denied as ExternalFolderException).code)
        }
        val visible = fixture.linux.visible(sessionId)
        assertEquals(listOf(fixture.mount), visible)
        assertFalse(fixture.mount.commandAccessible)
        assertTrue(visible.filter { it.commandAccessible }.isEmpty())
        assertNull(ExternalMountPaths.commandAccess(fixture.mount).realPath)
    }

    @Test fun `本地传输支持自选目录与 Linux 已登记工作区并拒绝符号链接`() = runBlocking<Unit> {
        val fixture = RemoteMountTestFixture(directory.root)
        val selected = directory.newFolder("selected").canonicalFile
        fixture.selected = SessionWorkspace.Directory(
            WorkspaceEntity("selected", WorkspaceEntity.KIND_PROJECT, "已选目录", "selected", 1), selected,
        )
        val file = File(selected, "a.txt").apply { writeText("工作区内容") }
        assertEquals(file, fixture.builtin.remoteMountLocalFile("s1", file.path, writing = false).file)
        assertEquals(file, fixture.linux.remoteMountLocalFile("s1", "/workspace/a.txt", writing = false).file)
        val outside = directory.newFolder("outside")
        Files.createSymbolicLink(File(selected, "link").toPath(), outside.toPath())
        for (name in listOf("link/a.txt", ".agents/skills/a.md")) {
            val failure = runCatching { fixture.builtin.remoteMountLocalFile("s1", name, writing = true) }.exceptionOrNull()
            assertTrue(failure is ExternalFolderException)
        }
        val outsideLinux = runCatching { fixture.linux.remoteMountLocalFile("s1", "/etc/passwd", writing = false) }.exceptionOrNull()
        assertEquals("ACCESS_DENIED", (outsideLinux as ExternalFolderException).code)
    }

    @Test fun `只读远程摘要入口仅校验挂载内的普通文件`() = runBlocking<Unit> {
        val fixture = RemoteMountTestFixture(directory.root)
        val bytes = "服务端校验".toByteArray()
        fixture.transport.put("/mount/notes.txt", bytes)
        val backend = fixture.backend(fixture.mount.copy(readOnly = true))
        val receipt = backend.computeFileSha256("/notes.txt")
        assertEquals(mountTestHash(bytes), receipt.sha256)
        assertEquals("ssh_exec_sha256sum", receipt.verificationMethod)
        for (path in listOf("/", "/../notes.txt")) {
            assertTrue(runCatching { backend.computeFileSha256(path) }.exceptionOrNull() is ExternalFolderException)
        }
        assertTrue(fixture.transport.readOffsets.isEmpty())
    }

    @Test fun `提示在内置与 Linux 模式声明归档 只读和不可用权限`() {
        val fixture = RemoteMountTestFixture(directory.root)
        fun prompt(mode: SessionRunMode, readOnly: Boolean = false, unavailable: String? = null) =
            SystemPromptSections.toolGuidance(
                setOf("read_file", "remote_mount_archive", "remote_mount_download"), generic = false,
                mounts = listOf(fixture.mount.copy(readOnly = readOnly, unavailableReason = unavailable)), runMode = mode,
            )
        for (mode in SessionRunMode.entries) {
            val write = prompt(mode)
            assertTrue(write.contains("`/mnt/drive/`：远程 SFTP（主机 `nas` · 可归档 · 非本地 Linux 路径）"))
            assertTrue(write.contains("写入均需逐次审批"))
            assertTrue(write.contains("使用 remote_mount_archive、remote_mount_download"))
            val read = prompt(mode, readOnly = true)
            assertTrue(read.contains("主机 `nas` · 只读 · 非本地 Linux 路径"))
            val unavailable = prompt(mode, unavailable = "离线")
            assertTrue(unavailable.contains("当前不可用；请用户检查 SSH 主机配置或网络连接"))
            assertFalse(unavailable.contains("/mount`"))
        }
    }
}
