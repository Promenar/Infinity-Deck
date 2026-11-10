package com.promenar.infinity.data.document.external

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExternalMarkdownTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `Markdown 类型直接接受且忽略大小写与参数`() {
        assertThat(ExternalMarkdown.accepts("text/markdown", null)).isTrue()
        assertThat(ExternalMarkdown.accepts("TEXT/X-MARKDOWN; charset=utf-8", "notes.txt")).isTrue()
    }

    @Test
    fun `通用类型或缺失类型时按扩展名判断`() {
        assertThat(ExternalMarkdown.accepts(null, "/storage/emulated/0/Download/周报.md")).isTrue()
        assertThat(ExternalMarkdown.accepts("application/octet-stream", "README.MARKDOWN")).isTrue()
        assertThat(ExternalMarkdown.accepts("text/plain", "a.Md")).isTrue()
        assertThat(ExternalMarkdown.accepts("*/*", "notes.txt")).isFalse()
        assertThat(ExternalMarkdown.accepts(null, null)).isFalse()
    }

    @Test
    fun `明确声明为其它类型时不按扩展名接管`() {
        assertThat(ExternalMarkdown.accepts("text/html", "page.md")).isFalse()
        assertThat(ExternalMarkdown.accepts("application/pdf", "doc.markdown")).isFalse()
    }

    @Test
    fun `只有扩展名的文件名不算 Markdown 文件`() {
        assertThat(ExternalMarkdown.hasMarkdownName(".md")).isFalse()
        assertThat(ExternalMarkdown.hasMarkdownName("dir/.markdown")).isFalse()
        assertThat(ExternalMarkdown.hasMarkdownName("dir.md/file")).isFalse()
    }

    @Test
    fun `补齐 Markdown 扩展名`() {
        assertThat(ExternalMarkdown.ensureMarkdownName("周报")).isEqualTo("周报.md")
        assertThat(ExternalMarkdown.ensureMarkdownName("周报.markdown")).isEqualTo("周报.markdown")
    }

    @Test
    fun `file 链接拒绝应用私有目录及其符号链接`() {
        val privateRoot = temporary.newFolder("data", "app")
        val secret = File(privateRoot, "databases/secret.md").apply { parentFile!!.mkdirs(); writeText("x") }
        val public = temporary.newFolder("public")
        val shared = File(public, "notes.md").apply { writeText("# notes") }
        val link = File(public, "link.md")
        Files.createSymbolicLink(link.toPath(), secret.toPath())

        assertThat(isAllowedExternalFile(shared, listOf(privateRoot))).isTrue()
        assertThat(isAllowedExternalFile(secret, listOf(privateRoot))).isFalse()
        assertThat(isAllowedExternalFile(File(public, "../data/app/databases/secret.md"), listOf(privateRoot))).isFalse()
        assertThat(isAllowedExternalFile(link, listOf(privateRoot))).isFalse()
        assertThat(isAllowedExternalFile(privateRoot, listOf(privateRoot))).isFalse()
    }

    @Test
    fun `私有目录同名前缀的兄弟目录不受影响`() {
        val privateRoot = temporary.newFolder("app")
        val sibling = temporary.newFolder("app-public")
        val file = File(sibling, "a.md").apply { writeText("a") }
        assertThat(isAllowedExternalFile(file, listOf(privateRoot))).isTrue()
    }
}
