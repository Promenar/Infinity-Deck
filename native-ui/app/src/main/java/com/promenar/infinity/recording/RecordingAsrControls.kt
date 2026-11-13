package com.promenar.infinity.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography

@Composable
internal fun RecordingAsrControls(
    state: RecordingAsrModelState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    inTranscript: Boolean = false,
) {
    val spec = RecordingAsrPackage.CURRENT
    val installed = state == RecordingAsrModelState.Installed
    Column(
        Modifier.fillMaxWidth().padding(InfinitySpacing.RowHorizontal),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        Text(
            stringResource(if (inTranscript && !installed) R.string.recording_asr_missing else R.string.recording_asr_title),
            style = InfinityTypography.titleSmall,
        )
        Text(
            stringResource(R.string.recording_asr_size, (spec.bytes + 500_000) / 1_000_000, (spec.installedBytes + 500_000) / 1_000_000),
            style = InfinityTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (inTranscript && state == RecordingAsrModelState.Missing) {
            Text(stringResource(R.string.recording_asr_audio_only), style = InfinityTypography.bodySmall)
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
                    compact = true,
                )
            }
            is RecordingAsrModelState.Downloading -> {
                val progress = (state.downloadedBytes.toFloat() / state.totalBytes.coerceAtLeast(1)).coerceIn(0f, 1f)
                RecordingWorkProgress(R.string.recording_asr_downloading, progress, onCancel)
            }
            RecordingAsrModelState.Installing -> RecordingWorkProgress(R.string.recording_asr_installing, null, onCancel)
            RecordingAsrModelState.Installed -> Text(stringResource(R.string.recording_asr_installed), style = InfinityTypography.bodySmall)
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
