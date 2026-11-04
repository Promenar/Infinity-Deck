package com.promenar.infinity.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 验证真实 Android 资源解析；可显式指向已安装的正式签名 APK。 */
@RunWith(AndroidJUnit4::class)
class LauncherIconDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetPackage = InstrumentationRegistry.getArguments()
        .getString("launcherIconTargetPackage") ?: instrumentation.targetContext.packageName

    private fun context(night: Boolean): Context {
        val app = instrumentation.targetContext.createPackageContext(targetPackage, 0)
        val configuration = Configuration(app.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return app.createConfigurationContext(configuration)
    }

    private fun icon(context: Context, name: String): AdaptiveIconDrawable {
        val id = context.resources.getIdentifier(name, "mipmap", targetPackage)
        assertThat(id).isNotEqualTo(0)
        val drawable = context.resources.getDrawable(id, context.theme)
        assertThat(drawable).isInstanceOf(AdaptiveIconDrawable::class.java)
        return drawable as AdaptiveIconDrawable
    }

    private fun render(drawable: Drawable, size: Int = SIZE): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(it))
        }

    /** 不透明度高于阈值的像素数；同时断言它们都在 66dp 安全圆内。 */
    private fun inkInsideSafeZone(bitmap: Bitmap, threshold: Int): Int {
        val scale = bitmap.width / 108.0
        var ink = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (Color.alpha(bitmap.getPixel(x, y)) <= threshold) continue
            ink++
            val dx = (x + .5) / scale - 54
            val dy = (y + .5) / scale - 54
            assertThat(dx * dx + dy * dy).isAtMost(33.0 * 33.0)
        }
        return ink
    }

    @Test
    fun normalAndRoundIconsResolveToTheGlowingDotMatrixInBothModes() {
        for (night in listOf(false, true)) {
            val context = context(night)
            for (name in listOf("ic_launcher", "ic_launcher_round")) {
                val icon = icon(context, name)
                val background = render(icon.background)
                for ((x, y) in listOf(SIZE / 2 to SIZE / 2, 0 to 0, SIZE - 1 to SIZE - 1)) {
                    val pixel = background.getPixel(x, y)
                    assertThat(Color.alpha(pixel)).isEqualTo(255)
                    assertThat(maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))).isLessThan(80)
                }
                val foreground = render(icon.foreground)
                assertThat(Color.alpha(foreground.getPixel(4, 4))).isEqualTo(0)
                // 环境光与光晕边缘的不透明度低于 150，只统计点本身。
                assertThat(inkInsideSafeZone(foreground, threshold = 150)).isGreaterThan(3000)
                // 发光位图前景的点芯接近白色（最小通道 > 180），光晕不计入。
                assertThat(brightest(foreground, 0 until SIZE, 0 until SIZE)).isGreaterThan(180)
            }
            val output = File(instrumentation.targetContext.filesDir, "launcher-icon-${if (night) "dark" else "light"}.png")
            output.outputStream().use { render(icon(context, "ic_launcher"), 256).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun brightest(bitmap: Bitmap, xs: IntRange, ys: IntRange): Int {
        var best = 0
        for (y in ys) for (x in xs) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.alpha(pixel) > 200) best = maxOf(best, minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)))
        }
        return best
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun monochromeIsAnOpaqueDotSilhouetteInsideTheSafeZone() {
        for (night in listOf(false, true)) {
            val mono = icon(context(night), "ic_launcher").monochrome
            assertThat(mono).isNotNull()
            assertThat(inkInsideSafeZone(render(requireNotNull(mono)), threshold = 200)).isGreaterThan(3000)
        }
    }

    @Test
    fun notificationIconIsASolidStrokeSilhouette() {
        val resources = context(false).resources
        val id = resources.getIdentifier("ic_notification", "drawable", targetPackage)
        assertThat(id).isNotEqualTo(0)
        val bitmap = render(requireNotNull(resources.getDrawable(id, null)), 96)
        var ink = 0
        for (y in 0 until 96) for (x in 0 until 96) if (Color.alpha(bitmap.getPixel(x, y)) > 200) ink++
        assertThat(ink).isGreaterThan(600)
        // 交汇点（母版 24dp 画布中约 (11.1, 13.2)）是实的，角落是空的。
        assertThat(Color.alpha(bitmap.getPixel(44, 53))).isGreaterThan(200)
        assertThat(Color.alpha(bitmap.getPixel(4, 4))).isEqualTo(0)
    }

    private companion object {
        const val SIZE = 432
    }
}
