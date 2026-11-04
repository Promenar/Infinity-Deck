package com.promenar.infinity.data.storage

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.generation.resolveSessionToolTarget
import com.promenar.infinity.data.local.db.dao.ExternalMountDao
import com.promenar.infinity.data.local.db.dao.SessionDao
import com.promenar.infinity.data.local.db.dao.WorkspaceDao
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.repository.ToolAllowlistStore
import com.promenar.infinity.data.script.ScriptFileApprovalBroker
import com.promenar.infinity.data.workspace.SessionWorkspaceResolver
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.script.ScriptFailure
import com.promenar.infinity.domain.storage.*
import com.promenar.infinity.domain.tool.ToolExecutionTargets
import com.promenar.infinity.infra.util.Sha256Utils
import com.promenar.infinity.ui.chat.diff.ApprovalDiffPreview
import com.promenar.infinity.ui.chat.diff.ApprovalDiffPreviewLoader
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.registry.toProtocolTool
import com.promenar.infinity.ui.chat.manager.skills.*
import io.mockk.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

class SkillsVirtualWorkspaceTest {
    private val directory = Files.createTempDirectory("skills-vfs").toFile().canonicalFile
    private val appFiles = File(directory, "files").apply { mkdirs() }
    private val userSkills = File(appFiles, "agent_skills").apply { mkdirs() }
    private val projects = File(directory, "projects")
    private val repo = File(projects, "demo").apply { mkdirs() }
    private val external = File(directory, "external").apply { mkdirs() }
    private var rooted = true
    private val context = mockk<Context> {
        every { filesDir } returns appFiles
        every { noBackupFilesDir } returns File(directory, "no-backup")
    }
    private val sessionDao = mockk<SessionDao> {
        coEvery { getById(any()) } answers {
            SessionEntity(firstArg(), "agent", workspaceId = if (rooted) "ws" else null, createdAt = 1, updatedAt = 1)
        }
    }
    private val workspaceDao = mockk<WorkspaceDao> {
        coEvery { getById("ws") } returns WorkspaceEntity("ws", WorkspaceEntity.KIND_PROJECT, "演示", "demo", 1)
    }
    private val fileSystem = JvmBoundRealPathFileSystem()
    private val resolver = SessionWorkspaceResolver(sessionDao, workspaceDao, projects, mockk(), projectBackend = { fileSystem.backend(it) })
    private val dao = mockk<ExternalMountDao> {
        coEvery { visible(any()) } returns emptyList()
        every { observeVisible(any()) } returns flowOf(emptyList())
        coEvery { all() } returns emptyList()
    }
    private val workspace = mockk<IWorkspaceRepository>()
    private val files = mockk<IFileOperationRepository>()
    private val manager = ExternalFolderManager(context, dao, workspace, FileIndexEventSink.None, resolver) { mount ->
        when {
            mount.isWorkspaceRoot -> null
            mount.id == "builtin:skills" -> fileSystem.backend(userSkills)
            else -> fileSystem.backend(external)
        }
    }
    private val execution = object : SkillExecutionContext {
        override val sessionId = "session"
        override val agentId = "agent"
        override val workspaceRootUuid = "root"
        override val workspacePath: String? = null
    }
    private fun args(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private suspend fun execute(skill: SkillDefinition, raw: String): com.promenar.infinity.data.model.ToolResult {
        val target = resolveSessionToolTarget(Session("session", "agent", workspaceRootUuid = "root"), skill.toProtocolTool(), raw, workspace, manager)
        val approved = object : SkillExecutionContext by execution {
            override val approvedExecutionTarget = target
            override val isApprovalBoundExecution = true
        }
        return MountedWorkspaceSkill(skill, manager, workspace, files, FileIndexEventSink.None).execute(args(raw), approved)
    }
    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun `skills是独立保留根并在两种工作区模式下归属相同`() = runBlocking {
        assertThat(ExternalMountPaths.parse("/skills")).isEqualTo("skills" to "/")
        assertThat(ExternalMountPaths.parse("//skills/demo/SKILL.md/")).isEqualTo("skills" to "/demo/SKILL.md")
        for (root in listOf(true, false)) {
            assertThat(ExternalMountPaths.aliasOf("/skills/demo", root)).isEqualTo("skills")
            assertThat(ExternalMountPaths.aliasOf("/skills-other", root)).isEqualTo(if (root) "" else null)
        }
        assertThrows(ExternalFolderException::class.java) { ExternalMountPaths.validateAlias("skills") }
        assertThrows(ExternalFolderException::class.java) { ExternalMountPaths.parse("/mnt/skills/a") }
        val mount = manager.visible("session").single { it.alias == "skills" }
        assertThat(manager.visible("another")).contains(mount.copy(sessionId = "another"))
        assertThat(ExternalMountPaths.uuidPath(ExternalMountPaths.uuid(mount, "/demo/SKILL.md"), listOf(mount)))
            .isEqualTo("/skills/demo/SKILL.md")
        assertThat(manager.list("session", "/mnt")).isEmpty()
    }

    @Test fun `文件工具在两种模式下读写列表移动并永久删除技能`() = runBlocking {
        for (root in listOf(true, false)) {
            rooted = root
            assertThat(execute(CreateDirectorySkill(workspace), """{"parentPath":"/skills","name":"demo"}""").status).isEqualTo("success")
            assertThat(execute(CreateFileSkill(workspace, files), """{"parentPath":"/skills/demo","name":"SKILL.md","content":"one"}""").status).isEqualTo("success")
            val entry = manager.entry("session", "/skills/demo/SKILL.md")!!
            assertThat(execute(FileReadSkill(files, workspace), """{"uuid":"${entry.uuid}"}""").content).contains("one")
            assertThat(execute(FileWriteSkill(files, workspace), """{"path":"/skills/demo/SKILL.md","content":"two","expected_sha256":"${entry.hash}"}""").status).isEqualTo("success")
            assertThat(File(userSkills, "demo/SKILL.md").readText()).isEqualTo("two")
            assertThat(execute(FileListSkill(workspace), """{"path":"/skills/demo"}""").content).contains("/skills/demo/SKILL.md")
            assertThat(execute(MoveFileSkill(workspace, FileIndexEventSink.None), """{"path":"/skills/demo/SKILL.md","newName":"README.md"}""").status).isEqualTo("success")
            assertThat(File(userSkills, "demo/SKILL.md").exists()).isFalse()
            assertThat(execute(DeleteFileSkill(workspace), """{"path":"/skills/demo/README.md"}""").status).isEqualTo("success")
            assertThat(File(userSkills, "demo/README.md").exists()).isFalse()
            execute(DeleteFileSkill(workspace), """{"path":"/skills/demo"}""")
        }
        confirmVerified(workspace, files)
    }

    @Test fun `skills根不可变更且遮蔽工作区同名目录而不遮蔽相似前缀`() = runBlocking {
        File(repo, "skills").mkdirs()
        File(repo, "skills/local.txt").writeText("workspace")
        File(repo, "skills-other").mkdirs()
        File(userSkills, "user.txt").writeText("user")
        assertThat(execute(FileReadSkill(files, workspace), """{"path":"/skills/user.txt"}""").content).contains("user")
        assertThat(execute(FileReadSkill(files, workspace), """{"path":"/skills/local.txt"}""").status).isEqualTo("error")
        val listing = execute(FileListSkill(workspace), "{}").content
        assertThat(Regex("\\[DIR] /skills(?:\\s|$)").findAll(listing).count()).isEqualTo(1)
        assertThat(listing).contains("/skills-other")
        for (root in listOf(true, false)) {
            rooted = root
            assertThat(execute(DeleteFileSkill(workspace), """{"path":"/skills"}""").status).isEqualTo("error")
            assertThat(execute(MoveFileSkill(workspace, FileIndexEventSink.None), """{"path":"/skills","newName":"other"}""").status).isEqualTo("error")
            val uuid = manager.entry("session", "/skills")!!.uuid
            assertThat(execute(DeleteFileSkill(workspace), """{"uuid":"$uuid"}""").status).isEqualTo("error")
            assertThat(execute(MoveFileSkill(workspace, FileIndexEventSink.None), """{"uuid":"$uuid","newName":"other"}""").status).isEqualTo("error")
        }
        assertThat(File(repo, "skills/local.txt").readText()).isEqualTo("workspace")
        assertThat(File(userSkills, "user.txt").readText()).isEqualTo("user")
    }

    @Test fun `移动支持技能与工作区及用户挂载之间的传输`() = runBlocking {
        coEvery { dao.visible(any()) } returns listOf(com.promenar.infinity.data.local.db.entity.ExternalMountEntity("mounted", "docs", "real_path", external.path, "", false, 1))
        File(userSkills, "a.txt").writeText("payload")
        assertThat(execute(MoveFileSkill(workspace, FileIndexEventSink.None), """{"path":"/skills/a.txt","newParentPath":"/"}""").status).isEqualTo("success")
        assertThat(File(repo, "a.txt").readText()).isEqualTo("payload")
        assertThat(execute(MoveFileSkill(workspace, FileIndexEventSink.None), """{"path":"/a.txt","newParentPath":"/mnt/docs"}""").status).isEqualTo("success")
        assertThat(execute(MoveFileSkill(workspace, FileIndexEventSink.None), """{"path":"/mnt/docs/a.txt","newParentPath":"/skills"}""").status).isEqualTo("success")
        assertThat(File(userSkills, "a.txt").readText()).isEqualTo("payload")
    }

    @Test fun `脚本在自动模式逐次审批技能写入且忽略记忆选择`() = runBlocking {
        for (root in listOf(true, false)) {
            rooted = root
            val broker = ScriptFileApprovalBroker()
            val grants = ToolAllowlistStore(File(directory, "grants-$root"))
            val gateway = ScriptWorkspaceGateway.open(execution, workspace, manager, files, FileIndexEventSink.None, broker, null)
            for ((operation, path, content) in listOf(
                Triple("mkdir", "/skills/folder-$root", null),
                Triple("create", "/skills/test.txt", "one"),
                Triple("write", "/skills/test.txt", "two"),
                Triple("delete", "/skills/test.txt", null),
            )) {
                val hash = File(userSkills, "test.txt").takeIf { it.isFile }?.let { Sha256Utils.hash(it.readBytes()) }
                val arguments = buildJsonObject { put("path", path); content?.let { put("content", it) }; hash?.let { put("expected_sha256", it) } }
                val pending = async { gateway.invoke(operation, arguments) }
                try {
                    val approval = withTimeout(3000) { broker.pending.first { it.isNotEmpty() }.single() }
                    assertThat(ToolExecutionTargets.canRemember(approval.target)).isFalse()
                    assertThat(Json.parseToJsonElement(approval.target).jsonArray.last().jsonObject["oneShot"]?.jsonPrimitive?.booleanOrNull).isTrue()
                    broker.resolve(approval.id, true, true)
                    pending.await()
                    assertThat(grants.entries.value).isEmpty()
                    if (operation in setOf("write", "create")) assertThat(gateway.invoke("read", args("""{"path":"/skills/test.txt"}""")).jsonObject.getValue("content").jsonPrimitive.content).isEqualTo(content)
                    if (operation == "mkdir") assertThat(gateway.invoke("list", args("""{"path":"/skills"}""")).jsonArray.map { it.jsonObject["path"]?.jsonPrimitive?.content }).contains(path)
                } finally { pending.cancelAndJoin() }
            }
            assertThat(File(userSkills, "test.txt").exists()).isFalse()
        }
    }

    @Test fun `脚本项目级技能写入逐次审批并可拒绝`() = runBlocking {
        File(repo, ".agents/skills").mkdirs()
        val broker = ScriptFileApprovalBroker()
        val gateway = ScriptWorkspaceGateway.open(execution, workspace, manager, files, FileIndexEventSink.None, broker, null)
        val pending = async { runCatching { gateway.invoke("create", args("""{"path":"/.agents/skills/test.md","content":"blocked"}""")) } }
        try {
            val approval = withTimeout(3000) { broker.pending.first { it.isNotEmpty() }.single() }
            broker.resolve(approval.id, false)
            assertThat((pending.await().exceptionOrNull() as? ScriptFailure)?.code).isEqualTo("APPROVAL_DENIED")
            assertThat(File(repo, ".agents/skills/test.md").exists()).isFalse()
        } finally { pending.cancelAndJoin() }
    }

    @Test fun `技能UUID审批与差异预览使用用户级文件正文`() = runBlocking {
        for (root in listOf(true, false)) {
            rooted = root
            File(userSkills, "SKILL.md").writeText("before\n")
            val entry = manager.entry("session", "/skills/SKILL.md")!!
            val tool = FileWriteSkill(files, workspace).toProtocolTool()
            val arguments = """{"uuid":"${entry.uuid}","content":"after\n","expected_sha256":"${entry.hash}"}"""
            val target = resolveSessionToolTarget(Session("session", "agent", workspaceRootUuid = null), tool, arguments, workspace, manager)!!
            assertThat(ToolExecutionTargets.canRemember(target)).isFalse()
            assertThat(ToolExecutionTargets.display(target)).isEqualTo("/skills/SKILL.md")
            val preview = ApprovalDiffPreviewLoader(workspace, manager).load("write_file", args(arguments), "session", "root")
            assertThat(preview).isInstanceOf(ApprovalDiffPreview.Diff::class.java)
        }
    }

    @Test fun `技能变更不能直接执行或借用其它路径的审批`() = runBlocking {
        val skill = MountedWorkspaceSkill(CreateFileSkill(workspace, files), manager, workspace, files, FileIndexEventSink.None)
        val arguments = args("""{"parentPath":"/skills","name":"blocked.md","content":"blocked"}""")
        assertThat(skill.execute(arguments, execution).content).contains("APPROVAL_REQUIRED")
        val wrongApproval = object : SkillExecutionContext by execution {
            override val isApprovalBoundExecution = true
            override val approvedExecutionTarget = """["path","/safe/blocked.md",{"mountIds":{"skills":"builtin:skills"}}]"""
        }
        assertThat(skill.execute(arguments, wrongApproval).content).contains("APPROVAL_REQUIRED")
        assertThat(File(userSkills, "blocked.md").exists()).isFalse()
    }

    @Test fun `审批侧无法解析目标的技能写入在执行层被拒绝`() = runBlocking {
        for (root in listOf(true, false)) {
            rooted = root
            val result = execute(FileWriteSkill(files, workspace), """{"path":"\\\\skills\\\\demo\\\\SKILL.md","content":"x"}""")
            assertThat(result.status).isEqualTo("error")
            assertThat(result.content).contains("INVALID_PATH")
            assertThat(userSkills.walkTopDown().none { it.isFile }).isTrue()
        }
    }

    @Test fun `工作区不可用时用户级技能仍然可读`() = runBlocking {
        File(userSkills, "available.md").writeText("available")
        repo.deleteRecursively()
        assertThat(execute(FileReadSkill(files, workspace), """{"path":"/skills/available.md"}""").content).contains("available")
    }

    @Test fun `没有私有工作区根也能预览技能且UUID优先于路径`() = runBlocking {
        rooted = false
        File(userSkills, "SKILL.md").writeText("before\n")
        val entry = manager.entry("session", "/skills/SKILL.md")!!
        val preview = ApprovalDiffPreviewLoader(workspace, manager).load("write_file",
            args("""{"uuid":"${entry.uuid}","path":"/skills/missing.md","content":"after\n"}"""), "session", null)
        assertThat(preview).isInstanceOf(ApprovalDiffPreview.Diff::class.java)
    }

    @Test fun `历史skills别名保留管理入口但不污染可达挂载列表`() = runBlocking {
        val rows = listOf("skills", "docs").map { alias ->
            com.promenar.infinity.data.local.db.entity.ExternalMountEntity("legacy-$alias", alias, "real_path", external.path, "", false, 1)
        }
        coEvery { dao.visible(any()) } returns rows
        every { dao.observeVisible(any()) } returns flowOf(rows)
        assertThat(manager.list("session", "/mnt").map { it.name }).containsExactly("docs")
        assertThat(manager.visible("session").map { it.id }).doesNotContain("legacy-skills")
        assertThat(manager.observeMounts("session").first().map { it.id }).contains("legacy-skills")
        val failure = assertThrows(ExternalFolderException::class.java) { ExternalMountPaths.parse("/mnt/skills/a.md") }
        assertThat(failure.message).contains("挂载管理")
        File(userSkills, "user.md").writeText("user")
        assertThat(String(manager.read("session", "/skills/user.md"))).isEqualTo("user")
    }
}
