package com.promenar.infinity.ui.theme

import android.annotation.SuppressLint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.google.android.material.color.utilities.Hct
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.Test

class SeededColorSchemeStyleTest {
    private fun saturation(color: Color): Float {
        val maxC = max(color.red, max(color.green, color.blue))
        val minC = min(color.red, min(color.green, color.blue))
        return if (maxC <= 0f) 0f else (maxC - minC) / maxC
    }

    @Test
    fun `高饱和种子色在柔和风格下被压低，在标准与明亮风格下保持鲜明`() {
        val seed = Color(0xFFFF1744)
        val soft = seededColorScheme(seed, dark = false, style = InfinitySchemeStyle.TONAL_SPOT).primary
        val standard = seededColorScheme(seed, dark = false, style = InfinitySchemeStyle.FIDELITY).primary
        val bright = seededColorScheme(seed, dark = false, style = InfinitySchemeStyle.BRIGHT).primary

        assertThat(saturation(standard)).isGreaterThan(saturation(soft) + 0.2f)
        assertThat(saturation(bright)).isGreaterThan(saturation(soft) + 0.2f)
    }

    @Test
    fun `三档配色风格在深色模式下呈现柔和到标准再到明亮的递增饱和梯度`() {
        val seed = InfinityIceBlueSeed
        val soft = seededColorScheme(seed, dark = true, style = InfinitySchemeStyle.TONAL_SPOT)
        val standard = seededColorScheme(seed, dark = true, style = InfinitySchemeStyle.FIDELITY)
        val bright = seededColorScheme(seed, dark = true, style = InfinitySchemeStyle.BRIGHT)
        assertThat(saturation(bright.primary)).isGreaterThan(saturation(soft.primary))
        assertThat(standard.primary).isNotEqualTo(soft.primary)
    }

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return max(la, lb) / min(la, lb)
    }

    /**
     * 冰蓝种子在柔和风格下：深色主色接近 #9FD3FF（每个通道相差不超过 24），浅色主色与其上文字、与白底的对比度都不低于 4.5。
     * 种子色是待主控验证的候选值，首次运行后把实测的深浅主色补记到这里。
     */
    @Test
    fun `冰蓝预设深色主色接近目标冰蓝，浅色模式对比度合格`() {
        val target = Color(0xFF9FD3FF)
        val dark = seededColorScheme(InfinityIceBlueSeed, dark = true).primary
        val light = seededColorScheme(InfinityIceBlueSeed, dark = false)

        assertThat(abs(dark.red - target.red) * 255).isAtMost(24f)
        assertThat(abs(dark.green - target.green) * 255).isAtMost(24f)
        assertThat(abs(dark.blue - target.blue) * 255).isAtMost(24f)
        assertThat(contrast(light.primary, light.onPrimary)).isAtLeast(4.5f)
        assertThat(contrast(light.primary, Color.White)).isAtLeast(4.5f)
    }

    private fun hueDelta(a: Double, b: Double): Double {
        val diff = abs(a - b) % 360.0
        return if (diff > 180.0) 360.0 - diff else diff
    }

    @SuppressLint("RestrictedApi")
    @Test
    fun `明亮风格在全部预设色上锁定原色相、深色更饱和且具备可读对比度`() {
        InfinityPresetColors.forEach { seed ->
            val seedHue = Hct.fromInt(seed.toArgb()).hue
            val darkBright = seededColorScheme(seed, dark = true, style = InfinitySchemeStyle.BRIGHT)
            val darkSoft = seededColorScheme(seed, dark = true, style = InfinitySchemeStyle.TONAL_SPOT)
            val lightBright = seededColorScheme(seed, dark = false, style = InfinitySchemeStyle.BRIGHT)

            val darkHue = Hct.fromInt(darkBright.primary.toArgb()).hue
            val lightHue = Hct.fromInt(lightBright.primary.toArgb()).hue
            assertThat(hueDelta(darkHue, seedHue)).isAtMost(12.0)
            assertThat(hueDelta(lightHue, seedHue)).isAtMost(12.0)

            assertThat(saturation(darkBright.primary)).isGreaterThan(saturation(darkSoft.primary))
            assertThat(contrast(darkBright.primary, Color.Black)).isAtLeast(4.5f)
            assertThat(contrast(darkBright.primary, darkBright.onPrimary)).isAtLeast(2.7f)
            assertThat(contrast(lightBright.primary, lightBright.onPrimary)).isAtLeast(4.5f)
            assertThat(contrast(lightBright.primary, Color.White)).isAtLeast(4.5f)
        }
    }

    @SuppressLint("RestrictedApi")
    @Test
    fun `明亮风格遇到近中性灰种子时不凭空注入高彩度`() {
        val neutral = Color(0xFF7A7A7A)
        val darkBright = seededColorScheme(neutral, dark = true, style = InfinitySchemeStyle.BRIGHT)
        val lightBright = seededColorScheme(neutral, dark = false, style = InfinitySchemeStyle.BRIGHT)

        assertThat(Hct.fromInt(darkBright.primary.toArgb()).chroma).isLessThan(12.0)
        assertThat(Hct.fromInt(lightBright.primary.toArgb()).chroma).isLessThan(12.0)
    }
}
