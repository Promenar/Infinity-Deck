package com.promenar.infinity.ui.chat

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.common.motion.GenerationMotion
import com.promenar.infinity.ui.common.motion.GenerationMotionPhase
import kotlin.math.max
import kotlin.math.min
import org.junit.Test

class ComposerIslandGlowTest {
    @Test
    fun `浅色主题的光效颜色提亮提纯但不改色相`() {
        // 浅色主题的 primary 偏深偏灰，直接用在白底上发闷。
        val muted = Color(0xFF4A5C8A)
        val vivid = vividAccent(muted)

        // 颜色按 8 位通道存储，留出量化误差。
        assertThat(saturation(vivid)).isAtLeast(0.74f)
        assertThat(value(vivid)).isAtLeast(0.94f)
        assertThat(hue(vivid)).isWithin(1f).of(hue(muted))
        assertThat(vivid.alpha).isEqualTo(muted.alpha)
    }

    @Test
    fun `已经鲜艳的颜色保持原样`() {
        val vivid = Color(1f, 0.1f, 0.4f)
        val result = vividAccent(vivid)

        assertThat(result.red).isWithin(1e-3f).of(vivid.red)
        assertThat(result.green).isWithin(1e-3f).of(vivid.green)
        assertThat(result.blue).isWithin(1e-3f).of(vivid.blue)
    }

    @Test
    fun `灰色不会被染上色相`() {
        val result = vividAccent(Color(0.4f, 0.4f, 0.4f))

        assertThat(result.red).isEqualTo(result.green)
        assertThat(result.green).isEqualTo(result.blue)
    }

    @Test
    fun `扫描渐变的旋转让光带头部对齐十二点钟起算的角度`() {
        // SweepGradient 的 0° 在三点钟，光带角度以十二点钟为 0°。
        assertThat(sweepRotationForHead(90f)).isEqualTo(0f)
        assertThat(sweepRotationForHead(0f)).isEqualTo(-90f)
    }

    @Test
    fun `光带按周期匀速绕行并在一圈后回到起点`() {
        assertThat(advanceGlowAngle(0f, ComposerGlowPeriodSeconds / 4f)).isWithin(1e-3f).of(90f)
        assertThat(advanceGlowAngle(350f, ComposerGlowPeriodSeconds / 36f)).isWithin(1e-3f).of(0f)
        assertThat(advanceGlowAngle(30f, ComposerGlowPeriodSeconds * 3f)).isWithin(1e-2f).of(30f)
        assertThat(advanceGlowAngle(10f, 0f)).isEqualTo(10f)
    }

    @Test
    fun `极光光带首字前慢转，流式时随速率加快，工具执行与审批时停下`() {
        fun speedIn(phase: GenerationMotionPhase, act: Float): Float {
            val motion = GenerationMotion(GenerationMotion.Source.LIVE)
            motion.update(phase, retry = null, outputTokens = 0, animate = false)
            // 流式阶段要有正文到达才按流式显示。
            motion.update(phase, retry = null, outputTokens = 1, animate = false)
            return auroraHeadSpeed(motion, act)
        }
        assertThat(speedIn(GenerationMotionPhase.WAIT, 1f)).isWithin(1e-3f).of(60f)
        assertThat(speedIn(GenerationMotionPhase.STREAM, 0f)).isWithin(1e-2f).of(360f / ComposerGlowPeriodSeconds / 2)
        assertThat(speedIn(GenerationMotionPhase.STREAM, 1f)).isGreaterThan(speedIn(GenerationMotionPhase.STREAM, 0.3f))
        assertThat(speedIn(GenerationMotionPhase.TOOL, 1f)).isEqualTo(0f)
        assertThat(speedIn(GenerationMotionPhase.APPROVAL, 1f)).isEqualTo(0f)
    }

    private fun value(color: Color) = max(color.red, max(color.green, color.blue))

    private fun saturation(color: Color): Float {
        val maxC = value(color)
        return if (maxC == 0f) 0f else (maxC - min(color.red, min(color.green, color.blue))) / maxC
    }

    private fun hue(color: Color): Float {
        val hsv = FloatArray(3)
        java.awt.Color.RGBtoHSB(
            (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt(), hsv,
        )
        return hsv[0] * 360f
    }
}
