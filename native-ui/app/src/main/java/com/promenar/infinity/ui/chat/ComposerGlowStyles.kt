package com.promenar.infinity.ui.chat

import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.motion.GenerationMotion
import com.promenar.infinity.ui.common.motion.GenerationMotionPhase
import com.promenar.infinity.ui.common.motion.LedBatch
import com.promenar.infinity.ui.common.motion.LocalGenerationMotion
import com.promenar.infinity.ui.common.motion.MotionPalette
import com.promenar.infinity.ui.common.motion.actOn
import com.promenar.infinity.ui.common.motion.breath
import com.promenar.infinity.ui.common.motion.clamp01
import com.promenar.infinity.ui.common.motion.evenBlink
import com.promenar.infinity.ui.common.motion.fract
import com.promenar.infinity.ui.common.motion.heartbeat
import com.promenar.infinity.ui.common.motion.motionHash
import com.promenar.infinity.ui.common.motion.pingPong
import com.promenar.infinity.ui.common.motion.rememberMotionPalette
import com.promenar.infinity.ui.common.motion.slowBlink
import com.promenar.infinity.ui.common.motion.smoothStep
import com.promenar.infinity.ui.theme.ComposerGlowStyle
import com.promenar.infinity.ui.theme.LocalInfinityMotionStyles
import com.promenar.infinity.ui.theme.InfinityPureWhite
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** 呼吸节奏：2.6 秒一个周期，0..1。 */
internal fun composerBreath(timeSeconds: Float): Float = 0.5f - 0.5f * cos(2 * PI.toFloat() * timeSeconds / 2.6f)

/**
 * 浮岛内的停止键位置：停止键 40dp，距右边缘与底边都是 [ComposerToolbarInset]（8dp），
 * 因此圆心距右边缘、底边都是 28dp，与浮岛圆角同心。灯效都避开以它为圆心、半径 21dp 的区域。
 */
internal fun composerStopCenter(width: Float, height: Float, dp: Float): Pair<Float, Float> {
    val inset = (ComposerToolbarInset.value + 20) * dp
    return (width - inset) to (height - inset)
}

/** 输入行右侧点阵屏与柔光的右缘距浮岛右边缘：停止键右缘再往里 2dp。 */
internal const val ComposerInputRowEndDp = 10f

/** 单行输入文字的中心距浮岛底边：底部留白 8dp + 工具行 40dp + 输入行 48dp 里文字下方 9dp + 半个 22sp 行高。 */
internal const val ComposerInputRowCenterDp = 68f

/** 浮岛外圈与描边所需的可复用画笔；每个浮岛持有一份。 */
internal class ComposerOutlinePaints {
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    /** 模糊半径按 1px 取整缓存，呼吸时不逐帧新建滤镜。 */
    private val blurs = HashMap<Int, BlurMaskFilter>()
    fun blur(radius: Float): BlurMaskFilter {
        val key = radius.roundToInt().coerceAtLeast(1)
        return blurs.getOrPut(key) { BlurMaskFilter(key.toFloat(), BlurMaskFilter.Blur.NORMAL) }
    }
}

/** 贴着浮岛边缘的模糊光圈，画在浮岛底色之下；[colorB] 不同时为主色到第三色的斜向渐变。 */
internal fun DrawScope.drawHaloRing(alpha: Float, blur: Float, width: Float, cornerPx: Float, colorA: Color, colorB: Color, paints: ComposerOutlinePaints) {
    if (alpha <= 0.004f) return
    val paint = paints.halo
    paint.shader = if (colorA == colorB) null else LinearGradient(0f, 0f, size.width, size.height, colorA.toArgb(), colorB.toArgb(), Shader.TileMode.CLAMP)
    paint.color = colorA.toArgb()
    paint.alpha = (255 * clamp01(alpha)).roundToInt()
    paint.strokeWidth = width * density
    paint.maskFilter = paints.blur(blur * density)
    drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, cornerPx, cornerPx, paint) }
}

/** 贴边描边，内缩半个线宽；[colorB] 不同时为主色到第三色的斜向渐变。 */
internal fun DrawScope.drawEdgeStroke(alpha: Float, width: Float, cornerPx: Float, colorA: Color, colorB: Color, paints: ComposerOutlinePaints) {
    if (alpha <= 0.004f) return
    val paint = paints.stroke
    val inset = width * density / 2
    paint.shader = if (colorA == colorB) null else LinearGradient(0f, 0f, size.width, size.height, colorA.toArgb(), colorB.toArgb(), Shader.TileMode.CLAMP)
    paint.color = colorA.toArgb()
    paint.alpha = (255 * clamp01(alpha)).roundToInt()
    paint.strokeWidth = width * density
    drawIntoCanvas { it.nativeCanvas.drawRoundRect(inset, inset, size.width - inset, size.height - inset, cornerPx - inset, cornerPx - inset, paint) }
}

/** 潮汐呼吸在 [phase] 下的呼吸量（0..1）与渐变两端颜色。 */
private fun breathePhase(phase: GenerationMotionPhase, state: ComposerGlowState, palette: MotionPalette, colorA: Color, colorB: Color): Triple<Float, Color, Color> {
    val t = state.timeSeconds
    return when (phase) {
        GenerationMotionPhase.WAIT -> Triple(breath(t, 3.6f) * 0.75f, colorA, palette.mid)
        GenerationMotionPhase.STREAM -> Triple((0.5f - 0.5f * cos(2 * PI.toFloat() * state.breathPhase)) * (0.7f + 0.3f * state.act), colorA, colorB)
        GenerationMotionPhase.TOOL -> Triple(min(1f, heartbeat(t)), colorB, colorB)
        GenerationMotionPhase.APPROVAL -> Triple(slowBlink(t) * 0.85f, colorB, colorB)
        GenerationMotionPhase.RETRY -> Triple(0.35f * evenBlink(t), colorA, colorA)
    }
}

/**
 * 潮汐呼吸的外圈光晕：画在浮岛底色之下。首字前 3.6 秒一次的慢呼吸；流式时呼吸随速率加快，最快约 1.3 秒；
 * 工具执行时第三色心跳式双拍；等待审批时第三色慢闪；重试时不画光晕。
 */
internal fun DrawScope.drawBreatheHalo(gen: Float, state: ComposerGlowState, motion: GenerationMotion, cornerPx: Float, palette: MotionPalette, colorA: Color, colorB: Color, paints: ComposerOutlinePaints) {
    for (phase in GenerationMotionPhase.entries) {
        val weight = motion.weight(phase)
        if (weight <= 0.004f || phase == GenerationMotionPhase.RETRY) continue
        val (b, c1, c2) = breathePhase(phase, state, palette, colorA, colorB)
        drawHaloRing(gen * weight * (0.15f + 0.6f * b), 8 + 12 * b, 5 + 8 * b, cornerPx, c1, c2, paints)
    }
}

/** 潮汐呼吸的贴边描边，透明度随各阶段的呼吸涨落。 */
internal fun DrawScope.drawBreatheStroke(gen: Float, state: ComposerGlowState, motion: GenerationMotion, cornerPx: Float, palette: MotionPalette, colorA: Color, colorB: Color, paints: ComposerOutlinePaints) {
    for (phase in GenerationMotionPhase.entries) {
        val weight = motion.weight(phase)
        if (weight <= 0.004f) continue
        val (b, c1, c2) = breathePhase(phase, state, palette, colorA, colorB)
        val alpha = if (phase == GenerationMotionPhase.RETRY) 0.1f + 0.5f * b else 0.35f + 0.65f * b
        drawEdgeStroke(gen * weight * alpha, 1.6f, cornerPx, c1, c2, paints)
    }
}

/**
 * 灯语样式外面那圈安静的描边，视线交给浮岛里的灯：首字前与流式为主色呼吸，工具执行为第三色快呼吸，
 * 等待审批为第三色慢闪，重试为压暗的等间隔闪。
 */
internal fun DrawScope.drawPhaseBorder(gen: Float, time: Float, motion: GenerationMotion, cornerPx: Float, palette: MotionPalette, paints: ComposerOutlinePaints) {
    for (phase in GenerationMotionPhase.entries) {
        val weight = motion.weight(phase)
        if (weight <= 0.004f) continue
        val (color, alpha) = when (phase) {
            GenerationMotionPhase.WAIT -> palette.a to 0.12f + 0.18f * breath(time, 3.2f)
            GenerationMotionPhase.STREAM -> palette.a to 0.15f + 0.2f * breath(time, 2.6f)
            GenerationMotionPhase.TOOL -> palette.b to 0.2f + 0.25f * breath(time, 1.2f)
            GenerationMotionPhase.APPROVAL -> palette.b to 0.25f + 0.5f * slowBlink(time)
            GenerationMotionPhase.RETRY -> palette.a to 0.08f + 0.17f * evenBlink(time)
        }
        drawEdgeStroke(gen * weight * alpha, 1f, cornerPx, color, color, paints)
    }
}

/** 浮岛内灯效的可复用绘制对象。 */
internal class ComposerLightsPainter {
    val leds4 = LedBatch(levels = 4, glowFrom = 1)
    val leds5 = LedBatch(levels = 5, glowFrom = 2)
    val leds6 = LedBatch(levels = 6, glowFrom = 3)
    val holes = Array(7) { Path() }
    val holePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val ledX = FloatArray(6)
    val ledY = FloatArray(6)
    val ledLevel = FloatArray(6)
    val ledWarm = BooleanArray(6)
    val packets = FloatArray(15)
    var screenLit = FloatArray(0)
    var screenAmber = BooleanArray(0)
    /** 点阵屏读数的字符缓冲，逐帧复用。 */
    val readout = CharArray(ScopeReadoutCapacity)
    /** 扫光点阵写入行上每颗灯珠最近一次被写入头走过的时间（秒）；只在浮岛宽度变化时重建。 */
    var trailAt = FloatArray(0)
    /** 上一帧写入头的位置（格），用来补记这一帧走过的灯珠。 */
    var lastHead = 0f
}

/**
 * 浮岛内的灯效层：放在浮岛底色之上、输入内容之下，只在网孔背光、底沿数据流与扫光点阵三种样式下绘制，
 * 按当前生成阶段（[LocalGenerationMotion]）分别绘制并交叉淡入淡出。[state] 的生成进度为 0 时什么都不画。
 * 灯珠墙类样式只铺到输入区（[ComposerGlowState.lightsTop] 以下），不铺到运行坞文字下方。点阵波形屏在运行坞里（[RunDockScope]）。
 */
@Composable
internal fun ComposerIslandLights(state: ComposerGlowState, style: ComposerGlowStyle, modifier: Modifier = Modifier) {
    if (style == ComposerGlowStyle.AURORA || style == ComposerGlowStyle.BREATHE || style == ComposerGlowStyle.MATERIAL_ONLY) return
    val palette = rememberMotionPalette()
    val motion = LocalGenerationMotion.current
    val painter = remember { ComposerLightsPainter() }
    Canvas(modifier.clearAndSetSemantics {}) {
        val gen = state.generating.value
        if (gen <= 0f) return@Canvas
        val time = state.timeSeconds
        drawIntoCanvas { canvas ->
            for (phase in GenerationMotionPhase.entries) {
                val weight = motion.weight(phase)
                if (weight <= 0.004f) continue
                val alpha = gen * weight
                when (style) {
                    ComposerGlowStyle.MESH -> drawMesh(canvas, phase, alpha, time, state, palette, painter)
                    ComposerGlowStyle.BOTTOM_STRIP -> drawBottomStrip(canvas, phase, alpha, time, state, motion, palette, painter)
                    ComposerGlowStyle.SWEEP_GRID -> drawSweepGrid(canvas, phase, alpha, time, state, palette, painter)
                    else -> Unit
                }
            }
        }
    }
}

/**
 * 网孔背光：浮岛右半像服务器前面板的冲孔网罩，几颗状态灯藏在网后，光从网孔里透出来。
 * 首字前只有一颗 LINK 灯在网后呼吸；流式时网后 ACT 灯按速率乱闪；工具执行时一颗第三色灯在网后来回寻道；
 * 等待审批时网后整排第三色灯慢闪；重试时只剩一颗灯等间隔闪。
 */
private fun DrawScope.drawMesh(canvas: ComposeCanvas, phase: GenerationMotionPhase, alpha: Float, t: Float, state: ComposerGlowState, palette: MotionPalette, p: ComposerLightsPainter) {
    val k = density
    val w = size.width
    val h = size.height
    val (sx, sy) = composerStopCenter(w, h, k)
    // 运行坞在浮岛顶层时，网罩只铺在输入区。
    val top = state.lightsTop.coerceIn(0f, h)
    val cy = (top + h) / 2
    val x0 = w * 0.46f
    val x1 = sx - 30 * k
    val warm = phase == GenerationMotionPhase.TOOL || phase == GenerationMotionPhase.APPROVAL
    val seek = 1 + (pingPong(t / 1.1f) * 4).roundToInt()
    for (i in 0 until 6) {
        p.ledX[i] = x0 + (x1 - x0) * (i / 5f)
        p.ledY[i] = cy + (if (i % 2 == 1) 7 else -7) * k
        p.ledWarm[i] = false
        p.ledLevel[i] = when (phase) {
            GenerationMotionPhase.WAIT -> if (i == 0) 0.3f + 0.7f * breath(t, 1.6f) else 0f
            GenerationMotionPhase.STREAM -> {
                p.ledWarm[i] = i == 3
                if (i == 0 || actOn(i, t, state.act, i * 2.1f)) 1f else 0f
            }
            GenerationMotionPhase.TOOL -> when (i) {
                seek -> 1f.also { p.ledWarm[i] = true }
                0 -> 0.45f
                else -> 0f
            }
            GenerationMotionPhase.APPROVAL -> (if (slowBlink(t) > 0.5f) 1f else 0.3f).also { p.ledWarm[i] = true }
            GenerationMotionPhase.RETRY -> if (i == 0) 0.6f * evenBlink(t) else 0f
        }
    }
    val native = canvas.nativeCanvas
    val radius = 22 * k
    for (i in 0 until 6) {
        val level = p.ledLevel[i]
        if (level <= 0f) continue
        val color = if (p.ledWarm[i]) palette.b else palette.a
        p.glowPaint.shader = RadialGradient(p.ledX[i], p.ledY[i], radius, color.copy(alpha = clamp01((if (palette.dark) 0.22f else 0.16f) * level * alpha)).toArgb(), color.copy(alpha = 0f).toArgb(), Shader.TileMode.CLAMP)
        native.drawCircle(p.ledX[i], p.ledY[i], radius, p.glowPaint)
    }
    val pitch = 4.2f * k
    val holeRadius = 0.85f * k
    val sigma2 = 2 * (8 * k).pow(2)
    val base = if (phase == GenerationMotionPhase.RETRY) 0.05f else 0.1f
    p.holes.forEach { it.rewind() }
    var row = 0
    var y = top + pitch / 2
    while (y < h) {
        var x = if (row % 2 == 1) pitch / 2 else 0f
        while (x < w) {
            val fade = smoothStep(0.32f, 0.6f, x / w)
            if (fade > 0f && hypot(x - sx, y - sy) >= 21 * k) {
                var v = base
                for (i in 0 until 6) if (p.ledLevel[i] > 0f) {
                    val dx = x - p.ledX[i]
                    val dy = y - p.ledY[i]
                    v += p.ledLevel[i] * exp(-(dx * dx + dy * dy) / sigma2)
                }
                v = min(1f, v) * fade
                if (v >= 0.03f) p.holes[min(6, (v * 7).toInt())].addCircle(x, y, holeRadius, Path.Direction.CW)
            }
            x += pitch
        }
        row++
        y += pitch * 0.866f
    }
    val holeColor = if (warm) palette.b else palette.a
    val hotColor = if (!warm) palette.hot else if (palette.dark) lerp(palette.b, InfinityPureWhite, 0.45f) else palette.b
    p.holePaint.blendMode = BlendMode.SRC_OVER
    for (i in 0 until 7) {
        if (p.holes[i].isEmpty) continue
        val color = if (i >= 5) hotColor else holeColor
        p.holePaint.color = color.copy(alpha = clamp01((i + 0.5f) / 7 * (if (palette.dark) 0.95f else 0.85f) * alpha)).toArgb()
        native.drawPath(p.holes[i], p.holePaint)
    }
}

/**
 * 底沿数据流：底沿内侧两排细灯，像 1U 服务器前面板下方的灯带，每排最左一颗是常亮的 LINK 灯。
 * 首字前一个光点在上排来回扫；流式时数据包的数量与流速跟随速率，速率为 0 时只剩 LINK；
 * 工具执行时第三色分段逐段点亮；等待审批时整条灯带第三色慢闪；重试时上排变成退避倒计时进度条。
 */
private fun DrawScope.drawBottomStrip(canvas: ComposeCanvas, phase: GenerationMotionPhase, alpha: Float, t: Float, state: ComposerGlowState, motion: GenerationMotion, palette: MotionPalette, p: ComposerLightsPainter) {
    val k = density
    val (sx, _) = composerStopCenter(size.width, size.height, k)
    drawLedStrip(
        canvas.nativeCanvas, k, phase, alpha, t, state, motion, palette, p,
        x0 = ComposerIslandCorner.toPx() * 0.75f, x1 = sx - 26 * k,
        yTop = size.height - 11 * k, rowGap = 4 * k, rows = 2,
    )
}

/**
 * 一到两排细灯的数据流，底沿数据流与运行坞细灯带共用：第 0 排在 [yTop]，往下每排隔 [rowGap]。
 * [phase] 为 null 时只画熄灭的灯珠（不在生成时的分隔线）。
 */
private fun drawLedStrip(
    native: android.graphics.Canvas,
    k: Float,
    phase: GenerationMotionPhase?,
    alpha: Float,
    t: Float,
    state: ComposerGlowState,
    motion: GenerationMotion,
    palette: MotionPalette,
    p: ComposerLightsPainter,
    x0: Float,
    x1: Float,
    yTop: Float,
    rowGap: Float,
    rows: Int,
) {
    val act = state.act
    val pitch = 3.6f * k
    val sz = 1.9f * k
    val gz = 3.8f * k
    val cols = max(8, ((x1 - x0) / pitch).toInt())
    val span = cols * pitch
    val sweep = 1 + pingPong(t / 2.4f) * (cols - 2)
    val flow = state.stripPhase
    val packetCount = if (act < 0.02f) 0 else 1 + (4 * act).roundToInt()
    val segments = 4
    val segmentLength = max(2, (cols - 1) / segments)
    val litSegment = floor(t * 4).toInt() % segments
    val blink = slowBlink(t) > 0.5f
    val retryLeft = motion.retryLeft
    val leds = p.leds4
    leds.reset()
    val packets = p.packets
    for (r in 0 until rows) {
        val y = yTop + r * rowGap
        if (phase == GenerationMotionPhase.STREAM) {
            for (j in 0 until packetCount) {
                val len = (2 + floor(motionHash(r.toFloat(), j.toFloat()) * 5)) * pitch
                val speed = (28 + motionHash(j.toFloat(), r + 3f) * 46) * k
                val head = fract(motionHash(j.toFloat(), r + 7f) + flow * speed / (span + len)) * (span + len)
                packets[j * 3] = head - len
                packets[j * 3 + 1] = head
                packets[j * 3 + 2] = if (motionHash(j.toFloat(), r + 11f) < 0.2f) 1f else 0f
            }
        }
        for (c in 0 until cols) {
            val px = c * pitch + pitch / 2
            val x = x0 + px
            var intensity = 0f
            var amber = false
            if (phase == null) {
                Unit
            } else if (c == 0) {
                intensity = when (phase) {
                    GenerationMotionPhase.APPROVAL -> 0f
                    GenerationMotionPhase.RETRY -> 0.5f * evenBlink(t)
                    else -> 0.85f
                }
            } else when (phase) {
                GenerationMotionPhase.WAIT -> if (r == 0) {
                    val d = abs(c - sweep)
                    intensity = if (d < 0.5f) 1f else if (d < 1.5f) 0.45f else if (d < 2.5f) 0.18f else 0f
                }
                GenerationMotionPhase.STREAM -> {
                    for (j in 0 until packetCount) if (px > packets[j * 3] && px <= packets[j * 3 + 1]) {
                        intensity = 1f
                        amber = packets[j * 3 + 2] > 0f
                        break
                    }
                    if (intensity == 1f && motionHash(c * 7f + r, floor(t * (18 + 24 * act))) < 0.2f) intensity = 0.2f
                }
                GenerationMotionPhase.TOOL -> {
                    val segment = (c - 1) / segmentLength
                    val inSegment = (c - 1) % segmentLength
                    intensity = if (segment == litSegment && inSegment < segmentLength - 1) 1f else 0f
                    amber = true
                }
                GenerationMotionPhase.APPROVAL -> {
                    intensity = if (blink) 0.9f else 0.28f
                    amber = true
                }
                GenerationMotionPhase.RETRY -> if (r == 0) intensity = if ((c - 1f) / (cols - 1) < retryLeft) 0.45f else 0f
            }
            if (intensity < 0.1f) leds.offDot(x, y, sz) else leds.dot(if (amber) 1 else 0, intensity, x, y, sz, gz)
        }
    }
    leds.draw(native, palette.leds, palette.dark, blur = 3 * k, offColor = palette.a.copy(alpha = if (palette.dark) 0.1f else 0.16f), alpha = alpha)
}

/** 运行坞细灯带的高度与左右留白。 */
internal val ComposerDockStripHeight = 6.dp
private const val DockStripHorizontalDp = 14f

/**
 * 运行坞与输入区之间的一排小灯，与底沿数据流同一套数据包逻辑，随出字速率流动；
 * 不在生成时只留一排熄灭的灯珠当分隔线。
 */
@Composable
internal fun ComposerDockStrip(state: ComposerGlowState, modifier: Modifier = Modifier) {
    val palette = rememberMotionPalette()
    val motion = LocalGenerationMotion.current
    val painter = remember { ComposerLightsPainter() }
    Canvas(modifier.fillMaxWidth().height(ComposerDockStripHeight).clearAndSetSemantics {}) {
        val gen = state.generating.value
        val k = density
        val x0 = DockStripHorizontalDp * k
        val x1 = size.width - DockStripHorizontalDp * k
        val y = size.height / 2
        val time = state.timeSeconds
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            if (gen < 1f) drawLedStrip(native, k, null, 1f - gen, time, state, motion, palette, painter, x0, x1, y, 0f, rows = 1)
            if (gen > 0f) for (phase in GenerationMotionPhase.entries) {
                val weight = motion.weight(phase)
                if (weight <= 0.004f) continue
                drawLedStrip(native, k, phase, gen * weight, time, state, motion, palette, painter, x0, x1, y, 0f, rows = 1)
            }
        }
    }
}

/** 运行坞点阵波形屏的可调参数；尺寸单位为 dp。 */
internal object ScopeScreenSpec {
    /** 点阵行数。 */
    const val Rows = 7
    /** 屏宽随运行坞剩余空间在这两者之间伸缩。 */
    const val MinWidthDp = 72f
    const val MaxWidthDp = 140f
    const val PitchDp = 3.2f
    const val DotDp = 2f
    const val GlowDp = 3.6f
    /** 点阵与屏框之间的留白。 */
    const val InsetDp = 2f
    /** 数字区固定列宽：放得下最长的读数“99:59”，数字变化时波形区不跟着伸缩。 */
    const val ReadoutCols = 17
    /** 数字区与波形区之间空出的列数。 */
    const val ReadoutGapCols = 2
    /** 屏宽不足这么多列时不显示数字，波形铺满。 */
    const val MinColsForReadout = 30
}

/** 读数最长 5 个字符（“99:59”），留些余量。 */
internal const val ScopeReadoutCapacity = 8

/** 3×5 点阵字形，自上而下；窄字形（“.”与“:”）只占 1 列，取最低位。 */
private val ScreenGlyphs: Map<Char, IntArray> = mapOf(
    '0' to intArrayOf(0b111, 0b101, 0b101, 0b101, 0b111),
    '1' to intArrayOf(0b010, 0b110, 0b010, 0b010, 0b111),
    '2' to intArrayOf(0b111, 0b001, 0b111, 0b100, 0b111),
    '3' to intArrayOf(0b111, 0b001, 0b111, 0b001, 0b111),
    '4' to intArrayOf(0b101, 0b101, 0b111, 0b001, 0b001),
    '5' to intArrayOf(0b111, 0b100, 0b111, 0b001, 0b111),
    '6' to intArrayOf(0b111, 0b100, 0b111, 0b101, 0b111),
    '7' to intArrayOf(0b111, 0b001, 0b010, 0b010, 0b010),
    '8' to intArrayOf(0b111, 0b101, 0b111, 0b101, 0b111),
    '9' to intArrayOf(0b111, 0b101, 0b111, 0b001, 0b111),
    '!' to intArrayOf(0b010, 0b010, 0b010, 0b000, 0b010),
    '#' to intArrayOf(0b101, 0b111, 0b101, 0b111, 0b101),
    '.' to intArrayOf(0b0, 0b0, 0b0, 0b0, 0b1),
    ':' to intArrayOf(0b0, 0b1, 0b0, 0b1, 0b0),
)

/** 点阵屏能画出的字符；读数只会写入这些字符。 */
internal fun scopeGlyphSupported(ch: Char): Boolean = ch in ScreenGlyphs

private fun glyphWidth(ch: Char): Int = if (ch == '.' || ch == ':') 1 else 3

/** 读数占的列数：各字宽之和加字间 1 列。 */
internal fun scopeReadoutWidth(text: CharArray, length: Int): Int {
    var width = 0
    for (i in 0 until length) width += glyphWidth(text[i]) + if (i > 0) 1 else 0
    return width
}

private fun writeInt(value: Int, out: CharArray, start: Int): Int {
    var digits = 1
    var v = value
    while (v >= 10) {
        v /= 10
        digits++
    }
    var x = value
    for (i in digits - 1 downTo 0) {
        out[start + i] = '0' + x % 10
        x /= 10
    }
    return start + digits
}

/** 秒数读数：100 秒以内为整数秒，以上为 m:ss，封顶 99:59。返回写入后的位置。 */
internal fun writeSecondsReadout(seconds: Int, out: CharArray, start: Int = 0): Int {
    val s = seconds.coerceIn(0, 99 * 60 + 59)
    if (s < 100) return writeInt(s, out, start)
    var n = writeInt(s / 60, out, start)
    out[n++] = ':'
    out[n++] = '0' + (s % 60) / 10
    out[n++] = '0' + s % 10
    return n
}

/** 每秒 Token 数读数：不到 10 保留一位小数，以上取整，封顶 9999。返回写入后的位置。 */
internal fun writeRateReadout(rate: Float, out: CharArray, start: Int = 0): Int {
    val r = rate.coerceIn(0f, 9999f)
    if (r < 9.95f) {
        val tenths = (r * 10).roundToInt()
        var n = writeInt(tenths / 10, out, start)
        out[n++] = '.'
        out[n++] = '0' + tenths % 10
        return n
    }
    return writeInt(r.roundToInt(), out, start)
}

/**
 * 点阵屏在 [phase] 下的数字读数写入 [out]，返回字符数：首字前为已等待秒数，流式（含思考）为每秒 Token 数，
 * 工具执行为“#轮次”，等待审批为“!”，重试为倒计时秒数。
 */
internal fun scopeReadout(
    phase: GenerationMotionPhase,
    waitSeconds: Int,
    rate: Float,
    toolRound: Int,
    retrySeconds: Int,
    out: CharArray,
): Int = when (phase) {
    GenerationMotionPhase.WAIT -> writeSecondsReadout(waitSeconds, out)
    GenerationMotionPhase.STREAM -> writeRateReadout(rate, out)
    GenerationMotionPhase.TOOL -> {
        out[0] = '#'
        writeInt(toolRound.coerceIn(1, 99), out, 1)
    }
    GenerationMotionPhase.APPROVAL -> {
        out[0] = '!'
        1
    }
    GenerationMotionPhase.RETRY -> writeSecondsReadout(retrySeconds, out)
}

/**
 * 点阵波形屏的一帧：左上角 ([sx], [sy])、[cols] × [rows] 的点阵，像交换机前面板的流量显示；不画底板与描边，灯珠直接落在浮岛表面上。
 * 显示数字且屏够宽时左侧是数字区（[scopeReadout]），右侧是波形区；关闭数字时波形铺满整块屏，各阶段只保留图形。
 * 波形区：首字前底行一条带噪声的基线；流式（含思考）时柱状图就是实际出字速率，不断左移，最新两列在闪、越旧越暗；
 * 工具执行时第三色扫描线来回扫过；等待审批时第三色“!”慢闪（有数字区时“!”在数字区）；重试时底行是退避倒计时进度条。
 */
internal fun drawScopeFrame(
    native: android.graphics.Canvas,
    k: Float,
    sx: Float,
    sy: Float,
    cols: Int,
    rows: Int,
    phase: GenerationMotionPhase,
    alpha: Float,
    t: Float,
    motion: GenerationMotion,
    palette: MotionPalette,
    p: ComposerLightsPainter,
    digits: Boolean,
    /** 停在上一轮：画 [GenerationMotion.lastRunLevel] 的静止波形与平均速率，[phase] 不再起作用。 */
    rest: Boolean = false,
    /** 亮灯颜色（0 主色、1 第三色）。 */
    colors: List<Color> = palette.leds,
    /** 是否画熄灭的灯珠；运行坞点阵屏另画常驻底格，这里不重复。 */
    drawOff: Boolean = true,
    /**
     * 等待、工具与重试阶段是否显示数字（首字前秒数、工具轮次、重试倒计时）。为 false 时这些阶段波形铺满整块屏，
     * 只有流式速率、停在上一轮的平均速率与审批“!”保留数字区；运行坞用 false，步骤级计时由会话流里的状态行承担。
     */
    stepReadouts: Boolean = true,
) {
    if (alpha <= 0.004f) return
    val pitch = ScopeScreenSpec.PitchDp * k
    val inset = ScopeScreenSpec.InsetDp * k
    val sz = ScopeScreenSpec.DotDp * k
    val gz = ScopeScreenSpec.GlowDp * k
    if (p.screenLit.size != cols * rows) {
        p.screenLit = FloatArray(cols * rows)
        p.screenAmber = BooleanArray(cols * rows)
    } else {
        p.screenLit.fill(0f)
        p.screenAmber.fill(false)
    }
    val lit = p.screenLit
    val amber = p.screenAmber
    fun set(c: Int, r: Int, intensity: Float, isAmber: Boolean) {
        if (c < 0 || c >= cols || r < 0 || r >= rows) return
        val i = c * rows + r
        if (intensity > lit[i]) {
            lit[i] = intensity
            amber[i] = isAmber
        }
    }
    // 字形 5 行高，在屏内垂直居中；[endCol] 为最右一列，读数靠右对齐，数字变宽时向左长。
    fun glyphs(length: Int, endCol: Int, intensity: Float, isAmber: Boolean) {
        val text = p.readout
        val top = (rows - 5) / 2 + 4
        var c = endCol + 1 - scopeReadoutWidth(text, length)
        for (n in 0 until length) {
            val ch = text[n]
            val glyph = ScreenGlyphs[ch]
            val width = glyphWidth(ch)
            if (glyph != null) for (gy in 0 until 5) for (gx in 0 until width) {
                if (glyph[gy] shr (width - 1 - gx) and 1 == 1) set(c + gx, top - gy, intensity, isAmber)
            }
            c += width + 1
        }
    }
    val readout = digits && cols >= ScopeScreenSpec.MinColsForReadout &&
        (stepReadouts || rest || phase == GenerationMotionPhase.STREAM || phase == GenerationMotionPhase.APPROVAL)
    // 波形区起始列；没有数字区时从 0 开始铺满。
    val w0 = if (readout) ScopeScreenSpec.ReadoutCols + ScopeScreenSpec.ReadoutGapCols else 0
    val waveCols = cols - w0
    if (rest) {
        if (readout && motion.lastRunRate > 0f) glyphs(writeRateReadout(motion.lastRunRate, p.readout), ScopeScreenSpec.ReadoutCols - 1, 0.85f, false)
        for (c in w0 until cols) {
            val back = cols - 1 - c
            val level = motion.lastRunLevel(back)
            val height = if (level < 0.04f) 0 else max(1, (level * rows).roundToInt())
            val age = back.toFloat() / waveCols
            for (r in 0 until height) set(c, r, (if (r == height - 1) 0.9f else 0.65f) * (1 - 0.6f * age), false)
        }
    } else if (readout) {
        val length = scopeReadout(phase, motion.waitSeconds, motion.readoutRate, motion.toolRound, motion.retrySeconds, p.readout)
        val intensity = when (phase) {
            GenerationMotionPhase.STREAM -> 1f
            GenerationMotionPhase.WAIT -> 0.75f
            GenerationMotionPhase.TOOL -> 0.9f
            GenerationMotionPhase.APPROVAL -> if (slowBlink(t) > 0.5f) 1f else 0.35f
            GenerationMotionPhase.RETRY -> 0.4f + 0.35f * evenBlink(t)
        }
        val isAmber = phase == GenerationMotionPhase.TOOL || phase == GenerationMotionPhase.APPROVAL
        glyphs(length, ScopeScreenSpec.ReadoutCols - 1, intensity, isAmber)
    }
    if (!rest) when (phase) {
        GenerationMotionPhase.WAIT -> for (c in w0 until cols) {
            set(c, 0, 0.35f, false)
            if (motionHash(c.toFloat(), floor(t * 6)) < 0.1f) set(c, 1, 0.3f, false)
        }
        GenerationMotionPhase.STREAM -> {
            val now = floor(t / 0.09f)
            for (c in w0 until cols) {
                val back = cols - 1 - c
                val level = clamp01(motion.historyLevel(back, now - back) + (motionHash(now - back, 1.3f) - 0.5f) * 0.1f)
                val height = if (level < 0.04f) 0 else max(1, (level * rows).roundToInt())
                val age = back.toFloat() / waveCols
                for (r in 0 until height) {
                    var intensity = (if (r == height - 1) 1f else 0.75f) * (1 - 0.7f * age)
                    if (c >= cols - 2 && motionHash(c * 5f + r, floor(t * 30)) < 0.3f) intensity *= 0.3f
                    set(c, r, intensity, r == rows - 1)
                }
            }
        }
        GenerationMotionPhase.TOOL -> {
            val pos = w0 + (pingPong(t / 1.2f) * (waveCols - 1)).roundToInt()
            for (c in w0 until cols) {
                set(c, 0, 0.25f, false)
                val d = abs(c - pos)
                if (d <= 1) for (r in 0 until rows) set(c, r, if (d == 0) 1f else 0.35f, true)
            }
        }
        GenerationMotionPhase.APPROVAL -> if (readout) {
            for (c in w0 until cols) set(c, 0, 0.25f, true)
        } else {
            p.readout[0] = '!'
            glyphs(1, (cols + 3) / 2 - 1, if (slowBlink(t) > 0.5f) 1f else 0.35f, true)
        }
        GenerationMotionPhase.RETRY -> {
            val left = motion.retryLeft
            val blink = 0.4f + 0.35f * evenBlink(t)
            for (c in w0 until cols) set(c, 0, if ((c - w0 + 0.5f) / waveCols < left) blink else 0.12f, false)
        }
    }
    val leds = p.leds5
    leds.reset()
    for (c in 0 until cols) for (r in 0 until rows) {
        val x = sx + inset + c * pitch + pitch / 2
        val y = sy + inset + (rows - 1 - r) * pitch + pitch / 2
        val i = c * rows + r
        if (lit[i] < 0.1f) {
            if (drawOff) leds.offDot(x, y, sz)
        } else {
            leds.dot(if (amber[i]) 1 else 0, lit[i], x, y, sz, gz)
        }
    }
    leds.draw(native, colors, palette.dark, blur = 3 * k, offColor = if (drawOff) scopeOffColor(palette) else Color.Transparent, alpha = alpha)
}

/** 点阵屏熄灭灯珠的颜色。 */
internal fun scopeOffColor(palette: MotionPalette): Color = palette.a.copy(alpha = if (palette.dark) 0.1f else 0.14f)

/** 点阵屏的常驻底格：[cols] × [rows] 颗熄灭的灯珠，运行坞没有生成时也留着，屏的位置不会空出来。 */
internal fun drawScopeGrid(native: android.graphics.Canvas, k: Float, sx: Float, sy: Float, cols: Int, rows: Int, palette: MotionPalette, p: ComposerLightsPainter, alpha: Float = 1f) {
    val pitch = ScopeScreenSpec.PitchDp * k
    val inset = ScopeScreenSpec.InsetDp * k
    val sz = ScopeScreenSpec.DotDp * k
    val leds = p.leds5
    leds.reset()
    for (c in 0 until cols) for (r in 0 until rows) {
        leds.offDot(sx + inset + c * pitch + pitch / 2, sy + inset + r * pitch + pitch / 2, sz)
    }
    leds.draw(native, palette.leds, palette.dark, blur = 0f, offColor = scopeOffColor(palette), alpha = alpha)
}

/** 扫光点阵的可调参数，真机上再定。 */
internal object SweepGridSpec {
    /** 速率映射的指数：强度 = (速率 / 满档)^0.6，低速也有可见反馈，中速与满速拉开差距。 */
    const val RateExponent = 0.6f
    /** 流式时最多几道光带；同斜率、等距、同速移动，随速率从 1 道增到这个数。 */
    const val MaxBands = 3
    /** 写入头所在的固定行数，从输入区顶部数起。 */
    const val WriteRows = 3
    /** 每到一个 Token 写入头前进的格数。 */
    const val CellsPerToken = 1.1f / 0.6f
    /** 写入头追赶目标位置的时间常数（秒）：一批 Token 到达时平滑前进，不是瞬移。 */
    const val HeadEaseSeconds = 0.08f
    /** 余辉时长：低速 0.35 秒，满速 0.6 秒。 */
    const val TrailMinSeconds = 0.35f
    const val TrailMaxSeconds = 0.6f
    /** 余辉起始亮度。 */
    const val TrailPeak = 0.95f
    /** 闪点密度（灯珠占比）与亮灯闪烁比例，低速到满速。 */
    const val SparkleMin = 0.002f
    const val SparkleMax = 0.014f
    const val FlickerMin = 0.06f
    const val FlickerMax = 0.16f
}

/** 扫光点阵流式阶段的强度 0..1：出字速率强度按 [SweepGridSpec.RateExponent] 次方映射。 */
internal fun sweepRateLevel(act: Float): Float = clamp01(act).pow(SweepGridSpec.RateExponent)

/** 第 [band] 道（0 起）光带的亮度权重：强度 0 时只有第 0 道，满速时 [SweepGridSpec.MaxBands] 道全亮，中间平滑长出。 */
internal fun sweepBandWeight(level: Float, band: Int): Float = clamp01(1 + (SweepGridSpec.MaxBands - 1) * clamp01(level) - band)

/** 写入头余辉的时长（秒），随强度变长。 */
internal fun sweepTrailSeconds(level: Float): Float =
    SweepGridSpec.TrailMinSeconds + (SweepGridSpec.TrailMaxSeconds - SweepGridSpec.TrailMinSeconds) * clamp01(level)

/**
 * 写入头从上一帧位置走到 [head]（格）时，把途经的灯珠记为 [time] 时刻点亮；写入行共 [cells] 颗，走满一圈后回到第一行开头。
 * 位置回退（新一轮从头累计）时只更新基准。一帧最多补记一圈，不随积压无限循环。
 */
internal fun advanceWriteTrail(trailAt: FloatArray, lastHead: Float, head: Float, time: Float): Float {
    val cells = trailAt.size
    if (cells == 0 || head <= lastHead) return head
    val to = floor(head).toLong()
    var i = max(floor(lastHead).toLong() + 1, to - cells + 1)
    while (i <= to) {
        trailAt[i.mod(cells.toLong()).toInt()] = time
        i++
    }
    return head
}

/**
 * 扫光点阵：浮岛输入区里藏着一整面看不见的灯珠，光带扫到哪里，哪里的灯珠就亮起来。首字前细光带 4.8 秒慢扫一次；
 * 流式（含思考）时一个写入头沿输入区上部固定的几行灯珠前进，每到一批字前进相应格数，走过的灯珠留下余辉，一行走完换下一行；
 * 同斜率、等距的光带随速率从 1 道增到 3 道、同速移动，闪点与闪烁保持稀疏；速率越高，写入头越快、余辉越长、光带越宽越亮。
 * 工具执行时第三色竖条来回寻道；等待审批时停止键周围第三色灯慢闪；重试时光带停住、压暗等间隔闪。
 */
private fun DrawScope.drawSweepGrid(canvas: ComposeCanvas, phase: GenerationMotionPhase, alpha: Float, t: Float, state: ComposerGlowState, palette: MotionPalette, p: ComposerLightsPainter) {
    val k = density
    val w = size.width
    val h = size.height
    val level = state.sweepLevel
    val (sx, sy) = composerStopCenter(w, h, k)
    val pitch = 4.4f * k
    val sz = 1.5f * k
    val gz = 3.2f * k
    // 运行坞在浮岛顶层时，灯珠墙只铺在输入区。
    val top = state.lightsTop.coerceIn(0f, h)
    val cy = (top + h) / 2
    val span = w + 80 * k
    var bx = 0f
    var band = 14 * k
    var peak = 0.9f
    var slant = 0.7f
    var amberBand = false
    var sparkle = 0f
    var flicker = 0f
    val streaming = phase == GenerationMotionPhase.STREAM
    when (phase) {
        GenerationMotionPhase.WAIT -> {
            bx = -40 * k + span * fract(t / 4.8f)
            band = 10 * k
            peak = 0.55f
        }
        GenerationMotionPhase.STREAM -> {
            band = (10 + 6 * level) * k
            peak = 0.6f + 0.4f * level
            sparkle = SweepGridSpec.SparkleMin + (SweepGridSpec.SparkleMax - SweepGridSpec.SparkleMin) * level
            flicker = SweepGridSpec.FlickerMin + (SweepGridSpec.FlickerMax - SweepGridSpec.FlickerMin) * level
        }
        GenerationMotionPhase.TOOL -> {
            bx = 18 * k + (w - 80 * k) * pingPong(t / 1.4f)
            band = 5 * k
            slant = 0f
            amberBand = true
        }
        GenerationMotionPhase.RETRY -> {
            bx = w * 0.42f
            band = 10 * k
            peak = 0.4f * evenBlink(t)
        }
        GenerationMotionPhase.APPROVAL -> Unit
    }
    val cols = max(1, ((w - pitch / 2) / pitch).toInt() + 1)
    val firstRow = max(0, ceil((top - pitch / 2) / pitch).toInt())
    val trailSeconds = sweepTrailSeconds(level)
    if (streaming) {
        val cells = SweepGridSpec.WriteRows * cols
        if (p.trailAt.size != cells) {
            p.trailAt = FloatArray(cells)
            p.trailAt.fill(-1e6f)
            p.lastHead = state.writeHead
        }
        p.lastHead = advanceWriteTrail(p.trailAt, p.lastHead, state.writeHead, t)
    }
    val pulse = if (slowBlink(t) > 0.5f) 0.9f else 0.25f
    val leds = p.leds6
    leds.reset()
    var row = firstRow
    var y = pitch / 2 + firstRow * pitch
    while (y < h) {
        var x = pitch / 2
        var c = 0
        while (x < w) {
            val cell = row * cols + c
            val ds = hypot(x - sx, y - sy)
            if (ds < 21 * k) {
                x += pitch
                c++
                continue
            }
            var intensity: Float
            val isAmber: Boolean
            if (phase == GenerationMotionPhase.APPROVAL) {
                intensity = pulse * exp(-(ds - 21 * k) / (40 * k))
                isAmber = true
            } else {
                val slanted = (y - cy) * slant
                intensity = 0f
                if (streaming) {
                    for (j in 0 until SweepGridSpec.MaxBands) {
                        val weight = sweepBandWeight(level, j)
                        if (weight <= 0f) continue
                        val d = x - (-40 * k + span * fract(state.sweepPhase + j.toFloat() / SweepGridSpec.MaxBands)) + slanted
                        if (abs(d) < band * 2.5f) intensity = max(intensity, weight * peak * exp(-(d / band).pow(2)))
                    }
                    val writeRow = row - firstRow
                    if (writeRow < SweepGridSpec.WriteRows) {
                        val age = t - p.trailAt[writeRow * cols + c]
                        if (age in 0f..trailSeconds) intensity = max(intensity, SweepGridSpec.TrailPeak * (1 - age / trailSeconds).pow(1.5f))
                    }
                } else {
                    val d = (x - bx) + slanted
                    intensity = if (abs(d) < band * 2.5f) peak * exp(-(d / band).pow(2)) else 0f
                }
                if (intensity > 0.1f && flicker > 0f && motionHash(cell.toFloat(), floor(t * 30)) < flicker) intensity *= 0.3f
                if (intensity < 0.1f && sparkle > 0f && motionHash(cell.toFloat(), floor(t * 4 + motionHash(cell.toFloat(), 3f) * 4)) < sparkle) intensity = 0.6f
                isAmber = amberBand || (streaming && motionHash(cell.toFloat(), 7.7f) < 0.08f)
            }
            if (intensity >= 0.06f) leds.dot(if (isAmber) 1 else 0, intensity * 0.9f, x, y, sz, gz)
            x += pitch
            c++
        }
        y += pitch
        row++
    }
    leds.draw(canvas.nativeCanvas, palette.leds, palette.dark, blur = 3 * k, alpha = alpha)
}
