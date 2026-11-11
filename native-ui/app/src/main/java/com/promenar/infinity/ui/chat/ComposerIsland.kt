package com.promenar.infinity.ui.chat

import android.graphics.Matrix
import android.graphics.RuntimeShader
import android.graphics.SweepGradient
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.motion.GenerationMotion
import com.promenar.infinity.ui.common.motion.GenerationMotionPhase
import com.promenar.infinity.ui.common.motion.LocalGenerationMotion
import com.promenar.infinity.ui.common.motion.breath
import com.promenar.infinity.ui.common.motion.evenBlink
import com.promenar.infinity.ui.common.motion.rememberMotionPalette
import com.promenar.infinity.ui.common.motion.slowBlink
import com.promenar.infinity.ui.common.network.rememberAnimationsEnabled
import com.promenar.infinity.ui.theme.ComposerGlowStyle
import com.promenar.infinity.ui.theme.ComposerSurfaceStyle
import com.promenar.infinity.ui.theme.InfinityPureWhite
import com.promenar.infinity.ui.theme.LocalInfinityMotionStyles
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** 输入框浮岛的圆角；描边、光晕与外壳共用。等于发送键半径加 [ComposerToolbarInset]，外圈与发送键同心。 */
internal val ComposerIslandCorner = 28.dp

/** 工具行距浮岛左、右、底边的留白。 */
internal val ComposerToolbarInset = 8.dp

/** 光带绕浮岛一圈的时长；描边与光晕共用同一角度，始终同步。 */
internal const val ComposerGlowPeriodSeconds = 2.8f

/**
 * 浮岛材质。环境光为实色：深色沿用底部导航栏的抬升底色与低透明描边，浅色改用纯白底，层次靠短投影与描边。
 * 毛玻璃时容器透明，底色由 [ComposerIslandSurface] 画模糊背景再叠 [frostedTint]；描边改为浅色亮边，深浅色都画上沿 1px 内高光。
 */
internal data class ComposerIslandMaterial(
    val container: Color,
    val border: BorderStroke,
    val topHighlight: Color?,
    /** 工具行胶囊（模型名、推理档位）的填充色，比浮岛底色高一级。 */
    val chip: Color,
    /** 毛玻璃叠在模糊背景上的半透明色；实色材质为 null。 */
    val frostedTint: Color? = null,
)

@Composable
internal fun composerIslandMaterial(): ComposerIslandMaterial {
    val scheme = MaterialTheme.colorScheme
    val dark = MaterialTheme.infinityDomainColors.isDark
    val style = LocalComposerIslandStyle.current
    fun rim(color: Color, alpha: Float) = BorderStroke(1.dp, color.copy(alpha = alpha))
    if (LocalInfinityMotionStyles.current.surface == ComposerSurfaceStyle.FROSTED) {
        return if (dark) {
            ComposerIslandMaterial(
                container = Color.Transparent,
                border = rim(scheme.onSurface, 0.14f),
                topHighlight = scheme.onSurface.copy(alpha = 0.16f),
                chip = scheme.onSurface.copy(alpha = 0.08f),
                frostedTint = scheme.surfaceContainer.copy(alpha = 0.6f),
            )
        } else {
            ComposerIslandMaterial(
                container = Color.Transparent,
                border = rim(scheme.outlineVariant, 0.5f),
                topHighlight = InfinityPureWhite.copy(alpha = 0.9f),
                chip = scheme.onSurface.copy(alpha = 0.05f),
                frostedTint = scheme.surfaceContainerLowest.copy(alpha = 0.6f),
            )
        }
    }
    if (style.layered) {
        // 分层：底色比卡片高一级，实色描边；深色上沿提亮一档，胶囊用最高一级底色配细描边。
        return if (dark) {
            ComposerIslandMaterial(
                container = scheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, scheme.outlineVariant),
                topHighlight = scheme.onSurface.copy(alpha = 0.12f),
                chip = scheme.surfaceContainerHighest,
            )
        } else {
            ComposerIslandMaterial(
                container = scheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, scheme.outlineVariant),
                topHighlight = null,
                chip = scheme.surfaceContainerLowest,
            )
        }
    }
    return if (dark) {
        ComposerIslandMaterial(
            container = scheme.surfaceContainer,
            border = rim(scheme.outlineVariant, 0.41f),
            topHighlight = null,
            chip = scheme.surfaceContainerHigh,
        )
    } else {
        ComposerIslandMaterial(
            container = scheme.surfaceContainerLowest,
            border = rim(scheme.outlineVariant, 0.56f),
            topHighlight = null,
            chip = scheme.surfaceContainerLow,
        )
    }
}

/** 光带两端颜色：深色直接用主色与第三色；浅色主题的主色偏深，提亮并加饱和后才像“发光”。 */
@Composable
internal fun composerGlowColors(): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return if (MaterialTheme.infinityDomainColors.isDark) {
        scheme.primary to scheme.tertiary
    } else {
        vividAccent(scheme.primary) to vividAccent(scheme.tertiary)
    }
}

/** 在 HSV 空间把颜色推到至少 0.75 饱和、0.95 明度，色相不变；灰色保持灰色。 */
internal fun vividAccent(color: Color): Color {
    val r = color.red
    val g = color.green
    val b = color.blue
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val delta = maxC - minC
    if (delta < 1e-4f) return Color(maxOf(maxC, 0.95f), maxOf(maxC, 0.95f), maxOf(maxC, 0.95f), color.alpha)
    val hue = when (maxC) {
        r -> 60f * (((g - b) / delta).mod(6f))
        g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }
    val saturation = max(delta / maxC, 0.75f)
    val value = max(maxC, 0.95f)
    return Color.hsv(hue, saturation, value, color.alpha)
}

/** SweepGradient 的 0° 在 3 点钟方向，光带角度以 12 点钟为 0°，两者相差 90°。 */
internal fun sweepRotationForHead(headDegrees: Float): Float = headDegrees - 90f

internal fun advanceGlowAngle(angle: Float, deltaSeconds: Float, periodSeconds: Float = ComposerGlowPeriodSeconds): Float =
    (angle + deltaSeconds * 360f / periodSeconds).mod(360f)

/**
 * 极光光带头部的角速度（度/秒）：首字前 6 秒一圈；流式时随速率加快，满速约 1.9 秒一圈；其余阶段光带停下。
 * 阶段交叉淡入淡出时按权重混合，光带不会跳位。
 */
internal fun auroraHeadSpeed(motion: GenerationMotion, act: Float): Float =
    motion.weight(GenerationMotionPhase.WAIT) * 60f +
        motion.weight(GenerationMotionPhase.STREAM) * (360f / ComposerGlowPeriodSeconds) * (0.5f + act)

/** 光效的动画状态：只在绘制阶段读取，变化时只重绘不重组。随速率累积的相位与时间一同逐帧推进。 */
@Stable
internal class ComposerGlowState {
    var headDegrees by mutableFloatStateOf(0f)
    var timeSeconds by mutableFloatStateOf(0f)
    val generating = Animatable(0f)
    val error = Animatable(0f)
    /** 当前流式强度 0..1。 */
    var act = 0f
    /** 潮汐呼吸在流式阶段的呼吸相位（圈数）。 */
    var breathPhase = 0f
    /** 底沿数据流的流动时间。 */
    var stripPhase = 0f
    /** 扫光点阵在流式阶段的扫光相位（圈数）。 */
    var sweepPhase = 0f
    /** 扫光点阵流式阶段的强度：[act] 按 [SweepGridSpec.RateExponent] 次方映射。 */
    var sweepLevel = 0f
    /** 扫光点阵写入头的位置（格），平滑追赶已到达 Token 数乘 [SweepGridSpec.CellsPerToken]。 */
    var writeHead = 0f
    /** 输入区在浮岛里的上沿（像素）；运行坞等顶层内容在它之上，灯珠墙类样式只铺到这里以下。 */
    var lightsTop by mutableFloatStateOf(0f)
    /** 浮岛左上角在根布局里的位置（像素）。 */
    var islandOriginInRoot by mutableStateOf(Offset.Zero)
    /** 运行坞点阵屏中心在根布局里的位置（像素）；没有点阵屏时为 [Offset.Unspecified]。 */
    var scopeCenterInRoot by mutableStateOf(Offset.Unspecified)

    /** 运行坞点阵屏中心在浮岛里的位置；没有点阵屏时为 [Offset.Unspecified]。 */
    val scopeCenter: Offset
        get() = scopeCenterInRoot.takeIf { it.isSpecified }?.minus(islandOriginInRoot) ?: Offset.Unspecified

    fun advance(delta: Float, motion: GenerationMotion) {
        timeSeconds += delta
        act = motion.act(timeSeconds)
        sweepLevel = sweepRateLevel(act)
        headDegrees = (headDegrees + delta * auroraHeadSpeed(motion, act)).mod(360f)
        breathPhase += delta * (0.6f + 1.4f * act) / 2.6f
        stripPhase += delta * (0.4f + act)
        sweepPhase += delta * (0.5f + sweepLevel) / 2.4f
        val target = motion.writtenTokens(timeSeconds) * SweepGridSpec.CellsPerToken
        // 新一轮从头累计时直接回到起点；否则按时间常数追上，一批字到达时写入头平滑前进。
        writeHead = if (target < writeHead) target else writeHead + (target - writeHead) * (1 - exp(-delta / SweepGridSpec.HeadEaseSeconds))
    }
}

/** 当前浮岛的光效状态；运行坞点阵屏从这里取生成进度与时钟，并回报自己的位置。浮岛之外为 null。 */
internal val LocalComposerGlow = staticCompositionLocalOf<ComposerGlowState?> { null }

/**
 * 生成中光效淡入并按当前生成阶段（[LocalGenerationMotion]）变化，结束后淡出；进入错误状态时整圈泛红后退去。
 * [generating] 在等待审批时也应为 true，浮岛保持第三色慢闪。系统关闭动画或静态预览时不推进，光带停在右上方。
 */
@Composable
internal fun rememberComposerGlowState(generating: Boolean, error: Boolean): ComposerGlowState {
    val state = remember { ComposerGlowState().apply { headDegrees = 30f } }
    val motion = LocalGenerationMotion.current
    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    LaunchedEffect(generating, animate) {
        val target = if (generating) 1f else 0f
        if (animate) state.generating.animateTo(target, tween(if (generating) 400 else 600)) else state.generating.snapTo(target)
    }
    LaunchedEffect(error, animate) {
        if (!error) return@LaunchedEffect
        if (animate) {
            state.error.snapTo(1f)
            state.error.animateTo(0f, tween(2_200, easing = LinearEasing))
        }
    }
    LaunchedEffect(animate, motion) {
        if (!animate) {
            state.act = motion.act(0.37f)
            state.sweepLevel = sweepRateLevel(state.act)
            return@LaunchedEffect
        }
        // 只在光效可见时逐帧推进；空闲时不申请帧。
        snapshotFlow { state.generating.value > 0f || state.error.value > 0f }
            .distinctUntilChanged()
            .collectLatest { visible ->
                if (!visible) return@collectLatest
                var last = -1L
                while (true) {
                    withInfiniteAnimationFrameNanos { now ->
                        if (last > 0) state.advance((now - last) / 1_000_000_000f, motion)
                        last = now
                    }
                }
            }
    }
    return state
}

/**
 * 浮岛外圈光效：按外观设置的样式与当前生成阶段（[LocalGenerationMotion]）绘制，阶段切换时交叉淡入淡出。
 * 极光环行：首字前主色光带 6 秒一圈慢转、光晕减半；流式时转速与亮度随速率增加（Android 13 起光晕用 AGSL）；
 * 工具执行时光带停下、整圈换第三色呼吸；等待审批时整圈第三色慢闪；重试时只剩一圈暗描边等间隔闪。
 * 潮汐呼吸画随各阶段节奏扩散的外圈光晕与渐变描边；灯语样式只留一圈按阶段换色的描边，灯效由 [ComposerIslandLights] 画在浮岛内。
 * 出错时所有样式都整圈泛红。光晕画在浮岛自身边界之外，因此本修饰符须放在裁剪与背景之前。
 * 材质（[ComposerSurfaceStyle]）：毛玻璃整体减弱到 [FrostedGlowScale]；环境光不画任何超出浮岛边界的光晕，
 * 只留贴边描边，柔光、出错的泛色由 [ComposerIslandSurface] 画在浮岛内部。
 */
@Composable
internal fun Modifier.composerIslandGlow(
    state: ComposerGlowState,
    topHighlight: Color?,
    corner: Dp = LocalComposerIslandStyle.current.corner,
): Modifier {
    val (colorA, colorB) = composerGlowColors()
    val style = LocalInfinityMotionStyles.current.composer
    val surface = LocalInfinityMotionStyles.current.surface
    val islandStyle = LocalComposerIslandStyle.current
    val haloScale = islandStyle.outerHaloScale
    val outerHalo = surface != ComposerSurfaceStyle.AMBIENT && haloScale > 0f
    val glowScale = if (surface == ComposerSurfaceStyle.FROSTED) FrostedGlowScale else 1f
    val motion = LocalGenerationMotion.current
    val palette = rememberMotionPalette()
    val outlinePaints = remember { ComposerOutlinePaints() }
    val errorColor = MaterialTheme.infinityDomainColors.signalDown
    val dark = MaterialTheme.infinityDomainColors.isDark
    val colorMid = remember(colorA, colorB) { com.promenar.infinity.ui.common.hueMidpoint(colorA, colorB) }
    // 静态预览不跑着色器；Android 12 只保留描边。着色器按颜色缓存，重组时不重新编译。
    val inspection = LocalInspectionMode.current
    val haloShader: android.graphics.Shader? = remember(colorA, colorB, errorColor, dark, inspection, outerHalo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !inspection && outerHalo) {
            composerHaloShader(colorA, colorB, errorColor, dark)
        } else {
            null
        }
    }
    return this.drawWithCache {
        val cornerPx = corner.toPx()
        val strokePx = islandStyle.glowStroke.toPx()
        val reach = 64.dp.toPx()
        val center = Offset(size.width / 2f, size.height / 2f)
        fun sweepOf(end: Color) = SweepGradient(
            center.x, center.y,
            intArrayOf(colorA.copy(alpha = 0f).toArgb(), colorA.toArgb(), end.toArgb(), end.copy(alpha = 0f).toArgb(), end.copy(alpha = 0f).toArgb()),
            floatArrayOf(0f, 70f / 360f, 130f / 360f, 190f / 360f, 1f),
        )
        // 首字前光带收在主色与两色中间色之间，流式时铺满主色到第三色。
        val streamSweep = sweepOf(colorB)
        val waitSweep = sweepOf(colorMid)
        val sweepMatrix = Matrix()
        val streamBrush = ShaderBrush(streamSweep)
        val waitBrush = ShaderBrush(waitSweep)
        val highlight = topHighlight?.let {
            Brush.verticalGradient(0f to it, 0.4f to Color.Transparent)
        }
        haloShader?.let { updateHaloGeometry(it, center, size, cornerPx, density) }
        val haloBrush = haloShader?.let { ShaderBrush(it) }
        onDrawWithContent {
            val generating = state.generating.value * glowScale
            val error = state.error.value * glowScale
            val time = state.timeSeconds
            val aurora = style == ComposerGlowStyle.AURORA
            val wait = motion.weight(GenerationMotionPhase.WAIT)
            val stream = motion.weight(GenerationMotionPhase.STREAM)
            val tool = motion.weight(GenerationMotionPhase.TOOL)
            val approval = motion.weight(GenerationMotionPhase.APPROVAL)
            val retry = motion.weight(GenerationMotionPhase.RETRY)
            // 环行光晕只属于极光环行的首字前与流式；出错泛红所有样式共用。
            val haloGenerating = if (aurora) generating * (wait * 0.5f + stream * (0.7f + 0.3f * state.act)) * haloScale else 0f
            if (haloShader != null && (haloGenerating > 0f || error > 0f)) {
                updateHaloShader(
                    haloShader, state, haloGenerating, error * haloScale,
                    if (stream + wait > 0f) lerp(colorB, colorMid, wait / (stream + wait)) else colorB,
                )
                drawRect(haloBrush!!, topLeft = Offset(-reach, -reach), size = Size(size.width + reach * 2, size.height + reach * 2))
            }
            val haloGen = generating * haloScale
            if (outerHalo && aurora && generating > 0f) {
                drawHaloRing(haloGen * tool * (0.12f + 0.45f * breath(time, 1.2f)), 12f, 9f, cornerPx, colorB, colorB, outlinePaints)
                drawHaloRing(haloGen * approval * (0.1f + 0.5f * slowBlink(time)), 12f, 9f, cornerPx, colorB, colorB, outlinePaints)
            }
            if (outerHalo && style == ComposerGlowStyle.BREATHE && generating > 0f) {
                drawBreatheHalo(haloGen, state, motion, cornerPx, palette, colorA, colorB, outlinePaints)
            }
            drawContent()
            if (highlight != null) {
                drawRoundRect(
                    brush = highlight,
                    topLeft = Offset(strokePx / 2, strokePx / 2),
                    size = Size(size.width - strokePx, size.height - strokePx),
                    cornerRadius = CornerRadius(cornerPx),
                    style = Stroke(1.dp.toPx()),
                )
            }
            if (generating <= 0f) return@onDrawWithContent
            when (style) {
                ComposerGlowStyle.BREATHE -> drawBreatheStroke(generating, state, motion, cornerPx, palette, colorA, colorB, outlinePaints)
                ComposerGlowStyle.AURORA -> {
                    sweepMatrix.setRotate(sweepRotationForHead(state.headDegrees), center.x, center.y)
                    for (pass in 0 until 2) {
                        val alpha = if (pass == 0) wait * 0.7f else stream
                        if (alpha <= 0.004f) continue
                        (if (pass == 0) waitSweep else streamSweep).setLocalMatrix(sweepMatrix)
                        drawRoundRect(
                            brush = if (pass == 0) waitBrush else streamBrush,
                            topLeft = Offset(strokePx / 2, strokePx / 2),
                            size = Size(size.width - strokePx, size.height - strokePx),
                            cornerRadius = CornerRadius(cornerPx - strokePx / 2),
                            style = Stroke(strokePx),
                            alpha = generating * alpha,
                        )
                    }
                    drawEdgeStroke(generating * tool * (0.45f + 0.5f * breath(time, 1.2f)), 2f, cornerPx, colorB, colorB, outlinePaints)
                    drawEdgeStroke(generating * approval * (0.35f + 0.65f * slowBlink(time)), 2f, cornerPx, colorB, colorB, outlinePaints)
                    drawEdgeStroke(generating * retry * (0.12f + 0.3f * evenBlink(time)), 1.4f, cornerPx, colorA, colorA, outlinePaints)
                }
                // 仅材质光不画描边，生成状态只靠浮岛材质与运行坞点阵屏。
                ComposerGlowStyle.MATERIAL_ONLY -> Unit
                else -> drawPhaseBorder(generating, time, motion, cornerPx, palette, outlinePaints)
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun composerHaloShader(
    colorA: Color,
    colorB: Color,
    errorColor: Color,
    dark: Boolean,
): android.graphics.Shader = RuntimeShader(ComposerHaloAgsl).apply {
    setFloatUniform("lightMode", if (dark) 0f else 1f)
    setColorUniform("colorA", colorA.toArgb())
    setColorUniform("colorB", colorB.toArgb())
    setColorUniform("colorErr", errorColor.toArgb())
}

private fun updateHaloGeometry(shader: android.graphics.Shader, center: Offset, size: Size, cornerPx: Float, density: Float) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val runtime = shader as RuntimeShader
    runtime.setFloatUniform("center", center.x, center.y)
    runtime.setFloatUniform("halfSize", size.width / 2f, size.height / 2f)
    runtime.setFloatUniform("radius", cornerPx)
    runtime.setFloatUniform("density", density)
}

private fun updateHaloShader(shader: android.graphics.Shader, state: ComposerGlowState, generating: Float, error: Float, arcEnd: Color) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val runtime = shader as RuntimeShader
    runtime.setColorUniform("colorB", arcEnd.toArgb())
    runtime.setFloatUniform("time", state.timeSeconds)
    runtime.setFloatUniform("gen", generating)
    runtime.setFloatUniform("err", error)
    runtime.setFloatUniform("head", Math.toRadians(state.headDegrees.toDouble()).toFloat())
}

/**
 * 光晕着色器：以浮岛圆角矩形的外距离衰减，按与光带头部的夹角取一段弧（与描边渐变的 70°–130° 亮段一致），
 * 叠加沿边缘流动的噪声纹理；错误时整圈改为红色并向外多扩散一些。光晕在 44dp 内衰减到 0（绘制范围 64dp），
 * 不会在列表上留下矩形边界。输出为预乘透明度。
 */
internal const val ComposerHaloAgsl = """
uniform float2 center;
uniform float2 halfSize;
uniform float radius;
uniform float density;
uniform float time;
uniform float gen;
uniform float err;
uniform float head;
uniform float lightMode;
layout(color) uniform half4 colorA;
layout(color) uniform half4 colorB;
layout(color) uniform half4 colorErr;

float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }
float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), f.x), mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), f.x), f.y);
}
float fbm(float2 p) {
    float s = 0.0;
    float a = 0.5;
    for (int k = 0; k < 4; k++) { s += a * noise(p); p = p * 2.03 + float2(1.7, 9.2); a *= 0.5; }
    return s;
}
float roundedBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}
half4 main(float2 coord) {
    float2 p = (coord - center) / density;
    float d = max(roundedBox(p, halfSize / density, radius / density), 0.0);
    float an = atan(p.x, -p.y);
    float deg = mod(an - head, 6.2831853) * 57.29578;
    float arc = smoothstep(0.0, 70.0, deg) * (1.0 - smoothstep(130.0, 190.0, deg));
    // 噪声在圆周上取样，角度回绕处没有接缝。
    float2 ring = float2(cos(an - time * 0.23), sin(an - time * 0.23)) * 1.6;
    float tx = fbm(ring + float2(d * 0.06 - time * 0.35, time * 0.11));
    // 在绘制范围之内平滑衰减到 0：长尾在 8 位色深下会留下圆角矩形的色阶轮廓。
    float fade = 1.0 - smoothstep(16.0, 44.0, d);
    float core = exp(-d / 3.5) * 0.55;
    float halo = exp(-d * d / 300.0) * (0.45 + 0.75 * tx);
    float ga = gen * arc * (core + halo * 0.85);
    float gb = gen * 0.07 * exp(-d * d / 200.0);
    float e = err * (exp(-d * d / 500.0) * (0.55 + 0.6 * tx) + 0.35 * exp(-d / 14.0));
    // 弧段颜色随角度渐变；整圈底光用两色中间值，光带头尾相接处不跳色。
    half3 arcCol = mix(colorA.rgb, colorB.rgb, half(smoothstep(70.0, 130.0, deg)));
    half3 baseCol = mix(colorA.rgb, colorB.rgb, half(0.5));
    float g = ga + gb;
    half3 col = (arcCol * half(ga) + baseCol * half(gb)) / half(max(g, 0.0001));
    col = mix(col, colorErr.rgb, half(clamp(e / (g + e + 0.001), 0.0, 1.0)));
    float a = clamp((g + e) * fade * mix(1.0, 0.8, lightMode), 0.0, 1.0);
    // 低亮度区加半级抖动，打散色阶。
    a = clamp(a + (hash(coord) - 0.5) / 255.0 * step(0.002, a), 0.0, 1.0);
    return half4(col * half(a), half(a));
}
"""
