package com.promenar.infinity.ui.common

import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfinityPageLayout(
    title: String,
    onBack: (() -> Unit)? = null,
    scrollable: Boolean = true,
    imePadding: Boolean = true,
    modifier: Modifier = Modifier,
    /** 标题下方的单行说明；为 null 时顶栏只有标题。 */
    subtitle: String? = null,
    /** 页面主体内边距；为 null 时使用设置类页面的默认留白。 */
    bodyPadding: PaddingValues? = null,
    actions: @Composable RowScope.() -> Unit = {},
    /** 为 false 时不画顶栏；用于检视器的根层，页签已写明所在页。 */
    showTopBar: Boolean = true,
    titleContent: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    // 可滚动页的正文从毛玻璃顶栏下滚过；不可滚动页（终端等）正文仍从顶栏下沿开始。
    val backdrop = rememberBackdropSource(enabled = showTopBar && scrollable)
    // 检视器是会话内的浮动卡片，不贴系统栏，不再留系统栏内边距。
    val insets = if (LocalInfinityPanePresentation.current == InfinityPanePresentation.Inspector) WindowInsets(0) else WindowInsets.systemBars

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (showTopBar) FrostedTopBar(backdrop) { TopAppBar(
                title = {
                    if (titleContent != null) titleContent() else Column(modifier = Modifier.padding(start = if (onBack != null) 0.dp else 4.dp)) {
                        InfinityHeaderTitle(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (subtitle != null) {
                            InfinityHeaderTitle(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                heading = false,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        InfinityBackButton(onClick = onBack)
                    }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                scrollBehavior = scrollBehavior,
                // 顶栏只吃顶部与左右的系统栏；整组 systemBars 会把底部导航条的高度也垫进顶栏下方。
                windowInsets = insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            ) }
        },
        contentWindowInsets = insets,
    ) { paddingValues ->
        val layoutDirection = LocalLayoutDirection.current
        val topInset = paddingValues.calculateTopPadding()
        // 正文延伸到毛玻璃顶栏后方时，顶栏高度改为滚动内容的上留白。
        val underTopBar = backdrop != null
        Box(
            modifier = Modifier
                .fillMaxSize()
                .backdropSource(backdrop)
                .padding(
                    start = paddingValues.calculateStartPadding(layoutDirection),
                    top = if (underTopBar) 0.dp else topInset,
                    end = paddingValues.calculateEndPadding(layoutDirection),
                    bottom = paddingValues.calculateBottomPadding(),
                )
                // 已让出的系统栏不再计入键盘内边距，否则键盘弹出时导航栏高度被算两次。
                .consumeWindowInsets(paddingValues)
                .then(if (imePadding) Modifier.imePadding() else Modifier),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 960.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(top = if (underTopBar) topInset else 0.dp)
                    .padding(
                        bodyPadding ?: PaddingValues(
                            start = InfinitySpacing.ScreenHorizontal,
                            end = InfinitySpacing.ScreenHorizontal,
                            top = InfinitySpacing.Large,
                            bottom = InfinitySpacing.Large + InfinitySpacing.XLarge,
                        )
                    )
                    .testTag("infinity_page_body")
            ) {
                content()
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF131315)
@Composable
private fun InfinityPageLayoutPreview() {
    InfinityTheme {
        InfinityPageLayout(
            title = "Settings",
            onBack = {}
        ) {
            Text(
                text = "Page content goes here",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
