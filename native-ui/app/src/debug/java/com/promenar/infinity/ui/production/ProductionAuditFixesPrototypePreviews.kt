package com.promenar.infinity.ui.production

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.ListAlt
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.data.production.ReviewKind
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPanelTab
import com.promenar.infinity.ui.common.InfinityPanelTabs
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.infinityDomainColors

/*
 * 隔离无状态高保真视觉原型（v0.80.2-beta QA & PM 体验审计修复方案）：
 * 仅用于 Compose Preview 渲染供用户确认，不改动任何线上组件。
 * 覆盖：
 * 1. 审批弹窗内联化重构（去全屏大弹窗，延续方向 C 原地展开内联美学）；
 * 2. 总览页、窄分屏 7 页签渐隐边与底栏角标语义分离；
 * 3. 后处理对比审片默认选中衍生候选 + A/B 切换 + 窄屏独立滑块播放器 + 多集切换器；
 * 4. 资产卡、分镜计数、紧凑限宽弹窗、白底灯箱胶囊分页点与扫描空态轻提示。
 */

@Composable
private fun PrototypeFrame(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    InfinityTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                content()
            }
        }
    }
}

/** 可复用的方向 C 风格内联审批槽原型：原地平滑展开，零全屏暗幕。 */
@Composable
private fun PrototypeInlineReviewPanel(
    selected: ReviewKind,
    noteText: String,
    showRetract: Boolean,
    retractLabel: String = "撤回批准",
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.65f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InfinitySegmentedControl(
                segments = listOf(
                    InfinitySegment(
                        value = ReviewKind.APPROVE,
                        label = "批准",
                        leading = { Box(Modifier.size(7.dp).clip(CircleShape).background(domain.success)) },
                    ),
                    InfinitySegment(
                        value = ReviewKind.REJECT,
                        label = "否决",
                        leading = { Box(Modifier.size(7.dp).clip(CircleShape).background(scheme.error)) },
                    ),
                    InfinitySegment(
                        value = ReviewKind.SUGGEST,
                        label = "建议",
                        leading = { Box(Modifier.size(7.dp).clip(CircleShape).background(scheme.primary)) },
                    ),
                ),
                selected = selected,
                onSelect = {},
                fillWidth = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = noteText,
                onValueChange = {},
                placeholder = {
                    Text(
                        when (selected) {
                            ReviewKind.APPROVE -> "审批附注（选填）"
                            ReviewKind.REJECT -> "否决原因（建议填写具体修改方向）"
                            ReviewKind.SUGGEST -> "填写给智能体的调整建议（必填）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                minLines = 2,
                maxLines = 3,
                textStyle = MaterialTheme.typography.bodySmall.forInput(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showRetract) {
                    OutlinedButton(
                        onClick = {},
                        shape = CircleShape,
                        border = BorderStroke(0.8.dp, scheme.error.copy(alpha = 0.45f)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.heightIn(min = 34.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.Undo,
                            contentDescription = null,
                            tint = scheme.error,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(retractLabel, style = MaterialTheme.typography.labelMedium, color = scheme.error)
                    }
                }
                Spacer(Modifier.weight(1f))
                InfinityPillButton(
                    text = "收起",
                    onClick = {},
                    tone = InfinityPillTone.NEUTRAL,
                    compact = true,
                )
                InfinityPillButton(
                    text = "保存",
                    onClick = {},
                    tone = InfinityPillTone.PRIMARY,
                    compact = true,
                )
            }
        }
    }
}

@Preview(name = "1-审批内联化重构", widthDp = 412, heightDp = 960, showBackground = true)
@Composable
fun Preview1_InlineReviewRedesign() {
    PrototypeFrame(
        title = "方向 A（推荐）：审批彻底内联化，告别全屏大弹窗",
        subtitle = "默认一行快捷直批；需要写附注/否决/建议时原地平滑展开，边看内容边批注",
    ) {
        val scheme = MaterialTheme.colorScheme

        // 状态 1：剧本卡片默认收起态（1 键快速批准 + 展开详细审批）
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = scheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("第 1 集 · 青石雨巷", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("S1E01/script.md · 2 场", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                    ProductionStatusChip("待审", ProductionTone.WARNING)
                }
                Surface(shape = RoundedCornerShape(10.dp), color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "夜雨浸润青石板，林提灯手执青铜风灯自巷口缓步而来，暖黄灯火在积水中拖出细长光带……",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "状态 1 · 收起态",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    InfinityPillButton(
                        text = "批准",
                        onClick = {},
                        tone = InfinityPillTone.PRIMARY,
                        icon = Icons.Rounded.Check,
                        compact = true,
                    )
                    InfinityPillButton(
                        text = "审批",
                        onClick = {},
                        tone = InfinityPillTone.NEUTRAL,
                        icon = Icons.Rounded.ExpandMore,
                        compact = true,
                    )
                }
            }
        }

        // 状态 2：剧本卡片原地展开内联审批态（选「否决」并写意见，正文依然完整可见）
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = scheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("第 2 集 · 旧阁听雨", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("状态 2 · 点「审批」原地展开，零暗幕遮挡", style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                    }
                    ProductionStatusChip("已否决 · 未发送", ProductionTone.ERROR)
                }
                PrototypeInlineReviewPanel(
                    selected = ReviewKind.REJECT,
                    noteText = "第二段独白节奏偏快，建议补一处檐下滴水停顿，方便后续配音留白。",
                    showRetract = true,
                    retractLabel = "撤回否决",
                )
            }
        }

        // 状态 3：分镜场头紧凑入口展开态（场头正下方丝滑展开，不遮挡下方镜头卡）
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("第 1 场 · 巷口独行", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("2 镜 · 11 秒 · 状态 3 场头内联展开", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
                ProductionStatusChip("待审", ProductionTone.WARNING)
                InfinityPillButton(
                    text = "收起",
                    onClick = {},
                    tone = InfinityPillTone.NEUTRAL,
                    icon = Icons.Rounded.ExpandLess,
                    compact = true,
                )
            }
            PrototypeInlineReviewPanel(
                selected = ReviewKind.SUGGEST,
                noteText = "SC01-01 机位再压低一点，突出积水倒影。",
                showRetract = false,
            )
            Surface(shape = RoundedCornerShape(14.dp), color = scheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SC01-01", style = MaterialTheme.typography.labelMedium.copy(fontFamily = InfinityMonospace), fontWeight = FontWeight.SemiBold)
                    Text("巷中提灯独行", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("5 秒", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Preview(name = "2-总览与窄屏导航角标修复", widthDp = 412, heightDp = 960, showBackground = true)
@Composable
fun Preview2_OverviewAndChromeFixes() {
    PrototypeFrame(
        title = "总览状态、窄屏 7 页签渐隐与底栏角标语义修复",
        subtitle = "修复 BUG-4 / BUG-5 / BUG-10 / BUG-14 及 PM-1 / PM-2 / PM-3 / PM-4",
    ) {
        val scheme = MaterialTheme.colorScheme
        val domain = MaterialTheme.infinityDomainColors

        // 1. 窄屏 7 页签：总览带待审提示点 + 右侧柔和渐隐边暗示可横滑至「剪辑」（无图标压字）
        Box(Modifier.fillMaxWidth()) {
            InfinityPanelTabs(
                tabs = listOf(
                    InfinityPanelTab("ov", "总览", dot = scheme.tertiary),
                    InfinityPanelTab("sc", "剧本"),
                    InfinityPanelTab("as", "资产"),
                    InfinityPanelTab("sb", "分镜"),
                    InfinityPanelTab("ou", "出片"),
                    InfinityPanelTab("pp", "后处理", count = "1"),
                    InfinityPanelTab("ct", "剪辑"),
                ),
                selected = "ov",
                onSelect = {},
                contentInset = 4.dp,
            )
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(28.dp)
                    .height(44.dp)
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, scheme.background.copy(alpha = 0.88f)))),
            )
        }

        // 2. 全剧进度固定首位（不因插入变更请求被顶出视口）
        Surface(shape = RoundedCornerShape(16.dp), color = scheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("全剧进度", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("4/6 镜已定稿", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                }
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(scheme.surfaceContainerHighest)) {
                    Box(Modifier.fillMaxWidth(0.67f).height(6.dp).clip(CircleShape).background(scheme.primary))
                }
                Text("共 2 集 · 紧随其下展示内容变更请求（不再隐匿于视口上方）", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }

        // 3. 内容变更请求：标题准确区分「待审 1 条 · 已处理未发送 1 条」（修复 BUG-14）
        ProductionSection(
            title = "内容变更请求",
            titleSegments = listOf("内容变更请求", "待审 1 条", "已处理 1 条"),
            titleAction = {
                InfinityPillButton(
                    text = "全部批准",
                    onClick = {},
                    tone = InfinityPillTone.PRIMARY,
                    icon = Icons.Rounded.DoneAll,
                    compact = true,
                    modifier = Modifier.padding(end = 8.dp),
                )
            },
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SC01-01 · 巷中提灯独行", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("画面补充青石雨夜冷暖反光细节", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
                ProductionStatusChip("已批准 · 未发送", ProductionTone.SUCCESS)
            }
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f), modifier = Modifier.padding(horizontal = 14.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SC01-02 · 灯前低语", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("时长 5 秒 → 6 秒", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
                ProductionStatusChip("待审", ProductionTone.WARNING)
            }
        }

        // 4. 流程状态修复：0 镜空场保持中性「未开始」（修复 BUG-4）+ 统一可选环节开关说明（修复 BUG-10）
        ProductionSection("制作流程") {
            ProductionRow(
                title = "分镜",
                icon = Icons.Rounded.ListAlt,
                subtitle = "3 场 · 尚未拆镜（0 镜不误报待审）",
                chip = ProductionChipSpec("未开始", ProductionTone.NEUTRAL),
            )
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f), modifier = Modifier.padding(horizontal = 14.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Slideshow, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("分镜预演", style = MaterialTheme.typography.bodyMedium)
                    Text("可选环节 · 开启后可在剪辑页生成预演", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
                Text("跳过", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(end = 6.dp))
                Switch(checked = true, onCheckedChange = {})
            }
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f), modifier = Modifier.padding(horizontal = 14.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoFixHigh, null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("后处理", style = MaterialTheme.typography.bodyMedium)
                    Text("可选环节 · 1 镜超分已定稿（已显示页签）", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
                Text("跳过", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(end = 6.dp))
                Switch(checked = false, onCheckedChange = {})
            }
        }

        // 5. 底部检视器栏（PM-1 统一「制作」/ PM-3 产物零重复角标 / PM-4 区分待审橙色与待发送主色 + 窄屏不裁切）
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = scheme.surfaceContainerLow,
            border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Icon(Icons.Rounded.Checklist, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Icon(Icons.Outlined.Folder, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Icon(Icons.Rounded.AccountTree, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Icon(Icons.Rounded.Public, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Icon(Icons.Rounded.Dashboard, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = scheme.surfaceContainerHigh,
                    border = BorderStroke(0.6.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box {
                            Icon(Icons.Rounded.Movie, null, tint = scheme.onSurface, modifier = Modifier.size(18.dp))
                        }
                        Text("制作", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Badge(containerColor = scheme.tertiary, contentColor = scheme.onTertiary) { Text("1") }
                        Badge(containerColor = domain.infoContainer, contentColor = domain.onInfoContainer) { Text("发 4") }
                    }
                }
                Icon(Icons.Rounded.Close, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Preview(name = "3-后处理对比审片与窄屏播放器", widthDp = 412, heightDp = 920, showBackground = true)
@Composable
fun Preview3_PostProcessAndPlayerFixes() {
    PrototypeFrame(
        title = "后处理 A/B 对比审片、独立滑块播放器与多集切换",
        subtitle = "修复 PM-5（多集选择器）/ PM-6（默认落衍生候选+A/B 切换）/ PM-11（窄屏滑块独立成行）",
    ) {
        val scheme = MaterialTheme.colorScheme

        // 1. 统一多集切换器（剧本 / 出片 页签复用分镜同款）
        Surface(shape = RoundedCornerShape(12.dp), color = scheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("第 1 集 · 青石雨巷", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Rounded.ExpandMore, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.weight(1f))
                Text("共 2 集 · 4 镜", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }

        // 2. 后处理对比审片：默认选中候选 2（2688×1536），上方直接提供一键原片/后处理 A/B 切换
        Surface(shape = RoundedCornerShape(16.dp), color = scheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SC01-01 · 巷中提灯独行", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    ProductionStatusChip("待审", ProductionTone.WARNING)
                }
                // 一键 A/B 切换
                InfinitySegmentedControl(
                    segments = listOf(
                        InfinitySegment("src", "原片 · 1344×768"),
                        InfinitySegment("der", "后处理 · 2688×1536"),
                    ),
                    selected = "der",
                    onSelect = {},
                    fillWidth = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 视频画面占位 + 规格角标
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF102235), Color(0xFF1D3549), Color(0xFF3E3422)),
                            ),
                        ),
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Black.copy(alpha = 0.62f),
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    ) {
                        Text(
                            "候选 2 · FlashVSR 2× · 2688×1536 · 24fps",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.56f),
                        modifier = Modifier.align(Alignment.Center).size(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Pause, null, tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                    }
                }
                // PM-11 修复：窄屏进度条独立成行置顶，控制按钮居下，彻底告别 40dp 挤压药丸
                Column(Modifier.fillMaxWidth()) {
                    Slider(
                        value = 0.42f,
                        onValueChange = {},
                        colors = SliderDefaults.colors(
                            thumbColor = scheme.primary,
                            activeTrackColor = scheme.primary,
                            inactiveTrackColor = scheme.surfaceContainerHighest,
                        ),
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Rounded.Pause, null, tint = scheme.onSurface, modifier = Modifier.size(20.dp))
                        Text(
                            "00:02 / 00:05",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Rounded.VolumeUp, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        Icon(Icons.Rounded.Fullscreen, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f))
                // 直接衔接新内联审批条
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("来源候选：候选 1", style = MaterialTheme.typography.labelSmall, color = scheme.primary, modifier = Modifier.weight(1f))
                    InfinityPillButton("批准定稿", onClick = {}, tone = InfinityPillTone.PRIMARY, icon = Icons.Rounded.Check, compact = true)
                    InfinityPillButton("附注", onClick = {}, tone = InfinityPillTone.NEUTRAL, icon = Icons.Rounded.RateReview, compact = true)
                }
            }
        }
    }
}

@Preview(name = "4-资产分镜灯箱与限宽弹窗精修", widthDp = 460, heightDp = 980, showBackground = true)
@Composable
fun Preview4_AssetsStoryboardAndDialogsFixes() {
    PrototypeFrame(
        title = "资产文案、分镜真实计数、白底灯箱胶囊与限宽弹窗",
        subtitle = "修复 BUG-7 / BUG-12 / BUG-13 及 PM-7 / PM-10 / PM-12 / PM-13 / PM-15",
    ) {
        val scheme = MaterialTheme.colorScheme
        val domain = MaterialTheme.infinityDomainColors

        // 1. 分镜行底栏：真实 5 参（不再截断 4 参）+ 中文标签（无 prop/location 英文泄漏）
        Surface(shape = RoundedCornerShape(14.dp), color = scheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("SC01-01 · 巷中提灯独行（BUG-7 + BUG-12）", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                InfinityMetaLine(
                    segments = listOf("青石雨巷（场景）", "青铜提灯（道具）", "林提灯（角色）"),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(4) {
                        Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(scheme.surfaceContainerHighest))
                    }
                    Text("5 参", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = scheme.primary)
                    Spacer(Modifier.weight(1f))
                    Text("H3 参考生视频 · 参考 · 分张", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
        }

        // 2. 声音资产卡（PM-13 时长·格式 + PM-15 角色绑定）+ 角色添加位（BUG-13）+ 场景单图标签（PM-10）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), color = scheme.surfaceContainerLow, modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFD69E2E)),
                    ) {
                        Icon(
                            Icons.Rounded.GraphicEq,
                            null,
                            tint = Color(0xFF231903),
                            modifier = Modifier.align(Alignment.Center).padding(top = 12.dp).size(26.dp),
                        )
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.65f),
                            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                        ) {
                            Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Icon(Icons.Rounded.Person, null, tint = Color.White, modifier = Modifier.size(11.dp))
                                Text("绑定：林提灯", style = MaterialTheme.typography.labelSmall, color = Color.White)
                            }
                        }
                    }
                    Text("林提灯·雨夜低语配音", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("13.6s · WAV", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
            Surface(shape = RoundedCornerShape(14.dp), color = scheme.surfaceContainerLow, modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF243B4A)),
                    ) {
                        Icon(
                            Icons.Rounded.Landscape,
                            null,
                            tint = Color(0xFF9AC5DC),
                            modifier = Modifier.align(Alignment.Center).padding(top = 12.dp).size(26.dp),
                        )
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.65f),
                            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                        ) {
                            Text("概念主图", style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                    Text("青石雨巷（场景）", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("单图参考 · 非多视角整卡", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
        }

        // 3. 角色造型添加位（BUG-13 改为「可选参考位」）+ 白底全屏灯箱高对比度深色胶囊分页点（PM-12）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = scheme.surfaceContainerLow,
                border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
                modifier = Modifier.weight(0.42f).height(88.dp),
            ) {
                Column(
                    Modifier.fillMaxSize().padding(10.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Rounded.Add, null, tint = scheme.primary, modifier = Modifier.size(22.dp))
                    Text("添加位", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                    Text("可选参考位", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
            // 白底角色图上的高对比度胶囊分页指示器
            Box(
                Modifier
                    .weight(0.58f)
                    .height(88.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFF4F4F5))
                    .border(0.5.dp, scheme.outlineVariant, RoundedCornerShape(12.dp)),
            ) {
                Text(
                    "白底角色设定图灯箱预览",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF4A4A4F),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
                )
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.62f),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(width = 16.dp, height = 6.dp).clip(CircleShape).background(Color.White))
                        repeat(3) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.45f)))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text("1 / 4", style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                }
            }
        }

        // 4. 折叠屏紧凑限宽选择卡片（440dp 上限，PM-12）+ 空面板扫描无结果轻提示（PM-7）
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = scheme.surfaceContainerHigh,
            border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.65f)),
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("生成模式（限宽 440dp，不再满屏拉伸）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("参考生视频", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.Check, null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("首尾帧生视频", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = domain.infoContainer.copy(alpha = 0.35f),
            border = BorderStroke(0.5.dp, domain.info.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Info, null, tint = domain.info, modifier = Modifier.size(16.dp))
                Text("已扫描工作区，未发现可关联的新制作项目", style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
            }
        }
    }
}
