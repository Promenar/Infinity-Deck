package com.promenar.infinity.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LocalLibrary
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Start
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySearchBar
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinityTabHeaderCircleAction
import com.promenar.infinity.ui.hub.HubListPaneWidth
import com.promenar.infinity.ui.production.ProductionStatusChip
import com.promenar.infinity.ui.production.ProductionTone
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityObsidianDialogChip
import com.promenar.infinity.ui.theme.InfinityObsidianDialogContainer
import com.promenar.infinity.ui.theme.InfinityPureWhite
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.breakableIdentifier
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.theme.onSelectionIndicator
import com.promenar.infinity.ui.theme.selectionIndicator

/*
 * 资料库三域认知架构重构 + 完整 SFTP 远程挂载 + 专用传输管理 高保真原型（v1，仅存于 debug 源码）
 *
 * 覆盖范围：
 * 1. 资料库三域认知体系（折叠屏双栏 & 外屏单栏 360dp / 1.3x 字体 · 深浅色）
 *    - 域一 · 知识与记忆（概览 / 知识文档 / 主动记忆 / 对话片段 / 知识图谱）
 *    - 域二 · 本机工作区与文件（工作区与项目 / 会话文件 / Linux 主目录 / 本机挂载目录）
 *    - 域三 · 远程存储（SFTP）（远程挂载点 / 传输管理入口 / 添加远程目录）
 * 2. 添加 / 编辑 SFTP 远程挂载面板（复用设置 → SSH 主机、远端路径浏览、别名、默认只读/可读写归档、连通测试）
 * 3. SFTP 远程目录浏览与分层预览（<=8 MB 自动缓存预览 vs >8 MB 远程大文件按需拉取详情卡 + 256 MB LRU 缓存治理）
 * 4. 专用传输管理界面（上传/下载/校验/暂停断点续传/完成校验回执/失败断点重试 · 深浅色与窄屏大字号）
 * 5. 会话挂载引用与 Agent 远程归档上传内联审批卡（禁止静默覆盖 + 服务端 SHA-256 校验）
 */

@Composable
private fun SftpProtoTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    InfinityTheme(
        darkTheme = dark,
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
        content = content,
    )
}

@Composable
private fun ProtoNavGroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = InfinitySpacing.ScreenHorizontal + InfinitySpacing.Medium,
            end = InfinitySpacing.ScreenHorizontal,
            top = InfinitySpacing.Large,
            bottom = InfinitySpacing.XSmall,
        ),
    )
}

@Composable
private fun ProtoNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    selected: Boolean = false,
    badge: String? = null,
    statusDotColor: Color? = null,
    accentAction: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val container = if (selected) scheme.selectionIndicator(scheme.surface) else scheme.surface
    val content = when {
        selected -> scheme.onSelectionIndicator
        accentAction -> scheme.primary
        else -> scheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = InfinitySpacing.ScreenHorizontal)
            .clip(MaterialTheme.shapes.large)
            .background(container)
            .heightIn(min = if (subtitle != null) 56.dp else InfinitySpacing.MinimumTouchTarget)
            .padding(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(InfinitySpacing.RowIconSize))
            if (statusDotColor != null) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(scheme.surface)
                        .padding(1.5.dp)
                        .clip(CircleShape)
                        .background(statusDotColor),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (badge != null) {
            Badge { Text(badge) }
        }
    }
}

@Composable
private fun ProtoTransferHeaderButton(activeCount: Int) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget),
        contentAlignment = Alignment.Center,
    ) {
        BadgedBox(
            badge = {
                if (activeCount > 0) {
                    Badge(containerColor = scheme.primary, contentColor = scheme.onPrimary) {
                        Text(activeCount.toString())
                    }
                }
            },
        ) {
            Surface(
                shape = CircleShape,
                color = scheme.surfaceContainerHigh,
                modifier = Modifier.size(34.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.SwapVert,
                        contentDescription = "传输管理",
                        tint = scheme.onSurface,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProtoLibraryHeader(title: String, activeTransfers: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = InfinitySpacing.ScreenHorizontal + InfinitySpacing.XSmall, end = InfinitySpacing.ScreenHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        ProtoTransferHeaderButton(activeTransfers)
        Spacer(Modifier.width(4.dp))
        InfinityTabHeaderCircleAction(
            icon = Icons.Rounded.Add,
            contentDescription = "添加",
            onClick = {},
        )
    }
}

@Composable
private fun ProtoKpiCard(
    value: String,
    label: String,
    detail: String? = null,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.heightIn(min = 86.dp),
    ) {
        Column(Modifier.padding(InfinitySpacing.Large), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (icon != null) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.height(4.dp))
            }
            Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ProtoOverviewCard(
    title: String,
    actionLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(InfinitySpacing.Large), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                if (actionLabel != null) {
                    InfinityPillButton(text = actionLabel, onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true)
                }
            }
            content()
        }
    }
}

// =================================================================================================
// 1. 折叠屏内屏两列：资料库三域认知体系（深色）
// =================================================================================================

@Preview(name = "1. 资料库三域认知体系（折叠屏两列 · 深色）", widthDp = 840, heightDp = 1040, showBackground = true)
@Composable
fun LibraryTwoPane3DomainDarkPreview() {
    SftpProtoTheme(dark = true) {
        val scheme = MaterialTheme.colorScheme
        val domain = MaterialTheme.infinityDomainColors
        Surface(color = scheme.background, modifier = Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                // 左列：三域清晰导航（320dp）
                Column(
                    Modifier
                        .width(HubListPaneWidth)
                        .fillMaxHeight()
                        .background(scheme.surface)
                        .verticalScroll(rememberScrollState()),
                ) {
                    ProtoLibraryHeader("资料", activeTransfers = 2)
                    InfinitySearchBar(
                        value = "",
                        onValueChange = {},
                        onSearch = {},
                        placeholder = "问资料",
                        modifier = Modifier.padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = InfinitySpacing.Small),
                    )

                    // 快捷固定（折叠展示）
                    ProtoNavGroupHeader("已固定")
                    ProtoNavRow(Icons.Rounded.PushPin, "帝国暗面", subtitle = "知识文档")

                    // 域一：知识与记忆
                    ProtoNavGroupHeader("知识与记忆")
                    ProtoNavRow(Icons.Rounded.Dashboard, "概览", selected = true)
                    ProtoNavRow(Icons.Rounded.LocalLibrary, "知识文档", subtitle = "4 篇已索引 · 全部就绪")
                    ProtoNavRow(Icons.Rounded.Psychology, "主动记忆", badge = "3")
                    ProtoNavRow(Icons.Rounded.Forum, "对话片段")
                    ProtoNavRow(Icons.Rounded.Hub, "知识图谱")

                    // 域二：本机工作区与文件
                    ProtoNavGroupHeader("本机工作区与文件")
                    ProtoNavRow(Icons.Rounded.SdStorage, "Personal-Archive", subtitle = "手机存储 · main")
                    ProtoNavRow(Icons.Rounded.SdStorage, "Infinity", subtitle = "手机存储 · /Download/Infinity")
                    ProtoNavRow(Icons.Rounded.FolderShared, "会话文件", subtitle = "活跃会话 · 含 1 个保留目录")
                    ProtoNavRow(Icons.Rounded.Terminal, "Linux 主目录")
                    ProtoNavRow(Icons.Rounded.CreateNewFolder, "管理本机挂载目录", accentAction = true)

                    // 域三：远程存储（SFTP）
                    ProtoNavGroupHeader("远程存储（SFTP）")
                    ProtoNavRow(
                        icon = Icons.Rounded.Cloud,
                        title = "飞牛 NAS · Codex-Assets",
                        subtitle = "FNOS · 可归档 · 在线",
                        statusDotColor = domain.signalOn,
                    )
                    ProtoNavRow(
                        icon = Icons.Rounded.CloudOff,
                        title = "服务器日志归档",
                        subtitle = "main-server · 只读 · 离线",
                        statusDotColor = domain.signalPending,
                    )
                    ProtoNavRow(
                        icon = Icons.Rounded.SwapVert,
                        title = "传输管理",
                        subtitle = "2 项进行中 · 12.6 MB/s",
                        badge = "2",
                    )
                    ProtoNavRow(Icons.Rounded.CloudUpload, "添加远程目录", accentAction = true)
                    Spacer(Modifier.height(InfinitySpacing.Large))
                }

                VerticalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))

                // 右列：概览页（兼顾知识库 + 远程存储与活跃传输态势）
                Column(Modifier.weight(1f).fillMaxHeight().background(scheme.background)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .padding(horizontal = InfinitySpacing.XSmall),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = {}) {
                            Icon(
                                Icons.Rounded.Start,
                                contentDescription = "展开",
                                tint = scheme.onSurface,
                                modifier = Modifier.size(24.dp).graphicsLayer { scaleX = -1f },
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text("概览", style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                    }
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = InfinitySpacing.ScreenHorizontal),
                        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
                    ) {
                        // KPI 2x2
                        Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                                ProtoKpiCard("4", "已索引文档", modifier = Modifier.weight(1f).fillMaxHeight())
                                ProtoKpiCard("15", "主动记忆", detail = "新增 3 条", modifier = Modifier.weight(1f).fillMaxHeight())
                            }
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                                ProtoKpiCard("28", "图谱实体", detail = "64 条关系", modifier = Modifier.weight(1f).fillMaxHeight())
                                ProtoKpiCard("446", "对话片段", modifier = Modifier.weight(1f).fillMaxHeight())
                            }
                        }

                        // 远程存储与活跃传输总览卡（无需跳转即可一眼看清进度）
                        ProtoOverviewCard(title = "远程传输与存储", actionLabel = "管理传输") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                            ) {
                                Box(
                                    Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(domain.infoContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.CloudUpload, null, tint = domain.onInfoContainer, modifier = Modifier.size(20.dp))
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "demo-recording-4k.mp4",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = scheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("48%", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                                    }
                                    LinearProgressIndicator(
                                        progress = { 0.48f },
                                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                                        strokeCap = StrokeCap.Round,
                                        drawStopIndicator = {},
                                    )
                                    InfinityMetaLine(
                                        segments = listOf("上传至 FNOS", "68.4 / 142.8 MB", "12.6 MB/s", "另 1 项排队"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                            }
                            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.35f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                InfinityMetaLine(
                                    segments = listOf("2 个 SFTP 挂载", "1 在线", "预览缓存 38.2 / 256 MB"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        // 待处理 + 最近加入（保留原“查看全部”，折叠了侧栏冗余入口）
                        ProtoOverviewCard(title = "待处理", actionLabel = "查看全部") {
                            Text(
                                "没有需要处理的文档，全部已索引。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant,
                            )
                        }

                        ProtoOverviewCard(title = "最近加入", actionLabel = "查看全部") {
                            ProtoOverviewDocRow("战锤40K同人小说《帝国暗面》1-18章正文及设定讨论.txt", "知识文档 › 帝国暗面 · 554 KB · 11月9日")
                            ProtoOverviewDocRow("全书大纲及规划初稿.txt", "知识文档 › 帝国暗面 · 22 KB · 11月9日")
                        }

                        ProtoOverviewCard(title = "常被引用", actionLabel = "查看全部") {
                            ProtoOverviewDocRow("全书大纲及规划初稿.txt", "被智能体引用 12 次 · 最近于昨天")
                        }
                        Spacer(Modifier.height(InfinitySpacing.Large))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoOverviewDocRow(title: String, meta: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Icon(Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// =================================================================================================
// 2. 外屏单栏 360dp + 1.3x 大字号（浅色压力验证）：三域统一入口
// =================================================================================================

@Preview(name = "2. 外屏单栏三域入口（360dp · 1.3x 大字号 · 浅色）", widthDp = 360, heightDp = 1120, fontScale = 1.3f, showBackground = true)
@Composable
fun LibrarySinglePaneCompactLargeFontLightPreview() {
    SftpProtoTheme(dark = false) {
        val scheme = MaterialTheme.colorScheme
        Surface(color = scheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ProtoLibraryHeader("资料", activeTransfers = 2)
                InfinitySearchBar(
                    value = "",
                    onValueChange = {},
                    onSearch = {},
                    placeholder = "问资料",
                    modifier = Modifier.padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = InfinitySpacing.Small),
                )
                Column(
                    modifier = Modifier.padding(horizontal = InfinitySpacing.ScreenHorizontal),
                    verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                ) {
                    InfinitySettingsSection(title = "知识与记忆") {
                        InfinitySettingsItem(icon = Icons.Rounded.LocalLibrary, title = "知识文档", subtitle = "4 篇已索引 · 全部就绪", onClick = {}, showChevron = true)
                        InfinitySettingsItem(icon = Icons.Rounded.Psychology, title = "主动记忆", subtitle = "15 条记忆 · 新增 3 条", onClick = {}, showChevron = true)
                        InfinitySettingsItem(icon = Icons.Rounded.Forum, title = "对话片段", subtitle = "446 条可检索片段", onClick = {}, showChevron = true)
                        InfinitySettingsItem(icon = Icons.Rounded.Hub, title = "知识图谱", subtitle = "28 实体 · 64 关系", onClick = {}, showChevron = true)
                    }

                    InfinitySettingsSection(title = "本机工作区与文件") {
                        InfinitySettingsItem(icon = Icons.Rounded.SdStorage, title = "Personal-Archive", subtitle = "手机存储 · main", onClick = {}, showChevron = true)
                        InfinitySettingsItem(icon = Icons.Rounded.FolderShared, title = "会话文件", subtitle = "活跃会话与 1 个保留目录", onClick = {}, showChevron = true)
                        InfinitySettingsItem(icon = Icons.Rounded.Terminal, title = "Linux 主目录", subtitle = "/root", onClick = {}, showChevron = true)
                        InfinitySettingsItem(icon = Icons.Rounded.CreateNewFolder, title = "管理本机挂载目录", onClick = {}, showChevron = true)
                    }

                    InfinitySettingsSection(title = "远程存储（SFTP）") {
                        InfinitySettingsItem(icon = Icons.Rounded.Cloud, title = "飞牛 NAS · Codex-Assets", subtitle = "FNOS · 可归档 · 在线", onClick = {}, showChevron = true)
                        InfinitySettingsItem(icon = Icons.Rounded.SwapVert, title = "传输管理", subtitle = "2 项进行中 · 12.6 MB/s", onClick = {}, showChevron = true)
                        InfinitySettingsItem(icon = Icons.Rounded.CloudUpload, title = "添加远程目录", onClick = {}, showChevron = true)
                    }
                }
                Spacer(Modifier.height(InfinitySpacing.Large))
            }
        }
    }
}

// =================================================================================================
// 3. 添加 / 编辑 SFTP 远程挂载面板（深色）
// =================================================================================================

@Preview(name = "3. 添加 SFTP 远程挂载面板（复用 SSH 主机 · 默认只读）", widthDp = 411, heightDp = 1080, showBackground = true)
@Composable
fun SftpAddMountSheetDarkPreview() {
    SftpProtoTheme(dark = true) {
        val scheme = MaterialTheme.colorScheme
        Surface(color = scheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // Sheet 顶栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {}) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = scheme.onSurface)
                    }
                    Text("添加远程目录（SFTP）", style = MaterialTheme.typography.titleLarge, color = scheme.onSurface, modifier = Modifier.weight(1f))
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = InfinitySpacing.ScreenHorizontal),
                    verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
                ) {
                    // 1. 选择 SSH 主机（直接复用设置 -> SSH）
                    InfinitySettingsSection(
                        title = "1. 选择 SSH 主机",
                        footer = "复用「设置 → SSH」中的主机指纹、硬件钥匙串私钥与内置 Tailscale 通道，不单独保存密码。",
                        typographicFooter = true,
                    ) {
                        ProtoSshHostOptionRow(
                            alias = "FNOS",
                            endpoint = "promenar@100.88.12.4:22",
                            tag = "Tailnet · 密钥已钉扎",
                            selected = true,
                        )
                        InfinityGroupDivider()
                        ProtoSshHostOptionRow(
                            alias = "MAIN",
                            endpoint = "promenar@100.106.180.58:22",
                            tag = "Tailnet · 密钥已钉扎",
                            selected = false,
                        )
                    }

                    // 2. 远端根目录与别名
                    Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("2. 远端路径与挂载别名", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            InfinityPillButton(
                                text = "浏览远端目录",
                                icon = Icons.Rounded.FolderOpen,
                                onClick = {},
                                tone = InfinityPillTone.NEUTRAL,
                                compact = true,
                            )
                        }
                        OutlinedTextField(
                            value = "/vol1/1000/Codex-Assets",
                            onValueChange = {},
                            label = { Text("远端绝对路径") },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = InfinityMonospace),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = "personal-archive-assets",
                            onValueChange = {},
                            label = { Text("挂载别名") },
                            supportingText = { Text("会话内路径：/mnt/personal-archive-assets/") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // 3. 权限档位
                    InfinitySettingsSection(
                        title = "3. 访问权限（新建默认只读）",
                        footer = "可读写归档允许手动上传、建目录与智能体归档；智能体写入仍需逐次内联审批，且默认拒绝覆盖已有不同文件。",
                        typographicFooter = true,
                    ) {
                        ProtoPermissionModeRow(
                            icon = Icons.Rounded.Lock,
                            title = "只读（推荐默认）",
                            subtitle = "仅允许浏览、≤8 MB 缓存预览与按需下载到工作区",
                            selected = false,
                        )
                        InfinityGroupDivider()
                        ProtoPermissionModeRow(
                            icon = Icons.Rounded.LockOpen,
                            title = "可读写归档",
                            subtitle = "额外支持原子上传归档、服务端 SHA-256 校验与目录管理",
                            selected = true,
                        )
                    }

                    // 4. 连通性探测结果卡片
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.infinityDomainColors.successContainer.copy(alpha = 0.35f),
                        border = BorderStroke(0.75.dp, MaterialTheme.infinityDomainColors.success.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(InfinitySpacing.Medium),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                        ) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.infinityDomainColors.success, modifier = Modifier.size(20.dp))
                            Column(Modifier.weight(1f)) {
                                Text("远端目录可用 · 延迟 18 ms", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
                                Text(
                                    "已检测：目录可写 · 支持 hardlink 原子发布 · 支持服务端 sha256sum",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(InfinitySpacing.Small))
                }

                // 底部操作胶囊行
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.35f))
                Box(Modifier.fillMaxWidth().padding(InfinitySpacing.ScreenHorizontal)) {
                    InfinityPillRow {
                        InfinityPillButton(text = "测试连接", icon = Icons.Rounded.Refresh, onClick = {}, tone = InfinityPillTone.NEUTRAL)
                        InfinityPillButton(text = "保存挂载", icon = Icons.Rounded.CloudDone, onClick = {}, tone = InfinityPillTone.PRIMARY)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoSshHostOptionRow(alias: String, endpoint: String, tag: String, selected: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Icon(Icons.Rounded.Dns, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(alias, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                ProductionStatusChip(tag, ProductionTone.INFO)
            }
            Text(
                endpoint,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RadioButton(selected = selected, onClick = {})
    }
}

@Composable
private fun ProtoPermissionModeRow(icon: ImageVector, title: String, subtitle: String, selected: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Icon(
            icon,
            null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RadioButton(selected = selected, onClick = {})
    }
}

// =================================================================================================
// 4. SFTP 远程目录浏览 + >8 MB 大文件按需详情页（折叠屏双栏 · 深色）
// =================================================================================================

@OptIn(ExperimentalLayoutApi::class)
@Preview(name = "4. SFTP 远程目录与大文件按需详情（双栏 · 缓存治理）", widthDp = 840, heightDp = 760, showBackground = true)
@Composable
fun SftpRemoteBrowserAndLargeFileDetailPreview() {
    SftpProtoTheme(dark = true) {
        val scheme = MaterialTheme.colorScheme
        val domain = MaterialTheme.infinityDomainColors
        Surface(color = scheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // 顶栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .padding(horizontal = InfinitySpacing.XSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {}) {
                        Icon(Icons.Rounded.Start, contentDescription = "展开", tint = scheme.onSurface, modifier = Modifier.graphicsLayer { scaleX = -1f })
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("飞牛 NAS · Codex-Assets", style = MaterialTheme.typography.titleMedium, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            ProductionStatusChip("SFTP · 可归档", ProductionTone.SUCCESS)
                        }
                        InfinityMetaLine(
                            segments = listOf("FNOS:/vol1/1000/Codex-Assets/videos", "缓存 38.2 / 256 MB"),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    ProtoTransferHeaderButton(activeCount = 2)
                    IconButton(onClick = {}) { Icon(Icons.Rounded.UploadFile, contentDescription = "上传归档", tint = scheme.onSurface) }
                    IconButton(onClick = {}) { Icon(Icons.Rounded.Search, contentDescription = "搜索", tint = scheme.onSurface) }
                    IconButton(onClick = {}) { Icon(Icons.Rounded.MoreVert, contentDescription = "更多", tint = scheme.onSurface) }
                }
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f))

                // 双栏：左文件列表 + 右远程大文件详情
                Row(Modifier.fillMaxSize()) {
                    // 左列：远程文件列表
                    Column(
                        modifier = Modifier
                            .width(340.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = InfinitySpacing.Small),
                    ) {
                        Text(
                            "根目录  ›  videos",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small),
                        )
                        ProtoRemoteListItem(Icons.Rounded.Folder, "2026-11", "目录 · 11月24日", cached = false, largeCloud = false, selected = false)
                        ProtoRemoteListItem(Icons.Rounded.Image, "storyboard-v2.png", "2.4 MB · 11月27日", cached = true, largeCloud = false, selected = false)
                        ProtoRemoteListItem(Icons.Rounded.Description, "asset-notes.md", "14 KB · 11月27日", cached = true, largeCloud = false, selected = false)
                        ProtoRemoteListItem(Icons.Rounded.Movie, "demo-recording-4k.mp4", "142.8 MB · 11月28日", cached = false, largeCloud = true, selected = true)
                        ProtoRemoteListItem(Icons.Rounded.PictureAsPdf, "architecture-slides.pdf", "24.6 MB · 11月25日", cached = false, largeCloud = true, selected = false)
                        ProtoRemoteListItem(Icons.Rounded.AudioFile, "podcast-ep12.wav", "68.5 MB · 11月22日", cached = false, largeCloud = true, selected = false)
                    }

                    VerticalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))

                    // 右列：大文件轻量详情卡（不自动下载，显式操作）
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(InfinitySpacing.Large),
                        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
                    ) {
                        // 主详情卡
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = scheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(InfinitySpacing.XLarge),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(domain.infoContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.Movie, null, tint = domain.onInfoContainer, modifier = Modifier.size(32.dp))
                                }
                                Text(
                                    "demo-recording-4k.mp4",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = scheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    ProductionStatusChip("142.8 MB", ProductionTone.NEUTRAL)
                                    ProductionStatusChip("远端大文件 · 未自动下载", ProductionTone.INFO)
                                    ProductionStatusChip("支持断点续传", ProductionTone.SUCCESS)
                                }
                                Text(
                                    "该文件超过 8 MB 自动预览阈值，仅读取元数据，不会后台拉取。可通过传输队列按需下载到本机工作区或临时缓存预览。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                )
                                InfinityPillRow(reverseWhenStacked = true) {
                                    InfinityPillButton(
                                        text = "缓存预览",
                                        icon = Icons.Rounded.CloudDownload,
                                        onClick = {},
                                        tone = InfinityPillTone.NEUTRAL,
                                    )
                                    InfinityPillButton(
                                        text = "下载到工作区",
                                        icon = Icons.Rounded.Download,
                                        onClick = {},
                                        tone = InfinityPillTone.PRIMARY,
                                    )
                                }
                            }
                        }

                        // 元数据与路径信息卡
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = scheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(InfinitySpacing.Large), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("远端属性", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                                ProtoKeyValueLine("远端主机", "FNOS（SFTP · 指纹已验证）")
                                ProtoKeyValueLine("远端路径", "/vol1/1000/Codex-Assets/videos/demo-recording-4k.mp4", monospace = true)
                                ProtoKeyValueLine("会话虚拟路径", "/mnt/personal-archive-assets/videos/demo-recording-4k.mp4", monospace = true)
                                ProtoKeyValueLine("修改时间", "2026-11-28 01:42")
                                ProtoKeyValueLine("远端 SHA-256", "点击校验（服务端原生计算，零回传流量）")
                            }
                        }

                        // LRU 预览缓存状态条
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = scheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Medium),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                            ) {
                                Icon(Icons.Rounded.CleaningServices, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("远程预览缓存 · 38.2 / 256 MB", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
                                    LinearProgressIndicator(
                                        progress = { 38.2f / 256f },
                                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                                        strokeCap = StrokeCap.Round,
                                        drawStopIndicator = {},
                                    )
                                }
                                InfinityPillButton(text = "一键清理", onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoRemoteListItem(
    icon: ImageVector,
    name: String,
    meta: String,
    cached: Boolean,
    largeCloud: Boolean,
    selected: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = InfinitySpacing.Small, vertical = 2.dp)
            .clip(MaterialTheme.shapes.large)
            .background(if (selected) scheme.selectionIndicator(scheme.background) else Color.Transparent)
            .padding(horizontal = InfinitySpacing.Medium, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Icon(
            icon,
            null,
            tint = if (selected) scheme.onSelectionIndicator else scheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) scheme.onSelectionIndicator else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(meta, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
        }
        when {
            cached -> ProductionStatusChip("已缓存", ProductionTone.SUCCESS)
            largeCloud -> Icon(Icons.Rounded.CloudDownload, contentDescription = "云端大文件", tint = scheme.outline, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ProtoKeyValueLine(label: String, value: String, monospace: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            if (monospace) value.breakableIdentifier() else value,
            style = if (monospace) MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace) else MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

// =================================================================================================
// 5. 专用传输管理界面（全部 5 种任务状态 · 深色 + 窄屏大字号浅色）
// =================================================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemoteTransferManagerBody() {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Surface(color = scheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(start = 4.dp, end = InfinitySpacing.ScreenHorizontal),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {}) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = scheme.onSurface)
                }
                Column(Modifier.weight(1f)) {
                    Text("传输管理", style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
                    InfinityMetaLine(
                        segments = listOf("进行中 2", "已暂停 1", "18.4 MB/s"),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                InfinityPillButton(text = "全部暂停", icon = Icons.Rounded.Pause, onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true)
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = InfinitySpacing.ScreenHorizontal),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
            ) {
                // 筛选胶囊行
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProtoFilterChipPill("全部 5", selected = true)
                    ProtoFilterChipPill("进行中 2", selected = false)
                    ProtoFilterChipPill("已暂停 1", selected = false)
                    ProtoFilterChipPill("已完成 1", selected = false)
                    ProtoFilterChipPill("失败 1", selected = false)
                }

                // 任务 1：上传进行中
                ProtoTransferTaskCard(
                    directionIcon = Icons.Rounded.CloudUpload,
                    iconBg = domain.infoContainer,
                    iconTint = domain.onInfoContainer,
                    fileName = "demo-recording-4k.mp4",
                    route = "Personal-Archive/raw → FNOS:/vol1/1000/Codex-Assets/videos",
                    statusChip = "上传中" to ProductionTone.INFO,
                    progress = 0.48f,
                    progressLabel = "68.4 / 142.8 MB (48%) · 12.6 MB/s · 约 6 秒",
                ) {
                    InfinityPillRow(reverseWhenStacked = false) {
                        InfinityPillButton(text = "暂停", icon = Icons.Rounded.Pause, onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true)
                        InfinityPillButton(text = "取消", icon = Icons.Rounded.Close, onClick = {}, tone = InfinityPillTone.DANGER, compact = true)
                    }
                }

                // 任务 2：上传后服务端原生 SHA-256 校验中
                ProtoTransferTaskCard(
                    directionIcon = Icons.Rounded.VerifiedUser,
                    iconBg = domain.infoContainer,
                    iconTint = domain.onInfoContainer,
                    fileName = "podcast-ep12.wav",
                    route = "会话文件 → FNOS:/vol1/1000/Codex-Assets/audio",
                    statusChip = "远端校验中" to ProductionTone.INFO,
                    progress = 1.0f,
                    progressLabel = "68.5 / 68.5 MB · 正在执行服务端 SHA-256 校验（零回传）",
                ) {
                    InfinityPillRow(reverseWhenStacked = false) {
                        InfinityPillButton(text = "取消", icon = Icons.Rounded.Close, onClick = {}, tone = InfinityPillTone.DANGER, compact = true)
                    }
                }

                // 任务 3：已暂停（支持断点续传）
                ProtoTransferTaskCard(
                    directionIcon = Icons.Rounded.CloudDownload,
                    iconBg = domain.warningContainer,
                    iconTint = domain.onWarningContainer,
                    fileName = "design-system-v4.fig",
                    route = "FNOS:/vol1/1000/Codex-Assets/design → Personal-Archive/inbox",
                    statusChip = "已暂停" to ProductionTone.WARNING,
                    progress = 0.37f,
                    progressLabel = "已暂停 · 31.2 / 84.0 MB (37%) · 可断点续传",
                ) {
                    InfinityPillRow(reverseWhenStacked = true) {
                        InfinityPillButton(text = "取消", icon = Icons.Rounded.Close, onClick = {}, tone = InfinityPillTone.DANGER, compact = true)
                        InfinityPillButton(text = "继续传输", icon = Icons.Rounded.PlayArrow, onClick = {}, tone = InfinityPillTone.PRIMARY, compact = true)
                    }
                }

                // 任务 4：已完成（附带可复制 SHA-256 归档回执）
                ProtoTransferTaskCard(
                    directionIcon = Icons.Rounded.CheckCircle,
                    iconBg = domain.successContainer,
                    iconTint = domain.onSuccessContainer,
                    fileName = "architecture-slides.pdf",
                    route = "Personal-Archive/docs → FNOS:/vol1/1000/Codex-Assets/docs",
                    statusChip = "已校验归档" to ProductionTone.SUCCESS,
                    progress = null,
                    progressLabel = "24.6 MB · ssh-local-sha256 · 9f8a4c2e…3b1d",
                ) {
                    InfinityPillRow(reverseWhenStacked = false) {
                        InfinityPillButton(text = "复制 SHA-256", icon = Icons.Rounded.ContentCopy, onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true)
                        InfinityPillButton(text = "移除记录", icon = Icons.Rounded.DeleteOutline, onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true)
                    }
                }

                // 任务 5：失败（保留断点可重试）
                ProtoTransferTaskCard(
                    directionIcon = Icons.Rounded.ErrorOutline,
                    iconBg = scheme.errorContainer,
                    iconTint = scheme.onErrorContainer,
                    fileName = "raw-footage-03.mov",
                    route = "Personal-Archive/raw → FNOS:/vol1/1000/Codex-Assets/videos",
                    statusChip = "网络中断" to ProductionTone.ERROR,
                    progress = 0.35f,
                    progressLabel = "连接超时 · 已保留 112.0 / 320.0 MB 临时断点",
                ) {
                    InfinityPillRow(reverseWhenStacked = true) {
                        InfinityPillButton(text = "放弃并清理", icon = Icons.Rounded.DeleteOutline, onClick = {}, tone = InfinityPillTone.DANGER, compact = true)
                        InfinityPillButton(text = "断点重试", icon = Icons.Rounded.Refresh, onClick = {}, tone = InfinityPillTone.PRIMARY, compact = true)
                    }
                }

                // 底部清除已完成
                Box(Modifier.fillMaxWidth().padding(vertical = InfinitySpacing.Small)) {
                    InfinityPillButton(
                        text = "清除已完成的传输记录",
                        icon = Icons.Rounded.CleaningServices,
                        onClick = {},
                        tone = InfinityPillTone.NEUTRAL,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(InfinitySpacing.Large))
            }
        }
    }
}

@Composable
private fun ProtoFilterChipPill(text: String, selected: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) scheme.primary else scheme.surfaceContainerHigh,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun ProtoTransferTaskCard(
    directionIcon: ImageVector,
    iconBg: Color,
    iconTint: Color,
    fileName: String,
    route: String,
    statusChip: Pair<String, ProductionTone>,
    progress: Float?,
    progressLabel: String,
    actions: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(InfinitySpacing.Large),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(iconBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(directionIcon, null, tint = iconTint, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            fileName,
                            style = MaterialTheme.typography.titleSmall,
                            color = scheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        ProductionStatusChip(statusChip.first, statusChip.second)
                    }
                    Text(
                        route.breakableIdentifier(),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                        color = scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                    color = when (statusChip.second) {
                        ProductionTone.ERROR -> scheme.error
                        ProductionTone.WARNING -> MaterialTheme.infinityDomainColors.warning
                        else -> scheme.primary
                    },
                    strokeCap = StrokeCap.Round,
                    drawStopIndicator = {},
                )
            }
            Text(progressLabel, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            actions()
        }
    }
}

@Preview(name = "5. 专用传输管理界面（全部状态 · 深色）", widthDp = 411, heightDp = 1180, showBackground = true)
@Composable
fun RemoteTransferManagerDarkPreview() {
    SftpProtoTheme(dark = true) { RemoteTransferManagerBody() }
}

@Preview(name = "5b. 专用传输管理界面（360dp 窄屏 · 1.25x 大字号 · 浅色）", widthDp = 360, heightDp = 1480, fontScale = 1.25f, showBackground = true)
@Composable
fun RemoteTransferManagerCompactLightPreview() {
    SftpProtoTheme(dark = false) { RemoteTransferManagerBody() }
}

// =================================================================================================
// 6. 会话挂载引用（统一 /mnt/<alias>/）+ Agent 远程归档上传内联审批卡（深色）
// =================================================================================================

@OptIn(ExperimentalLayoutApi::class)
@Preview(name = "6. 会话引用远程挂载 + Agent 归档上传内联审批卡", widthDp = 411, heightDp = 900, showBackground = true)
@Composable
fun SessionMountAndAgentApprovalPreview() {
    SftpProtoTheme(dark = true) {
        val scheme = MaterialTheme.colorScheme
        Surface(color = scheme.background, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(InfinitySpacing.ScreenHorizontal),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XLarge),
            ) {
                Spacer(Modifier.height(InfinitySpacing.Small))

                // 上半部：会话挂载面板中引用资料库 SFTP 挂载
                Text("会话挂载面板（引用资料库远程存储）", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                InfinitySettingsSection(
                    title = "当前会话已挂载",
                    footer = "远程 SFTP 挂载仅通过文件工具与归档工具访问，不绑定进 Linux 容器，也不向模型或终端暴露 SSH 凭据。",
                    typographicFooter = true,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(InfinitySpacing.RowHorizontal),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                    ) {
                        Icon(Icons.Rounded.Cloud, null, tint = scheme.primary, modifier = Modifier.size(22.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                itemVerticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "personal-archive-assets",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = scheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                ProductionStatusChip("SFTP · 可归档", ProductionTone.INFO)
                            }
                            InfinityMetaLine(
                                segments = listOf("/mnt/personal-archive-assets/", "FNOS", "仅文件与归档工具可见"),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = scheme.onSurfaceVariant)
                    }
                }

                InfinitySettingsSection(title = "可从资料库快速引用") {
                    InfinitySettingsItem(
                        icon = Icons.AutoMirrored.Rounded.LibraryBooks,
                        title = "服务器日志归档",
                        subtitle = "远程 SFTP · main-server · 只读 · 挂载至 /mnt/server-logs/",
                        subtitleMaxLines = 2,
                        onClick = {},
                        showChevron = true,
                    )
                }

                // 下半部：Agent 归档上传内联审批卡
                Text("对话内联审批卡（Agent 归档上传至 SFTP）", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                val cardShape = RoundedCornerShape(22.dp)
                Surface(
                    shape = cardShape,
                    color = InfinityObsidianDialogContainer,
                    border = BorderStroke(0.75.dp, InfinityPureWhite.copy(alpha = 0.13f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(InfinitySpacing.Large),
                        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(scheme.primary.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.Shield, null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text("批准远程归档上传？", style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                                Text("工具：remote_mount_upload · 逐次审批", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = InfinityObsidianDialogChip,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(InfinitySpacing.Medium), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                ProtoKeyValueLine("远端主机", "FNOS（指纹已钉扎）")
                                ProtoKeyValueLine("本地来源", "Personal-Archive/raw/demo-recording-4k.mp4", monospace = true)
                                ProtoKeyValueLine("远端目标", "/mnt/personal-archive-assets/videos/demo-recording-4k.mp4", monospace = true)
                                ProtoKeyValueLine("文件大小", "142.8 MB")
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ProductionStatusChip("禁止覆盖已有不同文件", ProductionTone.SUCCESS)
                            ProductionStatusChip("服务端 SHA-256 校验", ProductionTone.INFO)
                            ProductionStatusChip("进入传输管理队列", ProductionTone.NEUTRAL)
                        }

                        InfinityPillRow(reverseWhenStacked = true) {
                            InfinityPillButton(text = "拒绝", onClick = {}, tone = InfinityPillTone.NEUTRAL)
                            InfinityPillButton(text = "批准并归档", icon = Icons.Rounded.CloudUpload, onClick = {}, tone = InfinityPillTone.PRIMARY)
                        }
                    }
                }
                Spacer(Modifier.height(InfinitySpacing.Large))
            }
        }
    }
}
