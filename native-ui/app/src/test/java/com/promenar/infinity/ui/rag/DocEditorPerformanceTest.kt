package com.promenar.infinity.ui.rag

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DocEditorPerformanceTest {
    @Test
    fun `单次扫描保持CRLF行数与空白分词语义`() {
        assertThat(analyzeDocEditorText("")).isEqualTo(
            DocEditorTextStatistics(
                lineCount = 1,
                wordCount = 0,
                maxLogicalLineLength = 0,
            ),
        )
        assertThat(analyzeDocEditorText(" alpha\t beta\r\ngamma\rdelta\n")).isEqualTo(
            DocEditorTextStatistics(
                lineCount = 4,
                wordCount = 4,
                maxLogicalLineLength = 12,
            ),
        )
    }

    @Test
    fun `无Regex扫描保持旧ASCII空白分词边界`() {
        val unicodeSpacesStayInsideWord = "alpha\u00A0beta\u2003gamma\u3000delta"

        assertThat(analyzeDocEditorText("$unicodeSpacesStayInsideWord epsilon\tzeta")).isEqualTo(
            DocEditorTextStatistics(
                lineCount = 1,
                wordCount = 3,
                maxLogicalLineLength = unicodeSpacesStayInsideWord.length + 13,
            ),
        )
    }

    @Test
    fun `性能保护三组阈值本身仍可编辑且超出一个单位即保护`() {
        val contentBoundary = buildLengthBoundaryContent(MAX_EDITABLE_CONTENT_LENGTH)
        val lineCountBoundary = List(MAX_EDITABLE_LINE_COUNT) { "x" }.joinToString("\n")
        val logicalLineBoundary = "x".repeat(MAX_EDITABLE_LOGICAL_LINE_LENGTH)

        listOf(contentBoundary, lineCountBoundary, logicalLineBoundary).forEach { content ->
            assertThat(contentAccessFor(content.length, analyzeDocEditorText(content)))
                .isEqualTo(DocEditorContentAccess.Editable)
        }

        val tooLongContent = contentBoundary + "x"
        val tooManyLines = lineCountBoundary + "\nx"
        val tooLongLogicalLine = logicalLineBoundary + "x"
        listOf(tooLongContent, tooManyLines, tooLongLogicalLine).forEach { content ->
            assertThat(contentAccessFor(content.length, analyzeDocEditorText(content)))
                .isEqualTo(DocEditorContentAccess.PerformanceProtected)
        }
    }

    @Test
    fun `近1MiB超长单行统计不改变文本语义`() {
        val content = "x".repeat(1024 * 1024 - 1)

        val statistics = analyzeDocEditorText(content)

        assertThat(statistics.lineCount).isEqualTo(1)
        assertThat(statistics.wordCount).isEqualTo(1)
        assertThat(content.length).isEqualTo(1024 * 1024 - 1)
    }

    @Test
    fun `短文档预览保留完整内容且无截断事实`() {
        val content = "# Complete\n\n正文"

        val snapshot = buildDocEditorPreviewSnapshot(content)

        assertThat(snapshot.content).isSameInstanceAs(content)
        assertThat(snapshot.isTruncated).isFalse()
        assertThat(snapshot.originalLength).isEqualTo(content.length)
    }

    @Test
    fun `大文档预览限制为16K字符且不修改编辑保存全文`() {
        val fullContent = "x".repeat(1024 * 1024 - 1)

        val snapshot = buildDocEditorPreviewSnapshot(fullContent)

        assertThat(snapshot.content.length).isEqualTo(16 * 1024)
        assertThat(snapshot.content).isEqualTo(fullContent.take(16 * 1024))
        assertThat(snapshot.isTruncated).isTrue()
        assertThat(snapshot.originalLength).isEqualTo(fullContent.length)
        assertThat(fullContent.length).isEqualTo(1024 * 1024 - 1)
    }

    @Test
    fun `大文档预览不会在UTF16代理对中间截断`() {
        val emoji = "\uD83D\uDE03"
        val fullContent = "x".repeat(16 * 1024 - 1) + emoji + "tail"

        val snapshot = buildDocEditorPreviewSnapshot(fullContent)

        assertThat(snapshot.content.length).isEqualTo(16 * 1024 - 1)
        assertThat(snapshot.content.last()).isNotEqualTo('\uD83D')
        assertThat(snapshot.isTruncated).isTrue()
        assertThat(snapshot.originalLength).isEqualTo(fullContent.length)
    }

    @Test
    fun `一万行统计语义稳定且不构造完整行号字符串`() {
        val content = buildString {
            repeat(10_000) { index ->
                if (index > 0) append('\n')
                append("line ")
                append(index + 1)
            }
        }

        val statistics = analyzeDocEditorText(content)
        assertThat(statistics.lineCount).isEqualTo(10_000)
        assertThat(statistics.wordCount).isEqualTo(20_000)
        assertThat(sourceFile().readText()).doesNotContain("buildDocEditorLineNumbers")
    }

    @Test
    fun `EditorPane不再构造或渲染完整行号沟槽`() {
        val editorPane = sourceFile().readText()
            .substringAfter("private fun EditorPane")
            .substringBefore("private fun PreviewPane")

        assertThat(editorPane).doesNotContain("lineNumbers")
        assertThat(editorPane).doesNotContain("lineCount:")
        assertThat(editorPane).doesNotContain("text = lineNumbers")
    }

    @Test
    fun `Preview和Split只消费有界快照且Preview不绕过控制器`() {
        val loadedContent = sourceFile().readText()
            .substringAfter("private fun LoadedDocumentContent")
            .substringBefore("private fun DocumentIdentity")

        assertThat(loadedContent).contains("previewSnapshot.content")
        assertThat(loadedContent).doesNotContain("PreviewPane(content = editor.content)")
        assertThat(loadedContent).doesNotContain("content = editor.content,\n                        modifier")
    }

    @Test
    fun `截断预览提示提供中英文且明确编辑保存不受影响`() {
        val english = resourceFile("values/strings.xml").readText()
        val chinese = resourceFile("values-zh-rCN/strings.xml").readText()

        assertThat(english).contains(
            "<string name=\"doc_editor_preview_truncated\">Preview truncated; editing and saving unaffected</string>",
        )
        assertThat(chinese).contains(
            "<string name=\"doc_editor_preview_truncated\">预览已截断，不影响编辑和保存</string>",
        )
        assertThat(english).contains(
            "Previews the first ~16K characters; saving and copying use the full text",
        )
        assertThat(chinese).contains(
            "仅预览前约 1.6 万字，保存和复制使用全文",
        )
    }

    @Test
    fun `Split连续输入只在静默窗口提交最后快照`() = runTest {
        val controller = DocEditorPreviewSnapshotController(
            scope = this,
            initialContent = "base",
            debounceMillis = 250L,
        )

        controller.update("draft-1", DocEditorViewMode.SPLIT)
        runCurrent()
        advanceTimeBy(249L)
        assertThat(controller.snapshot.value.content).isEqualTo("base")

        controller.update("draft-2", DocEditorViewMode.SPLIT)
        advanceTimeBy(249L)
        assertThat(controller.snapshot.value.content).isEqualTo("base")
        advanceTimeBy(1L)
        runCurrent()

        assertThat(controller.snapshot.value.content).isEqualTo("draft-2")
    }

    @Test
    fun `等值不同实例不会重复调度Split快照`() = runTest {
        val initialContent = "base"
        val equalCopy = initialContent.toCharArray().concatToString()
        assertThat(equalCopy === initialContent).isFalse()
        val controller = DocEditorPreviewSnapshotController(
            scope = this,
            initialContent = initialContent,
            debounceMillis = 250L,
        )

        controller.update(equalCopy, DocEditorViewMode.SPLIT)

        assertThat(controller.hasPendingUpdate).isFalse()
        assertThat(controller.snapshot.value.content).isEqualTo(initialContent)
    }

    @Test
    fun `Preview切入立即采用最新正文且取消Split旧快照`() = runTest {
        val controller = DocEditorPreviewSnapshotController(
            scope = this,
            initialContent = "base",
            debounceMillis = 250L,
        )

        controller.update("stale-split", DocEditorViewMode.SPLIT)
        controller.update("latest-preview", DocEditorViewMode.PREVIEW)

        assertThat(controller.snapshot.value.content).isEqualTo("latest-preview")
        advanceUntilIdle()
        assertThat(controller.snapshot.value.content).isEqualTo("latest-preview")
    }

    @Test
    fun `退出Split会取消尚未提交的预览更新`() = runTest {
        val controller = DocEditorPreviewSnapshotController(
            scope = this,
            initialContent = "base",
            debounceMillis = 250L,
        )

        controller.update("stale-split", DocEditorViewMode.SPLIT)
        controller.update("editor-only", DocEditorViewMode.EDIT)
        advanceUntilIdle()

        assertThat(controller.snapshot.value.content).isEqualTo("base")
    }

    @Test
    fun `组合层销毁会取消尚未提交的预览更新`() = runTest {
        val controller = DocEditorPreviewSnapshotController(
            scope = this,
            initialContent = "base",
            debounceMillis = 250L,
        )

        controller.update("stale-split", DocEditorViewMode.SPLIT)
        controller.close()
        advanceUntilIdle()

        assertThat(controller.snapshot.value.content).isEqualTo("base")
    }

    private fun sourceFile(): File {
        val root = File(System.getProperty("user.dir") ?: ".")
        return sequenceOf(
            root.resolve("app/src/main/java/com/promenar/infinity/ui/rag/DocEditorScreen.kt"),
            root.resolve("native-ui/app/src/main/java/com/promenar/infinity/ui/rag/DocEditorScreen.kt"),
        ).first { it.isFile }
    }

    private fun buildLengthBoundaryContent(targetLength: Int): String = buildString(targetLength) {
        val unit = "x".repeat(63) + '\n'
        repeat(targetLength / unit.length) { append(unit) }
        append("x".repeat(targetLength - length))
    }

    private fun resourceFile(path: String): File {
        val root = File(System.getProperty("user.dir") ?: ".")
        return sequenceOf(
            root.resolve("app/src/main/res/$path"),
            root.resolve("native-ui/app/src/main/res/$path"),
        ).first { it.isFile }
    }
}
