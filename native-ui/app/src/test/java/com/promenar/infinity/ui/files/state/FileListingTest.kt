package com.promenar.infinity.ui.files.state

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.files.FileLocation
import com.promenar.infinity.domain.files.FileLocationCapabilities
import com.promenar.infinity.domain.files.FileLocationKind
import com.promenar.infinity.domain.files.FileSort
import com.promenar.infinity.domain.files.FileSortKey
import org.junit.Test

class FileListingTest {
    private val location = FileLocation("library", FileLocationKind.KNOWLEDGE_BASE, "存储空间", FileLocationCapabilities(true, true, true))
    private val alpha = file("/alpha.txt", modified = 20, size = 5, mime = "text/plain")
    private val beta = file("/beta.pdf", modified = 10, size = 20, mime = "application/pdf")
    private val folder = file("/folder", directory = true, modified = 1, size = 1)

    @Test fun `四种排序键支持升降序和文件夹优先`() {
        val items = listOf(alpha, folder, beta)
        val cases = listOf(
            FileSortKey.NAME to listOf("alpha.txt", "beta.pdf", "folder"),
            FileSortKey.MODIFIED to listOf("folder", "beta.pdf", "alpha.txt"),
            FileSortKey.SIZE to listOf("folder", "alpha.txt", "beta.pdf"),
            FileSortKey.TYPE to listOf("folder", "beta.pdf", "alpha.txt"),
        )
        for ((key, expected) in cases) {
            val ascending = FileListing.items(items, FileSort(key, ascending = true, foldersFirst = false), true)
            val descending = FileListing.items(items, FileSort(key, ascending = false, foldersFirst = false), true)
            assertThat(ascending.map { it.name }).containsExactlyElementsIn(expected).inOrder()
            assertThat(descending.map { it.name }).containsExactlyElementsIn(expected.reversed()).inOrder()
            for (direction in listOf(true, false)) {
                val first = FileListing.items(items, FileSort(key, direction, foldersFirst = true), true)
                assertThat(first.first()).isEqualTo(folder)
            }
        }
    }

    @Test fun `中文按中文排序规则排列`() {
        val items = listOf(file("/张.txt"), file("/李.txt"), file("/陈.txt"))
        val sorted = FileListing.items(items, FileSort(FileSortKey.NAME, true, false), true)
        assertThat(sorted.map { it.name }).containsExactly("陈.txt", "李.txt", "张.txt").inOrder()
    }

    @Test fun `隐藏文件和面包屑`() {
        val hidden = file("/.secret")
        assertThat(FileListing.items(listOf(alpha, hidden), FileSort(), false)).containsExactly(alpha)
        assertThat(FileListing.items(listOf(alpha, hidden), FileSort(), true)).containsExactly(alpha, hidden)
        assertThat(FileListing.crumbs(location, "/")).containsExactly(FileCrumb("存储空间", "/"))
        assertThat(FileListing.crumbs(location, "/项目/文档")).containsExactly(
            FileCrumb("存储空间", "/"), FileCrumb("项目", "/项目"), FileCrumb("文档", "/项目/文档"),
        ).inOrder()
    }

    @Test fun `类型根据扩展名和媒体类型识别并标记文本预览`() {
        assertThat(FileKinds.kind(file("/a.PDF"))).isEqualTo(FileKind.PDF)
        assertThat(FileKinds.kind(file("/a.md"))).isEqualTo(FileKind.MD)
        assertThat(FileKinds.kind(file("/a", mime = "image/png"))).isEqualTo(FileKind.IMG)
        assertThat(FileKinds.kind(file("/a", mime = "text/html"))).isEqualTo(FileKind.WEB)
        assertThat(FileKinds.kind(file("/a", mime = "application/vnd.ms-excel"))).isEqualTo(FileKind.SHEET)
        assertThat(FileKinds.kind(folder)).isEqualTo(FileKind.FOLDER)
        assertThat(FileKinds.isTextPreviewable(file("/a.kt"))).isTrue()
        assertThat(FileKinds.isTextPreviewable(file("/a", mime = "text/plain"))).isTrue()
        assertThat(FileKinds.isTextPreviewable(file("/a.pdf"))).isFalse()
        assertThat(FileKinds.isTextPreviewable(folder)).isFalse()
    }

    @Test fun `文件名校验按 UTF-8 字节数和非法字符判断`() {
        for (name in listOf("", "   ", ".", "..", "a/b", "a\\b", "a\u0000b", "中".repeat(86))) {
            assertThat(FileNames.isValid(name)).isFalse()
        }
        for (name in listOf(".gitignore", "中文.md", "中".repeat(85), "a".repeat(255))) {
            assertThat(FileNames.isValid(name)).isTrue()
        }
        assertThat(FileNames.renameSelectionEnd("report.final.md")).isEqualTo("report.final".length)
        assertThat(FileNames.renameSelectionEnd(".gitignore")).isEqualTo(".gitignore".length)
        assertThat(FileNames.renameSelectionEnd("notes")).isEqualTo("notes".length)
        assertThat(FileNames.renameSelectionEnd("name.")).isEqualTo("name.".length)
    }
}
