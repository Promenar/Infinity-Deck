package com.promenar.infinity.ui.library

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinitySettingsItem

/** 文件夹的图谱归属：独立作品（默认）、通用知识、与上级文件夹合并。 */
internal enum class FolderGraphMode(@StringRes val label: Int, @StringRes val description: Int) {
    WORK(R.string.ks_folder_mode_work, R.string.ks_folder_mode_work_desc),
    SHARED(R.string.ks_folder_mode_shared, R.string.ks_folder_mode_shared_desc),
    MERGE_PARENT(R.string.ks_folder_mode_merge, R.string.ks_folder_mode_merge_desc),
}

/**
 * 文件夹图谱设置面板，选中即生效并关闭。切换只按已有证据重新归类实体，不调用模型。
 * [selected] 为空表示正在读取当前设置，列表不显示选中项。
 */
@Composable
internal fun FolderGraphSettingSheet(
    folderName: String,
    selected: FolderGraphMode?,
    onSelect: (FolderGraphMode) -> Unit,
    onDismiss: () -> Unit,
) {
    InfinityBottomSheet(show = true, onDismiss = onDismiss, title = stringResource(R.string.ks_folder_title, folderName),
        scrollContent = true) {
        Column {
            Text(
                stringResource(R.string.ks_folder_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            FolderGraphMode.entries.forEach { mode ->
                val isSelected = mode == selected
                InfinitySettingsItem(
                    horizontalPadding = 0.dp,
                    title = stringResource(mode.label),
                    subtitle = stringResource(mode.description),
                    subtitleMaxLines = 3,
                    role = Role.RadioButton,
                    selected = isSelected,
                    enabled = selected != null,
                    onClick = { if (!isSelected) onSelect(mode); onDismiss() },
                    modifier = Modifier.testTag("folder_graph_mode_${mode.name.lowercase()}"),
                    trailingContent = if (isSelected) {
                        { Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary) }
                    } else null,
                )
            }
        }
    }
}
