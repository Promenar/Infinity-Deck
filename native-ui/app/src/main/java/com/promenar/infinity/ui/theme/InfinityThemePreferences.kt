package com.promenar.infinity.ui.theme

enum class InfinityThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        internal fun fromPreferenceValue(value: String?): InfinityThemeMode =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DARK
    }
}

enum class InfinityColorSource {
    INFINITY,
    DYNAMIC;

    companion object {
        internal fun fromPreferenceValue(value: String?): InfinityColorSource =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: INFINITY
    }
}

/** 触摸锁的解锁方式：长按锁按钮，或把锁滑到轨道尽头。 */
enum class TouchLockUnlock {
    HOLD,
    SLIDE;

    companion object {
        internal fun fromPreferenceValue(value: String?): TouchLockUnlock =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: HOLD
    }
}

/** 默认主色：冰蓝预设。 */
val InfinityDefaultPrimaryColor: Long = InfinityIceBlueSeed.value.toLong()

/**
 * 外观偏好。默认值只作用于从未手动设置过的用户：偏好里没有对应键时才回退到这里，已有的值原样保留。
 */
data class InfinityThemePreferences(
    val mode: InfinityThemeMode = InfinityThemeMode.DARK,
    val colorSource: InfinityColorSource = InfinityColorSource.INFINITY,
    /** 主色种子（Compose [androidx.compose.ui.graphics.Color.value]）；null 表示内置淡紫配色。从未选择过时为冰蓝。 */
    val primaryColor: Long? = InfinityDefaultPrimaryColor,
    /** 由种子色生成配色的算法；只对预设色与自定义色生效，跟随系统取色时不用。 */
    val schemeStyle: InfinitySchemeStyle = InfinitySchemeStyle.TONAL_SPOT,
    /** 深色模式下用纯黑背景；从未设置过时开启。 */
    val pureBlack: Boolean = true,
    val textScaleEnabled: Boolean = false,
    val textScale: Float = 1f,
    /** 一轮回复完成后把思考与工具过程收为一行摘要，只单独显示最终正文。 */
    val collapseFinishedTurns: Boolean = true,
    /** 点按、开关、发送与审批等交互的触感反馈；关闭后状态提醒也不振动。 */
    val hapticsEnabled: Boolean = true,
    /** 等待审批、较长生成完成或失败时振动一次。 */
    val statusHapticsEnabled: Boolean = true,
    /** 生成中锁定触摸后的解锁方式；生成期间屏幕始终保持常亮。 */
    val touchLockUnlock: TouchLockUnlock = TouchLockUnlock.HOLD,
    /** 输入框浮岛、生成状态指示与空会话三处动效的样式，颜色都跟随主题色。 */
    val motion: InfinityMotionStyles = InfinityMotionStyles(),
    /** 最近用过的自定义取色（ARGB，最新在前）。 */
    val recentCustomColors: List<Int> = emptyList(),
    /** 聊天壁纸配置；默认关闭，开启后自动提供模糊、遮罩与可选助手半透阅读衬底。 */
    val wallpaper: com.promenar.infinity.ui.wallpaper.ChatWallpaperConfig = com.promenar.infinity.ui.wallpaper.ChatWallpaperConfig(),
)
