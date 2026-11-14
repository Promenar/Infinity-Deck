package com.promenar.infinity.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.util.LocaleHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class RecordingActivity : ComponentActivity() {
    private val controller get() = recordingController()
    private var resumeAfterPermission: String? = null
    private var incomingDocument by mutableStateOf<String?>(null)
    private var incomingRevision by mutableIntStateOf(0)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val id = resumeAfterPermission
        resumeAfterPermission = null
        if (granted) beginWhenVisible(id, reopenActive = false)
        else controller.report(R.string.recording_permission_denied)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLanguage(newBase, LocaleHelper.getSavedLanguage(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        resumeAfterPermission = savedInstanceState?.getString("permissionSession")
        val app = application as InfinityApplication
        setContent {
            val preferences by app.themePreferenceStore.state.collectAsStateWithLifecycle()
            InfinityTheme(preferences = preferences) {
                RecordingScreen(
                    controller, onBack = ::finish, onBegin = ::requestMicrophone,
                    onFinish = { RecordingService.finish(this) },
                    onDiscard = { RecordingService.discard(this) },
                    incomingDocument = incomingDocument,
                    incomingRevision = incomingRevision,
                )
            }
        }
        incomingDocument = intent.getStringExtra("viewSession")
        if (savedInstanceState == null) handleEntry(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("permissionSession", resumeAfterPermission)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingRevision++
        handleEntry(intent)
    }

    private fun handleEntry(intent: Intent) {
        incomingDocument = intent.getStringExtra("viewSession")
        if (intent.action == "recording.start") beginWhenVisible(null, reopenActive = true)
    }

    private fun requestMicrophone(id: String?) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            try {
                RecordingService.begin(this, id)
            } catch (_: IllegalStateException) {
                controller.report(R.string.recording_capture_failed)
            } catch (_: SecurityException) {
                controller.report(R.string.recording_capture_failed)
            }
        } else {
            resumeAfterPermission = id
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun beginWhenVisible(id: String?, reopenActive: Boolean) {
        lifecycleScope.launch {
            controller.state.first { it.ready }
            lifecycle.withResumed {
                if (!reopenActive || controller.state.value.activeId == null) requestMicrophone(id)
            }
        }
    }
}

@Composable
private fun RecordingScreen(
    controller: RecordingController,
    onBack: () -> Unit,
    onBegin: (String?) -> Unit,
    onFinish: () -> Unit,
    onDiscard: () -> Unit,
    incomingDocument: String?,
    incomingRevision: Int,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val asr by controller.asrModel.collectAsStateWithLifecycle()
    val hotwords by controller.hotwords.collectAsStateWithLifecycle()
    var showHotwords by rememberSaveable { mutableStateOf(false) }
    var showVoice by rememberSaveable { mutableStateOf(false) }
    var showSetup by rememberSaveable { mutableStateOf(false) }
    var speakerId by rememberSaveable { mutableStateOf<Int?>(null) }
    var speakerSegment by rememberSaveable { mutableStateOf<Long?>(null) }
    val speakerModel by controller.speakerModel.collectAsStateWithLifecycle()
    var selected by rememberSaveable(incomingDocument, incomingRevision) { mutableStateOf(incomingDocument) }
    var historyParent by rememberSaveable(incomingDocument, incomingRevision) { mutableStateOf(incomingDocument == null) }
    var organized by rememberSaveable(incomingRevision) { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var defaultMode by remember { mutableStateOf(controller.store.mode) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editorText by rememberSaveable { mutableStateOf("") }
    var pendingLanguage by rememberSaveable { mutableStateOf<RecordingLanguage?>(null) }
    var leaveToHistory by rememberSaveable { mutableStateOf(false) }
    val current = state.sessions.firstOrNull { it.id == selected }
    val active = current != null && state.activeId == current.id
    val paused = current?.phase == RecordingPhase.Paused
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val id = exportId
        exportId = null
        if (id != null && uri != null) controller.exportRaw(id, uri)
    }

    LaunchedEffect(state.lastDiscardedId) {
        if (state.lastDiscardedId == selected && state.sessions.none { it.id == selected }) selected = null
    }
    LaunchedEffect(state.activeId) {
        if (state.activeId != null) {
            selected = state.activeId
            organized = false
            editing = false
            // 删除确认只针对打开它时的那条录音；开始新录音后不再保留。
            if (dialog == "delete" || dialog == "recognize") dialog = null
        }
    }
    val route = chooseRecordingRoute(current?.mode ?: defaultMode, state.cloudKey, current?.cloudConsent, state.localReady, current?.fallback ?: controller.store.fallback)
    val playback = remember(current?.id, active) { RecordingPlayback() }
    var playbackEnvelope by remember(current?.id, current?.audioBytes, active) { mutableStateOf(emptyList<Float>()) }
    LaunchedEffect(current?.id, current?.audioBytes, active, current?.captureRollbackPending) {
        val item = current?.takeUnless { active || it.captureRollbackPending } ?: return@LaunchedEffect
        playbackEnvelope = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { controller.store.playbackEnvelope(item.id, item.audioBytes) }
            catch (_: java.io.IOException) { emptyList() }
        }
    }
    DisposableEffect(playback) { onDispose { playback.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { playback.release() }
    val play: (Long) -> Unit = { time ->
        current?.takeUnless { it.captureRollbackPending }?.let { item ->
            if (!playback.play(controller.store.audio(item.id), time) {
                controller.report(R.string.recording_play_failed)
            }) controller.report(R.string.recording_play_failed)
        }
    }

    LaunchedEffect(playback, playback.playing) {
        while (playback.playing) {
            kotlinx.coroutines.delay(250)
            playback.refreshPosition()
        }
    }
    val showHistory = {
        selected = null
        historyParent = true
        organized = false
    }
    val backToHistory = current != null && (active || historyParent)
    val backAction: () -> Unit = {
        if (editing && current != null && editorText != current.organized) {
            leaveToHistory = true
            dialog = "unsaved"
        } else {
            editing = false
            if (backToHistory) showHistory() else onBack()
        }
    }
    BackHandler(enabled = backToHistory || editing, onBack = backAction)
    if (showSetup) {
        RecordingSetupPage(onFinish = { showSetup = false; dialog = "mode" }, firstRun = false)
        return
    }
    val closeHotwords = { showHotwords = false; dialog = "mode" }
    BackHandler(enabled = showHotwords, onBack = closeHotwords)
    if (showHotwords) {
        RecordingHotwordLibrary(controller, onBack = closeHotwords)
        return
    }
    val closeVoice = { showVoice = false; dialog = "mode" }
    BackHandler(enabled = showVoice, onBack = closeVoice)
    if (showVoice) {
        RecordingVoiceLibrary(controller, onBack = closeVoice)
        return
    }
    val effectiveHotwords = remember(current?.hotwords, current?.useHotwordLibrary, hotwords) {
        current?.let { effectiveRecordingHotwords(it, hotwords) }.orEmpty()
    }
    val canRecognize = current != null && recordingCanRecognize(current, state.activeId, state.recognizingId) &&
        asr == RecordingAsrModelState.Installed
    val exportRaw: () -> Unit = {
        current?.let {
            exportId = it.id
            export.launch("recording-${it.id.take(8)}.txt")
        }
    }
    RecordingPageContent(
        state = state,
        current = current,
        organized = organized,
        route = route,
        playing = playback.playing,
        positionMs = playback.position,
        playbackEnvelope = playbackEnvelope,
        onBack = backAction,
        onHistory = showHistory,
        onMode = { dialog = "mode" },
        onSelect = {
            selected = it.id
            historyParent = true
            organized = false
        },
        onOrganizedChange = { if (!editing) organized = it },
        onEdit = { current?.let { editorText = it.organized; editing = true; organized = true } },
        onRecordMenu = { dialog = "actions" },
        onRename = { dialog = "rename" },
        onLanguage = { pendingLanguage = null; dialog = "language" },
        onDiscard = { dialog = "discard" },
        onReturnActive = { selected = state.activeId; organized = false },
        editor = if (!editing || current == null) null else RecordingInlineEditor(editorText, { editorText = it },
            onSave = {
                if (editorText != current.organized) {
                    controller.updateDraft(current.id, current.title, current.hotwords, current.useHotwordLibrary, editorText)
                }
                editing = false
            },
            onCancel = {
                if (editorText == current.organized) editing = false
                else { leaveToHistory = false; dialog = "unsaved" }
            }),
        onConsent = { dialog = "consent" },
        onTranscriptMenu = { dialog = "transcript-actions" },
        onPlay = play,
        onTogglePlayback = {
            if (playback.playing) playback.pause() else play(playback.position.toLong())
        },
        onSeek = { playback.position = it },
        onSeekFinished = { play(playback.position.toLong()) },
        onBegin = { onBegin(null) },
        onPauseResume = { current?.let { if (paused) onBegin(it.id) else controller.pause() } },
        onFinish = { dialog = "finish" },
        onContinue = { dialog = "continue" },
        onOrganize = { dialog = "organize" },
        onDismissFailure = controller::dismissFailure,
        asrModel = asr,
        effectiveHotwords = effectiveHotwords,
        onDownloadAsr = controller::downloadAsrModel,
        onCancelDownload = controller::cancelAsrModelDownload,
        onRecognize = {
            if (canRecognize && current != null) {
                dialog = "recognize"
            }
        },
        onCancelRecognize = controller::cancelRecognize,
        speakerModel = speakerModel,
        onAnalyzeSpeakers = { current?.let { controller.analyzeSpeakers(it.id) } },
        onSpeakerSettings = { dialog = "mode" },
        onSpeakerSelect = { speakerId = it.id; speakerSegment = it.segmentStartMs; dialog = "speaker" },
    )

    when (dialog) {
        "mode" -> InfinityBottomSheet(
            show = true,
            onDismiss = { dialog = null },
            title = stringResource(R.string.recording_settings),
            height = InfinitySheetHeight.Content,
            scrollContent = true,
            dragGesturesEnabled = false,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large)) {
                RecordingWorkspaceSettings(current?.mode ?: defaultMode, current?.outputLanguage ?: controller.store.outputLanguage,
                    current?.liveOrganization ?: controller.store.liveOrganization,
                    current?.distinguishSpeakers ?: controller.store.distinguishSpeakers,
                    single = current != null, onSave = { mode, language, live, speakers ->
                        if (current == null) defaultMode = mode
                        controller.configure(current?.id, mode, language, live, speakers)
                        dialog = null
                    })
                if (current?.cloudConsent != null) TextButton(onClick = {
                    controller.allowCloud(current.id, null); dialog = null
                }) { Text(stringResource(R.string.recording_revoke)) }
                BettboxListGroup {
                    InfinitySettingsItem(title = stringResource(R.string.recording_setup_title), showChevron = true, onClick = {
                        dialog = null; showSetup = true
                    })
                }
                BettboxListGroup {
                    InfinitySettingsItem(title = stringResource(R.string.recording_hotword_library), showChevron = true, onClick = {
                        dialog = null; showHotwords = true
                    })
                    InfinitySettingsItem(title = stringResource(R.string.recording_speaker_library), showChevron = true, onClick = {
                        dialog = null; showVoice = true
                    })
                }
            }
        }

        "speaker" -> if (current != null) {
            speakerId?.let { id ->
                RecordingSpeakerSheet(current, RecordingSpeakerSelection(id, speakerSegment), controller) { dialog = null }
            }
        }
        "finish" -> RecordingConfirm(R.string.recording_finish_question, stringResource(R.string.recording_finish_detail),
            R.string.recording_finish_save, { dialog = null }, { dialog = null; organized = true; onFinish() })
        "continue" -> if (current != null && !active) {
            RecordingConfirm(R.string.recording_resume, stringResource(R.string.recording_append_detail, current.title),
                R.string.recording_append_start, { dialog = null }, { dialog = null; onBegin(current.id) })
        }
        "discard" -> if (current != null && active) {
            val detail = stringResource(if (state.appending) R.string.recording_discard_append_detail else R.string.recording_discard_detail)
            InfinityConfirmDialog(
                title = stringResource(if (state.appending) R.string.recording_discard_append_question else R.string.recording_discard_question),
                message = if (current.cloudConsent == null) detail else "$detail\n\n${stringResource(R.string.recording_discard_cloud)}",
                confirmText = stringResource(if (state.appending) R.string.recording_discard_append else R.string.recording_discard_all),
                cancelText = stringResource(R.string.recording_return_active), isDestructive = true,
                onConfirm = { dialog = null; organized = false; onDiscard() }, onCancel = { dialog = null },
            )
        }
        "rename" -> if (current != null) {
            com.promenar.infinity.ui.chat.RenameDialog(current.title, { dialog = null }) { title ->
                controller.updateDraft(current.id, title, current.hotwords, current.useHotwordLibrary, null)
                dialog = null
            }
        }
        "unsaved" -> RecordingConfirm(R.string.recording_discard_edit_question, stringResource(R.string.recording_discard_edit_detail),
            R.string.recording_discard, { dialog = null }, {
                dialog = null; editing = false
                if (leaveToHistory) { if (backToHistory) showHistory() else onBack() }
            })
        "actions" -> if (current != null) InfinityBottomSheet(show = true, onDismiss = { dialog = null },
            title = stringResource(R.string.recording_actions), height = InfinitySheetHeight.Content) {
            BettboxListGroup {
                InfinitySettingsItem(title = stringResource(R.string.recording_settings), onClick = { dialog = "mode" })
                InfinitySettingsItem(title = stringResource(R.string.recording_hotword_session), onClick = { dialog = "hotwords" })
                if (state.ready && !active && current.segments.isNotEmpty()) {
                    InfinitySettingsItem(title = stringResource(R.string.recording_ui_reprocess), onClick = { dialog = "organize" })
                }
                if (active && route == RecordingRoute.Consent) {
                    InfinitySettingsItem(title = stringResource(R.string.recording_enable_cloud), onClick = { dialog = "consent" })
                }
                if (!active) InfinitySettingsItem(title = stringResource(R.string.recording_ui_delete), onClick = { dialog = "delete" })
                if (!active && current.documentId == null) InfinitySettingsItem(title = stringResource(R.string.recording_save_library), onClick = {
                    controller.publish(current.id); dialog = null
                })
            }
        }
        "language" -> if (current != null) InfinityBottomSheet(show = true, onDismiss = { dialog = null },
            title = stringResource(R.string.recording_output_language), height = InfinitySheetHeight.Content) {
            Text(stringResource(R.string.recording_language_detail), style = MaterialTheme.typography.bodySmall)
            BettboxListGroup {
                RecordingLanguage.entries.forEach { language ->
                    InfinitySettingsItem(title = language.label, selected = language == current.outputLanguage, onClick = {
                        if (language == current.outputLanguage) dialog = null else {
                            controller.selectLanguage(current.id, language)
                            pendingLanguage = language
                            dialog = if (!active && current.organized.isNotBlank()) "organize" else null
                        }
                    })
                }
            }
        }
        "consent" -> {
            val key = state.cloudKey
            val name = state.cloudLabel
            if (current != null && key != null && name != null) {
                RecordingConfirm(R.string.recording_enable_cloud, stringResource(R.string.recording_consent_detail, name),
                    R.string.recording_allow, { dialog = null }, { dialog = null; controller.allowCloud(current.id, key) })
            }
        }
        "organize" -> if (current != null) {
            val language = pendingLanguage ?: current.outputLanguage
            RecordingConfirm(R.string.recording_ui_reprocess, stringResource(R.string.recording_reorganize_language, language.label),
                R.string.recording_ui_reprocess, { dialog = null }, {
                    dialog = if (route == RecordingRoute.Consent) "consent" else null
                    if (route == RecordingRoute.Local || route == RecordingRoute.Cloud || route == RecordingRoute.Consent) {
                        controller.retry(current.id, language)
                        pendingLanguage = null
                        organized = true
                    } else controller.report(if (route == RecordingRoute.Raw) R.string.recording_mode_raw else R.string.recording_route_unavailable)
                })
        }
        "delete" -> if (current != null && !active) {
            InfinityConfirmDialog(
                title = stringResource(R.string.recording_ui_delete),
                message = stringResource(
                    if (current.documentId == null) R.string.recording_ui_delete_detail
                    else R.string.recording_ui_delete_detail_document,
                ),
                confirmText = stringResource(R.string.recording_ui_delete_confirm),
                cancelText = stringResource(R.string.recording_cancel),
                onConfirm = {
                    dialog = null
                    showHistory()
                    controller.delete(current.id)
                },
                onCancel = { dialog = null },
                isDestructive = true,
            )
        }
        "transcript-actions" -> if (current != null) InfinityBottomSheet(show = true, onDismiss = { dialog = null },
            title = stringResource(R.string.recording_transcript_actions), height = InfinitySheetHeight.Content) {
            BettboxListGroup {
                InfinitySettingsItem(title = stringResource(R.string.recording_recognize), enabled = canRecognize,
                    onClick = { dialog = "recognize" })
                InfinitySettingsItem(title = stringResource(R.string.recording_hotword_session), onClick = { dialog = "hotwords" })
                InfinitySettingsItem(title = stringResource(R.string.recording_export_raw), enabled = current.segments.isNotEmpty(),
                    onClick = { dialog = null; exportRaw() })
            }
        }
        "recognize" -> if (current != null && canRecognize) {
            InfinityBottomSheet(
                show = true,
                onDismiss = { dialog = null },
                title = stringResource(R.string.recording_recognize),
                scrollContent = true,
                dragGesturesEnabled = false,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                    InfinityPillButton(
                        stringResource(R.string.recording_recognize_raw_only),
                        { dialog = null; controller.recognize(current.id, rewriteOrganized = false) },
                        modifier = Modifier.fillMaxWidth(),
                        tone = InfinityPillTone.PRIMARY,
                    )
                    if (route != RecordingRoute.Raw) {
                        InfinityPillButton(
                            stringResource(R.string.recording_recognize_rewrite),
                            { dialog = null; controller.recognize(current.id, rewriteOrganized = true) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    InfinityPillButton(stringResource(R.string.recording_cancel), { dialog = null }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        "hotwords" -> if (current != null) RecordingSessionHotwords(current, { dialog = null }) { words, useLibrary ->
            controller.updateDraft(current.id, current.title, words, useLibrary, null)
            dialog = null
        }
    }
}

@Composable
private fun RecordingConfirm(title: Int, details: String, confirm: Int, dismiss: () -> Unit, action: () -> Unit) {
    InfinityConfirmDialog(
        title = stringResource(title),
        message = details,
        confirmText = stringResource(confirm),
        cancelText = stringResource(R.string.recording_cancel),
        onConfirm = action,
        onCancel = dismiss,
    )
}

@Composable
private fun RecordingSessionHotwords(item: RecordingSession, dismiss: () -> Unit, save: (List<String>, Boolean) -> Unit) {
    var words by rememberSaveable(item.id) { mutableStateOf(item.hotwords.joinToString("\n")) }
    var useLibrary by rememberSaveable(item.id) { mutableStateOf(item.useHotwordLibrary) }
    InfinityBottomSheet(show = true, onDismiss = dismiss, title = stringResource(R.string.recording_hotword_session),
        height = InfinitySheetHeight.Content, scrollContent = true) {
        Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
            RowToggle(stringResource(R.string.recording_hotword_use_library), useLibrary) { useLibrary = it }
            OutlinedTextField(words, { words = it }, Modifier.fillMaxWidth(), minLines = 4,
                label = { Text(stringResource(R.string.recording_hotword_words)) }, textStyle = MaterialTheme.typography.bodyMedium.forInput())
            InfinityPillButton(stringResource(R.string.recording_save), { save(words.lines(), useLibrary) },
                modifier = Modifier.fillMaxWidth(), tone = InfinityPillTone.PRIMARY)
        }
    }
}

@Composable
private fun RowToggle(label: String, value: Boolean, update: (Boolean) -> Unit) {
    InfinitySettingsItem(title = label, onClick = { update(!value) }, trailingContent = {
        Switch(checked = value, onCheckedChange = null)
    })
}

@Composable
private fun RecordingWorkspaceSettings(
    mode: RecordingMode, language: RecordingLanguage, live: Boolean, speakers: Boolean, single: Boolean,
    onSave: (RecordingMode, RecordingLanguage, Boolean, Boolean) -> Unit,
) {
    var route by rememberSaveable { mutableStateOf(mode) }
    var output by rememberSaveable { mutableStateOf(language) }
    var draft by rememberSaveable { mutableStateOf(live) }
    var separate by rememberSaveable { mutableStateOf(speakers) }
    val app = LocalContext.current.applicationContext as InfinityApplication
    BettboxListGroup {
        Text(stringResource(R.string.recording_route_title), style = MaterialTheme.typography.labelLarge)
        RecordingMode.entries.filter { it != RecordingMode.Local || app.localInferenceRuntimeGate.isAvailable }.forEach { option ->
            val label = when (option) {
                RecordingMode.Auto -> R.string.recording_mode_auto
                RecordingMode.Local -> R.string.recording_mode_local
                RecordingMode.Raw -> R.string.recording_mode_raw
            }
            InfinitySettingsItem(title = stringResource(label), selected = route == option, onClick = { route = option })
        }
    }
    BettboxListGroup {
        Text(stringResource(if (single) R.string.recording_output_language else R.string.recording_default_language),
            style = MaterialTheme.typography.labelLarge)
        RecordingLanguage.entries.forEach { option ->
            InfinitySettingsItem(title = option.label, selected = output == option, onClick = { output = option })
        }
        RowToggle(stringResource(R.string.recording_live_setting), draft) { draft = it }
        RowToggle(stringResource(R.string.recording_speaker_distinguish), separate) { separate = it }
    }
    InfinityPillButton(stringResource(R.string.recording_save), { onSave(route, output, draft, separate) },
        modifier = Modifier.fillMaxWidth(), tone = InfinityPillTone.PRIMARY)
}

private class RecordingPlayback {
    private var player: MediaPlayer? = null
    var playing by mutableStateOf(false)
        private set
    var position by mutableFloatStateOf(0f)

    fun refreshPosition() {
        runCatching { player?.currentPosition?.toFloat() }.getOrNull()?.let { position = it }
    }

    fun play(file: File, time: Long, onError: () -> Unit): Boolean {
        release()
        position = time.toFloat()
        val next = MediaPlayer()
        player = next
        return runCatching {
            file.inputStream().use { next.setDataSource(it.fd) }
            next.setOnPreparedListener {
                if (player === it) {
                    it.seekTo(time, MediaPlayer.SEEK_CLOSEST)
                    it.start()
                    playing = true
                }
            }
            next.setOnCompletionListener { release(); position = 0f }
            next.setOnErrorListener { _, _, _ -> release(); onError(); true }
            next.prepareAsync()
        }.onFailure { release() }.isSuccess
    }

    fun pause() { player?.pause(); refreshPosition(); playing = false }

    fun release() {
        player?.release()
        player = null
        playing = false
    }
}

internal fun routeLabel(route: RecordingRoute): Int = when (route) {
    RecordingRoute.Cloud -> R.string.recording_route_cloud
    RecordingRoute.Consent -> R.string.recording_route_consent
    RecordingRoute.Local -> R.string.recording_route_local
    RecordingRoute.Raw -> R.string.recording_mode_raw
    RecordingRoute.Unavailable -> R.string.recording_route_unavailable
}

@Composable
internal fun LibraryRecordingPlayback(documentId: String) {
    val context = LocalContext.current
    val controller = (context.applicationContext as? InfinityApplication)?.recordings ?: return
    val state by controller.state.collectAsStateWithLifecycle()
    if (state.sessions.none { it.id == documentId && it.durationMs > 0 }) return
    IconButton(onClick = {
        context.startActivity(recordingIntent(context).putExtra("viewSession", documentId))
    }) {
        Icon(Icons.Rounded.PlayArrow, stringResource(R.string.recording_play))
    }
}
