package com.promenar.infinity.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.motion.GenerationMotionPhase
import com.promenar.infinity.ui.common.motion.IndicatorPainter
import com.promenar.infinity.ui.common.motion.LocalGenerationMotion
import com.promenar.infinity.ui.common.motion.clamp01
import com.promenar.infinity.ui.common.motion.evenBlink
import com.promenar.infinity.ui.common.motion.fract
import com.promenar.infinity.ui.common.motion.slowBlink
import com.promenar.infinity.ui.common.motion.drawGenerationIndicator
import com.promenar.infinity.ui.common.motion.indicatorDesignSize
import com.promenar.infinity.ui.common.motion.rememberMotionClock
import com.promenar.infinity.ui.common.motion.rememberMotionPalette
import com.promenar.infinity.ui.common.network.rememberAnimationsEnabled
import com.promenar.infinity.ui.theme.GenerationIndicatorStyle
import com.promenar.infinity.ui.theme.LocalInfinityMotionStyles
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 一轮波浪的时长、相邻圆点的相位差，以及每个圆点在一轮中处于“鼓起”状态的比例。 */
internal const val GenerationDotsPeriodMillis = 1200
internal const val GenerationDotsStagger = 0.125f
private const val GenerationDotsActiveFraction = 0.6f
private const val GenerationDotsRestAlpha = 0.35f

/**
 * 第 [index] 个圆点在相位 [phase]（0..1）时的鼓起程度，0 为静息、1 为最高点。
 * 每个圆点只在一轮的前 60% 内做一次半正弦起落，其余时间静息，三点依次错开形成波浪。
 */
internal fun generationDotLift(phase: Float, index: Int): Float {
    val local = (phase - index * GenerationDotsStagger).mod(1f)
    if (local >= GenerationDotsActiveFraction) return 0f
    return sin(PI.toFloat() * local / GenerationDotsActiveFraction).coerceIn(0f, 1f)
}

/**
 * 两色在色相环上沿较短弧线的中点，饱和度与明度取平均。
 * 主色与第三色常为互补色相，直接混合 RGB 或感知色彩会落到灰色；沿色相环取中点保持鲜明。
 */
internal fun hueMidpoint(a: Color, b: Color): Color {
    val (hueA, satA, valA) = a.toHsv()
    val (hueB, satB, valB) = b.toHsv()
    // 一端是灰色时没有色相可言，直接取另一端的色相。
    val hue = when {
        satA < 0.01f -> hueB
        satB < 0.01f -> hueA
        else -> {
            var delta = hueB - hueA
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            (hueA + delta / 2f).mod(360f)
        }
    }
    return Color.hsv(hue, (satA + satB) / 2f, (valA + valB) / 2f, (a.alpha + b.alpha) / 2f)
}

private fun Color.toHsv(): Triple<Float, Float, Float> {
    val maxC = max(red, max(green, blue))
    val delta = maxC - min(red, min(green, blue))
    val hue = when {
        delta < 1e-4f -> 0f
        maxC == red -> 60f * ((green - blue) / delta).mod(6f)
        maxC == green -> 60f * ((blue - red) / delta + 2f)
        else -> 60f * ((red - green) / delta + 4f)
    }
    return Triple(hue, if (maxC <= 0f) 0f else delta / maxC, maxC)
}

/**
 * 生成状态指示：按外观设置选的样式与当前生成阶段（[LocalGenerationMotion]）绘制，会话气泡、正文末尾与首页会话列表都经由这里。
 * 三点波浪的尺寸由 [dotSize]、[gap]、[lift] 决定；灯语样式按 [dotSize] 相对 6dp 等比缩放。
 * 系统关闭动画或静态预览时显示静止的画面。读屏报“正在生成回复”。
 */
@Composable
fun GenerationDots(
    modifier: Modifier = Modifier,
    dotSize: Dp = 6.dp,
    gap: Dp = 5.dp,
    lift: Dp = 4.dp,
    style: GenerationIndicatorStyle = LocalInfinityMotionStyles.current.indicator,
) {
    if (style == GenerationIndicatorStyle.WAVE) {
        WaveDots(modifier, dotSize, gap, lift)
    } else {
        LedIndicator(style, dotSize / 6.dp, modifier)
    }
}

@Composable
private fun LedIndicator(style: GenerationIndicatorStyle, scale: Float, modifier: Modifier) {
    val description = stringResource(R.string.chat_cd_generating_response)
    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    val clock = rememberMotionClock(running = animate)
    val palette = rememberMotionPalette()
    val motion = LocalGenerationMotion.current
    val painter = remember { IndicatorPainter() }
    val designSize = indicatorDesignSize(style)
    Canvas(
        modifier
            .size(designSize.width * scale, designSize.height * scale)
            .semantics { contentDescription = description },
    ) {
        // 静止时停在 0.37 秒：各灯有亮有灭，截图内容固定。
        val time = if (animate) clock.time else 0.37f
        drawGenerationIndicator(style, time, density * scale, palette, painter, motion)
    }
}

/**
 * 三点波浪：首字前只用主色、2.4 秒一轮的慢浪；流式（含只有思考内容到达时）颜色从主色过渡到第三色，速率越高浪越快、跳得越高；
 * 工具执行时第三色一点一拍依次跳格；等待审批时三点第三色慢闪；重试时三点压暗一起等间隔闪。
 * 静止时三点不跳，按当前阶段着色。
 */
@Composable
private fun WaveDots(modifier: Modifier, dotSize: Dp, gap: Dp, lift: Dp) {
    val description = stringResource(R.string.chat_cd_generating_response)
    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    val clock = rememberMotionClock(running = animate)
    val motion = LocalGenerationMotion.current
    val palette = rememberMotionPalette()
    val colors = remember(palette.a, palette.mid, palette.b) { listOf(palette.a, palette.mid, palette.b) }
    val painter = remember { IndicatorPainter() }
    Canvas(
        modifier
            .size(width = dotSize * 3 + gap * 2, height = dotSize + lift)
            .semantics { contentDescription = description },
    ) {
        val radius = dotSize.toPx() / 2f
        val step = dotSize.toPx() + gap.toPx()
        val restY = size.height - radius
        val liftPx = lift.toPx()
        if (!animate) {
            val phase = motion.shown ?: GenerationMotionPhase.STREAM
            for (index in 0..2) {
                val dotColor = when (phase) {
                    GenerationMotionPhase.TOOL -> palette.b
                    GenerationMotionPhase.APPROVAL -> palette.approval
                    else -> colors[index]
                }
                drawCircle(dotColor, radius, Offset(radius + step * index, restY))
            }
            return@Canvas
        }
        // 时间只在绘制阶段读取，动画只触发重绘。
        val t = clock.time
        painter.advance(t)
        val act = motion.act(t)
        for (phase in GenerationMotionPhase.entries) {
            val weight = motion.weight(phase)
            if (weight <= 0.004f) continue
            for (index in 0..2) {
                val (raised, color, alpha) = waveDot(phase, index, t, act, painter, colors, palette.approval)
                drawCircle(
                    color = color,
                    radius = radius,
                    center = Offset(radius + step * index, restY - liftPx * raised),
                    alpha = clamp01(alpha) * weight,
                )
            }
        }
    }
}

/** 第 [index] 个圆点在 [phase] 下的鼓起程度、颜色与透明度。 */
private fun waveDot(
    phase: GenerationMotionPhase,
    index: Int,
    t: Float,
    act: Float,
    painter: IndicatorPainter,
    colors: List<Color>,
    approvalColor: Color,
): Triple<Float, Color, Float> = when (phase) {
    GenerationMotionPhase.WAIT -> {
        val raised = generationDotLift(fract(t / 2.4f), index)
        Triple(raised * 0.55f, colors[0], 0.3f + 0.5f * raised)
    }
    GenerationMotionPhase.STREAM -> {
        val wave = fract(painter.acc(IndicatorPainter.AccWave, (0.45f + 1.1f * act) * 1000f / GenerationDotsPeriodMillis))
        val raised = generationDotLift(wave, index) * (0.45f + 0.55f * act)
        Triple(raised, colors[index], GenerationDotsRestAlpha + (1f - GenerationDotsRestAlpha) * raised)
    }
    GenerationMotionPhase.TOOL -> {
        val on = floor(t * 5).toInt() % 3 == index
        Triple(if (on) 0.8f else 0f, colors[2], if (on) 1f else 0.3f)
    }
    GenerationMotionPhase.APPROVAL -> Triple(0f, approvalColor, 0.4f + 0.6f * slowBlink(t))
    GenerationMotionPhase.RETRY -> Triple(0f, colors[0], 0.15f + 0.35f * evenBlink(t))
}
