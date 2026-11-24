package com.promenar.infinity.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.InfinityConfirmDialogCard
import com.promenar.infinity.ui.common.InfinityInspectorTopBar
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityMetaSeparator
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.theme.ComposerSurfaceStyle
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityIceBlueSeed
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinityMotionStyles
import com.promenar.infinity.ui.theme.InfinityObsidianSolidContainer
import com.promenar.infinity.ui.theme.InfinitySchemeStyle
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.infinityDomainColors

/*
 * 任务面板持久化 + 手动清空记录 & 侧边栏移除「远程」页签（方案 1）高保真 Compose 校准原型（v1，仅 debug 源码）。
 *
 * 确认范围：
 * 1. RunHistoryPersistedWideDarkPreview：宽屏 360dp 深色 · 生成结束/重进后的持久历史态 + 「已结束 · 22」行尾紧凑胶囊「清空记录」+ 4 常驻页签底栏（无「远程」）
 * 2. RunHistoryRunningAndClearWideLightPreview：宽屏 360dp 浅色 · 运行中 + 已结束并存态，核验「清空记录」仅归属已结束分组、不影响运行中任务
 * 3. RunHistoryClearConfirmDialogPreview：黑曜石危险确认弹窗（InfinityConfirmDialogCard），明确范围与不可撤销提示
 * 4. SlimSidebarStripAndPortraitDrawerPreview：收起后右侧边缘条（4 常驻图标）+ 竖屏 360dp 93% 熏黑抽屉底栏（4 常驻页签）
 * 5. RunHistoryLargeFontNarrowPreview：360dp 窄宽 + 1.3× 大字号压测，核验按钮、角标、列表项与底栏零裁切
 */

private data class ProtoJobItem(
    val title: String,
    val runner: String,
    val duration: String,
    val exitCode: Int = 0,
    val expanded: Boolean = false,
    val detailMeta: List<String> = emptyList(),
)

private val SampleFinishedJobs = listOf(
    ProtoJobItem(
        title = "验证代理连通性",
        runner = "Linux",
        duration = "1 秒",
        expanded = true,
        detailMeta = listOf("Linux", "已退出（0）· 1 秒"),
    ),
    ProtoJobItem("探测代理环境变量状态", "Linux", "1 秒"),
    ProtoJobItem("清理 0.165.0 解包残留", "Linux", "0 秒"),
    ProtoJobItem("检查 /tmp 中残留的 codex 文件", "Linux", "0 秒"),
    ProtoJobItem("删除旧版 Codex 二进制", "Linux", "0 秒"),
    ProtoJobItem("确认登录服务已退出", "Linux", "1 秒", exitCode = 1),
    ProtoJobItem("Codex exec 登录烟测", "Linux", "8 秒"),
    ProtoJobItem("备份 Codex 登录凭据并校验", "Linux", "0 秒"),
    ProtoJobItem("启动 Codex 登录（本机回调模式）", "Linux", "1 分 13 秒"),
    ProtoJobItem("替换为 Codex 0.171.0 并验证", "Linux", "6 秒"),
)

@Composable
private fun ProtoTheme(dark: Boolean, content: @Composable () -> Unit) {
    InfinityTheme(
        darkTheme = dark,
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
            primaryColor = InfinityIceBlueSeed.value.toLong(),
            schemeStyle = InfinitySchemeStyle.BRIGHT,
            pureBlack = dark,
            motion = InfinityMotionStyles(surface = ComposerSurfaceStyle.FROSTED),
        ),
        content = content,
    )
}

/** 方案 1 精简后的 4 常驻工作台页签（移除 ChatPage.Remote），与生产排序一致。 */
private fun slimPermanentHubEntries(running: Boolean): List<PanelHubEntry> = listOf(
    PanelHubEntry(
        page = ChatPage.Run,
        summary = if (running) "1 个运行中" else "22 个已结束",
        pending = 0,
        running = running,
        available = true,
        onOpen = {},
    ),
    PanelHubEntry(
        page = ChatPage.Explorer,
        summary = "Personal-Archive",
        pending = 0,
        running = false,
        available = true,
        onOpen = {},
    ),
    PanelHubEntry(
        page = ChatPage.Git,
        summary = "main · 47 处改动",
        pending = 0,
        running = false,
        available = true,
        onOpen = {},
    ),
    PanelHubEntry(
        page = ChatPage.Browser,
        summary = null,
        pending = 0,
        running = false,
        available = true,
        onOpen = {},
    ),
)

@Composable
private fun ProtoRunPanelBody(
    isGenerating: Boolean,
    showRunningJob: Boolean,
    finishedTotalCount: Int,
    jobs: List<ProtoJobItem>,
    elapsedClock: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val signalOn = MaterialTheme.infinityDomainColors.signalOn
    Column(Modifier.fillMaxSize()) {
        InfinityInspectorTopBar(
            title = "本次运行",
            meta = {
                if (isGenerating) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(signalOn, CircleShape),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                val summary = listOfNotNull(
                    if (isGenerating) "进行中" else null,
                    if (showRunningJob) "1 个运行中" else null,
                    if (finishedTotalCount > 0) "$finishedTotalCount 个已结束" else null,
                )
                Text(
                    text = summary.joinToString(" $InfinityMetaSeparator "),
                    maxLines = 1,
                    softWrap = false,
                )
                if (elapsedClock != null) {
                    Text(" $InfinityMetaSeparator ", maxLines = 1, softWrap = false)
                    Text(
                        text = elapsedClock,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = InfinityMonospace,
                            fontFeatureSettings = "tnum",
                        ),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            },
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(
                start = InfinitySpacing.Medium,
                end = InfinitySpacing.Medium,
                bottom = InfinitySpacing.Large,
            ),
        ) {
            if (showRunningJob) {
                item(key = "running_header") {
                    Text(
                        text = "运行中 · 1",
                        style = InfinityTypography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            start = InfinitySpacing.XSmall,
                            top = InfinitySpacing.Medium,
                            bottom = InfinitySpacing.Small,
                        ),
                    )
                }
                item(key = "running_card") {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = scheme.surfaceContainer,
                        border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth().padding(bottom = InfinitySpacing.Small),
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                start = InfinitySpacing.Medium,
                                end = InfinitySpacing.Small,
                                top = InfinitySpacing.Small,
                                bottom = InfinitySpacing.Small,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(InfinitySpacing.Medium))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "同步远端分支并检查提交差异",
                                    style = InfinityTypography.bodyMedium,
                                    color = scheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ProtoSmallTag("Linux", scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
                                    Text(
                                        text = "已运行 12 秒",
                                        style = InfinityTypography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                            OutlinedIconButton(
                                onClick = {},
                                border = BorderStroke(0.5.dp, scheme.outline),
                            ) {
                                Icon(Icons.Rounded.Stop, contentDescription = "停止任务", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            item(key = "finished_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = InfinitySpacing.XSmall,
                            top = if (showRunningJob) InfinitySpacing.Small else InfinitySpacing.Small,
                            bottom = InfinitySpacing.XSmall,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                ) {
                    Text(
                        text = "已结束 · $finishedTotalCount",
                        style = InfinityTypography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    AnimatedVisibility(
                        visible = finishedTotalCount > 0,
                        enter = InfinityMotion.expandIn,
                        exit = InfinityMotion.shrinkOut,
                    ) {
                        InfinityPillButton(
                            text = "清空记录",
                            onClick = {},
                            tone = InfinityPillTone.NEUTRAL,
                            icon = Icons.Rounded.DeleteOutline,
                            compact = true,
                        )
                    }
                }
            }
            item(key = "finished_card") {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = scheme.surfaceContainerLow,
                ) {
                    Column {
                        jobs.forEachIndexed { index, job ->
                            if (index > 0) {
                                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
                            }
                            ProtoFinishedJobRow(job)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoFinishedJobRow(job: ProtoJobItem) {
    val scheme = MaterialTheme.colorScheme
    val failed = job.exitCode != 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = {})
            .padding(
                start = InfinitySpacing.Medium,
                end = InfinitySpacing.Medium,
                top = 10.dp,
                bottom = 10.dp,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (failed) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = if (failed) scheme.error else scheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(InfinitySpacing.Medium))
            Text(
                text = job.title,
                style = InfinityTypography.bodyMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(InfinitySpacing.Small))
            if (failed) {
                ProtoSmallTag("退出 ${job.exitCode}", scheme.errorContainer, scheme.onErrorContainer)
            } else {
                Text(
                    text = job.duration,
                    style = InfinityTypography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        if (job.expanded && job.detailMeta.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(start = 18.dp + InfinitySpacing.Medium, top = InfinitySpacing.XSmall),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
            ) {
                InfinityMetaLine(
                    segments = job.detailMeta,
                    style = InfinityTypography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                InfinityPillButton(
                    text = "查看日志",
                    onClick = {},
                    icon = Icons.Rounded.FolderOpen,
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun ProtoSmallTag(text: String, containerColor: Color, contentColor: Color) {
    Surface(shape = RoundedCornerShape(999.dp), color = containerColor) {
        Text(
            text = text,
            style = InfinityTypography.labelSmall,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Preview(name = "1. 宽屏深色 · 持久化历史 + 清空按钮 + 4页签底栏", widthDp = 384, heightDp = 780, locale = "zh-rCN")
@Composable
internal fun RunHistoryPersistedWideDarkPreview() {
    ProtoTheme(dark = true) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(12.dp),
        ) {
            ChatInspectorCard(
                root = ChatPage.Run,
                entries = slimPermanentHubEntries(running = false),
                widened = false,
                onToggleWiden = {},
                onCollapse = {},
                modifier = Modifier.fillMaxSize(),
            ) {
                ProtoRunPanelBody(
                    isGenerating = false,
                    showRunningJob = false,
                    finishedTotalCount = 22,
                    jobs = SampleFinishedJobs,
                )
            }
        }
    }
}

@Preview(name = "2. 宽屏浅色 · 运行中与已结束并存 + 4页签底栏", widthDp = 384, heightDp = 780, locale = "zh-rCN")
@Composable
internal fun RunHistoryRunningAndClearWideLightPreview() {
    ProtoTheme(dark = false) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp),
        ) {
            ChatInspectorCard(
                root = ChatPage.Run,
                entries = slimPermanentHubEntries(running = true),
                widened = false,
                onToggleWiden = {},
                onCollapse = {},
                onLockTouch = {},
                modifier = Modifier.fillMaxSize(),
            ) {
                ProtoRunPanelBody(
                    isGenerating = true,
                    showRunningJob = true,
                    finishedTotalCount = 22,
                    jobs = SampleFinishedJobs.take(7),
                    elapsedClock = "0:12",
                )
            }
        }
    }
}

@Preview(name = "3. 清空记录确认弹窗（黑曜石危险态）", widthDp = 390, heightDp = 380, locale = "zh-rCN")
@Composable
internal fun RunHistoryClearConfirmDialogPreview() {
    ProtoTheme(dark = true) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF060709))
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            InfinityConfirmDialogCard(
                title = "清空已结束记录",
                message = "将清除本会话中 22 条已结束的执行记录与关联日志；1 个运行中任务与聊天记录不受影响。此操作不可撤销。",
                confirmText = "清空记录",
                cancelText = "取消",
                onConfirm = {},
                onCancel = {},
                isDestructive = true,
            )
        }
    }
}

@Preview(name = "4. 精简 4 页签 · 收起边缘条 + 竖屏 93% 抽屉", widthDp = 440, heightDp = 780, locale = "zh-rCN")
@Composable
internal fun SlimSidebarStripAndPortraitDrawerPreview() {
    ProtoTheme(dark = true) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(top = 32.dp),
            ) {
                ChatPortraitObsidianDrawer(
                    root = ChatPage.Run,
                    entries = slimPermanentHubEntries(running = false),
                    onClose = {},
                    modifier = Modifier.fillMaxSize(),
                ) {
                    ProtoRunPanelBody(
                        isGenerating = false,
                        showRunningJob = false,
                        finishedTotalCount = 22,
                        jobs = SampleFinishedJobs.take(8),
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = InfinityObsidianSolidContainer,
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f)),
                ) {
                    Text(
                        text = "边缘条",
                        style = InfinityTypography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                ChatInspectorStrip(
                    entries = slimPermanentHubEntries(running = false),
                    onExpand = {},
                )
            }
        }
    }
}

@Preview(name = "5. 窄宽 360dp × 1.3 倍大字号压测", widthDp = 360, heightDp = 780, fontScale = 1.3f, locale = "zh-rCN")
@Composable
internal fun RunHistoryLargeFontNarrowPreview() {
    ProtoTheme(dark = true) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(8.dp),
        ) {
            ChatInspectorCard(
                root = ChatPage.Run,
                entries = slimPermanentHubEntries(running = true),
                widened = false,
                onToggleWiden = {},
                onCollapse = {},
                modifier = Modifier.fillMaxSize(),
            ) {
                ProtoRunPanelBody(
                    isGenerating = true,
                    showRunningJob = true,
                    finishedTotalCount = 22,
                    jobs = SampleFinishedJobs.take(6),
                    elapsedClock = "0:12",
                )
            }
        }
    }
}
