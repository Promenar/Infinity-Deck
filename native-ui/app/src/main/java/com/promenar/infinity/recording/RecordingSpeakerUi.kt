package com.promenar.infinity.recording

import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Person
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
internal fun RecordingSpeakerChip(speaker: RecordingSpeaker, modifier: Modifier = Modifier, touchTarget: Boolean = false, onClick: (() -> Unit)? = null) {
    val tint = recordingSpeakerTint(speaker.id)
    val name = speaker.name.ifBlank { stringResource(R.string.recording_speaker_default, speaker.id + 1) }
    Surface(modifier = modifier, shape = CircleShape, color = tint.copy(alpha = .16f)) {
        Row(
            Modifier.widthIn(max = 192.dp).heightIn(min = if (touchTarget) 48.dp else 32.dp)
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
        if (pending) return
        pending = true
        failure = false
        coroutine.launch {
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

    val back: (() -> Unit)? = when (page) {
        "main" -> null
        "newperson" -> ({ failure = false; page = "assign" })
        else -> ({ failure = false; page = "main" })
    }
    val title = when (page) {
        "rename" -> stringResource(R.string.recording_speaker_rename)
        "save" -> stringResource(R.string.recording_speaker_save_sample)
        "newperson" -> stringResource(R.string.recording_speaker_new_person)
        "assign" -> stringResource(R.string.recording_speaker_assign)
        "merge" -> stringResource(R.string.recording_speaker_merge)
        "update" -> stringResource(R.string.recording_dialog_voice_update_title)
        else -> name
    }
    val enteringName = page in setOf("rename", "save", "newperson")
    val confirm = stringResource(when (page) {
        "update" -> R.string.recording_speaker_update_sample
        "save" -> R.string.recording_speaker_save_sample
        else -> R.string.recording_save
    })
    val footer: (@Composable () -> Unit)? = if (enteringName || page == "update") ({
        RecordingDialogActions(confirm, onConfirm = {
            when (page) {
                "rename" -> { controller.updateSpeaker(session.id, speaker.id, draft, speaker.personId); onClose() }
                "update" -> if (person != null) storeSample(person, person.name)
                else -> storeSample(null, draft)
            }
        }, dismiss = back ?: onClose, confirmEnabled = if (page == "update") person != null else draft.isNotBlank(),
            enabled = !pending)
    }) else null
    val match = when {
        speaker.confidence != null -> stringResource(R.string.recording_speaker_matched, (speaker.confidence * 100).toInt())
        person != null -> stringResource(R.string.recording_speaker_assigned)
        else -> stringResource(R.string.recording_speaker_unmatched)
    }
    RecordingDialog(title, onClose, icon = Icons.Rounded.Person,
        subtitle = if (page == "main") {
            val stats = stringResource(R.string.recording_speaker_stats, segments.size,
                recordingTimestamp(segments.sumOf { it.endMs - it.startMs }))
            "$stats · $match"
        } else null,
        width = if (page == "main") 560.dp else 412.dp, dismissEnabled = !pending,
        back = back, footer = footer,
    ) {
        when (page) {
            "rename", "save", "newperson" -> {
                val focus = remember { FocusRequester() }
                val keyboard = LocalSoftwareKeyboardController.current
                SettingsInput(draft, { draft = it.take(50); failure = false },
                    label = stringResource(R.string.recording_speaker_name), modifier = Modifier.focusRequester(focus))
                LaunchedEffect(page) { focus.requestFocus(); keyboard?.show() }
            }
            "assign" -> RecordingDialogGroup {
                people.forEach { target ->
                    RecordingDialogRow(target.name, {
                        controller.updateSpeaker(session.id, speaker.id, target.name, target.id); onClose()
                    }, subtitle = pluralStringResource(R.plurals.recording_voice_sample_count, target.samples.size, target.samples.size),
                        selected = target.id == speaker.personId)
                }
                RecordingDialogRow(stringResource(R.string.recording_speaker_new_person), {
                    draft = ""; page = "newperson"
                }, icon = Icons.Rounded.Person)
            }
            "merge" -> RecordingDialogGroup {
                others.forEach { target ->
                    val targetName = target.name.ifBlank { stringResource(R.string.recording_speaker_default, target.id + 1) }
                    val targetSegments = session.segments.filter { it.speakerId == target.id }
                    RecordingDialogRow(targetName, {
                        controller.moveSpeaker(session.id, speaker.id, target.id); onClose()
                    }, subtitle = stringResource(R.string.recording_speaker_stats, targetSegments.size,
                        recordingTimestamp(targetSegments.sumOf { it.endMs - it.startMs })))
                }
            }
            "update" -> if (person != null) {
                Text(stringResource(R.string.recording_dialog_voice_update_detail, person.name), style = InfinityTypography.bodyMedium)
            }
            else -> {
                selection.segmentStartMs?.let { start ->
                    RecordingDialogGroup(stringResource(R.string.recording_speaker_this_segment, recordingTimestamp(start))) {
                        session.segments.firstOrNull { it.startMs == start }?.let { RecordingDialogNote(it.text) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            others.forEach { target ->
                                RecordingSpeakerChip(target, Modifier.align(Alignment.CenterVertically), touchTarget = true) {
                                    controller.moveSpeaker(session.id, speaker.id, target.id, start); onClose()
                                }
                            }
                            InfinityPillButton(stringResource(R.string.recording_speaker_new), {
                                controller.moveSpeaker(session.id, speaker.id, null, start); onClose()
                            }, modifier = Modifier.align(Alignment.CenterVertically), compact = true)
                        }
                    }
                }
                RecordingDialogGroup(stringResource(R.string.recording_speaker_all_segments, name, segments.size)) {
                    RecordingDialogRow(stringResource(R.string.recording_speaker_rename), { draft = name; page = "rename" },
                        icon = Icons.Rounded.Edit)
                    RecordingDialogRow(stringResource(R.string.recording_speaker_assign), { page = "assign" },
                        subtitle = person?.name ?: stringResource(R.string.recording_speaker_unmatched),
                        icon = Icons.Rounded.Person)
                    if (others.isNotEmpty()) RecordingDialogRow(stringResource(R.string.recording_speaker_merge), { page = "merge" },
                        icon = Icons.Rounded.Link)
                    if (segments.isNotEmpty()) RecordingDialogRow(
                        stringResource(if (person == null) R.string.recording_speaker_save_sample else R.string.recording_speaker_update_sample),
                        { if (person == null) { draft = ""; page = "save" } else page = "update" },
                        icon = Icons.Rounded.Mic)
                    if (person != null) RecordingDialogRow(stringResource(R.string.recording_speaker_unmatch), {
                        controller.updateSpeaker(session.id, speaker.id, ""); onClose()
                    }, icon = Icons.Rounded.Close)
                }
            }
        }
        if (failure) Text(stringResource(R.string.recording_voice_sample_failed), color = MaterialTheme.colorScheme.error)
        if (pending) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
