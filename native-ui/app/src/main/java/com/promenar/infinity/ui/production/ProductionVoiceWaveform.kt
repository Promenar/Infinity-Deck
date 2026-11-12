package com.promenar.infinity.ui.production

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.media.MediaResolution
import com.promenar.infinity.domain.media.MediaSource
import com.promenar.infinity.ui.media.LocalWorkspaceMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.abs

/** 波形按真实解码振幅绘制；最长采样两分钟，失败时只显示不可用状态。 */
@Composable
internal fun ProductionVoiceWaveform(path: String) {
    val environment = LocalWorkspaceMedia.current
    val context = LocalContext.current
    var peaks by remember(environment, path) { mutableStateOf<List<Float>?>(null) }
    var loaded by remember(environment, path) { mutableStateOf(false) }
    LaunchedEffect(environment, path) {
        if (environment != null) {
            try {
                peaks = withContext(Dispatchers.IO) {
                    val mapped = environment.resolver.productionPath(environment.sessionId, path)
                    val available = environment.resolver.resolve(environment.sessionId, mapped) as? MediaResolution.Available
                    available?.let { decodeVoiceWaveform(context, it.source) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { peaks = null }
        }
        loaded = true
    }
    val label = stringResource(R.string.p2_voice_waveform)
    val color = MaterialTheme.colorScheme.primary
    val values = peaks
    if (!values.isNullOrEmpty()) Canvas(Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = label }) {
        val step = size.width / values.size
        values.forEachIndexed { index, peak ->
            val height = (peak.coerceIn(0f, 1f) * size.height).coerceAtLeast(2f)
            val x = (index + 0.5f) * step
            drawLine(color, Offset(x, (size.height - height) / 2), Offset(x, (size.height + height) / 2), strokeWidth = (step * 0.55f).coerceAtLeast(1f))
        }
    } else if (loaded) Text(stringResource(R.string.p2_waveform_unavailable), style = MaterialTheme.typography.labelSmall)
}

private suspend fun decodeVoiceWaveform(context: android.content.Context, source: MediaSource): List<Float>? {
    val extractor = MediaExtractor()
    var decoder: MediaCodec? = null
    try {
        when (source) {
            is MediaSource.LocalFile -> extractor.setDataSource(source.file.absolutePath)
            is MediaSource.ContentUri -> extractor.setDataSource(context, Uri.parse(source.uri), null)
        }
        val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val duration = (if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 120_000_000L).coerceIn(1L, 120_000_000L)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME) ?: return null)
        decoder = codec
        codec.configure(format, null, null, 0)
        codec.start()
        val values = VoiceWaveformAccumulator(duration)
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var floatPcm = false
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!outputEnded && System.nanoTime() < deadline) {
            currentCoroutineContext().ensureActive()
            if (!inputEnded) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index) ?: return null
                    val count = extractor.readSampleData(buffer, 0)
                    val time = extractor.sampleTime
                    inputEnded = count < 0 || time >= duration
                    codec.queueInputBuffer(index, 0, if (inputEnded) 0 else count, if (inputEnded) 0 else time, if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    if (!inputEnded) extractor.advance()
                }
            }
            val output = codec.dequeueOutputBuffer(info, 10_000)
            if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val outFormat = codec.outputFormat
                floatPcm = outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) && outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                rate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else if (output >= 0) {
                try {
                    codec.getOutputBuffer(output)?.let { buffer ->
                        buffer.order(ByteOrder.LITTLE_ENDIAN)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        var sample = 0
                        while (buffer.remaining() >= if (floatPcm) 4 else 2) {
                            val amplitude = if (floatPcm) abs(buffer.float) else pcm16Amplitude(buffer.short)
                            val time = info.presentationTimeUs + sample.toLong() * 1_000_000 / (rate.toLong() * channels.coerceAtLeast(1))
                            values.record(amplitude, time)
                            sample++
                        }
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                } finally { codec.releaseOutputBuffer(output, false) }
            }
        }
        return if (outputEnded) values.snapshot() else null
    } finally {
        decoder?.let { runCatching { it.stop() }; it.release() }
        extractor.release()
    }
}
