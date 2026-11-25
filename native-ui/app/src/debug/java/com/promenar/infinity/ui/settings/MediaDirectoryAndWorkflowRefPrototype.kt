package com.promenar.infinity.ui.settings

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.domain.mediagen.CharacterReferenceMode
import com.promenar.infinity.domain.mediagen.ComfyMediaCategory
import com.promenar.infinity.domain.mediagen.GenerationMode
import com.promenar.infinity.domain.mediagen.WorkflowExecutionMode
import com.promenar.infinity.ui.common.FrostedChromeRole
import com.promenar.infinity.ui.common.InfinityChip
import com.promenar.infinity.ui.common.InfinityFieldRow
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinityPanePresentation
import com.promenar.infinity.ui.common.InfinityPanelTab
import com.promenar.infinity.ui.common.InfinityPanelTabs
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinityTagScrollRow
import com.promenar.infinity.ui.common.LocalFrostedChromeMaterial
import com.promenar.infinity.ui.common.LocalInfinityPanePresentation
import com.promenar.infinity.ui.common.rememberFrostedChromeMaterial
import com.promenar.infinity.ui.production.LocalProductionSend
import com.promenar.infinity.ui.production.ProductionSendState
import com.promenar.infinity.ui.production.ProductionStatusChip
import com.promenar.infinity.ui.production.ProductionTab
import com.promenar.infinity.ui.production.ProductionTone
import com.promenar.infinity.ui.production.ProductionTopBar
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

/*
 * 模块三高保真补充原型（v2，隔离于 debug 源码）：
 * 1. 「媒体模型」能力目录页：图像/视频/音频三组全面移除排序箭头；视频与音频移除「默认」蓝标，图像仅给真实生效的云端默认生图模型标注「默认」；
 * 2. ComfyUI 工作流编辑页：当包含「参考」模式时，直接逐工作流配置「资产参考入参模式（分张单视图 / 多视图整卡）」与「参考图上限」；
 * 3. 制作面板右上角三点瘦身轻量下拉菜单：移除厚重底部弹层与项目级默认视频模型/参考入参设置，只保留 4 项轻量项目级动作。
 */

private data class ProtoChannelEntry(
    val name: String,
    val segments: List<String>,
    val isRealDefaultImage: Boolean = false,
)

@Composable
private fun ProtoModuleThreeFrame(
    mode: InfinityThemeMode,
    inspectorChrome: Boolean = false,
    content: @Composable () -> Unit,
) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        val chrome = if (inspectorChrome) rememberFrostedChromeMaterial(FrostedChromeRole.DOCKED_INSPECTOR) else null
        val surfaceColor = chrome?.frostedTint ?: chrome?.container ?: MaterialTheme.colorScheme.background
        CompositionLocalProvider(
            LocalFrostedChromeMaterial provides chrome,
            LocalInfinityPanePresentation provides if (inspectorChrome) InfinityPanePresentation.Inspector else InfinityPanePresentation.Page,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(if (inspectorChrome && mode == InfinityThemeMode.DARK) Color(0xFF0A0B0E) else MaterialTheme.colorScheme.background),
            ) {
                Surface(
                    color = surfaceColor,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun ProtoMediaDirectoryContent() {
    val imageRows = listOf(
        ProtoChannelEntry("Nano Banana 2.1", listOf("Gemini", "文生"), isRealDefaultImage = true),
        ProtoChannelEntry("Qwen2.1_Edit_3Ref", listOf("ComfyUI", "MAIN", "文生", "参考", "分张 ≤3")),
        ProtoChannelEntry("Qwen2.1_T2I", listOf("ComfyUI", "MAIN", "文生")),
    )
    val videoRows = listOf(
        ProtoChannelEntry("H3 Ref2VA 768p", listOf("ComfyUI", "MAIN", "参考", "分张 ≤3")),
        ProtoChannelEntry("H3 T2VA 768p Turbo4", listOf("ComfyUI", "MAIN", "文生")),
        ProtoChannelEntry("Seedance 2.5 Pro", listOf("火山方舟", "文生", "首帧", "参考", "整卡 ≤9")),
    )
    val audioRows = listOf(
        ProtoChannelEntry("MiniMax Music 3", listOf("ComfyUI", "MAIN", "音乐")),
        ProtoChannelEntry("Speech 2.6 HD", listOf("MiniMax", "语音")),
    )
    InfinitySettingsPageLayout(title = "媒体模型", onBack = {}) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding),
        ) {
            InfinitySettingsSection(title = "图像") {
                imageRows.forEachIndexed { idx, row ->
                    if (idx > 0) InfinityGroupDivider()
                    InfinitySettingsItem(
                        title = row.name,
                        subtitleSegments = row.segments,
                        subtitleMaxLines = 2,
                        titleMaxLines = 2,
                        showChevron = true,
                        onClick = {},
                        trailingContent = if (row.isRealDefaultImage) ({
                            ProductionStatusChip("默认", ProductionTone.INFO)
                        }) else null,
                    )
                }
            }
            InfinitySettingsSection(title = "视频") {
                videoRows.forEachIndexed { idx, row ->
                    if (idx > 0) InfinityGroupDivider()
                    InfinitySettingsItem(
                        title = row.name,
                        subtitleSegments = row.segments,
                        subtitleMaxLines = 2,
                        titleMaxLines = 2,
                        showChevron = true,
                        onClick = {},
                    )
                }
            }
            InfinitySettingsSection(title = "音频") {
                audioRows.forEachIndexed { idx, row ->
                    if (idx > 0) InfinityGroupDivider()
                    InfinitySettingsItem(
                        title = row.name,
                        subtitleSegments = row.segments,
                        subtitleMaxLines = 2,
                        titleMaxLines = 2,
                        showChevron = true,
                        onClick = {},
                    )
                }
            }
            InfinitySettingsSection(title = "服务商与 ComfyUI 主机") {
                InfinitySettingsItem(
                    icon = Icons.Rounded.Hub,
                    title = "服务商与 ComfyUI 主机",
                    showChevron = true,
                    onClick = {},
                )
            }
        }
    }
}

@Composable
private fun ProtoWorkflowRefModeEditorContent() {
    val scheme = MaterialTheme.colorScheme
    InfinitySettingsPageLayout(
        title = "编辑工作流",
        onBack = {},
        actions = {
            InfinityPillButton(
                text = "保存",
                onClick = {},
                tone = InfinityPillTone.PRIMARY,
                compact = true,
                modifier = Modifier.padding(end = 8.dp),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            InfinitySettingsSection(title = "基本信息") {
                InfinityFieldRow(label = "工作流名称", value = "H3 Ref2VA 768p", onValueChange = {})
                InfinityGroupDivider()
                InfinityFieldRow(label = "工作流 ID", value = "h3_ref2va_768p", onValueChange = {}, enabled = false, monospace = true)
                InfinityGroupDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("媒体类型", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    InfinityTagScrollRow(spacing = 8.dp) {
                        listOf(
                            ComfyMediaCategory.IMAGE to "图像",
                            ComfyMediaCategory.VIDEO to "视频",
                            ComfyMediaCategory.SPEECH to "语音",
                            ComfyMediaCategory.MUSIC to "音乐",
                            ComfyMediaCategory.POST_PROCESS to "后处理",
                        ).forEach { (cat, label) ->
                            InfinityChip(label = label, selected = cat == ComfyMediaCategory.VIDEO, onClick = {})
                        }
                    }
                    Text("支持模式", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    InfinityTagScrollRow(spacing = 8.dp) {
                        listOf(
                            GenerationMode.TEXT to "文生",
                            GenerationMode.FIRST_FRAME to "首帧",
                            GenerationMode.FIRST_LAST_FRAME to "首尾帧",
                            GenerationMode.REFERENCE to "参考",
                        ).forEach { (mode, label) ->
                            InfinityChip(label = label, selected = mode == GenerationMode.REFERENCE, onClick = {})
                        }
                    }
                    InfinitySegmentedControl(
                        segments = listOf(
                            InfinitySegment(WorkflowExecutionMode.SINGLE, "单次执行"),
                            InfinitySegment(WorkflowExecutionMode.STATEFUL_CHAIN, "有状态接续链"),
                        ),
                        selected = WorkflowExecutionMode.SINGLE,
                        onSelect = {},
                        fillWidth = true,
                    )
                }
                InfinityGroupDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("资产参考入参模式", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    InfinitySegmentedControl(
                        segments = listOf(
                            InfinitySegment(CharacterReferenceMode.SEPARATE, "分张单视图"),
                            InfinitySegment(CharacterReferenceMode.COMPOSITE, "多视图整卡"),
                        ),
                        selected = CharacterReferenceMode.SEPARATE,
                        onSelect = {},
                        fillWidth = true,
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = scheme.surfaceContainerHigh.copy(alpha = 0.72f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = null,
                                tint = scheme.primary,
                                modifier = Modifier.padding(top = 1.dp).size(16.dp),
                            )
                            Text(
                                text = "与本工作流绑定的默认参考图传入方式；单次实验如需临时切换，仍走固定字段覆写 Diff 审批。",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                InfinityGroupDivider()
                InfinityFieldRow(
                    label = "参考图上限（张）",
                    value = "3",
                    onValueChange = {},
                    monospace = true,
                )
            }
        }
    }
}

@Composable
private fun ProtoCompactOverflowMenuCard(experimentalEnabled: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.65f)),
        shadowElevation = 6.dp,
        modifier = Modifier.width(248.dp),
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.Science, contentDescription = null, tint = if (experimentalEnabled) scheme.primary else scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("试验模式", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = scheme.onSurface)
                    Text("跳过分镜锁场直接出片", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (experimentalEnabled) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Text("在文件中打开", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface, modifier = Modifier.weight(1f))
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.Hub, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Text("媒体模型与工作流", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface, modifier = Modifier.weight(1f))
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = scheme.outlineVariant.copy(alpha = 0.5f))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.LinkOff, contentDescription = null, tint = scheme.error, modifier = Modifier.size(20.dp))
                Text("解除项目绑定", style = MaterialTheme.typography.bodyMedium, color = scheme.error, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ---------- 4 个 Android Compose Preview 原型 ----------

@Preview(name = "1. 媒体模型能力目录 · 去排序化(深色边栏)", widthDp = 380, heightDp = 960, locale = "zh-rCN")
@Composable
fun mediaDirectoryDarkInspectorPreview() = ProtoModuleThreeFrame(
    mode = InfinityThemeMode.DARK,
    inspectorChrome = true,
) {
    ProtoMediaDirectoryContent()
}

@Preview(name = "2. 媒体模型能力目录 · 浅色大字号(360dp·1.3x)", widthDp = 360, heightDp = 1120, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun mediaDirectoryLightLargeFontPreview() = ProtoModuleThreeFrame(
    mode = InfinityThemeMode.LIGHT,
    inspectorChrome = false,
) {
    ProtoMediaDirectoryContent()
}

@Preview(name = "3. ComfyUI 工作流编辑页 · 逐工作流参考入参模式(深色)", widthDp = 380, heightDp = 920, locale = "zh-rCN")
@Composable
fun comfyWorkflowRefModeEditorDarkPreview() = ProtoModuleThreeFrame(
    mode = InfinityThemeMode.DARK,
    inspectorChrome = true,
) {
    ProtoWorkflowRefModeEditorContent()
}

@Preview(name = "4. 制作面板右上角三点瘦身轻菜单(深色边栏)", widthDp = 380, heightDp = 520, locale = "zh-rCN")
@Composable
fun productionCompactOverflowMenuDarkPreview() = ProtoModuleThreeFrame(
    mode = InfinityThemeMode.DARK,
    inspectorChrome = true,
) {
    CompositionLocalProvider(
        LocalProductionSend provides ProductionSendState(count = 4, sending = false, queued = false, onSend = {}),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                ProductionTopBar(
                    title = "惊澜三十秒",
                    meta = listOf("电影", "6 个镜头", "16:9 横屏"),
                    onBack = null,
                ) {
                    IconButton(onClick = {}) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "更多")
                    }
                }
                InfinityPanelTabs(
                    tabs = listOf(
                        InfinityPanelTab(ProductionTab.OVERVIEW, "总览"),
                        InfinityPanelTab(ProductionTab.SCRIPT, "剧本"),
                        InfinityPanelTab(ProductionTab.ASSETS, "资产"),
                        InfinityPanelTab(ProductionTab.STORYBOARD, "分镜"),
                        InfinityPanelTab(ProductionTab.OUTPUT, "出片"),
                        InfinityPanelTab(ProductionTab.CUT, "剪辑"),
                    ),
                    selected = ProductionTab.OVERVIEW,
                    onSelect = {},
                    contentInset = InfinitySpacing.ScreenHorizontal,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "右上角轻量菜单展开示意（移除原厚重底部弹层与项目级模型/参考设置）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Box(Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 12.dp)) {
                ProtoCompactOverflowMenuCard(experimentalEnabled = true)
            }
        }
    }
}
