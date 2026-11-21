package com.promenar.infinity.recording

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.local.inference.RecordingModelPackage
import com.promenar.infinity.ui.common.InfinityPageLayout
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySectionFooterCard
import androidx.compose.ui.draw.alpha
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.rememberLastNonNull

private data class SetupModel(
    val title: Int,
    val detail: Int,
    val bytes: Long,
    val installed: Boolean,
    val downloaded: Long,
    val selected: Boolean,
    val recommended: Boolean = false,
    val toggle: (Boolean) -> Unit,
)

@Composable
internal fun RecordingSetupPage(onFinish: () -> Unit, firstRun: Boolean = true) {
    val app = LocalContext.current.applicationContext as InfinityApplication
    val controller = app.recordings
    val screenState by controller.state.collectAsStateWithLifecycle()
    val asr by controller.asrModel.collectAsStateWithLifecycle()
    val refine by controller.refineModel.collectAsStateWithLifecycle()
    val speaker by controller.speakerModel.collectAsStateWithLifecycle()
    val gguf by controller.recommendedModel.state.collectAsStateWithLifecycle()
    val supportsLocal = app.localInferenceRuntimeGate.isAvailable
    val recommendRefine = screenState.refineCapability != RecordingRefineCapability.STREAMING_ONLY
    var useAsr by rememberSaveable { mutableStateOf(true) }
    var useRefineOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val useRefine = useRefineOverride ?: recommendRefine
    var useSpeaker by rememberSaveable { mutableStateOf(false) }
    var useLocal by rememberSaveable { mutableStateOf(false) }
    var started by rememberSaveable { mutableStateOf(false) }
    var paused by rememberSaveable { mutableStateOf(false) }
    val localInstalled = remember(gguf) { supportsLocal && controller.recommendedModel.installed() }
    val running = asr.busy() || refine.busy() || speaker.busy() || gguf?.isDownloading == true
    val failed = asr is RecordingAsrModelState.Failed ||
        refine is RecordingAsrModelState.Failed ||
        speaker is RecordingAsrModelState.Failed ||
        gguf?.error != null
    val models = buildList {
        add(
            SetupModel(
                title = R.string.recording_setup_asr,
                detail = R.string.recording_setup_asr_detail,
                bytes = controller.asrModelDownloadBytes,
                installed = asr == RecordingAsrModelState.Installed,
                downloaded = asr.bytes(controller.asrModelDownloadBytes),
                selected = useAsr,
            ) { useAsr = it },
        )
        add(
            SetupModel(
                title = R.string.recording_setup_refine,
                detail = R.string.recording_setup_refine_detail,
                bytes = controller.refineModelDownloadBytes,
                installed = refine == RecordingAsrModelState.Installed,
                downloaded = refine.bytes(controller.refineModelDownloadBytes),
                selected = useRefine,
                recommended = recommendRefine,
            ) { useRefineOverride = it },
        )
        add(
            SetupModel(
                title = R.string.recording_setup_speakers,
                detail = R.string.recording_setup_speakers_detail,
                bytes = RecordingSpeakerModelManager.DOWNLOAD_BYTES,
                installed = speaker == RecordingAsrModelState.Installed,
                downloaded = speaker.bytes(RecordingSpeakerModelManager.DOWNLOAD_BYTES),
                selected = useSpeaker,
            ) { useSpeaker = it },
        )
        if (supportsLocal) {
            add(
                SetupModel(
                    title = R.string.recording_setup_local,
                    detail = R.string.recording_setup_local_detail,
                    bytes = RecordingModelPackage.bytes,
                    installed = localInstalled,
                    downloaded = if (localInstalled) RecordingModelPackage.bytes else gguf?.downloadedBytes ?: 0,
                    selected = useLocal,
                ) { useLocal = it },
            )
        }
    }
    val selected = models.filter { model ->
        model.selected || when (model.title) {
            R.string.recording_setup_asr -> asr.busy()
            R.string.recording_setup_refine -> refine.busy()
            R.string.recording_setup_speakers -> speaker.busy()
            else -> gguf?.isDownloading == true
        }
    }
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
    val subtitleMaxLines = if (LocalDensity.current.fontScale > 1f) 2 else 1
    BackHandler(enabled = firstRun, onBack = onFinish)
    InfinityPageLayout(
        title = stringResource(if (firstRun) R.string.recording_setup_heading else R.string.recording_setup_title),
        onBack = onFinish,
        scrollable = false,
        bodyPadding = PaddingValues(),
    ) {
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
                    InfinitySectionFooterCard(
                        text = stringResource(R.string.recording_setup_intro),
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                        maxLines = 3,
                    )
                    Surface(shape = RoundedCornerShape(17.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column {
                            models.forEachIndexed { index, model ->
                                if (index != 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                val enabled = !model.installed && !running && !paused
                                Row(
                                    Modifier.fillMaxWidth()
                                        .heightIn(min = 68.dp)
                                        .toggleable(
                                            model in selected || model.installed,
                                            enabled = enabled,
                                            role = Role.Checkbox,
                                            onValueChange = model.toggle,
                                        )
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Checkbox(checked = model in selected || model.installed, onCheckedChange = null, enabled = enabled)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall + 2.dp),
                                        ) {
                                            Text(
                                                text = stringResource(model.title),
                                                style = InfinityTypography.titleSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            if (model.recommended) {
                                                Surface(
                                                    shape = RoundedCornerShape(999.dp),
                                                    color = MaterialTheme.colorScheme.primaryContainer,
                                                ) {
                                                    Text(
                                                        text = stringResource(R.string.recording_setup_recommended),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = stringResource(model.detail),
                                            style = InfinityTypography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = subtitleMaxLines,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Text(
                                        text = if (model.installed) stringResource(R.string.recording_setup_ready)
                                        else stringResource(R.string.recording_setup_mb, (model.bytes + 500_000) / 1_000_000),
                                        style = InfinityTypography.labelMedium,
                                        maxLines = 1,
                                        color = if (model.installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    InfinitySectionFooterCard(
                        text = stringResource(if (supportsLocal) R.string.recording_setup_privacy else R.string.recording_setup_play),
                        modifier = Modifier.padding(vertical = 12.dp),
                        maxLines = 3,
                    )
                    val lastStatus = rememberLastNonNull(status)
                    AnimatedVisibility(
                        visible = status != null,
                        enter = InfinityMotion.expandIn,
                        exit = InfinityMotion.shrinkOut,
                    ) {
                        lastStatus?.let { shownStatus ->
                            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .animateContentSize(InfinityMotion.contentSizeSpec())
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(stringResource(shownStatus), style = InfinityTypography.titleSmall)
                                    AnimatedVisibility(
                                        visible = !complete,
                                        enter = InfinityMotion.expandIn,
                                        exit = InfinityMotion.shrinkOut,
                                    ) {
                                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                            Text(stringResource(R.string.recording_setup_percent, (progress * 100).toInt()), style = InfinityTypography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                val skipAlpha by animateFloatAsState(
                    targetValue = if (complete) 0.35f else 1f,
                    animationSpec = tween(InfinityMotion.DurationShort),
                    label = "recording_setup_skip_alpha",
                )
                val primarySlot = when {
                    complete -> RecordingSetupPrimarySlot.ENTER
                    running -> RecordingSetupPrimarySlot.PAUSE
                    else -> RecordingSetupPrimarySlot.DOWNLOAD
                }
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                ) {
                    InfinityPillButton(
                        text = stringResource(if (!firstRun || running) R.string.recording_setup_enter else R.string.recording_setup_skip),
                        onClick = onFinish,
                        tone = InfinityPillTone.NEUTRAL,
                        enabled = !complete,
                        modifier = Modifier.weight(1f).alpha(skipAlpha).testTag("recording-setup-skip"),
                    )
                    AnimatedContent(
                        targetState = primarySlot,
                        transitionSpec = { InfinityMotion.fadeThrough() },
                        modifier = Modifier.weight(1f),
                        label = "recording_setup_primary_slot",
                    ) { slot ->
                        when (slot) {
                            RecordingSetupPrimarySlot.ENTER -> InfinityPillButton(
                                text = stringResource(R.string.recording_setup_enter),
                                onClick = onFinish,
                                tone = InfinityPillTone.PRIMARY,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            RecordingSetupPrimarySlot.PAUSE -> InfinityPillButton(
                                text = stringResource(R.string.recording_setup_pause),
                                onClick = {
                                    paused = true
                                    if (asr.busy()) { useAsr = true; controller.cancelAsrModelDownload() }
                                    if (refine.busy()) { useRefineOverride = true; controller.cancelRefineModelDownload() }
                                    if (speaker.busy()) { useSpeaker = true; controller.cancelSpeakerModel() }
                                    if (gguf?.isDownloading == true) { useLocal = true; controller.recommendedModel.pause() }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            RecordingSetupPrimarySlot.DOWNLOAD -> InfinityPillButton(
                                text = when {
                                    paused || failed -> stringResource(R.string.recording_setup_continue)
                                    pendingBytes == 0L -> stringResource(R.string.recording_setup_download_action)
                                    else -> stringResource(R.string.recording_setup_download, (pendingBytes + 500_000) / 1_000_000)
                                },
                                onClick = {
                                    started = true; paused = false
                                    if (useAsr && asr != RecordingAsrModelState.Installed) controller.downloadAsrModel()
                                    if (useRefine && refine != RecordingAsrModelState.Installed) controller.downloadRefineModel()
                                    if (useSpeaker && speaker != RecordingAsrModelState.Installed) controller.downloadSpeakerModel()
                                    if (supportsLocal && useLocal && !localInstalled) controller.recommendedModel.start()
                                },
                                enabled = pendingBytes > 0,
                                tone = InfinityPillTone.PRIMARY,
                                icon = Icons.Rounded.Download,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class RecordingSetupPrimarySlot {
    ENTER,
    PAUSE,
    DOWNLOAD,
}

private fun RecordingAsrModelState.busy() = this is RecordingAsrModelState.Downloading || this == RecordingAsrModelState.Installing

private fun RecordingAsrModelState.bytes(total: Long) = when (this) {
    is RecordingAsrModelState.Downloading -> downloadedBytes
    RecordingAsrModelState.Installing, RecordingAsrModelState.Installed -> total
    else -> 0L
}
