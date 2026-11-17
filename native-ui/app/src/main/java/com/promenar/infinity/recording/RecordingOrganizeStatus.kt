package com.promenar.infinity.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPanelTab
import com.promenar.infinity.ui.common.InfinityPanelTabs
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.ModelBrandIcon
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.forReading
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

internal enum class RecordingOrganizeTone { Neutral, Running, Warning, Error }

internal data class RecordingOrganizeCardAction(
    val label: String,
    val onClick: () -> Unit,
)

internal data class RecordingOrganizeCardModel(
    val tone: RecordingOrganizeTone,
    val icon: ImageVector?,
    val titleSegments: List<String>,
    val engine: RecordingOrganizeEngine?,
    val metaSegments: List<String> = emptyList(),
    val progress: Float? = null,
    val note: String? = null,
    val primaryAction: RecordingOrganizeCardAction? = null,
    val secondaryAction: RecordingOrganizeCardAction? = null,
    val spinning: Boolean = false,
)

@Composable
internal fun rememberRecordingOrganizeNowMs(
    session: RecordingSession?,
    screenState: RecordingScreenState,
): Long {
    val active = session != null && screenState.activeId == session.id
    val running = session != null && !active && (
        screenState.organizeRun?.let {
            it.sessionId == session.id && it.stage != RecordingOrganizeStage.LiveDraft && it.startedAtEpochMs > 0L
        } == true
    )
    val retryPending = session != null && !active && session.lastOrganizeFailure?.retryAtEpochMs != null
    val needsTick = !LocalInspectionMode.current && (running || retryPending)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session?.id, running, retryPending, needsTick) {
        now = System.currentTimeMillis()
        while (needsTick) {
            delay(1_000L)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
internal fun formatRecordingOrganizeTime(
    epochMs: Long,
    includeTodayPrefix: Boolean = true,
): String {
    if (epochMs <= 0L) return ""
    val configuration = LocalConfiguration.current
    val timeFormat = remember(configuration) {
        DateFormat.getTimeInstance(DateFormat.SHORT, configuration.locales[0])
    }
    val dateTimeFormat = remember(configuration) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, configuration.locales[0])
    }
    val todayAndNow = remember(epochMs, configuration) {
        val cal = Calendar.getInstance()
        val nowYear = cal.get(Calendar.YEAR)
        val nowDay = cal.get(Calendar.DAY_OF_YEAR)
        cal.timeInMillis = epochMs
        cal.get(Calendar.YEAR) == nowYear && cal.get(Calendar.DAY_OF_YEAR) == nowDay
    }
    return if (todayAndNow) {
        val formattedTime = timeFormat.format(Date(epochMs))
        if (includeTodayPrefix) stringResource(R.string.recording_organize_time_today, formattedTime)
        else formattedTime
    } else {
        dateTimeFormat.format(Date(epochMs))
    }
}

@Composable
internal fun RecordingOrganizeEngine.segments(): List<String> {
    val routeText = stringResource(if (cloud) R.string.recording_organize_route_cloud else R.string.recording_organize_route_local)
    val modelText = model.ifBlank { stringResource(R.string.recording_organize_unknown_model) }
    return buildList {
        add(routeText)
        if (cloud && provider.isNotBlank()) add(provider)
        add(modelText)
    }
}

@Composable
private fun RecordingOrganizeEngine.inlineDescription(): String = segments().joinToString(" ")

@Composable
internal fun recordingOrganizeSourceLabel(source: RecordingOrganizeSource): String {
    val timeText = formatRecordingOrganizeTime(source.completedAt, includeTodayPrefix = true)
    val engineText = source.engine.inlineDescription()
    return if (timeText.isNotBlank()) "$timeText $engineText" else engineText
}

@Composable
internal fun recordingOrganizeCardModel(
    state: RecordingOrganizeState,
    current: RecordingSession,
    localReady: Boolean,
    onRetry: () -> Unit,
    onReorganizeWithConfirm: () -> Unit,
    onCancel: () -> Unit,
    onConsent: () -> Unit,
    onSwitchToLocal: () -> Unit,
    onSettings: () -> Unit,
): RecordingOrganizeCardModel? {
    val canSwitchLocal = localReady && current.mode != RecordingMode.Local
    return when (state) {
        is RecordingOrganizeState.LiveDraft -> RecordingOrganizeCardModel(
            tone = RecordingOrganizeTone.Running,
            icon = null,
            titleSegments = listOf(
                stringResource(R.string.recording_organize_live_title),
                stringResource(R.string.recording_organize_live_covered, recordingTimestamp(state.coveredAudioMs)),
            ),
            engine = state.engine,
            metaSegments = listOf(stringResource(R.string.recording_organize_live_lag, state.lagSeconds)),
            note = stringResource(R.string.recording_organize_live_note),
            spinning = state.running,
        )

        is RecordingOrganizeState.LiveAfterRecording -> RecordingOrganizeCardModel(
            tone = RecordingOrganizeTone.Neutral,
            icon = Icons.Rounded.Schedule,
            titleSegments = listOf(stringResource(R.string.recording_draft_after)),
            engine = state.engine,
            note = stringResource(R.string.recording_organize_live_note),
        )

        is RecordingOrganizeState.RunningFirstTime -> RecordingOrganizeCardModel(
            tone = RecordingOrganizeTone.Running,
            icon = null,
            titleSegments = buildList {
                add(stringResource(R.string.recording_organize_running_title))
                if (state.run.totalSteps > 1) {
                    add(stringResource(R.string.recording_organize_step_of, state.run.step, state.run.totalSteps))
                }
            },
            engine = state.run.engine,
            metaSegments = buildList {
                add(stageLabel(state.run.stage))
                if (state.run.startedAtEpochMs > 0L) {
                    add(stringResource(R.string.recording_organize_elapsed_sec, state.elapsedSeconds))
                }
            },
            progress = state.progress,
            primaryAction = RecordingOrganizeCardAction(stringResource(R.string.recording_cancel), onCancel),
            spinning = true,
        )

        is RecordingOrganizeState.RunningRerun -> {
            val prevNote = state.previousSource?.let {
                stringResource(R.string.recording_organize_rerun_note, recordingOrganizeSourceLabel(it))
            } ?: stringResource(R.string.recording_organize_rerun_note_default)
            RecordingOrganizeCardModel(
                tone = RecordingOrganizeTone.Running,
                icon = null,
                titleSegments = buildList {
                    add(stringResource(R.string.recording_organize_rerun_title))
                    if (state.run.totalSteps > 1) {
                        add(stringResource(R.string.recording_organize_step_of, state.run.step, state.run.totalSteps))
                    }
                },
                engine = state.run.engine,
                metaSegments = buildList {
                    add(stageLabel(state.run.stage))
                    if (state.run.startedAtEpochMs > 0L) {
                        add(stringResource(R.string.recording_organize_elapsed_sec, state.elapsedSeconds))
                    }
                },
                progress = state.progress,
                note = prevNote,
                primaryAction = RecordingOrganizeCardAction(stringResource(R.string.recording_cancel), onCancel),
                spinning = true,
            )
        }

        is RecordingOrganizeState.FailedKeptOld -> {
            val keptText = state.currentSource?.let {
                stringResource(R.string.recording_organize_failed_kept_note, formatRecordingOrganizeTime(it.completedAt, includeTodayPrefix = true))
            } ?: stringResource(R.string.recording_organize_failed_kept_default)
            val retrySuffix = failureRetryNote(state.retryRemainingSeconds)
            val noteText = if (retrySuffix != null) "$keptText $retrySuffix" else keptText
            val failedWaitSec = (state.failure.elapsedMs / 1000L).toInt()
            RecordingOrganizeCardModel(
                tone = RecordingOrganizeTone.Error,
                icon = Icons.Rounded.ErrorOutline,
                titleSegments = listOf(
                    stringResource(R.string.recording_organize_failed_title),
                    errorKindLabel(state.failure.kind),
                ),
                engine = state.engine,
                metaSegments = buildList {
                    val failTime = formatRecordingOrganizeTime(state.failure.failedAt, includeTodayPrefix = false)
                    if (failTime.isNotBlank()) add(failTime)
                    if (failedWaitSec > 0) add(stringResource(R.string.recording_organize_failed_wait_sec, failedWaitSec))
                },
                note = noteText,
                primaryAction = RecordingOrganizeCardAction(stringResource(R.string.recording_organize_retry), onRetry),
                secondaryAction = if (canSwitchLocal) {
                    RecordingOrganizeCardAction(stringResource(R.string.recording_organize_switch_local), onSwitchToLocal)
                } else {
                    RecordingOrganizeCardAction(stringResource(R.string.recording_organize_go_settings), onSettings)
                },
            )
        }

        is RecordingOrganizeState.FailedEmpty -> {
            val baseNote = stringResource(
                if (state.canSwitchToLocal && canSwitchLocal) R.string.recording_organize_failed_empty_note_switch
                else R.string.recording_organize_failed_empty_note,
            )
            val retrySuffix = failureRetryNote(state.retryRemainingSeconds)
            val noteText = if (retrySuffix != null) "$baseNote $retrySuffix" else baseNote
            val failedWaitSec = (state.failure.elapsedMs / 1000L).toInt()
            RecordingOrganizeCardModel(
                tone = RecordingOrganizeTone.Error,
                icon = Icons.Rounded.ErrorOutline,
                titleSegments = listOf(
                    stringResource(R.string.recording_organize_failed_title),
                    errorKindLabel(state.failure.kind),
                ),
                engine = state.engine,
                metaSegments = buildList {
                    val failTime = formatRecordingOrganizeTime(state.failure.failedAt, includeTodayPrefix = false)
                    if (failTime.isNotBlank()) add(failTime)
                    if (failedWaitSec > 0) add(stringResource(R.string.recording_organize_failed_wait_sec, failedWaitSec))
                },
                note = noteText,
                primaryAction = RecordingOrganizeCardAction(stringResource(R.string.recording_organize_retry), onRetry),
                secondaryAction = if (state.canSwitchToLocal && canSwitchLocal) {
                    RecordingOrganizeCardAction(stringResource(R.string.recording_organize_switch_local), onSwitchToLocal)
                } else {
                    RecordingOrganizeCardAction(stringResource(R.string.recording_organize_go_settings), onSettings)
                },
            )
        }

        is RecordingOrganizeState.AwaitingConsent -> RecordingOrganizeCardModel(
            tone = RecordingOrganizeTone.Warning,
            icon = Icons.Rounded.Lock,
            titleSegments = listOf(stringResource(R.string.recording_organize_consent_title)),
            engine = state.engine,
            note = stringResource(R.string.recording_organize_consent_note),
            primaryAction = RecordingOrganizeCardAction(stringResource(R.string.recording_organize_consent_allow), onConsent),
            secondaryAction = if (state.canSwitchToLocal && !state.activeRecording) {
                RecordingOrganizeCardAction(stringResource(R.string.recording_organize_switch_local), onSwitchToLocal)
            } else {
                RecordingOrganizeCardAction(stringResource(R.string.recording_organize_go_settings), onSettings)
            },
        )

        is RecordingOrganizeState.WaitingLocal -> RecordingOrganizeCardModel(
            tone = RecordingOrganizeTone.Neutral,
            icon = Icons.Rounded.PhoneAndroid,
            titleSegments = listOf(stringResource(R.string.recording_organize_wait_local_title)),
            engine = state.engine,
            note = stringResource(R.string.recording_organize_wait_local_note),
        )

        is RecordingOrganizeState.WaitingCloud -> {
            val noteRes = when {
                state.waitingPreprocess -> R.string.recording_organize_wait_cloud_preprocess_note
                state.queuedBehindOther -> R.string.recording_organize_wait_cloud_queue_note
                else -> R.string.recording_organize_wait_cloud_ready_note
            }
            RecordingOrganizeCardModel(
                tone = RecordingOrganizeTone.Neutral,
                icon = Icons.Rounded.Schedule,
                titleSegments = listOf(stringResource(R.string.recording_organize_wait_cloud_title)),
                engine = state.engine,
                note = stringResource(noteRes),
                primaryAction = if (!state.waitingPreprocess) {
                    RecordingOrganizeCardAction(stringResource(R.string.recording_organize_start_now), onRetry)
                } else null,
            )
        }

        is RecordingOrganizeState.WaitingSpeakers -> {
            val pct = (state.progressFraction * 100f).toInt().coerceIn(0, 100)
            RecordingOrganizeCardModel(
                tone = RecordingOrganizeTone.Neutral,
                icon = Icons.Rounded.RecordVoiceOver,
                titleSegments = listOf(stringResource(R.string.recording_organize_wait_speakers_title)),
                engine = state.engine,
                metaSegments = if (pct > 0) listOf(stringResource(R.string.recording_organize_speakers_progress, pct)) else emptyList(),
                progress = state.progressFraction.takeIf { it > 0f }?.coerceIn(0f, 1f),
                note = stringResource(R.string.recording_organize_wait_speakers_note),
            )
        }

        is RecordingOrganizeState.Unavailable -> RecordingOrganizeCardModel(
            tone = RecordingOrganizeTone.Warning,
            icon = Icons.Rounded.CloudOff,
            titleSegments = listOf(
                stringResource(R.string.recording_organize_unavailable_title),
                stringResource(R.string.recording_organize_unavailable_reason),
            ),
            engine = null,
            note = stringResource(R.string.recording_organize_unavailable_note),
            primaryAction = RecordingOrganizeCardAction(stringResource(R.string.recording_organize_open_settings), onSettings),
        )

        is RecordingOrganizeState.StaleNewSegments -> {
            val title = if (state.canceled) {
                stringResource(R.string.recording_organize_stale_canceled)
            } else {
                stringResource(R.string.recording_organize_stale_segments, state.addedSegments.coerceAtLeast(1))
            }
            val meta = if (!state.canceled && state.addedDurationMs > 0L) {
                listOf(stringResource(R.string.recording_organize_stale_append_duration, recordingTimestamp(state.addedDurationMs)))
            } else emptyList()
            val actionLabel = if (state.canceled && current.organized.isBlank()) {
                stringResource(R.string.recording_generate_summary)
            } else {
                stringResource(R.string.recording_organize_stale_append_action)
            }
            val onPrimary = if (!state.canceled && current.manualEdit && current.organized.isNotBlank()) {
                onReorganizeWithConfirm
            } else {
                onRetry
            }
            RecordingOrganizeCardModel(
                tone = RecordingOrganizeTone.Neutral,
                icon = Icons.Rounded.Schedule,
                titleSegments = listOf(title),
                engine = state.engine,
                metaSegments = meta,
                note = if (state.canceled) {
                    stringResource(R.string.recording_organize_stale_canceled_note, current.segments.size)
                } else null,
                primaryAction = RecordingOrganizeCardAction(actionLabel, onPrimary),
            )
        }

        is RecordingOrganizeState.RawOnly -> RecordingOrganizeCardModel(
            tone = RecordingOrganizeTone.Neutral,
            icon = Icons.AutoMirrored.Rounded.Notes,
            titleSegments = listOf(stringResource(R.string.recording_organize_raw_title)),
            engine = null,
            note = stringResource(R.string.recording_organize_raw_note),
            primaryAction = RecordingOrganizeCardAction(stringResource(R.string.recording_organize_enable_organize), onSettings),
        )

        is RecordingOrganizeState.Completed,
        RecordingOrganizeState.IdleEmpty,
        -> null
    }
}

@Composable
private fun stageLabel(stage: RecordingOrganizeStage): String = when (stage) {
    RecordingOrganizeStage.LiveDraft -> stringResource(R.string.recording_organize_live_title)
    RecordingOrganizeStage.Points -> stringResource(R.string.recording_organize_stage_points)
    RecordingOrganizeStage.Overview -> stringResource(R.string.recording_organize_stage_overview)
    RecordingOrganizeStage.Publish -> stringResource(R.string.recording_organize_stage_publish)
}

@Composable
private fun errorKindLabel(kind: RecordingOrganizeErrorKind): String = when (kind) {
    RecordingOrganizeErrorKind.Timeout -> stringResource(R.string.recording_organize_error_timeout)
    RecordingOrganizeErrorKind.ModelError -> stringResource(R.string.recording_organize_error_model)
    RecordingOrganizeErrorKind.LanguageMismatch -> stringResource(R.string.recording_organize_error_language)
    RecordingOrganizeErrorKind.EmptyOutput -> stringResource(R.string.recording_organize_error_empty)
    RecordingOrganizeErrorKind.StorageError -> stringResource(R.string.recording_organize_error_storage)
}

@Composable
private fun failureRetryNote(remainingSeconds: Int?): String? = when {
    remainingSeconds == null -> null
    remainingSeconds > 0 -> stringResource(R.string.recording_organize_retry_countdown_sec, remainingSeconds)
    else -> stringResource(R.string.recording_organize_retry_pending_soon)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordingOrganizeStatusCard(
    model: RecordingOrganizeCardModel,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val (container, accent) = when (model.tone) {
        RecordingOrganizeTone.Neutral -> scheme.surfaceContainer to scheme.onSurfaceVariant
        RecordingOrganizeTone.Running -> scheme.surfaceContainer to scheme.primary
        RecordingOrganizeTone.Warning -> domain.warningContainer to domain.warning
        RecordingOrganizeTone.Error -> scheme.errorContainer.copy(alpha = .55f) to scheme.error
    }
    val titleColor = if (model.tone == RecordingOrganizeTone.Warning) domain.onWarningContainer else scheme.onSurface
    val bodyColor = if (model.tone == RecordingOrganizeTone.Warning) {
        domain.onWarningContainer.copy(alpha = .82f)
    } else {
        scheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = container,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RecordingOrganizeStatusBadge(model, accent)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    InfinityMetaLine(
                        segments = model.titleSegments,
                        style = InfinityTypography.titleSmall,
                        color = titleColor,
                    )
                    val meta = model.engine?.segments().orEmpty() + model.metaSegments
                    if (meta.isNotEmpty()) {
                        InfinityMetaLine(
                            segments = meta,
                            style = InfinityTypography.bodySmall,
                            color = bodyColor,
                        )
                    }
                }
            }
            model.progress?.let { value ->
                LinearProgressIndicator(
                    progress = { value },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = accent,
                    trackColor = scheme.surfaceContainerHighest,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
            model.note?.let {
                Text(it, style = InfinityTypography.bodySmall.forReading(), color = bodyColor)
            }
            if (model.primaryAction != null || model.secondaryAction != null) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    model.secondaryAction?.let {
                        InfinityPillButton(it.label, it.onClick, compact = true)
                    }
                    model.primaryAction?.let {
                        InfinityPillButton(
                            text = it.label,
                            onClick = it.onClick,
                            compact = true,
                            tone = if (model.tone == RecordingOrganizeTone.Running) {
                                InfinityPillTone.NEUTRAL
                            } else {
                                InfinityPillTone.PRIMARY
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingOrganizeStatusBadge(
    model: RecordingOrganizeCardModel,
    accent: Color,
) {
    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
        val brandModel = model.engine?.model?.takeIf { it.isNotBlank() }
        when {
            model.spinning -> {
                CircularProgressIndicator(Modifier.size(36.dp), color = accent, strokeWidth = 2.dp)
                if (brandModel != null) ModelBrandIcon(brandModel, Modifier.size(18.dp))
            }
            model.icon != null -> Box(
                Modifier.size(36.dp).background(accent.copy(alpha = .14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(model.icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = accent)
            }
            brandModel != null -> Box(
                Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                ModelBrandIcon(brandModel, Modifier.size(20.dp))
            }
        }
    }
}

/** 整理稿已完成时页头下方的来源行：模型品牌图标、路线与模型、完成时间，以及刚更新或已编辑标记。 */
@Composable
internal fun RecordingProvenanceRow(
    source: RecordingOrganizeSource?,
    justUpdated: Boolean,
    manualEdit: Boolean,
    modifier: Modifier = Modifier,
) {
    if (source == null && !justUpdated && !manualEdit) return
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val timeText = source?.let { formatRecordingOrganizeTime(it.completedAt, includeTodayPrefix = true) }.orEmpty()
    val metaSegments = buildList {
        if (source != null) {
            addAll(source.engine.segments())
            if (timeText.isNotBlank()) add(timeText)
        }
        if (manualEdit) add(stringResource(R.string.recording_organize_edited_badge))
    }
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (source != null) {
            ModelBrandIcon(source.model.ifBlank { null }, Modifier.size(16.dp))
        }
        if (metaSegments.isNotEmpty()) {
            InfinityMetaLine(
                segments = metaSegments,
                modifier = Modifier.weight(1f),
                style = InfinityTypography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (justUpdated) {
            Surface(shape = RoundedCornerShape(50), color = domain.successContainer) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(14.dp), tint = domain.success)
                    Text(
                        stringResource(R.string.recording_organize_updated_badge),
                        style = InfinityTypography.labelSmall,
                        color = domain.onSuccessContainer,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 正在重新整理或整理失败保留旧稿时，正文上方的版本来源标签。 */
@Composable
internal fun RecordingVersionLabel(
    isPrevious: Boolean,
    source: RecordingOrganizeSource?,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(if (isPrevious) R.string.recording_organize_version_old else R.string.recording_organize_version_current)
    val timeText = source?.let { formatRecordingOrganizeTime(it.completedAt, includeTodayPrefix = true) }.orEmpty()
    val segments = buildList {
        add(label)
        if (timeText.isNotBlank()) add(timeText)
        if (source != null) {
            addAll(source.engine.segments())
        }
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Rounded.History, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        InfinityMetaLine(
            segments = segments,
            style = InfinityTypography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// —— Compose Preview（用于侧栏高保真复核） ——

@Composable
private fun OrganizeStatusPreviewFrame(
    mode: InfinityThemeMode = InfinityThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize(), content = content)
    }
}

@Preview(name = "整理状态全集 正式 深中360", widthDp = 360, heightDp = 2450, locale = "zh-rCN")
@Composable
internal fun RecordingOrganizeStatusGalleryPreview() = OrganizeStatusPreviewFrame {
    val cloud = RecordingOrganizeEngine(cloud = true, provider = "DeepSeek", model = "deepseek-v4-flash")
    val local = RecordingOrganizeEngine(cloud = false, model = "Qwen3-4B (Q8_0)")
    val now = 1_763_000_200_000L
    val oldSource = RecordingOrganizeSource(cloud = false, model = "Qwen3-4B", completedAt = now - 240_000L)
    val session = RecordingSession(
        id = "preview-1",
        createdAt = now - 600_000L,
        title = "产品周会",
        phase = RecordingPhase.Finished,
        audioBytes = 320_000L * 32,
        segments = listOf(
            RecordingSegment(0L, 12_000L, "第一段"),
            RecordingSegment(12_000L, 28_000L, "第二段"),
        ),
        organized = "旧稿正文",
        organizedThrough = 2,
        organizedSource = oldSource,
    )
    val items = listOf<Pair<String, @Composable () -> Unit>>(
        "① 录音中 · 实时草稿" to {
            val model = recordingOrganizeCardModel(
                RecordingOrganizeState.LiveDraft(cloud, coveredAudioMs = 200_000L, lagSeconds = 12, running = true),
                session, true, {}, {}, {}, {}, {}, {},
            )!!
            RecordingOrganizeStatusCard(model)
        },
        "② 首次生成 · 分步进度" to {
            val run = RecordingOrganizeRun("preview-1", cloud, RecordingOrganizeStage.Points, 2, 4, now - 18_000L)
            val model = recordingOrganizeCardModel(
                RecordingOrganizeState.RunningFirstTime(run, 18, .50f),
                session.copy(organized = ""), true, {}, {}, {}, {}, {}, {},
            )!!
            RecordingOrganizeStatusCard(model)
        },
        "③ 重新整理 · 保留旧版直到成功" to {
            val run = RecordingOrganizeRun("preview-1", cloud, RecordingOrganizeStage.Overview, 3, 4, now - 41_000L)
            val model = recordingOrganizeCardModel(
                RecordingOrganizeState.RunningRerun(run, 41, .75f, oldSource),
                session, true, {}, {}, {}, {}, {}, {},
            )!!
            RecordingOrganizeStatusCard(model)
        },
        "④ 失败 · 旧版未被替换" to {
            val fail = RecordingOrganizeFailure(RecordingOrganizeErrorKind.Timeout, cloud, now, 75_000L, now + 60_000L)
            val model = recordingOrganizeCardModel(
                RecordingOrganizeState.FailedKeptOld(fail, cloud, 60, oldSource),
                session, true, {}, {}, {}, {}, {}, {},
            )!!
            RecordingOrganizeStatusCard(model)
        },
        "⑤ 失败 · 尚无整理稿" to {
            val fail = RecordingOrganizeFailure(RecordingOrganizeErrorKind.ModelError, cloud, now, 4_000L, now + 60_000L)
            val model = recordingOrganizeCardModel(
                RecordingOrganizeState.FailedEmpty(fail, cloud, 60, canSwitchToLocal = true),
                session.copy(organized = ""), true, {}, {}, {}, {}, {}, {},
            )!!
            RecordingOrganizeStatusCard(model)
        },
        "⑥ 等待授权云端" to {
            val model = recordingOrganizeCardModel(
                RecordingOrganizeState.AwaitingConsent(cloud, activeRecording = false, canSwitchToLocal = true),
                session, true, {}, {}, {}, {}, {}, {},
            )!!
            RecordingOrganizeStatusCard(model)
        },
        "⑦ 等待本地（需前台）" to {
            val model = recordingOrganizeCardModel(
                RecordingOrganizeState.WaitingLocal(local),
                session.copy(mode = RecordingMode.Local), true, {}, {}, {}, {}, {}, {},
            )!!
            RecordingOrganizeStatusCard(model)
        },
        "⑧ 等待云端（排队/前置处理）" to {
            val model = recordingOrganizeCardModel(
                RecordingOrganizeState.WaitingCloud(cloud, waitingPreprocess = true),
                session, true, {}, {}, {}, {}, {}, {},
            )!!
            RecordingOrganizeStatusCard(model)
        },
        "⑨ 等待说话人分析" to {
            val model = recordingOrganizeCardModel(
                RecordingOrganizeState.WaitingSpeakers(cloud, .64f),
                session, true, {}, {}, {}, {}, {}, {},
            )!!
            RecordingOrganizeStatusCard(model)
        },
        "⑩ 无可用模型" to {
            val model = recordingOrganizeCardModel(
                RecordingOrganizeState.Unavailable(activeRecording = false),
                session, false, {}, {}, {}, {}, {}, {},
            )!!
            RecordingOrganizeStatusCard(model)
        },
        "⑪ 续录后有新内容" to {
            val model = recordingOrganizeCardModel(
                RecordingOrganizeState.StaleNewSegments(cloud, addedSegments = 3, addedDurationMs = 102_000L, canceled = false),
                session, true, {}, {}, {}, {}, {}, {},
            )!!
            RecordingOrganizeStatusCard(model)
        },
        "⑫ 仅转写" to {
            val model = recordingOrganizeCardModel(
                RecordingOrganizeState.RawOnly(activeRecording = false),
                session.copy(mode = RecordingMode.Raw), true, {}, {}, {}, {}, {}, {},
            )!!
            RecordingOrganizeStatusCard(model)
        },
        "⑬ 成功 · 来源行" to {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RecordingProvenanceRow(
                    RecordingOrganizeSource(cloud = true, provider = "DeepSeek", model = "deepseek-v4-flash", completedAt = now),
                    justUpdated = true,
                    manualEdit = false,
                )
                RecordingProvenanceRow(oldSource, justUpdated = false, manualEdit = false)
            }
        },
    )
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items.forEach { (label, body) ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, style = InfinityTypography.labelMedium, color = MaterialTheme.colorScheme.primary)
                body()
            }
        }
    }
}

@Preview(name = "外屏 失败保留旧版 正式 深中360", widthDp = 360, heightDp = 640, locale = "zh-rCN")
@Composable
internal fun RecordingOrganizeNarrowFailedPreview() = OrganizeStatusPreviewFrame {
    val cloud = RecordingOrganizeEngine(cloud = true, provider = "DeepSeek", model = "deepseek-v4-flash")
    val now = 1_763_000_200_000L
    val oldSource = RecordingOrganizeSource(cloud = false, model = "Qwen3-4B", completedAt = now - 240_000L)
    val fail = RecordingOrganizeFailure(RecordingOrganizeErrorKind.Timeout, cloud, now, 75_000L, now + 60_000L)
    val session = RecordingSession(id = "preview-f", createdAt = now, title = "周会", organized = "正文", organizedSource = oldSource, lastOrganizeFailure = fail)
    val model = recordingOrganizeCardModel(
        RecordingOrganizeState.FailedKeptOld(fail, cloud, 60, oldSource),
        session, true, {}, {}, {}, {}, {}, {},
    )!!
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InfinityPanelTabs(
            tabs = listOf(InfinityPanelTab(false, "逐字稿"), InfinityPanelTab(true, "整理稿", dot = MaterialTheme.colorScheme.error)),
            selected = true,
            onSelect = {},
        )
        RecordingOrganizeStatusCard(model)
        RecordingVersionLabel(isPrevious = false, source = oldSource)
        Text("会议结论", style = InfinityTypography.titleSmall)
        Text("本周明确了 SenseVoice 二遍精修在不同内存机型上的自动分级参数。", style = InfinityTypography.bodyLarge.forReading())
    }
}

@Preview(name = "外屏 失败保留旧版 浅英360", widthDp = 360, heightDp = 640, locale = "en")
@Composable
internal fun RecordingOrganizeNarrowFailedLightEnPreview() = OrganizeStatusPreviewFrame(InfinityThemeMode.LIGHT) {
    val cloud = RecordingOrganizeEngine(cloud = true, provider = "DeepSeek", model = "deepseek-v4-flash")
    val now = 1_763_000_200_000L
    val oldSource = RecordingOrganizeSource(cloud = false, model = "Qwen3-4B", completedAt = now - 240_000L)
    val fail = RecordingOrganizeFailure(RecordingOrganizeErrorKind.Timeout, cloud, now, 75_000L, now + 60_000L)
    val session = RecordingSession(id = "preview-f-en", createdAt = now, title = "Sync", organized = "Body", organizedSource = oldSource, lastOrganizeFailure = fail)
    val model = recordingOrganizeCardModel(
        RecordingOrganizeState.FailedKeptOld(fail, cloud, 60, oldSource),
        session, true, {}, {}, {}, {}, {}, {},
    )!!
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InfinityPanelTabs(
            tabs = listOf(InfinityPanelTab(false, "Transcript"), InfinityPanelTab(true, "Cleaned up", dot = MaterialTheme.colorScheme.error)),
            selected = true,
            onSelect = {},
        )
        RecordingOrganizeStatusCard(model)
        RecordingVersionLabel(isPrevious = false, source = oldSource)
        Text("Key Decisions", style = InfinityTypography.titleSmall)
        Text("Confirmed automatic RAM-tier thresholds for SenseVoice two-pass refinement.", style = InfinityTypography.bodyLarge.forReading())
    }
}

@Preview(name = "内屏 首次生成 正式 深中840", widthDp = 840, heightDp = 520, locale = "zh-rCN")
@Composable
internal fun RecordingOrganizeWideRunningPreview() = OrganizeStatusPreviewFrame {
    val cloud = RecordingOrganizeEngine(cloud = true, provider = "DeepSeek", model = "deepseek-v4-flash")
    val now = 1_763_000_200_000L
    val run = RecordingOrganizeRun("preview-w", cloud, RecordingOrganizeStage.Points, 2, 4, now - 18_000L)
    val model = recordingOrganizeCardModel(
        RecordingOrganizeState.RunningFirstTime(run, 18, .50f),
        RecordingSession(id = "preview-w", createdAt = now, title = "周会"), true, {}, {}, {}, {}, {}, {},
    )!!
    Row(Modifier.fillMaxSize().padding(16.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("逐字稿", style = InfinityTypography.titleSmall)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            listOf(
                "00:00  林妍  本周先核对二遍精修在不同内存档位下的退避阈值。",
                "00:24  陈默  连续两段 RTF 超过 0.65 会自动降级到纯流式识别。",
            ).forEach {
                Text(it, style = InfinityTypography.bodyLarge.forReading(), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("整理稿", style = InfinityTypography.titleSmall)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            RecordingOrganizeStatusCard(model)
        }
    }
}
