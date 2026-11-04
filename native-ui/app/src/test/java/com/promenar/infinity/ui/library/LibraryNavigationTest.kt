package com.promenar.infinity.ui.library

import androidx.compose.runtime.saveable.SaverScope
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.files.FileRef
import com.promenar.infinity.navigation.NavDestinations
import com.promenar.infinity.ui.AppTab
import com.promenar.infinity.ui.MainTabsState
import org.junit.jupiter.api.Test

class LibraryNavigationTest {
    private fun state(tab: AppTab = AppTab.LIBRARY) = MainTabsState(tab, emptyList(), NavDestinations.THEME_CONFIG)

    @Test
    fun `页面键往返解析，浏览页保留位置与带分隔符的路径`() {
        val ref = FileRef("external:m1", "/产品 调研/2026|草稿")
        assertThat(LibraryPageKeys.parse(LibraryPageKeys.browse(ref))).isEqualTo(LibraryPage.Browse(ref))
        assertThat(LibraryPageKeys.parse(LibraryPageKeys.document("d1"))).isEqualTo(LibraryPage.Document("d1"))
        assertThat(LibraryPageKeys.parse(LibraryPageKeys.fragmentSession("s1"))).isEqualTo(LibraryPage.FragmentSession("s1"))
        assertThat(LibraryPageKeys.parse(LibraryPageKeys.MEMORY)).isEqualTo(LibraryPage.Memory)
        assertThat(LibraryPageKeys.parse("browse:|/x")).isEqualTo(LibraryPage.Overview)
        assertThat(LibraryPageKeys.parse("unknown")).isEqualTo(LibraryPage.Overview)
    }

    @Test
    fun `内屏空栈显示概览，页内推入保留概览为根，返回不弹出根页面`() {
        val state = state()
        val navigator = LibraryNavigator(state, twoPane = true)
        assertThat(navigator.stack).containsExactly(LibraryPageKeys.OVERVIEW)

        navigator.push(LibraryPageKeys.document("d1"))
        assertThat(navigator.stack).containsExactly(LibraryPageKeys.OVERVIEW, "doc:d1").inOrder()
        assertThat(navigator.handlesBack).isTrue()

        navigator.handleBack()
        assertThat(navigator.stack).containsExactly(LibraryPageKeys.OVERVIEW)
        assertThat(navigator.handlesBack).isFalse()
        assertThat(navigator.back()).isFalse()
    }

    @Test
    fun `左列另选一项替换根页面，外屏首页上的分区按推入处理`() {
        val state = state()
        val inner = LibraryNavigator(state, twoPane = true)
        inner.push(LibraryPageKeys.document("d1"))
        inner.openSection(LibraryPageKeys.MEMORY)
        assertThat(inner.stack).containsExactly(LibraryPageKeys.MEMORY)

        val outer = LibraryNavigator(state(), twoPane = false)
        assertThat(outer.stack).isEmpty()
        outer.openSection(LibraryPageKeys.FILES)
        outer.push(LibraryPageKeys.browse(FileRef("kb", "/")))
        assertThat(outer.stack).hasSize(2)
        outer.handleBack()
        outer.handleBack()
        assertThat(outer.stack).isEmpty()
        assertThat(outer.handlesBack).isFalse()
    }

    @Test
    fun `关闭问资料时连同压在上面的页面一起移除`() {
        val state = state()
        val navigator = LibraryNavigator(state, twoPane = true)
        navigator.push(LibraryPageKeys.ASK)
        navigator.push(LibraryPageKeys.document("d1"))
        navigator.dropAsk()
        assertThat(navigator.stack).containsExactly(LibraryPageKeys.OVERVIEW)
    }

    @Test
    fun `展开时系统返回先弹出页内层级再还原两列，切换页签也还原`() {
        val state = state()
        val navigator = LibraryNavigator(state, twoPane = true)
        navigator.push(LibraryPageKeys.document("d1"))
        navigator.toggleExpanded()
        assertThat(navigator.expanded).isTrue()

        navigator.handleBack()
        assertThat(navigator.expanded).isTrue()
        assertThat(navigator.handlesBack).isTrue()
        navigator.handleBack()
        assertThat(navigator.expanded).isFalse()

        navigator.toggleExpanded()
        state.selectTab(AppTab.CHAT)
        assertThat(state.libraryExpanded).isFalse()
        // 外屏不存在展开态。
        state.selectTab(AppTab.LIBRARY)
        state.toggleLibraryExpanded()
        assertThat(LibraryNavigator(state, twoPane = false).expanded).isFalse()
    }

    @Test
    fun `从设置跳到资料页的长期记忆`() {
        val state = state(AppTab.SETTINGS)
        state.openLibrary(LibraryPageKeys.MEMORY)
        assertThat(state.selectedTab).isEqualTo(AppTab.LIBRARY)
        assertThat(state.libraryStack).containsExactly(LibraryPageKeys.MEMORY)
    }

    @Test
    fun `资料页层级与展开态随状态保存恢复，旧格式缺字段时取默认值`() {
        val state = state()
        state.pushLibrary(LibraryPageKeys.GRAPH)
        state.toggleLibraryExpanded()
        val scope = SaverScope { true }
        val saved = with(MainTabsState.Saver) { scope.save(state) }!!
        val restored = MainTabsState.Saver.restore(saved)!!
        assertThat(restored.libraryStack).containsExactly(LibraryPageKeys.GRAPH)
        assertThat(restored.libraryExpanded).isTrue()

        @Suppress("UNCHECKED_CAST")
        val legacy = (saved as List<Any>).take(5)
        val fromLegacy = MainTabsState.Saver.restore(legacy)!!
        assertThat(fromLegacy.libraryStack).isEmpty()
        assertThat(fromLegacy.libraryExpanded).isFalse()
    }
}
