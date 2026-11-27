package com.promenar.infinity.ui.wallpaper

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 内置零包体程序化壁纸预设。
 * 预设本身通过 Compose Canvas 实时绘制，不增加 APK 体积，并自带与视觉协调的推荐主色与默认遮罩强度。
 */
enum class WallpaperPresetId(
    val storageKey: String,
    val accentSeedArgb: Int,
    val defaultDimDark: Float,
    val defaultDimLight: Float,
) {
    AURORA("aurora", 0xFF64B5F6.toInt(), 0.28f, 0.22f),
    OBSIDIAN_TOPO("obsidian_topo", 0xFF9FA8DA.toInt(), 0.20f, 0.22f),
    PINE_MIST("pine_mist", 0xFF4DB6AC.toInt(), 0.26f, 0.22f),
    AMBER_DUSK("amber_dusk", 0xFFFFB74D.toInt(), 0.28f, 0.24f),
    ABYSSAL_WAVE("abyssal_wave", 0xFF5C8DF6.toInt(), 0.22f, 0.22f),
    WARM_PAPER("warm_paper", 0xFFC88A55.toInt(), 0.42f, 0.18f),
    ;

    fun defaultDim(dark: Boolean): Float = if (dark) defaultDimDark else defaultDimLight

    companion object {
        fun fromStorageKey(value: String?): WallpaperPresetId? =
            entries.firstOrNull { it.storageKey.equals(value, ignoreCase = true) }

        /** 浅色主题下把「暖砂纸纹」前置到首位，便于首屏立即可见。 */
        fun ordered(dark: Boolean): List<WallpaperPresetId> = if (dark) {
            listOf(AURORA, OBSIDIAN_TOPO, PINE_MIST, AMBER_DUSK, ABYSSAL_WAVE, WARM_PAPER)
        } else {
            listOf(WARM_PAPER, AURORA, OBSIDIAN_TOPO, PINE_MIST, AMBER_DUSK, ABYSSAL_WAVE)
        }
    }
}

/**
 * 壁纸内容来源。采用受限接口便于后续无侵入扩展动态着色器、循环视频或宠物联动场景。
 */
@Immutable
sealed interface WallpaperSource {
    data object None : WallpaperSource

    @Immutable
    data class Preset(val id: WallpaperPresetId) : WallpaperSource

    @Immutable
    data class Image(
        val fileName: String,
        val extractedAccentArgb: Int? = null,
    ) : WallpaperSource
}

/**
 * 壁纸生效作用域。一期使用 [Global]，架构预留按智能体与单会话覆写扩展位。
 */
@Immutable
sealed interface WallpaperScope {
    data object Global : WallpaperScope
    data class Agent(val agentId: String) : WallpaperScope
    data class Session(val sessionId: String) : WallpaperScope
}

@Immutable
data class ChatWallpaperConfig(
    val source: WallpaperSource = WallpaperSource.None,
    /** 背景模糊半径（dp，0–24）；图片壁纸由本地缓存预烘焙，滑动零开销。 */
    val blurDp: Int = DEFAULT_PRESET_BLUR_DP,
    /** 主题适配可读性遮罩强度（0f–0.85f）：深色模式叠加黑曜石压暗，浅色模式叠加柔光雾面。 */
    val dimFraction: Float = DEFAULT_PHOTO_DIM_DARK,
    /** 开启后为助手回复正文叠加半透毛玻璃阅读衬底，保证复杂照片下高可读。 */
    val assistantReadingCard: Boolean = true,
) {
    val isActive: Boolean get() = source != WallpaperSource.None

    /** 仅在壁纸启用且开关打开时启用助手半透衬底；关闭壁纸时完全还原原生无框排版。 */
    val effectiveAssistantCard: Boolean get() = isActive && assistantReadingCard

    val clampedBlurDp: Int get() = blurDp.coerceIn(MIN_BLUR_DP, MAX_BLUR_DP)

    val clampedDimFraction: Float get() = dimFraction.coerceIn(MIN_DIM_FRACTION, MAX_DIM_FRACTION)

    /** 供「提取壁纸主色为强调色」使用的推荐 ARGB 种子色。 */
    fun recommendedAccentArgb(): Int? = when (val s = source) {
        WallpaperSource.None -> null
        is WallpaperSource.Preset -> s.id.accentSeedArgb
        is WallpaperSource.Image -> s.extractedAccentArgb
    }

    fun recommendedAccentColor(): Color? = recommendedAccentArgb()?.let { Color(it) }

    companion object {
        const val MIN_BLUR_DP = 0
        const val MAX_BLUR_DP = 24
        const val MIN_DIM_FRACTION = 0f
        const val MAX_DIM_FRACTION = 0.85f
        const val DEFAULT_PRESET_BLUR_DP = 0
        const val DEFAULT_PHOTO_BLUR_DP = 8
        const val DEFAULT_PHOTO_DIM_DARK = 0.48f
        const val DEFAULT_PHOTO_DIM_LIGHT = 0.42f

        const val KIND_NONE = "none"
        const val KIND_PRESET = "preset"
        const val KIND_IMAGE = "image"

        fun forPreset(preset: WallpaperPresetId, dark: Boolean, assistantReadingCard: Boolean = true): ChatWallpaperConfig =
            ChatWallpaperConfig(
                source = WallpaperSource.Preset(preset),
                blurDp = DEFAULT_PRESET_BLUR_DP,
                dimFraction = preset.defaultDim(dark),
                assistantReadingCard = assistantReadingCard,
            )

        fun forImportedImage(
            fileName: String,
            extractedAccentArgb: Int?,
            dark: Boolean,
        ): ChatWallpaperConfig = ChatWallpaperConfig(
            source = WallpaperSource.Image(fileName = fileName, extractedAccentArgb = extractedAccentArgb),
            blurDp = DEFAULT_PHOTO_BLUR_DP,
            dimFraction = if (dark) DEFAULT_PHOTO_DIM_DARK else DEFAULT_PHOTO_DIM_LIGHT,
            assistantReadingCard = true,
        )

        /** 校验单段安全文件名，防止路径穿越或损坏偏好残留。 */
        fun isSafeImageFileName(fileName: String?): Boolean {
            if (fileName.isNullOrBlank()) return false
            if (fileName.length > 128) return false
            if ('/' in fileName || '\\' in fileName || ".." in fileName) return false
            return fileName.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
        }

        fun fromPersisted(
            kind: String?,
            presetKey: String?,
            imageFileName: String?,
            extractedAccentArgb: Int?,
            blurDp: Int,
            dimFraction: Float,
            assistantReadingCard: Boolean,
        ): ChatWallpaperConfig {
            val source = when (kind?.lowercase()) {
                KIND_PRESET -> WallpaperPresetId.fromStorageKey(presetKey)
                    ?.let { WallpaperSource.Preset(it) }
                    ?: WallpaperSource.None

                KIND_IMAGE -> imageFileName
                    ?.takeIf(::isSafeImageFileName)
                    ?.let { WallpaperSource.Image(fileName = it, extractedAccentArgb = extractedAccentArgb) }
                    ?: WallpaperSource.None

                else -> WallpaperSource.None
            }
            return ChatWallpaperConfig(
                source = source,
                blurDp = blurDp.coerceIn(MIN_BLUR_DP, MAX_BLUR_DP),
                dimFraction = if (dimFraction.isFinite()) dimFraction.coerceIn(MIN_DIM_FRACTION, MAX_DIM_FRACTION) else DEFAULT_PHOTO_DIM_DARK,
                assistantReadingCard = assistantReadingCard,
            )
        }
    }
}

/** 当前生效的聊天壁纸配置；默认关闭（[WallpaperSource.None]），不影响未开启壁纸的页面与截图基线。 */
val LocalChatWallpaperConfig = staticCompositionLocalOf { ChatWallpaperConfig() }
