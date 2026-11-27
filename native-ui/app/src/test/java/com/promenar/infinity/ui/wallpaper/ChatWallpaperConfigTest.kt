package com.promenar.infinity.ui.wallpaper

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.wallpaper.WallpaperAssetStore
import com.promenar.infinity.data.wallpaper.WallpaperColorExtractor
import com.promenar.infinity.data.wallpaper.WallpaperStackBlur
import org.junit.jupiter.api.Test
import kotlin.math.abs

class ChatWallpaperConfigTest {

    @Test
    fun `默认配置关闭壁纸且助手衬底不生效`() {
        val config = ChatWallpaperConfig()
        assertThat(config.isActive).isFalse()
        assertThat(config.effectiveAssistantCard).isFalse()
        assertThat(config.recommendedAccentArgb()).isNull()
    }

    @Test
    fun `预设顺序在浅色模式下将暖砂纸纹置顶`() {
        assertThat(WallpaperPresetId.ordered(dark = true).first()).isEqualTo(WallpaperPresetId.AURORA)
        assertThat(WallpaperPresetId.ordered(dark = false).first()).isEqualTo(WallpaperPresetId.WARM_PAPER)
        assertThat(WallpaperPresetId.ordered(dark = true)).hasSize(WallpaperPresetId.entries.size)
        assertThat(WallpaperPresetId.ordered(dark = false)).hasSize(WallpaperPresetId.entries.size)
    }

    @Test
    fun `持久化还原正确处理边界钳制与非法文件名防穿越`() {
        val preset = ChatWallpaperConfig.fromPersisted(
            kind = ChatWallpaperConfig.KIND_PRESET,
            presetKey = "pine_mist",
            imageFileName = null,
            extractedAccentArgb = null,
            blurDp = 99,
            dimFraction = 1.5f,
            assistantReadingCard = false,
        )
        assertThat(preset.source).isEqualTo(WallpaperSource.Preset(WallpaperPresetId.PINE_MIST))
        assertThat(preset.clampedBlurDp).isEqualTo(ChatWallpaperConfig.MAX_BLUR_DP)
        assertThat(preset.clampedDimFraction).isEqualTo(ChatWallpaperConfig.MAX_DIM_FRACTION)
        assertThat(preset.effectiveAssistantCard).isFalse()

        val traversal = ChatWallpaperConfig.fromPersisted(
            kind = ChatWallpaperConfig.KIND_IMAGE,
            presetKey = null,
            imageFileName = "../secret.webp",
            extractedAccentArgb = 0xFF336699.toInt(),
            blurDp = -5,
            dimFraction = Float.NaN,
            assistantReadingCard = true,
        )
        assertThat(traversal.source).isEqualTo(WallpaperSource.None)
        assertThat(traversal.clampedBlurDp).isEqualTo(0)
        assertThat(traversal.clampedDimFraction).isEqualTo(ChatWallpaperConfig.DEFAULT_PHOTO_DIM_DARK)

        val validImage = ChatWallpaperConfig.fromPersisted(
            kind = ChatWallpaperConfig.KIND_IMAGE,
            presetKey = null,
            imageFileName = "wp-1234_abcd.webp",
            extractedAccentArgb = 0xFF336699.toInt(),
            blurDp = 8,
            dimFraction = 0.48f,
            assistantReadingCard = true,
        )
        assertThat(validImage.source).isEqualTo(
            WallpaperSource.Image(fileName = "wp-1234_abcd.webp", extractedAccentArgb = 0xFF336699.toInt()),
        )
        assertThat(validImage.effectiveAssistantCard).isTrue()
        assertThat(validImage.recommendedAccentArgb()).isEqualTo(0xFF336699.toInt())
    }

    @Test
    fun `文件名安全校验拦截路径分隔与空白`() {
        assertThat(ChatWallpaperConfig.isSafeImageFileName("wp-abc123.webp")).isTrue()
        assertThat(ChatWallpaperConfig.isSafeImageFileName("")).isFalse()
        assertThat(ChatWallpaperConfig.isSafeImageFileName("a/b.webp")).isFalse()
        assertThat(ChatWallpaperConfig.isSafeImageFileName("a\\b.webp")).isFalse()
        assertThat(ChatWallpaperConfig.isSafeImageFileName("..wp.webp")).isFalse()
        assertThat(ChatWallpaperConfig.isSafeImageFileName("bad name.webp")).isFalse()
    }

    @Test
    fun `颜色提取器忽略黑底并提取主视觉色相`() {
        val pixels = IntArray(400) { idx ->
            if (idx < 300) 0xFF06070A.toInt() // 75% 深黑背景
            else 0xFF14B8A6.toInt() // 25% 青绿色主体
        }
        val accent = WallpaperColorExtractor.extractAccentArgb(pixels)
        val r = (accent ushr 16) and 0xFF
        val g = (accent ushr 8) and 0xFF
        val b = accent and 0xFF
        // 提取结果应明显偏青绿（G、B 远高于 R）
        assertThat(g).isGreaterThan(r + 60)
        assertThat(b).isGreaterThan(r + 40)

        // 空数组与纯黑均安全回退为不透明默认色
        assertThat((WallpaperColorExtractor.extractAccentArgb(IntArray(0)) ushr 24) and 0xFF).isEqualTo(0xFF)
        assertThat((WallpaperColorExtractor.extractAccentArgb(IntArray(16) { 0xFF000000.toInt() }) ushr 24) and 0xFF).isEqualTo(0xFF)
    }

    @Test
    fun `StackBlur 平滑锐利边缘并保持纯色不变`() {
        val width = 16
        val height = 16
        val uniform = IntArray(width * height) { 0xFF4488CC.toInt() }
        WallpaperStackBlur.blurInPlace(uniform, width, height, radius = 4)
        assertThat(uniform[0]).isEqualTo(0xFF4488CC.toInt())
        assertThat(uniform[uniform.lastIndex]).isEqualTo(0xFF4488CC.toInt())

        val split = IntArray(width * height) { idx ->
            val x = idx % width
            if (x < width / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        WallpaperStackBlur.blurInPlace(split, width, height, radius = 3)
        val leftR = (split[8 * width + 7] ushr 16) and 0xFF
        val rightR = (split[8 * width + 8] ushr 16) and 0xFF
        // 原本跳变 255，模糊后交界处应平滑过渡
        assertThat(abs(rightR - leftR)).isLessThan(120)
        assertThat(leftR).isGreaterThan(40)
        assertThat(rightR).isLessThan(215)
    }

    @Test
    fun `采样倍率对超大图返回安全 2 的幂次`() {
        assertThat(WallpaperAssetStore.computeSampleSize(1200, 900, 2048)).isEqualTo(1)
        assertThat(WallpaperAssetStore.computeSampleSize(4096, 3072, 2048)).isEqualTo(2)
        assertThat(WallpaperAssetStore.computeSampleSize(9000, 6000, 2048)).isEqualTo(4)
    }

    @Test
    fun `备份偏好策略仅放行可跨机迁移的壁纸参数并排除本机图片文件名`() {
        val policy = com.promenar.infinity.data.backup.BackupPreferencePolicy
        assertThat(policy.isAllowed("settings", "wallpaper_kind")).isTrue()
        assertThat(policy.isAllowed("settings", "wallpaper_preset")).isTrue()
        assertThat(policy.isAllowed("settings", "wallpaper_blur_dp")).isTrue()
        assertThat(policy.isAllowed("settings", "wallpaper_dim_fraction")).isTrue()
        assertThat(policy.isAllowed("settings", "wallpaper_assistant_card")).isTrue()

        assertThat(policy.isAllowed("settings", "wallpaper_image_file")).isFalse()
        assertThat(policy.isAllowed("settings", "wallpaper_accent_argb")).isFalse()
        assertThat(policy.isKnown("settings", "wallpaper_image_file")).isTrue()
        assertThat(policy.isKnown("settings", "wallpaper_accent_argb")).isTrue()
    }
}
