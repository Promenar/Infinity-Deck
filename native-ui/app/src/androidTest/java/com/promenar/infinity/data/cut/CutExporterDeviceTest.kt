package com.promenar.infinity.data.cut

import android.content.Context
import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.promenar.infinity.data.production.*
import com.promenar.infinity.domain.media.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 素材全部由本机代码生成，不依赖网络、外部账号或预置媒体。 */
@RunWith(AndroidJUnit4::class)
class CutExporterDeviceTest {
    @Test fun differentSizesAndFrameRatesWithSilentVideoDubbingMusicAndTitleCard() = runBlocking {
        withTimeout(120_000) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val directory = withContext(Dispatchers.IO) { java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "cut-device-").toFile() }
            try {
                withContext(Dispatchers.IO) {
                    video(File(directory, "a.mp4"), 320, 240, 24, 2, 72)
                    video(File(directory, "b.mp4"), 640, 360, 30, 2, 160)
                    wave(File(directory, "voice.wav"), 1.2)
                    wave(File(directory, "music.wav"), 6.0)
                }
                val resolver = LocalResolver(directory)
                val probe = WorkspaceCutMediaProbe(context, resolver)
                val metadata = listOf("test/a.mp4", "test/b.mp4", "test/voice.wav", "test/music.wav").associateWith { probe.read("s", it) }
                fun take(id: String, file: String) = Take(id, "test/$file", TakeSource("设备素材"), 1)
                val first = Shot("SC01-01", "第一镜", durationSeconds = 2.0, takes = listOf(take("a", "a.mp4")), selectedTakeId = "a",
                    dialogue = listOf(ShotDialogue("char", "第一句 <测试>", audioSource = DialogueAudioSource.DUBBED), ShotDialogue("char", "第二句")),
                    audio = listOf(ShotAudio("voice", "test/voice.wav", 0, "task", TakePlanSnapshot(mode = ShotMode.TEXT, inputs = emptyList(), prompt = "配音"), 1)))
                val second = Shot("SC01-02", "第二镜", durationSeconds = 2.0, takes = listOf(take("b", "b.mp4")), selectedTakeId = "b",
                    dialogue = listOf(ShotDialogue("char", "第二镜字幕")))
                val gap = Shot("SC01-03", "缺口标题", durationSeconds = 1.0)
                val production = Production("p", "设备测试", ProductionKind.FILM, "test", createdAt = 1, updatedAt = 1,
                    seasons = listOf(Season("S1", 1, episodes = listOf(Episode("S1E01", 1, "第一集", scenes = listOf(Scene("SC01", 1, "场", shots = listOf(first, second, gap))))))),
                    assets = listOf(Asset("music", AssetKind.SOUND, "配乐", versions = listOf(AssetVersion(1, "test/music.wav", 1)), currentVersion = 1, status = AssetStatus.LOCKED)))
                val created = ProductionCuts.create(production, "S1E01", metadata.mapValues { requireNotNull(it.value.durationSeconds) }, 2)
                val cut = created.cuts.single().copy(burnSubtitles = true)
                val plan = CutCompositionPlanner.plan(created, cut, metadata)
                val artifact = Media3CutExporter(context, resolver).export("s", plan) { progress ->
                    assertTrue(progress == null || progress in 0f..1f)
                }
                try {
                    withContext(Dispatchers.IO) {
                        MediaMetadataRetriever().use { retriever ->
                            retriever.setDataSource(artifact.video.path)
                            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toDouble() / 1000
                            assertEquals(plan.durationSeconds, duration, 0.2)
                            assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                            assertEquals(plan.width.toString(), retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                            assertEquals(plan.height.toString(), retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
                            // 检查不同镜头、标题卡与烧录字幕所在帧均可解码。
                            listOf(500_000L, ((plan.segments[1].startSeconds + 0.5) * 1_000_000).toLong(), ((plan.durationSeconds - 0.5) * 1_000_000).toLong()).forEach { time ->
                                val frame = retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST)
                                assertNotNull(frame)
                                frame?.recycle()
                            }
                        }
                        val srt = File(directory, "result.srt").apply { writeText(plan.srt, Charsets.UTF_8) }.readText()
                        assertTrue(srt.startsWith("1\n00:00:00,000 --> "))
                        assertTrue(srt.contains("第一句 &lt;测试&gt;"))
                        assertTrue(srt.contains("第二镜字幕"))
                        assertFalse(srt.contains("char"))
                        assertEquals(3, plan.subtitles.size)
                    }
                } finally { withContext(NonCancellable + Dispatchers.IO) { artifact.close() } }
            } finally { withContext(NonCancellable + Dispatchers.IO) { directory.deleteRecursively() } }
        }
    }

    private class LocalResolver(private val directory: File) : WorkspaceMediaResolver {
        override suspend fun productionPath(sessionId: String, path: String) = path
        override suspend fun resolve(sessionId: String, path: String): MediaResolution {
            val file = File(directory, path.removePrefix("test/"))
            val audio = file.extension == "wav"
            return MediaResolution.Available(MediaRef(path, if (audio) "audio/wav" else "video/mp4", if (audio) MediaKind.AUDIO else MediaKind.VIDEO, file.length()), MediaSource.LocalFile(file))
        }
        override suspend fun open(resolution: MediaResolution.Available) = (resolution.source as MediaSource.LocalFile).file.inputStream()
    }

    private fun wave(file: File, seconds: Double) {
        val rate = 48_000
        val frames = (seconds * rate).toInt()
        val size = frames * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(size)
        file.outputStream().use { output ->
            output.write(header.array())
            val pcm = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames) { index -> pcm.putShort((sin(2 * Math.PI * 440 * index / rate) * 3500).toInt().toShort()) }
            output.write(pcm.array())
        }
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
                                val value = if (planeIndex == 0) luminance.toByte() else 128.toByte()
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
