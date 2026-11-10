package com.promenar.infinity.data.storage

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import com.promenar.infinity.infra.util.Sha256Utils
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.FileReadSkill
import com.promenar.infinity.ui.chat.manager.skills.MountedWorkspaceSkill
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.io.InputStream

class MountedWorkspaceVersionTest {
    @Test fun `读取后来源改变不能把新版本配给旧正文`() = runBlocking {
        val oldBytes = "previous".toByteArray()
        val path = "/mnt/termux/a.txt"
        val uuid = "external:id:L2EudHh0"
        val newer = FileEntry(uuid, "root", null, "a.txt", Sha256Utils.hash("newer"), physicalRootPath = "", materializedPath = path, createdAt = 1, updatedAt = 2)
        val mounts = mockk<ExternalWorkspaceAccess> {
            coEvery { workspaceMount(any()) } returns null
            coEvery { visible("session") } returns emptyList()
            coEvery { entry("session", path) } returns newer
            coEvery { resolveUuid("session", uuid) } returns path
            coEvery { read("session", path, any()) } returns oldBytes
        }
        val workspace = mockk<IWorkspaceRepository>()
        val operations = mockk<IFileOperationRepository>()
        val context = object : SkillExecutionContext {
            override val sessionId = "session"
            override val agentId = "agent"
            override val workspaceRootUuid = "root"
            override val workspacePath: String? = null
        }
        val result = MountedWorkspaceSkill(FileReadSkill(operations, workspace), mounts, workspace, operations, FileIndexEventSink.None)
            .execute(Json.parseToJsonElement("""{"path":"$path"}""").jsonObject, context)
        assertThat(result.content).contains("previous")
        assertThat(result.content.substringAfterLast("sha256: ")).isEqualTo(Sha256Utils.hash(oldBytes))
    }

    @Test fun `挂载目录超过1MiB的文件流式分段读取且sha256覆盖全文`() = runBlocking {
        val lines = List(50_000) { "row-${it + 1} " + "q".repeat(30) }
        val bytes = lines.joinToString("\r\n").toByteArray()
        val path = "/mnt/saf/big.log"
        val uuid = "external:id:YmlnLmxvZw"
        val entry = FileEntry(uuid, "root", null, "big.log", "", sizeBytes = bytes.size.toLong(), physicalRootPath = "", materializedPath = path, createdAt = 1, updatedAt = 2)
        val mounts = mockk<ExternalWorkspaceAccess> {
            coEvery { workspaceMount(any()) } returns null
            coEvery { visible("session") } returns emptyList()
            coEvery { entry("session", path) } returns entry
            coEvery { resolveUuid("session", uuid) } returns path
            coEvery { readStream("session", path, any(), any<suspend (InputStream) -> Any?>()) } coAnswers {
                arg<suspend (InputStream) -> Any?>(3).invoke(bytes.inputStream())
            }
        }
        val workspace = mockk<IWorkspaceRepository>()
        val operations = mockk<IFileOperationRepository>()
        val context = object : SkillExecutionContext {
            override val sessionId = "session"
            override val agentId = "agent"
            override val workspaceRootUuid = "root"
            override val workspacePath: String? = null
        }
        val result = MountedWorkspaceSkill(FileReadSkill(operations, workspace), mounts, workspace, operations, FileIndexEventSink.None)
            .execute(Json.parseToJsonElement("""{"path":"$path","mode":"range","startLine":30000,"endLine":30001}""").jsonObject, context)

        assertThat(bytes.size).isGreaterThan(1024 * 1024)
        assertThat(result.status).isNotEqualTo("error")
        assertThat(result.content).contains("行数: 50000 (返回 30000-30001)")
        assertThat(result.content).contains("30000| ${lines[29_999]}\n30001| ${lines[30_000]}")
        assertThat(result.content).contains("后续: mode=range startLine=30002")
        assertThat(result.content.substringAfterLast("sha256: ")).isEqualTo(Sha256Utils.hash(bytes))
        coVerify(exactly = 0) { mounts.read(any(), any(), any()) }
    }
}
