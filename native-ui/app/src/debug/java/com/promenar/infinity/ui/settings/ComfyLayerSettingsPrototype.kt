package com.promenar.infinity.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.BurstMode
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.ShortText
import androidx.compose.material.icons.rounded.Subject
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.data.mediagen.comfy.ComfyAuthKind
import com.promenar.infinity.domain.mediagen.PreflightResult
import com.promenar.infinity.domain.mediagen.PreflightStatus
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinityChip
import com.promenar.infinity.ui.common.InfinityFieldRow
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPanelTab
import com.promenar.infinity.ui.common.InfinityPanelTabs
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinityScrollingLine
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinitySwitch
import com.promenar.infinity.ui.common.InfinityTagScrollRow
import com.promenar.infinity.ui.production.ProductionStatusChip
import com.promenar.infinity.ui.production.ProductionTone
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.theme.keepCjkTailTogether

/*
 * 高保真隔离原型 v1：ComfyUI 独立兼容层 — 主机列表、主机详情（工作流 / 提交器脚本库）、万能工作流编辑器与自定义字段表单。
 * 全站统一术语为「工作流」。仅位于 debug 源集供 Android Compose Preview 验收。
 */

private enum class ProtoHostTab { WORKFLOWS, SUBMITTERS, HOST_CONFIG }
private enum class ProtoWorkflowExecMode { SINGLE, STATEFUL_CHAIN }
private enum class ProtoFieldMutability { CONTENT, FIXED }

private data class ProtoComfyHost(
    val id: String,
    val name: String,
    val endpoint: String,
    val authKind: ComfyAuthKind,
    val connection: ComfyConnection,
    val workflowCount: Int,
    val submitterCount: Int,
    val isTailnet: Boolean,
)

@Preview(name = "ComfyUI主机列表-浅色412", widthDp = 412, heightDp = 860, locale = "zh-rCN")
@Preview(name = "ComfyUI主机列表-深色412", widthDp = 412, heightDp = 860, locale = "zh-rCN", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun comfyHostsHomePreview() {
    PrototypeThemeSurface {
        val scheme = MaterialTheme.colorScheme
        val domain = MaterialTheme.infinityDomainColors
        val hosts = listOf(
            ProtoComfyHost("main", "工作站 4090 (MAIN)", "http://100.106.180.58:8188", ComfyAuthKind.BEARER, ComfyConnection.ONLINE, 6, 6, true),
            ProtoComfyHost("lab4080", "工作室备机 (4080)", "http://192.168.1.42:8188", ComfyAuthKind.NONE, ComfyConnection.OFFLINE, 2, 2, false),
        )

        InfinitySettingsPageLayout(
            title = "ComfyUI",
            onBack = {},
            actions = {
                IconButton(onClick = {}, modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget)) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "检测全部主机连通")
                }
                IconButton(onClick = {}, modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget)) {
                    Icon(Icons.Rounded.Add, contentDescription = "添加 ComfyUI 主机")
                }
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = domain.infoContainer.copy(alpha = 0.55f),
                    border = BorderStroke(0.8.dp, domain.onInfoContainer.copy(alpha = 0.22f)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(domain.onInfoContainer.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Terminal, null, tint = domain.onInfoContainer, modifier = Modifier.size(20.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                Text(
                                    text = "受控内置 Python 3.14 提交环境就绪",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = domain.onInfoContainer,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                            InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                InfinityMetaLine(
                                    segments = listOf("无需 Alpine Linux", "仅用于工作流提交", "本机回环代理注入凭据"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = domain.onInfoContainer.copy(alpha = 0.88f),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                InfinitySettingsSection(
                    title = "ComfyUI 主机",
                    footer = "每台主机独立托管其工作流与已验证 Python 提交器脚本。",
                ) {
                    hosts.forEachIndexed { index, host ->
                        InfinitySettingsItem(
                            icon = Icons.Rounded.Dns,
                            title = host.name,
                            subtitleContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    InfinityTagScrollRow(spacing = 6.dp) {
                                        Text(
                                            text = host.endpoint,
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                                            color = scheme.onSurfaceVariant,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                        if (host.isTailnet) PrototypeMiniTag("Tailnet", emphasized = true)
                                    }
                                    InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                        InfinityMetaLine(
                                            segments = listOf(
                                                "${host.workflowCount} 个工作流",
                                                "${host.submitterCount} 个提交器",
                                                authKindLabel(host.authKind),
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = scheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            },
                            subtitleMaxLines = 2,
                            onClick = {},
                            trailingContent = { ConnectionChip(host.connection) },
                        )
                        if (index < hosts.lastIndex) InfinityGroupDivider()
                    }
                }

                InfinitySettingsSection(title = "操作") {
                    InfinitySettingsItem(icon = Icons.Rounded.Add, title = "新增 ComfyUI 主机", onClick = {})
                    InfinityGroupDivider()
                    InfinitySettingsItem(icon = Icons.Rounded.NetworkCheck, title = "检测全部主机连通", onClick = {})
                }
            }
        }
    }
}

private data class ProtoWorkflowSummary(
    val id: String,
    val name: String,
    val category: String,
    val icon: ImageVector,
    val chained: Boolean,
    val submitterName: String,
    val contentFieldsCount: Int,
    val fixedFieldsCount: Int,
    val preflight: PreflightResult,
)

@Preview(name = "ComfyUI主机详情-工作流页签-浅色412", widthDp = 412, heightDp = 1080, locale = "zh-rCN")
@Composable
fun comfyHostWorkflowsTabPreview() {
    PrototypeThemeSurface {
        var selectedTab by remember { mutableStateOf(ProtoHostTab.WORKFLOWS) }
        var category by remember { mutableStateOf("全部") }
        val categories = listOf("全部", "图像", "视频", "音频", "后处理")
        val scheme = MaterialTheme.colorScheme

        val workflows = listOf(
            ProtoWorkflowSummary("qwen_t2i", "Qwen Image 2.1 文生图", "图像", Icons.Rounded.Image, false, "submit_qwen21.py", 3, 6, PreflightResult(PreflightStatus.PASS)),
            ProtoWorkflowSummary("qwen_edit", "Qwen Image 2.1 多图参考编辑", "图像", Icons.Rounded.Collections, false, "submit_qwen21.py", 4, 5, PreflightResult(PreflightStatus.PASS)),
            ProtoWorkflowSummary("h3_ref", "MiniMax H3 Ref2VA 多模态参考", "视频", Icons.Rounded.Movie, false, "submit_h3.py", 6, 8, PreflightResult(PreflightStatus.PASS)),
            ProtoWorkflowSummary("h3_motion", "MiniMax H3 Motion Context 接续", "视频", Icons.Rounded.Link, true, "submit_h3_motion.py", 4, 7, PreflightResult(PreflightStatus.PASS)),
            ProtoWorkflowSummary("music3", "MiniMax Music3 完整歌曲", "音频", Icons.Rounded.MusicNote, false, "submit_music3.py", 4, 5, PreflightResult(PreflightStatus.PASS)),
            ProtoWorkflowSummary(
                "flashvsr",
                "FlashVSR 2x 视频超分",
                "后处理",
                Icons.Rounded.AutoFixHigh,
                false,
                "submit_flashvsr.py",
                2,
                4,
                PreflightResult(PreflightStatus.REVIEW, uncertain = listOf("FlashVSRNode: 建议实测复检显存占用")),
            ),
        )

        InfinitySettingsPageLayout(
            title = "工作站 4090 (MAIN)",
            onBack = {},
            horizontalContentPadding = 0.dp,
            actions = {
                ConnectionChip(ComfyConnection.ONLINE)
                Spacer(Modifier.width(8.dp))
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()),
            ) {
                InfinityPanelTabs(
                    tabs = listOf(
                        InfinityPanelTab(ProtoHostTab.WORKFLOWS, "工作流", count = "6"),
                        InfinityPanelTab(ProtoHostTab.SUBMITTERS, "提交器脚本", count = "6"),
                        InfinityPanelTab(ProtoHostTab.HOST_CONFIG, "主机设置"),
                    ),
                    selected = selectedTab,
                    onSelect = { selectedTab = it },
                )

                Column(
                    modifier = Modifier.fillMaxWidth().padding(InfinitySpacing.ScreenHorizontal),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    InfinityPillRow(Modifier.fillMaxWidth(), stackedSpacing = 8.dp) {
                        InfinityPillButton(
                            text = "新建工作流",
                            onClick = {},
                            tone = InfinityPillTone.PRIMARY,
                            icon = Icons.Rounded.Add,
                            modifier = Modifier.weight(1f),
                        )
                        InfinityPillButton(
                            text = "全部复检",
                            onClick = {},
                            tone = InfinityPillTone.NEUTRAL,
                            icon = Icons.Rounded.Refresh,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        categories.forEach { cat ->
                            InfinityChip(label = cat, selected = category == cat, onClick = { category = cat })
                        }
                    }

                    BettboxListGroup {
                        workflows.forEachIndexed { index, wf ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(role = Role.Button, onClick = {})
                                    .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (wf.chained) scheme.primaryContainer else scheme.surfaceContainerHigh),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = wf.icon,
                                        contentDescription = null,
                                        tint = if (wf.chained) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                        Text(
                                            text = wf.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = scheme.onSurface,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                    InfinityTagScrollRow(spacing = 6.dp) {
                                        PrototypeMiniTag(wf.category)
                                        PrototypeMiniTag(if (wf.chained) "链式接续" else "单次", emphasized = wf.chained)
                                        Text(
                                            text = wf.submitterName,
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                                            color = scheme.onSurfaceVariant,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                    InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                        InfinityMetaLine(
                                            segments = listOfNotNull(
                                                "${wf.contentFieldsCount} 内容",
                                                "${wf.fixedFieldsCount} 固定",
                                                "2 阶段 (first / next)".takeIf { wf.chained },
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = scheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                }
                                PreflightChip(wf.preflight)
                            }
                            if (index < workflows.lastIndex) {
                                InfinityGroupDivider(startInset = InfinitySpacing.RowHorizontal + 36.dp + 12.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class ProtoSubmitterEntry(
    val fileName: String,
    val sourceLabel: String,
    val shaShort: String,
    val usedByWorkflows: Int,
    val dryRunLatencyMs: Int,
    val entrySummary: String,
)

@Preview(name = "ComfyUI提交器脚本库-浅色412", widthDp = 412, heightDp = 1320, locale = "zh-rCN")
@Composable
fun comfyHostSubmittersTabPreview() {
    PrototypeThemeSurface {
        val scheme = MaterialTheme.colorScheme
        val domain = MaterialTheme.infinityDomainColors
        val submitters = listOf(
            ProtoSubmitterEntry("submit_h3_motion.py", "桌面 Skill 导入", "a91f3c02", 1, 138, "支持 first / next 阶段、latent_dir 继承与中段重试"),
            ProtoSubmitterEntry("submit_h3.py", "桌面 Skill 导入", "4d82e110", 1, 112, "MiniMax H3 T2VA/I2VA/FL2VA/L2VA/Ref2VA + Guides"),
            ProtoSubmitterEntry("submit_qwen21.py", "桌面 Skill 导入", "9b0472cd", 2, 95, "Qwen Image 2.1 文生图与多图参考编辑"),
            ProtoSubmitterEntry("submit_music3.py", "桌面 Skill 导入", "c3187a4e", 1, 84, "MiniMax Music3 器乐/完整歌曲/四 Seed 试听"),
            ProtoSubmitterEntry("submit_flashvsr.py", "Agent 同步", "e77105bc", 1, 106, "FlashVSR-v1.1 2x 后处理放大，自动识别 SaveVideo"),
            ProtoSubmitterEntry("submit_workflow.py", "内置通用提交器", "01fa9d22", 0, 42, "直接读标准 API JSON 节点映射，零脚本兜底"),
        )

        InfinitySettingsPageLayout(
            title = "工作站 4090 (MAIN)",
            onBack = {},
            horizontalContentPadding = 0.dp,
            actions = {
                ConnectionChip(ComfyConnection.ONLINE)
                Spacer(Modifier.width(8.dp))
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()),
            ) {
                InfinityPanelTabs(
                    tabs = listOf(
                        InfinityPanelTab(ProtoHostTab.WORKFLOWS, "工作流", count = "6"),
                        InfinityPanelTab(ProtoHostTab.SUBMITTERS, "提交器脚本", count = "6"),
                        InfinityPanelTab(ProtoHostTab.HOST_CONFIG, "主机设置"),
                    ),
                    selected = ProtoHostTab.SUBMITTERS,
                    onSelect = {},
                )

                Column(
                    modifier = Modifier.fillMaxWidth().padding(InfinitySpacing.ScreenHorizontal),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = scheme.surfaceContainerLow,
                        border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(Icons.Rounded.Security, null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                            Text(
                                text = "脚本运行于内置 Python 3.14 隔离沙箱，通过本机回环代理自动注入鉴权头；可直接导入桌面已验证脚本或随 Skill 同步替换。",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }

                    InfinityPillRow(Modifier.fillMaxWidth(), stackedSpacing = 8.dp) {
                        InfinityPillButton(
                            text = "导入提交器脚本",
                            onClick = {},
                            tone = InfinityPillTone.PRIMARY,
                            icon = Icons.Rounded.FileUpload,
                            modifier = Modifier.weight(1f),
                        )
                        InfinityPillButton(
                            text = "全部干跑自检",
                            onClick = {},
                            tone = InfinityPillTone.NEUTRAL,
                            icon = Icons.Rounded.PlayArrow,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    BettboxListGroup {
                        submitters.forEachIndexed { index, sub ->
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(Icons.Rounded.Code, null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                                    InfinityScrollingLine(Modifier.weight(1f)) {
                                        Text(
                                            text = sub.fileName,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = InfinityMonospace),
                                            fontWeight = FontWeight.SemiBold,
                                            color = scheme.onSurface,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                    ProductionStatusChip("自检通过", ProductionTone.SUCCESS)
                                }
                                InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                    Text(
                                        text = sub.entrySummary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                                InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                    InfinityMetaLine(
                                        segments = listOf(
                                            sub.sourceLabel,
                                            "SHA ${sub.shaShort}",
                                            "关联 ${sub.usedByWorkflows} 个工作流",
                                            "${sub.dryRunLatencyMs}ms",
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                InfinityTagScrollRow(spacing = 8.dp, modifier = Modifier.padding(top = 2.dp)) {
                                    InfinityPillButton(text = "干跑自检", onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true)
                                    InfinityPillButton(text = "替换脚本", onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true)
                                    InfinityPillButton(text = "查看源码", onClick = {}, tone = InfinityPillTone.NEUTRAL, compact = true)
                                }
                            }
                            if (index < submitters.lastIndex) InfinityGroupDivider()
                        }
                    }
                }
            }
        }
    }
}

private data class ProtoWorkflowFieldRow(
    val key: String,
    val label: String,
    val typeLabel: String,
    val constraintOrDefault: String,
    val semanticRole: String?,
)

@Composable
internal fun ProtoSingleLineValueRow(label: String, segments: List<String>) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(96.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
        InfinityScrollingLine(Modifier.weight(1f)) {
            if (segments.size <= 1) {
                Text(
                    text = segments.firstOrNull().orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                )
            } else {
                InfinityMetaLine(
                    segments = segments,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun ProtoSingleLineValueRow(label: String, value: String) =
    ProtoSingleLineValueRow(label, listOf(value))

@Preview(name = "万能工作流编辑器-浅色412", widthDp = 412, heightDp = 2120, locale = "zh-rCN")
@Preview(name = "万能工作流编辑器-深色412", widthDp = 412, heightDp = 2120, locale = "zh-rCN", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun comfyWorkflowEditorPreview() {
    PrototypeThemeSurface {
        var name by remember { mutableStateOf("MiniMax H3 Motion Context 接续") }
        var execMode by remember { mutableStateOf(ProtoWorkflowExecMode.STATEFUL_CHAIN) }
        var allowMidRetry by remember { mutableStateOf(true) }
        val scheme = MaterialTheme.colorScheme
        val domain = MaterialTheme.infinityDomainColors

        val contentFields = listOf(
            ProtoWorkflowFieldRow("prompt", "提示词", "多行文本", "必填 · 中文/英文动作与声景描述", "提示词"),
            ProtoWorkflowFieldRow("image_refs", "参考图列表", "有序多图", "0–9 张 · 主体与环境参考", "角色/场景参考图"),
            ProtoWorkflowFieldRow("guides", "时间点锚定图", "帧锚点文件列表", "可选 · 指定 frame_idx", "关键帧锚点"),
            ProtoWorkflowFieldRow("frames", "本段帧数", "整数", "默认 121 (4n+1 · 上限 241)", "帧数"),
        )
        val fixedFields = listOf(
            ProtoWorkflowFieldRow("family", "模型家族", "枚举", "锁定：i2v_t2v", null),
            ProtoWorkflowFieldRow("speed", "采样速度档", "枚举", "锁定：pika4", null),
            ProtoWorkflowFieldRow("context_length", "上下文帧窗", "整数", "锁定：25", null),
            ProtoWorkflowFieldRow("width / height", "输出分辨率", "整数对", "锁定：1280 × 720", null),
            ProtoWorkflowFieldRow("cfg", "引导强度", "小数", "锁定：1.0", null),
            ProtoWorkflowFieldRow("steps", "采样步数", "整数", "锁定：4", null),
            ProtoWorkflowFieldRow("lora_strength", "加速 LoRA 权重", "小数", "锁定：1.0", null),
        )

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
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding),
            ) {
                InfinitySettingsSection(title = "基础与执行契约") {
                    InfinityFieldRow(label = "工作流名称", value = name, onValueChange = { name = it })
                    InfinityGroupDivider()
                    ProtoSingleLineValueRow("所属主机", listOf("工作站 4090 (MAIN)", "在线"))
                    InfinityGroupDivider()
                    ProtoSingleLineValueRow("绑定提交器", listOf("submit_h3_motion.py", "SHA a91f3c02"))
                    InfinityGroupDivider()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("执行模式", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        InfinitySegmentedControl(
                            segments = listOf(
                                InfinitySegment(ProtoWorkflowExecMode.SINGLE, "单次提交"),
                                InfinitySegment(ProtoWorkflowExecMode.STATEFUL_CHAIN, "有状态链式"),
                            ),
                            selected = execMode,
                            onSelect = { execMode = it },
                            fillWidth = true,
                        )
                    }
                }

                if (execMode == ProtoWorkflowExecMode.STATEFUL_CHAIN) {
                    InfinitySettingsSection(
                        title = "链式阶段与中段接续策略",
                        footer = "若同槽位被新分支覆写，旧兄弟分支仍保留生成视频供对比回放，并自动标记不可继续下游生成。".keepCjkTailTogether(),
                    ) {
                        ProtoSingleLineValueRow("声明阶段", listOf("first (建立首段)", "next (潜空间接续)"))
                        InfinityGroupDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                    Text("允许从任意中间段分支重试", style = MaterialTheme.typography.bodyLarge, maxLines = 1, softWrap = false)
                                }
                                InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                    Text("可选择历史任意有效段作为父节点重跑", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                                }
                            }
                            InfinitySwitch(checked = allowMidRetry, onCheckedChange = { allowMidRetry = it })
                        }
                        InfinityGroupDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("中段接续自动锁定（防参数漂移）", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                            InfinityTagScrollRow(spacing = 6.dp) {
                                listOf("latent_dir", "clip_index-1", "width", "height", "family", "context_length").forEach { key ->
                                    PrototypeMiniTag(key, emphasized = true)
                                }
                            }
                        }
                    }
                }

                InfinitySettingsSection(
                    title = "内容字段 · 共 4 项（生成时按次填写）", // wrap-check: allow 分区标题写明数量分类
                    footer = "支持任意数量与复杂类型；常规调用直接填写，无需单独审批。",
                ) {
                    contentFields.forEachIndexed { index, field ->
                        WorkflowFieldSummaryRow(field, locked = false)
                        if (index < contentFields.lastIndex) InfinityGroupDivider()
                    }
                    InfinityGroupDivider()
                    InfinitySettingsItem(icon = Icons.Rounded.Add, title = "添加内容字段", onClick = {}, titleColor = scheme.primary)
                }

                InfinitySettingsSection(
                    title = "工作流固定字段 · 共 7 项（已验证基线）", // wrap-check: allow 分区标题写明数量分类
                    footer = "常规调用隐藏且直接沿用锁定值；若实验中临时更改任一项，提交前将触发一次性 diff 审批卡，且不会改写原工作流基线。",
                ) {
                    fixedFields.forEachIndexed { index, field ->
                        WorkflowFieldSummaryRow(field, locked = true)
                        if (index < fixedFields.lastIndex) InfinityGroupDivider()
                    }
                    InfinityGroupDivider()
                    InfinitySettingsItem(icon = Icons.Rounded.Add, title = "添加固定字段", onClick = {}, titleColor = domain.onWarningContainer)
                }
            }
        }
    }
}

@Composable
private fun WorkflowFieldSummaryRow(field: ProtoWorkflowFieldRow, locked: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = {})
            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (locked) Icons.Rounded.Lock else Icons.Rounded.Tune,
            contentDescription = null,
            tint = if (locked) scheme.onSurfaceVariant else scheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            InfinityScrollingLine(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(field.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = scheme.onSurface, maxLines = 1, softWrap = false)
                    Text(
                        text = field.key,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            InfinityTagScrollRow(spacing = 6.dp) {
                PrototypeMiniTag(field.typeLabel, emphasized = !locked)
                field.semanticRole?.let { PrototypeMiniTag("映射：$it", emphasized = true) }
            }
            InfinityScrollingLine(Modifier.fillMaxWidth()) {
                Text(field.constraintOrDefault, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, softWrap = false)
            }
        }
        Icon(Icons.Rounded.Edit, contentDescription = "编辑字段", tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

private data class ProtoFieldValueType(
    val id: String,
    val label: String,
    val icon: ImageVector,
)

@OptIn(ExperimentalLayoutApi::class)
@Preview(name = "自定义字段表单-浅色412", widthDp = 412, heightDp = 1100, locale = "zh-rCN")
@Composable
fun comfyFieldEditorSheetPreview() {
    PrototypeThemeSurface {
        var fieldKey by remember { mutableStateOf("guides") }
        var fieldLabel by remember { mutableStateOf("时间点锚定图") }
        var mutability by remember { mutableStateOf(ProtoFieldMutability.CONTENT) }
        var selectedType by remember { mutableStateOf("timed_files") }
        var required by remember { mutableStateOf(false) }
        val scheme = MaterialTheme.colorScheme

        val valueTypes = listOf(
            ProtoFieldValueType("string", "单行文本", Icons.Rounded.ShortText),
            ProtoFieldValueType("text", "多行文本", Icons.Rounded.Subject),
            ProtoFieldValueType("int", "整数", Icons.Rounded.Numbers),
            ProtoFieldValueType("float", "小数", Icons.Rounded.Tune),
            ProtoFieldValueType("bool", "开关", Icons.Rounded.ToggleOn),
            ProtoFieldValueType("enum", "枚举", Icons.Rounded.ViewList),
            ProtoFieldValueType("json", "JSON", Icons.Rounded.DataObject),
            ProtoFieldValueType("image", "图片文件", Icons.Rounded.Image),
            ProtoFieldValueType("video", "视频文件", Icons.Rounded.VideoFile),
            ProtoFieldValueType("audio", "音频文件", Icons.Rounded.AudioFile),
            ProtoFieldValueType("file_list", "有序多图", Icons.Rounded.Collections),
            ProtoFieldValueType("timed_files", "帧锚点列表", Icons.Rounded.BurstMode),
        )

        InfinitySettingsPageLayout(title = "编辑自定义字段", onBack = {}) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding),
            ) {
                InfinitySettingsSection(title = "字段标识") {
                    InfinityFieldRow(label = "字段键名 (key)", value = fieldKey, onValueChange = { fieldKey = it }, monospace = true)
                    InfinityGroupDivider()
                    InfinityFieldRow(label = "显示名称", value = fieldLabel, onValueChange = { fieldLabel = it })
                }

                InfinitySettingsSection(
                    title = "字段权限级别",
                    footer = "内容字段按任务填写；工作流固定字段在常规生成时锁定，实验性改值自动弹出审批卡。",
                ) {
                    Column(Modifier.padding(12.dp)) {
                        InfinitySegmentedControl(
                            segments = listOf(
                                InfinitySegment(ProtoFieldMutability.CONTENT, "内容字段（按次）"),
                                InfinitySegment(ProtoFieldMutability.FIXED, "工作流固定字段"),
                            ),
                            selected = mutability,
                            onSelect = { mutability = it },
                            fillWidth = true,
                        )
                    }
                }

                InfinitySettingsSection(title = "值类型（全面支持标量与多模态文件）") {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        valueTypes.forEach { item ->
                            InfinityChip(
                                label = item.label,
                                selected = selectedType == item.id,
                                onClick = { selectedType = item.id },
                                leading = {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = if (selectedType == item.id) scheme.primary else scheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                    }
                }

                InfinitySettingsSection(
                    title = "约束与制作面板语义绑定",
                    footer = "语义绑定为可选项；即使不映射制作面板字段，用户与智能体仍可在工作流调用表单中直接填写该字段。",
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("提交时必填", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        InfinitySwitch(checked = required, onCheckedChange = { required = it })
                    }
                    InfinityGroupDivider()
                    ProtoSingleLineValueRow("数量范围", "0–6 项 ({ frame_idx, image_path })")
                    InfinityGroupDivider()
                    ProtoSingleLineValueRow("制作面板语义", "关键帧时间点锚点 (GUIDES)")
                }

                InfinityPillRow(Modifier.fillMaxWidth().padding(top = 4.dp), stackedSpacing = 8.dp) {
                    InfinityPillButton(text = "删除字段", onClick = {}, tone = InfinityPillTone.DANGER, modifier = Modifier.weight(1f))
                    InfinityPillButton(text = "保存字段", onClick = {}, tone = InfinityPillTone.PRIMARY, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
