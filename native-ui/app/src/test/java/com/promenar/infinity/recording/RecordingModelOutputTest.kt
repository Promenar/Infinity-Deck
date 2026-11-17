package com.promenar.infinity.recording

import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.remote.protocol.LocalProtocol
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
}
