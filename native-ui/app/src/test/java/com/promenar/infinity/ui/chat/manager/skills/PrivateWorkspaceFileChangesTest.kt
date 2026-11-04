package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.model.FileChangeHunkOmission
import com.promenar.infinity.data.model.FileChangeType
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.repository.PatchResult
import com.promenar.infinity.domain.repository.WriteResult
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import com.promenar.infinity.infra.util.Sha256Utils
import com.promenar.infinity.ui.chat.diff.ApprovalDiffPreview
import com.promenar.infinity.ui.chat.diff.ApprovalDiffPreviewLoader
import com.promenar.infinity.ui.chat.diff.PrivateWorkspaceReader
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Test

/** 会话私有工作区（Room 树）上的改动摘要采集与审批差异预览：读写前后内容都来自物理根。 */
class PrivateWorkspaceFileChangesTest {
    private val rootDir = Files.createTempDirectory("private-changes").toFile().canonicalFile
    private val root = FileEntry(
        uuid = "root", workspaceRootUuid = "root", parentUuid = null, name = "", hash = "root-identity",
        isDirectory = true, physicalRootPath = rootDir.path, materializedPath = "/", createdAt = 1, updatedAt = 1,
    )

    private fun entryAt(path: String): FileEntry? {
        if (path == "/") return root
        val file = File(rootDir, path.trimStart('/'))
        if (!file.exists()) return null
        return FileEntry(
            uuid = "uuid:$path", workspaceRootUuid = "root", parentUuid = "root", name = file.name,
            hash = if (file.isDirectory) "" else Sha256Utils.hash(file.readBytes()), sizeBytes = file.length(),
            isDirectory = file.isDirectory, physicalRootPath = rootDir.path, materializedPath = path, createdAt = 1, updatedAt = 1,
        )
    }

    private val workspace = mockk<IWorkspaceRepository> {
        coEvery { getByMaterializedPath("root", any()) } answers { entryAt(secondArg()) }
        coEvery { getByUuid("root", any()) } answers { val uuid = secondArg<String>(); if (uuid == "root") root else entryAt(uuid.removePrefix("uuid:")) }
        coEvery { createFileInWorkspace("root", any(), any(), "", "root", any()) } answers {
            val path = arg<String>(5)
            File(rootDir, path.trimStart('/')).writeText("")
            entryAt(path)!!
        }
        coEvery { permanentDelete("root", any()) } answers {
            File(rootDir, secondArg<String>().removePrefix("uuid:").trimStart('/')).delete()
            Unit
        }
    }
    private val files = mockk<IFileOperationRepository> {
        coEvery { writeFileAtomic("root", any(), any(), "session", any()) } answers {
            val path = secondArg<String>().removePrefix("uuid:").let { if (it.startsWith("/")) it else null }
            val target = path?.let { File(rootDir, it.trimStart('/')) }
                ?: rootDir.listFiles()!!.single { it.length() == 0L }
            target.writeText(thirdArg())
            WriteResult.Success(Sha256Utils.hash(target.readBytes()))
        }
        coEvery { patchFile("root", any(), any(), any()) } answers {
            val target = File(rootDir, secondArg<String>().removePrefix("uuid:").trimStart('/'))
            target.writeText(target.readText() + "appended\n")
            PatchResult.Success(Sha256Utils.hash(target.readBytes()), 1)
        }
    }
    private val mounts = mockk<ExternalWorkspaceAccess> {
        coEvery { workspaceMount("session") } returns null
        coEvery { visible("session") } returns emptyList()
    }
    private val execution = object : SkillExecutionContext {
        override val sessionId = "session"
        override val agentId = "agent"
        override val workspaceRootUuid = "root"
        override val workspacePath: String? = null
    }

    // 生产实现经 SecureDirectoryStream 读取物理根（macOS JVM 不支持），测试替身按根条目定位后直接读文件。
    private val privateReader = PrivateWorkspaceReader { repository, rootUuid, path, maxBytes ->
        val rootEntry = repository.getByUuid(rootUuid, rootUuid)!!
        val bytes = File(rootEntry.physicalRootPath, path.trimStart('/')).readBytes()
        check(bytes.size <= maxBytes)
        bytes
    }

    @After fun cleanup() { rootDir.deleteRecursively() }

    private fun skill(original: SkillDefinition) = MountedWorkspaceSkill(original, mounts, workspace, files, FileIndexEventSink.None, privateReader = privateReader)
    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test fun `私有工作区写入后附带改动摘要且模型文本不变`() = runBlocking<Unit> {
        File(rootDir, "a.txt").writeText("alpha\nbeta\n")
        val hash = Sha256Utils.hash(File(rootDir, "a.txt").readBytes())

        val result = skill(FileWriteSkill(files, workspace)).execute(args("""{"path":"/a.txt","content":"alpha\nBETA\n","expectedHash":"$hash"}"""), execution)

        assertThat(result.status).isEqualTo("success")
        assertThat(result.content).startsWith("写入成功，索引已入队。新 Hash: ")
        assertThat(result.content).doesNotContain("BETA")
        val change = result.fileChanges!!.single()
        assertThat(change.path).isEqualTo("/a.txt")
        assertThat(change.change).isEqualTo(FileChangeType.MODIFIED)
        assertThat(change.added to change.removed).isEqualTo(1 to 1)
        assertThat(change.hunks!!.single().lines).containsExactly(" alpha", "-beta", "+BETA").inOrder()
    }

    @Test fun `写入前快照与乐观锁哈希不一致时不给计数`() = runBlocking<Unit> {
        File(rootDir, "a.txt").writeText("alpha\n")

        val result = skill(FileWriteSkill(files, workspace)).execute(args("""{"path":"/a.txt","content":"x\n","expectedHash":"stale"}"""), execution)

        // 测试替身不校验哈希，写入照常成功；采集侧发现快照不是被覆盖的版本，只保留路径与原因。
        val change = result.fileChanges!!.single()
        assertThat(change.added).isNull()
        assertThat(change.hunksOmitted).isEqualTo(FileChangeHunkOmission.UNAVAILABLE)
    }

    @Test fun `私有工作区新建补丁与删除`() = runBlocking<Unit> {
        val created = skill(CreateFileSkill(workspace, files)).execute(args("""{"name":"n.md","content":"# 标题\n正文\n"}"""), execution)
        assertThat(created.status).isEqualTo("success")
        assertThat(created.fileChanges!!.single().path).isEqualTo("/n.md")
        assertThat(created.fileChanges!!.single().added).isEqualTo(2)

        val hash = Sha256Utils.hash(File(rootDir, "n.md").readBytes())
        val patched = skill(FilePatchSkill(files, workspace)).execute(
            args("""{"path":"/n.md","expectedHash":"$hash","operations":[{"action":"insert_after","afterLine":2,"newContent":"appended"}]}"""),
            execution,
        )
        assertThat(patched.status).isEqualTo("success")
        assertThat(patched.fileChanges!!.single().added).isEqualTo(1)
        assertThat(patched.fileChanges!!.single().removed).isEqualTo(0)

        val deleted = skill(DeleteFileSkill(workspace)).execute(args("""{"path":"/n.md"}"""), execution)
        assertThat(deleted.content).isEqualTo("已永久删除：/n.md")
        assertThat(deleted.fileChanges!!.single().change).isEqualTo(FileChangeType.DELETED)
        assertThat(deleted.fileChanges!!.single().removed).isEqualTo(3)
        assertThat(deleted.fileChanges!!.single().hunks).isNull()
    }

    @Test fun `脚本网关关闭采集时不附带改动摘要`() = runBlocking<Unit> {
        File(rootDir, "a.txt").writeText("alpha\n")
        val hash = Sha256Utils.hash(File(rootDir, "a.txt").readBytes())
        val quiet = MountedWorkspaceSkill(FileWriteSkill(files, workspace), mounts, workspace, files, FileIndexEventSink.None, captureFileChanges = false)

        val result = quiet.execute(args("""{"path":"/a.txt","content":"beta\n","expectedHash":"$hash"}"""), execution)

        assertThat(result.status).isEqualTo("success")
        assertThat(result.fileChanges).isNull()
    }

    @Test fun `审批差异预览读取私有工作区当前内容`() = runBlocking<Unit> {
        File(rootDir, "a.txt").writeText("one\ntwo\nthree\n")
        val loader = ApprovalDiffPreviewLoader(workspace, mounts, privateReader = privateReader)

        val write = loader.load("write_file", args("""{"path":"/a.txt","content":"one\n2\nthree\n"}"""), "session", "root") as ApprovalDiffPreview.Diff
        assertThat(write.model.countsLabel).isEqualTo("+1 −1")
        val byUuid = loader.load("write_file", args("""{"uuid":"uuid:/a.txt","content":"one\n"}"""), "session", "root") as ApprovalDiffPreview.Diff
        assertThat(byUuid.model.countsLabel).isEqualTo("+0 −2")
        assertThat(loader.load("delete_file", args("""{"path":"/a.txt"}"""), "session", "root")).isEqualTo(ApprovalDiffPreview.Deletion(3))
        // 缺少私有工作区根时无法定位，回退到文本预览。
        assertThat(loader.load("write_file", args("""{"path":"/a.txt","content":"x"}"""), "session", null)).isNull()
        assertThat(File(rootDir, "a.txt").readText()).isEqualTo("one\ntwo\nthree\n")
    }
}
