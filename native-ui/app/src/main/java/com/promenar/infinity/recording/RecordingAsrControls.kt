package com.promenar.infinity.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityEmbeddedNoteCard
import com.promenar.infinity.ui.common.InfinityEmbeddedNoteRow
import com.promenar.infinity.ui.common.InfinityNoteTone
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography

@Composable
internal fun RecordingAsrControls(
    state: RecordingAsrModelState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    inTranscript: Boolean = false,
    speaker: Boolean = false,
) {
    val spec = RecordingAsrPackage.CURRENT
    val modelTitle = if (speaker) R.string.recording_speaker_model else R.string.recording_asr_title
    val modelMissing = if (speaker) R.string.recording_speaker_model_missing else R.string.recording_asr_missing
    val downloadBytes = if (speaker) RecordingSpeakerModelManager.DOWNLOAD_BYTES else spec.bytes
    val installedBytes = if (speaker) RecordingSpeakerModelManager.INSTALLED_BYTES else spec.installedBytes
    val installed = state == RecordingAsrModelState.Installed
    val titleRes = if (inTranscript && !installed) modelMissing else modelTitle
    val sizeRes = if (speaker) R.string.recording_speaker_model_size else R.string.recording_asr_size
    val noteText = if (inTranscript && !speaker && state == RecordingAsrModelState.Missing) {
        stringResource(R.string.recording_asr_audio_only)
    } else {
        null
    }
    RecordingModelPackageCard(
        icon = if (speaker) Icons.Rounded.Memory else Icons.Rounded.GraphicEq,
        title = stringResource(titleRes),
        sizeLine = stringResource(
            sizeRes,
            (downloadBytes + 500_000) / 1_000_000,
            (installedBytes + 500_000) / 1_000_000,
        ),
        description = noteText,
        state = state,
        downloadingLabel = R.string.recording_asr_downloading,
        installingLabel = R.string.recording_asr_installing,
        onDownload = onDownload,
        onCancel = onCancel,
    )
}

@Composable
internal fun RecordingRefineControls(
    state: RecordingAsrModelState,
    capability: RecordingRefineCapability,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val sizeMb = ((RecordingRefinePackage.CURRENT.bytes + 500_000) / 1_000_000).toInt()
    val badge = if (capability != RecordingRefineCapability.STREAMING_ONLY) {
        stringResource(R.string.recording_refine_hardware_recommended)
    } else {
        null
    }
    RecordingModelPackageCard(
        icon = Icons.Rounded.AutoAwesome,
        title = stringResource(R.string.recording_refine_title),
        sizeLine = stringResource(R.string.recording_refine_size, sizeMb, sizeMb),
        description = stringResource(R.string.recording_refine_description),
        badge = badge,
        state = state,
        downloadingLabel = R.string.recording_refine_downloading,
        installingLabel = R.string.recording_refine_installing,
        onDownload = onDownload,
        onCancel = onCancel,
    )
}

@Composable
internal fun RecordingRefineStrategyControls(
    selectedMode: RecordingRefineMode,
    capability: RecordingRefineCapability,
    refineInstalled: Boolean,
    runtimeDegraded: Boolean,
    onSelectMode: (RecordingRefineMode) -> Unit,
) {
    val badgeText = when (capability) {
        RecordingRefineCapability.REALTIME_TWO_PASS -> stringResource(R.string.recording_refine_badge_realtime)
        RecordingRefineCapability.DEFERRED_TWO_PASS -> stringResource(R.string.recording_refine_badge_deferred)
        RecordingRefineCapability.STREAMING_ONLY -> stringResource(R.string.recording_refine_badge_streaming)
    }
    val capabilityIcon = when (capability) {
        RecordingRefineCapability.REALTIME_TWO_PASS -> Icons.Rounded.Speed
        RecordingRefineCapability.DEFERRED_TWO_PASS -> Icons.Rounded.Tune
        RecordingRefineCapability.STREAMING_ONLY -> Icons.Rounded.Info
    }
    val hardwareText = when (capability) {
        RecordingRefineCapability.REALTIME_TWO_PASS -> stringResource(R.string.recording_refine_hw_realtime)
        RecordingRefineCapability.DEFERRED_TWO_PASS -> stringResource(R.string.recording_refine_hw_deferred)
        RecordingRefineCapability.STREAMING_ONLY -> stringResource(R.string.recording_refine_hw_streaming)
    }
    val effectiveStrategy = RecordingHardwareAdvisor.resolveStrategy(
        mode = selectedMode,
        refineModelInstalled = refineInstalled,
        capability = capability,
        runtimeDegraded = runtimeDegraded,
    )
    val strategyText = when {
        !refineInstalled -> stringResource(R.string.recording_refine_active_uninstalled)
        effectiveStrategy == RecordingRefineStrategy.REALTIME_AND_DEFERRED ->
            stringResource(R.string.recording_refine_active_realtime)
        effectiveStrategy == RecordingRefineStrategy.DEFERRED_ONLY ->
            stringResource(R.string.recording_refine_active_deferred)
        else -> stringResource(R.string.recording_refine_active_off)
    }
    val noteRows = buildList {
        add(InfinityEmbeddedNoteRow(icon = Icons.Rounded.Memory, text = hardwareText, tone = InfinityNoteTone.DEFAULT))
        add(InfinityEmbeddedNoteRow(icon = capabilityIcon, text = strategyText, tone = InfinityNoteTone.PRIMARY))
        if (runtimeDegraded && selectedMode == RecordingRefineMode.AUTO && refineInstalled) {
            add(
                InfinityEmbeddedNoteRow(
                    icon = Icons.Rounded.WarningAmber,
                    text = stringResource(R.string.recording_refine_degraded_notice),
                    tone = InfinityNoteTone.WARNING,
                ),
            )
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(InfinitySpacing.RowHorizontal),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        ) {
            Text(
                text = stringResource(R.string.recording_refine_strategy_title),
                style = InfinityTypography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = capabilityIcon,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        InfinitySegmentedControl(
            segments = listOf(
                InfinitySegment(RecordingRefineMode.AUTO, stringResource(R.string.recording_refine_mode_auto)),
                InfinitySegment(RecordingRefineMode.ALWAYS_ON, stringResource(R.string.recording_refine_mode_always)),
                InfinitySegment(RecordingRefineMode.OFF, stringResource(R.string.recording_refine_mode_off)),
            ),
            selected = selectedMode,
            onSelect = onSelectMode,
            fillWidth = true,
            compact = true,
        )
        InfinityEmbeddedNoteCard(rows = noteRows)
    }
}

@Composable
private fun RecordingModelPackageCard(
    icon: ImageVector,
    title: String,
    sizeLine: String,
    description: String? = null,
    badge: String? = null,
    state: RecordingAsrModelState,
    downloadingLabel: Int,
    installingLabel: Int,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(InfinitySpacing.RowHorizontal),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = title,
                style = InfinityTypography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (!badge.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
        Text(
            text = sizeLine,
            style = InfinityTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!description.isNullOrBlank()) {
            Text(
                text = description,
                style = InfinityTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (state) {
            RecordingAsrModelState.Missing, is RecordingAsrModelState.Failed -> {
                if (state is RecordingAsrModelState.Failed) {
                    Text(
                        stringResource(state.message),
                        style = InfinityTypography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                InfinityPillButton(
                    text = stringResource(if (state is RecordingAsrModelState.Failed) R.string.common_retry else R.string.recording_asr_download),
                    onClick = onDownload,
                    tone = InfinityPillTone.PRIMARY,
                    icon = Icons.Rounded.Download,
                    compact = true,
                )
            }

            is RecordingAsrModelState.Downloading -> {
                val progress = (state.downloadedBytes.toFloat() / state.totalBytes.coerceAtLeast(1)).coerceIn(0f, 1f)
                RecordingWorkProgress(downloadingLabel, progress, onCancel)
            }

            RecordingAsrModelState.Installing -> RecordingWorkProgress(installingLabel, null, onCancel)
            RecordingAsrModelState.Installed -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(R.string.recording_asr_installed),
                        style = InfinityTypography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
internal fun RecordingWorkProgress(label: Int, progress: Float?, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall)) {
        Text(
            if (progress == null) stringResource(label) else stringResource(R.string.recording_progress_label, stringResource(label), (progress * 100).toInt()),
            style = InfinityTypography.bodySmall,
        )
        if (progress == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        InfinityPillButton(text = stringResource(R.string.recording_cancel), onClick = onCancel, compact = true)
    }
}
