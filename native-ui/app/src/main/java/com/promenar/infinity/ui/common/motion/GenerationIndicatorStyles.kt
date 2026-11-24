package com.promenar.infinity.ui.common.motion

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.theme.GenerationIndicatorStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/** 各灯语指示的设计尺寸（缩放 1 时）；三点波浪的尺寸由调用方的圆点参数决定。 */
internal fun indicatorDesignSize(style: GenerationIndicatorStyle): DpSize = when (style) {
    GenerationIndicatorStyle.WAVE -> DpSize(28.dp, 10.dp)
    GenerationIndicatorStyle.PORTS -> DpSize(48.dp, 8.dp)
    GenerationIndicatorStyle.MATRIX -> DpSize(43.dp, 12.dp)
    GenerationIndicatorStyle.STACKED -> DpSize(47.dp, 12.dp)
    GenerationIndicatorStyle.BAYS -> DpSize(44.dp, 8.dp)
    GenerationIndicatorStyle.INFINITY_LINE -> DpSize(44.dp, 16.dp)
    GenerationIndicatorStyle.INFINITY_LED -> DpSize(46.dp, 16.dp)
}

/** 绘制灯语指示所需的可复用对象与随速率累积的相位；每个指示实例持有一份，避免逐帧分配。 */
internal class IndicatorPainter {
    /** 两档：0 为压暗（不带光晕），1 为点亮。 */
    val leds = LedBatch(levels = 2, glowFrom = 1)
    val beads = LedBatch(levels = 5, glowFrom = 2)
    val infinityBeads: FloatArray by lazy { lemniscatePoints(24) }
    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    val glowSpritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val glowRect = RectF()
    val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val point = FloatArray(2)
    val point2 = FloatArray(2)

    private var lastTime = Float.NaN
    private var frame = 0
    private var dt = 0f
    private val accValues = FloatArray(AccSlots)
    private val accFrames = IntArray(AccSlots) { -1 }

    /** 每帧绘制前调用一次，记下与上一帧的间隔。 */
    fun advance(time: Float) {
        if (time == lastTime) return
        dt = if (lastTime.isNaN()) 0f else (time - lastTime).coerceIn(0f, 0.1f)
        lastTime = time
        frame++
    }

    /** 随速率累积的相位：速率变化时不跳帧；同一帧里多次调用只前进一次。 */
    fun acc(slot: Int, speed: Float): Float {
        if (accFrames[slot] != frame) {
            accFrames[slot] = frame
            accValues[slot] += dt * speed
        }
        return accValues[slot]
    }

    companion object {
        const val AccWave = 0
        const val AccMatrixDrift = 1
        const val AccMatrixSweep = 2
        const val AccInfinityLine = 3
        const val AccInfinityLed = 4
        private const val AccSlots = 5
    }
}

/**
 * 以画布中心为原点绘制一种灯语指示。[k] 为每个设计单位（dp）对应的像素数，已含调用方缩放。
 * 交换机面板的灯语：常亮 = 在线，高频随机闪烁 = 正在收发，琥珀（第三色）= 忙碌或告警。
 * 按 [motion] 的各阶段权重分别绘制，阶段切换时交叉淡入淡出；思考内容到达后与正文同属流式，配色与节奏一致。
 */
internal fun DrawScope.drawGenerationIndicator(
    style: GenerationIndicatorStyle,
    time: Float,
    k: Float,
    palette: MotionPalette,
    painter: IndicatorPainter,
    motion: GenerationMotion,
) {
    painter.advance(time)
    val act = motion.act(time)
    for (phase in GenerationMotionPhase.entries) {
        val alpha = motion.weight(phase)
        if (alpha <= 0.004f) continue
        drawIndicatorPhase(style, phase, time, k, act, alpha, palette, painter)
    }
}

private fun DrawScope.drawIndicatorPhase(
    style: GenerationIndicatorStyle,
    phase: GenerationMotionPhase,
    t: Float,
    k: Float,
    act: Float,
    alpha: Float,
    palette: MotionPalette,
    painter: IndicatorPainter,
) {
    val cx = size.width / 2
    val cy = size.height / 2
    when (style) {
        GenerationIndicatorStyle.WAVE -> Unit
        GenerationIndicatorStyle.PORTS -> drawPorts(cx, cy, phase, t, k, act, alpha, palette, painter.leds)
        GenerationIndicatorStyle.MATRIX -> drawMatrix(cx, cy, phase, t, k, act, alpha, palette, painter)
        GenerationIndicatorStyle.STACKED -> drawStacked(cx, cy, phase, t, k, act, alpha, palette, painter.leds)
        GenerationIndicatorStyle.BAYS -> drawBays(cx, cy, phase, t, k, act, alpha, palette, painter.leds)
        GenerationIndicatorStyle.INFINITY_LINE -> drawInfinityLine(cx, cy, phase, t, k, act, alpha, palette, painter)
        GenerationIndicatorStyle.INFINITY_LED -> drawInfinityLed(cx, cy, phase, t, k, act, alpha, palette, painter)
    }
}

private fun DrawScope.faceplate(x: Float, y: Float, w: Float, h: Float, k: Float, alpha: Float, palette: MotionPalette) {
    drawRoundRect(palette.faceplate, Offset(x, y), Size(w, h), CornerRadius(1.6f * k), alpha = alpha)
}

/** 一颗灯：[level] 0 灭、1 压暗、2 点亮；[amber] 为第三色。 */
private fun LedBatch.led(level: Int, amber: Boolean, x: Float, y: Float, w: Float, h: Float, glowPad: Float) {
    when (level) {
        0 -> offRect(x, y, w, h)
        1 -> rect(if (amber) 1 else 0, 0.4f, x, y, w, h, glowPad)
        else -> rect(if (amber) 1 else 0, 1f, x, y, w, h, glowPad)
    }
}

private fun DrawScope.flushLeds(leds: LedBatch, k: Float, alpha: Float, palette: MotionPalette, phase: GenerationMotionPhase) {
    drawIntoCanvas { leds.draw(it.nativeCanvas, palette.phaseLeds(phase), palette.dark, blur = 3f * k, offColor = palette.off, whitenFrom = 1, alpha = alpha) }
}

private fun Float.lit(): Int = if (this > 0.5f) 2 else 1

/**
 * 三组网口灯，左灯 LINK、右灯 ACT。首字前三颗 LINK 轮流亮；流式时 LINK 常亮、ACT 按速率乱闪；
 * 工具执行时 ACT 换第三色依次跳动；等待审批时 LINK 第三色慢闪；重试时 LINK 压暗等间隔闪。
 */
private fun DrawScope.drawPorts(
    cx: Float, cy: Float, phase: GenerationMotionPhase, t: Float, k: Float, act: Float, alpha: Float,
    palette: MotionPalette, leds: LedBatch,
) {
    val sz = 3.8f * k
    val inner = 2f * k
    val pad = 1.8f * k
    val pw = sz * 2 + inner + pad * 2
    val gap = 4f * k
    val total = pw * 3 + gap * 2
    val ly = cy - sz / 2
    leds.reset()
    var x = cx - total / 2
    for (i in 0 until 3) {
        faceplate(x, cy - sz / 2 - pad, pw, sz + pad * 2, k, alpha, palette)
        var link = 2
        var actLevel = 0
        var linkAmber = false
        var actAmber = false
        when (phase) {
            GenerationMotionPhase.WAIT -> link = if (floor(t * 1.4f).toInt() % 3 == i) 2 else 1
            GenerationMotionPhase.STREAM -> {
                actLevel = if (actOn(i * 2 + 1, t, act, i * 1.9f)) 2 else 0
                actAmber = actLevel > 0 && motionHash(i.toFloat(), floor(t * 6)) < 0.08f
            }
            GenerationMotionPhase.TOOL -> {
                link = 1
                actLevel = if (floor(t * 8).toInt() % 3 == i) 2 else 0
                actAmber = true
            }
            GenerationMotionPhase.APPROVAL -> {
                link = slowBlink(t).lit()
                linkAmber = true
            }
            GenerationMotionPhase.RETRY -> link = evenBlink(t).toInt()
        }
        leds.led(link, linkAmber, x + pad, ly, sz, sz, k)
        leds.led(actLevel, actAmber, x + pad + sz + inner, ly, sz, sz, k)
        x += pw + gap
    }
    flushLeds(leds, k, alpha, palette, phase)
}

/**
 * 10×3 点阵。首字前一列光来回扫；流式时负载场的亮度与密度随速率涨落；工具执行时第三色蛇形逐格推进；
 * 等待审批时中间两列第三色慢闪；重试时整面压暗等间隔闪。
 */
private fun DrawScope.drawMatrix(
    cx: Float, cy: Float, phase: GenerationMotionPhase, t: Float, k: Float, act: Float, alpha: Float,
    palette: MotionPalette, painter: IndicatorPainter,
) {
    val cols = 10
    val rows = 3
    val pitch = 4.4f * k
    val sz = 2.8f * k
    val x0 = cx - (cols - 1) * pitch / 2
    val y0 = cy - (rows - 1) * pitch / 2
    val pos = pingPong(t / 2.2f) * (cols - 1)
    val drift = if (phase == GenerationMotionPhase.STREAM) painter.acc(IndicatorPainter.AccMatrixDrift, 1.9f * (0.4f + act)) else 0f
    val sweep = if (phase == GenerationMotionPhase.STREAM) floor(painter.acc(IndicatorPainter.AccMatrixSweep, 12 * (0.4f + act))).toInt().mod(cols + 5) else -1
    val snake = floor(t * 16).toInt().mod(cols * rows + 6)
    val blink = slowBlink(t).lit()
    val leds = painter.leds
    leds.reset()
    for (c in 0 until cols) for (r in 0 until rows) {
        val x = x0 + c * pitch - sz / 2
        val y = y0 + r * pitch - sz / 2
        var level = 0
        var amber = false
        when (phase) {
            GenerationMotionPhase.WAIT -> {
                val d = abs(c - pos)
                level = if (d < 0.6f) 2 else if (d < 1.6f) 1 else 0
            }
            GenerationMotionPhase.STREAM -> {
                val field = valueNoise2(c * 0.55f + drift, r * 1.1f - t * 1.2f)
                val flicker = motionHash(c * 7.1f + r * 3.3f, floor(t * (10 + motionHash(c.toFloat(), r.toFloat()) * 10 + 20 * act)))
                level = if (flicker < field * (0.25f + 0.75f * act) + (if (sweep == c) 0.5f * act else 0f) - 0.12f) 2 else 0
                amber = motionHash(c.toFloat(), r + 9f) < 0.14f
            }
            GenerationMotionPhase.TOOL -> {
                val order = c * rows + (if (c % 2 == 1) rows - 1 - r else r)
                level = if (order == snake) 2 else if (order < snake && order > snake - 6) 1 else 0
                amber = true
            }
            GenerationMotionPhase.APPROVAL -> {
                level = if (c == 4 || c == 5) blink else 0
                amber = true
            }
            GenerationMotionPhase.RETRY -> level = evenBlink(t).toInt()
        }
        leds.led(level, amber, x, y, sz, sz, 0.6f * k)
    }
    flushLeds(leds, k, alpha, palette, phase)
}

/**
 * 上下两排共 16 个口，个别口没插线。首字前各口常亮、一道亮光从左扫到右；流式时忙碌的口按速率收发；
 * 工具执行时下排第三色灯依次走过；等待审批时中间两口第三色慢闪；重试时各口压暗等间隔闪。
 */
private fun DrawScope.drawStacked(
    cx: Float, cy: Float, phase: GenerationMotionPhase, t: Float, k: Float, act: Float, alpha: Float,
    palette: MotionPalette, leds: LedBatch,
) {
    val cols = 8
    val w = 3.8f * k
    val h = 2.2f * k
    val gx = 1.8f * k
    val gy = 3.4f * k
    val total = cols * w + (cols - 1) * gx
    val x0 = cx - total / 2
    val y0 = cy - (h * 2 + gy) / 2
    faceplate(x0 - 2 * k, y0 - 2 * k, total + 4 * k, h * 2 + gy + 4 * k, k, alpha, palette)
    val scan = fract(t / 1.8f) * (cols + 3) - 1.5f
    val chase = floor(t * 10).toInt().mod(cols)
    val blink = slowBlink(t).lit()
    leds.reset()
    for (c in 0 until cols) for (r in 0 until 2) {
        val id = c * 2 + r
        val x = x0 + c * (w + gx)
        val y = y0 + r * (h + gy)
        val linked = motionHash(id.toFloat(), 3.3f) > 0.13f
        val busy = motionHash(id.toFloat(), 4.4f) < 0.55f
        val amberPort = motionHash(id.toFloat(), 5.5f) < 0.16f
        var level = 0
        var amber = false
        when (phase) {
            GenerationMotionPhase.WAIT -> level = if (!linked) 0 else if (abs(c - scan) < 0.7f) 2 else 1
            GenerationMotionPhase.STREAM -> {
                level = if (!linked) 0 else if (!busy || actOn(id, t, act, id * 0.9f)) 2 else 0
                amber = amberPort
            }
            GenerationMotionPhase.TOOL -> if (r == 0) {
                level = if (linked) 1 else 0
            } else {
                level = if (c == chase) 2 else if (c == (chase + cols - 1) % cols) 1 else 0
                amber = true
            }
            GenerationMotionPhase.APPROVAL -> {
                level = if (c == 3 || c == 4) blink else 0
                amber = true
            }
            GenerationMotionPhase.RETRY -> level = if (linked) evenBlink(t).toInt() else 0
        }
        leds.led(level, amber, x, y, w, h, 0.8f * k)
    }
    flushLeds(leds, k, alpha, palette, phase)
}

/**
 * 四个盘位托架，各一颗电源灯与一条活动灯。首字前活动灯逐盘慢速点亮；流式时活动灯按速率乱闪；
 * 工具执行时第三色活动灯依次寻道；等待审批时电源灯第三色慢闪；重试时电源灯压暗等间隔闪。
 */
private fun DrawScope.drawBays(
    cx: Float, cy: Float, phase: GenerationMotionPhase, t: Float, k: Float, act: Float, alpha: Float,
    palette: MotionPalette, leds: LedBatch,
) {
    val n = 4
    val tw = 9.5f * k
    val th = 8f * k
    val gap = 2f * k
    val total = n * tw + (n - 1) * gap
    val x0 = cx - total / 2
    leds.reset()
    for (i in 0 until n) {
        val x = x0 + i * (tw + gap)
        val y = cy - th / 2
        drawRoundRect(palette.outline.copy(alpha = 0.9f), Offset(x, y), Size(tw, th), CornerRadius(1.4f * k), style = Stroke(0.8f * k), alpha = alpha)
        drawRect(palette.outline.copy(alpha = 0.35f), Offset(x + 1.6f * k, y + th - 2.4f * k), Size(tw - 3.2f * k, 0.8f * k), alpha = alpha)
        var power = 2
        var activity = 0
        var powerAmber = false
        var activityAmber = false
        when (phase) {
            GenerationMotionPhase.WAIT -> activity = if (floor(t * 2.2f).toInt() % n == i) 1 else 0
            GenerationMotionPhase.STREAM -> {
                activity = if (actOn(i + 10, t, act, i * 2.6f)) 2 else 0
                activityAmber = activity > 0 && motionHash(i.toFloat(), floor(t * 3)) < 0.06f
            }
            GenerationMotionPhase.TOOL -> {
                power = 1
                activity = if (floor(t * 6).toInt() % n == i) 2 else 0
                activityAmber = true
            }
            GenerationMotionPhase.APPROVAL -> {
                power = slowBlink(t).lit()
                powerAmber = true
            }
            GenerationMotionPhase.RETRY -> power = evenBlink(t).toInt()
        }
        leds.led(power, powerAmber, x + 1.6f * k, y + 1.8f * k, 1.8f * k, 1.8f * k, 0.6f * k)
        leds.led(activity, activityAmber, x + 4.4f * k, y + 2.1f * k, 3.6f * k, 1.2f * k, 0.6f * k)
    }
    flushLeds(leds, k, alpha, palette, phase)
}

/**
 * ∞ 描线：线宽在两环处粗、交叉处细，暗示莫比乌斯带的扭转。首字前一个光头 3.6 秒慢走一圈；
 * 流式时双光头反向追逐，速率越高越快、尾巴越长，每半圈在中心交汇闪一下；工具执行时第三色光头一格一格跳着走；
 * 等待审批时整条 ∞ 第三色慢闪；重试时 ∞ 压暗等间隔闪。
 */
private fun DrawScope.drawInfinityLine(
    cx: Float, cy: Float, phase: GenerationMotionPhase, t: Float, k: Float, act: Float, alpha: Float,
    palette: MotionPalette, painter: IndicatorPainter,
) {
    val a = 20f * k
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        when (phase) {
            GenerationMotionPhase.WAIT -> {
                infinityBase(native, cx, cy, a, k, palette.mid, (if (palette.dark) 0.12f else 0.18f) * alpha, 0.9f, painter)
                infinityTails(native, cx, cy, a, k, palette, painter) { draw -> draw(t / 3.6f * Tau, palette.a, 1.4f, 0.75f * alpha) }
            }
            GenerationMotionPhase.STREAM -> {
                infinityBase(native, cx, cy, a, k, palette.mid, (if (palette.dark) 0.16f else 0.22f) * alpha, 0.9f, painter)
                val head = painter.acc(IndicatorPainter.AccInfinityLine, Tau / 1.8f * (0.45f + 1.1f * act))
                val length = 1.6f + 1.2f * act
                infinityTails(native, cx, cy, a, k, palette, painter) { draw ->
                    draw(head, palette.a, length, alpha)
                    draw(head + PiF, palette.b, length, alpha)
                }
                val flash = exp(-(abs(cos(head)) / 0.18f).pow(2))
                if (flash > 0.03f) {
                    val r = 7f * k
                    painter.flashPaint.shader = RadialGradient(cx, cy, r, palette.hot.copy(alpha = flash * alpha).toArgb(), palette.mid.copy(alpha = 0f).toArgb(), Shader.TileMode.CLAMP)
                    painter.flashPaint.blendMode = if (palette.dark) BlendMode.PLUS else BlendMode.SRC_OVER
                    native.drawCircle(cx, cy, r, painter.flashPaint)
                }
            }
            GenerationMotionPhase.TOOL -> {
                infinityBase(native, cx, cy, a, k, palette.b, (if (palette.dark) 0.14f else 0.2f) * alpha, 0.9f, painter)
                val u = t / 1.6f * 8
                val q = (floor(u) + smoothStep(0f, 0.35f, fract(u))) / 8
                infinityTails(native, cx, cy, a, k, palette, painter) { draw -> draw(q * Tau, palette.b, 0.9f, alpha) }
            }
            GenerationMotionPhase.APPROVAL ->
                infinityBase(native, cx, cy, a, k, palette.approval, (0.25f + 0.6f * slowBlink(t)) * alpha, 1.6f, painter)
            GenerationMotionPhase.RETRY ->
                infinityBase(native, cx, cy, a, k, palette.a, (0.1f + 0.3f * evenBlink(t)) * alpha, 1.1f, painter)
        }
    }
}

/** ∞ 底线：64 段折线。 */
private fun infinityBase(native: Canvas, cx: Float, cy: Float, a: Float, k: Float, color: Color, alpha: Float, width: Float, painter: IndicatorPainter) {
    val p = painter.point
    val q = painter.point2
    val line = painter.linePaint
    line.blendMode = BlendMode.SRC_OVER
    line.strokeWidth = width * k
    line.color = color.copy(alpha = clamp01(alpha)).toArgb()
    for (i in 0 until 64) {
        lemniscateAt(i / 64f * Tau, p)
        lemniscateAt((i + 1) / 64f * Tau, q)
        native.drawLine(cx + a * p[0], cy + a * p[1], cx + a * q[0], cy + a * q[1], line)
    }
}

/** 光头与渐隐的尾巴：先画无模糊高斯精灵柔光层（深色主题叠加混合），再画清晰的一层。[tails] 依次给出每条尾巴的头部、颜色、长度与透明度。 */
private inline fun infinityTails(
    native: Canvas, cx: Float, cy: Float, a: Float, k: Float, palette: MotionPalette, painter: IndicatorPainter,
    tails: ((Float, Color, Float, Float) -> Unit) -> Unit,
) {
    val p = painter.point
    val q = painter.point2
    val glowBlur = 2.5f * k
    tails { head, color, length, alpha ->
        val segments = 34
        for (j in segments - 1 downTo 0) {
            val s0 = head - j.toFloat() / segments * length
            val s1 = head - (j + 1f) / segments * length
            val f = 1 - j.toFloat() / segments
            lemniscateAt(s0, p)
            lemniscateAt(s1, q)
            val segColor = if (j < 2) palette.hot else color
            val segAlpha = clamp01(f * f * alpha)
            val width = (0.6f + 1.6f * f) * k * (0.55f + 0.45f * abs(cos(s0)))
            drawGaussianGlowSegment(
                native,
                cx + a * q[0], cy + a * q[1], cx + a * p[0], cy + a * p[1],
                width, segColor, segAlpha, glowBlur, palette.dark, painter.glowSpritePaint, painter.glowRect,
            )
        }
    }
    painter.linePaint.blendMode = BlendMode.SRC_OVER
    tails { head, color, length, alpha ->
        val segments = 34
        for (j in segments - 1 downTo 0) {
            val s0 = head - j.toFloat() / segments * length
            val s1 = head - (j + 1f) / segments * length
            val f = 1 - j.toFloat() / segments
            lemniscateAt(s0, p)
            lemniscateAt(s1, q)
            painter.linePaint.color = (if (j < 2) palette.hot else color).copy(alpha = clamp01(f * f * alpha)).toArgb()
            painter.linePaint.strokeWidth = (0.6f + 1.6f * f) * k * (0.55f + 0.45f * abs(cos(s0)))
            native.drawLine(cx + a * q[0], cy + a * q[1], cx + a * p[0], cy + a * p[1], painter.linePaint)
        }
    }
}

/**
 * ∞ 灯珠：24 颗方灯沿 ∞ 等距排开。首字前一个数据包慢走、不闪；流式时 1–3 个包，数量和速度随速率，包里的灯按 ACT 节奏闪；
 * 工具执行时三颗第三色灯像加载圈一样转；等待审批时整圈第三色慢闪；重试时整圈压暗等间隔闪。
 */
private fun DrawScope.drawInfinityLed(
    cx: Float, cy: Float, phase: GenerationMotionPhase, t: Float, k: Float, act: Float, alpha: Float,
    palette: MotionPalette, painter: IndicatorPainter,
) {
    val points = painter.infinityBeads
    val n = points.size / 2
    val a = 21f * k
    val sz = 2.3f * k
    val gz = 4.2f * k
    var tail = 6f
    var flicker = false
    var heads = FloatArray(0)
    var amber = BooleanArray(0)
    if (phase == GenerationMotionPhase.WAIT) {
        heads = floatArrayOf(fract(t / 3.2f) * n)
        amber = booleanArrayOf(false)
        tail = 4f
    } else if (phase == GenerationMotionPhase.STREAM && act >= 0.02f) {
        val u = painter.acc(IndicatorPainter.AccInfinityLed, (0.45f + 1.1f * act) / 1.5f)
        val count = 1 + (2 * act).roundToInt()
        val loop = floor(u)
        heads = FloatArray(count) { fract(u + it.toFloat() / count) * n }
        amber = BooleanArray(count) { motionHash(loop, 2.2f + it) < 0.25f }
        flicker = true
    }
    val step = floor(t * 12).toInt()
    val blink = slowBlink(t) > 0.5f
    val evenOn = evenBlink(t) > 0.5f
    val beads = painter.beads
    beads.reset()
    for (i in 0 until n) {
        val x = cx + points[i * 2] * a
        val y = cy + points[i * 2 + 1] * a
        var intensity = 0f
        var isAmber = false
        when (phase) {
            GenerationMotionPhase.TOOL -> {
                val m = (i - step).mod(8)
                intensity = if (m == 0) 1f else if (m == 1) 0.35f else 0f
                isAmber = true
            }
            GenerationMotionPhase.APPROVAL -> {
                intensity = if (blink) 0.75f else 0.3f
                isAmber = true
            }
            GenerationMotionPhase.RETRY -> intensity = if (evenOn) 0.35f else 0f
            else -> {
                for (h in heads.indices) {
                    val d = (heads[h] - i).mod(n.toFloat())
                    if (d < tail) {
                        val v = (1 - d / tail).pow(1.5f) * (if (phase == GenerationMotionPhase.WAIT) 0.85f else 1f)
                        if (v > intensity) {
                            intensity = v
                            isAmber = amber[h]
                        }
                    }
                }
                if (flicker && intensity > 0.15f && intensity < 0.95f && motionHash(i.toFloat(), floor(t * (20 + 30 * act))) < 0.25f) intensity *= 0.25f
            }
        }
        if (intensity < 0.08f) beads.offDot(x, y, sz) else beads.dot(if (isAmber) 1 else 0, intensity, x, y, sz, gz)
    }
    drawIntoCanvas {
        beads.draw(it.nativeCanvas, palette.phaseLeds(phase), palette.dark, blur = 3f * k, offColor = palette.a.copy(alpha = if (palette.dark) 0.13f else 0.18f), alpha = alpha)
    }
}

private const val PiF = Math.PI.toFloat()
