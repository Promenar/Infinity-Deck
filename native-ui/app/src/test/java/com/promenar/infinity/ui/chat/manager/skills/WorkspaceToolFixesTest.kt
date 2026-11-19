package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.repository.WorkspaceLifecycleErrorCode
import com.promenar.infinity.data.repository.WorkspaceLifecycleException
import com.promenar.infinity.domain.repository.*
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import io.mockk.*
import java.nio.file.FileAlreadyExistsException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test

class WorkspaceToolFixesTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "session"
        override val agentId = "agent"
        override val workspaceRootUuid = "root"
        override val workspacePath: String? = null
    }
    private val workspace = mockk<IWorkspaceRepository>()
    private val files = mockk<IFileOperationRepository>()
    private val mounts = mockk<ExternalWorkspaceAccess> {
        coEvery { workspaceMount(any()) } returns null
        coEvery { visible(any()) } returns emptyList()
    }
    private fun entry(uuid: String, path: String, directory: Boolean = false, parent: String = "root") = FileEntry(
        uuid, "root", parent, path.substringAfterLast('/'), "hash", isDirectory = directory,
        physicalRootPath = "/private/hidden", materializedPath = path, createdAt = 1, updatedAt = 1,
    )
    private fun args(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun wrapped(skill: SkillDefinition) = MountedWorkspaceSkill(skill, mounts, workspace, files, FileIndexEventSink.None, captureFileChanges = false)

    @Test fun `搜索隔离坏挂载并严格校验显式起点`() = runTest {
        val root = "/mnt"
        val bad = "$root/bad"
        val good = "$root/good"
        val hit = "$good/note.md"
        fun directory(path: String) = ExternalFolderEntry(path, path.substringAfterLast('/'), true, 0, 1)
        coEvery { mounts.list("session", root) } returns listOf(directory(bad), directory(good))
        coEvery { mounts.list("session", bad) } throws ExternalFolderException("ACCESS_DENIED", "授权已撤回")
        coEvery { mounts.list("session", good) } returns listOf(ExternalFolderEntry(hit, "note.md", false, 0, 1))
        coEvery { mounts.entry("session", root) } returns entry("external:mnt", root, true)
        coEvery { mounts.entry("session", bad) } throws ExternalFolderException("ACCESS_DENIED", "授权已撤回")
        coEvery { mounts.entry("session", good) } returns entry("external:good", good, true)
        coEvery { mounts.entry("session", hit) } returns entry("external:hit", hit)
        val search = wrapped(FileSearchSkill(workspace, files))
        val result = search.execute(args("""{"query":"note","path":"/mnt"}"""), context)
        assertThat(result.content).contains(hit)
        assertThat(result.content).contains("跳过 1")
        assertThat(result.content).contains("ACCESS_DENIED")
        val denied = search.execute(args("""{"query":"note","path":"$bad"}"""), context)
        assertThat(denied.status).isEqualTo("error")
        assertThat(denied.content).contains("ACCESS_DENIED")
    }

    @Test fun `Linux 默认名称搜索只扫描工作区专用空间与挂载`() = runTest {
        coEvery { mounts.list("session", "/workspace") } returns listOf(ExternalFolderEntry("/workspace/note.md", "note.md", false, 0, 1))
        coEvery { mounts.list("session", "/home/session") } returns emptyList()
        coEvery { mounts.list("session", "/mnt") } returns emptyList()
        coEvery { mounts.entry("session", "/workspace/note.md") } returns entry("external:note", "/workspace/note.md")
        val repository = MountedWorkspaceRepository(workspace, mounts, "session", rooted = true, linuxPaths = true)
        val result = FileSearchSkill(repository, files).execute(args("""{"query":"note"}"""), context)
        assertThat(result.content).contains("/workspace/note.md")
        coVerify(exactly = 0) { mounts.list(any(), "/") }
    }

    @Test fun `内容搜索跳过损坏子目录但保留正常命中并原样抛出取消`() = runTest {
        val bad = entry("bad", "/bad", true)
        val good = entry("good", "/good", true)
        val hit = entry("hit", "/good/note.md")
        every { workspace.observeChildren("root", "root") } returns kotlinx.coroutines.flow.flowOf(listOf(bad, good))
        every { workspace.observeChildren("root", "bad") } throws ExternalFolderException("ACCESS_DENIED", "授权已撤回")
        every { workspace.observeChildren("root", "good") } returns kotlinx.coroutines.flow.flowOf(listOf(hit))
        coEvery { files.readFileRange("root", "hit", any(), any()) } returns ReadResult("hit", "note.md", 1, 1, 1, "needle", "hash", 1)
        val skill = FileSearchSkill(workspace, files)
        val args = args("""{"query":"needle","mode":"content"}""")
        val result = skill.execute(args, context)
        assertThat(result.content).contains("/good/note.md:1")
        assertThat(result.content).contains("ACCESS_DENIED")
        val cancellation = kotlinx.coroutines.CancellationException("停止检索")
        every { workspace.observeChildren("root", "bad") } throws cancellation
        assertThat(runCatching { skill.execute(args, context) }.exceptionOrNull()).isSameInstanceAs(cancellation)
    }

    @Test fun `挂载列表保留条目查询失败旁的正常兄弟目录`() = runTest {
        val good = "/mnt/skills"
        val bad = "/mnt/knowledge"
        val hit = "$good/readme.md"
        fun folder(path: String) = ExternalFolderEntry(path, path.substringAfterLast('/'), true, 0, 1)
        coEvery { mounts.entry("session", "/mnt") } returns entry("external:mounts", "/mnt", true)
        coEvery { mounts.resolveUuid("session", "external:mounts") } returns "/mnt"
        coEvery { mounts.resolveUuid("session", "external:skills") } returns good
        coEvery { mounts.list("session", "/mnt") } returns listOf(folder(bad), folder(good))
        coEvery { mounts.entry("session", bad) } throws ExternalFolderException("INVALID_PATH", "不允许通过符号链接访问目录（触发段：knowledge）")
        coEvery { mounts.entry("session", good) } returns entry("external:skills", good, true)
        coEvery { mounts.list("session", good) } returns listOf(ExternalFolderEntry(hit, "readme.md", false, 0, 1))
        coEvery { mounts.entry("session", hit) } returns entry("external:readme", hit)
        val result = wrapped(FileListSkill(workspace)).execute(args("""{"path":"/mnt","depth":2}"""), context)
        assertThat(result.status).isEqualTo("success")
        assertThat(result.content).contains(hit)
        assertThat(result.content).contains("INVALID_PATH")
        assertThat(result.content).contains("触发段：knowledge")
    }

    @Test fun `stat 对大二进制文件流式散列并保留只读权限`() = runTest {
        val path = "/mnt/audio/recording.wav"
        val bytes = ByteArray(1024 * 1024 + 37) { it.toByte() }
        val hash = com.promenar.infinity.infra.util.Sha256Utils.hash(bytes)
        val mount = ExternalMount("audio", "audio", "real_path", "", "session", 1, readOnly = true)
        coEvery { mounts.visible("session") } returns listOf(mount)
        coEvery { mounts.entry("session", path) } returns entry("external:audio:file", path)
            .copy(hash = "", sizeBytes = bytes.size.toLong())
        coEvery { mounts.readStream<String>("session", path, any(), any()) } coAnswers {
            arg<suspend (java.io.InputStream) -> String>(3)(bytes.inputStream())
        }
        val result = wrapped(FileReadSkill(files, workspace)).execute(args("""{"path":"$path","mode":"stat"}"""), context)
        assertThat(result.status).isEqualTo("success")
        assertThat(result.content).contains(bytes.size.toString())
        assertThat(result.content).contains(hash)
        assertThat(result.content).contains("只读")
        coVerify(exactly = 0) { files.readFileWindow(any(), any(), any()) }
    }

    @Test fun `两个写工具在私有与挂载路径给出相同的结构化版本缺参指引`() = runTest {
        for (path in listOf("/a.txt", "/mnt/test/a.txt")) {
            coEvery { workspace.getByMaterializedPath("root", path) } returns entry("a", path)
            coEvery { mounts.entry(any(), path) } returns entry("external:a", path)
            for (skill in listOf(FileWriteSkill(files, workspace), FilePatchSkill(files, workspace))) {
                val result = wrapped(skill).execute(args("""{"path":"$path","content":"new","operations":[]}"""), context)
                assertThat(result.status).isEqualTo("error")
                val error = Json.parseToJsonElement(result.content).jsonObject
                assertThat(error["code"]?.jsonPrimitive?.content).isEqualTo("MISSING_VERSION")
                assertThat(error["message"]?.jsonPrimitive?.content).contains("expectedHash 或 expected_sha256")
                assertThat(error["source_tool"]?.jsonPrimitive?.content).isEqualTo("read_file")
                assertThat(error["source_fields"]?.jsonArray?.map { it.jsonPrimitive.content }).containsExactly("Hash", "sha256")
            }
        }
        coVerify(exactly = 0) { files.writeFileAtomic(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { files.patchFile(any(), any(), any(), any()) }
    }

    @Test fun `写工具描述与两个版本参数均明确调用方必填契约`() {
        for (skill in listOf(FileWriteSkill(files, workspace), FilePatchSkill(files, workspace)).map(::wrapped)) {
            assertThat(skill.description).contains("必须提供 expectedHash 或 expected_sha256 之一")
            val properties = Json.parseToJsonElement(skill.parametersSchema).jsonObject.getValue("properties").jsonObject
            for (name in listOf("expectedHash", "expected_sha256")) {
                assertThat(properties.getValue(name).jsonObject.getValue("description").jsonPrimitive.content).contains("read_file")
            }
        }
    }

    @Test fun `别名版本显式透传且冲突版本不能执行写入`() = runTest {
        coEvery { workspace.getByMaterializedPath("root", "/a.txt") } returns entry("a", "/a.txt")
        coEvery { files.writeFileAtomic("root", "a", "new", "session", "caller-hash") } returns WriteResult.Success("new-hash")
        val skill = wrapped(FileWriteSkill(files, workspace))
        assertThat(skill.execute(args("""{"path":"/a.txt","content":"new","expected_sha256":"caller-hash"}"""), context).status).isEqualTo("success")
        assertThat(skill.execute(args("""{"path":"/a.txt","content":"new","expectedHash":"one","expected_sha256":"two"}"""), context).status).isEqualTo("error")
        coVerify(exactly = 1) { files.writeFileAtomic(any(), any(), any(), any(), any()) }
    }

    @Test fun `同目录改名和无改名不调用父目录变更`() = runTest {
        val source = entry("a", "/probe/a.txt", parent = "probe")
        coEvery { workspace.getByMaterializedPath("root", "/probe/a.txt") } returns source
        coEvery { workspace.getByMaterializedPath("root", "/probe") } returns entry("probe", "/probe", true)
        coEvery { workspace.getByUuid("root", "a") } returns source
        coEvery { workspace.updateParent(any(), any(), any()) } throws FileAlreadyExistsException("/private/hidden/a.txt")
        coEvery { workspace.rename("root", "a", "b.txt", "a.txt") } returns RenameResult.Success("b.txt", "hash", 2, true)
        val skill = MoveFileSkill(workspace, FileIndexEventSink.None)
        for (name in listOf("", ",\"newName\":\"a.txt\"", ",\"newName\":\"b.txt\"")) {
            assertThat(skill.execute(args("""{"path":"/probe/a.txt","newParentPath":"/probe"$name}"""), context).status).isEqualTo("success")
        }
        coVerify(exactly = 0) { workspace.updateParent(any(), any(), any()) }
        coVerify(exactly = 1) { workspace.rename(any(), any(), any(), any()) }
    }

    @Test fun `私有工作区同目录无操作不发布虚假文件变更摘要`() = runTest {
        val source = entry("a", "/probe/a.txt", parent = "probe")
        coEvery { workspace.getByMaterializedPath("root", "/probe/a.txt") } returns source
        coEvery { workspace.getByMaterializedPath("root", "/probe") } returns entry("probe", "/probe", true)
        coEvery { workspace.getByUuid("root", "a") } returns source
        val skill = MountedWorkspaceSkill(MoveFileSkill(workspace, FileIndexEventSink.None), mounts, workspace, files, FileIndexEventSink.None)
        val result = skill.execute(args("""{"path":"/probe/a.txt","newParentPath":"/probe"}"""), context)
        assertThat(result.status).isEqualTo("success")
        assertThat(result.fileChanges.orEmpty()).isEmpty()
        coVerify(exactly = 0) { workspace.updateParent(any(), any(), any()) }
        coVerify(exactly = 0) { workspace.rename(any(), any(), any(), any()) }
    }

    @Test fun `跨目录移动后改名且具体文件异常不回显物理路径`() = runTest {
        val source = entry("a", "/a.txt")
        coEvery { workspace.getByMaterializedPath("root", "/a.txt") } returns source
        coEvery { workspace.getByMaterializedPath("root", "/docs") } returns entry("docs", "/docs", true)
        coEvery { workspace.getByUuid("root", "a") } returns source.copy(name = "b.txt", materializedPath = "/docs/b.txt")
        coEvery { workspace.updateParent("root", "a", "docs") } returns Unit
        coEvery { workspace.rename("root", "a", "b.txt", "a.txt") } returns RenameResult.Success("b.txt", "hash", 2, true)
        val skill = MoveFileSkill(workspace, FileIndexEventSink.None)
        val arguments = args("""{"path":"/a.txt","newParentPath":"/docs","newName":"b.txt"}""")
        assertThat(skill.execute(arguments, context).content).contains("/docs/b.txt")
        for ((failure, reason) in listOf(
            FileAlreadyExistsException("/private/hidden/a.txt") to "目标已存在",
            NoSuchFileException("/private/hidden/a.txt") to "条目不存在",
            AccessDeniedException("/private/hidden/a.txt") to "权限不足",
            IllegalStateException("/private/hidden state") to "状态冲突",
            WorkspaceLifecycleException(WorkspaceLifecycleErrorCode.TARGET_COLLISION, "目标路径已被占用，未执行移动。") to "目标路径已被占用，未执行移动。",
            UnsupportedOperationException("/private/hidden unknown") to "UnsupportedOperationException",
        )) {
            coEvery { workspace.updateParent("root", "a", "docs") } throws failure
            val result = skill.execute(arguments, context)
            assertThat(result.status).isEqualTo("error")
            assertThat(result.content).contains(reason)
            assertThat(result.content).doesNotContain("/private/hidden")
        }
    }
}
