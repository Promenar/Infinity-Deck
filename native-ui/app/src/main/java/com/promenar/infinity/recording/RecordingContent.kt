package com.promenar.infinity.recording

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import java.util.Locale
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.*
import com.promenar.infinity.ui.common.motion.indicatorDesignSize
import com.promenar.infinity.ui.common.network.rememberAnimationsEnabled
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.LocalInfinityMotionStyles
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.forReading
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** 内容区达到这个宽度与高度时用内屏版式：通栏录音卡加逐字稿、整理稿双栏。 */
private val RecordingWideWidth = 720.dp
private val RecordingWideHeight = 480.dp

/**
 * 外屏版式下，内容区（顶栏以下、含底部操作区）达到这个高度时录音卡与页签固定在顶部、仅正文滚动；
 * 更矮的窗口整页滚动。固定的页头与操作区合计约 350dp，这个值保证正文至少留有 200dp 左右。
 */
private val RecordingPinnedHeight = 560.dp

/** 录音页一次渲染用到的状态，以及由它推出的几个判断。 */
private class RecordingView(
    val state: RecordingScreenState,
    val current: RecordingSession?,
    val route: RecordingRoute,
    val playing: Boolean,
    val positionMs: Float,
    val asrModel: RecordingAsrModelState,
    val effectiveHotwords: List<RecordingHotword>,
) {
    val active = current != null && state.activeId == current.id
    val paused = current?.phase == RecordingPhase.Paused
    val organizing = current != null && state.organizingId == current.id
    val queued = current != null && !organizing && state.failure == R.string.recording_organize_failed
    val consent = current != null && route == RecordingRoute.Consent
    val recognizing = current != null && state.recognizingId == current.id
    val canRecognize = asrModel == RecordingAsrModelState.Installed &&
        current?.let { recordingCanRecognize(it, state.activeId, state.recognizingId) } == true
}

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
    onContinue: () -> Unit = {},
    onOrganize: () -> Unit = {},
    onPublish: () -> Unit = {},
    onDelete: () -> Unit = {},
    onDismissFailure: (Int) -> Unit = {},
    asrModel: RecordingAsrModelState = RecordingAsrModelState.Installed,
    effectiveHotwords: List<RecordingHotword> = emptyList(),
    onDownloadAsr: () -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onRecognize: () -> Unit = {},
    onCancelRecognize: () -> Unit = {},
    speakerModel: RecordingAsrModelState = RecordingAsrModelState.Installed,
    onAnalyzeSpeakers: () -> Unit = {},
    onSpeakerSettings: () -> Unit = {},
    onSpeakerSelect: (RecordingSpeakerSelection) -> Unit = {},
) {
    val view = RecordingView(state, current, route, playing, positionMs, asrModel, effectiveHotwords)
    val transcriptTools: @Composable (Boolean) -> Unit = { titled ->
        TranscriptPaneHeader(view, titled, onExport, onDownloadAsr, onCancelDownload, onRecognize, onCancelRecognize)
    }
    val speakerTools: @Composable () -> Unit = {
        if (current?.distinguishSpeakers == true && !view.active) {
            RecordingSpeakerStatus(current, state, speakerModel, onAnalyzeSpeakers, onSpeakerSettings)
        }
    }
    InfinityPageLayout(
        title = stringResource(R.string.recording_title),
        onBack = onBack,
        scrollable = false,
        bodyPadding = PaddingValues(),
        actions = {
            if (!view.active && current != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.recording_ui_delete))
                }
                IconButton(onClick = onHistory) {
                    Icon(Icons.Rounded.History, stringResource(R.string.recording_history))
                }
            }
            IconButton(onClick = onMode) {
                Icon(Icons.Rounded.Settings, stringResource(R.string.recording_settings))
            }
        },
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= RecordingWideWidth && maxHeight >= RecordingWideHeight
            val pinned = maxHeight >= RecordingPinnedHeight
            val buttons: @Composable (Boolean) -> Unit = { compact ->
                RecordingActionButtons(view, compact, onBegin, onPauseResume, onFinish, onContinue, onPublish)
            }
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (wide) {
                        RecordingWideBody(
                            view, onMode, onSelect, onEdit, onConsent, transcriptTools, onPlay,
                            onTogglePlayback, onSeek, onSeekFinished, onOrganize, buttons, speakerTools, onSpeakerSelect,
                        )
                    } else {
                        RecordingNarrowBody(
                            view, organized, pinned, onMode, onSelect, onOrganizedChange, onEdit, onConsent, transcriptTools,
                            onPlay, onTogglePlayback, onSeek, onSeekFinished, onOrganize, onContinue, speakerTools, onSpeakerSelect,
                        )
                    }
                    // 失败提示浮在正文之上、操作区上方，出现与消失都不推动页面内容。
                    RecordingFailureNotice(
                        failure = state.failure,
                        onDismiss = onDismissFailure,
                        modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = FailureNoticeMaxWidth),
                    )
                }
                if (!wide) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InfinityPillRow(
                        modifier = Modifier.fillMaxWidth().padding(InfinitySpacing.ScreenHorizontal),
                        spacing = InfinitySpacing.Small,
                        stackedSpacing = InfinitySpacing.XSmall,
                    ) { buttons(false) }
                }
            }
        }
    }
}

/** 外屏：录音卡与页签在上，正文在下；[pinned] 为假时页头作为列表首项随正文滚动。 */
@Composable
private fun RecordingNarrowBody(
    view: RecordingView,
    organized: Boolean,
    pinned: Boolean,
    onMode: () -> Unit,
    onSelect: (RecordingSession) -> Unit,
    onOrganizedChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onConsent: () -> Unit,
    transcriptTools: @Composable (Boolean) -> Unit,
    onPlay: (Long) -> Unit,
    onTogglePlayback: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onOrganize: () -> Unit,
    onContinue: () -> Unit,
    speakerTools: @Composable () -> Unit,
    onSpeakerSelect: (RecordingSpeakerSelection) -> Unit,
) {
    val current = view.current
    val edge = InfinitySpacing.ScreenHorizontal
    val header: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
            RecorderCard(view, band = false, onEdit, onTogglePlayback, onSeek, onSeekFinished) {
                if (current?.canContinue == true && !view.active) RecordingContinueButton(view, onContinue)
            }
            if (current == null) {
                RecordingModeRow(view.route, onMode)
                if (view.state.sessions.isNotEmpty()) {
                    Text(
                        stringResource(R.string.recording_history),
                        style = InfinityTypography.titleSmall,
                        modifier = Modifier.padding(top = InfinitySpacing.Small),
                    )
                }
            } else {
                if (view.consent) RecordingConsentBar(onConsent)
                val dot = when {
                    view.consent || view.queued -> MaterialTheme.infinityDomainColors.warning
                    view.organizing -> MaterialTheme.colorScheme.primary
                    else -> null
                }
                InfinityPanelTabs(
                    tabs = listOf(
                        InfinityPanelTab(false, stringResource(R.string.recording_raw)),
                        InfinityPanelTab(true, stringResource(R.string.recording_organized), dot = dot),
                    ),
                    selected = organized,
                    onSelect = onOrganizedChange,
                    bleed = edge,
                )
                if (organized) OrganizedPaneHeader(view, titled = false, onOrganize)
                else {
                    transcriptTools(false)
                    speakerTools()
                    if (current.distinguishSpeakers && !view.active && view.state.speakerAnalysisId != current.id) {
                        RecordingSpeakerStrip(current, onSpeakerSelect)
                    }
                }
            }
        }
    }
    val leading: (@Composable () -> Unit)? = if (pinned) null else header
    val padding = PaddingValues(start = edge, end = edge, top = if (pinned) InfinitySpacing.Small else edge, bottom = edge)
    Column(Modifier.fillMaxSize()) {
        if (pinned) Box(Modifier.padding(start = edge, end = edge, top = edge)) { header() }
        val pane = Modifier.weight(1f).fillMaxWidth()
        when {
            current == null -> RecordingHistoryList(view.state.sessions, leading, showEmpty = false, padding, onSelect, pane)
            organized -> RecordingOrganizedList(view, leading, fill = pinned, padding, onMode, pane)
            else -> RecordingTranscriptList(view, leading, fill = pinned, padding, onPlay, pane, onSpeakerSelect)
        }
    }
}

/** 内屏：通栏录音卡（操作按钮在卡片右侧），下方逐字稿与整理稿左右对照、各自滚动。 */
@Composable
private fun RecordingWideBody(
    view: RecordingView,
    onMode: () -> Unit,
    onSelect: (RecordingSession) -> Unit,
    onEdit: () -> Unit,
    onConsent: () -> Unit,
    transcriptTools: @Composable (Boolean) -> Unit,
    onPlay: (Long) -> Unit,
    onTogglePlayback: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onOrganize: () -> Unit,
    buttons: @Composable (Boolean) -> Unit,
    speakerTools: @Composable () -> Unit,
    onSpeakerSelect: (RecordingSpeakerSelection) -> Unit,
) {
    val edge = InfinitySpacing.ScreenHorizontal
    val padding = PaddingValues(top = InfinitySpacing.Small, bottom = edge)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = edge)) {
            RecorderCard(view, band = true, onEdit, onTogglePlayback, onSeek, onSeekFinished) { buttons(true) }
        }
        HorizontalDivider(
            Modifier.padding(top = InfinitySpacing.Medium),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Row(Modifier.weight(1f).fillMaxWidth()) {
            val column = Modifier.weight(1f).fillMaxHeight()
                .padding(horizontal = edge).padding(top = InfinitySpacing.Small)
            Column(column) {
                if (view.current == null) {
                    RecordingPaneHeader(stringResource(R.string.recording_history)) {}
                    RecordingHistoryList(view.state.sessions, null, showEmpty = true, padding, onSelect, Modifier.weight(1f).fillMaxWidth())
                } else {
                    transcriptTools(true)
                    speakerTools()
                    RecordingTranscriptList(view, null, fill = true, padding, onPlay, Modifier.weight(1f).fillMaxWidth(), onSpeakerSelect)
                }
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(column) {
                if (view.current == null) {
                    RecordingPaneHeader(stringResource(R.string.recording_settings)) {}
                    Box(Modifier.padding(top = InfinitySpacing.Small)) { RecordingModeRow(view.route, onMode) }
                } else {
                    val session = view.current
                    val summary = session.distinguishSpeakers
                    val header: (@Composable () -> Unit)? = if (!summary) null else {
                        {
                            Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
                                if (!view.active && view.state.speakerAnalysisId != session.id) {
                                    RecordingSpeakerSummary(session, onSpeakerSelect)
                                }
                                OrganizedPaneHeader(view, titled = true, onOrganize)
                                if (view.consent) RecordingConsentBar(onConsent)
                            }
                        }
                    }
                    if (header == null) {
                        OrganizedPaneHeader(view, titled = true, onOrganize)
                        if (view.consent) RecordingConsentBar(onConsent, Modifier.padding(top = InfinitySpacing.Small))
                    }
                    RecordingOrganizedList(view, header, fill = true, padding, onMode, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

/** 录音卡的标题和操作共用首行，音频播放或实时波形独占下一行。 */
@Composable
private fun RecorderCard(
    view: RecordingView,
    band: Boolean,
    onEdit: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    actions: @Composable () -> Unit,
) {
    val current = view.current
    BettboxListGroup {
        Column(Modifier.padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small)) {
            if (current != null || band) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                ) {
                    if (current == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        Text(
                            current.title,
                            style = InfinityTypography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        InfinityPillButton(text = stringResource(R.string.recording_edit), onClick = onEdit, compact = true)
                    }
                    actions()
                }
            }
            if (current == null || view.active) RecorderLive(view, band)
            else RecorderPlayback(view, current, onTogglePlayback, onSeek, onSeekFinished)
        }
        current?.documentId?.let {
            HorizontalDivider(
                Modifier.padding(horizontal = InfinitySpacing.Large),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
            ) {
                Text(
                    stringResource(R.string.recording_ui_library),
                    style = InfinityTypography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    it,
                    modifier = Modifier.weight(1f),
                    style = InfinityTypography.bodyMedium.copy(fontFamily = InfinityMonospace),
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 待录音与录音中：状态行、计时与波形。 */
@Composable
private fun RecorderLive(view: RecordingView, band: Boolean) {
    val idle = view.current == null
    val opening = view.active && view.state.opening
    val dimmed = idle || view.paused || opening
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.heightIn(min = StatusLineHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        when {
            idle -> Unit
            view.paused -> Icon(Icons.Rounded.Pause, contentDescription = null, tint = muted, modifier = Modifier.size(StatusIconSize))
            opening -> CircularProgressIndicator(Modifier.size(StatusSpinnerSize), strokeWidth = 2.dp)
            else -> RecordingDot()
        }
        Text(
            stringResource(when {
                idle -> R.string.recording_ready
                view.paused -> R.string.recording_paused
                opening -> R.string.recording_ui_opening
                else -> R.string.recording_running
            }),
            style = if (idle) InfinityTypography.titleMedium else InfinityTypography.labelLarge,
            color = if (idle || !dimmed) MaterialTheme.colorScheme.onSurface else muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Large)) {
        Text(
            recordingTimestamp(if (idle) 0L else view.state.durationMs),
            style = InfinityTypography.displaySmall.copy(fontFamily = InfinityMonospace),
            color = if (dimmed) muted else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
        )
        // 外屏波形贴右，内屏紧跟计时；宽度不超过全部采样能画满的长度。
        Box(Modifier.weight(1f), contentAlignment = if (band) Alignment.CenterStart else Alignment.CenterEnd) {
            RecordingWaveform(
                levels = if (idle) emptyList() else view.state.levels,
                live = view.active && !view.paused && !opening,
                paused = view.paused,
                description = if (idle) null else stringResource(if (view.paused) R.string.recording_paused else R.string.recording_running),
                modifier = Modifier.widthIn(max = WaveformMaxWidth).fillMaxWidth().height(WaveformHeight),
            )
        }
    }
}

/** 已结束或中断的录音：播放键、进度条与时间同排。 */
@Composable
private fun RecorderPlayback(
    view: RecordingView,
    current: RecordingSession,
    onTogglePlayback: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (current.phase == RecordingPhase.Interrupted) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        ) {
            Icon(
                Icons.Rounded.WarningAmber, contentDescription = null,
                tint = MaterialTheme.infinityDomainColors.warning, modifier = Modifier.size(StatusIconSize),
            )
            Text(stringResource(R.string.recording_ui_interrupted), style = InfinityTypography.bodySmall, color = muted)
        }
    }
    if (current.durationMs > 0) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        ) {
            FilledTonalIconButton(onClick = onTogglePlayback, modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget)) {
                Icon(
                    if (view.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    stringResource(if (view.playing) R.string.recording_pause else R.string.recording_play),
                )
            }
            val seekLabel = stringResource(R.string.recording_ui_position)
            val position = view.positionMs.coerceIn(0f, current.durationMs.toFloat())
            InfinitySlider(
                value = position,
                onValueChange = onSeek,
                onValueChangeFinished = onSeekFinished,
                valueRange = 0f..current.durationMs.toFloat(),
                thumbSize = 16.dp,
                modifier = Modifier.weight(1f).semantics { contentDescription = seekLabel },
            )
            Text(
                "${recordingTimestamp(position.toLong())} / ${recordingTimestamp(current.durationMs)}",
                style = InfinityTypography.labelMedium.copy(fontFamily = InfinityMonospace),
                color = muted,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** 录音中的红点；系统未关闭动画时缓慢明暗。 */
@Composable
private fun RecordingDot() {
    val color = MaterialTheme.colorScheme.error
    val pulse = if (rememberAnimationsEnabled() && !LocalInspectionMode.current) {
        rememberInfiniteTransition(label = "recordingDot").animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "recordingDotAlpha",
        )
    } else null
    Box(Modifier.size(InfinitySpacing.Small).graphicsLayer { alpha = pulse?.value ?: 1f }.background(color, CircleShape))
}

private val StatusLineHeight = 24.dp
private val StatusIconSize = 16.dp
private val StatusSpinnerSize = 14.dp

private val WaveformHeight = 40.dp
private val WaveformPitch = 6.dp
private val WaveformBarWidth = 3.dp
private val WaveformMinBar = 4.dp
private val WaveformFade = 30.dp

/** 控制器最多保留 48 个电平采样，按固定间距正好画满这个宽度。 */
private val WaveformMaxWidth = WaveformPitch * 48

/** 低于这个电平按静音画成圆点。 */
private const val WaveformSilence = 0.06f

/** 每 3200 字节音频出一个电平采样，即 100 毫秒。 */
private const val WaveformSampleMillis = 100

/** 采样到得比绘制快时，最多欠下的格数；超过就直接跟上。 */
private const val WaveformMaxLag = 2f

/**
 * 滚动波形：最新采样在右端，柱条按固定间距向左排开，放不下的部分在左缘淡出。
 * [live] 时每到一个新采样，整组柱条在一个采样间隔内匀速左移一格，采样之间逐帧连续；系统关闭动画时逐格跳动。
 */
@Composable
private fun RecordingWaveform(
    levels: List<Float>,
    live: Boolean,
    paused: Boolean,
    description: String?,
    modifier: Modifier = Modifier,
) {
    val voiced = if (paused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
    val silent = MaterialTheme.colorScheme.outlineVariant
    val animate = live && rememberAnimationsEnabled() && !LocalInspectionMode.current
    // 以“格”为单位的右移量：新采样到达时加一格（柱条留在原位），随后匀速回到零。
    val shift = remember { Animatable(0f) }
    LaunchedEffect(levels, animate) {
        if (!animate) {
            shift.snapTo(0f)
            return@LaunchedEffect
        }
        shift.snapTo((shift.value + 1f).coerceAtMost(WaveformMaxLag))
        shift.animateTo(0f, tween((WaveformSampleMillis * shift.value).roundToInt(), easing = LinearEasing))
    }
    Canvas(if (description == null) modifier else modifier.semantics { contentDescription = description }) {
        val pitch = WaveformPitch.toPx()
        val bar = WaveformBarWidth.toPx()
        val fade = WaveformFade.toPx()
        val minBar = WaveformMinBar.toPx()
        val offset = shift.value * pitch
        val slots = ceil(size.width / pitch).toInt() + 2
        clipRect {
            repeat(slots) { slot ->
                val x = size.width - bar / 2 - slot * pitch + offset
                if (x < -bar) return@repeat
                val level = levels.getOrNull(levels.lastIndex - slot)?.coerceIn(0f, 1f) ?: 0f
                val alpha = ((x + bar / 2) / fade).coerceIn(0f, 1f)
                if (level < WaveformSilence) {
                    drawCircle(silent, radius = bar / 2, center = Offset(x, center.y), alpha = alpha)
                } else {
                    // 圆头端帽各伸出半个线宽，线段长度相应减去。
                    val half = ((minBar + level * (size.height - minBar) - bar) / 2).coerceAtLeast(0f)
                    drawLine(
                        voiced, Offset(x, center.y - half), Offset(x, center.y + half),
                        strokeWidth = bar, cap = StrokeCap.Round, alpha = alpha,
                    )
                }
            }
        }
    }
}

/** 待录音页的整理方式入口。 */
@Composable
private fun RecordingModeRow(route: RecordingRoute, onMode: () -> Unit) {
    BettboxListGroup {
        InfinitySettingsItem(
            title = stringResource(R.string.recording_settings),
            subtitle = stringResource(routeLabel(route)),
            onClick = onMode,
            subtitleMaxLines = 3,
            showChevron = true,
        )
    }
}

/** 云端整理待确认的提示条：出现在页签上方（外屏）或整理稿一栏顶部（内屏），占位固定。 */
@Composable
private fun RecordingConsentBar(onConsent: () -> Unit, modifier: Modifier = Modifier) {
    val content = MaterialTheme.colorScheme.onSecondaryContainer
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = content,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                .padding(start = InfinitySpacing.Large, end = InfinitySpacing.XSmall, top = InfinitySpacing.XSmall, bottom = InfinitySpacing.XSmall),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        ) {
            Text(stringResource(R.string.recording_route_consent), style = InfinityTypography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onConsent, colors = ButtonDefaults.textButtonColors(contentColor = content)) {
                Text(stringResource(R.string.recording_ui_enable), maxLines = 1)
            }
        }
    }
}

/** 一栏正文上方的页头行；带 [title] 时是内屏的栏标题，下方加分隔线。 */
@Composable
private fun RecordingPaneHeader(title: String?, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = PaneHeaderHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        if (title != null) Text(title, style = InfinityTypography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        else Spacer(Modifier.weight(1f))
        content()
    }
}

private val PaneHeaderHeight = 40.dp

/** 逐字稿操作与进度跟随栏位；缺少模型时可在同处下载。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranscriptPaneHeader(
    view: RecordingView,
    titled: Boolean,
    onExport: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onRecognize: () -> Unit,
    onCancelRecognize: () -> Unit,
) {
    val current = view.current ?: return
    val actions: @Composable () -> Unit = {
        InfinityPillButton(
            stringResource(R.string.recording_recognize), onRecognize, compact = true, enabled = view.canRecognize,
        )
        InfinityPillButton(
            stringResource(R.string.recording_export_raw), onExport, compact = true, enabled = current.segments.isNotEmpty(),
        )
    }
    if (titled) {
        RecordingPaneHeader(stringResource(R.string.recording_raw)) {
            if (!view.active) actions()
        }
    } else if (!view.active) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small, Alignment.End),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) { actions() }
    }
    if (view.recognizing) {
        RecordingWorkProgress(R.string.recording_recognizing, view.state.recognizeProgress, onCancelRecognize)
    }
    val model = view.asrModel
    if (model != RecordingAsrModelState.Installed) {
        BettboxListGroup { RecordingAsrControls(model, onDownload, onCancelDownload, inTranscript = true) }
    }
}

/** 整理稿页头：整理状态或整理方式，以及“重新整理”。 */
@Composable
private fun OrganizedPaneHeader(view: RecordingView, titled: Boolean, onOrganize: () -> Unit) {
    val current = view.current ?: return
    val reprocessable = !view.active && current.phase == RecordingPhase.Finished &&
        current.segments.isNotEmpty() && view.route != RecordingRoute.Raw
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val warning = MaterialTheme.infinityDomainColors.warning
    if (titled || !view.active || current.manualEdit) {
        RecordingPaneHeader(if (titled) stringResource(R.string.recording_organized) else null) {
            if (current.manualEdit) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)) {
                    Text(
                        stringResource(R.string.recording_manual_status),
                        modifier = Modifier.padding(horizontal = InfinitySpacing.Small, vertical = InfinitySpacing.XSmall),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = InfinityTypography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
            if (!view.active) {
                InfinityPillButton(
                    text = stringResource(R.string.recording_ui_reprocess), onClick = onOrganize,
                    compact = true, enabled = reprocessable,
                )
            }
        }
    }
    if (view.organizing || view.queued) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        ) {
            if (view.organizing) {
                CircularProgressIndicator(Modifier.size(StatusSpinnerSize), strokeWidth = 2.dp)
                PaneStatusText(stringResource(R.string.recording_ui_organizing), muted)
            } else {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = warning, modifier = Modifier.size(StatusIconSize))
                PaneStatusText(stringResource(R.string.recording_ui_queued), warning)
            }
        }
    }
}

@Composable
private fun PaneStatusText(text: String, color: Color) {
    Text(text, style = InfinityTypography.bodySmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** 正在录入的片段每多这么多字，列表再跟到底一次。 */
private const val PartialFollowStep = 24

/** 列表是否停在末尾附近：用户往回翻看时不再自动跟随新内容。 */
private fun LazyListState.followsEnd(): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    return last.index >= info.totalItemsCount - 2
}

/** 逐字稿：各段时间戳与文字；录音中末尾是正在录入的片段，结束后时间戳可点按播放，播放到的一段高亮。 */
@Composable
private fun RecordingTranscriptList(
    view: RecordingView,
    leading: (@Composable () -> Unit)?,
    fill: Boolean,
    contentPadding: PaddingValues,
    onPlay: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onSpeakerSelect: (RecordingSpeakerSelection) -> Unit = {},
) {
    val current = view.current ?: return
    val list = key(current.id) { rememberLazyListState() }
    val live = view.active && !view.paused
    val lastIndex = (if (leading != null) 1 else 0) + current.segments.size + (if (live) 1 else 0) - 1
    val inspecting = LocalInspectionMode.current
    LaunchedEffect(current.id, current.segments.size, live, view.state.partial.length / PartialFollowStep) {
        if (!inspecting && view.active && lastIndex >= 0 && !list.isScrollInProgress && list.followsEnd()) {
            list.scrollToItem(lastIndex)
        }
    }
    val playingIndex = if (view.playing) recordingSegmentAt(current.segments, view.positionMs.toLong()) else -1
    val stamp = InfinityTypography.labelMedium.copy(fontFamily = InfinityMonospace)
    val reading = InfinityTypography.bodyLarge.forReading()
    val hotwordPattern = remember(view.effectiveHotwords) { recordingHotwordPattern(view.effectiveHotwords) }
    val speakers = remember(current.speakers) { current.speakers.associateBy { it.id } }
    LazyColumn(state = list, modifier = modifier, contentPadding = contentPadding) {
        if (leading != null) item(key = "recording_header") { RecordingLeading(leading) }
        itemsIndexed(current.segments, key = { _, segment -> segment.startMs }) { index, segment ->
            Column(
                Modifier.fillMaxWidth().padding(bottom = InfinitySpacing.Large),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                ) {
                    if (view.active) {
                        Text(recordingTimestamp(segment.startMs), color = MaterialTheme.colorScheme.primary, style = stamp)
                    } else {
                        InfinityPillButton(
                            text = recordingTimestamp(segment.startMs),
                            onClick = { onPlay(segment.startMs) },
                            tone = if (index == playingIndex) InfinityPillTone.PRIMARY else InfinityPillTone.NEUTRAL,
                            icon = Icons.Rounded.PlayArrow,
                            compact = true,
                        )
                    }
                    if (current.distinguishSpeakers && !view.active) {
                        if (view.state.speakerAnalysisId == current.id) RecordingSpeakerPendingChip()
                        else speakers[segment.speakerId]?.let { speaker ->
                            RecordingSpeakerChip(speaker) { onSpeakerSelect(RecordingSpeakerSelection(speaker.id, segment.startMs)) }
                        }
                    }
                }
                val text = remember(segment.text, hotwordPattern) {
                    buildAnnotatedString {
                        append(segment.text)
                        hotwordPattern?.findAll(segment.text)?.forEach { match ->
                            addStyle(SpanStyle(textDecoration = TextDecoration.Underline), match.range.first, match.range.last + 1)
                        }
                    }
                }
                Text(text, style = reading)
            }
        }
        if (live) item(key = "partial") {
            Column(
                Modifier.fillMaxWidth().padding(bottom = InfinitySpacing.Large),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
            ) {
                Text(
                    recordingTimestamp(current.segments.lastOrNull()?.endMs ?: 0L),
                    color = MaterialTheme.colorScheme.primary, style = stamp,
                )
                LiveTranscriptText(
                    text = when {
                        view.state.opening -> stringResource(R.string.recording_ui_opening)
                        view.state.audioOnly -> stringResource(R.string.recording_asr_audio_only_live)
                        view.state.partial.isBlank() -> stringResource(R.string.recording_ui_listening)
                        else -> view.state.partial
                    },
                    animate = !view.state.opening && !view.state.audioOnly,
                )
            }
        }
        if (current.segments.isEmpty() && !live && !view.recognizing) item(key = "empty") {
            RecordingEmpty(fill) {
                RecordingEmptyLabel(Icons.Rounded.Description, stringResource(R.string.recording_ui_no_raw))
            }
        }
    }
}

/** 动画参与正文排版，始终跟在最后一个字后面。 */
@Composable
private fun LiveTranscriptText(text: String, animate: Boolean) {
    val reading = InfinityTypography.bodyLarge.forReading()
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    if (!animate) {
        Text(text, style = reading, color = color)
        return
    }

    val indicator = LocalInfinityMotionStyles.current.indicator
    val design = indicatorDesignSize(indicator)
    val gap = InfinitySpacing.Small
    val placeholder = with(LocalDensity.current) {
        Placeholder((design.width + gap).toSp(), design.height.toSp(), PlaceholderVerticalAlign.TextCenter)
    }
    val inline = remember(placeholder, gap, indicator) {
        mapOf(LiveIndicatorId to InlineTextContent(placeholder) {
            Box(Modifier.fillMaxSize().padding(start = gap), contentAlignment = Alignment.Center) {
                GenerationDots(style = indicator)
            }
        })
    }
    val annotated = remember(text) {
        buildAnnotatedString {
            append(text)
            appendInlineContent(LiveIndicatorId)
        }
    }
    Text(annotated, inlineContent = inline, style = reading, color = color)
}

private const val LiveIndicatorId = "recording-live-indicator"

/** 整理稿：全文；没有内容时按整理中、仅转写、尚未整理分别说明。 */
@Composable
private fun RecordingOrganizedList(
    view: RecordingView,
    leading: (@Composable () -> Unit)?,
    fill: Boolean,
    contentPadding: PaddingValues,
    onMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = view.current ?: return
    val list = key(current.id, current.distinguishSpeakers) { rememberLazyListState() }
    LazyColumn(state = list, modifier = modifier, contentPadding = contentPadding) {
        if (leading != null) item(key = "recording_header") { RecordingLeading(leading) }
        item(key = "organized") {
            when {
                current.organized.isNotBlank() -> Text(
                    remember(current.organized, current.distinguishSpeakers, current.manualEdit) {
                        if (!current.distinguishSpeakers || current.manualEdit) buildAnnotatedString { append(current.organized) }
                        else buildAnnotatedString {
                            current.organized.lineSequence().forEachIndexed { index, line ->
                                if (index > 0) append('\n')
                                if (line.startsWith("**") && line.endsWith("**") && line.length > 4) {
                                    val start = length
                                    append(line.substring(2, line.length - 2))
                                    addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), start, length)
                                } else append(line)
                            }
                        }
                    },
                    style = InfinityTypography.bodyLarge.forReading(),
                    modifier = Modifier.fillMaxWidth(),
                )
                view.organizing -> RecordingEmpty(fill && leading == null) {
                    GenerationDots()
                    Text(
                        stringResource(R.string.recording_ui_organizing),
                        style = InfinityTypography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                view.route == RecordingRoute.Raw -> RecordingEmpty(fill && leading == null) {
                    RecordingEmptyLabel(Icons.Rounded.Description, stringResource(R.string.recording_mode_raw))
                    InfinityPillButton(text = stringResource(R.string.recording_settings), onClick = onMode, compact = true)
                }
                else -> RecordingEmpty(fill && leading == null) {
                    RecordingEmptyLabel(Icons.Rounded.Description, stringResource(R.string.recording_no_organized))
                }
            }
        }
    }
}

/** 录音记录：标题与时长，中断的录音加注；[showEmpty] 时没有记录也给出说明。 */
@Composable
private fun RecordingHistoryList(
    sessions: List<RecordingSession>,
    leading: (@Composable () -> Unit)?,
    showEmpty: Boolean,
    contentPadding: PaddingValues,
    onSelect: (RecordingSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interrupted = stringResource(R.string.recording_ui_interrupted)
    LazyColumn(modifier = modifier, contentPadding = contentPadding) {
        if (leading != null) item(key = "recording_header") { RecordingLeading(leading) }
        if (sessions.isEmpty() && showEmpty) item(key = "empty") {
            RecordingEmpty(fill = true) {
                RecordingEmptyLabel(Icons.Rounded.Mic, stringResource(R.string.recording_ui_no_history))
            }
        }
        itemsIndexed(sessions, key = { _, item -> item.id }) { index, item ->
            InfinitySettingsItem(
                title = item.title,
                subtitleSegments = listOfNotNull(
                    recordingTimestamp(item.durationMs),
                    interrupted.takeIf { item.phase == RecordingPhase.Interrupted },
                ),
                onClick = { onSelect(item) },
                modifier = Modifier.bettboxListGroupItem(index, sessions.size),
                showChevron = true,
            )
        }
    }
}

/** 页头作为列表首项时，与正文之间留出间距。 */
@Composable
private fun RecordingLeading(header: @Composable () -> Unit) {
    Box(Modifier.padding(bottom = InfinitySpacing.Small)) { header() }
}

/** 一栏没有内容时的说明：[fill] 时在栏内居中，否则只占上下留白。 */
@Composable
private fun LazyItemScope.RecordingEmpty(fill: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Column(
        if (fill) Modifier.fillParentMaxSize() else Modifier.fillMaxWidth().padding(vertical = InfinitySpacing.XXLarge),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun RecordingEmptyLabel(icon: ImageVector, text: String) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Icon(icon, contentDescription = null, tint = color.copy(alpha = 0.6f), modifier = Modifier.size(EmptyIconSize))
    Text(text, style = InfinityTypography.bodyMedium, color = color)
}

private val EmptyIconSize = 28.dp

/** 内屏操作位于录音卡首行，外屏主操作位于底栏。 */
@Composable
private fun RecordingActionButtons(
    view: RecordingView,
    compact: Boolean,
    onBegin: () -> Unit,
    onPauseResume: () -> Unit,
    onFinish: () -> Unit,
    onContinue: () -> Unit,
    onPublish: () -> Unit,
) {
    val current = view.current
    if (view.active) {
        InfinityPillButton(
            text = stringResource(if (view.paused) R.string.recording_resume else R.string.recording_pause),
            onClick = onPauseResume, compact = compact,
            icon = if (view.paused) Icons.Rounded.Mic else Icons.Rounded.Pause,
        )
        InfinityPillButton(
            text = stringResource(R.string.recording_ui_stop), onClick = onFinish, compact = compact,
            tone = InfinityPillTone.PRIMARY, icon = Icons.Rounded.Stop,
        )
    } else {
        if (current != null) {
            InfinityPillButton(
                text = stringResource(if (current.documentId == null) R.string.recording_save_library else R.string.recording_ui_update),
                onClick = onPublish, compact = compact,
            )
            if (compact && current.canContinue) RecordingContinueButton(view, onContinue)
        }
        InfinityPillButton(
            text = stringResource(R.string.recording_start), onClick = onBegin, compact = compact,
            tone = InfinityPillTone.PRIMARY, enabled = view.state.ready, icon = Icons.Rounded.Mic,
        )
    }
}

@Composable
private fun RecordingContinueButton(view: RecordingView, onContinue: () -> Unit) {
    InfinityPillButton(
        text = stringResource(R.string.recording_resume), onClick = onContinue, compact = true,
        icon = Icons.Rounded.Mic, enabled = !view.recognizing,
    )
}

/** 浮动提示连同自带外边距的最大宽度：内屏居中，不横贯整屏。 */
private val FailureNoticeMaxWidth = 472.dp

/** 失败提示自动收起前的停留时间。 */
private const val FailureNoticeMillis = 5_000L

/**
 * 失败提示：浮在页面底部，停留数秒后自动收起，也可手动关闭。
 * 收起时回调 [onDismiss]；宿主可能保留这条失败用于状态显示，所以收起与否由本地记录决定。
 */
@Composable
private fun RecordingFailureNotice(failure: Int?, onDismiss: (Int) -> Unit, modifier: Modifier = Modifier) {
    var dismissed by rememberSaveable { mutableStateOf<Int?>(null) }
    // 退场动画期间仍显示刚才那条文字。
    var shown by remember { mutableStateOf(failure) }
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(failure) {
        if (failure == dismissed) return@LaunchedEffect
        dismissed = null
        if (failure == null) return@LaunchedEffect
        shown = failure
        delay(FailureNoticeMillis)
        dismissed = failure
        dismiss(failure)
    }
    AnimatedVisibility(
        visible = failure != null && failure != dismissed,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut(),
    ) {
        val message = failure ?: shown ?: return@AnimatedVisibility
        InfinitySnackbar(
            data = InfinitySnackbarData(
                message = stringResource(message),
                type = SnackbarType.ERROR,
                actionLabel = stringResource(R.string.recording_close),
            ),
            onAction = {
                dismissed = message
                dismiss(message)
            },
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** 表单整体滚动，键盘弹出时标题、热词和整理稿仍可访问。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordingEditorContent(
    title: String,
    hotwords: List<String>,
    useLibrary: Boolean,
    enabledHotwordCount: Int,
    draft: String,
    onTitle: (String) -> Unit,
    onHotwords: (List<String>) -> Unit,
    onUseLibrary: (Boolean) -> Unit,
    onDraft: (String) -> Unit,
    onSave: (List<String>) -> Unit,
    onCancel: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val word = input.trim()
    val duplicate = hotwords.any { it.lowercase(Locale.ROOT) == word.lowercase(Locale.ROOT) }
    val canAdd = word.isNotEmpty() && !duplicate && word.length <= RecordingHotwordPolicy.MAX_LENGTH &&
        hotwords.size < RecordingHotwordPolicy.MAX_SESSION
    val error = when {
        word.isEmpty() -> null
        duplicate -> R.string.recording_hotword_session_duplicate
        word.length > RecordingHotwordPolicy.MAX_LENGTH || hotwords.size >= RecordingHotwordPolicy.MAX_SESSION ->
            R.string.recording_hotword_session_limit
        else -> null
    }
    val add = {
        if (canAdd) {
            onHotwords(hotwords + word)
            input = ""
        }
    }
    Column(Modifier.fillMaxSize().imePadding(), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
        Text(stringResource(R.string.recording_edit), style = InfinityTypography.titleLarge)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        ) {
            SettingsInput(title, onTitle, label = stringResource(R.string.recording_document_title))
            if (hotwords.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall)) {
                    hotwords.forEach { term ->
                        val removeLabel = stringResource(R.string.recording_hotword_remove, term)
                        InputChip(
                            selected = false,
                            onClick = { onHotwords(hotwords - term) },
                            label = { Text(term, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            modifier = Modifier.semantics { contentDescription = removeLabel },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                textStyle = InfinityTypography.bodyLarge.forInput(),
                label = { Text(stringResource(R.string.recording_hotword_session)) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(stringResource(it)) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                trailingIcon = {
                    IconButton(onClick = add, enabled = canAdd) {
                        Icon(Icons.Rounded.Add, stringResource(R.string.recording_hotword_add))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            BettboxListGroup {
                SettingsToggle(
                    title = stringResource(R.string.recording_hotword_use_library),
                    description = stringResource(R.string.recording_hotword_enabled_count, enabledHotwordCount),
                    checked = useLibrary,
                    onCheckedChange = onUseLibrary,
                    descriptionMaxLines = 2,
                )
            }
            SettingsInput(draft, onDraft, label = stringResource(R.string.recording_organized), singleLine = false, maxLines = 8)
        }
        InfinityPillRow(Modifier.fillMaxWidth(), spacing = InfinitySpacing.Small, stackedSpacing = InfinitySpacing.XSmall) {
            InfinityPillButton(stringResource(R.string.recording_cancel), onCancel)
            InfinityPillButton(
                stringResource(R.string.recording_save),
                onClick = { onSave(if (canAdd) hotwords + word else hotwords) },
                enabled = error == null,
                tone = InfinityPillTone.PRIMARY,
            )
        }
    }
}
