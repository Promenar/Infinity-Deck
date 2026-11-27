package com.promenar.infinity.data.wallpaper

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 纯 Kotlin 壁纸主色提取器：
 * 对采样像素按色相分桶并过滤过暗、过亮与近灰像素，产出适合作为 Material 3 主题种子的和谐 ARGB 主色。
 */
internal object WallpaperColorExtractor {
    private const val HUE_BUCKETS = 24
    private const val DEFAULT_ACCENT_ARGB = 0xFF64B5F6.toInt()

    fun extractAccentArgb(pixels: IntArray): Int {
        if (pixels.isEmpty()) return DEFAULT_ACCENT_ARGB

        val bucketWeight = DoubleArray(HUE_BUCKETS)
        val bucketR = DoubleArray(HUE_BUCKETS)
        val bucketG = DoubleArray(HUE_BUCKETS)
        val bucketB = DoubleArray(HUE_BUCKETS)
        var fallbackWeight = 0.0
        var fallbackR = 0.0
        var fallbackG = 0.0
        var fallbackB = 0.0

        val step = max(1, pixels.size / 4096)
        var i = 0
        while (i < pixels.size) {
            val argb = pixels[i]
            i += step
            val alpha = (argb ushr 24) and 0xFF
            if (alpha < 128) continue
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF

            val rf = r / 255f
            val gf = g / 255f
            val bf = b / 255f
            val maxC = max(rf, max(gf, bf))
            val minC = min(rf, min(gf, bf))
            val delta = maxC - minC
            val value = maxC
            val sat = if (maxC <= 0f) 0f else delta / maxC

            if (value in 0.14f..0.94f) {
                fallbackWeight += 1.0
                fallbackR += r
                fallbackG += g
                fallbackB += b
            }
            if (sat < 0.14f || value < 0.15f || value > 0.96f) continue

            val rawHue = when {
                delta <= 0f -> 0f
                maxC == rf -> 60f * (((gf - bf) / delta) % 6f)
                maxC == gf -> 60f * (((bf - rf) / delta) + 2f)
                else -> 60f * (((rf - gf) / delta) + 4f)
            }
            val hue = if (rawHue < 0f) rawHue + 360f else rawHue
            val bucket = ((hue / 360f) * HUE_BUCKETS).toInt().coerceIn(0, HUE_BUCKETS - 1)
            // 偏向高饱和且明度居中的视觉焦点色，避免大片阴影压倒主体色调。
            val midValBonus = 1.0 - abs(value - 0.58)
            val weight = (0.35 + sat * 1.45) * (0.45 + midValBonus * 0.55)
            bucketWeight[bucket] += weight
            bucketR[bucket] += r * weight
            bucketG[bucket] += g * weight
            bucketB[bucket] += b * weight
        }

        var bestBucket = -1
        var bestScore = 0.0
        for (bIdx in 0 until HUE_BUCKETS) {
            if (bucketWeight[bIdx] > bestScore) {
                bestScore = bucketWeight[bIdx]
                bestBucket = bIdx
            }
        }

        val (rOut, gOut, bOut) = if (bestBucket >= 0 && bestScore > 0.0) {
            Triple(
                (bucketR[bestBucket] / bestScore).roundToInt(),
                (bucketG[bestBucket] / bestScore).roundToInt(),
                (bucketB[bestBucket] / bestScore).roundToInt(),
            )
        } else if (fallbackWeight > 0.0) {
            Triple(
                (fallbackR / fallbackWeight).roundToInt(),
                (fallbackG / fallbackWeight).roundToInt(),
                (fallbackB / fallbackWeight).roundToInt(),
            )
        } else {
            return DEFAULT_ACCENT_ARGB
        }

        return normalizeSeedBrightness(rOut, gOut, bOut)
    }

    /** 把种子色规整到适合 Material 3 派生的明度区间，避免纯黑/过暗种子产出失真色板。 */
    private fun normalizeSeedBrightness(r: Int, g: Int, b: Int): Int {
        val rf = r.coerceIn(0, 255) / 255f
        val gf = g.coerceIn(0, 255) / 255f
        val bf = b.coerceIn(0, 255) / 255f
        val maxC = max(rf, max(gf, bf))
        if (maxC < 0.01f) return DEFAULT_ACCENT_ARGB
        val targetMax = maxC.coerceIn(0.45f, 0.90f)
        val scale = targetMax / maxC
        val nr = (rf * scale * 255f).roundToInt().coerceIn(0, 255)
        val ng = (gf * scale * 255f).roundToInt().coerceIn(0, 255)
        val nb = (bf * scale * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
    }
}

/**
 * 纯整数 StackBlur 算法，用于后台线程预烘焙壁纸模糊缓存。
 * 不依赖已废弃的 RenderScript，也不阻塞 UI 渲染管线。
 */
internal object WallpaperStackBlur {
    fun blurInPlace(pixels: IntArray, width: Int, height: Int, radius: Int) {
        val r = radius.coerceIn(0, 64)
        if (r < 1 || width <= 1 || height <= 1 || pixels.size < width * height) return

        val wm = width - 1
        val hm = height - 1
        val wh = width * height
        val div = r + r + 1

        val red = IntArray(wh)
        val green = IntArray(wh)
        val blue = IntArray(wh)
        val vmin = IntArray(max(width, height))

        var divSum = (div + 1) shr 1
        divSum *= divSum
        val dv = IntArray(256 * divSum) { it / divSum }

        var yw = 0
        var yi = 0
        val stack = Array(div) { IntArray(3) }
        val r1 = r + 1

        for (y in 0 until height) {
            var rInSum = 0
            var gInSum = 0
            var bInSum = 0
            var rOutSum = 0
            var gOutSum = 0
            var bOutSum = 0
            var rSum = 0
            var gSum = 0
            var bSum = 0

            for (i in -r..r) {
                val p = pixels[yi + min(wm, max(i, 0))]
                val sir = stack[i + r]
                sir[0] = (p and 0xFF0000) shr 16
                sir[1] = (p and 0x00FF00) shr 8
                sir[2] = p and 0x0000FF
                val rbs = r1 - abs(i)
                rSum += sir[0] * rbs
                gSum += sir[1] * rbs
                bSum += sir[2] * rbs
                if (i > 0) {
                    rInSum += sir[0]
                    gInSum += sir[1]
                    bInSum += sir[2]
                } else {
                    rOutSum += sir[0]
                    gOutSum += sir[1]
                    bOutSum += sir[2]
                }
            }
            var stackPointer = r

            for (x in 0 until width) {
                red[yi] = dv[rSum]
                green[yi] = dv[gSum]
                blue[yi] = dv[bSum]

                rSum -= rOutSum
                gSum -= gOutSum
                bSum -= bOutSum

                val stackStart = stackPointer - r + div
                var sir = stack[stackStart % div]

                rOutSum -= sir[0]
                gOutSum -= sir[1]
                bOutSum -= sir[2]

                if (y == 0) vmin[x] = min(x + r + 1, wm)
                val p = pixels[yw + vmin[x]]
                sir[0] = (p and 0xFF0000) shr 16
                sir[1] = (p and 0x00FF00) shr 8
                sir[2] = p and 0x0000FF

                rInSum += sir[0]
                gInSum += sir[1]
                bInSum += sir[2]

                rSum += rInSum
                gSum += gInSum
                bSum += bInSum

                stackPointer = (stackPointer + 1) % div
                sir = stack[stackPointer % div]

                rOutSum += sir[0]
                gOutSum += sir[1]
                bOutSum += sir[2]

                rInSum -= sir[0]
                gInSum -= sir[1]
                bInSum -= sir[2]

                yi++
            }
            yw += width
        }

        for (x in 0 until width) {
            var rInSum = 0
            var gInSum = 0
            var bInSum = 0
            var rOutSum = 0
            var gOutSum = 0
            var bOutSum = 0
            var rSum = 0
            var gSum = 0
            var bSum = 0
            var yp = -r * width

            for (i in -r..r) {
                yi = max(0, yp) + x
                val sir = stack[i + r]
                sir[0] = red[yi]
                sir[1] = green[yi]
                sir[2] = blue[yi]
                val rbs = r1 - abs(i)
                rSum += red[yi] * rbs
                gSum += green[yi] * rbs
                bSum += blue[yi] * rbs
                if (i > 0) {
                    rInSum += sir[0]
                    gInSum += sir[1]
                    bInSum += sir[2]
                } else {
                    rOutSum += sir[0]
                    gOutSum += sir[1]
                    bOutSum += sir[2]
                }
                if (i < hm) yp += width
            }
            yi = x
            var stackPointer = r
            for (y in 0 until height) {
                pixels[yi] = (0xFF shl 24) or (dv[rSum] shl 16) or (dv[gSum] shl 8) or dv[bSum]

                rSum -= rOutSum
                gSum -= gOutSum
                bSum -= bOutSum

                val stackStart = stackPointer - r + div
                var sir = stack[stackStart % div]

                rOutSum -= sir[0]
                gOutSum -= sir[1]
                bOutSum -= sir[2]

                if (x == 0) vmin[y] = min(y + r1, hm) * width
                val p = x + vmin[y]
                sir[0] = red[p]
                sir[1] = green[p]
                sir[2] = blue[p]

                rInSum += sir[0]
                gInSum += sir[1]
                bInSum += sir[2]

                rSum += rInSum
                gSum += gInSum
                bSum += bInSum

                stackPointer = (stackPointer + 1) % div
                sir = stack[stackPointer]

                rOutSum += sir[0]
                gOutSum += sir[1]
                bOutSum += sir[2]

                rInSum -= sir[0]
                gInSum -= sir[1]
                bInSum -= sir[2]

                yi += width
            }
        }
    }
}
