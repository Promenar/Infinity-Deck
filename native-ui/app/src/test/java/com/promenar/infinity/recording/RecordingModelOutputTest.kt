package com.promenar.infinity.recording

import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.model.params.ReasoningEffort
import com.promenar.infinity.data.remote.ProviderResolution
import com.promenar.infinity.data.remote.ResolvedProviderModel
import com.promenar.infinity.data.remote.SessionHeaderWiringEndpoint
import com.promenar.infinity.data.remote.protocol.LlmProtocol
import com.promenar.infinity.data.remote.protocol.LocalProtocol
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.StreamChunk
import com.promenar.infinity.domain.generation.CompletionReason
import com.promenar.infinity.domain.generation.GenerationFailureCode
import io.ktor.http.HttpStatusCode
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeoutException

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

    @Test fun cloudOrganizeUsesUnboundedTokensAndLowReasoningWhileLocalKeeps208() = runBlocking {
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
        assertEquals(listOf(null, null), localPrompts.map { it.reasoningEffort })

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
        assertEquals(listOf<Int?>(null, null), cloudPrompts.map { it.maxTokens })
        assertEquals(listOf(ReasoningEffort.LOW, ReasoningEffort.LOW), cloudPrompts.map { it.reasoningEffort })
        assertEquals(listOf(cloudSession.id, cloudSession.id), cloudPrompts.map { it.sessionId })
    }

    @Test fun timeoutStreamErrorMapsToTimeoutExceptionWhileOtherErrorsRemainIllegalState() = runBlocking {
        val cloudProtocol = mockk<LlmProtocol>()
        val route = mockk<ResolvedProviderModel>(relaxed = true)
        every { route.remoteModelId } returns "deepseek-v4.1-flash"
        every { app.createRoutedModelProtocol(route) } returns cloudProtocol
        val cloud = RecordingModel.Cloud(key = "cloud-key", label = "NEWAPI · deepseek-v4.1-flash", route = route)
        val cloudSession = session.copy(cloudConsent = "cloud-key")
        val model = RecordingModel(app)

        coEvery { cloudProtocol.sendPrompt(any()) } returns flowOf(
            StreamChunk.Error(code = GenerationFailureCode.TIMEOUT, retryable = true, technical = "gateway timeout"),
        )
        val timeoutErr = runCatching {
            model.summarizeBatch(cloudSession, RecordingSummaryProgress(fingerprint = "test"), cloud, null, "")
        }.exceptionOrNull()
        assertTrue(timeoutErr is TimeoutException)

        coEvery { cloudProtocol.sendPrompt(any()) } returns flowOf(
            StreamChunk.Error(code = GenerationFailureCode.OUTPUT_LIMIT, retryable = false, technical = "length"),
        )
        val outputLimitErr = runCatching {
            model.summarizeBatch(cloudSession, RecordingSummaryProgress(fingerprint = "test"), cloud, null, "")
        }.exceptionOrNull()
        assertTrue(outputLimitErr is IllegalStateException && outputLimitErr !is RecordingEmptyOutputException)
    }

    @Test fun cloudOverviewAggregatesUpTo28KCharsInSinglePass() = runBlocking {
        val cloudProtocol = mockk<LlmProtocol>()
        val captured = mutableListOf<PromptRequest>()
        coEvery { cloudProtocol.sendPrompt(capture(captured)) } returns flowOf(
            StreamChunk.TextDelta("### 综述\n已整合超过五千字的长会议要点。"),
            StreamChunk.Completed(CompletionReason.END_TURN),
        )
        val route = mockk<ResolvedProviderModel>(relaxed = true)
        every { route.remoteModelId } returns "deepseek-v4.1-flash"
        every { app.createRoutedModelProtocol(route) } returns cloudProtocol
        val cloud = RecordingModel.Cloud(key = "cloud-key", label = "NEWAPI · deepseek-v4.1-flash", route = route)
        val cloudSession = session.copy(cloudConsent = "cloud-key")
        val longPoints = List(3) { RecordingSummaryPoint(null, "第${it + 1}阶段讨论内容：" + "要".repeat(1_900), listOf(it * 1000L)) }
        val progress = RecordingSummaryProgress(cursor = 1, points = longPoints, fingerprint = "test")

        val updated = RecordingModel(app).advanceOverview(cloudSession, progress, cloud, null)
        assertEquals(3, updated.overviewCursor)
        assertEquals(1, captured.size)
        assertTrue(captured.single().messages[1].content.length > 5_000)
    }

    @Test fun multiSpeakerInterleavedTurnsGroupPerWindowAndAdvanceCursorAfterLastSpeaker() = runBlocking {
        val cloudProtocol = mockk<LlmProtocol>()
        val captured = mutableListOf<PromptRequest>()
        coEvery { cloudProtocol.sendPrompt(capture(captured)) } returnsMany listOf(
            flowOf(StreamChunk.TextDelta("甲的发言要点"), StreamChunk.Completed(CompletionReason.END_TURN)),
            flowOf(StreamChunk.TextDelta("乙的发言要点"), StreamChunk.Completed(CompletionReason.END_TURN)),
        )
        val route = mockk<ResolvedProviderModel>(relaxed = true)
        every { route.remoteModelId } returns "deepseek-v4.1-flash"
        every { app.createRoutedModelProtocol(route) } returns cloudProtocol
        val cloud = RecordingModel.Cloud(key = "cloud-key", label = "NEWAPI · deepseek-v4.1-flash", route = route)
        val multiSession = RecordingSession(
            id = "multi",
            createdAt = 1L,
            title = "多人会议",
            cloudConsent = "cloud-key",
            distinguishSpeakers = true,
            segments = listOf(
                RecordingSegment(0, 1000, "甲第一句", speakerId = 0),
                RecordingSegment(1000, 2000, "乙第一句", speakerId = 1),
                RecordingSegment(2000, 3000, "甲第二句", speakerId = 0),
                RecordingSegment(3000, 4000, "乙第二句", speakerId = 1),
            ),
        )
        val model = RecordingModel(app)
        val first = model.summarizeBatch(multiSession, RecordingSummaryProgress(fingerprint = "test"), cloud, null, "")
        assertEquals(0, first.cursor)
        assertEquals(1, first.speakerCursor)
        assertEquals(1, first.points.size)
        assertEquals(0, first.points[0].speakerId)
        assertEquals(listOf(0L, 2000L), first.points[0].sources)

        val second = model.summarizeBatch(multiSession, first, cloud, null, "")
        assertEquals(4, second.cursor)
        assertEquals(0, second.speakerCursor)
        assertEquals(2, second.points.size)
        assertEquals(1, second.points[1].speakerId)
        assertEquals(listOf(1000L, 3000L), second.points[1].sources)
        assertTrue(captured[0].messages[1].content.contains("甲第一句\n甲第二句"))
        assertTrue(captured[1].messages[1].content.contains("乙第一句\n乙第二句"))
    }

    @Test fun cloudOrganizeInjectsSessionHeadersOnWireWhenRouterEnablesThem() = runBlocking {
        SessionHeaderWiringEndpoint(enabled = true).use { endpoint ->
            endpoint.reply = {
                HttpStatusCode.OK to SessionHeaderWiringEndpoint.textStream("讨论要点：已完成会话头全站收敛。")
            }
            val resolved = (endpoint.router.resolve(SessionHeaderWiringEndpoint.MODEL) as ProviderResolution.Success).value
            every { app.providerRequestRouter } returns endpoint.router
            every { app.createRoutedModelProtocol(resolved) } answers {
                endpoint.router.createProtocol(resolved) { endpoint.protocol }
            }
            val cloud = RecordingModel.Cloud(key = "cloud-key", label = "NEWAPI · model", route = resolved)
            val cloudSession = session.copy(id = "rec-session-42", cloudConsent = "cloud-key")

            val model = RecordingModel(app)
            val points = model.summarizeBatch(cloudSession, RecordingSummaryProgress(fingerprint = "test"), cloud, null, "")
            model.advanceOverview(cloudSession, points, cloud, null)
            assertEquals(2, endpoint.requests.size)
            endpoint.requests.forEach { request ->
                assertEquals(endpoint.identifier("rec-session-42"), request.headers["x-opencode-session"])
                assertTrue(request.headers["User-Agent"]?.startsWith("Infinity-Deck/") == true)
            }

            endpoint.enabled = false
            val disabledResolved = (endpoint.router.resolve(SessionHeaderWiringEndpoint.MODEL) as ProviderResolution.Success).value
            every { app.createRoutedModelProtocol(disabledResolved) } answers {
                endpoint.router.createProtocol(disabledResolved) { endpoint.protocol }
            }
            val disabledCloud = RecordingModel.Cloud(key = "cloud-key", label = "NEWAPI · model", route = disabledResolved)
            model.summarizeBatch(cloudSession, RecordingSummaryProgress(fingerprint = "test"), disabledCloud, null, "")
            assertNull(endpoint.requests.last().headers["x-opencode-session"])
            assertFalse(endpoint.requests.last().headers["User-Agent"]?.startsWith("Infinity-Deck/") == true)
        }
    }
}
