package com.promenar.infinity.recording

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.*
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.forReading

/** 录音页只渲染状态和派发动作，录音、播放与整理的生命周期由宿主持有。 */
@Composable
internal fun RecordingPageContent(
    state: RecordingScreenState,
    current: RecordingSession?,
    organized: Boolean,
    route: RecordingRoute,
    playing: Boolean = false,
    positionMs: Float = 0f,
    onBack: () -> Unit = {},
    onHistory: () -> Unit = {},
    onMode: () -> Unit = {},
    onSelect: (RecordingSession) -> Unit = {},
    onOrganizedChange: (Boolean) -> Unit = {},
    onEdit: () -> Unit = {},
    onConsent: () -> Unit = {},
    onExport: () -> Unit = {},
    onPlay: (Long) -> Unit = {},
    onTogglePlayback: () -> Unit = {},
    onSeek: (Float) -> Unit = {},
    onSeekFinished: () -> Unit = {},
    onBegin: () -> Unit = {},
    onPauseResume: () -> Unit = {},
    onFinish: () -> Unit = {},
    onResumeInterrupted: () -> Unit = {},
    onOrganize: () -> Unit = {},
    onPublish: () -> Unit = {},
) {
    val active = current != null && state.activeId == current.id
    val paused = current?.phase == RecordingPhase.Paused
    InfinityPageLayout(
        title = stringResource(R.string.recording_title),
        onBack = onBack,
        scrollable = false,
        bodyPadding = PaddingValues(),
        actions = {
            if (!active && current != null) {
                IconButton(onClick = onHistory) {
                    Icon(Icons.Rounded.History, stringResource(R.string.recording_history))
                }
            }
            IconButton(onClick = onMode) {
                Icon(Icons.Rounded.Settings, stringResource(R.string.recording_settings))
            }
        },
    ) {
        val list = key(current?.id) { rememberLazyListState() }
        val inspecting = LocalInspectionMode.current
        LaunchedEffect(current?.id, current?.segments?.size, organized, active) {
            if (!inspecting && active && !organized && current != null && current.segments.isNotEmpty() && !list.isScrollInProgress) {
                // 列表首项是录音信息与页签，后面才是逐字稿段落。
                list.scrollToItem(current.segments.size)
            }
        }
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(InfinitySpacing.ScreenHorizontal),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        ) {
            item(key = "recording_header") {
                Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
                    state.failure?.let {
                        Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = InfinityTypography.bodyMedium)
                    }
                    if (current == null) {
                        Text(stringResource(R.string.recording_ready), style = InfinityTypography.headlineSmall)
                        Text(stringResource(R.string.recording_ui_audio_local), style = InfinityTypography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        BettboxListGroup {
                            InfinitySettingsItem(title = stringResource(R.string.recording_settings),
                                subtitle = stringResource(routeLabel(route)), onClick = onMode, subtitleMaxLines = 3,
                                showChevron = true)
                        }
                        if (state.sessions.isNotEmpty()) {
                            Text(stringResource(R.string.recording_history), style = InfinityTypography.labelLarge)
                        }
                    } else {
                        RecordingSummary(
                            state, current, active, paused, playing, positionMs,
                            onTogglePlayback, onSeek, onSeekFinished,
                        )
                        Text(
                            stringResource(when {
                                state.organizingId == current.id -> R.string.recording_ui_organizing
                                state.failure == R.string.recording_organize_failed -> R.string.recording_ui_queued
                                else -> routeLabel(route)
                            }),
                            style = InfinityTypography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        InfinityButtonGroup(Modifier.fillMaxWidth()) {
                            if (route == RecordingRoute.Consent) {
                                InfinityPillButton(text = stringResource(R.string.recording_ui_cloud), onClick = onConsent, compact = true)
                            }
                            InfinityPillButton(text = stringResource(R.string.recording_edit), onClick = onEdit, compact = true)
                            if (!active && current.segments.isNotEmpty()) {
                                InfinityPillButton(text = stringResource(R.string.recording_export_raw), onClick = onExport, compact = true)
                            }
                        }
                        current.documentId?.let {
                            InfinityPropertySheet {
                                InfinityPropertyRow(label = stringResource(R.string.recording_ui_library), value = it)
                            }
                        }
                        InfinityPanelTabs(
                            tabs = listOf(
                                InfinityPanelTab(false, stringResource(R.string.recording_raw)),
                                InfinityPanelTab(true, stringResource(R.string.recording_organized)),
                            ),
                            selected = organized,
                            onSelect = onOrganizedChange,
                            bleed = InfinitySpacing.ScreenHorizontal,
                        )
                    }
                }
            }
            when {
                current == null -> itemsIndexed(state.sessions, key = { _, item -> item.id }) { index, item ->
                    InfinitySettingsItem(
                        title = item.title,
                        subtitle = recordingTimestamp(item.durationMs),
                        onClick = { onSelect(item) },
                        modifier = Modifier.bettboxListGroupItem(index, state.sessions.size),
                        showChevron = true,
                    )
                }
                organized -> item(key = "organized") {
                    Text(current.organized.ifBlank { stringResource(R.string.recording_no_organized) },
                        style = InfinityTypography.bodyLarge.forReading())
                }
                else -> {
                    items(current.segments, key = { it.startMs }) { segment ->
                        Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall)) {
                            if (active) {
                                Text(recordingTimestamp(segment.startMs), color = MaterialTheme.colorScheme.primary,
                                    style = InfinityTypography.labelMedium.copy(fontFamily = InfinityMonospace))
                            } else {
                                InfinityPillButton(text = recordingTimestamp(segment.startMs), onClick = { onPlay(segment.startMs) }, compact = true)
                            }
                            Text(segment.text, style = InfinityTypography.bodyLarge.forReading())
                            HorizontalDivider(Modifier.padding(top = InfinitySpacing.Small), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    if (active) item(key = "partial") {
                        Text(state.partial.ifBlank { stringResource(R.string.recording_ui_listening) },
                            style = InfinityTypography.bodyLarge.forReading(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        InfinityPillRow(
            modifier = Modifier.fillMaxWidth().padding(InfinitySpacing.ScreenHorizontal),
            spacing = InfinitySpacing.Small,
            stackedSpacing = InfinitySpacing.XSmall,
        ) {
            when {
                current == null -> InfinityPillButton(
                    text = stringResource(R.string.recording_start), onClick = onBegin,
                    enabled = state.ready, tone = InfinityPillTone.PRIMARY,
                    modifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget),
                )
                active -> {
                    InfinityPillButton(text = stringResource(if (paused) R.string.recording_resume else R.string.recording_pause),
                        onClick = onPauseResume, tone = if (paused) InfinityPillTone.PRIMARY else InfinityPillTone.NEUTRAL,
                        modifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget))
                    InfinityPillButton(text = stringResource(R.string.recording_ui_stop), onClick = onFinish,
                        tone = InfinityPillTone.DESTRUCTIVE, modifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget))
                }
                else -> {
                    if (current.phase == RecordingPhase.Interrupted) {
                        InfinityPillButton(text = stringResource(R.string.recording_resume), onClick = onResumeInterrupted)
                    } else {
                        InfinityPillButton(text = stringResource(R.string.recording_ui_reprocess), onClick = onOrganize,
                            enabled = current.segments.isNotEmpty())
                    }
                    InfinityPillButton(
                        text = stringResource(if (current.documentId == null) R.string.recording_save_library else R.string.recording_ui_update),
                        onClick = onPublish, tone = InfinityPillTone.PRIMARY,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingSummary(
    state: RecordingScreenState,
    current: RecordingSession,
    active: Boolean,
    paused: Boolean,
    playing: Boolean,
    positionMs: Float,
    onTogglePlayback: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
) {
    BettboxListGroup {
        Column(Modifier.padding(InfinitySpacing.Large), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
            Text(current.title, style = InfinityTypography.titleMedium)
            Text(
                recordingTimestamp(if (active) state.durationMs else current.durationMs),
                style = InfinityTypography.headlineLarge.copy(fontFamily = InfinityMonospace),
            )
            Text(stringResource(when {
                paused -> R.string.recording_paused
                active && state.opening -> R.string.recording_ui_opening
                active -> R.string.recording_running
                current.phase == RecordingPhase.Interrupted -> R.string.recording_ui_interrupted
                else -> R.string.recording_ui_audio_local
            }), style = InfinityTypography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (active) RecordingWaveform(state.levels, paused)
            if (!active && current.durationMs > 0) {
                InfinityButtonGroup(Modifier.fillMaxWidth()) {
                    InfinityPillButton(text = stringResource(if (playing) R.string.recording_pause else R.string.recording_play),
                        onClick = onTogglePlayback, compact = true)
                }
                Text(recordingTimestamp(positionMs.toLong()), style = InfinityTypography.bodySmall.copy(fontFamily = InfinityMonospace))
                val seekLabel = stringResource(R.string.recording_ui_position)
                InfinitySlider(
                    value = positionMs.coerceIn(0f, current.durationMs.toFloat()),
                    onValueChange = onSeek,
                    onValueChangeFinished = onSeekFinished,
                    valueRange = 0f..current.durationMs.toFloat(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = InfinitySpacing.MinimumTouchTarget)
                        .semantics { contentDescription = seekLabel },
                )
            }
        }
    }
}

private const val WaveformBarCount = 48
private const val WaveformAmplitude = 0.45f
private const val WaveformBaseline = 0.06f
private const val WaveformStrokeFraction = 0.7f

@Composable
private fun RecordingWaveform(levels: List<Float>, paused: Boolean) {
    val color = if (paused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
    val description = stringResource(if (paused) R.string.recording_paused else R.string.recording_running)
    Canvas(Modifier.fillMaxWidth().height(InfinitySpacing.XXLarge).semantics { contentDescription = description }) {
        val step = size.width / WaveformBarCount
        repeat(WaveformBarCount) { index ->
            val level = levels.getOrNull(index - (WaveformBarCount - levels.size)) ?: 0f
            val half = (level * WaveformAmplitude + WaveformBaseline) * size.height
            val x = (index + 0.5f) * step
            drawLine(color, Offset(x, center.y - half), Offset(x, center.y + half),
                strokeWidth = minOf(InfinitySpacing.RowTwoLineGap.toPx(), step * WaveformStrokeFraction), cap = StrokeCap.Round)
        }
    }
}

/** 对话框与预览共用输入内容，窄屏或键盘弹出时可独立滚动。 */
@Composable
internal fun RecordingEditFields(
    title: String,
    glossary: String,
    draft: String,
    onTitle: (String) -> Unit,
    onGlossary: (String) -> Unit,
    onDraft: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = InfinitySpacing.Small),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        SettingsInput(title, onTitle, label = stringResource(R.string.recording_document_title))
        SettingsInput(glossary, onGlossary, label = stringResource(R.string.recording_glossary), singleLine = false, maxLines = 2)
        SettingsInput(draft, onDraft, label = stringResource(R.string.recording_organized), singleLine = false, maxLines = 5)
    }
}


/** 编辑面板先为操作区分配高度，输入区使用剩余空间并可滚动。 */
@Composable
internal fun RecordingEditorContent(
    title: String,
    glossary: String,
    draft: String,
    onTitle: (String) -> Unit,
    onGlossary: (String) -> Unit,
    onDraft: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxSize().imePadding(), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
        Text(stringResource(R.string.recording_edit), style = InfinityTypography.titleLarge)
        RecordingEditFields(title, glossary, draft, onTitle, onGlossary, onDraft, modifier = Modifier.weight(1f))
        InfinityPillRow(Modifier.fillMaxWidth(), spacing = InfinitySpacing.Small, stackedSpacing = InfinitySpacing.XSmall) {
            InfinityPillButton(text = stringResource(R.string.recording_cancel), onClick = onCancel)
            InfinityPillButton(text = stringResource(R.string.recording_save), onClick = onSave, tone = InfinityPillTone.PRIMARY)
        }
    }
}
