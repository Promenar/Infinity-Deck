package com.promenar.infinity.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat

private val LocalInfinityDomainColors = staticCompositionLocalOf { InfinityDarkDomainColors }

val MaterialTheme.infinityDomainColors: InfinityDomainColors
    @Composable
    @ReadOnlyComposable
    get() = LocalInfinityDomainColors.current

private fun dynamicDomainColors(colorScheme: ColorScheme, dark: Boolean): InfinityDomainColors = InfinityDomainColors(
    success = colorScheme.tertiary,
    onSuccess = colorScheme.onTertiary,
    successContainer = colorScheme.tertiaryContainer,
    onSuccessContainer = colorScheme.onTertiaryContainer,
    warning = colorScheme.secondary,
    onWarning = colorScheme.onSecondary,
    warningContainer = colorScheme.secondaryContainer,
    onWarningContainer = colorScheme.onSecondaryContainer,
    info = colorScheme.primary,
    onInfo = colorScheme.onPrimary,
    infoContainer = colorScheme.primaryContainer,
    onInfoContainer = colorScheme.onPrimaryContainer,
    overlayContent = InfinityDarkDomainColors.overlayContent,
    ragReady = colorScheme.tertiary,
    ragIndexing = colorScheme.primary,
    ragError = colorScheme.error,
    ragPending = colorScheme.outline,
    codeKeyword = colorScheme.tertiary,
    codeDeclaration = colorScheme.primary,
    codeLiteral = colorScheme.secondary,
    codeFunction = colorScheme.tertiary,
    codeString = colorScheme.primary,
    codeComment = colorScheme.onSurfaceVariant,
    signalOn = if (dark) InfinityDarkSignalOn else InfinityLightSignalOn,
    signalPending = if (dark) InfinityDarkSignalPending else InfinityLightSignalPending,
    signalDown = if (dark) InfinityDarkSignalDown else InfinityLightSignalDown,
    isDark = dark,
)

@Composable
fun InfinityTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    preferences: InfinityThemePreferences? = null,
    content: @Composable () -> Unit
) {
    val useDarkTheme = when (preferences?.mode) {
        InfinityThemeMode.SYSTEM -> darkTheme
        InfinityThemeMode.LIGHT -> false
        InfinityThemeMode.DARK -> true
        null -> true
    }
    val useDynamicColor = preferences?.colorSource == InfinityColorSource.DYNAMIC ||
        (preferences == null && dynamicColor)
    val colorScheme = infinityColorScheme(preferences, useDarkTheme, useDynamicColor)
    val domainColors = remember(colorScheme, useDynamicColor, useDarkTheme) {
        when {
            usesDynamicColor(useDynamicColor) -> dynamicDomainColors(colorScheme, useDarkTheme)
            useDarkTheme -> InfinityDarkDomainColors
            else -> InfinityLightDomainColors
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !useDarkTheme
            insetsController.isAppearanceLightNavigationBars = !useDarkTheme
            
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
    }

    val currentDensity = LocalDensity.current
    val textScale = if (preferences?.textScaleEnabled == true) {
        preferences.textScale.coerceIn(0.8f, 1.4f)
    } else {
        1f
    }
    val scaledDensity = remember(currentDensity, textScale) {
        Density(currentDensity.density, currentDensity.fontScale * textScale)
    }

    val hapticsEnabled = preferences?.hapticsEnabled ?: false
    val statusHapticsEnabled = preferences?.statusHapticsEnabled ?: false
    val haptics = remember(view, hapticsEnabled, statusHapticsEnabled) {
        if (view.isInEditMode) com.promenar.infinity.ui.common.InfinityHaptics.None
        else com.promenar.infinity.ui.common.InfinityHaptics(view, hapticsEnabled, statusHapticsEnabled)
    }

    CompositionLocalProvider(
        LocalInfinityThemePreferences provides preferences,
        LocalInfinityDomainColors provides domainColors,
        LocalInfinityMotionStyles provides (preferences?.motion ?: InfinityMotionStyles()),
        LocalDensity provides scaledDensity,
        com.promenar.infinity.ui.common.LocalInfinityHaptics provides haptics,
        // 长按菜单、文本选择等组件内置的触感同样服从应用开关。
        androidx.compose.ui.platform.LocalHapticFeedback provides
            if (preferences != null && !hapticsEnabled) SilentHapticFeedback else androidx.compose.ui.platform.LocalHapticFeedback.current,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = InfinityTypography,
            shapes = InfinityShapes,
        ) {
            // 默认点按反馈裁在圆角内，覆盖 MaterialTheme 提供的矩形涟漪。
            CompositionLocalProvider(
                androidx.compose.foundation.LocalIndication provides InfinityIndication,
                androidx.compose.material3.LocalRippleConfiguration provides InfinityRippleConfiguration,
                content = content,
            )
        }
    }
}

/** 应用当前的外观偏好；静态预览未传入时为 null。 */
private val LocalInfinityThemePreferences = staticCompositionLocalOf<InfinityThemePreferences?> { null }

private fun usesDynamicColor(useDynamicColor: Boolean): Boolean = useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** 按偏好生成配色：系统取色、种子色（预设或自定义）或内置淡紫；深色且开启纯黑时背景与表面换成纯黑。 */
@Composable
private fun infinityColorScheme(preferences: InfinityThemePreferences?, dark: Boolean, useDynamicColor: Boolean): ColorScheme {
    val seedColor = preferences?.primaryColor?.let { Color(it.toULong()) }
    return when {
        usesDynamicColor(useDynamicColor) && dark -> dynamicDarkColorScheme(LocalContext.current)
        usesDynamicColor(useDynamicColor) -> androidx.compose.material3.dynamicLightColorScheme(LocalContext.current)
        seedColor != null -> seededColorScheme(seedColor, dark, preferences?.schemeStyle ?: InfinitySchemeStyle.TONAL_SPOT)
        // 内置配色选了非柔和的配色风格时，以内置淡紫的种子按该风格生成。
        preferences != null && preferences.schemeStyle != InfinitySchemeStyle.TONAL_SPOT ->
            seededColorScheme(InfinityIndigoSeed, dark, preferences.schemeStyle)
        dark -> InfinityDarkColorScheme
        else -> InfinityLightColorScheme
    }.withPureBlackSurface(dark && preferences?.pureBlack == true)
}

/**
 * 固定深色加纯黑背景的外观，首次引导用：不管用户选的是浅色还是深色都一样，主色跟随当前主题色（系统取色、预设或自定义色）。
 * 只在这一块内容里换配色，系统栏图标在显示期间切成浅色，离开后恢复。
 */
@Composable
fun InfinityPureDarkTheme(content: @Composable () -> Unit) {
    val preferences = LocalInfinityThemePreferences.current
    val dynamic = preferences?.colorSource == InfinityColorSource.DYNAMIC
    val colorScheme = infinityColorScheme(preferences, dark = true, useDynamicColor = dynamic).withPureBlackSurface(true)
    val domainColors = remember(colorScheme, dynamic) {
        if (usesDynamicColor(dynamic)) dynamicDomainColors(colorScheme, true) else InfinityDarkDomainColors
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.DisposableEffect(view) {
            val controller = WindowCompat.getInsetsController((view.context as Activity).window, view)
            val lightStatus = controller.isAppearanceLightStatusBars
            val lightNavigation = controller.isAppearanceLightNavigationBars
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            onDispose {
                controller.isAppearanceLightStatusBars = lightStatus
                controller.isAppearanceLightNavigationBars = lightNavigation
            }
        }
    }
    CompositionLocalProvider(LocalInfinityDomainColors provides domainColors) {
        MaterialTheme(colorScheme = colorScheme, typography = InfinityTypography, shapes = InfinityShapes) {
            CompositionLocalProvider(
                androidx.compose.foundation.LocalIndication provides InfinityIndication,
                androidx.compose.material3.LocalRippleConfiguration provides InfinityRippleConfiguration,
                content = content,
            )
        }
    }
}

private object SilentHapticFeedback : androidx.compose.ui.hapticfeedback.HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: androidx.compose.ui.hapticfeedback.HapticFeedbackType) = Unit
}
