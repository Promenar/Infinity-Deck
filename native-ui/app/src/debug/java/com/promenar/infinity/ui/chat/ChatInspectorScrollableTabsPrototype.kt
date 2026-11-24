package com.promenar.infinity.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.browser.BrowserMode
import com.promenar.infinity.ui.production.ProductionEntryState
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.InfinityTypography

private enum class PrototypeTabLabelMode {
    SELECTED_ONLY,
    ALL_LABELS,
}

@Composable
private fun sampleEntries(generating: Boolean = true): List<PanelHubEntry> = chatPanelHubEntries(
    signals = ChatPanelHubSignals(
        generating = generating,
        runningJobs = if (generating) 1 else 0,
        browser = BrowserMode.IDLE,
        canvases = 2,
        canvasCandidates = 8,
        hasStateBoard = false,
        production = ProductionEntryState(
            enabled = true,
            hasProduction = true,
            pending = 1,
            running = false,
            title = "陆昭晨间问候 20s 测试片",
        ),
        used = setOf(
            ChatPage.Run,
            ChatPage.Explorer,
            ChatPage.Git,
            ChatPage.Browser,
            ChatPage.Canvas,
            ChatPage.Production,
        ),
    ),
    summary = { null },
    onOpen = {},
)

/** 在绘制阶段读取滚动边界，首帧及滚动中均可按实时剩余空间渲染渐隐边而不触发额外重组。 */
private fun Modifier.dynamicHorizontalFadingEdges(
    state: ScrollState,
    length: Dp = 18.dp,
): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = length.toPx().coerceAtMost(size.width / 4f)
        val mask = Color.Transparent.copy(alpha = 1f)
        if (state.canScrollBackward && edge > 0f) {
            drawRect(
                brush = Brush.horizontalGradient(listOf(Color.Transparent, mask), startX = 0f, endX = edge),
                size = Size(edge, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
        if (state.canScrollForward && edge > 0f) {
            drawRect(
                brush = Brush.horizontalGradient(listOf(mask, Color.Transparent), startX = size.width - edge, endX = size.width),
                topLeft = Offset(size.width - edge, 0f),
                size = Size(edge, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }

@Composable
private fun PrototypeInspectorCard(
    root: ChatPage,
    entries: List<PanelHubEntry>,
    labelMode: PrototypeTabLabelMode,
    initialScrollDp: Dp,
    cardWidth: Dp,
    widened: Boolean = false,
    showTouchLock: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val scrollPx = with(LocalDensity.current) { initialScrollDp.roundToPx() }
    val scrollState = rememberScrollState(initial = scrollPx)
    Surface(
        modifier = Modifier.width(cardWidth),
        shape = RoundedCornerShape(20.dp),
        color = scheme.background,
        border = BorderStroke(0.5.dp, scheme.outlineVariant),
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "当前页签：${root.hubLabel()}",
                    style = InfinityTypography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.6f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 保持 48dp 视口高度，避免角标向上探出时被 Offscreen 渐隐层裁切。
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .dynamicHorizontalFadingEdges(scrollState, length = 22.dp)
                            .horizontalScroll(scrollState)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(
                            if (labelMode == PrototypeTabLabelMode.ALL_LABELS) 4.dp else 0.dp,
                        ),
                    ) {
                        entries.forEach { entry ->
                            val selected = entry.page == root
                            val showLabel = when (labelMode) {
                                PrototypeTabLabelMode.SELECTED_ONLY -> selected
                                PrototypeTabLabelMode.ALL_LABELS -> true
                            }
                            PrototypeScrollableTab(
                                entry = entry,
                                selected = selected,
                                showLabel = showLabel,
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .width(1.dp)
                        .height(20.dp)
                        .background(scheme.outlineVariant.copy(alpha = 0.55f)),
                )
                if (showTouchLock) {
                    TouchLockButton(onLock = {})
                }
                IconButton(onClick = {}) {
                    Icon(
                        if (widened) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull,
                        contentDescription = stringResource(
                            if (widened) R.string.chat_inspector_restore_width else R.string.chat_inspector_widen,
                        ),
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = {}) {
                    Icon(
                        Icons.Rounded.KeyboardDoubleArrowRight,
                        contentDescription = stringResource(R.string.chat_inspector_collapse),
                        tint = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PrototypeScrollableTab(
    entry: PanelHubEntry,
    selected: Boolean,
    showLabel: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val tint = when {
        !entry.available -> scheme.onSurface.copy(alpha = 0.38f)
        selected -> scheme.onSurface
        else -> scheme.onSurfaceVariant
    }
    val description = entry.description()
    Surface(
        onClick = {},
        enabled = entry.available,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) scheme.surfaceContainerHigh else Color.Transparent,
        modifier = Modifier
            .heightIn(min = 40.dp)
            .widthIn(min = 46.dp)
            .semantics {
                role = Role.Tab
                this.selected = selected
                contentDescription = description
            },
    ) {
        if (showLabel) {
            Row(
                modifier = Modifier.padding(PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PanelHubIcon(entry, tint, reserveBadge = true)
                Text(
                    text = entry.page.inspectorTabLabel(),
                    style = InfinityTypography.labelLarge,
                    color = tint,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        } else {
            Box(Modifier.width(46.dp).height(40.dp), contentAlignment = Alignment.Center) {
                PanelHubIcon(entry, tint)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, detail: String) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = InfinityTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = scheme.onSurface,
        )
        Text(
            text = detail,
            style = InfinityTypography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

@Preview(name = "底栏方案对比 深色 360dp", widthDp = 392, heightDp = 1040, locale = "zh-rCN")
@Composable
fun inspectorTabsCompareDark360Preview() {
    val entries = sampleEntries(generating = true)
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SectionHeader(
                    title = "现状｜固定配额 + 三点收纳",
                    detail = "360dp 下仅放 3 个图标与 …，远程/产物/制作被折叠进菜单",
                )
                ChatInspectorCard(
                    root = ChatPage.Run,
                    entries = entries,
                    widened = false,
                    onToggleWiden = {},
                    onCollapse = {},
                    modifier = Modifier.width(360.dp).height(112.dp),
                ) {
                    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                        Text(
                            text = "当前页签：任务（其余入口在 …）",
                            style = InfinityTypography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SectionHeader(
                    title = "方案 A（推荐）｜横滑轨道 · 仅当前项展名（起始位）",
                    detail = "左起对齐当前胶囊，右侧淡出提示可继续左滑，分隔线保护右侧操作区",
                )
                PrototypeInspectorCard(
                    root = ChatPage.Run,
                    entries = entries,
                    labelMode = PrototypeTabLabelMode.SELECTED_ONLY,
                    initialScrollDp = 0.dp,
                    cardWidth = 360.dp,
                )

                SectionHeader(
                    title = "方案 A｜横滑轨道 · 右滑至末段（选中「制作」）",
                    detail = "左侧淡出提示前序页签，尾端直接可见「产物 8」「制作 1」与未启用「状态」",
                )
                PrototypeInspectorCard(
                    root = ChatPage.Production,
                    entries = entries,
                    labelMode = PrototypeTabLabelMode.SELECTED_ONLY,
                    initialScrollDp = 240.dp,
                    cardWidth = 360.dp,
                )

                SectionHeader(
                    title = "方案 B｜横滑轨道 · 全员图标+短名称胶囊",
                    detail = "无需记忆图标含义，但单屏可见 2~3 项，常用功能切换需要更多横滑",
                )
                PrototypeInspectorCard(
                    root = ChatPage.Run,
                    entries = entries,
                    labelMode = PrototypeTabLabelMode.ALL_LABELS,
                    initialScrollDp = 0.dp,
                    cardWidth = 360.dp,
                )
            }
        }
    }
}

@Preview(name = "生成中锁屏+加宽态 深色", widthDp = 540, heightDp = 820, locale = "zh-rCN")
@Composable
fun inspectorTabsWidenedAndGeneratingDarkPreview() {
    val entries = sampleEntries(generating = true)
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                SectionHeader(
                    title = "生成中显示触摸锁｜360dp 现状对比",
                    detail = "增加触摸锁按钮后，固定模式仅剩 2 项 + …",
                )
                ChatInspectorCard(
                    root = ChatPage.Run,
                    entries = entries,
                    widened = false,
                    onToggleWiden = {},
                    onCollapse = {},
                    onLockTouch = {},
                    modifier = Modifier.width(360.dp).height(112.dp),
                ) {
                    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                        Text(
                            text = "生成中（触摸锁占用一个按钮位）",
                            style = InfinityTypography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SectionHeader(
                    title = "生成中显示触摸锁｜方案 A（360dp 滑至中段选中「产物」）",
                    detail = "左右双侧同时渐隐提示，角标完整不被裁切",
                )
                PrototypeInspectorCard(
                    root = ChatPage.Canvas,
                    entries = entries,
                    labelMode = PrototypeTabLabelMode.SELECTED_ONLY,
                    initialScrollDp = 152.dp,
                    cardWidth = 360.dp,
                    showTouchLock = true,
                )

                SectionHeader(
                    title = "加宽检视器（500dp）｜方案 A 与方案 B 对比",
                    detail = "500dp 下方案 A 可一次展示更多入口；方案 B 全员带名仍需轻量横滑",
                )
                PrototypeInspectorCard(
                    root = ChatPage.Production,
                    entries = entries,
                    labelMode = PrototypeTabLabelMode.SELECTED_ONLY,
                    initialScrollDp = 9999.dp,
                    cardWidth = 500.dp,
                    widened = true,
                    showTouchLock = true,
                )
                PrototypeInspectorCard(
                    root = ChatPage.Production,
                    entries = entries,
                    labelMode = PrototypeTabLabelMode.ALL_LABELS,
                    initialScrollDp = 9999.dp,
                    cardWidth = 500.dp,
                    widened = true,
                    showTouchLock = true,
                )
            }
        }
    }
}

@Preview(name = "浅色模式 + 1.3x 大字号", widthDp = 392, heightDp = 820, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun inspectorTabsLightLargeFontPreview() {
    val entries = sampleEntries(generating = true)
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SectionHeader(
                    title = "现状｜1.3x 大字号选中「制作」",
                    detail = "大字号挤占横向空间，固定模式被迫隐藏更多页签",
                )
                ChatInspectorCard(
                    root = ChatPage.Production,
                    entries = entries,
                    widened = false,
                    onToggleWiden = {},
                    onCollapse = {},
                    modifier = Modifier.width(360.dp).height(112.dp),
                ) {
                    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                        Text(
                            text = "大字号固定底栏",
                            style = InfinityTypography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SectionHeader(
                    title = "方案 A｜1.3x 大字号（选中「制作」，靠右滑入）",
                    detail = "48dp 渐隐视口 + heightIn 胶囊自适应大字号高度与角标",
                )
                PrototypeInspectorCard(
                    root = ChatPage.Production,
                    entries = entries,
                    labelMode = PrototypeTabLabelMode.SELECTED_ONLY,
                    initialScrollDp = 9999.dp,
                    cardWidth = 360.dp,
                )

                SectionHeader(
                    title = "方案 B｜1.3x 大字号（全员带名称横滑）",
                    detail = "大字号下双项即占满视口，验证胶囊内边距与角标无裁切",
                )
                PrototypeInspectorCard(
                    root = ChatPage.Production,
                    entries = entries,
                    labelMode = PrototypeTabLabelMode.ALL_LABELS,
                    initialScrollDp = 9999.dp,
                    cardWidth = 360.dp,
                )
            }
        }
    }
}
