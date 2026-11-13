package com.promenar.infinity.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.*
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val SpeakerLightColors = listOf(0xFF3B5BD9, 0xFF9A6200, 0xFF00796B, 0xFFB0306A, 0xFF4A7C1A, 0xFF7046C8)
private val SpeakerDarkColors = listOf(0xFF9DB7FF, 0xFFF5C26B, 0xFF6FD6C2, 0xFFF2A0C4, 0xFFB9D98A, 0xFFC9A8FF)

internal data class RecordingSpeakerSelection(val id: Int, val segmentStartMs: Long? = null)

@Composable
internal fun RecordingSpeakerChip(speaker: RecordingSpeaker, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val tint = recordingSpeakerTint(speaker.id)
    val name = speaker.name.ifBlank { stringResource(R.string.recording_speaker_default, speaker.id + 1) }
    Surface(modifier = modifier, shape = CircleShape, color = tint.copy(alpha = .16f)) {
        Row(
            Modifier.widthIn(max = 192.dp).heightIn(min = 32.dp)
                .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).background(tint, CircleShape))
            Text(name, style = InfinityTypography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun recordingSpeakerTint(id: Int): Color {
    val background = MaterialTheme.colorScheme.background
    val dark = background.red + background.green + background.blue < 1.5f
    val palette = if (dark) SpeakerDarkColors else SpeakerLightColors
    return Color(palette[Math.floorMod(id, palette.size)])
}

@Composable
internal fun RecordingSpeakerSummary(
    session: RecordingSession,
    onClick: (RecordingSpeakerSelection) -> Unit,
) {
    val bySpeaker = remember(session.segments) { session.segments.groupBy { it.speakerId } }
    val total = session.segments.sumOf { it.endMs - it.startMs }.coerceAtLeast(1)
    val speakers = session.speakers.filter { it.id in bySpeaker }
    if (speakers.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
        Text(stringResource(R.string.recording_speaker_section), style = InfinityTypography.titleSmall)
        BettboxListGroup {
            speakers.forEach { speaker ->
                val segments = bySpeaker[speaker.id].orEmpty()
                val duration = segments.sumOf { it.endMs - it.startMs }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.XSmall),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                ) {
                    Box(Modifier.weight(1f, fill = false)) {
                        RecordingSpeakerChip(speaker) { onClick(RecordingSpeakerSelection(speaker.id)) }
                    }
                    LinearProgressIndicator(
                        progress = { (duration.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f).height(6.dp),
                        color = recordingSpeakerTint(speaker.id),
                    )
                    Text(
                        stringResource(R.string.recording_speaker_stats, segments.size, recordingTimestamp(duration)),
                        style = InfinityTypography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
internal fun RecordingSpeakerStatus(
    session: RecordingSession,
    state: RecordingScreenState,
    model: RecordingAsrModelState,
    onAnalyze: () -> Unit,
    onSettings: () -> Unit,
) {
    if (state.speakerAnalysisId == session.id) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = InfinitySpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        ) {
            Text(stringResource(R.string.recording_speaker_analysis), style = InfinityTypography.labelMedium)
            LinearProgressIndicator(progress = { state.speakerProgress }, modifier = Modifier.weight(1f))
            Text("${(state.speakerProgress * 100).toInt()}%", style = InfinityTypography.labelMedium)
        }
    } else if (session.audioBytes > 0 && session.speakerAnalyzedBytes != session.audioBytes) {
        val installed = model == RecordingAsrModelState.Installed
        InfinityPillButton(
            stringResource(if (installed) R.string.recording_speaker_analyze_action else R.string.recording_speaker_model_missing),
            if (installed) onAnalyze else onSettings,
            enabled = state.activeId == null && state.recognizingId == null && state.speakerAnalysisId == null,
            compact = true,
        )
    }
}

@Composable
internal fun RecordingSpeakerPendingChip() {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = CircleShape, color = tint.copy(alpha = .12f)) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(7.dp).background(tint, CircleShape))
            Text("…", style = InfinityTypography.labelMedium, color = tint)
        }
    }
}

@Composable
internal fun RecordingSpeakerStrip(
    session: RecordingSession,
    onClick: (RecordingSpeakerSelection) -> Unit,
) {
    val durations = remember(session.segments) {
        session.segments.groupingBy { it.speakerId }.fold(0L) { total, segment -> total + segment.endMs - segment.startMs }
    }
    val total = durations.values.sum().coerceAtLeast(1)
    if (session.speakers.none { it.id in durations }) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        session.speakers.filter { it.id in durations }.forEach { speaker ->
            val percent = (durations.getValue(speaker.id) * 100 / total).toInt()
            val name = speaker.name.ifBlank { stringResource(R.string.recording_speaker_default, speaker.id + 1) }
            RecordingSpeakerChip(speaker.copy(name = "$name  $percent%")) { onClick(RecordingSpeakerSelection(speaker.id)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordingSpeakerSheet(
    session: RecordingSession,
    selection: RecordingSpeakerSelection,
    controller: RecordingController,
    onClose: () -> Unit,
) {
    val speaker = session.speakers.firstOrNull { it.id == selection.id } ?: return
    val others = session.speakers.filterNot { it.id == speaker.id }
    val name = speaker.name.ifBlank { stringResource(R.string.recording_speaker_default, speaker.id + 1) }
    val people by controller.voicePeople.collectAsStateWithLifecycle()
    val person = people.firstOrNull { it.id == speaker.personId }
    val coroutine = rememberCoroutineScope()
    var page by rememberSaveable(speaker.id) { mutableStateOf("main") }
    var draft by rememberSaveable(speaker.id) { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf(false) }
    val segments = session.segments.filter { it.speakerId == speaker.id }
    fun storeSample(existing: RecordingVoicePerson?, newName: String) {
        coroutine.launch {
            pending = true
            failure = false
            try {
                val sample = controller.speakerSample(session.id, speaker.id)
                val target = (existing ?: RecordingVoicePerson(name = newName.trim()))
                    .let { it.copy(samples = (it.samples + sample).takeLast(12)) }
                controller.saveVoicePerson(target)
                controller.updateSpeaker(session.id, speaker.id, target.name, target.id)
                onClose()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failure = true
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
            Row(
                Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
            ) {
                RecordingSpeakerChip(speaker)
                Text(
                    stringResource(R.string.recording_speaker_stats, segments.size, recordingTimestamp(segments.sumOf { it.endMs - it.startMs })),
                    style = InfinityTypography.bodySmall, modifier = Modifier.weight(1f),
                )
                Text(
                    when {
                        speaker.confidence != null -> stringResource(R.string.recording_speaker_matched, (speaker.confidence * 100).toInt())
                        person != null -> stringResource(R.string.recording_speaker_assigned)
                        else -> stringResource(R.string.recording_speaker_unmatched)
                    }, style = InfinityTypography.bodySmall,
                )
            }
            when (page) {
                "rename", "save", "newperson" -> {
                    val focus = remember { FocusRequester() }
                    val keyboard = LocalSoftwareKeyboardController.current
                    SettingsInput(draft, { draft = it.take(50); failure = false },
                        label = stringResource(R.string.recording_speaker_name), modifier = Modifier.focusRequester(focus))
                    LaunchedEffect(page) {
                        focus.requestFocus()
                        keyboard?.show()
                    }
                    InfinityPillRow {
                        InfinityPillButton(stringResource(R.string.recording_cancel), { page = "main" }, enabled = !pending)
                        InfinityPillButton(stringResource(R.string.recording_save), {
                            when (page) {
                                "rename" -> { controller.updateSpeaker(session.id, speaker.id, draft, speaker.personId); onClose() }
                                else -> storeSample(null, draft)
                            }
                        }, enabled = draft.isNotBlank() && !pending, tone = InfinityPillTone.PRIMARY)
                    }
                }
                "assign", "merge" -> {
                    Text(stringResource(if (page == "assign") R.string.recording_speaker_assign else R.string.recording_speaker_merge),
                        style = InfinityTypography.titleSmall)
                    BettboxListGroup {
                        if (page == "assign") {
                            people.forEach { target ->
                                InfinitySettingsItem(
                                    title = target.name,
                                    subtitle = pluralStringResource(R.plurals.recording_voice_sample_count, target.samples.size, target.samples.size),
                                    selected = target.id == speaker.personId,
                                    leadingContent = { RecordingVoiceAvatar(target.name) },
                                    onClick = { controller.updateSpeaker(session.id, speaker.id, target.name, target.id); onClose() },
                                )
                            }
                            InfinitySettingsItem(title = stringResource(R.string.recording_speaker_new_person), onClick = {
                                draft = ""
                                page = "newperson"
                            }, showChevron = true)
                        } else {
                            others.forEach { target ->
                                val targetName = target.name.ifBlank { stringResource(R.string.recording_speaker_default, target.id + 1) }
                                val targetSegments = session.segments.filter { it.speakerId == target.id }
                                InfinitySettingsItem(
                                    title = targetName,
                                    subtitle = stringResource(R.string.recording_speaker_stats, targetSegments.size,
                                        recordingTimestamp(targetSegments.sumOf { it.endMs - it.startMs })),
                                    leadingContent = { RecordingVoiceAvatar(targetName) },
                                    onClick = { controller.moveSpeaker(session.id, speaker.id, target.id); onClose() },
                                )
                            }
                        }
                    }
                    InfinityPillButton(stringResource(R.string.recording_cancel), { page = "main" })
                }
                else -> {
                    selection.segmentStartMs?.let { start ->
                        Text(stringResource(R.string.recording_speaker_this_segment, recordingTimestamp(start)), style = InfinityTypography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                            others.forEach { target ->
                                RecordingSpeakerChip(target, Modifier.align(Alignment.CenterVertically)) {
                                    controller.moveSpeaker(session.id, speaker.id, target.id, start)
                                    onClose()
                                }
                            }
                            InfinityPillButton(stringResource(R.string.recording_speaker_new), {
                                controller.moveSpeaker(session.id, speaker.id, null, start)
                                onClose()
                            }, modifier = Modifier.align(Alignment.CenterVertically), compact = true)
                        }
                    }
                    Text(stringResource(R.string.recording_speaker_all_segments, name, segments.size), style = InfinityTypography.titleSmall)
                    BettboxListGroup {
                        InfinitySettingsItem(title = stringResource(R.string.recording_speaker_rename), onClick = { draft = name; page = "rename" }, showChevron = true)
                        InfinitySettingsItem(title = stringResource(R.string.recording_speaker_assign), subtitle = person?.name ?: stringResource(R.string.recording_speaker_unmatched), onClick = { page = "assign" }, showChevron = true)
                        if (others.isNotEmpty()) InfinitySettingsItem(title = stringResource(R.string.recording_speaker_merge), onClick = { page = "merge" }, showChevron = true)
                        if (segments.isNotEmpty()) InfinitySettingsItem(
                            title = stringResource(if (person == null) R.string.recording_speaker_save_sample else R.string.recording_speaker_update_sample),
                            subtitle = person?.let { pluralStringResource(R.plurals.recording_voice_sample_count, it.samples.size, it.samples.size) },
                            onClick = { if (person == null) { draft = ""; page = "save" } else storeSample(person, person.name) },
                            showChevron = true,
                        )
                        if (person != null) InfinitySettingsItem(title = stringResource(R.string.recording_speaker_unmatch), onClick = {
                            controller.updateSpeaker(session.id, speaker.id, "")
                            onClose()
                        })
                    }
                }
            }
            if (failure) Text(stringResource(R.string.recording_voice_sample_failed), color = MaterialTheme.colorScheme.error)
            if (pending) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
