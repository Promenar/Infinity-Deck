package com.promenar.infinity.ui.preview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.InfinityIdentifierText
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySectionFooterCard
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.SettingsSectionHeader
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.forReading
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.theme.keepCjkTailTogether

/**
 * 统一诊断（单会话 Trace + ADB 调试桥）与资料库读写授权界面高保真隔离原型 v1.0：
 * 1. `KnowledgeAccessApprovalBody` 扩展：对比展示 `read_only`（默认）与 `read_write`（可写资料）审批卡及挂载行状态；
 * 2. `DeveloperScreen` 扩展：新增「诊断与调试桥」分组（单会话轨迹 Trace JSONL 导出、全局诊断包、ADB 实时调试桥说明）。
 */
@Composable
private fun DiagProtoFrame(
    mode: InfinityThemeMode,
    content: @Composable () -> Unit,
) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = mode,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                content()
            }
        }
    }
}

private data class ProtoKnowledgeApprovalSpec(
    val headerStatus: String,
    val reason: String,
    val kbTitle: String,
    val documentCount: Int,
    val mountPath: String,
    val accessModeLabel: String,
    val note: String,
    val noteIcon: ImageVector,
    val readWrite: Boolean,
    val confirmLabel: String,
)

@Composable
private fun ProtoKnowledgeApprovalCard(spec: ProtoKnowledgeApprovalSpec) {
    val accent = if (spec.readWrite) {
        MaterialTheme.infinityDomainColors.warning
    } else {
        MaterialTheme.infinityDomainColors.info
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = spec.headerStatus,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                ProtoBadge(label = spec.accessModeLabel, accent = accent)
            }

            InfinityIdentifierText(
                text = "request_knowledge_access",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Text(
                    text = spec.reason.keepCjkTailTogether(),
                    style = MaterialTheme.typography.bodyMedium.forReading(),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ProtoKeyValueRow(
                    label = "知识库",
                    value = {
                        Text(
                            text = "${spec.kbTitle}（${spec.documentCount}\u00A0个文档）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                )
                ProtoKeyValueRow(
                    label = "挂载到",
                    value = {
                        InfinityIdentifierText(
                            text = spec.mountPath,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                )
            }

            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = spec.noteIcon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.padding(top = 1.dp).size(16.dp),
                )
                Text(
                    text = spec.note.keepCjkTailTogether(),
                    style = MaterialTheme.typography.bodySmall.forReading(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            InfinityPillRow(modifier = Modifier.fillMaxWidth(), spacing = 10.dp) {
                InfinityPillButton(text = "拒绝", onClick = {})
                InfinityPillButton(
                    text = spec.confirmLabel,
                    onClick = {},
                    tone = InfinityPillTone.PRIMARY,
                )
            }
        }
    }
}

@Composable
private fun ProtoKeyValueRow(label: String, value: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        value()
    }
}

@Composable
private fun ProtoBadge(label: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = accent.copy(alpha = 0.14f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun KnowledgeApprovalAndMountShowcase() {
    SettingsSectionHeader(title = "一、知识库授权审批卡（只读 vs 可写对照）")

    ProtoKnowledgeApprovalCard(
        ProtoKnowledgeApprovalSpec(
            headerStatus = "需要批准：读取知识库",
            reason = "需要查阅《帝国暗面》设定文档并核对 02-12 录音整理稿全文。",
            kbTitle = "RAG Workspace",
            documentCount = 11,
            mountPath = "/mnt/knowledge",
            accessModeLabel = "只读挂载",
            note = "只读可查看资料库全部文档；录音整理稿（.md）支持带版本校验的编辑与工作台同步；普通文档与原始音频不可修改，且不暴露给 Linux 终端命令。",
            noteIcon = Icons.Rounded.Lock,
            readWrite = false,
            confirmLabel = "批准只读",
        ),
    )

    ProtoKnowledgeApprovalCard(
        ProtoKnowledgeApprovalSpec(
            headerStatus = "需要批准：读写知识库",
            reason = "需要在知识库中新建整理笔记、归类设定文档，并将过时草稿移至新目录。",
            kbTitle = "RAG Workspace",
            documentCount = 11,
            mountPath = "/mnt/knowledge",
            accessModeLabel = "读写授权",
            note = "读写授权允许文件工具创建、修改、移动与经审批删除资料文档并自动同步检索索引；录音原始音频（.wav）仍保持只读；不会直接挂载到 Linux 终端命令。",
            noteIcon = Icons.Rounded.EditNote,
            readWrite = true,
            confirmLabel = "批准读写",
        ),
    )

    SettingsSectionHeader(title = "二、已挂载文件夹面板 · 权限标识")

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            InfinitySettingsItem(
                icon = Icons.AutoMirrored.Rounded.LibraryBooks,
                title = "knowledge（RAG Workspace）",
                subtitleSegments = listOf("知识库", "读写授权", "仅文件工具可用"),
                onClick = {},
                showChevron = true,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            InfinitySettingsItem(
                icon = Icons.AutoMirrored.Rounded.LibraryBooks,
                title = "archive（历史资料）",
                subtitleSegments = listOf("知识库", "只读", "仅文件工具可用"),
                onClick = {},
                showChevron = true,
            )
        }
    }
}

@Composable
private fun DeveloperDiagnosticsShowcase(wide: Boolean) {
    val containerModifier = if (wide) {
        Modifier.fillMaxWidth().widthIn(max = 640.dp)
    } else {
        Modifier.fillMaxWidth()
    }
    Column(
        modifier = containerModifier,
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
    ) {
        InfinitySettingsSection(title = "开发工具") {
            InfinitySettingsItem(
                icon = Icons.Rounded.Science,
                title = "视觉实验室",
                subtitle = "高保真 UI 组件与多状态交互对照环境",
                onClick = {},
                showChevron = true,
            )
        }

        InfinitySettingsSection(title = "诊断与调试桥") {
            InfinitySettingsItem(
                icon = Icons.Rounded.Timeline,
                title = "导出会话执行轨迹（Trace JSONL）",
                subtitleSegments = listOf("符号链接阻断文件工具", "8 步工具调用", "含关联后端事件"),
                subtitleMaxLines = 2,
                onClick = {},
                showChevron = true,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            InfinitySettingsItem(
                icon = Icons.Rounded.BugReport,
                title = "导出全局运行诊断包",
                subtitleSegments = listOf("分片 JSONL 快照", "最近 2 MiB", "启动与工具/LLM 事件"),
                subtitleMaxLines = 2,
                onClick = {},
                trailingContent = {
                    Icon(
                        imageVector = Icons.Rounded.FileDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            InfinitySettingsItem(
                icon = Icons.Rounded.Terminal,
                title = "ADB 实时调试桥（dumpsys）",
                subtitle = "仅本地 USB ADB 权限与开发者模式开启时响应，严格脱敏凭据与正文",
                subtitleMaxLines = 2,
                onClick = {},
                trailingContent = {
                    Switch(checked = true, onCheckedChange = {})
                },
            )
        }

        InfinitySectionFooterCard(
            text = "USB 连接后可免界面直接采集结构化日志：\n" +
                "adb shell dumpsys activity provider com.promenar.infinity/.startup.diagnostics.DiagnosticDumpProvider --recent --since 0\n" +
                "adb shell dumpsys activity provider com.promenar.infinity/.startup.diagnostics.DiagnosticDumpProvider --trace latest",
            icon = Icons.Rounded.Code,
            selectable = true,
        )

        InfinitySettingsSection(title = "模式控制") {
            InfinitySettingsItem(
                icon = Icons.Rounded.DeveloperMode,
                title = "开发者选项",
                subtitle = "关闭后将同步停止 Release 环境 ADB 调试桥输出",
                subtitleMaxLines = 2,
                onClick = {},
                trailingContent = {
                    Switch(checked = true, onCheckedChange = {})
                },
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Timeline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "选择要导出轨迹的测试会话",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                TraceSessionCandidateRow(
                    title = "符号链接阻断文件工具",
                    segments = listOf("LINUX", "11/18 22:38", "8 步工具调用", "含 1 次预期只读拒绝"),
                    selected = true,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                TraceSessionCandidateRow(
                    title = "Simple Addition Calculation",
                    segments = listOf("BUILTIN", "11/18 16:49", "2 步工具调用", "0 失败"),
                    selected = false,
                )
                Spacer(Modifier.height(2.dp))
                InfinityPillRow(modifier = Modifier.fillMaxWidth(), spacing = 10.dp) {
                    InfinityPillButton("取消", onClick = {})
                    InfinityPillButton("分享 Trace JSONL", onClick = {}, tone = InfinityPillTone.PRIMARY)
                }
            }
        }
    }
}

@Composable
private fun TraceSessionCandidateRow(
    title: String,
    segments: List<String>,
    selected: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f) else Color.Transparent,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            InfinityMetaLine(
                segments = segments,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(name = "知识库分级授权 · 深色 360dp", widthDp = 360, showBackground = true)
@Composable
internal fun ProtoKnowledgeAccessDarkPreview() {
    DiagProtoFrame(InfinityThemeMode.DARK) {
        KnowledgeApprovalAndMountShowcase()
    }
}

@Preview(name = "知识库分级授权 · 浅色 411dp", widthDp = 411, showBackground = true)
@Composable
internal fun ProtoKnowledgeAccessLightPreview() {
    DiagProtoFrame(InfinityThemeMode.LIGHT) {
        KnowledgeApprovalAndMountShowcase()
    }
}

@Preview(name = "开发者诊断与 Trace · 深色 360dp", widthDp = 360, showBackground = true)
@Composable
internal fun ProtoDeveloperDiagnosticsDarkPreview() {
    DiagProtoFrame(InfinityThemeMode.DARK) {
        DeveloperDiagnosticsShowcase(wide = false)
    }
}

@Preview(name = "开发者诊断与 Trace · 浅色内屏 680dp", widthDp = 680, showBackground = true)
@Composable
internal fun ProtoDeveloperDiagnosticsWideLightPreview() {
    DiagProtoFrame(InfinityThemeMode.LIGHT) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            DeveloperDiagnosticsShowcase(wide = true)
        }
    }
}
