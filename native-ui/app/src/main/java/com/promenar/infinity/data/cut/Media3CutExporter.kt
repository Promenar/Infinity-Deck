package com.promenar.infinity.data.cut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.media.MediaCodecList
import android.net.Uri
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import com.promenar.infinity.data.production.*
import com.promenar.infinity.domain.media.*
import java.io.File
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 元数据读取与输入暂存均经工作区解析器核对当前会话的媒体访问范围。 */
class WorkspaceCutMediaProbe(private val context: Context, private val resolver: WorkspaceMediaResolver) : CutMediaProbe {
    override suspend fun read(sessionId: String, path: String): CutMediaMetadata = withContext(Dispatchers.IO) {
        ProductionManifestCodec.validatePath(path)
        val available = resolver.resolve(sessionId, resolver.productionPath(sessionId, path)) as? MediaResolution.Available
            ?: throw IllegalArgumentException("媒体不可读")
        require(available.ref.kind in setOf(MediaKind.VIDEO, MediaKind.AUDIO)) { "媒体类型无效" }
        MediaMetadataRetriever().use { retriever ->
            when (val source = available.source) {
                is MediaSource.LocalFile -> retriever.setDataSource(source.file.path)
                is MediaSource.ContentUri -> retriever.setDataSource(context, Uri.parse(source.uri))
            }
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            CutMediaMetadata(
                durationSeconds = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000),
                width = if (rotation % 180 == 0) width else height,
                height = if (rotation % 180 == 0) height else width,
                hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
            )
        }
    }
}

/** 主序列显式包含音频轨，Media3 为无音轨视频和图片补静音。 */
@androidx.annotation.OptIn(UnstableApi::class)
class Media3CutExporter(private val context: Context, private val resolver: WorkspaceMediaResolver) : CutExporter {
    override suspend fun export(sessionId: String, plan: CutCompositionPlan, progress: (Float?) -> Unit): CutExportArtifact = withContext(Dispatchers.IO) {
        require(plan.segments.isNotEmpty()) { "剪辑为空" }
        if (!MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
            codec.isEncoder && codec.supportedTypes.any { it.equals(MimeTypes.VIDEO_H264, ignoreCase = true) } &&
                codec.getCapabilitiesForType(MimeTypes.VIDEO_H264).videoCapabilities?.areSizeAndRateSupported(plan.width, plan.height, 30.0) == true
        }) throw CutExportFailure(CutFailureReason.UNSUPPORTED_SIZE)
        val directory = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "cut-export-").toFile()
        val output = File(directory, "output.mp4")
        try {
            val sources = (plan.segments.mapNotNull { it.path } + plan.audio.map { it.path }).distinct().mapIndexed { index, path ->
                currentCoroutineContext().ensureActive()
                val resolved = resolver.resolve(sessionId, resolver.productionPath(sessionId, path)) as? MediaResolution.Available
                    ?: throw CutExportFailure(CutFailureReason.MEDIA_UNAVAILABLE)
                // 保留扩展名以兼容需要文件名提示的提取器；所有字节仍来自核验后的引用。
                val file = File(directory, "source-$index.${path.substringAfterLast('.', "bin").take(12)}")
                resolver.open(resolved).use { input -> file.outputStream().use { target ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        target.write(buffer, 0, count)
                    }
                } }
                path to file
            }.toMap()
            // 每段在暂存后重新核对，防止路径内容在探测和复制之间变短导致内部时间线错位。
            val stagedDurations = sources.mapValues { (_, source) ->
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(source.path)
                    requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()) / 1000
                }
            }
            plan.segments.filter { it.path != null }.forEach { segment ->
                if (stagedDurations.getValue(requireNotNull(segment.path)) + 0.05 < segment.durationSeconds) throw CutExportFailure(CutFailureReason.SOURCE_CHANGED)
            }
            plan.audio.filter { it.kind != CutAudioKind.ORIGINAL }.forEach { audio ->
                if (stagedDurations.getValue(audio.path) + 0.002 < audio.sourceStartSeconds + audio.durationSeconds) throw CutExportFailure(CutFailureReason.SOURCE_CHANGED)
            }
            val cards = plan.segments.filter { it.path == null }.associate { segment ->
                val file = File(directory, "card-${segment.shotCode}.png")
                val bitmap = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.BLACK)
                    drawCutText(bitmap, "${segment.shotCode}\n${segment.title}", title = true)
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
                segment.shotCode to file
            }
            val composition = composition(plan, sources, cards)
            transform(composition, output, progress)
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(output.path)
                val seconds = requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()) / 1000
                if (abs(seconds - plan.durationSeconds) > 0.2 || retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "yes") throw CutExportFailure(CutFailureReason.INVALID_OUTPUT)
            }
            CutExportArtifact(output, directory)
        } catch (failure: Throwable) {
            directory.deleteRecursively()
            throw failure
        }
    }

    private fun composition(plan: CutCompositionPlan, sources: Map<String, File>, cards: Map<String, File>): Composition {
        val videos = plan.segments.map { segment ->
            val item = if (segment.path == null) MediaItem.Builder().setUri(Uri.fromFile(requireNotNull(cards[segment.shotCode])))
                .setImageDurationMs((segment.durationSeconds * 1000).roundToLong().coerceAtLeast(1)).build()
            else clipped(requireNotNull(sources[segment.path]), 0.0, segment.durationSeconds)
            val effects = mutableListOf<androidx.media3.common.Effect>(
                Presentation.createForWidthAndHeight(plan.width, plan.height, Presentation.LAYOUT_SCALE_TO_FIT),
            )
            val original = plan.audio.firstOrNull { it.kind == CutAudioKind.ORIGINAL && it.path == segment.path && abs(it.startSeconds - segment.startSeconds) < 0.000001 }
            EditedMediaItem.Builder(item).setFrameRate(30)
                .setEffects(Effects(original?.let(::gain).orEmpty(), effects)).build()
        }
        val sequences = mutableListOf(EditedMediaItemSequence.withAudioAndVideoFrom(videos))
        plan.audio.filter { it.kind != CutAudioKind.ORIGINAL && it.durationSeconds > 0 }.groupBy { it.kind }.values.forEach { track ->
            val sequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
            var cursorUs = 0L
            track.sortedBy { it.startSeconds }.forEach { audio ->
                val startUs = micros(audio.startSeconds)
                require(startUs + 1 >= cursorUs) { "附属音频时间线重叠" }
                if (startUs > cursorUs) sequence.addGap(startUs - cursorUs)
                sequence.addItem(EditedMediaItem.Builder(clipped(requireNotNull(sources[audio.path]), audio.sourceStartSeconds, audio.durationSeconds))
                    .setRemoveVideo(true).setEffects(Effects(gain(audio), emptyList())).build())
                cursorUs = startUs + micros(audio.durationSeconds)
            }
            val remainingUs = micros(plan.durationSeconds) - cursorUs
            if (remainingUs > 0) sequence.addGap(remainingUs)
            sequences += sequence.build()
        }
        return Composition.Builder(sequences)
            .setEffects(Effects(emptyList(), if (plan.burnSubtitles && plan.subtitles.isNotEmpty())
                listOf(OverlayEffect(listOf(CutTextOverlay(plan.width, plan.height, plan.subtitles)))) else emptyList()))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .setTransmuxAudio(false).setTransmuxVideo(false).build()
    }

    private fun clipped(file: File, start: Double, duration: Double) = MediaItem.Builder().setUri(Uri.fromFile(file))
        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
            .setStartPositionUs(micros(start)).setEndPositionUs(micros(start + duration)).build()).build()

    private fun gain(audio: CutAudioSegment): List<AudioProcessor> {
        if (audio.volumeDb == 0.0 && audio.fadeOutSeconds == 0.0) return emptyList()
        val multiplier = 10.0.pow(audio.volumeDb / 20).toFloat()
        return listOf(GainProcessor(object : GainProcessor.GainProvider {
            override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleRate: Int): Float {
                val seconds = samplePosition.toDouble() / sampleRate - audio.sourceStartSeconds
                val fade = if (audio.fadeOutSeconds > 0) ((audio.durationSeconds - seconds) / audio.fadeOutSeconds).coerceIn(0.0, 1.0) else 1.0
                return (multiplier * fade).toFloat()
            }
            override fun isUnityUntil(samplePosition: Long, sampleRate: Int): Long = C.TIME_UNSET
        }))
    }

    private suspend fun transform(composition: Composition, output: File, progress: (Float?) -> Unit) = withContext(Dispatchers.Main.immediate) {
        val transformer = Transformer.Builder(context).setLooper(Looper.getMainLooper())
            .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(DefaultEncoderFactory.Builder(context).setEnableFallback(false).build()).build()
        val poll = launch {
            val holder = ProgressHolder()
            while (isActive) {
                val status = transformer.getProgress(holder)
                progress(if (status == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress / 100f else null)
                delay(250)
            }
        }
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (continuation.isActive) continuation.resumeWithException(CutExportFailure(CutFailureReason.ENCODING_FAILED, exportException))
                    }
                })
                try { transformer.start(composition, output.path) }
                catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
            }
        } finally {
            withContext(NonCancellable) {
                poll.cancelAndJoin()
                // 包括取消在内，均在 Transformer 所属 Looper 上释放，之后 IO 层才能删除输入。
                transformer.cancel()
            }
        }
    }

    private fun micros(seconds: Double) = (seconds * 1_000_000).roundToLong()
}

/** 使用画布排版长字幕；只在字幕切换时重画，避免逐帧分配位图。 */
@androidx.annotation.OptIn(UnstableApi::class)
private class CutTextOverlay(
    private val width: Int,
    private val height: Int,
    private val cues: List<CutSubtitleCue>,
) : BitmapOverlay() {
    private var image: Bitmap? = null
    private var lastText: String? = null
    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val bitmap = image ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { image = it }
        val time = presentationTimeUs / 1000
        val text = cues.firstOrNull { time >= it.startMillis && time < it.endMillis }?.text.orEmpty()
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        if (text != lastText) {
            lastText = text
            bitmap.eraseColor(Color.TRANSPARENT)
            if (text.isNotEmpty()) drawCutText(bitmap, text, title = false)
        }
        return bitmap
    }
    override fun release() {
        super.release()
        image?.recycle()
        image = null
    }
}

private fun drawCutText(bitmap: Bitmap, text: String, title: Boolean) {
    val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = bitmap.height * if (title) 0.06f else 0.045f
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, (bitmap.width * 0.88).toInt().coerceAtLeast(1))
        .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
    val canvas = Canvas(bitmap)
    canvas.translate((bitmap.width - layout.width) / 2f, if (title) (bitmap.height - layout.height) / 2f else bitmap.height * 0.92f - layout.height)
    layout.draw(canvas)
}
