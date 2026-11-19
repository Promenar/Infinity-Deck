package com.promenar.infinity.ui.common.motion

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class LosslessGlowMathTest {
    private val projectRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/promenar/infinity/ui").isDirectory }

    @Test
    fun `erfApprox 在正负区间保持奇对称与高精渐近值`() {
        assertThat(erfApprox(0f)).isWithin(1e-6f).of(0f)
        assertThat(erfApprox(1f)).isWithin(1e-6f).of(0.8427007f)
        assertThat(erfApprox(2f)).isWithin(1e-6f).of(0.9953223f)
        assertThat(erfApprox(-1.25f)).isWithin(1e-6f).of(-erfApprox(1.25f))
        assertThat(erfApprox(6f)).isWithin(1e-6f).of(1f)
        assertThat(erfApprox(-6f)).isWithin(1e-6f).of(-1f)
    }

    @Test
    fun `skiaSigma 与 roundedBoxSdf 满足 Skia 转换与内外符号边界`() {
        assertThat(skiaSigma(0f)).isEqualTo(0f)
        assertThat(skiaSigma(-2f)).isEqualTo(0f)
        assertThat(skiaSigma(10f)).isWithin(1e-4f).of(6.2735f)

        // 100x60 半宽高圆角 12 的盒子：中心 < 0，四边中点 = 0，外缘 > 0，圆角超越半宽钳制安全。
        assertThat(roundedBoxSdf(0f, 0f, 50f, 30f, 12f)).isWithin(1e-4f).of(-30f)
        assertThat(roundedBoxSdf(50f, 0f, 50f, 30f, 12f)).isWithin(1e-4f).of(0f)
        assertThat(roundedBoxSdf(0f, 30f, 50f, 30f, 12f)).isWithin(1e-4f).of(0f)
        assertThat(roundedBoxSdf(60f, 0f, 50f, 30f, 12f)).isWithin(1e-4f).of(10f)
        assertThat(roundedBoxSdf(50f, 30f, 50f, 30f, 12f)).isGreaterThan(0f)
        assertThat(roundedBoxSdf(0f, 0f, 20f, 10f, 99f)).isWithin(1e-4f).of(-10f)
    }

    @Test
    fun `生产 UI 源码彻底零 BlurMaskFilter 残留以杜绝 Vulkan Scratch 泄漏`() {
        val uiRoot = File(projectRoot, "app/src/main/java/com/promenar/infinity/ui")
        val blurUsage = Regex("""import\s+android\.graphics\.BlurMaskFilter|\bBlurMaskFilter\s*[.(]""")
        val offenders = uiRoot.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .filter { file -> blurUsage.containsMatchIn(file.readText()) }
            .map { it.relativeTo(uiRoot).invariantSeparatorsPath }
            .toList()
        assertThat(offenders).isEmpty()
    }
}
