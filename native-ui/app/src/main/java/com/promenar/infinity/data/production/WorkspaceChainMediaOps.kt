package com.promenar.infinity.data.production

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import com.promenar.infinity.domain.media.*
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.*

/** 输入、输出均经工作区媒体边界；本地临时文件只在处理期间存在。 */
@androidx.annotation.OptIn(UnstableApi::class)
class WorkspaceChainMediaOps(
    private val context: Context, private val resolver: WorkspaceMediaResolver, private val store: WorkspaceMediaStore,
    private val publish: (suspend (String, String, File) -> Unit)? = null,
) : ChainMediaOps {
    private suspend fun resolve(session: String, path: String): MediaResolution.Available {
        ProductionManifestCodec.validatePath(path)
        return resolver.resolve(session, resolver.productionPath(session, path)) as? MediaResolution.Available
            ?: throw IllegalArgumentException("制作媒体不可读：$path")
    }
    private fun MediaMetadataRetriever.source(available: MediaResolution.Available) {
        when (val source = available.source) {
            is MediaSource.LocalFile -> setDataSource(source.file.path)
            is MediaSource.ContentUri -> setDataSource(context, Uri.parse(source.uri))
        }
    }
    override suspend fun spec(sessionId: String, path: String): MediaSpec = withContext(Dispatchers.IO) {
        val available = resolve(sessionId, path)
        if (available.ref.kind == MediaKind.IMAGE) {
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.open(available).use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
            require(options.outWidth > 0 && options.outHeight > 0) { "无法读取图片规格。" }
            return@withContext MediaSpec(width = options.outWidth, height = options.outHeight)
        }
        require(available.ref.kind in setOf(MediaKind.VIDEO, MediaKind.AUDIO)) { "需要视频或音频媒体。" }
        readVideoSpec(available)
    }
    private fun readVideoSpec(available: MediaResolution.Available): MediaSpec {
        return MediaMetadataRetriever().use { r ->
            r.source(available)
            val seconds = requireNotNull(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000)) { "无法读取媒体时长。" }
            require(seconds.isFinite() && seconds > 0) { "媒体时长无效。" }
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val count = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toDoubleOrNull()
            val fps = count?.takeIf { it > 0 }?.div(seconds) ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
            MediaSpec(if (rotation % 180 == 0) width else height, if (rotation % 180 == 0) height else width, fps?.takeIf { it.isFinite() && it > 0 }, seconds)
        }
    }
    override suspend fun copy(sessionId: String, source: String, target: String): Unit = withContext(Dispatchers.IO) {
        val input = resolve(sessionId, source)
        require(input.ref.kind == MediaKind.VIDEO) { "衔接起点必须是视频。" }
        val directory = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "chain-copy-").toFile()
        try {
            val file = File(directory, "segment.mp4")
            resolver.open(input).use { sourceStream -> file.outputStream().use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) { currentCoroutineContext().ensureActive(); val n = sourceStream.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
            } }
            saveFile(sessionId, target, file, "video/mp4")
        } finally { withContext(NonCancellable + Dispatchers.IO) { directory.deleteRecursively() } }
    }
    // 媒体存储负责关闭输入流；固定链文件名由发布器执行可恢复替换。
    private suspend fun saveFile(session: String, path: String, file: File, mime: String) {
        if (publish != null) { publish.invoke(session, path, file); return }
        val target = resolver.productionPath(session, path)
        val existing = resolver.resolve(session, target)
        if (existing is MediaResolution.Available) {
            val expected = file.inputStream().use { hash(it) }
            require(digest(existing) == expected) { "链媒体目标已被其它内容占用。" }
            return
        }
        store.save(session, MediaPayload.Stream { file.inputStream() }, MediaOrigin.GENERATED, mime, file.name, target)
    }
    private suspend fun digest(available: MediaResolution.Available) = resolver.open(available).use { hash(it) }
    private fun hash(input: java.io.InputStream): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    override suspend fun tailFrame(sessionId: String, video: String, target: String): Unit = withContext(Dispatchers.IO) {
        val source = resolve(sessionId, video)
        MediaMetadataRetriever().use { r ->
            r.source(source)
            val count = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
            val bitmap = if (count != null && count > 0) r.getFrameAtIndex(count - 1) else {
                val seconds = requireNotNull(spec(sessionId, video).durationSeconds)
                r.getFrameAtTime((seconds * 1_000_000).toLong() - 1, MediaMetadataRetriever.OPTION_CLOSEST)
            }
            requireNotNull(bitmap) { "无法提取视频尾帧。" }
            try {
                val bytes = ByteArrayOutputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray() }
                val file = File.createTempFile("chain-tail-", ".png", context.cacheDir)
                try { file.writeBytes(bytes); saveFile(sessionId, target, file, "image/png") } finally { file.delete() }
            } finally { bitmap.recycle() }
        }
    }
    override suspend fun concatenate(sessionId: String, paths: List<String>, target: String): ChainConcatenation = withContext(Dispatchers.IO) {
        require(paths.size in 1..8) { "拼接需要 1～8 个视频段。" }
        val directory = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "shot-chain-").toFile()
        try {
            val staged = paths.mapIndexed { index, path ->
                val available = resolve(sessionId, path)
                require(available.ref.kind == MediaKind.VIDEO) { "链段必须是视频。" }
                File(directory, "seg-$index.mp4").also { file -> resolver.open(available).use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
                } } }
            }
            val trimUs = staged.mapIndexed { index, file -> if (index == 0) 0L else secondFrameTimeUs(file) }
            val specs = staged.map { file -> readVideoSpec(MediaResolution.Available(MediaRef(file.name, "video/mp4", MediaKind.VIDEO, file.length()), MediaSource.LocalFile(file))) }
            val timeline = chainTimeline(paths, specs, trimUs.map { it / 1_000_000.0 })
            val existing = resolver.resolve(sessionId, resolver.productionPath(sessionId, target))
            if (existing is MediaResolution.Available) {
                val actual = spec(sessionId, target)
                require(actual.durationSeconds?.let { abs(it - timeline.last().endSeconds) <= 0.2 } == true) { "已保存的拼接产物时长不符。" }
                return@withContext ChainConcatenation(actual, timeline)
            }
            val first = specs.first()
            val edits = staged.mapIndexed { index, file ->
                val clipping = MediaItem.ClippingConfiguration.Builder()
                if (index > 0) clipping.setStartPositionUs(trimUs[index])
                val item = MediaItem.Builder().setUri(Uri.fromFile(file)).setClippingConfiguration(clipping.build()).build()
                val effects: List<androidx.media3.common.Effect> = if (first.width != null && first.height != null) listOf(Presentation.createForWidthAndHeight(first.width, first.height, Presentation.LAYOUT_SCALE_TO_FIT)) else emptyList()
                EditedMediaItem.Builder(item).setEffects(Effects(emptyList(), effects)).build()
            }
            val composition = Composition.Builder(listOf(EditedMediaItemSequence.withAudioAndVideoFrom(edits))).setTransmuxVideo(false).setTransmuxAudio(false).build()
            val output = File(directory, "joined.mp4")
            transform(composition, output)
            saveFile(sessionId, target, output, "video/mp4")
            val actual = spec(sessionId, target)
            require(actual.durationSeconds?.let { abs(it - timeline.last().endSeconds) <= 0.2 } == true) { "拼接产物时长不符。" }
            ChainConcatenation(actual, timeline)
        } finally { withContext(NonCancellable + Dispatchers.IO) { directory.deleteRecursively() } }
    }
    private fun secondFrameTimeUs(file: File): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: throw IllegalArgumentException("链段缺少视频轨。")
            extractor.selectTrack(track)
            // 压缩样本按解码顺序排列；取最早两个呈现时间才能精确裁一帧。
            return firstFrameEndUs(sequence {
                while (extractor.sampleTime >= 0) {
                    yield(extractor.sampleTime)
                    if (!extractor.advance()) break
                }
            })
        } finally { extractor.release() }
    }
    private suspend fun transform(composition: Composition, file: File) = withContext(Dispatchers.Main.immediate) {
        val transformer = Transformer.Builder(context).setLooper(Looper.getMainLooper()).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC).build()
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (continuation.isActive) continuation.resume(Unit) }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalArgumentException("链段拼接编码失败。", exportException))
                    }
                })
                try { transformer.start(composition, file.path) } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
            }
        } finally { withContext(NonCancellable + Dispatchers.Main.immediate) { transformer.cancel() } }
    }
}
