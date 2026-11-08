package com.promenar.infinity.ui.linux.output

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.promenar.infinity.ui.theme.InfinityAnsiDark
import com.promenar.infinity.ui.theme.InfinityAnsiLight

/** ANSI 16 色在 Infinity Deck 深浅色下的取值；扩展色按 xterm 规则计算。 */
@Immutable
class AnsiPalette(private val base: List<Color>) {
    init {
        require(base.size == 16)
    }

    fun color(color: AnsiColor): Color = when (color) {
        is AnsiColor.Rgb -> Color(color.red, color.green, color.blue)
        is AnsiColor.Indexed -> indexed(color.index)
    }

    private fun indexed(index: Int): Color = when (index) {
        in 0..15 -> base[index]
        in 16..231 -> {
            val value = index - 16
            fun level(step: Int) = if (step == 0) 0 else 55 + step * 40
            Color(level(value / 36), level(value / 6 % 6), level(value % 6))
        }
        in 232..255 -> {
            val gray = 8 + (index - 232) * 10
            Color(gray, gray, gray)
        }
        else -> base[7]
    }

    companion object {
        /** 深色底：对齐应用强调色，黑色提亮以便可读。取值在主题 token 文件中声明。 */
        val Dark = AnsiPalette(InfinityAnsiDark)

        /** 浅色底：各色加深以保持对比度，白色映射为深灰。 */
        val Light = AnsiPalette(InfinityAnsiLight)
    }
}

/** 一行输出的着色参数；[stderrColor] 用于合并视图中没有自带前景色的 stderr 文本。 */
@Immutable
data class OutputLineColors(
    val foreground: Color,
    val stderr: Color,
    val separator: Color,
    val match: Color,
    val onMatch: Color,
    val currentMatch: Color,
    val onCurrentMatch: Color,
)

/** 构造一行的富文本：ANSI 样式、stderr 底色、分隔行与搜索高亮依次叠加，后者覆盖前者。 */
fun outputLine(
    document: OutputDocument,
    line: IntRange,
    matches: List<IntRange>,
    currentMatch: IntRange?,
    palette: AnsiPalette,
    colors: OutputLineColors,
): AnnotatedString {
    val start = line.first
    val end = line.last + 1
    val text = document.text.substring(start, end)
    return buildAnnotatedString {
        append(text)
        if (text.isEmpty()) return@buildAnnotatedString
        fun add(style: SpanStyle, range: IntRange) {
            val from = maxOf(range.first, start) - start
            val to = minOf(range.last + 1, end) - start
            if (from < to) addStyle(style, from, to)
        }
        document.stderrRange?.let { add(SpanStyle(color = colors.stderr), it) }
        document.separatorRange?.let { add(SpanStyle(color = colors.separator, fontStyle = FontStyle.Italic), it) }
        overlapping(document.ansi.spans, start, end, { it.start }, { it.end }).forEach { span ->
            add(span.style.toSpanStyle(palette, colors.foreground), span.start until span.end)
        }
        overlapping(matches, start, end, { it.first }, { it.last + 1 }).forEach { match ->
            if (match == currentMatch) {
                add(SpanStyle(background = colors.currentMatch, color = colors.onCurrentMatch, fontWeight = FontWeight.Bold), match)
            } else {
                add(SpanStyle(background = colors.match, color = colors.onMatch), match)
            }
        }
    }
}

internal fun AnsiStyle.toSpanStyle(palette: AnsiPalette, defaultForeground: Color): SpanStyle {
    var foreground = foreground?.let(palette::color)
    var background = background?.let(palette::color)
    if (inverse) {
        val swapped = foreground ?: defaultForeground
        // 反显且未指定背景时，以调色板黑色作前景，深浅色主题各自可读。
        foreground = background ?: palette.color(AnsiColor.Indexed(0))
        background = swapped
    }
    if (dim) foreground = (foreground ?: defaultForeground).copy(alpha = 0.6f)
    return SpanStyle(
        color = foreground ?: Color.Unspecified,
        background = background ?: Color.Unspecified,
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = if (underline) TextDecoration.Underline else null,
    )
}

/** 在按起点排序、互不重叠的列表中取出与 [start, end) 相交的元素。 */
internal inline fun <T> overlapping(items: List<T>, start: Int, end: Int, first: (T) -> Int, endOf: (T) -> Int): List<T> {
    if (items.isEmpty()) return emptyList()
    var low = 0
    var high = items.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (endOf(items[mid]) <= start) low = mid + 1 else high = mid
    }
    val result = ArrayList<T>()
    var index = low
    while (index < items.size && first(items[index]) < end) {
        result += items[index]
        index++
    }
    return result
}
