package com.promenar.infinity.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.LocalLibrary
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityScrollingLine
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.infinityMenuColors
import com.promenar.infinity.ui.common.obsidianTopGlint
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

/*
 * 资料库「文件」位置分区与工作区管理交互 + ComfyUI 外屏 Tailnet 徽章修复高保真原型（v1，仅存于 debug 源码）：
 *
 * 1. 「文件」一级列表四分组（内外屏共享同一语义分组，行尾维持简洁小三角箭头 `>`）：
 *    - 内置位置：存储空间 / 会话工作区 / Linux 主目录（按 Alpine Linux 安装状态动态显隐）
 *    - 应用内工作区（仅非空时显示）：保留的会话文件及应用内项目目录，带清晰来源副标题
 *    - 外部路径：挂载的手机共享存储与外部授权文件夹 + 组尾「添加外部文件夹」
 *    - 已固定（仅非空时显示）：用户手动固定的快捷目录入口
 * 2. 双入口管理：长按列表行弹出 L3 黑曜石菜单 + 进入目录根目录后右上角 ⋮ 上下文菜单；
 *    根目录本身已在一级列表时不再重复显示「固定」按钮。
 * 3. 增强版「文件夹详情」：补齐来源类型、完整路径（等宽可折行 + 复制路径胶囊按钮）与引用状态。
 * 4. 一步确认删除：应用内工作区一步确认删除登记及本地文件；外部共享存储一步确认「移出列表（不删手机原文件）」。
 * 5. ComfyUI 设置页主机行：Tailnet 徽章上移至主机名右侧，长地址独占第二行零裁切。
 */

@Composable
private fun ProtoTheme(dark: Boolean = true, content: @Composable () -> Unit) {
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
private fun ProtoTopBar(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.width(4.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun OuterFilesCategorizedBody(linuxInstalled: Boolean = true) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ProtoTopBar("文件")
            Spacer(Modifier.height(InfinitySpacing.Large))
            Column(Modifier.padding(horizontal = InfinitySpacing.ScreenHorizontal)) {
                // 分组 1：内置位置（Linux 主目录按安装状态动态显隐）
                InfinitySettingsSection(title = "内置位置") {
                    InfinitySettingsItem(
                        icon = Icons.Rounded.LocalLibrary,
                        title = "存储空间",
                        onClick = {},
                        showChevron = true,
                    )
                    InfinitySettingsItem(
                        icon = Icons.Rounded.FolderShared,
                        title = "会话工作区",
                        onClick = {},
                        showChevron = true,
                    )
                    if (linuxInstalled) {
                        InfinitySettingsItem(
                            icon = Icons.Rounded.Terminal,
                            title = "Linux 主目录",
                            onClick = {},
                            showChevron = true,
                        )
                    }
                }

                // 分组 2：应用内工作区（历史保留的会话文件夹与项目工作区，空时自动隐藏）
                InfinitySettingsSection(
                    title = "应用内工作区",
                    footer = "长按条目可重命名、查看完整路径或删除；全部清理后此分组自动隐藏。",
                ) {
                    InfinitySettingsItem(
                        icon = Icons.Rounded.Work,
                        title = "执行测试文件操作",
                        subtitle = "保留的会话 · session-20261115-0002",
                        onClick = {},
                        showChevron = true,
                    )
                    InfinitySettingsItem(
                        icon = Icons.Rounded.Work,
                        title = "创建三个图像模板",
                        subtitle = "保留的会话 · session-20261123-1819",
                        onClick = {},
                        showChevron = true,
                    )
                    InfinitySettingsItem(
                        icon = Icons.Rounded.Work,
                        title = "New Chat",
                        subtitle = "保留的会话 · session-20261123-1819-2",
                        onClick = {},
                        showChevron = true,
                    )
                    InfinitySettingsItem(
                        icon = Icons.Rounded.Work,
                        title = "创建白模预演项目",
                        subtitle = "保留的会话 · session-20261123-1819-3",
                        onClick = {},
                        showChevron = true,
                    )
                }

                // 分组 3：外部路径（手机共享存储与外部授权目录 + 组尾添加入口）
                InfinitySettingsSection(title = "外部路径") {
                    InfinitySettingsItem(
                        icon = Icons.Rounded.SdStorage,
                        title = "Personal-Archive",
                        subtitle = "main · /Personal-Archive",
                        onClick = {},
                        showChevron = true,
                    )
                    InfinitySettingsItem(
                        icon = Icons.Rounded.SdStorage,
                        title = "Infinity",
                        subtitle = "/Infinity",
                        onClick = {},
                        showChevron = true,
                    )
                    InfinitySettingsItem(
                        icon = Icons.Rounded.CreateNewFolder,
                        title = "添加外部文件夹",
                        onClick = {},
                        showChevron = true,
                    )
                }

                // 分组 4：已固定（用户手动固定的快捷入口，空时自动隐藏）
                InfinitySettingsSection(title = "已固定") {
                    InfinitySettingsItem(
                        icon = Icons.Rounded.PushPin,
                        title = "帝国暗面",
                        subtitle = "存储空间",
                        onClick = {},
                        showChevron = true,
                    )
                    InfinitySettingsItem(
                        icon = Icons.Rounded.PushPin,
                        title = "v0721-e2e-ok",
                        subtitle = "会话工作区",
                        onClick = {},
                        showChevron = true,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Preview(name = "1. 外屏文件页（四分组 + 保留小三角箭头 · 深色）", widthDp = 411, heightDp = 1140, showBackground = true)
@Composable
fun OuterFilesCategorizedDarkPreview() {
    ProtoTheme(dark = true) { OuterFilesCategorizedBody(linuxInstalled = true) }
}

@Preview(name = "1b. 外屏文件页（无 Linux · 浅色对比）", widthDp = 411, heightDp = 1060, showBackground = true)
@Composable
fun OuterFilesCategorizedLightNoLinuxPreview() {
    ProtoTheme(dark = false) { OuterFilesCategorizedBody(linuxInstalled = false) }
}

@Composable
private fun ProtoObsidianMenuCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = infinityMenuColors()
    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(0.75.dp, colors.edge, shape)
            .obsidianTopGlint(colors.highlight, 18.dp),
        shape = shape,
        color = colors.container,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            content()
        }
    }
}

@Composable
private fun ProtoMenuRow(icon: ImageVector, label: String, destructive: Boolean = false) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Preview(name = "2. 长按菜单与目录内页头菜单对比（消除误导固定）", widthDp = 411, heightDp = 820, showBackground = true)
@Composable
fun LocationMenusComparisonPreview() {
    ProtoTheme(dark = true) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "长按一级列表条目（行尾保持小三角箭头）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    ProtoObsidianMenuCard("执行测试文件操作", Modifier.weight(1f)) {
                        ProtoMenuRow(Icons.Rounded.DriveFileRenameOutline, "重命名")
                        ProtoMenuRow(Icons.Rounded.Info, "文件夹详情")
                        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        ProtoMenuRow(Icons.Rounded.DeleteOutline, "删除工作区", destructive = true)
                    }
                    ProtoObsidianMenuCard("Personal-Archive", Modifier.weight(1f)) {
                        ProtoMenuRow(Icons.Rounded.DriveFileRenameOutline, "重命名")
                        ProtoMenuRow(Icons.Rounded.Info, "文件夹详情")
                        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        ProtoMenuRow(Icons.Rounded.LinkOff, "移出列表", destructive = true)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    ProtoObsidianMenuCard("已固定 · 帝国暗面", Modifier.weight(1f)) {
                        ProtoMenuRow(Icons.Rounded.PushPin, "取消快捷固定")
                        ProtoMenuRow(Icons.Rounded.Info, "文件夹详情")
                    }
                    ProtoObsidianMenuCard("进入工作区根目录 ⋮", Modifier.weight(1f)) {
                        ProtoMenuRow(Icons.Rounded.Visibility, "显示隐藏文件")
                        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        ProtoMenuRow(Icons.Rounded.Info, "文件夹详情")
                        ProtoMenuRow(Icons.Rounded.DriveFileRenameOutline, "重命名工作区")
                        ProtoMenuRow(Icons.Rounded.DeleteOutline, "删除工作区", destructive = true)
                    }
                }
                Text(
                    "说明：进入任意一级位置根目录（path == \"/\"）时，右上角不再显示“固定”，仅二级及以上子目录与具体会话文件夹支持“固定为快捷入口”。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProtoObsidianDialogCard(
    title: String,
    icon: ImageVector? = null,
    destructiveIcon: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.75.dp, InfinityPureWhite.copy(alpha = 0.13f), shape)
            .obsidianTopGlint(InfinityPureWhite.copy(alpha = 0.15f), 24.dp),
        shape = shape,
        color = InfinityObsidianDialogContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (destructiveIcon) MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (destructiveIcon) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}

@Composable
private fun ProtoDetailRow(label: String, value: String, monospace: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
        Text(
            text = if (monospace) value.breakableIdentifier() else value,
            style = if (monospace) MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace)
            else MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Preview(name = "3. 增强版文件夹详情（含完整路径）+ 一步确认删除弹窗", widthDp = 411, heightDp = 880, showBackground = true)
@Composable
fun FolderDetailsAndOneStepDeletePreview() {
    ProtoTheme(dark = true) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // 弹窗 A：增强版文件夹详情
                ProtoObsidianDialogCard("执行测试文件操作") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProtoDetailRow("位置", "应用内工作区 › 执行测试文件操作")
                        ProtoDetailRow("来源", "保留的会话文件夹（不进备份，卸载删除）")
                        ProtoDetailRow(
                            "完整路径",
                            "/data/data/com.promenar.infinity/files/projects/session-20261115-0002",
                            monospace = true,
                        )
                        ProtoDetailRow("引用状态", "未被会话或智能体引用")
                        ProtoDetailRow("统计", "0 个文件夹 · 2 个文件 · 1.4 KB")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {},
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, InfinityPureWhite.copy(alpha = 0.16f)),
                        ) {
                            Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("复制路径")
                        }
                        Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("关闭") }
                    }
                }

                // 弹窗 B：应用内工作区一步确认删除
                ProtoObsidianDialogCard("删除工作区？", icon = Icons.Rounded.WarningAmber, destructiveIcon = true) {
                    Text(
                        "将移除「执行测试文件操作」并永久删除应用内目录及其全部本地文件（2 个文件，共 1.4 KB），此操作不可恢复。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        color = InfinityObsidianDialogChip,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "/data/data/com.promenar.infinity/files/projects/session-20261115-0002".breakableIdentifier(),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {},
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, InfinityPureWhite.copy(alpha = 0.16f)),
                        ) { Text("取消") }
                        Button(
                            onClick = {},
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) { Text("删除") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComfyHostRowFixed(name: String, endpoint: String, tailnet: Boolean, statusLabel: String, statusTone: ProductionTone) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Dns, null, tint = scheme.primary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                InfinityScrollingLine(Modifier.weight(1f, fill = false)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                if (tailnet) ProductionStatusChip("Tailnet", ProductionTone.INFO)
            }
            InfinityScrollingLine(Modifier.fillMaxWidth()) {
                Text(
                    text = endpoint,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            InfinityScrollingLine(Modifier.fillMaxWidth()) {
                InfinityMetaLine(
                    segments = listOf("28 个工作流", "12 个提交器", "无"),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        ProductionStatusChip(statusLabel, statusTone)
    }
}

@Preview(name = "4. ComfyUI 主机行 Tailnet 徽章修复（411dp 外屏 + 360dp 窄屏）", widthDp = 411, heightDp = 420, showBackground = true)
@Composable
fun ComfyHostRowTailnetFixPreview() {
    ProtoTheme(dark = true) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                InfinitySettingsSection(title = "ComfyUI 主机（411dp 外屏）") {
                    ComfyHostRowFixed("MAIN", "http://100.106.180.58:8188", tailnet = true, statusLabel = "检测中...", statusTone = ProductionTone.INFO)
                    InfinityGroupDivider()
                    ComfyHostRowFixed("Studio-Workstation-RTX5090", "http://100.106.180.58:8188", tailnet = true, statusLabel = "在线", statusTone = ProductionTone.SUCCESS)
                }
                Box(Modifier.width(328.dp)) {
                    InfinitySettingsSection(title = "360dp 窄屏极限验证") {
                        ComfyHostRowFixed("MAIN", "http://100.106.180.58:8188", tailnet = true, statusLabel = "检测中...", statusTone = ProductionTone.INFO)
                    }
                }
            }
        }
    }
}
