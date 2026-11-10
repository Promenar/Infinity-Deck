package com.promenar.infinity.data.document.external

import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExternalDocumentIntentsTest {
    @Test
    fun `打开方式按类型或扩展名取出 Markdown 链接`() {
        val typed = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://files/document/42"), "text/markdown")
        val byName = Intent(Intent.ACTION_VIEW).setData(Uri.parse("file:///sdcard/Download/notes.md"))
        val generic = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://files/root/周报.markdown"), "application/octet-stream")

        assertThat(ExternalDocumentIntents.markdownUri(typed)).isEqualTo(typed.data)
        assertThat(ExternalDocumentIntents.markdownUri(byName)).isEqualTo(byName.data)
        assertThat(ExternalDocumentIntents.markdownUri(generic)).isEqualTo(generic.data)
    }

    @Test
    fun `单个分享的 Markdown 交给查看器`() {
        val uri = Uri.parse("content://files/root/notes.md")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
        assertThat(ExternalDocumentIntents.markdownUri(send)).isEqualTo(uri)
    }

    @Test
    fun `其它格式 多文件与非本地链接仍走原有流程`() {
        val pdf = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, Uri.parse("content://files/a.pdf"))
        val multiple = Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/markdown")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(Uri.parse("content://files/a.md"), Uri.parse("content://files/b.md")))
        val web = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("https://example.com/a.md"), "text/markdown")
        val text = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "# 标题")

        assertThat(ExternalDocumentIntents.markdownUri(pdf)).isNull()
        assertThat(ExternalDocumentIntents.markdownUri(multiple)).isNull()
        assertThat(ExternalDocumentIntents.markdownUri(web)).isNull()
        assertThat(ExternalDocumentIntents.markdownUri(text)).isNull()
        assertThat(ExternalDocumentIntents.markdownUri(null)).isNull()
    }
}
