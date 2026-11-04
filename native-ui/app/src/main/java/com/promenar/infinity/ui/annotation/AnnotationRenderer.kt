package com.promenar.infinity.ui.annotation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import com.promenar.infinity.domain.media.ImageAnnotationKind
import java.io.ByteArrayOutputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 选区在画板与预览图里的显示色，导出蒙版时不使用。 */
const val MaskTint: Int = 0xFFFF5A8C.toInt()

/** 预览图长边上限，与发送时的图片重编码上限一致。 */
const val AnnotationPreviewMaxEdge = 1568

/**
 * 同一套绘制同时用于屏幕显示与原始像素导出，保证所见即所得。
 * [scale] 把画板像素换算到目标画布像素。
 */
class AnnotationRenderer {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
    }
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val path = Path()

    val measurer = LabelMeasurer { text, size ->
        textPaint.textSize = size
        textPaint.measureText(text)
    }

    fun drawOps(canvas: Canvas, ops: List<BoardOp>, scale: Float) {
        ops.forEach { drawOp(canvas, it, scale) }
    }

    fun drawOp(canvas: Canvas, op: BoardOp, scale: Float) {
        when (op) {
            is BoardOp.Pen -> {
                stroke.color = op.color
                stroke.strokeWidth = op.width * scale
                canvas.drawPath(smoothPath(op.points, scale), stroke)
            }
            is BoardOp.Line -> {
                stroke.color = op.color
                stroke.strokeWidth = op.width * scale
                val ax = op.start.x * scale; val ay = op.start.y * scale
                val bx = op.end.x * scale; val by = op.end.y * scale
                canvas.drawLine(ax, ay, bx, by, stroke)
                if (op.arrow) {
                    val angle = atan2(by - ay, bx - ax)
                    val head = max(op.width * 4.2f, 14f) * scale
                    for (side in intArrayOf(-1, 1)) {
                        val a = angle + side * 0.5f
                        canvas.drawLine(bx, by, bx - head * cos(a), by - head * sin(a), stroke)
                    }
                }
            }
            is BoardOp.Box -> {
                stroke.color = op.color
                stroke.strokeWidth = op.width * scale
                canvas.drawRect(
                    min(op.start.x, op.end.x) * scale, min(op.start.y, op.end.y) * scale,
                    max(op.start.x, op.end.x) * scale, max(op.start.y, op.end.y) * scale, stroke,
                )
            }
            is BoardOp.Label -> {
                val size = op.size * scale
                textPaint.textSize = size
                textPaint.color = op.color
                val metrics = textPaint.fontMetrics
                val baseline = op.at.y * scale - (metrics.ascent + metrics.descent) / 2
                if (op.outline) {
                    outlinePaint.textSize = size
                    outlinePaint.strokeWidth = size * 0.18f
                    outlinePaint.color = if (isLightColor(op.color)) 0xD9000000.toInt() else 0xF2FFFFFF.toInt()
                    canvas.drawText(op.text, op.at.x * scale, baseline, outlinePaint)
                }
                canvas.drawText(op.text, op.at.x * scale, baseline, textPaint)
            }
            is BoardOp.MaskStroke -> Unit
        }
    }

    /** 草图纸面：与导出图层同为白色，不随界面深浅色变化。 */
    fun drawPaper(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
    }

    /** 在独立图层里画选区形状（擦除笔画挖空），再以 [color] 与 [alpha] 合成到 [canvas]。 */
    fun drawMask(canvas: Canvas, ops: List<BoardOp>, scale: Float, color: Int, alpha: Int) {
        val strokes = ops.filterIsInstance<BoardOp.MaskStroke>()
        if (strokes.isEmpty()) return
        fill.alpha = alpha
        val layer = canvas.saveLayer(null, fill)
        stroke.color = color
        strokes.forEach { s ->
            val paint = if (s.erase) clear else stroke
            paint.strokeWidth = s.width * scale
            canvas.drawPath(smoothPath(s.points, scale), paint)
        }
        canvas.restoreToCount(layer)
        fill.alpha = 255
    }

    /** 按原始像素导出图层：标注为透明底；选区为涂抹处透明、其余不透明黑；草图为白底。 */
    fun exportLayer(kind: ImageAnnotationKind, ops: List<BoardOp>, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        when (kind) {
            ImageAnnotationKind.MARKUP -> drawOps(canvas, ops, 1f)
            ImageAnnotationKind.SKETCH -> {
                canvas.drawColor(Color.WHITE)
                drawOps(canvas, ops, 1f)
            }
            ImageAnnotationKind.MASK -> {
                canvas.drawColor(Color.BLACK)
                val shape = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                drawMask(Canvas(shape), ops, 1f, Color.WHITE, 255)
                val punch = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
                canvas.drawBitmap(shape, 0f, 0f, punch)
                shape.recycle()
            }
        }
        return bitmap
    }

    /** 给对话模型看的合成预览：原图＋标注或半透明选区，草图即草图；长边不超过 [AnnotationPreviewMaxEdge]。 */
    fun exportPreview(kind: ImageAnnotationKind, source: Bitmap?, ops: List<BoardOp>, width: Int, height: Int): Bitmap {
        val scale = min(1f, AnnotationPreviewMaxEdge.toFloat() / max(width, height))
        val bitmap = Bitmap.createBitmap((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (source != null) {
            canvas.drawBitmap(source, null, RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            canvas.drawColor(Color.WHITE)
        }
        if (kind == ImageAnnotationKind.MASK) drawMask(canvas, ops, scale, MaskTint, 128) else drawOps(canvas, ops, scale)
        return bitmap
    }

    private fun smoothPath(points: List<BoardPoint>, scale: Float): Path {
        path.rewind()
        if (points.isEmpty()) return path
        val first = points.first()
        path.moveTo(first.x * scale, first.y * scale)
        if (points.size == 1) {
            // 单击也留下一个圆点。
            path.lineTo(first.x * scale + 0.01f, first.y * scale)
            return path
        }
        for (i in 1 until points.size - 1) {
            val p = points[i]
            val next = points[i + 1]
            path.quadTo(p.x * scale, p.y * scale, (p.x + next.x) / 2 * scale, (p.y + next.y) / 2 * scale)
        }
        val last = points.last()
        path.lineTo(last.x * scale, last.y * scale)
        return path
    }
}

private fun isLightColor(color: Int): Boolean {
    val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
    return 0.299 * r + 0.587 * g + 0.114 * b > 150
}

fun Bitmap.encodePng(): ByteArray = ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
