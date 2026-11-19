package com.promenar.infinity.ui.common.motion

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.promenar.infinity.ui.chat.ScopeScreenSpec
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityPureWhite
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * 无 BlurMaskFilter 发光动效重构的隔离高保真原型（Wave 1）：
 * 用「静态径向光晕精灵图（ARGB_8888 缓存 <= 4 张 48×48px） + 分层半透明描边」替代逐帧动态 Path + BlurMaskFilter，
 * 消除 Skia Vulkan 下每帧产生大量 ConvolveGaussian / Scratch Surface 导致的 4GB+ 原生内存泄漏与高 GPU/CPU 占用。
 */

private const val SPRITE_SIZE = 48

/**
 * 按颜色缓存在内存中的 48×48 径向渐变光晕贴图（极低内存：每色约 9 KB），供 LED 与灯珠逐帧复用。
 * 全生命周期零 Path BlurMaskFilter、零离屏高斯模糊纹理创建。
 */
internal class RefactoredGlowSpriteCache {
    private val sprites = HashMap<Int, Bitmap>(4)

    fun spriteFor(colorArgb: Int): Bitmap {
        val opaque = colorArgb or (0xFF shl 24)
        return sprites.getOrPut(opaque) { buildGlowSprite(opaque) }
    }

    private fun buildGlowSprite(opaqueRgb: Int): Bitmap {
        val bmp = Bitmap.createBitmap(SPRITE_SIZE, SPRITE_SIZE, Bitmap.Config.ARGB_8888)
        val r = (opaqueRgb ushr 16) and 0xFF
        val g = (opaqueRgb ushr 8) and 0xFF
        val b = opaqueRgb and 0xFF
        val half = (SPRITE_SIZE - 1) / 2f
        val maxDist = half
        val pixels = IntArray(SPRITE_SIZE * SPRITE_SIZE)
        for (y in 0 until SPRITE_SIZE) {
            for (x in 0 until SPRITE_SIZE) {
                val dx = (x - half) / maxDist
                val dy = (y - half) / maxDist
                val dist = sqrt(dx * dx + dy * dy).coerceIn(0f, 1.4f)
                // 余弦平滑径向衰减，在中心保持柔和饱和，边缘完全归零无矩形硬边。
                val falloff = if (dist >= 1f) 0f else {
                    val c = 1f - dist
                    c * c * (3f - 2f * c)
                }
                val a = (falloff * 255f).roundToInt().coerceIn(0, 255)
                pixels[y * SPRITE_SIZE + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bmp.setPixels(pixels, 0, SPRITE_SIZE, 0, 0, SPRITE_SIZE, SPRITE_SIZE)
        return bmp
    }
}

/** 轻量无模糊 LED 绘笔：亮灯芯走纯矩形/Path，光晕走静态精灵贴图（zero BlurMaskFilter）。 */
internal class RefactoredLedDrawer {
    private val spriteCache = RefactoredGlowSpriteCache()
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    fun drawDot(
        native: Canvas,
        baseColor: Color,
        intensity: Float,
        cx: Float,
        cy: Float,
        size: Float,
        glowSize: Float,
        dark: Boolean,
        alpha: Float = 1f,
    ) {
        if (intensity <= 0.01f || alpha <= 0.01f) return
        if (intensity >= 0.45f) {
            val sprite = spriteCache.spriteFor(baseColor.toArgb())
            val halfGlow = glowSize * 0.85f
            dst.set(cx - halfGlow, cy - halfGlow, cx + halfGlow, cy + halfGlow)
            haloPaint.blendMode = if (dark) BlendMode.PLUS else BlendMode.SRC_OVER
            haloPaint.alpha = (clampAlpha(intensity * (if (dark) 0.72f else 0.42f) * alpha) * 255f).roundToInt()
            native.drawBitmap(sprite, null, dst, haloPaint)
        }
        val tint = if (dark && intensity >= 0.7f) lerp(baseColor, InfinityPureWhite, 0.3f) else baseColor
        corePaint.blendMode = BlendMode.SRC_OVER
        corePaint.color = tint.copy(alpha = clampAlpha(intensity * alpha)).toArgb()
        native.drawRect(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f, corePaint)
    }

    fun drawOffDot(native: Canvas, offColor: Color, cx: Float, cy: Float, size: Float, alpha: Float = 1f) {
        if (offColor.alpha <= 0.005f || alpha <= 0.005f) return
        corePaint.blendMode = BlendMode.SRC_OVER
        corePaint.color = offColor.copy(alpha = clampAlpha(offColor.alpha * alpha)).toArgb()
        native.drawRect(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f, corePaint)
    }
}

private fun clampAlpha(v: Float): Float = v.coerceIn(0f, 1f)

/** 双层无模糊柔光 ∞ 拖尾（替代 BlurMaskFilter 逐段高斯模糊）。 */
private fun drawRefactoredInfinityArc(
    native: Canvas,
    cx: Float,
    cy: Float,
    a: Float,
    k: Float,
    palette: MotionPalette,
    head: Float,
    color: Color,
    length: Float,
    alpha: Float,
    paint: Paint,
) {
    val segments = 34
    val p = FloatArray(2)
    val q = FloatArray(2)
    fun pointAt(s: Float, out: FloatArray) {
        val c = cos(s)
        val sn = sin(s)
        val denom = 1f + sn * sn
        out[0] = c / denom
        out[1] = 0.72f * sn * c / denom
    }
    // 两层宽线光晕 + 核心亮线，完全不需要 BlurMaskFilter
    val passes = listOf(
        Triple(3.0f, 0.16f, if (palette.dark) BlendMode.PLUS else BlendMode.SRC_OVER),
        Triple(1.9f, 0.34f, if (palette.dark) BlendMode.PLUS else BlendMode.SRC_OVER),
        Triple(1.0f, 1.00f, BlendMode.SRC_OVER),
    )
    for ((widthMul, alphaMul, mode) in passes) {
        paint.blendMode = mode
        for (j in segments - 1 downTo 0) {
            val s0 = head - j.toFloat() / segments * length
            val s1 = head - (j + 1f) / segments * length
            val f = 1f - j.toFloat() / segments
            pointAt(s0, p)
            pointAt(s1, q)
            val segColor = if (j < 2) palette.hot else color
            paint.color = segColor.copy(alpha = clampAlpha(f * f * alpha * alphaMul)).toArgb()
            paint.strokeWidth = (0.6f + 1.6f * f) * k * (0.55f + 0.45f * abs(cos(s0))) * widthMul
            native.drawLine(cx + a * q[0], cy + a * q[1], cx + a * p[0], cy + a * p[1], paint)
        }
    }
}

/** 分层渐晕圆角外框（替代 ComposerGlowStyles drawHaloRing BlurMaskFilter）。 */
private fun DrawScope.drawRefactoredHaloRing(
    alpha: Float,
    cornerPx: Float,
    colorA: Color,
    colorB: Color,
    dark: Boolean,
    paint: Paint,
) {
    if (alpha <= 0.004f) return
    val layers = if (dark) {
        listOf(
            Triple(12f, 0.06f, 2.5f),
            Triple(8f, 0.11f, 1.5f),
            Triple(4.5f, 0.20f, 0.5f),
            Triple(2.2f, 0.36f, -0.3f),
            Triple(1.2f, 0.68f, -0.8f),
        )
    } else {
        listOf(
            Triple(10f, 0.04f, 2.0f),
            Triple(6.5f, 0.08f, 1.0f),
            Triple(3.8f, 0.14f, 0.2f),
            Triple(2.0f, 0.28f, -0.4f),
            Triple(1.1f, 0.52f, -0.8f),
        )
    }
    val shader = if (colorA == colorB) null else LinearGradient(
        0f, 0f, size.width, size.height, colorA.toArgb(), colorB.toArgb(), Shader.TileMode.CLAMP,
    )
    paint.shader = shader
    paint.style = Paint.Style.STROKE
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        for ((strokeDp, layerAlpha, outsetDp) in layers) {
            val outset = outsetDp * density
            val corner = (cornerPx + outset).coerceAtLeast(1f)
            paint.color = colorA.toArgb()
            paint.alpha = (clampAlpha(alpha * layerAlpha) * 255f).roundToInt()
            paint.strokeWidth = strokeDp * density
            native.drawRoundRect(-outset, -outset, size.width + outset, size.height + outset, corner, corner, paint)
        }
    }
}

@Composable
private fun PrototypeGlowShowcaseContent(english: Boolean) {
    val palette = rememberMotionPalette()
    val drawer = remember { RefactoredLedDrawer() }
    val linePaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
    }
    val haloPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(InfinitySpacing.Large),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (english) "Zero-BlurMaskFilter Glow Prototype" else "零 BlurMaskFilter 发光重构原型",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            InfinityMetaLine(
                segments = if (english) {
                    listOf("Static glow sprite", "Layered halo stroke", "0 Vulkan scratch leak")
                } else {
                    listOf("静态光晕精灵图", "分层柔光描边", "零 Vulkan 临时纹理泄漏")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 1. 运行坞点阵波形屏四阶段对比
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth().border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
        ) {
            Column(Modifier.padding(InfinitySpacing.Large), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
                Text(
                    text = if (english) "RunDock Matrix Scope (Wait / Stream / Tool / Approval)" else "运行坞点阵波形屏（等待 / 流式 / 工具 / 审批）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val labels = if (english) listOf("Wait", "Stream", "Tool", "Approval") else listOf("等待中", "流式中", "执行工具", "待审批")
                labels.forEachIndexed { idx, label ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        Canvas(Modifier.size(width = 132.dp, height = 30.dp)) {
                            val k = density
                            val pitch = ScopeScreenSpec.PitchDp * k
                            val dot = ScopeScreenSpec.DotDp * k
                            val glow = ScopeScreenSpec.GlowDp * k
                            val rows = ScopeScreenSpec.Rows
                            val cols = 38
                            val sx = size.width - cols * pitch
                            val sy = (size.height - rows * pitch) / 2f
                            drawIntoCanvas { canvas ->
                                val native = canvas.nativeCanvas
                                for (c in 0 until cols) for (r in 0 until rows) {
                                    val cx = sx + c * pitch + pitch / 2f
                                    val cy = sy + r * pitch + pitch / 2f
                                    drawer.drawOffDot(native, palette.off, cx, cy, dot)
                                    val lit: Pair<Color, Float>? = when (idx) {
                                        0 -> if (r == 3 && c in 12..18) palette.a to (1f - abs(c - 15) * 0.14f) else null
                                        1 -> {
                                            val waveR = 3 + (sin(c * 0.45f) * 2.3f).roundToInt()
                                            when {
                                                r == waveR -> palette.a to 1f
                                                abs(r - waveR) == 1 -> palette.a to 0.42f
                                                c == 33 && r in 1..5 -> palette.b to 0.9f
                                                else -> null
                                            }
                                        }
                                        2 -> if (abs(r - (c % rows)) <= 0 && c % 4 == 0) palette.b to 1f else if (r == 3 && c % 2 == 0) palette.b to 0.45f else null
                                        else -> if (c in 6..8 && (r in 1..3 || r == 5)) palette.b to 1f else if (r == 3 && c > 12) palette.b to 0.55f else null
                                    }
                                    if (lit != null) {
                                        drawer.drawDot(native, lit.first, lit.second, cx, cy, dot, glow, palette.dark)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. 浮岛呼吸光环 + 底沿数据流灯带
        val composerFill = MaterialTheme.colorScheme.surfaceContainerLow
        Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)) {
            Canvas(Modifier.matchParentSize()) {
                val cornerPx = 24.dp.toPx()
                drawRoundRect(
                    color = composerFill,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerPx),
                )
                drawRefactoredHaloRing(
                    alpha = 0.85f,
                    cornerPx = cornerPx,
                    colorA = palette.a,
                    colorB = palette.b,
                    dark = palette.dark,
                    paint = haloPaint,
                )
                val k = density
                val x0 = 20f * k
                val x1 = size.width - 24f * k
                val pitch = 3.6f * k
                val dot = 1.9f * k
                val glow = 3.8f * k
                val cols = max(8, ((x1 - x0) / pitch).toInt())
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    for (r in 0 until 2) {
                        val cy = size.height - 14f * k + r * 4f * k
                        for (c in 0 until cols) {
                            val cx = x0 + c * pitch
                            drawer.drawOffDot(native, palette.a.copy(alpha = 0.14f), cx, cy, dot)
                            val active = c == 0 || (r == 0 && c in 6..12) || (r == 1 && c in 18..25) || (r == 0 && c in 31..35)
                            if (active) {
                                val amber = r == 0 && c in 31..35
                                drawer.drawDot(native, if (amber) palette.b else palette.a, if (c == 0) 0.85f else 1f, cx, cy, dot, glow, palette.dark)
                            }
                        }
                    }
                }
            }
            Column(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 30.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = if (english) "Composer Halo + Bottom Strip (Zero BlurMaskFilter)" else "浮岛外圈柔光 + 底沿数据流灯带（无模糊滤镜）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (english) "Stepped outer halo ring and cached sprite LEDs stay crisp." else "多层递减外描边与精灵贴图灯珠，深浅主题光晕自然且零泄漏。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 3. ∞ 描线与索引轨迹（分层光晕替代 BlurMaskFilter）
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth().border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
        ) {
            Row(
                Modifier.padding(InfinitySpacing.Large).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
            ) {
                Canvas(Modifier.size(width = 64.dp, height = 28.dp)) {
                    val k = density
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val a = 22f * k
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        drawRefactoredInfinityArc(native, cx, cy, a, k, palette, head = 0.9f, color = palette.a, length = 2.4f, alpha = 1f, paint = linePaint)
                        drawRefactoredInfinityArc(native, cx, cy, a, k, palette, head = 0.9f + PI.toFloat(), color = palette.b, length = 2.4f, alpha = 1f, paint = linePaint)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (english) "Infinity Trace (Generation & Indexing)" else "∞ 轨迹光头（生成指示 & 索引指示）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (english) "3-pass soft core strokes without BlurMaskFilter." else "三层平滑核心描边叠加，取消 34 段逐帧高斯模糊。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Preview(name = "1. 零BlurMaskFilter发光重构 深色中文", widthDp = 411, heightDp = 640, locale = "zh-rCN")
@Composable
fun ledGlowRefactorDarkPreview() {
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            PrototypeGlowShowcaseContent(english = false)
        }
    }
}

@Preview(name = "2. Zero-BlurMaskFilter Glow 浅色英文", widthDp = 360, heightDp = 640, locale = "en")
@Composable
fun ledGlowRefactorLightPreview() {
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            PrototypeGlowShowcaseContent(english = true)
        }
    }
}
