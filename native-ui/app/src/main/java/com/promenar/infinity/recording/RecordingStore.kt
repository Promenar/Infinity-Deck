package com.promenar.infinity.recording

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import com.promenar.infinity.util.LocaleHelper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

private fun recordingRoot(context: Context) = File(context.filesDir, "recording-sessions")

/** 本机是否存有这些 id 中任一条录音；只读目录，不创建录音控制器。 */
internal fun hasRecordingSessions(context: Context, ids: Collection<String>): Boolean {
    val root = recordingRoot(context)
    return ids.any { id -> runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false) && File(root, id).isDirectory }
}

internal class RecordingStore(
    private val context: Context,
    private val beforeWrite: () -> Unit = {},
) {
    private val root = recordingRoot(context)
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("recording_defaults", Context.MODE_PRIVATE)

    var mode: RecordingMode
        get() = RecordingMode.entries.firstOrNull { it.name == prefs.getString("mode", null) } ?: RecordingMode.Auto
        set(value) { prefs.edit().putString("mode", value.name).apply() }

    var fallback: Boolean
        get() = prefs.getBoolean("fallback", true)
        set(value) { prefs.edit().putBoolean("fallback", value).apply() }

    var distinguishSpeakers: Boolean
        get() = prefs.getBoolean("distinguish_speakers", false)
        set(value) { prefs.edit().putBoolean("distinguish_speakers", value).apply() }

    var outputLanguage: RecordingLanguage
        get() = RecordingLanguage.entries.firstOrNull { it.name == prefs.getString("summary_language", null) }
            ?: RecordingLanguage.fromLocale(LocaleHelper.getSavedLanguage(context))
        set(value) { prefs.edit().putString("summary_language", value.name).apply() }

    var liveOrganization: Boolean
        get() = prefs.getBoolean("live_organization", true)
        set(value) { prefs.edit().putBoolean("live_organization", value).apply() }

    fun newSession(title: String) = RecordingSession(
        id = UUID.randomUUID().toString(),
        createdAt = System.currentTimeMillis(),
        title = title,
        mode = mode,
        fallback = fallback,
        distinguishSpeakers = distinguishSpeakers,
        outputLanguage = outputLanguage,
        liveOrganization = liveOrganization,
    )

    @Serializable
    private data class CaptureJournal(val original: RecordingSession?, val discarding: Boolean = false)

    private fun journal(id: String) = AtomicFile(File(File(root, id), "capture.json"))

    private fun readJournal(id: String): CaptureJournal? {
        val file = journal(id)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return json.decodeFromString<CaptureJournal>(file.readFully().decodeToString())
            .also { check(it.original == null || it.original.id == id) }
    }

    suspend fun beginCapture(original: RecordingSession?, active: RecordingSession) = withContext(Dispatchers.IO) {
        require(validSessionId(active.id) && (original == null || original.id == active.id))
        check(readJournal(active.id)?.discarding != true)
        writeAtomic(journal(active.id), json.encodeToString(CaptureJournal(original)))
        write(active)
    }

    suspend fun markDiscard(id: String) = withContext(Dispatchers.IO) {
        require(validSessionId(id))
        val previous = requireNotNull(readJournal(id))
        writeAtomic(journal(id), json.encodeToString(previous.copy(discarding = true)))
    }

    suspend fun pendingDiscardIds(): List<String> = withContext(Dispatchers.IO) {
        root.listFiles().orEmpty().asSequence().filter { it.isDirectory && validSessionId(it.name) }
            .map { it.name }.filter { runCatching { readJournal(it)?.discarding == true }.getOrDefault(false) }.toList()
    }

    suspend fun originalCapture(id: String): RecordingSession? = withContext(Dispatchers.IO) { readJournal(id)?.original }

    suspend fun clearCapture(id: String) = withContext(Dispatchers.IO) { journal(id).delete() }

    suspend fun rollbackCapture(id: String): RecordingSession? = withContext(Dispatchers.IO) {
        require(validSessionId(id))
        val saved = requireNotNull(readJournal(id)).also { check(it.discarding) }
        val original = saved.original
        if (original == null) {
            // 在目录删除完成前保留事务文件，重启时继续清理。
            val directory = audio(id).parentFile!!
            check(!audio(id).exists() || audio(id).delete())
            metadata(id).delete()
            clearCapture(id)
            directory.delete()
        } else {
            RandomAccessFile(audio(id), "rw").use { file ->
                if (original.audioBytes > 0) check(file.length() >= WAV_HEADER_BYTES + original.audioBytes)
                file.setLength(WAV_HEADER_BYTES + original.audioBytes)
                updateWavHeader(file, original.audioBytes)
                file.fd.sync()
            }
            write(original)
            clearCapture(id)
        }
        original
    }

    private fun metadata(id: String) = AtomicFile(File(File(root, id), "transcript.json"))

    suspend fun write(session: RecordingSession) = withContext(Dispatchers.IO) {
        require(validSessionId(session.id))
        beforeWrite()
        writeAtomic(metadata(session.id), json.encodeToString(session))
    }

    private fun writeAtomic(file: AtomicFile, value: String) {
        val stream = file.startWrite()
        try {
            stream.write(value.toByteArray())
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
    }

    suspend fun restore(): List<RecordingSession> = withContext(Dispatchers.IO) {
        root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            if (!validSessionId(dir.name)) return@mapNotNull null
            val pending = runCatching { readJournal(dir.name) }.getOrNull()
            if (pending?.discarding == true) {
                return@mapNotNull try {
                    rollbackCapture(dir.name)
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    pending.original?.copy(captureRollbackPending = true)
                }
            }
            var migration = false
            val session = try {
                val text = metadata(dir.name).readFully().decodeToString()
                migration = "glossary" in json.parseToJsonElement(text).jsonObject
                decodeRecordingSession(text)
                    .takeIf { it.id == dir.name && validSessionId(it.id) }
            } catch (_: Exception) {
                null
            } ?: return@mapNotNull null
            if (migration) {
                try { write(session) } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                }
            }
            if (pending != null) clearCapture(session.id)
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

    /** 删除一条录音的目录：音频与逐字稿、整理稿元数据一并移除。 */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        require(validSessionId(id))
        val directory = File(root, id)
        check(!directory.exists() || directory.deleteRecursively())
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

    fun playbackEnvelope(id: String, audioBytes: Long): List<Float> {
        val sampleCount = audioBytes / 2
        if (sampleCount == 0L) return emptyList()
        val bars = sampleCount.coerceAtMost(96L).toInt()
        // 每柱读取一小段 PCM，内存和读取量不随录音时长增长。
        val buffer = ByteArray(640)
        return RandomAccessFile(audio(id), "r").use { file ->
            List(bars) { index ->
                val start = sampleCount * index / bars
                val end = sampleCount * (index + 1) / bars
                val count = minOf(buffer.size / 2L, end - start).toInt()
                file.seek(WAV_HEADER_BYTES + (start + (end - start - count) / 2) * 2)
                file.readFully(buffer, 0, count * 2)
                var peak = 0
                repeat(count) { sample ->
                    val low = buffer[2 * sample].toInt() and 0xff
                    val high = buffer[2 * sample + 1].toInt() shl 8
                    peak = maxOf(peak, kotlin.math.abs(high or low))
                }
                (peak / 16_384f).coerceAtMost(1f)
            }
        }
    }

    companion object {
        const val WAV_HEADER_BYTES = 44L
        const val SAMPLE_RATE = RecordingCapturePolicy.SAMPLE_RATE
        const val MAX_AUDIO_BYTES = 2_000_000_000L

        fun updateWavHeader(file: RandomAccessFile, size: Long) = updateRecordingWavHeader(file, size)
    }
}
