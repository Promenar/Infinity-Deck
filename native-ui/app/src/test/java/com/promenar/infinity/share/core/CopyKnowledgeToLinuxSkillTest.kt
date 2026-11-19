package com.promenar.infinity.share.core

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.generation.DefaultSessionToolResolver
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.data.storage.JvmBoundRealPathFileSystem
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.storage.*
import com.promenar.infinity.domain.tool.ToolExecutionTargets
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.ApprovalPreview
import com.promenar.infinity.ui.chat.manager.registry.*
import com.promenar.infinity.ui.chat.manager.skills.CopyKnowledgeToLinuxSkill
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class CopyKnowledgeToLinuxSkillTest {
    @get:Rule val temporary = TemporaryFolder()
    private var session = Session("s", "agent", runMode = SessionRunMode.LINUX)
    private var allowed = true
    private var mountsAllowed = true
    private var reconciled = 0
    private lateinit var rootfsDirectory: File
    private val sourcePath = "/mnt/knowledge/data.sqlite"
    private val context = mockk<SkillExecutionContext>(relaxed = true).also {
        every { it.sessionId } returns "s"
        every { it.runMode } returns SessionRunMode.LINUX
        every { it.isApprovalBoundExecution } returns false
        every { it.approvedExecutionTarget } returns null
    }
    private fun args(source: String = sourcePath, target: String? = null) = buildJsonObject {
        put("source", source)
        if (target != null) put("target", target)
    }
    private fun fixture(): Triple<CopyKnowledgeToLinuxSkill, ShareWorkspaceFixture, File> {
        val sourceRoot = temporary.newFolder().canonicalFile
        File(sourceRoot, "data.sqlite").writeBytes(byteArrayOf(0, 1, 2, 3))
        val base = JvmBoundRealPathFileSystem().backend(sourceRoot)
        val mount = ExternalMount("mount-id", "knowledge", ExternalMountPaths.KNOWLEDGE_BASE_SOURCE, "kb", "s", 1, readOnly = true)
        val access = mockk<ExternalWorkspaceAccess>()
        coEvery { access.visible("s") } answers { if (mountsAllowed) listOf(mount) else emptyList() }
        fun relative(path: String) = path.removePrefix("/mnt/knowledge").ifEmpty { "/" }
        val backend = object : ExternalFolderBackend by base {
            override suspend fun stat(path: String) = base.stat(relative(path))?.copy(path = path, name = if (path == "/mnt/knowledge") "knowledge" else path.substringAfterLast('/'))
            override suspend fun list(path: String) = base.list(relative(path)).map { it.copy(path = "/mnt/knowledge" + it.path) }
            override suspend fun read(path: String, maxBytes: Long) = base.read(relative(path), maxBytes)
            override suspend fun <T> readStream(path: String, maxBytes: Long, consume: suspend (java.io.InputStream) -> T): T =
                base.readStream(relative(path), maxBytes, consume)
        }
        every { access.backend("s", mapOf("knowledge" to "mount-id")) } returns backend
        val workspace = ShareWorkspaceFixture(temporary.newFolder())
        val rootfs = temporary.newFolder().also { rootfsDirectory = it }
        File(rootfs, "root").mkdirs()
        return Triple(CopyKnowledgeToLinuxSkill({ session }, { allowed }, access, workspace.repo,
            shareLinuxFiles(rootfs), { true }, { reconciled++ }).apply { rootfsFileSystem = JvmBoundRealPathFileSystem() }, workspace, sourceRoot)
    }

    @Test
    fun `广告条件和执行重验共用权限且风险为文件写入`() = runTest {
        val skill = fixture().first
        val registry = mockk<SkillRegistry>()
        every { registry.getAllTools(null) } returns listOf(skill.toProtocolTool())
        val settings = mockk<SharedPreferences>(relaxed = true)
        every { settings.getStringSet(any(), null) } returns null
        val resolver = DefaultSessionToolResolver(settings, registry, knowledgeAccessAllowed = { allowed })
        assertThat(resolver.resolve(session).single().runtimeToolId).isEqualTo("copy_knowledge_to_linux")
        assertThat(skill.risk).isEqualTo(ToolRisk.FILE_WRITE)
        assertThat(resolver.resolve(session.copy(runMode = SessionRunMode.BUILTIN))).isEmpty()
        allowed = false
        assertThat(resolver.resolveForExecution(session)).isEmpty()
        assertThat(skill.execute(args(), context).status).isEqualTo("error")
        allowed = true
        session = session.copy(runMode = SessionRunMode.BUILTIN)
        assertThat(skill.execute(args(), context).status).isEqualTo("error")
    }

    @Test
    fun `源必须有本会话知识库只读挂载授权`() = runTest {
        val (skill, workspace) = fixture()
        mountsAllowed = false
        val result = skill.execute(args(), context)
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("request_knowledge_access")
        coVerify(exactly = 0) { workspace.repo.createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) }
        mountsAllowed = true
        assertThat(skill.execute(args("/mnt/other/data.sqlite"), context).status).isEqualTo("error")
    }

    @Test
    fun `读写授权的知识库挂载同样允许复制到Linux`() = runTest {
        val rwMount = ExternalMount("mount-rw", "knowledge", ExternalMountPaths.KNOWLEDGE_BASE_RW_SOURCE, "kb", "s", 1, readOnly = false)
        val sourceRoot = temporary.newFolder().canonicalFile
        File(sourceRoot, "data.sqlite").writeBytes(byteArrayOf(7, 8, 9))
        val base = JvmBoundRealPathFileSystem().backend(sourceRoot)
        val access = mockk<ExternalWorkspaceAccess>()
        coEvery { access.visible("s") } returns listOf(rwMount)
        val backend = object : ExternalFolderBackend by base {
            override suspend fun stat(path: String) = base.stat(path.removePrefix("/mnt/knowledge").ifEmpty { "/" })
                ?.copy(path = path, name = if (path == "/mnt/knowledge") "knowledge" else path.substringAfterLast('/'))
            override suspend fun <T> readStream(path: String, maxBytes: Long, consume: suspend (java.io.InputStream) -> T): T =
                base.readStream(path.removePrefix("/mnt/knowledge").ifEmpty { "/" }, maxBytes, consume)
        }
        every { access.backend("s", mapOf("knowledge" to "mount-rw")) } returns backend
        val workspace = ShareWorkspaceFixture(temporary.newFolder())
        val skill = CopyKnowledgeToLinuxSkill({ session }, { true }, access, workspace.repo,
            shareLinuxFiles(temporary.newFolder().apply { File(this, "root").mkdirs() }), { true }, { reconciled++ })
            .apply { rootfsFileSystem = JvmBoundRealPathFileSystem() }
        val result = skill.execute(args(), context)
        assertThat(result.status).isEqualTo("success")
        assertThat(File(workspace.root("root-s").physicalRootPath, "inbox/data.sqlite").readBytes()).isEqualTo(byteArrayOf(7, 8, 9))
    }

    @Test
    fun `默认落到会话inbox并对账且审批同时展示源和目标`() = runTest {
        val (skill, workspace) = fixture()
        val result = skill.execute(args(), context)
        com.google.common.truth.Truth.assertWithMessage(result.content).that(result.status).isNotEqualTo("error")
        assertThat(File(workspace.root("root-s").physicalRootPath, "inbox/data.sqlite").readBytes()).isEqualTo(byteArrayOf(0, 1, 2, 3))
        assertThat(reconciled).isEqualTo(1)
        assertThat(result.content).contains("/home/session/inbox")
        val preview = ApprovalPreview.format(skill.name, args())!!
        assertThat(preview.target).isEqualTo(CopyKnowledgeToLinuxSkill.DEFAULT_TARGET)
        assertThat(preview.detail).contains(sourcePath)
        val target = ToolExecutionTargets.extract(skill.toProtocolTool(), args().toString())!!
        assertThat(target).contains(sourcePath)
        assertThat(target).contains(CopyKnowledgeToLinuxSkill.DEFAULT_TARGET)
    }

    @Test
    fun `rootfs落点创建目标目录并沿用工作区模式的写入审批`() = runTest {
        val (skill, _) = fixture()
        val arguments = args(target = "/root/inbox")
        val result = skill.execute(arguments, context)
        com.google.common.truth.Truth.assertWithMessage(result.content).that(result.status).isEqualTo("success")
        assertThat(File(rootfsDirectory, "root/inbox/data.sqlite").readBytes()).isEqualTo(byteArrayOf(0, 1, 2, 3))
        val target = ToolExecutionTargets.extract(skill.toProtocolTool(), arguments.toString())!!
        assertThat(com.promenar.infinity.domain.tool.ToolExecutionPolicy.requiresApproval(
            com.promenar.infinity.domain.model.ExecutionMode.WORKSPACE, skill.id, skill.risk,
            arguments.toString(), target, usesLinux = true)).isTrue()
        assertThat(reconciled).isEqualTo(0)
    }

    @Test
    fun `目标拒绝挂载点越界非私有工作区和符号链接`() = runTest {
        val (skill, workspace) = fixture()
        val privateRoot = workspace.repo.ensureSessionRoot("s")
        Files.createSymbolicLink(File(privateRoot.physicalRootPath, "escape").toPath(), temporary.newFolder().toPath())
        for (target in listOf("/mnt/knowledge", "/root/../outside", "/../../tmp", "/home/session/escape")) {
            assertThat(skill.execute(args(target = target), context).status).isEqualTo("error")
        }
        session = session.copy(workspaceId = "selected")
        assertThat(skill.execute(args(target = "/workspace/inbox"), context).status).isEqualTo("error")
        coVerify(exactly = 0) { workspace.repo.createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `审批绑定来源挂载身份以及私有目标根`() = runTest {
        val (skill, workspace) = fixture()
        every { context.isApprovalBoundExecution } returns true
        every { context.approvedExecutionTarget } returns "[\"path\",\"/home/session/inbox\",{\"source\":\"/mnt/knowledge/data.sqlite\",\"mountIds\":{\"knowledge\":\"removed-mount\"}}]"
        assertThat(skill.execute(args(), context).status).isEqualTo("error")
        coVerify(exactly = 0) { workspace.repo.createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `目录总量恰好100MiB后仍能复制空文件`() = runTest {
        val (skill, workspace, source) = fixture()
        val directory = File(source, "exact").apply { mkdirs() }
        java.io.RandomAccessFile(File(directory, "a.sqlite"), "rw").use { it.setLength(CopyKnowledgeToLinuxSkill.MAX_BYTES) }
        File(directory, "z.empty").writeBytes(byteArrayOf())
        val result = skill.execute(args("/mnt/knowledge/exact"), context)
        com.google.common.truth.Truth.assertWithMessage(result.content).that(result.status).isNotEqualTo("error")
        val target = File(workspace.root("root-s").physicalRootPath, "inbox/exact")
        assertThat(File(target, "a.sqlite").length()).isEqualTo(CopyKnowledgeToLinuxSkill.MAX_BYTES)
        assertThat(File(target, "z.empty").isFile).isTrue()
    }

    @Test
    fun `技能目录与会话根导入agents目录走逐次审批`() = runTest {
        val (skill, workspace, source) = fixture()
        File(source, ".agents").mkdirs()
        assertThat(skill.execute(args(target = "/home/session/.agents/skills"), context).status).isEqualTo("error")
        assertThat(skill.execute(args("/mnt/knowledge/.agents", "/home/session"), context).status).isEqualTo("error")
        coVerify(exactly = 0) { workspace.repo.createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `目录复制跳过源符号链接并拒绝超过总量`() = runTest {
        val (skill, workspace, source) = fixture()
        val directory = File(source, "books").apply { mkdirs() }
        File(directory, "one.epub").writeText("book")
        Files.createSymbolicLink(File(directory, "outside.epub").toPath(), File(source, "data.sqlite").toPath())
        val result = skill.execute(args("/mnt/knowledge/books/"), context)
        com.google.common.truth.Truth.assertWithMessage(result.content).that(result.status).isNotEqualTo("error")
        val target = File(workspace.root("root-s").physicalRootPath, "inbox/books")
        assertThat(target.list()).asList().containsExactly("one.epub")
        java.io.RandomAccessFile(File(source, "huge.sqlite"), "rw").use { it.setLength(CopyKnowledgeToLinuxSkill.MAX_BYTES + 1) }
        assertThat(skill.execute(args("/mnt/knowledge/huge.sqlite"), context).status).isEqualTo("error")
        assertThat(File(workspace.root("root-s").physicalRootPath, "inbox/huge.sqlite").exists()).isFalse()
    }
}
