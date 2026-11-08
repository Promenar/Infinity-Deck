package com.promenar.infinity.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 流式新字的注解样式：只作用于新放出的区间，颜色从强调色按比例退回正文色，透明度按区间系数叠加。 */
class StreamRevealTintTest {
    private val text = Color(0xFFEEEEEE)
    private val accent = Color(0xFF8070FF)

    private fun annotate(content: String, ranges: List<ReadingRevealRange>): AnnotatedString {
        val paragraph = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
            .children.single { it.type == MarkdownElementTypes.PARAGRAPH }
        val annotator = readingMarkdownAnnotator(SpanStyle(), text, ranges, 0, accent)
        val settings = DefaultAnnotatorSettings(TextLinkStyles(), SpanStyle(), annotator, highlightSpanStyle = SpanStyle())
        return content.buildMarkdownAnnotatedString(paragraph, TextStyle.Default, settings)
    }

    private fun AnnotatedString.colorAt(index: Int): Color? =
        spanStyles.lastOrNull { index in it.start until it.end && it.item.color != Color.Unspecified }?.item?.color

    @Test
    fun `刚出现的字是低透明度的强调色`() {
        val annotated = annotate("旧字新字", listOf(ReadingRevealRange(2, 4, alpha = 0.2f, tint = 1f)))
        assertEquals(null, annotated.colorAt(0))
        val color = requireNotNull(annotated.colorAt(2))
        assertEquals(accent.red, color.red, 0.01f)
        assertEquals(accent.blue, color.blue, 0.01f)
        assertEquals(0.2f, color.alpha, 0.01f)
    }

    @Test
    fun `渐变途中颜色介于强调色与正文色之间`() {
        val annotated = annotate("新字", listOf(ReadingRevealRange(0, 2, alpha = 0.9f, tint = 0.5f)))
        val color = requireNotNull(annotated.colorAt(0))
        // 插值在感知均匀色彩空间进行，只要求各通道严格落在两端之间。
        assertTrue(color.red > accent.red + 0.05f && color.red < text.red - 0.05f)
        assertTrue(color.green > accent.green + 0.05f && color.green < text.green - 0.05f)
        assertEquals(0.9f, color.alpha, 0.01f)
    }

    @Test
    fun `渐变结束时与正文色一致`() {
        val annotated = annotate("新字", listOf(ReadingRevealRange(0, 2, alpha = 1f, tint = 0f)))
        val color = requireNotNull(annotated.colorAt(0))
        assertEquals(text.red, color.red, 0.01f)
        assertEquals(text.green, color.green, 0.01f)
        assertEquals(1f, color.alpha, 0.01f)
    }
}
