package com.promenar.infinity.ui.common.motion

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.chat.ComposerOutlinePaints
import com.promenar.infinity.ui.chat.ScopeScreenSpec
import com.promenar.infinity.ui.chat.drawHaloRing
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityPureWhite
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.common.InfinityMetaLine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * 零 BlurMaskFilter 视觉 1:1 无损重构对照原型（Wave 1 修订版）：
 * 1. 用「解析高斯误差函数 erf 预卷积精灵图 + 与原版完全一致的 BlendMode/灯芯参数」替代动态 Path + BlurMaskFilter，
 *    在数学与光学上等价还原原版光晕扩散半径、边缘柔度与深/浅主题混合，同时完全消除 Skia Vulkan Scratch Surface 泄漏；
 * 2. 浮岛呼吸外圈采用与线上 ComposerHaloAgsl 同源的圆角矩形 SDF + 1D erf 环带积分 + 线性渐变（原型用缓存 Bitmap，
 *    Wave 2 真机 API 33+ 直接合并进全 GPU AGSL RuntimeShader，API 31-32 使用同源缓存图）；
 * 3. 在同画幅中与原版 BlurMaskFilter 逐项并排对比，外加 4 帧关键帧序列 bar。
 */

private const val SQRT_2 = 1.4142135f

/**
 * 与生产 [LedBatch] 1:1 对标的零模糊批处理画笔：
 * - 熄灭底格与清晰灯芯保留纯几何 Path（零 BlurMaskFilter，不引发 fallback）；
 * - 光晕阶段用按 `(colorArgb, gwQ, ghQ, blurQ)` 缓存的 2D 可分离 erf 高斯卷积 ARGB_8888 贴图绘制，
 *   保持原版 Blur.NORMAL 扩展尺度与 PLUS/SRC_OVER 混合行为。
 */
internal class LosslessLedBatch(
    private val levels: Int,
    private val glowFrom: Int = 2,
    private val colors: Int = 2,
) {
    private data class RectGlow(val cx: Float, val cy: Float, val gw: Float, val gh: Float)
    private data class SpriteKey(val colorOpaque: Int, val gwQ: Int, val ghQ: Int, val blurQ: Int)
    private class CachedSprite(val bitmap: Bitmap, val halfW: Float, val halfH: Float)

    private val core = Array(colors) { Array(levels) { Path() } }
    private val off = Path()
    private val glows = Array(colors) { Array(levels) { ArrayList<RectGlow>(32) } }
    private val sprites = HashMap<SpriteKey, CachedSprite>(16)

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    fun reset() {
        for (c in 0 until colors) for (l in 0 until levels) {
            core[c][l].rewind()
            glows[c][l].clear()
        }
        off.rewind()
    }

    private fun level(intensity: Float): Int = (intensity * levels).toInt().coerceIn(0, levels - 1)

    fun dot(color: Int, intensity: Float, x: Float, y: Float, size: Float, glowSize: Float) {
        val l = level(intensity)
        core[color][l].addRect(x - size / 2f, y - size / 2f, x + size / 2f, y + size / 2f, Path.Direction.CW)
        if (l >= glowFrom) glows[color][l].add(RectGlow(x, y, glowSize, glowSize))
    }

    fun rect(color: Int, intensity: Float, x: Float, y: Float, w: Float, h: Float, glowPad: Float) {
        val l = level(intensity)
        core[color][l].addRect(x, y, x + w, y + h, Path.Direction.CW)
        if (l >= glowFrom) {
            glows[color][l].add(RectGlow(x + w / 2f, y + h / 2f, w + 2f * glowPad, h + 2f * glowPad))
        }
    }

    fun offDot(x: Float, y: Float, size: Float) =
        off.addRect(x - size / 2f, y - size / 2f, x + size / 2f, y + size / 2f, Path.Direction.CW)

    fun offRect(x: Float, y: Float, w: Float, h: Float) =
        off.addRect(x, y, x + w, y + h, Path.Direction.CW)

    private fun spriteFor(colorOpaque: Int, gw: Float, gh: Float, blur: Float): CachedSprite {
        val key = SpriteKey(
            colorOpaque = colorOpaque,
            gwQ = (gw * 4f).roundToInt().coerceAtLeast(1),
            ghQ = (gh * 4f).roundToInt().coerceAtLeast(1),
            blurQ = (blur * 4f).roundToInt().coerceAtLeast(1),
        )
        return sprites.getOrPut(key) { buildGaussianBoxSprite(colorOpaque, key.gwQ / 4f, key.ghQ / 4f, key.blurQ / 4f) }
    }

    private fun buildGaussianBoxSprite(colorOpaque: Int, gw: Float, gh: Float, blur: Float): CachedSprite {
        val sigma = skiaSigma(blur).coerceAtLeast(0.5f)
        val pad = ceil(3f * sigma).toInt().coerceAtLeast(2)
        val bmpW = (ceil(gw).toInt() + pad * 2).coerceAtLeast(4)
        val bmpH = (ceil(gh).toInt() + pad * 2).coerceAtLeast(4)
        val centerX = (bmpW - 1) / 2f
        val centerY = (bmpH - 1) / 2f
        val halfGw = gw / 2f
        val halfGh = gh / 2f
        val invS2 = 1f / (SQRT_2 * sigma)

        val ix = FloatArray(bmpW) { px ->
            val x = px - centerX
            0.5f * (erfApprox((x + halfGw) * invS2) - erfApprox((x - halfGw) * invS2))
        }
        val iy = FloatArray(bmpH) { py ->
            val y = py - centerY
            0.5f * (erfApprox((y + halfGh) * invS2) - erfApprox((y - halfGh) * invS2))
        }

        val r = (colorOpaque ushr 16) and 0xFF
        val g = (colorOpaque ushr 8) and 0xFF
        val b = colorOpaque and 0xFF
        val pixels = IntArray(bmpW * bmpH)
        for (py in 0 until bmpH) {
            val rowVal = iy[py]
            val offset = py * bmpW
            for (px in 0 until bmpW) {
                val cov = (ix[px] * rowVal).coerceIn(0f, 1f)
                val a = (cov * 255f).roundToInt().coerceIn(0, 255)
                // Premultiply RGB with alpha cleanly via ARGB_8888 Bitmap.setPixels
                pixels[offset + px] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, bmpW, 0, 0, bmpW, bmpH)
        }
        return CachedSprite(bmp, bmpW / 2f, bmpH / 2f)
    }

    fun draw(
        canvas: Canvas,
        palette: List<Color>,
        dark: Boolean,
        blur: Float,
        offColor: Color = Color.Transparent,
        whitenFrom: Int = 0,
        glowAlpha: Float = 0.8f,
        alpha: Float = 1f,
    ) {
        if (offColor.alpha > 0f) {
            corePaint.blendMode = BlendMode.SRC_OVER
            corePaint.color = offColor.copy(alpha = offColor.alpha * alpha).toArgb()
            canvas.drawPath(off, corePaint)
        }
        if (blur > 0f) {
            haloPaint.blendMode = if (dark) BlendMode.PLUS else BlendMode.SRC_OVER
            for (c in 0 until colors) {
                val opaque = palette[c].toArgb() or (0xFF shl 24)
                for (l in glowFrom until levels) {
                    val items = glows[c][l]
                    if (items.isEmpty()) continue
                    val layerAlpha = clamp01((l + 1f) / levels * glowAlpha * alpha)
                    haloPaint.alpha = (layerAlpha * 255f).roundToInt().coerceIn(0, 255)
                    for (i in 0 until items.size) {
                        val item = items[i]
                        val sprite = spriteFor(opaque, item.gw, item.gh, blur)
                        dst.set(item.cx - sprite.halfW, item.cy - sprite.halfH, item.cx + sprite.halfW, item.cy + sprite.halfH)
                        canvas.drawBitmap(sprite.bitmap, null, dst, haloPaint)
                    }
                }
            }
        }
        corePaint.blendMode = BlendMode.SRC_OVER
        for (c in 0 until colors) for (l in 0 until levels) {
            if (core[c][l].isEmpty) continue
            val base = palette[c]
            val tint = if (dark && l >= whitenFrom) lerp(base, InfinityPureWhite, 0.3f) else base
            corePaint.color = tint.copy(alpha = clamp01((l + 1f) / levels * alpha)).toArgb()
            canvas.drawPath(core[c][l], corePaint)
        }
    }
}

/** 缓存圆角呼吸光环 Bitmap（SDF + erf 解析环带高斯积分，对标原版 drawHaloRing）。 */
internal class LosslessHaloRingCache {
    private data class HaloKey(
        val wPx: Int,
        val hPx: Int,
        val blurPx: Int,
        val strokePxQ: Int,
        val cornerPxQ: Int,
        val colorA: Int,
        val colorB: Int,
    )

    private class HaloEntry(val bitmap: Bitmap, val padPx: Float)

    private val entries = HashMap<HaloKey, HaloEntry>(4)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        alpha: Float,
        blurPx: Float,
        strokeWidthPx: Float,
        cornerPx: Float,
        colorA: Color,
        colorB: Color,
    ) {
        if (alpha <= 0.004f || width <= 0f || height <= 0f) return
        val blurKey = blurPx.roundToInt().coerceAtLeast(1)
        val key = HaloKey(
            wPx = width.roundToInt().coerceAtLeast(1),
            hPx = height.roundToInt().coerceAtLeast(1),
            blurPx = blurKey,
            strokePxQ = (strokeWidthPx * 2f).roundToInt().coerceAtLeast(1),
            cornerPxQ = (cornerPx * 2f).roundToInt().coerceAtLeast(1),
            colorA = colorA.toArgb() or (0xFF shl 24),
            colorB = colorB.toArgb() or (0xFF shl 24),
        )
        val entry = entries.getOrPut(key) {
            buildHaloBitmap(
                w = width,
                h = height,
                blurPx = blurKey.toFloat(),
                strokeWidthPx = strokeWidthPx,
                cornerPx = cornerPx,
                colorA = key.colorA,
                colorB = key.colorB,
            )
        }
        val pad = entry.padPx
        dst.set(-pad, -pad, width + pad, height + pad)
        paint.alpha = (clamp01(alpha) * 255f).roundToInt().coerceIn(0, 255)
        canvas.drawBitmap(entry.bitmap, null, dst, paint)
    }

    private fun buildHaloBitmap(
        w: Float,
        h: Float,
        blurPx: Float,
        strokeWidthPx: Float,
        cornerPx: Float,
        colorA: Int,
        colorB: Int,
    ): HaloEntry {
        val sigma = skiaSigma(blurPx).coerceAtLeast(0.5f)
        val halfStroke = strokeWidthPx / 2f
        val pad = ceil(3.2f * sigma + halfStroke).coerceAtLeast(4f)
        // 半分辨率渲染足够保持高斯低频平滑且显著压缩一次性生成成本。
        val scale = 0.5f
        val totalW = w + pad * 2f
        val totalH = h + pad * 2f
        val bmpW = (totalW * scale).roundToInt().coerceAtLeast(8)
        val bmpH = (totalH * scale).roundToInt().coerceAtLeast(8)
        val halfBoxW = w / 2f
        val halfBoxH = h / 2f
        val invS2 = 1f / (SQRT_2 * sigma)
        val ar = (colorA ushr 16) and 0xFF
        val ag = (colorA ushr 8) and 0xFF
        val ab = colorA and 0xFF
        val br = (colorB ushr 16) and 0xFF
        val bg = (colorB ushr 8) and 0xFF
        val bb = colorB and 0xFF
        val diagDenom = max(1f, w * w + h * h)

        val pixels = IntArray(bmpW * bmpH)
        for (py in 0 until bmpH) {
            val fy = (py + 0.5f) / scale - pad
            val relY = fy - halfBoxH
            val rowOffset = py * bmpW
            for (px in 0 until bmpW) {
                val fx = (px + 0.5f) / scale - pad
                val relX = fx - halfBoxW
                val d = roundedBoxSdf(relX, relY, halfBoxW, halfBoxH, cornerPx)
                val band = 0.5f * (erfApprox((d + halfStroke) * invS2) - erfApprox((d - halfStroke) * invS2))
                val cov = band.coerceIn(0f, 1f)
                if (cov <= 0.001f) {
                    pixels[rowOffset + px] = 0
                } else {
                    // 匹配原版 LinearGradient(0, 0, w, h, colorA, colorB, CLAMP) 的对角线投影梯度。
                    val tGrad = ((fx * w + fy * h) / diagDenom).coerceIn(0f, 1f)
                    val rr = (ar + (br - ar) * tGrad).roundToInt().coerceIn(0, 255)
                    val gg = (ag + (bg - ag) * tGrad).roundToInt().coerceIn(0, 255)
                    val bbb = (ab + (bb - ab) * tGrad).roundToInt().coerceIn(0, 255)
                    val dither = (motionHash(px.toFloat(), py.toFloat()) - 0.5f) / 255f
                    val a = ((cov + dither).coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
                    pixels[rowOffset + px] = (a shl 24) or (rr shl 16) or (gg shl 8) or bbb
                }
            }
        }
        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, bmpW, 0, 0, bmpW, bmpH)
        }
        return HaloEntry(bmp, pad)
    }
}

/** 缓存真实高斯圆点贴图，沿曲线均匀重采样以 1:1 复现 BlurMaskFilter 柔光轨迹。 */
internal class LosslessInfinityLineRenderer {
    private data class DotKey(val colorOpaque: Int, val blurQ: Int)
    private class DotSprite(val bitmap: Bitmap, val halfSize: Float)

    private val sprites = HashMap<DotKey, DotSprite>(8)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val p = FloatArray(2)
    private val q = FloatArray(2)

    private fun spriteFor(colorOpaque: Int, blur: Float): DotSprite {
        val key = DotKey(colorOpaque, (blur * 4f).roundToInt().coerceAtLeast(1))
        return sprites.getOrPut(key) { buildGaussianCircleSprite(colorOpaque, key.blurQ / 4f) }
    }

    private fun buildGaussianCircleSprite(colorOpaque: Int, blur: Float): DotSprite {
        val sigma = skiaSigma(blur).coerceAtLeast(0.5f)
        val radius = ceil(3f * sigma).toInt().coerceAtLeast(2)
        val size = radius * 2 + 1
        val inv2S2 = 1f / (2f * sigma * sigma)
        val r = (colorOpaque ushr 16) and 0xFF
        val g = (colorOpaque ushr 8) and 0xFF
        val b = colorOpaque and 0xFF
        val pixels = IntArray(size * size)
        for (py in 0 until size) {
            val dy = py - radius
            val dy2 = dy * dy
            val off = py * size
            for (px in 0 until size) {
                val dx = px - radius
                val cov = exp(-(dx * dx + dy2) * inv2S2).coerceIn(0f, 1f)
                val a = (cov * 255f).roundToInt().coerceIn(0, 255)
                pixels[off + px] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
        return DotSprite(bmp, size / 2f)
    }

    fun drawTails(
        native: Canvas,
        cx: Float,
        cy: Float,
        a: Float,
        k: Float,
        palette: MotionPalette,
        tails: ((Float, Color, Float, Float) -> Unit) -> Unit,
    ) {
        val blur = 2.5f * k
        val sigma = skiaSigma(blur).coerceAtLeast(0.5f)
        val normDenom = (2f * PI.toFloat() * sigma * sigma).coerceAtLeast(1f)
        // Pass 0: 无损预卷积 2D 高斯卷积积分：权重精确按 dA / (2πσ²) 归一化，消除多次重叠过曝。
        haloPaint.blendMode = if (palette.dark) BlendMode.PLUS else BlendMode.SRC_OVER
        val subSteps = 3
        tails { head, color, length, alpha ->
            val segments = 34
            val totalSamples = segments * subSteps
            for (step in totalSamples - 1 downTo 0) {
                val jFloat = step.toFloat() / subSteps
                val jNextFloat = (step + 1f) / subSteps
                val j = step / subSteps
                val f = 1f - jFloat / segments
                val s0 = head - jFloat / segments * length
                val s1 = head - jNextFloat / segments * length
                lemniscateAt(s0, p)
                lemniscateAt(s1, q)
                val x0 = cx + a * p[0]
                val y0 = cy + a * p[1]
                val ds = hypot(a * (p[0] - q[0]), a * (p[1] - q[1])).coerceAtLeast(0.1f)
                val segAlpha = clamp01(f * f * alpha)
                val strokeW = (0.6f + 1.6f * f) * k * (0.55f + 0.45f * abs(cos(s0)))
                val sampleAlpha = clamp01(segAlpha * (strokeW * ds / normDenom))
                if (sampleAlpha <= 0.002f) continue
                val segColor = if (j < 2) palette.hot else color
                val opaque = segColor.toArgb() or (0xFF shl 24)
                val sprite = spriteFor(opaque, blur)
                haloPaint.alpha = (sampleAlpha * 255f).roundToInt().coerceIn(0, 255)
                val hs = sprite.halfSize
                dst.set(x0 - hs, y0 - hs, x0 + hs, y0 + hs)
                native.drawBitmap(sprite.bitmap, null, dst, haloPaint)
            }
        }
        // Pass 1: 与原版完全一致的清晰核心线条。
        linePaint.blendMode = BlendMode.SRC_OVER
        tails { head, color, length, alpha ->
            val segments = 34
            for (j in segments - 1 downTo 0) {
                val s0 = head - j.toFloat() / segments * length
                val s1 = head - (j + 1f) / segments * length
                val f = 1f - j.toFloat() / segments
                lemniscateAt(s0, p)
                lemniscateAt(s1, q)
                linePaint.color = (if (j < 2) palette.hot else color).copy(alpha = clamp01(f * f * alpha)).toArgb()
                linePaint.strokeWidth = (0.6f + 1.6f * f) * k * (0.55f + 0.45f * abs(cos(s0)))
                native.drawLine(cx + a * q[0], cy + a * q[1], cx + a * p[0], cy + a * p[1], linePaint)
            }
        }
    }
}

/** 原版 BlurMaskFilter ∞ 拖尾局部对照组，参数与 GenerationIndicatorStyles 保持逐字一致。 */
private fun drawOriginalInfinityLine(
    native: Canvas,
    cx: Float,
    cy: Float,
    a: Float,
    k: Float,
    palette: MotionPalette,
    head: Float,
    length: Float,
    glowPaint: Paint,
    linePaint: Paint,
) {
    glowPaint.maskFilter = BlurMaskFilter(2.5f * k, BlurMaskFilter.Blur.NORMAL)
    val p = FloatArray(2)
    val q = FloatArray(2)
    for (pass in 0 until 2) {
        val paint = if (pass == 0) glowPaint else linePaint
        paint.blendMode = if (pass == 0 && palette.dark) BlendMode.PLUS else BlendMode.SRC_OVER
        val drawTail = { tailHead: Float, color: Color ->
            val segments = 34
            for (j in segments - 1 downTo 0) {
                val s0 = tailHead - j.toFloat() / segments * length
                val s1 = tailHead - (j + 1f) / segments * length
                val f = 1f - j.toFloat() / segments
                lemniscateAt(s0, p)
                lemniscateAt(s1, q)
                paint.color = (if (j < 2) palette.hot else color).copy(alpha = clamp01(f * f)).toArgb()
                paint.strokeWidth = (0.6f + 1.6f * f) * k * (0.55f + 0.45f * abs(cos(s0)))
                native.drawLine(cx + a * q[0], cy + a * q[1], cx + a * p[0], cy + a * p[1], paint)
            }
        }
        drawTail(head, palette.a)
        drawTail(head + PI.toFloat(), palette.b)
    }
}

private fun fillScopeFrame(
    cols: Int,
    rows: Int,
    sx: Float,
    sy: Float,
    pitch: Float,
    sz: Float,
    gz: Float,
    t: Float,
    palette: MotionPalette,
    addOff: (Float, Float, Float) -> Unit,
    addDot: (Int, Float, Float, Float, Float, Float) -> Unit,
) {
    for (c in 0 until cols) for (r in 0 until rows) {
        val cx = sx + c * pitch + pitch / 2f
        val cy = sy + r * pitch + pitch / 2f
        val phaseShift = sin(c * 0.45f - t * 3.8f)
        val waveY = 3f + phaseShift * 2.1f
        val dist = abs(r - waveY)
        val bar = c == (cols - 5) && r in 1..5
        if (bar) {
            addDot(1, 0.92f, cx, cy, sz, gz)
        } else if (dist < 0.55f) {
            addDot(0, 1f, cx, cy, sz, gz)
        } else if (dist < 1.35f) {
            addDot(0, 0.46f, cx, cy, sz, gz)
        } else {
            addOff(cx, cy, sz)
        }
    }
}

private fun fillStripFrame(
    cols: Int,
    x0: Float,
    yTop: Float,
    rowGap: Float,
    pitch: Float,
    sz: Float,
    gz: Float,
    t: Float,
    addOff: (Float, Float, Float) -> Unit,
    addDot: (Int, Float, Float, Float, Float, Float) -> Unit,
) {
    val headA = ((t * 14f) % (cols + 8)) - 4f
    val headB = (((t + 0.55f) * 10f) % (cols + 8)) - 4f
    for (r in 0 until 2) {
        val y = yTop + r * rowGap
        for (c in 0 until cols) {
            val x = x0 + c * pitch + pitch / 2f
            val inA = r == 0 && c >= headA - 5 && c <= headA
            val inB = r == 1 && c >= headB - 4 && c <= headB
            val amber = r == 0 && c in (cols - 9)..(cols - 5)
            when {
                c == 0 -> addDot(0, 0.85f, x, y, sz, gz)
                amber -> addDot(1, 1f, x, y, sz, gz)
                inA || inB -> addDot(0, 1f, x, y, sz, gz)
                else -> addOff(x, y, sz)
            }
        }
    }
}

private fun fillMatrixFrame(
    cx: Float,
    cy: Float,
    k: Float,
    t: Float,
    addOff: (Float, Float, Float, Float) -> Unit,
    addRect: (Int, Float, Float, Float, Float, Float, Float) -> Unit,
) {
    val cols = 10
    val rows = 3
    val pitch = 4.4f * k
    val sz = 2.8f * k
    val glowPad = 0.6f * k
    val x0 = cx - (cols - 1) * pitch / 2f
    val y0 = cy - (rows - 1) * pitch / 2f
    val drift = t * 1.4f
    val sweep = floor(t * 9f).toInt().mod(cols + 4)
    for (c in 0 until cols) for (r in 0 until rows) {
        val x = x0 + c * pitch - sz / 2f
        val y = y0 + r * pitch - sz / 2f
        val field = valueNoise2(c * 0.55f + drift, r * 1.1f - t * 1.2f)
        val flicker = motionHash(c * 7.1f + r * 3.3f, floor(t * 16f))
        val lit = flicker < field * 0.85f + (if (sweep == c) 0.45f else 0f) - 0.1f
        val amber = motionHash(c.toFloat(), r + 9f) < 0.16f
        if (lit) {
            addRect(if (amber) 1 else 0, 1f, x, y, sz, sz, glowPad)
        } else {
            addOff(x, y, sz, sz)
        }
    }
}

@Composable
private fun CompareHeaderRow(leftLabel: String, rightLabel: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
        Text(leftLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(rightLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CompareSectionCard(title: String, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        Column(Modifier.padding(InfinitySpacing.Medium), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}

@Composable
private fun LosslessCompareScreen(mode: InfinityThemeMode) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        val palette = rememberMotionPalette()
        val origBatch = remember { LedBatch(levels = 5, glowFrom = 2) }
        val losslessBatch = remember { LosslessLedBatch(levels = 5, glowFrom = 2) }
        val origMatrixBatch = remember { LedBatch(levels = 2, glowFrom = 1) }
        val losslessMatrixBatch = remember { LosslessLedBatch(levels = 2, glowFrom = 1) }
        val outlinePaints = remember { ComposerOutlinePaints() }
        val haloCache = remember { LosslessHaloRingCache() }
        val lineRenderer = remember { LosslessInfinityLineRenderer() }
        val origGlowPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND } }
        val origLinePaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND } }
        val cardFill = MaterialTheme.colorScheme.surfaceContainerHigh
        val t = 1.25f

        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(InfinitySpacing.Large),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (palette.dark) "零 BlurMaskFilter · 1:1 无损对照（深色）" else "零 BlurMaskFilter · 1:1 无损对照（浅色）",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    InfinityMetaLine(
                        segments = listOf("左/上 原版 BlurMaskFilter", "右/下 无损零泄漏", "同帧同几何"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 1. RunDockScope 点阵波形屏 1:1 对照
                CompareSectionCard("1. 运行坞点阵波形屏（RunDockScope · Stream）") {
                    CompareHeaderRow("原版 BlurMaskFilter", "无损 2D 高斯精灵")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
                        Canvas(Modifier.weight(1f).height(34.dp)) {
                            val k = density
                            val pitch = ScopeScreenSpec.PitchDp * k
                            val sz = ScopeScreenSpec.DotDp * k
                            val gz = ScopeScreenSpec.GlowDp * k
                            val rows = ScopeScreenSpec.Rows
                            val cols = max(12, ((size.width - 4 * k) / pitch).toInt())
                            val sx = (size.width - cols * pitch) / 2f
                            val sy = (size.height - rows * pitch) / 2f
                            origBatch.reset()
                            fillScopeFrame(cols, rows, sx, sy, pitch, sz, gz, t, palette, origBatch::offDot) { c, lvl, x, y, s, g ->
                                origBatch.dot(c, lvl, x, y, s, g)
                            }
                            drawIntoCanvas {
                                origBatch.draw(
                                    it.nativeCanvas, palette.leds, palette.dark, blur = 3f * k,
                                    offColor = palette.a.copy(alpha = if (palette.dark) 0.1f else 0.14f),
                                )
                            }
                        }
                        Canvas(Modifier.weight(1f).height(34.dp)) {
                            val k = density
                            val pitch = ScopeScreenSpec.PitchDp * k
                            val sz = ScopeScreenSpec.DotDp * k
                            val gz = ScopeScreenSpec.GlowDp * k
                            val rows = ScopeScreenSpec.Rows
                            val cols = max(12, ((size.width - 4 * k) / pitch).toInt())
                            val sx = (size.width - cols * pitch) / 2f
                            val sy = (size.height - rows * pitch) / 2f
                            losslessBatch.reset()
                            fillScopeFrame(cols, rows, sx, sy, pitch, sz, gz, t, palette, losslessBatch::offDot) { c, lvl, x, y, s, g ->
                                losslessBatch.dot(c, lvl, x, y, s, g)
                            }
                            drawIntoCanvas {
                                losslessBatch.draw(
                                    it.nativeCanvas, palette.leds, palette.dark, blur = 3f * k,
                                    offColor = palette.a.copy(alpha = if (palette.dark) 0.1f else 0.14f),
                                )
                            }
                        }
                    }
                }

                // 2. BOTTOM_STRIP 底沿两排数据流 1:1 对照
                CompareSectionCard("2. 浮岛底沿两排数据流灯带（BOTTOM_STRIP）") {
                    Text("上：原版 BlurMaskFilter", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                        val k = density
                        val pitch = 3.6f * k
                        val sz = 1.9f * k
                        val gz = 3.8f * k
                        val cols = max(12, ((size.width - 8 * k) / pitch).toInt())
                        val x0 = (size.width - cols * pitch) / 2f
                        origBatch.reset()
                        fillStripFrame(cols, x0, 4f * k, 4.2f * k, pitch, sz, gz, t, origBatch::offDot) { c, lvl, x, y, s, g ->
                            origBatch.dot(c, lvl, x, y, s, g)
                        }
                        drawIntoCanvas {
                            origBatch.draw(
                                it.nativeCanvas, palette.leds, palette.dark, blur = 3f * k,
                                offColor = palette.a.copy(alpha = if (palette.dark) 0.1f else 0.16f),
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Text("下：无损 2D 高斯精灵（零泄漏）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                        val k = density
                        val pitch = 3.6f * k
                        val sz = 1.9f * k
                        val gz = 3.8f * k
                        val cols = max(12, ((size.width - 8 * k) / pitch).toInt())
                        val x0 = (size.width - cols * pitch) / 2f
                        losslessBatch.reset()
                        fillStripFrame(cols, x0, 4f * k, 4.2f * k, pitch, sz, gz, t, losslessBatch::offDot) { c, lvl, x, y, s, g ->
                            losslessBatch.dot(c, lvl, x, y, s, g)
                        }
                        drawIntoCanvas {
                            losslessBatch.draw(
                                it.nativeCanvas, palette.leds, palette.dark, blur = 3f * k,
                                offColor = palette.a.copy(alpha = if (palette.dark) 0.1f else 0.16f),
                            )
                        }
                    }
                }

                // 3. 浮岛呼吸外圈柔光（BREATHE drawHaloRing）1:1 对照
                CompareSectionCard("3. 浮岛呼吸外圈柔光（BREATHE drawHaloRing）") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Box(Modifier.fillMaxWidth()) {
                            Canvas(Modifier.matchParentSize()) {
                                val cornerPx = 22.dp.toPx()
                                drawHaloRing(0.62f, 16f, 11f, cornerPx, palette.a, palette.b, outlinePaints)
                                drawRoundRect(cardFill, cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerPx))
                            }
                            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("原版 BlurMaskFilter 外圈光晕", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                                Text("高斯 Blur.NORMAL（11dp 宽 / 16dp 模糊半径）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Box(Modifier.fillMaxWidth()) {
                            Canvas(Modifier.matchParentSize()) {
                                val cornerPx = 22.dp.toPx()
                                drawIntoCanvas {
                                    haloCache.draw(
                                        it.nativeCanvas, size.width, size.height,
                                        alpha = 0.62f, blurPx = 16f * density, strokeWidthPx = 11f * density,
                                        cornerPx = cornerPx, colorA = palette.a, colorB = palette.b,
                                    )
                                }
                                drawRoundRect(cardFill, cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerPx))
                            }
                            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("无损 SDF + erf 柔光（API 33+ 为 GPU AGSL）", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Text("与原版同源的高斯环带积分与对角渐变，零 Scratch 泄漏", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                // 4. MATRIX 10x3 与 INFINITY_LINE 双光头 1:1 对照
                CompareSectionCard("4. MATRIX 点阵与 ∞ 双光头指示器") {
                    CompareHeaderRow("原版 BlurMaskFilter", "无损零泄漏版")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            Canvas(Modifier.size(width = 56.dp, height = 24.dp)) {
                                val k = density
                                origMatrixBatch.reset()
                                fillMatrixFrame(size.width / 2f, size.height / 2f, k, t, origMatrixBatch::offRect) { c, lvl, x, y, w, h, pad ->
                                    origMatrixBatch.rect(c, lvl, x, y, w, h, pad)
                                }
                                drawIntoCanvas {
                                    origMatrixBatch.draw(it.nativeCanvas, palette.leds, palette.dark, blur = 3f * k, offColor = palette.off, whitenFrom = 1)
                                }
                            }
                            Canvas(Modifier.size(width = 58.dp, height = 26.dp)) {
                                val k = density
                                drawIntoCanvas {
                                    drawOriginalInfinityLine(it.nativeCanvas, size.width / 2f, size.height / 2f, 21f * k, k, palette, 0.9f, 2.2f, origGlowPaint, origLinePaint)
                                }
                            }
                        }
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            Canvas(Modifier.size(width = 56.dp, height = 24.dp)) {
                                val k = density
                                losslessMatrixBatch.reset()
                                fillMatrixFrame(size.width / 2f, size.height / 2f, k, t, losslessMatrixBatch::offRect) { c, lvl, x, y, w, h, pad ->
                                    losslessMatrixBatch.rect(c, lvl, x, y, w, h, pad)
                                }
                                drawIntoCanvas {
                                    losslessMatrixBatch.draw(it.nativeCanvas, palette.leds, palette.dark, blur = 3f * k, offColor = palette.off, whitenFrom = 1)
                                }
                            }
                            Canvas(Modifier.size(width = 58.dp, height = 26.dp)) {
                                val k = density
                                drawIntoCanvas {
                                    lineRenderer.drawTails(it.nativeCanvas, size.width / 2f, size.height / 2f, 21f * k, k, palette) { draw ->
                                        draw(0.9f, palette.a, 2.2f, 1f)
                                        draw(0.9f + PI.toFloat(), palette.b, 2.2f, 1f)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LosslessFilmstripScreen() {
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY)) {
        val palette = rememberMotionPalette()
        val origBatch = remember { LedBatch(levels = 5, glowFrom = 2) }
        val losslessBatch = remember { LosslessLedBatch(levels = 5, glowFrom = 2) }
        val origMatrixBatch = remember { LedBatch(levels = 2, glowFrom = 1) }
        val losslessMatrixBatch = remember { LosslessLedBatch(levels = 2, glowFrom = 1) }
        val origGlowPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND } }
        val origLinePaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND } }
        val lineRenderer = remember { LosslessInfinityLineRenderer() }
        val frames = listOf(0.00f to "t = 0.00s", 0.35f to "t = 0.35s", 0.70f to "t = 0.70s", 1.05f to "t = 1.05s")

        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(InfinitySpacing.Large),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("关键帧时间序列对照（t = 0.00s -> 1.05s）", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    InfinityMetaLine(
                        segments = listOf("静态多帧采样", "左：原版 BlurMaskFilter", "右：无损零泄漏"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CompareHeaderRow("原版（Scope + Matrix + ∞）", "无损版（Scope + Matrix + ∞）")
                val cardShape = RoundedCornerShape(16.dp)
                Surface(
                    shape = cardShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, cardShape),
                ) {
                    Column(Modifier.padding(InfinitySpacing.Medium), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                        frames.forEachIndexed { idx, (tVal, label) ->
                            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Canvas(Modifier.weight(1f).height(24.dp)) {
                                        val k = density
                                        val pitch = ScopeScreenSpec.PitchDp * k
                                        val sz = ScopeScreenSpec.DotDp * k
                                        val gz = ScopeScreenSpec.GlowDp * k
                                        val rows = ScopeScreenSpec.Rows
                                        val cols = max(10, ((size.width - 2 * k) / pitch).toInt())
                                        origBatch.reset()
                                        fillScopeFrame(cols, rows, (size.width - cols * pitch) / 2f, (size.height - rows * pitch) / 2f, pitch, sz, gz, tVal, palette, origBatch::offDot) { c, l, x, y, s, g ->
                                            origBatch.dot(c, l, x, y, s, g)
                                        }
                                        drawIntoCanvas { origBatch.draw(it.nativeCanvas, palette.leds, palette.dark, 3f * k, palette.a.copy(alpha = 0.1f)) }
                                    }
                                    Canvas(Modifier.size(width = 44.dp, height = 16.dp)) {
                                        val k = density
                                        origMatrixBatch.reset()
                                        fillMatrixFrame(size.width / 2f, size.height / 2f, k, tVal, origMatrixBatch::offRect) { c, lvl, x, y, w, h, pad ->
                                            origMatrixBatch.rect(c, lvl, x, y, w, h, pad)
                                        }
                                        drawIntoCanvas { origMatrixBatch.draw(it.nativeCanvas, palette.leds, palette.dark, 3f * k, palette.off, whitenFrom = 1) }
                                    }
                                    Canvas(Modifier.size(width = 44.dp, height = 18.dp)) {
                                        val k = density
                                        drawIntoCanvas {
                                            drawOriginalInfinityLine(it.nativeCanvas, size.width / 2f, size.height / 2f, 17f * k, k, palette, tVal * 2.8f, 2.1f, origGlowPaint, origLinePaint)
                                        }
                                    }
                                }
                                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Canvas(Modifier.weight(1f).height(24.dp)) {
                                        val k = density
                                        val pitch = ScopeScreenSpec.PitchDp * k
                                        val sz = ScopeScreenSpec.DotDp * k
                                        val gz = ScopeScreenSpec.GlowDp * k
                                        val rows = ScopeScreenSpec.Rows
                                        val cols = max(10, ((size.width - 2 * k) / pitch).toInt())
                                        losslessBatch.reset()
                                        fillScopeFrame(cols, rows, (size.width - cols * pitch) / 2f, (size.height - rows * pitch) / 2f, pitch, sz, gz, tVal, palette, losslessBatch::offDot) { c, l, x, y, s, g ->
                                            losslessBatch.dot(c, l, x, y, s, g)
                                        }
                                        drawIntoCanvas { losslessBatch.draw(it.nativeCanvas, palette.leds, palette.dark, 3f * k, palette.a.copy(alpha = 0.1f)) }
                                    }
                                    Canvas(Modifier.size(width = 44.dp, height = 16.dp)) {
                                        val k = density
                                        losslessMatrixBatch.reset()
                                        fillMatrixFrame(size.width / 2f, size.height / 2f, k, tVal, losslessMatrixBatch::offRect) { c, lvl, x, y, w, h, pad ->
                                            losslessMatrixBatch.rect(c, lvl, x, y, w, h, pad)
                                        }
                                        drawIntoCanvas { losslessMatrixBatch.draw(it.nativeCanvas, palette.leds, palette.dark, 3f * k, palette.off, whitenFrom = 1) }
                                    }
                                    Canvas(Modifier.size(width = 44.dp, height = 18.dp)) {
                                        val k = density
                                        drawIntoCanvas {
                                            lineRenderer.drawTails(it.nativeCanvas, size.width / 2f, size.height / 2f, 17f * k, k, palette) { draw ->
                                                draw(tVal * 2.8f, palette.a, 2.1f, 1f)
                                                draw(tVal * 2.8f + PI.toFloat(), palette.b, 2.1f, 1f)
                                            }
                                        }
                                    }
                                }
                            }
                            if (idx < frames.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "1. 零Blur无损1:1对照 深色", widthDp = 411, heightDp = 920, locale = "zh-rCN")
@Composable
fun ledGlowLosslessCompareDarkPreview() = LosslessCompareScreen(InfinityThemeMode.DARK)

@Preview(name = "2. 零Blur无损1:1对照 浅色", widthDp = 411, heightDp = 920, locale = "zh-rCN")
@Composable
fun ledGlowLosslessCompareLightPreview() = LosslessCompareScreen(InfinityThemeMode.LIGHT)

@Preview(name = "3. 关键帧序列对照 深色", widthDp = 411, heightDp = 560, locale = "zh-rCN")
@Composable
fun ledGlowMotionFilmstripDarkPreview() = LosslessFilmstripScreen()
