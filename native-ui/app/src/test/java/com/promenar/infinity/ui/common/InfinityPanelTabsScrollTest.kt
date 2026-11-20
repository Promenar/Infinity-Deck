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

    @Test
    fun `指示条按整数页位返回对应文字区间并扣除左右内边距`() {
        val bounds = mapOf(0 to (0 to 100), 1 to (100 to 140), 2 to (240 to 80))

        assertThat(panelTabIndicatorBounds(bounds, position = 0f, padPx = 12)).isEqualTo(12f to 76f)
        assertThat(panelTabIndicatorBounds(bounds, position = 1f, padPx = 12)).isEqualTo(112f to 116f)
        assertThat(panelTabIndicatorBounds(bounds, position = 2f, padPx = 12)).isEqualTo(252f to 56f)
    }

    @Test
    fun `指示条在不等宽相邻页签中点按线性插值对齐`() {
        val bounds = mapOf(0 to (0 to 100), 1 to (100 to 140))

        assertThat(panelTabIndicatorBounds(bounds, position = 0.5f, padPx = 12)).isEqualTo(62f to 96f)
    }

    @Test
    fun `指示条越界与缺失邻项边界处理`() {
        val bounds = mapOf(0 to (10 to 80), 2 to (200 to 100))

        assertThat(panelTabIndicatorBounds(emptyMap(), position = 0f, padPx = 10)).isNull()
        assertThat(panelTabIndicatorBounds(bounds, position = -0.3f, padPx = 10)).isEqualTo(20f to 60f)
        assertThat(panelTabIndicatorBounds(bounds, position = 5f, padPx = 10)).isEqualTo(210f to 80f)
        assertThat(panelTabIndicatorBounds(bounds, position = 0.4f, padPx = 10)).isEqualTo(20f to 60f)
        assertThat(panelTabIndicatorBounds(bounds, position = 1.6f, padPx = 10)).isEqualTo(210f to 80f)
    }
}
