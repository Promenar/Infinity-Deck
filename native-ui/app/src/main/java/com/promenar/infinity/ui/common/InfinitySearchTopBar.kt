package com.promenar.infinity.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.promenar.infinity.R
import com.promenar.infinity.ui.theme.InfinitySpacing

/** 二级页顶栏搜索的状态；查询与开关由调用方持有。 */
data class InfinityPageSearch(
    val query: String,
    val active: Boolean,
    val onQueryChange: (String) -> Unit,
    val onActiveChange: (Boolean) -> Unit,
    val placeholder: String? = null,
    val fieldTag: String? = null,
)

/**
 * 二级页与检视器页签的顶栏搜索：点放大镜后标题位换成紧凑搜索框，返回先退出搜索。
 *
 * 退出搜索时清空查询，回到页面完整内容。[search] 为 null（例如内容尚未就绪）时只显示标题与操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfinitySearchTopBar(
    title: String? = null,
    search: InfinityPageSearch?,
    onBack: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.background,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val placeholder = search?.placeholder ?: stringResource(R.string.common_search_placeholder)
    val searching = search?.active == true
    val exitSearch: () -> Unit = {
        focusManager.clearFocus()
        search?.onQueryChange?.invoke("")
        search?.onActiveChange?.invoke(false)
    }

    BackHandler(enabled = searching) { exitSearch() }

    LaunchedEffect(searching) {
        if (searching) focusRequester.requestFocus()
    }

    TopAppBar(
        title = {
            AnimatedContent(
                targetState = searching,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "infinity-search-top-bar",
            ) { active ->
                if (active && search != null) {
                    InfinitySearchBar(
                        value = search.query,
                        onValueChange = search.onQueryChange,
                        placeholder = placeholder,
                        onSearch = { focusManager.clearFocus() },
                        modifier = Modifier.padding(end = InfinitySpacing.Small)
                            .then(search.fieldTag?.let { Modifier.testTag(it) } ?: Modifier),
                        fieldModifier = Modifier.focusRequester(focusRequester),
                    )
                } else {
                    title?.takeIf(String::isNotBlank)?.let {
                        InfinityHeaderTitle(
                            text = it,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        navigationIcon = {
            val leading = LocalInfinitySettingsLeading.current
            when {
                searching -> InfinityBackButton(onClick = exitSearch)
                leading != null -> leading()
                onBack != null && !LocalInfinitySettingsPaneRoot.current -> InfinityBackButton(onClick = onBack)
            }
        },
        actions = {
            if (!searching) {
                if (search != null) {
                    IconButton(
                        onClick = { search.onActiveChange(true) },
                        modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget),
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = placeholder)
                    }
                }
                actions()
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor,
            scrolledContainerColor = containerColor,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

/**
 * 没有顶栏的面板（如检视器页签）里的工具行搜索：平时显示 [title] 与操作按钮，
 * 点放大镜后整行换成搜索框；关闭按钮或系统返回退出搜索并清空查询。
 */
@Composable
fun InfinityToolbarSearch(
    search: InfinityPageSearch,
    modifier: Modifier = Modifier,
    title: String? = null,
    openButtonTag: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val placeholder = search.placeholder ?: stringResource(R.string.common_search_placeholder)
    val exitSearch: () -> Unit = {
        focusManager.clearFocus()
        search.onQueryChange("")
        search.onActiveChange(false)
    }

    BackHandler(enabled = search.active) { exitSearch() }

    LaunchedEffect(search.active) {
        if (search.active) focusRequester.requestFocus()
    }

    AnimatedContent(
        targetState = search.active,
        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
        modifier = modifier.fillMaxWidth(),
        label = "infinity-toolbar-search",
    ) { active ->
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = InfinitySpacing.MinimumTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (active) {
                InfinitySearchBar(
                    value = search.query,
                    onValueChange = search.onQueryChange,
                    placeholder = placeholder,
                    onSearch = { focusManager.clearFocus() },
                    modifier = Modifier.weight(1f)
                        .then(search.fieldTag?.let { Modifier.testTag(it) } ?: Modifier),
                    fieldModifier = Modifier.focusRequester(focusRequester),
                )
                IconButton(onClick = exitSearch, modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget)) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_btn_cancel))
                }
            } else {
                Text(
                    text = title.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { search.onActiveChange(true) },
                    modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget)
                        .then(openButtonTag?.let { Modifier.testTag(it) } ?: Modifier),
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = placeholder)
                }
                actions()
            }
        }
    }
}
