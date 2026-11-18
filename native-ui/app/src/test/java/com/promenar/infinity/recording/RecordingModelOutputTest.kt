package com.promenar.infinity.recording

import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.remote.ResolvedProviderModel
import com.promenar.infinity.data.remote.protocol.LlmProtocol
import com.promenar.infinity.data.remote.protocol.LocalProtocol
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.StreamChunk
import com.promenar.infinity.domain.generation.CompletionReason
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RecordingModelOutputTest {
    private val app = mockk<InfinityApplication>(relaxed = true)
    private val session = RecordingSession("id", 1, "标题", segments = listOf(RecordingSegment(0, 1000, "讨论事项")))

    @Before fun setup() { mockkConstructor(LocalProtocol::class) }
    @After fun teardown() { unmockkConstructor(LocalProtocol::class) }

    private fun output(text: String, finished: Boolean = true) {
        coEvery { anyConstructed<LocalProtocol>().sendPrompt(any()) } returns flowOf(
            StreamChunk.TextDelta(text),
            StreamChunk.Completed(if (finished) CompletionReason.END_TURN else CompletionReason.TOOL_CALLS),
        )
    }

    @Test fun blankThinkingOnlyAndActionOnlyOutputsHaveTheirOwnErrorKind() = runBlocking {
        for (text in listOf("  ", "<think>分析</think>", "<think>未结束", "ACTION: 明天继续讨论")) {
            output(text)
            val error = runCatching { RecordingModel(app).summarizeBatch(session, RecordingSummaryProgress(fingerprint = "test"), null, "local", "") }.exceptionOrNull()
            assertTrue("空结果应有独立异常：$text，实际为 $error", error is RecordingEmptyOutputException)
        }
    }

    @Test fun languageMismatchAndUnfinishedOutputRemainDistinctFromEmptyOutput() = runBlocking {
        output("The project is ready.")
        assertTrue(runCatching { RecordingModel(app).summarizeBatch(session, RecordingSummaryProgress(fingerprint = "test"), null, "local", "") }.exceptionOrNull() is RecordingOutputLanguageMismatch)
        output("", finished = false)
        val error = runCatching { RecordingModel(app).summarizeBatch(session, RecordingSummaryProgress(fingerprint = "test"), null, "local", "") }.exceptionOrNull()
        assertTrue(error is IllegalStateException && error !is RecordingEmptyOutputException)
    }

    @Test fun validOutputStillProducesPointsAndActions() = runBlocking {
        output("项目将在周五发布。\nACTION: 明天完成检查")
        val progress = RecordingModel(app).summarizeBatch(session, RecordingSummaryProgress(fingerprint = "test"), null, "local", "")
        assertEquals(1, progress.cursor)
        assertEquals("项目将在周五发布。", progress.points.single().text)
        assertEquals(listOf("明天完成检查"), progress.actions)
    }

    @Test fun cloudOrganizeUses4096TokenBudgetWhileLocalKeeps208() = runBlocking {
        val localPrompts = mutableListOf<PromptRequest>()
        coEvery { anyConstructed<LocalProtocol>().sendPrompt(capture(localPrompts)) } returns flowOf(
            StreamChunk.TextDelta("本地要点"),
            StreamChunk.Completed(CompletionReason.END_TURN),
        )
        val model = RecordingModel(app)
        val localPoints = model.summarizeBatch(session, RecordingSummaryProgress(fingerprint = "test"), null, "local", "")
        model.advanceOverview(
            session,
            localPoints.copy(points = localPoints.points + RecordingSummaryPoint(null, "第二条要点", listOf(1000L))),
            null,
            "local",
        )
        assertEquals(listOf(208, 208), localPrompts.map { it.maxTokens })

        val cloudPrompts = mutableListOf<PromptRequest>()
        val cloudProtocol = mockk<LlmProtocol>()
        coEvery { cloudProtocol.sendPrompt(capture(cloudPrompts)) } returns flowOf(
            StreamChunk.TextDelta("云端结构化整理要点"),
            StreamChunk.Completed(CompletionReason.END_TURN),
        )
        val route = mockk<ResolvedProviderModel>(relaxed = true)
        every { route.remoteModelId } returns "deepseek-v4.1-flash"
        every { app.createRoutedModelProtocol(route) } returns cloudProtocol
        val cloud = RecordingModel.Cloud(key = "cloud-key", label = "NEWAPI · deepseek-v4.1-flash", route = route)
        val cloudSession = session.copy(cloudConsent = "cloud-key")

        val cloudPoints = model.summarizeBatch(cloudSession, RecordingSummaryProgress(fingerprint = "test"), cloud, null, "")
        model.advanceOverview(cloudSession, cloudPoints, cloud, null)
        assertEquals(listOf(4096, 4096), cloudPrompts.map { it.maxTokens })
    }
}
