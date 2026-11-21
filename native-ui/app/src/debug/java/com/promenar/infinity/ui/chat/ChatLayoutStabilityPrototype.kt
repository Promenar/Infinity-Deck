package com.promenar.infinity.ui.chat

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PendingActions
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WorkHistory
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.ComparisonBlock
import com.promenar.infinity.ui.common.InfinityDisclosureRow
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.PrototypeBoardHeader
import com.promenar.infinity.ui.common.PrototypeBoardSurface
import com.promenar.infinity.ui.common.PrototypeChip
import com.promenar.infinity.ui.common.PrototypeChipTone
import com.promenar.infinity.ui.common.StateTransitionBanner
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors

/*
 * 全站「非常驻 UI 与布局跳变」治理 — 批次 2（ui/chat/ B1–B11，3 张看板）高保真对比原型。
 * 隔离在 debug 源集，供 Android Compose Preview 审阅，不修改 src/main/ 正式代码。
 */

// =====================================================================
// B1 + B2: ChatInspector 底栏 TouchLock 与页签切换
// =====================================================================

private data class InspectorSampleTab(val label: String, val icon: ImageVector)

private val SampleInspectorTabs = listOf(
    InspectorSampleTab("任务", Icons.Rounded.WorkHistory),
    InspectorSampleTab("文件", Icons.Rounded.Folder),
    InspectorSampleTab("Git", Icons.Rounded.AccountTree),
    InspectorSampleTab("浏览器", Icons.Rounded.Language),
    InspectorSampleTab("视频", Icons.Rounded.Movie),
)

@Composable
private fun SampleInspectorBottomBar(
    generating: Boolean,
    reserveFixedLockSlot: Boolean,
    selectedIndex: Int,
    unifiedAnimatedTab: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SampleInspectorTabs.forEachIndexed { idx, tab ->
                    val selected = idx == selectedIndex
                    val bg = if (selected) scheme.secondaryContainer else scheme.surfaceContainerHighest.copy(alpha = 0.4f)
                    val fg = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant
                    if (unifiedAnimatedTab) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = bg,
                            modifier = Modifier.height(40.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = if (selected) 10.dp else 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(if (selected) 5.dp else 0.dp),
                            ) {
                                Icon(tab.icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = fg)
                                if (selected) {
                                    Text(
                                        text = tab.label,
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = fg,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            }
                        }
                    } else {
                        if (selected) {
                            Surface(shape = RoundedCornerShape(12.dp), color = bg, modifier = Modifier.height(40.dp)) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Icon(tab.icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = fg)
                                    Text(tab.label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1, softWrap = false)
                                }
                            }
                        } else {
                            Surface(shape = RoundedCornerShape(12.dp), color = bg, modifier = Modifier.size(40.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(tab.icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = fg)
                                }
                            }
                        }
                    }
                }
            }
            if (reserveFixedLockSlot) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .alpha(if (generating) 1f else 0f),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Lock, contentDescription = "锁定触摸", modifier = Modifier.size(16.dp), tint = scheme.primary)
                }
            } else if (generating) {
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Lock, contentDescription = "锁定触摸", modifier = Modifier.size(16.dp), tint = scheme.primary)
                }
            }
        }
    }
}

// =====================================================================
// B3: ChatInspectorRunTab StartupNotice
// =====================================================================

@Composable
private fun SampleRunTabNotice(hasPending: Boolean, afterTag: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hasPending) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = scheme.primaryContainer.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Rounded.PendingActions, contentDescription = null, modifier = Modifier.size(16.dp), tint = scheme.onPrimaryContainer)
                        Column(Modifier.weight(1f)) {
                            Text("2 项操作等待批准", style = MaterialTheme.typography.labelMedium, color = scheme.onPrimaryContainer, maxLines = 1)
                            Text("批准卡片在输入框上方", style = MaterialTheme.typography.labelSmall, color = scheme.onPrimaryContainer.copy(alpha = 0.8f), maxLines = 1)
                        }
                        PrototypeChip(if (afterTag) "平滑收起" else "跳到审批", PrototypeChipTone.INFO)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前没有运行中的任务", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

// =====================================================================
// B5: ChatTopBar 检视器图钉按钮
// =====================================================================

@Composable
private fun SampleChatTopBar(pinnedVisible: Boolean, reserveSlot: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp), tint = scheme.onSurface)
            Column(Modifier.weight(1f)) {
                Text("重构网络超时与离线缓存", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                InfinityMetaLine(segments = listOf("Nexara", "main"), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            if (reserveSlot) {
                Box(
                    modifier = Modifier.size(36.dp).alpha(if (pinnedVisible) 1f else 0f),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.PushPin, contentDescription = "固定检视器", modifier = Modifier.size(18.dp), tint = scheme.primary)
                }
            } else if (pinnedVisible) {
                Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PushPin, contentDescription = "固定检视器", modifier = Modifier.size(18.dp), tint = scheme.primary)
                }
            }
            Icon(Icons.Rounded.Lock, contentDescription = "锁定触摸", modifier = Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
            Icon(Icons.Rounded.MoreVert, contentDescription = null, modifier = Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
        }
    }
}

// =====================================================================
// B4 + B11: ContextCompactionFeedback + SummaryCard
// =====================================================================

@Composable
private fun SampleCompactionCard(
    compressing: Boolean,
    fixedLayout: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fixedLayout) {
                    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                        if (compressing) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(scheme.primary))
                        } else {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp), tint = domain.success)
                        }
                    }
                } else {
                    if (compressing) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(scheme.primary))
                    } else {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp), tint = domain.success)
                    }
                }
                Text("上下文压缩", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 1)
                PrototypeChip(if (compressing) "压缩中" else "完成", if (compressing) PrototypeChipTone.INFO else PrototypeChipTone.SUCCESS)
                Spacer(Modifier.weight(1f))
                if (fixedLayout) {
                    Box(
                        modifier = Modifier.size(32.dp).alpha(if (compressing) 0f else 1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭", modifier = Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                    }
                }
            }
            if (compressing) {
                LinearProgressIndicator(progress = { 0.62f }, modifier = Modifier.fillMaxWidth())
            } else {
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.3f))
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("查看结果", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                    Icon(Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp), tint = scheme.primary)
                }
            }
            if (!fixedLayout && !compressing) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = {}, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// =====================================================================
// B6: RunDetailHeader 与 RunPlanFooter
// =====================================================================

@Composable
private fun SampleRunDetailSheet(completed: Boolean, hasMenu: Boolean, unifiedPadding: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            val endPad = if (unifiedPadding || hasMenu) 4.dp else 14.dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(start = 14.dp, end = endPad, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("本次运行", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1)
                PrototypeChip(if (completed) "已完成" else "进行中", if (completed) PrototypeChipTone.SUCCESS else PrototypeChipTone.INFO)
                if (unifiedPadding) {
                    Box(Modifier.size(36.dp).alpha(if (hasMenu) 1f else 0f), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "更多操作", modifier = Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                    }
                } else if (hasMenu) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "更多操作", modifier = Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                    }
                }
            }
            InfinityMetaLine(
                segments = listOf("第 3 / 4 步", "1 个后台任务运行中"),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
            if (!completed) {
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.35f))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    InfinityPillButton("标记已完成", onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true, modifier = Modifier.weight(1f))
                    InfinityPillButton("继续处理", onClick = {}, tone = InfinityPillTone.PRIMARY, compact = true, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// =====================================================================
// B7: BackgroundJobRow
// =====================================================================

@Composable
private fun SampleBackgroundJobRow(running: Boolean, expanded: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.infinityDomainColors.success)
                }
                Column(Modifier.weight(1f)) {
                    Text("gradle test --offline", style = MaterialTheme.typography.bodySmall, color = scheme.onSurface, maxLines = 1)
                    Text(if (running) "已运行 14s" else "退出码 0 · 用时 18s", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1) // wrap-check: allow 原型单行样例
                }
                if (running) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(scheme.secondaryContainer), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Stop, contentDescription = "停止任务", modifier = Modifier.size(16.dp), tint = scheme.onSecondaryContainer)
                    }
                }
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
            }
            if (expanded) {
                Surface(shape = RoundedCornerShape(8.dp), color = scheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("> :app:testUnitTest PASSED", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(12.dp), tint = scheme.primary)
                            Text("查看日志", style = MaterialTheme.typography.labelSmall, color = scheme.primary, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================
// B8: SubagentViews 组卡 + 行内停止 + 详情底栏
// =====================================================================

@Composable
private fun SampleSubagentGroupCard(waiting: Boolean, fixedSingleLineMeta: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2 个子代理并行", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 1)
                if (fixedSingleLineMeta || !waiting) {
                    Text(
                        text = if (waiting) "主会话等待中 · 剩 18s" else "后台运行", // wrap-check: allow 原型单行样例
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (waiting) {
                    PrototypeChip("转入后台", PrototypeChipTone.INFO)
                }
            }
            if (!fixedSingleLineMeta && waiting) {
                Text("主会话等待中 · 剩 18s", style = MaterialTheme.typography.labelSmall, color = scheme.primary, maxLines = 1) // wrap-check: allow 原型单行样例
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Terminal, contentDescription = null, modifier = Modifier.size(15.dp), tint = scheme.primary)
                Text("Codebase Audit · read_file", style = MaterialTheme.typography.bodySmall, color = scheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1) // wrap-check: allow 原型单行样例
                if (waiting) {
                    Icon(Icons.Rounded.StopCircle, contentDescription = "停止", modifier = Modifier.size(18.dp), tint = scheme.error)
                }
            }
        }
    }
}

// =====================================================================
// B9 + B10: ToolStepCard 参数展开 + ToolLiveOutputPanel 停止横跳
// =====================================================================

@Composable
private fun SampleToolArgumentsCard(expanded: Boolean, afterSmooth: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.Build, contentDescription = null, modifier = Modifier.size(15.dp), tint = scheme.primary)
                Text("run_command · gradle", style = MaterialTheme.typography.labelMedium, color = scheme.onSurface, maxLines = 1) // wrap-check: allow 原型单行样例
            }
            if (expanded) {
                Text("CommandLine: ./gradlew :app:test\nWaitMsBeforeAsync: 10000", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            } else {
                Text("CommandLine=./gradlew :app:test", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
            }
            InfinityDisclosureRow(
                text = if (expanded) "收起参数" else "展开参数",
                expanded = expanded,
                onClick = {},
            )
            if (afterSmooth) {
                Text("fadeThrough 平滑切高 + 箭头旋转 180°", style = MaterialTheme.typography.labelSmall, color = scheme.primary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SampleLiveOutputHeader(stopRequested: Boolean, fixed48dpHeight: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (fixed48dpHeight || stopRequested) 48.dp else 36.dp)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Rounded.Code, contentDescription = null, modifier = Modifier.size(16.dp), tint = scheme.primary)
            Text("实时输出", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1)
            if (!stopRequested) {
                PrototypeChip("转入后台", PrototypeChipTone.NEUTRAL)
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.StopCircle, contentDescription = "停止", modifier = Modifier.size(18.dp), tint = scheme.error)
                }
            } else {
                Text("正在停止…", style = MaterialTheme.typography.labelMedium, color = scheme.error, maxLines = 1)
            }
        }
    }
}

// =====================================================================
// 批次 2 三张 Compose Preview 看板
// =====================================================================

@Preview(name = "B2-1 检视器与顶栏跳变对比 (深色)", widthDp = 820, heightDp = 1560, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun chatBatch2InspectorAndTopBarPreview() {
    PrototypeBoardSurface(dark = true) {
        PrototypeBoardHeader(
            batchBadge = "批次 2 · 1/3",
            title = "聊天工作区 ui/chat/ · 检视器底栏、提醒卡与顶栏 (B1/B2/B3/B5)",
            subtitle = "左：现状无过渡硬插与宽/高跳变 ｜ 右：建议方案",
        )
        ComparisonBlock(
            code = "B1",
            title = "ChatInspector 底栏与右缘条 TouchLockButton 插拔",
            sourceRef = "ChatInspector.kt:192, 408 · ChatScreen.kt:1826, 1938",
            beforeSummary = "生成启停直接插删 48dp 钮，挤压横滑页签宽度",
            afterSummary = "固定 48dp 槽淡隐（或水平收放），页签零抖动",
            beforeContent = {
                StateTransitionBanner("空闲态：无锁屏钮，页签占满行宽")
                SampleInspectorBottomBar(generating = false, reserveFixedLockSlot = false, selectedIndex = 0, unifiedAnimatedTab = false)
                StateTransitionBanner("生成中：硬插锁屏钮，左邻页签骤缩")
                SampleInspectorBottomBar(generating = true, reserveFixedLockSlot = false, selectedIndex = 0, unifiedAnimatedTab = false)
            },
            afterContent = {
                StateTransitionBanner("空闲态：保留固定锁定槽（透明禁用）")
                SampleInspectorBottomBar(generating = false, reserveFixedLockSlot = true, selectedIndex = 0, unifiedAnimatedTab = true)
                StateTransitionBanner("生成中：原地淡显启用，页签位置零位移")
                SampleInspectorBottomBar(generating = true, reserveFixedLockSlot = true, selectedIndex = 0, unifiedAnimatedTab = true)
            },
        )
        ComparisonBlock(
            code = "B2",
            title = "ChatInspector 底栏页签切换（核对 v0.69.4 残留缺口）",
            sourceRef = "ChatInspector.kt:232-345 · ChatPanelHubUi.kt:88-97",
            beforeSummary = "v0.69.4 仅补滚动，单页签仍硬切 46dp 图标/胶囊",
            afterSummary = "统一 Row + 文字水平展开 + 底色平滑过渡",
            beforeContent = {
                StateTransitionBanner("选中「任务」：其余 4 项为固定纯图标方框")
                SampleInspectorBottomBar(generating = false, reserveFixedLockSlot = false, selectedIndex = 0, unifiedAnimatedTab = false)
                StateTransitionBanner("切至「视频」：邻近图标瞬跳，无文字展开动画")
                SampleInspectorBottomBar(generating = false, reserveFixedLockSlot = false, selectedIndex = 4, unifiedAnimatedTab = false)
            },
            afterContent = {
                StateTransitionBanner("选中「任务」：文字胶囊平滑滑开 + 色彩渐变")
                SampleInspectorBottomBar(generating = false, reserveFixedLockSlot = true, selectedIndex = 0, unifiedAnimatedTab = true)
                StateTransitionBanner("切至「视频」：水平展开动画与轨道滚动同步")
                SampleInspectorBottomBar(generating = false, reserveFixedLockSlot = true, selectedIndex = 4, unifiedAnimatedTab = true)
            },
        )
        ComparisonBlock(
            code = "B3",
            title = "ChatInspectorRunTab 待批准 StartupNotice 提醒卡",
            sourceRef = "ChatInspector.kt:483-494",
            beforeSummary = "审批出现/清零硬插删提醒卡，下方任务区瞬推",
            afterSummary = "AnimatedVisibility + 退场缓存计数防闪 0",
            beforeContent = {
                StateTransitionBanner("有 2 项待批准：顶部硬插整张通知卡")
                SampleRunTabNotice(hasPending = true, afterTag = false)
                StateTransitionBanner("清零后：整张卡瞬间销毁，下方区域骤然上跳")
                SampleRunTabNotice(hasPending = false, afterTag = false)
            },
            afterContent = {
                StateTransitionBanner("有 2 项待批准：平滑下展通知卡")
                SampleRunTabNotice(hasPending = true, afterTag = true)
                StateTransitionBanner("清零后：expandIn/shrinkOut 平稳收起")
                SampleRunTabNotice(hasPending = false, afterTag = true)
            },
        )
        ComparisonBlock(
            code = "B5",
            title = "ChatTopBar 检视器图钉按钮硬插拔",
            sourceRef = "ChatScreen.kt:1036-1040, 2765-2779",
            beforeSummary = "收起检视器卸载 48dp 图钉，标题与状态行横跳",
            afterSummary = "宽屏固定 48dp 图钉槽淡隐禁用（对齐 touchLock）",
            beforeContent = {
                StateTransitionBanner("检视器展开：显示图钉钮")
                SampleChatTopBar(pinnedVisible = true, reserveSlot = false)
                StateTransitionBanner("检视器收起：卸载 48dp 钮，标题宽骤增重排")
                SampleChatTopBar(pinnedVisible = false, reserveSlot = false)
            },
            afterContent = {
                StateTransitionBanner("检视器展开：图钉槽显形可点")
                SampleChatTopBar(pinnedVisible = true, reserveSlot = true)
                StateTransitionBanner("检视器收起：原位透明禁用，标题宽零变化")
                SampleChatTopBar(pinnedVisible = false, reserveSlot = true)
            },
        )
    }
}

@Preview(name = "B2-2 压缩反馈与运行详单对比 (深色)", widthDp = 820, heightDp = 1040, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun chatBatch2CompactionAndRunSheetPreview() {
    PrototypeBoardSurface(dark = true) {
        PrototypeBoardHeader(
            batchBadge = "批次 2 · 2/3",
            title = "聊天工作区 ui/chat/ · 上下文压缩反馈 & 运行详情单 (B4/B11/B6)",
            subtitle = "合并消除底层孤立 48dp 关闭行、左图标 8dp→18dp 推标题与底部双钮硬卸",
        )
        ComparisonBlock(
            code = "B4+B11",
            title = "ContextCompactionFeedback + SummaryCard 完成跳变",
            sourceRef = "ChatScreen.kt:2523-2569 · ChatInlineComponents.kt:634-800",
            beforeSummary = "图标 8→18dp 推标题；硬插查看结果与 48dp 关闭行",
            afterSummary = "左图标固定 18dp；关闭钮入顶栏；进度与结果平滑过渡",
            beforeContent = {
                StateTransitionBanner("压缩中：8dp 蓝点 + 进度条，无关闭按钮")
                SampleCompactionCard(compressing = true, fixedLayout = false)
                StateTransitionBanner("压缩完成：标题右推 + 硬插结果行与底部关闭行")
                SampleCompactionCard(compressing = false, fixedLayout = false)
            },
            afterContent = {
                StateTransitionBanner("压缩中：18dp 居中容器 + 顶栏右侧透明禁用槽")
                SampleCompactionCard(compressing = true, fixedLayout = true)
                StateTransitionBanner("压缩完成：顶栏原位启用关闭 + 平滑切至查看结果")
                SampleCompactionCard(compressing = false, fixedLayout = true)
            },
        )
        ComparisonBlock(
            code = "B6",
            title = "RunDetailHeader 与 RunPlanFooter 完成态跳变",
            sourceRef = "RunDetailSheet.kt:266-331, 509-534",
            beforeSummary = "hasMenu 切边距推首行；完成直接卸载双按钮底栏",
            afterSummary = "统一边距与 48dp 菜单槽；底栏 AnimatedVisibility 收起",
            beforeContent = {
                StateTransitionBanner("进行中（无菜单→有菜单撑高首行；显示底栏）")
                SampleRunDetailSheet(completed = false, hasMenu = true, unifiedPadding = false)
                StateTransitionBanner("计划完成：直接销毁分割线与双按钮（上跳 ~76dp）")
                SampleRunDetailSheet(completed = true, hasMenu = false, unifiedPadding = false)
            },
            afterContent = {
                StateTransitionBanner("进行中：固定首行高与统一边距，无菜单时仅淡隐")
                SampleRunDetailSheet(completed = false, hasMenu = true, unifiedPadding = true)
                StateTransitionBanner("计划完成：底栏 shrinkOut 平稳折叠，首行纹丝不动")
                SampleRunDetailSheet(completed = true, hasMenu = false, unifiedPadding = true)
            },
        )
    }
}

@Preview(name = "B2-3 后台任务、子代理与工具卡对比 (深色)", widthDp = 820, heightDp = 1890, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun chatBatch2JobsSubagentsAndToolsPreview() {
    PrototypeBoardSurface(dark = true) {
        PrototypeBoardHeader(
            batchBadge = "批次 2 · 3/3",
            title = "聊天工作区 ui/chat/ · 后台任务、子代理、工具参数与实时输出 (B7–B10)",
            subtitle = "左：现状无过渡插删与顶栏跳高跳宽 ｜ 右：建议方案",
        )
        ComparisonBlock(
            code = "B7",
            title = "BackgroundJobRow 停止按钮与实时输出展开区",
            sourceRef = "AgentActivity.kt:320-383",
            beforeSummary = "同文件已有标准动画，唯独本行硬插停止钮与展开日志",
            afterSummary = "停止钮水平收起 + 展开区 align RunningJobCard 折叠",
            beforeContent = {
                StateTransitionBanner("运行中（已展开实时日志）")
                SampleBackgroundJobRow(running = true, expanded = true)
                StateTransitionBanner("结束后：停止钮硬卸、传输行与展开区无动画突变")
                SampleBackgroundJobRow(running = false, expanded = false)
            },
            afterContent = {
                StateTransitionBanner("运行中：Aligned AnimatedVisibility 展开实时输出")
                SampleBackgroundJobRow(running = true, expanded = true)
                StateTransitionBanner("结束后：行尾停止钮水平收缩 + animateContentSize")
                SampleBackgroundJobRow(running = false, expanded = false)
            },
        )
        ComparisonBlock(
            code = "B8",
            title = "SubagentViews 组卡「转入后台」与状态跳行",
            sourceRef = "SubagentViews.kt:173-224, 273-278, 640, 791-804",
            beforeSummary = "等待态拆两行+按钮；结束后退回单行，底栏硬卸",
            afterSummary = "状态文案固定首行不跳位；按钮与详情底栏平滑收起",
            beforeContent = {
                StateTransitionBanner("等待态：按钮挤顶行，倒计时拆到第二行占高")
                SampleSubagentGroupCard(waiting = true, fixedSingleLineMeta = false)
                StateTransitionBanner("转入后台后：第二行消失，「后台运行」弹回首行")
                SampleSubagentGroupCard(waiting = false, fixedSingleLineMeta = false)
            },
            afterContent = {
                StateTransitionBanner("等待态：倒计时与后台状态固定同槽，不上下跳位")
                SampleSubagentGroupCard(waiting = true, fixedSingleLineMeta = true)
                StateTransitionBanner("转入后台后：按钮水平收起，行内停止钮与底栏均过渡")
                SampleSubagentGroupCard(waiting = false, fixedSingleLineMeta = true)
            },
        )
        ComparisonBlock(
            code = "B9",
            title = "ToolStepCard 工具参数预览 / 展开切换",
            sourceRef = "ToolStepCard.kt:176-195 · InfinityDisclosureRow.kt:52",
            beforeSummary = "2 行预览 ↔ 字段列表硬切换，折叠箭头图标瞬变",
            afterSummary = "AnimatedContent(fadeThrough) + 箭头 180° 旋转",
            beforeContent = {
                SampleToolArgumentsCard(expanded = false, afterSmooth = false)
                SampleToolArgumentsCard(expanded = true, afterSmooth = false)
            },
            afterContent = {
                SampleToolArgumentsCard(expanded = false, afterSmooth = true)
                SampleToolArgumentsCard(expanded = true, afterSmooth = true)
            },
        )
        ComparisonBlock(
            code = "B10",
            title = "ToolLiveOutputPanel 点击停止后顶栏高度与宽度跳变",
            sourceRef = "ToolLiveOutputPanel.kt:84-121",
            beforeSummary = "顶栏 36↔48dp 跳高；删「转入后台」换更宽红字横跳",
            afterSummary = "固定 48dp 顶栏高；转入后台水平收起，停止态淡切",
            beforeContent = {
                StateTransitionBanner("运行中：「转入后台」+ 图标钮")
                SampleLiveOutputHeader(stopRequested = false, fixed48dpHeight = false)
                StateTransitionBanner("已请求停止：按钮硬卸，突变「正在停止…」推挤左标题")
                SampleLiveOutputHeader(stopRequested = true, fixed48dpHeight = false)
            },
            afterContent = {
                StateTransitionBanner("运行中：固定 48dp 顶栏，行高全程零变化")
                SampleLiveOutputHeader(stopRequested = false, fixed48dpHeight = true)
                StateTransitionBanner("已请求停止：转入后台平滑收缩，停止态原地淡切")
                SampleLiveOutputHeader(stopRequested = true, fixed48dpHeight = true)
            },
        )
    }
}
