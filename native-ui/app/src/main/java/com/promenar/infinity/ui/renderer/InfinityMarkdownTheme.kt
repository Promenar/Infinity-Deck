package com.promenar.infinity.ui.renderer

import com.promenar.infinity.ui.theme.InfinityMonospace
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownTypography
import com.promenar.infinity.ui.theme.InfinityInputLineBreak
import com.promenar.infinity.ui.theme.InfinityReadingLineBreak
import com.promenar.infinity.ui.theme.InfinityTypography

@Composable
fun infinityMarkdownColors(
    textColor: Color = MaterialTheme.colorScheme.onSurface
): MarkdownColors = markdownColor(
    text = textColor,
    codeBackground = MaterialTheme.colorScheme.surfaceContainerLow,
    inlineCodeBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
    dividerColor = MaterialTheme.colorScheme.outlineVariant,
    tableBackground = MaterialTheme.colorScheme.surfaceContainer,
)

internal fun infinityMarkdownBodyStyle(baseFontSize: Int, fontStyle: FontStyle? = null): TextStyle =
    InfinityTypography.bodyMedium.copy(
        fontSize = baseFontSize.sp,
        lineHeight = (baseFontSize * 1.6).sp,
        letterSpacing = 0.01.em,
        fontStyle = fontStyle,
        lineBreak = InfinityReadingLineBreak,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    )

internal fun infinityMarkdownHeadingStyle(level: Int, baseFontSize: Int, fontStyle: FontStyle? = null): TextStyle {
    val multiplier = when (level) { 1 -> 1.35; 2 -> 1.2; 3 -> 1.1; else -> 1.0 }
    val size = (baseFontSize * multiplier).sp
    return InfinityTypography.titleMedium.copy(
        fontSize = size,
        fontWeight = FontWeight.SemiBold,
        lineHeight = (size.value * 1.35).sp,
        fontStyle = fontStyle,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    )
}

internal fun infinityMarkdownCodeStyle(fontStyle: FontStyle? = null): TextStyle =
    InfinityTypography.bodySmall.copy(
        fontSize = 13.sp,
        fontFamily = InfinityMonospace,
        lineHeight = 20.sp,
        fontStyle = fontStyle,
        lineBreak = InfinityInputLineBreak,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    )

@Composable
fun infinityMarkdownTypography(
    baseFontSize: Int = 15,
    fontStyle: FontStyle? = null,
    headingColor: Color = Color.Unspecified,
): MarkdownTypography {
    val body = infinityMarkdownBodyStyle(baseFontSize, fontStyle)
    fun heading(level: Int) = infinityMarkdownHeadingStyle(level, baseFontSize, fontStyle).copy(color = headingColor)
    return markdownTypography(
        h1 = heading(1),
        h2 = heading(2),
        h3 = heading(3),
        h4 = heading(4),
        h5 = heading(5),
        h6 = heading(6),
        text = body,
        code = infinityMarkdownCodeStyle(fontStyle),
        inlineCode = body.copy(fontFamily = InfinityMonospace),
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        quote = body,
    )
}

fun infinitySyntaxTheme(isDark: Boolean): dev.snipme.highlights.model.SyntaxTheme {
    val base = dev.snipme.highlights.model.SyntaxThemes.atom(isDark)
    return if (isDark) {
        base.copy(punctuation = 0xFFCBD5E1.toInt())
    } else {
        base
    }
}
