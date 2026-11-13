package com.promenar.infinity.recording

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.*
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

private const val VoiceSeconds = 15

@Composable
internal fun RecordingVoiceLibrary(controller: RecordingController, onBack: () -> Unit) {
    val people by controller.voicePeople.collectAsStateWithLifecycle()
    val model by controller.speakerModel.collectAsStateWithLifecycle()
    val state by controller.state.collectAsStateWithLifecycle()
    var personId by rememberSaveable { mutableStateOf<String?>(null) }
    var enrolling by rememberSaveable { mutableStateOf(false) }
    val person = people.firstOrNull { it.id == personId }
    if (enrolling) {
        RecordingVoiceEnrollment(controller) { enrolling = false }
        return
    }
    RecordingVoiceLibraryContent(
        people, model, state.ready && state.activeId == null, onBack,
        { personId = it.id }, { enrolling = true }, controller::downloadSpeakerModel, controller::cancelSpeakerModel,
    )
    if (person != null) RecordingVoicePersonSheet(person, controller) { personId = null }
}

@Composable
internal fun RecordingVoiceLibraryContent(
    people: List<RecordingVoicePerson>,
    model: RecordingAsrModelState,
    ready: Boolean,
    onBack: () -> Unit,
    onPerson: (RecordingVoicePerson) -> Unit,
    onEnroll: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    InfinitySettingsPageLayout(title = stringResource(R.string.recording_speaker_library), onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.weight(1f)) {
                item {
                    BettboxListGroup {
                        VoiceInfo(R.string.recording_voice_storage, R.string.recording_voice_local)
                        VoiceInfo(R.string.recording_voice_backup, R.string.recording_voice_encrypted_backup)
                        if (model != RecordingAsrModelState.Installed) {
                            RecordingAsrControls(model, onDownload, onCancelDownload, inTranscript = true, speaker = true)
                        }
                    }
                }
                item {
                    InfinitySettingsSection(
                        title = stringResource(R.string.recording_voice_people),
                        modifier = Modifier.padding(top = InfinitySpacing.Large),
                    ) {
                        if (people.isEmpty()) Text(
                            stringResource(R.string.recording_voice_empty),
                            Modifier.padding(InfinitySpacing.RowHorizontal),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = InfinityTypography.bodyMedium,
                        )
                        people.forEach { person ->
                            val latest = person.samples.maxOfOrNull { it.createdAt }
                            InfinitySettingsItem(
                                title = person.name,
                                subtitleSegments = listOfNotNull(
                                    pluralStringResource(R.plurals.recording_voice_sample_count, person.samples.size, person.samples.size),
                                    latest?.let { stringResource(R.string.recording_voice_recent, voiceDate(it)) },
                                ),
                                subtitleMaxLines = 2,
                                leadingContent = { RecordingVoiceAvatar(person.name) },
                                onClick = { onPerson(person) }, showChevron = true,
                            )
                        }
                    }
                }
            }
            InfinityPillButton(
                stringResource(R.string.recording_voice_enroll), onEnroll,
                modifier = Modifier.fillMaxWidth().padding(top = InfinitySpacing.Medium),
                enabled = ready && model == RecordingAsrModelState.Installed,
                icon = Icons.Rounded.Mic, tone = InfinityPillTone.PRIMARY,
            )
        }
    }
}

@Composable
private fun VoiceInfo(label: Int, value: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Medium),
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), Modifier.weight(1f), style = InfinityTypography.bodyMedium)
        Text(stringResource(value), color = MaterialTheme.colorScheme.onSurfaceVariant, style = InfinityTypography.bodySmall)
    }
}

@Composable
internal fun RecordingVoiceAvatar(name: String, size: androidx.compose.ui.unit.Dp = InfinitySpacing.RowIconSize) {
    val tint = MaterialTheme.colorScheme.secondary
    Box(Modifier.size(size).background(tint.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
        Text(name.codePoints().limit(1).toArray().let { String(it, 0, it.size) },
            color = tint, style = InfinityTypography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun RecordingVoicePersonSheet(person: RecordingVoicePerson, controller: RecordingController, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(person.id) { mutableStateOf(person.name) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun change(action: suspend () -> Unit, close: Boolean) {
        scope.launch {
            pending = true
            failed = false
            try {
                action()
                if (close) onClose()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failed = true
            } finally {
                pending = false
            }
        }
    }
    InfinityBottomSheet(
        show = true, onDismiss = onClose, dismissEnabled = !pending,
        scrollContent = true, dragGesturesEnabled = false,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
                RecordingVoiceAvatar(person.name, size = 36.dp)
                Text(person.name, style = InfinityTypography.titleMedium)
            }
            SettingsInput(name, { name = it.take(50) }, stringResource(R.string.recording_speaker_name))
            Text(stringResource(R.string.recording_voice_samples), style = InfinityTypography.titleSmall)
            BettboxListGroup {
                person.samples.forEach { sample ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small),
                        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(sample.source, style = InfinityTypography.bodyMedium)
                            InfinityMetaLine(listOf(voiceDate(sample.createdAt), recordingTimestamp(sample.durationMs)),
                                style = InfinityTypography.labelSmall)
                        }
                        if (person.samples.size > 1) InfinityPillButton(
                            stringResource(R.string.recording_voice_remove_sample),
                            { change({ controller.removeVoiceSample(person.id, sample.id) }, close = false) },
                            enabled = !pending, compact = true, tone = InfinityPillTone.DANGER,
                        )
                    }
                }
            }
            if (failed) VoiceFailure()
            if (confirm) {
                Text(stringResource(R.string.recording_voice_remove_confirm, person.name), style = InfinityTypography.bodyMedium)
                InfinityPillRow {
                    InfinityPillButton(stringResource(R.string.recording_cancel), { confirm = false })
                    InfinityPillButton(stringResource(R.string.recording_ui_delete_confirm), {
                        confirm = false
                        change({ controller.deleteVoicePerson(person.id) }, close = true)
                    }, enabled = !pending, tone = InfinityPillTone.DESTRUCTIVE)
                }
            } else InfinityPillRow {
                InfinityPillButton(stringResource(R.string.recording_voice_remove_person), { confirm = true },
                    enabled = !pending, tone = InfinityPillTone.DANGER)
                InfinityPillButton(stringResource(R.string.recording_save),
                    { change({ controller.renameVoicePerson(person.id, name.trim()) }, close = true) },
                    enabled = !pending && name.isNotBlank(), tone = InfinityPillTone.PRIMARY)
            }
        }
    }
}

@Composable
private fun RecordingVoiceEnrollment(controller: RecordingController, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var capture by remember { mutableStateOf<Job?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var sample by remember { mutableStateOf<FloatArray?>(null) }
    var elapsed by remember { mutableIntStateOf(0) }
    var levels by remember { mutableStateOf(List(32) { 0f }) }
    var failed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    fun begin() {
        capture?.cancel()
        elapsed = 0
        sample = null
        levels = List(32) { 0f }
        failed = false
        capture = scope.launch {
            try {
                sample = controller.useVoiceMicrophone {
                    captureVoiceprint { milliseconds, peak ->
                        withContext(Dispatchers.Main) {
                            elapsed = milliseconds
                            levels = levels.drop(1) + peak
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failed = true
            } finally {
                capture = null
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else failed = true
    }
    fun request() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { capture?.cancel() }
    BackHandler { if (!saving) onClose() }
    val back: (() -> Unit)? = if (saving) null else onClose
    InfinitySettingsPageLayout(title = stringResource(R.string.recording_voice_enroll), onBack = back) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
            ) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = InfinitySpacing.XLarge),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
                ) {
                    Row(
                        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(recordingTimestamp(elapsed.toLong()), Modifier.alignByBaseline(),
                            style = InfinityTypography.headlineLarge.copy(fontFamily = InfinityMonospace, fontSize = 42.sp))
                        Text("/ ${recordingTimestamp(VoiceSeconds * 1_000L)}", Modifier.alignByBaseline(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = InfinityTypography.titleMedium.copy(fontFamily = InfinityMonospace))
                    }
                    val tint = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
                        val step = size.width / levels.size
                        levels.forEachIndexed { index, level ->
                            val x = (index + .5f) * step
                            val amplitude = size.height * (.06f + level.coerceIn(0f, 1f) * .42f)
                            drawLine(tint, Offset(x, center.y - amplitude), Offset(x, center.y + amplitude),
                                strokeWidth = minOf(step * .55f, 3.dp.toPx()), cap = StrokeCap.Round)
                        }
                    }
                    BettboxListGroup {
                        Text(stringResource(R.string.recording_voice_reading),
                            Modifier.padding(InfinitySpacing.Large), style = InfinityTypography.bodyLarge)
                    }
                    if (sample != null) {
                        val focus = remember { FocusRequester() }
                        val keyboard = LocalSoftwareKeyboardController.current
                        SettingsInput(name, { name = it.take(50) }, stringResource(R.string.recording_speaker_name),
                            modifier = Modifier.focusRequester(focus))
                        LaunchedEffect(Unit) {
                            focus.requestFocus()
                            keyboard?.show()
                        }
                    }
                    if (failed) VoiceFailure()
                }
                InfinityPillRow {
                    InfinityPillButton(
                        stringResource(if (sample == null && capture == null) R.string.recording_voice_begin else R.string.recording_voice_record_again),
                        {
                            if (capture != null) {
                                capture?.cancel()
                                elapsed = 0
                                levels = List(32) { 0f }
                            } else request()
                        }, enabled = !saving, icon = Icons.Rounded.Mic,
                        tone = if (sample == null && capture == null) InfinityPillTone.PRIMARY else InfinityPillTone.NEUTRAL,
                    )
                    if (sample != null) InfinityPillButton(stringResource(R.string.recording_save), {
                        val input = sample ?: return@InfinityPillButton
                        scope.launch {
                            saving = true
                            failed = false
                            try {
                                val voice = controller.enrollVoice(input)
                                controller.saveVoicePerson(RecordingVoicePerson(name = name.trim(), samples = listOf(voice)))
                                onClose()
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                failed = true
                            } finally {
                                saving = false
                            }
                        }
                    }, enabled = name.isNotBlank() && !saving, tone = InfinityPillTone.PRIMARY)
                }
            }
        }
    }
}

@Composable
private fun VoiceFailure() {
    Text(stringResource(R.string.recording_voice_sample_failed),
        color = MaterialTheme.colorScheme.error, style = InfinityTypography.bodySmall)
}

private fun voiceDate(time: Long): String = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(time))

@SuppressLint("MissingPermission")
private suspend fun captureVoiceprint(onProgress: suspend (Int, Float) -> Unit): FloatArray = withContext(Dispatchers.IO) {
    val rate = RecordingCapturePolicy.SAMPLE_RATE
    val bufferSize = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    require(bufferSize > 0)
    val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2)
    try {
        check(recorder.state == AudioRecord.STATE_INITIALIZED)
        recorder.startRecording()
        check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        val input = FloatArray(rate * VoiceSeconds)
        val buffer = ShortArray(rate / 20)
        var cursor = 0
        while (cursor < input.size) {
            ensureActive()
            val length = recorder.read(buffer, 0, minOf(buffer.size, input.size - cursor), AudioRecord.READ_BLOCKING)
            check(length > 0)
            var peak = 0f
            for (index in 0 until length) {
                val value = buffer[index] / 32768f
                input[cursor++] = value
                peak = maxOf(peak, abs(value))
            }
            onProgress(cursor * 1_000 / rate, peak)
        }
        input
    } finally {
        if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) runCatching(recorder::stop)
        recorder.release()
    }
}
