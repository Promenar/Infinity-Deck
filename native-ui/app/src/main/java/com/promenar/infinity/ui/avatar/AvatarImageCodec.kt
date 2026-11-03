package com.promenar.infinity.ui.avatar

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import java.io.IOException

/** 编辑器使用的源图：原始像素尺寸、EXIF 方向，以及按长边上限采样后的显示位图。 */
internal class AvatarSourceImage(
    val uri: Uri,
    val geometry: AvatarCropGeometry,
    val preview: Bitmap,
)

/** 头像源图解码与输出。所有方法都会阻塞，调用方负责放到 IO 线程。 */
internal object AvatarImageCodec {

    fun load(resolver: ContentResolver, uri: Uri): AvatarSourceImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(resolver, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) throw IOException("无法识别图片尺寸")

        val exif = runCatching {
            open(resolver, uri).use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val options = BitmapFactory.Options().apply {
            inSampleSize = AvatarSampling.forDisplay(width, height)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val preview = open(resolver, uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("无法解码图片")
        return AvatarSourceImage(
            uri = uri,
            geometry = AvatarCropGeometry(width, height, AvatarOrientation.fromExif(exif)),
            preview = preview,
        )
    }

    /**
     * 按取景状态生成方形 JPEG 写入 [destination]。优先对裁剪区域做区域解码以保留原图细节，
     * 格式不支持区域解码时退回到显示位图。
     */
    fun export(resolver: ContentResolver, source: AvatarSourceImage, state: AvatarCropState, destination: Uri) {
        val geometry = source.geometry
        val size = AvatarCropGeometry.OUTPUT_SIZE
        val region = geometry.sourceCropBounds(state)
        val decoded = decodeRegion(resolver, source.uri, region)
        val (bitmap, transform) = if (decoded != null) {
            decoded to geometry.regionTransform(state, region, decoded.width, decoded.height, size)
        } else {
            val whole = AvatarPixelRect(0, 0, geometry.rawWidth, geometry.rawHeight)
            source.preview to geometry.regionTransform(state, whole, source.preview.width, source.preview.height, size)
        }
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            Canvas(output).apply {
                // JPEG 没有透明通道，透明区域按白底输出，避免变成黑色。
                drawColor(Color.WHITE)
                drawBitmap(
                    bitmap,
                    Matrix().apply { setValues(transform.toMatrixValues()) },
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                )
            }
            val written = resolver.openOutputStream(destination, "wt")?.use { stream ->
                output.compress(Bitmap.CompressFormat.JPEG, AvatarCropGeometry.OUTPUT_JPEG_QUALITY, stream)
            } ?: false
            if (!written) throw IOException("无法写入头像文件")
        } finally {
            output.recycle()
            decoded?.recycle()
        }
    }

    private fun decodeRegion(resolver: ContentResolver, uri: Uri, region: AvatarPixelRect): Bitmap? = runCatching {
        open(resolver, uri).use { stream ->
            val decoder = BitmapRegionDecoder.newInstance(stream) ?: return@use null
            try {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = AvatarSampling.forRegion(region.width, region.height)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                decoder.decodeRegion(Rect(region.left, region.top, region.right, region.bottom), options)
            } finally {
                decoder.recycle()
            }
        }
    }.getOrNull()

    private fun open(resolver: ContentResolver, uri: Uri) =
        resolver.openInputStream(uri) ?: throw IOException("无法打开图片")
}
