package com.promenar.infinity.recording

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.ComparisonBlock
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.PrototypeBoardHeader
import com.promenar.infinity.ui.common.PrototypeBoardSurface
import com.promenar.infinity.ui.common.PrototypeChip
import com.promenar.infinity.ui.common.PrototypeChipTone
import com.promenar.infinity.ui.common.SettingsInput
import com.promenar.infinity.ui.common.StateTransitionBanner
import com.promenar.infinity.ui.theme.infinityDomainColors

/*
 * 全站「非常驻 UI 与布局跳变」治理 — 批次 3（recording/ C1–C8，2 张看板）高保真对比原型。
 * 隔离在 debug 源集，供 Android Compose Preview 审阅，不修改 src/main/ 正式代码。
 */

// =====================================================================
// C1: 录音详情底栏「放弃」与暂停/保存按钮切换
// =====================================================================

@Composable
private fun SampleRecordingFooterBar(active: Boolean, collapsedWhenEmpty: Boolean) {
    val scheme = MaterialTheme.colorScheme
    if (!active && collapsedWhenEmpty) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = scheme.surfaceContainerHighest.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "无底栏动作时：整条底栏含分隔线平滑收起至 0dp",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.infinityDomainColors.success,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        return
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.35f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (active) {
                    if (collapsedWhenEmpty) {
                        InfinityPillButton("放弃", onClick = {}, tone = InfinityPillTone.DANGER, compact = true)
                    } else {
                        Text("放弃", style = MaterialTheme.typography.labelLarge, color = scheme.error, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                if (active) {
                    InfinityPillButton("暂停", onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true)
                    InfinityPillButton("结束并保存", onClick = {}, tone = InfinityPillTone.PRIMARY, compact = true)
                }
            }
        }
    }
}

// =====================================================================
// C2: 转写区顶部 RecordingPaneHeader + 识别进度 + 未安装提示
// =====================================================================

@Composable
private fun SampleTranscriptPaneTop(
    recordingActive: Boolean,
    recognizing: Boolean,
    modelMissing: Boolean,
    showNote: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!recordingActive) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("逐字稿", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, maxLines = 1)
                    Icon(Icons.Rounded.MoreHoriz, contentDescription = "逐字稿选项", modifier = Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                }
            }
            if (recognizing) {
                if (showNote) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("正在重新识别 · 64%", style = MaterialTheme.typography.labelSmall, color = scheme.primary, maxLines = 1) // wrap-check: allow 原型单行样例
                            InfinityPillButton("取消", onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true)
                        }
                        LinearProgressIndicator(progress = { 0.64f }, modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("正在重新识别 · 64%", style = MaterialTheme.typography.labelSmall, color = scheme.primary, maxLines = 1) // wrap-check: allow 原型单行样例
                        LinearProgressIndicator(progress = { 0.64f }, modifier = Modifier.fillMaxWidth())
                        InfinityPillButton("取消", onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (modelMissing) {
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.infinityDomainColors.warningContainer.copy(alpha = 0.55f), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("尚未安装听写模型", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.infinityDomainColors.onWarningContainer, maxLines = 1)
                        InfinityPillButton("下载", onClick = {}, tone = InfinityPillTone.PRIMARY, compact = true)
                    }
                }
            }
            Text(
                text = if (showNote) "三区包 AnimatedVisibility（取消平行、进度全宽）" else "00:08 这里接下文逐字稿内容…",
                style = MaterialTheme.typography.bodySmall,
                color = if (showNote) MaterialTheme.infinityDomainColors.success else scheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

// =====================================================================
// C3: 整理区 OrganizedPaneHeader 编辑图标按钮卸载
// =====================================================================

@Composable
private fun SampleOrganizedHeader(
    organizing: Boolean,
    fixedSlotAndHeight: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (fixedSlotAndHeight) 48.dp else 40.dp)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("整理稿", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1)
            if (fixedSlotAndHeight) {
                InfinityPillButton("中文", onClick = {}, icon = Icons.Rounded.Language, tone = InfinityPillTone.NEUTRAL, compact = true)
            } else {
                Text("中文", style = MaterialTheme.typography.labelLarge, color = scheme.primary, maxLines = 1)
            }
            if (fixedSlotAndHeight || !organizing) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .alpha(if (organizing) 0.35f else 1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Edit, contentDescription = "编辑整理稿", modifier = Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                }
            }
            Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.FileDownload, contentDescription = "导出录音文档", modifier = Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
            }
        }
    }
}

// =====================================================================
// C5: 停录时时间戳纯文本变 InfinityPillButton(compact)
// =====================================================================

@Composable
private fun SampleTranscriptTimestampRow(recordingActive: Boolean, unifiedCapsuleHeight: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (recordingActive && !unifiedCapsuleHeight) {
                    Text("00:12", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1)
                } else {
                    Surface(
                        shape = CircleShape,
                        color = if (recordingActive) scheme.surfaceContainerHighest.copy(alpha = 0.45f) else scheme.surfaceContainerHighest,
                        modifier = Modifier.height(32.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp).alpha(if (recordingActive) 0.35f else 1f),
                                tint = scheme.onSurfaceVariant,
                            )
                            Text("00:12", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
                Text("说话人 1", style = MaterialTheme.typography.labelSmall, color = scheme.primary, maxLines = 1)
            }
            Text("我们先把录音模块的实时转写延迟核对一遍。", style = MaterialTheme.typography.bodySmall, color = scheme.onSurface, maxLines = 1)
        }
    }
}

// =====================================================================
// C4: 说话人分析按钮 / 进度 / 标签条
// =====================================================================

@Composable
private fun SampleSpeakerHeader(step: Int, singleUnifiedSlot: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (step) {
                0 -> Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (singleUnifiedSlot) "声纹待分析（统一容器）" else "点击分析前：挂 1 枚按钮", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                    InfinityPillButton("分析说话人", onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true)
                }
                1 -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("分析说话人 · 58%", style = MaterialTheme.typography.labelSmall, color = scheme.primary, maxLines = 1) // wrap-check: allow 原型单行样例
                    LinearProgressIndicator(progress = { 0.58f }, modifier = Modifier.fillMaxWidth())
                }
                else -> Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrototypeChip("说话人 1 · 62%", PrototypeChipTone.INFO)
                    PrototypeChip("说话人 2 · 38%", PrototypeChipTone.SUCCESS)
                }
            }
        }
    }
}

// =====================================================================
// C6: RecordingModelPackageCard 1 行 <-> 3 行硬切
// =====================================================================

@Composable
private fun SampleAsrPackageCard(downloading: Boolean, compactInlineProgress: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (compactInlineProgress) {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("听写模型", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 1)
                        Text(
                            text = if (downloading) "正在下载 · 45%" else "约 150 MB", // wrap-check: allow 原型单行样例
                            style = MaterialTheme.typography.labelSmall,
                            color = if (downloading) scheme.primary else scheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    if (downloading) {
                        InfinityPillButton("取消", onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.infinityDomainColors.success)
                            Text("已安装", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.infinityDomainColors.success, maxLines = 1)
                        }
                    }
                }
                if (downloading) {
                    LinearProgressIndicator(progress = { 0.45f }, modifier = Modifier.fillMaxWidth())
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("听写模型", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 1)
                    if (!downloading) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.infinityDomainColors.success)
                            Text("已安装", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.infinityDomainColors.success, maxLines = 1)
                        }
                    }
                }
                if (downloading) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("正在下载 · 45%", style = MaterialTheme.typography.labelSmall, color = scheme.primary, maxLines = 1) // wrap-check: allow 原型单行样例
                        LinearProgressIndicator(progress = { 0.45f }, modifier = Modifier.fillMaxWidth())
                        InfinityPillButton("取消", onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

// =====================================================================
// C7: 15 秒声纹采样完成后输入框与底栏按钮跳变
// =====================================================================

@Composable
private fun SampleVoiceEnrollmentCard(sampled: Boolean, persistentSlots: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp), tint = scheme.primary)
                Text(if (sampled) "已采集 15 秒语音样本" else "请朗读上方例句 15 秒", style = MaterialTheme.typography.labelMedium, color = scheme.onSurface, maxLines = 1)
            }
            if (sampled || persistentSlots) {
                SettingsInput(
                    value = if (sampled) "陆昭" else "",
                    onValueChange = {},
                    label = "姓名",
                    placeholder = "采集后填说话人姓名",
                )
            }
            InfinityPillRow(modifier = Modifier.fillMaxWidth()) {
                if (sampled || persistentSlots) {
                    InfinityPillButton(
                        text = if (sampled) "重录" else "开始",
                        onClick = {},
                        tone = if (sampled) InfinityPillTone.NEUTRAL else InfinityPillTone.PRIMARY,
                        compact = true,
                    )
                    InfinityPillButton(
                        text = "保存",
                        onClick = {},
                        tone = InfinityPillTone.PRIMARY,
                        enabled = sampled,
                        compact = true,
                    )
                } else {
                    InfinityPillButton("开始", onClick = {}, tone = InfinityPillTone.PRIMARY, compact = true)
                }
            }
        }
    }
}

// =====================================================================
// C8: RecordingSetupPage 下载状态条与底栏按钮宽度硬切
// =====================================================================

@Composable
private fun SampleSetupBottomArea(complete: Boolean, persistentDualSlot: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(10.dp), color = scheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (complete) "所选模型已就绪" else "正在准备所选模型 · 72%", // wrap-check: allow 原型单行样例
                        style = MaterialTheme.typography.labelSmall,
                        color = if (complete) MaterialTheme.infinityDomainColors.success else scheme.onSurface,
                        maxLines = 1,
                    )
                    if (!complete) {
                        LinearProgressIndicator(progress = { 0.72f }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (persistentDualSlot) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfinityPillButton(
                        text = "稍后设置",
                        onClick = {},
                        tone = InfinityPillTone.NEUTRAL,
                        enabled = !complete,
                        compact = true,
                        modifier = Modifier.weight(1f).alpha(if (complete) 0.35f else 1f),
                    )
                    InfinityPillButton(
                        text = if (complete) "进入应用" else "暂停下载",
                        onClick = {},
                        tone = InfinityPillTone.PRIMARY,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    InfinityPillButton(if (complete) "进入应用" else "稍后设置", onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true)
                    if (!complete) {
                        InfinityPillButton("暂停下载", onClick = {}, tone = InfinityPillTone.PRIMARY, compact = true)
                    }
                }
            }
        }
    }
}

// =====================================================================
// 批次 3 两张 Compose Preview 看板
// =====================================================================

@Preview(name = "B3-1 录音底栏、转写整理页头与时间戳对比 (深色)", widthDp = 820, heightDp = 1660, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun recordingBatch3ActiveAndTranscriptPreview() {
    PrototypeBoardSurface(dark = true) {
        PrototypeBoardHeader(
            batchBadge = "批次 3 · 1/2",
            title = "录音模块 recording/ · 详情底栏、转写整理页头与时间戳 (C1/C2/C3/C5)",
            subtitle = "左：现状无动画硬切与停录整表剧跳 ｜ 右：建议方案",
        )
        ComparisonBlock(
            code = "C1",
            title = "RecordingContent 底部「放弃」与暂停/保存按钮切换",
            sourceRef = "RecordingContent.kt:375-387, 1422-1436",
            beforeSummary = "停录后「放弃」硬卸，右按钮为 0 时底栏塌成空白带",
            afterSummary = "无操作态整栏 shrinkOut 收起，按钮组 fadeThrough 过渡",
            beforeContent = {
                StateTransitionBanner("录音中：左「放弃」+ 右「暂停 / 结束并保存」")
                SampleRecordingFooterBar(active = true, collapsedWhenEmpty = false)
                StateTransitionBanner("停录且不可续录：右侧全空，留出 18dp 空白带")
                SampleRecordingFooterBar(active = false, collapsedWhenEmpty = false)
            },
            afterContent = {
                StateTransitionBanner("录音中：统一底栏操作布局")
                SampleRecordingFooterBar(active = true, collapsedWhenEmpty = true)
                StateTransitionBanner("停录且不可续录：AnimatedVisibility 连分隔线整体收起")
                SampleRecordingFooterBar(active = false, collapsedWhenEmpty = true)
            },
        )
        ComparisonBlock(
            code = "C2",
            title = "转写区顶部 RecordingPaneHeader + 识别进度 + 未安装提示",
            sourceRef = "RecordingContent.kt:775-797",
            beforeSummary = "停录硬插 40dp 页头，进度与模型提示条无动画插拔",
            afterSummary = "三区均加 AnimatedVisibility，宽屏更多钮固定 40dp 槽",
            beforeContent = {
                StateTransitionBanner("录音中：窄屏无页头")
                SampleTranscriptPaneTop(recordingActive = true, recognizing = false, modelMissing = false, showNote = false)
                StateTransitionBanner("停录 + 重新识别 + 未安装：瞬插三块推下逐字稿")
                SampleTranscriptPaneTop(recordingActive = false, recognizing = true, modelMissing = true, showNote = false)
            },
            afterContent = {
                StateTransitionBanner("停录 + 识别中：expandIn 平滑展开，无瞬推冲击")
                SampleTranscriptPaneTop(recordingActive = false, recognizing = true, modelMissing = true, showNote = true)
            },
        )
        ComparisonBlock(
            code = "C3",
            title = "整理区 OrganizedPaneHeader 重新整理时卸载编辑按钮",
            sourceRef = "RecordingContent.kt:800-826",
            beforeSummary = "重整理时直接卸载编辑图标钮，导出/语言钮横向右跳",
            afterSummary = "编辑钮常驻以 enabled 置灰禁用，锁死 48dp 行高",
            beforeContent = {
                StateTransitionBanner("空闲态：语言 / 编辑 / 导出")
                SampleOrganizedHeader(organizing = false, fixedSlotAndHeight = false)
                StateTransitionBanner("重新整理中：卸载编辑钮，左侧语言文字突然右移")
                SampleOrganizedHeader(organizing = true, fixedSlotAndHeight = false)
            },
            afterContent = {
                StateTransitionBanner("空闲态：固定 48dp 行高与常驻操作槽")
                SampleOrganizedHeader(organizing = false, fixedSlotAndHeight = true)
                StateTransitionBanner("重新整理中：编辑钮原地置灰禁用，邻钮零位移")
                SampleOrganizedHeader(organizing = true, fixedSlotAndHeight = true)
            },
        )
        ComparisonBlock(
            code = "C5",
            title = "停录时时间戳由 16dp 纯文本突变 48dp 胶囊",
            sourceRef = "RecordingContent.kt:871-892",
            beforeSummary = "停录瞬间全列表每段 +32dp，整屏逐字稿大幅弹跳",
            afterSummary = "实录态复用等高胶囊占位，停录淡入播放图标（0dp 跳变）",
            beforeContent = {
                StateTransitionBanner("录音中：16dp 纯文本时间戳（紧凑行高）")
                SampleTranscriptTimestampRow(recordingActive = true, unifiedCapsuleHeight = false)
                StateTransitionBanner("停录瞬间：变 48dp 可点胶囊，整列段距陡增")
                SampleTranscriptTimestampRow(recordingActive = false, unifiedCapsuleHeight = false)
            },
            afterContent = {
                StateTransitionBanner("录音中：等高禁用胶囊占位，与停录态完全同高")
                SampleTranscriptTimestampRow(recordingActive = true, unifiedCapsuleHeight = true)
                StateTransitionBanner("停录后：原位淡入底色与图标，0dp 列表滚动漂移")
                SampleTranscriptTimestampRow(recordingActive = false, unifiedCapsuleHeight = true)
            },
        )
    }
}

@Preview(name = "B3-2 说话人分析、ASR 模型、声纹录入与引导对比 (深色)", widthDp = 820, heightDp = 1860, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun recordingBatch3SpeakerModelsVoiceSetupPreview() {
    PrototypeBoardSurface(dark = true) {
        PrototypeBoardHeader(
            batchBadge = "批次 3 · 2/2",
            title = "录音模块 recording/ · 说话人分析、模型卡片、15 秒声纹与引导 (C4/C6/C7/C8)",
            subtitle = "已纠偏真实文案：C1「放弃」、C7「15 秒声纹」、C8「下载所选 / 进入应用」",
        )
        ComparisonBlock(
            code = "C4",
            title = "说话人分析：待分析钮 → 进度条 → 占比条三次顶栏跳变",
            sourceRef = "RecordingContent.kt:502-505 · RecordingSpeakerUi.kt:109-169",
            beforeSummary = "按钮→进度→消失叠加下方标签条，引发连续 3 次跳高",
            afterSummary = "收口为单个 AnimatedContent(fadeThrough) 状态容器",
            beforeContent = {
                SampleSpeakerHeader(step = 0, singleUnifiedSlot = false)
                SampleSpeakerHeader(step = 1, singleUnifiedSlot = false)
                SampleSpeakerHeader(step = 2, singleUnifiedSlot = false)
            },
            afterContent = {
                SampleSpeakerHeader(step = 0, singleUnifiedSlot = true)
                SampleSpeakerHeader(step = 1, singleUnifiedSlot = true)
                SampleSpeakerHeader(step = 2, singleUnifiedSlot = true)
            },
        )
        ComparisonBlock(
            code = "C6",
            title = "RecordingModelPackageCard 1 行与 3 行竖排硬切",
            sourceRef = "RecordingAsrControls.kt:209-327",
            beforeSummary = "Downloading/Installing 为 3 行竖排 ~80dp，无过渡硬切",
            afterSummary = "取消胶囊与标题平行右置，进度条全宽贴底平滑展开",
            beforeContent = {
                SampleAsrPackageCard(downloading = false, compactInlineProgress = false)
                SampleAsrPackageCard(downloading = true, compactInlineProgress = false)
            },
            afterContent = {
                SampleAsrPackageCard(downloading = false, compactInlineProgress = true)
                SampleAsrPackageCard(downloading = true, compactInlineProgress = true)
            },
        )
        ComparisonBlock(
            code = "C7",
            title = "RecordingVoiceEnrollment 15 秒声纹采样完成",
            sourceRef = "RecordingVoiceLibrary.kt:59, 242-368",
            beforeSummary = "15 秒完毕硬插「姓名」框并把底栏单钮压成半宽双钮",
            afterSummary = "常驻输入框 +「开始/重录」与「保存」双槽固定（未采禁用）",
            beforeContent = {
                StateTransitionBanner("采样前：底栏仅 1 枚占满宽的「开始」")
                SampleVoiceEnrollmentCard(sampled = false, persistentSlots = false)
                StateTransitionBanner("15 秒完毕：突插输入框并压成双按钮（窄屏折两行）")
                SampleVoiceEnrollmentCard(sampled = true, persistentSlots = false)
            },
            afterContent = {
                StateTransitionBanner("采样前：双按钮槽已定宽，「保存」置灰待采")
                SampleVoiceEnrollmentCard(sampled = false, persistentSlots = true)
                StateTransitionBanner("15 秒完毕：直接聚焦输入姓名并点亮「保存」")
                SampleVoiceEnrollmentCard(sampled = true, persistentSlots = true)
            },
        )
        ComparisonBlock(
            code = "C8",
            title = "RecordingSetupPage 下载状态条与完成态底栏按钮变化",
            sourceRef = "RecordingSetupPage.kt:225-277",
            beforeSummary = "完成突缩进度条，右钮卸载致「进入应用」孤立靠左",
            afterSummary = "右槽固定主按钮原地切「暂停/进入应用」，左槽淡隐",
            beforeContent = {
                SampleSetupBottomArea(complete = false, persistentDualSlot = false)
                SampleSetupBottomArea(complete = true, persistentDualSlot = false)
            },
            afterContent = {
                SampleSetupBottomArea(complete = false, persistentDualSlot = true)
                SampleSetupBottomArea(complete = true, persistentDualSlot = true)
            },
        )
    }
}
