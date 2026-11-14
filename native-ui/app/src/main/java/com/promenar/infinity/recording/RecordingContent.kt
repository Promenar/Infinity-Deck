package com.promenar.infinity.recording

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
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
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.ChevronRight
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.activity.compose.BackHandler
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.*
import com.promenar.infinity.ui.common.motion.indicatorDesignSize
import com.promenar.infinity.ui.common.network.rememberAnimationsEnabled
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.LocalInfinityMotionStyles
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.forReading
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlin.math.ceil
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 内容区达到这个宽度与高度时用内屏版式：通栏录音卡加逐字稿、整理稿双栏。 */
private val RecordingWideWidth = 600.dp
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
    val playbackEnvelope: List<Float>,
    val asrModel: RecordingAsrModelState,
    val effectiveHotwords: List<RecordingHotword>,
    val waveform: StateFlow<RecordingWaveformSamples>,
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
    waveform: StateFlow<RecordingWaveformSamples>,
    playing: Boolean = false,
    positionMs: Float = 0f,
    playbackEnvelope: List<Float> = emptyList(),
    onBack: () -> Unit = {},
    onHistory: () -> Unit = {},
    onMode: () -> Unit = {},
    onSelect: (RecordingSession) -> Unit = {},
    onOrganizedChange: (Boolean) -> Unit = {},
    onEdit: () -> Unit = {},
    onConsent: () -> Unit = {},
    onPlay: (Long) -> Unit = {},
    onTogglePlayback: () -> Unit = {},
    onSeek: (Float) -> Unit = {},
    onSeekFinished: () -> Unit = {},
    onBegin: () -> Unit = {},
    onPauseResume: () -> Unit = {},
    onFinish: () -> Unit = {},
    onContinue: () -> Unit = {},
    onOrganize: () -> Unit = {},
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
    onRecordMenu: () -> Unit = onMode,
    onRename: () -> Unit = onEdit,
    onLanguage: () -> Unit = {},
    onTranscriptMenu: () -> Unit = onRecognize,
    onDiscard: () -> Unit = {},
    onReturnActive: () -> Unit = onHistory,
    editor: RecordingInlineEditor? = null,
    batchOperation: RecordingBatchOperation = remember { RecordingBatchOperation() },
    onDeleteRecordings: suspend (Collection<String>) -> RecordingBatchDeletion = { RecordingBatchDeletion() },
) {
    var historyQuery by rememberSaveable { mutableStateOf("") }
    var selectedRecordings by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var selectingRecordings by rememberSaveable { mutableStateOf(false) }
    var batchConfirmation by remember { mutableStateOf<List<RecordingSession>?>(null) }
    var confirmationVisible by remember { mutableStateOf(false) }
    val deleting = batchOperation.deleting
    var deleteResult by remember { mutableStateOf<RecordingBatchDeletion?>(null) }
    val focus = LocalFocusManager.current
    val eligibleSelection = remember(state.sessions, state.activeId, selectedRecordings) {
        val selectedIds = selectedRecordings.toSet()
        state.sessions.filter { it.id in selectedIds && recordingDeletable(it, state.activeId) }
    }
    val clearSelection = {
        selectedRecordings = arrayListOf()
        selectingRecordings = false
    }
    val finishedDeletion = batchOperation.result
    LaunchedEffect(finishedDeletion) {
        val result = finishedDeletion ?: return@LaunchedEffect
        selectedRecordings = ArrayList(result.failed)
        selectingRecordings = result.failed.isNotEmpty()
        deleteResult = result
        batchOperation.consumeResult()
    }
    LaunchedEffect(current?.id) {
        if (current != null && !deleting) {
            confirmationVisible = false
            clearSelection()
        }
    }
    val historySelecting = current == null && selectingRecordings
    val pageBack = {
        if (historySelecting) {
            if (!deleting) clearSelection()
        } else onBack()
    }
    BackHandler(enabled = historySelecting, onBack = pageBack)
    val active = current != null && state.activeId == current.id
    val historyList = rememberLazyListState()
    val animationsEnabled = rememberAnimationsEnabled()
    InfinityPageLayout(
        title = stringResource(R.string.recording_title),
        onBack = pageBack,
        scrollable = false,
        bodyPadding = PaddingValues(),
        titleContent = if (current == null) null else {
            {
                Row(Modifier.clickable(onClick = onRename).heightIn(min = 44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(current.title, style = InfinityTypography.titleLarge, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Rounded.Edit, stringResource(R.string.recording_rename_title), Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        actions = {
            IconButton(onClick = if (current == null) ({ clearSelection(); onMode() }) else onRecordMenu,
                enabled = !deleting) {
                Icon(if (current == null) Icons.Rounded.Settings else Icons.Rounded.MoreHoriz,
                    stringResource(if (current == null) R.string.recording_settings else R.string.recording_actions))
            }
        },
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= RecordingWideWidth && maxHeight >= RecordingWideHeight
            val pinned = maxHeight >= RecordingPinnedHeight
            // 同一录音的实时听写持续更新，只在列表与详情之间播放转场。
            AnimatedContent(
                targetState = current,
                modifier = Modifier.fillMaxSize().clipToBounds(),
                contentKey = { it != null },
                transitionSpec = {
                    if (animationsEnabled) InfinityMotion.sharedAxisX(forward = targetState != null)
                    else EnterTransition.None togetherWith ExitTransition.None
                },
                label = "recording_navigation",
            ) { shown ->
                val foreground = (shown == null) == (current == null)
                val view = RecordingView(state, shown, route, playing, positionMs, playbackEnvelope, asrModel, effectiveHotwords, waveform)
                val transcriptTools: @Composable (Boolean) -> Unit = { titled ->
                    TranscriptPaneHeader(view, titled, onDownloadAsr, onCancelDownload, onTranscriptMenu, onCancelRecognize)
                }
                val speakerTools: @Composable () -> Unit = {
                    if (shown?.distinguishSpeakers == true && !view.active) {
                        RecordingSpeakerStatus(shown, state, speakerModel, onAnalyzeSpeakers, onSpeakerSettings)
                    }
                }
                val buttons: @Composable (Boolean) -> Unit = { compact ->
                    RecordingActionButtons(view, compact, onPauseResume, onFinish, onContinue, onReturnActive)
                }
                Box(if (foreground) Modifier.fillMaxSize() else Modifier.fillMaxSize().clearAndSetSemantics {}) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (shown == null) {
                                RecordingWorkspaceHistory(
                                    state, historyList, historyQuery, { historyQuery = it },
                                    onStart = { clearSelection(); onBegin() },
                                    onReturn = { clearSelection(); onReturnActive() },
                                    onSelect = onSelect,
                                    selecting = historySelecting,
                                    selected = eligibleSelection.mapTo(mutableSetOf()) { it.id },
                                    onSelectionChange = {
                                        selectingRecordings = true
                                        selectedRecordings = ArrayList(it)
                                    },
                                    onExitSelection = clearSelection,
                                    onDeleteSelection = {
                                        if (!batchOperation.deleting && batchConfirmation == null && eligibleSelection.isNotEmpty()) {
                                            focus.clearFocus()
                                            batchConfirmation = eligibleSelection
                                            confirmationVisible = true
                                        }
                                    },
                                    enabled = !deleting,
                                    animationsEnabled = animationsEnabled,
                                )
                                RecordingDeleteNotice(deleteResult, animationsEnabled,
                                    Modifier.align(Alignment.BottomCenter).widthIn(max = FailureNoticeMaxWidth))
                            } else if (wide) {
                                RecordingWideBody(
                                    view, onMode, onEdit, onConsent, transcriptTools, onPlay,
                                    onTogglePlayback, onSeek, onSeekFinished, onOrganize, onLanguage, editor, speakerTools, onSpeakerSelect,
                                )
                            } else {
                                RecordingNarrowBody(
                                    view, organized, pinned, onMode, onOrganizedChange, onEdit, onConsent,
                                    transcriptTools, onPlay, onTogglePlayback, onSeek, onSeekFinished, onOrganize, onLanguage, editor,
                                    speakerTools, onSpeakerSelect,
                                )
                            }
                            if (foreground) {
                                // 失败提示只在当前页组合，且浮在操作区上方。
                                RecordingFailureNotice(
                                    failure = state.failure,
                                    onDismiss = onDismissFailure,
                                    modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = FailureNoticeMaxWidth),
                                )
                            }
                        }
                        if (shown != null) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (view.active) {
                                    TextButton(onClick = onDiscard) {
                                        Text(stringResource(R.string.recording_discard), color = MaterialTheme.colorScheme.error)
                                    }
                                    Spacer(Modifier.weight(1f))
                                } else Spacer(Modifier.weight(1f))
                                buttons(false)
                            }
                        }
                    }
                    // 退场内容在过渡期间保持绘制，但不接收点击。
                    if (!foreground) Box(Modifier.matchParentSize().pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    })
                }
            }
        }
    }
    batchConfirmation?.let { confirmed ->
        RecordingBatchDeleteDialog(
            confirmed, confirmationVisible && current == null, animationsEnabled,
            dismiss = { confirmationVisible = false },
            onExit = { batchConfirmation = null },
            onConfirm = {
                if (confirmationVisible && current == null && !batchOperation.deleting) {
                    confirmationVisible = false
                    deleteResult = null
                    batchOperation.submit(confirmed.map { it.id }, onDeleteRecordings)
                }
            },
        )
    }
    LaunchedEffect(deleteResult) {
        if (deleteResult != null) {
            delay(FailureNoticeMillis)
            deleteResult = null
        }
    }
}

/** 覆盖配置变化期间的删除请求，重建后由当前录音页面消费结果。 */
internal class RecordingBatchOperation : ViewModel() {
    var deleting by mutableStateOf(false)
        private set
    var result by mutableStateOf<RecordingBatchDeletion?>(null)
        private set

    fun submit(ids: List<String>, delete: suspend (Collection<String>) -> RecordingBatchDeletion) {
        if (deleting) return
        deleting = true
        viewModelScope.launch {
            try {
                result = delete(ids)
            } finally {
                deleting = false
            }
        }
    }

    fun consumeResult() { result = null }
}

internal class RecordingInlineEditor(val text: String, val onChange: (String) -> Unit, val onSave: () -> Unit, val onCancel: () -> Unit)

@Composable
private fun RecordingNarrowBody(
    view: RecordingView,
    organized: Boolean,
    pinned: Boolean,
    onMode: () -> Unit,
    onOrganizedChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onConsent: () -> Unit,
    transcriptTools: @Composable (Boolean) -> Unit,
    onPlay: (Long) -> Unit,
    onTogglePlayback: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onOrganize: () -> Unit,
    onLanguage: () -> Unit,
    editor: RecordingInlineEditor?,
    speakerTools: @Composable () -> Unit,
    onSpeakerSelect: (RecordingSpeakerSelection) -> Unit,
) {
    val current = requireNotNull(view.current)
    val edge = 16.dp
    val header: @Composable () -> Unit = {
        Column {
            WorkspaceRecorder(view, onTogglePlayback, onSeek, onSeekFinished)
            InfinityPanelTabs(
                tabs = listOf(InfinityPanelTab(false, stringResource(R.string.recording_raw)),
                    InfinityPanelTab(true, stringResource(R.string.recording_organized),
                        dot = MaterialTheme.colorScheme.primary.takeIf { view.organizing })),
                selected = organized, onSelect = onOrganizedChange, bleed = edge,
            )
            if (organized) {
                OrganizedPaneHeader(view, false, onLanguage, onEdit, editor != null)
                if (view.consent) RecordingConsentBar(onConsent)
            } else {
                transcriptTools(false)
                speakerTools()
                if (current.distinguishSpeakers && !view.active && view.state.speakerAnalysisId != current.id) {
                    RecordingSpeakerStrip(current, onSpeakerSelect)
                }
            }
        }
    }
    val pinHeader = pinned || editor != null
    val leading: (@Composable () -> Unit)? = if (pinHeader) null else header
    val padding = PaddingValues(start = edge, end = edge, top = 8.dp, bottom = edge)
    Column(Modifier.fillMaxSize()) {
        if (pinHeader) Box(Modifier.padding(horizontal = edge)) { header() }
        val pane = Modifier.weight(1f).fillMaxWidth()
        if (organized) RecordingOrganizedList(view, leading, pinHeader, padding, onMode, pane, onOrganize, onPlay, editor)
        else RecordingTranscriptList(view, leading, pinHeader, padding, onPlay, pane, onSpeakerSelect)
    }
}

@Composable
private fun RecordingWideBody(
    view: RecordingView,
    onMode: () -> Unit,
    onEdit: () -> Unit,
    onConsent: () -> Unit,
    transcriptTools: @Composable (Boolean) -> Unit,
    onPlay: (Long) -> Unit,
    onTogglePlayback: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onOrganize: () -> Unit,
    onLanguage: () -> Unit,
    editor: RecordingInlineEditor?,
    speakerTools: @Composable () -> Unit,
    onSpeakerSelect: (RecordingSpeakerSelection) -> Unit,
) {
    val padding = PaddingValues(top = 12.dp, bottom = 16.dp)
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        WorkspaceRecorder(view, onTogglePlayback, onSeek, onSeekFinished)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(end = 16.dp)) {
                transcriptTools(true)
                speakerTools()
                RecordingTranscriptList(view, null, true, padding, onPlay, Modifier.weight(1f).fillMaxWidth(), onSpeakerSelect)
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp)) {
                OrganizedPaneHeader(view, true, onLanguage, onEdit, editor != null)
                if (view.consent) RecordingConsentBar(onConsent)
                RecordingOrganizedList(view, null, true, padding, onMode, Modifier.weight(1f).fillMaxWidth(), onOrganize, onPlay, editor)
            }
        }
    }
}

@Composable
private fun WorkspaceRecorder(view: RecordingView, onPlay: () -> Unit, onSeek: (Float) -> Unit, onSeekFinished: () -> Unit) {
    val current = requireNotNull(view.current)
    val elapsed = if (view.active) view.state.durationMs else if (view.playing || view.positionMs > 0) view.positionMs.toLong() else current.durationMs
    Surface(shape = RoundedCornerShape(19.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                modifier = if (view.active) Modifier.widthIn(min = 73.dp) else Modifier,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(recordingTimestamp(elapsed), style = InfinityTypography.headlineSmall.copy(fontFamily = InfinityMonospace),
                    maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (view.active && !view.paused && !view.state.opening) RecordingDot()
                    val status = when {
                        view.state.opening && view.active -> R.string.recording_ui_opening
                        view.active && view.paused -> R.string.recording_paused
                        view.active -> R.string.recording_running
                        view.playing -> R.string.recording_playing_source
                        current.phase == RecordingPhase.Interrupted -> R.string.recording_ui_interrupted
                        else -> R.string.recording_original_audio
                    }
                    Text(stringResource(status), style = InfinityTypography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            if (view.active) {
                key(current.id) {
                    RecordingWaveform(view.waveform, live = !view.paused && !view.state.opening,
                        paused = view.paused, modifier = Modifier.weight(1f).height(32.dp))
                }
            } else {
                val position = view.positionMs.coerceIn(0f, current.durationMs.toFloat())
                val seekLabel = stringResource(R.string.recording_ui_position)
                val duration = current.durationMs.coerceAtLeast(1).toFloat()
                val elapsedColor = MaterialTheme.colorScheme.primary
                val pendingColor = MaterialTheme.colorScheme.outline
                Box(Modifier.weight(1f).height(48.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxWidth().height(32.dp)) {
                        val columns = (size.width / 5.dp.toPx()).toInt().coerceIn(1, 96)
                        val step = size.width / columns
                        repeat(columns) { index ->
                            val x = (index + 0.5f) * step
                            val progress = index.toFloat() / columns
                            val level = view.playbackEnvelope.getOrNull((progress * view.playbackEnvelope.size).toInt()) ?: 0f
                            val half = 1.dp.toPx() + level * size.height * 0.43f
                            drawLine(if (progress <= position / duration) elapsedColor else pendingColor,
                                Offset(x, center.y - half), Offset(x, center.y + half), 2.dp.toPx(), StrokeCap.Round)
                        }
                    }
                    InfinitySlider(value = position, onValueChange = onSeek, onValueChangeFinished = onSeekFinished,
                        valueRange = 0f..duration, thumbSize = 12.dp,
                        modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = 0f }.semantics { contentDescription = seekLabel })
                }
                FilledTonalIconButton(onClick = onPlay, enabled = current.audioBytes > 0 && !current.captureRollbackPending) {
                    Icon(if (view.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        stringResource(if (view.playing) R.string.recording_pause else R.string.recording_play))
                }
            }
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

private val WaveformPitch = 6.dp
private val WaveformBarWidth = 3.dp
private val WaveformMinBar = 4.dp
private val WaveformFade = 30.dp

/** 低于这个电平按静音画成圆点。 */
private const val WaveformSilence = 0.06f

/**
 * 波形只在绘制阶段读取音量和位移。暂停保持最后一帧，系统关闭动画时按采样间距更新。
 */
@Composable
private fun RecordingWaveform(
    waveform: StateFlow<RecordingWaveformSamples>,
    live: Boolean,
    paused: Boolean,
    modifier: Modifier = Modifier,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val voiced = if (paused) muted else MaterialTheme.colorScheme.primary
    val smooth = rememberAnimationsEnabled() && !LocalInspectionMode.current
    val samples = waveform.collectAsStateWithLifecycle()
    val motion = remember(smooth) { RecordingWaveformMotion() }
    var animated by remember(smooth) { mutableStateOf<RecordingWaveformFrame?>(null) }
    LaunchedEffect(live, smooth) {
        try {
            while (live && smooth) {
                withFrameNanos { animated = motion.frame(samples.value, it) }
            }
        } finally {
            motion.pause()
        }
    }
    Canvas(modifier) {
        val frame = animated.takeIf { smooth } ?: RecordingWaveformFrame(samples.value.levels, 0f)
        val levels = frame.levels
        val pitch = WaveformPitch.toPx()
        val bar = WaveformBarWidth.toPx()
        val fade = WaveformFade.toPx()
        val minBar = WaveformMinBar.toPx()
        val offset = frame.offset * pitch
        val slots = ceil(size.width / pitch).toInt() + 2
        clipRect {
            repeat(slots) { slot ->
                val x = size.width - bar / 2 - slot * pitch + offset
                if (x < -bar) return@repeat
                val level = levels.getOrNull(levels.lastIndex - slot)?.coerceIn(0f, 1f) ?: 0f
                val alpha = ((x + bar / 2) / fade).coerceIn(0f, 1f)
                if (level < WaveformSilence) {
                    drawCircle(muted, radius = bar / 2, center = Offset(x, center.y), alpha = alpha * .29f)
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
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onRecognize: () -> Unit,
    onCancelRecognize: () -> Unit,
) {
    if (view.current == null) return
    if (titled || !view.active) RecordingPaneHeader(if (titled) stringResource(R.string.recording_raw) else null) {
        if (!view.active) IconButton(onClick = onRecognize, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Rounded.MoreHoriz, stringResource(R.string.recording_transcript_actions), Modifier.size(18.dp))
        }
    }
    if (titled) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    if (view.recognizing) {
        RecordingWorkProgress(R.string.recording_recognizing, view.state.recognizeProgress, onCancelRecognize)
    }
    val model = view.asrModel
    if (model != RecordingAsrModelState.Installed) {
        BettboxListGroup { RecordingAsrControls(model, onDownload, onCancelDownload, inTranscript = true) }
    }
}

@Composable
private fun OrganizedPaneHeader(view: RecordingView, titled: Boolean, onLanguage: () -> Unit, onEdit: () -> Unit, editing: Boolean) {
    val current = view.current ?: return
    RecordingPaneHeader(if (titled) stringResource(R.string.recording_organized) else null) {
        if (!editing) {
            val next = current.publishedLanguage != null && current.outputLanguage != current.publishedLanguage
            TextButton(onClick = onLanguage) {
                Icon(Icons.Rounded.Language, stringResource(R.string.recording_output_language), Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (next) stringResource(R.string.recording_language_next, current.outputLanguage.label) else current.outputLanguage.label)
            }
            if (!view.active && !view.organizing && current.organized.isNotBlank()) {
                IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, stringResource(R.string.recording_edit_summary)) }
            }
        }
    }
    if (titled) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordingOrganizedList(
    view: RecordingView, leading: (@Composable () -> Unit)?, fill: Boolean, contentPadding: PaddingValues,
    onMode: () -> Unit, modifier: Modifier = Modifier, onOrganize: () -> Unit, onPlay: (Long) -> Unit,
    editor: RecordingInlineEditor?,
) {
    val current = view.current ?: return
    if (editor != null) {
        Column(modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(editor.text, editor.onChange, Modifier.fillMaxWidth().weight(1f),
                textStyle = InfinityTypography.bodyLarge.forInput(), label = { Text(stringResource(R.string.recording_edit_summary)) })
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfinityPillButton(stringResource(R.string.recording_cancel), editor.onCancel)
                InfinityPillButton(stringResource(R.string.recording_save), editor.onSave,
                    enabled = editor.text.isNotBlank(), tone = InfinityPillTone.PRIMARY)
            }
        }
        return
    }
    val list = key(current.id, current.distinguishSpeakers) { rememberLazyListState() }
    val blocks = remember(current.organized) { current.organized.split("\n\n").filter(String::isNotBlank) }
    val segmentStarts = remember(current.segments) { current.segments.mapTo(hashSetOf(), RecordingSegment::startMs) }
    LazyColumn(state = list, modifier = modifier, contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (leading != null) item(key = "recording_header") { RecordingLeading(leading) }
        if (view.active) {
            item(key = "draft_status") {
                val message = when {
                    view.route == RecordingRoute.Raw -> R.string.recording_mode_raw
                    view.consent -> R.string.recording_awaiting_consent
                    view.route == RecordingRoute.Unavailable -> R.string.recording_route_unavailable
                    !current.liveOrganization -> R.string.recording_draft_after
                    else -> R.string.recording_draft_live
                }
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Text(stringResource(message), Modifier.fillMaxWidth().padding(10.dp), style = InfinityTypography.bodySmall)
                }
            }
            if (current.liveOrganization && view.route !in setOf(RecordingRoute.Raw, RecordingRoute.Consent, RecordingRoute.Unavailable)) {
                items(current.draftBatches, key = { it.from }) { batch ->
                    val time = current.segments.getOrNull(batch.from)?.startMs ?: 0L
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(recordingTimestamp(time), style = InfinityTypography.labelMedium.copy(fontFamily = InfinityMonospace),
                            color = MaterialTheme.colorScheme.primary)
                        Text(batch.text, style = InfinityTypography.bodyLarge.forReading())
                    }
                }
                if (view.organizing) item { GenerationDots() }
            }
        } else {
            if (view.organizing) item { Text(stringResource(R.string.recording_final_running), style = InfinityTypography.bodySmall) }
            else if (current.manualEdit && current.organizedThrough < current.segments.size) item {
                TextButton(onClick = onOrganize) { Text(stringResource(R.string.recording_append_summary_pending)) }
            }
            if (current.organized.isNotBlank()) {
                itemsIndexed(blocks) { _, block ->
                    val link = recordingSeekLink
                    val times = link.findAll(block).mapNotNull { it.groupValues[2].toLongOrNull() }
                        .filter { it in segmentStarts }.toList()
                    when {
                        times.isNotEmpty() && link.replace(block, "").isBlank() -> FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            times.forEach { ms -> TextButton(onClick = { onPlay(ms) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text(recordingTimestamp(ms), style = InfinityTypography.labelMedium.copy(fontFamily = InfinityMonospace), maxLines = 1)
                            } }
                        }
                        block.startsWith("### ") -> Text(block.removePrefix("### "), style = InfinityTypography.titleSmall)
                        block.startsWith("**") && block.endsWith("**") -> Text(block.removeSurrounding("**"),
                            color = MaterialTheme.colorScheme.primary, style = InfinityTypography.labelLarge)
                        else -> Text(block, style = InfinityTypography.bodyLarge.forReading())
                    }
                }
            } else if (!view.organizing) item {
                RecordingEmpty(fill && leading == null) {
                    RecordingEmptyLabel(Icons.Rounded.Description, stringResource(if (view.route == RecordingRoute.Raw) R.string.recording_mode_raw else R.string.recording_no_organized))
                    if (current.segments.isNotEmpty()) {
                        TextButton(onClick = if (view.route in setOf(RecordingRoute.Raw, RecordingRoute.Unavailable)) onMode else onOrganize) {
                            Text(stringResource(if (view.route in setOf(RecordingRoute.Raw, RecordingRoute.Unavailable)) R.string.recording_settings else R.string.recording_generate_summary))
                        }
                    }
                }
            }
        }
    }
}

private val recordingSeekLink = Regex("\\[(\\d+:[\\d:]+)]\\(recording://seek/(\\d+)\\)")

@Composable
private fun RecordingWorkspaceHistory(
    state: RecordingScreenState, list: LazyListState, query: String, onQuery: (String) -> Unit,
    onStart: () -> Unit, onReturn: () -> Unit, onSelect: (RecordingSession) -> Unit,
    selecting: Boolean, selected: Set<String>, onSelectionChange: (Set<String>) -> Unit,
    onExitSelection: () -> Unit, onDeleteSelection: () -> Unit, enabled: Boolean, animationsEnabled: Boolean,
) {
    val active = state.sessions.firstOrNull { it.id == state.activeId }
    val today = stringResource(R.string.recording_today)
    val yesterday = stringResource(R.string.recording_yesterday)
    val context = androidx.compose.ui.platform.LocalConfiguration.current
    val dayFormat = remember(context) { DateFormat.getDateInstance(DateFormat.MEDIUM, context.locales[0]) }
    val timeFormat = remember(context) { DateFormat.getTimeInstance(DateFormat.SHORT, context.locales[0]) }
    val groups = remember(state.sessions, state.activeId, query, today, yesterday, dayFormat) {
        fun day(date: Long) = Calendar.getInstance().apply { timeInMillis = date }
            .let { it.get(Calendar.YEAR) to it.get(Calendar.DAY_OF_YEAR) }
        val now = System.currentTimeMillis()
        val yesterdayDate = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
        state.sessions.filter { it.id != state.activeId && it.title.contains(query.trim(), ignoreCase = true) }
            .groupBy { item -> when (day(item.createdAt)) {
                day(now) -> today
                day(yesterdayDate) -> yesterday
                else -> dayFormat.format(Date(item.createdAt))
            } }
    }
    val matching = remember(groups, state.activeId) {
        groups.values.flatten().filter { recordingDeletable(it, state.activeId) }.mapTo(mutableSetOf()) { it.id }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= RecordingWideWidth
        val wideHeader = maxWidth >= (620 * maxOf(1f, LocalDensity.current.fontScale)).dp
        val edge = if (wide) 20.dp else 16.dp
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = edge, end = edge, bottom = 24.dp)) {
            item(key = "recording-start-card") {
                val accent = MaterialTheme.colorScheme.primary
                Surface(shape = RoundedCornerShape(23.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()) {
                    Box {
                        Canvas(Modifier.matchParentSize()) {
                            clipRect(left = size.width * .50f) {
                                val center = Offset(size.width * .79f, size.height * .56f)
                                rotate(-34f, center) {
                                    repeat(19) { index ->
                                        val w = (32 + index * 11).dp.toPx()
                                        val h = w * .65f
                                        drawOval(accent.copy(alpha = (.32f - index * .012f).coerceAtLeast(.065f)),
                                            topLeft = Offset(center.x - w / 2, center.y - h / 2),
                                            size = Size(w, h), style = Stroke(.8.dp.toPx()))
                                    }
                                }
                            }
                        }
                        Column(Modifier.fillMaxWidth().heightIn(min = if (wide) 168.dp else 152.dp)
                            .padding(if (wide) 24.dp else 17.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterVertically)) {
                            Text(stringResource(if (active == null) R.string.recording_new else R.string.recording_active_card),
                                style = InfinityTypography.headlineSmall)
                            Text(active?.title ?: stringResource(R.string.recording_start_description),
                                style = InfinityTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth(if (wide) .55f else .72f))
                            Spacer(Modifier.height(3.dp))
                            InfinityPillButton(stringResource(if (active == null) R.string.recording_start else R.string.recording_return_active),
                                if (active == null) onStart else onReturn, tone = InfinityPillTone.PRIMARY,
                                enabled = state.ready && enabled, icon = Icons.Rounded.Mic)
                        }
                    }
                }
            }
            item(key = "recording-history-gap") { Spacer(Modifier.height(16.dp)) }
            stickyHeader(key = "recording-search") {
                RecordingHistoryHeader(
                    wideHeader, query, onQuery, selecting, selected, matching, onSelectionChange, onExitSelection,
                    onDeleteSelection, enabled, animationsEnabled,
                )
            }
            if (groups.isEmpty()) item(key = "recording-empty") {
                Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                    RecordingEmptyLabel(Icons.Rounded.Mic, stringResource(if (query.isBlank()) R.string.recording_ui_no_history else R.string.recording_search_empty))
                }
            }
            groups.forEach { (day, sessions) ->
                item(key = "date-$day") {
                    Text(day, Modifier.padding(top = 14.dp, bottom = 6.dp), style = InfinityTypography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                itemsIndexed(sessions, key = { _, item -> item.id }) { index, item ->
                    val condition = when {
                        item.phase == RecordingPhase.Interrupted -> R.string.recording_ui_interrupted
                        item.organized.isNotBlank() -> R.string.recording_has_summary
                        item.segments.isNotEmpty() -> R.string.recording_raw
                        else -> R.string.recording_original_audio
                    }
                    val canSelect = item.id in matching
                    val marked = item.id in selected
                    val toggle = { onSelectionChange(if (marked) selected - item.id else selected + item.id) }
                    RecordingHistoryRow(
                        item, listOf(timeFormat.format(Date(item.createdAt)), stringResource(condition)),
                        modifier = Modifier.bettboxListGroupItem(index, sessions.size),
                        selecting = selecting, checked = marked, enabled = enabled && (!selecting || canSelect),
                        onClick = { if (selecting) toggle() else onSelect(item) },
                        onLongClick = if (enabled && !selecting && canSelect) toggle else null,
                        animationsEnabled = animationsEnabled,
                    )
                }
            }
        }
    }
}

private val RecordingSelectionEasing = CubicBezierEasing(.2f, .65f, .35f, 1f)

@Composable
private fun recordingSelectionProgress(visible: Boolean, animationsEnabled: Boolean): State<Float> =
    animateFloatAsState(if (visible) 1f else 0f, if (animationsEnabled) tween(220, easing = RecordingSelectionEasing) else snap(),
        label = "recording_selection")

@Composable
private fun RecordingHistoryHeader(
    wide: Boolean, query: String, onQuery: (String) -> Unit, selecting: Boolean,
    selected: Set<String>, matching: Set<String>, onSelectionChange: (Set<String>) -> Unit,
    onExitSelection: () -> Unit, onDeleteSelection: () -> Unit, enabled: Boolean, animationsEnabled: Boolean,
) {
    val progress = recordingSelectionProgress(selecting, animationsEnabled)
    val hidden = selected.count { it !in matching }
    val all = matching.isNotEmpty() && selected.containsAll(matching)
    val selectLabel = when {
        all -> R.string.recording_selection_clear
        query.isNotBlank() -> R.string.recording_selection_results
        else -> R.string.recording_selection_all
    }
    val search: @Composable (Modifier) -> Unit = { modifier ->
        CompactOutlinedTextField(query, onQuery, modifier.heightIn(min = 48.dp),
            leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)) },
            placeholder = stringResource(R.string.recording_search))
    }
    val heading: @Composable (Modifier) -> Unit = { modifier ->
        val minimumHeight = maxOf(48.dp, with(LocalDensity.current) {
            InfinityTypography.labelLarge.lineHeight.toDp() + InfinityTypography.labelSmall.lineHeight.toDp() + 6.dp
        })
        Box(modifier.heightIn(min = minimumHeight), contentAlignment = Alignment.CenterStart) {
            Text(stringResource(R.string.recording_history), style = InfinityTypography.titleMedium,
                modifier = (if (selecting) Modifier.clearAndSetSemantics {} else Modifier).graphicsLayer {
                    alpha = 1f - progress.value
                    translationY = -5.dp.toPx() * progress.value
                })
            // 只叠放在原栏头。退场保留按钮的尺寸和文案，直到淡出结束。
            var shownCount by remember { mutableStateOf(selected.size) }
            var shownHidden by remember { mutableStateOf(hidden) }
            var shownLabel by remember { mutableStateOf(selectLabel) }
            if (selecting) {
                SideEffect {
                    shownCount = selected.size
                    shownHidden = hidden
                    shownLabel = selectLabel
                }
            }
            if (selecting || progress.value > 0f) {
                Row(
                    Modifier.matchParentSize()
                        .then(if (selecting) Modifier else Modifier.clearAndSetSemantics {})
                        .graphicsLayer { alpha = progress.value; translationY = 5.dp.toPx() * (1f - progress.value) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IconButton(onClick = onExitSelection, enabled = selecting && enabled) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.recording_selection_exit),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                        val count = if (selecting) selected.size else shownCount
                        val outside = if (selecting) hidden else shownHidden
                        Text(pluralStringResource(R.plurals.recording_selection_count, count, count),
                            style = InfinityTypography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (outside > 0) Text(pluralStringResource(R.plurals.recording_selection_hidden, outside, outside),
                            style = InfinityTypography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    InfinityPillButton(stringResource(if (selecting) selectLabel else shownLabel),
                        { if (selecting) onSelectionChange(toggleRecordingSearchSelection(selected, matching)) },
                        compact = true, enabled = enabled && matching.isNotEmpty())
                    InfinityPillButton(stringResource(R.string.recording_ui_delete_confirm), { if (selecting) onDeleteSelection() },
                        compact = true, enabled = enabled && (if (selecting) selected.isNotEmpty() else shownCount > 0),
                        tone = InfinityPillTone.DANGER)
                }
            }
        }
    }
    val surface = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(bottom = 3.dp)
    if (wide) Row(surface, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        heading(Modifier.weight(1f))
        search(Modifier.widthIn(max = 300.dp).fillMaxWidth(.44f))
    } else Column(surface, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        heading(Modifier.fillMaxWidth())
        search(Modifier.fillMaxWidth())
    }
}

@Composable
private fun RecordingHistoryRow(
    item: RecordingSession, subtitle: List<String>, modifier: Modifier,
    selecting: Boolean, checked: Boolean, enabled: Boolean, onClick: () -> Unit,
    onLongClick: (() -> Unit)?, animationsEnabled: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val progress = recordingSelectionProgress(selecting, animationsEnabled)
    val background = animateColorAsState(
        if (checked) scheme.secondaryContainer else scheme.surfaceContainerLow,
        if (animationsEnabled) tween(220, easing = RecordingSelectionEasing) else snap(), label = "recording_selected_color",
    )
    val selectItem = stringResource(R.string.recording_selection_item)
    InfinitySettingsRowLayout(
        title = item.title,
        subtitleSegments = subtitle,
        modifier = modifier.drawBehind { drawRect(background.value) }
            .combinedClickable(enabled = enabled, role = if (selecting) Role.Checkbox else Role.Button,
                onClick = onClick, onLongClick = onLongClick, onLongClickLabel = if (onLongClick != null) selectItem else null)
            .semantics { if (selecting) toggleableState = if (checked) ToggleableState.On else ToggleableState.Off },
        leadingContent = {
            Icon(Icons.Rounded.GraphicEq, null, Modifier.size(InfinitySpacing.RowIconSize).graphicsLayer {
                alpha = 1f - progress.value
            }, tint = scheme.onSurfaceVariant)
            Icon(if (checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, null,
                Modifier.size(InfinitySpacing.RowIconSize).graphicsLayer {
                    alpha = progress.value * if (enabled) 1f else .38f
                    scaleX = .86f + .14f * progress.value
                    scaleY = scaleX
                }, tint = if (checked) scheme.primary else scheme.onSurfaceVariant)
        },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(recordingTimestamp(item.durationMs), style = InfinityTypography.labelMedium.copy(fontFamily = InfinityMonospace))
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp).graphicsLayer { alpha = 1f - progress.value },
                    tint = scheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
private fun RecordingBatchDeleteDialog(
    confirmed: List<RecordingSession>, visible: Boolean, animationsEnabled: Boolean,
    dismiss: () -> Unit, onExit: () -> Unit, onConfirm: () -> Unit,
) {
    val count = confirmed.size
    RecordingDialog(
        pluralStringResource(R.plurals.recording_selection_delete_question, count, count), dismiss,
        icon = Icons.Rounded.DeleteOutline, width = 412.dp, destructive = true,
        dismissEnabled = visible, visible = visible, animationsEnabled = animationsEnabled, onExit = onExit,
        footer = {
            RecordingDialogActions(stringResource(R.string.recording_ui_delete_confirm), onConfirm, dismiss,
                destructive = true, enabled = visible)
        },
    ) {
        Text(stringResource(R.string.recording_ui_delete_detail), style = InfinityTypography.bodyMedium)
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                confirmed.take(3).forEach { Text(it.title, style = InfinityTypography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis) }
                val remaining = count - 3
                if (remaining > 0) Text(pluralStringResource(R.plurals.recording_selection_delete_more, remaining, remaining),
                    style = InfinityTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(stringResource(R.string.recording_selection_keep_documents), style = InfinityTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RecordingDeleteNotice(result: RecordingBatchDeletion?, animationsEnabled: Boolean, modifier: Modifier) {
    var last by remember { mutableStateOf<RecordingBatchDeletion?>(null) }
    SideEffect { if (result != null) last = result }
    AnimatedVisibility(result != null, modifier,
        enter = if (animationsEnabled) fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 2 } else EnterTransition.None,
        exit = if (animationsEnabled) fadeOut(tween(220)) else ExitTransition.None,
    ) {
        val shown = result ?: last ?: return@AnimatedVisibility
        val count = shown.deleted.size
        val failed = shown.failed.size
        val message = when {
            failed > 0 && shown.unavailable > 0 -> stringResource(R.string.recording_selection_delete_partial, count, failed, shown.unavailable)
            failed > 0 -> stringResource(R.string.recording_selection_delete_failed, count, failed)
            count == 0 -> stringResource(R.string.recording_selection_delete_unavailable)
            shown.unavailable > 0 -> stringResource(R.string.recording_selection_delete_skipped, count, shown.unavailable)
            else -> pluralStringResource(R.plurals.recording_selection_deleted, count, count)
        }
        InfinitySnackbar(InfinitySnackbarData(message, if (failed > 0) SnackbarType.ERROR else if (count > 0) SnackbarType.SUCCESS else SnackbarType.INFO),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
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

@Composable
private fun RecordingActionButtons(
    view: RecordingView, compact: Boolean, onPauseResume: () -> Unit, onFinish: () -> Unit,
    onContinue: () -> Unit, onReturnActive: () -> Unit,
) {
    if (view.active) {
        InfinityPillButton(stringResource(if (view.paused) R.string.recording_continue_action else R.string.recording_pause),
            onPauseResume, compact = compact, icon = if (view.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause)
        InfinityPillButton(stringResource(R.string.recording_finish_save), onFinish, compact = compact,
            tone = InfinityPillTone.PRIMARY, icon = Icons.Rounded.Stop)
    } else if (view.state.activeId != null) {
        InfinityPillButton(stringResource(R.string.recording_return_active), onReturnActive, icon = Icons.Rounded.Mic)
    } else if (view.current?.canContinue == true) {
        InfinityPillButton(stringResource(R.string.recording_resume), onContinue, icon = Icons.Rounded.Mic, enabled = !view.recognizing)
    }
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
