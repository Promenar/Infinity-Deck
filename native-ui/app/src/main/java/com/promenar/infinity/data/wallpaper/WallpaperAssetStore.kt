package com.promenar.infinity.data.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import com.promenar.infinity.ui.wallpaper.ChatWallpaperConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

data class WallpaperImportResult(
    val fileName: String,
    val extractedAccentArgb: Int,
)

/**
 * 聊天壁纸本地资产与预烘焙模糊缓存仓库：
 * - 原图经 EXIF 方向矫正与安全降采样后以 WebP 保存在私有目录 `filesDir/wallpapers/`；
 * - 模糊版本按 `(fileName, blurDp)` 在后台预烘焙到 `cacheDir/wallpaper-blur/`，对话滑动零实时模糊开销。
 */
class WallpaperAssetStore(
    private val context: Context,
) {
    private val wallpaperDir: File
        get() = File(context.filesDir, WALLPAPER_DIR_NAME).apply { mkdirs() }

    private val blurCacheDir: File
        get() = File(context.cacheDir, BLUR_CACHE_DIR_NAME).apply { mkdirs() }

    suspend fun importFromUri(uri: Uri): Result<WallpaperImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = decodeNormalizedBitmap(uri)
            try {
                persistNormalizedBitmap(bitmap)
            } finally {
                bitmap.recycle()
            }
        }
    }

    suspend fun importFromBitmap(source: Bitmap): Result<WallpaperImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val scaled = scaleDownIfNeeded(source, MAX_STORED_DIMENSION)
            try {
                persistNormalizedBitmap(scaled)
            } finally {
                if (scaled !== source) scaled.recycle()
            }
        }
    }

    fun resolveOriginalFile(fileName: String): File? {
        if (!ChatWallpaperConfig.isSafeImageFileName(fileName)) return null
        val dir = wallpaperDir.canonicalFile
        val target = File(dir, fileName).canonicalFile
        if (target.parentFile != dir || !target.isFile) return null
        return target
    }

    /**
     * 解析指定模糊半径的展示文件：
     * - `blurDp <= 0` 直接返回原图；
     * - `blurDp > 0` 命中缓存立刻返回，未命中则在 IO 线程降采样预烘焙后原子落盘。
     */
    suspend fun resolveDisplayFile(fileName: String, blurDp: Int): File? = withContext(Dispatchers.IO) {
        val original = resolveOriginalFile(fileName) ?: return@withContext null
        val clamped = blurDp.coerceIn(ChatWallpaperConfig.MIN_BLUR_DP, ChatWallpaperConfig.MAX_BLUR_DP)
        if (clamped <= 0) return@withContext original

        val cacheFile = blurCacheFileFor(fileName, clamped)
        if (cacheFile.isFile && cacheFile.length() > 0L) return@withContext cacheFile

        bakeBlurredCopy(original, cacheFile, clamped)
        cacheFile.takeIf { it.isFile && it.length() > 0L } ?: original
    }

    /** 清理不再引用的历史自定义壁纸与旧模糊缓存，避免反复换图堆积空间。 */
    suspend fun pruneUnused(keepFileName: String?) = withContext(Dispatchers.IO) {
        val safeKeep = keepFileName?.takeIf(ChatWallpaperConfig::isSafeImageFileName)
        wallpaperDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name != safeKeep) {
                runCatching { file.delete() }
            }
        }
        val keepPrefix = safeKeep?.substringBeforeLast('.')
        blurCacheDir.listFiles()?.forEach { file ->
            if (file.isFile && (keepPrefix == null || !file.name.startsWith("${keepPrefix}-b"))) {
                runCatching { file.delete() }
            }
        }
    }

    private fun blurCacheFileFor(fileName: String, blurDp: Int): File {
        val stem = fileName.substringBeforeLast('.')
        return File(blurCacheDir, "${stem}-b${blurDp}.webp")
    }

    private fun persistNormalizedBitmap(bitmap: Bitmap): WallpaperImportResult {
        val accent = extractAccent(bitmap)
        val fileName = "wp-${UUID.randomUUID()}.webp"
        val dest = File(wallpaperDir, fileName)
        writeWebpAtomically(bitmap, dest, STORED_WEBP_QUALITY)
        return WallpaperImportResult(fileName = fileName, extractedAccentArgb = accent)
    }

    private fun extractAccent(bitmap: Bitmap): Int {
        val sample = scaleDownIfNeeded(bitmap, COLOR_SAMPLE_MAX_DIMENSION)
        val software = if (sample.config == Bitmap.Config.ARGB_8888) sample
        else sample.copy(Bitmap.Config.ARGB_8888, false)
        return try {
            val pixels = IntArray(software.width * software.height)
            software.getPixels(pixels, 0, software.width, 0, 0, software.width, software.height)
            WallpaperColorExtractor.extractAccentArgb(pixels)
        } finally {
            if (software !== sample) software.recycle()
            if (sample !== bitmap) sample.recycle()
        }
    }

    private fun bakeBlurredCopy(original: File, destination: File, blurDp: Int) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(original.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, BLUR_WORKING_MAX_DIMENSION)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val decoded = BitmapFactory.decodeFile(original.absolutePath, decodeOptions) ?: return
        val working = scaleDownIfNeeded(decoded, BLUR_WORKING_MAX_DIMENSION)
        val mutable = if (working.isMutable && working.config == Bitmap.Config.ARGB_8888) working
        else working.copy(Bitmap.Config.ARGB_8888, true)
        try {
            val w = mutable.width
            val h = mutable.height
            val pixels = IntArray(w * h)
            mutable.getPixels(pixels, 0, w, 0, 0, w, h)
            // 工作图约 640px 边长，按 1.6× 系数折算对应屏幕上 0–24dp 的柔和视觉散景。
            val pixelRadius = (blurDp * 1.6f).roundToInt().coerceIn(2, 48)
            WallpaperStackBlur.blurInPlace(pixels, w, h, pixelRadius)
            mutable.setPixels(pixels, 0, w, 0, 0, w, h)
            writeWebpAtomically(mutable, destination, BLUR_WEBP_QUALITY)
        } finally {
            if (mutable !== working) mutable.recycle()
            if (working !== decoded) working.recycle()
            decoded.recycle()
        }
    }

    private fun decodeNormalizedBitmap(uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IOException("Unable to open image stream")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Unsupported image dimensions")
        }

        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val options = BitmapFactory.Options().apply {
            inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, MAX_STORED_DIMENSION)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Unable to decode image")

        val oriented = applyExifOrientation(decoded, orientation)
        val scaled = scaleDownIfNeeded(oriented, MAX_STORED_DIMENSION)
        if (scaled !== oriented) oriented.recycle()
        if (oriented !== decoded && scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun writeWebpAtomically(bitmap: Bitmap, destination: File, quality: Int) {
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, "${destination.name}.tmp")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        val ok = temp.outputStream().use { stream ->
            bitmap.compress(format, quality, stream)
        }
        if (!ok) {
            temp.delete()
            throw IOException("Failed to encode wallpaper WebP")
        }
        if (destination.exists()) destination.delete()
        if (!temp.renameTo(destination)) {
            temp.delete()
            throw IOException("Failed to commit wallpaper file")
        }
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / longest.toFloat()
        val targetW = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
        val targetH = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    companion object {
        private const val WALLPAPER_DIR_NAME = "wallpapers"
        private const val BLUR_CACHE_DIR_NAME = "wallpaper-blur"
        private const val MAX_STORED_DIMENSION = 2048
        private const val BLUR_WORKING_MAX_DIMENSION = 640
        private const val COLOR_SAMPLE_MAX_DIMENSION = 160
        private const val STORED_WEBP_QUALITY = 88
        private const val BLUR_WEBP_QUALITY = 84

        internal fun computeSampleSize(width: Int, height: Int, targetMaxDimension: Int): Int {
            var sample = 1
            val longest = max(width, height)
            while (longest / (sample * 2) >= targetMaxDimension) {
                sample *= 2
            }
            return sample
        }
    }
}
