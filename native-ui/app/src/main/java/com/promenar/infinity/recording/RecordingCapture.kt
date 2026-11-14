package com.promenar.infinity.recording

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.PowerManager
import android.os.Process
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
    withDecoder: WithRecordingDecoder,
    onCaptureStarted: suspend () -> Unit,
    waveform: RecordingWaveformSampler,
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
            RandomAccessFile(store.audio(session.id), "rw").use { file ->
                wakeLock.acquire()
                recordPcmAudio(source, file, session.audioBytes, RecordingStore.MAX_AUDIO_BYTES,
                    command = command,
                    withDecoder = withDecoder,
                    onCaptureStarted = onCaptureStarted,
                    waveform = waveform,
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
            if (wakeLock.isHeld) wakeLock.release()
        }
    } finally {
        recorder.release()
    }
}
