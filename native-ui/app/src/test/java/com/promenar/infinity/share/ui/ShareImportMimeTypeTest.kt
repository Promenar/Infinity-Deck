package com.promenar.infinity.share.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShareImportMimeTypeTest {
    @Test
    fun `已知MIME紧凑显示且未知值保持原样`() {
        assertThat(compactMimeType("APPLICATION/PDF")).isEqualTo("PDF")
        assertThat(
            compactMimeType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ),
        ).isEqualTo("DOCX")
        assertThat(compactMimeType("text/plain")).isEqualTo("TXT")
        assertThat(compactMimeType("text/html")).isEqualTo("HTML")
        assertThat(compactMimeType("application/x-custom-format"))
            .isEqualTo("application/x-custom-format")
        assertThat(compactMimeType(null)).isNull()
    }

    @Test
    fun `应用不解析的格式优先用扩展名作类型标签`() {
        assertThat(shareFileTypeLabel("application/pdf", "报告.pdf")).isEqualTo("PDF")
        assertThat(shareFileTypeLabel("application/vnd.sqlite3", "chat_backup.sqlite")).isEqualTo("SQLITE")
        assertThat(shareFileTypeLabel("application/epub+zip", "设计模式.epub")).isEqualTo("EPUB")
        assertThat(shareFileTypeLabel("application/octet-stream", "README")).isEqualTo("application/octet-stream")
        assertThat(shareFileTypeLabel(null, "archive.tar.gz")).isEqualTo("GZ")
        assertThat(shareFileTypeLabel(null, "no-extension")).isNull()
    }

    @Test
    fun `存储空间路径按层级显示`() {
        assertThat(knowledgeFolderLabel("/资料/财务")).isEqualTo("资料 / 财务")
        assertThat(knowledgeFolderLabel("/资料/")).isEqualTo("资料")
    }
}
