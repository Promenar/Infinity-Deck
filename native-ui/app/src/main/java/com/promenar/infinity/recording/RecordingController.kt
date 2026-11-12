package com.promenar.infinity.recording

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.backup.BackupStartupState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

internal data class RecordingScreenState(
    val sessions: List<RecordingSession> = emptyList(),
    val activeId: String? = null,
    val opening: Boolean = false,
    val partial: String = "",
    val durationMs: Long = 0,
    val levels: List<Float> = emptyList(),
    val organizingId: String? = null,
    val failure: Int? = null,
    val ready: Boolean = false,
    val cloudKey: String? = null,
    val cloudLabel: String? = null,
    val localLabel: String? = null,
) {
    val localReady get() = localLabel != null
}

internal class RecordingController(private val app: InfinityApplication) {
    val store = RecordingStore(app)
    private val model = RecordingModel(app)
    private val library = RecordingLibrary(app)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val publishing = Mutex()
    private val microphone = Mutex()
    private val command = AtomicReference(CaptureCommand.Continue)
    private val serviceCommands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(RecordingScreenState())
    val state = _state.asStateFlow()
    private var capture: Job? = null
    private var organize: Job? = null
    private var quiet = false
    private var cloudJobRunning = false
    private var organizingCloud = false
    private var nextAttempt = 0L

    init {
        io.launch {
            for (action in serviceCommands) action()
        }
        io.launch {
            val restored = store.restore()
            lock.withLock { _state.value = RecordingScreenState(sessions = restored, ready = true) }
            restored.filter { it.phase == RecordingPhase.Finished && it.documentHash == null }.forEach { publish(it.id) }
            while (true) {
                try {
                    lock.withLock { scheduleOrganization() }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    report(R.string.recording_organize_failed)
                }
                delay(3_000)
            }
        }
    }

    // 与宠物吹气检测共用麦克风；录音优先，等待宠物释放后再打开 AudioRecord。
    suspend fun listenForPet(listen: suspend () -> Unit) = microphone.withLock {
        if (_state.value.activeId == null) listen()
    }

    fun capture(id: String?, onIdle: () -> Unit) {
        serviceCommands.trySend {
            state.first { it.ready }
            lock.withLock {
                if (capture?.isActive == true) return@withLock
                val previous = _state.value.activeId?.let(::session)
                if (previous != null && (previous.id != id || previous.phase != RecordingPhase.Paused)) return@withLock
                val resume = id?.let(::session)
                if (id != null && resume?.phase !in setOf(RecordingPhase.Paused, RecordingPhase.Interrupted)) {
                    if (_state.value.activeId == null) onIdle()
                    return@withLock
                }
                val item = (resume ?: store.newSession(
                    app.getString(R.string.recording_default_title,
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())),
                )).copy(phase = RecordingPhase.Recording)
                try {
                    save(item)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    report(R.string.recording_storage_failed)
                    if (_state.value.activeId == null) onIdle()
                    return@withLock
                }
                quiet = false
                command.set(CaptureCommand.Continue)
                organize?.cancel()
                _state.value = _state.value.copy(activeId = item.id, opening = true, partial = "", durationMs = item.durationMs, levels = emptyList(), failure = null)
                capture = io.launch { record(item, onIdle) }
            }
        }
    }

    private suspend fun record(item: RecordingSession, onIdle: () -> Unit) {
        var outcome: RecordingPhase
        var lastLevel = item.audioBytes
        try {
            val result = microphone.withLock {
                captureRecording(app, store, item, command::get) { frame ->
                    lock.withLock {
                        val current = requireNotNull(session(item.id))
                        if (frame.checkpoint) {
                            val segments = frame.segment?.let { current.segments + it } ?: current.segments
                            save(current.copy(audioBytes = frame.bytes, segments = segments, checkpointText = frame.provisional))
                        }
                        if (quiet && !frame.quiet) organize?.cancel()
                        quiet = frame.quiet
                        val previous = _state.value
                        val levels = if (frame.bytes - lastLevel >= 3_200) {
                            lastLevel = frame.bytes
                            previous.levels.takeLast(47) + frame.level
                        } else previous.levels
                        _state.value = previous.copy(
                            opening = false, partial = frame.partial, durationMs = frame.bytes / 32 / 1000 * 1000, levels = levels,
                        )
                    }
                }
            }
            outcome = if (result == CaptureCommand.Pause) RecordingPhase.Paused else RecordingPhase.Finished
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            outcome = RecordingPhase.Interrupted
        } catch (_: LinkageError) {
            outcome = RecordingPhase.Interrupted
        }
        lock.withLock {
            if (command.get() == CaptureCommand.Finish && outcome == RecordingPhase.Paused) outcome = RecordingPhase.Finished
            val current = requireNotNull(session(item.id))
            val final = if (outcome == RecordingPhase.Interrupted) {
                runCatching { store.recover(current) }.getOrDefault(current.copy(phase = outcome))
            } else current.copy(phase = outcome)
            try {
                save(final)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                replace(final)
                report(R.string.recording_storage_failed)
            }
            if (outcome == RecordingPhase.Interrupted) report(R.string.recording_capture_failed)
            capture = null
            quiet = true
            _state.value = _state.value.copy(
                activeId = item.id.takeIf { outcome == RecordingPhase.Paused }, opening = false, partial = "", durationMs = final.durationMs,
            )
            if (outcome == RecordingPhase.Finished) publish(item.id)
            if (outcome != RecordingPhase.Paused) onIdle()
        }
    }

    fun pause() { command.compareAndSet(CaptureCommand.Continue, CaptureCommand.Pause) }

    fun finish(onIdle: () -> Unit) {
        serviceCommands.trySend {
            lock.withLock {
                command.set(CaptureCommand.Finish)
                if (capture?.isActive == true) return@withLock
                val current = _state.value.activeId?.let(::session)
                if (current != null) {
                    try {
                        save(current.copy(phase = RecordingPhase.Finished))
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        report(R.string.recording_storage_failed)
                        return@withLock
                    }
                    _state.value = _state.value.copy(activeId = null)
                    publish(current.id)
                }
                onIdle()
            }
        }
    }

    fun setMode(id: String?, mode: RecordingMode) {
        if (id == null) store.mode = mode
        else edit(id) { it?.copy(mode = mode, cloudConsent = if (mode == RecordingMode.Auto) it.cloudConsent else null) }
    }

    fun allowCloud(id: String, key: String?) = edit(id) { it?.copy(cloudConsent = key) }

    fun updateDraft(id: String, title: String, glossary: String, editedText: String?) = edit(id) {
        it?.copy(title = title.trim().take(100).ifBlank { it.title }, glossary = glossary.take(200),
            organized = editedText ?: it.organized, manualEdit = it.manualEdit || editedText != null,
            reprocessThrough = it.reprocessThrough.takeIf { editedText == null },
            reprocessText = it.reprocessText.takeIf { editedText == null }.orEmpty())
    }

    fun retry(id: String) = edit(id) {
        it?.copy(manualEdit = false, reprocessThrough = 0, reprocessText = "")
    }

    private fun edit(id: String?, transform: (RecordingSession?) -> RecordingSession?) {
        io.launch {
            lock.withLock {
                if (id == _state.value.organizingId) organize?.cancel()
                nextAttempt = 0
                try {
                    transform(id?.let(::session))?.let { save(it) }
                    _state.value = _state.value.copy(failure = null)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    report(R.string.recording_storage_failed)
                }
            }
        }
    }

    fun publish(id: String) {
        io.launch {
            publishing.withLock {
                val item = lock.withLock { session(id) } ?: return@withLock
                if (item.phase !in setOf(RecordingPhase.Interrupted, RecordingPhase.Finished)) return@withLock
                try {
                    val document = library.publish(item)
                    lock.withLock {
                        val current = session(id) ?: return@withLock
                        save(current.copy(documentId = document.path, documentHash = document.hash))
                        if (_state.value.failure == R.string.recording_publish_failed) {
                            _state.value = _state.value.copy(failure = null)
                        }
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    report(R.string.recording_publish_failed)
                }
            }
        }
    }

    fun exportRaw(id: String, destination: Uri) {
        io.launch {
            val item = lock.withLock { session(id) } ?: return@launch
            try {
                val output = requireNotNull(app.contentResolver.openOutputStream(destination, "wt"))
                output.bufferedWriter(Charsets.UTF_8).use { it.write(recordingRawText(item)) }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                report(R.string.recording_export_failed)
            }
        }
    }

    suspend fun retryCloudQueue(): Boolean {
        state.first { it.ready }
        if (withTimeoutOrNull(20_000) { app.startupState.first { it == BackupStartupState.Ready } } == null) return true
        lock.withLock { cloudJobRunning = true }
        try {
            val complete = withTimeoutOrNull(135_000) {
                while (lock.withLock { hasCloudQueue(model.cloud()?.key) }) {
                    delay(1_000)
                }
                true
            }
            return complete != true
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                lock.withLock {
                    cloudJobRunning = false
                    if (organizingCloud && !isForeground() && _state.value.activeId == null) organize?.cancel()
                }
            }
        }
    }

    private fun hasCloudQueue(key: String?): Boolean = key != null && _state.value.sessions.any {
        it.mode == RecordingMode.Auto && it.cloudConsent == key && !it.manualEdit &&
            it.organizationCursor < it.segments.size
    }

    private fun isForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentStateFlow.value.isAtLeast(Lifecycle.State.STARTED)

    private fun scheduleOrganization() {
        val cloud = model.cloud()
        val local = model.local()
        _state.value = _state.value.copy(cloudKey = cloud?.key, cloudLabel = cloud?.label, localLabel = local?.modelName)
        val active = _state.value.activeId
        val foreground = isForeground()
        if (organize?.isActive == true || SystemClock.elapsedRealtime() < nextAttempt) return
        if (active != null && !quiet) return
        if (active == null && !foreground && !cloudJobRunning) return
        val item = _state.value.sessions.firstOrNull {
            !it.manualEdit && it.organizationCursor < it.segments.size &&
                (active == null || it.id == active) &&
                chooseRecordingRoute(it.mode, cloud?.key, it.cloudConsent, local != null, it.fallback) in
                (if (active != null || foreground) setOf(RecordingRoute.Cloud, RecordingRoute.Local)
                 else setOf(RecordingRoute.Cloud))
        } ?: return
        val useCloud = item.mode == RecordingMode.Auto && cloud != null
        organizingCloud = useCloud
        val range = nextRecordingBatch(item.segments, item.organizationCursor, if (useCloud) 2000 else 300) ?: return
        _state.value = _state.value.copy(organizingId = item.id)
        organize = io.launch {
            try {
                val result = withTimeoutOrNull(120_000) {
                    model.organize(item, range, cloud.takeIf { useCloud }, local?.modelName)
                } ?: error("整理超时")
                lock.withLock {
                    val current = session(item.id) ?: return@withLock
                    if (current.mode != item.mode || current.cloudConsent != item.cloudConsent || current.manualEdit ||
                        current.organizationCursor != range.first || current.glossary != item.glossary
                    ) return@withLock
                    val cursor = range.last + 1
                    val staging = current.reprocessThrough != null
                    val done = cursor == current.segments.size
                    val prior = if (staging) current.reprocessText else current.organized
                    val text = listOf(prior, result).filter(String::isNotBlank).joinToString("\n\n")
                    save(if (staging && !done) current.copy(reprocessText = text, reprocessThrough = cursor)
                         else current.copy(organized = text, organizedThrough = cursor, reprocessThrough = null, reprocessText = ""))
                    if (current.phase == RecordingPhase.Finished && done) publish(current.id)
                    if (_state.value.failure == R.string.recording_organize_failed) {
                        _state.value = _state.value.copy(failure = null)
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                lock.withLock {
                    nextAttempt = SystemClock.elapsedRealtime() + 60_000
                    report(R.string.recording_organize_failed)
                }
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    lock.withLock {
                        organizingCloud = false
                        _state.value = _state.value.copy(organizingId = null)
                    }
                }
            }
        }
    }

    private suspend fun save(item: RecordingSession) {
        store.write(item)
        replace(item)
        if (item.mode == RecordingMode.Auto && item.cloudConsent != null &&
            !item.manualEdit && item.organizationCursor < item.segments.size) {
            runCatching { RecordingRetryService.ensureScheduled(app) }
        }
    }

    private fun replace(item: RecordingSession) {
        val items = _state.value.sessions
        val updated = if (items.any { it.id == item.id }) items.map { if (it.id == item.id) item else it }
        else listOf(item) + items
        _state.value = _state.value.copy(sessions = updated)
    }

    private fun session(id: String) = _state.value.sessions.firstOrNull { it.id == id }

    fun report(message: Int) {
        io.launch { lock.withLock { _state.value = _state.value.copy(failure = message) } }
    }
}

internal fun Context.recordingController() = (applicationContext as InfinityApplication).recordings
