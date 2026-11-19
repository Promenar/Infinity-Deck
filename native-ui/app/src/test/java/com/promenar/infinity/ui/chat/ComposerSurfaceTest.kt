package com.promenar.infinity.ui.chat

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.theme.ComposerSurfaceStyle
import com.promenar.infinity.ui.theme.InfinityPureWhite
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

    @Test
    fun `深色熏黑玻璃与暗黑省电实底脱离灰底并保留冷白浅色`() {
        val darkScheme = darkColorScheme()
        val frostedDark = resolveComposerIslandMaterial(darkScheme, dark = true, ComposerSurfaceStyle.FROSTED, layered = false)
        assertThat(frostedDark.container).isEqualTo(Color.Transparent)
        val darkTint = checkNotNull(frostedDark.frostedTint)
        assertThat(darkTint.alpha).isWithin(1e-3f).of(0.78f)
        assertThat(darkTint.red).isLessThan(0.05f)
        assertThat(darkTint.blue).isLessThan(0.05f)
        assertThat(frostedDark.topHighlight).isNotNull()

        val ambientDark = resolveComposerIslandMaterial(darkScheme, dark = true, ComposerSurfaceStyle.AMBIENT, layered = false)
        assertThat(ambientDark.frostedTint).isNull()
        assertThat(ambientDark.container.red).isLessThan(0.05f)
        assertThat(ambientDark.container.blue).isLessThan(0.05f)
        assertThat(ambientDark.topHighlight).isNotNull()

        val lightScheme = lightColorScheme()
        val frostedLight = resolveComposerIslandMaterial(lightScheme, dark = false, ComposerSurfaceStyle.FROSTED, layered = false)
        val lightTint = checkNotNull(frostedLight.frostedTint)
        assertThat(lightTint.copy(alpha = 1f)).isEqualTo(InfinityPureWhite)
        assertThat(lightTint.alpha).isWithin(1e-3f).of(0.78f)
    }
}
