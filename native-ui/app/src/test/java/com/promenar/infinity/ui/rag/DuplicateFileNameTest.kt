package com.promenar.infinity.ui.rag

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DuplicateFileNameTest {

    private val english: (String) -> String = { "$it - Copy" }

    @Test
    fun `副本后缀插在扩展名之前`() {
        assertThat(duplicateFileName("notes.md", english)).isEqualTo("notes - Copy.md")
        assertThat(duplicateFileName("archive.tar.gz", english)).isEqualTo("archive.tar - Copy.gz")
    }

    @Test
    fun `没有扩展名或以点开头的文件不追加多余的点`() {
        assertThat(duplicateFileName("README", english)).isEqualTo("README - Copy")
        assertThat(duplicateFileName(".env", english)).isEqualTo(".env - Copy")
    }
}
