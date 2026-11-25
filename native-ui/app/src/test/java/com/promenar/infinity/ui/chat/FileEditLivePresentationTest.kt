package com.promenar.infinity.ui.chat

import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.FileChangeSummary
import com.promenar.infinity.data.model.FileChangeType
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.ToolCall
import com.promenar.infinity.ui.common.motion.GenerationMotionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileEditLivePresentationTest {

    @Test
    fun `parseLiveFileEditCall waits for closed filename then streams live patch counts`() {
        // 半截未闭合 path 不提前显示，避免文件名逐字抖动。
        assertNull(parseLiveFileEditCall("patch_file", """{"path":"ui/chat/ChatView"""))

        val stage1 = parseLiveFileEditCall(
            "patch_file",
            """{"path":"ui/chat/ChatViewModel.kt","operations":[{"action":"replace_lines","startLine":10,"endLine":13,"newContent":"line1\nline2""",
        )
        assertNotNull(stage1)
        assertEquals("ChatViewModel.kt", stage1?.displayName)
        assertEquals(2, stage1?.added)
        assertEquals(4, stage1?.removed)

        val stage2 = parseLiveFileEditCall(
            "patch_file",
            """{"path":"ui/chat/ChatViewModel.kt","operations":[{"action":"replace_lines","startLine":10,"endLine":13,"newContent":"line1\nline2\nline3\nline4\nline5"},{"action":"delete_lines","startLine":20,"endLine":21}""",
        )
        assertEquals(5, stage2?.added)
        assertEquals(6, stage2?.removed)
    }

    @Test
    fun `parseLiveFileEditCall parses streaming write_file and create_file with escaped newlines`() {
        val writeEst = parseLiveFileEditCall(
            "write_file",
            """{"path":"/src/App.kt","content":"val a = \"hi\"\nval b = 2\nval c = 3\n""",
        )
        assertEquals("App.kt", writeEst?.displayName)
        assertEquals(3, writeEst?.added)
        assertNull(writeEst?.removed)

        assertNull(parseLiveFileEditCall("create_file", """{"parentPath":"/docs","name":"Readm"""))
        val createEst = parseLiveFileEditCall(
            "create_file",
            """{"parentPath":"/docs","name":"Readme.md","content":"# Title\nbody""",
        )
        assertEquals("/docs/Readme.md", createEst?.path)
        assertEquals("Readme.md", createEst?.displayName)
        assertEquals(2, createEst?.added)
        assertNull(createEst?.removed)
    }

    @Test
    fun `parseLiveFileEditCall resolves uuid from previous read_file or list_files output`() {
        val messages = listOf(
            Message(
                id = "tool-1",
                role = MessageRole.TOOL,
                content = "文件: ComfySettingsScreens.kt [uuid=u-123]\n[FILE] /ui/chat/ChatScreen.kt (12 KB) [uuid=u-456]",
            ),
        )
        val known = extractKnownUuidPaths(messages)
        val patchByUuid = parseLiveFileEditCall(
            "patch_file",
            """{"uuid":"u-456","operations":[{"action":"insert_after","afterLine":12,"newContent":"a\nb"}]}""",
            knownUuidPaths = known,
        )

        assertEquals("/ui/chat/ChatScreen.kt", patchByUuid?.path)
        assertEquals("ChatScreen.kt", patchByUuid?.displayName)
        assertEquals(2, patchByUuid?.added)
        assertNull(patchByUuid?.removed)
        assertNull(parseLiveFileEditCall("read_file", """{"path":"/a.kt"}"""))
        assertNull(parseLiveFileEditCall("diff_file", """{"path":"/a.kt"}"""))
    }

    @Test
    fun `buildLiveFileEditBatch lists sequential files row-by-row and accumulates same-file re-edits in place`() {
        val inv1 = completedEditInvocation(
            id = "1",
            path = "ui/chat/ChatViewModel.kt",
            added = 18,
            removed = 4,
            timestamp = 1_000L,
        )
        val inv2 = completedEditInvocation(
            id = "2",
            path = "ui/chat/ChatScreen.kt",
            added = 24,
            removed = 7,
            timestamp = 2_000L,
        )
        // 对第一个文件再次发起 patch_file 流式编辑，应留在首行原地累加且恢复 ACTIVE。
        val streamingReEdit = ToolCall(
            id = "tc-3",
            name = "patch_file",
            arguments = """{"path":"ui/chat/ChatViewModel.kt","operations":[{"action":"replace_lines","startLine":30,"endLine":32,"newContent":"x\ny\nz\nw"}]}""",
        )

        val batch = buildLiveFileEditBatch(
            invocations = listOf(inv1, inv2),
            streamingCalls = listOf(streamingReEdit),
            knownUuidPaths = emptyMap(),
            isTurnGenerating = true,
            isLastStepInTurn = true,
            hasPendingApproval = false,
            liveStep = LiveStep(LiveStepKind.PREPARING_TOOL, GenerationMotionPhase.STREAM, startedAt = 3_000L),
        )

        assertNotNull(batch)
        assertTrue(batch!!.isLiveBatch)
        assertTrue(batch.hasActiveRow)
        assertTrue(batch.isPureFileEditStep)
        assertEquals(2, batch.rows.size)
        assertEquals("ChatViewModel.kt", batch.rows[0].displayName)
        assertEquals(LiveFileEditRowStatus.ACTIVE, batch.rows[0].status)
        assertEquals(22, batch.rows[0].added)
        assertEquals(7, batch.rows[0].removed)
        assertEquals("ChatScreen.kt", batch.rows[1].displayName)
        assertEquals(LiveFileEditRowStatus.COMPLETED, batch.rows[1].status)
        assertEquals(24, batch.rows[1].added)
        assertEquals(7, batch.rows[1].removed)
    }

    @Test
    fun `buildLiveFileEditBatch collapses automatically once turn finishes or moves past edit batch`() {
        val invocations = listOf(
            completedEditInvocation("1", "a.kt", added = 10, removed = 2, timestamp = 1_000L),
            completedEditInvocation("2", "b.kt", added = 5, removed = 1, timestamp = 2_000L),
        )

        val afterTurnEnded = buildLiveFileEditBatch(
            invocations = invocations,
            streamingCalls = emptyList(),
            knownUuidPaths = emptyMap(),
            isTurnGenerating = false,
            isLastStepInTurn = true,
            hasPendingApproval = false,
            liveStep = null,
        )
        assertNotNull(afterTurnEnded)
        assertFalse(afterTurnEnded!!.isLiveBatch)

        val afterContentStarted = buildLiveFileEditBatch(
            invocations = invocations,
            streamingCalls = emptyList(),
            knownUuidPaths = emptyMap(),
            isTurnGenerating = true,
            isLastStepInTurn = false,
            hasPendingApproval = false,
            liveStep = LiveStep(LiveStepKind.CONTENT, GenerationMotionPhase.STREAM, targetId = "ai-2"),
        )
        assertFalse(afterContentStarted!!.isLiveBatch)

        val whenNonEditStreams = buildLiveFileEditBatch(
            invocations = invocations,
            streamingCalls = listOf(ToolCall("tc-shell", "shell_exec", """{"command":"./gradlew test"}""")),
            knownUuidPaths = emptyMap(),
            isTurnGenerating = true,
            isLastStepInTurn = true,
            hasPendingApproval = false,
            liveStep = LiveStep(LiveStepKind.PREPARING_TOOL, GenerationMotionPhase.STREAM),
        )
        assertFalse(whenNonEditStreams!!.isLiveBatch)
    }

    @Test
    fun `buildLiveFileEditBatch handles mixed tool group with trailing file edits`() {
        val readInv = ToolInvocationPresentation(
            listOf(
                ExecutionStep("c0", "tool_call", toolName = "read_file", toolCallId = "tc-0", timestamp = 500L),
                ExecutionStep("r0", "tool_result", toolName = "read_file", toolCallId = "tc-0", content = "ok", timestamp = 700L),
            ),
        )
        val editRunning = ToolInvocationPresentation(
            listOf(
                ExecutionStep(
                    id = "c1",
                    type = "tool_call",
                    toolName = "patch_file",
                    toolCallId = "tc-1",
                    toolArgs = """{"path":"src/Main.kt","operations":[{"action":"insert_after","afterLine":1,"newContent":"a\nb\nc"}]}""",
                    timestamp = 1_000L,
                ),
            ),
        )

        val batch = buildLiveFileEditBatch(
            invocations = listOf(readInv, editRunning),
            streamingCalls = emptyList(),
            knownUuidPaths = emptyMap(),
            isTurnGenerating = true,
            isLastStepInTurn = true,
            hasPendingApproval = false,
            liveStep = LiveStep(LiveStepKind.TOOL, GenerationMotionPhase.TOOL, startedAt = 1_000L, targetId = "ai-1::c1"),
        )

        assertNotNull(batch)
        assertTrue(batch!!.isLiveBatch)
        assertTrue(batch.hasActiveRow)
        assertFalse(batch.isPureFileEditStep)
        assertEquals(1, batch.trailingInvocationCount)
        assertEquals("Main.kt", batch.rows.single().displayName)
        assertEquals(3, batch.rows.single().added)
        assertTrue(shouldExitLiveTailImmediately(isGenerating = true, liveStep = null, liveFileEditOwnsTail = true))
    }

    private fun completedEditInvocation(
        id: String,
        path: String,
        added: Int,
        removed: Int,
        timestamp: Long,
    ): ToolInvocationPresentation = ToolInvocationPresentation(
        listOf(
            ExecutionStep(
                id = "c-$id",
                type = "tool_call",
                toolName = "patch_file",
                toolCallId = "tc-$id",
                toolArgs = """{"path":"$path","operations":[]}""",
                timestamp = timestamp,
            ),
            ExecutionStep(
                id = "r-$id",
                type = "tool_result",
                toolName = "patch_file",
                toolCallId = "tc-$id",
                content = "补丁应用成功",
                timestamp = timestamp + 500L,
                fileChanges = listOf(
                    FileChangeSummary(
                        path = path,
                        change = FileChangeType.MODIFIED,
                        added = added,
                        removed = removed,
                    ),
                ),
            ),
        ),
    )
}
