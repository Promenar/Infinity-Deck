package com.promenar.infinity.ui.common.motion

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.chat.vividAccent
import com.promenar.infinity.ui.chat.washColorIndex
import com.promenar.infinity.ui.theme.InfinityDarkDomainColors
import com.promenar.infinity.ui.theme.InfinityLightDomainColors
import com.promenar.infinity.ui.theme.InfinityPresetColors
import com.promenar.infinity.ui.theme.InfinitySchemeStyle
import com.promenar.infinity.ui.theme.seededColorScheme
import org.junit.Test

class MotionPalettePhaseSyncTest {

    @Test
    fun `所有配色风格与种子色下，各生成阶段在点阵屏、灯带与漫光之间保持色彩槽位统一`() {
        for (dark in listOf(true, false)) {
            val domain = if (dark) InfinityDarkDomainColors else InfinityLightDomainColors
            val expectedApproval = if (dark) domain.warning else vividAccent(domain.warning)
            for (style in InfinitySchemeStyle.entries) {
                for (seed in InfinityPresetColors) {
                    val scheme = seededColorScheme(seed, dark = dark, style = style)
                    val palette = resolveMotionPalette(
                        primary = scheme.primary,
                        tertiary = scheme.tertiary,
                        warning = domain.warning,
                        outlineVariant = scheme.outlineVariant,
                        onSurfaceVariant = scheme.onSurfaceVariant,
                        dark = dark,
                    )

                    assertThat(palette.approval).isEqualTo(expectedApproval)
                    assertThat(palette.phaseAccent(GenerationMotionPhase.APPROVAL)).isEqualTo(expectedApproval)
                    assertThat(palette.phaseLeds(GenerationMotionPhase.APPROVAL)).containsExactly(palette.a, expectedApproval).inOrder()

                    assertThat(palette.phaseAccent(GenerationMotionPhase.TOOL)).isEqualTo(palette.b)
                    assertThat(palette.phaseLeds(GenerationMotionPhase.TOOL)).containsExactly(palette.a, palette.b).inOrder()

                    assertThat(palette.phaseLeds(GenerationMotionPhase.WAIT)).containsExactly(palette.a, palette.b).inOrder()
                    assertThat(palette.phaseLeds(GenerationMotionPhase.STREAM)).containsExactly(palette.a, palette.b).inOrder()
                    assertThat(palette.phaseLeds(GenerationMotionPhase.RETRY)).containsExactly(palette.a, palette.b).inOrder()
                    assertThat(palette.phaseLeds(null)).containsExactly(palette.a, palette.b).inOrder()
                }
            }
        }
    }

    @Test
    fun `环境光与毛玻璃漫光通道严格区分主色、工具第三色与审批警示色`() {
        assertThat(washColorIndex(GenerationMotionPhase.WAIT)).isEqualTo(0)
        assertThat(washColorIndex(GenerationMotionPhase.STREAM)).isEqualTo(0)
        assertThat(washColorIndex(GenerationMotionPhase.RETRY)).isEqualTo(0)
        assertThat(washColorIndex(GenerationMotionPhase.TOOL)).isEqualTo(1)
        assertThat(washColorIndex(GenerationMotionPhase.APPROVAL)).isEqualTo(2)
    }
}
