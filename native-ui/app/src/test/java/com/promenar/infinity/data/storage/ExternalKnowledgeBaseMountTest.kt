package com.promenar.infinity.data.storage

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.branch.BranchLinuxWorkspaceOperation
import com.promenar.infinity.data.agent.AgentRetrievalConfig
import com.promenar.infinity.data.knowledge.*
import com.promenar.infinity.data.generation.resolveSessionToolTarget
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.local.db.dao.ExternalMountDao
import com.promenar.infinity.data.local.db.entity.ExternalMountEntity
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.repository.WriteResult
import com.promenar.infinity.domain.repository.RenameIndexTarget
import com.promenar.infinity.domain.repository.RenameResult
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.domain.tool.ToolExecutionTargets
import com.promenar.infinity.recording.LOCAL_RECORDING_MIME
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.registry.toProtocolTool
import com.promenar.infinity.ui.chat.manager.skills.CreateDirectorySkill
import com.promenar.infinity.ui.chat.manager.skills.CreateFileSkill
import com.promenar.infinity.ui.chat.manager.skills.DeleteFileSkill
import com.promenar.infinity.ui.chat.manager.skills.FilePatchSkill
import com.promenar.infinity.ui.chat.manager.skills.FileReadSkill
import com.promenar.infinity.ui.chat.manager.skills.MoveFileSkill
import com.promenar.infinity.ui.chat.manager.skills.RequestKnowledgeAccessSkill
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
            assertThat(result.content).contains("资料库录音整理稿已保存并同步工作台：$path")
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

    @Test fun `读写授权整批预检失败时保留已有挂载档位`() = runBlocking<Unit> {
        val existing = manager.addKnowledgeBase("knowledge", "session", "kb-root")
        val target = KnowledgeAccessTarget("kb-root", "资料库", 1, "knowledge", "/mnt/knowledge", readOnly = false,
            alreadyMounted = true, mountedId = existing.id, mountedSourceType = existing.sourceType, mountedReadOnly = true)
        val denied = KnowledgeAccessTarget("unavailable", "其它资料库", 1, "other", "/mnt/other", readOnly = false)
        val failure = runCatching { manager.grantKnowledgeBaseAccess("session", listOf(target, denied)) }.exceptionOrNull()
        assertThat((failure as ExternalFolderException).code).isEqualTo("UNAVAILABLE")
        assertThat(rows.value.single().sourceType).isEqualTo(ExternalMountPaths.KNOWLEDGE_BASE_SOURCE)
        assertThat(rows.value.single().readOnly).isTrue()
    }

    @Test fun `批准读写升级保留挂载身份且两种运行模式走受管文件链路`() = runBlocking<Unit> {
        val entries = linkedMapOf(rootEntry.uuid to rootEntry)
        fun add(uuid: String, parent: String?, path: String, mime: String?, bytes: ByteArray? = null): FileEntry {
            val physical = File(root, path.removePrefix("/"))
            if (bytes == null) physical.mkdirs() else physical.writeBytes(bytes)
            return rootEntry.copy(uuid = uuid, parentUuid = parent, name = physical.name, materializedPath = path,
                mimeType = mime, isDirectory = bytes == null, sizeBytes = bytes?.size?.toLong() ?: 0,
                hash = bytes?.let(::externalSha256).orEmpty()).also { entries[uuid] = it }
        }
        val recording = add("rec", "kb-root", "/访谈.md", LOCAL_RECORDING_MIME, "原稿".toByteArray())
        val audioBytes = ByteArray(1024 * 1024 + 25) { (it % 251).toByte() }
        val audio = File(temporary, "original.wav").apply { writeBytes(audioBytes) }
        val published = mutableListOf<FileIndexEvent>()
        val deleted = mutableListOf<String>()
        val saved = mutableListOf<String>()
        val sink = FileIndexEventSink { published += it }
        val files = mockk<IFileOperationRepository>()
        val privateRoot = rootEntry.copy(uuid = "session-root", workspaceRootUuid = "session-root", physicalRootPath = temporary.path)
        coEvery { workspace.ensureSessionRoot("session") } returns privateRoot
        coEvery { workspace.getByUuid("kb-root", any()) } answers { entries[secondArg()] }
        coEvery { workspace.getByMaterializedPath("kb-root", any()) } answers {
            entries.values.singleOrNull { it.materializedPath == secondArg<String>() }
        }
        every { workspace.observeChildren("kb-root", any()) } answers {
            MutableStateFlow(entries.values.filter { it.parentUuid == secondArg<String>() })
        }
        coEvery { workspace.createDirectoryInWorkspace("kb-root", any(), any(), any(), any()) } answers {
            add(secondArg(), arg(3), arg(4), null)
        }
        coEvery { workspace.createFileInWorkspaceStreaming("kb-root", any(), any(), any(), any(), any(), any(), any()) } answers {
            val buffer = java.io.ByteArrayOutputStream()
            arg<(java.io.OutputStream) -> Unit>(7)(buffer)
            add(secondArg(), arg(4), arg(5), arg(3), buffer.toByteArray())
        }
        coEvery { workspace.confirmCreatedEntry("kb-root", any()) } just Runs
        coEvery { files.writeFileAtomicExact("kb-root", any(), any(), "session", any()) } coAnswers {
            val current = entries.getValue(secondArg())
            val expected = arg<String>(4)
            if (current.hash != expected) WriteResult.Conflict(current.hash, expected, "版本冲突") else {
                val bytes = thirdArg<String>().toByteArray()
                File(root, current.materializedPath.removePrefix("/")).writeBytes(bytes)
                val updated = current.copy(hash = externalSha256(bytes), sizeBytes = bytes.size.toLong(), updatedAt = current.updatedAt + 1)
                entries[current.uuid] = updated
                sink.publish(FileIndexEvent.Changed("kb-root", current.uuid, updated.hash, updated.updatedAt))
                WriteResult.Success(updated.hash, targetEpoch = updated.updatedAt)
            }
        }
        fun moveEntry(entry: FileEntry, parent: FileEntry, name: String): RenameResult.Success {
            val target = parent.materializedPath.trimEnd('/') + "/" + name
            Files.move(File(root, entry.materializedPath.removePrefix("/")).toPath(), File(root, target.removePrefix("/")).toPath())
            val updated = entry.copy(name = name, parentUuid = parent.uuid, materializedPath = target, updatedAt = entry.updatedAt + 1)
            entries[entry.uuid] = updated
            return RenameResult.Success(name, updated.hash, updated.updatedAt, true,
                listOf(RenameIndexTarget(entry.uuid, updated.hash, updated.updatedAt)))
        }
        coEvery { workspace.rename("kb-root", any(), any(), any()) } answers {
            val current = entries.getValue(secondArg())
            moveEntry(current, entries.getValue(current.parentUuid!!), thirdArg())
        }
        coEvery { workspace.relocate("kb-root", any(), any(), any(), any(), any()) } answers {
            val current = entries.getValue(secondArg())
            check(current.hash == arg<String?>(5))
            moveEntry(current, entries.getValue(thirdArg()), arg(3))
        }
        coEvery { workspace.permanentDeleteIfHash("kb-root", any(), any()) } answers {
            val entry = entries.getValue(secondArg())
            check(entry.hash == thirdArg<String>())
            Files.delete(File(root, entry.materializedPath.removePrefix("/")).toPath())
            entries.remove(entry.uuid)
            Unit
        }
        coEvery { workspace.permanentDelete("kb-root", any()) } answers {
            val entry = entries.getValue(secondArg())
            Files.delete(File(root, entry.materializedPath.removePrefix("/")).toPath())
            entries.remove(entry.uuid)
            Unit
        }

        val access = ExternalFolderManager(context, dao, workspace, sink,
            knowledgeBaseRoots = { setOf("kb-root") }, knowledgeBaseAllowed = { _, _ -> knowledgeAllowed },
            backendOverride = {
                KnowledgeBaseRecordingBackend(fileSystem.backend(root), "kb-root", workspace,
                    { id -> audio.takeIf { id == "rec" } }, { fileSystem.backend(it) })
            },
            fileOperations = files, onRecordingDocumentSaved = { _, content, _ -> saved += content },
            onRecordingDocumentsDeleted = { deleted += it })
        val session = Session("session", "agent", ragOptions = RagOptions())
        val config = AgentRetrievalConfig()
        val planner = KnowledgeAccessPlanner({ session }, { config }, access, object : KnowledgeCatalogSource {
            override suspend fun knowledgeBases(session: Session, options: RagOptions) =
                listOf(KnowledgeBaseSummary("kb-root", "默认资料", 1, true))
            override suspend fun snapshot(session: Session, options: RagOptions) = KnowledgeCatalogSnapshot()
            override suspend fun hasIndexedMaterialAsync(session: Session, options: RagOptions) = false
            override suspend fun documentLocation(sessionId: String, reference: RagReference): KnowledgeDocumentLocation? = null
        })
        val request = RequestKnowledgeAccessSkill(planner, { session }, { config }, KnowledgeTurnState())
        suspend fun requestMode(mode: String, preview: KnowledgeAccessPreview): com.promenar.infinity.data.model.ToolResult =
            request.execute(Json.parseToJsonElement("""{"reason":"整理资料","mode":"$mode"}""").jsonObject,
                object : SkillExecutionContext {
                    override val sessionId = session.id
                    override val agentId = "agent"
                    override val workspaceRootUuid = privateRoot.uuid
                    override val workspacePath: String? = null
                    override val isApprovalBoundExecution = true
                    override val approvedExecutionTarget = Json.encodeToString(KnowledgeAccessPreview.serializer(), preview)
                })
        val readOnlyPreview = planner.preview(session.id)
        assertThat(requestMode("read_write", readOnlyPreview).status).isEqualTo("error")
        assertThat(rows.value).isEmpty()
        assertThat(requestMode("read_only", readOnlyPreview).content).contains("只读")
        val originalMount = rows.value.single()
        assertThat(requestMode("read_write", planner.preview(session.id, accessMode = "read_write")).content).contains("读写")
        val upgraded = access.visible(session.id).single { it.alias == "knowledge" }
        assertThat(upgraded.id).isEqualTo(originalMount.id)
        assertThat(upgraded.sourceType).isEqualTo(ExternalMountPaths.KNOWLEDGE_BASE_RW_SOURCE)
        assertThat(upgraded.readOnly).isFalse()
        assertThat(upgraded.commandAccessible).isFalse()

        val rootfs = mockk<ExternalFolderBackend> {
            coEvery { stat("/") } returns ExternalFolderEntry("/", "", true, 0, 1, identity = "linux-rootfs")
        }
        val linux = LinuxSessionExternalWorkspaceAccess(access, workspace, files, { SessionWorkspace.Private }, rootfs)
        suspend fun call(skill: SkillDefinition, mode: SessionRunMode, args: String, success: Boolean = true): String {
            val mounted = MountedWorkspaceSkill(skill, access, workspace, files, sink, captureFileChanges = false)
            val routed = LinuxSessionFileSkill(mounted, { mode }, linux, workspace, files, sink, captureFileChanges = false)
            val approval = if (!ToolExecutionTargets.isPathMutation(skill.id)) null else requireNotNull(
                resolveSessionToolTarget(session.copy(runMode = mode, workspaceRootUuid = privateRoot.uuid),
                    routed.toProtocolTool(), args, workspace, access, linuxFiles = linux),
            )
            val result = routed.execute(Json.parseToJsonElement(args).jsonObject, object : SkillExecutionContext {
                override val sessionId = session.id
                override val agentId = "agent"
                override val workspaceRootUuid = privateRoot.uuid
                override val workspacePath: String? = null
                override val runMode = mode
                override val isApprovalBoundExecution = approval != null
                override val approvedExecutionTarget = approval
            })
            assertThat(result.status).isEqualTo(if (success) "success" else "error")
            return result.content
        }
        val mountPath = "/mnt/knowledge"
        val audioPath = "$mountPath/访谈.wav"
        for (mode in listOf(SessionRunMode.BUILTIN, SessionRunMode.LINUX)) {
            val doc = "${mode.name.lowercase()}.md"
            val docPath = "$mountPath/$doc"
            val folder = mode.name.lowercase()
            val folderPath = "$mountPath/$folder"
            val moved = "$folderPath/整理.md"
            assertThat(call(FileReadSkill(files, workspace), mode, """{"path":"$audioPath","mode":"stat"}"""))
                .contains("sha256: ${externalSha256(audioBytes)}")
            assertThat(call(FileReadSkill(files, workspace), mode, """{"path":"$audioPath","mode":"page"}""", false))
                .doesNotContain(externalSha256(audioBytes))
            for ((skill, input) in listOf(
                FileWriteSkill(files, workspace) to """{"path":"$audioPath","expectedHash":"hash","content":"改写"}""",
                FilePatchSkill(files, workspace) to """{"path":"$audioPath","expectedHash":"hash","operations":[]}""",
                MoveFileSkill(workspace, sink) to """{"path":"$audioPath","newName":"移动.wav"}""",
                DeleteFileSkill(workspace) to """{"path":"$audioPath"}""",
            )) assertThat(call(skill, mode, input, false)).contains("READ_ONLY：录音原始音频为只读")

            call(CreateDirectorySkill(workspace), mode, """{"parentPath":"$mountPath","name":"$folder"}""")
            call(CreateFileSkill(workspace, files), mode, """{"parentPath":"$mountPath","name":"$doc","content":"初稿"}""")
            val initial = entries.values.single { it.materializedPath == "/$doc" }
            assertThat(call(FileWriteSkill(files, workspace), mode, """{"path":"$docPath","expectedHash":"${initial.hash}","content":"改稿"}"""))
                .contains("资料库文档已保存并同步索引：$docPath")
            val newHash = entries.getValue(initial.uuid).hash
            assertThat(call(FilePatchSkill(files, workspace), mode,
                """{"path":"$docPath","expectedHash":"$newHash","operations":[{"action":"replace_lines","startLine":1,"endLine":1,"newContent":"定稿"}]}"""))
                .contains("资料库文档已保存并同步索引：$docPath")
            val patchedHash = entries.getValue(initial.uuid).hash
            call(MoveFileSkill(workspace, sink), mode,
                """{"path":"$docPath","newParentPath":"$folderPath","newName":"整理.md","expectedHash":"$patchedHash"}""")
            assertThat(entries.getValue(initial.uuid).materializedPath).isEqualTo("/$folder/整理.md")
            assertThat(File(root, "$folder/整理.md").readText()).isEqualTo("定稿")
            call(DeleteFileSkill(workspace), mode, """{"path":"$moved","expectedHash":"$patchedHash"}""")
            call(DeleteFileSkill(workspace), mode, """{"path":"$folderPath"}""")
            assertThat(entries).doesNotContainKey(initial.uuid)
            assertThat(published.filterIsInstance<FileIndexEvent.Changed>().count { it.fileUuid == initial.uuid }).isEqualTo(4)
        }
        assertThat(call(FileWriteSkill(files, workspace), SessionRunMode.LINUX,
            """{"path":"$mountPath/访谈.md","expectedHash":"${recording.hash}","content":"新稿"}"""))
            .contains("资料库录音整理稿已保存并同步工作台")
        assertThat(saved).containsExactly("新稿")
        call(MoveFileSkill(workspace, sink), SessionRunMode.BUILTIN, """{"path":"$mountPath/访谈.md","newName":"采访.md"}""")
        assertThat(entries.getValue("rec").materializedPath).isEqualTo("/采访.md")
        call(DeleteFileSkill(workspace), SessionRunMode.LINUX, """{"path":"$mountPath/采访.md"}""")
        assertThat(deleted).containsExactly("rec")
        assertThat(audio.readBytes()).isEqualTo(audioBytes)

        knowledgeAllowed = false
        for (mode in listOf(SessionRunMode.BUILTIN, SessionRunMode.LINUX)) {
            assertThat(call(FileReadSkill(files, workspace), mode, """{"path":"$mountPath","mode":"stat"}""", false)).contains("ACCESS_DENIED")
            assertThat(call(CreateFileSkill(workspace, files), mode, """{"parentPath":"$mountPath","name":"denied.md"}""", false)).contains("ACCESS_DENIED")
        }
        assertThat(requestMode("read_only", planner.preview(session.id)).content).contains("ACCESS_DENIED")
        knowledgeAllowed = true
        assertThat(requestMode("read_only", planner.preview(session.id)).content).contains("只读")
        assertThat(rows.value.single().id).isEqualTo(originalMount.id)
        assertThat(rows.value.single().readOnly).isTrue()
    }

    @Test fun 父级系统符号链接不影响知识库与录音虚拟音频访问且直连符号链接仍被拒绝() = runBlocking<Unit> {
        val realData = File(temporary, "data-data").apply { mkdirs() }
        val userZeroLink = Files.createSymbolicLink(File(temporary, "user-0").toPath(), realData.toPath()).toFile()
        val realKb = File(realData, "rag_workspace").apply { mkdirs() }
        val linkedKbView = File(userZeroLink, "rag_workspace")
        File(realKb, "笔记.md").writeText("正文")

        val directRootLink = Files.createSymbolicLink(File(temporary, "direct-root-link").toPath(), realKb.toPath()).toFile()
        rootEntry = rootEntry.copy(physicalRootPath = directRootLink.path)
        assertThat(assertThrows(ExternalFolderException::class.java) {
            runBlocking { manager.addKnowledgeBase("knowledge", "session", "kb-root") }
        }.code).isEqualTo("UNAVAILABLE")

        rootEntry = rootEntry.copy(physicalRootPath = linkedKbView.path)
        assertThat(manager.addKnowledgeBase("knowledge", "session", "kb-root").sourceId).isEqualTo("kb-root")
        val audioParentReal = File(realData, "recording-sessions/rec-1").apply { mkdirs() }
        val audioReal = File(audioParentReal, "recording.wav").apply { writeBytes(byteArrayOf(82, 73, 70, 70, 99)) }
        val linkedAudioView = File(userZeroLink, "recording-sessions/rec-1/recording.wav")
        val recording = rootEntry.copy(uuid = "rec-1", parentUuid = "kb-root", name = "访谈.md",
            materializedPath = "/访谈.md", isDirectory = false, mimeType = LOCAL_RECORDING_MIME)
        coEvery { workspace.observeChildren("kb-root", "kb-root") } returns MutableStateFlow(listOf(recording))

        val backend = KnowledgeBaseRecordingBackend(fileSystem.backend(linkedKbView.canonicalFile), "kb-root", workspace, { linkedAudioView }, { fileSystem.backend(it) })
        assertThat(backend.list("/").map { it.name }).containsExactly("笔记.md", "访谈.wav")
        assertThat(backend.stat("/访谈.wav")?.size).isEqualTo(audioReal.length())
        assertThat(backend.read("/访谈.wav").toList()).isEqualTo(audioReal.readBytes().toList())

        val symlinkAudio = Files.createSymbolicLink(File(realData, "linked.wav").toPath(), audioReal.toPath()).toFile()
        val symlinkParentDir = Files.createSymbolicLink(File(realData, "linked-session").toPath(), audioParentReal.toPath()).toFile()
        for (badAudio in listOf(symlinkAudio, File(symlinkParentDir, "recording.wav"))) {
            val guarded = KnowledgeBaseRecordingBackend(fileSystem.backend(realKb), "kb-root", workspace, { badAudio }, { fileSystem.backend(it) })
            assertThat(guarded.list("/").map { it.name }).containsExactly("笔记.md")
            assertThat(guarded.stat("/访谈.wav")).isNull()
        }
    }
}
