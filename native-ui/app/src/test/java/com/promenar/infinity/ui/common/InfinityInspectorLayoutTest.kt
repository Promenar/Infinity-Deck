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
        // 会话宽 = 窗口 − 检视器 − 右缘 8dp，至少 320dp。
        assertThat(infinityInspectorPlacement(688.dp, 360.dp)).isEqualTo(InfinityInspectorPlacement.Shift)
        assertThat(infinityInspectorPlacement(687.dp, 360.dp)).isEqualTo(InfinityInspectorPlacement.Float)
    }

    @Test fun `图钉松开时总是浮动，按下时只在会话够宽时固定`() {
        assertThat(infinityInspectorPlacement(814.dp, 360.dp, pinned = false)).isEqualTo(InfinityInspectorPlacement.Float)
        assertThat(infinityInspectorPlacement(1280.dp, 360.dp, pinned = false)).isEqualTo(InfinityInspectorPlacement.Float)
        assertThat(infinityInspectorPlacement(814.dp, 360.dp, pinned = true)).isEqualTo(InfinityInspectorPlacement.Shift)
        // 加宽后会话不足 320dp：图钉不可用，按下也只能浮动。
        assertThat(infinityInspectorCanPin(814.dp, 560.dp)).isFalse()
        assertThat(infinityInspectorPlacement(814.dp, 560.dp, pinned = true)).isEqualTo(InfinityInspectorPlacement.Float)
    }

    @Test fun `边缘条常驻时正文右缘距窗口右缘至少 64dp，两侧空白够时不加`() {
        // 折叠屏内屏会话区约 742dp：限宽 720dp 居中后右侧空白 11dp，正文自带 20dp，再加 33dp。
        assertThat(infinityInspectorStripInset(742.dp, 720.dp, 20.dp)).isEqualTo(33.dp)
        assertThat(infinityInspectorStripInset(800.dp, 720.dp, 20.dp)).isEqualTo(4.dp)
        // 空白与正文内边距合计已有 64dp：不加。
        assertThat(infinityInspectorStripInset(808.dp, 720.dp, 20.dp)).isEqualTo(0.dp)
        assertThat(infinityInspectorStripInset(1280.dp, 720.dp, 20.dp)).isEqualTo(0.dp)
        // 窗口比限宽还窄时没有居中空白，全部由正文末端留出。
        assertThat(infinityInspectorStripInset(700.dp, 720.dp, 20.dp)).isEqualTo(44.dp)
    }
}
