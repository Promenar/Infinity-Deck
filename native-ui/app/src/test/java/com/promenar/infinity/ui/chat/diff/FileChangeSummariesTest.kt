package com.promenar.infinity.ui.chat.diff

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.FileChangeHunk
import com.promenar.infinity.data.model.FileChangeHunkOmission
import com.promenar.infinity.data.model.FileChangeSummary
import com.promenar.infinity.data.model.FileChangeType
import com.promenar.infinity.ui.common.diff.DiffLineKind
import com.promenar.infinity.ui.common.diff.DiffSegment
import com.promenar.infinity.ui.common.diff.FileDiffContent
import com.promenar.infinity.ui.common.diff.FileDiffLimits
import com.promenar.infinity.ui.common.diff.FileDiffModel
import com.promenar.infinity.ui.common.diff.OmittedDiffSegment
import org.junit.Test

class FileChangeSummariesTest {
    private fun text(value: String) = FileSnapshot.Text(value)

    @Test
    fun `新建只计新增并保存全部行`() {
        val summary = FileChangeSummaries.created("/docs/a.md", text("一\n二\n三\n"))

        assertThat(summary.change).isEqualTo(FileChangeType.CREATED)
        assertThat(summary.added).isEqualTo(3)
        assertThat(summary.removed).isEqualTo(0)
        assertThat(summary.shownRemoved).isNull()
        assertThat(summary.countsLabel).isEqualTo("+3")
        assertThat(summary.hunks).containsExactly(FileChangeHunk(1, 0, 1, 3, listOf("+一", "+二", "+三")))
    }

    @Test
    fun `删除只计删除`() {
        val summary = FileChangeSummaries.deleted("/a.txt", text("a\nb"))

        assertThat(summary.change).isEqualTo(FileChangeType.DELETED)
        assertThat(summary.removed).isEqualTo(2)
        assertThat(summary.countsLabel).isEqualTo("−2")
        assertThat(summary.hunks).isNull()
        assertThat(summary.hunksOmitted).isEqualTo(com.promenar.infinity.data.model.FileChangeHunkOmission.PERMANENT_DELETE)
    }

    @Test
    fun `修改按真实行号分段并标出末尾换行变化`() {
        val old = (1..20).joinToString("\n") { "line $it" } + "\n"
        val new = old.replace("line 2\n", "line 2 changed\n").replace("line 18\n", "") .removeSuffix("\n")

        val summary = FileChangeSummaries.modified("/src/a.kt", text(old), text(new))

        assertThat(summary.added).isEqualTo(2)
        assertThat(summary.removed).isEqualTo(3)
        val hunks = summary.hunks!!
        assertThat(hunks).hasSize(2)
        assertThat(hunks[0]).isEqualTo(
            FileChangeHunk(1, 5, 1, 5, listOf(" line 1", "-line 2", "+line 2 changed", " line 3", " line 4", " line 5")),
        )
        assertThat(hunks[1].oldStart).isEqualTo(15)
        assertThat(hunks[1].newStart).isEqualTo(15)
        // 新侧最后一行缺少末尾换行：按一删一增呈现并追加 `\` 标记。
        assertThat(hunks[1].lines.takeLast(3)).containsExactly("-line 20", "+line 20", "\\").inOrder()
    }

    @Test
    fun `内容未变时计数为零且不保存 hunk`() {
        val summary = FileChangeSummaries.modified("/a.txt", text("same\n"), text("same\n"))

        assertThat(summary.added).isEqualTo(0)
        assertThat(summary.removed).isEqualTo(0)
        assertThat(summary.hunks).isNull()
        assertThat(summary.hunksOmitted).isEqualTo(FileChangeHunkOmission.UNCHANGED)
    }

    @Test
    fun `移动不改内容计数为零`() {
        val summary = FileChangeSummaries.moved("/a.txt", "/docs/a.txt", directory = false)

        assertThat(summary.path).isEqualTo("/docs/a.txt")
        assertThat(summary.fromPath).isEqualTo("/a.txt")
        assertThat(summary.added).isEqualTo(0)
        assertThat(summary.removed).isEqualTo(0)
        assertThat(summary.hunksOmitted).isEqualTo(FileChangeHunkOmission.UNCHANGED)
        assertThat(FileChangeSummaries.moved("/d", "/e", directory = true).hunksOmitted).isEqualTo(FileChangeHunkOmission.DIRECTORY)
    }

    @Test
    fun `二进制不给计数`() {
        val decoded = FileChangeSummaries.decode(byteArrayOf(0x50, 0x4B, 0x00, 0x01))
        assertThat(decoded).isEqualTo(FileSnapshot.Binary)
        assertThat(FileChangeSummaries.decode(byteArrayOf(0xC3.toByte(), 0x28))).isEqualTo(FileSnapshot.Binary)

        val summary = FileChangeSummaries.deleted("/img.png", decoded)

        assertThat(summary.binary).isTrue()
        assertThat(summary.added).isNull()
        assertThat(summary.removed).isNull()
        assertThat(summary.countsLabel).isNull()
        assertThat(summary.hunksOmitted).isEqualTo(FileChangeHunkOmission.BINARY)
    }

    @Test
    fun `解码去掉 BOM 并保留换行`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "a\r\nb\n".toByteArray()

        assertThat(FileChangeSummaries.decode(bytes)).isEqualTo(FileSnapshot.Text("a\r\nb\n"))
    }

    @Test
    fun `超过逐行阈值标为大文件且仍给计数`() {
        val limits = FileDiffLimits(maxLinesPerSide = 10)
        val old = (1..30).joinToString("\n") { "row $it" }
        val new = old.replace("row 7", "row seven") + "\nrow 31"

        val summary = FileChangeSummaries.modified("/big.csv", text(old), text(new), limits)

        assertThat(summary.large).isTrue()
        assertThat(summary.added).isEqualTo(2)
        assertThat(summary.removed).isEqualTo(1)
        assertThat(summary.hunks).isNull()
        assertThat(summary.hunksOmitted).isEqualTo(FileChangeHunkOmission.LARGE)
    }

    @Test
    fun `超过读取上限只标大文件不给计数`() {
        val summary = FileChangeSummaries.modified("/huge.log", FileSnapshot.TooLarge, text("x"))

        assertThat(summary.large).isTrue()
        assertThat(summary.added).isNull()
        assertThat(summary.hunksOmitted).isEqualTo(FileChangeHunkOmission.LARGE)
    }

    @Test
    fun `读取失败时不给计数`() {
        val summary = FileChangeSummaries.modified("/a.txt", FileSnapshot.Unavailable, text("x"))

        assertThat(summary.added).isNull()
        assertThat(summary.hunksOmitted).isEqualTo(FileChangeHunkOmission.UNAVAILABLE)
        assertThat(FileChangeSummaries.deleted("/dir", FileSnapshot.Directory).directory).isTrue()
    }

    @Test
    fun `单文件 hunk 超过 32 KiB 时只保留计数`() {
        val line = "x".repeat(200)
        val content = (1..200).joinToString("\n") { "$it $line" }

        val summary = FileChangeSummaries.created("/gen.txt", text(content))

        assertThat(summary.added).isEqualTo(200)
        assertThat(summary.hunks).isNull()
        assertThat(summary.hunksOmitted).isEqualTo(FileChangeHunkOmission.SIZE_LIMIT)
    }

    @Test
    fun `已存 hunk 还原为差异模型时保留行号并把间隔显示为折叠区段`() {
        val old = (1..40).joinToString("\n") { "line $it" }
        val new = old.replace("line 5", "line five").replace("line 30", "line thirty")
        val summary = FileChangeSummaries.modified("/a.kt", text(old), text(new))

        val model = summary.toDiffModel()!!
        val full = FileDiffModel.fromTexts(old, new)

        val content = model.content as FileDiffContent.Lines
        val fullShown = (full.content as FileDiffContent.Lines).segments.filterIsInstance<DiffSegment.Shown>().flatMap { it.lines }
        assertThat(content.lines).isEqualTo(fullShown)
        assertThat(content.segments.map { it::class }).containsExactly(
            OmittedDiffSegment::class, DiffSegment.Shown::class, OmittedDiffSegment::class, DiffSegment.Shown::class,
        ).inOrder()
        assertThat(content.segments.filterIsInstance<OmittedDiffSegment>().map { it.lineCount }).containsExactly(1, 18).inOrder()
        assertThat(model.stats!!.added).isEqualTo(2)
        assertThat(content.lines.first { it.kind == DiffLineKind.ADDED }.newLineNumber).isEqualTo(5)
    }

    @Test
    fun `损坏的 hunk 返回空而不是抛出`() {
        val broken = FileChangeSummary(
            path = "/a",
            change = FileChangeType.MODIFIED,
            hunks = listOf(FileChangeHunk(5, 1, 5, 1, listOf("?bad"))),
        )
        assertThat(broken.toDiffModel()).isNull()
        assertThat(FileChangeSummary(path = "/a", change = FileChangeType.MODIFIED).toDiffModel()).isNull()
    }

    @Test
    fun `多个文件合计只累加可显示的计数`() {
        val changes = listOf(
            FileChangeSummary("/a", FileChangeType.CREATED, added = 3, removed = 0),
            FileChangeSummary("/b", FileChangeType.MODIFIED, added = 1, removed = 2),
            FileChangeSummary("/c", FileChangeType.DELETED, added = 0, removed = 4),
            FileChangeSummary("/d", FileChangeType.DELETED, binary = true),
        )

        assertThat(changes.totalCounts()).isEqualTo(4 to 6)
        assertThat(listOf(FileChangeSummary("/d", FileChangeType.DELETED, binary = true)).totalCounts()).isEqualTo(null to null)
    }
}
