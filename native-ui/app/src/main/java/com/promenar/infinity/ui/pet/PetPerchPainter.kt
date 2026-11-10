package com.promenar.infinity.ui.pet

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin

/**
 * 栖息点（云朵、七彩泡泡）与收起闪光的绘制，见 v0.3 增补第 2、4 节。
 *
 * 基座自带两种默认外观；宠物包在 `props.perch` 里提供图片时改画图片。所有坐标为图层内的像素。
 */
internal class PetPerchPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glowBlurRadius = 0f
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val rect = RectF()
    private val star = Path()
    private val body = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(0x57FFFFFF, 0x1A96C8FF, 0x42BE96FF),
        floatArrayOf(0f, 0.7f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val rainbow = SweepGradient(
        0f, 0f,
        intArrayOf(
            0xFFFF8FB1.toInt(), 0xFFFFD36E.toInt(), 0xFF9BF0A5.toInt(),
            0xFF7FD4FF.toInt(), 0xFFB79BFF.toInt(), 0xFFFF8FB1.toInt(),
        ),
        null,
    )

    /** 被提起时的提示透明度，逐帧向目标值靠拢。 */
    private var ghostAlpha = 1f

    /**
     * @param seatY 落座线的纵坐标。
     * @param ghost 宠物被提起时的提示状态：半透明，[hot] 为真（已进入可落座范围）时变实并略微放大。
     * @param appear 出现进度；[vanish] 消失进度，按 [exit] 的方式散开、破开或被吹走。
     * @param bitmap 宠物包提供的图片；为 null 时画默认外观。
     */
    fun draw(
        canvas: Canvas,
        kind: String,
        x: Float,
        seatY: Float,
        density: Float,
        ghost: Boolean,
        hot: Boolean,
        appear: Float,
        vanish: Float,
        exit: PetEngine.PerchExit,
        alpha: Float,
        time: Float,
        bitmap: Bitmap?,
        seatU: Float?,
        seatV: Float?,
        widthDp: Int?,
    ) {
        val target = if (ghost) (if (hot) 0.95f else 0.38f) else 1f
        ghostAlpha += (target - ghostAlpha) * 0.2f
        val eased = appear * appear * (3f - 2f * appear)
        val size = (0.6f + 0.4f * eased) * if (ghost && hot) 1.08f else 1f
        val opacity = (1f - vanish) * appear * ghostAlpha * alpha
        if (opacity <= 0.004f) return
        when {
            bitmap != null -> drawBitmap(canvas, bitmap, x, seatY, density, size * (1f + vanish * 0.3f), opacity, seatU, seatV, widthDp)
            kind == BUBBLE -> drawBubble(canvas, x, seatY, density, size, vanish, exit, opacity, time)
            else -> drawCloud(canvas, x, seatY, density, size, vanish, exit, opacity)
        }
    }

    private fun drawCloud(
        canvas: Canvas,
        x: Float,
        seatY: Float,
        density: Float,
        size: Float,
        vanish: Float,
        exit: PetEngine.PerchExit,
        opacity: Float,
    ) {
        val blown = exit == PetEngine.PerchExit.BLOWN
        val spread = vanish * if (blown) 90f else 26f
        val glowRadius = 14f * density
        if (glowRadius != glowBlurRadius) {
            glowBlurRadius = glowRadius
            glow.maskFilter = BlurMaskFilter(glowRadius, BlurMaskFilter.Blur.NORMAL)
        }
        glow.color = CLOUD_GLOW
        glow.alpha = (opacity * (CLOUD_GLOW ushr 24)).toInt().coerceIn(0, 255)
        fill.shader = null
        fill.color = CLOUD_COLOR
        fill.alpha = (opacity * 0.96f * 255f).toInt().coerceIn(0, 255)
        // 先画整片光晕再画云体，光晕不会盖到相邻的云团上。
        repeat(2) { pass ->
            val paint = if (pass == 0) glow else fill
            for (index in CLOUD_PUFFS.indices step 3) {
                val cx = CLOUD_PUFFS[index]
                val cy = CLOUD_PUFFS[index + 1]
                val radius = CLOUD_PUFFS[index + 2]
                val side = if (cx == 0f) 1f else sign(cx)
                val drift = side * spread * (if (blown) 1f else 0.6f) + if (blown) spread else 0f
                canvas.drawCircle(
                    x + (cx + drift) * size * density,
                    seatY + (cy - vanish * 10f) * size * density,
                    radius * size * (1f - vanish * 0.4f) * density,
                    paint,
                )
            }
        }
    }

    private fun drawBubble(
        canvas: Canvas,
        x: Float,
        seatY: Float,
        density: Float,
        size: Float,
        vanish: Float,
        exit: PetEngine.PerchExit,
        opacity: Float,
        time: Float,
    ) {
        val centerY = seatY + BUBBLE_DROP * density
        val radius = BUBBLE_RADIUS * size * (1f + vanish * 0.5f) * density
        val alpha = (opacity * 255f).toInt().coerceIn(0, 255)

        matrix.setScale(radius, radius)
        matrix.postTranslate(x - radius * 0.25f, centerY - radius * 0.3f)
        body.setLocalMatrix(matrix)
        fill.shader = body
        fill.alpha = alpha
        canvas.drawCircle(x, centerY, radius, fill)

        matrix.setRotate(time * 34f)
        matrix.postTranslate(x, centerY)
        rainbow.setLocalMatrix(matrix)
        fill.shader = rainbow
        fill.alpha = (alpha * 0.2f).toInt()
        canvas.drawCircle(x, centerY, radius, fill)
        fill.shader = null

        stroke.shader = rainbow
        stroke.alpha = alpha
        stroke.strokeWidth = 3.6f * density
        canvas.drawCircle(x, centerY, radius, stroke)

        stroke.shader = null
        stroke.color = Color.WHITE
        stroke.alpha = (alpha * 0.85f).toInt()
        stroke.strokeWidth = 2.2f * density
        val inner = radius * 0.74f
        rect.set(x - inner, centerY - inner, x + inner, centerY + inner)
        canvas.drawArc(rect, 200f, 52f, false, stroke)

        if (exit == PetEngine.PerchExit.POP && vanish > 0f) {
            // 破开时向四周溅开的水珠。
            fill.color = DROP_COLOR
            fill.alpha = alpha
            val ring = radius + vanish * 60f * density
            for (index in 0 until DROPS) {
                val angle = index * 2f * PI.toFloat() / DROPS
                canvas.drawCircle(x + cos(angle) * ring, centerY + sin(angle) * ring, 2.2f * density, fill)
            }
        }
    }

    private fun drawBitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        x: Float,
        seatY: Float,
        density: Float,
        size: Float,
        opacity: Float,
        seatU: Float?,
        seatV: Float?,
        widthDp: Int?,
    ) {
        val width = (widthDp ?: DEFAULT_WIDTH_DP) * density * size
        val height = width * bitmap.height / bitmap.width
        val left = x - (seatU ?: 0.5f) * width
        val top = seatY - (seatV ?: 0.45f) * height
        rect.set(left, top, left + width, top + height)
        bitmapPaint.alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        canvas.drawBitmap(bitmap, null, rect, bitmapPaint)
    }

    /** 被甩出收起时留下的四角星闪光；[progress] 为 0 到 1。 */
    fun drawTwinkle(canvas: Canvas, x: Float, y: Float, progress: Float, density: Float) {
        val wave = sin(progress.coerceIn(0f, 1f) * PI.toFloat())
        if (wave <= 0f) return
        val outer = (4f + 22f * wave) * density
        val inner = outer * 0.35f
        star.rewind()
        for (index in 0 until 8) {
            val angle = index * PI.toFloat() / 4f + progress * 1.2f
            val radius = if (index % 2 == 0) outer else inner
            val px = x + cos(angle) * radius
            val py = y + sin(angle) * radius
            if (index == 0) star.moveTo(px, py) else star.lineTo(px, py)
        }
        star.close()
        fill.shader = null
        fill.color = TWINKLE_COLOR
        fill.alpha = (wave * 255f).toInt().coerceIn(0, 255)
        canvas.drawPath(star, fill)
    }

    companion object {
        const val DEFAULT_WIDTH_DP = 120
        const val BUBBLE = "bubble"

        /** 泡泡圆心在落座线下方的距离与半径（dp）。 */
        const val BUBBLE_DROP = 30f
        const val BUBBLE_RADIUS = 40f
        private const val DROPS = 18
        private const val CLOUD_COLOR = 0xFFF4F8FF.toInt()
        private const val CLOUD_GLOW = 0x7378AAFF
        private const val DROP_COLOR = 0xFF8FC0FF.toInt()
        private const val TWINKLE_COLOR = 0xFFFFF6C8.toInt()

        /** 默认云朵的各团：相对落座点的横、纵偏移与半径（dp），三个一组。 */
        private val CLOUD_PUFFS = floatArrayOf(
            -38f, 14f, 17f,
            -18f, 6f, 22f,
            6f, 3f, 25f,
            30f, 9f, 19f,
            46f, 17f, 13f,
            -4f, 20f, 24f,
            22f, 22f, 18f,
            -28f, 24f, 14f,
        )
    }
}
