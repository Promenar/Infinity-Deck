package com.promenar.infinity.data.document

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarkdownChunksTest {
    @Test
    fun `不超过整篇上限时只有一块`() {
        val text = "# 标题\n\n正文"
        assertThat(MarkdownChunks.split(text, wholeLimit = 100, target = 4)).containsExactly(text)
    }

    @Test
    fun `分块连接后与原文一致 且在标题或空行后切开`() {
        val sections = (1..30).joinToString("\n") { index -> "## 第 $index 节\n\n第 $index 段正文，内容足够长以便分块。\n" }
        val chunks = MarkdownChunks.split(sections, wholeLimit = 0, target = 80)

        assertThat(chunks.size).isGreaterThan(1)
        assertThat(chunks.joinToString("\n")).isEqualTo(sections)
        chunks.drop(1).forEach { chunk ->
            val first = chunk.lineSequence().first()
            assertThat(first.startsWith("## ") || first.isNotBlank()).isTrue()
        }
    }

    @Test
    fun `不在围栏代码块内部切开`() {
        val code = (1..200).joinToString("\n") { "line $it" }
        val text = "# 开头\n\n" + "前文。\n\n".repeat(20) + "```kotlin\n$code\n\n# 不是标题\n\n```\n\n# 后面\n\n结尾"
        val chunks = MarkdownChunks.split(text, wholeLimit = 0, target = 32)

        assertThat(chunks.joinToString("\n")).isEqualTo(text)
        // 围栏开头和结尾必须在同一块里。
        val fenced = chunks.single { it.contains("```kotlin") }
        assertThat(fenced).contains("line 200")
        assertThat(fenced.split("```").size).isEqualTo(3)
    }

    @Test
    fun `波浪线围栏与更长的闭合标记`() {
        val text = "~~~~\n# 内部\n\n~~~\n仍在内部\n~~~~~\n\n# 外部\n\n正文"
        val chunks = MarkdownChunks.split(text, wholeLimit = 0, target = 1)
        assertThat(chunks.joinToString("\n")).isEqualTo(text)
        assertThat(chunks.first()).contains("仍在内部")
        assertThat(chunks.first()).doesNotContain("# 外部")
    }

    @Test
    fun `没有块级边界的长段落也会被强制切开`() {
        val text = (1..400).joinToString("\n") { "连续的一行 $it" }
        val chunks = MarkdownChunks.split(text, wholeLimit = 0, target = 100)
        assertThat(chunks.size).isGreaterThan(1)
        assertThat(chunks.joinToString("\n")).isEqualTo(text)
        chunks.dropLast(1).forEach { assertThat(it.length).isLessThan(100 * 4 + 32) }
    }

    @Test
    fun `末尾换行与连续空行保持不变`() {
        val text = "# a\n\n\n\n# b\n\ntext\n"
        assertThat(MarkdownChunks.split(text, wholeLimit = 0, target = 1).joinToString("\n")).isEqualTo(text)
    }
}
