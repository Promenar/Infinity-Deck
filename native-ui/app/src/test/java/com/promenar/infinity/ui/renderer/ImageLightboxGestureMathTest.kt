package com.promenar.infinity.ui.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ImageLightboxGestureMathTest {
    @Test fun `缩放倍率约束在最小与最大范围且判断放大态准确`() {
        assertThat(ImageLightboxGestureMath.clampScale(0.2f)).isEqualTo(ImageLightboxGestureMath.MIN_SCALE)
        assertThat(ImageLightboxGestureMath.clampScale(2.5f)).isEqualTo(2.5f)
        assertThat(ImageLightboxGestureMath.clampScale(9.0f)).isEqualTo(ImageLightboxGestureMath.MAX_SCALE)
        assertThat(ImageLightboxGestureMath.isZoomed(1.0f)).isFalse()
        assertThat(ImageLightboxGestureMath.isZoomed(1.01f)).isFalse()
        assertThat(ImageLightboxGestureMath.isZoomed(1.05f)).isTrue()
    }

    @Test fun `顺时针90度旋转角度按四步循环取模`() {
        var turns = 0
        assertThat(ImageLightboxGestureMath.displayDegrees(turns)).isEqualTo(0)
        turns = ImageLightboxGestureMath.nextQuarterTurns(turns)
        assertThat(ImageLightboxGestureMath.displayDegrees(turns)).isEqualTo(90)
        turns = ImageLightboxGestureMath.nextQuarterTurns(turns)
        assertThat(ImageLightboxGestureMath.displayDegrees(turns)).isEqualTo(180)
        turns = ImageLightboxGestureMath.nextQuarterTurns(turns)
        assertThat(ImageLightboxGestureMath.displayDegrees(turns)).isEqualTo(270)
        turns = ImageLightboxGestureMath.nextQuarterTurns(turns)
        assertThat(ImageLightboxGestureMath.displayDegrees(turns)).isEqualTo(0)
        assertThat(ImageLightboxGestureMath.displayDegrees(-1)).isEqualTo(270)
    }

    @Test fun `平移偏移按当前放大倍率夹紧到可视区域并支持零视口保护`() {
        val viewport = IntSize(width = 1000, height = 2000)
        assertThat(ImageLightboxGestureMath.clampOffset(Offset(100f, 100f), viewport, 1f)).isEqualTo(Offset.Zero)
        assertThat(ImageLightboxGestureMath.clampOffset(Offset(100f, 100f), IntSize.Zero, 3f)).isEqualTo(Offset.Zero)
        // scale = 2.5f -> maxX = (1.5 * 1000 / 2) = 750f, maxY = (1.5 * 2000 / 2) = 1500f
        val clamped = ImageLightboxGestureMath.clampOffset(Offset(900f, -1800f), viewport, 2.5f)
        assertThat(clamped).isEqualTo(Offset(750f, -1500f))
        val within = ImageLightboxGestureMath.clampOffset(Offset(200f, -300f), viewport, 2.5f)
        assertThat(within).isEqualTo(Offset(200f, -300f))
    }

    @Test fun `变换状态检测与顶部摘要文本格式化正确`() {
        assertThat(ImageLightboxGestureMath.isTransformed(1f, 0)).isFalse()
        assertThat(ImageLightboxGestureMath.formatTransformSummary(1f, 0)).isNull()

        assertThat(ImageLightboxGestureMath.isTransformed(1f, 1)).isTrue()
        assertThat(ImageLightboxGestureMath.formatTransformSummary(1f, 1)).isEqualTo("90°")

        assertThat(ImageLightboxGestureMath.isTransformed(2.5f, 0)).isTrue()
        assertThat(ImageLightboxGestureMath.formatTransformSummary(2.5f, 0)).isEqualTo("2.5×")

        assertThat(ImageLightboxGestureMath.isTransformed(2.5f, 2)).isTrue()
        assertThat(ImageLightboxGestureMath.formatTransformSummary(2.5f, 2)).isEqualTo("180° · 2.5×")

        // 旋转满一圈回到 0° 且未放大时恢复未变换状态。
        assertThat(ImageLightboxGestureMath.isTransformed(1f, 4)).isFalse()
        assertThat(ImageLightboxGestureMath.formatTransformSummary(1f, 4)).isNull()
    }
}
