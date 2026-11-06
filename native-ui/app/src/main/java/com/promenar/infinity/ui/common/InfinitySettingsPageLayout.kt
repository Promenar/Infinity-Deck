package com.promenar.infinity.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.promenar.infinity.ui.theme.InfinitySpacing

/** 宽屏设置右列的根层页面为 true：页面仍有标题，但不画返回按钮，离开它靠左列或主导航。 */
val LocalInfinitySettingsPaneRoot = compositionLocalOf { false }

/**
 * 设置页嵌入另一页面（例如会话“远程”面板的分段）时给出外层留白：骨架不画顶栏、不贴系统栏，
 * 只把这里的上下留白加在内容边距上。只作用于最外层骨架，页内再打开的设置页照常显示。
 */
val LocalInfinitySettingsEmbedded = compositionLocalOf<PaddingValues?> { null }

/**
 * 宿主替换顶栏最左侧按钮（例如资料页右列的展开 / 还原按钮）：给出时代替返回按钮，搜索态仍显示退出搜索。
 * 宿主只在被嵌入的那一页外层提供它。
 */
val LocalInfinitySettingsLeading = compositionLocalOf<(@Composable () -> Unit)?> { null }

/**
 * 设置层级页共用骨架。
 *
 * 内容边距由 [PaddingValues] 统一下发，调用方可直接传给 LazyColumn 的
 * `contentPadding`，也可应用到自定义滚动容器；骨架不强制内容的滚动实现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfinitySettingsPageLayout(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    horizontalContentPadding: Dp = InfinitySpacing.ScreenHorizontal,
    showTopBar: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    // 主标签页用自定义页头（如 [InfinityTabHeader]）替换标准顶栏；页头自行处理状态栏内边距。
    header: (@Composable () -> Unit)? = null,
    // 二级页顶栏搜索：给出时顶栏右侧出现放大镜，点开后标题位换成搜索框。
    search: InfinityPageSearch? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val embedded = LocalInfinitySettingsEmbedded.current
    if (embedded != null) {
        Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            CompositionLocalProvider(LocalInfinitySettingsEmbedded provides null) {
                content(
                    PaddingValues(
                        start = horizontalContentPadding,
                        top = embedded.calculateTopPadding(),
                        end = horizontalContentPadding,
                        bottom = embedded.calculateBottomPadding() + InfinitySpacing.XLarge,
                    ),
                )
            }
        }
        return
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            if (header != null) header() else if (showTopBar && search != null) InfinitySearchTopBar(
                title = title,
                search = search,
                onBack = onBack,
                actions = actions,
            ) else if (showTopBar) TopAppBar(
                title = {
                    InfinityHeaderTitle(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    val leading = LocalInfinitySettingsLeading.current
                    when {
                        leading != null -> leading()
                        onBack != null && !LocalInfinitySettingsPaneRoot.current -> InfinityBackButton(onClick = onBack)
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) else {
                // 无顶栏时用页面背景色垫住状态栏，滚动内容不会从状态栏图标下方穿过。
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsTopHeight(WindowInsets.statusBars)
                        .background(MaterialTheme.colorScheme.background),
                )
            }
        },
    ) { scaffoldPadding ->
        content(
            PaddingValues(
                start = horizontalContentPadding,
                top = scaffoldPadding.calculateTopPadding(),
                end = horizontalContentPadding,
                bottom = scaffoldPadding.calculateBottomPadding() + InfinitySpacing.XLarge,
            ),
        )
    }
}
