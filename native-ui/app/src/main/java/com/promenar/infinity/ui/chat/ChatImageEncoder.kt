package com.promenar.infinity.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.promenar.infinity.data.media.annotation.ImageAnnotationPreviewCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 把用户选择的图片降采样并编码为 JPEG data URL，作为视觉模型输入。
 *
 * [openStream] 每次调用都需返回一个新的输入流：先只读尺寸，再按尺寸选择采样率解码。
 * 无法打开、不是可解码图片或编码失败时返回 null。
 */
internal object ChatImageEncoder {
    const val MAX_DIMENSION_PX = 1568
    const val JPEG_QUALITY = 85

    /** 画板预览先剥离私有 `inAn` 块再解码：编辑状态不外发，部分解码器也不接受未知块。 */
    fun encodeAnnotatedPreview(png: ByteArray): String? =
        runCatching { ImageAnnotationPreviewCodec.strip(png) }.getOrNull()?.let { stripped -> encodeToDataUrl { ByteArrayInputStream(stripped) } }

    fun encodeToDataUrl(openStream: () -> InputStream?): String? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // 只读尺寸时 decodeStream 按设计返回 null，可读与否以解析出的宽高为准。
        openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: Unit
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            }
            openStream()?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }?.let { bitmap ->
                val output = ByteArrayOutputStream()
                val compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                bitmap.recycle()
                if (compressed && output.size() > 0) {
                    "data:image/jpeg;base64,${Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)}"
                } else {
                    null
                }
            }
        } else {
            null
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    /** 取 2 的幂采样率，使解码后的长边不小于 [MAX_DIMENSION_PX] 的一半且尽量接近上限。 */
    fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= MAX_DIMENSION_PX) sample *= 2
        return sample
    }
}
