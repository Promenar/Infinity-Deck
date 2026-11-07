package com.promenar.infinity.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import com.google.common.truth.Truth.assertThat
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.jupiter.api.Test

/** 数学定界符识别必须把代码与链接当作字面量保护区，同时不误伤正常公式与货币金额。 */
class MarkdownMathDelimiterTest {

    private fun latexContents(text: String): List<String> =
        splitRichSegments(text).mapNotNull {
            when (it) {
                is ContentSegment.InlineLatex -> it.content
                is ContentSegment.Latex -> it.content
                else -> null
            }
        }

    private fun renderedLatex(text: String): List<String> = latexContents(prepareMarkdownForRendering(text))

    /** 按正文注解器把单个段落转换为最终显示文本。 */
    private fun annotatedParagraph(content: String): String {
        val paragraph = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
            .children.single { it.type == MarkdownElementTypes.PARAGRAPH }
        val annotator = readingMarkdownAnnotator(SpanStyle(), Color.White, emptyList(), 0)
        val settings = DefaultAnnotatorSettings(TextLinkStyles(), SpanStyle(), annotator, highlightSpanStyle = SpanStyle())
        return content.buildMarkdownAnnotatedString(paragraph, TextStyle.Default, settings).text
    }

    @Test
    fun `真机复现 两个行内代码之间的美元符号不构成公式`() {
        val input = "**3. 克隆 `\$HOME/src-demo` → `\$INFINITY_SKILLS/cloned-demo`** ✅"

        assertThat(latexContents(input)).isEmpty()
        assertThat(splitRichSegments(input)).containsExactly(ContentSegment.Markdown(input))
        assertThat(prepareMarkdownForRendering(input)).isEqualTo(input)
        assertThat(renderedLatex(input)).isEmpty()
    }

    @Test
    fun `列表与多行变体中的行内代码变量不构成公式`() {
        val inputs = listOf(
            "1. 克隆 `\$HOME/src-demo` → `\$INFINITY_SKILLS/cloned-demo`",
            "- 设置 `\$JAVA_HOME` 与 `\$ANDROID_HOME` 后重试",
            "运行 `echo \$PATH`，再查看 `\$SHELL` 的值。",
            "路径 `\$HOME`\n与 `\$TMPDIR` 分属两行",
            "双反引号 ``a \$b` c`` 与 `\$d` 并列",
            "只有一个 `\$VAR` 和一个价格 \$5",
        )
        for (input in inputs) {
            assertThat(renderedLatex(input)).isEmpty()
        }
    }

    @Test
    fun `围栏与缩进代码块中的美元符号不构成公式`() {
        val fenced = "示例：\n\n```bash\ncp \$SRC/\$DST\nexport A=\$\$\n```\n\n结束 \$\$"
        val indented = "示例：\n\n    cp \$SRC/\$DST\n\n结束"

        assertThat(renderedLatex(fenced)).isEmpty()
        assertThat(renderedLatex(indented)).isEmpty()
    }

    @Test
    fun `链接地址中的美元符号不构成公式`() {
        val input = "见 [文档](https://example.com/\$a\$/b) 与 <https://example.com/\$x\$>"
        assertThat(renderedLatex(input)).isEmpty()
    }

    @Test
    fun `行内代码之外的正常公式仍然识别`() {
        assertThat(renderedLatex("变量 `\$HOME` 与公式 \$x^2\$ 并存")).containsExactly("x^2")
        // 首个候选与代码重叠时必须从后续位置重新查找，不能吞掉紧邻的公式。
        assertThat(renderedLatex("`\$a`\$b\$")).containsExactly("b")
        assertThat(renderedLatex("块公式：\n\n\$\$\n\\int_0^1 x\\,dx\n\$\$\n\n代码 `echo \$\$`"))
            .containsExactly("\\int_0^1 x\\,dx")
        assertThat(renderedLatex("行内 \$a+b\$ 与块 \$\$E=mc^2\$\$")).containsExactly("a+b", "E=mc^2").inOrder()
    }

    @Test
    fun `货币金额不构成公式`() {
        assertThat(renderedLatex("It costs \$5 and \$10 today")).isEmpty()
        assertThat(renderedLatex("区间 \$5/\$10 都可以")).isEmpty()
        assertThat(renderedLatex("从 \$5-\$10 不等")).isEmpty()
    }

    @Test
    fun `未拆为公式的美元片段按原文显示`() {
        // GFM 解析器会把 `$5 and $` 解析为行内数学节点；真正的公式已在分段时拆出，正文中剩下的应原样显示。
        for (input in listOf("It costs \$5 and \$10 today", "价格区间 \$5/\$10", "范围 \$x \\in [0, 1]\$10 内")) {
            assertThat(splitRichSegments(input)).containsExactly(ContentSegment.Markdown(input))
            assertThat(annotatedParagraph(input)).isEqualTo(input)
        }
    }

    @Test
    fun `括号定界符归一化不改写代码内容`() {
        val code = "正则 `grep -E '\\(foo\\)'` 与\n\n```text\n\\[a-z\\]\n```"
        assertThat(normalizeLatexDelimiters(code)).isEqualTo(code)
        assertThat(normalizeLatexDelimiters("公式 \\(x\\) 与 `\\(y\\)`")).isEqualTo("公式 \$x\$ 与 `\\(y\\)`")
    }

    @Test
    fun `流式补全不因代码中的美元符号截断后文`() {
        val input = "运行 `echo \$\$` 查看进程号，然后继续阅读"
        assertThat(sanitizeStreamingMarkdown(input)).isEqualTo(input)
        assertThat(sanitizeStreamingMarkdown("变量 `\$HOME` 末尾 \$")).isEqualTo("变量 `\$HOME` 末尾 ")
    }
}
