package com.promenar.infinity.ui.common.motion

import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.theme.GenerationIndicatorStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min

/*
 * 资料索引指示：与生成状态指示同一套机房灯语，区别在于索引有确定的进度。
 * 向量化用主色按进度逐格点亮，前沿一格闪烁表示正在写入；抽取图谱换成主色与第三色的色相中点。
 * 第三色（琥珀）沿用生成指示的含义：慢闪 = 等你确认，常亮 = 出错；压暗等间隔闪 = 重试等待。
 * 交换机口与两种 ∞ 把向量与图谱分成两条轨道同时显示，其余样式让图谱沿同一条轨道再走一遍。
 */

/** 资料索引的阶段。 */
enum class IndexMotionPhase {
    /** 排队等待。 */
    QUEUED,
    /** 解析正文、切块。 */
    PREPARING,
    VECTORIZING,
    /** 向量化批次失败后等待重试。 */
    RETRYING,
    EXTRACTING_GRAPH,
    /** 写入索引。 */
    SAVING,
    /** 向量已索引，图谱等待用户确认用量。 */
    NEEDS_CONFIRMATION,
    /** 索引或图谱失败：[IndexMotionState.vectorProgress] 未满时是向量失败，满了是图谱失败。 */
    FAILED,
}

/** 索引指示的输入：进度都在 0..1；[withGraph] 表示本次索引包含图谱抽取。 */
@Immutable
data class IndexMotionState(
    val phase: IndexMotionPhase,
    val vectorProgress: Float = 0f,
    val graphProgress: Float = 0f,
    val withGraph: Boolean = false,
)

/** 索引指示的样式，跟随生成状态指示的选择。 */
internal enum class IndexIndicatorKind {
    /** 16 段电平条。 */
    BAR,
    /** 上排向量、下排图谱的交换机口。 */
    STACKED,
    /** 10×3 点阵逐列填充。 */
    MATRIX,
    /** 四个盘位的活动条。 */
    BAYS,
    /** 左环向量、右环图谱的 ∞ 描线。 */
    INFINITY_LINE,
    /** 左 12 颗向量、右 12 颗图谱的 ∞ 灯珠。 */
    INFINITY_LED,
}

internal fun GenerationIndicatorStyle.indexingKind(): IndexIndicatorKind = when (this) {
    GenerationIndicatorStyle.WAVE -> IndexIndicatorKind.BAR
    GenerationIndicatorStyle.PORTS, GenerationIndicatorStyle.STACKED -> IndexIndicatorKind.STACKED
    GenerationIndicatorStyle.MATRIX -> IndexIndicatorKind.MATRIX
    GenerationIndicatorStyle.BAYS -> IndexIndicatorKind.BAYS
    GenerationIndicatorStyle.INFINITY_LINE -> IndexIndicatorKind.INFINITY_LINE
    GenerationIndicatorStyle.INFINITY_LED -> IndexIndicatorKind.INFINITY_LED
}

/** 向量与图谱各占一条轨道。 */
internal val IndexIndicatorKind.separateTracks: Boolean
    get() = this == IndexIndicatorKind.STACKED || this == IndexIndicatorKind.INFINITY_LINE || this == IndexIndicatorKind.INFINITY_LED

/** 每条轨道的灯数。 */
internal val IndexIndicatorKind.trackUnits: Int
    get() = when (this) {
        IndexIndicatorKind.BAR -> 16
        IndexIndicatorKind.STACKED -> 8
        IndexIndicatorKind.MATRIX -> 30
        IndexIndicatorKind.BAYS -> 12
        IndexIndicatorKind.INFINITY_LINE -> 28
        IndexIndicatorKind.INFINITY_LED -> 12
    }

/** 设计尺寸（缩放 1 时），与生成指示同量级，能放进列表行的元信息行。 */
internal fun indexIndicatorDesignSize(kind: IndexIndicatorKind): DpSize = when (kind) {
    IndexIndicatorKind.BAR -> DpSize(52.dp, 10.dp)
    IndexIndicatorKind.STACKED -> DpSize(47.dp, 12.dp)
    IndexIndicatorKind.MATRIX -> DpSize(43.dp, 12.dp)
    IndexIndicatorKind.BAYS -> DpSize(44.dp, 8.dp)
    IndexIndicatorKind.INFINITY_LINE -> DpSize(44.dp, 16.dp)
    IndexIndicatorKind.INFINITY_LED -> DpSize(46.dp, 16.dp)
}

/** 灯色序号，对应 [IndexIndicatorPainter.colors] 的顺序。 */
internal object IndexLed {
    const val PRIMARY = 0
    const val GRAPH = 1
    const val AMBER = 2
    const val HOT = 3
}

/** 低于该亮度按熄灭绘制。 */
internal const val IndexLedOff = 0.06f

/** 前沿写入灯的高频闪烁：每秒 14 个时间槽，约六成时间点亮。 */
private fun frontierFlicker(i: Int, t: Float): Float = if (motionHash(i * 7.3f, floor(t * 14)) > 0.4f) 1f else 0.18f

/** 共用轨道上“等待确认”时第三色慢闪的居中几格，对应生成指示审批阶段点阵中间两列。 */
internal fun isCenterUnit(i: Int, n: Int): Boolean = abs(i - (n - 1) / 2f) < n / 10f

/**
 * 计算一条轨道上每颗灯的亮度（0..1）与颜色序号，写入 [outLevel] 与 [outColor] 的前 [n] 项。
 * [graphTrack] 只对分轨样式有意义；共用轨道传 false，由阶段决定显示向量还是图谱。
 */
internal fun indexTrackLevels(
    state: IndexMotionState,
    graphTrack: Boolean,
    separate: Boolean,
    n: Int,
    t: Float,
    outLevel: FloatArray,
    outColor: IntArray,
) {
    val vector = clamp01(state.vectorProgress)
    val graph = clamp01(state.graphProgress)
    val ownProgress = if (graphTrack) graph else vector
    fun set(i: Int, level: Float, color: Int) {
        outLevel[i] = level
        outColor[i] = color
    }
    for (i in 0 until n) {
        val flick = frontierFlicker(i, t)
        when (state.phase) {
            IndexMotionPhase.QUEUED ->
                set(i, if (i == 0 && !graphTrack) 0.25f + 0.35f * breath(t, 2.6f) else 0f, IndexLed.PRIMARY)
            IndexMotionPhase.PREPARING -> if (separate && graphTrack) set(i, 0f, IndexLed.PRIMARY) else {
                val d = abs(i - pingPong(t / 2.6f) * (n - 1))
                set(i, if (d < 0.6f) 0.85f else if (d < 1.6f) 0.35f else 0f, IndexLed.PRIMARY)
            }
            IndexMotionPhase.VECTORIZING, IndexMotionPhase.RETRYING -> {
                val lit = litCount(vector, n)
                when {
                    separate && graphTrack -> set(i, 0f, IndexLed.PRIMARY)
                    i < lit -> set(i, 0.7f, IndexLed.PRIMARY)
                    i == lit -> set(i, if (state.phase == IndexMotionPhase.RETRYING) 0.35f * evenBlink(t) else flick, IndexLed.PRIMARY)
                    else -> set(i, 0f, IndexLed.PRIMARY)
                }
            }
            IndexMotionPhase.EXTRACTING_GRAPH -> {
                val lit = litCount(graph, n)
                when {
                    separate && !graphTrack -> set(i, 0.55f, IndexLed.PRIMARY)
                    i < lit -> set(i, 0.75f, IndexLed.GRAPH)
                    i == lit -> set(i, flick, IndexLed.GRAPH)
                    // 共用轨道上未抽取的格子以压暗的主色表示“向量已就绪”。
                    else -> set(i, if (separate) 0f else 0.22f, IndexLed.PRIMARY)
                }
            }
            IndexMotionPhase.SAVING -> {
                val stageColor = if (if (separate) graphTrack else state.withGraph) IndexLed.GRAPH else IndexLed.PRIMARY
                if (separate && graphTrack && !state.withGraph) set(i, 0f, IndexLed.PRIMARY) else {
                    val d = abs(i - (fract(t / 1.2f) * (n + 3) - 1.5f))
                    if (d < 0.8f) set(i, 1f, IndexLed.HOT) else set(i, 0.6f, stageColor)
                }
            }
            IndexMotionPhase.NEEDS_CONFIRMATION -> {
                val blink = 0.3f + 0.55f * slowBlink(t / 1.6f)
                when {
                    separate && !graphTrack -> set(i, 0.55f, IndexLed.PRIMARY)
                    separate -> set(i, blink, IndexLed.AMBER)
                    isCenterUnit(i, n) -> set(i, blink, IndexLed.AMBER)
                    else -> set(i, 0.22f, IndexLed.PRIMARY)
                }
            }
            IndexMotionPhase.FAILED -> {
                val vectorFailed = vector < 1f
                val failingTrack = if (separate) graphTrack == !vectorFailed else true
                val progress = if (separate) ownProgress else if (vectorFailed) vector else graph
                val lit = litCount(progress, n)
                val stageColor = if (vectorFailed) IndexLed.PRIMARY else IndexLed.GRAPH
                when {
                    separate && !graphTrack && !vectorFailed -> set(i, 0.55f, IndexLed.PRIMARY)
                    !failingTrack -> set(i, 0f, IndexLed.PRIMARY)
                    i < lit -> set(i, 0.35f, stageColor)
                    i == lit -> set(i, 1f, IndexLed.AMBER)
                    else -> set(i, 0f, IndexLed.PRIMARY)
                }
            }
        }
    }
}

/** 已完成的灯数；进度满时前沿落在轨道之外。 */
internal fun litCount(progress: Float, n: Int): Int = min(n, floor(clamp01(progress) * n).toInt())

/**
 * 点阵抽图谱时的“关系数据包”：每 0.5 秒从前沿格沿同一行向左跑到一个更早抽取的格子，到达时目标格闪一下。
 * 只在已点亮的格子上移动，不画额外线条；返回需要覆盖的格子写入 [outLevel]、[outColor]。
 */
internal fun matrixRelationPacket(
    graphProgress: Float,
    rows: Int,
    n: Int,
    t: Float,
    outLevel: FloatArray,
    outColor: IntArray,
) {
    val lit = litCount(graphProgress, n)
    val frontierColumn = lit / rows
    if (frontierColumn <= 0) return
    val row = lit % rows
    val slot = floor(t / 0.5f)
    val f = fract(t / 0.5f)
    val target = floor(motionHash(slot, 1.7f) * frontierColumn).toInt()
    if (f < 0.75f) {
        val pos = frontierColumn - (frontierColumn - target) * (f / 0.75f)
        for (column in target until frontierColumn) {
            val i = column * rows + row
            if (i >= lit) continue
            val d = pos - column
            if (abs(d) < 0.5f) {
                outLevel[i] = 1f
                outColor[i] = IndexLed.HOT
            } else if (d < 0f && d > -1.5f) {
                outLevel[i] = 0.85f
                outColor[i] = IndexLed.GRAPH
            }
        }
    } else {
        val i = target * rows + row
        if (i < lit) {
            outLevel[i] = 1f - (f - 0.75f) * 2f
            outColor[i] = IndexLed.HOT
        }
    }
}

/** 每个指示实例持有一份，逐帧复用。 */
internal class IndexIndicatorPainter {
    val leds = LedBatch(levels = 5, glowFrom = 4, colors = 4)
    val levels = FloatArray(32)
    val colors = IntArray(32)
    val levels2 = FloatArray(32)
    val colors2 = IntArray(32)
    val infinityBeads: FloatArray by lazy { lemniscatePoints(24) }
    val point = FloatArray(2)
    val point2 = FloatArray(2)
    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    var glowBlur = -1f
    /** 等待确认与失败用的琥珀色，取主题的警示色，不随主色变化。 */
    var amber: Color = Color.Unspecified
}

/** 以画布中心为原点绘制索引指示；[k] 为每 dp 的像素数，已含调用方缩放。 */
internal fun DrawScope.drawIndexingIndicator(
    kind: IndexIndicatorKind,
    state: IndexMotionState,
    time: Float,
    k: Float,
    palette: MotionPalette,
    painter: IndexIndicatorPainter,
    amber: Color = palette.b,
) {
    painter.amber = amber
    val n = kind.trackUnits
    val separate = kind.separateTracks
    indexTrackLevels(state, graphTrack = false, separate = separate, n = n, t = time, outLevel = painter.levels, outColor = painter.colors)
    if (separate) {
        indexTrackLevels(state, graphTrack = true, separate = true, n = n, t = time, outLevel = painter.levels2, outColor = painter.colors2)
    }
    val cx = size.width / 2
    val cy = size.height / 2
    when (kind) {
        IndexIndicatorKind.BAR -> drawBar(cx, cy, k, palette, painter)
        IndexIndicatorKind.STACKED -> drawStackedTracks(cx, cy, k, palette, painter)
        IndexIndicatorKind.MATRIX -> {
            if (state.phase == IndexMotionPhase.EXTRACTING_GRAPH) {
                matrixRelationPacket(state.graphProgress, rows = 3, n = n, t = time, outLevel = painter.levels, outColor = painter.colors)
            }
            drawMatrixFill(cx, cy, k, palette, painter)
        }
        IndexIndicatorKind.BAYS -> drawBayFill(cx, cy, state, time, k, palette, painter)
        IndexIndicatorKind.INFINITY_LINE -> drawInfinityTrace(cx, cy, k, palette, painter)
        IndexIndicatorKind.INFINITY_LED -> drawInfinityBeads(cx, cy, k, palette, painter)
    }
}

private fun MotionPalette.indexColors(amber: Color) = listOf(a, mid, amber.takeOrElse { b }, hot)

private fun LedBatch.unit(level: Float, color: Int, x: Float, y: Float, w: Float, h: Float, glowPad: Float) {
    if (level < IndexLedOff) offRect(x, y, w, h) else rect(color, level, x, y, w, h, glowPad)
}

private fun DrawScope.flush(painter: IndexIndicatorPainter, k: Float, palette: MotionPalette) {
    drawIntoCanvas {
        painter.leds.draw(it.nativeCanvas, palette.indexColors(painter.amber), palette.dark, blur = 3f * k, offColor = palette.off, whitenFrom = 4)
    }
}

private fun DrawScope.plate(x: Float, y: Float, w: Float, h: Float, k: Float, palette: MotionPalette) {
    drawRoundRect(palette.faceplate, Offset(x, y), Size(w, h), CornerRadius(1.6f * k))
}

private fun DrawScope.drawBar(cx: Float, cy: Float, k: Float, palette: MotionPalette, painter: IndexIndicatorPainter) {
    val n = 16
    val w = 2f * k
    val h = 6f * k
    val g = 1f * k
    val total = n * w + (n - 1) * g
    val x0 = cx - total / 2
    plate(x0 - 2 * k, cy - h / 2 - 2 * k, total + 4 * k, h + 4 * k, k, palette)
    painter.leds.reset()
    for (i in 0 until n) painter.leds.unit(painter.levels[i], painter.colors[i], x0 + i * (w + g), cy - h / 2, w, h, 0.8f * k)
    flush(painter, k, palette)
}

private fun DrawScope.drawStackedTracks(cx: Float, cy: Float, k: Float, palette: MotionPalette, painter: IndexIndicatorPainter) {
    val n = 8
    val w = 3.8f * k
    val h = 2.2f * k
    val gx = 1.8f * k
    val gy = 3.4f * k
    val total = n * w + (n - 1) * gx
    val x0 = cx - total / 2
    val y0 = cy - (h * 2 + gy) / 2
    plate(x0 - 2 * k, y0 - 2 * k, total + 4 * k, h * 2 + gy + 4 * k, k, palette)
    painter.leds.reset()
    for (i in 0 until n) {
        val x = x0 + i * (w + gx)
        painter.leds.unit(painter.levels[i], painter.colors[i], x, y0, w, h, 0.8f * k)
        painter.leds.unit(painter.levels2[i], painter.colors2[i], x, y0 + h + gy, w, h, 0.8f * k)
    }
    flush(painter, k, palette)
}

private fun DrawScope.drawMatrixFill(cx: Float, cy: Float, k: Float, palette: MotionPalette, painter: IndexIndicatorPainter) {
    val cols = 10
    val rows = 3
    val pitch = 4.4f * k
    val sz = 2.8f * k
    val x0 = cx - (cols - 1) * pitch / 2
    val y0 = cy - (rows - 1) * pitch / 2
    painter.leds.reset()
    // 列优先编号：每列自上而下，列从左到右，进度看起来逐列写满。
    for (i in 0 until cols * rows) {
        val x = x0 + (i / rows) * pitch - sz / 2
        val y = y0 + (i % rows) * pitch - sz / 2
        painter.leds.unit(painter.levels[i], painter.colors[i], x, y, sz, sz, 0.6f * k)
    }
    flush(painter, k, palette)
}

private fun DrawScope.drawBayFill(
    cx: Float, cy: Float, state: IndexMotionState, t: Float, k: Float, palette: MotionPalette, painter: IndexIndicatorPainter,
) {
    val bays = 4
    val tw = 9.5f * k
    val th = 8f * k
    val gap = 2f * k
    val total = bays * tw + (bays - 1) * gap
    val x0 = cx - total / 2
    val y = cy - th / 2
    val confirming = state.phase == IndexMotionPhase.NEEDS_CONFIRMATION
    val failedBay = if (state.phase == IndexMotionPhase.FAILED) {
        litCount(if (state.vectorProgress < 1f) state.vectorProgress else state.graphProgress, 12).takeIf { it < 12 }?.div(3) ?: -1
    } else -1
    painter.leds.reset()
    for (b in 0 until bays) {
        val x = x0 + b * (tw + gap)
        drawRoundRect(palette.outline.copy(alpha = 0.9f), Offset(x, y), Size(tw, th), CornerRadius(1.4f * k), style = Stroke(0.8f * k))
        // 盘位用电源灯表达“等你确认”与“出错”，活动条只表达进度。
        val power = when {
            confirming -> 0.3f + 0.55f * slowBlink(t / 1.6f)
            b == failedBay -> 1f
            else -> 0.75f
        }
        val powerColor = if (confirming || b == failedBay) IndexLed.AMBER else IndexLed.PRIMARY
        painter.leds.unit(power, powerColor, x + 1.6f * k, y + 1.8f * k, 1.8f * k, 1.8f * k, 0.6f * k)
        for (s in 0 until 3) {
            val i = b * 3 + s
            var level = painter.levels[i]
            var color = painter.colors[i]
            if (confirming) {
                level = 0.22f
                color = IndexLed.PRIMARY
            } else if (color == IndexLed.AMBER) {
                // 出错位置已由电源灯标出，活动条保持熄灭。
                level = 0f
            }
            painter.leds.unit(level, color, x + 1.6f * k + s * 2.2f * k, y + 4.6f * k, 1.8f * k, 1.6f * k, 0.6f * k)
        }
    }
    flush(painter, k, palette)
}

private fun DrawScope.drawInfinityBeads(cx: Float, cy: Float, k: Float, palette: MotionPalette, painter: IndexIndicatorPainter) {
    val points = painter.infinityBeads
    val a = 21f * k
    val sz = 2.3f * k
    painter.leds.reset()
    // 灯珠从中心出发先走左环（向量），再走右环（图谱）。
    for (i in 0 until 24) {
        val x = cx + points[i * 2] * a - sz / 2
        val y = cy + points[i * 2 + 1] * a - sz / 2
        val graph = i >= 12
        val j = if (graph) i - 12 else i
        val level = if (graph) painter.levels2[j] else painter.levels[j]
        val color = if (graph) painter.colors2[j] else painter.colors[j]
        painter.leds.unit(level, color, x, y, sz, sz, 0.9f * k)
    }
    flush(painter, k, palette)
}

private fun DrawScope.drawInfinityTrace(cx: Float, cy: Float, k: Float, palette: MotionPalette, painter: IndexIndicatorPainter) {
    val a = 20f * k
    val segments = 28
    val colors = palette.indexColors(painter.amber)
    if (painter.glowBlur != 2.5f * k) {
        painter.glowPaint.maskFilter = BlurMaskFilter(2.5f * k, BlurMaskFilter.Blur.NORMAL)
        painter.glowBlur = 2.5f * k
    }
    val p = painter.point
    val q = painter.point2
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        for (pass in 0 until 2) {
            for (track in 0 until 2) {
                val levels = if (track == 0) painter.levels else painter.levels2
                val trackColors = if (track == 0) painter.colors else painter.colors2
                val start = (if (track == 0) PI / 2 else PI * 1.5).toFloat()
                for (i in 0 until segments) {
                    val s0 = start + i.toFloat() / segments * PI.toFloat()
                    val s1 = s0 + PI.toFloat() / segments
                    val level = levels[i]
                    val lit = level >= IndexLedOff
                    // 第 0 遍画深色主题里的光晕，只给最亮的前沿与数据包。
                    if (pass == 0 && !(palette.dark && level >= 0.9f)) continue
                    lemniscateAt(s0, p)
                    lemniscateAt(s1, q)
                    val paint = if (pass == 0) painter.glowPaint else painter.linePaint
                    paint.blendMode = if (pass == 0) BlendMode.PLUS else BlendMode.SRC_OVER
                    val color = if (lit) colors[trackColors[i]].copy(alpha = clamp01(level)) else palette.off
                    paint.color = color.toArgb()
                    paint.strokeWidth = (if (lit) 1.5f else 0.9f) * k * (0.55f + 0.45f * abs(cos(s0)))
                    native.drawLine(cx + a * p[0], cy + a * p[1], cx + a * q[0], cy + a * q[1], paint)
                }
            }
        }
    }
}
