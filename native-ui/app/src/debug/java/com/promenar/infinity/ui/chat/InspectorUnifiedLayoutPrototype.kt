package com.promenar.infinity.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MovieCreation
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewQuilt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.ui.common.InfinityPaneHeaderTitle
import com.promenar.infinity.ui.common.InfinityPanelTab
import com.promenar.infinity.ui.common.InfinityPanelTabs
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityObsidianGlassTint
import com.promenar.infinity.ui.theme.InfinityObsidianSolidChip
import com.promenar.infinity.ui.theme.InfinityObsidianSolidContainer
import com.promenar.infinity.ui.theme.InfinityPureWhite
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.infinityDomainColors

/*
 * 侧边栏三形态统一布局与熏黑抽屉原型（v1，隔离于 debug 源码）：
 *
 * 1. 两档严格坐标体系（替代既有 7 套割裂实现）：
 *    - A 档「纯吸顶 TAB 根页」（Remote / Canvas）：无冗余标题栏，顶部固定 top = 4dp、InfinityPanelTabs 48dp、contentInset = 16dp，底线固定 y = 52dp。
 *    - B 档「上下文页头型」（Run / Git / Production / Explorer / State / Browser）：
 *      统一 56dp 轻页头（top = 4dp、y = 4..60dp、titleMedium 16sp Medium + 可选 1 行 12sp 元信息；
 *      无返回左起 x = 16dp，有返回时 48dp 返回键在 x = 4dp、标题在 x = 56dp，右操作 end = 4dp）。
 *      凡带二级页签的 B 档页面（Run / Git / Production），吸顶 InfinityPanelTabs 严格对齐 y = 60dp（底线 y = 108dp）。
 * 2. 三形态完全合一（同一份面板内容 + 底栏 8 页签 + 支持左右滑动手势切换）：
 *    - 形态 1：内屏固定分栏（360dp）——熏黑实底 #070709 + 12% 白细边 + 14% 顶缘镜面高光（零高斯模糊 GPU 开销，视觉与浮岛 100% 统一）。
 *    - 形态 2：内屏扩大抽屉（560dp / 浏览器铺满）——熏黑实时毛玻璃 #050508@82% + 左侧柔和暗幕。
 *    - 形态 3：竖屏形态——同窗口 93% 全高熏黑毛玻璃上滑抽屉（顶部圆角 + 居中拖拽把手 + 底部 8 页签横滑条）。
 */

private val ProtoPanelTopGap = 4.dp
private val ProtoHeaderHeight = 56.dp
private val ProtoTabHeight = 48.dp
private val ProtoObsidianElevatedChip = Color(0xFF12141B)

@Composable
private fun ProtoTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    InfinityTheme(
        darkTheme = dark,
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
        content = content,
    )
}

/** 统一熏黑面板外壳：固定态零模糊纯熏黑实底，覆盖态/竖屏上滑态支持实时毛玻璃质感与顶缘镜面高光。 */
@Composable
private fun ProtoObsidianPanelFrame(
    dark: Boolean,
    frosted: Boolean,
    cornerRadius: Dp = 20.dp,
    bottomCornersFlat: Boolean = false,
    modifier: Modifier = Modifier,
    blurredBehindContent: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = if (bottomCornersFlat) {
        RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius, bottomStart = 0.dp, bottomEnd = 0.dp)
    } else {
        RoundedCornerShape(cornerRadius)
    }
    val borderColor = if (dark) InfinityPureWhite.copy(alpha = 0.12f) else scheme.outlineVariant.copy(alpha = 0.55f)
    val topGlint = if (dark) InfinityPureWhite.copy(alpha = 0.15f) else InfinityPureWhite.copy(alpha = 0.90f)
    val fill = when {
        dark && frosted -> InfinityObsidianGlassTint.copy(alpha = 0.82f)
        dark -> InfinityObsidianSolidContainer
        frosted -> InfinityPureWhite.copy(alpha = 0.84f)
        else -> scheme.surfaceContainerLowest
    }

    Surface(
        modifier = modifier,
        shape = shape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = if (frosted) 12.dp else 4.dp,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .drawWithContent {
                    drawContent()
                    // 顶缘 1dp 镜面细高光（与输入浮岛同源，不向内铺白雾）
                    val inset = cornerRadius.toPx()
                    val stroke = 1.dp.toPx()
                    drawLine(
                        brush = Brush.horizontalGradient(
                            0f to Color.Transparent,
                            0.18f to topGlint,
                            0.82f to topGlint,
                            1f to Color.Transparent,
                        ),
                        start = Offset(inset * 0.4f, stroke / 2f),
                        end = Offset(size.width - inset * 0.4f, stroke / 2f),
                        strokeWidth = stroke,
                    )
                },
        ) {
            if (frosted && blurredBehindContent != null) {
                Box(Modifier.matchParentSize().blur(24.dp, BlurredEdgeTreatment.Rectangle)) {
                    blurredBehindContent()
                }
            }
            Box(Modifier.matchParentSize().background(fill))
            content()
        }
    }
}

/** 统一 B 档 56dp 上下文页头原语：top = 4dp, height = 56dp, x = 16dp (有返回时 x = 4dp + 标题 56dp), end = 4dp。 */
@Composable
private fun ProtoUnifiedPanelHeader(
    title: String,
    meta: (@Composable RowScope.() -> Unit)? = null,
    leadingBadge: (@Composable () -> Unit)? = null,
    hasBack: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = ProtoPanelTopGap)
            .height(ProtoHeaderHeight)
            .padding(
                start = if (hasBack) 4.dp else InfinitySpacing.ScreenHorizontal,
                end = 4.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasBack) {
            IconButton(onClick = {}) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        if (leadingBadge != null) {
            leadingBadge()
            Spacer(Modifier.width(8.dp))
        }
        InfinityPaneHeaderTitle(
            title = title,
            modifier = Modifier.weight(1f),
            meta = meta,
        )
        actions()
    }
}

/** 统一底栏：8 页签横滑切换条 + 分隔竖线 + 右侧动作区（加宽/收起或竖屏收起）。 */
@Composable
private fun ProtoInspectorBottomBar(
    active: ChatPage,
    dark: Boolean,
    widened: Boolean = false,
    portrait: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val dividerColor = if (dark) InfinityPureWhite.copy(alpha = 0.08f) else scheme.outlineVariant.copy(alpha = 0.45f)
    val chipBg = if (dark) ProtoObsidianElevatedChip else scheme.surfaceContainerHigh
    val allItems = listOf(
        Triple(ChatPage.Run, "任务", Icons.Rounded.Checklist),
        Triple(ChatPage.Explorer, "文件", Icons.Rounded.FolderOpen),
        Triple(ChatPage.Git, "Git", Icons.Rounded.AccountTree),
        Triple(ChatPage.Browser, "浏览器", Icons.Rounded.Public),
        Triple(ChatPage.Canvas, "产物", Icons.Rounded.ViewQuilt),
        Triple(ChatPage.State, "状态", Icons.Rounded.TableChart),
        Triple(ChatPage.Production, "制作", Icons.Rounded.MovieCreation),
    )
    // 模拟真实横向滚动视窗：始终让当前选中页签滚入可视区域
    val visibleCount = if (portrait) 6 else 5
    val activeIdx = allItems.indexOfFirst { it.first == active }.coerceAtLeast(0)
    val startIdx = (activeIdx - (visibleCount - 2)).coerceIn(0, (allItems.size - visibleCount).coerceAtLeast(0))
    val visibleItems = allItems.subList(startIdx, (startIdx + visibleCount).coerceAtMost(allItems.size))

    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = dividerColor)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                visibleItems.forEach { (page, label, icon) ->
                    val selected = page == active
                    if (selected) {
                        Row(
                            modifier = Modifier
                                .height(36.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(chipBg)
                                .border(
                                    0.5.dp,
                                    if (dark) InfinityPureWhite.copy(alpha = 0.12f) else scheme.outlineVariant.copy(alpha = 0.5f),
                                    RoundedCornerShape(12.dp),
                                )
                                .padding(horizontal = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(icon, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(17.dp))
                            Text(
                                text = label,
                                style = InfinityTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = scheme.onSurface,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    } else {
                        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                            Icon(icon, contentDescription = label, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            if (page == ChatPage.Git) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 8.dp, end = 8.dp)
                                        .size(6.dp)
                                        .background(
                                            scheme.primary,
                                            CircleShape,
                                        ),
                                )
                            }
                        }
                    }
                }
            }
            if (!portrait) {
                Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(18.dp).background(dividerColor))
                IconButton(onClick = {}, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (widened) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull,
                        contentDescription = if (widened) "还原宽度" else "加宽",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = {}, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Rounded.KeyboardDoubleArrowRight,
                        contentDescription = "收起",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(18.dp).background(dividerColor))
                IconButton(onClick = {}, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "关闭面板",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}

/** 模拟聊天背景（用于宽屏分栏左侧与毛玻璃抽屉背后透视）。 */
@Composable
private fun ProtoChatBackdropCanvas(
    dark: Boolean,
    showComposer: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val cardBg = if (dark) InfinityObsidianSolidChip else scheme.surfaceContainerLow
    val cardBorder = if (dark) InfinityPureWhite.copy(alpha = 0.08f) else scheme.outlineVariant.copy(alpha = 0.5f)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(if (dark) Color(0xFF000000) else scheme.background),
    ) {
        // 模拟主聊天顶栏（56dp，16sp 标题 + 状态行）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {}) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = scheme.onSurfaceVariant)
            }
            InfinityPaneHeaderTitle(
                title = "v0721-e2e-ok",
                modifier = Modifier.weight(1f),
                meta = {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Nexara · main", maxLines = 1)
                },
            )
            IconButton(onClick = {}) {
                Icon(Icons.Rounded.MoreVert, contentDescription = null, tint = scheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = if (dark) InfinityPureWhite.copy(alpha = 0.08f) else scheme.outlineVariant.copy(alpha = 0.4f))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 用户气泡
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (dark) Color(0xFF141722) else scheme.primaryContainer.copy(alpha = 0.7f),
                    border = BorderStroke(0.5.dp, cardBorder),
                ) {
                    Text(
                        text = "帮我把内屏侧边栏的标题栏、吸顶页签与竖屏上滑抽屉统一成同一套熏黑体系。",
                        style = InfinityTypography.bodyMedium,
                        color = scheme.onSurface,
                        modifier = Modifier.widthIn(max = 320.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
            // 工具执行块
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = cardBg,
                border = BorderStroke(0.5.dp, cardBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Code, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                        Text("已核查 8 个侧边栏页面与 4 个共享浮层原语", style = InfinityTypography.labelLarge, color = scheme.onSurface)
                    }
                    Text(
                        text = "ChatInspectorCard · InfinityPanelTabs · InfinityBottomSheet · InfinityDropdownMenu",
                        style = InfinityTypography.bodySmall.copy(fontFamily = InfinityMonospace),
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 助手正文
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "统一方案已按两档坐标契约收口：",
                    style = InfinityTypography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = scheme.onSurface,
                )
                Text(
                    text = "1. A 档纯吸顶页签（远程、产物）不堆重复标题栏，TAB 锁死 y = 4dp；\n2. B 档上下文页头（任务、Git、制作、文件、状态、浏览器）统一 56dp 轻页头，二级 TAB 锁死 y = 60dp；\n3. 固定侧边栏同步熏黑实底（零模糊开销），扩大抽屉与竖屏 93% 上滑抽屉采用同源实时毛玻璃。",
                    style = InfinityTypography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        if (showComposer) {
            // 底部熏黑输入浮岛（视觉对齐基准）
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                ProtoObsidianPanelFrame(
                    dark = dark,
                    frosted = true,
                    cornerRadius = 24.dp,
                    modifier = Modifier.fillMaxWidth().height(92.dp),
                ) {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("给 Infinity 发消息", style = InfinityTypography.bodyMedium, color = scheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Add, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Icon(Icons.Rounded.Shield, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Surface(
                                shape = CircleShape,
                                color = if (dark) InfinityPureWhite.copy(alpha = 0.07f) else scheme.surfaceContainerHigh,
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(14.dp))
                                    Text("Gemini 2.5 Pro", style = InfinityTypography.labelMedium, color = scheme.onSurface)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = CircleShape,
                                color = if (dark) InfinityPureWhite.copy(alpha = 0.07f) else scheme.surfaceContainerHigh,
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Icon(Icons.Rounded.Lightbulb, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
                                    Text("高", style = InfinityTypography.labelMedium, color = scheme.onSurface)
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(scheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.ArrowUpward, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// 8 个页面的统一内容组件（A 档纯吸顶 TAB / B 档统一 56dp 轻页头）
// =========================================================================

@Composable
private fun ProtoRemoteContent(dark: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val cardBg = if (dark) ProtoObsidianElevatedChip else scheme.surfaceContainerLow
    val border = BorderStroke(0.5.dp, if (dark) InfinityPureWhite.copy(alpha = 0.08f) else scheme.outlineVariant.copy(alpha = 0.5f))
    Column(Modifier.fillMaxSize()) {
        // A 档：无冗余标题栏，TAB 锁死 y = 4dp
        InfinityPanelTabs(
            tabs = listOf(
                InfinityPanelTab("ssh", "SSH", dot = MaterialTheme.infinityDomainColors.signalOn),
                InfinityPanelTab("git", "Git"),
                InfinityPanelTab("ts", "Tailscale"),
                InfinityPanelTab("net", "网络"),
            ),
            selected = "ssh",
            onSelect = {},
            contentInset = InfinitySpacing.ScreenHorizontal,
            modifier = Modifier.padding(top = ProtoPanelTopGap),
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(shape = RoundedCornerShape(14.dp), color = cardBg, border = border, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Terminal, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text("main-gpu-01", style = InfinityTypography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = scheme.onSurface)
                        Text("100.64.0.12 · 在线 · 2 活跃会话", style = InfinityTypography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
            }
            Surface(shape = RoundedCornerShape(14.dp), color = cardBg, border = border, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Computer, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text("fnos-builder", style = InfinityTypography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = scheme.onSurface)
                        Text("100.64.0.21 · Linux 构建机", style = InfinityTypography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoCanvasContent(dark: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val cardBg = if (dark) ProtoObsidianElevatedChip else scheme.surfaceContainerLow
    val border = BorderStroke(0.5.dp, if (dark) InfinityPureWhite.copy(alpha = 0.08f) else scheme.outlineVariant.copy(alpha = 0.5f))
    Column(Modifier.fillMaxSize()) {
        // A 档：统一改为同款吸顶 InfinityPanelTabs，与 Remote 严格对齐 y = 4dp
        InfinityPanelTabs(
            tabs = listOf(
                InfinityPanelTab("all", "全部", count = "6"),
                InfinityPanelTab("proto", "原型", count = "2"),
                InfinityPanelTab("doc", "文档", count = "3"),
                InfinityPanelTab("img", "图片", count = "1"),
            ),
            selected = "all",
            onSelect = {},
            contentInset = InfinitySpacing.ScreenHorizontal,
            modifier = Modifier.padding(top = ProtoPanelTopGap),
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("原型 · 2 项", style = InfinityTypography.labelMedium, color = scheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("检视器三形态原型" to "HTML · 刚刚", "熏黑主题组件表" to "Compose · 2 分钟前").forEach { (name, meta) ->
                    Surface(shape = RoundedCornerShape(14.dp), color = cardBg, border = border, modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(10.dp))
                                    .background(if (dark) Color(0xFF1A1D28) else scheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.ViewQuilt, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(22.dp))
                            }
                            Text(name, style = InfinityTypography.labelLarge, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(meta, style = InfinityTypography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
            Text("文档 · 3 项", style = InfinityTypography.labelMedium, color = scheme.onSurfaceVariant)
            Surface(shape = RoundedCornerShape(14.dp), color = cardBg, border = border, modifier = Modifier.fillMaxWidth()) {
                Column {
                    listOf("三形态坐标审计报告.md" to "Markdown · 8.4 KB", "熏黑材质设计令牌.json" to "JSON · 2.1 KB").forEachIndexed { idx, (name, meta) ->
                        if (idx > 0) HorizontalDivider(color = if (dark) InfinityPureWhite.copy(alpha = 0.06f) else scheme.outlineVariant.copy(alpha = 0.4f))
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Rounded.Description, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, style = InfinityTypography.bodyMedium, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(meta, style = InfinityTypography.labelSmall, color = scheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoRunContent(dark: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val cardBg = if (dark) ProtoObsidianElevatedChip else scheme.surfaceContainerLow
    val border = BorderStroke(0.5.dp, if (dark) InfinityPureWhite.copy(alpha = 0.08f) else scheme.outlineVariant.copy(alpha = 0.5f))
    Column(Modifier.fillMaxSize()) {
        // B 档：状态圆点放进第二行元信息，标题首字严丝合缝对齐 x = 16dp 基准线
        ProtoUnifiedPanelHeader(
            title = "统一侧边栏与熏黑原型",
            meta = {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(MaterialTheme.infinityDomainColors.signalOn, CircleShape),
                )
                Spacer(Modifier.width(6.dp))
                Text("进行中 · 第 3 / 5 步 · 01:18", maxLines = 1)
            },
            actions = {
                IconButton(onClick = {}) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "更多", tint = scheme.onSurfaceVariant)
                }
            },
        )
        // TAB 锁死 y = 60dp，contentInset 统一为 16dp（修正原 20dp 错位）
        InfinityPanelTabs(
            tabs = listOf(
                InfinityPanelTab("plan", "计划", count = "3/5"),
                InfinityPanelTab("bg", "后台", count = "1", dot = scheme.primary),
            ),
            selected = "plan",
            onSelect = {},
            contentInset = InfinitySpacing.ScreenHorizontal,
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LinearProgressIndicator(
                progress = { 0.6f },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            listOf(
                Triple("核查 8 页面三形态坐标差异", true, false),
                Triple("收口两档顶栏与吸顶 TAB 规范", true, false),
                Triple("渲染全站熏黑主题原型并复核", false, true),
            ).forEach { (step, done, active) ->
                Surface(shape = RoundedCornerShape(12.dp), color = cardBg, border = border, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(
                            if (done) Icons.Rounded.CheckCircle else Icons.Rounded.Checklist,
                            contentDescription = null,
                            tint = when {
                                done -> MaterialTheme.infinityDomainColors.success
                                active -> scheme.primary
                                else -> scheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(18.dp),
                        )
                        Text(step, style = InfinityTypography.bodyMedium, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoGitContent(dark: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val cardBg = if (dark) ProtoObsidianElevatedChip else scheme.surfaceContainerLow
    val border = BorderStroke(0.5.dp, if (dark) InfinityPureWhite.copy(alpha = 0.08f) else scheme.outlineVariant.copy(alpha = 0.5f))
    Column(Modifier.fillMaxSize()) {
        // B 档：分支与同步摘要收进页头第二行元信息，彻底消除 360↔560dp 切换时 TAB 栏上下跳动 26dp
        ProtoUnifiedPanelHeader(
            title = "Nexara",
            meta = {
                Icon(Icons.AutoMirrored.Rounded.CallSplit, contentDescription = null, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text("main → origin/main · ↑2 · 3 分钟前", maxLines = 1)
            },
            actions = {
                IconButton(onClick = {}) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = scheme.onSurfaceVariant)
                }
            },
        )
        // TAB 严格锁死 y = 60dp，与 Run / Production 齐平
        InfinityPanelTabs(
            tabs = listOf(
                InfinityPanelTab("changes", "改动", count = "4"),
                InfinityPanelTab("commits", "提交"),
                InfinityPanelTab("branches", "分支", count = "3"),
            ),
            selected = "changes",
            onSelect = {},
            contentInset = InfinitySpacing.ScreenHorizontal,
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("未暂存 · 4", style = InfinityTypography.labelMedium, color = scheme.onSurfaceVariant)
            listOf(
                Triple("M", "ChatInspector.kt", "+48 -19"),
                Triple("M", "InfinityPanelTabs.kt", "+12 -4"),
                Triple("M", "InfinityBottomSheet.kt", "+34 -12"),
                Triple("M", "InfinityDropdownMenu.kt", "+22 -8"),
            ).forEach { (flag, file, stat) ->
                Surface(shape = RoundedCornerShape(12.dp), color = cardBg, border = border, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(flag, style = InfinityTypography.labelMedium.copy(fontFamily = InfinityMonospace, fontWeight = FontWeight.Bold), color = MaterialTheme.infinityDomainColors.warning)
                        Text(file, style = InfinityTypography.bodyMedium, color = scheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stat, style = InfinityTypography.labelSmall.copy(fontFamily = InfinityMonospace), color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoProductionContent(dark: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val cardBg = if (dark) ProtoObsidianElevatedChip else scheme.surfaceContainerLow
    val border = BorderStroke(0.5.dp, if (dark) InfinityPureWhite.copy(alpha = 0.08f) else scheme.outlineVariant.copy(alpha = 0.5f))
    Column(Modifier.fillMaxSize()) {
        // B 档：从原 74dp/68dp 收敛到统一 56dp 轻页头
        ProtoUnifiedPanelHeader(
            title = "雨夜便利店",
            meta = { Text("短片 · 12 镜 · 16:9", maxLines = 1) },
            actions = {
                Surface(
                    shape = CircleShape,
                    color = scheme.primaryContainer.copy(alpha = 0.85f),
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(13.dp))
                        Text("发送", style = InfinityTypography.labelMedium, color = scheme.onPrimaryContainer)
                    }
                }
                IconButton(onClick = {}) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "更多", tint = scheme.onSurfaceVariant)
                }
            },
        )
        // TAB 严格锁死 y = 60dp，与 Run / Git 齐平
        InfinityPanelTabs(
            tabs = listOf(
                InfinityPanelTab("overview", "总览"),
                InfinityPanelTab("script", "剧本"),
                InfinityPanelTab("assets", "资产", count = "2"),
                InfinityPanelTab("board", "分镜", dot = scheme.primary),
                InfinityPanelTab("out", "成品"),
            ),
            selected = "overview",
            onSelect = {},
            contentInset = InfinitySpacing.ScreenHorizontal,
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(shape = RoundedCornerShape(14.dp), color = cardBg, border = border, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("第一幕 · 霓虹雨夜", style = InfinityTypography.titleSmall, color = scheme.onSurface)
                    Text("已完成 8 / 12 镜分镜锁定，2 个角色设定待审", style = InfinityTypography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ProtoExplorerContent(dark: Boolean, dualPane: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val cardBg = if (dark) ProtoObsidianElevatedChip else scheme.surfaceContainerLow
    val dividerColor = if (dark) InfinityPureWhite.copy(alpha = 0.08f) else scheme.outlineVariant.copy(alpha = 0.5f)
    Column(Modifier.fillMaxSize()) {
        // B 档：去掉误加的 top=16dp，字号从 22sp 收回统一 16sp，左基线 16dp
        ProtoUnifiedPanelHeader(
            title = "v0721-e2e-ok",
            meta = {
                Text("本会话工作区", color = scheme.primary, maxLines = 1)
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
            },
            actions = {
                IconButton(onClick = {}) {
                    Icon(Icons.Rounded.Search, contentDescription = "搜索", tint = scheme.onSurfaceVariant)
                }
                IconButton(onClick = {}) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "更多", tint = scheme.onSurfaceVariant)
                }
            },
        )
        HorizontalDivider(color = dividerColor)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.then(if (dualPane) Modifier.width(260.dp) else Modifier.fillMaxWidth()).fillMaxHeight()) {
                Row(
                    Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("根目录 / native-ui", style = InfinityTypography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
                    Icon(Icons.Rounded.Sort, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("修改时间", style = InfinityTypography.labelMedium, color = scheme.onSurfaceVariant)
                }
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        Triple(Icons.Rounded.Folder, "ui/common", "14 项"),
                        Triple(Icons.Rounded.Description, "ChatInspector.kt", "18.2 KB · 刚刚"),
                        Triple(Icons.Rounded.Description, "approved_marker.txt", "0 B · 18:55"),
                        Triple(Icons.Rounded.Image, "preview_obsidian.png", "412 KB · 20:10"),
                    ).forEachIndexed { idx, (icon, name, sub) ->
                        val selected = dualPane && idx == 1
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) (if (dark) Color(0xFF191D29) else scheme.primaryContainer.copy(alpha = 0.5f)) else Color.Transparent,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(
                                    Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(cardBg),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(icon, contentDescription = null, tint = if (selected) scheme.primary else scheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(name, style = InfinityTypography.bodyMedium, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(sub, style = InfinityTypography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
            if (dualPane) {
                VerticalDivider(color = dividerColor)
                Column(Modifier.weight(1f).fillMaxHeight().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("ChatInspector.kt", style = InfinityTypography.titleSmall, color = scheme.onSurface)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = cardBg,
                        border = BorderStroke(0.5.dp, dividerColor),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) {
                        Text(
                            text = "internal fun ChatInspectorCard(\n  root: ChatPage,\n  widened: Boolean,\n  floating: Boolean,\n) {\n  // 固定分栏与扩大抽屉统一熏黑材质\n  val role = if (floating)\n    FLOATING_INSPECTOR\n  else DOCKED_INSPECTOR\n}",
                            style = InfinityTypography.bodySmall.copy(fontFamily = InfinityMonospace, lineHeight = 19.sp),
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoStateContent(dark: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val cardBg = if (dark) ProtoObsidianElevatedChip else scheme.surfaceContainerLow
    val border = BorderStroke(0.5.dp, if (dark) InfinityPureWhite.copy(alpha = 0.08f) else scheme.outlineVariant.copy(alpha = 0.5f))
    Column(Modifier.fillMaxSize()) {
        // B 档：去掉 40dp 独有方块图标与 start=20dp 偏移，对齐标准 56dp 页头与 16dp 基线
        ProtoUnifiedPanelHeader(
            title = "发布前验收看板",
            meta = { Text("智能体维护 · 更新于 01:36 · 4 项", maxLines = 1) },
            actions = {
                IconButton(onClick = {}) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "更多", tint = scheme.onSurfaceVariant)
                }
            },
        )
        HorizontalDivider(color = if (dark) InfinityPureWhite.copy(alpha = 0.08f) else scheme.outlineVariant.copy(alpha = 0.4f))
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("目标版本" to "v0.73.0-beta", "通过用例" to "169 / 169").forEach { (k, v) ->
                    Surface(shape = RoundedCornerShape(14.dp), color = cardBg, border = border, modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(k, style = InfinityTypography.labelSmall, color = scheme.onSurfaceVariant)
                            Text(v, style = InfinityTypography.titleSmall, color = scheme.onSurface)
                        }
                    }
                }
            }
            Surface(shape = RoundedCornerShape(14.dp), color = cardBg, border = border, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("当前焦点", style = InfinityTypography.labelSmall, color = scheme.onSurfaceVariant)
                    Text("三形态侧边栏对齐与全站熏黑原型评审", style = InfinityTypography.bodyMedium, color = scheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun ProtoBrowserContent(dark: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val pillBg = if (dark) ProtoObsidianElevatedChip else scheme.surfaceContainerHigh
    val dividerColor = if (dark) InfinityPureWhite.copy(alpha = 0.08f) else scheme.outlineVariant.copy(alpha = 0.45f)
    Column(Modifier.fillMaxSize()) {
        // B 档同高 56dp 地址栏（top = 4dp）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = ProtoPanelTopGap)
                .height(ProtoHeaderHeight)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = pillBg,
                border = BorderStroke(0.5.dp, dividerColor),
                modifier = Modifier.weight(1f).height(38.dp),
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    Text("developer.android.com/compose", style = InfinityTypography.bodySmall, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = {}, modifier = Modifier.size(38.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "后退", tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = {}, modifier = Modifier.size(38.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "前进", tint = scheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = {}, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
        // 多网页标签条：高度对齐 48dp + 左起 16dp + 通栏底线（与 InfinityPanelTabs 基准线对齐！）
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().height(ProtoTabHeight).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = pillBg,
                    border = BorderStroke(0.5.dp, dividerColor),
                ) {
                    Row(Modifier.height(30.dp).padding(start = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Compose Docs", style = InfinityTypography.labelMedium, color = scheme.onSurface)
                        Icon(Icons.Rounded.Close, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
                    }
                }
                Text("Material 3", style = InfinityTypography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))
                Icon(Icons.Rounded.Add, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
                Spacer(Modifier.weight(1f))
                Surface(shape = CircleShape, color = Color.Transparent, border = BorderStroke(0.5.dp, dividerColor)) {
                    Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.fillMaxHeight().background(scheme.secondaryContainer).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(14.dp))
                        }
                        Box(Modifier.fillMaxHeight().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Computer, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
            HorizontalDivider(color = dividerColor)
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(12.dp)).background(if (dark) Color(0xFF0B0D12) else scheme.surfaceContainerLowest),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.Public, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
                Text("WebView 实时网页视口", style = InfinityTypography.labelMedium, color = scheme.onSurfaceVariant)
            }
        }
    }
}

/** 坐标基准线叠加（仅用于对齐矩阵原型核验 y=4 / y=52 / y=60 / y=108）。 */
@Composable
private fun BoxScope.AlignmentGuideLines(tierA: Boolean) {
    Canvas(Modifier.matchParentSize()) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        val y1 = if (tierA) (ProtoPanelTopGap + ProtoTabHeight).toPx() else (ProtoPanelTopGap + ProtoHeaderHeight).toPx()
        drawLine(
            color = Color(0x6638BDF8),
            start = Offset(0f, y1),
            end = Offset(size.width, y1),
            strokeWidth = 1.dp.toPx(),
            pathEffect = dash,
        )
        if (!tierA) {
            val y2 = (ProtoPanelTopGap + ProtoHeaderHeight + ProtoTabHeight).toPx()
            drawLine(
                color = Color(0x664ADE80),
                start = Offset(0f, y2),
                end = Offset(size.width, y2),
                strokeWidth = 1.dp.toPx(),
                pathEffect = dash,
            )
        }
        val x16 = 16.dp.toPx()
        drawLine(
            color = Color(0x55FACC15),
            start = Offset(x16, 0f),
            end = Offset(x16, 128.dp.toPx()),
            strokeWidth = 1.dp.toPx(),
            pathEffect = dash,
        )
    }
}

// =========================================================================
// Preview 1: 内屏固定分栏（360dp 熏黑实底 + 统一 56dp 页头与 y=60dp 吸顶 TAB）
// =========================================================================

@Preview(name = "A1-内屏固定分栏-360dp熏黑实底", widthDp = 840, heightDp = 900, showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun UnifiedDockedInspectorDarkPreview() {
    ProtoTheme(dark = true) {
        Box(Modifier.fillMaxSize().background(Color(0xFF000000))) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    ProtoChatBackdropCanvas(dark = true, showComposer = true)
                }
                // 右侧 360dp 固定分栏（位于顶栏下方，熏黑实底 #070709，零模糊开销却与浮岛视觉完全统一）
                Box(
                    Modifier
                        .width(368.dp)
                        .fillMaxHeight()
                        .padding(top = 72.dp, end = 8.dp, bottom = 8.dp),
                ) {
                    ProtoObsidianPanelFrame(dark = true, frosted = false, modifier = Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxWidth()) { ProtoGitContent(dark = true) }
                            ProtoInspectorBottomBar(active = ChatPage.Git, dark = true)
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// Preview 2: 全部 8 页面坐标基准对齐矩阵（2x4，360dp 真实卡宽 + 虚线基准线）
// =========================================================================

@Preview(name = "A2-全部8页坐标基准对齐矩阵-360dp", widthDp = 1528, heightDp = 1020, showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun UnifiedAll8PagesAlignmentMatrixDarkPreview() {
    ProtoTheme(dark = true) {
        Column(
            modifier = Modifier.fillMaxSize().background(Color(0xFF000000)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 图例说明条
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text("全部 8 页在 360dp 侧边栏下的统一坐标基准线验证", style = InfinityTypography.titleSmall, color = InfinityPureWhite)
                Text("黄虚线：x = 16dp 统一左基线", style = InfinityTypography.labelMedium, color = Color(0xFFFACC15))
                Text("蓝虚线：A档 TAB 底 y=52dp / B档页头底 y=60dp", style = InfinityTypography.labelMedium, color = Color(0xFF38BDF8))
                Text("绿虚线：B档吸顶 TAB 底 y=108dp", style = InfinityTypography.labelMedium, color = Color(0xFF4ADE80))
            }

            @Composable
            fun PageCard(
                label: String,
                page: ChatPage,
                tierA: Boolean,
                modifier: Modifier,
                content: @Composable () -> Unit,
            ) {
                Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(label, style = InfinityTypography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ProtoObsidianPanelFrame(dark = true, frosted = false, modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                content()
                                AlignmentGuideLines(tierA = tierA)
                            }
                            ProtoInspectorBottomBar(active = page, dark = true)
                        }
                    }
                }
            }

            // 第一行：A 档（远程、产物）+ B 档带 TAB（任务、Git）
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PageCard("A档 · 产物（改吸顶 TAB y=4..52）", ChatPage.Canvas, tierA = true, Modifier.weight(1f)) { ProtoCanvasContent(true) }
                PageCard("B档 · 任务（56dp 轻页头 + TAB y=60..108）", ChatPage.Run, tierA = false, Modifier.weight(1f)) { ProtoRunContent(true) }
                PageCard("B档 · Git（分支并入页头 + TAB y=60..108）", ChatPage.Git, tierA = false, Modifier.weight(1f)) { ProtoGitContent(true) }
            }
            // 第二行：B 档带 TAB（制作）+ B 档上下文页头（文件、状态、浏览器）
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PageCard("B档 · 制作（56dp 轻页头 + TAB y=60..108）", ChatPage.Production, tierA = false, Modifier.weight(1f)) { ProtoProductionContent(true) }
                PageCard("B档 · 文件（去 top=16dp，16sp 56dp 页头）", ChatPage.Explorer, tierA = false, Modifier.weight(1f)) { ProtoExplorerContent(true) }
                PageCard("B档 · 状态（去大方块图标，16dp 56dp 页头）", ChatPage.State, tierA = false, Modifier.weight(1f)) { ProtoStateContent(true) }
                PageCard("B档 · 浏览器（56dp 地址栏 + 48dp 标签栏对齐）", ChatPage.Browser, tierA = false, Modifier.weight(1f)) { ProtoBrowserContent(true) }
            }
        }
    }
}

// =========================================================================
// Preview 3: 内屏扩大侧边栏（560dp 熏黑实时毛玻璃覆盖抽屉 + 文件双栏统一 56dp 轻页头）
// =========================================================================

@Preview(name = "A3-内屏扩大侧边栏-560dp熏黑毛玻璃", widthDp = 840, heightDp = 900, showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun UnifiedWidenedDrawerDarkPreview() {
    ProtoTheme(dark = true) {
        Box(Modifier.fillMaxSize().background(Color(0xFF000000))) {
            ProtoChatBackdropCanvas(dark = true, showComposer = true)
            // 顶栏下方暗幕
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = 64.dp)
                    .background(Color(0xFF000000).copy(alpha = 0.48f)),
            )
            // 右侧 560dp 全高熏黑毛玻璃抽屉
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 72.dp, end = 8.dp, bottom = 8.dp)
                    .width(560.dp)
                    .fillMaxHeight(),
            ) {
                ProtoObsidianPanelFrame(
                    dark = true,
                    frosted = true,
                    modifier = Modifier.fillMaxSize(),
                    blurredBehindContent = { ProtoChatBackdropCanvas(dark = true, showComposer = true) },
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { ProtoExplorerContent(dark = true, dualPane = true) }
                        ProtoInspectorBottomBar(active = ChatPage.Explorer, dark = true, widened = true)
                    }
                }
            }
        }
    }
}

// =========================================================================
// Preview 4: 竖屏形态——同窗口 93% 全高熏黑毛玻璃上滑抽屉（暗色 A档产物 + B档Git 并排）
// =========================================================================

@Preview(name = "A4-竖屏93%熏黑上滑抽屉-A档与B档对照", widthDp = 856, heightDp = 915, showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun UnifiedPortraitObsidianDrawerDarkPreview() {
    ProtoTheme(dark = true) {
        Row(
            Modifier.fillMaxSize().background(Color(0xFF000000)).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            listOf(
                Triple(ChatPage.Canvas, "产物", true),
                Triple(ChatPage.Git, "Git", false),
            ).forEach { (page, _, isCanvas) ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(28.dp))
                        .border(1.dp, InfinityPureWhite.copy(alpha = 0.10f), RoundedCornerShape(28.dp)),
                ) {
                    // 背后聊天界面
                    ProtoChatBackdropCanvas(dark = true, showComposer = true)
                    // 顶部暗幕
                    Box(Modifier.fillMaxSize().background(Color(0xFF000000).copy(alpha = 0.56f)))
                    // 同窗口 93% 全高熏黑毛玻璃上滑抽屉
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(0.93f),
                    ) {
                        ProtoObsidianPanelFrame(
                            dark = true,
                            frosted = true,
                            cornerRadius = 24.dp,
                            bottomCornersFlat = true,
                            modifier = Modifier.fillMaxSize(),
                            blurredBehindContent = { ProtoChatBackdropCanvas(dark = true, showComposer = false) },
                        ) {
                            Column(Modifier.fillMaxSize()) {
                                // 顶部居中拖拽把手（仅把手/页头区可下拉关闭）
                                Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                                    Box(
                                        Modifier
                                            .width(36.dp)
                                            .height(4.dp)
                                            .clip(CircleShape)
                                            .background(InfinityPureWhite.copy(alpha = 0.24f)),
                                    )
                                }
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    if (isCanvas) ProtoCanvasContent(dark = true) else ProtoGitContent(dark = true)
                                }
                                ProtoInspectorBottomBar(active = page, dark = true, portrait = true)
                                Spacer(Modifier.height(10.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// Preview 5: 浅色主题冷白通透同步验收（竖屏上滑抽屉 + 内屏固定分栏）
// =========================================================================

@Preview(name = "A5-浅色主题-竖屏冷白上滑抽屉", widthDp = 412, heightDp = 915, showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
fun UnifiedPortraitDrawerLightPreview() {
    ProtoTheme(dark = false) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            ProtoChatBackdropCanvas(dark = false, showComposer = true)
            Box(Modifier.fillMaxSize().background(Color(0xFF000000).copy(alpha = 0.20f)))
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.93f),
            ) {
                ProtoObsidianPanelFrame(
                    dark = false,
                    frosted = true,
                    cornerRadius = 24.dp,
                    bottomCornersFlat = true,
                    modifier = Modifier.fillMaxSize(),
                    blurredBehindContent = { ProtoChatBackdropCanvas(dark = false, showComposer = false) },
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier
                                    .width(36.dp)
                                    .height(4.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.outlineVariant),
                            )
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            ProtoGitContent(dark = false)
                        }
                        ProtoInspectorBottomBar(active = ChatPage.Git, dark = false, portrait = true)
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
    }
}
