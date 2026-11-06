package com.promenar.infinity.data.storage

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.dao.ExternalMountDao
import com.promenar.infinity.data.local.db.entity.ExternalMountEntity
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.generation.resolveSessionToolTarget
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.ProtocolToolFunction
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.*
import com.promenar.infinity.domain.tool.ToolExecutionTargets
import com.promenar.infinity.infra.util.Sha256Utils
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.*
import io.mockk.*
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

class ExternalFolderManagerTest {
    private val directory = Files.createTempDirectory("external-manager-test").toFile()
    private val context = mockk<Context> { every { noBackupFilesDir } returns directory }
    private val rows = MutableStateFlow(listOf(ExternalMountEntity("mount", "termux", "saf_tree", "content://test/tree/root", "session", false, 1)))
    private val dao = mockk<ExternalMountDao> {
        coEvery { visible(any()) } answers { rows.value.filter { it.scopeKey.isNotEmpty() && it.scopeKey == firstArg<String>() } }
        every { observeVisible(any()) } answers { val id = firstArg<String>(); rows.map { list -> list.filter { it.scopeKey.isNotEmpty() && it.scopeKey == id } } }
        coEvery { all() } answers { rows.value }
        coEvery { get(id = any()) } answers { rows.value.firstOrNull { it.id == firstArg<String>() } }
        coEvery { delete(any()) } coAnswers { val id = firstArg<String>(); rows.value = rows.value.filterNot { it.id == id } }
        coEvery { update(any()) } coAnswers { val row = firstArg<ExternalMountEntity>(); rows.value = rows.value.map { if (it.id == row.id) row else it } }
        coEvery { insert(any()) } coAnswers { rows.value = rows.value + firstArg<ExternalMountEntity>() }
    }
    private val workspace = mockk<IWorkspaceRepository>()
    private val memory = TransferMemoryBackend()
    private var unavailable = false
    private val backend = object : ExternalFolderBackend by memory {
        override suspend fun read(path: String, maxBytes: Long): ByteArray {
            if (unavailable) throw ExternalFolderException("UNAVAILABLE", "授权撤销")
            return memory.read(path, maxBytes)
        }
        override suspend fun stat(path: String): ExternalFolderEntry? {
            if (unavailable) throw ExternalFolderException("UNAVAILABLE", "授权撤销")
            return memory.stat(path)
        }
        override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean): ExternalFolderEntry {
            if (expectedSha256 != null && (memory.stat(path) == null || Sha256Utils.hash(memory.read(path)) != expectedSha256)) {
                throw ExternalFolderException("CONFLICT", "版本不一致")
            }
            return memory.write(path, content, expectedSha256, createOnly)
        }
    }
    private val manager = ExternalFolderManager(context, dao, workspace, FileIndexEventSink.None) { backend }
    private val executionContext = object : SkillExecutionContext {
        override val sessionId = "session"
        override val agentId = "agent"
        override val workspaceRootUuid = "root"
        override val workspacePath: String? = null
    }

    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun `资料库挂载可新增访问并独立于会话删除`() = runBlocking {
        mockkObject(RealPathMountSupport)
        try {
            rows.value = rows.value.map { it.copy(sourceType = "real_path") }
            every { RealPathMountSupport.normalizeSource(any(), any()) } returns "/fictional/docs"
            val library = ExternalMountPaths.LIBRARY_SCOPE
            val mount = manager.addRealPath("docs", library, "/fictional/docs")
            assertThat(manager.observeMounts(library).first().map { it.id }).containsExactly(mount.id)
            assertThat(manager.visible(library).map { it.id }).containsExactly(mount.id)
            assertThat(manager.list(library, "/mnt").map { it.name }).containsExactly("docs")
            manager.write(library, "/mnt/docs/note.txt", "内容".toByteArray(), null, true)
            assertThat(String(manager.read(library, "/mnt/docs/note.txt"))).isEqualTo("内容")
            assertThat(manager.entry(library, "/mnt/docs/note.txt")?.name).isEqualTo("note.txt")
            manager.deleteSessionMounts("session")
            manager.deleteSessionMounts(library)
            assertThat(rows.value.map { it.id }).containsExactly(mount.id)
            assertThat(manager.workspaceMount(library)).isNull()
            assertThat(assertThrows(ExternalFolderException::class.java) {
                runBlocking { manager.addKnowledgeBase("kb", library, "root") }
            }.code).isEqualTo("INVALID_SESSION")
        } finally { unmockkObject(RealPathMountSupport) }
    }

    @Test fun `引用资料库来源复用行且移除资料库后会话仍可访问`() = runBlocking {
        val library = ExternalMountPaths.LIBRARY_SCOPE
        rows.value = listOf(ExternalMountEntity("library", "docs", "real_path", "/fictional/docs", library, true, 1))
        memory.write("/note.txt", "hello".toByteArray())
        val attached = manager.attachLibraryMount("session", "library")
        assertThat(attached.id).isNotEqualTo("library")
        assertThat(attached.sourceId).isEqualTo("/fictional/docs")
        assertThat(attached.readOnly).isTrue()
        assertThat(manager.attachLibraryMount("session", "library", "other").id).isEqualTo(attached.id)
        assertThat(manager.libraryMountReferences("library")).isEqualTo(1)
        assertThat(assertThrows(ExternalFolderException::class.java) {
            runBlocking { manager.attachLibraryMount(library, "library") }
        }.code).isEqualTo("INVALID_SESSION")
        rows.value = rows.value + ExternalMountEntity("invalid", "kb", "knowledge_base", "root", library, true, 1)
        assertThat(assertThrows(ExternalFolderException::class.java) {
            runBlocking { manager.attachLibraryMount("session", "invalid") }
        }.code).isEqualTo("UNSUPPORTED")
        manager.remove("library")
        assertThat(String(manager.read("session", "/mnt/docs/note.txt"))).isEqualTo("hello")
    }

    @Test fun `引用时别名冲突和不可用来源均拒绝插入`() = runBlocking {
        rows.value = listOf(
            ExternalMountEntity("library", "docs", "real_path", "/fictional/docs", ExternalMountPaths.LIBRARY_SCOPE, false, 1),
            ExternalMountEntity("existing", "docs", "real_path", "/fictional/other", "session", false, 1),
        )
        assertThat(assertThrows(ExternalFolderException::class.java) {
            runBlocking { manager.attachLibraryMount("session", "library") }
        }.code).isEqualTo("CONFLICT")
        unavailable = true
        assertThat(assertThrows(ExternalFolderException::class.java) {
            runBlocking { manager.attachLibraryMount("session", "library", "other") }
        }.code).isEqualTo("UNAVAILABLE")
        assertThat(rows.value).hasSize(2)
    }

    @Test fun `移除资料库 SAF 挂载时会话引用保留系统授权`() = runBlocking {
        rows.value = listOf(ExternalMountEntity("library", "docs", "saf_tree", "content://test/tree/root", ExternalMountPaths.LIBRARY_SCOPE, false, 1))
        val attached = manager.attachLibraryMount("session", "library")
        manager.remove("library")
        assertThat(rows.value.map { it.id }).containsExactly(attached.id)
        verify(exactly = 0) { context.contentResolver }
    }

    @Test fun `删除目录只清理可计算的当前版本键`() = runBlocking<Unit> {
        memory.createDirectory("/docs")
        memory.write("/docs/a.txt", "old".toByteArray())
        memory.write("/keep.txt", "keep".toByteArray())
        manager.read("session", "/mnt/termux/docs/a.txt")
        manager.read("session", "/mnt/termux/keep.txt")
        memory.write("/docs/a.txt", "current".toByteArray())
        manager.read("session", "/mnt/termux/docs/a.txt")
        val oldHash = Sha256Utils.hash("old")
        val currentHash = Sha256Utils.hash("current")
        val keepHash = Sha256Utils.hash("keep")
        manager.backend("session").delete("/mnt/termux/docs")
        assertThat(memory.stat("/docs")).isNull()
        assertThat(manager.version("session", "/mnt/termux/docs/a.txt", currentHash)).isNull()
        assertThat(String(manager.version("session", "/mnt/termux/docs/a.txt", oldHash)!!)).isEqualTo("old")
        assertThat(String(manager.version("session", "/mnt/termux/keep.txt", keepHash)!!)).isEqualTo("keep")
    }

    @Test fun `删除完成后迟到的读取不能重新保存版本快照`() = runBlocking<Unit> {
        memory.write("/a.txt", "content".toByteArray())
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val delayed = object : ExternalFolderBackend by memory {
            override suspend fun read(path: String, maxBytes: Long): ByteArray {
                val bytes = memory.read(path, maxBytes)
                readStarted.complete(Unit)
                releaseRead.await()
                return bytes
            }
        }
        val isolated = ExternalFolderManager(context, dao, workspace, FileIndexEventSink.None) { delayed }
        val read = async { isolated.read("session", "/mnt/termux/a.txt") }
        withTimeout(3000) { readStarted.await() }
        isolated.delete("session", "/mnt/termux/a.txt", null)
        releaseRead.complete(Unit)
        assertThat(String(read.await())).isEqualTo("content")
        assertThat(isolated.version("session", "/mnt/termux/a.txt", Sha256Utils.hash("content"))).isNull()
    }

    @Test fun `新增挂载必须指定非空会话且在申请权限前拒绝空白`() = runBlocking {
        for (session in listOf("", " ", "\t")) {
            assertThat(assertThrows(ExternalFolderException::class.java) {
                runBlocking { manager.addSaf("docs", session, "content://test/tree/root", 0) }
            }.code).isEqualTo("INVALID_SESSION")
            assertThat(assertThrows(ExternalFolderException::class.java) {
                runBlocking { manager.addRealPath("docs", session, "/storage/emulated/0/docs") }
            }.code).isEqualTo("INVALID_SESSION")
        }
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test fun `删除会话挂载只移除本会话且保留来源文件`() = runBlocking {
        rows.value = listOf(
            rows.value.single().copy(sourceType = "real_path"),
            rows.value.single().copy(id = "other", scopeKey = "other", sourceType = "real_path"),
        )
        memory.write("/a.txt", "保留".toByteArray())
        manager.deleteSessionMounts("session")
        assertThat(rows.value.map { it.id }).containsExactly("other")
        assertThat(String(memory.read("/a.txt"))).isEqualTo("保留")
        coVerify(exactly = 1) { dao.delete("mount") }
    }

    @Test fun `只读挂载保留查询且所有写原语在备份前拒绝`() = runBlocking {
        memory.write("/a.txt", "hello".toByteArray())
        rows.value = rows.value.map { it.copy(readOnly = true) }
        val backend = manager.backend("session")
        val operations: List<suspend () -> Any?> = listOf(
            { manager.write("session", "/mnt/termux/a.txt", "changed".toByteArray(), null, false) },
            { manager.createDirectory("session", "/mnt/termux/dir") },
            { manager.delete("session", "/mnt/termux/a.txt", null) },
            { manager.importSnapshot("session", "root", "/mnt/termux/a.txt") },
            { backend.writeStream("/mnt/termux/new", 0) { error("只读不得运行生产者") } },
            { backend.write("/mnt/termux/a.txt", "changed".toByteArray()) },
            { backend.createDirectory("/mnt/termux/dir") },
            { backend.delete("/mnt/termux/a.txt") },
            { backend.move("/mnt/termux/a.txt", "/mnt/termux/b.txt") },
            { backend.copy("/mnt/termux/a.txt", "/mnt/termux/b.txt") },
        )
        operations.forEach { action ->
            val failure = assertThrows(ExternalFolderException::class.java) { runBlocking { action() } }
            assertThat(failure.code).isEqualTo("READ_ONLY")
            assertThat(failure.message).isEqualTo("知识库挂载为只读")
        }
        assertThat(String(manager.read("session", "/mnt/termux/a.txt"))).isEqualTo("hello")
        assertThat(manager.list("session", "/mnt/termux").map { it.name }).containsExactly("a.txt")
        assertThat(backend.stat("/mnt/termux/a.txt")?.isDirectory).isFalse()
        assertThat(manager.visible("session").single { it.id == "mount" }.readOnly).isTrue()
        assertThat(memory.stat("/dir")).isNull()
        assertThat(memory.stat("/b.txt")).isNull()
        assertThat(assertThrows(ExternalFolderException::class.java) { runBlocking { manager.read("different", "/mnt/termux/a.txt") } }.code).isEqualTo("NOT_FOUND")
        coVerify(exactly = 0) { workspace.createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `挂载文件工具写入修补新建移动删除均被只读收口而搜索正常`() = runBlocking {
        memory.write("/a.txt", "original".toByteArray())
        memory.createDirectory("/docs")
        rows.value = rows.value.map { it.copy(readOnly = true) }
        val operations = mockk<com.promenar.infinity.domain.repository.IFileOperationRepository>()
        fun skill(original: com.promenar.infinity.ui.chat.manager.registry.SkillDefinition) =
            MountedWorkspaceSkill(original, manager, workspace, operations, FileIndexEventSink.None)
        val hash = Sha256Utils.hash("original")
        val calls = listOf(
            FileWriteSkill(operations, workspace) to """{"path":"/mnt/termux/a.txt","content":"changed","expected_sha256":"$hash"}""",
            FilePatchSkill(operations, workspace) to """{"path":"/mnt/termux/a.txt","expected_sha256":"$hash","operations":[{"action":"insert_after","afterLine":1,"newContent":"changed"}]}""",
            CreateFileSkill(workspace, operations) to """{"parentPath":"/mnt/termux","name":"new.txt","content":"changed"}""",
            CreateDirectorySkill(workspace) to """{"parentPath":"/mnt/termux","name":"new"}""",
            MoveFileSkill(workspace, FileIndexEventSink.None) to """{"path":"/mnt/termux/a.txt","newParentPath":"/mnt/termux/docs"}""",
            DeleteFileSkill(workspace) to """{"path":"/mnt/termux/a.txt"}""",
        )
        calls.forEach { (original, arguments) ->
            val result = skill(original).execute(args(arguments), executionContext)
            assertThat(result.status).isEqualTo("error")
            assertThat(result.content).contains("READ_ONLY：知识库挂载为只读")
        }
        coEvery { workspace.searchByName("root", "a.txt") } returns MutableStateFlow(emptyList())
        assertThat(skill(FileSearchSkill(workspace, operations)).execute(args("""{"query":"a.txt"}"""), executionContext).content)
            .contains("/mnt/termux/a.txt")
        assertThat(skill(FileReadSkill(operations, workspace)).execute(args("""{"path":"/mnt/termux/a.txt"}"""), executionContext).content)
            .contains("original")
        assertThat(String(memory.read("/a.txt"))).isEqualTo("original")
        assertThat(memory.stat("/docs/a.txt")).isNull()
    }

    @Test fun `移动可写来源到只读目标时拒绝且保留来源`() = runBlocking {
        memory.write("/a.txt", "original".toByteArray())
        memory.createDirectory("/docs")
        rows.value = rows.value + rows.value.single().copy(id = "readonly", alias = "readonly", readOnly = true)
        val operations = mockk<com.promenar.infinity.domain.repository.IFileOperationRepository>()
        val skill = MountedWorkspaceSkill(MoveFileSkill(workspace, FileIndexEventSink.None), manager, workspace,
            operations, FileIndexEventSink.None)
        val result = skill.execute(args("""{"path":"/mnt/termux/a.txt","newParentPath":"/mnt/readonly/docs"}"""), executionContext)
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("READ_ONLY：知识库挂载为只读")
        assertThat(String(memory.read("/a.txt"))).isEqualTo("original")
        assertThat(memory.stat("/docs/a.txt")).isNull()
    }

    @Test fun `授权撤销写入不可用状态且恢复授权清除错误`() = runBlocking {
        memory.write("/a.txt", byteArrayOf(1))
        unavailable = true
        assertThat(assertThrows(ExternalFolderException::class.java) { runBlocking { manager.read("session", "/mnt/termux/a.txt") } }.code).isEqualTo("UNAVAILABLE")
        assertThat(rows.value.single().unavailableReason).isNotNull()
        unavailable = false
        manager.read("session", "/mnt/termux/a.txt")
        assertThat(rows.value.single().unavailableReason).isNull()
    }

    @Test fun `版本缓存不能跨挂载文件与会话读取`() = runBlocking {
        memory.write("/secret.txt", "private value".toByteArray())
        val hash = Sha256Utils.hash(manager.read("session", "/mnt/termux/secret.txt"))
        rows.value = rows.value + rows.value.single().copy(id = "other", alias = "public", scopeKey = "other-session")
        assertThat(manager.version("other-session", "/mnt/public/other.txt", hash)).isNull()
        assertThat(manager.version("session", "/mnt/termux/other.txt", hash)).isNull()
        assertThat(String(manager.version("session", "/mnt/termux/secret.txt", hash)!!)).isEqualTo("private value")
    }

    @Test fun `写入回执固定来源且跨文件操作拒绝同名挂载替换`() = runBlocking<Unit> {
        val switching = object : ExternalFolderBackend by backend {
            override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean): ExternalFolderEntry {
                val result = backend.write(path, content, expectedSha256, createOnly)
                rows.value = rows.value.map { it.copy(id = "replacement") }
                return result
            }
        }
        val writer = ExternalFolderManager(context, dao, workspace, FileIndexEventSink.None) { switching }
        val result = writer.write("session", "/mnt/termux/a.txt", "one".toByteArray(), null, true)
        assertThat(result.uuid).startsWith("external:mount:")
        val bound = manager.backend("session", mapOf("termux" to "mount"))
        assertThat(assertThrows(ExternalFolderException::class.java) {
            runBlocking { bound.write("/mnt/termux/other.txt", "other".toByteArray(), createOnly = true) }
        }.code).isEqualTo("CONFLICT")
        assertThat(memory.stat("/other.txt")).isNull()
    }

    @Test fun `不支持索引的文件不能创建知识库快照`() = runBlocking {
        memory.write("/image.png", byteArrayOf(1, 2, 3))
        assertThat(assertThrows(ExternalFolderException::class.java) {
            runBlocking { manager.importSnapshot("session", "root", "/mnt/termux/image.png") }
        }.code).isEqualTo("UNSUPPORTED")
        coVerify(exactly = 0) { workspace.createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `显式UUID解析期间同别名换源不能操作替换挂载`() = runBlocking<Unit> {
        memory.write("/a.txt", "original".toByteArray())
        val original = rows.value
        var queries = 0
        coEvery { dao.visible("session") } answers {
            if (queries++ == 0) original.also { rows.value = original.map { it.copy(id = "replacement") } }
            else rows.value
        }
        val operations = mockk<com.promenar.infinity.domain.repository.IFileOperationRepository>()
        val result = MountedWorkspaceSkill(FileWriteSkill(operations, workspace), manager, workspace, operations, FileIndexEventSink.None)
            .execute(args("""{"uuid":"external:mount:L2EudHh0","content":"changed","expected_sha256":"${Sha256Utils.hash("original")}"}"""), executionContext)
        assertThat(result.status).isEqualTo("error")
        assertThat(String(memory.read("/a.txt"))).isEqualTo("original")
    }

    @Test fun `路径工具调用期间同别名换源不能写入替换挂载`() = runBlocking<Unit> {
        memory.write("/a.txt", "original".toByteArray())
        val original = rows.value
        var queries = 0
        coEvery { dao.visible("session") } answers {
            if (queries++ == 0) original.also { rows.value = original.map { it.copy(id = "replacement") } }
            else rows.value
        }
        val operations = mockk<com.promenar.infinity.domain.repository.IFileOperationRepository>()
        val result = MountedWorkspaceSkill(FileWriteSkill(operations, workspace), manager, workspace, operations, FileIndexEventSink.None)
            .execute(args("""{"path":"/mnt/termux/a.txt","content":"changed","expected_sha256":"${Sha256Utils.hash("original")}"}"""), executionContext)
        assertThat(result.status).isEqualTo("error")
        assertThat(String(memory.read("/a.txt"))).isEqualTo("original")
    }

    @Test fun `批准身份到技能执行之间换源必须拒绝写入`() = runBlocking<Unit> {
        memory.write("/a.txt", "original".toByteArray())
        rows.value = rows.value.map { it.copy(id = "replacement") }
        val approvedContext = object : SkillExecutionContext by executionContext {
            override val approvedExecutionTarget = """["path","/mnt/termux/a.txt",{"mountIds":{"termux":"mount"}}]"""
        }
        val operations = mockk<com.promenar.infinity.domain.repository.IFileOperationRepository>()
        val result = MountedWorkspaceSkill(FileWriteSkill(operations, workspace), manager, workspace, operations, FileIndexEventSink.None)
            .execute(args("""{"path":"/mnt/termux/a.txt","content":"changed","expected_sha256":"${Sha256Utils.hash("original")}"}"""), approvedContext)
        assertThat(result.status).isEqualTo("error")
        assertThat(String(memory.read("/a.txt"))).isEqualTo("original")
    }

    @Test fun `审批原目标不可用时不能接受后来出现的挂载`() = runBlocking<Unit> {
        memory.write("/a.txt", "original".toByteArray())
        val approvedContext = object : SkillExecutionContext by executionContext {
            override val isApprovalBoundExecution = true
        }
        val operations = mockk<com.promenar.infinity.domain.repository.IFileOperationRepository>()
        val result = MountedWorkspaceSkill(FileWriteSkill(operations, workspace), manager, workspace, operations, FileIndexEventSink.None)
            .execute(args("""{"path":"/mnt/termux/a.txt","content":"changed","expected_sha256":"${Sha256Utils.hash("original")}"}"""), approvedContext)
        assertThat(result.status).isEqualTo("error")
        assertThat(String(memory.read("/a.txt"))).isEqualTo("original")
    }

    @Test fun `快照索引拒绝时通过原有创建授权回滚文件`() = runBlocking<Unit> {
        memory.write("/note.md", "note".toByteArray())
        coEvery { workspace.createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            FileEntry(secondArg(), "root", "root", thirdArg(), Sha256Utils.hash("note"), mimeType = arg(3), physicalRootPath = "", materializedPath = arg(5), createdAt = 1, updatedAt = 1)
        }
        coEvery { workspace.confirmCreatedEntry(any(), any()) } just Runs
        coEvery { workspace.rollbackCreatedEntry(any(), any()) } just Runs
        coEvery { dao.putSnapshot(any()) } just Runs
        val failed = ExternalFolderManager(context, dao, workspace, FileIndexEventSink { throw IllegalStateException("索引未接收") }) { backend }
        assertThrows(IllegalStateException::class.java) { runBlocking { failed.importSnapshot("session", "root", "/mnt/termux/note.md") } }
        coVerify(exactly = 1) { workspace.rollbackCreatedEntry("root", any()) }
        coVerify(exactly = 0) { workspace.confirmCreatedEntry(any(), any()) }
    }

    @Test fun `十个工具共用挂载路由并校验写入sha256`() = runBlocking {
        val fileOperations = mockk<com.promenar.infinity.domain.repository.IFileOperationRepository>()
        fun skill(original: com.promenar.infinity.ui.chat.manager.registry.SkillDefinition) = MountedWorkspaceSkill(original, manager, workspace, fileOperations, FileIndexEventSink.None)
        val create = skill(CreateFileSkill(workspace, fileOperations)).execute(args("""{"parentPath":"/mnt/termux","name":"a.txt","content":"one\ntwo"}"""), executionContext)
        assertThat(create.status).isEqualTo("success")
        val read = skill(FileReadSkill(fileOperations, workspace)).execute(args("""{"path":"/mnt/termux/a.txt"}"""), executionContext)
        assertThat(read.content).contains("1| one")
        assertThat(read.content).contains("sha256:")
        val write = skill(FileWriteSkill(fileOperations, workspace))
        val rejected = write.execute(args("""{"path":"/mnt/termux/a.txt","content":"lost","expected_sha256":"wrong"}"""), executionContext)
        assertThat(rejected.status).isEqualTo("error")
        assertThat(String(memory.read("/a.txt"))).isEqualTo("one\ntwo")
        val hash = Sha256Utils.hash(memory.read("/a.txt"))
        assertThat(write.execute(args("""{"path":"/mnt/termux/a.txt","content":"new","expected_sha256":"$hash"}"""), executionContext).status).isEqualTo("success")
        val patchHash = Sha256Utils.hash(memory.read("/a.txt"))
        assertThat(skill(FilePatchSkill(fileOperations, workspace)).execute(args("""{"path":"/mnt/termux/a.txt","expected_sha256":"$patchHash","operations":[{"action":"insert_after","afterLine":1,"newContent":"last"}]}"""), executionContext).status).isEqualTo("success")
        assertThat(String(memory.read("/a.txt"))).isEqualTo("new\nlast")
        assertThat(skill(FileDiffSkill(fileOperations, workspace)).execute(args("""{"path":"/mnt/termux/a.txt","basisHash":"$hash"}"""), executionContext).content).contains("removed")
        assertThat(skill(FileListSkill(workspace)).execute(args("""{"path":"/mnt/termux"}"""), executionContext).content).contains("/mnt/termux/a.txt")
        assertThat(skill(CreateDirectorySkill(workspace)).execute(args("""{"parentPath":"/mnt/termux","name":"docs"}"""), executionContext).status).isEqualTo("success")
        coEvery { workspace.searchByName("root", "a.txt") } returns MutableStateFlow(emptyList())
        assertThat(skill(FileSearchSkill(workspace, fileOperations)).execute(args("""{"query":"a.txt"}"""), executionContext).content).contains("/mnt/termux/a.txt")
        assertThat(skill(MoveFileSkill(workspace, FileIndexEventSink.None)).execute(args("""{"path":"/mnt/termux/a.txt","newParentPath":"/mnt/termux/docs"}"""), executionContext).status).isEqualTo("success")
        assertThat(skill(DeleteFileSkill(workspace)).execute(args("""{"path":"/mnt/termux/docs/a.txt"}"""), executionContext).status).isEqualTo("success")
        assertThat(memory.stat("/docs/a.txt")).isNull()
        coVerify(exactly = 0) { fileOperations.writeFileAtomic(any(), any(), any(), any(), any()) }
    }

    @Test fun `历史mnt私有目录UUID创建不能写入同名外部挂载`() = runBlocking<Unit> {
        val legacy = FileEntry("legacy", "root", "root", "termux", "", isDirectory = true,
            physicalRootPath = "", materializedPath = "/mnt/termux", createdAt = 1, updatedAt = 1)
        coEvery { workspace.getByUuid("root", "legacy") } returns legacy
        coEvery { workspace.createFileInWorkspace(any(), any(), any(), any(), any(), any()) } returns legacy.copy(uuid = "child", name = "a.txt", isDirectory = false)
        coEvery { workspace.createDirectoryInWorkspace(any(), any(), any(), any(), any()) } returns legacy.copy(uuid = "directory", name = "docs")
        val operations = mockk<com.promenar.infinity.domain.repository.IFileOperationRepository>()
        fun routed(original: com.promenar.infinity.ui.chat.manager.registry.SkillDefinition) = MountedWorkspaceSkill(original, manager, workspace, operations, FileIndexEventSink.None)
        assertThat(routed(CreateFileSkill(workspace, operations)).execute(args("""{"parentUuid":"legacy","name":"a.txt"}"""), executionContext).status).isEqualTo("success")
        assertThat(routed(CreateDirectorySkill(workspace)).execute(args("""{"parentUuid":"legacy","name":"docs"}"""), executionContext).status).isEqualTo("success")
        assertThat(memory.stat("/a.txt")).isNull()
        assertThat(memory.stat("/docs")).isNull()
        coVerify(exactly = 1) { workspace.createFileInWorkspace("root", any(), "a.txt", "", "legacy", "/mnt/termux/a.txt") }
        coVerify(exactly = 1) { workspace.createDirectoryInWorkspace("root", any(), "docs", "legacy", "/mnt/termux/docs") }
    }

    @Test fun `审批目标绑定挂载身份并保持可读路径`() = runBlocking {
        val tool = ProtocolTool(function = ProtocolToolFunction("write_file", "", "{}"))
        val session = Session(id = "session", agentId = "agent", title = "test", workspaceRootUuid = "root")
        val first = resolveSessionToolTarget(session, tool, """{"path":"/mnt/termux/a.txt"}""", workspace, manager)!!
        assertThat(ToolExecutionTargets.display(first)).isEqualTo("/mnt/termux/a.txt")
        rows.value = rows.value.map { it.copy(id = "replacement") }
        val second = resolveSessionToolTarget(session, tool, """{"path":"/mnt/termux/a.txt"}""", workspace, manager)
        assertThat(second).isNotEqualTo(first)
        assertThat(resolveSessionToolTarget(session.copy(id = "other"), tool, """{"path":"/mnt/termux/a.txt"}""", workspace, manager)).isNull()
        val deletion = tool.copy(function = tool.function.copy(name = "delete_file"))
        val arguments = """{"path":"/mnt/termux/a.txt"}"""
        val approvalTarget = resolveSessionToolTarget(session, deletion, arguments, workspace, manager)!!
        assertThat(ToolExecutionTargets.display(approvalTarget)).isEqualTo("/mnt/termux/a.txt")
        assertThat(ToolExecutionTargets.canRemember(approvalTarget)).isFalse()
        val grants = com.promenar.infinity.data.repository.ToolAllowlistStore(directory.resolve("grants"))
        grants.add("delete_file", approvalTarget, "mount:replacement", "session")
        assertThat(grants.contains("delete_file", approvalTarget, "mount:replacement")).isFalse()
        assertThat(grants.entries.value).isEmpty()
        rows.value = rows.value.map { it.copy(id = "changed-again") }
        assertThat(resolveSessionToolTarget(session, deletion, arguments, workspace, manager)).isNotEqualTo(approvalTarget)
    }

    @Test fun `重复插入坐标与私有工作区一样整批拒绝`() = runBlocking<Unit> {
        memory.write("/a.txt", "original".toByteArray())
        val operations = mockk<com.promenar.infinity.domain.repository.IFileOperationRepository>()
        val result = MountedWorkspaceSkill(FilePatchSkill(operations, workspace), manager, workspace, operations, FileIndexEventSink.None)
            .execute(args("""{"path":"/mnt/termux/a.txt","expected_sha256":"${Sha256Utils.hash("original")}","operations":[{"action":"insert_after","afterLine":1,"newContent":"first"},{"action":"insert_after","afterLine":1,"newContent":"second"}]}"""), executionContext)
        assertThat(result.status).isEqualTo("error")
        assertThat(String(memory.read("/a.txt"))).isEqualTo("original")
    }

    private fun args(raw: String) = Json.parseToJsonElement(raw).jsonObject
}
