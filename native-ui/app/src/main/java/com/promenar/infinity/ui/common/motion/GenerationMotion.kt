package com.promenar.infinity.ui.common.motion

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalInspectionMode
import com.promenar.infinity.domain.generation.GenerationPhase
import com.promenar.infinity.domain.generation.GenerationRetry
import com.promenar.infinity.ui.common.network.rememberAnimationsEnabled
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow

/**
 * 生成过程的动效阶段，浮岛光效与生成状态指示共用；每种样式在各阶段保持同一视觉家族，只换节奏与颜色。
 * 灯语借用交换机面板：首字前像链路协商（慢扫或呼吸，不闪），流式输出像 ACT 收发（随机闪烁，跟随实际 Token 速率），
 * 工具执行像忙碌寻道（第三色按固定节拍推进），等待审批像告警慢闪（第三色每秒慢闪），重试等待像降速待机（压暗等间隔闪）。
 */
enum class GenerationMotionPhase {
    /** 已发出请求、还没有任何内容到达；思考阶段在思考内容到达前也算在这里。 */
    WAIT,
    /** 思考内容或正文逐字到达；两者共用同一套表现，速率按二者合计的 Token 数估算。 */
    STREAM,
    /** 在等工具结果。 */
    TOOL,
    /** 暂停，等用户批准或回答。 */
    APPROVAL,
    /** 自动重试前的退避倒计时。 */
    RETRY,
}

/**
 * 把生成任务的阶段映射为动效阶段；[awaitingUser] 为有待批准的工具调用或待回答的提问。
 * 没有进行中的生成且无需用户处理时返回 null。重试倒计时由 [GenerationMotion] 按时间叠加，不在这里判断。
 */
internal fun generationMotionPhase(phase: GenerationPhase?, awaitingUser: Boolean): GenerationMotionPhase? = when {
    awaitingUser -> GenerationMotionPhase.APPROVAL
    phase == null -> null
    else -> when (phase) {
        GenerationPhase.QUEUED,
        GenerationPhase.PREPARING,
        GenerationPhase.BUILDING_CONTEXT,
        GenerationPhase.CONNECTING,
        GenerationPhase.POST_PROCESSING -> GenerationMotionPhase.WAIT
        // 思考归入流式家族；思考内容到达前由 [GenerationMotion] 按“没有新增内容”回落为首字前。
        GenerationPhase.THINKING,
        GenerationPhase.STREAMING -> GenerationMotionPhase.STREAM
        GenerationPhase.EXECUTING_TOOLS -> GenerationMotionPhase.TOOL
        GenerationPhase.WAITING_APPROVAL,
        GenerationPhase.WAITING_INPUT -> GenerationMotionPhase.APPROVAL
        GenerationPhase.COMPLETED,
        GenerationPhase.FAILED,
        GenerationPhase.CANCELLED,
        GenerationPhase.PERSISTENCE_FAILED -> null
    }
}

/** 审批：1 秒一次的慢闪，亮灭各半，边缘柔和。 */
internal fun slowBlink(t: Float): Float {
    val f = fract(t)
    return smoothStep(0f, 0.08f, f) * (1 - smoothStep(0.5f, 0.6f, f))
}

/** 重试：0.8 秒一次的等间隔短闪。 */
internal fun evenBlink(t: Float): Float = if (fract(t / 0.8f) < 0.3f) 1f else 0f

/** 周期为 [period] 秒的呼吸，0..1。 */
internal fun breath(t: Float, period: Float): Float = 0.5f - 0.5f * cos(Tau * t / period)

/** 0→1→0 的往返。 */
internal fun pingPong(u: Float): Float {
    val f = fract(u)
    return if (f < 0.5f) f * 2 else 2 - f * 2
}

/** 1.1 秒一次的心跳双拍。 */
internal fun heartbeat(t: Float): Float {
    val f = fract(t / 1.1f)
    return exp(-((f - 0.05f) / 0.045f).pow(2)) + 0.7f * exp(-((f - 0.26f) / 0.045f).pow(2))
}

/** 流式的 ACT 灯：[act] 为 0 时熄灭，越高闪得越密越快。 */
internal fun actOn(id: Int, time: Float, act: Float, seed: Float = 0f): Boolean {
    if (act < 0.02f) return false
    val burst = act * (0.55f + 0.45f * valueNoise(time * 2.1f + seed))
    val hz = 16 + 34 * act
    return motionHash(id * 3.3f, floor(time * hz + id * 5.1f)) < 0.08f + 0.8f * burst
}

/** Token 速率达到这个值（Token/秒）时流式动效最满。 */
internal const val GenerationMotionFullRate = 36f

/**
 * Token 速率：按上报的累计 Token 数（正文加思考内容）估算，时间常数 0.5 秒的指数平滑；每 0.09 秒记一次历史，供点阵波形屏画柱状图。
 * 累计 Token 数变小（新一轮回复）时只更新基准，不计入速率。
 */
internal class OutputRateMeter {
    private var lastLength = -1f
    private var pending = 0f

    /** 本轮累计到达的 Token 数，只计增量；扫光点阵的写入头按它前进，点阵屏按它算平均速率。 */
    var total = 0f
        private set
    private var historyClock = 0f
    private val history = FloatArray(HistorySize)
    private var historyHead = 0
    private var historyCount = 0

    /** 当前速率，Token/秒。 */
    var rate = 0f
        private set

    val act: Float get() = clamp01(rate / GenerationMotionFullRate)

    fun report(length: Int) = report(length.toFloat())

    fun report(length: Float) {
        if (lastLength >= 0 && length > lastLength) {
            pending += length - lastLength
            total += length - lastLength
        }
        lastLength = length
    }

    fun step(dt: Float) {
        if (dt <= 0f) return
        val instant = pending / dt
        pending = 0f
        rate += (instant - rate) * (1 - exp(-dt / SmoothingSeconds))
        historyClock += dt
        while (historyClock >= HistoryStepSeconds) {
            historyClock -= HistoryStepSeconds
            history[historyHead] = rate
            historyHead = (historyHead + 1) % HistorySize
            if (historyCount < HistorySize) historyCount++
        }
    }

    /** [back] 个记录之前的速率，0 为最新一条；没有记录时为 0。 */
    fun historyAt(back: Int): Float {
        if (back < 0 || back >= historyCount) return 0f
        return history[(historyHead - 1 - back).mod(HistorySize)]
    }

    /** 把历史按新到旧复制进 [out]，返回条数。 */
    fun copyHistory(out: FloatArray): Int {
        val n = minOf(historyCount, out.size)
        for (i in 0 until n) out[i] = historyAt(i)
        return n
    }

    fun reset() {
        lastLength = -1f
        pending = 0f
        total = 0f
        rate = 0f
        historyClock = 0f
        historyHead = 0
        historyCount = 0
    }

    companion object {
        const val SmoothingSeconds = 0.5f
        const val HistoryStepSeconds = 0.09f
        const val HistorySize = 80
    }
}

/**
 * 阶段控制：真实阶段变化后，显示阶段至少停留 [DwellSeconds] 才切换，避免连续短工具调用时来回闪；
 * 切换时新旧阶段在 [FadeSeconds] 内交叉淡入淡出。真实阶段为 null（生成结束）时保留最后显示的阶段，
 * 由外层的整体淡出收尾；下一次开始时直接切到新阶段，不淡入。
 */
internal class MotionPhaseController {
    private val weights = FloatArray(GenerationMotionPhase.entries.size)
    private var clock = 0f
    private var since = 0f

    var shown: GenerationMotionPhase? = null
        private set

    fun weight(phase: GenerationMotionPhase): Float = weights[phase.ordinal]

    fun snap(phase: GenerationMotionPhase) {
        shown = phase
        since = clock
        weights.fill(0f)
        weights[phase.ordinal] = 1f
    }

    fun step(dt: Float, actual: GenerationMotionPhase?) {
        clock += dt
        if (actual == null) return
        val current = shown
        if (current == null) {
            snap(actual)
            return
        }
        if (actual != current && clock - since >= DwellSeconds) {
            shown = actual
            since = clock
        }
        for (p in GenerationMotionPhase.entries) {
            val target = if (p == shown) 1f else 0f
            val w = weights[p.ordinal]
            weights[p.ordinal] = when {
                target > w -> minOf(1f, w + dt / FadeSeconds)
                target < w -> maxOf(0f, w - dt / FadeSeconds)
                else -> w
            }
        }
    }

    companion object {
        const val DwellSeconds = 0.4f
        const val FadeSeconds = 0.25f
    }
}

/**
 * 一个会话界面共用的生成动效状态：显示阶段与各阶段权重、Token 速率与重试倒计时，以及点阵屏读数（首字等待秒数、
 * 工具轮次）。字段只在绘制阶段读取，推进时不触发重组。四种来源：
 * - 实时：由 [rememberGenerationMotion] 按生成任务推进；
 * - 演示：设置页预览按固定脚本轮播五个阶段，流式之前有一段思考；
 * - 环境：没有提供方时（首页会话列表、静态预览）固定为流式，速率取噪声；
 * - 固定：会话流中挂在某一步上的指示，阶段固定为该步的阶段，Token 速率等读数取自 [delegate]。
 */
@Stable
class GenerationMotion internal constructor(
    private val source: Source,
    private val pinned: GenerationMotionPhase? = null,
    private val delegate: GenerationMotion? = null,
) {
    internal enum class Source { LIVE, DEMO, AMBIENT, PINNED }

    private val controller = MotionPhaseController()
    private val meter = OutputRateMeter()
    private var clock = 0f
    private var rawPhase: GenerationMotionPhase? = null
    private var preStreamLength = 0f
    private var streamBaseLength = 0f
    private var basePhase: GenerationMotionPhase? = null
    private var retry: GenerationRetry? = null
    private var retryStartedAt = 0f
    private var demoRetryLeft = 0f
    private var trackedPhase: GenerationMotionPhase? = null
    private var waitStartedAt = 0f
    private var toolRounds = 0
    private var heldRate = 0f
    private var heldRateClock = 0f
    private var lastTotal = 0f
    private var outputStartedAt = -1f
    private var outputEndedAt = 0f
    private val lastRunHistory = FloatArray(OutputRateMeter.HistorySize)
    private var lastRunCount = 0
    private var lastRunAverage = 0f

    /** 真实阶段（已叠加重试倒计时）；没有进行中的生成时为 null。值相同时写入不触发重组，组合中可以读取。 */
    var actual: GenerationMotionPhase? by mutableStateOf(
        when (source) {
            Source.AMBIENT -> GenerationMotionPhase.STREAM
            Source.PINNED -> pinned
            else -> null
        },
    )
        private set

    init {
        // 演示从脚本里的思考段开始，系统关闭动画时停在流式。
        if (source == Source.DEMO) clock = DemoStreamStart
        // 固定来源不推进，阶段权重停在快照上。
        if (source == Source.PINNED) controller.snap(requireNotNull(pinned)) else if (source != Source.LIVE) controller.snap(GenerationMotionPhase.STREAM)
    }

    /** [phase] 在当前画面中的权重，0..1；各阶段之和为 1。还没有显示过任何阶段时按流式显示。 */
    fun weight(phase: GenerationMotionPhase): Float = when {
        source == Source.AMBIENT || controller.shown == null -> if (phase == GenerationMotionPhase.STREAM) 1f else 0f
        else -> controller.weight(phase)
    }

    /** 当前显示的阶段；尚未开始过生成时为 null。 */
    val shown: GenerationMotionPhase? get() = controller.shown

    /** 与 [shown] 相同，但只在切换时写入快照状态，供组合中显示阶段名。 */
    var displayed: GenerationMotionPhase? by mutableStateOf(controller.shown)
        private set

    /** 演示脚本正处在思考段（流式家族，只有思考内容到达），供设置页预览显示阶段名；实时来源恒为 false。 */
    var demoThinking: Boolean by mutableStateOf(false)
        private set

    /** 流式动效强度 0..1：实际 Token 速率除以 [GenerationMotionFullRate]。环境与演示来源按 [time] 取噪声。 */
    fun act(time: Float): Float = when (source) {
        Source.LIVE -> meter.act
        Source.DEMO -> meter.act
        Source.AMBIENT -> clamp01(0.3f + 0.45f * valueNoise(time * 0.5f + 3f))
        Source.PINNED -> requireNotNull(delegate).act(time)
    }

    /**
     * 点阵波形屏第 [back] 列（0 为最新）的柱高 0..1；时间槽 [slot] 只用于环境来源合成柱高。
     */
    fun historyLevel(back: Int, slot: Float): Float = when (source) {
        Source.PINNED -> requireNotNull(delegate).historyLevel(back, slot)
        Source.AMBIENT -> clamp01(0.2f + 0.65f * valueNoise(slot * 0.19f) + (motionHash(slot, 1.3f) - 0.5f) * 0.35f)
        else -> clamp01(meter.historyAt(back) / (GenerationMotionFullRate * 1.15f))
    }

    /** 本轮累计到达的 Token 数（正文加思考），扫光点阵的写入头按它前进；环境来源按 [time] 匀速合成。 */
    fun writtenTokens(time: Float): Float = when (source) {
        Source.PINNED -> requireNotNull(delegate).writtenTokens(time)
        Source.LIVE -> meter.total
        Source.DEMO -> demoTokens
        Source.AMBIENT -> time * 12f
    }

    /** 点阵屏显示的每秒 Token 数：每 [ScopeReadoutHoldSeconds] 取一次速率，数字不逐帧跳动。 */
    val readoutRate: Float get() = when (source) {
        Source.AMBIENT -> 19.2f
        Source.PINNED -> requireNotNull(delegate).readoutRate
        else -> heldRate
    }

    /**
     * 上一轮结束时速率历史第 [back] 列（0 为最新）的柱高 0..1，供运行坞点阵屏停在本轮波形；本轮还在进行时也是上一轮的。
     * 环境来源按列号合成固定柱高。
     */
    fun lastRunLevel(back: Int): Float = when (source) {
        Source.PINNED -> requireNotNull(delegate).lastRunLevel(back)
        Source.AMBIENT -> historyLevel(back, 40f - back)
        else -> if (back in 0 until lastRunCount) clamp01(lastRunHistory[back] / (GenerationMotionFullRate * 1.15f)) else 0f
    }

    /** 上一轮的平均 Token 速率（Token/秒）：累计 Token 数除以首字到末字的时长（至少 1 秒）；没有出过字时为 0。 */
    val lastRunRate: Float get() = when (source) {
        Source.AMBIENT -> 16.8f
        Source.PINNED -> requireNotNull(delegate).lastRunRate
        else -> lastRunAverage
    }

    /** 首字前已等待的整秒数，从进入首字前起逐秒递增。 */
    val waitSeconds: Int get() = when (source) {
        Source.AMBIENT -> 0
        Source.PINNED -> requireNotNull(delegate).waitSeconds
        else -> floor(clock - waitStartedAt).toInt().coerceAtLeast(0)
    }

    /** 本轮第几次进入工具执行，从 1 起；还没执行过工具时为 0。 */
    val toolRound: Int get() = if (source == Source.PINNED) requireNotNull(delegate).toolRound else toolRounds

    /** 重试倒计时剩余比例 1→0。 */
    val retryLeft: Float
        get() = when {
            source == Source.DEMO -> demoRetryLeft
            retry == null -> 0f
            else -> clamp01(1 - (clock - retryStartedAt) / retryDelaySeconds())
        }

    /** 重试倒计时剩余整秒数。 */
    val retrySeconds: Int
        get() = when (source) {
            Source.DEMO -> ceil(DemoRetrySeconds * demoRetryLeft).toInt()
            else -> retry?.let { ceil(retryDelaySeconds() - (clock - retryStartedAt)).toInt().coerceAtLeast(0) } ?: 0
        }

    private fun retryDelaySeconds(): Float = (retry?.delayMs ?: 0L).coerceAtLeast(1L) / 1000f

    internal fun update(phase: GenerationMotionPhase?, retry: GenerationRetry?, outputTokens: Int, animate: Boolean) =
        update(phase, retry, outputTokens.toFloat(), animate)

    internal fun update(phase: GenerationMotionPhase?, retry: GenerationRetry?, outputTokens: Float, animate: Boolean) {
        if (phase != null && rawPhase == null) beginRun()
        if (retry != this.retry) {
            this.retry = retry
            retryStartedAt = clock
        }
        if (phase == null) {
            if (rawPhase != null) snapshotRun()
            meter.reset()
        } else {
            meter.report(outputTokens)
            if (meter.total > lastTotal) {
                if (outputStartedAt < 0f) outputStartedAt = clock
                outputEndedAt = clock
                lastTotal = meter.total
            }
        }
        // 生成任务收到首个分片就进入流式；思考内容或正文比进入流式前有新增后才按流式显示，没有内容到达时仍是首字前。
        if (phase == GenerationMotionPhase.STREAM) {
            if (rawPhase != GenerationMotionPhase.STREAM) streamBaseLength = preStreamLength
            if (outputTokens < streamBaseLength) streamBaseLength = outputTokens
        } else {
            preStreamLength = outputTokens
        }
        rawPhase = phase
        basePhase = if (phase == GenerationMotionPhase.STREAM && outputTokens <= streamBaseLength) GenerationMotionPhase.WAIT else phase
        publishActual(resolveActual())
        // 不推进时（系统关闭动画）直接显示真实阶段。
        if (!animate) actual?.let(controller::snap)
        displayed = controller.shown
    }

    private fun beginRun() {
        meter.report(0f)
        toolRounds = 0
        heldRate = 0f
        heldRateClock = 0f
        lastTotal = 0f
        outputStartedAt = -1f
    }

    /** 本轮结束：速率表清零前记下历史与平均速率。 */
    private fun snapshotRun() {
        lastRunCount = meter.copyHistory(lastRunHistory)
        lastRunAverage = if (meter.total > 0 && outputStartedAt >= 0f) meter.total / maxOf(1f, outputEndedAt - outputStartedAt) else 0f
    }

    private var currentTokens = 0f

    internal fun updateCurrentTokens(tokens: Float) { currentTokens = tokens }
    internal fun currentTokenEstimate(): Float = currentTokens

    internal fun applyActualRate(tokens: Int, durationMs: Long) {
        if (tokens > 0 && durationMs > 0) lastRunAverage = tokens * 1000f / durationMs
    }

    private fun resolveActual(): GenerationMotionPhase? {
        val base = basePhase ?: return null
        if (base == GenerationMotionPhase.APPROVAL) return base
        val pending = retry ?: return base
        return if (clock - retryStartedAt < pending.delayMs / 1000f) GenerationMotionPhase.RETRY else base
    }

    /** 写入真实阶段，同时记下首字前的起点与工具轮次。 */
    private fun publishActual(next: GenerationMotionPhase?) {
        if (next != trackedPhase) {
            if (next == GenerationMotionPhase.WAIT) waitStartedAt = clock
            if (next == GenerationMotionPhase.TOOL) toolRounds++
            trackedPhase = next
        }
        actual = next
    }

    internal fun step(dt: Float) {
        clock += dt
        if (source == Source.DEMO) {
            stepDemo(dt)
        } else {
            publishActual(resolveActual())
        }
        meter.step(dt)
        heldRateClock += dt
        if (heldRateClock >= ScopeReadoutHoldSeconds) {
            heldRateClock = 0f
            heldRate = meter.rate
        }
        controller.step(dt, actual)
        if (displayed != controller.shown) displayed = controller.shown
    }

    /** 演示脚本：首字前 → 思考 → 流式 → 工具（含三次连续短调用）→ 等待审批 → 工具 → 重试 → 首字前 → 流式，循环。 */
    private fun stepDemo(dt: Float) {
        val t = clock.mod(DemoScriptSeconds)
        if (t < lastDemoTime) toolRounds = 0
        lastDemoTime = t
        var start = 0f
        var segment = DemoScript.first()
        var segmentStart = 0f
        for (s in DemoScript) {
            if (t < start + s.seconds) {
                segment = s
                segmentStart = start
                break
            }
            start += s.seconds
        }
        val phase = segment.phase
        publishActual(phase)
        if (demoThinking != segment.thinking) demoThinking = segment.thinking
        demoRetryLeft = if (phase == GenerationMotionPhase.RETRY) clamp01(1 - (t - segmentStart) / segment.seconds) else 0f
        if (phase == GenerationMotionPhase.STREAM) {
            val stall = valueNoise(clock * 0.8f + 7f) < 0.14f
            // 思考段出字慢一些，正文段快一些，预览能看出速率联动。
            val base = if (segment.thinking) 10.8f else 19.2f
            val rate = if (stall) 0f else base * (0.3f + 1.4f * valueNoise(clock * 0.5f + 3f))
            demoTokens += rate * dt
            meter.report(demoTokens)
        }
    }

    private var demoTokens = 0f
    private var lastDemoTime = 0f

    private class DemoSegment(val phase: GenerationMotionPhase, val seconds: Float, val thinking: Boolean = false)

    internal companion object {
        /** 点阵屏每秒 Token 数读数的刷新间隔。 */
        const val ScopeReadoutHoldSeconds = 0.25f
        private const val DemoStreamStart = 1.6f
        private const val DemoRetrySeconds = 2.6f
        private val DemoScript = listOf(
            DemoSegment(GenerationMotionPhase.WAIT, 1.6f),
            DemoSegment(GenerationMotionPhase.STREAM, 2.4f, thinking = true),
            DemoSegment(GenerationMotionPhase.STREAM, 3.6f),
            DemoSegment(GenerationMotionPhase.TOOL, 2f),
            DemoSegment(GenerationMotionPhase.WAIT, 0.12f),
            DemoSegment(GenerationMotionPhase.TOOL, 0.18f),
            DemoSegment(GenerationMotionPhase.WAIT, 0.1f),
            DemoSegment(GenerationMotionPhase.TOOL, 0.15f),
            DemoSegment(GenerationMotionPhase.WAIT, 0.9f),
            DemoSegment(GenerationMotionPhase.APPROVAL, 2.6f),
            DemoSegment(GenerationMotionPhase.TOOL, 1.2f),
            DemoSegment(GenerationMotionPhase.RETRY, DemoRetrySeconds),
            DemoSegment(GenerationMotionPhase.WAIT, 0.9f),
            DemoSegment(GenerationMotionPhase.STREAM, 3.6f),
        )
        private val DemoScriptSeconds = DemoScript.sumOf { it.seconds.toDouble() }.toFloat()

        val Ambient = GenerationMotion(Source.AMBIENT)

        /** 阶段固定为 [phase] 的指示状态；Token 速率、点阵读数取自 [delegate]（通常是会话的实时状态）。 */
        fun pinned(phase: GenerationMotionPhase, delegate: GenerationMotion): GenerationMotion =
            GenerationMotion(Source.PINNED, pinned = phase, delegate = delegate)
    }
}

/** 当前界面的生成动效状态；会话界面提供实时状态，其余位置为固定流式的环境状态。 */
val LocalGenerationMotion = staticCompositionLocalOf { GenerationMotion.Ambient }

/**
 * 会话界面的实时生成动效：[phase] 为 [generationMotionPhase] 的结果，[retry] 为正在等待的自动重试，
 * [outputTokens] 为已到达的估算 Token 数；[tokenInput] 提供正文与思考增量及可校准的用量。只在有进行中的生成时逐帧推进；
 * 结束后各阶段权重停在最后一帧，浮岛光效与点阵屏随生成态动画一起淡出。
 */
@Composable
internal fun rememberGenerationMotion(phase: GenerationMotionPhase?, retry: GenerationRetry?, outputTokens: Float = 0f, tokenInput: TokenMotionInput? = null, sessionKey: String? = null): GenerationMotion {
    val motion = remember(sessionKey) { GenerationMotion(GenerationMotion.Source.LIVE) }
    val tokenTracker = remember(sessionKey) { TokenMotionTracker(ProcessTokenRateCalibration.value) }
    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    var active by remember { mutableStateOf(false) }
    SideEffect {
        tokenInput?.let { tokenTracker.update(phase != null, it, motion) }
        motion.update(phase, retry, if (tokenInput != null) motion.currentTokenEstimate() else outputTokens, animate)
        tokenInput?.let { tokenTracker.finish(it, motion) }
        active = phase != null
    }
    LaunchedEffect(motion, animate) {
        if (!animate) return@LaunchedEffect
        snapshotFlow { active }.collect { running ->
            if (!running) return@collect
            var last = -1L
            while (active) {
                withInfiniteAnimationFrameNanos { now ->
                    if (last > 0) motion.step(((now - last) / 1_000_000_000f).coerceAtMost(0.1f))
                    last = now
                }
            }
        }
    }
    return motion
}

/** 设置页预览用：按演示脚本轮播五个阶段，从思考段开始。系统关闭动画时停在流式。 */
@Composable
internal fun rememberDemoGenerationMotion(): GenerationMotion {
    val motion = remember { GenerationMotion(GenerationMotion.Source.DEMO) }
    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    LaunchedEffect(motion, animate) {
        if (!animate) return@LaunchedEffect
        var last = -1L
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                if (last > 0) motion.step(((now - last) / 1_000_000_000f).coerceAtMost(0.1f))
                last = now
            }
        }
    }
    return motion
}
