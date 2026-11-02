package com.promenar.infinity.ui.welcome

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.promenar.infinity.ui.common.motion.rememberMotionClock
import com.promenar.infinity.ui.common.network.rememberAnimationsEnabled
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinityPureBlack
import com.promenar.infinity.ui.theme.InfinityStarCold
import com.promenar.infinity.ui.theme.InfinityStarWarm
import kotlin.math.sin
import kotlin.random.Random

/** 引导页的尺寸：控件缩小一档，留白加大。 */
internal object OnboardingDimens {
    val PageMargin = 28.dp
    val SectionGap = 24.dp
    val RowMinHeight = 54.dp
    val FieldHeight = 46.dp
    val ButtonHeight = 46.dp
    val TileMinHeight = 52.dp
    val TileIcon = 30.dp
    val CardRadius = 18.dp
    /** 内屏右栏内容限宽。 */
    val ContentMaxWidth = 520.dp
    /** 语言页与就绪页在宽屏上的居中单栏宽度。 */
    val CenteredMaxWidth = 440.dp
}

/** 引导页的文字层级：标题、正文、说明、列表主副文字与按钮。 */
internal object OnboardingType {
    @Composable
    fun title(): TextStyle = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, lineHeight = 29.sp, fontWeight = FontWeight.Medium)

    @Composable
    fun body(): TextStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 22.sp)

    @Composable
    fun caption(): TextStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 18.sp)

    @Composable
    fun eyebrow(): TextStyle = caption().copy(letterSpacing = 0.08.em)

    @Composable
    fun rowTitle(): TextStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.5.sp, lineHeight = 21.sp)

    @Composable
    fun rowSubtitle(): TextStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp)

    @Composable
    fun button(): TextStyle = MaterialTheme.typography.labelLarge.copy(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.02.em)
}

/** 第三档文字（说明、未到的步骤）。 */
internal val onboardingFaintText: Color
    @Composable get() = MaterialTheme.colorScheme.outline

/** 卡片底色：近黑、略透出星空。 */
internal val onboardingCardColor: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.72f)

/** 卡片与胶囊的细边框。 */
internal val onboardingLineColor: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh

/** 近黑底、细边框、圆角 18dp 的卡片，不发光。 */
@Composable
internal fun OnboardingCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(OnboardingDimens.CardRadius),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = shape,
        color = onboardingCardColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, onboardingLineColor),
        modifier = modifier.fillMaxWidth(),
    ) { Column(content = content) }
}

/** 主按钮：高 46dp 的胶囊，主色填充。 */
@Composable
internal fun OnboardingPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 20.dp),
        modifier = modifier.fillMaxWidth().height(OnboardingDimens.ButtonHeight),
        content = content,
    )
}

/** 步骤进度：[count] 段、2dp 高；已完成的段为主色，当前段亮一半。 */
@Composable
internal fun OnboardingProgressBar(count: Int, current: Int, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val primary = MaterialTheme.colorScheme.primary
    Row(modifier.fillMaxWidth().height(2.dp).clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { index ->
            val fill by animateColorAsState(
                if (index <= current) primary else track,
                tween(InfinityMotion.DurationMedium),
                label = "onboarding_progress_$index",
            )
            val half = index == current
            Canvas(Modifier.weight(1f).fillMaxSize()) {
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(track, cornerRadius = radius)
                drawRoundRect(fill, size = if (half) Size(size.width / 2, size.height) else size, cornerRadius = radius)
            }
        }
    }
}

/** 星空里的一颗星：位置为 0..1 的比例，[tier] 为亮度档 0..2。 */
@Immutable
internal class OnboardingStar(val x: Float, val y: Float, val tier: Int, val phase: Float, val speed: Float, val warm: Boolean)

internal const val OnboardingStarCount = 140

/** 生成稀疏星空：约 65% 暗星、27% 中等、8% 亮星，6% 偏暖。同一种子结果确定，截图稳定。 */
internal fun onboardingStars(count: Int = OnboardingStarCount, seed: Int = 29): List<OnboardingStar> {
    val random = Random(seed)
    return List(count) {
        val r = random.nextFloat()
        OnboardingStar(
            x = random.nextFloat(),
            y = random.nextFloat(),
            tier = when {
                r < 0.08f -> 2
                r < 0.35f -> 1
                else -> 0
            },
            phase = random.nextFloat() * 6.283f,
            speed = 0.4f + random.nextFloat() * 1.2f,
            warm = random.nextFloat() < 0.06f,
        )
    }
}

/** 星的横向位置（0..1）：整体极慢地向右漂移，亮档略快，出右边后从左边回来。 */
internal fun onboardingStarX(star: OnboardingStar, time: Float): Float = (star.x + time * 0.002f * (star.tier + 1)).mod(1f)

private val StarAlpha = floatArrayOf(0.28f, 0.5f, 0.85f)
private val StarRadiusDp = floatArrayOf(0.5f, 0.8f, 1.25f)

/**
 * 引导页背景：纯黑上的稀疏星空，约 140 颗星分三档亮度，缓慢闪烁并极慢地横向漂移。
 * 只在系统动画开启时推进；关闭动画或静态预览时星点静止、不闪。纯装饰，不进入无障碍树。
 */
@Composable
internal fun OnboardingStarfield(modifier: Modifier = Modifier) {
    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    val clock = rememberMotionClock(running = animate)
    val stars = remember { onboardingStars() }
    Canvas(modifier.fillMaxSize().clearAndSetSemantics {}) {
        drawRect(InfinityPureBlack)
        val time = if (animate) clock.time else 0f
        for (star in stars) {
            val twinkle = if (animate) 0.6f + 0.4f * sin(time * star.speed + star.phase) else 1f
            val alpha = StarAlpha[star.tier] * twinkle
            val radius = StarRadiusDp[star.tier] * density
            val color = if (star.warm) InfinityStarWarm else InfinityStarCold
            val center = Offset(onboardingStarX(star, time) * size.width, star.y * size.height)
            drawCircle(color.copy(alpha = alpha), radius, center)
            if (star.tier == 2) drawCircle(color.copy(alpha = alpha * 0.12f), radius * 4, center)
        }
    }
}

/** 引导页底色之上的整页容器：纯黑星空在下，内容在上。 */
@Composable
internal fun OnboardingBackdrop(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxSize(), color = InfinityPureBlack, contentColor = MaterialTheme.colorScheme.onSurface) {
        Box(Modifier.fillMaxSize()) {
            OnboardingStarfield()
            content()
        }
    }
}
