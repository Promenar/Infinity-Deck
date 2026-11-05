package com.promenar.infinity.ui.files

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.files.FileLocation
import com.promenar.infinity.domain.files.FileLocationCapabilities
import com.promenar.infinity.domain.files.FileLocationKind
import com.promenar.infinity.ui.files.state.FileCrumb
import com.promenar.infinity.ui.files.state.FileManagerUiState
import org.junit.jupiter.api.Test

class FileListHierarchyTest {
    private val caps = FileLocationCapabilities(writable = true, deletable = true, archive = true)
    private val library = FileLocation("kb", FileLocationKind.KNOWLEDGE_BASE, "资料库", caps)
    private val sessions = FileLocation("sessions", FileLocationKind.SESSION_WORKSPACE, "会话工作区", caps)
    private val session = FileLocation("session:s1", FileLocationKind.SESSION_WORKSPACE, "示例会话", caps, sessionId = "s1")

    private fun at(location: FileLocation, vararg crumbs: String) =
        FileManagerUiState(location = location, crumbs = crumbs.map { FileCrumb(it, "/") })

    @Test
    fun `同一位置内属于同一层级链`() {
        assertThat(sameHierarchy(at(library, "资料库"), at(library, "资料库", "产品"))).isTrue()
    }

    @Test
    fun `从会话工作区进入会话或返回都按层级滑动`() {
        val list = at(sessions, "会话工作区")
        val inside = at(session, "会话工作区", "示例会话")
        assertThat(sameHierarchy(list, inside)).isTrue()
        assertThat(sameHierarchy(inside, list)).isTrue()
        // 面包屑层数随之加深，进入向右、返回向左。
        assertThat(inside.crumbs.size).isGreaterThan(list.crumbs.size)
    }

    @Test
    fun `换到其它位置交叉淡入`() {
        assertThat(sameHierarchy(at(library, "资料库"), at(sessions, "会话工作区"))).isFalse()
        // 会话作用域里会话工作区本身就是根位置，面包屑只有一层，不与汇总列表相连。
        assertThat(sameHierarchy(at(sessions, "会话工作区"), at(session, "示例会话"))).isFalse()
    }
}
