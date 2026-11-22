package com.promenar.infinity.ui.renderer

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.common.INLINE_CODE_PADDING_ALTERNATE_TEXT
import com.promenar.infinity.ui.common.INLINE_CODE_PADDING_ID
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.jupiter.api.Test

class TableWidgetTest {
    @Test
    fun `parses header and body cell semantics from the GFM table AST`() {
        val markdown = """
            | Model | Context | Status |
            |---|---:|:---:|
            | DeepSeek V4 | 64K | Ready |
            | MiniMax M3 | 128K | Preview |
        """.trimIndent()
        val tableNode = MarkdownParser(GFMFlavourDescriptor())
            .buildMarkdownTreeFromString(markdown)
            .children
            .single { it.type == GFMElementTypes.TABLE }

        val table = parseMarkdownTable(markdown, tableNode)

        assertThat(table).isNotNull()
        assertThat(table!!.headerCells).containsExactly("Model", "Context", "Status").inOrder()
        assertThat(table.rows).containsExactly(
            listOf("DeepSeek V4", "64K", "Ready"),
            listOf("MiniMax M3", "128K", "Preview"),
        ).inOrder()
        assertThat(table.headerNodes).hasSize(3)
        assertThat(table.rowNodes.map { it.size }).containsExactly(3, 3)
    }

    @Test
    fun `column widths follow the widest cell and stay within bounds`() {
        val table = ParsedTable(
            headerCells = listOf("ID", "说明", "长文本"),
            rows = listOf(
                listOf("1", "短", "x".repeat(200)),
                listOf("22", "稍长一些的说明", ""),
            ),
        )
        // 假定每个字符 8dp，便于验证取最宽单元格、加 24dp 内边距并夹在上下限之间。
        val widths = tableColumnWidths(table) { column, row ->
            val text = if (row < 0) table.headerCells[column] else table.rows[row][column]
            (text.length * 8).dp
        }

        assertThat(widths[0]).isEqualTo(TableMinColumnWidth)
        assertThat(widths[1]).isEqualTo((7 * 8 + 24).dp)
        assertThat(widths[2]).isEqualTo(TableMaxColumnWidth)
    }

    @Test
    fun `rejects non table AST nodes`() {
        val markdown = "ordinary paragraph"
        val paragraph = MarkdownParser(GFMFlavourDescriptor())
            .buildMarkdownTreeFromString(markdown)
            .children
            .single()

        assertThat(parseMarkdownTable(markdown, paragraph)).isNull()
    }

    @Test
    fun `inline content placeholders are removed while styles stay on the remaining text`() {
        val styled = buildAnnotatedString {
            append("支持 ")
            appendInlineContent(INLINE_CODE_PADDING_ID, INLINE_CODE_PADDING_ALTERNATE_TEXT)
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("tool_call") }
            appendInlineContent("library-placeholder")
            append(" 原文\uFFFD")
        }

        val cleaned = styled.withoutInlineContentPlaceholders()

        assertThat(cleaned.text).isEqualTo("支持 tool_call 原文\uFFFD")
        val bold = cleaned.spanStyles.single()
        assertThat(cleaned.text.substring(bold.start, bold.end)).isEqualTo("tool_call")
    }

    @Test
    fun `table cell converts workspace and remote images to clickable links and blocks unsafe schemes`() {
        val markdown = """
            | 段 1 | 远程 | 拦截 |
            |---|---|---|
            | ![红苹果](/workspace/media/2026-11-22/apple.png) | ![](https://example.com/pic.png) | ![非法](file:///etc/passwd) |
        """.trimIndent()
        val tableNode = MarkdownParser(GFMFlavourDescriptor())
            .buildMarkdownTreeFromString(markdown)
            .children
            .single { it.type == GFMElementTypes.TABLE }
        val table = parseMarkdownTable(markdown, tableNode)!!
        val rowNodes = table.rowNodes.single()

        fun renderCell(col: Int) = buildTableCellAnnotatedString(
            source = table.source,
            node = rowNodes[col],
            fallback = table.rows.single()[col],
        ) { src, nodes ->
            AnnotatedString(nodes.joinToString("") { src.substring(it.startOffset, it.endOffset) })
        }

        val c0 = renderCell(0)
        assertThat(c0.text).isEqualTo("红苹果")
        val link0 = c0.getLinkAnnotations(0, c0.length).single().item as LinkAnnotation.Url
        assertThat(link0.url).isEqualTo("/workspace/media/2026-11-22/apple.png")

        val c1 = renderCell(1)
        assertThat(c1.text).isEqualTo("pic.png")
        val link1 = c1.getLinkAnnotations(0, c1.length).single().item as LinkAnnotation.Url
        assertThat(link1.url).isEqualTo("https://example.com/pic.png")

        val c2 = renderCell(2)
        assertThat(c2.text).isEqualTo("非法")
        assertThat(c2.getLinkAnnotations(0, c2.length)).isEmpty()
    }
}
