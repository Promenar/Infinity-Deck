package com.promenar.infinity.ui.production

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.FrostedChromeRole
import com.promenar.infinity.ui.common.InfinityPanePresentation
import com.promenar.infinity.ui.common.InfinityPanelTab
import com.promenar.infinity.ui.common.InfinityPanelTabs
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySwitch
import com.promenar.infinity.ui.common.LocalFrostedChromeMaterial
import com.promenar.infinity.ui.common.LocalInfinityPanePresentation
import com.promenar.infinity.ui.common.rememberFrostedChromeMaterial
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

/*
 * 制作面板总览进度置顶、剧本空态修复与空面板挂载入口高保真原型（v1，隔离于 debug 源码）：
 * 1. 总览顶部紧凑项目进度摘要卡：置于「制作流程」正上方，单集显示全片定稿进度，多集显示全剧汇总进度并在下方保留逐季分集明细；
 * 2. 剧本空态状态一致性修复：episode.scriptPath == null 时总览剧本行显示中性「未开始」，不再误报橙色「待审」；
 * 3. 空制作面板挂载与建项引导：支持一键挂载工作区扫描到的已有 production.json，或一键引导智能体为当前会话建项。
 */

private data class ProtoManifestCandidate(
    val title: String,
    val path: String,
    val meta: String,
)

@Composable
private fun ProtoProductionFrame(
    mode: InfinityThemeMode,
    inspectorChrome: Boolean = false,
    sendCount: Int = 0,
    content: @Composable () -> Unit,
) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        val chrome = if (inspectorChrome) rememberFrostedChromeMaterial(FrostedChromeRole.DOCKED_INSPECTOR) else null
        val surfaceColor = chrome?.frostedTint ?: chrome?.container ?: MaterialTheme.colorScheme.background
        CompositionLocalProvider(
            LocalFrostedChromeMaterial provides chrome,
            LocalInfinityPanePresentation provides if (inspectorChrome) InfinityPanePresentation.Inspector else InfinityPanePresentation.Page,
            LocalProductionSend provides ProductionSendState(count = sendCount, sending = false, queued = false, onSend = {}),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(if (inspectorChrome && mode == InfinityThemeMode.DARK) Color(0xFF0A0B0E) else MaterialTheme.colorScheme.background),
            ) {
                Surface(color = surfaceColor, modifier = Modifier.fillMaxSize()) {
                    content()
                }
            }
        }
    }
}

/** 置顶紧凑项目进度摘要卡：单集显示「项目进度」，多集显示「全剧进度」，点击直达对应分镜/出片进度入口。 */
@Composable
private fun ProtoTopProgressCard(
    label: String,
    summary: String,
    progress: Float,
    detail: String? = null,
    onClick: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    BettboxListGroup {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            ProductionProgressBar(progress)
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ProtoProductionHeaderAndTabs(
    title: String,
    meta: List<String>,
) {
    ProductionTopBar(
        title = title,
        meta = meta,
        onBack = null,
    ) {
        IconButton(onClick = {}) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "更多")
        }
    }
    InfinityPanelTabs(
        tabs = listOf(
            InfinityPanelTab(ProductionTab.OVERVIEW, "总览"),
            InfinityPanelTab(ProductionTab.SCRIPT, "剧本"),
            InfinityPanelTab(ProductionTab.ASSETS, "资产"),
            InfinityPanelTab(ProductionTab.STORYBOARD, "分镜"),
            InfinityPanelTab(ProductionTab.OUTPUT, "出片"),
            InfinityPanelTab(ProductionTab.CUT, "剪辑"),
        ),
        selected = ProductionTab.OVERVIEW,
        onSelect = {},
        contentInset = InfinitySpacing.ScreenHorizontal,
    )
}

/** 空制作面板原型：支持一键挂载工作区已有的 production.json 与引导智能体新建制作项目。 */
@Composable
private fun ProtoProductionEmptyContent(
    candidates: List<ProtoManifestCandidate>,
) {
    val scheme = MaterialTheme.colorScheme
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = InfinitySpacing.ScreenHorizontal, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(ProductionSectionGap),
    ) {
        item("hero") {
            BettboxListGroup {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = scheme.primaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Movie,
                                contentDescription = null,
                                tint = scheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    Text(
                        text = "当前会话未关联制作项目",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = if (candidates.isNotEmpty()) {
                            "可直接挂载工作区里已有的制作项目，或让智能体按当前需求新建项目与分镜。"
                        } else {
                            "可让智能体把本轮视频方案、视觉资产与分镜直接装载到制作面板，方便逐步审片。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(2.dp))
                    InfinityPillButton(
                        text = "让智能体建立制作项目",
                        onClick = {},
                        tone = InfinityPillTone.PRIMARY,
                        icon = Icons.Rounded.AutoAwesome,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (candidates.isEmpty()) {
                        InfinityPillButton(
                            text = "扫描工作区项目",
                            onClick = {},
                            tone = InfinityPillTone.NEUTRAL,
                            icon = Icons.Rounded.Refresh,
                            compact = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        if (candidates.isNotEmpty()) {
            item("candidates") {
                ProductionSection(
                    title = "工作区已有项目",
                    titleAction = {
                        InfinityPillButton(
                            text = "重新扫描",
                            onClick = {},
                            tone = InfinityPillTone.NEUTRAL,
                            icon = Icons.Rounded.Refresh,
                            compact = true,
                        )
                    },
                ) {
                    candidates.forEachIndexed { index, item ->
                        if (index > 0) {
                            HorizontalDivider(
                                Modifier.padding(horizontal = InfinitySpacing.RowHorizontal),
                                color = scheme.outlineVariant.copy(alpha = 0.4f),
                            )
                        }
                        ProductionRow(
                            title = item.title,
                            icon = Icons.Rounded.FolderOpen,
                            subtitleSegments = listOf(item.meta, item.path),
                            subtitleMaxLines = 2,
                            chevron = false,
                            trailing = {
                                InfinityPillButton(
                                    text = "挂载",
                                    onClick = {},
                                    tone = InfinityPillTone.NEUTRAL,
                                    icon = Icons.Rounded.Link,
                                    compact = true,
                                )
                            },
                            onClick = {},
                        )
                    }
                }
            }
        }
    }
}

// ---------- 4 个 Android Compose Preview 原型 ----------

@Preview(name = "1. 单集电影总览 · 进度置顶与剧本未开始(深色边栏)", widthDp = 380, heightDp = 860, locale = "zh-rCN")
@Composable
fun overviewSingleFilmDarkInspectorPreview() = ProtoProductionFrame(
    mode = InfinityThemeMode.DARK,
    inspectorChrome = true,
    sendCount = 4,
) {
    Column(Modifier.fillMaxSize()) {
        ProtoProductionHeaderAndTabs(
            title = "惊澜三十秒",
            meta = listOf("电影", "6 个镜头", "16:9 横屏，1344×768"),
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = InfinitySpacing.ScreenHorizontal, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("top-progress") {
                ProtoTopProgressCard(
                    label = "项目进度",
                    summary = "0/6 镜已定稿",
                    progress = 0f,
                )
            }
            item("stages") {
                ProductionSection("制作流程") {
                    ProductionRow(
                        title = "剧本",
                        icon = Icons.Rounded.Description,
                        subtitle = "暂无剧本",
                        chip = ProductionChipSpec("未开始", ProductionTone.NEUTRAL),
                        onClick = {},
                    )
                    ProductionRow(
                        title = "视觉资产",
                        icon = Icons.Rounded.Palette,
                        subtitle = "角色 1 · 场景 1 · 道具 1",
                        chip = ProductionChipSpec("资产 3/3 已批准", ProductionTone.SUCCESS),
                        onClick = {},
                    )
                    ProductionRow(
                        title = "分镜",
                        icon = Icons.AutoMirrored.Rounded.ViewList,
                        subtitle = "1 场 · 6 镜 · 30 秒",
                        chip = ProductionChipSpec("1/1 场已批准", ProductionTone.SUCCESS),
                        onClick = {},
                    )
                    ProductionRow(
                        title = "分镜预演",
                        icon = Icons.Rounded.Slideshow,
                        subtitle = "0/6 镜 · 可选环节",
                        chevron = false,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("跳过", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                InfinitySwitch(checked = false, onCheckedChange = null)
                            }
                        },
                        onClick = {},
                    )
                    ProductionRow(
                        title = "出片",
                        icon = Icons.Rounded.Movie,
                        subtitle = "0/6 镜已批准",
                        chip = ProductionChipSpec("0/6 镜", ProductionTone.NEUTRAL),
                        onClick = {},
                    )
                    ProductionRow(
                        title = "声音",
                        icon = Icons.Rounded.GraphicEq,
                        subtitle = "对白 0 条 · 声音资产 1",
                        chip = ProductionChipSpec("未开始", ProductionTone.NEUTRAL),
                        onClick = {},
                    )
                    ProductionRow(
                        title = "剪辑",
                        icon = Icons.Rounded.ContentCut,
                        subtitle = "暂无粗剪版本",
                        chip = ProductionChipSpec("未开始", ProductionTone.NEUTRAL),
                        onClick = {},
                    )
                }
            }
            item("tasks") {
                ProductionSection("任务", trailing = "0 项进行中") {
                    ProductionMutedRow("队列为空")
                }
            }
        }
    }
}

@Preview(name = "2. 多集剧集总览 · 全剧汇总置顶与大字号适配(浅色·1.3x)", widthDp = 360, heightDp = 1040, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun overviewSeriesLightLargeFontPreview() = ProtoProductionFrame(
    mode = InfinityThemeMode.LIGHT,
    inspectorChrome = false,
    sendCount = 0,
) {
    Column(Modifier.fillMaxSize()) {
        ProtoProductionHeaderAndTabs(
            title = "雾港",
            meta = listOf("剧集", "2 集", "5 个镜头", "16:9"),
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = InfinitySpacing.ScreenHorizontal, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("top-progress") {
                ProtoTopProgressCard(
                    label = "全剧进度",
                    summary = "1/5 镜已定稿",
                    progress = 0.2f,
                    detail = "共 2 集 · 1 镜待审 · 1 镜需重做",
                )
            }
            item("stages") {
                ProductionSection("制作流程") {
                    ProductionRow(
                        title = "剧本",
                        icon = Icons.Rounded.Description,
                        subtitle = "2 集",
                        chip = ProductionChipSpec("1/2 集", ProductionTone.WARNING),
                        onClick = {},
                    )
                    ProductionRow(
                        title = "视觉资产",
                        icon = Icons.Rounded.Palette,
                        subtitle = "角色 2 · 场景 1 · 道具 1",
                        chip = ProductionChipSpec("2/4 · 1 待审", ProductionTone.WARNING),
                        onClick = {},
                    )
                    ProductionRow(
                        title = "分镜",
                        icon = Icons.AutoMirrored.Rounded.ViewList,
                        subtitle = "2 场 · 5 镜 · 22.5 秒",
                        chip = ProductionChipSpec("1 场待审", ProductionTone.WARNING),
                        onClick = {},
                    )
                    ProductionRow(
                        title = "出片",
                        icon = Icons.Rounded.Movie,
                        subtitle = "1/5 镜已批准",
                        chip = ProductionChipSpec("1/5 · 1 重做", ProductionTone.ERROR),
                        onClick = {},
                    )
                }
            }
            item("season-1") {
                ProductionSection("第 1 季") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("第 1 集 归港", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.2f))
                        ProductionProgressBar(0.2f, Modifier.weight(1f))
                        Text("1/5", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("第 2 集 潮汐表", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.2f))
                        ProductionProgressBar(0f, Modifier.weight(1f))
                        Text("剧本", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Preview(name = "3. 空制作面板 · 发现工作区已有项目一键挂载(深色边栏)", widthDp = 380, heightDp = 760, locale = "zh-rCN")
@Composable
fun emptyPanelWithCandidatesDarkInspectorPreview() = ProtoProductionFrame(
    mode = InfinityThemeMode.DARK,
    inspectorChrome = true,
) {
    Column(Modifier.fillMaxSize()) {
        ProductionTopBar(title = "制作面板", meta = emptyList(), onBack = null)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ProtoProductionEmptyContent(
                candidates = listOf(
                    ProtoManifestCandidate(
                        title = "惊澜三十秒",
                        meta = "电影 · 6 镜",
                        path = "AI-Assets/Productions/series/huaxia-chronicles/development/jinglan-30s/production.json",
                    ),
                    ProtoManifestCandidate(
                        title = "雾港",
                        meta = "剧集 · 2 集",
                        path = "productions/harbor/production.json",
                    ),
                ),
            )
        }
    }
}

@Preview(name = "4. 空制作面板 · 工作区尚无项目引导建项(浅色·1.3x)", widthDp = 360, heightDp = 680, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun emptyPanelNoCandidatesLightLargeFontPreview() = ProtoProductionFrame(
    mode = InfinityThemeMode.LIGHT,
    inspectorChrome = false,
) {
    Column(Modifier.fillMaxSize()) {
        ProductionTopBar(title = "制作面板", meta = emptyList(), onBack = null)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ProtoProductionEmptyContent(candidates = emptyList())
        }
    }
}
