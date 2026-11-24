package com.promenar.infinity.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.motion.GenerationMotion
import com.promenar.infinity.ui.common.motion.GenerationMotionPhase
import com.promenar.infinity.ui.common.motion.MotionPalette
import com.promenar.infinity.ui.common.motion.breath
import com.promenar.infinity.ui.common.motion.clamp01
import com.promenar.infinity.ui.common.motion.evenBlink
import com.promenar.infinity.ui.common.motion.slowBlink
import com.promenar.infinity.ui.common.network.rememberAnimationsEnabled
import com.promenar.infinity.ui.theme.ComposerSurfaceStyle
import com.promenar.infinity.ui.theme.InfinityPureWhite
import com.promenar.infinity.ui.theme.LocalInfinityMotionStyles
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

/** 全局厚玻璃光效浸润的有效物理距离上限；超过此距离衰减严格归零且跳过绘制。 */
internal val AmbientLightMaxReach = 235.dp

/** 低于该有效能量阈值时跳过绘制，避免无效 GPU 消耗。 */
internal const val AmbientMinVisibleEnergy = 0.004f

/** 布局坐标抖动过滤阈值（像素），避免亚像素浮动触发无意义重绘。 */
private const val BoundsEpsilonPx = 1f

/** 厚玻璃面板朝向光源的受光切面边缘。 */
enum class InfiltrationEdge {
    LEFT,
    RIGHT,
    TOP,
    BOTTOM,
}

/** 某一玻璃面板相对主光源（输入浮岛）的空间受光耦合解。 */
@Immutable
internal data class AmbientGlassCoupling(
    val edge: InfiltrationEdge,
    /** 光源沿受光切面的投影锚点（接收面局部像素坐标：LEFT/RIGHT 为 Y，TOP/BOTTOM 为 X）。 */
    val anchorPx: Float,
    /** 物理距离衰减系数 0..1。 */
    val falloff: Float,
)

/** 绘制期复用的零分配采样载体。 */
class AmbientLightSample {
    var color: Color = Color.Transparent
    var energy: Float = 0f

    fun clear() {
        color = Color.Transparent
        energy = 0f
    }
}

/** 主光源发射器契约：只在接收面处于物理距离内且需绘制时按需读取，不额外创建帧循环。 */
@Stable
interface AmbientLightEmitter {
    /** 快速判定光源是否处于发光态；空闲时返回 false，接收面直接跳过后续采样。 */
    fun isActive(): Boolean

    /** 在绘制阶段采样当前时刻的合并阶段色与强度写入 [out]。 */
    fun sampleInto(out: AmbientLightSample)
}

/**
 * 窗口级全局光场协调器：
 * 维护当前活跃的主光源栈（如父会话推入子代理会话时后进先出，退回后自动恢复）与其根坐标边界。
 * 接收端仅在绘制期读取快照状态，且先检查物理距离衰减再决定是否订阅逐帧光强，
 * 因此远距离玻璃表面（如底端浮岛时的顶栏）与空闲态均保持零逐帧重绘、零重组与零模糊重录。
 */
@Stable
class AmbientLightField {
    private val emitterStack = mutableListOf<AmbientLightEmitter>()
    private val boundsByEmitter = mutableMapOf<AmbientLightEmitter, Rect>()

    var activeEmitter: AmbientLightEmitter? by mutableStateOf(null)
        private set

    var sourceBoundsInRoot: Rect by mutableStateOf(Rect.Zero)
        private set

    fun registerEmitter(emitter: AmbientLightEmitter) {
        emitterStack.removeAll { it === emitter }
        emitterStack.add(emitter)
        syncActiveEmitter()
    }

    fun unregisterEmitter(emitter: AmbientLightEmitter) {
        emitterStack.removeAll { it === emitter }
        boundsByEmitter.remove(emitter)
        syncActiveEmitter()
    }

    fun updateSourceBounds(emitter: AmbientLightEmitter, bounds: Rect) {
        val prev = boundsByEmitter[emitter]
        if (prev != null && boundsAlmostEqual(prev, bounds)) return
        boundsByEmitter[emitter] = bounds
        if (activeEmitter === emitter) {
            sourceBoundsInRoot = bounds
        }
    }

    private fun syncActiveEmitter() {
        val top = emitterStack.lastOrNull()
        activeEmitter = top
        sourceBoundsInRoot = top?.let { boundsByEmitter[it] } ?: Rect.Zero
    }
}

/** 窗口级全局厚玻璃光场；由根宿主提供，全站熏黑/冷白毛玻璃表面共享同一坐标系。 */
val LocalAmbientLightField = staticCompositionLocalOf { AmbientLightField() }

private fun boundsAlmostEqual(a: Rect, b: Rect): Boolean =
    abs(a.left - b.left) < BoundsEpsilonPx &&
        abs(a.top - b.top) < BoundsEpsilonPx &&
        abs(a.right - b.right) < BoundsEpsilonPx &&
        abs(a.bottom - b.bottom) < BoundsEpsilonPx

/**
 * 平滑物理距离衰减函数：
 * 贴合时为 1.0，随距离按 1.55 次幂连续衰减，超过 [maxReachPx] 严格归 0。
 */
internal fun ambientDistanceFalloff(distancePx: Float, maxReachPx: Float): Float {
    if (maxReachPx <= 0f || distancePx >= maxReachPx) return 0f
    val u = (1f - (distancePx.coerceAtLeast(0f) / maxReachPx)).coerceIn(0f, 1f)
    return u.pow(1.55f)
}

/**
 * 按接收面 [selfBounds] 与主光源 [sourceBounds] 的根坐标关系，求解受光切面、投影锚点与物理距离衰减。
 * - 相邻不重叠面（左右侧栏、固定检查器、顶栏、收起胶囊条）：取欧氏最近边距离并按 [ambientDistanceFalloff] 衰减；
 * - 叠压悬浮面（扩大抽屉切过浮岛、竖屏 93% 熏黑抽屉覆于浮岛上方）：取切过光源的玻璃切边或下缘承接次表面渗光；
 * - 距离超出 [maxReachPx] 或退化边界时返回 null。
 */
internal fun computeAmbientGlassCoupling(
    selfBounds: Rect,
    sourceBounds: Rect,
    maxReachPx: Float,
): AmbientGlassCoupling? {
    if (
        selfBounds.width <= 0f ||
        selfBounds.height <= 0f ||
        sourceBounds.width <= 0f ||
        sourceBounds.height <= 0f ||
        maxReachPx <= 0f
    ) {
        return null
    }

    val srcCx = sourceBounds.left + sourceBounds.width * 0.52f
    val srcCy = sourceBounds.top + sourceBounds.height * 0.45f
    val padX = (selfBounds.width * 0.12f).coerceAtMost(16f)
    val padY = (selfBounds.height * 0.12f).coerceAtMost(16f)
    val clampedLocalX = (srcCx - selfBounds.left).coerceIn(padX, (selfBounds.width - padX).coerceAtLeast(padX))
    val clampedLocalY = (srcCy - selfBounds.top).coerceIn(padY, (selfBounds.height - padY).coerceAtLeast(padY))

    val gapLeftOfSrc = sourceBounds.left - selfBounds.right
    val gapRightOfSrc = selfBounds.left - sourceBounds.right
    val gapAboveSrc = sourceBounds.top - selfBounds.bottom
    val gapBelowSrc = selfBounds.top - sourceBounds.bottom

    val dx = maxOf(0f, gapLeftOfSrc, gapRightOfSrc)
    val dy = maxOf(0f, gapAboveSrc, gapBelowSrc)

    if (dx > 0f || dy > 0f) {
        val dist = hypot(dx, dy)
        val falloff = ambientDistanceFalloff(dist, maxReachPx)
        if (falloff <= AmbientMinVisibleEnergy) return null
        return if (dx >= dy) {
            val edge = if (gapLeftOfSrc >= gapRightOfSrc) InfiltrationEdge.RIGHT else InfiltrationEdge.LEFT
            AmbientGlassCoupling(edge = edge, anchorPx = clampedLocalY, falloff = falloff)
        } else {
            val edge = if (gapAboveSrc >= gapBelowSrc) InfiltrationEdge.BOTTOM else InfiltrationEdge.TOP
            AmbientGlassCoupling(edge = edge, anchorPx = clampedLocalX, falloff = falloff)
        }
    }

    // 同一区域自身（防止光源误照自己）
    if (boundsAlmostEqual(selfBounds, sourceBounds)) return null

    // 叠压场景：右侧扩大悬浮检视器切过浮岛右半区 -> 左切面受光
    if (selfBounds.left > sourceBounds.left && selfBounds.left < sourceBounds.right && selfBounds.right >= sourceBounds.right) {
        return AmbientGlassCoupling(edge = InfiltrationEdge.LEFT, anchorPx = clampedLocalY, falloff = 0.94f)
    }
    // 左侧悬浮抽屉切过浮岛左半区 -> 右切面受光
    if (selfBounds.right > sourceBounds.left && selfBounds.right < sourceBounds.right && selfBounds.left <= sourceBounds.left) {
        return AmbientGlassCoupling(edge = InfiltrationEdge.RIGHT, anchorPx = clampedLocalY, falloff = 0.94f)
    }
    // 全宽竖屏上滑抽屉覆于浮岛之上 -> 下缘/上缘承接次表面底光
    if (selfBounds.left <= sourceBounds.left && selfBounds.right >= sourceBounds.right) {
        val edge = if (srcCy >= selfBounds.center.y) InfiltrationEdge.BOTTOM else InfiltrationEdge.TOP
        return AmbientGlassCoupling(edge = edge, anchorPx = clampedLocalX, falloff = 0.86f)
    }

    // 其余局部交叠：取距离光源中心最近的接收面切边
    val dLeft = abs(selfBounds.left - srcCx)
    val dRight = abs(selfBounds.right - srcCx)
    val dTop = abs(selfBounds.top - srcCy)
    val dBottom = abs(selfBounds.bottom - srcCy)
    val minDist = minOf(dLeft, dRight, dTop, dBottom)
    return when (minDist) {
        dLeft -> AmbientGlassCoupling(InfiltrationEdge.LEFT, clampedLocalY, 0.85f)
        dRight -> AmbientGlassCoupling(InfiltrationEdge.RIGHT, clampedLocalY, 0.85f)
        dTop -> AmbientGlassCoupling(InfiltrationEdge.TOP, clampedLocalX, 0.85f)
        else -> AmbientGlassCoupling(InfiltrationEdge.BOTTOM, clampedLocalX, 0.85f)
    }
}

/**
 * 阶段差异化光效强度曲线：
 * - 日常 WAIT / STREAM / TOOL 保持克制微光，不干扰阅读与编码；
 * - APPROVAL（暖琥珀）与 ERROR（红光）明显加强，在阅读侧栏代码或文件时提供柔和而清晰的余光提示。
 */
internal fun ambientPhaseLevel(phase: GenerationMotionPhase, timeSeconds: Float, act: Float): Float = when (phase) {
    GenerationMotionPhase.WAIT -> 0.42f * (0.45f + 0.55f * breath(timeSeconds, 3.2f))
    GenerationMotionPhase.STREAM -> 0.56f * (0.55f + 0.45f * clamp01(act))
    GenerationMotionPhase.TOOL -> 0.70f * (0.50f + 0.50f * breath(timeSeconds, 1.3f))
    GenerationMotionPhase.APPROVAL -> 1.18f * (0.42f + 0.58f * slowBlink(timeSeconds))
    GenerationMotionPhase.RETRY -> 0.36f * (0.25f + 0.75f * evenBlink(timeSeconds))
}

/** 与浮岛点阵屏、灯带和内部柔光完全同源的阶段颜色映射。 */
internal fun ambientPhaseColor(phase: GenerationMotionPhase, palette: MotionPalette): Color = when (phase) {
    GenerationMotionPhase.TOOL -> palette.b
    GenerationMotionPhase.APPROVAL -> palette.approval
    else -> palette.a
}

/**
 * 汇总各阶段权重、实时呼吸/闪烁与出错泛红，将合并后的全局光场颜色与有效强度写入 [out]。
 * 纯数值计算，零堆分配，便于在绘制期直接调用与单元测试。
 */
internal fun resolveAmbientPhaseEmission(
    generating: Float,
    error: Float,
    timeSeconds: Float,
    act: Float,
    weightOf: (GenerationMotionPhase) -> Float,
    palette: MotionPalette,
    errorColor: Color,
    out: AmbientLightSample,
) {
    val gen = clamp01(generating)
    val err = clamp01(error)
    if (gen <= AmbientMinVisibleEnergy && err <= AmbientMinVisibleEnergy) {
        out.clear()
        return
    }
    var r = 0f
    var g = 0f
    var b = 0f
    var total = 0f
    if (gen > AmbientMinVisibleEnergy) {
        for (phase in GenerationMotionPhase.entries) {
            val w = weightOf(phase)
            if (w <= AmbientMinVisibleEnergy) continue
            val contrib = gen * w * ambientPhaseLevel(phase, timeSeconds, act)
            if (contrib <= AmbientMinVisibleEnergy) continue
            val c = ambientPhaseColor(phase, palette)
            r += c.red * contrib
            g += c.green * contrib
            b += c.blue * contrib
            total += contrib
        }
    }
    if (err > AmbientMinVisibleEnergy) {
        val errContrib = 1.15f * err
        r += errorColor.red * errContrib
        g += errorColor.green * errContrib
        b += errorColor.blue * errContrib
        total += errContrib
    }
    if (total <= AmbientMinVisibleEnergy) {
        out.clear()
        return
    }
    out.color = Color(
        red = (r / total).coerceIn(0f, 1f),
        green = (g / total).coerceIn(0f, 1f),
        blue = (b / total).coerceIn(0f, 1f),
        alpha = 1f,
    )
    out.energy = total.coerceIn(0f, 1.25f)
}

/**
 * 将当前会话的活跃主光源绑定至全局 [LocalAmbientLightField]；组件退出组合时自动按栈注销并恢复下层光源。
 */
@Composable
internal fun RegisterAmbientLightEmitter(
    emitter: AmbientLightEmitter,
    enabled: Boolean = true,
) {
    val field = LocalAmbientLightField.current
    DisposableEffect(field, emitter, enabled) {
        if (enabled) {
            field.registerEmitter(emitter)
        }
        onDispose {
            field.unregisterEmitter(emitter)
        }
    }
}

/** 将主光源（输入浮岛）的根坐标边界持续同步到全局光场。 */
internal fun Modifier.publishAmbientLightBounds(
    field: AmbientLightField,
    emitter: AmbientLightEmitter,
): Modifier = onGloballyPositioned { coords ->
    if (coords.isAttached) {
        field.updateSourceBounds(emitter, coords.boundsInRoot())
    }
}

/** 进程级缓存的 64×64 磨砂微颗粒重复纹理（约 16KB），仅在玻璃受光区极低透明度叠加，增强蚀刻毛玻璃质感。 */
private object FrostedMicroGrainCache {
    @Volatile
    private var cached: BitmapShader? = null

    fun shader(): BitmapShader? {
        cached?.let { return it }
        return runCatching {
            val size = 64
            val pixels = IntArray(size * size)
            var seed = 0x51A7F3C9
            for (i in pixels.indices) {
                seed = seed * 1664525 + 1013904223
                val v = (seed ushr 24) and 0xFF
                val alpha = if (v > 148) ((v - 148) * 110) / 107 else 0
                pixels[i] = (alpha shl 24) or 0x00FFFFFF
            }
            val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
            BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also { cached = it }
        }.getOrNull()
    }
}

/**
 * 全站厚玻璃光效浸润修饰符：
 * 在玻璃底面之上、内容层之下绘制三层光学效果——
 * 1) 各向异性次表面磨砂漫射；
 * 2) 受光区局部磨砂微颗粒散射；
 * 3) 双层厚玻璃切面全内反射亮边（2dp 切面主高光 + 3.5dp 内偏玻璃厚度反射线）。
 *
 * 仅在毛玻璃材质 + 用户开启「全局光效浸润」+ 系统允许动画（或预览显式注入）下生效；
 * 环境光纯色省电档、系统减弱动态、空闲态或超出 235dp 物理距离时严格跳过绘制且不订阅逐帧状态。
 */
fun Modifier.ambientGlassInfiltration(
    cornerRadius: Dp = 0.dp,
    enabled: Boolean = true,
): Modifier = composed {
    val field = LocalAmbientLightField.current
    val motionStyles = LocalInfinityMotionStyles.current
    val animationsEnabled = rememberAnimationsEnabled()
    val inspection = LocalInspectionMode.current
    val dark = MaterialTheme.infinityDomainColors.isDark

    val effectiveEnabled = enabled &&
        motionStyles.surface == ComposerSurfaceStyle.FROSTED &&
        motionStyles.glassLightInfiltration &&
        (animationsEnabled || inspection)

    val selfBoundsState = remember { mutableStateOf(Rect.Zero) }

    this
        .clipToBounds()
        .onGloballyPositioned { coords ->
            if (!coords.isAttached) return@onGloballyPositioned
            val next = coords.boundsInRoot()
            if (!boundsAlmostEqual(selfBoundsState.value, next)) {
                selfBoundsState.value = next
            }
        }
        .drawWithCache {
            val maxReachPx = AmbientLightMaxReach.toPx()
            val cornerPx = cornerRadius.toPx().coerceAtLeast(0f)
            val clipPath = if (cornerPx > 0f) {
                Path().apply {
                    addRoundRect(
                        RectF(0f, 0f, size.width, size.height),
                        cornerPx,
                        cornerPx,
                        Path.Direction.CW,
                    )
                }
            } else {
                null
            }
            val bevelPrimaryPx = 2.dp.toPx()
            val bevelSecondaryPx = 3.dp.toPx()
            val bevelInnerOffsetPx = 3.5.dp.toPx()
            val edgeSpanPx = 185.dp.toPx()
            val bleedDepthPx = 114.dp.toPx()
            val bleedSpreadPx = 240.dp.toPx()

            val sample = AmbientLightSample()
            val bleedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val baseGrainShader = FrostedMicroGrainCache.shader()
            val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val primaryRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = bevelPrimaryPx
                strokeCap = Paint.Cap.ROUND
            }
            val secondaryRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = bevelSecondaryPx
                strokeCap = Paint.Cap.ROUND
            }
            val matrix = Matrix()

            var cachedColorArgb = 0
            var cachedDark = dark
            var cachedOx = Float.NaN
            var cachedOy = Float.NaN
            var cachedRx = Float.NaN
            var cachedRy = Float.NaN
            var bleedShader: RadialGradient? = null
            var grainMaskedShader: Shader? = null
            var primaryVerticalShader: LinearGradient? = null
            var secondaryVerticalShader: LinearGradient? = null
            var primaryHorizontalShader: LinearGradient? = null
            var secondaryHorizontalShader: LinearGradient? = null

            fun ensureShaders(color: Color) {
                val argb = color.toArgb()
                if (bleedShader != null && cachedColorArgb == argb && cachedDark == dark) return
                cachedColorArgb = argb
                cachedDark = dark
                cachedOx = Float.NaN
                val transparent = color.copy(alpha = 0f).toArgb()
                val mid52 = color.copy(alpha = 0.52f).toArgb()
                val tail14 = color.copy(alpha = 0.14f).toArgb()
                bleedShader = RadialGradient(
                    0f,
                    0f,
                    1f,
                    intArrayOf(argb, mid52, tail14, transparent),
                    floatArrayOf(0f, 0.32f, 0.70f, 1f),
                    Shader.TileMode.CLAMP,
                )
                grainMaskedShader = null
                val coreColor = if (dark) lerp(color, InfinityPureWhite, 0.45f) else InfinityPureWhite
                val coreArgb = coreColor.toArgb()
                val shoulderArgb = color.copy(alpha = 0.48f).toArgb()
                val primaryStops = intArrayOf(transparent, shoulderArgb, coreArgb, shoulderArgb, transparent)
                val primaryPositions = floatArrayOf(0f, 0.28f, 0.5f, 0.72f, 1f)
                val secondaryStops = intArrayOf(transparent, argb, transparent)
                val secondaryPositions = floatArrayOf(0f, 0.5f, 1f)
                primaryVerticalShader = LinearGradient(0f, -1f, 0f, 1f, primaryStops, primaryPositions, Shader.TileMode.CLAMP)
                secondaryVerticalShader = LinearGradient(0f, -1f, 0f, 1f, secondaryStops, secondaryPositions, Shader.TileMode.CLAMP)
                primaryHorizontalShader = LinearGradient(-1f, 0f, 1f, 0f, primaryStops, primaryPositions, Shader.TileMode.CLAMP)
                secondaryHorizontalShader = LinearGradient(-1f, 0f, 1f, 0f, secondaryStops, secondaryPositions, Shader.TileMode.CLAMP)
            }

            onDrawBehind {
                // 1) 功能关闭或环境光省电档：零状态订阅直接返回
                if (!effectiveEnabled) return@onDrawBehind
                // 2) 当前无活跃光源（例如设置页、库页）：直接返回
                val emitter = field.activeEmitter ?: return@onDrawBehind
                // 3) 先做空间距离求解；距离超界时不读取逐帧动画状态，远端顶栏零重绘
                val coupling = computeAmbientGlassCoupling(
                    selfBounds = selfBoundsState.value,
                    sourceBounds = field.sourceBoundsInRoot,
                    maxReachPx = maxReachPx,
                ) ?: return@onDrawBehind
                // 4) 空闲态快速退出
                if (!emitter.isActive()) return@onDrawBehind
                // 5) 采样当前时刻合并光色与强度
                emitter.sampleInto(sample)
                val energy = (sample.energy * coupling.falloff).coerceIn(0f, 1.25f)
                if (energy <= AmbientMinVisibleEnergy || sample.color.alpha <= 0f) return@onDrawBehind

                ensureShaders(sample.color)
                val radial = bleedShader ?: return@onDrawBehind

                drawIntoCanvas { composeCanvas ->
                    val canvas = composeCanvas.nativeCanvas
                    val saveCount = canvas.save()
                    if (clipPath != null) {
                        canvas.clipPath(clipPath)
                    } else {
                        canvas.clipRect(0f, 0f, size.width, size.height)
                    }

                    // Layer 1: 各向异性次表面磨砂漫射（限制上限，严保深浅代码/文本可读性）
                    val bleedAlpha = ((if (dark) 0.20f else 0.14f) * energy).coerceAtMost(if (dark) 0.28f else 0.20f)
                    if (bleedAlpha > AmbientMinVisibleEnergy) {
                        val ox: Float
                        val oy: Float
                        val rx: Float
                        val ry: Float
                        when (coupling.edge) {
                            InfiltrationEdge.LEFT -> {
                                ox = 0f
                                oy = coupling.anchorPx
                                rx = minOf(size.width * 0.68f, bleedDepthPx).coerceAtLeast(1f)
                                ry = minOf(size.height * 0.68f, bleedSpreadPx).coerceAtLeast(1f)
                            }
                            InfiltrationEdge.RIGHT -> {
                                ox = size.width
                                oy = coupling.anchorPx
                                rx = minOf(size.width * 0.82f, bleedDepthPx).coerceAtLeast(1f)
                                ry = minOf(size.height * 0.68f, bleedSpreadPx).coerceAtLeast(1f)
                            }
                            InfiltrationEdge.BOTTOM -> {
                                ox = coupling.anchorPx
                                oy = size.height
                                rx = minOf(size.width * 0.72f, bleedSpreadPx).coerceAtLeast(1f)
                                ry = minOf(size.height * 0.44f, bleedDepthPx).coerceAtLeast(1f)
                            }
                            InfiltrationEdge.TOP -> {
                                ox = coupling.anchorPx
                                oy = 0f
                                rx = minOf(size.width * 0.72f, bleedSpreadPx).coerceAtLeast(1f)
                                ry = minOf(size.height * 0.44f, bleedDepthPx).coerceAtLeast(1f)
                            }
                        }
                        val geometryChanged = cachedOx != ox || cachedOy != oy || cachedRx != rx || cachedRy != ry
                        if (geometryChanged) {
                            cachedOx = ox
                            cachedOy = oy
                            cachedRx = rx
                            cachedRy = ry
                            matrix.reset()
                            matrix.setScale(rx, ry)
                            matrix.postTranslate(ox, oy)
                            radial.setLocalMatrix(matrix)
                            // Android ComposeShader 在构造时抓取子 Shader 原生快照；仅在颜色或几何变化时重建，呼吸帧零分配。
                            grainMaskedShader = baseGrainShader?.let { grain ->
                                runCatching { ComposeShader(grain, radial, PorterDuff.Mode.DST_IN) }.getOrNull()
                            }
                        }
                        bleedPaint.shader = radial
                        bleedPaint.alpha = (255f * bleedAlpha).roundToInt().coerceIn(0, 255)
                        canvas.drawRect(0f, 0f, size.width, size.height, bleedPaint)

                        // Layer 2: 受光带磨砂微颗粒散射（由同一椭圆梯度自然衰减，零硬边接缝）
                        val maskedGrain = grainMaskedShader
                        if (maskedGrain != null) {
                            val grainAlpha = ((if (dark) 0.14f else 0.08f) * energy).coerceAtMost(if (dark) 0.22f else 0.12f)
                            if (grainAlpha > AmbientMinVisibleEnergy) {
                                grainPaint.shader = maskedGrain
                                grainPaint.alpha = (255f * grainAlpha).roundToInt().coerceIn(0, 255)
                                canvas.drawRect(0f, 0f, size.width, size.height, grainPaint)
                            }
                        }
                    }

                    // Layer 3: 双层厚玻璃切面全内反射亮边（物理厚度质感核心）
                    val rimPeak = ((if (dark) 0.76f else 0.56f) * energy).coerceIn(0f, 0.94f)
                    if (rimPeak > AmbientMinVisibleEnergy) {
                        val primaryAlpha = (255f * rimPeak).roundToInt().coerceIn(0, 255)
                        val secondaryAlpha = (255f * rimPeak * 0.28f).roundToInt().coerceIn(0, 255)
                        when (coupling.edge) {
                            InfiltrationEdge.LEFT, InfiltrationEdge.RIGHT -> {
                                val ex = if (coupling.edge == InfiltrationEdge.LEFT) bevelPrimaryPx * 0.5f else size.width - bevelPrimaryPx * 0.5f
                                val ex2 = if (coupling.edge == InfiltrationEdge.LEFT) bevelInnerOffsetPx else size.width - bevelInnerOffsetPx
                                val span = minOf(edgeSpanPx, maxOf(size.height * 0.65f, 48f))
                                val y0 = (coupling.anchorPx - span).coerceAtLeast(0f)
                                val y1 = (coupling.anchorPx + span).coerceAtMost(size.height)
                                primaryVerticalShader?.let { s ->
                                    matrix.reset()
                                    matrix.setScale(1f, span)
                                    matrix.postTranslate(ex, coupling.anchorPx)
                                    s.setLocalMatrix(matrix)
                                    primaryRimPaint.shader = s
                                    primaryRimPaint.alpha = primaryAlpha
                                    canvas.drawLine(ex, y0, ex, y1, primaryRimPaint)
                                }
                                secondaryVerticalShader?.let { s ->
                                    val span2 = span * 0.75f
                                    matrix.reset()
                                    matrix.setScale(1f, span2)
                                    matrix.postTranslate(ex2, coupling.anchorPx)
                                    s.setLocalMatrix(matrix)
                                    secondaryRimPaint.shader = s
                                    secondaryRimPaint.alpha = secondaryAlpha
                                    canvas.drawLine(
                                        ex2,
                                        (coupling.anchorPx - span2).coerceAtLeast(0f),
                                        ex2,
                                        (coupling.anchorPx + span2).coerceAtMost(size.height),
                                        secondaryRimPaint,
                                    )
                                }
                            }
                            InfiltrationEdge.BOTTOM, InfiltrationEdge.TOP -> {
                                val ey = if (coupling.edge == InfiltrationEdge.BOTTOM) size.height - bevelPrimaryPx * 0.5f else bevelPrimaryPx * 0.5f
                                val ey2 = if (coupling.edge == InfiltrationEdge.BOTTOM) size.height - bevelInnerOffsetPx else bevelInnerOffsetPx
                                val span = minOf(edgeSpanPx, maxOf(size.width * 0.65f, 48f))
                                val x0 = (coupling.anchorPx - span).coerceAtLeast(0f)
                                val x1 = (coupling.anchorPx + span).coerceAtMost(size.width)
                                primaryHorizontalShader?.let { s ->
                                    matrix.reset()
                                    matrix.setScale(span, 1f)
                                    matrix.postTranslate(coupling.anchorPx, ey)
                                    s.setLocalMatrix(matrix)
                                    primaryRimPaint.shader = s
                                    primaryRimPaint.alpha = primaryAlpha
                                    canvas.drawLine(x0, ey, x1, ey, primaryRimPaint)
                                }
                                secondaryHorizontalShader?.let { s ->
                                    val span2 = span * 0.75f
                                    matrix.reset()
                                    matrix.setScale(span2, 1f)
                                    matrix.postTranslate(coupling.anchorPx, ey2)
                                    s.setLocalMatrix(matrix)
                                    secondaryRimPaint.shader = s
                                    secondaryRimPaint.alpha = secondaryAlpha
                                    canvas.drawLine(
                                        (coupling.anchorPx - span2).coerceAtLeast(0f),
                                        ey2,
                                        (coupling.anchorPx + span2).coerceAtMost(size.width),
                                        ey2,
                                        secondaryRimPaint,
                                    )
                                }
                            }
                        }
                    }

                    canvas.restoreToCount(saveCount)
                }
            }
        }
}
