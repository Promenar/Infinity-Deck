package com.promenar.infinity.ui.chat

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.R
import com.promenar.infinity.data.model.FileChangeSummary
import com.promenar.infinity.data.model.FileChangeType
import com.promenar.infinity.ui.chat.components.RunDockRow
import com.promenar.infinity.ui.chat.components.runDockState
import com.promenar.infinity.ui.chat.diff.fileChangesStyledTitle
import com.promenar.infinity.ui.common.ExecutionMode
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinityPanelTab
import com.promenar.infinity.ui.common.InfinityPanelTabs
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.motion.GenerationMotion
import com.promenar.infinity.ui.common.motion.GenerationMotionPhase
import com.promenar.infinity.ui.common.motion.LocalGenerationMotion
import com.promenar.infinity.ui.production.ProductionStatusChip
import com.promenar.infinity.ui.production.ProductionTone
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.infinityDomainColors

/*
 * 高保真 Compose Preview 原型 v1（三项交互与排版修复）：
 * 1. 问题 3 方案 A：连续编辑多文件「单行原位锚定累加」四帧演进对比（现状 72dp <-> 36dp 跳变 vs 方案 A 恒定 36dp）；
 * 2. 问题 2：审批批准后命令执行中状态（待审批 vs 旧 BUG 黄色停顿+灰发送键 vs 修复后工具执行流光+亮色停止键）；
 * 3. 问题 1：ComfyUI「主机设置」页签修复后顶部与两侧 16dp 标准边距。
 */

private val PrototypeThemePrefs = InfinityThemePreferences(
    mode = InfinityThemeMode.DARK,
    colorSource = InfinityColorSource.INFINITY,
)

@Composable
private fun PrototypeFrameHeader(
    title: String,
    badge: String,
    tone: ProductionTone,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = InfinityTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        ProductionStatusChip(badge, tone)
    }
}

@Composable
private fun PrototypeCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(
            width = if (highlighted) 1.dp else 0.5.dp,
            color = if (highlighted) scheme.primary.copy(alpha = 0.5f) else scheme.outlineVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            content()
        }
    }
}

@Preview(
    name = "1. 方案A · 连续改多文件原位锚定（全程36dp零跳变）",
    widthDp = 412,
    heightDp = 1060,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    locale = "zh-rCN",
)
@Composable
fun PlanAConsecutiveFileEditsPrototype() {
    val now = 1_760_000_012_000L
    val change1 = listOf(FileChangeSummary("ui/chat/ChatViewModel.kt", FileChangeType.MODIFIED, added = 18, removed = 4))
    val change2 = change1 + FileChangeSummary("ui/chat/ChatScreen.kt", FileChangeType.MODIFIED, added = 24, removed = 7)
    val change3 = change2 + FileChangeSummary("ui/settings/ComfySettingsScreens.kt", FileChangeType.MODIFIED, added = 23, removed = 8)

    InfinityTheme(preferences = PrototypeThemePrefs) {
        CompositionLocalProvider(LocalLiveStepNow provides now) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "问题 3 · 方案 A：连续编辑多文件（逐行流式与自动折叠）",
                        style = InfinityTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "无“正在修改文件”前缀；直接以生成动画接真实文件名与异向滚动增删行数，同批多文件逐行常驻，全部改完后平滑折叠为单行汇总。",
                        style = InfinityTypography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // 对比组 0：现状 BUG（改完第 1 个 -> 正在改第 2 个，72dp 骤缩为 36dp 且改动消失）
                    PrototypeCard {
                        PrototypeFrameHeader("现状 · 改完第 1 个、准备改第 2 个", "总高 72dp", ProductionTone.WARNING)
                        CompactStepRow(
                            segments = listOf(fileChangesStyledTitle(change1), "1.4 秒"),
                            icon = Icons.Rounded.CheckCircle,
                            onClick = {},
                        )
                        LiveStatusRow(
                            LiveStatusLine(
                                labelRes = R.string.live_step_preparing_tool,
                                countdown = null,
                                clockSeconds = 4L,
                                badge = null,
                                motionPhase = GenerationMotionPhase.STREAM,
                            ),
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        PrototypeFrameHeader("现状 · 开始改第 2 个文件瞬间", "36dp (-36) · 改动消失", ProductionTone.ERROR)
                        CompactStepRow(
                            segments = listOf("正在修改文件…"),
                            icon = Icons.Rounded.CheckCircle,
                            active = true,
                            onClick = {},
                            leading = { LiveStepLeading(GenerationMotionPhase.TOOL) },
                            trailing = { LiveStepTrailing(now - 5_000L) },
                        )
                    }

                    // 方案 A v2 终态演进（无“正在修改文件”，逐行列出 + 异向滚动计数 + 全部改完折叠汇总）
                    PrototypeCard(highlighted = true) {
                        Text(
                            text = "方案 A 终态（直接显示文件名 · 异向滚动行数 · 全部改完折叠汇总）",
                            style = InfinityTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                        )

                        Spacer(Modifier.height(2.dp))
                        PrototypeFrameHeader("帧 1 · 正在流式写入第 1 个文件", "36dp · 无冗余前缀", ProductionTone.INFO)
                        LiveFileEditBatchRows(
                            rows = listOf(
                                LiveFileEditRowState(
                                    key = "ui/chat/chatviewmodel.kt",
                                    path = "ui/chat/ChatViewModel.kt",
                                    displayName = "ChatViewModel.kt",
                                    added = 14,
                                    removed = 4,
                                    status = LiveFileEditRowStatus.ACTIVE,
                                    motionPhase = GenerationMotionPhase.STREAM,
                                    startedAt = now - 2_000L,
                                ),
                            ),
                            onOpenChange = {},
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        PrototypeFrameHeader("帧 2 · 第 1 个完成，逐行展开第 2 个", "已完成常驻上方", ProductionTone.INFO)
                        LiveFileEditBatchRows(
                            rows = listOf(
                                LiveFileEditRowState(
                                    key = "ui/chat/chatviewmodel.kt",
                                    path = "ui/chat/ChatViewModel.kt",
                                    displayName = "ChatViewModel.kt",
                                    added = 18,
                                    removed = 4,
                                    status = LiveFileEditRowStatus.COMPLETED,
                                    latestChange = change1.first(),
                                ),
                                LiveFileEditRowState(
                                    key = "ui/chat/chatscreen.kt",
                                    path = "ui/chat/ChatScreen.kt",
                                    displayName = "ChatScreen.kt",
                                    added = 19,
                                    removed = 5,
                                    status = LiveFileEditRowStatus.ACTIVE,
                                    motionPhase = GenerationMotionPhase.TOOL,
                                    startedAt = now - 5_000L,
                                ),
                            ),
                            onOpenChange = {},
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        PrototypeFrameHeader("帧 3 · 连续修改第 3 个文件", "逐行累加 · 零闪烁", ProductionTone.INFO)
                        LiveFileEditBatchRows(
                            rows = listOf(
                                LiveFileEditRowState(
                                    key = "ui/chat/chatviewmodel.kt",
                                    path = "ui/chat/ChatViewModel.kt",
                                    displayName = "ChatViewModel.kt",
                                    added = 18,
                                    removed = 4,
                                    status = LiveFileEditRowStatus.COMPLETED,
                                    latestChange = change1.first(),
                                ),
                                LiveFileEditRowState(
                                    key = "ui/chat/chatscreen.kt",
                                    path = "ui/chat/ChatScreen.kt",
                                    displayName = "ChatScreen.kt",
                                    added = 24,
                                    removed = 7,
                                    status = LiveFileEditRowStatus.COMPLETED,
                                    latestChange = change2[1],
                                ),
                                LiveFileEditRowState(
                                    key = "ui/settings/comfysettingsscreens.kt",
                                    path = "ui/settings/ComfySettingsScreens.kt",
                                    displayName = "ComfySettingsScreens.kt",
                                    added = 16,
                                    removed = 6,
                                    status = LiveFileEditRowStatus.ACTIVE,
                                    motionPhase = GenerationMotionPhase.TOOL,
                                    startedAt = now - 9_000L,
                                ),
                            ),
                            onOpenChange = {},
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        PrototypeFrameHeader("帧 4 · 全部改完，平滑折叠为单行汇总", "36dp · 原位收口", ProductionTone.SUCCESS)
                        CompactStepRow(
                            segments = listOf(fileChangesStyledTitle(change3), "11.2 秒"),
                            icon = Icons.Rounded.CheckCircle,
                            active = false,
                            onClick = {},
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "已完成 3 个文件的修改，正在运行单元测试验证…",
                            style = InfinityTypography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Preview(
    name = "2. 问题2 · 批准审批后命令执行中（流光与停止按钮对照）",
    widthDp = 412,
    heightDp = 880,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    locale = "zh-rCN",
)
@Composable
fun PostApprovalExecutionStatePrototype() {
    val now = 1_760_000_020_000L
    InfinityTheme(preferences = PrototypeThemePrefs) {
        CompositionLocalProvider(LocalLiveStepNow provides now) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = "问题 2 · 审批卡批准后的生成动效与停止按钮",
                        style = InfinityTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    val baseMotion = LocalGenerationMotion.current
                    // 状态 1：等待审批（保持不变）
                    val waitingMotion = GenerationMotion.pinned(GenerationMotionPhase.APPROVAL, baseMotion)
                    val waitingDock = runDockState(
                        plan = null,
                        isGenerating = false,
                        pendingApprovals = 1,
                        runningJobs = 0,
                        enabledSchedules = 0,
                        boardTitle = null,
                        elapsedMillis = null,
                    )
                    PrototypeFrameHeader("1. 弹出审批卡待用户确认（保持不变）", "黄色慢闪 · 盾牌 1", ProductionTone.WARNING)
                    CompositionLocalProvider(LocalGenerationMotion provides waitingMotion) {
                        ChatInputBar(
                            text = "",
                            onTextChange = {},
                            onSend = {},
                            status = GenerationStatus.IDLE,
                            isGenerating = false,
                            modelName = "DeepSeek V4.1",
                            executionMode = ExecutionMode.WORKSPACE,
                            pendingApprovals = 1,
                            runDockVisible = waitingDock != null,
                            runDock = { waitingDock?.let { RunDockRow(it, onOpen = {}) } },
                        )
                    }

                    // 状态 2：现状 BUG（已点批准，命令仍在跑）
                    PrototypeFrameHeader("2. 现状 BUG · 已批准、命令运行中", "仍黄闪 · 灰发送键", ProductionTone.ERROR)
                    CompositionLocalProvider(LocalGenerationMotion provides waitingMotion) {
                        ChatInputBar(
                            text = "",
                            onTextChange = {},
                            onSend = {},
                            status = GenerationStatus.IDLE,
                            isGenerating = false,
                            modelName = "DeepSeek V4.1",
                            executionMode = ExecutionMode.WORKSPACE,
                            pendingApprovals = 0,
                            runDockVisible = false,
                            runDock = {},
                        )
                    }

                    // 状态 3：修复后（已点批准，命令仍在跑）
                    val toolMotion = GenerationMotion.pinned(GenerationMotionPhase.TOOL, baseMotion)
                    val runningDock = runDockState(
                        plan = null,
                        isGenerating = true,
                        pendingApprovals = 0,
                        runningJobs = 0,
                        enabledSchedules = 0,
                        boardTitle = null,
                        elapsedMillis = 6_000L,
                    )
                    PrototypeFrameHeader("3. 修复后 · 已批准、命令运行中", "工具执行态 · 停止按钮", ProductionTone.SUCCESS)
                    CompositionLocalProvider(LocalGenerationMotion provides toolMotion) {
                        ChatInputBar(
                            text = "",
                            onTextChange = {},
                            onSend = {},
                            status = GenerationStatus.THINKING,
                            isGenerating = true,
                            modelName = "DeepSeek V4.1",
                            executionMode = ExecutionMode.WORKSPACE,
                            pendingApprovals = 0,
                            runDockVisible = runningDock != null,
                            runDock = { runningDock?.let { RunDockRow(it, onOpen = {}, startedAt = System.currentTimeMillis() - 6_000L) } },
                        )
                    }
                }
            }
        }
    }
}

private enum class HostTabPreview { WORKFLOWS, SUBMITTERS, HOST_CONFIG }

@Preview(
    name = "3. 问题1 · ComfyUI 主机设置页签边距修复后",
    widthDp = 412,
    heightDp = 620,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    locale = "zh-rCN",
)
@Composable
fun ComfyHostSettingsTabPaddingPrototype() {
    InfinityTheme(preferences = PrototypeThemePrefs) {
        val scheme = MaterialTheme.colorScheme
        InfinitySettingsPageLayout(
            title = "MAIN",
            onBack = {},
            horizontalContentPadding = 0.dp,
            topContentPadding = 0.dp,
            actions = {
                Box(Modifier.padding(end = 12.dp)) {
                    ProductionStatusChip("在线", ProductionTone.SUCCESS)
                }
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()),
            ) {
                InfinityPanelTabs(
                    tabs = listOf(
                        InfinityPanelTab(HostTabPreview.WORKFLOWS, "工作流", count = "28"),
                        InfinityPanelTab(HostTabPreview.SUBMITTERS, "提交器脚本", count = "12"),
                        InfinityPanelTab(HostTabPreview.HOST_CONFIG, "主机设置"),
                    ),
                    selected = HostTabPreview.HOST_CONFIG,
                    onSelect = {},
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = 16.dp),
                ) {
                    InfinitySettingsSection(title = "连接") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("地址", style = InfinityTypography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.width(84.dp))
                            Text(
                                "http://100.106.180.58:8188",
                                style = InfinityTypography.bodyMedium.copy(fontFamily = InfinityMonospace, fontSize = 13.sp),
                                color = scheme.onSurface,
                            )
                        }
                        InfinityGroupDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("鉴权方式", style = InfinityTypography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.width(84.dp))
                            Text("无", style = InfinityTypography.bodyMedium, color = scheme.onSurface)
                        }
                        InfinityGroupDivider()
                        InfinitySettingsItem(
                            icon = Icons.Rounded.NetworkCheck,
                            title = "检测连接",
                            onClick = {},
                            trailingContent = { ProductionStatusChip("在线", ProductionTone.SUCCESS) },
                        )
                    }
                    InfinitySettingsSection(title = "主机") {
                        InfinitySettingsItem(
                            icon = Icons.Rounded.Edit,
                            title = "编辑主机",
                            onClick = {},
                            showChevron = true,
                        )
                        InfinityGroupDivider()
                        InfinitySettingsItem(
                            icon = Icons.Rounded.Delete,
                            title = "删除",
                            onClick = {},
                            titleColor = MaterialTheme.infinityDomainColors.ragError,
                        )
                    }
                }
            }
        }
    }
}
