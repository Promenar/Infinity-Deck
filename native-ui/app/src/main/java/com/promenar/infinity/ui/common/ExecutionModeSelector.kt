package com.promenar.infinity.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R

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
