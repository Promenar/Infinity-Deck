package com.promenar.infinity.data.storage

import com.promenar.infinity.data.generation.remoteMountTargetResolver
import com.promenar.infinity.data.generation.resolveSessionToolTarget
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.ssh.MemorySshHosts
import com.promenar.infinity.data.ssh.SshFailure
import com.promenar.infinity.data.ssh.SshHost
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.*
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.registry.toProtocolTool
import com.promenar.infinity.ui.chat.manager.skills.BuiltinRealPathWorkspaceAccess
import com.promenar.infinity.ui.chat.manager.skills.LinuxSessionExternalWorkspaceAccess
import com.promenar.infinity.ui.chat.manager.skills.remoteMountTools
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteMountSkillsTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun `六种工具使用封闭参数并完成归档下载状态建目录重命名和删除`() = runBlocking<Unit> {
        val fixture = RemoteMountTestFixture(directory.root)
        fixture.manager().use { controller ->
            val tools = fixture.tools(controller)
            assertEquals(6, tools.size)
            for (tool in tools.values) assertEquals(JsonPrimitive(false), Json.parseToJsonElement(tool.parametersSchema).jsonObject["additionalProperties"])
            val source = File(fixture.workspaceDir, "原文件.txt").apply { writeText("远程归档内容") }
            val hash = mountTestHash(source.readBytes())
            val mkdir = """{"mount_path":"/mnt/drive/nested"}"""
            assertEquals("SUCCEEDED", fixture.call(tools, "mkdir", mkdir).field("state"))
            val archive = """{"mount_path":"/mnt/drive/nested/data.txt","workspace_path":"原文件.txt","wait_ms":15000}"""
            val uploaded = fixture.call(tools, "archive", archive)
            assertEquals("SUCCEEDED", uploaded.field("state"))
            val receipt = uploaded.json().getValue("receipt").jsonObject
            assertEquals("/mnt/drive/nested/data.txt", receipt.getValue("mount_path").jsonPrimitive.content)
            assertEquals("/mount/nested/data.txt", receipt.getValue("remote_path").jsonPrimitive.content)
            assertEquals(source.length(), receipt.getValue("size_bytes").jsonPrimitive.long)
            assertEquals(hash, receipt.getValue("sha256").jsonPrimitive.content)
            assertEquals("ssh_exec_sha256sum", receipt.getValue("verification_method").jsonPrimitive.content)
            assertEquals(uploaded.field("transfer_id"), receipt.getValue("transfer_id").jsonPrimitive.content)
            assertEquals(JsonPrimitive(false), receipt["deduplicated"])
            val duplicate = fixture.call(tools, "archive", archive)
            assertEquals(JsonPrimitive(true), duplicate.json().getValue("receipt").jsonObject["deduplicated"])
            val status = """{"transfer_id":"${uploaded.field("transfer_id")}"}"""
            assertEquals(receipt, fixture.call(tools, "transfer_status", status).json()["receipt"])
            assertEquals("NOT_FOUND", fixture.call(tools, "transfer_status", status, sessionId = "another").field("errorCode"))

            val renamed = """{"mount_path":"/mnt/drive/nested/data.txt","target_mount_path":"/mnt/drive/nested/renamed.txt","expected_source_sha256":"$hash"}"""
            assertEquals("SUCCEEDED", fixture.call(tools, "rename", renamed).field("state"))
            assertNull(fixture.transport.bytes("/mount/nested/data.txt"))
            val pulled = fixture.call(tools, "download",
                """{"mount_path":"/mnt/drive/nested/renamed.txt","workspace_path":"/home/session/download.txt","wait_ms":15000}""",
                mode = SessionRunMode.LINUX)
            assertEquals("SUCCEEDED", pulled.field("state"))
            val downloaded = pulled.json().getValue("receipt").jsonObject
            assertEquals("/home/session/download.txt", downloaded.getValue("local_path").jsonPrimitive.content)
            assertEquals(hash, downloaded.getValue("sha256").jsonPrimitive.content)
            assertArrayEquals(source.readBytes(), File(fixture.workspaceDir, "download.txt").readBytes())
            assertEquals(listOf("root"), fixture.refreshedRoots.toList())
            val delete = """{"mount_path":"/mnt/drive/nested/renamed.txt","expected_sha256":"$hash"}"""
            assertEquals("SUCCEEDED", fixture.call(tools, "delete", delete).field("state"))
            assertNull(fixture.transport.bytes("/mount/nested/renamed.txt"))
        }
    }

    @Test fun `只读保护无批准换源与主机指纹变化均拒绝`() = runBlocking<Unit> {
        val fixture = RemoteMountTestFixture(directory.root)
        fixture.manager().use { controller ->
            val tools = fixture.tools(controller)
            val args = """{"mount_path":"/mnt/drive/new"}"""
            val approved = fixture.approve(tools.getValue("mkdir"), args)
            assertNotNull(approved)
            assertEquals("APPROVAL_REQUIRED", fixture.call(tools, "mkdir", args, approval = null).field("errorCode"))
            assertEquals("CONFLICT", fixture.call(tools, "mkdir", args.replace("new", "changed"), approval = approved).field("errorCode"))
            fixture.mount = fixture.mount.copy(id = "changed-mount")
            assertEquals("CONFLICT", fixture.call(tools, "mkdir", args, approval = approved).field("errorCode"))
            fixture.mount = fixture.mount.copy(id = "mount-id", readOnly = true)
            assertEquals("READ_ONLY", fixture.call(tools, "mkdir", args, approval = approved).field("errorCode"))
            fixture.transport.put("/mount/read.txt", "只读保留下载".toByteArray())
            val readOnlyDownload = """{"mount_path":"/mnt/drive/read.txt","workspace_path":"available.txt","wait_ms":15000}"""
            assertEquals("SUCCEEDED", fixture.call(tools, "download", readOnlyDownload).field("state"))
            fixture.mount = fixture.mount.copy(readOnly = false)
            fixture.hosts.host = fixture.hosts.host.copy(trustedFingerprint = "SHA256:changed")
            assertEquals("HOST_AUTHORIZATION_CHANGED", fixture.call(tools, "mkdir", args, approval = approved).field("errorCode"))
            assertFalse("/mount/new" in fixture.transport.directories)
        }
    }

    @Test fun `差异文件保留且拒绝覆写不匹配摘要目录和技能下载`() = runBlocking<Unit> {
        val fixture = RemoteMountTestFixture(directory.root)
        fixture.manager().use { controller ->
            val tools = fixture.tools(controller)
            val bytes = "保留内容".toByteArray()
            fixture.transport.put("/mount/exists.txt", bytes)
            File(fixture.workspaceDir, "source.txt").writeText("要归档的内容")
            val archive = """{"mount_path":"/mnt/drive/exists.txt","workspace_path":"source.txt","wait_ms":15000}"""
            assertEquals("TARGET_CONFLICT", fixture.call(tools, "archive", archive).field("errorCode"))
            assertEquals("ATOMIC_CONDITIONAL_REPLACE_UNSUPPORTED",
                fixture.call(tools, "archive", archive.dropLast(1) + ""","overwrite":true}""").field("errorCode"))
            val wrong = "0".repeat(64)
            val hash = mountTestHash(bytes)
            assertEquals("CONFLICT", fixture.call(tools, "delete",
                """{"mount_path":"/mnt/drive/exists.txt","expected_sha256":"$wrong"}""").field("errorCode"))
            assertEquals("CONFLICT", fixture.call(tools, "rename",
                """{"mount_path":"/mnt/drive/exists.txt","target_mount_path":"/mnt/drive/new.txt","expected_source_sha256":"$wrong"}""").field("errorCode"))
            fixture.transport.put("/mount/new.txt", byteArrayOf(1))
            assertEquals("CONFLICT", fixture.call(tools, "rename",
                """{"mount_path":"/mnt/drive/exists.txt","target_mount_path":"/mnt/drive/new.txt","expected_source_sha256":"$hash"}""").field("errorCode"))
            assertArrayEquals(bytes, fixture.transport.bytes("/mount/exists.txt"))
            assertEquals("INVALID_PATH", fixture.call(tools, "delete",
                """{"mount_path":"/mnt/drive/","expected_sha256":"$wrong"}""", approval = null).field("errorCode"))
            assertEquals("ACCESS_DENIED", fixture.call(tools, "download",
                """{"mount_path":"/mnt/drive/exists.txt","workspace_path":".agents/skills/target.txt"}""", approval = null).field("errorCode"))
            assertEquals("INVALID_ARGUMENTS", fixture.call(tools, "download",
                """{"mount_path":"/mnt/drive/exists.txt","workspace_path":"target.txt","wait_ms":-1}""", approval = null).field("errorCode"))
        }
    }

    @Test fun `排队时校验预先绑定的主机与本地工作区身份`() = runBlocking<Unit> {
        val fixture = RemoteMountTestFixture(directory.root)
        val entered = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        fixture.beforeEndpoint = {
            entered.complete(Unit)
            unblock.await()
        }
        fixture.manager().use { controller ->
            File(fixture.workspaceDir, "source.txt").writeText("待验证")
            val tools = fixture.tools(controller)
            val queued = fixture.call(tools, "archive",
                """{"mount_path":"/mnt/drive/queued.txt","workspace_path":"source.txt","wait_ms":0}""")
            assertNotNull(queued.field("guidance"))
            withTimeout(5_000) { entered.await() }
            fixture.hosts.host = fixture.hosts.host.copy(id = "same-settings-different-host")
            unblock.complete(Unit)
            assertEquals("HOST_AUTHORIZATION_CHANGED", controller.awaitTask(requireNotNull(queued.field("transfer_id")), 5_000)?.errorCode)
            assertNull(fixture.transport.bytes("/mount/queued.txt"))
        }
    }

    @Test fun `获批覆写后入队遇到本地改写会保留新内容`() = runBlocking<Unit> {
        val fixture = RemoteMountTestFixture(directory.root)
        val entered = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        fixture.beforeEndpoint = {
            entered.complete(Unit)
            unblock.await()
        }
        fixture.transport.put("/mount/incoming.txt", "远端内容".toByteArray())
        val local = File(fixture.workspaceDir, "local.txt").apply { writeText("审批时的旧内容") }
        fixture.manager().use { controller ->
            val tools = fixture.tools(controller)
            val queued = fixture.call(tools, "download",
                """{"mount_path":"/mnt/drive/incoming.txt","workspace_path":"local.txt","overwrite":true,"wait_ms":0}""")
            withTimeout(5_000) { entered.await() }
            local.writeText("排队后本地新内容")
            unblock.complete(Unit)
            val result = controller.awaitTask(requireNotNull(queued.field("transfer_id")), 5_000)
            assertEquals("LOCAL_AUTHORIZATION_CHANGED", result?.errorCode)
            assertEquals("排队后本地新内容", local.readText())
            assertTrue(fixture.transport.readOffsets.isEmpty())
        }
    }

    private fun ToolResult.json() = Json.parseToJsonElement(requireNotNull(data)).jsonObject
    private fun ToolResult.field(key: String) = json()[key]?.jsonPrimitive?.contentOrNull
}

internal class RemoteMountTestFixture(root: File) {
    private val testDir = root.canonicalFile
    private val appDir = File(testDir, "app").apply { mkdirs() }
    val workspaceDir = File(appDir, "files/session_workspaces/root").apply { mkdirs() }
    private val skillsDir = File(appDir, "files/agent_skills").apply { mkdirs() }
    val transport = MemoryMountTransport()
    private val spec = SftpRemoteMountSpec("nas", "/mount")
    var mount = ExternalMount("mount-id", "drive", "sftp_remote", SftpRemoteMountSpec.encode(spec), "s1", 1)
    var selected: SessionWorkspace = SessionWorkspace.Private
    val hosts = MemorySshHosts().apply {
        host = SshHost(id = "host-id", alias = "nas", hostname = "nas.example.test", username = "test", trustedFingerprint = "SHA256:original")
    }
    val workspace = mockk<IWorkspaceRepository> {
        coEvery { ensureSessionRoot(any()) } returns FileEntry(
            uuid = "root", parentUuid = null, name = "", hash = "root-hash", isDirectory = true,
            physicalRootPath = workspaceDir.path, materializedPath = "/", createdAt = 1, updatedAt = 1,
        )
    }
    val files = mockk<IFileOperationRepository>()
    private val linuxRoot = mockk<ExternalFolderBackend> {
        coEvery { stat("/") } returns ExternalFolderEntry("/", "", true, 0, 1, identity = "test-linux")
    }
    fun backend(mount: ExternalMount = this.mount) =
        SftpExternalFolderBackend(mount, requireNotNull(SftpRemoteMountSpec.decode(mount.sourceId)), testDir) { transport }

    val mounts = mockk<ExternalWorkspaceAccess> {
        coEvery { visible(any()) } answers { listOf(mount).filter { it.sessionId == firstArg<String>() } }
        coEvery { workspaceMount(any()) } returns null
        coEvery { resolveUuid(any(), any()) } answers { ExternalMountPaths.uuidPath(secondArg(), listOf(mount)) }
        coEvery { entry(any(), any()) } coAnswers {
            backend().stat(requireNotNull(ExternalMountPaths.parse(secondArg())).second)?.let {
                FileEntry(uuid = ExternalMountPaths.uuid(mount, it.path), parentUuid = null, name = it.name, hash = "", isDirectory = it.isDirectory,
                    physicalRootPath = "", materializedPath = secondArg(), createdAt = 1, updatedAt = it.lastModified, sizeBytes = it.size)
            }
        }
        coEvery { list(any(), any()) } coAnswers { backend().list(requireNotNull(ExternalMountPaths.parse(secondArg())).second) }
        coEvery { read(any(), any(), any()) } coAnswers {
            backend().read(requireNotNull(ExternalMountPaths.parse(secondArg())).second, thirdArg())
        }
        coEvery { readStream<String>(any(), any(), any(), any()) } coAnswers {
            backend().readStream(requireNotNull(ExternalMountPaths.parse(secondArg())).second, thirdArg(), arg(3))
        }
        io.mockk.every { backend(any(), any()) } answers {
            val boundMount = mount
            object : ExternalFolderBackend by backend(boundMount) {
                override suspend fun stat(path: String): ExternalFolderEntry? =
                    backend(boundMount).stat(requireNotNull(ExternalMountPaths.parse(path)).second)
            }
        }
    }
    val builtin = BuiltinRealPathWorkspaceAccess(mounts, workspace, files, { selected }, skillsDir, appDir)
    val linux = LinuxSessionExternalWorkspaceAccess(mounts, workspace, files, { selected }, linuxRoot)
    val refreshedRoots = java.util.concurrent.CopyOnWriteArrayList<String>()
    var beforeEndpoint: suspend () -> Unit = {}

    fun manager() = RemoteTransferManager(
        RemoteTransferJournal(testDir),
        resolveEndpoint = {
            beforeEndpoint()
            val identity = hosts.host.executionIdentity()
            RemoteTransferEndpoint(transport, identity.digest, spec.rootPath, mount.readOnly,
                identity.hostId, com.promenar.infinity.infra.util.Sha256Utils.hash(SftpRemoteMountSpec.encode(spec)), mount.alias)
        },
        validateWorkspace = { task, bound, physicalPath, checkRevision ->
            val writing = task.direction == RemoteTransferDirection.DOWNLOAD
            val current = if (bound.path.startsWith("/home/session"))
                linux.remoteMountLocalFile(requireNotNull(task.sessionId), bound.path, writing)
            else builtin.remoteMountLocalFile(requireNotNull(task.sessionId), bound.path, writing)
            if (current.identity != bound.identity || current.file.path != physicalPath ||
                checkRevision && current.revision != bound.revision
            ) throw SshFailure("LOCAL_AUTHORIZATION_CHANGED")
        },
        refreshWorkspaceIndex = { _, id -> refreshedRoots += id },
    )

    fun tools(controller: RemoteTransferController) = remoteMountTools(
        remoteMountTargetResolver(mounts, hosts, builtin, linux), { controller }, { _, bound -> backend(bound.mount) },
    ).associateBy { it.id.removePrefix("remote_mount_") }

    suspend fun approve(tool: com.promenar.infinity.ui.chat.manager.registry.SkillDefinition, args: String, mode: SessionRunMode = SessionRunMode.BUILTIN): String? =
        resolveSessionToolTarget(Session("s1", "agent", workspaceRootUuid = "root", runMode = mode),
            tool.toProtocolTool(), args, workspace, mounts, hosts, linuxFiles = linux, builtinFiles = builtin)

    fun context(session: String = "s1", mode: SessionRunMode = SessionRunMode.BUILTIN, approved: String? = null) = object : SkillExecutionContext {
        override val sessionId = session
        override val agentId = "agent"
        override val workspaceRootUuid = "root"
        override val workspacePath = workspaceDir.path
        override val runMode = mode
        override val approvedExecutionTarget = approved
        override val isApprovalBoundExecution = approved != null
    }

    suspend fun call(
        tools: Map<String, com.promenar.infinity.ui.chat.manager.registry.SkillDefinition>,
        name: String,
        args: String,
        sessionId: String = "s1",
        mode: SessionRunMode = SessionRunMode.BUILTIN,
        approval: String? = "自动解析",
    ): ToolResult {
        val tool = tools.getValue(name)
        val target = if (approval == "自动解析") approve(tool, args, mode) else approval
        return tool.execute(Json.parseToJsonElement(args).jsonObject, context(sessionId, mode, target))
    }
}
