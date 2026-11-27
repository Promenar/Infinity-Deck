package com.promenar.infinity.data.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import com.promenar.infinity.utils.InfinityLogger
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

data class ImportedWallpaperEntry(
    val fileName: String,
    val file: File,
    val lastModifiedMillis: Long,
)

/**
 * 聊天壁纸本地资产与预烘焙模糊缓存仓库：
 * - 外部相册图片优先通过 Android `ImageDecoder`（minSdk 31 原生可用）单次原子解码，自动处理 EXIF 旋转、HEIC/AVIF/ProXDR/10-bit 广色域；
 * - 落盘前统一规范化为 sRGB `ARGB_8888` 软位图并完整保留原图长宽比（最长边 2048px），便于内外屏独立无损构图；
 * - 保留最近导入的多张壁纸图片供随时切换与删除，模糊版本按 `(fileName, blurDp)` 在后台预烘焙到 `cacheDir/wallpaper-blur/`。
 */
class WallpaperAssetStore(
    private val context: Context,
) {
    private val wallpaperDir: File
        get() = File(context.filesDir, WALLPAPER_DIR_NAME).apply { mkdirs() }

    private val blurCacheDir: File
        get() = File(context.cacheDir, BLUR_CACHE_DIR_NAME).apply { mkdirs() }

    private val importScratchDir: File
        get() = File(context.cacheDir, IMPORT_SCRATCH_DIR_NAME).apply { mkdirs() }

    suspend fun importFromUri(uri: Uri): Result<WallpaperImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = decodeNormalizedBitmap(uri)
            try {
                persistNormalizedBitmap(bitmap)
            } finally {
                bitmap.recycle()
            }
        }.onFailure { error ->
            InfinityLogger.diagnostic(
                "wallpaper.import_failed",
                mapOf(
                    "scheme" to (uri.scheme ?: "unknown"),
                    "errorType" to error::class.java.simpleName,
                ),
            )
            InfinityLogger.logError(TAG, error)
        }
    }

    suspend fun importFromBitmap(source: Bitmap): Result<WallpaperImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val normalized = normalizeToArgb8888Scaled(source, MAX_STORED_DIMENSION)
            try {
                persistNormalizedBitmap(normalized)
            } finally {
                if (normalized !== source) normalized.recycle()
            }
        }.onFailure { error ->
            InfinityLogger.logError(TAG, error)
        }
    }

    /** 列出本机私有壁纸库中保存的已导入自定义壁纸（按最新修改时间降序）。 */
    suspend fun listImportedWallpapers(): List<ImportedWallpaperEntry> = withContext(Dispatchers.IO) {
        val dir = wallpaperDir.canonicalFile
        dir.listFiles()
            ?.asSequence()
            ?.filter { file ->
                file.isFile &&
                    file.length() > 0L &&
                    !file.name.endsWith(".tmp") &&
                    ChatWallpaperConfig.isSafeImageFileName(file.name)
            }
            ?.map { file ->
                ImportedWallpaperEntry(
                    fileName = file.name,
                    file = file,
                    lastModifiedMillis = file.lastModified(),
                )
            }
            ?.sortedByDescending { it.lastModifiedMillis }
            ?.toList()
            .orEmpty()
    }

    /** 从已有本地壁纸文件重新采样主视觉强调色（切换历史已导入图片时使用）。 */
    suspend fun extractAccentForFile(fileName: String): Int? = withContext(Dispatchers.IO) {
        val file = resolveOriginalFile(fileName) ?: return@withContext null
        runCatching {
            val bitmap = decodeFileScaled(file, COLOR_SAMPLE_MAX_DIMENSION) ?: return@runCatching null
            try {
                extractAccent(bitmap)
            } finally {
                bitmap.recycle()
            }
        }.getOrNull()
    }

    /** 安全删除单张已导入壁纸及其全部关联模糊缓存。 */
    suspend fun deleteImportedWallpaper(fileName: String): Boolean = withContext(Dispatchers.IO) {
        if (!ChatWallpaperConfig.isSafeImageFileName(fileName)) return@withContext false
        val target = resolveOriginalFile(fileName)
        val deleted = target?.let { runCatching { it.delete() }.getOrDefault(false) } ?: false
        deleteBlurCachesFor(fileName)
        deleted
    }

    /** 保留最近 [maxCount] 张已导入壁纸（始终保留当前使用的 [keepFileName]），清理超出上限的旧图及对应缓存。 */
    suspend fun enforceLibraryLimit(
        keepFileName: String? = null,
        maxCount: Int = MAX_LIBRARY_IMAGES,
    ) = withContext(Dispatchers.IO) {
        val safeKeep = keepFileName?.takeIf(ChatWallpaperConfig::isSafeImageFileName)
        val entries = listImportedWallpapers()
        if (entries.size <= maxCount) {
            pruneOrphanBlurCaches(entries.map { it.fileName }.toSet())
            return@withContext
        }
        val kept = LinkedHashSet<String>(maxCount)
        if (safeKeep != null && entries.any { it.fileName == safeKeep }) {
            kept.add(safeKeep)
        }
        for (entry in entries) {
            if (kept.size >= maxCount) break
            kept.add(entry.fileName)
        }
        entries.filter { it.fileName !in kept }.forEach { stale ->
            runCatching { stale.file.delete() }
            deleteBlurCachesFor(stale.fileName)
        }
        pruneOrphanBlurCaches(kept)
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

    /** 清理不再引用的历史自定义壁纸与旧模糊缓存（仅在显式全量重置时调用）。 */
    suspend fun pruneUnused(keepFileName: String?) = withContext(Dispatchers.IO) {
        val safeKeep = keepFileName?.takeIf(ChatWallpaperConfig::isSafeImageFileName)
        wallpaperDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name != safeKeep) {
                runCatching { file.delete() }
            }
        }
        pruneOrphanBlurCaches(safeKeep?.let { setOf(it) }.orEmpty())
    }

    private fun deleteBlurCachesFor(fileName: String) {
        val prefix = "${fileName.substringBeforeLast('.')}-b"
        blurCacheDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith(prefix)) {
                runCatching { file.delete() }
            }
        }
    }

    private fun pruneOrphanBlurCaches(validFileNames: Set<String>) {
        val validPrefixes = validFileNames.map { "${it.substringBeforeLast('.')}-b" }
        blurCacheDir.listFiles()?.forEach { file ->
            if (file.isFile && validPrefixes.none { file.name.startsWith(it) }) {
                runCatching { file.delete() }
            }
        }
    }

    private fun blurCacheFileFor(fileName: String, blurDp: Int): File {
        val stem = fileName.substringBeforeLast('.')
        return File(blurCacheDir, "${stem}-b${blurDp}.webp")
    }

    private fun persistNormalizedBitmap(bitmap: Bitmap): WallpaperImportResult {
        val safeBitmap = ensureArgb8888Software(bitmap)
        try {
            val accent = extractAccent(safeBitmap)
            val id = UUID.randomUUID().toString()
            val webpName = "wp-$id.webp"
            val webpDest = File(wallpaperDir, webpName)
            if (tryWriteEncodedAtomically(safeBitmap, webpDest, webpLossyFormat(), STORED_WEBP_QUALITY)) {
                return WallpaperImportResult(fileName = webpName, extractedAccentArgb = accent)
            }
            // 极少数厂商硬件编码器拒绝 WebP 时平滑退回 JPEG，确保外部照片稳定落盘。
            val jpgName = "wp-$id.jpg"
            val jpgDest = File(wallpaperDir, jpgName)
            if (tryWriteEncodedAtomically(safeBitmap, jpgDest, Bitmap.CompressFormat.JPEG, STORED_JPEG_QUALITY)) {
                return WallpaperImportResult(fileName = jpgName, extractedAccentArgb = accent)
            }
            throw IOException("Failed to encode wallpaper image")
        } finally {
            if (safeBitmap !== bitmap) safeBitmap.recycle()
        }
    }

    private fun extractAccent(bitmap: Bitmap): Int {
        val sample = normalizeToArgb8888Scaled(bitmap, COLOR_SAMPLE_MAX_DIMENSION)
        return try {
            val pixels = IntArray(sample.width * sample.height)
            sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
            WallpaperColorExtractor.extractAccentArgb(pixels)
        } finally {
            if (sample !== bitmap) sample.recycle()
        }
    }

    private fun bakeBlurredCopy(original: File, destination: File, blurDp: Int) {
        val decoded = decodeFileScaled(original, BLUR_WORKING_MAX_DIMENSION) ?: return
        val mutable = ensureMutableArgb8888(decoded)
        try {
            val w = mutable.width
            val h = mutable.height
            val pixels = IntArray(w * h)
            mutable.getPixels(pixels, 0, w, 0, 0, w, h)
            // 工作图约 640px 边长，按 1.6× 系数折算对应屏幕上 0–24dp 的柔和视觉散景。
            val pixelRadius = (blurDp * 1.6f).roundToInt().coerceIn(2, 48)
            WallpaperStackBlur.blurInPlace(pixels, w, h, pixelRadius)
            mutable.setPixels(pixels, 0, w, 0, 0, w, h)
            if (!tryWriteEncodedAtomically(mutable, destination, webpLossyFormat(), BLUR_WEBP_QUALITY)) {
                tryWriteEncodedAtomically(mutable, destination, Bitmap.CompressFormat.JPEG, BLUR_WEBP_QUALITY)
            }
        } finally {
            if (mutable !== decoded) mutable.recycle()
            decoded.recycle()
        }
    }

    private fun decodeNormalizedBitmap(uri: Uri): Bitmap {
        // 优先走 ImageDecoder 单次原子解码（原生兼容 HEIC/HEIF/AVIF/UltraHDR/自动 EXIF 方向，不重复打开 URI 流）。
        val direct = runCatching {
            decodeWithImageDecoder(ImageDecoder.createSource(context.contentResolver, uri), MAX_STORED_DIMENSION)
        }.getOrNull()
        if (direct != null) return normalizeToArgb8888Scaled(direct, MAX_STORED_DIMENSION).also {
            if (it !== direct) direct.recycle()
        }

        // 部分第三方图库/云端选择器返回一次性不可回绕管道流：先单次拷贝到私有缓存文件，再走本地安全解码。
        val scratch = copyUriToScratchFile(uri)
        try {
            val fromFile = runCatching {
                decodeWithImageDecoder(ImageDecoder.createSource(scratch), MAX_STORED_DIMENSION)
            }.getOrNull() ?: decodeFileWithBitmapFactory(scratch, MAX_STORED_DIMENSION)
                ?: throw IOException("Unable to decode image from URI")
            return normalizeToArgb8888Scaled(fromFile, MAX_STORED_DIMENSION).also {
                if (it !== fromFile) fromFile.recycle()
            }
        } finally {
            runCatching { scratch.delete() }
        }
    }

    private fun copyUriToScratchFile(uri: Uri): File {
        val temp = File(importScratchDir, "import-${UUID.randomUUID()}.bin")
        val resolver = context.contentResolver
        val opened = runCatching { resolver.openInputStream(uri) }.getOrNull()
            ?: runCatching { resolver.openAssetFileDescriptor(uri, "r")?.createInputStream() }.getOrNull()
            ?: throw IOException("Unable to open image stream")
        opened.use { input ->
            temp.outputStream().use { output ->
                val copied = input.copyTo(output)
                if (copied <= 0L) throw IOException("Image stream is empty")
            }
        }
        return temp
    }

    private fun decodeWithImageDecoder(source: ImageDecoder.Source, maxDimension: Int): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val width = info.size.width
            val height = info.size.height
            val longest = max(width, height)
            if (longest > maxDimension && width > 0 && height > 0) {
                val ratio = maxDimension.toFloat() / longest.toFloat()
                decoder.setTargetSize(
                    (width * ratio).roundToInt().coerceAtLeast(1),
                    (height * ratio).roundToInt().coerceAtLeast(1),
                )
            }
        }

    private fun decodeFileScaled(file: File, maxDimension: Int): Bitmap? =
        runCatching { decodeWithImageDecoder(ImageDecoder.createSource(file), maxDimension) }.getOrNull()
            ?: decodeFileWithBitmapFactory(file, maxDimension)

    private fun decodeFileWithBitmapFactory(file: File, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val options = BitmapFactory.Options().apply {
            inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        val oriented = applyExifOrientation(decoded, orientation)
        val scaled = scaleDownIfNeeded(oriented, maxDimension)
        if (scaled !== oriented) oriented.recycle()
        if (oriented !== decoded && scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun normalizeToArgb8888Scaled(source: Bitmap, maxDimension: Int): Bitmap {
        val scaled = scaleDownIfNeeded(source, maxDimension)
        val argb = ensureArgb8888Software(scaled)
        if (argb !== scaled && scaled !== source) scaled.recycle()
        return argb
    }

    /** 统一绘制到 sRGB ARGB_8888 软位图，剥离 10-bit / F16 / HARDWARE / Gainmap 以避免编码器失败。 */
    private fun ensureArgb8888Software(bitmap: Bitmap): Bitmap {
        val hasGainmap = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && bitmap.hasGainmap()
        val isSrgb = bitmap.colorSpace == null || bitmap.colorSpace == ColorSpace.get(ColorSpace.Named.SRGB)
        if (bitmap.config == Bitmap.Config.ARGB_8888 && !hasGainmap && isSrgb) return bitmap
        val out = Bitmap.createBitmap(bitmap.width.coerceAtLeast(1), bitmap.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bitmap, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun ensureMutableArgb8888(bitmap: Bitmap): Bitmap {
        val soft = ensureArgb8888Software(bitmap)
        if (soft.isMutable && soft.config == Bitmap.Config.ARGB_8888) return soft
        val copy = soft.copy(Bitmap.Config.ARGB_8888, true)
        if (soft !== bitmap) soft.recycle()
        return copy
    }

    private fun webpLossyFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

    private fun tryWriteEncodedAtomically(
        bitmap: Bitmap,
        destination: File,
        format: Bitmap.CompressFormat,
        quality: Int,
    ): Boolean {
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, "${destination.name}.tmp")
        val ok = runCatching {
            temp.outputStream().use { stream -> bitmap.compress(format, quality, stream) }
        }.getOrDefault(false)
        if (!ok || temp.length() <= 0L) {
            runCatching { temp.delete() }
            return false
        }
        if (destination.exists()) runCatching { destination.delete() }
        if (!temp.renameTo(destination)) {
            runCatching { temp.delete() }
            return false
        }
        return true
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
        private const val TAG = "WallpaperAssetStore"
        private const val WALLPAPER_DIR_NAME = "wallpapers"
        private const val BLUR_CACHE_DIR_NAME = "wallpaper-blur"
        private const val IMPORT_SCRATCH_DIR_NAME = "wallpaper-import"
        private const val MAX_STORED_DIMENSION = 2048
        private const val BLUR_WORKING_MAX_DIMENSION = 640
        private const val COLOR_SAMPLE_MAX_DIMENSION = 160
        private const val STORED_WEBP_QUALITY = 88
        private const val STORED_JPEG_QUALITY = 90
        private const val BLUR_WEBP_QUALITY = 84
        const val MAX_LIBRARY_IMAGES = 12

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
