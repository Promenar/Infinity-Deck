package com.promenar.infinity.recording

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

internal class RecordingStore(private val context: Context) {
    private val root = File(context.filesDir, "recording-sessions")
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("recording_defaults", Context.MODE_PRIVATE)

    var mode: RecordingMode
        get() = RecordingMode.entries.firstOrNull { it.name == prefs.getString("mode", null) } ?: RecordingMode.Auto
        set(value) { prefs.edit().putString("mode", value.name).apply() }

    var fallback: Boolean
        get() = prefs.getBoolean("fallback", true)
        set(value) { prefs.edit().putBoolean("fallback", value).apply() }

    fun newSession(title: String) = RecordingSession(
        id = UUID.randomUUID().toString(),
        createdAt = System.currentTimeMillis(),
        title = title,
        mode = mode,
        fallback = fallback,
    )

    private fun metadata(id: String) = AtomicFile(File(File(root, id), "transcript.json"))

    suspend fun write(session: RecordingSession) = withContext(Dispatchers.IO) {
        require(validSessionId(session.id))
        val file = metadata(session.id)
        val stream = file.startWrite()
        try {
            stream.write(json.encodeToString(session).toByteArray())
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
    }

    suspend fun restore(): List<RecordingSession> = withContext(Dispatchers.IO) {
        root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            val session = try {
                json.decodeFromString<RecordingSession>(metadata(dir.name).readFully().decodeToString())
                    .takeIf { it.id == dir.name && validSessionId(it.id) }
            } catch (_: Exception) {
                null
            } ?: return@mapNotNull null
            if (session.phase != RecordingPhase.Recording && session.phase != RecordingPhase.Paused) {
                session
            } else {
                val recovered = runCatching { recover(session) }
                    .getOrDefault(session.copy(phase = RecordingPhase.Interrupted))
                try {
                    write(recovered)
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                }
                recovered
            }
        }.sortedByDescending { it.createdAt }
    }

    private fun validSessionId(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    fun recover(session: RecordingSession): RecordingSession {
        val wav = audio(session.id)
        val bytes = if (wav.exists()) {
            RandomAccessFile(wav, "rw").use { file ->
                val count = (file.length() - WAV_HEADER_BYTES).coerceAtLeast(0) and -2L
                updateWavHeader(file, count)
                count
            }
        } else 0
        return recoverRecordingTranscript(session, bytes)
    }

    fun audio(id: String) = File(File(root, id), "audio.wav")

    companion object {
        const val WAV_HEADER_BYTES = 44L
        const val SAMPLE_RATE = 16000
        const val MAX_AUDIO_BYTES = 2_000_000_000L

        fun updateWavHeader(file: RandomAccessFile, size: Long) {
            val header = ByteBuffer.allocate(WAV_HEADER_BYTES.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt((size + 36).toInt()).put("WAVEfmt ".toByteArray())
            header.putInt(16).putShort(1).putShort(1).putInt(SAMPLE_RATE)
            header.putInt(SAMPLE_RATE * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(size.toInt())
            file.seek(0)
            file.write(header.array())
            file.seek(WAV_HEADER_BYTES + size)
        }
    }
}
