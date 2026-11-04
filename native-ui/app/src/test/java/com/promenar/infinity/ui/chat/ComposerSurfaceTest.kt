package com.promenar.infinity.ui.chat

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ComposerSurfaceTest {

    @Test
    fun `环境光柔光从运行坞点阵屏中心漫开`() {
        val anchor = composerWashAnchor(Offset(880f, 40f), width = 1000f, height = 400f, dp = 2f)
        assertThat(anchor).isEqualTo(Offset(880f, 40f))
    }

    @Test
    fun `点阵屏中心落在浮岛外时收回浮岛边界内`() {
        val anchor = composerWashAnchor(Offset(1200f, -10f), width = 1000f, height = 400f, dp = 2f)
        assertThat(anchor).isEqualTo(Offset(1000f, 0f))
    }

    @Test
    fun `没有点阵屏时柔光从输入行右侧漫开`() {
        val anchor = composerWashAnchor(Offset.Unspecified, width = 1000f, height = 400f, dp = 2f)
        // 距右缘 90dp，距底边 68dp。
        assertThat(anchor.x).isEqualTo(1000f - 90 * 2f)
        assertThat(anchor.y).isEqualTo(400f - 136f)
    }

    @Test
    fun `窄浮岛上柔光中心不越过左半与上沿三成`() {
        val anchor = composerWashAnchor(Offset.Unspecified, width = 200f, height = 100f, dp = 3f)
        assertThat(anchor.x).isEqualTo(100f)
        assertThat(anchor.y).isWithin(1e-3f).of(30f)
    }
}
