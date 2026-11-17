package com.promenar.infinity.data.storage

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.branch.BranchLinuxWorkspaceOperation
import com.promenar.infinity.data.local.db.dao.ExternalMountDao
import com.promenar.infinity.data.local.db.entity.ExternalMountEntity
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.repository.WriteResult
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.recording.LOCAL_RECORDING_MIME
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.FilePatchSkill
import com.promenar.infinity.ui.chat.manager.skills.FileWriteSkill
import com.promenar.infinity.ui.chat.manager.skills.LinuxSessionExternalWorkspaceAccess
import com.promenar.infinity.ui.chat.manager.skills.LinuxSessionFileSkill
import com.promenar.infinity.ui.chat.manager.skills.MountedWorkspaceSkill
import io.mockk.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

class ExternalKnowledgeBaseMountTest {
    private val temporary = Files.createTempDirectory("knowledge-mount").toFile().canonicalFile
    private val root = File(temporary, "knowledge").apply { mkdirs() }
    private val rows = MutableStateFlow(emptyList<ExternalMountEntity>())
    private val dao = mockk<ExternalMountDao> {
        coEvery { visible(any()) } answers { rows.value.filter { it.scopeKey == firstArg<String>() } }
        every { observeVisible(any()) } answers { val id = firstArg<String>(); rows.map { entries -> entries.filter { it.scopeKey == id } } }
        coEvery { all() } answers { rows.value }
        coEvery { insert(any()) } coAnswers { rows.value += firstArg<ExternalMountEntity>() }
        coEvery { update(any()) } coAnswers { val row = firstArg<ExternalMountEntity>(); rows.value = rows.value.map { if (it.id == row.id) row else it } }
        coEvery { get(id = any()) } answers { rows.value.firstOrNull { it.id == firstArg<String>() } }
        coEvery { delete(any()) } coAnswers { val id = firstArg<String>(); rows.value = rows.value.filterNot { it.id == id } }
    }
    private var rootEntry = FileEntry("kb-root", "kb-root", null, "知识库", "identity", isDirectory = true,
        physicalRootPath = root.path, materializedPath = "/", createdAt = 1, updatedAt = 1)
    private val workspace = mockk<IWorkspaceRepository> { coEvery { getByUuid("kb-root", "kb-root") } answers { rootEntry } }
    private val context = mockk<Context> { every { noBackupFilesDir } returns File(temporary, "backup") }
    private val fileSystem = JvmBoundRealPathFileSystem()
    private var knowledgeAllowed = true
    private val manager = ExternalFolderManager(context, dao, workspace, FileIndexEventSink.None,
        knowledgeBaseRoots = { setOf("kb-root") }, knowledgeBaseAllowed = { _, _ -> knowledgeAllowed }, backendOverride = { fileSystem.backend(root) })

    @After fun cleanup() { temporary.deleteRecursively() }

    @Test fun `已批准挂载随权限收紧拒绝读取且放宽后恢复`() = runBlocking<Unit> {
        File(root, "note.md").writeText("全文")
        manager.addKnowledgeBase("knowledge", "session", "kb-root")
        assertThat(manager.read("session", "/mnt/knowledge/note.md").toString(Charsets.UTF_8)).isEqualTo("全文")
        knowledgeAllowed = false
        assertThat(assertThrows(ExternalFolderException::class.java) {
            runBlocking { manager.read("session", "/mnt/knowledge/note.md") }
        }.code).isEqualTo("ACCESS_DENIED")
        knowledgeAllowed = true
        assertThat(manager.read("session", "/mnt/knowledge/note.md").toString(Charsets.UTF_8)).isEqualTo("全文")
    }

    @Test fun `知识库根UUID适配目录后端并持久化只读标志且同根不重复挂载`() = runBlocking {
        File(root, "note.md").writeText("全文")
        val mount = manager.addKnowledgeBase("knowledge", "session", "kb-root")
        assertThat(mount.sourceId).isEqualTo("kb-root")
        assertThat(mount.sourceType).isEqualTo(ExternalMountPaths.KNOWLEDGE_BASE_SOURCE)
        assertThat(mount.readOnly).isTrue()
        assertThat(mount.commandAccessible).isFalse()
        assertThat(ExternalMountPaths.commandAccess(mount).realPath).isNull()
        assertThat(rows.value.single().readOnly).isTrue()
        assertThat(manager.observeMounts("session").first().single().readOnly).isTrue()
        assertThat(manager.addKnowledgeBase("other", "session", "kb-root").id).isEqualTo(mount.id)
        assertThat(rows.value).hasSize(1)
        assertThat(manager.read("session", "/mnt/knowledge/note.md").toString(Charsets.UTF_8)).isEqualTo("全文")
        assertThat(manager.list("session", "/mnt/knowledge").map { it.name }).containsExactly("note.md")
        manager.deleteSessionMounts("session")
        assertThat(rows.value).isEmpty()
        assertThat(File(root, "note.md").readText()).isEqualTo("全文")
    }

    @Test fun `根结构身份不匹配在挂载写库之前拒绝`() = runBlocking {
        for (invalid in listOf(rootEntry.copy(workspaceRootUuid = "other"), rootEntry.copy(parentUuid = "parent"),
            rootEntry.copy(isDirectory = false), rootEntry.copy(materializedPath = "/child"),
            rootEntry.copy(physicalRootPath = "relative"))) {
            rootEntry = invalid
            assertThat(assertThrows(ExternalFolderException::class.java) {
                runBlocking { manager.addKnowledgeBase("knowledge", "session", "kb-root") }
            }.code).isEqualTo("UNAVAILABLE")
        }
        assertThat(rows.value).isEmpty()
    }

    @Test fun `未获系统智能体知识库根身份确认不能挂载其它工作区`() = runBlocking {
        val unconfigured = ExternalFolderManager(context, dao, workspace, FileIndexEventSink.None,
            backendOverride = { fileSystem.backend(root) })
        assertThat(assertThrows(ExternalFolderException::class.java) {
            runBlocking { unconfigured.addKnowledgeBase("knowledge", "session", "kb-root") }
        }.code).isEqualTo("UNAVAILABLE")
        assertThat(rows.value).isEmpty()
        coVerify(exactly = 0) { workspace.getByUuid(any(), any()) }
    }

    @Test fun `知识库来源即使只读列未置位也不能写入或向该根导入快照`() = runBlocking {
        rows.value = listOf(ExternalMountEntity("kb", "knowledge", ExternalMountPaths.KNOWLEDGE_BASE_SOURCE, "kb-root", "session", false, 1),
            ExternalMountEntity("source", "source", "real_path", root.path, "session", false, 1))
        File(root, "note.md").writeText("原文")
        assertThat(manager.visible("session").single { it.id == "kb" }.readOnly).isTrue()
        for (action in listOf<suspend () -> Any?>(
            { manager.write("session", "/mnt/knowledge/note.md", byteArrayOf(), null, false) },
            { manager.importSnapshot("session", "kb-root", "/mnt/source/note.md") },
        )) {
            assertThat(assertThrows(ExternalFolderException::class.java) { runBlocking { action() } }.code).isEqualTo("READ_ONLY")
        }
        assertThat(File(root, "note.md").readText()).isEqualTo("原文")
        coVerify(exactly = 0) { workspace.createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `普通会话不能向未挂载的知识库根导入快照`() = runBlocking {
        rows.value = listOf(ExternalMountEntity("source", "source", "real_path", root.path, "session", false, 1))
        File(root, "note.md").writeText("原文")
        val failure = assertThrows(ExternalFolderException::class.java) {
            runBlocking { manager.importSnapshot("session", "kb-root", "/mnt/source/note.md") }
        }
        assertThat(failure.code).isEqualTo("READ_ONLY")
        assertThat(failure.message).isEqualTo("知识库挂载为只读")
        assertThat(rows.value.none { it.sourceType == ExternalMountPaths.KNOWLEDGE_BASE_SOURCE }).isTrue()
        assertThat(root.list().toList()).containsExactly("note.md")
        coVerify(exactly = 0) { workspace.createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `受信知识库管理会话可以向自己的根导入可写来源快照`() = runBlocking {
        val source = File(temporary, "source").apply { mkdirs() }
        File(source, "note.md").writeText("导入正文")
        rows.value = listOf(ExternalMountEntity("source", "source", "real_path", source.path, "knowledge-session", false, 1))
        coEvery { workspace.createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val target = File(root, thirdArg<String>())
            target.outputStream().use { arg<(java.io.OutputStream) -> Unit>(7)(it) }
            FileEntry(secondArg(), firstArg(), arg(4), target.name, externalSha256(target.readBytes()), mimeType = arg(3),
                sizeBytes = target.length(), physicalRootPath = root.path, materializedPath = arg(5), createdAt = 1, updatedAt = 1)
        }
        coEvery { workspace.confirmCreatedEntry(any(), any()) } just Runs
        coEvery { dao.putSnapshot(any()) } just Runs
        val ownerManager = ExternalFolderManager(context, dao, workspace, FileIndexEventSink.None,
            knowledgeBaseRoots = { setOf("kb-root") },
            knowledgeBaseOwnerRoot = { if (it == "knowledge-session") "kb-root" else null },
            backendOverride = { fileSystem.backend(File(it.sourceId)) })
        val imported = ownerManager.importSnapshot("knowledge-session", "kb-root", "/mnt/source/note.md")
        assertThat(root.listFiles().single().readText()).isEqualTo("导入正文")
        assertThat(File(source, "note.md").readText()).isEqualTo("导入正文")
        coVerify(exactly = 1) { workspace.createFileInWorkspaceStreaming("kb-root", imported, any(), any(), "kb-root", any(), any(), any()) }
        coVerify(exactly = 1) { workspace.confirmCreatedEntry("kb-root", imported) }
    }

    @Test fun `分支Linux替换后端不能绕过只读工作区挂载`() = runBlocking {
        File(root, "note.md").writeText("原文")
        val mount = ExternalMount("branch-root", "", "branch_linux_workspace", "branch", "session", 1, readOnly = true)
        val operation = BranchLinuxWorkspaceOperation("session", mount, fileSystem.backend(root), root, workspacePaths = true)
        withContext(operation) {
            val boundContext = kotlin.coroutines.coroutineContext
            assertThat(manager.read("session", "/note.md").toString(Charsets.UTF_8)).isEqualTo("原文")
            for (action in listOf<suspend () -> Any?>(
                { manager.write("session", "/note.md", byteArrayOf(), null, false) },
                { manager.createDirectory("session", "/new") }, { manager.delete("session", "/note.md", null) },
                { manager.backend("session").move("/note.md", "/new") },
                { manager.backend("session").copy("/note.md", "/new") },
            )) assertThat(assertThrows(ExternalFolderException::class.java) { runBlocking(boundContext) { action() } }.code).isEqualTo("READ_ONLY")
        }
        assertThat(File(root, "note.md").readText()).isEqualTo("原文")
        assertThat(File(root, "new").exists()).isFalse()
    }

    @Test fun 虚拟录音音频支持受限读取并保留磁盘同名文件优先级() = runBlocking<Unit> {
        val audioDirectory = File(temporary, "audio").apply { mkdirs() }
        val audio = File(audioDirectory, "original.wav").apply { writeBytes(byteArrayOf(82, 73, 70, 70, 42)) }
        val recording = rootEntry.copy(uuid = "rec", parentUuid = "kb-root", name = "访谈.md",
            materializedPath = "/访谈.md", isDirectory = false, mimeType = LOCAL_RECORDING_MIME)
        coEvery { workspace.observeChildren("kb-root", "kb-root") } returns MutableStateFlow(listOf(recording, recording.copy(uuid = "trash",
            name = "废弃.md", inRecycleBin = true)))
        val backend = KnowledgeBaseRecordingBackend(fileSystem.backend(root), "kb-root", workspace, { audio }, { fileSystem.backend(it) })
        assertThat(backend.list("/").map { it.name }).containsExactly("访谈.wav")
        assertThat(backend.stat("/访谈.wav")?.size).isEqualTo(audio.length())
        assertThat(backend.stat("/访谈.wav")?.sha256).isEqualTo(externalSha256(audio.readBytes()))
        assertThat(backend.read("/访谈.wav").toList()).isEqualTo(audio.readBytes().toList())
        assertThat(backend.readStream("/访谈.wav", 5) { it.readBytes().toList() }).isEqualTo(audio.readBytes().toList())
        assertThat(assertThrows(ExternalFolderException::class.java) {
            runBlocking { backend.readStream("/访谈.wav", 4) { it.read() } }
        }.code).isEqualTo("TOO_LARGE")
        assertThat(assertThrows(ExternalFolderException::class.java) {
            runBlocking { backend.write("/访谈.wav", byteArrayOf()) }
        }.code).isEqualTo("READ_ONLY")
        File(root, "访谈.wav").writeText("磁盘文件")
        assertThat(backend.list("/").map { it.name }).containsExactly("访谈.wav")
        assertThat(backend.read("/访谈.wav").decodeToString()).isEqualTo("磁盘文件")
    }

    @Test fun 已授权录音文档使用原子版本写入并回调同步() = runBlocking<Unit> {
        val markdownFile = File(root, "访谈.md").apply { writeText("原稿") }
        val path = "/mnt/knowledge/访谈.md"
        var entry = rootEntry.copy(uuid = "rec", parentUuid = "kb-root", name = markdownFile.name,
            materializedPath = "/访谈.md", isDirectory = false, mimeType = LOCAL_RECORDING_MIME,
            hash = externalSha256(markdownFile.readBytes()), sizeBytes = markdownFile.length())
        coEvery { workspace.getByMaterializedPath("kb-root", any()) } answers { entry.takeIf { it.materializedPath == secondArg<String>() } }
        coEvery { workspace.getByUuid("kb-root", "rec") } answers { entry }
        val files = mockk<IFileOperationRepository>()
        coEvery { files.writeFileAtomicExact("kb-root", "rec", any(), "session", any()) } answers {
            val expected = arg<String>(4)
            if (expected != entry.hash) WriteResult.Conflict(entry.hash, expected, "版本冲突") else {
                markdownFile.writeText(thirdArg())
                entry = entry.copy(hash = externalSha256(markdownFile.readBytes()), sizeBytes = markdownFile.length())
                WriteResult.Success(entry.hash)
            }
        }
        val callbacks = mutableListOf<Triple<String, String, String>>()
        val recordingManager = ExternalFolderManager(context, dao, workspace, FileIndexEventSink.None,
            knowledgeBaseRoots = { setOf("kb-root") }, knowledgeBaseAllowed = { _, _ -> knowledgeAllowed },
            backendOverride = { fileSystem.backend(root) }, fileOperations = files,
            onRecordingDocumentSaved = { id, content, hash -> callbacks += Triple(id, content, hash) })
        recordingManager.addKnowledgeBase("knowledge", "session", "kb-root")
        val oldHash = entry.hash
        assertThat(recordingManager.isWritableKnowledgeRecording("session", path)).isTrue()
        assertThat(recordingManager.write("session", path, "新稿".toByteArray(), oldHash, false)).isEqualTo(entry)
        assertThat(callbacks).containsExactly(Triple("rec", "新稿", entry.hash))
        assertThat(recordingManager.version("session", path, entry.hash)?.decodeToString()).isEqualTo("新稿")
        assertThat(assertThrows(ExternalFolderException::class.java) {
            runBlocking { recordingManager.write("session", path, "冲突".toByteArray(), oldHash, false) }
        }.code).isEqualTo("CONFLICT")
        assertThat(assertThrows(ExternalFolderException::class.java) {
            runBlocking { recordingManager.write("session", path, byteArrayOf(0xc0.toByte()), null, false) }
        }.code).isEqualTo("INVALID_TEXT")
        for (action in listOf<suspend () -> Any?>(
            { recordingManager.write("session", path, byteArrayOf(), null, true) },
            { recordingManager.write("session", "/mnt/knowledge/访谈.wav", byteArrayOf(), null, false) },
            { recordingManager.write("session", "/mnt/knowledge/note.md", byteArrayOf(), null, false) },
            { recordingManager.delete("session", path, null) },
            { recordingManager.createDirectory("session", "/mnt/knowledge/new") },
        )) assertThat(assertThrows(ExternalFolderException::class.java) { runBlocking { action() } }.code).isEqualTo("READ_ONLY")

        val privateRoot = rootEntry.copy(uuid = "session-root", workspaceRootUuid = "session-root", physicalRootPath = temporary.path)
        coEvery { workspace.ensureSessionRoot("session") } returns privateRoot
        val rootfs = mockk<ExternalFolderBackend> {
            coEvery { stat("/") } returns ExternalFolderEntry("/", "", true, 0, 1, identity = "linux-rootfs")
        }
        val linux = LinuxSessionExternalWorkspaceAccess(recordingManager, workspace, files, { SessionWorkspace.Private }, rootfs)
        suspend fun runTool(skill: SkillDefinition, mode: SessionRunMode, input: String) {
            val mounted = MountedWorkspaceSkill(skill, recordingManager, workspace, files, FileIndexEventSink.None, captureFileChanges = false)
            val routed = LinuxSessionFileSkill(mounted, { mode }, linux, workspace, files, FileIndexEventSink.None, captureFileChanges = false)
            val invocation = object : SkillExecutionContext {
                override val sessionId = "session"
                override val agentId = "agent"
                override val workspaceRootUuid = privateRoot.uuid
                override val workspacePath: String? = null
                override val runMode = mode
            }
            val result = routed.execute(Json.parseToJsonElement(input).jsonObject, invocation)
            assertThat(result.status).isEqualTo("success")
        }
        runTool(FileWriteSkill(files, workspace), SessionRunMode.BUILTIN,
            """{"path":"$path","expectedHash":"${entry.hash}","content":"内置全量编辑"}""")
        runTool(FilePatchSkill(files, workspace), SessionRunMode.LINUX,
            """{"path":"$path","expectedHash":"${entry.hash}","operations":[{"action":"replace_lines","startLine":1,"endLine":1,"newContent":"Linux 补丁编辑"}]}""")
        assertThat(markdownFile.readText()).isEqualTo("Linux 补丁编辑")

        knowledgeAllowed = false
        assertThat(recordingManager.isWritableKnowledgeRecording("session", path)).isFalse()
        assertThat(assertThrows(ExternalFolderException::class.java) {
            runBlocking { recordingManager.write("session", path, "越权".toByteArray(), null, false) }
        }.code).isEqualTo("ACCESS_DENIED")
        assertThat(markdownFile.readText()).isEqualTo("Linux 补丁编辑")
        assertThat(callbacks.map { it.second }).containsExactly("新稿", "内置全量编辑", "Linux 补丁编辑").inOrder()
    }
}
