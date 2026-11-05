package com.promenar.infinity.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentLocateTest {
    private val body = "# 标题\n\n第一段。\n\n第二段包含离线队列。\n\n第三段。"

    @Test
    fun `命中区间扩展到所在段落`() {
        val start = body.indexOf("离线队列")
        val (from, to) = paragraphBounds(body, start until start + 4)
        assertEquals("第二段包含离线队列。", body.substring(from, to))
    }

    @Test
    fun `首段与末段命中扩展到正文首尾`() {
        assertEquals("# 标题", body.substring(0, paragraphBounds(body, 2..3).second))
        val last = body.indexOf("第三段")
        val (from, to) = paragraphBounds(body, last..last)
        assertEquals("第三段。", body.substring(from, to))
    }

    @Test
    fun `切成普通段与命中段交替`() {
        val start = body.indexOf("第二段")
        val located = locateBody(body, listOf(start..start + 2), markdown = true)!!
        assertEquals(1, located.hitCount)
        assertFalse(located.plainText)
        assertEquals(listOf(null, 0, null), located.segments.map { it.hitOrdinal })
        assertEquals("# 标题\n\n第一段。", located.segments[0].text)
        assertEquals("第二段包含离线队列。", located.segments[1].text)
        assertEquals("第三段。", located.segments[2].text)
    }

    @Test
    fun `同段两处命中合并且多段按位置编号`() {
        val a = body.indexOf("第三段")
        val b = body.indexOf("第一段")
        val c = body.indexOf("第一段") + 2
        val located = locateBody(body, listOf(a..a, b..b, c..c), markdown = false)!!
        assertEquals(2, located.hitCount)
        val hits = located.segments.filter { it.hitOrdinal != null }
        assertEquals(listOf("第一段。", "第三段。"), hits.map { it.text })
        assertEquals(listOf(0, 1), hits.map { it.hitOrdinal })
    }

    @Test
    fun `相邻段落各算一处命中，中间不留空段`() {
        val a = body.indexOf("第一段")
        val b = body.indexOf("第二段")
        val located = locateBody(body, listOf(a..a, b..b), markdown = true)!!
        assertEquals(2, located.hitCount)
        assertEquals(listOf(null, 0, 1, null), located.segments.map { it.hitOrdinal })
        assertEquals("第二段包含离线队列。", located.segments[2].text)
    }

    @Test
    fun `切点落在代码围栏内时改用纯文本`() {
        val code = "说明\n\n```\nval a = 1\n\nval b = 2\n```\n\n结尾"
        val hit = code.indexOf("val b")
        val located = locateBody(code, listOf(hit..hit + 4), markdown = true)!!
        assertTrue(located.plainText)
        assertFalse(locateBody(code, listOf(hit..hit + 4), markdown = false)!!.plainText)
    }

    @Test
    fun `没有有效区间返回空`() {
        assertNull(locateBody(body, emptyList(), markdown = true))
        assertNull(locateBody(body, listOf(IntRange.EMPTY, 999..1000), markdown = true))
    }
    @Test
    fun `CRLF 空行也算段落边界`() {
        val crlf = "第一段内容\r\n\r\n第二段命中这里\r\n\r\n第三段"
        val start = crlf.indexOf("命中")
        val (from, to) = paragraphBounds(crlf, start until start + 2)
        assertEquals("第二段命中这里", crlf.substring(from, to).trim())
    }

    @Test
    fun `没有空行的长文只扩展到命中所在行而不是整篇`() {
        val lines = (1..400).map { "第${it}行：这是一段用于测试的较长正文内容，单换行分隔，没有空行。" }
        val long = lines.joinToString("\n")
        val target = lines[200]
        val start = long.indexOf(target)
        val (from, to) = paragraphBounds(long, start until start + target.length)
        assertEquals(target, long.substring(from, to))
        val located = locateBody(long, listOf(start until start + target.length), markdown = false)!!
        assertEquals(target, located.segments.single { it.hitOrdinal == 0 }.text)
    }
}
