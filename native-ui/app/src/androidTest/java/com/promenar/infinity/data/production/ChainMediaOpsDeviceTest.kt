package com.promenar.infinity.data.production

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.promenar.infinity.domain.media.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 在设备本地生成三段定帧率素材，无需网络、模型或预置资产。 */
@RunWith(AndroidJUnit4::class)
class ChainMediaOpsDeviceTest {
    @Test fun concatenateThreeSegmentsTrimsExactlyOneFrameFromEachFollowingSegment() = runBlocking {
        withTimeout(120_000) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val directory = withContext(Dispatchers.IO) { java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "chain-device-").toFile() }
            try {
                withContext(Dispatchers.IO) { repeat(3) { video(File(directory, "seg-$it.mp4"), 320, 240, 25, 1, 64 + it * 32) } }
                val io = LocalMedia(directory)
                val ops = WorkspaceChainMediaOps(context, io, io)
                val paths = List(3) { "test/seg-$it.mp4" }
                val result = ops.concatenate("s", paths, "test/joined.mp4")
                assertEquals(2.92, requireNotNull(result.spec.durationSeconds), 0.04)
                assertEquals(2.92, result.segments.last().endSeconds, 0.005)
                withContext(Dispatchers.IO) { assertEquals(73, frameCount(File(directory, "joined.mp4"))) }
                // 恢复后重复拼接不会新建另一份产物或重复裁帧。
                val recovered = ops.concatenate("s", paths, "test/joined.mp4")
                assertEquals(result, recovered)
            } finally { withContext(NonCancellable + Dispatchers.IO) { directory.deleteRecursively() } }
        }
    }
    @Test fun extractsLastDecodedFrameAsReadableImage() = runBlocking {
        withTimeout(60_000) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val directory = withContext(Dispatchers.IO) { java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "tail-device-").toFile() }
            try {
                withContext(Dispatchers.IO) { video(File(directory, "source.mp4"), 320, 240, 25, 1, 64) }
                val io = LocalMedia(directory); val ops = WorkspaceChainMediaOps(context, io, io)
                ops.tailFrame("s", "test/source.mp4", "test/last.png")
                val imageSpec = ops.spec("s", "test/last.png")
                assertEquals(320, imageSpec.width); assertEquals(240, imageSpec.height); assertNull(imageSpec.durationSeconds)
                withContext(Dispatchers.IO) {
                    val last = requireNotNull(BitmapFactory.decodeFile(File(directory, "last.png").path))
                    try {
                        assertEquals(320, last.width); assertEquals(240, last.height)
                        MediaMetadataRetriever().use { r ->
                            r.setDataSource(File(directory, "source.mp4").path)
                            val first = requireNotNull(r.getFrameAtIndex(0))
                            try { assertTrue(Color.red(last.getPixel(160, 120)) > Color.red(first.getPixel(160, 120)) + 10) }
                            finally { first.recycle() }
                        }
                    } finally { last.recycle() }
                }
                ops.tailFrame("s", "test/source.mp4", "test/last.png")
            } finally { withContext(NonCancellable + Dispatchers.IO) { directory.deleteRecursively() } }
        }
    }
    private class LocalMedia(private val directory: File) : WorkspaceMediaResolver, WorkspaceMediaStore {
        private fun file(path: String) = File(directory, path.removePrefix("/").removePrefix("test/"))
        override suspend fun productionPath(sessionId: String, path: String) = path
        override suspend fun resolve(sessionId: String, path: String): MediaResolution {
            val file = file(path)
            if (!file.isFile) return MediaResolution.Unavailable(path, MediaUnavailableReason.NOT_FOUND)
            val image = file.extension == "png"
            return MediaResolution.Available(MediaRef(path, if (image) "image/png" else "video/mp4", if (image) MediaKind.IMAGE else MediaKind.VIDEO, file.length()), MediaSource.LocalFile(file))
        }
        override suspend fun open(resolution: MediaResolution.Available) = (resolution.source as MediaSource.LocalFile).file.inputStream()
        override suspend fun save(sessionId: String, payload: MediaPayload, origin: MediaOrigin, mimeTypeHint: String?, suggestedName: String?, targetPath: String?): MediaRef {
            val path = requireNotNull(targetPath); val file = file(path)
            require(!file.exists()); file.parentFile?.mkdirs()
            file.outputStream().use { output -> when (payload) {
                is MediaPayload.Bytes -> output.write(payload.bytes)
                is MediaPayload.Stream -> payload.open().use { it.copyTo(output) }
            } }
            return (resolve(sessionId, path) as MediaResolution.Available).ref
        }
    }
    private fun frameCount(file: File): Int {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val index = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            extractor.selectTrack(index)
            var count = 0
            while (extractor.sampleTime >= 0) { count++; if (!extractor.advance()) break }
            return count
        } finally { extractor.release() }
    }
    private fun video(file: File, width: Int, height: Int, fps: Int, seconds: Int, luminance: Int) {
        val codec = MediaCodec.createEncoderByType("video/avc")
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var track = -1
        try {
            codec.configure(MediaFormat.createVideoFormat("video/avc", width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 600_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var frame = 0
            var inputDone = false
            var outputDone = false
            val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
            while (!outputDone) {
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "设备素材编码超时" }
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        if (frame == fps * seconds) {
                            codec.queueInputBuffer(index, 0, 0, seconds * 1_000_000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val image = requireNotNull(codec.getInputImage(index))
                            check(image.format == ImageFormat.YUV_420_888)
                            image.planes.forEachIndexed { planeIndex, plane ->
                                val planeWidth = if (planeIndex == 0) width else width / 2
                                val planeHeight = if (planeIndex == 0) height else height / 2
                                val value = if (planeIndex == 0) (luminance + frame).toByte() else 128.toByte()
                                val base = plane.buffer.position()
                                repeat(planeHeight) { row -> repeat(planeWidth) { column ->
                                    plane.buffer.put(base + row * plane.rowStride + column * plane.pixelStride, value)
                                } }
                            }
                            codec.queueInputBuffer(index, 0, width * height * 3 / 2, frame * 1_000_000L / fps, 0)
                            frame++
                        }
                    }
                }
                var index = codec.dequeueOutputBuffer(info, 10_000)
                while (index != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(!muxerStarted)
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    } else if (index >= 0) {
                        val buffer = requireNotNull(codec.getOutputBuffer(index))
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            check(muxerStarted)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                    if (outputDone) break
                    index = codec.dequeueOutputBuffer(info, 0)
                }
            }
        } finally {
            try {
                try { codec.stop() } finally { codec.release() }
            } finally {
                try { if (muxerStarted) muxer.stop() } finally { muxer.release() }
            }
        }
    }
}
