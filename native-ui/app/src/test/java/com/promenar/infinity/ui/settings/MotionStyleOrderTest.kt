package com.promenar.infinity.ui.settings

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.theme.ComposerGlowStyle
import com.promenar.infinity.ui.theme.EmptyChatStyle
import com.promenar.infinity.ui.theme.GenerationIndicatorStyle
import org.junit.Test

class MotionStyleOrderTest {
    @Test
    fun `三个动效列表把默认样式排第一，其余保持枚举相对顺序`() {
        assertThat(ComposerGlowStyle.entries.defaultFirst(ComposerGlowStyle.MATERIAL_ONLY)).containsExactly(
            ComposerGlowStyle.MATERIAL_ONLY,
            ComposerGlowStyle.AURORA,
            ComposerGlowStyle.BREATHE,
            ComposerGlowStyle.MESH,
            ComposerGlowStyle.BOTTOM_STRIP,
            ComposerGlowStyle.SWEEP_GRID,
        ).inOrder()
        assertThat(GenerationIndicatorStyle.entries.defaultFirst(GenerationIndicatorStyle.MATRIX)).containsExactly(
            GenerationIndicatorStyle.MATRIX,
            GenerationIndicatorStyle.WAVE,
            GenerationIndicatorStyle.PORTS,
            GenerationIndicatorStyle.STACKED,
            GenerationIndicatorStyle.BAYS,
            GenerationIndicatorStyle.INFINITY_LINE,
            GenerationIndicatorStyle.INFINITY_LED,
        ).inOrder()
        assertThat(EmptyChatStyle.entries.defaultFirst(EmptyChatStyle.LED_MORPH)).containsExactly(
            EmptyChatStyle.LED_MORPH,
            EmptyChatStyle.FLOW,
            EmptyChatStyle.LED_WORDMARK,
            EmptyChatStyle.MOBIUS,
            EmptyChatStyle.MOBIUS_LED,
            EmptyChatStyle.MORPH,
            EmptyChatStyle.LED_GLOBE,
        ).inOrder()
    }

    @Test
    fun `只改列表排列，枚举顺序不变`() {
        // 偏好按小写名称存储，与序号无关；枚举首项仍是原来的样式。
        assertThat(ComposerGlowStyle.entries.first()).isEqualTo(ComposerGlowStyle.AURORA)
        assertThat(GenerationIndicatorStyle.entries.first()).isEqualTo(GenerationIndicatorStyle.WAVE)
        assertThat(EmptyChatStyle.entries.first()).isEqualTo(EmptyChatStyle.FLOW)
    }
}
