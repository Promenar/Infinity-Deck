package com.promenar.infinity.ui.production

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.SubdirectoryArrowRight
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Web
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.FrostedChromeRole
import com.promenar.infinity.ui.common.InfinityChip
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPanePresentation
import com.promenar.infinity.ui.common.InfinityPanelTab
import com.promenar.infinity.ui.common.InfinityPanelTabs
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinitySwitch
import com.promenar.infinity.ui.common.LocalFrostedChromeMaterial
import com.promenar.infinity.ui.common.LocalInfinityPanePresentation
import com.promenar.infinity.ui.common.rememberFrostedChromeMaterial
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.forReading
import com.promenar.infinity.ui.theme.infinityDomainColors

/*
 * 本轮全部前端改动的高保真 Compose 原型套件（v1.2，隔离于 src/debug，共 9 组预览，完整覆盖所有受影响前端界面）：
 * 1. 制作总览：16dp 顶距 + 移除「声音」行 + 新增可选「后处理」跳过开关 + 底部「任务」卡同步 comfy_run_workflow 实时进度；
 * 2. 后处理页签（方案 A）：动态展示智能体注册的 DERIVE 类工作流 + 筛选 + 派生对比卡 + 未配置工作流空态引导；
 * 3. 单镜规格临时覆写：镜头规划「规格」行由只读升级为可点按配置 + 底部通用配置面板（ComfyUI 通用宽高透传 + 云端动态分辨率档位）；
 * 4. Guide 强锁帧（ANCHOR / GUIDES）全流程 UI：输入区锁帧标识与「管理锁帧」入口 + 底部锚点管理弹窗 + 链式续写卡自动联动提示；
 * 5. 任务页签 Todo 总进度条：top 10dp → 16dp（无进度条时 8dp → 14dp）；
 * 6. 远程页签：top 4dp (XSmall) → 16dp（含 Linux 提示条顶距协调）；
 * 7. Git 变更页签：移除固定 Spacer(8dp)，改为列表 contentPadding(top = 14dp) 消除滚动硬裁切；
 * 8. 产物页签：top 8dp → 14dp；
 * 9. 知识检索详情弹层：移除固定 Spacer(8dp)，将 top = 14dp 移入各页签 LazyColumn 的 contentPadding。
 */

private enum class ProtoProdTab { OVERVIEW, SCRIPT, ASSETS, STORYBOARD, OUTPUT, POST_PROCESS, CUT }

private val ProtoImprovedPagePadding = PaddingValues(
    start = InfinitySpacing.Large,
    top = InfinitySpacing.Large, // 16dp：与全站基准（SessionSettings / ComfySettings）对齐
    end = InfinitySpacing.Large,
    bottom = 20.dp,
)

@Composable
private fun ProtoUpgradeFrame(
    mode: InfinityThemeMode,
    inspectorChrome: Boolean = false,
    content: @Composable () -> Unit,
) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        val chrome = if (inspectorChrome) rememberFrostedChromeMaterial(FrostedChromeRole.DOCKED_INSPECTOR) else null
        val surfaceColor = chrome?.frostedTint ?: chrome?.container ?: MaterialTheme.colorScheme.background
        CompositionLocalProvider(
            LocalFrostedChromeMaterial provides chrome,
            LocalInfinityPanePresentation provides if (inspectorChrome) InfinityPanePresentation.Inspector else InfinityPanePresentation.Page,
            LocalProductionSend provides ProductionSendState(count = 0, sending = false, queued = false, onSend = {}),
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

@Composable
private fun ProtoTabsHeader(
    selected: ProtoProdTab,
    showPostProcess: Boolean = true,
) {
    val tabs = buildList {
        add(InfinityPanelTab(ProtoProdTab.OVERVIEW, "总览"))
        add(InfinityPanelTab(ProtoProdTab.SCRIPT, "剧本"))
        add(InfinityPanelTab(ProtoProdTab.ASSETS, "资产"))
        add(InfinityPanelTab(ProtoProdTab.STORYBOARD, "分镜"))
        add(InfinityPanelTab(ProtoProdTab.OUTPUT, "出片", count = "2"))
        if (showPostProcess) add(InfinityPanelTab(ProtoProdTab.POST_PROCESS, "后处理", count = "1"))
        add(InfinityPanelTab(ProtoProdTab.CUT, "剪辑"))
    }
    InfinityPanelTabs(
        tabs = tabs,
        selected = selected,
        onSelect = {},
        contentInset = InfinitySpacing.ScreenHorizontal,
    )
}

// =====================================================================
// 1. 制作总览：16dp 顶距 + 移除声音 + 新增可选后处理 + comfy_run_workflow 任务同步
// =====================================================================

@Composable
private fun ProtoOverviewPipelineContent() {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        ProtoTabsHeader(selected = ProtoProdTab.OVERVIEW, showPostProcess = true)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = ProtoImprovedPagePadding,
            verticalArrangement = Arrangement.spacedBy(ProductionSectionGap),
        ) {
            item("top-progress") {
                BettboxListGroup {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "项目进度",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = scheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "2/6 镜已定稿",
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurfaceVariant,
                            )
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        ProductionProgressBar(2f / 6f)
                    }
                }
            }
            item("stages") {
                ProductionSection("制作流程") {
                    ProductionRow("剧本", icon = Icons.Rounded.Description, subtitle = "暂无剧本文档", chip = ProductionChipSpec("未开始", ProductionTone.NEUTRAL))
                    ProductionRow("视觉资产", icon = Icons.Rounded.Palette, subtitle = "角色 1 · 场景 1 · 道具 1", chip = ProductionChipSpec("资产 3/3 已批准", ProductionTone.SUCCESS))
                    ProductionRow("分镜", icon = Icons.AutoMirrored.Rounded.ViewList, subtitle = "1 场 · 6 镜", chip = ProductionChipSpec("1/1 场已批准", ProductionTone.SUCCESS))
                    ProductionRow(
                        title = "动态预演",
                        icon = Icons.Rounded.Slideshow,
                        subtitle = "可选 · 0/6 镜有预演",
                        chevron = false,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("跳过", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                                InfinitySwitch(checked = true, onCheckedChange = null)
                            }
                        },
                    )
                    ProductionRow("出片", icon = Icons.Rounded.Movie, subtitle = "2 镜已定稿 · 2 镜待审", chip = ProductionChipSpec("2/6 · 2 待审", ProductionTone.INFO))
                    ProductionRow(
                        title = "后处理",
                        icon = Icons.Rounded.AutoFixHigh,
                        subtitle = "可选 · 2/6 镜已派生（1 待审）",
                        chevron = false,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("跳过", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                                InfinitySwitch(checked = false, onCheckedChange = null)
                            }
                        },
                        onClick = {},
                    )
                    ProductionRow("剪辑", icon = Icons.Rounded.ContentCut, subtitle = "暂无版本", chip = ProductionChipSpec("未开始", ProductionTone.NEUTRAL))
                }
            }
            item("tasks") {
                ProductionSection("任务", trailing = "进行中 1") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "SC01-03 低身三百六十度旋斩",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = scheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                InfinityMetaLine(
                                    segments = listOf("ComfyUI 兼容层", "本地视频工作流", "1920×1088", "采样中"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                            ProductionStatusChip("64%", ProductionTone.INFO)
                        }
                        ProductionProgressBar(0.64f)
                    }
                }
            }
        }
    }
}

// =====================================================================
// 2. 方案 A 动态「后处理」页签（v1.3：去掉顶部臃肿蓝卡，与「出片」页签同构精简）
// =====================================================================

@Composable
private fun ProtoPostProcessTabContent() {
    val scheme = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize()) {
        ProtoTabsHeader(selected = ProtoProdTab.POST_PROCESS, showPostProcess = true)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = ProtoImprovedPagePadding,
            verticalArrangement = Arrangement.spacedBy(ProductionSectionGap),
        ) {
            item("filter") {
                InfinitySegmentedControl(
                    segments = listOf(
                        InfinitySegment("all", "全部 6"),
                        InfinitySegment("pending", "待处理 4"),
                        InfinitySegment("review", "待审 1"),
                        InfinitySegment("done", "已定稿 1"),
                    ),
                    selected = "all",
                    onSelect = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item("list") {
                ProductionSection("第 1 场 · 外 临溪石坪 雨夜") {
                    ProtoPostProcessShotItem(
                        code = "SC01-03",
                        title = "低身三百六十度旋斩",
                        sourceSegments = listOf("原片 #1", "1344×768", "5 秒"),
                        derivedSegments = listOf("派生 #2", "2688×1536", "已注册派生工作流"),
                        chip = ProductionChipSpec("待审", ProductionTone.WARNING),
                        actionLabel = "对比审片",
                        actionPrimary = true,
                    )
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f), modifier = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal))
                    ProtoPostProcessShotItem(
                        code = "SC01-04",
                        title = "腾空反手下劈",
                        sourceSegments = listOf("原片 #1", "1344×768", "5 秒"),
                        derivedSegments = listOf("派生 #2", "2688×1536", "已注册派生工作流"),
                        chip = ProductionChipSpec("已定稿", ProductionTone.SUCCESS),
                        actionLabel = "查看成片",
                        actionPrimary = false,
                    )
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f), modifier = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal))
                    ProtoPostProcessShotItem(
                        code = "SC01-01",
                        title = "雨中静立",
                        sourceSegments = listOf("已定稿原片 #1", "1344×768"),
                        derivedSegments = null,
                        chip = ProductionChipSpec("待后处理", ProductionTone.NEUTRAL),
                        actionLabel = null,
                        actionPrimary = false,
                    )
                }
            }
            item("empty-state-preview") {
                ProductionSection("无内容时的极简空态") {
                    ProductionMutedRow("暂无后处理内容；可由智能体调用已配置的派生工作流产出，或在总览跳过本阶段。")
                }
            }
        }
    }
}

@Composable
private fun ProtoPostProcessShotItem(
    code: String,
    title: String,
    sourceSegments: List<String>,
    derivedSegments: List<String>?,
    chip: ProductionChipSpec,
    actionLabel: String?,
    actionPrimary: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = {})
            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(code, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = scheme.primary)
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            ProductionStatusChip(chip.text, chip.tone)
        }
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = scheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfinityMetaLine(segments = sourceSegments, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                if (derivedSegments != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.AutoFixHigh, null, tint = scheme.primary, modifier = Modifier.size(14.dp))
                        InfinityMetaLine(segments = derivedSegments, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
                    }
                } else {
                    Text("尚未派生后处理版本", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant.copy(alpha = 0.8f))
                }
            }
        }
        if (actionLabel != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                InfinityPillButton(
                    text = actionLabel,
                    onClick = {},
                    tone = if (actionPrimary) InfinityPillTone.PRIMARY else InfinityPillTone.NEUTRAL,
                    icon = Icons.Rounded.RateReview,
                    compact = true,
                )
            }
        }
    }
}

// =====================================================================
// 3. 单镜头规格（v1.3：回归正常生成参数定位，去掉「临时覆写」心智负担）
// =====================================================================

@Composable
private fun ProtoShotSpecsOverrideContent() {
    val scheme = MaterialTheme.colorScheme

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = ProtoImprovedPagePadding,
        verticalArrangement = Arrangement.spacedBy(ProductionSectionGap),
    ) {
        item("channel-mode") {
            ProductionSection("通道与模式") {
                ProductionRow("模式", value = "多参", onClick = {})
                ProductionRow("通道", valueSegments = listOf("已注册视频工作流", "MAIN 4090"), onClick = {})
                ProductionRow(
                    title = "规格",
                    subtitle = "默认 1344×768",
                    valueSegments = listOf("1920×1088", "124 帧"),
                    chip = ProductionChipSpec("自定", ProductionTone.NEUTRAL),
                    onClick = {},
                )
                ProductionRow("预演素材", value = "无", chevron = false)
            }
        }
        item("specs-sheet-preview") {
            ProductionSection("点按「规格」后的底部选择面板") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "调整当前镜头的输出分辨率，作为正常生成参数传入；不影响工作流或模型默认设置。",
                        style = MaterialTheme.typography.bodySmall.forReading(),
                        color = scheme.onSurfaceVariant,
                    )
                    Text("ComfyUI 通道（透传至工作流声明的宽高生成参数槽）", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfinityChip("跟随默认 (1344×768)", selected = false, onClick = {})
                        InfinityChip("自定义宽高", selected = true, onClick = {})
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProtoNumericFieldChip(label = "宽 (px)", value = "1920", modifier = Modifier.weight(1f))
                        ProtoNumericFieldChip(label = "高 (px)", value = "1088", modifier = Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        InfinityPillButton(text = "恢复默认", onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true)
                        Spacer(Modifier.width(8.dp))
                        InfinityPillButton(text = "应用", onClick = {}, tone = InfinityPillTone.PRIMARY, icon = Icons.Rounded.Check, compact = true)
                    }
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f))
                    Text("云端通道（按该通道 capabilities.resolutions 动态渲染）", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfinityChip("跟随默认", selected = false, onClick = {})
                        InfinityChip("480p", selected = false, onClick = {})
                        InfinityChip("720p", selected = false, onClick = {})
                        InfinityChip("1080p", selected = true, onClick = {})
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoNumericFieldChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = scheme.onSurface)
        }
    }
}

// =====================================================================
// 4. GUIDES 与链式 Motion Context 完整只读检视（v1.3：纯检视，去除所有人工编辑交互）
// =====================================================================

@Composable
private fun ProtoGuideAnchorFlowContent() {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors

    Column(Modifier.fillMaxSize()) {
        // 真实镜头详情顶部上下文条
        Surface(color = scheme.surfaceContainerLow) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Large, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SC01-03", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = scheme.primary)
                    Text("低身三百六十度旋斩", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    ProductionStatusChip("待审", ProductionTone.WARNING)
                }
                InfinityMetaLine(
                    segments = listOf("多参", "1920×1088", "2 段链式 · 10.0 秒"),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f))

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = ProtoImprovedPagePadding,
            verticalArrangement = Arrangement.spacedBy(ProductionSectionGap),
        ) {
            item("inputs-readonly") {
                ProductionSection(
                    title = "输入素材",
                    titleSegments = listOf("输入素材", "分张", "2 张参考", "1 帧锁帧"),
                    trailing = "只读检视",
                ) {
                    // 锁帧行与普通参考图严格同列对齐缩略图，仅以徽章+时序信息一眼区分，不设手动编辑按钮
                    ProtoReadOnlyInputRow(
                        icon = Icons.Rounded.Image,
                        title = "起手跃起关键帧",
                        meta = listOf("第 0 帧（0.0 秒）", "锚定起手构图"),
                        badge = "锁帧",
                        isAnchor = true,
                    )
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(start = 70.dp, end = InfinitySpacing.RowHorizontal))
                    ProtoReadOnlyInputRow(
                        icon = Icons.Rounded.Palette,
                        title = "P1 正面全身",
                        meta = listOf("沈知澜", "宗门法衣"),
                        badge = "图1",
                        isAnchor = false,
                    )
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(start = 70.dp, end = InfinitySpacing.RowHorizontal))
                    ProtoReadOnlyInputRow(
                        icon = Icons.Rounded.Image,
                        title = "雨夜石坪空镜",
                        meta = listOf("临溪石坪", "场景主视图"),
                        badge = "图2",
                        isAnchor = false,
                    )
                }
            }
            item("chain-readonly") {
                ProductionSection(
                    title = "链式生成记录",
                    titleSegments = listOf("链式生成记录", "2 段", "248 帧"),
                    trailing = "由智能体编排",
                ) {
                    ProtoReadOnlyChainSegmentRow(
                        segmentLabel = "段 1 · #A",
                        timeMeta = listOf("0.0–5.0 秒", "124 帧"),
                        status = ProductionChipSpec("已完成", ProductionTone.SUCCESS),
                        guideTag = "锁帧 0.0s（第 0 帧）",
                        promptSummary = "雨夜石坪起手跃起，长剑横扫破开雨幕，镜头低机位跟拍。",
                        continuedFrom = null,
                    )
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal))
                    ProtoReadOnlyChainSegmentRow(
                        segmentLabel = "段 2 · #A",
                        timeMeta = listOf("5.0–10.0 秒", "124 帧"),
                        status = ProductionChipSpec("当前末端", ProductionTone.INFO),
                        guideTag = null,
                        promptSummary = "接续段 1 动作低身三百六十度旋斩落地，水花四溅定格收剑。",
                        continuedFrom = "承接段 1 · #A 运动状态",
                    )
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.35f), modifier = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Rounded.Info, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                        Text(
                            "续写、重跑与锁帧编排由智能体在对话中管理；点按任意分段缩略图可回放成片。",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoReadOnlyInputRow(
    icon: ImageVector,
    title: String,
    meta: List<String>,
    badge: String,
    isAnchor: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProtoMediaThumbPlaceholder(icon, highlight = isAnchor)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isAnchor) FontWeight.Medium else FontWeight.Normal, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (isAnchor) {
                    Surface(shape = RoundedCornerShape(6.dp), color = domain.infoContainer) {
                        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Icon(Icons.Rounded.PushPin, null, tint = domain.onInfoContainer, modifier = Modifier.size(11.dp))
                            Text(badge, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = domain.onInfoContainer)
                        }
                    }
                } else {
                    Text(badge, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
            InfinityMetaLine(segments = meta, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProtoReadOnlyChainSegmentRow(
    segmentLabel: String,
    timeMeta: List<String>,
    status: ProductionChipSpec,
    guideTag: String?,
    promptSummary: String,
    continuedFrom: String?,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = {}).padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (continuedFrom != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(Icons.Rounded.SubdirectoryArrowRight, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(15.dp))
                Text(continuedFrom, style = MaterialTheme.typography.labelSmall, color = scheme.primary)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(76.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(scheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Surface(shape = CircleShape, color = scheme.scrim.copy(alpha = 0.5f)) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "回放", tint = Color.White, modifier = Modifier.padding(4.dp).size(16.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(segmentLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface, modifier = Modifier.weight(1f))
                    ProductionStatusChip(status.text, status.tone)
                }
                InfinityMetaLine(segments = timeMeta, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                if (guideTag != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Rounded.PushPin, contentDescription = null, tint = domain.onInfoContainer, modifier = Modifier.size(12.dp))
                        Text(guideTag, style = MaterialTheme.typography.labelSmall, color = domain.onInfoContainer)
                    }
                }
                Text(
                    text = promptSummary,
                    style = MaterialTheme.typography.bodySmall.forReading(),
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ProtoMediaThumbPlaceholder(icon: ImageVector, highlight: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlight) domain.infoContainer.copy(alpha = 0.45f) else scheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (highlight) domain.onInfoContainer else scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

// =====================================================================
// 5. Todo 列表页面（v1.3：取消顶部进度条，重点优化字数悬殊时的排版美感与扫读节奏）
// =====================================================================

private enum class ProtoTodoStatus { DONE, ACTIVE, TODO }

private data class ProtoTodoItem(
    val text: String,
    val status: ProtoTodoStatus,
    val expandable: Boolean = false,
)

@Composable
private fun ProtoTaskTodoSpacingPreviewBody(mode: InfinityThemeMode) {
    ProtoUpgradeFrame(mode = mode, inspectorChrome = mode == InfinityThemeMode.DARK) {
        val scheme = MaterialTheme.colorScheme
        val domain = MaterialTheme.infinityDomainColors
        val activeSteps = listOf(
            ProtoTodoItem("拉取最新分支", ProtoTodoStatus.DONE),
            ProtoTodoItem(
                "修正旧模板迁移中 WIDTH / HEIGHT 字段默认可变性，统一改为 CONTENT 生成参数并补齐相关单元测试",
                ProtoTodoStatus.DONE,
            ),
            ProtoTodoItem(
                "落盘单镜规格、方案 A 动态后处理页签及全站 6 处页签顶距统一，核对暗亮双色截图基线并在真机验证",
                ProtoTodoStatus.ACTIVE,
            ),
            ProtoTodoItem("补单测", ProtoTodoStatus.TODO),
            ProtoTodoItem(
                "执行静态检查、16KB 页对齐与 Sideload 多进程并行截图校验，确认全部通过后同步 CHANGELOG、README 与 HLG 交接记录并推送 main 分支，最后整理可复核的交付清单与真机验证入口",
                ProtoTodoStatus.TODO,
                expandable = true,
            ),
            ProtoTodoItem("推送提交", ProtoTodoStatus.TODO),
        )
        Column(Modifier.fillMaxSize()) {
            InfinityPanelTabs(
                tabs = listOf(
                    InfinityPanelTab("plan", "计划", count = "5/9"),
                    InfinityPanelTab("bg", "后台", count = "1"),
                    InfinityPanelTab("state", "状态"),
                ),
                selected = "plan",
                onSelect = {},
                contentInset = InfinitySpacing.ScreenHorizontal,
            )
            // 取消进度条：列表直接以 14dp 顶距起排，与页签保持轻盈呼吸感
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = InfinitySpacing.ScreenHorizontal, top = 14.dp, end = InfinitySpacing.ScreenHorizontal, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item("done-group") {
                    // 已折叠的完成阶段：单行轻卡片，降低视觉噪音
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = scheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = domain.success, modifier = Modifier.size(16.dp))
                            Text(
                                "阶段一：代码核查与原型定稿",
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text("3/3", style = MaterialTheme.typography.labelMedium, color = domain.success)
                        }
                    }
                }
                item("active-group") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "阶段二：管线升级与验证",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text("2/6", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                        }
                        // 核心排版策略：统一卡片容器 + 首行光学对齐 + 全行等宽正文列（不右侧挤压）+ 内缩细分隔线 + 超 3 行省略可展开
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = scheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                activeSteps.forEachIndexed { index, step ->
                                    if (index > 0) {
                                        HorizontalDivider(
                                            color = scheme.outlineVariant.copy(alpha = 0.35f),
                                            modifier = Modifier.padding(start = 42.dp, end = 14.dp),
                                        )
                                    }
                                    ProtoTodoStepRow(step)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoTodoStepRow(step: ProtoTodoItem) {
    val scheme = MaterialTheme.colorScheme
    val isActive = step.status == ProtoTodoStatus.ACTIVE
    val isDone = step.status == ProtoTodoStatus.DONE
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .then(
                if (isActive) Modifier.background(scheme.primaryContainer.copy(alpha = 0.36f))
                else Modifier,
            ),
    ) {
        // 进行中项左侧 3dp 强调竖条，完整贴合该行高度，不占右侧文字宽度，保持全部行文本列完全等宽
        if (isActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .padding(vertical = 10.dp)
                    .width(3.dp)
                    .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                    .background(scheme.primary),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.Top, // 关键：首行对齐，长短项左侧状态轨始终笔直
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 2.dp) // 光学对齐 bodyMedium.forReading() 首行中线
                    .size(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (step.status) {
                    ProtoTodoStatus.DONE -> Icon(Icons.Rounded.CheckCircle, null, tint = scheme.primary.copy(alpha = 0.85f), modifier = Modifier.size(16.dp))
                    ProtoTodoStatus.ACTIVE -> Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(scheme.primary),
                    )
                    ProtoTodoStatus.TODO -> Icon(Icons.Rounded.RadioButtonUnchecked, null, tint = scheme.outline, modifier = Modifier.size(16.dp))
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = step.text,
                    style = MaterialTheme.typography.bodyMedium.forReading(),
                    fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                    color = when (step.status) {
                        ProtoTodoStatus.DONE -> scheme.onSurfaceVariant.copy(alpha = 0.78f)
                        ProtoTodoStatus.ACTIVE -> scheme.onSurface
                        ProtoTodoStatus.TODO -> scheme.onSurface.copy(alpha = 0.9f)
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isActive) {
                    Text(
                        text = "进行中",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = scheme.primary,
                    )
                } else if (step.expandable) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("展开全文", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

// =====================================================================
// 6. 远程页签 16dp 顶距（RemotePage：原 top = 4dp → 16dp）
// =====================================================================

@Composable
private fun ProtoRemoteSpacingBody() {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        InfinityPanelTabs(
            tabs = listOf(
                InfinityPanelTab("ssh", "SSH", dot = scheme.primary),
                InfinityPanelTab("git", "Git"),
                InfinityPanelTab("ts", "Tailscale"),
                InfinityPanelTab("net", "网络"),
            ),
            selected = "ssh",
            onSelect = {},
            contentInset = InfinitySpacing.ScreenHorizontal,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = InfinitySpacing.ScreenHorizontal, top = 16.dp, end = InfinitySpacing.ScreenHorizontal, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item("hint") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(scheme.surfaceContainer, RoundedCornerShape(12.dp))
                        .padding(InfinitySpacing.Medium),
                    horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp).padding(top = 1.dp))
                    Text("顶部原 4dp 紧贴页签底边，统一改为 16dp，与下方服务卡片间距（14dp）自然衔接。", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            item("hosts") {
                ProductionSection("远程主机") {
                    ProductionRow("MAIN 4090", icon = Icons.Rounded.Dns, subtitle = "Tailscale 直连 · 在线", chip = ProductionChipSpec("已连接", ProductionTone.SUCCESS))
                    ProductionRow("FNOS 构筑机", icon = Icons.Rounded.Terminal, subtitle = "Linux x86_64 · 空闲", chip = ProductionChipSpec("待机", ProductionTone.NEUTRAL))
                }
            }
        }
    }
}

// =====================================================================
// 7. Git 变更页签 14dp 顶距（GitChangesPanel：移除固定 Spacer(8dp)，改列表 contentPadding top = 14dp）
// =====================================================================

@Composable
private fun ProtoGitSpacingBody() {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Column(Modifier.fillMaxSize()) {
        InfinityPanelTabs(
            tabs = listOf(
                InfinityPanelTab("changes", "变更", count = "3"),
                InfinityPanelTab("commits", "提交"),
                InfinityPanelTab("branches", "分支", count = "2"),
            ),
            selected = "changes",
            onSelect = {},
            contentInset = InfinitySpacing.ScreenHorizontal,
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = InfinitySpacing.ScreenHorizontal, top = 14.dp, end = InfinitySpacing.ScreenHorizontal, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item("hint") {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = scheme.surfaceContainerLow,
                    border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "分支 main · 只读变更视图（由列表 contentPadding top=14dp 提供呼吸间距，滚动时不产生顶部硬裁切）",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
            item("group") {
                ProductionSection("未暂存的修改 · 3") {
                    ProtoGitFileRow("ProductionPanel.kt", "+86 -12", "M", domain.info)
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f), modifier = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal))
                    ProtoGitFileRow("ProductionShotPlanning.kt", "+142 -28", "M", domain.info)
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f), modifier = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal))
                    ProtoGitFileRow("ProductionPostProcessTab.kt", "+210", "A", domain.success)
                }
            }
        }
    }
}

@Composable
private fun ProtoGitFileRow(name: String, stat: String, badge: String, badgeColor: Color) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(badge, style = MaterialTheme.typography.labelMedium.copy(fontFamily = InfinityMonospace), fontWeight = FontWeight.Bold, color = badgeColor)
        Text(name, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(stat, style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace), color = scheme.onSurfaceVariant)
    }
}

// =====================================================================
// 8. 产物页签 14dp 顶距（ArtifactsContent：原 top = 8dp → 14dp）
// =====================================================================

@Composable
private fun ProtoArtifactsSpacingBody() {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        InfinityPanelTabs(
            tabs = listOf(
                InfinityPanelTab("all", "全部", count = "5"),
                InfinityPanelTab("proto", "原型", count = "2"),
                InfinityPanelTab("doc", "文档", count = "3"),
            ),
            selected = "all",
            onSelect = {},
            contentInset = InfinitySpacing.ScreenHorizontal,
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item("proto-header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Large),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("交互原型", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                    Text("2 项", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }
            }
            item("proto-cards") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Large),
                    horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                ) {
                    ProtoArtifactGridCard("片场分镜总览", "HTML · 刚刚", Modifier.weight(1f))
                    ProtoArtifactGridCard("参数审批流", "HTML · 12 分钟前", Modifier.weight(1f))
                }
            }
            item("doc-list") {
                Column(Modifier.padding(horizontal = InfinitySpacing.Large), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("文档 · 3", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, modifier = Modifier.padding(top = 4.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = scheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, scheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            ProductionRow("第一集分镜脚本.md", icon = Icons.AutoMirrored.Rounded.InsertDriveFile, subtitle = "Markdown · 18 KB")
                            ProductionRow("角色资产对照表.md", icon = Icons.AutoMirrored.Rounded.InsertDriveFile, subtitle = "Markdown · 9 KB")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoArtifactGridCard(title: String, meta: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = modifier,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(8.dp)).background(scheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Web, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(22.dp))
            }
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
}

// =====================================================================
// 9. 知识检索详情弹层 14dp 顶距（RagDetailsSheet：移除固定 Spacer(8dp)，改列表 top = 14dp）
// =====================================================================

@Composable
private fun ProtoRagDetailsSpacingBody() {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("检索详情", style = MaterialTheme.typography.titleLarge, color = scheme.onSurface, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        InfinityPanelTabs(
            tabs = listOf(
                InfinityPanelTab(0, "知识库引用"),
                InfinityPanelTab(1, "网页来源"),
                InfinityPanelTab(2, "知识图谱"),
            ),
            selected = 0,
            onSelect = {},
            bleed = 16.dp,
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item("trace") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = scheme.surfaceContainer,
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("混合检索摘要", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
                        InfinityMetaLine(
                            segments = listOf("召回 12 条", "重排保留 4 条", "耗时 148 ms"),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item("ref1") {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("[1] ComfyUI 提交器契约规范 · comfyui-submitters.md", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = scheme.onSurface)
                    Text("TIMED_FILES 语义槽支持按 {frame_idx}:{file} 自动展开参数，无须在客户端硬编码具体工作流。", style = MaterialTheme.typography.bodySmall.forReading(), color = scheme.onSurfaceVariant)
                }
            }
            item("div") { HorizontalDivider(color = scheme.outlineVariant) }
            item("ref2") {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("[2] 片场制作流程说明 · production.md", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = scheme.onSurface)
                    Text("后处理阶段复用 DERIVE 派生候选链路，审片通过后自动供剪辑模块消费。", style = MaterialTheme.typography.bodySmall.forReading(), color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

// =====================================================================
// Preview 入口（9 组：覆盖本轮全部前端界面与间距调整）
// =====================================================================

@Preview(name = "1. 总览 · 后处理阶段与16dp顶距 (Dark)", widthDp = 412, heightDp = 960, locale = "zh-rCN", showBackground = true)
@Composable
fun OverviewPostProcessAndSpacingDarkPreview() {
    ProtoUpgradeFrame(mode = InfinityThemeMode.DARK, inspectorChrome = true) {
        ProtoOverviewPipelineContent()
    }
}

@Preview(name = "2. 后处理页签 · 去蓝卡精简版 (Dark)", widthDp = 412, heightDp = 860, locale = "zh-rCN", showBackground = true)
@Composable
fun PostProcessTabDarkPreview() {
    ProtoUpgradeFrame(mode = InfinityThemeMode.DARK, inspectorChrome = true) {
        ProtoPostProcessTabContent()
    }
}

@Preview(name = "3. 单镜规格 · 正常生成参数配置 (Light)", widthDp = 412, heightDp = 760, locale = "zh-rCN", showBackground = true)
@Composable
fun ShotSpecsOverrideLightPreview() {
    ProtoUpgradeFrame(mode = InfinityThemeMode.LIGHT) {
        ProtoShotSpecsOverrideContent()
    }
}

@Preview(name = "4. 镜头检视 · Guide锁帧与链式只读视图 (Dark)", widthDp = 412, heightDp = 860, locale = "zh-rCN", showBackground = true)
@Composable
fun GuideAnchorFlowDarkPreview() {
    ProtoUpgradeFrame(mode = InfinityThemeMode.DARK, inspectorChrome = true) {
        ProtoGuideAnchorFlowContent()
    }
}

@Preview(name = "5a. Todo列表 · 去进度条与长短项混排 (Dark 360dp)", widthDp = 360, heightDp = 640, locale = "zh-rCN", showBackground = true)
@Composable
fun TaskTodoSpacingDarkPreview() {
    ProtoTaskTodoSpacingPreviewBody(mode = InfinityThemeMode.DARK)
}

@Preview(name = "5b. Todo列表 · 去进度条与长短项混排 (Light 412dp)", widthDp = 412, heightDp = 640, locale = "zh-rCN", showBackground = true)
@Composable
fun TaskTodoMixedLengthLightPreview() {
    ProtoTaskTodoSpacingPreviewBody(mode = InfinityThemeMode.LIGHT)
}

@Preview(name = "6. 远程页签16dp顶距 (Dark)", widthDp = 412, heightDp = 460, locale = "zh-rCN", showBackground = true)
@Composable
fun RemotePageSpacingDarkPreview() {
    ProtoUpgradeFrame(mode = InfinityThemeMode.DARK, inspectorChrome = true) {
        ProtoRemoteSpacingBody()
    }
}

@Preview(name = "7. Git变更页签14dp顶距 (Dark)", widthDp = 412, heightDp = 460, locale = "zh-rCN", showBackground = true)
@Composable
fun GitChangesSpacingDarkPreview() {
    ProtoUpgradeFrame(mode = InfinityThemeMode.DARK, inspectorChrome = true) {
        ProtoGitSpacingBody()
    }
}

@Preview(name = "8. 产物页签14dp顶距 (Light)", widthDp = 412, heightDp = 520, locale = "zh-rCN", showBackground = true)
@Composable
fun ArtifactsSpacingLightPreview() {
    ProtoUpgradeFrame(mode = InfinityThemeMode.LIGHT) {
        ProtoArtifactsSpacingBody()
    }
}

@Preview(name = "9. 知识检索详情弹层14dp顶距 (Dark)", widthDp = 412, heightDp = 500, locale = "zh-rCN", showBackground = true)
@Composable
fun RagDetailsSpacingDarkPreview() {
    ProtoUpgradeFrame(mode = InfinityThemeMode.DARK, inspectorChrome = true) {
        ProtoRagDetailsSpacingBody()
    }
}
