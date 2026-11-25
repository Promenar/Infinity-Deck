package com.promenar.infinity.ui.common.motion

import com.promenar.infinity.data.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenRateEstimatorTest {
    @Test fun `字符类别估算与分片无关`() {
        val samples = listOf("中文文本", "English words 123456", "你好 world 42！", "fun main() { println(123) }", "日本語 한글 😀")
        for (sample in samples) {
            val whole = estimateTokenDelta(sample)
            val chunks = sample.map { estimateTokenDelta(it.toString()) }.sum()
            assertEquals(whole, chunks, 0.0001f)
        }
        assertEquals(2.4f, estimateTokenDelta("中文文本"), 0.001f)
        assertEquals(1f, estimateTokenDelta("word"), 0.001f)
        assertTrue(estimateTokenDelta("fun main() { println(123) }") > 5f)
    }

    @Test fun `满速对应中文估算比例`() {
        assertEquals(36f, GenerationMotionFullRate, 0f)
    }

    @Test fun `正文与思考的增量独立累计`() {
        val estimate = IncrementalTokenEstimate()
        estimate.update("Hello", "思考")
        val total = estimate.update("Hello world", "思考中")
        assertEquals(estimateTokenDelta("Hello world") + estimateTokenDelta("思考中"), total, 0.0001f)
    }

    @Test fun `真实用量迟到时校准并更新结束读数一次`() {
        val calibration = TokenRateCalibration()
        val tracker = TokenMotionTracker(calibration)
        val motion = GenerationMotion(GenerationMotion.Source.LIVE)
        val input = TokenMotionInput("中文文本", "思考", "msg", "model", 100, 110, 120, 2120, 12, true)
        tracker.update(true, input, motion)
        motion.update(GenerationMotionPhase.STREAM, null, motion.currentTokenEstimate(), true)
        tracker.update(false, input, motion)
        motion.update(null, null, motion.currentTokenEstimate(), true)
        tracker.finish(input.copy(actualTokens = null), motion)
        tracker.finish(input, motion)
        assertEquals(6f, motion.lastRunRate, 0.001f)
        val factor = calibration.factor("model")
        tracker.finish(input, motion)
        assertEquals(factor, calibration.factor("model"), 0f)
        assertTrue(factor > 1f)
    }

    @Test fun `末尾文本在结束后到达时计入校准`() {
        val calibration = TokenRateCalibration()
        val tracker = TokenMotionTracker(calibration)
        val motion = GenerationMotion(GenerationMotion.Source.LIVE)
        val input = TokenMotionInput("中文", "", "msg", "model", 100, 110, 120, 2120, null, true)
        tracker.update(true, input, motion)
        tracker.update(false, input, motion)
        val completed = input.copy(content = "中文文本", actualTokens = 6)
        tracker.update(false, completed, motion)
        tracker.finish(completed, motion)
        assertEquals(1.3f, calibration.factor("model"), 0.001f)
        assertEquals(3f, motion.lastRunRate, 0.001f)
    }

    @Test fun `历史消息用量不可校准当前生成`() {
        val calibration = TokenRateCalibration()
        val tracker = TokenMotionTracker(calibration)
        val motion = GenerationMotion(GenerationMotion.Source.LIVE)
        val input = TokenMotionInput("正文", "", "current", "model", 100, 110, 120, 2120, 10, true)
        tracker.update(true, input, motion)
        tracker.update(false, input, motion)
        tracker.finish(input.copy(messageId = "old"), motion)
        tracker.finish(input.copy(requestStartedAt = 10), motion)
        assertEquals(1f, calibration.factor("model"), 0f)
    }

    @Test fun `跨消息和估算用量不会参与校准`() {
        val calibration = TokenRateCalibration()
        val tracker = TokenMotionTracker(calibration)
        val motion = GenerationMotion(GenerationMotion.Source.LIVE)
        val first = TokenMotionInput("中文", "", "first", "model", 100, 110, 120, 2120, 10, true)
        tracker.update(true, first, motion)
        val second = first.copy(messageId = "second", content = "后文")
        tracker.update(true, second, motion)
        tracker.update(false, second, motion)
        tracker.finish(second, motion)
        assertEquals(1f, calibration.factor("model"), 0f)
        tracker.update(true, first, motion)
        tracker.update(false, first, motion)
        tracker.finish(first.copy(eligibleUsage = false), motion)
        assertEquals(1f, calibration.factor("model"), 0f)
    }

    @Test fun `校准收敛且有界并按模型隔离`() {
        val calibration = TokenRateCalibration()
        repeat(30) { calibration.observe("a", 100f, 200) }
        assertEquals(2f, calibration.factor("a"), 0.01f)
        assertEquals(1f, calibration.factor("b"), 0f)
        repeat(100) { calibration.observe("a", 1f, 1000) }
        assertEquals(2.5f, calibration.factor("a"), 0.001f)
        repeat(100) { calibration.observe("a", 1000f, 1) }
        assertEquals(0.4f, calibration.factor("a"), 0.001f)
    }

    @Test fun `仅流式输出工具调用参数时进入流式态并产生非零速率`() {
        val calibration = TokenRateCalibration()
        val tracker = TokenMotionTracker(calibration)
        val motion = GenerationMotion(GenerationMotion.Source.LIVE)
        val frame = 1f / 60f
        val baseInput = TokenMotionInput(
            content = "",
            reasoning = "",
            messageId = "msg-tool",
            modelId = "model",
            runStartedAt = 100L,
            requestStartedAt = 110L,
            firstOutputAt = 120L,
            endedAt = null,
            actualTokens = null,
            eligibleUsage = false,
            toolCalls = emptyList(),
        )
        tracker.update(true, baseInput, motion)
        motion.update(GenerationMotionPhase.STREAM, null, motion.currentTokenEstimate(), true)
        assertEquals(GenerationMotionPhase.WAIT, motion.actual)

        var args = ""
        repeat(120) { i ->
            if (i % 6 == 0) {
                args += "\"line_$i\":\"hello world payload\","
                val stepInput = baseInput.copy(toolCalls = listOf(ToolCall("call-1", "write_file", args)))
                tracker.update(true, stepInput, motion)
                motion.update(GenerationMotionPhase.STREAM, null, motion.currentTokenEstimate(), true)
            }
            motion.step(frame)
        }

        assertEquals(GenerationMotionPhase.STREAM, motion.actual)
        assertTrue("流式工具参数期间速率应大于零：${motion.readoutRate}", motion.readoutRate > 5f)
        assertTrue("流式工具参数期间 ACT 动效强度应大于零：${motion.act(0f)}", motion.act(0f) > 0.1f)
        assertTrue("点阵波形历史柱高应非零：${motion.historyLevel(0, 0f)}", motion.historyLevel(0, 0f) > 0.1f)
    }

    @Test fun `思考切到长工具参数期间速率不掉零`() {
        val tracker = TokenMotionTracker(TokenRateCalibration())
        val motion = GenerationMotion(GenerationMotion.Source.LIVE)
        val frame = 1f / 60f
        var reasoning = ""
        var args = ""
        val base = TokenMotionInput("", "", "msg-1", "model", 100L, 110L, 120L, null, null, false)

        repeat(60) { i ->
            if (i % 6 == 0) {
                reasoning += "正在分析并构造参数。"
                val input = base.copy(reasoning = reasoning)
                tracker.update(true, input, motion)
                motion.update(GenerationMotionPhase.STREAM, null, motion.currentTokenEstimate(), true)
            }
            motion.step(frame)
        }
        val rateAfterThinking = motion.readoutRate
        assertTrue("思考阶段速率应大于零：$rateAfterThinking", rateAfterThinking > 5f)

        // 思考结束、正文为空，持续流式输出工具参数超过 1.5 秒（远超 0.5 秒衰减时间常数）。
        var minRateDuringTools = Float.MAX_VALUE
        repeat(96) { i ->
            if (i % 6 == 0) {
                args += "{\"command\":\"echo step $i && ls -la\"},"
                val input = base.copy(
                    reasoning = reasoning,
                    toolCalls = listOf(ToolCall("c1", "run_shell", args)),
                )
                tracker.update(true, input, motion)
                motion.update(GenerationMotionPhase.STREAM, null, motion.currentTokenEstimate(), true)
            }
            motion.step(frame)
            if (i >= 18) minRateDuringTools = minOf(minRateDuringTools, motion.readoutRate)
        }
        assertEquals(GenerationMotionPhase.STREAM, motion.actual)
        assertTrue("工具参数流式期间速率不得断崖掉零：$minRateDuringTools", minRateDuringTools > 5f)
    }

    @Test fun `单轮多并行工具调用按id独立累计且切消息后重置基准`() {
        val estimate = IncrementalTokenEstimate()
        val firstBatch = listOf(
            ToolCall("c1", "read_file", "{\"path\":\"a"),
            ToolCall("c2", "search_code", "{\"query\":\"foo"),
        )
        estimate.update("", "", firstBatch)
        val secondBatch = listOf(
            ToolCall("c1", "read_file", "{\"path\":\"a.kt\"}"),
            ToolCall("c2", "search_code", "{\"query\":\"foobar\"}"),
        )
        val round1Total = estimate.update("", "", secondBatch)
        val expectedRound1 = estimateTokenDelta("read_file") + estimateTokenDelta("{\"path\":\"a.kt\"}") +
            estimateTokenDelta("search_code") + estimateTokenDelta("{\"query\":\"foobar\"}")
        assertEquals(expectedRound1, round1Total, 0.0001f)

        // 切换消息重置基准后，新消息复用相同 id 仍从 0 起累计。
        estimate.resetTextBaseline()
        val round2Call = listOf(ToolCall("c1", "run_shell", "{\"cmd\":\"ls\"}"))
        val afterRound2 = estimate.update("完成", "", round2Call)
        val expectedRound2 = expectedRound1 + estimateTokenDelta("完成") +
            estimateTokenDelta("run_shell") + estimateTokenDelta("{\"cmd\":\"ls\"}")
        assertEquals(expectedRound2, afterRound2, 0.0001f)
    }

    @Test fun `单条带工具调用消息允许校准，跨消息工具轮次仍阻止校准`() {
        val calibration = TokenRateCalibration()
        val tracker = TokenMotionTracker(calibration)
        val motion = GenerationMotion(GenerationMotion.Source.LIVE)

        // 1) 单条带 toolCalls 的助手消息：分子与分母口径一致，真实用量参与校准并更新平均速率。
        val singleToolInput = TokenMotionInput(
            content = "",
            reasoning = "",
            messageId = "msg-single",
            modelId = "model-a",
            runStartedAt = 100L,
            requestStartedAt = 110L,
            firstOutputAt = 120L,
            endedAt = 2120L,
            actualTokens = 20,
            eligibleUsage = true,
            toolCalls = listOf(ToolCall("call-1", "write_file", "{\"path\":\"demo.kt\",\"content\":\"println(1)\"}")),
        )
        tracker.update(true, singleToolInput, motion)
        motion.update(GenerationMotionPhase.STREAM, null, motion.currentTokenEstimate(), true)
        tracker.update(false, singleToolInput, motion)
        motion.update(null, null, motion.currentTokenEstimate(), true)
        tracker.finish(singleToolInput, motion)
        assertEquals(10f, motion.lastRunRate, 0.001f)
        assertTrue("单条带工具调用消息应完成校准", calibration.factor("model-a") != 1f)

        // 2) 多消息轮次（首条仅 toolCalls，次条为最终正文）：multipleMessages 为 true，禁止用末条 actualTokens 校准整轮估算。
        val round1 = TokenMotionInput(
            content = "",
            reasoning = "",
            messageId = "msg-r1",
            modelId = "model-b",
            runStartedAt = 5000L,
            requestStartedAt = 5010L,
            firstOutputAt = 5020L,
            endedAt = 6020L,
            actualTokens = 15,
            eligibleUsage = true,
            toolCalls = listOf(ToolCall("call-r1", "read_file", "{\"path\":\"a.kt\"}")),
        )
        tracker.update(true, round1, motion)
        val round2 = TokenMotionInput(
            content = "已完成读取并确认结果。",
            reasoning = "",
            messageId = "msg-r2",
            modelId = "model-b",
            runStartedAt = 5000L,
            requestStartedAt = 6500L,
            firstOutputAt = 6600L,
            endedAt = 7600L,
            actualTokens = 12,
            eligibleUsage = true,
            toolCalls = emptyList(),
        )
        tracker.update(true, round2, motion)
        tracker.update(false, round2, motion)
        tracker.finish(round2, motion)
        assertEquals(1f, calibration.factor("model-b"), 0f)
    }
}
