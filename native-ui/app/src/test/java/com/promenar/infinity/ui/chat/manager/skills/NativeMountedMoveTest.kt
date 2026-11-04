package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.*
import com.promenar.infinity.infra.util.Sha256Utils
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import io.mockk.*
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 同一套行为合同同时约束普通工作区和 Linux 路径转换。 */
@RunWith(Parameterized::class)
class NativeMountedMoveTest(private val linux: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "linux={0}") fun modes() = listOf(arrayOf(false), arrayOf(true))
    }
    private val directory = Files.createTempDirectory("native-mounted-move").toFile()
    private val root = FileEntry("root", "root", null, "", "root-hash", isDirectory = true,
        physicalRootPath = directory.path, materializedPath = "/", createdAt = 1, updatedAt = 1)
    private val workspace = mockk<IWorkspaceRepository> {
        coEvery { ensureSessionRoot("session") } returns root
    }
    private val files = mockk<IFileOperationRepository>()
    private var readOnly = false
    private val store = MemoryBackend()
    private val mount = ExternalMount("docs-id", "docs", "real_path", directory.path, "session", 1)
    private val selected = SessionWorkspace.Directory(WorkspaceEntity("selected", WorkspaceEntity.KIND_SHARED, "共享目录", directory.path, 1), directory)
    private val mounts = mockk<ExternalWorkspaceAccess> {
        coEvery { workspaceMount("session") } answers { ExternalMount("ws_selected", "", "workspace", "selected", "session", 1, readOnly = readOnly) }
        coEvery { visible("session") } answers { listOf(mount.copy(readOnly = readOnly), mount.copy(id = "other-id", alias = "other")) }
        every { backend("session", any()) } answers {
            val bindings = secondArg<Map<String, String>>()
            assertThat(bindings[""]).isEqualTo("ws_selected")
            assertThat(bindings["docs"]).isEqualTo("docs-id")
            store
        }
        coEvery { entry("session", any()) } coAnswers {
            store.stat(secondArg())?.let { root.copy(uuid = "entry:${it.path}", name = it.name, materializedPath = it.path, isDirectory = it.isDirectory, sizeBytes = it.size) }
        }
        coEvery { read("session", any(), any()) } coAnswers { store.read(secondArg(), thirdArg()) }
        coEvery { readStream("session", any(), any(), any<suspend (InputStream) -> String>()) } coAnswers {
            store.readStream(secondArg(), thirdArg(), arg(3))
        }
        coEvery { write("session", any(), any(), any(), any()) } coAnswers {
            val entry = store.write(arg(1), arg(2), arg(3), arg(4))
            root.copy(uuid = "copy:${entry.path}", materializedPath = entry.path, name = entry.name, isDirectory = false)
        }
        coEvery { createDirectory("session", any()) } coAnswers {
            val entry = store.createDirectory(secondArg())
            root.copy(uuid = "copy:${entry.path}", materializedPath = entry.path, name = entry.name)
        }
        coEvery { delete("session", any(), any()) } coAnswers { store.delete(arg(1), arg(2)) }
    }
    private val rootfs = mockk<ExternalFolderBackend> {
        coEvery { stat("/") } returns ExternalFolderEntry("/", "", true, 0, 1, identity = "rootfs")
    }
    private val access: ExternalWorkspaceAccess = if (linux)
        LinuxSessionExternalWorkspaceAccess(mounts, workspace, files, { selected }, rootfs) else mounts
    private val context = object : SkillExecutionContext {
        override val sessionId = "session"
        override val agentId = "agent"
        override val workspaceRootUuid = "root"
        override val workspacePath: String? = null
    }
    private fun path(relative: String, external: Boolean = false) =
        if (external) "/mnt/docs$relative" else (if (linux) "/workspace" else "") + relative
    private suspend fun move(source: String, destination: String, expected: String? = null, approved: String? = null): com.promenar.infinity.data.model.ToolResult {
        val args = buildJsonObject {
            put("path", source); put("newParentPath", destination.substringBeforeLast('/').ifEmpty { "/" })
            put("newName", destination.substringAfterLast('/')); expected?.let { put("expected_sha256", it) }
        }
        val invocation = if (approved == null) context else object : SkillExecutionContext by context {
            override val isApprovalBoundExecution = true
            override val approvedExecutionTarget = approved
        }
        return MountedWorkspaceSkill(MoveFileSkill(workspace, FileIndexEventSink.None), access, workspace, files,
            FileIndexEventSink.None, captureFileChanges = false, linuxPaths = linux).execute(args, invocation)
    }
    @After fun cleanup() { directory.deleteRecursively(); com.promenar.infinity.startup.StartupWriteBarrier.resetForTesting() }

    @Test fun `同挂载文件改名跨目录和目录移动保留后端身份且无备份`() = runBlocking {
        for (external in listOf(false, true)) {
            for ((source, target, isDirectory) in listOf(Triple("/a", "/b", false), Triple("/c", "/dest/c", false), Triple("/tree", "/dest/tree", true))) {
                val prefix = if (external) "/mnt/docs" else ""
                store.add(prefix + source, isDirectory)
                store.add(prefix + "/dest", true)
                if (isDirectory) store.add(prefix + source + "/child")
                val identity = store.nodes.getValue(prefix + source).identity
                val result = move(path(source, external), path(target, external))
                assertWithMessage(result.content).that(result.status).isEqualTo("success")
                assertThat(result.content).contains("已移动（原位重命名）")
                assertThat(result.content).contains("Moved (in-place rename)")
                assertThat(store.nodes[prefix + source]).isNull()
                assertThat(store.nodes.getValue(prefix + target).identity).isEqualTo(identity)
                if (isDirectory) assertThat(store.nodes).containsKey(prefix + target + "/child")
            }
        }
        assertThat(store.moves).hasSize(6)
        assertThat(store.reads).isEqualTo(0)
    }

    @Test fun `大文件与千条目目录移动不进入受限快照`() = runBlocking {
        store.add("/large", size = 4L * 1024 * 1024 * 1024)
        store.add("/tree", true)
        repeat(1005) { store.add("/tree/item$it") }
        for ((from, to) in listOf("/large" to "/renamed", "/tree" to "/renamed-tree")) {
            val result = move(path(from), path(to))
            assertWithMessage(result.content).that(result.status).isEqualTo("success")
        }
        assertThat(store.nodes.getValue("/renamed").size).isEqualTo(4L * 1024 * 1024 * 1024)
        assertThat(store.nodes.keys.count { it.startsWith("/renamed-tree/") }).isEqualTo(1005)
        assertThat(store.reads).isEqualTo(0)
    }

    @Test fun `流式版本校验允许超过32MiB且拒绝不一致版本`() = runBlocking {
        store.add("/large", size = 80L * 1024 * 1024)
        val mismatch = move(path("/large"), path("/rejected"), "wrong")
        assertThat(mismatch.content).contains("CONFLICT")
        assertThat(store.moves).isEmpty()
        assertThat(store.nodes).containsKey("/large")
        val result = move(path("/large"), path("/verified"), Sha256Utils.hash("payload"))
        assertWithMessage(result.content).that(result.status).isEqualTo("success")
        assertThat(store.streamLimits).containsExactly(Long.MAX_VALUE, Long.MAX_VALUE)
        assertThat(store.reads).isEqualTo(0)
    }

    @Test fun `后端不支持移动时使用流式复制校验`() = runBlocking {
        store.add("/source")
        store.failure = "UNSUPPORTED"
        val result = move(path("/source"), path("/target"))
        assertWithMessage(result.content).that(result.status).isEqualTo("success")
        assertThat(result.content).contains("已复制、校验并移动")
        assertThat(result.content).contains("Copied, verified and moved")
        assertThat(store.moves).containsExactly("/source" to "/target")
        assertThat(store.nodes).doesNotContainKey("/source")
        assertThat(store.nodes.getValue("/target").identity).isNotEqualTo("id:/source")
        assertThat(store.streamLimits).isNotEmpty()
    }

    @Test fun `跨挂载不需要回收备份且版本使用无界流读取`() = runBlocking {
        for (destination in listOf(path("/target"), "/mnt/other/target")) {
            store.add("/mnt/docs/source")
            val result = move("/mnt/docs/source", destination, Sha256Utils.hash("payload"))
            assertWithMessage(result.content).that(result.status).isEqualTo("success")
            assertThat(result.content).contains("已复制、校验并移动")
            assertThat(store.nodes).doesNotContainKey("/mnt/docs/source")
        }
        assertThat(store.moves).isEmpty()
        assertThat(store.streamLimits).doesNotContain(32L * 1024 * 1024)
    }

    @Test fun `只读挂载拒绝原生移动和备份`() = runBlocking {
        readOnly = true
        store.add("/mnt/docs/source")
        val result = move("/mnt/docs/source", "/mnt/docs/target")
        assertThat(result.content).contains("READ_ONLY")
        assertThat(store.moves).isEmpty()
    }

    @Test fun `后端真实错误直接返回且不转入复制`() = runBlocking {
        for (code in listOf("NAME_EXISTS", "NOT_FOUND", "RECOVERY_REQUIRED", "CONFLICT")) {
            store.add("/source")
            store.failure = code
            val result = move(path("/source"), path("/target"))
            assertThat(result.status).isEqualTo("error")
            assertThat(result.content).contains(code)
        }
    }

    @Test fun `目录不能移动到子目录且过期审批身份拒绝写入`() = runBlocking {
        store.add("/tree", true)
        assertThat(move(path("/tree"), path("/tree/child")).content).contains("INVALID_PATH")
        assertThat(move(path("/tree"), path("/target"), approved = """["move_file",{"mountIds":{"":"stale"}}]""").content).contains("CONFLICT")
        assertThat(store.moves).isEmpty()
    }

    @Test fun `私有命名空间的流式读取向底层传递私有相对路径`() = runBlocking {
        store.add("/source")
        val backend = com.promenar.infinity.data.workspace.SessionPrivateWorkspaceBackend(store)
        val content = backend.readStream("/session/source", Long.MAX_VALUE) { it.readBytes().toString(Charsets.UTF_8) }
        assertThat(content).isEqualTo("payload")
    }

    @Test fun `同名目标和来源缺失由后端拒绝且不备份`() = runBlocking {
        store.add("/source")
        store.add("/target")
        assertThat(move(path("/source"), path("/target")).content).contains("NAME_EXISTS")
        assertThat(store.nodes).containsKey("/source")
        assertThat(move(path("/absent"), path("/target")).content).contains("NOT_FOUND")
    }

    private class MemoryBackend : ExternalFolderBackend {
        val nodes = linkedMapOf<String, ExternalFolderEntry>()
        val moves = mutableListOf<Pair<String, String>>()
        val streamLimits = mutableListOf<Long>()
        var reads = 0
        var failure: String? = null
        fun add(path: String, directory: Boolean = false, size: Long = 7) {
            nodes[path] = ExternalFolderEntry(path, path.substringAfterLast('/'), directory, if (directory) 0 else size, 1, identity = "id:$path")
        }
        override suspend fun stat(path: String) = nodes[path]
        override suspend fun list(path: String) = nodes.values.filter { it.path.substringBeforeLast('/') == path }
        override suspend fun read(path: String, maxBytes: Long): ByteArray {
            reads++
            if (nodes.getValue(path).size > maxBytes) throw ExternalFolderException("TOO_LARGE", "读取超限")
            return "payload".toByteArray()
        }
        override suspend fun <T> readStream(path: String, maxBytes: Long, consume: suspend (InputStream) -> T): T {
            if (path !in nodes) throw ExternalFolderException("NOT_FOUND", "流式来源不存在：$path")
            streamLimits.add(maxBytes)
            return "payload".byteInputStream().use { consume(it) }
        }
        override suspend fun move(sourcePath: String, targetPath: String) {
            moves.add(sourcePath to targetPath)
            failure?.let { throw ExternalFolderException(it, "后端拒绝移动") }
            if (sourcePath !in nodes) throw ExternalFolderException("NOT_FOUND", "来源不存在")
            if (targetPath in nodes) throw ExternalFolderException("NAME_EXISTS", "目标已存在")
            val moved = nodes.filterKeys { it == sourcePath || it.startsWith("$sourcePath/") }
            moved.forEach { (path, entry) ->
                nodes.remove(path)
                val target = targetPath + path.removePrefix(sourcePath)
                nodes[target] = entry.copy(path = target, name = target.substringAfterLast('/'))
            }
        }
        override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean): ExternalFolderEntry {
            if (createOnly && path in nodes) throw ExternalFolderException("NAME_EXISTS", "目标已存在")
            add(path)
            return nodes.getValue(path).copy(identity = "copy:$path").also { nodes[path] = it }
        }
        override suspend fun createDirectory(path: String): ExternalFolderEntry { add(path, true); return nodes.getValue(path) }
        override suspend fun delete(path: String, expectedSha256: String?) { nodes.keys.removeAll { it == path || it.startsWith("$path/") } }
    }
}
