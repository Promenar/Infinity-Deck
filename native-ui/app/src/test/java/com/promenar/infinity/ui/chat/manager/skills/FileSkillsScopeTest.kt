package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.repository.DiffResult
import com.promenar.infinity.domain.repository.DiffHunk
import com.promenar.infinity.domain.repository.DiffLine
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.repository.PatchResult
import com.promenar.infinity.domain.repository.ReadWindowResult
import com.promenar.infinity.domain.repository.WriteResult
import com.promenar.infinity.data.repository.WorkspaceTextErrorCode
import com.promenar.infinity.data.repository.WorkspaceTextPolicyException
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test

class FileSkillsScopeTest {
    private val json = Json
    private val context = object : SkillExecutionContext {
        override val sessionId = "session"
        override val agentId = "agent"
        override val workspacePath = "/workspace"
        override val workspaceRootUuid = "root-a"
    }

    @Test
    fun `every file skill forwards execution context workspace root`() = runTest {
        val operations = mockk<IFileOperationRepository>()
        coEvery { operations.readFileWindow("root-a", "file", any()) } returns
            ReadWindowResult("file", "a.txt", 1, 1, 1, 0, "old", "hash", 1, null, 0)
        coEvery { operations.writeFileAtomic("root-a", "file", "new", "session", "hash") } returns
            WriteResult.Success("new-hash")
        coEvery { operations.diffFile("root-a", "file", null) } returns
            DiffResult("file", "hash", "hash", emptyList())
        coEvery { operations.patchFile("root-a", "file", any(), "hash") } returns
            PatchResult.Success("new-hash", 1)

        FileReadSkill(operations).execute(skillArgs("uuid" to "file"), context)
        FileWriteSkill(operations).execute(
            skillArgs("uuid" to "file", "content" to "new", "expectedHash" to "hash"),
            context,
        )
        FileDiffSkill(operations).execute(skillArgs("uuid" to "file"), context)
        FilePatchSkill(operations).execute(
            skillArgs(
                "uuid" to "file",
                "expectedHash" to "hash",
                "operations" to listOf(
                    mapOf("action" to "replace_lines", "startLine" to 1, "endLine" to 1, "newContent" to "new"),
                ),
            ),
            context,
        )

        coVerify { operations.readFileWindow("root-a", "file", any()) }
        coVerify { operations.writeFileAtomic("root-a", "file", "new", "session", "hash") }
        coVerify { operations.diffFile("root-a", "file", null) }
        coVerify { operations.patchFile("root-a", "file", any(), "hash") }
    }

    @Test
    fun `list and search skills never use another root`() = runTest {
        val workspace = mockk<IWorkspaceRepository>()
        every { workspace.observeChildren("root-a", "root-a") } returns flowOf(emptyList())
        every { workspace.searchByName("root-a", "x") } returns flowOf(emptyList())

        FileListSkill(workspace).execute(skillArgs(), context)
        FileSearchSkill(workspace).execute(skillArgs("query" to "x"), context)

        verify(exactly = 1) { workspace.observeChildren("root-a", "root-a") }
        verify(exactly = 1) { workspace.searchByName("root-a", "x") }
    }

    @Test
    fun `read write diff文本策略拒绝返回稳定错误码与缩小范围建议`() = runTest {
        val operations = mockk<IFileOperationRepository>()
        val failure = WorkspaceTextPolicyException(
            WorkspaceTextErrorCode.INPUT_TOO_LARGE,
            "文件超过上限，请缩小读取范围。",
        )
        coEvery { operations.readFileWindow(any(), any(), any()) } throws failure
        coEvery { operations.writeFileAtomic(any(), any(), any(), any(), any()) } throws failure
        coEvery { operations.diffFile(any(), any(), any()) } throws failure

        val results = listOf(
            FileReadSkill(operations).execute(skillArgs("uuid" to "file"), context),
            FileWriteSkill(operations).execute(
                skillArgs("uuid" to "file", "content" to "new", "expectedHash" to "hash"),
                context,
            ),
            FileDiffSkill(operations).execute(skillArgs("uuid" to "file"), context),
        )

        results.forEach { result ->
            assertThat(result.status).isEqualTo("error")
            assertThat(result.content).contains("缩小")
            assertThat(json.parseToJsonElement(result.data!!).jsonObject["errorCode"]?.jsonPrimitive?.content)
                .isEqualTo("INPUT_TOO_LARGE")
        }
    }

    @Test
    fun `read输出预算包含最终ToolResult的JSON转义开销`() = runTest {
        val operations = mockk<IFileOperationRepository>()
        val shortLines = "123456789012\n".repeat(20_000).trimEnd()
        coEvery { operations.readFileWindow(any(), any(), any()) } returns
            ReadWindowResult("file", "large.txt", 20_000, 1, 20_000, 0, shortLines, "hash", shortLines.length.toLong(), null, 0)

        val result = FileReadSkill(operations).execute(skillArgs("uuid" to "file"), context)

        assertThat(result.status).isEqualTo("error")
        assertThat(json.parseToJsonElement(result.data!!).jsonObject["errorCode"]?.jsonPrimitive?.content)
            .isEqualTo("OUTPUT_TOO_LARGE")
    }

    @Test
    fun `diff输出预算包含每行JSON元数据与转义开销`() = runTest {
        val operations = mockk<IFileOperationRepository>()
        val lines = List(20_000) { DiffLine("context", "") }
        coEvery { operations.diffFile(any(), any(), any()) } returns
            DiffResult("file", "base", "current", listOf(DiffHunk(1, 20_000, 1, 20_000, lines)))

        val result = FileDiffSkill(operations).execute(skillArgs("uuid" to "file"), context)

        assertThat(result.status).isEqualTo("error")
        assertThat(json.parseToJsonElement(result.data!!).jsonObject["errorCode"]?.jsonPrimitive?.content)
            .isEqualTo("OUTPUT_TOO_LARGE")
    }

    @Test
    fun `diff使用标准JSON编码控制字符并可完整round trip`() = runTest {
        val operations = mockk<IFileOperationRepository>()
        val controlText = "prefix\bsuffix\u0001"
        coEvery { operations.diffFile(any(), any(), any()) } returns
            DiffResult("file", "base", "current", listOf(
                DiffHunk(1, 1, 1, 1, listOf(DiffLine("context", controlText))),
            ))

        val result = FileDiffSkill(operations).execute(skillArgs("uuid" to "file"), context)

        val decoded = json.parseToJsonElement(result.content).jsonObject
            .getValue("hunks").jsonArray[0].jsonObject
            .getValue("lines").jsonArray[0].jsonObject
            .getValue("content").jsonPrimitive.content
        assertThat(decoded).isEqualTo(controlText)
    }

    @Test
    fun `write与patch已入队时返回精确目标结构`() = runTest {
        val operations = mockk<IFileOperationRepository>()
        coEvery { operations.writeFileAtomic("root-a", "file", "new", "session", "old-hash") } returns
            WriteResult.Success("write-hash", indexQueued = true, targetEpoch = 101L)
        coEvery { operations.patchFile("root-a", "file", any(), "write-hash") } returns
            PatchResult.Success("patch-hash", 1, indexQueued = true, targetEpoch = 102L)

        val write = FileWriteSkill(operations).execute(
            skillArgs("uuid" to "file", "content" to "new", "expectedHash" to "old-hash"),
            context,
        )
        val patch = FilePatchSkill(operations).execute(
            skillArgs(
                "uuid" to "file",
                "expectedHash" to "write-hash",
                "operations" to listOf(
                    mapOf("action" to "replace_lines", "startLine" to 1, "endLine" to 1, "newContent" to "patched"),
                ),
            ),
            context,
        )

        assertIndexResult(write, "file", "write-hash", 101L, true, "success")
        assertIndexResult(patch, "file", "patch-hash", 102L, true, "success")
        assertThat(write.content).contains("索引已入队")
        assertThat(patch.content).contains("索引已入队")
    }

    @Test
    fun `write与patch索引未入队时返回可补偿的精确目标结构`() = runTest {
        val operations = mockk<IFileOperationRepository>()
        coEvery { operations.writeFileAtomic("root-a", "file", "new", "session", "old-hash") } returns
            WriteResult.Success("write-hash", indexQueued = false, targetEpoch = 201L)
        coEvery { operations.patchFile("root-a", "file", any(), "write-hash") } returns
            PatchResult.Success("patch-hash", 1, indexQueued = false, targetEpoch = 202L)

        val write = FileWriteSkill(operations).execute(
            skillArgs("uuid" to "file", "content" to "new", "expectedHash" to "old-hash"),
            context,
        )
        val patch = FilePatchSkill(operations).execute(
            skillArgs(
                "uuid" to "file",
                "expectedHash" to "write-hash",
                "operations" to listOf(
                    mapOf("action" to "replace_lines", "startLine" to 1, "endLine" to 1, "newContent" to "patched"),
                ),
            ),
            context,
        )

        assertIndexResult(write, "file", "write-hash", 201L, false, "error")
        assertIndexResult(patch, "file", "patch-hash", 202L, false, "error")
        assertThat(write.content).contains("写入成功，但索引尚未入队")
        assertThat(patch.content).contains("补丁应用成功，但索引尚未入队")
        assertRetryContract(write, "write-hash", 201L)
        assertRetryContract(patch, "patch-hash", 202L)
    }

    @Test
    fun `索引未入队且缺少epoch时拒绝伪报完整成功`() = runTest {
        val operations = mockk<IFileOperationRepository>()
        coEvery { operations.writeFileAtomic("root-a", "file", "new", "session", "old-hash") } returns
            WriteResult.Success("write-hash", indexQueued = false, targetEpoch = null)
        coEvery { operations.patchFile("root-a", "file", any(), "write-hash") } returns
            PatchResult.Success("patch-hash", 1, indexQueued = false, targetEpoch = null)

        val write = FileWriteSkill(operations).execute(
            skillArgs("uuid" to "file", "content" to "new", "expectedHash" to "old-hash"),
            context,
        )
        val patch = FilePatchSkill(operations).execute(
            skillArgs(
                "uuid" to "file",
                "expectedHash" to "write-hash",
                "operations" to listOf(
                    mapOf("action" to "replace_lines", "startLine" to 1, "endLine" to 1, "newContent" to "patched"),
                ),
            ),
            context,
        )

        listOf(write, patch).forEach { result ->
            assertThat(result.status).isEqualTo("error")
            assertThat(result.content).contains("缺少 targetEpoch")
            val data = json.parseToJsonElement(requireNotNull(result.data)).jsonObject
            assertThat(data.getValue("targetEpoch")).isEqualTo(JsonNull)
            assertThat(data.getValue("indexQueued").jsonPrimitive.boolean).isFalse()
            assertThat(data).doesNotContainKey("indexRetry")
        }
    }

    private fun assertIndexResult(
        result: com.promenar.infinity.data.model.ToolResult,
        fileUuid: String,
        contentHash: String,
        targetEpoch: Long,
        indexQueued: Boolean,
        status: String,
    ) {
        assertThat(result.status).isEqualTo(status)
        val data = json.parseToJsonElement(requireNotNull(result.data)).jsonObject
        assertThat(data.getValue("fileUuid").jsonPrimitive.content).isEqualTo(fileUuid)
        assertThat(data.getValue("contentHash").jsonPrimitive.content).isEqualTo(contentHash)
        assertThat(data.getValue("targetEpoch").jsonPrimitive.long).isEqualTo(targetEpoch)
        assertThat(data.getValue("indexQueued").jsonPrimitive.boolean).isEqualTo(indexQueued)
    }

    private fun assertRetryContract(
        result: com.promenar.infinity.data.model.ToolResult,
        contentHash: String,
        targetEpoch: Long,
    ) {
        val retry = json.parseToJsonElement(requireNotNull(result.data))
            .jsonObject
            .getValue("indexRetry")
            .jsonObject
        assertThat(retry.getValue("workspaceRootUuid").jsonPrimitive.content).isEqualTo("root-a")
        assertThat(retry.getValue("fileUuid").jsonPrimitive.content).isEqualTo("file")
        assertThat(retry.getValue("contentHash").jsonPrimitive.content).isEqualTo(contentHash)
        assertThat(retry.getValue("targetEpoch").jsonPrimitive.long).isEqualTo(targetEpoch)
        val steps = retry.getValue("steps").jsonArray
        assertThat(steps.map { it.jsonObject.getValue("action").jsonPrimitive.content })
            .containsExactly("read_file", "write_file")
            .inOrder()
        val readArgs = steps[0].jsonObject.getValue("arguments").jsonObject
        assertThat(readArgs.getValue("uuid").jsonPrimitive.content).isEqualTo("file")
        val writeArgs = steps[1].jsonObject.getValue("arguments").jsonObject
        assertThat(writeArgs.getValue("uuid").jsonPrimitive.content).isEqualTo("file")
        assertThat(writeArgs.getValue("content").jsonPrimitive.content).isEqualTo("{{read_file.content}}")
        assertThat(writeArgs.getValue("expectedHash").jsonPrimitive.content).isEqualTo(contentHash)
        assertThat(requireNotNull(result.data)).doesNotContain("publish_file_index_changed")
    }
}
