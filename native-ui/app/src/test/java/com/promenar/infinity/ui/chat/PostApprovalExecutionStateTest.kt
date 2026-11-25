package com.promenar.infinity.ui.chat

import com.promenar.infinity.data.model.ApprovalRequest
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.domain.generation.GenerationPhase
import com.promenar.infinity.ui.common.motion.GenerationMotionPhase
import com.promenar.infinity.ui.common.motion.generationMotionPhase
import com.promenar.infinity.ui.testing.UiTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostApprovalExecutionStateTest {

    @Test
    fun `waiting approval stays non-generating and approval phase when not executing`() {
        val state = resolveEffectiveApprovalExecutionState(
            coordinatorGenerating = false,
            rawPhase = GenerationPhase.WAITING_APPROVAL,
            rawStatus = GenerationStatus.IDLE,
            isApprovalExecuting = false,
        )

        assertFalse(state.isGenerating)
        assertEquals(GenerationPhase.WAITING_APPROVAL, state.phase)
        assertEquals(GenerationStatus.IDLE, state.status)
    }

    @Test
    fun `approved execution immediately switches from WAITING_APPROVAL to active EXECUTING_TOOLS`() {
        val state = resolveEffectiveApprovalExecutionState(
            coordinatorGenerating = false,
            rawPhase = GenerationPhase.WAITING_APPROVAL,
            rawStatus = GenerationStatus.IDLE,
            isApprovalExecuting = true,
        )

        assertTrue(state.isGenerating)
        assertEquals(GenerationPhase.EXECUTING_TOOLS, state.phase)
        assertEquals(GenerationStatus.THINKING, state.status)
        assertEquals(
            GenerationMotionPhase.TOOL,
            generationMotionPhase(phase = state.phase, awaitingUser = false),
        )
    }

    @Test
    fun `approved execution overrides null or terminal coordinator phase cleanly`() {
        listOf(null, GenerationPhase.COMPLETED, GenerationPhase.CANCELLED).forEach { raw ->
            val state = resolveEffectiveApprovalExecutionState(
                coordinatorGenerating = false,
                rawPhase = raw,
                rawStatus = GenerationStatus.COMPLETED,
                isApprovalExecuting = true,
            )
            assertTrue(state.isGenerating)
            assertEquals(GenerationPhase.EXECUTING_TOOLS, state.phase)
            assertEquals(GenerationStatus.THINKING, state.status)
        }
    }

    @Test
    fun `handoff to next generation preserves new coordinator phase without flicker`() {
        val state = resolveEffectiveApprovalExecutionState(
            coordinatorGenerating = true,
            rawPhase = GenerationPhase.STREAMING,
            rawStatus = GenerationStatus.RECEIVING,
            isApprovalExecuting = true,
        )

        assertTrue(state.isGenerating)
        assertEquals(GenerationPhase.STREAMING, state.phase)
        assertEquals(GenerationStatus.RECEIVING, state.status)
    }

    @Test
    fun `chatRenderStateTag leaves approval tag while approved request is submitting and executing`() {
        val request = ApprovalRequest(
            toolName = "shell_exec",
            reason = "run test",
            assistantMessageId = "ai-1",
        )
        val waitingState = ChatUiState(
            approvalRequest = request,
            isApprovalSubmitting = false,
            isGenerating = false,
        )
        val executingState = waitingState.copy(
            isApprovalSubmitting = true,
            isGenerating = true,
            status = GenerationStatus.THINKING,
        )

        assertEquals(UiTags.CHAT_STATE_APPROVAL, chatRenderStateTag(waitingState))
        assertEquals(UiTags.CHAT_STATE_GENERATING, chatRenderStateTag(executingState))
    }

    @Test
    fun `resolveLiveStep picks approved executing tool even without liveToolKeys`() {
        val assistant = Message(
            id = "ai-1",
            role = MessageRole.ASSISTANT,
            content = "",
            createdAt = 1_000L,
            executionSteps = listOf(
                ExecutionStep(
                    id = "step_1",
                    type = "tool_call",
                    toolName = "shell_exec",
                    toolCallId = "call-1",
                    toolArgs = """{"command":"./gradlew test"}""",
                    timestamp = 2_000L,
                ),
            ),
        )
        val effective = resolveEffectiveApprovalExecutionState(
            coordinatorGenerating = false,
            rawPhase = GenerationPhase.WAITING_APPROVAL,
            rawStatus = GenerationStatus.IDLE,
            isApprovalExecuting = true,
        )

        val liveStep = resolveLiveStep(
            messages = listOf(assistant),
            signals = LiveTurnSignals(
                phase = effective.phase,
                isGenerating = effective.isGenerating,
                hasStreamingContent = false,
                awaitingApproval = false,
                liveToolKeys = emptySet(),
            ),
        )

        assertNotNull(liveStep)
        assertEquals(LiveStepKind.TOOL, liveStep?.kind)
        assertEquals(GenerationMotionPhase.TOOL, liveStep?.motionPhase)
        assertEquals("ai-1::step_1", liveStep?.targetId)
        assertEquals(2_000L, liveStep?.startedAt)
    }
}
