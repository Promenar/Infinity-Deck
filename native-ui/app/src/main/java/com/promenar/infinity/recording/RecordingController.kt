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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

internal data class RecordingScreenState(
    val sessions: List<RecordingSession> = emptyList(),
    val activeId: String? = null,
    val opening: Boolean = false,
    val audioOnly: Boolean = false,
    val partial: String = "",
    val durationMs: Long = 0,
    val levels: List<Float> = emptyList(),
    val organizingId: String? = null,
    val recognizingId: String? = null,
    val recognizeProgress: Float = 0f,
    val failure: Int? = null,
    val ready: Boolean = false,
    val cloudKey: String? = null,
    val cloudLabel: String? = null,
    val localLabel: String? = null,
) {
    val localReady get() = localLabel != null
}

internal typealias RecordingAudioCapture = suspend (Context, RecordingStore, RecordingSession, () -> CaptureCommand, RecordingDecoder, suspend (CaptureFrame) -> Unit) -> CaptureCommand

internal class RecordingController(
    private val app: InfinityApplication,
    private val io: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val decoderFactory: RecordingDecoderFactory = RecordingDecoderFactory(::createRecordingDecoder),
    private val recordAudio: RecordingAudioCapture = ::captureRecording,
    private val organizationEnabled: Boolean = true,
    private val publishDocument: suspend (RecordingSession) -> RecordingDocument = { RecordingLibrary(app).publish(it) },
    private val recognizeWithWakeLock: suspend (suspend () -> List<RecordingSegment>) -> List<RecordingSegment> = { withRecordingRecognitionWakeLock(app, it) },
) {
    val store = RecordingStore(app)
    private val model = RecordingModel(app)
    private val asr = RecordingAsrModelManager(app, io)
    val asrModel = asr.state
    val asrModelInstalledBytes = asr.spec.installedBytes
    val asrModelDownloadBytes = asr.spec.bytes
    private val hotwordStore = RecordingHotwordStore(java.io.File(app.filesDir, RecordingHotwordStore.FILE_NAME))
    private val _hotwords = MutableStateFlow<List<RecordingHotword>>(emptyList())
    val hotwords = _hotwords.asStateFlow()
    private val recognizerUse = Mutex()
    @Volatile private var recognition: Job? = null
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
            val hotwordFailure = try {
                _hotwords.value = hotwordStore.read()
                null
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                R.string.recording_hotword_storage
            }
            lock.withLock { _state.value = RecordingScreenState(sessions = restored, ready = true, failure = hotwordFailure) }
            restored.filter { it.phase == RecordingPhase.Finished && it.documentHash == null }.forEach { publish(it.id) }
            while (true) {
                try {
                    lock.withLock { if (organizationEnabled) scheduleOrganization() }
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
                recognition?.cancel()
                quiet = false
                command.set(CaptureCommand.Continue)
                organize?.cancel()
                _state.value = _state.value.copy(activeId = item.id, opening = true, audioOnly = false, partial = "", durationMs = item.durationMs, levels = emptyList(), failure = null)
                capture = io.launch { record(item, onIdle) }
            }
        }
    }

    private suspend fun record(item: RecordingSession, onIdle: () -> Unit) {
        var outcome: RecordingPhase
        var lastLevel = item.audioBytes
        try {
            val result = recognizerUse.withLock {
                val request = lock.withLock {
                    decoderRequest(item).also { _state.value = _state.value.copy(audioOnly = it.directory == null) }
                }
                decoderFactory.create(request).use { decoder ->
                    microphone.withLock {
                        recordAudio(app, store, item, command::get, decoder) { frame ->
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

    fun updateDraft(id: String, title: String, hotwords: List<String>, useHotwordLibrary: Boolean, editedText: String?) {
        val words = try { RecordingHotwordPolicy.sessionWords(hotwords) } catch (_: IllegalArgumentException) {
            report(R.string.recording_hotword_invalid)
            return
        }
        edit(id) {
            it?.copy(title = title.trim().take(100).ifBlank { it.title }, hotwords = words, useHotwordLibrary = useHotwordLibrary,
                organized = editedText ?: it.organized, manualEdit = it.manualEdit || editedText != null,
                reprocessThrough = it.reprocessThrough.takeIf { editedText == null },
                reprocessText = it.reprocessText.takeIf { editedText == null }.orEmpty())
        }
    }

    fun downloadAsrModel() = asr.start()
    fun cancelAsrModelDownload() = asr.cancel()
    fun unknownHotwordCharacters(text: String): List<Char>? = asr.unknownCharacters(text)

    private fun effective(item: RecordingSession) = effectiveRecordingHotwords(item, _hotwords.value)
    private fun decoderRequest(item: RecordingSession): RecordingDecoderRequest {
        val directory = asr.installedDirectory()
        val vocabulary = asr.tokenCharacters() ?: directory?.resolve("tokens.txt")?.useLines { recordingTokenCharacters(it) }.orEmpty()
        return RecordingDecoderRequest(directory, effective(item), vocabulary)
    }

    private suspend fun writeHotwords(entries: List<RecordingHotword>) {
        hotwordStore.write(entries)
        _hotwords.value = entries
    }

    /** 返回可显示的错误，表单只在持久化成功后关闭。 */
    suspend fun saveHotword(entry: RecordingHotword): Int? = withContext(Dispatchers.IO) {
        state.first { it.ready }
        lock.withLock {
            try {
                val clean = RecordingHotwordPolicy.normalize(entry)
                val entries = _hotwords.value.toMutableList()
                val index = entries.indexOfFirst { it.id == clean.id }
                if (index < 0) entries.add(clean) else entries[index] = clean
                writeHotwords(RecordingHotwordPolicy.validate(entries))
                null
            } catch (_: IllegalArgumentException) {
                R.string.recording_hotword_invalid
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                R.string.recording_hotword_storage
            }
        }
    }

    fun deleteHotword(id: String) = changeHotwords { entries -> entries.filterNot { it.id == id } }
    fun setHotwordEnabled(id: String, enabled: Boolean) = changeHotwords { entries ->
        entries.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    private fun changeHotwords(transform: (List<RecordingHotword>) -> List<RecordingHotword>) {
        io.launch {
            state.first { it.ready }
            lock.withLock {
                try { writeHotwords(transform(_hotwords.value)) } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    report(R.string.recording_hotword_storage)
                }
            }
        }
    }

    suspend fun importHotwords(text: String): RecordingHotwordImport? = withContext(Dispatchers.IO) {
        state.first { it.ready }
        lock.withLock {
            val (entries, result) = RecordingHotwordPolicy.import(text, _hotwords.value)
            try {
                if (result.added > 0) writeHotwords(entries)
                result
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                null
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun recognize(id: String, rewriteOrganized: Boolean) {
        io.launch {
            state.first { it.ready }
            lock.withLock {
                val item = session(id) ?: return@withLock
                if (!recordingCanRecognize(item, _state.value.activeId, _state.value.recognizingId) ||
                    capture?.isActive == true || recognition?.isCompleted == false || !asr.isInstalled()) return@withLock
                if (_state.value.organizingId == id) organize?.cancel()
                val request = decoderRequest(item)
                // 即使刚派发就被取消，也要执行 finally 清除界面上的识别状态。
                recognition = io.launch(start = CoroutineStart.ATOMIC) {
                    try {
                        currentCoroutineContext().ensureActive()
                        val segments = recognizerUse.withLock {
                            recognizeWithWakeLock {
                                decoderFactory.create(request).use { decoder ->
                                    recognizeRecordingWav(store.audio(id), decoder) { progress ->
                                        lock.withLock { _state.value = _state.value.copy(recognizeProgress = progress.coerceIn(0f, 1f)) }
                                    }
                                }
                            }
                        }
                        lock.withLock {
                            currentCoroutineContext().ensureActive()
                            val current = session(id) ?: return@withLock
                            if (current.copy(documentId = item.documentId, documentHash = item.documentHash) != item ||
                                effective(current) != request.hotwords) return@withLock
                            withContext(NonCancellable) { save(recordingRecognitionResult(current, segments, rewriteOrganized)) }
                            nextAttempt = 0
                            publish(id)
                        }
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        report(R.string.recording_recognize_failed)
                    } catch (_: LinkageError) { report(R.string.recording_recognize_failed) }
                    finally {
                        withContext(NonCancellable) {
                            lock.withLock { _state.value = _state.value.copy(recognizingId = null, recognizeProgress = 0f) }
                        }
                    }
                }
                _state.value = _state.value.copy(recognizingId = id, recognizeProgress = 0f)
            }
        }
    }

    fun cancelRecognize() { recognition?.cancel() }

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

    /** 删除已结束或中断的录音；录音进行中的那一条不删。资料库里已保存的文档不受影响。 */
    fun delete(id: String) {
        io.launch {
            publishing.withLock {
                lock.withLock {
                    val item = session(id) ?: return@withLock
                    if (recordingDeletable(item, _state.value.activeId)) discard(id)
                }
            }
        }
    }

    /** 用户删除资料文档后，一并删除它对应的录音；正在录的那一条保留，结束后会重新保存到资料库。 */
    fun deleteForDocuments(documentIds: Collection<String>) {
        if (documentIds.isEmpty()) return
        io.launch {
            state.first { it.ready }
            publishing.withLock {
                lock.withLock {
                    recordingsRemovedWithDocuments(_state.value.sessions, documentIds, _state.value.activeId)
                        .forEach { discard(it) }
                }
            }
        }
    }

    // 调用方持有 publishing 与 lock。删除失败时录音保留并提示。
    private suspend fun discard(id: String) {
        if (id == _state.value.organizingId) organize?.cancel()
        if (id == _state.value.recognizingId) recognition?.cancel()
        try {
            store.delete(id)
            _state.value = _state.value.copy(sessions = _state.value.sessions.filterNot { it.id == id })
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            report(R.string.recording_storage_failed)
        }
    }

    fun publish(id: String) {
        io.launch {
            publishing.withLock {
                val item = lock.withLock { session(id) } ?: return@withLock
                if (item.phase !in setOf(RecordingPhase.Interrupted, RecordingPhase.Finished)) return@withLock
                try {
                    val document = publishDocument(item)
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
            it.id != _state.value.recognizingId && !it.manualEdit && it.organizationCursor < it.segments.size &&
                (active == null || it.id == active) &&
                chooseRecordingRoute(it.mode, cloud?.key, it.cloudConsent, local != null, it.fallback) in
                (if (active != null || foreground) setOf(RecordingRoute.Cloud, RecordingRoute.Local)
                 else setOf(RecordingRoute.Cloud))
        } ?: return
        val useCloud = item.mode == RecordingMode.Auto && cloud != null
        organizingCloud = useCloud
        val range = nextRecordingBatch(item.segments, item.organizationCursor, if (useCloud) 2000 else 300) ?: return
        _state.value = _state.value.copy(organizingId = item.id)
        val hotwords = effective(item)
        organize = io.launch {
            try {
                val result = withTimeoutOrNull(120_000) {
                    model.organize(item, range, cloud.takeIf { useCloud }, local?.modelName, recordingHotwordGlossary(hotwords))
                } ?: error("整理超时")
                lock.withLock {
                    val current = session(item.id) ?: return@withLock
                    if (current.mode != item.mode || current.cloudConsent != item.cloudConsent || current.manualEdit ||
                        current.organizationCursor != range.first || effective(current) != hotwords
                    ) return@withLock
                    val cursor = range.last + 1
                    val staging = current.reprocessThrough != null
                    val done = cursor == current.segments.size
                    val prior = if (staging) current.reprocessText else current.organized
                    val text = listOf(prior, result).filter(String::isNotBlank).joinToString("\n\n")
                    save(if (staging && !done) current.copy(reprocessText = text, reprocessThrough = cursor)
                         else current.copy(organized = text, organizedThrough = cursor, reprocessThrough = null, reprocessText = ""))
                    if (done && current.phase in setOf(RecordingPhase.Finished, RecordingPhase.Interrupted)) publish(current.id)
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

    /** 失败提示收起后清除这条失败；整理失败保留到下次整理成功，页面据此显示“待整理”。 */
    fun dismissFailure(message: Int) {
        if (message == R.string.recording_organize_failed) return
        io.launch {
            lock.withLock {
                if (_state.value.failure == message) _state.value = _state.value.copy(failure = null)
            }
        }
    }
}

internal fun Context.recordingController() = (applicationContext as InfinityApplication).recordings
