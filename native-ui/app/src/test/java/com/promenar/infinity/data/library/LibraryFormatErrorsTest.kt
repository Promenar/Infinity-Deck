package com.promenar.infinity.data.library

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.rag.DocumentFormatError
import org.junit.Test

class LibraryFormatErrorsTest {
    @Test fun `每个格式错误写入队列的错误码都有说明文字`() {
        DocumentFormatError.entries.forEach { error ->
            assertThat(LibraryFormatErrors.resourceFor(LibraryFormatErrors.codeFor(error))).isNotNull()
        }
    }

    @Test fun `其它错误不映射`() {
        assertThat(LibraryFormatErrors.resourceFor("kg.failed.parse")).isNull()
        assertThat(LibraryFormatErrors.resourceFor("网络中断")).isNull()
        assertThat(LibraryFormatErrors.resourceFor(null)).isNull()
    }
}
