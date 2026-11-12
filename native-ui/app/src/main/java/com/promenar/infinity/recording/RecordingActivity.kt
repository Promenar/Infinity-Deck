package com.promenar.infinity.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Mic
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
                    incomingDocument = incomingDocument,
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
internal fun LibraryRecordingAction() {
    val context = LocalContext.current
    InfinityPillButton(
        text = stringResource(R.string.recording_entry),
        onClick = { context.startActivity(recordingIntent(context, autoStart = true)) },
        icon = Icons.Rounded.Mic,
        tone = InfinityPillTone.PRIMARY,
    )
}

@Composable
private fun RecordingScreen(
    controller: RecordingController,
    onBack: () -> Unit,
    onBegin: (String?) -> Unit,
    onFinish: () -> Unit,
    incomingDocument: String?,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    var selected by rememberSaveable(incomingDocument) { mutableStateOf(incomingDocument) }
    var organized by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var defaultMode by remember { mutableStateOf(controller.store.mode) }
    val current = state.sessions.firstOrNull { it.id == (state.activeId ?: selected) }
    val active = current != null && state.activeId == current.id
    val paused = current?.phase == RecordingPhase.Paused
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val id = exportId
        exportId = null
        if (id != null && uri != null) controller.exportRaw(id, uri)
    }

    LaunchedEffect(state.activeId) {
        if (state.activeId != null) {
            selected = state.activeId
            organized = false
        }
    }
    val route = chooseRecordingRoute(current?.mode ?: defaultMode, state.cloudKey, current?.cloudConsent, state.localReady, current?.fallback ?: controller.store.fallback)
    val playback = remember(current?.id, active) { RecordingPlayback() }
    DisposableEffect(playback) { onDispose { playback.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { playback.release() }
    val play: (Long) -> Unit = { time ->
        current?.let { item ->
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
    RecordingPageContent(
        state = state,
        current = current,
        organized = organized,
        route = route,
        playing = playback.playing,
        positionMs = playback.position,
        onBack = onBack,
        onHistory = { selected = null },
        onMode = { dialog = "mode" },
        onSelect = { selected = it.id; organized = false },
        onOrganizedChange = { organized = it },
        onEdit = { dialog = "edit" },
        onConsent = { dialog = "consent" },
        onExport = {
            current?.let {
                exportId = it.id
                export.launch("recording-${it.id.take(8)}.txt")
            }
        },
        onPlay = play,
        onTogglePlayback = {
            if (playback.playing) playback.pause() else play(playback.position.toLong())
        },
        onSeek = { playback.position = it },
        onSeekFinished = { play(playback.position.toLong()) },
        onBegin = { onBegin(null) },
        onPauseResume = { current?.let { if (paused) onBegin(it.id) else controller.pause() } },
        onFinish = { dialog = "finish" },
        onResumeInterrupted = { current?.let { onBegin(it.id) } },
        onOrganize = { dialog = "organize" },
        onPublish = { current?.let { controller.publish(it.id) } },
    )

    when (dialog) {
        "mode" -> InfinityBottomSheet(
            show = true,
            onDismiss = { dialog = null },
            title = stringResource(R.string.recording_settings),
            height = InfinitySheetHeight.Content,
        ) {
            Column(Modifier.padding(InfinitySpacing.ScreenHorizontal)) {
                RecordingModeOptions(current?.mode ?: defaultMode) { mode ->
                    if (current == null) defaultMode = mode
                    controller.setMode(current?.id, mode)
                    dialog = null
                }
                if (current?.cloudConsent != null) {
                    InfinityPillButton(
                        text = stringResource(R.string.recording_ui_revoke_cloud),
                        onClick = { controller.allowCloud(current.id, null); dialog = null },
                        modifier = Modifier.fillMaxWidth(),
                        tone = InfinityPillTone.DANGER,
                    )
                }
            }
        }

        "finish" -> RecordingConfirm(R.string.recording_ui_stop, stringResource(R.string.recording_finish_detail),
            R.string.recording_ui_stop, { dialog = null }, { dialog = null; onFinish() })
        "consent" -> {
            val key = state.cloudKey
            val name = state.cloudLabel
            if (current != null && key != null && name != null) {
                RecordingConfirm(R.string.recording_enable_cloud, stringResource(R.string.recording_consent_detail, name),
                    R.string.recording_allow, { dialog = null }, { dialog = null; controller.allowCloud(current.id, key) })
            }
        }
        "organize" -> if (current != null) {
            RecordingConfirm(R.string.recording_ui_reprocess, stringResource(R.string.recording_reorganize_detail),
                R.string.recording_ui_reprocess, { dialog = null }, {
                    dialog = if (route == RecordingRoute.Consent) "consent" else null
                    if (route == RecordingRoute.Local || route == RecordingRoute.Cloud || route == RecordingRoute.Consent) {
                        controller.retry(current.id)
                        organized = true
                    } else controller.report(if (route == RecordingRoute.Raw) R.string.recording_mode_raw else R.string.recording_route_unavailable)
                })
        }
        "edit" -> if (current != null) RecordingEditDialog(current, { dialog = null }) { title, glossary, draft ->
            controller.updateDraft(current.id, title, glossary, draft)
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
private fun RecordingEditDialog(item: RecordingSession, dismiss: () -> Unit, save: (String, String, String?) -> Unit) {
    var title by rememberSaveable(item.id) { mutableStateOf(item.title) }
    var glossary by rememberSaveable(item.id) { mutableStateOf(item.glossary) }
    val original = remember(item.id) { item.organized }
    var draft by remember(item.id) { mutableStateOf(original) }
    InfinityBottomSheet(
        show = true,
        onDismiss = dismiss,
        height = InfinitySheetHeight.Full,
        dragGesturesEnabled = false,
    ) {
        RecordingEditorContent(title, glossary, draft, { title = it }, { glossary = it }, { draft = it },
            onSave = { save(title, glossary, draft.takeIf { it != original }) }, onCancel = dismiss)
    }
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
