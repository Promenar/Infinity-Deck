package com.promenar.infinity.recording

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.local.inference.RecordingModelPackage
import com.promenar.infinity.ui.common.InfinityPageLayout
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.theme.InfinityTypography

private data class SetupModel(
    val title: Int, val detail: Int, val bytes: Long, val installed: Boolean, val downloaded: Long,
    val selected: Boolean, val toggle: (Boolean) -> Unit,
)

@Composable
internal fun RecordingSetupPage(onFinish: () -> Unit, firstRun: Boolean = true) {
    val app = LocalContext.current.applicationContext as InfinityApplication
    val controller = app.recordings
    val asr by controller.asrModel.collectAsStateWithLifecycle()
    val speaker by controller.speakerModel.collectAsStateWithLifecycle()
    val gguf by controller.recommendedModel.state.collectAsStateWithLifecycle()
    val supportsLocal = app.localInferenceRuntimeGate.isAvailable
    var useAsr by rememberSaveable { mutableStateOf(true) }
    var useSpeaker by rememberSaveable { mutableStateOf(false) }
    var useLocal by rememberSaveable { mutableStateOf(false) }
    var started by rememberSaveable { mutableStateOf(false) }
    var paused by rememberSaveable { mutableStateOf(false) }
    val localInstalled = remember(gguf) { supportsLocal && controller.recommendedModel.installed() }
    val running = asr.busy() || speaker.busy() || gguf?.isDownloading == true
    val failed = asr is RecordingAsrModelState.Failed || speaker is RecordingAsrModelState.Failed || gguf?.error != null
    val models = buildList {
        add(SetupModel(R.string.recording_setup_asr, R.string.recording_setup_asr_detail, controller.asrModelDownloadBytes,
            asr == RecordingAsrModelState.Installed, asr.bytes(controller.asrModelDownloadBytes), useAsr) { useAsr = it })
        add(SetupModel(R.string.recording_setup_speakers, R.string.recording_setup_speakers_detail, RecordingSpeakerModelManager.DOWNLOAD_BYTES,
            speaker == RecordingAsrModelState.Installed, speaker.bytes(RecordingSpeakerModelManager.DOWNLOAD_BYTES), useSpeaker) { useSpeaker = it })
        if (supportsLocal) add(SetupModel(R.string.recording_setup_local, R.string.recording_setup_local_detail, RecordingModelPackage.bytes,
            localInstalled, if (localInstalled) RecordingModelPackage.bytes else gguf?.downloadedBytes ?: 0, useLocal) { useLocal = it })
    }
    val selected = models.filter { model -> model.selected || when (model.title) {
        R.string.recording_setup_asr -> asr.busy()
        R.string.recording_setup_speakers -> speaker.busy()
        else -> gguf?.isDownloading == true
    } }
    val pendingBytes = selected.filterNot { it.installed }.sumOf { it.bytes }
    val complete = started && pendingBytes == 0L
    val totalBytes = selected.sumOf { it.bytes }
    val progress = (selected.sumOf { it.downloaded }.toFloat() / totalBytes.coerceAtLeast(1)).coerceIn(0f, 1f)
    val status = when {
        complete -> R.string.recording_setup_total_ready
        running -> R.string.recording_setup_preparing
        paused -> R.string.recording_setup_paused
        failed || started -> R.string.recording_setup_retry
        else -> null
    }
    BackHandler(onBack = onFinish)
    InfinityPageLayout(title = stringResource(if (firstRun) R.string.recording_setup_heading else R.string.recording_setup_title),
        onBack = onFinish, scrollable = false, bodyPadding = PaddingValues()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 510.dp).fillMaxWidth().fillMaxHeight().padding(horizontal = 20.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 17.dp, bottom = 14.dp)) {
                    Surface(shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(Modifier.height(13.dp))
                    Text(stringResource(R.string.recording_setup_title), style = InfinityTypography.headlineSmall)
                    Text(stringResource(R.string.recording_setup_intro), Modifier.padding(top = 6.dp, bottom = 20.dp),
                        style = InfinityTypography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(shape = RoundedCornerShape(17.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column {
                            models.forEachIndexed { index, model ->
                                if (index != 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                val enabled = !model.installed && !running && !paused
                                Row(Modifier.fillMaxWidth().heightIn(min = 69.dp)
                                    .toggleable(model in selected || model.installed, enabled = enabled, role = Role.Checkbox, onValueChange = model.toggle)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Checkbox(checked = model in selected || model.installed, onCheckedChange = null, enabled = enabled)
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(model.title), style = InfinityTypography.titleSmall)
                                        Text(stringResource(model.detail), style = InfinityTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(if (model.installed) stringResource(R.string.recording_setup_ready)
                                        else stringResource(R.string.recording_setup_mb, (model.bytes + 500_000) / 1_000_000),
                                        style = InfinityTypography.labelMedium, maxLines = 1)
                                }
                            }
                        }
                    }
                    Text(stringResource(if (supportsLocal) R.string.recording_setup_privacy else R.string.recording_setup_play),
                        Modifier.padding(vertical = 11.dp), style = InfinityTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (status != null) {
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(status), style = InfinityTypography.titleSmall)
                                if (!complete) {
                                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                    Text(stringResource(R.string.recording_setup_percent, (progress * 100).toInt()), style = InfinityTypography.bodySmall)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onFinish, modifier = Modifier.testTag("recording-setup-skip")) {
                        Text(stringResource(if (!firstRun || running || complete) R.string.recording_setup_enter else R.string.recording_setup_skip))
                    }
                    if (!complete) {
                        if (running) InfinityPillButton(stringResource(R.string.recording_setup_pause), {
                            paused = true
                            if (asr.busy()) { useAsr = true; controller.cancelAsrModelDownload() }
                            if (speaker.busy()) { useSpeaker = true; controller.cancelSpeakerModel() }
                            if (gguf?.isDownloading == true) { useLocal = true; controller.recommendedModel.pause() }
                        }) else InfinityPillButton(
                            text = when {
                                paused || failed -> stringResource(R.string.recording_setup_continue)
                                pendingBytes == 0L -> stringResource(R.string.recording_setup_download_action)
                                else -> stringResource(R.string.recording_setup_download, (pendingBytes + 500_000) / 1_000_000)
                            },
                            onClick = {
                                started = true; paused = false
                                if (useAsr && asr != RecordingAsrModelState.Installed) controller.downloadAsrModel()
                                if (useSpeaker && speaker != RecordingAsrModelState.Installed) controller.downloadSpeakerModel()
                                if (supportsLocal && useLocal && !localInstalled) controller.recommendedModel.start()
                            }, enabled = pendingBytes > 0, tone = InfinityPillTone.PRIMARY,
                        )
                    }
                }
            }
        }
    }
}

private fun RecordingAsrModelState.busy() = this is RecordingAsrModelState.Downloading || this == RecordingAsrModelState.Installing

private fun RecordingAsrModelState.bytes(total: Long) = when (this) {
    is RecordingAsrModelState.Downloading -> downloadedBytes
    RecordingAsrModelState.Installing, RecordingAsrModelState.Installed -> total
    else -> 0L
}
