package com.promenar.infinity.recording

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.PowerManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.RandomAccessFile
import kotlin.math.sqrt

internal enum class CaptureCommand { Continue, Pause, Finish }

internal data class CaptureFrame(
    val bytes: Long,
    val partial: String,
    val provisional: RecordingSegment?,
    val segment: RecordingSegment?,
    val quiet: Boolean,
    val checkpoint: Boolean,
    val level: Float,
)

@SuppressLint("MissingPermission", "WakelockTimeout")
internal suspend fun captureRecording(
    context: Context,
    store: RecordingStore,
    session: RecordingSession,
    command: () -> CaptureCommand,
    onFrame: suspend (CaptureFrame) -> Unit,
): CaptureCommand {
    val rate = RecordingStore.SAMPLE_RATE
    val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    check(minimum > 0)
    val recorder = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, rate / 2),
    )
    val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:recording")
    try {
        check(recorder.state == AudioRecord.STATE_INITIALIZED)
        val recognizer = OnlineRecognizer(context.assets, OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = rate, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "asr/encoder-epoch-99-avg-1.int8.onnx",
                    decoder = "asr/decoder-epoch-99-avg-1.onnx",
                    joiner = "asr/joiner-epoch-99-avg-1.int8.onnx",
                ),
                tokens = "asr/tokens.txt",
                numThreads = 2,
                modelType = "zipformer",
            ),
            enableEndpoint = true,
        ))
        try {
            val stream = recognizer.createStream()
            try {
                RandomAccessFile(store.audio(session.id), "rw").use { file ->
                    var written = session.audioBytes
                    file.setLength(RecordingStore.WAV_HEADER_BYTES + written)
                    RecordingStore.updateWavHeader(file, written)
                    var segmentStart = session.durationMs
                    var lastCheckpoint = written
                    var lastVoice = written
                    var speechAt: Long? = null
                    val pcm = ShortArray(640)
                    val encoded = ByteArray(pcm.size * 2)
                    val fullFrame = FloatArray(pcm.size)
                    wakeLock.acquire()
                    if (command() == CaptureCommand.Continue) {
                        recorder.startRecording()
                        check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    }
                    while (command() == CaptureCommand.Continue && written + encoded.size <= RecordingStore.MAX_AUDIO_BYTES) {
                        currentCoroutineContext().ensureActive()
                        val count = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                        check(count > 0)
                        var energy = 0.0
                        val waveform = if (count == pcm.size) fullFrame else FloatArray(count)
                        for (i in 0 until count) {
                            val sample = pcm[i].toInt()
                            encoded[2 * i] = sample.toByte()
                            encoded[2 * i + 1] = (sample shr 8).toByte()
                            waveform[i] = sample / 32768f
                            energy += waveform[i] * waveform[i]
                        }
                        file.write(encoded, 0, count * 2)
                        written += count * 2
                        val speech = energy / count >= 0.00005
                        if (speech) {
                            if (speechAt == null) speechAt = (written - count * 2) / 32
                            lastVoice = written
                        }
                        stream.acceptWaveform(waveform, rate)
                        while (recognizer.isReady(stream)) recognizer.decode(stream)
                        val text = recognizer.getResult(stream).text.trim()
                        val end = recognizer.isEndpoint(stream) || written / 32 - segmentStart >= 30_000
                        val segment = if (end && text.isNotEmpty()) {
                            RecordingSegment((speechAt ?: segmentStart).coerceAtLeast(segmentStart), written / 32, text)
                        } else null
                        if (end) {
                            recognizer.reset(stream)
                            segmentStart = written / 32
                            speechAt = null
                        }
                        val checkpoint = segment != null || written - lastCheckpoint >= rate * 2L * 5
                        val provisional = if (checkpoint && !end && text.isNotEmpty()) {
                            RecordingSegment((speechAt ?: segmentStart).coerceAtLeast(segmentStart), written / 32, text)
                        } else null
                        if (checkpoint) {
                            RecordingStore.updateWavHeader(file, written)
                            file.fd.sync()
                            lastCheckpoint = written
                        }
                        onFrame(CaptureFrame(
                            bytes = written, partial = if (end) "" else text, provisional = provisional, segment = segment,
                            quiet = written - lastVoice >= rate * 2L * 4, checkpoint = checkpoint,
                            level = (sqrt(energy / count).toFloat() * 8).coerceIn(0f, 1f),
                        ))
                    }
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                    stream.acceptWaveform(FloatArray(rate / 3), rate)
                    stream.inputFinished()
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    val tail = recognizer.getResult(stream).text.trim().takeIf(String::isNotEmpty)
                        ?.let { RecordingSegment((speechAt ?: segmentStart).coerceAtLeast(segmentStart), written / 32, it) }
                    RecordingStore.updateWavHeader(file, written)
                    file.fd.sync()
                    onFrame(CaptureFrame(written, "", null, tail, quiet = true, checkpoint = true, level = 0f))
                    return command().takeUnless { it == CaptureCommand.Continue } ?: CaptureCommand.Finish
                }
            } finally {
                stream.release()
            }
        } finally {
            recognizer.release()
        }
    } finally {
        if (wakeLock.isHeld) wakeLock.release()
        recorder.release()
    }
}
