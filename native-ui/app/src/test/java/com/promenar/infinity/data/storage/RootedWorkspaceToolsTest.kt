package com.promenar.infinity.data.storage

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.generation.resolveSessionToolTarget
import com.promenar.infinity.data.local.db.dao.ExternalMountDao
import com.promenar.infinity.data.local.db.dao.SessionDao
import com.promenar.infinity.data.local.db.dao.WorkspaceDao
import com.promenar.infinity.data.local.db.entity.ExternalMountEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.ProtocolToolFunction
import com.promenar.infinity.data.repository.ToolAllowlistStore
import com.promenar.infinity.data.script.ScriptFileApprovalBroker
import com.promenar.infinity.data.workspace.SessionWorkspaceResolver
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.script.ScriptFailure
import com.promenar.infinity.infra.util.Sha256Utils
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.*
import io.mockk.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

/** 自选目录工作区以 `/` 为根接入文件工具、审批目标与脚本网关；私有工作区与 Room 一律不被触碰。 */
class RootedWorkspaceToolsTest {
    private val directory = Files.createTempDirectory("rooted-workspace").toFile().canonicalFile
    private val projects = File(directory, "projects")
    private val repo = File(projects, "demo").apply { mkdirs() }
    private val other = File(projects, "other").apply { mkdirs() }
    private val context = mockk<Context> { every { noBackupFilesDir } returns File(directory, "no-backup") }
    private var boundWorkspace: String? = "ws-1"
    private val records = mapOf(
        "ws-1" to WorkspaceEntity("ws-1", WorkspaceEntity.KIND_PROJECT, "演示仓库", "demo", 1),
        "ws-2" to WorkspaceEntity("ws-2", WorkspaceEntity.KIND_PROJECT, "另一个", "other", 2),
    )
    private val sessionDao = mockk<SessionDao> {
        coEvery { getById("session") } answers { SessionEntity("session", "agent", workspaceId = boundWorkspace, createdAt = 1, updatedAt = 1) }
    }
    private val workspaceDao = mockk<WorkspaceDao> { coEvery { getById(any()) } answers { records[firstArg()] } }
    private val fileSystem = JvmBoundRealPathFileSystem()
    private val resolver = SessionWorkspaceResolver(sessionDao, workspaceDao, projects, mockk(), projectBackend = { fileSystem.backend(it) })
    private val rows = MutableStateFlow(listOf(ExternalMountEntity("mount", "termux", "saf_tree", "content://test/tree/root", "session", false, 1)))
    private val mountDao = mockk<ExternalMountDao> {
        coEvery { visible(any()) } answers { rows.value.filter { it.scopeKey.isEmpty() || it.scopeKey == firstArg<String>() } }
        every { observeVisible(any()) } answers { val id = firstArg<String>(); rows.map { list -> list.filter { it.scopeKey == id } } }
        coEvery { all() } answers { rows.value }
        coEvery { update(any()) } just Runs
    }
    private val memory = TransferMemoryBackend()
    /** 私有工作区仓库与文件操作不设任何应答：被调用即失败。 */
    private val workspace = mockk<IWorkspaceRepository>()
    private val files = mockk<IFileOperationRepository>()
    private val manager = ExternalFolderManager(context, mountDao, workspace, FileIndexEventSink.None, resolver) { mount ->
        if (mount.isWorkspaceRoot) null else memory
    }
    private val execution = object : SkillExecutionContext {
        override val sessionId = "session"
        override val agentId = "agent"
        override val workspaceRootUuid = "root"
        override val workspacePath: String? = null
    }

    @After fun cleanup() { directory.deleteRecursively() }

    private fun skill(original: SkillDefinition) = MountedWorkspaceSkill(original, manager, workspace, files, FileIndexEventSink.None)
    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject
    private fun hash(file: File) = Sha256Utils.hash(file.readBytes())

    @Test fun `自选目录与挂载同目录无操作不改动且同目录跨目录改名均成功`() = runBlocking<Unit> {
        for (base in listOf("", "/mnt/termux")) {
            val create = skill(CreateFileSkill(workspace, files))
            assertThat(create.execute(args("""{"parentPath":"${base.ifEmpty { "/" }}","name":"a.txt","content":"content"}"""), execution).status).isEqualTo("success")
            val move = skill(MoveFileSkill(workspace, FileIndexEventSink.None))
            val path = "$base/a.txt"
            val before = manager.entry("session", path)!!
            for (name in listOf("", ",\"newName\":\"a.txt\"")) {
                val result = move.execute(args("""{"path":"$path","newParentPath":"${base.ifEmpty { "/" }}"$name}"""), execution)
                assertThat(result.status).isEqualTo("success")
                assertThat(result.fileChanges.orEmpty()).isEmpty()
            }
            assertThat(manager.entry("session", path)!!.updatedAt).isEqualTo(before.updatedAt)
            assertThat(move.execute(args("""{"path":"$path","newParentPath":"${base.ifEmpty { "/" }}","newName":"b.txt"}"""), execution).status).isEqualTo("success")
            assertThat(String(manager.read("session", "$base/b.txt", 1024))).isEqualTo("content")
            assertThat(skill(CreateDirectorySkill(workspace)).execute(args("""{"parentPath":"${base.ifEmpty { "/" }}","name":"target"}"""), execution).status).isEqualTo("success")
            assertThat(move.execute(args("""{"path":"$base/b.txt","newParentPath":"$base/target","newName":"c.txt"}"""), execution).status).isEqualTo("success")
            assertThat(String(manager.read("session", "$base/target/c.txt", 1024))).isEqualTo("content")
            assertThat(create.execute(args("""{"parentPath":"$base/target","name":"occupied.txt","content":"keep"}"""), execution).status).isEqualTo("success")
            val occupied = move.execute(args("""{"path":"$base/target/c.txt","newParentPath":"$base/target","newName":"occupied.txt"}"""), execution)
            assertThat(occupied.status).isEqualTo("error")
            assertThat(String(manager.read("session", "$base/target/c.txt", 1024))).isEqualTo("content")
            assertThat(String(manager.read("session", "$base/target/occupied.txt", 1024))).isEqualTo("keep")

        }
    }

    @Test fun `十个文件工具在自选目录读写且不触碰私有工作区`() = runBlocking<Unit> {
        File(repo, "mnt").mkdirs()
        val created = skill(CreateFileSkill(workspace, files)).execute(args("""{"name":"a.txt","content":"one\ntwo"}"""), execution)
        assertThat(created.status).isEqualTo("success")
        assertThat(File(repo, "a.txt").readText()).isEqualTo("one\ntwo")
        val read = skill(FileReadSkill(files, workspace)).execute(args("""{"path":"/a.txt"}"""), execution)
        assertThat(read.content).contains("1| one")
        assertThat(read.content).contains("sha256: ${hash(File(repo, "a.txt"))}")
        val write = skill(FileWriteSkill(files, workspace))
        assertThat(write.execute(args("""{"path":"/a.txt","content":"lost","expected_sha256":"${"0".repeat(64)}"}"""), execution).status).isEqualTo("error")
        val original = hash(File(repo, "a.txt"))
        val saved = write.execute(args("""{"path":"/a.txt","content":"new","expected_sha256":"$original"}"""), execution)
        assertThat(saved.content).startsWith("已保存：/a.txt")
        assertThat(saved.content).doesNotContain("知识库")
        assertThat(skill(FilePatchSkill(files, workspace)).execute(
            args("""{"path":"/a.txt","expected_sha256":"${hash(File(repo, "a.txt"))}","operations":[{"action":"insert_after","afterLine":1,"newContent":"last"}]}"""), execution,
        ).status).isEqualTo("success")
        assertThat(File(repo, "a.txt").readText()).isEqualTo("new\nlast")
        assertThat(skill(FileDiffSkill(files, workspace)).execute(args("""{"path":"/a.txt","basisHash":"$original"}"""), execution).content).contains("removed")
        assertThat(skill(CreateDirectorySkill(workspace)).execute(args("""{"name":"docs"}"""), execution).status).isEqualTo("success")
        assertThat(File(repo, "docs").isDirectory).isTrue()

        val listing = skill(FileListSkill(workspace)).execute(args("{}"), execution).content
        assertThat(listing).contains("[FILE] /a.txt")
        assertThat(listing).contains("[DIR] /docs")
        // 目录中真实存在的顶层 mnt 被虚拟挂载目录遮蔽，只列出一次挂载根。
        assertThat(Regex("\\[DIR] /mnt\\b").findAll(listing).count()).isEqualTo(1)
        assertThat(skill(FileSearchSkill(workspace, files)).execute(args("""{"query":"a.txt"}"""), execution).content).contains("/a.txt")

        assertThat(skill(MoveFileSkill(workspace, FileIndexEventSink.None)).execute(args("""{"path":"/a.txt","newParentPath":"/docs"}"""), execution).status).isEqualTo("success")
        assertThat(File(repo, "a.txt").exists()).isFalse()
        assertThat(skill(DeleteFileSkill(workspace)).execute(args("""{"path":"/docs/a.txt"}"""), execution).status).isEqualTo("success")
        assertThat(File(repo, "docs/a.txt").exists()).isFalse()
        assertThat(skill(DeleteFileSkill(workspace)).execute(args("""{"path":"/"}"""), execution).status).isEqualTo("error")
        assertThat(repo.isDirectory).isTrue()
        confirmVerified(workspace, files)
    }

    @Test fun `私有工作区的uuid不可达而挂载路径照常可用`() = runBlocking<Unit> {
        val leaked = skill(FileReadSkill(files, workspace)).execute(args("""{"uuid":"private-uuid"}"""), execution)
        assertThat(leaked.status).isEqualTo("error")
        assertThat(skill(CreateFileSkill(workspace, files)).execute(args("""{"parentPath":"/mnt/termux","name":"m.txt","content":"mounted"}"""), execution).status).isEqualTo("success")
        assertThat(String(memory.read("/m.txt"))).isEqualTo("mounted")
        assertThat(File(repo, "mnt").exists()).isFalse()
        confirmVerified(workspace, files)
    }

    @Test fun `审批目标绑定工作区身份且切换工作区后失效`() = runBlocking<Unit> {
        File(repo, "a.txt").writeText("v1")
        val tool = ProtocolTool(function = ProtocolToolFunction("write_file", "", "{}"))
        val session = Session(id = "session", agentId = "agent", title = "test", workspaceRootUuid = "root")
        val arguments = """{"path":"/a.txt","content":"v2","expected_sha256":"${hash(File(repo, "a.txt"))}"}"""
        val target = resolveSessionToolTarget(session, tool, arguments, workspace, manager)!!
        val mountIds = (Json.parseToJsonElement(target).jsonArray.last() as JsonObject).getValue("mountIds").jsonObject
        assertThat(mountIds.getValue("").jsonPrimitive.content).isEqualTo("ws_ws-1")
        fun approved(target: String) = object : SkillExecutionContext by execution {
            override val approvedExecutionTarget = target
            override val isApprovalBoundExecution = true
        }
        boundWorkspace = "ws-2"
        File(other, "a.txt").writeText("v1")
        assertThat(skill(FileWriteSkill(files, workspace)).execute(args(arguments), approved(target)).content).contains("CONFLICT")
        assertThat(File(other, "a.txt").readText()).isEqualTo("v1")
        // 私有工作区时批准的同一路径，在会话改用自选目录后也必须重新审批。
        boundWorkspace = null
        coEvery { workspace.getByUuid(any(), any()) } returns null
        val privateTarget = resolveSessionToolTarget(session, tool, arguments, workspace, manager)!!
        assertThat(privateTarget).isNotEqualTo(target)
        boundWorkspace = "ws-1"
        assertThat(skill(FileWriteSkill(files, workspace)).execute(args(arguments), approved(privateTarget)).content).contains("CONFLICT")
        assertThat(File(repo, "a.txt").readText()).isEqualTo("v1")
        assertThat(skill(FileWriteSkill(files, workspace)).execute(args(arguments), approved(target)).status).isEqualTo("success")
        assertThat(File(repo, "a.txt").readText()).isEqualTo("v2")
    }

    @Test fun `工作区目录不可用时拒绝且不回退到私有工作区`() = runBlocking<Unit> {
        repo.deleteRecursively()
        val listed = skill(FileListSkill(workspace)).execute(args("{}"), execution)
        assertThat(listed.status).isEqualTo("error")
        val created = skill(CreateFileSkill(workspace, files)).execute(args("""{"name":"a.txt","content":"x"}"""), execution)
        assertThat(created.status).isEqualTo("error")
        assertThat(created.content).contains("当前工作区不可用")
        assertThat(repo.exists()).isFalse()
        confirmVerified(workspace, files)
    }

    @Test fun `脚本网关在自选目录读写并拒绝私有工作区路径回退`() = runBlocking<Unit> {
        File(repo, "lib").mkdirs()
        File(repo, "lib/data.txt").writeText("hello")
        val gateway = ScriptWorkspaceGateway.open(execution, workspace, manager, files, FileIndexEventSink.None,
            ScriptFileApprovalBroker(), null)
        val read = gateway.invoke("read", buildJsonObject { put("path", "/lib/data.txt") }).jsonObject
        assertThat(read.getValue("content").jsonPrimitive.content).isEqualTo("hello")
        gateway.invoke("create", buildJsonObject { put("path", "/lib/out.txt"); put("content", "made") })
        assertThat(File(repo, "lib/out.txt").readText()).isEqualTo("made")
        val names = gateway.invoke("list", buildJsonObject { put("path", "/lib") }).jsonArray.map { it.jsonObject.getValue("path").jsonPrimitive.content }
        assertThat(names).containsExactly("/lib/data.txt", "/lib/out.txt")
        assertThat(assertThrows(ScriptFailure::class.java) {
            runBlocking { gateway.invoke("read", buildJsonObject { put("path", "/mnt") }) }
        }.code).isEqualTo("NOT_FOUND")
        boundWorkspace = "ws-2"
        assertThat(assertThrows(ScriptFailure::class.java) {
            runBlocking { gateway.invoke("read", buildJsonObject { put("path", "/lib/data.txt") }) }
        }.code).isEqualTo("MOUNT_CHANGED")
        confirmVerified(workspace, files)
    }
}
