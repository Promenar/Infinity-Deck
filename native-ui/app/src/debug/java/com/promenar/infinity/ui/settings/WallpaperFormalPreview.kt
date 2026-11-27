package com.promenar.infinity.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.wallpaper.ChatWallpaperConfig
import com.promenar.infinity.ui.wallpaper.WallpaperPresetId

/*
 * 正式生产组件验收预览：直接渲染生产版 WallpaperSettingsScreenContent 与 ThemeScreenContent，
 * 用于对照已确认原型 v1（WallpaperPrototype.kt）完成像素级验收。
 */

@Preview(name = "Formal_WallpaperStudio_Dark", widthDp = 390, heightDp = 960, showBackground = true)
@Composable
fun FormalWallpaperStudioDarkPreview() {
    val config = ChatWallpaperConfig.forPreset(WallpaperPresetId.AURORA, dark = true, assistantReadingCard = true)
        .copy(blurDp = 6, dimFraction = 0.36f)
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = InfinityThemeMode.DARK,
            colorSource = InfinityColorSource.INFINITY,
            wallpaper = config,
        ),
    ) {
        WallpaperSettingsScreenContent(
            config = config,
            onNavigateBack = {},
            onReset = {},
            onSelectNone = {},
            onPickPhoto = {},
            onSelectPreset = {},
            onBlurChange = {},
            onDimChange = {},
            onAssistantCardChange = {},
            onApplyAccent = {},
        )
    }
}

@Preview(name = "Formal_WallpaperStudio_Light", widthDp = 390, heightDp = 960, showBackground = true)
@Composable
fun FormalWallpaperStudioLightPreview() {
    val config = ChatWallpaperConfig.forPreset(WallpaperPresetId.WARM_PAPER, dark = false, assistantReadingCard = true)
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
            primaryColor = androidx.compose.ui.graphics.Color(WallpaperPresetId.WARM_PAPER.accentSeedArgb).value.toLong(),
            wallpaper = config,
        ),
    ) {
        WallpaperSettingsScreenContent(
            config = config,
            onNavigateBack = {},
            onReset = {},
            onSelectNone = {},
            onPickPhoto = {},
            onSelectPreset = {},
            onBlurChange = {},
            onDimChange = {},
            onAssistantCardChange = {},
            onApplyAccent = {},
        )
    }
}

@Preview(name = "Formal_WallpaperStudio_WideFold", widthDp = 840, heightDp = 640, showBackground = true)
@Composable
fun FormalWallpaperStudioWidePreview() {
    val config = ChatWallpaperConfig.forPreset(WallpaperPresetId.PINE_MIST, dark = true, assistantReadingCard = true)
        .copy(blurDp = 8, dimFraction = 0.42f)
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = InfinityThemeMode.DARK,
            colorSource = InfinityColorSource.INFINITY,
            wallpaper = config,
        ),
    ) {
        WallpaperSettingsScreenContent(
            config = config,
            onNavigateBack = {},
            onReset = {},
            onSelectNone = {},
            onPickPhoto = {},
            onSelectPreset = {},
            onBlurChange = {},
            onDimChange = {},
            onAssistantCardChange = {},
            onApplyAccent = {},
        )
    }
}

@Preview(name = "Formal_ThemeScreen_WithWallpaperEntry", widthDp = 390, heightDp = 760, showBackground = true)
@Composable
fun FormalThemeScreenWallpaperEntryPreview() {
    val config = ChatWallpaperConfig.forPreset(WallpaperPresetId.AURORA, dark = true)
    val prefs = InfinityThemePreferences(
        mode = InfinityThemeMode.DARK,
        colorSource = InfinityColorSource.INFINITY,
        wallpaper = config,
    )
    InfinityTheme(preferences = prefs) {
        ThemeScreenContent(
            state = ThemeUiState(preferences = prefs, dynamicColorAvailable = true),
            onModeSelect = {},
            onColorSourceSelect = {},
            onNavigateBack = {},
            onOpenWallpaper = {},
        )
    }
}
