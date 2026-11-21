package com.promenar.infinity.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.ComparisonBlock
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinityStatusKind
import com.promenar.infinity.ui.common.InfinityStatusStrip
import com.promenar.infinity.ui.common.NarrowLightVerificationFrame
import com.promenar.infinity.ui.common.PrototypeBoardHeader
import com.promenar.infinity.ui.common.PrototypeBoardSurface
import com.promenar.infinity.ui.common.PrototypeChip
import com.promenar.infinity.ui.common.PrototypeChipTone
import com.promenar.infinity.ui.common.SettingsInput
import com.promenar.infinity.ui.common.StateTransitionBanner
import com.promenar.infinity.ui.theme.infinityDomainColors

/*
 * 全站「非常驻 UI 与布局跳变」治理 — 批次 5（ui/library/ + ui/hub/ + ui/canvas/ + ui/settings/ E1–E14，4 张看板）高保真对比原型。
 * 隔离在 debug 源集，供 Android Compose Preview 审阅，不修改 src/main/ 正式代码。
 */

// =====================================================================
// E1–E3: ui/library/ 资料库（问资料 / 详情定位与图谱 / 篇目顺序）
// =====================================================================

@Composable
private fun SampleLibraryAskTop(hasAnswerAndFilters: Boolean, afterNote: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(10.dp), color = scheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (hasAnswerAndFilters) "资料里的答案" else "输入时只按关键词实时匹配；按搜索键生成答案。",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (hasAnswerAndFilters) scheme.primary else scheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                    if (hasAnswerAndFilters) {
                        Text("已汇总 3 条资料，点 [1] 可跳转到命中段落。", style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
                    }
                }
            }
            if (hasAnswerAndFilters) {
                InfinitySegmentedControl(
                    segments = listOf(
                        InfinitySegment(0, "全部", count = 6),
                        InfinitySegment(1, "文档", count = 4),
                        InfinitySegment(2, "记忆", count = 2),
                    ),
                    selected = 0,
                    onSelect = {},
                    compact = true,
                    fillWidth = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = if (afterNote) "head 用 fadeThrough；筛选条 expandIn 展开" else "下方匹配列表随输入反复上下跳动",
                style = MaterialTheme.typography.labelSmall,
                color = if (afterNote) MaterialTheme.infinityDomainColors.success else scheme.error,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SampleDocLocateAndGraph(afterSteady: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(10.dp), color = scheme.primaryContainer.copy(alpha = 0.55f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("问资料命中 3 处 · 第 1 处", style = MaterialTheme.typography.labelMedium, color = scheme.onPrimaryContainer, maxLines = 1) // wrap-check: allow 原型单行样例
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Rounded.ArrowUpward, contentDescription = "上一处", modifier = Modifier.size(16.dp), tint = scheme.onPrimaryContainer)
                        Icon(Icons.Rounded.ArrowDownward, contentDescription = "下一处", modifier = Modifier.size(16.dp), tint = scheme.onPrimaryContainer)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("知识图谱", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 1)
                    Text(if (afterSteady) "抽取中 2 / 5" else "尚未抽取", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                }
                InfinityPillButton(
                    text = if (afterSteady) "抽取中" else "抽取图谱",
                    onClick = {},
                    tone = InfinityPillTone.NEUTRAL,
                    enabled = !afterSteady,
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun SampleKgOrderHeader(hasManual: Boolean, persistentButton: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (persistentButton) 48.dp else 36.dp)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("篇目顺序", style = MaterialTheme.typography.labelLarge, color = scheme.primary, maxLines = 1)
            if (persistentButton || hasManual) {
                InfinityPillButton(
                    text = "按名称排序",
                    onClick = {},
                    tone = InfinityPillTone.NEUTRAL,
                    enabled = hasManual,
                    compact = true,
                )
            }
        }
    }
}

// =====================================================================
// E4–E7: ui/hub/ + ui/canvas/ 跳变对比
// =====================================================================

@Composable
private fun SampleHubTopBanners(afterAnimated: Boolean) {
    val domain = MaterialTheme.infinityDomainColors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InfinityStatusStrip(kind = InfinityStatusKind.Warning, message = "1 个启动自检问题 · 已暂停写入")
        InfinityStatusStrip(kind = InfinityStatusKind.Error, message = "Linux 环境不可用 · 2 个会话待修复")
        Text(
            text = if (afterAnimated) "两条异步横幅分别包 expandIn + LatestValue" else "首帧异步返回后硬插 2 张卡，列表骤推 130dp",
            style = MaterialTheme.typography.labelSmall,
            color = if (afterAnimated) domain.success else MaterialTheme.colorScheme.error,
            maxLines = 1,
        )
    }
}

@Composable
private fun SampleAgentEditNoticeAndConflict(afterSnackbar: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!afterSnackbar) {
            InfinityStatusStrip(kind = InfinityStatusKind.Success, message = "已保存你的修改（2.6 秒后整页上弹）")
        } else {
            Surface(shape = RoundedCornerShape(10.dp), color = scheme.inverseSurface, modifier = Modifier.fillMaxWidth()) {
                Text("底部浮层：已保存你的修改（不占表单高度）", style = MaterialTheme.typography.labelSmall, color = scheme.inverseOnSurface, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), maxLines = 1)
            }
        }
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.infinityDomainColors.warningContainer.copy(alpha = 0.55f), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("智能体也修改了系统提示词", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.infinityDomainColors.onWarningContainer, maxLines = 1)
                if (afterSnackbar) {
                    Text("下方 220dp 差异视图使用 AnimatedVisibility 展开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.infinityDomainColors.onWarningContainer, maxLines = 1)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfinityPillButton("查看差异", onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true)
                    InfinityPillButton("采用新内容", onClick = {}, tone = InfinityPillTone.PRIMARY, compact = true)
                }
            }
        }
    }
}

@Composable
private fun SampleCanvasHistoryBar(showDuplicateNoticeRow: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp), tint = scheme.onSurface)
                    Text("交互原型画板", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, maxLines = 1)
                }
                Surface(shape = RoundedCornerShape(8.dp), color = scheme.surfaceContainerHighest) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("第 2 版 · 只读", style = MaterialTheme.typography.labelMedium, color = scheme.onSurface, maxLines = 1) // wrap-check: allow 原型单行样例
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                    }
                }
            }
            if (showDuplicateNoticeRow) {
                Text("历史版本只读（与选择器「只读」重复且压低画板）", style = MaterialTheme.typography.labelSmall, color = scheme.error, maxLines = 1)
            }
        }
    }
}

// =====================================================================
// E8–E14: ui/settings/ 设置页（Termux / Tailscale / SSH / 备份 / 本地模型 / 媒体 / 搜索）
// =====================================================================

@Composable
private fun SampleInfraSettingsComparison(
    code: String,
    beforeText: String,
    afterText: String,
    isAfter: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Surface(shape = RoundedCornerShape(12.dp), color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(code, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 1)
                PrototypeChip(if (isAfter) "平滑零闪跳" else "无动画硬插", if (isAfter) PrototypeChipTone.SUCCESS else PrototypeChipTone.ERROR)
            }
            if (code == "Termux" && !isAfter) {
                InfinityStatusStrip(kind = InfinityStatusKind.Info, message = "已与底部 Snackbar 重复的内联通知")
            }
            if (code == "SSH 保存态") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfinityPillButton("取消", onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true, modifier = Modifier.weight(1f))
                    InfinityPillButton(if (isAfter) "保存中…" else "保存", onClick = {}, tone = InfinityPillTone.PRIMARY, compact = true, modifier = Modifier.weight(1f))
                }
            }
            Text(
                text = if (isAfter) afterText else beforeText,
                style = MaterialTheme.typography.labelSmall,
                color = if (isAfter) domain.success else scheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SampleMediaOperationHeader(busy: Boolean, overlayTopBar: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(12.dp), color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            if (overlayTopBar) {
                Box(Modifier.fillMaxWidth().height(3.dp)) {
                    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
                }
            } else if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp))
            }
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("ComfyUI 主机列表", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, maxLines = 1)
                PrototypeChip(if (overlayTopBar) "检测无下抖" else "检测整页下抖", if (overlayTopBar) PrototypeChipTone.SUCCESS else PrototypeChipTone.WARNING)
            }
        }
    }
}

@Composable
private fun SampleSearchEngineConfig(engine: String, isAfter: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(12.dp), color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfinitySegmentedControl(
                segments = listOf(
                    InfinitySegment("ddg", "DuckDuckGo"),
                    InfinitySegment("tavily", "Tavily"),
                    InfinitySegment("searx", "SearXNG"),
                ),
                selected = engine,
                onSelect = {},
                compact = true,
                fillWidth = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (engine == "searx") {
                SettingsInput(value = "https://search.local", onValueChange = {}, label = "实例地址 (SearXNG)")
            } else if (engine == "tavily") {
                SettingsInput(value = "tvly-••••••••", onValueChange = {}, label = "API 密钥")
            }
            Text(
                text = if (isAfter) "AnimatedContent(fadeThrough) 切换 0–2 张配置卡" else "切引擎时 0–2 张配置卡片硬跳弹层高度",
                style = MaterialTheme.typography.labelSmall,
                color = if (isAfter) MaterialTheme.infinityDomainColors.success else scheme.error,
                maxLines = 1,
            )
        }
    }
}

// =====================================================================
// 批次 5 四张 Compose Preview 看板
// =====================================================================

@Preview(name = "B5-1 资料库跳转与排序对比 (深色)", widthDp = 820, heightDp = 1280, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun libraryBatch5Preview() {
    PrototypeBoardSurface(dark = true) {
        PrototypeBoardHeader(
            batchBadge = "批次 5 · 1/4",
            title = "资料库 ui/library/ · 问资料、详情定位与篇目排序 (E1–E3)",
            subtitle = "已纠偏 E3 真实按钮文案「按名称排序」（非恢复默认顺序）",
        )
        ComparisonBlock(
            code = "E1",
            title = "LibraryAskPage 问答区 head 切换与条件筛选分段",
            sourceRef = "LibraryAskPage.kt:171-179, 375-395",
            beforeSummary = "提示↔答案硬切，输入中筛选条因结果类别反复跳动",
            afterSummary = "head 包 fadeThrough；筛选条 expandIn 或灰显常驻",
            beforeContent = {
                SampleLibraryAskTop(hasAnswerAndFilters = false, afterNote = false)
                SampleLibraryAskTop(hasAnswerAndFilters = true, afterNote = false)
            },
            afterContent = {
                SampleLibraryAskTop(hasAnswerAndFilters = false, afterNote = true)
                SampleLibraryAskTop(hasAnswerAndFilters = true, afterNote = true)
            },
        )
        ComparisonBlock(
            code = "E2",
            title = "LibraryPagesContent 异步定位条与行尾「抽取图谱」",
            sourceRef = "LibraryPagesContent.kt:699-725, 973-1067",
            beforeSummary = "异步加载硬插定位条下推正文，抽取中按钮突消",
            afterSummary = "定位条 expandIn + LatestValue；按钮保留禁用占位",
            beforeContent = { SampleDocLocateAndGraph(afterSteady = false) },
            afterContent = { SampleDocLocateAndGraph(afterSteady = true) },
        )
        ComparisonBlock(
            code = "E3",
            title = "KgCollectionSheets 手动排序插入「按名称排序」",
            sourceRef = "KgCollectionSheets.kt:403-410",
            beforeSummary = "首次调整顺序标题行硬插 48dp 按钮，撑高标题下推列表",
            afterSummary = "标题行锁 48dp 高，按名称排序常驻（未调整时置灰）",
            beforeContent = {
                SampleKgOrderHeader(hasManual = false, persistentButton = false)
                SampleKgOrderHeader(hasManual = true, persistentButton = false)
            },
            afterContent = {
                SampleKgOrderHeader(hasManual = false, persistentButton = true)
                SampleKgOrderHeader(hasManual = true, persistentButton = true)
            },
        )
    }
}

@Preview(name = "B5-2 智能体中心与画板顶栏对比 (深色)", widthDp = 820, heightDp = 1240, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun hubAndCanvasBatch5Preview() {
    PrototypeBoardSurface(dark = true) {
        PrototypeBoardHeader(
            batchBadge = "批次 5 · 2/4",
            title = "智能体中心 ui/hub/ · 画板 ui/canvas/ (E4–E7)",
            subtitle = "消除异步横幅突插、2.6 秒自动消失回弹、220dp 差异硬弹与重复只读行",
        )
        ComparisonBlock(
            code = "E4",
            title = "AgentHubScreen 顶栏下方异步自检 / Linux 横幅",
            sourceRef = "AgentHubScreen.kt:222-245, 695-713",
            beforeSummary = "异步 IO 统计返回后硬插 1–2 张通知卡下推对话列表",
            afterSummary = "两条横幅包 AnimatedVisibility(expandIn) + LatestValue",
            beforeContent = { SampleHubTopBanners(afterAnimated = false) },
            afterContent = { SampleHubTopBanners(afterAnimated = true) },
        )
        ComparisonBlock(
            code = "E5+E6",
            title = "AgentEditScreen 2.6 秒通知 / 冲突 + 220dp 差异视图",
            sourceRef = "AgentEditScreen.kt:295-348 · AgentEditConflictViews.kt:116-140",
            beforeSummary = "2.6s 通知自毁致表单骤弹；点差异瞬插 245dp 推编辑区",
            afterSummary = "2.6s 通知走 SnackbarHost 浮层；差异区 expandIn 平滑展开",
            beforeContent = { SampleAgentEditNoticeAndConflict(afterSnackbar = false) },
            afterContent = { SampleAgentEditNoticeAndConflict(afterSnackbar = true) },
        )
        ComparisonBlock(
            code = "E7",
            title = "CanvasComponents & PrototypeBoard 历史只读行",
            sourceRef = "CanvasComponents.kt:419-426 · PrototypeBoard.kt:193-200",
            beforeSummary = "选择器已标「只读」，下方仍插一行重复文字压低画板",
            afterSummary = "移除重复「历史版本只读」独立行，切换零高度跳变",
            beforeContent = { SampleCanvasHistoryBar(showDuplicateNoticeRow = true) },
            afterContent = { SampleCanvasHistoryBar(showDuplicateNoticeRow = false) },
        )
    }
}

@Preview(name = "B5-3 系统与网络设置页对比 (深色)", widthDp = 820, heightDp = 1220, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun settingsInfraBatch5Preview() {
    PrototypeBoardSurface(dark = true) {
        PrototypeBoardHeader(
            batchBadge = "批次 5 · 3/4",
            title = "系统设置 ui/settings/ · Termux / Tailscale / SSH / 备份 (E8–E11)",
            subtitle = "统一清理闪烁进度条、重复内联通知、无 key 列表硬插与 scrollToItem 瞬跳",
        )
        ComparisonBlock(
            code = "E8",
            title = "TermuxSettingsScreen 进度条 / 重复通知 / 权限行",
            sourceRef = "TermuxSettingsScreen.kt:365-425",
            beforeSummary = "回前台进度条闪烁；内联通知与 Snackbar 重复；权限行消失",
            afterSummary = "去重复内联条，权限行常驻置灰，进度与诊断平稳折叠",
            beforeContent = {
                SampleInfraSettingsComparison(
                    code = "Termux",
                    beforeText = "回前台刷新闪烁下推 · 授权后第 3 步消失上跳",
                    afterText = "",
                    isAfter = false,
                )
            },
            afterContent = {
                SampleInfraSettingsComparison(
                    code = "Termux",
                    beforeText = "",
                    afterText = "去重内联提示 · 权限行常驻灰显 · 诊断平稳展开",
                    isAfter = true,
                )
            },
        )
        ComparisonBlock(
            code = "E9+E10",
            title = "Tailscale 异步分区 + SSH 状态卡与编辑器 busy",
            sourceRef = "TailscaleSettingsScreen.kt:269-401 · SshSettingsScreen.kt:454-1078",
            beforeSummary = "Tailscale/SSH 无 key 无动画；编辑 busy 单独起行推按钮",
            afterSummary = "补 key+animateItem；SSH 状态走 Snackbar、busy 入保存钮",
            beforeContent = {
                SampleInfraSettingsComparison(
                    code = "Tailscale",
                    beforeText = "启用后异步硬插状态/提示/设备卡，高级卡被骤推",
                    afterText = "",
                    isAfter = false,
                )
                SampleInfraSettingsComparison(
                    code = "SSH 保存态",
                    beforeText = "状态卡频闪 + 编辑器 busy 独立起行推下按钮 36dp",
                    afterText = "",
                    isAfter = false,
                )
            },
            afterContent = {
                SampleInfraSettingsComparison(
                    code = "Tailscale",
                    beforeText = "",
                    afterText = "分区包 expandIn + key；本机/登录行平滑显现",
                    isAfter = true,
                )
                SampleInfraSettingsComparison(
                    code = "SSH 保存态",
                    beforeText = "",
                    afterText = "状态走 Snackbar · 忙态圆环嵌进「保存」按钮内",
                    isAfter = true,
                )
            },
        )
        ComparisonBlock(
            code = "E11",
            title = "BackupSettingsScreen 顶部操作卡 / 估算行数 / WebDAV 态",
            sourceRef = "BackupSettingsScreen.kt:112-116, 301-321, 427-556",
            beforeSummary = "操作条撑开且 scrollToItem(1) 瞬滚；估算/WebDAV 态硬切",
            afterSummary = "操作条 expandIn + animateScrollToItem(0)；态切 fadeThrough",
            beforeContent = {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("正在上传备份…（撑高顶部又触发瞬滚到 item 1）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                Text("WebDAV：加载中 / 暂无备份 / 列表之间直接硬切", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            },
            afterContent = {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("正在上传备份…（平滑展开 + 平滑滚到顶部可见）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.infinityDomainColors.success, maxLines = 1)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                Text("WebDAV：fadeThrough 切换态，估算行数平稳增高", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.infinityDomainColors.success, maxLines = 1)
            },
        )
    }
}

@Preview(name = "B5-4 模型、公共媒体条与搜索配置 + 360dp 验收", widthDp = 820, heightDp = 1320, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun settingsModelsAndSkillsBatch5Preview() {
    PrototypeBoardSurface(dark = true) {
        PrototypeBoardHeader(
            batchBadge = "批次 5 · 4/4",
            title = "模型、媒体与技能设置 (E12/E13/E14) · 含 360dp 浅色大字号验证",
            subtitle = "解决 7 页共用 MediaOperationState 抖动、本地模型双向跳动与引擎表单跳高",
        )
        ComparisonBlock(
            code = "E12",
            title = "LocalModelsScreen 下载进度条与空态双向跳动",
            sourceRef = "LocalModelsScreen.kt:266-284, 468-486, 515-574",
            beforeSummary = "点下载同时触发顶部加进度 + 底部消空态，弹窗裸切",
            afterSummary = "进度/空态包 expandIn+animateItem；弹窗切 fadeThrough",
            beforeContent = {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("SenseVoice · 正在下载 38%（顶部硬插进度条）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1) // wrap-check: allow 原型单行样例
                        LinearProgressIndicator(progress = { 0.38f }, modifier = Modifier.fillMaxWidth())
                    }
                }
                Text("底部「暂无本地模型」卡片同时瞬消导致列表上弹", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1)
            },
            afterContent = {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("SenseVoice · 正在下载 38%（平滑展开进列表）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.infinityDomainColors.success, maxLines = 1) // wrap-check: allow 原型单行样例
                        LinearProgressIndicator(progress = { 0.38f }, modifier = Modifier.fillMaxWidth())
                    }
                }
                Text("空态卡 shrinkOut 退场 + 槽位弹层 fadeThrough", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.infinityDomainColors.success, maxLines = 1)
            },
        )
        ComparisonBlock(
            code = "E13",
            title = "MediaOperationState（7 页共用）+ Comfy 鉴权 + 计费编辑",
            sourceRef = "MediaSettingsComponents.kt:83-90 · ComfySettingsScreens.kt · MediaModelDetailScreen.kt",
            beforeSummary = "7 个页面点检测/保存即下抖整页；计费切换/鉴权裸插",
            afterSummary = "busy 进度条固定顶缘叠槽（零下推）；子段平稳折叠",
            beforeContent = { SampleMediaOperationHeader(busy = true, overlayTopBar = false) },
            afterContent = { SampleMediaOperationHeader(busy = true, overlayTopBar = true) },
        )
        ComparisonBlock(
            code = "E14",
            title = "SkillsScreen 搜索引擎配置与添加 MCP 报错",
            sourceRef = "SkillsScreen.kt:329-335, 617-651",
            beforeSummary = "切 DuckDuckGo / Tavily / SearXNG 硬跳 0–2 张卡",
            afterSummary = "配置区 AnimatedContent(fadeThrough)，MCP 报错 expandIn",
            beforeContent = { SampleSearchEngineConfig(engine = "searx", isAfter = false) },
            afterContent = { SampleSearchEngineConfig(engine = "searx", isAfter = true) },
        )
        NarrowLightVerificationFrame(title = "360dp 窄屏 · 浅色主题 · 1.3x 大字号验证 (E3 / E7 / E13)") {
            SampleKgOrderHeader(hasManual = true, persistentButton = true)
            SampleCanvasHistoryBar(showDuplicateNoticeRow = false)
            SampleMediaOperationHeader(busy = true, overlayTopBar = true)
        }
    }
}
