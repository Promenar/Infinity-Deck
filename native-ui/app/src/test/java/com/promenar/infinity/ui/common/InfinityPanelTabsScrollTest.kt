package com.promenar.infinity.ui.common

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
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

    @Test
    fun `容器宽度动画期间切页直接对齐零偏移避免与横滑动画竞态`() {
        assertThat(
            panelTabPagerSyncAction(
                tabsChanged = false,
                animateSelection = false,
                currentPage = 2,
                targetPage = 2,
                currentPageOffsetFraction = 0f,
                desiredPage = 3,
            ),
        ).isEqualTo(PanelTabPagerSyncAction.SNAP)
    }

    @Test
    fun `停留在目标页但残留非零偏移时立即归零修复错位`() {
        assertThat(
            panelTabPagerSyncAction(
                tabsChanged = false,
                animateSelection = true,
                currentPage = 3,
                targetPage = 3,
                currentPageOffsetFraction = -0.45f,
                desiredPage = 3,
            ),
        ).isEqualTo(PanelTabPagerSyncAction.SNAP)
        assertThat(
            panelTabPagerSyncAction(
                tabsChanged = false,
                animateSelection = true,
                currentPage = 3,
                targetPage = 3,
                currentPageOffsetFraction = 0f,
                desiredPage = 3,
            ),
        ).isEqualTo(PanelTabPagerSyncAction.NONE)
    }

    @Test
    fun `静止态存在非零页偏移且视口有效时触发自动归零`() {
        assertThat(shouldSnapIdlePagerOffset(isScrollInProgress = false, pageSize = 800, currentPageOffsetFraction = -0.35f)).isTrue()
        assertThat(shouldSnapIdlePagerOffset(isScrollInProgress = true, pageSize = 800, currentPageOffsetFraction = -0.35f)).isFalse()
        assertThat(shouldSnapIdlePagerOffset(isScrollInProgress = false, pageSize = 0, currentPageOffsetFraction = -0.35f)).isFalse()
        assertThat(shouldSnapIdlePagerOffset(isScrollInProgress = false, pageSize = 800, currentPageOffsetFraction = 0f)).isFalse()
    }

    @Test
    fun `六项及以上页签适度收紧水平内边距且二至五项保持标准留白`() {
        for (count in 2..5) {
            assertThat(panelTabHorizontalPadding(count)).isEqualTo(12.dp)
        }
        assertThat(panelTabHorizontalPadding(6)).isEqualTo(9.dp)
        assertThat(panelTabHorizontalPadding(7)).isEqualTo(9.dp)
    }

    @Test
    fun `面板页签使用绘制阶段滚动状态渲染三十二像素渐隐边避免首帧硬裁切`() {
        val relative = "src/main/java/com/promenar/infinity/ui/common/InfinityPanelTabs.kt"
        val file = Path.of("app").resolve(relative).takeIf { Files.exists(it) } ?: Path.of(relative)
        val source = Files.readString(file)
        assertThat(source).contains("PanelTabFadeWidth = 32.dp")
        assertThat(source).contains("state = scrollState")
        assertThat(source).doesNotContain("end = scrollState.canScrollForward")
    }
}
