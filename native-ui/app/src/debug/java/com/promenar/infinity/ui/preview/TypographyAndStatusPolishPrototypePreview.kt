package com.promenar.infinity.ui.preview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.domain.export.SessionExportFormat
import com.promenar.infinity.recording.RecordingOrganizeEngine
import com.promenar.infinity.recording.RecordingOrganizeSource
import com.promenar.infinity.recording.RecordingOrganizeTone
import com.promenar.infinity.ui.common.InfinityConfirmDialogCard
import com.promenar.infinity.ui.common.InfinityEmbeddedNoteCard
import com.promenar.infinity.ui.common.InfinityEmbeddedNoteRow
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityNoteTone
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySectionFooterCard
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.ModelBrandIcon
import com.promenar.infinity.ui.common.SettingsSectionHeader
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.forReading

/**
 * 全站「单字孤行（Widow）与状态卡排版精致化」高保真隔离原型 v1.0：
 * 1. 录音整理状态卡重设计：动静分离（动态倒计时移至底部同轴左侧，tnum 等宽数字），并与下方版本标签信息去重；
 * 2. 导出面板重设计：预计输出改用 InfinityMetaLine 原子分段（\u00A0 绑定数字与单位），页脚收编进 InfinitySectionFooterCard；
 * 3. 全站通用防孤字引擎预览：覆盖每句句末（。！？；）双字 U+2060 连写 + 数字单位 \u00A0 不拆行（在 1.3x 大字号验证）。
 */

private val CjkOrphanUnitPattern = Regex("""(\d)\s+([%％]|kB|MB|GB|TB|ms|px|dp|sp|[秒分时天页张条轮段行个项次位])(?![A-Za-z0-9])""")
private val SentenceEndPunc = setOf('。', '！', '？', '；', '!', '?', ';')

private fun Char.isCjkChar(): Boolean = when (Character.UnicodeScript.of(code)) {
    Character.UnicodeScript.HAN,
    Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA,
    Character.UnicodeScript.HANGUL,
    -> true
    else -> false
}

/** 增强版中文防孤字排版器：句末两汉字以 U+2060 连写（覆盖多句句末），数字与单位用 \u00A0 绑定。 */
private fun String.refineCjkTypography(): String {
    if (isEmpty()) return this
    val bound = CjkOrphanUnitPattern.replace(this) { m -> "${m.groupValues[1]}\u00A0${m.groupValues[2]}" }
    val out = StringBuilder(bound.length + 8)
    var i = 0
    while (i < bound.length) {
        val ch = bound[i]
        out.append(ch)
        // 若当前汉字后紧跟另一个汉字，且第二个汉字后是句末标点或串尾，则插入 U+2060 避免单字孤行。
        if (ch.isCjkChar() && i + 1 < bound.length && bound[i + 1].isCjkChar()) {
            val afterNext = bound.getOrNull(i + 2)
            val atEndOrSentenceBreak = afterNext == null || afterNext in SentenceEndPunc || afterNext == '\n'
            if (atEndOrSentenceBreak) {
                out.append('\u2060')
            }
        }
        i++
    }
    return out.toString()
}

@Composable
private fun TypoPrototypeFrame(
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
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                content()
            }
        }
    }
}

private data class ProtoStatusCardSpec(
    val tone: RecordingOrganizeTone,
    val icon: ImageVector?,
    val titleSegments: List<String>,
    val engine: RecordingOrganizeEngine,
    val metaSegments: List<String>,
    val note: String?,
    val footerStatus: String? = null,
    val footerIcon: ImageVector? = null,
    val progress: Float? = null,
    val spinning: Boolean = false,
    val secondaryAction: String? = null,
    val primaryAction: String? = null,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProtoRefinedRecordingStatusCard(spec: ProtoStatusCardSpec) {
    val scheme = MaterialTheme.colorScheme
    val (container, accent) = when (spec.tone) {
        RecordingOrganizeTone.Error -> scheme.errorContainer.copy(alpha = 0.55f) to scheme.error
        RecordingOrganizeTone.Running -> scheme.surfaceContainer to scheme.primary
        else -> scheme.surfaceContainer to scheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = container,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    if (spec.spinning) {
                        CircularProgressIndicator(Modifier.size(36.dp), color = accent, strokeWidth = 2.dp)
                        ModelBrandIcon(spec.engine.model, Modifier.size(18.dp))
                    } else if (spec.icon != null) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(accent.copy(alpha = 0.14f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(spec.icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = accent)
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    InfinityMetaLine(
                        segments = spec.titleSegments,
                        style = InfinityTypography.titleSmall,
                        color = scheme.onSurface,
                    )
                    val routeText = if (spec.engine.cloud) "云端" else "本地"
                    val meta = buildList {
                        add(routeText)
                        if (spec.engine.cloud && spec.engine.provider.isNotBlank()) add(spec.engine.provider)
                        add(spec.engine.model)
                        addAll(spec.metaSegments)
                    }
                    InfinityMetaLine(
                        segments = meta,
                        style = InfinityTypography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            spec.progress?.let { value ->
                LinearProgressIndicator(
                    progress = { value },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = accent,
                    trackColor = scheme.surfaceContainerHighest,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
            spec.note?.let { raw ->
                Text(
                    text = raw.refineCjkTypography(),
                    style = InfinityTypography.bodySmall.forReading(),
                    color = scheme.onSurfaceVariant,
                )
            }
            if (spec.footerStatus != null || spec.primaryAction != null || spec.secondaryAction != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (spec.footerStatus != null) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            spec.footerIcon?.let {
                                Icon(
                                    imageVector = it,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = scheme.onSurfaceVariant.copy(alpha = 0.85f),
                                )
                            }
                            Text(
                                text = spec.footerStatus.refineCjkTypography(),
                                style = InfinityTypography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        spec.secondaryAction?.let {
                            InfinityPillButton(it, onClick = {}, compact = true)
                        }
                        spec.primaryAction?.let {
                            InfinityPillButton(
                                text = it,
                                onClick = {},
                                compact = true,
                                tone = if (spec.tone == RecordingOrganizeTone.Running) {
                                    InfinityPillTone.NEUTRAL
                                } else {
                                    InfinityPillTone.PRIMARY
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoRecordingVersionLabel(source: RecordingOrganizeSource) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Rounded.History, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        InfinityMetaLine(
            segments = listOf("当前版本", "今天 17:09", "本地", source.model),
            style = InfinityTypography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(name = "状态卡重设计 · 深色 360dp", widthDp = 360, showBackground = true)
@Composable
internal fun ProtoRecordingStatusCardRefinedDarkPreview() {
    val cloud = RecordingOrganizeEngine(cloud = true, provider = "NEWAPI", model = "deepseek-v4.1-flash")
    val oldSource = RecordingOrganizeSource(cloud = false, model = "Qwen3-0.6B-Q8_0-23749fe.gguf", completedAt = 1L)
    TypoPrototypeFrame(InfinityThemeMode.DARK) {
        Text("① 失败保留旧稿（动静分离 + 信息去重）", style = InfinityTypography.labelMedium, color = MaterialTheme.colorScheme.primary)
        ProtoRefinedRecordingStatusCard(
            ProtoStatusCardSpec(
                tone = RecordingOrganizeTone.Error,
                icon = Icons.Rounded.ErrorOutline,
                titleSegments = listOf("整理失败", "模型返回错误"),
                engine = cloud,
                metaSegments = listOf("17:15", "等待 1 秒无响应"),
                note = "下方已有整理稿未被替换。",
                footerStatus = "55 秒后自动重试",
                footerIcon = Icons.Rounded.Schedule,
                secondaryAction = "改用本地",
                primaryAction = "重试",
            ),
        )
        ProtoRecordingVersionLabel(oldSource)

        Text("② 重新整理进行中（去重文案）", style = InfinityTypography.labelMedium, color = MaterialTheme.colorScheme.primary)
        ProtoRefinedRecordingStatusCard(
            ProtoStatusCardSpec(
                tone = RecordingOrganizeTone.Running,
                icon = null,
                titleSegments = listOf("正在重新整理", "第 2 步，共 4 步"),
                engine = cloud,
                metaSegments = listOf("汇总摘要", "已用 12 秒"),
                progress = 0.5f,
                spinning = true,
                note = "生成完成后将自动替换下方已有整理稿。",
                primaryAction = "取消",
            ),
        )

        Text("③ 无旧稿失败（倒计时同轴左侧）", style = InfinityTypography.labelMedium, color = MaterialTheme.colorScheme.primary)
        ProtoRefinedRecordingStatusCard(
            ProtoStatusCardSpec(
                tone = RecordingOrganizeTone.Error,
                icon = Icons.Rounded.ErrorOutline,
                titleSegments = listOf("整理失败", "请求超时"),
                engine = cloud,
                metaSegments = listOf("17:16", "等待 75 秒无响应"),
                note = "逐字稿已完整保存，可重试或改用本地整理。",
                footerStatus = "30 秒后自动重试",
                footerIcon = Icons.Rounded.Schedule,
                secondaryAction = "改用本地",
                primaryAction = "重试",
            ),
        )
    }
}

@Preview(name = "状态卡重设计 · 浅色 411dp", widthDp = 411, showBackground = true)
@Composable
internal fun ProtoRecordingStatusCardRefinedLightWidePreview() {
    val cloud = RecordingOrganizeEngine(cloud = true, provider = "NEWAPI", model = "deepseek-v4.1-flash")
    val oldSource = RecordingOrganizeSource(cloud = false, model = "Qwen3-0.6B-Q8_0-23749fe.gguf", completedAt = 1L)
    TypoPrototypeFrame(InfinityThemeMode.LIGHT) {
        ProtoRefinedRecordingStatusCard(
            ProtoStatusCardSpec(
                tone = RecordingOrganizeTone.Error,
                icon = Icons.Rounded.ErrorOutline,
                titleSegments = listOf("整理失败", "模型返回错误"),
                engine = cloud,
                metaSegments = listOf("17:15", "等待 1 秒无响应"),
                note = "下方已有整理稿未被替换。",
                footerStatus = "55 秒后自动重试",
                footerIcon = Icons.Rounded.Schedule,
                secondaryAction = "改用本地",
                primaryAction = "重试",
            ),
        )
        ProtoRecordingVersionLabel(oldSource)
    }
}

@Preview(name = "导出面板重设计 · 360dp", widthDp = 360, showBackground = true)
@Composable
internal fun ProtoExportSheetRefinedPreview() {
    TypoPrototypeFrame(InfinityThemeMode.DARK) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("导出录音文档", style = InfinityTypography.headlineSmall)
                InfinityMetaLine(
                    segments = listOf("录音 2026-11-18 02:12", "01:38", "2 段转写"),
                    style = InfinityTypography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsSectionHeader(title = "格式")
                        InfinitySegmentedControl(
                            segments = listOf(
                                InfinitySegment(SessionExportFormat.PNG, "PNG"),
                                InfinitySegment(SessionExportFormat.PDF, "PDF"),
                                InfinitySegment(SessionExportFormat.MARKDOWN, "MD"),
                                InfinitySegment(SessionExportFormat.HTML, "HTML"),
                                InfinitySegment(SessionExportFormat.TXT, "TXT"),
                            ),
                            selected = SessionExportFormat.PNG,
                            onSelect = {},
                            fillWidth = true,
                            compact = true,
                        )
                    }
                }

                // 预计输出：改用 InfinityMetaLine 原子分段，避免出现 0.99 / MB 拆行。
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("预计输出（PNG）", style = InfinityTypography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        InfinityMetaLine(
                            segments = listOf(
                                "1\u00A0张 1:1 全分辨率完整长图",
                                "≥1440px\u00A0宽",
                                "约 0.99\u00A0MB",
                            ),
                            style = InfinityTypography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("预计输出（PDF）", style = InfinityTypography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        InfinityMetaLine(
                            segments = listOf("自适应矢量分页", "约 2\u00A0页", "约 83\u00A0kB"),
                            style = InfinityTypography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                // 导出页脚统一收编进 InfinitySectionFooterCard，杜绝“完整长\n图。”单字孤行。
                InfinitySectionFooterCard(
                    text = "按内容自动拓展画布宽高，1440px 基准起 1:1 导出单张完整长图。",
                    selectable = false,
                )

                InfinityPillRow(modifier = Modifier.fillMaxWidth(), spacing = 10.dp) {
                    InfinityPillButton("保存到...", onClick = {}, icon = Icons.Rounded.FolderOpen)
                    InfinityPillButton("分享", onClick = {}, icon = Icons.Rounded.Share, tone = InfinityPillTone.PRIMARY)
                }
            }
        }
    }
}

@Preview(name = "全站防孤字引擎 · 大字号 1.3x 360dp", widthDp = 360, fontScale = 1.3f, showBackground = true)
@Composable
internal fun ProtoSiteWideOrphanGuardPreview() {
    TypoPrototypeFrame(InfinityThemeMode.DARK) {
        Text("① 确认弹窗多句防孤字", style = InfinityTypography.labelMedium, color = MaterialTheme.colorScheme.primary)
        InfinityConfirmDialogCard(
            title = "启用云端整理",
            message = "NEWAPI · deepseek-v4.1-flash 会收到待整理的逐字稿、相关上下文和术语表。录音保存在本机。".refineCjkTypography(),
            confirmText = "允许发送文本",
            cancelText = "取消",
            onConfirm = {},
            onCancel = {},
            icon = Icons.Rounded.CloudQueue,
        )

        Text("② 内联编辑历史轮次提示", style = InfinityTypography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "此后还有 3\u00A0轮对话，就地重新生成将替换这些后续内容；也可新建分支探索，磁盘工作区文件不受影响。".refineCjkTypography(),
                style = InfinityTypography.bodySmall.forReading(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text("③ 嵌入式提示卡（InfinityEmbeddedNoteCard）", style = InfinityTypography.labelMedium, color = MaterialTheme.colorScheme.primary)
        InfinityEmbeddedNoteCard(
            rows = listOf(
                InfinityEmbeddedNoteRow(Icons.Rounded.Info, "原始音频和声纹保留在此设备。".refineCjkTypography()),
                InfinityEmbeddedNoteRow(Icons.Rounded.Schedule, "55 秒后将自动重试。".refineCjkTypography(), InfinityNoteTone.PRIMARY),
            ),
        )
    }
}
