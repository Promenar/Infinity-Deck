package com.promenar.infinity.ui.production

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RateReview
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.infinityDomainColors

/*
 * 审批状态徽章全站统一高保真原型（v1，隔离于 debug 源码）：
 * 1. 一物一标 + 合并发送态：同一对象只保留 1 枚合并态 ProductionStatusChip，消除红蓝双胶囊并排与同卡重复双标/三标；
 * 2. 结构化反馈块（ProtoReviewFeedbackBox）：否决原因用浅错误色带细描边，待发建议用中性高亮底面配右侧静默“未发送”文字；
 * 3. 固定空间锚点：详情页/弹层标题或版本栏右侧唯一锚定当前选中版本/对象状态，按钮区仅留反馈与操作；
 * 4. 页内独立审批卡（ProtoReviewCard）：候选页与剪辑页统一封装审批卡片，场头与剧本行各自归位不互相挤压，资产网格移至缩略图右上角。
 */

@Composable
private fun BadgeProtoFrame(
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

/** 缩略图右上角高对比状态胶囊：复用语义色板并叠加细描边与微阴影，兼容纯白/高亮图片背景。 */
@Composable
private fun ProtoOverlayStatusChip(
    text: String,
    tone: ProductionTone,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val (container, content) = when (tone) {
        ProductionTone.NEUTRAL -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        ProductionTone.INFO -> domain.infoContainer to domain.onInfoContainer
        ProductionTone.WARNING -> domain.warningContainer to domain.onWarningContainer
        ProductionTone.SUCCESS -> domain.successContainer to domain.onSuccessContainer
        ProductionTone.ERROR -> scheme.errorContainer to scheme.onErrorContainer
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = container,
        border = BorderStroke(0.75.dp, scheme.outlineVariant.copy(alpha = 0.65f)),
        shadowElevation = 1.dp,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** 结构化审批反馈容器：否决原因用浅错误色带边框，待发送建议用中性高表面并配静默弱化“未发送”标注。 */
@Composable
private fun ProtoReviewFeedbackBox(
    rejectReason: String? = null,
    pendingSuggestion: String? = null,
    modifier: Modifier = Modifier,
) {
    if (rejectReason.isNullOrBlank() && pendingSuggestion.isNullOrBlank()) return
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!rejectReason.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = scheme.errorContainer.copy(alpha = 0.24f),
                border = BorderStroke(0.5.dp, scheme.error.copy(alpha = 0.28f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "否决原因",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.error,
                    )
                    Text(
                        rejectReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurface,
                    )
                }
            }
        }
        if (!pendingSuggestion.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = scheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "待发送建议",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "未发送",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.78f),
                        )
                    }
                    Text(
                        pendingSuggestion,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurface,
                    )
                }
            }
        }
    }
}

private enum class ProtoVerdict { NONE, APPROVE, REJECT }

@Composable
private fun ProtoReviewActionBar(
    verdict: ProtoVerdict = ProtoVerdict.NONE,
    busy: Boolean = false,
) {
    ProductionActionRow(
        actions = listOf(
            ProductionButton(
                text = "否决",
                tone = InfinityPillTone.DANGER,
                enabled = !busy,
                tag = "proto_reject",
                icon = Icons.Rounded.Close.takeIf { verdict == ProtoVerdict.REJECT },
                onClick = {},
            ),
            ProductionButton(
                text = "建议",
                tone = InfinityPillTone.NEUTRAL,
                enabled = !busy,
                tag = "proto_suggest",
                onClick = {},
            ),
            ProductionButton(
                text = "批准",
                tone = InfinityPillTone.PRIMARY,
                enabled = !busy,
                tag = "proto_approve",
                icon = Icons.Rounded.Check.takeIf { verdict == ProtoVerdict.APPROVE },
                onClick = {},
            ),
        ),
    )
}

/** 页内独立审批卡：用于镜头详情「候选」与剪辑页「粗剪版本」，右上角唯一状态徽章。 */
@Composable
private fun ProtoReviewCard(
    title: String,
    chip: ProductionChipSpec,
    subtitle: String? = null,
    rejectReason: String? = null,
    pendingSuggestion: String? = null,
    verdict: ProtoVerdict = ProtoVerdict.NONE,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                ProductionStatusChip(chip.text, chip.tone)
            }
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            ProtoReviewFeedbackBox(rejectReason = rejectReason, pendingSuggestion = pendingSuggestion)
            ProtoReviewActionBar(verdict = verdict)
        }
    }
}

/** 重构后的场头卡片：第 1 行场号+状态徽章左齐（支持大字号折行）+ 审核按钮右齐；第 2 行场景名；第 3 行纯元数据。 */
@Composable
private fun ProtoSceneHeaderCard(
    sceneNumber: Int,
    sceneCode: String,
    heading: String,
    meta: String,
    chip: ProductionChipSpec,
    showReviewButton: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.65f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = RoundedCornerShape(8.dp), color = scheme.primaryContainer) {
                        Text(
                            "第 $sceneNumber 场 · $sceneCode",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onPrimaryContainer,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    ProductionStatusChip(chip.text, chip.tone)
                }
                if (showReviewButton) {
                    InfinityPillButton(
                        text = "审核",
                        onClick = {},
                        tone = InfinityPillTone.NEUTRAL,
                        icon = Icons.Rounded.RateReview,
                        compact = true,
                    )
                }
            }
            Text(
                heading,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                meta,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProtoVersionPill(label: String, selected: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ProtoGroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** 图 1 角色详情卡重构内容：版本栏右侧唯一状态徽章，按钮区彻底移除重复徽章。 */
@Composable
private fun ProtoCharacterOverviewCard() {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("版本", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProtoVersionPill("v1", selected = false)
                    ProtoVersionPill("v2", selected = true)
                }
                ProductionStatusChip("待审", ProductionTone.WARNING)
            }
            // 模拟纯白底人物立绘容器，核验视觉平衡。
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.White,
                border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth().height(176.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.Person, contentDescription = null, tint = Color(0xFF333942), modifier = Modifier.size(44.dp))
                        Text("asset-lz-v2.png · 角色立绘", style = MaterialTheme.typography.labelMedium, color = Color(0xFF555E6B))
                    }
                }
            }
            Text(
                "版本2：按第1轮审核改为独立单图入参；完整正/侧/背见 asset-lz-threeview。版本1整卡仅保留谱系。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            Text(
                "Canon 资产编号 ASSET-LZ-01C。30岁东方青年军警，185cm，深黑短发、无胡茬，重磅哑光黑真丝风衣，实际入参改用独立单图。",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f))
            ProtoReviewActionBar(verdict = ProtoVerdict.NONE)
        }
    }
}

/** 模拟通用非角色资产详情弹层（ProductionAssetSheet）：标题行右对齐单徽章，副标题纯元信息。 */
@Composable
private fun ProtoAssetDetailSheetCard() {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = scheme.surfaceContainer,
        border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "糕点铺门前 · 晨光",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                ProductionStatusChip("已批准 · 未发送", ProductionTone.SUCCESS)
            }
            Text("场景 · 3 个镜头引用 · 当前版本 v2", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF28221D), Color(0xFF473726), Color(0xFF1F2730)))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Landscape, contentDescription = null, tint = Color(0xFFD6B991), modifier = Modifier.size(34.dp))
            }
            ProtoReviewActionBar(verdict = ProtoVerdict.APPROVE)
        }
    }
}

/** 图 2 审核弹层卡片：标题右单徽章 + 结构化反馈容器 + 操作区。 */
@Composable
private fun ProtoReviewSheetContainer(
    title: String,
    chip: ProductionChipSpec,
    subtitle: String,
    rejectReason: String? = null,
    pendingSuggestion: String? = null,
    verdict: ProtoVerdict = ProtoVerdict.NONE,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = scheme.surfaceContainer,
        border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                ProductionStatusChip(chip.text, chip.tone)
            }
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            ProtoReviewFeedbackBox(rejectReason = rejectReason, pendingSuggestion = pendingSuggestion)
            ProtoReviewActionBar(verdict = verdict)
        }
    }
}

/** 剧本两段式行卡：首行标题右单徽章，次行路径+审核按钮互不挤压。 */
@Composable
private fun ProtoScriptTwoTierSection(
    title: String,
    path: String,
    chip: ProductionChipSpec,
) {
    val scheme = MaterialTheme.colorScheme
    BettboxListGroup {
        Column(Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Description, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ProductionStatusChip(chip.text, chip.tone)
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 30.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    path,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                InfinityPillButton(
                    text = "审核",
                    onClick = {},
                    tone = InfinityPillTone.NEUTRAL,
                    icon = Icons.Rounded.RateReview,
                    compact = true,
                )
            }
        }
    }
}

/** 重构后的资产网格卡片：唯一状态徽章锚定缩略图右上角，底栏版本号与引用数完整展开。 */
@Composable
private fun ProtoAssetGridCard(
    title: String,
    meta: String,
    chip: ProductionChipSpec,
    lightBackground: Boolean,
    lookBadge: String? = null,
    icon: ImageVector = Icons.Rounded.Person,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainerLow,
        modifier = modifier,
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.35f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (lightBackground) Color(0xFFF6F7F9)
                        else Color(0xFF282D35),
                    ),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (lightBackground) Color(0xFF4C5564) else Color(0xFF9FB0C4),
                    modifier = Modifier.align(Alignment.Center).size(30.dp),
                )
                ProtoOverlayStatusChip(
                    text = chip.text,
                    tone = chip.tone,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
                if (!lookBadge.isNullOrBlank()) {
                    Row(Modifier.align(Alignment.BottomStart).padding(4.dp)) {
                        ClipBadge(lookBadge)
                    }
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
            )
        }
    }
}

// ---------- 5 个 Android Compose Preview 入口 ----------

@Preview(name = "1. 角色详情与资产弹层重构(深色)", widthDp = 380, heightDp = 1120, locale = "zh-rCN")
@Composable
fun reviewBadgeProto1CharacterAndAssetDarkPreview() = BadgeProtoFrame(InfinityThemeMode.DARK, sendCount = 1) {
    Column {
        ProductionTopBar(
            title = "陆昭 · 新神州玄鳞飞鱼服 V3",
            meta = listOf("角色", "4 个镜头引用"),
            onBack = {},
        )
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ProtoGroupLabel("图1重构 · 角色详情（版本栏单标 + 按钮区零重复）")
            ProtoCharacterOverviewCard()
            ProtoGroupLabel("非角色资产详情弹层（标题行右单标 + 元信息独立一行）")
            ProtoAssetDetailSheetCard()
        }
    }
}

@Preview(name = "2. 审核弹层合并态与原因容器(深色)", widthDp = 520, heightDp = 760, locale = "zh-rCN")
@Composable
fun reviewBadgeProto2ReviewSheetDarkPreview() = BadgeProtoFrame(InfinityThemeMode.DARK, sendCount = 1) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        ProtoGroupLabel("图2重构 · 已否决且未发送（单枚合并徽章 + 结构化否决原因容器）")
        ProtoReviewSheetContainer(
            title = "陆昭晨间问候 20s 测试片",
            chip = ProductionChipSpec("已否决 · 未发送", ProductionTone.ERROR),
            subtitle = "AI-Assets/Productions/series/huaxia-chronicles/development/lu-zhao-morning-greeting-20s/script.md",
            rejectReason = "台词设计太简短了，明显给人一种赶工的 AI 感",
            verdict = ProtoVerdict.REJECT,
        )
        ProtoGroupLabel("审核弹层 · 待审附带未发送建议（右置弱化标注不堆双胶囊）")
        ProtoReviewSheetContainer(
            title = "第 1 场",
            chip = ProductionChipSpec("待审", ProductionTone.WARNING),
            subtitle = "日外 · 承天市城东新老街 · 糕点铺门前",
            pendingSuggestion = "开场风衣摆角可以再带一点街灯冷反光，与铺前热雾形成冷暖对冲。",
            verdict = ProtoVerdict.NONE,
        )
    }
}

@Preview(name = "3. 剧本行·总览行·场头卡片重构(深色)", widthDp = 380, heightDp = 920, locale = "zh-rCN")
@Composable
fun reviewBadgeProto3ScriptAndSceneDarkPreview() = BadgeProtoFrame(InfinityThemeMode.DARK, sendCount = 1) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ProtoGroupLabel("剧本页签 · 两段式剧本卡（标题与审核按钮不再互挤）")
        ProtoScriptTwoTierSection(
            title = "陆昭晨间问候 20s 测试片",
            path = "AI-Assets/.../lu-zhao-morning-greeting-20s/script.md",
            chip = ProductionChipSpec("已否决 · 未发送", ProductionTone.ERROR),
        )
        ProtoGroupLabel("剧本场次行 & 总览流程单集剧本行（真实映射审核状态）")
        BettboxListGroup {
            Column(Modifier.padding(vertical = InfinitySpacing.XSmall)) {
                ProductionRow(
                    title = "日外 · 承天市城东新老街 · 糕点铺门前",
                    subtitle = "4 镜 · 19.8 秒",
                    leading = { Text("1", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    chip = ProductionChipSpec("待审", ProductionTone.WARNING),
                    chevron = false,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                ProductionRow(
                    title = "剧本（总览流程）",
                    icon = Icons.Rounded.Description,
                    subtitle = "script.md",
                    chip = ProductionChipSpec("已否决 · 未发送", ProductionTone.ERROR),
                    onClick = {},
                )
            }
        }
        ProtoGroupLabel("分镜与出片 · 场头卡片重构（场号紧随状态 + 右齐审核）")
        ProtoSceneHeaderCard(
            sceneNumber = 1,
            sceneCode = "SC01",
            heading = "日外 · 承天市城东新老街 · 糕点铺门前",
            meta = "4 镜 · 19.8 秒",
            chip = ProductionChipSpec("待审", ProductionTone.WARNING),
            showReviewButton = true,
        )
        ProtoSceneHeaderCard(
            sceneNumber = 2,
            sceneCode = "SC02",
            heading = "夜内 · 灵调科第七特勤组档案廊",
            meta = "3 镜 · 14.2 秒",
            chip = ProductionChipSpec("已批准", ProductionTone.SUCCESS),
            showReviewButton = false,
        )
    }
}

@Preview(name = "4. 候选·剪辑卡与资产网格(深色)", widthDp = 380, heightDp = 1140, locale = "zh-rCN")
@Composable
fun reviewBadgeProto4CandidatesCutAssetsDarkPreview() = BadgeProtoFrame(InfinityThemeMode.DARK, sendCount = 1) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ProtoGroupLabel("镜头详情「候选」页 · 独立候选审核卡片")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(2) { idx ->
                Column(Modifier.width(108.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF232830))
                            .then(if (idx == 0) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Movie, contentDescription = null, tint = Color(0xFF9EB0C5), modifier = Modifier.size(18.dp))
                    }
                    Text("候选 ${idx + 1}", style = MaterialTheme.typography.labelSmall, color = if (idx == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        ProtoReviewCard(
            title = "候选 1 · 审核",
            chip = ProductionChipSpec("已批准 · 未发送", ProductionTone.SUCCESS),
            verdict = ProtoVerdict.APPROVE,
        )

        ProtoGroupLabel("剪辑页 · 导出动作与粗剪审核卡片分层")
        ProductionActionRow(
            actions = listOf(
                ProductionButton("播放顺序", InfinityPillTone.NEUTRAL, true, "proto_play", onClick = {}),
                ProductionButton("导出", InfinityPillTone.PRIMARY, true, "proto_export", onClick = {}),
            ),
        )
        ProtoReviewCard(
            title = "粗剪 v1 · 审核",
            chip = ProductionChipSpec("待审", ProductionTone.WARNING),
            verdict = ProtoVerdict.NONE,
        )

        ProtoGroupLabel("资产网格 · 右上角高对比徽章 + 底栏完整版本与引用")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProtoAssetGridCard(
                title = "陆昭 · 新神州玄鳞飞鱼服 V3",
                meta = "v2 · 4 个镜头引用",
                chip = ProductionChipSpec("待审", ProductionTone.WARNING),
                lightBackground = true,
                lookBadge = "1 造型",
                modifier = Modifier.weight(1f),
            )
            ProtoAssetGridCard(
                title = "糕点铺门前 · 晨光",
                meta = "v1 · 3 个镜头引用",
                chip = ProductionChipSpec("已批准", ProductionTone.SUCCESS),
                lightBackground = false,
                icon = Icons.Rounded.Landscape,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Preview(name = "5. 浅色大字号极端适配(360dp·1.3x)", widthDp = 360, heightDp = 1220, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun reviewBadgeProto5LightLargeFontPreview() = BadgeProtoFrame(InfinityThemeMode.LIGHT, sendCount = 1) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ProtoGroupLabel("大字号验证 · 审核弹层标题与长徽章")
        ProtoReviewSheetContainer(
            title = "陆昭晨间问候 20s",
            chip = ProductionChipSpec("已否决 · 未发送", ProductionTone.ERROR),
            subtitle = "AI-Assets/.../lu-zhao-morning-greeting-20s/script.md",
            rejectReason = "台词设计太简短了，明显给人一种赶工的 AI 感",
            verdict = ProtoVerdict.REJECT,
        )
        ProtoGroupLabel("大字号验证 · 场头卡片 FlowRow 自动折行")
        ProtoSceneHeaderCard(
            sceneNumber = 1,
            sceneCode = "SC01",
            heading = "日外 · 承天市城东新老街 · 糕点铺门前",
            meta = "4 镜 · 19.8 秒",
            chip = ProductionChipSpec("已否决 · 未发送", ProductionTone.ERROR),
            showReviewButton = true,
        )
        ProtoGroupLabel("大字号验证 · 浅色纯白底资产网格角标")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProtoAssetGridCard(
                title = "陆昭 · 玄鳞飞鱼服",
                meta = "v2 · 4 镜",
                chip = ProductionChipSpec("待审", ProductionTone.WARNING),
                lightBackground = true,
                modifier = Modifier.weight(1f),
            )
            ProtoAssetGridCard(
                title = "承天街晨雾场景",
                meta = "v1 · 3 镜",
                chip = ProductionChipSpec("已批准 · 未发送", ProductionTone.SUCCESS),
                lightBackground = false,
                icon = Icons.Rounded.Landscape,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
