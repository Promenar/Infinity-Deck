package com.promenar.infinity.ui.common

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test

class MarkdownWideContentContractTest {
    private val moduleRoot: Path = Path.of(System.getProperty("user.dir") ?: ".").let { root ->
        if (Files.isDirectory(root.resolve("src/main"))) root else root.resolve("app")
    }

    private fun source(relativePath: String): String = String(
        Files.readAllBytes(moduleRoot.resolve(relativePath)),
        Charsets.UTF_8,
    )

    @Test
    fun `第三方 Markdown 回调不注册新的横向滚动`() {
        val markdownSource = source("src/main/java/com/promenar/infinity/ui/common/MarkdownText.kt")
        val tableSource = source("src/main/java/com/promenar/infinity/ui/renderer/TableWidget.kt")
        val callbackSource = markdownSource
            .substringAfter("markdownComponents(")
            .substringBefore("\n    Markdown(\n")

        assertThat(callbackSource).doesNotContain("horizontalScroll(")
        assertThat(callbackSource).doesNotContain("rememberScrollState(")
        assertThat(tableSource).doesNotContain("horizontalScroll(")
        assertThat(tableSource).doesNotContain("rememberScrollState(")
    }

    @Test
    fun `单次 Markdown 成功回调只接管顶层代码节点`() {
        val markdownSource = source("src/main/java/com/promenar/infinity/ui/common/MarkdownText.kt")
        val hostSource = markdownSource.substringAfter("private fun WideContentHost")

        assertThat(markdownSource).contains("success = { state, resolvedComponents, successModifier ->")
        assertThat(markdownSource).contains("val blocks = state.node.children.filter")
        assertThat(markdownSource).contains("blocks.forEachIndexed")
        assertThat(markdownSource).contains("MarkdownElement(node, resolvedComponents, renderContent)")
        assertThat(markdownSource).contains("MarkdownElementTypes.PARAGRAPH")
        assertThat(markdownSource).contains("MarkdownElementTypes.CODE_FENCE")
        assertThat(markdownSource).contains("MarkdownElementTypes.CODE_BLOCK")
        assertThat(markdownSource).contains("GFMElementTypes.TABLE")
        assertThat(markdownSource).contains("val renderContent = state.content")
        assertThat(markdownSource).contains("standaloneInlineCode(node, renderContent)")
        assertThat(markdownSource).contains("node.containsWideContentDescendant()")
        assertThat(markdownSource).contains("nestedTableMinimumWidth(")
        assertThat(markdownSource).contains("WideAwareContainer(")
        assertThat(markdownSource).doesNotContain("findStandaloneInlineCode")
        assertThat(markdownSource).doesNotContain("splitWideMarkdownSegments")
        assertThat(markdownSource).doesNotContain("WideMarkdownSegment")
        assertThat(hostSource).contains("BoxWithConstraints")
        assertThat(hostSource).contains("maxOf(viewportWidth, contentWidth)")
        assertThat(hostSource).contains("rememberTextMeasurer()")
        assertThat(hostSource).contains("widestLogicalLineWidthPx(text)")
        assertThat(hostSource).doesNotContain("maxByOrNull(String::length)")
        assertThat(hostSource).contains("horizontalScroll(scrollState)")
        assertThat(hostSource).contains("UiTags.MARKDOWN_WIDE_CONTENT")
    }

    @Test
    fun `顶层表格由成功回调外部宽内容宿主接管且保留表格组件`() {
        val markdownSource = source("src/main/java/com/promenar/infinity/ui/common/MarkdownText.kt")
        val tableSource = source("src/main/java/com/promenar/infinity/ui/renderer/TableWidget.kt")
        val successSource = markdownSource
            .substringAfter("success = { state, resolvedComponents, successModifier ->")
            .substringBefore("private fun WideContentHost")

        assertThat(successSource).contains("GFMElementTypes.TABLE")
        assertThat(successSource).contains("WideContentHost(")
        assertThat(successSource).contains("InfinityTableWidget(")
        assertThat(successSource).contains("minimumContentWidth = tableLayout.totalWidth")
        assertThat(successSource).contains("MarkdownElement(node, resolvedComponents, renderContent)")
        assertThat(tableSource).contains("BoxWithConstraints")
        assertThat(tableSource).contains("val scale = if (total > 0.dp) maxWidth / total else 1f")
    }

    @Test
    fun `宽内容外层横向滚动宿主先叠加横向渐隐边再注册横向滚动`() {
        val markdownSource = source("src/main/java/com/promenar/infinity/ui/common/MarkdownText.kt")
        val tableSource = source("src/main/java/com/promenar/infinity/ui/renderer/TableWidget.kt")
        val hostSource = markdownSource
            .substringAfter("private fun WideContentHost(")
            .substringBefore("private val bracketBlockLatex")

        val fadeIndex = hostSource.indexOf(".horizontalFadingEdges(scrollState, length = 24.dp)")
        val scrollIndex = hostSource.indexOf(".horizontalScroll(scrollState)")
        assertThat(fadeIndex).isAtLeast(0)
        assertThat(scrollIndex).isGreaterThan(fadeIndex)
        assertThat(tableSource).doesNotContain("horizontalScroll(")
    }
}
