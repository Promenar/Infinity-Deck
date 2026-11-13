package com.promenar.infinity.recording

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.PowerManager
import android.os.Process
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile

@SuppressLint("MissingPermission", "WakelockTimeout")
internal suspend fun captureRecording(
    context: Context,
    store: RecordingStore,
    session: RecordingSession,
    command: () -> CaptureCommand,
    onFrame: suspend (CaptureFrame) -> Unit,
): CaptureCommand = withContext(Dispatchers.IO) {
    val rate = RecordingStore.SAMPLE_RATE
    val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    check(minimum > 0)
    val recorder = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        maxOf(minimum * 2, rate * 2 * RecordingCapturePolicy.RECORDER_BUFFER_SECONDS),
    )
    try {
        check(recorder.state == AudioRecord.STATE_INITIALIZED)
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:recording")
        try {
            val recognizer = OnlineRecognizer(context.assets, OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = rate, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "asr/encoder.int8.onnx",
                        decoder = "asr/decoder.onnx",
                        joiner = "asr/joiner.int8.onnx",
                    ),
                    tokens = "asr/tokens.txt",
                    numThreads = 2,
                    modelType = "zipformer2",
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, RecordingCapturePolicy.ENDPOINT_EMPTY_SILENCE_SECONDS,
                        RecordingCapturePolicy.ENDPOINT_NO_MIN_UTTERANCE_SECONDS),
                    rule2 = EndpointRule(true, RecordingCapturePolicy.ENDPOINT_TEXT_SILENCE_SECONDS,
                        RecordingCapturePolicy.ENDPOINT_NO_MIN_UTTERANCE_SECONDS),
                    rule3 = EndpointRule(false, RecordingCapturePolicy.ENDPOINT_LONG_SILENCE_SECONDS,
                        RecordingCapturePolicy.ENDPOINT_LONG_UTTERANCE_SECONDS),
                ),
                enableEndpoint = true,
            ))
            try {
                val stream = recognizer.createStream()
                try {
                    val source = object : RecordingAudioSource {
                        override fun start() {
                            recorder.startRecording()
                            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                        }
                        override fun read(buffer: ShortArray): Int =
                            recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        override fun stop() {
                            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                        }
                    }
                    val decoder = object : RecordingDecoder {
                        private var text = ""
                        override fun accept(pcm: ShortArray): RecordingRecognition {
                            stream.acceptWaveform(FloatArray(pcm.size) { pcm[it] / 32768f }, rate)
                            var decoded = false
                            while (recognizer.isReady(stream)) {
                                recognizer.decode(stream)
                                decoded = true
                            }
                            if (decoded) text = recognizer.getResult(stream).text
                            return RecordingRecognition(text, decoded && recognizer.isEndpoint(stream))
                        }
                        override fun reset() {
                            recognizer.reset(stream)
                            text = ""
                        }
                        override fun finish(): String {
                            stream.acceptWaveform(FloatArray(rate * RecordingCapturePolicy.TAIL_SILENCE_SECONDS), rate)
                            stream.inputFinished()
                            while (recognizer.isReady(stream)) recognizer.decode(stream)
                            return recognizer.getResult(stream).text
                        }
                    }
                    RandomAccessFile(store.audio(session.id), "rw").use { file ->
                        wakeLock.acquire()
                        recordPcmAudio(source, decoder, file, session.audioBytes, RecordingStore.MAX_AUDIO_BYTES,
                            command = command,
                            // 提升优先级失败不影响录音。
                            onReaderStart = { runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } },
                            onBacklog = { backlog ->
                                InfinityLogger.diagnostic("recording.capture_backlog", mapOf(
                                    "max_backlog_ms" to backlog.maxQueuedMs,
                                    "duration_ms" to backlog.durationMs,
                                ))
                            },
                            onFrame = onFrame,
                        )
                    }
                } finally {
                    stream.release()
                }
            } finally {
                recognizer.release()
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    } finally {
        recorder.release()
    }
}
