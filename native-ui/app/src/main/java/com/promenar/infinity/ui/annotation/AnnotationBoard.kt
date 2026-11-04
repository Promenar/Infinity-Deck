package com.promenar.infinity.ui.annotation

import com.promenar.infinity.domain.media.ImageAnnotationKind
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 画板坐标一律用导出图的原始像素，缩放与屏幕尺寸不影响落点。 */
@Serializable
data class BoardPoint(val x: Float, val y: Float)

/** 一笔画板内容；颜色为 ARGB。 */
@Serializable
sealed interface BoardOp {
    @Serializable @SerialName("pen")
    data class Pen(val color: Int, val width: Float, val points: List<BoardPoint>) : BoardOp

    /** 直线；[arrow] 为真时在终点画箭头。 */
    @Serializable @SerialName("line")
    data class Line(val color: Int, val width: Float, val start: BoardPoint, val end: BoardPoint, val arrow: Boolean) : BoardOp

    @Serializable @SerialName("rect")
    data class Box(val color: Int, val width: Float, val start: BoardPoint, val end: BoardPoint) : BoardOp

    /** 以 [at] 为中心的一行文字；标注模式带反差描边，保证落在任意底色上都看得清。 */
    @Serializable @SerialName("text")
    data class Label(val color: Int, val size: Float, val at: BoardPoint, val text: String, val outline: Boolean) : BoardOp

    /** 选区笔画；[erase] 为真时从已涂区域中擦除。 */
    @Serializable @SerialName("mask")
    data class MaskStroke(val width: Float, val points: List<BoardPoint>, val erase: Boolean) : BoardOp
}

enum class BoardTool { PEN, ARROW, LINE, RECT, TEXT, ERASER, BRUSH, UNBRUSH }

/** 各画法可用的工具，顺序即工具栏顺序。 */
fun boardTools(kind: ImageAnnotationKind): List<BoardTool> = when (kind) {
    ImageAnnotationKind.MARKUP -> listOf(BoardTool.PEN, BoardTool.ARROW, BoardTool.RECT, BoardTool.TEXT, BoardTool.ERASER)
    ImageAnnotationKind.MASK -> listOf(BoardTool.BRUSH, BoardTool.UNBRUSH)
    ImageAnnotationKind.SKETCH -> listOf(BoardTool.PEN, BoardTool.LINE, BoardTool.RECT, BoardTool.TEXT, BoardTool.ERASER)
}

/** 标注用高饱和色，便于在照片上辨认；草图以黑灰为主。 */
val MarkupColors = listOf(0xFFFF3B30.toInt(), 0xFFFFCC00.toInt(), 0xFF34C759.toInt(), 0xFF0A84FF.toInt(), 0xFFFFFFFF.toInt())
val SketchColors = listOf(0xFF1C1C1E.toInt(), 0xFF8E8E93.toInt(), 0xFFFF3B30.toInt(), 0xFF0A84FF.toInt(), 0xFF34C759.toInt(), 0xFFFF9500.toInt())

/** 三档粗细，按短边比例换算成像素：笔画、选区画笔、文字各一套。 */
private val StrokeFractions = floatArrayOf(0.006f, 0.011f, 0.02f)
private val BrushFractions = floatArrayOf(0.035f, 0.065f, 0.11f)
private val TextFractions = floatArrayOf(0.036f, 0.05f, 0.07f)

/** 草图画布尺寸，宽高均为 16 的倍数，可直接作为生图 size。 */
data class SketchCanvasSize(val label: String, val width: Int, val height: Int)

val SketchCanvasSizes = listOf(
    SketchCanvasSize("1:1", 1024, 1024),
    SketchCanvasSize("2:3", 1024, 1536),
    SketchCanvasSize("3:2", 1536, 1024),
    SketchCanvasSize("9:16", 1152, 2048),
)

/** 文字宽度测量；界面传入画笔实测，测试可用近似值。 */
fun interface LabelMeasurer {
    fun width(text: String, size: Float): Float
}

/**
 * 画板状态：三种画法各自一份笔画与撤销栈。标注与选区共用一张原图，可以都画，但完成时只提交当前画法。
 * 所有操作返回新状态，不修改原对象。
 */
data class AnnotationBoard(
    val kind: ImageAnnotationKind,
    val width: Int,
    val height: Int,
    val layers: Map<ImageAnnotationKind, List<BoardOp>> = emptyMap(),
    val undoStack: Map<ImageAnnotationKind, List<List<BoardOp>>> = emptyMap(),
    val redoStack: Map<ImageAnnotationKind, List<List<BoardOp>>> = emptyMap(),
) {
    val ops: List<BoardOp> get() = layers[kind].orEmpty()
    val canUndo: Boolean get() = undoStack[kind].orEmpty().isNotEmpty()
    val canRedo: Boolean get() = redoStack[kind].orEmpty().isNotEmpty()
    val isEmpty: Boolean get() = ops.isEmpty()
    val hasAnyContent: Boolean get() = layers.values.any { it.isNotEmpty() }
    private val shortEdge: Int get() = min(width, height)

    fun strokeWidth(level: Int): Float = shortEdge * StrokeFractions[level.coerceIn(0, 2)]
    fun brushWidth(level: Int): Float = shortEdge * BrushFractions[level.coerceIn(0, 2)]
    fun textSize(level: Int): Float = shortEdge * TextFractions[level.coerceIn(0, 2)]

    /** 新增一笔，记入撤销栈并清空重做。 */
    fun add(op: BoardOp): AnnotationBoard = replace(ops + op, recordFrom = ops)

    /** 用 [next] 替换当前画法的笔画；[recordFrom] 非空时把它记为一步撤销。 */
    fun replace(next: List<BoardOp>, recordFrom: List<BoardOp>?): AnnotationBoard = copy(
        layers = layers + (kind to next),
        undoStack = if (recordFrom == null) undoStack else undoStack + (kind to undoStack[kind].orEmpty() + listOf(recordFrom)),
        redoStack = if (recordFrom == null) redoStack else redoStack - kind,
    )

    fun clear(): AnnotationBoard = if (ops.isEmpty()) this else replace(emptyList(), recordFrom = ops)

    fun undo(): AnnotationBoard {
        val stack = undoStack[kind].orEmpty()
        if (stack.isEmpty()) return this
        return copy(
            layers = layers + (kind to stack.last()),
            undoStack = undoStack + (kind to stack.dropLast(1)),
            redoStack = redoStack + (kind to redoStack[kind].orEmpty() + listOf(ops)),
        )
    }

    fun redo(): AnnotationBoard {
        val stack = redoStack[kind].orEmpty()
        if (stack.isEmpty()) return this
        return copy(
            layers = layers + (kind to stack.last()),
            redoStack = redoStack + (kind to stack.dropLast(1)),
            undoStack = undoStack + (kind to undoStack[kind].orEmpty() + listOf(ops)),
        )
    }

    /** 切换标注与选区；草图画板不切换画法。 */
    fun switchTo(target: ImageAnnotationKind): AnnotationBoard {
        if (kind == ImageAnnotationKind.SKETCH || target == ImageAnnotationKind.SKETCH) return this
        return copy(kind = target)
    }

    /** 另一种画法也有内容时，提示完成时只提交当前这种。 */
    val hasConflictingLayer: Boolean
        get() = kind != ImageAnnotationKind.SKETCH && layers[otherImageKind(kind)].orEmpty().isNotEmpty()

    /** 草图换画布尺寸；已有笔画时由调用方先确认清空。 */
    fun resizeSketch(size: SketchCanvasSize, discardContent: Boolean): AnnotationBoard {
        if (kind != ImageAnnotationKind.SKETCH || (size.width == width && size.height == height)) return this
        if (!isEmpty && !discardContent) return this
        return AnnotationBoard(kind, size.width, size.height)
    }

    /** 去掉 [point] 处被碰到的整笔，返回剩余笔画；没碰到时原样返回同一列表。 */
    fun eraseAt(point: BoardPoint, measurer: LabelMeasurer, tolerance: Float = shortEdge * 0.012f): List<BoardOp> {
        val remaining = ops.filterNot { hits(it, point, tolerance, measurer) }
        return if (remaining.size == ops.size) ops else remaining
    }

    fun snapshot(): String = BoardJson.encodeToString(BoardSnapshot.serializer(), BoardSnapshot(kind, width, height, layers.filterValues { it.isNotEmpty() }))

    companion object {
        fun blank(kind: ImageAnnotationKind, width: Int, height: Int) = AnnotationBoard(kind, width, height)

        /** 按快照自带的画法与尺寸解析，用于界面重建后恢复；无法解析时返回 null。 */
        fun parse(snapshot: String?): AnnotationBoard? =
            snapshot?.let { runCatching { BoardJson.decodeFromString(BoardSnapshot.serializer(), it) }.getOrNull() }
                ?.let { AnnotationBoard(it.kind, it.width, it.height, it.layers) }

        /** 从再次编辑的快照恢复；尺寸不符或无法解析时返回空白画板。 */
        fun restore(snapshot: String?, kind: ImageAnnotationKind, width: Int, height: Int): AnnotationBoard {
            val parsed = snapshot?.let { runCatching { BoardJson.decodeFromString(BoardSnapshot.serializer(), it) }.getOrNull() }
            if (parsed == null || parsed.width != width || parsed.height != height) return blank(kind, width, height)
            return AnnotationBoard(kind, width, height, parsed.layers)
        }
    }
}

@Serializable
internal data class BoardSnapshot(
    val kind: ImageAnnotationKind,
    val width: Int,
    val height: Int,
    val layers: Map<ImageAnnotationKind, List<BoardOp>>,
)

internal val BoardJson = Json { ignoreUnknownKeys = true; classDiscriminator = "t" }

private fun otherImageKind(kind: ImageAnnotationKind) =
    if (kind == ImageAnnotationKind.MARKUP) ImageAnnotationKind.MASK else ImageAnnotationKind.MARKUP

/** 点到线段的距离。 */
internal fun segmentDistance(p: BoardPoint, a: BoardPoint, b: BoardPoint): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val lengthSquared = dx * dx + dy * dy
    val t = if (lengthSquared == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
    return hypot(p.x - a.x - t * dx, p.y - a.y - t * dy)
}

internal fun hits(op: BoardOp, p: BoardPoint, tolerance: Float, measurer: LabelMeasurer): Boolean = when (op) {
    is BoardOp.Pen -> op.points.indices.any { i ->
        segmentDistance(p, op.points[i], op.points.getOrElse(i + 1) { op.points[i] }) <= op.width / 2 + tolerance
    }
    is BoardOp.Line -> segmentDistance(p, op.start, op.end) <= op.width / 2 + tolerance
    is BoardOp.Box -> {
        val left = min(op.start.x, op.end.x); val right = max(op.start.x, op.end.x)
        val top = min(op.start.y, op.end.y); val bottom = max(op.start.y, op.end.y)
        val corners = listOf(BoardPoint(left, top), BoardPoint(right, top), BoardPoint(right, bottom), BoardPoint(left, bottom))
        corners.indices.any { segmentDistance(p, corners[it], corners[(it + 1) % 4]) <= op.width / 2 + tolerance }
    }
    is BoardOp.Label -> abs(p.x - op.at.x) <= measurer.width(op.text, op.size) / 2 + tolerance &&
        abs(p.y - op.at.y) <= op.size / 2 + tolerance
    is BoardOp.MaskStroke -> false
}

/** 拖出的直线或方框太短时视为误触，不落笔。 */
fun isNegligibleDrag(start: BoardPoint, end: BoardPoint, board: AnnotationBoard): Boolean =
    hypot(end.x - start.x, end.y - start.y) < min(board.width, board.height) * 0.01f
