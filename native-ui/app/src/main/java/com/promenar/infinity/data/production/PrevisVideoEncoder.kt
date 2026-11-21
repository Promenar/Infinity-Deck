package com.promenar.infinity.data.production

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.util.Base64
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 3D 白模预演 H.264 视频编码结果。
 */
data class PrevisVideoResult(
    val file: File,
    val width: Int,
    val height: Int,
    val durationSeconds: Double,
    val frameCount: Int,
    val sizeBytes: Long,
)

/** 计算指定时长与帧率应生成的确定性总帧数（至少 1 帧）。 */
fun previsFrameCount(totalDurationSeconds: Double, fps: Int = 24): Int {
    require(fps > 0) { "fps 必须大于 0。" }
    if (!totalDurationSeconds.isFinite() || totalDurationSeconds <= 0.0) return 1
    return max(1, ceil(totalDurationSeconds * fps).roundToInt())
}

/** 计算第 [frameIndex] 帧的严格微秒时间戳（无 B 帧、恒定帧间隔，与抓帧耗时解耦）。 */
fun previsFrameTimestampUs(frameIndex: Int, fps: Int = 24): Long {
    require(frameIndex >= 0 && fps > 0) { "帧号非负且 fps 必须大于 0。" }
    return frameIndex.toLong() * 1_000_000L / fps.toLong()
}

/**
 * 解析 WebView `evaluateJavascript` 返回的 JSON 编码 data URL（如 `"data:image/png;base64,..."`）。
 */
fun decodePrevisDataUrl(jsResult: String?): ByteArray? {
    val raw = jsResult?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
    val unwrapped = if (raw.startsWith('"') && raw.endsWith('"') && raw.length >= 2) {
        raw.substring(1, raw.length - 1).replace("\\/", "/")
    } else {
        raw
    }
    val marker = ";base64,"
    val idx = unwrapped.indexOf(marker)
    if (idx < 0) return null
    val payload = unwrapped.substring(idx + marker.length)
    if (payload.isBlank()) return null
    return Base64.getMimeDecoder().decode(payload)
}

/**
 * 将 ARGB_8888 像素数组按 BT.601 studio range 写入任意 YUV420 平面布局（支持 I420 planar 与 NV12/NV21 semi-planar）。
 */
fun argbToYuv420Planes(
    argb: IntArray,
    width: Int,
    height: Int,
    yBuffer: ByteBuffer,
    yRowStride: Int,
    yPixelStride: Int,
    uBuffer: ByteBuffer,
    uRowStride: Int,
    uPixelStride: Int,
    vBuffer: ByteBuffer,
    vRowStride: Int,
    vPixelStride: Int,
) {
    require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
        "YUV420 编码宽高必须为正偶数，当前 ${width}x${height}。"
    }
    require(argb.size >= width * height) { "像素数组长度不足 ${width * height}。" }

    for (y in 0 until height) {
        val srcRow = y * width
        val yDstRow = y * yRowStride
        for (x in 0 until width) {
            val px = argb[srcRow + x]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val lum = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(16, 235)
            yBuffer.put(yDstRow + x * yPixelStride, lum.toByte())
        }
    }

    val halfW = width / 2
    val halfH = height / 2
    for (cy in 0 until halfH) {
        val r0 = (cy * 2) * width
        val r1 = r0 + width
        val uRow = cy * uRowStride
        val vRow = cy * vRowStride
        for (cx in 0 until halfW) {
            val c = cx * 2
            val p00 = argb[r0 + c]
            val p01 = argb[r0 + c + 1]
            val p10 = argb[r1 + c]
            val p11 = argb[r1 + c + 1]

            val r = (((p00 shr 16) and 0xFF) + ((p01 shr 16) and 0xFF) + ((p10 shr 16) and 0xFF) + ((p11 shr 16) and 0xFF) + 2) shr 2
            val g = (((p00 shr 8) and 0xFF) + ((p01 shr 8) and 0xFF) + ((p10 shr 8) and 0xFF) + ((p11 shr 8) and 0xFF) + 2) shr 2
            val b = ((p00 and 0xFF) + (p01 and 0xFF) + (p10 and 0xFF) + (p11 and 0xFF) + 2) shr 2

            val cb = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(16, 240)
            val cr = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(16, 240)

            uBuffer.put(uRow + cx * uPixelStride, cb.toByte())
            vBuffer.put(vRow + cx * vPixelStride, cr.toByte())
        }
    }
}

/**
 * 零 FFmpeg 依赖的 Android 原生 H.264 预演视频流式编码器（`MediaCodec` + `MediaMuxer`）。
 * 从调用方拉取每帧预演 `Bitmap` 并以确定性 24fps 时间戳写入 MP4。
 */
class PrevisVideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int = 24,
) {
    init {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "视频编码分辨率须为正偶数，当前 ${width}x${height}。"
        }
        require(fps > 0) { "fps 必须大于 0。" }
    }

    suspend fun encode(
        outputFile: File,
        totalDurationSeconds: Double,
        captureFrame: suspend (globalTimeSec: Double) -> Bitmap,
        onProgress: (Float) -> Unit,
    ): PrevisVideoResult = withContext(Dispatchers.IO) {
        val frameCount = previsFrameCount(totalDurationSeconds, fps)
        val codecName = findH264EncoderName(width, height, fps)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, recommendedBitrate(width, height))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }

        val codec = if (codecName != null) {
            MediaCodec.createByCodecName(codecName)
        } else {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        }
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        var trackIndex = -1
        var muxerStarted = false
        var codecStarted = false
        val bufferInfo = MediaCodec.BufferInfo()
        val argb = IntArray(width * height)

        fun drainOutput(endOfStream: Boolean) {
            while (true) {
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 15_000L else 0L)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) return
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "编码器输出格式重复变更。" }
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val encodedData = codec.getOutputBuffer(outIndex)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && muxerStarted && encodedData != null) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            return
                        }
                    }
                }
            }
        }

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            codecStarted = true
            val inputFormat = runCatching { codec.inputFormat }.getOrNull()
            val stride = inputFormat?.let {
                if (it.containsKey(MediaFormat.KEY_STRIDE)) it.getInteger(MediaFormat.KEY_STRIDE) else null
            }?.coerceAtLeast(width) ?: width
            val sliceHeight = inputFormat?.let {
                if (it.containsKey(MediaFormat.KEY_SLICE_HEIGHT)) it.getInteger(MediaFormat.KEY_SLICE_HEIGHT) else null
            }?.coerceAtLeast(height) ?: height
            val expectedYuvBytes = maxOf(width * height * 3 / 2, stride * sliceHeight * 3 / 2)

            for (frameIdx in 0 until frameCount) {
                currentCoroutineContext().ensureActive()
                val timeSec = frameIdx.toDouble() / fps.toDouble()
                val rawBitmap = captureFrame(timeSec)
                val scaled = if (rawBitmap.width == width && rawBitmap.height == height) {
                    rawBitmap
                } else {
                    Bitmap.createScaledBitmap(rawBitmap, width, height, true).also { rawBitmap.recycle() }
                }
                try {
                    scaled.getPixels(argb, 0, width, 0, 0, width, height)
                } finally {
                    scaled.recycle()
                }

                var inputIndex = -1
                while (inputIndex < 0) {
                    currentCoroutineContext().ensureActive()
                    drainOutput(endOfStream = false)
                    inputIndex = codec.dequeueInputBuffer(20_000L)
                }

                val inputCapacity = codec.getInputBuffer(inputIndex)?.capacity()
                val queueSize = minOf(inputCapacity ?: expectedYuvBytes, expectedYuvBytes)
                val image = codec.getInputImage(inputIndex)
                    ?: error("设备 H.264 编码器不支持 YUV420Flexible 输入图像。")
                try {
                    val planes = image.planes
                    require(planes.size >= 3) { "编码器 YUV 输入平面不足 3 层。" }
                    argbToYuv420Planes(
                        argb = argb,
                        width = width,
                        height = height,
                        yBuffer = planes[0].buffer,
                        yRowStride = planes[0].rowStride,
                        yPixelStride = planes[0].pixelStride,
                        uBuffer = planes[1].buffer,
                        uRowStride = planes[1].rowStride,
                        uPixelStride = planes[1].pixelStride,
                        vBuffer = planes[2].buffer,
                        vRowStride = planes[2].rowStride,
                        vPixelStride = planes[2].pixelStride,
                    )
                } finally {
                    image.close()
                }

                val ptsUs = previsFrameTimestampUs(frameIdx, fps)
                codec.queueInputBuffer(inputIndex, 0, queueSize, ptsUs, 0)
                drainOutput(endOfStream = false)
                onProgress(((frameIdx + 1).toFloat() / frameCount.toFloat()).coerceIn(0f, 0.99f))
            }

            var eosIndex = -1
            while (eosIndex < 0) {
                currentCoroutineContext().ensureActive()
                drainOutput(endOfStream = false)
                eosIndex = codec.dequeueInputBuffer(20_000L)
            }
            val eosPtsUs = previsFrameTimestampUs(frameCount, fps)
            codec.queueInputBuffer(eosIndex, 0, 0, eosPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drainOutput(endOfStream = true)
        } finally {
            runCatching { if (codecStarted) codec.stop() }
            runCatching { codec.release() }
            runCatching { if (muxerStarted) muxer.stop() }
            runCatching { muxer.release() }
        }

        val verified = verifyEncodedMp4(outputFile)
        onProgress(1f)
        PrevisVideoResult(
            file = outputFile,
            width = verified.first,
            height = verified.second,
            durationSeconds = verified.third,
            frameCount = frameCount,
            sizeBytes = outputFile.length(),
        )
    }

    private fun verifyEncodedMp4(file: File): Triple<Int, Int, Double> {
        require(file.isFile && file.length() > 0L) { "预演视频编码结果为空文件。" }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            require(hasVideo == "yes") { "导出的 MP4 缺少有效视频轨。" }
            val outW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: width
            val outH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: height
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0
            require(durationMs > 0.0) { "导出的 MP4 时长为 0。" }
            Triple(outW, outH, durationMs / 1000.0)
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        private fun recommendedBitrate(width: Int, height: Int): Int {
            val pixels = width.toLong() * height.toLong()
            return (pixels * 4.8).toInt().coerceIn(1_500_000, 6_000_000)
        }

        private fun findH264EncoderName(width: Int, height: Int, fps: Int): String? {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            val matching = codecs.filter { info ->
                if (!info.isEncoder) return@filter false
                val caps = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull()
                    ?: return@filter false
                val supportsYuv = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in caps.colorFormats
                val supportsSize = caps.videoCapabilities?.areSizeAndRateSupported(width, height, fps.toDouble()) ?: true
                supportsYuv && supportsSize
            }
            return matching.firstOrNull { !it.isSoftwareOnly }?.name ?: matching.firstOrNull()?.name
        }
    }
}
