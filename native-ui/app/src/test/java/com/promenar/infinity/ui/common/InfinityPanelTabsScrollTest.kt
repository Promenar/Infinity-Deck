package com.promenar.infinity.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 选中页签滚入视野：只在越出视野时滚动，并与视野边缘留出行首尾留白。 */
class InfinityPanelTabsScrollTest {
    @Test
    fun `已在视野内时不滚动`() {
        assertThat(panelTabScrollTarget(left = 4, right = 100, scroll = 0, viewport = 360, edge = 4)).isNull()
        assertThat(panelTabScrollTarget(left = 200, right = 356, scroll = 0, viewport = 360, edge = 4)).isNull()
    }

    @Test
    fun `右侧越界时滚到页签右缘加留白`() {
        assertThat(panelTabScrollTarget(left = 300, right = 420, scroll = 0, viewport = 360, edge = 4)).isEqualTo(64)
    }

    @Test
    fun `左侧越界时滚到页签左缘减留白且不小于零`() {
        assertThat(panelTabScrollTarget(left = 120, right = 200, scroll = 150, viewport = 360, edge = 4)).isEqualTo(116)
        assertThat(panelTabScrollTarget(left = 2, right = 80, scroll = 40, viewport = 360, edge = 4)).isEqualTo(0)
    }

    @Test
    fun `视野尚未测量时不滚动`() {
        assertThat(panelTabScrollTarget(left = 300, right = 420, scroll = 0, viewport = 0, edge = 4)).isNull()
    }
}
