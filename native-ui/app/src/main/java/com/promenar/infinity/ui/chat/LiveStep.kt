package com.promenar.infinity.ui.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.R
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.domain.ask.AskUserLimits
import com.promenar.infinity.domain.generation.GenerationPhase
import com.promenar.infinity.ui.common.GenerationDots
import com.promenar.infinity.ui.common.motion.GenerationMotion
import com.promenar.infinity.ui.common.motion.GenerationMotionPhase
import com.promenar.infinity.ui.common.motion.LocalGenerationMotion
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────────
//  进行中的一步：同一时刻会话流里只有一个元素在“进行中”，生成动画与计时挂在它身上
// ─────────────────────────────────────────────────────────────────

/** 会话流中正在进行的那一步的种类。 */
enum class LiveStepKind {
    /** 工具正在执行：动画与计时挂在该工具行的标题上。 */
    TOOL,
    /** 等待用户批准工具调用：审批卡上方显示“已等待 m:ss”。 */
    APPROVAL,
    /** 等待用户回答 `ask_user`：提问卡上方显示“已等待 m:ss”。 */
    QUESTION,
    /** 推理正在流式到达：思考行本身显示“思考中 · m:ss”。 */
    THINKING,
    /** 正文正在流式输出：动画紧跟正文末尾。 */
    CONTENT,
    /** 模型正在输出工具调用参数，工具尚未开始执行：流末尾一行“正在准备工具调用”。 */
    PREPARING_TOOL,
    /** 请求已发出尚未收到输出，或工具结束后模型的下一轮尚未开始输出：流末尾一行“等待模型响应”。 */
    WAITING_MODEL,
}

/** 等待模型超过这个时长仍无输出时改为警示样式，并提供停止入口。 */
internal const val LiveStepStallThresholdMs = 30_000L

/**
 * 当前进行中的一步：显示在哪个元素上（[kind] 与 [targetId]）、动画阶段 [motionPhase]、计时起点 [startedAt]。
 */
data class LiveStep(
    val kind: LiveStepKind,
    val motionPhase: GenerationMotionPhase,
    /** 计时起点，墙钟毫秒。思考行沿用自身的思考计时、正文不计时，二者为 null。 */
    val startedAt: Long? = null,
    /**
     * 挂载目标：工具为展示副本中的调用步骤 id（`消息id::步骤id`，与工具行内的调用步骤一致）；
     * 思考、正文、审批与提问为所属助手消息 id；流末尾的等待行为 null。
     */
    val targetId: String? = null,
) {
    /** 进行中元素的说明文字；工具行沿用自身标题，正文不显示文字，二者为 null。 */
    @get:StringRes
    internal val labelRes: Int?
        get() = when (kind) {
            LiveStepKind.WAITING_MODEL -> R.string.live_step_waiting_model
            LiveStepKind.PREPARING_TOOL -> R.string.live_step_preparing_tool
            LiveStepKind.APPROVAL, LiveStepKind.QUESTION -> R.string.live_step_waited
            LiveStepKind.THINKING -> R.string.live_step_thinking_elapsed
            LiveStepKind.TOOL, LiveStepKind.CONTENT -> null
        }

    /** 流末尾单独占一行的种类；其余挂在已有的工具行、思考行、正文或卡片上。 */
    internal val isTailRow: Boolean get() = kind == LiveStepKind.WAITING_MODEL || kind == LiveStepKind.PREPARING_TOOL

    /** 从计时起点到 [nowMs] 的整秒数；没有起点时为 null，时钟回拨时不为负。 */
    internal fun elapsedSeconds(nowMs: Long): Long? = startedAt?.let { (nowMs - it).coerceAtLeast(0) / 1_000 }

    /** 等待模型已超过 [LiveStepStallThresholdMs] 仍无输出。只有“等待模型响应”会进入警示。 */
    internal fun isStalled(nowMs: Long): Boolean =
        kind == LiveStepKind.WAITING_MODEL && startedAt != null && nowMs - startedAt >= LiveStepStallThresholdMs
}

/** 判定进行中元素所需的会话信号；只为最后一个助手分组构造。 */
internal data class LiveTurnSignals(
    /** 当前生成任务的阶段；没有任务或尚未建立任务时为 null，此时按消息内容推断。 */
    val phase: GenerationPhase?,
    /** 本组生成仍在进行（不含等待批准与等待回答）。 */
    val isGenerating: Boolean,
    /** 当前目标消息已有流式到达的正文。 */
    val hasStreamingContent: Boolean,
    /** 本组有显示中的待批准请求（提交中的请求卡片已收起，不算）。 */
    val awaitingApproval: Boolean = false,
    /** 待批准请求所属的助手消息；为 null 时按本组最后一条助手消息。 */
    val approvalMessageId: String? = null,
    /** 本组有等待回答的 `ask_user` 提问。 */
    val awaitingAnswer: Boolean = false,
    val questionMessageId: String? = null,
    /** 正在产生实时输出的调用步骤 id（未限定消息作用域）；审批通过后的调用在生成任务外执行，靠它判断仍在运行。 */
    val liveToolKeys: Set<String> = emptySet(),
    /** 本次生成任务开始的墙钟时间。 */
    val generationStartedAt: Long? = null,
)

/** 等待用户处理、由会话流底部的专用卡片承载的工具；它们未完成时不算“正在运行”。 */
private val UserCardTools = setOf(AskUserLimits.TOOL_NAME, com.promenar.infinity.domain.branch.BranchLimits.TOOL_NAME)

/**
 * 判定本组当前唯一的进行中元素；本轮已结束且没有待处理的批准、提问或实时输出时返回 null。
 *
 * 优先级：正在运行的工具 → 待批准 → 待回答 → 按生成阶段与消息内容判定模型侧的状态。
 * 多个工具同时未完成时取调用时间最早的一个（并行时它决定整组还要等多久；串行时它就是正在跑的那个），时间相同取先出现的。
 */
internal fun resolveLiveStep(messages: List<Message>, signals: LiveTurnSignals): LiveStep? {
    val assistants = messages.filter { it.role == MessageRole.ASSISTANT && !it.isSessionTimelineRecord }
    val finalAssistant = assistants.lastOrNull()
    val turnActive = signals.isGenerating || signals.awaitingApproval || signals.awaitingAnswer || signals.liveToolKeys.isNotEmpty()
    if (!turnActive) return null

    runningTool(assistants, finalAssistant, signals)?.let { return it }
    if (signals.awaitingApproval) {
        val owner = assistants.lastOrNull { it.id == signals.approvalMessageId } ?: finalAssistant
        return LiveStep(LiveStepKind.APPROVAL, GenerationMotionPhase.APPROVAL, owner?.let(::lastActivityAt), owner?.id)
    }
    if (signals.awaitingAnswer) {
        val owner = assistants.lastOrNull { it.id == signals.questionMessageId } ?: finalAssistant
        return LiveStep(LiveStepKind.QUESTION, GenerationMotionPhase.APPROVAL, owner?.let(::lastActivityAt), owner?.id)
    }
    if (!signals.isGenerating) return null

    val waiting = LiveStep(LiveStepKind.WAITING_MODEL, GenerationMotionPhase.WAIT, modelWaitStart(assistants, finalAssistant, signals))
    return when (signals.phase) {
        GenerationPhase.QUEUED,
        GenerationPhase.PREPARING,
        GenerationPhase.BUILDING_CONTEXT,
        GenerationPhase.CONNECTING,
        // 工具阶段却没有未完成的调用：工具已结束，正在等模型的下一轮。
        GenerationPhase.EXECUTING_TOOLS -> waiting
        GenerationPhase.POST_PROCESSING ->
            finalAssistant?.takeIf { it.content.isNotBlank() }
                ?.let { LiveStep(LiveStepKind.CONTENT, GenerationMotionPhase.WAIT, targetId = it.id) }
        // 等待批准或回答但卡片不在本组（或正在提交），以及终态：本组没有进行中的元素。
        GenerationPhase.WAITING_APPROVAL,
        GenerationPhase.WAITING_INPUT,
        GenerationPhase.COMPLETED,
        GenerationPhase.FAILED,
        GenerationPhase.CANCELLED,
        GenerationPhase.PERSISTENCE_FAILED -> null
        GenerationPhase.THINKING,
        GenerationPhase.STREAMING,
        null -> modelOutputStep(finalAssistant, signals, waiting)
    }
}

/** 模型正在输出（或刚建立连接）时按目标消息的内容判定：工具调用参数 → 正文 → 推理 → 仍在等待。 */
private fun modelOutputStep(finalAssistant: Message?, signals: LiveTurnSignals, waiting: LiveStep): LiveStep {
    val final = finalAssistant ?: return waiting
    // 流式期间工具调用先写进消息的 toolCalls，开始执行时才登记调用步骤；有调用尚未登记说明参数还在输出。
    val recorded = final.executionSteps.orEmpty().mapNotNullTo(HashSet()) { it.toolCallId }
    if (final.toolCalls.orEmpty().any { it.id !in recorded }) {
        return waiting.copy(kind = LiveStepKind.PREPARING_TOOL, motionPhase = GenerationMotionPhase.STREAM)
    }
    if (signals.hasStreamingContent || final.content.isNotBlank()) {
        return LiveStep(LiveStepKind.CONTENT, GenerationMotionPhase.STREAM, targetId = final.id)
    }
    if (!final.reasoning.isNullOrBlank()) {
        return LiveStep(LiveStepKind.THINKING, GenerationMotionPhase.STREAM, targetId = final.id)
    }
    return waiting
}

private fun runningTool(assistants: List<Message>, finalAssistant: Message?, signals: LiveTurnSignals): LiveStep? {
    val executingPhase = signals.isGenerating && (signals.phase == null || signals.phase == GenerationPhase.EXECUTING_TOOLS)
    var picked: Pair<String, com.promenar.infinity.data.model.ExecutionStep>? = null
    assistants.forEach { message ->
        toolInvocations(message.executionSteps.orEmpty()).forEach { invocation ->
            val call = invocation.call ?: return@forEach
            if (invocation.complete || invocation.name in UserCardTools) return@forEach
            val live = call.id in signals.liveToolKeys
            val executing = executingPhase && message.id == finalAssistant?.id && call.timestamp > 0
            if (!live && !executing) return@forEach
            val current = picked
            if (current == null || callOrder(call) < callOrder(current.second)) picked = message.id to call
        }
    }
    val (messageId, call) = picked ?: return null
    return LiveStep(
        kind = LiveStepKind.TOOL,
        motionPhase = GenerationMotionPhase.TOOL,
        startedAt = call.timestamp.takeIf { it > 0 },
        targetId = "$messageId::${call.id}",
    )
}

/** 没有可信时间的调用排在最后。 */
private fun callOrder(call: com.promenar.infinity.data.model.ExecutionStep): Long = call.timestamp.takeIf { it > 0 } ?: Long.MAX_VALUE

/** 一条助手消息最后一次有动静的时刻：创建时间与其工具步骤时间中最晚的一个。 */
private fun lastActivityAt(message: Message): Long? =
    (listOf(message.createdAt) + message.executionSteps.orEmpty().map { it.timestamp }).filter { it > 0 }.maxOrNull()

/**
 * 等待模型的起点：本次任务开始、最后一条助手消息创建（续轮会新建消息）与最后一个工具步骤结束中最晚的时刻。
 */
private fun modelWaitStart(assistants: List<Message>, finalAssistant: Message?, signals: LiveTurnSignals): Long? =
    buildList {
        signals.generationStartedAt?.let(::add)
        finalAssistant?.createdAt?.let(::add)
        assistants.forEach { message -> message.executionSteps.orEmpty().forEach { add(it.timestamp) } }
    }.filter { it > 0 }.maxOrNull()

/** 计时文字：分:秒，秒补足两位。 */
internal fun liveClockText(seconds: Long): String = "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)

// ─────────────────────────────────────────────────────────────────
//  界面
// ─────────────────────────────────────────────────────────────────

private const val LIVE_STEP_WAITING_ROW_TAG = "CHAT_LIVE_STEP_WAITING"
private const val LIVE_STEP_STALLED_HINT_TAG = "CHAT_LIVE_STEP_STALLED_HINT"
private const val LIVE_STEP_STOP_TAG = "CHAT_LIVE_STEP_STOP"
private const val LIVE_STEP_ELAPSED_TAG = "CHAT_LIVE_STEP_ELAPSED"
private const val LIVE_STEP_WAIT_BADGE_TAG = "CHAT_LIVE_STEP_WAIT_BADGE"

/** 固定“现在”的墙钟时间，供截图与预览得到确定的计时；为 null 时按真实时钟每秒刷新。 */
internal val LocalLiveStepNow = staticCompositionLocalOf<Long?> { null }

/**
 * 计时用的“现在”：每秒只在整秒边界刷新一次，只重组读取它的元素。
 * 静态预览没有固定时间时停在 [startedAt]（显示 0:00），保证画面稳定。
 */
@Composable
private fun rememberLiveNow(startedAt: Long): State<Long> {
    val fixed = LocalLiveStepNow.current
    val inspection = LocalInspectionMode.current
    return produceState(initialValue = fixed ?: if (inspection) startedAt else System.currentTimeMillis(), startedAt, fixed, inspection) {
        if (fixed != null || inspection) return@produceState
        while (true) {
            val now = System.currentTimeMillis()
            value = now
            delay(1_000 - (now - startedAt).mod(1_000L))
        }
    }
}

/** 进行中元素前的生成动画：复用外观设置选定的指示样式，阶段固定为 [phase]，速率读数取会话的实时状态。 */
@Composable
internal fun LiveStepDots(phase: GenerationMotionPhase, modifier: Modifier = Modifier, dotSize: Dp = 4.dp) {
    val base = LocalGenerationMotion.current
    val pinned = remember(phase, base) { GenerationMotion.pinned(phase, base) }
    CompositionLocalProvider(LocalGenerationMotion provides pinned) {
        GenerationDots(modifier = modifier, dotSize = dotSize, gap = dotSize * 0.75f, lift = dotSize * 0.75f)
    }
}

private val LiveClockStyle: TextStyle
    @Composable get() = InfinityTypography.labelLarge.copy(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
        fontFeatureSettings = "tnum",
    )

/** 从 [startedAt] 起算的 m:ss 计时，等宽数字；每秒只重组自身。 */
@Composable
internal fun LiveElapsedText(startedAt: Long, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val now by rememberLiveNow(startedAt)
    Text(
        text = liveClockText((now - startedAt).coerceAtLeast(0) / 1_000),
        style = LiveClockStyle,
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.testTag(LIVE_STEP_ELAPSED_TAG),
    )
}

/** 工具行标题前的动画，挂在 [CompactStepRow] 的前置位。 */
@Composable
internal fun RowScope.LiveStepLeading(phase: GenerationMotionPhase) {
    LiveStepDots(phase, Modifier.padding(end = 8.dp).align(Alignment.CenterVertically))
}

/** 工具行标题后的计时，挂在 [CompactStepRow] 的后置位。 */
@Composable
internal fun RowScope.LiveStepTrailing(startedAt: Long) {
    LiveElapsedText(
        startedAt,
        modifier = Modifier.padding(start = 6.dp).align(Alignment.CenterVertically),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
    )
}

/**
 * 工具行没有标题行可挂（浏览器、知识检索、记忆写入逐条显示）时，在逐条步骤上方单独显示的动画与计时。
 */
@Composable
internal fun LiveToolTicker(step: LiveStep) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = CompactStepRowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveStepLeading(step.motionPhase)
        step.startedAt?.let { LiveStepTrailing(it) }
    }
}

/**
 * 流末尾的等待行：“等待模型响应”或“正在准备工具调用”，前为动画、后为计时。
 * 等待模型超过 30 秒仍无输出时文字改为警示色，下方说明可能原因，并提供 [onStopGeneration] 只停止本轮、不重跑已执行的工具。
 */
@Composable
internal fun LiveWaitingRow(step: LiveStep, onStopGeneration: (() -> Unit)?, modifier: Modifier = Modifier) {
    val startedAt = step.startedAt
    val now = startedAt?.let { rememberLiveNow(it).value }
    val stalled = now != null && step.isStalled(now)
    val warning = MaterialTheme.infinityDomainColors.warning
    val textColor = if (stalled) warning else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    Column(modifier = modifier.fillMaxWidth().testTag(LIVE_STEP_WAITING_ROW_TAG)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = CompactStepRowHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveStepDots(step.motionPhase, Modifier.padding(end = 8.dp).testTag(com.promenar.infinity.ui.testing.UiTags.CHAT_GENERATING_INDICATOR))
            step.labelRes?.let { label ->
                Text(
                    text = stringResource(label),
                    style = LiveClockStyle,
                    color = textColor,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (startedAt != null && now != null) {
                Text(
                    text = liveClockText((now - startedAt).coerceAtLeast(0) / 1_000),
                    style = LiveClockStyle,
                    color = textColor,
                    maxLines = 1,
                    softWrap = false,
                    // 计时每秒变化，读屏不逐秒播报。
                    modifier = Modifier.padding(start = 6.dp).clearAndSetSemantics {}.testTag(LIVE_STEP_ELAPSED_TAG),
                )
            }
        }
        if (stalled) {
            // 说明在左，停止图标按钮在行尾；说明过长时自行换行，不挤压按钮。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.live_step_stalled_hint),
                    style = InfinityTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).testTag(LIVE_STEP_STALLED_HINT_TAG),
                )
                if (onStopGeneration != null) {
                    IconButton(onClick = onStopGeneration, modifier = Modifier.testTag(LIVE_STEP_STOP_TAG)) {
                        Icon(
                            Icons.Rounded.Stop,
                            contentDescription = stringResource(R.string.live_step_stop),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 审批卡、提问卡上方的“已等待 m:ss”，前为等待审批阶段的动画。
 * 卡片外层是读屏即时播报区域，这一行只做视觉提示，不进入无障碍树，避免每秒播报。
 */
@Composable
internal fun LiveWaitBadge(step: LiveStep, modifier: Modifier = Modifier) {
    val startedAt = step.startedAt
    val now = startedAt?.let { rememberLiveNow(it).value }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .clearAndSetSemantics {}
            .testTag(LIVE_STEP_WAIT_BADGE_TAG),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveStepDots(step.motionPhase, Modifier.padding(end = 8.dp))
        val elapsed = if (startedAt != null && now != null) liveClockText((now - startedAt).coerceAtLeast(0) / 1_000) else null
        if (elapsed != null) {
            Text(
                text = stringResource(R.string.live_step_waited, elapsed),
                style = LiveClockStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                maxLines = 1,
            )
        }
    }
}
