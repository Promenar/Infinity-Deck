package com.promenar.infinity.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/** 生成中输入框浮岛的光效样式。 */
enum class ComposerGlowStyle {
    /** 光带绕浮岛环行，外圈带噪声光晕。 */
    AURORA,
    /** 整圈描边随呼吸节奏涨落。 */
    BREATHE,
    /** 浮岛右半像冲孔网罩，状态灯在网后闪烁。 */
    MESH,
    /** 底沿内侧两排细灯，数据包从左往右流过。 */
    BOTTOM_STRIP,
    /** 不画额外流光，只保留浮岛材质自带的生成光（环境光漫色或毛玻璃）；生成状态交给运行坞点阵屏表达。 */
    MATERIAL_ONLY,
    /** 斜向光带扫过浮岛里隐藏的灯珠墙。 */
    SWEEP_GRID;

    companion object {
        /** 点阵屏拆进运行坞前的取值；读到时按仅材质光处理，点阵屏改由 [InfinityMotionStyles.runDockScope] 控制。 */
        internal const val LegacyScopeScreenValue = "scope_screen"

        internal fun fromPreferenceValue(value: String?): ComposerGlowStyle =
            if (value.equals(LegacyScopeScreenValue, ignoreCase = true)) MATERIAL_ONLY
            else entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: MATERIAL_ONLY
    }
}

/** 输入框浮岛的材质。 */
enum class ComposerSurfaceStyle {
    /** 毛玻璃：半透明表面，背后的会话内容实时模糊，上沿一道细高光；生成光效沿用所选流光样式但减弱。 */
    FROSTED,
    /** 环境光：实色表面，不画超出浮岛边界的光晕；生成时阶段色从运行坞点阵屏一侧在浮岛内部漫开。 */
    AMBIENT;

    companion object {
        internal fun fromPreferenceValue(value: String?): ComposerSurfaceStyle =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AMBIENT
    }
}

/** 生成状态指示的样式；会话气泡、正文光标与首页会话列表共用。 */
enum class GenerationIndicatorStyle {
    /** 三点依次上浮。 */
    WAVE,
    /** 三组网口灯：左灯常亮、右灯高频闪烁。 */
    PORTS,
    /** 10×3 点阵按负载场亮灭。 */
    MATRIX,
    /** 上下两排共 16 个交换机口。 */
    STACKED,
    /** 四个硬盘位，各一颗电源灯与活动灯。 */
    BAYS,
    /** 两个光头沿 ∞ 反向追逐。 */
    INFINITY_LINE,
    /** 24 颗方灯排成 ∞，数据包沿 8 字跑圈。 */
    INFINITY_LED;

    companion object {
        internal fun fromPreferenceValue(value: String?): GenerationIndicatorStyle =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: MATRIX
    }
}

/** 空会话的背景动画样式。 */
enum class EmptyChatStyle {
    /** 字标由弹簧粒子拼成，环境粒子沿流场漂移。 */
    FLOW,
    /** 字标排成灯珠点阵，数据光带扫过。 */
    LED_WORDMARK,
    /** 粒子铺满 ∞ 形莫比乌斯带流动。 */
    MOBIUS,
    /** 莫比乌斯带上固定排着灯珠。 */
    MOBIUS_LED,
    /** 粒子在字标与莫比乌斯带之间往复变形。 */
    MORPH,
    /** 灯珠排成缓缓自转的球。 */
    LED_GLOBE,
    /** 灯珠字标与灯珠莫比乌斯环之间往复变形。 */
    LED_MORPH;

    companion object {
        internal fun fromPreferenceValue(value: String?): EmptyChatStyle =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: LED_MORPH
    }
}

/** 由种子色生成配色的算法，对应 Material Color Utilities 的几种方案。 */
enum class InfinitySchemeStyle {
    /** 柔和：压低种子色饱和度，整体沉稳。 */
    TONAL_SPOT,
    /** 鲜艳：主色保持高饱和。 */
    VIBRANT,
    /** 忠实：主色尽量贴近所选颜色。 */
    FIDELITY,
    /** 表现力：辅色与第三色偏移色相，对比更强。 */
    EXPRESSIVE;

    companion object {
        internal fun fromPreferenceValue(value: String?): InfinitySchemeStyle =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: TONAL_SPOT
    }
}

/** 默认值只作用于从未手动选择过的用户：偏好里没有对应键时才回退到这里，选择过的值原样保留。 */
@Immutable
data class InfinityMotionStyles(
    val composer: ComposerGlowStyle = ComposerGlowStyle.MATERIAL_ONLY,
    val indicator: GenerationIndicatorStyle = GenerationIndicatorStyle.MATRIX,
    val emptyChat: EmptyChatStyle = EmptyChatStyle.LED_MORPH,
    /** 输入框浮岛的材质。 */
    val surface: ComposerSurfaceStyle = ComposerSurfaceStyle.AMBIENT,
    /** 运行坞右侧是否显示点阵波形屏；与 [composer] 的浮岛流光互相独立。 */
    val runDockScope: Boolean = true,
    /** 运行坞点阵屏是否显示数字读数；关闭时波形铺满整块屏。 */
    val scopeDigits: Boolean = true,
)

/** 当前生效的动效样式；由 [InfinityTheme] 按外观设置提供，设置页预览可以局部覆盖。 */
val LocalInfinityMotionStyles = staticCompositionLocalOf { InfinityMotionStyles() }
