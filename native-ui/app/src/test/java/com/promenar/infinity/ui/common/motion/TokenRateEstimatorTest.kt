package com.promenar.infinity.ui.common.motion

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
}
