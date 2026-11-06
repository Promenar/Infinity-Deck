package com.promenar.infinity.share.core

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.rag.DocumentReferenceExtractor
import org.junit.Test

class AndroidSafImportRequestFactoryTest {
    @Test
    fun `HTML声明与扩展名统一解析为text html`() {
        assertThat(AndroidSafImportRequestFactory.resolveMime("text/html; charset=UTF-8", "page.bin"))
            .isEqualTo("text/html")
        assertThat(AndroidSafImportRequestFactory.resolveMime("application/octet-stream", "page.html"))
            .isEqualTo("text/html")
        assertThat(AndroidSafImportRequestFactory.resolveMime(null, "PAGE.HTM"))
            .isEqualTo("text/html")
    }

    @Test
    fun `Word支持DOCX与旧版DOC并按扩展名推断MIME`() {
        assertThat(AndroidSafImportRequestFactory.resolveMime("application/msword", "legacy.doc"))
            .isEqualTo("application/msword")
        assertThat(AndroidSafImportRequestFactory.resolveMime(null, "legacy.doc"))
            .isEqualTo("application/msword")
        assertThat(AndroidSafImportRequestFactory.resolveMime(null, "modern.docx"))
            .isEqualTo(DocumentReferenceExtractor.DOCX_MIME)
    }
}
