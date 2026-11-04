package com.promenar.infinity.ui.common

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 行内代码换行时，左右留白占位可能被断行器单独留在上一行末或推到下一行首；
 * 这些只含留白的行段不应再画出一小块空底色。
 */
class InlineCodeBackgroundSegmentsTest {
    private fun codeText(prefix: String, code: String, suffix: String): Triple<AnnotatedString, Int, Int> {
        var start = 0
        var end = 0
        val text = buildAnnotatedString {
            append(prefix)
            start = length
            appendInlineContent(INLINE_CODE_PADDING_ID)
            append(code)
            appendInlineContent(INLINE_CODE_PADDING_ID)
            end = length
            append(suffix)
        }
        return Triple(text, start, end)
    }

    private fun segments(text: AnnotatedString, start: Int, end: Int, vararg lineEnds: Int): List<InlineCodeSegment> {
        val starts = listOf(0) + lineEnds.dropLast(1)
        return inlineCodeBackgroundSegments(
            text = text, start = start, end = end,
            firstLine = starts.indexOfLast { it <= start }, lastLine = starts.indexOfLast { it <= end },
            lineStart = { starts[it] }, lineEnd = { lineEnds[it] },
        )
    }

    @Test
    fun `左留白单独留在上一行末时只画下一行的代码段`() {
        val (text, start, end) = codeText("正文", "comfyui_host.py", "，符合")
        // 第一行在左留白之后断开：正文 + 留白。
        val segments = segments(text, start, end, start + 1, text.length)
        assertEquals(listOf(InlineCodeSegment(line = 1, start = start + 1, end = end)), segments)
    }

    @Test
    fun `右留白被推到下一行首时只画上一行的代码段`() {
        val (text, start, end) = codeText("正文", "__pycache__", "消失后")
        val breakAt = end - 1
        val segments = segments(text, start, end, breakAt, text.length)
        assertEquals(listOf(InlineCodeSegment(line = 0, start = start, end = breakAt)), segments)
    }

    @Test
    fun `代码本身跨行时两段都保留`() {
        val (text, start, end) = codeText("正文", "comfyui-generation", "源")
        val breakAt = start + 8
        val segments = segments(text, start, end, breakAt, text.length)
        assertEquals(
            listOf(InlineCodeSegment(0, start, breakAt), InlineCodeSegment(1, breakAt, end)),
            segments,
        )
    }

    @Test
    fun `同一行内的行内代码保持单段`() {
        val (text, start, end) = codeText("正文", "Qwen21_00001_.png", "，")
        assertEquals(listOf(InlineCodeSegment(0, start, end)), segments(text, start, end, text.length))
    }

    @Test
    fun `结束位置恰在下一行首时不产生空段`() {
        val (text, start, end) = codeText("正文", "code", "")
        // 末尾留白后紧跟换行：end 等于第二行起点。
        val segments = segments(text, start, end, end, end)
        assertEquals(listOf(InlineCodeSegment(0, start, end)), segments)
    }
}
