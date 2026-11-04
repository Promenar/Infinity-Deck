package com.promenar.infinity.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MarkdownStreamingSupportTest {
    @Test
    fun `代码增长只测量新增或改变的行且最大宽度随删除收缩`() {
        val cache = LogicalLineWidthCache()
        val measured = mutableListOf<String>()
        val measure: (String) -> Int = { measured += it; it.length * 10 }

        assertEquals(60, cache.widestLogicalLineWidthPx("stable\ntail", measure))
        assertEquals(80, cache.widestLogicalLineWidthPx("stable\ntail-add\nnew", measure))
        assertEquals(listOf("stable", "tail", "tail-add", "new"), measured)
        assertEquals(30, cache.widestLogicalLineWidthPx("new", measure))
        assertEquals(listOf("stable", "tail", "tail-add", "new"), measured)
    }

    @Test
    fun `相同内容的多个逻辑行共用测量且空行与emoji保留`() {
        val cache = LogicalLineWidthCache()
        val measured = mutableListOf<String>()
        val widths = mapOf("same" to 30, "" to 0, "🚀" to 40)

        assertEquals(40, cache.widestLogicalLineWidthPx("same\n\nsame\n🚀") { measured += it; widths.getValue(it) })
        assertEquals(listOf("same", "", "🚀"), measured)
    }

    @Test
    fun `高亮保留已覆盖前缀而新增尾部立即显示为未着色文本`() {
        val red = SpanStyle(color = Color.Red)
        val highlighted = AnnotatedString("val a", listOf(AnnotatedString.Range(red, 0, 3)))

        val result = highlightedCodeWithUnstyledTail("val a = 12", highlighted)

        assertEquals("val a = 12", result.text)
        assertEquals(listOf(AnnotatedString.Range(red, 0, 3)), result.spanStyles)
    }

    @Test
    fun `代码改写只保留公共前缀的高亮且不会保留旧尾部`() {
        val red = SpanStyle(color = Color.Red)
        val highlighted = AnnotatedString("val old", listOf(AnnotatedString.Range(red, 0, 7)))

        val result = highlightedCodeWithUnstyledTail("val new", highlighted)

        assertEquals("val new", result.text)
        assertEquals(listOf(AnnotatedString.Range(red, 0, 4)), result.spanStyles)
    }

    @Test
    fun `流式高亮启动间隔至少250毫秒且等待期间只处理最新内容`() = runTest {
        val requests = MutableStateFlow(CodeHighlightRequest("a", true))
        val starts = mutableListOf<Pair<String, Long>>()
        val completed = mutableListOf<String>()
        backgroundScope.launch {
            collectCodeHighlights(requests, { testScheduler.currentTime }, { code ->
                starts += code to testScheduler.currentTime
                delay(1)
                AnnotatedString(code)
            }) { completed += it.text }
        }
        runCurrent()
        advanceTimeBy(10)
        requests.value = CodeHighlightRequest("ab", true)
        runCurrent()
        advanceTimeBy(10)
        requests.value = CodeHighlightRequest("abc", true)
        runCurrent()
        advanceTimeBy(229)
        runCurrent()
        assertEquals(listOf("a" to 0L), starts)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("a" to 0L, "abc" to 250L), starts)
        advanceTimeBy(10)
        requests.value = CodeHighlightRequest("abcd", false)
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        assertEquals("abcd", completed.last())
        assertEquals("abcd" to 260L, starts.last())
    }

    @Test
    fun `增长不取消超过一帧的高亮而流结束会最终高亮最新全文`() = runTest {
        val requests = MutableStateFlow(CodeHighlightRequest("a", true))
        val starts = mutableListOf<String>()
        val completed = mutableListOf<String>()
        var cancelled = false
        var running = 0
        var maximumRunning = 0
        backgroundScope.launch {
            collectCodeHighlights(requests, { testScheduler.currentTime }, { code ->
                starts += code
                running++
                maximumRunning = maxOf(maximumRunning, running)
                try {
                    delay(400)
                    AnnotatedString(code)
                } catch (failure: kotlinx.coroutines.CancellationException) {
                    cancelled = true
                    throw failure
                } finally {
                    running--
                }
            }) { completed += it.text }
        }
        runCurrent()
        advanceTimeBy(100)
        requests.value = CodeHighlightRequest("ab", true)
        runCurrent()
        advanceTimeBy(100)
        requests.value = CodeHighlightRequest("abc", true)
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        assertFalse("增量不能取消正在执行的高亮", cancelled)
        assertEquals(listOf("a"), completed)
        assertEquals(listOf("a", "abc"), starts)

        advanceTimeBy(50)
        requests.value = CodeHighlightRequest("abcd", false)
        runCurrent()
        advanceTimeBy(750)
        runCurrent()
        assertEquals(listOf("a", "abc", "abcd"), completed)
        assertEquals(1, maximumRunning)
    }

    @Test
    fun `全文已可见时临时围栏和粗体尾标记全部进入解析内容`() {
        for ((source, rendered) in listOf("**甲" to "**甲**", "```kotlin\nval a" to "```kotlin\nval a\n```")) {
            val prepared = PreparedMarkdownContent(source, rendered)
            assertEquals(rendered, prepared.reveal(StreamRevealFrame(source, 0L)).text)
        }
    }

    @Test
    fun `原文边界与渐显范围映射到补空白后的完整AST位置`() {
        val source = "#标题\n\n\n**甲"
        val prepared = PreparedMarkdownContent(source, "# 标题\n\n**甲**")
        val frame = prepared.reveal(StreamRevealFrame("#标题", 10L, listOf(StreamRevealRange(1, 3, 0L))))

        assertEquals("# 标题", frame.text)
        assertEquals(listOf(StreamRevealRange(2, 4, 0L)), frame.recentRanges)
        assertEquals(10L, frame.nowNanos)
    }

    @Test
    fun `LaTeX分隔符规范化后正文渐显仍使用原始边界`() {
        val prepared = PreparedMarkdownContent("前\\[x\\]后", "前\$\$\nx\n\$\$后")
        val frame = prepared.reveal(StreamRevealFrame("前\\[x", 10L, listOf(StreamRevealRange(3, 4, 0L))))

        assertEquals("前\$\$\nx", frame.text)
        assertEquals(listOf(StreamRevealRange(4, 5, 0L)), frame.recentRanges)
        assertTrue(prepared.text.startsWith(frame.text))
    }

    @Test
    fun `流式预处理删去首尾空白并折叠CRLF时保持原文位置`() {
        val source = "\r\n  #标题\r\n\r\n\r\n**甲"
        val prepared = PreparedMarkdownContent(source, prepareMarkdownForRendering(source, true))
        val visible = source.take(source.indexOf("题") + 1)
        val frame = prepared.reveal(StreamRevealFrame(visible, 10L))

        assertEquals("# 标题", frame.text)
        assertEquals("# 标题\n\n**甲**", prepared.text)
        assertEquals(prepared.text, prepared.reveal(StreamRevealFrame(source, 20L)).text)
    }

    @Test
    fun `中文标点处补入零宽空格后正文渐显仍使用原始边界`() {
        val source = "说法：**收件人是神仙。**至于灵不灵"
        val prepared = PreparedMarkdownContent(source, prepareMarkdownForRendering(source, true))
        val visible = source.take(source.indexOf("至") + 1)
        val frame = prepared.reveal(StreamRevealFrame(visible, 10L))

        assertEquals("说法：**收件人是神仙。​**至", frame.text)
        assertEquals(prepared.text, prepared.reveal(StreamRevealFrame(source, 20L)).text)
    }

    @Test
    fun `嵌套引用中的代码可见边界保持代码正文与缩进`() {
        val source = "> ```kotlin\n> val a = 1\n> val b = 2\n> ```"
        val prepared = PreparedMarkdownContent(source, prepareMarkdownForRendering(source, true))
        val visible = source.take(source.indexOf("\n> val b"))

        assertEquals(visible, prepared.reveal(StreamRevealFrame(visible, 10L)).text)
        assertEquals(source, prepared.reveal(StreamRevealFrame(source, 20L)).text)
    }

    @Test
    fun `新原文改短时将上一帧限制在公共前缀而不越界或闪出新全文`() {
        val prepared = PreparedMarkdownContent("共同新", "共同新")

        assertEquals("共同", prepared.reveal(StreamRevealFrame("共同旧文字很长", 10L)).text)
        assertEquals("", PreparedMarkdownContent("新", "新").reveal(StreamRevealFrame("旧", 10L)).text)
    }

    @Test
    fun `未闭合粗体与围栏持续增长时采样只根据原始累计文本`() {
        for (opening in listOf("**", "```kotlin\n")) {
            val revealer = StreamRevealer()
            revealer.updateTarget(opening, true, 0L)
            var raw = opening
            for (index in 1..3) {
                raw += "甲".repeat(index * 10)
                val frame = revealer.updateTarget(raw, true, index * 100_000_000L)
                val prepared = PreparedMarkdownContent(raw, prepareMarkdownForRendering(raw, true))
                assertTrue(prepared.text.startsWith(prepared.reveal(frame).text))
            }
            assertEquals(181.0, revealer.estimatedRate, 0.0001)
        }
    }

    @Test
    fun `压缩表格补齐行末管道后仍逐步显示后续正文`() {
        val source = "|A|B||---|---||1|2\n继续增长中的正文"
        val prepared = PreparedMarkdownContent(source, prepareMarkdownForRendering(source, true))
        val visible = source.take(source.indexOf("增长") + 2)

        assertEquals("|A|B|\n|---|---|\n|1|2|\n继续增长中的正文", prepared.text)
        assertEquals("|A|B|\n|---|---|\n|1|2|\n继续增长", prepared.reveal(StreamRevealFrame(visible, 10L)).text)
    }
}
