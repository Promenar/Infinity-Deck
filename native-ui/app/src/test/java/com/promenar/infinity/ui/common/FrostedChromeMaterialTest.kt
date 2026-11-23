package com.promenar.infinity.ui.common

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.theme.ComposerSurfaceStyle
import com.promenar.infinity.ui.theme.InfinityObsidianGlassTint
import com.promenar.infinity.ui.theme.InfinityObsidianSolidChip
import com.promenar.infinity.ui.theme.InfinityObsidianSolidContainer
import com.promenar.infinity.ui.theme.InfinityPureWhite
import org.junit.Test

class FrostedChromeMaterialTest {

    private val darkScheme = darkColorScheme()
    private val lightScheme = lightColorScheme()

    @Test
    fun `深色熏黑毛玻璃实时角色与浮岛材质基准及透明阶梯对齐`() {
        val expectedAlphas = mapOf(
            FrostedChromeRole.TOP_BAR to 0.76f,
            FrostedChromeRole.FLOATING_INSPECTOR to 0.82f,
            FrostedChromeRole.FLOATING_STRIP to 0.78f,
            FrostedChromeRole.FLOATING_DRAWER to 0.82f,
            FrostedChromeRole.PORTRAIT_DRAWER to 0.84f,
        )

        expectedAlphas.forEach { (role, expectedAlpha) ->
            val spec = resolveFrostedChromeMaterial(
                role = role,
                scheme = darkScheme,
                dark = true,
                surface = ComposerSurfaceStyle.FROSTED,
            )
            assertThat(spec.isFrosted).isTrue()
            assertThat(spec.container).isEqualTo(Color.Transparent)
            val tint = checkNotNull(spec.frostedTint)
            assertThat(tint.copy(alpha = 1f)).isEqualTo(InfinityObsidianGlassTint)
            assertThat(tint.alpha).isWithin(1e-3f).of(expectedAlpha)
            if (role == FrostedChromeRole.TOP_BAR) {
                assertThat(spec.border).isNull()
                assertThat(spec.topHighlight).isNull()
            } else {
                assertThat(spec.border).isNotNull()
                assertThat(spec.topHighlight).isNotNull()
            }
            assertThat(spec.innerCardBorder).isNotNull()
        }
    }

    @Test
    fun `固定分栏侧边栏无论材质档位均走零模糊熏黑实底`() {
        listOf(ComposerSurfaceStyle.FROSTED, ComposerSurfaceStyle.AMBIENT).forEach { style ->
            val darkSpec = resolveFrostedChromeMaterial(
                role = FrostedChromeRole.DOCKED_INSPECTOR,
                scheme = darkScheme,
                dark = true,
                surface = style,
            )
            assertThat(darkSpec.isFrosted).isFalse()
            assertThat(darkSpec.frostedTint).isNull()
            assertThat(darkSpec.container).isEqualTo(InfinityObsidianSolidContainer)
            assertThat(darkSpec.innerCard).isEqualTo(InfinityObsidianSolidChip)
            assertThat(darkSpec.border).isNotNull()
            assertThat(darkSpec.topHighlight).isNotNull()

            val lightSpec = resolveFrostedChromeMaterial(
                role = FrostedChromeRole.DOCKED_INSPECTOR,
                scheme = lightScheme,
                dark = false,
                surface = style,
            )
            assertThat(lightSpec.isFrosted).isFalse()
            assertThat(lightSpec.frostedTint).isNull()
            assertThat(lightSpec.container).isEqualTo(lightScheme.surfaceContainerLowest)
            assertThat(lightSpec.border).isNotNull()
        }
    }

    @Test
    fun `深色环境光省电档全角色同步降级为熏黑实底且顶栏无全周边框`() {
        FrostedChromeRole.entries.forEach { role ->
            val spec = resolveFrostedChromeMaterial(
                role = role,
                scheme = darkScheme,
                dark = true,
                surface = ComposerSurfaceStyle.AMBIENT,
            )
            assertThat(spec.isFrosted).isFalse()
            assertThat(spec.frostedTint).isNull()
            assertThat(spec.container).isEqualTo(InfinityObsidianSolidContainer)
            assertThat(spec.innerCard).isEqualTo(InfinityObsidianSolidChip)
            assertThat(spec.innerCardBorder).isNotNull()
            if (role == FrostedChromeRole.TOP_BAR) {
                assertThat(spec.border).isNull()
                assertThat(spec.topHighlight).isNull()
            } else {
                assertThat(spec.border).isNotNull()
                assertThat(spec.topHighlight).isNotNull()
            }
        }
    }

    @Test
    fun `浅色冷白磨砂使用纯白基底并按环境光切换为最低表面实底`() {
        val expectedLightAlphas = mapOf(
            FrostedChromeRole.TOP_BAR to 0.78f,
            FrostedChromeRole.FLOATING_INSPECTOR to 0.82f,
            FrostedChromeRole.FLOATING_STRIP to 0.78f,
            FrostedChromeRole.FLOATING_DRAWER to 0.82f,
            FrostedChromeRole.PORTRAIT_DRAWER to 0.84f,
        )

        expectedLightAlphas.forEach { (role, expectedAlpha) ->
            val frosted = resolveFrostedChromeMaterial(
                role = role,
                scheme = lightScheme,
                dark = false,
                surface = ComposerSurfaceStyle.FROSTED,
            )
            assertThat(frosted.isFrosted).isTrue()
            assertThat(frosted.container).isEqualTo(Color.Transparent)
            val tint = checkNotNull(frosted.frostedTint)
            assertThat(tint.copy(alpha = 1f)).isEqualTo(InfinityPureWhite)
            assertThat(tint.alpha).isWithin(1e-3f).of(expectedAlpha)
            if (role == FrostedChromeRole.TOP_BAR) {
                assertThat(frosted.border).isNull()
                assertThat(frosted.topHighlight).isNull()
            } else {
                assertThat(frosted.border).isNotNull()
                assertThat(frosted.topHighlight).isNotNull()
            }

            val ambient = resolveFrostedChromeMaterial(
                role = role,
                scheme = lightScheme,
                dark = false,
                surface = ComposerSurfaceStyle.AMBIENT,
            )
            assertThat(ambient.isFrosted).isFalse()
            assertThat(ambient.frostedTint).isNull()
            assertThat(ambient.container).isEqualTo(lightScheme.surfaceContainerLowest)
            assertThat(ambient.innerCard).isEqualTo(lightScheme.surfaceContainerLow)
            assertThat(ambient.topHighlight).isNull()
            if (role == FrostedChromeRole.TOP_BAR) {
                assertThat(ambient.border).isNull()
            } else {
                assertThat(ambient.border).isNotNull()
            }
        }
    }
}
