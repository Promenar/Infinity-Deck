package com.promenar.infinity.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackGuardTest {
    @Test
    fun `栈顶页面且下面还有页面时返回`() {
        assertThat(canPopFrom(currentEntryId = "terminal", entryId = "terminal", hasPrevious = true)).isTrue()
    }

    @Test
    fun `退场动画中的页面再次返回不弹出下面的页面`() {
        // 终端页已弹出，栈顶回到主标签页；终端页仍在退场动画里，又收到一次返回（连点或硬件回车）。
        assertThat(canPopFrom(currentEntryId = "main", entryId = "terminal", hasPrevious = false)).isFalse()
        assertThat(canPopFrom(currentEntryId = "settings", entryId = "terminal", hasPrevious = true)).isFalse()
    }

    @Test
    fun `起始页不弹出，返回栈不会被清空`() {
        assertThat(canPopFrom(currentEntryId = "main", entryId = "main", hasPrevious = false)).isFalse()
        assertThat(canPopFrom(currentEntryId = null, entryId = "main", hasPrevious = false)).isFalse()
    }
}
