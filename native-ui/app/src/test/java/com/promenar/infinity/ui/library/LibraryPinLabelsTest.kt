package com.promenar.infinity.ui.library

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.files.FileLocation
import com.promenar.infinity.domain.files.FileLocationCapabilities
import com.promenar.infinity.domain.files.FileLocationKind
import com.promenar.infinity.domain.files.FileRef
import org.junit.Test

class LibraryPinLabelsTest {
    private val project = FileLocation("workspace:p", FileLocationKind.PROJECT, "示例项目", FileLocationCapabilities(true, true, true))
    private val sessions = FileLocation("sessions", FileLocationKind.SESSION_WORKSPACE, "会话工作区", FileLocationCapabilities(false, false, false))
    private val locations = LibraryLocations(
        locations = listOf(project, sessions),
        sessionNames = mapOf("session:s" to "会话甲"),
    )

    @Test fun `位置列表里的文件夹以位置名为副标题`() {
        assertThat(pinLabels(FileRef(project.id, "/docs/notes"), locations)).isEqualTo("notes" to "示例项目")
        assertThat(pinLabels(FileRef(project.id, "/"), locations)).isEqualTo("示例项目" to null)
    }

    @Test fun `会话文件夹以会话名和会话工作区标注`() {
        assertThat(pinLabels(FileRef("session:s", "/"), locations)).isEqualTo("会话甲" to "会话工作区")
        assertThat(pinLabels(FileRef("session:s", "/docs"), locations)).isEqualTo("docs" to "会话工作区 · 会话甲")
    }

    @Test fun `找不到所属位置时只显示文件夹名`() {
        assertThat(pinLabels(FileRef("session:gone", "/docs"), locations)).isEqualTo("docs" to null)
    }
}
