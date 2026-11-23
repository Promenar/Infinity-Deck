package com.promenar.infinity.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityTypography

enum class ExecutionMode(@param:StringRes val labelRes: Int, @param:StringRes val descriptionRes: Int) {
    WORKSPACE(R.string.common_mode_workspace, R.string.common_mode_workspace_desc),
    FULL_ACCESS(R.string.common_mode_full_access, R.string.common_mode_full_access_desc),
}

/** 执行模式是保存下来的互斥取值，用等分整行的分段滑块。 */
@Composable
fun ExecutionModeSelector(
    selected: ExecutionMode,
    onSelect: (ExecutionMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    InfinitySegmentedControl(
        segments = ExecutionMode.entries.map { InfinitySegment(it, stringResource(it.labelRes)) },
        selected = selected,
        onSelect = onSelect,
        modifier = modifier,
        fillWidth = true,
    )
}

@Preview(name = "zh_narrow", locale = "zh-rCN", widthDp = 320, showBackground = true, backgroundColor = 0xFF131315)
@Preview(name = "zh_large_font", locale = "zh-rCN", widthDp = 360, fontScale = 1.3f, showBackground = true, backgroundColor = 0xFF131315)
@Preview(name = "en_narrow", locale = "en", widthDp = 320, showBackground = true, backgroundColor = 0xFF131315)
@Preview(name = "en_large_font", locale = "en", widthDp = 360, fontScale = 1.3f, showBackground = true, backgroundColor = 0xFF131315)
@Composable
private fun ExecutionModeCopyPreview() {
    InfinityTheme {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExecutionModeSelector(selected = ExecutionMode.WORKSPACE, onSelect = {})
            InfinitySettingsSection(
                title = stringResource(R.string.agent_reach_section_approval),
                footer = stringResource(R.string.agent_reach_approval_footer),
            ) {
                InfinitySettingsRowLayout(
                    title = stringResource(R.string.common_mode_workspace),
                    subtitle = stringResource(R.string.common_mode_workspace_desc),
                    icon = Icons.Rounded.VerifiedUser,
                    subtitleMaxLines = 3,
                )
                InfinitySettingsRowLayout(
                    title = stringResource(R.string.common_mode_full_access),
                    subtitle = stringResource(R.string.common_mode_full_access_desc),
                    icon = Icons.Rounded.VerifiedUser,
                    subtitleMaxLines = 3,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.common_mode_workspace), style = InfinityTypography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                Text(stringResource(R.string.chat_execution_mode_workspace_hint), style = InfinityTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(stringResource(R.string.common_mode_full_access), style = InfinityTypography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                Text(stringResource(R.string.chat_execution_mode_full_access_hint), style = InfinityTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}
