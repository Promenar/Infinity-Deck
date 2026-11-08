package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ComposerGeometryTest {

    @Test
    fun `发送键与浮岛外圈同心`() {
        val (x, y) = composerStopCenter(width = 1000f, height = 300f, dp = 2f)
        // 圆心距右边缘、底边都等于浮岛圆角，发送键半径加工具行留白正好是圆角。
        assertThat(1000f - x).isEqualTo(ComposerIslandCorner.value * 2f)
        assertThat(300f - y).isEqualTo(ComposerIslandCorner.value * 2f)
        assertThat(ChatCompactIconSize.value / 2 + ComposerToolbarInset.value).isEqualTo(ComposerIslandCorner.value)
    }

    @Test
    fun `各档浮岛外圈都与发送键同心，默认档等于正式界面`() {
        ComposerIslandVariant.entries.forEach { variant ->
            val style = variant.style
            val sendRadius = style.sendCorner?.value ?: (ChatCompactIconSize.value / 2)
            assertThat(style.corner.value).isEqualTo(sendRadius + ComposerToolbarInset.value)
        }
        assertThat(ComposerIslandVariant.CURRENT.style).isEqualTo(ComposerIslandStyle())
        assertThat(ComposerIslandStyle().corner).isEqualTo(ComposerIslandCorner)
    }

    @Test
    fun `输入正文左缘与加号笔画左缘对齐`() {
        // 8dp 工具行留白 + (40 - 22) / 2 图标留白 + 22 × 5/24 起笔留白。
        assertThat(ComposerTextInset.value).isWithin(1e-3f).of(8f + 9f + 22f * 5f / 24f)
    }
}
