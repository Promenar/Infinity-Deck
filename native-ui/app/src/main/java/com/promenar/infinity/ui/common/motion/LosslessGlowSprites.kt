package com.promenar.infinity.ui.common.motion

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.os.Build
import android.util.LruCache
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * 零掩码模糊滤镜高斯光晕内核与有上限精灵缓存：
 * - 数学与光学上 1:1 对标 Skia 高斯掩码模糊（σ = 0.57735 * radius + 0.5）；
 * - 逐帧仅使用不可变预卷积 ARGB_8888 贴图或全 GPU AGSL RuntimeShader 绘制，消除动态 Path 模糊引起的
 *   Skia Vulkan Scratch Surface 持续泄漏。
 */

private const val SQRT_2 = 1.4142135f
private const val BOX_SPRITE_CACHE_LIMIT = 96
private const val CIRCLE_SPRITE_CACHE_LIMIT = 48
private const val DISK_SPRITE_CACHE_LIMIT = 32
private const val HALO_CACHE_LIMIT = 8

/** Skia `SkBlurMask::ConvertRadiusToSigma` 精确标准差换算。 */
internal fun skiaSigma(blurRadius: Float): Float =
    if (blurRadius > 0f) 0.57735f * blurRadius + 0.5f else 0f

/** Abramowitz & Stegun 7.1.26 高斯误差函数近似（最大误差 < 1.5e-7）。 */
internal fun erfApprox(x: Float): Float {
    val sign = if (x < 0f) -1f else 1f
    val ax = abs(x)
    val t = 1f / (1f + 0.3275911f * ax)
    val poly = t * (0.254829592f + t * (-0.284496736f + t * (1.421413741f + t * (-1.453152027f + t * 1.061405429f))))
    val y = 1f - poly * exp(-ax * ax)
    return sign * y
}

/** 圆角矩形外距离场（边界内负、外正，边界为 0）。 */
internal fun roundedBoxSdf(px: Float, py: Float, halfW: Float, halfH: Float, r: Float): Float {
    val safeR = r.coerceIn(0f, min(halfW, halfH))
    val qx = abs(px) - halfW + safeR
    val qy = abs(py) - halfH + safeR
    val outX = max(qx, 0f)
    val outY = max(qy, 0f)
    return sqrt(outX * outX + outY * outY) + min(max(qx, qy), 0f) - safeR
}

internal class GaussianSprite(val bitmap: Bitmap, val halfW: Float, val halfH: Float)

internal object GaussianBoxSpriteCache {
    private data class Key(val colorOpaque: Int, val gwQ: Int, val ghQ: Int, val blurQ: Int)

    private val lock = Any()
    private val cache = object : LruCache<Key, GaussianSprite>(BOX_SPRITE_CACHE_LIMIT) {}

    fun get(colorOpaque: Int, gw: Float, gh: Float, blur: Float): GaussianSprite {
        val key = Key(
            colorOpaque = colorOpaque or (0xFF shl 24),
            gwQ = (gw * 2f).roundToInt().coerceAtLeast(1),
            ghQ = (gh * 2f).roundToInt().coerceAtLeast(1),
            blurQ = (blur * 2f).roundToInt().coerceAtLeast(1),
        )
        synchronized(lock) {
            cache.get(key)?.let { return it }
        }
        val built = buildBoxSprite(key.colorOpaque, key.gwQ / 2f, key.ghQ / 2f, key.blurQ / 2f)
        synchronized(lock) {
            return cache.get(key) ?: built.also { cache.put(key, it) }
        }
    }

    private fun buildBoxSprite(colorOpaque: Int, gw: Float, gh: Float, blur: Float): GaussianSprite {
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
                pixels[offset + px] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, bmpW, 0, 0, bmpW, bmpH)
        }
        return GaussianSprite(bmp, bmpW / 2f, bmpH / 2f)
    }
}

internal object GaussianCircleSpriteCache {
    private data class Key(val colorOpaque: Int, val blurQ: Int)

    private val lock = Any()
    private val cache = object : LruCache<Key, GaussianSprite>(CIRCLE_SPRITE_CACHE_LIMIT) {}

    fun get(colorOpaque: Int, blur: Float): GaussianSprite {
        val key = Key(colorOpaque or (0xFF shl 24), (blur * 4f).roundToInt().coerceAtLeast(1))
        synchronized(lock) {
            cache.get(key)?.let { return it }
        }
        val built = buildCircleSprite(key.colorOpaque, key.blurQ / 4f)
        synchronized(lock) {
            return cache.get(key) ?: built.also { cache.put(key, it) }
        }
    }

    private fun buildCircleSprite(colorOpaque: Int, blur: Float): GaussianSprite {
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
        return GaussianSprite(bmp, size / 2f, size / 2f)
    }
}

/** 实心圆盘高斯卷积精灵贴图缓存：用精确柱面高斯基元为桌宠云朵光晕等大半径圆盘生成无模糊滤镜贴图。 */
internal object GaussianDiskSpriteCache {
    private data class Key(val colorOpaque: Int, val radiusQ: Int, val blurQ: Int)

    private val lock = Any()
    private val cache = object : LruCache<Key, GaussianSprite>(DISK_SPRITE_CACHE_LIMIT) {}

    fun get(colorOpaque: Int, diskRadius: Float, blur: Float): GaussianSprite {
        val key = Key(
            colorOpaque = colorOpaque or (0xFF shl 24),
            radiusQ = diskRadius.roundToInt().coerceAtLeast(1),
            blurQ = blur.roundToInt().coerceAtLeast(1),
        )
        synchronized(lock) {
            cache.get(key)?.let { return it }
        }
        val built = buildDiskSprite(key.colorOpaque, key.radiusQ.toFloat(), key.blurQ.toFloat())
        synchronized(lock) {
            return cache.get(key) ?: built.also { cache.put(key, it) }
        }
    }

    private fun buildDiskSprite(colorOpaque: Int, diskRadius: Float, blur: Float): GaussianSprite {
        val sigma = skiaSigma(blur).coerceAtLeast(0.5f)
        val outerR = ceil(diskRadius + 3f * sigma).toInt().coerceAtLeast(2)
        val size = outerR * 2 + 1
        // 用一维径向采样查表计算圆盘与 2D 可分离高斯的精确卷积，仅需数十步积分即可线性插值全图。
        val lutSize = outerR + 4
        val sliceCount = 48
        val dyStep = (2f * diskRadius) / sliceCount
        val invSqrt2PiSigma = 1f / (sqrt(2f * PI.toFloat()) * sigma)
        val invS2 = 1f / (SQRT_2 * sigma)
        val inv2S2 = 1f / (2f * sigma * sigma)
        val lut = FloatArray(lutSize)
        for (ri in 0 until lutSize) {
            val dist = ri.toFloat()
            var acc = 0f
            for (s in 0 until sliceCount) {
                val y = -diskRadius + (s + 0.5f) * dyStep
                val chord = sqrt(max(0f, diskRadius * diskRadius - y * y))
                val gy = exp(-(y * y) * inv2S2) * invSqrt2PiSigma
                val gx = 0.5f * (erfApprox((dist + chord) * invS2) - erfApprox((dist - chord) * invS2))
                acc += gx * gy * dyStep
            }
            lut[ri] = acc.coerceIn(0f, 1f)
        }

        val r = (colorOpaque ushr 16) and 0xFF
        val g = (colorOpaque ushr 8) and 0xFF
        val b = colorOpaque and 0xFF
        val pixels = IntArray(size * size)
        for (py in 0 until size) {
            val dy = (py - outerR).toFloat()
            val dy2 = dy * dy
            val off = py * size
            for (px in 0 until size) {
                val dx = (px - outerR).toFloat()
                val dist = sqrt(dx * dx + dy2)
                val idx = floor(dist).toInt()
                val cov = if (idx >= lutSize - 1) 0f else {
                    val f = dist - idx
                    lut[idx] + (lut[idx + 1] - lut[idx]) * f
                }
                val a = (cov.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
                pixels[off + px] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
        return GaussianSprite(bmp, size / 2f, size / 2f)
    }
}

/** 供 API 31–32 与静态预览兜底的圆角外圈柔光缓存（SDF + erf 解析积分，对标原版 drawHaloRing）。 */
internal object RoundedRingHaloCache {
    private data class Key(
        val wPx: Int,
        val hPx: Int,
        val blurPx: Int,
        val strokePxQ: Int,
        val cornerPxQ: Int,
        val colorA: Int,
        val colorB: Int,
    )

    private class Entry(val bitmap: Bitmap, val padPx: Float)

    private val lock = Any()
    private val cache = object : LruCache<Key, Entry>(HALO_CACHE_LIMIT) {}
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
        val key = Key(
            wPx = width.roundToInt().coerceAtLeast(1),
            hPx = height.roundToInt().coerceAtLeast(1),
            blurPx = blurKey,
            strokePxQ = (strokeWidthPx * 2f).roundToInt().coerceAtLeast(1),
            cornerPxQ = (cornerPx * 2f).roundToInt().coerceAtLeast(1),
            colorA = colorA.toArgb() or (0xFF shl 24),
            colorB = colorB.toArgb() or (0xFF shl 24),
        )
        val entry = synchronized(lock) { cache.get(key) } ?: buildEntry(
            w = width,
            h = height,
            blurPx = blurKey.toFloat(),
            strokeWidthPx = strokeWidthPx,
            cornerPx = cornerPx,
            colorA = key.colorA,
            colorB = key.colorB,
        ).also { built ->
            synchronized(lock) { cache.put(key, built) }
        }
        val pad = entry.padPx
        dst.set(-pad, -pad, width + pad, height + pad)
        synchronized(paint) {
            paint.alpha = (clamp01(alpha) * 255f).roundToInt().coerceIn(0, 255)
            canvas.drawBitmap(entry.bitmap, null, dst, paint)
        }
    }

    private fun buildEntry(
        w: Float,
        h: Float,
        blurPx: Float,
        strokeWidthPx: Float,
        cornerPx: Float,
        colorA: Int,
        colorB: Int,
    ): Entry {
        val sigma = skiaSigma(blurPx).coerceAtLeast(0.5f)
        val halfStroke = strokeWidthPx / 2f
        val pad = ceil(3.2f * sigma + halfStroke).coerceAtLeast(4f)
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
        return Entry(bmp, pad)
    }
}

/** API 33+ 全 GPU 解析高斯环带光圈着色器（零离屏 Scratch 表面，视觉 1:1 等价于原掩码模糊）。 */
internal const val RoundedRingHaloAgsl = """
uniform float2 boxSize;
uniform float radius;
uniform float halfStroke;
uniform float invS2;
uniform float ringAlpha;
layout(color) uniform half4 colorA;
layout(color) uniform half4 colorB;

float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }

float erfApprox(float x) {
    float s = x < 0.0 ? -1.0 : 1.0;
    float ax = abs(x);
    float t = 1.0 / (1.0 + 0.3275911 * ax);
    float poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))));
    return s * (1.0 - poly * exp(-ax * ax));
}

float roundedBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 coord) {
    float2 halfSize = boxSize * 0.5;
    float d = roundedBox(coord - halfSize, halfSize, min(radius, min(halfSize.x, halfSize.y)));
    float band = 0.5 * (erfApprox((d + halfStroke) * invS2) - erfApprox((d - halfStroke) * invS2));
    float tGrad = clamp(dot(coord, boxSize) / max(1.0, dot(boxSize, boxSize)), 0.0, 1.0);
    half3 col = mix(colorA.rgb, colorB.rgb, half(tGrad));
    float a = clamp(band * ringAlpha, 0.0, 1.0);
    a = clamp(a + (hash(coord) - 0.5) / 255.0 * step(0.002, a), 0.0, 1.0);
    return half4(col * half(a), half(a));
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun createRoundedRingHaloShader(): RuntimeShader = RuntimeShader(RoundedRingHaloAgsl)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun updateRoundedRingHaloShader(
    shader: RuntimeShader,
    width: Float,
    height: Float,
    alpha: Float,
    blurPx: Float,
    strokeWidthPx: Float,
    cornerPx: Float,
    colorA: Color,
    colorB: Color,
) {
    val sigma = skiaSigma(blurPx).coerceAtLeast(0.5f)
    shader.setFloatUniform("boxSize", width, height)
    shader.setFloatUniform("radius", cornerPx)
    shader.setFloatUniform("halfStroke", strokeWidthPx * 0.5f)
    shader.setFloatUniform("invS2", 1f / (SQRT_2 * sigma))
    shader.setFloatUniform("ringAlpha", clamp01(alpha))
    shader.setColorUniform("colorA", colorA.toArgb())
    shader.setColorUniform("colorB", colorB.toArgb())
}

/**
 * 以与原掩码高斯模糊线段卷积等价的面积权重 `dA / (2πσ²)` 沿直线段绘制无模糊柔光。
 * 供 ∞ 轨迹生成与索引指示共用。
 */
internal fun drawGaussianGlowSegment(
    canvas: Canvas,
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    strokeWidthPx: Float,
    color: Color,
    alpha: Float,
    blurPx: Float,
    additive: Boolean,
    paint: Paint,
    dst: RectF,
    subSteps: Int = 3,
) {
    val safeAlpha = clamp01(alpha)
    if (safeAlpha <= 0.002f || strokeWidthPx <= 0f || blurPx <= 0f) return
    val segLen = hypot(x1 - x0, y1 - y0)
    val steps = if (segLen <= 0.5f) 1 else subSteps
    val ds = max(0.1f, segLen / steps)
    val sigma = skiaSigma(blurPx).coerceAtLeast(0.5f)
    val normDenom = (2f * PI.toFloat() * sigma * sigma).coerceAtLeast(1f)
    val sampleAlpha = clamp01(safeAlpha * (strokeWidthPx * ds / normDenom))
    if (sampleAlpha <= 0.002f) return
    val sprite = GaussianCircleSpriteCache.get(color.toArgb(), blurPx)
    paint.blendMode = if (additive) BlendMode.PLUS else BlendMode.SRC_OVER
    paint.alpha = (sampleAlpha * 255f).roundToInt().coerceIn(0, 255)
    val hs = sprite.halfW
    for (step in 0 until steps) {
        val f = (step + 0.5f) / steps
        val x = x0 + (x1 - x0) * f
        val y = y0 + (y1 - y0) * f
        dst.set(x - hs, y - hs, x + hs, y + hs)
        canvas.drawBitmap(sprite.bitmap, null, dst, paint)
    }
}
