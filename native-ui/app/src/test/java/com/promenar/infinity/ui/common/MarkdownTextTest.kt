package com.promenar.infinity.ui.common

import com.google.common.truth.Truth.assertThat
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MarkdownTextTest {
    @Test
    fun `完整预处理保留空格与Tab混合缩进代码`() {
        for (spaces in listOf(" ", "  ", "   ")) {
            val input = "${spaces}\t#标题 1.内容 > 引用\n${spaces}\t2.下一行"
            assertThat(prepareMarkdownForRendering(input)).isEqualTo(input)
        }
    }
    @Test
    fun `完整预处理保留Tab缩进代码内容`() {
        val input = "\t#标题 1.内容 > 引用\n\t2.下一行"
        assertThat(prepareMarkdownForRendering(input)).isEqualTo(input)
    }

    @Nested
    inner class NormalizeLatexDelimiters {

        @Test
        fun `converts block latex delimiters to double dollar block`() {
            val input = "\\[E = mc^2\\]"
            val result = normalizeLatexDelimiters(input)
            assertThat(result).isEqualTo("\$\$\nE = mc^2\n\$\$")
            assertThat(result).doesNotContain("\\[")
            assertThat(result).doesNotContain("\\]")
        }

        @Test
        fun `converts inline latex delimiters to single dollar`() {
            val input = "Inline \\(E = mc^2\\) here"
            val result = normalizeLatexDelimiters(input)
            assertThat(result).isEqualTo("Inline \$E = mc^2\$ here")
            assertThat(result).doesNotContain("\\(")
            assertThat(result).doesNotContain("\\)")
        }

        @Test
        fun `converts multi-line block latex content`() {
            val input = "\\[\nE = mc^2\nF = ma\n\\]"
            val result = normalizeLatexDelimiters(input)
            // group content is trimmed by implementation
            assertThat(result).isEqualTo("\$\$\nE = mc^2\nF = ma\n\$\$")
            assertThat(result).startsWith("\$\$\n")
            assertThat(result).endsWith("\n\$\$")
        }

        @Test
        fun `returns text unchanged when no latex delimiters present`() {
            val input = "Just some markdown **text** with no LaTeX."
            val result = normalizeLatexDelimiters(input)
            assertThat(result).isEqualTo(input)
        }

        @Test
        fun `converts mixed block and inline latex delimiters`() {
            val input = "Block \\[a + b\\] and inline \\(x = 1\\)."
            val result = normalizeLatexDelimiters(input)
            assertThat(result).isEqualTo("Block \$\$\na + b\n\$\$ and inline \$x = 1\$.")
        }

        @Test
        fun `trims surrounding whitespace inside block latex group`() {
            val input = "\\[   spaced formula   \\]"
            val result = normalizeLatexDelimiters(input)
            assertThat(result).isEqualTo("\$\$\nspaced formula\n\$\$")
        }
    }

    @Nested
    inner class SanitizeStreamingMarkdown {

        @Test
        fun `closes unclosed code fence when odd number of fences`() {
            val input = "```kotlin\nval x = 1"
            val result = sanitizeStreamingMarkdown(input)
            assertThat(result.endsWith("```")).isTrue()
            assertThat(result).isEqualTo("```kotlin\nval x = 1\n```")
        }

        @Test
        fun `does not modify already closed code fence`() {
            val input = "```kotlin\nval x = 1\n```"
            val result = sanitizeStreamingMarkdown(input)
            assertThat(result).isEqualTo(input)
        }

        @Test
        fun `truncates from last unclosed double dollar block math`() {
            // Single $$ (odd) -> everything from the last $$ onward is dropped.
            val input = "intro\n\$\$\nE = mc^2"
            val result = sanitizeStreamingMarkdown(input)
            assertThat(result).isEqualTo("intro\n")
            assertThat(result).doesNotContain("\$\$")
        }

        @Test
        fun `leaves balanced double dollar block math intact`() {
            val input = "\$\$\nE = mc^2\n\$\$"
            val result = sanitizeStreamingMarkdown(input)
            assertThat(result).isEqualTo(input)
        }

        @Test
        fun `drops trailing lone single dollar when inline count is odd`() {
            // Lone trailing $ -> dropped (not closed by appending).
            val input = "cost is \$"
            val result = sanitizeStreamingMarkdown(input)
            assertThat(result).isEqualTo("cost is ")
            assertThat(result).doesNotContain("\$")
        }

        @Test
        fun `does not append closing single dollar for non-trailing odd dollar`() {
            // Odd lone $ that is not at the end -> implementation leaves it untouched.
            val input = "price \$5 today"
            val result = sanitizeStreamingMarkdown(input)
            assertThat(result).isEqualTo("price \$5 today")
        }

        @Test
        fun `returns already complete text unchanged`() {
            val input = "Hello **world** and `code`."
            val result = sanitizeStreamingMarkdown(input)
            assertThat(result).isEqualTo(input)
        }

        @Test
        fun `temporarily closes unbalanced bold marker while streaming`() {
            val input = "1. **预"
            val result = sanitizeStreamingMarkdown(input)
            assertThat(result).isEqualTo("1. **预**")
        }

        @Test
        fun `ignores bold markers inside inline code and code fences`() {
            val input = "`**not bold**`\n```kotlin\nval text = \"**raw**\"\n```"
            val result = sanitizeStreamingMarkdown(input)
            assertThat(result).isEqualTo(input)
        }

        @Test
        fun `returns empty string unchanged`() {
            val result = sanitizeStreamingMarkdown("")
            assertThat(result).isEqualTo("")
        }

        @Test
        fun `double dollar inside inline single dollar count is excluded`() {
            // $$ contributes zero to the lone-$ count; a trailing lone $ is still dropped.
            val input = "\$\$a\$\$ trailing \$"
            val result = sanitizeStreamingMarkdown(input)
            // $$ balanced (2) -> kept; lone trailing $ (count 1, odd, ends with $) -> dropped.
            assertThat(result).isEqualTo("\$\$a\$\$ trailing ")
        }
    }

    @Nested
    inner class SafeTrimIndent {

        @Test
        fun `trims common indent when no four-space indented lines`() {
            // 2-space indented lines do NOT trigger code-block protection, so trimIndent
            // collapses the shared 2-space prefix.
            val input = "  line one\n  line two"
            val result = safeTrimIndent(input)
            assertThat(result).isEqualTo("line one\nline two")
        }

        @Test
        fun `preserves text when a line starts with four-space indent`() {
            val input = "intro\n    val x = 1\n    val y = 2"
            val result = safeTrimIndent(input)
            assertThat(result).isEqualTo(input)
        }

        @Test
        fun `returns empty string for empty input`() {
            assertThat(safeTrimIndent("")).isEqualTo("")
        }

        @Test
        fun `returns single line unchanged when no shared indent`() {
            val input = "single line"
            val result = safeTrimIndent(input)
            assertThat(result).isEqualTo("single line")
        }

        @Test
        fun `does not treat four-space-only blank line as code block`() {
            // A line that is only whitespace after the 4 spaces does NOT trigger protection,
            // so trimIndent applies and trims the leading blank line.
            val result = safeTrimIndent("    \nrest")
            assertThat(result).isEqualTo("rest")
        }
    }

    @Nested
    inner class RepairCompressedMarkdownBoundaries {

        @Test
        fun `separates compressed cjk headings into block headings`() {
            val input = "前言 #一级标题##二级标题###三级标题正文"
            val result = repairCompressedMarkdownBoundaries(input)

            assertThat(result).contains("前言\n\n# 一级标题")
            assertThat(result).contains("\n\n## 二级标题")
            assertThat(result).contains("\n\n### 三级标题正文")
        }

        @Test
        fun `separates horizontal rule followed by heading`() {
            val input = "排版效果： ---###列表测试"
            val result = repairCompressedMarkdownBoundaries(input)

            assertThat(result).contains("排版效果：\n\n---\n\n### 列表测试")
        }

        @Test
        fun `separates compressed ordered list markers`() {
            val input = "有序列表： **1.第一步 2.第二步 3.第三步###代码块测试"
            val result = repairCompressedMarkdownBoundaries(input)

            assertThat(result).contains("有序列表：\n1. 第一步")
            assertThat(result).doesNotContain("**\n1.")
            assertThat(result).contains("\n2. 第二步")
            assertThat(result).contains("\n3. 第三步")
            assertThat(result).contains("\n\n### 代码块测试")
        }

        @Test
        fun `小数和百分比保持原文`() {
            val input = "算一下3.7% 年化，收益率为0.85%，本金增长1.25倍。"

            assertThat(repairCompressedMarkdownBoundaries(input)).isEqualTo(input)
        }

        @Test
        fun `版本号和价格保持原文`() {
            val input = "版本 1.2.3、v0.41.0，价格为￥12.99 和 100.50 元。"

            assertThat(repairCompressedMarkdownBoundaries(input)).isEqualTo(input)
        }

        @Test
        fun `整行加粗的编号标题保持原文`() {
            val inputs = listOf(
                "**3. 克隆示例仓库** ✅",
                "**3.克隆示例仓库**",
                "- **1. 准备环境**",
                "> __2. 注意事项__",
                "步骤：\n**1. 安装**\n**2. 配置**",
            )
            for (input in inputs) {
                assertThat(repairCompressedMarkdownBoundaries(input)).isEqualTo(input)
            }
        }

        @Test
        fun `加粗小数保持标记和数字`() {
            val input = "**3.7%** 年化收益"

            assertThat(repairCompressedMarkdownBoundaries(input)).isEqualTo(input)
        }

        @Test
        fun `有序列表的数字内容保持完整`() {
            val input = "1. 3.7% 年化\n2. 12.99 元\n3. 版本 1.2.3"

            assertThat(repairCompressedMarkdownBoundaries(input)).isEqualTo(input)
        }

        @Test
        fun `普通句和算式中的减号加号乘号保持原文`() {
            val input = "比较 A - B，计算 10 - 3 - 2、A + B + C 和 A * B * C。"

            assertThat(repairCompressedMarkdownBoundaries(input)).isEqualTo(input)
        }

        @Test
        fun `已有列表项内的算式不生成新列表项`() {
            val input = "- 计算 10 - 3 + 2\n- 比较 A - B"

            assertThat(repairCompressedMarkdownBoundaries(input)).isEqualTo(input)
        }

        @Test
        fun `冒号引出的连续同类列表标记恢复换行`() {
            listOf('-', '*', '+').forEach { marker ->
                val input = "要点： $marker 第一项 $marker 第二项 $marker 第三项"
                val expected = "要点：\n$marker 第一项\n$marker 第二项\n$marker 第三项"

                assertThat(repairCompressedMarkdownBoundaries(input)).isEqualTo(expected)
            }
        }

        @Test
        fun `孤立标记和混合标记不被猜测为压缩列表`() {
            val input = "前文 - 后文\n要点： - 第一项 + 第二项\n计算：10 - 3 - 2"

            assertThat(repairCompressedMarkdownBoundaries(input)).isEqualTo(input)
        }

        @Test
        fun `冒号后的负数百分比算式保持原文`() {
            val input = "计算： - 3.7% - 2.5%"

            assertThat(repairCompressedMarkdownBoundaries(input)).isEqualTo(input)
        }

        @Test
        fun `普通文本的CRLF换行保持原文`() {
            val input = "第一段。\r\n\r\n比较 A - B。"

            assertThat(repairCompressedMarkdownBoundaries(input)).isEqualTo(input)
        }

        @Test
        fun `separates compressed markdown table rows`() {
            val input = "###表格测试|功能|状态|备注||---|---|---||粗体|✅|正常工作||斜体|✅|正常工作"
            val result = repairCompressedMarkdownBoundaries(input)

            assertThat(result).contains("### 表格测试\n|功能|状态|备注|")
            assertThat(result).contains("\n|---|---|---|")
            assertThat(result).contains("\n|粗体|✅|正常工作|")
            assertThat(result).contains("\n|斜体|✅|正常工作|")
        }

        @Test
        fun `表格行内的井号编号和大于号保持原文`() {
            val input = "按最近更新排序：\n\n| # | 仓库 | 说明 |\n|---|---|---|\n| 1 | Personal-Archive | 1. 首页 |\n| 2 | Nian | a > b |"
            val result = repairCompressedMarkdownBoundaries(input)

            assertThat(result).isEqualTo(input)
            val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(result)
            assertThat(tree.children.map { it.type.name }).contains("TABLE")
        }

        @Test
        fun `does not alter inline code and links`() {
            val input = "保留 `#标签##值 1.不是列表` 和 [标题#一](https://example.com/a#b)"
            val result = repairCompressedMarkdownBoundaries(input)

            assertThat(result).isEqualTo(input)
        }

        @Test
        fun `does not trim protected indented code block`() {
            val input = "    val x = 1\n    val y = 2"
            val result = repairCompressedMarkdownBoundaries(input)

            assertThat(result).isEqualTo(input)
        }

        @Test
        fun `缩进代码中的标题列表和引用标记保持原文`() {
            val input = "正文\n\n    #标题 1.内容 A - B > 引用\n    2.下一行"

            assertThat(repairCompressedMarkdownBoundaries(input)).isEqualTo(input)
        }

        @Test
        fun `反引号和波浪号围栏中的内容保持原文`() {
            val input = "```text\n要点： - 第一项 - 第二项\n#标题 1.内容 `值` > 引用\n```\n\n~~~text\n#标题 1.内容 A - B > 引用\n~~~"

            assertThat(repairCompressedMarkdownBoundaries(input)).isEqualTo(input)
        }

        @Test
        fun `多反引号行内代码保持原文`() {
            val input = "保留 ``内容 `#标题 1.内容 A - B` 结束``。"

            assertThat(repairCompressedMarkdownBoundaries(input)).isEqualTo(input)
        }

        @Test
        fun `带嵌套括号的链接和引用链接保持原文`() {
            val input = "[比较 [A - B] #标题](https://example.com/a_(b))\n\n[A - B #标题][手册]\n\n[手册]: https://example.com"

            assertThat(repairCompressedMarkdownBoundaries(input)).isEqualTo(input)
        }
    }

    @Nested
    inner class CjkPunctuationEmphasis {
        private fun nodesOf(markdown: String, type: org.intellij.markdown.IElementType): List<String> {
            val found = mutableListOf<String>()
            fun visit(node: ASTNode) {
                if (node.type == type) found += markdown.substring(node.startOffset, node.endOffset)
                node.children.forEach(::visit)
            }
            visit(MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown))
            return found
        }

        private fun strongTexts(markdown: String) = nodesOf(markdown, MarkdownElementTypes.STRONG)

        @Test
        fun `闭合加粗左侧为中文标点右侧为汉字时生效`() {
            val input = "所以更准确的说法是：**符是一门用图形写成的公文术，……只是收件人是神仙。**至于灵不灵"
            val prepared = prepareMarkdownForRendering(input)

            assertThat(strongTexts(prepared).single()).contains("只是收件人是神仙。")
            assertThat(prepared).isEqualTo(
                "所以更准确的说法是：**符是一门用图形写成的公文术，……只是收件人是神仙。​**至于灵不灵",
            )
        }

        @Test
        fun `开启加粗左侧为汉字右侧为中文引号时生效`() {
            val prepared = prepareMarkdownForRendering("这叫**“画符”**而已")

            assertThat(strongTexts(prepared).single()).contains("“画符”")
        }

        @Test
        fun `单星号斜体与三星号粗斜体同样生效`() {
            val emphasis = prepareMarkdownForRendering("提示：*先读说明。*再动手")
            assertThat(nodesOf(emphasis, MarkdownElementTypes.EMPH).single()).contains("先读说明。")

            val strongEmphasis = prepareMarkdownForRendering("结论：***务必备份。***再升级")
            assertThat(strongTexts(strongEmphasis)).isNotEmpty()
            assertThat(nodesOf(strongEmphasis, MarkdownElementTypes.EMPH)).isNotEmpty()
        }

        @Test
        fun `列表项内与同一行多段加粗都生效`() {
            val prepared = prepareMarkdownForRendering("- **第一，**其次。**第二。**最后")

            assertThat(strongTexts(prepared)).hasSize(2)
        }

        @Test
        fun `原本就能解析的中文加粗保持原文`() {
            for (input in listOf("说法是：**符**至于", "**重点**。后文", "前文**重点**后文", "注意：**一定**。")) {
                assertThat(prepareMarkdownForRendering(input)).isEqualTo(input)
            }
        }

        @Test
        fun `英文强调保持原文`() {
            for (input in listOf("foo.**bar**baz", "**Hello**, world.", "a *b* c", "x.*y*z", "2*3*4")) {
                assertThat(prepareMarkdownForRendering(input)).isEqualTo(input)
            }
        }

        @Test
        fun `代码链接网址与转义星号保持原文`() {
            val inputs = listOf(
                "：**开始 `神仙。**至于` 结束",
                "前文\n\n```\n：**开始神仙。**至于\n```\n\n后文",
                "：**开始[神仙。**至于](https://example.com/a。**b)末",
                "：**开始 https://example.com/神仙。**至于 末",
                "字面：\\*\\*神仙。\\**至于",
            )
            for (input in inputs) {
                assertThat(prepareMarkdownForRendering(input)).isEqualTo(input)
            }
        }

        @Test
        fun `列表符号与分隔线不受影响`() {
            val input = "* 第一项。\n* 第二项\n\n***\n\n结束。"

            assertThat(prepareMarkdownForRendering(input)).isEqualTo(input)
        }
    }

    @Nested
    inner class PreserveOriginalSpacing {

        @Test
        fun `汉字后接英文时保持原文`() {
            val result = prepareMarkdownForRendering("测试hello")
            assertThat(result).isEqualTo("测试hello")
        }

        @Test
        fun `英文后接汉字时保持原文`() {
            val result = prepareMarkdownForRendering("hello测试")
            assertThat(result).isEqualTo("hello测试")
        }

        @Test
        fun `汉字与数字相邻时保持原文`() {
            val result = prepareMarkdownForRendering("共3项")
            assertThat(result).isEqualTo("共3项")
        }

        @Test
        fun `does not insert inside inline code spans`() {
            // Code containing CJK+ASCII adjacency must be preserved verbatim.
            val result = prepareMarkdownForRendering("前`测a`后")
            assertThat(result).isEqualTo("前`测a`后")
            assertThat(result).doesNotContain("\u200A")
        }

        @Test
        fun `does not insert inside link text or url`() {
            val result = prepareMarkdownForRendering("见[测试a](http://x.com)末")
            assertThat(result).isEqualTo("见[测试a](http://x.com)末")
            assertThat(result).doesNotContain("\u200A")
        }

        @Test
        fun `leaves pure english text unchanged`() {
            val input = "hello world foo bar"
            assertThat(prepareMarkdownForRendering(input)).isEqualTo(input)
        }

        @Test
        fun `leaves pure cjk text unchanged`() {
            val input = "测试中文标点"
            assertThat(prepareMarkdownForRendering(input)).isEqualTo(input)
        }

        @Test
        fun `混排词组两侧保持原文`() {
            val result = prepareMarkdownForRendering("中文ABC中文")
            assertThat(result).isEqualTo("中文ABC中文")
        }

        @Test
        fun `中文标点和已有空白保持原文`() {
            val input = "选 A 还是选 B、中文ABC，XX  新闻、保留\u200A原有间隔。"

            assertThat(prepareMarkdownForRendering(input)).isEqualTo(input)
        }
    }

    @Nested
    inner class StripBlockQuoteMarkers {

        @Test
        fun `strips single line block quote marker`() {
            val result = stripBlockQuoteMarkers("> 文本")
            assertThat(result).isEqualTo("文本")
        }

        @Test
        fun `strips multi line block quote`() {
            val input = "> line one\n> line two\n> line three"
            val result = stripBlockQuoteMarkers(input)
            assertThat(result).isEqualTo("line one\nline two\nline three")
        }

        @Test
        fun `reduces nested block quote by one level`() {
            val input = ">> nested"
            val result = stripBlockQuoteMarkers(input)
            assertThat(result).isEqualTo("> nested")
        }

        @Test
        fun `returns non quote lines verbatim`() {
            val input = "plain line\nanother line"
            val result = stripBlockQuoteMarkers(input)
            assertThat(result).isEqualTo("plain line\nanother line")
        }

        @Test
        fun `strips marker when no space follows the angle bracket`() {
            val result = stripBlockQuoteMarkers(">文本")
            assertThat(result).isEqualTo("文本")
        }

        @Test
        fun `trims leading whitespace before angle bracket`() {
            val result = stripBlockQuoteMarkers("   > indented quote")
            assertThat(result).isEqualTo("indented quote")
        }
    }

    @Nested
    inner class ReplaceCodeInMarkdown {

        @Test
        fun `replaces matching code block content`() {
            val markdown = "```kotlin\nval x = 1\n```"
            val result = replaceCodeInMarkdown(markdown, "kotlin", "val x = 1", "val y = 2")
            assertThat(result).isEqualTo("```kotlin\nval y = 2\n```")
        }

        @Test
        fun `replaces only the matching language among multiple blocks`() {
            val markdown = "```python\nprint(1)\n```\n```kotlin\nval x = 1\n```"
            val result = replaceCodeInMarkdown(markdown, "kotlin", "val x = 1", "val y = 2")
            assertThat(result).isEqualTo("```python\nprint(1)\n```\n```kotlin\nval y = 2\n```")
        }

        @Test
        fun `returns unchanged when language does not match`() {
            val markdown = "```kotlin\nval x = 1\n```"
            val result = replaceCodeInMarkdown(markdown, "java", "val x = 1", "val y = 2")
            assertThat(result).isEqualTo(markdown)
        }

        @Test
        fun `returns unchanged when no code block present`() {
            val markdown = "just plain text"
            val result = replaceCodeInMarkdown(markdown, "kotlin", "val x = 1", "val y = 2")
            assertThat(result).isEqualTo(markdown)
        }

        @Test
        fun `handles null language as empty fence label`() {
            val markdown = "```\ncode here\n```"
            val result = replaceCodeInMarkdown(markdown, null, "code here", "new code")
            assertThat(result).isEqualTo("```\nnew code\n```")
        }
    }

    @Nested
    inner class Slugify {

        @Test
        fun `slugifies plain english title`() {
            assertThat(slugify("Hello World")).isEqualTo("hello-world")
        }

        @Test
        fun `preserves cjk characters in slug`() {
            // \p{L} includes CJK ideographs, so Chinese is retained (only lowercased,
            // which is a no-op for CJK).
            assertThat(slugify("测试标题")).isEqualTo("测试标题")
        }

        @Test
        fun `removes punctuation from title`() {
            assertThat(slugify("Hello, World!")).isEqualTo("hello-world")
        }

        @Test
        fun `collapses multiple whitespace into single hyphen`() {
            assertThat(slugify("Multiple   spaces")).isEqualTo("multiple-spaces")
        }

        @Test
        fun `keeps alphanumeric mix`() {
            assertThat(slugify("Section 42")).isEqualTo("section-42")
        }

        @Test
        fun `returns fallback heading when result is empty`() {
            assertThat(slugify("!!!???")).isEqualTo("heading")
        }

        @Test
        fun `returns fallback heading for blank input`() {
            assertThat(slugify("   ")).isEqualTo("heading")
        }
    }

    @Nested
    inner class SplitRichSegments {

        @Test
        fun `returns single markdown segment for plain text`() {
            val result = splitRichSegments("just some markdown text")
            assertThat(result).hasSize(1)
            assertThat(result[0]).isInstanceOf(ContentSegment.Markdown::class.java)
            assertThat((result[0] as ContentSegment.Markdown).content).isEqualTo("just some markdown text")
        }

        @Test
        fun `splits out mermaid code block`() {
            val input = "```mermaid\ngraph TD\nA-->B\n```"
            val result = splitRichSegments(input)
            assertThat(result).hasSize(1)
            assertThat(result[0]).isInstanceOf(ContentSegment.Mermaid::class.java)
            assertThat((result[0] as ContentSegment.Mermaid).content).isEqualTo("graph TD\nA-->B")
        }

        @Test
        fun `splits out echarts code block`() {
            val input = "```echarts\n{\"x\":1}\n```"
            val result = splitRichSegments(input)
            assertThat(result).hasSize(1)
            assertThat(result[0]).isInstanceOf(ContentSegment.ECharts::class.java)
            assertThat((result[0] as ContentSegment.ECharts).content).isEqualTo("{\"x\":1}")
        }

        @Test
        fun `splits out plantuml code block`() {
            val input = "```plantuml\n@startuml\nA-->B\n@enduml\n```"
            val result = splitRichSegments(input)
            assertThat(result).hasSize(1)
            assertThat(result[0]).isInstanceOf(ContentSegment.PlantUml::class.java)
            assertThat((result[0] as ContentSegment.PlantUml).content)
                .isEqualTo("@startuml\nA-->B\n@enduml")
        }

        @Test
        fun `splits out block level latex`() {
            val input = "\$\$E = mc^2\$\$"
            val result = splitRichSegments(input)
            assertThat(result).hasSize(1)
            assertThat(result[0]).isInstanceOf(ContentSegment.Latex::class.java)
            assertThat((result[0] as ContentSegment.Latex).content).isEqualTo("E = mc^2")
        }

        @Test
        fun `splits out inline latex`() {
            val input = "math \$E = mc^2\$ here"
            val result = splitRichSegments(input)
            assertThat(result).hasSize(3)
            assertThat(result[0]).isInstanceOf(ContentSegment.Markdown::class.java)
            assertThat((result[0] as ContentSegment.Markdown).content).isEqualTo("math ")
            assertThat(result[1]).isInstanceOf(ContentSegment.InlineLatex::class.java)
            assertThat((result[1] as ContentSegment.InlineLatex).content).isEqualTo("E = mc^2")
            assertThat(result[2]).isInstanceOf(ContentSegment.Markdown::class.java)
            assertThat((result[2] as ContentSegment.Markdown).content).isEqualTo(" here")
        }

        @Test
        fun `preserves segment order and types for mixed content`() {
            val input = "intro\n```mermaid\ngraph TD\nA-->B\n```\nmiddle\n\$\$E = mc^2\$\$\nend"
            val result = splitRichSegments(input)
            assertThat(result).hasSize(5)
            assertThat(result[0]).isInstanceOf(ContentSegment.Markdown::class.java)
            assertThat((result[0] as ContentSegment.Markdown).content).isEqualTo("intro\n")
            assertThat(result[1]).isInstanceOf(ContentSegment.Mermaid::class.java)
            assertThat((result[1] as ContentSegment.Mermaid).content).isEqualTo("graph TD\nA-->B")
            assertThat(result[2]).isInstanceOf(ContentSegment.Markdown::class.java)
            assertThat((result[2] as ContentSegment.Markdown).content).isEqualTo("\nmiddle\n")
            assertThat(result[3]).isInstanceOf(ContentSegment.Latex::class.java)
            assertThat((result[3] as ContentSegment.Latex).content).isEqualTo("E = mc^2")
            assertThat(result[4]).isInstanceOf(ContentSegment.Markdown::class.java)
            assertThat((result[4] as ContentSegment.Markdown).content).isEqualTo("\nend")
        }

        @Test
        fun `treats language case insensitively for rich blocks`() {
            val input = "```MERMAID\ngraph TD\nA-->B\n```"
            val result = splitRichSegments(input)
            assertThat(result).hasSize(1)
            assertThat(result[0]).isInstanceOf(ContentSegment.Mermaid::class.java)
        }

        @Test
        fun `inline latex inside ordered list item with latex commands is segmented`() {
            // 审计缺陷 P0-3 实测原文：真实模型回复中位于有序列表项内的行内公式
            val input = "3. 行内 LaTeX 二次方程求根公式：\$x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}\$\n\n4. Mermaid 流程图"
            val result = splitRichSegments(input)
            assertThat(result.any { it is ContentSegment.InlineLatex }).isTrue()
            assertThat(result.none { (it as? ContentSegment.Markdown)?.content?.contains("\\frac") == true }).isTrue()
        }
    }

    @Nested
    inner class StandaloneInlineCode {

        private fun paragraph(markdown: String): ASTNode =
            MarkdownParser(GFMFlavourDescriptor())
                .buildMarkdownTreeFromString(markdown)
                .children
                .single { it.type == MarkdownElementTypes.PARAGRAPH }

        @Test
        fun `recognizes a paragraph whose AST has exactly one code span`() {
            val command = "./gradlew :app:validateDebugScreenshotTest --tests WIDE_CONTENT_END_SENTINEL"
            val markdown = "`$command`"

            val result = standaloneInlineCode(paragraph(markdown), markdown)

            assertThat(result).isEqualTo(command)
        }

        @Test
        fun `recognizes a multi backtick code span without handwritten delimiter parsing`() {
            val markdown = "`` code ` inside ``"

            val result = standaloneInlineCode(paragraph(markdown), markdown)

            assertThat(result).isEqualTo("code ` inside")
        }

        @Test
        fun `rejects paragraph when code span has prose sibling nodes`() {
            val markdown = "Run `./gradlew test` before publishing."

            val result = standaloneInlineCode(paragraph(markdown), markdown)

            assertThat(result).isNull()
        }

        @Test
        fun `rejects paragraph when it has multiple code span nodes`() {
            val markdown = "`./gradlew test` `./gradlew lint`"

            val result = standaloneInlineCode(paragraph(markdown), markdown)

            assertThat(result).isNull()
        }

        @Test
        fun `rejects non paragraph AST node even when it contains one code span`() {
            val markdown = "- `./gradlew test`"
            val list = MarkdownParser(GFMFlavourDescriptor())
                .buildMarkdownTreeFromString(markdown)
                .children
                .single()

            val result = standaloneInlineCode(list, markdown)

            assertThat(result).isNull()
        }
    }

    @Nested
    inner class WideContentMeasurement {

        @Test
        fun `chooses widest measured logical line instead of longest string`() {
            val text = "iiiiiiiiiiiiiiii\nWWWW\n中文🚀"
            val measuredWidths = mapOf(
                "iiiiiiiiiiiiiiii" to 48,
                "WWWW" to 92,
                "中文🚀" to 76,
            )

            val width = widestLogicalLineWidthPx(text) { line -> measuredWidths.getValue(line) }

            assertThat(width).isEqualTo(92)
        }

        @Test
        fun `measures every logical line including empty and emoji lines`() {
            val measured = mutableListOf<String>()

            val width = widestLogicalLineWidthPx("first\n\n🚀") { line ->
                measured += line
                when (line) {
                    "first" -> 20
                    "" -> 0
                    else -> 44
                }
            }

            assertThat(measured).containsExactly("first", "", "🚀").inOrder()
            assertThat(width).isEqualTo(44)
        }
    }

    @Nested
    inner class NestedWideTableDetection {

        @Test
        fun `finds a GFM table nested inside a block quote`() {
            val markdown = """
                > | A | B | C | D | E | F |
                > |---|---|---|---|---|---|
                > | 1 | 2 | 3 | 4 | 5 | END |
            """.trimIndent()
            val blockQuote = MarkdownParser(GFMFlavourDescriptor())
                .buildMarkdownTreeFromString(markdown)
                .children
                .single()

            assertThat(blockQuote.containsWideTableDescendant()).isTrue()
        }

        @Test
        fun `finds a GFM table nested inside a list item`() {
            val markdown = """
                - item

                  | A | B | C | D | E | F |
                  |---|---|---|---|---|---|
                  | 1 | 2 | 3 | 4 | 5 | END |
            """.trimIndent()
            val list = MarkdownParser(GFMFlavourDescriptor())
                .buildMarkdownTreeFromString(markdown)
                .children
                .single()

            assertThat(list.containsWideTableDescendant()).isTrue()
        }

        @Test
        fun `ordinary quote and list do not enter the recursive wide renderer`() {
            val markdown = "> ordinary quote\n\n- ordinary list item"
            val roots = MarkdownParser(GFMFlavourDescriptor())
                .buildMarkdownTreeFromString(markdown)
                .children

            assertThat(roots).isNotEmpty()
            roots.forEach { node -> assertThat(node.containsWideTableDescendant()).isFalse() }
        }
    }
}
