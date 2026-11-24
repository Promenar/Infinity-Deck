package com.promenar.infinity.ui.common.motion

import com.promenar.infinity.ui.theme.InfinityPureWhite
import com.promenar.infinity.ui.theme.InfinityPureBlack
import com.promenar.infinity.ui.theme.InfinityLedFaceplateLight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.promenar.infinity.ui.chat.vividAccent
import com.promenar.infinity.ui.common.hueMidpoint
import com.promenar.infinity.ui.theme.infinityDomainColors

/**
 * 灯语动效的统一配色源：
 * - [a] 主色灯（等待、流式主通道与重试），跟随主题主色；
 * - [b] 第三色灯（工具执行与流式点缀），跟随主题第三色；
 * - [approval] 等待审批警示灯，固定取领域警示色，使运行坞点阵屏、细灯带、浮岛漫光、外圈流光与行内指示在所有配色风格下保持上下完全一致；
 * - [mid] 为 [a] 与 [b] 的色相中点，[hot] 为高亮灯芯。浅色主题提亮加饱和后才像“发光”。
 */
@Immutable
internal data class MotionPalette(
    val a: Color,
    val b: Color,
    val approval: Color,
    val mid: Color,
    val hot: Color,
    val outline: Color,
    val muted: Color,
    val dark: Boolean,
) {
    /** 熄灭灯的底色。 */
    val off: Color get() = a.copy(alpha = if (dark) 0.14f else 0.18f)

    /** 面板底板：深色压暗，浅色只留一层淡灰。 */
    val faceplate: Color get() = if (dark) InfinityPureBlack.copy(alpha = 0.5f) else InfinityLedFaceplateLight

    val leds: List<Color> get() = listOf(a, b)

    val approvalLeds: List<Color> get() = listOf(a, approval)

    /** 按生成阶段返回统一的双色灯槽（0 主色、1 阶段强调色）；审批阶段所有动效表面共享警示色。 */
    fun phaseLeds(phase: GenerationMotionPhase?): List<Color> =
        if (phase == GenerationMotionPhase.APPROVAL) approvalLeds else leds

    /** 工具阶段返回第三色，等待审批阶段返回警示色，供单色外圈、背光与指示复用。 */
    fun phaseAccent(phase: GenerationMotionPhase): Color =
        if (phase == GenerationMotionPhase.APPROVAL) approval else b
}

internal fun resolveMotionPalette(
    primary: Color,
    tertiary: Color,
    warning: Color,
    outlineVariant: Color,
    onSurfaceVariant: Color,
    dark: Boolean,
): MotionPalette {
    val a = if (dark) primary else vividAccent(primary)
    val b = if (dark) tertiary else vividAccent(tertiary)
    val approval = if (dark) warning else vividAccent(warning)
    return MotionPalette(
        a = a,
        b = b,
        approval = approval,
        mid = hueMidpoint(a, b),
        hot = if (dark) lerp(a, InfinityPureWhite, 0.55f) else a,
        outline = outlineVariant,
        muted = onSurfaceVariant,
        dark = dark,
    )
}

@Composable
internal fun rememberMotionPalette(): MotionPalette {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val dark = domain.isDark
    val warning = domain.warning
    return remember(scheme.primary, scheme.tertiary, warning, scheme.outlineVariant, scheme.onSurfaceVariant, dark) {
        resolveMotionPalette(
            primary = scheme.primary,
            tertiary = scheme.tertiary,
            warning = warning,
            outlineVariant = scheme.outlineVariant,
            onSurfaceVariant = scheme.onSurfaceVariant,
            dark = dark,
        )
    }
}
