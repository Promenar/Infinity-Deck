package com.promenar.infinity.data.branch

import android.content.Context
import com.promenar.infinity.data.exec.linuxExecSpec
import com.promenar.infinity.data.local.db.dao.ExternalMountDao
import com.promenar.infinity.data.local.db.entity.ExternalMountEntity
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.ProtocolToolFunction
import com.promenar.infinity.data.storage.ExternalFolderManager
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.data.storage.JvmBoundRealPathFileSystem
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.data.workspace.SessionWorkspaceResolver
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.infra.util.Sha256Utils
import com.promenar.infinity.startup.StartupWriteBarrier
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.CreateFileSkill
import com.promenar.infinity.ui.chat.manager.skills.LinuxSessionExternalWorkspaceAccess
import com.promenar.infinity.ui.chat.manager.skills.LinuxSessionFileSkill
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 实际读写临时文件；仅 Room/Android 来源使用替身，文件后端与外部访问管理器均为真实实现。 */
class BranchLinuxFileNamespaceTest {
    private val temporary = Files.createTempDirectory("branch-linux-files").toFile().canonicalFile
    private val base = File(temporary, "parent").apply { mkdirs() }
    private val child = File(base, "branches/foo").apply { mkdirs() }
    private val home = File(temporary, "private-child").apply { mkdirs() }
    private val skills = File(temporary, "skills").apply { mkdirs() }
    private val knowledge = File(temporary, "knowledge").apply { mkdirs() }
    private val fileSystem = JvmBoundRealPathFileSystem()
    private val selected = SessionWorkspace.Directory(WorkspaceEntity("child-worktree", "project", "分支", "child", 1), child)
    private val resolver = mockk<SessionWorkspaceResolver> {
        coEvery { resolve("branch") } returns selected
        coEvery { resolveWorkspace("child-worktree") } returns selected
        every { backend(selected) } answers { fileSystem.backend(child) }
    }
    private var mountRows = emptyList<ExternalMountEntity>()
    private val dao = mockk<ExternalMountDao> {
        coEvery { visible(any()) } answers { mountRows.filter { it.scopeKey == firstArg<String>() } }
        every { observeVisible(any()) } answers { flowOf(mountRows) }
    }
    private val context = mockk<Context> {
        every { noBackupFilesDir } returns File(temporary, "no-backup")
        every { filesDir } returns File(temporary, "files")
    }
    private val privateRoot = FileEntry("child-private-root", "child-private-root", null, "", "private-identity",
        physicalRootPath = home.path, materializedPath = "/", isDirectory = true, createdAt = 1, updatedAt = 1)
    private val workspace = mockk<IWorkspaceRepository> {
        coEvery { ensureSessionRoot("branch") } returns privateRoot
        coEvery { getByUuid("knowledge-root", "knowledge-root") } returns privateRoot.copy(
            uuid = "knowledge-root", workspaceRootUuid = "knowledge-root", physicalRootPath = knowledge.path)
    }
    private val files = mockk<IFileOperationRepository>()
    private val rootfs = mockk<ExternalFolderBackend> {
        coEvery { stat("/") } returns ExternalFolderEntry("/", "", true, 0, 1, identity = "rootfs-identity")
    }
    private val original = ExternalFolderManager(context, dao, workspace, FileIndexEventSink.None, resolver,
        userSkillsDirectory = skills, knowledgeBaseRoots = { setOf("knowledge-root") }, knowledgeBaseAllowed = { _, _ -> true }, backendOverride = {
            when { it.id == "builtin:skills" -> fileSystem.backend(skills)
                it.sourceType == ExternalMountPaths.KNOWLEDGE_BASE_SOURCE -> fileSystem.backend(knowledge)
                else -> null }
        })
    private var namespace = BranchLinuxFileNamespace(BranchLinuxCommandWorkspace(base, "/workspace/branches/foo"), fileSystem.backend(base))
    private val access = LinuxSessionExternalWorkspaceAccess(original, workspace, files, { selected }, rootfs,
        branchNamespace = { namespace })

    @After fun cleanup() { StartupWriteBarrier.resetForTesting(); temporary.deleteRecursively() }

    private fun spec() = linuxExecSpec("pwd", child, "project", emptyList(), skills, home, namespace.commandWorkspace)
    private fun physical(guest: String): File {
        require(guest == "/workspace" || guest.startsWith("/workspace/"))
        return File(requireNotNull(spec().workspace), guest.removePrefix("/workspace").removePrefix("/"))
    }

    @Test fun `绝对workspace写读命中命令的父挂载而非分支目录`() = runBlocking {
        access.write("branch", "/workspace/probe", "parent".toByteArray(), null, true)
        assertEquals("parent", File(base, "probe").readText())
        assertFalse(File(child, "probe").exists())
        assertEquals(File(base, "probe"), physical("/workspace/probe"))
        assertEquals("parent", access.read("branch", "/workspace/probe", 1024).toString(Charsets.UTF_8))
    }

    @Test fun `相对文件路径与真实宿主shell默认cwd落到同一分支目录`() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())
        access.write("branch", "relative.txt", "file-tool".toByteArray(), null, true)
        val launch = spec()
        val command = ProcessBuilder("/bin/sh", "-c", "cat relative.txt; printf command > command.txt")
            .directory(physical(requireNotNull(launch.workingDirectory))).redirectErrorStream(true).start()
        assertEquals("file-tool", command.inputStream.bufferedReader().readText())
        assertEquals(0, command.waitFor())
        assertEquals("command", access.read("branch", "command.txt", 1024).toString(Charsets.UTF_8))
        assertEquals("file-tool", File(child, "relative.txt").readText())
        assertFalse(File(base, "relative.txt").exists())
        assertEquals(home, launch.sessionHome)
    }

    @Test fun `文件工具缺省创建父目录跟随分支cwd且审批使用相同绝对路径`() = runBlocking {
        val skill = LinuxSessionFileSkill(CreateFileSkill(workspace, files), { SessionRunMode.LINUX }, access,
            workspace, files, FileIndexEventSink.None, captureFileChanges = false)
        val invocation = object : SkillExecutionContext {
            override val sessionId = "branch"
            override val agentId = "agent"
            override val workspaceRootUuid = privateRoot.uuid
            override val workspacePath: String? = null
            override val runMode = SessionRunMode.LINUX
        }
        val result = skill.execute(Json.parseToJsonElement("""{"name":"default.txt","content":"child"}""").jsonObject, invocation)
        assertEquals(result.content, "success", result.status)
        assertEquals("child", File(child, "default.txt").readText())
        assertFalse(File(base, "default.txt").exists())
    }

    @Test fun `工作区override仅限当前操作且不会污染普通访问`() = runBlocking {
        access.write("branch", "/workspace/parent.txt", "parent".toByteArray(), null, true)
        original.write("branch", "/plain.txt", "child".toByteArray(), null, true)
        assertEquals("parent", File(base, "parent.txt").readText())
        assertEquals("child", File(child, "plain.txt").readText())
        assertFalse(File(base, "plain.txt").exists())
    }

    @Test fun `分支Linux文件命名空间可读取知识库而全部修改入口拒绝`() = runBlocking {
        mountRows = listOf(ExternalMountEntity("kb", "knowledge", ExternalMountPaths.KNOWLEDGE_BASE_SOURCE,
            "knowledge-root", "branch", true, 1))
        File(knowledge, "note.md").writeText("知识库全文")
        assertEquals("知识库全文", access.read("branch", "/mnt/knowledge/note.md", 1024).toString(Charsets.UTF_8))
        assertEquals(listOf("note.md"), access.list("branch", "/mnt/knowledge").map { it.name })
        val mount = access.visible("branch").single { it.id == "kb" }
        assertTrue(mount.readOnly)
        assertFalse(mount.commandAccessible)
        assertEquals(null, ExternalMountPaths.commandAccess(mount).realPath)
        assertFalse(linuxExecSpec("pwd", child, "project", listOf(mount), skills, home, namespace.commandWorkspace)
            .mounts.any { it.guestPath == "/mnt/knowledge" })
        val readOnlyPath = mount.copy(id = "readonly-path", alias = "readonly-path", sourceType = "real_path", sourceId = knowledge.path)
        assertFalse(linuxExecSpec("pwd", child, "project", listOf(readOnlyPath), skills, home, namespace.commandWorkspace)
            .mounts.any { it.guestPath == "/mnt/readonly-path" })
        val backend = access.backend("branch")
        val actions: List<suspend () -> Any?> = listOf(
            { access.write("branch", "/mnt/knowledge/note.md", byteArrayOf(), null, false) },
            { access.createDirectory("branch", "/mnt/knowledge/new") },
            { access.delete("branch", "/mnt/knowledge/note.md", null) },
            { backend.move("/mnt/knowledge/note.md", "/mnt/knowledge/new") },
            { backend.copy("/mnt/knowledge/note.md", "/mnt/knowledge/new") },
            { access.importSnapshot("branch", privateRoot.uuid, "/mnt/knowledge/note.md") },
        )
        actions.forEach { action ->
            val failure = assertThrows(ExternalFolderException::class.java) { runBlocking { action() } }
            assertEquals("READ_ONLY", failure.code)
            assertEquals("知识库挂载为只读", failure.message)
        }
        val skill = LinuxSessionFileSkill(CreateFileSkill(workspace, files), { SessionRunMode.LINUX }, access,
            workspace, files, FileIndexEventSink.None, captureFileChanges = false)
        val invocation = object : SkillExecutionContext {
            override val sessionId = "branch"
            override val agentId = "agent"
            override val workspaceRootUuid = privateRoot.uuid
            override val workspacePath: String? = null
            override val runMode = SessionRunMode.LINUX
        }
        val result = skill.execute(Json.parseToJsonElement("""{"parentPath":"/mnt/knowledge","name":"new.md","content":"修改"}""").jsonObject, invocation)
        assertEquals("error", result.status)
        assertTrue(result.content.contains("READ_ONLY：知识库挂载为只读"))
        assertEquals("知识库全文", File(knowledge, "note.md").readText())
        assertFalse(File(knowledge, "new.md").exists())
    }

    @Test fun `永久删除只操作真实父目录`() = runBlocking {
        val bytes = "recover-parent".toByteArray()
        val hash = Sha256Utils.hash(bytes)
        access.write("branch", "/workspace/recycle.txt", bytes, null, true)
        access.read("branch", "/workspace/recycle.txt", 1024)
        assertEquals("recover-parent", access.version("branch", "/workspace/recycle.txt", hash)!!.toString(Charsets.UTF_8))
        access.delete("branch", "/workspace/recycle.txt", hash)
        assertFalse(File(base, "recycle.txt").exists())
        assertFalse(File(base, "recycle.txt").exists())
        assertFalse(File(child, "recycle.txt").exists())
    }

    @Test fun `审批身份绑定base目录与cwd且工具参数不能注入工作区override`() = runBlocking {
        val tool = ProtocolTool("function", ProtocolToolFunction("write_file", "", "{}"))
        val args = Json.parseToJsonElement("""{"path":"probe","workingDirectory":"/workspace","baseRoot":"/other"}""").jsonObject
        val target = access.executionTarget("branch", tool, args)!!
        assertTrue(target.contains("/workspace/branches/foo/probe"))
        assertEquals(base, namespace.commandWorkspace.baseRoot)
        namespace = namespace.copy(commandWorkspace = BranchLinuxCommandWorkspace(base, "/workspace/branches/bar"))
        assertNotEquals(target, access.executionTarget("branch", tool, args))
        val other = File(temporary, "other-base").apply { mkdirs() }
        namespace = BranchLinuxFileNamespace(BranchLinuxCommandWorkspace(other, "/workspace/branches/bar"), fileSystem.backend(other))
        assertNotEquals(target, access.executionTarget("branch", tool, args))
    }

    @Test fun `相同路径的分支目录被替换后旧审批身份失效`() = runBlocking {
        val tool = ProtocolTool("function", ProtocolToolFunction("write_file", "", "{}"))
        val args = Json.parseToJsonElement("""{"path":"probe"}""").jsonObject
        val approved = access.executionTarget("branch", tool, args)
        assertTrue(child.renameTo(File(base, "branches/original")))
        assertTrue(child.mkdir())
        assertNotEquals(approved, access.executionTarget("branch", tool, args))
    }

    @Test fun `workspace内mnt和skills实物目录不被外部虚拟根遮蔽且可删除`() = runBlocking {
        for (name in listOf("mnt", "skills")) {
            File(base, name).mkdirs()
            val path = "/workspace/$name/probe.txt"
            val bytes = "base-$name".toByteArray()
            val hash = Sha256Utils.hash(bytes)
            access.write("branch", path, bytes, null, true)
            assertEquals("base-$name", File(base, "$name/probe.txt").readText())
            assertEquals("base-$name", access.read("branch", path, 1024).toString(Charsets.UTF_8))
            assertTrue(access.list("branch", "/workspace").any { it.path == "/workspace/$name" })
            assertTrue(access.list("branch", "/workspace/$name").any { it.path == path })
            access.delete("branch", path, hash)
            assertFalse(File(base, "$name/probe.txt").exists())
        }
        File(skills, "probe.txt").writeText("user-skills")
        assertEquals("user-skills", access.read("branch", "/mnt/skills/probe.txt", 1024).toString(Charsets.UTF_8))
    }
}
