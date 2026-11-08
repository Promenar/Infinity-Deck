package com.promenar.infinity.ui.common

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InfinityInspectorLayoutTest {
    @Test fun `折叠屏内屏与平板启用检视器，竖屏与横屏手机仍按窄窗口`() {
        assertThat(infinityInspectorAvailable(814.dp, 898.dp)).isTrue()
        assertThat(infinityInspectorAvailable(1280.dp, 800.dp)).isTrue()
        assertThat(infinityInspectorAvailable(600.dp, 480.dp)).isTrue()
        assertThat(infinityInspectorAvailable(411.dp, 892.dp)).isFalse()
        assertThat(infinityInspectorAvailable(892.dp, 411.dp)).isFalse()
        assertThat(infinityInspectorAvailable(599.dp, 900.dp)).isFalse()
    }

    @Test fun `加宽至多 560dp，并给会话留出至少 48dp`() {
        assertThat(infinityInspectorWidth(814.dp, widened = false)).isEqualTo(360.dp)
        assertThat(infinityInspectorWidth(814.dp, widened = true)).isEqualTo(560.dp)
        assertThat(infinityInspectorWidth(600.dp, widened = true)).isEqualTo(552.dp)
        // 再窄也不比常规宽度更窄。
        assertThat(infinityInspectorWidth(380.dp, widened = true)).isEqualTo(360.dp)
    }

    @Test fun `折叠屏内屏默认宽度让位，加宽后会话不足 320dp 时浮在会话之上`() {
        assertThat(infinityInspectorPlacement(814.dp, 360.dp)).isEqualTo(InfinityInspectorPlacement.Shift)
        assertThat(infinityInspectorPlacement(814.dp, 560.dp)).isEqualTo(InfinityInspectorPlacement.Float)
        assertThat(infinityInspectorPlacement(1280.dp, 560.dp)).isEqualTo(InfinityInspectorPlacement.Shift)
        assertThat(infinityInspectorPlacement(704.dp, 360.dp)).isEqualTo(InfinityInspectorPlacement.Shift)
        assertThat(infinityInspectorPlacement(703.dp, 360.dp)).isEqualTo(InfinityInspectorPlacement.Float)
    }

    @Test fun `图钉松开时总是浮动，按下时只在会话够宽时固定`() {
        assertThat(infinityInspectorPlacement(814.dp, 360.dp, pinned = false)).isEqualTo(InfinityInspectorPlacement.Float)
        assertThat(infinityInspectorPlacement(1280.dp, 360.dp, pinned = false)).isEqualTo(InfinityInspectorPlacement.Float)
        assertThat(infinityInspectorPlacement(814.dp, 360.dp, pinned = true)).isEqualTo(InfinityInspectorPlacement.Shift)
        // 加宽后会话不足 320dp：图钉不可用，按下也只能浮动。
        assertThat(infinityInspectorCanPin(814.dp, 560.dp)).isFalse()
        assertThat(infinityInspectorPlacement(814.dp, 560.dp, pinned = true)).isEqualTo(InfinityInspectorPlacement.Float)
    }

    @Test fun `边缘条常驻时内容列右缘距窗口右缘至少 64dp，两侧空白够时不让`() {
        // 折叠屏内屏会话区约 742dp：限宽 720dp 的内容列两侧各只剩 11dp，整条让出 64dp。
        assertThat(infinityInspectorStripReserve(742.dp, 720.dp)).isEqualTo(64.dp)
        // 两侧空白介于 32 与 64dp 之间：让出的宽度加上居中余量正好 64dp。
        assertThat(infinityInspectorStripReserve(800.dp, 720.dp)).isEqualTo(48.dp)
        // 两侧空白都已不少于 64dp：不让。
        assertThat(infinityInspectorStripReserve(848.dp, 720.dp)).isEqualTo(0.dp)
        assertThat(infinityInspectorStripReserve(1280.dp, 720.dp)).isEqualTo(0.dp)
    }
}
