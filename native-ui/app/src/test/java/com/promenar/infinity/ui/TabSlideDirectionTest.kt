package com.promenar.infinity.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TabSlideDirectionTest {
    @Test
    fun `按会话、资料库、设置的顺序判断滑动方向`() {
        assertThat(tabSlideDirection(AppTab.CHAT, AppTab.LIBRARY)).isEqualTo(1)
        assertThat(tabSlideDirection(AppTab.CHAT, AppTab.SETTINGS)).isEqualTo(1)
        assertThat(tabSlideDirection(AppTab.LIBRARY, AppTab.SETTINGS)).isEqualTo(1)
        assertThat(tabSlideDirection(AppTab.SETTINGS, AppTab.LIBRARY)).isEqualTo(-1)
        // 跨两个标签仍只是一个方向，不放大位移。
        assertThat(tabSlideDirection(AppTab.SETTINGS, AppTab.CHAT)).isEqualTo(-1)
        assertThat(tabSlideDirection(AppTab.LIBRARY, AppTab.LIBRARY)).isEqualTo(0)
    }

    @Test
    fun `导航轨纵向滑动，底栏横向滑动`() {
        assertThat(tabSlideVertical(navigationRail = true)).isTrue()
        assertThat(tabSlideVertical(navigationRail = false)).isFalse()
        // 导航轨的阈值与布局切换一致：600dp 起为导航轨。
        assertThat(tabSlideVertical(shouldUseNavigationRail(600.dp))).isTrue()
        assertThat(tabSlideVertical(shouldUseNavigationRail(599.dp))).isFalse()
    }

    @Test
    fun `资料次级页不显示底栏，资料首页、两列与其它标签照常显示`() {
        assertThat(bottomDockVisible(AppTab.LIBRARY, emptyList(), libraryTwoPane = false)).isTrue()
        assertThat(bottomDockVisible(AppTab.LIBRARY, listOf("doc:1"), libraryTwoPane = false)).isFalse()
        assertThat(bottomDockVisible(AppTab.LIBRARY, listOf("files", "doc:1"), libraryTwoPane = false)).isFalse()
        // 两列时左列就是资料导航，底栏布局下也不隐藏。
        assertThat(bottomDockVisible(AppTab.LIBRARY, listOf("overview", "doc:1"), libraryTwoPane = true)).isTrue()
        // 离开资料页时保留的资料层级不影响其它标签。
        assertThat(bottomDockVisible(AppTab.CHAT, listOf("doc:1"), libraryTwoPane = false)).isTrue()
        assertThat(bottomDockVisible(AppTab.SETTINGS, listOf("doc:1"), libraryTwoPane = false)).isTrue()
    }

    private val Int.dp get() = androidx.compose.ui.unit.Dp(toFloat())
}
