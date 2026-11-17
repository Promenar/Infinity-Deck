package com.promenar.infinity.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.recording.RecordingAsrControls
import com.promenar.infinity.recording.RecordingAsrModelState
import com.promenar.infinity.recording.RecordingRefineCapability
import com.promenar.infinity.recording.RecordingRefineControls
import com.promenar.infinity.recording.RecordingRefineMode
import com.promenar.infinity.recording.RecordingRefinePackage
import com.promenar.infinity.recording.RecordingRefineStrategyControls
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinityPageLayout
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySectionFooterCard
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.InfinityTypography

/**
 * 录音二遍精修正式组件与引导页预览：直接组合生产无状态控件，用于深浅色与窄屏核验。
 */
@Composable
private fun RefinePrototypeFrame(
    mode: InfinityThemeMode,
    content: @Composable () -> Unit,
) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = mode,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

@Preview(name = "正式设置页 旗舰实时精修 深色中文", widthDp = 411, heightDp = 1140, locale = "zh-rCN")
@Composable
fun RecordingSettingsRefineFlagshipDarkPreview() {
    RefinePrototypeFrame(InfinityThemeMode.DARK) {
        InfinitySettingsPageLayout(title = stringResource(R.string.recording_settings), onBack = {}) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding,
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.SectionGap),
            ) {
                item {
                    InfinitySettingsSection(
                        title = stringResource(R.string.recording_asr_section),
                        footer = stringResource(R.string.recording_refine_section_footer),
                    ) {
                        InfinitySettingsItem(
                            title = stringResource(R.string.recording_setup_title),
                            subtitle = stringResource(R.string.recording_setup_entry_subtitle),
                            onClick = {},
                            showChevron = true,
                        )
                        InfinityGroupDivider()
                        RecordingAsrControls(
                            state = RecordingAsrModelState.Installed,
                            onDownload = {},
                            onCancel = {},
                        )
                        InfinityGroupDivider()
                        RecordingRefineControls(
                            state = RecordingAsrModelState.Installed,
                            capability = RecordingRefineCapability.REALTIME_TWO_PASS,
                            onDownload = {},
                            onCancel = {},
                        )
                        InfinityGroupDivider()
                        RecordingRefineStrategyControls(
                            selectedMode = RecordingRefineMode.AUTO,
                            capability = RecordingRefineCapability.REALTIME_TWO_PASS,
                            refineInstalled = true,
                            runtimeDegraded = false,
                            onSelectMode = {},
                        )
                        InfinityGroupDivider()
                        RecordingAsrControls(
                            state = RecordingAsrModelState.Installed,
                            onDownload = {},
                            onCancel = {},
                            speaker = true,
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "正式设置页 多状态对照 浅色中文", widthDp = 411, heightDp = 1140, locale = "zh-rCN")
@Composable
fun RecordingSettingsRefineStatesLightPreview() {
    RefinePrototypeFrame(InfinityThemeMode.LIGHT) {
        InfinitySettingsPageLayout(title = stringResource(R.string.recording_settings), onBack = {}) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding,
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.SectionGap),
            ) {
                item {
                    InfinitySettingsSection(
                        title = stringResource(R.string.recording_asr_section),
                        footer = stringResource(R.string.recording_refine_section_footer),
                    ) {
                        RecordingRefineControls(
                            state = RecordingAsrModelState.Missing,
                            capability = RecordingRefineCapability.DEFERRED_TWO_PASS,
                            onDownload = {},
                            onCancel = {},
                        )
                        InfinityGroupDivider()
                        RecordingRefineControls(
                            state = RecordingAsrModelState.Downloading(
                                downloadedBytes = (RecordingRefinePackage.CURRENT.bytes * 0.46f).toLong(),
                                totalBytes = RecordingRefinePackage.CURRENT.bytes,
                            ),
                            capability = RecordingRefineCapability.DEFERRED_TWO_PASS,
                            onDownload = {},
                            onCancel = {},
                        )
                    }
                }
                item {
                    InfinitySettingsSection(title = stringResource(R.string.recording_refine_strategy_title)) {
                        RecordingRefineStrategyControls(
                            selectedMode = RecordingRefineMode.AUTO,
                            capability = RecordingRefineCapability.DEFERRED_TWO_PASS,
                            refineInstalled = true,
                            runtimeDegraded = false,
                            onSelectMode = {},
                        )
                        InfinityGroupDivider()
                        RecordingRefineStrategyControls(
                            selectedMode = RecordingRefineMode.AUTO,
                            capability = RecordingRefineCapability.REALTIME_TWO_PASS,
                            refineInstalled = true,
                            runtimeDegraded = true,
                            onSelectMode = {},
                        )
                    }
                }
            }
        }
    }
}

private data class PrototypeSetupRow(
    val titleRes: Int,
    val detailRes: Int,
    val sizeText: String,
    val checked: Boolean,
    val enabled: Boolean = true,
    val recommended: Boolean = false,
)

@Preview(name = "正式引导页 四项等高清单 深色中文", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Composable
fun RecordingSetupRefineDarkPreview() {
    val rows = listOf(
        PrototypeSetupRow(
            titleRes = R.string.recording_setup_asr,
            detailRes = R.string.recording_setup_asr_detail,
            sizeText = stringResource(R.string.recording_setup_ready),
            checked = true,
            enabled = false,
        ),
        PrototypeSetupRow(
            titleRes = R.string.recording_setup_refine,
            detailRes = R.string.recording_setup_refine_detail,
            sizeText = stringResource(R.string.recording_setup_mb, 237),
            checked = true,
            recommended = true,
        ),
        PrototypeSetupRow(
            titleRes = R.string.recording_setup_speakers,
            detailRes = R.string.recording_setup_speakers_detail,
            sizeText = stringResource(R.string.recording_setup_mb, 30),
            checked = true,
        ),
        PrototypeSetupRow(
            titleRes = R.string.recording_setup_local,
            detailRes = R.string.recording_setup_local_detail,
            sizeText = stringResource(R.string.recording_setup_mb, 639),
            checked = false,
        ),
    )
    val subtitleMaxLines = if (LocalDensity.current.fontScale > 1f) 2 else 1
    RefinePrototypeFrame(InfinityThemeMode.DARK) {
        InfinityPageLayout(
            title = stringResource(R.string.recording_setup_title),
            onBack = {},
            scrollable = false,
            bodyPadding = PaddingValues(),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 510.dp)
                        .fillMaxWidth()
                        .padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Large),
                    verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
                ) {
                    Surface(
                        shape = RoundedCornerShape(15.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(12.dp)
                                .size(24.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                        Text(
                            text = stringResource(R.string.recording_setup_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        InfinitySectionFooterCard(
                            text = stringResource(R.string.recording_setup_intro),
                            maxLines = 3,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(17.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            rows.forEachIndexed { index, row ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 68.dp)
                                        .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = row.checked,
                                        onCheckedChange = null,
                                        enabled = row.enabled,
                                    )
                                    Spacer(Modifier.width(InfinitySpacing.Small))
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(
                                                text = stringResource(row.titleRes),
                                                style = InfinityTypography.titleSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            if (row.recommended) {
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
                                            text = stringResource(row.detailRes),
                                            style = InfinityTypography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = subtitleMaxLines,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Spacer(Modifier.width(InfinitySpacing.Small))
                                    Text(
                                        text = row.sizeText,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        color = if (row.enabled) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                    )
                                }
                                if (index < rows.lastIndex) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                    }
                    InfinitySectionFooterCard(
                        text = stringResource(R.string.recording_setup_privacy),
                        maxLines = 3,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InfinityPillButton(
                            text = stringResource(R.string.recording_setup_skip),
                            onClick = {},
                            tone = InfinityPillTone.NEUTRAL,
                        )
                        InfinityPillButton(
                            text = stringResource(R.string.recording_setup_download, 267),
                            onClick = {},
                            tone = InfinityPillTone.PRIMARY,
                            icon = Icons.Rounded.Download,
                        )
                    }
                }
            }
        }
    }
}
