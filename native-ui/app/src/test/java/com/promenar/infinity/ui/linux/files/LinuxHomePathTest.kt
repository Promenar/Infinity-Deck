package com.promenar.infinity.ui.linux.files

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LinuxHomePathTest {
    @Test
    fun `主目录位置内路径换算为 Linux 绝对路径`() {
        assertThat(linuxAbsolutePath("/")).isEqualTo("/root")
        assertThat(linuxAbsolutePath("/project/src")).isEqualTo("/root/project/src")
        assertThat(linuxAbsolutePath("/notes.md/")).isEqualTo("/root/notes.md")
    }

    @Test
    fun `只有主目录内的绝对路径能换算回位置内路径`() {
        assertThat(linuxHomeRelativePath("/root")).isEqualTo("/")
        assertThat(linuxHomeRelativePath("/root/")).isEqualTo("/")
        assertThat(linuxHomeRelativePath("/root/project")).isEqualTo("/project")
        assertThat(linuxHomeRelativePath("/rootkit")).isNull()
        assertThat(linuxHomeRelativePath("/etc")).isNull()
    }
}
