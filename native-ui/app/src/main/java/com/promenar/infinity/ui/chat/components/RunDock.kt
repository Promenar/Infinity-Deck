package com.promenar.infinity.ui.chat.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.R
import com.promenar.infinity.ui.chat.ComposerLightsPainter
import com.promenar.infinity.ui.chat.ComposerTextInset
import com.promenar.infinity.ui.chat.LocalComposerGlow
import com.promenar.infinity.ui.chat.ScopeScreenSpec
import com.promenar.infinity.ui.chat.drawScopeFrame
import com.promenar.infinity.ui.chat.drawScopeGrid
import com.promenar.infinity.ui.common.motion.GenerationMotionPhase
import com.promenar.infinity.ui.common.motion.LocalGenerationMotion
import com.promenar.infinity.ui.common.motion.rememberMotionPalette
import com.promenar.infinity.ui.common.pressFeedback
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.LocalInfinityMotionStyles
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlin.math.max
import kotlinx.coroutines.delay

/**
 * 输入浮岛顶层的运行坞：一行显示当前在做什么、计划进度（“2/5”）与本轮用时，最左是后台任务圆环（或完成、暂停标记），
 * 右侧是状态面板入口与点阵波形屏（[RunDockScope]）；点按整行打开运行详情。待批准数由工具行的执行模式盾牌承载，锁定触摸在顶栏。
 * 与输入区之间的细灯带由浮岛绘制。[startedAt] 为本轮开始时间（毫秒），不为 null 时用时每秒刷新，只重组运行坞这一行。
 */
@Composable
internal fun RunDockRow(
    state: RunDockUiState,
    onOpen: (RunDetailTab?) -> Unit,
    modifier: Modifier = Modifier,
    startedAt: Long? = null,
) {
    val label = runDockLabelText(state.label)
    val elapsedSeconds by rememberRunElapsedSeconds(startedAt)
    val meta = runDockMetaText(state, elapsedSeconds)
    val openLabel = stringResource(R.string.run_dock_open)
    val jobsLabel = when {
        state.runningJobs > 0 -> pluralStringResource(R.plurals.agent_jobs_running, state.runningJobs, state.runningJobs)
        state.enabledSchedules > 0 -> pluralStringResource(R.plurals.agent_schedules_count, state.enabledSchedules, state.enabledSchedules)
        else -> null
    }
    val showBoard = state.hasStateBoard && state.label !is RunDockLabel.Board
    val showScope = LocalInfinityMotionStyles.current.runDockScope
    val leading = jobsLabel != null || state.tone == RunDockTone.DONE || state.tone == RunDockTone.PAUSED || state.tone == RunDockTone.IDLE
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth().testTag(UiTags.CHAT_RUN_DOCK)) {
        // 点阵屏宽度随剩余空间伸缩：先给文字留够（至多 150dp 随字号放大、不超过行宽 42%），余下的给屏，限在 72～140dp。
        val labelReserve = minOf(RunDockLabelReserve * fontScale, maxWidth * 0.42f) + if (leading) RunDockLeadStart + RunDockLeadSize + RunDockGap else ComposerTextInset
        val boardReserve = if (showBoard) RunDockBoardChipWidth + RunDockGap else 0.dp
        val scopeWidth = (maxWidth - labelReserve - boardReserve - RunDockEndPadding - RunDockGap)
            .coerceIn(ScopeScreenSpec.MinWidthDp.dp, ScopeScreenSpec.MaxWidthDp.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                .clickable(onClickLabel = openLabel, role = Role.Button) { onOpen(runDockDefaultTab(state.label)) }
                // 没有前置标记时文字与输入区文字左缘对齐；有标记时标记圆环与工具行“＋”笔画左缘对齐。
                .padding(start = if (leading) RunDockLeadStart else ComposerTextInset, end = RunDockEndPadding)
                .semantics(mergeDescendants = true) {
                    contentDescription = listOfNotNull(label, meta, jobsLabel).filter(String::isNotBlank).joinToString("，")
                }
                .testTag(UiTags.CHAT_RUN_DOCK_MAIN),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RunDockGap),
        ) {
            if (leading) {
                Box(Modifier.size(RunDockLeadSize).testTag(if (jobsLabel != null) UiTags.CHAT_RUN_DOCK_JOBS else UiTags.CHAT_RUN_DOCK_LEAD), contentAlignment = Alignment.Center) {
                    when {
                        state.runningJobs > 0 -> RunDockJobsRing(state.runningJobs, spinning = true)
                        state.enabledSchedules > 0 -> RunDockJobsRing(state.enabledSchedules, spinning = false)
                        else -> RunDockDot(state.tone)
                    }
                }
            }
            // 标签与进度同一行文字：窄屏大字号时先截掉尾部的进度与用时，保住当前在做什么。
            val metaStyle = MaterialTheme.typography.labelMedium
            val metaColor = MaterialTheme.colorScheme.onSurfaceVariant
            Text(
                text = buildAnnotatedString {
                    append(label)
                    if (meta.isNotBlank()) {
                        append("\u2002")
                        withStyle(SpanStyle(fontSize = metaStyle.fontSize, color = metaColor, fontFeatureSettings = "tnum")) { append(meta) }
                    }
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showBoard) {
                RunDockChip(
                    onClick = { onOpen(RunDetailTab.STATE) },
                    label = stringResource(R.string.state_open),
                    tag = UiTags.CHAT_RUN_DOCK_STATE,
                    square = true,
                ) {
                    Icon(Icons.Rounded.TableChart, contentDescription = null, modifier = Modifier.size(15.dp))
                }
            }
            if (showScope) RunDockScope(runDockScopeMode(state.tone), Modifier.width(scopeWidth))
        }
    }
}

/** 运行坞文字至少保留的宽度（按 1 倍字号），随系统字号放大。 */
private val RunDockLabelReserve = 150.dp
private val RunDockBoardChipWidth = 32.dp
private val RunDockGap = 6.dp

/** 前置标记盒尺寸；圆环 24dp 居中，盒边比圆环多 2dp。 */
private val RunDockLeadSize = 28.dp
private val RunDockLeadStart = ComposerTextInset - 2.dp
private val RunDockEndPadding = 10.dp

/** 后台任务圆环：圆环中间是数量；有任务在运行时转动，只有定时任务时静止。 */
@Composable
private fun RunDockJobsRing(count: Int, spinning: Boolean) {
    if (spinning) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    } else {
        CircularProgressIndicator(
            progress = { 1f },
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.outline,
        )
    }
    Text(
        if (count > 99) "99" else "$count",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
    )
}

/** 运行坞点阵屏的显示方式，由运行坞状态决定。 */
internal enum class RunDockScopeMode {
    /** 生成中或等待审批：按生成阶段实时绘制。 */
    LIVE,
    /** 计划完成：停在本轮波形，数字区为本轮平均速率。 */
    REST,
    /** 已暂停：停在本轮波形并调暗。 */
    PAUSED,
    /** 只有后台任务：只留熄灭的底格。 */
    OFF,
}

internal fun runDockScopeMode(tone: RunDockTone): RunDockScopeMode = when (tone) {
    RunDockTone.RUNNING, RunDockTone.WAITING -> RunDockScopeMode.LIVE
    RunDockTone.DONE -> RunDockScopeMode.REST
    RunDockTone.PAUSED -> RunDockScopeMode.PAUSED
    RunDockTone.IDLE -> RunDockScopeMode.OFF
}

/** 运行坞点阵屏的高度：7 行点阵加屏框留白，上下再各留一点。 */
private val RunDockScopeHeight = 30.dp

/**
 * 运行坞右侧的点阵波形屏：常驻一层熄灭的底格，屏的位置不会因状态切换空出来；生成中按阶段绘制（等待审批时“!”慢闪、
 * 波形区为静止的第三色基线），计划完成时停在本轮波形，暂停时停在本轮波形并调暗。
 * 生成进度与时钟取自浮岛的 [LocalComposerGlow]，并把自己的中心回报给浮岛作为环境光柔光的中心。纯装饰，不进入无障碍树。
 */
@Composable
internal fun RunDockScope(mode: RunDockScopeMode, modifier: Modifier = Modifier) {
    val glow = LocalComposerGlow.current
    val motion = LocalGenerationMotion.current
    val digits = LocalInfinityMotionStyles.current.scopeDigits
    val palette = rememberMotionPalette()
    val painter = remember { ComposerLightsPainter() }
    // 待批准的“!”与盾牌同用警示色，不随主题第二灯色变化。
    val approvalColors = listOf(palette.a, MaterialTheme.infinityDomainColors.warning)
    if (glow != null) {
        DisposableEffect(glow) { onDispose { glow.scopeCenterInRoot = Offset.Unspecified } }
    }
    Canvas(
        modifier
            .height(RunDockScopeHeight)
            .onGloballyPositioned { if (glow != null) glow.scopeCenterInRoot = it.boundsInRoot().center }
            .clearAndSetSemantics {}
            .testTag(UiTags.CHAT_RUN_DOCK_SCOPE),
    ) {
        val k = density
        val pitch = ScopeScreenSpec.PitchDp * k
        val inset = ScopeScreenSpec.InsetDp * k
        val rows = ScopeScreenSpec.Rows
        val cols = max(8, ((size.width - 2 * inset) / pitch).toInt())
        val sx = size.width - (cols * pitch + 2 * inset)
        val sy = (size.height - (rows * pitch + 2 * inset)) / 2
        // 没有浮岛状态（单独预览运行坞）时，生成中按满幅显示。
        val gen = glow?.generating?.value ?: if (mode == RunDockScopeMode.LIVE) 1f else 0f
        val time = glow?.timeSeconds ?: 0f
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            drawScopeGrid(native, k, sx, sy, cols, rows, palette, painter)
            if (gen > 0f) for (phase in GenerationMotionPhase.entries) {
                val weight = motion.weight(phase)
                if (weight <= 0.004f) continue
                drawScopeFrame(
                    native, k, sx, sy, cols, rows, phase, gen * weight, time, motion, palette, painter, digits,
                    colors = if (phase == GenerationMotionPhase.APPROVAL) approvalColors else palette.leds, drawOff = false,
                )
            }
            val rest = when (mode) {
                RunDockScopeMode.REST -> 0.8f
                RunDockScopeMode.PAUSED -> 0.4f
                else -> 0f
            } * (1f - gen)
            if (rest > 0.004f) {
                drawScopeFrame(
                    native, k, sx, sy, cols, rows, GenerationMotionPhase.STREAM, rest, time, motion, palette, painter, digits,
                    rest = true, drawOff = false,
                )
            }
        }
    }
}

@Composable
private fun RunDockChip(
    onClick: () -> Unit,
    label: String,
    tag: String,
    modifier: Modifier = Modifier,
    square: Boolean = false,
    container: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    body: @Composable () -> Unit,
) {
    // 外层 48dp 触控区，内层 32dp 可见胶囊；按压反馈画在胶囊内。
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .heightIn(min = InfinitySpacing.MinimumTouchTarget)
            .clickable(interactionSource = interaction, indication = null, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(50), color = container, contentColor = content) {
            Row(
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .pressFeedback(interaction, RoundedCornerShape(50))
                    .padding(horizontal = if (square) 8.5.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) { body() }
        }
    }
}

/**
 * 运行坞标签前的静态状态标记。执行中与等待中不画：浮岛光效、点阵屏和标签文字已经表达了生成状态，
 * 再加一颗脉动圆点既重复又与其余动效风格不一致。
 */
@Composable
internal fun RunDockDot(tone: RunDockTone, modifier: Modifier = Modifier) {
    when (tone) {
        RunDockTone.RUNNING, RunDockTone.WAITING -> Unit
        RunDockTone.DONE -> Icon(
            Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier.size(16.dp),
        )
        RunDockTone.PAUSED -> TaskStatusDot(pulsing = false, color = MaterialTheme.colorScheme.tertiary, modifier = modifier)
        RunDockTone.IDLE -> TaskStatusDot(pulsing = false, color = MaterialTheme.colorScheme.outline, modifier = modifier)
    }
}

@Composable
internal fun runDockLabelText(label: RunDockLabel): String = when (label) {
    is RunDockLabel.Step -> label.title
    RunDockLabel.Running -> stringResource(R.string.chat_progress_running)
    RunDockLabel.WaitingApproval -> stringResource(R.string.run_dock_waiting_approval)
    RunDockLabel.WaitingAnswer -> stringResource(R.string.run_dock_waiting_answer)
    RunDockLabel.PlanCompleted -> stringResource(R.string.run_dock_plan_completed)
    is RunDockLabel.Paused -> if (label.step.isBlank()) stringResource(R.string.run_dock_paused)
        else stringResource(R.string.run_dock_paused_step, label.step)
    RunDockLabel.BackgroundOnly -> stringResource(R.string.run_dock_background)
    is RunDockLabel.Board -> label.title
}

/** 运行坞文字尾部：计划进度“2/5”与本轮用时（[runDockElapsedText]），以“ · ”相连。 */
internal fun runDockMetaText(state: RunDockUiState, elapsedSeconds: Long?): String = buildList {
    if (state.planTotal != null) add("${state.planDone ?: 0}/${state.planTotal}")
    elapsedSeconds?.let { add(runDockElapsedText(it)) }
}.joinToString(" · ")  // wrap-check: allow 运行坞单行省略；触控锁中作为一段短计时

/** 本轮用时：不到 1 小时为 m:ss，以上为 h:mm:ss；显示时配等宽数字，逐秒变化不跳动。 */
internal fun runDockElapsedText(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val hours = s / 3600
    val minutes = s % 3600 / 60
    val secs = s % 60
    fun twoDigits(value: Long) = if (value < 10) "0$value" else "$value"
    return if (hours > 0) "$hours:${twoDigits(minutes)}:${twoDigits(secs)}" else "$minutes:${twoDigits(secs)}"
}

/** 运行坞用时的刷新间隔（毫秒）。 */
internal const val RunDockClockTickMillis = 1_000L

/** 从 [startedAt]（毫秒）起算的整秒数，每秒刷新一次并对齐到整秒边界；[startedAt] 为 null 时为 null。 */
@Composable
internal fun rememberRunElapsedSeconds(startedAt: Long?): State<Long?> {
    val elapsed = remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(startedAt) {
        if (startedAt == null) {
            elapsed.value = null
            return@LaunchedEffect
        }
        while (true) {
            val passed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0)
            elapsed.value = passed / 1000
            delay(RunDockClockTickMillis - passed % RunDockClockTickMillis)
        }
    }
    return elapsed
}
