package com.promenar.infinity.recording

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.backup.BackupStartupState
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
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
    val appending: Boolean = false,
    val lastDiscardedId: String? = null,
    val audioOnly: Boolean = false,
    val partial: String = "",
    val durationMs: Long = 0,
    val organizingId: String? = null,
    val recognizingId: String? = null,
    val recognizeProgress: Float = 0f,
    val speakerAnalysisId: String? = null,
    val speakerProgress: Float = 0f,
    val failure: Int? = null,
    val ready: Boolean = false,
    val cloudKey: String? = null,
    val cloudLabel: String? = null,
    val localLabel: String? = null,
) {
    val localReady get() = localLabel != null
}

internal typealias RecordingAudioCapture = suspend (
    Context, RecordingStore, RecordingSession, () -> CaptureCommand, WithRecordingDecoder,
    suspend () -> Unit, RecordingWaveformSampler, suspend (CaptureFrame) -> Unit,
) -> CaptureCommand
internal typealias RecordingDocumentPublisher = suspend (RecordingSession, suspend (String?, String) -> Unit) -> RecordingDocument

internal data class RecordingBatchDeletion(
    val deleted: Set<String> = emptySet(),
    val failed: Set<String> = emptySet(),
    val unavailable: Int = 0,
)

internal class RecordingController(
    private val app: InfinityApplication,
    private val io: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val decoderFactory: RecordingDecoderFactory = RecordingDecoderFactory(::createRecordingDecoder),
    private val recordAudio: RecordingAudioCapture = ::captureRecording,
    private val organizationEnabled: Boolean = true,
    private val publishDocument: RecordingDocumentPublisher = { item, prepare ->
        RecordingLibrary(app).publish(item, prepare)
    },
    private val recognizeWithWakeLock: suspend (suspend () -> List<RecordingSegment>) -> List<RecordingSegment> = { withRecordingRecognitionWakeLock(app, it) },
) {
    val store = RecordingStore(app)
    val recommendedModel = com.promenar.infinity.data.local.inference.RecordingModelInstaller(app, io)
    private val model = RecordingModel(app)
    private val asr = RecordingAsrModelManager(app, io)
    val asrModel = asr.state
    val asrModelInstalledBytes = asr.spec.installedBytes
    val asrModelDownloadBytes = asr.spec.bytes
    private val hotwordStore = RecordingHotwordStore(java.io.File(app.filesDir, RecordingHotwordStore.FILE_NAME))
    private val _hotwords = MutableStateFlow<List<RecordingHotword>>(emptyList())
    val hotwords = _hotwords.asStateFlow()
    private val speakerModels = RecordingSpeakerModelManager(app, io)
    val speakerModel = speakerModels.state
    private val voiceStore = RecordingVoiceStore(java.io.File(app.noBackupFilesDir, RecordingVoiceStore.FILE_NAME))
    private val voicePeopleValue = MutableStateFlow<List<RecordingVoicePerson>>(emptyList())
    val voicePeople = voicePeopleValue.asStateFlow()
    private val speakerUse = Mutex()
    private var speakerJob: Job? = null
    private val speakerAnalysisFailures = mutableMapOf<String, Long>()

    private val recognizerUse = Mutex()
    @Volatile private var recognition: Job? = null
    private val lock = Mutex()
    private val publishing = Mutex()
    private val microphone = Mutex()
    private val command = AtomicReference(CaptureCommand.Continue)
    private val serviceCommands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val liveDraftSignals = Channel<Unit>(Channel.CONFLATED)
    private val _state = MutableStateFlow(RecordingScreenState())
    val state = _state.asStateFlow()
    private val _waveform = MutableStateFlow(RecordingWaveformSamples())
    val waveform = _waveform.asStateFlow()
    private var capture: Job? = null
    private var organize: Job? = null
    private var quiet = false
    private var cloudJobRunning = false
    private var organizingCloud = false
    private var completingFirstLocalDraft = false
    private var firstLocalDraftAttempted = false
    private val nextAttempt = mutableMapOf<String, Long>()
    private val unfinishedRollbacks = linkedSetOf<String>()

    init {
        io.launch {
            for (action in serviceCommands) action()
        }
        if (organizationEnabled) io.launch {
            for (signal in liveDraftSignals) {
                try {
                    lock.withLock {
                        if (_state.value.activeId != null) scheduleOrganization()
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    InfinityLogger.diagnostic("recording.scheduler_failed", mapOf("cause" to failure.javaClass.simpleName))
                    report(R.string.recording_organize_failed)
                }
            }
        }
        io.launch {
            val restored = store.restore()
            val rollbacks = store.pendingDiscardIds()
            val hotwordFailure = try {
                _hotwords.value = hotwordStore.read()
                null
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                R.string.recording_hotword_storage
            }
            voicePeopleValue.value = runCatching { voiceStore.read() }.getOrDefault(emptyList())
            lock.withLock {
                unfinishedRollbacks.addAll(rollbacks)
                _state.value = RecordingScreenState(sessions = restored, ready = true, failure = hotwordFailure)
            }
            restored.filter {
                it.needsDocumentPublication()
            }.forEach { publish(it.id) }
            speakerModel.collect { model ->
                if (model == RecordingAsrModelState.Installed) lock.withLock { scheduleSpeakerAnalysis() }
            }
        }
        io.launch {
            while (true) {
                try {
                    lock.withLock {
                        retryCaptureRollback()
                        if (organizationEnabled) {
                            scheduleSpeakerAnalysis()
                            scheduleOrganization()
                        }
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    InfinityLogger.diagnostic("recording.scheduler_failed", mapOf("cause" to failure.javaClass.simpleName))
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
            // 等待上一版资料文档完成发布，再为续录保存检查点。
            if (id == null) startCapture(id, onIdle)
            else publishing.withLock { startCapture(id, onIdle) }
        }
    }

    private suspend fun startCapture(id: String?, onIdle: () -> Unit) {
        lock.withLock {
            if (capture?.isActive == true) return@withLock
            val previous = _state.value.activeId?.let(::session)
            if (previous != null && (previous.id != id || previous.phase != RecordingPhase.Paused)) return@withLock
            val resume = id?.let(::session)
            val fromPause = previous != null && previous.id == id
            if (id != null && resume?.canContinue != true) {
                if (_state.value.activeId == null) onIdle()
                return@withLock
            }
            val item = (resume ?: store.newSession(
                app.getString(R.string.recording_default_title,
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())),
            )).copy(phase = RecordingPhase.Recording)
            try {
                if (fromPause) save(item) else {
                    store.beginCapture(resume, item)
                    replace(item)
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                report(R.string.recording_storage_failed)
                if (_state.value.activeId == null) onIdle()
                return@withLock
            }
            recognition?.cancel()
            speakerJob?.cancel()
            quiet = false
            if (!fromPause) firstLocalDraftAttempted = resume != null
            command.set(CaptureCommand.Continue)
            organize?.cancel()
            nextAttempt.remove(item.id)
            if (!fromPause) _waveform.value = RecordingWaveformSamples()
            _state.value = _state.value.copy(activeId = item.id, opening = true,
                appending = if (fromPause) _state.value.appending else resume != null, lastDiscardedId = null, audioOnly = false, partial = "", durationMs = item.durationMs, failure = null)
            capture = io.launch { record(item, onIdle) }
        }
    }

    private suspend fun record(item: RecordingSession, onIdle: () -> Unit) {
        var outcome: RecordingPhase
        val waveform = RecordingWaveformSampler(_waveform.value) { _waveform.value = it }
        try {
            val result = microphone.withLock {
                recordAudio(app, store, item, command::get, { decode ->
                    recognizerUse.withLock {
                        val request = lock.withLock {
                            decoderRequest(item).also { _state.value = _state.value.copy(audioOnly = it.directory == null) }
                        }
                        decoderFactory.create(request).use { decode(it) }
                    }
                }, {
                    lock.withLock { _state.value = _state.value.copy(opening = false) }
                }, waveform) { frame ->
                    lock.withLock {
                        val current = requireNotNull(session(item.id))
                        if (frame.checkpoint) {
                            val segments = frame.segment?.let { current.segments + it } ?: current.segments
                            save(current.copy(audioBytes = frame.bytes, segments = segments, checkpointText = frame.provisional))
                        }
                        val quietChanged = quiet != frame.quiet
                        if (quiet && !frame.quiet && !organizingCloud && !completingFirstLocalDraft) organize?.cancel()
                        quiet = frame.quiet
                        _state.value = _state.value.copy(
                            partial = frame.partial, durationMs = frame.bytes / 32 / 1000 * 1000,
                        )
                        if (organizationEnabled && current.liveOrganization && (frame.segment != null || quietChanged)) {
                            liveDraftSignals.trySend(Unit)
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
            if (command.get() == CaptureCommand.Discard) {
                completeCaptureDiscard(item.id, onIdle)
                return@withLock
            }
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
            if (outcome != RecordingPhase.Paused) {
                if (_state.value.organizingId == item.id) organize?.cancel()
                store.clearCapture(item.id)
            }
            if (outcome == RecordingPhase.Finished) {
                nextAttempt.remove(item.id)
                publish(item.id)
                startSpeakerAnalysis(item.id)
            }
            if (outcome != RecordingPhase.Paused) onIdle()
        }
    }

    fun pause() { command.compareAndSet(CaptureCommand.Continue, CaptureCommand.Pause) }

    fun finish(onIdle: () -> Unit) {
        serviceCommands.trySend {
            lock.withLock {
                if (command.get() == CaptureCommand.Discard) return@withLock
                command.set(CaptureCommand.Finish)
                val current = _state.value.activeId?.let(::session)
                if (current != null && _state.value.organizingId == current.id) organize?.cancel()
                if (capture?.isActive == true) return@withLock
                if (current != null) {
                    try {
                        save(current.copy(phase = RecordingPhase.Finished))
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        report(R.string.recording_storage_failed)
                        return@withLock
                    }
                    store.clearCapture(current.id)
                    nextAttempt.remove(current.id)
                    _state.value = _state.value.copy(activeId = null)
                    publish(current.id)
                    startSpeakerAnalysis(current.id)
                }
                onIdle()
            }
        }
    }

    fun discardCapture(onIdle: () -> Unit) {
        serviceCommands.trySend {
            lock.withLock {
                val id = _state.value.activeId
                if (id == null) { onIdle(); return@withLock }
                if (command.get() == CaptureCommand.Discard) return@withLock
                try {
                    store.markDiscard(id)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    report(R.string.recording_storage_failed)
                    return@withLock
                }
                organize?.cancel()
                command.set(CaptureCommand.Discard)
                if (capture?.isActive != true) completeCaptureDiscard(id, onIdle)
            }
        }
    }

    // 调用方持有 lock，此时采集线程已关闭 WAV 文件。
    private suspend fun completeCaptureDiscard(id: String, onIdle: () -> Unit) {
        val original = runCatching { store.originalCapture(id) }.getOrNull()
        val restored = try {
            store.rollbackCapture(id)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            report(R.string.recording_storage_failed)
            unfinishedRollbacks.add(id)
            original?.copy(captureRollbackPending = true)
        }
        if (restored != null) replace(restored)
        val current = _state.value
        _state.value = current.copy(
            sessions = if (restored == null) current.sessions.filterNot { it.id == id } else current.sessions,
            activeId = null, lastDiscardedId = id.takeUnless { it in unfinishedRollbacks }, opening = false, appending = false,
            partial = "", durationMs = restored?.durationMs ?: 0,
        )
        capture = null
        quiet = true
        command.set(CaptureCommand.Finish)
        if (restored?.needsDocumentPublication() == true) publish(id)
        onIdle()
    }

    private suspend fun retryCaptureRollback() {
        val iterator = unfinishedRollbacks.iterator()
        while (iterator.hasNext()) {
            val id = iterator.next()
            val original = try {
                store.rollbackCapture(id)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                continue
            }
            iterator.remove()
            if (original != null) {
                replace(original)
                if (original.needsDocumentPublication()) publish(id)
            }
            else _state.value = _state.value.copy(sessions = _state.value.sessions.filterNot { it.id == id })
            _state.value = _state.value.copy(lastDiscardedId = id)
        }
    }

    fun downloadSpeakerModel() = speakerModels.start()
    fun cancelSpeakerModel() = speakerModels.cancel()

    fun distinguishSpeakers(id: String?, enabled: Boolean) {
        if (enabled && speakerModels.state.value == RecordingAsrModelState.Missing) speakerModels.start()
        if (id == null) store.distinguishSpeakers = enabled
        else io.launch {
            lock.withLock {
                val item = session(id) ?: return@withLock
                if (!enabled && _state.value.speakerAnalysisId == id) speakerJob?.cancel()
                val updated = item.copy(distinguishSpeakers = enabled)
                save(if (enabled && item.speakerAnalyzedBytes != item.audioBytes) updated else updated.forSpeakerOrganization())
                if (enabled) startSpeakerAnalysis(id)
                else publish(id)
            }
        }
    }

    fun analyzeSpeakers(id: String) = io.launch { lock.withLock { startSpeakerAnalysis(id, force = true) } }

    private fun scheduleSpeakerAnalysis() {
        if (speakerModels.state.value != RecordingAsrModelState.Installed || _state.value.activeId != null ||
            speakerJob?.isCompleted == false || _state.value.recognizingId != null) return
        _state.value.sessions.firstOrNull {
            it.distinguishSpeakers && it.audioBytes > 0 && it.speakerAnalyzedBytes != it.audioBytes &&
                speakerAnalysisFailures[it.id] != it.audioBytes &&
                it.phase in setOf(RecordingPhase.Finished, RecordingPhase.Interrupted)
        }?.let { startSpeakerAnalysis(it.id) }
    }

    private fun RecordingSession.forSpeakerOrganization(): RecordingSession =
        if (manualEdit) this else copy(summaryPending = true, draftBatches = emptyList(), summaryProgress = null)

    @OptIn(DelicateCoroutinesApi::class)
    private fun startSpeakerAnalysis(id: String, force: Boolean = false) {
        val item = session(id) ?: return
        val files = speakerModels.installed() ?: return
        if (item.captureRollbackPending || !item.distinguishSpeakers || item.audioBytes == 0L || _state.value.activeId != null ||
            _state.value.recognizingId != null || speakerJob?.isCompleted == false ||
            (!force && item.speakerAnalyzedBytes == item.audioBytes) ||
            item.phase !in setOf(RecordingPhase.Finished, RecordingPhase.Interrupted)) return
        if (_state.value.organizingId == id) organize?.cancel()
        speakerAnalysisFailures.remove(id)
        _state.value = _state.value.copy(speakerAnalysisId = id, speakerProgress = 0f)
        speakerJob = io.launch(start = CoroutineStart.ATOMIC) {
            try {
                val request = lock.withLock { decoderRequest(item) }
                val people = voicePeopleValue.value
                val (result, refined) = withRecordingRecognitionWakeLock(app) {
                    analyzeSpeakerRecording(item, files, item.segments, request, people) { fraction ->
                        lock.withLock { _state.value = _state.value.copy(speakerProgress = fraction) }
                    }
                }
                lock.withLock {
                    val current = session(id) ?: return@withLock
                    if (current.audioBytes != item.audioBytes || current.segments != item.segments ||
                        current.speakers != item.speakers || !current.distinguishSpeakers ||
                        effective(current) != request.hotwords || voicePeopleValue.value != people) return@withLock
                    val analyzed = current.copy(segments = refined).applySpeakerAnalysis(result)
                    save(analyzed.forSpeakerOrganization())
                    publish(id)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                lock.withLock { speakerAnalysisFailures[id] = item.audioBytes }
                report(R.string.recording_speaker_analysis_failed)
            } catch (_: LinkageError) {
                lock.withLock { speakerAnalysisFailures[id] = item.audioBytes }
                report(R.string.recording_speaker_analysis_failed)
            } finally {
                withContext(NonCancellable) {
                    lock.withLock { _state.value = _state.value.copy(speakerAnalysisId = null, speakerProgress = 0f) }
                }
            }
        }
    }

    private suspend fun analyzeSpeakerRecording(
        item: RecordingSession,
        files: RecordingSpeakerModels,
        segments: List<RecordingSegment>,
        request: RecordingDecoderRequest,
        people: List<RecordingVoicePerson>,
        progress: suspend (Float) -> Unit,
    ): Pair<RecordingSpeakerResult, List<RecordingSegment>> {
        val audio = store.audio(item.id)
        val result = speakerUse.withLock {
            RecordingSpeakerAnalysis.analyze(files, audio, item.durationMs, people) { progress(it * .8f) }
        }
        val preserved = if (item.speakerAssignmentsEdited) segments else segments.map { it.copy(speakerId = null) }
        val needsRefinement = request.directory != null && result.speakers.size > 1 && preserved.isNotEmpty()
        val refined = if (!needsRefinement) preserved else recognizerUse.withLock {
            decoderFactory.create(request).use { refineRecordingSpeakerSegments(audio, preserved, result.turns, it) }
        }
        progress(1f)
        return result to refined
    }

    fun updateSpeaker(id: String, speakerId: Int, name: String, personId: String? = null) {
        editSpeaker(id) { item ->
            val trimmed = name.trim()
            require(trimmed.length <= 50)
            item.copy(speakers = item.speakers.map {
                if (it.id == speakerId) it.copy(name = trimmed, personId = personId, confidence = null) else it
            })
        }
    }

    fun moveSpeaker(id: String, from: Int, to: Int?, segmentStartMs: Long? = null) {
        editSpeaker(id) { item ->
            if (to != null) item.withSpeakerChange(from, to, segmentStartMs)
            else {
                val next = (item.speakers.maxOfOrNull { it.id } ?: -1) + 1
                item.copy(speakers = item.speakers + RecordingSpeaker(next)).withSpeakerChange(from, next, segmentStartMs)
            }
        }
    }

    private fun editSpeaker(id: String, transform: (RecordingSession) -> RecordingSession) = io.launch {
        lock.withLock {
            val item = session(id) ?: return@withLock
            try {
                if (_state.value.organizingId == id) organize?.cancel()
                save(transform(item).copy(speakerAssignmentsEdited = true).forSpeakerOrganization())
                publish(id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                report(R.string.recording_storage_failed)
            }
        }
    }

    suspend fun saveVoicePerson(person: RecordingVoicePerson) = lock.withLock {
        val people = voicePeopleValue.value
        val old = people.firstOrNull { it.id == person.id }
        writeVoicePeople(if (old == null) people + person else people.map { if (it.id == person.id) person else it })
        if (old != null && old.name != person.name) syncVoiceName(person.id, person.name)
    }

    suspend fun renameVoicePerson(id: String, name: String) = lock.withLock {
        val people = voicePeopleValue.value
        val person = people.firstOrNull { it.id == id } ?: return@withLock
        if (person.name == name) return@withLock
        writeVoicePeople(people.map { if (it.id == id) it.copy(name = name) else it })
        syncVoiceName(id, name)
    }

    suspend fun removeVoiceSample(id: String, sampleId: String) = lock.withLock {
        val people = voicePeopleValue.value
        val person = people.firstOrNull { it.id == id } ?: return@withLock
        require(person.samples.size > 1)
        writeVoicePeople(people.map {
            if (it.id == id) it.copy(samples = it.samples.filterNot { sample -> sample.id == sampleId }) else it
        })
    }

    suspend fun deleteVoicePerson(id: String) = lock.withLock {
        writeVoicePeople(voicePeopleValue.value.filterNot { it.id == id })
        syncVoiceName(id, null)
    }

    private suspend fun writeVoicePeople(people: List<RecordingVoicePerson>) = withContext(Dispatchers.IO + NonCancellable) {
        voiceStore.write(people)
        voicePeopleValue.value = people
    }

    private suspend fun syncVoiceName(personId: String, name: String?) {
        for (item in _state.value.sessions.filter { item -> item.speakers.any { it.personId == personId } }) {
            save(item.copy(speakers = item.speakers.map {
                if (it.personId != personId) it else it.copy(name = name.orEmpty(), personId = personId.takeIf { name != null }, confidence = null)
            }).forSpeakerOrganization())
            publish(item.id)
        }
    }

    suspend fun speakerSample(id: String, speakerId: Int): RecordingVoiceSample = withContext(Dispatchers.IO) {
        val item = lock.withLock { session(id) } ?: error("录音不存在")
        val segment = item.segments.filter { it.speakerId == speakerId }.maxByOrNull { it.endMs - it.startMs }
            ?: error("说话人没有可用音频")
        val files = speakerModels.installed() ?: error("声纹模型未安装")
        speakerUse.withLock { RecordingSpeakerAnalysis.sample(files, store.audio(id), segment, item.title) }
    }

    suspend fun enrollVoice(input: FloatArray): RecordingVoiceSample = withContext(Dispatchers.IO) {
        val files = speakerModels.installed() ?: error("声纹模型未安装")
        speakerUse.withLock { RecordingSpeakerAnalysis.sample(files, input, app.getString(R.string.recording_voice_enroll)) }
    }

    suspend fun <T> useVoiceMicrophone(action: suspend () -> T): T = microphone.withLock {
        check(_state.value.activeId == null)
        action()
    }

    fun setMode(id: String?, mode: RecordingMode) {
        if (id == null) store.mode = mode
        else edit(id) { it?.copy(mode = mode, cloudConsent = if (mode == RecordingMode.Auto) it.cloudConsent else null) }
    }

    fun configure(id: String?, mode: RecordingMode, language: RecordingLanguage, live: Boolean, speakers: Boolean) {
        if (id == null) {
            store.mode = mode
            store.outputLanguage = language
            store.liveOrganization = live
            store.distinguishSpeakers = speakers
            if (speakers) downloadSpeakerModel()
            return
        }
        io.launch {
            lock.withLock {
                val item = session(id)?.takeUnless { it.captureRollbackPending } ?: return@withLock
                if (_state.value.organizingId == id) organize?.cancel()
                val changedSpeakers = item.distinguishSpeakers != speakers
                if (changedSpeakers && !speakers && _state.value.speakerAnalysisId == id) speakerJob?.cancel()
                try {
                    val changed = item.copy(mode = mode, outputLanguage = language, liveOrganization = live,
                        distinguishSpeakers = speakers, cloudConsent = item.cloudConsent.takeIf { mode == item.mode })
                    save(if (changedSpeakers) changed.forSpeakerOrganization() else changed)
                    nextAttempt.remove(id)
                    if (changedSpeakers && speakers) {
                        if (speakerModels.state.value == RecordingAsrModelState.Missing) speakerModels.start()
                        startSpeakerAnalysis(id)
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    report(R.string.recording_storage_failed)
                }
            }
        }
    }

    fun selectLanguage(id: String, language: RecordingLanguage) = edit(id) { it?.copy(outputLanguage = language) }

    fun allowCloud(id: String, key: String?) = edit(id) { it?.copy(cloudConsent = key) }

    fun updateDraft(id: String, title: String, hotwords: List<String>, useHotwordLibrary: Boolean, editedText: String?) {
        val words = try { RecordingHotwordPolicy.sessionWords(hotwords) } catch (_: IllegalArgumentException) {
            report(R.string.recording_hotword_invalid)
            return
        }
        edit(id) {
            it?.copy(title = title.trim().take(100).ifBlank { it.title }, hotwords = words, useHotwordLibrary = useHotwordLibrary,
                organizedThrough = if (editedText != null) it.segments.size else it.organizedThrough,
                organized = editedText ?: it.organized, manualEdit = it.manualEdit || editedText != null,
                reprocessThrough = it.reprocessThrough.takeIf { editedText == null },
                reprocessText = it.reprocessText.takeIf { editedText == null }.orEmpty(),
                summaryPending = it.summaryPending && editedText == null)
        }
    }

    fun downloadAsrModel() = asr.start()
    fun cancelAsrModelDownload() = asr.cancel()
    fun unknownHotwordCharacters(text: String): List<Char>? = asr.unknownCharacters(text)

    private fun effective(item: RecordingSession) = effectiveRecordingHotwords(item, _hotwords.value)
    private fun decoderRequest(item: RecordingSession): RecordingDecoderRequest {
        val directory = asr.installedDirectory()
        val vocabulary = asr.tokenCharacters() ?: directory?.resolve("tokens.txt")?.useLines { recordingTokenCharacters(it) }.orEmpty()
        return RecordingDecoderRequest(directory, effective(item), vocabulary, app.assets)
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
                if (_state.value.speakerAnalysisId == id) speakerJob?.cancel()
                val request = decoderRequest(item)
                // 即使刚派发就被取消，也要执行 finally 清除界面上的识别状态。
                recognition = io.launch(start = CoroutineStart.ATOMIC) {
                    try {
                        currentCoroutineContext().ensureActive()
                        val speakerFiles = speakerModels.installed()?.takeIf { item.distinguishSpeakers }
                        val recognitionShare = if (speakerFiles == null) 1f else .85f
                        val people = voicePeopleValue.value
                        val segments = recognizerUse.withLock {
                            recognizeWithWakeLock {
                                decoderFactory.create(request).use { decoder ->
                                    val audio = store.audio(id)
                                    val recognized = recognizeRecordingWav(audio, decoder) { progress ->
                                        lock.withLock { _state.value = _state.value.copy(recognizeProgress = progress.coerceIn(0f, 1f) * recognitionShare) }
                                    }
                                    if (!item.distinguishSpeakers || !item.speakerAssignmentsEdited) recognized
                                    else {
                                        decoder.reset()
                                        val refined = refineRecordingSpeakerSegments(
                                            audio, recognized, item.assignedSpeakerTurns(), decoder, protectAssignments = true,
                                        )
                                        item.copy(segments = refined).preserveSpeakerAssignments(item).segments
                                    }
                                }
                            }
                        }
                        val speakerResult = speakerFiles?.let { files ->
                            try {
                                withRecordingRecognitionWakeLock(app) {
                                    analyzeSpeakerRecording(item, files, segments, request, people) { fraction ->
                                        lock.withLock { _state.value = _state.value.copy(recognizeProgress = .85f + fraction * .15f) }
                                    }
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                report(R.string.recording_speaker_analysis_failed)
                                null
                            }
                        }
                        lock.withLock {
                            currentCoroutineContext().ensureActive()
                            val current = session(id) ?: return@withLock
                            if (current.copy(documentId = item.documentId, documentHash = item.documentHash,
                                    pendingDocumentHash = item.pendingDocumentHash) != item ||
                                effective(current) != request.hotwords) return@withLock
                            val result = speakerResult.takeIf { voicePeopleValue.value == people }
                            val next = recordingRecognitionResult(current, result?.second ?: segments, rewriteOrganized)
                            withContext(NonCancellable) {
                                save(if (result == null) {
                                    next.preserveSpeakerAssignments(current).copy(
                                        speakerAnalyzedBytes = if (current.distinguishSpeakers) 0 else current.speakerAnalyzedBytes,
                                    )
                                } else next.applySpeakerAnalysis(result.first))
                            }
                            nextAttempt.remove(id)
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

    fun retry(id: String, language: RecordingLanguage? = null) = edit(id) {
        it?.copy(outputLanguage = language ?: it.outputLanguage, summaryPending = true,
            reprocessThrough = null, reprocessText = "", summaryProgress = null)
    }

    private fun edit(id: String?, transform: (RecordingSession?) -> RecordingSession?) {
        io.launch {
            lock.withLock {
                if (id == _state.value.organizingId) organize?.cancel()
                if (id != null) nextAttempt.remove(id)
                try {
                    transform(id?.let(::session)?.takeUnless { it.captureRollbackPending })?.let { item ->
                        save(item)
                        if (item.phase == RecordingPhase.Finished || item.phase == RecordingPhase.Interrupted) publish(item.id)
                    }
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
                    if (recordingDeletable(item, _state.value.activeId) && !discard(id)) {
                        report(R.string.recording_storage_failed)
                    }
                }
            }
        }
    }

    /** 固定确认时的 ID；持锁重新检查录音状态，再逐条提交删除。 */
    fun deleteBatch(ids: Collection<String>): Deferred<RecordingBatchDeletion> {
        val confirmed = ids.toSet()
        return io.async {
            state.first { it.ready }
            publishing.withLock {
                lock.withLock {
                    val snapshot = _state.value
                    val eligible = snapshot.sessions.filter { it.id in confirmed && recordingDeletable(it, snapshot.activeId) }
                    val deleted = mutableSetOf<String>()
                    val failed = mutableSetOf<String>()
                    for (item in eligible) {
                        currentCoroutineContext().ensureActive()
                        if (discard(item.id)) deleted.add(item.id) else failed.add(item.id)
                    }
                    RecordingBatchDeletion(deleted, failed, confirmed.size - eligible.size)
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
                        .forEach { if (!discard(it)) report(R.string.recording_storage_failed) }
                }
            }
        }
    }

    // 调用方持有 publishing 与 lock。存储提交失败时保留会话及其后台任务。
    private suspend fun discard(id: String): Boolean = withContext(NonCancellable) {
        try {
            store.delete(id)
            if (id == _state.value.organizingId) organize?.cancel()
            if (id == _state.value.recognizingId) recognition?.cancel()
            if (id == _state.value.speakerAnalysisId) speakerJob?.cancel()
            nextAttempt.remove(id)
            speakerAnalysisFailures.remove(id)
            _state.value = _state.value.copy(sessions = _state.value.sessions.filterNot { it.id == id })
            true
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            false
        }
    }

    fun publish(id: String) {
        io.launch {
            publishing.withLock {
                try {
                    val item = lock.withLock { session(id)?.takeUnless { it.captureRollbackPending ||
                        it.phase !in setOf(RecordingPhase.Interrupted, RecordingPhase.Finished) } } ?: return@withLock
                    val document = publishDocument(item) { base, target ->
                        lock.withLock {
                            val current = requireNotNull(session(id))
                            save(current.copy(documentHash = base, pendingDocumentHash = target))
                        }
                    }
                    lock.withLock {
                        val current = session(id) ?: return@withLock
                        save(current.copy(documentId = document.path, documentHash = document.hash, pendingDocumentHash = null))
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

    private fun RecordingSession.needsDocumentPublication() = !captureRollbackPending &&
        (pendingDocumentHash != null || (phase == RecordingPhase.Finished ||
            phase == RecordingPhase.Interrupted && (documentHash != null || publishedLanguage != null)) &&
            documentHash != com.promenar.infinity.infra.util.Sha256Utils.hash(recordingMarkdown(this).toByteArray()))

    fun exportRaw(id: String, destination: Uri) {
        io.launch {
            val item = lock.withLock { session(id)?.takeUnless { it.captureRollbackPending } } ?: return@launch
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
        it.mode == RecordingMode.Auto && it.cloudConsent == key && it.needsFinalSummary()
    }

    private fun isForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentStateFlow.value.isAtLeast(Lifecycle.State.STARTED)

    private fun RecordingSession.needsFinalSummary() = !captureRollbackPending && segments.isNotEmpty() &&
        phase in setOf(RecordingPhase.Finished, RecordingPhase.Interrupted) &&
        (summaryPending || (!manualEdit && (organized.isBlank() || organizationCursor < segments.size)))

    private fun scheduleOrganization() {
        val cloud = model.cloud()
        val local = model.local()
        _state.value = _state.value.copy(cloudKey = cloud?.key, cloudLabel = cloud?.label, localLabel = local?.modelName)
        val active = _state.value.activeId
        val foreground = isForeground()
        if (organize?.isCompleted == false) return
        if (command.get() == CaptureCommand.Discard) return
        if (active != null && command.get() == CaptureCommand.Finish) return
        if (active == null && !foreground && !cloudJobRunning) return
        fun route(candidate: RecordingSession) =
            chooseRecordingRoute(candidate.mode, cloud?.key, candidate.cloudConsent, local != null, candidate.fallback)
        val item = _state.value.sessions.firstOrNull { candidate ->
            if (candidate.captureRollbackPending || SystemClock.elapsedRealtime() < (nextAttempt[candidate.id] ?: 0L) ||
                active != null && candidate.id != active || candidate.id == _state.value.recognizingId ||
                candidate.id == _state.value.speakerAnalysisId) return@firstOrNull false
            val selectedRoute = route(candidate)
            if (active == candidate.id) {
                return@firstOrNull nextLiveRecordingDraft(candidate, selectedRoute, quiet, !firstLocalDraftAttempted) != null
            }
            val ready = candidate.needsFinalSummary() && (!candidate.distinguishSpeakers ||
                speakerModels.state.value != RecordingAsrModelState.Installed ||
                candidate.speakerAnalyzedBytes == candidate.audioBytes || speakerAnalysisFailures[candidate.id] == candidate.audioBytes)
            ready && (selectedRoute == RecordingRoute.Cloud || foreground && selectedRoute == RecordingRoute.Local)
        } ?: return
        val live = active == item.id
        val selectedRoute = route(item)
        val useCloud = selectedRoute == RecordingRoute.Cloud
        val selectedCloud = cloud.takeIf { useCloud }
        val range = if (live) requireNotNull(nextLiveRecordingDraft(item, selectedRoute, quiet, !firstLocalDraftAttempted)) else null
        organizingCloud = useCloud
        // 仅允许首批本地草稿在语音恢复时继续，后续本地批次仍向实时听写让出资源。
        completingFirstLocalDraft = range?.first == 0 && selectedRoute == RecordingRoute.Local && !firstLocalDraftAttempted
        if (completingFirstLocalDraft) firstLocalDraftAttempted = true
        _state.value = _state.value.copy(organizingId = item.id)
        val hotwords = effective(item)
        val started = SystemClock.elapsedRealtime()
        val audioAtStart = maxOf(_state.value.durationMs, item.durationMs)
        val glossary = recordingHotwordGlossary(hotwords)
        val fingerprint = if (live) null else item.summaryFingerprint(glossary, selectedCloud?.key ?: local?.modelPath.orEmpty())
        val progress = fingerprint?.let { key -> item.summaryProgress?.takeIf { it.fingerprint == key }
            ?: RecordingSummaryProgress(fingerprint = key) }
        val stage = when {
            live -> "draft"
            requireNotNull(progress).cursor < item.segments.size -> "points"
            progress.overviewCursor < progress.points.size -> "overview"
            else -> "publish"
        }
        @OptIn(DelicateCoroutinesApi::class)
        val launched = io.launch(start = CoroutineStart.ATOMIC) {
            try {
                if (range?.first == 0) {
                    InfinityLogger.diagnostic("recording.draft_start", mapOf(
                        "start_audio_ms" to audioAtStart, "segment_end_ms" to item.segments[range.last].endMs,
                        "remote" to useCloud, "segments" to (range.last + 1),
                    ))
                }
                var batch: RecordingSummaryProgress? = null
                var firstOutput = 0L
                val inferenceStarted = SystemClock.elapsedRealtime()
                val result = withTimeoutOrNull(if (live) 30_000 else 75_000) {
                    if (range != null) model.organize(item, range, selectedCloud, local?.modelName, glossary) {
                        firstOutput = SystemClock.elapsedRealtime() - inferenceStarted
                    }
                    else if (requireNotNull(progress).cursor < item.segments.size) {
                        batch = model.summarizeBatch(item, progress, selectedCloud, local?.modelName, glossary)
                        ""
                    } else if (progress.overviewCursor < progress.points.size) {
                        batch = model.advanceOverview(item, progress, selectedCloud, local?.modelName)
                        ""
                    } else renderRecordingSummary(item, progress.overview, progress.points, progress.actions)
                } ?: throw java.util.concurrent.TimeoutException()
                val inferenceFinished = SystemClock.elapsedRealtime()
                lock.withLock {
                    val current = session(item.id) ?: return@withLock
                    if (command.get() == CaptureCommand.Discard || current.mode != item.mode ||
                        current.cloudConsent != item.cloudConsent || current.outputLanguage != item.outputLanguage ||
                        current.distinguishSpeakers != item.distinguishSpeakers || current.speakers != item.speakers ||
                        effective(current) != hotwords) return@withLock
                    if (range != null) {
                        if (command.get() == CaptureCommand.Finish || _state.value.activeId != current.id || !current.liveOrganization ||
                            current.draftCursor != range.first || current.segments.size <= range.last ||
                            range.any { current.segments[it] != item.segments[it] }) return@withLock
                        save(current.copy(draftBatches = current.draftBatches + RecordingDraftBatch(range.first, range.last + 1, result)))
                        InfinityLogger.diagnostic("recording.draft_batch", mapOf(
                            "latency_ms" to (SystemClock.elapsedRealtime() - started),
                            "lag_ms" to (_state.value.durationMs - item.segments[range.last].endMs).coerceAtLeast(0),
                            "start_audio_ms" to audioAtStart,
                            "segment_end_ms" to item.segments[range.last].endMs,
                            "first_output_ms" to firstOutput,
                            "inference_ms" to (inferenceFinished - inferenceStarted),
                            "commit_ms" to (SystemClock.elapsedRealtime() - inferenceFinished),
                            "segments" to (range.last - range.first + 1),
                        ))
                    } else {
                        if (current.segments != item.segments || current.phase != item.phase || current.manualEdit != item.manualEdit ||
                            current.organized != item.organized || !current.needsFinalSummary()) return@withLock
                        val step = batch
                        if (step != null) {
                            save(current.copy(summaryProgress = step))
                            return@withLock
                        }
                        save(current.copy(organized = result, organizedThrough = current.segments.size, manualEdit = false,
                            publishedLanguage = item.outputLanguage, summaryPending = false, summaryProgress = null,
                            reprocessThrough = null, reprocessText = "", draftBatches = emptyList()))
                        InfinityLogger.diagnostic("recording.summary_ready", mapOf("segments" to current.segments.size,
                            "points" to requireNotNull(progress).points.size, "language" to item.outputLanguage.name))
                        publish(current.id)
                    }
                    if (_state.value.failure == R.string.recording_organize_failed) _state.value = _state.value.copy(failure = null)
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                InfinityLogger.diagnostic("recording.organization_failed", mapOf(
                    "stage" to stage, "cause" to failure.javaClass.simpleName, "remote" to useCloud,
                    "elapsed_ms" to (SystemClock.elapsedRealtime() - started),
                ))
                lock.withLock {
                    nextAttempt[item.id] = SystemClock.elapsedRealtime() + if (live) 5_000 else 60_000
                    report(R.string.recording_organize_failed)
                }
            } finally {
                withContext(NonCancellable) {
                    lock.withLock {
                        organizingCloud = false
                        completingFirstLocalDraft = false
                        _state.value = _state.value.copy(organizingId = null)
                    }
                }
            }
        }
        organize = launched
    }

    private suspend fun save(item: RecordingSession) {
        store.write(item)
        replace(item)
        if (item.mode == RecordingMode.Auto && item.cloudConsent != null &&
            item.needsFinalSummary()) {
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
