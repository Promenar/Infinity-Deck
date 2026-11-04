package com.promenar.infinity.ui.files

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.files.FileRef
import org.junit.jupiter.api.Test

class FileListKeyTest {
    @Test
    fun `会话工作区汇总根下各会话同为根路径时列表键仍互不相同`() {
        val children = listOf(FileRef("session:a", "/"), FileRef("session:b", "/"), FileRef("session:c", "/"))
        assertThat(children.map { it.listKey() }.toSet()).hasSize(children.size)
    }

    @Test
    fun `同一位置内列表键由路径区分且稳定`() {
        assertThat(FileRef("kb", "/a").listKey()).isEqualTo(FileRef("kb", "/a").listKey())
        assertThat(FileRef("kb", "/a").listKey()).isNotEqualTo(FileRef("kb", "/b").listKey())
    }
}
