package com.promenar.infinity.ui.production

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinityScrollingLine
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinityTagScrollRow
import com.promenar.infinity.ui.settings.PrototypeMiniTag
import com.promenar.infinity.ui.settings.PrototypeThemeSurface
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.theme.keepCjkTailTogether

/*
 * 高保真隔离原型 v1：制作面板与有状态接续链（Production Panel）—
 * 1. 执行通道解耦选择器（云端 API 通道 vs 按主机分组的 ComfyUI 工作流）+ 分镜参数区（内容字段直填 + 固定字段基线折叠/实验覆写）；
 * 2. 有状态链式工作流（H3 Motion Context）多段版本树 + 任意中间段发起重试/接续 + 分支隔离与原位覆写防护。
 * 仅位于 debug 源集供 Android Compose Preview 验收。
 */

@Preview(name = "制作面板-通道解耦与工作流参数-浅色412", widthDp = 412, heightDp = 1280, locale = "zh-rCN")
@Composable
fun productionChannelPickerAndShotFormPreview() {
    PrototypeThemeSurface {
        var fixedExpanded by remember { mutableStateOf(true) }
        var speedOverridden by remember { mutableStateOf(true) }
        val scheme = MaterialTheme.colorScheme
        val domain = MaterialTheme.infinityDomainColors

        Column(modifier = Modifier.fillMaxSize()) {
            ProductionTopBar(
                title = "S01-04 走廊追逐长镜",
                meta = listOf("中景", "跟拍", "5 秒"),
                onBack = {},
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ProductionSection(title = "云端 API 通道") {
                    ProductionRow(
                        title = "Seedance 2.0",
                        icon = Icons.Rounded.Cloud,
                        subtitleSegments = listOf("火山方舟", "4K", "最长 15s", "9 图参考"),
                        chip = ProductionChipSpec("默认视频", ProductionTone.INFO),
                        selected = false,
                        chevron = false,
                        onClick = {},
                    )
                    InfinityGroupDivider()
                    ProductionRow(
                        title = "MiniMax H3",
                        icon = Icons.Rounded.Cloud,
                        subtitleSegments = listOf("MiniMax", "2K", "最长 15s", "9 图参考"),
                        selected = false,
                        chevron = false,
                        onClick = {},
                    )
                }

                ProductionSection(title = "ComfyUI · 工作站 4090 (MAIN)") { // wrap-check: allow 分区标题按主机分组命名
                    ProductionRow(
                        title = "MiniMax H3 Motion Context 接续",
                        icon = Icons.Rounded.Dns,
                        subtitleSegments = listOf("链式接续", "submit_h3_motion.py"),
                        chip = ProductionChipSpec("通过", ProductionTone.SUCCESS),
                        selected = true,
                        chevron = false,
                        onClick = {},
                    )
                    InfinityGroupDivider()
                    ProductionRow(
                        title = "MiniMax H3 Ref2VA 多模态参考",
                        icon = Icons.Rounded.Dns,
                        subtitleSegments = listOf("单次提交", "submit_h3.py"),
                        chip = ProductionChipSpec("通过", ProductionTone.SUCCESS),
                        selected = false,
                        chevron = false,
                        onClick = {},
                    )
                }

                ProductionSection(
                    title = "内容字段（按次填写 · 无需审批）",
                    trailing = "已绑定工作流",
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            InfinityTagScrollRow(spacing = 6.dp) {
                                Text("提示词 (prompt)", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 1, softWrap = false)
                                PrototypeMiniTag("映射：提示词", emphasized = true)
                            }
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = scheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = "夜雨霓虹窄巷，林澈回头加速冲过水洼，手持长焦紧跟，雨滴飞溅与急促脚步声景。".keepCjkTailTogether(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurface,
                                    modifier = Modifier.padding(10.dp),
                                )
                            }
                        }
                        InfinityGroupDivider()
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                InfinityTagScrollRow(spacing = 6.dp) {
                                    Text("参考图 (image_refs)", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface, maxLines = 1, softWrap = false)
                                    PrototypeMiniTag("映射：角色/场景", emphasized = true)
                                }
                                InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                    Text("已自动按分张顺序填充：林澈正面、暴雨窄巷", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            PrototypeMiniTag("2 / 9 张")
                        }
                        InfinityGroupDivider()
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                    Text("时间点锚定图 (guides)", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface, maxLines = 1, softWrap = false)
                                }
                                InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                    Text("已挂接 frame 60 转身关键帧", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            InfinityPillButton(text = "管理锚点", onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true)
                        }
                        InfinityGroupDivider()
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("本段帧数 (frames)", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false)
                            PrototypeMiniTag("121 帧 (5.0s)", emphasized = true)
                        }
                    }
                }

                ProductionSection(
                    title = "工作流固定基线（7 项已锁定）",
                    titleAction = {
                        InfinityPillButton(
                            text = if (fixedExpanded) "收起" else "展开",
                            onClick = { fixedExpanded = !fixedExpanded },
                            tone = InfinityPillTone.NEUTRAL,
                            compact = true,
                            icon = if (fixedExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        )
                    },
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        InfinityScrollingLine(Modifier.fillMaxWidth()) {
                            InfinityMetaLine(
                                segments = listOf("i2v_t2v", "context 25 帧", "1280×720", "cfg 1.0", "lora 1.0"),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        if (fixedExpanded) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (speedOverridden) domain.warningContainer else scheme.surfaceContainerHigh,
                                border = BorderStroke(0.6.dp, if (speedOverridden) domain.onWarningContainer.copy(alpha = 0.3f) else scheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = if (speedOverridden) Icons.Rounded.Science else Icons.Rounded.Lock,
                                        contentDescription = null,
                                        tint = if (speedOverridden) domain.onWarningContainer else scheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                            Text(
                                                text = if (speedOverridden) "实验覆写 speed：pika4 → native20" else "全部固定基线未改动",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = if (speedOverridden) domain.onWarningContainer else scheme.onSurface,
                                                maxLines = 1,
                                                softWrap = false,
                                            )
                                        }
                                        Text(
                                            text = "仅本次提交生效（需弹出一次性审批卡，不更改原基线）".keepCjkTailTogether(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (speedOverridden) domain.onWarningContainer.copy(alpha = 0.9f) else scheme.onSurfaceVariant,
                                        )
                                    }
                                    InfinityPillButton(
                                        text = if (speedOverridden) "还原" else "单次实验覆写",
                                        onClick = { speedOverridden = !speedOverridden },
                                        tone = InfinityPillTone.NEUTRAL,
                                        compact = true,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class ProtoChainValidity { MAIN_ACTIVE, ACTIVE, OVERWRITTEN_SIBLING }
private enum class ProtoBranchPolicy { ISOLATED_DIR, IN_PLACE_OVERWRITE }

@Preview(name = "有状态链中段任意接续-浅色412", widthDp = 412, heightDp = 1660, locale = "zh-rCN")
@Preview(name = "有状态链中段任意接续-深色412", widthDp = 412, heightDp = 1660, locale = "zh-rCN", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "有状态链中段任意接续-窄屏大字360", widthDp = 360, heightDp = 2120, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun productionStatefulChainMidRetryPreview() {
    PrototypeThemeSurface {
        var selectedParentId by remember { mutableStateOf("seg_02_v1") }
        var branchPolicy by remember { mutableStateOf(ProtoBranchPolicy.ISOLATED_DIR) }
        val scheme = MaterialTheme.colorScheme
        val domain = MaterialTheme.infinityDomainColors

        Column(modifier = Modifier.fillMaxSize()) {
            ProductionTopBar(
                title = "S01-04 走廊追逐长镜",
                meta = listOf("H3 Motion Context", "4 段主链", "20.0s"),
                onBack = {},
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = scheme.surfaceContainerLow,
                    border = BorderStroke(0.6.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Rounded.Link, null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                Text("当前主链（支持中段分支重跑）", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 1, softWrap = false)
                            }
                            InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                InfinityMetaLine(
                                    segments = listOf("段1 v1", "段2 v1", "段3 v2", "段4 v1"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                        InfinityPillButton(text = "成片", onClick = {}, icon = Icons.Rounded.PlayArrow, tone = InfinityPillTone.NEUTRAL, compact = true)
                    }
                }

                InfinityScrollingLine(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    Text(
                        text = "接续段落版本树（点选任意有效段起续 / 重跑）",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = scheme.primary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                ProtoChainSegmentCard(
                    segmentLabel = "第 1 段 (clip 1 · v1)",
                    timeRange = "0.0–5.0s · 121 帧",
                    summary = "首段建轨：走廊远处出现黑影，镜头匀速推进",
                    parentHint = "首段 (first) · 已建立 latent_dir",
                    validity = ProtoChainValidity.MAIN_ACTIVE,
                    selectedAsParent = selectedParentId == "seg_01_v1",
                    indent = false,
                    onSelectParent = { selectedParentId = "seg_01_v1" },
                )

                ProtoChainSegmentCard(
                    segmentLabel = "第 2 段 (clip 2 · v1)",
                    timeRange = "5.0–10.0s · 121 帧",
                    summary = "追逐拐过电梯口，角色挥手掀翻推车",
                    parentHint = "承接：第 1 段 v1",
                    validity = ProtoChainValidity.MAIN_ACTIVE,
                    selectedAsParent = selectedParentId == "seg_02_v1",
                    indent = false,
                    onSelectParent = { selectedParentId = "seg_02_v1" },
                )

                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ProtoChainSegmentCard(
                        segmentLabel = "第 3 段 (clip 3 · v1 旧版)",
                        timeRange = "10.0–15.0s · 121 帧",
                        summary = "转身时人物右手出现六指穿帮（已从第 2 段重跑取代）",
                        parentHint = "承接：第 2 段 v1",
                        validity = ProtoChainValidity.OVERWRITTEN_SIBLING,
                        selectedAsParent = false,
                        indent = true,
                        onSelectParent = {},
                    )
                    ProtoChainSegmentCard(
                        segmentLabel = "第 3 段 (clip 3 · v2 当前主链)",
                        timeRange = "10.0–15.0s · 121 帧",
                        summary = "顺滑转身冲向安全出口推开防火门",
                        parentHint = "承接：第 2 段 v1",
                        validity = ProtoChainValidity.MAIN_ACTIVE,
                        selectedAsParent = selectedParentId == "seg_03_v2",
                        indent = true,
                        onSelectParent = { selectedParentId = "seg_03_v2" },
                    )
                }

                ProtoChainSegmentCard(
                    segmentLabel = "第 4 段 (clip 4 · v1)",
                    timeRange = "15.0–20.0s · 121 帧",
                    summary = "冲出楼梯间天台，强风吹起外套定格",
                    parentHint = "承接：第 3 段 v2",
                    validity = ProtoChainValidity.MAIN_ACTIVE,
                    selectedAsParent = selectedParentId == "seg_04_v1",
                    indent = false,
                    onSelectParent = { selectedParentId = "seg_04_v1" },
                )

                ProductionSection(title = "从第 2 段 v1 发起第 3 段新分支 (v3)") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = scheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Rounded.Lock, null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                                    InfinityScrollingLine(Modifier.weight(1f)) {
                                        Text("只读继承锁定上下文", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 1, softWrap = false)
                                    }
                                }
                                InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                    InfinityMetaLine(
                                        segments = listOf("parent = seg_02_v1", "load_index = 2", "next = 3"),
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                    InfinityMetaLine(
                                        segments = listOf("latent_dir = shot_04_chain_a", "1280×720", "i2v_t2v", "context 25"),
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }

                        Text("分支潜空间保存策略", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        InfinitySegmentedControl(
                            segments = listOf(
                                InfinitySegment(ProtoBranchPolicy.ISOLATED_DIR, "独立分支目录"),
                                InfinitySegment(ProtoBranchPolicy.IN_PLACE_OVERWRITE, "原位覆写"),
                            ),
                            selected = branchPolicy,
                            onSelect = { branchPolicy = it },
                            fillWidth = true,
                        )

                        if (branchPolicy == ProtoBranchPolicy.IN_PLACE_OVERWRITE) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = domain.warningContainer,
                                border = BorderStroke(0.6.dp, domain.onWarningContainer.copy(alpha = 0.25f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                                    Icon(Icons.Rounded.WarningAmber, null, tint = domain.onWarningContainer, modifier = Modifier.size(18.dp))
                                    Text(
                                        text = "原位模式会覆盖工作站上的 clip_3 潜空间，完成后第 3 段 v2 与第 4 段将标为「潜空间已被覆写」（视频仍保留可回放）。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = domain.onWarningContainer,
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "将第 2 段潜空间衍生写入分支子目录 branch_v3，保留既有第 3 段 v2 与第 4 段继续可接续。",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = scheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("新第 3 段内容字段：提示词与帧锚点", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                                Text(
                                    text = "沿第 2 段掀翻推车后的侧身动势，低机位切入推开防火门，加入门轴吱呀声与急喘，frames = 121。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurface,
                                )
                            }
                        }

                        InfinityPillButton(
                            text = "从第 2 段生成第 3 段 (v3)",
                            onClick = {},
                            tone = InfinityPillTone.PRIMARY,
                            icon = Icons.AutoMirrored.Rounded.CallSplit,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoChainSegmentCard(
    segmentLabel: String,
    timeRange: String,
    summary: String,
    parentHint: String,
    validity: ProtoChainValidity,
    selectedAsParent: Boolean,
    indent: Boolean,
    onSelectParent: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val canContinue = validity != ProtoChainValidity.OVERWRITTEN_SIBLING
    val container = when {
        selectedAsParent -> scheme.primaryContainer.copy(alpha = 0.35f)
        !canContinue -> scheme.surfaceContainerHighest.copy(alpha = 0.5f)
        else -> scheme.surfaceContainerLow
    }
    val border = when {
        selectedAsParent -> BorderStroke(1.5.dp, scheme.primary)
        !canContinue -> BorderStroke(0.6.dp, domain.onWarningContainer.copy(alpha = 0.3f))
        else -> BorderStroke(0.6.dp, scheme.outlineVariant.copy(alpha = 0.6f))
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (canContinue) Modifier.clickable(role = Role.Button, onClick = onSelectParent) else Modifier),
        shape = RoundedCornerShape(16.dp),
        color = container,
        border = border,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(width = 64.dp, height = 38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selectedAsParent) scheme.primary.copy(alpha = 0.18f) else scheme.surfaceContainerHighest)
                        .border(0.5.dp, scheme.outlineVariant, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (indent) Icons.AutoMirrored.Rounded.CallSplit else Icons.Rounded.Movie,
                        contentDescription = null,
                        tint = if (canContinue) scheme.primary else scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    InfinityScrollingLine(Modifier.fillMaxWidth()) {
                        Text(segmentLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface, maxLines = 1, softWrap = false)
                    }
                    InfinityScrollingLine(Modifier.fillMaxWidth()) {
                        InfinityMetaLine(
                            segments = listOf(timeRange, parentHint),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                when {
                    selectedAsParent -> ProductionStatusChip("已选父段", ProductionTone.INFO)
                    !canContinue -> ProductionStatusChip("潜空间覆写", ProductionTone.WARNING)
                    else -> ProductionStatusChip("主链有效", ProductionTone.SUCCESS)
                }
            }

            Text(
                text = summary.keepCjkTailTogether(),
                style = MaterialTheme.typography.bodySmall,
                color = if (canContinue) scheme.onSurface else scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            InfinityTagScrollRow(spacing = 8.dp) {
                InfinityPillButton(
                    text = "回放",
                    onClick = {},
                    tone = InfinityPillTone.NEUTRAL,
                    compact = true,
                    icon = Icons.Rounded.PlayArrow,
                )
                if (canContinue) {
                    InfinityPillButton(
                        text = if (selectedAsParent) "已选起点" else "从此段接续",
                        onClick = onSelectParent,
                        tone = if (selectedAsParent) InfinityPillTone.PRIMARY else InfinityPillTone.NEUTRAL,
                        compact = true,
                        icon = if (selectedAsParent) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.CallSplit,
                    )
                    InfinityPillButton(
                        text = "重跑本段",
                        onClick = {},
                        tone = InfinityPillTone.NEUTRAL,
                        compact = true,
                        icon = Icons.Rounded.Replay,
                    )
                } else {
                    InfinityPillButton(
                        text = "潜空间已失效（仅对比）",
                        onClick = {},
                        enabled = false,
                        tone = InfinityPillTone.NEUTRAL,
                        compact = true,
                    )
                }
            }
        }
    }
}
