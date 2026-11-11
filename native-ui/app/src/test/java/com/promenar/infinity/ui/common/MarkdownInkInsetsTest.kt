package com.promenar.infinity.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownInkInsetsTest {
    @Test
    fun `首末字形外留白被裁去而中间基线跨度保持`() {
        assertEquals(ReadingInkInsets(8, 7), readingInkInsets(72, 8.7f, 64.2f))
        assertEquals(57, 72 - 8 - 7)
    }

    @Test
    fun `向外取整保留抗锯齿边缘且不产生负高度`() {
        assertEquals(ReadingInkInsets(0, 0), readingInkInsets(24, -0.2f, 24.2f))
        assertEquals(ReadingInkInsets(24, 0), readingInkInsets(24, 30f, 40f))
    }

    /** 模拟固定行高、首末行裁去行距的代码正文：首行基线固定，每多一行末行基线下移一个行高。 */
    private fun codeVisibleHeight(lines: Int): Int {
        val lineHeight = 40
        val trimTop = 6
        val trimBottom = 4
        val height = lines * lineHeight - trimTop - trimBottom
        val firstBaseline = 30 - trimTop
        val lastBaseline = firstBaseline + (lines - 1) * lineHeight
        val insets = stableCodeInkInsets(height, firstBaseline, lastBaseline, referenceInkTop = -20, referenceInkBottom = 5)
        return height - insets.top - insets.bottom
    }

    @Test
    fun `代码正文每多一行只增加一个行高`() {
        assertEquals(25, codeVisibleHeight(1))
        for (lines in 1 until 40) {
            assertEquals(40, codeVisibleHeight(lines + 1) - codeVisibleHeight(lines))
        }
    }

    @Test
    fun `参照外沿超出行框时裁切归零而高度不为负`() {
        assertEquals(ReadingInkInsets(0, 0), stableCodeInkInsets(26, 24, 24, referenceInkTop = -30, referenceInkBottom = 9))
    }
}
