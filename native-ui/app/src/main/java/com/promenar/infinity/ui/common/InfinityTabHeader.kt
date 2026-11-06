package com.promenar.infinity.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.theme.InfinitySpacing

/**
 * 主标签页（对话、资料、设置）共用的大标题页头。
 *
 * 标题与右侧操作同处一行；传入 [onSearchActiveChange] 时右侧出现搜索按钮，进入搜索后
 * 标题行下方展开搜索框并自动聚焦，返回键或“取消”退出搜索并清空查询。
 * 页头自带状态栏内边距，放在 Scaffold 的 topBar 槽位即可。
 */
@Composable
fun InfinityTabHeader(
    title: String,
    modifier: Modifier = Modifier,
    searchActive: Boolean = false,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    onSearchActiveChange: ((Boolean) -> Unit)? = null,
    searchPlaceholder: String = stringResource(R.string.common_search_placeholder),
    searchFieldTag: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val exitSearch: () -> Unit = {
        focusManager.clearFocus()
        onQueryChange("")
        onSearchActiveChange?.invoke(false)
    }
    BackHandler(enabled = searchActive && onSearchActiveChange != null, onBack = exitSearch)
    LaunchedEffect(searchActive) {
        if (searchActive) focusRequester.requestFocus()
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(start = InfinitySpacing.ScreenHorizontal + InfinitySpacing.XSmall, end = InfinitySpacing.XSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InfinityHeaderTitle(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            if (onSearchActiveChange != null && !searchActive) {
                IconButton(
                    onClick = { onSearchActiveChange(true) },
                    modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = searchPlaceholder,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            actions()
        }
        AnimatedVisibility(visible = searchActive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = InfinitySpacing.ScreenHorizontal, end = InfinitySpacing.XSmall, bottom = InfinitySpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InfinitySearchBar(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = searchPlaceholder,
                    onSearch = { focusManager.clearFocus() },
                    modifier = Modifier
                        .weight(1f)
                        .let { if (searchFieldTag != null) it.testTag(searchFieldTag) else it },
                    fieldModifier = Modifier.focusRequester(focusRequester),
                )
                Spacer(Modifier.width(InfinitySpacing.XSmall))
                TextButton(onClick = exitSearch) {
                    Text(stringResource(R.string.common_btn_cancel))
                }
            }
        }
    }
}
