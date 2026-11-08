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
}
