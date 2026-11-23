package com.promenar.infinity.data.workspace

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.repository.DiffResult
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.repository.PatchOperation
import com.promenar.infinity.domain.repository.PatchResult
import com.promenar.infinity.domain.repository.ReadResult
import com.promenar.infinity.domain.repository.ReadWindowRequest
import com.promenar.infinity.domain.repository.ReadWindowResult
import com.promenar.infinity.domain.repository.RenameResult
import com.promenar.infinity.domain.repository.WriteResult
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import com.promenar.infinity.domain.tool.ApprovalGrantCategory
import com.promenar.infinity.domain.tool.ToolExecutionPolicy
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.infra.util.Sha256Utils
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.registry.toProtocolTool
import com.promenar.infinity.ui.chat.manager.skills.BuiltinRealPathWorkspaceAccess
import com.promenar.infinity.ui.chat.manager.skills.CreateFileSkill
import com.promenar.infinity.ui.chat.manager.skills.DeleteFileSkill
import com.promenar.infinity.ui.chat.manager.skills.FileListSkill
import com.promenar.infinity.ui.chat.manager.skills.FileReadSkill
import com.promenar.infinity.ui.chat.manager.skills.FileWriteSkill
import com.promenar.infinity.ui.chat.manager.skills.LinuxSessionExternalWorkspaceAccess
import com.promenar.infinity.ui.chat.manager.skills.LinuxSessionFileSkill
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BuiltinRealPathWorkspaceAccessTest {
    private lateinit var rootTemp: File
    private lateinit var appDataDir: File
    private lateinit var privateDir: File
    private lateinit var skillsDir: File
    private lateinit var externalDir: File
    private lateinit var realMountDir: File
    private lateinit var selectedWorkspace: SessionWorkspace

    private val entries = linkedMapOf<String, FileEntry>()
    private val contents = linkedMapOf<String, String>()
    private val visibleMounts = mutableListOf<ExternalMount>()

    private lateinit var workspaceRepo: IWorkspaceRepository
    private lateinit var fileOpRepo: IFileOperationRepository
    private lateinit var originalMounts: ExternalWorkspaceAccess
    private lateinit var builtinAccess: BuiltinRealPathWorkspaceAccess

    private fun ctx(approvedTarget: String? = null, approvalBound: Boolean = false) = object : SkillExecutionContext {
        override val sessionId = "s1"
        override val agentId = "agent"
        override val workspacePath: String? = privateDir.canonicalPath
        override val workspaceRootUuid = "root-uuid"
        override val runMode = SessionRunMode.BUILTIN
        override val approvedExecutionTarget = approvedTarget
        override val isApprovalBoundExecution = approvalBound
    }

    @Before
    fun setUp() {
        rootTemp = Files.createTempDirectory("builtin-realpath-test").toFile().canonicalFile
        appDataDir = File(rootTemp, "app-data").apply { mkdirs() }
        privateDir = File(appDataDir, "files/session_workspaces/root-uuid").apply { mkdirs() }
        skillsDir = File(appDataDir, "files/agent_skills").apply { mkdirs() }
        externalDir = File(rootTemp, "external-proj").apply { mkdirs() }
        realMountDir = File(rootTemp, "real-mount").apply { mkdirs() }
        selectedWorkspace = SessionWorkspace.Private

        // 准备关键运行时诊断文件与敏感密钥文件
        File(appDataDir, "files/infinity_diagnostics.jsonl").writeText("""{"event":"boot_ok"}""" + "\n")
        File(appDataDir, "files/infinity_logs.txt").writeText("line1\nline2\n")
        File(appDataDir, "databases").mkdirs()
        File(appDataDir, "databases/meta.txt").writeText("db-meta")
        File(appDataDir, "shared_prefs").mkdirs()
        File(appDataDir, "shared_prefs/infinity_settings.xml").writeText("<map/>")
        File(appDataDir, "shared_prefs/infinity_encrypted_secrets.xml").writeText("TOP_SECRET_ENVELOPE")
        File(appDataDir, "shared_prefs/infinity_encrypted_secrets.xml.bak").writeText("TOP_SECRET_BAK")
        File(appDataDir, "no_backup").mkdirs()
        File(appDataDir, "no_backup/state.json").writeText("{}")
        File(appDataDir, "cache").mkdirs()
        File(appDataDir, "cache/temp.txt").writeText("cache-hello")

        val rootEntry = FileEntry(
            uuid = "root-uuid", workspaceRootUuid = "root-uuid", parentUuid = null,
            name = "", hash = "root-hash", sizeBytes = 0, isDirectory = true,
            physicalRootPath = privateDir.canonicalPath, materializedPath = "/",
            createdAt = 1, updatedAt = 1,
        )
        entries["/"] = rootEntry

        workspaceRepo = mockk(relaxed = true) {
            coEvery { ensureSessionRoot("s1") } returns rootEntry
            coEvery { getByUuid("root-uuid", any()) } answers {
                val id = secondArg<String>()
                entries.values.firstOrNull { it.uuid == id }
            }
            coEvery { getByMaterializedPath("root-uuid", any()) } answers {
                entries[secondArg()]
            }
            every { observeChildren("root-uuid", any()) } answers {
                val parentId = secondArg<String>()
                flowOf(entries.values.filter { it.parentUuid == parentId })
            }
            coEvery { createFileInWorkspaceStreaming("root-uuid", any(), any(), any(), any(), any(), any(), any()) } answers {
                val uuid = arg<String>(1)
                val name = arg<String>(2)
                val parentUuid = arg<String>(4)
                val path = arg<String>(5)
                val writer = arg<(java.io.OutputStream) -> Unit>(7)
                val out = java.io.ByteArrayOutputStream()
                writer(out)
                val bytes = out.toByteArray()
                val text = bytes.toString(Charsets.UTF_8)
                val hash = Sha256Utils.hash(bytes)
                val created = FileEntry(
                    uuid = uuid, workspaceRootUuid = "root-uuid", parentUuid = parentUuid,
                    name = name, hash = hash, sizeBytes = bytes.size.toLong(), isDirectory = false,
                    physicalRootPath = privateDir.canonicalPath, materializedPath = path,
                    createdAt = 2, updatedAt = 2,
                )
                entries[path] = created
                contents[uuid] = text
                File(privateDir, path.removePrefix("/")).apply { parentFile?.mkdirs(); writeBytes(bytes) }
                created
            }
            coEvery { createDirectoryInWorkspace("root-uuid", any(), any(), any(), any()) } answers {
                val uuid = arg<String>(1)
                val name = arg<String>(2)
                val parentUuid = arg<String?>(3)
                val path = arg<String>(4)
                val created = FileEntry(
                    uuid = uuid, workspaceRootUuid = "root-uuid", parentUuid = parentUuid,
                    name = name, hash = "", sizeBytes = 0, isDirectory = true,
                    physicalRootPath = privateDir.canonicalPath, materializedPath = path,
                    createdAt = 2, updatedAt = 2,
                )
                entries[path] = created
                File(privateDir, path.removePrefix("/")).mkdirs()
                created
            }
            coEvery { confirmCreatedEntry("root-uuid", any()) } returns Unit
            coEvery { permanentDelete("root-uuid", any()) } answers {
                val id = secondArg<String>()
                val target = entries.values.firstOrNull { it.uuid == id }
                if (target != null) {
                    entries.remove(target.materializedPath)
                    contents.remove(id)
                    File(privateDir, target.materializedPath.removePrefix("/")).deleteRecursively()
                }
            }
        }

        fileOpRepo = mockk(relaxed = true) {
            coEvery { readFileRange("root-uuid", any(), any(), any()) } answers {
                val id = secondArg<String>()
                val entry = entries.values.first { it.uuid == id }
                val text = contents[id].orEmpty()
                ReadResult(id, entry.name, text.lines().size, 1, text.lines().size, text, entry.hash, entry.updatedAt)
            }
            coEvery { writeFileAtomicExact("root-uuid", any(), any(), any(), any()) } answers {
                val id = arg<String>(1)
                val newText = arg<String>(2)
                val expected = arg<String>(4)
                val entry = entries.values.firstOrNull { it.uuid == id } ?: return@answers WriteResult.NotFound
                if (entry.hash != expected) return@answers WriteResult.Conflict(expected, entry.hash, "hash mismatch")
                val newHash = Sha256Utils.hash(newText.toByteArray(Charsets.UTF_8))
                entries[entry.materializedPath] = entry.copy(hash = newHash, sizeBytes = newText.toByteArray().size.toLong())
                contents[id] = newText
                File(privateDir, entry.materializedPath.removePrefix("/")).writeText(newText)
                WriteResult.Success(newHash)
            }
        }

        visibleMounts.clear()
        visibleMounts.add(ExternalMount(ExternalMountPaths.SKILLS_MOUNT_ID, "skills", ExternalMountPaths.SKILLS_SOURCE, "agent_skills", "s1", 0))
        visibleMounts.add(ExternalMount("kb1", "knowledge", ExternalMountPaths.KNOWLEDGE_BASE_SOURCE, "kb", "s1", 0, readOnly = true))
        visibleMounts.add(ExternalMount("real1", "assets", "real_path", realMountDir.canonicalPath, "s1", 0))
        visibleMounts.add(ExternalMount("saf1", "safdocs", "saf_tree", "content://tree/demo", "s1", 0))

        originalMounts = mockk(relaxed = true) {
            coEvery { visible("s1") } answers { visibleMounts.toList() }
            coEvery { workspaceMount("s1") } returns null
            coEvery { entry("s1", "/mnt/knowledge/doc.md") } returns FileEntry(
                uuid = "kb-doc", workspaceRootUuid = "root-uuid", parentUuid = null,
                name = "doc.md", hash = Sha256Utils.hash("知识库内容".toByteArray()),
                sizeBytes = 15, isDirectory = false, physicalRootPath = "",
                materializedPath = "/mnt/knowledge/doc.md", createdAt = 1, updatedAt = 1,
            )
            coEvery { read("s1", "/mnt/knowledge/doc.md", any()) } returns "知识库内容".toByteArray()
            coEvery { list("s1", "/mnt/assets") } returns listOf(ExternalFolderEntry("/mnt/assets/pic.txt", "pic.txt", false, 4, 1))
            coEvery { entry("s1", "/mnt/assets/pic.txt") } returns FileEntry(
                uuid = "asset-1", workspaceRootUuid = "root-uuid", parentUuid = null,
                name = "pic.txt", hash = Sha256Utils.hash("real".toByteArray()),
                sizeBytes = 4, isDirectory = false, physicalRootPath = "",
                materializedPath = "/mnt/assets/pic.txt", createdAt = 1, updatedAt = 1,
            )
            coEvery { read("s1", "/mnt/assets/pic.txt", any()) } returns "real".toByteArray()
        }

        builtinAccess = BuiltinRealPathWorkspaceAccess(
            original = originalMounts,
            workspace = workspaceRepo,
            files = fileOpRepo,
            sessionWorkspace = { selectedWorkspace },
            userSkillsDir = skillsDir,
            appDataDir = appDataDir,
            indexEvents = FileIndexEventSink.None,
            privateReader = com.promenar.infinity.ui.chat.diff.PrivateWorkspaceReader { _, _, path, _ ->
                val normalized = com.promenar.infinity.domain.storage.ExternalFolderPaths.normalize(path)
                File(privateDir, normalized.removePrefix("/")).readBytes()
            },
        )
    }

    @After
    fun tearDown() {
        rootTemp.deleteRecursively()
    }

    private fun wrapped(skill: SkillDefinition): LinuxSessionFileSkill = LinuxSessionFileSkill(
        original = skill,
        modeFor = { SessionRunMode.BUILTIN },
        linuxFiles = mockk(relaxed = true),
        workspace = workspaceRepo,
        files = fileOpRepo,
        indexEvents = FileIndexEventSink.None,
        captureFileChanges = false,
        builtinFiles = builtinAccess,
    )

    @Test
    fun `内置模式支持便携相对路径与真实绝对路径一一对应并拒绝伪前导斜杠路径`() = runBlocking {
        val create = wrapped(CreateFileSkill(workspaceRepo, fileOpRepo))
        val read = wrapped(FileReadSkill(fileOpRepo, workspaceRepo))
        val list = wrapped(FileListSkill(workspaceRepo))

        // 1. 缺省父目录建文件 -> 返回真实 Android 规范路径
        val created = create.execute(buildJsonObject {
            put("name", "hello.txt")
            put("content", "world")
        }, ctx())
        com.google.common.truth.Truth.assertWithMessage("create_file failed: ${created.content}").that(created.status).isEqualTo("success")
        val realFilePath = "${privateDir.canonicalPath}/hello.txt"
        assertThat(created.content).contains(realFilePath)

        // 2. 用便携相对路径（无前导 /）读取，与用真实绝对路径读取得到同一内容与同一路径头
        val readByRel = read.execute(buildJsonObject { put("path", "hello.txt") }, ctx())
        assertThat(readByRel.status).isEqualTo("success")
        assertThat(readByRel.content).contains("路径: $realFilePath")
        assertThat(readByRel.content).contains("world")

        val readByAbs = read.execute(buildJsonObject { put("path", realFilePath) }, ctx())
        assertThat(readByAbs.status).isEqualTo("success")
        assertThat(readByAbs.content).contains("路径: $realFilePath")
        assertThat(readByAbs.content).contains("world")

        // 3. list_files 展示真实绝对路径
        val listed = list.execute(buildJsonObject {}, ctx())
        assertThat(listed.status).isEqualTo("success")
        assertThat(listed.content).contains(realFilePath)

        // 4. 拒绝已废除的伪前导 / 写法：/hello.txt、/skills/...、/session/...、/mnt/assets/...
        for (bad in listOf("/hello.txt", "/skills/demo/SKILL.md", "/session/hello.txt", "/mnt/assets/pic.txt")) {
            val failed = read.execute(buildJsonObject { put("path", bad) }, ctx())
            assertThat(failed.status).isEqualTo("error")
            assertThat(failed.content).contains("真实路径")
        }

        // 5. 无 POSIX 路径的逻辑挂载 /mnt/knowledge 照常可用；real_path 挂载直接使用真实路径
        val kbRead = read.execute(buildJsonObject { put("path", "/mnt/knowledge/doc.md") }, ctx())
        assertThat(kbRead.status).isEqualTo("success")
        assertThat(kbRead.content).contains("知识库内容")

        val realMountRead = read.execute(buildJsonObject { put("path", "${realMountDir.canonicalPath}/pic.txt") }, ctx())
        assertThat(realMountRead.status).isEqualTo("success")
        assertThat(realMountRead.content).contains("real")
    }

    @Test
    fun `关键运行时数据目录开放只读自诊断、硬拒写删且密钥存储文件读写列举全硬拒`() = runBlocking {
        val read = wrapped(FileReadSkill(fileOpRepo, workspaceRepo))
        val list = wrapped(FileListSkill(workspaceRepo))
        val write = wrapped(FileWriteSkill(fileOpRepo, workspaceRepo))
        val delete = wrapped(DeleteFileSkill(workspaceRepo))

        // 1. 诊断日志、databases、非密钥 shared_prefs、no_backup 均开放只读
        val diagRead = read.execute(buildJsonObject { put("path", "${appDataDir.canonicalPath}/files/infinity_diagnostics.jsonl") }, ctx())
        assertThat(diagRead.status).isEqualTo("success")
        assertThat(diagRead.content).contains("boot_ok")

        val prefsRead = read.execute(buildJsonObject { put("path", "${appDataDir.canonicalPath}/shared_prefs/infinity_settings.xml") }, ctx())
        assertThat(prefsRead.status).isEqualTo("success")
        assertThat(prefsRead.content).contains("<map/>")

        val dbList = list.execute(buildJsonObject { put("path", "${appDataDir.canonicalPath}/databases") }, ctx())
        assertThat(dbList.status).isEqualTo("success")
        assertThat(dbList.content).contains("meta.txt")

        // 2. shared_prefs 列举自动隐藏加密密钥文件，直接读取密钥文件硬拒
        val prefsList = list.execute(buildJsonObject { put("path", "${appDataDir.canonicalPath}/shared_prefs") }, ctx())
        assertThat(prefsList.status).isEqualTo("success")
        assertThat(prefsList.content).contains("infinity_settings.xml")
        assertThat(prefsList.content).doesNotContain("infinity_encrypted_secrets")

        val secretRead = read.execute(buildJsonObject { put("path", "${appDataDir.canonicalPath}/shared_prefs/infinity_encrypted_secrets.xml") }, ctx())
        assertThat(secretRead.status).isEqualTo("error")
        assertThat(secretRead.content).contains("密钥存储文件")

        // 3. 写入或删除关键运行时数据目录硬拒（连审批目标也不放行）
        val writeDiag = write.execute(buildJsonObject {
            put("path", "${appDataDir.canonicalPath}/files/infinity_diagnostics.jsonl")
            put("content", "tampered")
            put("expectedHash", "dummy")
        }, ctx())
        assertThat(writeDiag.status).isEqualTo("error")
        assertThat(writeDiag.content).contains("只读自诊断")

        val deleteDb = delete.execute(buildJsonObject { put("path", "${appDataDir.canonicalPath}/databases/meta.txt") }, ctx())
        assertThat(deleteDb.status).isEqualTo("error")
        assertThat(deleteDb.content).contains("只读自诊断")
    }

    @Test
    fun `技能真实路径变更始终归入 L2 审批，工作区外真实路径写入归入 L1 审批`() = runBlocking {
        val writeTool = FileWriteSkill(fileOpRepo, workspaceRepo).toProtocolTool()

        // 1. 用户技能目录真实路径 -> builtinSkills=true -> 两档均为 ApprovalGrantCategory.SKILLS
        val skillArgs = buildJsonObject {
            put("path", "${skillsDir.canonicalPath}/demo/SKILL.md")
            put("content", "updated")
            put("expectedHash", "abc")
        }
        val skillTarget = builtinAccess.executionTarget("s1", writeTool, skillArgs)
        assertThat(skillTarget).isNotNull()
        assertThat(skillTarget).contains("\"builtinSkills\":true")
        for (mode in ExecutionMode.entries) {
            assertThat(ToolExecutionPolicy.grantCategory(mode, "write_file", ToolRisk.FILE_WRITE, skillArgs.toString(), skillTarget, usesLinux = false))
                .isEqualTo(ApprovalGrantCategory.SKILLS)
        }

        // 2. 工作区外合法本机路径（如 app cache）-> outsideWorkspace=true -> 风险审批档 L1，免批准档跳过
        val cacheArgs = buildJsonObject {
            put("path", "${appDataDir.canonicalPath}/cache/temp.txt")
            put("content", "updated")
            put("expectedHash", "abc")
        }
        val cacheTarget = builtinAccess.executionTarget("s1", writeTool, cacheArgs)
        assertThat(cacheTarget).isNotNull()
        assertThat(cacheTarget).contains("\"outsideWorkspace\":true")
        assertThat(ToolExecutionPolicy.grantCategory(ExecutionMode.WORKSPACE, "write_file", ToolRisk.FILE_WRITE, cacheArgs.toString(), cacheTarget, usesLinux = false))
            .isEqualTo(ApprovalGrantCategory.OUTSIDE_WORKSPACE)
        assertThat(ToolExecutionPolicy.grantCategory(ExecutionMode.FULL_ACCESS, "write_file", ToolRisk.FILE_WRITE, cacheArgs.toString(), cacheTarget, usesLinux = false))
            .isNull()
    }
}
