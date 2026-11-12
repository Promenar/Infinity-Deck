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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityTabHeader
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
    FilledTonalButton(onClick = { context.startActivity(recordingIntent(context, autoStart = true)) }) {
        Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(InfinitySpacing.XSmall))
        Text(stringResource(R.string.recording_entry), maxLines = 1)
    }
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

    Scaffold(
        topBar = {
            InfinityTabHeader(title = stringResource(R.string.recording_title)) {
                if (!active && current != null) {
                    IconButton(onClick = { selected = null }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.recording_history))
                    }
                }
                IconButton(onClick = { dialog = "mode" }) {
                    Icon(Icons.Rounded.Settings, stringResource(R.string.recording_settings))
                }
                TextButton(onClick = onBack) { Text(stringResource(R.string.recording_close)) }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(InfinitySpacing.ScreenHorizontal),
                    horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                ) {
                    when {
                        current == null -> Button(enabled = state.ready, onClick = { onBegin(null) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.recording_start))
                        }
                        active -> {
                            FilledTonalButton(onClick = { if (paused) onBegin(current.id) else controller.pause() },
                                modifier = Modifier.weight(1f)) {
                                Text(stringResource(if (paused) R.string.recording_resume else R.string.recording_pause))
                            }
                            Button(onClick = { dialog = "finish" }, modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError)) {
                                Text(stringResource(R.string.recording_finish))
                            }
                        }
                        else -> {
                            if (current.phase == RecordingPhase.Interrupted) {
                                FilledTonalButton(onClick = { onBegin(current.id) }, modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.recording_resume))
                                }
                            } else {
                                FilledTonalButton(onClick = { dialog = "organize" }, modifier = Modifier.weight(1f),
                                    enabled = current.segments.isNotEmpty()) {
                                    Text(stringResource(R.string.recording_reorganize))
                                }
                            }
                            Button(onClick = { controller.publish(current.id) }, modifier = Modifier.weight(1f)) {
                                Text(stringResource(if (current.documentId == null) R.string.recording_save_library else R.string.recording_update_library))
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = InfinitySpacing.ScreenHorizontal)) {
            state.failure?.let { Text(stringResource(it), Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
            if (current == null) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text(stringResource(R.string.recording_ready), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.recording_local_audio), modifier = Modifier.padding(vertical = 8.dp))
                        FilledTonalButton(onClick = { dialog = "mode" }) { Text(stringResource(routeLabel(route))) }
                    }
                    if (state.sessions.isNotEmpty()) item {
                        Text(stringResource(R.string.recording_history), style = MaterialTheme.typography.titleMedium)
                    }
                    items(state.sessions, key = { it.id }) { item ->
                        OutlinedCard(onClick = { selected = item.id; organized = false }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium)
                                Text(recordingTimestamp(item.durationMs))
                            }
                        }
                    }
                }
            } else {
                OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(current.title, style = MaterialTheme.typography.titleMedium)
                        Text(recordingTimestamp(if (active) state.durationMs else current.durationMs),
                            style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(when {
                            paused -> R.string.recording_paused
                            active && state.opening -> R.string.recording_opening
                            active -> R.string.recording_running
                            current.phase == RecordingPhase.Interrupted -> R.string.recording_interrupted
                            else -> R.string.recording_local_audio
                        }))
                        if (active) RecordingWaveform(state.levels, paused)
                        if (!active && current.durationMs > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    if (playback.playing) playback.pause() else play(playback.position.toLong())
                                }) {
                                    Text(stringResource(if (playback.playing) R.string.recording_pause else R.string.recording_play))
                                }
                                Text(recordingTimestamp(playback.position.toLong()), style = MaterialTheme.typography.bodySmall)
                            }
                            Slider(value = playback.position.coerceIn(0f, current.durationMs.toFloat()),
                                onValueChange = { playback.position = it },
                                onValueChangeFinished = { play(playback.position.toLong()) },
                                valueRange = 0f..current.durationMs.toFloat())
                            LaunchedEffect(playback, playback.playing) {
                                while (playback.playing) {
                                    kotlinx.coroutines.delay(250)
                                    playback.refreshPosition()
                                }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(when {
                        state.organizingId == current.id -> R.string.recording_organizing
                        state.failure == R.string.recording_organize_failed -> R.string.recording_queued
                        else -> routeLabel(route)
                    }), Modifier.weight(1f))
                    if (route == RecordingRoute.Consent) {
                        TextButton(onClick = { dialog = "consent" }) { Text(stringResource(R.string.recording_enable_cloud)) }
                    }
                    TextButton(onClick = { dialog = "edit" }) { Text(stringResource(R.string.recording_edit)) }
                }
                if (!active && current.segments.isNotEmpty()) {
                    TextButton(onClick = {
                        exportId = current.id
                        export.launch("recording-${current.id.take(8)}.txt")
                    }) { Text(stringResource(R.string.recording_export_raw)) }
                }
                if (current.documentId != null) {
                    Text(stringResource(R.string.recording_library_path, current.documentId),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
                }
                TabRow(selectedTabIndex = if (organized) 1 else 0) {
                    Tab(selected = !organized, onClick = { organized = false }, text = { Text(stringResource(R.string.recording_raw)) })
                    Tab(selected = organized, onClick = { organized = true }, text = { Text(stringResource(R.string.recording_organized)) })
                }
                val list = rememberLazyListState()
                LaunchedEffect(current.id, current.segments.size, organized, active) {
                    if (active && !organized && current.segments.isNotEmpty() && !list.isScrollInProgress) {
                        list.scrollToItem(current.segments.size - 1)
                    }
                }
                LazyColumn(state = list, contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (organized) item {
                        Text(current.organized.ifBlank { stringResource(R.string.recording_no_organized) })
                    } else {
                        items(current.segments, key = { it.startMs }) { segment ->
                            Column {
                                if (active) Text(recordingTimestamp(segment.startMs), color = MaterialTheme.colorScheme.primary)
                                else TextButton(onClick = { play(segment.startMs) }) { Text(recordingTimestamp(segment.startMs)) }
                                Text(segment.text, style = MaterialTheme.typography.bodyLarge)
                                HorizontalDivider(Modifier.padding(top = 8.dp))
                            }
                        }
                        if (active) item { Text(state.partial.ifBlank { stringResource(R.string.recording_waiting) }) }
                    }
                }
            }
        }
    }

    when (dialog) {
        "mode" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.recording_settings)) },
            text = {
                Column {
                    RecordingModeOptions(current?.mode ?: defaultMode) { mode ->
                        if (current == null) defaultMode = mode
                        controller.setMode(current?.id, mode)
                        dialog = null
                    }
                    if (current?.cloudConsent != null) {
                        TextButton(onClick = { controller.allowCloud(current.id, null); dialog = null }) {
                            Text(stringResource(R.string.recording_revoke))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.recording_close)) } },
        )
        "finish" -> RecordingConfirm(R.string.recording_finish, stringResource(R.string.recording_finish_detail),
            R.string.recording_finish, { dialog = null }, { dialog = null; onFinish() })
        "consent" -> {
            val key = state.cloudKey
            val name = state.cloudLabel
            if (current != null && key != null && name != null) {
                RecordingConfirm(R.string.recording_enable_cloud, stringResource(R.string.recording_consent_detail, name),
                    R.string.recording_allow, { dialog = null }, { dialog = null; controller.allowCloud(current.id, key) })
            }
        }
        "organize" -> if (current != null) {
            RecordingConfirm(R.string.recording_reorganize, stringResource(R.string.recording_reorganize_detail),
                R.string.recording_reorganize, { dialog = null }, {
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
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(title)) }, text = { Text(details) },
        confirmButton = { TextButton(onClick = action) { Text(stringResource(confirm)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.recording_cancel)) } })
}

@Composable
private fun RecordingEditDialog(item: RecordingSession, dismiss: () -> Unit, save: (String, String, String?) -> Unit) {
    var title by rememberSaveable(item.id) { mutableStateOf(item.title) }
    var glossary by rememberSaveable(item.id) { mutableStateOf(item.glossary) }
    val original = remember(item.id) { item.organized }
    var draft by remember(item.id) { mutableStateOf(original) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.recording_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.recording_document_title)) },
                    singleLine = true)
                OutlinedTextField(glossary, { glossary = it }, label = { Text(stringResource(R.string.recording_glossary)) }, maxLines = 2)
                OutlinedTextField(draft, { draft = it }, label = { Text(stringResource(R.string.recording_organized)) }, maxLines = 5)
            }
        },
        confirmButton = { TextButton(onClick = { save(title, glossary, draft.takeIf { it != original }) }) { Text(stringResource(R.string.recording_save)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.recording_cancel)) } },
    )
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
private fun RecordingWaveform(levels: List<Float>, paused: Boolean) {
    val color = if (paused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error
    Canvas(Modifier.fillMaxWidth().height(32.dp)) {
        val step = size.width / 48
        repeat(48) { index ->
            val level = levels.getOrNull(index - (48 - levels.size)) ?: 0f
            val half = (level * 0.45f + 0.06f) * size.height
            val x = (index + 0.5f) * step
            drawLine(color, Offset(x, center.y - half), Offset(x, center.y + half),
                strokeWidth = minOf(2.dp.toPx(), step * 0.7f), cap = StrokeCap.Round)
        }
    }
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
        Icon(Icons.Rounded.Mic, stringResource(R.string.recording_play))
    }
}
