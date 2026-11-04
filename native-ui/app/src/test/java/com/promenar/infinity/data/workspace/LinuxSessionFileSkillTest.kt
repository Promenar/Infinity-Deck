package com.promenar.infinity.data.workspace

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.infinity.data.generation.resolveSessionToolTarget
import com.promenar.infinity.data.linux.LinuxFileSystemImpl
import com.promenar.infinity.data.linux.TestLinuxFileMetadata
import com.promenar.infinity.data.linux.deleteLinuxFixture
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.repository.SecureWorkspaceFileOps
import com.promenar.infinity.data.repository.TestWorkspaceFileOps
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.repository.*
import com.promenar.infinity.domain.storage.*
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.infra.util.Sha256Utils
import com.promenar.infinity.ui.chat.manager.registry.*
import com.promenar.infinity.ui.chat.manager.skills.*
import io.mockk.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Test

class LinuxSessionFileSkillTest {
    private val temp = Files.createTempDirectory("linux-session-files").toFile().canonicalFile
    private val privateFileOps = TestWorkspaceFileOps()
    private val roots = listOf("one", "two").associateWith { session ->
        val directory = File(temp, session).apply { mkdirs() }
        val identity = privateFileOps.ensureRoot(directory.toPath())
        entry("root-$session", "/", directory.path, directory = true, hash = identity)
    }
    private val entries = roots.values.associateBy { it.uuid }.toMutableMap()
    private val workspace = mockk<IWorkspaceRepository> {
        coEvery { ensureSessionRoot(any()) } answers { roots.getValue(firstArg()) }
        coEvery { getByUuid(any(), any()) } answers { entries[secondArg<String>()]?.takeIf { it.workspaceRootUuid == firstArg<String>() } }
        coEvery { getByMaterializedPath(any(), any()) } answers { entries.values.singleOrNull { it.workspaceRootUuid == firstArg<String>() && it.materializedPath == secondArg<String>() } }
        every { observeChildren(any(), any()) } answers { flowOf(entries.values.filter { it.workspaceRootUuid == firstArg<String>() && it.parentUuid == secondArg<String>() }) }
    }
    private val files = mockk<IFileOperationRepository>()
    private val skills = ExternalMount(ExternalMountPaths.SKILLS_MOUNT_ID, "skills", ExternalMountPaths.SKILLS_SOURCE, "agent_skills", "one", 0)
    private var mountId = "docs-id"
    private val externalDirectory = File(temp, "external").apply { mkdirs() }
    private var selected: SessionWorkspace = SessionWorkspace.Private
    private val mounts = mockk<ExternalWorkspaceAccess> {
        coEvery { visible(any()) } answers { listOf(ExternalMount(mountId, "docs", "real_path", externalDirectory.path, firstArg(), 1), ExternalMount("saf-id", "saf", "saf_tree", "content://test", firstArg(), 1), skills) }
        coEvery { workspaceMount(any()) } answers { (selected as? SessionWorkspace.Directory)?.let { ExternalMount("ws_${it.workspace.id}", "", "workspace", it.workspace.id, firstArg(), 0) } }
        coEvery { entry(any(), any()) } answers {
            val path = secondArg<String>()
            val content = externalContent(path)
            val mount = if (path.startsWith("/skills")) skills else if (path.startsWith("/mnt/docs")) ExternalMount(mountId, "docs", "real_path", "/test/docs", firstArg(), 1)
                else ExternalMount("ws_project", "", "workspace", "project", firstArg(), 0)
            val relative = if (path.startsWith("/mnt/docs")) path.removePrefix("/mnt/docs").ifEmpty { "/" } else if (path.startsWith("/skills")) path.removePrefix("/skills").ifEmpty { "/" } else path
            entry(ExternalMountPaths.uuid(mount, relative), path, "", directory = content == null, hash = content?.let(Sha256Utils::hash).orEmpty())
        }
        coEvery { read(any(), any(), any()) } answers { externalContent(secondArg())!!.toByteArray() }
        coEvery { list(any(), any()) } returns emptyList()
    }
    private val rootfsDirectory = File(temp, "rootfs").apply { mkdirs() }
    private val fs = LinuxFileSystemImpl(rootfsDirectory, { true }, TestLinuxFileMetadata)
    private val access = LinuxSessionExternalWorkspaceAccess(mounts, workspace, files, { selected }, LinuxSessionRootfsBackend(fs))
    private var mode: SessionRunMode? = SessionRunMode.LINUX

    init {
        // macOS 不提供 SecureDirectoryStream；此夹具只验证路由，FD 安全由现有 Android 夹具覆盖。
        mockkConstructor(SecureWorkspaceFileOps::class)
        every { anyConstructed<SecureWorkspaceFileOps>().readLimited(any(), any(), any()) } answers {
            privateFileOps.readLimited(firstArg(), secondArg(), thirdArg())
        }
        privateFile("one", "same.txt", "one-private")
        privateFile("two", "same.txt", "two-private")
        File(rootfsDirectory, "etc/config").apply { parentFile!!.mkdirs(); writeText("rootfs-data") }
    }

    @After fun cleanup() { com.promenar.infinity.startup.StartupWriteBarrier.resetForTesting(); unmockkConstructor(SecureWorkspaceFileOps::class); deleteLinuxFixture(temp) }

    @Test fun `Linux传输目录回执使用底层身份并支持整树移动`() = runBlocking {
        File(rootfsDirectory, "tmp/source/child").apply { mkdirs() }
        File(rootfsDirectory, "tmp/source/child/item.bin").writeBytes(byteArrayOf(1, 2, 3))
        val backend = access.backend("one")
        com.promenar.infinity.data.storage.ExternalTreeTransfer.move(backend, "/tmp/source", backend, "/tmp/target")
        assertThat(File(rootfsDirectory, "tmp/source").exists()).isFalse()
        assertThat(File(rootfsDirectory, "tmp/target/child/item.bin").readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test fun `手机存储的workspace路径写入映射到所选目录根`() = runBlocking {
        selected = SessionWorkspace.Directory(
            WorkspaceEntity("shared", WorkspaceEntity.KIND_SHARED, "手机存储", externalDirectory.path, 0), externalDirectory,
        )
        val bytes = "shared-data".toByteArray()
        coEvery { mounts.write("one", "/probe.txt", bytes, null, true) } coAnswers {
            assertThat(kotlinx.coroutines.currentCoroutineContext()[ExternalMountOperationBindings]?.mountIds?.get(""))
                .isEqualTo(ExternalMountPaths.workspaceMountId("shared"))
            entry("shared-file", "/probe.txt", externalDirectory.path)
        }
        access.write("one", "/workspace/probe.txt", bytes, null, true)
        coVerify(exactly = 1) { mounts.write("one", "/probe.txt", bytes, null, true) }
        assertThat(File(roots.getValue("one").physicalRootPath, "probe.txt").exists()).isFalse()
        assertThat(File(rootfsDirectory, "workspace/probe.txt").exists()).isFalse()
    }

    @Test fun `Linux目录相对文件使用选中根且绝对别名保持一致`() = runBlocking {
        val identity = com.promenar.infinity.data.repository.PersistentFileIdentity.encode("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "ino=1")
        selected = SessionWorkspace.Directory(WorkspaceEntity("linux", WorkspaceEntity.KIND_LINUX_HOME, "app", "/opt/app#$identity", 0), externalDirectory)
        coEvery { mounts.read("one", "/same.txt", any()) } returns "selected".toByteArray()
        assertThat(access.read("one", "same.txt").toString(Charsets.UTF_8)).isEqualTo("selected")
        assertThat(access.read("one", "/opt/app/same.txt").toString(Charsets.UTF_8)).isEqualTo("selected")
        assertThat(access.read("one", "/workspace/same.txt").toString(Charsets.UTF_8)).isEqualTo("selected")
        coVerify(exactly = 3) { mounts.read("one", "/same.txt", any()) }
        val write = FileWriteSkill(files, workspace)
        val target = access.executionTarget("one", write.toProtocolTool(), json("path" to ".agents/skills/a.md", "content" to "new"))!!
        assertThat(target).contains("\"linuxSkills\":true")
        assertThat(target).contains("\"oneShot\":true")
        selected = (selected as SessionWorkspace.Directory).let { it.copy(workspace = it.workspace.copy(location = "/home#$identity")) }
        val privateSkills = access.executionTarget("one", write.toProtocolTool(), json("path" to "/home/session/.agents/skills/a.md", "content" to "new"))!!
        assertThat(privateSkills).contains("\"linuxSkills\":true")
        assertThat(privateSkills).contains("\"oneShot\":true")
    }

    @Test fun `隐藏名称与尾空格的登记根通过工具别名读取且审批保留实际路径`() = runBlocking {
        val identity = com.promenar.infinity.data.repository.PersistentFileIdentity.encode("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "ino=1")
        coEvery { mounts.read("one", "/same.txt", any()) } returns "external-data".toByteArray()
        listOf("/opt/.infinity-project", "/root/.l2s.project", "/home/code ").forEach { root ->
            selected = SessionWorkspace.Directory(WorkspaceEntity("linux", WorkspaceEntity.KIND_LINUX_HOME, "app", "$root#$identity", 0), externalDirectory)
            val read = skill(FileReadSkill(files, workspace)).execute(json("path" to "same.txt"), context())
            assertThat(read.status).isEqualTo("success")
            assertThat(read.content).contains("路径: $root/same.txt")
            val invocation = access.invocationArguments("one", "list_files", JsonObject(emptyMap()))
            assertThat(invocation["path"]?.jsonPrimitive?.content).isEqualTo("/workspace")
            val write = access.executionTarget("one", FileWriteSkill(files, workspace).toProtocolTool(), json("path" to "same.txt", "content" to "new"))!!
            assertThat(write).contains(root)
        }
    }

    @Test fun `home工作区别名与会话私有路径不共享审批目标`() = runBlocking {
        val identity = com.promenar.infinity.data.repository.PersistentFileIdentity.encode("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "ino=1")
        selected = SessionWorkspace.Directory(WorkspaceEntity("linux", WorkspaceEntity.KIND_LINUX_HOME, "home", "/home#$identity", 0), externalDirectory)
        val tool = FileWriteSkill(files, workspace).toProtocolTool()
        val workspaceTarget = access.executionTarget("one", tool, json("path" to "/workspace/session/same.txt", "content" to "new"))!!
        val privateTarget = access.executionTarget("one", tool, json("path" to "/home/session/same.txt", "content" to "new"))!!
        assertThat(workspaceTarget).isNotEqualTo(privateTarget)
        assertThat(workspaceTarget).contains("/workspace/session/same.txt")
        assertThat(privateTarget).contains("/home/session/same.txt")
    }

    @Test fun `rootfs移动的版本冲突仍在原读取预算内检查`() = runBlocking {
        val result = skill(MoveFileSkill(workspace, FileIndexEventSink.None)).execute(
            json("path" to "/etc/config", "newParentPath" to "/mnt/docs", "expected_sha256" to "wrong"), context())
        assertThat(result.content).contains("CONFLICT")
        assertThat(File(rootfsDirectory, "etc/config").readText()).isEqualTo("rootfs-data")
        coVerify(exactly = 0) { mounts.write(any(), any(), any(), any(), any()) }
    }

    @Test fun `分支workspace原生移动保留基础挂载身份和路径绑定`() = runBlocking {
        selectProject()
        val backend = mockk<ExternalFolderBackend>()
        val branch = com.promenar.infinity.data.branch.BranchLinuxFileNamespace(
            com.promenar.infinity.data.branch.BranchLinuxCommandWorkspace(externalDirectory, "/workspace/branches/feature"), backend,
        )
        val fresh = LinuxSessionExternalWorkspaceAccess(mounts, workspace, files, { selected }, LinuxSessionRootfsBackend(fs), branchNamespace = { branch })
        every { mounts.backend("one", any()) } answers { backend }
        coEvery { backend.move("/a", "/b") } coAnswers {
            val operation = kotlinx.coroutines.currentCoroutineContext()[com.promenar.infinity.data.branch.BranchLinuxWorkspaceOperation]!!
            assertThat(operation.workspacePaths).isTrue()
            assertThat(operation.baseRoot).isEqualTo(externalDirectory)
            assertThat(operation.backend).isSameInstanceAs(backend)
            assertThat(kotlinx.coroutines.currentCoroutineContext()[ExternalMountOperationBindings]?.mountIds?.get(""))
                .isEqualTo(operation.mount.id)
        }
        val wrapped = LinuxSessionFileSkill(MoveFileSkill(workspace, FileIndexEventSink.None), { mode }, fresh,
            workspace, files, FileIndexEventSink.None, captureFileChanges = false)
        val result = wrapped.execute(json("path" to "/workspace/a", "newName" to "b"), context())
        assertWithMessage(result.content).that(result.status).isEqualTo("success")
        assertThat(result.content).contains("已移动（原位重命名）")
        coVerify(exactly = 1) { backend.move("/a", "/b") }
    }

    @Test fun `特殊登记根下分支工具读取创建和移动保留实际cwd与父挂载区别`() = runBlocking {
        for (cwd in listOf("/opt/.infinity-project/feature", "/root/.l2s.project", "/home/code ")) {
            val directory = File(rootfsDirectory, cwd.removePrefix("/")).apply { mkdirs() }
            File(directory, "same.txt").writeText("branch-data")
            selected = SessionWorkspace.Directory(WorkspaceEntity("branch", "branch_worktree", "branch", cwd, 0), directory)
            val branch = com.promenar.infinity.data.branch.BranchLinuxFileNamespace(
                com.promenar.infinity.data.branch.BranchLinuxCommandWorkspace(externalDirectory, cwd), mockk(),
            )
            val fresh = LinuxSessionExternalWorkspaceAccess(mounts, workspace, files, { selected }, LinuxSessionRootfsBackend(fs), branchNamespace = { branch })
            fun wrapped(original: SkillDefinition) = LinuxSessionFileSkill(original, { mode }, fresh, workspace, files, FileIndexEventSink.None, captureFileChanges = false)
            val read = FileReadSkill(files, workspace)
            val result = wrapped(read).execute(json("path" to "same.txt"), context())
            assertWithMessage("结果=%s", result.content).that(result.status).isEqualTo("success")
            assertThat(result.content).contains("路径: $cwd/same.txt")
            assertThat(result.content).contains("branch-data")
            val relativeTarget = fresh.executionTarget("one", read.toProtocolTool(), json("path" to "same.txt"))!!
            val parentTarget = fresh.executionTarget("one", read.toProtocolTool(), json("path" to "/workspace/same.txt"))!!
            assertThat(relativeTarget).isNotEqualTo(parentTarget)
            assertThat(relativeTarget).contains(cwd)
            val created = wrapped(CreateFileSkill(workspace, files)).execute(json("name" to "new.txt", "content" to "new"), context())
            assertWithMessage("结果=%s", created.content).that(created.status).isEqualTo("success")
            assertThat(File(directory, "new.txt").readText()).isEqualTo("new")
            val moved = wrapped(MoveFileSkill(workspace, FileIndexEventSink.None)).execute(json("path" to "new.txt", "newName" to "moved.txt"), context())
            assertWithMessage("结果=%s", moved.content).that(moved.status).isEqualTo("success")
            assertThat(File(directory, "new.txt").exists()).isFalse()
            assertThat(File(directory, "moved.txt").readText()).isEqualTo("new")
            assertThat(fresh.list("one", cwd).map { it.name }).containsAtLeast("same.txt", "moved.txt")
            val listed = wrapped(FileListSkill(workspace)).execute(JsonObject(emptyMap()), context())
            assertWithMessage("结果=%s", listed.content).that(listed.status).isEqualTo("success")
            assertThat(listed.content).contains("moved.txt")
            assertThat(fresh.resolveUuid("one", fresh.entry("one", "$cwd/moved.txt")!!.uuid)).isEqualTo("$cwd/moved.txt")
            val searched = wrapped(FileSearchSkill(workspace, files)).execute(json("query" to "new", "mode" to "content"), context())
            assertWithMessage("结果=%s", searched.content).that(searched.status).isEqualTo("success")
            assertThat(searched.content).contains("$cwd/moved.txt:1:")
            val createTarget = fresh.executionTarget("one", CreateDirectorySkill(workspace).toProtocolTool(), json("parentPath" to "/", "name" to "infinity-linux-cwd"))!!
            assertThat(createTarget).contains("\"/infinity-linux-cwd\"")
            val moveTarget = fresh.executionTarget("one", MoveFileSkill(workspace, FileIndexEventSink.None).toProtocolTool(), json("path" to "/a", "newName" to "infinity-linux-cwd"))!!
            assertThat(moveTarget).contains("\"/infinity-linux-cwd\"")
            val inheritedNameTarget = fresh.executionTarget("one", MoveFileSkill(workspace, FileIndexEventSink.None).toProtocolTool(), json("path" to "/tmp/infinity-linux-cwd", "newParentPath" to "/"))!!
            assertThat(inheritedNameTarget).contains("\"/infinity-linux-cwd\"")
        }
        Unit
    }

    @Test fun `所有Linux路径读取返回Linux路径且保持私有会话隔离`() = runBlocking {
        val read = skill(FileReadSkill(files, workspace))
        mapOf("same.txt" to "one-private", "/workspace/same.txt" to "one-private", "/home/session/same.txt" to "one-private", "/mnt/docs/a.txt" to "external-data", "/mnt/skills/a.md" to "skill-data", "/etc/config" to "rootfs-data").forEach { (path, content) ->
            val result = read.execute(json("path" to path), context())
            assertWithMessage("路径=%s，结果=%s", path, result.content).that(result.status).isEqualTo("success")
            assertThat(result.content).contains(content)
            assertThat(result.content).contains("路径: ${LinuxSessionPaths.normalize(path)}")
        }
        val other = read.execute(json("path" to "/home/session/same.txt"), context("two"))
        assertThat(other.content).contains("two-private")
        val foreign = read.execute(json("uuid" to "file-two-same.txt"), context())
        assertThat(foreign.status).isEqualTo("error")
        coVerify(exactly = 0) { files.readFileRange(any(), any(), any(), any()) }
    }

    @Test fun `项目workspace与home session分别访问项目及Room树`() = runBlocking {
        selectProject()
        val read = skill(FileReadSkill(files, workspace))
        assertThat(read.execute(json("path" to "/workspace/same.txt"), context()).content).contains("project-data")
        assertThat(read.execute(json("path" to "/home/session/same.txt"), context()).content).contains("one-private")
        val rawUuid = read.execute(json("uuid" to "file-one-same.txt"), context())
        assertThat(rawUuid.content).contains("路径: /home/session/same.txt")
        assertThat(access.list("one", "/mnt").map { it.name }).containsExactly("docs", "skills")
        assertThat(read.execute(json("path" to "/mnt/saf/a.txt"), context()).status).isEqualTo("error")
        coVerify(exactly = 0) { mounts.read(any(), match { it.startsWith("/mnt/saf") }, any()) }
    }

    @Test fun `私有覆盖写经原事务且已批准Linux路径不被换成其它目标`() = runBlocking {
        val original = FileWriteSkill(files, workspace)
        val args = json("path" to "/home/session/same.txt", "content" to "new", "expectedHash" to Sha256Utils.hash("one-private"))
        val target = access.executionTarget("one", original.toProtocolTool(), args)!!
        coEvery { files.writeFileAtomicExact("root-one", "file-one-same.txt", "new", "one", Sha256Utils.hash("one-private")) } answers {
            File(roots.getValue("one").physicalRootPath, "same.txt").writeText("new")
            entries["file-one-same.txt"] = entries.getValue("file-one-same.txt").copy(hash = Sha256Utils.hash("new"), sizeBytes = 3)
            WriteResult.Success(Sha256Utils.hash("new"))
        }
        val result = skill(original).execute(args, context(approved = target))
        assertThat(result.status).isEqualTo("success")
        coVerify(exactly = 1) { files.writeFileAtomicExact("root-one", "file-one-same.txt", "new", "one", Sha256Utils.hash("one-private")) }
        val different = skill(original).execute(JsonObject(args + json("path" to "/workspace/same.txt")), context(approved = target))
        assertThat(different.status).isEqualTo("error")
        assertThat(different.content).contains("CONFLICT")
    }

    @Test fun `审批绑定命名空间工作区与挂载身份且rootfs变更携带命令策略标记`() = runBlocking {
        val write = FileWriteSkill(files, workspace)
        val args = json("path" to "/etc/config", "content" to "updated", "expectedHash" to Sha256Utils.hash("rootfs-data"))
        val target = access.executionTarget("one", write.toProtocolTool(), args)!!
        assertThat(target).contains("\"linuxRootfs\":true")
        assertThat(target).contains("\"oneShot\":true")
        val written = skill(write).execute(args, context(approved = target))
        assertWithMessage("结果=%s", written.content).that(written.status).isEqualTo("success")
        assertThat(File(rootfsDirectory, "etc/config").readText()).isEqualTo("updated")
        val read = FileReadSkill(files, workspace)
        val mountedArgs = json("path" to "/mnt/docs/a.txt")
        val mountedTarget = access.executionTarget("one", read.toProtocolTool(), mountedArgs)!!
        mountId = "other-docs-id"
        assertThat(skill(read).execute(mountedArgs, context(approved = mountedTarget)).status).isEqualTo("error")
        val privateArgs = json("path" to "/workspace/same.txt")
        val privateTarget = access.executionTarget("one", read.toProtocolTool(), privateArgs)!!
        selectProject()
        assertThat(skill(read).execute(privateArgs, context(approved = privateTarget)).status).isEqualTo("error")
    }

    @Test fun `严格拒绝跨命名空间点片段和符号链接`() = runBlocking {
        Files.createSymbolicLink(File(rootfsDirectory, "etc/escape").toPath(), File("../../outside").toPath())
        Files.createSymbolicLink(File(rootfsDirectory, "etc/bound").toPath(), File("/home/session/same.txt").toPath())
        Files.createSymbolicLink(File(roots.getValue("one").physicalRootPath, "escape").toPath(), File(roots.getValue("two").physicalRootPath, "same.txt").toPath())
        entries["escape"] = entry("escape", "/escape", roots.getValue("one").physicalRootPath, root = "root-one", hash = "untrusted")
        val read = skill(FileReadSkill(files, workspace))
        listOf("/etc/escape", "/etc/bound", "/home/session/escape", "/workspace/../etc/config", "/mnt/docs/../../etc/config").forEach { path ->
            assertThat(read.execute(json("path" to path), context()).status).isEqualTo("error")
        }
    }

    @Test fun `技能逐次审批及BUILTIN原始参数与结果完整委派`() = runBlocking {
        val write = FileWriteSkill(files, workspace)
        val args = json("path" to "/mnt/skills/a.md", "content" to "new", "expectedHash" to Sha256Utils.hash("skill-data"))
        assertThat(access.executionTarget("one", write.toProtocolTool(), args)).contains("\"linuxSkills\":true")
        assertThat(skill(write).execute(args, context()).content).contains("APPROVAL_REQUIRED")
        val original = mockk<SkillDefinition> {
            every { id } returns "read_file"
            every { description } returns "原始工具"
            every { parametersSchema } returns "{}"
            coEvery { execute(any(), any()) } returns ToolResult("builtin", "原始返回")
        }
        mode = SessionRunMode.BUILTIN
        val context = context()
        val builtinArgs = json("path" to "unchanged/../path")
        assertThat(skill(original).execute(builtinArgs, context)).isEqualTo(ToolResult("builtin", "原始返回"))
        coVerify(exactly = 1) { original.execute(builtinArgs, context) }
        mode = null
        assertThat(skill(original).execute(builtinArgs, context).status).isEqualTo("error")
        coVerify(exactly = 1) { original.execute(builtinArgs, context) }
    }

    @Test fun `会话审批解析器共享同一Linux目标且缺少适配时拒绝回退`() = runBlocking {
        val session = Session(id = "one", agentId = "agent", runMode = SessionRunMode.LINUX)
        val tool = FileReadSkill(files, workspace).toProtocolTool()
        val args = json("path" to "same.txt")
        assertThat(resolveSessionToolTarget(session, tool, args.toString(), workspace, mounts, linuxFiles = access))
            .isEqualTo(access.executionTarget("one", tool, args))
        assertThat(resolveSessionToolTarget(session, tool, args.toString(), workspace, mounts)).isNull()
    }

    @Test fun `同路径重建真实挂载和当前项目目录均不能继承已批准目标`() = runBlocking {
        val read = FileReadSkill(files, workspace)
        val mountedArgs = json("path" to "/mnt/docs/a.txt")
        val mountedTarget = access.executionTarget("one", read.toProtocolTool(), mountedArgs)!!
        Files.move(externalDirectory.toPath(), File(temp, "external-original").toPath())
        externalDirectory.mkdir()
        assertThat(skill(read).execute(mountedArgs, context(approved = mountedTarget)).status).isEqualTo("error")
        coVerify(exactly = 0) { mounts.read(any(), "/mnt/docs/a.txt", any()) }
        selectProject()
        val projectArgs = json("path" to "/workspace/same.txt")
        val projectTarget = access.executionTarget("one", read.toProtocolTool(), projectArgs)!!
        val project = (selected as SessionWorkspace.Directory).root
        Files.move(project.toPath(), File(temp, "project-original").toPath())
        project.mkdir()
        assertThat(skill(read).execute(projectArgs, context(approved = projectTarget)).status).isEqualTo("error")
        coVerify(exactly = 0) { mounts.read(any(), "/same.txt", any()) }
    }

    @Test fun `私有文件的workspace与home别名共享版本快照`() = runBlocking {
        val old = access.read("one", "/workspace/same.txt")
        File(roots.getValue("one").physicalRootPath, "same.txt").writeText("new-private")
        entries["file-one-same.txt"] = entries.getValue("file-one-same.txt").copy(hash = Sha256Utils.hash("new-private"))
        assertThat(access.version("one", "/home/session/same.txt", Sha256Utils.hash(old))?.toString(Charsets.UTF_8)).isEqualTo("one-private")
    }

    @Test fun `Linux根文件删除使不同会话的同路径快照失效`() = runBlocking<Unit> {
        val first = access.read("one", "/etc/config")
        val second = access.read("two", "/etc/config")
        val hash = Sha256Utils.hash(first)
        assertThat(second).isEqualTo(first)
        assertThat(access.version("two", "/etc/config", hash)).isNotNull()
        access.delete("one", "/etc/config", hash)
        assertThat(access.version("one", "/etc/config", hash)).isNull()
        assertThat(access.version("two", "/etc/config", hash)).isNull()
        assertThat(File(rootfsDirectory, "etc/config").exists()).isFalse()
    }

    @Test fun `模式快照已改变时不执行任何文件访问`() = runBlocking {
        val original = mockk<SkillDefinition> {
            every { id } returns "read_file"
            every { description } returns "原始工具"
            every { parametersSchema } returns "{}"
        }
        mode = SessionRunMode.BUILTIN
        val result = skill(original).execute(json("path" to "/etc/config"), context(expectedMode = SessionRunMode.LINUX))
        assertThat(result.content).contains("EXEC_MODE_CHANGED")
        coVerify(exactly = 0) { original.execute(any(), any()) }
        coVerify(exactly = 0) { workspace.ensureSessionRoot(any()) }
    }

    @Test fun `新命名空间实例不依赖RAM快照而使用Room持久basisHash`() = runBlocking {
        val basis = Sha256Utils.hash("persisted-old-version")
        val result = DiffResult("file-one-same.txt", basis, Sha256Utils.hash("one-private"), listOf(DiffHunk(1, 1, 1, 1, listOf(DiffLine("-", "persisted-old-version"), DiffLine("+", "one-private")))))
        coEvery { files.diffFile("root-one", "file-one-same.txt", basis) } returns result
        val fresh = LinuxSessionExternalWorkspaceAccess(mounts, workspace, files, { selected }, LinuxSessionRootfsBackend(fs))
        val skill = LinuxSessionFileSkill(FileDiffSkill(files, workspace), { mode }, fresh, workspace, files, FileIndexEventSink.None, captureFileChanges = false)
        val diff = skill.execute(json("path" to "/home/session/same.txt", "basisHash" to basis), context())
        assertWithMessage("结果=%s", diff.content).that(diff.status).isEqualTo("success")
        assertThat(diff.content).contains("persisted-old-version")
        coVerify(exactly = 1) { files.diffFile("root-one", "file-one-same.txt", basis) }
    }

    @Test fun `同一私有树跨Linux别名移动保留Room UUID与历史`() = runBlocking {
        val root = roots.getValue("one")
        entries["docs-one"] = entry("docs-one", "/docs", root.physicalRootPath, root = root.uuid, directory = true)
        coEvery { workspace.relocate("root-one", "file-one-same.txt", "docs-one", "renamed.txt", "same.txt", null) } answers {
            val previous = entries.getValue("file-one-same.txt")
            entries["file-one-same.txt"] = previous.copy(parentUuid = "docs-one", name = "renamed.txt", materializedPath = "/docs/renamed.txt")
            RenameResult.Success("renamed.txt", previous.hash, previous.updatedAt, true)
        }
        val moved = skill(MoveFileSkill(workspace, FileIndexEventSink.None)).execute(json("path" to "/workspace/same.txt", "newParentPath" to "/home/session/docs", "newName" to "renamed.txt"), context())
        assertWithMessage("结果=%s", moved.content).that(moved.status).isEqualTo("success")
        assertThat(moved.content).contains("/home/session/docs/renamed.txt")
        assertThat(entries.getValue("file-one-same.txt").materializedPath).isEqualTo("/docs/renamed.txt")
        coVerify(exactly = 1) { workspace.relocate("root-one", "file-one-same.txt", "docs-one", "renamed.txt", "same.txt", null) }
        coVerify(exactly = 0) { workspace.updateParent(any(), any(), any()) }
        coVerify(exactly = 0) { workspace.rename(any(), any(), any(), any()) }
        coVerify(exactly = 0) { workspace.permanentDelete(any(), any()) }
        val basis = Sha256Utils.hash("old-version")
        coEvery { files.diffFile("root-one", "file-one-same.txt", basis) } returns DiffResult("file-one-same.txt", basis, entries.getValue("file-one-same.txt").hash, emptyList())
        assertThat(skill(FileDiffSkill(files, workspace)).execute(json("path" to "/home/session/docs/renamed.txt", "basisHash" to basis), context()).status).isEqualTo("success")
        coVerify(exactly = 1) { files.diffFile("root-one", "file-one-same.txt", basis) }
    }

    @Test fun `无扩展rootfs配置可读取但NUL与非法UTF8仍拒绝`() = runBlocking {
        File(rootfsDirectory, "etc/profile").writeText("export PATH=/usr/bin")
        File(rootfsDirectory, "etc/binary").writeBytes(byteArrayOf(65, 0, 66))
        File(rootfsDirectory, "etc/invalid").writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        val read = skill(FileReadSkill(files, workspace))
        val profile = read.execute(json("path" to "/etc/profile"), context())
        assertWithMessage("结果=%s", profile.content).that(profile.status).isEqualTo("success")
        assertThat(profile.content).contains("export PATH=/usr/bin")
        listOf("/etc/binary", "/etc/invalid").forEach { path ->
            val result = read.execute(json("path" to path), context())
            assertThat(result.status).isEqualTo("error")
            assertThat(result.content).contains("UTF-8")
        }
    }

    @Test fun `私有文本新建在Room登记文本MIME避免后续写入被误判二进制`() = runBlocking {
        coEvery { workspace.createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            val sink = java.io.ByteArrayOutputStream()
            arg<(java.io.OutputStream) -> Unit>(7).invoke(sink)
            val content = sink.toByteArray()
            val uuid = secondArg<String>()
            val created = entry(uuid, arg(5), roots.getValue("one").physicalRootPath, root = "root-one", hash = Sha256Utils.hash(content))
                .copy(mimeType = arg(3), sizeBytes = content.size.toLong())
            entries[uuid] = created
            File(created.physicalRootPath, created.materializedPath.removePrefix("/")).writeBytes(content)
            created
        }
        coEvery { workspace.confirmCreatedEntry(any(), any()) } returns Unit
        val result = skill(CreateFileSkill(workspace, files)).execute(json("name" to "profile", "content" to "export A=1"), context())
        assertWithMessage("结果=%s", result.content).that(result.status).isEqualTo("success")
        val created = entries.values.single { it.materializedPath == "/profile" }
        assertThat(created.mimeType).isEqualTo("text/plain")
        assertThat(created.hash).isEqualTo(Sha256Utils.hash("export A=1"))
    }


    @Test fun `启动隔离阻断Linux各命名空间变更但读取仍然可用`() = runBlocking {
        val manager = com.promenar.infinity.startup.StartupIssueManager()
        com.promenar.infinity.startup.StartupWriteBarrier.install(manager)
        manager.bindRoot("root-one", roots.getValue("one").physicalRootPath, "one")
        manager.recover(com.promenar.infinity.startup.StartupStep.WORKSPACE_JOURNAL,
            com.promenar.infinity.startup.StartupIssueScope(com.promenar.infinity.startup.StartupScopeKind.WORKSPACE_ROOT, "root-one", "工作区")) { error("冲突") }
        for (path in listOf("/home/session/same.txt", "/workspace/same.txt", "/mnt/docs/a.txt", "/etc/config")) {
            assertThat(access.read("one", path)).isNotEmpty()
            val actions: List<suspend () -> Unit> = listOf(
                { access.write("one", path, "new".toByteArray(), null, false); Unit },
                { access.createDirectory("one", path + "-dir"); Unit },
                { access.delete("one", path, null) },
            )
            for (action in actions) assertWithMessage("隔离路径=%s", path).that(runCatching { action() }.exceptionOrNull())
                .isInstanceOf(com.promenar.infinity.startup.StartupIsolationException::class.java)
        }
        assertThat(runCatching { access.movePrivate("one", "/workspace/same.txt", "/home/session/new.txt", null) }.exceptionOrNull())
            .isInstanceOf(com.promenar.infinity.startup.StartupIsolationException::class.java)
        assertThat(File(rootfsDirectory, "etc/config").readText()).isEqualTo("rootfs-data")
    }


    @Test fun `隔离读使用既有Room根而不调用建根写入入口`() = runBlocking {
        val sessions = mockk<com.promenar.infinity.data.repository.ISessionRepository> {
            coEvery { getById("one") } returns Session(id = "one", agentId = "agent", workspaceRootUuid = "root-one")
        }
        coEvery { workspace.ensureSessionRoot(any()) } throws AssertionError("读取不能调用建根")
        val fresh = LinuxSessionExternalWorkspaceAccess(mounts, workspace, files, { selected }, LinuxSessionRootfsBackend(fs), sessions = sessions)
        val manager = com.promenar.infinity.startup.StartupIssueManager()
        com.promenar.infinity.startup.StartupWriteBarrier.install(manager)
        manager.recover(com.promenar.infinity.startup.StartupStep.WORKSPACE_JOURNAL,
            com.promenar.infinity.startup.StartupIssueScope(com.promenar.infinity.startup.StartupScopeKind.WORKSPACE_ROOT, "root-one", "工作区")) { error("冲突") }
        assertThat(fresh.read("one", "/home/session/same.txt").toString(Charsets.UTF_8)).isEqualTo("one-private")
        assertThat(runCatching { fresh.write("one", "/etc/config", "new".toByteArray(), null, false) }.exceptionOrNull())
            .isInstanceOf(com.promenar.infinity.startup.StartupIsolationException::class.java)
        coVerify(exactly = 0) { workspace.ensureSessionRoot(any()) }
    }

    @Test fun `其它会话的隔离物理根不能通过Linux真实挂载或rootfs写入`() = runBlocking {
        for ((root, path) in listOf(externalDirectory to "/mnt/docs/a.txt", File(rootfsDirectory, "etc") to "/etc/config")) {
            val manager = com.promenar.infinity.startup.StartupIssueManager()
            com.promenar.infinity.startup.StartupWriteBarrier.install(manager)
            manager.bindRoot("other-root", root.canonicalPath, "two")
            manager.recover(com.promenar.infinity.startup.StartupStep.WORKSPACE_JOURNAL,
                com.promenar.infinity.startup.StartupIssueScope(com.promenar.infinity.startup.StartupScopeKind.WORKSPACE_ROOT, "other-root", "工作区")) { error("冲突") }
            assertThat(access.read("one", path)).isNotEmpty()
            assertThat(runCatching { access.write("one", path, "new".toByteArray(), null, false) }.exceptionOrNull())
                .isInstanceOf(com.promenar.infinity.startup.StartupIsolationException::class.java)
        }
    }

    private fun skill(original: SkillDefinition) = LinuxSessionFileSkill(original, { mode }, access, workspace, files, FileIndexEventSink.None, captureFileChanges = false)
    private fun context(session: String = "one", approved: String? = null, expectedMode: SessionRunMode? = null) = object : SkillExecutionContext {
        override val sessionId = session
        override val agentId = "agent"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root-$session"
        override val approvedExecutionTarget = approved
        override val isApprovalBoundExecution = approved != null
        override val runMode = expectedMode
    }
    private fun selectProject() {
        selected = SessionWorkspace.Directory(WorkspaceEntity("project", "project", "项目", "project", 1), File(temp, "project").apply { mkdirs() })
    }
    private fun privateFile(session: String, name: String, content: String) {
        val root = roots.getValue(session)
        File(root.physicalRootPath, name).writeText(content)
        entries["file-$session-$name"] = entry("file-$session-$name", "/$name", root.physicalRootPath, root = root.uuid, hash = Sha256Utils.hash(content)).copy(sizeBytes = content.toByteArray().size.toLong())
    }
    private fun externalContent(path: String): String? = when (path) {
        "/mnt/docs/a.txt" -> "external-data"
        "/skills/a.md" -> "skill-data"
        "/same.txt" -> "project-data"
        else -> null
    }
    private fun json(vararg values: Pair<String, String>) = JsonObject(values.associate { it.first to JsonPrimitive(it.second) })
    private fun entry(uuid: String, path: String, physical: String, root: String = uuid, directory: Boolean = false, hash: String = "") = FileEntry(
        uuid, root, if (path == "/") null else root, path.substringAfterLast('/'), hash, isDirectory = directory,
        physicalRootPath = physical, materializedPath = path, createdAt = 1, updatedAt = 1,
    )
}
