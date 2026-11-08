package com.promenar.infinity.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.promenar.infinity.ui.common.pressFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 浮岛的几何与质感。默认值就是正式界面；视觉实验室经 [LocalComposerIslandStyle] 换成 [ComposerIslandVariant] 的其它档位做整页对比。
 */
@Immutable
internal data class ComposerIslandStyle(
    val corner: Dp = ComposerIslandCorner,
    val shadowElevation: Dp = 2.dp,
    /** 底色比卡片高一级，实色描边，深色上沿提亮一档。 */
    val layered: Boolean = false,
    /** 发送键圆角；null 为圆形。 */
    val sendCorner: Dp? = null,
    /** 工具行胶囊圆角；null 为全圆。 */
    val chipCorner: Dp? = null,
    /** 胶囊外加 0.5dp 细描边。 */
    val chipOutline: Boolean = false,
    /** 工具行沉进一条托盘，与输入区之间一道细线。 */
    val toolbarTray: Boolean = false,
    /** 浮岛外圈光晕强度的倍数；0 时生成只留贴边描边。 */
    val outerHaloScale: Float = 0.45f,
    /** 生成时贴边流光的线宽。 */
    val glowStroke: Dp = 1.5.dp,
) {
    val sendShape: Shape get() = sendCorner?.let { RoundedCornerShape(it) } ?: CircleShape
    val chipShape: Shape get() = chipCorner?.let { RoundedCornerShape(it) } ?: CircleShape
}

internal val LocalComposerIslandStyle = staticCompositionLocalOf { ComposerIslandStyle() }

/** 工具行胶囊的按压反馈、填充与形状；[outlined] 为 false 的胶囊（推理档位）不加细描边。 */
@Composable
internal fun Modifier.composerChip(interaction: InteractionSource, fill: Color, outlined: Boolean = true): Modifier {
    val style = LocalComposerIslandStyle.current
    val shape = style.chipShape
    val outline = MaterialTheme.colorScheme.outlineVariant
    // 底色按矩形铺在形状裁剪之内，边缘只抗锯齿一次。
    return pressFeedback(interaction, shape).background(fill)
        .then(if (style.chipOutline && outlined) Modifier.border(0.5.dp, outline, shape) else Modifier)
}

/** 实验室对比用的三档，从软到硬；外圈圆角都等于发送键圆角加 [ComposerToolbarInset]，与发送键同心。 */
internal enum class ComposerIslandVariant(val style: ComposerIslandStyle) {
    /** 正式界面：短投影、低描边、外圈光晕压低。 */
    CURRENT(ComposerIslandStyle()),

    /** 发送键与胶囊改成圆角方形，外圈 24dp。 */
    SQUARE(
        ComposerIslandStyle(
            corner = 24.dp, sendCorner = 16.dp, chipCorner = 12.dp, outerHaloScale = 0f,
        ),
    ),

    /** 圆角与设置卡片一致，分量改由底色、托盘与细描边给出。 */
    LAYERED(
        ComposerIslandStyle(
            corner = 20.dp, shadowElevation = 3.dp, layered = true,
            sendCorner = 12.dp, chipCorner = 10.dp, chipOutline = true, toolbarTray = true,
            outerHaloScale = 0f, glowStroke = 1.dp,
        ),
    ),
}
