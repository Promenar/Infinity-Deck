package com.promenar.infinity.ui.production

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.WarningAmber
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinityPanelTab
import com.promenar.infinity.ui.common.InfinityPanelTabs
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinitySwitch
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.infinityDomainColors

/*
 * 制作面板打磨与缺陷修复高保真原型（v1，隔离 debug 源码）：
 * 1. 总览取消常驻长列表“待你处理”，阶段行改为自动推导进度并点击直达对应页签（仅可选分镜预演保留跳过开关）；
 *    仅在存在锁定场内容变更请求时于总览顶部条件浮现；
 * 2. 分镜一级页面采用结构化场次头卡片与字段化镜头卡片，消除裸字落底与十行纯文本堆砌；
 * 3. 角色资产详情置顶版本图、描述与页内审批操作，三视图造型参考包按需紧凑折叠；
 * 4. 分镜详情消除 channel == null 时的 0 上限假超限报错与“造型缺失”误导，展示全部已挂载输入与通道引导；
 * 5. 补齐资产分类计数、单片隐藏“第 1 集”、剪辑轨简洁标注及更多菜单开关可视化。
 */

@Composable
private fun ProdProtoFrame(
    mode: InfinityThemeMode,
    sendCount: Int = 0,
    content: @Composable () -> Unit,
) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        CompositionLocalProvider(
            LocalProductionSend provides ProductionSendState(count = sendCount, sending = false, queued = false, onSend = {}),
        ) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}

@Composable
private fun ProdProtoRootChrome(
    selectedTab: ProductionTab,
    onMenuClick: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ProductionTopBar(
            title = "陆昭晨间问候 20s 测试片",
            meta = listOf("电影", "4 个镜头", "16:9"),
            onBack = null,
        ) {
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Rounded.Hub, contentDescription = "媒体模型与项目设置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        InfinityPanelTabs(
            tabs = listOf(
                InfinityPanelTab(ProductionTab.OVERVIEW, "总览"),
                InfinityPanelTab(ProductionTab.SCRIPT, "剧本"),
                InfinityPanelTab(ProductionTab.ASSETS, "资产", count = "6"),
                InfinityPanelTab(ProductionTab.STORYBOARD, "分镜", dot = MaterialTheme.colorScheme.primary),
                InfinityPanelTab(ProductionTab.OUTPUT, "出片"),
                InfinityPanelTab(ProductionTab.CUT, "剪辑"),
            ),
            selected = selectedTab,
            onSelect = {},
            contentInset = InfinitySpacing.Large,
            modifier = Modifier.padding(bottom = InfinitySpacing.XSmall),
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            content()
        }
    }
}

// ---------- 1. 总览重构 ----------

@Composable
private fun ProdProtoOverviewScreen() {
    ProdProtoRootChrome(selectedTab = ProductionTab.OVERVIEW) {
        ProductionSection("制作流程") {
            ProductionRow(
                title = "剧本",
                icon = Icons.Rounded.Description,
                subtitle = "script.md",
                chip = ProductionChipSpec("待审", ProductionTone.WARNING),
                onClick = {},
            )
            ProductionRow(
                title = "视觉开发",
                icon = Icons.Rounded.Palette,
                subtitle = "角色 3 · 场景 2 · 道具 1",
                chip = ProductionChipSpec("0/6 · 6 待审", ProductionTone.WARNING),
                onClick = {},
            )
            ProductionRow(
                title = "分镜",
                icon = Icons.AutoMirrored.Rounded.ViewList,
                subtitle = "1 场 · 4 镜 · 19.8 秒",
                chip = ProductionChipSpec("1 场待审", ProductionTone.WARNING),
                onClick = {},
            )
            ProductionRow(
                title = "分镜预演",
                icon = Icons.Rounded.Slideshow,
                subtitle = "0/4 镜 · 可选环节",
                chevron = false,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("跳过", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        InfinitySwitch(checked = false, onCheckedChange = {})
                    }
                },
            )
            ProductionRow(
                title = "出片",
                icon = Icons.Rounded.Movie,
                subtitle = "批准分镜后解锁",
                chip = ProductionChipSpec("0/4 镜", ProductionTone.NEUTRAL),
                onClick = {},
            )
            ProductionRow(
                title = "声音",
                icon = Icons.Rounded.GraphicEq,
                subtitle = "对白 5 条 · 声音资产 0",
                chip = ProductionChipSpec("待补", ProductionTone.NEUTRAL),
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

        ProductionSection("任务与成本", trailing = "0 项进行中") {
            ProductionRow(
                title = "队列为空",
                subtitle = "视觉开发累计生成 9 次",
                chevron = false,
            )
        }

        ProductionSection("项目进度") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = {})
                    .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "陆昭晨间问候 20s 测试片",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("0/4 镜", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ProductionProgressBar(0f)
            }
        }

        ProductionSection(null) {
            ProductionRow(
                title = "媒体模型",
                icon = Icons.Rounded.Hub,
                value = "图像 3 · 视频 1 · 音频 0",
                onClick = {},
            )
        }

        // 条件浮现注释卡：日常 0 占位，仅在锁定场收到智能体内容修改申请时才显示审批卡。
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.infinityDomainColors.infoContainer.copy(alpha = 0.5f),
            border = BorderStroke(0.5.dp, MaterialTheme.infinityDomainColors.onInfoContainer.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Rounded.EditNote, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.infinityDomainColors.onInfoContainer)
                Text(
                    "无锁定场内容变更请求，总览顶部保持零占位；收到修改申请时自动置顶呈现审批卡。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.infinityDomainColors.onInfoContainer,
                )
            }
        }
    }
}

// ---------- 2. 分镜一级列表重构（深/浅双色复用） ----------

private data class ProdProtoShotItem(
    val code: String,
    val title: String,
    val shotSize: String,
    val duration: String,
    val camera: String,
    val movement: String,
    val visual: String,
    val dialogueSpeaker: String? = null,
    val dialogueText: String? = null,
    val dialogueTone: String? = null,
    val soundSummary: String,
    val transitionSummary: String,
    val refThumbCount: Int,
    val hasNativeAudio: Boolean,
    val planSummary: String,
)

private val prodProtoShots = listOf(
    ProdProtoShotItem(
        code = "SC01-01",
        title = "经过",
        shotSize = "全景转中全景",
        duration = "4.5 秒",
        camera = "35mm · 视平线略低 · 陆昭背后偏左 2.5m",
        movement = "Tracking Shot 跟拍 · 微右摇",
        visual = "晨光青石板、红灯笼与蒸笼白汽，远处太空电梯；陆昭从左侧入画沿街走近糕点铺，接近门前略放缓。",
        soundSummary = "蒸笼沸水、早市人声、风衣摩擦 · 无对白",
        transitionSummary = "不切 · S02 Motion Context 同镜续接",
        refThumbCount = 3,
        hasNativeAudio = false,
        planSummary = "参考 · 待配通道",
    ),
    ProdProtoShotItem(
        code = "SC01-02",
        title = "招呼",
        shotSize = "中景",
        duration = "5 秒",
        camera = "50mm · 陆昭左前方 45° · 距 2.2m",
        movement = "Arc Shot 向左 30° · 承接 S01 跟拍动量",
        visual = "陆昭 3/4 侧脸占左半，掌柜在右后景柜台内；掌柜抬头伸向油纸包笑问，陆昭点头应声。",
        dialogueSpeaker = "掌柜",
        dialogueText = "陆警官，早啊！",
        dialogueTone = "热情熟络 · 尾音上扬",
        soundSummary = "早市人声、鞋底轻擦、蒸笼盖轻响",
        transitionSummary = "不切 · Motion Context 续接 S03",
        refThumbCount = 3,
        hasNativeAudio = true,
        planSummary = "参考 · 原生人声 · 待配通道",
    ),
)

@Composable
private fun ProdProtoStoryboardScreen() {
    ProdProtoRootChrome(selectedTab = ProductionTab.STORYBOARD) {
        // 场头结构化卡片（替代裸字落底）
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            "第 1 场 · SC01",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    Text(
                        "4 镜 · 19.8 秒",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ProductionStatusChip("待审", ProductionTone.WARNING)
                    Spacer(Modifier.weight(1f))
                    InfinityPillButton(
                        text = "审核",
                        onClick = {},
                        tone = InfinityPillTone.NEUTRAL,
                        icon = Icons.Rounded.RateReview,
                        compact = true,
                    )
                }
                Text(
                    "日外 · 承天市城东新老街 · 糕点铺门前 · 晨",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            prodProtoShots.forEach { item ->
                ProdProtoShotCard(item)
            }
        }
    }
}

@Composable
private fun ProdProtoShotCard(shot: ProdProtoShotItem) {
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainerLow,
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 顶栏：镜号胶囊 + 标题 + 景别时长胶囊
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = scheme.surfaceContainerHighest,
                ) {
                    Text(
                        shot.code,
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = InfinityMonospace),
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
                Text(
                    shot.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ProdProtoCompactMetaTag(shot.shotSize)
                ProdProtoCompactMetaTag(shot.duration)
            }

            // 字段化参数区（与详情页视觉同源但保持列表节奏）
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                ProdProtoShotFieldLine("机位", shot.camera)
                ProdProtoShotFieldLine("运镜", shot.movement, maxLines = 2)
                ProdProtoShotFieldLine("画面", shot.visual, maxLines = 3, primaryTone = true)
            }

            if (shot.dialogueSpeaker != null && shot.dialogueText != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = scheme.surfaceContainerHigh.copy(alpha = 0.72f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("对白", style = MaterialTheme.typography.labelSmall, color = muted)
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = scheme.tertiary, fontWeight = FontWeight.Medium)) { append(shot.dialogueSpeaker) }
                                append("「${shot.dialogueText}」")
                                shot.dialogueTone?.let { withStyle(SpanStyle(color = muted)) { append(" $it") } }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(14.dp), tint = muted)
                    Text(shot.soundSummary, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(14.dp), tint = muted)
                    Text(shot.transitionSummary, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))

            // 底栏：缩略参考群组 + 规划摘要 + 右箭头
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(shot.refThumbCount) { idx ->
                        val bg = when (idx) {
                            0 -> scheme.primaryContainer
                            1 -> scheme.secondaryContainer
                            else -> scheme.tertiaryContainer
                        }
                        val fg = when (idx) {
                            0 -> scheme.onPrimaryContainer
                            1 -> scheme.onSecondaryContainer
                            else -> scheme.onTertiaryContainer
                        }
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(bg),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (idx == 2) Icons.Rounded.Person else Icons.Rounded.Landscape,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = fg,
                            )
                        }
                    }
                    if (shot.hasNativeAudio) {
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(scheme.tertiaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(13.dp), tint = scheme.onTertiaryContainer)
                        }
                    }
                    Spacer(Modifier.width(2.dp))
                    Text("${shot.refThumbCount} 参", style = MaterialTheme.typography.labelSmall, color = muted)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    shot.planSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = muted)
            }
        }
    }
}

@Composable
private fun ProdProtoCompactMetaTag(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ProdProtoShotFieldLine(
    label: String,
    value: String,
    maxLines: Int = 2,
    primaryTone: Boolean = false,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(34.dp).padding(top = 1.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (primaryTone) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------- 3. 角色资产详情页重构 ----------

@Composable
private fun ProdProtoCharacterDetailScreen() {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Column(Modifier.fillMaxSize()) {
        ProductionTopBar(
            title = "陆昭 · 新神州玄鳞飞鱼服 V3",
            meta = listOf("角色", "4 镜引用"),
            onBack = {},
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 首屏主卡：版本切换 + 资产图展示 + 说明 + 页内审批按钮组
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = scheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("版本", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        Surface(
                            shape = CircleShape,
                            color = scheme.surfaceContainerHigh,
                            onClick = {},
                        ) {
                            Text("v1", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                        Surface(
                            shape = CircleShape,
                            color = scheme.primaryContainer,
                            onClick = {},
                        ) {
                            Text("v2", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = scheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        ProductionStatusChip("待审", ProductionTone.WARNING)
                    }

                    // 版本大图展示位（16:9 适配首屏，确保下方引用行完整呈现）
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(scheme.surfaceContainerHighest, scheme.surfaceContainerHigh, scheme.primaryContainer.copy(alpha = 0.45f)),
                                ),
                            ),
                    ) {
                        Column(
                            Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Surface(shape = CircleShape, color = scheme.surface.copy(alpha = 0.72f), modifier = Modifier.size(56.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(30.dp), tint = scheme.primary)
                                }
                            }
                            Text("角色资产卡 · 全身多视角参考 v2", style = MaterialTheme.typography.labelMedium, color = scheme.onSurface)
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = scheme.scrim.copy(alpha = 0.65f),
                            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                        ) {
                            Text(
                                "assets/lz01c-scene-walk-v02.png",
                                style = MaterialTheme.typography.labelSmall,
                                color = domain.overlayContent,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "v2 · Images 2.5 全身多参参考（已归档）",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                        Text(
                            "2026-11-20 Images 2.5 多参全身参考 v2，替代肩部金色格栅偏硬甲的 v01，作为当前人物核心参考。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurface,
                        )
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfinityPillButton("否决", onClick = {}, tone = InfinityPillTone.DANGER, modifier = Modifier.weight(1f))
                        InfinityPillButton("建议", onClick = {}, tone = InfinityPillTone.NEUTRAL, modifier = Modifier.weight(1f))
                        InfinityPillButton("批准", onClick = {}, tone = InfinityPillTone.PRIMARY, icon = Icons.Rounded.Check, modifier = Modifier.weight(1f))
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = domain.infoContainer.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = domain.onInfoContainer)
                            Text(
                                "已暂存审批意见，点击右上角「发送 1」提交给智能体执行。",
                                style = MaterialTheme.typography.labelMedium,
                                color = domain.onInfoContainer,
                            )
                        }
                    }
                }
            }

            // 多视角造型参考包（无造型时紧凑折叠，告别半屏大空框）
            ProductionSection("多视角造型参考包（可选）") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("尚未拆分三视图造型", style = MaterialTheme.typography.bodyLarge)
                        Text("当前直接使用上方版本图作为生成参考", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    InfinityPillButton(
                        text = "新建造型",
                        onClick = {},
                        tone = InfinityPillTone.NEUTRAL,
                        icon = Icons.Rounded.Add,
                        compact = true,
                    )
                }
            }

            ProductionSection("参考语音") {
                ProductionMutedRow("暂无参考语音")
            }

            ProductionSection("镜头引用") {
                listOf(
                    "SC01-01" to "经过",
                    "SC01-02" to "招呼",
                    "SC01-03" to "让包",
                    "SC01-04" to "回应离开",
                ).forEach { (code, title) ->
                    ProductionRow(
                        title = title,
                        titleSegments = listOf(code, title),
                        value = "版本图",
                        onClick = {},
                    )
                }
            }
        }
    }
}

// ---------- 4. 分镜详情：角色参考 + 无通道引导 + 完整输入展示 ----------

@Composable
private fun ProdProtoShotInputsScreen() {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Column(Modifier.fillMaxSize()) {
        ProductionTopBar(
            title = "SC01-01 经过",
            meta = listOf("第 1 场", "全景转中全景", "4.5 秒"),
            onBack = {},
        )
        InfinityPanelTabs(
            tabs = listOf(
                InfinityPanelTab(ShotDetailTab.STORYBOARD, "分镜", dot = scheme.primary),
                InfinityPanelTab(ShotDetailTab.CANDIDATES, "候选"),
            ),
            selected = ShotDetailTab.STORYBOARD,
            onSelect = {},
            contentInset = InfinitySpacing.Large,
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ProductionSection("角色参考") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = {})
                        .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(scheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(18.dp), tint = scheme.onPrimaryContainer)
                    }
                    Text(
                        "陆昭 · 新神州玄鳞飞鱼服 V3",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    ProductionStatusChip("使用版本图", ProductionTone.NEUTRAL)
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
                }
            }

            ProductionSection("通道与模式") {
                ProductionRow(title = "模式", value = "参考", onClick = {})
                ProductionRow(title = "通道", value = "暂无支持「参考」的通道", onClick = {})
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = domain.infoContainer.copy(alpha = 0.6f),
                    border = BorderStroke(0.5.dp, domain.onInfoContainer.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 6.dp),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(18.dp).padding(top = 1.dp), tint = domain.onInfoContainer)
                            Text(
                                "已配 1 个视频通道（H3 smoke T2V，仅支持「文生」），暂未配置支持「参考」的模板。",
                                style = MaterialTheme.typography.bodySmall,
                                color = domain.onInfoContainer,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            InfinityPillButton("媒体模型", onClick = {}, tone = InfinityPillTone.PRIMARY, compact = true)
                            InfinityPillButton("切换为文生", onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true)
                        }
                    }
                }
                ProductionRow(title = "规格", value = "16:9 · 4.5 秒", chevron = false)
                ProductionRow(title = "预演素材", value = "无", chevron = false)
            }

            ProductionSection(
                title = "输入",
                titleSegments = listOf("输入", "3 张", "待匹配通道"),
            ) {
                ProdProtoInputRow("图1", "35mm 老街母图", "场景 · sc03a-old-street-master-v01.png", Icons.Rounded.Landscape, scheme.primaryContainer, scheme.onPrimaryContainer)
                ProdProtoInputRow("图2", "50mm 门前行动区", "场景 · sc03b-pastry-shop-v01.png", Icons.Rounded.Landscape, scheme.secondaryContainer, scheme.onSecondaryContainer)
                ProdProtoInputRow("图3", "陆昭 V3 资产卡", "角色 · lz01c-scene-walk-v02.png", Icons.Rounded.Person, scheme.tertiaryContainer, scheme.onTertiaryContainer)
            }

            // 仅保留真实有效的待审提示，彻底移除误报的超限黄框
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = domain.warningContainer,
                border = BorderStroke(0.8.dp, domain.onWarningContainer.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = null, modifier = Modifier.size(18.dp), tint = domain.onWarningContainer)
                    Text(
                        "参考待审：陆昭 · 玄鳞飞鱼服 V3",
                        style = MaterialTheme.typography.bodySmall,
                        color = domain.onWarningContainer,
                        modifier = Modifier.weight(1f),
                    )
                    InfinityPillButton("去审核", onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true)
                }
            }
        }
    }
}

@Composable
private fun ProdProtoInputRow(
    indexLabel: String,
    title: String,
    detail: String,
    icon: ImageVector,
    thumbBg: Color,
    thumbFg: Color,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(indexLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp))
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(thumbBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = thumbFg)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------- 5. 资产分类计数 + 剪辑时间线 + 更多菜单开关 ----------

@Composable
private fun ProdProtoAssetsCutMoreScreen() {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = InfinitySpacing.Large, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // A. 资产页签分段计数 + 卡片优化
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ProductionSectionHeader("资产页签 · 分类计数与卡片信息密度")
            InfinitySegmentedControl(
                segments = listOf(
                    InfinitySegment("char", "角色", count = 3),
                    InfinitySegment("loc", "场景", count = 2),
                    InfinitySegment("prop", "道具", count = 1),
                    InfinitySegment("style", "风格", count = 0),
                    InfinitySegment("sound", "声音", count = 0),
                ),
                selected = "char",
                onSelect = {},
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProdProtoAssetMiniCard(
                    title = "陆昭 · 新神州玄鳞飞鱼服 V3",
                    meta = "v2 · 4 镜引用",
                    modifier = Modifier.weight(1f),
                )
                ProdProtoAssetMiniCard(
                    title = "老街糕点铺掌柜（03B 取用）",
                    meta = "v1 · 3 镜引用",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // B. 剪辑时间线简洁可读 + 缺口明确文案
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProductionSectionHeader("剪辑页 · 对白简称与缺口语义")
            ProductionSection(null) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("陆昭晨间问候 20s 测试片", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text("19.8 秒", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf("经过" to "4.5s", "招呼" to "5s", "让包" to "5.2s", "离开" to "5.2s").forEach { (name, dur) ->
                            Surface(
                                shape = RoundedCornerShape(7.dp),
                                color = scheme.surfaceContainerHighest,
                                modifier = Modifier.weight(1f).height(46.dp),
                            ) {
                                Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.Center) {
                                    Text(name, style = MaterialTheme.typography.labelSmall, color = scheme.onSurface, maxLines = 1)
                                    Text(dur, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        Spacer(Modifier.weight(1f))
                        listOf("掌柜·原生", "掌柜·原生", "陆昭·原生").forEach { label ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(20.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(scheme.tertiaryContainer)
                                    .padding(horizontal = 4.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onTertiaryContainer, maxLines = 1)
                            }
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(20.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(domain.successContainer)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text("环境 · 蒸笼 · 早市 · 飞车", style = MaterialTheme.typography.labelSmall, color = domain.onSuccessContainer, maxLines = 1)
                    }
                }
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
                ProductionRow("缺口", value = "4 镜待补预演（当前占位）", chevron = false)
            }
        }

        // C. 更多菜单：试验项目真实开关可见
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProductionSectionHeader("更多菜单 · 开关可视化")
            ProductionSection(null) {
                ProductionRow("媒体模型", icon = Icons.Rounded.Hub, onClick = {})
                ProductionRow(
                    title = "试验项目",
                    icon = Icons.Rounded.Science,
                    subtitle = "跳过场批准检查",
                    chevron = false,
                    trailing = { InfinitySwitch(checked = false, onCheckedChange = {}) },
                    onClick = {},
                )
                ProductionRow("在文件中打开", icon = Icons.Rounded.FolderOpen, subtitle = "AI-Assets/Productions/.../lu-zhao-morning-greeting-20s", onClick = {})
            }
        }
    }
}

@Composable
private fun ProdProtoAssetMiniCard(
    title: String,
    meta: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainerLow,
        onClick = {},
        modifier = modifier,
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.35f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(28.dp), tint = scheme.onSurfaceVariant)
            }
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Row(
                Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(meta, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
                ProductionStatusChip("待审", ProductionTone.WARNING)
            }
        }
    }
}

// ---------- 6 个 @Preview 入口 ----------

@Preview(name = "1. 总览重构(380dp深色)", widthDp = 380, heightDp = 1060, locale = "zh-rCN")
@Composable
fun productionProtoOverviewDarkPreview() = ProdProtoFrame(InfinityThemeMode.DARK) {
    ProdProtoOverviewScreen()
}

@Preview(name = "2. 分镜列表重构(380dp深色)", widthDp = 380, heightDp = 960, locale = "zh-rCN")
@Composable
fun productionProtoStoryboardDarkPreview() = ProdProtoFrame(InfinityThemeMode.DARK) {
    ProdProtoStoryboardScreen()
}

@Preview(name = "3. 分镜列表重构(380dp浅色)", widthDp = 380, heightDp = 960, locale = "zh-rCN")
@Composable
fun productionProtoStoryboardLightPreview() = ProdProtoFrame(InfinityThemeMode.LIGHT) {
    ProdProtoStoryboardScreen()
}

@Preview(name = "4. 角色资产详情重构(380dp深色)", widthDp = 380, heightDp = 1060, locale = "zh-rCN")
@Composable
fun productionProtoCharacterDetailDarkPreview() = ProdProtoFrame(InfinityThemeMode.DARK, sendCount = 1) {
    ProdProtoCharacterDetailScreen()
}

@Preview(name = "5. 分镜输入与无通道引导(380dp深色)", widthDp = 380, heightDp = 940, locale = "zh-rCN")
@Composable
fun productionProtoShotInputsDarkPreview() = ProdProtoFrame(InfinityThemeMode.DARK) {
    ProdProtoShotInputsScreen()
}

@Preview(name = "6. 资产计数·剪辑轨·更多开关(380dp深色)", widthDp = 380, heightDp = 920, locale = "zh-rCN")
@Composable
fun productionProtoAssetsCutMoreDarkPreview() = ProdProtoFrame(InfinityThemeMode.DARK) {
    ProdProtoAssetsCutMoreScreen()
}
