package com.promenar.infinity.ui.common.motion

import com.promenar.infinity.domain.generation.GenerationFailureCode
import com.promenar.infinity.domain.generation.GenerationPhase
import com.promenar.infinity.domain.generation.GenerationRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun generationOutputTokens(content: String, reasoning: String?): Float =
    estimateTokenDelta(content) + estimateTokenDelta(reasoning.orEmpty())

class GenerationMotionTest {
    private val frame = 1f / 60f

    private fun GenerationMotion.run(seconds: Float) {
        repeat((seconds / frame).toInt()) { step(frame) }
    }

    @Test
    fun `思考归入流式家族，工具执行与等待用户各自成阶段`() {
        assertEquals(GenerationMotionPhase.WAIT, generationMotionPhase(GenerationPhase.CONNECTING, awaitingUser = false))
        assertEquals(GenerationMotionPhase.STREAM, generationMotionPhase(GenerationPhase.THINKING, awaitingUser = false))
        assertEquals(GenerationMotionPhase.STREAM, generationMotionPhase(GenerationPhase.STREAMING, awaitingUser = false))
        assertEquals(GenerationMotionPhase.TOOL, generationMotionPhase(GenerationPhase.EXECUTING_TOOLS, awaitingUser = false))
        assertEquals(GenerationMotionPhase.APPROVAL, generationMotionPhase(GenerationPhase.WAITING_APPROVAL, awaitingUser = false))
        assertEquals(GenerationMotionPhase.APPROVAL, generationMotionPhase(GenerationPhase.WAITING_INPUT, awaitingUser = false))
        // 子代理在工具执行中请求审批：轮到用户处理，优先显示等待审批。
        assertEquals(GenerationMotionPhase.APPROVAL, generationMotionPhase(GenerationPhase.EXECUTING_TOOLS, awaitingUser = true))
        // 持久化的待批准调用在生成任务退出后仍提示。
        assertEquals(GenerationMotionPhase.APPROVAL, generationMotionPhase(null, awaitingUser = true))
        assertNull(generationMotionPhase(GenerationPhase.COMPLETED, awaitingUser = false))
        assertNull(generationMotionPhase(null, awaitingUser = false))
    }

    @Test
    fun `开始生成时直接显示首个阶段，不淡入`() {
        val controller = MotionPhaseController()
        controller.step(frame, GenerationMotionPhase.WAIT)
        assertEquals(GenerationMotionPhase.WAIT, controller.shown)
        assertEquals(1f, controller.weight(GenerationMotionPhase.WAIT))
    }

    @Test
    fun `连续的短工具调用不会让显示阶段来回切换`() {
        val controller = MotionPhaseController()
        controller.step(frame, GenerationMotionPhase.WAIT)
        repeat(30) { controller.step(frame, GenerationMotionPhase.WAIT) }
        // 工具执行 0.15 秒 → 首字前 0.12 秒 → 工具执行 0.18 秒 → 首字前 0.1 秒，像连续三次很快的工具调用。
        val switchTimes = mutableListOf<Float>()
        var last = controller.shown
        var clock = 0f
        val bursts = listOf(
            GenerationMotionPhase.TOOL to 0.15f, GenerationMotionPhase.WAIT to 0.12f,
            GenerationMotionPhase.TOOL to 0.18f, GenerationMotionPhase.WAIT to 0.1f,
            GenerationMotionPhase.TOOL to 0.15f, GenerationMotionPhase.WAIT to 0.12f,
        )
        for ((phase, seconds) in bursts) repeat((seconds / frame).toInt()) {
            controller.step(frame, phase)
            clock += frame
            if (controller.shown != last) {
                switchTimes += clock
                last = controller.shown
            }
        }
        // 真实阶段切换了 6 次，显示阶段只跟着切换几次，且两次切换之间至少隔 0.4 秒。
        assertTrue("切换次数：${switchTimes.size}", switchTimes.size in 1..3)
        switchTimes.zipWithNext().forEach { (a, b) -> assertTrue("两次切换间隔 ${b - a} 秒", b - a >= MotionPhaseController.DwellSeconds - 1e-3f) }
    }

    @Test
    fun `稳定阶段的权重保持为 1，不逐帧抖动`() {
        val controller = MotionPhaseController()
        repeat(120) {
            controller.step(frame, GenerationMotionPhase.STREAM)
            assertEquals(1f, controller.weight(GenerationMotionPhase.STREAM))
        }
    }

    @Test
    fun `真实阶段稳定后显示阶段在停留时间后跟上，并在淡入时长内完成交叉淡入淡出`() {
        val controller = MotionPhaseController()
        controller.step(frame, GenerationMotionPhase.WAIT)
        repeat(60) { controller.step(frame, GenerationMotionPhase.WAIT) }
        controller.step(frame, GenerationMotionPhase.STREAM)
        assertEquals("超过停留时间后立即切换", GenerationMotionPhase.STREAM, controller.shown)
        repeat(7) { controller.step(frame, GenerationMotionPhase.STREAM) }
        val wait = controller.weight(GenerationMotionPhase.WAIT)
        val stream = controller.weight(GenerationMotionPhase.STREAM)
        assertTrue("淡入进行中：$wait / $stream", wait in 0.1f..0.9f)
        assertEquals("权重之和保持为 1", 1f, wait + stream, 0.02f)
        repeat(12) { controller.step(frame, GenerationMotionPhase.STREAM) }
        assertEquals(0f, controller.weight(GenerationMotionPhase.WAIT))
        assertEquals(1f, controller.weight(GenerationMotionPhase.STREAM))
    }

    @Test
    fun `生成结束后保留最后显示的阶段，由外层淡出收尾`() {
        val controller = MotionPhaseController()
        controller.step(frame, GenerationMotionPhase.STREAM)
        repeat(30) { controller.step(frame, null) }
        assertEquals(GenerationMotionPhase.STREAM, controller.shown)
        assertEquals(1f, controller.weight(GenerationMotionPhase.STREAM))
    }

    @Test
    fun `Token 速率跟随实际到达的 Token 数，停顿后回落到零`() {
        val meter = OutputRateMeter()
        var length = 0
        meter.report(length)
        // 每 0.1 秒到达 4 个字，约 40 Token/秒。
        repeat(180) { i ->
            if (i % 6 == 0) {
                length += 4
                meter.report(length)
            }
            meter.step(frame)
        }
        assertEquals(40f, meter.rate, 8f)
        assertEquals(1f, meter.act, 0.02f)
        assertTrue("历史记录了速率", meter.historyAt(0) > 20f)
        repeat(240) { meter.step(frame) }
        assertTrue("停顿 4 秒后速率回落：${meter.rate}", meter.rate < 1f)
        assertEquals(0f, meter.act, 0.02f)
    }

    @Test
    fun `新一轮回复的累计 Token 数变小时不计入速率`() {
        val meter = OutputRateMeter()
        meter.report(500)
        meter.step(frame)
        meter.report(3)
        meter.step(frame)
        assertEquals(0f, meter.rate, 0.01f)
    }

    @Test
    fun `重试倒计时期间显示重试等待，倒计时结束回到首字前`() {
        val motion = GenerationMotion(GenerationMotion.Source.LIVE)
        val retry = GenerationRetry(attempt = 1, maxAttempts = 10, code = GenerationFailureCode.NETWORK, delayMs = 2_000)
        motion.update(GenerationMotionPhase.WAIT, retry, outputTokens = 0, animate = true)
        motion.step(frame)
        assertEquals(GenerationMotionPhase.RETRY, motion.actual)
        assertEquals(2, motion.retrySeconds)
        motion.run(1.2f)
        assertEquals(1, motion.retrySeconds)
        assertTrue(motion.retryLeft in 0.3f..0.5f)
        motion.run(1f)
        assertEquals(GenerationMotionPhase.WAIT, motion.actual)
        assertEquals(GenerationMotionPhase.WAIT, motion.shown)
    }

    @Test
    fun `进入流式但还没有任何内容到达时仍按首字前显示`() {
        val motion = GenerationMotion(GenerationMotion.Source.LIVE)
        // 生成任务已进入流式阶段，但正文与思考内容都还是 0 字。
        motion.update(GenerationMotionPhase.STREAM, retry = null, outputTokens = 0, animate = true)
        motion.step(frame)
        assertEquals(GenerationMotionPhase.WAIT, motion.actual)
        motion.update(GenerationMotionPhase.STREAM, retry = null, outputTokens = 12, animate = true)
        motion.step(frame)
        assertEquals(GenerationMotionPhase.STREAM, motion.actual)
    }

    @Test
    fun `进入流式与首段正文同时到达时直接显示流式`() {
        val motion = GenerationMotion(GenerationMotion.Source.LIVE)
        motion.update(GenerationMotionPhase.WAIT, retry = null, outputTokens = 0, animate = true)
        // 一次重组里阶段切到流式、正文也已到达（短回复可能只有这一个分片）。
        motion.update(GenerationMotionPhase.STREAM, retry = null, outputTokens = 8, animate = true)
        assertEquals(GenerationMotionPhase.STREAM, motion.actual)
    }

    @Test
    fun `工具执行后新一轮思考时，正文没有新增前回到首字前`() {
        val motion = GenerationMotion(GenerationMotion.Source.LIVE)
        motion.update(GenerationMotionPhase.STREAM, retry = null, outputTokens = 40, animate = true)
        motion.update(GenerationMotionPhase.TOOL, retry = null, outputTokens = 40, animate = true)
        // 上一轮正文保留在累计内容里，新一轮先输出思考。
        motion.update(GenerationMotionPhase.STREAM, retry = null, outputTokens = 40, animate = true)
        assertEquals(GenerationMotionPhase.WAIT, motion.actual)
        motion.update(GenerationMotionPhase.STREAM, retry = null, outputTokens = 41, animate = true)
        assertEquals(GenerationMotionPhase.STREAM, motion.actual)
        // 累计正文变短（新一轮从头累计）后，以新的长度为基准。
        motion.update(GenerationMotionPhase.STREAM, retry = null, outputTokens = 0, animate = true)
        assertEquals(GenerationMotionPhase.WAIT, motion.actual)
        motion.update(GenerationMotionPhase.STREAM, retry = null, outputTokens = 3, animate = true)
        assertEquals(GenerationMotionPhase.STREAM, motion.actual)
    }

    @Test
    fun `等待审批不被重试倒计时覆盖`() {
        val motion = GenerationMotion(GenerationMotion.Source.LIVE)
        val retry = GenerationRetry(attempt = 1, maxAttempts = 10, code = GenerationFailureCode.NETWORK, delayMs = 5_000)
        motion.update(GenerationMotionPhase.APPROVAL, retry, outputTokens = 0, animate = true)
        motion.step(frame)
        assertEquals(GenerationMotionPhase.APPROVAL, motion.actual)
    }

    @Test
    fun `系统关闭动画时直接显示真实阶段`() {
        val motion = GenerationMotion(GenerationMotion.Source.LIVE)
        motion.update(GenerationMotionPhase.TOOL, retry = null, outputTokens = 0, animate = false)
        assertEquals(GenerationMotionPhase.TOOL, motion.shown)
        assertEquals(1f, motion.weight(GenerationMotionPhase.TOOL))
        assertEquals(GenerationMotionPhase.TOOL, motion.displayed)
    }

    @Test
    fun `还没开始过生成时按流式显示`() {
        val motion = GenerationMotion(GenerationMotion.Source.LIVE)
        assertEquals(1f, motion.weight(GenerationMotionPhase.STREAM))
        assertEquals(0f, motion.weight(GenerationMotionPhase.WAIT))
    }

    @Test
    fun `演示脚本轮播全部五个阶段`() {
        val motion = GenerationMotion(GenerationMotion.Source.DEMO)
        val seen = mutableSetOf<GenerationMotionPhase>()
        repeat((30f / frame).toInt()) {
            motion.step(frame)
            motion.shown?.let(seen::add)
        }
        assertEquals(GenerationMotionPhase.entries.toSet(), seen)
    }

    @Test
    fun `慢闪每秒一次、亮灭各半，等间隔闪 0点8 秒一次`() {
        val samples = (0 until 1000).map { it / 1000f }
        val litFraction = samples.count { slowBlink(it) > 0.5f } / 1000f
        assertEquals(0.5f, litFraction, 0.08f)
        assertEquals(slowBlink(0.3f), slowBlink(1.3f), 1e-4f)
        assertEquals(1f, evenBlink(0.1f))
        assertEquals(0f, evenBlink(0.5f))
        assertEquals(1f, evenBlink(0.9f))
    }

    @Test
    fun `速率表计入思考 Token 数`() {
        assertEquals(7.2f, generationOutputTokens("", "思考".repeat(6)), 0.001f)
        assertEquals(4.2f, generationOutputTokens("正文", "思考五个字"), 0.001f)
        assertEquals(0.75f, generationOutputTokens("abc", null), 0.001f)
        val meter = OutputRateMeter()
        var reasoning = ""
        meter.report(generationOutputTokens("", reasoning))
        // 只有思考内容到达：每 0.1 秒 3 个字，约 18 Token/秒。
        repeat(180) { i ->
            if (i % 6 == 0) {
                reasoning += "思考中"
                meter.report(generationOutputTokens("", reasoning))
            }
            meter.step(frame)
        }
        assertEquals(18f, meter.rate, 4f)
        assertEquals(reasoning.length * 0.6f, meter.total, 0.01f)
    }

    @Test
    fun `思考阶段只有思考内容到达时显示流式，没有内容到达时回落首字前`() {
        val motion = GenerationMotion(GenerationMotion.Source.LIVE)
        val thinking = generationMotionPhase(GenerationPhase.THINKING, awaitingUser = false)
        motion.update(GenerationMotionPhase.WAIT, retry = null, outputTokens = 0, animate = true)
        motion.update(thinking, retry = null, outputTokens = generationOutputTokens("", ""), animate = true)
        assertEquals("思考内容还没到达", GenerationMotionPhase.WAIT, motion.actual)
        motion.update(thinking, retry = null, outputTokens = generationOutputTokens("", "先想一想"), animate = true)
        assertEquals("思考内容到达后进入流式家族", GenerationMotionPhase.STREAM, motion.actual)
        // 思考转正文：仍在流式家族，不回落。
        motion.update(GenerationMotionPhase.STREAM, retry = null, outputTokens = generationOutputTokens("答", "先想一想"), animate = true)
        assertEquals(GenerationMotionPhase.STREAM, motion.actual)
    }

    @Test
    fun `点阵屏读数：首字等待秒数逐秒递增，工具轮次逐次累加`() {
        val motion = GenerationMotion(GenerationMotion.Source.LIVE)
        motion.update(GenerationMotionPhase.WAIT, retry = null, outputTokens = 0, animate = true)
        assertEquals(0, motion.waitSeconds)
        motion.run(2.5f)
        assertEquals(2, motion.waitSeconds)
        assertEquals(0, motion.toolRound)
        motion.update(GenerationMotionPhase.TOOL, retry = null, outputTokens = 0, animate = true)
        assertEquals(1, motion.toolRound)
        motion.run(0.5f)
        motion.update(GenerationMotionPhase.WAIT, retry = null, outputTokens = 0, animate = true)
        assertEquals("重新进入首字前时从 0 计", 0, motion.waitSeconds)
        motion.run(1.2f)
        assertEquals(1, motion.waitSeconds)
        motion.update(GenerationMotionPhase.TOOL, retry = null, outputTokens = 0, animate = true)
        assertEquals(2, motion.toolRound)
        // 下一轮生成从头计数。
        motion.update(null, retry = null, outputTokens = 0, animate = true)
        motion.update(GenerationMotionPhase.TOOL, retry = null, outputTokens = 0, animate = true)
        assertEquals(1, motion.toolRound)
    }

    @Test
    fun `点阵屏读数：每秒 Token 数按固定间隔取样，不逐帧跳动`() {
        val motion = GenerationMotion(GenerationMotion.Source.LIVE)
        var length = 0
        motion.update(GenerationMotionPhase.STREAM, retry = null, outputTokens = length, animate = true)
        repeat(180) { i ->
            if (i % 6 == 0) {
                length += 4
                motion.update(GenerationMotionPhase.STREAM, retry = null, outputTokens = length, animate = true)
            }
            motion.step(frame)
        }
        assertEquals(40f, motion.readoutRate, 8f)
        // 1 秒 60 帧里读数最多换 4、5 次。
        val seen = mutableListOf(motion.readoutRate)
        repeat(60) {
            motion.step(frame)
            if (motion.readoutRate != seen.last()) seen += motion.readoutRate
        }
        assertTrue("读数变化次数：${seen.size - 1}", seen.size - 1 <= (1f / GenerationMotion.ScopeReadoutHoldSeconds).toInt() + 1)
    }

    @Test
    fun `本轮结束即没有真实阶段，点阵屏不再单独停留`() {
        val motion = GenerationMotion(GenerationMotion.Source.LIVE)
        var length = 0
        motion.update(GenerationMotionPhase.STREAM, retry = null, outputTokens = 0, animate = true)
        repeat(120) { i ->
            if (i % 6 == 0) {
                length += 5
                motion.update(GenerationMotionPhase.STREAM, retry = null, outputTokens = length, animate = true)
            }
            motion.step(frame)
        }
        motion.update(null, retry = null, outputTokens = 0, animate = true)
        // 结束后不再有需要逐帧推进的停留画面，点阵屏只随浮岛生成态动画淡出。
        assertNull(motion.actual)
    }

    @Test
    fun `演示脚本含思考段，思考段属于流式家族`() {
        val motion = GenerationMotion(GenerationMotion.Source.DEMO)
        var sawThinking = false
        repeat((30f / frame).toInt()) {
            motion.step(frame)
            if (motion.demoThinking) {
                sawThinking = true
                assertEquals(GenerationMotionPhase.STREAM, motion.actual)
            }
        }
        assertTrue(sawThinking)
    }

    @Test
    fun `速率为零时流式的 ACT 灯全部熄灭`() {
        assertTrue((0 until 200).none { actOn(it % 6, it * 0.013f, act = 0f) })
        assertTrue((0 until 200).any { actOn(it % 6, it * 0.013f, act = 1f) })
    }

    @Test
    fun `本轮结束时记下波形与平均速率，速率表清零后运行坞点阵屏仍能停在本轮`() {
        val motion = GenerationMotion(GenerationMotion.Source.LIVE)
        motion.update(GenerationMotionPhase.STREAM, retry = null, outputTokens = 0, animate = true)
        for (i in 1..20) {
            motion.run(0.1f)
            motion.update(GenerationMotionPhase.STREAM, retry = null, outputTokens = i * 6, animate = true)
        }
        motion.run(0.2f)
        assertEquals(0f, motion.lastRunRate, 0f)
        motion.update(null, retry = null, outputTokens = 120, animate = true)
        // 120 字，首字到末字约 1.9 秒。
        assertEquals(120f / 1.9f, motion.lastRunRate, 1.5f)
        assertTrue(motion.lastRunLevel(0) > 0.1f)
        assertEquals(0f, motion.lastRunLevel(OutputRateMeter.HistorySize), 0f)
        // 下一轮开始后，结束前仍是上一轮的。
        motion.update(GenerationMotionPhase.WAIT, retry = null, outputTokens = 0, animate = true)
        assertEquals(120f / 1.9f, motion.lastRunRate, 1.5f)
    }

    @Test
    fun `没有出过字的一轮平均速率为零`() {
        val motion = GenerationMotion(GenerationMotion.Source.LIVE)
        motion.update(GenerationMotionPhase.WAIT, retry = null, outputTokens = 0, animate = true)
        motion.run(1f)
        motion.update(null, retry = null, outputTokens = 0, animate = true)
        assertEquals(0f, motion.lastRunRate, 0f)
        assertEquals(0f, motion.lastRunLevel(0), 0f)
    }
}
