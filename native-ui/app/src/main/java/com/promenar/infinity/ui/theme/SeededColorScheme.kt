package com.promenar.infinity.ui.theme

import android.annotation.SuppressLint
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.DynamicScheme
import com.google.android.material.color.utilities.SchemeExpressive
import com.google.android.material.color.utilities.SchemeFidelity
import com.google.android.material.color.utilities.SchemeTonalSpot
import com.google.android.material.color.utilities.SchemeVibrant

/** 使用 Google Material Color Utilities 按 [style] 生成与 Material 3 同源的色板，缺省为 Tonal Spot；[InfinitySchemeStyle.BRIGHT] 锁定原色相并提升深浅色通透活力。 */
// Material Color Utilities 官方 Java 指南直接使用这些 API；Android artifact 的
// library-group 注解并不代表运行时不稳定，因此在这一个适配边界内集中抑制。
@SuppressLint("RestrictedApi")
internal fun seededColorScheme(seed: Color, dark: Boolean, style: InfinitySchemeStyle = InfinitySchemeStyle.TONAL_SPOT): ColorScheme {
    val hct = Hct.fromInt(seed.toArgb())
    if (style == InfinitySchemeStyle.BRIGHT) {
        return brightColorScheme(seed, hct, dark)
    }
    val scheme: DynamicScheme = when (style) {
        InfinitySchemeStyle.TONAL_SPOT -> SchemeTonalSpot(hct, dark, 0.0)
        InfinitySchemeStyle.VIBRANT -> SchemeVibrant(hct, dark, 0.0)
        InfinitySchemeStyle.FIDELITY -> SchemeFidelity(hct, dark, 0.0)
        InfinitySchemeStyle.EXPRESSIVE -> SchemeExpressive(hct, dark, 0.0)
        InfinitySchemeStyle.BRIGHT -> SchemeFidelity(hct, dark, 0.0)
    }
    val base = if (dark) InfinityDarkColorScheme else InfinityLightColorScheme
    fun Int.composeColor() = Color(this)

    return base.copy(
        primary = scheme.primary.composeColor(),
        onPrimary = scheme.onPrimary.composeColor(),
        primaryContainer = scheme.primaryContainer.composeColor(),
        onPrimaryContainer = scheme.onPrimaryContainer.composeColor(),
        inversePrimary = scheme.inversePrimary.composeColor(),
        secondary = scheme.secondary.composeColor(),
        onSecondary = scheme.onSecondary.composeColor(),
        secondaryContainer = scheme.secondaryContainer.composeColor(),
        onSecondaryContainer = scheme.onSecondaryContainer.composeColor(),
        tertiary = scheme.tertiary.composeColor(),
        onTertiary = scheme.onTertiary.composeColor(),
        tertiaryContainer = scheme.tertiaryContainer.composeColor(),
        onTertiaryContainer = scheme.onTertiaryContainer.composeColor(),
        background = scheme.background.composeColor(),
        onBackground = scheme.onBackground.composeColor(),
        surface = scheme.surface.composeColor(),
        onSurface = scheme.onSurface.composeColor(),
        surfaceVariant = scheme.surfaceVariant.composeColor(),
        onSurfaceVariant = scheme.onSurfaceVariant.composeColor(),
        outline = scheme.outline.composeColor(),
        outlineVariant = scheme.outlineVariant.composeColor(),
        error = scheme.error.composeColor(),
        onError = scheme.onError.composeColor(),
        errorContainer = scheme.errorContainer.composeColor(),
        onErrorContainer = scheme.onErrorContainer.composeColor(),
        surfaceTint = scheme.primary.composeColor(),
        inverseSurface = scheme.inverseSurface.composeColor(),
        inverseOnSurface = scheme.inverseOnSurface.composeColor(),
        surfaceDim = scheme.surfaceDim.composeColor(),
        surfaceBright = scheme.surfaceBright.composeColor(),
        surfaceContainerLowest = scheme.surfaceContainerLowest.composeColor(),
        surfaceContainerLow = scheme.surfaceContainerLow.composeColor(),
        surfaceContainer = scheme.surfaceContainer.composeColor(),
        surfaceContainerHigh = scheme.surfaceContainerHigh.composeColor(),
        surfaceContainerHighest = scheme.surfaceContainerHighest.composeColor(),
    )
}

/**
 * 「明亮」配色风格：严格锁定种子原色相（不偏移），在深色模式下将主强调色推入通透高饱和中亮区，
 * 在浅色模式下保持清透高饱和亮色，并按实际亮度自适应高对比度前景；近中性灰种子保持低彩度，避免凭空偏色。
 */
@SuppressLint("RestrictedApi")
private fun brightColorScheme(seed: Color, hct: Hct, dark: Boolean): ColorScheme {
    val base = seededColorScheme(seed, dark, InfinitySchemeStyle.FIDELITY)
    val hue = hct.hue
    val neutralSeed = hct.chroma < 8.0
    val chroma = if (neutralSeed) hct.chroma else hct.chroma.coerceAtLeast(64.0)
    return if (dark) {
        val primaryTone = if (hue in 55.0..165.0) 64.0 else 58.0
        val primaryChroma = if (neutralSeed) chroma else chroma.coerceAtLeast(74.0)
        val primary = Color(Hct.from(hue, primaryChroma, primaryTone).toInt())
        val onPrimary = if (primary.luminance() > 0.34f) {
            Color(Hct.from(hue, if (neutralSeed) chroma else 36.0, 10.0).toInt())
        } else {
            InfinityPureWhite
        }
        val primaryContainer = Color(Hct.from(hue, if (neutralSeed) chroma else (chroma * 0.92).coerceAtLeast(62.0), 42.0).toInt())
        val onPrimaryContainer = Color(Hct.from(hue, if (neutralSeed) chroma else 20.0, 96.0).toInt())
        val secondary = Color(Hct.from(hue, if (neutralSeed) chroma else (chroma * 0.55).coerceAtLeast(36.0), 72.0).toInt())
        val tertiary = Color(Hct.from((hue + 28.0) % 360.0, if (neutralSeed) chroma else (chroma * 0.85).coerceAtLeast(56.0), 66.0).toInt())
        base.copy(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            tertiary = tertiary,
            surfaceTint = primary,
        )
    } else {
        val primaryTone = if (hue in 55.0..155.0) 44.0 else 48.0
        val primaryChroma = if (neutralSeed) chroma else chroma.coerceAtLeast(78.0)
        val primary = Color(Hct.from(hue, primaryChroma, primaryTone).toInt())
        val onPrimary = InfinityPureWhite
        val primaryContainer = Color(Hct.from(hue, if (neutralSeed) chroma else (chroma * 0.68).coerceAtLeast(48.0), 87.0).toInt())
        val onPrimaryContainer = Color(Hct.from(hue, if (neutralSeed) chroma else 70.0, 20.0).toInt())
        val tertiary = Color(Hct.from((hue + 28.0) % 360.0, if (neutralSeed) chroma else (chroma * 0.82).coerceAtLeast(54.0), 46.0).toInt())
        base.copy(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            tertiary = tertiary,
            surfaceTint = primary,
        )
    }
}

internal fun ColorScheme.withPureBlackSurface(enabled: Boolean): ColorScheme =
    if (!enabled) this else copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
    )
